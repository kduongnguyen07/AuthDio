package com.example.mdownload.core.audio

import android.content.Context
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.RepositoryLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Persistent, file-versioned, LRU-bounded cache and coordinator for Audio Quality Inspection,
 * ReplayGain tag lookups, and optional EBU R128 loudness scans.
 *
 * - Hydrates cache from disk asynchronously off the Main Thread (`loadAsync = true`).
 * - Limits FFprobe/FFmpeg external process concurrency via a `Semaphore(1)`.
 * - Cache keys include URI + file size + `dateModifiedSec` + schema version so any file
 *   edit automatically invalidates stale entries without re-scanning unchanged files.
 * - Enforces a bounded LRU cap ([maxCachedEntries]) in both memory and disk persistence so
 *   regenerable analysis metadata never grows unbounded across large libraries.
 * - Caches lightweight tag-only ReplayGain lookups in a separate bounded in-memory map so
 *   replaying tracks without full FFprobe inspection avoids redundant file header re-reads.
 */
class AudioAnalysisRepository(
    private val appContext: Context,
    private val cacheFile: File = File(
        File(appContext.filesDir, "hifideck_data"),
        "audio_analysis_cache.json"
    ),
    private val maxCachedEntries: Int = MAX_CACHED_ENTRIES,
    loadAsync: Boolean = true,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        private const val SCHEMA_VERSION = 1
        const val MAX_CACHED_ENTRIES = 1000
    }

    private val processSemaphore = Semaphore(1)
    private val diskLock = Any()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val readyDeferred = CompletableDeferred<Unit>()

    private val _loadState = MutableStateFlow(
        if (loadAsync) RepositoryLoadState.LOADING else RepositoryLoadState.READY
    )
    val loadState: StateFlow<RepositoryLoadState> = _loadState.asStateFlow()

    // Keyed by track.uri -> CacheRecord(versionKey, info, lastAccessMs)
    private data class CacheRecord(
        val versionKey: String,
        val info: AudioQualityInfo,
        @Volatile var lastAccessMs: Long = info.inspectedAtMs
    )

    // Bounded in-memory tag-only ReplayGain cache (versionKey -> ReplayGainInfo?, null wrapped)
    private data class TagRgEntry(
        val uri: String,
        val replayGain: ReplayGainInfo?,
        @Volatile var lastAccessMs: Long
    )

    private val cache = ConcurrentHashMap<String, CacheRecord>()
    private val tagOnlyReplayGainCache = ConcurrentHashMap<String, TagRgEntry>()

    @Volatile
    private var hydrated: Boolean = false

    private val _cacheVersion = MutableStateFlow(0L)
    val cacheVersion: StateFlow<Long> = _cacheVersion.asStateFlow()

    init {
        if (loadAsync) {
            scope.launch(ioDispatcher) {
                hydrateFromDisk()
            }
        } else {
            hydrateFromDisk()
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun cachedEntryCount(): Int = cache.size

    fun versionKeyFor(track: LibraryTrack): String =
        "${track.uri}|${track.sizeBytes}|${track.dateModifiedSec}|v$SCHEMA_VERSION"

    fun getCachedInfo(track: LibraryTrack): AudioQualityInfo? {
        val rec = cache[track.uri] ?: return null
        return if (rec.versionKey == versionKeyFor(track)) {
            rec.lastAccessMs = System.currentTimeMillis()
            rec.info
        } else {
            null
        }
    }

    fun getCachedSnapshotByUri(): Map<String, AudioQualityInfo> =
        cache.mapValues { it.value.info }

    /**
     * Inserts a pre-computed [AudioQualityInfo] entry directly (used by LRU eviction tests and
     * internal cache updates) while enforcing the bounded LRU cap.
     */
    internal fun putCachedInfoForTest(track: LibraryTrack, info: AudioQualityInfo, persist: Boolean = false) {
        synchronized(diskLock) {
            ensureHydratedLocked()
            val now = System.currentTimeMillis()
            cache[track.uri] = CacheRecord(
                versionKey = versionKeyFor(track),
                info = info,
                lastAccessMs = info.inspectedAtMs.takeIf { it > 0L } ?: now
            )
            evictCacheIfNeededLocked()
            if (persist) persistToDiskLocked()
            _cacheVersion.value += 1L
        }
    }

    suspend fun inspectTrack(
        track: LibraryTrack,
        hasLyrics: Boolean?,
        lyricsSourceLabel: String?,
        forceRefresh: Boolean = false
    ): AudioQualityInfo = withContext(ioDispatcher) {
        ensureHydrated()
        val vKey = versionKeyFor(track)
        if (!forceRefresh) {
            cache[track.uri]?.takeIf { it.versionKey == vKey }?.let { cached ->
                cached.lastAccessMs = System.currentTimeMillis()
                return@withContext cached.info.copy(
                    hasLyrics = hasLyrics ?: cached.info.hasLyrics,
                    lyricsSourceLabel = lyricsSourceLabel ?: cached.info.lyricsSourceLabel
                )
            }
        }

        val inspected = processSemaphore.withPermit {
            AudioInspectorEngine.inspectTrack(
                context = appContext,
                track = track,
                hasLyrics = hasLyrics,
                lyricsSourceLabel = lyricsSourceLabel,
                allowFfprobe = true
            )
        }

        // Preserve any previously scanned loudness measurement if the unchanged file still has no embedded RG tag
        val prevRg = cache[track.uri]?.takeIf { it.versionKey == vKey }?.info?.replayGain
        val merged = if (inspected.replayGain == null && prevRg?.isScannedEstimate == true) {
            inspected.copy(replayGain = prevRg)
        } else {
            inspected
        }

        synchronized(diskLock) {
            cache[track.uri] = CacheRecord(vKey, merged, System.currentTimeMillis())
            tagOnlyReplayGainCache.remove(vKey)
            evictCacheIfNeededLocked()
            persistToDiskLocked()
        }
        _cacheVersion.value += 1L
        merged
    }

    /**
     * Fast ReplayGain lookup for playback: checks versioned full-inspection cache first, then
     * versioned tag-only in-memory cache, otherwise runs pure-Kotlin header/tag reader without
     * launching FFprobe.
     */
    suspend fun resolveReplayGain(track: LibraryTrack): ReplayGainInfo? = withContext(ioDispatcher) {
        ensureHydrated()
        val vKey = versionKeyFor(track)
        cache[track.uri]?.takeIf { it.versionKey == vKey }?.let { cached ->
            cached.lastAccessMs = System.currentTimeMillis()
            return@withContext cached.info.replayGain
        }
        tagOnlyReplayGainCache[vKey]?.let { entry ->
            entry.lastAccessMs = System.currentTimeMillis()
            return@withContext entry.replayGain
        }

        val resolved = ReplayGainMetadataReader.readFromUri(
            context = appContext,
            uriString = track.uri,
            displayName = track.displayName,
            mimeType = track.mimeType
        )
        putTagOnlyReplayGain(vKey, track.uri, resolved)
        resolved
    }

    /**
     * Optional user-initiated offline EBU R128 scan for tracks without ReplayGain metadata.
     * Cancellable, single-flight via Semaphore, cached against file version.
     */
    suspend fun scanLoudnessForTrack(track: LibraryTrack): ReplayGainInfo? = withContext(ioDispatcher) {
        ensureHydrated()
        val vKey = versionKeyFor(track)
        val scanned = processSemaphore.withPermit {
            LoudnessScanner.scanTrackUri(
                context = appContext,
                uriString = track.uri,
                displayName = track.displayName,
                durationMs = track.durationMs.takeIf { it > 0L }
            )
        } ?: return@withContext null

        val existing = cache[track.uri]?.takeIf { it.versionKey == vKey }?.info
        val updatedInfo = (existing ?: AudioInspectorEngine.inspectTrack(
            context = appContext,
            track = track,
            hasLyrics = null,
            lyricsSourceLabel = null,
            allowFfprobe = false
        )).copy(replayGain = scanned)

        synchronized(diskLock) {
            cache[track.uri] = CacheRecord(vKey, updatedInfo, System.currentTimeMillis())
            tagOnlyReplayGainCache.remove(vKey)
            evictCacheIfNeededLocked()
            persistToDiskLocked()
        }
        _cacheVersion.value += 1L
        scanned
    }

    fun isLoudnessScanAvailable(): Boolean = LoudnessScanner.isAvailable(appContext)

    fun invalidate(uriString: String) {
        if (uriString.isBlank()) return
        tagOnlyReplayGainCache.entries.removeIf { it.value.uri == uriString }
        val removed = synchronized(diskLock) {
            ensureHydratedLocked()
            val didRemove = cache.remove(uriString) != null
            if (didRemove) {
                persistToDiskLocked()
            }
            didRemove
        }
        if (removed) {
            _cacheVersion.value += 1L
        }
    }

    fun clearAll() {
        tagOnlyReplayGainCache.clear()
        synchronized(diskLock) {
            hydrated = true
            cache.clear()
            persistToDiskLocked()
            _loadState.value = RepositoryLoadState.READY
            readyDeferred.complete(Unit)
        }
        _cacheVersion.value += 1L
    }

    private fun putTagOnlyReplayGain(vKey: String, uri: String, rg: ReplayGainInfo?) {
        tagOnlyReplayGainCache[vKey] = TagRgEntry(uri = uri, replayGain = rg, lastAccessMs = System.currentTimeMillis())
        val limit = maxCachedEntries.coerceAtLeast(1)
        if (tagOnlyReplayGainCache.size > limit) {
            val overflow = tagOnlyReplayGainCache.size - limit
            val victims = tagOnlyReplayGainCache.entries
                .sortedBy { it.value.lastAccessMs }
                .take(overflow)
            for (v in victims) {
                tagOnlyReplayGainCache.remove(v.key)
            }
        }
    }

    private fun evictCacheIfNeededLocked() {
        val limit = maxCachedEntries.coerceAtLeast(1)
        if (cache.size <= limit) return
        val overflow = cache.size - limit
        val victims = cache.entries
            .sortedBy { it.value.lastAccessMs }
            .take(overflow)
        for (v in victims) {
            cache.remove(v.key)
        }
    }

    private fun hydrateFromDisk() {
        synchronized(diskLock) {
            ensureHydratedLocked()
        }
    }

    private fun ensureHydrated() {
        if (hydrated) return
        synchronized(diskLock) {
            ensureHydratedLocked()
        }
    }

    private fun ensureHydratedLocked() {
        if (hydrated) return
        var failed = false
        if (cacheFile.isFile) {
            runCatching {
                val root = JSONObject(cacheFile.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("entries")
                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val uri = obj.optString("uri", "")
                        val vKey = obj.optString("vkey", "")
                        if (uri.isBlank() || vKey.isBlank()) continue
                        val rgObj = obj.optJSONObject("replay_gain")
                        val rg = if (rgObj != null) {
                            ReplayGainInfo(
                                trackGainDb = if (rgObj.has("track_gain_db")) rgObj.optDouble("track_gain_db").toFloat() else null,
                                albumGainDb = if (rgObj.has("album_gain_db")) rgObj.optDouble("album_gain_db").toFloat() else null,
                                trackPeak = if (rgObj.has("track_peak")) rgObj.optDouble("track_peak").toFloat() else null,
                                albumPeak = if (rgObj.has("album_peak")) rgObj.optDouble("album_peak").toFloat() else null,
                                source = rgObj.optString("source", "Cached"),
                                measuredIntegratedLufs = if (rgObj.has("measured_lufs")) rgObj.optDouble("measured_lufs").toFloat() else null,
                                targetLoudnessLufs = if (rgObj.has("target_lufs")) rgObj.optDouble("target_lufs").toFloat() else null
                            )
                        } else null

                        val inspectedAt = obj.optLong("inspected_at_ms", 0L)
                        val info = AudioQualityInfo(
                            uri = uri,
                            codec = obj.optString("codec").takeIf { it.isNotBlank() },
                            codecProfile = obj.optString("codec_profile").takeIf { it.isNotBlank() },
                            container = obj.optString("container").takeIf { it.isNotBlank() },
                            bitrate = if (obj.has("bitrate")) obj.optLong("bitrate") else null,
                            bitrateMode = BitrateMode.entries.firstOrNull {
                                it.name == obj.optString("bitrate_mode")
                            } ?: BitrateMode.UNKNOWN,
                            sampleRate = if (obj.has("sample_rate")) obj.optInt("sample_rate") else null,
                            channels = if (obj.has("channels")) obj.optInt("channels") else null,
                            channelLayout = obj.optString("channel_layout").takeIf { it.isNotBlank() },
                            bitDepth = if (obj.has("bit_depth")) obj.optInt("bit_depth") else null,
                            sampleFormat = obj.optString("sample_format").takeIf { it.isNotBlank() },
                            durationMs = if (obj.has("duration_ms")) obj.optLong("duration_ms") else null,
                            fileSizeBytes = if (obj.has("file_size")) obj.optLong("file_size") else null,
                            isLossless = if (obj.has("is_lossless")) obj.optBoolean("is_lossless") else null,
                            losslessCaveat = obj.optString("lossless_caveat").takeIf { it.isNotBlank() },
                            fileLocation = obj.optString("file_location").takeIf { it.isNotBlank() },
                            hasEmbeddedArtwork = if (obj.has("has_artwork")) obj.optBoolean("has_artwork") else null,
                            artworkDimensions = obj.optString("artwork_dims").takeIf { it.isNotBlank() },
                            replayGain = rg,
                            inspectionSource = obj.optString("inspection_source", "Cached"),
                            inspectedAtMs = inspectedAt
                        )
                        cache.putIfAbsent(uri, CacheRecord(vKey, info, inspectedAt))
                    }
                    evictCacheIfNeededLocked()
                }
            }.onFailure {
                failed = true
            }
        }
        hydrated = true
        _loadState.value = if (failed) RepositoryLoadState.FAILED else RepositoryLoadState.READY
        readyDeferred.complete(Unit)
        if (cache.isNotEmpty()) {
            _cacheVersion.value += 1L
        }
    }

    private fun persistToDiskLocked() {
        runCatching {
            cacheFile.parentFile?.mkdirs()
            val trimmed = cache.values
                .sortedByDescending { it.lastAccessMs }
                .take(maxCachedEntries.coerceAtLeast(1))
            val arr = JSONArray()
            for (rec in trimmed) {
                val info = rec.info
                val obj = JSONObject().apply {
                    put("uri", info.uri)
                    put("vkey", rec.versionKey)
                    info.codec?.let { put("codec", it) }
                    info.codecProfile?.let { put("codec_profile", it) }
                    info.container?.let { put("container", it) }
                    info.bitrate?.let { put("bitrate", it) }
                    put("bitrate_mode", info.bitrateMode.name)
                    info.sampleRate?.let { put("sample_rate", it) }
                    info.channels?.let { put("channels", it) }
                    info.channelLayout?.let { put("channel_layout", it) }
                    info.bitDepth?.let { put("bit_depth", it) }
                    info.sampleFormat?.let { put("sample_format", it) }
                    info.durationMs?.let { put("duration_ms", it) }
                    info.fileSizeBytes?.let { put("file_size", it) }
                    info.isLossless?.let { put("is_lossless", it) }
                    info.losslessCaveat?.let { put("lossless_caveat", it) }
                    info.fileLocation?.let { put("file_location", it) }
                    info.hasEmbeddedArtwork?.let { put("has_artwork", it) }
                    info.artworkDimensions?.let { put("artwork_dims", it) }
                    put("inspection_source", info.inspectionSource)
                    put("inspected_at_ms", info.inspectedAtMs)
                    info.replayGain?.let { rg ->
                        put("replay_gain", JSONObject().apply {
                            rg.trackGainDb?.let { put("track_gain_db", it.toDouble()) }
                            rg.albumGainDb?.let { put("album_gain_db", it.toDouble()) }
                            rg.trackPeak?.let { put("track_peak", it.toDouble()) }
                            rg.albumPeak?.let { put("album_peak", it.toDouble()) }
                            put("source", rg.source)
                            rg.measuredIntegratedLufs?.let { put("measured_lufs", it.toDouble()) }
                            rg.targetLoudnessLufs?.let { put("target_lufs", it.toDouble()) }
                        })
                    }
                }
                arr.put(obj)
            }
            val root = JSONObject().apply { put("entries", arr) }
            val tmp = File(cacheFile.parentFile, "${cacheFile.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}

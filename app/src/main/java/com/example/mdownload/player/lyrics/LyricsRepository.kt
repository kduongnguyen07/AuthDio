package com.example.mdownload.player.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.mdownload.core.LibraryTrack
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Coordinates HiFiDeck's 5-layer offline-first lyrics discovery & management pipeline:
 *
 * 1. Embedded audio tags ([LyricsTagIO]: ID3v2 USLT/SYLT, FLAC Vorbis, MP4 ©lyr, Ogg/Opus)
 * 2. Sidecar `.lrc` file ([LyricsStorage]: SAF tree, adjacent folder, or app sidecar store)
 * 3. Local JSON lyrics cache ([LyricsStorage])
 * 4. Online provider ([LrcLibLyricsProvider]) gated by [LyricsMatcher] confidence thresholds
 * 5. Manual user import / edit / candidate selection / offset adjustment / `.lrc` export / safe embed
 */
class LyricsRepository(
    private val appContext: Context,
    private val storage: LyricsStorage = LyricsStorage(appContext),
    private val provider: OnlineLyricsProvider = LrcLibLyricsProvider()
) {
    companion object {
        private const val TAG = "LyricsRepository"
        private const val ONLINE_MISS_COOLDOWN_MS = 10 * 60 * 1000L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _uiState = MutableStateFlow(LyricsUiState())
    val uiState: StateFlow<LyricsUiState> = _uiState.asStateFlow()

    private val memoryCache = ConcurrentHashMap<String, TrackLyrics>()
    private val recentOnlineMisses = ConcurrentHashMap<String, Long>()
    private var activeLoadJob: Job? = null

    /**
     * Loads lyrics for the currently playing [track].
     * Always resolves local layers first (Cache/Manual override → Embedded → Sidecar), then
     * optionally queries online LRCLIB when [allowOnlineLookup] is true and no local lyrics exist.
     */
    fun loadForTrack(
        track: LibraryTrack?,
        customTreeUri: String = "",
        allowOnlineLookup: Boolean = true
    ) {
        activeLoadJob?.cancel()
        if (track == null) {
            _uiState.value = LyricsUiState()
            return
        }

        val trackKey = storage.trackKeyFor(track)
        val canEmbed = LyricsTagIO.supportsEmbedding(track.displayName, track.mimeType)

        // Instant in-memory hit
        memoryCache[trackKey]?.let { mem ->
            _uiState.value = LyricsUiState(
                activeTrack = track,
                lyrics = mem,
                isLoading = false,
                canEmbedIntoFile = canEmbed
            )
            return
        }

        _uiState.value = LyricsUiState(
            activeTrack = track,
            lyrics = null,
            isLoading = true,
            canEmbedIntoFile = canEmbed
        )

        activeLoadJob = scope.launch {
            val resolved = resolveLocalLayers(track, customTreeUri)
            if (resolved != null) {
                memoryCache[trackKey] = resolved
                updateStateIfActive(trackKey) {
                    it.copy(
                        lyrics = resolved,
                        isLoading = false,
                        errorMessage = null
                    )
                }
                return@launch
            }

            if (!allowOnlineLookup || isRecentlyMissed(trackKey)) {
                updateStateIfActive(trackKey) {
                    it.copy(isLoading = false)
                }
                return@launch
            }

            // Layer 4: Online LRCLIB lookup (non-blocking, confidence-gated)
            updateStateIfActive(trackKey) { it.copy(isSearchingOnline = true) }
            val query = LyricsMatcher.buildQuery(
                rawTitle = track.title,
                rawArtist = track.artist,
                rawAlbum = track.album,
                durationMs = track.durationMs
            )
            val result = provider.lookup(query)
            result.onSuccess { rawCandidates ->
                val decision = LyricsMatcher.rankAndSelect(query, rawCandidates)
                val auto = decision.bestAutoMatch
                if (auto != null) {
                    val autoLyrics = candidateToTrackLyrics(trackKey, auto)
                    if (autoLyrics != null) {
                        memoryCache[trackKey] = autoLyrics
                        storage.saveCached(autoLyrics)
                        updateStateIfActive(trackKey) {
                            it.copy(
                                lyrics = autoLyrics,
                                candidates = decision.rankedCandidates,
                                isLoading = false,
                                isSearchingOnline = false,
                                errorMessage = null
                            )
                        }
                        return@launch
                    }
                }
                if (decision.rankedCandidates.isEmpty()) {
                    recentOnlineMisses[trackKey] = System.currentTimeMillis()
                }
                updateStateIfActive(trackKey) {
                    it.copy(
                        candidates = decision.rankedCandidates,
                        isLoading = false,
                        isSearchingOnline = false
                    )
                }
            }.onFailure { err ->
                Log.d(TAG, "Online lyrics lookup skipped/failed for ${track.title}: ${err.message}")
                updateStateIfActive(trackKey) {
                    it.copy(
                        isLoading = false,
                        isSearchingOnline = false
                    )
                }
            }
        }
    }

    /** Explicit user-initiated online search (manual query or active track metadata). */
    fun searchOnlineForActiveTrack(
        customTitle: String? = null,
        customArtist: String? = null
    ) {
        val track = _uiState.value.activeTrack ?: return
        val trackKey = storage.trackKeyFor(track)
        recentOnlineMisses.remove(trackKey)

        scope.launch {
            updateStateIfActive(trackKey) {
                it.copy(
                    isSearchingOnline = true,
                    errorMessage = null,
                    statusMessage = null
                )
            }
            val query = if (!customTitle.isNullOrBlank()) {
                LyricsQuery(
                    title = LyricsMatcher.cleanDisplayTitle(customTitle),
                    artist = LyricsMatcher.cleanDisplayArtist(customArtist.orEmpty()),
                    album = track.album,
                    durationSec = (track.durationMs / 1000L).toInt().takeIf { it > 0 }
                )
            } else {
                LyricsMatcher.buildQuery(
                    rawTitle = track.title,
                    rawArtist = track.artist,
                    rawAlbum = track.album,
                    durationMs = track.durationMs
                )
            }

            val result = if (!customTitle.isNullOrBlank()) {
                provider.search(query.title, query.artist)
            } else {
                provider.lookup(query)
            }

            result.onSuccess { rawCandidates ->
                val scored = rawCandidates
                    .filter { it.hasAnyLyrics }
                    .map { it.copy(confidence = LyricsMatcher.scoreCandidate(query, it)) }
                    .sortedWith(
                        compareByDescending<LyricsCandidate> { it.confidence }
                            .thenByDescending { it.isSynced }
                    )

                updateStateIfActive(trackKey) {
                    it.copy(
                        isSearchingOnline = false,
                        candidates = scored,
                        statusMessage = if (scored.isEmpty()) "No online lyrics matches found." else null
                    )
                }
            }.onFailure { err ->
                updateStateIfActive(trackKey) {
                    it.copy(
                        isSearchingOnline = false,
                        errorMessage = "Online search unavailable (${err.message ?: "check internet connection"})."
                    )
                }
            }
        }
    }

    /** Applies a user-selected candidate from the search picker and caches it locally. */
    fun selectCandidate(candidate: LyricsCandidate) {
        val track = _uiState.value.activeTrack ?: return
        val trackKey = storage.trackKeyFor(track)
        val parsed = candidateToTrackLyrics(trackKey, candidate) ?: return
        memoryCache[trackKey] = parsed
        scope.launch {
            storage.saveCached(parsed)
        }
        updateStateIfActive(trackKey) {
            it.copy(
                lyrics = parsed,
                statusMessage = "Loaded lyrics from ${candidate.providerName}.",
                errorMessage = null
            )
        }
    }

    /** Saves manually edited or pasted LRC/plain text lyrics into the local cache. */
    fun saveManualLyrics(rawText: String, offsetMs: Long? = null): Boolean {
        val track = _uiState.value.activeTrack ?: return false
        val trackKey = storage.trackKeyFor(track)
        val parsed = LyricsParser.toTrackLyrics(
            trackKey = trackKey,
            rawInput = rawText,
            source = LyricsSource.MANUAL,
            matchedTitle = track.title,
            matchedArtist = track.artist,
            confidence = 1f,
            offsetMsOverride = offsetMs
        ) ?: return false

        memoryCache[trackKey] = parsed
        scope.launch {
            storage.saveCached(parsed)
            storage.saveAppSidecarLrc(track, parsed)
        }
        updateStateIfActive(trackKey) {
            it.copy(
                lyrics = parsed,
                statusMessage = if (parsed.isSynced) "Synchronized lyrics saved." else "Plain lyrics saved.",
                errorMessage = null
            )
        }
        return true
    }

    /** Imports a `.lrc` or `.txt` file picked via Android SAF. */
    fun importLyricsFromUri(uri: Uri) {
        val track = _uiState.value.activeTrack ?: return
        val trackKey = storage.trackKeyFor(track)
        scope.launch {
            val raw = storage.readImportedTextUri(uri)
            if (raw.isNullOrBlank()) {
                updateStateIfActive(trackKey) {
                    it.copy(errorMessage = "Could not read selected lyrics file.")
                }
                return@launch
            }
            val parsed = LyricsParser.toTrackLyrics(
                trackKey = trackKey,
                rawInput = raw,
                source = LyricsSource.MANUAL,
                matchedTitle = track.title,
                matchedArtist = track.artist
            )
            if (parsed == null) {
                updateStateIfActive(trackKey) {
                    it.copy(errorMessage = "Selected file did not contain valid lyrics.")
                }
                return@launch
            }
            memoryCache[trackKey] = parsed
            storage.saveCached(parsed)
            storage.saveAppSidecarLrc(track, parsed)
            updateStateIfActive(trackKey) {
                it.copy(
                    lyrics = parsed,
                    statusMessage = "Imported lyrics file.",
                    errorMessage = null
                )
            }
        }
    }

    /** Adjusts the synchronization offset for the active track's lyrics and persists it. */
    fun adjustOffset(deltaMs: Long) {
        val current = _uiState.value.lyrics ?: return
        val track = _uiState.value.activeTrack ?: return
        val trackKey = storage.trackKeyFor(track)
        val updated = current.copy(
            offsetMs = (current.offsetMs + deltaMs).coerceIn(-30_000L, 30_000L),
            updatedAtMs = System.currentTimeMillis()
        )
        memoryCache[trackKey] = updated
        scope.launch { storage.saveCached(updated) }
        updateStateIfActive(trackKey) {
            it.copy(lyrics = updated)
        }
    }

    fun resetOffset() {
        val current = _uiState.value.lyrics ?: return
        val track = _uiState.value.activeTrack ?: return
        val trackKey = storage.trackKeyFor(track)
        val updated = current.copy(offsetMs = 0L, updatedAtMs = System.currentTimeMillis())
        memoryCache[trackKey] = updated
        scope.launch { storage.saveCached(updated) }
        updateStateIfActive(trackKey) { it.copy(lyrics = updated) }
    }

    /** Exports active lyrics as `.lrc` to a user-selected SAF URI. */
    fun exportActiveLyricsToUri(uri: Uri, onResult: (Boolean) -> Unit = {}) {
        val lyrics = _uiState.value.lyrics ?: run {
            onResult(false)
            return
        }
        val trackKey = lyrics.trackKey
        scope.launch {
            val ok = storage.exportLrcToUri(uri, lyrics)
            updateStateIfActive(trackKey) {
                if (ok) it.copy(statusMessage = "Exported .lrc file.", errorMessage = null)
                else it.copy(errorMessage = "Could not export .lrc file.")
            }
            withContext(Dispatchers.Main) { onResult(ok) }
        }
    }

    /** Safely embeds the active lyrics into the audio file when the container & storage allow it. */
    fun embedActiveLyricsIntoAudioFile(
        onRequiresWritePermission: ((List<Uri>) -> Unit)? = null
    ) {
        val track = _uiState.value.activeTrack ?: return
        val lyrics = _uiState.value.lyrics ?: return
        val trackKey = storage.trackKeyFor(track)
        scope.launch {
            updateStateIfActive(trackKey) {
                it.copy(isEmbedding = true, errorMessage = null, statusMessage = null)
            }
            val result = LyricsTagIO.embedLyricsSafely(
                context = appContext,
                uriString = track.uri,
                displayName = track.displayName,
                mimeType = track.mimeType,
                lyrics = lyrics
            )
            when (result) {
                is EmbedWriteResult.Success -> {
                    val updated = lyrics.copy(
                        source = LyricsSource.EMBEDDED,
                        updatedAtMs = System.currentTimeMillis()
                    )
                    memoryCache[trackKey] = updated
                    storage.saveCached(updated)
                    updateStateIfActive(trackKey) {
                        it.copy(
                            lyrics = updated,
                            isEmbedding = false,
                            statusMessage = result.detail,
                            errorMessage = null
                        )
                    }
                }
                is EmbedWriteResult.RequiresWritePermission -> {
                    updateStateIfActive(trackKey) {
                        it.copy(
                            isEmbedding = false,
                            errorMessage = if (onRequiresWritePermission == null) result.message else null
                        )
                    }
                    if (onRequiresWritePermission != null) {
                        withContext(Dispatchers.Main) {
                            onRequiresWritePermission(result.uris)
                        }
                    }
                }
                is EmbedWriteResult.Unsupported -> {
                    updateStateIfActive(trackKey) {
                        it.copy(isEmbedding = false, errorMessage = result.reason)
                    }
                }
                is EmbedWriteResult.Failed -> {
                    updateStateIfActive(trackKey) {
                        it.copy(isEmbedding = false, errorMessage = result.reason)
                    }
                }
            }
        }
    }

    fun clearLyricsForActiveTrack() {
        val track = _uiState.value.activeTrack ?: return
        val trackKey = storage.trackKeyFor(track)
        memoryCache.remove(trackKey)
        scope.launch { storage.clearCached(trackKey) }
        updateStateIfActive(trackKey) {
            it.copy(lyrics = null, statusMessage = "Cleared cached lyrics.")
        }
    }

    fun clearNotices() {
        _uiState.update { it.copy(statusMessage = null, errorMessage = null) }
    }

    /**
     * Called after Metadata Editor commits tag edits to [track].
     * Preserves all existing embedded/sidecar/cached lyrics and only clears online miss cooldown
     * or reloads when identity fields (title/artist/album) changed.
     */
    fun onTrackMetadataEdited(
        track: LibraryTrack,
        identityChanged: Boolean,
        customTreeUri: String = ""
    ) {
        val trackKey = storage.trackKeyFor(track)
        if (identityChanged) {
            recentOnlineMisses.remove(trackKey)
        }
        val active = _uiState.value.activeTrack
        if (active != null && active.uri == track.uri) {
            val hadExistingLyrics = _uiState.value.lyrics?.hasContent == true
            if (!hadExistingLyrics && identityChanged) {
                memoryCache.remove(trackKey)
                loadForTrack(track, customTreeUri = customTreeUri, allowOnlineLookup = true)
            } else {
                _uiState.update { it.copy(activeTrack = track) }
            }
        }
    }

    /**
     * Checks local lyrics availability synchronously/off-main-thread for Audio Quality Inspector.
     */
    fun inspectLyricsAvailability(track: LibraryTrack, customTreeUri: String = ""): Pair<Boolean, String?> {
        val trackKey = storage.trackKeyFor(track)
        memoryCache[trackKey]?.takeIf { it.hasContent }?.let { cached ->
            return true to cached.source.displayName
        }
        val local = resolveLocalLayers(track, customTreeUri)
        return if (local != null && local.hasContent) {
            true to local.source.displayName
        } else {
            false to null
        }
    }

    /**
     * Non-blocking post-download lyrics enrichment triggered after a track finishes exporting.
     * Never throws or interferes with audio downloads.
     */
    fun fetchPostDownloadLyricsAsync(
        uriString: String,
        displayName: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        mimeType: String,
        embedIntoFile: Boolean,
        onLog: (String) -> Unit = {}
    ) {
        if (uriString.isBlank() || title.isBlank()) return
        scope.launch {
            runCatching {
                val query = LyricsMatcher.buildQuery(
                    rawTitle = title,
                    rawArtist = artist,
                    rawAlbum = album,
                    durationMs = durationMs
                )
                val lookupResult = provider.lookup(query).getOrNull().orEmpty()
                val decision = LyricsMatcher.rankAndSelect(query, lookupResult)
                val best = decision.bestAutoMatch ?: run {
                    onLog("LYRICS: No high-confidence match for \"$title\"")
                    return@launch
                }
                val lyrics = candidateToTrackLyrics(uriString, best) ?: return@launch
                memoryCache[uriString] = lyrics
                storage.saveCached(lyrics)

                val kind = if (lyrics.isSynced) "synced" else "plain"
                onLog("LYRICS: Cached $kind lyrics for \"$title\" (${best.providerName}, ${(best.confidence * 100).toInt()}%)")

                if (embedIntoFile && LyricsTagIO.supportsEmbedding(displayName, mimeType)) {
                    when (val embedRes = LyricsTagIO.embedLyricsSafely(appContext, uriString, displayName, mimeType, lyrics)) {
                        is EmbedWriteResult.Success -> {
                            val embeddedLyrics = lyrics.copy(source = LyricsSource.EMBEDDED)
                            memoryCache[uriString] = embeddedLyrics
                            storage.saveCached(embeddedLyrics)
                            onLog("LYRICS: ${embedRes.detail}")
                        }
                        is EmbedWriteResult.RequiresWritePermission -> onLog("LYRICS: Embed requires user write consent (${embedRes.message})")
                        is EmbedWriteResult.Unsupported -> onLog("LYRICS: ${embedRes.reason}")
                        is EmbedWriteResult.Failed -> onLog("LYRICS: Embed skipped (${embedRes.reason})")
                    }
                }

                // Refresh live UI if the newly downloaded track is currently playing
                updateStateIfActive(uriString) { current ->
                    if (current.lyrics == null) {
                        current.copy(lyrics = memoryCache[uriString] ?: lyrics)
                    } else current
                }
            }.onFailure { err ->
                onLog("LYRICS: Post-download lookup skipped (${err.message})")
            }
        }
    }

    private fun resolveLocalLayers(track: LibraryTrack, customTreeUri: String): TrackLyrics? {
        val trackKey = storage.trackKeyFor(track)

        // If the user explicitly saved/edited manual lyrics in cache, respect their override first
        val cached = storage.loadCached(trackKey)
        if (cached != null && cached.source == LyricsSource.MANUAL) {
            return cached
        }

        // Layer 1: Embedded tags inside the audio file
        val embedded = LyricsTagIO.readEmbeddedLyrics(
            context = appContext,
            uriString = track.uri,
            trackKey = trackKey,
            displayName = track.displayName,
            mimeType = track.mimeType
        ).lyrics
        if (embedded != null && embedded.hasContent) {
            // Preserve user's saved offset if previously tuned in cache
            return if (cached != null && cached.offsetMs != 0L) {
                embedded.copy(offsetMs = cached.offsetMs)
            } else {
                embedded
            }
        }

        // Layer 2: Sidecar .lrc file
        val sidecar = storage.findSidecarLrc(track, customTreeUri)
        if (sidecar != null && sidecar.hasContent) {
            return if (cached != null && cached.offsetMs != 0L) {
                sidecar.copy(offsetMs = cached.offsetMs)
            } else {
                sidecar
            }
        }

        // Layer 3: Cached lyrics (previously fetched online or saved)
        if (cached != null && cached.hasContent) {
            return cached
        }

        return null
    }

    private fun candidateToTrackLyrics(trackKey: String, candidate: LyricsCandidate): TrackLyrics? {
        val raw = candidate.syncedLyrics?.takeIf { it.isNotBlank() }
            ?: candidate.plainLyrics?.takeIf { it.isNotBlank() }
            ?: return null
        return LyricsParser.toTrackLyrics(
            trackKey = trackKey,
            rawInput = raw,
            source = LyricsSource.ONLINE_LRCLIB,
            providerName = candidate.providerName,
            matchedTitle = candidate.title,
            matchedArtist = candidate.artist,
            confidence = candidate.confidence
        )
    }

    private fun isRecentlyMissed(trackKey: String): Boolean {
        val ts = recentOnlineMisses[trackKey] ?: return false
        return (System.currentTimeMillis() - ts) < ONLINE_MISS_COOLDOWN_MS
    }

    private fun updateStateIfActive(trackKey: String, transform: (LyricsUiState) -> LyricsUiState) {
        _uiState.update { state ->
            if (state.activeTrack?.let { storage.trackKeyFor(it) } == trackKey) {
                transform(state)
            } else {
                state
            }
        }
    }
}

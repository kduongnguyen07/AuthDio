package com.example.mdownload.player.library

import android.content.Context
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.RepositoryLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicLong

data class ListeningStats(
    val trackId: String,
    val lastKnownUri: String = "",
    val playCount: Int = 0,
    val totalListenedMs: Long = 0L,
    val lastPlayedAt: Long? = null,
    val firstPlayedAt: Long? = null
)

/**
 * Lightweight, offline-first listening history repository with atomic JSON persistence.
 *
 * - Keyed by [LibraryTrack.stableId] (with an O(1) URI secondary index) so play counts and
 *   listening duration survive MediaStore rescans and metadata edits without duplicate inflation.
 * - Supports non-blocking async startup hydration (`loadAsync = true` in production [Context]
 *   constructor) with guaranteed anti-overwrite protection: any write arriving before hydration
 *   completes loads disk state first before merging.
 * - Supports bounded debounced disk persistence for periodic duration updates (`flushImmediately = false`),
 *   paired with immediate durable flushes (`flushNow()`) on qualified play, track change, pause,
 *   session completion, and service lifecycle transitions.
 */
class ListeningHistoryRepository(
    private val file: File,
    loadAsync: Boolean = false,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    companion object {
        const val DEFAULT_DEBOUNCE_MS = 30_000L
    }

    constructor(context: Context) : this(
        file = File(File(context.filesDir, "hifideck_data"), "listening_history.json"),
        loadAsync = true
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val readyDeferred = CompletableDeferred<Unit>()

    private val _loadState = MutableStateFlow(
        if (loadAsync) RepositoryLoadState.LOADING else RepositoryLoadState.READY
    )
    val loadState: StateFlow<RepositoryLoadState> = _loadState.asStateFlow()

    private val _statsByTrackId = MutableStateFlow<Map<String, ListeningStats>>(emptyMap())
    val statsByTrackId: StateFlow<Map<String, ListeningStats>> = _statsByTrackId.asStateFlow()

    @Volatile
    private var uriIndex: Map<String, ListeningStats> = emptyMap()

    private var hydrated: Boolean = false
    private var hasPendingDiskWrite: Boolean = false
    private var debounceJob: Job? = null

    private val _diskWriteCount = AtomicLong(0L)
    val diskWriteCount: Long
        get() = _diskWriteCount.get()

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

    fun statsForTrack(track: LibraryTrack): ListeningStats? {
        val map = _statsByTrackId.value
        return map[track.stableId]
            ?: map[track.uri]
            ?: uriIndex[track.uri]
    }

    fun recordQualifiedPlay(trackId: String, uri: String, timestampMs: Long = System.currentTimeMillis()) {
        if (trackId.isBlank()) return
        synchronized(lock) {
            ensureHydratedLocked()
            val map = _statsByTrackId.value.toMutableMap()
            val prev = map[trackId] ?: map.remove(uri) ?: ListeningStats(trackId = trackId, lastKnownUri = uri)
            val updated = prev.copy(
                trackId = trackId,
                lastKnownUri = uri.ifBlank { prev.lastKnownUri },
                playCount = prev.playCount + 1,
                firstPlayedAt = prev.firstPlayedAt ?: timestampMs,
                lastPlayedAt = maxOf(prev.lastPlayedAt ?: 0L, timestampMs)
            )
            map[trackId] = updated
            publishMapLocked(map)
            cancelDebounceLocked()
            hasPendingDiskWrite = false
            saveToDiskLocked(map)
        }
    }

    fun addListenedDuration(
        trackId: String,
        uri: String,
        deltaMs: Long,
        timestampMs: Long = System.currentTimeMillis(),
        flushImmediately: Boolean = true
    ) {
        if (trackId.isBlank() || deltaMs <= 0L) return
        synchronized(lock) {
            ensureHydratedLocked()
            val map = _statsByTrackId.value.toMutableMap()
            val prev = map[trackId] ?: map.remove(uri) ?: ListeningStats(trackId = trackId, lastKnownUri = uri)
            val updated = prev.copy(
                trackId = trackId,
                lastKnownUri = uri.ifBlank { prev.lastKnownUri },
                totalListenedMs = prev.totalListenedMs + deltaMs,
                firstPlayedAt = prev.firstPlayedAt ?: timestampMs
            )
            map[trackId] = updated
            publishMapLocked(map)
            if (flushImmediately || debounceMs <= 0L) {
                cancelDebounceLocked()
                hasPendingDiskWrite = false
                saveToDiskLocked(map)
            } else {
                hasPendingDiskWrite = true
                scheduleDebouncedSaveLocked()
            }
        }
    }

    /**
     * Immediately persists any buffered in-memory listening duration changes to disk.
     * Safe to call on track change, pause, session completion, or service shutdown.
     */
    fun flushNow() {
        synchronized(lock) {
            if (!hasPendingDiskWrite) return
            ensureHydratedLocked()
            cancelDebounceLocked()
            hasPendingDiskWrite = false
            saveToDiskLocked(_statsByTrackId.value)
        }
    }

    fun removeTrackStats(trackId: String, uri: String) {
        synchronized(lock) {
            ensureHydratedLocked()
            val map = _statsByTrackId.value.toMutableMap()
            var changed = map.remove(trackId) != null
            if (uri.isNotBlank() && map.remove(uri) != null) changed = true
            if (changed) {
                publishMapLocked(map)
                cancelDebounceLocked()
                hasPendingDiskWrite = false
                saveToDiskLocked(map)
            }
        }
    }

    private fun hydrateFromDisk() {
        synchronized(lock) {
            ensureHydratedLocked()
        }
    }

    private fun ensureHydratedLocked() {
        if (hydrated) return
        var failed = false
        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("stats")
                if (arr != null) {
                    val map = LinkedHashMap<String, ListeningStats>(arr.length())
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val id = o.optString("track_id", "")
                        if (id.isBlank()) continue
                        map[id] = ListeningStats(
                            trackId = id,
                            lastKnownUri = o.optString("uri", ""),
                            playCount = o.optInt("play_count", 0).coerceAtLeast(0),
                            totalListenedMs = o.optLong("total_listened_ms", 0L).coerceAtLeast(0L),
                            lastPlayedAt = if (o.has("last_played_at")) o.optLong("last_played_at") else null,
                            firstPlayedAt = if (o.has("first_played_at")) o.optLong("first_played_at") else null
                        )
                    }
                    publishMapLocked(map)
                }
            }.onFailure {
                failed = true
            }
        }
        hydrated = true
        _loadState.value = if (failed) RepositoryLoadState.FAILED else RepositoryLoadState.READY
        readyDeferred.complete(Unit)
    }

    private fun publishMapLocked(map: Map<String, ListeningStats>) {
        val byUri = HashMap<String, ListeningStats>(map.size)
        for (entry in map.values) {
            if (entry.lastKnownUri.isNotBlank() && entry.lastKnownUri !in byUri) {
                byUri[entry.lastKnownUri] = entry
            }
        }
        uriIndex = byUri
        _statsByTrackId.value = map
    }

    private fun scheduleDebouncedSaveLocked() {
        if (debounceJob?.isActive == true) return
        debounceJob = scope.launch(ioDispatcher) {
            delay(debounceMs)
            flushNow()
        }
    }

    private fun cancelDebounceLocked() {
        debounceJob?.cancel()
        debounceJob = null
    }

    private fun saveToDiskLocked(map: Map<String, ListeningStats>) {
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (s in map.values) {
                arr.put(JSONObject().apply {
                    put("track_id", s.trackId)
                    put("uri", s.lastKnownUri)
                    put("play_count", s.playCount)
                    put("total_listened_ms", s.totalListenedMs)
                    s.lastPlayedAt?.let { put("last_played_at", it) }
                    s.firstPlayedAt?.let { put("first_played_at", it) }
                })
            }
            val root = JSONObject().apply { put("stats", arr) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            _diskWriteCount.incrementAndGet()
        }
    }
}

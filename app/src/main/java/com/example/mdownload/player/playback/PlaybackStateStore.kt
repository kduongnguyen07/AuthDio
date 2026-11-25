package com.example.mdownload.player.playback

import android.content.Context
import com.example.mdownload.core.LibraryTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Lightweight playback cursor written on periodic 5s playback ticks and mode/speed changes
 * without rewriting the full serialized track queue (~120 KB).
 */
data class PlaybackCursorState(
    val queueSignature: String,
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeOption = RepeatModeOption.OFF,
    val playbackSpeed: Float = 1.0f,
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Atomic JSON persistence for active playback queue and cursor.
 *
 * Splits persistence into two sibling files in `hifideck_data/`:
 * 1. `playback_state.json` — structural queue + track metadata + `queue_signature`, written ONLY
 *    when the queue composition or track metadata changes.
 * 2. `playback_cursor.json` (~160 bytes) — current index, position, shuffle, repeat, and speed,
 *    written on 5-second periodic ticks and playback mode toggles.
 *
 * Fully backward-compatible with pre-STAB-05 legacy `playback_state.json` files (when no
 * `playback_cursor.json` exists yet, `load()` restores directly from `playback_state.json`).
 */
class PlaybackStateStore(private val stateFile: File) {

    constructor(context: Context) : this(
        File(File(context.filesDir, "hifideck_data"), "playback_state.json")
    )

    companion object {
        private const val MAX_PERSISTED_QUEUE = 500

        /**
         * Fast deterministic structural fingerprint of a playback queue so cursor-only writes
         * can be safely matched against the persisted queue without hashing full JSON payloads.
         */
        fun computeQueueSignature(queue: List<LibraryTrack>): String {
            val trimmed = queue.take(MAX_PERSISTED_QUEUE)
            var h1 = -3750763034362895579L // FNV-1a 64-bit offset basis
            var h2 = 0x517cc1b727220a95L
            fun mix(value: Long) {
                h1 = (h1 xor value) * 1099511628211L
                h2 = ((h2 shl 5) - h2) xor value
            }
            fun mixString(s: String) {
                mix(s.length.toLong())
                for (i in 0 until s.length) {
                    mix(s[i].code.toLong())
                }
            }
            mix(trimmed.size.toLong())
            for (t in trimmed) {
                mixString(t.uri)
                mixString(t.title)
                mixString(t.artist)
                mixString(t.album)
                mix(t.dateModifiedSec)
            }
            return "${trimmed.size}:${java.lang.Long.toUnsignedString(h1, 16)}:${java.lang.Long.toUnsignedString(h2, 16)}"
        }

        fun computeQueueSignatureFromDescriptors(
            descriptors: List<QueueItemDescriptor>
        ): String {
            val trimmed = descriptors.take(MAX_PERSISTED_QUEUE)
            var h1 = -3750763034362895579L
            var h2 = 0x517cc1b727220a95L
            fun mix(value: Long) {
                h1 = (h1 xor value) * 1099511628211L
                h2 = ((h2 shl 5) - h2) xor value
            }
            fun mixString(s: String) {
                mix(s.length.toLong())
                for (i in 0 until s.length) {
                    mix(s[i].code.toLong())
                }
            }
            mix(trimmed.size.toLong())
            for (d in trimmed) {
                mixString(d.uri)
                mixString(d.title)
                mixString(d.artist)
                mixString(d.album)
                mix(d.dateModifiedSec)
            }
            return "${trimmed.size}:${java.lang.Long.toUnsignedString(h1, 16)}:${java.lang.Long.toUnsignedString(h2, 16)}"
        }
    }

    data class QueueItemDescriptor(
        val uri: String,
        val title: String,
        val artist: String,
        val album: String,
        val dateModifiedSec: Long
    )

    private val cursorFile: File = File(
        stateFile.parentFile,
        "${stateFile.nameWithoutExtension}_cursor.json"
    )

    private val lock = Any()

    @Volatile
    var fullQueueWriteCount: Int = 0
        private set

    @Volatile
    var cursorOnlyWriteCount: Int = 0
        private set

    val cursorFilePath: File
        get() = cursorFile

    fun load(): PersistedPlaybackState = synchronized(lock) {
        if (!stateFile.isFile) return PersistedPlaybackState()
        return runCatching {
            val root = JSONObject(stateFile.readText(Charsets.UTF_8))
            val rawQueue = root.optJSONArray("queue") ?: JSONArray()
            val tracks = mutableListOf<LibraryTrack>()
            for (i in 0 until rawQueue.length()) {
                val obj = rawQueue.optJSONObject(i) ?: continue
                val uri = obj.optString("uri", "")
                if (uri.isBlank()) continue
                tracks.add(
                    LibraryTrack(
                        id = obj.optLong("id", uri.hashCode().toLong()),
                        uri = uri,
                        displayName = obj.optString("display_name", ""),
                        title = obj.optString("title", "Unknown track"),
                        artist = obj.optString("artist", "Unknown artist"),
                        album = obj.optString("album", ""),
                        durationMs = obj.optLong("duration_ms", 0L).coerceAtLeast(0L),
                        sizeBytes = obj.optLong("size_bytes", 0L).coerceAtLeast(0L),
                        dateAddedSec = obj.optLong("date_added_sec", 0L).coerceAtLeast(0L),
                        dateModifiedSec = obj.optLong("date_modified_sec", 0L).coerceAtLeast(0L),
                        mimeType = obj.optString("mime_type", "audio/*"),
                        videoId = obj.optString("video_id", ""),
                        albumId = obj.optLong("album_id", 0L),
                        folderPath = obj.optString("folder_path", ""),
                        folderName = obj.optString("folder_name", ""),
                        isDownloadedByApp = obj.optBoolean("is_downloaded_by_app", false),
                        bitrate = obj.optInt("bitrate", 0)
                    )
                )
            }
            val queueSignature = root.optString("queue_signature", "")
                .ifBlank { computeQueueSignature(tracks) }

            var currentIndex = if (tracks.isEmpty()) 0 else root.optInt("current_index", 0)
                .coerceIn(0, tracks.lastIndex)
            var positionMs = root.optLong("position_ms", 0L).coerceAtLeast(0L)
            var shuffleEnabled = root.optBoolean("shuffle_enabled", false)
            var repeatMode = RepeatModeOption.fromName(root.optString("repeat_mode"))
            var playbackSpeed = root.optDouble("playback_speed", 1.0)
                .toFloat()
                .coerceIn(0.5f, 2.0f)

            // Overlay lightweight cursor when present and matching the persisted queue signature
            if (cursorFile.isFile) {
                runCatching {
                    val cursor = JSONObject(cursorFile.readText(Charsets.UTF_8))
                    val cursorSig = cursor.optString("queue_signature", "")
                    if (cursorSig.isBlank() || cursorSig == queueSignature) {
                        currentIndex = if (tracks.isEmpty()) 0 else cursor.optInt("current_index", currentIndex)
                            .coerceIn(0, tracks.lastIndex)
                        positionMs = cursor.optLong("position_ms", positionMs).coerceAtLeast(0L)
                        shuffleEnabled = cursor.optBoolean("shuffle_enabled", shuffleEnabled)
                        repeatMode = RepeatModeOption.fromName(cursor.optString("repeat_mode", repeatMode.name))
                        playbackSpeed = cursor.optDouble("playback_speed", playbackSpeed.toDouble())
                            .toFloat()
                            .coerceIn(0.5f, 2.0f)
                    }
                }
            }

            PersistedPlaybackState(
                queue = tracks,
                currentIndex = currentIndex,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                playbackSpeed = playbackSpeed
            )
        }.getOrDefault(PersistedPlaybackState())
    }

    /**
     * Persists the full structural queue to `playback_state.json` AND synchronizes the lightweight
     * `playback_cursor.json` file. Call ONLY when queue structure or track metadata changes.
     */
    fun save(state: PersistedPlaybackState) = synchronized(lock) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            val trimmedQueue = state.queue.take(MAX_PERSISTED_QUEUE)
            val safeIndex = if (trimmedQueue.isEmpty()) 0 else state.currentIndex.coerceIn(0, trimmedQueue.lastIndex)
            val signature = computeQueueSignature(trimmedQueue)
            val root = JSONObject().apply {
                put("queue_signature", signature)
                put("current_index", safeIndex)
                put("position_ms", state.positionMs.coerceAtLeast(0L))
                put("shuffle_enabled", state.shuffleEnabled)
                put("repeat_mode", state.repeatMode.name)
                put("playback_speed", state.playbackSpeed.coerceIn(0.5f, 2.0f).toDouble())
                put("queue", JSONArray().apply {
                    trimmedQueue.forEach { t ->
                        put(JSONObject().apply {
                            put("id", t.id)
                            put("uri", t.uri)
                            put("display_name", t.displayName)
                            put("title", t.title)
                            put("artist", t.artist)
                            put("album", t.album)
                            put("duration_ms", t.durationMs)
                            put("size_bytes", t.sizeBytes)
                            put("date_added_sec", t.dateAddedSec)
                            put("date_modified_sec", t.dateModifiedSec)
                            put("mime_type", t.mimeType)
                            put("video_id", t.videoId)
                            put("album_id", t.albumId)
                            put("folder_path", t.folderPath)
                            put("folder_name", t.folderName)
                            put("is_downloaded_by_app", t.isDownloadedByApp)
                            put("bitrate", t.bitrate)
                        })
                    }
                })
            }
            writeAtomic(stateFile, root.toString())
            fullQueueWriteCount++
            writeCursorLocked(
                PlaybackCursorState(
                    queueSignature = signature,
                    currentIndex = safeIndex,
                    positionMs = state.positionMs.coerceAtLeast(0L),
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode = state.repeatMode,
                    playbackSpeed = state.playbackSpeed.coerceIn(0.5f, 2.0f)
                )
            )
        }
    }

    /**
     * Persists ONLY the tiny (~160 byte) playback cursor file without rewriting `playback_state.json`.
     */
    fun saveCursorOnly(cursor: PlaybackCursorState) = synchronized(lock) {
        runCatching {
            stateFile.parentFile?.mkdirs()
            writeCursorLocked(cursor)
            cursorOnlyWriteCount++
        }
    }

    private fun writeCursorLocked(cursor: PlaybackCursorState) {
        val obj = JSONObject().apply {
            put("queue_signature", cursor.queueSignature)
            put("current_index", cursor.currentIndex.coerceAtLeast(0))
            put("position_ms", cursor.positionMs.coerceAtLeast(0L))
            put("shuffle_enabled", cursor.shuffleEnabled)
            put("repeat_mode", cursor.repeatMode.name)
            put("playback_speed", cursor.playbackSpeed.coerceIn(0.5f, 2.0f).toDouble())
            put("updated_at_ms", cursor.updatedAtMs)
        }
        writeAtomic(cursorFile, obj.toString())
    }

    private fun writeAtomic(target: File, content: String) {
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    fun clear() = synchronized(lock) {
        runCatching {
            if (stateFile.exists()) stateFile.delete()
            if (cursorFile.exists()) cursorFile.delete()
        }
    }
}

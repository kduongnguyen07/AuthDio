package com.example.mdownload.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class PersistedState(
    val settings: AppSettings = AppSettings(),
    val jobs: List<DownloadJob> = emptyList(),
    val history: List<HistoryRecord> = emptyList()
)

/**
 * Atomic JSON persistence for Settings, Queue, History and diagnostics log,
 * matching HiFi Deck's portable Data/settings.json and settings.log contract.
 */
class SettingsStore(context: Context) {

    private val dataDir = File(context.filesDir, "hifideck_data")
    private val settingsFile = File(dataDir, "settings.json")
    private val logFile = File(dataDir, "settings.log")

    private val lock = Any()

    fun load(): PersistedState = synchronized(lock) {
        if (!settingsFile.isFile) return PersistedState()
        return runCatching {
            val root = JSONObject(settingsFile.readText(Charsets.UTF_8))
            val settings = parseSettings(root.optJSONObject("settings"))
            val rawJobs = root.optJSONArray("jobs") ?: JSONArray()
            val rawHistory = root.optJSONArray("history") ?: JSONArray()

            val restoredJobs = mutableListOf<DownloadJob>()
            val restoredHistory = mutableListOf<HistoryRecord>()

            for (i in 0 until rawJobs.length()) {
                val obj = rawJobs.optJSONObject(i) ?: continue
                val job = parseJob(obj, settings) ?: continue
                if (job.status.isTerminal) {
                    restoredHistory.add(
                        HistoryRecord(
                            id = job.id,
                            url = job.url,
                            title = job.title,
                            sourceTitle = job.sourceTitle,
                            artist = job.artist,
                            thumbnailUrl = job.thumbnailUrl,
                            status = job.status,
                            detail = job.detail,
                            completedAtIso = "",
                            formatLabel = TextUtils.formatLabel(job.options),
                            options = job.options,
                            metadata = job.metadata
                        )
                    )
                } else {
                    // Interrupted active jobs return to Queued on next launch.
                    restoredJobs.add(
                        job.copy(
                            status = JobStatus.QUEUED,
                            stage = JobStage.WAITING,
                            detail = "Waiting for session",
                            progress = 0f,
                            speedBytesPerSec = 0.0
                        )
                    )
                }
            }

            for (i in 0 until rawHistory.length()) {
                val obj = rawHistory.optJSONObject(i) ?: continue
                parseHistory(obj, settings)?.let { restoredHistory.add(it) }
            }

            PersistedState(
                settings = settings,
                jobs = restoredJobs,
                history = restoredHistory
            )
        }.getOrDefault(PersistedState())
    }

    fun save(state: PersistedState) = synchronized(lock) {
        runCatching {
            dataDir.mkdirs()
            val root = JSONObject().apply {
                put("settings", serializeSettings(state.settings))
                put("jobs", JSONArray().apply {
                    state.jobs.filterNot { it.isConnectionCheck }.forEach { put(serializeJob(it)) }
                })
                put("history", JSONArray().apply {
                    state.history.take(300).forEach { put(serializeHistory(it)) }
                })
            }
            val tmp = File(dataDir, "settings.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(settingsFile)) {
                settingsFile.writeText(root.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun appendLog(line: String) = synchronized(lock) {
        runCatching {
            dataDir.mkdirs()
            val sanitized = LogSanitizer.sanitize(line)
            val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            logFile.appendText("[$ts] $sanitized\n", Charsets.UTF_8)
            // Cap log file size at ~256 KB
            if (logFile.length() > 262_144) {
                val tail = logFile.readLines(Charsets.UTF_8).takeLast(400)
                logFile.writeText(tail.joinToString("\n") + "\n", Charsets.UTF_8)
            }
        }
    }

    fun readLogs(maxLines: Int = 200): List<String> = synchronized(lock) {
        if (!logFile.isFile) return emptyList()
        runCatching {
            logFile.readLines(Charsets.UTF_8).takeLast(maxLines)
        }.getOrDefault(emptyList())
    }

    fun clearLogs() = synchronized(lock) {
        runCatching {
            if (logFile.exists()) logFile.writeText("", Charsets.UTF_8)
        }
    }

    private fun serializeSettings(s: AppSettings) = JSONObject().apply {
        put("audio_format", s.audioFormat.id)
        put("audio_quality", s.audioQuality)
        put("ascii_names", s.asciiNames)
        put("embed_artwork", s.embedArtwork)
        put("skip_duplicates", s.skipDuplicates)
        put("gentle_requests", s.gentleRequests)
        put("parallel_downloads", s.parallelDownloads)
        put("download_threads", s.downloadThreads)
        put("queue_block_reason", s.queueBlockReason)
        put("wifi_only", s.wifiOnly)
        put("allow_background", s.allowBackground)
        put("theme_mode", s.themeMode)
        put("language", s.language)
        put("auto_update", s.autoUpdate)
        put("last_update_check", s.lastUpdateCheck)
        put("last_update_result", s.lastUpdateResult)
        put("storage_tree_uri", s.storageTreeUri)
        put("storage_folder_label", s.storageFolderLabel)
        put("instant_share_download", s.instantShareDownload)
        put("scan_all_device_music", s.scanAllDeviceMusic)
        put("restore_playback_on_launch", s.restorePlaybackOnLaunch)
        put("default_playback_speed", s.defaultPlaybackSpeed.toDouble())
        put("auto_fetch_lyrics_after_download", s.autoFetchLyricsAfterDownload)
        put("embed_lyrics_after_download", s.embedLyricsAfterDownload)
        put("replay_gain_mode", s.replayGainMode)
        put("replay_gain_preamp_db", s.replayGainPreampDb.toDouble())
        put("replay_gain_missing_behavior", s.replayGainMissingBehavior)
        put("replay_gain_fallback_preamp_db", s.replayGainFallbackPreampDb.toDouble())
        put("replay_gain_prevent_clipping", s.replayGainPreventClipping)
        put("sleep_timer_default_fade_sec", s.sleepTimerDefaultFadeSec)
    }

    private fun parseSettings(obj: JSONObject?): AppSettings {
        if (obj == null) return AppSettings()
        val format = AudioFormat.fromId(obj.optString("audio_format", "mp3"))
        val rawQuality = obj.optInt("audio_quality", format.defaultBitrate)
        val quality = if (format == AudioFormat.ORIGINAL || rawQuality !in format.bitrates) {
            format.defaultBitrate
        } else rawQuality
        val hasThreadSchema = obj.has("download_threads")
        return AppSettings(
            audioFormat = format,
            audioQuality = quality,
            asciiNames = obj.optBoolean("ascii_names", false),
            embedArtwork = obj.optBoolean("embed_artwork", true),
            skipDuplicates = obj.optBoolean("skip_duplicates", true),
            gentleRequests = if (hasThreadSchema) obj.optBoolean("gentle_requests", false) else false,
            parallelDownloads = (if (hasThreadSchema) obj.optInt("parallel_downloads", 3) else 3)
                .coerceIn(1, AppSettings.MAX_PARALLEL_DOWNLOADS),
            downloadThreads = obj.optInt("download_threads", 4)
                .coerceIn(1, AppSettings.MAX_DOWNLOAD_THREADS),
            queueBlockReason = obj.optString("queue_block_reason", ""),
            wifiOnly = obj.optBoolean("wifi_only", false),
            allowBackground = obj.optBoolean("allow_background", true),
            themeMode = obj.optString("theme_mode", "system"),
            language = obj.optString("language", "system"),
            autoUpdate = obj.optBoolean("auto_update", false),
            lastUpdateCheck = obj.optString("last_update_check", ""),
            lastUpdateResult = obj.optString("last_update_result", ""),
            storageTreeUri = obj.optString("storage_tree_uri", ""),
            storageFolderLabel = obj.optString("storage_folder_label", ""),
            instantShareDownload = obj.optBoolean("instant_share_download", false),
            scanAllDeviceMusic = obj.optBoolean("scan_all_device_music", true),
            restorePlaybackOnLaunch = obj.optBoolean("restore_playback_on_launch", true),
            defaultPlaybackSpeed = obj.optDouble("default_playback_speed", 1.0)
                .toFloat()
                .coerceIn(0.5f, 2.0f),
            autoFetchLyricsAfterDownload = obj.optBoolean("auto_fetch_lyrics_after_download", true),
            embedLyricsAfterDownload = obj.optBoolean("embed_lyrics_after_download", false),
            replayGainMode = obj.optString("replay_gain_mode", "off")
                .lowercase(Locale.US)
                .takeIf { it in setOf("off", "track", "album") } ?: "off",
            replayGainPreampDb = obj.optDouble("replay_gain_preamp_db", 0.0)
                .toFloat()
                .takeIf { it.isFinite() }
                ?.coerceIn(-12f, 12f) ?: 0.0f,
            replayGainMissingBehavior = obj.optString("replay_gain_missing_behavior", "unchanged")
                .lowercase(Locale.US)
                .takeIf { it in setOf("unchanged", "fallback_preamp") } ?: "unchanged",
            replayGainFallbackPreampDb = obj.optDouble("replay_gain_fallback_preamp_db", 0.0)
                .toFloat()
                .takeIf { it.isFinite() }
                ?.coerceIn(-12f, 6f) ?: 0.0f,
            replayGainPreventClipping = obj.optBoolean("replay_gain_prevent_clipping", true),
            sleepTimerDefaultFadeSec = obj.optInt("sleep_timer_default_fade_sec", 10)
                .takeIf { it in setOf(0, 5, 10, 15, 30) } ?: 10
        )
    }

    private fun serializeOptions(o: JobOptions) = JSONObject().apply {
        put("audio_format", o.audioFormat.id)
        put("audio_quality", o.audioQuality)
        put("ascii_names", o.asciiNames)
        put("embed_artwork", o.embedArtwork)
        put("skip_duplicates", o.skipDuplicates)
    }

    private fun parseOptions(obj: JSONObject?, fallback: AppSettings): JobOptions {
        if (obj == null) return fallback.toJobOptions()
        return JobOptions(
            audioFormat = AudioFormat.fromId(obj.optString("audio_format", fallback.audioFormat.id)),
            audioQuality = obj.optInt("audio_quality", fallback.audioQuality),
            asciiNames = obj.optBoolean("ascii_names", fallback.asciiNames),
            embedArtwork = obj.optBoolean("embed_artwork", fallback.embedArtwork),
            skipDuplicates = obj.optBoolean("skip_duplicates", fallback.skipDuplicates)
        ).normalized()
    }

    private fun serializeMetadata(m: TrackMetadata) = JSONObject().apply {
        if (m.title.isNotBlank()) put("title", m.title)
        if (m.artist.isNotBlank()) put("artist", m.artist)
        if (m.album.isNotBlank()) put("album", m.album)
        if (m.year.isNotBlank()) put("year", m.year)
        if (m.customArtworkUri.isNotBlank()) put("custom_artwork_uri", m.customArtworkUri)
    }

    private fun parseMetadata(obj: JSONObject?): TrackMetadata {
        if (obj == null) return TrackMetadata()
        return TextUtils.cleanMetadata(
            TrackMetadata(
                title = obj.optString("title", ""),
                artist = obj.optString("artist", ""),
                album = obj.optString("album", ""),
                year = obj.optString("year", ""),
                customArtworkUri = obj.optString("custom_artwork_uri", "")
            )
        )
    }

    private fun serializeJob(job: DownloadJob) = JSONObject().apply {
        put("id", job.id)
        put("url", job.url)
        put("title", job.title)
        put("source_title", job.sourceTitle)
        put("artist", job.artist)
        put("thumbnail_url", job.thumbnailUrl)
        put("status", job.status.label)
        put("detail", job.detail)
        put("options", serializeOptions(job.options))
        put("metadata", serializeMetadata(job.metadata))
    }

    private fun parseJob(obj: JSONObject, settings: AppSettings): DownloadJob? {
        val rawUrl = obj.optString("url", "")
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(rawUrl) }.getOrNull() ?: return null
        val metadata = parseMetadata(obj.optJSONObject("metadata"))
        val sourceTitle = obj.optString("source_title", obj.optString("title", normalized))
        return DownloadJob(
            id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            url = normalized,
            title = metadata.title.ifBlank { obj.optString("title", sourceTitle) },
            sourceTitle = sourceTitle,
            artist = obj.optString("artist", ""),
            thumbnailUrl = obj.optString("thumbnail_url", ""),
            status = JobStatus.fromLabel(obj.optString("status")),
            detail = obj.optString("detail", "Waiting for session"),
            options = parseOptions(obj.optJSONObject("options"), settings),
            metadata = metadata
        )
    }

    private fun serializeHistory(h: HistoryRecord) = JSONObject().apply {
        put("id", h.id)
        put("url", h.url)
        put("title", h.title)
        put("source_title", h.sourceTitle)
        put("artist", h.artist)
        put("thumbnail_url", h.thumbnailUrl)
        put("status", h.status.label)
        put("detail", h.detail)
        put("completed_at", h.completedAtIso)
        put("format_label", h.formatLabel)
        put("saved_uri", h.savedUri)
        put("saved_display_name", h.savedDisplayName)
        put("options", serializeOptions(h.options))
        put("metadata", serializeMetadata(h.metadata))
        put("connection_check", h.isConnectionCheck)
    }

    private fun parseHistory(obj: JSONObject, settings: AppSettings): HistoryRecord? {
        val rawUrl = obj.optString("url", "")
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(rawUrl) }.getOrNull() ?: return null
        val options = parseOptions(obj.optJSONObject("options"), settings)
        val metadata = parseMetadata(obj.optJSONObject("metadata"))
        val sourceTitle = obj.optString("source_title", obj.optString("title", normalized))
        return HistoryRecord(
            id = obj.optString("id").ifBlank { java.util.UUID.randomUUID().toString() },
            url = normalized,
            title = metadata.title.ifBlank { obj.optString("title", sourceTitle) },
            sourceTitle = sourceTitle,
            artist = obj.optString("artist", ""),
            thumbnailUrl = obj.optString("thumbnail_url", ""),
            status = JobStatus.fromLabel(obj.optString("status", JobStatus.DONE.label)),
            detail = obj.optString("detail", ""),
            completedAtIso = obj.optString("completed_at", ""),
            formatLabel = obj.optString("format_label", TextUtils.formatLabel(options)),
            savedUri = obj.optString("saved_uri", ""),
            savedDisplayName = obj.optString("saved_display_name", ""),
            options = options,
            metadata = metadata,
            isConnectionCheck = obj.optBoolean("connection_check", false)
        )
    }
}

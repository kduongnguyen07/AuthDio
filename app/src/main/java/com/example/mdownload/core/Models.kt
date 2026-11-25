package com.example.mdownload.core

import java.util.UUID

enum class AudioFormat(
    val id: String,
    val displayName: String,
    val bitrates: List<Int>,
    val defaultBitrate: Int,
    val mimeType: String
) {
    MP3("mp3", "MP3", listOf(128, 192, 320), 128, "audio/mpeg"),
    M4A("m4a", "M4A", listOf(128, 192, 256), 192, "audio/mp4"),
    OPUS("opus", "Opus", listOf(128, 160, 192), 160, "audio/opus"),
    ORIGINAL("original", "Original", emptyList(), 128, "audio/*");

    companion object {
        fun fromId(id: String?): AudioFormat =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: MP3
    }
}

enum class DownloadPreset(
    val label: String,
    val subtitle: String,
    val format: AudioFormat,
    val quality: Int,
    val asciiNames: Boolean
) {
    USB(
        label = "USB / Standard",
        subtitle = "MP3 128 · ASCII",
        format = AudioFormat.MP3,
        quality = 128,
        asciiNames = true
    ),
    DESKTOP(
        label = "High Quality",
        subtitle = "MP3 192 · Unicode",
        format = AudioFormat.MP3,
        quality = 192,
        asciiNames = false
    ),
    ORIGINAL(
        label = "Original",
        subtitle = "Source stream",
        format = AudioFormat.ORIGINAL,
        quality = 128,
        asciiNames = false
    )
}

enum class JobStatus(val label: String) {
    QUEUED("Queued"),
    RUNNING("Running"),
    RETRYING("Retrying"),
    STOPPING("Stopping"),
    DONE("Done"),
    SKIPPED("Skipped"),
    FAILED("Failed"),
    CANCELLED("Cancelled");

    val isTerminal: Boolean
        get() = this == DONE || this == SKIPPED || this == FAILED || this == CANCELLED

    val isActive: Boolean
        get() = this == RUNNING || this == RETRYING || this == STOPPING

    companion object {
        fun fromLabel(label: String?): JobStatus =
            entries.firstOrNull { it.label.equals(label, ignoreCase = true) } ?: QUEUED
    }
}

enum class JobStage(val label: String) {
    WAITING("Waiting"),
    CONNECTING("Connecting"),
    FETCHING_METADATA("Fetching metadata"),
    DOWNLOADING("Downloading"),
    CONVERTING("Converting"),
    WRITING_METADATA("Writing metadata"),
    EMBEDDING_ARTWORK("Embedding artwork"),
    EXPORTING("Saving to Music"),
    PROCESSING("Processing")
}

data class TrackMetadata(
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    val year: String = "",
    val customArtworkUri: String = ""
) {
    val isEmpty: Boolean
        get() = title.isBlank() &&
            artist.isBlank() &&
            album.isBlank() &&
            year.isBlank() &&
            customArtworkUri.isBlank()
}

data class JobOptions(
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val audioQuality: Int = 128,
    val asciiNames: Boolean = false,
    val embedArtwork: Boolean = true,
    val skipDuplicates: Boolean = true
) {
    fun normalized(): JobOptions {
        val validQuality = if (audioFormat == AudioFormat.ORIGINAL) {
            128
        } else if (audioQuality in audioFormat.bitrates) {
            audioQuality
        } else {
            audioFormat.defaultBitrate
        }
        return copy(audioQuality = validQuality)
    }
}

data class DownloadJob(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val sourceTitle: String = title,
    val artist: String = "",
    val thumbnailUrl: String = "",
    val status: JobStatus = JobStatus.QUEUED,
    val stage: JobStage = JobStage.WAITING,
    val detail: String = "Waiting for session",
    val progress: Float = 0f,
    val speedBytesPerSec: Double = 0.0,
    val options: JobOptions = JobOptions(),
    val metadata: TrackMetadata = TrackMetadata(),
    val isConnectionCheck: Boolean = false
)

data class HistoryRecord(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val sourceTitle: String = title,
    val artist: String = "",
    val thumbnailUrl: String = "",
    val status: JobStatus,
    val detail: String,
    val completedAtIso: String,
    val formatLabel: String,
    val savedUri: String = "",
    val savedDisplayName: String = "",
    val options: JobOptions = JobOptions(),
    val metadata: TrackMetadata = TrackMetadata(),
    val isConnectionCheck: Boolean = false
)

data class AppSettings(
    val audioFormat: AudioFormat = AudioFormat.MP3,
    val audioQuality: Int = 128,
    val asciiNames: Boolean = false,
    val embedArtwork: Boolean = true,
    val skipDuplicates: Boolean = true,
    val gentleRequests: Boolean = false,
    val parallelDownloads: Int = 3,
    val downloadThreads: Int = 4,
    val queueBlockReason: String = "",
    val wifiOnly: Boolean = false,
    val allowBackground: Boolean = true,
    val themeMode: String = "system",
    val language: String = "system",
    val autoUpdate: Boolean = false,
    val lastUpdateCheck: String = "",
    val lastUpdateResult: String = "",
    val storageTreeUri: String = "",
    val storageFolderLabel: String = "",
    val instantShareDownload: Boolean = false,
    val scanAllDeviceMusic: Boolean = true,
    val restorePlaybackOnLaunch: Boolean = true,
    val defaultPlaybackSpeed: Float = 1.0f,
    val autoFetchLyricsAfterDownload: Boolean = true,
    val embedLyricsAfterDownload: Boolean = false,
    val replayGainMode: String = "off",
    val replayGainPreampDb: Float = 0.0f,
    val replayGainMissingBehavior: String = "unchanged",
    val replayGainFallbackPreampDb: Float = 0.0f,
    val replayGainPreventClipping: Boolean = true,
    val sleepTimerDefaultFadeSec: Int = 10
) {
    companion object {
        const val MAX_PARALLEL_DOWNLOADS = 6
        const val MAX_DOWNLOAD_THREADS = 8
        const val DEFAULT_STORAGE_LABEL = "Music/MDownload"
    }

    val effectiveParallelDownloads: Int
        get() = if (gentleRequests) 1 else parallelDownloads.coerceIn(1, MAX_PARALLEL_DOWNLOADS)

    val effectiveDownloadThreads: Int
        get() = if (gentleRequests) 1 else downloadThreads.coerceIn(1, MAX_DOWNLOAD_THREADS)

    val effectiveStorageLabel: String
        get() = if (storageTreeUri.isNotBlank()) {
            storageFolderLabel.ifBlank { "Custom folder" }
        } else {
            DEFAULT_STORAGE_LABEL
        }

    fun toJobOptions(): JobOptions = JobOptions(
        audioFormat = audioFormat,
        audioQuality = audioQuality,
        asciiNames = asciiNames,
        embedArtwork = embedArtwork,
        skipDuplicates = skipDuplicates
    ).normalized()
}

data class PlaylistTrackItem(
    val index: Int,
    val videoId: String,
    val url: String,
    val title: String,
    val artist: String,
    val durationSec: Int?,
    val available: Boolean,
    val thumbnailUrl: String = ""
)

data class SourcePreviewInfo(
    val sourceUrl: String,
    val normalizedUrl: String,
    val isPlaylist: Boolean,
    val isMix: Boolean,
    val singleVideoUrl: String?,
    val title: String,
    val artist: String,
    val album: String = "",
    val releaseYear: String = "",
    val durationSec: Int?,
    val thumbnailUrl: String,
    val entries: List<PlaylistTrackItem> = emptyList()
)

data class LibraryTrack(
    val id: Long,
    val uri: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mimeType: String,
    val videoId: String = "",
    val albumId: Long = 0L,
    val folderPath: String = "",
    val folderName: String = "",
    val isDownloadedByApp: Boolean = false,
    val bitrate: Int = 0,
    val genre: String = "",
    val year: Int = 0,
    val albumArtist: String = "",
    val trackNumber: Int = 0,
    val dateModifiedSec: Long = 0L
) {
    /**
     * Stable cross-rescan track identity key.
     * Uses embedded videoId when present (combined with extension so MP3/FLAC copies remain distinct),
     * otherwise falls back to URI.
     */
    val stableId: String
        get() = if (videoId.isNotBlank()) {
            val ext = displayName.substringAfterLast('.', "").lowercase(java.util.Locale.ROOT)
            "yt:${videoId.trim()}:${ext.ifBlank { "audio" }}"
        } else {
            uri
        }
}

enum class DiscoverSearchFilter(
    val label: String,
    val spParam: String
) {
    ALL("All", ""),
    VIDEOS("Videos", "EgIQAQ%3D%3D"),
    PLAYLISTS("Playlists", "EgIQAw%3D%3D")
}

data class DiscoverResultItem(
    val id: String,
    val url: String,
    val title: String,
    val artist: String,
    val durationText: String = "",
    val durationSec: Int? = null,
    val metaLine: String = "",
    val thumbnailUrl: String = "",
    val isPlaylist: Boolean = false,
    val trackCount: Int? = null
)

sealed interface EngineState {
    data object Initializing : EngineState
    data class Ready(val ytDlpVersion: String) : EngineState
    data class Updating(val currentVersion: String) : EngineState
    data class Error(val message: String) : EngineState
}

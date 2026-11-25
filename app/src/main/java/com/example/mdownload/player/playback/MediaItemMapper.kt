package com.example.mdownload.player.playback

import android.content.ContentUris
import android.net.Uri
import android.os.Bundle
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.Tracks
import com.example.mdownload.core.LibraryTrack
import java.util.Locale

/**
 * Bidirectional mapping between HiFiDeck's LibraryTrack and AndroidX Media3 MediaItem,
 * plus audio codec/format extraction from ExoPlayer Tracks.
 */
object MediaItemMapper {

    private const val EXTRA_ID = "hifideck.track.id"
    private const val EXTRA_DISPLAY_NAME = "hifideck.track.display_name"
    private const val EXTRA_DURATION_MS = "hifideck.track.duration_ms"
    private const val EXTRA_SIZE_BYTES = "hifideck.track.size_bytes"
    private const val EXTRA_DATE_ADDED = "hifideck.track.date_added"
    private const val EXTRA_DATE_MODIFIED = "hifideck.track.date_modified"
    private const val EXTRA_MIME_TYPE = "hifideck.track.mime_type"
    private const val EXTRA_VIDEO_ID = "hifideck.track.video_id"
    private const val EXTRA_ALBUM_ID = "hifideck.track.album_id"
    private const val EXTRA_FOLDER_PATH = "hifideck.track.folder_path"
    private const val EXTRA_FOLDER_NAME = "hifideck.track.folder_name"
    private const val EXTRA_DOWNLOADED_BY_APP = "hifideck.track.downloaded_by_app"
    private const val EXTRA_BITRATE = "hifideck.track.bitrate"

    private val ALBUM_ART_BASE_URI: Uri = Uri.parse("content://media/external/audio/albumart")

    fun albumArtUri(albumId: Long): Uri? =
        if (albumId > 0L) ContentUris.withAppendedId(ALBUM_ART_BASE_URI, albumId) else null

    fun toMediaItem(
        track: LibraryTrack,
        artworkBytes: ByteArray? = null
    ): MediaItem {
        val extras = Bundle().apply {
            putLong(EXTRA_ID, track.id)
            putString(EXTRA_DISPLAY_NAME, track.displayName)
            putLong(EXTRA_DURATION_MS, track.durationMs)
            putLong(EXTRA_SIZE_BYTES, track.sizeBytes)
            putLong(EXTRA_DATE_ADDED, track.dateAddedSec)
            putLong(EXTRA_DATE_MODIFIED, track.dateModifiedSec)
            putString(EXTRA_MIME_TYPE, track.mimeType)
            putString(EXTRA_VIDEO_ID, track.videoId)
            putLong(EXTRA_ALBUM_ID, track.albumId)
            putString(EXTRA_FOLDER_PATH, track.folderPath)
            putString(EXTRA_FOLDER_NAME, track.folderName)
            putBoolean(EXTRA_DOWNLOADED_BY_APP, track.isDownloadedByApp)
            putInt(EXTRA_BITRATE, track.bitrate)
        }

        val metadataBuilder = MediaMetadata.Builder()
            .setTitle(track.title)
            .setDisplayTitle(track.title)
            .setSubtitle(track.artist)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setAlbumArtist(track.albumArtist.ifBlank { null })
            .setGenre(track.genre.ifBlank { null })
            .setDescription(track.album.ifBlank { null })
            .setIsBrowsable(false)
            .setIsPlayable(true)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)

        if (track.year > 0) {
            metadataBuilder.setRecordingYear(track.year)
        }
        if (track.trackNumber > 0) {
            metadataBuilder.setTrackNumber(track.trackNumber)
        }
        if (track.durationMs > 0L) {
            metadataBuilder.setDurationMs(track.durationMs)
        }

        if (artworkBytes != null && artworkBytes.isNotEmpty()) {
            metadataBuilder.setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
        } else {
            albumArtUri(track.albumId)?.let { metadataBuilder.setArtworkUri(it) }
        }

        val itemBuilder = MediaItem.Builder()
            .setMediaId(track.uri)
            .setUri(Uri.parse(track.uri))
            .setMediaMetadata(metadataBuilder.build())

        if (track.mimeType.isNotBlank() && track.mimeType != "audio/*") {
            itemBuilder.setMimeType(track.mimeType)
        }

        return itemBuilder.build()
    }

    fun withArtworkData(item: MediaItem, artworkBytes: ByteArray): MediaItem {
        val updatedMetadata = item.mediaMetadata.buildUpon()
            .setArtworkData(artworkBytes, MediaMetadata.PICTURE_TYPE_FRONT_COVER)
            .build()
        return item.buildUpon()
            .setMediaMetadata(updatedMetadata)
            .build()
    }

    fun fromMediaItem(item: MediaItem): LibraryTrack? {
        val uri = item.localConfiguration?.uri?.toString()
            ?: item.mediaId.takeIf { it.isNotBlank() }
            ?: return null
        val meta = item.mediaMetadata
        val extras = meta.extras

        val title = meta.title?.toString()?.takeIf { it.isNotBlank() }
            ?: meta.displayTitle?.toString()?.takeIf { it.isNotBlank() }
            ?: extras?.getString(EXTRA_DISPLAY_NAME)?.substringBeforeLast('.')?.takeIf { it.isNotBlank() }
            ?: "Unknown track"
        val artist = meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown artist"
        val album = meta.albumTitle?.toString().orEmpty()

        return LibraryTrack(
            id = extras?.getLong(EXTRA_ID, uri.hashCode().toLong()) ?: uri.hashCode().toLong(),
            uri = uri,
            displayName = extras?.getString(EXTRA_DISPLAY_NAME).orEmpty().ifBlank { title },
            title = title,
            artist = artist,
            album = album,
            albumArtist = meta.albumArtist?.toString().orEmpty(),
            genre = meta.genre?.toString().orEmpty(),
            year = meta.recordingYear ?: 0,
            trackNumber = meta.trackNumber ?: 0,
            durationMs = extras?.getLong(EXTRA_DURATION_MS, 0L) ?: (meta.durationMs ?: 0L),
            sizeBytes = extras?.getLong(EXTRA_SIZE_BYTES, 0L) ?: 0L,
            dateAddedSec = extras?.getLong(EXTRA_DATE_ADDED, 0L) ?: 0L,
            dateModifiedSec = extras?.getLong(EXTRA_DATE_MODIFIED, 0L) ?: 0L,
            mimeType = extras?.getString(EXTRA_MIME_TYPE).orEmpty(),
            videoId = extras?.getString(EXTRA_VIDEO_ID).orEmpty(),
            albumId = extras?.getLong(EXTRA_ALBUM_ID, 0L) ?: 0L,
            folderPath = extras?.getString(EXTRA_FOLDER_PATH).orEmpty(),
            folderName = extras?.getString(EXTRA_FOLDER_NAME).orEmpty(),
            isDownloadedByApp = extras?.getBoolean(EXTRA_DOWNLOADED_BY_APP, false) ?: false,
            bitrate = extras?.getInt(EXTRA_BITRATE, 0) ?: 0
        )
    }

    fun extractAudioOutputInfo(tracks: Tracks, fallbackTrack: LibraryTrack?): AudioOutputInfo {
        var selectedFormat: Format? = null
        for (group in tracks.groups) {
            if (group.type == C.TRACK_TYPE_AUDIO && group.isSelected) {
                for (i in 0 until group.length) {
                    if (group.isTrackSelected(i)) {
                        selectedFormat = group.getTrackFormat(i)
                        break
                    }
                }
            }
            if (selectedFormat != null) break
        }
        val fmt = selectedFormat ?: return fallbackAudioOutputInfo(fallbackTrack)
        val codec = codecDisplayName(fmt.sampleMimeType, fmt.containerMimeType, fallbackTrack?.mimeType)
        val bitrateRaw = when {
            fmt.bitrate != Format.NO_VALUE && fmt.bitrate > 0 -> fmt.bitrate
            fmt.averageBitrate != Format.NO_VALUE && fmt.averageBitrate > 0 -> fmt.averageBitrate
            (fallbackTrack?.bitrate ?: 0) > 0 -> fallbackTrack!!.bitrate
            else -> 0
        }
        val bitrateKbps = if (bitrateRaw >= 1000) bitrateRaw / 1000 else bitrateRaw
        val sampleRate = if (fmt.sampleRate != Format.NO_VALUE && fmt.sampleRate > 0) fmt.sampleRate else 0
        val channels = if (fmt.channelCount != Format.NO_VALUE && fmt.channelCount > 0) fmt.channelCount else 0
        return AudioOutputInfo(
            codecLabel = codec,
            sampleRateHz = sampleRate,
            channelCount = channels,
            bitrateKbps = bitrateKbps
        )
    }

    fun fallbackAudioOutputInfo(track: LibraryTrack?): AudioOutputInfo {
        if (track == null) return AudioOutputInfo()
        val codec = codecDisplayName(track.mimeType, null, track.displayName)
        val bitrateKbps = if (track.bitrate >= 1000) track.bitrate / 1000 else track.bitrate
        return AudioOutputInfo(codecLabel = codec, bitrateKbps = bitrateKbps)
    }

    private fun codecDisplayName(sampleMime: String?, containerMime: String?, fallback: String?): String {
        val raw = (sampleMime ?: containerMime ?: fallback).orEmpty().lowercase(Locale.US)
        return when {
            raw == MimeTypes.AUDIO_MPEG || raw.contains("mpeg") || raw.endsWith(".mp3") -> "MP3"
            raw == MimeTypes.AUDIO_FLAC || raw.contains("flac") -> "FLAC"
            raw == MimeTypes.AUDIO_OPUS || raw.contains("opus") -> "Opus"
            raw == MimeTypes.AUDIO_VORBIS || raw.contains("vorbis") || raw.contains("ogg") -> "Ogg"
            raw == MimeTypes.AUDIO_AAC || raw.contains("mp4") || raw.contains("aac") || raw.endsWith(".m4a") -> "AAC"
            raw == MimeTypes.AUDIO_RAW || raw.contains("wav") -> "PCM"
            raw == MimeTypes.AUDIO_ALAC || raw.contains("alac") -> "ALAC"
            raw.contains("webm") -> "WebM"
            else -> ""
        }
    }
}

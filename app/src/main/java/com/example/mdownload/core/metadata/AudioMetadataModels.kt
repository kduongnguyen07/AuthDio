package com.example.mdownload.core.metadata

import android.net.Uri
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.lyrics.AudioContainerFormat

data class FileSnapshot(
    val uri: String,
    val sizeBytes: Long,
    val dateModifiedSec: Long
)

data class EditableTrackMetadata(
    val title: String = "",
    val artist: String = "",
    val albumArtist: String = "",
    val album: String = "",
    val genre: String = "",
    val year: String = "",
    val trackNumber: String = "",
    val trackTotal: String = "",
    val discNumber: String = "",
    val discTotal: String = "",
    val composer: String = "",
    val comment: String = "",
    val artworkBytes: ByteArray? = null,
    val artworkMimeType: String = "image/jpeg",
    val artworkChanged: Boolean = false,
    val artworkRemoved: Boolean = false,
    val format: AudioContainerFormat = AudioContainerFormat.UNSUPPORTED
) {
    val hasArtwork: Boolean
        get() = !artworkRemoved && artworkBytes != null && artworkBytes.isNotEmpty()

    fun identityChangedFrom(other: EditableTrackMetadata): Boolean =
        title.trim() != other.title.trim() ||
            artist.trim() != other.artist.trim() ||
            album.trim() != other.album.trim()

    fun hasTextOrArtworkChangesFrom(original: EditableTrackMetadata): Boolean =
        title != original.title ||
            artist != original.artist ||
            albumArtist != original.albumArtist ||
            album != original.album ||
            genre != original.genre ||
            year != original.year ||
            trackNumber != original.trackNumber ||
            trackTotal != original.trackTotal ||
            discNumber != original.discNumber ||
            discTotal != original.discTotal ||
            composer != original.composer ||
            comment != original.comment ||
            artworkChanged ||
            artworkRemoved

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EditableTrackMetadata) return false
        return title == other.title &&
            artist == other.artist &&
            albumArtist == other.albumArtist &&
            album == other.album &&
            genre == other.genre &&
            year == other.year &&
            trackNumber == other.trackNumber &&
            trackTotal == other.trackTotal &&
            discNumber == other.discNumber &&
            discTotal == other.discTotal &&
            composer == other.composer &&
            comment == other.comment &&
            artworkMimeType == other.artworkMimeType &&
            artworkChanged == other.artworkChanged &&
            artworkRemoved == other.artworkRemoved &&
            format == other.format &&
            (artworkBytes?.contentEquals(other.artworkBytes) ?: (other.artworkBytes == null))
    }

    override fun hashCode(): Int {
        var result = title.hashCode()
        result = 31 * result + artist.hashCode()
        result = 31 * result + albumArtist.hashCode()
        result = 31 * result + album.hashCode()
        result = 31 * result + genre.hashCode()
        result = 31 * result + year.hashCode()
        result = 31 * result + trackNumber.hashCode()
        result = 31 * result + trackTotal.hashCode()
        result = 31 * result + discNumber.hashCode()
        result = 31 * result + discTotal.hashCode()
        result = 31 * result + composer.hashCode()
        result = 31 * result + comment.hashCode()
        result = 31 * result + (artworkBytes?.contentHashCode() ?: 0)
        result = 31 * result + artworkChanged.hashCode()
        result = 31 * result + artworkRemoved.hashCode()
        return result
    }
}

/**
 * Patch for batch editing multiple tracks.
 * Null fields are left completely untouched on each file.
 * Deliberately excludes Title and Track Number to prevent corrupting unique per-track identifiers.
 */
data class BatchMetadataPatch(
    val artist: String? = null,
    val albumArtist: String? = null,
    val album: String? = null,
    val genre: String? = null,
    val year: String? = null,
    val composer: String? = null,
    val comment: String? = null,
    val artworkBytes: ByteArray? = null,
    val artworkMimeType: String = "image/jpeg",
    val replaceArtwork: Boolean = false,
    val removeArtwork: Boolean = false
) {
    val hasAnyChange: Boolean
        get() = artist != null || albumArtist != null || album != null || genre != null ||
            year != null || composer != null || comment != null || replaceArtwork || removeArtwork

    fun applyTo(existing: EditableTrackMetadata): EditableTrackMetadata = existing.copy(
        artist = artist ?: existing.artist,
        albumArtist = albumArtist ?: existing.albumArtist,
        album = album ?: existing.album,
        genre = genre ?: existing.genre,
        year = year ?: existing.year,
        composer = composer ?: existing.composer,
        comment = comment ?: existing.comment,
        artworkBytes = when {
            removeArtwork -> null
            replaceArtwork -> artworkBytes
            else -> existing.artworkBytes
        },
        artworkMimeType = if (replaceArtwork) artworkMimeType else existing.artworkMimeType,
        artworkChanged = replaceArtwork,
        artworkRemoved = removeArtwork
    )
}

data class MetadataValidationResult(
    val fieldErrors: Map<String, String> = emptyMap()
) {
    val isValid: Boolean
        get() = fieldErrors.isEmpty()
}

sealed interface MetadataWriteResult {
    data class Success(
        val format: AudioContainerFormat,
        val verifiedReadBack: EditableTrackMetadata?,
        val detail: String
    ) : MetadataWriteResult

    data class RequiresWritePermission(
        val uris: List<Uri>,
        val message: String
    ) : MetadataWriteResult

    data class Unsupported(val reason: String) : MetadataWriteResult
    data class Conflict(val reason: String) : MetadataWriteResult
    data class Failed(val reason: String) : MetadataWriteResult
}

data class BatchMetadataResult(
    val trackResults: List<Pair<LibraryTrack, MetadataWriteResult>>,
    val cancelled: Boolean
) {
    val succeededCount: Int
        get() = trackResults.count { it.second is MetadataWriteResult.Success }

    val failedCount: Int
        get() = trackResults.count { it.second !is MetadataWriteResult.Success }
}

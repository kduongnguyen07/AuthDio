package com.example.mdownload.player.library

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import java.util.Locale

data class AlbumGroup(
    val id: String,
    val title: String,
    val artist: String,
    val albumId: Long,
    val coverTrackUri: String,
    val tracks: List<LibraryTrack>
) {
    val totalDurationMs: Long
        get() = tracks.sumOf { it.durationMs }
}

data class ArtistGroup(
    val id: String,
    val name: String,
    val coverTrackUri: String,
    val coverAlbumId: Long,
    val customCoverPath: String = "",
    val isCustomProfile: Boolean = false,
    val albums: List<AlbumGroup>,
    val tracks: List<LibraryTrack>
) {
    val albumCount: Int
        get() = albums.size
}

data class FolderGroup(
    val path: String,
    val displayName: String,
    val coverTrackUri: String,
    val coverAlbumId: Long,
    val tracks: List<LibraryTrack>
) {
    val totalSizeBytes: Long
        get() = tracks.sumOf { it.sizeBytes }
}

/**
 * Pure, deterministic library grouping & deduplication helpers for Songs, Albums, Artists, and Folders.
 */
object LibraryGroupingHelper {

    fun deduplicateTracks(tracks: List<LibraryTrack>): List<LibraryTrack> {
        val byUri = LinkedHashMap<String, LibraryTrack>()
        val seenFileFingerprints = HashSet<String>()

        for (track in tracks) {
            if (track.uri.isBlank()) continue
            val existingByUri = byUri[track.uri]
            if (existingByUri != null) {
                byUri[track.uri] = mergeRicher(existingByUri, track)
                continue
            }

            val fingerprint = fileFingerprint(track)
            if (fingerprint != null && !seenFileFingerprints.add(fingerprint)) {
                // Already represented by another URI (e.g., SAF + MediaStore overlap for same file).
                val existingKey = byUri.entries.firstOrNull { fileFingerprint(it.value) == fingerprint }?.key
                if (existingKey != null) {
                    byUri[existingKey] = mergeRicher(byUri.getValue(existingKey), track)
                }
                continue
            }

            byUri[track.uri] = track
        }

        return byUri.values.toList()
    }

    fun groupAlbums(tracks: List<LibraryTrack>): List<AlbumGroup> {
        return tracks
            .groupBy { track ->
                val albumName = track.album.trim().ifBlank { "Singles & Unknown Album" }
                normalizeKey(albumName)
            }
            .map { (key, groupTracks) ->
                val sortedTracks = groupTracks.sortedBy { normalizeKey(it.title) }
                val representative = sortedTracks.first()
                val title = representative.album.trim().ifBlank { "Singles & Unknown Album" }
                val artists = sortedTracks.map { it.artist }.distinct()
                val artistLabel = if (artists.size == 1) artists.first() else "Various Artists"
                AlbumGroup(
                    id = key,
                    title = title,
                    artist = artistLabel,
                    albumId = sortedTracks.firstOrNull { it.albumId > 0L }?.albumId ?: 0L,
                    coverTrackUri = representative.uri,
                    tracks = sortedTracks
                )
            }
            .sortedBy { normalizeKey(it.title) }
    }

    fun groupArtists(
        tracks: List<LibraryTrack>,
        profiles: List<CustomArtistProfile> = emptyList()
    ): List<ArtistGroup> {
        val profileByKey = profiles.associateBy { normalizeKey(it.name) }
        val groupedByTrack = tracks.groupBy { track ->
            normalizeKey(track.artist.trim().ifBlank { "Unknown artist" })
        }

        val allKeys = LinkedHashSet<String>().apply {
            addAll(groupedByTrack.keys)
            addAll(profileByKey.keys)
        }

        return allKeys.mapNotNull { key ->
            val groupTracks = groupedByTrack[key].orEmpty()
            val profile = profileByKey[key]
            if (groupTracks.isEmpty() && profile == null) return@mapNotNull null

            val sortedTracks = groupTracks.sortedBy { normalizeKey(it.title) }
            val representative = sortedTracks.firstOrNull()
            val displayName = profile?.name?.takeIf { it.isNotBlank() }
                ?: representative?.artist?.trim()?.ifBlank { "Unknown artist" }
                ?: "Unknown artist"

            ArtistGroup(
                id = key,
                name = displayName,
                coverTrackUri = representative?.uri.orEmpty(),
                coverAlbumId = sortedTracks.firstOrNull { it.albumId > 0L }?.albumId ?: 0L,
                customCoverPath = profile?.coverPath.orEmpty(),
                isCustomProfile = profile != null,
                albums = if (sortedTracks.isNotEmpty()) groupAlbums(sortedTracks) else emptyList(),
                tracks = sortedTracks
            )
        }.sortedBy { normalizeKey(it.name) }
    }

    fun groupFolders(tracks: List<LibraryTrack>): List<FolderGroup> {
        return tracks
            .groupBy { track ->
                track.folderPath.trim('/').ifBlank { "Music" }
            }
            .map { (path, groupTracks) ->
                val sortedTracks = groupTracks.sortedByDescending { it.dateAddedSec }
                val representative = sortedTracks.first()
                val displayName = representative.folderName.ifBlank {
                    path.substringAfterLast('/').ifBlank { path }
                }
                FolderGroup(
                    path = path,
                    displayName = displayName,
                    coverTrackUri = representative.uri,
                    coverAlbumId = sortedTracks.firstOrNull { it.albumId > 0L }?.albumId ?: 0L,
                    tracks = sortedTracks
                )
            }
            .sortedBy { normalizeKey(it.displayName) }
    }

    fun downloadedTracks(tracks: List<LibraryTrack>): List<LibraryTrack> =
        tracks
            .filter { it.isDownloadedByApp }
            .sortedByDescending { it.dateAddedSec }

    fun recentlyAddedTracks(tracks: List<LibraryTrack>, limit: Int = 50): List<LibraryTrack> =
        tracks
            .sortedByDescending { it.dateAddedSec }
            .take(limit)

    private fun fileFingerprint(track: LibraryTrack): String? {
        val name = track.displayName.trim().lowercase(Locale.ROOT)
        if (name.isBlank() || track.sizeBytes <= 0L) return null
        return "$name|${track.sizeBytes}"
    }

    private fun mergeRicher(first: LibraryTrack, second: LibraryTrack): LibraryTrack {
        return first.copy(
            album = first.album.ifBlank { second.album },
            videoId = first.videoId.ifBlank { second.videoId },
            albumId = if (first.albumId > 0L) first.albumId else second.albumId,
            isDownloadedByApp = first.isDownloadedByApp || second.isDownloadedByApp,
            durationMs = if (first.durationMs > 0L) first.durationMs else second.durationMs,
            bitrate = if (first.bitrate > 0) first.bitrate else second.bitrate,
            folderPath = first.folderPath.ifBlank { second.folderPath },
            folderName = first.folderName.ifBlank { second.folderName }
        )
    }

    private fun normalizeKey(raw: String): String =
        TextUtils.asciiText(raw).lowercase(Locale.ROOT).ifBlank { raw.lowercase(Locale.ROOT) }
}

package com.example.mdownload.player.library

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.mdownload.core.AudioTagFingerprint
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.RepositoryLoadState
import com.example.mdownload.engine.MediaStoreExporter
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Offline-first local music library repository backed by Android MediaStore + optional SAF folder.
 * Supports full scans, incremental single-track ingestion after downloads finish, missing-file
 * detection, deduplication, and user-created playlists without blocking the UI thread.
 */
class MusicLibraryRepository(
    private val appContext: Context,
    private val exporter: MediaStoreExporter,
    val artworkCache: ArtworkCache,
    private val playlistStore: PlaylistStore,
    private val artistStore: ArtistStore = ArtistStore(appContext)
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val tracksLock = Any()
    private val storesHydrated = CompletableDeferred<Unit>()

    private val _metadataLoadState = MutableStateFlow(RepositoryLoadState.LOADING)
    val metadataLoadState: StateFlow<RepositoryLoadState> = _metadataLoadState.asStateFlow()

    private var rawTracks: List<LibraryTrack> = emptyList()

    private val _tracks = MutableStateFlow<List<LibraryTrack>>(emptyList())
    val tracks: StateFlow<List<LibraryTrack>> = _tracks.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _hasAudioPermission = MutableStateFlow(isAudioPermissionGranted())
    val hasAudioPermission: StateFlow<Boolean> = _hasAudioPermission.asStateFlow()

    private val _playlists = MutableStateFlow<List<UserPlaylist>>(emptyList())
    val playlists: StateFlow<List<UserPlaylist>> = _playlists.asStateFlow()

    private val _artistCatalog = MutableStateFlow(ArtistCatalogState())
    val artistCatalog: StateFlow<ArtistCatalogState> = _artistCatalog.asStateFlow()

    init {
        scope.launch {
            var failed = false
            val loadedPlaylists = runCatching { playlistStore.load() }.getOrElse {
                failed = true
                emptyList()
            }
            val loadedArtists = runCatching { artistStore.load() }.getOrElse {
                failed = true
                ArtistCatalogState()
            }
            synchronized(tracksLock) {
                val earlyPlaylists = _playlists.value
                if (earlyPlaylists.isEmpty()) {
                    _playlists.value = loadedPlaylists
                } else {
                    val existingIds = earlyPlaylists.mapTo(HashSet()) { it.id }
                    _playlists.value = earlyPlaylists + loadedPlaylists.filter { it.id !in existingIds }
                }

                val earlyArtists = _artistCatalog.value
                if (earlyArtists.profiles.isEmpty() &&
                    earlyArtists.trackArtistOverrides.isEmpty() &&
                    earlyArtists.artistNameAliases.isEmpty()
                ) {
                    _artistCatalog.value = loadedArtists
                } else {
                    val earlyProfileKeys = earlyArtists.profiles.mapTo(HashSet()) {
                        ArtistLibraryHelper.normalizeKey(it.name)
                    }
                    _artistCatalog.value = ArtistCatalogState(
                        profiles = loadedArtists.profiles.filter {
                            ArtistLibraryHelper.normalizeKey(it.name) !in earlyProfileKeys
                        } + earlyArtists.profiles,
                        trackArtistOverrides = loadedArtists.trackArtistOverrides + earlyArtists.trackArtistOverrides,
                        artistNameAliases = loadedArtists.artistNameAliases + earlyArtists.artistNameAliases
                    )
                }
                publishEffectiveTracksLocked()
            }
            _metadataLoadState.value = if (failed) RepositoryLoadState.FAILED else RepositoryLoadState.READY
            storesHydrated.complete(Unit)
        }
    }

    suspend fun awaitMetadataReady() {
        storesHydrated.await()
    }

    private fun publishEffectiveTracksLocked() {
        _tracks.value = ArtistLibraryHelper.applyCatalog(rawTracks, _artistCatalog.value)
    }

    private fun isAudioPermissionGranted(): Boolean =
        ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_MEDIA_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

    fun checkAudioPermission(): Boolean {
        val granted = isAudioPermissionGranted()
        _hasAudioPermission.value = granted
        return granted
    }

    fun refreshLibrary(
        customTreeUri: String,
        scanAllDeviceMusic: Boolean,
        onComplete: ((Int) -> Unit)? = null
    ) {
        scope.launch {
            _isSyncing.value = true
            try {
                checkAudioPermission()
                val combined = mutableListOf<LibraryTrack>()

                // Always include active custom SAF folder if configured and accessible.
                if (customTreeUri.isNotBlank() && exporter.hasValidTreePermission(customTreeUri)) {
                    val folderLabel = exporter.formatTreeLabel(Uri.parse(customTreeUri))
                    val safTracks = exporter.scanLibraryTracks(customTreeUri).map { t ->
                        t.copy(
                            folderPath = folderLabel,
                            folderName = folderLabel.substringAfterLast('/').ifBlank { folderLabel },
                            isDownloadedByApp = true
                        )
                    }
                    combined.addAll(safTracks)
                }

                // Query MediaStore: either full local music library or HiFiDeck's Music/MDownload folder.
                val mediaStoreTracks = queryMediaStoreAudio(includeAllDeviceAudio = scanAllDeviceMusic)
                combined.addAll(mediaStoreTracks)

                val deduplicated = LibraryGroupingHelper.deduplicateTracks(combined)
                    .sortedByDescending { it.dateAddedSec }
                synchronized(tracksLock) {
                    rawTracks = deduplicated
                    publishEffectiveTracksLocked()
                }
                onComplete?.invoke(deduplicated.size)
            } finally {
                _isSyncing.value = false
            }
        }
    }

    /**
     * Incrementally inserts or updates a single newly exported track without rescanning
     * the entire device library.
     */
    fun ingestExportedTrack(
        uriString: String,
        fallbackTitle: String = "",
        fallbackArtist: String = "",
        fallbackAlbum: String = "",
        fallbackDisplayName: String = "",
        videoId: String = "",
        customFolderLabel: String = ""
    ) {
        if (uriString.isBlank()) return
        scope.launch {
            artworkCache.invalidate(uriString)
            val resolved = querySingleTrack(
                uriString = uriString,
                fallbackTitle = fallbackTitle,
                fallbackArtist = fallbackArtist,
                fallbackAlbum = fallbackAlbum,
                fallbackDisplayName = fallbackDisplayName,
                videoId = videoId,
                customFolderLabel = customFolderLabel
            ) ?: return@launch

            synchronized(tracksLock) {
                val merged = listOf(resolved) + rawTracks.filterNot { it.uri == resolved.uri }
                rawTracks = LibraryGroupingHelper.deduplicateTracks(merged)
                    .sortedByDescending { it.dateAddedSec }
                publishEffectiveTracksLocked()
            }
        }
    }

    suspend fun isUriAccessible(uriString: String): Boolean = withContext(Dispatchers.IO) {
        if (uriString.isBlank()) return@withContext false
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return@withContext false
        runCatching {
            appContext.contentResolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
                afd.length != 0L
            } ?: false
        }.getOrDefault(false)
    }

    suspend fun removeMissingTrack(uriString: String) {
        artworkCache.invalidate(uriString)
        synchronized(tracksLock) {
            rawTracks = rawTracks.filterNot { it.uri == uriString }
            publishEffectiveTracksLocked()
        }
    }

    suspend fun deleteTrack(track: LibraryTrack): Boolean = withContext(Dispatchers.IO) {
        val deleted = exporter.deleteTrack(track.uri)
        if (deleted) {
            artworkCache.invalidate(track.uri)
            synchronized(tracksLock) {
                rawTracks = rawTracks.filterNot { it.uri == track.uri }
                val updatedCatalog = ArtistLibraryHelper.removeDeletedTrack(_artistCatalog.value, track.uri)
                if (updatedCatalog != _artistCatalog.value) {
                    _artistCatalog.value = updatedCatalog
                    persistArtistCatalog()
                }
                publishEffectiveTracksLocked()
            }
            removeUriFromAllPlaylists(track.uri)
        }
        deleted
    }

    suspend fun loadArtwork(track: LibraryTrack, highRes: Boolean = false): Bitmap? =
        artworkCache.loadArtwork(track.uri, track.albumId, highRes = highRes)

    suspend fun loadArtworkByUri(uri: String, albumId: Long = 0L, highRes: Boolean = false): Bitmap? =
        artworkCache.loadArtwork(uri, albumId, highRes = highRes)

    fun clearArtworkCache() {
        artworkCache.clear()
    }

    // --- User Playlist CRUD ---

    fun createPlaylist(name: String, initialTrackUris: List<String> = emptyList()): UserPlaylist? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        val now = System.currentTimeMillis()
        val created = UserPlaylist(
            name = clean,
            trackUris = initialTrackUris.distinct(),
            createdAtMs = now,
            updatedAtMs = now
        )
        _playlists.update { listOf(created) + it }
        persistPlaylists()
        return created
    }

    fun renamePlaylist(playlistId: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isBlank()) return false
        var changed = false
        _playlists.update { list ->
            list.map { pl ->
                if (pl.id == playlistId) {
                    changed = true
                    pl.copy(name = clean, updatedAtMs = System.currentTimeMillis())
                } else pl
            }
        }
        if (changed) persistPlaylists()
        return changed
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.update { list -> list.filterNot { it.id == playlistId } }
        persistPlaylists()
    }

    fun addTracksToPlaylist(playlistId: String, uris: List<String>): Int {
        if (uris.isEmpty()) return 0
        var addedCount = 0
        _playlists.update { list ->
            list.map { pl ->
                if (pl.id == playlistId) {
                    val existing = pl.trackUris.toSet()
                    val fresh = uris.filter { it.isNotBlank() && it !in existing }
                    addedCount = fresh.size
                    if (fresh.isNotEmpty()) {
                        pl.copy(
                            trackUris = pl.trackUris + fresh,
                            updatedAtMs = System.currentTimeMillis()
                        )
                    } else pl
                } else pl
            }
        }
        if (addedCount > 0) persistPlaylists()
        return addedCount
    }

    fun removeTrackFromPlaylist(playlistId: String, trackUri: String) {
        _playlists.update { list ->
            list.map { pl ->
                if (pl.id == playlistId) {
                    pl.copy(
                        trackUris = pl.trackUris.filterNot { it == trackUri },
                        updatedAtMs = System.currentTimeMillis()
                    )
                } else pl
            }
        }
        persistPlaylists()
    }

    private fun removeUriFromAllPlaylists(trackUri: String) {
        var changed = false
        _playlists.update { list ->
            list.map { pl ->
                if (trackUri in pl.trackUris) {
                    changed = true
                    pl.copy(
                        trackUris = pl.trackUris.filterNot { it == trackUri },
                        updatedAtMs = System.currentTimeMillis()
                    )
                } else pl
            }
        }
        if (changed) persistPlaylists()
    }

    private fun persistPlaylists() {
        scope.launch {
            storesHydrated.await()
            playlistStore.save(_playlists.value)
        }
    }

    // --- Artist Management CRUD ---

    fun createArtist(
        name: String,
        coverPath: String = "",
        initialTrackUris: List<String> = emptyList()
    ): CustomArtistProfile? {
        val (updated, profile) = synchronized(tracksLock) {
            val pair = ArtistLibraryHelper.createOrUpdateArtist(
                catalog = _artistCatalog.value,
                name = name,
                coverPath = coverPath,
                initialTrackUris = initialTrackUris
            )
            if (pair.second != null) {
                _artistCatalog.value = pair.first
                publishEffectiveTracksLocked()
            }
            pair
        }
        if (profile != null) {
            persistArtistCatalog()
        }
        return profile
    }

    fun editArtist(
        oldName: String,
        newName: String,
        coverPath: String? = null,
        clearCover: Boolean = false
    ): Boolean {
        val cleanOld = oldName.trim()
        val cleanNew = newName.trim()
        if (cleanOld.isBlank() || cleanNew.isBlank()) return false

        val oldCoverToClean: String?
        val updated = synchronized(tracksLock) {
            val prevCatalog = _artistCatalog.value
            val prevProfile = prevCatalog.profiles.firstOrNull {
                ArtistLibraryHelper.normalizeKey(it.name) == ArtistLibraryHelper.normalizeKey(cleanOld)
            }
            val next = ArtistLibraryHelper.editArtist(
                catalog = prevCatalog,
                oldName = cleanOld,
                newName = cleanNew,
                newCoverPath = coverPath,
                clearCover = clearCover
            )
            val newProfile = next.profiles.firstOrNull {
                ArtistLibraryHelper.normalizeKey(it.name) == ArtistLibraryHelper.normalizeKey(cleanNew)
            }
            oldCoverToClean = prevProfile?.coverPath
                ?.takeIf { it.isNotBlank() && it != newProfile?.coverPath }
            _artistCatalog.value = next
            publishEffectiveTracksLocked()
            next
        }
        persistArtistCatalog()
        cleanupUnusedCoverFile(oldCoverToClean, updated)
        return true
    }

    fun deleteArtist(artistName: String): Boolean {
        val (updated, removedCover) = synchronized(tracksLock) {
            val result = ArtistLibraryHelper.deleteArtist(_artistCatalog.value, artistName)
            if (result.first == _artistCatalog.value) return false
            _artistCatalog.value = result.first
            publishEffectiveTracksLocked()
            result
        }
        persistArtistCatalog()
        cleanupUnusedCoverFile(removedCover, updated)
        return true
    }

    fun setArtistTracks(artistName: String, selectedTrackUris: Set<String>): Pair<Int, Int> {
        val (updated, added, removed) = synchronized(tracksLock) {
            val result = ArtistLibraryHelper.syncArtistTracks(
                catalog = _artistCatalog.value,
                artistName = artistName,
                selectedTrackUris = selectedTrackUris,
                rawTracks = rawTracks
            )
            if (result.second > 0 || result.third > 0) {
                _artistCatalog.value = result.first
                publishEffectiveTracksLocked()
            }
            result
        }
        if (added > 0 || removed > 0) {
            persistArtistCatalog()
        }
        return added to removed
    }

    fun setTrackArtist(trackUri: String, targetArtistName: String): Boolean {
        if (trackUri.isBlank() || targetArtistName.isBlank()) return false
        val updated = synchronized(tracksLock) {
            val next = ArtistLibraryHelper.assignTrackArtist(_artistCatalog.value, trackUri, targetArtistName)
            _artistCatalog.value = next
            publishEffectiveTracksLocked()
            next
        }
        persistArtistCatalog()
        return true
    }

    fun resetTrackArtist(trackUri: String): Boolean {
        val updated = synchronized(tracksLock) {
            val prev = _artistCatalog.value
            val next = ArtistLibraryHelper.resetTrackArtist(prev, trackUri)
            if (next == prev) return false
            _artistCatalog.value = next
            publishEffectiveTracksLocked()
            next
        }
        persistArtistCatalog()
        return true
    }

    fun originalArtistForTrack(trackUri: String): String? = synchronized(tracksLock) {
        rawTracks.firstOrNull { it.uri == trackUri }?.artist
    }

    /**
     * Immediately reflects verified on-disk tag edits in memory and invalidates artwork cache.
     * Clears any stale artist override for this track when its embedded artist tag was updated.
     */
    fun applyEditedMetadata(
        trackUri: String,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        year: Int,
        trackNumber: Int,
        artworkModified: Boolean
    ) {
        if (artworkModified) {
            artworkCache.invalidate(trackUri)
        }
        val updatedCatalog = synchronized(tracksLock) {
            val nowSec = System.currentTimeMillis() / 1000L
            rawTracks = rawTracks.map { t ->
                if (t.uri == trackUri) {
                    t.copy(
                        title = title.trim().ifBlank { t.title },
                        artist = artist.trim().ifBlank { "Unknown artist" },
                        album = album.trim(),
                        albumArtist = albumArtist.trim(),
                        genre = genre.trim(),
                        year = year,
                        trackNumber = trackNumber,
                        dateModifiedSec = nowSec
                    )
                } else {
                    t
                }
            }
            val prevCat = _artistCatalog.value
            val nextCat = if (artist.isNotBlank() && prevCat.trackArtistOverrides.containsKey(trackUri)) {
                ArtistLibraryHelper.resetTrackArtist(prevCat, trackUri)
            } else {
                prevCat
            }
            _artistCatalog.value = nextCat
            publishEffectiveTracksLocked()
            if (nextCat != prevCat) nextCat else null
        }
        if (updatedCatalog != null) {
            persistArtistCatalog()
        }
    }

    private fun persistArtistCatalog() {
        scope.launch {
            storesHydrated.await()
            artistStore.save(_artistCatalog.value)
        }
    }

    private fun cleanupUnusedCoverFile(path: String?, catalog: ArtistCatalogState) {
        if (path.isNullOrBlank()) return
        if (catalog.profiles.any { it.coverPath == path }) return
        scope.launch {
            runCatching {
                val file = java.io.File(path)
                val artistDir = java.io.File(appContext.filesDir, "hifideck_artist_covers")
                if (file.isFile && file.canonicalPath.startsWith(artistDir.canonicalPath)) {
                    file.delete()
                }
            }
        }
    }

    // --- MediaStore queries ---

    private fun queryMediaStoreAudio(includeAllDeviceAudio: Boolean): List<LibraryTrack> {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.RELATIVE_PATH,
            MediaStore.Audio.Media.IS_MUSIC,
            MediaStore.Audio.Media.BITRATE,
            MediaStore.Audio.Media.GENRE,
            MediaStore.Audio.Media.YEAR,
            MediaStore.Audio.Media.ALBUM_ARTIST,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.DATE_MODIFIED
        )

        val selection: String
        val selectionArgs: Array<String>?
        if (includeAllDeviceAudio) {
            // Exclude pending/trashed files and tiny notification/UI sounds (< 5s non-music).
            selection = "${MediaStore.Audio.Media.IS_PENDING} = 0 AND " +
                "${MediaStore.Audio.Media.IS_TRASHED} = 0 AND " +
                "${MediaStore.Audio.Media.SIZE} >= 1024 AND " +
                "(${MediaStore.Audio.Media.IS_MUSIC} != 0 OR ${MediaStore.Audio.Media.DURATION} >= 15000)"
            selectionArgs = null
        } else {
            selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND " +
                "${MediaStore.Audio.Media.IS_PENDING} = 0 AND " +
                "${MediaStore.Audio.Media.IS_TRASHED} = 0"
            selectionArgs = arrayOf("%${MediaStoreExporter.PUBLIC_SUBFOLDER}%")
        }

        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"
        val results = mutableListOf<LibraryTrack>()

        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
                val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                val bitrateCol = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)
                val genreCol = cursor.getColumnIndex(MediaStore.Audio.Media.GENRE)
                val yearCol = cursor.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val albumArtistCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ARTIST)
                val trackNumCol = cursor.getColumnIndex(MediaStore.Audio.Media.TRACK)
                val dateModCol = cursor.getColumnIndex(MediaStore.Audio.Media.DATE_MODIFIED)

                while (cursor.moveToNext()) {
                    val displayName = cursor.getString(nameCol).orEmpty()
                    if (displayName.startsWith(".mdownload_tmp_") || displayName.endsWith(".part")) {
                        continue
                    }
                    val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty().trim('/') else ""
                    val lowerPath = relPath.lowercase(Locale.ROOT)
                    if (includeAllDeviceAudio && (
                            lowerPath.startsWith("ringtones") ||
                                lowerPath.startsWith("notifications") ||
                                lowerPath.startsWith("alarms")
                            )
                    ) {
                        continue
                    }

                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val sizeBytes = cursor.getLong(sizeCol)
                    val isAppDownload = relPath.contains(MediaStoreExporter.PUBLIC_SUBFOLDER, ignoreCase = true)

                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    val artist = cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown artist"
                    val album = cursor.getString(albumCol)
                        ?.takeIf {
                            it.isNotBlank() &&
                                it != "<unknown>" &&
                                !it.equals(MediaStoreExporter.PUBLIC_SUBFOLDER, ignoreCase = true)
                        }
                        .orEmpty()
                    val albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L
                    val bitrate = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0
                    val genre = if (genreCol >= 0) cursor.getString(genreCol)?.takeIf { it != "<unknown>" }.orEmpty() else ""
                    val year = if (yearCol >= 0) cursor.getInt(yearCol).coerceAtLeast(0) else 0
                    val albumArtist = if (albumArtistCol >= 0) {
                        cursor.getString(albumArtistCol)?.takeIf { it != "<unknown>" }.orEmpty()
                    } else ""
                    val rawTrackNum = if (trackNumCol >= 0) cursor.getInt(trackNumCol).coerceAtLeast(0) else 0
                    val trackNumber = if (rawTrackNum >= 1000) rawTrackNum % 1000 else rawTrackNum
                    val dateModifiedSec = if (dateModCol >= 0) cursor.getLong(dateModCol).coerceAtLeast(0L) else 0L

                    // Only inspect embedded hidden YouTube video ID for HiFiDeck downloads or bracketed filenames
                    // so device-wide library scans remain instant on large music collections.
                    val videoId = AudioTagFingerprint.extractVideoIdFromDisplayName(displayName)
                        ?: if (isAppDownload) {
                            AudioTagFingerprint.extractVideoIdFromUri(appContext, contentUri, sizeBytes).orEmpty()
                        } else {
                            ""
                        }

                    val folderPath = relPath.ifBlank { "Music" }
                    val folderName = folderPath.substringAfterLast('/').ifBlank { folderPath }

                    results.add(
                        LibraryTrack(
                            id = id,
                            uri = contentUri.toString(),
                            displayName = displayName,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = cursor.getLong(durCol).coerceAtLeast(0L),
                            sizeBytes = sizeBytes,
                            dateAddedSec = cursor.getLong(dateCol),
                            mimeType = cursor.getString(mimeCol).orEmpty(),
                            videoId = videoId,
                            albumId = albumId,
                            folderPath = folderPath,
                            folderName = folderName,
                            isDownloadedByApp = isAppDownload,
                            bitrate = bitrate,
                            genre = genre,
                            year = year,
                            albumArtist = albumArtist,
                            trackNumber = trackNumber,
                            dateModifiedSec = dateModifiedSec
                        )
                    )
                }
            }
        }

        return results
    }

    private fun querySingleTrack(
        uriString: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String,
        fallbackDisplayName: String,
        videoId: String,
        customFolderLabel: String
    ): LibraryTrack? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val resolver = appContext.contentResolver

        // Try MediaStore single-row lookup first (instant, zero full-device scan).
        if (!DocumentsContract.isDocumentUri(appContext, uri)) {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.RELATIVE_PATH,
                MediaStore.Audio.Media.BITRATE
            )
            val fromMediaStore = runCatching {
                resolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val id = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID))
                    val displayName = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME))
                        .orEmpty().ifBlank { fallbackDisplayName }
                    val sizeBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
                    val relPathCol = cursor.getColumnIndex(MediaStore.Audio.Media.RELATIVE_PATH)
                    val relPath = if (relPathCol >= 0) cursor.getString(relPathCol).orEmpty().trim('/') else MediaStoreExporter.RELATIVE_MUSIC_PATH
                    val albumIdCol = cursor.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                    val bitrateCol = cursor.getColumnIndex(MediaStore.Audio.Media.BITRATE)

                    val title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE))
                        ?.takeIf { it.isNotBlank() }
                        ?: fallbackTitle.ifBlank { displayName.substringBeforeLast('.') }
                    val artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST))
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: fallbackArtist.ifBlank { "Unknown artist" }
                    val album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM))
                        ?.takeIf {
                            it.isNotBlank() &&
                                it != "<unknown>" &&
                                !it.equals(MediaStoreExporter.PUBLIC_SUBFOLDER, ignoreCase = true)
                        }
                        ?: fallbackAlbum

                    val resolvedVideoId = videoId.ifBlank {
                        AudioTagFingerprint.extractVideoIdFromDisplayName(displayName)
                            ?: AudioTagFingerprint.extractVideoIdFromUri(appContext, uri, sizeBytes).orEmpty()
                    }
                    val folderPath = relPath.ifBlank { MediaStoreExporter.RELATIVE_MUSIC_PATH }
                    LibraryTrack(
                        id = id,
                        uri = uriString,
                        displayName = displayName,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)).coerceAtLeast(0L),
                        sizeBytes = sizeBytes,
                        dateAddedSec = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED))
                            .takeIf { it > 0L } ?: (System.currentTimeMillis() / 1000L),
                        mimeType = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)).orEmpty(),
                        videoId = resolvedVideoId,
                        albumId = if (albumIdCol >= 0) cursor.getLong(albumIdCol) else 0L,
                        folderPath = folderPath,
                        folderName = folderPath.substringAfterLast('/').ifBlank { folderPath },
                        isDownloadedByApp = true,
                        bitrate = if (bitrateCol >= 0) cursor.getInt(bitrateCol) else 0
                    )
                }
            }.getOrNull()
            if (fromMediaStore != null) return fromMediaStore
        }

        // Fallback for SAF documents or MediaStore entries still indexing duration: inspect via MediaMetadataRetriever.
        var title = fallbackTitle
        var artist = fallbackArtist
        var album = fallbackAlbum
        var durationMs = 0L
        var bitrate = 0
        var sizeBytes = 0L
        var mimeType = resolver.getType(uri).orEmpty()
        var displayName = fallbackDisplayName

        runCatching {
            resolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { c ->
                if (c.moveToFirst()) {
                    val nameIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val sizeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val mimeIdx = c.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    if (nameIdx >= 0) displayName = c.getString(nameIdx).orEmpty().ifBlank { displayName }
                    if (sizeIdx >= 0) sizeBytes = c.getLong(sizeIdx)
                    if (mimeIdx >= 0) mimeType = c.getString(mimeIdx).orEmpty().ifBlank { mimeType }
                }
            }
        }

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, uri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() } ?: title
            artist = (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST))
                ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: artist
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeIf { it.isNotBlank() && it != "<unknown>" } ?: album
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                ?.toIntOrNull() ?: 0
        } catch (_: Exception) {
            // Keep fallbacks
        } finally {
            runCatching { retriever.release() }
        }

        val resolvedVideoId = videoId.ifBlank {
            AudioTagFingerprint.extractVideoIdFromDisplayName(displayName)
                ?: AudioTagFingerprint.extractVideoIdFromUri(appContext, uri, sizeBytes).orEmpty()
        }
        val folderPath = customFolderLabel.ifBlank { MediaStoreExporter.RELATIVE_MUSIC_PATH }
        return LibraryTrack(
            id = uriString.hashCode().toLong() and Long.MAX_VALUE,
            uri = uriString,
            displayName = displayName.ifBlank { title },
            title = title.ifBlank { displayName.substringBeforeLast('.').ifBlank { "Unknown track" } },
            artist = artist.ifBlank { "Unknown artist" },
            album = album,
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateAddedSec = System.currentTimeMillis() / 1000L,
            mimeType = mimeType.ifBlank { "audio/*" },
            videoId = resolvedVideoId,
            folderPath = folderPath,
            folderName = folderPath.substringAfterLast('/').ifBlank { folderPath },
            isDownloadedByApp = true,
            bitrate = bitrate
        )
    }
}

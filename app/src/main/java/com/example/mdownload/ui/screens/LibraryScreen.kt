package com.example.mdownload.ui.screens

import android.graphics.Bitmap
import android.net.Uri
import androidx.compose.runtime.Composable
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.library.ArtistCatalogState
import com.example.mdownload.player.library.UserPlaylist
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.ui.LibraryHubScreen

/**
 * Legacy entry point forwarding to [LibraryHubScreen] in the native music player UI package.
 */
@Composable
fun LibraryScreen(
    tracks: List<LibraryTrack>,
    playlists: List<UserPlaylist>,
    artistCatalog: ArtistCatalogState = ArtistCatalogState(),
    playbackState: PlaybackUiState,
    storageLabel: String,
    scanAllDeviceMusic: Boolean,
    hasAudioPermission: Boolean,
    isSyncing: Boolean,
    canChangeFolder: Boolean,
    onRequestAudioPermission: () -> Unit,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPlayTrackInQueue: (LibraryTrack, List<LibraryTrack>) -> Unit,
    onPlayQueue: (List<LibraryTrack>, Boolean) -> Unit,
    onPlayNext: (LibraryTrack) -> Unit,
    onAddToQueue: (LibraryTrack) -> Unit,
    onAddTrackToPlaylist: (LibraryTrack) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (String, String) -> Unit,
    onDeletePlaylist: (String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
    onDeleteTrack: (LibraryTrack) -> Unit,
    onImportArtistCover: suspend (Uri) -> String? = { null },
    onCreateArtist: (name: String, coverPath: String) -> Unit = { _, _ -> },
    onEditArtist: (oldName: String, newName: String, coverPath: String?, clearCover: Boolean) -> Unit = { _, _, _, _ -> },
    onDeleteArtist: (artistName: String) -> Unit = {},
    onSetArtistTracks: (artistName: String, selectedTrackUris: Set<String>) -> Unit = { _, _ -> },
    onSetTrackArtist: (LibraryTrack, String) -> Unit = { _, _ -> },
    onCreateArtistAndAssignTrack: (LibraryTrack, String) -> Unit = { _, _ -> },
    onResetTrackArtist: (LibraryTrack) -> Unit = {},
    originalArtistForTrack: (String) -> String? = { null },
    loadArtwork: suspend (String, Long, Boolean) -> Bitmap?
) {
    LibraryHubScreen(
        tracks = tracks,
        playlists = playlists,
        artistCatalog = artistCatalog,
        playbackState = playbackState,
        storageLabel = storageLabel,
        scanAllDeviceMusic = scanAllDeviceMusic,
        hasAudioPermission = hasAudioPermission,
        isSyncing = isSyncing,
        canChangeFolder = canChangeFolder,
        onRequestAudioPermission = onRequestAudioPermission,
        onPickFolder = onPickFolder,
        onRefresh = onRefresh,
        onPlayTrackInQueue = onPlayTrackInQueue,
        onPlayQueue = onPlayQueue,
        onPlayNext = onPlayNext,
        onAddToQueue = onAddToQueue,
        onAddTrackToPlaylist = onAddTrackToPlaylist,
        onCreatePlaylist = onCreatePlaylist,
        onRenamePlaylist = onRenamePlaylist,
        onDeletePlaylist = onDeletePlaylist,
        onRemoveFromPlaylist = onRemoveFromPlaylist,
        onDeleteTrack = onDeleteTrack,
        onImportArtistCover = onImportArtistCover,
        onCreateArtist = onCreateArtist,
        onEditArtist = onEditArtist,
        onDeleteArtist = onDeleteArtist,
        onSetArtistTracks = onSetArtistTracks,
        onSetTrackArtist = onSetTrackArtist,
        onCreateArtistAndAssignTrack = onCreateArtistAndAssignTrack,
        onResetTrackArtist = onResetTrackArtist,
        originalArtistForTrack = originalArtistForTrack,
        loadArtwork = loadArtwork
    )
}

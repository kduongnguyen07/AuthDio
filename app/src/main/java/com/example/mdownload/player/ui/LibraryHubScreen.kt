package com.example.mdownload.player.ui

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.TrendingUp
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Clear
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.NewReleases
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAdd
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueuePlayNext
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryQueryHelper
import com.example.mdownload.core.LibrarySearchScope
import com.example.mdownload.core.LibrarySortField
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.SortDirection
import com.example.mdownload.core.TextUtils
import com.example.mdownload.player.library.AlbumGroup
import com.example.mdownload.player.library.ArtistCatalogState
import com.example.mdownload.player.library.ArtistGroup
import com.example.mdownload.player.library.ArtistLibraryHelper
import com.example.mdownload.player.library.BuiltInSmartPlaylists
import com.example.mdownload.player.library.FolderGroup
import com.example.mdownload.player.library.LibraryGroupingHelper
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistEvalContext
import com.example.mdownload.player.library.SmartPlaylistEvaluator
import com.example.mdownload.player.library.UserPlaylist
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.HiFiArtworkTile
import com.example.mdownload.ui.components.HiFiEmptyState
import com.example.mdownload.ui.components.HiFiGlassChip
import com.example.mdownload.ui.components.HiFiGlassPanel
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.components.HiFiGlowProgressBar
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.components.HiFiTopChrome
import com.example.mdownload.ui.components.HiFiTrackOverflowButton
import com.example.mdownload.ui.components.HiFiTrackRow
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import java.util.Locale

enum class LibrarySectionTab(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
    PLAYLISTS("Playlists"),
    FOLDERS("Folders")
}

private sealed interface LibraryDetailTarget {
    data class Album(val albumIdKey: String) : LibraryDetailTarget
    data class Artist(val artistIdKey: String) : LibraryDetailTarget
    data class Folder(val folderPath: String) : LibraryDetailTarget
    data class UserPlaylistDetail(val playlistId: String) : LibraryDetailTarget
    data class SmartPlaylistDetail(val smartPlaylistId: String) : LibraryDetailTarget
}

/**
 * Local music library redesigned directly around the Dribbble reference's right screen:
 * - Atmospheric obsidian-and-orchid top chrome with subtle pill handle
 * - Sleek pill section tabs (Songs, Albums, Artists, Playlists, Folders)
 * - Borderless, cardless track rows with 52dp rounded-square artwork, crisp titles,
 *   muted artists, tabular durations, and fine hairline dividers
 */
@Composable
fun LibraryHubScreen(
    tracks: List<LibraryTrack>,
    playlists: List<UserPlaylist>,
    builtInSmartPlaylists: List<SmartPlaylistDefinition> = BuiltInSmartPlaylists.all(),
    customSmartPlaylists: List<SmartPlaylistDefinition> = emptyList(),
    evaluatedSmartPlaylists: Map<String, List<LibraryTrack>> = emptyMap(),
    smartPlaylistEvalContext: SmartPlaylistEvalContext = SmartPlaylistEvalContext(),
    artistCatalog: ArtistCatalogState = ArtistCatalogState(),
    playbackState: PlaybackUiState,
    storageLabel: String,
    scanAllDeviceMusic: Boolean,
    hasAudioPermission: Boolean,
    isSyncing: Boolean,
    canChangeFolder: Boolean,
    bottomContentPadding: Dp = HiFiSpacing.dockWithMiniPlayerClearance,
    onRequestAudioPermission: () -> Unit,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPlayTrackInQueue: (track: LibraryTrack, queue: List<LibraryTrack>) -> Unit,
    onPlayQueue: (queue: List<LibraryTrack>, shuffle: Boolean) -> Unit,
    onPlayNext: (LibraryTrack) -> Unit,
    onAddToQueue: (LibraryTrack) -> Unit,
    onAddTrackToPlaylist: (LibraryTrack) -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (playlistId: String, newName: String) -> Unit,
    onDeletePlaylist: (playlistId: String) -> Unit,
    onRemoveFromPlaylist: (playlistId: String, trackUri: String) -> Unit,
    onSaveCustomSmartPlaylist: (SmartPlaylistDefinition) -> Unit = {},
    onRenameCustomSmartPlaylist: (String, String) -> Unit = { _, _ -> },
    onDeleteCustomSmartPlaylist: (String) -> Unit = {},
    onOpenAudioInspector: (LibraryTrack) -> Unit = {},
    onEditTrackMetadata: (LibraryTrack) -> Unit = {},
    onBatchEditTracks: (List<LibraryTrack>) -> Unit = {},
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
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    val context = LocalContext.current
    var activeSection by rememberSaveable { mutableStateOf(LibrarySectionTab.SONGS) }
    var detailTarget by remember { mutableStateOf<LibraryDetailTarget?>(null) }

    var search by rememberSaveable { mutableStateOf("") }
    var artistSearch by rememberSaveable { mutableStateOf("") }
    var searchScope by rememberSaveable { mutableStateOf(LibrarySearchScope.ALL) }
    var sortField by rememberSaveable { mutableStateOf(LibrarySortField.DATE_ADDED) }
    var sortDirection by rememberSaveable { mutableStateOf(SortDirection.DESC) }
    var showSearchBar by rememberSaveable { mutableStateOf(false) }

    var trackToDelete by remember { mutableStateOf<LibraryTrack?>(null) }
    var showCreatePlaylistDialog by remember { mutableStateOf(false) }
    var playlistToRename by remember { mutableStateOf<UserPlaylist?>(null) }
    var playlistToDelete by remember { mutableStateOf<UserPlaylist?>(null) }

    var showSmartPlaylistEditor by remember { mutableStateOf(false) }
    var smartPlaylistToEdit by remember { mutableStateOf<SmartPlaylistDefinition?>(null) }
    var smartPlaylistToRename by remember { mutableStateOf<SmartPlaylistDefinition?>(null) }
    var smartPlaylistToDelete by remember { mutableStateOf<SmartPlaylistDefinition?>(null) }

    var showCreateArtistDialog by remember { mutableStateOf(false) }
    var artistToEdit by remember { mutableStateOf<ArtistGroup?>(null) }
    var artistToDelete by remember { mutableStateOf<ArtistGroup?>(null) }
    var artistToManageSongs by remember { mutableStateOf<ArtistGroup?>(null) }
    var trackToChangeArtist by remember { mutableStateOf<LibraryTrack?>(null) }

    val allSmartPlaylists = remember(builtInSmartPlaylists, customSmartPlaylists) {
        builtInSmartPlaylists + customSmartPlaylists
    }

    val albums = remember(tracks) { LibraryGroupingHelper.groupAlbums(tracks) }
    val artists = remember(tracks, artistCatalog.profiles) {
        LibraryGroupingHelper.groupArtists(tracks, artistCatalog.profiles)
    }
    val folders = remember(tracks) { LibraryGroupingHelper.groupFolders(tracks) }
    val tracksByUri = remember(tracks) { tracks.associateBy { it.uri } }

    val shareTrack: (LibraryTrack) -> Unit = { track ->
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = track.mimeType.ifBlank { "audio/*" }
            putExtra(Intent.EXTRA_STREAM, Uri.parse(track.uri))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { context.startActivity(Intent.createChooser(shareIntent, track.title)) }
    }

    if (detailTarget != null) {
        BackHandler { detailTarget = null }
    }

    val activeDetail = detailTarget
    if (activeDetail != null) {
        when (activeDetail) {
            is LibraryDetailTarget.Album -> {
                val album = albums.find { it.id == activeDetail.albumIdKey }
                if (album == null) {
                    LaunchedEffect(Unit) { detailTarget = null }
                } else {
                    TrackCollectionDetailScreen(
                        title = album.title,
                        subtitle = "${album.artist} · ${album.tracks.size} track(s) · ${TextUtils.formatDurationMs(album.totalDurationMs)}",
                        coverUri = album.coverTrackUri,
                        coverAlbumId = album.albumId,
                        tracks = album.tracks,
                        currentTrackUri = playbackState.currentTrack?.uri,
                        isPlaying = playbackState.isPlaying,
                        bottomContentPadding = bottomContentPadding,
                        onBack = { detailTarget = null },
                        onPlayAll = { onPlayQueue(album.tracks, false) },
                        onShuffle = { onPlayQueue(album.tracks, true) },
                        onPlayTrack = { track -> onPlayTrackInQueue(track, album.tracks) },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onChangeArtist = { trackToChangeArtist = it },
                        onOpenAudioInspector = onOpenAudioInspector,
                        onEditTrackMetadata = onEditTrackMetadata,
                        onBatchEdit = if (album.tracks.size > 1) ({ onBatchEditTracks(album.tracks) }) else null,
                        onShare = shareTrack,
                        onDelete = { trackToDelete = it },
                        loadArtwork = loadArtwork
                    )
                }
            }

            is LibraryDetailTarget.Artist -> {
                val artist = artists.find { it.id == activeDetail.artistIdKey }
                if (artist == null) {
                    LaunchedEffect(Unit) { detailTarget = null }
                } else {
                    ArtistDetailScreen(
                        artist = artist,
                        currentTrackUri = playbackState.currentTrack?.uri,
                        isPlaying = playbackState.isPlaying,
                        bottomContentPadding = bottomContentPadding,
                        onBack = { detailTarget = null },
                        onEditArtist = { artistToEdit = artist },
                        onManageSongs = { artistToManageSongs = artist },
                        onOpenAlbum = { album -> detailTarget = LibraryDetailTarget.Album(album.id) },
                        onPlayAll = { onPlayQueue(artist.tracks, false) },
                        onShuffle = { onPlayQueue(artist.tracks, true) },
                        onPlayTrack = { track -> onPlayTrackInQueue(track, artist.tracks) },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onChangeArtist = { trackToChangeArtist = it },
                        onOpenAudioInspector = onOpenAudioInspector,
                        onEditTrackMetadata = onEditTrackMetadata,
                        onBatchEdit = if (artist.tracks.size > 1) ({ onBatchEditTracks(artist.tracks) }) else null,
                        onShare = shareTrack,
                        onDelete = { trackToDelete = it },
                        loadArtwork = loadArtwork
                    )
                }
            }

            is LibraryDetailTarget.Folder -> {
                val folder = folders.find { it.path == activeDetail.folderPath }
                if (folder == null) {
                    LaunchedEffect(Unit) { detailTarget = null }
                } else {
                    TrackCollectionDetailScreen(
                        title = folder.displayName,
                        subtitle = "${folder.path} · ${folder.tracks.size} track(s)",
                        coverUri = folder.coverTrackUri,
                        coverAlbumId = folder.coverAlbumId,
                        tracks = folder.tracks,
                        currentTrackUri = playbackState.currentTrack?.uri,
                        isPlaying = playbackState.isPlaying,
                        bottomContentPadding = bottomContentPadding,
                        onBack = { detailTarget = null },
                        onPlayAll = { onPlayQueue(folder.tracks, false) },
                        onShuffle = { onPlayQueue(folder.tracks, true) },
                        onPlayTrack = { track -> onPlayTrackInQueue(track, folder.tracks) },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onChangeArtist = { trackToChangeArtist = it },
                        onOpenAudioInspector = onOpenAudioInspector,
                        onEditTrackMetadata = onEditTrackMetadata,
                        onBatchEdit = if (folder.tracks.size > 1) ({ onBatchEditTracks(folder.tracks) }) else null,
                        onShare = shareTrack,
                        onDelete = { trackToDelete = it },
                        loadArtwork = loadArtwork
                    )
                }
            }

            is LibraryDetailTarget.UserPlaylistDetail -> {
                val playlist = playlists.find { it.id == activeDetail.playlistId }
                if (playlist == null) {
                    LaunchedEffect(Unit) { detailTarget = null }
                } else {
                    val plTracks = remember(playlist, tracksByUri) {
                        playlist.trackUris.mapNotNull { tracksByUri[it] }
                    }
                    TrackCollectionDetailScreen(
                        title = playlist.name,
                        subtitle = "${plTracks.size} track(s) · ${TextUtils.formatDurationMs(plTracks.sumOf { it.durationMs.coerceAtLeast(0L) })}",
                        coverUri = plTracks.firstOrNull()?.uri.orEmpty(),
                        coverAlbumId = plTracks.firstOrNull()?.albumId ?: 0L,
                        tracks = plTracks,
                        currentTrackUri = playbackState.currentTrack?.uri,
                        isPlaying = playbackState.isPlaying,
                        bottomContentPadding = bottomContentPadding,
                        onBack = { detailTarget = null },
                        onPlayAll = { onPlayQueue(plTracks, false) },
                        onShuffle = { onPlayQueue(plTracks, true) },
                        onPlayTrack = { track -> onPlayTrackInQueue(track, plTracks) },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onChangeArtist = { trackToChangeArtist = it },
                        onOpenAudioInspector = onOpenAudioInspector,
                        onEditTrackMetadata = onEditTrackMetadata,
                        onBatchEdit = if (plTracks.size > 1) ({ onBatchEditTracks(plTracks) }) else null,
                        onRemoveFromPlaylist = { track -> onRemoveFromPlaylist(playlist.id, track.uri) },
                        onShare = shareTrack,
                        onDelete = { trackToDelete = it },
                        loadArtwork = loadArtwork
                    )
                }
            }

            is LibraryDetailTarget.SmartPlaylistDetail -> {
                val def = allSmartPlaylists.find { it.id == activeDetail.smartPlaylistId }
                if (def == null) {
                    LaunchedEffect(Unit) { detailTarget = null }
                } else {
                    val smartTracks = evaluatedSmartPlaylists[def.id]
                        ?: remember(def, tracks, smartPlaylistEvalContext) {
                            SmartPlaylistEvaluator.evaluate(def, tracks, smartPlaylistEvalContext)
                        }
                    TrackCollectionDetailScreen(
                        title = def.name,
                        subtitle = "${smartTracks.size} track(s) · ${TextUtils.formatDurationMs(smartTracks.sumOf { it.durationMs.coerceAtLeast(0L) })}",
                        coverUri = smartTracks.firstOrNull()?.uri.orEmpty(),
                        coverAlbumId = smartTracks.firstOrNull()?.albumId ?: 0L,
                        tracks = smartTracks,
                        currentTrackUri = playbackState.currentTrack?.uri,
                        isPlaying = playbackState.isPlaying,
                        bottomContentPadding = bottomContentPadding,
                        onBack = { detailTarget = null },
                        onPlayAll = { onPlayQueue(smartTracks, false) },
                        onShuffle = { onPlayQueue(smartTracks, true) },
                        onPlayTrack = { track -> onPlayTrackInQueue(track, smartTracks) },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onChangeArtist = { trackToChangeArtist = it },
                        onOpenAudioInspector = onOpenAudioInspector,
                        onEditTrackMetadata = onEditTrackMetadata,
                        onBatchEdit = if (smartTracks.size > 1) ({ onBatchEditTracks(smartTracks) }) else null,
                        onEditSmartRules = if (!def.isBuiltIn) {
                            {
                                smartPlaylistToEdit = def
                                showSmartPlaylistEditor = true
                            }
                        } else null,
                        onShare = shareTrack,
                        onDelete = { trackToDelete = it },
                        loadArtwork = loadArtwork
                    )
                }
            }
        }
    } else {
        val totalSizeBytes = remember(tracks) { tracks.sumOf { it.sizeBytes } }
        val scopeLabel = if (scanAllDeviceMusic) "Device + $storageLabel" else storageLabel

        Column(modifier = Modifier.fillMaxSize()) {
            HiFiTopChrome(
                title = "Library",
                subtitle = "$scopeLabel · ${tracks.size} track(s) · ${TextUtils.formatFileSize(totalSizeBytes)}",
                trailing = {
                    if (activeSection == LibrarySectionTab.SONGS || activeSection == LibrarySectionTab.ARTISTS) {
                        val hasQuery = if (activeSection == LibrarySectionTab.ARTISTS) {
                            artistSearch.isNotBlank()
                        } else {
                            search.isNotBlank()
                        }
                        IconButton(onClick = { showSearchBar = !showSearchBar }) {
                            Icon(
                                imageVector = Icons.Rounded.Search,
                                contentDescription = localText("Search"),
                                tint = if (showSearchBar || hasQuery) tokens.accent else tokens.inkPrimary
                            )
                        }
                    }
                    IconButton(onClick = onPickFolder, enabled = canChangeFolder) {
                        Icon(
                            imageVector = Icons.Rounded.FolderOpen,
                            contentDescription = localText("Choose folder"),
                            tint = tokens.inkSecondary
                        )
                    }
                    IconButton(onClick = onRefresh, enabled = !isSyncing) {
                        Icon(
                            imageVector = Icons.Rounded.Sync,
                            contentDescription = localText("Sync folder"),
                            tint = tokens.inkSecondary
                        )
                    }
                }
            )

            // Section pills: Songs / Albums / Artists / Playlists / Folders
            LazyRow(
                contentPadding = PaddingValues(horizontal = HiFiSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(LibrarySectionTab.entries) { tab ->
                    HiFiGlassChip(
                        label = tab.label,
                        selected = activeSection == tab,
                        onClick = { activeSection = tab }
                    )
                }
            }

            if (isSyncing) {
                Spacer(Modifier.height(10.dp))
                HiFiGlowProgressBar(
                    progress = null,
                    modifier = Modifier.padding(horizontal = HiFiSpacing.lg),
                    height = 2.5.dp
                )
            }

            if (!hasAudioPermission) {
                Spacer(Modifier.height(10.dp))
                HiFiGlassPanel(
                    modifier = Modifier.padding(horizontal = HiFiSpacing.lg),
                    containerColor = tokens.warningContainer,
                    contentPadding = HiFiSpacing.sm
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Audio access needed",
                                style = MaterialTheme.typography.titleSmall,
                                color = tokens.warning
                            )
                            Text(
                                text = "Grant audio permission to browse all local music on your device.",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.warning.copy(alpha = 0.85f)
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        HiFiPillActionButton(
                            label = "Grant",
                            onClick = onRequestAudioPermission,
                            primary = false
                        )
                    }
                }
            }

            Spacer(Modifier.height(HiFiSpacing.sm))

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeSection) {
                    LibrarySectionTab.SONGS -> SongsTabContent(
                        tracks = tracks,
                        search = search,
                        onSearchChange = { search = it },
                        showSearchBar = showSearchBar || search.isNotBlank(),
                        searchScope = searchScope,
                        onSearchScopeChange = { searchScope = it },
                        sortField = sortField,
                        onSortFieldChange = { sortField = it },
                        sortDirection = sortDirection,
                        onToggleSortDirection = {
                            sortDirection = if (sortDirection == SortDirection.DESC) SortDirection.ASC else SortDirection.DESC
                        },
                        currentTrackUri = playbackState.currentTrack?.uri,
                        isPlaying = playbackState.isPlaying,
                        bottomContentPadding = bottomContentPadding,
                        onPlayAll = { list -> onPlayQueue(list, false) },
                        onShuffleAll = { list -> onPlayQueue(list, true) },
                        onPlayTrack = { track, filteredList -> onPlayTrackInQueue(track, filteredList) },
                        onPlayNext = onPlayNext,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = onAddTrackToPlaylist,
                        onChangeArtist = { trackToChangeArtist = it },
                        onOpenAudioInspector = onOpenAudioInspector,
                        onEditTrackMetadata = onEditTrackMetadata,
                        onBatchEditTracks = onBatchEditTracks,
                        onShare = shareTrack,
                        onDelete = { trackToDelete = it },
                        loadArtwork = loadArtwork
                    )

                    LibrarySectionTab.ALBUMS -> AlbumsTabContent(
                        albums = albums,
                        bottomContentPadding = bottomContentPadding,
                        onOpenAlbum = { album -> detailTarget = LibraryDetailTarget.Album(album.id) },
                        loadArtwork = loadArtwork
                    )

                    LibrarySectionTab.ARTISTS -> ArtistsTabContent(
                        artists = artists,
                        search = artistSearch,
                        onSearchChange = { artistSearch = it },
                        showSearchBar = showSearchBar || artistSearch.isNotBlank(),
                        bottomContentPadding = bottomContentPadding,
                        onCreateArtist = { showCreateArtistDialog = true },
                        onOpenArtist = { artist -> detailTarget = LibraryDetailTarget.Artist(artist.id) },
                        onEditArtist = { artistToEdit = it },
                        onManageSongs = { artistToManageSongs = it },
                        onDeleteArtist = { artistToDelete = it },
                        loadArtwork = loadArtwork
                    )

                    LibrarySectionTab.PLAYLISTS -> PlaylistsTabContent(
                        playlists = playlists,
                        builtInSmartPlaylists = builtInSmartPlaylists,
                        customSmartPlaylists = customSmartPlaylists,
                        evaluatedSmartPlaylists = evaluatedSmartPlaylists,
                        evalContext = smartPlaylistEvalContext,
                        allTracks = tracks,
                        tracksByUri = tracksByUri,
                        bottomContentPadding = bottomContentPadding,
                        onCreatePlaylist = { showCreatePlaylistDialog = true },
                        onCreateSmartPlaylist = {
                            smartPlaylistToEdit = null
                            showSmartPlaylistEditor = true
                        },
                        onOpenSmartPlaylist = { def -> detailTarget = LibraryDetailTarget.SmartPlaylistDetail(def.id) },
                        onPlaySmartPlaylist = { def ->
                            val resolved = evaluatedSmartPlaylists[def.id]
                                ?: SmartPlaylistEvaluator.evaluate(def, tracks, smartPlaylistEvalContext)
                            if (resolved.isNotEmpty()) onPlayQueue(resolved, false)
                        },
                        onEditSmartPlaylist = { def ->
                            smartPlaylistToEdit = def
                            showSmartPlaylistEditor = true
                        },
                        onRenameSmartPlaylist = { smartPlaylistToRename = it },
                        onDeleteSmartPlaylist = { smartPlaylistToDelete = it },
                        onOpenPlaylist = { pl -> detailTarget = LibraryDetailTarget.UserPlaylistDetail(pl.id) },
                        onPlayPlaylist = { pl ->
                            val resolved = pl.trackUris.mapNotNull { tracksByUri[it] }
                            if (resolved.isNotEmpty()) onPlayQueue(resolved, false)
                        },
                        onRenamePlaylist = { playlistToRename = it },
                        onDeletePlaylist = { playlistToDelete = it },
                        loadArtwork = loadArtwork
                    )

                    LibrarySectionTab.FOLDERS -> FoldersTabContent(
                        folders = folders,
                        bottomContentPadding = bottomContentPadding,
                        onOpenFolder = { folder -> detailTarget = LibraryDetailTarget.Folder(folder.path) },
                        loadArtwork = loadArtwork
                    )
                }
            }
        }
    }

    trackToDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { trackToDelete = null },
            shape = HiFiShapes.card,
            containerColor = tokens.canvasDeep,
            title = { Text("Delete track?", color = tokens.inkPrimary) },
            text = { Text("Remove \"${target.title}\" from storage? This cannot be undone.", color = tokens.inkSecondary) },
            dismissButton = {
                TextButton(onClick = { trackToDelete = null }) { Text("Cancel", color = tokens.inkSecondary) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val toRemove = target
                    trackToDelete = null
                    onDeleteTrack(toRemove)
                }) {
                    Text("Delete", color = tokens.error)
                }
            }
        )
    }

    if (showCreatePlaylistDialog) {
        PlaylistNameDialog(
            title = "New playlist",
            initialName = "",
            confirmLabel = "Create",
            onDismiss = { showCreatePlaylistDialog = false },
            onConfirm = { name ->
                showCreatePlaylistDialog = false
                onCreatePlaylist(name)
            }
        )
    }

    playlistToRename?.let { pl ->
        PlaylistNameDialog(
            title = "Rename playlist",
            initialName = pl.name,
            confirmLabel = "Save",
            onDismiss = { playlistToRename = null },
            onConfirm = { name ->
                playlistToRename = null
                onRenamePlaylist(pl.id, name)
            }
        )
    }

    playlistToDelete?.let { pl ->
        AlertDialog(
            onDismissRequest = { playlistToDelete = null },
            shape = HiFiShapes.card,
            containerColor = tokens.canvasDeep,
            title = { Text("Delete playlist?", color = tokens.inkPrimary) },
            text = { Text("Delete \"${pl.name}\"? Local audio files will not be deleted.", color = tokens.inkSecondary) },
            dismissButton = {
                TextButton(onClick = { playlistToDelete = null }) { Text("Cancel", color = tokens.inkSecondary) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = pl.id
                    playlistToDelete = null
                    onDeletePlaylist(id)
                }) {
                    Text("Delete", color = tokens.error)
                }
            }
        )
    }

    if (showSmartPlaylistEditor) {
        SmartPlaylistEditorDialog(
            initial = smartPlaylistToEdit,
            allTracks = tracks,
            evalContext = smartPlaylistEvalContext,
            onDismiss = {
                showSmartPlaylistEditor = false
                smartPlaylistToEdit = null
            },
            onSave = { def ->
                showSmartPlaylistEditor = false
                smartPlaylistToEdit = null
                onSaveCustomSmartPlaylist(def)
            }
        )
    }

    smartPlaylistToRename?.let { sp ->
        PlaylistNameDialog(
            title = "Rename smart playlist",
            initialName = sp.name,
            confirmLabel = "Save",
            onDismiss = { smartPlaylistToRename = null },
            onConfirm = { name ->
                val id = sp.id
                smartPlaylistToRename = null
                onRenameCustomSmartPlaylist(id, name)
            }
        )
    }

    smartPlaylistToDelete?.let { sp ->
        AlertDialog(
            onDismissRequest = { smartPlaylistToDelete = null },
            shape = HiFiShapes.card,
            containerColor = tokens.canvasDeep,
            title = { Text("Delete smart playlist?", color = tokens.inkPrimary) },
            text = { Text("Delete \"${sp.name}\"? Local audio files will not be deleted.", color = tokens.inkSecondary) },
            dismissButton = {
                TextButton(onClick = { smartPlaylistToDelete = null }) { Text("Cancel", color = tokens.inkSecondary) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val id = sp.id
                    smartPlaylistToDelete = null
                    onDeleteCustomSmartPlaylist(id)
                    val cur = detailTarget
                    if (cur is LibraryDetailTarget.SmartPlaylistDetail && cur.smartPlaylistId == id) {
                        detailTarget = null
                    }
                }) {
                    Text("Delete", color = tokens.error)
                }
            }
        )
    }

    if (showCreateArtistDialog) {
        ArtistEditorDialog(
            title = "New artist",
            initialName = "",
            initialCoverPath = "",
            existingArtistNames = artists.map { it.name },
            confirmLabel = "Create",
            onImportCover = onImportArtistCover,
            loadArtwork = loadArtwork,
            onDismiss = { showCreateArtistDialog = false },
            onConfirm = { name, coverPath, _ ->
                showCreateArtistDialog = false
                onCreateArtist(name, coverPath)
            }
        )
    }

    artistToEdit?.let { targetArtist ->
        ArtistEditorDialog(
            title = "Edit artist",
            initialName = targetArtist.name,
            initialCoverPath = targetArtist.customCoverPath,
            fallbackTrackUri = targetArtist.coverTrackUri,
            fallbackAlbumId = targetArtist.coverAlbumId,
            existingArtistNames = artists.map { it.name },
            confirmLabel = "Save",
            onImportCover = onImportArtistCover,
            loadArtwork = loadArtwork,
            onDismiss = { artistToEdit = null },
            onConfirm = { newName, newCoverPath, clearCover ->
                val oldName = targetArtist.name
                val oldId = targetArtist.id
                artistToEdit = null
                onEditArtist(oldName, newName, newCoverPath.takeIf { it.isNotBlank() }, clearCover)
                val currentDetail = detailTarget
                if (currentDetail is LibraryDetailTarget.Artist && currentDetail.artistIdKey == oldId) {
                    detailTarget = LibraryDetailTarget.Artist(ArtistLibraryHelper.normalizeKey(newName))
                }
            }
        )
    }

    artistToDelete?.let { targetArtist ->
        AlertDialog(
            onDismissRequest = { artistToDelete = null },
            shape = HiFiShapes.card,
            containerColor = tokens.canvasDeep,
            title = { Text("Delete artist?", color = tokens.inkPrimary) },
            text = {
                Text(
                    "Remove custom artist \"${targetArtist.name}\"? Assigned songs will return to their original tags and audio files will not be deleted.",
                    color = tokens.inkSecondary
                )
            },
            dismissButton = {
                TextButton(onClick = { artistToDelete = null }) { Text("Cancel", color = tokens.inkSecondary) }
            },
            confirmButton = {
                TextButton(onClick = {
                    val targetName = targetArtist.name
                    val targetId = targetArtist.id
                    artistToDelete = null
                    onDeleteArtist(targetName)
                    val currentDetail = detailTarget
                    if (currentDetail is LibraryDetailTarget.Artist && currentDetail.artistIdKey == targetId) {
                        detailTarget = null
                    }
                }) {
                    Text("Delete", color = tokens.error)
                }
            }
        )
    }

    artistToManageSongs?.let { targetArtist ->
        val assignedUris = remember(targetArtist) { targetArtist.tracks.map { it.uri }.toSet() }
        AssignSongsToArtistDialog(
            artistName = targetArtist.name,
            allTracks = tracks,
            initialAssignedUris = assignedUris,
            loadArtwork = loadArtwork,
            onDismiss = { artistToManageSongs = null },
            onConfirm = { selectedUris ->
                val targetName = targetArtist.name
                artistToManageSongs = null
                onSetArtistTracks(targetName, selectedUris)
            }
        )
    }

    trackToChangeArtist?.let { targetTrack ->
        val isOverridden = targetTrack.uri in artistCatalog.trackArtistOverrides
        val origTag = remember(targetTrack.uri) { originalArtistForTrack(targetTrack.uri) }
        AssignTrackArtistDialog(
            track = targetTrack,
            artists = artists,
            isOverridden = isOverridden,
            originalTagArtist = origTag,
            loadArtwork = loadArtwork,
            onSelectArtist = { artistName ->
                val tr = targetTrack
                trackToChangeArtist = null
                onSetTrackArtist(tr, artistName)
            },
            onCreateAndAssign = { artistName ->
                val tr = targetTrack
                trackToChangeArtist = null
                onCreateArtistAndAssignTrack(tr, artistName)
            },
            onResetToOriginal = {
                val tr = targetTrack
                trackToChangeArtist = null
                onResetTrackArtist(tr)
            },
            onDismiss = { trackToChangeArtist = null }
        )
    }
}

@Composable
private fun SongsTabContent(
    tracks: List<LibraryTrack>,
    search: String,
    onSearchChange: (String) -> Unit,
    showSearchBar: Boolean,
    searchScope: LibrarySearchScope,
    onSearchScopeChange: (LibrarySearchScope) -> Unit,
    sortField: LibrarySortField,
    onSortFieldChange: (LibrarySortField) -> Unit,
    sortDirection: SortDirection,
    onToggleSortDirection: () -> Unit,
    currentTrackUri: String?,
    isPlaying: Boolean,
    bottomContentPadding: Dp,
    onPlayAll: (List<LibraryTrack>) -> Unit,
    onShuffleAll: (List<LibraryTrack>) -> Unit,
    onPlayTrack: (LibraryTrack, List<LibraryTrack>) -> Unit,
    onPlayNext: (LibraryTrack) -> Unit,
    onAddToQueue: (LibraryTrack) -> Unit,
    onAddToPlaylist: (LibraryTrack) -> Unit,
    onChangeArtist: (LibraryTrack) -> Unit,
    onOpenAudioInspector: (LibraryTrack) -> Unit,
    onEditTrackMetadata: (LibraryTrack) -> Unit,
    onBatchEditTracks: (List<LibraryTrack>) -> Unit,
    onShare: (LibraryTrack) -> Unit,
    onDelete: (LibraryTrack) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    val listState = rememberLazyListState()
    var selectionMode by remember { mutableStateOf(false) }
    var selectedUris by remember { mutableStateOf(emptySet<String>()) }
    val filtered = remember(tracks, search, searchScope, sortField, sortDirection) {
        LibraryQueryHelper.filterAndSort(
            tracks = tracks,
            query = search,
            scope = searchScope,
            sortField = sortField,
            sortDirection = sortDirection
        )
    }
    val selectedTracks = remember(filtered, selectedUris) {
        filtered.filter { it.uri in selectedUris }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showSearchBar) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HiFiGlassTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    placeholder = when (searchScope) {
                        LibrarySearchScope.ALL -> "Search song or artist…"
                        LibrarySearchScope.TITLE -> "Search by song name…"
                        LibrarySearchScope.ARTIST -> "Search by artist…"
                    },
                    onClear = { onSearchChange("") }
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LibrarySearchScope.entries.forEach { scope ->
                        HiFiGlassChip(
                            label = scope.label,
                            selected = searchScope == scope,
                            onClick = { onSearchScopeChange(scope) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(HiFiSpacing.xs))
        }

        // Compact sort & quick play/shuffle/batch-select bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiFiSpacing.lg),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LazyRow(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                item {
                    HiFiGlassChip(
                        label = if (sortDirection == SortDirection.ASC) "Asc" else "Desc",
                        selected = false,
                        leadingIcon = if (sortDirection == SortDirection.ASC) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
                        onClick = onToggleSortDirection
                    )
                }
                items(LibrarySortField.entries) { field ->
                    HiFiGlassChip(
                        label = field.label,
                        selected = sortField == field,
                        onClick = { onSortFieldChange(field) }
                    )
                }
            }
            if (filtered.isNotEmpty()) {
                IconButton(
                    onClick = {
                        selectionMode = !selectionMode
                        if (!selectionMode) selectedUris = emptySet()
                    },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Rounded.Checklist,
                        contentDescription = localText("Batch edit"),
                        tint = if (selectionMode) tokens.accentBright else tokens.inkSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
                IconButton(
                    onClick = { onPlayAll(filtered) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = localText("Play all"),
                        tint = tokens.accent
                    )
                }
                IconButton(
                    onClick = { onShuffleAll(filtered) },
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        Icons.Rounded.Shuffle,
                        contentDescription = localText("Shuffle"),
                        tint = tokens.inkSecondary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        if (selectionMode && filtered.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            HiFiGlassPanel(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg),
                contentPadding = 10.dp
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "${selectedTracks.size} selected",
                        style = MaterialTheme.typography.labelLarge,
                        color = tokens.inkPrimary
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        TextButton(onClick = {
                            selectedUris = if (selectedTracks.size == filtered.size) emptySet()
                            else filtered.map { it.uri }.toSet()
                        }) {
                            Text(if (selectedTracks.size == filtered.size) "Clear" else "All", color = tokens.accentBright)
                        }
                        HiFiPillActionButton(
                            label = "Batch edit",
                            icon = Icons.Rounded.Edit,
                            enabled = selectedTracks.isNotEmpty(),
                            primary = true,
                            onClick = {
                                val targets = selectedTracks
                                selectionMode = false
                                selectedUris = emptySet()
                                onBatchEditTracks(targets)
                            }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(6.dp))

        if (filtered.isEmpty()) {
            HiFiEmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = if (tracks.isEmpty()) "No local audio files yet" else "No matching tracks",
                subtitle = if (tracks.isEmpty()) {
                    "Download music from Home or grant audio permission to browse your device."
                } else {
                    "Try a different song name or artist filter."
                }
            )
        } else {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(bottom = bottomContentPadding)
            ) {
                items(
                    items = filtered,
                    key = { it.uri },
                    contentType = { "library_track_row" }
                ) { track ->
                    val isCurrent = currentTrackUri == track.uri
                    val isSelected = track.uri in selectedUris
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (selectionMode) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selectedUris = if (checked) selectedUris + track.uri else selectedUris - track.uri
                                },
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                        Box(modifier = Modifier.weight(1f)) {
                            LibraryTrackItemRow(
                                track = track,
                                isCurrent = isCurrent,
                                isPlaying = isCurrent && isPlaying,
                                onPlay = {
                                    if (selectionMode) {
                                        selectedUris = if (isSelected) selectedUris - track.uri else selectedUris + track.uri
                                    } else {
                                        onPlayTrack(track, filtered)
                                    }
                                },
                                onPlayNext = { onPlayNext(track) },
                                onAddToQueue = { onAddToQueue(track) },
                                onAddToPlaylist = { onAddToPlaylist(track) },
                                onChangeArtist = { onChangeArtist(track) },
                                onOpenAudioInspector = { onOpenAudioInspector(track) },
                                onEditMetadata = { onEditTrackMetadata(track) },
                                onShare = { onShare(track) },
                                onDelete = { onDelete(track) },
                                loadArtwork = loadArtwork
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AlbumsTabContent(
    albums: List<AlbumGroup>,
    bottomContentPadding: Dp,
    onOpenAlbum: (AlbumGroup) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    if (albums.isEmpty()) {
        HiFiEmptyState(
            icon = Icons.Rounded.Album,
            title = "No albums found",
            subtitle = "Downloaded and local tracks grouped by album will appear here."
        )
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 152.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(
            start = HiFiSpacing.lg,
            end = HiFiSpacing.lg,
            top = 4.dp,
            bottom = bottomContentPadding
        ),
        modifier = Modifier.fillMaxSize()
    ) {
        items(albums, key = { it.id }) { album ->
            HiFiArtworkTile(
                title = album.title,
                subtitle = "${album.artist} · ${album.tracks.size} track(s)",
                artworkUri = album.coverTrackUri,
                albumId = album.albumId,
                onClick = { onOpenAlbum(album) },
                loadArtwork = loadArtwork
            )
        }
    }
}

@Composable
private fun ArtistsTabContent(
    artists: List<ArtistGroup>,
    search: String,
    onSearchChange: (String) -> Unit,
    showSearchBar: Boolean,
    bottomContentPadding: Dp,
    onCreateArtist: () -> Unit,
    onOpenArtist: (ArtistGroup) -> Unit,
    onEditArtist: (ArtistGroup) -> Unit,
    onManageSongs: (ArtistGroup) -> Unit,
    onDeleteArtist: (ArtistGroup) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    val filteredArtists = remember(artists, search) {
        val query = TextUtils.asciiText(search).lowercase(Locale.ROOT).trim()
        if (query.isBlank()) artists
        else artists.filter {
            TextUtils.asciiText(it.name).lowercase(Locale.ROOT).contains(query) ||
                it.name.lowercase(Locale.ROOT).contains(search.trim().lowercase(Locale.ROOT))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (showSearchBar) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg)
            ) {
                HiFiGlassTextField(
                    value = search,
                    onValueChange = onSearchChange,
                    placeholder = "Search by artist…",
                    onClear = { onSearchChange("") }
                )
            }
            Spacer(Modifier.height(HiFiSpacing.xs))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiFiSpacing.lg, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${filteredArtists.size} artist(s)",
                style = MaterialTheme.typography.labelLarge,
                color = tokens.inkSecondary
            )
            HiFiPillActionButton(
                label = "New artist",
                icon = Icons.Rounded.PersonAdd,
                onClick = onCreateArtist,
                primary = false
            )
        }

        if (filteredArtists.isEmpty()) {
            HiFiEmptyState(
                icon = Icons.Rounded.Person,
                title = if (artists.isEmpty()) "No artists found" else "No matching artists",
                subtitle = if (artists.isEmpty()) {
                    "Create an artist or import local music to organize tracks by artist."
                } else {
                    "Try a different artist name or create a new artist."
                },
                actionLabel = "New artist",
                onAction = onCreateArtist
            )
            return
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            modifier = Modifier.fillMaxSize()
        ) {
            items(filteredArtists, key = { it.id }) { artist ->
                var menuOpen by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenArtist(artist) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = HiFiSpacing.lg, end = HiFiSpacing.xs, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtistAvatarBox(
                            customCoverPath = artist.customCoverPath,
                            fallbackTrackUri = artist.coverTrackUri,
                            fallbackAlbumId = artist.coverAlbumId,
                            highRes = false,
                            iconSize = 22.dp,
                            modifier = Modifier.size(52.dp),
                            loadArtwork = loadArtwork
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = artist.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${artist.tracks.size} track(s) · ${artist.albums.size} album(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                        }
                        Box {
                            HiFiTrackOverflowButton(onClick = { menuOpen = true })
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = tokens.glassElevated
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Edit artist") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onEditArtist(artist)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Manage songs") },
                                    leadingIcon = { Icon(Icons.Rounded.LibraryAdd, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onManageSongs(artist)
                                    }
                                )
                                if (artist.isCustomProfile) {
                                    DropdownMenuItem(
                                        text = { Text("Delete artist", color = tokens.error) },
                                        leadingIcon = {
                                            Icon(
                                                Icons.Rounded.Delete,
                                                contentDescription = null,
                                                tint = tokens.error
                                            )
                                        },
                                        onClick = {
                                            menuOpen = false
                                            onDeleteArtist(artist)
                                        }
                                    )
                                }
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = HiFiSpacing.lg + 66.dp, end = HiFiSpacing.lg),
                        thickness = 0.65.dp,
                        color = tokens.divider
                    )
                }
            }
        }
    }
}

@Composable
private fun PlaylistsTabContent(
    playlists: List<UserPlaylist>,
    builtInSmartPlaylists: List<SmartPlaylistDefinition>,
    customSmartPlaylists: List<SmartPlaylistDefinition>,
    evaluatedSmartPlaylists: Map<String, List<LibraryTrack>>,
    evalContext: SmartPlaylistEvalContext,
    allTracks: List<LibraryTrack>,
    tracksByUri: Map<String, LibraryTrack>,
    bottomContentPadding: Dp,
    onCreatePlaylist: () -> Unit,
    onCreateSmartPlaylist: () -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistDefinition) -> Unit,
    onPlaySmartPlaylist: (SmartPlaylistDefinition) -> Unit,
    onEditSmartPlaylist: (SmartPlaylistDefinition) -> Unit,
    onRenameSmartPlaylist: (SmartPlaylistDefinition) -> Unit,
    onDeleteSmartPlaylist: (SmartPlaylistDefinition) -> Unit,
    onOpenPlaylist: (UserPlaylist) -> Unit,
    onPlayPlaylist: (UserPlaylist) -> Unit,
    onRenamePlaylist: (UserPlaylist) -> Unit,
    onDeletePlaylist: (UserPlaylist) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    // Prefer off-Main-Thread evaluatedSmartPlaylists map from ViewModel; fall back only when empty in standalone tests/previews.
    val smartCounts = remember(builtInSmartPlaylists, customSmartPlaylists, evaluatedSmartPlaylists, allTracks, evalContext) {
        if (evaluatedSmartPlaylists.isNotEmpty()) {
            evaluatedSmartPlaylists.mapValues { it.value.size }
        } else {
            SmartPlaylistEvaluator.evaluateAll(builtInSmartPlaylists + customSmartPlaylists, allTracks, evalContext)
                .mapValues { it.value.size }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        item(key = "smart_playlists_header", contentType = "section_header") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Smart playlists",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.inkSecondary
                )
                HiFiPillActionButton(
                    label = "New smart playlist",
                    icon = Icons.Rounded.AutoAwesome,
                    onClick = onCreateSmartPlaylist,
                    primary = false
                )
            }
        }

        // 7 Built-in dynamic Smart Playlists
        items(
            items = builtInSmartPlaylists,
            key = { it.id },
            contentType = { "smart_playlist_row" }
        ) { def ->
            val count = smartCounts[def.id] ?: 0
            val icon = when (def.id) {
                BuiltInSmartPlaylists.ID_RECENTLY_ADDED -> Icons.Rounded.History
                BuiltInSmartPlaylists.ID_RECENTLY_PLAYED -> Icons.Rounded.PlayArrow
                BuiltInSmartPlaylists.ID_MOST_PLAYED -> Icons.AutoMirrored.Rounded.TrendingUp
                BuiltInSmartPlaylists.ID_NEVER_PLAYED -> Icons.Rounded.NewReleases
                BuiltInSmartPlaylists.ID_FAVORITES -> Icons.Rounded.Favorite
                BuiltInSmartPlaylists.ID_HIGH_QUALITY -> Icons.Rounded.HighQuality
                BuiltInSmartPlaylists.ID_RECENTLY_DOWNLOADED -> Icons.Rounded.DownloadDone
                else -> Icons.Rounded.AutoAwesome
            }
            SmartPlaylistRow(
                icon = icon,
                title = def.name,
                subtitle = "$count track(s)",
                showPlay = count > 0,
                onPlay = { onPlaySmartPlaylist(def) },
                onClick = { onOpenSmartPlaylist(def) }
            )
        }

        // User-created Custom Smart Playlists
        items(
            items = customSmartPlaylists,
            key = { it.id },
            contentType = { "custom_smart_playlist_row" }
        ) { def ->
            val count = smartCounts[def.id] ?: 0
            var menuOpen by remember { mutableStateOf(false) }
            SmartPlaylistRow(
                icon = Icons.Rounded.AutoAwesome,
                title = def.name,
                subtitle = "$count track(s) · ${def.rules.size} rule(s)",
                showPlay = count > 0,
                onPlay = { onPlaySmartPlaylist(def) },
                onClick = { onOpenSmartPlaylist(def) },
                trailingMenu = {
                    Box {
                        HiFiTrackOverflowButton(onClick = { menuOpen = true })
                        DropdownMenu(
                            expanded = menuOpen,
                            onDismissRequest = { menuOpen = false },
                            containerColor = tokens.glassElevated
                        ) {
                            DropdownMenuItem(
                                text = { Text("Edit rules") },
                                leadingIcon = { Icon(Icons.Rounded.Tune, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onEditSmartPlaylist(def)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename smart playlist") },
                                leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                onClick = {
                                    menuOpen = false
                                    onRenameSmartPlaylist(def)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Delete smart playlist", color = tokens.error) },
                                leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = tokens.error) },
                                onClick = {
                                    menuOpen = false
                                    onDeleteSmartPlaylist(def)
                                }
                            )
                        }
                    }
                }
            )
        }

        item {
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Your playlists",
                    style = MaterialTheme.typography.labelLarge,
                    color = tokens.inkSecondary
                )
                HiFiPillActionButton(
                    label = "New playlist",
                    icon = Icons.Rounded.Add,
                    onClick = onCreatePlaylist,
                    primary = false
                )
            }
        }

        if (playlists.isEmpty()) {
            item {
                HiFiEmptyState(
                    icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                    title = "No custom playlists yet",
                    subtitle = "Create a playlist to organize your favorite offline tracks.",
                    actionLabel = "New playlist",
                    onAction = onCreatePlaylist
                )
            }
        } else {
            items(playlists, key = { it.id }) { pl ->
                val resolved = remember(pl, tracksByUri) { pl.trackUris.mapNotNull { tracksByUri[it] } }
                var menuOpen by remember { mutableStateOf(false) }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onOpenPlaylist(pl) }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = HiFiSpacing.lg, end = HiFiSpacing.xs, top = 12.dp, bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TrackArtworkBox(
                            uri = resolved.firstOrNull()?.uri.orEmpty(),
                            albumId = resolved.firstOrNull()?.albumId ?: 0L,
                            highRes = false,
                            cornerRadius = 14.dp,
                            iconSize = 22.dp,
                            modifier = Modifier.size(52.dp),
                            loadArtwork = loadArtwork
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = pl.name,
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.inkPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${resolved.size} track(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                        }
                        if (resolved.isNotEmpty()) {
                            IconButton(onClick = { onPlayPlaylist(pl) }) {
                                Icon(
                                    Icons.Rounded.PlayArrow,
                                    contentDescription = localText("Play all"),
                                    tint = tokens.accent
                                )
                            }
                        }
                        Box {
                            HiFiTrackOverflowButton(onClick = { menuOpen = true })
                            DropdownMenu(
                                expanded = menuOpen,
                                onDismissRequest = { menuOpen = false },
                                containerColor = tokens.glassElevated
                            ) {
                                DropdownMenuItem(
                                    text = { Text("Rename playlist") },
                                    leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                                    onClick = {
                                        menuOpen = false
                                        onRenamePlaylist(pl)
                                    }
                                )
                                DropdownMenuItem(
                                    text = { Text("Delete playlist", color = tokens.error) },
                                    leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = tokens.error) },
                                    onClick = {
                                        menuOpen = false
                                        onDeletePlaylist(pl)
                                    }
                                )
                            }
                        }
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = HiFiSpacing.lg + 66.dp, end = HiFiSpacing.lg),
                        thickness = 0.65.dp,
                        color = tokens.divider
                    )
                }
            }
        }
    }
}

@Composable
private fun SmartPlaylistRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    showPlay: Boolean = false,
    onPlay: () -> Unit = {},
    onClick: () -> Unit,
    trailingMenu: (@Composable () -> Unit)? = null
) {
    val tokens = HiFiTheme.colors
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = HiFiSpacing.lg, end = if (trailingMenu != null || showPlay) HiFiSpacing.xs else HiFiSpacing.lg, top = 11.dp, bottom = 11.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(HiFiShapes.artworkRow)
                    .background(tokens.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = tokens.accentBright,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }
            if (showPlay) {
                IconButton(onClick = onPlay) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = localText("Play all"),
                        tint = tokens.accent
                    )
                }
            }
            trailingMenu?.invoke()
        }
        HorizontalDivider(
            modifier = Modifier.padding(start = HiFiSpacing.lg + 66.dp, end = HiFiSpacing.lg),
            thickness = 0.65.dp,
            color = tokens.divider
        )
    }
}

@Composable
private fun FoldersTabContent(
    folders: List<FolderGroup>,
    bottomContentPadding: Dp,
    onOpenFolder: (FolderGroup) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    if (folders.isEmpty()) {
        HiFiEmptyState(
            icon = Icons.Rounded.Folder,
            title = "No audio folders found",
            subtitle = "Folders containing local audio files will appear here."
        )
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(bottom = bottomContentPadding),
        modifier = Modifier.fillMaxSize()
    ) {
        items(folders, key = { it.path }) { folder ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpenFolder(folder) }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HiFiSpacing.lg, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackArtworkBox(
                        uri = folder.coverTrackUri,
                        albumId = folder.coverAlbumId,
                        highRes = false,
                        cornerRadius = 14.dp,
                        iconSize = 22.dp,
                        modifier = Modifier.size(52.dp),
                        loadArtwork = loadArtwork
                    )
                    Spacer(Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = folder.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            color = tokens.inkPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${folder.path} · ${folder.tracks.size} track(s)",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.inkSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                HorizontalDivider(
                    modifier = Modifier.padding(start = HiFiSpacing.lg + 66.dp, end = HiFiSpacing.lg),
                    thickness = 0.65.dp,
                    color = tokens.divider
                )
            }
        }
    }
}

@Composable
private fun TrackCollectionDetailScreen(
    title: String,
    subtitle: String,
    coverUri: String,
    coverAlbumId: Long,
    tracks: List<LibraryTrack>,
    currentTrackUri: String?,
    isPlaying: Boolean,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (LibraryTrack) -> Unit,
    onPlayNext: (LibraryTrack) -> Unit,
    onAddToQueue: (LibraryTrack) -> Unit,
    onAddToPlaylist: (LibraryTrack) -> Unit,
    onChangeArtist: (LibraryTrack) -> Unit,
    onOpenAudioInspector: (LibraryTrack) -> Unit,
    onEditTrackMetadata: (LibraryTrack) -> Unit,
    onBatchEdit: (() -> Unit)? = null,
    onEditSmartRules: (() -> Unit)? = null,
    onRemoveFromPlaylist: ((LibraryTrack) -> Unit)? = null,
    onShare: (LibraryTrack) -> Unit,
    onDelete: (LibraryTrack) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        HiFiTopChrome(
            title = title,
            leading = {
                IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = localText("Back"),
                        tint = tokens.inkPrimary
                    )
                }
            },
            trailing = {
                if (onEditSmartRules != null) {
                    IconButton(onClick = onEditSmartRules, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Tune,
                            contentDescription = localText("Edit rules"),
                            tint = tokens.accentBright
                        )
                    }
                }
                if (onBatchEdit != null) {
                    IconButton(onClick = onBatchEdit, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Edit,
                            contentDescription = localText("Batch edit metadata"),
                            tint = tokens.inkSecondary
                        )
                    }
                }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HiFiSpacing.lg, vertical = HiFiSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    TrackArtworkBox(
                        uri = coverUri,
                        albumId = coverAlbumId,
                        highRes = true,
                        cornerRadius = 22.dp,
                        iconSize = 48.dp,
                        modifier = Modifier.size(164.dp),
                        loadArtwork = loadArtwork
                    )
                    Spacer(Modifier.height(HiFiSpacing.md))
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.inkSecondary
                    )
                    Spacer(Modifier.height(HiFiSpacing.md))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HiFiPillActionButton(
                            label = "Play all",
                            icon = Icons.Rounded.PlayArrow,
                            onClick = onPlayAll,
                            enabled = tracks.isNotEmpty(),
                            primary = true,
                            modifier = Modifier.weight(1f)
                        )
                        HiFiPillActionButton(
                            label = "Shuffle",
                            icon = Icons.Rounded.Shuffle,
                            onClick = onShuffle,
                            enabled = tracks.isNotEmpty(),
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                Spacer(Modifier.height(HiFiSpacing.sm))
                HorizontalDivider(thickness = 0.65.dp, color = tokens.divider)
            }

            if (tracks.isEmpty()) {
                item {
                    HiFiEmptyState(
                        icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                        title = "No tracks in this collection",
                        subtitle = "Add tracks from your library or Now Playing screen."
                    )
                }
            } else {
                items(tracks, key = { it.uri }) { track ->
                    val isCurrent = currentTrackUri == track.uri
                    LibraryTrackItemRow(
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        onPlay = { onPlayTrack(track) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onChangeArtist = { onChangeArtist(track) },
                        onOpenAudioInspector = { onOpenAudioInspector(track) },
                        onEditMetadata = { onEditTrackMetadata(track) },
                        onRemoveFromPlaylist = onRemoveFromPlaylist?.let { cb -> { cb(track) } },
                        onShare = { onShare(track) },
                        onDelete = { onDelete(track) },
                        loadArtwork = loadArtwork
                    )
                }
            }
        }
    }
}

@Composable
private fun ArtistDetailScreen(
    artist: ArtistGroup,
    currentTrackUri: String?,
    isPlaying: Boolean,
    bottomContentPadding: Dp,
    onBack: () -> Unit,
    onEditArtist: () -> Unit,
    onManageSongs: () -> Unit,
    onOpenAlbum: (AlbumGroup) -> Unit,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onPlayTrack: (LibraryTrack) -> Unit,
    onPlayNext: (LibraryTrack) -> Unit,
    onAddToQueue: (LibraryTrack) -> Unit,
    onAddToPlaylist: (LibraryTrack) -> Unit,
    onChangeArtist: (LibraryTrack) -> Unit,
    onOpenAudioInspector: (LibraryTrack) -> Unit,
    onEditTrackMetadata: (LibraryTrack) -> Unit,
    onBatchEdit: (() -> Unit)? = null,
    onShare: (LibraryTrack) -> Unit,
    onDelete: (LibraryTrack) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    Column(modifier = Modifier.fillMaxSize()) {
        HiFiTopChrome(
            title = artist.name,
            subtitle = "${artist.tracks.size} track(s) · ${artist.albums.size} album(s)",
            leading = {
                IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = localText("Back"),
                        tint = tokens.inkPrimary
                    )
                }
            },
            trailing = {
                if (onBatchEdit != null) {
                    IconButton(onClick = onBatchEdit, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.Rounded.Checklist,
                            contentDescription = localText("Batch edit metadata"),
                            tint = tokens.inkSecondary
                        )
                    }
                }
                IconButton(onClick = onManageSongs, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Rounded.LibraryAdd,
                        contentDescription = localText("Manage songs"),
                        tint = tokens.inkPrimary
                    )
                }
                IconButton(onClick = onEditArtist, modifier = Modifier.size(38.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = localText("Edit artist"),
                        tint = tokens.accentBright
                    )
                }
            }
        )

        LazyColumn(
            contentPadding = PaddingValues(bottom = bottomContentPadding),
            modifier = Modifier.fillMaxSize()
        ) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = HiFiSpacing.lg, vertical = HiFiSpacing.sm),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    ArtistAvatarBox(
                        customCoverPath = artist.customCoverPath,
                        fallbackTrackUri = artist.coverTrackUri,
                        fallbackAlbumId = artist.coverAlbumId,
                        highRes = true,
                        iconSize = 44.dp,
                        modifier = Modifier
                            .size(124.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onEditArtist),
                        loadArtwork = loadArtwork
                    )
                    Spacer(Modifier.height(HiFiSpacing.md))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HiFiPillActionButton(
                            label = "Play all",
                            icon = Icons.Rounded.PlayArrow,
                            onClick = onPlayAll,
                            enabled = artist.tracks.isNotEmpty(),
                            primary = true,
                            modifier = Modifier.weight(1f)
                        )
                        HiFiPillActionButton(
                            label = "Shuffle",
                            icon = Icons.Rounded.Shuffle,
                            onClick = onShuffle,
                            enabled = artist.tracks.isNotEmpty(),
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        HiFiPillActionButton(
                            label = "Manage songs",
                            icon = Icons.Rounded.LibraryAdd,
                            onClick = onManageSongs,
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                        HiFiPillActionButton(
                            label = "Edit artist",
                            icon = Icons.Rounded.Edit,
                            onClick = onEditArtist,
                            primary = false,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            if (artist.albums.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(HiFiSpacing.md))
                    Text(
                        text = "Albums",
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.inkPrimary,
                        modifier = Modifier.padding(horizontal = HiFiSpacing.lg)
                    )
                    Spacer(Modifier.height(HiFiSpacing.sm))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = HiFiSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(artist.albums, key = { it.id }) { album ->
                            HiFiArtworkTile(
                                title = album.title,
                                subtitle = "${album.tracks.size} track(s)",
                                artworkUri = album.coverTrackUri,
                                albumId = album.albumId,
                                tileWidth = 132.dp,
                                onClick = { onOpenAlbum(album) },
                                loadArtwork = loadArtwork
                            )
                        }
                    }
                    Spacer(Modifier.height(HiFiSpacing.lg))
                    Text(
                        text = "Songs",
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.inkPrimary,
                        modifier = Modifier.padding(horizontal = HiFiSpacing.lg)
                    )
                    Spacer(Modifier.height(HiFiSpacing.xs))
                }
            }

            if (artist.tracks.isEmpty()) {
                item {
                    HiFiEmptyState(
                        icon = Icons.Rounded.LibraryMusic,
                        title = "No songs assigned yet",
                        subtitle = "Add songs from your library to group them under this artist.",
                        actionLabel = "Manage songs",
                        onAction = onManageSongs
                    )
                }
            } else {
                items(artist.tracks, key = { it.uri }) { track ->
                    val isCurrent = currentTrackUri == track.uri
                    LibraryTrackItemRow(
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && isPlaying,
                        onPlay = { onPlayTrack(track) },
                        onPlayNext = { onPlayNext(track) },
                        onAddToQueue = { onAddToQueue(track) },
                        onAddToPlaylist = { onAddToPlaylist(track) },
                        onChangeArtist = { onChangeArtist(track) },
                        onOpenAudioInspector = { onOpenAudioInspector(track) },
                        onEditMetadata = { onEditTrackMetadata(track) },
                        onShare = { onShare(track) },
                        onDelete = { onDelete(track) },
                        loadArtwork = loadArtwork
                    )
                }
            }
        }
    }
}

@Composable
private fun LibraryTrackItemRow(
    track: LibraryTrack,
    isCurrent: Boolean,
    isPlaying: Boolean,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onAddToQueue: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onChangeArtist: () -> Unit,
    onOpenAudioInspector: () -> Unit,
    onEditMetadata: () -> Unit,
    onRemoveFromPlaylist: (() -> Unit)? = null,
    onShare: () -> Unit,
    onDelete: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    var menuExpanded by remember { mutableStateOf(false) }

    HiFiTrackRow(
        track = track,
        isCurrent = isCurrent,
        isPlaying = isPlaying,
        onClick = onPlay,
        loadArtwork = loadArtwork,
        trailingMenu = {
            Box {
                HiFiTrackOverflowButton(onClick = { menuExpanded = true })
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    containerColor = tokens.glassElevated
                ) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = { Icon(Icons.Rounded.QueuePlayNext, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onPlayNext()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddToQueue()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Add to playlist") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onAddToPlaylist()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Audio details") },
                        leadingIcon = { Icon(Icons.Rounded.GraphicEq, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onOpenAudioInspector()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Edit metadata") },
                        leadingIcon = { Icon(Icons.Rounded.Edit, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onEditMetadata()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Change artist") },
                        leadingIcon = { Icon(Icons.Rounded.Person, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onChangeArtist()
                        }
                    )
                    if (onRemoveFromPlaylist != null) {
                        DropdownMenuItem(
                            text = { Text("Remove from playlist") },
                            leadingIcon = { Icon(Icons.Rounded.Clear, contentDescription = null) },
                            onClick = {
                                menuExpanded = false
                                onRemoveFromPlaylist()
                            }
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Share track") },
                        leadingIcon = { Icon(Icons.Rounded.Share, contentDescription = null) },
                        onClick = {
                            menuExpanded = false
                            onShare()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Delete track", color = tokens.error) },
                        leadingIcon = { Icon(Icons.Rounded.Delete, contentDescription = null, tint = tokens.error) },
                        onClick = {
                            menuExpanded = false
                            onDelete()
                        }
                    )
                }
            }
        }
    )
}

@Composable
private fun PlaylistNameDialog(
    title: String,
    initialName: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    val tokens = HiFiTheme.colors
    var text by remember(initialName) { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = { Text(title, color = tokens.inkPrimary) },
        text = {
            HiFiGlassTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = "Playlist name",
                leadingIcon = null
            )
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = tokens.inkSecondary) }
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
                enabled = text.isNotBlank()
            ) {
                Text(confirmLabel, color = tokens.accent)
            }
        }
    )
}

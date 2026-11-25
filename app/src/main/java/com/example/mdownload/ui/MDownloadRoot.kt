package com.example.mdownload.ui

import android.app.Activity
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.mdownload.AppTab
import com.example.mdownload.MainViewModel
import com.example.mdownload.core.YouTubeUrlParser
import com.example.mdownload.player.ui.AddToPlaylistDialog
import com.example.mdownload.player.ui.AudioInspectorSheet
import com.example.mdownload.player.ui.BatchMetadataEditorSheet
import com.example.mdownload.player.ui.LibraryHubScreen
import com.example.mdownload.player.ui.NowPlayingScreen
import com.example.mdownload.player.ui.SleepTimerSheet
import com.example.mdownload.player.ui.TrackMetadataEditorSheet
import com.example.mdownload.ui.components.HiFiAtmosphericCanvas
import com.example.mdownload.ui.components.MetadataEditorDialog
import com.example.mdownload.ui.components.PlaylistTrackPickerDialog
import com.example.mdownload.ui.components.UnifiedBottomDock
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.design.extractArtworkAuraColor
import com.example.mdownload.ui.screens.DiscoverScreen
import com.example.mdownload.ui.screens.DownloadsScreen
import com.example.mdownload.ui.screens.HomeScreen
import com.example.mdownload.ui.screens.SettingsScreen

@Composable
fun MDownloadRoot(
    viewModel: MainViewModel,
    onRequestAudioPermission: () -> Unit = {}
) {
    val tokens = HiFiTheme.colors
    val selectedTab by viewModel.selectedTab.collectAsState()
    val engineState by viewModel.engineState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val homeState by viewModel.homeState.collectAsState()
    val discoverState by viewModel.discoverState.collectAsState()
    val jobs by viewModel.jobs.collectAsState()
    val history by viewModel.history.collectAsState()
    val isQueueRunning by viewModel.isQueueRunning.collectAsState()
    val isQueuePaused by viewModel.isQueuePaused.collectAsState()
    val totalSpeed by viewModel.totalSpeed.collectAsState()
    val libraryTracks by viewModel.libraryTracks.collectAsState()
    val userPlaylists by viewModel.userPlaylists.collectAsState()
    val artistCatalog by viewModel.artistCatalog.collectAsState()
    val isLibrarySyncing by viewModel.isLibrarySyncing.collectAsState()
    val hasAudioPermission by viewModel.hasAudioPermission.collectAsState()
    val playbackState by viewModel.playbackState.collectAsState()
    val lyricsState by viewModel.lyricsState.collectAsState()
    val sleepTimerState by viewModel.sleepTimerState.collectAsState()
    val listeningStats by viewModel.listeningStats.collectAsState()
    val customSmartPlaylists by viewModel.customSmartPlaylists.collectAsState()
    val evaluatedSmartPlaylists by viewModel.evaluatedSmartPlaylists.collectAsState()
    val audioAnalysisVersion by viewModel.audioAnalysisCacheVersion.collectAsState()
    val audioInspectorState by viewModel.audioInspectorState.collectAsState()
    val isSleepTimerOpen by viewModel.isSleepTimerOpen.collectAsState()
    val trackMetadataEditorState by viewModel.trackMetadataEditorState.collectAsState()
    val batchMetadataEditorState by viewModel.batchMetadataEditorState.collectAsState()
    val pendingWritePermissionUris by viewModel.pendingWritePermissionUris.collectAsState()
    val isNowPlayingOpen by viewModel.isNowPlayingOpen.collectAsState()
    val playlistPickerTrack by viewModel.playlistPickerTrack.collectAsState()
    val playlistPicker by viewModel.playlistPicker.collectAsState()
    val metadataEditor by viewModel.metadataEditor.collectAsState()
    val bannerMessage by viewModel.bannerMessage.collectAsState()

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val writePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        viewModel.onWritePermissionResult(result.resultCode == Activity.RESULT_OK)
    }
    LaunchedEffect(pendingWritePermissionUris) {
        val uris = pendingWritePermissionUris
        if (!uris.isNullOrEmpty()) {
            runCatching {
                val pendingIntent = MediaStore.createWriteRequest(context.contentResolver, uris)
                writePermissionLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
            }.onFailure {
                viewModel.onWritePermissionResult(false)
            }
        }
    }
    val smartEvalContext = remember(listeningStats, userPlaylists, audioAnalysisVersion) {
        viewModel.currentSmartPlaylistEvalContext()
    }
    val folderPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            viewModel.selectStorageFolder(uri)
        }
    }
    val lyricsImportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            viewModel.importLyricsFromUri(uri)
        }
    }
    val lyricsExportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        if (uri != null) {
            viewModel.exportActiveLyricsToUri(uri)
        }
    }
    val isVietnamese = LocalConfiguration.current.locales[0].language == "vi"
    val downloadedVideoIds = remember(libraryTracks) {
        libraryTracks.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toSet()
    }
    val queuedVideoIds = remember(jobs) {
        jobs.filterNot { it.isConnectionCheck }
            .mapNotNull { YouTubeUrlParser.extractVideoId(it.url) }
            .toSet()
    }

    // Dynamic ambient top-aura tint subtly following the active track artwork
    var activeArtworkBitmap by remember { mutableStateOf<Bitmap?>(null) }
    val currentTrack = playbackState.currentTrack
    LaunchedEffect(currentTrack?.uri, currentTrack?.albumId) {
        activeArtworkBitmap = if (currentTrack != null) {
            viewModel.loadTrackArtwork(currentTrack.uri, currentTrack.albumId, false)
        } else {
            null
        }
    }
    val ambientAuraColor = remember(activeArtworkBitmap, tokens.auraPeak, tokens.isDark) {
        extractArtworkAuraColor(activeArtworkBitmap, tokens.auraPeak, tokens.isDark)
    }

    val hasMiniPlayer = playbackState.currentTrack != null
    val bottomDockClearance = if (hasMiniPlayer) {
        HiFiSpacing.dockWithMiniPlayerClearance
    } else {
        HiFiSpacing.dockBaseClearance
    }

    var previousTabBeforeDiscover by remember { mutableStateOf(AppTab.HOME) }
    fun openDiscover() {
        if (selectedTab != AppTab.DISCOVER) {
            previousTabBeforeDiscover = selectedTab
        }
        viewModel.selectTab(AppTab.DISCOVER)
    }

    LaunchedEffect(bannerMessage) {
        val msg = bannerMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(translateText(msg, isVietnamese))
            viewModel.clearBanner()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HiFiAtmosphericCanvas(
            modifier = Modifier.fillMaxSize(),
            auraTint = ambientAuraColor,
            auraIntensity = 0.92f
        ) {
            // Active full-bleed tab content
            Box(modifier = Modifier.fillMaxSize()) {
                when (selectedTab) {
                    AppTab.HOME -> HomeScreen(
                        homeState = homeState,
                        engineState = engineState,
                        settings = settings,
                        jobs = jobs,
                        isQueueRunning = isQueueRunning,
                        totalSpeed = totalSpeed,
                        libraryTracks = libraryTracks,
                        playbackState = playbackState,
                        bottomContentPadding = bottomDockClearance,
                        onUrlChange = viewModel::onUrlInputChanged,
                        onClearUrl = viewModel::clearUrlInput,
                        onApplyPreset = viewModel::applyPreset,
                        onOpenMetadataEditor = viewModel::openComposerMetadataEditor,
                        onSubmit = viewModel::submitComposerUrls,
                        onOpenDiscover = ::openDiscover,
                        onOpenDownloads = { viewModel.selectTab(AppTab.DOWNLOADS) },
                        onOpenLibrary = { viewModel.selectTab(AppTab.LIBRARY) },
                        onOpenNowPlaying = viewModel::openNowPlaying,
                        onTogglePlayPause = viewModel::togglePlayPause,
                        onPlayTrackInQueue = viewModel::playTrackInQueue,
                        onPlayQueue = viewModel::playQueue,
                        loadArtwork = viewModel::loadTrackArtwork,
                        playbackPositionMsFlow = viewModel.playbackPositionMs
                    )

                    AppTab.DISCOVER -> DiscoverScreen(
                        discoverState = discoverState,
                        jobs = jobs,
                        downloadedVideoIds = downloadedVideoIds,
                        skipDuplicates = settings.skipDuplicates,
                        bottomContentPadding = bottomDockClearance,
                        onBack = { viewModel.selectTab(previousTabBeforeDiscover) },
                        onQueryChange = viewModel::onDiscoverQueryChanged,
                        onClearQuery = viewModel::clearDiscoverQuery,
                        onSearch = { q -> viewModel.submitDiscoverSearch(explicitQuery = q) },
                        onCancelSearch = viewModel::cancelDiscoverSearch,
                        onSelectFilter = viewModel::setDiscoverFilter,
                        onAddResult = viewModel::addDiscoverResult,
                        onEditResultMetadata = viewModel::openDiscoverMetadataEditor
                    )

                    AppTab.DOWNLOADS -> DownloadsScreen(
                        jobs = jobs,
                        history = history,
                        settings = settings,
                        isQueueRunning = isQueueRunning,
                        isQueuePaused = isQueuePaused,
                        totalSpeed = totalSpeed,
                        bottomContentPadding = bottomDockClearance,
                        onOpenDiscover = ::openDiscover,
                        onStartQueue = viewModel::startQueue,
                        onPauseQueue = viewModel::pauseQueue,
                        onStopQueue = viewModel::stopQueue,
                        onRemoveJob = viewModel::removeJob,
                        onMoveJobUp = viewModel::moveJobUp,
                        onMoveJobDown = viewModel::moveJobDown,
                        onMoveJobToTop = viewModel::moveJobToTop,
                        onClearWaiting = viewModel::clearWaitingJobs,
                        onEditJobMetadata = viewModel::openJobMetadataEditor,
                        onRetryHistory = viewModel::retryHistoryRecord,
                        onRetryAllFailed = viewModel::retryAllFailed,
                        onPlayHistory = viewModel::playUriFromHistory,
                        onAddHistoryToQueue = viewModel::addHistoryToPlaybackQueue,
                        onClearHistory = viewModel::clearHistory,
                        onSetParallelDownloads = viewModel::setParallelDownloads,
                        onSetDownloadThreads = viewModel::setDownloadThreads
                    )

                    AppTab.LIBRARY -> LibraryHubScreen(
                        tracks = libraryTracks,
                        playlists = userPlaylists,
                        builtInSmartPlaylists = viewModel.builtInSmartPlaylists,
                        customSmartPlaylists = customSmartPlaylists,
                        evaluatedSmartPlaylists = evaluatedSmartPlaylists,
                        smartPlaylistEvalContext = smartEvalContext,
                        artistCatalog = artistCatalog,
                        playbackState = playbackState,
                        storageLabel = settings.effectiveStorageLabel,
                        scanAllDeviceMusic = settings.scanAllDeviceMusic,
                        hasAudioPermission = hasAudioPermission,
                        isSyncing = isLibrarySyncing,
                        canChangeFolder = !isQueueRunning && jobs.none { it.status.isActive },
                        bottomContentPadding = bottomDockClearance,
                        onRequestAudioPermission = onRequestAudioPermission,
                        onPickFolder = { folderPicker.launch(null) },
                        onRefresh = { viewModel.syncStorageFolder(showFeedback = true) },
                        onPlayTrackInQueue = viewModel::playTrackInQueue,
                        onPlayQueue = viewModel::playQueue,
                        onPlayNext = viewModel::playNextInQueue,
                        onAddToQueue = viewModel::addTrackToPlaybackQueue,
                        onAddTrackToPlaylist = viewModel::openAddTrackToPlaylist,
                        onCreatePlaylist = { name -> viewModel.createUserPlaylist(name) },
                        onRenamePlaylist = viewModel::renameUserPlaylist,
                        onDeletePlaylist = viewModel::deleteUserPlaylist,
                        onRemoveFromPlaylist = viewModel::removeTrackFromUserPlaylist,
                        onSaveCustomSmartPlaylist = viewModel::saveCustomSmartPlaylist,
                        onRenameCustomSmartPlaylist = viewModel::renameCustomSmartPlaylist,
                        onDeleteCustomSmartPlaylist = viewModel::deleteCustomSmartPlaylist,
                        onOpenAudioInspector = viewModel::openAudioInspector,
                        onEditTrackMetadata = viewModel::openTrackMetadataEditor,
                        onBatchEditTracks = viewModel::openBatchMetadataEditor,
                        onDeleteTrack = viewModel::deleteLibraryTrack,
                        onImportArtistCover = viewModel::importArtistCover,
                        onCreateArtist = viewModel::createArtist,
                        onEditArtist = viewModel::editArtist,
                        onDeleteArtist = viewModel::deleteArtist,
                        onSetArtistTracks = viewModel::setArtistTracks,
                        onSetTrackArtist = viewModel::setTrackArtist,
                        onCreateArtistAndAssignTrack = viewModel::createArtistAndAssignTrack,
                        onResetTrackArtist = viewModel::resetTrackArtist,
                        originalArtistForTrack = viewModel::originalArtistForTrack,
                        loadArtwork = viewModel::loadTrackArtwork
                    )

                    AppTab.SETTINGS -> SettingsScreen(
                        viewModel = viewModel,
                        bottomContentPadding = bottomDockClearance
                    )
                }
            }

            // Floating Snackbar positioned cleanly above the double-deck smoked-glass dock
            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = bottomDockClearance - 8.dp, start = 16.dp, end = 16.dp)
            ) { data ->
                Snackbar(
                    snackbarData = data,
                    shape = HiFiShapes.pill,
                    containerColor = tokens.glassElevated,
                    contentColor = tokens.inkPrimary,
                    actionColor = tokens.accentBright
                )
            }

            // Signature floating double-deck smoked-glass dock (MiniPlayer + 5-slot nav capsule)
            UnifiedBottomDock(
                selectedTab = selectedTab,
                playbackState = playbackState,
                activeDownloadCount = jobs.size,
                onSelectTab = viewModel::selectTab,
                onCenterOrbClick = {
                    when {
                        playbackState.currentTrack != null -> viewModel.openNowPlaying()
                        libraryTracks.isNotEmpty() -> {
                            viewModel.playQueue(libraryTracks, false)
                            viewModel.openNowPlaying()
                        }
                        else -> viewModel.selectTab(AppTab.LIBRARY)
                    }
                },
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipToNext,
                loadArtwork = viewModel::loadTrackArtwork,
                playbackPositionMsFlow = viewModel.playbackPositionMs,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Full-screen Orbital Now Playing overlay (Reference 1 Normal + Reference 2 Orbital Navigator)
        AnimatedVisibility(
            visible = isNowPlayingOpen && playbackState.currentTrack != null,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut()
        ) {
            val activeTrackUri = playbackState.currentTrack?.uri.orEmpty()
            val isFavorite = userPlaylists.any {
                it.name.equals("Favorites", ignoreCase = true) && activeTrackUri in it.trackUris
            }
            NowPlayingScreen(
                state = playbackState,
                lyricsState = lyricsState,
                sleepTimerState = sleepTimerState,
                isFavorite = isFavorite,
                onCollapse = viewModel::closeNowPlaying,
                onToggleFavorite = viewModel::toggleFavoriteTrack,
                onTogglePlayPause = viewModel::togglePlayPause,
                onSkipNext = viewModel::skipToNext,
                onSkipPrevious = viewModel::skipToPrevious,
                onSeekTo = viewModel::seekTo,
                onToggleShuffle = viewModel::toggleShuffle,
                onCycleRepeat = viewModel::cycleRepeatMode,
                onSetSpeed = viewModel::setPlaybackSpeed,
                onSelectQueueIndex = viewModel::skipToQueueItem,
                onCommitOrbitalTimelineIndex = viewModel::selectQueueItemPreservingPlayState,
                onMoveQueueItem = viewModel::movePlaybackQueueItem,
                onRemoveQueueIndex = viewModel::removePlaybackQueueItem,
                onClearQueue = viewModel::clearPlaybackQueue,
                onAddTrackToPlaylist = viewModel::openAddTrackToPlaylist,
                onOpenAudioInspector = viewModel::openAudioInspector,
                onOpenSleepTimer = viewModel::openSleepTimer,
                onEditTrackMetadata = viewModel::openTrackMetadataEditor,
                onDismissError = viewModel::dismissPlaybackError,
                onSearchOnlineLyrics = viewModel::searchOnlineLyrics,
                onSelectLyricsCandidate = viewModel::selectLyricsCandidate,
                onSaveManualLyrics = viewModel::saveManualLyrics,
                onImportLyricsFile = {
                    lyricsImportPicker.launch(arrayOf("text/*", "application/octet-stream", "*/*"))
                },
                onExportLrcFile = {
                    val base = playbackState.currentTrack?.displayName?.substringBeforeLast('.')
                        ?.ifBlank { playbackState.currentTrack?.title }
                        ?: "lyrics"
                    lyricsExportPicker.launch("$base.lrc")
                },
                onEmbedLyricsIntoFile = viewModel::embedActiveLyricsIntoAudioFile,
                onAdjustLyricsOffset = viewModel::adjustLyricsOffset,
                onResetLyricsOffset = viewModel::resetLyricsOffset,
                onClearLyrics = viewModel::clearActiveLyrics,
                onDismissLyricsNotice = viewModel::dismissLyricsNotice,
                loadArtwork = viewModel::loadTrackArtwork,
                positionMsFlow = viewModel.playbackPositionMs
            )
        }
    }

    playlistPickerTrack?.let { pendingTrack ->
        AddToPlaylistDialog(
            track = pendingTrack,
            playlists = userPlaylists,
            onSelectPlaylist = { playlistId ->
                viewModel.addTrackToUserPlaylist(playlistId, pendingTrack)
            },
            onCreateAndAdd = { name ->
                viewModel.createUserPlaylist(name, initialTrack = pendingTrack)
            },
            onDismiss = viewModel::dismissAddTrackToPlaylist
        )
    }

    playlistPicker?.let { picker ->
        PlaylistTrackPickerDialog(
            state = picker,
            downloadedVideoIds = downloadedVideoIds,
            queuedVideoIds = queuedVideoIds,
            skipDuplicates = settings.skipDuplicates,
            onDismiss = viewModel::dismissPlaylistPicker,
            onOnlyThisVideo = picker.singleVideoUrl?.let { single ->
                {
                    viewModel.dismissPlaylistPicker()
                    viewModel.addFromDiscover(single, picker.title, onlySingleVideo = true)
                }
            },
            onConfirm = viewModel::confirmPlaylistTracks
        )
    }

    metadataEditor?.let { editor ->
        MetadataEditorDialog(
            state = editor,
            onImportCover = viewModel::importCustomCover,
            onFetchHdCover = viewModel::fetchHighResCover,
            onDismiss = viewModel::dismissMetadataEditor,
            onSave = viewModel::saveMetadataEditor
        )
    }

    if (audioInspectorState.track != null) {
        AudioInspectorSheet(
            state = audioInspectorState,
            onDismiss = viewModel::dismissAudioInspector,
            onRefresh = viewModel::refreshAudioInspector,
            onScanLoudness = viewModel::scanActiveInspectorLoudness,
            onCancelScan = viewModel::cancelInspectorLoudnessScan,
            onEditMetadata = { track ->
                viewModel.dismissAudioInspector()
                viewModel.openTrackMetadataEditor(track)
            }
        )
    }

    if (isSleepTimerOpen) {
        SleepTimerSheet(
            state = sleepTimerState,
            defaultFadeSeconds = settings.sleepTimerDefaultFadeSec,
            onStartDuration = viewModel::startSleepTimerDuration,
            onStartEndOfTrack = viewModel::startSleepTimerEndOfTrack,
            onStartEndOfQueue = viewModel::startSleepTimerEndOfQueue,
            onUpdateFadeSeconds = viewModel::updateSleepTimerFade,
            onExtendMinutes = viewModel::extendSleepTimer,
            onCancel = viewModel::cancelSleepTimer,
            onDismiss = viewModel::dismissSleepTimer
        )
    }

    trackMetadataEditorState?.let { editorState ->
        TrackMetadataEditorSheet(
            state = editorState,
            onPrepareArtwork = viewModel::prepareTrackEditorArtwork,
            onSave = viewModel::saveTrackMetadata,
            onDismiss = viewModel::dismissTrackMetadataEditor
        )
    }

    batchMetadataEditorState?.let { batchState ->
        BatchMetadataEditorSheet(
            state = batchState,
            onPrepareArtwork = viewModel::prepareTrackEditorArtwork,
            onApplyBatch = viewModel::applyBatchMetadata,
            onCancelBatch = viewModel::cancelBatchMetadata,
            onDismiss = viewModel::dismissBatchMetadataEditor
        )
    }
}

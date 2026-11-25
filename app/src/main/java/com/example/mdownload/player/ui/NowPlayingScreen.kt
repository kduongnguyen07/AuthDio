package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import com.example.mdownload.player.lyrics.LyricsCandidate
import com.example.mdownload.player.lyrics.LyricsUiState
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.playback.SleepTimerMode
import com.example.mdownload.player.playback.SleepTimerState
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

private val PLAYBACK_SPEEDS = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)

/**
 * Redesigned Orbital Music Player uniting:
 * - Default State (Reference 1): Near-black void with subtle deep-navy washes, concentric bezel,
 *   continuous angle-based circular drag seeking ring ([CircularSeekRing]), solid white Play/Pause
 *   orb, and compact Next Songs preview.
 * - Orbital State (Reference 2): Long-pressing the inner album artwork morphs smoothly into a
 *   vertical curved constellation of queue tracks with zero gesture conflict against circular seek.
 * - Synchronized Lyrics Mode ([LyricsScreen]): Seamless toggle between artwork disc and live
 *   Media3-synced lyrics with search, import, offset, editing, export, and safe file embedding.
 */
@Composable
fun NowPlayingScreen(
    state: PlaybackUiState,
    lyricsState: LyricsUiState = LyricsUiState(),
    sleepTimerState: SleepTimerState = SleepTimerState(),
    isFavorite: Boolean,
    onCollapse: () -> Unit,
    onToggleFavorite: (LibraryTrack) -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onSetSpeed: (Float) -> Unit,
    onSelectQueueIndex: (Int) -> Unit,
    onCommitOrbitalTimelineIndex: (Int) -> Unit,
    onMoveQueueItem: (Int, Int) -> Unit,
    onRemoveQueueIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    onAddTrackToPlaylist: (LibraryTrack) -> Unit,
    onOpenAudioInspector: (LibraryTrack) -> Unit = {},
    onOpenSleepTimer: () -> Unit = {},
    onEditTrackMetadata: (LibraryTrack) -> Unit = {},
    onDismissError: () -> Unit,
    onSearchOnlineLyrics: (title: String?, artist: String?) -> Unit = { _, _ -> },
    onSelectLyricsCandidate: (LyricsCandidate) -> Unit = {},
    onSaveManualLyrics: (String) -> Unit = {},
    onImportLyricsFile: () -> Unit = {},
    onExportLrcFile: () -> Unit = {},
    onEmbedLyricsIntoFile: () -> Unit = {},
    onAdjustLyricsOffset: (Long) -> Unit = {},
    onResetLyricsOffset: () -> Unit = {},
    onClearLyrics: () -> Unit = {},
    onDismissLyricsNotice: () -> Unit = {},
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    positionMsFlow: StateFlow<Long>? = null
) {
    val tokens = HiFiTheme.colors
    val track = state.currentTrack
    val navQueue = remember(state.queue, state.orderedQueueIndices) { state.navigationQueue }
    val navCurrentIndex = remember(state.queue, state.orderedQueueIndices, state.currentIndex) {
        state.navigationCurrentIndex
    }
    val upcoming = remember(state.queue, state.orderedQueueIndices, state.currentIndex, state.repeatMode) {
        state.upcomingTracks(limit = 2)
    }

    val orbitalController = rememberOrbitalGestureController()
    val scrubController = rememberCircularScrubController()
    var showLyrics by rememberSaveable { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }
    var overflowExpanded by remember { mutableStateOf(false) }

    val latestState by rememberUpdatedState(state)
    val latestNavQueue by rememberUpdatedState(navQueue)
    val latestNavIndex by rememberUpdatedState(navCurrentIndex)

    // Back press exits Orbital Mode first, then Lyrics Mode, otherwise collapses Now Playing.
    BackHandler {
        when {
            orbitalController.isOrbitVisible -> orbitalController.cancelOrbit()
            showLyrics -> showLyrics = false
            else -> onCollapse()
        }
    }

    // Cancel any active orbital drag or circular scrub cleanly if the app moves to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, orbitalController, scrubController) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE || event == Lifecycle.Event.ON_STOP) {
                orbitalController.cancelOrbit()
                scrubController.cancel()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Safely reconcile orbital indices if Media3's queue mutates mid-drag
    LaunchedEffect(navQueue.size, navCurrentIndex) {
        orbitalController.reconcileQueue(
            newQueueSize = navQueue.size,
            newCurrentNavIndex = navCurrentIndex
        )
    }

    // Cancel active circular scrub cleanly on track change or if Orbital Mode engages
    LaunchedEffect(track?.uri) {
        scrubController.reconcileTrackChange(track?.uri.orEmpty())
    }
    LaunchedEffect(orbitalController.isOrbitVisible, orbitalController.holdProgress > 0.005f, showLyrics) {
        if (orbitalController.isOrbitVisible || orbitalController.holdProgress > 0.005f || showLyrics) {
            scrubController.cancel()
        }
    }

    fun commitNavIndex(navIdx: Int) {
        val timelineIdx = latestState.timelineIndexForNavigation(navIdx)
        if (timelineIdx >= 0) {
            onCommitOrbitalTimelineIndex(timelineIdx)
        }
    }

    // Root container consumes unconsumed pointer events so nothing passes through to MDownloadRoot underneath.
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        event.changes.forEach { it.consume() }
                    }
                }
            }
            .background(OrbitalDesignTokens.Colors.BackgroundVoid)
            .drawWithCache {
                // Restrained deep-navy ambient washes cached per viewport size
                val topBrush = Brush.radialGradient(
                    0.0f to OrbitalDesignTokens.Colors.AmbientNavyTop.copy(alpha = 0.72f),
                    0.45f to OrbitalDesignTokens.Colors.AmbientNavyTopMid.copy(alpha = 0.42f),
                    0.82f to Color.Transparent,
                    center = Offset(size.width * 0.68f, -size.height * 0.02f),
                    radius = size.width * 1.05f
                )
                val bottomBrush = Brush.radialGradient(
                    0.0f to OrbitalDesignTokens.Colors.AmbientNavyBottom.copy(alpha = 0.64f),
                    0.48f to OrbitalDesignTokens.Colors.AmbientNavyBottomMid.copy(alpha = 0.34f),
                    0.85f to Color.Transparent,
                    center = Offset(size.width * 0.50f, size.height * 1.02f),
                    radius = size.width * 1.10f
                )
                onDrawBehind {
                    drawRect(brush = topBrush)
                    drawRect(brush = bottomBrush)
                }
            }
    ) {
        val viewportHeightPx = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val stepDistancePx = (viewportHeightPx * 0.165f).coerceAtLeast(115f)
        val compactScreen = maxHeight < 700.dp

        // LAYER 1: Normal Music Player (stays mounted during hold-and-drag so pointer input stays unbroken!)
        // Defer orbitProgress read to graphicsLayer so Orbital transitions never recompose Layer 1.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .graphicsLayer {
                    val p = orbitalController.orbitProgress.value
                    alpha = (1f - p * 1.45f).coerceIn(0f, 1f)
                    scaleX = 1f - 0.04f * p
                    scaleY = 1f - 0.04f * p
                },
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Minimal top chrome: collapse chevron (left), pill handle (center), overflow (right)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitalDesignTokens.Spacing.TopChromeHorizontal, vertical = 4.dp)
            ) {
                IconButton(
                    onClick = {
                        if (showLyrics) showLyrics = false else onCollapse()
                    },
                    modifier = Modifier.align(Alignment.CenterStart)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.KeyboardArrowDown,
                        contentDescription = localText(if (showLyrics) "Back to player" else "Collapse"),
                        tint = OrbitalDesignTokens.Colors.IconPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }

                if (sleepTimerState.isActive) {
                    val pillText = when (sleepTimerState.mode) {
                        SleepTimerMode.DURATION -> formatSleepCountdown(sleepTimerState.remainingMs)
                        SleepTimerMode.END_OF_TRACK -> localText("End of song")
                        SleepTimerMode.END_OF_QUEUE -> localText("End of queue")
                        SleepTimerMode.OFF -> ""
                    }
                    Row(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .clip(CircleShape)
                            .background(OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.85f))
                            .clickable(onClick = onOpenSleepTimer)
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Bedtime,
                            contentDescription = localText("Sleep Timer"),
                            tint = OrbitalDesignTokens.Colors.IconAccent,
                            modifier = Modifier.size(13.dp)
                        )
                        Text(
                            text = pillText,
                            style = OrbitalDesignTokens.Typography.NextSongMeta,
                            color = OrbitalDesignTokens.Colors.TextPrimary
                        )
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .size(width = 34.dp, height = 3.5.dp)
                            .clip(CircleShape)
                            .background(OrbitalDesignTokens.Colors.IconPrimary.copy(alpha = 0.28f))
                    )
                }

                Box(modifier = Modifier.align(Alignment.CenterEnd)) {
                    IconButton(onClick = { overflowExpanded = true }) {
                        Icon(
                            imageVector = Icons.Rounded.MoreVert,
                            contentDescription = localText("More options"),
                            tint = OrbitalDesignTokens.Colors.IconPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = overflowExpanded,
                        onDismissRequest = { overflowExpanded = false },
                        containerColor = OrbitalDesignTokens.Colors.BezelMid
                    ) {
                        DropdownMenuItem(
                            text = {
                                Text(
                                    if (showLyrics) "Artwork player" else "Lyrics",
                                    color = OrbitalDesignTokens.Colors.TextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics,
                                    contentDescription = null,
                                    tint = OrbitalDesignTokens.Colors.IconAccent
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                showLyrics = !showLyrics
                            }
                        )
                        if (navQueue.isNotEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Orbital navigation",
                                        color = OrbitalDesignTokens.Colors.TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.TrackChanges,
                                        contentDescription = null,
                                        tint = OrbitalDesignTokens.Colors.IconAccent
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    showLyrics = false
                                    orbitalController.enterOrbit(
                                        queueSize = navQueue.size,
                                        currentNavIndex = navCurrentIndex,
                                        accessibleMode = true
                                    )
                                }
                            )
                        }
                        if (track != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Audio details",
                                        color = OrbitalDesignTokens.Colors.TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.GraphicEq,
                                        contentDescription = null,
                                        tint = OrbitalDesignTokens.Colors.IconAccent
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onOpenAudioInspector(track)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                val sleepLabel = if (sleepTimerState.isActive) {
                                    when (sleepTimerState.mode) {
                                        SleepTimerMode.DURATION -> "Sleep timer (${formatSleepCountdown(sleepTimerState.remainingMs)})"
                                        SleepTimerMode.END_OF_TRACK -> "Sleep timer (End of song)"
                                        SleepTimerMode.END_OF_QUEUE -> "Sleep timer (End of queue)"
                                        SleepTimerMode.OFF -> "Sleep timer"
                                    }
                                } else "Sleep timer"
                                Text(
                                    sleepLabel,
                                    color = OrbitalDesignTokens.Colors.TextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Bedtime,
                                    contentDescription = null,
                                    tint = if (sleepTimerState.isActive) OrbitalDesignTokens.Colors.IconAccent else OrbitalDesignTokens.Colors.IconPrimary
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                onOpenSleepTimer()
                            }
                        )
                        if (track != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Edit metadata",
                                        color = OrbitalDesignTokens.Colors.TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.Rounded.Edit,
                                        contentDescription = null,
                                        tint = OrbitalDesignTokens.Colors.IconPrimary
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onEditTrackMetadata(track)
                                }
                            )
                        }
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Playing queue (${state.queue.size})",
                                    color = OrbitalDesignTokens.Colors.TextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.AutoMirrored.Rounded.QueueMusic,
                                    contentDescription = null,
                                    tint = OrbitalDesignTokens.Colors.IconPrimary
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                showQueueSheet = true
                            }
                        )
                        DropdownMenuItem(
                            text = {
                                Text(
                                    "Playback speed (${String.format(Locale.US, "%.2gx", state.playbackSpeed)})",
                                    color = OrbitalDesignTokens.Colors.TextPrimary
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Rounded.Speed,
                                    contentDescription = null,
                                    tint = OrbitalDesignTokens.Colors.IconPrimary
                                )
                            },
                            onClick = {
                                overflowExpanded = false
                                showSpeedDialog = true
                            }
                        )
                        if (track != null) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Add to playlist",
                                        color = OrbitalDesignTokens.Colors.TextPrimary
                                    )
                                },
                                leadingIcon = {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.PlaylistAdd,
                                        contentDescription = null,
                                        tint = OrbitalDesignTokens.Colors.IconPrimary
                                    )
                                },
                                onClick = {
                                    overflowExpanded = false
                                    onAddTrackToPlaylist(track)
                                }
                            )
                        }
                    }
                }
            }

            if (track == null) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Nothing is playing",
                        style = OrbitalDesignTokens.Typography.NextSongsHeading,
                        color = OrbitalDesignTokens.Colors.TextSecondary
                    )
                }
            } else {
                Spacer(Modifier.height(if (compactScreen) 4.dp else 8.dp))

                // Header: Light-weight song title + artist + Lyrics toggle + Favorite heart
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = OrbitalDesignTokens.Spacing.ScreenHorizontal),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = track.title,
                            style = if (showLyrics) {
                                OrbitalDesignTokens.Typography.OrbitalSelectedTitle
                            } else {
                                OrbitalDesignTokens.Typography.HeaderTitle
                            },
                            color = OrbitalDesignTokens.Colors.TextPrimary,
                            maxLines = if (showLyrics) 1 else 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = track.artist,
                            style = OrbitalDesignTokens.Typography.HeaderArtist,
                            color = OrbitalDesignTokens.Colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    IconButton(onClick = { showLyrics = !showLyrics }) {
                        Icon(
                            imageVector = if (showLyrics) Icons.Rounded.Album else Icons.Rounded.Lyrics,
                            contentDescription = localText(if (showLyrics) "Artwork player" else "Lyrics"),
                            tint = if (showLyrics || lyricsState.lyrics?.hasContent == true) {
                                OrbitalDesignTokens.Colors.IconAccent
                            } else {
                                OrbitalDesignTokens.Colors.IconPrimary.copy(alpha = 0.78f)
                            },
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    IconButton(onClick = { onToggleFavorite(track) }) {
                        Icon(
                            imageVector = if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            contentDescription = localText(if (isFavorite) "Remove from Favorites" else "Add to Favorites"),
                            tint = if (isFavorite) {
                                OrbitalDesignTokens.Colors.FavoriteActive
                            } else {
                                OrbitalDesignTokens.Colors.IconPrimary.copy(alpha = 0.88f)
                            },
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }

                if (!state.playbackError.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = OrbitalDesignTokens.Spacing.ScreenHorizontal)
                            .clip(RoundedCornerShape(14.dp))
                            .background(tokens.errorContainer)
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = state.playbackError,
                            style = OrbitalDesignTokens.Typography.NextSongMeta,
                            color = tokens.error,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = onDismissError) {
                            Text("Close", color = OrbitalDesignTokens.Colors.TextPrimary)
                        }
                    }
                }

                // Center viewport: seamlessly switches between Circular Artwork Player & Synchronized Lyrics
                AnimatedContent(
                    targetState = showLyrics,
                    transitionSpec = { fadeIn() togetherWith fadeOut() },
                    label = "nowPlayingBody",
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                ) { lyricsVisible ->
                    if (lyricsVisible) {
                        LyricsScreen(
                            lyricsState = lyricsState,
                            positionMs = state.positionMs,
                            positionMsFlow = positionMsFlow,
                            onSeekTo = onSeekTo,
                            onSearchOnline = onSearchOnlineLyrics,
                            onSelectCandidate = onSelectLyricsCandidate,
                            onSaveManualLyrics = onSaveManualLyrics,
                            onImportLyricsFile = onImportLyricsFile,
                            onExportLrcFile = onExportLrcFile,
                            onEmbedIntoAudioFile = onEmbedLyricsIntoFile,
                            onAdjustOffset = onAdjustLyricsOffset,
                            onResetOffset = onResetLyricsOffset,
                            onClearLyrics = onClearLyrics,
                            onDismissNotice = onDismissLyricsNotice,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        BoxWithConstraints(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            val outerTouchFactor = scrubController.config.outerTouchRadiusFraction
                            val discDiameter = minOf(
                                maxWidth * 0.80f,
                                maxWidth / outerTouchFactor,
                                maxHeight / outerTouchFactor,
                                OrbitalDesignTokens.Sizing.NormalDiscMax
                            ).coerceAtLeast(OrbitalDesignTokens.Sizing.NormalDiscMin)
                            val density = LocalDensity.current
                            val bezelRadiusPx = with(density) { (discDiameter / 2f).toPx() }
                            val innerDiscHitFilter = remember(bezelRadiusPx) {
                                { pos: Offset, boxSize: androidx.compose.ui.unit.IntSize ->
                                    val effectiveOuterR = bezelRadiusPx.coerceAtMost(
                                        minOf(boxSize.width, boxSize.height) / 2f
                                    )
                                    CircularScrubEngine.isInInnerArtworkZone(
                                        x = pos.x,
                                        y = pos.y,
                                        centerX = boxSize.width / 2f,
                                        centerY = boxSize.height / 2f,
                                        outerRadiusPx = effectiveOuterR,
                                        innerArtworkRadiusFraction = 0.72f
                                    )
                                }
                            }

                            val openOrbitA11yLabel = localText("Orbital navigation")
                            val artworkGestureModifier = Modifier
                                .semantics {
                                    if (navQueue.isNotEmpty()) {
                                        customActions = listOf(
                                            CustomAccessibilityAction(openOrbitA11yLabel) {
                                                orbitalController.enterOrbit(
                                                    queueSize = latestNavQueue.size,
                                                    currentNavIndex = latestNavIndex,
                                                    accessibleMode = true
                                                )
                                                true
                                            }
                                        )
                                    }
                                }
                                .orbitalArtworkLongPressDrag(
                                    enabled = navQueue.isNotEmpty() &&
                                        !orbitalController.session.isAccessibleMode &&
                                        !scrubController.isTracking,
                                    controller = orbitalController,
                                    queueSizeProvider = { latestNavQueue.size },
                                    currentNavIndexProvider = { latestNavIndex },
                                    stepDistancePxProvider = { stepDistancePx },
                                    onCommitNavIndex = ::commitNavIndex,
                                    hitZoneFilter = innerDiscHitFilter
                                )

                            CircularAlbumArtwork(
                                trackUri = track.uri,
                                albumId = track.albumId,
                                isPlaying = state.isPlaying,
                                positionMs = state.positionMs,
                                positionMsFlow = positionMsFlow,
                                durationMs = state.durationMs,
                                isSeekable = state.isSeekable,
                                bezelDiameter = discDiameter,
                                scrubController = scrubController,
                                holdFeedbackProgress = orbitalController.holdProgress,
                                interactiveSeeking = !orbitalController.isOrbitVisible &&
                                    orbitalController.holdProgress <= 0.005f,
                                onCommitSeek = onSeekTo,
                                artworkTouchModifier = artworkGestureModifier,
                                loadArtwork = loadArtwork
                            )
                        }
                    }
                }

                // Restrained 5-icon playback controls with large solid white circular Play/Pause orb
                PlayerControls(
                    isPlaying = state.isPlaying,
                    shuffleEnabled = state.shuffleEnabled,
                    repeatMode = state.repeatMode,
                    onToggleShuffle = onToggleShuffle,
                    onSkipPrevious = onSkipPrevious,
                    onTogglePlayPause = onTogglePlayPause,
                    onSkipNext = onSkipNext,
                    onCycleRepeat = onCycleRepeat,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = if (showLyrics) 10.dp else 0.dp)
                )

                if (!showLyrics) {
                    Spacer(Modifier.height(if (compactScreen) 12.dp else 20.dp))

                    // Compact Next Songs preview section anchored cleanly above navigation bar (Reference 1)
                    NextSongsPreviewSection(
                        upcoming = upcoming,
                        queueCount = state.queue.size,
                        audioInfoSummary = if (state.audioOutputInfo.isAvailable) state.audioOutputInfo.summaryText else null,
                        compact = compactScreen,
                        onOpenQueue = { showQueueSheet = true },
                        onOpenAudioInspector = { onOpenAudioInspector(track) },
                        onOpenOrbital = {
                            if (navQueue.isNotEmpty()) {
                                orbitalController.enterOrbit(
                                    queueSize = navQueue.size,
                                    currentNavIndex = navCurrentIndex,
                                    accessibleMode = true
                                )
                            }
                        },
                        onSelectUpcomingTrack = { timelineIdx -> onSelectQueueIndex(timelineIdx) },
                        loadArtwork = loadArtwork,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                start = OrbitalDesignTokens.Spacing.ScreenHorizontal,
                                end = OrbitalDesignTokens.Spacing.ScreenHorizontal,
                                bottom = 14.dp
                            )
                    )
                }
            }
        }

        // LAYER 2: Expanded Orbital Song Navigator (Reference 2) morphing smoothly over the Normal Player
        if (orbitalController.isOrbitVisible && navQueue.isNotEmpty()) {
            OrbitalNavigator(
                tracks = navQueue,
                shuffleEnabled = state.shuffleEnabled,
                controller = orbitalController,
                onCommitNavIndex = ::commitNavIndex,
                onDismiss = { orbitalController.cancelOrbit() },
                loadArtwork = loadArtwork,
                modifier = Modifier.fillMaxSize()
            )
        }
    }

    if (showQueueSheet) {
        PlaybackQueueSheet(
            state = state,
            onDismiss = { showQueueSheet = false },
            onSelectIndex = onSelectQueueIndex,
            onMoveItem = onMoveQueueItem,
            onRemoveIndex = onRemoveQueueIndex,
            onClearQueue = onClearQueue,
            loadArtwork = loadArtwork
        )
    }

    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            containerColor = OrbitalDesignTokens.Colors.BezelMid,
            title = { Text("Playback speed", color = OrbitalDesignTokens.Colors.TextPrimary) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PLAYBACK_SPEEDS.forEach { spd ->
                        val selected = kotlin.math.abs(state.playbackSpeed - spd) < 0.01f
                        TextButton(
                            onClick = {
                                onSetSpeed(spd)
                                showSpeedDialog = false
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = String.format(Locale.US, "%.2gx", spd),
                                    color = if (selected) {
                                        OrbitalDesignTokens.Colors.IconAccent
                                    } else {
                                        OrbitalDesignTokens.Colors.TextPrimary
                                    }
                                )
                                if (selected) {
                                    Text("Active", color = OrbitalDesignTokens.Colors.IconAccent)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text("Close", color = OrbitalDesignTokens.Colors.IconAccent)
                }
            }
        )
    }
}

/**
 * Compact Next Songs preview anchored cleanly at the bottom of the Normal Player (Reference 1).
 * Never overlaps playback controls and provides accessible entry points to both full Queue
 * and Orbital Navigation.
 */
@Composable
private fun NextSongsPreviewSection(
    upcoming: List<Pair<Int, LibraryTrack>>,
    queueCount: Int,
    audioInfoSummary: String?,
    compact: Boolean,
    onOpenQueue: () -> Unit,
    onOpenAudioInspector: () -> Unit,
    onOpenOrbital: () -> Unit,
    onSelectUpcomingTrack: (Int) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    val openOrbitLabel = localText("Orbital navigation")

    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Next Songs",
                style = OrbitalDesignTokens.Typography.NextSongsHeading,
                color = OrbitalDesignTokens.Colors.TextPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onOpenQueue)
                    .padding(vertical = 4.dp)
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (!audioInfoSummary.isNullOrBlank()) {
                    Text(
                        text = audioInfoSummary,
                        style = OrbitalDesignTokens.Typography.NextSongMeta,
                        color = OrbitalDesignTokens.Colors.TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clip(RoundedCornerShape(8.dp))
                            .clickable(onClick = onOpenAudioInspector)
                            .padding(horizontal = 6.dp, vertical = 3.dp)
                    )
                }
                if (queueCount > 0) {
                    IconButton(
                        onClick = onOpenOrbital,
                        modifier = Modifier
                            .size(48.dp)
                            .semantics {
                                role = Role.Button
                            }
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.TrackChanges,
                            contentDescription = openOrbitLabel,
                            tint = OrbitalDesignTokens.Colors.TextSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(if (compact) 6.dp else 10.dp))

        if (upcoming.isEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(onClick = onOpenQueue)
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = if (queueCount <= 1) "End of queue" else "Playing queue ($queueCount)",
                    style = OrbitalDesignTokens.Typography.NextSongMeta,
                    color = OrbitalDesignTokens.Colors.TextSecondary
                )
                Text(
                    text = "Open queue",
                    style = OrbitalDesignTokens.Typography.NextSongMeta,
                    color = OrbitalDesignTokens.Colors.IconAccent
                )
            }
        } else {
            val previewItems = upcoming.take(1)
            previewItems.forEach { (timelineIdx, nextTrack) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectUpcomingTrack(timelineIdx) }
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackArtworkBox(
                        uri = nextTrack.uri,
                        albumId = nextTrack.albumId,
                        highRes = false,
                        cornerRadius = OrbitalDesignTokens.Sizing.NextSongCorner,
                        iconSize = 20.dp,
                        showBorder = false,
                        modifier = Modifier.size(OrbitalDesignTokens.Sizing.NextSongArtwork),
                        loadArtwork = loadArtwork
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = nextTrack.title,
                            style = OrbitalDesignTokens.Typography.NextSongTitle,
                            color = OrbitalDesignTokens.Colors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = nextTrack.artist,
                            style = OrbitalDesignTokens.Typography.NextSongMeta,
                            color = OrbitalDesignTokens.Colors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = TextUtils.formatDurationMs(nextTrack.durationMs),
                        style = OrbitalDesignTokens.Typography.NextSongMeta,
                        color = OrbitalDesignTokens.Colors.TextSecondary
                    )
                }
            }
        }
    }
}

package com.example.mdownload.ui.screens

import android.graphics.Bitmap
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.mdownload.HomeUiState
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.DownloadPreset
import com.example.mdownload.core.EngineState
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.YouTubeUrlParser
import com.example.mdownload.player.library.LibraryGroupingHelper
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.HiFiArtworkTile
import com.example.mdownload.ui.components.HiFiContinueListeningCard
import com.example.mdownload.ui.components.HiFiGlassChip
import com.example.mdownload.ui.components.HiFiGlassPanel
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.components.HiFiSectionHeading
import com.example.mdownload.ui.components.HiFiTopChrome
import com.example.mdownload.ui.components.HiFiTrackRow
import com.example.mdownload.ui.components.SmokedGlassSurface
import com.example.mdownload.ui.components.StatusPill
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.flow.StateFlow

/**
 * Music-first Home Hub combining:
 * - Continue listening spotlight
 * - Quick audio URL download capsule + format presets + 1-tap YouTube Search
 * - Recently added albums carousel
 * - Recently downloaded / local tracks (borderless reference rows, instant playback)
 */
@Composable
fun HomeScreen(
    homeState: HomeUiState,
    engineState: EngineState,
    settings: AppSettings,
    jobs: List<DownloadJob>,
    isQueueRunning: Boolean,
    totalSpeed: Double,
    libraryTracks: List<LibraryTrack>,
    playbackState: PlaybackUiState,
    bottomContentPadding: Dp = HiFiSpacing.dockWithMiniPlayerClearance,
    onUrlChange: (String) -> Unit,
    onClearUrl: () -> Unit,
    onApplyPreset: (DownloadPreset) -> Unit,
    onOpenMetadataEditor: () -> Unit,
    onSubmit: (onlySingleVideo: Boolean, autoStart: Boolean) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onPlayTrackInQueue: (LibraryTrack, List<LibraryTrack>) -> Unit,
    onPlayQueue: (List<LibraryTrack>, Boolean) -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    playbackPositionMsFlow: StateFlow<Long>? = null
) {
    val tokens = HiFiTheme.colors
    val clipboard = LocalClipboardManager.current

    val extractedUrls = remember(homeState.urlInput) {
        YouTubeUrlParser.extractUrlsFromText(homeState.urlInput)
    }
    val singleNormalized = remember(extractedUrls) {
        if (extractedUrls.size == 1) {
            runCatching { YouTubeUrlParser.normalizeUrl(extractedUrls.first()) }.getOrNull()
        } else null
    }
    val isSinglePlaylist = remember(singleNormalized, homeState.preview) {
        (singleNormalized != null && YouTubeUrlParser.isPlaylist(singleNormalized)) ||
            (singleNormalized != null &&
                homeState.preview?.normalizedUrl == singleNormalized &&
                homeState.preview.isPlaylist)
    }
    val hasSingleVideoInPlaylist = remember(singleNormalized, isSinglePlaylist) {
        isSinglePlaylist && singleNormalized != null && YouTubeUrlParser.singleVideoUrl(singleNormalized) != null
    }
    val detectedSource = remember(singleNormalized) {
        singleNormalized?.let { YouTubeUrlParser.sourceName(it) }
    }

    val recentTracks = remember(libraryTracks) {
        LibraryGroupingHelper.recentlyAddedTracks(libraryTracks, limit = 8)
    }
    val recentAlbums = remember(libraryTracks) {
        LibraryGroupingHelper.groupAlbums(libraryTracks).take(8)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HiFiTopChrome(
            title = "HiFiDeck",
            trailing = {
                if (isQueueRunning && totalSpeed > 0.0) {
                    StatusPill(
                        text = TextUtils.formatSpeed(totalSpeed),
                        backgroundColor = tokens.accentSoft,
                        contentColor = tokens.accentBright
                    )
                } else {
                    when (engineState) {
                        is EngineState.Initializing -> StatusPill("Starting…", tokens.glassElevated, tokens.inkSecondary)
                        is EngineState.Updating -> StatusPill("Updating…", tokens.accentSoft, tokens.accentBright)
                        is EngineState.Ready -> StatusPill("yt-dlp ready", tokens.successContainer, tokens.success)
                        is EngineState.Error -> StatusPill("Engine error", tokens.errorContainer, tokens.error)
                    }
                }
                IconButton(onClick = onOpenDiscover) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = localText("Discover"),
                        tint = tokens.inkPrimary
                    )
                }
            }
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = bottomContentPadding)
        ) {
            // Rate limit warning banner if active
            if (settings.queueBlockReason.isNotBlank()) {
                item {
                    HiFiGlassPanel(
                        modifier = Modifier.padding(horizontal = HiFiSpacing.lg, vertical = 6.dp),
                        containerColor = tokens.warningContainer
                    ) {
                        Text("YouTube queue held", style = MaterialTheme.typography.titleSmall, color = tokens.warning)
                        Spacer(Modifier.height(4.dp))
                        Text(settings.queueBlockReason, style = MaterialTheme.typography.bodySmall, color = tokens.warning)
                    }
                }
            }

            // Continue Listening Hero Card
            playbackState.currentTrack?.let { activeTrack ->
                item(key = "continue_listening", contentType = "continue_listening") {
                    val dur = playbackState.durationMs.coerceAtLeast(1L)
                    val prog = (playbackState.positionMs.toFloat() / dur.toFloat()).coerceIn(0f, 1f)
                    Box(modifier = Modifier.padding(horizontal = HiFiSpacing.lg, vertical = 6.dp)) {
                        HiFiContinueListeningCard(
                            track = activeTrack,
                            isPlaying = playbackState.isPlaying,
                            progress = prog,
                            onOpenNowPlaying = onOpenNowPlaying,
                            onTogglePlayPause = onTogglePlayPause,
                            loadArtwork = loadArtwork,
                            positionMsFlow = playbackPositionMsFlow,
                            durationMs = playbackState.durationMs
                        )
                    }
                }
            }

            // Quick Audio Link / Download Composer Capsule
            item {
                Spacer(Modifier.height(HiFiSpacing.xs))
                HiFiGlassPanel(
                    modifier = Modifier.padding(horizontal = HiFiSpacing.lg),
                    containerColor = tokens.glassSurface
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Add audio link",
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.inkPrimary
                            )
                            Text(
                                text = "Paste URL, Share → HiFiDeck, or search YouTube",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                        }
                        IconButton(
                            onClick = {
                                val pasted = clipboard.getText()?.text?.trim().orEmpty()
                                if (pasted.isNotEmpty()) onUrlChange(pasted)
                            },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ContentPaste,
                                contentDescription = localText("Paste link"),
                                tint = tokens.accent
                            )
                        }
                    }

                    val validCount = remember(extractedUrls) {
                        extractedUrls.count { YouTubeUrlParser.isValidUrl(it) }
                    }
                    if (!detectedSource.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill(detectedSource, tokens.accentSoft, tokens.accentBright)
                    } else if (validCount > 1) {
                        Spacer(Modifier.height(6.dp))
                        StatusPill("$validCount links detected", tokens.accentSoft, tokens.accentBright)
                    }

                    Spacer(Modifier.height(10.dp))

                    HiFiGlassTextField(
                        value = homeState.urlInput,
                        onValueChange = onUrlChange,
                        placeholder = "https://music.youtube.com/watch?v=…",
                        contentDescription = localText("Audio or YouTube URL"),
                        leadingIcon = Icons.Rounded.Link,
                        singleLine = false,
                        minLines = 1,
                        maxLines = 3,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            if (homeState.urlInput.isNotBlank()) onSubmit(false, !isSinglePlaylist)
                        },
                        onClear = onClearUrl
                    )

                    // Live non-blocking metadata preview
                    if (homeState.isInspecting) {
                        Spacer(Modifier.height(10.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(vertical = 4.dp)
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = tokens.accent
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = "Inspecting source metadata…",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                        }
                    } else if (homeState.preview != null) {
                        val preview = homeState.preview
                        val effectiveTitle = homeState.pendingMetadata.title.ifBlank { preview.title }
                        val effectiveArtist = homeState.pendingMetadata.artist.ifBlank { preview.artist }
                        val effectiveCover: Any? = remember(homeState.pendingMetadata.customArtworkUri, preview.thumbnailUrl) {
                            val custom = homeState.pendingMetadata.customArtworkUri
                            when {
                                custom.isNotBlank() -> java.io.File(custom).takeIf { it.isFile } ?: custom
                                preview.thumbnailUrl.isNotBlank() -> preview.thumbnailUrl
                                else -> null
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        SmokedGlassSurface(
                            shape = HiFiShapes.input,
                            containerColor = tokens.glassElevated
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(width = 60.dp, height = 44.dp)
                                        .clip(RoundedCornerShape(10.dp))
                                        .background(tokens.accentSoft),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (effectiveCover != null) {
                                        AsyncImage(
                                            model = effectiveCover,
                                            contentDescription = localText("Artwork"),
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = tokens.accent)
                                    }
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = effectiveTitle,
                                        style = MaterialTheme.typography.titleSmall,
                                        color = tokens.inkPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = listOfNotNull(
                                            effectiveArtist.takeIf { it.isNotBlank() },
                                            preview.durationSec?.let { TextUtils.formatDurationSeconds(it) }
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (!preview.isPlaylist || preview.singleVideoUrl != null) {
                                    IconButton(onClick = onOpenMetadataEditor, modifier = Modifier.size(48.dp)) {
                                        Icon(
                                            Icons.Rounded.Edit,
                                            contentDescription = localText("Edit metadata"),
                                            tint = tokens.accent,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    } else if (!homeState.previewError.isNullOrBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            text = "Preview unavailable: ${homeState.previewError}",
                            style = MaterialTheme.typography.bodySmall,
                            color = tokens.inkSecondary
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Action pills
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HiFiPillActionButton(
                            label = if (isSinglePlaylist) "Choose tracks…" else "Download now",
                            icon = if (isSinglePlaylist) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.Rounded.Download,
                            onClick = { onSubmit(false, !isSinglePlaylist) },
                            enabled = homeState.urlInput.isNotBlank(),
                            primary = true,
                            modifier = Modifier.weight(1f)
                        )
                        if (!isSinglePlaylist) {
                            HiFiPillActionButton(
                                label = "Queue",
                                icon = Icons.Rounded.Add,
                                onClick = { onSubmit(false, false) },
                                enabled = homeState.urlInput.isNotBlank(),
                                primary = false
                            )
                        }
                    }

                    if (hasSingleVideoInPlaylist) {
                        Spacer(Modifier.height(8.dp))
                        HiFiPillActionButton(
                            label = "Only this video (ignore playlist)",
                            onClick = { onSubmit(true, true) },
                            primary = false,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Spacer(Modifier.height(12.dp))

                    // Compact inline output profile pills
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(DownloadPreset.entries) { preset ->
                            val selected = settings.audioFormat == preset.format &&
                                (preset.format.bitrates.isEmpty() || settings.audioQuality == preset.quality) &&
                                settings.asciiNames == preset.asciiNames
                            HiFiGlassChip(
                                label = "${preset.label} · ${preset.subtitle}",
                                selected = selected,
                                onClick = { onApplyPreset(preset) }
                            )
                        }
                    }
                }
            }

            // Active Download Queue compact strip
            if (jobs.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(HiFiSpacing.sm))
                    HiFiGlassPanel(
                        modifier = Modifier
                            .padding(horizontal = HiFiSpacing.lg)
                            .clickable(onClick = onOpenDownloads),
                        contentPadding = 14.dp
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                                contentDescription = null,
                                tint = tokens.accent
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (isQueueRunning) "Downloading ${jobs.size} track(s)" else "${jobs.size} track(s) waiting in queue",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = tokens.inkPrimary
                                )
                                Text(
                                    text = jobs.first().title,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.inkSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            TextButton(onClick = onOpenDownloads) {
                                Text("Open queue", color = tokens.accent)
                            }
                        }
                    }
                }
            }

            // Recently Added Albums Carousel
            if (recentAlbums.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(HiFiSpacing.lg))
                    HiFiSectionHeading(
                        title = "Albums",
                        actionLabel = "See all",
                        onAction = onOpenLibrary,
                        modifier = Modifier.padding(horizontal = HiFiSpacing.lg)
                    )
                    Spacer(Modifier.height(HiFiSpacing.sm))
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = HiFiSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        items(recentAlbums, key = { it.id }) { album ->
                            HiFiArtworkTile(
                                title = album.title,
                                subtitle = album.artist,
                                artworkUri = album.coverTrackUri,
                                albumId = album.albumId,
                                tileWidth = 136.dp,
                                onClick = { onPlayQueue(album.tracks, false) },
                                loadArtwork = loadArtwork
                            )
                        }
                    }
                }
            }

            // Recently Downloaded / Local Tracks (borderless reference track list)
            if (recentTracks.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(HiFiSpacing.lg))
                    HiFiSectionHeading(
                        title = "Recently Added",
                        actionLabel = "Library",
                        onAction = onOpenLibrary,
                        modifier = Modifier.padding(horizontal = HiFiSpacing.lg)
                    )
                    Spacer(Modifier.height(HiFiSpacing.xs))
                }
                items(recentTracks, key = { it.uri }) { track ->
                    val isCurrent = playbackState.currentTrack?.uri == track.uri
                    HiFiTrackRow(
                        track = track,
                        isCurrent = isCurrent,
                        isPlaying = isCurrent && playbackState.isPlaying,
                        onClick = { onPlayTrackInQueue(track, recentTracks) },
                        loadArtwork = loadArtwork
                    )
                }
            }
        }
    }
}

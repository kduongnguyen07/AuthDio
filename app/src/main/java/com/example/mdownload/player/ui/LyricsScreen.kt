package com.example.mdownload.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CenterFocusStrong
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FileOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.TextUtils
import com.example.mdownload.player.lyrics.LyricsCandidate
import com.example.mdownload.player.lyrics.LyricsSyncEngine
import com.example.mdownload.player.lyrics.LyricsUiState
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.localText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Full dark-cinematic Lyrics presentation & management surface integrated into [NowPlayingScreen].
 *
 * - Synchronized mode follows Media3's live [positionMs] strictly via [LyricsSyncEngine] with zero
 *   independent drifting timer.
 * - Detects manual scrolling and pauses auto-centering while presenting a subtle floating
 *   "Current line" return pill.
 * - Supports plain lyrics, empty states, live sync offset fine-tuning, LRCLIB search/candidate
 *   picker, `.lrc`/`.txt` SAF import, raw editor, `.lrc` SAF export, and safe file tag embedding.
 */
@Composable
fun LyricsScreen(
    lyricsState: LyricsUiState,
    positionMs: Long,
    onSeekTo: (Long) -> Unit,
    onSearchOnline: (title: String?, artist: String?) -> Unit,
    onSelectCandidate: (LyricsCandidate) -> Unit,
    onSaveManualLyrics: (rawText: String) -> Unit,
    onImportLyricsFile: () -> Unit,
    onExportLrcFile: () -> Unit,
    onEmbedIntoAudioFile: () -> Unit,
    onAdjustOffset: (Long) -> Unit,
    onResetOffset: () -> Unit,
    onClearLyrics: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier,
    positionMsFlow: StateFlow<Long>? = null
) {
    val livePositionMs = positionMsFlow?.collectAsState()?.value ?: positionMs
    val lyrics = lyricsState.lyrics
    val track = lyricsState.activeTrack
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var showOffsetBar by remember { mutableStateOf(false) }
    var showSearchDialog by remember { mutableStateOf(false) }
    var showEditorDialog by remember { mutableStateOf(false) }

    var cachedSyncIndex by remember(lyrics?.trackKey, lyrics?.updatedAtMs) { mutableIntStateOf(-1) }
    val syncSnapshot = remember(lyrics, livePositionMs, cachedSyncIndex) {
        LyricsSyncEngine.resolve(
            lyrics = lyrics,
            positionMs = livePositionMs,
            cachedIndex = cachedSyncIndex
        )
    }
    LaunchedEffect(syncSnapshot.activeLineIndex) {
        cachedSyncIndex = syncSnapshot.activeLineIndex
    }

    // Track manual scrolling so the user can freely read ahead/behind without fighting auto-scroll
    var userManuallyScrolled by remember(lyrics?.trackKey) { mutableStateOf(false) }
    var programmaticScrolling by remember { mutableStateOf(false) }

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { inProgress ->
                if (inProgress && !programmaticScrolling) {
                    userManuallyScrolled = true
                }
            }
    }

    // Smoothly auto-center the active synchronized line when not manually overridden
    LaunchedEffect(syncSnapshot.activeLineIndex, userManuallyScrolled, lyrics?.isSynced) {
        if (lyrics == null || !lyrics.isSynced || userManuallyScrolled) return@LaunchedEffect
        val targetIdx = syncSnapshot.activeLineIndex
        if (targetIdx < 0 || targetIdx >= lyrics.lines.size) return@LaunchedEffect
        val viewportHeight = listState.layoutInfo.viewportSize.height
        val centerOffset = if (viewportHeight > 0) -(viewportHeight * 0.28f).toInt() else -180
        programmaticScrolling = true
        try {
            listState.animateScrollToItem(index = targetIdx, scrollOffset = centerOffset)
        } finally {
            delay(80L)
            programmaticScrolling = false
        }
    }

    Column(
        modifier = modifier.fillMaxSize()
    ) {
        // Subtle compact utility action strip (Search / Offset / Edit / Import / Export / Embed)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = OrbitalDesignTokens.Spacing.ScreenHorizontal, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LyricsPillChip(
                icon = Icons.Rounded.Search,
                label = if (lyricsState.candidates.size > 1) {
                    "Matches (${lyricsState.candidates.size})"
                } else {
                    "Search lyrics"
                },
                active = showSearchDialog || lyricsState.isSearchingOnline,
                onClick = {
                    showSearchDialog = true
                    if (lyricsState.candidates.isEmpty() && !lyricsState.isSearchingOnline) {
                        onSearchOnline(null, null)
                    }
                }
            )
            if (lyrics != null && lyrics.isSynced) {
                val offsetLabel = if (lyrics.offsetMs == 0L) {
                    "Timing"
                } else {
                    val sign = if (lyrics.offsetMs > 0L) "+" else ""
                    "Timing ${sign}${lyrics.offsetMs}ms"
                }
                LyricsPillChip(
                    icon = Icons.Rounded.Timer,
                    label = offsetLabel,
                    active = showOffsetBar || lyrics.offsetMs != 0L,
                    onClick = { showOffsetBar = !showOffsetBar }
                )
            }
            LyricsPillChip(
                icon = Icons.Rounded.Edit,
                label = if (lyrics != null) "Edit" else "Paste",
                active = false,
                onClick = { showEditorDialog = true }
            )
            LyricsPillChip(
                icon = Icons.Rounded.FileOpen,
                label = "Import .lrc",
                active = false,
                onClick = onImportLyricsFile
            )
            if (lyrics != null && lyrics.hasContent) {
                LyricsPillChip(
                    icon = Icons.Rounded.Download,
                    label = "Export .lrc",
                    active = false,
                    onClick = onExportLrcFile
                )
                if (lyricsState.canEmbedIntoFile) {
                    LyricsPillChip(
                        icon = Icons.Rounded.LibraryMusic,
                        label = if (lyricsState.isEmbedding) "Embedding…" else "Embed tag",
                        active = lyricsState.isEmbedding,
                        enabled = !lyricsState.isEmbedding,
                        onClick = onEmbedIntoAudioFile
                    )
                }
            }
        }

        // Inline live sync offset adjustment bar
        AnimatedVisibility(visible = showOffsetBar && lyrics != null && lyrics.isSynced) {
            val offsetMs = lyrics?.offsetMs ?: 0L
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitalDesignTokens.Spacing.ScreenHorizontal, vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.88f))
                    .border(0.8.dp, OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                OffsetStepButton("-0.5s") { onAdjustOffset(-500L) }
                OffsetStepButton("-0.1s") { onAdjustOffset(-100L) }
                Text(
                    text = "${if (offsetMs > 0L) "+" else ""}${offsetMs}ms",
                    style = OrbitalDesignTokens.Typography.ProgressTime,
                    color = OrbitalDesignTokens.Colors.IconAccent,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(onClick = onResetOffset)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
                OffsetStepButton("+0.1s") { onAdjustOffset(100L) }
                OffsetStepButton("+0.5s") { onAdjustOffset(500L) }
            }
        }

        // Subtle status or error notice banner
        val notice = lyricsState.errorMessage ?: lyricsState.statusMessage
        val isError = lyricsState.errorMessage != null
        AnimatedVisibility(visible = !notice.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = OrbitalDesignTokens.Spacing.ScreenHorizontal, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isError) Color(0x38FF5F7A) else OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.82f)
                    )
                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = notice.orEmpty(),
                    style = OrbitalDesignTokens.Typography.NextSongMeta,
                    color = if (isError) Color(0xFFFFB4C2) else OrbitalDesignTokens.Colors.TextPrimary,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                IconButton(onClick = onDismissNotice, modifier = Modifier.size(48.dp)) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = localText("Dismiss"),
                        tint = OrbitalDesignTokens.Colors.TextSecondary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }

        // Main Lyrics viewport
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                lyricsState.isLoading && lyrics == null -> {
                    Column(
                        modifier = Modifier.align(Alignment.Center),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = OrbitalDesignTokens.Colors.IconAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(28.dp)
                        )
                        Text(
                            text = if (lyricsState.isSearchingOnline) {
                                "Searching lyrics…"
                            } else {
                                "Loading lyrics…"
                            },
                            style = OrbitalDesignTokens.Typography.NextSongMeta,
                            color = OrbitalDesignTokens.Colors.TextSecondary
                        )
                    }
                }

                lyrics == null || !lyrics.hasContent -> {
                    LyricsEmptyState(
                        candidateCount = lyricsState.candidates.size,
                        isSearching = lyricsState.isSearchingOnline,
                        onSearchOnline = {
                            showSearchDialog = true
                            onSearchOnline(null, null)
                        },
                        onImportFile = onImportLyricsFile,
                        onOpenEditor = { showEditorDialog = true },
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = OrbitalDesignTokens.Spacing.ScreenHorizontal)
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(
                            start = 16.dp,
                            end = 16.dp,
                            top = 36.dp,
                            bottom = 72.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
                            .drawWithContent {
                                drawContent()
                                // Soft cinematic top & bottom fade into dark void
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        0.0f to Color.Transparent,
                                        0.09f to Color.Black,
                                        0.90f to Color.Black,
                                        1.0f to Color.Transparent
                                    ),
                                    blendMode = BlendMode.DstIn
                                )
                            }
                    ) {
                        // Subtle source & sync attribution pill at top of scroll list
                        item(key = "lyrics-source-header") {
                            val badge = buildString {
                                append(if (lyrics.isSynced) "Synchronized" else "Plain lyrics")
                                append(" · ")
                                append(
                                    lyrics.providerName.ifBlank { lyrics.source.displayName }
                                )
                            }
                            Text(
                                text = badge,
                                style = OrbitalDesignTokens.Typography.OrbitalCaption,
                                color = OrbitalDesignTokens.Colors.TextMuted,
                                modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)
                            )
                        }

                        if (lyrics.isSynced && syncSnapshot.isBeforeFirstLine) {
                            item(key = "lyrics-intro-cue") {
                                Text(
                                    text = "Instrumental intro…",
                                    style = OrbitalDesignTokens.Typography.NextSongMeta,
                                    color = OrbitalDesignTokens.Colors.IconAccent.copy(alpha = 0.78f),
                                    modifier = Modifier.padding(start = 12.dp, bottom = 8.dp)
                                )
                            }
                        }

                        itemsIndexed(
                            items = lyrics.lines,
                            key = { idx, line -> "${idx}_${line.timestampMs ?: -1L}_${line.text.hashCode()}" }
                        ) { index, line ->
                            val isActive = lyrics.isSynced && index == syncSnapshot.activeLineIndex
                            val isPast = lyrics.isSynced &&
                                syncSnapshot.activeLineIndex >= 0 &&
                                index < syncSnapshot.activeLineIndex

                            LyricsLineRow(
                                line = line,
                                isActive = isActive,
                                isPast = isPast,
                                isSynced = lyrics.isSynced,
                                onTapLine = if (lyrics.isSynced && line.timestampMs != null) {
                                    {
                                        LyricsSyncEngine.seekPositionForLine(line, lyrics.offsetMs)
                                            ?.let { seekMs ->
                                                userManuallyScrolled = false
                                                onSeekTo(seekMs)
                                            }
                                    }
                                } else null
                            )
                        }
                    }

                    // Floating "Current line" pill whenever the user manually scrolls away
                    androidx.compose.animation.AnimatedVisibility(
                        visible = lyrics.isSynced && userManuallyScrolled && syncSnapshot.activeLineIndex >= 0,
                        enter = fadeIn(),
                        exit = fadeOut(),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(end = 20.dp, bottom = 14.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .clip(CircleShape)
                                .background(OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.94f))
                                .border(
                                    0.9.dp,
                                    OrbitalDesignTokens.Colors.IconAccent.copy(alpha = 0.55f),
                                    CircleShape
                                )
                                .clickable {
                                    userManuallyScrolled = false
                                    val idx = syncSnapshot.activeLineIndex
                                    if (idx >= 0) {
                                        scope.launch {
                                            val vh = listState.layoutInfo.viewportSize.height
                                            val off = if (vh > 0) -(vh * 0.28f).toInt() else -180
                                            programmaticScrolling = true
                                            try {
                                                listState.animateScrollToItem(idx, off)
                                            } finally {
                                                delay(80L)
                                                programmaticScrolling = false
                                            }
                                        }
                                    }
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.CenterFocusStrong,
                                contentDescription = null,
                                tint = OrbitalDesignTokens.Colors.IconAccent,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = "Current line",
                                style = OrbitalDesignTokens.Typography.NextSongMeta,
                                color = OrbitalDesignTokens.Colors.TextPrimary
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSearchDialog && track != null) {
        LyricsSearchDialog(
            initialTitle = track.title,
            initialArtist = track.artist,
            candidates = lyricsState.candidates,
            isSearching = lyricsState.isSearchingOnline,
            onSearch = { qTitle, qArtist -> onSearchOnline(qTitle, qArtist) },
            onSelect = { candidate ->
                onSelectCandidate(candidate)
                showSearchDialog = false
            },
            onDismiss = { showSearchDialog = false }
        )
    }

    if (showEditorDialog && track != null) {
        LyricsEditorDialog(
            initialText = lyrics?.toExportableLrc().orEmpty(),
            hasExistingLyrics = lyrics != null && lyrics.hasContent,
            onSave = { raw ->
                onSaveManualLyrics(raw)
                showEditorDialog = false
            },
            onClear = {
                onClearLyrics()
                showEditorDialog = false
            },
            onDismiss = { showEditorDialog = false }
        )
    }
}

@Composable
private fun LyricsPillChip(
    icon: ImageVector,
    label: String,
    active: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val bg = if (active) {
        OrbitalDesignTokens.Colors.ProgressArcGlow.copy(alpha = 0.26f)
    } else {
        OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.78f)
    }
    val border = if (active) {
        OrbitalDesignTokens.Colors.IconAccent.copy(alpha = 0.62f)
    } else {
        OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.36f)
    }
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(bg)
            .border(0.8.dp, border, CircleShape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (active) OrbitalDesignTokens.Colors.IconAccent else OrbitalDesignTokens.Colors.TextSecondary,
            modifier = Modifier.size(14.dp)
        )
        Text(
            text = label,
            style = OrbitalDesignTokens.Typography.NextSongMeta,
            color = if (active) OrbitalDesignTokens.Colors.TextPrimary else OrbitalDesignTokens.Colors.TextSecondary
        )
    }
}

@Composable
private fun OffsetStepButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = OrbitalDesignTokens.Typography.NextSongMeta,
            color = OrbitalDesignTokens.Colors.TextPrimary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(OrbitalDesignTokens.Colors.BezelOuter.copy(alpha = 0.9f))
                .padding(horizontal = 9.dp, vertical = 5.dp)
        )
    }
}

@Composable
private fun LyricsEmptyState(
    candidateCount: Int,
    isSearching: Boolean,
    onSearchOnline: () -> Unit,
    onImportFile: () -> Unit,
    onOpenEditor: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.65f))
            .border(0.9.dp, OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.32f), RoundedCornerShape(22.dp))
            .padding(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.AutoAwesome,
            contentDescription = null,
            tint = OrbitalDesignTokens.Colors.IconAccent,
            modifier = Modifier.size(30.dp)
        )
        Text(
            text = "No lyrics loaded yet",
            style = OrbitalDesignTokens.Typography.NextSongsHeading,
            color = OrbitalDesignTokens.Colors.TextPrimary
        )
        Text(
            text = "Search LRCLIB online, import a .lrc file, or paste lyrics manually.",
            style = OrbitalDesignTokens.Typography.NextSongMeta,
            color = OrbitalDesignTokens.Colors.TextSecondary
        )
        Spacer(Modifier.height(2.dp))
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            LyricsPillChip(
                icon = Icons.Rounded.Search,
                label = if (candidateCount > 0) "Choose match ($candidateCount)" else "Search online",
                active = true,
                enabled = !isSearching,
                onClick = onSearchOnline
            )
            LyricsPillChip(
                icon = Icons.Rounded.FileOpen,
                label = "Import .lrc",
                active = false,
                onClick = onImportFile
            )
        }
        TextButton(onClick = onOpenEditor) {
            Text(
                text = "Paste or edit lyrics",
                color = OrbitalDesignTokens.Colors.IconAccent
            )
        }
    }
}

@Composable
private fun LyricsSearchDialog(
    initialTitle: String,
    initialArtist: String,
    candidates: List<LyricsCandidate>,
    isSearching: Boolean,
    onSearch: (title: String, artist: String) -> Unit,
    onSelect: (LyricsCandidate) -> Unit,
    onDismiss: () -> Unit
) {
    var titleQuery by remember(initialTitle) { mutableStateOf(initialTitle) }
    var artistQuery by remember(initialArtist) { mutableStateOf(initialArtist) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = OrbitalDesignTokens.Colors.TextPrimary,
        unfocusedTextColor = OrbitalDesignTokens.Colors.TextPrimary,
        focusedBorderColor = OrbitalDesignTokens.Colors.IconAccent,
        unfocusedBorderColor = OrbitalDesignTokens.Colors.BezelRimSoft,
        focusedLabelColor = OrbitalDesignTokens.Colors.IconAccent,
        unfocusedLabelColor = OrbitalDesignTokens.Colors.TextSecondary
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitalDesignTokens.Colors.BezelMid,
        title = { Text("Search lyrics (LRCLIB)", color = OrbitalDesignTokens.Colors.TextPrimary) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = titleQuery,
                    onValueChange = { titleQuery = it },
                    label = { Text("Title") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artistQuery,
                    onValueChange = { artistQuery = it },
                    label = { Text("Artist") },
                    singleLine = true,
                    colors = fieldColors,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    LyricsPillChip(
                        icon = Icons.Rounded.Search,
                        label = if (isSearching) "Searching…" else "Search",
                        active = true,
                        enabled = !isSearching && titleQuery.isNotBlank(),
                        onClick = { onSearch(titleQuery.trim(), artistQuery.trim()) }
                    )
                }

                if (isSearching) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            color = OrbitalDesignTokens.Colors.IconAccent,
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                } else if (candidates.isEmpty()) {
                    Text(
                        text = "No matching lyrics found. Try simplifying the title or artist.",
                        style = OrbitalDesignTokens.Typography.NextSongMeta,
                        color = OrbitalDesignTokens.Colors.TextSecondary
                    )
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        candidates.take(12).forEach { cand ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(OrbitalDesignTokens.Colors.BezelOuter.copy(alpha = 0.92f))
                                    .clickable { onSelect(cand) }
                                    .padding(12.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = cand.title.ifBlank { titleQuery },
                                        style = OrbitalDesignTokens.Typography.NextSongTitle,
                                        color = OrbitalDesignTokens.Colors.TextPrimary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        text = if (cand.isSynced) "Synced" else "Plain",
                                        style = OrbitalDesignTokens.Typography.OrbitalCaption,
                                        color = if (cand.isSynced) {
                                            OrbitalDesignTokens.Colors.IconAccent
                                        } else {
                                            OrbitalDesignTokens.Colors.TextSecondary
                                        }
                                    )
                                }
                                val meta = buildString {
                                    if (cand.artist.isNotBlank()) append(cand.artist)
                                    if (cand.durationSec != null && cand.durationSec > 0) {
                                        if (isNotEmpty()) append(" · ")
                                        append(TextUtils.formatDurationMs(cand.durationSec * 1000L))
                                    }
                                    if (cand.confidence > 0f) {
                                        if (isNotEmpty()) append(" · ")
                                        append("${(cand.confidence * 100).toInt()}%")
                                    }
                                }
                                if (meta.isNotBlank()) {
                                    Spacer(Modifier.height(2.dp))
                                    Text(
                                        text = meta,
                                        style = OrbitalDesignTokens.Typography.NextSongMeta,
                                        color = OrbitalDesignTokens.Colors.TextSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close", color = OrbitalDesignTokens.Colors.IconAccent)
            }
        }
    )
}

@Composable
private fun LyricsEditorDialog(
    initialText: String,
    hasExistingLyrics: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit
) {
    var draft by remember(initialText) { mutableStateOf(initialText) }
    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedTextColor = OrbitalDesignTokens.Colors.TextPrimary,
        unfocusedTextColor = OrbitalDesignTokens.Colors.TextPrimary,
        focusedBorderColor = OrbitalDesignTokens.Colors.IconAccent,
        unfocusedBorderColor = OrbitalDesignTokens.Colors.BezelRimSoft
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = OrbitalDesignTokens.Colors.BezelMid,
        title = { Text("Edit lyrics", color = OrbitalDesignTokens.Colors.TextPrimary) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "Supports LRC timestamps ([mm:ss.xx]Line) or plain UTF-8 text.",
                    style = OrbitalDesignTokens.Typography.NextSongMeta,
                    color = OrbitalDesignTokens.Colors.TextSecondary
                )
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = {
                        Text(
                            "[00:12.50]First line…",
                            color = OrbitalDesignTokens.Colors.TextMuted
                        )
                    },
                    colors = fieldColors,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 180.dp, max = 300.dp)
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = { onSave(draft) }
            ) {
                Text("Save", color = OrbitalDesignTokens.Colors.IconAccent, fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            Row {
                if (hasExistingLyrics) {
                    TextButton(onClick = onClear) {
                        Text("Clear", color = Color(0xFFFF7B94))
                    }
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = OrbitalDesignTokens.Colors.TextSecondary)
                }
            }
        }
    )
}

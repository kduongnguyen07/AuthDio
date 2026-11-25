package com.example.mdownload.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.mdownload.DiscoverUiState
import com.example.mdownload.core.DiscoverResultItem
import com.example.mdownload.core.DiscoverSearchFilter
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.YouTubeUrlParser
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.HiFiGlassChip
import com.example.mdownload.ui.components.HiFiGlassPanel
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.components.HiFiTopChrome
import com.example.mdownload.ui.components.SmokedGlassSurface
import com.example.mdownload.ui.components.StatusPill
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText

/**
 * Native YouTube music search workspace styled in the Obsidian Orchid smoked-glass language.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiscoverScreen(
    discoverState: DiscoverUiState,
    jobs: List<DownloadJob>,
    downloadedVideoIds: Set<String>,
    skipDuplicates: Boolean,
    bottomContentPadding: Dp = HiFiSpacing.dockWithMiniPlayerClearance,
    onBack: (() -> Unit)? = null,
    onQueryChange: (String) -> Unit,
    onClearQuery: () -> Unit,
    onSearch: (String?) -> Unit,
    onCancelSearch: () -> Unit,
    onSelectFilter: (DiscoverSearchFilter) -> Unit,
    onAddResult: (DiscoverResultItem, Boolean) -> Unit,
    onEditResultMetadata: (DiscoverResultItem) -> Unit
) {
    val tokens = HiFiTheme.colors
    val focusManager = LocalFocusManager.current
    val queuedVideoIds = remember(jobs) {
        jobs.mapNotNull { YouTubeUrlParser.extractVideoId(it.url) }.toSet()
    }

    fun triggerSearch(q: String? = null) {
        focusManager.clearFocus()
        onSearch(q)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HiFiTopChrome(
            title = "Discover",
            subtitle = "Search YouTube music",
            leading = onBack?.let { back ->
                {
                    IconButton(onClick = back, modifier = Modifier.size(38.dp)) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = localText("Back"),
                            tint = tokens.inkPrimary
                        )
                    }
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiFiSpacing.lg)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                HiFiGlassTextField(
                    value = discoverState.query,
                    onValueChange = onQueryChange,
                    placeholder = "Search song, artist or YouTube URL…",
                    imeAction = ImeAction.Search,
                    onImeAction = { triggerSearch() },
                    onClear = onClearQuery,
                    modifier = Modifier.weight(1f)
                )
                HiFiPillActionButton(
                    label = "Search",
                    onClick = { triggerSearch() },
                    enabled = discoverState.query.isNotBlank() && !discoverState.isLoading,
                    primary = true
                )
            }

            if (discoverState.suggestions.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SmokedGlassSurface(
                    shape = HiFiShapes.card,
                    containerColor = tokens.glassElevated
                ) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        discoverState.suggestions.take(5).forEachIndexed { idx, suggestion ->
                            if (idx > 0) {
                                HorizontalDivider(thickness = 0.65.dp, color = tokens.divider)
                            }
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { triggerSearch(suggestion) }
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Rounded.Search,
                                    contentDescription = null,
                                    tint = tokens.inkTertiary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(Modifier.width(10.dp))
                                Text(
                                    text = suggestion,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = tokens.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                DiscoverSearchFilter.entries.forEach { filter ->
                    HiFiGlassChip(
                        label = filter.label,
                        selected = discoverState.filter == filter,
                        onClick = { onSelectFilter(filter) }
                    )
                }
                Spacer(Modifier.weight(1f))
                if (discoverState.results.isNotEmpty() && !discoverState.isLoading) {
                    Text(
                        text = "${discoverState.results.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.inkSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                discoverState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(28.dp),
                                color = tokens.accent,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                text = "Searching YouTube…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = tokens.inkSecondary
                            )
                            Spacer(Modifier.height(8.dp))
                            TextButton(onClick = onCancelSearch) {
                                Text("Cancel", color = tokens.accent)
                            }
                        }
                    }
                }

                discoverState.error != null && discoverState.results.isEmpty() -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(HiFiSpacing.lg),
                        contentAlignment = Alignment.Center
                    ) {
                        HiFiGlassPanel {
                            Text(
                                text = "Search unavailable",
                                style = MaterialTheme.typography.titleMedium,
                                color = tokens.inkPrimary
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = discoverState.error,
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                            Spacer(Modifier.height(12.dp))
                            HiFiPillActionButton(
                                label = "Try again",
                                onClick = { triggerSearch(discoverState.submittedQuery.ifBlank { discoverState.query }) },
                                primary = false
                            )
                        }
                    }
                }

                discoverState.results.isEmpty() -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = HiFiSpacing.lg,
                            end = HiFiSpacing.lg,
                            bottom = bottomContentPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            HiFiGlassPanel {
                                Text(
                                    text = "Search YouTube music",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = tokens.inkPrimary
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = "Type a song, artist, or paste a YouTube link to browse results natively and queue tracks directly.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.inkSecondary
                                )
                            }
                        }

                        if (discoverState.recentQueries.isNotEmpty()) {
                            item {
                                HiFiGlassPanel {
                                    Text(
                                        text = "Recent searches",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = tokens.inkPrimary
                                    )
                                    Spacer(Modifier.height(10.dp))
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        discoverState.recentQueries.forEach { recent ->
                                            HiFiGlassChip(
                                                label = recent,
                                                selected = false,
                                                leadingIcon = Icons.Rounded.History,
                                                onClick = { triggerSearch(recent) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = HiFiSpacing.lg,
                            end = HiFiSpacing.lg,
                            bottom = bottomContentPadding
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(
                            items = discoverState.results,
                            key = { "${if (it.isPlaylist) "pl" else "v"}_${it.id}" }
                        ) { item ->
                            val isQueued = !item.isPlaylist && item.id in queuedVideoIds
                            val isDownloaded = !item.isPlaylist && item.id in downloadedVideoIds
                            DiscoverResultCard(
                                item = item,
                                isQueued = isQueued,
                                isDownloaded = isDownloaded,
                                skipDuplicates = skipDuplicates,
                                onDownloadNow = { onAddResult(item, true) },
                                onAddToQueue = { onAddResult(item, false) },
                                onEditMetadata = { onEditResultMetadata(item) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiscoverResultCard(
    item: DiscoverResultItem,
    isQueued: Boolean,
    isDownloaded: Boolean,
    skipDuplicates: Boolean,
    onDownloadNow: () -> Unit,
    onAddToQueue: () -> Unit,
    onEditMetadata: () -> Unit
) {
    val tokens = HiFiTheme.colors
    HiFiGlassPanel(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top
        ) {
            Box(
                modifier = Modifier
                    .size(width = 104.dp, height = 62.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                if (item.thumbnailUrl.isNotBlank()) {
                    AsyncImage(
                        model = item.thumbnailUrl,
                        contentDescription = localText("Artwork"),
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        imageVector = if (item.isPlaylist) Icons.AutoMirrored.Rounded.PlaylistPlay else Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = tokens.accent
                    )
                }

                if (item.durationText.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(4.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xCC0A0710))
                            .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                        Text(
                            text = item.durationText,
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            maxLines = 1
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                if (item.isPlaylist || isQueued || isDownloaded) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (item.isPlaylist) {
                            StatusPill("Playlist", tokens.accentSoft, tokens.accentBright)
                        }
                        if (isDownloaded) {
                            StatusPill("Downloaded", tokens.successContainer, tokens.success)
                        }
                        if (isQueued) {
                            StatusPill("In queue", tokens.accentSoft, tokens.accentBright)
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                }
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(3.dp))
                val subtitle = buildString {
                    append(item.artist)
                    if (item.metaLine.isNotBlank()) {
                        append(" · ")
                        append(item.metaLine)
                    }
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        val blockedDuplicate = isQueued || (isDownloaded && skipDuplicates)

        if (item.isPlaylist) {
            HiFiPillActionButton(
                label = "Choose tracks…",
                icon = Icons.AutoMirrored.Rounded.PlaylistPlay,
                onClick = onAddToQueue,
                primary = true,
                modifier = Modifier.fillMaxWidth()
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HiFiPillActionButton(
                    label = when {
                        isQueued -> "In queue"
                        isDownloaded && skipDuplicates -> "Downloaded"
                        else -> "Download now"
                    },
                    icon = if (blockedDuplicate) Icons.Rounded.Check else Icons.Rounded.Download,
                    onClick = onDownloadNow,
                    enabled = !blockedDuplicate,
                    primary = true,
                    modifier = Modifier.weight(1f)
                )

                HiFiPillActionButton(
                    label = "Queue",
                    icon = if (blockedDuplicate) Icons.Rounded.Check else Icons.Rounded.Add,
                    onClick = onAddToQueue,
                    enabled = !blockedDuplicate,
                    primary = false
                )

                IconButton(
                    onClick = onEditMetadata,
                    enabled = !blockedDuplicate,
                    modifier = Modifier.size(38.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = localText("Edit metadata"),
                        tint = if (blockedDuplicate) tokens.inkTertiary else tokens.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}

package com.example.mdownload.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.HistoryRecord
import com.example.mdownload.core.JobStage
import com.example.mdownload.core.JobStatus
import com.example.mdownload.core.QueueOrderHelper
import com.example.mdownload.core.TaskFeedback
import com.example.mdownload.core.TextUtils
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.HiFiEmptyState
import com.example.mdownload.ui.components.HiFiGlassChip
import com.example.mdownload.ui.components.HiFiGlassPanel
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.components.HiFiGlowProgressBar
import com.example.mdownload.ui.components.HiFiPillActionButton
import com.example.mdownload.ui.components.HiFiTopChrome
import com.example.mdownload.ui.components.JobStatusPill
import com.example.mdownload.ui.components.SmokedGlassSurface
import com.example.mdownload.ui.components.StatusPill
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText

@Composable
fun DownloadsScreen(
    jobs: List<DownloadJob>,
    history: List<HistoryRecord>,
    settings: AppSettings,
    isQueueRunning: Boolean,
    isQueuePaused: Boolean,
    totalSpeed: Double,
    bottomContentPadding: Dp = HiFiSpacing.dockWithMiniPlayerClearance,
    onOpenDiscover: () -> Unit = {},
    onStartQueue: () -> Unit,
    onPauseQueue: () -> Unit,
    onStopQueue: () -> Unit,
    onRemoveJob: (String) -> Unit,
    onMoveJobUp: (String) -> Unit,
    onMoveJobDown: (String) -> Unit,
    onMoveJobToTop: (String) -> Unit,
    onClearWaiting: () -> Unit,
    onEditJobMetadata: (DownloadJob) -> Unit,
    onRetryHistory: (HistoryRecord) -> Unit,
    onRetryAllFailed: () -> Unit,
    onPlayHistory: (HistoryRecord) -> Unit,
    onAddHistoryToQueue: (HistoryRecord) -> Unit,
    onClearHistory: () -> Unit,
    onSetParallelDownloads: (Int) -> Unit,
    onSetDownloadThreads: (Int) -> Unit
) {
    val tokens = HiFiTheme.colors
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Queue, 1 = History
    val failedCount = remember(history) {
        history.count { it.status == JobStatus.FAILED && !it.isConnectionCheck }
    }

    val subtitleText = when {
        isQueueRunning -> "${TextUtils.formatSpeed(totalSpeed)} · ${jobs.size} active/queued"
        isQueuePaused && jobs.isNotEmpty() -> "Paused · ${jobs.size} waiting"
        else -> "${jobs.size} queued · ${history.size} history"
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HiFiTopChrome(
            title = "Downloads",
            subtitle = subtitleText,
            trailing = {
                IconButton(onClick = onOpenDiscover) {
                    Icon(
                        imageVector = Icons.Rounded.Search,
                        contentDescription = localText("Discover"),
                        tint = tokens.inkPrimary
                    )
                }
            }
        )

        // Smoked-glass capsule switcher: Queue / History
        SmokedGlassSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiFiSpacing.lg),
            shape = HiFiShapes.pill,
            containerColor = tokens.glassSurface
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                DownloadsSegmentTab(
                    label = "Queue (${jobs.size})",
                    selected = selectedSection == 0,
                    onClick = { selectedSection = 0 },
                    modifier = Modifier.weight(1f)
                )
                DownloadsSegmentTab(
                    label = "History (${history.size})",
                    selected = selectedSection == 1,
                    onClick = { selectedSection = 1 },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (selectedSection == 0) {
                QueueSection(
                    jobs = jobs,
                    failedCount = failedCount,
                    settings = settings,
                    isQueueRunning = isQueueRunning,
                    isQueuePaused = isQueuePaused,
                    totalSpeed = totalSpeed,
                    bottomContentPadding = bottomContentPadding,
                    onStartQueue = onStartQueue,
                    onPauseQueue = onPauseQueue,
                    onStopQueue = onStopQueue,
                    onRemoveJob = onRemoveJob,
                    onMoveJobUp = onMoveJobUp,
                    onMoveJobDown = onMoveJobDown,
                    onMoveJobToTop = onMoveJobToTop,
                    onClearWaiting = onClearWaiting,
                    onEditJobMetadata = onEditJobMetadata,
                    onRetryAllFailed = onRetryAllFailed,
                    onSetParallelDownloads = onSetParallelDownloads,
                    onSetDownloadThreads = onSetDownloadThreads
                )
            } else {
                HistorySection(
                    history = history,
                    failedCount = failedCount,
                    bottomContentPadding = bottomContentPadding,
                    onRetryHistory = onRetryHistory,
                    onRetryAllFailed = onRetryAllFailed,
                    onPlayHistory = onPlayHistory,
                    onAddHistoryToQueue = onAddHistoryToQueue,
                    onClearHistory = onClearHistory
                )
            }
        }
    }
}

@Composable
private fun DownloadsSegmentTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HiFiTheme.colors
    Box(
        modifier = modifier
            .clip(HiFiShapes.pill)
            .background(if (selected) tokens.accentSoft else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) tokens.accentBright else tokens.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun QueueSection(
    jobs: List<DownloadJob>,
    failedCount: Int,
    settings: AppSettings,
    isQueueRunning: Boolean,
    isQueuePaused: Boolean,
    totalSpeed: Double,
    bottomContentPadding: Dp,
    onStartQueue: () -> Unit,
    onPauseQueue: () -> Unit,
    onStopQueue: () -> Unit,
    onRemoveJob: (String) -> Unit,
    onMoveJobUp: (String) -> Unit,
    onMoveJobDown: (String) -> Unit,
    onMoveJobToTop: (String) -> Unit,
    onClearWaiting: () -> Unit,
    onEditJobMetadata: (DownloadJob) -> Unit,
    onRetryAllFailed: () -> Unit,
    onSetParallelDownloads: (Int) -> Unit,
    onSetDownloadThreads: (Int) -> Unit
) {
    val tokens = HiFiTheme.colors
    val queuedCount = remember(jobs) { jobs.count { it.status == JobStatus.QUEUED } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = HiFiSpacing.lg,
            end = HiFiSpacing.lg,
            bottom = bottomContentPadding
        ),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (settings.queueBlockReason.isNotBlank()) {
            item {
                HiFiGlassPanel(containerColor = tokens.warningContainer) {
                    Text(
                        text = "Queue held due to YouTube rate limit",
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.warning
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = settings.queueBlockReason,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.warning.copy(alpha = 0.88f)
                    )
                    Spacer(Modifier.height(10.dp))
                    HiFiPillActionButton(
                        label = "Resume queue",
                        icon = Icons.Rounded.PlayArrow,
                        onClick = onStartQueue,
                        primary = false
                    )
                }
            }
        }

        // Queue control row
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (isQueueRunning) {
                    HiFiPillActionButton(
                        label = "Pause queue",
                        icon = Icons.Rounded.Pause,
                        onClick = onPauseQueue,
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                    SmokedGlassSurface(
                        shape = CircleShape,
                        containerColor = tokens.errorContainer
                    ) {
                        IconButton(onClick = onStopQueue, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Rounded.Stop,
                                contentDescription = localText("Stop downloads"),
                                tint = tokens.error,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                } else {
                    HiFiPillActionButton(
                        label = if (isQueuePaused) "Resume queue" else "Download queue",
                        icon = if (isQueuePaused) Icons.Rounded.PlayArrow else Icons.Rounded.Download,
                        onClick = onStartQueue,
                        enabled = queuedCount > 0,
                        primary = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (queuedCount > 0) {
                    SmokedGlassSurface(
                        shape = CircleShape,
                        containerColor = tokens.glassSurface
                    ) {
                        IconButton(onClick = onClearWaiting, modifier = Modifier.size(44.dp)) {
                            Icon(
                                Icons.Rounded.DeleteSweep,
                                contentDescription = localText("Clear waiting"),
                                tint = tokens.inkSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        if (failedCount > 0) {
            item {
                HiFiGlassPanel(
                    containerColor = tokens.errorContainer,
                    contentPadding = 12.dp
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "$failedCount failed track(s)",
                            style = MaterialTheme.typography.titleSmall,
                            color = tokens.error,
                            modifier = Modifier.weight(1f)
                        )
                        HiFiPillActionButton(
                            label = "Retry failed",
                            icon = Icons.Rounded.Refresh,
                            onClick = onRetryAllFailed,
                            primary = false
                        )
                    }
                }
            }
        }

        // Multithread live adjuster
        item {
            HiFiGlassPanel(contentPadding = 14.dp) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ThreadStepper(
                        label = "Parallel files",
                        value = settings.effectiveParallelDownloads,
                        max = AppSettings.MAX_PARALLEL_DOWNLOADS,
                        onChange = onSetParallelDownloads,
                        modifier = Modifier.weight(1f)
                    )
                    ThreadStepper(
                        label = "Threads / file",
                        value = settings.effectiveDownloadThreads,
                        max = AppSettings.MAX_DOWNLOAD_THREADS,
                        onChange = onSetDownloadThreads,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (isQueueRunning) {
                    Spacer(Modifier.height(8.dp))
                    val activeCount = jobs.count { it.status.isActive }
                    Text(
                        text = "Transfer speed: ${TextUtils.formatSpeed(totalSpeed)} · Active: $activeCount/${settings.effectiveParallelDownloads}" +
                            if (settings.gentleRequests) " (Gentle)" else " · ${settings.effectiveDownloadThreads}×",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                } else if (isQueuePaused && queuedCount > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Paused · $queuedCount track(s) waiting",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.accentBright
                    )
                }
            }
        }

        if (jobs.isEmpty()) {
            item {
                HiFiEmptyState(
                    icon = Icons.Rounded.Download,
                    title = "Queue is empty",
                    subtitle = "Add a YouTube link from Home or Discover to start downloading."
                )
            }
        } else {
            items(jobs, key = { it.id }) { job ->
                val showReorder = queuedCount > 1 && job.status == JobStatus.QUEUED
                QueueJobCard(
                    job = job,
                    showReorder = showReorder,
                    canMoveUp = showReorder && QueueOrderHelper.canMoveUp(jobs, job.id),
                    canMoveDown = showReorder && QueueOrderHelper.canMoveDown(jobs, job.id),
                    onMoveUp = { onMoveJobUp(job.id) },
                    onMoveDown = { onMoveJobDown(job.id) },
                    onMoveToTop = { onMoveJobToTop(job.id) },
                    onEditMetadata = { onEditJobMetadata(job) },
                    onRemove = { onRemoveJob(job.id) }
                )
            }
        }
    }
}

@Composable
private fun ThreadStepper(
    label: String,
    value: Int,
    max: Int,
    onChange: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = HiFiTheme.colors
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = tokens.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SmokedGlassSurface(
                shape = CircleShape,
                containerColor = tokens.glassElevated
            ) {
                IconButton(
                    onClick = { onChange((value - 1).coerceAtLeast(1)) },
                    enabled = value > 1,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.Remove,
                        contentDescription = localText("Decrease"),
                        tint = if (value > 1) tokens.inkPrimary else tokens.inkTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
            Text(
                text = "$value",
                style = MaterialTheme.typography.titleMedium,
                color = tokens.inkPrimary,
                modifier = Modifier.padding(horizontal = 4.dp)
            )
            SmokedGlassSurface(
                shape = CircleShape,
                containerColor = tokens.glassElevated
            ) {
                IconButton(
                    onClick = { onChange((value + 1).coerceAtMost(max)) },
                    enabled = value < max,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        Icons.Rounded.Add,
                        contentDescription = localText("Increase"),
                        tint = if (value < max) tokens.inkPrimary else tokens.inkTertiary,
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun QueueJobCard(
    job: DownloadJob,
    showReorder: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onMoveToTop: () -> Unit,
    onEditMetadata: () -> Unit,
    onRemove: () -> Unit
) {
    val tokens = HiFiTheme.colors
    HiFiGlassPanel(contentPadding = 14.dp) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(tokens.accentSoft),
                contentAlignment = Alignment.Center
            ) {
                val coverModel: Any? = remember(job.thumbnailUrl, job.metadata.customArtworkUri) {
                    val custom = job.metadata.customArtworkUri
                    val raw = custom.ifBlank { job.thumbnailUrl }
                    when {
                        raw.isBlank() -> null
                        raw.startsWith("/") -> java.io.File(raw).takeIf { it.isFile }
                        else -> raw
                    }
                }
                if (coverModel != null) {
                    AsyncImage(
                        model = coverModel,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        tint = tokens.accent
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = job.title,
                    style = MaterialTheme.typography.titleSmall,
                    color = tokens.inkPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    JobStatusPill(status = job.status)
                    StatusPill(
                        text = TextUtils.formatLabel(job.options),
                        backgroundColor = tokens.glassElevated,
                        contentColor = tokens.inkSecondary
                    )
                }
            }
            if (job.status == JobStatus.QUEUED) {
                IconButton(onClick = onEditMetadata, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Edit,
                        contentDescription = localText("Edit metadata"),
                        tint = tokens.accent,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = localText("Cancel or remove"),
                    tint = tokens.inkSecondary,
                    modifier = Modifier.size(18.dp)
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${job.stage.label} · ${job.detail}",
                style = MaterialTheme.typography.bodySmall,
                color = tokens.inkSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (showReorder) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onMoveToTop, enabled = canMoveUp, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.VerticalAlignTop,
                            contentDescription = localText("Move to next"),
                            tint = if (canMoveUp) tokens.inkPrimary else tokens.inkTertiary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                    IconButton(onClick = onMoveUp, enabled = canMoveUp, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.KeyboardArrowUp,
                            contentDescription = localText("Move up"),
                            tint = if (canMoveUp) tokens.inkPrimary else tokens.inkTertiary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown, modifier = Modifier.size(30.dp)) {
                        Icon(
                            Icons.Rounded.KeyboardArrowDown,
                            contentDescription = localText("Move down"),
                            tint = if (canMoveDown) tokens.inkPrimary else tokens.inkTertiary,
                            modifier = Modifier.size(19.dp)
                        )
                    }
                }
            }
        }

        if (job.status.isActive) {
            Spacer(Modifier.height(8.dp))
            HiFiGlowProgressBar(
                progress = if (job.stage == JobStage.DOWNLOADING && job.progress > 0f) {
                    job.progress.coerceIn(0f, 1f)
                } else {
                    null
                },
                height = 3.5.dp
            )
        }
    }
}

@Composable
private fun HistorySection(
    history: List<HistoryRecord>,
    failedCount: Int,
    bottomContentPadding: Dp,
    onRetryHistory: (HistoryRecord) -> Unit,
    onRetryAllFailed: () -> Unit,
    onPlayHistory: (HistoryRecord) -> Unit,
    onAddHistoryToQueue: (HistoryRecord) -> Unit,
    onClearHistory: () -> Unit
) {
    val tokens = HiFiTheme.colors
    var search by remember { mutableStateOf("") }
    var filterStatus by remember { mutableStateOf<JobStatus?>(null) }

    val filtered = remember(history, search, filterStatus) {
        val q = search.trim().lowercase()
        history.filter { item ->
            (filterStatus == null || item.status == filterStatus) &&
                (q.isEmpty() || item.title.lowercase().contains(q) || item.detail.lowercase().contains(q))
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = HiFiSpacing.lg),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            HiFiGlassTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = "Search history…",
                onClear = { search = "" },
                modifier = Modifier.weight(1f)
            )
            if (history.isNotEmpty()) {
                TextButton(onClick = onClearHistory) {
                    Text("Clear", color = tokens.accent)
                }
            }
        }

        if (failedCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "$failedCount failed track(s)",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.error
                )
                HiFiPillActionButton(
                    label = "Retry failed",
                    icon = Icons.Rounded.Refresh,
                    onClick = onRetryAllFailed,
                    primary = false
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        LazyRow(
            contentPadding = PaddingValues(horizontal = HiFiSpacing.lg),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            item {
                HiFiGlassChip(
                    label = "All",
                    selected = filterStatus == null,
                    onClick = { filterStatus = null }
                )
            }
            items(listOf(JobStatus.DONE, JobStatus.FAILED, JobStatus.SKIPPED, JobStatus.CANCELLED)) { st ->
                HiFiGlassChip(
                    label = st.label,
                    selected = filterStatus == st,
                    onClick = { filterStatus = if (filterStatus == st) null else st }
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            HiFiEmptyState(
                icon = Icons.Rounded.History,
                title = if (history.isEmpty()) "No history yet" else "No matching history entries.",
                subtitle = if (history.isEmpty()) "Finished downloads will appear here." else "Try adjusting your search or filter."
            )
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(
                    start = HiFiSpacing.lg,
                    end = HiFiSpacing.lg,
                    bottom = bottomContentPadding
                )
            ) {
                items(filtered, key = { "${it.id}_${it.completedAtIso}" }) { record ->
                    HiFiGlassPanel(contentPadding = 14.dp) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = record.title,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = tokens.inkPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    JobStatusPill(status = record.status)
                                    StatusPill(
                                        text = record.formatLabel,
                                        backgroundColor = tokens.glassElevated,
                                        contentColor = tokens.inkSecondary
                                    )
                                }
                            }
                            if (record.status == JobStatus.DONE && record.savedUri.isNotBlank()) {
                                IconButton(onClick = { onPlayHistory(record) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Rounded.PlayArrow,
                                        contentDescription = localText("Play now"),
                                        tint = tokens.accent
                                    )
                                }
                                IconButton(onClick = { onAddHistoryToQueue(record) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.AutoMirrored.Rounded.QueueMusic,
                                        contentDescription = localText("Add to queue"),
                                        tint = tokens.inkSecondary
                                    )
                                }
                            }
                            if (!record.isConnectionCheck) {
                                IconButton(onClick = { onRetryHistory(record) }, modifier = Modifier.size(36.dp)) {
                                    Icon(
                                        Icons.Rounded.Refresh,
                                        contentDescription = localText("Queue again"),
                                        tint = tokens.inkSecondary
                                    )
                                }
                            }
                        }

                        if (record.detail.isNotBlank()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = record.detail,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (record.status == JobStatus.FAILED) tokens.error else tokens.inkSecondary
                            )
                        }

                        if (record.status != JobStatus.DONE) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                text = TaskFeedback.recoveryHint(record),
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(tokens.glassElevated)
                                    .padding(10.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

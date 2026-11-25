package com.example.mdownload.ui.components

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoFixHigh
import androidx.compose.material.icons.rounded.HighQuality
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.mdownload.MetadataEditorState
import com.example.mdownload.PlaylistPickerState
import com.example.mdownload.core.PlaylistTrackItem
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.TrackMetadata
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.launch
import java.io.File

/**
 * Explicit per-track playlist selector styled in Obsidian Orchid smoked-glass.
 */
@Composable
fun PlaylistTrackPickerDialog(
    state: PlaylistPickerState,
    downloadedVideoIds: Set<String> = emptySet(),
    queuedVideoIds: Set<String> = emptySet(),
    skipDuplicates: Boolean = true,
    onDismiss: () -> Unit,
    onOnlyThisVideo: ((String) -> Unit)? = null,
    onConfirm: (List<PlaylistTrackItem>, Boolean) -> Unit
) {
    val tokens = HiFiTheme.colors
    var query by remember(state.sourceUrl) { mutableStateOf("") }
    var selectedIds by remember(state.sourceUrl) { mutableStateOf<Set<String>>(emptySet()) }

    val filtered = remember(state.tracks, query) {
        if (query.isBlank()) state.tracks
        else {
            val q = query.trim().lowercase()
            state.tracks.filter {
                it.title.lowercase().contains(q) || it.artist.lowercase().contains(q)
            }
        }
    }
    val selectedTracks = remember(state.tracks, selectedIds) {
        state.tracks.filter { it.available && it.videoId in selectedIds }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = {
            Column {
                Text(
                    text = state.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = tokens.inkPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (state.isMix) {
                    Text(
                        text = "YouTube Mix · Bounded to up to 50 tracks",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                when {
                    state.isLoading -> {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(160.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(28.dp),
                                    color = tokens.accent,
                                    strokeWidth = 2.5.dp
                                )
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    text = "Fetching playlist tracks…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.inkSecondary
                                )
                            }
                        }
                    }

                    state.error != null -> {
                        Text(
                            text = state.error,
                            style = MaterialTheme.typography.bodyMedium,
                            color = tokens.error
                        )
                    }

                    else -> {
                        HiFiGlassTextField(
                            value = query,
                            onValueChange = { query = it },
                            placeholder = "Filter by title or artist…",
                            onClear = { query = "" }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${selectedTracks.size} selected of ${state.tracks.count { it.available }} available",
                                style = MaterialTheme.typography.bodySmall,
                                color = tokens.inkSecondary
                            )
                            Row {
                                TextButton(onClick = {
                                    selectedIds = selectedIds + filtered
                                        .filter { item ->
                                            val dup = item.videoId in queuedVideoIds || (skipDuplicates && item.videoId in downloadedVideoIds)
                                            item.available && !dup
                                        }
                                        .map { it.videoId }
                                }) {
                                    Text("Select visible", color = tokens.accent)
                                }
                                TextButton(onClick = { selectedIds = emptySet() }) {
                                    Text("Clear", color = tokens.inkSecondary)
                                }
                            }
                        }
                        HorizontalDivider(thickness = 0.65.dp, color = tokens.divider)
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 320.dp)
                        ) {
                            items(filtered, key = { "${it.index}_${it.videoId}" }) { item ->
                                val isQueued = item.videoId in queuedVideoIds
                                val isDownloaded = item.videoId in downloadedVideoIds
                                val blockedDup = isQueued || (skipDuplicates && isDownloaded)
                                val selectable = item.available && !blockedDup
                                val checked = selectable && item.videoId in selectedIds
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable(enabled = selectable) {
                                            selectedIds = if (checked) {
                                                selectedIds - item.videoId
                                            } else {
                                                selectedIds + item.videoId
                                            }
                                        }
                                        .padding(vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = checked,
                                        enabled = selectable,
                                        colors = CheckboxDefaults.colors(
                                            checkedColor = tokens.accent,
                                            checkmarkColor = tokens.onAccent,
                                            uncheckedColor = tokens.inkTertiary
                                        ),
                                        onCheckedChange = { isChecked ->
                                            selectedIds = if (isChecked) {
                                                selectedIds + item.videoId
                                            } else {
                                                selectedIds - item.videoId
                                            }
                                        }
                                    )
                                    Spacer(Modifier.width(4.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "${item.index}. ${item.title}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (selectable) tokens.inkPrimary else tokens.inkTertiary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        val statusTag = when {
                                            !item.available -> "Unavailable"
                                            isQueued -> "In queue"
                                            isDownloaded -> "Downloaded"
                                            else -> ""
                                        }
                                        val metaSubtitle = if (item.available) {
                                            val base = "${item.artist} · ${TextUtils.formatDurationSeconds(item.durationSec)}"
                                            if (statusTag.isNotBlank()) "${localText(statusTag)} · $base" else base
                                        } else {
                                            localText("Unavailable")
                                        }
                                        Text(
                                            text = metaSubtitle,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = if (isDownloaded || isQueued) tokens.accentBright else tokens.inkSecondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                                HorizontalDivider(thickness = 0.6.dp, color = tokens.divider)
                            }
                        }
                    }
                }
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = tokens.inkSecondary)
                }
                if (state.singleVideoUrl != null && onOnlyThisVideo != null) {
                    TextButton(onClick = { onOnlyThisVideo(state.singleVideoUrl) }) {
                        Text("Only this video", color = tokens.accent)
                    }
                }
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HiFiPillActionButton(
                    label = "Queue (${selectedTracks.size})",
                    onClick = { onConfirm(selectedTracks, false) },
                    enabled = selectedTracks.isNotEmpty(),
                    primary = false
                )
                HiFiPillActionButton(
                    label = "Download (${selectedTracks.size})",
                    onClick = { onConfirm(selectedTracks, true) },
                    enabled = selectedTracks.isNotEmpty(),
                    primary = true
                )
            }
        }
    )
}

/**
 * Smart per-track metadata & high-resolution cover art editor styled in Obsidian Orchid smoked-glass.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MetadataEditorDialog(
    state: MetadataEditorState,
    onImportCover: suspend (Uri) -> String?,
    onFetchHdCover: suspend (sourceUrl: String, videoId: String?) -> String?,
    onDismiss: () -> Unit,
    onSave: (TrackMetadata) -> Unit
) {
    val tokens = HiFiTheme.colors
    val scope = rememberCoroutineScope()
    var title by remember(state) { mutableStateOf(state.metadata.title) }
    var artist by remember(state) { mutableStateOf(state.metadata.artist) }
    var album by remember(state) { mutableStateOf(state.metadata.album) }
    var year by remember(state) { mutableStateOf(state.metadata.year) }
    var customArtworkUri by remember(state) { mutableStateOf(state.metadata.customArtworkUri) }
    var isLoadingCover by remember(state) { mutableStateOf(false) }
    var coverNote by remember(state) { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { pickedUri ->
        if (pickedUri != null) {
            scope.launch {
                isLoadingCover = true
                coverNote = null
                val savedPath = onImportCover(pickedUri)
                if (!savedPath.isNullOrBlank()) {
                    customArtworkUri = savedPath
                    coverNote = "HD cover selected"
                } else {
                    coverNote = "Could not load selected image"
                }
                isLoadingCover = false
            }
        }
    }

    val coverPreviewModel: Any? = remember(customArtworkUri, state.sourceThumbnailUrl) {
        when {
            customArtworkUri.isNotBlank() -> {
                val f = File(customArtworkUri)
                if (f.isFile) f else customArtworkUri
            }
            state.sourceThumbnailUrl.isNotBlank() -> state.sourceThumbnailUrl
            else -> null
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = HiFiShapes.card,
        containerColor = tokens.canvasDeep,
        title = {
            Column {
                Text("Edit track metadata", style = MaterialTheme.typography.titleMedium, color = tokens.inkPrimary)
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "Blank fields keep source values. Suffixes like Official MV / Lyrics Video are cleaned automatically.",
                    style = MaterialTheme.typography.bodySmall,
                    color = tokens.inkSecondary
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                SmokedGlassSurface(
                    shape = HiFiShapes.input,
                    containerColor = tokens.glassElevated
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(76.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(tokens.accentSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            if (coverPreviewModel != null) {
                                AsyncImage(
                                    model = coverPreviewModel,
                                    contentDescription = localText("Cover art"),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = tokens.accent
                                )
                            }
                            if (isLoadingCover) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(tokens.canvasDeep.copy(alpha = 0.7f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(22.dp),
                                        color = tokens.accent,
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text("Cover art", style = MaterialTheme.typography.titleSmall, color = tokens.inkPrimary)
                                if (customArtworkUri.isNotBlank()) {
                                    StatusPill("HD", tokens.accentSoft, tokens.accentBright)
                                }
                            }
                            if (!coverNote.isNullOrBlank()) {
                                Text(
                                    text = coverNote!!,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = tokens.accentBright
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                HiFiGlassChip(
                                    label = "Choose photo",
                                    selected = false,
                                    leadingIcon = Icons.Rounded.Image,
                                    onClick = {
                                        if (!isLoadingCover) {
                                            photoPicker.launch(
                                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                            )
                                        }
                                    }
                                )
                                if (state.sourceThumbnailUrl.isNotBlank() || !state.sourceVideoId.isNullOrBlank()) {
                                    HiFiGlassChip(
                                        label = "Fetch HD",
                                        selected = false,
                                        leadingIcon = Icons.Rounded.HighQuality,
                                        onClick = {
                                            if (!isLoadingCover) {
                                                scope.launch {
                                                    isLoadingCover = true
                                                    coverNote = null
                                                    val hdPath = onFetchHdCover(state.sourceThumbnailUrl, state.sourceVideoId)
                                                    if (!hdPath.isNullOrBlank()) {
                                                        customArtworkUri = hdPath
                                                        coverNote = "HD cover fetched"
                                                    } else {
                                                        coverNote = "Using source cover"
                                                    }
                                                    isLoadingCover = false
                                                }
                                            }
                                        }
                                    )
                                }
                                if (customArtworkUri.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            customArtworkUri = ""
                                            coverNote = null
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.height(30.dp)
                                    ) {
                                        Icon(Icons.Rounded.RestartAlt, contentDescription = null, tint = tokens.inkSecondary, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(2.dp))
                                        Text("Reset", style = MaterialTheme.typography.labelSmall, color = tokens.inkSecondary)
                                    }
                                }
                            }
                        }
                    }
                }

                val cleanedPreview = remember(title) { TextUtils.cleanSourceTitle(title) }
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Title") },
                    placeholder = {
                        Text(
                            TextUtils.cleanSourceTitle(state.sourceTitle).ifBlank { "Source title" },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    trailingIcon = {
                        if (title.isNotBlank() && cleanedPreview != title.trim()) {
                            IconButton(onClick = { title = cleanedPreview }) {
                                Icon(
                                    Icons.Rounded.AutoFixHigh,
                                    contentDescription = localText("Clean title"),
                                    tint = tokens.accent
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = HiFiShapes.input,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = artist,
                    onValueChange = { artist = it },
                    label = { Text("Artist") },
                    placeholder = { Text("Source channel / artist") },
                    singleLine = true,
                    shape = HiFiShapes.input,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = album,
                        onValueChange = { album = it },
                        label = { Text("Album") },
                        placeholder = { Text("Optional album tag") },
                        singleLine = true,
                        shape = HiFiShapes.input,
                        modifier = Modifier.weight(1.4f)
                    )
                    OutlinedTextField(
                        value = year,
                        onValueChange = { raw -> year = raw.filter(Char::isDigit).take(4) },
                        label = { Text("Year") },
                        placeholder = { Text("2024") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        shape = HiFiShapes.input,
                        modifier = Modifier.weight(0.8f)
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = tokens.inkSecondary)
            }
        },
        confirmButton = {
            HiFiPillActionButton(
                label = "Save",
                enabled = !isLoadingCover,
                primary = true,
                onClick = {
                    onSave(
                        TrackMetadata(
                            title = title,
                            artist = artist,
                            album = album,
                            year = year,
                            customArtworkUri = customArtworkUri
                        )
                    )
                }
            )
        }
    )
}

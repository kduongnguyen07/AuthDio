package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.TextUtils
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQueueSheet(
    state: PlaybackUiState,
    onDismiss: () -> Unit,
    onSelectIndex: (Int) -> Unit,
    onMoveItem: (from: Int, to: Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onClearQueue: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?
) {
    val tokens = HiFiTheme.colors
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = HiFiShapes.sheetTop,
        containerColor = tokens.canvasDeep,
        contentColor = tokens.inkPrimary
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 580.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = HiFiSpacing.lg, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Playing queue",
                        style = MaterialTheme.typography.titleLarge,
                        color = tokens.inkPrimary
                    )
                    Text(
                        text = "${state.queue.size} track(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary
                    )
                }
                if (state.queue.isNotEmpty()) {
                    TextButton(onClick = {
                        onClearQueue()
                        onDismiss()
                    }) {
                        Text("Clear", color = tokens.error)
                    }
                }
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(thickness = 0.7.dp, color = tokens.divider)

            if (state.queue.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Playback queue is empty",
                        style = MaterialTheme.typography.bodyMedium,
                        color = tokens.inkSecondary
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(vertical = 8.dp)
                ) {
                    itemsIndexed(
                        items = state.queue,
                        key = { index, item -> "${index}_${item.uri}" }
                    ) { index, track ->
                        val isCurrent = index == state.currentIndex
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    if (isCurrent) tokens.accentSoft.copy(alpha = 0.28f)
                                    else Color.Transparent
                                )
                                .clickable { onSelectIndex(index) }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = HiFiSpacing.lg, end = 4.dp, top = 6.dp, bottom = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TrackArtworkBox(
                                    uri = track.uri,
                                    albumId = track.albumId,
                                    highRes = false,
                                    isPlayingIndicator = isCurrent && state.isPlaying,
                                    cornerRadius = 11.dp,
                                    iconSize = 18.dp,
                                    modifier = Modifier.size(44.dp),
                                    loadArtwork = loadArtwork
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (isCurrent) {
                                            Icon(
                                                imageVector = Icons.Rounded.GraphicEq,
                                                contentDescription = null,
                                                tint = tokens.accent,
                                                modifier = Modifier
                                                    .size(15.dp)
                                                    .padding(end = 4.dp)
                                            )
                                        }
                                        Text(
                                            text = track.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = if (isCurrent) {
                                                if (tokens.isDark) tokens.accentBright else tokens.accent
                                            } else {
                                                tokens.inkPrimary
                                            },
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    Text(
                                        text = "${track.artist} · ${TextUtils.formatDurationMs(track.durationMs)}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = tokens.inkSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                if (state.queue.size > 1) {
                                    IconButton(
                                        onClick = { onMoveItem(index, index - 1) },
                                        enabled = index > 0,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.KeyboardArrowUp,
                                            contentDescription = localText("Move up"),
                                            tint = if (index > 0) tokens.inkSecondary else tokens.inkTertiary.copy(alpha = 0.35f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                    IconButton(
                                        onClick = { onMoveItem(index, index + 1) },
                                        enabled = index < state.queue.lastIndex,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            Icons.Rounded.KeyboardArrowDown,
                                            contentDescription = localText("Move down"),
                                            tint = if (index < state.queue.lastIndex) tokens.inkSecondary else tokens.inkTertiary.copy(alpha = 0.35f),
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                                IconButton(
                                    onClick = { onRemoveIndex(index) },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    Icon(
                                        Icons.Rounded.Close,
                                        contentDescription = localText("Remove from queue"),
                                        modifier = Modifier.size(17.dp),
                                        tint = tokens.inkSecondary
                                    )
                                }
                            }
                            HorizontalDivider(
                                modifier = Modifier.padding(start = HiFiSpacing.lg + 56.dp, end = HiFiSpacing.lg),
                                thickness = 0.6.dp,
                                color = tokens.divider
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

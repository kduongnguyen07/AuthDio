package com.example.mdownload.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.ui.TrackArtworkBox
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.flow.StateFlow

/**
 * Clean artwork-forward album or playlist tile without heavy surrounding borders.
 */
@Composable
fun HiFiArtworkTile(
    title: String,
    subtitle: String,
    artworkUri: String,
    albumId: Long,
    onClick: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier,
    tileWidth: Dp? = null
) {
    val tokens = HiFiTheme.colors
    Column(
        modifier = modifier
            .then(if (tileWidth != null) Modifier.width(tileWidth) else Modifier.fillMaxWidth())
            .clickable(onClick = onClick)
    ) {
        TrackArtworkBox(
            uri = artworkUri,
            albumId = albumId,
            highRes = false,
            cornerRadius = 18.dp,
            iconSize = 36.dp,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            loadArtwork = loadArtwork
        )
        Spacer(Modifier.height(HiFiSpacing.xs))
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = tokens.inkPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = tokens.inkSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Atmospheric smoked-glass "Continue Listening" spotlight card for Home.
 */
@Composable
fun HiFiContinueListeningCard(
    track: LibraryTrack,
    isPlaying: Boolean,
    progress: Float,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier,
    positionMsFlow: StateFlow<Long>? = null,
    durationMs: Long = 0L
) {
    val tokens = HiFiTheme.colors
    SmokedGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenNowPlaying),
        shape = HiFiShapes.card,
        containerColor = tokens.glassSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(HiFiSpacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtworkBox(
                    uri = track.uri,
                    albumId = track.albumId,
                    highRes = false,
                    isPlayingIndicator = isPlaying,
                    cornerRadius = 16.dp,
                    iconSize = 26.dp,
                    modifier = Modifier.size(60.dp),
                    loadArtwork = loadArtwork
                )
                Spacer(Modifier.width(14.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isPlaying) "Now playing" else "Continue listening",
                        style = MaterialTheme.typography.labelMedium,
                        color = tokens.accent
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = tokens.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = localText(if (isPlaying) "Pause" else "Play"),
                        tint = tokens.inkPrimary,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
            Spacer(Modifier.height(HiFiSpacing.sm))
            ContinueListeningProgressLeaf(
                positionMsFlow = positionMsFlow,
                durationMs = durationMs,
                fallbackProgress = progress
            )
        }
    }
}

@Composable
private fun ContinueListeningProgressLeaf(
    positionMsFlow: StateFlow<Long>?,
    durationMs: Long,
    fallbackProgress: Float
) {
    val liveProgress = if (positionMsFlow != null && durationMs > 0L) {
        val pos by positionMsFlow.collectAsState()
        (pos.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        fallbackProgress
    }
    HiFiGlowProgressBar(progress = liveProgress, height = 3.dp)
}

@Composable
fun HiFiSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null
) {
    val tokens = HiFiTheme.colors
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = tokens.inkPrimary
        )
        if (!actionLabel.isNullOrBlank() && onAction != null) {
            Text(
                text = actionLabel,
                style = MaterialTheme.typography.labelLarge,
                color = tokens.accent,
                modifier = Modifier
                    .clickable(onClick = onAction)
                    .padding(horizontal = 4.dp, vertical = 4.dp)
            )
        }
    }
}

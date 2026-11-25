package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.SmokedGlassSurface
import com.example.mdownload.ui.design.HiFiMotion
import com.example.mdownload.ui.design.HiFiShapes
import com.example.mdownload.ui.design.HiFiSpacing
import com.example.mdownload.ui.design.HiFiTheme
import com.example.mdownload.ui.localText
import kotlinx.coroutines.flow.StateFlow

/**
 * Floating smoked-glass mini-player directly inspired by the Dribbble reference's right screen:
 * - Translucent left-to-right progress wash sweeping smoothly behind the content
 * - 44dp rounded-square (12dp) artwork
 * - Crisp title + muted artist
 * - Minimalist Play/Pause + Skip Next glyphs
 */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier,
    positionMsFlow: StateFlow<Long>? = null
) {
    val track = state.currentTrack ?: return
    val tokens = HiFiTheme.colors

    SmokedGlassSurface(
        modifier = modifier
            .fillMaxWidth()
            .height(HiFiSpacing.miniPlayerHeight)
            .clickable(onClick = onOpenNowPlaying),
        shape = HiFiShapes.miniPlayer,
        containerColor = tokens.glassSurface
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(HiFiSpacing.miniPlayerHeight)
        ) {
            // Isolated zero-child leaf so live 250ms position ticks never recompose artwork, text, or buttons.
            MiniPlayerProgressWash(
                positionMsFlow = positionMsFlow,
                fallbackPositionMs = state.positionMs,
                durationMs = state.durationMs,
                progressWashColor = tokens.progressWash,
                isDark = tokens.isDark,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HiFiSpacing.miniPlayerHeight)
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HiFiSpacing.miniPlayerHeight)
                    .padding(start = 12.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TrackArtworkBox(
                    uri = track.uri,
                    albumId = track.albumId,
                    highRes = false,
                    isPlayingIndicator = state.isPlaying,
                    cornerRadius = 12.dp,
                    iconSize = 20.dp,
                    modifier = Modifier.size(44.dp),
                    loadArtwork = loadArtwork
                )

                Spacer(Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = tokens.inkPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = track.artist,
                        style = MaterialTheme.typography.bodySmall,
                        color = tokens.inkSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                IconButton(
                    onClick = onTogglePlayPause,
                    modifier = Modifier.size(48.dp)
                ) {
                    AnimatedContent(
                        targetState = state.isPlaying,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "miniPlayPause"
                    ) { playing ->
                        Icon(
                            imageVector = if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = localText(if (playing) "Pause" else "Play"),
                            tint = tokens.inkPrimary,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }

                IconButton(
                    onClick = onSkipNext,
                    enabled = state.hasNext || state.queue.size > 1,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.SkipNext,
                        contentDescription = localText("Next track"),
                        tint = if (state.hasNext || state.queue.size > 1) {
                            tokens.inkPrimary.copy(alpha = 0.92f)
                        } else {
                            tokens.inkTertiary
                        },
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

/**
 * Zero-child draw-phase progress wash isolated from [MiniPlayer]'s layout and text tree.
 * Reads animated progress strictly inside `onDrawBehind` so animations never recompose siblings.
 */
@Composable
private fun MiniPlayerProgressWash(
    positionMsFlow: StateFlow<Long>?,
    fallbackPositionMs: Long,
    durationMs: Long,
    progressWashColor: Color,
    isDark: Boolean,
    modifier: Modifier = Modifier
) {
    val livePosition = positionMsFlow?.collectAsState()?.value ?: fallbackPositionMs
    val safeDuration = durationMs.coerceAtLeast(1L)
    val targetProgress = (livePosition.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    val animatedProgressState: State<Float> = animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = HiFiMotion.dockSpringFloat,
        label = "miniPlayerProgress"
    )
    Box(
        modifier = modifier.drawWithCache {
            val brush = Brush.horizontalGradient(
                colors = listOf(
                    progressWashColor.copy(alpha = if (isDark) 0.58f else 0.34f),
                    progressWashColor.copy(alpha = if (isDark) 0.32f else 0.18f)
                ),
                startX = 0f,
                endX = size.width.coerceAtLeast(1f)
            )
            onDrawBehind {
                val fillWidth = size.width * animatedProgressState.value
                if (fillWidth > 1f) {
                    drawRect(
                        brush = brush,
                        topLeft = Offset.Zero,
                        size = Size(fillWidth, size.height)
                    )
                }
            }
        }
    )
}

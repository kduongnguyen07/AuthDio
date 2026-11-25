package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.StateFlow

/**
 * Physical floating circular album artwork disc reproducing Reference 1:
 * - Soft ambient drop shadow floating above the near-black void.
 * - Multi-layered dark slate-charcoal bezel with top-left specular illuminated rim.
 * - Integrated [CircularSeekRing] with an expanded invisible outer touch band (`+36.dp`)
 *   for continuous angle-based drag seeking without colliding with center artwork gestures.
 * - Crisp circular inner album cover with anti-aliased edge and animated center hold halo
 *   communicating the long-press orbital navigation affordance.
 */
@Composable
fun CircularAlbumArtwork(
    trackUri: String,
    albumId: Long,
    isPlaying: Boolean,
    positionMs: Long,
    durationMs: Long,
    isSeekable: Boolean,
    bezelDiameter: Dp,
    scrubController: CircularScrubController,
    holdFeedbackProgress: Float = 0f,
    interactiveSeeking: Boolean = true,
    onCommitSeek: (Long) -> Unit,
    artworkTouchModifier: Modifier = Modifier,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier,
    positionMsFlow: StateFlow<Long>? = null
) {
    val innerArtDiameter = bezelDiameter * 0.72f
    // Full unclipped circular touch container (2 * outerTouchRadiusFraction * R) so the
    // 0.74R..1.22R seek annulus has zero outer clipping (UI-01).
    val outerTouchDiameter = bezelDiameter * scrubController.config.outerTouchRadiusFraction

    // Attach artworkTouchModifier on the outer unified container (gated by polar radius <= 0.72R)
    // instead of the inner square Box so the inner Box's 4 corners (extending to sqrt(2)*0.72R
    // = 1.018R at 45°/135°/225°/315°) can never intercept diagonal CircularSeekRing touches (UI-01).
    Box(
        modifier = modifier
            .size(outerTouchDiameter)
            .then(artworkTouchModifier),
        contentAlignment = Alignment.Center
    ) {
        // Multi-layered dark outer bezel, soft ambient shadow & illuminated specular rim (cached shaders)
        Spacer(
            modifier = Modifier
                .size(bezelDiameter)
                .drawWithCache {
                    val outerR = minOf(size.width, size.height) / 2f
                    val innerR = outerR * 0.72f
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val shadowCenter = Offset(center.x, center.y + 8.dp.toPx())
                    val shadowRadius = outerR * 1.12f
                    val shadowBrush = Brush.radialGradient(
                        0.0f to Color.Black.copy(alpha = 0.62f),
                        0.78f to Color.Black.copy(alpha = 0.34f),
                        1.0f to Color.Transparent,
                        center = shadowCenter,
                        radius = shadowRadius
                    )
                    val bezelBrush = Brush.linearGradient(
                        colors = listOf(
                            OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.96f),
                            OrbitalDesignTokens.Colors.BezelOuter.copy(alpha = 0.98f),
                            OrbitalDesignTokens.Colors.BezelInnerChannel.copy(alpha = 0.98f)
                        ),
                        start = Offset.Zero,
                        end = Offset(size.width, size.height)
                    )
                    val rimBrush = Brush.linearGradient(
                        colors = listOf(
                            OrbitalDesignTokens.Colors.BezelRimBright.copy(alpha = 0.56f),
                            OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.24f),
                            OrbitalDesignTokens.Colors.BezelRimDark.copy(alpha = 0.45f),
                            OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.22f)
                        ),
                        start = Offset(size.width * 0.14f, size.height * 0.08f),
                        end = Offset(size.width * 0.86f, size.height * 0.92f)
                    )
                    val innerBevelStroke = Stroke(width = 6.dp.toPx())
                    val innerBevelRadius = innerR + 4.dp.toPx()
                    val outerRimStroke = Stroke(width = OrbitalDesignTokens.Sizing.BezelOuterStroke.toPx())
                    val outerRimRadius = outerR - 0.8.dp.toPx()

                    onDrawBehind {
                        drawCircle(brush = shadowBrush, radius = shadowRadius, center = shadowCenter)
                        drawCircle(brush = bezelBrush, radius = outerR, center = center)
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.38f),
                            radius = innerBevelRadius,
                            center = center,
                            style = innerBevelStroke
                        )
                        drawCircle(
                            brush = rimBrush,
                            radius = outerRimRadius,
                            center = center,
                            style = outerRimStroke
                        )
                    }
                }
        )

        // Continuous angle-based circular drag seek ring with expanded invisible outer touch band
        CircularSeekRing(
            trackKey = trackUri,
            positionMs = positionMs,
            positionMsFlow = positionMsFlow,
            durationMs = durationMs,
            isSeekable = isSeekable,
            visualBezelDiameter = bezelDiameter,
            scrubController = scrubController,
            interactiveSeeking = interactiveSeeking,
            innerArtworkRadiusFraction = 0.72f,
            onCommitSeek = onCommitSeek,
            modifier = Modifier.fillMaxSize()
        )

        // Central circular album artwork (strictly visual; polar-gated gestures live on outer Box)
        Box(
            modifier = Modifier
                .size(innerArtDiameter)
                .clip(CircleShape),
            contentAlignment = Alignment.Center
        ) {
            TrackArtworkBox(
                uri = trackUri,
                albumId = albumId,
                highRes = true,
                isPlayingIndicator = isPlaying,
                shape = CircleShape,
                iconSize = 54.dp,
                showBorder = false,
                modifier = Modifier.fillMaxSize(),
                loadArtwork = loadArtwork
            )

            // Fine anti-aliased inner glass edge + subtle long-press hold halo
            Canvas(modifier = Modifier.fillMaxSize()) {
                val r = minOf(size.width, size.height) / 2f
                drawCircle(
                    color = OrbitalDesignTokens.Colors.ArtworkInnerRim.copy(alpha = 0.36f),
                    radius = r - 0.5.dp.toPx(),
                    style = Stroke(width = 0.9.dp.toPx())
                )

                if (holdFeedbackProgress > 0.01f) {
                    val haloRadius = r * (0.20f + 0.16f * holdFeedbackProgress)
                    drawCircle(
                        color = OrbitalDesignTokens.Colors.HoldHaloFill.copy(
                            alpha = 0.34f * holdFeedbackProgress
                        ),
                        radius = haloRadius,
                        center = center
                    )
                    drawCircle(
                        color = OrbitalDesignTokens.Colors.HoldHaloStroke.copy(
                            alpha = 0.88f * holdFeedbackProgress
                        ),
                        radius = haloRadius,
                        center = center,
                        style = Stroke(width = 1.6.dp.toPx())
                    )
                }
            }
        }
    }
}

package com.example.mdownload.player.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.mdownload.core.LibraryTrack
import kotlin.math.abs

/**
 * Single circular album cover positioned on the orbital constellation via [graphicsLayer]
 * so continuous vertical dragging avoids full-tree relayouts.
 *
 * As [transform.relativeIndex] approaches `0f`, the focal dark layered bezel and specular rim
 * from Reference 2 smoothly bloom around the selected artwork.
 */
@Composable
fun OrbitalItem(
    track: LibraryTrack,
    transform: OrbitalItemTransform,
    baseBezelDiameterPx: Float,
    isFocalSelection: Boolean,
    interactiveTapEnabled: Boolean,
    onTapSelect: () -> Unit,
    loadArtwork: suspend (uri: String, albumId: Long, highRes: Boolean) -> Bitmap?,
    modifier: Modifier = Modifier
) {
    if (transform.alpha <= 0.01f || baseBezelDiameterPx <= 1f) return

    val density = LocalDensity.current
    val baseDiameterDp = with(density) { baseBezelDiameterPx.toDp() }
    val innerArtDp = baseDiameterDp * 0.74f

    // Smooth focal bezel prominence: 1f at center, fading cleanly to 0f by |rel| >= 0.65
    val focusWeight = (1f - (abs(transform.relativeIndex) / 0.65f)).coerceIn(0f, 1f)
    val highRes = abs(transform.relativeIndex) < 0.85f

    Box(
        modifier = modifier
            .zIndex(transform.zIndex)
            .size(baseDiameterDp)
            .graphicsLayer {
                translationX = transform.offsetX - baseBezelDiameterPx * 0.5f
                translationY = transform.offsetY - baseBezelDiameterPx * 0.5f
                scaleX = transform.scale
                scaleY = transform.scale
                alpha = transform.alpha
            }
            .semantics {
                selected = isFocalSelection
                contentDescription = "${track.title}, ${track.artist}"
                if (interactiveTapEnabled) {
                    role = Role.Button
                }
            }
            .then(
                if (interactiveTapEnabled) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onTapSelect
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        // Layered dark bezel + soft depth shadow blooming smoothly for the focal cover (Reference 2)
        if (focusWeight > 0.02f) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val outerR = minOf(size.width, size.height) / 2f

                // Ambient depth shadow
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color.Black.copy(alpha = 0.58f * focusWeight),
                        0.82f to Color.Black.copy(alpha = 0.28f * focusWeight),
                        1.0f to Color.Transparent,
                        center = Offset(center.x, center.y + 6.dp.toPx()),
                        radius = outerR * 1.14f
                    ),
                    radius = outerR * 1.14f,
                    center = Offset(center.x, center.y + 6.dp.toPx())
                )

                // Dark slate concentric bezel body
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            OrbitalDesignTokens.Colors.BezelMid.copy(alpha = 0.94f * focusWeight),
                            OrbitalDesignTokens.Colors.BezelOuter.copy(alpha = 0.96f * focusWeight),
                            OrbitalDesignTokens.Colors.BezelInnerChannel.copy(alpha = 0.96f * focusWeight)
                        ),
                        start = Offset(0f, 0f),
                        end = Offset(size.width, size.height)
                    ),
                    radius = outerR,
                    center = center
                )

                // Illuminated specular rim around focal disc
                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            OrbitalDesignTokens.Colors.BezelRimBright.copy(alpha = 0.58f * focusWeight),
                            OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.25f * focusWeight),
                            OrbitalDesignTokens.Colors.BezelRimDark.copy(alpha = 0.44f * focusWeight),
                            OrbitalDesignTokens.Colors.BezelRimSoft.copy(alpha = 0.24f * focusWeight)
                        ),
                        start = Offset(size.width * 0.14f, size.height * 0.10f),
                        end = Offset(size.width * 0.88f, size.height * 0.90f)
                    ),
                    radius = outerR - 0.8.dp.toPx(),
                    center = center,
                    style = Stroke(width = OrbitalDesignTokens.Sizing.BezelOuterStroke.toPx())
                )
            }
        } else {
            // Subtle floating drop shadow & hairline halo on neighboring covers so they stay distinct
            Canvas(modifier = Modifier.fillMaxSize()) {
                val artR = (minOf(size.width, size.height) / 2f) * 0.74f
                drawCircle(
                    brush = Brush.radialGradient(
                        0.0f to Color.Black.copy(alpha = 0.42f),
                        0.85f to Color.Black.copy(alpha = 0.18f),
                        1.0f to Color.Transparent,
                        center = Offset(center.x, center.y + 4.dp.toPx()),
                        radius = artR * 1.18f
                    ),
                    radius = artR * 1.18f,
                    center = Offset(center.x, center.y + 4.dp.toPx())
                )
            }
        }

        // Circular album artwork
        TrackArtworkBox(
            uri = track.uri,
            albumId = track.albumId,
            highRes = highRes,
            isPlayingIndicator = false,
            shape = CircleShape,
            iconSize = if (focusWeight > 0.5f) 40.dp else 26.dp,
            showBorder = true,
            modifier = Modifier
                .size(innerArtDp)
                .clip(CircleShape),
            loadArtwork = loadArtwork
        )
    }
}

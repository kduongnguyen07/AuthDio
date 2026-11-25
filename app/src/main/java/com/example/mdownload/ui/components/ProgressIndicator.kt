package com.example.mdownload.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.design.HiFiTheme
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Signature Dribbble-inspired concentric orbital progress halo with interactive touch scrubbing.
 *
 * Geometry:
 * - Outer translucent frosted-glass halo disc with luminous specular rim.
 * - Sweeping circular progress arc starting at bottom-left (114°) and sweeping 312° clockwise
 *   to bottom-right (66°), leaving an open bottom notch where the elapsed time sits cleanly.
 * - Direct touch/drag around the ring seeks smoothly without fighting live Media3 position updates.
 */
@Composable
fun OrbitalProgressScrubber(
    progress: Float,
    timeLabel: String,
    onSeekFraction: (Float) -> Unit,
    onScrubPreview: (Float?) -> Unit = {},
    modifier: Modifier = Modifier,
    centerContent: @Composable BoxScope.() -> Unit
) {
    val tokens = HiFiTheme.colors
    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(progress) }
    val activeProgress = (if (isDragging) dragFraction else progress).coerceIn(0f, 1f)

    // Arc starts at 114° (lower-left of bottom notch) and sweeps 312° clockwise to 66° (lower-right)
    val startAngleDeg = 114f
    val totalSweepDeg = 312f

    fun angleToFraction(offset: Offset, size: Size): Float? {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dx = offset.x - cx
        val dy = offset.y - cy
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
        val outerR = minOf(cx, cy)
        // Respond to touches in the halo band (avoid stealing center artwork taps)
        if (dist < outerR * 0.54f || dist > outerR * 1.08f) return null
        var deg = (atan2(dy.toDouble(), dx.toDouble()) * 180.0 / PI).toFloat()
        if (deg < 0f) deg += 360f
        var relative = deg - startAngleDeg
        if (relative < 0f) relative += 360f
        return when {
            relative <= totalSweepDeg -> (relative / totalSweepDeg).coerceIn(0f, 1f)
            relative < totalSweepDeg + 24f -> 1f
            else -> 0f
        }
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { tapOffset ->
                        val frac = angleToFraction(
                            tapOffset,
                            Size(size.width.toFloat(), size.height.toFloat())
                        )
                        if (frac != null) {
                            onSeekFraction(frac)
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { startOffset ->
                            val frac = angleToFraction(
                                startOffset,
                                Size(size.width.toFloat(), size.height.toFloat())
                            )
                            if (frac != null) {
                                isDragging = true
                                dragFraction = frac
                                onScrubPreview(frac)
                            }
                        },
                        onDragEnd = {
                            if (isDragging) {
                                onSeekFraction(dragFraction)
                                onScrubPreview(null)
                                isDragging = false
                            }
                        },
                        onDragCancel = {
                            isDragging = false
                            onScrubPreview(null)
                        },
                        onDrag = { change, _ ->
                            if (isDragging) {
                                val frac = angleToFraction(
                                    change.position,
                                    Size(size.width.toFloat(), size.height.toFloat())
                                )
                                if (frac != null) {
                                    dragFraction = frac
                                    onScrubPreview(frac)
                                }
                            }
                        }
                    )
                }
        ) {
            val outerRadius = (minOf(size.width, size.height) / 2f) - 2.dp.toPx()
            val center = Offset(size.width / 2f, size.height / 2f)

            // 1. Concentric translucent frosted halo disc
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.0f to Color.Transparent,
                        0.66f to tokens.glassElevated.copy(alpha = if (tokens.isDark) 0.55f else 0.45f),
                        0.88f to tokens.glassSurface.copy(alpha = if (tokens.isDark) 0.68f else 0.60f),
                        1.0f to tokens.glassRimTop.copy(alpha = if (tokens.isDark) 0.24f else 0.40f)
                    ),
                    center = center,
                    radius = outerRadius
                ),
                radius = outerRadius,
                center = center
            )

            // 2. Crisp specular outer glass rim
            drawCircle(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        tokens.glassRimTop.copy(alpha = if (tokens.isDark) 0.48f else 0.72f),
                        tokens.glassRimBottom.copy(alpha = if (tokens.isDark) 0.14f else 0.25f),
                        tokens.glassRimTop.copy(alpha = if (tokens.isDark) 0.28f else 0.42f)
                    )
                ),
                radius = outerRadius,
                center = center,
                style = Stroke(width = 1.2.dp.toPx())
            )

            // 3. Progress arc track nested inside the frosted halo band
            val arcRadius = outerRadius * 0.855f
            val arcTopLeft = Offset(center.x - arcRadius, center.y - arcRadius)
            val arcSize = Size(arcRadius * 2f, arcRadius * 2f)
            val strokePx = 2.6.dp.toPx()

            drawArc(
                color = tokens.progressTrack,
                startAngle = startAngleDeg,
                sweepAngle = totalSweepDeg,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // 4. Luminous progress sweep (Periwinkle -> Orchid -> Crisp White tip)
            val sweep = totalSweepDeg * activeProgress
            if (sweep > 0.5f) {
                drawArc(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            tokens.progressArcStart,
                            tokens.progressArcEnd,
                            if (tokens.isDark) Color.White else tokens.accent
                        ),
                        start = Offset(center.x - arcRadius, center.y + arcRadius),
                        end = Offset(center.x + arcRadius, center.y - arcRadius)
                    ),
                    startAngle = startAngleDeg,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx + 0.4.dp.toPx(), cap = StrokeCap.Round)
                )

                // Specular glowing thumb dot at current progress tip
                val tipRad = (startAngleDeg + sweep) * (PI / 180.0)
                val tipX = center.x + (arcRadius * cos(tipRad)).toFloat()
                val tipY = center.y + (arcRadius * sin(tipRad)).toFloat()
                drawCircle(
                    color = tokens.progressArcStart.copy(alpha = 0.42f),
                    radius = if (isDragging) 8.dp.toPx() else 5.5.dp.toPx(),
                    center = Offset(tipX, tipY)
                )
                drawCircle(
                    color = if (tokens.isDark) Color.White else tokens.accent,
                    radius = if (isDragging) 4.dp.toPx() else 2.6.dp.toPx(),
                    center = Offset(tipX, tipY)
                )
            }
        }

        // Center circular album artwork slot
        centerContent()

        // Bottom-notch embedded timestamp (matching "3:23" inside the halo notch of the reference)
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 14.dp)
        ) {
            Text(
                text = timeLabel,
                style = MaterialTheme.typography.labelMedium,
                color = tokens.inkPrimary.copy(alpha = 0.90f)
            )
        }
    }
}

/**
 * Sleek luminous gradient linear progress bar for active downloads & background tasks.
 */
@Composable
fun HiFiGlowProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    height: Dp = 4.dp
) {
    val tokens = HiFiTheme.colors
    val clamped = progress?.coerceIn(0f, 1f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(CircleShape)
            .background(tokens.progressTrack)
    ) {
        if (clamped != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(clamped.coerceAtLeast(0.02f))
                    .height(height)
                    .clip(CircleShape)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(tokens.progressArcStart, tokens.accent, tokens.accentBright)
                        )
                    )
            )
        } else {
            androidx.compose.material3.LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height),
                color = tokens.accent,
                trackColor = Color.Transparent
            )
        }
    }
}

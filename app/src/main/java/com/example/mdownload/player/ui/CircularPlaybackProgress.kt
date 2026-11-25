package com.example.mdownload.player.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.mdownload.ui.AppText as Text
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Circular playback progress ring reproducing Reference 1:
 * - Recessed circular groove inside the dark bezel channel with a bottom 6 o'clock break
 *   framing the centered tabular timestamp (`2:23`).
 * - Ice-blue/periwinkle active arc with soft ambient glow sweeping clockwise around the disc.
 * - Optional perimeter-only scrubbing: touches inside the inner album art radius are intentionally
 *   ignored so center long-press orbital activation never conflicts with ring seeking.
 */
@Composable
fun CircularPlaybackProgress(
    progress: Float,
    timeLabel: String,
    innerArtworkRadiusFraction: Float = 0.72f,
    interactiveSeeking: Boolean = true,
    onSeekFraction: (Float) -> Unit,
    onScrubPreview: (Float?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    val shownProgress = (if (dragging) dragFraction else progress).coerceIn(0f, 1f)

    // Clockwise 328° arc leaving a clean 32° gap at 6 o'clock (90°) for the timestamp
    val startAngle = 106f
    val totalSweep = 328f

    fun isOnBezelRing(pos: Offset, size: Size): Boolean {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val dist = hypot(pos.x - cx, pos.y - cy)
        val outerR = minOf(size.width, size.height) / 2f
        val innerR = outerR * innerArtworkRadiusFraction
        return dist >= innerR * 0.96f && dist <= outerR * 1.06f
    }

    fun offsetToFraction(pos: Offset, size: Size): Float {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val rad = atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble())
        var deg = Math.toDegrees(rad).toFloat()
        if (deg < 0f) deg += 360f
        var delta = deg - startAngle
        if (delta < 0f) delta += 360f
        return when {
            delta <= totalSweep -> (delta / totalSweep).coerceIn(0f, 1f)
            delta > totalSweep + (360f - totalSweep) / 2f -> 0f
            else -> 1f
        }
    }

    Box(
        modifier = modifier
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(shownProgress, 0f..1f)
            }
            .then(
                if (interactiveSeeking) {
                    Modifier
                        .pointerInput(Unit) {
                            detectTapGestures { pos ->
                                val sz = Size(size.width.toFloat(), size.height.toFloat())
                                if (isOnBezelRing(pos, sz)) {
                                    onSeekFraction(offsetToFraction(pos, sz))
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            detectDragGestures(
                                onDragStart = { pos ->
                                    val sz = Size(size.width.toFloat(), size.height.toFloat())
                                    if (isOnBezelRing(pos, sz)) {
                                        dragging = true
                                        dragFraction = offsetToFraction(pos, sz)
                                        onScrubPreview(dragFraction)
                                    }
                                },
                                onDragEnd = {
                                    if (dragging) {
                                        dragging = false
                                        onScrubPreview(null)
                                        onSeekFraction(dragFraction)
                                    }
                                },
                                onDragCancel = {
                                    dragging = false
                                    onScrubPreview(null)
                                },
                                onDrag = { change, _ ->
                                    if (dragging) {
                                        change.consume()
                                        val sz = Size(size.width.toFloat(), size.height.toFloat())
                                        dragFraction = offsetToFraction(change.position, sz)
                                        onScrubPreview(dragFraction)
                                    }
                                }
                            )
                        }
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val outerR = minOf(size.width, size.height) / 2f
            val innerR = outerR * innerArtworkRadiusFraction
            val trackRadius = (outerR + innerR) * 0.5f + 2.dp.toPx()
            val arcTopLeft = Offset(center.x - trackRadius, center.y - trackRadius)
            val arcSize = Size(trackRadius * 2f, trackRadius * 2f)

            // Recessed subtle circular track groove
            drawArc(
                color = OrbitalDesignTokens.Colors.ProgressGroove.copy(alpha = 0.72f),
                startAngle = startAngle,
                sweepAngle = totalSweep,
                useCenter = false,
                topLeft = arcTopLeft,
                size = arcSize,
                style = Stroke(
                    width = OrbitalDesignTokens.Sizing.ProgressGrooveWidth.toPx(),
                    cap = StrokeCap.Round
                )
            )

            // Illuminated ice-blue progress sweep + soft outer aura
            val activeSweep = (totalSweep * shownProgress).coerceAtLeast(if (shownProgress > 0f) 2f else 0f)
            if (activeSweep > 0f) {
                drawArc(
                    color = OrbitalDesignTokens.Colors.ProgressArcGlow.copy(alpha = 0.28f),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(
                        width = (OrbitalDesignTokens.Sizing.ProgressActiveWidth + 4.dp).toPx(),
                        cap = StrokeCap.Round
                    )
                )
                drawArc(
                    brush = Brush.sweepGradient(
                        0.0f to OrbitalDesignTokens.Colors.ProgressArcStart,
                        0.5f to OrbitalDesignTokens.Colors.ProgressArcEnd,
                        1.0f to OrbitalDesignTokens.Colors.ProgressArcStart,
                        center = center
                    ),
                    startAngle = startAngle,
                    sweepAngle = activeSweep,
                    useCenter = false,
                    topLeft = arcTopLeft,
                    size = arcSize,
                    style = Stroke(
                        width = OrbitalDesignTokens.Sizing.ProgressActiveWidth.toPx(),
                        cap = StrokeCap.Round
                    )
                )

                // Subtle luminous tip highlight while scrubbing
                if (dragging) {
                    val endRad = (startAngle + activeSweep) * (PI / 180.0)
                    val tip = Offset(
                        x = center.x + trackRadius * kotlin.math.cos(endRad).toFloat(),
                        y = center.y + trackRadius * kotlin.math.sin(endRad).toFloat()
                    )
                    drawCircle(
                        color = OrbitalDesignTokens.Colors.ProgressArcEnd,
                        radius = 4.dp.toPx(),
                        center = tip
                    )
                }
            }
        }

        // Centered 6 o'clock tabular timestamp nestled cleanly in the progress ring break
        Text(
            text = timeLabel,
            style = OrbitalDesignTokens.Typography.ProgressTime,
            color = OrbitalDesignTokens.Colors.TextPrimary.copy(alpha = 0.90f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 11.dp)
        )
    }
}

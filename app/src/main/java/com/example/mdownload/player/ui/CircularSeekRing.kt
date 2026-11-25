package com.example.mdownload.player.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.mdownload.core.TextUtils
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.localText
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.flow.StateFlow

/**
 * Interactive circular playback progress ring with continuous angle-based drag seeking.
 *
 * Design & interaction guarantees:
 * - Preserves Reference 1's recessed circular groove, 6 o'clock timestamp break, and illuminated
 *   ice-blue sweep arc.
 * - Uses an expanded invisible outer touch band (`0.74R .. 1.22R` relative to [visualBezelDiameter])
 *   strictly separated from the inner circular album cover (`0.72R`) so touches never collide with
 *   center long-press Orbital Navigation.
 * - Dragging clockwise seeks forward; dragging counterclockwise seeks backward with continuous
 *   `[-PI, PI]` angular delta normalization (zero jump at 0°/360° boundary, multi-revolution safe).
 * - Previews target timestamp + subtle `±delta` locally while Media3 playback keeps running;
 *   commits a single `onCommitSeek(positionMs)` call only on clean finger release.
 */
@Composable
fun CircularSeekRing(
    trackKey: String,
    positionMs: Long,
    durationMs: Long,
    isSeekable: Boolean,
    visualBezelDiameter: Dp,
    scrubController: CircularScrubController,
    interactiveSeeking: Boolean,
    innerArtworkRadiusFraction: Float = 0.72f,
    onCommitSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    positionMsFlow: StateFlow<Long>? = null
) {
    val livePositionMs = positionMsFlow?.collectAsState()?.value ?: positionMs
    val latestTrackKeyState = rememberUpdatedState(trackKey)
    val latestPositionMsState = rememberUpdatedState(livePositionMs)
    val latestDurationMsState = rememberUpdatedState(durationMs)
    val latestIsSeekableState = rememberUpdatedState(isSeekable)
    val latestInteractiveState = rememberUpdatedState(interactiveSeeking)
    val latestBezelDiameterState = rememberUpdatedState(visualBezelDiameter)
    val latestOnCommitState = rememberUpdatedState(onCommitSeek)

    val scrubSnapshot = scrubController.snapshot
    val isScrubbing = scrubSnapshot.isScrubbing
    val safeDuration = durationMs.coerceAtLeast(0L)
    val liveFraction = if (safeDuration > 0L) {
        (livePositionMs.toFloat() / safeDuration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val shownFraction = if (isScrubbing) scrubSnapshot.previewFraction else liveFraction
    val shownPositionMs = if (isScrubbing) scrubSnapshot.previewPositionMs else livePositionMs.coerceAtLeast(0L)

    val activeStrokeWidth by animateDpAsState(
        targetValue = if (isScrubbing) {
            OrbitalDesignTokens.Sizing.ProgressActiveWidth + 1.1.dp
        } else {
            OrbitalDesignTokens.Sizing.ProgressActiveWidth
        },
        animationSpec = tween(140),
        label = "seekRingStroke"
    )
    val handleAlpha by animateFloatAsState(
        targetValue = if (isScrubbing) 1f else if (isSeekable && safeDuration > 0L) 0.55f else 0f,
        animationSpec = tween(140),
        label = "seekHandleAlpha"
    )
    val handleRadiusDp by animateDpAsState(
        targetValue = if (isScrubbing) 5.2.dp else 2.6.dp,
        animationSpec = tween(140),
        label = "seekHandleRadius"
    )

    // Clockwise 328° arc leaving a clean 32° break at 6 o'clock (90°) for the centered timestamp
    val startAngle = 106f
    val totalSweep = 328f

    val progressLabel = localText("Playback progress")
    val seekActionLabel = localText("Seek")
    val seekForwardLabel = localText("Seek forward 10 seconds")
    val seekBackwardLabel = localText("Seek backward 10 seconds")
    val unknownDurationLabel = localText("Duration unknown")
    val canAccessibleSeek = interactiveSeeking && isSeekable && safeDuration > 0L

    Box(
        modifier = modifier
            .semantics {
                contentDescription = progressLabel
                stateDescription = if (safeDuration > 0L) {
                    "${TextUtils.formatDurationMs(shownPositionMs)} / ${TextUtils.formatDurationMs(safeDuration)}"
                } else {
                    unknownDurationLabel
                }
                progressBarRangeInfo = if (safeDuration > 0L) {
                    ProgressBarRangeInfo(shownFraction, 0f..1f)
                } else {
                    ProgressBarRangeInfo.Indeterminate
                }
                if (canAccessibleSeek) {
                    setProgress(label = seekActionLabel) { targetFraction ->
                        val dur = latestDurationMsState.value
                        if (!latestInteractiveState.value || !latestIsSeekableState.value || dur <= 0L) {
                            return@setProgress false
                        }
                        val targetMs = CircularScrubEngine.seekPositionForProgressFraction(targetFraction, dur)
                            ?: return@setProgress false
                        latestOnCommitState.value(targetMs)
                        true
                    }
                    customActions = listOf(
                        CustomAccessibilityAction(seekForwardLabel) {
                            val dur = latestDurationMsState.value
                            if (!latestInteractiveState.value || !latestIsSeekableState.value || dur <= 0L) {
                                false
                            } else {
                                val nextMs = (latestPositionMsState.value + 10_000L).coerceIn(0L, dur)
                                latestOnCommitState.value(nextMs)
                                true
                            }
                        },
                        CustomAccessibilityAction(seekBackwardLabel) {
                            val dur = latestDurationMsState.value
                            if (!latestInteractiveState.value || !latestIsSeekableState.value || dur <= 0L) {
                                false
                            } else {
                                val prevMs = (latestPositionMsState.value - 10_000L).coerceIn(0L, dur)
                                latestOnCommitState.value(prevMs)
                                true
                            }
                        }
                    )
                }
            }
            .pointerInput(scrubController) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!latestInteractiveState.value ||
                        !latestIsSeekableState.value ||
                        latestDurationMsState.value <= 0L
                    ) {
                        return@awaitEachGesture
                    }

                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val visualOuterR = (latestBezelDiameterState.value.toPx() / 2f)
                        .coerceAtMost(minOf(size.width, size.height) / 2f)
                    if (!CircularScrubEngine.isInRingTouchZone(
                            x = down.position.x,
                            y = down.position.y,
                            centerX = cx,
                            centerY = cy,
                            outerRadiusPx = visualOuterR,
                            config = scrubController.config
                        )
                    ) {
                        return@awaitEachGesture
                    }

                    val startAngleRad = CircularScrubEngine.angleRadians(
                        x = down.position.x,
                        y = down.position.y,
                        centerX = cx,
                        centerY = cy
                    )
                    val armed = scrubController.beginTouch(
                        trackKey = latestTrackKeyState.value,
                        currentPositionMs = latestPositionMsState.value,
                        durationMs = latestDurationMsState.value,
                        isSeekable = latestIsSeekableState.value,
                        initialAngleRad = startAngleRad
                    )
                    if (!armed) return@awaitEachGesture

                    val pointerId = down.id
                    var cancelled: Boolean

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (!latestInteractiveState.value) {
                            cancelled = true
                            break
                        }
                        val pressedCount = event.changes.count { it.pressed }
                        if (pressedCount > 1) {
                            cancelled = true
                            event.changes.forEach { it.consume() }
                            break
                        }
                        val change = event.changes.firstOrNull { it.id == pointerId }
                        if (change == null) {
                            cancelled = true
                            break
                        }
                        if (!change.pressed) {
                            if (scrubController.isScrubbing) {
                                change.consume()
                            }
                            cancelled = !change.changedToUpIgnoreConsumed()
                            break
                        }

                        val angleRad = CircularScrubEngine.angleRadians(
                            x = change.position.x,
                            y = change.position.y,
                            centerX = cx,
                            centerY = cy
                        )
                        scrubController.updateAngle(angleRad)
                        if (scrubController.isScrubbing) {
                            change.consume()
                        }
                    }

                    if (cancelled || !latestInteractiveState.value) {
                        scrubController.cancel()
                    } else {
                        scrubController.finishAndCommit(latestOnCommitState.value)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Spacer(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    val outerR = (visualBezelDiameter.toPx() / 2f)
                        .coerceAtMost(minOf(size.width, size.height) / 2f)
                    val innerR = outerR * innerArtworkRadiusFraction
                    val trackRadius = (outerR + innerR) * 0.5f + 2.dp.toPx()
                    val center = Offset(size.width / 2f, size.height / 2f)
                    val arcTopLeft = Offset(center.x - trackRadius, center.y - trackRadius)
                    val arcSize = Size(trackRadius * 2f, trackRadius * 2f)
                    val grooveStroke = Stroke(
                        width = OrbitalDesignTokens.Sizing.ProgressGrooveWidth.toPx(),
                        cap = StrokeCap.Round
                    )
                    val sweepBrush = Brush.sweepGradient(
                        0.0f to OrbitalDesignTokens.Colors.ProgressArcStart,
                        0.5f to OrbitalDesignTokens.Colors.ProgressArcEnd,
                        1.0f to OrbitalDesignTokens.Colors.ProgressArcStart,
                        center = center
                    )

                    onDrawBehind {
                        // Recessed subtle circular track groove
                        drawArc(
                            color = OrbitalDesignTokens.Colors.ProgressGroove.copy(
                                alpha = if (isScrubbing) 0.92f else 0.72f
                            ),
                            startAngle = startAngle,
                            sweepAngle = totalSweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = grooveStroke
                        )

                        // Illuminated ice-blue progress sweep + soft outer aura
                        val activeSweep = (totalSweep * shownFraction)
                            .coerceAtLeast(if (shownFraction > 0f) 2f else 0f)
                        if (activeSweep > 0f) {
                            drawArc(
                                color = OrbitalDesignTokens.Colors.ProgressArcGlow.copy(
                                    alpha = if (isScrubbing) 0.42f else 0.28f
                                ),
                                startAngle = startAngle,
                                sweepAngle = activeSweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(
                                    width = (activeStrokeWidth + 4.5.dp).toPx(),
                                    cap = StrokeCap.Round
                                )
                            )
                            drawArc(
                                brush = sweepBrush,
                                startAngle = startAngle,
                                sweepAngle = activeSweep,
                                useCenter = false,
                                topLeft = arcTopLeft,
                                size = arcSize,
                                style = Stroke(
                                    width = activeStrokeWidth.toPx(),
                                    cap = StrokeCap.Round
                                )
                            )
                        }

                        // Subtle draggable handle at the active progress arc tip (expands softly while scrubbing)
                        if (handleAlpha > 0.01f) {
                            val endRad = (startAngle + activeSweep) * (PI / 180.0)
                            val tip = Offset(
                                x = center.x + trackRadius * cos(endRad).toFloat(),
                                y = center.y + trackRadius * sin(endRad).toFloat()
                            )
                            if (isScrubbing) {
                                drawCircle(
                                    color = OrbitalDesignTokens.Colors.ProgressArcGlow.copy(alpha = 0.36f * handleAlpha),
                                    radius = (handleRadiusDp + 5.dp).toPx(),
                                    center = tip
                                )
                            }
                            drawCircle(
                                color = OrbitalDesignTokens.Colors.ProgressArcEnd.copy(alpha = handleAlpha),
                                radius = handleRadiusDp.toPx(),
                                center = tip
                            )
                            if (isScrubbing) {
                                drawCircle(
                                    color = Color.White.copy(alpha = 0.92f * handleAlpha),
                                    radius = (handleRadiusDp * 0.48f).toPx(),
                                    center = tip
                                )
                            }
                        }
                    }
                }
        )

        // Centered 6 o'clock tabular timestamp + subtle live ±delta readout anchored to visual bezel bottom
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier.padding(top = visualBezelDiameter - 42.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    AnimatedVisibility(
                        visible = isScrubbing && scrubSnapshot.deltaFromOriginMs != 0L,
                        enter = fadeIn(tween(110)),
                        exit = fadeOut(tween(110))
                    ) {
                        Text(
                            text = CircularScrubEngine.formatDeltaMs(scrubSnapshot.deltaFromOriginMs),
                            style = OrbitalDesignTokens.Typography.ProgressTime.copy(fontSize = 10.sp),
                            color = OrbitalDesignTokens.Colors.ProgressArcEnd.copy(alpha = 0.94f)
                        )
                    }
                    Text(
                        text = TextUtils.formatDurationMs(shownPositionMs),
                        style = OrbitalDesignTokens.Typography.ProgressTime,
                        color = if (isScrubbing) {
                            OrbitalDesignTokens.Colors.TextPrimary
                        } else {
                            OrbitalDesignTokens.Colors.TextPrimary.copy(alpha = 0.90f)
                        }
                    )
                }
            }
        }
    }
}

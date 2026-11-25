package com.example.mdownload.player.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.hypot

/**
 * Stateful Compose controller coordinating the Orbital Navigation gesture state machine,
 * spring morph animation (`orbitProgress`), spring snap offset (`animatedFocusIndex`),
 * press-hold feedback halo (`holdProgress`), and safe cancellation/commit lifecycle.
 */
@Stable
class OrbitalGestureController(
    private val scope: CoroutineScope,
    private val haptics: HapticFeedback
) {
    var session by mutableStateOf(OrbitalSessionSnapshot())
        private set

    /** `0f` = Normal Player, `1f` = Expanded Orbital Navigator. */
    val orbitProgress = Animatable(0f)

    /** Continuously interpolated focal track index for smooth orbital item motion. */
    val animatedFocusIndex = Animatable(0f)

    /** `0f..1f` subtle center halo fill while holding the artwork prior to long-press trigger. */
    var holdProgress by mutableFloatStateOf(0f)
        private set

    private var morphJob: Job? = null
    private var snapJob: Job? = null
    private var holdFeedbackJob: Job? = null

    val isOrbitVisible: Boolean
        get() = session.state.isOrbitActive || orbitProgress.value > 0.005f

    fun startHoldFeedback(durationMs: Long) {
        holdFeedbackJob?.cancel()
        holdProgress = 0f
        holdFeedbackJob = scope.launch {
            val anim = Animatable(0f)
            anim.animateTo(
                targetValue = 1f,
                animationSpec = tween(durationMillis = durationMs.toInt().coerceIn(150, 900))
            ) {
                holdProgress = value
            }
        }
    }

    fun clearHoldFeedback() {
        holdFeedbackJob?.cancel()
        holdFeedbackJob = null
        holdProgress = 0f
    }

    /** Enters Orbital Navigation Mode from either long-press gesture or accessible menu action. */
    fun enterOrbit(queueSize: Int, currentNavIndex: Int, accessibleMode: Boolean) {
        clearHoldFeedback()
        if (queueSize <= 0) return

        val started = OrbitalSelectionEngine.startSession(
            queueSize = queueSize,
            currentIndex = currentNavIndex,
            accessibleMode = accessibleMode
        )
        session = started
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)

        snapJob?.cancel()
        morphJob?.cancel()
        scope.launch {
            animatedFocusIndex.snapTo(started.continuousFocus)
        }
        morphJob = scope.launch {
            orbitProgress.animateTo(1f, OrbitalDesignTokens.Motion.OrbitMorphSpring)
            if (session.state == OrbitalGestureState.ENTERING_ORBIT) {
                session = session.copy(
                    state = if (accessibleMode) {
                        OrbitalGestureState.ORBIT_SELECTING
                    } else {
                        OrbitalGestureState.ORBIT_DRAGGING
                    }
                )
            }
        }
    }

    /** Applies a vertical drag increment while finger is pressed (up = next, down = previous). */
    fun onVerticalDrag(dragDeltaYPx: Float, stepDistancePx: Float) {
        if (!session.state.isOrbitActive || session.queueSize <= 0) return
        val prevSelected = session.selectedIndex
        val updated = OrbitalSelectionEngine.applyVerticalDrag(
            snapshot = session,
            dragDeltaYPx = dragDeltaYPx,
            stepDistancePx = stepDistancePx
        )
        session = updated
        if (updated.selectedIndex != prevSelected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        snapJob?.cancel()
        scope.launch {
            animatedFocusIndex.snapTo(updated.continuousFocus)
        }
    }

    /** Settles visual focus onto the closest selected track with a natural spring (used after accessible drag). */
    fun settleToSelected() {
        if (!session.state.isOrbitActive) return
        val target = session.selectedIndex.toFloat()
        session = session.copy(
            state = OrbitalGestureState.ORBIT_SELECTING,
            continuousFocus = target
        )
        snapJob?.cancel()
        snapJob = scope.launch {
            animatedFocusIndex.animateTo(target, OrbitalDesignTokens.Motion.OrbitSnapSpring)
        }
    }

    /** Steps forward or backward by one track (accessible controls & TalkBack). */
    fun stepSelection(delta: Int) {
        if (!session.state.isOrbitActive) return
        val prevSelected = session.selectedIndex
        val updated = OrbitalSelectionEngine.stepSelection(session, delta)
        session = updated
        if (updated.selectedIndex != prevSelected) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
        snapJob?.cancel()
        snapJob = scope.launch {
            animatedFocusIndex.animateTo(
                updated.selectedIndex.toFloat(),
                OrbitalDesignTokens.Motion.OrbitSnapSpring
            )
        }
    }

    /** Focuses a tapped cover in accessible mode (or commits if already selected). */
    fun tapOrbitIndex(targetIndex: Int, onCommitNavIndex: (Int) -> Unit) {
        if (!session.state.isOrbitActive) return
        if (targetIndex == session.selectedIndex) {
            commitAndExit(onCommitNavIndex)
            return
        }
        val updated = OrbitalSelectionEngine.selectExplicit(session, targetIndex)
        session = updated
        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        snapJob?.cancel()
        snapJob = scope.launch {
            animatedFocusIndex.animateTo(
                updated.selectedIndex.toFloat(),
                OrbitalDesignTokens.Motion.OrbitSnapSpring
            )
        }
    }

    /**
     * Commits the selected track on finger release (or accessible Confirm), preserving playback state
     * and never restarting an unchanged track, then smoothly exits Orbital Mode.
     */
    fun commitAndExit(onCommitNavIndex: (Int) -> Unit) {
        clearHoldFeedback()
        if (!session.state.isOrbitActive) return

        val committedNavIndex = OrbitalSelectionEngine.resolveCommitIndex(session)
        val snapTarget = session.selectedIndex.toFloat()
        session = session.copy(
            state = OrbitalGestureState.COMMIT_SELECTION,
            continuousFocus = snapTarget
        )

        if (committedNavIndex != null) {
            onCommitNavIndex(committedNavIndex)
        }

        snapJob?.cancel()
        morphJob?.cancel()
        session = session.copy(state = OrbitalGestureState.EXITING_ORBIT)
        morphJob = scope.launch {
            launch {
                animatedFocusIndex.animateTo(snapTarget, OrbitalDesignTokens.Motion.OrbitSnapSpring)
            }
            orbitProgress.animateTo(0f, OrbitalDesignTokens.Motion.OrbitMorphSpring)
            session = OrbitalSessionSnapshot(state = OrbitalGestureState.IDLE)
        }
    }

    /**
     * Cancels an active orbital session safely (Android gesture cancel, multi-touch,
     * app backgrounding, Back press, or empty queue) WITHOUT changing the playing track.
     */
    fun cancelOrbit() {
        clearHoldFeedback()
        if (!session.state.isOrbitActive && orbitProgress.value <= 0.005f) return

        val restoreTarget = session.originalIndex.toFloat()
        session = session.copy(
            state = OrbitalGestureState.EXITING_ORBIT,
            selectedIndex = session.originalIndex,
            continuousFocus = restoreTarget
        )
        snapJob?.cancel()
        morphJob?.cancel()
        morphJob = scope.launch {
            launch {
                animatedFocusIndex.animateTo(restoreTarget, OrbitalDesignTokens.Motion.OrbitSnapSpring)
            }
            orbitProgress.animateTo(0f, OrbitalDesignTokens.Motion.OrbitMorphSpring)
            session = OrbitalSessionSnapshot(state = OrbitalGestureState.IDLE)
        }
    }

    /** Reconciles indices safely if items are added/removed in Media3 while orbital mode is active. */
    fun reconcileQueue(newQueueSize: Int, newCurrentNavIndex: Int) {
        if (!session.state.isOrbitActive) return
        if (newQueueSize <= 0) {
            cancelOrbit()
            return
        }
        val reconciled = OrbitalSelectionEngine.reconcileQueueChange(
            snapshot = session,
            newQueueSize = newQueueSize,
            newCurrentIndex = newCurrentNavIndex
        )
        session = reconciled
        scope.launch {
            animatedFocusIndex.snapTo(reconciled.continuousFocus)
        }
    }
}

@Composable
fun rememberOrbitalGestureController(): OrbitalGestureController {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    return remember(scope, haptics) {
        OrbitalGestureController(scope, haptics)
    }
}

/**
 * Primary long-press-and-drag pointer recognizer attached directly to the central circular album artwork.
 *
 * - Uses Android platform [androidx.compose.ui.platform.ViewConfiguration.longPressTimeoutMillis]
 *   and `touchSlop` rather than hardcoded timeouts.
 * - Captures the active pointer seamlessly on long press so the user can immediately drag
 *   vertically without lifting their finger.
 * - Cancels safely on pointer cancellation, multi-touch interruption, or empty queue.
 * - Short taps never accidentally trigger play/pause.
 */
fun Modifier.orbitalArtworkLongPressDrag(
    enabled: Boolean,
    controller: OrbitalGestureController,
    queueSizeProvider: () -> Int,
    currentNavIndexProvider: () -> Int,
    stepDistancePxProvider: () -> Float,
    onCommitNavIndex: (Int) -> Unit,
    hitZoneFilter: ((Offset, IntSize) -> Boolean)? = null
): Modifier = pointerInput(enabled, controller, hitZoneFilter) {
    if (!enabled) return@pointerInput

    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (hitZoneFilter != null && !hitZoneFilter(down.position, size)) {
            return@awaitEachGesture
        }
        val queueSize = queueSizeProvider()
        if (queueSize <= 0) return@awaitEachGesture

        val longPressTimeout = viewConfiguration.longPressTimeoutMillis
        val touchSlop = viewConfiguration.touchSlop
        val activePointerId = down.id
        var lastPosition = down.position

        controller.startHoldFeedback(longPressTimeout)

        // Phase 1: Wait for platform long-press timeout while ensuring pointer stays down & still
        var longPressAchieved = false
        var abortedBeforeLongPress = false

        try {
            withTimeout(longPressTimeout) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    // Multi-touch interruption cancels safely
                    if (event.changes.count { it.pressed } > 1) {
                        abortedBeforeLongPress = true
                        break
                    }
                    val activeChange = event.changes.firstOrNull { it.id == activePointerId }
                    if (activeChange == null || activeChange.changedToUpIgnoreConsumed() || !activeChange.pressed) {
                        abortedBeforeLongPress = true
                        break
                    }
                    val moved = hypot(
                        activeChange.position.x - down.position.x,
                        activeChange.position.y - down.position.y
                    )
                    if (moved > touchSlop * 1.4f) {
                        abortedBeforeLongPress = true
                        break
                    }
                    lastPosition = activeChange.position
                }
            }
        } catch (_: PointerEventTimeoutCancellationException) {
            longPressAchieved = true
        }

        if (!longPressAchieved || abortedBeforeLongPress) {
            controller.clearHoldFeedback()
            return@awaitEachGesture
        }

        // Phase 2: Enter Orbital Navigation Mode and capture the active gesture
        val liveQueueSize = queueSizeProvider()
        if (liveQueueSize <= 0) {
            controller.clearHoldFeedback()
            return@awaitEachGesture
        }
        controller.enterOrbit(
            queueSize = liveQueueSize,
            currentNavIndex = currentNavIndexProvider(),
            accessibleMode = false
        )

        // Phase 3: Continuous vertical drag while pointer stays pressed
        var cancelled: Boolean
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Main)
            val pressedPointers = event.changes.filter { it.pressed }
            // Multi-touch interruption cancels cleanly without changing track
            if (pressedPointers.size > 1) {
                cancelled = true
                event.changes.forEach { it.consume() }
                break
            }
            val activeChange = event.changes.firstOrNull { it.id == activePointerId }
            if (activeChange == null) {
                cancelled = true
                break
            }
            if (!activeChange.pressed) {
                activeChange.consume()
                // Distinguish clean finger-up release from system gesture cancellation
                cancelled = !activeChange.changedToUpIgnoreConsumed()
                break
            }
            val dy = activeChange.position.y - lastPosition.y
            lastPosition = activeChange.position
            activeChange.consume()
            if (dy != 0f) {
                controller.onVerticalDrag(
                    dragDeltaYPx = dy,
                    stepDistancePx = stepDistancePxProvider()
                )
            }
        }

        // Phase 4: Commit or cancel cleanly
        if (cancelled || !controller.session.state.isOrbitActive) {
            controller.cancelOrbit()
        } else {
            controller.commitAndExit(onCommitNavIndex)
        }
    }
}

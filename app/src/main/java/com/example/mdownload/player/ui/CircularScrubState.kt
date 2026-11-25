package com.example.mdownload.player.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToLong

enum class CircularScrubPhase {
    IDLE,
    ARMED,
    SCRUBBING,
    CANCELLED;

    val isTracking: Boolean
        get() = this == ARMED || this == SCRUBBING
}

/**
 * Polar hit-test region classification for the circular album artwork + seek ring composite (UI-01).
 * Strictly evaluated via Euclidean radius `hypot(dx, dy)` rather than square bounding boxes so
 * diagonal quadrants (45°, 135°, 225°, 315°) never exhibit dead zones.
 */
enum class CircularDiscHitZone {
    INNER_ARTWORK,
    SEEK_RING,
    OUTSIDE
}

/**
 * Tuning configuration for continuous circular drag seeking.
 *
 * - [innerTouchRadiusFraction] and [outerTouchRadiusFraction] define an expanded invisible outer
 *   touch ring strictly separated from the inner circular album artwork (`0.72f`) so Orbital
 *   Navigation and Circular Seeking never compete for the same touches.
 */
data class CircularScrubConfig(
    val innerTouchRadiusFraction: Float = 0.74f,
    val outerTouchRadiusFraction: Float = 1.22f,
    val minActivationArcRad: Double = 0.035,
    val noiseFloorRad: Double = 0.002,
    val sensitivityMultiplier: Float = 1.0f,
    val msPerFullRevolutionOverride: Long? = null,
    val minMsPerRevolution: Long = 15_000L,
    val maxAutoMsPerRevolution: Long = 300_000L
)

data class CircularScrubSnapshot(
    val phase: CircularScrubPhase = CircularScrubPhase.IDLE,
    val trackKey: String = "",
    val originPositionMs: Long = 0L,
    val previewPositionExactMs: Double = 0.0,
    val durationMs: Long = 0L,
    val lastAngleRad: Double = 0.0,
    val accumulatedArmRad: Double = 0.0,
    val totalAngleDeltaRad: Double = 0.0
) {
    val isScrubbing: Boolean
        get() = phase == CircularScrubPhase.SCRUBBING

    val previewPositionMs: Long
        get() = previewPositionExactMs.roundToLong().coerceIn(0L, durationMs.coerceAtLeast(0L))

    val deltaFromOriginMs: Long
        get() = previewPositionMs - originPositionMs

    val previewFraction: Float
        get() = if (durationMs > 0L) {
            (previewPositionExactMs / durationMs.toDouble()).toFloat().coerceIn(0f, 1f)
        } else {
            0f
        }
}

data class CircularScrubCommitResult(
    val nextSnapshot: CircularScrubSnapshot,
    val commitPositionMs: Long?
)

/**
 * Pure, framework-independent mathematical engine for continuous angle-based circular scrubbing.
 *
 * Guarantees:
 * - Zero discontinuity across the `-PI` / `+PI` (0° / 360°) seam via wrapped angular deltas.
 * - Clockwise = forward (+ms), counterclockwise = backward (-ms).
 * - Multi-revolution dragging continues smoothly until clamped to `[0, durationMs]`.
 * - Ignores micro-jitter without losing slow continuous movement.
 * - Guards against unknown/zero duration, unseekable Media3 items, cancellations, and track swaps.
 */
object CircularScrubEngine {

    private const val TWO_PI = 2.0 * PI

    /** Computes screen-space pointer angle relative to disc center in `[-PI, PI]`. */
    fun angleRadians(x: Float, y: Float, centerX: Float, centerY: Float): Double =
        atan2((y - centerY).toDouble(), (x - centerX).toDouble())

    /** Normalizes any angular difference into `[-PI, PI]` so crossing the seam never jumps. */
    fun normalizeAngleDelta(rawDeltaRad: Double): Double {
        var delta = rawDeltaRad % TWO_PI
        if (delta > PI) delta -= TWO_PI
        if (delta < -PI) delta += TWO_PI
        return delta
    }

    /** Returns true when `(x, y)` lies inside the true circular inner album artwork disc (`r <= innerFraction * R`). */
    fun isInInnerArtworkZone(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        outerRadiusPx: Float,
        innerArtworkRadiusFraction: Float = 0.72f
    ): Boolean {
        if (outerRadiusPx <= 0f) return false
        val dist = hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
        return dist <= outerRadiusPx * innerArtworkRadiusFraction
    }

    /** Returns true when `(x, y)` lies within the expanded outer ring touch band. */
    fun isInRingTouchZone(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        outerRadiusPx: Float,
        config: CircularScrubConfig = CircularScrubConfig()
    ): Boolean {
        if (outerRadiusPx <= 0f) return false
        val dist = hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
        val innerBound = outerRadiusPx * config.innerTouchRadiusFraction
        val outerBound = outerRadiusPx * config.outerTouchRadiusFraction
        return dist in innerBound..outerBound
    }

    /**
     * Classifies a touch point `(x, y)` into disjoint circular zones using true polar distance
     * `hypot(x - centerX, y - centerY)` (UI-01).
     */
    fun classifyDiscTouchZone(
        x: Float,
        y: Float,
        centerX: Float,
        centerY: Float,
        outerRadiusPx: Float,
        innerArtworkRadiusFraction: Float = 0.72f,
        config: CircularScrubConfig = CircularScrubConfig()
    ): CircularDiscHitZone {
        if (outerRadiusPx <= 0f) return CircularDiscHitZone.OUTSIDE
        val dist = hypot((x - centerX).toDouble(), (y - centerY).toDouble()).toFloat()
        return when {
            dist <= outerRadiusPx * innerArtworkRadiusFraction -> CircularDiscHitZone.INNER_ARTWORK
            dist in (outerRadiusPx * config.innerTouchRadiusFraction)..(outerRadiusPx * config.outerTouchRadiusFraction) ->
                CircularDiscHitZone.SEEK_RING
            else -> CircularDiscHitZone.OUTSIDE
        }
    }

    /**
     * Minimum square container diameter required to avoid clipping the outer circular touch halo
     * (`2 * outerTouchRadiusFraction * R`).
     */
    fun requiredTouchContainerDiameterPx(
        bezelDiameterPx: Float,
        config: CircularScrubConfig = CircularScrubConfig()
    ): Float = (bezelDiameterPx * config.outerTouchRadiusFraction).coerceAtLeast(bezelDiameterPx)

    /**
     * Converts a semantic accessibility progress fraction (`0f..1f`) into a clamped millisecond
     * seek target (UI-03). Returns `null` when duration is unknown or non-positive.
     */
    fun seekPositionForProgressFraction(fraction: Float, durationMs: Long): Long? {
        if (durationMs <= 0L || !fraction.isFinite()) return null
        val clamped = fraction.coerceIn(0f, 1f)
        return (clamped.toDouble() * durationMs.toDouble()).roundToLong().coerceIn(0L, durationMs)
    }

    /** Milliseconds scrubbed per complete 360° revolution. */
    fun effectiveMsPerRevolution(
        durationMs: Long,
        config: CircularScrubConfig = CircularScrubConfig()
    ): Double {
        val baseMs = config.msPerFullRevolutionOverride?.coerceAtLeast(1_000L)?.toDouble()
            ?: durationMs.coerceIn(config.minMsPerRevolution, config.maxAutoMsPerRevolution).toDouble()
        return baseMs * config.sensitivityMultiplier.coerceIn(0.25f, 4.0f).toDouble()
    }

    /** Arms a potential circular scrub when the finger touches down inside the ring zone. */
    fun beginTouch(
        trackKey: String,
        currentPositionMs: Long,
        durationMs: Long,
        isSeekable: Boolean,
        initialAngleRad: Double
    ): CircularScrubSnapshot {
        if (!isSeekable || durationMs <= 0L) {
            return CircularScrubSnapshot(phase = CircularScrubPhase.IDLE)
        }
        val clampedOrigin = currentPositionMs.coerceIn(0L, durationMs)
        return CircularScrubSnapshot(
            phase = CircularScrubPhase.ARMED,
            trackKey = trackKey,
            originPositionMs = clampedOrigin,
            previewPositionExactMs = clampedOrigin.toDouble(),
            durationMs = durationMs,
            lastAngleRad = initialAngleRad,
            accumulatedArmRad = 0.0,
            totalAngleDeltaRad = 0.0
        )
    }

    /**
     * Advances circular scrubbing with the latest pointer angle.
     * Returns `(updatedSnapshot, enteredScrubbingNow)`.
     */
    fun updateAngle(
        snapshot: CircularScrubSnapshot,
        newAngleRad: Double,
        config: CircularScrubConfig = CircularScrubConfig()
    ): Pair<CircularScrubSnapshot, Boolean> {
        if (!snapshot.phase.isTracking || snapshot.durationMs <= 0L) {
            return snapshot to false
        }

        val deltaRad = normalizeAngleDelta(newAngleRad - snapshot.lastAngleRad)

        if (snapshot.phase == CircularScrubPhase.ARMED) {
            val nextArm = snapshot.accumulatedArmRad + deltaRad
            if (abs(nextArm) < config.minActivationArcRad) {
                return snapshot.copy(
                    lastAngleRad = newAngleRad,
                    accumulatedArmRad = nextArm
                ) to false
            }
            val msPerRad = effectiveMsPerRevolution(snapshot.durationMs, config) / TWO_PI
            val nextExact = (snapshot.previewPositionExactMs + nextArm * msPerRad)
                .coerceIn(0.0, snapshot.durationMs.toDouble())
            return snapshot.copy(
                phase = CircularScrubPhase.SCRUBBING,
                previewPositionExactMs = nextExact,
                lastAngleRad = newAngleRad,
                accumulatedArmRad = 0.0,
                totalAngleDeltaRad = nextArm
            ) to true
        }

        // Active SCRUBBING: ignore sub-threshold sensor jitter without advancing lastAngleRad
        // so very slow intentional motion still accumulates cleanly.
        if (abs(deltaRad) < config.noiseFloorRad) {
            return snapshot to false
        }

        val msPerRad = effectiveMsPerRevolution(snapshot.durationMs, config) / TWO_PI
        val nextExact = (snapshot.previewPositionExactMs + deltaRad * msPerRad)
            .coerceIn(0.0, snapshot.durationMs.toDouble())

        return snapshot.copy(
            previewPositionExactMs = nextExact,
            lastAngleRad = newAngleRad,
            totalAngleDeltaRad = snapshot.totalAngleDeltaRad + deltaRad
        ) to false
    }

    /**
     * Finalizes scrubbing on finger release.
     * Commits a seek target **only** when the gesture actually entered [CircularScrubPhase.SCRUBBING];
     * plain taps on the ring without circular drag commit `null`.
     */
    fun finish(snapshot: CircularScrubSnapshot): CircularScrubCommitResult {
        val commitPos = if (snapshot.phase == CircularScrubPhase.SCRUBBING && snapshot.durationMs > 0L) {
            snapshot.previewPositionMs
        } else {
            null
        }
        return CircularScrubCommitResult(
            nextSnapshot = CircularScrubSnapshot(phase = CircularScrubPhase.IDLE),
            commitPositionMs = commitPos
        )
    }

    /** Cancels any armed or active scrub without emitting a seek commit. */
    fun cancel(): CircularScrubSnapshot =
        CircularScrubSnapshot(phase = CircularScrubPhase.CANCELLED)

    /** Cancels an active scrub cleanly if the active track changes mid-drag. */
    fun reconcileTrackChange(
        snapshot: CircularScrubSnapshot,
        activeTrackKey: String
    ): CircularScrubSnapshot {
        if (!snapshot.phase.isTracking) return snapshot
        return if (snapshot.trackKey != activeTrackKey) {
            CircularScrubSnapshot(phase = CircularScrubPhase.IDLE)
        } else {
            snapshot
        }
    }

    /** Formats a signed scrub delta such as `+0:14`, `-0:08`, or `+1:05`. */
    fun formatDeltaMs(deltaMs: Long): String {
        val sign = when {
            deltaMs > 0L -> "+"
            deltaMs < 0L -> "-"
            else -> ""
        }
        val totalSec = abs(deltaMs) / 1000L
        val mins = totalSec / 60L
        val secs = totalSec % 60L
        return "$sign$mins:${secs.toString().padStart(2, '0')}"
    }
}

/**
 * Stateful Compose controller wrapping [CircularScrubEngine] with subtle haptic confirmation
 * when circular scrubbing activates and clean reset/cancellation hooks.
 */
@Stable
class CircularScrubController(
    private val haptics: HapticFeedback,
    val config: CircularScrubConfig = CircularScrubConfig()
) {
    var snapshot by mutableStateOf(CircularScrubSnapshot())
        private set

    val isScrubbing: Boolean
        get() = snapshot.isScrubbing

    val isTracking: Boolean
        get() = snapshot.phase.isTracking

    fun beginTouch(
        trackKey: String,
        currentPositionMs: Long,
        durationMs: Long,
        isSeekable: Boolean,
        initialAngleRad: Double
    ): Boolean {
        val armed = CircularScrubEngine.beginTouch(
            trackKey = trackKey,
            currentPositionMs = currentPositionMs,
            durationMs = durationMs,
            isSeekable = isSeekable,
            initialAngleRad = initialAngleRad
        )
        snapshot = armed
        return armed.phase == CircularScrubPhase.ARMED
    }

    fun updateAngle(newAngleRad: Double) {
        val (updated, enteredScrubbing) = CircularScrubEngine.updateAngle(
            snapshot = snapshot,
            newAngleRad = newAngleRad,
            config = config
        )
        snapshot = updated
        if (enteredScrubbing) {
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    fun finishAndCommit(onCommitSeek: (Long) -> Unit) {
        val result = CircularScrubEngine.finish(snapshot)
        snapshot = result.nextSnapshot
        result.commitPositionMs?.let(onCommitSeek)
    }

    fun cancel() {
        if (snapshot.phase.isTracking) {
            snapshot = CircularScrubSnapshot(phase = CircularScrubPhase.IDLE)
        }
    }

    fun reconcileTrackChange(activeTrackKey: String) {
        val reconciled = CircularScrubEngine.reconcileTrackChange(snapshot, activeTrackKey)
        if (reconciled != snapshot) {
            snapshot = reconciled
        }
    }
}

@Composable
fun rememberCircularScrubController(
    config: CircularScrubConfig = CircularScrubConfig()
): CircularScrubController {
    val haptics = LocalHapticFeedback.current
    return remember(haptics, config) {
        CircularScrubController(haptics = haptics, config = config)
    }
}

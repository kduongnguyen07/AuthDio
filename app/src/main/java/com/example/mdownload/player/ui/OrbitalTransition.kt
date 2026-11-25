package com.example.mdownload.player.ui

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Explicit gesture & transition state machine for the Orbital Song Navigator.
 */
enum class OrbitalGestureState {
    IDLE,
    ENTERING_ORBIT,
    ORBIT_DRAGGING,
    ORBIT_SELECTING,
    COMMIT_SELECTION,
    EXITING_ORBIT;

    val isOrbitActive: Boolean
        get() = this != IDLE
}

/**
 * Reusable coordinate & visual transform for a single circular cover on the orbital path.
 */
data class OrbitalItemTransform(
    val relativeIndex: Float,
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
    val alpha: Float,
    val zIndex: Float,
    val diameterPx: Float
)

/**
 * Pure, resolution-independent orbital coordinate math derived directly from Reference 2:
 * - Sweeping giant planetary arc bowing smoothly rightward through the focal center (`x ≈ 58.5%`)
 *   and curving back toward the top-left and bottom-left (`x ≈ 24–30%`).
 * - Focal selected cover sits slightly right of center so the selected song label occupies
 *   the clean left side without ever colliding with artwork or screen edges.
 */
object OrbitalGeometry {

    /** Number of tracks composed above and below the visual focal track. */
    const val VISIBLE_NEIGHBOR_RADIUS = 3

    /** Base focal bezel diameter scaled responsively to viewport dimensions (matching Reference 2). */
    fun focalBezelDiameterPx(viewportWidthPx: Float, viewportHeightPx: Float): Float {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f) return 0f
        return min(viewportWidthPx * 0.52f, viewportHeightPx * 0.255f).coerceAtLeast(140f)
    }

    /** Inner circular album artwork diameter for the focal disc. */
    fun focalArtworkDiameterPx(viewportWidthPx: Float, viewportHeightPx: Float): Float =
        focalBezelDiameterPx(viewportWidthPx, viewportHeightPx) * 0.74f

    /** Horizontal center coordinate of the focal selected cover (right-of-center per Reference 2). */
    fun focalCenterX(viewportWidthPx: Float): Float = viewportWidthPx * 0.655f

    /** Vertical center coordinate of the focal selected cover. */
    fun focalCenterY(viewportHeightPx: Float): Float = viewportHeightPx * 0.495f

    /**
     * Left boundary of the focal disc's outer bezel, used to constrain [SelectedSongLabel]
     * so long song titles can never collide with the central circular cover.
     */
    fun focalBezelLeftEdgePx(viewportWidthPx: Float, viewportHeightPx: Float): Float {
        val centerX = focalCenterX(viewportWidthPx)
        val halfBezel = focalBezelDiameterPx(viewportWidthPx, viewportHeightPx) * 0.5f
        return (centerX - halfBezel).coerceAtLeast(0f)
    }

    /** Computes a point `(x, y)` on the sweeping planetary arc for any continuous relative position [relativeIndex]. */
    fun pointOnCurve(
        relativeIndex: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float
    ): Pair<Float, Float> {
        val apexX = focalCenterX(viewportWidthPx)
        val centerY = focalCenterY(viewportHeightPx)
        val arcDepth = viewportWidthPx * 0.39f
        val norm = (relativeIndex / 2.2f).coerceIn(-1.45f, 1.45f)
        val rawX = apexX - arcDepth * (norm * norm)
        val safeX = rawX.coerceIn(viewportWidthPx * 0.14f, viewportWidthPx * 0.78f)

        val absRel = abs(relativeIndex)
        val verticalStep = viewportHeightPx * 0.228f
        val compression = 1f - 0.048f * min(absRel, 2.6f)
        val y = centerY + relativeIndex * verticalStep * compression
        return safeX to y
    }

    /**
     * Evaluates the full [OrbitalItemTransform] for an item at [itemIndex] given the continuous
     * [visualFocusIndex] and current viewport size.
     */
    fun computeTransform(
        itemIndex: Int,
        visualFocusIndex: Float,
        viewportWidthPx: Float,
        viewportHeightPx: Float
    ): OrbitalItemTransform {
        val rel = itemIndex - visualFocusIndex
        val absRel = abs(rel)
        val (x, y) = pointOnCurve(rel, viewportWidthPx, viewportHeightPx)
        val baseDiameter = focalBezelDiameterPx(viewportWidthPx, viewportHeightPx)

        // Smooth scale curve matching Reference 2: 1.00 (center) -> 0.56 (±1) -> 0.36 (±2) -> 0.22 (±3)
        val scale = when {
            absRel <= 1f -> lerp(1.00f, 0.56f, easeOutCurve(absRel))
            absRel <= 2f -> lerp(0.56f, 0.36f, absRel - 1f)
            absRel <= 3f -> lerp(0.36f, 0.22f, absRel - 2f)
            else -> 0.18f
        }

        // Smooth opacity curve: focal item is brightest, neighbors step down gently
        val alpha = when {
            absRel <= 1f -> lerp(1.00f, 0.90f, absRel)
            absRel <= 2f -> lerp(0.90f, 0.72f, absRel - 1f)
            absRel <= 2.75f -> lerp(0.72f, 0f, (absRel - 2f) / 0.75f)
            else -> 0f
        }.coerceIn(0f, 1f)

        val zIndex = (10f - absRel).coerceAtLeast(0f)

        return OrbitalItemTransform(
            relativeIndex = rel,
            offsetX = x,
            offsetY = y,
            scale = scale,
            alpha = alpha,
            zIndex = zIndex,
            diameterPx = baseDiameter * scale
        )
    }

    /** Returns only the nearby queue indices visible along the orbit (`±VISIBLE_NEIGHBOR_RADIUS`). */
    fun visibleIndices(queueSize: Int, visualFocusIndex: Float): IntRange {
        if (queueSize <= 0) return IntRange.EMPTY
        val anchor = visualFocusIndex.roundToInt().coerceIn(0, queueSize - 1)
        val start = (anchor - VISIBLE_NEIGHBOR_RADIUS).coerceAtLeast(0)
        val end = (anchor + VISIBLE_NEIGHBOR_RADIUS).coerceAtMost(queueSize - 1)
        return start..end
    }

    /** Samples smooth points along the curved planetary path from top to bottom of the viewport. */
    fun sampleArcPoints(
        viewportWidthPx: Float,
        viewportHeightPx: Float,
        steps: Int = 44
    ): List<Pair<Float, Float>> {
        if (viewportWidthPx <= 0f || viewportHeightPx <= 0f || steps < 2) return emptyList()
        val minRel = -2.75f
        val maxRel = 2.75f
        return List(steps + 1) { i ->
            val t = i.toFloat() / steps.toFloat()
            val rel = lerp(minRel, maxRel, t)
            pointOnCurve(rel, viewportWidthPx, viewportHeightPx)
        }
    }

    private fun easeOutCurve(t: Float): Float {
        val clamped = t.coerceIn(0f, 1f)
        return 1f - (1f - clamped).pow(1.65f)
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t.coerceIn(0f, 1f)
}

/**
 * Pure, deterministic state engine for Orbital drag math, hysteresis, boundary clamping,
 * cancellation, and mid-drag queue mutation safety.
 */
data class OrbitalSessionSnapshot(
    val state: OrbitalGestureState = OrbitalGestureState.IDLE,
    val queueSize: Int = 0,
    val originalIndex: Int = 0,
    val selectedIndex: Int = 0,
    val continuousFocus: Float = 0f,
    val isAccessibleMode: Boolean = false
)

object OrbitalSelectionEngine {

    /**
     * Hysteresis threshold past the 0.5 midpoint required to switch tracks (`0.58` forward/backward).
     * Small finger tremors around a boundary will not cause rapid flickering between adjacent songs.
     */
    const val SWITCH_HYSTERESIS_THRESHOLD = 0.58f

    /** Rubber-band overscroll damping factor when dragging past the first or last track. */
    const val BOUNDARY_OVERSCROLL_DAMPING = 0.22f

    /** Maximum visual overscroll distance in track-index units at queue ends. */
    const val MAX_BOUNDARY_OVERSCROLL = 0.32f

    fun startSession(
        queueSize: Int,
        currentIndex: Int,
        accessibleMode: Boolean
    ): OrbitalSessionSnapshot {
        if (queueSize <= 0) {
            return OrbitalSessionSnapshot(state = OrbitalGestureState.IDLE)
        }
        val safeIdx = currentIndex.coerceIn(0, queueSize - 1)
        return OrbitalSessionSnapshot(
            state = OrbitalGestureState.ENTERING_ORBIT,
            queueSize = queueSize,
            originalIndex = safeIdx,
            selectedIndex = safeIdx,
            continuousFocus = safeIdx.toFloat(),
            isAccessibleMode = accessibleMode
        )
    }

    /**
     * Applies a vertical drag increment [dragDeltaYPx]:
     * - Dragging UP (`dragDeltaYPx < 0`) moves forward to subsequent songs (`+index`).
     * - Dragging DOWN (`dragDeltaYPx > 0`) moves backward to previous songs (`-index`).
     */
    fun applyVerticalDrag(
        snapshot: OrbitalSessionSnapshot,
        dragDeltaYPx: Float,
        stepDistancePx: Float
    ): OrbitalSessionSnapshot {
        if (!snapshot.state.isOrbitActive || snapshot.queueSize <= 0 || stepDistancePx <= 1f) {
            return snapshot
        }
        val indexDelta = -dragDeltaYPx / stepDistancePx
        val maxIdx = (snapshot.queueSize - 1).toFloat()
        val rawNext = snapshot.continuousFocus + indexDelta

        val nextFocus = when {
            rawNext < 0f -> {
                val baseOverscroll = if (snapshot.continuousFocus < 0f) {
                    snapshot.continuousFocus + indexDelta * BOUNDARY_OVERSCROLL_DAMPING
                } else {
                    rawNext * BOUNDARY_OVERSCROLL_DAMPING
                }
                baseOverscroll.coerceIn(-MAX_BOUNDARY_OVERSCROLL, maxIdx)
            }
            rawNext > maxIdx -> {
                val baseOverscroll = if (snapshot.continuousFocus > maxIdx) {
                    snapshot.continuousFocus + indexDelta * BOUNDARY_OVERSCROLL_DAMPING
                } else {
                    maxIdx + (rawNext - maxIdx) * BOUNDARY_OVERSCROLL_DAMPING
                }
                baseOverscroll.coerceIn(0f, maxIdx + MAX_BOUNDARY_OVERSCROLL)
            }
            else -> rawNext
        }

        val nextSelected = resolveSelectedWithHysteresis(
            continuousFocus = nextFocus,
            currentSelected = snapshot.selectedIndex,
            queueSize = snapshot.queueSize
        )
        val nextState = if (nextSelected != snapshot.selectedIndex) {
            OrbitalGestureState.ORBIT_SELECTING
        } else {
            OrbitalGestureState.ORBIT_DRAGGING
        }

        return snapshot.copy(
            state = nextState,
            selectedIndex = nextSelected,
            continuousFocus = nextFocus
        )
    }

    /** Steps cleanly by `+1` (next) or `-1` (previous) for accessible TalkBack / button navigation. */
    fun stepSelection(snapshot: OrbitalSessionSnapshot, delta: Int): OrbitalSessionSnapshot {
        if (!snapshot.state.isOrbitActive || snapshot.queueSize <= 0) return snapshot
        val target = (snapshot.selectedIndex + delta).coerceIn(0, snapshot.queueSize - 1)
        return snapshot.copy(
            state = OrbitalGestureState.ORBIT_SELECTING,
            selectedIndex = target,
            continuousFocus = target.toFloat()
        )
    }

    /** Selects an explicit visible index (e.g. tapping an orbital cover in accessible mode). */
    fun selectExplicit(snapshot: OrbitalSessionSnapshot, targetIndex: Int): OrbitalSessionSnapshot {
        if (!snapshot.state.isOrbitActive || snapshot.queueSize <= 0) return snapshot
        val target = targetIndex.coerceIn(0, snapshot.queueSize - 1)
        return snapshot.copy(
            state = OrbitalGestureState.ORBIT_SELECTING,
            selectedIndex = target,
            continuousFocus = target.toFloat()
        )
    }

    /**
     * Resolves the committed index on release.
     * Returns `null` if the user released on the original song (so playback must not restart),
     * or the new index if selection changed.
     */
    fun resolveCommitIndex(snapshot: OrbitalSessionSnapshot): Int? {
        if (snapshot.queueSize <= 0) return null
        val target = snapshot.selectedIndex.coerceIn(0, snapshot.queueSize - 1)
        return if (target != snapshot.originalIndex) target else null
    }

    /** Safely reconciles an active orbital session if the playback queue mutates mid-drag. */
    fun reconcileQueueChange(
        snapshot: OrbitalSessionSnapshot,
        newQueueSize: Int,
        newCurrentIndex: Int
    ): OrbitalSessionSnapshot {
        if (!snapshot.state.isOrbitActive) {
            return snapshot.copy(
                queueSize = newQueueSize,
                originalIndex = if (newQueueSize > 0) newCurrentIndex.coerceIn(0, newQueueSize - 1) else 0,
                selectedIndex = if (newQueueSize > 0) newCurrentIndex.coerceIn(0, newQueueSize - 1) else 0,
                continuousFocus = if (newQueueSize > 0) newCurrentIndex.coerceIn(0, newQueueSize - 1).toFloat() else 0f
            )
        }
        if (newQueueSize <= 0) {
            return OrbitalSessionSnapshot(state = OrbitalGestureState.IDLE)
        }
        val clampedOriginal = snapshot.originalIndex.coerceIn(0, newQueueSize - 1)
        val clampedSelected = snapshot.selectedIndex.coerceIn(0, newQueueSize - 1)
        val clampedFocus = snapshot.continuousFocus.coerceIn(0f, (newQueueSize - 1).toFloat())
        return snapshot.copy(
            queueSize = newQueueSize,
            originalIndex = clampedOriginal,
            selectedIndex = clampedSelected,
            continuousFocus = clampedFocus
        )
    }

    fun resolveSelectedWithHysteresis(
        continuousFocus: Float,
        currentSelected: Int,
        queueSize: Int
    ): Int {
        if (queueSize <= 1) return 0
        val clampedCur = currentSelected.coerceIn(0, queueSize - 1)
        val clampedFocus = continuousFocus.coerceIn(0f, (queueSize - 1).toFloat())
        val diff = clampedFocus - clampedCur
        if (abs(diff) < SWITCH_HYSTERESIS_THRESHOLD) {
            return clampedCur
        }
        // When crossing multiple tracks in a fast drag, jump smoothly to the nearest track respecting hysteresis
        return if (diff > 0f) {
            floor(clampedFocus + (1f - SWITCH_HYSTERESIS_THRESHOLD)).toInt().coerceIn(0, queueSize - 1)
        } else {
            kotlin.math.ceil(clampedFocus - (1f - SWITCH_HYSTERESIS_THRESHOLD)).toInt().coerceIn(0, queueSize - 1)
        }
    }
}

package com.example.mdownload

import androidx.compose.ui.graphics.Color
import com.example.mdownload.player.ui.CircularDiscHitZone
import com.example.mdownload.player.ui.CircularScrubConfig
import com.example.mdownload.player.ui.CircularScrubEngine
import com.example.mdownload.player.ui.CircularScrubPhase
import com.example.mdownload.ui.design.ObsidianOrchidColors
import com.example.mdownload.ui.design.PearlOrchidColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/**
 * Phase B (STAB-03) JVM unit verification suite:
 * - UI-01: Polar hit-zone classification across all 9 key angles (0°, 45°, 90°, 135°, 180°, 225°,
 *   270°, 315°, 359°), proving zero diagonal square-corner dead zones and unclipped outer halo.
 * - UI-01: Continuous angle-based scrubbing across 0°/360° seam, multi-revolution clamping, and
 *   track-change cancellation.
 * - UI-03: Accessibility `setProgress` fraction-to-ms conversion and unknown-duration safety.
 * - UI-02: WCAG 2.1 contrast ratio verification for ObsidianOrchidColors (Dark) and
 *   PearlOrchidColors (Light) bottom sheet tokens.
 */
class CircularScrubAndUiP1Test {

    private val keyAnglesDeg = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 359.0)

    private fun pointAt(cx: Float, cy: Float, radius: Float, angleDeg: Double): Pair<Float, Float> {
        val rad = Math.toRadians(angleDeg)
        return (cx + radius * cos(rad).toFloat()) to (cy + radius * sin(rad).toFloat())
    }

    // =========================================================================
    // UI-01: Polar Hit-Zone Classification & Zero Diagonal Dead Zones
    // =========================================================================

    @Test
    fun ui01_polarZoneClassification_zeroDeadZonesAcrossAllAnglesIncludingDiagonals() {
        val config = CircularScrubConfig()
        val outerR = 120f
        val containerSize = CircularScrubEngine.requiredTouchContainerDiameterPx(outerR * 2f, config)
        val cx = containerSize / 2f
        val cy = containerSize / 2f

        for (angle in keyAnglesDeg) {
            // Inner artwork radii: strictly INNER_ARTWORK, never SEEK_RING
            for (frac in listOf(0.0f, 0.35f, 0.719f)) {
                val (x, y) = pointAt(cx, cy, outerR * frac, angle)
                assertEquals(
                    "Expected INNER_ARTWORK at angle=$angle frac=$frac",
                    CircularDiscHitZone.INNER_ARTWORK,
                    CircularScrubEngine.classifyDiscTouchZone(x, y, cx, cy, outerR, 0.72f, config)
                )
                assertTrue(CircularScrubEngine.isInInnerArtworkZone(x, y, cx, cy, outerR, 0.72f))
                assertFalse(CircularScrubEngine.isInRingTouchZone(x, y, cx, cy, outerR, config))
            }

            // Neutral separation band (0.73R): neither zone fires
            run {
                val (x, y) = pointAt(cx, cy, outerR * 0.73f, angle)
                assertEquals(
                    "Expected OUTSIDE in separation band at angle=$angle",
                    CircularDiscHitZone.OUTSIDE,
                    CircularScrubEngine.classifyDiscTouchZone(x, y, cx, cy, outerR, 0.72f, config)
                )
                assertFalse(CircularScrubEngine.isInInnerArtworkZone(x, y, cx, cy, outerR, 0.72f))
                assertFalse(CircularScrubEngine.isInRingTouchZone(x, y, cx, cy, outerR, config))
            }

            // Seek ring annulus including former diagonal square-corner occlusion (0.74R..1.018R)
            // and former clipped outer halo (1.12R..1.22R)
            for (frac in listOf(0.75f, 0.85f, 0.95f, 1.00f, 1.018f, 1.15f, 1.219f)) {
                val (x, y) = pointAt(cx, cy, outerR * frac, angle)
                assertEquals(
                    "Expected SEEK_RING at angle=$angle frac=$frac",
                    CircularDiscHitZone.SEEK_RING,
                    CircularScrubEngine.classifyDiscTouchZone(x, y, cx, cy, outerR, 0.72f, config)
                )
                assertTrue(
                    "Ring touch must be inside container bounds at angle=$angle frac=$frac",
                    x in 0f..containerSize && y in 0f..containerSize
                )
                assertTrue(CircularScrubEngine.isInRingTouchZone(x, y, cx, cy, outerR, config))
                assertFalse(CircularScrubEngine.isInInnerArtworkZone(x, y, cx, cy, outerR, 0.72f))
            }

            // Beyond outer halo: OUTSIDE
            run {
                val (x, y) = pointAt(cx, cy, outerR * 1.25f, angle)
                assertEquals(
                    "Expected OUTSIDE beyond halo at angle=$angle",
                    CircularDiscHitZone.OUTSIDE,
                    CircularScrubEngine.classifyDiscTouchZone(x, y, cx, cy, outerR, 0.72f, config)
                )
            }
        }
    }

    @Test
    fun ui01_requiredTouchContainerDiameter_preventsOuterHaloClipping() {
        val config = CircularScrubConfig(outerTouchRadiusFraction = 1.22f)
        val bezelDiameter = 250f
        val required = CircularScrubEngine.requiredTouchContainerDiameterPx(bezelDiameter, config)
        assertEquals(305f, required, 1e-3f)
        // Must strictly exceed old hardcoded bezelDiameter + 36px/dp clipping
        assertTrue(required >= bezelDiameter * config.outerTouchRadiusFraction)
    }

    @Test
    fun ui01_continuousScrub_allNineAnglesAndSeamCrossingsAndTrackChange() {
        val config = CircularScrubConfig()
        val durationMs = 240_000L
        val originMs = 120_000L

        for (startDeg in keyAnglesDeg) {
            val startRad = Math.toRadians(startDeg)
            var snap = CircularScrubEngine.beginTouch(
                trackKey = "track_1",
                currentPositionMs = originMs,
                durationMs = durationMs,
                isSeekable = true,
                initialAngleRad = startRad
            )
            assertEquals(CircularScrubPhase.ARMED, snap.phase)

            // Drag +30° clockwise in 10 smooth 3° increments (crosses 359° -> 0° cleanly!)
            for (step in 1..10) {
                val deg = (startDeg + step * 3.0) % 360.0
                val rad = Math.toRadians(deg)
                val (next, _) = CircularScrubEngine.updateAngle(snap, rad, config)
                snap = next
            }
            assertEquals("Expected SCRUBBING for startDeg=$startDeg", CircularScrubPhase.SCRUBBING, snap.phase)
            val commit = CircularScrubEngine.finish(snap)
            assertNotNull("Expected non-null commit for startDeg=$startDeg", commit.commitPositionMs)
            // +30° out of 360° = +1/12 of 240s = +20s => ~140_000ms
            assertEquals(
                "Unexpected clockwise scrub delta from startDeg=$startDeg",
                140_000.0,
                commit.commitPositionMs!!.toDouble(),
                600.0
            )
        }

        // Counterclockwise across 0° -> 359° seam
        var ccwSnap = CircularScrubEngine.beginTouch(
            trackKey = "track_1",
            currentPositionMs = originMs,
            durationMs = durationMs,
            isSeekable = true,
            initialAngleRad = Math.toRadians(15.0)
        )
        for (step in 1..10) {
            val deg = (15.0 - step * 3.0 + 360.0) % 360.0
            ccwSnap = CircularScrubEngine.updateAngle(ccwSnap, Math.toRadians(deg), config).first
        }
        val ccwCommit = CircularScrubEngine.finish(ccwSnap).commitPositionMs
        assertNotNull(ccwCommit)
        assertEquals(100_000.0, ccwCommit!!.toDouble(), 600.0)

        // Track change mid-drag cancels cleanly without emitting stale seek
        var activeSnap = CircularScrubEngine.beginTouch(
            trackKey = "track_A",
            currentPositionMs = 60_000L,
            durationMs = durationMs,
            isSeekable = true,
            initialAngleRad = 0.0
        )
        activeSnap = CircularScrubEngine.updateAngle(activeSnap, PI / 6.0, config).first
        assertTrue(activeSnap.isScrubbing)
        val reconciled = CircularScrubEngine.reconcileTrackChange(activeSnap, "track_B")
        assertEquals(CircularScrubPhase.IDLE, reconciled.phase)
        assertNull(CircularScrubEngine.finish(reconciled).commitPositionMs)
    }

    // =========================================================================
    // UI-03: Accessibility Progress Fraction Seek Mapping
    // =========================================================================

    @Test
    fun ui03_seekPositionForProgressFraction_clampsAndGuardsUnknownDuration() {
        assertEquals(0L, CircularScrubEngine.seekPositionForProgressFraction(0f, 180_000L))
        assertEquals(90_000L, CircularScrubEngine.seekPositionForProgressFraction(0.5f, 180_000L))
        assertEquals(180_000L, CircularScrubEngine.seekPositionForProgressFraction(1f, 180_000L))
        assertEquals(0L, CircularScrubEngine.seekPositionForProgressFraction(-0.5f, 180_000L))
        assertEquals(180_000L, CircularScrubEngine.seekPositionForProgressFraction(1.7f, 180_000L))

        assertNull(CircularScrubEngine.seekPositionForProgressFraction(Float.NaN, 180_000L))
        assertNull(CircularScrubEngine.seekPositionForProgressFraction(Float.POSITIVE_INFINITY, 180_000L))
        assertNull(CircularScrubEngine.seekPositionForProgressFraction(Float.NEGATIVE_INFINITY, 180_000L))
        assertNull(CircularScrubEngine.seekPositionForProgressFraction(0.5f, 0L))
        assertNull(CircularScrubEngine.seekPositionForProgressFraction(0.5f, -5_000L))
    }

    // =========================================================================
    // UI-02: WCAG 2.1 Contrast Verification for Dark & Light Bottom Sheets
    // =========================================================================

    private fun relativeLuminance(color: Color): Double {
        fun linearize(c: Float): Double =
            if (c <= 0.03928f) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
        return 0.2126 * linearize(color.red) +
            0.7152 * linearize(color.green) +
            0.0722 * linearize(color.blue)
    }

    private fun contrastRatio(fg: Color, bg: Color): Double {
        val l1 = relativeLuminance(fg)
        val l2 = relativeLuminance(bg)
        val hi = maxOf(l1, l2)
        val lo = minOf(l1, l2)
        return (hi + 0.05) / (lo + 0.05)
    }

    @Test
    fun ui02_bottomSheetTokens_meetWcagAAInBothDarkAndLightThemes() {
        // Surface polarity & full opacity checks
        assertEquals(1f, ObsidianOrchidColors.surfaceElevated.alpha, 1e-4f)
        assertEquals(1f, PearlOrchidColors.surfaceElevated.alpha, 1e-4f)
        assertTrue(
            "Dark surfaceElevated must be dark",
            relativeLuminance(ObsidianOrchidColors.surfaceElevated) < 0.05
        )
        assertTrue(
            "Light surfaceElevated must be light",
            relativeLuminance(PearlOrchidColors.surfaceElevated) > 0.85
        )

        // Dark theme bottom sheet contrast assertions (WCAG AA >= 4.5:1)
        val darkBg = ObsidianOrchidColors.surfaceElevated
        val darkPairs = mapOf(
            "dark inkPrimary" to ObsidianOrchidColors.inkPrimary,
            "dark inkSecondary" to ObsidianOrchidColors.inkSecondary,
            "dark sheetAccent (accentBright)" to ObsidianOrchidColors.accentBright,
            "dark error" to ObsidianOrchidColors.error,
            "dark success" to ObsidianOrchidColors.success
        )
        for ((name, fg) in darkPairs) {
            val ratio = contrastRatio(fg, darkBg)
            assertTrue("$name contrast ($ratio) must meet WCAG AA >= 4.5:1", ratio >= 4.5)
        }

        // Light theme bottom sheet contrast assertions (WCAG AA >= 4.5:1)
        val lightBg = PearlOrchidColors.surfaceElevated
        val lightPairs = mapOf(
            "light inkPrimary" to PearlOrchidColors.inkPrimary,
            "light inkSecondary" to PearlOrchidColors.inkSecondary,
            "light sheetAccent (accent)" to PearlOrchidColors.accent,
            "light error" to PearlOrchidColors.error,
            "light success" to PearlOrchidColors.success
        )
        for ((name, fg) in lightPairs) {
            val ratio = contrastRatio(fg, lightBg)
            assertTrue("$name contrast ($ratio) must meet WCAG AA >= 4.5:1", ratio >= 4.5)
        }
    }
}

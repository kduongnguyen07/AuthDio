package com.example.mdownload

import com.example.mdownload.player.ui.CircularScrubConfig
import com.example.mdownload.player.ui.CircularScrubEngine
import com.example.mdownload.player.ui.CircularScrubPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class CircularScrubTest {

    private val deterministicConfig = CircularScrubConfig(
        minActivationArcRad = 0.035,
        noiseFloorRad = 0.002,
        sensitivityMultiplier = 1.0f,
        msPerFullRevolutionOverride = 120_000L // 120s per full 360° revolution
    )

    @Test
    fun clockwiseDragIncreasesPreviewPosition() {
        var state = CircularScrubEngine.beginTouch(
            trackKey = "track://1",
            currentPositionMs = 30_000L,
            durationMs = 240_000L,
            isSeekable = true,
            initialAngleRad = 0.0
        )
        assertEquals(CircularScrubPhase.ARMED, state.phase)

        // Quarter revolution clockwise (+PI/2) => +30,000 ms
        val (s1, entered) = CircularScrubEngine.updateAngle(state, PI / 2.0, deterministicConfig)
        state = s1
        assertTrue(entered)
        assertEquals(CircularScrubPhase.SCRUBBING, state.phase)
        assertEquals(60_000L, state.previewPositionMs)
        assertEquals(30_000L, state.deltaFromOriginMs)
    }

    @Test
    fun counterclockwiseDragDecreasesPreviewPosition() {
        var state = CircularScrubEngine.beginTouch(
            trackKey = "track://1",
            currentPositionMs = 90_000L,
            durationMs = 240_000L,
            isSeekable = true,
            initialAngleRad = 0.5
        )
        // Quarter revolution counterclockwise (-PI/2) => -30,000 ms
        val (s1, entered) = CircularScrubEngine.updateAngle(state, 0.5 - PI / 2.0, deterministicConfig)
        state = s1
        assertTrue(entered)
        assertEquals(60_000L, state.previewPositionMs)
        assertEquals(-30_000L, state.deltaFromOriginMs)
    }

    @Test
    fun wrapAroundAcrossPiSeamHasNoJumpOrDiscontinuity() {
        // Test clockwise crossing from +170° (+2.967 rad) to -170° (-2.967 rad): true delta is +20° (+PI/9)
        val deg170 = Math.toRadians(170.0)
        val degNeg170 = Math.toRadians(-170.0)
        val cwDelta = CircularScrubEngine.normalizeAngleDelta(degNeg170 - deg170)
        assertTrue(abs(cwDelta - Math.toRadians(20.0)) < 1e-6)

        // Test counterclockwise crossing from -170° to +170°: true delta is -20°
        val ccwDelta = CircularScrubEngine.normalizeAngleDelta(deg170 - degNeg170)
        assertTrue(abs(ccwDelta - Math.toRadians(-20.0)) < 1e-6)

        var state = CircularScrubEngine.beginTouch(
            trackKey = "track://wrap",
            currentPositionMs = 60_000L,
            durationMs = 240_000L,
            isSeekable = true,
            initialAngleRad = deg170
        )
        // Step across seam clockwise in 20° increments: 170° -> -170° -> -150° (total +40° = +13,333ms)
        state = CircularScrubEngine.updateAngle(state, degNeg170, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, Math.toRadians(-150.0), deterministicConfig).first
        assertEquals(CircularScrubPhase.SCRUBBING, state.phase)
        assertTrue(abs(state.previewPositionMs - 73_333L) <= 2L)

        // Reverse cleanly back across the seam: -150° -> -170° -> +170° (total -40° -> back to 60,000ms)
        state = CircularScrubEngine.updateAngle(state, degNeg170, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, deg170, deterministicConfig).first
        assertTrue(abs(state.previewPositionMs - 60_000L) <= 2L)
    }

    @Test
    fun clampsCleanlyAtZeroAndDurationAndRecoversImmediatelyOnReverse() {
        var state = CircularScrubEngine.beginTouch(
            trackKey = "track://clamp",
            currentPositionMs = 10_000L,
            durationMs = 100_000L,
            isSeekable = true,
            initialAngleRad = 0.0
        )
        // Overshoot past 0 counterclockwise by half revolution (-60s)
        state = CircularScrubEngine.updateAngle(state, -PI / 2.0, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, -PI, deterministicConfig).first
        assertEquals(0L, state.previewPositionMs)

        // Reverse immediately clockwise by +90° (+30s) -> should move cleanly from 0 to 30,000ms
        state = CircularScrubEngine.updateAngle(state, -PI / 2.0, deterministicConfig).first
        assertEquals(30_000L, state.previewPositionMs)

        // Overshoot past duration clockwise
        state = CircularScrubEngine.updateAngle(state, 0.0, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, PI / 2.0, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, PI, deterministicConfig).first
        assertEquals(100_000L, state.previewPositionMs)
    }

    @Test
    fun microJitterAndPlainTapAreIgnoredWithoutSeekCommit() {
        var state = CircularScrubEngine.beginTouch(
            trackKey = "track://jitter",
            currentPositionMs = 45_000L,
            durationMs = 180_000L,
            isSeekable = true,
            initialAngleRad = 1.0
        )
        // Tiny sub-activation jitter (< 0.035 rad)
        val (s1, entered) = CircularScrubEngine.updateAngle(state, 1.01, deterministicConfig)
        state = s1
        assertFalse(entered)
        assertEquals(CircularScrubPhase.ARMED, state.phase)

        // Releasing without crossing activation threshold commits NULL (no accidental tap seek)
        val tapResult = CircularScrubEngine.finish(state)
        assertNull(tapResult.commitPositionMs)
        assertEquals(CircularScrubPhase.IDLE, tapResult.nextSnapshot.phase)

        // Once actively scrubbing, tiny sensor jitter (< 0.002 rad) does not move preview timestamp
        var active = CircularScrubEngine.beginTouch("track://jitter", 45_000L, 180_000L, true, 0.0)
        active = CircularScrubEngine.updateAngle(active, 0.5, deterministicConfig).first
        val stablePos = active.previewPositionMs
        active = CircularScrubEngine.updateAngle(active, 0.5008, deterministicConfig).first
        active = CircularScrubEngine.updateAngle(active, 0.4995, deterministicConfig).first
        assertEquals(stablePos, active.previewPositionMs)
    }

    @Test
    fun cancelAndMidDragTrackChangeNeverCommitSeek() {
        var state = CircularScrubEngine.beginTouch("track://A", 20_000L, 200_000L, true, 0.0)
        state = CircularScrubEngine.updateAngle(state, PI / 2.0, deterministicConfig).first
        assertTrue(state.isScrubbing)

        // Cancel explicitly
        val cancelled = CircularScrubEngine.cancel()
        assertNull(CircularScrubEngine.finish(cancelled).commitPositionMs)

        // Track changes mid-drag -> resets to IDLE so finger release does not seek new track
        val reconciled = CircularScrubEngine.reconcileTrackChange(state, "track://B")
        assertEquals(CircularScrubPhase.IDLE, reconciled.phase)
        assertNull(CircularScrubEngine.finish(reconciled).commitPositionMs)
    }

    @Test
    fun multiRevolutionScrubbingWorksSmoothlyOnLongAudioFiles() {
        // 25-minute track: default config caps auto revolution scale at 5 minutes (300,000ms)
        val longDuration = 25 * 60_000L
        val autoConfig = CircularScrubConfig()
        assertEquals(300_000.0, CircularScrubEngine.effectiveMsPerRevolution(longDuration, autoConfig), 0.1)

        var state = CircularScrubEngine.beginTouch("track://mix", 0L, longDuration, true, 0.0)
        // Perform 3 complete clockwise revolutions in 60° steps (18 steps) => +15 minutes (900,000 ms)
        var angle = 0.0
        repeat(18) {
            angle += PI / 3.0
            val wrapped = CircularScrubEngine.normalizeAngleDelta(angle)
            state = CircularScrubEngine.updateAngle(state, wrapped, autoConfig).first
        }
        assertTrue(abs(state.previewPositionMs - 900_000L) <= 5L)
    }

    @Test
    fun ignoresUnknownZeroDurationAndUnseekableMedia() {
        val zeroDur = CircularScrubEngine.beginTouch("track://z", 0L, 0L, true, 0.0)
        assertEquals(CircularScrubPhase.IDLE, zeroDur.phase)

        val negativeDur = CircularScrubEngine.beginTouch("track://n", 0L, -1L, true, 0.0)
        assertEquals(CircularScrubPhase.IDLE, negativeDur.phase)

        val unseekable = CircularScrubEngine.beginTouch("track://live", 10_000L, 180_000L, false, 0.0)
        assertEquals(CircularScrubPhase.IDLE, unseekable.phase)
    }

    @Test
    fun commitsSingleSeekTargetOnReleaseOnly() {
        var state = CircularScrubEngine.beginTouch("track://commit", 10_000L, 180_000L, true, 0.0)
        val committedTargets = mutableListOf<Long>()

        // Multiple drag updates modify local preview only
        state = CircularScrubEngine.updateAngle(state, PI / 4.0, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, PI / 2.0, deterministicConfig).first
        state = CircularScrubEngine.updateAngle(state, 3.0 * PI / 4.0, deterministicConfig).first
        assertEquals(0, committedTargets.size)

        // Release commits once
        val result = CircularScrubEngine.finish(state)
        result.commitPositionMs?.let { committedTargets.add(it) }
        assertEquals(1, committedTargets.size)
        assertNotNull(result.commitPositionMs)
        assertEquals(55_000L, committedTargets.single())
        assertEquals(CircularScrubPhase.IDLE, result.nextSnapshot.phase)
    }

    @Test
    fun touchZonesStayDisjointFromInnerArtworkAndDeltaFormatsCleanly() {
        val config = CircularScrubConfig()
        val outerR = 150f
        // Center & inner album art (r = 0..108 = 0.72R) MUST be rejected by ring touch zone
        assertFalse(CircularScrubEngine.isInRingTouchZone(150f, 150f, 150f, 150f, outerR, config))
        assertFalse(CircularScrubEngine.isInRingTouchZone(150f + 108f, 150f, 150f, 150f, outerR, config))

        // Outer ring & expanded invisible halo (0.74R..1.22R = 111..183) MUST be accepted
        assertTrue(CircularScrubEngine.isInRingTouchZone(150f + 125f, 150f, 150f, 150f, outerR, config))
        assertTrue(CircularScrubEngine.isInRingTouchZone(150f + 175f, 150f, 150f, 150f, outerR, config))

        // Far outside halo rejected
        assertFalse(CircularScrubEngine.isInRingTouchZone(150f + 220f, 150f, 150f, 150f, outerR, config))

        assertEquals("+0:14", CircularScrubEngine.formatDeltaMs(14_000L))
        assertEquals("-0:08", CircularScrubEngine.formatDeltaMs(-8_000L))
        assertEquals("+1:05", CircularScrubEngine.formatDeltaMs(65_000L))
    }
}

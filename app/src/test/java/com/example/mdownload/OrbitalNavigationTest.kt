package com.example.mdownload

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.playback.RepeatModeOption
import com.example.mdownload.player.ui.OrbitalGeometry
import com.example.mdownload.player.ui.OrbitalGestureState
import com.example.mdownload.player.ui.OrbitalSelectionEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrbitalNavigationTest {

    private fun sampleTracks(count: Int): List<LibraryTrack> =
        List(count) { idx ->
            LibraryTrack(
                id = idx.toLong() + 1L,
                uri = "content://media/external/audio/media/${idx + 1}",
                displayName = "track_${idx + 1}.mp3",
                title = "Track ${idx + 1}",
                artist = "Artist ${idx + 1}",
                album = "Album ${(idx / 3) + 1}",
                durationMs = 180_000L + idx * 10_000L,
                sizeBytes = 4_000_000L,
                dateAddedSec = 1_700_000_000L + idx,
                mimeType = "audio/mpeg"
            )
        }

    @Test
    fun emptyAndSingleSongQueue_handledSafelyWithoutCrashing() {
        val empty = OrbitalSelectionEngine.startSession(queueSize = 0, currentIndex = 0, accessibleMode = false)
        assertEquals(OrbitalGestureState.IDLE, empty.state)
        assertNull(OrbitalSelectionEngine.resolveCommitIndex(empty))
        assertTrue(OrbitalGeometry.visibleIndices(0, 0f).isEmpty())

        val single = OrbitalSelectionEngine.startSession(queueSize = 1, currentIndex = 0, accessibleMode = false)
        assertEquals(OrbitalGestureState.ENTERING_ORBIT, single.state)
        val dragged = OrbitalSelectionEngine.applyVerticalDrag(single, dragDeltaYPx = -400f, stepDistancePx = 150f)
        assertEquals(0, dragged.selectedIndex)
        assertNull(OrbitalSelectionEngine.resolveCommitIndex(dragged))
    }

    @Test
    fun upwardDragAdvancesToNextTracks_downwardDragMovesToPreviousTracks() {
        var session = OrbitalSelectionEngine.startSession(queueSize = 10, currentIndex = 3, accessibleMode = false)

        // Drag upward (negative Y) moves forward (+1)
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = -180f, stepDistancePx = 150f)
        assertEquals(4, session.selectedIndex)

        // Continue dragging upward across multiple tracks in one drag
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = -300f, stepDistancePx = 150f)
        assertEquals(6, session.selectedIndex)

        // Drag downward (positive Y) moves backward (-2)
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = 300f, stepDistancePx = 150f)
        assertEquals(4, session.selectedIndex)
    }

    @Test
    fun smallMovementsWithinHysteresisBand_doNotTriggerAccidentalTrackSwitch() {
        val start = OrbitalSelectionEngine.startSession(queueSize = 8, currentIndex = 2, accessibleMode = false)

        // 0.52 of a step is past 0.50 midpoint but strictly inside 0.58 hysteresis band
        val jitterUp = OrbitalSelectionEngine.applyVerticalDrag(start, dragDeltaYPx = -78f, stepDistancePx = 150f)
        assertEquals(2, jitterUp.selectedIndex)
        assertNull(OrbitalSelectionEngine.resolveCommitIndex(jitterUp))

        val jitterDown = OrbitalSelectionEngine.applyVerticalDrag(start, dragDeltaYPx = 78f, stepDistancePx = 150f)
        assertEquals(2, jitterDown.selectedIndex)
        assertNull(OrbitalSelectionEngine.resolveCommitIndex(jitterDown))

        // Crossing 0.60 of a step cleanly switches to track 3
        val intentional = OrbitalSelectionEngine.applyVerticalDrag(start, dragDeltaYPx = -92f, stepDistancePx = 150f)
        assertEquals(3, intentional.selectedIndex)
        assertEquals(3, OrbitalSelectionEngine.resolveCommitIndex(intentional))
    }

    @Test
    fun releaseWithoutChangingSelection_returnsNullSoPlaybackDoesNotRestart() {
        var session = OrbitalSelectionEngine.startSession(queueSize = 6, currentIndex = 2, accessibleMode = false)
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = -160f, stepDistancePx = 150f)
        assertEquals(3, session.selectedIndex)

        // Drag back to original track 2 before release
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = 160f, stepDistancePx = 150f)
        assertEquals(2, session.selectedIndex)
        assertNull(OrbitalSelectionEngine.resolveCommitIndex(session))
    }

    @Test
    fun boundaryClampingAndMidDragQueueMutation_stayWithinValidBounds() {
        var session = OrbitalSelectionEngine.startSession(queueSize = 4, currentIndex = 0, accessibleMode = false)
        // Dragging downward past index 0 stays clamped at 0 with bounded overscroll
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = 600f, stepDistancePx = 150f)
        assertEquals(0, session.selectedIndex)
        assertTrue(session.continuousFocus >= -OrbitalSelectionEngine.MAX_BOUNDARY_OVERSCROLL - 0.001f)

        // Drag upward past the last track (index 3)
        session = OrbitalSelectionEngine.applyVerticalDrag(session, dragDeltaYPx = -1200f, stepDistancePx = 150f)
        assertEquals(3, session.selectedIndex)
        assertTrue(session.continuousFocus <= 3f + OrbitalSelectionEngine.MAX_BOUNDARY_OVERSCROLL + 0.001f)

        // Mid-drag item removal shrinks queue to 2 items -> indices re-clamp safely
        val shrunk = OrbitalSelectionEngine.reconcileQueueChange(session, newQueueSize = 2, newCurrentIndex = 0)
        assertEquals(1, shrunk.selectedIndex)
        assertEquals(2, shrunk.queueSize)

        // Queue cleared mid-drag -> cleanly returns to IDLE
        val emptied = OrbitalSelectionEngine.reconcileQueueChange(shrunk, newQueueSize = 0, newCurrentIndex = -1)
        assertEquals(OrbitalGestureState.IDLE, emptied.state)
    }

    @Test
    fun playbackUiState_respectsShuffleOrderAndMapsBackToMedia3TimelineIndex() {
        val tracks = sampleTracks(5)
        val shuffleOrder = listOf(2, 4, 0, 3, 1)
        val state = PlaybackUiState(
            currentTrack = tracks[4],
            queue = tracks,
            currentIndex = 4,
            orderedQueueIndices = shuffleOrder,
            shuffleEnabled = true,
            repeatMode = RepeatModeOption.OFF
        )

        assertEquals(listOf("Track 3", "Track 5", "Track 1", "Track 4", "Track 2"), state.navigationQueue.map { it.title })
        assertEquals(1, state.navigationCurrentIndex)
        assertEquals(0, state.timelineIndexForNavigation(2))
        assertEquals(3, state.timelineIndexForNavigation(3))

        // Next songs follow actual shuffle order: Track 1 (timeline 0), Track 4 (timeline 3)
        val upcoming = state.upcomingTracks(limit = 2)
        assertEquals(listOf(0 to "Track 1", 3 to "Track 4"), upcoming.map { it.first to it.second.title })
    }

    @Test
    fun orbitalGeometry_curvesAcrossScreenSizes_leavesSpaceForLeftLabel_andWindowsLargeQueues() {
        val viewports = listOf(
            360f to 640f,
            1080f to 2400f,
            1260f to 2800f,
            1600f to 2560f
        )

        for ((w, h) in viewports) {
            val center = OrbitalGeometry.computeTransform(5, 5f, w, h)
            val prev1 = OrbitalGeometry.computeTransform(4, 5f, w, h)
            val next1 = OrbitalGeometry.computeTransform(6, 5f, w, h)
            val prev2 = OrbitalGeometry.computeTransform(3, 5f, w, h)
            val next2 = OrbitalGeometry.computeTransform(7, 5f, w, h)

            // Selected center item dominates scale, opacity, and z-order
            assertEquals(1.0f, center.scale, 0.001f)
            assertEquals(1.0f, center.alpha, 0.001f)
            assertTrue(center.scale > prev1.scale && prev1.scale > prev2.scale)
            assertTrue(center.zIndex > prev1.zIndex && prev1.zIndex > prev2.zIndex)

            // Genuine sweeping curve bowing rightward at center (not a straight vertical line)
            assertTrue(center.offsetX > prev1.offsetX && prev1.offsetX > prev2.offsetX)
            assertTrue(center.offsetX > next1.offsetX && next1.offsetX > next2.offsetX)

            // Vertical ordering preserved from top to bottom
            assertTrue(prev2.offsetY < prev1.offsetY && prev1.offsetY < center.offsetY)
            assertTrue(center.offsetY < next1.offsetY && next1.offsetY < next2.offsetY)

            // Guaranteed non-overlapping left clearance for SelectedSongLabel
            val leftClearance = OrbitalGeometry.focalBezelLeftEdgePx(w, h)
            assertTrue("Expected positive left label space for ${w}x${h}", leftClearance >= w * 0.25f)
        }

        // Large 500-track queue renders only the small visible window (<= 7 items)
        val visible = OrbitalGeometry.visibleIndices(queueSize = 500, visualFocusIndex = 240.2f)
        assertTrue(visible.count() <= OrbitalGeometry.VISIBLE_NEIGHBOR_RADIUS * 2 + 1)
    }
}

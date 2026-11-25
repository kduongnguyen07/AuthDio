package com.example.mdownload

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.audio.AudioQualityInfo
import com.example.mdownload.core.audio.BitrateMode
import com.example.mdownload.core.metadata.AndroidContentResolverBackend
import com.example.mdownload.core.metadata.AudioContainerPrimitives
import com.example.mdownload.core.metadata.EditableTrackMetadata
import com.example.mdownload.core.metadata.SafeAudioWritePipeline
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.LyricsUiState
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.playback.RepeatModeOption
import com.example.mdownload.player.playback.SleepTimerMode
import com.example.mdownload.player.playback.SleepTimerState
import com.example.mdownload.player.ui.AudioInspectorSheet
import com.example.mdownload.player.ui.AudioInspectorUiState
import com.example.mdownload.player.ui.BatchMetadataEditorSheet
import com.example.mdownload.player.ui.BatchMetadataEditorUiState
import com.example.mdownload.player.ui.CircularAlbumArtwork
import com.example.mdownload.player.ui.CircularScrubEngine
import com.example.mdownload.player.ui.CircularSeekRing
import com.example.mdownload.player.ui.LyricsScreen
import com.example.mdownload.player.ui.MiniPlayer
import com.example.mdownload.player.ui.OrbitalNavigator
import com.example.mdownload.player.ui.PlaybackQueueSheet
import com.example.mdownload.player.ui.PlayerControls
import com.example.mdownload.player.ui.SleepTimerSheet
import com.example.mdownload.player.ui.TrackMetadataEditorSheet
import com.example.mdownload.player.ui.TrackMetadataEditorUiState
import com.example.mdownload.player.ui.orbitalArtworkLongPressDrag
import com.example.mdownload.player.ui.rememberCircularScrubController
import com.example.mdownload.player.ui.rememberOrbitalGestureController
import com.example.mdownload.ui.components.HiFiGlassTextField
import com.example.mdownload.ui.design.HiFiDeckTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.cos
import kotlin.math.sin

/**
 * On-device Phase B (STAB-03) + Phase C (P0 + SEC-03 Coexistence) Instrumented Suite.
 *
 * Verifies on real Android hardware (vivo X200s / OriginOS 7 / Android 17):
 * - UI-01: Full 360° circular ring scrubbing across all 9 key angles (0°, 45°, 90°, 135°, 180°,
 *   225°, 270°, 315°, 359°), seam crossings, outer halo reach, inner artwork Orbital isolation,
 *   multi-touch cancellation, and track-change cancellation.
 * - UI-02: Light and Dark theme rendering of AudioInspectorSheet, SleepTimerSheet,
 *   TrackMetadataEditorSheet, and BatchMetadataEditorSheet without crashes or missing content.
 * - UI-03: CircularSeekRing accessibility semantics (`SetProgress`, forward/backward 10s actions,
 *   unknown duration indeterminate state).
 * - UI-06: Home URL input (`HiFiGlassTextField`) accessibility label & Clear button 48dp target.
 * - UI-07: >= 48.dp touch target bounds across MiniPlayer, PlayerControls, PlaybackQueueSheet,
 *   OrbitalNavigator, and LyricsScreen.
 * - Phase C: SafeAudioWritePipeline rollback & startup recovery under
 *   `filesDir/hifideck_data/pending_writes/` coexists safely with SEC-03 backup exclusion rules.
 */
@RunWith(AndroidJUnit4::class)
class P1UiAndAccessibilityInstrumentedTest {

    @get:Rule
    val composeRule = createEmptyComposeRule()

    private var activeHostActivity: ComponentActivity? = null

    @After
    fun tearDownHostActivity() {
        val host = activeHostActivity ?: return
        activeHostActivity = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            if (!host.isFinishing && !host.isDestroyed) {
                host.finish()
            }
        }
        Thread.sleep(150L)
    }

    /**
     * Launches [ComponentActivity] via shell `am start -W` so vivo OriginOS 7's background-start
     * policy never blocks Espresso's BootstrapActivity, then mounts Compose content directly.
     */
    private fun setHostContent(content: @Composable () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pfd = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -n com.example.mdownload/androidx.activity.ComponentActivity"
        )
        ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() }

        val hostRef = AtomicReference<ComponentActivity?>(null)
        val deadline = System.currentTimeMillis() + 6_000L
        while (System.currentTimeMillis() < deadline && hostRef.get() == null) {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<ComponentActivity>()
                    .lastOrNull { it::class.java == ComponentActivity::class.java }
                hostRef.set(resumed)
            }
            if (hostRef.get() == null) Thread.sleep(50L)
        }
        val host = hostRef.get()
        assertNotNull("ComponentActivity failed to reach RESUMED stage", host)
        activeHostActivity = host!!
        instrumentation.runOnMainSync {
            host.setContent(content = content)
        }
        composeRule.waitForIdle()
    }

    private fun sampleTrack(id: Long = 1L, title: String = "Midnight Signal"): LibraryTrack =
        LibraryTrack(
            id = id,
            uri = "content://media/external/audio/media/$id",
            displayName = "track_$id.flac",
            title = title,
            artist = "HiFi Ensemble",
            album = "Orchid Sessions",
            durationMs = 240_000L,
            sizeBytes = 24_000_000L,
            dateAddedSec = 1_700_000_000L,
            dateModifiedSec = 1_700_000_000L,
            mimeType = "audio/flac"
        )

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    // =========================================================================
    // UI-01: Full 360° Circular Scrubbing (All 9 Angles + Seam + Outer Halo)
    // =========================================================================

    @Test
    fun ui01_circularAlbumArtwork_allNineAnglesIncludingDiagonalsAndOuterHaloCommitSeek() {
        val committedSeeks = mutableListOf<Long>()
        val orbitalCommits = mutableListOf<Int>()

        setHostContent {
            HiFiDeckTheme(darkTheme = true) {
                val scrubController = rememberCircularScrubController()
                val orbitalController = rememberOrbitalGestureController()
                val outerFactor = scrubController.config.outerTouchRadiusFraction
                val innerHitFilter: (Offset, IntSize) -> Boolean = { pos, sz ->
                    val visualOuterR = (minOf(sz.width, sz.height) / 2f) / outerFactor
                    CircularScrubEngine.isInInnerArtworkZone(
                        x = pos.x,
                        y = pos.y,
                        centerX = sz.width / 2f,
                        centerY = sz.height / 2f,
                        outerRadiusPx = visualOuterR,
                        innerArtworkRadiusFraction = 0.72f
                    )
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularAlbumArtwork(
                        trackUri = "track://angle_matrix",
                        albumId = 1L,
                        isPlaying = true,
                        positionMs = 120_000L,
                        durationMs = 240_000L,
                        isSeekable = true,
                        bezelDiameter = 220.dp,
                        scrubController = scrubController,
                        holdFeedbackProgress = orbitalController.holdProgress,
                        interactiveSeeking = true,
                        onCommitSeek = { committedSeeks.add(it) },
                        artworkTouchModifier = Modifier.orbitalArtworkLongPressDrag(
                            enabled = true,
                            controller = orbitalController,
                            queueSizeProvider = { 5 },
                            currentNavIndexProvider = { 1 },
                            stepDistancePxProvider = { 120f },
                            onCommitNavIndex = { orbitalCommits.add(it) },
                            hitZoneFilter = innerHitFilter
                        ),
                        loadArtwork = { _, _, _ -> null },
                        modifier = Modifier.testTag("circular_disc")
                    )
                }
            }
        }

        val node = composeRule.onNodeWithTag("circular_disc")
        val angles = listOf(0.0, 45.0, 90.0, 135.0, 180.0, 225.0, 270.0, 315.0, 359.0)

        fun dragRing(startAngleDeg: Double, sweepDeg: Double, radiusFraction: Float) {
            node.performTouchInput {
                val cx = width / 2f
                val cy = height / 2f
                val visualOuterR = (minOf(width, height) / 2f) / 1.22f
                val r = visualOuterR * radiusFraction
                fun pt(deg: Double): Offset {
                    val rad = Math.toRadians(deg)
                    return Offset(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
                }
                down(pt(startAngleDeg))
                val steps = 10
                for (i in 1..steps) {
                    advanceEventTime(16L)
                    moveTo(pt(startAngleDeg + sweepDeg * i / steps))
                }
                advanceEventTime(16L)
                up()
            }
            composeRule.waitForIdle()
        }

        // 1. All 9 angles at r = 0.86R (squarely inside old 45°/135°/225°/315° square-corner dead zones!)
        for (angle in angles) {
            val before = committedSeeks.size
            dragRing(startAngleDeg = angle, sweepDeg = 30.0, radiusFraction = 0.86f)
            assertEquals("Ring drag must commit seek at startAngle=$angle°", before + 1, committedSeeks.size)
            assertTrue(
                "Clockwise drag at $angle° must seek forward (> 120s), got ${committedSeeks.last()}",
                committedSeeks.last() > 130_000L
            )
        }

        // 2. Former clipped outer halo (r = 1.18R) at diagonal 45°
        run {
            val before = committedSeeks.size
            dragRing(startAngleDeg = 45.0, sweepDeg = 30.0, radiusFraction = 1.18f)
            assertEquals("Outer halo drag at 1.18R / 45° must commit seek", before + 1, committedSeeks.size)
            assertTrue(committedSeeks.last() > 130_000L)
        }

        // 3. Explicit counterclockwise crossing across 0° -> 359° seam (15° -> -15° = 345°)
        run {
            val before = committedSeeks.size
            dragRing(startAngleDeg = 15.0, sweepDeg = -30.0, radiusFraction = 0.90f)
            assertEquals("Counterclockwise seam drag must commit seek", before + 1, committedSeeks.size)
            assertTrue(
                "Counterclockwise drag must seek backward (< 110s), got ${committedSeeks.last()}",
                committedSeeks.last() < 110_000L
            )
        }

        // Orbital navigation must NEVER misfire from any ring touch
        assertTrue("Orbital navigation must not trigger on ring touches", orbitalCommits.isEmpty())
    }

    @Test
    fun ui01_innerArtworkLongPressTriggersOrbital_andMultiTouchAndTrackSwapCancelScrub() {
        val committedSeeks = mutableListOf<Long>()
        val orbitalCommits = mutableListOf<Int>()
        var activeTrackUri by mutableStateOf("track://first")

        setHostContent {
            HiFiDeckTheme(darkTheme = true) {
                val scrubController = rememberCircularScrubController()
                val orbitalController = rememberOrbitalGestureController()
                LaunchedEffect(activeTrackUri) {
                    scrubController.reconcileTrackChange(activeTrackUri)
                }
                val outerFactor = scrubController.config.outerTouchRadiusFraction
                val innerHitFilter: (Offset, IntSize) -> Boolean = { pos, sz ->
                    val visualOuterR = (minOf(sz.width, sz.height) / 2f) / outerFactor
                    CircularScrubEngine.isInInnerArtworkZone(
                        x = pos.x,
                        y = pos.y,
                        centerX = sz.width / 2f,
                        centerY = sz.height / 2f,
                        outerRadiusPx = visualOuterR,
                        innerArtworkRadiusFraction = 0.72f
                    )
                }

                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularAlbumArtwork(
                        trackUri = activeTrackUri,
                        albumId = 1L,
                        isPlaying = true,
                        positionMs = 100_000L,
                        durationMs = 240_000L,
                        isSeekable = true,
                        bezelDiameter = 220.dp,
                        scrubController = scrubController,
                        holdFeedbackProgress = orbitalController.holdProgress,
                        interactiveSeeking = true,
                        onCommitSeek = { committedSeeks.add(it) },
                        artworkTouchModifier = Modifier.orbitalArtworkLongPressDrag(
                            enabled = true,
                            controller = orbitalController,
                            queueSizeProvider = { 5 },
                            currentNavIndexProvider = { 1 },
                            stepDistancePxProvider = { 90f },
                            onCommitNavIndex = { orbitalCommits.add(it) },
                            hitZoneFilter = innerHitFilter
                        ),
                        loadArtwork = { _, _, _ -> null },
                        modifier = Modifier.testTag("circular_disc_cancel")
                    )
                }
            }
        }

        val node = composeRule.onNodeWithTag("circular_disc_cancel")

        // A. Multi-touch interruption mid-scrub cancels cleanly without committing seek
        node.performTouchInput {
            val cx = width / 2f
            val cy = height / 2f
            val visualOuterR = (minOf(width, height) / 2f) / 1.22f
            val r = visualOuterR * 0.90f
            fun pt(deg: Double): Offset {
                val rad = Math.toRadians(deg)
                return Offset(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
            }
            down(pointerId = 0, position = pt(45.0))
            for (i in 1..6) {
                advanceEventTime(16L)
                moveTo(pointerId = 0, position = pt(45.0 + i * 4.0))
            }
            // Second finger interrupts!
            advanceEventTime(16L)
            down(pointerId = 1, position = pt(225.0))
            advanceEventTime(16L)
            up(pointerId = 1)
            up(pointerId = 0)
        }
        composeRule.waitForIdle()
        assertTrue("Multi-touch must cancel scrub without committing seek", committedSeeks.isEmpty())

        // B. Track change mid-drag cancels cleanly without committing seek
        node.performTouchInput {
            val cx = width / 2f
            val cy = height / 2f
            val visualOuterR = (minOf(width, height) / 2f) / 1.22f
            val r = visualOuterR * 0.90f
            fun pt(deg: Double): Offset {
                val rad = Math.toRadians(deg)
                return Offset(cx + r * cos(rad).toFloat(), cy + r * sin(rad).toFloat())
            }
            down(pointerId = 0, position = pt(135.0))
            for (i in 1..6) {
                advanceEventTime(16L)
                moveTo(pointerId = 0, position = pt(135.0 + i * 4.0))
            }
        }
        composeRule.runOnIdle { activeTrackUri = "track://second" }
        composeRule.waitForIdle()
        node.performTouchInput {
            advanceEventTime(16L)
            up(pointerId = 0)
        }
        composeRule.waitForIdle()
        assertTrue("Track change mid-drag must cancel scrub without committing seek", committedSeeks.isEmpty())

        // C. Inner artwork long-press + upward drag enters Orbital mode and selects next track (index 2)
        node.performTouchInput {
            val cx = width / 2f
            val cy = height / 2f
            val start = Offset(cx, cy)
            down(start)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 120L)
            moveTo(Offset(cx, cy - 130f))
            advanceEventTime(32L)
            up()
        }
        composeRule.waitForIdle()
        assertEquals("Inner artwork long-press drag must commit next orbital track", listOf(2), orbitalCommits)
        assertTrue("Inner artwork orbital drag must never commit a seek", committedSeeks.isEmpty())
    }

    // =========================================================================
    // UI-03: CircularSeekRing Accessibility Semantics
    // =========================================================================

    @Test
    fun ui03_circularSeekRing_semanticsSetProgressAndCustomActionsAndUnknownDuration() {
        val seeks = mutableListOf<Long>()
        var durationMs by mutableStateOf(200_000L)
        var positionMs by mutableStateOf(50_000L)

        setHostContent {
            HiFiDeckTheme(darkTheme = true) {
                val controller = rememberCircularScrubController()
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularSeekRing(
                        trackKey = "a11y_track",
                        positionMs = positionMs,
                        durationMs = durationMs,
                        isSeekable = true,
                        visualBezelDiameter = 220.dp,
                        scrubController = controller,
                        interactiveSeeking = true,
                        onCommitSeek = { seeks.add(it) }
                    )
                }
            }
        }

        val ring = composeRule.onNodeWithContentDescription("Playback progress")
        ring.assertIsDisplayed()
        ring.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo(0.25f, 0f..1f)
            )
        )

        // SetProgress -> 0.5f => 100_000ms
        ring.performSemanticsAction(SemanticsActions.SetProgress) { action ->
            assertTrue(action(0.5f))
        }
        composeRule.waitForIdle()
        assertEquals(100_000L, seeks.last())

        // Custom actions: Seek forward 10 seconds (50s -> 60s) & Seek backward 10 seconds (50s -> 40s)
        val semanticsNode = ring.fetchSemanticsNode()
        val customActions = semanticsNode.config[SemanticsActions.CustomActions]
        val forward = customActions.first { it.label == "Seek forward 10 seconds" }
        val backward = customActions.first { it.label == "Seek backward 10 seconds" }

        composeRule.runOnIdle { assertTrue(forward.action()) }
        assertEquals(60_000L, seeks.last())

        composeRule.runOnIdle { assertTrue(backward.action()) }
        assertEquals(40_000L, seeks.last())

        // Unknown duration -> ProgressBarRangeInfo.Indeterminate & stateDescription "Duration unknown"
        composeRule.runOnIdle { durationMs = 0L }
        composeRule.waitForIdle()
        ring.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate
            )
        )
        ring.assert(
            SemanticsMatcher.expectValue(
                SemanticsProperties.StateDescription,
                "Duration unknown"
            )
        )
    }

    // =========================================================================
    // UI-02: All Four Bottom Sheets Render Cleanly in Light & Dark Themes
    // =========================================================================

    @Test
    fun ui02_allFourBottomSheets_renderCleanlyInBothLightAndDarkThemes() {
        val track = sampleTrack(1L, "Nocturne in Violet")
        val qualityInfo = AudioQualityInfo(
            uri = track.uri,
            codec = "FLAC",
            container = "FLAC",
            sampleRate = 96_000,
            bitDepth = 24,
            channels = 2,
            bitrate = 2_940_000L,
            bitrateMode = BitrateMode.VBR,
            fileSizeBytes = track.sizeBytes,
            durationMs = track.durationMs,
            isLossless = true
        )

        var activeSheet by mutableStateOf(0) // 0=Inspector, 1=Sleep, 2=TrackMeta, 3=BatchMeta
        var darkTheme by mutableStateOf(false)

        setHostContent {
            HiFiDeckTheme(darkTheme = darkTheme) {
                when (activeSheet) {
                    0 -> AudioInspectorSheet(
                        state = AudioInspectorUiState(track = track, info = qualityInfo),
                        onDismiss = {},
                        onRefresh = {},
                        onScanLoudness = {},
                        onCancelScan = {},
                        onEditMetadata = {}
                    )
                    1 -> SleepTimerSheet(
                        state = SleepTimerState(mode = SleepTimerMode.OFF),
                        defaultFadeSeconds = 10,
                        onStartDuration = { _, _ -> },
                        onStartEndOfTrack = {},
                        onStartEndOfQueue = {},
                        onUpdateFadeSeconds = {},
                        onExtendMinutes = {},
                        onCancel = {},
                        onDismiss = {}
                    )
                    2 -> TrackMetadataEditorSheet(
                        state = TrackMetadataEditorUiState(
                            track = track,
                            original = EditableTrackMetadata(
                                title = track.title,
                                artist = track.artist,
                                album = track.album,
                                format = AudioContainerFormat.FLAC
                            ),
                            isLoading = false
                        ),
                        onPrepareArtwork = { _, _ -> null },
                        onSave = {},
                        onDismiss = {}
                    )
                    3 -> BatchMetadataEditorSheet(
                        state = BatchMetadataEditorUiState(
                            tracks = listOf(track, sampleTrack(2L, "Second Signal"))
                        ),
                        onPrepareArtwork = { _, _ -> null },
                        onApplyBatch = {},
                        onCancelBatch = {},
                        onDismiss = {}
                    )
                }
            }
        }

        for (isDark in listOf(false, true)) {
            composeRule.runOnIdle {
                darkTheme = isDark
                activeSheet = 0
            }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Audio Details").assertIsDisplayed()
            composeRule.onNodeWithText("Nocturne in Violet · HiFi Ensemble").assertIsDisplayed()

            composeRule.runOnIdle { activeSheet = 1 }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Sleep Timer").assertIsDisplayed()
            composeRule.onNodeWithText("End of song").assertIsDisplayed()

            composeRule.runOnIdle { activeSheet = 2 }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Edit Metadata").assertIsDisplayed()
            composeRule.onNodeWithText("Save").assertIsDisplayed()

            composeRule.runOnIdle { activeSheet = 3 }
            composeRule.waitForIdle()
            composeRule.onNodeWithText("Batch Edit Metadata").assertIsDisplayed()
        }
    }

    // =========================================================================
    // UI-06: Home URL Input Accessibility Description & 48dp Clear Target
    // =========================================================================

    @Test
    fun ui06_hiFiGlassTextField_exposesContentDescriptionAnd48dpClearTarget() {
        var text by mutableStateOf("")
        setHostContent {
            HiFiDeckTheme(darkTheme = false) {
                HiFiGlassTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = "Paste a YouTube or audio URL",
                    contentDescription = "Audio or YouTube URL",
                    onClear = { text = "" }
                )
            }
        }

        val inputNode = composeRule.onNodeWithContentDescription("Audio or YouTube URL")
        inputNode.assertIsDisplayed()
        inputNode.performTextInput("https://youtu.be/dQw4w9WgXcQ")
        composeRule.waitForIdle()

        val clearBounds = composeRule.onNodeWithContentDescription("Clear").getUnclippedBoundsInRoot()
        assertTrue("Clear button width ${clearBounds.width} must be >= 48.dp", clearBounds.width >= 47.5.dp)
        assertTrue("Clear button height ${clearBounds.height} must be >= 48.dp", clearBounds.height >= 47.5.dp)
    }

    // =========================================================================
    // UI-07: Minimum 48dp Touch Targets Across Updated Controls
    // =========================================================================

    @Test
    fun ui07_remediatedControls_allMeetMinimum48dpTouchTargetBounds() {
        val tracks = listOf(sampleTrack(1L, "Track One"), sampleTrack(2L, "Track Two"), sampleTrack(3L, "Track Three"))
        val playbackState = PlaybackUiState(
            queue = tracks,
            currentIndex = 1,
            currentTrack = tracks[1],
            isPlaying = true,
            positionMs = 30_000L,
            durationMs = 240_000L
        )
        var screenMode by mutableStateOf(0) // 0=Mini+Controls, 1=QueueSheet, 2=Orbital, 3=LyricsNotice

        setHostContent {
            HiFiDeckTheme(darkTheme = true) {
                when (screenMode) {
                    0 -> Box(modifier = Modifier.fillMaxSize()) {
                        MiniPlayer(
                            state = playbackState,
                            onOpenNowPlaying = {},
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            loadArtwork = { _, _, _ -> null },
                            modifier = Modifier.align(Alignment.TopCenter)
                        )
                        PlayerControls(
                            isPlaying = true,
                            shuffleEnabled = false,
                            repeatMode = RepeatModeOption.OFF,
                            onToggleShuffle = {},
                            onSkipPrevious = {},
                            onTogglePlayPause = {},
                            onSkipNext = {},
                            onCycleRepeat = {},
                            modifier = Modifier.align(Alignment.BottomCenter)
                        )
                    }
                    1 -> PlaybackQueueSheet(
                        state = playbackState,
                        onDismiss = {},
                        onSelectIndex = {},
                        onMoveItem = { _, _ -> },
                        onRemoveIndex = {},
                        onClearQueue = {},
                        loadArtwork = { _, _, _ -> null }
                    )
                    2 -> {
                        val orbital = rememberOrbitalGestureController()
                        LaunchedEffect(Unit) {
                            orbital.enterOrbit(queueSize = tracks.size, currentNavIndex = 1, accessibleMode = true)
                        }
                        OrbitalNavigator(
                            tracks = tracks,
                            shuffleEnabled = false,
                            controller = orbital,
                            onCommitNavIndex = {},
                            onDismiss = {},
                            loadArtwork = { _, _, _ -> null }
                        )
                    }
                    3 -> LyricsScreen(
                        lyricsState = LyricsUiState(
                            activeTrack = tracks[0],
                            statusMessage = "Saved synced lyrics"
                        ),
                        positionMs = 10_000L,
                        onSeekTo = {},
                        onSearchOnline = { _, _ -> },
                        onSelectCandidate = {},
                        onSaveManualLyrics = {},
                        onImportLyricsFile = {},
                        onExportLrcFile = {},
                        onEmbedIntoAudioFile = {},
                        onAdjustOffset = {},
                        onResetOffset = {},
                        onClearLyrics = {},
                        onDismissNotice = {}
                    )
                }
            }
        }

        fun assertMin48dp(contentDesc: String) {
            val bounds = composeRule.onAllNodesWithContentDescription(contentDesc).onFirst().getUnclippedBoundsInRoot()
            assertTrue("[$contentDesc] width ${bounds.width} must be >= 48.dp", bounds.width >= 47.5.dp)
            assertTrue("[$contentDesc] height ${bounds.height} must be >= 48.dp", bounds.height >= 47.5.dp)
        }

        // MiniPlayer + PlayerControls
        composeRule.waitForIdle()
        assertMin48dp("Pause")
        assertMin48dp("Next track")
        assertMin48dp("Shuffle")
        assertMin48dp(RepeatModeOption.OFF.label)
        assertMin48dp("Previous track")

        // PlaybackQueueSheet row controls
        composeRule.runOnIdle { screenMode = 1 }
        composeRule.waitForIdle()
        assertMin48dp("Move up")
        assertMin48dp("Move down")
        assertMin48dp("Remove from queue")

        // OrbitalNavigator close + accessible step buttons
        composeRule.runOnIdle { screenMode = 2 }
        composeRule.waitUntil(timeoutMillis = 3_000L) {
            composeRule.onAllNodesWithContentDescription("Close")
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertMin48dp("Close")
        assertMin48dp("Previous track")
        assertMin48dp("Next track")

        // LyricsScreen notice dismiss button
        composeRule.runOnIdle { screenMode = 3 }
        composeRule.waitForIdle()
        assertMin48dp("Dismiss")
    }

    // =========================================================================
    // Phase C: SafeAudioWritePipeline & SEC-03 Backup Exclusion Coexistence
    // =========================================================================

    @Test
    fun phaseC_safeAudioWritePipeline_realPendingWritesDirRecoveryUnaffectedByBackupExclusions() = runBlocking {
        val ctx = appContext()
        val workDir = File(ctx.cacheDir, "p1_phasec_${System.currentTimeMillis()}").apply { mkdirs() }
        // Production journal directory (excluded from cloud/device backup by SEC-03, kept local!)
        val prodJournalRoot = File(File(ctx.filesDir, "hifideck_data"), "pending_writes").apply { mkdirs() }
        val testOpId = "p1_test_op_${System.currentTimeMillis()}"
        val testOpDir = File(prodJournalRoot, testOpId)

        try {
            // Generate a tiny real disposable FLAC fixture in cacheDir
            val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
            val packagesDir = File(File(ctx.noBackupFilesDir, com.yausername.youtubedl_android.YoutubeDL.baseName), "packages")
            val ffmpegLibDir = File(packagesDir, "ffmpeg/usr/lib")
            if (!ffmpegLibDir.isDirectory) {
                runCatching { com.yausername.youtubedl_android.YoutubeDL.getInstance().init(ctx) }
                runCatching { com.yausername.ffmpeg.FFmpeg.getInstance().init(ctx) }
            }
            val targetFile = File(workDir, "phasec_disposable.flac")
            val pb = ProcessBuilder(
                bin.absolutePath, "-y", "-v", "error",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=0.8",
                "-c:a", "flac", "-metadata", "TITLE=Original PhaseC",
                targetFile.absolutePath
            ).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = listOf(
                ffmpegLibDir.absolutePath,
                File(packagesDir, "python/usr/lib").absolutePath
            ).joinToString(":")
            val proc = pb.start()
            assertTrue(proc.waitFor(20, TimeUnit.SECONDS))
            assertEquals(0, proc.exitValue())

            val origBytes = targetFile.readBytes()
            val origSha = AudioContainerPrimitives.computeFileSha256(targetFile)!!
            val targetUri = Uri.fromFile(targetFile).toString()

            val pipeline = SafeAudioWritePipeline(
                journalRootDir = prodJournalRoot,
                backend = AndroidContentResolverBackend(ctx)
            )

            // Simulate crash journal inside real production hifideck_data/pending_writes/
            testOpDir.mkdirs()
            val rollbackFile = File(testOpDir, "rollback.flac").apply { writeBytes(origBytes) }
            targetFile.writeBytes(origBytes.copyOf(24)) // truncated!
            val manifest = SafeAudioWritePipeline.WriteManifest(
                opId = testOpId,
                targetUri = targetUri,
                displayName = targetFile.name,
                operationType = SafeAudioWritePipeline.OperationType.METADATA,
                formatName = AudioContainerFormat.FLAC.name,
                originalSha256 = origSha,
                originalSizeBytes = origBytes.size.toLong(),
                rollbackPath = rollbackFile.absolutePath,
                expectedOutputSha256 = "1111111111111111111111111111111111111111111111111111111111111111",
                expectedOutputSizeBytes = origBytes.size.toLong() + 10L,
                state = SafeAudioWritePipeline.OperationState.COMMITTING,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            File(testOpDir, "manifest.json").writeText(manifest.toJsonString(), Charsets.UTF_8)

            // Startup recovery restores original bytes bit-for-bit and cleans ONLY our verified test op
            val report = pipeline.recoverInterruptedOperations()
            assertTrue("Expected at least 1 recovered operation", report.restoredCount >= 1)
            assertEquals(0, report.failedRetainedCount)
            assertArrayEquals("Original audio must be restored bit-for-bit", origBytes, targetFile.readBytes())
            assertTrue("Verified rollback dir must be cleaned after recovery", !testOpDir.exists())
        } finally {
            testOpDir.deleteRecursively()
            workDir.deleteRecursively()
        }
    }
}

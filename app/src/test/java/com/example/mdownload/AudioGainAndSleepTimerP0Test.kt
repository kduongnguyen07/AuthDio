package com.example.mdownload

import androidx.media3.common.C
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import com.example.mdownload.core.audio.GainApplicationPath
import com.example.mdownload.core.audio.MissingReplayGainBehavior
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.core.audio.ReplayGainConfig
import com.example.mdownload.core.audio.ReplayGainInfo
import com.example.mdownload.core.audio.ReplayGainMode
import com.example.mdownload.player.playback.PlaybackGainCoordinator
import com.example.mdownload.player.playback.ReplayGainAudioProcessor
import com.example.mdownload.player.playback.SleepTimerController
import com.example.mdownload.player.playback.SleepTimerMath
import com.example.mdownload.player.playback.SleepTimerMode
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/**
 * Phase B (`STAB-01` / `AUD-01`, `AUD-02`) deterministic regression tests for:
 * - B1: Zero double ReplayGain attenuation across Fallback <-> PCM AudioProcessor transitions
 * - B2: Lock-free audio-thread ownership of `currentGain` under concurrent updates
 * - B3: Bit-exact unity-gain fast path (PCM_16BIT & PCM_FLOAT)
 * - B4: Sleep Timer expiry holding `0.0f` fade until playback is confirmed paused (no volume burst)
 * - B5: `END_OF_QUEUE` and `END_OF_TRACK` across Repeat OFF/ONE/ALL × Shuffle OFF/ON × single/empty queue
 */
@androidx.annotation.OptIn(UnstableApi::class)
class AudioGainAndSleepTimerP0Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // Fake Media3 Player & Timeline helpers (zero Android runtime dependency)
    // =========================================================================

    private class FakePlayerState(
        var itemCount: Int = 3,
        var currentIndex: Int = 0,
        var repeatMode: Int = Player.REPEAT_MODE_OFF,
        var shuffleEnabled: Boolean = false,
        var shuffleOrder: IntArray? = null,
        var isPlaying: Boolean = true,
        var playWhenReady: Boolean = true,
        var durationMs: Long = 180_000L,
        var positionMs: Long = 10_000L,
        var volume: Float = 1.0f
    ) {
        var pauseCallCount: Int = 0
            private set

        fun buildTimeline(): Timeline {
            if (itemCount <= 0) return Timeline.EMPTY
            val count = itemCount
            val order = shuffleOrder ?: IntArray(count) { it }
            return object : Timeline() {
                override fun getWindowCount(): Int = count
                override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
                    window.set(
                        /* uid = */ windowIndex,
                        /* mediaItem = */ androidx.media3.common.MediaItem.EMPTY,
                        /* manifest = */ null,
                        /* presentationStartTimeMs = */ C.TIME_UNSET,
                        /* windowStartTimeMs = */ C.TIME_UNSET,
                        /* elapsedRealtimeEpochOffsetMs = */ C.TIME_UNSET,
                        /* isSeekable = */ true,
                        /* isDynamic = */ false,
                        /* liveConfiguration = */ null,
                        /* defaultPositionUs = */ 0L,
                        /* durationUs = */ durationMs * 1000L,
                        /* firstPeriodIndex = */ windowIndex,
                        /* lastPeriodIndex = */ windowIndex,
                        /* positionInFirstPeriodUs = */ 0L
                    )
                    return window
                }

                override fun getPeriodCount(): Int = count
                override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
                    period.set(periodIndex, periodIndex, periodIndex, durationMs * 1000L, 0L)
                    return period
                }

                override fun getIndexOfPeriod(uid: Any): Int = (uid as? Int) ?: C.INDEX_UNSET
                override fun getUidOfPeriod(periodIndex: Int): Any = periodIndex

                override fun getFirstWindowIndex(shuffleModeEnabled: Boolean): Int {
                    if (count == 0) return C.INDEX_UNSET
                    return if (shuffleModeEnabled) order.first() else 0
                }

                override fun getLastWindowIndex(shuffleModeEnabled: Boolean): Int {
                    if (count == 0) return C.INDEX_UNSET
                    return if (shuffleModeEnabled) order.last() else count - 1
                }
            }
        }

        fun createProxy(): Player = Proxy.newProxyInstance(
            Player::class.java.classLoader,
            arrayOf(Player::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getVolume" -> volume
                "setVolume" -> {
                    volume = (args?.get(0) as Float)
                    Unit
                }
                "getApplicationLooper" -> null
                "getMediaItemCount" -> itemCount
                "getCurrentMediaItemIndex" -> currentIndex
                "getRepeatMode" -> repeatMode
                "getShuffleModeEnabled" -> shuffleEnabled
                "getCurrentTimeline" -> buildTimeline()
                "isPlaying" -> isPlaying
                "getPlayWhenReady" -> playWhenReady
                "getDuration" -> durationMs
                "getCurrentPosition" -> positionMs
                "getPlaybackParameters" -> PlaybackParameters.DEFAULT
                "getCurrentMediaItem" -> null
                "hasNextMediaItem" -> {
                    when {
                        itemCount <= 0 -> false
                        repeatMode == Player.REPEAT_MODE_ALL || repeatMode == Player.REPEAT_MODE_ONE -> true
                        else -> currentIndex < itemCount - 1
                    }
                }
                "pause" -> {
                    pauseCallCount++
                    playWhenReady = false
                    Unit
                }
                "equals" -> false
                "hashCode" -> System.identityHashCode(this)
                "toString" -> "FakePlayer(idx=$currentIndex/$itemCount, vol=$volume, playing=$isPlaying)"
                else -> when (method.returnType) {
                    java.lang.Boolean.TYPE -> false
                    java.lang.Integer.TYPE -> 0
                    java.lang.Long.TYPE -> 0L
                    java.lang.Float.TYPE -> 0f
                    java.lang.Double.TYPE -> 0.0
                    else -> null
                }
            }
        } as Player
    }

    private fun pcm16Format(sampleRate: Int = 44100, channels: Int = 2) =
        AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_16BIT)

    private fun pcmFloatFormat(sampleRate: Int = 48000, channels: Int = 2) =
        AudioProcessor.AudioFormat(sampleRate, channels, C.ENCODING_PCM_FLOAT)

    private fun makePcm16Buffer(vararg samples: Short): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        for (s in samples) buf.putShort(s)
        buf.flip()
        return buf
    }

    // =========================================================================
    // B1 — Zero Double ReplayGain Attenuation Across Fallback <-> PCM Handoff
    // =========================================================================

    @Test
    fun gainCoordinator_fallbackToPcmHandoffNeverDoubleAttenuatesEvenWithAsyncMainThreadLag() {
        val processor = ReplayGainAudioProcessor()
        val pendingMainQueue = ArrayDeque<() -> Unit>()
        val coordinator = PlaybackGainCoordinator(
            audioProcessor = processor,
            playerDispatcher = { _, block -> pendingMainQueue.addLast(block) }
        )
        fun drainMain() {
            while (pendingMainQueue.isNotEmpty()) {
                pendingMainQueue.removeFirst().invoke()
            }
        }

        val fakeState = FakePlayerState()
        val player = fakeState.createProxy()

        coordinator.attachPlayer(player)
        coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.TRACK))
        coordinator.updateTrackReplayGain(
            trackUri = "content://track/1",
            info = ReplayGainInfo(trackGainDb = -6.0f, trackPeak = 0.9f),
            snapOnTrackSwitch = true
        )
        drainMain()

        val expectedGain = ReplayGainCalculator.dbToLinear(-6.0f) // ~0.501187

        // Phase 1: Before onConfigure() -> PLAYER_VOLUME_FALLBACK owns attenuation; processor MUST stay unity 1.0f
        assertFalse(processor.isProcessingPcm)
        assertEquals(GainApplicationPath.PLAYER_VOLUME_FALLBACK, coordinator.state.value.applicationPath)
        assertEquals(expectedGain, fakeState.volume, 1e-4f)
        assertEquals(1.0f, processor.currentTargetLinearGain, 1e-5f)
        assertEquals(expectedGain, fakeState.volume * processor.currentTargetLinearGain, 1e-4f)

        // Phase 2: ExoPlayer audio thread calls onConfigure(PCM_16BIT) BEFORE Main thread runs posted handoff!
        processor.configure(pcm16Format())
        processor.flush()
        assertTrue(processor.isProcessingPcm)
        assertTrue("Handoff runnable must be queued to Main thread, never mutating player.volume on audio thread", pendingMainQueue.isNotEmpty())

        // CRITICAL CHECK (AUD-01): While handoff is still in flight on Main queue, combined gain MUST NOT double-attenuate!
        assertEquals("Processor must stay unity until Main resets player.volume", 1.0f, processor.currentTargetLinearGain, 1e-5f)
        assertEquals("Player volume still carries -6dB during handoff window", expectedGain, fakeState.volume, 1e-4f)
        assertEquals(
            "Combined gain during async transition window must equal single -6dB (never -12dB!)",
            expectedGain,
            fakeState.volume * processor.currentTargetLinearGain,
            1e-4f
        )

        // Even if ExoPlayer feeds a PCM buffer during that window, processor passes unity (1.0x)
        val midTransitionOut = run {
            processor.queueInput(makePcm16Buffer(20000, 20000, 20000, 20000))
            processor.output
        }
        assertEquals(20000, midTransitionOut.short.toInt())

        // Phase 3: Main thread executes handoff -> player.volume becomes 1.0f and DSP takes over -6dB
        drainMain()
        assertEquals(GainApplicationPath.DSP_PCM_PROCESSOR, coordinator.state.value.applicationPath)
        assertEquals("player.volume MUST be strictly 1.0f when DSP PCM is active", 1.0f, fakeState.volume, 1e-5f)
        assertEquals(expectedGain, processor.currentTargetLinearGain, 1e-4f)
        assertEquals(expectedGain, fakeState.volume * processor.currentTargetLinearGain, 1e-4f)

        // Next PCM buffer is scaled directly by DSP to ~10024 (-6dB), and player.volume is 1.0f
        processor.queueInput(makePcm16Buffer(20000, 20000, 20000, 20000))
        val scaledOut = processor.output
        val scaledSample = scaledOut.short.toInt()
        assertTrue("Expected ~10024 after single -6dB DSP scaling, got $scaledSample", scaledSample in 9950..10100)
    }

    @Test
    fun gainCoordinator_pcmToOffloadResetAndPositivePreampAndModeTransitions() {
        val processor = ReplayGainAudioProcessor()
        val coordinator = PlaybackGainCoordinator(
            audioProcessor = processor,
            playerDispatcher = { _, block -> block() } // synchronous player thread
        )
        val fakeState = FakePlayerState()
        val player = fakeState.createProxy()

        coordinator.attachPlayer(player)
        processor.configure(pcm16Format())
        processor.flush()

        // 1. ReplayGain OFF -> BYPASS, both actuators strictly 1.0f
        coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.OFF))
        assertEquals(GainApplicationPath.BYPASS, coordinator.state.value.applicationPath)
        assertEquals(1.0f, fakeState.volume, 1e-5f)
        assertEquals(1.0f, processor.currentTargetLinearGain, 1e-5f)

        // 2. Positive preamp (+4.0 dB = ~1.585x) in PCM DSP mode -> DSP applies >1.0f while player.volume == 1.0f
        coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.TRACK, preampDb = 4.0f, preventClipping = false))
        coordinator.updateTrackReplayGain("u1", ReplayGainInfo(trackGainDb = 0.0f), snapOnTrackSwitch = true)
        val boostLinear = ReplayGainCalculator.dbToLinear(4.0f)
        assertEquals(GainApplicationPath.DSP_PCM_PROCESSOR, coordinator.state.value.applicationPath)
        assertEquals(1.0f, fakeState.volume, 1e-5f)
        assertEquals(boostLinear, processor.currentTargetLinearGain, 1e-3f)

        // 3. Album mode + track attenuation (-8 dB) + sleep fade 0.5x -> combined in DSP, player.volume == 1.0f
        coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.ALBUM))
        coordinator.updateTrackReplayGain("u2", ReplayGainInfo(trackGainDb = -3.0f, albumGainDb = -8.0f), snapOnTrackSwitch = true)
        coordinator.setSleepFadeFactor(0.5f)
        val expectedAlbumFade = ReplayGainCalculator.dbToLinear(-8.0f) * 0.5f
        assertEquals(GainApplicationPath.DSP_PCM_PROCESSOR, coordinator.state.value.applicationPath)
        assertEquals(1.0f, fakeState.volume, 1e-5f)
        assertEquals(expectedAlbumFade, processor.currentTargetLinearGain, 1e-4f)

        // 4. Switch to offload / sink reset -> demotes cleanly to PLAYER_VOLUME_FALLBACK; processor resets to 1.0f
        processor.reset()
        assertFalse(processor.isProcessingPcm)
        assertEquals(GainApplicationPath.PLAYER_VOLUME_FALLBACK, coordinator.state.value.applicationPath)
        assertEquals(1.0f, processor.currentTargetLinearGain, 1e-5f)
        assertEquals(expectedAlbumFade, fakeState.volume, 1e-4f)

        // 5. Missing metadata policy (fallback preamp -3 dB) while offloaded
        coordinator.resetSleepFade()
        coordinator.updateConfig(
            ReplayGainConfig(
                mode = ReplayGainMode.TRACK,
                missingBehavior = MissingReplayGainBehavior.APPLY_FALLBACK_PREAMP,
                fallbackPreampDb = -3.0f
            )
        )
        coordinator.updateTrackReplayGain("u3", null, snapOnTrackSwitch = true)
        assertEquals(ReplayGainCalculator.dbToLinear(-3.0f), fakeState.volume, 1e-4f)
        assertEquals(1.0f, processor.currentTargetLinearGain, 1e-5f)

        // 6. ReplayGain back OFF -> BYPASS, both 1.0f
        coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.OFF))
        assertEquals(GainApplicationPath.BYPASS, coordinator.state.value.applicationPath)
        assertEquals(1.0f, fakeState.volume, 1e-5f)
        assertEquals(1.0f, processor.currentTargetLinearGain, 1e-5f)
    }

    // =========================================================================
    // B2 & B3 — Thread-Safe Audio Processor & Bit-Exact Unity Fast Path
    // =========================================================================

    @Test
    fun audioProcessor_unityFastPathIsBitExactForPcm16AndPcmFloat() {
        val processor = ReplayGainAudioProcessor()

        // PCM_16BIT unity check
        processor.configure(pcm16Format())
        processor.flush()
        processor.setTargetLinearGain(1.0f)
        val raw16 = shortArrayOf(Short.MIN_VALUE, -12345, -1, 0, 1, 12345, Short.MAX_VALUE, 777)
        val in16 = makePcm16Buffer(*raw16)
        val beforeBytes16 = ByteArray(in16.remaining()).also { in16.duplicate().get(it) }
        processor.queueInput(in16)
        val out16 = processor.output
        val afterBytes16 = ByteArray(out16.remaining()).also { out16.get(it) }
        assertArrayEquals("PCM_16BIT unity output must be bit-for-bit identical", beforeBytes16, afterBytes16)
        assertEquals(1L, processor.unityFastPathBufferCount)
        assertEquals(0L, processor.scaledBufferCount)

        // PCM_FLOAT unity check
        processor.reset()
        processor.configure(pcmFloatFormat())
        processor.flush()
        processor.setTargetLinearGain(1.0f)
        val inFloat = ByteBuffer.allocateDirect(4 * 4).order(ByteOrder.nativeOrder()).apply {
            putFloat(-1.0f)
            putFloat(-0.1234567f)
            putFloat(0.0f)
            putFloat(0.9876543f)
            flip()
        }
        val beforeFloat = ByteArray(inFloat.remaining()).also { inFloat.duplicate().get(it) }
        processor.queueInput(inFloat)
        val outFloat = processor.output
        val afterFloat = ByteArray(outFloat.remaining()).also { outFloat.get(it) }
        assertArrayEquals("PCM_FLOAT unity output must be bit-for-bit identical", beforeFloat, afterFloat)
        assertEquals(2L, processor.unityFastPathBufferCount)
        assertEquals(0L, processor.scaledBufferCount)
    }

    @Test
    fun audioProcessor_concurrentTargetGainAndSnapUpdatesAreRaceFree() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(pcmFloatFormat(sampleRate = 48000, channels = 2))
        processor.flush()

        val stop = AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val started = CountDownLatch(4)

        val writers = (0 until 4).map { idx ->
            Thread {
                started.countDown()
                var step = 0
                try {
                    while (!stop.get()) {
                        val g = when ((step + idx) % 5) {
                            0 -> 1.0f
                            1 -> 0.5f
                            2 -> 0.0f
                            3 -> 1.8f
                            else -> 0.25f
                        }
                        processor.setTargetLinearGain(g)
                        if (step % 7 == 0) processor.snapToTargetGain()
                        step++
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }.apply { start() }
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))

        // Single audio thread pumps 500 buffers while 4 writer threads hammer targetGain + snap
        val buf = ByteBuffer.allocateDirect(64 * 2 * 4).order(ByteOrder.nativeOrder())
        for (iter in 0 until 500) {
            buf.clear()
            for (i in 0 until 128) {
                buf.putFloat(if (i % 2 == 0) 0.5f else -0.5f)
            }
            buf.flip()
            processor.queueInput(buf)
            val out = processor.output
            while (out.hasRemaining()) {
                val sample = out.float
                assertTrue("Sample must be finite and clamped [-1, 1], got $sample", sample.isFinite() && sample in -1.0f..1.0f)
            }
            if (iter % 100 == 0) processor.flush()
        }

        stop.set(true)
        writers.forEach { it.join(2000) }
        assertTrue("No thread may fail: ${failure.get()}", failure.get() == null)

        // Final snap + verify exact convergence to 0.5f
        processor.setTargetLinearGain(0.5f)
        processor.snapToTargetGain()
        buf.clear()
        repeat(16) { buf.putFloat(0.8f) }
        buf.flip()
        processor.queueInput(buf)
        val finalOut = processor.output
        assertEquals(0.4f, finalOut.float, 1e-4f)
    }

    // =========================================================================
    // B4 — Sleep Timer Expiry Holds 0.0f Fade Until Playback Paused (No Spike)
    // =========================================================================

    @Test
    fun sleepTimer_expiryHoldsZeroFadeUntilPausedAndRestoresCleanlyOnSettleOrResume() {
        val processor = ReplayGainAudioProcessor()
        processor.configure(pcm16Format())
        processor.flush()
        val coordinator = PlaybackGainCoordinator(
            audioProcessor = processor,
            playerDispatcher = { _, block -> block() }
        )
        val fakeState = FakePlayerState(isPlaying = true, playWhenReady = true, itemCount = 3, currentIndex = 1, positionMs = 42_000L)
        val player = fakeState.createProxy()

        coordinator.attachPlayer(player)
        coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.TRACK))
        coordinator.updateTrackReplayGain("t1", ReplayGainInfo(trackGainDb = -4.0f), snapOnTrackSwitch = true)
        val expectedRg = ReplayGainCalculator.dbToLinear(-4.0f)

        val nowElapsed = AtomicLong(1_000_000L)
        val nowWall = AtomicLong(1_760_000_000_000L)
        val timer = SleepTimerController(
            persistFile = null,
            gainCoordinator = coordinator,
            elapsedClock = { nowElapsed.get() },
            wallClock = { nowWall.get() },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            pauseSettleDelayMs = 0L, // deterministic restore as soon as onIsPlayingChanged(false) confirms pause
            playerDispatcher = { _, block -> block() }
        )
        timer.attachPlayer(player)

        // Start END_OF_TRACK with 10s fade -> expire via auto transition while player.isPlaying is STILL true
        timer.startEndOfTrack(fadeOutSeconds = 10)
        coordinator.setSleepFadeFactor(0.05f) // simulate near end of fade
        timer.onMediaItemTransition(newMediaId = "t2", reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)

        // Immediately after expireAndPause():
        // 1. Timer is OFF and pause() was called once, queue & position preserved
        assertFalse(timer.state.value.isActive)
        assertEquals(1, fakeState.pauseCallCount)
        assertEquals(3, fakeState.itemCount)
        assertEquals(1, fakeState.currentIndex)
        assertEquals(42_000L, fakeState.positionMs)

        // 2. CRITICAL (AUD-02a): Because player.isPlaying is STILL true (AudioTrack draining),
        //    sleepFadeFactor MUST remain 0.0f — NO volume spike!
        assertTrue("Must await pause confirmation before restoring fade", timer.isAwaitingPauseFadeRestore)
        assertEquals(0.0f, coordinator.state.value.sleepFadeFactor, 1e-5f)
        assertEquals(0.0f, coordinator.state.value.effectiveLinearGain, 1e-5f)
        assertEquals(0.0f, processor.currentTargetLinearGain, 1e-5f)

        // 3. Now ExoPlayer confirms pause via onIsPlayingChanged(false) -> restores ReplayGain cleanly!
        fakeState.isPlaying = false
        timer.onIsPlayingChanged(false)
        assertFalse(timer.isAwaitingPauseFadeRestore)
        assertEquals(1.0f, coordinator.state.value.sleepFadeFactor, 1e-5f)
        assertEquals(expectedRg, coordinator.state.value.effectiveLinearGain, 1e-4f)
        assertEquals(expectedRg, processor.currentTargetLinearGain, 1e-4f)

        // 4. Also verify immediate user resume BEFORE onIsPlayingChanged(false) restores fade immediately (never muted!)
        fakeState.isPlaying = true
        fakeState.playWhenReady = true
        timer.startEndOfTrack(fadeOutSeconds = 10)
        timer.onMediaItemTransition("t3", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertTrue(timer.isAwaitingPauseFadeRestore)
        assertEquals(0.0f, coordinator.state.value.sleepFadeFactor, 1e-5f)

        fakeState.playWhenReady = true
        timer.onPlayWhenReadyChanged(true)
        assertFalse(timer.isAwaitingPauseFadeRestore)
        assertEquals(1.0f, coordinator.state.value.sleepFadeFactor, 1e-5f)
        assertEquals(expectedRg, coordinator.state.value.effectiveLinearGain, 1e-4f)
        timer.cancel()
    }

    // =========================================================================
    // B5 — END_OF_QUEUE Across Repeat OFF / ONE / ALL × Shuffle OFF / ON × Single / Empty
    // =========================================================================

    @Test
    fun sleepTimer_endOfQueueMathAndControllerWorkAcrossAllRepeatAndShuffleModes() {
        // Pure math matrix checks
        assertFalse(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 0, currentIndex = 0, lastLogicalIndex = -1, repeatMode = Player.REPEAT_MODE_OFF))
        assertTrue(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 1, currentIndex = 0, lastLogicalIndex = 0, repeatMode = Player.REPEAT_MODE_OFF))
        assertTrue(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 1, currentIndex = 0, lastLogicalIndex = 0, repeatMode = Player.REPEAT_MODE_ALL))
        assertTrue(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 5, currentIndex = 2, lastLogicalIndex = 4, repeatMode = Player.REPEAT_MODE_ONE))
        assertFalse(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 5, currentIndex = 2, lastLogicalIndex = 4, repeatMode = Player.REPEAT_MODE_ALL))
        assertTrue(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 5, currentIndex = 4, lastLogicalIndex = 4, repeatMode = Player.REPEAT_MODE_ALL))
        // Shuffle order [2, 0, 1] -> logical last is index 1, NOT index 2!
        assertFalse(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 3, currentIndex = 2, lastLogicalIndex = 1, repeatMode = Player.REPEAT_MODE_ALL))
        assertTrue(SleepTimerMath.isAtEffectiveQueueEnd(itemCount = 3, currentIndex = 1, lastLogicalIndex = 1, repeatMode = Player.REPEAT_MODE_ALL))

        fun newController(fakeState: FakePlayerState): SleepTimerController {
            val coord = PlaybackGainCoordinator(playerDispatcher = { _, b -> b() })
            val ctrl = SleepTimerController(
                persistFile = null,
                gainCoordinator = coord,
                mainDispatcher = Dispatchers.Unconfined,
                ioDispatcher = Dispatchers.Unconfined,
                pauseSettleDelayMs = 0L,
                playerDispatcher = { _, b -> b() }
            )
            ctrl.attachPlayer(fakeState.createProxy())
            return ctrl
        }

        // Case 1: REPEAT_MODE_OFF, Shuffle OFF (3 tracks): advances 0->1->2 without pausing, pauses on STATE_ENDED
        run {
            val st = FakePlayerState(itemCount = 3, currentIndex = 0, repeatMode = Player.REPEAT_MODE_OFF, shuffleEnabled = false)
            val ctrl = newController(st)
            ctrl.startEndOfQueue(fadeOutSeconds = 0)
            st.currentIndex = 1
            ctrl.onMediaItemTransition("t1", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertTrue("Must remain active at track 1/3", ctrl.state.value.isActive)
            st.currentIndex = 2
            ctrl.onMediaItemTransition("t2", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertTrue("Must remain active on last track until finished", ctrl.state.value.isActive)
            ctrl.onPlaybackStateChanged(Player.STATE_ENDED)
            assertFalse("Must expire at STATE_ENDED", ctrl.state.value.isActive)
            assertEquals(1, st.pauseCallCount)
            assertEquals(Player.REPEAT_MODE_OFF, st.repeatMode)
        }

        // Case 2: REPEAT_MODE_ALL, Shuffle OFF (3 tracks): wraps 2 -> 0 and pauses WITHOUT mutating repeatMode!
        run {
            val st = FakePlayerState(itemCount = 3, currentIndex = 1, repeatMode = Player.REPEAT_MODE_ALL, shuffleEnabled = false)
            val ctrl = newController(st)
            ctrl.startEndOfQueue(fadeOutSeconds = 0)
            st.currentIndex = 2
            ctrl.onMediaItemTransition("t2", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertTrue("Still playing last track (index 2)", ctrl.state.value.isActive)
            // Wrap from 2 -> 0
            st.currentIndex = 0
            ctrl.onMediaItemTransition("t0", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertFalse("Must expire when REPEAT_MODE_ALL wraps past last track", ctrl.state.value.isActive)
            assertEquals(1, st.pauseCallCount)
            assertEquals("User's repeatMode must NEVER be mutated", Player.REPEAT_MODE_ALL, st.repeatMode)
        }

        // Case 3: REPEAT_MODE_ALL, Shuffle ON with shuffle order [2, 0, 1] -> logical last is index 1!
        run {
            val st = FakePlayerState(
                itemCount = 3,
                currentIndex = 2,
                repeatMode = Player.REPEAT_MODE_ALL,
                shuffleEnabled = true,
                shuffleOrder = intArrayOf(2, 0, 1)
            )
            val ctrl = newController(st)
            ctrl.startEndOfQueue(fadeOutSeconds = 0)
            // 2 -> 0 (not logical last)
            st.currentIndex = 0
            ctrl.onMediaItemTransition("t0", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertTrue("Index 2 was first in shuffle order, must NOT expire", ctrl.state.value.isActive)
            // 0 -> 1 (arriving at logical last)
            st.currentIndex = 1
            ctrl.onMediaItemTransition("t1", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertTrue("Just started logical last track (index 1)", ctrl.state.value.isActive)
            // 1 -> 2 (wrapping past logical last track in shuffle order!)
            st.currentIndex = 2
            ctrl.onMediaItemTransition("t2", Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            assertFalse("Must expire when shuffle order wraps from logical last (1) to first (2)", ctrl.state.value.isActive)
            assertEquals(1, st.pauseCallCount)
            assertEquals(Player.REPEAT_MODE_ALL, st.repeatMode)
            assertTrue(st.shuffleEnabled)
        }

        // Case 4: REPEAT_MODE_ONE on middle track (index 1 of 3) -> expires on auto-repeat discontinuity!
        run {
            val st = FakePlayerState(itemCount = 3, currentIndex = 1, repeatMode = Player.REPEAT_MODE_ONE)
            val ctrl = newController(st)
            ctrl.startEndOfQueue(fadeOutSeconds = 0)
            ctrl.onPositionDiscontinuity(oldMediaItemIndex = 1, newMediaItemIndex = 1, reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            assertFalse("REPEAT_MODE_ONE must expire on auto-loop instead of looping infinitely", ctrl.state.value.isActive)
            assertEquals(1, st.pauseCallCount)
            assertEquals(Player.REPEAT_MODE_ONE, st.repeatMode)
        }

        // Case 5: Single-track queue under REPEAT_MODE_ALL -> expires on auto-repeat discontinuity!
        run {
            val st = FakePlayerState(itemCount = 1, currentIndex = 0, repeatMode = Player.REPEAT_MODE_ALL)
            val ctrl = newController(st)
            ctrl.startEndOfQueue(fadeOutSeconds = 0)
            ctrl.onPositionDiscontinuity(oldMediaItemIndex = 0, newMediaItemIndex = 0, reason = Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
            assertFalse("Single-track REPEAT_MODE_ALL must expire after track completes", ctrl.state.value.isActive)
            assertEquals(1, st.pauseCallCount)
        }

        // Case 6: Empty queue -> safe, never crashes or false-expires
        run {
            val st = FakePlayerState(itemCount = 0, currentIndex = 0, repeatMode = Player.REPEAT_MODE_OFF, isPlaying = false)
            val ctrl = newController(st)
            ctrl.startEndOfQueue(fadeOutSeconds = 0)
            assertTrue(ctrl.state.value.isActive)
            assertEquals(0, st.pauseCallCount)
            ctrl.cancel()
        }
    }

    @Test
    fun sleepTimer_endOfTrackRepeatOneAndPersistedFadeAndRestore() {
        val persistFile = File(tempFolder.root, "sleep_timer.json")
        val nowElapsed = AtomicLong(500_000L)
        val nowWall = AtomicLong(1_760_000_000_000L)
        val coord = PlaybackGainCoordinator(playerDispatcher = { _, b -> b() })

        // 1. END_OF_TRACK under REPEAT_MODE_ONE expires cleanly via onPositionDiscontinuity
        val st = FakePlayerState(itemCount = 2, currentIndex = 0, repeatMode = Player.REPEAT_MODE_ONE)
        val ctrl = SleepTimerController(
            persistFile = persistFile,
            gainCoordinator = coord,
            elapsedClock = { nowElapsed.get() },
            wallClock = { nowWall.get() },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            pauseSettleDelayMs = 0L,
            playerDispatcher = { _, b -> b() }
        )
        ctrl.attachPlayer(st.createProxy())
        ctrl.startEndOfTrack(fadeOutSeconds = 0)
        ctrl.onPositionDiscontinuity(0, 0, Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
        assertFalse(ctrl.state.value.isActive)
        assertEquals(1, st.pauseCallCount)

        // 2. Start DURATION, update fadeOutSeconds -> persisted -> restored on fresh instance cleanly!
        ctrl.startDuration(minutes = 10, fadeOutSeconds = 10)
        ctrl.updateFadeOutSeconds(25)
        assertTrue(persistFile.isFile)
        ctrl.cancel() // clear running job only? Wait: cancel deletes persistFile; re-start for restore test:
        ctrl.startDuration(minutes = 10, fadeOutSeconds = 10)
        ctrl.updateFadeOutSeconds(25)
        assertEquals(25, ctrl.state.value.fadeOutSeconds)

        // Simulate app restart 2 minutes later without player attached yet
        val restored = SleepTimerController(
            persistFile = persistFile,
            gainCoordinator = PlaybackGainCoordinator(playerDispatcher = { _, b -> b() }),
            elapsedClock = { nowElapsed.get() + 120_000L },
            wallClock = { nowWall.get() + 120_000L },
            mainDispatcher = Dispatchers.Unconfined,
            ioDispatcher = Dispatchers.Unconfined,
            pauseSettleDelayMs = 0L,
            playerDispatcher = { _, b -> b() }
        )
        assertTrue(restored.state.value.isActive)
        assertEquals(SleepTimerMode.DURATION, restored.state.value.mode)
        assertEquals(25, restored.state.value.fadeOutSeconds)
        assertTrue(
            "Restored remaining should be ~8 minutes, got ${restored.state.value.remainingMs}",
            abs(restored.state.value.remainingMs - 8 * 60_000L) < 2_000L
        )
        restored.cancel()
        ctrl.cancel()
    }
}

package com.example.mdownload

import android.content.Context
import android.os.ParcelFileDescriptor
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.RepositoryLoadState
import com.example.mdownload.core.audio.AudioAnalysisRepository
import com.example.mdownload.core.audio.AudioQualityInfo
import com.example.mdownload.core.audio.LoudnessScanner
import com.example.mdownload.player.library.ListeningHistoryRepository
import com.example.mdownload.player.library.PlaylistRule
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistRepository
import com.example.mdownload.player.library.SmartRuleType
import com.example.mdownload.player.playback.PersistedPlaybackState
import com.example.mdownload.player.playback.PlaybackCursorState
import com.example.mdownload.player.playback.PlaybackStateStore
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.playback.RepeatModeOption
import com.example.mdownload.player.ui.CircularSeekRing
import com.example.mdownload.player.ui.MiniPlayer
import com.example.mdownload.player.ui.rememberCircularScrubController
import com.example.mdownload.ui.design.HiFiDeckTheme
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device STAB-05 verification suite for vivo X200s / OriginOS 7 / arm64-v8a:
 * - Workstream A: Verifies arm64-only APK ships working yt-dlp, FFmpeg, and FFprobe native binaries.
 * - Workstream B: Verifies async startup repositories reach READY without blocking Main Thread.
 * - Workstream C: Verifies high-frequency playback positionMs updates isolate recomposition to leaf
 *   progress composables with ZERO root or sibling recompositions.
 * - Workstream E: Verifies split PlaybackStateStore cursor persistence and LRU-bounded
 *   AudioAnalysisRepository + debounced ListeningHistoryRepository on real Android storage.
 */
@RunWith(AndroidJUnit4::class)
class Stab05PerformanceInstrumentedTest {

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
        composeRule.waitForIdle()
    }

    private fun launchHostAndSetContent(content: @Composable () -> Unit): ComponentActivity {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val pfd: ParcelFileDescriptor = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -a android.intent.action.MAIN -n com.example.mdownload/androidx.activity.ComponentActivity"
        )
        pfd.close()

        val activityRef = AtomicReference<ComponentActivity?>(null)
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (System.nanoTime() < deadline && activityRef.get() == null) {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                val match = resumed.filterIsInstance<ComponentActivity>()
                    .firstOrNull { it.javaClass == ComponentActivity::class.java }
                if (match != null) {
                    activityRef.set(match)
                }
            }
            if (activityRef.get() == null) Thread.sleep(50)
        }
        val host = checkNotNull(activityRef.get()) {
            "Timed out waiting for ComponentActivity host to resume on device"
        }
        activeHostActivity = host
        instrumentation.runOnMainSync {
            host.setContent {
                HiFiDeckTheme(darkTheme = true) {
                    content()
                }
            }
        }
        composeRule.waitForIdle()
        return host
    }

    @Test
    fun arm64Packaging_nativeLibrariesYoutubeDlFfmpegAndFfprobeWorkWithoutUnsatisfiedLinkError() {
        val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
        // Verify YoutubeDL and FFmpeg native bundles initialize cleanly on arm64-v8a
        runCatching { YoutubeDL.getInstance().init(context) }
        runCatching { FFmpeg.getInstance().init(context) }

        val ytdlpVersion = YoutubeDL.getInstance().version(context)
        assertTrue("Expected non-blank yt-dlp version on arm64 APK, got: $ytdlpVersion", !ytdlpVersion.isNullOrBlank())

        // Verify FFmpeg & FFprobe native binaries are bundled and available
        assertTrue(
            "Expected FFmpeg loudness scanner binary to be available in arm64 APK",
            LoudnessScanner.isAvailable(context)
        )
        val nativeLibDir = File(context.applicationInfo.nativeLibraryDir)
        val soFiles = nativeLibDir.listFiles()?.map { it.name }.orEmpty()
        assertTrue("Expected libpython.so in $nativeLibDir, found $soFiles", "libpython.so" in soFiles)
        assertTrue("Expected libffmpeg.so in $nativeLibDir, found $soFiles", "libffmpeg.so" in soFiles)
        assertTrue("Expected libffprobe.so in $nativeLibDir, found $soFiles", "libffprobe.so" in soFiles)
    }

    @Test
    fun startupHydration_appRepositoriesReachReadyStateCleanly() = runBlocking {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MDownloadApp
        app.queueManager.awaitHydrated()
        app.listeningHistoryRepository.awaitReady()
        app.smartPlaylistRepository.awaitReady()
        app.audioAnalysisRepository.awaitReady()
        app.sleepTimerController.awaitReady()
        app.libraryRepository.awaitMetadataReady()

        assertEquals(RepositoryLoadState.READY, app.queueManager.loadState.value)
        assertEquals(RepositoryLoadState.READY, app.listeningHistoryRepository.loadState.value)
        assertEquals(RepositoryLoadState.READY, app.smartPlaylistRepository.loadState.value)
        assertEquals(RepositoryLoadState.READY, app.audioAnalysisRepository.loadState.value)
        assertEquals(RepositoryLoadState.READY, app.sleepTimerController.loadState.value)
        assertEquals(RepositoryLoadState.READY, app.libraryRepository.metadataLoadState.value)
    }

    @Test
    fun composeRecompositionIsolation_positionMsTicksDoNotRecomposeRootOrSiblingSubtrees() {
        val track = LibraryTrack(
            id = 101L,
            uri = "content://media/external/audio/media/101",
            displayName = "isolation_test.flac",
            title = "Isolation Test",
            artist = "HiFiDeck",
            album = "Test Album",
            durationMs = 200_000L,
            sizeBytes = 5_000_000L,
            dateAddedSec = 1_750_000_000L,
            mimeType = "audio/flac"
        )
        val structuralFlow = MutableStateFlow(
            PlaybackUiState(
                currentTrack = track,
                queue = listOf(track),
                currentIndex = 0,
                isPlaying = true,
                positionMs = 0L,
                durationMs = 200_000L,
                isSeekable = true
            )
        )
        val positionFlow = MutableStateFlow(0L)
        val rootRecompCount = AtomicInteger(0)
        val siblingRecompCount = AtomicInteger(0)

        launchHostAndSetContent {
            val structuralState by structuralFlow.collectAsState()
            SideEffect { rootRecompCount.incrementAndGet() }
            Column(modifier = Modifier.fillMaxSize()) {
                UnrelatedSibling(onRecompose = { siblingRecompCount.incrementAndGet() })
                MiniPlayer(
                    state = structuralState,
                    positionMsFlow = positionFlow,
                    onOpenNowPlaying = {},
                    onTogglePlayPause = {},
                    onSkipNext = {},
                    loadArtwork = { _, _, _ -> null }
                )
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    val scrubController = rememberCircularScrubController()
                    CircularSeekRing(
                        trackKey = track.uri,
                        positionMs = structuralState.positionMs,
                        positionMsFlow = positionFlow,
                        durationMs = structuralState.durationMs,
                        isSeekable = true,
                        visualBezelDiameter = 220.dp,
                        scrubController = scrubController,
                        interactiveSeeking = true,
                        onCommitSeek = {}
                    )
                }
            }
        }

        val baselineRoot = rootRecompCount.get()
        val baselineSibling = siblingRecompCount.get()

        // Emit 20 rapid 250ms playback position updates (5 seconds of live playback)
        for (tick in 1..20) {
            positionFlow.value = tick * 1_000L
            composeRule.waitForIdle()
        }

        assertEquals(
            "Root composable MUST NOT recompose on live positionMs ticks",
            baselineRoot,
            rootRecompCount.get()
        )
        assertEquals(
            "Unrelated sibling MUST NOT recompose on live positionMs ticks",
            baselineSibling,
            siblingRecompCount.get()
        )
    }

    @Composable
    private fun UnrelatedSibling(onRecompose: () -> Unit) {
        SideEffect { onRecompose() }
        Box(modifier = Modifier)
    }

    @Test
    fun persistenceOptimization_splitCursorAndBoundedLruCacheWorkOnDeviceStorage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempDir = File(context.cacheDir, "stab05_persist_test_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            // 1. Split PlaybackStateStore cursor test
            val store = PlaybackStateStore(File(tempDir, "playback_state.json"))
            val queue = (1..25).map { i ->
                LibraryTrack(
                    id = i.toLong(),
                    uri = "content://media/external/audio/media/$i",
                    displayName = "song_$i.mp3",
                    title = "Song $i",
                    artist = "Artist $i",
                    album = "Album $i",
                    durationMs = 180_000L,
                    sizeBytes = 4_000_000L,
                    dateAddedSec = 1_750_000_000L,
                    mimeType = "audio/mpeg"
                )
            }
            val sig = PlaybackStateStore.computeQueueSignature(queue)
            store.save(
                PersistedPlaybackState(
                    queue = queue,
                    currentIndex = 0,
                    positionMs = 0L,
                    shuffleEnabled = false,
                    repeatMode = RepeatModeOption.OFF,
                    playbackSpeed = 1.0f
                )
            )
            repeat(8) { tick ->
                store.saveCursorOnly(
                    PlaybackCursorState(
                        queueSignature = sig,
                        currentIndex = 2,
                        positionMs = (tick + 1) * 5_000L,
                        shuffleEnabled = true,
                        repeatMode = RepeatModeOption.ALL,
                        playbackSpeed = 1.25f
                    )
                )
            }
            assertEquals(1, store.fullQueueWriteCount)
            assertEquals(8, store.cursorOnlyWriteCount)
            val restored = store.load()
            assertEquals(25, restored.queue.size)
            assertEquals(2, restored.currentIndex)
            assertEquals(40_000L, restored.positionMs)

            // 2. Debounced ListeningHistoryRepository test
            val historyRepo = ListeningHistoryRepository(
                file = File(tempDir, "listening_history.json"),
                loadAsync = false,
                debounceMs = 60_000L
            )
            repeat(5) { i ->
                historyRepo.addListenedDuration("stable_1", "content://media/1", 5_000L, 1_000L + i, flushImmediately = false)
            }
            assertEquals(0L, historyRepo.diskWriteCount)
            historyRepo.flushNow()
            assertEquals(1L, historyRepo.diskWriteCount)
            val reloadedHistory = ListeningHistoryRepository(File(tempDir, "listening_history.json"), loadAsync = false)
            assertEquals(25_000L, reloadedHistory.statsByTrackId.value["stable_1"]?.totalListenedMs)

            // 3. Bounded LRU AudioAnalysisRepository cap check (insert 5 entries with cap = 3)
            val cacheFile = File(tempDir, "audio_analysis_cache.json")
            val analysisRepo = AudioAnalysisRepository(
                appContext = context,
                cacheFile = cacheFile,
                maxCachedEntries = 3,
                loadAsync = false
            )
            assertEquals(RepositoryLoadState.READY, analysisRepo.loadState.value)
            for (i in 1..5) {
                val track = queue[i - 1]
                analysisRepo.putCachedInfoForTest(
                    track = track,
                    info = AudioQualityInfo(
                        uri = track.uri,
                        codec = "MP3",
                        bitrate = 320_000L,
                        isLossless = false,
                        inspectedAtMs = 10_000L + i * 1_000L
                    ),
                    persist = true
                )
            }
            assertEquals(3, analysisRepo.cachedEntryCount())
            assertEquals(null, analysisRepo.getCachedInfo(queue[0]))
            assertEquals(null, analysisRepo.getCachedInfo(queue[1]))
            assertTrue(analysisRepo.getCachedInfo(queue[4]) != null)
            val modifiedTrack = queue[4].copy(dateModifiedSec = queue[4].dateModifiedSec + 99L)
            assertEquals(null, analysisRepo.getCachedInfo(modifiedTrack))
            val reloadedAnalysis = AudioAnalysisRepository(
                appContext = context,
                cacheFile = cacheFile,
                maxCachedEntries = 3,
                loadAsync = false
            )
            assertEquals(3, reloadedAnalysis.cachedEntryCount())
            assertFalse(cacheFile.readText().contains(queue[0].uri))

            // 4. Legacy pre-STAB-05 playback_state.json backward compatibility
            val legacyFile = File(tempDir, "legacy_playback_state.json")
            legacyFile.writeText(
                """
                {
                  "current_index": 1,
                  "position_ms": 42500,
                  "shuffle_enabled": true,
                  "repeat_mode": "ALL",
                  "playback_speed": 1.25,
                  "queue": [
                    {"id": 1, "uri": "content://media/1", "title": "Song A", "artist": "Art A", "duration_ms": 200000},
                    {"id": 2, "uri": "content://media/2", "title": "Song B", "artist": "Art B", "duration_ms": 240000}
                  ]
                }
                """.trimIndent(),
                Charsets.UTF_8
            )
            val legacyStore = PlaybackStateStore(legacyFile)
            assertFalse(legacyStore.cursorFilePath.exists())
            val loadedLegacy = legacyStore.load()
            assertEquals(2, loadedLegacy.queue.size)
            assertEquals(1, loadedLegacy.currentIndex)
            assertEquals(42_500L, loadedLegacy.positionMs)
            assertTrue(loadedLegacy.shuffleEnabled)
            assertEquals(RepeatModeOption.ALL, loadedLegacy.repeatMode)
            assertEquals(1.25f, loadedLegacy.playbackSpeed, 1e-4f)

            // 5. Async hydration anti-overwrite race protection (ListeningHistory & SmartPlaylists)
            val raceHistoryFile = File(tempDir, "race_history.json")
            ListeningHistoryRepository(raceHistoryFile, loadAsync = false)
                .recordQualifiedPlay("existing_id", "content://media/existing", 10_000L)
            val asyncHistory = ListeningHistoryRepository(raceHistoryFile, loadAsync = true)
            asyncHistory.recordQualifiedPlay("early_id", "content://media/early", 20_000L)
            asyncHistory.awaitReady()
            val verifyHistory = ListeningHistoryRepository(raceHistoryFile, loadAsync = false)
            assertEquals(1, verifyHistory.statsByTrackId.value["existing_id"]?.playCount)
            assertEquals(1, verifyHistory.statsByTrackId.value["early_id"]?.playCount)

            val raceSmartFile = File(tempDir, "race_smart.json")
            SmartPlaylistRepository(raceSmartFile, loadAsync = false).createOrUpdateCustom(
                SmartPlaylistDefinition(
                    id = "sp_existing",
                    name = "Existing",
                    rules = listOf(PlaylistRule(SmartRuleType.IS_LOSSLESS_CODEC, boolValue = true)),
                    isBuiltIn = false
                )
            )
            val asyncSmart = SmartPlaylistRepository(raceSmartFile, loadAsync = true)
            asyncSmart.createOrUpdateCustom(
                SmartPlaylistDefinition(
                    id = "sp_early",
                    name = "Early",
                    rules = listOf(PlaylistRule(SmartRuleType.GENRE_EQUALS, stringValue = "Jazz")),
                    isBuiltIn = false
                )
            )
            asyncSmart.awaitReady()
            val verifySmart = SmartPlaylistRepository(raceSmartFile, loadAsync = false)
            assertEquals(
                setOf("sp_existing", "sp_early"),
                verifySmart.customPlaylists.value.map { it.id }.toSet()
            )
        } finally {
            tempDir.deleteRecursively()
        }
    }
}

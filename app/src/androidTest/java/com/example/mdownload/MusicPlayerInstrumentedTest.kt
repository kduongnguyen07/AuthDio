package com.example.mdownload

import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.library.PlaylistStore
import com.example.mdownload.player.library.UserPlaylist
import com.example.mdownload.player.playback.MediaItemMapper
import com.example.mdownload.player.playback.PersistedPlaybackState
import com.example.mdownload.player.playback.PlaybackStateStore
import com.example.mdownload.player.playback.RepeatModeOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class MusicPlayerInstrumentedTest {

    @Test
    fun playbackStateStore_roundTripsQueuePositionAndModes() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempFile = File(context.cacheDir, "test_playback_state_${System.currentTimeMillis()}.json")
        try {
            val store = PlaybackStateStore(tempFile)
            val track = LibraryTrack(
                id = 42L,
                uri = "content://media/external/audio/media/42",
                displayName = "Test Track.mp3",
                title = "Test Track",
                artist = "Test Artist",
                album = "Test Album",
                durationMs = 185_000L,
                sizeBytes = 4_000_000L,
                dateAddedSec = 1_700_000_000L,
                mimeType = "audio/mpeg",
                videoId = "abc123XYZ90",
                albumId = 9L,
                folderPath = "Music/MDownload",
                folderName = "MDownload",
                isDownloadedByApp = true,
                bitrate = 192_000
            )
            val saved = PersistedPlaybackState(
                queue = listOf(track),
                currentIndex = 0,
                positionMs = 47_500L,
                shuffleEnabled = true,
                repeatMode = RepeatModeOption.ONE,
                playbackSpeed = 1.25f
            )
            store.save(saved)

            val loaded = store.load()
            assertEquals(1, loaded.queue.size)
            assertEquals(track.uri, loaded.queue.first().uri)
            assertEquals("Test Track", loaded.queue.first().title)
            assertEquals(47_500L, loaded.positionMs)
            assertTrue(loaded.shuffleEnabled)
            assertEquals(RepeatModeOption.ONE, loaded.repeatMode)
            assertEquals(1.25f, loaded.playbackSpeed, 0.01f)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun playlistStore_roundTripsPlaylistsAtomically() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempFile = File(context.cacheDir, "test_playlists_${System.currentTimeMillis()}.json")
        try {
            val store = PlaylistStore(tempFile)
            val list = listOf(
                UserPlaylist(
                    id = "pl-1",
                    name = "Late Night Chill",
                    trackUris = listOf("content://media/external/audio/media/1", "content://media/external/audio/media/2")
                )
            )
            store.save(list)
            val loaded = store.load()
            assertEquals(1, loaded.size)
            assertEquals("Late Night Chill", loaded.first().name)
            assertEquals(2, loaded.first().trackUris.size)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun mediaItemMapper_preservesFullTrackMetadataAndExtras() {
        val track = LibraryTrack(
            id = 77L,
            uri = "content://media/external/audio/media/77",
            displayName = "Ocean.flac",
            title = "Ocean",
            artist = "Blue Wave",
            album = "Deep",
            durationMs = 240_000L,
            sizeBytes = 30_000_000L,
            dateAddedSec = 1_700_000_123L,
            mimeType = "audio/flac",
            albumId = 15L,
            folderPath = "Music/FLAC",
            folderName = "FLAC",
            isDownloadedByApp = true,
            bitrate = 980_000
        )
        val item = MediaItemMapper.toMediaItem(track)
        assertEquals(track.artist, item.mediaMetadata.subtitle?.toString())
        assertEquals(track.album, item.mediaMetadata.description?.toString())
        val mapped = MediaItemMapper.fromMediaItem(item)
        assertNotNull(mapped)
        assertEquals(track.uri, mapped!!.uri)
        assertEquals(track.title, mapped.title)
        assertEquals(track.artist, mapped.artist)
        assertEquals(track.album, mapped.album)
        assertEquals(track.durationMs, mapped.durationMs)
        assertEquals(track.mimeType, mapped.mimeType)
        assertEquals(track.bitrate, mapped.bitrate)
        assertTrue(mapped.isDownloadedByApp)
    }

    @Test
    fun manifest_exposesMediaSessionServiceAndMediaButtonReceiver() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pm = context.packageManager

        val serviceIntent = Intent("androidx.media3.session.MediaSessionService").setPackage(context.packageName)
        val services = pm.queryIntentServices(serviceIntent, 0)
        assertTrue(
            "HiFiPlaybackService should be discoverable via MediaSessionService intent",
            services.any { it.serviceInfo.name.endsWith("HiFiPlaybackService") }
        )

        val buttonIntent = Intent(Intent.ACTION_MEDIA_BUTTON).setPackage(context.packageName)
        val receivers = pm.queryBroadcastReceivers(buttonIntent, 0)
        assertTrue(
            "MediaButtonReceiver should be registered for headset & Bluetooth controls",
            receivers.any { it.activityInfo.name == "androidx.media3.session.MediaButtonReceiver" }
        )
    }

    @Test
    fun realDevicePlayback_exercisesMedia3ServiceQueueControlsAndMetadata() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MDownloadApp
        val repo = app.libraryRepository
        val controller = app.playbackController

        val settings = app.settingsStore.load().settings
        repo.refreshLibrary(
            customTreeUri = settings.storageTreeUri,
            scanAllDeviceMusic = settings.scanAllDeviceMusic
        )
        val tracks = waitUntil(timeoutMs = 10_000L) {
            repo.tracks.value.takeIf { it.size >= 3 }
        }
        org.junit.Assume.assumeNotNull(
            "Device needs at least 3 accessible audio tracks with READ_MEDIA_AUDIO granted",
            tracks
        )
        val sample = tracks!!.take(3)
        val extraTrack = tracks.getOrNull(3) ?: sample.first()

        try {
            controller.connect(restoreLastSession = false)
            controller.playQueue(
                tracks = sample,
                startIndex = 0,
                startPositionMs = 0L,
                playWhenReady = true,
                shuffle = false,
                playbackSpeed = 1.0f
            )

            val playingState = waitUntil(timeoutMs = 10_000L) {
                controller.state.value.takeIf {
                    it.isServiceConnected && it.isPlaying && it.currentTrack?.uri == sample[0].uri
                }
            }
            assertNotNull("PlaybackController should connect and start playing via HiFiPlaybackService", playingState)
            assertEquals(3, playingState!!.queue.size)
            assertTrue("AudioOutputInfo should report active format", playingState.audioOutputInfo.isAvailable)

            val startPos = playingState.positionMs
            val advancedState = waitUntil(timeoutMs = 4_000L) {
                controller.state.value.takeIf { it.isPlaying && it.positionMs > startPos + 400L }
            }
            assertNotNull("Playback position should advance continuously while playing", advancedState)

            // Seek forward
            val targetSeekMs = minOf(15_000L, (sample[0].durationMs / 2L).coerceAtLeast(2_000L))
            controller.seekTo(targetSeekMs)
            val seekedState = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.positionMs >= targetSeekMs - 1_000L }
            }
            assertNotNull("Seek should jump near target position", seekedState)

            // Next track
            controller.skipToNext()
            val nextState = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.currentIndex == 1 && it.currentTrack?.uri == sample[1].uri }
            }
            assertNotNull("skipToNext should advance to index 1", nextState)

            // Previous track (at <3s position -> goes back to index 0)
            controller.skipToPrevious()
            val prevState = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.currentIndex == 0 && it.currentTrack?.uri == sample[0].uri }
            }
            assertNotNull("skipToPrevious should return to index 0", prevState)

            // Queue manipulation: playNext, addToQueue, removeFromQueue
            controller.playNext(extraTrack)
            val afterPlayNext = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.queue.size == 4 && it.queue[1].uri == extraTrack.uri }
            }
            assertNotNull("playNext should insert right after current track", afterPlayNext)

            controller.addToQueue(extraTrack)
            val afterAdd = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.queue.size == 5 && it.queue.last().uri == extraTrack.uri }
            }
            assertNotNull("addToQueue should append to end of queue", afterAdd)

            controller.removeFromQueue(4)
            val afterRemove = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.queue.size == 4 }
            }
            assertNotNull("removeFromQueue should remove item at index 4", afterRemove)

            // Shuffle, Repeat, Speed
            controller.toggleShuffle()
            val afterShuffle = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.shuffleEnabled }
            }
            assertNotNull("toggleShuffle should enable shuffle", afterShuffle)
            controller.toggleShuffle()

            val initialRepeat = controller.state.value.repeatMode
            controller.cycleRepeatMode()
            val afterRepeat = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { it.repeatMode == initialRepeat.next() }
            }
            assertNotNull("cycleRepeatMode should advance repeat mode", afterRepeat)

            controller.setPlaybackSpeed(1.25f)
            val afterSpeed = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { kotlin.math.abs(it.playbackSpeed - 1.25f) < 0.02f }
            }
            assertNotNull("setPlaybackSpeed should apply 1.25x speed", afterSpeed)

            // Pause
            controller.togglePlayPause()
            val pausedState = waitUntil(timeoutMs = 5_000L) {
                controller.state.value.takeIf { !it.isPlaying }
            }
            assertNotNull("togglePlayPause should pause active playback", pausedState)
        } finally {
            controller.setPlaybackSpeed(1.0f)
            controller.stop()
            waitUntil(timeoutMs = 3_000L) {
                controller.state.value.takeIf { it.queue.isEmpty() }
            }
        }
    }

    @Test
    fun artistStore_roundTripsUtf8NamesCoversOverridesAndAliases() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val tmpFile = File(ctx.cacheDir, "test_artists_${System.currentTimeMillis()}.json")
        try {
            val store = com.example.mdownload.player.library.ArtistStore(tmpFile)
            val (c1, _) = com.example.mdownload.player.library.ArtistLibraryHelper.createOrUpdateArtist(
                catalog = com.example.mdownload.player.library.ArtistCatalogState(),
                name = "Đen Vâu",
                coverPath = "/data/user/0/com.example.mdownload/files/hifideck_artist_covers/artist_1.jpg",
                initialTrackUris = listOf("content://media/external/audio/media/42")
            )
            val c2 = com.example.mdownload.player.library.ArtistLibraryHelper.editArtist(
                catalog = c1,
                oldName = "Đen Vâu Official",
                newName = "Đen Vâu"
            )
            store.save(c2)
            val loaded = store.load()

            assertEquals(1, loaded.profiles.size)
            assertEquals("Đen Vâu", loaded.profiles.first().name)
            assertEquals(
                "/data/user/0/com.example.mdownload/files/hifideck_artist_covers/artist_1.jpg",
                loaded.profiles.first().coverPath
            )
            assertEquals("Đen Vâu", loaded.trackArtistOverrides["content://media/external/audio/media/42"])
            assertEquals(
                "Đen Vâu",
                loaded.artistNameAliases[com.example.mdownload.player.library.ArtistLibraryHelper.normalizeKey("Đen Vâu Official")]
            )
        } finally {
            tmpFile.delete()
        }
    }

    @Test
    fun listeningHistoryAndSmartPlaylistRepos_roundTripPersistAtomically() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val historyFile = File(ctx.cacheDir, "test_history_${System.currentTimeMillis()}.json")
        val smartFile = File(ctx.cacheDir, "test_smart_${System.currentTimeMillis()}.json")
        try {
            val historyRepo = com.example.mdownload.player.library.ListeningHistoryRepository(historyFile)
            historyRepo.recordQualifiedPlay("track-1", "content://media/external/audio/media/1", 1_750_000_000_000L)
            historyRepo.addListenedDuration("track-1", "content://media/external/audio/media/1", 45_000L, 1_750_000_045_000L)

            val reloadedHistory = com.example.mdownload.player.library.ListeningHistoryRepository(historyFile)
            val stats = reloadedHistory.statsByTrackId.value["track-1"]
            assertNotNull(stats)
            assertEquals(1, stats!!.playCount)
            assertEquals(45_000L, stats.totalListenedMs)

            val smartRepo = com.example.mdownload.player.library.SmartPlaylistRepository(smartFile)
            assertEquals(7, smartRepo.builtInPlaylists.size)
            val created = smartRepo.createOrUpdateCustom(
                com.example.mdownload.player.library.SmartPlaylistDefinition(
                    id = "custom-1",
                    name = "Night FLAC",
                    rules = listOf(
                        com.example.mdownload.player.library.PlaylistRule(
                            type = com.example.mdownload.player.library.SmartRuleType.IS_LOSSLESS_CODEC,
                            boolValue = true
                        )
                    ),
                    matchMode = com.example.mdownload.player.library.MatchMode.ALL,
                    sortOrder = com.example.mdownload.player.library.PlaylistSortOrder.PLAY_COUNT_DESC,
                    limit = 50,
                    isBuiltIn = false
                )
            )
            assertNotNull(created)

            val reloadedSmart = com.example.mdownload.player.library.SmartPlaylistRepository(smartFile)
            assertEquals(1, reloadedSmart.customPlaylists.value.size)
            assertEquals("Night FLAC", reloadedSmart.customPlaylists.value.first().name)
        } finally {
            historyFile.delete()
            smartFile.delete()
        }
    }

    @Test
    fun replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade() {
        val processor = com.example.mdownload.player.playback.ReplayGainAudioProcessor()
        val coordinator = com.example.mdownload.player.playback.PlaybackGainCoordinator(processor)

        val format = androidx.media3.common.audio.AudioProcessor.AudioFormat(
            44100,
            2,
            androidx.media3.common.C.ENCODING_PCM_16BIT
        )
        processor.configure(format)
        processor.flush()

        coordinator.updateConfig(
            com.example.mdownload.core.audio.ReplayGainConfig(
                mode = com.example.mdownload.core.audio.ReplayGainMode.TRACK
            )
        )
        coordinator.updateTrackReplayGain(
            trackUri = "content://test/1",
            info = com.example.mdownload.core.audio.ReplayGainInfo(trackGainDb = -6.0f, trackPeak = 0.9f),
            snapOnTrackSwitch = true
        )

        val expectedRg = com.example.mdownload.core.audio.ReplayGainCalculator.dbToLinear(-6.0f)
        assertEquals(expectedRg, coordinator.state.value.effectiveLinearGain, 1e-3f)

        // Feed PCM-16 stereo frames with value 20000 and verify ~-6dB (~10024) scaling
        val input = java.nio.ByteBuffer.allocateDirect(16).order(java.nio.ByteOrder.nativeOrder())
        repeat(8) { input.putShort(20000) }
        input.flip()
        processor.queueInput(input)
        val out = processor.output
        assertEquals(16, out.remaining())
        val firstSample = out.short.toInt()
        assertTrue("Expected ~10024 after -6dB, got $firstSample", firstSample in 9800..10250)

        // Sleep timer fade factor multiplies cleanly and restores on reset
        coordinator.setSleepFadeFactor(0.5f)
        assertEquals(expectedRg * 0.5f, coordinator.state.value.effectiveLinearGain, 1e-3f)
        coordinator.resetSleepFade()
        assertEquals(expectedRg, coordinator.state.value.effectiveLinearGain, 1e-3f)
    }

    @Test
    fun sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val processor = com.example.mdownload.player.playback.ReplayGainAudioProcessor()
        val coordinator = com.example.mdownload.player.playback.PlaybackGainCoordinator(processor)
        coordinator.updateConfig(
            com.example.mdownload.core.audio.ReplayGainConfig(
                mode = com.example.mdownload.core.audio.ReplayGainMode.TRACK
            )
        )
        coordinator.updateTrackReplayGain(
            trackUri = "content://test/sleep",
            info = com.example.mdownload.core.audio.ReplayGainInfo(trackGainDb = -4.0f, trackPeak = 0.9f),
            snapOnTrackSwitch = true
        )
        val expectedRg = com.example.mdownload.core.audio.ReplayGainCalculator.dbToLinear(-4.0f)

        val timer = com.example.mdownload.player.playback.SleepTimerController(
            context = ctx,
            gainCoordinator = coordinator
        )
        try {
            timer.startDuration(minutes = 15, fadeOutSeconds = 10)
            assertTrue(timer.state.value.isActive)
            assertEquals(com.example.mdownload.player.playback.SleepTimerMode.DURATION, timer.state.value.mode)
            assertTrue(timer.state.value.remainingMs in 14 * 60_000L..15 * 60_000L + 500L)

            val beforeExtend = timer.state.value.remainingMs
            timer.extendMinutes(5)
            assertTrue(
                "Extending by 5 min should increase remaining time",
                timer.state.value.remainingMs >= beforeExtend + 4 * 60_000L
            )

            // Simulate active fade then cancel -> ReplayGain must remain intact & fade restored to 1.0f
            coordinator.setSleepFadeFactor(0.25f)
            assertEquals(expectedRg * 0.25f, coordinator.state.value.effectiveLinearGain, 1e-3f)

            timer.cancel()
            assertTrue(!timer.state.value.isActive)
            assertEquals(1.0f, coordinator.state.value.sleepFadeFactor, 1e-4f)
            assertEquals(expectedRg, coordinator.state.value.effectiveLinearGain, 1e-3f)
        } finally {
            timer.cancel()
        }
    }

    @Test
    fun audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as MDownloadApp
        val repo = app.libraryRepository
        val settings = app.settingsStore.load().settings
        repo.refreshLibrary(
            customTreeUri = settings.storageTreeUri,
            scanAllDeviceMusic = settings.scanAllDeviceMusic
        )
        val track = waitUntil(timeoutMs = 8_000L) {
            repo.tracks.value.firstOrNull()
        }
        org.junit.Assume.assumeNotNull("Need at least 1 local audio track for on-device inspection", track)

        val tempCache = File(app.cacheDir, "test_analysis_cache_${System.currentTimeMillis()}.json")
        try {
            val analysisRepo = com.example.mdownload.core.audio.AudioAnalysisRepository(app, tempCache)
            val info = kotlinx.coroutines.runBlocking {
                analysisRepo.inspectTrack(
                    track = track!!,
                    hasLyrics = false,
                    lyricsSourceLabel = null,
                    forceRefresh = true
                )
            }
            assertNotNull("Codec should be identified on real local file", info.codec)
            assertTrue("Sample rate should be positive", (info.sampleRate ?: 0) > 0)
            if (info.isLossless == false) {
                assertTrue("Lossy file must not fabricate bit depth", info.bitDepth == null)
            }

            // Cached hit returns identical info without re-running ffprobe
            val cached = analysisRepo.getCachedInfo(track!!)
            assertNotNull("Versioned cache hit expected for unchanged track", cached)
            assertEquals(info.codec, cached!!.codec)

            // Versioned key invalidates automatically if file size changes
            val modifiedTrack = track.copy(sizeBytes = track.sizeBytes + 512L)
            assertTrue(
                "Changed file size must miss cache automatically",
                analysisRepo.getCachedInfo(modifiedTrack) == null
            )

            // Explicit invalidation clears cache and increments cacheVersion
            val vBefore = analysisRepo.cacheVersion.value
            analysisRepo.invalidate(track.uri)
            assertTrue(analysisRepo.getCachedInfo(track) == null)
            assertTrue(analysisRepo.cacheVersion.value > vBefore)
        } finally {
            tempCache.delete()
        }
    }

    private fun <T> waitUntil(timeoutMs: Long, stepMs: Long = 100L, condition: () -> T?): T? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            condition()?.let { return it }
            Thread.sleep(stepMs)
        }
        return condition()
    }
}

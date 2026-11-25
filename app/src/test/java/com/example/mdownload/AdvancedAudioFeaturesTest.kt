package com.example.mdownload

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.audio.AudioInspectorEngine
import com.example.mdownload.core.audio.AudioQualityInfo
import com.example.mdownload.core.audio.BitrateMode
import com.example.mdownload.core.audio.LoudnessScanner
import com.example.mdownload.core.audio.MissingReplayGainBehavior
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.core.audio.ReplayGainConfig
import com.example.mdownload.core.audio.ReplayGainInfo
import com.example.mdownload.core.audio.ReplayGainMetadataReader
import com.example.mdownload.core.audio.ReplayGainMode
import com.example.mdownload.core.metadata.BatchMetadataPatch
import com.example.mdownload.core.metadata.EditableTrackMetadata
import com.example.mdownload.core.metadata.MetadataTagIO
import com.example.mdownload.core.metadata.MetadataValidator
import com.example.mdownload.core.metadata.MetadataWriteResult
import com.example.mdownload.player.library.BuiltInSmartPlaylists
import com.example.mdownload.player.library.ListeningStats
import com.example.mdownload.player.library.MatchMode
import com.example.mdownload.player.library.PlaylistRule
import com.example.mdownload.player.library.PlaylistSortOrder
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistEvalContext
import com.example.mdownload.player.library.SmartPlaylistEvaluator
import com.example.mdownload.player.library.SmartRuleType
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.LyricsTagIO
import com.example.mdownload.player.playback.ListeningSessionTracker
import com.example.mdownload.player.playback.SleepTimerMath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets

class AdvancedAudioFeaturesTest {

    // =========================================================================
    // 1. REPLAYGAIN MATH, SAFETY & TAG PARSING
    // =========================================================================

    @Test
    fun replayGain_dbToLinearAndBackAreAccurate() {
        assertEquals(1.0f, ReplayGainCalculator.dbToLinear(0f), 1e-5f)
        assertEquals(0.501187f, ReplayGainCalculator.dbToLinear(-6f), 1e-4f)
        assertEquals(1.995262f, ReplayGainCalculator.dbToLinear(6f), 1e-4f)
        assertEquals(-6f, ReplayGainCalculator.linearToDb(0.501187f), 1e-2f)
    }

    @Test
    fun replayGain_modeSelectionAndAlbumFallback() {
        val infoWithBoth = ReplayGainInfo(trackGainDb = -7.2f, trackPeak = 0.95f, albumGainDb = -5.0f, albumPeak = 0.98f)
        val trackOnly = ReplayGainInfo(trackGainDb = -4.5f, trackPeak = 0.80f)

        val off = ReplayGainCalculator.compute(infoWithBoth, ReplayGainConfig(mode = ReplayGainMode.OFF))
        assertEquals(1.0f, off.linearGain, 1e-5f)
        assertEquals(0f, off.appliedGainDb, 1e-5f)

        val trackRes = ReplayGainCalculator.compute(infoWithBoth, ReplayGainConfig(mode = ReplayGainMode.TRACK))
        assertEquals(-7.2f, trackRes.appliedGainDb, 1e-3f)

        val albumRes = ReplayGainCalculator.compute(infoWithBoth, ReplayGainConfig(mode = ReplayGainMode.ALBUM))
        assertEquals(-5.0f, albumRes.appliedGainDb, 1e-3f)

        // Album mode falls back cleanly to track gain when album gain is absent
        val fallbackRes = ReplayGainCalculator.compute(trackOnly, ReplayGainConfig(mode = ReplayGainMode.ALBUM))
        assertEquals(-4.5f, fallbackRes.appliedGainDb, 1e-3f)
        assertTrue(fallbackRes.usedTrackGain)
        assertFalse(fallbackRes.usedAlbumGain)
        assertTrue(fallbackRes.reason.contains("fallback", ignoreCase = true))
    }

    @Test
    fun replayGain_missingMetadataAndClippingPrevention() {
        val unityRes = ReplayGainCalculator.compute(
            null,
            ReplayGainConfig(
                mode = ReplayGainMode.TRACK,
                missingBehavior = MissingReplayGainBehavior.LEAVE_UNCHANGED
            )
        )
        assertEquals(1.0f, unityRes.linearGain, 1e-5f)

        val fallbackPreampRes = ReplayGainCalculator.compute(
            null,
            ReplayGainConfig(
                mode = ReplayGainMode.TRACK,
                missingBehavior = MissingReplayGainBehavior.APPLY_FALLBACK_PREAMP,
                fallbackPreampDb = -4.0f
            )
        )
        assertEquals(-4.0f, fallbackPreampRes.appliedGainDb, 1e-3f)

        // Positive gain + high peak -> clipping protection caps linearGain * peak <= 1.0
        val hotTrack = ReplayGainInfo(trackGainDb = 5.0f, trackPeak = 0.95f)
        val protectedRes = ReplayGainCalculator.compute(
            hotTrack,
            ReplayGainConfig(
                mode = ReplayGainMode.TRACK,
                preampDb = 3.0f,
                preventClipping = true
            )
        )
        assertTrue(protectedRes.peakLimited)
        assertTrue(protectedRes.linearGain * 0.95f <= 1.0001f)

        // NaN / Infinity values are safely ignored
        val malformed = ReplayGainInfo(trackGainDb = Float.NaN, trackPeak = Float.POSITIVE_INFINITY)
        val safeRes = ReplayGainCalculator.compute(malformed, ReplayGainConfig(mode = ReplayGainMode.TRACK))
        assertTrue(safeRes.linearGain.isFinite())
        assertEquals(1.0f, safeRes.linearGain, 1e-5f)
    }

    @Test
    fun replayGain_composesCleanlyWithSleepFade() {
        val rgLinear = ReplayGainCalculator.dbToLinear(-6f)
        assertEquals(rgLinear, ReplayGainCalculator.composeEffectiveGain(rgLinear, 1.0f), 1e-5f)
        assertEquals(rgLinear * 0.5f, ReplayGainCalculator.composeEffectiveGain(rgLinear, 0.5f), 1e-5f)
        assertEquals(0f, ReplayGainCalculator.composeEffectiveGain(rgLinear, 0.0f), 1e-5f)
    }

    @Test
    fun replayGain_parsesOpusR128Q78AndEbuR128ScannerOutput() {
        // R128_TRACK_GAIN = -1280 in Q7.8 = -5.0 dB at -23 LUFS -> 0.0 dB at -18 LUFS ReplayGain reference!
        val converted = ReplayGainMetadataReader.r128Q78ToReplayGainDb("-1280")
        assertNotNull(converted)
        assertEquals(0.0f, converted!!, 1e-3f)

        val ffmpegOutput = """
            [Parsed_ebur128_0 @ 0x1234] Summary:
              Integrated loudness:
                I:         -14.2 LUFS
                Threshold: -24.5 LUFS
              True peak:
                Peak:      -0.4 dBFS
        """.trimIndent()
        val scanned = LoudnessScanner.parseEbuR128Output(ffmpegOutput)
        assertNotNull(scanned)
        assertEquals(-14.2f, scanned!!.measuredIntegratedLufs!!, 1e-3f)
        assertEquals(-3.8f, scanned.trackGainDb!!, 1e-2f) // -18.0 - (-14.2) = -3.8 dB
        assertTrue(scanned.isScannedEstimate)
        assertNotNull(scanned.trackPeak)
        assertTrue(scanned.trackPeak!! in 0.90f..0.98f)
    }

    @Test
    fun replayGain_readsId3v2TxxxAndFlacVorbisTagsFromFiles() {
        val tmpMp3 = createTempFileWithBytes("rg_test", ".mp3", buildTestMp3Bytes(
            title = "Loudness Song",
            artist = "Test Artist",
            extraTxxx = listOf(
                "REPLAYGAIN_TRACK_GAIN" to "-6.42 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.988123",
                "REPLAYGAIN_ALBUM_GAIN" to "-5.10 dB",
                "REPLAYGAIN_ALBUM_PEAK" to "0.999000"
            )
        ))
        try {
            val rg = ReplayGainMetadataReader.readFromFile(tmpMp3, "rg_test.mp3", "audio/mpeg")
            assertNotNull(rg)
            assertEquals(-6.42f, rg!!.trackGainDb!!, 1e-3f)
            assertEquals(0.988123f, rg.trackPeak!!, 1e-4f)
            assertEquals(-5.10f, rg.albumGainDb!!, 1e-3f)
            assertEquals(0.999f, rg.albumPeak!!, 1e-4f)
        } finally {
            tmpMp3.delete()
        }

        val tmpFlac = createTempFileWithBytes("rg_test", ".flac", buildTestFlacBytes(
            comments = listOf(
                "TITLE" to "FLAC Song",
                "REPLAYGAIN_TRACK_GAIN" to "-3.25 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.875"
            ),
            sampleRate = 96000,
            channels = 2,
            bitDepth = 24
        ))
        try {
            val rg = ReplayGainMetadataReader.readFromFile(tmpFlac, "rg_test.flac", "audio/flac")
            assertNotNull(rg)
            assertEquals(-3.25f, rg!!.trackGainDb!!, 1e-3f)
            assertEquals(0.875f, rg.trackPeak!!, 1e-4f)
        } finally {
            tmpFlac.delete()
        }
    }

    // =========================================================================
    // 2. AUDIO QUALITY INSPECTOR
    // =========================================================================

    @Test
    fun audioInspector_parsesFlacStreamInfoAndLosslessCaveatTruthfully() {
        val flac = createTempFileWithBytes("inspect", ".flac", buildTestFlacBytes(
            comments = listOf("TITLE" to "HiRes Track"),
            sampleRate = 96000,
            channels = 2,
            bitDepth = 24
        ))
        try {
            val info = AudioInspectorEngine.inspectLocalFilePure(flac, isDownloadedByApp = true)
            assertEquals("FLAC", info.codec)
            assertEquals("FLAC", info.container)
            assertEquals(96000, info.sampleRate)
            assertEquals(2, info.channels)
            assertEquals(24, info.bitDepth)
            assertEquals(true, info.isLossless)
            // Must warn honestly that app-downloaded/transcoded FLAC cannot restore lossy source quality
            assertNotNull(info.losslessCaveat)
        } finally {
            flac.delete()
        }
    }

    @Test
    fun audioInspector_parsesMp3HeadersWithoutFakeBitDepth() {
        val mp3 = createTempFileWithBytes("inspect_vbr", ".mp3", buildTestMp3Bytes(
            title = "VBR Track",
            artist = "Artist",
            includeXingHeader = true
        ))
        try {
            val info = AudioInspectorEngine.inspectLocalFilePure(mp3, isDownloadedByApp = false)
            assertEquals("MP3", info.codec)
            assertEquals(44100, info.sampleRate)
            assertEquals(2, info.channels)
            assertEquals(false, info.isLossless)
            assertEquals(BitrateMode.VBR, info.bitrateMode)
            // Lossy codecs must NEVER fabricate a bit depth
            assertNull(info.bitDepth)
        } finally {
            mp3.delete()
        }
    }

    @Test
    fun audioInspector_parsesWavAndHandlesCorruptFilesSafely() {
        val wav = createTempFileWithBytes("inspect", ".wav", buildTestWavBytes(sampleRate = 48000, channels = 2, bitDepth = 16))
        val corrupt = createTempFileWithBytes("corrupt", ".mp3", byteArrayOf(0x01, 0x02, 0x03, 0x04))
        try {
            val wavInfo = AudioInspectorEngine.inspectLocalFilePure(wav, isDownloadedByApp = false)
            assertEquals("PCM", wavInfo.codec)
            assertEquals("WAV", wavInfo.container)
            assertEquals(48000, wavInfo.sampleRate)
            assertEquals(2, wavInfo.channels)
            assertEquals(16, wavInfo.bitDepth)
            assertEquals(true, wavInfo.isLossless)

            val corruptInfo = AudioInspectorEngine.inspectLocalFilePure(corrupt, isDownloadedByApp = false)
            assertNotNull(corruptInfo)
            assertNull(corruptInfo.bitDepth)
        } finally {
            wav.delete()
            corrupt.delete()
        }
    }

    // =========================================================================
    // 3. METADATA EDITOR VALIDATION & NON-DESTRUCTIVE TAG ROUND-TRIP
    // =========================================================================

    @Test
    fun metadataValidator_validatesSingleAndBatchRules() {
        val badSingle = MetadataValidator.validateSingle(
            EditableTrackMetadata(
                title = "   ",
                year = "abcd",
                trackNumber = "12",
                trackTotal = "10",
                discNumber = "3",
                discTotal = "2"
            )
        )
        assertFalse(badSingle.isValid)
        assertTrue(badSingle.fieldErrors.containsKey("title"))
        assertTrue(badSingle.fieldErrors.containsKey("year"))
        assertTrue(badSingle.fieldErrors.containsKey("trackNumber"))
        assertTrue(badSingle.fieldErrors.containsKey("discNumber"))

        val goodSingle = MetadataValidator.validateSingle(
            EditableTrackMetadata(
                title = "Valid Title",
                year = "2025",
                trackNumber = "3",
                trackTotal = "12"
            )
        )
        assertTrue(goodSingle.isValid)

        assertFalse(MetadataValidator.validateBatch(BatchMetadataPatch()).isValid)
        assertTrue(MetadataValidator.validateBatch(BatchMetadataPatch(album = "New Album", year = "2024")).isValid)
    }

    @Test
    fun metadataTagIO_mp3RoundTripPreservesLyricsReplayGainYtIdUnknownFramesAndAudioPayload() {
        val lyricsText = "[00:05.00]Hello orbital world\n[00:10.00]Still preserved!"
        val src = createTempFileWithBytes("edit_src", ".mp3", buildTestMp3Bytes(
            title = "Old Title",
            artist = "Old Artist",
            lyrics = lyricsText,
            extraTxxx = listOf(
                "REPLAYGAIN_TRACK_GAIN" to "-7.50 dB",
                "MDOWNLOAD_YT_ID" to "dQw4w9WgXcQ"
            ),
            includeCustomPrivFrame = true
        ))
        val dst = File.createTempFile("edit_dst", ".mp3")
        try {
            val origSha = MetadataTagIO.computeAudioPayloadSha256(src, AudioContainerFormat.MP3)
            assertNotNull(origSha)

            val fakeJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x11, 0x22, 0xFF.toByte(), 0xD9.toByte())
            val edit = EditableTrackMetadata(
                title = "New Title — Tiếng Việt",
                artist = "New Artist",
                albumArtist = "Album Artist",
                album = "New Album",
                genre = "Electronic",
                year = "2026",
                trackNumber = "4",
                trackTotal = "12",
                discNumber = "1",
                discTotal = "2",
                composer = "Composer Name",
                comment = "Remastered edition",
                artworkBytes = fakeJpeg,
                artworkMimeType = "image/jpeg",
                artworkChanged = true,
                format = AudioContainerFormat.MP3
            )
            val res = MetadataTagIO.writeMetadataToFilePair(null, src, dst, AudioContainerFormat.MP3, edit)
            assertTrue("Expected Success, got $res", res is MetadataWriteResult.Success)

            // 1. Read back edited fields
            val readBack = MetadataTagIO.readMetadataFromFile(dst, "edit_dst.mp3", "audio/mpeg")
            assertNotNull(readBack)
            assertEquals("New Title — Tiếng Việt", readBack!!.title)
            assertEquals("New Artist", readBack.artist)
            assertEquals("Album Artist", readBack.albumArtist)
            assertEquals("New Album", readBack.album)
            assertEquals("Electronic", readBack.genre)
            assertEquals("2026", readBack.year)
            assertEquals("4", readBack.trackNumber)
            assertEquals("12", readBack.trackTotal)
            assertEquals("1", readBack.discNumber)
            assertEquals("2", readBack.discTotal)
            assertEquals("Composer Name", readBack.composer)
            assertEquals("Remastered edition", readBack.comment)
            assertArrayEquals(fakeJpeg, readBack.artworkBytes)

            // 2. Verify audio payload SHA-256 is byte-for-byte identical
            val newSha = MetadataTagIO.computeAudioPayloadSha256(dst, AudioContainerFormat.MP3)
            assertEquals(origSha, newSha)

            // 3. Verify embedded lyrics (USLT) survived untouched
            val preservedLyrics = LyricsTagIO.readEmbeddedLyricsFromFile(dst, "edit_dst.mp3", "audio/mpeg")
            assertNotNull("Embedded USLT lyrics must survive tag edit", preservedLyrics.lyrics)
            assertTrue(preservedLyrics.lyrics!!.lines.any { it.text.contains("Hello orbital world") })

            // 4. Verify ReplayGain TXXX survived untouched
            val preservedRg = ReplayGainMetadataReader.readFromFile(dst, "edit_dst.mp3", "audio/mpeg")
            assertNotNull("ReplayGain TXXX must survive tag edit", preservedRg)
            assertEquals(-7.50f, preservedRg!!.trackGainDb!!, 1e-3f)

            // 5. Verify MDOWNLOAD_YT_ID & unknown PRIV frame survived in raw file bytes
            val rawString = String(dst.readBytes(), StandardCharsets.ISO_8859_1)
            assertTrue("MDOWNLOAD_YT_ID must be preserved", rawString.contains("dQw4w9WgXcQ"))
            assertTrue("Unknown PRIV frame must be preserved", rawString.contains("PRIV") && rawString.contains("hifideck-custom-payload"))
        } finally {
            src.delete()
            dst.delete()
        }
    }

    @Test
    fun metadataTagIO_flacRoundTripPreservesCommentsLyricsReplayGainAndAudioPayload() {
        val src = createTempFileWithBytes("edit_src", ".flac", buildTestFlacBytes(
            comments = listOf(
                "TITLE" to "Original FLAC",
                "ARTIST" to "Old Artist",
                "LYRICS" to "[00:01.00]FLAC lyrics intact",
                "REPLAYGAIN_TRACK_GAIN" to "-4.80 dB",
                "MDOWNLOAD_YT_ID" to "ytFlac12345",
                "CUSTOM_VENDOR_TAG" to "keep-me"
            ),
            sampleRate = 48000,
            channels = 2,
            bitDepth = 24
        ))
        val dst = File.createTempFile("edit_dst", ".flac")
        try {
            val origSha = MetadataTagIO.computeAudioPayloadSha256(src, AudioContainerFormat.FLAC)
            val edit = EditableTrackMetadata(
                title = "Updated FLAC",
                artist = "Updated Artist",
                album = "Lossless LP",
                genre = "Jazz",
                year = "2024",
                trackNumber = "2",
                trackTotal = "8",
                format = AudioContainerFormat.FLAC
            )
            val res = MetadataTagIO.writeMetadataToFilePair(null, src, dst, AudioContainerFormat.FLAC, edit)
            assertTrue("Expected Success, got $res", res is MetadataWriteResult.Success)

            val readBack = MetadataTagIO.readMetadataFromFile(dst, "edit_dst.flac", "audio/flac")
            assertEquals("Updated FLAC", readBack!!.title)
            assertEquals("Updated Artist", readBack.artist)
            assertEquals("Lossless LP", readBack.album)
            assertEquals("Jazz", readBack.genre)
            assertEquals("2024", readBack.year)
            assertEquals("2", readBack.trackNumber)
            assertEquals("8", readBack.trackTotal)

            assertEquals(origSha, MetadataTagIO.computeAudioPayloadSha256(dst, AudioContainerFormat.FLAC))

            val rg = ReplayGainMetadataReader.readFromFile(dst, "edit_dst.flac", "audio/flac")
            assertEquals(-4.80f, rg!!.trackGainDb!!, 1e-3f)

            val raw = String(dst.readBytes(), StandardCharsets.UTF_8)
            assertTrue(raw.contains("FLAC lyrics intact"))
            assertTrue(raw.contains("ytFlac12345"))
            assertTrue(raw.contains("CUSTOM_VENDOR_TAG=keep-me"))
        } finally {
            src.delete()
            dst.delete()
        }
    }

    @Test
    fun batchMetadataPatch_preservesTitleTrackNumberAndUncheckedFields() {
        val original = EditableTrackMetadata(
            title = "Unique Song 01",
            artist = "Old Artist",
            album = "Old Album",
            genre = "Rock",
            year = "2020",
            trackNumber = "7",
            trackTotal = "10",
            comment = "Keep this note"
        )
        val patch = BatchMetadataPatch(
            artist = "Unified Artist",
            album = "Greatest Hits"
        )
        val merged = patch.applyTo(original)
        assertEquals("Unique Song 01", merged.title)
        assertEquals("7", merged.trackNumber)
        assertEquals("10", merged.trackTotal)
        assertEquals("Rock", merged.genre)
        assertEquals("2020", merged.year)
        assertEquals("Keep this note", merged.comment)
        assertEquals("Unified Artist", merged.artist)
        assertEquals("Greatest Hits", merged.album)
    }

    // =========================================================================
    // 4. SMART PLAYLISTS & LISTENING HISTORY SESSION TRACKER
    // =========================================================================

    @Test
    fun smartPlaylists_evaluatesAll7BuiltInsAndCustomAndOrRules() {
        val nowMs = 1_750_000_000_000L
        val nowSec = nowMs / 1000L
        val t1 = sampleTrack(1, "Alpha", "Flume", "Skin", "Electronic", 2016, 200_000L, nowSec - 3600, "audio/flac", 950_000, downloaded = true)
        val t2 = sampleTrack(2, "Beta", "Roary", "Passage", "Ambient", 2021, 150_000L, nowSec - 86400 * 10, "audio/mpeg", 320_000, downloaded = false)
        val t3 = sampleTrack(3, "Gamma", "Flume", "Hi This Is Flume", "Electronic", 2019, 90_000L, nowSec - 86400 * 60, "audio/mpeg", 128_000, downloaded = true)
        val tracks = listOf(t1, t2, t3)

        val stats = mapOf(
            t1.stableId to ListeningStats(t1.stableId, t1.uri, playCount = 12, totalListenedMs = 2_000_000L, lastPlayedAt = nowMs - 10_000L, firstPlayedAt = nowMs - 86_400_000L),
            t2.stableId to ListeningStats(t2.stableId, t2.uri, playCount = 2, totalListenedMs = 200_000L, lastPlayedAt = nowMs - 600_000L, firstPlayedAt = nowMs - 86_400_000L)
        )
        val analysis = mapOf(
            t1.uri to AudioQualityInfo(uri = t1.uri, codec = "FLAC", isLossless = true, bitrate = 950_000L),
            t2.uri to AudioQualityInfo(uri = t2.uri, codec = "MP3", isLossless = false, bitrate = 320_000L),
            t3.uri to AudioQualityInfo(uri = t3.uri, codec = "MP3", isLossless = false, bitrate = 128_000L)
        )
        val ctx = SmartPlaylistEvalContext(
            statsByTrackId = stats,
            favoriteUris = setOf(t2.uri),
            analysisByUri = analysis,
            nowMs = nowMs
        )

        val builtIns = BuiltInSmartPlaylists.all().associateBy { it.id }
        assertEquals(7, builtIns.size)

        // Recently Added (within last 30 days -> t1 & t2, excludes 60-day-old t3)
        assertEquals(listOf(t1.uri, t2.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_RECENTLY_ADDED]!!, tracks, ctx).map { it.uri })
        // Recently Played
        assertEquals(listOf(t1.uri, t2.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_RECENTLY_PLAYED]!!, tracks, ctx).map { it.uri })
        // Most Played
        assertEquals(listOf(t1.uri, t2.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_MOST_PLAYED]!!, tracks, ctx).map { it.uri })
        // Never Played
        assertEquals(listOf(t3.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_NEVER_PLAYED]!!, tracks, ctx).map { it.uri })
        // Favorites
        assertEquals(listOf(t2.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_FAVORITES]!!, tracks, ctx).map { it.uri })
        // High Quality (FLAC lossless + 320kbps MP3, excludes 128kbps MP3)
        assertEquals(setOf(t1.uri, t2.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_HIGH_QUALITY]!!, tracks, ctx).map { it.uri }.toSet())
        // Recently Downloaded
        assertEquals(listOf(t1.uri, t3.uri), SmartPlaylistEvaluator.evaluate(builtIns[BuiltInSmartPlaylists.ID_RECENTLY_DOWNLOADED]!!, tracks, ctx).map { it.uri })

        // Custom ALL (AND): Artist contains "Flume" AND duration >= 120s -> only t1
        val customAnd = SmartPlaylistDefinition(
            id = "c1",
            name = "Long Flume",
            rules = listOf(
                PlaylistRule(SmartRuleType.ARTIST_CONTAINS, stringValue = "flume"),
                PlaylistRule(SmartRuleType.DURATION_AT_LEAST_SEC, intValue = 120)
            ),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.TITLE_ASC
        )
        assertEquals(listOf(t1.uri), SmartPlaylistEvaluator.evaluate(customAnd, tracks, ctx).map { it.uri })

        // Custom ANY (OR) with limit 2 sorted by title
        val customOr = SmartPlaylistDefinition(
            id = "c2",
            name = "Flume or Ambient",
            rules = listOf(
                PlaylistRule(SmartRuleType.ARTIST_CONTAINS, stringValue = "flume"),
                PlaylistRule(SmartRuleType.GENRE_EQUALS, stringValue = "ambient")
            ),
            matchMode = MatchMode.ANY,
            sortOrder = PlaylistSortOrder.TITLE_ASC,
            limit = 2
        )
        assertEquals(listOf(t1.uri, t2.uri), SmartPlaylistEvaluator.evaluate(customOr, tracks, ctx).map { it.uri })
    }

    @Test
    fun listeningSessionTracker_countsQualifiedPlaysExcludesSeeksAndHandlesRepeatOne() {
        var qualifiedPlays = 0
        var totalCommittedMs = 0L
        val tracker = ListeningSessionTracker(
            onQualifiedPlay = { _, _, _ -> qualifiedPlays++ },
            onCommitListenedMs = { _, _, ms, _ -> totalCommittedMs += ms }
        )

        // Threshold for 180s track = 30s; for 12s short track = 6s
        assertEquals(30_000L, tracker.qualificationThresholdMs(180_000L))
        assertEquals(6_000L, tracker.qualificationThresholdMs(12_000L))

        tracker.onTrackStarted("id1", "uri1", 180_000L, 0L, 1_000L)

        // Advance 10s naturally (10 ticks of 1s)
        for (sec in 1..10) {
            tracker.onProgressSample("id1", "uri1", durationMs = 180_000L, positionMs = sec * 1000L, isPlaying = true, playbackSpeed = 1.0f, nowMs = 1_000L + sec * 1000L)
        }
        assertEquals(0, qualifiedPlays)

        // User seeks forward 90 seconds -> must NOT count toward listened duration!
        tracker.onSeek(100_000L)
        tracker.onProgressSample("id1", "uri1", durationMs = 180_000L, positionMs = 100_000L, isPlaying = true, playbackSpeed = 1.0f, nowMs = 12_000L)
        assertEquals(0, qualifiedPlays)

        // Pause and resume -> same session, continues accumulating
        tracker.onProgressSample("id1", "uri1", durationMs = 180_000L, positionMs = 100_000L, isPlaying = false, playbackSpeed = 1.0f, nowMs = 15_000L)
        for (i in 1..20) {
            tracker.onProgressSample("id1", "uri1", durationMs = 180_000L, positionMs = 100_000L + i * 1000L, isPlaying = true, playbackSpeed = 1.0f, nowMs = 15_000L + i * 1000L)
        }
        // Now total natural listened time = 10s + 20s = 30s -> exactly 1 qualified play!
        assertEquals(1, qualifiedPlays)

        // Keep playing another 10s -> still only 1 qualified play for this session
        for (i in 21..30) {
            tracker.onProgressSample("id1", "uri1", durationMs = 180_000L, positionMs = 100_000L + i * 1000L, isPlaying = true, playbackSpeed = 1.0f, nowMs = 15_000L + i * 1000L)
        }
        assertEquals(1, qualifiedPlays)

        // Repeat-One restarts fresh session -> another 30s triggers a 2nd qualified play
        tracker.onTrackStarted("id1", "uri1", 180_000L, 0L, 50_000L)
        for (sec in 1..30) {
            tracker.onProgressSample("id1", "uri1", durationMs = 180_000L, positionMs = sec * 1000L, isPlaying = true, playbackSpeed = 1.0f, nowMs = 50_000L + sec * 1000L)
        }
        tracker.flushPending(85_000L)
        assertEquals(2, qualifiedPlays)
        assertEquals(70_000L, totalCommittedMs)
    }

    // =========================================================================
    // 5. SLEEP TIMER MATH
    // =========================================================================

    @Test
    fun sleepTimerMath_monotonicRemainingFadeCurveAndExtension() {
        val startElapsed = 1_000_000L
        val deadline = startElapsed + 60_000L
        assertEquals(60_000L, SleepTimerMath.remainingMs(deadline, startElapsed))
        assertEquals(15_000L, SleepTimerMath.remainingMs(deadline, startElapsed + 45_000L))
        assertEquals(0L, SleepTimerMath.remainingMs(deadline, deadline + 5_000L))

        // Fade curve (10s fade window)
        assertEquals(1.0f, SleepTimerMath.fadeFactor(remainingMs = 20_000L, fadeOutSeconds = 10), 1e-5f)
        assertEquals(1.0f, SleepTimerMath.fadeFactor(remainingMs = 10_000L, fadeOutSeconds = 10), 1e-5f)
        assertEquals(0.25f, SleepTimerMath.fadeFactor(remainingMs = 5_000L, fadeOutSeconds = 10), 1e-4f) // quadratic 0.5^2
        assertEquals(0.0f, SleepTimerMath.fadeFactor(remainingMs = 0L, fadeOutSeconds = 10), 1e-5f)
        // Fade disabled (0s) stays at 1.0 until 0
        assertEquals(1.0f, SleepTimerMath.fadeFactor(remainingMs = 500L, fadeOutSeconds = 0), 1e-5f)

        // Extension adds +10 minutes cleanly
        val extended = SleepTimerMath.extendedDeadline(deadline, startElapsed + 30_000L, 10)
        assertEquals(deadline + 600_000L, extended)
    }

    // =========================================================================
    // TEST BINARY FIXTURE BUILDERS
    // =========================================================================

    private fun createTempFileWithBytes(prefix: String, suffix: String, bytes: ByteArray): File {
        val f = File.createTempFile(prefix, suffix)
        f.writeBytes(bytes)
        return f
    }

    private fun sampleTrack(
        id: Long,
        title: String,
        artist: String,
        album: String,
        genre: String,
        year: Int,
        durationMs: Long,
        dateAddedSec: Long,
        mimeType: String,
        bitrate: Int,
        downloaded: Boolean
    ) = LibraryTrack(
        id = id,
        uri = "content://media/external/audio/media/$id",
        displayName = "$title.${if (mimeType.contains("flac")) "flac" else "mp3"}",
        title = title,
        artist = artist,
        album = album,
        genre = genre,
        year = year,
        durationMs = durationMs,
        sizeBytes = 5_000_000L,
        dateAddedSec = dateAddedSec,
        mimeType = mimeType,
        isDownloadedByApp = downloaded,
        bitrate = bitrate
    )

    private fun buildTestMp3Bytes(
        title: String,
        artist: String,
        lyrics: String? = null,
        extraTxxx: List<Pair<String, String>> = emptyList(),
        includeCustomPrivFrame: Boolean = false,
        includeXingHeader: Boolean = false
    ): ByteArray {
        val framesOut = ByteArrayOutputStream()
        fun writeId3Frame(id: String, body: ByteArray) {
            framesOut.write(id.toByteArray(StandardCharsets.ISO_8859_1))
            framesOut.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(body.size).array())
            framesOut.write(byteArrayOf(0, 0))
            framesOut.write(body)
        }
        fun utf8Body(text: String): ByteArray = byteArrayOf(3) + text.toByteArray(StandardCharsets.UTF_8)

        writeId3Frame("TIT2", utf8Body(title))
        writeId3Frame("TPE1", utf8Body(artist))
        if (lyrics != null) {
            val uslt = byteArrayOf(3, 'e'.code.toByte(), 'n'.code.toByte(), 'g'.code.toByte(), 0) +
                lyrics.toByteArray(StandardCharsets.UTF_8)
            writeId3Frame("USLT", uslt)
        }
        extraTxxx.forEach { (desc, value) ->
            val body = byteArrayOf(3) +
                desc.toByteArray(StandardCharsets.UTF_8) +
                byteArrayOf(0) +
                value.toByteArray(StandardCharsets.UTF_8)
            writeId3Frame("TXXX", body)
        }
        if (includeCustomPrivFrame) {
            writeId3Frame("PRIV", "hifideck-custom-payload".toByteArray(StandardCharsets.ISO_8859_1))
        }

        val tagBody = framesOut.toByteArray()
        val tagSize = tagBody.size
        val synchsafe = byteArrayOf(
            ((tagSize shr 21) and 0x7F).toByte(),
            ((tagSize shr 14) and 0x7F).toByte(),
            ((tagSize shr 7) and 0x7F).toByte(),
            (tagSize and 0x7F).toByte()
        )

        // Valid MPEG1 Layer 3, 128 kbps (index 9), 44100 Hz (index 0), Stereo (channel mode 0) frame (417 bytes)
        val mp3Frame = ByteArray(417)
        mp3Frame[0] = 0xFF.toByte()
        mp3Frame[1] = 0xFB.toByte()
        mp3Frame[2] = 0x90.toByte()
        mp3Frame[3] = 0x00.toByte()
        if (includeXingHeader) {
            // Stereo MPEG1 Xing offset = 4 + 32 = 36
            val xing = "Xing".toByteArray(StandardCharsets.ISO_8859_1)
            System.arraycopy(xing, 0, mp3Frame, 36, 4)
            mp3Frame[43] = 0x01 // frames flag
            ByteBuffer.wrap(mp3Frame, 44, 4).order(ByteOrder.BIG_ENDIAN).putInt(1000)
        } else {
            for (i in 4 until mp3Frame.size) mp3Frame[i] = ((i * 31) and 0xFF).toByte()
        }

        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0))
        out.write(synchsafe)
        out.write(tagBody)
        out.write(mp3Frame)
        out.write(mp3Frame)
        return out.toByteArray()
    }

    private fun buildTestFlacBytes(
        comments: List<Pair<String, String>>,
        sampleRate: Int,
        channels: Int,
        bitDepth: Int
    ): ByteArray {
        // 34-byte STREAMINFO payload
        val streamInfo = ByteArray(34)
        streamInfo[0] = 0x10; streamInfo[1] = 0x00 // min block 4096
        streamInfo[2] = 0x10; streamInfo[3] = 0x00 // max block 4096
        val totalSamples = sampleRate.toLong() * 10L
        // Pack 20-bit sampleRate | 3-bit (channels-1) | 5-bit (bitDepth-1) | 36-bit totalSamples into bytes 10..17
        val packed = ((sampleRate.toLong() and 0xFFFFFL) shl 44) or
            (((channels - 1).toLong() and 0x7L) shl 41) or
            (((bitDepth - 1).toLong() and 0x1FL) shl 36) or
            (totalSamples and 0xFFFFFFFFFL)
        ByteBuffer.wrap(streamInfo, 10, 8).order(ByteOrder.BIG_ENDIAN).putLong(packed)

        // VORBIS_COMMENT payload
        val vcOut = ByteArrayOutputStream()
        val vendor = "HiFiDeckTest".toByteArray(StandardCharsets.UTF_8)
        vcOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(vendor.size).array())
        vcOut.write(vendor)
        vcOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(comments.size).array())
        comments.forEach { (k, v) ->
            val entry = "$k=$v".toByteArray(StandardCharsets.UTF_8)
            vcOut.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(entry.size).array())
            vcOut.write(entry)
        }
        val vcBytes = vcOut.toByteArray()

        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(StandardCharsets.ISO_8859_1))
        // Block 0: STREAMINFO (not last)
        out.write(0x00)
        out.write(byteArrayOf((streamInfo.size shr 16).toByte(), (streamInfo.size shr 8).toByte(), streamInfo.size.toByte()))
        out.write(streamInfo)
        // Block 4: VORBIS_COMMENT (last = 0x80 | 4)
        out.write(0x84)
        out.write(byteArrayOf((vcBytes.size shr 16).toByte(), (vcBytes.size shr 8).toByte(), vcBytes.size.toByte()))
        out.write(vcBytes)
        // Dummy audio frames payload
        out.write(byteArrayOf(0xFF.toByte(), 0xF8.toByte(), 0x69, 0x18, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55))
        return out.toByteArray()
    }

    private fun buildTestWavBytes(sampleRate: Int, channels: Int, bitDepth: Int): ByteArray {
        val dataSize = sampleRate * channels * (bitDepth / 8) // 1 second
        val byteRate = sampleRate * channels * (bitDepth / 8)
        val blockAlign = channels * (bitDepth / 8)
        val out = ByteArrayOutputStream()
        out.write("RIFF".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(36 + dataSize).array())
        out.write("WAVE".toByteArray(StandardCharsets.ISO_8859_1))
        out.write("fmt ".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(16).array())
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(1).array()) // PCM
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(channels.toShort()).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(sampleRate).array())
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(byteRate).array())
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(blockAlign.toShort()).array())
        out.write(ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN).putShort(bitDepth.toShort()).array())
        out.write("data".toByteArray(StandardCharsets.ISO_8859_1))
        out.write(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(dataSize).array())
        out.write(ByteArray(256))
        return out.toByteArray()
    }
}

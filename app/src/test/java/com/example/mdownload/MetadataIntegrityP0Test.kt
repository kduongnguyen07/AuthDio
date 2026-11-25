package com.example.mdownload

import com.example.mdownload.core.audio.ReplayGainMetadataReader
import com.example.mdownload.core.metadata.AudioContainerPrimitives
import com.example.mdownload.core.metadata.EditableTrackMetadata
import com.example.mdownload.core.metadata.MetadataTagIO
import com.example.mdownload.core.metadata.MetadataWriteResult
import com.example.mdownload.core.metadata.SafeAudioWritePipeline
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.EmbedWriteResult
import com.example.mdownload.player.lyrics.LyricsParser
import com.example.mdownload.player.lyrics.LyricsSource
import com.example.mdownload.player.lyrics.LyricsTagIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets

/**
 * Phase A (STAB-02) regression and failure-injection tests covering:
 * - A1: SafeAudioWritePipeline rollback, crash recovery, and all 8 failure-injection scenarios
 * - A2/A3: Unified ID3v2.4 per-frame unsynchronization fix across MetadataTagIO and LyricsTagIO
 * - A4: ID3v2.2 explicit rejection without tag wipe
 * - A5: Genuine pure-Kotlin MP4 metadata & ReplayGain reader (`udta/ilst/----` + QuickTime `mdta`) + `mdat` SHA-256
 * - A6: Ogg/Opus explicit artwork rejection + packet SHA-256
 * - A7: FLAC PICTURE block size, MIME, magic header, and dimension validation
 */
class MetadataIntegrityP0Test {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // =========================================================================
    // A1 — SafeAudioWritePipeline Failure-Injection Suite (8 Scenarios)
    // =========================================================================

    private class FaultInjectingFileBackend(
        private val targetFile: File,
        var mediaStoreUri: Boolean = true,
        var allowReadWrite: Boolean = true,
        var availableBytes: Long? = 100L * 1024L * 1024L,
        var failWriteAfterBytes: Long? = null,
        var truncateWriteAtBytes: Long? = null,
        var failWriteWithSecurityException: Boolean = false,
        var corruptAfterWrite: Boolean = false,
        var failRollbackWrite: Boolean = false
    ) : SafeAudioWritePipeline.AudioUriIoBackend {

        var writeCallCount = 0

        override fun isMediaStoreUri(targetUri: String): Boolean = mediaStoreUri
        override fun canOpenReadWrite(targetUri: String): Boolean = allowReadWrite
        override fun availableDestinationBytes(targetUri: String): Long? = availableBytes

        override fun openInputStream(targetUri: String): InputStream? =
            if (targetFile.isFile) FileInputStream(targetFile) else null

        override fun openTruncatingOutputStream(targetUri: String): OutputStream? {
            writeCallCount++
            val isRollbackCall = writeCallCount > 1
            if (isRollbackCall && failRollbackWrite) {
                throw IOException("Simulated rollback write failure (storage unavailable)")
            }
            if (!isRollbackCall && failWriteWithSecurityException) {
                throw SecurityException("Simulated MediaStore write permission revoked")
            }
            // Mimic Android ContentResolver.openOutputStream(uri, "wt") immediate truncation
            val fos = FileOutputStream(targetFile, false)
            if (isRollbackCall) return fos

            return object : OutputStream() {
                private var written = 0L

                override fun write(b: Int) {
                    write(byteArrayOf(b.toByte()), 0, 1)
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    val failAt = failWriteAfterBytes
                    if (failAt != null && written + len > failAt) {
                        val allowed = (failAt - written).toInt().coerceAtLeast(0)
                        if (allowed > 0) {
                            fos.write(b, off, allowed)
                            written += allowed
                        }
                        fos.flush()
                        throw IOException("Simulated ENOSPC / I/O failure mid-commit")
                    }
                    val truncAt = truncateWriteAtBytes
                    if (truncAt != null) {
                        val remaining = (truncAt - written).toInt().coerceAtLeast(0)
                        val toWrite = minOf(len, remaining)
                        if (toWrite > 0) {
                            fos.write(b, off, toWrite)
                            written += toWrite
                        }
                        return
                    }
                    fos.write(b, off, len)
                    written += len
                }

                override fun flush() {
                    fos.flush()
                }

                override fun close() {
                    fos.close()
                    if (corruptAfterWrite && targetFile.isFile && targetFile.length() > 4) {
                        val bytes = targetFile.readBytes()
                        bytes[bytes.lastIndex] = (bytes[bytes.lastIndex].toInt() xor 0xFF).toByte()
                        targetFile.writeBytes(bytes)
                    }
                }
            }
        }
    }

    @Test
    fun safePipeline_happyPathCommitsVerifiesAndCleansRollback() = runBlocking {
        val journal = tempFolder.newFolder("journal_ok")
        val target = tempFolder.newFile("song_ok.mp3")
        val originalBytes = buildId3v23Mp3(title = "Before", artist = "Artist")
        target.writeBytes(originalBytes)

        val backend = FaultInjectingFileBackend(target)
        val pipeline = SafeAudioWritePipeline(journal, backend)

        val outcome = pipeline.executeSafeWrite(
            targetUri = "content://media/external/audio/media/101",
            displayName = "song_ok.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { src, dst ->
                val res = MetadataTagIO.writeMetadataToFilePair(
                    null, src, dst, AudioContainerFormat.MP3,
                    EditableTrackMetadata(title = "After Commit", artist = "Artist", format = AudioContainerFormat.MP3)
                )
                if (res is MetadataWriteResult.Success) SafeAudioWritePipeline.TransformDecision.Ready(res)
                else SafeAudioWritePipeline.TransformDecision.Abort("Failed")
            },
            postVerifyDestinationFile = { f ->
                MetadataTagIO.readMetadataFromFile(f, "song_ok.mp3")?.title == "After Commit"
            }
        )

        assertTrue("Expected Committed, got $outcome", outcome is SafeAudioWritePipeline.CommitOutcome.Committed)
        assertEquals("After Commit", MetadataTagIO.readMetadataFromFile(target, "song_ok.mp3")?.title)
        assertTrue("Journal must be clean after verified commit", pipeline.listPendingManifests().isEmpty())
    }

    @Test
    fun safePipeline_cancellationBeforeCommitLeavesDestinationUntouchedAndCleansUp() = runBlocking {
        val journal = tempFolder.newFolder("journal_cancel")
        val target = tempFolder.newFile("song_cancel.mp3")
        val originalBytes = buildId3v23Mp3(title = "Original", artist = "Artist")
        target.writeBytes(originalBytes)

        val backend = FaultInjectingFileBackend(target)
        val pipeline = SafeAudioWritePipeline(journal, backend)

        var caughtCancel = false
        val job = launch {
            try {
                pipeline.executeSafeWrite<String>(
                    targetUri = "content://media/external/audio/media/102",
                    displayName = "song_cancel.mp3",
                    format = AudioContainerFormat.MP3,
                    operationType = SafeAudioWritePipeline.OperationType.METADATA,
                    transformFile = { _, _ ->
                        cancel("User cancelled before commit")
                        throw CancellationException("Cancelled")
                    }
                )
            } catch (_: CancellationException) {
                caughtCancel = true
            }
        }
        job.join()

        assertTrue("CancellationException should propagate", caughtCancel)
        assertEquals("Destination stream must never be opened", 0, backend.writeCallCount)
        assertArrayEquals("Original file bytes must be untouched", originalBytes, target.readBytes())
        assertTrue("Journal must be cleaned on pre-commit cancel", pipeline.listPendingManifests().isEmpty())
    }

    @Test
    fun safePipeline_exceptionDuringWriteAutomaticallyRestoresOriginalBytes() = runBlocking {
        val journal = tempFolder.newFolder("journal_write_ex")
        val target = tempFolder.newFile("song_ex.mp3")
        val originalBytes = buildId3v23Mp3(title = "Original Safe", artist = "Artist")
        target.writeBytes(originalBytes)

        val backend = FaultInjectingFileBackend(target, failWriteAfterBytes = 64L)
        val pipeline = SafeAudioWritePipeline(journal, backend)

        val outcome = pipeline.executeSafeWrite(
            targetUri = "content://media/external/audio/media/103",
            displayName = "song_ex.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { src, dst ->
                val res = MetadataTagIO.writeMetadataToFilePair(
                    null, src, dst, AudioContainerFormat.MP3,
                    EditableTrackMetadata(title = "Edited", artist = "Artist", format = AudioContainerFormat.MP3)
                )
                SafeAudioWritePipeline.TransformDecision.Ready(res)
            }
        )

        assertTrue("Expected FailedRecovered, got $outcome", outcome is SafeAudioWritePipeline.CommitOutcome.FailedRecovered)
        assertArrayEquals("Original file must be restored bit-for-bit", originalBytes, target.readBytes())
        assertTrue("Rollback cleaned only after verified restore", pipeline.listPendingManifests().isEmpty())
    }

    @Test
    fun safePipeline_partialWriteAndReadBackMismatchBothRestoreOriginalBytes() = runBlocking {
        val journal = tempFolder.newFolder("journal_partial")
        val target = tempFolder.newFile("song_partial.mp3")
        val originalBytes = buildId3v23Mp3(title = "Keep Me", artist = "Artist")
        target.writeBytes(originalBytes)

        // Case 1: Silent partial/truncated write
        val partialBackend = FaultInjectingFileBackend(target, truncateWriteAtBytes = 100L)
        val pipeline1 = SafeAudioWritePipeline(journal, partialBackend)
        val out1 = pipeline1.executeSafeWrite(
            targetUri = "content://media/external/audio/media/104",
            displayName = "song_partial.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { src, dst ->
                MetadataTagIO.writeMetadataToFilePair(
                    null, src, dst, AudioContainerFormat.MP3,
                    EditableTrackMetadata(title = "Truncated", artist = "Artist", format = AudioContainerFormat.MP3)
                )
                SafeAudioWritePipeline.TransformDecision.Ready(Unit)
            }
        )
        assertTrue("Partial write must recover: $out1", out1 is SafeAudioWritePipeline.CommitOutcome.FailedRecovered)
        assertArrayEquals("Original bytes restored after partial write", originalBytes, target.readBytes())

        // Case 2: Post-write corruption / read-back mismatch
        val corruptBackend = FaultInjectingFileBackend(target, corruptAfterWrite = true)
        val pipeline2 = SafeAudioWritePipeline(journal, corruptBackend)
        val out2 = pipeline2.executeSafeWrite(
            targetUri = "content://media/external/audio/media/104",
            displayName = "song_partial.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { src, dst ->
                MetadataTagIO.writeMetadataToFilePair(
                    null, src, dst, AudioContainerFormat.MP3,
                    EditableTrackMetadata(title = "Corrupted", artist = "Artist", format = AudioContainerFormat.MP3)
                )
                SafeAudioWritePipeline.TransformDecision.Ready(Unit)
            }
        )
        assertTrue("Read-back mismatch must recover: $out2", out2 is SafeAudioWritePipeline.CommitOutcome.FailedRecovered)
        assertArrayEquals("Original bytes restored after read-back mismatch", originalBytes, target.readBytes())
    }

    @Test
    fun safePipeline_fullDestinationStorageInvalidReplacementAndPermissionDenialAbortSafely() = runBlocking {
        val journal = tempFolder.newFolder("journal_guards")
        val target = tempFolder.newFile("song_guard.mp3")
        val originalBytes = buildId3v23Mp3(title = "Guarded", artist = "Artist")
        target.writeBytes(originalBytes)

        // 1. Insufficient destination storage
        val lowSpaceBackend = FaultInjectingFileBackend(target, availableBytes = 1024L)
        val outLowSpace = SafeAudioWritePipeline(journal, lowSpaceBackend).executeSafeWrite(
            targetUri = "content://media/external/audio/media/105",
            displayName = "song_guard.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { src, dst ->
                MetadataTagIO.writeMetadataToFilePair(
                    null, src, dst, AudioContainerFormat.MP3,
                    EditableTrackMetadata(title = "Big Edit", artist = "Artist", format = AudioContainerFormat.MP3)
                )
                SafeAudioWritePipeline.TransformDecision.Ready(Unit)
            }
        )
        assertTrue("Expected Aborted on low space, got $outLowSpace", outLowSpace is SafeAudioWritePipeline.CommitOutcome.Aborted)
        assertEquals(0, lowSpaceBackend.writeCallCount)
        assertArrayEquals(originalBytes, target.readBytes())

        // 2. Invalid / empty replacement output
        val invalidBackend = FaultInjectingFileBackend(target)
        val outInvalid = SafeAudioWritePipeline(journal, invalidBackend).executeSafeWrite(
            targetUri = "content://media/external/audio/media/105",
            displayName = "song_guard.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { _, dst ->
                dst.writeBytes(byteArrayOf(1, 2, 3)) // invalid/truncated
                SafeAudioWritePipeline.TransformDecision.Ready(Unit)
            }
        )
        assertTrue("Expected Aborted on invalid replacement, got $outInvalid", outInvalid is SafeAudioWritePipeline.CommitOutcome.Aborted)
        assertEquals(0, invalidBackend.writeCallCount)
        assertArrayEquals(originalBytes, target.readBytes())

        // 3. Permission denial (MediaStore URI)
        val deniedBackend = FaultInjectingFileBackend(target, mediaStoreUri = true, allowReadWrite = false)
        val outDenied = SafeAudioWritePipeline(journal, deniedBackend).executeSafeWrite<Unit>(
            targetUri = "content://media/external/audio/media/105",
            displayName = "song_guard.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.LYRICS,
            transformFile = { _, _ -> SafeAudioWritePipeline.TransformDecision.Ready(Unit) }
        )
        assertTrue("Expected PermissionRequired, got $outDenied", outDenied is SafeAudioWritePipeline.CommitOutcome.PermissionRequired)
        assertArrayEquals(originalBytes, target.readBytes())
    }

    @Test
    fun safePipeline_processKillAndFailedRollbackRetainsBackupUntilStartupRecoverySucceeds() = runBlocking {
        val journal = tempFolder.newFolder("journal_crash")
        val target = tempFolder.newFile("song_crash.mp3")
        val originalBytes = buildId3v23Mp3(title = "Survive Crash", artist = "Artist")
        target.writeBytes(originalBytes)

        // Simulate commit write failure AND immediate rollback failure (e.g., process killed or storage temporarily locked)
        val crashBackend = FaultInjectingFileBackend(
            targetFile = target,
            failWriteAfterBytes = 32L,
            failRollbackWrite = true
        )
        val pipeline1 = SafeAudioWritePipeline(journal, crashBackend)
        val out = pipeline1.executeSafeWrite(
            targetUri = "content://media/external/audio/media/106",
            displayName = "song_crash.mp3",
            format = AudioContainerFormat.MP3,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { src, dst ->
                MetadataTagIO.writeMetadataToFilePair(
                    null, src, dst, AudioContainerFormat.MP3,
                    EditableTrackMetadata(title = "Interrupted", artist = "Artist", format = AudioContainerFormat.MP3)
                )
                SafeAudioWritePipeline.TransformDecision.Ready(Unit)
            }
        )

        assertTrue("Must retain rollback on incomplete recovery: $out", out is SafeAudioWritePipeline.CommitOutcome.FailedRollbackRetained)
        val retainedPath = (out as SafeAudioWritePipeline.CommitOutcome.FailedRollbackRetained).rollbackPath
        assertTrue("Rollback backup file MUST remain on disk", File(retainedPath).isFile)
        assertEquals("Destination is currently damaged (32 bytes)", 32L, target.length())
        assertEquals(1, pipeline1.listPendingManifests().size)

        // Simulate next app startup where destination storage is writable again
        val recoveryBackend = FaultInjectingFileBackend(targetFile = target)
        val pipeline2 = SafeAudioWritePipeline(journal, recoveryBackend)
        val report = pipeline2.recoverInterruptedOperations()

        assertEquals(1, report.scannedCount)
        assertEquals(1, report.restoredCount)
        assertEquals(0, report.failedRetainedCount)
        assertArrayEquals("Startup recovery must restore original file bit-for-bit!", originalBytes, target.readBytes())
        assertTrue("Rollback cleaned up ONLY after verified recovery", pipeline2.listPendingManifests().isEmpty())
    }

    // =========================================================================
    // A3 — ID3v2.4 Per-Frame Unsynchronization Regression
    // =========================================================================

    @Test
    fun id3v24_perFrameUnsyncFlagClearedOnRewritePreventingDoubleCorruptionAcrossRoundTrips() {
        // JPEG payload containing 0xFF 0x00 and 0xFF 0xE0 sequences that would corrupt if de-unsynced twice!
        val originalArt = byteArrayOf(
            0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
            0x00, 0x10, 0xFF.toByte(), 0x00, 0x42, 0xFF.toByte(), 0xD9.toByte()
        )
        val originalPriv = "com.example.priv\u0000".toByteArray(StandardCharsets.ISO_8859_1) +
            byteArrayOf(0xFF.toByte(), 0x00, 0xAA.toByte(), 0xFF.toByte(), 0x00, 0xBB.toByte())

        val src = tempFolder.newFile("id3v24_unsync_src.mp3")
        src.writeBytes(buildId3v24PerFrameUnsyncMp3(
            title = "Initial v2.4 — Tiếng Việt",
            artworkBytes = originalArt,
            privPayload = originalPriv,
            replayGainDb = "-6.25 dB",
            lyrics = "[00:02.00]Unsynced v2.4 lyrics"
        ))

        val mid = tempFolder.newFile("id3v24_unsync_mid.mp3")
        val dst = tempFolder.newFile("id3v24_unsync_dst.mp3")
        val origAudioHash = MetadataTagIO.computeAudioPayloadSha256(src, AudioContainerFormat.MP3)
        assertNotNull(origAudioHash)

        // Round-trip 1: edit title only via MetadataTagIO (preserving APIC, PRIV, USLT, ReplayGain)
        val res1 = MetadataTagIO.writeMetadataToFilePair(
            null, src, mid, AudioContainerFormat.MP3,
            EditableTrackMetadata(
                title = "Round 1 — Tiếng Việt",
                artist = "Artist 1",
                format = AudioContainerFormat.MP3
            )
        )
        assertTrue("Round 1 must succeed: $res1", res1 is MetadataWriteResult.Success)

        // Verify raw ID3v2.4 frames on disk now have unsync flag bit 0x02 CLEARED!
        val parsedMid = AudioContainerPrimitives.readId3v2(mid)
        assertTrue(parsedMid is AudioContainerPrimitives.Id3v2ReadResult.Supported)
        val midFrames = (parsedMid as AudioContainerPrimitives.Id3v2ReadResult.Supported).frames
        for (frame in midFrames) {
            assertEquals("Frame ${frame.id} must NOT keep unsync bit 0x02 set", 0, frame.flags[1].toInt() and 0x02)
        }

        // Round-trip 2: edit artist again — if bit 0x02 had remained set, 0xFF 0x00 inside APIC & PRIV would be corrupted!
        val res2 = MetadataTagIO.writeMetadataToFilePair(
            null, mid, dst, AudioContainerFormat.MP3,
            EditableTrackMetadata(
                title = "Round 2 — Tiếng Việt",
                artist = "Artist 2",
                format = AudioContainerFormat.MP3
            )
        )
        assertTrue("Round 2 must succeed: $res2", res2 is MetadataWriteResult.Success)

        val readBack = MetadataTagIO.readMetadataFromFile(dst, "id3v24_unsync_dst.mp3", "audio/mpeg")
        assertNotNull(readBack)
        assertEquals("Round 2 — Tiếng Việt", readBack!!.title)
        assertEquals("Artist 2", readBack.artist)
        assertArrayEquals("APIC JPEG with 0xFF 0x00 must survive 2 round-trips bit-for-bit", originalArt, readBack.artworkBytes)

        val parsedDst = AudioContainerPrimitives.readId3v2(dst) as AudioContainerPrimitives.Id3v2ReadResult.Supported
        val privFrame = parsedDst.frames.firstOrNull { it.id == "PRIV" }
        assertNotNull("Unknown PRIV frame must survive", privFrame)
        assertArrayEquals("PRIV payload with 0xFF 0x00 must survive 2 round-trips bit-for-bit", originalPriv, privFrame!!.body)

        val rg = ReplayGainMetadataReader.readFromFile(dst, "id3v24_unsync_dst.mp3", "audio/mpeg")
        assertNotNull("ReplayGain TXXX must survive", rg)
        assertEquals(-6.25f, rg!!.trackGainDb!!, 1e-3f)

        val lyr = LyricsTagIO.readEmbeddedLyricsFromFile(dst, "k", "id3v24_unsync_dst.mp3", "audio/mpeg").lyrics
        assertNotNull("USLT lyrics must survive", lyr)
        assertTrue(lyr!!.lines.any { it.text.contains("Unsynced v2.4 lyrics") })
        assertEquals(origAudioHash, MetadataTagIO.computeAudioPayloadSha256(dst, AudioContainerFormat.MP3))

        // Also test LyricsTagIO rewrite on the original ID3v2.4 unsync file
        val lyrTarget = tempFolder.newFile("id3v24_lyrics_copy.mp3")
        src.copyTo(lyrTarget, overwrite = true)
        val newLyrics = LyricsParser.toTrackLyrics("k2", "[00:05.00]Updated via LyricsTagIO", LyricsSource.MANUAL)!!
        val embedRes = LyricsTagIO.embedLyricsIntoLocalFile(null, lyrTarget, AudioContainerFormat.MP3, newLyrics)
        assertTrue("LyricsTagIO embed on ID3v2.4 unsync must succeed: $embedRes", embedRes is EmbedWriteResult.Success)
        val afterLyrMeta = MetadataTagIO.readMetadataFromFile(lyrTarget, "id3v24_lyrics_copy.mp3", "audio/mpeg")
        assertArrayEquals("Artwork must remain intact after LyricsTagIO rewrite", originalArt, afterLyrMeta?.artworkBytes)
    }

    // =========================================================================
    // A4 — ID3v2.2 Explicit Rejection Without Tag Loss
    // =========================================================================

    @Test
    fun id3v22_rejectedExplicitlyByBothMetadataAndLyricsWritersWithoutWipingTags() {
        val v22Bytes = buildId3v22Mp3(title = "Legacy v2.2 Song")
        val src = tempFolder.newFile("legacy_v22.mp3")
        src.writeBytes(v22Bytes)
        val dst = tempFolder.newFile("legacy_v22_out.mp3")

        val metaRes = MetadataTagIO.writeMetadataToFilePair(
            null, src, dst, AudioContainerFormat.MP3,
            EditableTrackMetadata(title = "Overwrite Attempt", format = AudioContainerFormat.MP3)
        )
        assertTrue("ID3v2.2 metadata edit must be explicitly rejected: $metaRes", metaRes is MetadataWriteResult.Unsupported)
        assertArrayEquals("Original ID3v2.2 file must remain untouched", v22Bytes, src.readBytes())

        val lyr = LyricsParser.toTrackLyrics("v22", "[00:01.00]Test", LyricsSource.MANUAL)!!
        val lyrRes = LyricsTagIO.embedLyricsIntoLocalFile(null, src, AudioContainerFormat.MP3, lyr)
        assertTrue("ID3v2.2 lyrics embed must be explicitly rejected: $lyrRes", lyrRes is EmbedWriteResult.Unsupported)
        assertArrayEquals("Original ID3v2.2 file must remain untouched after lyrics attempt", v22Bytes, src.readBytes())
    }

    // =========================================================================
    // A5 — Genuine MP4 Atom Reader (`udta/ilst/----` + QuickTime `mdta`) + `mdat` SHA-256
    // =========================================================================

    @Test
    fun mp4_readsItunesUdtaAndQuicktimeMdtaMetadataReplayGainLyricsAndMdatSha256() {
        val mdatPayload = ByteArray(1024) { (it * 7 + 13).toByte() }
        val fakeArt = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0x01, 0x02, 0xFF.toByte(), 0xD9.toByte())

        // 1. Standard iTunes moov/udta/meta/ilst with ---- freeform ReplayGain + lyrics + cover
        val itunesM4a = tempFolder.newFile("sample_itunes.m4a")
        itunesM4a.writeBytes(buildSyntheticItunesMp4(
            title = "Midnight Signal",
            artist = "HiFi Artist",
            albumArtist = "HiFi Collective",
            album = "Analog Dreams",
            genre = "Synthwave",
            year = "2026",
            trackNum = 3,
            trackTot = 10,
            discNum = 1,
            discTot = 2,
            composer = "Composer A",
            comment = "Mastered for HiFiDeck",
            lyrics = "[00:04.50]MP4 lyrics preserved",
            artworkBytes = fakeArt,
            freeform = mapOf(
                "REPLAYGAIN_TRACK_GAIN" to "-5.40 dB",
                "REPLAYGAIN_ALBUM_GAIN" to "-4.10 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.912000",
                "MDOWNLOAD_YT_ID" to "MDOWNLOAD_YT_ID:abc123XYZ00"
            ),
            mdatPayload = mdatPayload
        ))

        val parsed = MetadataTagIO.readMetadataFromFile(itunesM4a, "sample_itunes.m4a", "audio/mp4")
        assertNotNull("MP4 readMetadataFromFile must NOT return empty stub", parsed)
        assertEquals("Midnight Signal", parsed!!.title)
        assertEquals("HiFi Artist", parsed.artist)
        assertEquals("HiFi Collective", parsed.albumArtist)
        assertEquals("Analog Dreams", parsed.album)
        assertEquals("Synthwave", parsed.genre)
        assertEquals("2026", parsed.year)
        assertEquals("3", parsed.trackNumber)
        assertEquals("10", parsed.trackTotal)
        assertEquals("1", parsed.discNumber)
        assertEquals("2", parsed.discTotal)
        assertEquals("Composer A", parsed.composer)
        assertEquals("Mastered for HiFiDeck", parsed.comment)
        assertArrayEquals(fakeArt, parsed.artworkBytes)

        val rgItunes = ReplayGainMetadataReader.readFromFile(itunesM4a, "sample_itunes.m4a", "audio/mp4")
        assertNotNull("Must parse iTunes ---- ReplayGain", rgItunes)
        assertEquals(-5.40f, rgItunes!!.trackGainDb!!, 1e-3f)
        assertEquals(-4.10f, rgItunes.albumGainDb!!, 1e-3f)
        assertEquals(0.912f, rgItunes.trackPeak!!, 1e-3f)

        val lyr = LyricsTagIO.readEmbeddedLyricsFromFile(itunesM4a, "mp4_k", "sample_itunes.m4a", "audio/mp4").lyrics
        assertNotNull(lyr)
        assertTrue(lyr!!.lines.any { it.text.contains("MP4 lyrics preserved") })

        val sha1 = MetadataTagIO.computeAudioPayloadSha256(itunesM4a, AudioContainerFormat.MP4_M4A)
        assertNotNull("MP4 mdat SHA-256 must be computed (not null)", sha1)

        // 2. QuickTime moov/meta/keys + ilst (mdta format written by FFmpeg -movflags use_metadata_tags)
        val mdtaM4a = tempFolder.newFile("sample_mdta.m4a")
        mdtaM4a.writeBytes(buildSyntheticQuickTimeMdtaMp4(
            tags = linkedMapOf(
                "title" to "FFmpeg Remuxed Track",
                "artist" to "QuickTime Artist",
                "REPLAYGAIN_TRACK_GAIN" to "-8.20 dB",
                "REPLAYGAIN_TRACK_PEAK" to "0.850000"
            ),
            mdatPayload = mdatPayload
        ))

        val mdtaMeta = MetadataTagIO.readMetadataFromFile(mdtaM4a, "sample_mdta.m4a", "audio/mp4")
        assertEquals("FFmpeg Remuxed Track", mdtaMeta!!.title)
        assertEquals("QuickTime Artist", mdtaMeta.artist)

        val rgMdta = ReplayGainMetadataReader.readFromFile(mdtaM4a, "sample_mdta.m4a", "audio/mp4")
        assertNotNull("ReplayGainMetadataReader MUST read QuickTime mdta keys written by FFmpeg", rgMdta)
        assertEquals(-8.20f, rgMdta!!.trackGainDb!!, 1e-3f)
        assertEquals(0.85f, rgMdta.trackPeak!!, 1e-3f)
        assertEquals("Identical mdat payloads must produce identical SHA-256", sha1, MetadataTagIO.computeAudioPayloadSha256(mdtaM4a, AudioContainerFormat.MP4_M4A))
    }

    // =========================================================================
    // A6 — Ogg / Opus Explicit Artwork Rejection & Audio Packet Hash
    // =========================================================================

    @Test
    fun oggOpus_explicitlyRejectsArtworkEditsAndComputesAudioPacketSha256() {
        val opusFile = tempFolder.newFile("sample.opus")
        opusFile.writeBytes(buildSyntheticOggOpus(
            comments = listOf(
                "TITLE" to "Opus Track",
                "ARTIST" to "Opus Artist",
                "R128_TRACK_GAIN" to "-1280"
            )
        ))
        val dst = tempFolder.newFile("sample_out.opus")

        val meta = MetadataTagIO.readMetadataFromFile(opusFile, "sample.opus", "audio/opus")
        assertNotNull(meta)
        assertEquals("Opus Track", meta!!.title)
        assertEquals("Opus Artist", meta.artist)
        assertNotNull("Ogg/Opus audio packet SHA-256 must be non-null", MetadataTagIO.computeAudioPayloadSha256(opusFile, AudioContainerFormat.OGG_OPUS))

        val replaceArtRes = MetadataTagIO.writeMetadataToFilePair(
            null, opusFile, dst, AudioContainerFormat.OGG_OPUS,
            meta.copy(
                artworkBytes = buildValidPng(32, 32),
                artworkMimeType = "image/png",
                artworkChanged = true
            )
        )
        assertTrue("Ogg/Opus artwork replace MUST return Unsupported, never Success: $replaceArtRes", replaceArtRes is MetadataWriteResult.Unsupported)

        val removeArtRes = MetadataTagIO.writeMetadataToFilePair(
            null, opusFile, dst, AudioContainerFormat.OGG_OPUS,
            meta.copy(artworkRemoved = true)
        )
        assertTrue("Ogg/Opus artwork remove MUST return Unsupported, never Success: $removeArtRes", removeArtRes is MetadataWriteResult.Unsupported)
    }

    // =========================================================================
    // A7 — FLAC PICTURE Block Validation
    // =========================================================================

    @Test
    fun flacPicture_validatesMimeSizeDimensionsMagicAndPreservesAudio() {
        val src = tempFolder.newFile("pic_src.flac")
        src.writeBytes(buildMinimalFlac(listOf("TITLE" to "FLAC Pic Test")))
        val dst = tempFolder.newFile("pic_dst.flac")
        val origSha = MetadataTagIO.computeAudioPayloadSha256(src, AudioContainerFormat.FLAC)

        // 1. Valid 64x64 PNG embeds cleanly and populates width/height in FLAC PICTURE block
        val validPng = buildValidPng(64, 64)
        val okRes = MetadataTagIO.writeMetadataToFilePair(
            null, src, dst, AudioContainerFormat.FLAC,
            EditableTrackMetadata(
                title = "FLAC With Valid PNG",
                artworkBytes = validPng,
                artworkMimeType = "image/png",
                artworkChanged = true,
                format = AudioContainerFormat.FLAC
            )
        )
        assertTrue("Valid PNG must embed into FLAC: $okRes", okRes is MetadataWriteResult.Success)
        assertEquals(origSha, MetadataTagIO.computeAudioPayloadSha256(dst, AudioContainerFormat.FLAC))
        val blocks = AudioContainerPrimitives.readFlacBlocks(dst)!!.first
        val picBlock = blocks.first { it.type == 6 }
        // Width/Height at bytes: 4 (type) + 4 (mimeLen) + 9 ("image/png") + 4 (descLen=0) = offset 21
        assertEquals(64, AudioContainerPrimitives.readBigEndianInt(picBlock.payload, 21))
        assertEquals(64, AudioContainerPrimitives.readBigEndianInt(picBlock.payload, 25))

        // 2. Oversized artwork (> 4 MiB) rejected
        val hugeBytes = buildValidPng(64, 64) + ByteArray(4 * 1024 * 1024 + 128)
        val hugeRes = MetadataTagIO.writeMetadataToFilePair(
            null, src, tempFolder.newFile("pic_huge.flac"), AudioContainerFormat.FLAC,
            EditableTrackMetadata(
                title = "Too Big",
                artworkBytes = hugeBytes,
                artworkMimeType = "image/png",
                artworkChanged = true,
                format = AudioContainerFormat.FLAC
            )
        )
        assertTrue("Oversized FLAC artwork must fail safely: $hugeRes", hugeRes is MetadataWriteResult.Failed)

        // 3. Unsupported MIME rejected
        val badMimeRes = MetadataTagIO.writeMetadataToFilePair(
            null, src, tempFolder.newFile("pic_mime.flac"), AudioContainerFormat.FLAC,
            EditableTrackMetadata(
                title = "Bad Mime",
                artworkBytes = validPng,
                artworkMimeType = "image/gif",
                artworkChanged = true,
                format = AudioContainerFormat.FLAC
            )
        )
        assertTrue("Unsupported MIME must fail safely: $badMimeRes", badMimeRes is MetadataWriteResult.Failed)

        // 4. Decompression-bomb dimensions (8000x8000 in PNG IHDR) rejected
        val bombPng = buildValidPng(8000, 8000)
        val bombRes = MetadataTagIO.writeMetadataToFilePair(
            null, src, tempFolder.newFile("pic_bomb.flac"), AudioContainerFormat.FLAC,
            EditableTrackMetadata(
                title = "Huge Dims",
                artworkBytes = bombPng,
                artworkMimeType = "image/png",
                artworkChanged = true,
                format = AudioContainerFormat.FLAC
            )
        )
        assertTrue("Oversized PNG dimensions must fail safely: $bombRes", bombRes is MetadataWriteResult.Failed)

        // 5. Corrupt non-PNG bytes claiming image/png rejected
        val corruptRes = MetadataTagIO.writeMetadataToFilePair(
            null, src, tempFolder.newFile("pic_corrupt.flac"), AudioContainerFormat.FLAC,
            EditableTrackMetadata(
                title = "Corrupt",
                artworkBytes = "not a png at all".toByteArray(),
                artworkMimeType = "image/png",
                artworkChanged = true,
                format = AudioContainerFormat.FLAC
            )
        )
        assertTrue("Corrupt PNG bytes must fail safely: $corruptRes", corruptRes is MetadataWriteResult.Failed)
    }

    // =========================================================================
    // Synthetic Binary Fixture Builders
    // =========================================================================

    private fun applyUnsync(raw: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(raw.size + 16)
        for (i in raw.indices) {
            out.write(raw[i].toInt())
            if (raw[i] == 0xFF.toByte() &&
                (i + 1 < raw.size && (raw[i + 1] == 0x00.toByte() || (raw[i + 1].toInt() and 0xE0) == 0xE0))
            ) {
                out.write(0x00)
            }
        }
        return out.toByteArray()
    }

    private fun buildId3v23Mp3(title: String, artist: String): ByteArray {
        val frames = ByteArrayOutputStream()
        fun writeFrame(id: String, text: String) {
            val body = ByteArrayOutputStream().apply {
                write(3)
                write(text.toByteArray(Charsets.UTF_8))
            }.toByteArray()
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(AudioContainerPrimitives.writeBigEndianInt(body.size))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }
        writeFrame("TIT2", title)
        writeFrame("TPE1", artist)
        val payload = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(3, 0, 0))
        out.write(AudioContainerPrimitives.writeSynchsafeInt(payload.size))
        out.write(payload)
        out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        out.write(ByteArray(512) { (it and 0x7F).toByte() })
        return out.toByteArray()
    }

    private fun buildId3v24PerFrameUnsyncMp3(
        title: String,
        artworkBytes: ByteArray,
        privPayload: ByteArray,
        replayGainDb: String,
        lyrics: String
    ): ByteArray {
        val frames = ByteArrayOutputStream()
        fun writeV24Frame(id: String, rawBody: ByteArray, perFrameUnsync: Boolean) {
            val finalBody = if (perFrameUnsync) applyUnsync(rawBody) else rawBody
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            frames.write(AudioContainerPrimitives.writeSynchsafeInt(finalBody.size))
            frames.write(byteArrayOf(0, if (perFrameUnsync) 0x02 else 0x00))
            frames.write(finalBody)
        }

        // TIT2 (normal UTF-8)
        writeV24Frame("TIT2", byteArrayOf(3) + title.toByteArray(Charsets.UTF_8), perFrameUnsync = false)

        // APIC with per-frame unsynchronization (flag 0x02)
        val apicBody = ByteArrayOutputStream().apply {
            write(0)
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(3)
            write(0)
            write(artworkBytes)
        }.toByteArray()
        writeV24Frame("APIC", apicBody, perFrameUnsync = true)

        // PRIV with per-frame unsynchronization (flag 0x02)
        writeV24Frame("PRIV", privPayload, perFrameUnsync = true)

        // TXXX ReplayGain
        val txxxBody = ByteArrayOutputStream().apply {
            write(3)
            write("REPLAYGAIN_TRACK_GAIN".toByteArray(Charsets.UTF_8))
            write(0)
            write(replayGainDb.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        writeV24Frame("TXXX", txxxBody, perFrameUnsync = false)

        // USLT lyrics
        val usltBody = ByteArrayOutputStream().apply {
            write(3)
            write("eng".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(lyrics.toByteArray(Charsets.UTF_8))
        }.toByteArray()
        writeV24Frame("USLT", usltBody, perFrameUnsync = false)

        val payload = frames.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(4, 0, 0))
        out.write(AudioContainerPrimitives.writeSynchsafeInt(payload.size))
        out.write(payload)
        out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        out.write(ByteArray(512) { (it * 3).toByte() })
        return out.toByteArray()
    }

    private fun buildId3v22Mp3(title: String): ByteArray {
        val tt2Body = byteArrayOf(0) + title.toByteArray(Charsets.ISO_8859_1)
        val frame = ByteArrayOutputStream().apply {
            write("TT2".toByteArray(Charsets.ISO_8859_1))
            write((tt2Body.size ushr 16) and 0xFF)
            write((tt2Body.size ushr 8) and 0xFF)
            write(tt2Body.size and 0xFF)
            write(tt2Body)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        out.write("ID3".toByteArray(Charsets.ISO_8859_1))
        out.write(byteArrayOf(2, 0, 0)) // Major version 2 (ID3v2.2)
        out.write(AudioContainerPrimitives.writeSynchsafeInt(frame.size))
        out.write(frame)
        out.write(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00))
        out.write(ByteArray(256) { 0x55 })
        return out.toByteArray()
    }

    private fun buildBox(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        out.write(AudioContainerPrimitives.writeBigEndianInt(8 + payload.size))
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    private fun buildDataAtom(typeIndicator: Int, data: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream(8 + data.size).apply {
            write(AudioContainerPrimitives.writeBigEndianInt(typeIndicator))
            write(AudioContainerPrimitives.writeBigEndianInt(0))
            write(data)
        }.toByteArray()
        return buildBox("data", payload)
    }

    private fun buildSyntheticItunesMp4(
        title: String,
        artist: String,
        albumArtist: String,
        album: String,
        genre: String,
        year: String,
        trackNum: Int,
        trackTot: Int,
        discNum: Int,
        discTot: Int,
        composer: String,
        comment: String,
        lyrics: String,
        artworkBytes: ByteArray,
        freeform: Map<String, String>,
        mdatPayload: ByteArray
    ): ByteArray {
        val ilstBody = ByteArrayOutputStream()
        fun textAtom(fourcc: String, value: String) {
            ilstBody.write(buildBox(fourcc, buildDataAtom(1, value.toByteArray(Charsets.UTF_8))))
        }
        fun pairAtom(fourcc: String, num: Int, tot: Int) {
            val bytes = byteArrayOf(
                0, 0,
                ((num ushr 8) and 0xFF).toByte(), (num and 0xFF).toByte(),
                ((tot ushr 8) and 0xFF).toByte(), (tot and 0xFF).toByte(),
                0, 0
            )
            ilstBody.write(buildBox(fourcc, buildDataAtom(0, bytes)))
        }
        textAtom("\u00A9nam", title)
        textAtom("\u00A9ART", artist)
        textAtom("aART", albumArtist)
        textAtom("\u00A9alb", album)
        textAtom("\u00A9gen", genre)
        textAtom("\u00A9day", year)
        pairAtom("trkn", trackNum, trackTot)
        pairAtom("disk", discNum, discTot)
        textAtom("\u00A9wrt", composer)
        textAtom("\u00A9cmt", comment)
        textAtom("\u00A9lyr", lyrics)
        ilstBody.write(buildBox("covr", buildDataAtom(13, artworkBytes)))
        for ((k, v) in freeform) {
            val mean = buildBox("mean", byteArrayOf(0, 0, 0, 0) + "com.apple.iTunes".toByteArray(Charsets.UTF_8))
            val name = buildBox("name", byteArrayOf(0, 0, 0, 0) + k.toByteArray(Charsets.UTF_8))
            val data = buildDataAtom(1, v.toByteArray(Charsets.UTF_8))
            ilstBody.write(buildBox("----", mean + name + data))
        }
        val ilst = buildBox("ilst", ilstBody.toByteArray())
        val meta = buildBox("meta", byteArrayOf(0, 0, 0, 0) + ilst)
        val udta = buildBox("udta", meta)
        val moov = buildBox("moov", udta)
        val ftyp = buildBox("ftyp", "M4A \u0000\u0000\u0000\u0000M4A isom".toByteArray(Charsets.ISO_8859_1))
        val mdat = buildBox("mdat", mdatPayload)
        return ftyp + moov + mdat
    }

    private fun buildSyntheticQuickTimeMdtaMp4(
        tags: LinkedHashMap<String, String>,
        mdatPayload: ByteArray
    ): ByteArray {
        val keysBody = ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 0)) // version/flags
            write(AudioContainerPrimitives.writeBigEndianInt(tags.size))
            for (key in tags.keys) {
                write(buildBox("mdta", key.toByteArray(Charsets.UTF_8)))
            }
        }.toByteArray()
        val ilstBody = ByteArrayOutputStream().apply {
            var idx = 1
            for (value in tags.values) {
                val dataAtom = buildDataAtom(1, value.toByteArray(Charsets.UTF_8))
                write(AudioContainerPrimitives.writeBigEndianInt(8 + dataAtom.size))
                write(AudioContainerPrimitives.writeBigEndianInt(idx))
                write(dataAtom)
                idx++
            }
        }.toByteArray()
        val meta = buildBox("meta", byteArrayOf(0, 0, 0, 0) + buildBox("keys", keysBody) + buildBox("ilst", ilstBody))
        val moov = buildBox("moov", meta)
        val ftyp = buildBox("ftyp", "M4A \u0000\u0000\u0000\u0000M4A isom".toByteArray(Charsets.ISO_8859_1))
        val mdat = buildBox("mdat", mdatPayload)
        return ftyp + moov + mdat
    }

    private fun buildOggPage(packet: ByteArray, pageSeq: Int): ByteArray {
        val segs = mutableListOf<Int>()
        var rem = packet.size
        while (rem >= 255) {
            segs.add(255)
            rem -= 255
        }
        segs.add(rem)
        val out = ByteArrayOutputStream()
        out.write("OggS".toByteArray(Charsets.ISO_8859_1))
        out.write(0) // version
        out.write(if (pageSeq == 0) 2 else 0) // BOS on first page
        out.write(ByteArray(8)) // granule
        out.write(AudioContainerPrimitives.writeLittleEndianInt(1)) // serial
        out.write(AudioContainerPrimitives.writeLittleEndianInt(pageSeq))
        out.write(ByteArray(4)) // checksum placeholder for synthetic reader test
        out.write(segs.size)
        for (s in segs) out.write(s)
        out.write(packet)
        return out.toByteArray()
    }

    private fun buildSyntheticOggOpus(comments: List<Pair<String, String>>): ByteArray {
        val opusHead = "OpusHead".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 2, 0, 0, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        val opusTags = "OpusTags".toByteArray(Charsets.ISO_8859_1) + AudioContainerPrimitives.buildVorbisComment("HiFiDeckTest", comments)
        val audioPkt = ByteArray(128) { (it * 5).toByte() }
        return buildOggPage(opusHead, 0) + buildOggPage(opusTags, 1) + buildOggPage(audioPkt, 2)
    }

    private fun buildMinimalFlac(comments: List<Pair<String, String>>): ByteArray {
        val streamInfo = ByteArray(34)
        val commentPayload = AudioContainerPrimitives.buildVorbisComment("HiFiDeck", comments)
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        // STREAMINFO (type 0, not last)
        out.write(0)
        out.write(0); out.write(0); out.write(34)
        out.write(streamInfo)
        // VORBIS_COMMENT (type 4, last)
        out.write(0x84)
        out.write((commentPayload.size ushr 16) and 0xFF)
        out.write((commentPayload.size ushr 8) and 0xFF)
        out.write(commentPayload.size and 0xFF)
        out.write(commentPayload)
        // Audio frames
        out.write(ByteArray(512) { (it * 11).toByte() })
        return out.toByteArray()
    }

    private fun buildValidPng(width: Int, height: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        out.write(AudioContainerPrimitives.writeBigEndianInt(13)) // IHDR length
        out.write("IHDR".toByteArray(Charsets.ISO_8859_1))
        out.write(AudioContainerPrimitives.writeBigEndianInt(width))
        out.write(AudioContainerPrimitives.writeBigEndianInt(height))
        out.write(byteArrayOf(8, 2, 0, 0, 0)) // 8-bit RGB
        out.write(ByteArray(4)) // CRC placeholder
        return out.toByteArray()
    }
}

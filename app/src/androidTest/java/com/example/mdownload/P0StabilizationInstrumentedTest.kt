package com.example.mdownload

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.audio.GainApplicationPath
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.core.audio.ReplayGainConfig
import com.example.mdownload.core.audio.ReplayGainInfo
import com.example.mdownload.core.audio.ReplayGainMetadataReader
import com.example.mdownload.core.audio.ReplayGainMode
import com.example.mdownload.core.metadata.AndroidContentResolverBackend
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
import com.example.mdownload.player.playback.MediaItemMapper
import com.example.mdownload.player.playback.PlaybackGainCoordinator
import com.example.mdownload.player.playback.ReplayGainAudioProcessor
import com.example.mdownload.player.playback.SleepTimerController
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device P0 Stabilization Verification Suite (runs exclusively on disposable fixtures in cacheDir;
 * NEVER reads, writes, or modifies any real user music files on the device).
 *
 * Verifies on real Android hardware (vivo X200s / OriginOS 7 / Android 17):
 * 1. Metadata & Lyrics Integrity Matrix across real FFmpeg-generated MP3, FLAC, M4A, and Ogg/Opus files
 *    (tag persistence, comment clearing, ReplayGain preservation, lyrics preservation, artwork handling,
 *    Ogg artwork explicit rejection, and bit-exact audio-payload SHA-256 preservation).
 * 2. SafeAudioWritePipeline real Android storage commit & simulated crash recovery.
 * 3. Real Media3 ExoPlayer + DefaultAudioSink + ReplayGainAudioProcessor + PlaybackGainCoordinator
 *    cold-start fallback -> PCM handoff with ZERO double attenuation, in-place replaceMediaItem metadata
 *    sync without playback restart, and SleepTimerController expiry without volume spike.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class P0StabilizationInstrumentedTest {

    private fun appContext(): Context =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private fun ffmpegBinary(ctx: Context): File {
        val bin = File(ctx.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        assertTrue("Bundled libffmpeg.so must exist at ${bin.absolutePath}", bin.isFile && bin.canExecute())
        return bin
    }

    private fun runFfmpeg(ctx: Context, args: List<String>) {
        val bin = ffmpegBinary(ctx)
        val packagesDir = File(File(ctx.noBackupFilesDir, com.yausername.youtubedl_android.YoutubeDL.baseName), "packages")
        val ffmpegLibDir = File(packagesDir, "ffmpeg/usr/lib")
        if (!ffmpegLibDir.isDirectory) {
            runCatching { com.yausername.youtubedl_android.YoutubeDL.getInstance().init(ctx) }
            runCatching { com.yausername.ffmpeg.FFmpeg.getInstance().init(ctx) }
        }
        assertTrue("FFmpeg shared libs must exist at ${ffmpegLibDir.absolutePath}", ffmpegLibDir.isDirectory)
        val cmd = listOf(bin.absolutePath, "-y", "-v", "error") + args
        val pb = ProcessBuilder(cmd).redirectErrorStream(true)
        pb.environment()["LD_LIBRARY_PATH"] = listOf(
            ffmpegLibDir.absolutePath,
            File(packagesDir, "python/usr/lib").absolutePath
        ).joinToString(":")
        val proc = pb.start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("FFmpeg timed out", proc.waitFor(25, TimeUnit.SECONDS))
        assertEquals("FFmpeg failed ($cmd): $out", 0, proc.exitValue())
    }

    private fun makeValidPngBytes(width: Int = 64, height: Int = 64, color: Int = 0xFF3366CC.toInt()): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(color)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            bmp.recycle()
            out.toByteArray()
        }
    }

    // =========================================================================
    // 1. Metadata & Lyrics Integrity Matrix (MP3, FLAC, M4A, Ogg/Opus)
    // =========================================================================

    @Test
    fun realFfmpeg_mp3FlacM4aOggMetadataAndLyricsMatrixPreservesAudioAndReplayGain() {
        val ctx = appContext()
        val workDir = File(ctx.cacheDir, "p0_matrix_${System.currentTimeMillis()}").apply { mkdirs() }
        try {
            val cover1 = File(workDir, "cover1.png").apply { writeBytes(makeValidPngBytes(64, 64, 0xFF2255AA.toInt())) }
            val cover2Bytes = makeValidPngBytes(80, 80, 0xFFCC4422.toInt())

            // --- A. MP3 (ID3v2.3 + ReplayGain + Lyrics + Cover) ---
            val mp3Src = File(workDir, "fixture.mp3")
            val mp3Out = File(workDir, "fixture_edited.mp3")
            runFfmpeg(ctx, listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1.5",
                "-i", cover1.absolutePath,
                "-map", "0:a", "-map", "1:v",
                "-c:a", "libmp3lame", "-b:a", "192k", "-c:v", "png",
                "-id3v2_version", "3",
                "-metadata", "title=Orig MP3",
                "-metadata", "artist=Orig Artist",
                "-metadata", "comment=Old MP3 Comment",
                "-metadata", "REPLAYGAIN_TRACK_GAIN=-5.50 dB",
                "-metadata", "REPLAYGAIN_TRACK_PEAK=0.912000",
                "-metadata", "MDOWNLOAD_YT_ID=yt_mp3_01",
                mp3Src.absolutePath
            ))
            // Seed initial lyrics via LyricsTagIO so we prove metadata edit preserves existing lyrics
            val seedMp3Lyrics = LyricsParser.toTrackLyrics("mp3_seed", "[00:00.50]Seed MP3 lyric", LyricsSource.MANUAL)!!
            assertTrue(LyricsTagIO.embedLyricsIntoLocalFile(ctx, mp3Src, AudioContainerFormat.MP3, seedMp3Lyrics) is EmbedWriteResult.Success)
            val mp3OrigAudioSha = MetadataTagIO.computeAudioPayloadSha256(mp3Src, AudioContainerFormat.MP3)
            assertNotNull(mp3OrigAudioSha)

            val mp3Res = MetadataTagIO.writeMetadataToFilePair(
                ctx, mp3Src, mp3Out, AudioContainerFormat.MP3,
                EditableTrackMetadata(
                    title = "Edited MP3",
                    artist = "Edited Artist",
                    album = "Edited Album",
                    albumArtist = "Album Collective",
                    genre = "Electronic",
                    year = "2026",
                    trackNumber = "2/10",
                    comment = "", // explicitly cleared!
                    artworkBytes = cover2Bytes,
                    artworkMimeType = "image/png",
                    artworkChanged = true,
                    format = AudioContainerFormat.MP3
                )
            )
            assertTrue("MP3 metadata edit must succeed: $mp3Res", mp3Res is MetadataWriteResult.Success)
            val mp3Read = MetadataTagIO.readMetadataFromFile(mp3Out, mp3Out.name, "audio/mpeg")!!
            assertEquals("Edited MP3", mp3Read.title)
            assertEquals("Edited Artist", mp3Read.artist)
            assertEquals("Album Collective", mp3Read.albumArtist)
            assertEquals("", mp3Read.comment)
            assertArrayEquals(cover2Bytes, mp3Read.artworkBytes)
            assertEquals(mp3OrigAudioSha, MetadataTagIO.computeAudioPayloadSha256(mp3Out, AudioContainerFormat.MP3))
            val mp3Rg = ReplayGainMetadataReader.readFromFile(mp3Out, mp3Out.name, "audio/mpeg")
            assertNotNull("MP3 ReplayGain must survive edit", mp3Rg)
            assertEquals(-5.50f, mp3Rg!!.trackGainDb!!, 1e-2f)
            val mp3PreservedLyrics = LyricsTagIO.readEmbeddedLyricsFromFile(mp3Out, "k", mp3Out.name, "audio/mpeg").lyrics
            assertNotNull("MP3 existing lyrics must survive metadata edit", mp3PreservedLyrics)

            // Embed updated lyrics on edited MP3 and verify metadata + RG + artwork + audio SHA survive
            val newLyrics = LyricsParser.toTrackLyrics("upd", "[00:01.00]Verified on vivo X200s", LyricsSource.MANUAL)!!
            assertTrue(LyricsTagIO.embedLyricsIntoLocalFile(ctx, mp3Out, AudioContainerFormat.MP3, newLyrics) is EmbedWriteResult.Success)
            assertEquals("Edited MP3", MetadataTagIO.readMetadataFromFile(mp3Out, mp3Out.name, "audio/mpeg")?.title)
            assertEquals(mp3OrigAudioSha, MetadataTagIO.computeAudioPayloadSha256(mp3Out, AudioContainerFormat.MP3))

            // --- B. FLAC (Vorbis comments + ReplayGain + Lyrics + Validated PICTURE) ---
            val flacSrc = File(workDir, "fixture.flac")
            val flacOut = File(workDir, "fixture_edited.flac")
            runFfmpeg(ctx, listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1.5",
                "-c:a", "flac",
                "-metadata", "TITLE=Orig FLAC",
                "-metadata", "ARTIST=Orig Artist",
                "-metadata", "COMMENT=Old FLAC Comment",
                "-metadata", "REPLAYGAIN_TRACK_GAIN=-4.25 dB",
                "-metadata", "REPLAYGAIN_TRACK_PEAK=0.880000",
                "-metadata", "LYRICS=[00:00.50]Seed FLAC lyric",
                "-metadata", "MDOWNLOAD_YT_ID=yt_flac_01",
                flacSrc.absolutePath
            ))
            val flacOrigAudioSha = MetadataTagIO.computeAudioPayloadSha256(flacSrc, AudioContainerFormat.FLAC)
            assertNotNull(flacOrigAudioSha)

            val flacRes = MetadataTagIO.writeMetadataToFilePair(
                ctx, flacSrc, flacOut, AudioContainerFormat.FLAC,
                EditableTrackMetadata(
                    title = "Edited FLAC",
                    artist = "HiFi Master",
                    album = "Lossless Vault",
                    albumArtist = "HiFi Master",
                    genre = "Jazz",
                    year = "2026",
                    trackNumber = "1",
                    comment = "",
                    artworkBytes = cover2Bytes,
                    artworkMimeType = "image/png",
                    artworkChanged = true,
                    format = AudioContainerFormat.FLAC
                )
            )
            assertTrue("FLAC metadata edit must succeed: $flacRes", flacRes is MetadataWriteResult.Success)
            val flacRead = MetadataTagIO.readMetadataFromFile(flacOut, flacOut.name, "audio/flac")!!
            assertEquals("Edited FLAC", flacRead.title)
            assertEquals("HiFi Master", flacRead.artist)
            assertEquals("", flacRead.comment)
            assertArrayEquals(cover2Bytes, flacRead.artworkBytes)
            assertEquals(flacOrigAudioSha, MetadataTagIO.computeAudioPayloadSha256(flacOut, AudioContainerFormat.FLAC))
            val flacRg = ReplayGainMetadataReader.readFromFile(flacOut, flacOut.name, "audio/flac")
            assertNotNull("FLAC ReplayGain must survive edit", flacRg)
            assertEquals(-4.25f, flacRg!!.trackGainDb!!, 1e-2f)
            assertNotNull("FLAC lyrics must survive metadata edit", LyricsTagIO.readEmbeddedLyricsFromFile(flacOut, "k", flacOut.name, "audio/flac").lyrics)

            assertTrue(LyricsTagIO.embedLyricsIntoLocalFile(ctx, flacOut, AudioContainerFormat.FLAC, newLyrics) is EmbedWriteResult.Success)
            assertEquals("Edited FLAC", MetadataTagIO.readMetadataFromFile(flacOut, flacOut.name, "audio/flac")?.title)
            assertEquals(flacOrigAudioSha, MetadataTagIO.computeAudioPayloadSha256(flacOut, AudioContainerFormat.FLAC))

            // --- C. M4A / MP4 (AAC + ReplayGain + Lyrics + Comment Clear + mdat SHA-256) ---
            val m4aSrc = File(workDir, "fixture.m4a")
            val m4aOut = File(workDir, "fixture_edited.m4a")
            runFfmpeg(ctx, listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1.5",
                "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+use_metadata_tags+faststart",
                "-metadata", "title=Orig M4A",
                "-metadata", "artist=Orig Artist",
                "-metadata", "comment=Old M4A Comment",
                "-metadata", "REPLAYGAIN_TRACK_GAIN=-6.10 dB",
                "-metadata", "REPLAYGAIN_TRACK_PEAK=0.945000",
                "-metadata", "lyrics=[00:00.50]Seed M4A lyric",
                "-metadata", "MDOWNLOAD_YT_ID=yt_m4a_01",
                m4aSrc.absolutePath
            ))
            val m4aOrigMdatSha = AudioContainerPrimitives.computeMp4MdatSha256(m4aSrc)
            assertNotNull("Original M4A mdat SHA-256 must be computed", m4aOrigMdatSha)

            val m4aRes = MetadataTagIO.writeMetadataToFilePair(
                ctx, m4aSrc, m4aOut, AudioContainerFormat.MP4_M4A,
                EditableTrackMetadata(
                    title = "Edited M4A",
                    artist = "M4A Artist",
                    album = "M4A Album",
                    albumArtist = "M4A Collective",
                    genre = "Acoustic",
                    year = "2026",
                    trackNumber = "3",
                    comment = "", // explicitly cleared!
                    format = AudioContainerFormat.MP4_M4A
                )
            )
            assertTrue("M4A metadata edit must succeed: $m4aRes", m4aRes is MetadataWriteResult.Success)
            val m4aRead = MetadataTagIO.readMetadataFromFile(m4aOut, m4aOut.name, "audio/mp4")!!
            assertEquals("Edited M4A", m4aRead.title)
            assertEquals("M4A Artist", m4aRead.artist)
            assertEquals("M4A Album", m4aRead.album)
            assertEquals("", m4aRead.comment)
            assertEquals("M4A mdat audio payload must remain bit-exact", m4aOrigMdatSha, AudioContainerPrimitives.computeMp4MdatSha256(m4aOut))
            val m4aRg = ReplayGainMetadataReader.readFromFile(m4aOut, m4aOut.name, "audio/mp4")
            assertNotNull("M4A ReplayGain must remain readable after FFmpeg remux", m4aRg)
            assertEquals(-6.10f, m4aRg!!.trackGainDb!!, 1e-2f)
            val m4aLyricsAfterMeta = LyricsTagIO.readEmbeddedLyricsFromFile(m4aOut, "k", m4aOut.name, "audio/mp4").lyrics
            assertNotNull("M4A lyrics must survive metadata edit", m4aLyricsAfterMeta)

            // Now embed new lyrics into M4A and verify metadata + RG + mdat SHA survive!
            assertTrue(LyricsTagIO.embedLyricsIntoLocalFile(ctx, m4aOut, AudioContainerFormat.MP4_M4A, newLyrics) is EmbedWriteResult.Success)
            val m4aAfterLyr = MetadataTagIO.readMetadataFromFile(m4aOut, m4aOut.name, "audio/mp4")!!
            assertEquals("Edited M4A", m4aAfterLyr.title)
            assertEquals(-6.10f, ReplayGainMetadataReader.readFromFile(m4aOut, m4aOut.name, "audio/mp4")!!.trackGainDb!!, 1e-2f)
            assertEquals(m4aOrigMdatSha, AudioContainerPrimitives.computeMp4MdatSha256(m4aOut))

            // --- D. Ogg / Opus (Text Edit + R128 Preservation + Explicit Artwork Rejection) ---
            val opusSrc = File(workDir, "fixture.opus")
            val opusOut = File(workDir, "fixture_edited.opus")
            runFfmpeg(ctx, listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1.5",
                "-c:a", "libopus", "-b:a", "128k",
                "-metadata", "TITLE=Orig Opus",
                "-metadata", "ARTIST=Orig Artist",
                "-metadata", "COMMENT=Old Opus Comment",
                "-metadata", "REPLAYGAIN_TRACK_GAIN=-3.50 dB",
                "-metadata", "LYRICS=[00:00.50]Seed Opus lyric",
                opusSrc.absolutePath
            ))
            val opusOrigAudioSha = MetadataTagIO.computeAudioPayloadSha256(opusSrc, AudioContainerFormat.OGG_OPUS)
            assertNotNull(opusOrigAudioSha)

            // Artwork edit on Ogg/Opus MUST be explicitly rejected as Unsupported!
            val opusArtAttempt = MetadataTagIO.writeMetadataToFilePair(
                ctx, opusSrc, opusOut, AudioContainerFormat.OGG_OPUS,
                EditableTrackMetadata(
                    title = "Should Fail",
                    artworkBytes = cover2Bytes,
                    artworkMimeType = "image/png",
                    artworkChanged = true,
                    format = AudioContainerFormat.OGG_OPUS
                )
            )
            assertTrue("Ogg/Opus artwork edit must be rejected explicitly: $opusArtAttempt", opusArtAttempt is MetadataWriteResult.Unsupported)

            // Text-only edit on Ogg/Opus succeeds & preserves audio packets + RG + lyrics!
            val opusTextRes = MetadataTagIO.writeMetadataToFilePair(
                ctx, opusSrc, opusOut, AudioContainerFormat.OGG_OPUS,
                EditableTrackMetadata(
                    title = "Edited Opus",
                    artist = "Opus Artist",
                    album = "Opus Album",
                    comment = "",
                    format = AudioContainerFormat.OGG_OPUS
                )
            )
            assertTrue("Ogg/Opus text metadata edit must succeed: $opusTextRes", opusTextRes is MetadataWriteResult.Success)
            val opusRead = MetadataTagIO.readMetadataFromFile(opusOut, opusOut.name, "audio/ogg")!!
            assertEquals("Edited Opus", opusRead.title)
            assertEquals("Opus Artist", opusRead.artist)
            assertEquals("", opusRead.comment)
            assertEquals(opusOrigAudioSha, MetadataTagIO.computeAudioPayloadSha256(opusOut, AudioContainerFormat.OGG_OPUS))
            val opusRg = ReplayGainMetadataReader.readFromFile(opusOut, opusOut.name, "audio/ogg")
            assertNotNull("Opus ReplayGain must survive text edit", opusRg)
            assertEquals(-3.50f, opusRg!!.trackGainDb!!, 1e-2f)
            assertNotNull("Opus lyrics must survive text edit", LyricsTagIO.readEmbeddedLyricsFromFile(opusOut, "k", opusOut.name, "audio/ogg").lyrics)

            // Also verify LyricsTagIO embed on Ogg/Opus preserves edited metadata + RG + audio packets!
            assertTrue(LyricsTagIO.embedLyricsIntoLocalFile(ctx, opusOut, AudioContainerFormat.OGG_OPUS, newLyrics) is EmbedWriteResult.Success)
            assertEquals("Edited Opus", MetadataTagIO.readMetadataFromFile(opusOut, opusOut.name, "audio/ogg")?.title)
            val updatedOpusLyr = LyricsTagIO.readEmbeddedLyricsFromFile(opusOut, "k", opusOut.name, "audio/ogg").lyrics
            assertTrue("Updated Opus lyrics must persist", updatedOpusLyr?.lines?.any { it.text.contains("Verified on vivo X200s") } == true)
            assertEquals(opusOrigAudioSha, MetadataTagIO.computeAudioPayloadSha256(opusOut, AudioContainerFormat.OGG_OPUS))
        } finally {
            workDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 2. SafeAudioWritePipeline Real Android Storage Commit & Crash Recovery
    // =========================================================================

    @Test
    fun safeAudioWritePipeline_realAndroidFileCommitAndCrashRecoveryOnDevice() = runBlocking {
        val ctx = appContext()
        val workDir = File(ctx.cacheDir, "p0_pipeline_${System.currentTimeMillis()}").apply { mkdirs() }
        val journalDir = File(workDir, "journal")
        try {
            val targetFile = File(workDir, "disposable.flac")
            runFfmpeg(ctx, listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=1.0",
                "-c:a", "flac",
                "-metadata", "TITLE=Before Pipeline",
                targetFile.absolutePath
            ))
            val origBytes = targetFile.readBytes()
            val origSha = AudioContainerPrimitives.computeFileSha256(targetFile)!!

            val pipeline = SafeAudioWritePipeline(
                journalRootDir = journalDir,
                backend = AndroidContentResolverBackend(ctx)
            )
            val fileUri = Uri.fromFile(targetFile).toString()

            // A. Happy-path commit via SafeAudioWritePipeline -> verifies & cleans journal
            val commitOutcome = pipeline.executeSafeWrite(
                targetUri = fileUri,
                displayName = targetFile.name,
                format = AudioContainerFormat.FLAC,
                operationType = SafeAudioWritePipeline.OperationType.METADATA,
                transformFile = { src, stagedOut ->
                    val res = MetadataTagIO.writeMetadataToFilePair(
                        ctx, src, stagedOut, AudioContainerFormat.FLAC,
                        EditableTrackMetadata(title = "After Pipeline", format = AudioContainerFormat.FLAC)
                    )
                    if (res is MetadataWriteResult.Success) SafeAudioWritePipeline.TransformDecision.Ready(res)
                    else SafeAudioWritePipeline.TransformDecision.Abort("Failed: $res", res)
                }
            )
            assertTrue("Pipeline commit must succeed: $commitOutcome", commitOutcome is SafeAudioWritePipeline.CommitOutcome.Committed<*>)
            assertEquals("After Pipeline", MetadataTagIO.readMetadataFromFile(targetFile, targetFile.name, "audio/flac")?.title)
            assertTrue("Journal must be cleaned after verified commit", pipeline.listPendingManifests().isEmpty())

            // B. Simulate interrupted mid-commit crash (truncated destination + COMMITTING manifest on real storage)
            val opDir = File(journalDir, "sim_crash_op").apply { mkdirs() }
            val rollbackFile = File(opDir, "rollback.flac").apply { writeBytes(origBytes) }
            targetFile.writeBytes(origBytes.copyOf(32)) // truncated/corrupted destination!
            val manifest = SafeAudioWritePipeline.WriteManifest(
                opId = "sim_crash_op",
                targetUri = fileUri,
                displayName = targetFile.name,
                operationType = SafeAudioWritePipeline.OperationType.METADATA,
                formatName = AudioContainerFormat.FLAC.name,
                originalSha256 = origSha,
                originalSizeBytes = origBytes.size.toLong(),
                rollbackPath = rollbackFile.absolutePath,
                expectedOutputSha256 = "0000000000000000000000000000000000000000000000000000000000000000",
                expectedOutputSizeBytes = origBytes.size.toLong() + 10L,
                state = SafeAudioWritePipeline.OperationState.COMMITTING,
                createdAtMs = System.currentTimeMillis(),
                updatedAtMs = System.currentTimeMillis()
            )
            File(opDir, "manifest.json").writeText(manifest.toJsonString(), Charsets.UTF_8)

            // Startup recovery restores original bytes bit-for-bit and cleans rollback ONLY after verification
            val report = pipeline.recoverInterruptedOperations()
            assertEquals(1, report.restoredCount)
            assertEquals(0, report.failedRetainedCount)
            assertArrayEquals("Original audio must be restored bit-for-bit", origBytes, targetFile.readBytes())
            assertTrue("Journal cleaned after verified recovery", pipeline.listPendingManifests().isEmpty())
        } finally {
            workDir.deleteRecursively()
        }
    }

    // =========================================================================
    // 3. Real Media3 ExoPlayer: Zero Double Attenuation + replaceMediaItem + Sleep Timer Expiry
    // =========================================================================

    @Test
    fun realExoPlayer_replayGainSingleAuthorityZeroDoubleAttenuationAndSleepTimerExpiry() {
        val ctx = appContext()
        val workDir = File(ctx.cacheDir, "p0_exo_${System.currentTimeMillis()}").apply { mkdirs() }
        val mainHandler = Handler(Looper.getMainLooper())
        val playerRef = AtomicReference<ExoPlayer?>(null)

        fun <T> onMainSync(block: () -> T): T {
            val latch = CountDownLatch(1)
            val resultRef = AtomicReference<Result<T>>()
            mainHandler.post {
                resultRef.set(runCatching(block))
                latch.countDown()
            }
            assertTrue("Main thread task timed out", latch.await(10, TimeUnit.SECONDS))
            return resultRef.get().getOrThrow()
        }

        try {
            val flacFile = File(workDir, "tone_4s.flac")
            runFfmpeg(ctx, listOf(
                "-f", "lavfi", "-i", "sine=frequency=440:duration=4.0",
                "-c:a", "flac",
                "-metadata", "TITLE=Live Exo Title",
                "-metadata", "ARTIST=Live Exo Artist",
                flacFile.absolutePath
            ))

            val processor = ReplayGainAudioProcessor()
            val coordinator = PlaybackGainCoordinator(processor)
            val sleepTimer = SleepTimerController(
                persistFile = null,
                gainCoordinator = coordinator,
                pauseSettleDelayMs = 150L
            )

            val player = onMainSync {
                val rf = object : DefaultRenderersFactory(ctx) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): AudioSink = DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(enableFloatOutput)
                        .setAudioProcessors(arrayOf(coordinator.processor))
                        .build()
                }
                ExoPlayer.Builder(ctx, rf)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                            .setUsage(C.USAGE_MEDIA)
                            .build(),
                        false
                    )
                    .build().also { p ->
                        p.addListener(object : Player.Listener {
                            override fun onIsPlayingChanged(isPlaying: Boolean) {
                                sleepTimer.onIsPlayingChanged(isPlaying)
                            }
                            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                                sleepTimer.onPlayWhenReadyChanged(playWhenReady)
                            }
                            override fun onPlaybackStateChanged(playbackState: Int) {
                                sleepTimer.onPlaybackStateChanged(playbackState)
                            }
                        })
                    }
            }
            playerRef.set(player)

            // Configure ReplayGain (-6 dB) BEFORE playback starts (reproduces cold-start AUD-01 race!)
            val expectedRg = ReplayGainCalculator.dbToLinear(-6.0f)
            onMainSync {
                coordinator.attachPlayer(player)
                sleepTimer.attachPlayer(player)
                coordinator.updateConfig(ReplayGainConfig(mode = ReplayGainMode.TRACK))
                coordinator.updateTrackReplayGain(
                    trackUri = Uri.fromFile(flacFile).toString(),
                    info = ReplayGainInfo(trackGainDb = -6.0f, trackPeak = 0.9f),
                    snapOnTrackSwitch = true
                )
            }

            // Before PCM configure: PLAYER_VOLUME_FALLBACK owns -6dB; processor MUST be 1.0f
            val preVol = onMainSync { player.volume }
            assertEquals(GainApplicationPath.PLAYER_VOLUME_FALLBACK, coordinator.state.value.applicationPath)
            assertEquals(expectedRg, preVol, 1e-3f)
            assertEquals(1.0f, processor.currentTargetLinearGain, 1e-4f)

            // Start real FLAC playback!
            val track = LibraryTrack(
                id = 999L,
                uri = Uri.fromFile(flacFile).toString(),
                displayName = flacFile.name,
                title = "Live Exo Title",
                artist = "Live Exo Artist",
                album = "Test Album",
                durationMs = 4000L,
                sizeBytes = flacFile.length(),
                dateAddedSec = 1000L,
                dateModifiedSec = 1000L,
                mimeType = "audio/flac"
            )
            onMainSync {
                player.setMediaItem(MediaItemMapper.toMediaItem(track))
                player.prepare()
                player.play()
            }

            // Wait for real ExoPlayer to start playing & configure PCM AudioProcessor
            val deadline = System.currentTimeMillis() + 8_000L
            while (System.currentTimeMillis() < deadline) {
                val playing = onMainSync { player.isPlaying }
                if (playing && processor.isProcessingPcm && coordinator.state.value.applicationPath == GainApplicationPath.DSP_PCM_PROCESSOR) {
                    break
                }
                Thread.sleep(50L)
            }

            assertTrue("Real ExoPlayer must engage ReplayGainAudioProcessor", processor.isProcessingPcm)
            assertEquals(GainApplicationPath.DSP_PCM_PROCESSOR, coordinator.state.value.applicationPath)

            // CRITICAL AUD-01 ASSERTION ON REAL HARDWARE:
            // player.volume MUST be strictly 1.0f while DSP processor carries -6.0 dB!
            val liveVol = onMainSync { player.volume }
            assertEquals("player.volume must be 1.0f when PCM DSP is active (zero double attenuation)", 1.0f, liveVol, 1e-4f)
            assertEquals(expectedRg, processor.currentTargetLinearGain, 1e-3f)

            // Wait briefly for DSP to process PCM buffers
            Thread.sleep(300L)
            assertTrue("DSP processor must have scaled live PCM buffers", processor.scaledBufferCount > 0L)

            // A8 ASSERTION ON REAL EXOPLAYER: replaceMediaItem updates metadata in-place without stopping playback!
            val posBefore = onMainSync { player.currentPosition }
            onMainSync {
                val updatedTrack = track.copy(title = "Edited Live Title", artist = "Edited Live Artist")
                player.replaceMediaItem(0, MediaItemMapper.toMediaItem(updatedTrack))
            }
            Thread.sleep(150L)
            val (titleAfter, playingAfter, posAfter) = onMainSync {
                Triple(
                    player.currentMediaItem?.mediaMetadata?.title?.toString(),
                    player.isPlaying,
                    player.currentPosition
                )
            }
            assertEquals("Edited Live Title", titleAfter)
            assertTrue("Playback must remain playing during in-place metadata refresh", playingAfter)
            assertTrue("Playback position must continue forward ($posBefore -> $posAfter)", posAfter >= posBefore)

            // B4 ASSERTION ON REAL EXOPLAYER: Sleep Timer expiry pauses cleanly, preserves queue, and settles fade!
            onMainSync {
                sleepTimer.startEndOfTrack(fadeOutSeconds = 1)
                sleepTimer.onMediaItemTransition(newMediaId = null, reason = Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            }
            // Wait for pause + 150ms AudioTrack drain settle
            val pauseDeadline = System.currentTimeMillis() + 3_000L
            while (System.currentTimeMillis() < pauseDeadline) {
                val playing = onMainSync { player.isPlaying }
                if (!playing && !sleepTimer.isAwaitingPauseFadeRestore) break
                Thread.sleep(50L)
            }
            val (stillPlaying, itemCountAfter) = onMainSync { player.isPlaying to player.mediaItemCount }
            assertFalse("Player must be paused after sleep timer expiry", stillPlaying)
            assertEquals("Queue must be preserved after sleep timer expiry", 1, itemCountAfter)
            assertEquals("Sleep fade must cleanly restore to 1.0f after pause settles", 1.0f, coordinator.state.value.sleepFadeFactor, 1e-4f)
            assertEquals("ReplayGain must remain intact after sleep fade restores", expectedRg, coordinator.state.value.effectiveLinearGain, 1e-3f)
        } finally {
            playerRef.get()?.let { p ->
                runCatching { onMainSync { p.release() } }
            }
            workDir.deleteRecursively()
        }
    }
}

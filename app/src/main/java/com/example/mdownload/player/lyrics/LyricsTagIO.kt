package com.example.mdownload.player.lyrics

import android.content.Context
import android.net.Uri
import android.util.Log
import com.example.mdownload.core.AudioTagFingerprint
import com.example.mdownload.core.metadata.AudioContainerPrimitives
import com.example.mdownload.core.metadata.AudioContainerPrimitives.FlacBlock
import com.example.mdownload.core.metadata.AudioContainerPrimitives.Id3v2ReadResult
import com.example.mdownload.core.metadata.AudioContainerPrimitives.RawId3Frame
import com.example.mdownload.core.metadata.SafeAudioWritePipeline
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale
import java.util.concurrent.TimeUnit

enum class AudioContainerFormat {
    MP3,
    FLAC,
    MP4_M4A,
    OGG_OPUS,
    UNSUPPORTED
}

data class EmbeddedLyricsResult(
    val lyrics: TrackLyrics? = null,
    val format: AudioContainerFormat = AudioContainerFormat.UNSUPPORTED
)

sealed interface EmbedWriteResult {
    data class Success(val format: AudioContainerFormat, val detail: String) : EmbedWriteResult
    data class RequiresWritePermission(val uris: List<Uri>, val message: String) : EmbedWriteResult
    data class Unsupported(val reason: String) : EmbedWriteResult
    data class Failed(val reason: String) : EmbedWriteResult
}

/**
 * Format-aware, rollback-protected audio tag reader & writer for lyrics.
 *
 * Shares low-level ID3v2 / FLAC / MP4 / Ogg container primitives with
 * [com.example.mdownload.core.metadata.MetadataTagIO] via [AudioContainerPrimitives] and uses the
 * shared crash-recoverable [SafeAudioWritePipeline] for all `content://` URI writes.
 */
object LyricsTagIO {

    private const val TAG = "LyricsTagIO"
    private const val FFMPEG_TIMEOUT_MS = 30_000L

    fun detectFormat(displayName: String = "", mimeType: String = "", file: File? = null): AudioContainerFormat {
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
            .ifBlank { file?.extension?.lowercase(Locale.US).orEmpty() }
        val mime = mimeType.lowercase(Locale.US)

        return when {
            ext == "mp3" || mime.contains("mpeg") -> AudioContainerFormat.MP3
            ext == "flac" || mime.contains("flac") -> AudioContainerFormat.FLAC
            ext in setOf("m4a", "mp4", "aac") || mime.contains("mp4") || mime.contains("m4a") ->
                AudioContainerFormat.MP4_M4A
            ext in setOf("ogg", "oga", "opus") || mime.contains("ogg") || mime.contains("opus") ->
                AudioContainerFormat.OGG_OPUS
            file != null && file.isFile -> sniffFormatFromHeader(file)
            else -> AudioContainerFormat.UNSUPPORTED
        }
    }

    fun supportsEmbedding(displayName: String, mimeType: String): Boolean {
        return detectFormat(displayName, mimeType) != AudioContainerFormat.UNSUPPORTED
    }

    fun readEmbeddedLyrics(
        context: Context,
        uriString: String,
        trackKey: String,
        displayName: String = "",
        mimeType: String = ""
    ): EmbeddedLyricsResult {
        if (uriString.isBlank()) return EmbeddedLyricsResult()
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return EmbeddedLyricsResult()

        if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
            val file = File(uri.path ?: uriString)
            if (!file.isFile) return EmbeddedLyricsResult()
            return readEmbeddedLyricsFromFile(file, trackKey, displayName, mimeType)
        }

        val tempDir = File(context.cacheDir, "lyrics_tag_read").apply { mkdirs() }
        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US).ifBlank { "tmp" }
        val tempFile = File(tempDir, "read_${System.nanoTime()}.$ext")
        return try {
            val copied = context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile).use { output ->
                    input.copyTo(output, bufferSize = 32 * 1024)
                }
                true
            } ?: false
            if (!copied || !tempFile.isFile || tempFile.length() == 0L) {
                EmbeddedLyricsResult()
            } else {
                readEmbeddedLyricsFromFile(tempFile, trackKey, displayName, mimeType)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed reading embedded lyrics for $uriString: ${e.message}")
            EmbeddedLyricsResult()
        } finally {
            tempFile.delete()
        }
    }

    fun readEmbeddedLyricsFromFile(
        file: File,
        trackKey: String,
        displayName: String = file.name,
        mimeType: String = ""
    ): EmbeddedLyricsResult {
        if (!file.isFile || file.length() < 12L) return EmbeddedLyricsResult()
        val format = detectFormat(displayName, mimeType, file)
        val lyrics = runCatching {
            when (format) {
                AudioContainerFormat.MP3 -> readMp3Lyrics(file, trackKey)
                AudioContainerFormat.FLAC -> readFlacLyrics(file, trackKey)
                AudioContainerFormat.MP4_M4A -> readMp4Lyrics(file, trackKey)
                AudioContainerFormat.OGG_OPUS -> readOggOrOpusLyrics(file, trackKey)
                AudioContainerFormat.UNSUPPORTED -> null
            }
        }.onFailure {
            Log.w(TAG, "Tag parse error for ${file.name}: ${it.message}")
        }.getOrNull()
        return EmbeddedLyricsResult(lyrics = lyrics, format = format)
    }

    /**
     * Safely embeds [lyrics] into a local `File` or Android `content://` URI (MediaStore / SAF),
     * using the shared [SafeAudioWritePipeline] with durable rollback, manifest journaling,
     * MediaStore write-permission reporting, and post-commit read-back verification.
     */
    fun embedLyricsSafely(
        context: Context,
        uriString: String,
        displayName: String,
        mimeType: String,
        lyrics: TrackLyrics
    ): EmbedWriteResult {
        if (!lyrics.hasContent) {
            return EmbedWriteResult.Failed("No lyrics content to embed.")
        }
        val format = detectFormat(displayName, mimeType)
        if (format == AudioContainerFormat.UNSUPPORTED) {
            return EmbedWriteResult.Unsupported("Audio format does not support embedded lyrics.")
        }
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return EmbedWriteResult.Failed("Invalid audio URI.")

        if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
            val file = File(uri.path ?: uriString)
            if (!file.isFile || !file.canWrite()) {
                return EmbedWriteResult.Failed("Audio file is not writable.")
            }
            return embedLyricsIntoLocalFile(context, file, format, lyrics)
        }

        val pipeline = SafeAudioWritePipeline.forContext(context)
        val outcome = runBlocking {
            pipeline.executeSafeWrite(
                targetUri = uriString,
                displayName = displayName,
                format = format,
                operationType = SafeAudioWritePipeline.OperationType.LYRICS,
                transformFile = { srcFile, dstFile ->
                    when (val writeRes = writeLyricsToFilePair(context, srcFile, dstFile, format, lyrics)) {
                        is EmbedWriteResult.Success -> SafeAudioWritePipeline.TransformDecision.Ready(writeRes)
                        is EmbedWriteResult.Unsupported ->
                            SafeAudioWritePipeline.TransformDecision.Abort(writeRes.reason, writeRes)
                        is EmbedWriteResult.Failed ->
                            SafeAudioWritePipeline.TransformDecision.Abort(writeRes.reason, writeRes)
                        is EmbedWriteResult.RequiresWritePermission ->
                            SafeAudioWritePipeline.TransformDecision.Abort(writeRes.message, writeRes)
                    }
                },
                postVerifyDestinationFile = { readBackFile ->
                    readEmbeddedLyricsFromFile(readBackFile, lyrics.trackKey, displayName, mimeType)
                        .lyrics?.hasContent == true
                }
            )
        }

        return when (outcome) {
            is SafeAudioWritePipeline.CommitOutcome.Committed -> outcome.payload
            is SafeAudioWritePipeline.CommitOutcome.PermissionRequired ->
                EmbedWriteResult.RequiresWritePermission(
                    uris = listOf(uri),
                    message = outcome.message
                )
            is SafeAudioWritePipeline.CommitOutcome.Aborted ->
                (outcome.payload as? EmbedWriteResult) ?: EmbedWriteResult.Failed(outcome.reason)
            is SafeAudioWritePipeline.CommitOutcome.FailedRecovered ->
                EmbedWriteResult.Failed(outcome.reason)
            is SafeAudioWritePipeline.CommitOutcome.FailedRollbackRetained ->
                EmbedWriteResult.Failed(outcome.reason)
        }
    }

    /**
     * Pure local-file entry point (directly usable from JVM unit tests for MP3 & FLAC).
     */
    fun embedLyricsIntoLocalFile(
        context: Context?,
        targetFile: File,
        format: AudioContainerFormat = detectFormat(targetFile.name, "", targetFile),
        lyrics: TrackLyrics
    ): EmbedWriteResult {
        if (!targetFile.isFile) return EmbedWriteResult.Failed("Audio file not found.")
        if (!lyrics.hasContent) return EmbedWriteResult.Failed("No lyrics content to embed.")
        if (format == AudioContainerFormat.UNSUPPORTED) {
            return EmbedWriteResult.Unsupported("Unsupported audio container.")
        }

        val parent = targetFile.parentFile ?: return EmbedWriteResult.Failed("Invalid file path.")
        val ext = targetFile.extension.takeIf { it.isNotBlank() } ?: when (format) {
            AudioContainerFormat.MP3 -> "mp3"
            AudioContainerFormat.FLAC -> "flac"
            AudioContainerFormat.MP4_M4A -> "m4a"
            AudioContainerFormat.OGG_OPUS -> "ogg"
            AudioContainerFormat.UNSUPPORTED -> "tmp"
        }
        val tempOut = File(parent, ".${targetFile.nameWithoutExtension}.lyrics_tmp_${System.nanoTime()}.$ext")
        return try {
            val result = writeLyricsToFilePair(context, targetFile, tempOut, format, lyrics)
            if (result !is EmbedWriteResult.Success) {
                tempOut.delete()
                return result
            }
            if (!tempOut.renameTo(targetFile)) {
                FileInputStream(tempOut).use { inp ->
                    FileOutputStream(targetFile).use { out ->
                        inp.copyTo(out, 32 * 1024)
                        out.flush()
                        runCatching { out.fd.sync() }
                    }
                }
                tempOut.delete()
            }
            result
        } catch (e: Exception) {
            tempOut.delete()
            EmbedWriteResult.Failed("Failed to replace audio file safely: ${e.message}")
        }
    }

    fun computeAudioPayloadSha256(file: File, format: AudioContainerFormat): String? =
        AudioContainerPrimitives.computeAudioPayloadSha256(file, format)

    private fun writeLyricsToFilePair(
        context: Context?,
        srcFile: File,
        dstFile: File,
        format: AudioContainerFormat,
        lyrics: TrackLyrics
    ): EmbedWriteResult {
        return when (format) {
            AudioContainerFormat.MP3 -> {
                val probe = AudioContainerPrimitives.readId3v2(srcFile)
                if (probe is Id3v2ReadResult.UnsupportedVersion) {
                    return EmbedWriteResult.Unsupported(
                        "ID3v2.${probe.majorVersion} tags are not supported for safe lyrics embedding; original file was left untouched."
                    )
                }
                val beforeHash = computeAudioPayloadSha256(srcFile, AudioContainerFormat.MP3)
                writeMp3Lyrics(srcFile, dstFile, lyrics)
                val readBack = readMp3Lyrics(dstFile, lyrics.trackKey)
                val afterHash = computeAudioPayloadSha256(dstFile, AudioContainerFormat.MP3)
                if (readBack == null || !readBack.hasContent) {
                    EmbedWriteResult.Failed("Verification failed: embedded MP3 lyrics could not be read back.")
                } else if (beforeHash != null && afterHash != beforeHash) {
                    EmbedWriteResult.Failed("Safety check failed: MP3 audio payload mismatch.")
                } else {
                    val tagSummary = if (lyrics.isSynced) "ID3v2 USLT + SYLT" else "ID3v2 USLT"
                    EmbedWriteResult.Success(format, "Embedded $tagSummary lyrics into MP3.")
                }
            }

            AudioContainerFormat.FLAC -> {
                val beforeHash = computeAudioPayloadSha256(srcFile, AudioContainerFormat.FLAC)
                writeFlacLyrics(srcFile, dstFile, lyrics)
                val readBack = readFlacLyrics(dstFile, lyrics.trackKey)
                val afterHash = computeAudioPayloadSha256(dstFile, AudioContainerFormat.FLAC)
                if (readBack == null || !readBack.hasContent) {
                    EmbedWriteResult.Failed("Verification failed: embedded FLAC Vorbis comment could not be read back.")
                } else if (beforeHash != null && afterHash != beforeHash) {
                    EmbedWriteResult.Failed("Safety check failed: FLAC audio payload mismatch.")
                } else {
                    EmbedWriteResult.Success(format, "Embedded Vorbis comment LYRICS into FLAC.")
                }
            }

            AudioContainerFormat.MP4_M4A, AudioContainerFormat.OGG_OPUS -> {
                if (context == null) {
                    return EmbedWriteResult.Unsupported("FFmpeg remux requires Android runtime context.")
                }
                val beforeHash = computeAudioPayloadSha256(srcFile, format)
                val err = remuxWithFfmpegLyrics(context, srcFile, dstFile, format, lyrics)
                if (err != null) {
                    return EmbedWriteResult.Failed(err)
                }
                val afterHash = computeAudioPayloadSha256(dstFile, format)
                if (beforeHash != null && afterHash != null && afterHash != beforeHash) {
                    return EmbedWriteResult.Failed("Safety check failed: ${format.name} encoded audio payload mismatch.")
                }
                val readBack = readEmbeddedLyricsFromFile(dstFile, lyrics.trackKey, dstFile.name).lyrics
                if (readBack == null || !readBack.hasContent) {
                    EmbedWriteResult.Failed("Verification failed: lyrics could not be read back from updated ${format.name} file.")
                } else {
                    val label = if (format == AudioContainerFormat.MP4_M4A) "MP4 ©lyr" else "Ogg/Opus LYRICS"
                    EmbedWriteResult.Success(format, "Embedded $label tag safely.")
                }
            }

            AudioContainerFormat.UNSUPPORTED ->
                EmbedWriteResult.Unsupported("Audio format does not support embedded lyrics.")
        }
    }

    // -------------------------------------------------------------------------
    // MP3 ID3v2.3 / ID3v2.4 (USLT + SYLT) built on shared AudioContainerPrimitives
    // -------------------------------------------------------------------------

    internal fun readMp3Lyrics(file: File, trackKey: String): TrackLyrics? {
        if (file.length() < 10L) return null
        val parsed = AudioContainerPrimitives.readId3v2(file)
        if (parsed !is Id3v2ReadResult.Supported) return null

        var syltLines: List<LyricLine>? = null
        var usltText: String? = null

        for (frame in parsed.frames) {
            when (frame.id) {
                "SYLT" -> {
                    val decoded = parseSyltFrame(frame.body)
                    if (decoded.isNotEmpty()) syltLines = decoded
                }
                "USLT" -> {
                    val decoded = parseUsltFrame(frame.body)
                    if (!decoded.isNullOrBlank()) usltText = decoded
                }
            }
        }

        if (!syltLines.isNullOrEmpty()) {
            val lrc = LyricsParser.serializeLrc(syltLines)
            val plain = syltLines.mapNotNull { it.text.takeIf(String::isNotBlank) }.joinToString("\n")
            return TrackLyrics(
                trackKey = trackKey,
                lines = syltLines,
                rawLrc = lrc,
                plainText = plain,
                isSynced = true,
                source = LyricsSource.EMBEDDED
            )
        }

        val rawUslt = usltText ?: return null
        return LyricsParser.toTrackLyrics(
            trackKey = trackKey,
            rawInput = rawUslt,
            source = LyricsSource.EMBEDDED
        )
    }

    internal fun writeMp3Lyrics(srcFile: File, dstFile: File, lyrics: TrackLyrics) {
        val readResult = AudioContainerPrimitives.readId3v2(srcFile)
        val (targetVersionMajor, existingFrames, payloadOffset) = when (readResult) {
            is Id3v2ReadResult.NoTag -> Triple(3, emptyList(), 0L)
            is Id3v2ReadResult.Supported -> Triple(readResult.majorVersion, readResult.frames, readResult.payloadOffset)
            is Id3v2ReadResult.UnsupportedVersion ->
                throw IllegalArgumentException("ID3v2.${readResult.majorVersion} tags are not supported for safe lyrics embedding.")
        }

        val keptFrames = existingFrames.filterNot { it.id == "USLT" || it.id == "SYLT" }
        val newFrames = ArrayList<RawId3Frame>(keptFrames.size + 2)
        newFrames.addAll(keptFrames)

        val usltContent = if (lyrics.isSynced) lyrics.toExportableLrc() else lyrics.displayPlainText
        newFrames.add(
            RawId3Frame(
                id = "USLT",
                flags = byteArrayOf(0, 0),
                body = buildUsltBody(usltContent, useUtf8 = targetVersionMajor == 4)
            )
        )

        if (lyrics.isSynced) {
            val syncedLines = lyrics.lines.filter { it.timestampMs != null }
            if (syncedLines.isNotEmpty()) {
                newFrames.add(
                    RawId3Frame(
                        id = "SYLT",
                        flags = byteArrayOf(0, 0),
                        body = buildSyltBody(syncedLines, lyrics.offsetMs, useUtf8 = targetVersionMajor == 4)
                    )
                )
            }
        }

        AudioContainerPrimitives.writeId3v2File(srcFile, dstFile, targetVersionMajor, newFrames, payloadOffset)
    }

    private fun parseUsltFrame(body: ByteArray): String? {
        if (body.size < 5) return null
        val encoding = body[0].toInt() and 0xFF
        val (descEnd, _) = AudioContainerPrimitives.readTerminatedId3String(body, 4, encoding)
        if (descEnd > body.size) return null
        return AudioContainerPrimitives.decodeId3Text(body, descEnd, body.size - descEnd, encoding)
            .trimEnd('\u0000')
            .trim()
            .takeIf { it.isNotBlank() }
    }

    private fun buildUsltBody(text: String, useUtf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (useUtf8) {
            out.write(3)
            out.write("und".toByteArray(Charsets.ISO_8859_1))
            out.write(0)
            out.write(text.toByteArray(Charsets.UTF_8))
        } else {
            out.write(1)
            out.write("und".toByteArray(Charsets.ISO_8859_1))
            out.write(byteArrayOf(0x00, 0x00))
            out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
            out.write(text.toByteArray(Charsets.UTF_16LE))
        }
        return out.toByteArray()
    }

    private fun parseSyltFrame(body: ByteArray): List<LyricLine> {
        if (body.size < 8) return emptyList()
        val encoding = body[0].toInt() and 0xFF
        val tsFormat = body[4].toInt() and 0xFF
        if (tsFormat != 2) return emptyList()
        var pos = AudioContainerPrimitives.readTerminatedId3String(body, 6, encoding).first
        val lines = mutableListOf<LyricLine>()
        while (pos < body.size) {
            val (nextPos, rawText) = AudioContainerPrimitives.readTerminatedId3String(body, pos, encoding)
            if (nextPos + 4 > body.size) break
            val tsMs = AudioContainerPrimitives.readBigEndianUInt(body, nextPos)
            val cleaned = rawText.trim('\r', '\n', '\u0000')
            lines.add(LyricLine(timestampMs = tsMs, text = cleaned))
            pos = nextPos + 4
        }
        return lines.sortedBy { it.timestampMs ?: 0L }
    }

    private fun buildSyltBody(lines: List<LyricLine>, offsetMs: Long, useUtf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        val encodingByte = if (useUtf8) 3 else 1
        out.write(encodingByte)
        out.write("und".toByteArray(Charsets.ISO_8859_1))
        out.write(2)
        out.write(1)
        if (useUtf8) {
            out.write(0)
        } else {
            out.write(byteArrayOf(0, 0))
        }
        for (line in lines) {
            val ts = ((line.timestampMs ?: 0L) - offsetMs).coerceAtLeast(0L)
            if (useUtf8) {
                out.write(line.text.toByteArray(Charsets.UTF_8))
                out.write(0)
            } else {
                out.write(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))
                out.write(line.text.toByteArray(Charsets.UTF_16LE))
                out.write(byteArrayOf(0, 0))
            }
            out.write(AudioContainerPrimitives.writeBigEndianInt(ts.toInt()))
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // FLAC VORBIS_COMMENT built on shared AudioContainerPrimitives
    // -------------------------------------------------------------------------

    internal fun readFlacLyrics(file: File, trackKey: String): TrackLyrics? {
        val (blocks, _) = AudioContainerPrimitives.readFlacBlocks(file) ?: return null
        val commentBlock = blocks.firstOrNull { it.type == 4 } ?: return null
        val (_, comments) = AudioContainerPrimitives.parseVorbisComment(commentBlock.payload, 0)
        val raw = extractLyricsFromVorbisComments(comments) ?: return null
        return LyricsParser.toTrackLyrics(
            trackKey = trackKey,
            rawInput = raw,
            source = LyricsSource.EMBEDDED
        )
    }

    internal fun writeFlacLyrics(srcFile: File, dstFile: File, lyrics: TrackLyrics) {
        val parsed = AudioContainerPrimitives.readFlacBlocks(srcFile)
            ?: throw IllegalArgumentException("Not a valid FLAC file.")
        val (blocks, audioOffset) = parsed

        val existingComment = blocks.firstOrNull { it.type == 4 }
        val (vendor, oldComments) = if (existingComment != null) {
            AudioContainerPrimitives.parseVorbisComment(existingComment.payload, 0)
        } else {
            "HiFiDeck" to emptyList()
        }

        val preservedComments = oldComments.filterNot { (k, _) ->
            k.equals("LYRICS", ignoreCase = true) || k.equals("UNSYNCEDLYRICS", ignoreCase = true)
        }.toMutableList()

        val textValue = if (lyrics.isSynced) lyrics.toExportableLrc() else lyrics.displayPlainText
        preservedComments.add("LYRICS" to textValue)

        val newCommentBlock = FlacBlock(
            type = 4,
            payload = AudioContainerPrimitives.buildVorbisComment(vendor.ifBlank { "HiFiDeck" }, preservedComments)
        )

        val updatedBlocks = mutableListOf<FlacBlock>()
        var replaced = false
        for (block in blocks) {
            if (block.type == 4 && !replaced) {
                updatedBlocks.add(newCommentBlock)
                replaced = true
            } else if (block.type != 4) {
                updatedBlocks.add(block)
            }
        }
        if (!replaced) {
            updatedBlocks.add(newCommentBlock)
        }

        AudioContainerPrimitives.writeFlacFile(srcFile, dstFile, updatedBlocks, audioOffset)
    }

    private fun extractLyricsFromVorbisComments(comments: List<Pair<String, String>>): String? {
        val priorityKeys = listOf("LYRICS", "SYNCEDLYRICS", "UNSYNCEDLYRICS")
        for (key in priorityKeys) {
            val match = comments.firstOrNull { it.first.equals(key, ignoreCase = true) && it.second.isNotBlank() }
            if (match != null) return match.second
        }
        return null
    }

    // -------------------------------------------------------------------------
    // M4A / MP4 & Ogg / Opus Readers
    // -------------------------------------------------------------------------

    internal fun readMp4Lyrics(file: File, trackKey: String): TrackLyrics? {
        val raw = AudioContainerPrimitives.readMp4Metadata(file)?.lyrics ?: return null
        return LyricsParser.toTrackLyrics(
            trackKey = trackKey,
            rawInput = raw,
            source = LyricsSource.EMBEDDED
        )
    }

    internal fun readOggOrOpusLyrics(file: File, trackKey: String): TrackLyrics? {
        val packet = AudioContainerPrimitives.extractOggCommentPacketFromFile(file) ?: return null
        val (_, comments) = when {
            packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags" ->
                AudioContainerPrimitives.parseVorbisComment(packet, startOffset = 8)
            packet.size > 7 && packet[0] == 3.toByte() &&
                String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis" ->
                AudioContainerPrimitives.parseVorbisComment(packet, startOffset = 7)
            else -> return null
        }

        val raw = extractLyricsFromVorbisComments(comments) ?: return null
        return LyricsParser.toTrackLyrics(
            trackKey = trackKey,
            rawInput = raw,
            source = LyricsSource.EMBEDDED
        )
    }

    // -------------------------------------------------------------------------
    // Lossless FFmpeg `-c copy` lyrics embedding for M4A/MP4 & Ogg/Opus
    // -------------------------------------------------------------------------

    private fun remuxWithFfmpegLyrics(
        context: Context,
        srcFile: File,
        dstFile: File,
        format: AudioContainerFormat,
        lyrics: TrackLyrics
    ): String? {
        val ffmpegBin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpegBin.isFile) return "Bundled FFmpeg binary is unavailable."

        val preMp4 = if (format == AudioContainerFormat.MP4_M4A) {
            AudioContainerPrimitives.readMp4Metadata(srcFile)
        } else null
        val existingYtId = AudioTagFingerprint.extractVideoIdFromFile(srcFile)

        val textValue = if (lyrics.isSynced) lyrics.toExportableLrc() else lyrics.displayPlainText
        val cmd = mutableListOf(
            ffmpegBin.absolutePath,
            "-y",
            "-v", "error",
            "-i", srcFile.absolutePath,
            "-map", "0",
            "-c", "copy",
            "-map_metadata", "0"
        )
        if (format == AudioContainerFormat.MP4_M4A) {
            cmd += listOf("-movflags", "+use_metadata_tags+faststart")
        }
        if (format == AudioContainerFormat.OGG_OPUS) {
            cmd += listOf("-map_metadata:s:a:0", "0:s:a:0")
        }
        cmd += listOf("-metadata", "lyrics=$textValue")
        if (format == AudioContainerFormat.OGG_OPUS) {
            cmd += listOf("-metadata:s:a:0", "lyrics=$textValue")
        }
        if (!existingYtId.isNullOrBlank()) {
            val ytVal = AudioTagFingerprint.markerValue(existingYtId)
            cmd += listOf("-metadata", "mdownload_yt_id=$ytVal")
            if (format == AudioContainerFormat.OGG_OPUS) {
                cmd += listOf("-metadata:s:a:0", "mdownload_yt_id=$ytVal")
            }
        }
        preMp4?.freeformTags?.forEach { (k, v) ->
            if (!k.equals("lyrics", ignoreCase = true) && v.isNotBlank()) {
                cmd += listOf("-metadata", "${k.trim()}=$v")
            }
        }
        cmd += dstFile.absolutePath

        return try {
            val packagesDir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")
            val ldPath = listOf(
                File(packagesDir, "ffmpeg/usr/lib").absolutePath,
                File(packagesDir, "python/usr/lib").absolutePath
            ).joinToString(":")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            val process = pb.start()
            val outBuf = StringBuilder()
            val reader = Thread {
                runCatching {
                    process.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { if (outBuf.length < 4096) outBuf.append(it).append('\n') }
                    }
                }
            }.apply { isDaemon = true; start() }

            val finished = try {
                process.waitFor(FFMPEG_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                Thread.currentThread().interrupt()
                return "Lyrics embed remux was cancelled."
            }

            if (!finished) {
                process.destroyForcibly()
                runCatching { reader.join(1000L) }
                return "FFmpeg lyrics embed timed out after ${FFMPEG_TIMEOUT_MS / 1000}s."
            }
            runCatching { reader.join(1000L) }
            val exitCode = process.exitValue()
            if (exitCode != 0) {
                Log.w(TAG, "FFmpeg lyrics remux failed ($exitCode): ${outBuf.toString().trim()}")
                return "Lossless tag embed failed for ${format.name} (exit $exitCode)."
            }
            if (!dstFile.isFile || dstFile.length() < 32L) {
                return "FFmpeg produced an empty output file."
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "FFmpeg lyrics remux error: ${e.message}")
            "FFmpeg lyrics remux error: ${e.message ?: "unknown error"}"
        }
    }

    private fun sniffFormatFromHeader(file: File): AudioContainerFormat {
        return runCatching {
            val hdr = ByteArray(12)
            RandomAccessFile(file, "r").use { raf ->
                if (raf.length() < 12L) return AudioContainerFormat.UNSUPPORTED
                raf.readFully(hdr)
            }
            val head4 = String(hdr, 0, 4, Charsets.ISO_8859_1)
            when {
                head4.startsWith("ID3") -> AudioContainerFormat.MP3
                head4 == "fLaC" -> AudioContainerFormat.FLAC
                head4 == "OggS" -> AudioContainerFormat.OGG_OPUS
                String(hdr, 4, 4, Charsets.ISO_8859_1) == "ftyp" -> AudioContainerFormat.MP4_M4A
                (hdr[0].toInt() and 0xFF) == 0xFF && (hdr[1].toInt() and 0xE0) == 0xE0 ->
                    AudioContainerFormat.MP3
                else -> AudioContainerFormat.UNSUPPORTED
            }
        }.getOrDefault(AudioContainerFormat.UNSUPPORTED)
    }
}

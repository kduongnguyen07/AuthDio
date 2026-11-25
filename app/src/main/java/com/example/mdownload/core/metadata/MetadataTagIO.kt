package com.example.mdownload.core.metadata

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import com.example.mdownload.core.AudioTagFingerprint
import com.example.mdownload.core.metadata.AudioContainerPrimitives.FlacBlock
import com.example.mdownload.core.metadata.AudioContainerPrimitives.Id3v2ReadResult
import com.example.mdownload.core.metadata.AudioContainerPrimitives.RawId3Frame
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.LyricsTagIO
import com.yausername.youtubedl_android.YoutubeDL
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Format-aware audio metadata reader & writer supporting MP3 (ID3v2.3/v2.4), FLAC (Vorbis comments
 * & validated PICTURE blocks), M4A/MP4, and Ogg/Opus (text tags).
 *
 * Preservation guarantees:
 * - MP3 (ID3v2.3 / ID3v2.4): Pure-Kotlin frame-level writer backed by [AudioContainerPrimitives].
 *   Preserves USLT, SYLT, TXXX (ReplayGain, fingerprints), RVA2, PRIV, and unknown frames, clears
 *   ID3v2.4 per-frame unsynchronization flags cleanly after de-unsynchronizing, explicitly rejects
 *   ID3v2.2 writes instead of silently wiping tags, and verifies untouched audio payload SHA-256.
 * - FLAC: Pure-Kotlin block-level writer preserving STREAMINFO, SEEKTABLE, CUESHEET, LYRICS,
 *   REPLAYGAIN_*, hidden `MDOWNLOAD_YT_ID`, and unknown Vorbis comments; validates PICTURE block
 *   MIME, size, dimensions, and 24-bit block limit; verifies untouched audio payload SHA-256.
 * - M4A/MP4: Lossless bundled `libffmpeg.so` `-c copy -map_metadata 0 -movflags +use_metadata_tags+faststart`
 *   with explicit ReplayGain/freeform/`MDOWNLOAD_YT_ID`/lyrics preservation, 30s timeout, genuine
 *   pure-Kotlin atom read-back verification, and `mdat` encoded-audio SHA-256 verification.
 * - Ogg/Opus: Lossless text-tag editing via `libffmpeg.so` with read-back & encoded packet SHA-256
 *   verification. Artwork editing is explicitly rejected with [MetadataWriteResult.Unsupported]
 *   rather than silently ignored.
 */
object MetadataTagIO {

    private const val TAG = "MetadataTagIO"
    private const val MAX_ARTWORK_DIMENSION = 1024
    private const val PASSTHROUGH_ARTWORK_MAX_BYTES = 1_500_000
    private const val FFMPEG_REMUX_TIMEOUT_MS = 30_000L

    private val MP3_EDITABLE_TEXT_FRAMES = setOf(
        "TIT2", "TPE1", "TPE2", "TALB", "TCON", "TDRC", "TYER", "TRCK", "TPOS", "TCOM"
    )

    private val FLAC_EDITABLE_KEYS = setOf(
        "TITLE", "ARTIST", "ALBUMARTIST", "ALBUM ARTIST", "ALBUM", "GENRE",
        "DATE", "YEAR", "TRACKNUMBER", "TRACKTOTAL", "TOTALTRACKS",
        "DISCNUMBER", "DISCTOTAL", "TOTALDISCS", "COMPOSER", "COMMENT"
    )

    fun readMetadataFromFile(
        file: File,
        displayName: String = file.name,
        mimeType: String = ""
    ): EditableTrackMetadata? {
        if (!file.isFile || file.length() < 16L) return null
        val format = LyricsTagIO.detectFormat(displayName, mimeType, file)
        return runCatching {
            when (format) {
                AudioContainerFormat.MP3 -> readMp3Metadata(file)
                AudioContainerFormat.FLAC -> readFlacMetadata(file)
                AudioContainerFormat.OGG_OPUS -> readOggMetadata(file)
                AudioContainerFormat.MP4_M4A -> readMp4Metadata(file)
                AudioContainerFormat.UNSUPPORTED -> null
            }
        }.getOrNull()
    }

    fun readMetadataFromUri(
        context: Context,
        uriString: String,
        displayName: String,
        mimeType: String,
        fallbackTitle: String,
        fallbackArtist: String,
        fallbackAlbum: String
    ): EditableTrackMetadata {
        val format = LyricsTagIO.detectFormat(displayName, mimeType)
        val uri = runCatching { Uri.parse(uriString) }.getOrNull()

        var fromFile: EditableTrackMetadata? = null
        if (uri != null) {
            if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
                val f = File(uri.path ?: uriString)
                if (f.isFile) fromFile = readMetadataFromFile(f, displayName, mimeType)
            } else if (format != AudioContainerFormat.UNSUPPORTED) {
                val tmp = File(context.cacheDir, "meta_read_${System.nanoTime()}.tmp")
                try {
                    val maxBytes = if (format == AudioContainerFormat.MP4_M4A) 32L * 1024L * 1024L else 10L * 1024L * 1024L
                    context.contentResolver.openInputStream(uri)?.use { inp ->
                        FileOutputStream(tmp).use { out ->
                            val buf = ByteArray(32 * 1024)
                            var remaining = maxBytes
                            while (remaining > 0L) {
                                val r = inp.read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                                if (r <= 0) break
                                out.write(buf, 0, r)
                                remaining -= r
                            }
                        }
                    }
                    if (tmp.isFile && tmp.length() > 16L) {
                        fromFile = readMetadataFromFile(tmp, displayName, mimeType)
                    }
                } catch (_: Exception) {
                } finally {
                    tmp.delete()
                }
            }
        }

        // Supplement via Android MediaMetadataRetriever if header copy missed trailing MP4 moov or fields
        var retrieverMeta: EditableTrackMetadata? = null
        if (uri != null && (fromFile == null || (format == AudioContainerFormat.MP4_M4A && fromFile.title.isBlank()))) {
            retrieverMeta = readViaRetriever(context, uri, format)
        }

        val merged = when {
            fromFile != null && retrieverMeta != null -> fromFile.copy(
                title = fromFile.title.ifBlank { retrieverMeta.title },
                artist = fromFile.artist.ifBlank { retrieverMeta.artist },
                albumArtist = fromFile.albumArtist.ifBlank { retrieverMeta.albumArtist },
                album = fromFile.album.ifBlank { retrieverMeta.album },
                genre = fromFile.genre.ifBlank { retrieverMeta.genre },
                year = fromFile.year.ifBlank { retrieverMeta.year },
                trackNumber = fromFile.trackNumber.ifBlank { retrieverMeta.trackNumber },
                trackTotal = fromFile.trackTotal.ifBlank { retrieverMeta.trackTotal },
                discNumber = fromFile.discNumber.ifBlank { retrieverMeta.discNumber },
                discTotal = fromFile.discTotal.ifBlank { retrieverMeta.discTotal },
                composer = fromFile.composer.ifBlank { retrieverMeta.composer },
                artworkBytes = fromFile.artworkBytes ?: retrieverMeta.artworkBytes
            )
            fromFile != null -> fromFile
            retrieverMeta != null -> retrieverMeta
            else -> EditableTrackMetadata(format = format)
        }

        return merged.copy(
            title = merged.title.ifBlank { fallbackTitle },
            artist = merged.artist.ifBlank { fallbackArtist.takeIf { it != "Unknown artist" }.orEmpty() },
            album = merged.album.ifBlank { fallbackAlbum },
            format = if (merged.format != AudioContainerFormat.UNSUPPORTED) merged.format else format
        )
    }

    /**
     * Pure local-file write & verify entry point (directly testable in JVM unit tests for MP3 & FLAC,
     * and on-device with bundled FFmpeg for MP4/M4A & Ogg/Opus).
     */
    fun writeMetadataToFilePair(
        context: Context?,
        srcFile: File,
        dstFile: File,
        format: AudioContainerFormat,
        metadata: EditableTrackMetadata
    ): MetadataWriteResult {
        if (format == AudioContainerFormat.UNSUPPORTED) {
            return MetadataWriteResult.Unsupported("Unsupported audio format for tag writing.")
        }

        // Fix A6: Never ignore Ogg/Opus artwork edits and return Success.
        if (format == AudioContainerFormat.OGG_OPUS && (metadata.artworkChanged || metadata.artworkRemoved)) {
            return MetadataWriteResult.Unsupported(
                "Artwork editing is not supported for Ogg/Opus files. Text metadata can still be edited safely."
            )
        }

        // Fix A4: Explicitly reject ID3v2.2 writes instead of silently discarding existing v2.2 tags.
        if (format == AudioContainerFormat.MP3) {
            val probe = AudioContainerPrimitives.readId3v2(srcFile)
            if (probe is Id3v2ReadResult.UnsupportedVersion) {
                return MetadataWriteResult.Unsupported(
                    "ID3v2.${probe.majorVersion} tags are not supported for safe editing; original file was left untouched."
                )
            }
        }

        val preHash = computeAudioPayloadSha256(srcFile, format)

        try {
            when (format) {
                AudioContainerFormat.MP3 -> writeMp3Metadata(srcFile, dstFile, metadata)
                AudioContainerFormat.FLAC -> writeFlacMetadata(srcFile, dstFile, metadata)
                AudioContainerFormat.MP4_M4A,
                AudioContainerFormat.OGG_OPUS -> {
                    val ctx = context ?: return MetadataWriteResult.Unsupported(
                        "FFmpeg context is required to write ${format.name} tags."
                    )
                    val err = remuxWithFfmpegMetadata(ctx, srcFile, dstFile, format, metadata)
                    if (err != null) {
                        dstFile.delete()
                        return MetadataWriteResult.Failed(err)
                    }
                }
                AudioContainerFormat.UNSUPPORTED -> {
                    return MetadataWriteResult.Unsupported("Unsupported format.")
                }
            }
        } catch (e: IllegalArgumentException) {
            dstFile.delete()
            return MetadataWriteResult.Failed(e.message ?: "Invalid metadata or artwork payload.")
        } catch (e: Exception) {
            dstFile.delete()
            return MetadataWriteResult.Failed("Tag write failed: ${e.message ?: "I/O error"}")
        }

        if (!dstFile.isFile || dstFile.length() < 32L) {
            dstFile.delete()
            return MetadataWriteResult.Failed("Generated file was empty or truncated.")
        }

        // Verify untouched encoded audio payload integrity across MP3, FLAC, MP4 (when no video-track artwork mutation), and Ogg/Opus
        val checkPayloadHash = preHash != null &&
            !(format == AudioContainerFormat.MP4_M4A && (metadata.artworkChanged || metadata.artworkRemoved))
        if (checkPayloadHash) {
            val postHash = computeAudioPayloadSha256(dstFile, format)
            if (postHash == null || postHash != preHash) {
                dstFile.delete()
                return MetadataWriteResult.Failed(
                    "Audio payload integrity check failed; original file left untouched."
                )
            }
        }

        // Genuine read-back verification across all formats (including MP4 & Ogg)
        val readBack = readMetadataFromFile(dstFile, dstFile.name, "")
        if (readBack == null) {
            dstFile.delete()
            return MetadataWriteResult.Failed("Verification failed: rewritten tags could not be read back.")
        }
        val expectedTitle = metadata.title.trim()
        if (expectedTitle.isNotEmpty() && readBack.title.trim() != expectedTitle) {
            dstFile.delete()
            return MetadataWriteResult.Failed(
                "Verification failed: read-back title \"${readBack.title}\" did not match \"$expectedTitle\"."
            )
        }
        val expectedComment = metadata.comment.trim()
        if (expectedComment.isEmpty() && readBack.comment.trim().isNotEmpty()) {
            dstFile.delete()
            return MetadataWriteResult.Failed("Verification failed: cleared comment was still present after rewrite.")
        }

        val label = when (format) {
            AudioContainerFormat.MP3 -> "ID3v2"
            AudioContainerFormat.FLAC -> "FLAC Vorbis Comment"
            AudioContainerFormat.MP4_M4A -> "MP4 / M4A"
            AudioContainerFormat.OGG_OPUS -> "Ogg / Opus"
            AudioContainerFormat.UNSUPPORTED -> "Unknown"
        }
        return MetadataWriteResult.Success(
            format = format,
            verifiedReadBack = readBack,
            detail = "Saved $label tags safely."
        )
    }

    /**
     * Prepares user-picked artwork bytes for embedding:
     * - Preserves original JPEG/PNG bytes without recompression if already <= 1024px & <= 1.5MB and crop is disabled.
     * - Otherwise downscales safely (and optionally center-crops to 1:1) to max 1024px JPEG.
     */
    fun prepareArtworkForEmbedding(
        context: Context,
        imageUri: Uri,
        squareCrop: Boolean = false
    ): Pair<ByteArray, String>? {
        val raw = runCatching {
            context.contentResolver.openInputStream(imageUri)?.use { it.readBytes() }
        }.getOrNull() ?: return null
        if (raw.isEmpty() || raw.size > 24 * 1024 * 1024) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return null

        val mime = bounds.outMimeType.orEmpty()
        val isCleanFormat = mime == "image/jpeg" || mime == "image/png"
        val needsCrop = squareCrop && w != h
        if (!needsCrop && isCleanFormat && w <= MAX_ARTWORK_DIMENSION && h <= MAX_ARTWORK_DIMENSION &&
            raw.size <= PASSTHROUGH_ARTWORK_MAX_BYTES
        ) {
            return raw to mime
        }

        var inSample = 1
        while ((w / inSample) > MAX_ARTWORK_DIMENSION * 2 || (h / inSample) > MAX_ARTWORK_DIMENSION * 2) {
            inSample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            raw,
            0,
            raw.size,
            BitmapFactory.Options().apply { inSampleSize = inSample }
        ) ?: return null

        var working = decoded
        if (squareCrop && working.width != working.height) {
            val side = minOf(working.width, working.height)
            val x = (working.width - side) / 2
            val y = (working.height - side) / 2
            val cropped = Bitmap.createBitmap(working, x, y, side, side)
            if (cropped !== working) working.recycle()
            working = cropped
        }

        val maxSide = maxOf(working.width, working.height)
        if (maxSide > MAX_ARTWORK_DIMENSION) {
            val scale = MAX_ARTWORK_DIMENSION.toFloat() / maxSide.toFloat()
            val nw = (working.width * scale).toInt().coerceAtLeast(1)
            val nh = (working.height * scale).toInt().coerceAtLeast(1)
            val scaled = Bitmap.createScaledBitmap(working, nw, nh, true)
            if (scaled !== working) working.recycle()
            working = scaled
        }

        val out = ByteArrayOutputStream()
        working.compress(Bitmap.CompressFormat.JPEG, 90, out)
        working.recycle()
        val resultBytes = out.toByteArray()
        return if (resultBytes.isNotEmpty() && resultBytes.size <= MetadataValidator.MAX_EMBEDDED_ARTWORK_BYTES) {
            resultBytes to "image/jpeg"
        } else {
            null
        }
    }

    fun computeAudioPayloadSha256(file: File, format: AudioContainerFormat): String? =
        AudioContainerPrimitives.computeAudioPayloadSha256(file, format)

    // -------------------------------------------------------------------------
    // MP3 ID3v2.3 / ID3v2.4 Reader & Frame-Preserving Writer
    // -------------------------------------------------------------------------

    private fun readMp3Metadata(file: File): EditableTrackMetadata {
        val parsed = AudioContainerPrimitives.readId3v2(file)
        if (parsed !is Id3v2ReadResult.Supported) {
            return EditableTrackMetadata(format = AudioContainerFormat.MP3)
        }

        var title = ""
        var artist = ""
        var albumArtist = ""
        var album = ""
        var genre = ""
        var year = ""
        var trackNum = ""
        var trackTot = ""
        var discNum = ""
        var discTot = ""
        var composer = ""
        var comment = ""
        var artworkBytes: ByteArray? = null
        var artworkMime = "image/jpeg"

        for (frame in parsed.frames) {
            when (frame.id) {
                "TIT2" -> title = decodeTextFrame(frame.body)
                "TPE1" -> artist = decodeTextFrame(frame.body)
                "TPE2" -> albumArtist = decodeTextFrame(frame.body)
                "TALB" -> album = decodeTextFrame(frame.body)
                "TCON" -> genre = decodeTextFrame(frame.body)
                "TDRC" -> if (year.isBlank()) year = decodeTextFrame(frame.body)
                "TYER" -> if (year.isBlank()) year = decodeTextFrame(frame.body)
                "TRCK" -> {
                    val pair = AudioContainerPrimitives.splitSlashPair(decodeTextFrame(frame.body))
                    trackNum = pair.first
                    trackTot = pair.second
                }
                "TPOS" -> {
                    val pair = AudioContainerPrimitives.splitSlashPair(decodeTextFrame(frame.body))
                    discNum = pair.first
                    discTot = pair.second
                }
                "TCOM" -> composer = decodeTextFrame(frame.body)
                "COMM" -> {
                    val c = parseCommFrame(frame.body)
                    if (!c.isNullOrBlank() && !c.contains(AudioTagFingerprint.TAG_PREFIX) && comment.isBlank()) {
                        comment = c
                    }
                }
                "APIC" -> if (artworkBytes == null) {
                    parseApicFrame(frame.body)?.let { (mime, bytes) ->
                        artworkMime = mime
                        artworkBytes = bytes
                    }
                }
            }
        }

        return EditableTrackMetadata(
            title = title,
            artist = artist,
            albumArtist = albumArtist,
            album = album,
            genre = genre,
            year = year,
            trackNumber = trackNum,
            trackTotal = trackTot,
            discNumber = discNum,
            discTotal = discTot,
            composer = composer,
            comment = comment,
            artworkBytes = artworkBytes,
            artworkMimeType = artworkMime,
            format = AudioContainerFormat.MP3
        )
    }

    private fun writeMp3Metadata(srcFile: File, dstFile: File, meta: EditableTrackMetadata) {
        val readResult = AudioContainerPrimitives.readId3v2(srcFile)
        val (major, existingFrames, payloadOffset) = when (readResult) {
            is Id3v2ReadResult.NoTag -> Triple(3, emptyList(), 0L)
            is Id3v2ReadResult.Supported -> Triple(readResult.majorVersion, readResult.frames, readResult.payloadOffset)
            is Id3v2ReadResult.UnsupportedVersion ->
                throw IllegalArgumentException("ID3v2.${readResult.majorVersion} tags are not supported for safe editing.")
        }
        val useUtf8 = major == 4

        val kept = mutableListOf<RawId3Frame>()
        existingFrames.forEach { frame ->
            val drop = when {
                frame.id in MP3_EDITABLE_TEXT_FRAMES -> true
                frame.id == "COMM" -> {
                    val text = parseCommFrame(frame.body).orEmpty()
                    !text.contains(AudioTagFingerprint.TAG_PREFIX)
                }
                frame.id == "APIC" -> meta.artworkChanged || meta.artworkRemoved
                else -> false // Preserve USLT, SYLT, TXXX (ReplayGain, fingerprints), RVA2, PRIV, unknown frames
            }
            if (!drop) kept.add(frame)
        }

        val newFrames = ArrayList<RawId3Frame>(kept.size + 14)
        newFrames.addAll(kept)

        fun addText(id: String, value: String) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) {
                newFrames.add(RawId3Frame(id, byteArrayOf(0, 0), buildTextFrameBody(trimmed, useUtf8)))
            }
        }

        addText("TIT2", meta.title)
        addText("TPE1", meta.artist)
        addText("TPE2", meta.albumArtist)
        addText("TALB", meta.album)
        addText("TCON", meta.genre)
        if (meta.year.trim().isNotEmpty()) {
            val yearFrameId = if (major == 4) "TDRC" else "TYER"
            val yearVal = if (major == 4) meta.year.trim() else meta.year.trim().take(4)
            addText(yearFrameId, yearVal)
        }
        AudioContainerPrimitives.formatSlashPair(meta.trackNumber, meta.trackTotal)?.let { addText("TRCK", it) }
        AudioContainerPrimitives.formatSlashPair(meta.discNumber, meta.discTotal)?.let { addText("TPOS", it) }
        addText("TCOM", meta.composer)
        if (meta.comment.trim().isNotEmpty()) {
            newFrames.add(RawId3Frame("COMM", byteArrayOf(0, 0), buildCommFrameBody(meta.comment.trim(), useUtf8)))
        }
        if (meta.artworkChanged && !meta.artworkRemoved && meta.artworkBytes != null && meta.artworkBytes.isNotEmpty()) {
            newFrames.add(
                RawId3Frame(
                    "APIC",
                    byteArrayOf(0, 0),
                    buildApicFrameBody(meta.artworkMimeType.ifBlank { "image/jpeg" }, meta.artworkBytes)
                )
            )
        }

        AudioContainerPrimitives.writeId3v2File(srcFile, dstFile, major, newFrames, payloadOffset)
    }

    // -------------------------------------------------------------------------
    // FLAC Block Reader & Tag/Picture-Preserving Writer
    // -------------------------------------------------------------------------

    private fun readFlacMetadata(file: File): EditableTrackMetadata {
        val (blocks, _) = AudioContainerPrimitives.readFlacBlocks(file)
            ?: return EditableTrackMetadata(format = AudioContainerFormat.FLAC)
        val commentBlock = blocks.firstOrNull { it.type == 4 }
        val comments = if (commentBlock != null) {
            AudioContainerPrimitives.parseVorbisComment(commentBlock.payload, 0).second
        } else {
            emptyList()
        }

        val picBlock = blocks.firstOrNull { it.type == 6 }
        val picture = picBlock?.let { AudioContainerPrimitives.parseFlacPictureBlock(it.payload) }

        return commentsToEditable(comments, AudioContainerFormat.FLAC).copy(
            artworkBytes = picture?.second,
            artworkMimeType = picture?.first ?: "image/jpeg"
        )
    }

    private fun writeFlacMetadata(srcFile: File, dstFile: File, meta: EditableTrackMetadata) {
        val (blocks, audioOffset) = AudioContainerPrimitives.readFlacBlocks(srcFile)
            ?: throw IllegalArgumentException("Not a valid FLAC file.")

        val existingComment = blocks.firstOrNull { it.type == 4 }
        val (vendor, oldComments) = if (existingComment != null) {
            AudioContainerPrimitives.parseVorbisComment(existingComment.payload, 0)
        } else {
            "HiFiDeck" to emptyList()
        }

        val keptComments = oldComments.filterNot { (k, v) ->
            val upper = k.trim().uppercase(Locale.US)
            if (upper == "COMMENT" && v.contains(AudioTagFingerprint.TAG_PREFIX)) {
                false
            } else {
                upper in FLAC_EDITABLE_KEYS
            }
        }.toMutableList()

        fun put(key: String, value: String) {
            val trimmed = value.trim()
            if (trimmed.isNotEmpty()) keptComments.add(key to trimmed)
        }

        put("TITLE", meta.title)
        put("ARTIST", meta.artist)
        put("ALBUMARTIST", meta.albumArtist)
        put("ALBUM", meta.album)
        put("GENRE", meta.genre)
        put("DATE", meta.year)
        put("TRACKNUMBER", meta.trackNumber)
        put("TRACKTOTAL", meta.trackTotal)
        put("DISCNUMBER", meta.discNumber)
        put("DISCTOTAL", meta.discTotal)
        put("COMPOSER", meta.composer)
        put("COMMENT", meta.comment)

        val newCommentBlock = FlacBlock(
            type = 4,
            payload = AudioContainerPrimitives.buildVorbisComment(vendor.ifBlank { "HiFiDeck" }, keptComments)
        )

        val updatedBlocks = mutableListOf<FlacBlock>()
        var replacedComment = false
        for (block in blocks) {
            when {
                block.type == 4 && !replacedComment -> {
                    updatedBlocks.add(newCommentBlock)
                    replacedComment = true
                }
                block.type == 4 -> Unit
                block.type == 6 && (meta.artworkChanged || meta.artworkRemoved) -> Unit
                else -> updatedBlocks.add(block)
            }
        }
        if (!replacedComment) updatedBlocks.add(newCommentBlock)

        // Fix A7: Validate artwork size, MIME, magic header, dimensions, and 24-bit block limit before commit
        if (meta.artworkChanged && !meta.artworkRemoved && meta.artworkBytes != null && meta.artworkBytes.isNotEmpty()) {
            val picPayload = AudioContainerPrimitives.validateAndBuildFlacPictureBlock(
                rawMimeType = meta.artworkMimeType.ifBlank { "image/jpeg" },
                imageBytes = meta.artworkBytes
            )
            updatedBlocks.add(FlacBlock(type = 6, payload = picPayload))
        }

        AudioContainerPrimitives.writeFlacFile(srcFile, dstFile, updatedBlocks, audioOffset)
    }

    // -------------------------------------------------------------------------
    // Ogg/Opus & MP4 Readers + Safe Lossless FFmpeg `-c copy` Writer
    // -------------------------------------------------------------------------

    private fun readOggMetadata(file: File): EditableTrackMetadata {
        val packet = AudioContainerPrimitives.extractOggCommentPacketFromFile(file)
            ?: return EditableTrackMetadata(format = AudioContainerFormat.OGG_OPUS)
        val comments = when {
            packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags" ->
                AudioContainerPrimitives.parseVorbisComment(packet, 8).second
            packet.size > 7 && packet[0] == 3.toByte() && String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis" ->
                AudioContainerPrimitives.parseVorbisComment(packet, 7).second
            else -> emptyList()
        }
        return commentsToEditable(comments, AudioContainerFormat.OGG_OPUS)
    }

    private fun readMp4Metadata(file: File): EditableTrackMetadata? {
        return AudioContainerPrimitives.readMp4Metadata(file)?.editable
    }

    private fun readViaRetriever(context: Context, uri: Uri, format: AudioContainerFormat): EditableTrackMetadata? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val trackPair = AudioContainerPrimitives.splitSlashPair(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER).orEmpty()
            )
            val discPair = AudioContainerPrimitives.splitSlashPair(
                mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DISC_NUMBER).orEmpty()
            )
            val pic = mmr.embeddedPicture
            EditableTrackMetadata(
                title = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                artist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST).orEmpty(),
                albumArtist = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(),
                album = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                genre = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE).orEmpty(),
                year = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR).orEmpty()
                    .ifBlank { mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE).orEmpty().take(10) },
                trackNumber = trackPair.first,
                trackTotal = trackPair.second,
                discNumber = discPair.first,
                discTotal = discPair.second,
                composer = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_COMPOSER).orEmpty(),
                artworkBytes = pic?.takeIf { it.isNotEmpty() },
                format = format
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    /**
     * Lossless FFmpeg `-c copy` metadata remuxer for MP4/M4A and Ogg/Opus.
     * Returns `null` on success, or a descriptive error message on failure/timeout.
     */
    private fun remuxWithFfmpegMetadata(
        context: Context,
        srcFile: File,
        dstFile: File,
        format: AudioContainerFormat,
        meta: EditableTrackMetadata
    ): String? {
        val ffmpegBin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpegBin.isFile) return "Bundled FFmpeg binary is unavailable."
        val packagesDir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")
        val ffmpegLibDir = File(packagesDir, "ffmpeg/usr/lib")
        if (!ffmpegLibDir.isDirectory) return "FFmpeg runtime libraries are not initialized yet."

        // Capture existing custom/freeform/lyrics/fingerprint metadata before remuxing so nothing is lost
        val preMp4 = if (format == AudioContainerFormat.MP4_M4A) {
            AudioContainerPrimitives.readMp4Metadata(srcFile)
        } else null
        val existingYtId = AudioTagFingerprint.extractVideoIdFromFile(srcFile)

        var tempCoverFile: File? = null
        return try {
            val cmd = mutableListOf(
                ffmpegBin.absolutePath,
                "-y",
                "-v", "error",
                "-i", srcFile.absolutePath
            )

            val embedArt = format == AudioContainerFormat.MP4_M4A &&
                meta.artworkChanged && !meta.artworkRemoved &&
                meta.artworkBytes != null && meta.artworkBytes.isNotEmpty()

            if (embedArt) {
                val ext = if (meta.artworkMimeType.contains("png", true)) "png" else "jpg"
                tempCoverFile = File(context.cacheDir, "meta_cover_${System.nanoTime()}.$ext").apply {
                    writeBytes(meta.artworkBytes!!)
                }
                cmd += listOf("-i", tempCoverFile.absolutePath)
                cmd += listOf(
                    "-map", "0:a",
                    "-map", "1:v",
                    "-c:a", "copy",
                    "-c:v", "copy",
                    "-disposition:v:0", "attached_pic"
                )
            } else if (meta.artworkRemoved && format == AudioContainerFormat.MP4_M4A) {
                cmd += listOf("-map", "0:a", "-c:a", "copy")
            } else {
                cmd += listOf("-map", "0", "-c", "copy")
            }

            cmd += listOf("-map_metadata", "0")
            if (format == AudioContainerFormat.OGG_OPUS) {
                // In Ogg/Opus containers, Vorbis/Opus comment headers live on stream s:a:0;
                // preserve existing stream tags and override edited fields on s:a:0 as well.
                cmd += listOf("-map_metadata:s:a:0", "0:s:a:0")
            }
            if (format == AudioContainerFormat.MP4_M4A) {
                // Critical fix A5: preserve non-standard/freeform custom tags (ReplayGain, MDOWNLOAD_YT_ID, etc.)
                cmd += listOf("-movflags", "+use_metadata_tags+faststart")
            }

            fun putTag(k: String, v: String) {
                val trimmed = v.trim()
                cmd += listOf("-metadata", "$k=$trimmed")
                if (format == AudioContainerFormat.OGG_OPUS) {
                    cmd += listOf("-metadata:s:a:0", "$k=$trimmed")
                }
            }

            putTag("title", meta.title)
            putTag("artist", meta.artist)
            putTag("album_artist", meta.albumArtist)
            putTag("album", meta.album)
            putTag("genre", meta.genre)
            putTag("date", meta.year)
            putTag("track", AudioContainerPrimitives.formatSlashPair(meta.trackNumber, meta.trackTotal).orEmpty())
            putTag("disc", AudioContainerPrimitives.formatSlashPair(meta.discNumber, meta.discTotal).orEmpty())
            putTag("composer", meta.composer)
            // Always emit comment explicitly (empty string clears an existing comment!)
            putTag("comment", meta.comment)

            // Preserve existing YouTube duplicate-detection fingerprint in a dedicated custom tag
            // so editing or clearing `comment` never destroys MDOWNLOAD_YT_ID.
            if (!existingYtId.isNullOrBlank()) {
                putTag("mdownload_yt_id", AudioTagFingerprint.markerValue(existingYtId))
            }

            // Explicitly re-emit preserved MP4 lyrics and ReplayGain/custom freeform tags
            if (preMp4 != null) {
                preMp4.lyrics?.takeIf { it.isNotBlank() }?.let { lyr ->
                    putTag("lyrics", lyr)
                }
                val standardLower = setOf(
                    "title", "artist", "album_artist", "albumartist", "album", "genre",
                    "date", "year", "track", "disc", "composer", "comment", "lyrics",
                    "mdownload_yt_id"
                )
                for ((k, v) in preMp4.freeformTags) {
                    val lower = k.trim().lowercase(Locale.US)
                    if (lower !in standardLower && v.isNotBlank()) {
                        putTag(k.trim(), v)
                    }
                }
            }

            cmd += dstFile.absolutePath

            val ldPath = listOf(
                ffmpegLibDir.absolutePath,
                File(packagesDir, "python/usr/lib").absolutePath
            ).joinToString(":")
            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            val proc = pb.start()

            // Drain merged stdout/stderr on a background thread so waitFor(timeout) cannot deadlock on full pipe
            val outputBuf = StringBuilder()
            val readerThread = Thread {
                runCatching {
                    proc.inputStream.bufferedReader().useLines { lines ->
                        lines.forEach { line ->
                            if (outputBuf.length < 4096) {
                                outputBuf.append(line).append('\n')
                            }
                        }
                    }
                }
            }.apply { isDaemon = true; start() }

            val finished = try {
                proc.waitFor(FFMPEG_REMUX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            } catch (ie: InterruptedException) {
                proc.destroyForcibly()
                Thread.currentThread().interrupt()
                return "Metadata remux was cancelled."
            }

            if (!finished) {
                proc.destroyForcibly()
                runCatching { readerThread.join(1000L) }
                return "FFmpeg metadata remux timed out after ${FFMPEG_REMUX_TIMEOUT_MS / 1000}s."
            }

            runCatching { readerThread.join(1000L) }
            val exit = proc.exitValue()
            if (exit != 0) {
                val msg = outputBuf.toString().trim()
                Log.w(TAG, "FFmpeg metadata remux failed ($exit): $msg")
                return "FFmpeg remux failed (exit $exit)${if (msg.isNotBlank()) ": $msg" else "."}"
            }
            if (!dstFile.isFile || dstFile.length() < 32L) {
                return "FFmpeg produced an empty output file."
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "FFmpeg metadata remux error: ${e.message}")
            "FFmpeg remux error: ${e.message ?: "unknown error"}"
        } finally {
            tempCoverFile?.delete()
        }
    }

    // -------------------------------------------------------------------------
    // Format-Specific Frame Builders / Decoders
    // -------------------------------------------------------------------------

    private fun commentsToEditable(comments: List<Pair<String, String>>, format: AudioContainerFormat): EditableTrackMetadata {
        val map = mutableMapOf<String, String>()
        for ((k, v) in comments) {
            val upper = k.trim().uppercase(Locale.US)
            if (upper == "COMMENT" && v.contains(AudioTagFingerprint.TAG_PREFIX)) continue
            if (!map.containsKey(upper) && v.isNotBlank()) map[upper] = v
        }
        val trackPair = AudioContainerPrimitives.splitSlashPair(map["TRACKNUMBER"].orEmpty())
        val discPair = AudioContainerPrimitives.splitSlashPair(map["DISCNUMBER"].orEmpty())
        return EditableTrackMetadata(
            title = map["TITLE"].orEmpty(),
            artist = map["ARTIST"].orEmpty(),
            albumArtist = map["ALBUMARTIST"] ?: map["ALBUM ARTIST"].orEmpty(),
            album = map["ALBUM"].orEmpty(),
            genre = map["GENRE"].orEmpty(),
            year = map["DATE"] ?: map["YEAR"].orEmpty(),
            trackNumber = trackPair.first,
            trackTotal = map["TRACKTOTAL"] ?: map["TOTALTRACKS"] ?: trackPair.second,
            discNumber = discPair.first,
            discTotal = map["DISCTOTAL"] ?: map["TOTALDISCS"] ?: discPair.second,
            composer = map["COMPOSER"].orEmpty(),
            comment = map["COMMENT"].orEmpty(),
            format = format
        )
    }

    private fun decodeTextFrame(body: ByteArray): String {
        if (body.size < 2) return ""
        val enc = body[0].toInt() and 0xFF
        return AudioContainerPrimitives.decodeId3Text(body, 1, body.size - 1, enc).trimEnd('\u0000').trim()
    }

    private fun buildTextFrameBody(value: String, useUtf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (useUtf8) {
            out.write(3)
            out.write(value.toByteArray(Charsets.UTF_8))
        } else {
            out.write(1)
            out.write(0xFF)
            out.write(0xFE)
            out.write(value.toByteArray(Charsets.UTF_16LE))
        }
        return out.toByteArray()
    }

    private fun parseCommFrame(body: ByteArray): String? {
        if (body.size < 5) return null
        val enc = body[0].toInt() and 0xFF
        val afterDesc = AudioContainerPrimitives.skipTerminatedId3String(body, 4, enc)
        if (afterDesc >= body.size) return null
        return AudioContainerPrimitives.decodeId3Text(body, afterDesc, body.size - afterDesc, enc)
            .trimEnd('\u0000')
            .trim()
    }

    private fun buildCommFrameBody(value: String, useUtf8: Boolean): ByteArray {
        val out = ByteArrayOutputStream()
        if (useUtf8) {
            out.write(3)
            out.write("eng".toByteArray(Charsets.ISO_8859_1))
            out.write(0)
            out.write(value.toByteArray(Charsets.UTF_8))
        } else {
            out.write(1)
            out.write("eng".toByteArray(Charsets.ISO_8859_1))
            out.write(0xFF); out.write(0xFE); out.write(0); out.write(0)
            out.write(0xFF); out.write(0xFE)
            out.write(value.toByteArray(Charsets.UTF_16LE))
        }
        return out.toByteArray()
    }

    private fun parseApicFrame(body: ByteArray): Pair<String, ByteArray>? {
        if (body.size < 8) return null
        val enc = body[0].toInt() and 0xFF
        var pos = 1
        while (pos < body.size && body[pos] != 0.toByte()) pos++
        val mime = String(body, 1, pos - 1, Charsets.ISO_8859_1).ifBlank { "image/jpeg" }
        pos += 2 // null + picture type byte
        if (pos >= body.size) return null
        pos = AudioContainerPrimitives.skipTerminatedId3String(body, pos, enc)
        if (pos >= body.size) return null
        return mime to body.copyOfRange(pos, body.size)
    }

    private fun buildApicFrameBody(mimeType: String, imageBytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(mimeType.length + imageBytes.size + 8)
        out.write(0) // ISO-8859-1 description
        out.write(mimeType.toByteArray(Charsets.ISO_8859_1))
        out.write(0)
        out.write(3) // Picture type: Cover (front)
        out.write(0) // Empty description
        out.write(imageBytes)
        return out.toByteArray()
    }
}

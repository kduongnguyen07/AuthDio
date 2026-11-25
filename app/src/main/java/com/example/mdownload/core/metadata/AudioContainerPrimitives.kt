package com.example.mdownload.core.metadata

import com.example.mdownload.core.AudioTagFingerprint
import com.example.mdownload.player.lyrics.AudioContainerFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Locale

/**
 * Shared pure-Kotlin low-level binary primitives for ID3v2 (MP3), FLAC, MP4/M4A, and Ogg/Opus.
 *
 * Used by both [MetadataTagIO] and [com.example.mdownload.player.lyrics.LyricsTagIO] so container
 * parsing, safety validation, and audio payload integrity hashing share a single tested source of
 * truth without duplicating binary format quirks.
 */
object AudioContainerPrimitives {

    const val MAX_TAG_READ_BYTES = 24 * 1024 * 1024
    const val MAX_FLAC_BLOCK_SIZE = 0xFFFFFF // 16,777,215 bytes (24-bit FLAC metadata block ceiling)
    const val MAX_FLAC_PICTURE_BYTES = MetadataValidator.MAX_EMBEDDED_ARTWORK_BYTES // 4 MiB
    const val MAX_FLAC_PICTURE_DIMENSION = 4096
    const val MAX_OGG_HEADER_READ_BYTES = 4 * 1024 * 1024
    const val MAX_OGG_HEADER_PAGES = 256
    private const val STREAM_BUFFER_SIZE = 32 * 1024

    // -------------------------------------------------------------------------
    // Shared ID3v2 (MP3) Primitives
    // -------------------------------------------------------------------------

    data class RawId3Frame(
        val id: String,
        val flags: ByteArray,
        val body: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RawId3Frame) return false
            return id == other.id && flags.contentEquals(other.flags) && body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var res = id.hashCode()
            res = 31 * res + flags.contentHashCode()
            res = 31 * res + body.contentHashCode()
            return res
        }
    }

    sealed interface Id3v2ReadResult {
        data object NoTag : Id3v2ReadResult
        data class Supported(
            val majorVersion: Int,
            val frames: List<RawId3Frame>,
            val payloadOffset: Long
        ) : Id3v2ReadResult

        /**
         * Returned for ID3v2.2 (or unknown versions) so callers NEVER silently discard existing tags.
         */
        data class UnsupportedVersion(
            val majorVersion: Int,
            val payloadOffset: Long
        ) : Id3v2ReadResult
    }

    fun readId3v2(file: File): Id3v2ReadResult {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10L) return Id3v2ReadResult.NoTag
            val hdr = ByteArray(10)
            raf.readFully(hdr)
            if (hdr[0] != 'I'.code.toByte() || hdr[1] != 'D'.code.toByte() || hdr[2] != '3'.code.toByte()) {
                return Id3v2ReadResult.NoTag
            }
            val major = hdr[3].toInt() and 0xFF
            val flags = hdr[5].toInt() and 0xFF
            val tagSize = readSynchsafeInt(hdr, 6)
            val hasFooter = (flags and 0x10) != 0
            val payloadOffset = (10L + tagSize.toLong().coerceAtLeast(0L) + if (hasFooter) 10L else 0L)
                .coerceAtMost(raf.length())

            if (major !in 3..4) {
                return Id3v2ReadResult.UnsupportedVersion(majorVersion = major, payloadOffset = payloadOffset)
            }
            if (tagSize <= 0 || tagSize > MAX_TAG_READ_BYTES || 10L + tagSize > raf.length()) {
                return Id3v2ReadResult.Supported(majorVersion = major, frames = emptyList(), payloadOffset = payloadOffset)
            }

            val rawTag = ByteArray(tagSize)
            raf.readFully(rawTag)
            // Tag-level unsynchronization (bit 0x80) applies to the tag payload on ID3v2.3/v2.4.
            val data = if ((flags and 0x80) != 0) removeUnsync(rawTag) else rawTag

            var pos = 0
            if ((flags and 0x40) != 0 && data.size >= 4) {
                val extSize = if (major == 4) readSynchsafeInt(data, 0) else readBigEndianInt(data, 0)
                pos += if (major == 4) extSize else (4 + extSize)
            }

            val frames = mutableListOf<RawId3Frame>()
            while (pos + 10 <= data.size) {
                if (data[pos] == 0.toByte()) break // padding reached
                val id = String(data, pos, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val size = if (major == 4) readSynchsafeInt(data, pos + 4) else readBigEndianInt(data, pos + 4)
                if (size <= 0 || pos + 10 + size > data.size) break
                val frameFlags = data.copyOfRange(pos + 8, pos + 10)
                var body = data.copyOfRange(pos + 10, pos + 10 + size)

                // Fix A3 (META-02a): When ID3v2.4 per-frame unsynchronization (flag bit 0x02) is set,
                // de-unsynchronize the frame payload AND clear bit 0x02 on frameFlags so rewriting the
                // preserved frame does NOT instruct subsequent decoders to de-unsynchronize a second time.
                if (major == 4 && (frameFlags[1].toInt() and 0x02) != 0) {
                    val hasDataLengthIndicator = (frameFlags[1].toInt() and 0x01) != 0
                    body = removeUnsync(body)
                    frameFlags[1] = (frameFlags[1].toInt() and 0xFD).toByte()
                    // If the frame also carried a 4-byte synchsafe Data Length Indicator (bit 0x01),
                    // strip DLI + clear bit 0x01 if the de-unsynced length no longer needs DLI or keep consistent:
                    if (hasDataLengthIndicator && body.size >= 4) {
                        val declaredLen = readSynchsafeInt(body, 0)
                        if (declaredLen == body.size - 4) {
                            // Keep DLI stripped cleanly and clear bit 0x01 so standard readers see raw frame body
                            body = body.copyOfRange(4, body.size)
                            frameFlags[1] = (frameFlags[1].toInt() and 0xFE).toByte()
                        }
                    }
                }
                frames.add(RawId3Frame(id, frameFlags, body))
                pos += 10 + size
            }
            return Id3v2ReadResult.Supported(majorVersion = major, frames = frames, payloadOffset = payloadOffset)
        }
    }

    fun findMp3AudioPayloadOffset(file: File): Long {
        if (file.length() < 10L) return 0L
        RandomAccessFile(file, "r").use { raf ->
            val hdr = ByteArray(10)
            raf.readFully(hdr)
            if (hdr[0] == 'I'.code.toByte() && hdr[1] == 'D'.code.toByte() && hdr[2] == '3'.code.toByte()) {
                val tagSize = readSynchsafeInt(hdr, 6)
                val hasFooter = (hdr[5].toInt() and 0x10) != 0
                return (10L + tagSize.toLong().coerceAtLeast(0L) + if (hasFooter) 10L else 0L)
                    .coerceAtMost(file.length())
            }
            return 0L
        }
    }

    fun writeId3v2File(
        srcFile: File,
        dstFile: File,
        majorVersion: Int,
        frames: List<RawId3Frame>,
        payloadOffset: Long
    ) {
        val major = majorVersion.coerceIn(3, 4)
        val framesBytes = ByteArrayOutputStream()
        for (frame in frames) {
            val idBytes = frame.id.toByteArray(Charsets.ISO_8859_1)
            if (idBytes.size != 4) continue
            val sanitizedFlags = if (frame.flags.size == 2) frame.flags.copyOf() else byteArrayOf(0, 0)
            // Guarantee unsynchronization flag bit 0x02 is never left set on de-unsynchronized output
            if (major == 4) {
                sanitizedFlags[1] = (sanitizedFlags[1].toInt() and 0xFD).toByte()
            }
            framesBytes.write(idBytes)
            framesBytes.write(if (major == 4) writeSynchsafeInt(frame.body.size) else writeBigEndianInt(frame.body.size))
            framesBytes.write(sanitizedFlags)
            framesBytes.write(frame.body)
        }

        val tagPayload = framesBytes.toByteArray()
        val header = ByteArray(10)
        header[0] = 'I'.code.toByte()
        header[1] = 'D'.code.toByte()
        header[2] = '3'.code.toByte()
        header[3] = major.toByte()
        header[4] = 0
        header[5] = 0 // No tag-level unsynchronization, no footer
        System.arraycopy(writeSynchsafeInt(tagPayload.size), 0, header, 6, 4)

        FileOutputStream(dstFile).use { out ->
            out.write(header)
            out.write(tagPayload)
            RandomAccessFile(srcFile, "r").use { raf ->
                raf.seek(payloadOffset.coerceIn(0L, raf.length()))
                val buf = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val read = raf.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    fun removeUnsync(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size)
        var i = 0
        while (i < data.size) {
            out.write(data[i].toInt())
            if (data[i] == 0xFF.toByte() && i + 1 < data.size && data[i + 1] == 0x00.toByte()) {
                i += 2
            } else {
                i++
            }
        }
        return out.toByteArray()
    }

    fun readTerminatedId3String(data: ByteArray, start: Int, encoding: Int): Pair<Int, String> {
        val doubleNull = encoding == 1 || encoding == 2
        var i = start
        if (doubleNull) {
            while (i + 1 < data.size) {
                if (data[i] == 0.toByte() && data[i + 1] == 0.toByte()) {
                    val str = decodeId3Text(data, start, i - start, encoding)
                    return (i + 2) to str
                }
                i += 2
            }
            return data.size to decodeId3Text(data, start, (data.size - start).coerceAtLeast(0), encoding)
        } else {
            while (i < data.size) {
                if (data[i] == 0.toByte()) {
                    val str = decodeId3Text(data, start, i - start, encoding)
                    return (i + 1) to str
                }
                i++
            }
            return data.size to decodeId3Text(data, start, (data.size - start).coerceAtLeast(0), encoding)
        }
    }

    fun skipTerminatedId3String(data: ByteArray, start: Int, encoding: Int): Int {
        var pos = start
        return if (encoding == 1 || encoding == 2) {
            while (pos + 1 < data.size) {
                if (data[pos] == 0.toByte() && data[pos + 1] == 0.toByte()) return pos + 2
                pos += 2
            }
            data.size
        } else {
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            if (pos < data.size) pos + 1 else data.size
        }
    }

    fun decodeId3Text(data: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (length <= 0 || offset < 0 || offset >= data.size) return ""
        val safeLen = length.coerceAtMost(data.size - offset)
        return when (encoding) {
            0 -> String(data, offset, safeLen, Charsets.ISO_8859_1)
            1 -> when {
                safeLen >= 2 && data[offset] == 0xFF.toByte() && data[offset + 1] == 0xFE.toByte() ->
                    String(data, offset + 2, safeLen - 2, Charsets.UTF_16LE)
                safeLen >= 2 && data[offset] == 0xFE.toByte() && data[offset + 1] == 0xFF.toByte() ->
                    String(data, offset + 2, safeLen - 2, Charsets.UTF_16BE)
                else -> String(data, offset, safeLen, Charset.forName("UTF-16"))
            }
            2 -> String(data, offset, safeLen, Charsets.UTF_16BE)
            else -> String(data, offset, safeLen, Charsets.UTF_8)
        }
    }

    // -------------------------------------------------------------------------
    // Shared FLAC Metadata Block & Safe PICTURE Primitives
    // -------------------------------------------------------------------------

    data class FlacBlock(
        val type: Int,
        val payload: ByteArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is FlacBlock) return false
            return type == other.type && payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int = 31 * type + payload.contentHashCode()
    }

    data class ValidatedImageHeader(
        val mimeType: String,
        val width: Int,
        val height: Int,
        val bitsPerPixel: Int
    )

    fun readFlacBlocks(file: File): Pair<List<FlacBlock>, Long>? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8L) return null
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
            val blocks = mutableListOf<FlacBlock>()
            while (raf.filePointer + 4L <= raf.length()) {
                val hdr = ByteArray(4)
                raf.readFully(hdr)
                val isLast = (hdr[0].toInt() and 0x80) != 0
                val type = hdr[0].toInt() and 0x7F
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                if (len < 0 || len > MAX_FLAC_BLOCK_SIZE || raf.filePointer + len > raf.length()) {
                    return null
                }
                val payload = ByteArray(len)
                raf.readFully(payload)
                blocks.add(FlacBlock(type, payload))
                if (isLast) return blocks to raf.filePointer
            }
            return null
        }
    }

    fun findFlacAudioPayloadOffset(file: File): Long? {
        if (file.length() < 8L) return null
        RandomAccessFile(file, "r").use { raf ->
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null
            while (raf.filePointer + 4L <= raf.length()) {
                val hdr = ByteArray(4)
                raf.readFully(hdr)
                val isLast = (hdr[0].toInt() and 0x80) != 0
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                if (len < 0 || raf.filePointer + len > raf.length()) return null
                raf.seek(raf.filePointer + len)
                if (isLast) return raf.filePointer
            }
            return null
        }
    }

    fun writeFlacFile(
        srcFile: File,
        dstFile: File,
        blocks: List<FlacBlock>,
        audioOffset: Long
    ) {
        // Pre-validate every metadata block length fits inside FLAC's 24-bit header before touching disk
        for (block in blocks) {
            require(block.type in 0..126) { "Invalid FLAC metadata block type: ${block.type}" }
            require(block.payload.size in 0..MAX_FLAC_BLOCK_SIZE) {
                "FLAC metadata block type ${block.type} (${block.payload.size} bytes) exceeds 16 MiB FLAC block limit."
            }
        }
        FileOutputStream(dstFile).use { out ->
            out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
            blocks.forEachIndexed { idx, block ->
                val isLast = idx == blocks.lastIndex
                val first = (block.type and 0x7F) or (if (isLast) 0x80 else 0)
                val len = block.payload.size
                out.write(first)
                out.write((len ushr 16) and 0xFF)
                out.write((len ushr 8) and 0xFF)
                out.write(len and 0xFF)
                out.write(block.payload)
            }
            RandomAccessFile(srcFile, "r").use { raf ->
                raf.seek(audioOffset.coerceIn(0L, raf.length()))
                val buf = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val read = raf.read(buf)
                    if (read <= 0) break
                    out.write(buf, 0, read)
                }
            }
            out.flush()
            runCatching { out.fd.sync() }
        }
    }

    /**
     * Validates artwork MIME type, size ceiling, image header magic, and image dimensions before
     * constructing a FLAC `PICTURE` (type 6) metadata block.
     *
     * Throws [IllegalArgumentException] on oversized, unsupported MIME, or corrupt image payloads.
     */
    fun validateAndBuildFlacPictureBlock(rawMimeType: String, imageBytes: ByteArray): ByteArray {
        require(imageBytes.isNotEmpty()) { "FLAC artwork image bytes cannot be empty." }
        require(imageBytes.size <= MAX_FLAC_PICTURE_BYTES) {
            "FLAC artwork (${imageBytes.size} bytes) exceeds the ${MAX_FLAC_PICTURE_BYTES / (1024 * 1024)} MB limit."
        }
        val header = validateImageHeader(rawMimeType, imageBytes)
        val mimeBytes = header.mimeType.toByteArray(Charsets.ISO_8859_1)
        val totalPayloadSize = 32L + mimeBytes.size.toLong() + imageBytes.size.toLong()
        require(totalPayloadSize <= MAX_FLAC_BLOCK_SIZE.toLong()) {
            "FLAC PICTURE block ($totalPayloadSize bytes) exceeds FLAC 24-bit block limit."
        }

        val out = ByteArrayOutputStream(totalPayloadSize.toInt())
        out.write(writeBigEndianInt(3)) // Picture type: Cover (front)
        out.write(writeBigEndianInt(mimeBytes.size))
        out.write(mimeBytes)
        out.write(writeBigEndianInt(0)) // description length
        out.write(writeBigEndianInt(header.width))
        out.write(writeBigEndianInt(header.height))
        out.write(writeBigEndianInt(header.bitsPerPixel))
        out.write(writeBigEndianInt(0)) // indexed colors
        out.write(writeBigEndianInt(imageBytes.size))
        out.write(imageBytes)
        return out.toByteArray()
    }

    fun validateImageHeader(rawMimeType: String, bytes: ByteArray): ValidatedImageHeader {
        val normalizedMime = when (rawMimeType.trim().lowercase(Locale.US)) {
            "image/jpeg", "image/jpg" -> "image/jpeg"
            "image/png" -> "image/png"
            "image/webp" -> "image/webp"
            else -> throw IllegalArgumentException("Unsupported artwork MIME type: $rawMimeType")
        }

        return when (normalizedMime) {
            "image/png" -> {
                require(
                    bytes.size >= 24 &&
                        bytes[0] == 0x89.toByte() && bytes[1] == 0x50.toByte() &&
                        bytes[2] == 0x4E.toByte() && bytes[3] == 0x47.toByte() &&
                        bytes[4] == 0x0D.toByte() && bytes[5] == 0x0A.toByte() &&
                        bytes[6] == 0x1A.toByte() && bytes[7] == 0x0A.toByte() &&
                        String(bytes, 12, 4, Charsets.ISO_8859_1) == "IHDR"
                ) { "Artwork bytes are not a valid PNG image." }
                val w = readBigEndianInt(bytes, 16)
                val h = readBigEndianInt(bytes, 20)
                require(w in 1..MAX_FLAC_PICTURE_DIMENSION && h in 1..MAX_FLAC_PICTURE_DIMENSION) {
                    "PNG artwork dimensions (${w}x${h}) exceed safe bounds (1..$MAX_FLAC_PICTURE_DIMENSION)."
                }
                ValidatedImageHeader("image/png", w, h, 32)
            }

            "image/jpeg" -> {
                require(bytes.size >= 4 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) {
                    "Artwork bytes are not a valid JPEG image."
                }
                val dims = parseJpegDimensions(bytes)
                if (dims != null) {
                    val (w, h) = dims
                    require(w in 1..MAX_FLAC_PICTURE_DIMENSION && h in 1..MAX_FLAC_PICTURE_DIMENSION) {
                        "JPEG artwork dimensions (${w}x${h}) exceed safe bounds (1..$MAX_FLAC_PICTURE_DIMENSION)."
                    }
                    ValidatedImageHeader("image/jpeg", w, h, 24)
                } else {
                    // Allow minimal JPEG SOI..EOI streams if properly terminated, otherwise reject corrupt data
                    val hasEoi = bytes[bytes.size - 2] == 0xFF.toByte() && bytes[bytes.size - 1] == 0xD9.toByte()
                    require(hasEoi) { "JPEG artwork is truncated or missing SOF/EOI markers." }
                    ValidatedImageHeader("image/jpeg", 0, 0, 24)
                }
            }

            "image/webp" -> {
                require(
                    bytes.size >= 16 &&
                        String(bytes, 0, 4, Charsets.ISO_8859_1) == "RIFF" &&
                        String(bytes, 8, 4, Charsets.ISO_8859_1) == "WEBP"
                ) { "Artwork bytes are not a valid WebP image." }
                val dims = parseWebpDimensions(bytes)
                if (dims != null) {
                    val (w, h) = dims
                    require(w in 1..MAX_FLAC_PICTURE_DIMENSION && h in 1..MAX_FLAC_PICTURE_DIMENSION) {
                        "WebP artwork dimensions (${w}x${h}) exceed safe bounds (1..$MAX_FLAC_PICTURE_DIMENSION)."
                    }
                    ValidatedImageHeader("image/webp", w, h, 24)
                } else {
                    ValidatedImageHeader("image/webp", 0, 0, 24)
                }
            }

            else -> throw IllegalArgumentException("Unsupported artwork MIME type: $rawMimeType")
        }
    }

    private fun parseJpegDimensions(bytes: ByteArray): Pair<Int, Int>? {
        var i = 2
        while (i + 3 < bytes.size) {
            while (i < bytes.size && bytes[i] != 0xFF.toByte()) i++
            while (i < bytes.size && bytes[i] == 0xFF.toByte()) i++
            if (i >= bytes.size) break
            val marker = bytes[i].toInt() and 0xFF
            i++
            if (marker == 0xD9 || marker == 0xDA) break // EOI or SOS
            if (marker == 0x01 || marker in 0xD0..0xD8) continue
            if (i + 1 >= bytes.size) break
            val segLen = ((bytes[i].toInt() and 0xFF) shl 8) or (bytes[i + 1].toInt() and 0xFF)
            if (segLen < 2 || i + segLen > bytes.size) break
            val isSof = marker in setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
            if (isSof && segLen >= 7 && i + 6 < bytes.size) {
                val h = ((bytes[i + 3].toInt() and 0xFF) shl 8) or (bytes[i + 4].toInt() and 0xFF)
                val w = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                return w to h
            }
            i += segLen
        }
        return null
    }

    private fun parseWebpDimensions(bytes: ByteArray): Pair<Int, Int>? {
        if (bytes.size < 30) return null
        val chunk = String(bytes, 12, 4, Charsets.ISO_8859_1)
        return when (chunk) {
            "VP8X" -> {
                val w = 1 + ((bytes[24].toInt() and 0xFF) or
                    ((bytes[25].toInt() and 0xFF) shl 8) or
                    ((bytes[26].toInt() and 0xFF) shl 16))
                val h = 1 + ((bytes[27].toInt() and 0xFF) or
                    ((bytes[28].toInt() and 0xFF) shl 8) or
                    ((bytes[29].toInt() and 0xFF) shl 16))
                w to h
            }
            "VP8L" -> {
                if (bytes[20] != 0x2F.toByte()) return null
                val b0 = bytes[21].toInt() and 0xFF
                val b1 = bytes[22].toInt() and 0xFF
                val b2 = bytes[23].toInt() and 0xFF
                val b3 = bytes[24].toInt() and 0xFF
                val w = 1 + (b0 or ((b1 and 0x3F) shl 8))
                val h = 1 + (((b1 and 0xC0) ushr 6) or (b2 shl 2) or ((b3 and 0x0F) shl 10))
                w to h
            }
            "VP8 " -> {
                if (bytes[23] == 0x9D.toByte() && bytes[24] == 0x01.toByte() && bytes[25] == 0x2A.toByte()) {
                    val w = ((bytes[26].toInt() and 0xFF) or ((bytes[27].toInt() and 0x3F) shl 8))
                    val h = ((bytes[28].toInt() and 0xFF) or ((bytes[29].toInt() and 0x3F) shl 8))
                    w to h
                } else null
            }
            else -> null
        }
    }

    fun parseFlacPictureBlock(payload: ByteArray): Pair<String, ByteArray>? {
        if (payload.size < 32) return null
        var pos = 4 // picture type
        val mimeLen = readBigEndianInt(payload, pos); pos += 4
        if (mimeLen < 0 || pos + mimeLen + 4 > payload.size) return null
        val mime = String(payload, pos, mimeLen, Charsets.ISO_8859_1).ifBlank { "image/jpeg" }
        pos += mimeLen
        val descLen = readBigEndianInt(payload, pos); pos += 4
        if (descLen < 0 || pos + descLen + 20 > payload.size) return null
        pos += descLen + 16
        val dataLen = readBigEndianInt(payload, pos); pos += 4
        if (dataLen <= 0 || pos + dataLen > payload.size) return null
        return mime to payload.copyOfRange(pos, pos + dataLen)
    }

    fun parseVorbisComment(data: ByteArray, startOffset: Int = 0): Pair<String, List<Pair<String, String>>> {
        var pos = startOffset
        if (pos + 4 > data.size) return "" to emptyList()
        val vendorLen = readLittleEndianInt(data, pos); pos += 4
        if (vendorLen < 0 || pos + vendorLen > data.size) return "" to emptyList()
        val vendor = String(data, pos, vendorLen, Charsets.UTF_8); pos += vendorLen
        if (pos + 4 > data.size) return vendor to emptyList()
        val count = readLittleEndianInt(data, pos); pos += 4
        val list = mutableListOf<Pair<String, String>>()
        for (i in 0 until count.coerceAtMost(4096)) {
            if (pos + 4 > data.size) break
            val len = readLittleEndianInt(data, pos); pos += 4
            if (len < 0 || pos + len > data.size) break
            val entry = String(data, pos, len, Charsets.UTF_8); pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) list.add(entry.substring(0, eq) to entry.substring(eq + 1))
        }
        return vendor to list
    }

    fun buildVorbisComment(vendor: String, comments: List<Pair<String, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val vb = vendor.toByteArray(Charsets.UTF_8)
        out.write(writeLittleEndianInt(vb.size))
        out.write(vb)
        out.write(writeLittleEndianInt(comments.size))
        for ((k, v) in comments) {
            val bytes = "$k=$v".toByteArray(Charsets.UTF_8)
            out.write(writeLittleEndianInt(bytes.size))
            out.write(bytes)
        }
        return out.toByteArray()
    }

    // -------------------------------------------------------------------------
    // MP4 / M4A Atom Reader (iTunes `udta/meta/ilst` + QuickTime `meta/keys` + `mdat` hash)
    // -------------------------------------------------------------------------

    data class Mp4ParsedMetadata(
        val editable: EditableTrackMetadata,
        val lyrics: String? = null,
        val rawComment: String = "",
        val freeformTags: Map<String, String> = emptyMap()
    )

    /**
     * Genuine pure-Kotlin MP4/M4A metadata reader parsing:
     * - Standard iTunes atoms (`©nam`, `©ART`, `aART`, `©alb`, `©gen`, `©day`, `trkn`, `disk`,
     *   `©wrt`, `©cmt`, `©lyr`, `covr`)
     * - iTunes freeform `----` atoms (`mean` + `name` + `data`) under `moov/udta/meta/ilst`
     * - QuickTime `mdta` metadata (`moov/meta/keys` + `moov/meta/ilst`) written by FFmpeg `-movflags use_metadata_tags`
     */
    fun readMp4Metadata(file: File): Mp4ParsedMetadata? {
        if (!file.isFile || file.length() < 16L) return null
        return runCatching {
            val acc = Mp4ParseAccumulator()
            RandomAccessFile(file, "r").use { raf ->
                if (!hasMp4Signature(raf)) return null
                walkMp4Boxes(raf, 0L, raf.length(), acc)
            }
            val cleanComment = if (acc.comment.contains(AudioTagFingerprint.TAG_PREFIX)) "" else acc.comment
            val editable = EditableTrackMetadata(
                title = acc.title,
                artist = acc.artist,
                albumArtist = acc.albumArtist,
                album = acc.album,
                genre = acc.genre,
                year = acc.year,
                trackNumber = acc.trackNumber,
                trackTotal = acc.trackTotal,
                discNumber = acc.discNumber,
                discTotal = acc.discTotal,
                composer = acc.composer,
                comment = cleanComment,
                artworkBytes = acc.artworkBytes,
                artworkMimeType = acc.artworkMime,
                format = AudioContainerFormat.MP4_M4A
            )
            Mp4ParsedMetadata(
                editable = editable,
                lyrics = acc.lyrics.takeIf { it.isNotBlank() },
                rawComment = acc.comment,
                freeformTags = acc.freeformTags.toMap()
            )
        }.getOrNull()
    }

    /**
     * Computes SHA-256 across the payload bytes of all `mdat` (encoded audio media data) boxes in
     * an MP4/M4A file. Used to verify that FFmpeg `-c copy` preserved encoded audio samples without
     * transcoding or truncation.
     */
    fun computeMp4MdatSha256(file: File): String? {
        if (!file.isFile || file.length() < 16L) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var hashedBytes = 0L
            RandomAccessFile(file, "r").use { raf ->
                if (!hasMp4Signature(raf)) return null
                var pos = 0L
                val end = raf.length()
                val hdr = ByteArray(8)
                val buf = ByteArray(STREAM_BUFFER_SIZE)
                while (pos + 8L <= end) {
                    raf.seek(pos)
                    raf.readFully(hdr)
                    var size = readBigEndianUInt(hdr, 0)
                    val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
                    var headerLen = 8L
                    if (size == 1L && pos + 16L <= end) {
                        size = raf.readLong()
                        headerLen = 16L
                    } else if (size == 0L) {
                        size = end - pos
                    }
                    if (size < headerLen || pos + size > end) break
                    if (type == "mdat") {
                        var remaining = size - headerLen
                        raf.seek(pos + headerLen)
                        while (remaining > 0L) {
                            val toRead = minOf(buf.size.toLong(), remaining).toInt()
                            val r = raf.read(buf, 0, toRead)
                            if (r <= 0) break
                            digest.update(buf, 0, r)
                            hashedBytes += r
                            remaining -= r
                        }
                    }
                    pos += size
                }
            }
            if (hashedBytes <= 0L) null else digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private class Mp4ParseAccumulator {
        var title = ""
        var artist = ""
        var albumArtist = ""
        var album = ""
        var genre = ""
        var year = ""
        var trackNumber = ""
        var trackTotal = ""
        var discNumber = ""
        var discTotal = ""
        var composer = ""
        var comment = ""
        var lyrics = ""
        var artworkBytes: ByteArray? = null
        var artworkMime = "image/jpeg"
        val freeformTags = linkedMapOf<String, String>()
    }

    private fun hasMp4Signature(raf: RandomAccessFile): Boolean {
        if (raf.length() < 12L) return false
        val hdr = ByteArray(12)
        raf.seek(0L)
        raf.readFully(hdr)
        return String(hdr, 4, 4, Charsets.ISO_8859_1) == "ftyp"
    }

    private fun walkMp4Boxes(raf: RandomAccessFile, start: Long, end: Long, acc: Mp4ParseAccumulator) {
        var pos = start
        val hdr = ByteArray(8)
        while (pos + 8L <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            var size = readBigEndianUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L && pos + 16L <= end) {
                size = raf.readLong()
                headerLen = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerLen || pos + size > end) break
            val childStart = pos + headerLen
            val childEnd = pos + size

            when (type) {
                "moov", "udta" -> walkMp4Boxes(raf, childStart, childEnd, acc)
                "meta" -> parseMp4MetaBox(raf, childStart, childEnd, acc)
            }
            pos = childEnd
        }
    }

    private fun parseMp4MetaBox(raf: RandomAccessFile, start: Long, end: Long, acc: Mp4ParseAccumulator) {
        // MP4 `meta` is normally a FullBox (4-byte version+flags) before children (`hdlr`, `keys`, `ilst`),
        // though some QuickTime writers omit the 4 bytes; detect cleanly by inspecting child box type.
        val bodyStart = if (start + 12L <= end) {
            val probe = ByteArray(8)
            raf.seek(start)
            raf.readFully(probe)
            val probeType = String(probe, 4, 4, Charsets.ISO_8859_1)
            if (probeType in setOf("hdlr", "keys", "ilst")) start else start + 4L
        } else {
            return
        }

        val mdtaKeys = mutableListOf<String>()
        var ilstRange: Pair<Long, Long>? = null

        var pos = bodyStart
        val hdr = ByteArray(8)
        while (pos + 8L <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            var size = readBigEndianUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L && pos + 16L <= end) {
                size = raf.readLong()
                headerLen = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerLen || pos + size > end) break
            val childStart = pos + headerLen
            val childEnd = pos + size
            when (type) {
                "keys" -> parseMp4KeysBox(raf, childStart, childEnd, mdtaKeys)
                "ilst" -> ilstRange = childStart to childEnd
            }
            pos = childEnd
        }

        ilstRange?.let { (iStart, iEnd) ->
            parseMp4Ilst(raf, iStart, iEnd, mdtaKeys, acc)
        }
    }

    private fun parseMp4KeysBox(raf: RandomAccessFile, start: Long, end: Long, outKeys: MutableList<String>) {
        // FullBox: 4 bytes version/flags + 4 bytes entry_count
        if (start + 8L > end) return
        raf.seek(start + 4L)
        val count = raf.readInt().coerceIn(0, 4096)
        var pos = start + 8L
        val hdr = ByteArray(8)
        for (i in 0 until count) {
            if (pos + 8L > end) break
            raf.seek(pos)
            raf.readFully(hdr)
            val entrySize = readBigEndianUInt(hdr, 0)
            if (entrySize < 8L || pos + entrySize > end) break
            val keyLen = (entrySize - 8L).toInt().coerceAtMost(4096)
            val keyBytes = ByteArray(keyLen)
            raf.readFully(keyBytes)
            outKeys.add(String(keyBytes, Charsets.UTF_8).trim())
            pos += entrySize
        }
    }

    private fun parseMp4Ilst(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        mdtaKeys: List<String>,
        acc: Mp4ParseAccumulator
    ) {
        var pos = start
        val hdr = ByteArray(8)
        while (pos + 8L <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            var size = readBigEndianUInt(hdr, 0)
            val rawTypeInt = readBigEndianInt(hdr, 0 + 4)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L && pos + 16L <= end) {
                size = raf.readLong()
                headerLen = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerLen || pos + size > end) break
            val childStart = pos + headerLen
            val childEnd = pos + size

            if (mdtaKeys.isNotEmpty() && rawTypeInt in 1..mdtaKeys.size) {
                val keyName = mdtaKeys[rawTypeInt - 1]
                extractMp4DataAtomBytes(raf, childStart, childEnd)?.let { (_, dataBytes) ->
                    val text = String(dataBytes, Charsets.UTF_8).trim()
                    if (text.isNotEmpty()) {
                        acc.freeformTags[keyName] = text
                        applyMdtaTag(keyName, text, acc)
                    }
                }
            } else {
                when (type) {
                    "\u00A9nam" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.title = it }
                    "\u00A9ART" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.artist = it }
                    "aART" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.albumArtist = it }
                    "\u00A9alb" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.album = it }
                    "\u00A9gen" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.genre = it }
                    "\u00A9day" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.year = it }
                    "\u00A9wrt" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.composer = it }
                    "\u00A9cmt" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.comment = it }
                    "\u00A9lyr" -> extractMp4DataUtf8(raf, childStart, childEnd)?.let { acc.lyrics = it }
                    "trkn" -> extractMp4BinaryPair(raf, childStart, childEnd)?.let { (n, t) ->
                        if (n > 0) acc.trackNumber = n.toString()
                        if (t > 0) acc.trackTotal = t.toString()
                    }
                    "disk" -> extractMp4BinaryPair(raf, childStart, childEnd)?.let { (n, t) ->
                        if (n > 0) acc.discNumber = n.toString()
                        if (t > 0) acc.discTotal = t.toString()
                    }
                    "covr" -> if (acc.artworkBytes == null) {
                        extractMp4DataAtomBytes(raf, childStart, childEnd)?.let { (typeIndicator, bytes) ->
                            if (bytes.isNotEmpty()) {
                                acc.artworkBytes = bytes
                                acc.artworkMime = if (typeIndicator == 14) "image/png" else "image/jpeg"
                            }
                        }
                    }
                    "----" -> parseMp4FreeformAtom(raf, childStart, childEnd)?.let { (k, v) ->
                        acc.freeformTags[k] = v
                    }
                }
            }
            pos = childEnd
        }
    }

    private fun applyMdtaTag(rawKey: String, value: String, acc: Mp4ParseAccumulator) {
        when (rawKey.trim().lowercase(Locale.US)) {
            "title", "com.apple.quicktime.title" -> if (acc.title.isBlank()) acc.title = value
            "artist", "com.apple.quicktime.artist" -> if (acc.artist.isBlank()) acc.artist = value
            "album_artist", "albumartist" -> if (acc.albumArtist.isBlank()) acc.albumArtist = value
            "album", "com.apple.quicktime.album" -> if (acc.album.isBlank()) acc.album = value
            "genre", "com.apple.quicktime.genre" -> if (acc.genre.isBlank()) acc.genre = value
            "date", "year" -> if (acc.year.isBlank()) acc.year = value
            "composer" -> if (acc.composer.isBlank()) acc.composer = value
            "comment" -> if (acc.comment.isBlank()) acc.comment = value
            "lyrics" -> if (acc.lyrics.isBlank()) acc.lyrics = value
            "track" -> {
                val (n, t) = splitSlashPair(value)
                if (acc.trackNumber.isBlank()) acc.trackNumber = n
                if (acc.trackTotal.isBlank()) acc.trackTotal = t
            }
            "disc" -> {
                val (n, t) = splitSlashPair(value)
                if (acc.discNumber.isBlank()) acc.discNumber = n
                if (acc.discTotal.isBlank()) acc.discTotal = t
            }
        }
    }

    private fun extractMp4DataAtomBytes(raf: RandomAccessFile, start: Long, end: Long): Pair<Int, ByteArray>? {
        var pos = start
        val hdr = ByteArray(8)
        while (pos + 8L <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            val size = readBigEndianUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (size < 8L || pos + size > end) break
            if (type == "data" && size > 16L) {
                val typeIndicator = raf.readInt() and 0x00FFFFFF
                raf.readInt() // locale
                val payloadLen = (size - 16L).toInt().coerceAtMost(MAX_TAG_READ_BYTES)
                val bytes = ByteArray(payloadLen)
                raf.readFully(bytes)
                return typeIndicator to bytes
            }
            pos += size
        }
        return null
    }

    private fun extractMp4DataUtf8(raf: RandomAccessFile, start: Long, end: Long): String? {
        val (_, bytes) = extractMp4DataAtomBytes(raf, start, end) ?: return null
        return String(bytes, Charsets.UTF_8).trim().takeIf { it.isNotEmpty() }
    }

    private fun extractMp4BinaryPair(raf: RandomAccessFile, start: Long, end: Long): Pair<Int, Int>? {
        val (_, bytes) = extractMp4DataAtomBytes(raf, start, end) ?: return null
        if (bytes.size < 6) return null
        val num = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val total = ((bytes[4].toInt() and 0xFF) shl 8) or (bytes[5].toInt() and 0xFF)
        return num to total
    }

    private fun parseMp4FreeformAtom(raf: RandomAccessFile, start: Long, end: Long): Pair<String, String>? {
        var pos = start
        val hdr = ByteArray(8)
        var name = ""
        var dataVal = ""
        while (pos + 8L <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            val size = readBigEndianUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (size < 8L || pos + size > end) break
            val payloadLen = (size - 8L).toInt().coerceAtMost(65_536)
            when (type) {
                "name" -> if (payloadLen > 4) {
                    raf.seek(pos + 12L)
                    val buf = ByteArray(payloadLen - 4)
                    raf.readFully(buf)
                    name = String(buf, Charsets.UTF_8).trim()
                }
                "data" -> if (payloadLen > 8) {
                    raf.seek(pos + 16L)
                    val buf = ByteArray(payloadLen - 8)
                    raf.readFully(buf)
                    dataVal = String(buf, Charsets.UTF_8).trim()
                }
            }
            pos += size
        }
        return if (name.isNotBlank() && dataVal.isNotBlank()) name to dataVal else null
    }

    // -------------------------------------------------------------------------
    // Ogg / Opus Comment Packet Reader & Encoded Audio Packet Hash
    // -------------------------------------------------------------------------

    fun extractOggCommentPacketFromFile(file: File): ByteArray? {
        if (!file.isFile || file.length() < 32L) return null
        val cap = minOf(file.length(), MAX_OGG_HEADER_READ_BYTES.toLong()).toInt()
        val bytes = ByteArray(cap)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        return extractOggCommentPacket(bytes)
    }

    fun extractOggCommentPacket(bytes: ByteArray): ByteArray? {
        var pos = 0
        var pages = 0
        val currentPacket = ByteArrayOutputStream()
        while (pos + 27 <= bytes.size && pages < MAX_OGG_HEADER_PAGES) {
            if (bytes[pos] != 'O'.code.toByte() || bytes[pos + 1] != 'g'.code.toByte() ||
                bytes[pos + 2] != 'g'.code.toByte() || bytes[pos + 3] != 'S'.code.toByte()
            ) break
            val pageSegments = bytes[pos + 26].toInt() and 0xFF
            val tableStart = pos + 27
            var dataPos = tableStart + pageSegments
            if (dataPos > bytes.size) break
            for (s in 0 until pageSegments) {
                val segLen = bytes[tableStart + s].toInt() and 0xFF
                if (dataPos + segLen > bytes.size) return null
                currentPacket.write(bytes, dataPos, segLen)
                dataPos += segLen
                if (segLen < 255) {
                    val finished = currentPacket.toByteArray()
                    currentPacket.reset()
                    if (isOggCommentPacket(finished)) return finished
                }
            }
            pos = dataPos
            pages++
        }
        return null
    }

    /**
     * Computes SHA-256 across all encoded Ogg audio packets (i.e., packets after the OpusHead/OpusTags
     * or Vorbis ID/comment/setup headers). Because FFmpeg `-c copy` may rewrite Ogg page framing/serials
     * without re-encoding Opus/Vorbis audio frames, hashing logical audio packets verifies untouched audio data.
     */
    fun computeOggAudioPacketsSha256(file: File): String? {
        if (!file.isFile || file.length() < 32L) return null
        return runCatching {
            val digest = MessageDigest.getInstance("SHA-256")
            var hashedBytes = 0L
            var isVorbis = false
            var packetIndex = 0
            val currentPacket = ByteArrayOutputStream()

            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val hdr = ByteArray(27)
                var pos = 0L
                while (pos + 27L <= len) {
                    raf.seek(pos)
                    raf.readFully(hdr)
                    if (hdr[0] != 'O'.code.toByte() || hdr[1] != 'g'.code.toByte() ||
                        hdr[2] != 'g'.code.toByte() || hdr[3] != 'S'.code.toByte()
                    ) break
                    val segCount = hdr[26].toInt() and 0xFF
                    if (pos + 27L + segCount > len) break
                    val segTable = ByteArray(segCount)
                    raf.readFully(segTable)
                    var bodyPos = pos + 27L + segCount
                    for (s in 0 until segCount) {
                        val segLen = segTable[s].toInt() and 0xFF
                        if (bodyPos + segLen > len) return null
                        val segBytes = ByteArray(segLen)
                        raf.readFully(segBytes)
                        currentPacket.write(segBytes)
                        bodyPos += segLen
                        if (segLen < 255) {
                            val pkt = currentPacket.toByteArray()
                            currentPacket.reset()
                            if (packetIndex == 0 && pkt.size >= 7 && pkt[0] == 1.toByte() &&
                                String(pkt, 1, 6, Charsets.ISO_8859_1) == "vorbis"
                            ) {
                                isVorbis = true
                            }
                            val headerPacketCount = if (isVorbis) 3 else 2
                            if (packetIndex >= headerPacketCount) {
                                digest.update(pkt)
                                hashedBytes += pkt.size
                            }
                            packetIndex++
                        }
                    }
                    pos = bodyPos
                }
            }
            if (hashedBytes <= 0L) null else digest.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    private fun isOggCommentPacket(packet: ByteArray): Boolean {
        if (packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags") return true
        if (packet.size > 7 && packet[0] == 3.toByte() &&
            String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis"
        ) return true
        return false
    }

    // -------------------------------------------------------------------------
    // Shared Hashing & Binary Helpers
    // -------------------------------------------------------------------------

    fun computeAudioPayloadSha256(file: File, format: AudioContainerFormat): String? {
        if (!file.isFile) return null
        return when (format) {
            AudioContainerFormat.MP3 -> computeSha256FromOffset(file, findMp3AudioPayloadOffset(file))
            AudioContainerFormat.FLAC -> {
                val offset = findFlacAudioPayloadOffset(file) ?: return null
                computeSha256FromOffset(file, offset)
            }
            AudioContainerFormat.MP4_M4A -> computeMp4MdatSha256(file)
            AudioContainerFormat.OGG_OPUS -> computeOggAudioPacketsSha256(file)
            AudioContainerFormat.UNSUPPORTED -> null
        }
    }

    fun computeFileSha256(file: File): String? = computeSha256FromOffset(file, 0L)

    fun computeSha256FromOffset(file: File, offset: Long): String? {
        if (!file.isFile) return null
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            RandomAccessFile(file, "r").use { raf ->
                raf.seek(offset.coerceIn(0L, raf.length()))
                val buf = ByteArray(STREAM_BUFFER_SIZE)
                while (true) {
                    val r = raf.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        }.getOrNull()
    }

    fun splitSlashPair(raw: String): Pair<String, String> {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return "" to ""
        val slash = trimmed.indexOf('/')
        return if (slash >= 0) {
            trimmed.substring(0, slash).trim() to trimmed.substring(slash + 1).trim()
        } else {
            trimmed to ""
        }
    }

    fun formatSlashPair(first: String, second: String): String? {
        val a = first.trim()
        val b = second.trim()
        return when {
            a.isNotEmpty() && b.isNotEmpty() -> "$a/$b"
            a.isNotEmpty() -> a
            b.isNotEmpty() -> "0/$b"
            else -> null
        }
    }

    fun readSynchsafeInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    fun writeSynchsafeInt(v: Int): ByteArray = byteArrayOf(
        ((v ushr 21) and 0x7F).toByte(),
        ((v ushr 14) and 0x7F).toByte(),
        ((v ushr 7) and 0x7F).toByte(),
        (v and 0x7F).toByte()
    )

    fun readBigEndianInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    fun readBigEndianUInt(b: ByteArray, off: Int): Long =
        (((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL))

    fun writeBigEndianInt(v: Int): ByteArray = byteArrayOf(
        ((v ushr 24) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        (v and 0xFF).toByte()
    )

    fun readLittleEndianInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)

    fun writeLittleEndianInt(v: Int): ByteArray = byteArrayOf(
        (v and 0xFF).toByte(),
        ((v ushr 8) and 0xFF).toByte(),
        ((v ushr 16) and 0xFF).toByte(),
        ((v ushr 24) and 0xFF).toByte()
    )
}

package com.example.mdownload.core.audio

import android.content.Context
import android.net.Uri
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.LyricsTagIO
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Pure-Kotlin ReplayGain & R128 loudness tag reader supporting:
 * - MP3 (ID3v2.3 / ID3v2.4 `TXXX` frames & `RVA2` frames)
 * - FLAC (Vorbis comments: `REPLAYGAIN_*` and `R128_*`)
 * - Ogg Vorbis & Opus (`VorbisComment` / `OpusTags`: `R128_TRACK_GAIN`, `R128_ALBUM_GAIN`, `REPLAYGAIN_*`)
 * - M4A / MP4 (iTunes freeform `----` atoms)
 *
 * Unit conversions:
 * - ReplayGain tags are stored in dB strings (e.g. `-6.42 dB`) referenced to -18 LUFS (89 dB SPL).
 * - R128_TRACK_GAIN / R128_ALBUM_GAIN (RFC 7845 / Opus) are signed integers in Q7.8 fixed-point
 *   (`1/256 dB`) referenced to -23 LUFS. Converting to the -18 LUFS ReplayGain target adds `+5.0 dB`.
 */
object ReplayGainMetadataReader {

    private const val MAX_HEADER_COPY_BYTES = 2 * 1024 * 1024L // 2 MiB max temp read for content URIs
    private const val R128_TO_REPLAYGAIN_OFFSET_DB = 5.0f

    fun readFromUri(
        context: Context,
        uriString: String,
        displayName: String = "",
        mimeType: String = ""
    ): ReplayGainInfo? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
            val file = File(uri.path ?: uriString)
            return if (file.isFile) readFromFile(file, displayName.ifBlank { file.name }, mimeType) else null
        }

        val format = LyricsTagIO.detectFormat(displayName, mimeType)
        val tmp = File(context.cacheDir, "rg_probe_${System.nanoTime()}.tmp")
        return try {
            val copied = copyRelevantBytesFromUri(context, uri, format, tmp)
            if (!copied || !tmp.isFile || tmp.length() < 16L) return null
            readFromFile(tmp, displayName, mimeType)
        } finally {
            tmp.delete()
        }
    }

    fun readFromFile(
        file: File,
        displayName: String = file.name,
        mimeType: String = ""
    ): ReplayGainInfo? {
        if (!file.isFile || file.length() < 16L) return null
        val format = LyricsTagIO.detectFormat(displayName, mimeType, file)
        return runCatching {
            when (format) {
                AudioContainerFormat.MP3 -> readMp3ReplayGain(file)
                AudioContainerFormat.FLAC -> readFlacReplayGain(file)
                AudioContainerFormat.OGG_OPUS -> readOggOrOpusReplayGain(file)
                AudioContainerFormat.MP4_M4A -> readMp4ReplayGain(file)
                AudioContainerFormat.UNSUPPORTED -> null
            }
        }.getOrNull()
    }

    fun parseGainDbString(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim()
            .replace("dB", "", ignoreCase = true)
            .replace(",", ".")
            .trim()
        val parsed = cleaned.toFloatOrNull() ?: return null
        return ReplayGainCalculator.sanitizeGainDb(parsed)
    }

    fun parsePeakString(raw: String?): Float? {
        if (raw.isNullOrBlank()) return null
        val parsed = raw.trim().replace(",", ".").toFloatOrNull() ?: return null
        return ReplayGainCalculator.sanitizePeak(parsed)
    }

    /**
     * Converts RFC 7845 `R128_*_GAIN` Q7.8 fixed-point value (referenced to -23 LUFS)
     * into ReplayGain dB referenced to -18 LUFS (+5.0 dB alignment).
     */
    fun r128Q78ToReplayGainDb(raw: String?): Float? {
        val q78 = raw?.trim()?.toIntOrNull() ?: return null
        val r128Db = q78 / 256.0f
        return ReplayGainCalculator.sanitizeGainDb(r128Db + R128_TO_REPLAYGAIN_OFFSET_DB)
    }

    // -------------------------------------------------------------------------
    // MP3 (ID3v2.3 / ID3v2.4 TXXX + RVA2)
    // -------------------------------------------------------------------------

    private fun readMp3ReplayGain(file: File): ReplayGainInfo? {
        val frames = readId3v2Frames(file) ?: return null
        var trackGain: Float? = null
        var albumGain: Float? = null
        var trackPeak: Float? = null
        var albumPeak: Float? = null
        var source = ""

        for ((id, body) in frames) {
            when (id) {
                "TXXX" -> {
                    val (desc, value) = parseTxxx(body) ?: continue
                    when (desc.trim().uppercase(Locale.US)) {
                        "REPLAYGAIN_TRACK_GAIN" -> {
                            parseGainDbString(value)?.let { trackGain = it; source = "ID3v2 TXXX" }
                        }
                        "REPLAYGAIN_ALBUM_GAIN" -> {
                            parseGainDbString(value)?.let { albumGain = it; source = "ID3v2 TXXX" }
                        }
                        "REPLAYGAIN_TRACK_PEAK" -> {
                            parsePeakString(value)?.let { trackPeak = it }
                        }
                        "REPLAYGAIN_ALBUM_PEAK" -> {
                            parsePeakString(value)?.let { albumPeak = it }
                        }
                    }
                }
                "RVA2" -> {
                    val parsed = parseRva2(body) ?: continue
                    val (ident, gainDb, peak) = parsed
                    val upper = ident.uppercase(Locale.US)
                    if (upper.contains("ALBUM")) {
                        if (albumGain == null) {
                            albumGain = gainDb
                            if (source.isBlank()) source = "ID3v2 RVA2"
                        }
                        if (albumPeak == null && peak != null) albumPeak = peak
                    } else {
                        if (trackGain == null) {
                            trackGain = gainDb
                            if (source.isBlank()) source = "ID3v2 RVA2"
                        }
                        if (trackPeak == null && peak != null) trackPeak = peak
                    }
                }
            }
        }

        if (trackGain == null && albumGain == null && trackPeak == null && albumPeak == null) return null
        return ReplayGainInfo(
            trackGainDb = trackGain,
            albumGainDb = albumGain,
            trackPeak = trackPeak,
            albumPeak = albumPeak,
            source = source.ifBlank { "ID3v2" },
            targetLoudnessLufs = -18.0f
        )
    }

    private fun readId3v2Frames(file: File): List<Pair<String, ByteArray>>? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 10L) return null
            val hdr = ByteArray(10)
            raf.readFully(hdr)
            if (hdr[0] != 'I'.code.toByte() || hdr[1] != 'D'.code.toByte() || hdr[2] != '3'.code.toByte()) {
                return null
            }
            val major = hdr[3].toInt() and 0xFF
            if (major !in 3..4) return null
            val flags = hdr[5].toInt() and 0xFF
            val tagSize = readSynchsafeInt(hdr, 6)
            if (tagSize <= 0 || tagSize > 16 * 1024 * 1024 || 10L + tagSize > raf.length()) return null

            val raw = ByteArray(tagSize)
            raf.readFully(raw)
            val data = if ((flags and 0x80) != 0) removeUnsync(raw) else raw
            var pos = 0
            if ((flags and 0x40) != 0 && data.size >= 4) {
                val extSize = if (major == 4) readSynchsafeInt(data, 0) else readBigEndianInt(data, 0)
                pos += if (major == 4) extSize else (4 + extSize)
            }

            val result = mutableListOf<Pair<String, ByteArray>>()
            while (pos + 10 <= data.size) {
                if (data[pos] == 0.toByte()) break
                val id = String(data, pos, 4, Charsets.ISO_8859_1)
                if (!id.all { it in 'A'..'Z' || it in '0'..'9' }) break
                val size = if (major == 4) readSynchsafeInt(data, pos + 4) else readBigEndianInt(data, pos + 4)
                if (size <= 0 || pos + 10 + size > data.size) break
                val frameFlags = data.copyOfRange(pos + 8, pos + 10)
                var body = data.copyOfRange(pos + 10, pos + 10 + size)
                if (major == 4 && (frameFlags[1].toInt() and 0x02) != 0) {
                    body = removeUnsync(body)
                }
                result.add(id to body)
                pos += 10 + size
            }
            return result
        }
    }

    private fun parseTxxx(body: ByteArray): Pair<String, String>? {
        if (body.size < 2) return null
        val encoding = body[0].toInt() and 0xFF
        val (afterDesc, desc) = readTerminatedString(body, 1, encoding)
        if (afterDesc >= body.size) return null
        val value = decodeText(body, afterDesc, body.size - afterDesc, encoding).trimEnd('\u0000')
        return desc to value
    }

    private fun parseRva2(body: ByteArray): Triple<String, Float, Float?>? {
        var pos = 0
        while (pos < body.size && body[pos] != 0.toByte()) pos++
        val ident = String(body, 0, pos, Charsets.ISO_8859_1)
        pos++ // skip null
        while (pos + 4 <= body.size) {
            val channelType = body[pos].toInt() and 0xFF
            val adjRaw = ((body[pos + 1].toInt() shl 8) or (body[pos + 2].toInt() and 0xFF)).toShort().toInt()
            val gainDb = ReplayGainCalculator.sanitizeGainDb(adjRaw / 512.0f)
            val peakBits = body[pos + 3].toInt() and 0xFF
            val peakBytes = (peakBits + 7) / 8
            pos += 4
            var peak: Float? = null
            if (peakBytes > 0 && pos + peakBytes <= body.size && peakBits in 1..31) {
                var peakInt = 0L
                for (i in 0 until peakBytes) {
                    peakInt = (peakInt shl 8) or (body[pos + i].toLong() and 0xFFL)
                }
                val denom = (1L shl peakBits).toFloat()
                if (denom > 0f) peak = ReplayGainCalculator.sanitizePeak(peakInt.toFloat() / denom)
            }
            pos += peakBytes
            if (channelType == 1 && gainDb != null) {
                return Triple(ident, gainDb, peak)
            }
        }
        return null
    }

    // -------------------------------------------------------------------------
    // FLAC (Vorbis comments block type 4)
    // -------------------------------------------------------------------------

    private fun readFlacReplayGain(file: File): ReplayGainInfo? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 8L) return null
            val magic = ByteArray(4)
            raf.readFully(magic)
            if (String(magic, Charsets.ISO_8859_1) != "fLaC") return null

            while (raf.filePointer + 4 <= raf.length()) {
                val hdr = ByteArray(4)
                raf.readFully(hdr)
                val isLast = (hdr[0].toInt() and 0x80) != 0
                val type = hdr[0].toInt() and 0x7F
                val len = ((hdr[1].toInt() and 0xFF) shl 16) or
                    ((hdr[2].toInt() and 0xFF) shl 8) or
                    (hdr[3].toInt() and 0xFF)
                if (len < 0 || raf.filePointer + len > raf.length()) break
                if (type == 4 && len <= 4 * 1024 * 1024) {
                    val payload = ByteArray(len)
                    raf.readFully(payload)
                    val comments = parseVorbisComments(payload, 0)
                    return parseCommentsToReplayGain(comments, isOpus = false, defaultSource = "FLAC Vorbis Comment")
                } else {
                    raf.seek(raf.filePointer + len)
                }
                if (isLast) break
            }
            return null
        }
    }

    // -------------------------------------------------------------------------
    // Ogg Vorbis & Ogg Opus
    // -------------------------------------------------------------------------

    private fun readOggOrOpusReplayGain(file: File): ReplayGainInfo? {
        val maxRead = minOf(file.length(), 512 * 1024L).toInt()
        if (maxRead < 32) return null
        val bytes = ByteArray(maxRead)
        RandomAccessFile(file, "r").use { raf -> raf.readFully(bytes) }
        val packet = extractOggCommentPacket(bytes) ?: return null
        return when {
            packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags" -> {
                val comments = parseVorbisComments(packet, 8)
                parseCommentsToReplayGain(comments, isOpus = true, defaultSource = "OpusTags")
            }
            packet.size > 7 && packet[0] == 3.toByte() &&
                String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> {
                val comments = parseVorbisComments(packet, 7)
                parseCommentsToReplayGain(comments, isOpus = false, defaultSource = "Vorbis Comment")
            }
            else -> null
        }
    }

    private fun parseCommentsToReplayGain(
        comments: List<Pair<String, String>>,
        isOpus: Boolean,
        defaultSource: String
    ): ReplayGainInfo? {
        var rgTrackGain: Float? = null
        var rgAlbumGain: Float? = null
        var rgTrackPeak: Float? = null
        var rgAlbumPeak: Float? = null
        var r128TrackGain: Float? = null
        var r128AlbumGain: Float? = null

        for ((rawKey, value) in comments) {
            when (rawKey.trim().uppercase(Locale.US)) {
                "REPLAYGAIN_TRACK_GAIN" -> parseGainDbString(value)?.let { rgTrackGain = it }
                "REPLAYGAIN_ALBUM_GAIN" -> parseGainDbString(value)?.let { rgAlbumGain = it }
                "REPLAYGAIN_TRACK_PEAK" -> parsePeakString(value)?.let { rgTrackPeak = it }
                "REPLAYGAIN_ALBUM_PEAK" -> parsePeakString(value)?.let { rgAlbumPeak = it }
                "R128_TRACK_GAIN" -> r128Q78ToReplayGainDb(value)?.let { r128TrackGain = it }
                "R128_ALBUM_GAIN" -> r128Q78ToReplayGainDb(value)?.let { r128AlbumGain = it }
            }
        }

        // For Opus, RFC 7845 specifies R128_*_GAIN as primary; fall back to REPLAYGAIN_*.
        // For FLAC/Vorbis, prefer REPLAYGAIN_*; fall back to R128_*_GAIN if present.
        val trackGain = if (isOpus) r128TrackGain ?: rgTrackGain else rgTrackGain ?: r128TrackGain
        val albumGain = if (isOpus) r128AlbumGain ?: rgAlbumGain else rgAlbumGain ?: r128AlbumGain
        val usedR128 = (isOpus && (r128TrackGain != null || r128AlbumGain != null)) ||
            (!isOpus && rgTrackGain == null && rgAlbumGain == null && (r128TrackGain != null || r128AlbumGain != null))

        if (trackGain == null && albumGain == null && rgTrackPeak == null && rgAlbumPeak == null) return null
        return ReplayGainInfo(
            trackGainDb = trackGain,
            albumGainDb = albumGain,
            trackPeak = rgTrackPeak,
            albumPeak = rgAlbumPeak,
            source = if (usedR128) "$defaultSource (R128→RG -18 LUFS)" else defaultSource,
            targetLoudnessLufs = -18.0f
        )
    }

    // -------------------------------------------------------------------------
    // M4A / MP4 (iTunes freeform `----` atoms under moov -> udta -> meta -> ilst)
    // -------------------------------------------------------------------------

    private fun readMp4ReplayGain(file: File): ReplayGainInfo? {
        // Parse BOTH iTunes `moov/udta/meta/ilst/----` freeform atoms AND QuickTime `moov/meta/keys + ilst` (`mdta`)
        // written by FFmpeg `-movflags use_metadata_tags`.
        val parsed = com.example.mdownload.core.metadata.AudioContainerPrimitives.readMp4Metadata(file)
        val map = mutableMapOf<String, String>()
        parsed?.freeformTags?.forEach { (k, v) ->
            map[k.trim().uppercase(Locale.US)] = v
        }
        if (map.isEmpty()) {
            RandomAccessFile(file, "r").use { raf ->
                scanMp4FreeformAtoms(raf, 0L, raf.length(), map)
            }
        }
        if (map.isEmpty()) return null
        val trackGain = parseGainDbString(map["REPLAYGAIN_TRACK_GAIN"])
        val albumGain = parseGainDbString(map["REPLAYGAIN_ALBUM_GAIN"])
        val trackPeak = parsePeakString(map["REPLAYGAIN_TRACK_PEAK"])
        val albumPeak = parsePeakString(map["REPLAYGAIN_ALBUM_PEAK"])
        if (trackGain == null && albumGain == null && trackPeak == null && albumPeak == null) return null
        return ReplayGainInfo(
            trackGainDb = trackGain,
            albumGainDb = albumGain,
            trackPeak = trackPeak,
            albumPeak = albumPeak,
            source = "MP4 iTunes/QuickTime atom",
            targetLoudnessLufs = -18.0f
        )
    }

    private fun scanMp4FreeformAtoms(
        raf: RandomAccessFile,
        start: Long,
        end: Long,
        out: MutableMap<String, String>
    ) {
        var pos = start
        val hdr = ByteArray(8)
        while (pos + 8 <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            var size = readBigEndianUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            var headerLen = 8L
            if (size == 1L && pos + 16 <= end) {
                val ext = ByteArray(8)
                raf.readFully(ext)
                size = 0L
                for (b in ext) size = (size shl 8) or (b.toLong() and 0xFFL)
                headerLen = 16L
            } else if (size == 0L) {
                size = end - pos
            }
            if (size < headerLen || pos + size > end) break
            val payloadStart = pos + headerLen
            val atomEnd = pos + size

            when (type) {
                "moov", "udta", "ilst" -> scanMp4FreeformAtoms(raf, payloadStart, atomEnd, out)
                "meta" -> if (payloadStart + 4 < atomEnd) scanMp4FreeformAtoms(raf, payloadStart + 4, atomEnd, out)
                "----" -> parseFreeformAtom(raf, payloadStart, atomEnd)?.let { (k, v) ->
                    out[k.uppercase(Locale.US)] = v
                }
            }
            pos = atomEnd
        }
    }

    private fun parseFreeformAtom(raf: RandomAccessFile, start: Long, end: Long): Pair<String, String>? {
        var pos = start
        val hdr = ByteArray(8)
        var name = ""
        var dataVal = ""
        while (pos + 8 <= end) {
            raf.seek(pos)
            raf.readFully(hdr)
            val size = readBigEndianUInt(hdr, 0)
            val type = String(hdr, 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || pos + size > end) break
            val payloadLen = (size - 8).toInt().coerceAtMost(65_536)
            when (type) {
                "name" -> if (payloadLen > 4) {
                    raf.seek(pos + 12)
                    val buf = ByteArray(payloadLen - 4)
                    raf.readFully(buf)
                    name = String(buf, Charsets.UTF_8).trim()
                }
                "data" -> if (payloadLen > 8) {
                    raf.seek(pos + 16)
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
    // Shared binary helpers
    // -------------------------------------------------------------------------

    private fun copyRelevantBytesFromUri(
        context: Context,
        uri: Uri,
        format: AudioContainerFormat,
        target: File
    ): Boolean {
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(target).use { out ->
                    // MP4 moov atoms can sit at the end of the file, so copy full file when small (<= 32MB);
                    // for header-based formats (MP3/FLAC/OGG) copy at most MAX_HEADER_COPY_BYTES.
                    val cap = if (format == AudioContainerFormat.MP4_M4A) 32L * 1024L * 1024L else MAX_HEADER_COPY_BYTES
                    val buf = ByteArray(32 * 1024)
                    var remaining = cap
                    while (remaining > 0L) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val read = input.read(buf, 0, toRead)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
            }
            target.isFile && target.length() > 16L
        }.getOrDefault(false)
    }

    private fun extractOggCommentPacket(data: ByteArray): ByteArray? {
        var pos = 0
        var packetOut = ByteArrayOutputStream()
        var pageCount = 0
        while (pos + 27 <= data.size && pageCount < 16) {
            if (data[pos] != 'O'.code.toByte() || data[pos + 1] != 'g'.code.toByte() ||
                data[pos + 2] != 'g'.code.toByte() || data[pos + 3] != 'S'.code.toByte()
            ) break
            val segCount = data[pos + 26].toInt() and 0xFF
            if (pos + 27 + segCount > data.size) break
            val segTableStart = pos + 27
            var bodyPos = segTableStart + segCount
            for (i in 0 until segCount) {
                val segLen = data[segTableStart + i].toInt() and 0xFF
                if (bodyPos + segLen > data.size) return null
                packetOut.write(data, bodyPos, segLen)
                bodyPos += segLen
                if (segLen < 255) {
                    val pkt = packetOut.toByteArray()
                    if (isOggCommentPacket(pkt)) return pkt
                    packetOut = ByteArrayOutputStream()
                }
            }
            pos = bodyPos
            pageCount++
        }
        return null
    }

    private fun isOggCommentPacket(packet: ByteArray): Boolean {
        if (packet.size > 8 && String(packet, 0, 8, Charsets.ISO_8859_1) == "OpusTags") return true
        if (packet.size > 7 && packet[0] == 3.toByte() &&
            String(packet, 1, 6, Charsets.ISO_8859_1) == "vorbis"
        ) return true
        return false
    }

    private fun parseVorbisComments(data: ByteArray, startOffset: Int): List<Pair<String, String>> {
        var pos = startOffset
        if (pos + 4 > data.size) return emptyList()
        val vendorLen = readLittleEndianInt(data, pos)
        pos += 4
        if (vendorLen < 0 || pos + vendorLen > data.size) return emptyList()
        pos += vendorLen
        if (pos + 4 > data.size) return emptyList()
        val count = readLittleEndianInt(data, pos)
        pos += 4
        val comments = mutableListOf<Pair<String, String>>()
        for (i in 0 until count.coerceAtMost(4096)) {
            if (pos + 4 > data.size) break
            val len = readLittleEndianInt(data, pos)
            pos += 4
            if (len < 0 || pos + len > data.size) break
            val entry = String(data, pos, len, Charsets.UTF_8)
            pos += len
            val eq = entry.indexOf('=')
            if (eq > 0) comments.add(entry.substring(0, eq) to entry.substring(eq + 1))
        }
        return comments
    }

    private fun readTerminatedString(data: ByteArray, start: Int, encoding: Int): Pair<Int, String> {
        val isUtf16 = encoding == 1 || encoding == 2
        if (isUtf16) {
            var pos = start
            while (pos + 1 < data.size) {
                if (data[pos] == 0.toByte() && data[pos + 1] == 0.toByte()) {
                    return (pos + 2) to decodeText(data, start, pos - start, encoding)
                }
                pos += 2
            }
            return data.size to decodeText(data, start, data.size - start, encoding)
        } else {
            var pos = start
            while (pos < data.size && data[pos] != 0.toByte()) pos++
            val next = if (pos < data.size) pos + 1 else data.size
            return next to decodeText(data, start, pos - start, encoding)
        }
    }

    private fun decodeText(data: ByteArray, offset: Int, length: Int, encoding: Int): String {
        if (length <= 0 || offset < 0 || offset >= data.size) return ""
        val safeLen = length.coerceAtMost(data.size - offset)
        return when (encoding) {
            0 -> String(data, offset, safeLen, Charsets.ISO_8859_1)
            1 -> when {
                safeLen >= 2 && data[offset] == 0xFF.toByte() && data[offset + 1] == 0xFE.toByte() ->
                    String(data, offset + 2, safeLen - 2, Charsets.UTF_16LE)
                safeLen >= 2 && data[offset] == 0xFE.toByte() && data[offset + 1] == 0xFF.toByte() ->
                    String(data, offset + 2, safeLen - 2, Charsets.UTF_16BE)
                else -> String(data, offset, safeLen, Charsets.UTF_16)
            }
            2 -> String(data, offset, safeLen, Charsets.UTF_16BE)
            3 -> String(data, offset, safeLen, Charsets.UTF_8)
            else -> String(data, offset, safeLen, Charsets.UTF_8)
        }
    }

    private fun removeUnsync(data: ByteArray): ByteArray {
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

    private fun readSynchsafeInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0x7F) shl 21) or
            ((b[off + 1].toInt() and 0x7F) shl 14) or
            ((b[off + 2].toInt() and 0x7F) shl 7) or
            (b[off + 3].toInt() and 0x7F)

    private fun readBigEndianInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)

    private fun readBigEndianUInt(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFFL) shl 24) or
            ((b[off + 1].toLong() and 0xFFL) shl 16) or
            ((b[off + 2].toLong() and 0xFFL) shl 8) or
            (b[off + 3].toLong() and 0xFFL)

    private fun readLittleEndianInt(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            ((b[off + 2].toInt() and 0xFF) shl 16) or
            ((b[off + 3].toInt() and 0xFF) shl 24)
}

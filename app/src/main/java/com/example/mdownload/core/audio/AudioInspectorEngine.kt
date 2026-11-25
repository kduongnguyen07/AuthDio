package com.example.mdownload.core.audio

import android.content.Context
import android.graphics.BitmapFactory
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.LyricsTagIO
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

/**
 * Unified local audio quality inspector combining:
 * 1. Pure-Kotlin binary container/header parsers (FLAC STREAMINFO, MP3 MPEG + Xing/VBRI VBR/CBR, Ogg OpusHead/Vorbis)
 * 2. Android [MediaExtractor] & [MediaMetadataRetriever]
 * 3. Optional bundled `libffprobe.so` JSON enrichment (off-main-thread, bounded timeout)
 *
 * Truthfulness guarantees:
 * - Unknown properties stay null; values are never fabricated.
 * - Lossy codecs explicitly keep `bitDepth = null` (never misreporting decoder PCM width).
 * - Lossless classification reflects the encoded codec only, with an explicit caveat when
 *   source provenance (e.g., YouTube download) cannot prove a lossless master recording.
 */
object AudioInspectorEngine {

    private const val FFPROBE_TIMEOUT_SECONDS = 5L
    private const val MAX_HEADER_PROBE_BYTES = 256 * 1024

    fun classifyCodecLossless(codec: String?, mimeType: String? = null): Boolean? {
        val raw = (codec ?: mimeType).orEmpty().lowercase(Locale.US)
        if (raw.isBlank()) return null
        return when {
            raw.contains("flac") || raw.contains("alac") || raw.contains("pcm") ||
                raw.contains("wav") || raw.contains("aiff") || raw.contains("ape") ||
                raw.contains("wavpack") || raw.contains("dsd") || raw.contains("raw") -> true

            raw.contains("mp3") || raw.contains("mpeg") || raw.contains("aac") ||
                raw.contains("mp4a") || raw.contains("opus") || raw.contains("vorbis") ||
                raw.contains("ogg") || raw.contains("wma") || raw.contains("ac3") ||
                raw.contains("eac3") || raw.contains("amr") -> false

            else -> null
        }
    }

    fun normalizeCodecLabel(rawCodec: String?, mimeType: String? = null): String? {
        val raw = (rawCodec ?: mimeType).orEmpty().lowercase(Locale.US)
        if (raw.isBlank()) return null
        return when {
            raw == "mp3" || raw.contains("audio/mpeg") || raw.contains("mp3") -> "MP3"
            raw.contains("flac") -> "FLAC"
            raw.contains("alac") -> "ALAC"
            raw.contains("opus") -> "Opus"
            raw.contains("vorbis") -> "Vorbis"
            raw.contains("aac") || raw.contains("mp4a") -> "AAC"
            raw.contains("pcm_s16") -> "PCM (16-bit)"
            raw.contains("pcm_s24") -> "PCM (24-bit)"
            raw.contains("pcm_s32") -> "PCM (32-bit)"
            raw.contains("pcm_f32") -> "PCM (32-bit float)"
            raw.contains("pcm") || raw.contains("audio/raw") -> "PCM"
            else -> rawCodec?.uppercase(Locale.US)
        }
    }

    fun normalizeContainerLabel(rawContainer: String?, displayName: String, mimeType: String): String? {
        val lowerName = displayName.lowercase(Locale.US)
        val lowerMime = mimeType.lowercase(Locale.US)
        val raw = rawContainer.orEmpty().lowercase(Locale.US)
        return when {
            raw.contains("flac") || lowerName.endsWith(".flac") || lowerMime.contains("flac") -> "FLAC"
            raw.contains("mp3") || lowerName.endsWith(".mp3") || lowerMime.contains("mpeg") -> "MP3"
            raw.contains("m4a") || raw.contains("mp4") || raw.contains("mov") ||
                lowerName.endsWith(".m4a") || lowerName.endsWith(".mp4") || lowerMime.contains("mp4") -> "MP4 / M4A"
            raw.contains("ogg") || lowerName.endsWith(".ogg") || lowerName.endsWith(".opus") ||
                lowerMime.contains("ogg") || lowerMime.contains("opus") -> "Ogg"
            raw.contains("matroska") || raw.contains("webm") || lowerName.endsWith(".webm") || lowerName.endsWith(".mka") -> "WebM / Matroska"
            raw.contains("wav") || lowerName.endsWith(".wav") || lowerMime.contains("wav") -> "WAV"
            raw.isNotBlank() -> rawContainer?.uppercase(Locale.US)
            else -> null
        }
    }

    suspend fun inspectTrack(
        context: Context,
        track: LibraryTrack,
        hasLyrics: Boolean?,
        lyricsSourceLabel: String?,
        allowFfprobe: Boolean = true
    ): AudioQualityInfo {
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull()
        val sources = mutableListOf<String>()

        var container = normalizeContainerLabel(null, track.displayName, track.mimeType)
        var codec: String? = null
        var codecProfile: String? = null
        var bitrate: Long? = track.bitrate.takeIf { it > 0 }?.toLong()
        var bitrateMode = BitrateMode.UNKNOWN
        var sampleRate: Int? = null
        var channels: Int? = null
        var channelLayout: String? = null
        var bitDepth: Int? = null
        var sampleFormat: String? = null
        var durationMs: Long? = track.durationMs.takeIf { it > 0L }
        var fileSizeBytes: Long? = track.sizeBytes.takeIf { it > 0L }
        var hasEmbeddedArtwork: Boolean? = null
        var artworkDimensions: String? = null

        // 1. Android MediaExtractor (fast, works directly on content:// URIs)
        if (uri != null) {
            extractWithMediaExtractor(context, uri)?.let { ext ->
                sources.add("MediaExtractor")
                codec = codec ?: ext.codec
                codecProfile = codecProfile ?: ext.codecProfile
                if (ext.bitrate != null && ext.bitrate > 0L) bitrate = ext.bitrate
                if (ext.sampleRate != null && ext.sampleRate > 0) sampleRate = ext.sampleRate
                if (ext.channels != null && ext.channels > 0) channels = ext.channels
                if (ext.durationMs != null && ext.durationMs > 0L) durationMs = ext.durationMs
                if (ext.bitDepth != null && ext.bitDepth > 0) bitDepth = ext.bitDepth
            }

            // 2. MediaMetadataRetriever for artwork presence & fallback bitrate/duration
            extractWithRetriever(context, uri)?.let { ret ->
                sources.add("MediaMetadataRetriever")
                if (bitrate == null && ret.bitrate != null) bitrate = ret.bitrate
                if (durationMs == null && ret.durationMs != null) durationMs = ret.durationMs
                hasEmbeddedArtwork = ret.hasArtwork
                artworkDimensions = ret.artworkDimensions
            }

            // 3. Pure-Kotlin binary header inspection (FLAC STREAMINFO / MP3 Xing/VBRI / OpusHead)
            probeBinaryHeadersFromUri(context, uri, track.displayName, track.mimeType)?.let { hdr ->
                sources.add("HeaderParser")
                if (hdr.codec != null) codec = hdr.codec
                if (hdr.container != null) container = hdr.container
                if (hdr.sampleRate != null) sampleRate = hdr.sampleRate
                if (hdr.channels != null) channels = hdr.channels
                if (hdr.bitDepth != null) bitDepth = hdr.bitDepth
                if (hdr.bitrateMode != BitrateMode.UNKNOWN) bitrateMode = hdr.bitrateMode
                if (hdr.bitrate != null && bitrate == null) bitrate = hdr.bitrate
            }

            // 4. Optional FFprobe enrichment when bundled binary is available
            if (allowFfprobe) {
                probeWithFfprobe(context, uri, track.displayName)?.let { ff ->
                    sources.add("FFprobe")
                    if (!ff.codec.isNullOrBlank()) codec = normalizeCodecLabel(ff.codec, track.mimeType)
                    if (!ff.codecProfile.isNullOrBlank()) codecProfile = ff.codecProfile
                    if (!ff.container.isNullOrBlank()) {
                        container = normalizeContainerLabel(ff.container, track.displayName, track.mimeType)
                    }
                    if (ff.bitrate != null && ff.bitrate > 0L) bitrate = ff.bitrate
                    if (ff.sampleRate != null && ff.sampleRate > 0) sampleRate = ff.sampleRate
                    if (ff.channels != null && ff.channels > 0) channels = ff.channels
                    if (!ff.channelLayout.isNullOrBlank()) channelLayout = ff.channelLayout
                    if (ff.bitDepth != null && ff.bitDepth > 0) bitDepth = ff.bitDepth
                    if (!ff.sampleFormat.isNullOrBlank()) sampleFormat = ff.sampleFormat
                    if (ff.durationMs != null && ff.durationMs > 0L) durationMs = ff.durationMs
                    if (ff.fileSizeBytes != null && ff.fileSizeBytes > 0L) fileSizeBytes = ff.fileSizeBytes
                    if (ff.hasVideoArtworkStream != null && hasEmbeddedArtwork == null) {
                        hasEmbeddedArtwork = ff.hasVideoArtworkStream
                    }
                }
            }
        }

        // Normalize codec & lossless classification strictly from encoded codec
        val resolvedCodec = normalizeCodecLabel(codec, track.mimeType)
        val isLossless = classifyCodecLossless(resolvedCodec, track.mimeType)

        // Never report meaningless PCM bit depth for lossy codecs
        val truthfulBitDepth = if (isLossless == true) bitDepth else null
        val truthfulSampleFormat = if (isLossless == true) sampleFormat else sampleFormat?.let { "$it (decoder)" }

        // Compute average bitrate from file size + duration only if bitrate is still missing and both exist
        if (bitrate == null && fileSizeBytes != null && durationMs != null && durationMs > 1000L) {
            val approx = (fileSizeBytes * 8L * 1000L) / durationMs
            if (approx in 8_000L..20_000_000L) {
                bitrate = approx
            }
        }

        val replayGain = ReplayGainMetadataReader.readFromUri(
            context = context,
            uriString = track.uri,
            displayName = track.displayName,
            mimeType = track.mimeType
        )

        val losslessCaveat = if (isLossless == true) {
            if (track.isDownloadedByApp || track.videoId.isNotBlank()) {
                "Lossless container/codec only; audio downloaded from streaming sources originates from a lossy stream."
            } else {
                "Lossless codec confirmed; container encoding alone does not prove the recording originated from a lossless master."
            }
        } else {
            null
        }

        val location = buildString {
            if (track.folderPath.isNotBlank()) {
                append(track.folderPath.trimEnd('/'))
                append('/')
            }
            append(track.displayName.ifBlank { track.uri })
        }

        return AudioQualityInfo(
            uri = track.uri,
            codec = resolvedCodec,
            codecProfile = codecProfile?.takeIf { it.isNotBlank() && !it.equals("unknown", true) },
            container = container,
            bitrate = bitrate,
            bitrateMode = bitrateMode,
            sampleRate = sampleRate,
            channels = channels,
            channelLayout = channelLayout ?: when (channels) {
                1 -> "Mono"
                2 -> "Stereo"
                6 -> "5.1"
                8 -> "7.1"
                else -> null
            },
            bitDepth = truthfulBitDepth,
            sampleFormat = truthfulSampleFormat,
            durationMs = durationMs,
            fileSizeBytes = fileSizeBytes,
            isLossless = isLossless,
            losslessCaveat = losslessCaveat,
            fileLocation = location,
            hasEmbeddedArtwork = hasEmbeddedArtwork,
            artworkDimensions = artworkDimensions,
            replayGain = replayGain,
            hasLyrics = hasLyrics,
            lyricsSourceLabel = lyricsSourceLabel,
            inspectionSource = sources.distinct().joinToString(" + ").ifBlank { "Unavailable" }
        )
    }

    /**
     * Pure local-file inspection entry point (usable directly in unit tests).
     */
    fun inspectLocalFilePure(file: File, isDownloadedByApp: Boolean = false): AudioQualityInfo {
        if (!file.isFile) {
            return AudioQualityInfo(uri = file.toURI().toString(), inspectionSource = "File missing")
        }
        val hdr = probeBinaryHeadersFromFile(file, file.name, "")
        val resolvedCodec = normalizeCodecLabel(hdr?.codec, file.extension)
        val isLossless = classifyCodecLossless(resolvedCodec, file.extension)
        val rg = ReplayGainMetadataReader.readFromFile(file, file.name, "")
        return AudioQualityInfo(
            uri = file.toURI().toString(),
            codec = resolvedCodec,
            container = hdr?.container ?: normalizeContainerLabel(null, file.name, ""),
            bitrate = hdr?.bitrate,
            bitrateMode = hdr?.bitrateMode ?: BitrateMode.UNKNOWN,
            sampleRate = hdr?.sampleRate,
            channels = hdr?.channels,
            channelLayout = when (hdr?.channels) {
                1 -> "Mono"
                2 -> "Stereo"
                else -> null
            },
            bitDepth = if (isLossless == true) hdr?.bitDepth else null,
            durationMs = hdr?.durationMs,
            fileSizeBytes = file.length(),
            isLossless = isLossless,
            losslessCaveat = if (isLossless == true) {
                if (isDownloadedByApp) {
                    "Lossless container/codec only; audio downloaded from streaming sources originates from a lossy stream."
                } else {
                    "Lossless codec confirmed; container encoding alone does not prove a lossless master."
                }
            } else null,
            fileLocation = file.absolutePath,
            replayGain = rg,
            inspectionSource = if (hdr != null) "HeaderParser" else "Unrecognized file"
        )
    }

    /**
     * Queries live Android audio output routing without fabricating DAC hardware specs.
     */
    fun queryActiveAudioOutputDevice(context: Context): Pair<String?, String?> {
        return runCatching {
            val audioManager = context.getSystemService(AudioManager::class.java) ?: return null to null
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            // Prefer external active routes over built-in speaker if attached
            val priority = listOf(
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_BLE_HEADSET,
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_HDMI,
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            )
            val chosen = priority.firstNotNullOfOrNull { type -> devices.firstOrNull { it.type == type } }
                ?: devices.firstOrNull()
                ?: return null to null

            val typeLabel = when (chosen.type) {
                AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE -> "USB Audio"
                AudioDeviceInfo.TYPE_BLE_HEADSET -> "Bluetooth LE Audio"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "Bluetooth A2DP"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "Wired Headphones"
                AudioDeviceInfo.TYPE_WIRED_HEADSET -> "Wired Headset"
                AudioDeviceInfo.TYPE_HDMI -> "HDMI"
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "Device Speaker"
                AudioDeviceInfo.TYPE_BUILTIN_EARPIECE -> "Earpiece"
                else -> "Android Audio Output"
            }
            val name = chosen.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel
            name to typeLabel
        }.getOrDefault(null to null)
    }

    // -------------------------------------------------------------------------
    // Internal probe implementations
    // -------------------------------------------------------------------------

    private data class ExtractorProbe(
        val codec: String?,
        val codecProfile: String?,
        val bitrate: Long?,
        val sampleRate: Int?,
        val channels: Int?,
        val durationMs: Long?,
        val bitDepth: Int?
    )

    private fun extractWithMediaExtractor(context: Context, uri: Uri): ExtractorProbe? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(context, uri, null)
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("audio/")) continue
                val sr = if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else null
                val ch = if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else null
                val br = if (fmt.containsKey(MediaFormat.KEY_BIT_RATE)) fmt.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else null
                val durUs = if (fmt.containsKey(MediaFormat.KEY_DURATION)) fmt.getLong(MediaFormat.KEY_DURATION) else null
                val pcmEnc = if (fmt.containsKey(MediaFormat.KEY_PCM_ENCODING)) fmt.getInteger(MediaFormat.KEY_PCM_ENCODING) else null
                val profileInt = if (fmt.containsKey(MediaFormat.KEY_AAC_PROFILE)) fmt.getInteger(MediaFormat.KEY_AAC_PROFILE) else null
                val profile = when (profileInt) {
                    2 -> "AAC-LC"
                    5 -> "HE-AAC"
                    29 -> "HE-AAC v2"
                    else -> null
                }
                val depth = when (pcmEnc) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT -> 8
                    android.media.AudioFormat.ENCODING_PCM_16BIT -> 16
                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 24
                    android.media.AudioFormat.ENCODING_PCM_32BIT,
                    android.media.AudioFormat.ENCODING_PCM_FLOAT -> 32
                    else -> null
                }
                return ExtractorProbe(
                    codec = normalizeCodecLabel(null, mime),
                    codecProfile = profile,
                    bitrate = br?.takeIf { it > 0L },
                    sampleRate = sr?.takeIf { it > 0 },
                    channels = ch?.takeIf { it > 0 },
                    durationMs = durUs?.takeIf { it > 0L }?.div(1000L),
                    bitDepth = depth
                )
            }
            null
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private data class RetrieverProbe(
        val bitrate: Long?,
        val durationMs: Long?,
        val hasArtwork: Boolean,
        val artworkDimensions: String?
    )

    private fun extractWithRetriever(context: Context, uri: Uri): RetrieverProbe? {
        val mmr = MediaMetadataRetriever()
        return try {
            mmr.setDataSource(context, uri)
            val br = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.takeIf { it > 0L }
            val dur = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.takeIf { it > 0L }
            val pic = mmr.embeddedPicture
            var dims: String? = null
            if (pic != null && pic.isNotEmpty()) {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(pic, 0, pic.size, opts)
                if (opts.outWidth > 0 && opts.outHeight > 0) {
                    dims = "${opts.outWidth} × ${opts.outHeight} px"
                }
            }
            RetrieverProbe(
                bitrate = br,
                durationMs = dur,
                hasArtwork = pic != null && pic.isNotEmpty(),
                artworkDimensions = dims
            )
        } catch (_: Exception) {
            null
        } finally {
            runCatching { mmr.release() }
        }
    }

    private data class BinaryHeaderProbe(
        val codec: String? = null,
        val container: String? = null,
        val sampleRate: Int? = null,
        val channels: Int? = null,
        val bitDepth: Int? = null,
        val bitrate: Long? = null,
        val bitrateMode: BitrateMode = BitrateMode.UNKNOWN,
        val durationMs: Long? = null
    )

    private fun probeBinaryHeadersFromUri(
        context: Context,
        uri: Uri,
        displayName: String,
        mimeType: String
    ): BinaryHeaderProbe? {
        val tmp = File(context.cacheDir, "hdr_probe_${System.nanoTime()}.tmp")
        return try {
            context.contentResolver.openInputStream(uri)?.use { inp ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(16 * 1024)
                    var remaining = MAX_HEADER_PROBE_BYTES
                    while (remaining > 0) {
                        val read = inp.read(buf, 0, minOf(buf.size, remaining))
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        remaining -= read
                    }
                }
            }
            if (!tmp.isFile || tmp.length() < 16L) return null
            probeBinaryHeadersFromFile(tmp, displayName, mimeType)
        } catch (_: Exception) {
            null
        } finally {
            tmp.delete()
        }
    }

    private fun probeBinaryHeadersFromFile(
        file: File,
        displayName: String,
        mimeType: String
    ): BinaryHeaderProbe? {
        val format = LyricsTagIO.detectFormat(displayName, mimeType, file)
        return runCatching {
            when (format) {
                AudioContainerFormat.FLAC -> parseFlacStreamInfo(file)
                AudioContainerFormat.MP3 -> parseMp3Header(file)
                AudioContainerFormat.OGG_OPUS -> parseOggHeader(file)
                AudioContainerFormat.MP4_M4A -> BinaryHeaderProbe(container = "MP4 / M4A")
                AudioContainerFormat.UNSUPPORTED -> parseWavHeader(file)
            }
        }.getOrNull()
    }

    private fun parseFlacStreamInfo(file: File): BinaryHeaderProbe? {
        RandomAccessFile(file, "r").use { raf ->
            if (raf.length() < 42L) return null
            val hdr = ByteArray(42)
            raf.readFully(hdr)
            if (String(hdr, 0, 4, Charsets.ISO_8859_1) != "fLaC") return null
            val blockType = hdr[4].toInt() and 0x7F
            if (blockType != 0) return BinaryHeaderProbe(codec = "FLAC", container = "FLAC")
            // STREAMINFO starts at offset 8; bytes 10..17 of STREAMINFO pack sampleRate(20), channels-1(3), bps-1(5), totalSamples(36)
            val b10 = hdr[18].toLong() and 0xFFL
            val b11 = hdr[19].toLong() and 0xFFL
            val b12 = hdr[20].toLong() and 0xFFL
            val b13 = hdr[21].toLong() and 0xFFL
            val b14 = hdr[22].toLong() and 0xFFL
            val b15 = hdr[23].toLong() and 0xFFL
            val b16 = hdr[24].toLong() and 0xFFL
            val b17 = hdr[25].toLong() and 0xFFL

            val sampleRate = ((b10 shl 12) or (b11 shl 4) or (b12 ushr 4)).toInt()
            val channels = (((b12 ushr 1) and 0x07L) + 1L).toInt()
            val bitsPerSample = ((((b12 and 0x01L) shl 4) or (b13 ushr 4)) + 1L).toInt()
            val totalSamples = ((b13 and 0x0FL) shl 32) or (b14 shl 24) or (b15 shl 16) or (b16 shl 8) or b17
            val durationMs = if (sampleRate > 0 && totalSamples > 0) (totalSamples * 1000L) / sampleRate else null

            return BinaryHeaderProbe(
                codec = "FLAC",
                container = "FLAC",
                sampleRate = sampleRate.takeIf { it > 0 },
                channels = channels.takeIf { it in 1..8 },
                bitDepth = bitsPerSample.takeIf { it in 4..32 },
                bitrateMode = BitrateMode.VBR,
                durationMs = durationMs
            )
        }
    }

    private fun parseMp3Header(file: File): BinaryHeaderProbe? {
        val len = minOf(file.length(), MAX_HEADER_PROBE_BYTES.toLong()).toInt()
        if (len < 16) return null
        val bytes = ByteArray(len)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }

        var offset = 0
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) {
            val tagSize = ((bytes[6].toInt() and 0x7F) shl 21) or
                ((bytes[7].toInt() and 0x7F) shl 14) or
                ((bytes[8].toInt() and 0x7F) shl 7) or
                (bytes[9].toInt() and 0x7F)
            offset = (10 + tagSize).coerceAtMost(len - 4)
        }

        // Scan for MPEG-1 Layer III frame sync (11 set bits)
        var framePos = -1
        for (i in offset until (len - 4)) {
            if ((bytes[i].toInt() and 0xFF) == 0xFF && (bytes[i + 1].toInt() and 0xE0) == 0xE0) {
                val versionBits = (bytes[i + 1].toInt() ushr 3) and 0x03
                val layerBits = (bytes[i + 1].toInt() ushr 1) and 0x03
                val brIdx = (bytes[i + 2].toInt() ushr 4) and 0x0F
                val srIdx = (bytes[i + 2].toInt() ushr 2) and 0x03
                if (versionBits != 1 && layerBits == 1 && brIdx in 1..14 && srIdx in 0..2) {
                    framePos = i
                    break
                }
            }
        }
        if (framePos < 0) return BinaryHeaderProbe(codec = "MP3", container = "MP3")

        val b1 = bytes[framePos + 1].toInt() and 0xFF
        val b2 = bytes[framePos + 2].toInt() and 0xFF
        val b3 = bytes[framePos + 3].toInt() and 0xFF
        val isMpeg1 = ((b1 ushr 3) and 0x03) == 3
        val brIdx = (b2 ushr 4) and 0x0F
        val srIdx = (b2 ushr 2) and 0x03
        val channelMode = (b3 ushr 6) and 0x03

        val mpeg1Bitrates = intArrayOf(0, 32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 0)
        val mpeg2Bitrates = intArrayOf(0, 8, 16, 24, 32, 40, 48, 56, 64, 80, 96, 112, 128, 144, 160, 0)
        val mpeg1Rates = intArrayOf(44100, 48000, 32000)
        val mpeg2Rates = intArrayOf(22050, 24000, 16000)

        val frameBitrateBps = (if (isMpeg1) mpeg1Bitrates[brIdx] else mpeg2Bitrates[brIdx]) * 1000L
        val sampleRate = if (isMpeg1) mpeg1Rates[srIdx] else mpeg2Rates[srIdx]
        val channels = if (channelMode == 3) 1 else 2

        // Inspect Xing / Info / VBRI tag inside first frame to distinguish VBR vs CBR
        val sideInfoSize = when {
            isMpeg1 && channels == 1 -> 17
            isMpeg1 -> 32
            channels == 1 -> 9
            else -> 17
        }
        val xingPos = framePos + 4 + sideInfoSize
        var mode = BitrateMode.UNKNOWN
        if (xingPos + 4 <= len) {
            val tag = String(bytes, xingPos, 4, Charsets.ISO_8859_1)
            mode = when (tag) {
                "Xing" -> BitrateMode.VBR
                "Info" -> BitrateMode.CBR
                else -> mode
            }
        }
        if (mode == BitrateMode.UNKNOWN && framePos + 36 + 4 <= len) {
            val vbri = String(bytes, framePos + 36, 4, Charsets.ISO_8859_1)
            if (vbri == "VBRI") mode = BitrateMode.VBR
        }

        return BinaryHeaderProbe(
            codec = "MP3",
            container = "MP3",
            sampleRate = sampleRate,
            channels = channels,
            bitrate = frameBitrateBps.takeIf { it > 0L },
            bitrateMode = mode
        )
    }

    private fun parseOggHeader(file: File): BinaryHeaderProbe? {
        val len = minOf(file.length(), 4096L).toInt()
        if (len < 48) return null
        val bytes = ByteArray(len)
        RandomAccessFile(file, "r").use { it.readFully(bytes) }
        val text = String(bytes, Charsets.ISO_8859_1)
        val opusIdx = text.indexOf("OpusHead")
        if (opusIdx >= 0 && opusIdx + 16 <= len) {
            val channels = bytes[opusIdx + 9].toInt() and 0xFF
            // Opus always decodes at 48 kHz internally; input sample rate is in bytes 12..15 LE
            val inputSr = (bytes[opusIdx + 12].toInt() and 0xFF) or
                ((bytes[opusIdx + 13].toInt() and 0xFF) shl 8) or
                ((bytes[opusIdx + 14].toInt() and 0xFF) shl 16) or
                ((bytes[opusIdx + 15].toInt() and 0xFF) shl 24)
            return BinaryHeaderProbe(
                codec = "Opus",
                container = "Ogg",
                sampleRate = if (inputSr > 0) inputSr else 48000,
                channels = channels.takeIf { it in 1..8 },
                bitrateMode = BitrateMode.VBR
            )
        }
        val vorbisIdx = text.indexOf("\u0001vorbis")
        if (vorbisIdx >= 0 && vorbisIdx + 20 <= len) {
            val channels = bytes[vorbisIdx + 11].toInt() and 0xFF
            val sr = (bytes[vorbisIdx + 12].toInt() and 0xFF) or
                ((bytes[vorbisIdx + 13].toInt() and 0xFF) shl 8) or
                ((bytes[vorbisIdx + 14].toInt() and 0xFF) shl 16) or
                ((bytes[vorbisIdx + 15].toInt() and 0xFF) shl 24)
            return BinaryHeaderProbe(
                codec = "Vorbis",
                container = "Ogg",
                sampleRate = sr.takeIf { it > 0 },
                channels = channels.takeIf { it in 1..8 },
                bitrateMode = BitrateMode.VBR
            )
        }
        return BinaryHeaderProbe(container = "Ogg")
    }

    private fun parseWavHeader(file: File): BinaryHeaderProbe? {
        if (file.length() < 44L) return null
        val hdr = ByteArray(44)
        RandomAccessFile(file, "r").use { it.readFully(hdr) }
        if (String(hdr, 0, 4, Charsets.ISO_8859_1) != "RIFF" ||
            String(hdr, 8, 4, Charsets.ISO_8859_1) != "WAVE"
        ) return null
        val channels = (hdr[22].toInt() and 0xFF) or ((hdr[23].toInt() and 0xFF) shl 8)
        val sr = (hdr[24].toInt() and 0xFF) or
            ((hdr[25].toInt() and 0xFF) shl 8) or
            ((hdr[26].toInt() and 0xFF) shl 16) or
            ((hdr[27].toInt() and 0xFF) shl 24)
        val byteRate = (hdr[28].toInt() and 0xFF) or
            ((hdr[29].toInt() and 0xFF) shl 8) or
            ((hdr[30].toInt() and 0xFF) shl 16) or
            ((hdr[31].toInt() and 0xFF) shl 24)
        val bps = (hdr[34].toInt() and 0xFF) or ((hdr[35].toInt() and 0xFF) shl 8)
        return BinaryHeaderProbe(
            codec = "PCM",
            container = "WAV",
            sampleRate = sr.takeIf { it > 0 },
            channels = channels.takeIf { it in 1..8 },
            bitDepth = bps.takeIf { it in 8..32 },
            bitrate = (byteRate.toLong() * 8L).takeIf { it > 0L },
            bitrateMode = BitrateMode.CBR
        )
    }

    private data class FfprobeResult(
        val codec: String?,
        val codecProfile: String?,
        val container: String?,
        val bitrate: Long?,
        val sampleRate: Int?,
        val channels: Int?,
        val channelLayout: String?,
        val bitDepth: Int?,
        val sampleFormat: String?,
        val durationMs: Long?,
        val fileSizeBytes: Long?,
        val hasVideoArtworkStream: Boolean?
    )

    private suspend fun probeWithFfprobe(context: Context, uri: Uri, displayName: String): FfprobeResult? {
        currentCoroutineContext().ensureActive()
        val ffprobeBin = File(context.applicationInfo.nativeLibraryDir, "libffprobe.so")
        if (!ffprobeBin.isFile) return null
        val packagesDir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")
        val ffmpegLibDir = File(packagesDir, "ffmpeg/usr/lib")
        if (!ffmpegLibDir.isDirectory) return null

        val ext = displayName.substringAfterLast('.', "tmp").lowercase(Locale.US).take(6)
        val localFile: File
        var tempCreated = false
        if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
            localFile = File(uri.path ?: uri.toString())
            if (!localFile.isFile) return null
        } else {
            val tmp = File(context.cacheDir, "ffprobe_${System.nanoTime()}.$ext")
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = inp.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                }
                tmp.isFile && tmp.length() > 64L
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (_: Exception) {
                false
            }
            if (!copied) {
                tmp.delete()
                return null
            }
            localFile = tmp
            tempCreated = true
        }

        return try {
            currentCoroutineContext().ensureActive()
            val ldPath = listOf(
                ffmpegLibDir.absolutePath,
                File(packagesDir, "python/usr/lib").absolutePath
            ).joinToString(":")

            val runResult = BoundedSubprocessRunner.run(
                command = listOf(
                    ffprobeBin.absolutePath,
                    "-v", "quiet",
                    "-print_format", "json",
                    "-show_format",
                    "-show_streams",
                    localFile.absolutePath
                ),
                environment = mapOf("LD_LIBRARY_PATH" to ldPath),
                timeoutMs = FFPROBE_TIMEOUT_SECONDS * 1000L,
                mergeErrorStream = false,
                maxStdoutChars = 512 * 1024,
                maxStderrChars = 64 * 1024
            )
            val success = runResult as? BoundedSubprocessRunner.SubprocessResult.Success ?: return null
            if (success.stdout.isBlank() || success.stdoutTruncated) return null

            val root = JSONObject(success.stdout)
            val format = root.optJSONObject("format")
            val streams = root.optJSONArray("streams")
            var audioStream: JSONObject? = null
            var hasArtStream = false
            if (streams != null) {
                for (i in 0 until streams.length()) {
                    val s = streams.optJSONObject(i) ?: continue
                    val type = s.optString("codec_type")
                    if (type == "audio" && audioStream == null) {
                        audioStream = s
                    } else if (type == "video") {
                        val disp = s.optJSONObject("disposition")
                        if (disp?.optInt("attached_pic", 0) == 1) hasArtStream = true
                    }
                }
            }
            if (audioStream == null) return null

            val bitRate = audioStream.optString("bit_rate").toLongOrNull()
                ?: format?.optString("bit_rate")?.toLongOrNull()
            val sr = audioStream.optString("sample_rate").toIntOrNull()
            val ch = audioStream.optInt("channels", 0).takeIf { it > 0 }
            val bps = audioStream.optInt("bits_per_raw_sample", 0).takeIf { it > 0 }
                ?: audioStream.optInt("bits_per_sample", 0).takeIf { it > 0 }
            val durSec = audioStream.optString("duration").toDoubleOrNull()
                ?: format?.optString("duration")?.toDoubleOrNull()
            val size = format?.optString("size")?.toLongOrNull()

            FfprobeResult(
                codec = audioStream.optString("codec_name").takeIf { it.isNotBlank() },
                codecProfile = audioStream.optString("profile").takeIf { it.isNotBlank() && !it.equals("unknown", true) },
                container = format?.optString("format_name")?.takeIf { it.isNotBlank() },
                bitrate = bitRate?.takeIf { it > 0L },
                sampleRate = sr?.takeIf { it > 0 },
                channels = ch,
                channelLayout = audioStream.optString("channel_layout").takeIf { it.isNotBlank() },
                bitDepth = bps,
                sampleFormat = audioStream.optString("sample_fmt").takeIf { it.isNotBlank() },
                durationMs = durSec?.takeIf { it > 0.0 }?.let { (it * 1000.0).toLong() },
                fileSizeBytes = size?.takeIf { it > 0L },
                hasVideoArtworkStream = hasArtStream
            )
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } finally {
            if (tempCreated) localFile.delete()
        }
    }
}

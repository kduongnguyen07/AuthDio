package com.example.mdownload.core.audio

import com.example.mdownload.core.AppSettings
import java.util.Locale

enum class ReplayGainMode(val id: String, val label: String) {
    OFF("off", "Off"),
    TRACK("track", "Track Gain"),
    ALBUM("album", "Album Gain");

    companion object {
        fun fromId(id: String?): ReplayGainMode =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: OFF
    }
}

enum class MissingReplayGainBehavior(val id: String, val label: String) {
    LEAVE_UNCHANGED("unchanged", "Leave unchanged (0 dB)"),
    APPLY_FALLBACK_PREAMP("fallback_preamp", "Apply fallback preamp");

    companion object {
        fun fromId(id: String?): MissingReplayGainBehavior =
            entries.firstOrNull { it.id.equals(id, ignoreCase = true) } ?: LEAVE_UNCHANGED
    }
}

data class ReplayGainConfig(
    val mode: ReplayGainMode = ReplayGainMode.OFF,
    val preampDb: Float = 0.0f,
    val missingBehavior: MissingReplayGainBehavior = MissingReplayGainBehavior.LEAVE_UNCHANGED,
    val fallbackPreampDb: Float = 0.0f,
    val preventClipping: Boolean = true,
    val maxGainDb: Float = 12.0f,
    val minGainDb: Float = -24.0f
) {
    companion object {
        fun fromAppSettings(settings: AppSettings): ReplayGainConfig = ReplayGainConfig(
            mode = ReplayGainMode.fromId(settings.replayGainMode),
            preampDb = settings.replayGainPreampDb.coerceIn(-12f, 12f),
            missingBehavior = MissingReplayGainBehavior.fromId(settings.replayGainMissingBehavior),
            fallbackPreampDb = settings.replayGainFallbackPreampDb.coerceIn(-12f, 6f),
            preventClipping = settings.replayGainPreventClipping
        )
    }
}

/**
 * Normalized loudness & ReplayGain metadata extracted from audio file tags or an optional offline scan.
 *
 * Conventions:
 * - Standard ReplayGain tags (ID3v2 TXXX, Vorbis REPLAYGAIN_*, MP4 iTunes atoms) reference -18 LUFS (89 dB SPL).
 * - Opus R128_TRACK_GAIN / R128_ALBUM_GAIN are stored in Q7.8 fixed-point (1/256 dB) referenced to -23 LUFS;
 *   [ReplayGainMetadataReader] converts them to the -18 LUFS ReplayGain convention (+5.0 dB offset) and records
 *   [source] accordingly.
 * - Optional EBU R128 offline scan results also record [measuredIntegratedLufs] separately so measured loudness
 *   and estimated gain remain explicitly distinguished.
 */
data class ReplayGainInfo(
    val trackGainDb: Float? = null,
    val albumGainDb: Float? = null,
    val trackPeak: Float? = null,
    val albumPeak: Float? = null,
    val source: String = "None",
    val measuredIntegratedLufs: Float? = null,
    val targetLoudnessLufs: Float? = null
) {
    val hasAnyGain: Boolean
        get() = (trackGainDb != null && trackGainDb.isFinite()) ||
            (albumGainDb != null && albumGainDb.isFinite())

    val isScannedEstimate: Boolean
        get() = measuredIntegratedLufs != null
}

enum class GainApplicationPath(val label: String) {
    BYPASS("Bypass (unity 0 dB)"),
    DSP_PCM_PROCESSOR("Media3 PCM AudioProcessor"),
    PLAYER_VOLUME_FALLBACK("Player volume attenuation fallback")
}

data class ReplayGainResult(
    val appliedGainDb: Float,
    val linearGain: Float,
    val usedAlbumGain: Boolean,
    val usedTrackGain: Boolean,
    val usedFallback: Boolean,
    val peakLimited: Boolean,
    val reason: String
)

enum class BitrateMode(val label: String) {
    CBR("CBR"),
    VBR("VBR"),
    UNKNOWN("Unknown")
}

/**
 * Source-file audio inspection metadata.
 * Unknown properties remain null rather than fabricated.
 * Lossy codecs keep [bitDepth] as null so decoder PCM width is never misrepresented as source bit depth.
 */
data class AudioQualityInfo(
    val uri: String,
    val codec: String? = null,
    val codecProfile: String? = null,
    val container: String? = null,
    val bitrate: Long? = null,
    val bitrateMode: BitrateMode = BitrateMode.UNKNOWN,
    val sampleRate: Int? = null,
    val channels: Int? = null,
    val channelLayout: String? = null,
    val bitDepth: Int? = null,
    val sampleFormat: String? = null,
    val durationMs: Long? = null,
    val fileSizeBytes: Long? = null,
    val isLossless: Boolean? = null,
    val losslessCaveat: String? = null,
    val fileLocation: String? = null,
    val hasEmbeddedArtwork: Boolean? = null,
    val artworkDimensions: String? = null,
    val replayGain: ReplayGainInfo? = null,
    val hasLyrics: Boolean? = null,
    val lyricsSourceLabel: String? = null,
    val inspectionSource: String = "Unknown",
    val inspectedAtMs: Long = System.currentTimeMillis()
) {
    val bitrateKbps: Int?
        get() = bitrate?.takeIf { it > 0L }?.let {
            if (it >= 1000L) (it / 1000L).toInt() else it.toInt()
        }

    val formattedSampleRate: String?
        get() = sampleRate?.takeIf { it > 0 }?.let { hz ->
            if (hz % 1000 == 0) "${hz / 1000} kHz"
            else String.format(Locale.US, "%.1f kHz", hz / 1000.0)
        }

    val formattedChannels: String?
        get() = when {
            !channelLayout.isNullOrBlank() && channels != null -> "$channels ($channelLayout)"
            channels == 1 -> "1 (Mono)"
            channels == 2 -> "2 (Stereo)"
            channels != null && channels > 0 -> "$channels ch"
            else -> null
        }

    val losslessLabel: String
        get() = when (isLossless) {
            true -> "Lossless codec"
            false -> "Lossy codec"
            null -> "Unknown"
        }
}

/**
 * Live playback output snapshot separated strictly from source-file properties.
 * Reports only observable Android AudioManager / Media3 state without claiming bit-perfect output or DAC capabilities.
 */
data class PlaybackOutputSnapshot(
    val outputDeviceName: String? = null,
    val outputDeviceType: String? = null,
    val reportedCodec: String? = null,
    val reportedSampleRateHz: Int? = null,
    val reportedChannelCount: Int? = null,
    val reportedBitrateKbps: Int? = null,
    val audioOffloadStatus: String = "Standard PCM AudioTrack",
    val gainApplicationPath: GainApplicationPath = GainApplicationPath.BYPASS,
    val activeReplayGainDb: Float = 0f,
    val activeSleepFadeFactor: Float = 1f,
    val effectiveLinearGain: Float = 1f,
    val replayGainReason: String = "Off"
)

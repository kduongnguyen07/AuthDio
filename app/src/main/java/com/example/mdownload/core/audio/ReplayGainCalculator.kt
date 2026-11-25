package com.example.mdownload.core.audio

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

/**
 * Pure, deterministic ReplayGain & volume normalization gain computation.
 *
 * Formula: linearGain = 10^(gainDb / 20)
 *
 * Important technical honesty:
 * - Peak protection uses sample peak metadata recorded in file tags (or scan results) to keep
 *   `linearGain * peak <= 1.0`. It is sample-peak headroom limiting, not guaranteed inter-sample
 *   true-peak limiting.
 */
object ReplayGainCalculator {

    private const val MAX_REASONABLE_TAG_GAIN_DB = 40.0f
    private const val MIN_REASONABLE_TAG_GAIN_DB = -40.0f

    fun dbToLinear(gainDb: Float): Float {
        if (!gainDb.isFinite()) return 1.0f
        if (abs(gainDb) < 0.0001f) return 1.0f
        return 10.0.pow(gainDb.toDouble() / 20.0).toFloat()
    }

    fun linearToDb(linear: Float): Float {
        if (!linear.isFinite() || linear <= 0f) return -96.0f
        return (20.0 * ln(linear.toDouble()) / ln(10.0)).toFloat()
    }

    fun sanitizeGainDb(value: Float?): Float? {
        if (value == null || !value.isFinite()) return null
        if (value < MIN_REASONABLE_TAG_GAIN_DB || value > MAX_REASONABLE_TAG_GAIN_DB) return null
        return value
    }

    fun sanitizePeak(value: Float?): Float? {
        if (value == null || !value.isFinite()) return null
        if (value <= 0f || value > 4.0f) return null
        return value
    }

    /**
     * Computes target ReplayGain adjustment for a track under [config].
     *
     * Mode selection:
     * - [ReplayGainMode.OFF]: unity (0 dB, 1.0x).
     * - [ReplayGainMode.TRACK]: uses track gain (+ preamp); falls back to missing-metadata policy if absent.
     * - [ReplayGainMode.ALBUM]: uses album gain (+ preamp); falls back cleanly to track gain if album gain is
     *   absent, and to missing-metadata policy if neither is present.
     */
    fun compute(
        info: ReplayGainInfo?,
        config: ReplayGainConfig
    ): ReplayGainResult {
        if (config.mode == ReplayGainMode.OFF) {
            return ReplayGainResult(
                appliedGainDb = 0f,
                linearGain = 1f,
                usedAlbumGain = false,
                usedTrackGain = false,
                usedFallback = false,
                peakLimited = false,
                reason = "Off"
            )
        }

        val trackGain = sanitizeGainDb(info?.trackGainDb)
        val albumGain = sanitizeGainDb(info?.albumGainDb)
        val trackPeak = sanitizePeak(info?.trackPeak)
        val albumPeak = sanitizePeak(info?.albumPeak)

        val safePreamp = sanitizeGainDb(config.preampDb)?.coerceIn(-12f, 12f) ?: 0f
        val safeFallbackPreamp = sanitizeGainDb(config.fallbackPreampDb)?.coerceIn(-12f, 6f) ?: 0f
        val safeMinDb = config.minGainDb.takeIf { it.isFinite() }?.coerceAtLeast(-36f) ?: -24f
        val safeMaxDb = config.maxGainDb.takeIf { it.isFinite() }?.coerceAtMost(18f)?.coerceAtLeast(safeMinDb) ?: 12f

        val rawGainDb: Float
        val selectedPeak: Float?
        val usedAlbum: Boolean
        val usedTrack: Boolean
        val usedFallback: Boolean
        val baseReason: String

        when (config.mode) {
            ReplayGainMode.TRACK -> {
                if (trackGain != null) {
                    rawGainDb = trackGain + safePreamp
                    selectedPeak = trackPeak ?: albumPeak
                    usedAlbum = false
                    usedTrack = true
                    usedFallback = false
                    baseReason = "Track gain (${formatDb(trackGain)})"
                } else {
                    rawGainDb = missingGainDb(config.missingBehavior, safeFallbackPreamp)
                    selectedPeak = trackPeak
                    usedAlbum = false
                    usedTrack = false
                    usedFallback = true
                    baseReason = missingReason(config.missingBehavior, rawGainDb)
                }
            }

            ReplayGainMode.ALBUM -> {
                when {
                    albumGain != null -> {
                        rawGainDb = albumGain + safePreamp
                        selectedPeak = albumPeak ?: trackPeak
                        usedAlbum = true
                        usedTrack = false
                        usedFallback = false
                        baseReason = "Album gain (${formatDb(albumGain)})"
                    }
                    trackGain != null -> {
                        // Documented fallback: when album gain is unavailable, fall back to track gain.
                        rawGainDb = trackGain + safePreamp
                        selectedPeak = trackPeak
                        usedAlbum = false
                        usedTrack = true
                        usedFallback = false
                        baseReason = "Track gain fallback (${formatDb(trackGain)})"
                    }
                    else -> {
                        rawGainDb = missingGainDb(config.missingBehavior, safeFallbackPreamp)
                        selectedPeak = null
                        usedAlbum = false
                        usedTrack = false
                        usedFallback = true
                        baseReason = missingReason(config.missingBehavior, rawGainDb)
                    }
                }
            }

            ReplayGainMode.OFF -> {
                return ReplayGainResult(0f, 1f, false, false, false, false, "Off")
            }
        }

        val clampedDb = rawGainDb.coerceIn(safeMinDb, safeMaxDb)
        var linear = dbToLinear(clampedDb)
        var peakLimited = false

        if (config.preventClipping && selectedPeak != null && selectedPeak > 0f) {
            val maxSafeLinear = 1.0f / selectedPeak
            if (linear > maxSafeLinear) {
                linear = maxSafeLinear
                peakLimited = true
            }
        }

        val finalLinear = linear.takeIf { it.isFinite() && it >= 0f } ?: 1f
        val finalDb = if (abs(finalLinear - 1f) < 0.0001f) 0f else linearToDb(finalLinear)
        val reason = if (peakLimited) "$baseReason · peak limited" else baseReason

        return ReplayGainResult(
            appliedGainDb = finalDb,
            linearGain = finalLinear,
            usedAlbumGain = usedAlbum,
            usedTrackGain = usedTrack,
            usedFallback = usedFallback,
            peakLimited = peakLimited,
            reason = reason
        )
    }

    /**
     * Composes ReplayGain linear factor with Sleep Timer fade factor deterministically.
     */
    fun composeEffectiveGain(replayGainLinear: Float, sleepFadeFactor: Float): Float {
        val rg = replayGainLinear.takeIf { it.isFinite() && it >= 0f } ?: 1f
        val fade = sleepFadeFactor.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 1f
        val product = rg * fade
        return if (product.isFinite() && product >= 0f) product else 0f
    }

    fun formatDb(db: Float): String {
        if (!db.isFinite()) return "0.0 dB"
        val sign = if (db > 0.05f) "+" else ""
        return "$sign${String.format(java.util.Locale.US, "%.1f", db)} dB"
    }

    private fun missingGainDb(behavior: MissingReplayGainBehavior, fallbackPreampDb: Float): Float =
        when (behavior) {
            MissingReplayGainBehavior.LEAVE_UNCHANGED -> 0f
            MissingReplayGainBehavior.APPLY_FALLBACK_PREAMP -> fallbackPreampDb
        }

    private fun missingReason(behavior: MissingReplayGainBehavior, gainDb: Float): String =
        when (behavior) {
            MissingReplayGainBehavior.LEAVE_UNCHANGED -> "No tags · unchanged"
            MissingReplayGainBehavior.APPLY_FALLBACK_PREAMP -> "No tags · fallback (${formatDb(gainDb)})"
        }
}

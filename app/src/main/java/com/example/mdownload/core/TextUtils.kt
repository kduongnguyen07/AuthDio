package com.example.mdownload.core

import java.text.Normalizer
import java.util.Locale

/**
 * Title cleanup, smart metadata helpers, Vietnamese-safe ASCII transliteration,
 * metadata sanitization, and formatting helpers.
 */
object TextUtils {

    /**
     * Regex used both in Kotlin and passed to yt-dlp `--replace-in-metadata` to strip common
     * video-only suffixes like Official MV, Official Music Video, Lyrics Video, Audio, 4K, HD, etc.
     */
    const val TITLE_CLEANUP_PATTERN =
        """(?i)\s*[\(\[{【]\s*(?:official(?:\s+(?:music|lyric|lyrics|audio|visualizer|hd|4k|uhd))*\s*(?:video|audio|mv|m/v|visualizer|lyrics?)?|lyrics?(?:\s+video)?|official\s+m/?v|music\s+video|m/v|mv|audio(?:\s+only)?|visualizer|4k(?:\s+uhd)?|full\s*hd|hd|hq|1080p|60fps|color\s+coded(?:\s+lyrics?)?|vietsub(?:\s*\+\s*engsub)?|engsub)\s*[\)\]}】]"""

    private val TITLE_CLEANUP_REGEX = Regex(TITLE_CLEANUP_PATTERN)
    private val TRAILING_NOISE_REGEX = Regex(
        """(?i)(?:\s*[|•·–—-]\s*(?:official(?:\s+(?:music|lyric|lyrics|audio))*\s*(?:video|audio|mv|m/v|visualizer)|lyrics?(?:\s+video)?|music\s+video|full\s*hd|4k|vietsub|engsub)|\s+(?:official\s+)?(?:m/v|mv|official\s+music\s+video|official\s+video|official\s+audio|lyrics?\s+video))\s*$"""
    )
    private val TOPIC_SUFFIX_REGEX = Regex("""(?i)\s*-\s*Topic\s*$""")
    private val YEAR_REGEX = Regex("""\b(19\d{2}|20\d{2})\b""")
    private val GOOGLE_THUMB_SIZE_REGEX = Regex("""=w\d+-h\d+[^&\s]*""")
    private val CONTROL_CHARS_REGEX = Regex("""[\x00-\x1F\x7F]+""")
    private val DIACRITICS_REGEX = Regex("""\p{InCombiningDiacriticalMarks}+""")
    private val UNSAFE_FILENAME_CHARS = Regex("""[\\/:*?"<>|]+""")
    private val MULTI_SPACE_REGEX = Regex("""\s+""")

    fun cleanSourceTitle(title: String): String {
        var current = title.trim()
        var previous: String
        // Iteratively peel stacked suffixes, e.g. "Song (Official MV) [4K]" -> "Song"
        do {
            previous = current
            current = TITLE_CLEANUP_REGEX.replace(current, " ").trim()
            current = TRAILING_NOISE_REGEX.replace(current, "").trim()
            current = current.trimEnd('-', '|', '–', '—', '·', '•').trim()
        } while (current != previous && current.isNotBlank())
        return MULTI_SPACE_REGEX.replace(current, " ").ifBlank { title.trim() }
    }

    fun cleanArtistName(rawArtist: String): String {
        val cleaned = TOPIC_SUFFIX_REGEX.replace(rawArtist.trim(), "").trim()
        return MULTI_SPACE_REGEX.replace(cleaned, " ")
    }

    fun extractYear(vararg candidates: String?): String {
        for (raw in candidates) {
            if (raw.isNullOrBlank()) continue
            val trimmed = raw.trim()
            if (trimmed.length >= 4 && trimmed.substring(0, 4).all(Char::isDigit)) {
                val yr = trimmed.substring(0, 4).toIntOrNull()
                if (yr != null && yr in 1900..2099) return yr.toString()
            }
            YEAR_REGEX.find(trimmed)?.groupValues?.getOrNull(1)?.let { return it }
        }
        return ""
    }

    /**
     * Upgrades YouTube / YouTube Music thumbnails to high-resolution artwork URLs when available.
     */
    fun upgradeThumbnailUrl(url: String, videoId: String? = null): String {
        val trimmed = url.trim()
        if (trimmed.contains("googleusercontent.com", ignoreCase = true) &&
            GOOGLE_THUMB_SIZE_REGEX.containsMatchIn(trimmed)
        ) {
            return GOOGLE_THUMB_SIZE_REGEX.replace(trimmed, "=w1200-h1200-l90-rj")
        }
        if (trimmed.contains("i.ytimg.com/vi/", ignoreCase = true)) {
            return trimmed
                .replace("/mqdefault.jpg", "/hqdefault.jpg")
                .replace("/default.jpg", "/hqdefault.jpg")
        }
        if (trimmed.isBlank() && !videoId.isNullOrBlank()) {
            return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
        return trimmed
    }

    fun asciiText(input: String): String {
        var text = input
            .replace("đ", "d")
            .replace("Đ", "D")
            .replace("–", "-")
            .replace("—", "-")
            .replace("’", "'")
            .replace("‘", "'")
            .replace("“", "\"")
            .replace("”", "\"")
            .replace("…", "...")
        text = Normalizer.normalize(text, Normalizer.Form.NFKD)
        text = DIACRITICS_REGEX.replace(text, "")
        return buildString(text.length) {
            for (ch in text) {
                if (ch.code in 32..126) append(ch)
            }
        }.trim()
    }

    fun sanitizeFileName(raw: String, asciiOnly: Boolean): String {
        val base = if (asciiOnly) asciiText(raw) else raw
        val cleaned = UNSAFE_FILENAME_CHARS.replace(base, " ")
        val collapsed = MULTI_SPACE_REGEX.replace(cleaned, " ").trim().trim('.')
        return collapsed.take(120).ifBlank { "Track" }
    }

    fun cleanMetadata(metadata: TrackMetadata): TrackMetadata = TrackMetadata(
        title = sanitizeMetaField(metadata.title),
        artist = sanitizeMetaField(metadata.artist),
        album = sanitizeMetaField(metadata.album),
        year = extractYear(metadata.year),
        customArtworkUri = metadata.customArtworkUri.trim()
    )

    private fun sanitizeMetaField(raw: String): String =
        CONTROL_CHARS_REGEX.replace(raw, " ").trim().take(200)

    fun formatLabel(options: JobOptions): String =
        if (options.audioFormat == AudioFormat.ORIGINAL) {
            "Original audio"
        } else {
            "${options.audioFormat.displayName} ${options.audioQuality} kbps"
        }

    fun profileSuffix(options: JobOptions): String {
        val norm = options.normalized()
        if (norm.audioFormat == AudioFormat.MP3 && norm.audioQuality == 128) return ""
        return if (norm.audioFormat == AudioFormat.ORIGINAL) {
            " - Original"
        } else {
            " - ${norm.audioFormat.displayName} ${norm.audioQuality}k"
        }
    }

    fun archiveFileName(options: JobOptions): String {
        val norm = options.normalized()
        if (norm.audioFormat == AudioFormat.MP3 && norm.audioQuality == 128) {
            return ".hifideck-archive.txt"
        }
        return if (norm.audioFormat == AudioFormat.ORIGINAL) {
            ".hifideck-archive-original.txt"
        } else {
            ".hifideck-archive-${norm.audioFormat.id}-${norm.audioQuality}.txt"
        }
    }

    fun buildExportFileName(
        title: String,
        videoId: String?,
        extension: String,
        options: JobOptions
    ): String {
        val cleanedTitle = cleanSourceTitle(title)
        val safeTitle = sanitizeFileName(cleanedTitle, options.asciiNames)
        val suffix = profileSuffix(options)
        val idPart = if (!videoId.isNullOrBlank()) " [$videoId]" else ""
        val ext = extension.trimStart('.').lowercase(Locale.US).ifBlank {
            if (options.audioFormat == AudioFormat.ORIGINAL) "m4a" else options.audioFormat.id
        }
        return "$safeTitle$suffix$idPart.$ext"
    }

    fun formatSpeed(bytesPerSec: Double): String {
        if (!bytesPerSec.isFinite() || bytesPerSec <= 0.0) return "0 B/s"
        val units = arrayOf("B/s", "KB/s", "MB/s", "GB/s")
        var value = bytesPerSec
        var unitIndex = 0
        while (value >= 1024.0 && unitIndex < units.lastIndex) {
            value /= 1024.0
            unitIndex++
        }
        return if (unitIndex == 0) {
            "${value.toInt()} ${units[unitIndex]}"
        } else {
            String.format(Locale.US, "%.1f %s", value, units[unitIndex])
        }
    }

    fun formatDurationSeconds(seconds: Int?): String {
        if (seconds == null || seconds < 0) return "—"
        val hours = seconds / 3600
        val mins = (seconds % 3600) / 60
        val secs = seconds % 60
        return if (hours > 0) {
            String.format(Locale.US, "%d:%02d:%02d", hours, mins, secs)
        } else {
            String.format(Locale.US, "%d:%02d", mins, secs)
        }
    }

    fun formatDurationMs(ms: Long): String {
        if (ms <= 0L) return "0:00"
        return formatDurationSeconds((ms / 1000L).toInt())
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0L) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB")
        var value = bytes.toDouble()
        var idx = 0
        while (value >= 1024.0 && idx < units.lastIndex) {
            value /= 1024.0
            idx++
        }
        return if (idx == 0) "${value.toInt()} B" else String.format(Locale.US, "%.1f %s", value, units[idx])
    }
}

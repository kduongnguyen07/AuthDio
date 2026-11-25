package com.example.mdownload.player.lyrics

import com.example.mdownload.core.LibraryTrack
import java.util.Locale

/**
 * Single line of lyrics.
 * - Synchronized lyrics have non-null [timestampMs] (`>= 0L`).
 * - Plain unsynchronized lyrics have `timestampMs == null`.
 * - Empty [text] with a non-null [timestampMs] marks an explicit instrumental pause/break in LRC.
 */
data class LyricLine(
    val timestampMs: Long?,
    val text: String
) {
    val isInstrumentalBreak: Boolean
        get() = timestampMs != null && text.isBlank()
}

enum class LyricsSource(val displayName: String) {
    EMBEDDED("Embedded tag"),
    SIDECAR("Sidecar .lrc"),
    CACHE("Local cache"),
    ONLINE_LRCLIB("LRCLIB"),
    MANUAL("Manual"),
    NONE("None");

    companion object {
        fun fromName(name: String?): LyricsSource =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: NONE
    }
}

data class TrackLyrics(
    val trackKey: String,
    val lines: List<LyricLine>,
    val rawLrc: String? = null,
    val plainText: String = "",
    val isSynced: Boolean = false,
    val source: LyricsSource = LyricsSource.NONE,
    val offsetMs: Long = 0L,
    val providerName: String = "",
    val matchedTitle: String = "",
    val matchedArtist: String = "",
    val confidence: Float = 1f,
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    val hasContent: Boolean
        get() = lines.any { it.text.isNotBlank() }

    val displayPlainText: String
        get() = plainText.ifBlank {
            lines.mapNotNull { it.text.takeIf(String::isNotBlank) }.joinToString("\n")
        }

    fun toExportableLrc(): String {
        if (isSynced) {
            return LyricsParser.serializeLrc(
                lines = lines,
                offsetMs = offsetMs,
                title = matchedTitle,
                artist = matchedArtist
            )
        }
        return rawLrc?.takeIf { it.isNotBlank() } ?: displayPlainText
    }
}

data class LyricsCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val durationSec: Int? = null,
    val syncedLyrics: String? = null,
    val plainLyrics: String? = null,
    val instrumental: Boolean = false,
    val providerName: String = "LRCLIB",
    val confidence: Float = 0f
) {
    val isSynced: Boolean
        get() = !syncedLyrics.isNullOrBlank()

    val hasAnyLyrics: Boolean
        get() = !syncedLyrics.isNullOrBlank() || !plainLyrics.isNullOrBlank()
}

data class LyricsUiState(
    val activeTrack: LibraryTrack? = null,
    val lyrics: TrackLyrics? = null,
    val isLoading: Boolean = false,
    val isSearchingOnline: Boolean = false,
    val isEmbedding: Boolean = false,
    val candidates: List<LyricsCandidate> = emptyList(),
    val statusMessage: String? = null,
    val errorMessage: String? = null,
    val canEmbedIntoFile: Boolean = false
)

data class ParsedLyricsContent(
    val lines: List<LyricLine>,
    val isSynced: Boolean,
    val offsetMs: Long,
    val plainText: String,
    val rawLrc: String?,
    val headerTitle: String = "",
    val headerArtist: String = ""
)

/**
 * Resilient UTF-8 / Vietnamese-safe LRC & plain lyrics parser and serializer.
 *
 * Handles:
 * - `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]`, `[mm:ss]`
 * - Multiple timestamps on one line: `[00:12.00][00:45.50]Chorus`
 * - Header metadata tags (`[offset:±ms]`, `[ti:]`, `[ar:]`, `[al:]`, `[by:]`, `[re:]`, `[ve:]`, `[length:]`)
 * - Malformed timestamps safely ignored instead of throwing
 * - Duplicate timestamps sorted stably in source order
 * - Full UTF-8 & Vietnamese diacritics preservation
 * - Plain text fallback when no valid timestamps exist
 */
object LyricsParser {

    // Matches [mm:ss], [mm:ss.xx], [mm:ss.xxx], [mm:ss:xx]
    private val TIMESTAMP_REGEX = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    // Matches standard LRC metadata tags like [ti:Title], [ar:Artist], [offset:-250]
    private val META_TAG_REGEX = Regex("""^\[(ti|ar|al|au|by|re|ve|length|offset):([^\]]*)]$""", RegexOption.IGNORE_CASE)

    // Matches malformed bracketed time-like prefixes like [xx:yy] or [12:99.abc] so we ignore bad timestamps cleanly
    private val MALFORMED_TIME_PREFIX_REGEX = Regex("""^\[(\d+:\d+[^\]]*|[^\]]*:[^\]]*)]""")

    fun parse(rawInput: String): ParsedLyricsContent {
        val cleaned = rawInput
            .removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        if (cleaned.isBlank()) {
            return ParsedLyricsContent(
                lines = emptyList(),
                isSynced = false,
                offsetMs = 0L,
                plainText = "",
                rawLrc = null
            )
        }

        var headerOffsetMs = 0L
        var headerTitle = ""
        var headerArtist = ""

        data class IndexedSyncedLine(val timestampMs: Long, val order: Int, val text: String)
        val syncedEntries = mutableListOf<IndexedSyncedLine>()
        val plainLines = mutableListOf<String>()
        var seq = 0

        for (rawLine in cleaned.split('\n')) {
            val trimmed = rawLine.trim()
            if (trimmed.isEmpty()) continue

            val metaMatch = META_TAG_REGEX.matchEntire(trimmed)
            if (metaMatch != null) {
                val key = metaMatch.groupValues[1].lowercase(Locale.US)
                val value = metaMatch.groupValues[2].trim()
                when (key) {
                    "offset" -> headerOffsetMs = value.toLongOrNull() ?: headerOffsetMs
                    "ti" -> if (value.isNotBlank()) headerTitle = value
                    "ar" -> if (value.isNotBlank()) headerArtist = value
                }
                continue
            }

            // Collect leading/inline valid LRC timestamps on this line
            val timestamps = mutableListOf<Long>()
            val matches = TIMESTAMP_REGEX.findAll(trimmed).toList()
            for (m in matches) {
                val mins = m.groupValues[1].toLongOrNull() ?: continue
                val secs = m.groupValues[2].toLongOrNull() ?: continue
                if (secs >= 60L) continue
                val fracRaw = m.groupValues[3]
                val millis = when (fracRaw.length) {
                    0 -> 0L
                    1 -> (fracRaw.toLongOrNull() ?: 0L) * 100L
                    2 -> (fracRaw.toLongOrNull() ?: 0L) * 10L
                    else -> fracRaw.take(3).toLongOrNull() ?: 0L
                }
                timestamps.add(mins * 60_000L + secs * 1_000L + millis)
            }

            if (timestamps.isNotEmpty()) {
                val lyricText = TIMESTAMP_REGEX.replace(trimmed, "").trim()
                for (ts in timestamps) {
                    syncedEntries.add(IndexedSyncedLine(ts, seq++, lyricText))
                }
            } else {
                // Ignore lines that are solely malformed timestamp tokens like [99:99.xx]
                val strippedMalformed = MALFORMED_TIME_PREFIX_REGEX.replace(trimmed, "").trim()
                if (strippedMalformed.isNotEmpty()) {
                    plainLines.add(strippedMalformed)
                }
            }
        }

        if (syncedEntries.isNotEmpty()) {
            val sorted = syncedEntries
                .sortedWith(compareBy<IndexedSyncedLine> { it.timestampMs }.thenBy { it.order })
                .map { LyricLine(timestampMs = it.timestampMs, text = it.text) }

            val derivedPlain = sorted
                .mapNotNull { it.text.takeIf(String::isNotBlank) }
                .joinToString("\n")

            return ParsedLyricsContent(
                lines = sorted,
                isSynced = true,
                offsetMs = headerOffsetMs,
                plainText = derivedPlain,
                rawLrc = cleaned.trim(),
                headerTitle = headerTitle,
                headerArtist = headerArtist
            )
        }

        // Plain unsynchronized lyrics fallback
        val unsyncedLines = plainLines.map { LyricLine(timestampMs = null, text = it) }
        return ParsedLyricsContent(
            lines = unsyncedLines,
            isSynced = false,
            offsetMs = 0L,
            plainText = plainLines.joinToString("\n"),
            rawLrc = null,
            headerTitle = headerTitle,
            headerArtist = headerArtist
        )
    }

    fun toTrackLyrics(
        trackKey: String,
        rawInput: String,
        source: LyricsSource,
        providerName: String = "",
        matchedTitle: String = "",
        matchedArtist: String = "",
        confidence: Float = 1f,
        offsetMsOverride: Long? = null
    ): TrackLyrics? {
        val parsed = parse(rawInput)
        if (parsed.lines.isEmpty()) return null
        return TrackLyrics(
            trackKey = trackKey,
            lines = parsed.lines,
            rawLrc = parsed.rawLrc,
            plainText = parsed.plainText,
            isSynced = parsed.isSynced,
            source = source,
            offsetMs = offsetMsOverride ?: parsed.offsetMs,
            providerName = providerName,
            matchedTitle = matchedTitle.ifBlank { parsed.headerTitle },
            matchedArtist = matchedArtist.ifBlank { parsed.headerArtist },
            confidence = confidence
        )
    }

    /** Formats a millisecond timestamp as `[mm:ss.xx]`. */
    fun formatTimestamp(timestampMs: Long): String {
        val safe = timestampMs.coerceAtLeast(0L)
        val totalSeconds = safe / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val centis = (safe % 1000L) / 10L
        return String.format(Locale.US, "[%02d:%02d.%02d]", minutes, seconds, centis)
    }

    /** Serializes synchronized [lines] into a clean UTF-8 `.lrc` document. */
    fun serializeLrc(
        lines: List<LyricLine>,
        offsetMs: Long = 0L,
        title: String = "",
        artist: String = ""
    ): String = buildString {
        if (title.isNotBlank()) appendLine("[ti:$title]")
        if (artist.isNotBlank()) appendLine("[ar:$artist]")
        if (offsetMs != 0L) appendLine("[offset:$offsetMs]")
        for (line in lines) {
            val ts = line.timestampMs
            if (ts != null) {
                appendLine("${formatTimestamp(ts)}${line.text}")
            } else if (line.text.isNotBlank()) {
                appendLine(line.text)
            }
        }
    }.trimEnd()
}

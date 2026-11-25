package com.example.mdownload.player.lyrics

import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class LyricsQuery(
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationSec: Int? = null
)

data class LyricsMatchDecision(
    val bestAutoMatch: LyricsCandidate?,
    val rankedCandidates: List<LyricsCandidate>
)

/**
 * Confidence-scored metadata normalizer & lyrics candidate matcher.
 *
 * - Strips common YouTube/upload noise (`Official MV`, `Lyrics Video`, `Audio`, `4K`, `Visualizer`).
 * - Handles both accented Vietnamese UTF-8 and diacritic-folded comparisons.
 * - Uses title similarity, artist similarity, duration proximity, and optional album match.
 * - Auto-attaches ONLY high-confidence matches ([HIGH_CONFIDENCE_THRESHOLD]); surfaces plausible
 *   matches ([PLAUSIBLE_THRESHOLD]) for manual selection without misattaching wrong lyrics.
 */
object LyricsMatcher {

    const val HIGH_CONFIDENCE_THRESHOLD = 0.72f
    const val PLAUSIBLE_THRESHOLD = 0.42f

    private val BRACKET_NOISE = Regex(
        """\s*[\(\[【（]\s*(?:official\s*)?(?:music\s*video|m/v|mv|video|lyric(?:s)?(?:\s*video)?|audio|hd|4k|uhd|hq|visualizer|live(?:\s*version)?|sped\s*up|slowed(?:\s*\+\s*reverb)?|topic|remastered(?:\s*\d{4})?|full\s*audio)\s*[\)\]】）]""",
        RegexOption.IGNORE_CASE
    )
    private val TRAILING_NOISE = Regex(
        """\s*[-|–—]\s*(?:official\s*)?(?:music\s*video|m/v|mv|lyric(?:s)?(?:\s*video)?|audio|visualizer|hd|4k)\s*$""",
        RegexOption.IGNORE_CASE
    )
    private val FEAT_SPLIT = Regex("""\s+(?:feat\.?|ft\.?|featuring)\s+.*$""", RegexOption.IGNORE_CASE)
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}\s]+""")
    private val MULTI_SPACE = Regex("""\s+""")
    private val DIACRITICS = Regex("""\p{InCombiningDiacriticalMarks}+""")

    /** Strips release/video noise while keeping clean Unicode/Vietnamese display text. */
    fun cleanDisplayTitle(raw: String): String {
        var s = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        s = BRACKET_NOISE.replace(s, "")
        s = TRAILING_NOISE.replace(s, "")
        s = MULTI_SPACE.replace(s, " ").trim()
        return s.ifBlank { raw.trim() }
    }

    /** Cleans channel suffixes like `" - Topic"` or `"VEVO"` from artist metadata. */
    fun cleanDisplayArtist(raw: String): String {
        val trimmed = Normalizer.normalize(raw, Normalizer.Form.NFC).trim()
        if (trimmed.equals("Unknown artist", ignoreCase = true) ||
            trimmed.equals("<unknown>", ignoreCase = true)
        ) {
            return ""
        }
        return trimmed
            .replace(Regex("""\s*-\s*Topic$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\s+Official$""", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    /**
     * Splits `"Artist - Title"` YouTube titles into `(cleanedTitle, inferredArtist)` when the
     * explicit artist field is empty or generic.
     */
    fun buildQuery(
        rawTitle: String,
        rawArtist: String,
        rawAlbum: String = "",
        durationMs: Long = 0L
    ): LyricsQuery {
        var title = cleanDisplayTitle(rawTitle)
        var artist = cleanDisplayArtist(rawArtist)
        if (artist.isBlank() && " - " in title) {
            val parts = title.split(" - ", limit = 2)
            if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                artist = cleanDisplayArtist(parts[0])
                title = cleanDisplayTitle(parts[1])
            }
        }
        val durationSec = if (durationMs > 0L) (durationMs / 1000L).toInt() else null
        return LyricsQuery(
            title = title,
            artist = artist,
            album = cleanDisplayTitle(rawAlbum),
            durationSec = durationSec
        )
    }

    fun normalizeForMatch(raw: String, stripFeat: Boolean = false): String {
        var s = cleanDisplayTitle(raw)
        if (stripFeat) {
            s = FEAT_SPLIT.replace(s, "")
        }
        s = Normalizer.normalize(s, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        s = NON_ALNUM.replace(s, " ")
        return MULTI_SPACE.replace(s, " ").trim()
    }

    fun foldAscii(raw: String): String {
        val replaced = raw
            .replace('đ', 'd')
            .replace('Đ', 'd')
        val decomposed = Normalizer.normalize(replaced, Normalizer.Form.NFD)
        return DIACRITICS.replace(decomposed, "").lowercase(Locale.ROOT)
    }

    /** Scores candidate fit against [query] in `0f..1f`. */
    fun scoreCandidate(query: LyricsQuery, candidate: LyricsCandidate): Float {
        if (!candidate.hasAnyLyrics) return 0f

        val titleSim = bestTextSimilarity(query.title, candidate.title, stripFeat = true)
        if (titleSim < 0.32f) return (titleSim * 0.5f).coerceIn(0f, 1f)

        val hasQueryArtist = query.artist.isNotBlank()
        val artistSim = if (hasQueryArtist && candidate.artist.isNotBlank()) {
            bestTextSimilarity(query.artist, candidate.artist, stripFeat = true)
        } else if (!hasQueryArtist) {
            0.55f
        } else {
            0.25f
        }

        val durationScore = scoreDuration(query.durationSec, candidate.durationSec)
        val albumBonus = if (query.album.isNotBlank() && candidate.album.isNotBlank()) {
            bestTextSimilarity(query.album, candidate.album, stripFeat = false) * 0.08f
        } else {
            0f
        }
        val syncedBonus = if (candidate.isSynced) 0.04f else 0f

        val weighted = if (hasQueryArtist) {
            titleSim * 0.50f + artistSim * 0.28f + durationScore * 0.16f + albumBonus + syncedBonus
        } else {
            titleSim * 0.68f + durationScore * 0.22f + albumBonus + syncedBonus
        }

        // Strongly penalize huge duration mismatches (> 30s difference) to avoid wrong remix/live matches
        val durationPenalty = if (query.durationSec != null && candidate.durationSec != null) {
            val diff = abs(query.durationSec - candidate.durationSec)
            when {
                diff > 45 -> 0.28f
                diff > 25 -> 0.14f
                else -> 0f
            }
        } else 0f

        return (weighted - durationPenalty).coerceIn(0f, 1f)
    }

    fun rankAndSelect(query: LyricsQuery, rawCandidates: List<LyricsCandidate>): LyricsMatchDecision {
        val scored = rawCandidates
            .filter { it.hasAnyLyrics }
            .map { cand -> cand.copy(confidence = scoreCandidate(query, cand)) }
            .filter { it.confidence >= PLAUSIBLE_THRESHOLD }
            .sortedWith(
                compareByDescending<LyricsCandidate> { it.confidence }
                    .thenByDescending { it.isSynced }
            )

        val autoMatch = scored.firstOrNull { it.confidence >= HIGH_CONFIDENCE_THRESHOLD }
        return LyricsMatchDecision(
            bestAutoMatch = autoMatch,
            rankedCandidates = scored
        )
    }

    private fun scoreDuration(querySec: Int?, candidateSec: Int?): Float {
        if (querySec == null || querySec <= 0 || candidateSec == null || candidateSec <= 0) {
            return 0.60f
        }
        val diff = abs(querySec - candidateSec)
        return when {
            diff <= 2 -> 1.0f
            diff <= 5 -> 0.90f
            diff <= 10 -> 0.72f
            diff <= 18 -> 0.45f
            diff <= 30 -> 0.20f
            else -> 0.0f
        }
    }

    private fun bestTextSimilarity(a: String, b: String, stripFeat: Boolean): Float {
        val normA = normalizeForMatch(a, stripFeat)
        val normB = normalizeForMatch(b, stripFeat)
        if (normA.isBlank() || normB.isBlank()) return 0f

        val exactScore = combinedSimilarity(normA, normB)
        val foldedScore = combinedSimilarity(foldAscii(normA), foldAscii(normB)) * 0.97f
        return maxOf(exactScore, foldedScore).coerceIn(0f, 1f)
    }

    private fun combinedSimilarity(a: String, b: String): Float {
        if (a == b) return 1.0f
        if (a.contains(b) || b.contains(a)) {
            val ratio = minOf(a.length, b.length).toFloat() / maxOf(a.length, b.length).toFloat()
            return (0.76f + 0.22f * ratio).coerceIn(0f, 0.98f)
        }
        val tokensA = a.split(' ').filter { it.isNotBlank() }.toSet()
        val tokensB = b.split(' ').filter { it.isNotBlank() }.toSet()
        val jaccard = if (tokensA.isEmpty() || tokensB.isEmpty()) {
            0f
        } else {
            val intersection = tokensA.intersect(tokensB).size.toFloat()
            val union = tokensA.union(tokensB).size.toFloat()
            intersection / union
        }
        val lev = normalizedLevenshtein(a, b)
        return (jaccard * 0.55f + lev * 0.45f).coerceIn(0f, 1f)
    }

    private fun normalizedLevenshtein(a: String, b: String): Float {
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1f
        val prev = IntArray(b.length + 1) { it }
        val curr = IntArray(b.length + 1)
        for (i in 1..a.length) {
            curr[0] = i
            val ca = a[i - 1]
            for (j in 1..b.length) {
                val cost = if (ca == b[j - 1]) 0 else 1
                curr[j] = minOf(
                    curr[j - 1] + 1,
                    prev[j] + 1,
                    prev[j - 1] + cost
                )
            }
            System.arraycopy(curr, 0, prev, 0, curr.size)
        }
        return (1f - prev[b.length].toFloat() / maxLen.toFloat()).coerceIn(0f, 1f)
    }
}

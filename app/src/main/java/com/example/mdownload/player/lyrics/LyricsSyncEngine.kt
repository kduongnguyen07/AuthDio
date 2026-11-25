package com.example.mdownload.player.lyrics

data class LyricsSyncSnapshot(
    val activeLineIndex: Int = -1,
    val isBeforeFirstLine: Boolean = false,
    val isInInstrumentalGap: Boolean = false,
    val nextLineTimestampMs: Long? = null
)

/**
 * Pure, deterministic synchronized lyrics resolver driven strictly by Media3's active playback
 * position (`positionMs`).
 *
 * - Never owns a separate drifting timer.
 * - Uses O(1) cached-index fast path for forward playback and O(log N) binary search for seeks.
 * - Correctly handles pre-first-line intro, explicit or long instrumental gaps, duplicate
 *   timestamps (advancing to the latest matching entry for that timestamp), sync offsets,
 *   pause/resume, and playback speed changes.
 */
object LyricsSyncEngine {

    /** Gaps longer than this between an active line and the next trigger a subtle instrumental cue. */
    private const val LONG_INSTRUMENTAL_GAP_MS = 18_000L
    private const val LINE_DISPLAY_TAIL_MS = 6_500L

    fun resolve(
        lyrics: TrackLyrics?,
        positionMs: Long,
        cachedIndex: Int = -1
    ): LyricsSyncSnapshot {
        if (lyrics == null || !lyrics.isSynced || lyrics.lines.isEmpty()) {
            return LyricsSyncSnapshot(activeLineIndex = -1)
        }
        return resolve(
            lines = lyrics.lines,
            positionMs = positionMs,
            offsetMs = lyrics.offsetMs,
            cachedIndex = cachedIndex
        )
    }

    fun resolve(
        lines: List<LyricLine>,
        positionMs: Long,
        offsetMs: Long = 0L,
        cachedIndex: Int = -1
    ): LyricsSyncSnapshot {
        if (lines.isEmpty()) return LyricsSyncSnapshot(activeLineIndex = -1)
        val firstTs = lines.first().timestampMs ?: return LyricsSyncSnapshot(activeLineIndex = -1)

        // Positive offset shifts lyrics earlier (appear sooner); negative delays them
        val effectivePosMs = positionMs + offsetMs

        if (effectivePosMs < firstTs) {
            return LyricsSyncSnapshot(
                activeLineIndex = -1,
                isBeforeFirstLine = true,
                isInInstrumentalGap = false,
                nextLineTimestampMs = firstTs
            )
        }

        val activeIdx = findIndexWithHint(lines, effectivePosMs, cachedIndex)
        if (activeIdx < 0) {
            return LyricsSyncSnapshot(
                activeLineIndex = -1,
                isBeforeFirstLine = true,
                nextLineTimestampMs = firstTs
            )
        }

        val currentLine = lines[activeIdx]
        val currentTs = currentLine.timestampMs ?: 0L
        val nextTs = lines.getOrNull(activeIdx + 1)?.timestampMs

        val explicitBreak = currentLine.isInstrumentalBreak
        val longGap = !explicitBreak &&
            nextTs != null &&
            (nextTs - currentTs) >= LONG_INSTRUMENTAL_GAP_MS &&
            (effectivePosMs - currentTs) >= LINE_DISPLAY_TAIL_MS

        return LyricsSyncSnapshot(
            activeLineIndex = activeIdx,
            isBeforeFirstLine = false,
            isInInstrumentalGap = explicitBreak || longGap,
            nextLineTimestampMs = nextTs
        )
    }

    /**
     * Converts a lyric line timestamp back into a Media3 seek position accounting for [offsetMs].
     */
    fun seekPositionForLine(line: LyricLine, offsetMs: Long = 0L): Long? {
        val ts = line.timestampMs ?: return null
        return (ts - offsetMs).coerceAtLeast(0L)
    }

    private fun findIndexWithHint(
        lines: List<LyricLine>,
        effectivePosMs: Long,
        hintIndex: Int
    ): Int {
        // Fast path for sequential playback: check current cached index and immediate next index
        if (hintIndex in lines.indices) {
            val hintTs = lines[hintIndex].timestampMs ?: Long.MAX_VALUE
            val nextTs = lines.getOrNull(hintIndex + 1)?.timestampMs ?: Long.MAX_VALUE
            if (effectivePosMs >= hintTs && effectivePosMs < nextTs) {
                return hintIndex
            }
            if (hintIndex + 1 < lines.size) {
                val afterNextTs = lines.getOrNull(hintIndex + 2)?.timestampMs ?: Long.MAX_VALUE
                if (effectivePosMs >= nextTs && effectivePosMs < afterNextTs) {
                    return hintIndex + 1
                }
            }
        }

        // Binary search for the rightmost index with timestampMs <= effectivePosMs
        var low = 0
        var high = lines.lastIndex
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val ts = lines[mid].timestampMs
            if (ts != null && ts <= effectivePosMs) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }
}

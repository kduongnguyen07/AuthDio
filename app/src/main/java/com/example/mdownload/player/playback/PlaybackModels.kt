package com.example.mdownload.player.playback

import androidx.media3.common.Player
import com.example.mdownload.core.LibraryTrack

enum class RepeatModeOption(val label: String, val playerRepeatMode: Int) {
    OFF("Repeat off", Player.REPEAT_MODE_OFF),
    ALL("Repeat all", Player.REPEAT_MODE_ALL),
    ONE("Repeat one", Player.REPEAT_MODE_ONE);

    fun next(): RepeatModeOption = when (this) {
        OFF -> ALL
        ALL -> ONE
        ONE -> OFF
    }

    companion object {
        fun fromPlayerMode(mode: Int): RepeatModeOption = when (mode) {
            Player.REPEAT_MODE_ONE -> ONE
            Player.REPEAT_MODE_ALL -> ALL
            else -> OFF
        }

        fun fromName(name: String?): RepeatModeOption =
            entries.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: OFF
    }
}

data class AudioOutputInfo(
    val codecLabel: String = "",
    val sampleRateHz: Int = 0,
    val channelCount: Int = 0,
    val bitrateKbps: Int = 0
) {
    val isAvailable: Boolean
        get() = codecLabel.isNotBlank() || sampleRateHz > 0 || bitrateKbps > 0

    val summaryText: String
        get() = buildList {
            if (codecLabel.isNotBlank()) add(codecLabel)
            if (bitrateKbps > 0) add("$bitrateKbps kbps")
            if (sampleRateHz > 0) {
                val khz = sampleRateHz / 1000.0
                val formatted = if (sampleRateHz % 1000 == 0) {
                    "${sampleRateHz / 1000} kHz"
                } else {
                    String.format(java.util.Locale.US, "%.1f kHz", khz)
                }
                add(formatted)
            }
            when (channelCount) {
                1 -> add("Mono")
                2 -> add("Stereo")
                in 3..16 -> add("${channelCount}ch")
            }
        }.joinToString(" · ")
}

data class PlaybackUiState(
    val currentTrack: LibraryTrack? = null,
    val queue: List<LibraryTrack> = emptyList(),
    val currentIndex: Int = -1,
    val orderedQueueIndices: List<Int> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val isSeekable: Boolean = true,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeOption = RepeatModeOption.OFF,
    val playbackSpeed: Float = 1.0f,
    val audioOutputInfo: AudioOutputInfo = AudioOutputInfo(),
    val playbackError: String? = null,
    val isServiceConnected: Boolean = false
) {
    /**
     * Effective queue order respecting Media3's active shuffle order when shuffle is enabled,
     * or linear queue order otherwise. Each entry is a valid index into [queue].
     */
    val effectiveQueueIndices: List<Int>
        get() = when {
            queue.isEmpty() -> emptyList()
            orderedQueueIndices.size == queue.size &&
                orderedQueueIndices.all { it in queue.indices } -> orderedQueueIndices
            else -> queue.indices.toList()
        }

    /** Tracks in the active playback navigation order (respecting shuffle). */
    val navigationQueue: List<LibraryTrack>
        get() = effectiveQueueIndices.map { queue[it] }

    /** Index of [currentTrack] within [navigationQueue], or -1 when queue is empty. */
    val navigationCurrentIndex: Int
        get() {
            if (queue.isEmpty() || currentIndex !in queue.indices) return -1
            val idx = effectiveQueueIndices.indexOf(currentIndex)
            return if (idx >= 0) idx else currentIndex.coerceIn(0, queue.lastIndex)
        }

    /** Maps a position in [navigationQueue] back to its real Media3 timeline index. */
    fun timelineIndexForNavigation(navIndex: Int): Int {
        val indices = effectiveQueueIndices
        if (navIndex !in indices.indices) return -1
        return indices[navIndex]
    }

    /** Upcoming `(timelineIndex, track)` pairs in actual playback order for Next Songs preview. */
    fun upcomingTracks(limit: Int = 4): List<Pair<Int, LibraryTrack>> {
        val indices = effectiveQueueIndices
        val navCur = navigationCurrentIndex
        if (indices.size <= 1 || navCur < 0) return emptyList()
        val result = mutableListOf<Pair<Int, LibraryTrack>>()
        var cursor = navCur + 1
        while (cursor < indices.size && result.size < limit) {
            val timelineIdx = indices[cursor]
            result += timelineIdx to queue[timelineIdx]
            cursor++
        }
        if (repeatMode == RepeatModeOption.ALL && result.size < limit) {
            var wrap = 0
            while (wrap < navCur && result.size < limit) {
                val timelineIdx = indices[wrap]
                result += timelineIdx to queue[timelineIdx]
                wrap++
            }
        }
        return result
    }

    val hasNext: Boolean
        get() = queue.isNotEmpty() && (
            repeatMode != RepeatModeOption.OFF ||
                shuffleEnabled ||
                (currentIndex in 0 until queue.lastIndex)
            )

    val hasPrevious: Boolean
        get() = queue.isNotEmpty() && (
            repeatMode != RepeatModeOption.OFF ||
                currentIndex > 0 ||
                positionMs > 3000L
            )
}

data class PersistedPlaybackState(
    val queue: List<LibraryTrack> = emptyList(),
    val currentIndex: Int = 0,
    val positionMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeatMode: RepeatModeOption = RepeatModeOption.OFF,
    val playbackSpeed: Float = 1.0f
)

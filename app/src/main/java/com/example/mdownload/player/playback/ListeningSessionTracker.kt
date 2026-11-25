package com.example.mdownload.player.playback

/**
 * Pure, deterministic qualified-play and real-listening-time tracker.
 *
 * Qualification rules (explicitly documented):
 * - Listening time advances ONLY while `isPlaying == true` and media position progresses naturally
 *   (deltas > [MAX_NATURAL_TICK_DELTA_MS] or negative deltas from seeks are ignored so seeking
 *   never inflates listening duration or play count).
 * - A track earns **at most 1 qualified play** per continuous session once accumulated real listening
 *   time reaches `min(30_000ms, max(5_000ms, durationMs / 2))` (or `15_000ms` if duration is unknown).
 * - Pausing and resuming the same track continues the existing session without double-counting.
 * - Switching to another track or looping via Repeat One (`startNewSession`) flushes accumulated
 *   listening time and opens a fresh session.
 * - Periodic mid-playback checkpoints fire every [periodicCommitIntervalMs] (default 30s) via
 *   [onPeriodicCommitListenedMs] (falling back to [onCommitListenedMs]) so disk persistence can be
 *   debounced while lifecycle/track-boundary flushes remain immediate and durable.
 */
class ListeningSessionTracker(
    private val onQualifiedPlay: (trackId: String, uri: String, timestampMs: Long) -> Unit,
    private val onCommitListenedMs: (trackId: String, uri: String, listenedMs: Long, timestampMs: Long) -> Unit,
    private val periodicCommitIntervalMs: Long = DEFAULT_PERIODIC_COMMIT_INTERVAL_MS,
    private val onPeriodicCommitListenedMs: ((trackId: String, uri: String, listenedMs: Long, timestampMs: Long) -> Unit)? = null
) {
    companion object {
        const val MAX_QUALIFIED_THRESHOLD_MS = 30_000L
        const val MIN_QUALIFIED_THRESHOLD_MS = 5_000L
        const val UNKNOWN_DURATION_THRESHOLD_MS = 15_000L
        const val MAX_NATURAL_TICK_DELTA_MS = 3_500L
        const val DEFAULT_PERIODIC_COMMIT_INTERVAL_MS = 30_000L
    }

    private var activeTrackId: String? = null
    private var activeUri: String? = null
    private var activeDurationMs: Long = 0L
    private var lastObservedPositionMs: Long = -1L
    private var sessionAccumulatedMs: Long = 0L
    private var uncommittedMs: Long = 0L
    private var qualifiedRecorded: Boolean = false

    fun qualificationThresholdMs(durationMs: Long): Long = when {
        durationMs <= 0L -> UNKNOWN_DURATION_THRESHOLD_MS
        durationMs < MIN_QUALIFIED_THRESHOLD_MS -> (durationMs / 2L).coerceAtLeast(1_000L)
        else -> minOf(MAX_QUALIFIED_THRESHOLD_MS, maxOf(MIN_QUALIFIED_THRESHOLD_MS, durationMs / 2L))
    }

    fun onTrackStarted(trackId: String, uri: String, durationMs: Long, initialPositionMs: Long, nowMs: Long) {
        flushPending(nowMs)
        activeTrackId = trackId
        activeUri = uri
        activeDurationMs = durationMs.coerceAtLeast(0L)
        lastObservedPositionMs = initialPositionMs.coerceAtLeast(0L)
        sessionAccumulatedMs = 0L
        uncommittedMs = 0L
        qualifiedRecorded = false
    }

    fun onSeek(newPositionMs: Long) {
        // Reset baseline position so seek distance is never counted as listened time.
        lastObservedPositionMs = newPositionMs.coerceAtLeast(0L)
    }

    fun onProgressSample(
        trackId: String,
        uri: String,
        durationMs: Long,
        positionMs: Long,
        isPlaying: Boolean,
        playbackSpeed: Float,
        nowMs: Long
    ) {
        if (activeTrackId != trackId || activeUri != uri) {
            onTrackStarted(trackId, uri, durationMs, positionMs, nowMs)
            return
        }
        if (durationMs > 0L && activeDurationMs <= 0L) {
            activeDurationMs = durationMs
        }
        val safePos = positionMs.coerceAtLeast(0L)
        if (!isPlaying) {
            lastObservedPositionMs = safePos
            return
        }
        if (lastObservedPositionMs < 0L) {
            lastObservedPositionMs = safePos
            return
        }

        val mediaDelta = safePos - lastObservedPositionMs
        lastObservedPositionMs = safePos

        val safeSpeed = playbackSpeed.takeIf { it.isFinite() && it in 0.25f..4.0f } ?: 1.0f
        val maxNaturalMediaDelta = (MAX_NATURAL_TICK_DELTA_MS * safeSpeed).toLong()
        if (mediaDelta <= 0L || mediaDelta > maxNaturalMediaDelta) {
            // Seek jump or pause gap — ignore without counting
            return
        }

        // Convert media-time advancement back to wall-clock listening time accounting for playback speed
        val listenedDelta = (mediaDelta / safeSpeed).toLong().coerceAtLeast(1L)
        sessionAccumulatedMs += listenedDelta
        uncommittedMs += listenedDelta

        if (!qualifiedRecorded && sessionAccumulatedMs >= qualificationThresholdMs(activeDurationMs)) {
            qualifiedRecorded = true
            onQualifiedPlay(trackId, uri, nowMs)
        }
        if (uncommittedMs >= periodicCommitIntervalMs) {
            flushPeriodic(nowMs)
        }
    }

    private fun flushPeriodic(nowMs: Long) {
        val id = activeTrackId ?: return
        val uri = activeUri ?: return
        if (uncommittedMs > 0L) {
            val delta = uncommittedMs
            uncommittedMs = 0L
            val cb = onPeriodicCommitListenedMs ?: onCommitListenedMs
            cb(id, uri, delta, nowMs)
        }
    }

    fun flushPending(nowMs: Long) {
        val id = activeTrackId ?: return
        val uri = activeUri ?: return
        if (uncommittedMs > 0L) {
            val delta = uncommittedMs
            uncommittedMs = 0L
            onCommitListenedMs(id, uri, delta, nowMs)
        }
    }

    fun clear(nowMs: Long) {
        flushPending(nowMs)
        activeTrackId = null
        activeUri = null
        activeDurationMs = 0L
        lastObservedPositionMs = -1L
        sessionAccumulatedMs = 0L
        uncommittedMs = 0L
        qualifiedRecorded = false
    }
}

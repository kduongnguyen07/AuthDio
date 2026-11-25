package com.example.mdownload.player.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.example.mdownload.core.RepositoryLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

enum class SleepTimerMode {
    OFF,
    DURATION,
    END_OF_TRACK,
    END_OF_QUEUE
}

data class SleepTimerState(
    val mode: SleepTimerMode = SleepTimerMode.OFF,
    val remainingMs: Long = 0L,
    val totalDurationMs: Long = 0L,
    val fadeOutSeconds: Int = 10,
    val isFadingOut: Boolean = false,
    val lastExpiredAtMs: Long = 0L
) {
    val isActive: Boolean
        get() = mode != SleepTimerMode.OFF
}

/**
 * Pure monotonic deadline, fade-out, and logical queue-boundary calculator for deterministic
 * JVM unit testing across all Repeat & Shuffle combinations.
 */
object SleepTimerMath {
    fun remainingMs(deadlineElapsedMs: Long, nowElapsedMs: Long): Long =
        (deadlineElapsedMs - nowElapsedMs).coerceAtLeast(0L)

    fun fadeFactor(remainingMs: Long, fadeOutSeconds: Int): Float {
        if (fadeOutSeconds <= 0) return 1.0f
        val fadeMs = fadeOutSeconds * 1000L
        if (remainingMs >= fadeMs) return 1.0f
        if (remainingMs <= 0L) return 0.0f
        // Smooth equal-power-like curve (quadratic ease-out) for natural auditory fade
        val linear = (remainingMs.toFloat() / fadeMs.toFloat()).coerceIn(0f, 1f)
        return (linear * linear).coerceIn(0f, 1f)
    }

    fun extendedDeadline(currentDeadlineElapsedMs: Long, nowElapsedMs: Long, addMinutes: Int): Long {
        val base = maxOf(currentDeadlineElapsedMs, nowElapsedMs)
        return base + addMinutes.coerceAtLeast(1) * 60_000L
    }

    /**
     * Determines whether the current queue index is the effective final track for [SleepTimerMode.END_OF_QUEUE].
     *
     * Rules (never mutates user's repeatMode):
     * - Empty queue -> `false`.
     * - `REPEAT_MODE_ONE` -> `true` (because Repeat-One loops the active track indefinitely and never advances,
     *   finishing the active track IS the effective end of playback).
     * - `REPEAT_MODE_OFF` and `REPEAT_MODE_ALL` -> `currentIndex == lastLogicalIndex`, where
     *   `lastLogicalIndex = timeline.getLastWindowIndex(shuffleModeEnabled)`.
     *   Works identically for Shuffle OFF, Shuffle ON, and single-track queues.
     */
    fun isAtEffectiveQueueEnd(
        itemCount: Int,
        currentIndex: Int,
        lastLogicalIndex: Int,
        repeatMode: Int
    ): Boolean {
        if (itemCount <= 0 || currentIndex < 0 || currentIndex >= itemCount) return false
        if (repeatMode == Player.REPEAT_MODE_ONE) return true
        return lastLogicalIndex >= 0 && currentIndex == lastLogicalIndex
    }

    /**
     * Determines whether an automatic transition / position discontinuity marks completion of the
     * logical queue boundary for [SleepTimerMode.END_OF_QUEUE].
     */
    fun shouldExpireEndOfQueueOnAutoTransition(
        itemCount: Int,
        previousIndex: Int,
        newIndex: Int,
        firstLogicalIndex: Int,
        lastLogicalIndex: Int,
        repeatMode: Int
    ): Boolean {
        if (itemCount <= 0) return false
        return when (repeatMode) {
            Player.REPEAT_MODE_ONE -> true
            Player.REPEAT_MODE_ALL -> {
                if (itemCount == 1) {
                    true
                } else {
                    (previousIndex >= 0 && previousIndex == lastLogicalIndex) ||
                        (firstLogicalIndex >= 0 && newIndex == firstLogicalIndex && previousIndex != newIndex)
                }
            }
            else -> previousIndex >= 0 && previousIndex == lastLogicalIndex && newIndex != previousIndex
        }
    }
}

/**
 * Service-bound Sleep Timer independent of Compose UI lifecycle.
 *
 * Lifecycle & accuracy guarantees:
 * - Tracks countdowns using [SystemClock.elapsedRealtime] so wall-clock/timezone edits cannot skew expiry.
 * - Coordinates fade-out exclusively through [PlaybackGainCoordinator] (never competing with ReplayGain
 *   or changing system device volume).
 * - Holds `sleepFadeFactor = 0.0f` across `player.pause()` and only restores `1.0f` AFTER ExoPlayer
 *   confirms playback is paused (`onIsPlayingChanged(false)` + short AudioTrack drain settle), or
 *   immediately if the user manually resumes playback (`onPlayWhenReadyChanged(true)` / `onIsPlayingChanged(true)`).
 *   This eliminates the brief volume burst previously heard during AudioTrack buffer drain at expiry.
 * - Evaluates `END_OF_QUEUE` using ExoPlayer's [Timeline.getLastWindowIndex] in effective playback order,
 *   handling `REPEAT_MODE_OFF`, `REPEAT_MODE_ONE`, `REPEAT_MODE_ALL`, Shuffle ON/OFF, single-track, and
 *   empty queues cleanly without ever mutating the user's repeat mode.
 * - Persists timer state asynchronously off the Main thread using pure-Kotlin JSON serialization.
 */
class SleepTimerController(
    private val persistFile: File?,
    private val gainCoordinator: PlaybackGainCoordinator,
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    mainDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val pauseSettleDelayMs: Long = 150L,
    private val playerDispatcher: (Player, () -> Unit) -> Unit = ::defaultPlayerDispatcher
) {
    constructor(
        context: Context,
        gainCoordinator: PlaybackGainCoordinator,
        elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
        wallClock: () -> Long = { System.currentTimeMillis() }
    ) : this(
        persistFile = File(
            File(context.filesDir, "hifideck_data"),
            "sleep_timer.json"
        ),
        gainCoordinator = gainCoordinator,
        elapsedClock = elapsedClock,
        wallClock = wallClock
    )

    companion object {
        private fun defaultPlayerDispatcher(player: Player, block: () -> Unit) {
            val looper = runCatching { player.applicationLooper }.getOrNull()
            if (looper == null || Looper.myLooper() == looper) {
                block()
            } else {
                Handler(looper).post(block)
            }
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + mainDispatcher)
    private val lock = Any()
    private val readyDeferred = CompletableDeferred<Unit>()

    private val _loadState = MutableStateFlow(RepositoryLoadState.LOADING)
    val loadState: StateFlow<RepositoryLoadState> = _loadState.asStateFlow()

    private val _state = MutableStateFlow(SleepTimerState())
    val state: StateFlow<SleepTimerState> = _state.asStateFlow()

    private var player: Player? = null
    private var tickJob: Job? = null
    private var fadeRestoreJob: Job? = null
    private var deadlineElapsedMs: Long = 0L
    private var armedTrackMediaId: String? = null
    private var lastObservedIndex: Int = -1
    private var lastObservedWasAtQueueEnd: Boolean = false
    private var pendingFadeRestoreAfterPause: Boolean = false
    private var userMutationGeneration: Long = 0L

    val isAwaitingPauseFadeRestore: Boolean
        get() = synchronized(lock) { pendingFadeRestoreAfterPause }

    init {
        // Offload persisted timer restoration to ioDispatcher so Main-Thread startup performs zero disk I/O.
        scope.launch(ioDispatcher) {
            restoreIfStillValid()
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun attachPlayer(player: Player?) {
        synchronized(lock) {
            this.player = player
            if (player != null) {
                captureQueuePositionLocked(player)
                if (_state.value.isActive && tickJob?.isActive != true) {
                    startTickerLoop()
                }
            }
        }
    }

    fun startDuration(minutes: Int, fadeOutSeconds: Int) {
        val clampedMin = minutes.coerceIn(1, 360)
        val safeFade = fadeOutSeconds.coerceIn(0, 60)
        val durationMs = clampedMin * 60_000L
        val now = elapsedClock()
        synchronized(lock) {
            userMutationGeneration++
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
            deadlineElapsedMs = now + durationMs
            armedTrackMediaId = null
            _state.value = SleepTimerState(
                mode = SleepTimerMode.DURATION,
                remainingMs = durationMs,
                totalDurationMs = durationMs,
                fadeOutSeconds = safeFade,
                isFadingOut = false
            )
            persistStateAsync(SleepTimerMode.DURATION, wallClock() + durationMs, durationMs, safeFade)
            startTickerLoop()
        }
    }

    fun startEndOfTrack(fadeOutSeconds: Int) {
        val safeFade = fadeOutSeconds.coerceIn(0, 60)
        synchronized(lock) {
            userMutationGeneration++
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
            deadlineElapsedMs = 0L
            armedTrackMediaId = player?.currentMediaItem?.mediaId
            captureQueuePositionLocked(player)
            val remaining = currentTrackRemainingMs(player)
            _state.value = SleepTimerState(
                mode = SleepTimerMode.END_OF_TRACK,
                remainingMs = remaining,
                totalDurationMs = remaining,
                fadeOutSeconds = safeFade,
                isFadingOut = false
            )
            persistStateAsync(SleepTimerMode.END_OF_TRACK, 0L, 0L, safeFade)
            startTickerLoop()
        }
    }

    fun startEndOfQueue(fadeOutSeconds: Int) {
        val safeFade = fadeOutSeconds.coerceIn(0, 60)
        synchronized(lock) {
            userMutationGeneration++
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
            deadlineElapsedMs = 0L
            armedTrackMediaId = null
            captureQueuePositionLocked(player)
            val atEnd = isPlayerAtEffectiveQueueEnd(player)
            val rem = if (atEnd) currentTrackRemainingMs(player) else 0L
            _state.value = SleepTimerState(
                mode = SleepTimerMode.END_OF_QUEUE,
                remainingMs = rem,
                totalDurationMs = rem,
                fadeOutSeconds = safeFade,
                isFadingOut = false
            )
            persistStateAsync(SleepTimerMode.END_OF_QUEUE, 0L, 0L, safeFade)
            startTickerLoop()
        }
    }

    fun updateFadeOutSeconds(fadeOutSeconds: Int) {
        val safeFade = fadeOutSeconds.coerceIn(0, 60)
        synchronized(lock) {
            val cur = _state.value
            if (!cur.isActive) {
                _state.value = cur.copy(fadeOutSeconds = safeFade)
                return
            }
            userMutationGeneration++
            val updated = cur.copy(fadeOutSeconds = safeFade, isFadingOut = if (safeFade == 0) false else cur.isFadingOut)
            _state.value = updated
            if (safeFade == 0) {
                gainCoordinator.resetSleepFade()
            }
            val wallDeadline = if (cur.mode == SleepTimerMode.DURATION) {
                wallClock() + SleepTimerMath.remainingMs(deadlineElapsedMs, elapsedClock())
            } else 0L
            persistStateAsync(updated.mode, wallDeadline, updated.totalDurationMs, safeFade)
        }
    }

    fun extendMinutes(addMinutes: Int) {
        if (addMinutes <= 0) return
        synchronized(lock) {
            userMutationGeneration++
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
            val cur = _state.value
            if (cur.mode != SleepTimerMode.DURATION) {
                startDuration(addMinutes, cur.fadeOutSeconds)
                return
            }
            val now = elapsedClock()
            deadlineElapsedMs = SleepTimerMath.extendedDeadline(deadlineElapsedMs, now, addMinutes)
            val newRemaining = SleepTimerMath.remainingMs(deadlineElapsedMs, now)
            val newTotal = maxOf(cur.totalDurationMs, newRemaining)
            _state.value = cur.copy(
                remainingMs = newRemaining,
                totalDurationMs = newTotal,
                isFadingOut = false
            )
            persistStateAsync(SleepTimerMode.DURATION, wallClock() + newRemaining, newTotal, cur.fadeOutSeconds)
            startTickerLoop()
        }
    }

    fun cancel() {
        synchronized(lock) {
            userMutationGeneration++
            tickJob?.cancel()
            tickJob = null
            deadlineElapsedMs = 0L
            armedTrackMediaId = null
            lastObservedIndex = -1
            lastObservedWasAtQueueEnd = false
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
            _state.value = SleepTimerState(
                mode = SleepTimerMode.OFF,
                fadeOutSeconds = _state.value.fadeOutSeconds
            )
            clearPersistedAsync()
        }
    }

    /**
     * Called by [HiFiPlaybackService] on ExoPlayer `onMediaItemTransition`.
     */
    fun onMediaItemTransition(newMediaId: String?, reason: Int) {
        synchronized(lock) {
            val cur = _state.value
            val p = player
            val prevIdx = lastObservedIndex
            val prevWasAtQueueEnd = lastObservedWasAtQueueEnd
            captureQueuePositionLocked(p)

            when (cur.mode) {
                SleepTimerMode.END_OF_TRACK -> {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        expireAndPauseLocked()
                    } else {
                        // User manually skipped tracks; re-arm end-of-track on the newly chosen track.
                        armedTrackMediaId = newMediaId
                        gainCoordinator.resetSleepFade()
                    }
                }

                SleepTimerMode.END_OF_QUEUE -> {
                    if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                        reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                    ) {
                        val shouldExpire = if (p == null) {
                            prevWasAtQueueEnd
                        } else {
                            val tl = p.currentTimeline
                            val count = p.mediaItemCount
                            val firstIdx = if (!tl.isEmpty) tl.getFirstWindowIndex(p.shuffleModeEnabled) else -1
                            val lastIdx = if (!tl.isEmpty) tl.getLastWindowIndex(p.shuffleModeEnabled) else -1
                            val newIdx = p.currentMediaItemIndex
                            prevWasAtQueueEnd || SleepTimerMath.shouldExpireEndOfQueueOnAutoTransition(
                                itemCount = count,
                                previousIndex = prevIdx,
                                newIndex = newIdx,
                                firstLogicalIndex = firstIdx,
                                lastLogicalIndex = lastIdx,
                                repeatMode = p.repeatMode
                            )
                        }
                        if (shouldExpire) {
                            expireAndPauseLocked()
                        }
                    } else {
                        // User manually jumped to another track in queue
                        gainCoordinator.resetSleepFade()
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * Called by [HiFiPlaybackService] on ExoPlayer `onPositionDiscontinuity`.
     * Essential for `REPEAT_MODE_ONE` (and single-track `REPEAT_MODE_ALL`), where Media3 loops
     * the same MediaItem via `DISCONTINUITY_REASON_AUTO_TRANSITION` without necessarily firing
     * `onMediaItemTransition`.
     */
    fun onPositionDiscontinuity(oldMediaItemIndex: Int, newMediaItemIndex: Int, reason: Int) {
        if (reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) return
        synchronized(lock) {
            val cur = _state.value
            val p = player
            when (cur.mode) {
                SleepTimerMode.END_OF_TRACK -> {
                    if (oldMediaItemIndex == newMediaItemIndex) {
                        expireAndPauseLocked()
                    }
                }

                SleepTimerMode.END_OF_QUEUE -> {
                    if (p == null) {
                        if (lastObservedWasAtQueueEnd) expireAndPauseLocked()
                        return
                    }
                    val tl = p.currentTimeline
                    val count = p.mediaItemCount
                    val firstIdx = if (!tl.isEmpty) tl.getFirstWindowIndex(p.shuffleModeEnabled) else -1
                    val lastIdx = if (!tl.isEmpty) tl.getLastWindowIndex(p.shuffleModeEnabled) else -1
                    val shouldExpire = SleepTimerMath.shouldExpireEndOfQueueOnAutoTransition(
                        itemCount = count,
                        previousIndex = oldMediaItemIndex,
                        newIndex = newMediaItemIndex,
                        firstLogicalIndex = firstIdx,
                        lastLogicalIndex = lastIdx,
                        repeatMode = p.repeatMode
                    )
                    if (shouldExpire) {
                        expireAndPauseLocked()
                    }
                }

                else -> Unit
            }
        }
    }

    /**
     * Called by [HiFiPlaybackService] on ExoPlayer `onIsPlayingChanged`.
     * Completes deferred post-pause fade restoration cleanly once AudioTrack stops playing,
     * or immediately restores unity fade if the user explicitly resumes playback.
     */
    fun onIsPlayingChanged(isPlaying: Boolean) {
        synchronized(lock) {
            if (!pendingFadeRestoreAfterPause) return
            if (isPlaying) {
                // User resumed playback before pause settle completed -> restore immediately so
                // resumed audio is never muted.
                clearPendingFadeRestoreLocked(restoreFadeNow = true)
            } else {
                // Playback confirmed paused -> schedule restore after brief AudioTrack drain settle.
                scheduleFadeRestoreAfterPauseLocked()
            }
        }
    }

    /**
     * Called by [HiFiPlaybackService] on ExoPlayer `onPlayWhenReadyChanged`.
     */
    fun onPlayWhenReadyChanged(playWhenReady: Boolean) {
        synchronized(lock) {
            if (playWhenReady && pendingFadeRestoreAfterPause) {
                clearPendingFadeRestoreLocked(restoreFadeNow = true)
            }
        }
    }

    /**
     * Called by [HiFiPlaybackService] on ExoPlayer `onPlaybackStateChanged`.
     */
    fun onPlaybackStateChanged(playbackState: Int) {
        synchronized(lock) {
            val cur = _state.value
            if (playbackState == Player.STATE_ENDED &&
                (cur.mode == SleepTimerMode.END_OF_TRACK || cur.mode == SleepTimerMode.END_OF_QUEUE)
            ) {
                expireAndPauseLocked()
            } else if ((playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) &&
                pendingFadeRestoreAfterPause
            ) {
                scheduleFadeRestoreAfterPauseLocked()
            }
        }
    }

    private fun startTickerLoop() {
        tickJob?.cancel()
        tickJob = scope.launch {
            while (true) {
                val cur = _state.value
                when (cur.mode) {
                    SleepTimerMode.OFF -> break

                    SleepTimerMode.DURATION -> {
                        val rem = SleepTimerMath.remainingMs(deadlineElapsedMs, elapsedClock())
                        if (rem <= 0L) {
                            synchronized(lock) { expireAndPauseLocked() }
                            break
                        }
                        val fadeFactor = SleepTimerMath.fadeFactor(rem, cur.fadeOutSeconds)
                        val fading = cur.fadeOutSeconds > 0 && rem <= cur.fadeOutSeconds * 1000L
                        gainCoordinator.setSleepFadeFactor(fadeFactor)
                        _state.value = cur.copy(remainingMs = rem, isFadingOut = fading)
                        delay(if (fading) 100L else 500L)
                    }

                    SleepTimerMode.END_OF_TRACK -> {
                        val p = player
                        synchronized(lock) { captureQueuePositionLocked(p) }
                        val rem = currentTrackRemainingMs(p)
                        val isPlaying = p?.isPlaying == true
                        val fadeFactor = if (isPlaying && rem > 0L) {
                            SleepTimerMath.fadeFactor(rem, cur.fadeOutSeconds)
                        } else {
                            1.0f
                        }
                        val fading = isPlaying && cur.fadeOutSeconds > 0 && rem in 1L..(cur.fadeOutSeconds * 1000L)
                        gainCoordinator.setSleepFadeFactor(fadeFactor)
                        _state.value = cur.copy(remainingMs = rem, isFadingOut = fading)
                        delay(if (fading) 100L else 400L)
                    }

                    SleepTimerMode.END_OF_QUEUE -> {
                        val p = player
                        val isLastTrack = synchronized(lock) {
                            captureQueuePositionLocked(p)
                            isPlayerAtEffectiveQueueEnd(p)
                        }
                        val rem = if (isLastTrack) currentTrackRemainingMs(p) else 0L
                        val isPlaying = p?.isPlaying == true
                        val fadeFactor = if (isLastTrack && isPlaying && rem > 0L) {
                            SleepTimerMath.fadeFactor(rem, cur.fadeOutSeconds)
                        } else {
                            1.0f
                        }
                        val fading = isLastTrack && isPlaying && cur.fadeOutSeconds > 0 && rem in 1L..(cur.fadeOutSeconds * 1000L)
                        gainCoordinator.setSleepFadeFactor(fadeFactor)
                        _state.value = cur.copy(remainingMs = rem, isFadingOut = fading)
                        delay(if (fading) 100L else 500L)
                    }
                }
            }
            tickJob = null
        }
    }

    private fun expireAndPauseLocked() {
        tickJob?.cancel()
        tickJob = null
        deadlineElapsedMs = 0L
        armedTrackMediaId = null
        lastObservedIndex = -1
        lastObservedWasAtQueueEnd = false

        val prevFade = _state.value.fadeOutSeconds
        val p = player
        val wasActivelyPlaying = p != null && (p.isPlaying || p.playWhenReady)

        if (prevFade > 0 && wasActivelyPlaying) {
            // Keep sleepFadeFactor at 0.0f while pause propagates through AudioTrack drain
            // so the user NEVER hears a loud volume burst at expiry.
            gainCoordinator.setSleepFadeFactor(0.0f)
            pendingFadeRestoreAfterPause = true
            fadeRestoreJob?.cancel()
            fadeRestoreJob = null
        } else {
            // Either fade was 0s or playback was already paused/idle: restore unity immediately.
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
        }

        // Pause playback cleanly on player.applicationLooper while preserving queue & position.
        if (p != null) {
            playerDispatcher(p) {
                runCatching { p.pause() }
                // If player was already not playing after pause() returns synchronously (e.g. STATE_ENDED
                // or fake player that doesn't emit onIsPlayingChanged), schedule settle check if needed.
                synchronized(lock) {
                    if (pendingFadeRestoreAfterPause && !p.isPlaying && !p.playWhenReady) {
                        scheduleFadeRestoreAfterPauseLocked()
                    }
                }
            }
        }

        _state.value = SleepTimerState(
            mode = SleepTimerMode.OFF,
            remainingMs = 0L,
            totalDurationMs = 0L,
            fadeOutSeconds = prevFade,
            isFadingOut = false,
            lastExpiredAtMs = wallClock()
        )
        clearPersistedAsync()
    }

    private fun scheduleFadeRestoreAfterPauseLocked() {
        if (!pendingFadeRestoreAfterPause) return
        if (fadeRestoreJob?.isActive == true) return
        if (pauseSettleDelayMs <= 0L) {
            clearPendingFadeRestoreLocked(restoreFadeNow = true)
            return
        }
        fadeRestoreJob = scope.launch {
            delay(pauseSettleDelayMs)
            synchronized(lock) {
                if (pendingFadeRestoreAfterPause) {
                    val p = player
                    // Only restore once playback is actually paused/ended (or player detached)
                    if (p == null || !p.isPlaying) {
                        clearPendingFadeRestoreLocked(restoreFadeNow = true)
                    }
                }
            }
        }
    }

    private fun clearPendingFadeRestoreLocked(restoreFadeNow: Boolean = true) {
        pendingFadeRestoreAfterPause = false
        fadeRestoreJob?.cancel()
        fadeRestoreJob = null
        if (restoreFadeNow) {
            gainCoordinator.resetSleepFade(snap = true)
        }
    }

    private fun captureQueuePositionLocked(p: Player?) {
        if (p == null || p.mediaItemCount <= 0) {
            lastObservedIndex = -1
            lastObservedWasAtQueueEnd = false
            return
        }
        lastObservedIndex = p.currentMediaItemIndex
        lastObservedWasAtQueueEnd = isPlayerAtEffectiveQueueEnd(p)
    }

    private fun isPlayerAtEffectiveQueueEnd(p: Player?): Boolean {
        if (p == null) return false
        val count = p.mediaItemCount
        if (count <= 0) return false
        val tl: Timeline = p.currentTimeline
        val lastLogical = if (!tl.isEmpty) {
            tl.getLastWindowIndex(p.shuffleModeEnabled)
        } else {
            count - 1
        }
        return SleepTimerMath.isAtEffectiveQueueEnd(
            itemCount = count,
            currentIndex = p.currentMediaItemIndex,
            lastLogicalIndex = lastLogical,
            repeatMode = p.repeatMode
        )
    }

    private fun currentTrackRemainingMs(p: Player?): Long {
        if (p == null) return 0L
        val dur = p.duration
        if (dur <= 0L) return 0L
        val speed = p.playbackParameters.speed.takeIf { it > 0.1f } ?: 1.0f
        val mediaRem = (dur - p.currentPosition).coerceAtLeast(0L)
        return (mediaRem / speed).toLong()
    }

    private fun persistStateAsync(mode: SleepTimerMode, wallDeadlineMs: Long, totalMs: Long, fadeSec: Int) {
        val file = persistFile ?: return
        val json = """{"mode":"${mode.name}","wall_deadline_ms":$wallDeadlineMs,"total_ms":$totalMs,"fade_sec":$fadeSec}"""
        scope.launch(ioDispatcher) {
            runCatching {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(json, Charsets.UTF_8)
                if (!tmp.renameTo(file)) {
                    file.writeText(json, Charsets.UTF_8)
                    tmp.delete()
                }
            }
        }
    }

    private fun clearPersistedAsync() {
        val file = persistFile ?: return
        scope.launch(ioDispatcher) {
            runCatching { if (file.exists()) file.delete() }
        }
    }

    private fun restoreIfStillValid() {
        val file = persistFile
        var failed = false
        if (file != null && file.isFile) {
            runCatching {
                val raw = file.readText(Charsets.UTF_8)
                val modeName = Regex("\"mode\"\\s*:\\s*\"([A-Z_]+)\"").find(raw)?.groupValues?.get(1) ?: ""
                val wallDeadline = Regex("\"wall_deadline_ms\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val totalMs = Regex("\"total_ms\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                val fadeSec = (Regex("\"fade_sec\"\\s*:\\s*(-?\\d+)").find(raw)?.groupValues?.get(1)?.toIntOrNull() ?: 10).coerceIn(0, 60)
                val mode = SleepTimerMode.entries.firstOrNull { it.name == modeName } ?: SleepTimerMode.OFF
                synchronized(lock) {
                    // Never clobber a timer explicitly started/cancelled by the user while IO restore was in flight.
                    if (userMutationGeneration > 0L) return@synchronized
                    when (mode) {
                        SleepTimerMode.DURATION -> {
                            val remaining = wallDeadline - wallClock()
                            if (remaining > 1500L) {
                                deadlineElapsedMs = elapsedClock() + remaining
                                _state.value = SleepTimerState(
                                    mode = SleepTimerMode.DURATION,
                                    remainingMs = remaining,
                                    totalDurationMs = totalMs.coerceAtLeast(remaining),
                                    fadeOutSeconds = fadeSec
                                )
                                // Start countdown ticker immediately even before player attaches (AUD-02c)
                                startTickerLoop()
                            } else {
                                clearPersistedAsync()
                            }
                        }
                        SleepTimerMode.END_OF_TRACK, SleepTimerMode.END_OF_QUEUE -> {
                            _state.value = SleepTimerState(mode = mode, fadeOutSeconds = fadeSec)
                        }
                        SleepTimerMode.OFF -> clearPersistedAsync()
                    }
                }
            }.onFailure {
                failed = true
            }
        }
        _loadState.value = if (failed) RepositoryLoadState.FAILED else RepositoryLoadState.READY
        readyDeferred.complete(Unit)
    }
}

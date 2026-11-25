# Sleep Timer Reliability & Edge-Case Fix Report (`STAB-01` / `AUD-02`)

**Date:** 2026-11-24  
**Primary Files:**
- `app/src/main/java/com/example/mdownload/player/playback/SleepTimerController.kt`
- `app/src/main/java/com/example/mdownload/player/playback/HiFiPlaybackService.kt`
- `app/src/main/java/com/example/mdownload/MainViewModel.kt`

---

## 1. Original Defects & Root Causes

1. **`AUD-02a` — Volume Spike at Sleep Timer Expiry**:
   - `SleepTimerController.expireAndPause()` invoked `player.pause()` and **immediately** called `gainCoordinator.resetSleepFade()` (`1.0f`) synchronously. Because `player.pause()` is asynchronous and ExoPlayer / `AudioTrack` still drains ~50–150 ms of buffered audio before silencing, gain snapped back to full volume during the drain window, producing an audible burst right after fading to silence.
2. **`AUD-02b` — `END_OF_QUEUE` Broken Under Repeat & Shuffle Modes**:
   - `END_OF_QUEUE` checked `!player.hasNextMediaItem()` to arm fade-out and detect the final track. Under `REPEAT_MODE_ALL` or `REPEAT_MODE_ONE`, Media3's `hasNextMediaItem()` is **always `true`**, so fade-out never started and `REPEAT_MODE_ONE` looped infinitely without ever expiring.
   - Under `REPEAT_MODE_ONE` (and single-track `REPEAT_MODE_ALL`), Media3 loops the same `MediaItem` via `onPositionDiscontinuity(..., DISCONTINUITY_REASON_AUTO_TRANSITION)` without necessarily emitting `onMediaItemTransition`, so neither `END_OF_TRACK` nor `END_OF_QUEUE` caught the boundary reliably.
3. **`AUD-02c/d/e` — Restoration, Main-Thread Disk I/O, and Fade Persistence**:
   - Restored `DURATION` timers did not start ticking until `attachPlayer()` was called.
   - `persistState()` performed synchronous disk writes inside `synchronized(lock)` on the Main thread.
   - `updateFadeOutSeconds()` did not persist the updated fade duration for active timers.

---

## 2. Implementation Strategy

### 2.1 Zero-Spike Post-Pause Fade Restoration (`B4`)
- Introduced `pendingFadeRestoreAfterPause` guarded by `lock`.
- When `expireAndPauseLocked()` runs while playback is active (`p.isPlaying || p.playWhenReady`) and `fadeOutSeconds > 0`:
  1. Keeps `gainCoordinator.setSleepFadeFactor(0.0f)` actively holding silence.
  2. Sets `pendingFadeRestoreAfterPause = true`.
  3. Dispatches `p.pause()` onto `player.applicationLooper` (preserving queue and playback position).
  4. Does **not** restore `1.0f` yet.
- `HiFiPlaybackService` now forwards `onIsPlayingChanged(isPlaying)`, `onPlayWhenReadyChanged(playWhenReady, reason)`, and `onPositionDiscontinuity(oldPosition, newPosition, reason)` to `SleepTimerController`.
- When `onIsPlayingChanged(false)` confirms playback has paused, `SleepTimerController` waits `pauseSettleDelayMs` (150 ms default for `AudioTrack` drain settle), verifies playback is still paused, and then silently calls `gainCoordinator.resetSleepFade(snap = true)` — restoring normal ReplayGain without any burst.
- If the user manually presses Play before the 150 ms settle finishes (`onPlayWhenReadyChanged(true)` or `onIsPlayingChanged(true)`), or starts/extends/cancels a timer, `clearPendingFadeRestoreLocked(restoreFadeNow = true)` restores `1.0f` immediately so resumed playback is never muted.

### 2.2 Logical Queue-End Detection Under All Repeat & Shuffle Modes (`B5`)
Added pure, deterministic `SleepTimerMath.isAtEffectiveQueueEnd(...)` and `SleepTimerMath.shouldExpireEndOfQueueOnAutoTransition(...)`:
- Uses Media3 `Timeline.getFirstWindowIndex(shuffleModeEnabled)` and `Timeline.getLastWindowIndex(shuffleModeEnabled)` — **never mutating the user's `player.repeatMode` or `shuffleModeEnabled`**.
- **Empty queue (`itemCount == 0`)**: safely remains armed without crashing or false-expiring.
- **`REPEAT_MODE_OFF` (Shuffle ON/OFF)**: fades on `currentIndex == lastLogicalIndex` and expires on `STATE_ENDED`.
- **`REPEAT_MODE_ALL` (Shuffle ON/OFF)**: fades on `currentIndex == lastLogicalIndex` (the true last track in active shuffle order!) and expires cleanly when ExoPlayer auto-wraps past `lastLogicalIndex` back to `firstLogicalIndex`.
- **`REPEAT_MODE_ONE` (single or multi-track)**: treats the active track as the effective playback end (preventing infinite looping), fades out cleanly, and expires via `onPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION)` or `onMediaItemTransition(REASON_REPEAT)`.
- **Single-track queue under `REPEAT_MODE_ALL`**: expires via `onPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION)` when track 0 loops.
- **`END_OF_TRACK` under `REPEAT_MODE_ONE`**: also intercepts `onPositionDiscontinuity(oldIdx == newIdx, DISCONTINUITY_REASON_AUTO_TRANSITION)`.

### 2.3 Async Pure-Kotlin Persistence & Immediate Restore Tick
- Replaced `org.json.JSONObject` with compact pure-Kotlin JSON serialization executed asynchronously on `ioDispatcher` (`Dispatchers.IO`).
- `updateFadeOutSeconds()` now persists updated fade seconds when a timer is active.
- `restoreIfStillValid()` starts `startTickerLoop()` immediately for restored `DURATION` timers even before `attachPlayer()` runs.

---

## 3. Tests Performed & Actual Results

### JVM Unit Tests (`AudioGainAndSleepTimerP0Test.kt`) — ALL PASSED
- `sleepTimer_expiryHoldsZeroFadeUntilPausedAndRestoresCleanlyOnSettleOrResume`:
  - Verified immediately after expiry (while `isPlaying == true`): `sleepFadeFactor == 0.0f`, `effectiveLinearGain == 0.0f`, `isAwaitingPauseFadeRestore == true`, `pauseCallCount == 1`, queue & position intact.
  - Verified after `onIsPlayingChanged(false)`: `sleepFadeFactor == 1.0f` and ReplayGain (`-4.0 dB`) restored cleanly.
  - Verified immediate user resume (`onPlayWhenReadyChanged(true)` before settle): `sleepFadeFactor` immediately restored to `1.0f`.
- `sleepTimer_endOfQueueMathAndControllerWorkAcrossAllRepeatAndShuffleModes`:
  - Verified `REPEAT_MODE_OFF` + Shuffle OFF (3 tracks)
  - Verified `REPEAT_MODE_ALL` + Shuffle OFF (3 tracks, `repeatMode` untouched)
  - Verified `REPEAT_MODE_ALL` + Shuffle ON (custom shuffle order `[2, 0, 1]`, expires strictly after logical last index `1` wraps to `2`, `repeatMode` & `shuffleEnabled` untouched)
  - Verified `REPEAT_MODE_ONE` (middle track 1 of 3 expires via `onPositionDiscontinuity`, `repeatMode` untouched)
  - Verified single-track queue under `REPEAT_MODE_ALL`
  - Verified empty queue (0 tracks, safe no-op)
- `sleepTimer_endOfTrackRepeatOneAndPersistedFadeAndRestore`:
  - Verified `END_OF_TRACK` under `REPEAT_MODE_ONE`, `updateFadeOutSeconds(25)` persistence, and restored `DURATION` timer countdown without an attached player.

### Real-Device Hardware Verification (vivo X200s / Android 17)
- `P0StabilizationInstrumentedTest#realExoPlayer_replayGainSingleAuthorityZeroDoubleAttenuationAndSleepTimerExpiry` — **PASSED** (real ExoPlayer paused cleanly, preserved queue, and restored `sleepFadeFactor == 1.0f` + `-6.0 dB` ReplayGain after `AudioTrack` pause settle).
- `MusicPlayerInstrumentedTest#sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel` — **PASSED**.

---

## 4. Remaining Limitations

1. **OS Process Death During Long Idle Timer**: Persisted wall-clock deadline restores remaining countdown if the app is relaunched before expiry, but exact pause delivery after OS kills the entire process would require `AlarmManager` exact alarms (intentionally out of scope for P0; playback is already stopped if the OS killed the foreground service).
2. **Dynamic Queue Editing During `END_OF_QUEUE`**: If the user appends tracks to the end of the queue while `END_OF_QUEUE` is already fading on the previously-last track, the next 500 ms ticker poll detects `!isPlayerAtEffectiveQueueEnd` and automatically resets fade to `1.0f` until the new last track is reached.

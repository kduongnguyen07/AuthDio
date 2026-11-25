# ReplayGain & Audio DSP Gain Authority Fix Report (`STAB-01` / `AUD-01`)

**Date:** 2026-11-24  
**Primary Files:**
- `app/src/main/java/com/example/mdownload/player/playback/ReplayGainAudioProcessor.kt`
- `app/src/main/java/com/example/mdownload/player/playback/PlaybackGainCoordinator.kt`
- `app/src/main/java/com/example/mdownload/player/playback/HiFiPlaybackService.kt`
- `app/src/main/java/com/example/mdownload/MainViewModel.kt`

---

## 1. Original Defects & Root Causes

1. **`AUD-01` — Double ReplayGain Attenuation Race**:
   - When `PlaybackGainCoordinator.reapplyLocked()` ran before ExoPlayer configured `ReplayGainAudioProcessor` (`isProcessingPcm == false`), it staged `audioProcessor.setTargetLinearGain(effective)` **AND** set `player.volume = effective.coerceIn(0f, 1f)`.
   - When ExoPlayer's audio thread subsequently called `ReplayGainAudioProcessor.onConfigure()`, `isProcessingPcm` flipped to `true` with **no callback** to `PlaybackGainCoordinator`. Both `ReplayGainAudioProcessor` and `player.volume` attenuated simultaneously (`-6 dB` became `-12 dB`).
   - Conversely, when `onReset()` flipped `isProcessingPcm` back to `false` (offload/passthrough or sink reset), `player.volume` remained `1.0f` and ReplayGain silently stopped applying.
2. **`AUD-01b` — Cross-Thread Data Race on `currentGain`**:
   - `ReplayGainAudioProcessor.currentGain` was an unsynchronized `Float` written by caller threads via `snapToTargetGain()` while simultaneously read/written on ExoPlayer's audio thread in `queueInput()`, `onFlush()`, and `onReset()`.
3. **`AUD-03` — Unity-Gain Processing Documentation Mismatch**:
   - Prior docs claimed zero-overhead processor bypass when unity (`1.0x`), while `BaseAudioProcessor.isActive()` remained `true`. Additionally, `onFlush()` overrode a deprecated Media3 method.
4. **`AUD-04` — Stale ReplayGain Across Uncached Track Transitions**:
   - In `HiFiPlaybackService.applyReplayGainForMediaItem()`, on cache miss, the previous track's gain remained active until background `Dispatchers.IO` tag scanning completed.
5. **`AUD-07` — Preferences Reset Desync**:
   - `MainViewModel.resetPreferences()` reset `AppSettings` without pushing the reset `ReplayGainConfig` to `PlaybackGainCoordinator`.

---

## 2. Implementation Strategy

### 2.1 Single-Authority Ordered Handoff State Machine (`PlaybackGainCoordinator.kt`)
Enforced strict mutual exclusion across all 3 paths:
- **`BYPASS`**: `audioProcessor.targetGain == 1.0f` and `player.volume == 1.0f`.
- **`DSP_PCM_PROCESSOR`**: `audioProcessor.targetGain == effective` and `player.volume == 1.0f`.
- **`PLAYER_VOLUME_FALLBACK`**: `audioProcessor.targetGain == 1.0f` (never pre-staged!) and `player.volume == effective.coerceIn(0f, 1f)`.

Handoff protocol when `ReplayGainAudioProcessor.onConfigure()` transitions `isProcessingPcm: false -> true` on ExoPlayer's audio thread:
1. Because `audioProcessor.targetGain` was held at `1.0f` while in `PLAYER_VOLUME_FALLBACK`, any audio frames decoded while the handoff runnable is queued to `player.applicationLooper` receive `1.0f * player.volume` (exact single attenuation — **zero double attenuation** even under Main-thread scheduling lag).
2. `onProcessorPcmStateChanged()` never mutates `player.volume` from the audio thread; it increments a monotonic `transitionGeneration` and posts `reconcileOnPlayerThreadLocked()` to `player.applicationLooper`.
3. On `player.applicationLooper`, `reconcileOnPlayerThreadLocked()` resets `player.volume = 1.0f` first, then sets `audioProcessor.setTargetLinearGain(effective)` + `snapToTargetGain()`, and records `actuatorOwner = ActuatorOwner.DSP_PCM`.
4. Subsequent steady-state gain/fade updates while `actuatorOwner == DSP_PCM` write directly to the lock-free `ReplayGainAudioProcessor` target without extra Main-thread posts.
5. On `onReset()` (`true -> false`), the coordinator demotes `audioProcessor.targetGain = 1.0f` and engages `player.volume = effective.coerceIn(0f, 1f)` on `player.applicationLooper`.

### 2.2 Lock-Free Audio-Thread Ownership (`ReplayGainAudioProcessor.kt`)
- `currentGain` is exclusively read and written on ExoPlayer's audio thread (`queueInput`, `onFlush(StreamMetadata)`, `onReset`).
- Cross-thread callers communicate solely via `@Volatile targetGain` and `AtomicBoolean snapRequested` — zero locks, zero heap allocations inside `queueInput()`.
- Replaced deprecated `onFlush()` with `onFlush(streamMetadata: AudioProcessor.StreamMetadata)`.
- **Honest Unity Fast-Path**: Kept `onConfigure()` active for `PCM_16BIT` and `PCM_FLOAT` because Media3's `DefaultAudioSink` checks `isActive()` only at `configure()`/`flush()` time; keeping the processor installed allows instantaneous mid-track ReplayGain toggling (including positive `+dB` preamp) and Sleep Timer fade-out without restarting the sink. When `currentGain == 1.0f && targetGain == 1.0f`, `queueInput()` takes a bit-exact bulk `ByteBuffer.put()` fast path with zero floating-point scaling or rounding math (tracked via `unityFastPathBufferCount`). We explicitly do **not** claim zero-copy `DefaultAudioSink` bypass.

### 2.3 Service & ViewModel Consistency
- `HiFiPlaybackService.applyReplayGainForMediaItem()` immediately clears the previous track's ReplayGain (`updateTrackReplayGain(freshTrack.uri, null, snapOnTrackSwitch)`) on cache miss before launching async IO tag resolution.
- `MainViewModel.resetPreferences()` immediately syncs reset `ReplayGainConfig` to `gainCoordinator` and default fade seconds to `sleepTimerController`.

---

## 3. Tests Performed & Actual Results

### JVM Unit Tests (`AudioGainAndSleepTimerP0Test.kt`) — ALL PASSED
- `gainCoordinator_fallbackToPcmHandoffNeverDoubleAttenuatesEvenWithAsyncMainThreadLag`: injected a manual Main-thread queue to simulate scheduling lag after `processor.configure(PCM_16BIT)`. Verified `player.volume * processor.currentTargetLinearGain == 0.501` (`-6.0 dB`) **before**, **during**, and **after** handoff, and verified mid-transition PCM buffer stayed `20000` (1.0x) while post-handoff buffer scaled to `~10024` (-6 dB) with `player.volume == 1.0f`.
- `gainCoordinator_pcmToOffloadResetAndPositivePreampAndModeTransitions`: verified `OFF -> TRACK (+4 dB boost) -> ALBUM + sleep fade -> processor.reset() fallback -> missing-tag fallback preamp -> OFF`.
- `audioProcessor_unityFastPathIsBitExactForPcm16AndPcmFloat`: verified bit-for-bit identical output bytes on both `PCM_16BIT` and `PCM_FLOAT` with `scaledBufferCount == 0`.
- `audioProcessor_concurrentTargetGainAndSnapUpdatesAreRaceFree`: 4 writer threads hammering `setTargetLinearGain` + `snapToTargetGain` concurrently with audio thread pumping 500 buffers + flushes; zero exceptions, zero NaN/out-of-range samples, exact convergence.

### Real-Device Hardware Verification (vivo X200s / Android 17)
- `P0StabilizationInstrumentedTest#realExoPlayer_replayGainSingleAuthorityZeroDoubleAttenuationAndSleepTimerExpiry` — **PASSED**!
- Captured live vivo X200s `AudioTrack` logcat proving exact single-authority handoff on real ExoPlayer hardware:
  ```
  D/AudioTrack: setVolume(9534): left = 0.501187, right = 0.501187   // cold-start fallback (-6.0 dB)
  D/AudioTrack: start(9534): prior state:STATE_STOPPED
  D/AudioTrack: setVolume(9534): left = 1.000000, right = 1.000000   // atomic handoff to DSP_PCM_PROCESSOR
  ```
- Also passed existing `MusicPlayerInstrumentedTest#replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade`.

---

## 4. Remaining Limitations

1. **Unity fast-path vs. zero-copy sink bypass**: Because Media3 evaluates `AudioProcessor.isActive()` only on `configure()`/`flush()`, unity playback performs a bit-exact `ByteBuffer.put()` copy (~176 KB/s for 44.1 kHz stereo PCM-16) rather than zero-copy sink bypass, preserving instant mid-track ReplayGain `+dB` boost and Sleep Timer responsiveness.
2. **Positive preamp in `PLAYER_VOLUME_FALLBACK`**: Android's `AudioTrack` / `Player.setVolume` is capped at `[0.0f, 1.0f]`, so positive boost (`> 0 dB`) can only apply when ExoPlayer routes through `DSP_PCM_PROCESSOR` (the normal path for all decoded local audio in HiFiDeck).

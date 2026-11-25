# HiFiDeck — Audio Pipeline Audit (ReplayGain, DSP, Sleep Timer & Audio Inspector)

**Audit Scope:** Phase 5B (ReplayGain & DSP Correctness), Phase 5C (Sleep Timer Reliability), EBU R128 Loudness Scanner, and Audio Quality Inspector.  
**Audit Date:** 2026-11-24

---

## 1. End-to-End Audio Pipeline Architecture

HiFiDeck uses Media3 ExoPlayer (`androidx.media3:media3-exoplayer:1.9.2`) inside `HiFiPlaybackService` (`app/src/main/java/com/example/mdownload/player/playback/HiFiPlaybackService.kt`) with a custom `DefaultRenderersFactory` that installs `ReplayGainAudioProcessor` into `DefaultAudioSink`:

```
Audio File (content:// or file://)
  └─> ExoPlayer MediaCodecAudioRenderer
       └─> DefaultAudioSink
            ├─> ReplayGainAudioProcessor (PCM_16BIT / PCM_FLOAT gain + 20 ms linear ramp)
            │    (controlled by PlaybackGainCoordinator)
            └─> Android AudioTrack (scaled by ExoPlayer player.volume fallback when !isProcessingPcm)
                 └─> AudioDeviceInfo (Speaker / BT / USB / Wired)
```

### Gain Authority Design
`PlaybackGainCoordinator` (`app/src/main/java/com/example/mdownload/player/playback/PlaybackGainCoordinator.kt`) is designed as the single gain authority combining:
- **ReplayGain linear factor** computed by `ReplayGainCalculator.compute(...)` (`app/src/main/java/com/example/mdownload/player/audio/ReplayGainCalculator.kt`)
- **Sleep Timer fade-out factor** (`0.0f..1.0f`) supplied by `SleepTimerController` (`app/src/main/java/com/example/mdownload/player/playback/SleepTimerController.kt`)

Effective linear gain: `effective = replayGainLinear * sleepFadeFactor`.

---

## 2. Phase 5B — ReplayGain & DSP Correctness Audit

### 2.1 Verified Correct Components

| Component | File | Assessment | Evidence |
|---|---|---|---|
| **dB ↔ Linear Math** | `ReplayGainCalculator.kt` | **VERIFIED** | `10^(dB / 20)` and `20 * log10(linear)`; unit-tested (`replayGain_dbToLinearAndBackAreAccurate`). |
| **Mode Selection & Fallback** | `ReplayGainCalculator.kt` | **VERIFIED** | `OFF` → unity (`1.0f`); `TRACK` prefers track gain (falls back to album); `ALBUM` prefers album gain (falls back to track); missing tag applies `missingTagPreampDb`. Verified in `replayGain_modeSelectionAndAlbumFallback` and `replayGain_missingMetadataAndClippingPrevention`. |
| **Sample-Peak Clipping Prevention** | `ReplayGainCalculator.kt` | **VERIFIED** | When `preventClipping = true` and `peak > 0f`, caps linear gain at `1.0f / peak` (or `min(desired, 1f/peak)`). Tested across boost and attenuation cases. |
| **Opus R128 Q7.8 Conversion** | `ReplayGainMetadataReader.kt`, `ReplayGainCalculator.kt` | **VERIFIED** | Correctly converts RFC 7845 Q7.8 fixed-point `R128_TRACK_GAIN` / `R128_ALBUM_GAIN` from `-23 LUFS` reference to `-18 LUFS` ReplayGain reference (`q78 / 256.0 + 5.0` dB). Tested in `replayGain_parsesOpusR128Q78AndEbuR128ScannerOutput`. |
| **PCM16 / PCM_FLOAT Scaling & Soft Clamp** | `ReplayGainAudioProcessor.kt` | **VERIFIED (on synthetic PCM16)** | Scales `ENCODING_PCM_16BIT` (with `Short.MIN_VALUE..Short.MAX_VALUE` clamping) and `ENCODING_PCM_FLOAT` (with `-1.0f..1.0f` clamping). Verified on live vivo X200s via `replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade`. |
| **20 ms Linear Gain Ramp** | `ReplayGainAudioProcessor.kt` | **VERIFIED** | Smooths per-sample gain steps over `~20 ms` (`sampleRate * 0.02`) across interleaved channels to avoid zipper clicks during live preamp or sleep-fade updates. |

---

### 2.2 Confirmed High-Risk Defects in ReplayGain & DSP

#### **[P0 / AUD-01] Double ReplayGain Attenuation Race on PCM Configuration Transition**
- **Files:** `PlaybackGainCoordinator.kt` (`reapplyLocked()`), `ReplayGainAudioProcessor.kt` (`onConfigure()`, `onReset()`)
- **Mechanism:**
  1. When a track starts or `PlaybackGainCoordinator.reapplyLocked()` executes *before* ExoPlayer calls `ReplayGainAudioProcessor.onConfigure()`, `audioProcessor.isProcessingPcm` is `false`.
  2. `reapplyLocked()` stages `audioProcessor.setTargetLinearGain(effective)` **AND** enters the non-PCM fallback branch, setting `player.volume = effective.coerceIn(0f, 1f)`.
  3. Moments later, ExoPlayer decodes the first audio frames and invokes `ReplayGainAudioProcessor.onConfigure()`, which flips `isProcessingPcm = true`.
  4. **Missing callback:** `ReplayGainAudioProcessor` has **no listener or callback** to notify `PlaybackGainCoordinator` when `isProcessingPcm` changes from `false → true` (or `true → false` on `onReset()`).
  5. **Result:** ExoPlayer runs **both** `ReplayGainAudioProcessor` (`effective`) **and** `player.volume` (`effective`) simultaneously! A `-6.0 dB` (`0.501`) ReplayGain adjustment becomes `-12.0 dB` (`0.251`) until the user manually changes settings or transitions to another track where `isProcessingPcm` happens to already be `true`.
  6. Conversely, if playback switches from PCM to an offloaded/passthrough format (`onReset()` sets `isProcessingPcm = false`), `player.volume` remains `1.0f` and ReplayGain silently stops applying.

#### **[P0 / AUD-01b] Unsynchronized Cross-Thread Access to `ReplayGainAudioProcessor.currentGain`**
- **File:** `ReplayGainAudioProcessor.kt`
- **Mechanism:** `targetGain` is `@Volatile`, but `currentGain` is a plain `Float` field. `queueInput()`, `onFlush()`, and `onReset()` read/write `currentGain` on ExoPlayer's internal audio thread, while `snapToTargetGain()` writes `currentGain = targetGain` from caller threads (Main / Coordinator lock). Under the Java Memory Model on ARM64 (`arm64-v8a`), this is a data race that can cause torn visibility or missed snaps on track switch.

#### **[P1 / AUD-03] Unity-Gain Bypass Claim Contradicted (`isActive()` Never Overridden)**
- **File:** `ReplayGainAudioProcessor.kt`
- **Claimed in:** `FEATURE_EXPANSION_DESIGN.md` & `AUDIO_PIPELINE.md` ("bypasses processing when ReplayGain is OFF and gain == 1.0f").
- **Actual Code:** `ReplayGainAudioProcessor` extends `BaseAudioProcessor` and never overrides `isActive()` to return `false` when `targetGain == 1.0f && currentGain == 1.0f`. Even with ReplayGain `OFF` and no sleep timer active, ExoPlayer routes every PCM buffer through `queueInput()`, allocating/copying samples on the audio thread.
- **Note:** IDE static analysis also flags `onFlush()` (`ReplayGainAudioProcessor.kt:88`) as overriding a deprecated Media3 method.

#### **[P1 / AUD-04] Stale ReplayGain Applied Across Track Transitions**
- **File:** `HiFiPlaybackService.kt` (`applyReplayGainForMediaItem`)
- **Mechanism:** On `onMediaItemTransition`, if the new track is not already in `AudioAnalysisRepository`'s in-memory cache, `applyReplayGainForMediaItem` launches an asynchronous `Dispatchers.IO` coroutine to read ReplayGain tags from disk. Until that I/O completes (which can take 50–300 ms if copying a large `content://` file to `cacheDir`), **the previous track's ReplayGain factor remains active on the new track**, followed by an audible loudness step mid-intro.

#### **[P2 / AUD-05] Cache Key Mismatch & Missing Tag-Only Caching**
- **Files:** `AudioAnalysisRepository.kt` (`resolveReplayGain()`), `MediaItemMapper.kt`
- **Mechanism:**
  1. `AudioAnalysisRepository.resolveReplayGain()` caches full inspection results (`inspectTrack()`), but **never caches tag-only ReplayGain lookups**. Every time a track is played without having been opened in Audio Inspector, `ReplayGainMetadataReader` re-copies 2–32 MiB from `content://` to `cacheDir` to re-parse tags.
  2. `MediaItemMapper` constructs Media3 `MediaItem` extras without `dateModifiedSec`, while `AudioAnalysisRepository` keys cache entries by `"$uri|$sizeBytes|$dateModifiedSec"`. Background playback in `HiFiPlaybackService` therefore misses cache entries populated by UI inspection.

#### **[P2 / AUD-06] Container Tag-Reader Size Caps Truncate Large Files**
- **File:** `ReplayGainMetadataReader.kt`
- **Mechanism:**
  - **MP4/M4A:** Caps `content://` staging copy at **32 MiB**. Lossless ALAC `.m4a` tracks frequently exceed 32 MiB and store the `moov` metadata atom at the **end of the file** (non-faststart). Truncating at 32 MiB makes `moov` unreadable, causing silent ReplayGain misses.
  - **Ogg/Opus:** Caps read at **512 KiB / 16 pages**. Ogg files with embedded `METADATA_BLOCK_PICTURE` artwork exceeding 512 KiB split the Vorbis/Opus comment packet across >16 pages, causing ReplayGain and `R128_*` tags after the artwork to be missed.

#### **[P2 / AUD-07] Offload & Output Routing Observability Gaps**
- **Files:** `HiFiPlaybackService.kt`, `AudioInspectorEngine.kt`, `MainViewModel.kt`
- **Observations:**
  - `HiFiPlaybackService.kt:110` uses deprecated `setEnableAudioTrackPlaybackParams` (flagged by IDE analyzer).
  - `AudioInspectorEngine.queryActiveAudioOutputDevice()` iterates `AudioManager.getDevices(GET_DEVICES_OUTPUTS)` using a static priority order (Bluetooth → USB → Wired → Speaker) instead of Android 13+ (API 33) `AudioManager.getAudioDevicesForAttributes(AudioAttributes)`, which actually reflects the active routed device (e.g., if BT is connected for calls only while music plays on USB DAC or speaker).
  - `MainViewModel.buildPlaybackOutputSnapshot()` hardcodes `audioOffloadStatus` strings instead of querying actual `AudioSink` / `TrackSelection` offload state.
  - `MainViewModel.resetPreferences()` resets `AppSettings` to defaults in `SettingsRepository`, but **forgets to push reset ReplayGain settings to `PlaybackGainCoordinator` or cancel `SleepTimerController`**.

---

## 3. Phase 5C — Sleep Timer Reliability Audit

**Files:** `SleepTimerController.kt`, `SleepTimerMath.kt`, `SleepTimerModels.kt`, `HiFiPlaybackService.kt`  
**Device Verification:** `MusicPlayerInstrumentedTest#sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel` **PASSED** on vivo X200s (`4.953s` batch).

### 3.1 Verified Capabilities
- **Duration mode (`DURATION`)**: Monotonic countdown based on `SystemClock.elapsedRealtime()` (immune to wall-clock adjustments during active process).
- **End-of-Track mode (`END_OF_TRACK`)**: Monitors current track remaining duration for fade-out and pauses cleanly on media item transition.
- **Extension (`+5m` / `+10m` / `+15m`)**: Extends deadline cleanly and restores `sleepFadeFactor` if already fading.
- **Cancellation**: Restores `sleepFadeFactor = 1.0f` and clears persisted timer state.
- **Persistence**: Saves wall-clock deadline (`epochMs`) to JSON so timer survives service recreation.

### 3.2 Confirmed Defects in Sleep Timer

| Severity | ID | Defect | Root Cause & Impact |
|---|---|---|---|
| **P1** | **AUD-02a** | **Audible Volume Spike at Sleep Timer Expiration** | In `SleepTimerController.expireAndPause()`, the code invokes `player.pause()` and **immediately** calls `gainCoordinator.resetSleepFade()` (`1.0f`). Because `player.pause()` is asynchronous and the Android `AudioTrack` / ExoPlayer DSP buffer still holds ~50–200 ms of queued audio (or ramps instantaneously before pause settles), the user hears a brief **loud volume burst** right after fading down to silence. |
| **P1** | **AUD-02b** | **`END_OF_QUEUE` Fails Under Repeat Modes** | `END_OF_QUEUE` checks `!player.hasNextMediaItem()` to trigger fade-out and pause. Under `REPEAT_MODE_ALL`, `hasNextMediaItem()` is **always `true`** (ExoPlayer wraps last track → track 0), so fade-out never triggers and playback either wraps to track 0 or never pauses; under `REPEAT_MODE_ONE`, `hasNextMediaItem()` stays `true` indefinitely and the timer **never expires**. |
| **P2** | **AUD-02c** | **Frozen Countdown After Restoration Without Active Player** | `SleepTimerController.init` calls `restoreIfStillValid()`, restoring `DURATION` state to `StateFlow`, but does **not** start `startTickerLoop()` until `attachPlayer()` is invoked. If the UI opens before `HiFiPlaybackService` attaches a player, the countdown displays a frozen time until playback starts. |
| **P2** | **AUD-02d** | **Main-Thread Synchronous Disk I/O Inside `synchronized(lock)`** | `SleepTimerController.persistState()` performs synchronous JSON file write while holding `lock` on the Main Thread (called on start, extend, cancel, and expire). |
| **P2** | **AUD-02e** | **`updateFadeOutSeconds()` Not Persisted Across Restart** | Changing the sleep timer fade-out duration updates in-memory state only (not written back to `SleepTimerController`'s persisted state if a timer is active). |

---

## 4. EBU R128 Loudness Scanner & Audio Quality Inspector Audit

**Files:** `LoudnessScanner.kt`, `AudioInspectorEngine.kt`, `AudioAnalysisRepository.kt`  
**Device Verification:** `MusicPlayerInstrumentedTest#audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly` **PASSED** on vivo X200s against a real track from the 153-track library.

### 4.1 Strengths
- **Truthful codec reporting:** Parses FLAC `STREAMINFO` (exact sample rate, channels, bit depth), WAV `RIFF`/`fmt ` headers, and MP3 MPEG frame headers (distinguishing CBR vs. VBR/Xing without fabricating bit depth).
- **Honest transcode caveat:** Flags FLAC files downloaded by HiFiDeck (`MDOWNLOAD_YT_ID` / `isDownloadedByApp`) so users know the source came from lossy YouTube audio transcoded to FLAC.
- **EBU R128 parsing:** Correctly parses FFmpeg `ebur128` integrated loudness (`I:`) and true peak (`Peak:`), converting to `-18 LUFS` ReplayGain target (`-18.0 - integratedLufs`).

### 4.2 Confirmed Reliability & Performance Defects

| Severity | ID | File & Method | Issue |
|---|---|---|---|
| **P1** | **AUD-08** | `AudioInspectorEngine.kt` (`probeWithFfprobe`) | Sets `ProcessBuilder.redirectErrorStream(false)` and reads **only `process.inputStream.bufferedReader().readText()`** **before** calling `process.waitFor(timeout)`. Two compounding bugs: (1) If `ffprobe` writes >64 KB to `stderr` (verbose warnings on corrupt files), the stderr OS pipe fills and **deadlocks indefinitely**; (2) because `readText()` blocks until EOF before `waitFor(timeout)` is ever reached, the **timeout never fires** if `ffprobe` hangs. |
| **P1** | **AUD-09** | `LoudnessScanner.kt` | Spawns `libffmpeg.so -filter_complex ebur128=peak=true` with **no `waitFor(timeout)` watchdog**, risking runaway CPU/battery drain on malformed streams or stuck subprocesses. |
| **P2** | **AUD-10** | `AudioInspectorEngine.kt` (`inspectTrack`) | Copies the **entire `content://` audio file** into `cacheDir` on every uncached inspection (e.g., 40–100 MB for a single FLAC album track), incurring heavy flash I/O and temporary storage spikes even when only headers are needed. |
| **P2** | **AUD-11** | `AudioAnalysisRepository.kt` | In-memory & on-disk JSON analysis cache has **no LRU cap or eviction policy**; over thousands of tracks or edits, `audio_analysis_cache.json` grows unbounded and is rewritten in full on each miss. |

---

## 5. Summary Verdict — Audio Pipeline

| Area | Verdict | Blocker for Production? |
|---|---|---|
| ReplayGain math & tag parsing (ID3v2/FLAC/Opus) | **Solid** (except >32 MiB MP4 & >512 KiB Ogg caps) | No |
| ReplayGain DSP ↔ `player.volume` coordination | **Broken on PCM transition (`AUD-01`)** + `currentGain` race | **YES (P0)** |
| Unity-gain bypass | **Missing** (contradicts design docs) | Medium (P1 perf/battery) |
| Sleep Timer core countdown & extend/cancel | **Verified on vivo X200s**, but expiry spike + `END_OF_QUEUE` repeat bug | **YES (P1 UX)** |
| Audio Inspector & EBU R128 Scanner | **Functional on clean tracks**, `ffprobe` deadlock/timeout bug + full-file copy | **YES (P1 reliability)** |

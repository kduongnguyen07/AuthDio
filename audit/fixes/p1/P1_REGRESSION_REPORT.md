# HiFiDeck Android — P1 Regression & Verification Report

**Date:** 2026-11-24  
**Repository:** `D:\MDownload` (`com.example.mdownload`, `compileSdk 37`, `minSdk 35`, `targetSdk 37`)  
**Validation Device:** vivo X200s (`V2458A`, serial `10AF8A0YUH005HA`, OriginOS 7, Android 17 / API 37, `arm64-v8a`)

---

## 1. Build & Compilation Summary

| Gradle Command | Result | Notes |
| :--- | :--- | :--- |
| `:app:testDebugUnitTest --rerun-tasks :app:assembleDebug :app:assembleDebugAndroidTest` | **BUILD SUCCESSFUL** | **112 passed, 0 skipped, 0 failed**; 0 compiler errors; 0 XML backup lint errors |
| `:app:assembleDebugAndroidTest` (final test update) | **BUILD SUCCESSFUL** | Clean APK installed on vivo X200s |

---

## 2. JVM Unit Test Ledger (`112 / 112 PASSED`)

| Suite Category | Baseline (Pre-P1) | P1 Current | Delta | Result |
| :--- | :---: | :---: | :---: | :--- |
| Pre-existing + P0 Unit Tests (Audio, Metadata, Lyrics, ReplayGain, SleepTimer, Orbital, CircularScrub, Parsers) | 89 | 89 | 0 | **89 / 89 PASS** |
| New `STAB-04` Security & Subprocess Tests (`SecurityAndSubprocessP1Test.kt`) | 0 | 18 | +18 | **18 / 18 PASS** |
| New `STAB-03` Circular Scrub & WCAG Contrast Tests (`CircularScrubAndUiP1Test.kt`) | 0 | 5 | +5 | **5 / 5 PASS** |
| **Total JVM Unit Tests (`:app:testDebugUnitTest --rerun-tasks`)** | **89** | **112** | **+23** | **112 / 112 PASS (0 skipped, 0 failed)** |

---

## 3. On-Device Instrumented Test Ledger on vivo X200s (`21 / 21 PASSED`)

### A. Preserved P0 + Baseline Instrumented Suite (`14 / 14 PASSED`, `9.505s`)
Command:
```text
adb -s 10AF8A0YUH005HA shell am instrument -w -e class com.example.mdownload.ExampleInstrumentedTest,com.example.mdownload.MusicPlayerInstrumentedTest,com.example.mdownload.P0StabilizationInstrumentedTest com.example.mdownload.test/androidx.test.runner.AndroidJUnitRunner
```
Output:
```text
com.example.mdownload.ExampleInstrumentedTest:.
com.example.mdownload.MusicPlayerInstrumentedTest:..........
com.example.mdownload.P0StabilizationInstrumentedTest:...

Time: 9.505

OK (14 tests)
```

### B. New P1 UI, Accessibility & Phase C Instrumented Suite (`7 / 7 PASSED`, `12.108s`)
Command:
```text
adb -s 10AF8A0YUH005HA shell am instrument -w -e class com.example.mdownload.P1UiAndAccessibilityInstrumentedTest com.example.mdownload.test/androidx.test.runner.AndroidJUnitRunner
```
Output:
```text
com.example.mdownload.P1UiAndAccessibilityInstrumentedTest:.......

Time: 12.108

OK (7 tests)
```

Test methods executed and passed on vivo X200s:
1. `ui01_circularAlbumArtwork_allNineAnglesIncludingDiagonalsAndOuterHaloCommitSeek` — **PASS**
2. `ui01_innerArtworkLongPressTriggersOrbital_andMultiTouchAndTrackSwapCancelScrub` — **PASS**
3. `ui02_allFourBottomSheets_renderCleanlyInBothLightAndDarkThemes` — **PASS**
4. `ui03_circularSeekRing_semanticsSetProgressAndCustomActionsAndUnknownDuration` — **PASS**
5. `ui06_hiFiGlassTextField_exposesContentDescriptionAnd48dpClearTarget` — **PASS**
6. `ui07_remediatedControls_allMeetMinimum48dpTouchTargetBounds` — **PASS**
7. `phaseC_safeAudioWritePipeline_realPendingWritesDirRecoveryUnaffectedByBackupExclusions` — **PASS**

### C. Platform Observation Noted During Instrumented Execution
- On vivo OriginOS 7 (Android 17), `ActivityScenario.launch(ComponentActivity::class.java)` (used internally by `createComposeRule()`) hangs waiting for Espresso's `BootstrapActivity` due to OriginOS background-activity launch restrictions. Using `createEmptyComposeRule()` paired with `UiAutomation.executeShellCommand("am start -W -a android.intent.action.MAIN -n com.example.mdownload/androidx.activity.ComponentActivity")` resolved this deterministically (all 7 Compose tests completed in `12.108s`).
- `LiveDownloadTest` (which performs live external network downloads) was not included in the offline stabilization regression run, matching the P0 14-test baseline.
- Real user music files on vivo X200s were **never** modified; all write/recovery tests used disposable FFmpeg-generated fixtures in `cacheDir` cleaned up in `finally` blocks.

---

## 4. P0 Preservation Verification Matrix

| P0 Guarantee | Verification Method | Status |
| :--- | :--- | :--- |
| `SafeAudioWritePipeline` recovery journaling & crash restoration | `P0StabilizationInstrumentedTest` + Phase C `P1UiAndAccessibilityInstrumentedTest` (real `filesDir/hifideck_data/pending_writes/`) | **PRESERVED & PASS** |
| Shared binary `AudioContainerPrimitives` (MP3/FLAC/M4A/Ogg audio SHA-256 + tag/ReplayGain/lyrics preservation + Ogg artwork rejection) | `P0StabilizationInstrumentedTest.realFfmpeg_mp3FlacM4aOggMetadataAndLyricsMatrixPreservesAudioAndReplayGain` | **PRESERVED & PASS** |
| ReplayGain single-authority DSP handoff (`player.volume == 1.0f` when PCM DSP active, zero double attenuation) | `P0StabilizationInstrumentedTest.realExoPlayer_replayGainSingleAuthorityZeroDoubleAttenuationAndSleepTimerExpiry` | **PRESERVED & PASS** |
| Audio-thread-safe gain processing & Sleep Timer fade restoration without volume spike | `P0StabilizationInstrumentedTest` + `MusicPlayerInstrumentedTest` + JVM unit tests | **PRESERVED & PASS** |
| Logical queue-end detection & live MediaSession metadata sync (`replaceMediaItem` without playback restart) | `P0StabilizationInstrumentedTest` + JVM unit tests | **PRESERVED & PASS** |

---

## 5. Live App Verification & Logcat Health (vivo X200s)

- Cold launch `MainActivity`: `TotalTime: 772ms` (`Status: ok`).
- Live `HomeScreen` XML (`studio_ui_state`): `EditText` exposes `content-desc="Audio or YouTube URL"` with **0 `NAF="true"`** nodes; Paste button `158 x 158 px (48.0 dp)`.
- Live `NowPlayingScreen` XML (`studio_ui_state`): `CircularSeekRing` exposed as `android.widget.SeekBar` (`content-desc="Playback progress"`, unclipped `1230 x 1230 px`); `Shuffle`, `Previous`, `Next`, `Repeat off`, and `Orbital navigation` all `158 x 158 px (48.0 dp)`; `MiniPlayer` Play/Pause & Next `158 x 158 px (48.0 dp)`.
- Live `AudioInspectorSheet` framebuffer RGB sampling: `#1A1725` (Dark) vs `#FAF8FD` (Light). Device restored to original Dark theme after validation.
- `studio_read_logcat` (`com.example.mdownload`): **0 fatal exceptions, 0 unhandled errors, 0 ANRs** during live verification.

# HiFiDeck — Independent Validation & Evidence Report

**Audit Scope:** Complete reproducible log of every command, build, unit test, live vivo X200s instrumented test, performance measurement, UI inspection, static analysis check, and Phase 2 claim verdict executed during this audit.  
**Audit Date:** 2026-11-24  
**Repository Integrity:** Read-only audit — **zero** production, test, or Gradle files modified.

---

## 1. Test Environment & Device Fingerprint

| Property | Verified Value | Command / Source |
|---|---|---|
| **Host OS / Repo** | Windows, `D:\MDownload` | Workspace metadata |
| **App Package / Version** | `com.example.mdownload`, `versionName = "1.1"`, `versionCode = 2` | `app/build.gradle.kts` |
| **SDK Config** | `compileSdk = 37`, `minSdk = 35`, `targetSdk = 37` | `app/build.gradle.kts` |
| **Connected Device Serial** | `10AF8A0YUH005HA` | `adb devices -l` |
| **Device Model / Product** | vivo X200s (`V2458A`, product `PD2415M`) | `adb shell getprop` |
| **OS / API Level** | `OriginOS 7`, **Android 17 (API 37)** | `adb shell getprop` |
| **Supported Device ABIs** | `arm64-v8a` only (64-bit-only SoC) | `adb shell getprop ro.product.cpu.abilist` |
| **Display Geometry** | `1260×2800` physical @ `560` physical / `525` override dpi (`~384×853 dp`) | `adb shell wm size` & `wm density` |
| **MediaStore Audio Library** | **153 audio tracks** | `adb shell content query --uri content://media/external/audio/media --projection _id` |
| **Runtime Permissions** | `POST_NOTIFICATIONS`: granted, `READ_MEDIA_AUDIO`: granted | `adb shell dumpsys package com.example.mdownload` |
| **Battery Whitelist** | `NOT_WHITELISTED` | `adb shell dumpsys deviceidle whitelist` |

---

## 2. Build & JVM Unit Test Verification

### Command Executed
`studio_gradle_build(":app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks")`

### Result
- **Build Status:** **SUCCESS**
- **`:app:testDebugUnitTest`:** **71 passed, 0 skipped, 0 failed** (verified across all 9 test files; see `11_TEST_COVERAGE_MATRIX.md` for per-method list).
- **Artifacts Built:**
  - `app/build/outputs/apk/debug/app-debug.apk` (**264.53 MB**)
  - `app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`

---

## 3. Live On-Device Instrumented Test Verification (vivo X200s)

### Installation
- Installed `app-debug.apk` + `app-debug-androidTest.apk` via `adb install -r -t` → **Success in `39.17 s`**.

### Test Batch A — Previously Unexecuted Device Integration Tests (2/2 PASS)
- **Command:** `adb shell am instrument -w -e class com.example.mdownload.MusicPlayerInstrumentedTest#sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel,com.example.mdownload.MusicPlayerInstrumentedTest#audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly com.example.mdownload.test/androidx.test.runner.AndroidJUnitRunner`
- **Result:** **`OK (2 tests)` in `4.953 s`**
  - `sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel`: **PASS**
  - `audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly`: **PASS** (inspected a real track from the 153-track library)

### Test Batch B — Previously Reported 9 Instrumented Tests (9/9 PASS)
- **Command:** `adb shell am instrument -w -e class com.example.mdownload.ExampleInstrumentedTest,com.example.mdownload.MusicPlayerInstrumentedTest#playbackStateStore_roundTripsQueuePositionAndModes,com.example.mdownload.MusicPlayerInstrumentedTest#playlistStore_roundTripsPlaylistsAtomically,com.example.mdownload.MusicPlayerInstrumentedTest#mediaItemMapper_preservesFullTrackMetadataAndExtras,com.example.mdownload.MusicPlayerInstrumentedTest#manifest_exposesMediaSessionServiceAndMediaButtonReceiver,com.example.mdownload.MusicPlayerInstrumentedTest#realDevicePlayback_exercisesMedia3ServiceQueueControlsAndMetadata,com.example.mdownload.MusicPlayerInstrumentedTest#artistStore_roundTripsUtf8NamesCoversOverridesAndAliases,com.example.mdownload.MusicPlayerInstrumentedTest#listeningHistoryAndSmartPlaylistRepos_roundTripPersistAtomically,com.example.mdownload.MusicPlayerInstrumentedTest#replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade com.example.mdownload.test/androidx.test.runner.AndroidJUnitRunner`
- **Result:** **`OK (9 tests)` in `6.587 s`** (all 9 PASS, including live Media3 service playback & PCM16 processor test).

### Unexecuted / Blocked Instrumented Tests (Honest Disclosure)
- `SettingsDeviceTest#persistenceAndCleanupProtectPendingAudio`: Attempted via `am instrument`; **stalled** during `createAndroidComposeRule<MainActivity>()` Activity launch/resume on vivo X200s and was terminated cleanly (`am force-stop`).
- `SettingsDeviceTest#settingsSectionsThemeAndConfirmation`: Shares the same stalled Compose rule; not re-run.
- `LiveDownloadTest#downloadExportAndPlayback` & `SettingsDeviceTest#storageCanRecoverAndVerifyRealAudio`: **Intentionally NOT executed** (opt-in `liveDownload=true` tests that perform external network downloads).

---

## 4. Live Performance & UI Hierarchy Measurements (vivo X200s)

| Measurement | Exact Method | Observed Output |
|---|---|---|
| **Cold Start (3 Trials)** | `am force-stop com.example.mdownload` + `am start -W -n com.example.mdownload/.MainActivity` | Trial 1: `TotalTime=779ms`, `WaitTime=781ms`<br>Trial 2: `TotalTime=711ms`, `WaitTime=713ms`<br>Trial 3: `TotalTime=740ms`, `WaitTime=741ms`<br>**Median: `740 ms`** (debug build) |
| **Memory Footprint** | `dumpsys meminfo com.example.mdownload` (3s post-launch idle on Home) | `TOTAL PSS: 192,588 KB` (~188.1 MB), `TOTAL RSS: 341,508 KB` (~333.5 MB), `Swap PSS: 4,543 KB`; Code `76,176 KB`, Graphics `34,704 KB`, Native Heap `30,126 KB`, Dalvik Heap `10,693 KB` |
| **Frame Timing** | `dumpsys gfxinfo com.example.mdownload` | `13` frames rendered, `4` janky (`30.77%`), `50th: 16ms`, `90th/95th/99th: 250ms` |
| **APK `.so` ABI Breakdown** | Zip inspection of `app-debug.apk` | Total APK `264.53 MB`; `.so` total `186.72 MB` compressed (`190.96 MB` raw): `arm64-v8a` `47.89 MB`, `x86_64` `50.75 MB`, `armeabi-v7a` `41.52 MB`, `x86` `46.56 MB` (**`138.83 MB` / 52.5% non-arm64 bloat**) |
| **Live UI Tree** | `studio_ui_state` on `MainActivity` Home (`1260×2800`) | Bottom dock `y=2494..2704` clears 96px gesture bar (`2704..2800`); top title `HiFiDeck` at `y=246`; `yt-dlp ready` visible; Home URL `EditText` flagged **`NAF="true"`** |

---

## 5. Android Studio IDE Static Analysis (`studio_analyze_file`)

Analyzed 8 high-risk production source files:

| File | Errors | Key Warnings Observed |
|---|---:|---|
| `PlaybackGainCoordinator.kt` | 0 | Clean |
| `ReplayGainAudioProcessor.kt` | 0 | Line 88: deprecated `onFlush()` override |
| `MetadataTagIO.kt` | 0 | **Line 654: `Parameter 'file' is never used` in `readMp4BasicMetadata(file)`** (confirmed dead read-back stub!) |
| `SleepTimerController.kt` | 0 | Clean |
| `MainViewModel.kt` | 0 | Lines 1378 & 2056: unused `isTrackFavorite`, `evaluateSmartPlaylist` |
| `NowPlayingScreen.kt` | 0 | Minor Compose/API warnings |
| `SmartPlaylistEvaluator.kt` | 0 | Clean |
| `HiFiPlaybackService.kt` | 0 | Line 110: deprecated `setEnableAudioTrackPlaybackParams`; Line 212: non-exhaustive `DISCONTINUITY_REASON_*` branches |

---

## 6. Phase 2 Claim Verification Verdict Ledger

| Prior Claim (`FEATURE_VALIDATION_REPORT.md` / `AUDIO_PIPELINE.md` / `FEATURE_EXPANSION_DESIGN.md`) | Audit Verdict | Evidence Summary |
|---|---|---|
| **71 passing JVM unit tests** | **VERIFIED** | Independently re-executed `:app:testDebugUnitTest --rerun-tasks`: 71/71 PASS. |
| **9 passing on-device instrumented tests** | **VERIFIED** | Independently re-executed on vivo X200s: 9/9 PASS in `6.587s`. |
| **2 additional device integration tests compiled but not executed** | **VERIFIED & NOW EXECUTED (PASS)** | Both `sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel` and `audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly` executed on vivo X200s: **2/2 PASS in `4.953s`**. |
| **153-track music library on vivo X200s** | **VERIFIED** | `content query --uri content://media/external/audio/media --projection _id` returned exactly **153 rows**. |
| **Single-authority ReplayGain + Sleep Timer gain coordination** | **PARTIALLY VERIFIED (P0 Defect)** | Architecture exists and passes static PCM16 test, but `PlaybackGainCoordinator` has a double-attenuation race when `isProcessingPcm` flips `false → true` (`AUD-01`) and `SleepTimerController.expireAndPause()` resets fade before AudioTrack drains (`AUD-02a`). |
| **Unity-gain bypass in `ReplayGainAudioProcessor`** | **CONTRADICTED** | `isActive()` is never overridden; copies PCM frames even when ReplayGain is OFF & sleep fade = `1.0f` (`AUD-03`). |
| **Native Kotlin MP4 `stco`/`co64` chunk-offset writer & Ogg page CRC writer** | **CONTRADICTED** | Neither exists in `MetadataTagIO.kt`. Both MP4 and Ogg shell out to `libffmpeg.so -c copy`. |
| **Audio payload SHA-256 integrity verification across formats** | **PARTIALLY VERIFIED / CONTRADICTED FOR MP4 & OGG** | Verified for MP3 & FLAC only; `computeAudioPayloadSha256()` returns `null` (skipped) for MP4/M4A & Ogg/Opus (`META-02c/d`). |
| **Safe tag preservation & read-back across MP3/FLAC/MP4/Ogg** | **CONTRADICTED** | ID3v2.4 unsync flag corrupts frames; ID3v2.2 wipes all tags; MP4 drops ReplayGain (`-movflags use_metadata_tags` omitted) & `readMp4BasicMetadata()` is a dead stub; Ogg silently drops artwork edits (`META-02`). |
| **Non-destructive scoped storage metadata writes** | **CONTRADICTED** | Both `MetadataRepository` and `LyricsTagIO` open `content://` URIs with `"wt"` (immediate 0-byte truncation with no rollback copy; `META-01`). |
| **Disjoint Circular Seek ring & inner artwork touch zones** | **PARTIALLY VERIFIED (UI Defect)** | Polar math config is disjoint, but Compose `clip(CircleShape)` does not clip pointer hit-testing → 4 diagonal dead zones (`UI-01`). |
| **Light & Dark theme parity across screens & sheets** | **CONTRADICTED** | 4 modal sheets hardcode dark `BezelMid` (`#1B2132`) with dark Light-Mode ink (`#181223`) (`UI-02`). |

---

## 7. Explicit Audit Limitations & Caveats

1. **No Pixel-Level Screenshot Verification:** Screenshot PNG bytes were offloaded to disk by the tool bridge and not visually rendered in context; UI/UX findings are grounded exclusively in Compose code and live Android UI hierarchy XML (`studio_ui_state`).
2. **Debug Build Performance Baseline:** Cold start (`740 ms` median), memory (`188.1 MB` PSS), and initial jank (`30.77%`) were measured on `app-debug.apk`. Release R8 builds will be faster and smaller in DEX/JIT footprint, though the `138.83 MB` ABI waste and 5 synchronous startup JSON reads affect release builds equally.
3. **No Live Perfetto / CPU Flamegraphs:** Orbital drag frame pacing and concurrent download + playback + EBU R128 thermal/CPU behavior were audited via static code inspection rather than live Perfetto traces.
4. **No Real Music Files Altered & No Live Network Downloads Executed:** Per strict audit safety rules, `LiveDownloadTest` and `SettingsDeviceTest#storageCanRecoverAndVerifyRealAudio` were skipped, and zero user audio files on the vivo X200s were written.

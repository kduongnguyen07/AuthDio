# HiFiDeck Android — STAB-05 Regression & Verification Report

**Date:** 2026-11-24  
**Repository:** `D:\MDownload` (`com.example.mdownload`)  
**Target Device:** vivo X200s (`10AF8A0YUH005HA`, OriginOS 7, Android 17 / API 37, `arm64-v8a`)

---

## 1. Build Matrix Verification

| Gradle Command | Outcome | Artifact Verified |
| :--- | :---: | :--- |
| `.\gradlew.bat :app:testArm64DebugUnitTest --rerun-tasks :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest` | **BUILD SUCCESSFUL** | `app-arm64-debug.apk` (`126.04 MB`), `app-arm64-debug-androidTest.apk` |
| `.\gradlew.bat :app:assembleUniversalDebug :app:bundleUniversalRelease` | **BUILD SUCCESSFUL** | `app-universal-debug.apk` (`265.07 MB`), `app-universal-release.aab` (`198.19 MB`) |

---

## 2. Automated Test Suite Results (142 Total Passing, 0 Failed)

### 2.1 JVM Unit Test Suite (`:app:testArm64DebugUnitTest --rerun-tasks`)

**Total: 117 passed, 0 skipped, 0 failed** (up from 112 baseline).

| Test Class | Scope | Status |
| :--- | :--- | :---: |
| `ExampleUnitTest` | Baseline sanity | PASS |
| `CoreDomainTest` | Core models & download domain | PASS |
| `SettingsPolicyTest` | Settings defaults & policies | PASS |
| `MusicPlayerDomainTest` | Queue, repeat, shuffle, formatting | PASS |
| `ArtistManagementTest` | Artist parsing, catalog & overrides | PASS |
| `OrbitalNavigationTest` | Orbital navigation geometry & routing | PASS |
| `CircularScrubTest` | Circular scrub angle math | PASS |
| `LyricsPipelineTest` | Synced LRC parsing, sidecar & tag IO | PASS |
| `AdvancedAudioFeaturesTest` | ReplayGain, Audio Inspector, Smart Playlists, Sleep Timer, History | PASS |
| `MetadataIntegrityP0Test` | P0 atomic writes, SHA-256 audio payload invariance, crash recovery | PASS |
| `AudioGainAndSleepTimerP0Test` | P0 gain arbitration, thread safety, sleep timer fade/edge cases | PASS |
| `SecurityAndSubprocessP1Test` | P1 URL validation, subprocess timeouts, path safety, manifest security | PASS |
| `CircularScrubAndUiP1Test` | P1 360° circular scrubbing & UI math | PASS |
| `SmartPlaylistBenchmarkTest` *(NEW in STAB-05)* | Scale benchmarks `N = 153 / 1,000 / 5,000 / 10,000` + deterministic checksums | PASS |
| `Stab05PerformanceAndPersistenceTest` *(NEW in STAB-05)* | Queue FNV-1a signature, `NotificationUpdateThrottler`, 30 s `ListeningSessionTracker`, `SmartPlaylistEvaluator.evaluateAll` parity | PASS |

### 2.2 On-Device Instrumented Test Suite (vivo X200s, `arm64-v8a`)

**Total: 25 passed, 0 failed** (up from 21 baseline).

| Instrumentation Run | Test Classes | Result | Duration |
| :--- | :--- | :---: | :---: |
| STAB-05 Suite | `Stab05PerformanceInstrumentedTest` (4 tests) | **4 passed, 0 failed** | `2.138 s` |
| Baseline + P0 Suite | `ExampleInstrumentedTest`, `MusicPlayerInstrumentedTest`, `P0StabilizationInstrumentedTest` | **14 passed, 0 failed** | `9.697 s` |
| P1 UI & Accessibility Suite | `P1UiAndAccessibilityInstrumentedTest` (7 tests) | **7 passed, 0 failed** | `11.871 s` |

#### Detailed STAB-05 Instrumented Test Coverage (`Stab05PerformanceInstrumentedTest`)
1. `arm64NativeLibrariesAndToolsInitializeCleanly` (**PASS**): Confirms `app-arm64-debug.apk` ships and initializes `yt-dlp`, `libpython.so`, `libffmpeg.so`, `libffprobe.so`, `libqjs.so`, and `LoudnessScanner`.
2. `asyncStartupHydrationReachesReadyWithoutBlocking` (**PASS**): Confirms all async-hydrated repositories reach `RepositoryLoadState.READY` cleanly.
3. `playbackPositionTicksDoNotRecomposeRootOrSiblings` (**PASS**): Emits 20 live `positionMs` updates and verifies **0** root recompositions and **0** sibling recompositions while updating the leaf subscriber 20 times.
4. `persistenceSplitCursorLegacyCompatDebouncedHistoryLruAndAsyncRace` (**PASS**): Verifies real `org.json` + flash storage behavior on vivo X200s for:
   - `PlaybackStateStore` split cursor persistence + stale signature rejection + pre-STAB-05 legacy JSON compatibility
   - `ListeningHistoryRepository` debounced writes + immediate `flushNow()` + async hydration merge without overwriting early mutations
   - `AudioAnalysisRepository` bounded LRU eviction cap (`maxEntries = 3`)

---

## 3. Feature Smoke & Safety Verification on vivo X200s

| Feature / Invariant | Verification Method | Status |
| :--- | :--- | :---: |
| yt-dlp engine & arm64 native libs | `yt-dlp ready` badge on Home screen + `Stab05PerformanceInstrumentedTest` | **PASS** |
| Local library (154 tracks, 708.0 MB) | Live Library navigation + 3-swipe smooth scroll | **PASS** |
| Media3 playback & Now Playing | Live playback of `BIGBANG - LIES(거짓말) M/V`, spec badge `MP3 · 128 kbps · 44.1 kHz · Stereo`, clean pause | **PASS** |
| Split playback persistence & pause flush | Live `stat` + `cat` of `files/hifideck_data/` over 12 s playback + pause | **PASS** |
| 360° Circular Scrubbing & Accessibility | `P1UiAndAccessibilityInstrumentedTest` (all 9 clock angles, Light/Dark themes, TalkBack semantics) | **PASS** |
| P0 Metadata Integrity & Audio Gain / Sleep Timer | `P0StabilizationInstrumentedTest` + JVM P0 suites | **PASS** |
| P1 Security & Subprocess Hardening | `SecurityAndSubprocessP1Test` + `P1UiAndAccessibilityInstrumentedTest` | **PASS** |
| User media file safety | Zero modifications to user's real audio files on device | **VERIFIED** |

---

## 4. Explicit Test Exclusions (Matching P0/P1 Baseline)

- `LiveDownloadTest` (requires live external YouTube network download — intentionally excluded from offline regression runs).
- `SettingsDeviceTest` (requires interactive Android SAF system document picker interaction — intentionally excluded from automated headless runs).
- No other tests were skipped, disabled, or relaxed.

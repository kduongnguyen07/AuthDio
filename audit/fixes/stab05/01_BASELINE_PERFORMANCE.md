# HiFiDeck Android — STAB-05 Phase Zero Performance Baseline

**Date:** 2026-11-24  
**Repository:** `D:\MDownload` (`com.example.mdownload`, `:app`, AGP `9.5.2`, Kotlin `2.2.10`, Compose BOM `2026.02.01`, Media3 `1.9.2`)  
**Target Hardware:** vivo X200s (`V2458A`, serial `10AF8A0YUH005HA`, OriginOS 7, Android 17 / API 37, `arm64-v8a` only, `1260×2800`, 153 local audio tracks)  
**Measurement Rule:** All measurements below were collected on the **unmodified post-P1 codebase** prior to any STAB-05 production changes.

---

## 1. Pre-STAB-05 Verification Status

| Suite | Command / Scope | Result |
| :--- | :--- | :--- |
| JVM Unit Tests | `:app:testDebugUnitTest --rerun-tasks` | **112 passed, 0 skipped, 0 failed** |
| vivo X200s Baseline + P0 Instrumented | `ExampleInstrumentedTest`, `MusicPlayerInstrumentedTest`, `P0StabilizationInstrumentedTest` | **14 passed, 0 failed** (`9.406 s`) |
| vivo X200s P1 UI & Accessibility Instrumented | `P1UiAndAccessibilityInstrumentedTest` | **7 passed, 0 failed** (`12.142 s`) |
| **Total Verified Baseline Tests** | JVM + vivo X200s | **112 JVM + 21 Instrumented = 133 PASS** |

*(Exclusions matching P0/P1: `LiveDownloadTest` [live network] and `SettingsDeviceTest` [interactive SAF picker] not executed in offline regression run.)*

---

## 2. APK Size & Packaging Baseline (`app-debug.apk`)

**File:** `D:\MDownload\app\build\outputs\apk\debug\app-debug.apk`  
**Total APK Size:** **277,629,885 bytes (264.77 MB)** across `594` ZIP entries.  
**Current `app/build.gradle.kts` configuration:**
- `ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")`
- `packaging.jniLibs.useLegacyPackaging = true` (required by `youtubedl-android` so `libpython.so`, `libqjs.so`, `libffmpeg.so`, `libffprobe.so`, `libffmpeg.zip.so`, and `libpython.zip.so` extract cleanly).

### Top-Level Category Breakdown

| Category | Entries | Compressed Size (MB) | Compressed Bytes | Uncompressed Size (MB) | Share of APK |
| :--- | :---: | :---: | :---: | :---: | :---: |
| `dex` (`classes*.dex`) | 14 | 73.89 MB | 77,483,708 | 73.89 MB | 27.91% |
| `lib/x86_64` | 7 | 50.75 MB | 53,216,822 | 51.94 MB | 19.17% |
| `lib/arm64-v8a` | 7 | 47.89 MB | 50,213,249 | 49.03 MB | 18.09% |
| `lib/x86` | 7 | 46.56 MB | 48,820,905 | 47.70 MB | 17.58% |
| `lib/armeabi-v7a` | 7 | 41.52 MB | 43,542,050 | 42.29 MB | 15.68% |
| `res/` | 450 | 3.15 MB | 3,299,330 | 3.30 MB | 1.19% |
| `resources.arsc` | 1 | 0.83 MB | 870,520 | 0.83 MB | 0.31% |
| `other` | 12 | 0.05 MB | 57,018 | 0.10 MB | 0.02% |
| `META-INF/` | 89 | 0.02 MB | 18,829 | 0.05 MB | 0.01% |

### Native Library (`arm64-v8a`) Inventory (All 7 Required)

| Native Entry (`lib/arm64-v8a/`) | Compressed | Uncompressed | Purpose |
| :--- | :---: | :---: | :--- |
| `libffmpeg.zip.so` | 33.80 MB | 33.97 MB | `youtubedl-android` FFmpeg runtime archive |
| `libpython.zip.so` | 13.41 MB | 13.64 MB | `youtubedl-android` embedded Python runtime archive |
| `libqjs.so` | 0.45 MB | 0.87 MB | QuickJS JS runtime for YouTube `yt-dlp` |
| `libffmpeg.so` | 0.14 MB | 0.30 MB | FFmpeg executable wrapper (conversions, ReplayGain EBU R128 scan, metadata) |
| `libffprobe.so` | 0.08 MB | 0.22 MB | FFprobe executable wrapper (Audio Quality Inspector) |
| `libandroidx.graphics.path.so` | 0.004 MB | 0.01 MB | AndroidX Graphics Path JNI |
| `libpython.so` | 0.002 MB | 0.002 MB | Python launcher binary |

**Key Finding:** Non-`arm64-v8a` ABIs (`armeabi-v7a`, `x86`, `x86_64`) account for **145,579,777 bytes (138.84 MB = 52.44%)** of the debug APK and are never loaded on the `arm64-v8a`-only vivo X200s.

---

## 3. Cold Startup Baseline on vivo X200s (10 Repeatable Runs)

**Methodology:** For each run `i = 1..10`:
1. `adb -s 10AF8A0YUH005HA shell am force-stop com.example.mdownload`
2. Sleep `700 ms`
3. `adb -s 10AF8A0YUH005HA shell am start -W -n com.example.mdownload/.MainActivity`
4. Record `TotalTime` and `WaitTime`, sleep `800 ms`

| Run | `TotalTime` (ms) | `WaitTime` (ms) |
| :---: | :---: | :---: |
| 1 | 733 | 736 |
| 2 | 778 | 781 |
| 3 | 775 | 777 |
| 4 | 741 | 745 |
| 5 | 767 | 769 |
| 6 | 720 | 722 |
| 7 | 733 | 735 |
| 8 | 733 | 736 |
| 9 | 777 | 779 |
| 10 | 771 | 773 |

### Cold Startup Summary

| Metric | Min | Median | p90 | Max |
| :--- | :---: | :---: | :---: | :---: |
| `TotalTime` | 720 ms | **754.0 ms** | **777 ms** | 778 ms |
| `WaitTime` | 722 ms | **757.0 ms** | **779 ms** | 781 ms |

### Verified Synchronous Main-Thread Disk I/O in `MDownloadApp.onCreate()`

On-device inspection of `files/hifideck_data/` (`run-as com.example.mdownload ls -la files/hifideck_data`):

| File | Current On-Device Size | Loaded Synchronously on Main Thread in `onCreate()`? |
| :--- | :---: | :--- |
| `playback_state.json` | 121,691 B (118.8 KB) | Loaded async by `PlaybackController.restoreSavedSession()`, **but rewritten in full every 5 s** by `HiFiPlaybackService` |
| `settings.log` | 51,763 B (50.6 KB) | **YES** — `DownloadQueueManager` init calls `store.readLogs()` on Main Thread |
| `settings.json` | 43,725 B (42.7 KB) | **YES** — `DownloadQueueManager` init calls `store.load()` on Main Thread |
| `listening_history.json` | 6,200 B (6.1 KB) | **YES** — `ListeningHistoryRepository.init` calls `loadFromDisk()` on Main Thread |
| `artists.json` | 1,666 B (1.6 KB) | **YES** — `MusicLibraryRepository` init loads `artistStore.load()` on Main Thread |
| `audio_analysis_cache.json` | 1,192 B (1.2 KB) | **YES** — `AudioAnalysisRepository.init` calls `loadFromDisk()` on Main Thread |
| `playlists.json` | 393 B (0.4 KB) | **YES** — `MusicLibraryRepository` init loads `playlistStore.load()` on Main Thread |
| `smart_playlists.json` | 0 B (absent if no custom) | **YES** — `SmartPlaylistRepository.init` calls `loadFromDisk()` on Main Thread |
| `sleep_timer.json` | 0 B (absent when inactive) | **YES** — `SleepTimerController.init` calls `restoreIfStillValid()` on Main Thread |

*(Baseline StrictMode disk-read count: NOT MEASURED via runtime instrumentation prior to STAB-05 because StrictMode was not installed in pre-STAB-05 `MDownloadApp`; verified statically as 8 synchronous `File.isFile`/`readText`/`mkdirs` paths executing on Main Thread during `MDownloadApp.onCreate()`.)*

---

## 4. Memory & UI Frame Timing Baseline on vivo X200s

### Memory (`dumpsys meminfo com.example.mdownload`, 1.5 s post-cold-launch)

| Region | PSS (KB) | RSS (KB) |
| :--- | :---: | :---: |
| Java Heap | 20,820 | 35,624 |
| Native Heap | 30,680 | 32,324 |
| Code | 77,356 | 218,116 |
| Graphics | 35,172 | 35,172 |
| **TOTAL** | **192,118 KB (~187.6 MB)** | **342,020 KB (~334.0 MB)** |

### UI Frame Timing (`dumpsys gfxinfo com.example.mdownload`)

| Scenario | Total Frames | Janky Frames | Legacy Janky | 50th | 90th | 95th | 99th | Slow UI Thread | Slow Draw Cmds |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: | :---: |
| Cold Launch (Home) | 15 | 3 (20.00%) | 11 (73.33%) | 14 ms | 250 ms | 250 ms | 250 ms | 3 | 1 |
| Library Scroll (153 tracks, 3 swipes) | 436 | 4 (0.92%) | 314 (72.02%) | 13 ms | 32 ms | 38 ms | 53 ms | 4 | 1 |
| **Now Playing (6 s idle active playback)** | **162** | **40 (24.69%)** | **126 (77.78%)** | **20 ms** | **29 ms** | **29 ms** | **34 ms** | **40** | **19** |

**Critical UI Finding:** During 6 seconds of *completely idle* Now Playing playback (zero user touch), HiFiDeck rendered `162` frames with **24.69% jank** (`40` Slow UI Thread frames, median frame `20 ms`), driven by `MDownloadRoot` collecting `PlaybackUiState` (containing 250 ms `positionMs` updates) and cascading recompositions down `NowPlayingScreen` and `UnifiedBottomDock`.

---

## 5. Smart Playlist Scalability Baseline (JVM Benchmark)

**Benchmark:** `SmartPlaylistBenchmarkTest.benchmarkAllBuiltInAndCustomSmartPlaylistsAcrossScale`  
**Command:** `.\gradlew.bat :app:testDebugUnitTest --tests com.example.mdownload.SmartPlaylistBenchmarkTest --rerun-tasks --info`  
**Dataset Distribution:** Deterministic seeded libraries of `153`, `1,000`, `5,000`, and `10,000` tracks with realistic listening history (`50%` matched by `stableId`, `20%` legacy ID with `lastKnownUri` fallback, `30%` unplayed — reproducing the `O(N log N * M)` linear fallback scan in `SmartPlaylistEvaluator.resolveStats()`).

| Library Size (`N`) | Workload | Median (ms) | p90 (ms) | p95 (ms) | Max (ms) | Checksum |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: |
| **153** | All 7 Built-In Playlists | 2.009 | 2.818 | 2.822 | 2.822 | 6720 |
| 153 | Most Played (`PLAY_COUNT_DESC`) | 0.180 | 0.200 | 0.422 | 0.422 | 1000 |
| 153 | Recently Played (`LAST_PLAYED_DESC`) | 0.227 | 0.325 | 0.423 | 0.423 | 1000 |
| 153 | Never Played | 0.039 | 0.045 | 0.047 | 0.047 | 900 |
| 153 | High Quality (`BITRATE_DESC`) | 0.131 | 0.271 | 0.300 | 0.300 | 1540 |
| 153 | Custom 3-Rule Compound | 0.125 | 0.172 | 0.173 | 0.173 | 280 |
| **1,000** | All 7 Built-In Playlists | 22.331 | 23.609 | 23.881 | 23.881 | 24000 |
| 1,000 | Most Played (`PLAY_COUNT_DESC`) | 7.957 | 8.196 | 8.426 | 8.426 | 1000 |
| 1,000 | Recently Played (`LAST_PLAYED_DESC`) | 8.463 | 12.961 | 19.650 | 19.650 | 1000 |
| 1,000 | Never Played | 1.298 | 2.189 | 2.776 | 2.776 | 6000 |
| 1,000 | High Quality (`BITRATE_DESC`) | 2.248 | 2.282 | 2.378 | 2.378 | 10000 |
| 1,000 | Custom 3-Rule Compound | 2.297 | 2.490 | 2.497 | 2.497 | 1800 |
| **5,000** | **All 7 Built-In Playlists** | **2,608.860** | **3,258.077** | **3,258.077** | **3,258.077** | 46800 |
| 5,000 | Most Played (`PLAY_COUNT_DESC`) | 1,009.315 | 1,245.476 | 1,245.476 | 1,245.476 | 450 |
| 5,000 | Recently Played (`LAST_PLAYED_DESC`) | 886.736 | 1,075.521 | 1,075.521 | 1,075.521 | 450 |
| 5,000 | Never Played | 102.628 | 147.750 | 147.750 | 147.750 | 13500 |
| 5,000 | High Quality (`BITRATE_DESC`) | 161.212 | 243.543 | 243.543 | 243.543 | 22500 |
| 5,000 | Custom 3-Rule Compound | 218.430 | 315.706 | 315.706 | 315.706 | 900 |
| **10,000** | **All 7 Built-In Playlists** | **15,251.380** | **17,715.645** | **17,715.645** | **17,715.645** | 71400 |
| 10,000 | Most Played (`PLAY_COUNT_DESC`) | 5,808.576 | 6,147.226 | 6,147.226 | 6,147.226 | 350 |
| 10,000 | Recently Played (`LAST_PLAYED_DESC`) | 1,465.766 | 1,519.570 | 1,519.570 | 1,519.570 | 350 |
| 10,000 | Never Played | 607.793 | 678.106 | 678.106 | 678.106 | 21000 |
| 10,000 | High Quality (`BITRATE_DESC`) | 1,121.162 | 1,625.999 | 1,625.999 | 1,625.999 | 35000 |
| 10,000 | Custom 3-Rule Compound | 1,771.672 | 1,974.568 | 1,974.568 | 1,974.568 | 700 |

**Critical Scalability Finding:** At `N = 5,000`, evaluating all 7 built-in playlists takes **2.61 seconds** (target: `< 50 ms`), and at `N = 10,000` it takes **15.25 seconds** — causing immediate ANR risk when evaluated synchronously inside Compose on the Main Thread.

---

## 6. Background I/O & Persistence Baseline

| Component | Current Behavior | Measured / Observed Impact |
| :--- | :--- | :--- |
| `HiFiPlaybackService` + `PlaybackStateStore` | Serializes full queue (up to 500 `LibraryTrack` objects, pretty-printed `toString(2)`) every **5 seconds** during active playback | Rewrites **121.7 KB** (`playback_state.json`) 12 times/min = ~1.46 MB/min (~87.6 MB/hr) just to persist `position_ms` |
| `ListeningSessionTracker` + `ListeningHistoryRepository` | Commits and rewrites full `listening_history.json` every **10 seconds** during playback | Rewrites full JSON 6 times/min on disk without debouncing |
| `DownloadForegroundService` | Calls `NotificationManager.notify()` on every combined `jobs` / `isRunning` / `totalSpeed` emission | Unthrottled Binder IPC spikes during multi-track concurrent downloads |
| `AudioAnalysisRepository` | Persists sorted top 1000 entries to disk, **but never evicts in-memory `ConcurrentHashMap`** (`cache` grows unbounded across sessions until restart), and `resolveReplayGain()` does not cache tag-only ReplayGain results | In-memory unbounded map growth + repeated file header reads on replay of non-inspected tracks |

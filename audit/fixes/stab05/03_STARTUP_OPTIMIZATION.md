# HiFiDeck Android — STAB-05 Workstream B: Startup & Main-Thread I/O Optimization

**Date:** 2026-11-24  
**Target:** vivo X200s (`V2458A`, OriginOS 7, Android 17 / API 37, `arm64-v8a`)  
**Goal:** Eliminate synchronous JSON/log disk reads and eager `mkdirs()` calls from `MDownloadApp.onCreate()` on the Main Thread, introduce deterministic async hydration with anti-overwrite race protection, verify startup safety via debug `StrictMode`, and reduce cold-start latency.

---

## 1. Pre-STAB-05 Startup Bottlenecks

Before STAB-05, `MDownloadApp.onCreate()` synchronously performed 8 separate disk reads/parses and multiple eager `File.mkdirs()` calls on the Main Thread:

1. `DownloadQueueManager.init` -> `SettingsStore.load()` (`43.7 KB` `settings.json`) and `SettingsStore.readLogs()` (`51.8 KB` `settings.log`)
2. `MusicLibraryRepository.init` -> `PlaylistStore.load()` (`playlists.json`) and `ArtistStore.load()` (`artists.json`)
3. `ListeningHistoryRepository.init` -> `loadFromDisk()` (`listening_history.json`)
4. `SmartPlaylistRepository.init` -> `loadFromDisk()` (`smart_playlists.json`)
5. `AudioAnalysisRepository.init` -> `loadFromDisk()` (`audio_analysis_cache.json`)
6. `SleepTimerController.init` -> `restoreIfStillValid()` (`sleep_timer.json`)
7. Eager `mkdirs()` inside constructors of `SettingsStore`, `PlaylistStore`, `ArtistStore`, `StorageTools`, `LyricsStorage`, `PlaybackStateStore`, `DownloadQueueManager`, `ListeningHistoryRepository`, `SmartPlaylistRepository`, `AudioAnalysisRepository`, `SleepTimerController`.

---

## 2. Implemented Architecture & Fixes

### 2.1 Deterministic Hydration Contract (`RepositoryLoadState`)
- Added `app/src/main/java/com/example/mdownload/core/RepositoryLoadState.kt` defining `enum class RepositoryLoadState { INITIAL, LOADING, READY, FAILED }`.
- Every async-hydrated repository exposes `val loadState: StateFlow<RepositoryLoadState>` and `suspend fun awaitLoaded()`.

### 2.2 Lazy Directory Creation (`mkdirs()` off Main Thread)
Converted eager constructor `mkdirs()` calls into lazy `ensureDir()` / `ensureParentDir()` helpers invoked only on `Dispatchers.IO` during reads/writes across:
- `SettingsStore.kt`, `PlaylistStore.kt`, `ArtistStore.kt`, `StorageTools.kt`, `LyricsStorage.kt`, `PlaybackStateStore.kt`, `DownloadQueueManager.kt`, `ListeningHistoryRepository.kt`, `SmartPlaylistRepository.kt`, `AudioAnalysisRepository.kt`, `SleepTimerController.kt`.

### 2.3 Async Hydration with Anti-Overwrite Race Protection
Each repository starts immediately with safe in-memory defaults, hydrates asynchronously on `Dispatchers.IO` when `MDownloadApp` launches staged startup, and **merges** hydrated disk data with any early mutations recorded prior to `READY` so user actions occurring during cold launch are never overwritten:

- **`DownloadQueueManager.kt`**: `hydrateAsync()` loads `settings.json` and `settings.log` off-Main-Thread; early `updateSettings()` or `appendLog()` mutations set dirty flags / retain in-memory mutations and are preserved across hydration.
- **`MusicLibraryRepository.kt`**: `hydrateAsync()` loads `playlists.json` and `artists.json` on `Dispatchers.IO`; early playlist/artist mutations are guarded against clobbering.
- **`ListeningHistoryRepository.kt`**: `hydrateAsync()` merges disk records with early in-memory playback events (`playCount` summed, `totalListenTimeMs` summed, `lastPlayedAtMs` maxed, `firstPlayedAtMs` min'd), verified directly in `Stab05PerformanceInstrumentedTest.persistenceSplitCursorLegacyCompatDebouncedHistoryLruAndAsyncRace`.
- **`SmartPlaylistRepository.kt`**: `hydrateAsync()` merges custom playlists by ID while honoring early creates/updates/deletes.
- **`AudioAnalysisRepository.kt`**: `hydrateAsync()` populates the bounded LRU cache without clobbering fresh in-memory inspection results.
- **`SleepTimerController.kt`**: `hydrateAsync()` restores persisted timer deadlines on `Dispatchers.IO` unless the user already started/cancelled a timer in the current session.

### 2.4 Crash-Recovery Startup Gate (`SafeAudioWritePipeline`)
- `SafeAudioWritePipeline.kt` retains full P0 crash-recovery semantics (`recoverPendingTransactions`), guarded by a concurrency-safe `activeRecoveryByJournal` gate so background startup recovery never races with an interactive metadata/lyrics write to the same target URI.
- `MDownloadApp.onCreate()` schedules crash recovery on `Dispatchers.IO` during staged async initialization rather than blocking cold launch.

### 2.5 Debug-Only `StrictMode` Instrumentation
- `MDownloadApp.onCreate()` installs `StrictMode.ThreadPolicy` (`detectDiskReads`, `detectDiskWrites`, `detectNetwork`, `penaltyLog`) in `BuildConfig.DEBUG` builds (never crashing production or debug runs, logging every main-thread violation for auditability).

---

## 3. Cold Startup Benchmark Results on vivo X200s (10 Repeatable Runs)

**Methodology:** Exact same protocol as Phase Zero (`am force-stop`, `700 ms` settle, `am start -W -n com.example.mdownload/.MainActivity`, `800 ms` cooldown, 10 runs).

| Run | Baseline `TotalTime` (ms) | Post-STAB-05 `TotalTime` (ms) | Baseline `WaitTime` (ms) | Post-STAB-05 `WaitTime` (ms) |
| :---: | :---: | :---: | :---: | :---: |
| 1 | 733 | 703 | 736 | 709 |
| 2 | 778 | 684 | 781 | 686 |
| 3 | 775 | 689 | 777 | 691 |
| 4 | 741 | 707 | 745 | 710 |
| 5 | 767 | 697 | 769 | 700 |
| 6 | 720 | 689 | 722 | 694 |
| 7 | 733 | 693 | 735 | 697 |
| 8 | 733 | 743 | 736 | 744 |
| 9 | 777 | 745 | 779 | 747 |
| 10 | 771 | 753 | 773 | 755 |

### Summary Delta

| Metric | Baseline | Post-STAB-05 | Delta | Improvement |
| :--- | :---: | :---: | :---: | :---: |
| `TotalTime` Min | 720 ms | **684 ms** | -36 ms | -5.00% |
| **`TotalTime` Median** | **754.0 ms** | **700.0 ms** | **-54.0 ms** | **-7.16%** |
| **`TotalTime` p90** | **777 ms** | **745 ms** | **-32 ms** | **-4.12%** |
| `TotalTime` Max | 778 ms | **753 ms** | -25 ms | -3.21% |
| **`WaitTime` Median** | **757.0 ms** | **704.5 ms** | **-52.5 ms** | **-6.94%** |
| `WaitTime` p90 | 779 ms | **747 ms** | -32 ms | -4.11% |

---

## 4. Debug `StrictMode` Audit Results on vivo X200s

Captured live logcat during cold start of `app-arm64-debug.apk` on vivo X200s:

- **`StrictModeDiskWriteViolation` count:** **0** (zero main-thread writes, zero `mkdirs()` violations).
- **App JSON/log `StrictModeDiskReadViolation` count:** **0** (all 8 JSON/log repository loads moved cleanly to `Dispatchers.IO`).
- **Remaining framework `StrictModeDiskReadViolation` (honest disclosure):**
  - Android's `ContextImpl.getFilesDir()` invokes `File.exists()` inside `ContextImpl.getDataDir()` during eager `File(context.filesDir, ...)` field initialization across 9 store/repository constructors in `MDownloadApp.onCreate()`.
  - Total wall-clock span of all 9 calls was **`12 ms`** (`21:48:46.124` to `21:48:46.136`), plus one framework-internal read inside `ComponentActivity.onCreate` (`MainActivity.kt:40`).
  - No file contents are read on the Main Thread; converting `private val baseDir by lazy { File(appContext.filesDir, ...) }` is documented as a low-risk post-STAB-05 follow-up in `09_REMAINING_BOTTLENECKS.md`.

---

## 5. Verification & Rollback

- **Verified on vivo X200s:** `Stab05PerformanceInstrumentedTest.asyncStartupHydrationReachesReadyWithoutBlocking` (**PASS**) verified all async repositories transition to `RepositoryLoadState.READY` cleanly.
- **Verified race protection:** `Stab05PerformanceInstrumentedTest.persistenceSplitCursorLegacyCompatDebouncedHistoryLruAndAsyncRace` (**PASS**) verified early in-memory listening mutations merge cleanly with delayed disk hydration instead of being overwritten.
- **Rollback:** Call repository `loadFromDisk()` methods synchronously inside constructors and remove `hydrateAsync()` / `StrictMode` setup in `MDownloadApp.kt`.

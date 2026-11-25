# HiFiDeck Android — STAB-05 Workstream D: Smart Playlist Scalability Optimization

**Date:** 2026-11-24  
**Scope:** `SmartPlaylistEvaluator.kt`, `MainViewModel.kt`, `MDownloadRoot.kt`, `LibraryHubScreen.kt`, `SmartPlaylistDialogs.kt`, `SmartPlaylistBenchmarkTest.kt`  
**Goal:** Eliminate the `O(N log N * M)` linear fallback scan inside `SmartPlaylistEvaluator`, move Smart Playlist evaluation off the Main Thread, and bring full 7-playlist evaluation at `N = 5,000` tracks well below **`50 ms`** while preserving 100% deterministic output parity.

---

## 1. Root Cause Analysis

In pre-STAB-05 `SmartPlaylistEvaluator.kt`:
1. **`O(M)` Linear Fallback inside `resolveStats()`:** When a track had no direct `stableId` match in `statsByTrackId` (e.g., legacy URI-keyed stats or unplayed tracks — ~50% of a typical library), `resolveStats()` fell back to `statsByTrackId.values.firstOrNull { it.lastKnownUri == track.uri }` — scanning up to `M` entries **per track** during filtering, and **again inside every comparator invocation during `sortedWith` (`O(N log N * M)`)**.
2. **Repeated Rule Parsing & Allocation:** String lowercasing and regex/substring normalization ran repeatedly per track and per sort comparison.
3. **Synchronous Main-Thread Evaluation inside Compose:** `LibraryHubScreen` and `SmartPlaylistDialogs` invoked `SmartPlaylistEvaluator.evaluate(...)` synchronously during Compose composition (`remember`), blocking the UI thread for **2.61 seconds at `N = 5,000`** and **15.25 seconds at `N = 10,000`**.

---

## 2. Implemented Optimizations

### 2.1 Algorithmic Overhaul in `SmartPlaylistEvaluator.kt`
- **Shared `O(1)` `EvaluationContext` / `statsByUri` Index:** Built once per evaluation pass (`buildStatsByUriIndex`), turning every `lastKnownUri` fallback from `O(M)` linear scan into an `O(1)` hash lookup.
- **Batch Entry Point (`evaluateAll`):** Accepts the full list of playlists and shares a single pre-built `statsByUri` index across all 7 built-in + custom Smart Playlists.
- **Precompiled Rule Predicates:** Pre-normalizes query strings, numeric thresholds, and relative time cutoffs once per playlist instead of per track.
- **Schwartzian Transform Sorting:** Pre-computes each matching track's sort key (`playCount`, `lastPlayedAtMs`, `bitrateKbps`, `dateAddedSec`, `durationMs`, etc.) once into an indexed wrapper prior to sorting, guaranteeing zero map lookups inside the comparator (`O(N log N)` total).
- **Exact Behavioral Parity:** Preserved all rule operators (`EQUALS`, `CONTAINS`, `GREATER_THAN`, `LESS_THAN`, `WITHIN_LAST_DAYS`, etc.), `MATCH_ALL`/`MATCH_ANY` combinators, tie-breaking rules, and `limit` clamping.

### 2.2 Off-Main-Thread Reactive Evaluation Pipeline
- **`MainViewModel.kt`**: Added `val evaluatedSmartPlaylists: StateFlow<Map<String, List<LibraryTrack>>>`, computed via `combine(smartPlaylists, tracks, listeningHistory, audioAnalysisCache)` and `mapLatest` on **`Dispatchers.Default`**, exposed with `SharingStarted.WhileSubscribed(5_000)`.
- **`MDownloadRoot.kt` -> `LibraryHubScreen.kt`**: Passes pre-evaluated `Map<String, List<LibraryTrack>>` directly into the Library Playlists tab so opening/scrolling Playlists executes **zero** Smart Playlist filtering/sorting on the Main Thread.
- **`SmartPlaylistDialogs.kt`**: Migrated live rule-builder preview counts from synchronous `remember` evaluation to `produceState` executing on `Dispatchers.Default`.

---

## 3. JVM Benchmark Comparison (Deterministic Checksum-Verified)

**Command:** `.\gradlew.bat :app:testArm64DebugUnitTest --tests com.example.mdownload.SmartPlaylistBenchmarkTest --rerun-tasks --info`  
**Note:** Every single workload at `N = 153`, `1,000`, `5,000`, and `10,000` produced the **exact same deterministic output checksum** before and after optimization, proving 100% result equivalence.

| Library Size (`N`) | Workload | Baseline Median (ms) | Post-STAB-05 Median (ms) | Post-STAB-05 p95 (ms) | Speedup | Checksum (Parity) |
| :---: | :--- | :---: | :---: | :---: | :---: | :---: |
| **153** | All 7 Built-In Playlists | 2.009 | **1.252** | 1.383 | **1.6×** | `6720` == `6720` |
| 153 | Most Played (`PLAY_COUNT_DESC`) | 0.180 | **0.071** | 0.125 | **2.5×** | `1000` == `1000` |
| 153 | Recently Played (`LAST_PLAYED_DESC`) | 0.227 | **0.088** | 0.130 | **2.6×** | `1000` == `1000` |
| 153 | Never Played | 0.039 | **0.030** | 0.045 | **1.3×** | `900` == `900` |
| 153 | High Quality (`BITRATE_DESC`) | 0.131 | **0.080** | 0.107 | **1.6×** | `1540` == `1540` |
| 153 | Custom 3-Rule Compound | 0.125 | **0.056** | 0.068 | **2.2×** | `280` == `280` |
| **1,000** | All 7 Built-In Playlists | 22.331 | **2.311** | 3.124 | **9.7×** | `24000` == `24000` |
| 1,000 | Most Played (`PLAY_COUNT_DESC`) | 7.957 | **0.355** | 0.579 | **22.4×** | `1000` == `1000` |
| 1,000 | Recently Played (`LAST_PLAYED_DESC`) | 8.463 | **0.335** | 0.453 | **25.3×** | `1000` == `1000` |
| 1,000 | Never Played | 1.298 | **0.119** | 0.144 | **10.9×** | `6000` == `6000` |
| 1,000 | High Quality (`BITRATE_DESC`) | 2.248 | **0.419** | 0.653 | **5.4×** | `10000` == `10000` |
| 1,000 | Custom 3-Rule Compound | 2.297 | **0.130** | 0.168 | **17.7×** | `1800` == `1800` |
| **5,000** | **All 7 Built-In Playlists** | **2,608.860** | **17.775** | **20.896** | **146.8×** | `46800` == `46800` |
| 5,000 | Most Played (`PLAY_COUNT_DESC`) | 1,009.315 | **1.830** | 2.487 | **551.5×** | `450` == `450` |
| 5,000 | Recently Played (`LAST_PLAYED_DESC`) | 886.736 | **1.923** | 2.662 | **461.1×** | `450` == `450` |
| 5,000 | Never Played | 102.628 | **0.517** | 0.871 | **198.5×** | `13500` == `13500` |
| 5,000 | High Quality (`BITRATE_DESC`) | 161.212 | **1.981** | 2.387 | **81.4×** | `22500` == `22500` |
| 5,000 | Custom 3-Rule Compound | 218.430 | **0.659** | 1.076 | **331.5×** | `900` == `900` |
| **10,000** | **All 7 Built-In Playlists** | **15,251.380** | **17.501** | **22.711** | **871.5×** | `71400` == `71400` |
| 10,000 | Most Played (`PLAY_COUNT_DESC`) | 5,808.576 | **4.644** | 6.647 | **1,250.8×** | `350` == `350` |
| 10,000 | Recently Played (`LAST_PLAYED_DESC`) | 1,465.766 | **3.920** | 5.217 | **373.9×** | `350` == `350` |
| 10,000 | Never Played | 607.793 | **0.923** | 1.564 | **658.5×** | `21000` == `21000` |
| 10,000 | High Quality (`BITRATE_DESC`) | 1,121.162 | **3.591** | 4.892 | **312.2×** | `35000` == `35000` |
| 10,000 | Custom 3-Rule Compound | 1,771.672 | **1.162** | 1.816 | **1,524.7×** | `700` == `700` |

### Target Assessment
- **Target (`N = 5,000`, all 7 built-in playlists `< 50 ms`):** **EXCEEDED** at **`17.775 ms`** median (`20.896 ms` p95) — **146.8× faster** than the `2,608.860 ms` baseline.
- **Bonus Scale (`N = 10,000`):** **`17.501 ms`** median (**871.5× faster** than `15.25 s`), completely eliminating ANR risk even for massive libraries — and now executing off the Main Thread on `Dispatchers.Default`.

---

## 4. Verification & Rollback

- **Unit Tests:** All Smart Playlist tests in `AdvancedAudioFeaturesTest.kt`, `SmartPlaylistBenchmarkTest.kt`, and `Stab05PerformanceAndPersistenceTest.kt` passed (`117/117` JVM PASS).
- **Rollback:** Revert `SmartPlaylistEvaluator.kt` to single-pass unindexed `resolveStats` and evaluate synchronously in `LibraryHubScreen` / `SmartPlaylistDialogs`.

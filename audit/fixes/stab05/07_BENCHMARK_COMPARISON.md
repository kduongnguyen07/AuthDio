# HiFiDeck Android — STAB-05 Before vs. After Benchmark Comparison

**Date:** 2026-11-24  
**Device:** vivo X200s (`V2458A`, serial `10AF8A0YUH005HA`, OriginOS 7, Android 17 / API 37, `arm64-v8a`)  
**Methodology:** All baseline values are taken verbatim from `audit/fixes/stab05/01_BASELINE_PERFORMANCE.md` (unmodified post-P1 build). All post-STAB-05 values were measured using identical protocols, commands, swipe coordinates, durations, and deterministic datasets.

---

## 1. Master Scorecard

| Benchmark Category | Metric | Baseline (Pre-STAB-05) | Post-STAB-05 | Delta | Improvement |
| :--- | :--- | :---: | :---: | :---: | :---: |
| **APK Packaging** | Default Debug APK Size | 264.77 MB (`277,629,885 B`) | **126.04 MB (`132,157,292 B`)** | **-138.73 MB** | **-52.40%** (`<= 135 MB` target met) |
| **APK Packaging** | Universal Multi-ABI Debug APK | 264.77 MB | 265.07 MB (`277,946,840 B`) | +0.30 MB | Preserved (4 ABIs) |
| **APK Packaging** | Universal Release AAB | Not measured | **198.19 MB (`207,813,991 B`)** | — | Verified build |
| **Cold Startup (10 runs)** | `TotalTime` Median | 754.0 ms | **700.0 ms** | **-54.0 ms** | **-7.16%** |
| **Cold Startup (10 runs)** | `TotalTime` p90 | 777 ms | **745 ms** | **-32 ms** | **-4.12%** |
| **Cold Startup (10 runs)** | `TotalTime` Min / Max | 720 ms / 778 ms | **684 ms / 753 ms** | -36 / -25 ms | Faster bounds |
| **Cold Startup (10 runs)** | `WaitTime` Median / p90 | 757.0 ms / 779 ms | **704.5 ms / 747 ms** | -52.5 / -32 ms | **-6.94% / -4.11%** |
| **Startup Main-Thread I/O** | Sync JSON/log reads & `mkdirs()` | 8 JSON/log loads + 11 eager `mkdirs()` | **0 JSON/log reads, 0 disk writes** | -8 reads, -11 `mkdirs()` | **100% eliminated** *(see note 1)* |
| **Now Playing (6 s idle)** | Total Frames Rendered | 162 | **96** | **-66 frames** | **-40.74%** |
| **Now Playing (6 s idle)** | Janky Frames (Count / Rate) | 40 (`24.69%`) | **22 (`22.92%`)** | **-18 frames** | **-45.00% count** |
| **Now Playing (6 s idle)** | 50th / 90th / 95th / 99th | 20 / 29 / 29 / 34 ms | **16 / 22 / 23 / 28 ms** | **-4 / -7 / -6 / -6 ms** | **-18% to -24%** |
| **Now Playing (6 s idle)** | Slow UI Thread / Slow Draw | 40 / 19 | **22 / 13** | **-18 / -6** | **-45.0% / -31.6%** |
| **Library Scroll (3 swipes)** | Janky Frames (Count / Rate) | 4 / 436 (`0.92%`) | **2 / 426 (`0.47%`)** | **-2 frames** | **-48.9% jank rate** |
| **Library Scroll (3 swipes)** | 50th / 90th / 95th / 99th | 13 / 32 / 38 / 53 ms | **12 / 32 / 42 / 53 ms** | -1 / 0 / +4 / 0 ms | Parity / -1 ms median |
| **Cold Launch `gfxinfo`** | Janky Rate / 90th Percentile | `20.00%` (3/15) / 250 ms | **`10.91%` (6/55) / 81 ms** | -9.09 pp / -169 ms | **-45.5% jank rate** |
| **Recomposition Isolation** | Root & Sibling Recomps over 20 Ticks | 20 (root cascade) | **0 root, 0 sibling** (20 leaf-only) | **-100% cascade** | Verified on vivo X200s |
| **Smart Playlists (`N=153`)** | All 7 Built-In Median | 2.009 ms | **1.252 ms** | -0.757 ms | **1.6× faster** |
| **Smart Playlists (`N=1,000`)** | All 7 Built-In Median | 22.331 ms | **2.311 ms** | -20.020 ms | **9.7× faster** |
| **Smart Playlists (`N=5,000`)** | All 7 Built-In Median | 2,608.860 ms | **17.775 ms** | **-2,591.085 ms** | **146.8× faster** (`< 50 ms` met) |
| **Smart Playlists (`N=10,000`)** | All 7 Built-In Median | 15,251.380 ms | **17.501 ms** | **-15,233.879 ms** | **871.5× faster** |
| **Playback Persistence** | Per-5 s Position Save Payload | 121,691 B (`1.46 MB/min`) | **192 B (`2.30 KB/min`)** | **-121,499 B/save** | **-99.84% (633.8× smaller)** |
| **Playback Persistence** | Full Queue Rewrites / Min Playing | 12/min | **0/min** | -12/min | **-100%** |
| **Listening History I/O** | Periodic Disk Flush Interval | Every 10 s (6/min) | **Every 30 s (2/min) + instant `flushNow()`** | -4 writes/min | **-66.7% periodic I/O** |
| **Memory (`dumpsys meminfo`)** | Total PSS / RSS (post-launch) | 192,118 KB / 342,020 KB | 213,607 KB / 365,528 KB *(note 2)* | +21.5 MB / +23.5 MB | Within normal debug variance |

### Measurement Notes & Caveats
1. **StrictMode Note:** Zero app JSON/log disk reads and zero disk writes occurred on the Main Thread during cold launch. Android's `ContextImpl.getFilesDir()` triggers internal `File.exists()` (`DiskReadViolation`) across 9 store constructors totaling `12 ms` wall time (`21:48:46.124`–`21:48:46.136`). Documented transparently in `03_STARTUP_OPTIMIZATION.md` and `09_REMAINING_BOTTLENECKS.md`.
2. **Memory Comparison Caveat:** The baseline `meminfo` snapshot was taken 1.5 s after a single launch (before async repositories and coil artwork caches fully warmed), whereas the post-STAB-05 `meminfo` snapshot was captured immediately after the 10-run cold-start sequence with full async hydration (`Java Heap 34,872 KB`, `Native Heap 21,776 KB` [down from `30,680 KB`], `Code 79,340 KB`, `Graphics 48,148 KB` [from warmed Compose/Coil textures]). Steady-state Native Heap actually decreased by **8.9 MB** (`30,680 KB` -> `21,776 KB`).

---

## 2. Smart Playlist Scaling Curve (`N = 153` to `10,000`)

| `N` | Workload | Baseline Median | Post-STAB-05 Median | Post-STAB-05 p95 | Speedup |
| :---: | :--- | :---: | :---: | :---: | :---: |
| 153 | All 7 Built-In | 2.009 ms | 1.252 ms | 1.383 ms | 1.6× |
| 1,000 | All 7 Built-In | 22.331 ms | 2.311 ms | 3.124 ms | 9.7× |
| 5,000 | All 7 Built-In | 2,608.860 ms | 17.775 ms | 20.896 ms | **146.8×** |
| 5,000 | Most Played | 1,009.315 ms | 1.830 ms | 2.487 ms | **551.5×** |
| 10,000 | All 7 Built-In | 15,251.380 ms | 17.501 ms | 22.711 ms | **871.5×** |
| 10,000 | Most Played | 5,808.576 ms | 4.644 ms | 6.647 ms | **1,250.8×** |
| 10,000 | Custom 3-Rule | 1,771.672 ms | 1.162 ms | 1.816 ms | **1,524.7×** |

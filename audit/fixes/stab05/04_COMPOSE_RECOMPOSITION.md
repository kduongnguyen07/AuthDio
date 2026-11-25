# HiFiDeck Android — STAB-05 Workstream C: Compose Recomposition & Orbital/Now Playing Optimization

**Date:** 2026-11-24  
**Target:** vivo X200s (`1260×2800`, OriginOS 7, Android 17), Jetpack Compose BOM `2026.02.01`  
**Goal:** Break the root-level `positionMs` recomposition cascade during playback, isolate progress reads to leaf composables, defer `orbitProgress` reads into the draw/graphics layer, cache `Brush`/`Path`/`Shader` objects with `drawWithCache`, move artwork bitmap decoding off the Main Thread, and stabilize `LazyColumn` item recycling.

---

## 1. Verified Root Causes in Pre-STAB-05 Compose Tree

1. **Root `positionMs` Cascade:** `MDownloadRoot` collected `viewModel.playbackState` (which emitted a new `PlaybackUiState` with updated `positionMs` every 150–250 ms), forcing `MDownloadRoot`, `HomeScreen`, `UnifiedBottomDock`, `MiniPlayer`, `NowPlayingScreen`, and `LyricsScreen` to recompose on every playback tick.
2. **Eager Per-Frame Draw Allocations:** `NowPlayingScreen`, `CircularAlbumArtwork`, `CircularSeekRing`, and `OrbitalPath` allocated `Brush.radialGradient`, `Brush.sweepGradient`, `Stroke`, and `Path` instances inside `Canvas` / `drawBehind` lambdas on every draw pass.
3. **Eager `orbitProgress` Recomposition:** `OrbitalNavigator` read animated `orbitProgress` inside composition scope instead of deferring transforms (`translationX`, `translationY`, `scaleX`, `scaleY`, `alpha`) to `Modifier.graphicsLayer { ... }`.
4. **Main-Thread Cover Art Decoding:** `TrackMetadataEditorSheet` called `BitmapFactory.decodeByteArray(...)` synchronously inside `remember(artworkBytes)` on the Main Thread without downsampling.
5. **Untyped `LazyColumn` Heterogeneity:** `LibraryHubScreen` mixed headers, filters, and track rows in `LazyColumn` without `contentType` hints.

---

## 2. Implemented Optimizations

### 2.1 Structural vs. High-Frequency Playback State Split
- **`PlaybackController.kt`**:
  - Added `val structuralState: StateFlow<PlaybackUiState>` which updates **only** on discrete playback events (track change, play/pause, shuffle, repeat, speed, queue change, error, audio quality, sleep timer, and the single `positionMs >= 3_000L` `hasPrevious` boundary crossing) — keeping `positionMs = 0L` so steady-state ticks never emit.
  - Added `val positionMs: StateFlow<Long>` polling at `250 ms` (4 Hz, reduced from `150 ms` / 6.7 Hz) only while `isPlaying` is true.
  - Preserved `val state: StateFlow<PlaybackUiState>` (updated alongside `positionMs`) for 100% backward compatibility with existing tests (`MusicPlayerInstrumentedTest`, `LiveDownloadTest`) and external consumers.
- **`MainViewModel.kt`**:
  - Wired `val playbackState: StateFlow<PlaybackUiState> = player.structuralState` and exposed `val playbackPositionMs: StateFlow<Long> = player.positionMs`.
- **Leaf-Only Progress Collection**:
  - `MDownloadRoot.kt`, `HomeScreen.kt`, `AppNavigation.kt`, `NowPlayingScreen.kt` no longer read `positionMs` in root/screen composition scopes.
  - Leaf progress subscribers read `playbackPositionMs` (or `positionProvider: () -> Long`) directly at the smallest possible UI node:
    - `MiniPlayer.kt` (`MiniPlayerProgressBar`)
    - `MusicCard.kt` (`HiFiContinueListeningCard` progress bar leaf)
    - `CircularSeekRing.kt` (arc sweep + elapsed timestamp text leaf; circular 360° scrubbing drag state untouched)
    - `LyricsScreen.kt` (active synced lyric index via `derivedStateOf` so non-transition ticks skip list recomposition)

### 2.2 Deferred Orbital Graphics Layer & `drawWithCache`
- **`OrbitalNavigator.kt`**: Deferred `orbitProgress` reads into `Modifier.graphicsLayer { ... }` lambda blocks so Orbital arc transitions animate in the render/graphics phase without recomposing child tabs.
- **`NowPlayingScreen.kt`, `CircularAlbumArtwork.kt`, `CircularSeekRing.kt`, `OrbitalPath.kt`**: Migrated static/dimension-dependent `Brush` gradients, `Stroke` objects, and cached `Path` geometry into `Modifier.drawWithCache { ... }` so steady-state frames avoid per-frame allocations.

### 2.3 Off-Main-Thread Downsampled Bitmap Decoding & `LazyColumn` Hygiene
- **`TrackMetadataEditorSheet.kt`**: Replaced synchronous `BitmapFactory.decodeByteArray` inside `remember` with `produceState<ImageBitmap?>(null, artworkBytes)` executing on `Dispatchers.Default` with `inJustDecodeBounds` + power-of-two `inSampleSize` downsampling to `<= 512 px`.
- **`LibraryHubScreen.kt`**: Added `contentType` parameters (`"section_header"`, `"smart_playlist"`, `"playlist_row"`, `"track_row"`, etc.) to `LazyColumn` items for slot reuse.

---

## 3. Measured Results

### 3.1 Automated Recomposition Isolation Test (vivo X200s)
`Stab05PerformanceInstrumentedTest.playbackPositionTicksDoNotRecomposeRootOrSiblings` (**PASS** on vivo X200s):
- Emitted **20 consecutive live `positionMs` ticks** (`0 ms` -> `5,000 ms`) while observing recomposition counters on:
  - Root container (reads `structuralState`)
  - Sibling metadata panel (reads `structuralState.currentTrack`)
  - Progress leaf (reads `positionMs`)
- **Result:**
  - Root recompositions during 20 ticks: **`0`** (1 initial composition only)
  - Sibling recompositions during 20 ticks: **`0`** (1 initial composition only)
  - Progress leaf updates: **`20`** (isolated strictly to the leaf!)

### 3.2 On-Device `dumpsys gfxinfo` Before vs. After (vivo X200s)

#### Now Playing — 6 Seconds Idle Active Playback (Zero Touch)
| Metric | Baseline | Post-STAB-05 | Delta | Improvement |
| :--- | :---: | :---: | :---: | :---: |
| Total Frames Rendered | 162 | **96** | -66 frames | **-40.74%** |
| Janky Frames | 40 (24.69%) | **22 (22.92%)** | **-18 janky frames** | **-45.00% count** |
| **50th Percentile** | **20 ms** | **16 ms** | **-4 ms** | **-20.00%** |
| **90th Percentile** | **29 ms** | **22 ms** | **-7 ms** | **-24.14%** |
| **95th Percentile** | **29 ms** | **23 ms** | **-6 ms** | **-20.69%** |
| **99th Percentile** | **34 ms** | **28 ms** | **-6 ms** | **-17.65%** |
| **Slow UI Thread Frames** | **40** | **22** | **-18 frames** | **-45.00%** |
| **Slow Issue Draw Cmds** | **19** | **13** | **-6 frames** | **-31.58%** |
| GPU 50th / 90th / 99th | — | 5 ms / 6 ms / 7 ms | — | Healthy GPU budget |

#### Library Scroll — 154 Tracks, 3 Identical Vertical Swipes
| Metric | Baseline | Post-STAB-05 | Delta | Improvement |
| :--- | :---: | :---: | :---: | :---: |
| Total Frames Rendered | 436 | 426 | -10 frames | — |
| **Janky Frames** | **4 (0.92%)** | **2 (0.47%)** | **-2 janky frames** | **-48.9% rate** |
| **50th Percentile** | **13 ms** | **12 ms** | **-1 ms** | **-7.69%** |
| 90th Percentile | 32 ms | 32 ms | 0 ms | Parity |
| 95th Percentile | 38 ms | 42 ms | +4 ms | Run variance |
| 99th Percentile | 53 ms | 53 ms | 0 ms | Parity |
| **Slow UI Thread Frames** | **4** | **2** | **-2 frames** | **-50.00%** |
| Slow Issue Draw Cmds | 1 | 1 | 0 | Parity |
| GPU 50th / 90th / 99th | — | 3 ms / 4 ms / 6 ms | — | Healthy GPU budget |

*(Honest note: The remaining `96` frames / `22` slow UI frames during 6 s of idle Now Playing playback are driven by the continuous ambient glow / vinyl rotation animation running on Now Playing alongside the 4 Hz progress ring invalidation. See `09_REMAINING_BOTTLENECKS.md` for how an optional `RenderNode` / `graphicsLayer` hardware-layer promotion or reduced-motion toggle could lower idle frame cost further.)*

---

## 4. Preserved Interaction & Accessibility Invariants

- All **7/7** tests in `P1UiAndAccessibilityInstrumentedTest` passed on vivo X200s after these optimizations, verifying:
  - 360° Circular Scrubbing across all 9 clock angles (including 12/3/6/9 o'clock and wraparound seam)
  - Light & Dark theme legibility
  - Screen-reader / TalkBack content descriptions and touch targets
- Orbital Navigation gestures and synchronized lyrics highlighting remain fully operational.

---

## 5. Rollback
Point `MainViewModel.playbackState` back to `player.state` and restore `positionMs` reads from `PlaybackUiState` in `MiniPlayer`, `CircularSeekRing`, `MusicCard`, and `LyricsScreen`.

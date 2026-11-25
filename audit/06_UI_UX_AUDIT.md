# HiFiDeck — UI/UX, Accessibility & Gesture Reliability Audit

**Audit Scope:** Phase 5E (Orbital Navigation & Circular Scrubbing Gestures) + Phase 9 (UI/UX, Accessibility, Touch Targets, Light/Dark Theme Consistency, One-Handed Ergonomics).  
**Audit Date:** 2026-11-24

> **Visual Inspection Caveat (Honest Evidence Disclosure):**  
> During live verification on the vivo X200s (`1260×2800` @ `525` override dpi, OriginOS 7 / Android 17), `studio_ui_state` returned the complete live XML View/Compose hierarchy for `MainActivity` (Home screen), but screenshot PNG bytes were offloaded to disk rather than rendered inline in context. Accordingly, this audit **does not claim pixel-level visual fidelity**. Every finding below is strictly backed by Compose source inspection and live UI hierarchy XML attributes/bounds.

---

## 1. Phase 5E — Orbital Navigation & Circular Scrubbing Reliability Audit

### 1.1 Pure Domain Math (Verified Strong)
- **`OrbitalSelectionEngine.kt`** (`OrbitalNavigationTest.kt`, **7/7 PASS**):
  - Cleanly separates session start, vertical drag accumulation, hysteresis deadband (preventing accidental skips from micro-jitter), boundary clamping, mid-drag queue mutation safety, cancel/release-without-change no-op (`null`), and shuffle-order → Media3 timeline index mapping.
- **`CircularScrubEngine.kt`** (`CircularScrubTest.kt`, **10/10 PASS**):
  - Correctly handles `atan2` angle wraparound across the `±π` seam without jump discontinuities, clockwise/counterclockwise direction, multi-revolution scaling for long tracks (auto-caps at 5 min/revolution), clamping at `[0, duration]` with immediate reversal recovery, micro-jitter rejection, mid-drag track change cancellation, unseekable/zero-duration guards, and commit-on-release-only semantics.

---

### 1.2 Confirmed UI / Gesture Integration Defects

#### **[P1 / UI-01] 4 Diagonal Dead Zones on Circular Seek Ring (Pointer Hit-Testing Overlap)**
- **Files:** `CircularAlbumArtwork.kt`, `CircularSeekRing.kt`
- **Root Cause:**
  - `CircularAlbumArtwork` layers an inner square `Box` for artwork touch gestures (`long-press` for Orbital mode, tap/drag) directly on top of `CircularSeekRing`:
    - Outer ring touch zone in `CircularScrubConfig`: `0.74R .. 1.22R`.
    - Inner artwork `Box`: `Modifier.size(innerArtDiameter).clip(CircleShape).then(artworkTouchModifier)` where `innerArtDiameter = 0.72 * bezelDiameter` (`radius = 0.72R`).
  - **Compose Framework Reality:** `Modifier.clip(CircleShape)` clips **rendering/drawing only** — it does **NOT** clip pointer input hit-testing! The hit-test region of the inner `Box` remains its full **axis-aligned square** of half-side `0.72R`.
  - The 4 corners of that square extend to distance:
    $$\sqrt{(0.72R)^2 + (0.72R)^2} = 0.72\sqrt{2}\,R \approx 1.018R$$
  - **User Impact:** Any finger touching the circular seek bezel near the 4 diagonals (**45°, 135°, 225°, 315° — i.e. 1:30, 4:30, 7:30, and 10:30 o'clock**) inside `0.74R .. 1.018R` is intercepted by the inner square `Box` instead of `CircularSeekRing`, causing frustrating unresponsive seek starts or accidental Orbital long-press triggers!
  - **Secondary Clipping:** `CircularSeekRing` sizes its touch container to `outerTouchDiameter = bezelDiameter + 36.dp`. On a standard `300.dp` bezel (`R = 150.dp`), `outerTouchDiameter = 336.dp` (`radius = 168.dp = 1.12R`), clipping the outer tolerance band declared in `CircularScrubConfig` (`1.22R = 183.dp`).

#### **[P1 / UI-03] Circular Scrubbing Completely Inaccessible to TalkBack / Switch Access**
- **File:** `CircularSeekRing.kt`
- **Root Cause:** `CircularSeekRing` attaches `semantics { progressBarRangeInfo = ... }`, announcing playback progress to screen readers, **but provides NO `setProgress` semantic action** (nor step-forward/step-backward custom accessibility actions). TalkBack and Switch Access users cannot seek on `NowPlayingScreen` (since `NowPlayingScreen` relies on the circular ring rather than a standard `Slider`).

#### **[P1 / UI-04] Per-Frame Recomposition & Coroutine Churn During Orbital / Circular Gestures**
- **Files:** `NowPlayingScreen.kt`, `OrbitalNavigator.kt`
- **Root Cause:**
  - `NowPlayingScreen` reads `orbitProgress.value` and `holdProgress` directly inside composable scope rather than deferring to `graphicsLayer { ... }` / `drawWithContent { ... }`.
  - `OrbitalNavigator` reads `animatedFocusIndex.value` during composition for all visible orbital items and launches a fresh coroutine on every `pointerInput` drag move event (`scope.launch { animatedFocusIndex.snapTo(...) }` / `animateTo`), creating frame-time allocation churn during continuous vertical drags.

#### **[P2 / UI-05] Orbital Navigator Landscape Vertical Crowding**
- **File:** `OrbitalNavigator.kt`
- **Root Cause:** On landscape screens (or split-screen windows with limited height), the orbital arc header, curved song list, and bottom accessibility action bar compete for vertical space without compact breakpoint adaptation, risking tight vertical overlap.

---

## 2. Phase 9 — Product UI/UX, Theming & Accessibility Audit

### 2.1 Confirmed High-Impact UX & Contrast Bugs

#### **[P1 / UI-02] Unreadable Bottom Sheets in Light Theme (`PearlOrchidColors`)**
- **Files:**
  - `AudioInspectorSheet.kt`
  - `SleepTimerSheet.kt`
  - `TrackMetadataEditorSheet.kt`
  - `BatchMetadataEditorSheet.kt`
- **Root Cause:** All four `ModalBottomSheet` surfaces hardcode a dark navy background:
  ```kotlin
  containerColor = OrbitalDesignTokens.Colors.BezelMid // #1B2132
  ```
  However, the text, labels, icons, and dividers inside these sheets use theme-adaptive `HiFiTheme.colors.inkPrimary` (`#181223` dark charcoal in Light Theme `PearlOrchidColors`) and `inkSecondary`!
- **User Impact:** When Light Theme is selected in Settings, all 4 advanced audio/metadata bottom sheets render **dark charcoal text (`#181223`) on a dark navy surface (`#1B2132`)** — an estimated contrast ratio of **~1.25:1** (severely failing WCAG 2.1 AA 4.5:1 and practically unreadable).

#### **[P1 / UI-06] Live-Verified Accessibility Failure on Home URL Input (`NAF="true"`)**
- **File:** `HomeScreen.kt`
- **Live vivo X200s UI Hierarchy Evidence (`studio_ui_state` on `MainActivity`):**
  - The primary URL `EditText` node appeared in the live Android UI tree with attribute **`NAF="true"`** (*Not Accessibility Friendly*), indicating missing `hint`, `label`, or `contentDescription` on the focusable text input surface.

#### **[P2 / UI-07] Undersized Touch Targets (< 48dp Material / Android Accessibility Minimum)**
Audit of clickable interactive controls across Compose screens identified multiple targets below `48.dp`:

| Screen / Component | File | Element | Configured Size | Gap vs. 48dp |
|---|---|---|---|---|
| **Queue Bottom Sheet** | `QueueBottomSheet.kt` | Move Up / Move Down / Remove row buttons | `32.dp` | `-16.dp` (high mis-tap risk) |
| **Lyrics Screen** | `LyricsScreen.kt` | Banner dismiss (`×`) button | `30.dp` | `-18.dp` |
| **Library / Next Songs** | `NowPlayingScreen.kt` / `LibraryHubScreen.kt` | Orbital trigger icon in Next Songs | `34.dp` | `-14.dp` |
| **Orbital Navigator** | `OrbitalNavigator.kt` | Close & Prev/Next step buttons | `40.dp` | `-8.dp` |
| **Now Playing & Mini Player** | `NowPlayingScreen.kt`, `MiniPlayerBar.kt` | Shuffle/Repeat mode icons & MiniPlayer action icons | `44.dp` | `-4.dp` |

---

### 2.2 Product & Workflow Friction Findings

| Severity | ID | Screen / Area | Finding & Concrete UX Impact |
|---|---|---|---|
| **P2** | **UI-08** | `SettingsScreen.kt` | **Version Mismatch in About Card:** Hardcodes display string `"HiFiDeck 1.2"` while `app/build.gradle.kts` ships `versionName = "1.1"` (`versionCode = 2`). Should read `BuildConfig.VERSION_NAME` / `PackageInfo`. |
| **P2** | **UI-09** | `MainViewModel.kt` / Playlists | **Fragile Favorites Coupling by Name String:** Heart/Favorite toggling looks up a manual playlist by literal string name `"Favorites"`. If the user renames the `"Favorites"` playlist (which the UI permits), heart toggling breaks or spawns a duplicate `"Favorites"` playlist. |
| **P2** | **UI-10** | `TrackMetadataEditorSheet.kt` | **Main-Thread Artwork Decode Hitch:** Opening the Metadata Editor decodes embedded artwork bytes (`BitmapFactory.decodeByteArray`) synchronously inside composition/Main Thread, causing a visible frame drop when opening sheets on tracks with large 2–5 MB embedded PNG/JPEG covers. |
| **P2** | **UI-11** | `LibraryHubScreen.kt` | **Monolithic 2,213-Line Hub File:** Combines Tracks, Albums, Artists, Folders, Manual Playlists, Smart Playlists, Search, Sort, Batch Selection, and Artist Management dialogs in a single file — increasing cognitive friction for consistent empty/loading/error states. |
| **P3** | **UI-12** | `SettingsScreen.kt` | **Settings Density & Organization:** Downloader settings, Audio/ReplayGain settings, Player UI toggles, Lyrics settings, Storage/Cleanup, and Diagnostics live in a long single vertical list without grouped sub-screens or search, increasing scroll fatigue for one-handed use on tall `1260×2800` displays. |

---

## 3. Live Edge-to-Edge & OriginOS 7 Layout Check (vivo X200s)

Verified via live `studio_ui_state` XML on vivo X200s (`1260×2800` px, `525` override dpi, `~384×853 dp`):
- **Top Status Bar Clearance:** `HiFiDeck` top header begins at `y = 246 px` (`~75 dp`), cleanly clearing the punch-hole camera & OriginOS 7 status bar.
- **Bottom Gesture Pill Clearance:** Bottom navigation dock spans `y = 2494..2704 px`, sitting cleanly above the `96 px` (`~29 dp`) system gesture bar (`2704..2800 px`). No overlap observed on Home screen.
- **One-Handed Reachability:** Bottom navigation dock (`Home`, `Library`, `Player`, `Settings`) and `MiniPlayerBar` sit in the lower thumb zone (good for one-handed `6.67"` vivo X200s ergonomics). Primary URL paste/download card sits mid-upper screen, while modal sheets open from the bottom (good reachability once Light Mode contrast is fixed).

---

## 4. Prioritized UI/UX Remediation Summary

1. **Fix Circular Seek Diagonal Dead Zones (`UI-01`):** Gate inner artwork pointer input by checking `(dx² + dy²) <= innerArtRadiusPx²` before consuming events (or handle both inner tap/long-press and outer ring scrub inside a single unified `pointerInput` modifier on `CircularAlbumArtwork` using polar radius `r`).
2. **Fix Light Theme Bottom Sheets (`UI-02`):** Replace hardcoded `OrbitalDesignTokens.Colors.BezelMid` with `HiFiTheme.colors.surfaceElevated` (or theme-aware sheet surface token) across all 4 modal sheets.
3. **Accessibility Baseline (`UI-03`, `UI-06`, `UI-07`):** Add `setProgress` semantic action to `CircularSeekRing`, add proper `label`/`contentDescription` to the Home URL `EditText`, and enforce `minimumInteractiveComponentSize()` (`48.dp`) on queue, lyrics, orbital, and mini-player controls.
4. **Eliminate Main-Thread Artwork Decode & Recomposition Hotspots (`UI-04`, `UI-10`):** Decode editor artwork via `produceState` on `Dispatchers.Default` (with `inSampleSize`) and defer `Animatable` reads to `graphicsLayer` / `Canvas` draw phase.

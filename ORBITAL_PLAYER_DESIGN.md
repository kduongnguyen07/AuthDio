# HiFiDeck — Orbital Music Player & Gesture Navigation Design

## 1. Visual Reference Analysis

### Reference 1 — Normal Music Player (Default State)
- **Atmosphere & Palette:** Near-black obsidian canvas (`#04060C`) illuminated by two restrained, matte deep-navy ambient washes — a soft upper-right slate-navy glow (`#222B45`) behind the header and a subtle lower navy-indigo lift (`#1A2238`) behind the playback controls and Next Songs preview. No bright neon or excessive glassmorphism.
- **Header Composition:**
  - Minimal top chrome: thin collapse chevron (`v`) on the left, subtle centered pill handle, vertical overflow menu (`⋮`) on the right.
  - Directly beneath: left-aligned Light-weight song title (`Passage`, ~`30sp`, `#F3F6FC`), muted cool-gray artist subtitle (`Roary`, `14sp`, `#9BA4BA`), and a right-aligned minimalist heart Favorite toggle (`♡` / `♥`).
- **Central Circular Album Artwork (`CircularAlbumArtwork` + `CircularPlaybackProgress`):**
  - Horizontally centered physical floating disc (`~78–82%` of screen width, capped at `316dp`).
  - Multi-layered dark charcoal-navy outer bezel (`#141824` → `#1E2536`) with a fine top-left illuminated specular rim (`#A8B9DB` fading to `#283148`) and soft ambient depth shadow.
  - Recessed circular progress groove inside the bezel channel with a clean 6 o'clock gap displaying the centered tabular timestamp (`2:23`).
  - Illuminated periwinkle-blue active progress arc (`#6A94FF` → `#BDD1FF`) sweeping clockwise around the perimeter.
  - Crisp anti-aliased circular inner album cover (`~72%` of bezel diameter) with subtle center press-hold halo feedback when initiating a long press.
- **Playback Controls (`PlayerControls`):**
  - Balanced 5-icon horizontal row floating with generous vertical breathing room below the disc: `Shuffle`, `Previous`, **large `64dp` solid white circular Play/Pause button** with dark `#070A12` glyph, `Next`, `Repeat`.
- **Next Songs Preview:**
  - Compact bottom section titled `Next Songs` (`17–18sp` crisp white) above upcoming track preview row(s) with `44dp` rounded-square artwork (`12dp` radius), title, artist, and right-aligned tabular duration (`3:23`). Strictly respects system navigation bar insets and never overlaps playback controls.

### Reference 2 — Expanded Orbital Song Navigation
- **Sweeping Planetary Arc (`OrbitalPath`):**
  - Deep near-black void (`#04060C`) bisected by a giant convex dark-slate dome bowing smoothly **rightward** through the middle of the screen (`x ≈ 58.5%` at `y ≈ 49.5%`) and curving back toward the top-left (`x ≈ 24%`) and bottom-left (`x ≈ 24%`).
  - Soft slate-blue inner dome gradient (`#28324D` near the arc edge fading into obsidian on the far left) traced by a delicate luminous cool-blue hairline stroke passing cleanly behind the circular covers.
- **Circular Album Constellation (`OrbitalNavigator` + `OrbitalItem`):**
  - Five visible covers arranged along the cubic arc (`r ∈ [-2, +2]`):
    - `r = 0` (selected focal track): largest circular cover framed by the signature dark concentric bezel + specular rim, positioned slightly right of center (`x ≈ 58.5%`, `y ≈ 49.5%`).
    - `r = ±1` (adjacent tracks): medium circular covers (`~62%` scale, `90%` opacity) positioned above and below along the curve (`x ≈ 51.5%`).
    - `r = ±2` (outer neighbors): smaller circular covers (`~38%` scale, `74%` opacity) curving back toward the upper-left and lower-left (`x ≈ 30.4%`).
  - Only nearby items (`selectedIndex - 3 .. selectedIndex + 3`) are composed simultaneously.
- **Selected Song Label (`SelectedSongLabel`):**
  - Anchored strictly to the **LEFT** of the focal album bezel, vertically centered with `y ≈ 49.5%`.
  - Constrained cleanly between the left margin and the focal bezel's left edge minus safety clearance so long titles wrap/truncate cleanly (`maxLines = 2`, ellipsis) without ever colliding with artwork or screen edges.
  - Displays honest queue context (`QUEUE · i/N` or `SHUFFLE · i/N`), animated primary song title, and secondary artist name.
- **Top-Right Dismiss Control:**
  - Translucent dark circular `×` close button (`40dp`, subtle rim stroke) in the top-right corner for quick cancellation in accessible/interactive mode.

---

## 2. Continuous Transition & Gesture State Machine

```text
IDLE
  │  Long press central album artwork (ViewConfiguration.longPressTimeoutMillis)
  ▼
ENTERING_ORBIT  ──► HapticFeedbackType.LongPress + spring morph (orbitProgress: 0f → 1f)
  │
  ▼
ORBIT_DRAGGING  ◄──► Continuous vertical pointer drag (up = next +1, down = prev -1)
  │                    Hysteresis band prevents jittery switching on micro-movements
  ▼
ORBIT_SELECTING ──► Spring snap to closest eligible track + subtle selection tick haptic
  │
  │  Finger release (or Confirm in accessible mode)
  ▼
COMMIT_SELECTION ─► Only switch Media3 track if index changed; preserve play/pause state
  │
  ▼
EXITING_ORBIT   ──► Spring morph back to Normal Player (orbitProgress: 1f → 0f)
  │
  ▼
IDLE
```

### Cancellation & Safety Rules
- Android pointer cancellation, multi-touch interruption, app backgrounding (`ON_PAUSE`), system Back press, or queue becoming empty immediately cancel the gesture, restore the original track without committing or restarting playback, and animate smoothly back to `IDLE`.
- If the active queue mutates mid-drag (e.g., an item is removed), orbital indices are safely re-clamped to the current queue bounds.
- A single-song queue or empty queue never crashes; empty queues disable orbital entry cleanly.

---

## 3. Reusable Orbital Coordinate System

For a viewport of size `(W, H)` and continuous relative offset `r = itemIndex - visualFocusIndex`:
- `centerY = H * 0.495f`
- `apexX = W * 0.585f`
- `arcDepth = W * 0.34f`
- `offsetX(r) = apexX - arcDepth * (r / 2.2f)^2` (clamped inside horizontal safe margins)
- `offsetY(r) = centerY + r * (H * 0.225f) * (1f - 0.045f * min(|r|, 2.5f))`
- `scale(r)` smoothly interpolates `1.00 → 0.62 → 0.38 → 0.22`
- `alpha(r)` smoothly interpolates `1.00 → 0.90 → 0.74 → 0.00`
- `zIndex(r) = 10f - |r|`

---

## 4. Media3 & Accessibility Integration
- Single existing Media3 `MediaController` / `HiFiPlaybackService` — zero duplicate players.
- `PlaybackUiState.orderedQueueIndices` mirrors Media3's live `Timeline` order (including real shuffle order when `shuffleModeEnabled == true`).
- Preview scrubbing in Orbital Mode never seeks or switches Media3 tracks until `COMMIT_SELECTION`.
- `PlaybackController.selectQueueItemPreservingPlayState(index)` skips only when `index != currentMediaItemIndex` and preserves active playing/paused state.
- Full non-gesture accessibility: overflow menu `"Orbital navigation"`, accessible Previous / Next / Play Selected actions inside Orbital Mode, and TalkBack semantics.

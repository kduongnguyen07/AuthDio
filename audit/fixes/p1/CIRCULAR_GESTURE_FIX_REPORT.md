# HiFiDeck Android — Circular Gesture Fix Report (`UI-01`)

**Date:** 2026-11-24  
**Scope:** `UI-01` (Diagonal dead zones & outer touch halo clipping in `CircularAlbumArtwork` / `CircularSeekRing` / `OrbitalGestureHandler`)  
**Key Files:**
- `app/src/main/java/com/example/mdownload/player/ui/CircularScrubState.kt`
- `app/src/main/java/com/example/mdownload/player/ui/OrbitalGestureHandler.kt`
- `app/src/main/java/com/example/mdownload/player/ui/CircularAlbumArtwork.kt`
- `app/src/main/java/com/example/mdownload/player/ui/NowPlayingScreen.kt`

---

## 1. Geometric Root Cause Analysis

### Defect A — Diagonal Square-Corner Occlusion (`45° / 135° / 225° / 315°`)
Previously, `CircularAlbumArtwork` rendered the inner album cover as a sibling `Box` positioned **above** `CircularSeekRing` with:
```kotlin
Box(
    modifier = Modifier
        .size(innerArtDiameter) // 0.72 * bezelDiameter
        .clip(CircleShape)
        .then(artworkTouchModifier) // orbitalArtworkLongPressDrag
)
```
In Jetpack Compose, `Modifier.clip(CircleShape)` clips **drawing** only—it does **not** clip pointer hit-testing. A square of side `2 * 0.72R` centered on the disc has diagonal corner radius:

$$r_{\text{corner}} = \sqrt{2} \times 0.72 R \approx 1.0182 R$$

Because `CircularSeekRing`'s active touch annulus spans `0.74R .. 1.22R`, the inner square `Box` occluded the entire seek ring from `0.74R` out to `1.0182R` along all four diagonal quadrants (`45°`, `135°`, `225°`, `315°`), stealing touch events into `orbitalArtworkLongPressDrag` before `CircularSeekRing` could receive them.

### Defect B — Outer Touch Halo Clipping
`CircularScrubConfig` declared `outerTouchRadiusFraction = 1.22f`, requiring a minimum square container of `2 * 1.22R = 1.22 * bezelDiameter`. However, `CircularAlbumArtwork` sized its outer `Box` to `bezelDiameter + 36.dp`, which on a `250.dp` bezel yielded `286.dp` (`1.144R`)—clipping the outer `1.144R .. 1.22R` touch halo on all cardinal edges.

---

## 2. Implemented Architecture Fix

1. **True Polar Hit-Zone Classification (`CircularScrubEngine`):**
   - Added `CircularDiscHitZone` (`INNER_ARTWORK`, `SEEK_RING`, `OUTSIDE`), `isInInnerArtworkZone()`, `classifyDiscTouchZone()`, and `requiredTouchContainerDiameterPx()`.
   - Both gestures are gated strictly by Euclidean polar distance `hypot(x - cx, y - cy)`:
     - Inner Orbital Artwork Zone: `r <= 0.72R`
     - Neutral Separation Gap: `0.72R < r < 0.74R`
     - Outer Circular Seek Ring Annulus: `0.74R <= r <= 1.22R`
2. **Polar-Gated `orbitalArtworkLongPressDrag` (`OrbitalGestureHandler`):**
   - Added optional `hitZoneFilter: ((Offset, IntSize) -> Boolean)? = null`. When a pointer down falls outside the inner circular disc (`r > 0.72R`), the orbital recognizer exits immediately without consuming or interfering with the gesture.
3. **Unified Unclipped Outer Touch Container (`CircularAlbumArtwork` & `NowPlayingScreen`):**
   - Removed `artworkTouchModifier` from the inner square `Box` (making the inner artwork container strictly visual).
   - Attached `artworkTouchModifier` (with `innerDiscHitFilter`) to the unified outer `Box` sized to `bezelDiameter * scrubController.config.outerTouchRadiusFraction` (`1.22 * bezelDiameter`).
   - Updated `NowPlayingScreen` disc sizing to account for `outerTouchFactor` cleanly within the viewport so zero clipping occurs.

---

## 3. Verification Matrix

### A. JVM Unit Tests (`CircularScrubAndUiP1Test.kt`)
- `ui01_polarZoneClassification_zeroDeadZonesAcrossAllAnglesIncludingDiagonals`: Verified across **all 9 key angles** (`0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°, 359°`) at radii `0.0R, 0.35R, 0.719R` (`INNER_ARTWORK`), `0.73R` (`OUTSIDE`), `0.75R, 0.85R, 0.95R, 1.00R, 1.018R, 1.15R, 1.219R` (`SEEK_RING`, all strictly inside container bounds), and `1.25R` (`OUTSIDE`).
- `ui01_requiredTouchContainerDiameter_preventsOuterHaloClipping`: Verified `250f -> 305f` (`>= 1.22 * bezelDiameter`).
- `ui01_continuousScrub_allNineAnglesAndSeamCrossingsAndTrackChange`: Verified clockwise `+30°` from all 9 angles, counterclockwise `15° -> 0° -> 345°` seam crossing, and clean cancellation on track change mid-drag.

### B. On-Device Compose Touch Tests on vivo X200s (`P1UiAndAccessibilityInstrumentedTest.kt`)
Mounted the real `CircularAlbumArtwork` + `CircularSeekRing` + `orbitalArtworkLongPressDrag(hitZoneFilter = innerHitFilter)` composite and injected real multi-step touch events:

| Test Case | Touch Radius | Angle(s) | Observed Result |
| :--- | :--- | :--- | :--- |
| Diagonal & cardinal ring scrub (all 9 angles) | `0.86R` (inside former square corner) | `0°, 45°, 90°, 135°, 180°, 225°, 270°, 315°, 359°` | **9/9 PASS** — committed forward seek; 0 Orbital misfires |
| Former clipped outer halo scrub | `1.18R` | `45°` | **PASS** — committed forward seek |
| Counterclockwise `0° / 360°` seam crossing | `0.90R` | `15° -> 345°` | **PASS** — smooth backward seek, zero jump |
| Multi-touch interruption mid-scrub | `0.90R` | `45°` + second finger at `225°` | **PASS** — cancelled cleanly, 0 seeks emitted |
| Track change mid-scrub | `0.90R` | `135°` -> `trackUri` swap | **PASS** — cancelled cleanly, 0 stale seeks emitted |
| Inner artwork long-press + vertical drag | `0.0R` (center) | Hold + drag up `130px` | **PASS** — entered Orbital mode & committed next track (`index 2`); 0 seeks emitted |

### C. Live vivo X200s Bounds Verification (`NowPlayingScreen`)
Captured via `studio_ui_state` on `1260 x 2800` display (`3.285 px/dp`):
- Outer `CircularSeekRing` (`SeekBar`) bounds: `[15,705][1245,1935]` (`1230 x 1230 px` = `374.4 x 374.4 dp`, completely unclipped within `1260 px` screen width).
- Inner artwork bounds: `[267,957][993,1683]` (`726 x 726 px` = `221.0 x 221.0 dp`).

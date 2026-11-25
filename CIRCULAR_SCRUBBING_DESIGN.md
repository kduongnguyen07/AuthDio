# HiFiDeck — Circular Drag Seeking Design

## 1. Overview
This document specifies the continuous angle-based circular drag seeking upgrade in HiFiDeck's Orbital Music Player. It replaces the earlier tap/absolute-angle ring seek in `CircularPlaybackProgress` with a dedicated, tactile, continuous-angle scrub controller (`CircularScrubState.kt` + `CircularSeekRing.kt`) that coexists with zero conflicts alongside the long-press-and-drag Orbital Song Navigation gesture.

## 2. Angular Math & Continuity Model
Let the circular disc center be $(c_x, c_y)$ and a pointer position be $(x, y)$.

- **Instantaneous angle:**
  $$\theta = \operatorname{atan2}(y - c_y,\; x - c_x) \in [-\pi, \pi]$$
- **Incremental angular delta (boundary-continuous):**
  For consecutive samples $\theta_{k-1}$ and $\theta_k$, the raw difference is wrapped into $[-\pi, \pi]$:
  $$\Delta\theta = \text{wrapToPi}(\theta_k - \theta_{k-1})$$
  where while $\Delta\theta > \pi$, $\Delta\theta \leftarrow \Delta\theta - 2\pi$, and while $\Delta\theta < -\pi$, $\Delta\theta \leftarrow \Delta\theta + 2\pi$.
- **Direction:**
  In screen coordinates ($+x$ right, $+y$ down), clockwise rotation produces $\Delta\theta > 0$ (seek forward) and counterclockwise rotation produces $\Delta\theta < 0$ (seek backward).
- **Wrap-around & Multi-Revolution Scrubbing:**
  Because $\Delta\theta$ is accumulated incrementally rather than mapped to a fixed absolute clock position, crossing the $\pm\pi$ seam or the 6 o'clock gap never produces a jump or discontinuity. Dragging across multiple full revolutions continues smoothly until clamped at `0L` or `durationMs`.
- **Sensitivity & Scaling (`msPerFullRevolution`):**
  - Default mode: scales smoothly with track duration while keeping fine control on long tracks:
    - For tracks $\le 5$ minutes: 1 full $360^\circ$ rotation equals the track duration (clamped to a minimum of `15_000 ms` so short clips don't jump uncontrollably).
    - For tracks $> 5$ minutes (e.g., DJ mixes / podcasts): 1 full rotation maps to `300_000 ms` (5 minutes), allowing multi-revolution scrubbing across the entire file without losing precision.
  - Configurable `sensitivityMultiplier` (default `1.0f`) and `msPerFullRevolutionOverride` allow deterministic unit testing and tuning.
- **Micro-Jitter Dead Zone:**
  - Before entering active `SCRUBBING`, the pointer must move past a small angular/linear movement threshold (`minActivationArcRad = 0.035 rad` / platform `touchSlop * 0.45f`), preventing accidental taps on the bezel from causing unintended seeks.
  - While scrubbing, sub-threshold single-frame sensor noise (`|deltaRad| < 0.002 rad`) is ignored so resting a finger on the ring holds the exact preview timestamp steady.

## 3. Gesture Ownership & Conflict Prevention
| Zone | Radius Range (fraction of bezel outer radius $R$) | Owner | Gesture |
| --- | --- | --- | --- |
| Inner Album Artwork | $r \le 0.72 R$ | `OrbitalGestureController` (`orbitalArtworkLongPressDrag`) | Long-press $\rightarrow$ vertical drag to switch songs |
| Outer Circular Ring Touch Band | $0.74 R \le r \le 1.18 R$ | `CircularScrubController` (`CircularSeekRing`) | Drag along ring $\rightarrow$ continuous circular seek |

- **Mutual Exclusion State Machine:**
  - `CircularSeekRing` refuses to start a scrub if `orbitalController.isOrbitVisible || orbitalController.holdProgress > 0f`.
  - `orbitalArtworkLongPressDrag` refuses to start if `scrubController.isScrubbing` is true.
  - If Orbital Mode activates or the active track changes mid-scrub, `CircularScrubController.cancel()` immediately discards the preview without calling `MediaController.seekTo()`.
  - Multi-touch interruptions (second finger down) or Android pointer cancellation (`!changedToUpIgnoreConsumed()`) abort scrubbing cleanly with zero side effects.

## 4. Playback Integration (Media3 Single Source of Truth)
- `PlaybackUiState` exposes `isSeekable: Boolean` populated from `MediaController.isCurrentMediaItemSeekable`.
- Scrubbing is disabled when `durationMs <= 0L` or `!isSeekable`.
- During active dragging:
  - Media3 playback continues uninterrupted (no `seekTo` storm on every pointer event).
  - Visual arc, handle glow, 6 o'clock timestamp, and subtle `±delta` indicator update purely from local `CircularScrubState.previewPositionMs`.
  - One subtle haptic tick (`HapticFeedbackType.TextHandleMove`) confirms scrub activation.
- On finger release:
  - `MediaController.seekTo(previewPositionMs)` is called **once**.
  - `CircularScrubState` returns to `IDLE` and resumes tracking live Media3 `positionMs`.

## 5. Module Layout
- `player/ui/CircularScrubState.kt` — Pure, unit-testable state machine & angular math (`CircularScrubEngine`, `CircularScrubPhase`, `CircularScrubSnapshot`, `CircularScrubController`).
- `player/ui/CircularSeekRing.kt` — Compose ring canvas, expanded invisible outer touch band, subtle active handle, 6 o'clock preview timestamp & `±delta` readout.
- `player/ui/CircularAlbumArtwork.kt` & `player/ui/NowPlayingScreen.kt` — Mutual exclusion wiring between `CircularSeekRing` and `OrbitalGestureController`.
- `app/src/test/java/com/example/mdownload/CircularScrubTest.kt` — Unit tests covering clockwise/counterclockwise dragging, 0°/360° wrap-around, clamping, micro-jitter, cancel without commit, multi-revolution scrubbing, unknown/zero duration, unseekable state, and single seek commit.

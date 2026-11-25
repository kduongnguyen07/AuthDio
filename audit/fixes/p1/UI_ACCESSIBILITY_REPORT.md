# HiFiDeck Android — UI Accessibility & Touch Target Report (`UI-03`, `UI-06`, `UI-07`)

**Date:** 2026-11-24  
**Device:** vivo X200s (`V2458A`, `1260 x 2800 px`, `~3.285 px/dp` -> `48.dp ≈ 158 px`)  
**Scope:** `UI-03` (`CircularSeekRing` semantics), `UI-06` (`HomeScreen` URL input `NAF="true"`), `UI-07` (`>= 48.dp` touch targets)

---

## 1. `UI-03` — Accessible `CircularSeekRing`

### Changes (`player/ui/CircularSeekRing.kt` & `player/ui/CircularScrubState.kt`)
- Added Compose `semantics {}` to `CircularSeekRing`:
  - `contentDescription = localText("Playback progress")`
  - `stateDescription`: `"mm:ss / mm:ss"` for known duration, or `localText("Duration unknown")` when `durationMs <= 0L`.
  - `progressBarRangeInfo`: `ProgressBarRangeInfo(shownFraction, 0f..1f)` for determinate tracks, or `ProgressBarRangeInfo.Indeterminate` for unknown duration.
  - `setProgress`: maps fraction via `CircularScrubEngine.seekPositionForProgressFraction(targetFraction, dur)` (clamped `[0, durationMs]`, guarding `NaN`/`Infinity`/`<= 0L`) and invokes `onCommitSeek(targetMs)`.
  - `customActions`: `"Seek forward 10 seconds"` and `"Seek backward 10 seconds"` when `interactiveSeeking && isSeekable && safeDuration > 0L`.

### Verification
1. **JVM Unit Test (`CircularScrubAndUiP1Test.ui03_seekPositionForProgressFraction_clampsAndGuardsUnknownDuration`)**: **PASS** (`0f`, `0.5f`, `1f`, out-of-range clamping, `NaN`/`Infinity`, `durationMs <= 0L`).
2. **On-Device Compose Semantics Test (`P1UiAndAccessibilityInstrumentedTest.ui03_circularSeekRing_semanticsSetProgressAndCustomActionsAndUnknownDuration`)**: **PASS** on vivo X200s (`SetProgress(0.5f) -> 100_000ms`, forward `+10s -> 60_000ms`, backward `-10s -> 40_000ms`, unknown duration -> `Indeterminate` + `"Duration unknown"`).
3. **Live vivo X200s Android Accessibility Hierarchy (`studio_ui_state` on `NowPlayingScreen`)**:
   ```xml
   <node bounds="[15,705][1245,1935]" class="android.widget.SeekBar" content-desc="Playback progress" drawing-order="0">
     <node bounds="[595,1737][665,1774]" class="android.widget.TextView" drawing-order="0" text="0:25"/>
   </node>
   ```
   Android's Compose-to-Accessibility bridge exposes the ring natively as an `android.widget.SeekBar` with `content-desc="Playback progress"`.

---

## 2. `UI-06` — `HomeScreen` URL Input `NAF="true"` Eliminated

### Root Cause
`HiFiGlassTextField` (`ui/components/SearchField.kt`) rendered a raw `BasicTextField` with placeholder text in a sibling `Text` node, leaving the editable `EditText` node with empty accessibility description (`NAF="true"` in `uiautomator`).

### Changes
- Added optional `contentDescription: String? = null` to `HiFiGlassTextField`, resolving `resolvedA11yLabel = (contentDescription?.takeIf { it.isNotBlank() } ?: placeholder).ifBlank { localText("Text input") }` and applying `Modifier.semantics { this.contentDescription = resolvedA11yLabel; ... }` directly on `BasicTextField`.
- Updated `HomeScreen.kt` to pass `contentDescription = localText("Audio or YouTube URL")`.

### Verification
1. **On-Device Compose Test (`P1UiAndAccessibilityInstrumentedTest.ui06_hiFiGlassTextField_exposesContentDescriptionAnd48dpClearTarget`)**: **PASS** — node found via `onNodeWithContentDescription("Audio or YouTube URL")`, accepts text input, and exposes `>= 48.dp x 48.dp` Clear button.
2. **Live vivo X200s Android UI Hierarchy (`studio_ui_state` on `HomeScreen`)**:
   ```xml
   <node bounds="[260,670][1128,828]" class="android.widget.EditText" clickable="true" drawing-order="0" focusable="true" long-clickable="true">
     <node bounds="[260,727][1128,771]" class="android.view.View" content-desc="Audio or YouTube URL" drawing-order="0"/>
   ```
   - **Zero `NAF="true"` nodes** anywhere on `HomeScreen`!
   - Input height: `828 - 670 = 158 px` (`48.0 dp`).

---

## 3. `UI-07` — Minimum `48.dp` Touch Target Remediation

| Surface / File | Control | Before | After | Live / Instrumented Proof |
| :--- | :--- | :--- | :--- | :--- |
| `SearchField.kt` | Clear button | `32.dp` | `48.dp` | Instrumented `ui06`: `>= 48.dp x 48.dp` |
| `HomeScreen.kt` | Paste link button | `38.dp` | `48.dp` | Live vivo X200s XML: `[983,479][1141,637]` = **`158 x 158 px (48.0 dp)`** |
| `HomeScreen.kt` | Edit metadata button (inline card) | `36.dp` | `48.dp` | Code updated to `Modifier.size(48.dp)` |
| `MiniPlayer.kt` | Play/Pause & Skip Next | `44.dp` | `48.dp` | Live vivo X200s XML: `[872,2278][1030,2436]` & `[1030,2278][1188,2436]` = **`158 x 158 px (48.0 dp)`** + Instrumented `ui07` |
| `OrbitalDesignTokens.kt` / `PlayerControls.kt` | Shuffle & Repeat (`ModeButtonSize`) | `44.dp` | `48.dp` | Live vivo X200s XML: `[109,2080][267,2238]` & `[993,2080][1151,2238]` = **`158 x 158 px (48.0 dp)`** + Instrumented `ui07` |
| `NowPlayingScreen.kt` | Next Songs Orbital icon button | `34.dp` | `48.dp` | Live vivo X200s XML: `[1023,2330][1181,2488]` = **`158 x 158 px (48.0 dp)`** |
| `PlaybackQueueSheet.kt` | Move up, Move down, Remove | `32.dp` | `48.dp` | Instrumented `ui07`: all 3 verified `>= 48.dp x 48.dp` |
| `OrbitalNavigator.kt` | Top-right Close (`×`) | `40.dp` | `48.dp` hit box around `40.dp` glass circle | Instrumented `ui07`: `>= 48.dp x 48.dp` (preserves visual 40dp glass orb) |
| `OrbitalNavigator.kt` | Accessible Previous / Next step buttons | `40.dp` | `48.dp` | Instrumented `ui07`: `>= 48.dp x 48.dp` |
| `LyricsScreen.kt` | Notice banner Dismiss button | `30.dp` | `48.dp` + `contentDescription = "Dismiss"` | Instrumented `ui07`: `>= 48.dp x 48.dp` |
| `LyricsScreen.kt` | `OffsetStepButton` | `< 48.dp` | `defaultMinSize(48.dp, 48.dp)` | Code updated cleanly |

---

## 4. Honest Out-of-Scope Observation (Follow-up Candidate)
During live `SettingsScreen` inspection on vivo X200s, standalone Material 3 `Switch` rows in `SettingsScreen` (e.g., "Embed artwork", "ASCII filenames") appeared with `NAF="true"` because their `Switch` composables are siblings of their `Text` labels rather than merged via `Modifier.toggleable` on the parent row. Those `SettingsScreen` switches were outside the P1 `UI-06` scope (`HomeScreen` URL input / `HiFiGlassTextField`) and are logged here transparently for future polish.

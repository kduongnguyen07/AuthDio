# HiFiDeck Android — P1 Stabilization Fix Summary (`STAB-04` & `STAB-03`)

**Date:** 2026-11-24  
**Repository:** `D:\MDownload` (`com.example.mdownload`)  
**Target Hardware:** vivo X200s (`V2458A`, serial `10AF8A0YUH005HA`, OriginOS 7, Android 17 / API 37, `arm64-v8a`)  
**Scope:** P1 Stabilization (`STAB-04` Security & Subprocess Reliability + `STAB-03` Circular Scrubbing, UI Consistency & Accessibility)  
**Out of Scope (Preserved Untouched):** `STAB-05` startup/APK optimization, feature expansion, historical `audit/00..12` and P0 `audit/fixes/*.md` reports.

---

## Executive Summary

All 12 P1 stabilization items across `STAB-04` (7 items) and `STAB-03` (5 items) have been implemented, compiled with zero errors, and validated across **112 JVM unit tests** (89 P0 baseline + 18 `STAB-04` + 5 `STAB-03`, **112/112 PASS**) and **21 on-device instrumented tests** on vivo X200s (14 P0/baseline + 7 new `STAB-03`/Phase C tests, **21/21 PASS**), plus live Android UI hierarchy and rendered pixel verification on vivo X200s.

All P0 stabilization guarantees (`SafeAudioWritePipeline` recovery journaling, shared `AudioContainerPrimitives`, ReplayGain single-authority DSP handoff, Sleep Timer fade restoration, logical queue-end detection, and live MediaSession metadata synchronization) remain intact and re-verified on device.

---

## Defect Remediation Matrix

| Audit ID | Workstream | Severity | Summary of Remediation | Key Files | Verification Status |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `SEC-01` | `STAB-04` (A1) | High | Added canonical `Path.startsWith(Path)` containment guard (`OutputPathGuard`) rejecting `..`, symlinks, directories, control chars, and out-of-sandbox marker paths from `yt-dlp --print after_move:filepath`. | `engine/OutputPathGuard.kt`, `engine/DownloadQueueManager.kt` | **VERIFIED** (JVM tests + code integration) |
| `SEC-02` | `STAB-04` (A2) | High | Hardened `--replace-in-metadata title` in `YtDlpEngine` with `(?s)\A.*\Z` full-string anchor and `escapePythonRegexReplacement()` escaping `\` -> `\\` to prevent Python `re.sub` backreference injection, trailing-backslash crash, and double replacement. | `engine/YtDlpEngine.kt` | **VERIFIED** (JVM tests) |
| `AUD-08` | `STAB-04` (A3) | High | Replaced raw blocking `Process.waitFor` / single-stream reads in `AudioInspectorEngine` with `BoundedSubprocessRunner` (concurrent daemon stdout/stderr drain threads, bounded memory caps, wall-clock timeout, responsive coroutine cancellation, escalating `destroy` -> `destroyForcibly`). | `core/audio/BoundedSubprocessRunner.kt`, `core/audio/AudioInspectorEngine.kt` | **VERIFIED** (Live JVM child-process fixtures + vivo X200s live `AudioInspectorSheet`) |
| `AUD-09` | `STAB-04` (A4) | High | Hardened `LoudnessScanner` with duration-aware timeout (`30s..180s`, 45s default), `BoundedSubprocessRunner`, `isRelevantEbuR128Line` filter (prevents per-frame 100ms progress spam from truncating final `Summary:`), structured `LoudnessScanResult`, and non-caching of failed/incomplete scans in `AudioAnalysisRepository`. | `core/audio/LoudnessScanner.kt`, `core/audio/AudioAnalysisRepository.kt` | **VERIFIED** (Live JVM child-process fixtures including noisy long-track simulation + `AudioAnalysisRepository` tests) |
| `SEC-04` | `STAB-04` (A5) | High | Hardened `YouTubeUrlParser.normalizeUrl()` (strictly offline syntactic & IP-literal validation rejecting bad schemes, credentials, backslashes, malformed `%`, invalid ports, local/single-label/trailing-dot hosts, alternate IPv4 encodings, private/loopback/link-local/CGNAT/multicast/reserved IPv4 & IPv6, with IDN normalization) + off-main-thread `validateResolvedHost()` wired into `YtDlpEngine`. | `core/YouTubeUrlParser.kt`, `engine/YtDlpEngine.kt` | **VERIFIED** (Comprehensive URL matrix JVM tests; DNS-rebinding/redirect boundary documented honestly) |
| `SEC-03` | `STAB-04` (A6) | Medium | Updated `data_extraction_rules.xml` and `backup_rules.xml` to include only `hifideck_data` while explicitly excluding `hifideck_data/pending_writes`, `settings.log`, `settings.json`, `settings.tmp`, `listening_history.json`, `audio_analysis_cache.json`, `playback_state.json`, and `sleep_timer.json` (implicitly excluding `hifideck_work`, `hifideck_archives`, caches). | `res/xml/data_extraction_rules.xml`, `res/xml/backup_rules.xml` | **VERIFIED** (0 Lint errors + Phase C on-device recovery coexistence test) |
| `SEC-05` | `STAB-04` (A7) | Medium | Added `LogSanitizer` redacting query params, URL credentials, bearer/auth tokens, cookies, and stripping control chars/log-injection newlines; wired into `SettingsStore.appendLog()` and `DownloadQueueManager.appendLog()`. | `core/LogSanitizer.kt`, `core/SettingsStore.kt`, `engine/DownloadQueueManager.kt` | **VERIFIED** (JVM tests) |
| `UI-01` | `STAB-03` (B1) | High | Eliminated 45°/135°/225°/315° circular seek dead zones by moving `artworkTouchModifier` off the inner square `Box` onto the unified outer `1.22 * bezelDiameter` container gated by true polar distance (`hypot(dx, dy) <= 0.72R`), and expanding the touch container from `bezelDiameter + 36.dp` to `bezelDiameter * 1.22f`. | `player/ui/CircularScrubState.kt`, `player/ui/OrbitalGestureHandler.kt`, `player/ui/CircularAlbumArtwork.kt`, `player/ui/NowPlayingScreen.kt` | **VERIFIED** (JVM polar tests + vivo X200s Compose touch tests across all 9 angles & seam crossings) |
| `UI-02` | `STAB-03` (B2) | High | Added opaque `surfaceElevated` token (`#1B1726` Dark / `#FAF8FD` Light) and replaced hardcoded `OrbitalDesignTokens.Colors.BezelMid` in `AudioInspectorSheet`, `SleepTimerSheet`, `TrackMetadataEditorSheet`, and `BatchMetadataEditorSheet` with theme-aware surfaces, borders, and WCAG AA (`>= 4.5:1`) contrast accents. | `ui/design/Color.kt`, `player/ui/AudioInspectorSheet.kt`, `player/ui/SleepTimerSheet.kt`, `player/ui/TrackMetadataEditorSheet.kt` | **VERIFIED** (WCAG 2.1 math JVM tests + Compose Light/Dark instrumented tests + vivo X200s rendered RGB pixel measurement `#1A1725` Dark vs `#FAF8FD` Light) |
| `UI-03` | `STAB-03` (B3) | Medium | Added full TalkBack/accessibility semantics to `CircularSeekRing`: `contentDescription`, `stateDescription`, `ProgressBarRangeInfo` (determinate & `Indeterminate` on unknown duration), `setProgress` wired to `onCommitSeek`, and `±10s` custom actions. | `player/ui/CircularSeekRing.kt` | **VERIFIED** (JVM tests + vivo X200s Compose semantics test + live vivo X200s XML `android.widget.SeekBar`) |
| `UI-06` | `STAB-03` (B4) | Medium | Added explicit `contentDescription` & `stateDescription` directly onto `BasicTextField` inside `HiFiGlassTextField` (and passed `"Audio or YouTube URL"` from `HomeScreen`), eliminating `NAF="true"` on the Home URL input. | `ui/components/SearchField.kt`, `ui/screens/HomeScreen.kt` | **VERIFIED** (Compose instrumented test + live vivo X200s `HomeScreen` XML showing `content-desc="Audio or YouTube URL"` and zero `NAF`) |
| `UI-07` | `STAB-03` (B5) | Medium | Enlarged undersized interactive touch targets to `>= 48.dp` across `SearchField` Clear, `HomeScreen` Paste/Edit metadata, `PlaybackQueueSheet` row actions, `MiniPlayer` Play/Pause & Next, `PlayerControls` Shuffle & Repeat, `NowPlayingScreen` Orbital trigger, `OrbitalNavigator` Close & step controls, and `LyricsScreen` notice dismiss & offset steps. | `ui/components/SearchField.kt`, `ui/screens/HomeScreen.kt`, `player/ui/PlaybackQueueSheet.kt`, `player/ui/MiniPlayer.kt`, `player/ui/OrbitalDesignTokens.kt`, `player/ui/NowPlayingScreen.kt`, `player/ui/OrbitalNavigator.kt`, `player/ui/LyricsScreen.kt` | **VERIFIED** (vivo X200s Compose bounds test + live vivo X200s XML bounds `158px x 158px = 48.0dp`) |

---

## Supporting Reports

1. `SECURITY_HARDENING_REPORT.md` — `SEC-01`, `SEC-02`, `SEC-03`, `SEC-04`, `SEC-05`, Phase C backup/recovery coexistence, and honest SSRF residual boundary disclosure.
2. `SUBPROCESS_RELIABILITY_REPORT.md` — `AUD-08` (`AudioInspectorEngine`) and `AUD-09` (`LoudnessScanner` + `AudioAnalysisRepository`) architecture, failure-mode matrix, and long-track EBU R128 bug catch.
3. `URL_VALIDATION_TEST_MATRIX.md` — Complete accepted/rejected URL, IPv4/IPv6 obfuscation, IDN, and DNS resolution test matrix.
4. `CIRCULAR_GESTURE_FIX_REPORT.md` — `UI-01` geometric root cause, polar separation proof, 9-angle + seam + multi-touch + track-swap test evidence.
5. `UI_ACCESSIBILITY_REPORT.md` — `UI-03`, `UI-06`, `UI-07` accessibility semantics, live vivo X200s XML hierarchy proof, and remaining out-of-scope observations.
6. `THEME_VISUAL_QA.md` — `UI-02` Dark & Light WCAG 2.1 contrast tables, Compose Light/Dark render tests, and live vivo X200s hardware RGB pixel measurements.
7. `P1_REGRESSION_REPORT.md` — Full JVM (`112/112`) and vivo X200s instrumented (`21/21`) test execution ledger, P0 preservation matrix, and logcat health summary.

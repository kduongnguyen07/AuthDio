# HiFiDeck (`com.example.mdownload`) — Executive Audit Summary

**Audit Scope:** Read-only technical, architectural, audio-pipeline, metadata-integrity, UI/UX, security, performance, and product audit.  
**Repository:** `D:\MDownload` (`:app`, `versionName = "1.1"`, `versionCode = 2`)  
**Primary Target Device Verified:** vivo X200s (`V2458A`, product `PD2415M`), `OriginOS 7`, Android 17 (API 37), `arm64-v8a` only, `1260×2800` @ `525` override dpi, **153 MediaStore audio tracks** present.  
**Audit Date:** 2026-11-24

---

## 1. Overall Application Maturity

**Assessment:** **Advanced Beta / Power-User Prototype (High Feature Breadth, Medium Production Hardening).**

HiFiDeck is a remarkably ambitious single-module Android application combining an embedded `yt-dlp` + `FFmpeg` + Python/QuickJS audio downloader with a Media3 (`1.9.2`) offline music player, custom Orbital Navigation + Circular Scrubbing Compose UI, synchronized lyrics pipeline (LRCLIB + `.lrc` + tag embedding), ReplayGain DSP audio processor, Audio Quality Inspector, single/batch Metadata Editor, Smart Playlists, Listening History, and Sleep Timer.

Its **core domain math and happy-path playback** are clean and well-tested (**71/71 JVM unit tests** and **11/11 on-device instrumented tests** independently executed and passing during this audit, including the 2 previously unexecuted tests). However, **production hardening lags behind feature velocity**:
- Several high-risk correctness and data-integrity defects exist in the audio gain pipeline, non-atomic `content://` tag writes, ID3v2/MP4/Ogg tag handling, and gesture hit-testing.
- Previous internal design/validation documents (`AUDIO_PIPELINE.md`, `FEATURE_EXPANSION_DESIGN.md`, `FEATURE_VALIDATION_REPORT.md`) contain **5 direct factual contradictions** against the actual codebase.
- The debug APK weighs **264.53 MB** (`138.83 MB` wasted on unused ABIs on a `minSdk = 35` 64-bit-only target), cold start averages **740 ms median** on a flagship vivo X200s due to synchronous Main-Thread JSON I/O across 5 repositories, and Light Theme renders **dark text on hardcoded dark navy bottom sheets**.

---

## 2. Verified Strengths

1. **Solid Media3 Playback Foundation:** `HiFiPlaybackService` + `PlaybackController` properly integrate `ExoPlayer`, `MediaSessionService`, `MediaButtonReceiver`, audio focus (`handleAudioFocus = true`), `handleAudioBecomingNoisy = true`, and background playback restoration (`PlaybackStateStore`). Verified live on vivo X200s (`realDevicePlayback_exercisesMedia3ServiceQueueControlsAndMetadata` PASS).
2. **All 82 Automated Tests Pass (71 JVM + 11 Instrumented):** Independently re-executed `:app:testDebugUnitTest --rerun-tasks` (71/71 PASS) and `ExampleInstrumentedTest` + all 10 tests in `MusicPlayerInstrumentedTest` on vivo X200s (11/11 PASS), closing the previously unexecuted gap for `sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel` and `audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly`.
3. **Pure-Kotlin Domain Engines:** `ReplayGainCalculator`, `OrbitalSelectionEngine`, `CircularScrubEngine`, `LyricsParser`, `LyricsSyncEngine`, `LyricsMatcher`, `ListeningSessionTracker`, `SmartPlaylistEvaluator`, and `SleepTimerMath` are cleanly separated from Android framework classes and thoroughly covered by JVM tests.
4. **Honest Audio Quality Reporting (Source vs. Output Separation):** `AudioInspectorEngine` refuses to fabricate bit depth for lossy codecs (`MP3`, `AAC`, `Opus`) and displays an explicit lossless transcode caveat when `isDownloadedByApp = true` on FLAC files.
5. **Resilient Download Pipeline:** `DownloadQueueManager` + `DownloadForegroundService` + `StorageTools` implement WakeLock + WiFiLock, parallel queue workers (1–6 files × 1–8 threads), duplicate fingerprinting (`MDOWNLOAD_YT_ID`), and retained-audio recovery when MediaStore/SAF export fails.

---

## 3. High-Risk Weaknesses & Confirmed Critical Issues

| Severity | ID | Confirmed Issue | Primary Files |
|---|---|---|---|
| **P0** | **AUD-01** | **Double ReplayGain Attenuation Race:** `PlaybackGainCoordinator` sets both `audioProcessor` target AND `player.volume` fallback when `!audioProcessor.isProcessingPcm`; when ExoPlayer later configures PCM (`onConfigure()`), no callback notifies the coordinator, causing simultaneous DSP + `player.volume` attenuation (e.g., `-6 dB` becomes `-12 dB`) + unsynchronized `currentGain` field race. | `PlaybackGainCoordinator.kt`, `ReplayGainAudioProcessor.kt` |
| **P0** | **META-01** | **Non-Atomic `content://` File Truncation Window:** Both `MetadataRepository.commitContentUri()` and `LyricsTagIO.embedLyricsIntoUri()` open `openOutputStream(uri, "wt")` (immediately truncating user's original audio file to 0 bytes) before streaming back; a crash, process kill, or storage-full error mid-copy permanently destroys the audio file. | `MetadataRepository.kt`, `LyricsTagIO.kt` |
| **P0** | **META-02** | **MP3/MP4/Ogg Tag Corruption & Silent Data Loss:** ID3v2.4 per-frame unsync bodies are de-unsynchronized while keeping the unsync flag bit `0x02` set (corrupting frames); ID3v2.2 tags are silently wiped on rewrite; MP4 FFmpeg remux omits `-movflags use_metadata_tags` (dropping ReplayGain freeform atoms) and `readMp4BasicMetadata()` is a no-op stub returning empty fields; Ogg/Opus artwork edits are silently ignored while returning `Success`; audio SHA-256 verification is skipped (`null`) for MP4/Ogg. | `MetadataTagIO.kt`, `LyricsTagIO.kt` |
| **P1** | **UI-01** | **4 Diagonal Dead Zones on Circular Seek Ring:** In `CircularAlbumArtwork`, the inner square `Box` (`0.72 * bezelDiameter`) uses `clip(CircleShape)` (which does not clip Compose pointer hit-testing); its square corners reach `1.018R`, intercepting touches in the `0.74R..1.018R` ring zone at 1:30, 4:30, 7:30, and 10:30 o'clock. | `CircularAlbumArtwork.kt`, `CircularSeekRing.kt` |
| **P1** | **UI-02** | **Light Mode Unreadable Bottom Sheets:** `AudioInspectorSheet`, `SleepTimerSheet`, `TrackMetadataEditorSheet`, and `BatchMetadataEditorSheet` hardcode `containerColor = OrbitalDesignTokens.Colors.BezelMid` (`#1B2132`) while drawing `HiFiTheme.colors.inkPrimary` (`#181223` in Light Theme). | `AudioInspectorSheet.kt`, `SleepTimerSheet.kt`, `TrackMetadataEditorSheet.kt` |
| **P1** | **AUD-02** | **Sleep Timer Expiry Volume Spike & Repeat-Mode Failures:** `expireAndPause()` immediately resets `sleepFadeFactor = 1.0f` before AudioTrack drains/pauses; `END_OF_QUEUE` checks `!hasNextMediaItem()` which is always `false` under `REPEAT_MODE_ALL`/`REPEAT_MODE_ONE`. | `SleepTimerController.kt` |
| **P1** | **PERF-01** | **264.53 MB Multi-ABI APK Bloat & Main-Thread Startup I/O:** Packaging 4 ABIs (`arm64-v8a`, `armeabi-v7a`, `x86`, `x86_64`) wastes `138.83 MB` (`52.5%` of APK) on `minSdk = 35` devices; 5 repositories execute synchronous JSON disk reads on Main Thread during `MDownloadApp.onCreate()`, driving `740 ms` median cold start. | `app/build.gradle.kts`, `MDownloadApp.kt` |
| **P1** | **PERF-02** | **Continuous Recompositions & O(N log N × M) Smart Playlists:** `MDownloadRoot` collects `playbackState` at root (recomposing on every progress tick); `NowPlayingScreen`/`OrbitalNavigator` read `Animatable.value` in composition; `ListeningSessionTracker` rewrites `listening_history.json` every 10s, triggering full Main-Thread Smart Playlist re-evaluation (`resolveStats` O(M) linear scan). | `MDownloadRoot.kt`, `NowPlayingScreen.kt`, `SmartPlaylistEvaluator.kt`, `ListeningHistoryRepository.kt` |
| **P1** | **SEC-01** | **yt-dlp Output Path Trust, Regex Injection & Unredacted Backups:** `resolveProducedFile()` trusts `HF_FILE:` path from yt-dlp stdout without canonical containment verification; `--replace-in-metadata` receives unescaped `title` (Python `re.sub` backreference injection); `allowBackup="true"` with empty rules backs up URLs/logs/pending audio. | `DownloadQueueManager.kt`, `YtDlpEngine.kt`, `AndroidManifest.xml`, `backup_rules.xml` |

---

## 4. Feature Completeness Summary

| Domain | Status | Evidence Summary |
|---|---|---|
| **Downloader (yt-dlp + FFmpeg + Queue + Share)** | Functional (with hardening gaps) | Core queue, SAF/MediaStore export, duplicate fingerprinting, Share sheet implemented; path confinement & regex escaping needed. |
| **Media3 Playback & Session** | Verified on Device | 11/11 instrumented tests pass on vivo X200s; `MediaSessionService`, Bluetooth/headset receiver, queue ops verified. |
| **Orbital Navigation & Circular Scrubbing** | Implemented, Partially Verified | Pure math thoroughly unit-tested (17 tests); live UI has 4 diagonal ring hit-test dead zones, per-frame recomposition, and missing TalkBack seek semantics. |
| **Synchronized Lyrics (LRCLIB + LRC + Tag Embed)** | Implemented, Partially Verified | Parser, sync engine, matcher, MP3/FLAC tag round-trip unit-tested; `LyricsTagIO` duplicates `MetadataTagIO` ID3v2/truncation bugs. |
| **ReplayGain & Loudness Normalization** | Implemented with P0 DSP Bug | Math & tags verified, PCM16 processor verified on device, but `PlaybackGainCoordinator` has double-attenuation transition bug & no bypass at unity gain. |
| **Audio Quality Inspector** | Verified on Device (with I/O caveats) | Verified on real vivo X200s track; however copies full file to `cacheDir` per inspect and `ffprobe` has stderr pipe deadlock/timeout order risk. |
| **Metadata Editor (Single & Batch)** | Partially Verified, P0 Risks | MP3/FLAC basic round-trip unit-tested; MP4/Ogg unverified, non-atomic `content://` writes, ID3v2.2/2.4 bugs, stale MediaSession notification metadata after edit. |
| **Smart Playlists & Listening History** | Verified (with Scale/Rescan Gaps) | All 7 built-ins + custom AND/OR rules + session tracking unit & persistence tested; lacks cross-rescan stableId fallback & has O(N log N × M) Main-Thread scaling bottleneck. |
| **Sleep Timer** | Verified on Device (with Edge Bugs) | Start/extend/cancel verified on vivo X200s; expiry volume spike, `END_OF_QUEUE` + Repeat interaction, and frozen countdown without active player remain. |

---

## 5. Top 10 High-Value Opportunities

1. **Fix ReplayGain/SleepTimer Gain Authority & PCM Bypass (`AUD-01`, `AUD-02`)** — Eliminate double attenuation, add `onConfigure`/`onReset` listener, fix `currentGain` thread visibility, bypass copy at unity gain, and fix expiry fade-restore race.
2. **Harden Metadata & Lyrics Tag I/O (`META-01`, `META-02`)** — Unify shared ID3v2/FLAC engine (eliminate 1,000+ lines of duplicated code between `MetadataTagIO` and `LyricsTagIO`), fix ID3v2.4 unsync/ID3v2.2 preservation, fix MP4/Ogg preservation + read-back, and eliminate `wt` truncation loss.
3. **Eliminate APK Bloat & Main-Thread Startup I/O (`PERF-01`)** — Enable ABI splits or `arm64-v8a` focus (cut APK from 264.53 MB to ~125 MB, cutting install time in half) and move all 5 repo JSON loads off `MDownloadApp.onCreate()`.
4. **Fix Circular Seek Dead Zones, Recomposition Hotspots & Light-Mode Sheets (`UI-01`, `UI-02`)** — Circular hit-testing gate on inner artwork, defer `Animatable` reads to `graphicsLayer`/draw phase, scope `playbackState` collection below `MDownloadRoot`, and theme all 4 bottom sheets with `HiFiTheme.colors`.
5. **Optimize Smart Playlists & Listening History Persistence (`PERF-02`)** — Pre-index `statsByUri` (O(1)), evaluate off Main Thread, debounce/batch disk flushes instead of 10s full-JSON rewrites, and implement true cross-rescan track fingerprinting.
6. **True Gapless Playback & Crossfade** — Leverage ExoPlayer's native gapless support (and verify LAME/iTunSMPB/Opus pre-skip metadata handling) plus optional smooth crossfade inside the DSP pipeline.
7. **Parametric / 10-Band EQ with Headroom-Safe Preamp** — Integrate a clean floating-point biquad `AudioProcessor` chained with `ReplayGainAudioProcessor` under the same single gain authority.
8. **Room/SQLite Migration for Library, History, Analysis Cache & Playlists** — Replace 7 ad-hoc JSON files with transactional SQLite/Room storage for instant cold start, O(1) incremental updates, and 10,000-track scalability.
9. **Background Batch ReplayGain / EBU R128 Library Scanner** — `WorkManager`-driven opt-in loudness scanner with notification progress, pausing on low battery, populating `AudioAnalysisRepository` so ReplayGain works across untagged YouTube downloads.
10. **Modularize `MainViewModel` (2,082 lines) & `LibraryHubScreen` (2,213 lines)** — Split into focused feature ViewModels/state holders (Downloader, Library, Playback, Metadata/Inspector) for testability and reduced recomposition blast radius.

---

## 6. Top 5 Recommended Next Engineering Tasks (Stabilization First)

1. **`STAB-01` (P0):** Fix `PlaybackGainCoordinator` ↔ `ReplayGainAudioProcessor` lifecycle sync (eliminate double attenuation, make `currentGain` thread-safe, fix unity bypass) and `SleepTimerController` expiry volume spike + `END_OF_QUEUE` repeat handling.
2. **`STAB-02` (P0):** Fix `MetadataTagIO` & `LyricsTagIO` data-integrity bugs (ID3v2.4 unsync flag reset, ID3v2.2 preservation or explicit rejection, MP4 `-movflags use_metadata_tags` + real MP4 metadata reader, Ogg artwork error instead of silent success, pre-write backup/recovery around `content://` `wt` writes, and post-edit MediaSession metadata sync).
3. **`STAB-03` (P1):** Fix `CircularAlbumArtwork` diagonal hit-test dead zones, add TalkBack `setProgress` seek semantics + 48dp touch targets, and fix hardcoded dark `BezelMid` sheet backgrounds in Light Theme.
4. **`STAB-04` (P1):** Security & reliability hardening — enforce canonical `jobWorkDir` containment in `DownloadQueueManager.resolveProducedFile()`, escape `cleanMeta.title` before `--replace-in-metadata`, fix `AudioInspectorEngine.probeWithFfprobe()` stderr drain + timeout ordering, and tighten `backup_rules.xml` / `YouTubeUrlParser` SSRF checks.
5. **`STAB-05` (P1):** Startup & runtime performance — offload 5 synchronous JSON loads from `MDownloadApp.onCreate()`, configure ABI splits / `arm64-v8a` builds (saving 138.8 MB), fix `SmartPlaylistEvaluator` O(N log N × M) scan, and scope `playbackState` / `Animatable` reads out of `MDownloadRoot` and `NowPlayingScreen` composition.

---

## Audit Document Index

- [01_APPLICATION_SPEC.md](file:///D:/MDownload/audit/01_APPLICATION_SPEC.md) — Repository map, build/SDK/ABI config, dependencies, entry points, Android 15+/OriginOS 7 compatibility
- [02_FEATURE_AUDIT.md](file:///D:/MDownload/audit/02_FEATURE_AUDIT.md) — Phase 2 claim verification verdicts + Phase 3 complete functional inventory
- [03_ARCHITECTURE.md](file:///D:/MDownload/audit/03_ARCHITECTURE.md) — Phase 4 component architecture, concurrency, StateFlow, dead code, refactoring priorities
- [04_AUDIO_PIPELINE_AUDIT.md](file:///D:/MDownload/audit/04_AUDIO_PIPELINE_AUDIT.md) — Phase 5B ReplayGain DSP, Phase 5C Sleep Timer, EBU R128 scanner, Audio Quality Inspector
- [05_METADATA_INTEGRITY_AUDIT.md](file:///D:/MDownload/audit/05_METADATA_INTEGRITY_AUDIT.md) — Phase 5A MP3/FLAC/M4A/Ogg tag rewriting, audio SHA-256, container correctness, scoped storage
- [06_UI_UX_AUDIT.md](file:///D:/MDownload/audit/06_UI_UX_AUDIT.md) — Phase 5E Orbital/Circular gestures + Phase 9 UI/UX, accessibility, Light Theme, touch targets
- [07_SECURITY_AUDIT.md](file:///D:/MDownload/audit/07_SECURITY_AUDIT.md) — Phase 8 confirmed vulnerabilities vs. theoretical risks
- [08_PERFORMANCE_AUDIT.md](file:///D:/MDownload/audit/08_PERFORMANCE_AUDIT.md) — Phase 6 live vivo X200s measurements, APK bloat, cold start, recomposition, I/O bottlenecks
- [09_TECHNICAL_DEBT.md](file:///D:/MDownload/audit/09_TECHNICAL_DEBT.md) — Complete structured defect & debt register with severity, evidence, and remediation
- [10_PRODUCT_ROADMAP.md](file:///D:/MDownload/audit/10_PRODUCT_ROADMAP.md) — Phase 10 opportunities, "Do NOT develop yet" list, Phase 11 weighted scoring & P0–P3 backlog
- [11_TEST_COVERAGE_MATRIX.md](file:///D:/MDownload/audit/11_TEST_COVERAGE_MATRIX.md) — Every JVM & instrumented test mapped to features, assertions, and gaps
- [12_VALIDATION_REPORT.md](file:///D:/MDownload/audit/12_VALIDATION_REPORT.md) — Exact verification commands, live vivo X200s test runs, static analysis, and caveats

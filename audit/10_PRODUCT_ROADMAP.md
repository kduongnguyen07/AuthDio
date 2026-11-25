# HiFiDeck — Future Opportunities, Anti-Roadmap & Prioritized Backlog

**Audit Scope:** Phase 10 (Future Development Opportunities & Explicit "Do NOT Develop Yet" List) + Phase 11 (Disclosed Weighted Scoring Model & Prioritized P0–P3 Engineering Backlog).  
**Audit Date:** 2026-11-24

> **No Fabricated Timelines:** Per audit guidelines, effort is expressed in relative engineering complexity (`XS`–`XL`) without invented calendar dates or release ETAs.

---

## 1. Phase 10 — Evaluation of Future Development Opportunities

| Opportunity | Product & Engineering Assessment | Prerequisites | Verdict |
|---|---|---|---|
| **1. True Gapless Playback** | High value for live albums, DJ mixes, and concept albums. Media3 `ExoPlayer` already supports gapless transitions when MP3 LAME/Xing encoder delay, MP4 `elst`/`iTunSMPB`, and Opus pre-skip metadata are intact, provided `ReplayGainAudioProcessor` handles `onFlush` smoothly without introducing silence buffers or double-attenuation steps. | `STAB-01` (fix `ReplayGainAudioProcessor` + track-switch pre-resolution) | **STRONG YES** (Track 3) |
| **2. Configurable Crossfade (1–12s)** | High delight factor for casual & party listening. Requires dual-buffer or dual-player overlap orchestration (`ExoPlayer` does not support native single-player overlapping crossfade without two players/renderers or custom mixer) coordinated with `PlaybackGainCoordinator` so ReplayGain & Sleep Timer stay single-authority. | `STAB-01`, gapless verification | **YES — LATER** (Track 4, Medium-High complexity) |
| **3. Parametric / 10-Band Equalizer (Custom Float `AudioProcessor`)** | Differentiator for audiophiles on vivo X200s + IEMs/USB DACs. Avoid Android's legacy `android.media.audiofx.Equalizer` (device-inconsistent 5-band, conflicts with OriginOS effects). Implement a clean 64-bit-coefficient biquad `AudioProcessor` (PCM_FLOAT) chained with `ReplayGainAudioProcessor`, sharing a single headroom/clipping guard. | `STAB-01` + unity-gain bypass | **STRONG YES** (Track 4) |
| **4. Room / SQLite Migration** | Essential for scaling beyond ~1,000 tracks. Replaces 5–7 full-rewrite JSON files (`listening_history.json`, `audio_analysis_cache.json`, `playlists.json`, `smart_playlists.json`, `artists.json`) with indexed queries, ACID transactions, instant cold start, and stable cross-rescan track identity. | `STAB-05` quick wins first | **STRONG YES** (Track 3) |
| **5. Background Batch EBU R128 Loudness Scanner (`WorkManager`)** | Bridges the biggest ReplayGain usability gap: most YouTube downloads & local MP3s lack ReplayGain tags. A `WorkManager` opt-in batch scanner (charging/battery-aware, throttled concurrency, progress notification, optional tag write-back) makes ReplayGain immediately useful library-wide. | `AUD-08`/`AUD-09` timeout fix + Room/indexed analysis cache | **STRONG YES** (Track 3) |
| **6. Audio Engine / Tag Engine Unification & Modularization** | Extracting `:core-audio-tags` (unifying `MetadataTagIO` + `LyricsTagIO`) and splitting `MainViewModel` (2,082 lines) & `LibraryHubScreen` (2,213 lines) unlocks JVM testability, cuts build times, and prevents duplicated binary bugs. | `STAB-02` | **STRONG YES** (Track 2/3) |
| **7. True Audio Offload & USB DAC Bit-Perfect Inspection** | On vivo X200s (USB-C DACs + Bluetooth LDAC/aptX), properly reading `AudioManager.getAudioDevicesForAttributes()` + `AudioTrack.getRoutedDevice()` and genuinely bypassing DSP when ReplayGain & EQ are OFF enables hardware offload and battery savings. Note: true Android bit-perfect USB DAC requires Android 14+ `AudioMixerAttributes.MIXER_BEHAVIOR_BIT_PERFECT` (limited to supported USB devices). | `AUD-03`, `AUD-07` | **YES — SCOPED** (Track 3; truthful routing + offload bypass first, optional USB bit-perfect later) |
| **8. Lyrics Enhancements (Word-by-Word / Enhanced LRC, Offset Tap-Sync, Export)** | Builds on an already-tested `LyricsParser`/`LyricsSyncEngine`. Adding live `±100ms` timing offset nudge persisted per track, `.lrc` sidecar export, and Enhanced LRC (`<mm:ss.xx>`) parsing has high UX ROI with low architectural risk. | `STAB-02` (safe tag embed) | **YES** (Track 3) |
| **9. User-Controlled Backup / Export & Restore (JSON/ZIP)** | Replaces unsafe `allowBackup="true"` with an explicit Settings action exporting Playlists, Smart Playlist rules, Artist overrides, Listening History, and Settings (excluding logs and raw audio) to a user-chosen SAF `.zip`/`.json`. | `SEC-03` | **YES** (Track 2) |
| **10. Broadening `minSdk` Evaluation (35 → 29/31?)** | Currently `minSdk = 35` restricts the app to **Android 15+ only** (~top 10–15% of active Android devices globally, though 100% fine for personal use on vivo X200s Android 17). If HiFiDeck ever targets wider distribution, lowering `minSdk` to 31 (Android 12) or 33 (Android 13) requires auditing API 35-only calls and scoped-storage fallbacks. | Product distribution decision | **CONDITIONAL** (Keep `minSdk = 35` if personal/flagship-only; lower only before public release) |

---

## 2. Explicit "Do NOT Develop Yet / Postpone" List

Building these before stabilization would multiply defects and maintenance burden without solving core user pain:

1. **DO NOT Build Yet — Cloud Account System / Cross-Device Sync Backend:** Zero user need for an offline-first audiophile player; introduces authentication, server costs, GDPR/privacy compliance, and sync conflict complexity.
2. **DO NOT Build Yet — Third-Party Streaming Service Integrations (Spotify / Apple Music / Tidal SDKs):** Violates offline-first identity, introduces DRM/API breakage, and dilutes core focus.
3. **DO NOT Build Yet — Video Downloading / Video Player Mode:** HiFiDeck's differentiation is clean Hi-Fi audio downloading & playback; video adds muxing complexity, massive storage churn, and UI bloat.
4. **DO NOT Build Yet — Custom C++/NDK Audio Decoder or Native DSP Engine:** Media3 + Kotlin `AudioProcessor` (PCM_FLOAT) already covers ReplayGain, EQ, and future crossfade with zero JNI crash surface or NDK build overhead.
5. **DO NOT Build Yet — Google Cast / DLNA / Multi-Room Network Streaming:** High testing surface and Media3 Cast integration complexity; premature while local playback (`AUD-01`) and Sleep Timer (`AUD-02`) still need stabilization.
6. **DO NOT Build Yet — AI / On-Device LLM Playlist Generation or Auto-Tagging:** Heavy binary/memory footprint on top of an already 264 MB APK (`188 MB` idle PSS); Smart Playlists + MusicBrainz/LRCLIB deterministic matching serve users far more reliably.
7. **DO NOT Build Yet — Home Screen Widgets / Wear OS Companion:** Wait until `PlaybackController`, `MDownloadRoot`, and Room persistence are cleanly decoupled so widgets don't trigger full-app JSON startup loads.

---

## 3. Phase 11 — Disclosed Weighted Scoring Model

Every roadmap candidate is scored `1–5` across 5 transparent criteria:

$$\text{Weighted Score} = 0.30 \times \text{User Impact} + 0.25 \times \text{Risk Reduction} + 0.15 \times \text{Differentiation} + 0.15 \times \text{Arch. Leverage} + 0.15 \times (6 - \text{Effort})$$

- **User Impact (30%):** Tangible daily benefit for HiFiDeck users on vivo X200s.
- **Risk Reduction (25%):** Prevents data loss, audio corruption, crashes, or security flaws.
- **Differentiation (15%):** Strengthens HiFiDeck's unique audiophile / orbital player value proposition.
- **Arch. Leverage (15%):** Unblocks future features or reduces ongoing maintenance cost.
- **Inverse Effort (`6 - Effort`, 15%):** `1 = XS`, `2 = S`, `3 = M`, `4 = L`, `5 = XL` (favors high-ROI quick wins).

| Rank | Initiative | Track | Impact (30%) | Risk Red. (25%) | Diff. (15%) | Arch. (15%) | Effort (1-5) | **Score (/5.00)** | Priority |
|---:|---|---|---:|---:|---:|---:|---:|---:|---|
| 1 | **`STAB-01`**: ReplayGain DSP Sync, Thread Safety & Sleep Timer Fixes | 1. Stabilization | 5 | 5 | 4 | 4 | 2 (S) | **4.55** | **P0** |
| 2 | **`STAB-02`**: Metadata & Lyrics Tag Safety, ID3v2/MP4/Ogg Fixes & Engine Unification | 1. Stabilization | 5 | 5 | 3 | 5 | 4 (L) | **4.25** | **P0** |
| 3 | **`STAB-03`**: Circular Seek Dead Zones, Light-Mode Sheets & Accessibility Fixes | 1. Stabilization | 5 | 3 | 5 | 2 | 2 (S) | **3.90** | **P1** |
| 4 | **`STAB-04`**: Security Hardening (`HF_FILE`, Regex Escape, `ffprobe` Deadlock, Backup/SSRF) | 1. Stabilization | 3 | 5 | 1 | 3 | 2 (S) | **3.35** | **P1** |
| 5 | **`STAB-05`**: ABI Splits (-139 MB APK), Async Startup I/O, Recomposition & Smart Playlist O(1) | 1. Stabilization | 5 | 3 | 2 | 4 | 3 (M) | **3.60** | **P1** |
| 6 | **`CORE-01`**: Metadata Post-Edit Sync (MediaSession + Batch Cancel) & Favorites ID Fix | 2. Core Usability | 4 | 3 | 2 | 3 | 2 (S) | **3.30** | **P1** |
| 7 | **`CORE-02`**: Zero-Copy Tag Reading (`FileChannel`), Tag-Only RG Cache & Throttled Saves | 2. Core Usability | 4 | 2 | 2 | 4 | 3 (M) | **3.05** | **P2** |
| 8 | **`CORE-03`**: User-Controlled Backup/Export (SAF JSON/ZIP) & Lyrics Timing Offset | 2. Core Usability | 4 | 3 | 3 | 2 | 2 (S) | **3.30** | **P2** |
| 9 | **`ENH-01`**: Room / SQLite Migration + Cross-Rescan Stable Track Identity | 3. Audio & Library | 4 | 3 | 2 | 5 | 5 (XL) | **3.15** | **P2** |
| 10 | **`ENH-02`**: Background Batch EBU R128 Scanner (`WorkManager`) | 3. Audio & Library | 4 | 1 | 5 | 3 | 3 (M) | **3.10** | **P2** |
| 11 | **`ENH-03`**: True Gapless Playback Verification, Unity DSP Bypass & Accurate Routing | 3. Audio & Library | 4 | 2 | 4 | 3 | 2 (S) | **3.35** | **P2** |
| 12 | **`ENH-04`**: Refactor `MainViewModel` & `LibraryHubScreen` + Split `SettingsDeviceTest` | 3. Audio & Library | 2 | 2 | 1 | 5 | 4 (L) | **2.30** | **P2** |
| 13 | **`ADV-01`**: 10-Band / Parametric Float-Biquad EQ (Under Single Gain Authority) | 4. Advanced | 4 | 1 | 5 | 2 | 4 (L) | **2.80** | **P3** |
| 14 | **`ADV-02`**: Configurable Smooth Crossfade (1–12s) | 4. Advanced | 3 | 1 | 4 | 2 | 4 (L) | **2.35** | **P3** |

---

## 4. Prioritized Engineering Backlog (4 Tracks)

### Track 1 — Stabilization (P0 & P1 Blockers)

#### `STAB-01` [P0] — ReplayGain DSP Authority & Sleep Timer Hardening
- **Justification:** Eliminates confirmed double-attenuation audio bug (`AUD-01`), ARM64 thread race on `currentGain`, Sleep Timer expiry volume burst (`AUD-02a`), and `END_OF_QUEUE` failure under Repeat modes (`AUD-02b`).
- **Dependencies:** None.
- **Effort:** `S` (1.5–2 days).
- **Acceptance Criteria:**
  - Transitioning `isProcessingPcm` (`false → true` and `true → false`) never applies gain to both `ReplayGainAudioProcessor` and `player.volume` simultaneously.
  - `currentGain` is mutated exclusively on the ExoPlayer audio thread.
  - Sleep Timer expiry keeps `sleepFadeFactor = 0.0f` until playback is confirmed paused + drained before restoring `1.0f`.
  - `END_OF_QUEUE` fades and pauses on the last queue track regardless of `REPEAT_MODE_ALL` / `REPEAT_MODE_ONE`.
- **Required Tests:** JVM unit test for `PlaybackGainCoordinator` PCM transition transitions + SleepTimer `END_OF_QUEUE` under all 3 repeat modes; on-device instrumented test verifying `player.volume == 1.0f` whenever `isProcessingPcm == true`.

#### `STAB-02` [P0] — Metadata & Lyrics Tag Integrity Hardening + Engine Unification
- **Justification:** Eliminates permanent file truncation risk (`META-01`), ID3v2.4 unsync corruption (`META-02a`), ID3v2.2 silent tag wipe (`META-02b`), MP4 ReplayGain loss & dead read-back stub (`META-02c`), and Ogg silent artwork loss (`META-02d`).
- **Dependencies:** None.
- **Effort:** `L` (4–6 days).
- **Acceptance Criteria:**
  - `content://` writes create a durable rollback copy before opening `"wt"` and verify external allocatable space; crash/failure restores original bytes cleanly.
  - Single shared ID3v2/FLAC engine used by both `MetadataTagIO` and `LyricsTagIO`; `LyricsTagIO` supports `MediaStore.createWriteRequest()`.
  - ID3v2.4 frames de-unsynchronized on read clear flag bit `0x02` on write; ID3v2.2 files never silently lose tags.
  - MP4 rewrites pass `-movflags use_metadata_tags`, preserve `MDOWNLOAD_YT_ID`, allow clearing comments, enforce FFmpeg timeout, and run real read-back verification.
  - Ogg/Opus artwork edits are either implemented natively or explicitly disabled in UI (never silent `Success`); FLAC `PICTURE` blocks reject/downscale >16 MiB.
- **Required Tests:** Golden-fixture JVM round-trip tests for ID3v2.4 unsync, ID3v2.2 rejection/upgrade, FLAC >16 MiB guard, MP4 ReplayGain + comment clear + read-back, Ogg artwork error/round-trip; disposable on-device MediaStore write + simulated failure rollback test.

#### `STAB-03` [P1] — Circular Seek Hit-Testing, Light-Mode Bottom Sheets & Accessibility
- **Justification:** Eliminates 4 diagonal dead zones on signature circular seek control (`UI-01`), fixes unreadable dark-on-dark sheets in Light Theme (`UI-02`), and fixes TalkBack / touch-target accessibility blockers (`UI-03`, `UI-06`, `UI-07`).
- **Dependencies:** None.
- **Effort:** `S` (1.5–2 days).
- **Acceptance Criteria:**
  - Circular seek responds smoothly across all 360° including 45°/135°/225°/315° in `0.74R..1.22R`.
  - All 4 modal sheets meet WCAG AA (≥4.5:1) contrast in both Dark and Light (`PearlOrchidColors`) themes.
  - `CircularSeekRing` exposes `setProgress` semantics; Home URL field has non-empty label/hint (`NAF` absent); interactive controls meet `48.dp` min touch target.
- **Required Tests:** Compose UI tests verifying diagonal ring touch coordinates at 45°/135°/225°/315°, Light Theme sheet surface/ink token contrast assertions, and semantics tree checks (`SetProgress`, Home URL label).

#### `STAB-04` [P1] — Subprocess Reliability (`ffprobe`/`ffmpeg`) & Security Hardening
- **Justification:** Prevents `ffprobe`/`LoudnessScanner` hangs (`AUD-08`/`AUD-09`), enforces `HF_FILE:` directory containment (`SEC-01`), fixes `--replace-in-metadata` regex injection (`SEC-02`), secures Auto-Backup (`SEC-03`), and hardens Share Intent SSRF checks (`SEC-04`).
- **Dependencies:** None.
- **Effort:** `S` (1.5–2 days).
- **Acceptance Criteria:**
  - `AudioInspectorEngine` & `LoudnessScanner` drain stderr asynchronously and kill hung subprocesses on timeout.
  - `resolveProducedFile()` rejects any path outside `jobWorkDir.canonicalFile`.
  - Titles containing `\1`, `\g<0>`, trailing `\` download and tag cleanly.
  - `backup_rules.xml` & `data_extraction_rules.xml` exclude `hifideck_work/`, `settings.log`, and caches; `YouTubeUrlParser` blocks IPv6 ULA/link-local/IPv4-mapped/CGNAT/numeric IPv4.
- **Required Tests:** JVM unit tests for canonical path rejection, backslash title escaping, SSRF edge cases (`[::ffff:127.0.0.1]`, `http://2130706433/`, `fd00::1`, `100.64.0.1`), and subprocess timeout cancellation.

#### `STAB-05` [P1] — APK Size Reduction, Async Startup I/O, Recomposition & Smart Playlist Scaling
- **Justification:** Cuts APK by >50% (`264.53 MB → ~125 MB`), eliminates Main-Thread startup JSON reads (`740 ms` cold start), stops 4 Hz root recompositions, and fixes `O(N log N × M)` Smart Playlist scaling bottleneck (`PERF-01/02/03`).
- **Dependencies:** None.
- **Effort:** `M` (3 days).
- **Acceptance Criteria:**
  - Per-ABI `arm64-v8a` APK ≤ 135 MB (or AAB splits configured).
  - Zero synchronous disk reads in `MDownloadApp.onCreate()` (verified under StrictMode `detectDiskReads()`).
  - `SmartPlaylistEvaluator` pre-indexes `statsByUri` (`O(1)` lookup) and evaluates on `Dispatchers.Default`; `ListeningHistoryRepository` flushes on track switch/pause/≥30s debounce.
  - `MDownloadRoot` does not recompose on 250 ms playback position ticks; `NowPlayingScreen`/`OrbitalNavigator` defer `Animatable` reads to draw/layer phase.
- **Required Tests:** 5,000-track JVM benchmark test for `SmartPlaylistEvaluator` (<50 ms); StrictMode startup check on vivo X200s; re-measure cold start & `dumpsys gfxinfo` on vivo X200s.

---

### Track 2 — Core Usability (P1 & P2)

#### `CORE-01` [P1] — Post-Edit MediaSession Sync, Batch Cancel Refresh & Immutable Favorites ID
- **Justification:** Fixes stale lock-screen/Bluetooth metadata after editing (`META-04`), partial batch refresh (`META-03`), `resetPreferences()` sync (`AUD-07`), and fragile `"Favorites"` string matching (`UI-09`).
- **Dependencies:** `STAB-02`.
- **Effort:** `S` (1.5 days).
- **Acceptance Criteria:** Editing active track immediately updates notification & MediaSession metadata; cancelling batch edit refreshes already-committed tracks; renaming Favorites playlist preserves heart button behavior.
- **Required Tests:** Instrumented MediaSession metadata update test + JVM unit tests for Favorites ID resilience and batch partial-commit refresh.

#### `CORE-02` [P2] — Zero-Copy Tag/Inspector I/O, Tag-Only RG Cache & Throttled Persistence
- **Justification:** Eliminates 2–100 MB `cacheDir` file copies on playback/inspection (`AUD-05`, `AUD-06`, `AUD-10`) and 5-second 500-track JSON writes (`PERF-04`).
- **Dependencies:** `STAB-01`.
- **Effort:** `M` (2–3 days).
- **Acceptance Criteria:** `ReplayGainMetadataReader` & `AudioInspectorEngine` read headers via `ParcelFileDescriptor`/`FileChannel` without full-file copies; `MediaItemMapper` propagates `dateModifiedSec`; tag-only RG results cached with LRU cap; queue snapshot saved only on queue mutation; download notifications throttled ≥500 ms.
- **Required Tests:** JVM tests for >32 MiB synthetic MP4 `moov`-at-end and >512 KiB Ogg artwork parsing; device test verifying zero temp copy growth during inspection.

#### `CORE-03` [P2] — User-Controlled SAF Backup/Restore & Per-Track Lyrics Timing Offset
- **Justification:** Safe replacement for disabled cloud backup (`SEC-03`) plus high-value lyrics sync polish (live `±100ms` offset nudge + `.lrc` export).
- **Dependencies:** `STAB-04`.
- **Effort:** `S` (2 days).
- **Acceptance Criteria:** Export/import single `.json` backup via SAF preserving playlists, smart rules, artist overrides, and listening history without leaking logs; per-track lyrics offset persists and shifts active lyric line live.
- **Required Tests:** JVM serialization round-trip tests for backup archive + lyrics offset persistence.

---

### Track 3 — Audio & Library Enhancement (P2)

#### `ENH-01` [P2] — Room / SQLite Persistence Migration & Cross-Rescan Stable Track Identity
- **Justification:** Replaces 7 JSON files with ACID transactional SQLite (`ARCH-02`), unlocking 10,000+ track libraries and preserving play history/playlists when MediaStore reassigns `_id` after file moves or OS updates.
- **Dependencies:** `STAB-05`, `CORE-01`.
- **Effort:** `XL` (7–10 days).
- **Acceptance Criteria:** One-time automatic migration from legacy JSON files with zero data loss; fallback resolution via `(normalizedTitle, normalizedArtist, durationMs, sizeBytes)` when URI changes; all queries run asynchronously via Room `Flow`.
- **Required Tests:** Room migration tests (legacy JSON → DB v1), DAO unit/instrumented tests, rescan URI-change reconciliation tests.

#### `ENH-02` [P2] — Background Batch EBU R128 Loudness Scanner (`WorkManager`)
- **Justification:** Makes ReplayGain work across untagged YouTube downloads and local libraries automatically on user request.
- **Dependencies:** `STAB-04` (`LoudnessScanner` timeout), `ENH-01` (or indexed `AudioAnalysisRepository`).
- **Effort:** `M` (3–4 days).
- **Acceptance Criteria:** User-triggered library scan runs via `WorkManager` with cancellable progress notification, respects battery/charging constraints, skips already-tagged/cached tracks, and optionally writes tags via unified `STAB-02` engine.
- **Required Tests:** WorkManager `TestListenableWorkerBuilder` tests + cancellation/resume tests on disposable fixtures.

#### `ENH-03` [P2] — Unity-Gain DSP Bypass, Gapless Verification & Accurate Audio Output Routing
- **Justification:** Aligns actual audio pipeline with `AUDIO_PIPELINE.md` design (`AUD-03`, `AUD-07`), lowers playback battery draw when ReplayGain is OFF, and ensures true gapless album transitions.
- **Dependencies:** `STAB-01`, `CORE-02`.
- **Effort:** `S` (2 days).
- **Acceptance Criteria:** `ReplayGainAudioProcessor` bypasses sample copying when unity & not ramping; next track's ReplayGain pre-resolved before transition; `AudioInspectorEngine` uses API 33+ `getAudioDevicesForAttributes()` + real Media3 offload status.
- **Required Tests:** JVM unit test for unity bypass + gapless fixture transition test on vivo X200s.

#### `ENH-04` [P2] — Modularize `MainViewModel` & `LibraryHubScreen` + Fix `SettingsDeviceTest`
- **Justification:** Reduces blast radius of 2,082-line `MainViewModel` and 2,213-line `LibraryHubScreen` (`ARCH-01`) and makes full device test suite green (`TEST-01`).
- **Dependencies:** `STAB-05`.
- **Effort:** `L` (5–7 days).
- **Acceptance Criteria:** Focused state holders for Downloader, Library, Playback, Metadata/Inspector; per-tab Library composables; `SettingsDeviceTest` runs deterministically to completion on vivo X200s without hanging.
- **Required Tests:** Full 71+ JVM + all non-live instrumented tests green in a single command on vivo X200s.

---

### Track 4 — Advanced Features (P3)

#### `ADV-01` [P3] — 10-Band / Parametric Floating-Point EQ (`AudioProcessor`)
- **Justification:** Core audiophile differentiator tailored for IEMs/headphones on vivo X200s, bypassing inconsistent OEM system EQ.
- **Dependencies:** `STAB-01`, `ENH-03`.
- **Effort:** `L` (5–7 days).
- **Acceptance Criteria:** PCM_FLOAT biquad filter bank chained with `ReplayGainAudioProcessor`; automatic headroom preamp adjustment to prevent digital clipping; presets (Flat, Bass Boost, Vocal, Custom) persisted cleanly; zero-overhead bypass when disabled.
- **Required Tests:** JVM DSP frequency-response & clipping-ceiling unit tests; on-device sine-sweep stability test.

#### `ADV-02` [P3] — Smooth Configurable Crossfade (1–12s)
- **Justification:** High user delight for playlists and continuous playback sessions.
- **Dependencies:** `STAB-01`, `ENH-03`.
- **Effort:** `L` (5–7 days).
- **Acceptance Criteria:** Optional 1–12s equal-power crossfade disabled automatically during album gapless playback (optional toggle), integrated cleanly with `PlaybackGainCoordinator` and `SleepTimerController`.
- **Required Tests:** JVM equal-power fade-curve unit tests; on-device transition test verifying clean resource release and zero audio glitches.

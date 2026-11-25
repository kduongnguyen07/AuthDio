# AuthDio — Development & Stabilization History

> **Branding Note:** AuthDio was formerly known as **HiFiDeck / MDownload**. For backward compatibility with existing installations, `applicationId` remains `com.example.mdownload` and persistent storage paths (`filesDir/hifideck_data/`, `Music/MDownload`) are intentionally preserved.

This document consolidates the major stabilization milestones, architectural decisions, recovery protocols, regression specifications, and operational troubleshooting notes from AuthDio's engineering history.

---

## 1. Milestone Timeline Overview

| Milestone | Scope | Key Outcome |
|---|---|---|
| **Core Downloader & Media3 Player** | `yt-dlp` + FFmpeg/QuickJS pipeline, `HiFiPlaybackService`, Orbital Navigation, Circular Scrubbing, Synchronized Lyrics, ReplayGain, Audio Quality Inspector, Metadata Editor, Smart Playlists, Sleep Timer | Feature-complete offline-first Android Hi-Fi audio downloader and player (`compileSdk = 37`, `minSdk = 35`, Media3 `1.9.2`, Compose BOM `2026.02.01`). |
| **P0 Stabilization (`STAB-01`, `STAB-02`)** | Metadata/lyrics crash-safe write pipeline, shared binary container engine, single-authority ReplayGain DSP handoff, Sleep Timer expiry & repeat/shuffle correctness | Eliminated `content://` truncation data-loss window, ID3v2.4/MP4/Ogg tag corruption, ReplayGain double attenuation, and Sleep Timer expiry volume bursts (`89` JVM + `14` instrumented tests passing). |
| **P1 Stabilization (`STAB-03`, `STAB-04`)** | Security hardening, bounded subprocess runner, 360° circular seek hit-testing, WCAG AA Light/Dark modal sheets, TalkBack accessibility & `48dp` touch targets | Hardened `yt-dlp` path/regex/URL/SSRF boundaries, eliminated FFprobe/FFmpeg pipe deadlocks, eliminated 4 diagonal circular seek dead zones, and achieved full Light/Dark sheet legibility (`112` JVM + `21` instrumented tests passing). |
| **STAB-05 Performance & Packaging** | ABI flavor split, async off-Main-Thread startup hydration, Compose recomposition isolation, `O(1)` Smart Playlist indexing, split playback cursor persistence | Default `arm64-v8a` APK reduced **-52.4%** (`264.77 MB → 126.04 MB`), cold startup `-54 ms` (`0` app JSON/log Main-Thread I/O), Smart Playlists up to **871× faster** (`N=10,000`: `15.25 s → 17.5 ms`), steady-state playback writes **-99.84%** (`121.7 KB → 192 B`) (`117` JVM + `25` instrumented tests passing). |
| **Branding & Adaptive Icon Migration** | Custom Android DJ adaptive + monochrome launcher icon; complete user-facing & Gradle/IDE rebranding to **AuthDio** | Preserved `com.example.mdownload` identity, existing user libraries/settings, and all `117/117` JVM unit tests with zero regressions. |

---

## 2. P0 Stabilization (`STAB-01` & `STAB-02`) — Data Integrity & Audio DSP

### 2.1 Crash-Safe Metadata & Lyrics Write Pipeline (`SafeAudioWritePipeline`)
- **Problem Solved (`META-01`):** Opening `contentResolver.openOutputStream(uri, "wt")` truncates MediaStore/SAF audio files to 0 bytes immediately before streaming replacement bytes; any mid-write I/O error, storage exhaustion, or OS process kill risked permanent audio loss.
- **Architecture:** Shared `SafeAudioWritePipeline` (`core/metadata/SafeAudioWritePipeline.kt`) serves both `MetadataRepository` and `LyricsTagIO`:
  1. Pre-checks `canOpenReadWrite(targetUri)` and returns `PermissionRequired` for MediaStore URIs needing `MediaStore.createWriteRequest()`.
  2. Streams the original audio file with a bounded 32 KiB buffer into durable app-private storage (`filesDir/hifideck_data/pending_writes/<opId>/rollback.<ext>`), computes SHA-256 + byte length, and calls `fd.sync()`.
  3. Persists a pure-Kotlin JSON journal (`manifest.json`) through atomic temp + `fd.sync()` + rename across states: `PREPARING → READY_TO_COMMIT → COMMITTING → VERIFIED` (or `NEEDS_RECOVERY → RECOVERY_FAILED`).
  4. Generates & verifies the transformed replacement file (`staged_out.<ext>`) and checks destination storage headroom (`requiredBytes + 4 MiB`) before opening the destination stream.
  5. After writing (`file://` sibling temp + atomic rename; `content://` bounded 32 KiB stream + `fd.sync()`), re-reads the destination to verify exact byte length, SHA-256, and tag read-back before deleting `<opId>/`.
  6. On any failure or hash mismatch, immediately restores `rollback.<ext>` and verifies restoration against `originalSha256`. If rollback cannot complete (e.g., revoked one-shot URI permission), marks `RECOVERY_FAILED` and **retains `rollback.<ext>` and `manifest.json`** intact.
  7. On next cold startup, `MDownloadApp.onCreate()` invokes `metadataRepository.recoverInterruptedWrites()` asynchronously on `Dispatchers.IO` to inspect `pending_writes/` and finish restoration or cleanup.

### 2.2 Unified Container Primitives & Format Guarantees (`AudioContainerPrimitives`)
- **Deduplication (`META-02`):** Consolidated ~640+ lines of duplicated ID3v2/FLAC/MP4/Ogg logic from `MetadataTagIO` and `LyricsTagIO` into `core/metadata/AudioContainerPrimitives.kt`.
- **Container Integrity Specifications:**
  - **MP3 (ID3v2.3 / ID3v2.4):** Clears per-frame unsynchronization bit `0x02` (and inconsistent Data Length Indicator `0x01` prefix) after de-unsynchronizing preserved frames so repeated edits never corrupt `0xFF 0x00` sequences in `APIC`/`PRIV`. Preserves all unknown frames and verifies MPEG audio payload SHA-256 bit-for-bit.
  - **MP3 (ID3v2.2):** Explicitly returns `Unsupported` rather than silently wiping legacy 3-character frames; original file remains untouched.
  - **FLAC:** Validates `PICTURE` blocks in pure Kotlin (non-empty, `<= 4 MiB`, JPEG/PNG/WebP magic bytes, real pixel dimensions `1..4096`, 24-bit FLAC metadata block ceiling `0xFFFFFF`) and verifies audio frames bit-for-bit.
  - **MP4 / M4A:** Reads both iTunes `moov/udta/meta/ilst` (including `----` freeform ReplayGain atoms) and QuickTime `moov/meta/keys + ilst` (`mdta`) in `AudioContainerPrimitives` & `ReplayGainMetadataReader`. FFmpeg lossless remux passes `-c copy -map_metadata 0 -movflags +use_metadata_tags+faststart`, explicit `-metadata comment=` for comment clearing, and verifies `mdat` SHA-256 on text/lyrics edits.
  - **Ogg Vorbis / Ogg Opus:** Passes stream-level Vorbis/Opus comment flags (`-map_metadata:s:a:0 0:s:a:0` and `-metadata:s:a:0 key=value`) so container-level metadata is not overridden by copied stream tags; preserves container file extensions on temp outputs. Artwork changes on Ogg/Opus explicitly return `Unsupported` (disabled in editor UI) due to Linux `execve` `MAX_ARG_STRLEN` (128 KiB) limits for CLI Base64 `METADATA_BLOCK_PICTURE`.
  - **Live Media3 Sync & Batch Cancel:** `PlaybackController.refreshTrackMetadataFromLibrary()` updates live active/queued items in-place via `Player.replaceMediaItem()`. Batch edits invoke per-track post-commit refreshes inside a `NonCancellable` block and report `BatchMetadataResult.cancelled = true`.

### 2.3 ReplayGain Single-Authority DSP Handoff & Sleep Timer Reliability (`STAB-01`)
- **Single-Authority Gain State Machine (`PlaybackGainCoordinator`):** Enforces strict mutual exclusion between `BYPASS` (`1.0f / 1.0f`), `DSP_PCM_PROCESSOR` (`effective / player.volume=1.0f`), and `PLAYER_VOLUME_FALLBACK` (`processor=1.0f / player.volume=effective.coerceIn(0f, 1f)`). Holding `audioProcessor.targetGain = 1.0f` during fallback guarantees zero double attenuation during asynchronous `onConfigure()` handoff onto `player.applicationLooper`.
- **Lock-Free Audio Thread (`ReplayGainAudioProcessor`):** `currentGain` is exclusively owned by ExoPlayer's audio thread; external callers update `@Volatile targetGain` and `AtomicBoolean snapRequested`. When unity (`1.0f`), `queueInput()` executes a bit-exact `ByteBuffer.put()` fast path (honestly documented as a bit-exact fast path rather than zero-copy sink bypass so instant mid-track `+dB` preamp and Sleep Timer fades work without sink restarts).
- **Zero-Spike Sleep Timer Expiry (`SleepTimerController`):** Holds `sleepFadeFactor = 0.0f` across `player.pause()` until `onIsPlayingChanged(false)` plus a `150 ms` `AudioTrack` drain settle window (restoring `1.0f` immediately if the user manually resumes).
- **Logical Queue-End Detection:** `SleepTimerMath` uses `Timeline.getFirstWindowIndex(shuffle)` / `getLastWindowIndex(shuffle)` and handles `onPositionDiscontinuity(DISCONTINUITY_REASON_AUTO_TRANSITION)` so `END_OF_QUEUE` and `END_OF_TRACK` work reliably under `REPEAT_MODE_OFF`, `REPEAT_MODE_ALL`, `REPEAT_MODE_ONE`, Shuffle ON/OFF, single-track queues, and empty queues without mutating user repeat/shuffle preferences.
- **Preference Sync:** `MainViewModel.resetPreferences()` immediately pushes reset `ReplayGainConfig` and default fade duration to `PlaybackGainCoordinator` and `SleepTimerController`.

---

## 3. P1 Stabilization (`STAB-03` & `STAB-04`) — Security, Subprocesses & UI/Accessibility

### 3.1 Security & Subprocess Hardening (`STAB-04`)
- **Output Path Containment (`SEC-01`, `engine/OutputPathGuard.kt`):** Enforces canonical `Path.startsWith(Path)` containment inside `jobWorkDir`, rejecting `../` traversal, sibling-prefix tricks (`/work_evil`), escaping symlinks, directories, control characters, and zero-byte outputs from `yt-dlp --print after_move:filepath`.
- **Safe Metadata Regex Escaping (`SEC-02`, `YtDlpEngine.kt`):** Anchors `--replace-in-metadata title` with `(?s)\A.*\Z` (preventing Python `re.sub(".*", ...)` double-replacement) and escapes `\` → `\\` via `escapePythonRegexReplacement()` (preventing `\1`/`\g<0>` backreference injection and trailing-backslash crashes).
- **Bounded Subprocess Execution (`AUD-08`, `AUD-09`, `core/audio/BoundedSubprocessRunner.kt`):** Replaces blocking single-stream waits in `AudioInspectorEngine` (`12 s` cap) and `LoudnessScanner` (duration-aware `30 s..180 s` cap, `45 s` default) with concurrent daemon `stdout`/`stderr` drain threads, bounded byte caps, responsive coroutine cancellation, and escalating `destroy()` → `destroyForcibly()`. `LoudnessScanner.isRelevantEbuR128Line` filters out per-100ms progress lines (`t: ... M: ...`) so long tracks never truncate the final EBU R128 `Summary:` block; failed/timed-out scans are never cached.
- **Strict URL & Pre-Flight SSRF Validation (`SEC-04`, `YouTubeUrlParser.kt`):**
  - Strictly offline `normalizeUrl()` rejects non-`http`/`https` schemes, embedded credentials/`@`, backslashes, malformed percent-encoding, invalid ports, single-label/local hosts (`.local`, `.internal`, `.home.arpa`), decimal/hex/octal/shorthand non-public IPv4, private/loopback/link-local/CGNAT/multicast/reserved IPv4 & IPv6, and IPv4-mapped/6to4/NAT64 non-public addresses (with IDN Punycode normalization).
  - Off-main-thread `validateResolvedHost()` runs before `YtDlpEngine` inspect/download and fails closed if any resolved A/AAAA record is non-public.
  - *Honest Boundary Disclosure:* Pre-flight validation cannot alone prevent TOCTOU DNS rebinding or HTTP 3xx redirects executed internally by `yt-dlp`'s Python HTTP client.
- **Backup & Log Sanitization (`SEC-03`, `SEC-05`):**
  - `data_extraction_rules.xml` & `backup_rules.xml` include only `hifideck_data` while excluding `pending_writes`, `settings.log`, `settings.json`, `settings.tmp`, `listening_history.json`, `audio_analysis_cache.json`, `playback_state.json`, and `sleep_timer.json` (never affecting local crash recovery in `filesDir`).
  - `LogSanitizer` redacts URL credentials, query parameters, Bearer/Cookie headers, and secret key-values and strips CR/LF log injection before persisting diagnostic logs.

### 3.2 Circular Scrubbing, Theme Contrast & Accessibility (`STAB-03`)
- **360° Polar Hit-Zone Separation (`UI-01`):** Removed `orbitalArtworkLongPressDrag` from the inner square `Box` (whose unclipped corners previously reached `√2 × 0.72R ≈ 1.018R` at 45°/135°/225°/315°) onto the unified outer `1.22 * bezelDiameter` touch container gated strictly by Euclidean polar distance (`r <= 0.72R` for Orbital long-press; `0.74R <= r <= 1.22R` for Circular Seek Ring).
- **Light/Dark Theme Modal Sheets (`UI-02`):** Added opaque `surfaceElevated` token (`#1B1726` Dark / `#FAF8FD` Light) and replaced hardcoded dark navy backgrounds in `AudioInspectorSheet`, `SleepTimerSheet`, `TrackMetadataEditorSheet`, and `BatchMetadataEditorSheet` with WCAG AA (`>= 4.5:1`) theme-aware surfaces and accents.
- **TalkBack & Touch Targets (`UI-03`, `UI-06`, `UI-07`):** Added `SeekBar` semantics (`ProgressBarRangeInfo`, `setProgress`, `±10s` custom actions) to `CircularSeekRing`, explicit accessibility descriptions to `HiFiGlassTextField` / Home URL input (eliminating `NAF`), and `>= 48.dp` minimum interactive touch targets across controls.

---

## 4. STAB-05 — Performance, Startup & Packaging Optimization

| Area | Before | After | Key Engineering Change |
|---|---:|---:|---|
| **Default `arm64-v8a` Debug APK** | `264.77 MB` | **`126.04 MB`** (`-52.4%`) | Added `abi` flavor dimension in `app/build.gradle.kts` (`arm64` default, `universal` preserved for multi-ABI APK & Play AAB). |
| **Cold Launch `TotalTime` (10-run median)** | `754.0 ms` | **`700.0 ms`** (`-7.2%`) | Added `RepositoryLoadState` async off-Main-Thread hydration & lazy `mkdirs()`; eliminated all 8 synchronous Main-Thread JSON/log reads and 11 eager `mkdirs()`. |
| **Playback Tick Recompositions** | `20` root cascades / 20 ticks | **`0` root / `0` sibling** | Split `PlaybackController` `structuralState` from `positionMs` (`StateFlow<Long>`); deferred `OrbitalNavigator` reads to `graphicsLayer` & `drawWithCache`. |
| **Now Playing Idle Frames (6 s)** | `162` frames (`40` slow UI) | **`96` frames** (`22` slow UI) | `-40.7%` frame count, `-45.0%` slow UI frames, p90 `29 ms → 22 ms`. |
| **Smart Playlists (`N = 5,000` / `10,000`, all 7 built-in)** | `2,608.9 ms` / `15,251.4 ms` | **`17.8 ms` / `17.5 ms`** | Pre-indexed `O(1)` `statsByUri` lookup, Schwartzian sort-key caching, off-Main-Thread evaluation on `Dispatchers.Default` (**147×–871× faster**). |
| **Playback Persistence (every 5 s playing)** | `121,691 B` full queue rewrite | **`192 B`** cursor file (`-99.84%`) | Split `playback_state.json` (saved only on queue/mode changes) from `playback_state_cursor.json` (lightweight position cursor); `30 s` debounced listening history + instant `flushNow()` on pause/transition; `500 ms` download notification throttle; `1,000`-entry analysis LRU cap. |

### Known Remaining Optimization Opportunities
1. **Debug DEX Size (`~74 MB`):** `material-icons-extended` accounts for the majority of unminified debug DEX; replacing with `material-icons-core` + selective vectors and enabling verified R8 release rules is the largest next size win.
2. **Lazy `ContextImpl.getFilesDir()`:** Deferring store `File(context.filesDir, ...)` construction to `by lazy` on `Dispatchers.IO` removes the remaining ~12 ms Android framework `StrictMode` read check during `Application.onCreate()`.
3. **Baseline Profiles & Room Scaling:** Generating an AndroidX Baseline Profile benefits release cold start; migrating `ListeningHistoryRepository` and `AudioAnalysisRepository` to Room/SQLite is worth considering only if typical libraries exceed 5,000–10,000+ entries.

---

## 5. Branding & Launcher Icon Architecture

- **Adaptive Launcher Icon:** Built from the custom Android DJ artwork across 23 resources (`res/mipmap-anydpi/ic_launcher*.xml`, solid `#FFFFFF` `res/drawable/ic_launcher_background.xml`, transparent RGBA `res/drawable-{mdpi..xxxhdpi}/ic_launcher_foreground.png` fitted inside the `66 dp` circular safe zone, pure-white alpha silhouette `ic_launcher_monochrome.png` for Material You themed icons, and legacy `mipmap-*/ic_launcher*.png`). Transparent foreground cutouts also keep `DownloadForegroundService`'s `R.drawable.ic_launcher_foreground` notification small icon crisp.
- **Rebranding (`HiFiDeck / MDownload` → `AuthDio`):** Updated `@string/app_name`, `@string/share_sheet_label`, Gradle `rootProject.name = "AuthDio"`, `.idea/.name`, all in-app EN/VI UI strings, diagnostics headers, smart playlist labels, LRCLIB `USER_AGENT` (`AuthDio/1.2`), and fallback Vorbis comment vendor strings while strictly keeping `applicationId = "com.example.mdownload"`, internal class/service/session identifiers, and on-disk persistence directories unchanged.

---

## 6. Testing & Operational Troubleshooting Reference

### Standard Test Commands
```powershell
# All JVM unit tests (117 tests covering P0, P1, STAB-05, Core Domain, Lyrics, Orbital/Circular Scrubbing, Smart Playlists)
.\gradlew.bat :app:testArm64DebugUnitTest

# Build default arm64 debug APK & instrumented test APK
.\gradlew.bat :app:assembleArm64Debug :app:assembleArm64DebugAndroidTest

# Build universal multi-ABI debug APK or release App Bundle
.\gradlew.bat :app:assembleUniversalDebug
.\gradlew.bat :app:bundleUniversalRelease
```

### Opt-In Live Network Download Instrumented Test
`LiveDownloadTest` downloads a real audio track, verifies public MediaStore export, and verifies Media3 playback (`> 1 s`). It is **disabled by default** in ordinary suites and requires an empty download queue:
```bash
adb shell am instrument -w \
  -e class com.example.mdownload.LiveDownloadTest \
  -e liveDownload true \
  com.example.mdownload.test/androidx.test.runner.AndroidJUnitRunner
```

### Troubleshooting Notes
- **Android Settings App Label Cache After In-Place Update:** After `adb install -r` updates `app_name`, Android Settings (`com.android.settings`) can temporarily display the cached previous label in `ApplicationsState` until the Settings app restarts, even though the launcher and `aapt2 dump badging` already show `AuthDio`.
- **Interrupted Metadata/Lyrics Write Recovery:** If the OS kills the process mid-commit during a `content://` tag edit, the intact pre-edit audio copy remains in `filesDir/hifideck_data/pending_writes/<opId>/rollback.<ext>` and is automatically restored on the next cold start (or retained as `RECOVERY_FAILED` if one-shot MediaStore write permission expired).
- **OriginOS / OEM Background Restrictions:** If background playback or downloads are throttled on OEM Android skins, use **Settings → Network & background** to grant **Unrestricted battery** optimization.

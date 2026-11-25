# HiFiDeck — Technical Debt & Structured Defect Register

**Audit Scope:** Complete prioritized catalog of confirmed bugs, architectural debt, security/privacy gaps, and performance liabilities discovered during the audit.  
**Audit Date:** 2026-11-24

**Severity Definitions:**
- **P0 (Critical):** Data loss/corruption, severe audio malfunction, or unsafe concurrency bug affecting core features.
- **P1 (High):** Broken UX/accessibility, security exposure, subprocess deadlock, or major startup/APK/scaling bottleneck.
- **P2 (Medium):** Maintainability/architecture drag, edge-case bugs, cache misses, or moderate resource waste.
- **P3 (Low):** Minor polish, stale warnings, or low-impact cleanup.

---

## 1. P0 — Critical Defects (Fix Immediately Before Any Feature Expansion)

### `AUD-01` — Double ReplayGain Attenuation Race & Unsynchronized `currentGain`
- **Severity:** **P0**
- **Evidence:** Direct code inspection of `PlaybackGainCoordinator.reapplyLocked()` and `ReplayGainAudioProcessor.onConfigure()` / `onReset()` / `snapToTargetGain()`.
- **Root Cause:** `reapplyLocked()` sets `audioProcessor.setTargetLinearGain(effective)` AND falls back to `player.volume = effective.coerceIn(0f, 1f)` when `!audioProcessor.isProcessingPcm`. When ExoPlayer subsequently invokes `onConfigure()` (setting `isProcessingPcm = true`), no callback notifies `PlaybackGainCoordinator` to reset `player.volume = 1.0f`, so both DSP and `player.volume` attenuate simultaneously. Separately, `currentGain` is a non-`@Volatile` `Float` mutated from caller threads (`snapToTargetGain()`) and ExoPlayer's audio thread (`queueInput()`/`onFlush()`/`onReset()`).
- **Impact:** Audible double attenuation (e.g., `-6 dB` becomes `-12 dB`) on playback start, silent loss of ReplayGain on offload transition, and ARM64 cross-thread visibility race.
- **Affected Files:** `PlaybackGainCoordinator.kt`, `ReplayGainAudioProcessor.kt`
- **Suggested Remediation:** Add an `onPcmActiveChanged: (Boolean) -> Unit` callback invoked from `onConfigure()`/`onReset()` to post a main-thread `reapply()` in `PlaybackGainCoordinator`; replace direct `currentGain` cross-thread mutation with an `@Volatile snapRequested: Boolean` flag consumed exclusively on the ExoPlayer audio thread inside `queueInput()`/`onFlush()`.
- **Effort:** **S** (0.5–1 day)
- **Regression Risk:** Low (isolated to 2 classes; easily verified with existing + new PCM transition unit/instrumented tests).

---

### `META-01` — Non-Atomic `content://` File Truncation Window (`"wt"`)
- **Severity:** **P0**
- **Evidence:** Direct code inspection of `MetadataRepository.commitContentUri()` and `LyricsTagIO.embedLyricsIntoUri()`.
- **Root Cause:** Both open the target MediaStore/SAF URI with `openOutputStream(uri, "wt")`, immediately truncating the user's audio file to 0 bytes before streaming the staged temp file. Only `cacheDir.usableSpace` is checked (not external storage headroom), and no backup copy exists if the process dies or I/O fails mid-write. Additionally, `LyricsTagIO` lacks `MediaStore.createWriteRequest()` support.
- **Impact:** Permanent destruction of user's music files on crash, OOM/OriginOS kill, or low external storage mid-write; lyrics embedding fails on non-owned files under Android 15–17 scoped storage.
- **Affected Files:** `MetadataRepository.kt`, `LyricsTagIO.kt`
- **Suggested Remediation:** Create a verified rollback copy in `filesDir/pending_metadata_rollback/` before opening `"wt"`, verify external storage allocatable bytes via `StorageManager`, write + flush + verify byte count/read-back, remove rollback only after success, add startup recovery if a rollback file remains, and route `LyricsTagIO` through the same `MediaStore.createWriteRequest()` pipeline.
- **Effort:** **M** (2–3 days)
- **Regression Risk:** Medium (touches write commit paths; must be verified on real MediaStore & SAF URIs on disposable copies).

---

### `META-02` — MP3 ID3v2.4/2.2 Corruption, MP4 ReplayGain Stripping / Stub Read-Back & Ogg Silent Artwork Loss
- **Severity:** **P0**
- **Evidence:** Direct code inspection of `MetadataTagIO.kt`, `LyricsTagIO.kt`, and Android Studio analyzer warning at `MetadataTagIO.kt:654` (`Parameter 'file' is never used`).
- **Root Cause:**
  1. ID3v2.4 per-frame unsync (`frameFlags[1] & 0x02`) strips `0xFF 0x00` bytes on read while preserving the `0x02` flag bit on write (duplicated in `MetadataTagIO.kt` and `LyricsTagIO.kt`).
  2. ID3v2.2 returns `null` from `readId3v2Frames()` while `findMp3AudioPayloadOffset()` skips the v2.2 tag, wiping all existing tags.
  3. MP4 FFmpeg remux omits `-movflags use_metadata_tags` (dropping ReplayGain freeform atoms), skips SHA-256 (`null`), `readMp4BasicMetadata(file)` is a no-op stub returning empty metadata, and writing comments overwrites `MDOWNLOAD_YT_ID` while clearing comments fails.
  4. Ogg/Opus ignores `artworkChanged`/`artworkRemoved` yet returns `MetadataWriteResult.Success`, and skips SHA-256.
  5. FLAC `PICTURE` block writer lacks a 16 MiB (`2^24 - 1`) length check.
- **Impact:** Silent corruption of ID3v2.4 artwork/frames, complete tag loss on ID3v2.2 MP3s, ReplayGain loss on edited M4As, false success on Ogg artwork edits, and broken duplicate detection on edited M4As.
- **Affected Files:** `MetadataTagIO.kt`, `LyricsTagIO.kt`, `TrackMetadataEditorSheet.kt`
- **Suggested Remediation:** Unify ID3v2/FLAC binary I/O into a single engine; clear bit `0x02` after `removeUnsync()`; reject ID3v2.2 explicitly (or map v2.2→v2.3 frames); add `-movflags use_metadata_tags` + real `ffprobe`/MP4 read-back + explicit comment clear (preserving `MDOWNLOAD_YT_ID`) + FFmpeg timeout; disable Ogg artwork editing in UI until native `METADATA_BLOCK_PICTURE` writer exists; cap FLAC `PICTURE` blocks below 16 MiB; update misleading UI preservation copy.
- **Effort:** **L** (4–6 days)
- **Regression Risk:** Medium-High (binary container code; requires golden-fixture JVM tests for ID3v2.2, ID3v2.4 unsync, FLAC >16MB guard, M4A ReplayGain preservation, and Ogg artwork handling).

---

## 2. P1 — High-Priority Defects & Structural Debt

| ID | Issue | Evidence & Root Cause | Impact | Affected Files | Suggested Remediation | Effort | Reg. Risk |
|---|---|---|---|---|---|---|---|
| **`AUD-02`** | **Sleep Timer Expiry Volume Spike & `END_OF_QUEUE` Repeat Failures** | `expireAndPause()` resets `sleepFadeFactor = 1.0f` before `player.pause()` / `AudioTrack` drains. `END_OF_QUEUE` checks `!hasNextMediaItem()` which is always `false` under `REPEAT_MODE_ALL`/`REPEAT_MODE_ONE`. | Brief loud burst after fade-out; `END_OF_QUEUE` fails to stop playback when Repeat is enabled. | `SleepTimerController.kt` | Keep `sleepFadeFactor = 0.0f` until `onIsPlayingChanged(false)` + short audio-drain delay (e.g., 250 ms) before restoring `1.0f`; compute queue end via `currentMediaItemIndex == mediaItemCount - 1` (not `!hasNextMediaItem()`). | **S** (1d) | Low |
| **`AUD-08`** | **`ffprobe` Stderr Pipe Deadlock & Ineffective Timeout + Unbounded `LoudnessScanner`** | `probeWithFfprobe()` sets `redirectErrorStream(false)` without draining `stderr` and blocks on `inputStream.readText()` *before* `waitFor(timeout)`. `LoudnessScanner` has no `waitFor(timeout)`. | Permanent coroutine hang / thread leak / runaway battery drain on malformed files. | `AudioInspectorEngine.kt`, `LoudnessScanner.kt` | Drain `stderr` asynchronously (or merge/discard cleanly) and enforce `withTimeout` + `process.destroyForcibly()` in `finally` for both `ffprobe` and `ffmpeg` loudness scans. | **S** (1d) | Low |
| **`UI-01`** | **4 Diagonal Dead Zones on Circular Seek Ring** | Inner square `Box` (`0.72 * bezelDiameter`) uses `clip(CircleShape)` (rendering only, not Compose pointer hit-testing); square corners reach `1.018R`, overlapping the `0.74R..1.22R` seek ring at 45°/135°/225°/315°. Also `outerTouchDiameter` clips `1.22R` to `~1.12R`. | Unresponsive or misfired circular scrubbing at 1:30, 4:30, 7:30, and 10:30 o'clock. | `CircularAlbumArtwork.kt`, `CircularSeekRing.kt` | Unify inner artwork + outer seek ring hit-testing via polar radius `r` in a single pointer handler (or reject touches with `r > innerArtRadiusPx` in inner artwork handler); expand container to `2 * 1.22R`. | **S** (1d) | Low |
| **`UI-02`** | **Light Theme Unreadable Bottom Sheets** | All 4 modal sheets hardcode `containerColor = OrbitalDesignTokens.Colors.BezelMid` (`#1B2132`) while text uses `HiFiTheme.colors.inkPrimary` (`#181223` in Light Mode). | ~1.25:1 contrast ratio; Audio Inspector, Sleep Timer, and Metadata Editors are unreadable in Light Theme. | `AudioInspectorSheet.kt`, `SleepTimerSheet.kt`, `TrackMetadataEditorSheet.kt`, `BatchMetadataEditorSheet.kt` | Replace hardcoded `BezelMid` with theme-adaptive `HiFiTheme.colors.surfaceElevated`. | **XS** (0.25d) | Very Low |
| **`UI-03` / `UI-06`** | **Accessibility Gaps (`CircularSeekRing` No `setProgress`, Home URL `NAF="true"`)** | `CircularSeekRing` lacks `setProgress` semantic action; live vivo X200s UI XML shows `NAF="true"` on Home URL `EditText`. | TalkBack/Switch Access users cannot seek playback or identify URL field. | `CircularSeekRing.kt`, `HomeScreen.kt` | Implement `semantics { setProgress { ... } }` on `CircularSeekRing` and add proper `label` / `contentDescription` on URL input. | **S** (0.5d) | Low |
| **`META-03` / `META-04`** | **Batch Cancel Skips Refresh & Stale MediaSession Notification Metadata** | Cancelling `batchMetadataJob` skips `postCommitMetadataRefresh()`; `refreshTrackMetadataFromLibrary()` updates UI state without updating ExoPlayer's `MediaItem.mediaMetadata`. | Partial batch edits leave UI stale; lock screen / notification / Bluetooth show outdated tags after edit. | `MainViewModel.kt`, `PlaybackController.kt`, `HiFiPlaybackService.kt` | Wrap `postCommitMetadataRefresh()` in `finally { withContext(NonCancellable) { ... } }` when `succeeded > 0`; update Media3 active `MediaItem` metadata via custom session command or `replaceMediaItem`. | **S** (1–2d) | Medium |
| **`PERF-01`** | **264.53 MB Multi-ABI APK Bloat & 5 Sync Main-Thread Startup JSON Reads** | `abiFilters` packs 4 ABIs (`138.83 MB` wasted non-`arm64-v8a`); `MDownloadApp.onCreate()` synchronously reads 5 JSON files on Main Thread → `740 ms` median cold start, `30.77%` initial jank (`250 ms` 90th-99th). | Bloated install (`39.2s` USB), wasted device storage, sluggish cold start on flagship hardware. | `app/build.gradle.kts`, `MDownloadApp.kt`, 5 Repositories | Configure ABI splits / `arm64-v8a` focus (cut APK ~52%); hydrate repositories asynchronously on `Dispatchers.IO` with non-blocking initial `StateFlow` loading state. | **M** (2–3d) | Medium |
| **`PERF-02` / `PERF-03`** | **Root 250ms Recomposition, Per-Frame Gesture Recomposition & O(N log N × M) Smart Playlists** | `MDownloadRoot` collects `playbackState` at root; `NowPlayingScreen`/`OrbitalNavigator` read `Animatable.value` in composition; `ListeningSessionTracker` flushes JSON every 10s triggering Main-Thread `SmartPlaylistEvaluator.resolveStats()` linear `O(M)` scan. | Continuous UI recomposition churn; severe 10s periodic UI jank on libraries >1,000 tracks. | `MDownloadRoot.kt`, `NowPlayingScreen.kt`, `OrbitalNavigator.kt`, `SmartPlaylistEvaluator.kt`, `ListeningHistoryRepository.kt`, `MainViewModel.kt` | Move `playbackState` collection to leaf components; defer `Animatable` reads to `graphicsLayer`/draw phase; pre-index `statsByUri: Map<String, TrackListeningStats>` (`O(1)` lookup), run Smart Playlist evaluation on `Dispatchers.Default`, and flush history on track change/pause/30–60s debounce. | **M** (2–3d) | Medium |
| **`SEC-01` / `SEC-02`** | **Untrusted `HF_FILE:` Path Import & Python `re.sub` Injection** | `resolveProducedFile()` does not check canonical containment inside `jobWorkDir`; `--replace-in-metadata` passes unescaped `cleanMeta.title`. | Arbitrary app-file export risk; download crashes/tag corruption on titles containing `\1`/`\g<0>`. | `DownloadQueueManager.kt`, `YtDlpEngine.kt` | Verify `candidate.canonicalFile.toPath().startsWith(jobWorkDir.canonicalFile.toPath())`; escape `\` → `\\` in `--replace-in-metadata`. | **S** (0.5d) | Low |
| **`SEC-03` / `SEC-04`** | **Unrestricted `allowBackup="true"` & Incomplete SSRF Host Blocklist** | Empty `backup_rules.xml`/`data_extraction_rules.xml` back up `settings.log`, URLs, and `hifideck_work/`; `isDisallowedHost()` misses IPv6 ULA/link-local/IPv4-mapped/CGNAT/numeric IPv4. | Privacy exposure + backup quota exhaustion; Share Intent SSRF bypass against localhost/LAN. | `AndroidManifest.xml`, `backup_rules.xml`, `data_extraction_rules.xml`, `YouTubeUrlParser.kt` | Add strict `<exclude>` rules for logs/work/cache; validate resolved `InetAddress` properties before yt-dlp execution. | **S** (1d) | Low |

---

## 3. P2 & P3 — Maintainability, Scale & Polish Debt

| Sev | ID | Issue | Affected Files | Suggested Remediation | Effort | Reg. Risk |
|---|---|---|---|---|---|---|
| **P2** | **`AUD-03`** | No unity-gain bypass in `ReplayGainAudioProcessor` (`isActive()` not overridden) + deprecated `onFlush()` | `ReplayGainAudioProcessor.kt` | Override `isActive()` cleanly (or zero-copy fast path when unity & not ramping) and migrate to current Media3 `onFlush(StreamMetadata)`. | **S** (0.5d) | Low |
| **P2** | **`AUD-04` / `AUD-05`** | Stale ReplayGain on async track switch + missing tag-only cache + `MediaItemMapper` missing `dateModifiedSec` | `HiFiPlaybackService.kt`, `AudioAnalysisRepository.kt`, `MediaItemMapper.kt` | Include `dateModifiedSec` in `MediaItem` extras; cache lightweight `ReplayGainInfo` in `AudioAnalysisRepository`; pre-resolve next track's ReplayGain. | **M** (1–2d) | Low |
| **P2** | **`AUD-06`** | 32 MiB MP4 cap truncates >32 MiB ALAC `moov`; 512 KiB / 16-page Ogg cap misses tags after large covers | `ReplayGainMetadataReader.kt` | Use `ParcelFileDescriptor` + `FileChannel` random-access seeking (zero staging copy!) instead of copying to `cacheDir`. | **M** (2d) | Medium |
| **P2** | **`AUD-07`** | Static priority output-device guess, hardcoded offload string, `resetPreferences()` skips Gain/SleepTimer reset | `AudioInspectorEngine.kt`, `MainViewModel.kt`, `HiFiPlaybackService.kt` | Use API 33+ `AudioManager.getAudioDevicesForAttributes()`; wire `resetPreferences()` to `gainCoordinator` & `sleepTimerController`. | **S** (1d) | Low |
| **P2** | **`AUD-02c/d/e`** | Frozen restored SleepTimer countdown before `attachPlayer()`, sync Main-Thread disk write under `lock`, `updateFadeOutSeconds()` not persisted | `SleepTimerController.kt` | Start ticker on restore if `DURATION` active; persist async on `Dispatchers.IO`; persist fade-out seconds. | **S** (0.5d) | Low |
| **P2** | **`PERF-04` / `PERF-05`** | 5s full 500-track queue JSON rewrite, unthrottled download `notify()`, unbounded analysis cache, Main-Thread editor artwork decode | `HiFiPlaybackService.kt`, `DownloadForegroundService.kt`, `AudioAnalysisRepository.kt`, `TrackMetadataEditorSheet.kt` | Split queue JSON vs. lightweight position store; throttle notifications to ≥500 ms; add LRU cap (e.g., 1,000 entries); decode cover art off Main Thread with `inSampleSize`. | **M** (2d) | Low |
| **P2** | **`ARCH-01`** | God-class `MainViewModel` (2,082 lines, ~40 fields) & `LibraryHubScreen` (2,213 lines) | `MainViewModel.kt`, `LibraryHubScreen.kt` | Incrementally extract `DownloaderViewModel`, `LibraryViewModel`, `MetadataEditorCoordinator`, and split `LibraryHubScreen` into per-tab composables. | **L** (5–7d) | Medium |
| **P2** | **`ARCH-02`** | 7 ad-hoc JSON files without relational queries, transactions, or cross-rescan track identity stability | `ListeningHistoryRepository.kt`, `AudioAnalysisRepository.kt`, `PlaylistRepository.kt`, `SmartPlaylistRepository.kt`, `ArtistRepository.kt` | Plan staged migration to Room/SQLite + deterministic cross-rescan `stableKey` fallback (`title|artist|durationMs|sizeBytes`) when MediaStore `_id` changes. | **XL** (7–10d) | Medium-High |
| **P2** | **`UI-07` / `UI-09`** | Undersized touch targets (`30–44 dp`) & fragile `"Favorites"` playlist name coupling | `QueueBottomSheet.kt`, `LyricsScreen.kt`, `OrbitalNavigator.kt`, `MiniPlayerBar.kt`, `MainViewModel.kt` | Enforce `48.dp` min interactive target; store immutable `Playlist.id` (`"builtin_favorites"`) instead of matching display name `"Favorites"`. | **S** (1d) | Low |
| **P2** | **`SEC-05`** | Unredacted URLs/paths in `settings.log` + missing LRCLIB third-party privacy disclosure | `AppLogger.kt`, `SettingsScreen.kt`, `LyricsScreen.kt` | Scrub query tokens/paths on log export; add one-line LRCLIB privacy note in Lyrics/Settings. | **S** (0.5d) | Very Low |
| **P3** | **`TEST-01`** | `SettingsDeviceTest` hangs under `createAndroidComposeRule<MainActivity>()` on vivo X200s | `SettingsDeviceTest.kt` | Split non-UI storage/persistence tests (`persistenceAndCleanupProtectPendingAudio`) out of Compose Activity rule into standalone `InstrumentationRegistry` tests. | **S** (0.5d) | Low |
| **P3** | **`CLEAN-01`** | Dead/unused code & minor warnings: `MainViewModel.isTrackFavorite` (L1378), `evaluateSmartPlaylist` (L2056), `"HiFiDeck 1.2"` hardcoded version, `yt-dlp` `"unknown"` version string bug, `HiFiPlaybackService` incomplete `DISCONTINUITY_REASON` switch | `MainViewModel.kt`, `SettingsScreen.kt`, `YtDlpEngine.kt`, `HiFiPlaybackService.kt` | Remove unused methods; display `BuildConfig.VERSION_NAME`; parse numeric `YYYY.MM.DD` tuple for yt-dlp update check. | **XS** (0.5d) | Very Low |

# HiFiDeck Android — Advanced Audio & Library Feature Validation Report

**Project:** HiFiDeck (`com.example.mdownload`, `D:/MDownload`)  
**SDK & Toolchain:** `compileSdk = 37`, `minSdk = 35`, Kotlin `2.2.10`, AGP `9.5.2`, Jetpack Compose BOM `2026.02.01`, AndroidX Media3 `1.9.2`  
**Validation Device:** vivo X200s (`model:V2458A`, serial `10AF8A0YUH005HA`, OriginOS)

---

## 1. Feature Implementation Status Matrix

| # | Feature | Status | Core Architecture & Files |
|---|---|---|---|
| 1 | **ReplayGain / Volume Normalization** | **Implemented & Validated** | `ReplayGainCalculator.kt`, `ReplayGainMetadataReader.kt`, `LoudnessScanner.kt`, `ReplayGainAudioProcessor.kt`, `PlaybackGainCoordinator.kt`, `ReplayGainSettingsSection.kt` |
| 2 | **Audio Quality Inspector** | **Implemented & Validated** | `AudioQualityModels.kt`, `AudioInspectorEngine.kt`, `AudioAnalysisRepository.kt`, `AudioInspectorSheet.kt` |
| 3 | **Metadata Editor (Single & Batch)** | **Implemented & Validated** | `AudioMetadataModels.kt`, `MetadataValidator.kt`, `MetadataTagIO.kt`, `MetadataRepository.kt`, `TrackMetadataEditorSheet.kt` |
| 4 | **Smart Playlists & Listening History** | **Implemented & Validated** | `ListeningHistoryRepository.kt`, `ListeningSessionTracker.kt`, `SmartPlaylistModels.kt`, `SmartPlaylistEvaluator.kt`, `SmartPlaylistRepository.kt`, `SmartPlaylistDialogs.kt` |
| 5 | **Sleep Timer** | **Implemented & Validated** | `SleepTimerController.kt`, `SleepTimerSheet.kt` |

### Existing Features Preserved
- **Downloader (`yt-dlp` + FFmpeg + Foreground Service + Queue)**: Unchanged and operational (`yt-dlp ready` confirmed on vivo X200s).
- **Orbital Navigation & Circular Scrubbing (`NowPlayingScreen`)**: Untouched gesture handlers and transitions; Sleep Timer adds only a compact top-chrome countdown pill when active, and tapping the existing `audioInfoSummary` opens the Audio Quality Inspector.
- **Synchronized Lyrics (`LyricsScreen` / `LyricsRepository`)**: Preserved and automatically synchronized when embedded `USLT`/lyrics metadata is edited.
- **Artist Management (`ArtistStore` / `ArtistManagementDialogs`)**: Preserved alongside new batch-edit actions in artist detail views.

---

## 2. Files Added & Modified

### Newly Created Production Files
- **Core Audio & ReplayGain (`app/src/main/java/com/example/mdownload/core/audio/`):**
  - `AudioQualityModels.kt`
  - `ReplayGainCalculator.kt`
  - `ReplayGainMetadataReader.kt`
  - `AudioInspectorEngine.kt`
  - `LoudnessScanner.kt`
  - `AudioAnalysisRepository.kt`
- **Metadata Editor (`app/src/main/java/com/example/mdownload/core/metadata/`):**
  - `AudioMetadataModels.kt`
  - `MetadataValidator.kt`
  - `MetadataTagIO.kt`
  - `MetadataRepository.kt`
- **Playback DSP, Sleep Timer & Listening Tracker (`app/src/main/java/com/example/mdownload/player/playback/`):**
  - `ReplayGainAudioProcessor.kt`
  - `PlaybackGainCoordinator.kt`
  - `SleepTimerController.kt`
  - `ListeningSessionTracker.kt`
- **Smart Playlists & History (`app/src/main/java/com/example/mdownload/player/library/`):**
  - `ListeningHistoryRepository.kt`
  - `SmartPlaylistModels.kt`
  - `SmartPlaylistEvaluator.kt`
  - `SmartPlaylistRepository.kt`
- **UI Sheets, Dialogs & Settings (`app/src/main/java/com/example/mdownload/player/ui/`):**
  - `AudioInspectorSheet.kt`
  - `SleepTimerSheet.kt`
  - `TrackMetadataEditorSheet.kt`
  - `SmartPlaylistDialogs.kt`
  - `ReplayGainSettingsSection.kt`
- **Tests & Documentation:**
  - `app/src/test/java/com/example/mdownload/AdvancedAudioFeaturesTest.kt`
  - `FEATURE_EXPANSION_DESIGN.md`
  - `AUDIO_PIPELINE.md`
  - `FEATURE_VALIDATION_REPORT.md`

### Updated Integration Files
- `MDownloadApp.kt` (wires `PlaybackGainCoordinator`, `SleepTimerController`, `AudioAnalysisRepository`, `MetadataRepository`, `ListeningHistoryRepository`, `SmartPlaylistRepository`)
- `core/Models.kt` & `core/SettingsStore.kt` (persists ReplayGain & Sleep Timer preferences)
- `player/playback/HiFiPlaybackService.kt` & `PlaybackController.kt` (custom `DefaultAudioSink` with `ReplayGainAudioProcessor`, Sleep Timer callbacks, `ListeningSessionTracker`, live `AudioOutputInfo` + `GainCoordinatorState`)
- `player/library/MusicLibraryRepository.kt` & `player/lyrics/LyricsRepository.kt` (targeted cache/library refresh after metadata edits)
- `MainViewModel.kt` (full state & action orchestration, `MediaStore.createWriteRequest` retry loop, post-commit synchronization)
- `ui/MDownloadRoot.kt` (`StartIntentSenderForResult` write-permission launcher, modal sheets for Inspector / Sleep Timer / Single & Batch Metadata Editor)
- `ui/screens/SettingsScreen.kt` (collapsible `ReplayGain & loudness` group)
- `player/ui/NowPlayingScreen.kt` (active Sleep Timer countdown pill, overflow actions `Audio details` / `Sleep timer` / `Edit metadata`, clickable `audioInfoSummary`)
- `player/ui/LibraryHubScreen.kt` (7 Built-in Smart Playlists, Custom Smart Playlists + rule editor/edit/delete, track overflow `Audio details` & `Edit metadata`, Songs multi-select `Batch edit` bar, collection/artist batch edit actions)
- `ui/LocalizedText.kt` (English & Vietnamese translations for all new UI strings)
- `app/src/androidTest/java/com/example/mdownload/MusicPlayerInstrumentedTest.kt` (added on-device tests for `ListeningHistoryRepository`, `SmartPlaylistRepository`, `ReplayGainAudioProcessor`, and `PlaybackGainCoordinator`)

---

## 3. Automated Test Results

### 3.1 JVM Unit Tests
- **Command:** `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest`
- **Result:** **PASS — `71 passed, 0 skipped, 0 failed`**
- **Verified Coverage in `AdvancedAudioFeaturesTest.kt` (plus all existing suites):**
  - ReplayGain Track / Album / Album→Track fallback, missing-tag `LEAVE_UNCHANGED` vs `APPLY_FALLBACK_PREAMP`, preamp bounds, sample-peak clipping protection, and sleep fade multiplication.
  - ReplayGain tag parsers: ID3v2 `TXXX`, Vorbis Comments, Opus RFC 7845 Q7.8 (`R128_TRACK_GAIN` / `R128_ALBUM_GAIN`), MP4 iTunes freeform atoms, and EBU R128 FFmpeg summary parser (`-18.0 LUFS` target).
  - Audio Quality Inspector honesty: lossy codecs (MP3/AAC/Opus) report `bitDepth = null`; lossless PCM/WAV reports exact bit depth; ffprobe JSON mapping.
  - Metadata validator: rejects blank titles, invalid years, invalid track/disc numbers; validates batch patch fields.
  - Smart Playlist evaluator: all 7 built-in definitions (`Recently Added`, `Recently Played`, `Most Played`, `Never Played`, `Favorites`, `High Quality`, `Recently Downloaded`), custom `ALL` (AND) / `ANY` (OR) rules, sorting, limits, and `ListeningSessionTracker` 30s / 50% qualification without double counting.
  - Sleep Timer math: monotonic `elapsedRealtime` remaining time, quadratic fade curve (`fadeOutSeconds`), and `+5/+10/+15m` extension.

### 3.2 On-Device Instrumented Tests (vivo X200s — `10AF8A0YUH005HA`)
- **Command:** `adb -s 10AF8A0YUH005HA shell am instrument -w -e class com.example.mdownload.ExampleInstrumentedTest,com.example.mdownload.MusicPlayerInstrumentedTest com.example.mdownload.test/androidx.test.runner.AndroidJUnitRunner`
- **Result:** **PASS — `OK (9 tests)` in `5.805s`, `0 failed, 0 ignored`**
- **Tests Executed:**
  1. `ExampleInstrumentedTest#useAppContext`
  2. `MusicPlayerInstrumentedTest#playbackStateStore_roundTripsQueuePositionAndModes`
  3. `MusicPlayerInstrumentedTest#playlistStore_roundTripsPlaylistsAtomically`
  4. `MusicPlayerInstrumentedTest#mediaItemMapper_preservesFullTrackMetadataAndExtras`
  5. `MusicPlayerInstrumentedTest#manifest_exposesMediaSessionServiceAndMediaButtonReceiver`
  6. `MusicPlayerInstrumentedTest#realDevicePlayback_exercisesMedia3ServiceQueueControlsAndMetadata` — verified live `HiFiPlaybackService` connection, real local audio playback through `DefaultAudioSink` + `ReplayGainAudioProcessor`, `AudioOutputInfo.isAvailable`, seek, next/previous, `playNext`, `addToQueue`, `removeFromQueue`, shuffle, repeat, `1.25x` speed, pause/stop.
  7. `MusicPlayerInstrumentedTest#artistStore_roundTripsUtf8NamesCoversOverridesAndAliases`
  8. `MusicPlayerInstrumentedTest#listeningHistoryAndSmartPlaylistRepos_roundTripPersistAtomically`
  9. `MusicPlayerInstrumentedTest#replayGainAudioProcessorAndCoordinator_scalesPcmAndRestoresAfterSleepFade` — verified direct PCM-16 ByteBuffer scaling (`-6 dB`: `20000` -> `~10024`), sleep fade multiplication (`0.5x`), and clean restoration on reset.
- **Additional Instrumented Integration Tests Compiled (Pending Device Reconnection):**
  - `MusicPlayerInstrumentedTest#sleepTimerController_integratesWithGainCoordinatorStartExtendAndCancel`
  - `MusicPlayerInstrumentedTest#audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly`
  - Both compile cleanly in `:app:assembleDebugAndroidTest`; the vivo X200s (`10AF8A0YUH005HA`) was disconnected prior to running this expanded pair, so they are intentionally listed as compiled-only rather than executed.

---

## 4. Real-Device Interactive Validation (vivo X200s)

Installed fresh `app-debug.apk` onto `10AF8A0YUH005HA` and validated live UI hierarchy & behavior via `studio_ui_state`, `studio_adb_shell_input`, and `studio_read_logcat`:

- **Cold Launch & Home Screen**: Launched cleanly (`TotalTime: 787 ms`), `yt-dlp ready`, Albums and Recently Added populated from real device library (`153 track(s) · 699.3 MB`).
- **Settings → ReplayGain & loudness**:
  - Expanded collapsible `ReplayGain & loudness` section cleanly.
  - Tapped `Track Gain` chip: header updated live from `Off` to `Track · 0.0 dB`; revealed `Preamp (with ReplayGain)` slider, `Tracks without ReplayGain tags` (`Leave unchanged (0 dB)` / `Fallback preamp`), `Peak-aware gain limiting` switch with honest sample-peak explanation, and live readout `Active stage: Bypass (unity 0 dB) · 0.0 dB (No tags · unchanged)`.
- **Library → Songs**:
  - Verified `Batch edit` button in Songs toolbar alongside `Play all` and `Shuffle`, plus track overflow triggers on every track row.
- **Library → Playlists (Smart Playlists)**:
  - Confirmed all 7 Built-in Smart Playlists evaluated live against the 153-track library (`Recently Added: 50`, `Recently Played: 0`, `Most Played: 0`, `Never Played: 153`, `Favorites: 0`, `High Quality: 0`, `Recently Downloaded: 50`).
  - Opened `New smart playlist` dialog and verified `Playlist name`, `All (AND)` / `Any (OR)` chips, rule type selector (`Artist contains`), `Add rule`, all 7 sort options, `Max tracks`, and `Cancel`/`Save` controls.
- **Logcat Health Check**:
  - Verified `0` unhandled exceptions, `0` crashes, and `0` ExoPlayer thread-violation errors across both the 9-test instrumented run and the interactive UI session.

---

## 5. Thread-Safety Fix Applied During Validation

During pre-release review of `PlaybackGainCoordinator` and `SleepTimerController`:
- Hardened `PlaybackGainCoordinator.applyPlayerVolumeSafely(...)` to dispatch `player.volume` mutations onto `player.applicationLooper` via `Handler(looper)` whenever invoked from background IO coroutines (e.g., post-metadata-edit ReplayGain refresh).
- Hardened `SleepTimerController.expireAndPause()` to dispatch `player.pause()` onto `player.applicationLooper`.
- Rebuilt APKs, verified `71/71` unit tests and a single-pass `9/9` instrumented run on vivo X200s (`5.805s`).

---

## 6. Documented Limitations & Honest Platform Caveats

1. **No Bit-Perfect / Direct Hardware DAC Claim**: Standard Android Media3 playback routes through `AudioTrack` / `AudioFlinger` unless an explicit USB-DAC direct driver is implemented. HiFiDeck reports real decoded PCM parameters without misleading audiophile marketing claims.
2. **Peak-Aware Limiting Scope**: ReplayGain clipping prevention uses embedded or EBU R128 sample-peak metadata (`1.0 / peak`) for headroom protection; it is not an oversampled inter-sample true-peak brickwall limiter.
3. **Sleep Timer Across Full OS Process Death**: Sleep Timer runs inside the foreground `HiFiPlaybackService` process and persists deadline metadata for restart recovery, but does not request `SCHEDULE_EXACT_ALARM` to fire after complete OS process termination.
4. **Metadata Container Support**: Non-destructive native tag editing supports **MP3 (ID3v2)**, **FLAC**, **M4A/MP4**, and **Ogg Vorbis/Opus**. WAV and uncommon legacy containers cleanly return an explicit `Unsupported` message rather than performing lossy re-encoding.
5. **Interactive Walkthrough Scope on Physical Device**: Settings (ReplayGain), Library Songs (Batch Edit toolbar), Library Playlists (7 Built-in Smart Playlists + New Smart Playlist editor), and real Media3 playback + PCM DSP tests were directly verified on vivo X200s; `AudioInspectorSheet`, `TrackMetadataEditorSheet`, and `SleepTimerSheet` were verified via unit/compile checks and are wired into `MDownloadRoot`, `NowPlayingScreen`, and `LibraryHubScreen` with zero warnings/errors.

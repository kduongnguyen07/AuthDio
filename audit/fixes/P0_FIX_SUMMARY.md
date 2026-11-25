# HiFiDeck Android — P0 Stabilization & Critical Bug Fixes Summary

**Date:** 2026-11-24  
**Repository:** `D:\MDownload` (`com.example.mdownload`, `:app`)  
**Target Device:** vivo X200s (`10AF8A0YUH005HA`, `V2458A`, OriginOS 7, Android 17 / API 37, `arm64-v8a`)  
**Scope:** Phase A (`STAB-02`: `META-01`, `META-02`) and Phase B (`STAB-01`: `AUD-01`, `AUD-02`). No feature expansion, no UI redesign, and all 13 original audit documents under `audit/` preserved unchanged.

---

## 1. Executive Fix Status

| Track | Audit IDs | Defect Summary | Status | Verification Evidence |
|---|---|---|---|---|
| **STAB-02 / A1** | `META-01` | Destructive `openOutputStream(uri, "wt")` truncation without durable rollback or startup recovery in `MetadataRepository` & `LyricsTagIO` | **FIXED & VERIFIED** | 8 failure-injection JVM tests (`MetadataIntegrityP0Test`) + on-device commit & crash recovery (`P0StabilizationInstrumentedTest`) |
| **STAB-02 / A2** | `META-02` | Duplicated ID3v2 / FLAC / MP4 / Ogg binary tag engines across `MetadataTagIO` & `LyricsTagIO` | **FIXED & VERIFIED** | Shared `AudioContainerPrimitives` (~640 lines deduplicated in `LyricsTagIO`); all format tests pass |
| **STAB-02 / A3** | `META-02` | ID3v2.4 per-frame unsynchronization bit (`0x02`) left set after de-unsynchronizing preserved frames | **FIXED & VERIFIED** | 2-round-trip ID3v2.4 unsync `APIC`/`PRIV` (`0xFF 0x00`) bit-exact test in `MetadataIntegrityP0Test` |
| **STAB-02 / A4** | `META-02` | ID3v2.2 files silently downgraded/wiped of unknown 3-char frames | **FIXED & VERIFIED** | Explicit `Unsupported` rejection by both `MetadataTagIO` & `LyricsTagIO` with original file untouched |
| **STAB-02 / A5** | `META-02` | MP4/M4A stubbed reader, dropped freeform/ReplayGain tags, un-cleared comments, missing `mdat` SHA-256 | **FIXED & VERIFIED** | Real `udta/ilst/----` + QuickTime `mdta` reader, `-movflags +use_metadata_tags+faststart`, `mdat` SHA-256 verified on JVM + vivo X200s real `libffmpeg.so` |
| **STAB-02 / A6** | `META-02` | Ogg/Opus silent artwork drop + stream-level tag overwrite bug caught on device | **FIXED & VERIFIED** | Explicit `Unsupported` rejection & UI disabled state for Ogg/Opus artwork; fixed `-metadata:s:a:0` stream tags & extension preservation for real `libffmpeg.so` on vivo X200s |
| **STAB-02 / A7** | `META-02` | Unvalidated FLAC `PICTURE` blocks (0x0 dims, unbounded size, missing header check) | **FIXED & VERIFIED** | Pure-Kotlin PNG/JPEG/WebP magic + dimension + 4 MiB + 24-bit FLAC ceiling validation in `AudioContainerPrimitives` |
| **STAB-02 / A8** | `META-02` | Stale Media3 notification/lock-screen/AVRCP metadata after edit; batch cancel hiding partial commits | **FIXED & VERIFIED** | In-place `Player.replaceMediaItem()` refresh verified on real ExoPlayer; `BatchMetadataResult.cancelled` + per-track `onTrackCommitted` refresh in `NonCancellable` block |
| **STAB-01 / B1** | `AUD-01` | Double ReplayGain attenuation race on `PLAYER_VOLUME_FALLBACK -> DSP_PCM_PROCESSOR` handoff | **FIXED & VERIFIED** | Ordered single-authority handoff verified in deterministic async-lag JVM test + live vivo X200s ExoPlayer `AudioTrack` logs |
| **STAB-01 / B2** | `AUD-01b` | Data race on `ReplayGainAudioProcessor.currentGain` across audio & coordinator threads | **FIXED & VERIFIED** | Audio-thread-exclusive `currentGain` + lock-free `AtomicBoolean snapRequested` + 4-writer stress test |
| **STAB-01 / B3** | `AUD-03` | Unity-gain processing honesty & bit-exact fast-path | **FIXED & VERIFIED** | Bit-exact PCM16 & PCM_FLOAT fast-path verified; Media3 `isActive()` constraint documented honestly (no false zero-copy bypass claim) |
| **STAB-01 / B4** | `AUD-02a` | Audible volume burst at Sleep Timer expiry during `AudioTrack` drain | **FIXED & VERIFIED** | Holds `sleepFadeFactor = 0.0f` until `onIsPlayingChanged(false)` + 150 ms settle (or immediate user resume) |
| **STAB-01 / B5** | `AUD-02b` | `END_OF_QUEUE` broken under `REPEAT_MODE_ALL`, `REPEAT_MODE_ONE`, and Shuffle | **FIXED & VERIFIED** | Logical queue end via `Timeline.getLastWindowIndex(shuffle)` + `onPositionDiscontinuity` verified across all Repeat × Shuffle × Single/Empty combinations |
| **STAB-01 / B6** | `AUD-07` | `MainViewModel.resetPreferences()` leaving stale ReplayGain & Sleep Timer defaults | **FIXED & VERIFIED** | `resetPreferences()` pushes reset `ReplayGainConfig` and default fade duration immediately |

---

## 2. Production & Test Files Modified / Added

### Production Code
- **Added** `app/src/main/java/com/example/mdownload/core/metadata/AudioContainerPrimitives.kt`
- **Added** `app/src/main/java/com/example/mdownload/core/metadata/SafeAudioWritePipeline.kt`
- **Updated** `app/src/main/java/com/example/mdownload/core/metadata/MetadataTagIO.kt`
- **Updated** `app/src/main/java/com/example/mdownload/core/metadata/MetadataRepository.kt`
- **Updated** `app/src/main/java/com/example/mdownload/core/audio/ReplayGainMetadataReader.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/lyrics/LyricsTagIO.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/lyrics/LyricsRepository.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/playback/ReplayGainAudioProcessor.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/playback/PlaybackGainCoordinator.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/playback/SleepTimerController.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/playback/HiFiPlaybackService.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/playback/MediaItemMapper.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/playback/PlaybackController.kt`
- **Updated** `app/src/main/java/com/example/mdownload/player/ui/TrackMetadataEditorSheet.kt`
- **Updated** `app/src/main/java/com/example/mdownload/MDownloadApp.kt`
- **Updated** `app/src/main/java/com/example/mdownload/MainViewModel.kt`

### Regression Tests Added
- **Added** `app/src/test/java/com/example/mdownload/MetadataIntegrityP0Test.kt` (11 JVM tests)
- **Added** `app/src/test/java/com/example/mdownload/AudioGainAndSleepTimerP0Test.kt` (7 JVM tests)
- **Added** `app/src/androidTest/java/com/example/mdownload/P0StabilizationInstrumentedTest.kt` (3 on-device tests on disposable `cacheDir` fixtures only)

---

## 3. Real-Device Bugs Discovered & Fixed During Verification

Running `P0StabilizationInstrumentedTest` on the vivo X200s with the real bundled `libffmpeg.so` caught **two additional production defects** that synthetic JVM tests alone could not reveal:
1. **`LyricsTagIO.embedLyricsIntoLocalFile` extensionless temp file**: `tempOut` ended in `.lyrics_tmp_<ns>` without `.m4a`/`.opus`, causing FFmpeg to fail output format detection on local M4A/Ogg files. Fixed by preserving the container extension on `tempOut`.
2. **Ogg/Opus FFmpeg stream-level tag precedence**: FFmpeg's Ogg muxer reads Vorbis/Opus comments from audio stream `s:a:0` (`-metadata:s:a:0`), so container-level `-metadata title=...` was silently overridden by `-map_metadata 0`'s copied stream tags! Our new read-back verifier caught the unchanged title immediately; fixed by passing `-map_metadata:s:a:0 0:s:a:0` and `-metadata:s:a:0 key=value` in both `MetadataTagIO` and `LyricsTagIO`.

---

## 4. Verification Snapshot

- **JVM Unit Tests (`:app:testDebugUnitTest --rerun-tasks`)**: **89 passed, 0 skipped, 0 failed** (71 pre-existing + 11 Phase A + 7 Phase B).
- **Build Artifacts (`:app:assembleDebug :app:assembleDebugAndroidTest`)**: **SUCCEEDED**.
- **On-Device Instrumented Suite (vivo X200s `10AF8A0YUH005HA`)**: **14 passed, 0 skipped, 0 failed** (`ExampleInstrumentedTest`: 1, `MusicPlayerInstrumentedTest`: 10, `P0StabilizationInstrumentedTest`: 3) in 9.845 s. Zero user music files touched.

See detailed companion reports in `audit/fixes/`:
- `METADATA_RECOVERY_DESIGN.md`
- `METADATA_INTEGRITY_TESTS.md`
- `AUDIO_GAIN_FIX_REPORT.md`
- `SLEEP_TIMER_FIX_REPORT.md`
- `P0_REGRESSION_REPORT.md`

# Architecture & Code Quality Audit (Phase 4)

## 1. Architectural Overview & Dependency Direction

HiFiDeck follows a **manual singleton service-locator (`MDownloadApp`) + monolithic ViewModel (`MainViewModel`) + Compose UI** pattern without Hilt/Koin or Room:

```
MDownloadApp (Singleton Graph — 15+ repos/controllers constructed eagerly on Main Thread)
  ├── Downloader: YtDlpEngine, MediaStoreExporter, StorageTools, DownloadQueueManager <-> DownloadForegroundService
  ├── Library: MusicLibraryRepository, PlaylistStore, ArtistStore, ArtworkCache, ListeningHistoryRepository, SmartPlaylistRepository
  ├── Audio & Metadata: AudioAnalysisRepository (AudioInspectorEngine, LoudnessScanner, ReplayGainMetadataReader), MetadataRepository (MetadataTagIO)
  ├── Lyrics: LyricsRepository (LyricsProvider, LyricsStorage, LyricsTagIO)
  └── Playback: ReplayGainAudioProcessor <- PlaybackGainCoordinator <- SleepTimerController
                 └── HiFiPlaybackService (ExoPlayer + MediaSession + ListeningSessionTracker)
                       └── PlaybackController (MediaController)
  └── MainViewModel (2,082 lines) -> MDownloadRoot -> Tabs / Sheets / NowPlayingScreen
```

**Dependency Direction Assessment:**
- **Good:** Pure domain objects (`core/`, `ReplayGainCalculator`, `SmartPlaylistEvaluator`, `OrbitalSelectionEngine`, `CircularScrubEngine`, `LyricsParser`, `LyricsSyncEngine`) have zero Android/UI dependencies.
- **Problematic:** `MainViewModel` imports and directly orchestrates **all 15 singletons** across 10 unrelated domains (downloading, YouTube search, SAF folder sync, library/playlists/artists, playback controls, lyrics, audio inspection, ReplayGain settings, sleep timer, single & batch metadata editing, diagnostics). There is no use-case/domain facade layer, and `MDownloadRoot` binds 27 `StateFlow`s at the root composable.

---

## 2. Deep-Dive Audit of the 12 Target Components

### 2.1 `MDownloadApp.kt` (193 lines)
- **Responsibilities:** Instantiates and wires every repository/controller synchronously inside `onCreate()`, applies initial ReplayGain config, initializes `YtDlpEngine` asynchronously.
- **Concrete Issues:**
  - **Main-Thread Startup Blocking:** Constructing `SettingsStore` (via `DownloadQueueManager`), `AudioAnalysisRepository`, `ListeningHistoryRepository`, `SmartPlaylistRepository`, and `SleepTimerController` performs **5 synchronous disk reads + `JSONObject` parses on the Main Thread** during `Application.onCreate()`. Directly contributes to the measured **740 ms median cold start** on vivo X200s.
  - **No Graceful Shutdown/Scope Isolation:** Uses `SupervisorJob() + Dispatchers.IO` application scope shared across services; reasonable for app singletons, but `SleepTimerController` and `DownloadQueueManager` rely on uncoordinated `synchronized` blocks.

### 2.2 `MainViewModel.kt` (2,082 lines — **Excessive Responsibilities**)
- **Responsibilities:** Handles 10+ feature domains, 27+ `MutableStateFlow`/`StateFlow` streams, 60+ public methods, coroutine jobs for search/preview/inspect/loudness/single-edit/batch-edit, MediaStore write-permission retry state machine, and diagnostics export.
- **Concrete Maintainability & Correctness Defects Caused by Size:**
  1. **State Drift on `resetPreferences()` (`MainViewModel.kt:1165-1180`):** Resets `SettingsStore` defaults (which resets ReplayGain and Sleep Timer prefs), **but forgets to push the reset config to `gainCoordinator.updateConfig(...)` or `sleepTimerController`** — leaving live DSP state desynchronized from UI settings until app restart!
  2. **Hardcoded Inaccurate Output Status (`MainViewModel.kt:1699-1725`):** `buildPlaybackOutputSnapshot()` hardcodes `audioOffloadStatus = "Standard PCM AudioTrack (software ReplayGain & fade active)"` even when ReplayGain is OFF or running in `PLAYER_VOLUME_FALLBACK`.
  3. **Batch Edit Cancellation Skips Cache Refresh (`MainViewModel.kt:1900-1980`):** Cancelling `batchMetadataJob` throws `CancellationException` before `succeededTracks.forEach { postCommitMetadataRefresh(...) }`, leaving already-modified tracks with stale library, artwork, and analysis caches.
  4. **Dead Methods:** `isTrackFavorite()` (line 1378) and `evaluateSmartPlaylist()` (line 2056) are unused (confirmed by IDE static analysis).
- **Concrete Refactoring Benefit:** Splitting into `DownloaderViewModel`, `LibraryViewModel`, `PlaybackViewModel`, and `TrackToolsViewModel` (Audio Inspector + Metadata Editor + Sleep Timer) eliminates cross-domain state coupling, fixes preference-sync omissions via reactive settings observation, and narrows Compose observation scopes.

### 2.3 `HiFiPlaybackService.kt` (442 lines)
- **Responsibilities:** Hosts `ExoPlayer`, `MediaSession`, custom `DefaultRenderersFactory` injecting `ReplayGainAudioProcessor`, connects `PlaybackGainCoordinator`, `SleepTimerController`, and `ListeningSessionTracker`, restores/persists playback state, resolves ReplayGain asynchronously on track transitions.
- **Concrete Issues:**
  1. **Previous Track's ReplayGain Persists During Async Lookup (`HiFiPlaybackService.kt:340-368`):** On `onMediaItemTransition`, if `audioAnalysisRepository.getCachedInfo(track)` is a cache miss, `resolveReplayGain()` runs asynchronously on `Dispatchers.IO` while **the previous track's ReplayGain remains active on the new track** until file copy + tag parse finishes (potentially hundreds of milliseconds on a 30 MB M4A!).
  2. **5-Second Queue JSON Rewrite Loop (`HiFiPlaybackService.kt:390-415`):** `persistCurrentState()` re-serializes and atomically writes up to 500 `LibraryTrack` objects to `playback_state.json` every 5 seconds during playback (unnecessary flash I/O & GC allocations).
  3. **Deprecated Media3 API (`HiFiPlaybackService.kt:110`):** Calls deprecated `DefaultAudioSink.Builder.setEnableAudioTrackPlaybackParams()`.
  4. **`onPositionDiscontinuity` Missing Cases (`HiFiPlaybackService.kt:212`):** Only handles `SEEK`/`SEEK_ADJUSTMENT`/`AUTO_TRANSITION`; ignores `REMOVE`/`SKIP`/`INTERNAL`.

### 2.4 `PlaybackController.kt` (591 lines)
- **Responsibilities:** Connects `MediaController`, polls playback position every 250ms while playing, manages queue commands, maintains `PlaybackUiState`, syncs track metadata from library.
- **Concrete Issues:**
  1. **Stale MediaSession/Notification Metadata After Tag Edit (`PlaybackController.kt:505-525`):** `refreshTrackMetadataFromLibrary()` updates `_state` (in-app UI) but **never calls `player.replaceMediaItem(index, updatedMediaItem)`** (or `MediaItem` metadata update). Consequently, the Android media notification, lock screen, Origin Island, and Bluetooth AVRCP display **old title/artist/album/artwork** until a new queue is loaded.
  2. **High-Frequency StateFlow Emissions:** Emits a full copy of `PlaybackUiState` (containing the entire `List<LibraryTrack>` queue) every 250ms while playing, which triggers recomposition at `MDownloadRoot`! Separating `positionMs` into its own lightweight `StateFlow<Long>` (or reading via lambda) avoids recomposing the entire app shell 4×/second.

### 2.5 `PlaybackGainCoordinator.kt` (161 lines)
- **Responsibilities:** Single authority composing ReplayGain (`ReplayGainCalculator`) × Sleep Timer fade (`0f..1f`) into effective linear gain and applying it via `ReplayGainAudioProcessor` (preferred) or `player.volume` fallback.
- **Concrete Issues:**
  1. **P0 Double Attenuation Bug (`PlaybackGainCoordinator.kt:105-145`):** When `reapplyLocked()` runs before ExoPlayer configures PCM (`!audioProcessor.isProcessingPcm`), it sets BOTH `audioProcessor.setTargetLinearGain(effective)` AND `player.volume = effective.coerceIn(0f, 1f)`. Later, when `ReplayGainAudioProcessor.onConfigure()` activates PCM processing, **nothing notifies `PlaybackGainCoordinator` to reset `player.volume = 1.0f`**. Both stages attenuate simultaneously!
  2. **Reverse Transition Bug:** When `onReset()` resets `isProcessingPcm = false`, coordinator is not notified either.
  3. **Looper Thread Coupling:** Uses `Handler(player.applicationLooper).post` — good for ExoPlayer thread confinement, but because the post is async, `player.volume` updates lag behind synchronous `audioProcessor` target writes.

### 2.6 `ReplayGainAudioProcessor.kt` (160 lines)
- **Responsibilities:** Media3 `BaseAudioProcessor` applying smoothed linear gain to `PCM_16BIT` and `PCM_FLOAT` buffers.
- **Concrete Issues:**
  1. **Thread-Safety Data Race (`ReplayGainAudioProcessor.kt:32-44, 62`):** `currentGain` is **non-`@Volatile`** (`private var currentGain = 1.0f`), read/written on ExoPlayer's internal audio thread (`queueInput`, `onFlush`, `onReset`), AND mutated from Main/IO threads inside `snapToTargetGain()`!
  2. **No Unity-Gain Bypass:** Does not override `isActive()`, so even when `ReplayGainMode.OFF` and `sleepFadeFactor == 1.0f`, every PCM buffer is allocated and copied (`replaceOutputBuffer(remaining)`).
  3. **Deprecated `onFlush()` override** (Media3 1.9.2 prefers `onFlush(StreamMetadata)`).

### 2.7 `MetadataTagIO.kt` (1,084 lines)
- **Responsibilities:** Reads/writes ID3v2 (MP3), Vorbis comments/PICTURE (FLAC), delegates MP4/M4A & Ogg/Opus to `libffmpeg.so -c copy`, prepares artwork bitmaps, computes audio-payload SHA-256 for MP3/FLAC.
- **Concrete Issues:**
  - **Massive Duplication with `LyricsTagIO.kt` (1,114 lines):** ~700+ lines of ID3v2 frame parsing, synchsafe ints, FLAC block I/O, Ogg packet scanning, and FFmpeg remuxing are copy-pasted between the two files — causing the exact same ID3v2.4 unsync and ID3v2.2 wipe bugs in BOTH files!
  - `readMp4BasicMetadata(file)` (line 654) is an unimplemented stub returning empty `EditableTrackMetadata()`.
  - Full details in [05_METADATA_INTEGRITY_AUDIT.md](file:///D:/MDownload/audit/05_METADATA_INTEGRITY_AUDIT.md).

### 2.8 `MetadataRepository.kt` (325 lines)
- **Responsibilities:** Reads embedded tags from `content://`/file URIs, stages edits to cache files, verifies writes, commits back to storage, handles `RecoverableSecurityException` / `RequiresWritePermission`.
- **Concrete Issues:**
  - **P0 Non-Atomic `content://` Write (`MetadataRepository.kt:215-245`):** Opens `contentResolver.openOutputStream(uri, "wt")` which truncates the target file to 0 bytes immediately before copying staged bytes back.
  - Checks `usableSpace` on `cacheDir` only, not on external shared storage volume where the target audio file lives.

### 2.9 `AudioAnalysisRepository.kt` (277 lines)
- **Responsibilities:** Version-keyed (`sizeBytes|dateModifiedSec`) persistent JSON cache (`audio_analysis_cache.json`) coordinating `AudioInspectorEngine`, `ReplayGainMetadataReader`, and `LoudnessScanner`.
- **Concrete Issues:**
  1. **Synchronous Main-Thread `init` load** of `audio_analysis_cache.json`.
  2. **Tag-Only ReplayGain Lookups Not Cached (`AudioAnalysisRepository.kt:135-170`):** `resolveReplayGain()` only checks cache; on miss it reads directly from disk/URI (copying up to 2–32 MiB to temp file!) and **does NOT store the result in the cache**, repeating the full copy/parse on every playback unless the user explicitly opens Audio Quality Inspector!
  3. **Version-Key Mismatch with `MediaItemMapper`:** `MediaItemMapper` drops `dateModifiedSec` (defaults to `0L`), so tracks restored/played via `HiFiPlaybackService` compute key `"size|0"` while `LibraryTrack` computes `"size|<realDateModified>"`, causing **100% cache misses** in background playback!

### 2.10 `SmartPlaylistRepository.kt` (164 lines) & `SmartPlaylistEvaluator.kt` (160 lines)
- **Responsibilities:** Stores custom smart playlist definitions (JSON), provides 7 built-in definitions, evaluates rules/sorting/limits against library + listening stats + favorites + analysis cache.
- **Concrete Issues:**
  - Synchronous Main-Thread JSON load in `init`.
  - `SmartPlaylistEvaluator.resolveStats()` (lines 118-126) performs an `O(M)` linear scan over `statsByTrackId.values` for every unplayed track, invoked inside `filter` AND `sortedWith` comparator → **`O(N log N × M)`** on the Main Thread inside Compose!
  - `PlaylistSortOrder.SHUFFLE` calls `.shuffled()` inside Compose `remember` — reshuffling every 10 seconds when `listeningStats` emits!

### 2.11 `SleepTimerController.kt` (414 lines)
- **Responsibilities:** Manages DURATION / END_OF_TRACK / END_OF_QUEUE sleep modes, monotonic `elapsedRealtime` countdown, fade-out loop, `sleep_timer.json` persistence, ExoPlayer pause on expiry.
- **Concrete Issues:**
  - Volume spike on expiry (`resetSleepFade()` before pause drains), `END_OF_QUEUE` broken under Repeat All/One, frozen restored countdown until `attachPlayer()`, synchronous `persistState()` file I/O on Main Thread inside `synchronized(lock)`, `updateFadeOutSeconds()` not persisted.

### 2.12 `NowPlayingScreen.kt` (956 lines)
- **Responsibilities:** Full-screen Orbital Normal Player + Orbital Navigator overlay + Lyrics screen switcher + Queue/Speed dialogs + overflow menu.
- **Concrete Issues:**
  - Reads `orbitalController.orbitProgress.value`, `holdProgress`, and `state.positionMs` directly in composition, causing full-screen recomposition on every animation frame and playback tick.
  - Nested `pointerInput` layers create the 4 diagonal hit-test dead zones with `CircularSeekRing`.

---

## 3. Confirmed Dead Code & Redundancies

| File / Symbol | Size | Evidence |
|---|---|---|
| `player/ui/CircularPlaybackProgress.kt` | 216 lines | 0 code usages (replaced by `CircularSeekRing.kt`; only referenced in `.md` docs). |
| `ui/screens/LibraryScreen.kt` | 85 lines | 0 code usages (`MDownloadRoot.kt` invokes `LibraryHubScreen` directly). |
| `engine/AudioPlayerManager.kt` | 13 lines | Stub `LegacyAudioPlayerMarker` object; 0 usages. |
| `ui/theme/Color.kt`, `ui/theme/Type.kt` | 89 lines | Legacy Material template tokens unused by `HiFiDeckTheme`. |
| `TextUtils.archiveFileName()` & `DownloadQueueManager.archiveDir` | ~25 lines | Unused yt-dlp archive infrastructure superseded by `AudioTagFingerprint`. |
| `MainViewModel.isTrackFavorite()`, `MainViewModel.evaluateSmartPlaylist()` | ~15 lines | Flagged unused by IDE inspection. |
| `MetadataTagIO.kt` vs. `LyricsTagIO.kt` | ~700 dup lines | Near-identical ID3v2/FLAC/Ogg/FFmpeg helpers maintaining duplicate P0 bugs. |

---

## 4. Concrete Memory Leak & Concurrency Checklist

- **Activity/Context Leaks:** No obvious `Activity` leaks detected in singletons (all receive `applicationContext`). `MainActivity` properly removes `ProcessLifecycleOwner` observer in `onDestroy()` / `DisposableEffect`.
- **Unbounded Caches:** `ArtworkCache` uses bounded `LruCache` (good), `AudioAnalysisRepository` has **no max entry eviction** (`audio_analysis_cache.json` grows unboundedly as files are renamed/deleted).
- **Concurrency Risks:**
  - `ReplayGainAudioProcessor.currentGain` unsynchronized cross-thread mutation.
  - `DownloadQueueManager.persist()` synchronous file I/O invoked directly on Main Thread from `MainViewModel` actions (pause, remove, reorder).
  - `SleepTimerController.persistState()` synchronous file I/O on Main Thread inside `synchronized(lock)`.
  - `AudioInspectorEngine.probeWithFfprobe()` `redirectErrorStream(false)` without draining `errorStream` (classic JVM subprocess pipe deadlock risk).

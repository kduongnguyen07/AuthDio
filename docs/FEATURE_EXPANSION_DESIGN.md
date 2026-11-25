# AuthDio Android — Advanced Audio & Library Feature Expansion Design

> **Branding Note:** AuthDio was formerly known as HiFiDeck / MDownload.

## 1. Repository Audit Summary

Baseline architecture audit (`com.example.mdownload`, `compileSdk = 37`, `minSdk = 35`, Kotlin `2.2.10`, AGP `9.5.2`, Jetpack Compose BOM `2026.02.01`, Media3 `1.9.2`, Coil `3.2.0`, `youtubedl-android` `0.18.1` + `ffmpeg`).

### Existing Components Verified
- **Playback Stack**:
  - `HiFiPlaybackService` (`MediaSessionService`) owns the single `ExoPlayer` + `MediaSession` instance, AudioFocus, becoming-noisy handling, wake lock, notification artwork enrichment, and playback resumption.
  - `PlaybackController` connects via `MediaController` and exposes `StateFlow<PlaybackUiState>` with Media3 shuffle timeline ordering (`effectiveQueueIndices`) and `AudioOutputInfo`.
  - `PlaybackStateStore` persists queue/position/shuffle/repeat/speed in `filesDir/hifideck_data/playback_state.json`.
- **UI & Custom Interactions**:
  - `NowPlayingScreen` integrates Orbital Navigation (`OrbitalNavigator`, `OrbitalGestureHandler`, `OrbitalTransition`), Circular Scrubbing (`CircularSeekRing`, `CircularScrubState`), and Synchronized Lyrics (`LyricsScreen`, `LyricsSyncEngine`).
  - `LibraryHubScreen` hosts Songs, Albums, Artists (with `ArtistStore` custom profiles/covers), Playlists (`PlaylistStore`), and Folders tabs.
  - `SettingsScreen` uses collapsible `SettingsGroup` cards and bilingual (`bi(en, vi)`) copy.
- **Downloader & Storage**:
  - `YtDlpEngine`, `DownloadQueueManager`, `DownloadForegroundService`, `MediaStoreExporter`, `CoverArtHelper`, `AudioTagFingerprint`.
  - Both `libffmpeg.so` and `libffprobe.so` are packaged in `applicationInfo.nativeLibraryDir`, with shared libraries unpacked in `noBackupFilesDir/youtubedl-android/packages/ffmpeg/usr/lib`.
- **Metadata & Lyrics Infrastructure**:
  - `AudioContainerPrimitives`, `SafeAudioWritePipeline`, `MetadataTagIO`, and `LyricsTagIO` share pure-Kotlin ID3v2.3/v2.4 & FLAC binary rewriters, MP4 (`udta` + `mdta`) / Ogg readers, bounded FFmpeg `-c copy` remuxing, and durable rollback journaling.
  - `LyricsRepository` caches lyrics in memory and sidecar storage (`LyricsStorage`), preserving user offsets and online/local layers.
  - `MusicLibraryRepository` queries MediaStore (`VOLUME_EXTERNAL_PRIMARY`) and SAF trees, overlays `ArtistStore` overrides, and manages `ArtworkCache`.

### Key Integration Risks & Mitigations
1. **Gain Competition (ReplayGain vs. Sleep Timer Fade-Out)**:
   - *Risk*: Independent calls to `player.volume` overwrite each other, and `player.volume` is clamped to `[0f, 1f]` (no positive preamp/gain boost) and can pop on track change.
   - *Mitigation*: Centralize gain composition in `PlaybackGainCoordinator` (`effectiveLinearGain = replayGainLinear * sleepFadeFactor`) driving a custom Media3 `ReplayGainAudioProcessor` (`BaseAudioProcessor`) installed in `HiFiPlaybackService`'s `DefaultAudioSink` with per-sample ramping and ordered single-authority handoff. At unity (`1.0f`), `queueInput()` runs a bit-exact `ByteBuffer.put()` fast path; if a stream bypasses PCM processing, fallback attenuation uses `player.volume` while holding processor target at `1.0f` and reports the active path truthfully.
2. **Android 15–17 Scoped Storage Metadata Writes**:
   - *Risk*: Direct writes to third-party MediaStore audio files throw `SecurityException` / `RecoverableSecurityException` without user consent.
   - *Mitigation*: Use `MediaStore.createWriteRequest(contentResolver, uris)` via `ActivityResultContracts.StartIntentSenderForResult` whenever `openFileDescriptor(uri, "rw")` is denied, combined with temp-file staging, audio payload integrity verification, and read-back validation.
3. **Preserving Lyrics, ReplayGain, Fingerprints & Unrecognized Tags**:
   - *Risk*: Naive tag rewriting strips `USLT`/`SYLT`, `REPLAYGAIN_*`, `MDOWNLOAD_YT_ID`, or vendor tags.
   - *Mitigation*: Pure-Kotlin frame-preserving ID3v2 and FLAC block writers update only targeted fields; FFmpeg `-c copy -map_metadata 0` preserves existing tags for M4A/Ogg/Opus.
4. **Heavy Processing Impact on Orbital Gestures & Playback**:
   - *Risk*: Running FFprobe/FFmpeg or smart-playlist evaluation on main thread or per 150ms ticker causes jank.
   - *Mitigation*: All file I/O, FFprobe, and optional EBU R128 scans run on `Dispatchers.IO` with bounded concurrency (`Semaphore(1)` for FFmpeg/FFprobe), explicit cancellation, and persistent file-versioned caching (`uri + sizeBytes + modifiedSec`). Smart playlists re-evaluate only on repository/history/analysis changes—never on playback position ticks.

---

## 2. Module Map (Adapted to Existing Repository)

```text
app/src/main/java/com/example/mdownload/
├── core/
│   ├── audio/
│   │   ├── AudioQualityModels.kt          # AudioQualityInfo, PlaybackOutputSnapshot, ReplayGainInfo, ReplayGainSettings
│   │   ├── ReplayGainCalculator.kt        # Pure dB->linear, track/album fallback, preamp, peak-aware limiting
│   │   ├── ReplayGainMetadataReader.kt    # Pure-Kotlin ID3v2 (TXXX/RVA2), FLAC/Vorbis, Opus (R128 Q7.8), MP4 (----)
│   │   ├── AudioInspectorEngine.kt        # Pure-Kotlin header parser + MediaExtractor + optional libffprobe.so enrichment
│   │   ├── LoudnessScanner.kt             # Optional cancellable FFmpeg ebur128 scanner (-18 LUFS target)
│   │   └── AudioAnalysisRepository.kt     # Persistent file-versioned cache + invalidation + single-flight concurrency
│   └── metadata/
│       ├── AudioMetadataModels.kt         # EditableTrackMetadata, BatchMetadataPatch, ValidationResult, MetadataWriteResult
│       ├── MetadataValidator.kt           # Pure field validation (year, track/disc numbers, totals, artwork limits)
│       ├── MetadataTagIO.kt               # Format-aware tag reader/writer (ID3v2, FLAC, MP4/M4A, Ogg/Opus) preserving all unknown/lyrics/RG tags
│       └── MetadataRepository.kt          # Safe write pipeline, snapshot conflict check, read-back verification, cache invalidation
├── player/
│   ├── playback/
│   │   ├── ReplayGainAudioProcessor.kt    # Media3 BaseAudioProcessor (PCM 16/float, smooth gain ramping, peak clamp)
│   │   ├── PlaybackGainCoordinator.kt     # Single authority composing ReplayGain * SleepTimer fade
│   │   ├── SleepTimerController.kt        # Service-owned monotonic countdown, End-of-Song, End-of-Queue, fade-out
│   │   ├── ListeningSessionTracker.kt     # Qualified play-count & real media-time accumulator
│   │   ├── HiFiPlaybackService.kt         # Updated: wires AudioProcessor, GainCoordinator, SleepTimer, ListeningTracker
│   │   └── PlaybackController.kt          # Updated: exposes AudioOutputSnapshot & coordinates metadata refresh
│   ├── library/
│   │   ├── ListeningHistoryRepository.kt  # Atomic JSON persistence of ListeningStats (stable track identity)
│   │   ├── SmartPlaylistModels.kt         # SmartPlaylistDefinition, PlaylistRule, MatchMode, PlaylistSortOrder, Built-ins
│   │   ├── SmartPlaylistEvaluator.kt      # Pure typed predicate & sort evaluation engine
│   │   └── SmartPlaylistRepository.kt     # Built-in + persisted custom smart playlists with reactive evaluation
│   └── ui/
│       ├── AudioInspectorSheet.kt         # Compact "Audio Details" bottom sheet (Source vs Playback Output)
│       ├── ReplayGainSettingsSection.kt   # ReplayGain settings UI integrated in SettingsScreen
│       ├── TrackMetadataEditorSheet.kt    # Single & Batch Metadata + Artwork editor with unsaved-changes guard
│       ├── SmartPlaylistDialogs.kt        # Custom Smart Playlist rule builder & editor
│       └── SleepTimerSheet.kt             # Compact Sleep Timer bottom sheet + subtle Now Playing indicator
```

---

## 3. Shared System Contracts

### 3.1 Unified Track Identity
- Primary runtime key: `track.uri` (`content://` or `file://`).
- Secondary fallback fingerprint: `videoId` (when present) or normalized `displayName|sizeBytes|durationSecBucket` so listening history can survive MediaStore rescans without duplicate entries.
- Cache invalidation key for `AudioAnalysisRepository`: `"$uri|$sizeBytes|$dateModifiedSec|v$CACHE_SCHEMA_VERSION"`. Any file modification changes size or timestamp and invalidates stale inspection/ReplayGain/loudness results automatically.

### 3.2 Cache Invalidation Matrix on Successful Metadata Write
1. `ArtworkCache.invalidate(uri)`
2. `AudioAnalysisRepository.invalidate(uri)`
3. `MusicLibraryRepository.refreshTrackAfterEdit(uri, editedMetadata)` (updates MediaStore via `MediaScannerConnection` + refreshes in-memory list immediately)
4. `LyricsRepository.onTrackMetadataEdited(uri, identityChanged)` (preserves existing lyrics; only re-matches online if title/artist/album changed and no local lyrics exist)
5. `PlaybackController.refreshTrackMetadataFromLibrary()` (updates Now Playing & MiniPlayer without restarting playback)
6. `HiFiPlaybackService` re-evaluates active ReplayGain metadata if the edited track is playing.
7. `SmartPlaylistRepository` automatically re-emits via `libraryTracks` flow observation.

### 3.3 UI Entry Points (Preserving AuthDio Dark Cinematic Design)
| Feature | Entry Point |
|---|---|
| ReplayGain | Settings → Audio & Playback → ReplayGain / Volume Normalization |
| Audio Quality Inspector | Now Playing → Overflow (`Audio details`) & subtle format pill; Library track `⋮` → `Audio details` |
| Metadata Editor | Library track `⋮` → `Edit metadata`; Now Playing → Overflow (`Edit metadata`); Library Songs multi-select → `Batch edit` |
| Smart Playlists | Library → Playlists tab (7 Built-in Smart Playlists + Custom Smart Playlists builder) |
| Sleep Timer | Now Playing → Overflow (`Sleep timer`) + subtle active countdown badge in Now Playing header |

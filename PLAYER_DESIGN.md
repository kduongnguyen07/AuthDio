# HiFiDeck Android — Native Music Player Technical Design

## 1. Repository Audit Summary

Before designing the player integration, the existing HiFiDeck Android repository (`D:\MDownload`, package `com.example.mdownload`) was audited:

| Area | Existing State | Integration Decision |
| --- | --- | --- |
| **Build & SDK** | Single `:app` Gradle module, AGP `9.5.2`, Kotlin `2.2.10`, Compose BOM `2026.02.01`, `compileSdk = 37`, `minSdk = 35`, `targetSdk = 37`. | Keep single `:app` module with logical `com.example.mdownload.player.*` subpackages (`playback`, `library`, `queue`, `ui`). |
| **Downloader Stack** | `YtDlpEngine` (`youtubedl-android:0.18.1` + bundled FFmpeg/QuickJS/Python), `DownloadQueueManager`, `DownloadForegroundService` (`foregroundServiceType="dataSync"`), `CoverArtHelper`, `MediaStoreExporter`, `ShareDownloadActivity`. | Preserve 100% untouched in responsibility; add incremental post-export notification hook to the music library. |
| **Previous Playback** | `AudioPlayerManager` created a bare `ExoPlayer` directly inside `MDownloadApp` with no `MediaSessionService`, no queue, no background lifecycle, no audio focus/noisy handling, no persistence, and no Now Playing screen. | Replace internals with Media3 `HiFiPlaybackService` (`MediaSessionService`), `PlaybackController` (`MediaController`), and `PlaybackStateStore`. |
| **Storage & Library** | `MediaStoreExporter` exported to `Music/MDownload` (MediaStore) or a user-selected SAF tree and scanned only the active download folder. | Reuse `MediaStoreExporter` for all writes; introduce `MusicLibraryRepository` (`player/library/`) that queries device-wide MediaStore audio + active SAF folder, deduplicates records, caches embedded artwork off-main-thread, and supports incremental single-URI updates on download completion. |
| **Persistence & DI** | Manual `Application` service locator (`MDownloadApp`) + atomic JSON files under `filesDir/hifideck_data/`. | Follow the same lightweight pattern (`playback_state.json`, `playlists.json`) with zero heavy DI or Room bloat. |
| **UI & Theme** | Jetpack Compose + Material 3, Editorial Tech theme (`#F8F7F4` canvas, `#7B56DF` violet accent, 1dp hairline borders, full dark mode, English/Vietnamese localization). | Build MiniPlayer, NowPlayingScreen, QueueSheet, and Library browsing screens (Songs, Albums, Artists, Playlists, Folders) using the exact same theme tokens. |

---

## 2. Logical Architecture & Component Responsibilities

```text
com.example.mdownload
├── MDownloadApp                    # Service locator: downloader + player + library singletons
├── MainActivity                    # Single Activity; handles permissions, Share handoff, Now Playing deep link
├── MainViewModel                   # UI orchestration bridging Downloader, Library, and PlaybackController
│
├── core/                           # Shared models, settings persistence, URL/text utilities
│   ├── Models.kt                   # AppSettings (+ playback/library prefs), LibraryTrack, Playback models
│   ├── SettingsStore.kt            # Atomic JSON persistence for settings, download queue & history
│   └── LibraryQueryHelper.kt       # Accent-insensitive search & sorting (Title/Artist/Album/Duration/Date/Size)
│
├── engine/                         # Existing downloader pipeline (preserved)
│   ├── YtDlpEngine.kt              # On-demand yt-dlp + FFmpeg execution (never kept alive for playback)
│   ├── DownloadQueueManager.kt     # Multi-threaded download queue; notifies library on verified export
│   ├── DownloadForegroundService.kt# dataSync FGS for screen-off downloading
│   └── MediaStoreExporter.kt       # Atomic verified export to MediaStore / SAF + hidden YT ID fingerprinting
│
└── player/                         # New offline-first native music player subsystem
    ├── playback/
    │   ├── HiFiPlaybackService.kt  # Media3 MediaSessionService (ExoPlayer + MediaSession + notification)
    │   ├── PlaybackController.kt   # MediaController bridge exposing single StateFlow<PlaybackUiState>
    │   ├── PlaybackStateStore.kt   # Atomic JSON persistence for queue, index, position, shuffle, repeat, speed
    │   └── MediaItemMapper.kt      # LibraryTrack <-> Media3 MediaItem (+ embedded artwork & extras)
    │
    ├── library/
    │   ├── MusicLibraryRepository.kt # MediaStore + SAF repository, incremental ingest, validation, grouping
    │   ├── ArtworkCache.kt         # Thread-safe LruCache + downsampled Bitmap decoding off the main thread
    │   └── PlaylistStore.kt        # Atomic JSON persistence for user-created playlists
    │
    └── ui/
        ├── LibraryHubScreen.kt     # Songs / Albums / Artists / Playlists / Folders + detail drill-downs
        ├── MiniPlayer.kt           # Persistent bottom mini-player with artwork, progress, Play/Pause, Next
        ├── NowPlayingScreen.kt     # Full-screen player: artwork, seek bar, transport, shuffle/repeat, speed, codec info
        └── PlaybackQueueSheet.kt   # Live queue sheet: tap to jump, reorder, remove, clear
```

---

## 3. Playback Service Lifecycle

1. **Creation (`HiFiPlaybackService.onCreate`)**:
   - Constructs `ExoPlayer` with:
     - `AudioAttributes(CONTENT_TYPE_MUSIC, USAGE_MEDIA)` and `handleAudioFocus = true`.
     - `setHandleAudioBecomingNoisy(true)` to auto-pause when wired or Bluetooth headphones disconnect.
     - `setWakeMode(C.WAKE_MODE_LOCAL)` for reliable screen-off local playback without holding Wi-Fi locks.
   - Builds `MediaSession` attached to `ExoPlayer`:
     - `sessionActivity` PendingIntent targeting `MainActivity` (`EXTRA_OPEN_NOW_PLAYING = true`) so tapping the system notification or lock-screen player opens Now Playing.
     - `MediaSession.Callback` implementing `onPlaybackResumption(...)` using `PlaybackStateStore` so Bluetooth play buttons and Android System UI resumption can restart playback after the service stops.
   - Publishes standard Media3 `MediaStyle` notifications via `DefaultMediaNotificationProvider` populated directly from `MediaSession` metadata and artwork.
2. **Active Background Playback**:
   - Service runs as `foregroundServiceType="mediaPlayback"` while playing; playback lifetime is bound to `HiFiPlaybackService`, completely decoupled from `MainActivity` or Composables.
   - `DownloadForegroundService` (`dataSync`) and `HiFiPlaybackService` (`mediaPlayback`) run independently so downloading and listening work concurrently without interfering with each other.
   - `YtDlpEngine` (Python runtime) is invoked strictly per download/inspect job and is never started or kept alive for music playback.
3. **Task Removal & Destruction**:
   - In `onTaskRemoved`, if playback is not ongoing (`!player.playWhenReady || player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED`), persists state and stops the service cleanly; if actively playing, playback continues uninterrupted in the background.
   - In `onDestroy`, flushes final playback state (queue, index, position, shuffle, repeat, speed) to `PlaybackStateStore`, releases `MediaSession` and `ExoPlayer`, and cancels service coroutines.

---

## 4. Data Flow & State Ownership

```text
MediaStore / SAF Folder ──► MusicLibraryRepository ──► StateFlow<List<LibraryTrack>>
                                   ▲                         │
                                   │ incremental single-URI  │ grouped views (Albums, Artists,
                                   │ ingest on finish        │ Folders) + PlaylistStore
                                   │                         ▼
DownloadQueueManager ────► MediaStoreExporter          MainViewModel ──► Compose UI
                                                             │             (Library, MiniPlayer,
                                                             │ commands     NowPlaying, Queue)
                                                             ▼
PlaybackStateStore ◄──► HiFiPlaybackService (ExoPlayer + MediaSession) ◄──► PlaybackController
                                │
                                ▼
             Android System Media Controls / Lock Screen / Bluetooth / Headset
```

**Single Source of Truth Rules:**
- **Live Playback State**: Owned solely by `ExoPlayer` inside `HiFiPlaybackService` and mirrored into UI via `PlaybackController` (`MediaController.Listener`). There is no competing local playback state.
- **Position Updates**: No background polling when paused or idle; a lightweight 400ms ticker runs only while `isPlaying == true` and stops immediately on pause/stop/release.
- **Persisted Playback State**: `HiFiPlaybackService` saves snapshots to `PlaybackStateStore` on media transition, pause, queue edit, mode/speed change, and teardown. On cold launch, if the session is empty and "Restore last session" is enabled, `PlaybackController` hydrates the saved queue & position in paused state (verifying file accessibility first so deleted files are skipped gracefully).

---

## 5. Downloader-to-Library Integration

```text
User submits URL / Share → HiFiDeck
  → YtDlpEngine downloads & converts in app-private temp workspace (not visible to library)
  → CoverArtHelper embeds cleaned metadata + HD artwork + hidden YouTube video ID tag
  → MediaStoreExporter verifies audio via MediaMetadataRetriever & checksum, then publishes atomically
      (IS_PENDING: 1 → 0 for MediaStore, or temp-file-then-rename for SAF)
  → DownloadQueueManager invokes onTrackExported(uri, displayName)
  → MusicLibraryRepository.ingestExportedTrack(uri) queries ONLY the single exported URI
      and merges/deduplicates it into StateFlow<List<LibraryTrack>> (no full-device rescan)
  → Track is immediately visible in Library & playable from Downloads History ("Play now" / "Add to queue")
```

- **Partial File Exclusion**: Temp files reside in `cacheDir`/`filesDir` and MediaStore inserts use `IS_PENDING = 1` until verified; SAF exports write to `.mdownload_tmp_*` before atomic rename. `MusicLibraryRepository` filters `IS_PENDING = 0`, `IS_TRASHED = 0`, `SIZE >= 1024`, and excludes `.mdownload_tmp_` prefixes.
- **Deduplication**: Tracks are deduplicated by canonical content URI (and normalized display name + file size when merging SAF + MediaStore).

---

## 6. Third-Party Dependencies & License Review

| Library / Reference | Version / Role | License | Usage in HiFiDeck |
| --- | --- | --- | --- |
| `androidx.media3:media3-exoplayer` | `1.9.2` (Audio playback engine) | Apache-2.0 | Official AndroidX dependency |
| `androidx.media3:media3-session` | `1.9.2` (`MediaSessionService`, `MediaController`, `MediaButtonReceiver`) | Apache-2.0 | Official AndroidX dependency (added) |
| `io.coil-kt.coil3:coil-compose` | `3.2.0` (Async image loading) | Apache-2.0 | Existing dependency |
| `io.github.junkfood02.youtubedl-android` | `0.18.1` (Existing downloader runtime) | GPL-3.0 | Existing downloader dependency (unchanged) |
| **Auxio** (GPL-3.0) / **Gramophone** (GPL-3.0) / **Melodia** | Architectural & UX reference only | N/A | Studied conceptually for library organization, MediaSession patterns, and Compose player UX. **Zero source code or assets copied.** |

---

## 7. Android System Integration & OriginOS 7 Considerations

- **Manifest Configuration**:
  - `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MEDIA_PLAYBACK` + `READ_MEDIA_AUDIO` + `POST_NOTIFICATIONS`.
  - `HiFiPlaybackService` registered with `android:foregroundServiceType="mediaPlayback"`, `android:exported="true"`, and intent filters for `androidx.media3.session.MediaSessionService` and `android.media.browse.MediaBrowserService`.
  - `androidx.media3.session.MediaButtonReceiver` registered for `android.intent.action.MEDIA_BUTTON` to enable Bluetooth/headset playback resumption.
- **OriginOS 7 (vivo X200s) Compatibility & Verified Real-Device Findings (`V2458A`, Android 17 / API 37)**:
  - HiFiDeck exposes standard Android `MediaSession` + `MediaStyle` notifications with embedded `artworkData` (scaled JPEG bytes), `TITLE`, `ARTIST` (also mirrored to `MediaDescription.subtitle`), `ALBUM` (also mirrored to `MediaDescription.description`), `DURATION`, and standard transport actions (`PLAY`, `PAUSE`, `NEXT`, `PREVIOUS`, `SEEK`).
  - No package spoofing, undocumented vivo/OriginOS private APIs, or security bypasses are used.
  - **Verified via ADB on vivo X200s (`V2458A`, Android 17, OriginOS 7)**:
    - `HiFiPlaybackService` runs as a genuine foreground service (`isForeground=true`, `foregroundId=1001`, `types=0x00000002` / `mediaPlayback`, `category=transport`, `vis=PUBLIC`, `actions=3`).
    - Active `MediaSession` (`com.example.mdownload/androidx.media3.session.id.hifideck_audio_session`) registers as the active `Media button session` backed by `androidx.media3.session.MediaButtonReceiver`, reporting title, artist, album, live position, and queue (`135` local tracks).
    - Continuous background playback after `KEYCODE_HOME` verified (`state=PLAYING(3)`, position advancing continuously with **zero** OriginOS `AudioHardening` background mutes, whereas the previous non-Media3 player triggered `AudioHardening background playback would be muted`).
    - Hardware/media button events (`KEYCODE_MEDIA_NEXT`, `KEYCODE_MEDIA_PAUSE`) work while the app is backgrounded, and tapping the notification deep link (`EXTRA_OPEN_NOW_PLAYING` with `FLAG_ACTIVITY_SINGLE_TOP`) opens directly into `NowPlayingScreen`.
    - Live audio codec inspection verified across real local MP3 (`44.1 kHz · Stereo`) and Opus (`48 kHz · Stereo`) files via ExoPlayer `Tracks`, with `AudioFocus` (`USAGE_MEDIA / CONTENT_TYPE_MUSIC`, `GAIN`) properly coordinating focus with competing media apps.
  - **Known Vendor Limitation**: OriginOS 7's proprietary **OriginPlayer** / Dynamic Island-style capsule maintains a vendor allowlist/heuristic for third-party apps; standard Android `MediaSession` guarantees notification shade, lock-screen controls, and Bluetooth AVRCP controls (and is automatically observed by third-party media observers like Origin Ghost Player when installed), while native OriginPlayer capsule visibility remains governed by OriginOS firmware policy.
  - **Background Survival**: Settings exposes a shortcut to Android/OriginOS battery optimization settings (`Unrestricted battery`) so users can exempt HiFiDeck from aggressive OEM background process killing.

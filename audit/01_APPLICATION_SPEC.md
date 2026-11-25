# Application Specification & Repository Architecture Map

## 1. Repository & Build Configuration

| Property | Value (Verified from Source) | Source File |
|---|---|---|
| **Root Project Name** | `MDownload` (UI branding: `HiFiDeck`) | `settings.gradle.kts` |
| **Gradle Modules** | Single module `:app` (`com.android.application`) | `settings.gradle.kts`, `app/build.gradle.kts` |
| **Application ID / Namespace** | `com.example.mdownload` | `app/build.gradle.kts` |
| **Version** | `versionCode = 2`, `versionName = "1.1"` *(Note: `SettingsScreen.kt:790` displays `"HiFiDeck 1.2 · Obsidian Orchid"` — version string drift)* | `app/build.gradle.kts`, `SettingsScreen.kt` |
| **SDK Targets** | `compileSdk = 37`, `targetSdk = 37`, **`minSdk = 35`** | `app/build.gradle.kts` |
| **Build Variants** | `debug`, `release` (no product flavors) | `app/build.gradle.kts` |
| **AGP / Kotlin** | AGP `9.5.2`, Kotlin `2.2.10` (`jvmTarget = "11"`, Java 11) | `gradle/libs.versions.toml`, `app/build.gradle.kts` |
| **Compose BOM** | `androidx.compose:compose-bom:2026.02.01` | `gradle/libs.versions.toml` |
| **Media3** | `1.9.2` (`exoplayer`, `session`, `ui`, `common`) | `gradle/libs.versions.toml` |
| **Release Optimization** | AGP 9 `optimization { enable = true; packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**") }`; `keepRules/rules.keep` is empty template | `app/build.gradle.kts`, `app/src/main/keepRules/rules.keep` |
| **Native Packaging** | `ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")`, `jniLibs.useLegacyPackaging = true`, `android:extractNativeLibs="true"` | `app/build.gradle.kts`, `AndroidManifest.xml` |
| **Measured Debug APK Size** | **264.53 MB** (`186.72 MB` compressed native `.so` across 4 ABIs; `138.83 MB` non-arm64-v8a) | Built `app/build/outputs/apk/debug/app-debug.apk` |

### Third-Party Dependencies (`gradle/libs.versions.toml` & `app/build.gradle.kts`)
- **AndroidX Core/UI:** `core-ktx:1.18.0`, `appcompat:1.7.1`, `lifecycle-runtime-ktx:2.10.0`, `lifecycle-viewmodel-compose:2.10.0`, `lifecycle-process:2.10.0`, `activity-compose:1.13.0`, `documentfile:1.1.0`, `material3`, `material-icons-extended`.
- **Media Playback:** `androidx.media3:media3-{exoplayer,session,ui,common}:1.9.2`.
- **Image Loading:** `io.coil-kt.coil3:coil-compose:3.2.0`, `io.coil-kt.coil3:coil-network-okhttp:3.2.0`.
- **Downloader / Native Tooling:** `io.github.junkfood02.youtubedl-android:library:0.18.1` and `ffmpeg:0.18.1` (bundles Python, QuickJS, yt-dlp, FFmpeg, FFprobe via `libffmpeg.so`/`libffprobe.so`/`libpython*.so`).
- **Concurrency:** `org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2`.
- **Testing:** `junit:4.13.2`, `androidx.test.ext:junit:1.3.0`, `espresso-core:3.7.0`, `compose-ui-test-junit4`.

---

## 2. Source Package Map (Production vs. Test vs. Dead Code)

Total Kotlin source files: **104 production files** under `app/src/main/java/com/example/mdownload/`, **9 JVM test files** under `app/src/test/`, **4 instrumented test files** under `app/src/androidTest/`.

| Package | Files | Responsibilities | Notable Dead / Legacy Files |
|---|---|---|---|
| Root (`com.example.mdownload`) | `MDownloadApp.kt` (193), `MainActivity.kt` (146), `MainViewModel.kt` (2,082), `ShareDownloadActivity.kt` (718) | Application DI container, main UI entrypoint, God ViewModel, Share-intent instant/sheet downloader | None |
| `core/` | 9 files (`Models.kt`, `SettingsStore.kt`, `YouTubeUrlParser.kt`, `TextUtils.kt`, `AudioTagFingerprint.kt`, `DownloadPolicy.kt`, `LibraryQueryHelper.kt`, `QueueOrderHelper.kt`, `TaskFeedback.kt`) | Domain models, JSON settings store, URL parsing, title cleanup, fingerprinting, query/queue helpers | `TextUtils.archiveFileName()` unused in production |
| `core/audio/` | 6 files (`AudioQualityModels.kt`, `ReplayGainCalculator.kt`, `ReplayGainMetadataReader.kt`, `AudioInspectorEngine.kt`, `LoudnessScanner.kt`, `AudioAnalysisRepository.kt`) | ReplayGain math & tag reader, FFprobe + pure-Kotlin audio inspector, EBU R128 loudness scanner, versioned JSON cache | None |
| `core/metadata/` | 4 files (`AudioMetadataModels.kt`, `MetadataValidator.kt`, `MetadataTagIO.kt`, `MetadataRepository.kt`) | Single/batch tag editing models, validation, MP3/FLAC pure-Kotlin writers, MP4/Ogg FFmpeg remux, MediaStore/SAF commit | `MetadataTagIO.readMp4BasicMetadata()` is a no-op stub |
| `engine/` | 8 files (`YtDlpEngine.kt`, `DownloadForegroundService.kt`, `DownloadQueueManager.kt`, `MediaStoreExporter.kt`, `CoverArtHelper.kt`, `StorageTools.kt`, `YouTubeSearchClient.kt`, `AudioPlayerManager.kt`) | Embedded yt-dlp/Python init & download execution, parallel download queue, FGS with WakeLock/WiFiLock, MediaStore/SAF export, YouTube Innertube search | **`AudioPlayerManager.kt`** (13-line `LegacyAudioPlayerMarker` stub); `archiveDir` unused |
| `player/playback/` | 9 files (`HiFiPlaybackService.kt`, `PlaybackController.kt`, `PlaybackGainCoordinator.kt`, `ReplayGainAudioProcessor.kt`, `SleepTimerController.kt`, `ListeningSessionTracker.kt`, `MediaItemMapper.kt`, `PlaybackModels.kt`, `PlaybackStateStore.kt`) | Media3 `MediaSessionService`, `MediaController` facade, custom PCM `AudioProcessor`, gain authority, sleep timer, listening tracker, queue persistence | None |
| `player/library/` | 8 files (`MusicLibraryRepository.kt`, `ArtworkCache.kt`, `PlaylistStore.kt`, `ArtistStore.kt`, `LibraryGroupingHelper.kt`, `ListeningHistoryRepository.kt`, `SmartPlaylistModels.kt`, `SmartPlaylistEvaluator.kt`, `SmartPlaylistRepository.kt`) | MediaStore + SAF library scan, LRU/disk artwork cache, manual playlists, custom artist overrides/aliases, listening history, smart playlists | None |
| `player/lyrics/` | 7 files (`LyricsMatcher.kt`, `LyricsParser.kt`, `LyricsProvider.kt`, `LyricsRepository.kt`, `LyricsStorage.kt`, `LyricsSyncEngine.kt`, `LyricsTagIO.kt`) | LRC parsing/sync, LRCLIB HTTP provider & candidate matching, file cache, MP3/FLAC/MP4/Ogg lyrics tag I/O | Heavy duplication with `MetadataTagIO.kt` |
| `player/ui/` | 25 files (`NowPlayingScreen.kt`, `Orbital*.kt` [6], `Circular*.kt` [4], `LibraryHubScreen.kt`, `Lyrics*.kt` [2], `AudioInspectorSheet.kt`, `SleepTimerSheet.kt`, `TrackMetadataEditorSheet.kt`, `SmartPlaylistDialogs.kt`, `ReplayGainSettingsSection.kt`, `MiniPlayer.kt`, `PlaybackQueueSheet.kt`, `PlayerControls.kt`, `AddToPlaylistDialog.kt`, `ArtistManagementDialogs.kt`, `TrackArtworkBox.kt`) | Orbital player, circular seek ring, synchronized lyrics UI, 2,213-line Library Hub, audio/metadata/sleep/smart-playlist sheets & dialogs | **`CircularPlaybackProgress.kt`** (216 lines, 0 code usages) |
| `ui/` & subpackages | `MDownloadRoot.kt`, `LocalizedText.kt`, `ui/screens/*` (5), `ui/components/*` (9), `ui/design/*` (6), `ui/theme/*` (3) | Root shell, EN/VI localization, Home/Discover/Downloads/Settings screens, Obsidian/Pearl Orchid design system | **`ui/screens/LibraryScreen.kt`** (85 lines, 0 usages); `ui/theme/Color.kt` & `Type.kt` unused legacy templates |

### Documentation & Validation Artifacts in Repo
- `FEATURE_EXPANSION_DESIGN.md`, `AUDIO_PIPELINE.md`, `FEATURE_VALIDATION_REPORT.md`, `ORBITAL_PLAYER_DESIGN.md`, `CIRCULAR_SCRUBBING_DESIGN.md`, `LYRICS_DESIGN.md`, `README.md`.
- `validation/20261123/`: `RESULT.txt`, `device-download.log`, `device-playback.log`, `orbital_normal_mode.png`, `orbital_expanded_mode.png`.

---

## 3. Application Entry Points, Services, Receivers & Persistence

### AndroidManifest Components (`app/src/main/AndroidManifest.xml`)
1. **`MDownloadApp`** (`android:name=".MDownloadApp"`): Manual singleton service locator initializing 15+ repositories/controllers in `onCreate()`.
2. **`MainActivity`** (`exported="true"`, `launchMode="singleTop"`, `MAIN`/`LAUNCHER`): Edge-to-edge Compose host (`enableEdgeToEdge()`), runtime permission requests (`POST_NOTIFICATIONS`, `READ_MEDIA_AUDIO`), `ProcessLifecycleOwner` visibility tracking.
3. **`ShareDownloadActivity`** (`exported="true"`, `ACTION_SEND` `text/plain`, translucent modal theme): Accepts shared links from YouTube/YouTube Music/SoundCloud/TikTok; supports instant background download or quick options sheet.
4. **`DownloadForegroundService`** (`exported="false"`, `foregroundServiceType="dataSync"`): Runs foreground notification + `PARTIAL_WAKE_LOCK` + `WifiLock` during active download queue.
5. **`HiFiPlaybackService`** (`exported="true"`, `foregroundServiceType="mediaPlayback"`, `androidx.media3.session.MediaSessionService`): Owns `ExoPlayer` with `ReplayGainAudioProcessor`, `MediaSession`, audio focus, and playback notification.
6. **`androidx.media3.session.MediaButtonReceiver`** (`exported="true"`, `ACTION_MEDIA_BUTTON`): Hardware headset & Bluetooth AVRCP media button dispatch.
7. **Workers / ContentProviders:** None (`WorkManager` is not used; no custom `ContentProvider`).

### Persistence Architecture (All Ad-Hoc JSON Files — No SQLite/Room)
Every persistent store resides under `context.filesDir/hifideck_data/` (plus `hifideck_lyrics/`, `hifideck_work/`, `hifideck_covers/`, `hifideck_artist_covers/`):
- `settings.json` (`SettingsStore`: `AppSettings`, download queue jobs, download history)
- `settings.log` (`SettingsStore`: diagnostic log lines containing URLs/titles/paths)
- `playback_state.json` (`PlaybackStateStore`: up to 500 queued tracks, index, position, shuffle/repeat/speed)
- `playlists.json` (`PlaylistStore`: manual user playlists)
- `artists.json` (`ArtistStore`: custom artist profiles, covers, track overrides, name aliases)
- `listening_history.json` (`ListeningHistoryRepository`: per-track play counts, total listened ms, timestamps)
- `smart_playlists.json` (`SmartPlaylistRepository`: user-defined smart playlist rules)
- `audio_analysis_cache.json` (`AudioAnalysisRepository`: version-keyed FFprobe/ReplayGain/R128 scan cache)
- `sleep_timer.json` (`SleepTimerController`: active deadline & fade-out seconds)

---

## 4. Android Compatibility & OriginOS 7 Analysis

### Standard Android Behavior (`compileSdk = 37`, `targetSdk = 37`, `minSdk = 35`)
- **Foreground Services:** Correctly declares `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` with matching manifest `foregroundServiceType` attributes. Note: On Android 15+ (`API 35+`), `dataSync` foreground services are subject to the **6-hour per-24h quota** (`Service.onTimeout(int, int)`). `DownloadForegroundService` does **NOT** override `onTimeout()` — a long playlist download hitting the 6-hour `dataSync` quota will receive a `RemoteServiceException` crash unless `onTimeout()` cleanly stops the service.
- **Scoped Storage & MediaStore Write Permissions:** Uses `MediaStore.createWriteRequest()` via `ActivityResultContracts.StartIntentSenderForResult` in `MDownloadRoot.kt:100-115` for non-app-owned tracks during metadata editing. However:
  - `MediaStoreExporter.deleteTrack()` does NOT use `MediaStore.createDeleteRequest()`, so deleting non-app-owned audio files fails silently/returns false on Android 11+.
  - `LyricsTagIO.embedLyricsIntoUri()` does NOT trigger `MediaStore.createWriteRequest()`, so embedding lyrics into non-app-owned MediaStore tracks fails with `SecurityException`.
- **Edge-to-Edge & Insets:** `MainActivity.onCreate()` calls `enableEdgeToEdge()`, and `NowPlayingScreen`/`UnifiedBottomDock`/bottom sheets apply `statusBarsPadding()` and `navigationBarsPadding()`. Verified on vivo X200s live UI XML (`1260×2800`): bottom dock sits cleanly at `y = 2494..2704` above the 96 px gesture navigation inset (`2704..2800`).
- **Device Rotation:** `MainActivity` does not lock orientation (`configChanges` unspecified); Compose `rememberSaveable` preserves `showLyrics` and tab state via `MainViewModel`, though landscape geometry on `NowPlayingScreen` (`OrbitalGeometry.focalCenterX = 0.655 * w`, `discDiameter = min(w*0.8, (h-36)*0.92)`) becomes cramped vertically when `maxHeight < 420dp` in landscape.
- **Audio Focus & Bluetooth:** `ExoPlayer` configured with `handleAudioFocus = true`, `USAGE_MEDIA`, `AUDIO_CONTENT_TYPE_MUSIC`, `setHandleAudioBecomingNoisy(true)`, and `MediaButtonReceiver` registered (verified via `manifest_exposesMediaSessionServiceAndMediaButtonReceiver`).

### Practical Consequences of `minSdk = 35` (Android 15+ Only)
`app/build.gradle.kts:20` sets `minSdk = 35`.
- **Is it intentional?** Likely tailored strictly for the developer's own vivo X200s (Android 15/16/17) to avoid legacy `< API 33` `READ_EXTERNAL_STORAGE` branching and `MediaStore` pre-API-30 legacy paths, and to simplify Android 15 edge-to-edge/FGS handling.
- **Practical consequences if retained:**
  1. **Addressable Market Loss:** Excludes **~75–85% of active global Android devices** still running Android 10–14 (API 29–34).
  2. **API Redundancy:** Codebase actually contains zero Android 15-only hard API requirements (all used APIs — `READ_MEDIA_AUDIO` [33], `POST_NOTIFICATIONS` [33], `MediaStore.createWriteRequest` [30], `PickVisualMedia` [19+], Media3 [21+], `FOREGROUND_SERVICE_MEDIA_PLAYBACK` [34] — work down to **API 33/34** cleanly, or API 29+ with minor permission branching).
  3. **Wasted ABI Footprint:** Keeping `armeabi-v7a`, `x86`, and `x86_64` in `ndk.abiFilters` makes **zero sense** with `minSdk = 35` since Android 15 flagship hardware (including the vivo X200s: `ro.product.cpu.abilist = arm64-v8a`) is predominantly 64-bit-only `arm64-v8a`.

### OriginOS 7 (vivo X200s, `V2458A`, Android 17 / API 37) Compatibility
**Verified vivo/OriginOS-specific observations (empirical on `10AF8A0YUH005HA`):**
1. **Background Battery Restriction Default:** `adb shell dumpsys deviceidle whitelist | grep mdownload` returned **`NOT_WHITELISTED`**. OriginOS 7 aggressively throttles/freezes non-whitelisted background processes when the screen turns off or background services idle.
2. **Mitigation Implemented in App:** `SettingsScreen.kt:604-640` explicitly surfaces OriginOS battery status (`PowerManager.isIgnoringBatteryOptimizations`) with a direct deep link to `ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS` and Vietnamese/English guidance to enable *"High background power usage"*, plus `DownloadForegroundService` acquires both `PARTIAL_WAKE_LOCK` and `WIFI_MODE_FULL_HIGH_PERF` (note: `WIFI_MODE_FULL_HIGH_PERF` is deprecated since API 29 in favor of `WIFI_MODE_FULL_LOW_LATENCY`).
3. **Display Density Scaling:** vivo X200s runs `1260×2800` physical resolution at `525` override density (`3.28125 px/dp`, ~`384×853 dp` logical canvas) — narrow logical width (`384 dp`) requires careful horizontal layout discipline for `SelectedSongLabel` and multi-chip `FlowRow` headers.
4. **Standard Android vs. OriginOS Distinction:**
   - *Standard Android 15–17:* 6-hour `dataSync` FGS timeout, `MediaStore.createWriteRequest` user consent dialog, `POST_NOTIFICATIONS` runtime gate, edge-to-edge system bar enforcement.
   - *OriginOS 7 Specific:* Aggressive background process freezing when not whitelisted in vivo Battery → High background power usage (observed during audit when background instrumentation sessions stalled after idle/screen transitions), and OEM notification grouping/lock-screen media card styling driven by `MediaSession` metadata (making the stale-metadata-after-edit bug in `PlaybackController.refreshTrackMetadataFromLibrary()` immediately visible on OriginOS lock screen / Origin Island!).

# AuthDio

**AuthDio** is an offline-first Android Hi-Fi audio downloader, library manager, and Media3 music player.

> **Note:** AuthDio was formerly known as **HiFiDeck / MDownload**. For backward compatibility with existing installations, app-private data, playlists, playback history, and MediaStore/SAF storage permissions, the Android package identifier remains `com.example.mdownload` and internal persistence directories (such as `filesDir/hifideck_data/` and `Music/MDownload`) are intentionally preserved.

## Highlights

- **Offline-First Media3 Player:** Single `MediaSessionService` (`HiFiPlaybackService`) with ReplayGain / EBU R128 loudness normalization, Sleep Timer with smooth DSP fade-out, Audio Quality Inspector, Orbital Navigation, Circular Drag Scrubbing, and Synchronized Lyrics.
- **Built-in Audio Downloader:** `yt-dlp` + FFmpeg + QuickJS pipeline with foreground download queue, YouTube search, Share Sheet receiver (`Share → AuthDio`), cover-art embedding, and duplicate fingerprinting.
- **Non-Destructive Metadata & Library:** Atomic crash-recoverable `SafeAudioWritePipeline` for single and batch ID3v2 / FLAC / MP4 / Ogg-Opus metadata editing, Smart Playlists, Artist management, and bilingual English/Vietnamese UI.

## Developer Setup & Build

- **Android Studio / Gradle:** Gradle `9.6.0`, AGP `9.5.2`, Kotlin `2.2.10`, JDK 25 launcher / Java 11 bytecode target
- **Android SDK:** `compileSdk = 37`, `minSdk = 35`, `targetSdk = 37`
- **Application ID:** `com.example.mdownload` (`versionCode = 2`, `versionName = 1.1`)

```powershell
# Build 64-bit ARM debug APK (recommended for Android 15+ arm64-v8a devices)
.\gradlew.bat :app:assembleArm64Debug

# Build universal multi-ABI debug APK (emulators / cross-ABI testing)
.\gradlew.bat :app:assembleUniversalDebug

# Build universal release App Bundle (AAB)
.\gradlew.bat :app:bundleUniversalRelease

# Run JVM unit tests (117 tests)
.\gradlew.bat :app:testArm64DebugUnitTest

# Install in-place without clearing existing app data
adb install -r app\build\outputs\apk\arm64\debug\app-arm64-debug.apk
```

## Documentation

- [Development & Stabilization History (P0, P1, STAB-05)](docs/DEVELOPMENT_HISTORY.md)
- [Audio Pipeline, DSP & Crash-Safe Metadata Architecture](docs/AUDIO_PIPELINE.md)
- [Native Music Player Design](docs/PLAYER_DESIGN.md)
- [Advanced Audio & Library Feature Design](docs/FEATURE_EXPANSION_DESIGN.md)
- [UI Design System (Obsidian Orchid)](docs/UI_DESIGN_SYSTEM.md)
- [Orbital Player & Gesture Navigation](docs/ORBITAL_PLAYER_DESIGN.md)
- [Circular Drag Scrubbing Design](docs/CIRCULAR_SCRUBBING_DESIGN.md)
- [Synchronized Lyrics Architecture](docs/LYRICS_DESIGN.md)

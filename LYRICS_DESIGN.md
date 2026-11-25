# HiFiDeck — Synchronized Lyrics System Design

## 1. Architecture Overview
HiFiDeck's lyrics system is offline-first, UTF-8 / Vietnamese-safe, non-blocking, and strictly driven by AndroidX Media3's single active playback timeline (`PlaybackController.state.positionMs`). No second `ExoPlayer` instance or independent drifting lyric timer is used.

```
[Active LibraryTrack / ExportedTrackNotification]
        │
        ▼
  LyricsRepository (StateFlow<LyricsUiState>)
   ├── 1. Embedded Audio Tags (LyricsTagIO: ID3v2 USLT/SYLT, FLAC Vorbis, MP4 ©lyr, Ogg/Opus)
   ├── 2. Sidecar .lrc File (LyricsStorage: SAF folder / app sidecar directory)
   ├── 3. Local JSON Lyrics Cache (LyricsStorage: filesDir/hifideck_lyrics/cache/)
   ├── 4. Online Provider (LyricsProvider -> LRCLIB /api/get & /api/search)
   │        └── Ranked & threshold-filtered by LyricsMatcher (normalized title/artist/album/duration)
   └── 5. Manual User Input (Import .lrc/.txt, Paste/Edit, Choose candidate, Offset ±ms, Export .lrc, Safe Embed)
```

## 2. Core Data Models (`player/lyrics/LyricsParser.kt`)
- `LyricLine(timestampMs: Long?, text: String)` — `timestampMs == null` represents plain unsynced lines; non-null represents synchronized millisecond timestamps.
- `LyricsSource`: `EMBEDDED`, `SIDECAR`, `CACHE`, `ONLINE_LRCLIB`, `MANUAL`, `NONE`.
- `TrackLyrics`: `trackKey`, `lines`, `rawLrc`, `plainText`, `isSynced`, `source`, `offsetMs`, `providerName`, `matchedTitle`, `matchedArtist`, `confidence`, `updatedAtMs`.
- `LyricsCandidate`: online search match with title, artist, album, duration, synced/plain content, and computed `confidence: Float` (`0f..1f`).
- `LyricsUiState`: tracks active track, loading state, active `TrackLyrics?`, alternative `candidates`, online search state, error/status notices, and capability flags (`canEmbedIntoFile`).

## 3. LRC Parsing & Synchronization (`LyricsParser.kt` & `LyricsSyncEngine.kt`)
- **Supported LRC constructs:**
  - Standard `[mm:ss.xx]`, `[mm:ss.xxx]`, `[mm:ss:xx]`, `[mm:ss]`
  - Multiple timestamps per line: `[00:12.00][00:45.50]Chorus` expands cleanly into sorted entries
  - Header tags `[offset:±ms]`, `[ti:]`, `[ar:]`, `[al:]` (metadata tags stripped from visible lines; `[offset:]` applied)
  - Malformed timestamps safely ignored rather than crashing
  - Full UTF-8 & Vietnamese diacritics preserved
  - Duplicate timestamps sorted stably in source order
  - Plain text fallback when no valid timestamps exist
- **Synchronization (`LyricsSyncEngine.kt`):**
  - Effective lyric time: `effectiveMs = positionMs + offsetMs`.
  - Binary search + cached index hint finds the active line index in $O(1)$ amortized / $O(\log N)$ worst-case on arbitrary seeks.
  - Handles: before first lyric (`activeIndex = -1`), instrumental gaps (explicit empty timestamp lines or long inter-line breaks), seeking forward/backward, pause/resume, playback speed changes (automatically accurate because Media3 `positionMs` scales with speed).
  - `PlaybackController` polls at `150ms` while actively playing so active-line highlights stay responsive with negligible CPU cost.

## 4. Matching & Online Provider (`LyricsMatcher.kt` & `LyricsProvider.kt`)
- **Provider:** Public **LRCLIB** API (`https://lrclib.net/api/get` and `https://lrclib.net/api/search`) with descriptive `User-Agent: HiFiDeck/1.2 (Android)`, strict connect/read timeouts, in-flight request deduplication, short miss cooldown, and zero audio uploading.
- **Metadata Normalization:**
  - Strips YouTube/release suffixes (`[Official MV]`, `(Lyrics Video)`, `(Audio)`, `(4K)`, `(Visualizer)`, etc.), normalizes Unicode NFC and whitespace, and scores both exact diacritics and folded ASCII forms so Vietnamese titles match reliably even when accented differently.
  - Combines title similarity (50%), artist similarity (25%), duration proximity within `±4s..±12s` (15%), and album bonus (10%), plus a slight preference for synchronized over plain lyrics when scores tie.
- **Confidence Gating:**
  - `HIGH_CONFIDENCE_THRESHOLD = 0.72f` — auto-attached when discovered automatically.
  - `PLAUSIBLE_THRESHOLD = 0.42f` — surfaced as selectable alternatives in the Lyrics Match Picker without auto-attaching wrong lyrics.

## 5. Format-Aware Safe Audio Tag I/O (`LyricsTagIO.kt`)
| Format | Read Support | Safe Write Support | Preservation Guarantee |
| --- | --- | --- | --- |
| **MP3** | ID3v2.3 / ID3v2.4 `USLT` & `SYLT` (UTF-8 / UTF-16 / ISO-8859-1) | Pure Kotlin ID3v2 frame rewriter writing `USLT` + `SYLT` (when synced) | Copies all non-lyric ID3 frames (`TIT2`, `TPE1`, `TALB`, `APIC`, `COMM`) verbatim and copies MP3 audio payload bytes bit-for-bit untouched |
| **FLAC** | `VORBIS_COMMENT` block (`LYRICS`, `UNSYNCEDLYRICS`) | Pure Kotlin FLAC metadata block rewriter | Preserves `STREAMINFO`, `PICTURE`, `SEEKTABLE`, other comments, and all FLAC audio frames bit-for-bit untouched |
| **M4A / MP4** | `moov/udta/meta/ilst/©lyr` atom parser | Bundled `libffmpeg.so` lossless remux (`-map 0 -c copy -metadata lyrics=...`) with verification read-back | Audio stream copied untouched (`-c copy`); falls back gracefully if FFmpeg unavailable |
| **Ogg / Opus** | Ogg packet reader for Vorbis Comment / `OpusTags` (`LYRICS`, `UNSYNCEDLYRICS`) | Bundled `libffmpeg.so` lossless remux (`-map 0 -c copy -metadata lyrics=...`) + sidecar `.lrc` export | Audio stream copied untouched; sidecar `.lrc` always available |

- **Safe Write Protocol (Android Scoped Storage / SAF):**
  1. Never writes to an actively downloading file (only post-export or explicit user request).
  2. Verifies target URI is writable before starting.
  3. Copies audio to a private temp file, writes lyrics to a second temp output file, verifies by reading back the embedded lyrics AND confirming non-empty intact audio (audio payload SHA-256 identical on pure-Kotlin MP3/FLAC rewrites, or valid audio stream check on FFmpeg remux).
  4. Replaces target atomically (or streams verified bytes back through `ContentResolver.openOutputStream(uri, "wt")`); on any failure the original audio file remains untouched.

## 6. Downloader Integration & Settings
- New `AppSettings` flags (persisted in `SettingsStore.kt` and exposed in `SettingsScreen.kt`):
  - `autoFetchLyricsAfterDownload: Boolean = true` — triggers a non-blocking background coroutine after a track finishes exporting to search LRCLIB, validate confidence, and store in local lyrics cache (never blocks or fails audio downloads).
  - `embedLyricsAfterDownload: Boolean = false` — conservative opt-in to also safely embed high-confidence fetched lyrics into newly downloaded audio files.

## 7. Lyrics UI & Editor (`player/ui/LyricsLine.kt`, `player/ui/LyricsScreen.kt`, `NowPlayingScreen.kt`)
- Accessible from a clean Lyrics toggle button in `NowPlayingScreen` top chrome (zero conflict with Orbital Navigation or Circular Drag Seeking).
- Dark cinematic styling matching `OrbitalDesignTokens`: luminous active line, dimmed surrounding lines, smooth spring/scroll centering, manual-scroll override with floating **"Jump to current"** pill, tap any synced line to seek, plain lyrics mode, and actionable empty state.
- Built-in **Lyrics Manager / Editor**: Search LRCLIB online, Pick alternative match, Adjust sync offset (`-500ms`, `-100ms`, `+100ms`, `+500ms`, Reset), Import `.lrc`/`.txt` (SAF `OpenDocument`), Edit/Paste raw lyrics, Export `.lrc` (SAF `CreateDocument`), and Embed into audio file when supported.

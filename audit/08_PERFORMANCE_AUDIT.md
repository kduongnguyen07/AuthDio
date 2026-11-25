# HiFiDeck — Performance & Resource Efficiency Audit

**Audit Scope:** Phase 6 — APK size, cold start, memory footprint, UI frame timing & recomposition, artwork decoding, playback/FFmpeg/EBU R128 I/O, Smart Playlist evaluation, JSON persistence, and battery impact.  
**Audit Date:** 2026-11-24

---

## 1. Live Device Measurements (vivo X200s)

### 1.1 Test Environment & Measurement Limitations
- **Device:** vivo X200s (`V2458A`, product `PD2415M`)
- **OS:** OriginOS 7, **Android 17 (API 37)**
- **CPU / ABI:** `arm64-v8a` only (64-bit-only SoC)
- **Display:** `1260×2800` physical (`560` physical / `525` override dpi, `~384×853 dp`)
- **Library Size:** **153 MediaStore audio tracks** present on device
- **Battery Optimization Status:** `dumpsys deviceidle whitelist` → **`NOT_WHITELISTED`**
- **Build Variant Measured:** **`app-debug.apk`** (unminified debug build — release R8/optimization numbers were not measured; debug JIT/Compose tooling overhead inflates cold start and memory relative to a release build).
- **Measurement Caveats:** No Perfetto system trace or live CPU profiler trace was captured during continuous Orbital drags or concurrent yt-dlp + playback + EBU R128 scans. All numbers below are direct `adb` / `dumpsys` measurements taken on 2026-11-24; code-level bottlenecks are reported separately from measured runtime data.

---

### 1.2 Measured Runtime Metrics

| Metric | Measurement Method | Measured Value | Assessment |
|---|---|---|---|
| **Debug APK Size** | Inspect `app/build/outputs/apk/debug/app-debug.apk` | **264.53 MB** | **CRITICAL BLOAT** (`138.83 MB` wasted on non-arm64 ABIs) |
| **USB Install Time** | `adb install -r -t` (debug + androidTest APKs) | **39.17 s** | Slow developer iteration & heavy user storage cost |
| **Cold Start Latency (3 Trials)** | `am force-stop com.example.mdownload` + `am start -W -n com.example.mdownload/.MainActivity` | Run 1: `779 ms` (Wait `781 ms`)<br>Run 2: `711 ms` (Wait `713 ms`)<br>Run 3: `740 ms` (Wait `741 ms`)<br>**Median: `740 ms`** (Mean: `743.3 ms`) | **Elevated** for a flagship SoC; driven by 5 sync JSON disk reads in `MDownloadApp.onCreate()` + debug overhead |
| **Idle Memory (3s post-launch, Home)** | `dumpsys meminfo com.example.mdownload` | **TOTAL PSS: `192,588 KB` (~188.1 MB)**<br>**TOTAL RSS: `341,508 KB` (~333.5 MB)**<br>Swap PSS: `4,543 KB`<br>• Code: `76,176 KB` PSS (`219,628 KB` RSS)<br>• Graphics: `34,704 KB`<br>• Native Heap: `30,126 KB`<br>• Dalvik Heap: `10,693 KB` | **High idle footprint**; `Code` + `Native Heap` dominated by eager `youtubedl-android` / Compose debug symbols |
| **Initial Launch Frame Timing** | `dumpsys gfxinfo com.example.mdownload` | `13` total frames, **`4` janky frames (`30.77%`)**<br>`50th`: `16 ms`<br>`90th / 95th / 99th`: **`250 ms`** | Noticeable startup stutter frame (`250 ms`) during initial composition & repository hydration |

---

### 1.3 APK Native Library ABI Breakdown (`app-debug.apk`)

Measured directly from the built `app-debug.apk` zip entries (`lib/**/*.so` = **`186.72 MB` compressed / `190.96 MB` uncompressed**):

| ABI | `.so` Files | Uncompressed Size | Compressed Size | Needed on vivo X200s / Android 15+? |
|---|---:|---:|---:|---|
| **`arm64-v8a`** | 7 | `49.03 MB` | `47.89 MB` | **YES** (vivo X200s & virtually all physical Android 15+ phones) |
| `x86_64` | 7 | `51.94 MB` | `50.75 MB` | Emulator only (x86_64 host) |
| `armeabi-v7a` | 7 | `42.29 MB` | `41.52 MB` | **Rare/Deprecated** (Android 15+ mandates 64-bit / ARMv9 era devices) |
| `x86` | 7 | `47.70 MB` | `46.56 MB` | **Obsolete** (32-bit x86 emulator) |
| **Wasted Non-`arm64-v8a` Total** | **21** | **`141.93 MB`** | **`138.83 MB`** | **52.5% of the entire 264.53 MB APK** |

**Root Cause:** `app/build.gradle.kts` hardcodes `abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")` alongside `packaging.jniLibs.useLegacyPackaging = true` (which forces Android to also extract `.so` files onto `/data/app/.../lib/` on install!). Enabling `splits { abi { ... } }` or AAB / `arm64-v8a` product flavor immediately drops the universal APK by **`~139 MB`** (`>50%`).

---

## 2. Code-Verified Runtime Performance Bottlenecks

### 2.1 [P1 / PERF-01] Synchronous Main-Thread Disk I/O During `Application.onCreate()`
- **File:** `app/src/main/java/com/example/mdownload/MDownloadApp.kt`
- **Mechanism:** `MDownloadApp.onCreate()` eagerly constructs **5 repositories** whose `init` blocks synchronously read and parse JSON files from `filesDir` on the **Main Thread** before `MainActivity.onCreate()` even begins:
  1. `SettingsRepository` (`settings.json`)
  2. `PlaylistRepository` (`playlists.json`)
  3. `ArtistRepository` (`artists.json`)
  4. `ListeningHistoryRepository` (`listening_history.json`)
  5. `SmartPlaylistRepository` (`smart_playlists.json`)
  *(Plus `AudioAnalysisRepository` & `SleepTimerController` state loads on first access/init.)*
- **Impact:** Directly inflates cold start (`740 ms` median on flagship vivo X200s) and causes the observed `250 ms` 90th–99th percentile initial frame jank. Will worsen linearly as `listening_history.json` and `audio_analysis_cache.json` grow.

---

### 2.2 [P1 / PERF-02] Root-Level 250 ms Recomposition Cascade & Per-Frame Gesture Recompositions
- **Files:** `MDownloadRoot.kt`, `NowPlayingScreen.kt`, `OrbitalNavigator.kt`
- **Mechanism:**
  1. **`MDownloadRoot` collects `playbackState` at the app root:** Because `PlaybackController` updates `positionMs` every ~250 ms while playing, collecting `playbackState` at `MDownloadRoot` forces top-level recomposition scopes to re-evaluate **4 times per second** even when the user is browsing Settings, Home, or Library!
  2. **`NowPlayingScreen` & `OrbitalNavigator` read `Animatable.value` during composition:** Reading `orbitProgress.value`, `holdProgress`, and `animatedFocusIndex.value` in composition (instead of `graphicsLayer { ... }` / `Modifier.drawWithContent`) forces full subtree recomposition at 60–120 Hz during Orbital drag animations.
  3. **Coroutine per pointer move:** `OrbitalNavigator` launches a coroutine on every vertical drag pointer event to update `animatedFocusIndex`.

---

### 2.3 [P1 / PERF-03] Smart Playlist O(N log N × M) Main-Thread Evaluation + 10-Second JSON Rewrites
- **Files:** `ListeningSessionTracker.kt`, `ListeningHistoryRepository.kt`, `SmartPlaylistEvaluator.kt`, `MainViewModel.kt`
- **Mechanism:**
  1. During active playback, `ListeningSessionTracker` flushes updated listening time to `ListeningHistoryRepository` **every 10 seconds**, triggering a full atomic JSON serialization + `fsync`/rename rewrite of the entire `listening_history.json` file.
  2. Every emission of `listeningHistory` triggers `MainViewModel`'s `combine(...)` pipeline on the **Main Thread** to re-evaluate **all Smart Playlists** (7 built-in + custom).
  3. Inside `SmartPlaylistEvaluator.resolveStats()`, track stats are looked up via a **linear `O(M)` scan** inside the track loop and sort comparator → **`O(N log N × M)`** per Smart Playlist!
- **Scaling Impact:** Barely noticeable at 153 tracks (`~153 × 153`), but at **2,000–5,000 tracks** this causes multi-hundred-millisecond Main-Thread UI freezes **every 10 seconds during music playback**.

---

### 2.4 [P2 / PERF-04] Excessive Periodic Disk Writes & Unthrottled Notifications
- **Files:** `HiFiPlaybackService.kt`, `DownloadForegroundService.kt`, `AudioAnalysisRepository.kt`
- **Findings:**
  - **`HiFiPlaybackService` 5-second queue snapshot:** Rewrites `playback_state.json` containing up to **500 full track JSON objects** every 5 seconds during playback (720 full disk rewrites/hour of listening!). Should save queue only on queue mutation and persist lightweight `(currentIndex, positionMs)` independently.
  - **`DownloadForegroundService` unthrottled `notify()`:** Emits notification updates directly from yt-dlp progress callbacks without a minimum interval throttle (e.g., 500 ms). Android 15–17 rate-limits rapid `NotificationManager.notify()` bursts (>5–10/sec) and drops progress updates or burns SystemUI CPU.
  - **`AudioAnalysisRepository` unbounded cache:** No eviction limit; entire JSON file re-serialized on every new track inspection.

---

### 2.5 [P2 / PERF-05] Heavy `content://` Full-File Copies & Main-Thread Artwork Decoding
- **Files:** `AudioInspectorEngine.kt`, `ReplayGainMetadataReader.kt`, `TrackMetadataEditorSheet.kt`
- **Findings:**
  - **Audio Inspector full-file staging:** `AudioInspectorEngine.inspectTrack()` copies the entire `content://` file (often 30–100 MB for FLAC) into `cacheDir` on every uncached inspection.
  - **ReplayGain uncached staging:** Because `AudioAnalysisRepository.resolveReplayGain()` doesn't cache tag-only results, every track switch copies 2–32 MiB to `cacheDir` to re-read ReplayGain tags.
  - **Unity-gain PCM copy (`ReplayGainAudioProcessor`):** Copies every PCM sample buffer through `queueInput()` even when ReplayGain is OFF and `sleepFadeFactor == 1.0f` (preventing zero-overhead bypass and blocking potential audio offload).
  - **Editor Main-Thread bitmap decode:** `TrackMetadataEditorSheet` decodes embedded cover art synchronously on the Main Thread via `BitmapFactory.decodeByteArray`.

---

## 3. Battery & OriginOS 7 Impact Summary

| Factor | Current Behavior | Impact on vivo X200s / OriginOS 7 |
|---|---|---|
| **Background Whitelist** | `NOT_WHITELISTED` in `deviceidle` | OriginOS 7 aggressively freezes background apps not holding a properly typed foreground service; Media3 `MediaSessionService` (`mediaPlayback`) and `DownloadForegroundService` (`dataSync`) are correctly typed, which is the right defense. |
| **Frequent Disk I/O** | 5s `playback_state.json` + 10s `listening_history.json` rewrites | Prevents flash storage sleep during screen-off playback; unnecessary wake/write amplification. |
| **Always-Active DSP Copy** | `ReplayGainAudioProcessor` always active even at `1.0f` | Keeps CPU decoding/copying active instead of allowing bypass or hardware offload when ReplayGain is OFF. |
| **Concurrent Downloads + Playback** | Up to 6 parallel `yt-dlp` Python + `FFmpeg` processes × 8 threads | High peak CPU/thermal load and RAM pressure; default gentle/clamped settings help, but `LoudnessScanner` lacks timeout/cancellation guardrails. |

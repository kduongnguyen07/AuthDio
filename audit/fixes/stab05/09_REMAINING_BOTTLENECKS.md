# HiFiDeck Android — Post-STAB-05 Remaining Bottlenecks & Next Optimization Opportunities

**Date:** 2026-11-24  
**Scope:** Honest technical inventory of remaining performance, binary-size, startup, and rendering bottlenecks observed after completing STAB-05. Items listed below were intentionally deferred because they are out of scope for STAB-05 or require larger architectural changes.

---

## 1. Debug DEX Size (`74.00 MB`, 58.71% of `arm64-debug` APK)

- **Observation:** After removing non-`arm64-v8a` native libraries (-138.73 MB), `classes*.dex` (`77.59 MB` across 14 DEX files) is now the single largest component of `app-arm64-debug.apk` (`126.04 MB`).
- **Primary Driver:** `androidx.compose.material:material-icons-extended` bundles thousands of unused vector icon classes in unminified debug builds.
- **Recommended Next Step (Post-STAB-05):** Replace `material-icons-extended` with `material-icons-core` plus the ~25–35 specific vector drawables actually used by HiFiDeck (or enable selective icon import), and verify release R8 shrinking (`isMinifyEnabled = true`, `isShrinkResources = true`) with tested keep rules for Media3 and `youtubedl-android`. Expected debug APK savings: **~35–45 MB** (bringing `arm64-debug` under ~85 MB) and significantly faster incremental DEX merging and cold ART verification.

---

## 2. Eager `ContextImpl.getFilesDir()` Calls in `MDownloadApp.onCreate()` (`12 ms`)

- **Observation:** Debug `StrictMode` confirmed **0** app JSON/log disk reads and **0** disk writes on the Main Thread, but logged `StrictModeDiskReadViolation` inside Android's `ContextImpl.getFilesDir()` (`File.exists()` in `getDataDir()`) across 9 store/repository constructors spanning `12 ms` (`21:48:46.124`–`21:48:46.136`).
- **Recommended Next Step:** Convert `private val baseDir = File(context.filesDir, ...)` fields in stores/repositories to `private val baseDir: File by lazy { File(appContext.filesDir, ...) }` so `ContextImpl.getFilesDir()` is only resolved on `Dispatchers.IO` during async hydration. Expected impact: **Zero** app-triggered `StrictMode` violations during `MDownloadApp.onCreate()` and ~10–15 ms faster cold launch.

---

## 3. Idle Now Playing Continuous Animation Cost (`96` frames / `22` slow UI frames in 6 s)

- **Observation:** STAB-05 reduced 6 s idle Now Playing frame count by **40.7%** (`162` -> `96`), slow UI frames by **45.0%** (`40` -> `22`), and p90 frame time by **24.1%** (`29 ms` -> `22 ms`). However, `22` frames over 6 seconds still exceeded the 120 Hz / 8.33 ms frame deadline on vivo X200s.
- **Primary Driver:** Continuous ambient glow / rotating vinyl animation on `NowPlayingScreen` combined with Compose invalidation of the circular seek ring every 250 ms (`Slow UI thread: 22`, `Slow issue draw commands: 13`, while GPU stayed fast at `5–7 ms`).
- **Recommended Next Step:**
  - Audit `NowPlayingScreen` continuous `infiniteRepeatable` animations and isolate them onto dedicated `Modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }` / `rotationZ` draw-only layers so zero layout or canvas re-recording happens per tick.
  - Consider honoring `Settings.Global.ANIMATOR_DURATION_SCALE` and offering a low-power/reduced-motion toggle for OLED battery savings during long playback sessions.

---

## 4. Baseline Profiles & Startup Dexopt

- **Observation:** Cold `TotalTime` median dropped from `754.0 ms` to **`700.0 ms`** on debug builds (unoptimized DEX + JIT cold compilation).
- **Recommended Next Step:** Generate an AndroidX **Baseline Profile** (`baseline-prof.txt`) covering Cold Start -> Home -> Library -> Now Playing -> Orbital Navigation, and benchmark `arm64Release` (R8 minified + Baseline Profile) on vivo X200s. Release builds with Baseline Profiles typically cut Compose first-frame cold startup by an additional 25–40%.

---

## 5. JSON Storage Scaling Beyond ~5,000–10,000 Tracks

- **Observation:** STAB-05 preserved the lightweight JSON persistence model (explicitly avoiding a risky Room migration) and reduced steady-state playback write volume by **99.84%** (`121.7 KB` -> `192 B`) and Smart Playlist evaluation at `N = 10,000` from `15.25 s` to **`17.5 ms`**.
- **Future Scaling Threshold:** If a user accumulates >5,000 listening-history entries or >5,000 cached audio inspections, full-file JSON serialization of `listening_history.json` / `audio_analysis_cache.json` (even debounced to 30 s) will grow into multi-hundred-KB writes.
- **Recommended Next Step (Only if library scale warrants):** Consider a dedicated future milestone migrating `ListeningHistoryRepository` and `AudioAnalysisRepository` to Room/SQLite with incremental row upserts, while keeping `PlaybackStateStore` split queue/cursor files as-is.

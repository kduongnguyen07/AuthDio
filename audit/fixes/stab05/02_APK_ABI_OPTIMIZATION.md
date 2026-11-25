# HiFiDeck Android — STAB-05 Workstream A: APK & ABI Packaging Optimization

**Date:** 2026-11-24  
**Target:** vivo X200s (`arm64-v8a`), AGP `9.5.2`, Gradle `:app` module  
**Goal:** Reduce the default `arm64-v8a` debug/development APK from **264.77 MB** to **`<= 135 MB`** while preserving full universal multi-ABI APK and release AAB distribution, and keeping all `youtubedl-android` / FFmpeg / FFprobe / QuickJS / Python native libraries operational.

---

## 1. Root Cause in Pre-STAB-05 Build Configuration

In pre-STAB-05 `app/build.gradle.kts`, `defaultConfig.ndk.abiFilters` unconditionally bundled all four ABIs (`armeabi-v7a`, `arm64-v8a`, `x86`, `x86_64`) into every debug and release APK. Because `youtubedl-android` ships embedded Python and FFmpeg runtime archives per ABI (`libffmpeg.zip.so`, `libpython.zip.so`, `libqjs.so`, `libffmpeg.so`, `libffprobe.so`, `libpython.so`), the three unused ABIs added **145,579,777 bytes (138.84 MB, 52.44%)** of dead payload to every APK installed on `arm64-v8a` phones like the vivo X200s.

---

## 2. Implemented Build Changes

**Modified file:** `app/build.gradle.kts`

1. Introduced `flavorDimensions += "abi"` with two product flavors:
   - **`arm64` (`isDefault = true`)**: sets `ndk { abiFilters += "arm64-v8a" }`. Android Studio and default tasks target `arm64Debug` / `arm64Release` for fast builds and installs on modern 64-bit devices (including vivo X200s).
   - **`universal`**: sets `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }`, preserving full multi-ABI APK distribution (`:app:assembleUniversalDebug`, `:app:assembleUniversalRelease`) and Play Store App Bundle generation (`:app:bundleUniversalRelease`).
2. Preserved `packaging.jniLibs.useLegacyPackaging = true` (explicitly required by `youtubedl-android` so bundled `.zip.so` and executable `.so` binaries extract to `nativeLibraryDir` cleanly on Android 17).
3. Did **not** modify `isMinifyEnabled`, `isShrinkResources`, or ProGuard rules for `debug` builds, and did not strip required codecs or `youtubedl-android` components.

---

## 3. Verified Artifact Size Comparison

| Artifact | Task | Size (Bytes) | Size (MB) | Delta vs Baseline | Target Met? |
| :--- | :--- | :---: | :---: | :---: | :---: |
| Baseline `app-debug.apk` (pre-STAB-05) | `:app:assembleDebug` | 277,629,885 | 264.77 MB | — | — |
| **Post-STAB-05 `app-arm64-debug.apk`** | **`:app:assembleArm64Debug`** | **132,157,292** | **126.04 MB** | **-145,472,593 B (-138.73 MB / -52.40%)** | **YES (`<= 135 MB`)** |
| Post-STAB-05 `app-universal-debug.apk` | `:app:assembleUniversalDebug` | 277,946,840 | 265.07 MB | +316,955 B (+0.30 MB / +0.11%) | Universal preserved |
| Post-STAB-05 `app-universal-release.aab` | `:app:bundleUniversalRelease` | 207,813,991 | 198.19 MB | — | AAB verified |

### `app-arm64-debug.apk` Contents Breakdown

| Category | Entries | Compressed Bytes | Compressed MB | Share |
| :--- | :---: | :---: | :---: | :---: |
| `dex` (`classes*.dex`) | 14 | 77,593,612 | 74.00 MB | 58.71% |
| `lib/arm64-v8a/` | 7 | 50,213,249 | 47.89 MB | 37.99% |
| `res/` | 450 | 3,299,330 | 3.15 MB | 2.50% |
| `other` (`resources.arsc`, `META-INF`, manifest) | 102 | 946,367 | 0.90 MB | 0.72% |
| **Non-`arm64-v8a` ABIs (`armeabi-v7a`, `x86`, `x86_64`)** | **0** | **0** | **0.00 MB** | **0.00%** |

*(Note: `app-universal-debug.apk` was also verified to still contain all four ABIs: `lib/x86_64` 50.75 MB, `lib/arm64-v8a` 47.89 MB, `lib/x86` 46.56 MB, `lib/armeabi-v7a` 41.52 MB.)*

---

## 4. On-Device Native Library & Functional Verification (vivo X200s)

- Installed `app-arm64-debug.apk` onto vivo X200s (`10AF8A0YUH005HA`) via `adb install -r -t`: succeeded (`APP_INSTALL_MS = 22,637 ms`).
- Executed `Stab05PerformanceInstrumentedTest.arm64NativeLibrariesAndToolsInitializeCleanly` on vivo X200s (**PASS**):
  - Verified `libpython.so`, `libffmpeg.so`, `libffprobe.so`, `libqjs.so`, `libpython.zip.so`, and `libffmpeg.zip.so` exist and are executable/accessible in `nativeLibraryDir`.
  - Verified `YtDlpTools.init()` initializes `yt-dlp` cleanly (`yt-dlp ready` badge confirmed on Home screen).
  - Verified `LoudnessScanner` (FFmpeg EBU R128) and FFprobe metadata/quality inspection remain wired to valid `arm64-v8a` binaries.

---

## 5. Build Task Reference & Rollback

### Common Gradle Commands
- Default `arm64` development APK: `.\gradlew.bat :app:assembleArm64Debug`
- Default `arm64` JVM unit tests: `.\gradlew.bat :app:testArm64DebugUnitTest`
- Default `arm64` instrumented test APK: `.\gradlew.bat :app:assembleArm64DebugAndroidTest`
- Multi-ABI universal debug APK: `.\gradlew.bat :app:assembleUniversalDebug`
- Play Store universal release bundle: `.\gradlew.bat :app:bundleUniversalRelease`

### Rollback
Remove `flavorDimensions += "abi"` and the `productFlavors { ... }` block in `app/build.gradle.kts`, and restore `ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64") }` inside `defaultConfig`.

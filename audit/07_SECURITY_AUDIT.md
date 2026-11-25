# HiFiDeck — Security & Privacy Audit

**Audit Scope:** Phase 8 — Subprocess execution (`yt-dlp`, `FFmpeg`, `ffprobe`), URL/SSRF validation, filename sanitization & path traversal, exported Android components & Intent handling, runtime permissions, local storage/backups, logging, external lyrics APIs, and metadata injection.  
**Audit Date:** 2026-11-24

> **Audit Policy:** Non-destructive static & configuration audit only. No exploitation payloads were executed against the device or external servers.

---

## 1. Confirmed Vulnerabilities & High-Priority Security Risks

### 1.1 [P1 / SEC-01] Untrusted `HF_FILE:` Output Path From `yt-dlp` Stdout (Arbitrary App-Readable File Import)
- **File:** `app/src/main/java/com/example/mdownload/engine/DownloadQueueManager.kt` (`resolveProducedFile()`)
- **Evidence:**
  - `YtDlpEngine` prints the post-processed output path prefixed with `HF_FILE:` to stdout.
  - `DownloadQueueManager.resolveProducedFile()` constructs `File(reportedPath)` and checks `file.exists() && file.isFile`, **without verifying that `file.canonicalPath` is contained within `jobWorkDir.canonicalPath`**.
- **Impact:** If a malicious or compromised `yt-dlp` extractor, playlist title edge case, or forged server metadata manipulates output template resolution or injects a crafted `HF_FILE:` line into stdout, HiFiDeck could copy/export an arbitrary app-readable file (e.g., from `filesDir` or `cacheDir`) into shared external MediaStore storage (`Music/`), exposing private app files to other apps.
- **Remediation:** Enforce strict canonical containment:
  ```kotlin
  val canonicalWorkDir = jobWorkDir.canonicalFile
  val canonicalCandidate = candidate.canonicalFile
  require(canonicalCandidate.toPath().startsWith(canonicalWorkDir.toPath()))
  ```

---

### 1.2 [P1 / SEC-02] Python `re.sub` Backreference Injection in `--replace-in-metadata`
- **File:** `app/src/main/java/com/example/mdownload/engine/YtDlpEngine.kt` (`executeDownload()`)
- **Evidence:**
  - `YtDlpEngine` passes `--replace-in-metadata title ^.*$ <cleanMeta.title>` directly to `yt-dlp`, where `cleanMeta.title` comes from untrusted remote video metadata (partially cleaned for display, but **not escaped for Python regex replacement syntax**).
- **Impact:** Remote titles containing backslashes (`\1`, `\g<0>`, `\u`, trailing `\`) trigger Python `re.error` crashes (DoS per download) or unintended backreference expansion into embedded audio tags.
- **Remediation:** Escape all backslashes (`cleanMeta.title.replace("\\", "\\\\")`) before passing to `--replace-in-metadata`, or apply clean title tagging natively after download instead of via regex replacement.

---

### 1.3 [P1 / SEC-03] Overbroad Auto-Backup (`allowBackup="true"` With Empty Rules)
- **Files:** `app/src/main/AndroidManifest.xml`, `app/src/main/res/xml/backup_rules.xml`, `app/src/main/res/xml/data_extraction_rules.xml`
- **Evidence:**
  - `AndroidManifest.xml` declares `android:allowBackup="true"`, pointing to template `backup_rules.xml` and `data_extraction_rules.xml` that contain **only commented-out placeholders** (zero `<exclude>` rules).
- **Impact:** Android cloud backup and device-to-device transfer back up the **entire `filesDir`**, including:
  - `hifideck_data/settings.json` (complete download URL history & user preferences)
  - `hifideck_data/settings.log` (full unredacted download URLs, titles, local file paths, error stack traces)
  - `hifideck_data/listening_history.json` (detailed timestamped listening behavior)
  - `hifideck_work/` (potentially hundreds of megabytes of pending/retained audio files, blowing past Android's 25 MB Auto-Backup quota and breaking backup reliability).
- **Remediation:** Add explicit `<exclude>` rules for `hifideck_work/`, `hifideck_data/settings.log`, and `hifideck_data/audio_analysis_cache.json` (or disable cloud backup for sensitive history/logs while offering explicit user-controlled JSON backup/export).

---

### 1.4 [P1 / SEC-04] Incomplete SSRF / Local-Network Host Blocklist on External Share Intents
- **Files:** `app/src/main/java/com/example/mdownload/core/YouTubeUrlParser.kt` (`isDisallowedHost()`), `app/src/main/java/com/example/mdownload/ShareDownloadActivity.kt`
- **Evidence:**
  - `ShareDownloadActivity` is `android:exported="true"` (required to receive Android Share Sheet `ACTION_SEND` `text/plain` intents from any installed app).
  - `YouTubeUrlParser.isDisallowedHost()` blocks common loopback/RFC1918 strings (`localhost`, `127.*`, `10.*`, `192.168.*`, `172.16-31.*`, `::1`, `*.local`), **but misses**:
    - IPv6 Unique Local Addresses (`fc00::/7`, `fd00::/8`)
    - IPv6 Link-Local (`fe80::/10`)
    - IPv4-mapped IPv6 addresses (`[::ffff:127.0.0.1]`, `[::ffff:192.168.1.1]`)
    - Carrier-Grade NAT (`100.64.0.0/10`)
    - Alternative IPv4 encodings (`http://2130706433/`, `http://0x7f000001/`, `http://0177.0.0.1/`)
    - DNS rebinding (hostname resolves to `127.0.0.1` or LAN IP inside `yt-dlp` Python runtime).
- **Impact:** A malicious app or web page shared to HiFiDeck can bypass string-based host checks and cause `yt-dlp` to issue HTTP requests to localhost services or private LAN endpoints.
- **Remediation:** Parse host via `InetAddress.getAllByName(host)` before invoking `yt-dlp` and reject `isLoopbackAddress || isSiteLocalAddress || isLinkLocalAddress || isAnyLocalAddress || isMulticastAddress`, plus CGNAT (`100.64.0.0/10`) and IPv6 ULA (`fc00::/7`).

---

### 1.5 [P2 / SEC-05] Unredacted Sensitive Data in Persistent Logs & Diagnostics Share Sheet
- **Files:** `AppLogger.kt` / `settings.log`, `SettingsScreen.kt`
- **Evidence:** Full video URLs (including query tokens/session IDs if present in shared URLs), video titles, local file paths, and device details are written to `filesDir/hifideck_data/settings.log` and Android `Log.*` (visible in logcat on debug builds).
- **Mitigation Already Present:** Settings warns the user before exporting/sharing diagnostics.
- **Gap:** No automatic query-parameter scrubbing (`token`, `sig`, `key`, `cookie`, `auth`) or path anonymization before persisting or sharing logs.

---

## 2. Exported Components & Permission Surface Audit

### 2.1 Exported Components (`AndroidManifest.xml`)

| Component | Exported | Purpose | Security Assessment |
|---|---|---|---|
| `MainActivity` | `true` | Launcher Activity | Standard entry point. No custom deep-link URI schemes accepting raw file paths. |
| `ShareDownloadActivity` | `true` | `ACTION_SEND` (`text/plain`) Share receiver | Required for Share Sheet. Validates text through `YouTubeUrlParser` and passes sanitized URL to queue, but subject to `SEC-04` SSRF bypass gaps and unthrottled intent flooding if called repeatedly by a malicious app. |
| `HiFiPlaybackService` | `true` | Media3 `MediaSessionService` (`MEDIA_BROWSER_SERVICE`) | Standard Media3 export requirement for system media controls, Bluetooth/headset, and Android Auto. Protected by Media3's `MediaSession` controller connection verification. |
| `androidx.media3.session.MediaButtonReceiver` | `true` | `MEDIA_BUTTON` broadcast receiver | Standard Media3 headset/Bluetooth key handling. |
| `DownloadForegroundService` | `false` (explicit) | Foreground `dataSync` download service | **Properly locked down** (`exported="false"`). |

### 2.2 Runtime & Manifest Permissions

| Permission | Justification | Assessment |
|---|---|---|
| `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | `yt-dlp` downloads, Coil artwork, LRCLIB lyrics API | Necessary. |
| `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK` | Background download service & Media3 playback service | Properly declared with matching `foregroundServiceType` attributes for Android 14–17. |
| `POST_NOTIFICATIONS` | Foreground download & playback notifications | Requested at runtime; verified granted on vivo X200s. |
| `READ_MEDIA_AUDIO` | Scanning local audio library via MediaStore | Correctly scoped (does NOT request `MANAGE_EXTERNAL_STORAGE` or `READ_MEDIA_VIDEO`/`IMAGES`). |
| `WAKE_LOCK` | Keep CPU awake during active downloads | Acquired only while download queue is active (released on stop). |

---

## 3. Theoretical / Lower-Severity Risks & Verified Safe Areas

| Area | Status | Notes |
|---|---|---|
| **Path Traversal in `StorageTools.discard()`** | **SAFE (Verified in code)** | Explicitly resolves canonical paths and rejects `../` traversal outside `hifideck_work/` (also covered by `SettingsDeviceTest`). |
| **Shell Command Injection in `FFmpeg` / `ffprobe` / `yt-dlp`** | **SAFE (No Shell Interpolation)** | All subprocesses (`YtDlpEngine`, `AudioInspectorEngine`, `LoudnessScanner`, `MetadataTagIO`) use `ProcessBuilder(List<String>)` / `YoutubeDLRequest` argument arrays — never `sh -c`. |
| **External Lyrics API Privacy (LRCLIB)** | **THEORETICAL / DISCLOSURE GAP** | `LyricsRepository` sends track title, artist, album, and duration over HTTPS to `https://lrclib.net/api/...` when user triggers online lyrics lookup (or auto-fetch if enabled). Transport is HTTPS (safe from eavesdropping), but there is no privacy notice/toggle disclosure explaining third-party metadata transmission to `lrclib.net`. |
| **`yt-dlp` Self-Update Version Check** | **BUG (Non-Exploitable)** | `YtDlpEngine.initializeAsync()` compares `version < "2026.10.26"` lexicographically; `"unknown" < "2026.10.26"` evaluates `false` (`'u' > '2'`), silently skipping updates when version detection fails. |
| **Embedded Binary Supply Chain (`youtubedl-android`)** | **OPERATIONAL RISK** | Bundles native `libpython.so`, `libqjs.so`, `libffmpeg.so` + runtime `yt-dlp` updates from GitHub releases (`YoutubeDL.updateYoutubeDL`). Pinning/verifying upstream release channels (`UpdateChannel.STABLE`) and keeping dependencies patched is essential. |
| **Metadata Injection into MediaStore / UI** | **LOW RISK** | Compose `Text` renders untrusted tag strings safely (no WebView/HTML evaluation). Extremely long tags (e.g., 100 KB `comment` or `lyrics`) are bounded in `MetadataValidator`, though incoming tags from external files have no display truncation cap in some inspector fields. |

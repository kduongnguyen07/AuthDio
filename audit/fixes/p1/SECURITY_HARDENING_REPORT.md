# HiFiDeck Android — P1 Security Hardening Report (`STAB-04`)

**Date:** 2026-11-24  
**Scope:** `SEC-01`, `SEC-02`, `SEC-03`, `SEC-04`, `SEC-05`, and Phase C P0 Recovery Coexistence  
**Test Suite:** `app/src/test/java/com/example/mdownload/SecurityAndSubprocessP1Test.kt` (18 JVM tests), `app/src/androidTest/java/com/example/mdownload/P1UiAndAccessibilityInstrumentedTest.kt` (Phase C test)

---

## 1. `SEC-01` — Output Path Containment Guard

### Root Cause
`DownloadQueueManager` previously trusted raw file paths printed by `yt-dlp --print after_move:filepath` (via `RESULT_FILE:<path>` markers) and passed `File(path)` directly into MediaStore export and cleanup routines without validating directory containment or symlink escapes.

### Remediation
- Added `com.example.mdownload.engine.OutputPathGuard`:
  - `parseMarkerLine(line)`: extracts the path after `RESULT_FILE:` while rejecting control characters (`\r`, `\n`, `\u0000`), blank values, and malformed markers.
  - `validateCandidate(candidatePath, allowedWorkDir)`: resolves `allowedWorkDir.toPath().toRealPath()` and `candidate.toPath().toRealPath()` (falling back safely when non-existent), enforces strict `Path.startsWith(Path)` containment (eliminating string-prefix bypasses such as `/work_evil` matching `/work`), rejects symlinks escaping the sandbox (`Files.isSymbolicLink` checked against real target), rejects directories (`!Files.isRegularFile`), and rejects zero-byte/missing files.
  - `resolveProducedFile(markerPath, fallbackDir, ...)`: validates the marker candidate first and falls back to the newest verified regular file strictly inside `allowedWorkDir`.
- Wired `OutputPathGuard` into `DownloadQueueManager` before post-processing, MediaStore export, and temporary work-dir cleanup.

### Verification
- `SecurityAndSubprocessP1Test.sec01_outputPathGuard_acceptsContainmentAndRejectsTraversalSymlinkAndSiblingPrefix`: verifies valid nested file acceptance and rejection of `../` traversal, sibling prefix (`/work_evil`), absolute out-of-sandbox paths, symlink escape, directory paths, and embedded control characters.

---

## 2. `SEC-02` — Safe `--replace-in-metadata` Escaping

### Root Cause
`YtDlpEngine` previously constructed `--replace-in-metadata title ".*" <userTitle>`. Because `yt-dlp` evaluates this with Python's `re.sub`:
1. Unescaped backreferences (`\1`, `\g<0>`, `\n`) or a trailing single backslash (`\`) in a user-edited title caused metadata corruption or Python `re.error` crashes.
2. In Python 3.7+, unanchored `.*` matches both the full string and the trailing empty string, causing `re.sub(".*", repl, s)` to **duplicate** the replacement text (`TitleTitle`).

### Remediation
- Updated `YtDlpEngine`:
  - `FULL_STRING_PYTHON_REGEX = "(?s)\\A.*\\Z"` — matches the entire string (including embedded newlines) exactly once, eliminating double-replacement.
  - `escapePythonRegexReplacement(raw)` — replaces every `\` with `\\` so `re.sub` treats all backslashes, `\1`, `\g<0>`, and trailing backslashes as literal characters.
  - `buildTitleReplaceCommand(rawTitle)` — emits `listOf("--replace-in-metadata", "title", FULL_STRING_PYTHON_REGEX, escapePythonRegexReplacement(rawTitle))`.

### Verification
- `SecurityAndSubprocessP1Test.sec02_replaceInMetadata_escapesBackslashesAndAnchorsFullString`: verifies escaping of `\1`, `\g<0>`, trailing `\`, mixed `C:\Music\Album\`, and exact anchor `(?s)\A.*\Z`.

---

## 3. `SEC-04` — Strict URL Parsing & SSRF Defense-in-Depth

### Root Cause
`YouTubeUrlParser.normalizeUrl()` previously relied on permissive `URI` checks that allowed credentials (`user:pass@host`), backslashes (`https://public\@127.0.0.1`), single-label/local hosts, integer/octal/hex IPv4 encodings (`2130706433`, `0x7f000001`, `0177.0.0.1`), IPv4-mapped IPv6 (`[::ffff:127.0.0.1]`), CGNAT (`100.64.0.0/10`), cloud metadata (`169.254.169.254`), and DNS hostnames resolving to loopback/private addresses.

### Remediation
- Hardened `YouTubeUrlParser.normalizeUrl(rawInput)` (strictly **offline**, safe on main thread):
  - Rejects non-`http`/`https` schemes (`file://`, `javascript:`, `ftp://`, `data:`, etc.).
  - Rejects embedded userinfo/`@`, backslashes (`\`), control chars, malformed percent-encoding, and out-of-range ports (`<= 0` or `> 65535`).
  - Applies IDN/Punycode normalization (`java.net.IDN.toASCII`).
  - Rejects trailing-dot hosts, single-label hosts (`localhost`, `intranet`), and local suffixes (`.local`, `.localhost`, `.internal`, `.home.arpa`, `.localdomain`).
  - Parses and blocks ALL non-public IPv4 literals including decimal-integer, hex, octal, and shorthand dotted forms (`0.0.0.0/8`, `10.0.0.0/8`, `100.64.0.0/10`, `127.0.0.0/8`, `169.254.0.0/16`, `172.16.0.0/12`, `192.168.0.0/16`, `224.0.0.0/4`, `240.0.0.0/4`, `255.255.255.255`).
  - Parses and blocks ALL non-public IPv6 literals including zone IDs (`%eth0`), unspecified (`::`), loopback (`::1`), link-local (`fe80::/10`), ULA (`fc00::/7`), multicast (`ff00::/8`), IPv4-mapped (`::ffff:0:0/96`), IPv4-compatible, 6to4 (`2002::/16`), and NAT64 (`64:ff9b::/96`) when embedded IPv4 is non-public.
- Added off-main-thread `YouTubeUrlParser.validateResolvedHost(normalizedUrl, resolver)` and wired it into `YtDlpEngine.inspectSource()` and `YtDlpEngine.executeDownload()` (on background worker threads) to reject domains whose resolved A/AAAA records include any non-public IP address.

### Honest Residual Boundary Disclosure (SSRF Defense-in-Depth)
Pre-flight URL validation and pre-flight DNS validation (`validateResolvedHost`) significantly reduce accidental and naive SSRF vectors, **but cannot by themselves prevent**:
1. **TOCTOU DNS Rebinding:** A malicious authoritative DNS server can return a public IP during `validateResolvedHost()` and a private/loopback IP milliseconds later when `yt-dlp`'s embedded Python runtime performs its own DNS lookup.
2. **HTTP Redirects inside `yt-dlp`:** A public URL that passes both syntactic and DNS validation may return an HTTP `302`/`307` redirect to `http://127.0.0.1:...` or `http://169.254.169.254/...` inside `yt-dlp`'s HTTP client, because `youtubedl-android` does not expose a custom socket factory or per-hop redirect validator to Kotlin.

Full elimination of those residual vectors would require an in-app controlled HTTP proxy or a patched `yt-dlp` network handler, which is outside `STAB-04` scope. No live SSRF probes were executed against any third-party infrastructure during validation.

---

## 4. `SEC-03` — Backup & Data Extraction Exclusions + Phase C Coexistence

### Root Cause
`data_extraction_rules.xml` and `backup_rules.xml` previously left broad app files eligible for Android Auto Backup / device-to-device transfer, risking leakage of download logs, temporary work directories, cache artifacts, playback/listening history, and transient P0 rollback journals (`pending_writes`).

### Remediation
- Updated `app/src/main/res/xml/data_extraction_rules.xml` (`cloud-backup` & `device-transfer`) and `app/src/main/res/xml/backup_rules.xml` (`full-backup-content`):
  - Scoped `<include domain="file" path="hifideck_data" />` so all sibling directories (`hifideck_work`, `hifideck_archives`, caches, temp staging) are excluded by default.
  - Explicitly excluded sensitive/transient files inside `hifideck_data`:
    - `hifideck_data/pending_writes` (P0 crash-recovery journal & rollback copies)
    - `hifideck_data/settings.log`
    - `hifideck_data/settings.json`
    - `hifideck_data/settings.tmp`
    - `hifideck_data/listening_history.json`
    - `hifideck_data/audio_analysis_cache.json`
    - `hifideck_data/playback_state.json`
    - `hifideck_data/sleep_timer.json`
  - Preserved zero Android Lint errors (`BackupRules` / `DataExtractionRules`).

### Phase C Verification (P0 Recovery Coexistence)
Backup exclusion rules control only cloud/device transfer serialization and **never** delete or restrict local files in `context.filesDir`. Confirmed on real vivo X200s hardware via `P1UiAndAccessibilityInstrumentedTest.phaseC_safeAudioWritePipeline_realPendingWritesDirRecoveryUnaffectedByBackupExclusions`:
- Created a disposable FFmpeg FLAC fixture in `cacheDir` and simulated an interrupted `COMMITTING` journal + rollback inside the real production directory `filesDir/hifideck_data/pending_writes/`.
- `SafeAudioWritePipeline.recoverInterruptedOperations()` restored the truncated target file bit-for-bit (`restoredCount >= 1`, `failedRetainedCount == 0`) and removed the verified test journal cleanly.

---

## 5. `SEC-05` — Diagnostic Log Sanitization

### Root Cause
`SettingsStore.appendLog()` and `DownloadQueueManager.appendLog()` persisted raw URLs (including query parameters, tokens, credentials), HTTP headers, and unfiltered line breaks into `settings.log`.

### Remediation
- Created `com.example.mdownload.core.LogSanitizer`:
  - Redacts URL userinfo (`https://user:pass@host` -> `https://[REDACTED]@host`).
  - Redacts query parameters and fragments (`?token=abc&sig=xyz` -> `?[REDACTED]`).
  - Redacts `Authorization: Bearer ...`, `Cookie: ...`, `Set-Cookie: ...`, and key-value secrets (`token=`, `api_key=`, `password=`, `secret=`, `sig=`).
  - Strips/escapes CR/LF and control characters to prevent log-forging/line-injection and bounds single-entry length.
- Wired `LogSanitizer.sanitize()` into `SettingsStore.appendLog()` and `DownloadQueueManager.appendLog()`.

### Verification
- `SecurityAndSubprocessP1Test.sec05_logSanitizer_redactsSecretsQueryStringsCredentialsAndNewlines`: verified redaction across URLs with credentials & query strings, bearer/cookie headers, and CRLF log injection attempts.

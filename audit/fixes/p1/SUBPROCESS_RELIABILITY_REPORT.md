# HiFiDeck Android — Subprocess Reliability Report (`AUD-08` & `AUD-09`)

**Date:** 2026-11-24  
**Scope:** `AUD-08` (`AudioInspectorEngine`) and `AUD-09` (`LoudnessScanner` & `AudioAnalysisRepository`)  
**Key Files:**
- `app/src/main/java/com/example/mdownload/core/audio/BoundedSubprocessRunner.kt`
- `app/src/main/java/com/example/mdownload/core/audio/AudioInspectorEngine.kt`
- `app/src/main/java/com/example/mdownload/core/audio/LoudnessScanner.kt`
- `app/src/main/java/com/example/mdownload/core/audio/AudioAnalysisRepository.kt`

---

## 1. Shared Execution Engine (`BoundedSubprocessRunner`)

### Previous Failure Modes
1. **Pipe Deadlock:** Unmerged or sequentially read `stdout`/`stderr` pipes can fill the OS pipe buffer (~64 KiB on Linux/Android) and deadlock the child process indefinitely.
2. **Unbounded Memory Growth:** `bufferedReader().readText()` on verbose FFmpeg/FFprobe output could retain megabytes of logs in RAM.
3. **Unresponsive Cancellation & Zombie Processes:** Blocking `process.waitFor(timeout)` ignored coroutine cancellation until the full timeout elapsed and did not consistently escalate `destroy()` -> `destroyForcibly()` and reap exit status.

### Implemented Safeguards
`BoundedSubprocessRunner.run(...)` (`suspend`, runs on `Dispatchers.IO`):
- Spawns two concurrent daemon reader threads (`stdout` and `stderr`, or merged when requested) that drain streams continuously until EOF so child processes can **never** deadlock on full OS pipes.
- Enforces configurable per-stream byte caps (`maxStdoutBytes`, `maxStderrBytes`) plus optional per-line filtering (`stdoutLineFilter`, `stderrLineFilter`) so irrelevant verbose progress output is discarded before hitting the cap while keeping stream draining active.
- Polls process completion with short slices under a strict wall-clock deadline (`timeoutMs`), immediately detecting coroutine cancellation (`CancellationException`) or deadline expiration.
- Terminates hung/cancelled processes via escalating `destroy()` -> short grace window -> `destroyForcibly()` -> bounded reap, preventing zombie processes and leaked file descriptors.
- Returns structured `BoundedSubprocessRunner.SubprocessResult` with `Completed`, `TimedOut`, `Cancelled`, `StartFailed`, exit code, captured bounded output, truncation flags, and elapsed time.

---

## 2. `AUD-08` — `AudioInspectorEngine` Hardening

### Changes
- Converted `AudioInspectorEngine.inspectTrack()` and `probeWithFfprobe()` to `suspend` functions using `BoundedSubprocessRunner.run(...)`.
- Configured explicit bounded probe timeout (`12_000L` ms) and bounded JSON capture (`128 * 1024` bytes stdout, `16 * 1024` bytes stderr).
- Preserves graceful fallback to `MediaExtractor` + `MediaMetadataRetriever` + binary `HeaderParser` when FFprobe is unavailable, times out, or returns malformed output.

### Verification
- Live JVM child-process unit tests in `SecurityAndSubprocessP1Test`:
  - `aud08_boundedSubprocessRunner_enforcesTimeoutAndKillsHungProcess`: spawns a real child JVM process sleeping 30s, verifies timeout triggers cleanly near deadline and child process is terminated (`!isAlive`).
  - `aud08_boundedSubprocessRunner_drainsHighVolumeStdoutStderrWithoutDeadlock`: spawns a real child JVM process writing >512 KiB simultaneously to stdout and stderr, verifies zero pipe deadlock, exit code 0, and bounded memory capture.
  - `aud08_boundedSubprocessRunner_coroutineCancellationTerminatesSubprocessPromptly`: cancels the parent coroutine mid-execution and verifies prompt child termination + `CancellationException` propagation.
- Live vivo X200s execution: `AudioInspectorSheet` opened on a real 6:17 MP3 track reported `Inspector source: MediaExtractor + MediaMetadataRetriever + HeaderParser + FFprobe` with full accurate metadata (`44.1 kHz`, `2 (stereo)`, `192 kbps · CBR`, `8.7 MB`) and zero UI freeze.

---

## 3. `AUD-09` — `LoudnessScanner` & `AudioAnalysisRepository` Hardening

### Changes
- Added duration-aware timeout calculation `LoudnessScanner.computeTimeoutMs(durationMs)`:
  - Default when duration is unknown (`<= 0`): `45_000L` ms.
  - Scales cleanly with track duration (`30_000L` ms minimum, `180_000L` ms maximum) so 20+ minute lossless/Hi-Res recordings do not prematurely abort while corrupt inputs remain strictly bounded.
- Added `LoudnessScanner.isRelevantEbuR128Line(line)` filter passed to `BoundedSubprocessRunner`:
  - **Real Bug Caught During Testing:** FFmpeg's `ebur128` filter emits `I: -xx.x LUFS` on every 100ms progress frame (`[Parsed_ebur128_0] t: 0.1 ... M: ... I: ...`). On long tracks, retaining all progress lines filled the 128 KiB stderr cap before the final `Summary:` block arrived, causing scan failures or inaccurate mid-track readings.
  - Filtering out per-frame progress lines (lines containing both `t:` and `M:`) while retaining `Summary:`, `Integrated loudness:`, `I:`, `True peak:`, `Peak:`, and `LRA:` guarantees the final summary is always captured regardless of track length.
- Introduced structured `LoudnessScanResult` (`Success`, `TimedOut`, `Cancelled`, `ProcessFailed`, `ParseFailed`, `Unavailable`) via `scanTrackUriDetailed(...)` (keeping `scanTrackUri(...)` backward-compatible).
- Updated `AudioAnalysisRepository` to pass `track.durationMs` into `scanTrackUriDetailed(...)` and **never** persist failed, timed-out, or incomplete loudness results into the analysis cache.

### Verification
- `SecurityAndSubprocessP1Test.aud09_loudnessScanner_durationTimeoutAndNoisyLongTrackParsing`:
  - Verifies `computeTimeoutMs()` bounds (`0L -> 45s`, `30s -> 30s`, `30m -> 180s`).
  - Spawns a real child JVM process simulating a noisy multi-megabyte FFmpeg `ebur128` stream (thousands of per-100ms `t: ... M: ... I:` progress frames followed by the final `Summary:` block) and verifies `isRelevantEbuR128Line` + `BoundedSubprocessRunner` captures the exact final `I: -14.2 LUFS` and `Peak: -1.1 dBFS` with zero truncation.
- `SecurityAndSubprocessP1Test.aud09_loudnessScanner_timeoutAndFailureReturnStructuredErrorsWithoutCaching`: verifies `TimedOut` and `ProcessFailed` never masquerade as valid loudness results.
- `MusicPlayerInstrumentedTest.audioAnalysisRepository_inspectsRealTrackCachesAndInvalidatesCleanly`: **PASSED** on vivo X200s.

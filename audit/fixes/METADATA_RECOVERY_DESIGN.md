# Metadata & Lyrics Safe Write Pipeline and Recovery Design (`STAB-02` / `META-01`)

**Date:** 2026-11-24  
**Primary Files:**
- `app/src/main/java/com/example/mdownload/core/metadata/SafeAudioWritePipeline.kt`
- `app/src/main/java/com/example/mdownload/core/metadata/MetadataRepository.kt`
- `app/src/main/java/com/example/mdownload/player/lyrics/LyricsTagIO.kt`
- `app/src/main/java/com/example/mdownload/player/lyrics/LyricsRepository.kt`
- `app/src/main/java/com/example/mdownload/MDownloadApp.kt`
- `app/src/main/java/com/example/mdownload/MainViewModel.kt`

---

## 1. Original Defect (`META-01`)

Both `MetadataRepository.commitContentUri()` and `LyricsTagIO.embedLyricsIntoUri()` previously committed edited audio files to MediaStore / SAF URIs by directly opening:
```kotlin
contentResolver.openOutputStream(uri, "wt")
```
Because `"wt"` truncates the existing file to 0 bytes before streaming replacement bytes, any mid-write exception, storage exhaustion (`ENOSPC`), coroutine cancellation, MediaStore revocation, or OS process kill (`SIGKILL`/LMK) left the user's audio file permanently truncated or destroyed with no durable backup and no startup recovery mechanism. Additionally, `LyricsTagIO.embedLyricsIntoUri()` bypassed the MediaStore write-permission flow (`createWriteRequest`).

---

## 2. Root Cause

1. Android's `ContentResolver` does not expose POSIX atomic file replacement (`rename(2)`) across arbitrary `content://` providers.
2. Staging files lived in ephemeral `cacheDir` without `fsync`, without a persistent operation journal, and were deleted in `finally` blocks even when destination writes failed or crashed.
3. Metadata and lyrics writes implemented separate, uncoordinated commit routines.

---

## 3. Implementation Strategy: `SafeAudioWritePipeline`

One shared pipeline (`SafeAudioWritePipeline`) now serves both `MetadataRepository` and `LyricsTagIO`.

### 3.1 Journal Layout
Durable recovery state is stored under app-private non-cache storage:
```
<filesDir>/hifideck_data/pending_writes/<opId>/
  ├── rollback.<ext>        # Full fsync'd copy of original audio file
  ├── manifest.json         # Pure-Kotlin JSON manifest (written via temp + fsync + atomic rename)
  └── staged_out.<ext>      # Temporary generated replacement (deleted on completion)
```
Manifest serialization uses a pure-Kotlin flat JSON encoder/decoder (zero dependency on Android's `org.json` stub) so JVM failure-injection tests and Android runtime execute the exact same journal code path.

### 3.2 13-Step Commit State Machine

```
PREPARING ──▶ READY_TO_COMMIT ──▶ COMMITTING ──▶ VERIFIED ──▶ (delete opDir)
                                      │
                                      ▼ (error / mismatch / partial write)
                                NEEDS_RECOVERY
                                      │
                     ┌────────────────┴────────────────┐
                     ▼ (rollback verified)             ▼ (rollback incomplete/failed)
             FailedRecovered                    RECOVERY_FAILED
             (delete opDir)                     (RETAIN rollback + manifest!)
```

1. **Permission pre-check**: Checks `backend.canOpenReadWrite(targetUri)` before allocating backups; returns `CommitOutcome.PermissionRequired` for MediaStore URIs requiring `MediaStore.createWriteRequest()`.
2. **Read & validate original**: Streams the original URI with a bounded 32 KiB buffer into `rollback.<ext>`, computes SHA-256 + exact byte length, calls `out.flush()` + `out.fd.sync()`, and verifies minimum size.
3. **Persist `PREPARING` manifest**: Writes `manifest.json` (`opId`, `targetUri`, `displayName`, `operationType`, `formatName`, `originalSha256`, `originalSizeBytes`, `rollbackPath`, timestamps, state) via sibling `.tmp` + `fd.sync()` + `renameTo()`.
4. **Generate & validate replacement**: Runs `transformFile(rollbackFile, stagedOutFile)` (which performs format-specific tag editing, audio-payload SHA-256 check, and tag read-back verification) before touching the destination URI.
5. **Cancellation checkpoint**: Checks `currentCoroutineContext().ensureActive()` before any destructive action; if cancelled before commit, deletes staging dir and leaves destination untouched.
6. **Destination capacity check**: Queries `StorageManager.getAllocatableBytes(uuid)` (or `File.usableSpace` for `file://`) and rejects commit before truncation if `availableBytes < requiredBytes + 4 MiB`.
7. **Persist `READY_TO_COMMIT`**: Records `expectedOutputSha256`, `expectedOutputSizeBytes`, and `stagedOutputPath` durably.
8. **Persist `COMMITTING`**: Durably records commit intent immediately before opening the destination stream.
9. **Bounded-memory commit**:
   - For `file://` URIs: writes `stagedOutFile` to a same-directory sibling `.tmp` file (`fd.sync()`) and commits via atomic `renameTo()` where supported.
   - For `content://` URIs: streams `stagedOutFile` in 32 KiB chunks into `openOutputStream(uri, "wt")`, calls `flush()` + `fd.sync()` where exposed.
10. **Post-write verification**: Re-reads the destination URI with bounded 32 KiB streaming, verifies exact byte count AND SHA-256 equal `expectedOutputSizeBytes` / `expectedOutputSha256`, and runs optional format read-back verification (`postVerifyDestinationFile`).
11. **Cleanup strictly after verification**: Marks `VERIFIED` and deletes `<opDir>` (including `rollback.<ext>`) **only** after step 10 passes.
12. **Immediate rollback on failure**: On any write exception, partial write, or verification mismatch, marks `NEEDS_RECOVERY`, streams `rollback.<ext>` back to `targetUri`, and verifies restored destination matches `originalSha256`.
    - If restoration verifies bit-for-bit: deletes `<opDir>` and returns `CommitOutcome.FailedRecovered`.
    - If restoration fails or mismatches: marks `RECOVERY_FAILED`, **retains `rollback.<ext>` and `manifest.json`**, and returns `CommitOutcome.FailedRollbackRetained`.
13. **Next-startup crash recovery**: `MDownloadApp.onCreate()` invokes `metadataRepository.recoverInterruptedWrites()` asynchronously off the Main thread (`Dispatchers.IO`). `recoverInterruptedOperations()` inspects every directory in `pending_writes/`:
    - `VERIFIED` or destination already matches `expectedOutputSha256` -> clean up.
    - Destination still matches `originalSha256` -> clean up untouched staging.
    - Destination truncated/corrupted/missing -> verify `rollback.<ext>` SHA-256 matches `originalSha256`, stream back to `targetUri`, re-verify destination SHA-256, and delete `<opDir>` **only** if restoration verifies; otherwise mark `RECOVERY_FAILED` and preserve rollback.

---

## 4. Tests Performed & Actual Results

- **JVM Failure Injection Suite (`MetadataIntegrityP0Test.kt`)** — **11/11 PASSED**:
  - `safePipeline_happyPathCommitsVerifiesAndCleansRollback`: verified commit + SHA-256 + journal cleanup.
  - `safePipeline_cancellationBeforeCommitLeavesDestinationUntouchedAndCleansUp`: verified cancellation aborts cleanly before opening destination.
  - `safePipeline_exceptionDuringWriteAutomaticallyRestoresOriginalBytes`: verified mid-stream `IOException` triggers verified rollback.
  - `safePipeline_partialWriteAndReadBackMismatchBothRestoreOriginalBytes`: verified truncated write and silent provider byte corruption both trigger verified rollback.
  - `safePipeline_insufficientStorageAndPermissionDenialAndInvalidReplacementAbortBeforeTouchingDestination`: verified ENOSPC, permission denial (`PermissionRequired`), and invalid transform all abort without opening destination.
  - `safePipeline_processKillAndFailedRollbackRetainsBackupUntilStartupRecoverySucceeds`: verified failed immediate rollback retains `rollback` + `RECOVERY_FAILED` manifest, and subsequent `recoverInterruptedOperations()` restores original bytes bit-for-bit on simulated restart.
- **On-Device vivo X200s Test (`P0StabilizationInstrumentedTest#safeAudioWritePipeline_realAndroidFileCommitAndCrashRecoveryOnDevice`)** — **PASSED**:
  - Executed real FLAC metadata commit via `SafeAudioWritePipeline` + `AndroidContentResolverBackend` on Android 17 storage, then simulated a mid-commit truncated destination with a durable `COMMITTING` manifest and verified bit-for-bit restoration and cleanup.

---

## 5. Honest Remaining Limitations

1. **No OS-level crash-proof atomicity for generic `content://` providers**: Android's `ContentResolver` does not guarantee atomic replace across arbitrary SAF/MediaStore providers. Because `"wt"` truncates before streaming, crash safety relies on durable rollback + startup recovery rather than single-syscall atomicity.
2. **Startup recovery requires permission persistence**: If a process dies mid-commit on a MediaStore URI whose one-shot `createWriteRequest` grant expired across process death, `recoverInterruptedOperations()` will fail to open the URI, **retain the intact rollback copy** (`RECOVERY_FAILED`) in `filesDir/hifideck_data/pending_writes/<opId>/`, and never delete it. A future UX improvement (post-P0) can surface retained `RECOVERY_FAILED` backups in a recovery dialog to request write permission again.
3. **Storage headroom estimation**: SAF providers backed by remote/virtual documents may return `null` for available storage; when unavailable, capacity pre-check degrades gracefully and relies on write-error + rollback recovery.

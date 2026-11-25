package com.example.mdownload.core.metadata

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.MediaStore
import android.util.Log
import com.example.mdownload.player.lyrics.AudioContainerFormat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Shared, rollback-protected, crash-recoverable write pipeline for modifying audio files via
 * Android `content://` URIs (MediaStore / SAF) or local `file://` paths.
 *
 * Used by BOTH [MetadataRepository] and [com.example.mdownload.player.lyrics.LyricsTagIO].
 *
 * ### Honest Atomicity & Durability Guarantee
 * - For `file://` paths: writes verified replacement to a sibling temp file (`fd.sync()`) and uses
 *   atomic `renameTo()` (with rollback backup if cross-filesystem fallback copy is ever needed).
 * - For Android `content://` URIs (MediaStore / SAF): Android's generic `ContentResolver` does NOT
 *   provide POSIX atomic rename across arbitrary document providers; opening `openOutputStream(uri, "wt")`
 *   truncates the destination before streaming bytes. We therefore NEVER claim crash-proof OS atomicity
 *   for generic `content://` providers. Instead, we guarantee **recoverable durability**:
 *   1. Stream source into a durable rollback copy under `filesDir/hifideck_data/pending_writes/<opId>/`
 *      and compute its SHA-256 + byte length (`fd.sync()`).
 *   2. Persist an atomic, `fd.sync()`-ed `manifest.json` (`PREPARING` -> `READY_TO_COMMIT` -> `COMMITTING`).
 *   3. Generate & validate the replacement file before touching the destination URI.
 *   4. Verify destination storage capacity where provider/OS exposes reliable capacity.
 *   5. Verify MediaStore / SAF write permission before touching destination.
 *   6. Stream replacement with bounded 32 KiB memory, flush, and re-read destination to verify exact
 *      byte length and SHA-256 match against the staged output.
 *   7. Delete rollback data ONLY after post-write destination verification succeeds.
 *   8. On any write failure or verification mismatch: automatically restore from rollback copy,
 *      verify restored destination matches `originalSha256`, and KEEP rollback data if restoration
 *      is incomplete or fails.
 *   9. On next app startup ([recoverInterruptedOperations]), scan all unfinished manifests and
 *      recover any interrupted `COMMITTING` / `NEEDS_RECOVERY` operations automatically.
 */
class SafeAudioWritePipeline(
    private val journalRootDir: File,
    private val backend: AudioUriIoBackend
) {
    companion object {
        private const val TAG = "SafeAudioWritePipeline"
        private const val JOURNAL_DIR_NAME = "hifideck_data/pending_writes"
        private const val BUFFER_SIZE = 32 * 1024
        private const val MIN_HEADROOM_BYTES = 4L * 1024L * 1024L

        // Coordinates startup recovery with incoming metadata/lyrics writes on the same journal dir.
        private val activeRecoveryByJournal = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

        fun forContext(context: Context): SafeAudioWritePipeline = SafeAudioWritePipeline(
            journalRootDir = File(context.filesDir, JOURNAL_DIR_NAME),
            backend = AndroidContentResolverBackend(context.applicationContext)
        )
    }

    private fun journalKey(): String =
        runCatching { journalRootDir.canonicalPath }.getOrElse { journalRootDir.absolutePath }

    enum class OperationType { METADATA, LYRICS }

    enum class OperationState {
        PREPARING,
        READY_TO_COMMIT,
        COMMITTING,
        VERIFIED,
        NEEDS_RECOVERY,
        RECOVERY_FAILED
    }

    data class WriteManifest(
        val opId: String,
        val targetUri: String,
        val displayName: String,
        val operationType: OperationType,
        val formatName: String,
        val originalSha256: String,
        val originalSizeBytes: Long,
        val rollbackPath: String,
        val expectedOutputSha256: String = "",
        val expectedOutputSizeBytes: Long = 0L,
        val stagedOutputPath: String = "",
        val state: OperationState,
        val createdAtMs: Long,
        val updatedAtMs: Long,
        val lastError: String = ""
    ) {
        fun toJsonString(): String {
            val sb = StringBuilder(512)
            sb.append("{\n")
            fun appendStr(k: String, v: String, comma: Boolean = true) {
                sb.append("  \"").append(k).append("\": \"").append(escapeJson(v)).append("\"")
                if (comma) sb.append(',')
                sb.append('\n')
            }
            fun appendLong(k: String, v: Long, comma: Boolean = true) {
                sb.append("  \"").append(k).append("\": ").append(v)
                if (comma) sb.append(',')
                sb.append('\n')
            }
            appendStr("opId", opId)
            appendStr("targetUri", targetUri)
            appendStr("displayName", displayName)
            appendStr("operationType", operationType.name)
            appendStr("formatName", formatName)
            appendStr("originalSha256", originalSha256)
            appendLong("originalSizeBytes", originalSizeBytes)
            appendStr("rollbackPath", rollbackPath)
            appendStr("expectedOutputSha256", expectedOutputSha256)
            appendLong("expectedOutputSizeBytes", expectedOutputSizeBytes)
            appendStr("stagedOutputPath", stagedOutputPath)
            appendStr("state", state.name)
            appendLong("createdAtMs", createdAtMs)
            appendLong("updatedAtMs", updatedAtMs)
            appendStr("lastError", lastError, comma = false)
            sb.append('}')
            return sb.toString()
        }

        companion object {
            fun fromJsonString(json: String): WriteManifest? = runCatching {
                val map = parseFlatJsonObject(json) ?: return null
                WriteManifest(
                    opId = map["opId"] ?: return null,
                    targetUri = map["targetUri"] ?: return null,
                    displayName = map["displayName"] ?: "",
                    operationType = OperationType.valueOf(map["operationType"] ?: OperationType.METADATA.name),
                    formatName = map["formatName"] ?: AudioContainerFormat.UNSUPPORTED.name,
                    originalSha256 = map["originalSha256"] ?: return null,
                    originalSizeBytes = (map["originalSizeBytes"] ?: return null).toLong(),
                    rollbackPath = map["rollbackPath"] ?: return null,
                    expectedOutputSha256 = map["expectedOutputSha256"] ?: "",
                    expectedOutputSizeBytes = map["expectedOutputSizeBytes"]?.toLongOrNull() ?: 0L,
                    stagedOutputPath = map["stagedOutputPath"] ?: "",
                    state = OperationState.valueOf(map["state"] ?: return null),
                    createdAtMs = map["createdAtMs"]?.toLongOrNull() ?: 0L,
                    updatedAtMs = map["updatedAtMs"]?.toLongOrNull() ?: 0L,
                    lastError = map["lastError"] ?: ""
                )
            }.getOrNull()

            private fun escapeJson(s: String): String = buildString(s.length) {
                for (c in s) {
                    when (c) {
                        '\\' -> append("\\\\")
                        '"' -> append("\\\"")
                        '\n' -> append("\\n")
                        '\r' -> append("\\r")
                        '\t' -> append("\\t")
                        else -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
                    }
                }
            }

            /**
             * Pure-Kotlin parser for flat JSON objects (strings & numbers) so [SafeAudioWritePipeline]
             * runs identically on Android runtime and plain JVM unit tests without mocking `org.json`.
             */
            private fun parseFlatJsonObject(src: String): Map<String, String>? {
                val out = LinkedHashMap<String, String>()
                var i = 0
                val n = src.length
                fun skipWs() {
                    while (i < n && src[i].isWhitespace()) i++
                }
                fun readString(): String? {
                    if (i >= n || src[i] != '"') return null
                    i++
                    val sb = StringBuilder()
                    while (i < n) {
                        val c = src[i++]
                        if (c == '"') return sb.toString()
                        if (c == '\\') {
                            if (i >= n) return null
                            when (val esc = src[i++]) {
                                '\\' -> sb.append('\\')
                                '"' -> sb.append('"')
                                '/' -> sb.append('/')
                                'n' -> sb.append('\n')
                                'r' -> sb.append('\r')
                                't' -> sb.append('\t')
                                'b' -> sb.append('\b')
                                'f' -> sb.append('\u000C')
                                'u' -> {
                                    if (i + 4 > n) return null
                                    val hex = src.substring(i, i + 4)
                                    sb.append(hex.toInt(16).toChar())
                                    i += 4
                                }
                                else -> sb.append(esc)
                            }
                        } else {
                            sb.append(c)
                        }
                    }
                    return null
                }

                skipWs()
                if (i >= n || src[i] != '{') return null
                i++
                while (true) {
                    skipWs()
                    if (i >= n) return null
                    if (src[i] == '}') return out
                    val key = readString() ?: return null
                    skipWs()
                    if (i >= n || src[i] != ':') return null
                    i++
                    skipWs()
                    if (i >= n) return null
                    val value = if (src[i] == '"') {
                        readString() ?: return null
                    } else {
                        val start = i
                        while (i < n && src[i] != ',' && src[i] != '}' && !src[i].isWhitespace()) i++
                        src.substring(start, i)
                    }
                    out[key] = value
                    skipWs()
                    if (i < n && src[i] == ',') {
                        i++
                        continue
                    }
                    if (i < n && src[i] == '}') return out
                    return null
                }
            }
        }
    }

    sealed interface CommitOutcome<out T> {
        data class Committed<T>(val payload: T, val outputSizeBytes: Long, val outputSha256: String) : CommitOutcome<T>
        data class PermissionRequired(val targetUri: String, val message: String) : CommitOutcome<Nothing>
        data class Aborted(val reason: String, val payload: Any? = null) : CommitOutcome<Nothing>
        data class FailedRecovered(val reason: String) : CommitOutcome<Nothing>
        data class FailedRollbackRetained(val reason: String, val rollbackPath: String) : CommitOutcome<Nothing>
    }

    data class RecoveryReport(
        val scannedCount: Int = 0,
        val restoredCount: Int = 0,
        val cleanedAlreadyCompleteCount: Int = 0,
        val cleanedUntouchedCount: Int = 0,
        val failedRetainedCount: Int = 0,
        val details: List<String> = emptyList()
    )

    /**
     * Injectable I/O backend so JVM & instrumented failure-injection tests can deterministically
     * simulate write exceptions, partial writes, ENOSPC, permission denial, read-back corruption,
     * and process death during commit without touching real user music files.
     */
    interface AudioUriIoBackend {
        fun isMediaStoreUri(targetUri: String): Boolean
        fun canOpenReadWrite(targetUri: String): Boolean
        fun openInputStream(targetUri: String): InputStream?
        fun openTruncatingOutputStream(targetUri: String): OutputStream?
        fun availableDestinationBytes(targetUri: String): Long?
    }

    /**
     * Executes a full prepare -> validate -> backup -> commit -> verify -> cleanup transaction.
     *
     * [transformFile] receives `(sourceRollbackFile, stagedOutputFile)` and returns either:
     * - `Right(payload)` on valid replacement output, or
     * - `Left(abortReason, optionalResultPayload)` to abort cleanly before touching the destination.
     */
    suspend fun <T> executeSafeWrite(
        targetUri: String,
        displayName: String,
        format: AudioContainerFormat,
        operationType: OperationType,
        expectedMinSourceBytes: Long = 16L,
        transformFile: suspend (srcFile: File, dstFile: File) -> TransformDecision<T>,
        postVerifyDestinationFile: ((File) -> Boolean)? = null
    ): CommitOutcome<T> {
        // Wait for any in-flight startup crash recovery on this journal directory before starting a new write.
        activeRecoveryByJournal[journalKey()]?.await()

        // Pre-check permission before allocating rollback copies
        if (!backend.canOpenReadWrite(targetUri)) {
            return if (backend.isMediaStoreUri(targetUri)) {
                CommitOutcome.PermissionRequired(
                    targetUri = targetUri,
                    message = "Grant permission to modify this audio file."
                )
            } else {
                CommitOutcome.Aborted("Android storage permission does not allow modifying this file.")
            }
        }

        journalRootDir.mkdirs()
        val opId = "${operationType.name.lowercase()}_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
        val opDir = File(journalRootDir, opId).apply { mkdirs() }
        val ext = extensionFor(format, displayName)
        val rollbackFile = File(opDir, "rollback.$ext")
        val stagedOutputFile = File(opDir, "staged_out.$ext")
        val manifestFile = File(opDir, "manifest.json")

        var manifest: WriteManifest? = null
        var destinationTouched = false

        try {
            currentCoroutineContext().ensureActive()

            // Stage 1: Stream original destination into durable rollback copy + compute SHA-256
            val (origSize, origSha) = streamToFileWithSha256(
                inputProvider = { backend.openInputStream(targetUri) },
                targetFile = rollbackFile
            ) ?: return CommitOutcome.Aborted("Could not read original audio file.").also {
                deleteDirRecursively(opDir)
            }

            if (origSize < expectedMinSourceBytes) {
                deleteDirRecursively(opDir)
                return CommitOutcome.Aborted("Original audio file is empty or truncated ($origSize bytes).")
            }

            // Stage 2: Check journal directory headroom for staged replacement
            val requiredJournalBytes = origSize + MIN_HEADROOM_BYTES
            if (journalRootDir.usableSpace < requiredJournalBytes) {
                deleteDirRecursively(opDir)
                return CommitOutcome.Aborted("Insufficient internal storage to stage a safe write.")
            }

            val now = System.currentTimeMillis()
            manifest = WriteManifest(
                opId = opId,
                targetUri = targetUri,
                displayName = displayName,
                operationType = operationType,
                formatName = format.name,
                originalSha256 = origSha,
                originalSizeBytes = origSize,
                rollbackPath = rollbackFile.absolutePath,
                stagedOutputPath = stagedOutputFile.absolutePath,
                state = OperationState.PREPARING,
                createdAtMs = now,
                updatedAtMs = now
            )
            writeManifestDurably(manifestFile, manifest)

            currentCoroutineContext().ensureActive()

            // Stage 3: Build & validate replacement file in stagedOutputFile
            val decision = transformFile(rollbackFile, stagedOutputFile)
            val successPayload = when (decision) {
                is TransformDecision.Abort -> {
                    deleteDirRecursively(opDir)
                    return CommitOutcome.Aborted(decision.reason, decision.originalPayload)
                }
                is TransformDecision.Ready -> decision.payload
            }

            if (!stagedOutputFile.isFile || stagedOutputFile.length() < expectedMinSourceBytes) {
                deleteDirRecursively(opDir)
                return CommitOutcome.Aborted("Generated replacement file was empty or invalid.")
            }

            val expectedOutSize = stagedOutputFile.length()
            val expectedOutSha = AudioContainerPrimitives.computeFileSha256(stagedOutputFile)
                ?: run {
                    deleteDirRecursively(opDir)
                    return CommitOutcome.Aborted("Failed to fingerprint staged replacement file.")
                }

            // Stage 4: Check destination volume headroom when provider exposes capacity
            val destAvailable = backend.availableDestinationBytes(targetUri)
            val growthBytes = (expectedOutSize - origSize).coerceAtLeast(0L)
            if (destAvailable != null && destAvailable < growthBytes + MIN_HEADROOM_BYTES) {
                deleteDirRecursively(opDir)
                return CommitOutcome.Aborted(
                    "Insufficient destination storage (${destAvailable / (1024 * 1024)} MB free) to safely commit updated audio file."
                )
            }

            // Stage 5: Durably mark READY_TO_COMMIT before touching destination
            manifest = manifest.copy(
                expectedOutputSha256 = expectedOutSha,
                expectedOutputSizeBytes = expectedOutSize,
                state = OperationState.READY_TO_COMMIT,
                updatedAtMs = System.currentTimeMillis()
            )
            writeManifestDurably(manifestFile, manifest)

            // Final cancellation check before mutating destination
            currentCoroutineContext().ensureActive()

            // Stage 6: Mark COMMITTING durably BEFORE truncating/writing destination
            manifest = manifest.copy(
                state = OperationState.COMMITTING,
                updatedAtMs = System.currentTimeMillis()
            )
            writeManifestDurably(manifestFile, manifest)
            destinationTouched = true

            // Stage 7: Stream staged output into destination with bounded 32 KiB memory
            val bytesWritten = try {
                streamFileToDestination(stagedOutputFile, targetUri)
            } catch (sec: SecurityException) {
                // Destination was not opened for write if SecurityException happened in openOutputStream
                val intact = verifyDestinationMatches(targetUri, origSize, origSha)
                if (intact) {
                    deleteDirRecursively(opDir)
                    return if (backend.isMediaStoreUri(targetUri)) {
                        CommitOutcome.PermissionRequired(targetUri, "Permission required to save changes to this file.")
                    } else {
                        CommitOutcome.Aborted("Permission denied while writing audio file.")
                    }
                }
                return rollbackAfterCommitFailure(
                    manifestFile, manifest, targetUri, rollbackFile,
                    "Permission error during commit: ${sec.message}"
                )
            } catch (e: Exception) {
                return rollbackAfterCommitFailure(
                    manifestFile, manifest, targetUri, rollbackFile,
                    "Write failed during commit: ${e.message ?: e.javaClass.simpleName}"
                )
            }

            if (bytesWritten != expectedOutSize) {
                return rollbackAfterCommitFailure(
                    manifestFile, manifest, targetUri, rollbackFile,
                    "Partial destination write detected ($bytesWritten of $expectedOutSize bytes)."
                )
            }

            // Stage 8: Independent post-write destination read-back verification (size + SHA-256)
            val verifiedHash = verifyDestinationAndOptionallyReadBack(
                targetUri = targetUri,
                expectedSize = expectedOutSize,
                expectedSha256 = expectedOutSha,
                verifyDir = opDir,
                ext = ext,
                postVerifyFile = postVerifyDestinationFile
            )
            if (!verifiedHash) {
                return rollbackAfterCommitFailure(
                    manifestFile, manifest, targetUri, rollbackFile,
                    "Destination read-back verification failed after commit."
                )
            }

            // Stage 9: Verified! Mark VERIFIED and clean up rollback + staging directory
            manifest = manifest.copy(
                state = OperationState.VERIFIED,
                updatedAtMs = System.currentTimeMillis()
            )
            writeManifestDurably(manifestFile, manifest)
            deleteDirRecursively(opDir)
            return CommitOutcome.Committed(
                payload = successPayload,
                outputSizeBytes = expectedOutSize,
                outputSha256 = expectedOutSha
            )
        } catch (ce: CancellationException) {
            if (!destinationTouched) {
                deleteDirRecursively(opDir)
            } else if (manifest != null) {
                // Destination was already touched: attempt immediate rollback before propagating cancel
                rollbackAfterCommitFailure(
                    manifestFile, manifest, targetUri, rollbackFile,
                    "Operation cancelled during commit."
                )
            }
            throw ce
        } catch (sec: SecurityException) {
            if (!destinationTouched) {
                deleteDirRecursively(opDir)
                return if (backend.isMediaStoreUri(targetUri)) {
                    CommitOutcome.PermissionRequired(targetUri, "Permission required to modify this audio file.")
                } else {
                    CommitOutcome.Aborted("Permission denied: ${sec.message}")
                }
            }
            return rollbackAfterCommitFailure(
                manifestFile, manifest!!, targetUri, rollbackFile,
                "Security error: ${sec.message}"
            )
        } catch (e: Exception) {
            if (!destinationTouched || manifest == null) {
                deleteDirRecursively(opDir)
                return CommitOutcome.Aborted("Write preparation failed: ${e.message ?: e.javaClass.simpleName}")
            }
            return rollbackAfterCommitFailure(
                manifestFile, manifest, targetUri, rollbackFile,
                "Unexpected commit error: ${e.message ?: e.javaClass.simpleName}"
            )
        }
    }

    /**
     * Scans `journalRootDir` for interrupted operations (e.g. after process kill, crash, or failed
     * rollback) and restores any damaged destinations from their durable rollback copies.
     *
     * Never deletes a rollback copy unless the destination is verified to match either
     * `expectedOutputSha256` (commit finished before cleanup) or `originalSha256` (untouched or
     * verified restored).
     */
    fun recoverInterruptedOperations(): RecoveryReport {
        val key = journalKey()
        val gate = CompletableDeferred<Unit>()
        if (activeRecoveryByJournal.putIfAbsent(key, gate) != null) {
            return RecoveryReport()
        }
        return try {
            recoverInterruptedOperationsInternal()
        } finally {
            gate.complete(Unit)
            activeRecoveryByJournal.remove(key, gate)
        }
    }

    private fun recoverInterruptedOperationsInternal(): RecoveryReport {
        if (!journalRootDir.isDirectory) return RecoveryReport()
        val opDirs = journalRootDir.listFiles()?.filter { it.isDirectory } ?: return RecoveryReport()
        var scanned = 0
        var restored = 0
        var cleanedComplete = 0
        var cleanedUntouched = 0
        var failedRetained = 0
        val details = mutableListOf<String>()

        for (opDir in opDirs) {
            val manifestFile = File(opDir, "manifest.json")
            if (!manifestFile.isFile) {
                // Orphan staging directory with no manifest (interrupted before manifest written)
                deleteDirRecursively(opDir)
                continue
            }
            val manifest = runCatching {
                WriteManifest.fromJsonString(manifestFile.readText(Charsets.UTF_8))
            }.getOrNull()
            if (manifest == null) {
                failedRetained++
                details.add("Retained corrupt manifest directory: ${opDir.name}")
                continue
            }
            scanned++
            val rollbackFile = File(manifest.rollbackPath)

            // Case 1: Already verified before process died during cleanup
            if (manifest.state == OperationState.VERIFIED) {
                deleteDirRecursively(opDir)
                cleanedComplete++
                details.add("Cleaned completed operation ${manifest.opId}")
                continue
            }

            // Case 2: Check if destination already matches expected output (crash right after write finished)
            if (manifest.expectedOutputSha256.isNotBlank() &&
                manifest.expectedOutputSizeBytes > 0L &&
                verifyDestinationMatches(
                    manifest.targetUri,
                    manifest.expectedOutputSizeBytes,
                    manifest.expectedOutputSha256
                )
            ) {
                deleteDirRecursively(opDir)
                cleanedComplete++
                details.add("Confirmed completed commit for ${manifest.displayName} (${manifest.opId})")
                continue
            }

            // Case 3: Check if destination is still untouched and matches original file
            if (verifyDestinationMatches(
                    manifest.targetUri,
                    manifest.originalSizeBytes,
                    manifest.originalSha256
                )
            ) {
                deleteDirRecursively(opDir)
                cleanedUntouched++
                details.add("Destination intact for interrupted ${manifest.displayName} (${manifest.opId}); cleaned staging.")
                continue
            }

            // Case 4: Destination is truncated/corrupted/missing — restore from durable rollback copy!
            if (!rollbackFile.isFile || rollbackFile.length() != manifest.originalSizeBytes) {
                failedRetained++
                val updated = manifest.copy(
                    state = OperationState.RECOVERY_FAILED,
                    updatedAtMs = System.currentTimeMillis(),
                    lastError = "Rollback file missing or size mismatch."
                )
                runCatching { writeManifestDurably(manifestFile, updated) }
                details.add("Recovery failed for ${manifest.displayName}: rollback file missing or truncated.")
                continue
            }

            val rollbackSha = AudioContainerPrimitives.computeFileSha256(rollbackFile)
            if (rollbackSha != manifest.originalSha256) {
                failedRetained++
                val updated = manifest.copy(
                    state = OperationState.RECOVERY_FAILED,
                    updatedAtMs = System.currentTimeMillis(),
                    lastError = "Rollback file checksum mismatch."
                )
                runCatching { writeManifestDurably(manifestFile, updated) }
                details.add("Recovery failed for ${manifest.displayName}: rollback checksum mismatch.")
                continue
            }

            val restoreResult = runCatching {
                val written = streamFileToDestination(rollbackFile, manifest.targetUri)
                written == manifest.originalSizeBytes &&
                    verifyDestinationMatches(manifest.targetUri, manifest.originalSizeBytes, manifest.originalSha256)
            }

            if (restoreResult.getOrDefault(false)) {
                deleteDirRecursively(opDir)
                restored++
                details.add("Restored original audio for ${manifest.displayName} (${manifest.opId}) from rollback.")
                runCatching { Log.i(TAG, "Recovered interrupted audio write: ${manifest.displayName} (${manifest.opId})") }
            } else {
                failedRetained++
                val errMsg = restoreResult.exceptionOrNull()?.message ?: "Restored destination failed checksum verification."
                val updated = manifest.copy(
                    state = OperationState.RECOVERY_FAILED,
                    updatedAtMs = System.currentTimeMillis(),
                    lastError = errMsg
                )
                runCatching { writeManifestDurably(manifestFile, updated) }
                details.add("Recovery incomplete for ${manifest.displayName}; rollback preserved at ${rollbackFile.absolutePath} ($errMsg)")
                runCatching { Log.e(TAG, "Recovery incomplete for ${manifest.opId}: $errMsg (rollback preserved)") }
            }
        }

        return RecoveryReport(
            scannedCount = scanned,
            restoredCount = restored,
            cleanedAlreadyCompleteCount = cleanedComplete,
            cleanedUntouchedCount = cleanedUntouched,
            failedRetainedCount = failedRetained,
            details = details
        )
    }

    fun listPendingManifests(): List<WriteManifest> {
        if (!journalRootDir.isDirectory) return emptyList()
        return journalRootDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val f = File(dir, "manifest.json")
                if (!f.isFile) null else runCatching {
                    WriteManifest.fromJsonString(f.readText(Charsets.UTF_8))
                }.getOrNull()
            }
            .orEmpty()
    }

    sealed interface TransformDecision<out T> {
        data class Ready<T>(val payload: T) : TransformDecision<T>
        data class Abort(val reason: String, val originalPayload: Any? = null) : TransformDecision<Nothing>
    }

    private fun rollbackAfterCommitFailure(
        manifestFile: File,
        manifest: WriteManifest,
        targetUri: String,
        rollbackFile: File,
        failureReason: String
    ): CommitOutcome<Nothing> {
        val opDir = manifestFile.parentFile
        val markedNeedsRecovery = manifest.copy(
            state = OperationState.NEEDS_RECOVERY,
            updatedAtMs = System.currentTimeMillis(),
            lastError = failureReason
        )
        runCatching { writeManifestDurably(manifestFile, markedNeedsRecovery) }

        // Attempt immediate rollback from durable backup
        val restored = runCatching {
            if (!rollbackFile.isFile || rollbackFile.length() != manifest.originalSizeBytes) return@runCatching false
            val written = streamFileToDestination(rollbackFile, targetUri)
            written == manifest.originalSizeBytes &&
                verifyDestinationMatches(targetUri, manifest.originalSizeBytes, manifest.originalSha256)
        }.getOrDefault(false)

        return if (restored) {
            if (opDir != null) deleteDirRecursively(opDir)
            CommitOutcome.FailedRecovered("$failureReason Original file was restored safely.")
        } else {
            val retained = markedNeedsRecovery.copy(
                state = OperationState.RECOVERY_FAILED,
                updatedAtMs = System.currentTimeMillis(),
                lastError = "$failureReason (rollback preserved for next-startup recovery)"
            )
            runCatching { writeManifestDurably(manifestFile, retained) }
            CommitOutcome.FailedRollbackRetained(
                reason = "$failureReason Automatic rollback could not complete; backup was retained for recovery.",
                rollbackPath = rollbackFile.absolutePath
            )
        }
    }

    private fun streamToFileWithSha256(
        inputProvider: () -> InputStream?,
        targetFile: File
    ): Pair<Long, String>? {
        val md = MessageDigest.getInstance("SHA-256")
        var totalBytes = 0L
        val inp = inputProvider() ?: return null
        inp.use { input ->
            FileOutputStream(targetFile).use { out ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    md.update(buf, 0, r)
                    totalBytes += r
                }
                out.flush()
                runCatching { out.fd.sync() }
            }
        }
        val sha = md.digest().joinToString("") { "%02x".format(it) }
        return totalBytes to sha
    }

    private fun streamFileToDestination(srcFile: File, targetUri: String): Long {
        val outStream = backend.openTruncatingOutputStream(targetUri)
            ?: throw IOException("Could not open destination output stream for $targetUri")
        var written = 0L
        outStream.use { out ->
            FileInputStream(srcFile).use { inp ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val r = inp.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                    written += r
                }
            }
            out.flush()
            if (out is FileOutputStream) {
                runCatching { out.fd.sync() }
            }
        }
        return written
    }

    private fun verifyDestinationMatches(targetUri: String, expectedSize: Long, expectedSha256: String): Boolean {
        if (expectedSize <= 0L || expectedSha256.isBlank()) return false
        return runCatching {
            val md = MessageDigest.getInstance("SHA-256")
            var total = 0L
            val inp = backend.openInputStream(targetUri) ?: return false
            inp.use { input ->
                val buf = ByteArray(BUFFER_SIZE)
                while (true) {
                    val r = input.read(buf)
                    if (r <= 0) break
                    md.update(buf, 0, r)
                    total += r
                }
            }
            if (total != expectedSize) return false
            val sha = md.digest().joinToString("") { "%02x".format(it) }
            sha == expectedSha256
        }.getOrDefault(false)
    }

    private fun verifyDestinationAndOptionallyReadBack(
        targetUri: String,
        expectedSize: Long,
        expectedSha256: String,
        verifyDir: File,
        ext: String,
        postVerifyFile: ((File) -> Boolean)?
    ): Boolean {
        if (postVerifyFile == null) {
            return verifyDestinationMatches(targetUri, expectedSize, expectedSha256)
        }
        val readBackFile = File(verifyDir, "verify_readback.$ext")
        return try {
            val (size, sha) = streamToFileWithSha256(
                inputProvider = { backend.openInputStream(targetUri) },
                targetFile = readBackFile
            ) ?: return false
            if (size != expectedSize || sha != expectedSha256) return false
            postVerifyFile(readBackFile)
        } finally {
            readBackFile.delete()
        }
    }

    private fun writeManifestDurably(manifestFile: File, manifest: WriteManifest) {
        val parent = manifestFile.parentFile ?: return
        parent.mkdirs()
        val tmp = File(parent, "${manifestFile.name}.tmp")
        val bytes = manifest.toJsonString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { out ->
            out.write(bytes)
            out.flush()
            runCatching { out.fd.sync() }
        }
        if (!tmp.renameTo(manifestFile)) {
            FileOutputStream(manifestFile).use { out ->
                out.write(bytes)
                out.flush()
                runCatching { out.fd.sync() }
            }
            tmp.delete()
        }
    }

    private fun extensionFor(format: AudioContainerFormat, displayName: String): String = when (format) {
        AudioContainerFormat.MP3 -> "mp3"
        AudioContainerFormat.FLAC -> "flac"
        AudioContainerFormat.MP4_M4A -> "m4a"
        AudioContainerFormat.OGG_OPUS -> if (displayName.endsWith(".opus", true)) "opus" else "ogg"
        AudioContainerFormat.UNSUPPORTED -> "tmp"
    }

    private fun deleteDirRecursively(dir: File) {
        runCatching { dir.deleteRecursively() }
    }
}

/**
 * Production Android [SafeAudioWritePipeline.AudioUriIoBackend] backed by `ContentResolver`,
 * `MediaStore`, and `StorageManager`.
 */
class AndroidContentResolverBackend(private val appContext: Context) : SafeAudioWritePipeline.AudioUriIoBackend {

    override fun isMediaStoreUri(targetUri: String): Boolean {
        val uri = runCatching { Uri.parse(targetUri) }.getOrNull() ?: return false
        return uri.scheme.equals("content", true) && uri.authority.equals(MediaStore.AUTHORITY, true)
    }

    override fun canOpenReadWrite(targetUri: String): Boolean {
        val uri = runCatching { Uri.parse(targetUri) }.getOrNull() ?: return false
        if (uri.scheme.equals("file", true) || uri.scheme.isNullOrBlank()) {
            val f = File(uri.path ?: targetUri)
            return f.isFile && f.canRead() && f.canWrite()
        }
        return runCatching {
            appContext.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
        }.getOrDefault(false)
    }

    override fun openInputStream(targetUri: String): InputStream? {
        val uri = runCatching { Uri.parse(targetUri) }.getOrNull() ?: return null
        if (uri.scheme.equals("file", true) || uri.scheme.isNullOrBlank()) {
            val f = File(uri.path ?: targetUri)
            return if (f.isFile) FileInputStream(f) else null
        }
        return appContext.contentResolver.openInputStream(uri)
    }

    override fun openTruncatingOutputStream(targetUri: String): OutputStream? {
        val uri = runCatching { Uri.parse(targetUri) }.getOrNull() ?: return null
        if (uri.scheme.equals("file", true) || uri.scheme.isNullOrBlank()) {
            val f = File(uri.path ?: targetUri)
            return FileOutputStream(f, false)
        }
        return appContext.contentResolver.openOutputStream(uri, "wt")
    }

    override fun availableDestinationBytes(targetUri: String): Long? {
        val uri = runCatching { Uri.parse(targetUri) }.getOrNull() ?: return null
        if (uri.scheme.equals("file", true) || uri.scheme.isNullOrBlank()) {
            return File(uri.path ?: targetUri).parentFile?.usableSpace
        }
        // For MediaStore external audio, query StorageManager / external storage usable bytes
        if (isMediaStoreUri(targetUri)) {
            return runCatching {
                val sm = appContext.getSystemService(StorageManager::class.java)
                val extDir = Environment.getExternalStorageDirectory()
                if (sm != null && extDir != null) {
                    val uuid = sm.getUuidForPath(extDir)
                    sm.getAllocatableBytes(uuid)
                } else {
                    extDir?.usableSpace
                }
            }.getOrNull() ?: runCatching { Environment.getExternalStorageDirectory()?.usableSpace }.getOrNull()
        }
        // Generic SAF providers may represent cloud/remote volumes with unknown capacity
        return null
    }
}

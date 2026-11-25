package com.example.mdownload.engine

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/**
 * Validates untrusted output paths emitted by external `yt-dlp` subprocesses (`HF_FILE:` markers)
 * before HiFiDeck reads, tags, retains, or exports them into shared storage (`SEC-01`).
 *
 * Security invariants:
 * - Path-aware canonical containment via `java.nio.file.Path.startsWith(Path)` (immune to sibling
 *   prefix collisions such as `/jobs/job123` vs `/jobs/job123_backup/private.mp3`).
 * - Resolves relative paths strictly against [workDir] (never against process CWD).
 * - Rejects NUL bytes, embedded line breaks, missing files, directories, symlinks, and non-regular
 *   special files (`LinkOption.NOFOLLOW_LINKS`).
 */
object OutputPathGuard {

    sealed interface ValidationResult {
        data class Valid(val canonicalFile: File) : ValidationResult
        data class Rejected(val reason: String) : ValidationResult
    }

    fun parseMarkerLine(line: String): String? {
        if (!line.startsWith("HF_FILE:")) return null
        val raw = line.removePrefix("HF_FILE:").trim()
        if (raw.isEmpty() || raw.contains('\u0000') || raw.contains('\n') || raw.contains('\r')) {
            return null
        }
        return raw
    }

    /**
     * Validates that [reportedPath] resolves to an existing regular file strictly contained inside
     * [workDir] with no symlink escape or traversal.
     */
    fun validateCandidate(reportedPath: String?, workDir: File): ValidationResult {
        val raw = reportedPath?.trim().orEmpty()
        if (raw.isEmpty()) return ValidationResult.Rejected("Empty output path.")
        if (raw.contains('\u0000') || raw.contains('\n') || raw.contains('\r')) {
            return ValidationResult.Rejected("Malformed output path containing control characters.")
        }

        val canonicalWorkDir = runCatching { workDir.canonicalFile }.getOrNull()
            ?: return ValidationResult.Rejected("Could not resolve canonical job working directory.")
        val canonicalWorkPath = canonicalWorkDir.toPath().normalize()
        if (!Files.isDirectory(canonicalWorkPath)) {
            return ValidationResult.Rejected("Authorized job working directory does not exist.")
        }

        val rawFile = File(raw)
        val candidateFile = if (rawFile.isAbsolute) rawFile else File(canonicalWorkDir, raw)
        val candidatePath = candidateFile.toPath()

        // Reject direct symlinks before canonicalization
        if (Files.isSymbolicLink(candidatePath)) {
            return ValidationResult.Rejected("Symlink output paths are not permitted.")
        }

        val canonicalCandidate = runCatching { candidateFile.canonicalFile }.getOrNull()
            ?: return ValidationResult.Rejected("Could not resolve canonical output file path.")
        val canonicalCandidatePath = canonicalCandidate.toPath().normalize()

        // Path-aware containment check (NOT string-prefix!)
        if (canonicalCandidatePath == canonicalWorkPath || !canonicalCandidatePath.startsWith(canonicalWorkPath)) {
            return ValidationResult.Rejected("Output path escapes authorized job working directory.")
        }

        // Verify every intermediate path component inside workDir is not a symlink
        var cursor = candidatePath.normalize()
        while (cursor != canonicalWorkPath && cursor.startsWith(canonicalWorkPath)) {
            if (Files.isSymbolicLink(cursor)) {
                return ValidationResult.Rejected("Symlink component inside output path is not permitted.")
            }
            cursor = cursor.parent ?: break
        }

        if (!Files.exists(canonicalCandidatePath, LinkOption.NOFOLLOW_LINKS)) {
            return ValidationResult.Rejected("Reported output file does not exist.")
        }
        if (Files.isDirectory(canonicalCandidatePath, LinkOption.NOFOLLOW_LINKS)) {
            return ValidationResult.Rejected("Reported output path is a directory, not a file.")
        }
        if (!Files.isRegularFile(canonicalCandidatePath, LinkOption.NOFOLLOW_LINKS)) {
            return ValidationResult.Rejected("Reported output path is not a regular file.")
        }

        return ValidationResult.Valid(canonicalCandidate)
    }

    /**
     * Resolves a validated audio file produced by yt-dlp using:
     * 1. Live `HF_FILE:` marker captured during execution (strictly validated).
     * 2. Last `HF_FILE:` marker in stdout (strictly validated).
     * 3. Fallback scan of regular audio files strictly inside [workDir].
     */
    fun resolveProducedFile(
        markerPath: String?,
        stdout: String?,
        workDir: File,
        onRejected: ((String) -> Unit)? = null
    ): File? {
        if (!markerPath.isNullOrBlank()) {
            when (val res = validateCandidate(markerPath, workDir)) {
                is ValidationResult.Valid -> return res.canonicalFile
                is ValidationResult.Rejected -> onRejected?.invoke("Ignored untrusted HF_FILE marker: ${res.reason}")
            }
        }

        val stdoutMarker = stdout?.lines()
            ?.lastOrNull { it.startsWith("HF_FILE:") }
            ?.let(::parseMarkerLine)
        if (!stdoutMarker.isNullOrBlank()) {
            when (val res = validateCandidate(stdoutMarker, workDir)) {
                is ValidationResult.Valid -> return res.canonicalFile
                is ValidationResult.Rejected -> onRejected?.invoke("Ignored untrusted stdout HF_FILE marker: ${res.reason}")
            }
        }

        val audioExts = setOf("mp3", "m4a", "opus", "ogg", "aac", "flac", "wav", "webm", "mp4")
        return workDir.listFiles()
            ?.mapNotNull { entry ->
                if (entry.extension.lowercase() !in audioExts) return@mapNotNull null
                when (val res = validateCandidate(entry.absolutePath, workDir)) {
                    is ValidationResult.Valid -> res.canonicalFile.takeIf { it.length() > 1024L }
                    is ValidationResult.Rejected -> null
                }
            }
            ?.maxByOrNull { it.lastModified() }
    }
}

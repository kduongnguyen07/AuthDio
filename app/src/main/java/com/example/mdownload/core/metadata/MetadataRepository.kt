package com.example.mdownload.core.metadata

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.LyricsTagIO
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Safe, Scoped-Storage-compliant, rollback-protected metadata read/write pipeline.
 *
 * Pipeline stages:
 * 1. Load existing tags + capture [FileSnapshot] (size & modified time)
 * 2. Validate draft via [MetadataValidator]
 * 3. Check write permission (`file://` canWrite or `content://` rw probe; returns
 *    [MetadataWriteResult.RequiresWritePermission] so the UI can invoke `MediaStore.createWriteRequest`)
 * 4. Verify source file has not been modified concurrently since opening
 * 5. Delegate `content://` writes to [SafeAudioWritePipeline] (durable rollback copy + `fd.sync()`
 *    manifest -> generate & validate replacement -> verify storage headroom -> bounded streaming
 *    commit -> post-write SHA-256 & tag read-back verification -> rollback cleanup only after
 *    confirmed verification; automatic rollback & next-startup recovery on failure).
 * 6. Update MediaStore metadata columns so MediaStore & library queries reflect edits immediately.
 */
class MetadataRepository(
    private val appContext: Context,
    private val writePipeline: SafeAudioWritePipeline = SafeAudioWritePipeline.forContext(appContext)
) {

    fun recoverInterruptedWrites(): SafeAudioWritePipeline.RecoveryReport =
        writePipeline.recoverInterruptedOperations()

    suspend fun loadEditableMetadata(track: LibraryTrack): Pair<EditableTrackMetadata, FileSnapshot> =
        withContext(Dispatchers.IO) {
            val snapshot = captureSnapshot(track)
            val meta = MetadataTagIO.readMetadataFromUri(
                context = appContext,
                uriString = track.uri,
                displayName = track.displayName,
                mimeType = track.mimeType,
                fallbackTitle = track.title,
                fallbackArtist = track.artist,
                fallbackAlbum = track.album
            )
            meta to snapshot
        }

    suspend fun prepareArtwork(imageUri: Uri, squareCrop: Boolean): Pair<ByteArray, String>? =
        withContext(Dispatchers.IO) {
            MetadataTagIO.prepareArtworkForEmbedding(appContext, imageUri, squareCrop)
        }

    suspend fun saveSingleMetadata(
        track: LibraryTrack,
        draft: EditableTrackMetadata,
        expectedSnapshot: FileSnapshot?,
        isDownloadInProgress: Boolean
    ): MetadataWriteResult = withContext(Dispatchers.IO) {
        if (isDownloadInProgress) {
            return@withContext MetadataWriteResult.Conflict(
                "Cannot edit metadata while a download is active."
            )
        }

        val validation = MetadataValidator.validateSingle(draft)
        if (!validation.isValid) {
            return@withContext MetadataWriteResult.Failed(
                validation.fieldErrors.values.firstOrNull() ?: "Invalid metadata values."
            )
        }

        val format = LyricsTagIO.detectFormat(track.displayName, track.mimeType)
        if (format == AudioContainerFormat.UNSUPPORTED) {
            return@withContext MetadataWriteResult.Unsupported(
                "Editing tags for this audio format is not supported."
            )
        }

        val uri = runCatching { Uri.parse(track.uri) }.getOrNull()
            ?: return@withContext MetadataWriteResult.Failed("Invalid audio URI.")

        // Concurrent modification detection
        if (expectedSnapshot != null) {
            val currentSnap = captureSnapshot(track)
            if (currentSnap.sizeBytes > 0L && expectedSnapshot.sizeBytes > 0L &&
                (currentSnap.sizeBytes != expectedSnapshot.sizeBytes ||
                    (expectedSnapshot.dateModifiedSec > 0L && currentSnap.dateModifiedSec != expectedSnapshot.dateModifiedSec))
            ) {
                return@withContext MetadataWriteResult.Conflict(
                    "The audio file changed on disk while editing. Reopen the editor to avoid overwriting changes."
                )
            }
        }

        // Handle local file:// URIs via atomic sibling rename (with fsync)
        if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
            val target = File(uri.path ?: track.uri)
            if (!target.isFile || !target.canWrite()) {
                return@withContext MetadataWriteResult.Failed("Audio file is read-only or missing.")
            }
            return@withContext commitLocalFile(target, format, draft)
        }

        // Check MediaStore / SAF write permission
        if (!canOpenReadWrite(uri)) {
            return@withContext if (isMediaStoreUri(uri)) {
                MetadataWriteResult.RequiresWritePermission(
                    uris = listOf(uri),
                    message = "Grant permission to modify this audio file."
                )
            } else {
                MetadataWriteResult.Failed("Android storage permission does not allow modifying this file.")
            }
        }

        return@withContext commitContentUri(uri, track, format, draft)
    }

    /**
     * Checks which tracks in a batch require Android MediaStore write authorization before running.
     */
    suspend fun findUrisRequiringWritePermission(tracks: List<LibraryTrack>): List<Uri> =
        withContext(Dispatchers.IO) {
            tracks.mapNotNull { track ->
                val uri = runCatching { Uri.parse(track.uri) }.getOrNull() ?: return@mapNotNull null
                if (isMediaStoreUri(uri) && !canOpenReadWrite(uri)) uri else null
            }
        }

    suspend fun saveBatchMetadata(
        tracks: List<LibraryTrack>,
        patch: BatchMetadataPatch,
        isDownloadInProgress: Boolean,
        onTrackCommitted: (track: LibraryTrack, appliedDraft: EditableTrackMetadata) -> Unit = { _, _ -> },
        onProgress: (completed: Int, total: Int, currentTrackTitle: String) -> Unit
    ): BatchMetadataResult = withContext(Dispatchers.IO) {
        val validation = MetadataValidator.validateBatch(patch)
        if (!validation.isValid) {
            val err = MetadataWriteResult.Failed(
                validation.fieldErrors.values.firstOrNull() ?: "Invalid batch metadata."
            )
            return@withContext BatchMetadataResult(tracks.map { it to err }, cancelled = false)
        }
        if (isDownloadInProgress) {
            val err = MetadataWriteResult.Conflict("Stop active downloads before batch editing metadata.")
            return@withContext BatchMetadataResult(tracks.map { it to err }, cancelled = false)
        }

        val results = mutableListOf<Pair<LibraryTrack, MetadataWriteResult>>()
        var cancelled = false
        try {
            tracks.forEachIndexed { index, track ->
                currentCoroutineContext().ensureActive()
                onProgress(index, tracks.size, track.title)
                val (existing, snapshot) = loadEditableMetadata(track)
                val merged = patch.applyTo(existing)
                val res = saveSingleMetadata(
                    track = track,
                    draft = merged,
                    expectedSnapshot = snapshot,
                    isDownloadInProgress = false
                )
                results.add(track to res)
                if (res is MetadataWriteResult.Success) {
                    onTrackCommitted(track, merged)
                }
            }
            onProgress(tracks.size, tracks.size, "")
        } catch (_: CancellationException) {
            // Preserve all already-committed results honestly so callers can synchronize them
            // without claiming the full batch succeeded.
            cancelled = true
        }
        BatchMetadataResult(trackResults = results, cancelled = cancelled)
    }

    private fun commitLocalFile(
        target: File,
        format: AudioContainerFormat,
        draft: EditableTrackMetadata
    ): MetadataWriteResult {
        val parent = target.parentFile ?: return MetadataWriteResult.Failed("Invalid file path.")
        val tempOut = File(parent, ".${target.name}.meta_tmp_${System.nanoTime()}")
        return try {
            val res = MetadataTagIO.writeMetadataToFilePair(appContext, target, tempOut, format, draft)
            if (res !is MetadataWriteResult.Success) {
                tempOut.delete()
                return res
            }
            if (!tempOut.renameTo(target)) {
                FileInputStream(tempOut).use { inp ->
                    FileOutputStream(target).use { out ->
                        inp.copyTo(out, 32 * 1024)
                        out.flush()
                        runCatching { out.fd.sync() }
                    }
                }
                tempOut.delete()
            }
            res
        } catch (e: Exception) {
            tempOut.delete()
            MetadataWriteResult.Failed("Failed to commit metadata safely: ${e.message}")
        }
    }

    private suspend fun commitContentUri(
        uri: Uri,
        track: LibraryTrack,
        format: AudioContainerFormat,
        draft: EditableTrackMetadata
    ): MetadataWriteResult {
        val outcome = writePipeline.executeSafeWrite(
            targetUri = uri.toString(),
            displayName = track.displayName,
            format = format,
            operationType = SafeAudioWritePipeline.OperationType.METADATA,
            transformFile = { srcFile, dstFile ->
                when (val res = MetadataTagIO.writeMetadataToFilePair(appContext, srcFile, dstFile, format, draft)) {
                    is MetadataWriteResult.Success -> SafeAudioWritePipeline.TransformDecision.Ready(res)
                    is MetadataWriteResult.Unsupported ->
                        SafeAudioWritePipeline.TransformDecision.Abort(res.reason, res)
                    is MetadataWriteResult.Failed ->
                        SafeAudioWritePipeline.TransformDecision.Abort(res.reason, res)
                    is MetadataWriteResult.Conflict ->
                        SafeAudioWritePipeline.TransformDecision.Abort(res.reason, res)
                    is MetadataWriteResult.RequiresWritePermission ->
                        SafeAudioWritePipeline.TransformDecision.Abort(res.message, res)
                }
            },
            postVerifyDestinationFile = { readBackFile ->
                val rb = MetadataTagIO.readMetadataFromFile(readBackFile, track.displayName, track.mimeType)
                val expectedTitle = draft.title.trim()
                rb != null && (expectedTitle.isEmpty() || rb.title.trim() == expectedTitle)
            }
        )

        return when (outcome) {
            is SafeAudioWritePipeline.CommitOutcome.Committed -> {
                syncMediaStoreColumns(uri, draft)
                outcome.payload
            }
            is SafeAudioWritePipeline.CommitOutcome.PermissionRequired ->
                MetadataWriteResult.RequiresWritePermission(
                    uris = listOf(uri),
                    message = outcome.message
                )
            is SafeAudioWritePipeline.CommitOutcome.Aborted ->
                (outcome.payload as? MetadataWriteResult) ?: MetadataWriteResult.Failed(outcome.reason)
            is SafeAudioWritePipeline.CommitOutcome.FailedRecovered ->
                MetadataWriteResult.Failed(outcome.reason)
            is SafeAudioWritePipeline.CommitOutcome.FailedRollbackRetained ->
                MetadataWriteResult.Failed(outcome.reason)
        }
    }

    private fun syncMediaStoreColumns(uri: Uri, draft: EditableTrackMetadata) {
        if (!isMediaStoreUri(uri)) return
        runCatching {
            val cv = ContentValues().apply {
                if (draft.title.isNotBlank()) put(MediaStore.Audio.Media.TITLE, draft.title.trim())
                if (draft.artist.isNotBlank()) put(MediaStore.Audio.Media.ARTIST, draft.artist.trim())
                if (draft.album.isNotBlank()) put(MediaStore.Audio.Media.ALBUM, draft.album.trim())
                if (draft.albumArtist.isNotBlank()) put(MediaStore.Audio.Media.ALBUM_ARTIST, draft.albumArtist.trim())
                if (draft.genre.isNotBlank()) put(MediaStore.Audio.Media.GENRE, draft.genre.trim())
                draft.year.trim().take(4).toIntOrNull()?.let { put(MediaStore.Audio.Media.YEAR, it) }
                draft.trackNumber.trim().toIntOrNull()?.let { put(MediaStore.Audio.Media.TRACK, it) }
            }
            if (cv.size() > 0) {
                appContext.contentResolver.update(uri, cv, null, null)
            }
        }
    }

    private fun canOpenReadWrite(uri: Uri): Boolean = runCatching {
        appContext.contentResolver.openFileDescriptor(uri, "rw")?.use { true } ?: false
    }.getOrDefault(false)

    private fun isMediaStoreUri(uri: Uri): Boolean =
        uri.scheme.equals("content", true) && uri.authority.equals(MediaStore.AUTHORITY, true)

    private fun captureSnapshot(track: LibraryTrack): FileSnapshot {
        val uri = runCatching { Uri.parse(track.uri) }.getOrNull()
        if (uri != null && (uri.scheme.equals("file", true) || uri.scheme.isNullOrBlank())) {
            val f = File(uri.path ?: track.uri)
            if (f.isFile) return FileSnapshot(track.uri, f.length(), f.lastModified() / 1000L)
        }
        if (uri != null && isMediaStoreUri(uri)) {
            runCatching {
                appContext.contentResolver.query(
                    uri,
                    arrayOf(MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.DATE_MODIFIED),
                    null,
                    null,
                    null
                )?.use { c ->
                    if (c.moveToFirst()) {
                        val size = c.getLong(0)
                        val mod = c.getLong(1)
                        return FileSnapshot(track.uri, size, mod)
                    }
                }
            }
        }
        return FileSnapshot(track.uri, track.sizeBytes, track.dateModifiedSec)
    }
}

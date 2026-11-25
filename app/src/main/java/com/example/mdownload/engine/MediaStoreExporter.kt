package com.example.mdownload.engine

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import com.example.mdownload.core.AudioTagFingerprint
import com.example.mdownload.core.JobOptions
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.TrackMetadata
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class VerifiedAudioInfo(
    val durationMs: Long,
    val title: String,
    val artist: String,
    val album: String,
    val year: String = "",
    val bitrate: Int
)

data class ExportedAudioResult(
    val uri: String,
    val displayName: String,
    val sizeBytes: Long
)

/**
 * Verifies produced audio files using Android's MediaMetadataRetriever and exports them
 * atomically into either default public Music/MDownload (MediaStore) or a user-selected SAF folder,
 * while syncing library metadata and hidden YouTube video IDs from the active folder.
 */
class MediaStoreExporter(private val appContext: Context) {

    companion object {
        const val PUBLIC_SUBFOLDER = "MDownload"
        val RELATIVE_MUSIC_PATH: String = "${Environment.DIRECTORY_MUSIC}/$PUBLIC_SUBFOLDER"
        private const val MAX_SAF_SCAN_DEPTH = 3
        private val VALID_AUDIO_EXTENSIONS = setOf(
            "mp3", "m4a", "opus", "ogg", "aac", "flac", "wav", "webm", "mp4"
        )

        fun formatTreeDocumentId(docId: String): String {
            val trimmed = docId.trim()
            if (trimmed.isEmpty()) return "Custom folder"
            val colon = trimmed.indexOf(':')
            if (colon < 0) return trimmed.trim('/')
            val volume = trimmed.substring(0, colon)
            val path = trimmed.substring(colon + 1).trim('/')
            return when {
                volume.equals("primary", ignoreCase = true) ->
                    if (path.isBlank()) "Internal storage" else path
                path.isBlank() -> "SD Card ($volume)"
                else -> "SD Card/$path"
            }
        }
    }

    private data class CachedSafTrack(
        val sizeBytes: Long,
        val lastModifiedMs: Long,
        val track: LibraryTrack
    )

    // Cache hidden-metadata videoId per MediaStore _ID + sizeBytes to keep library scans fast.
    private val hiddenIdCache = ConcurrentHashMap<Long, Pair<Long, String>>()

    // Cache full SAF track metadata per document URI + (sizeBytes, lastModifiedMs) for fast folder sync.
    private val safTrackCache = ConcurrentHashMap<String, CachedSafTrack>()

    fun formatTreeLabel(treeUri: Uri): String {
        val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull().orEmpty()
        return if (docId.isNotBlank()) {
            formatTreeDocumentId(docId)
        } else {
            treeUri.lastPathSegment?.ifBlank { "Custom folder" } ?: "Custom folder"
        }
    }

    fun hasValidTreePermission(treeUriString: String): Boolean {
        if (treeUriString.isBlank()) return false
        val uri = runCatching { Uri.parse(treeUriString) }.getOrNull() ?: return false
        val persisted = appContext.contentResolver.persistedUriPermissions.any {
            it.uri == uri && it.isReadPermission && it.isWritePermission
        }
        if (!persisted) return false
        return runCatching {
            val docId = DocumentsContract.getTreeDocumentId(uri)
            val rootUri = DocumentsContract.buildDocumentUriUsingTree(uri, docId)
            appContext.contentResolver.query(
                rootUri,
                arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID),
                null,
                null,
                null
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    fun verifyAudioFile(file: File): VerifiedAudioInfo? {
        if (!file.isFile || file.length() < 1024L) return null
        val ext = file.extension.lowercase(Locale.US)
        if (ext !in VALID_AUDIO_EXTENSIONS) return null

        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            if (hasAudio != "yes" || durationMs <= 0L) {
                null
            } else {
                VerifiedAudioInfo(
                    durationMs = durationMs,
                    title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty(),
                    artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                        ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty(),
                    album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty(),
                    year = TextUtils.extractYear(
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_YEAR),
                        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DATE)
                    ),
                    bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                        ?.toIntOrNull() ?: 0
                )
            }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    fun exportToPublicMusic(
        sourceFile: File,
        videoId: String?,
        options: JobOptions,
        metadataOverride: TrackMetadata,
        verifiedInfo: VerifiedAudioInfo,
        fallbackTitle: String,
        fallbackArtist: String,
        customTreeUri: String = ""
    ): ExportedAudioResult {
        val cleanMeta = TextUtils.cleanMetadata(metadataOverride)
        val rawTitle = cleanMeta.title
            .ifBlank { verifiedInfo.title }
            .ifBlank { fallbackTitle }
        val finalTitle = if (options.asciiNames) TextUtils.asciiText(rawTitle).ifBlank { rawTitle } else rawTitle
        val rawArtist = cleanMeta.artist
            .ifBlank { verifiedInfo.artist }
            .ifBlank { fallbackArtist }
            .ifBlank { "Unknown Artist" }
        val finalArtist = if (options.asciiNames) TextUtils.asciiText(rawArtist).ifBlank { rawArtist } else rawArtist
        val rawAlbum = cleanMeta.album.ifBlank { verifiedInfo.album }
        val finalAlbum = if (options.asciiNames) TextUtils.asciiText(rawAlbum) else rawAlbum
        val finalYear = TextUtils.extractYear(cleanMeta.year, verifiedInfo.year).toIntOrNull()

        val displayName = TextUtils.buildExportFileName(
            title = rawTitle,
            videoId = videoId,
            extension = sourceFile.extension,
            options = options
        )
        val mimeType = mimeTypeForExtension(sourceFile.extension)

        if (customTreeUri.isNotBlank()) {
            return exportToSafTree(
                treeUri = Uri.parse(customTreeUri),
                sourceFile = sourceFile,
                displayName = displayName,
                mimeType = mimeType,
                replaceExisting = !options.skipDuplicates
            )
        }

        val resolver = appContext.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
            put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_MUSIC_PATH)
            put(MediaStore.Audio.Media.TITLE, finalTitle)
            put(MediaStore.Audio.Media.ARTIST, finalArtist)
            if (finalAlbum.isNotBlank()) {
                put(MediaStore.Audio.Media.ALBUM, finalAlbum)
            }
            if (finalYear != null) {
                put(MediaStore.Audio.Media.YEAR, finalYear)
            }
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }

        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val insertedUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create MediaStore entry in Music/$PUBLIC_SUBFOLDER")

        var copiedBytes = 0L
        try {
            resolver.openOutputStream(insertedUri, "w")?.use { output ->
                sourceFile.inputStream().use { input ->
                    copiedBytes = input.copyTo(output)
                }
                output.flush()
            } ?: throw IllegalStateException("Failed to open MediaStore output stream.")

            if (copiedBytes != sourceFile.length() || copiedBytes < 1024L) {
                throw IllegalStateException("Verified export byte count mismatch ($copiedBytes vs ${sourceFile.length()}).")
            }

            val copiedHash = resolver.openInputStream(insertedUri)?.let { sha256(it) }
                ?: error("Could not verify destination audio")
            check(sha256(sourceFile.inputStream()).contentEquals(copiedHash)) { "Destination audio checksum mismatch" }

            val publish = ContentValues().apply {
                put(MediaStore.Audio.Media.IS_PENDING, 0)
            }
            check(resolver.update(insertedUri, publish, null, null) > 0) { "Could not publish verified audio" }
            if (!options.skipDuplicates) deleteExistingByDisplayName(displayName, insertedUri)
            return ExportedAudioResult(
                uri = insertedUri.toString(),
                displayName = displayName,
                sizeBytes = copiedBytes
            )
        } catch (e: Exception) {
            runCatching { resolver.delete(insertedUri, null, null) }
            throw e
        }
    }

    private fun exportToSafTree(
        treeUri: Uri,
        sourceFile: File,
        displayName: String,
        mimeType: String,
        replaceExisting: Boolean
    ): ExportedAudioResult {
        check(hasValidTreePermission(treeUri.toString())) {
            "Selected storage folder is unavailable. Please choose the folder again in Settings."
        }
        val resolver = appContext.contentResolver
        val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, treeDocId)

        val existingMatch = if (replaceExisting) {
            findDirectChildByName(treeUri, treeDocId, displayName)
        } else null

        val tempName = ".mdownload_tmp_${System.currentTimeMillis()}_$displayName"
        var createdUri = DocumentsContract.createDocument(resolver, parentDocUri, mimeType, tempName)
            ?: DocumentsContract.createDocument(resolver, parentDocUri, mimeType, displayName)
            ?: throw IllegalStateException("Could not create audio file in selected folder.")

        var copiedBytes = 0L
        try {
            resolver.openOutputStream(createdUri, "w")?.use { output ->
                sourceFile.inputStream().use { input ->
                    copiedBytes = input.copyTo(output)
                }
                output.flush()
            } ?: throw IllegalStateException("Failed to open output stream in selected folder.")

            if (copiedBytes != sourceFile.length() || copiedBytes < 1024L) {
                throw IllegalStateException("Verified export byte count mismatch ($copiedBytes vs ${sourceFile.length()}).")
            }

            val copiedHash = resolver.openInputStream(createdUri)?.let { sha256(it) }
                ?: error("Could not verify destination audio")
            check(sha256(sourceFile.inputStream()).contentEquals(copiedHash)) { "Destination audio checksum mismatch" }

            if (existingMatch != null) {
                runCatching {
                    DocumentsContract.deleteDocument(resolver, existingMatch)
                    safTrackCache.remove(existingMatch.toString())
                }
            }

            // Rename from temp name to the clean final displayName atomically after verification.
            val renamed = runCatching {
                DocumentsContract.renameDocument(resolver, createdUri, displayName)
            }.getOrNull()
            if (renamed != null) {
                createdUri = renamed
            }
            safTrackCache.remove(createdUri.toString())

            return ExportedAudioResult(
                uri = createdUri.toString(),
                displayName = displayName,
                sizeBytes = copiedBytes
            )
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, createdUri) }
            throw e
        }
    }

    private fun findDirectChildByName(treeUri: Uri, dirDocId: String, displayName: String): Uri? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        return runCatching {
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameCol) == displayName) {
                        return@use DocumentsContract.buildDocumentUriUsingTree(treeUri, cursor.getString(idCol))
                    }
                }
                null
            }
        }.getOrNull()
    }

    fun hasExistingExport(videoId: String?, options: JobOptions, customTreeUri: String = ""): Boolean {
        return hasExistingVideoId(videoId, customTreeUri)
    }

    /**
     * Checks whether a track with the given YouTube video code already exists in the active folder
     * (either the selected SAF folder or default Music/MDownload), matching filename `[videoId]`
     * or the hidden `MDOWNLOAD_YT_ID:<id>` tag embedded inside the audio file metadata.
     */
    fun hasExistingVideoId(videoId: String?, customTreeUri: String = ""): Boolean {
        if (videoId.isNullOrBlank()) return false
        val target = videoId.trim()
        if (customTreeUri.isNotBlank() && hasValidTreePermission(customTreeUri)) {
            return scanSafTracks(Uri.parse(customTreeUri)).any { it.videoId == target }
        }

        val resolver = appContext.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.SIZE
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%$PUBLIC_SUBFOLDER%")

        return runCatching {
            resolver.query(collection, projection, selection, selectionArgs, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol).orEmpty()
                    val sizeBytes = cursor.getLong(sizeCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val found = resolveVideoId(id, contentUri, name, sizeBytes)
                    if (found == target) return@use true
                }
                false
            } ?: false
        }.getOrDefault(false)
    }

    private fun resolveVideoId(id: Long, contentUri: Uri, displayName: String, sizeBytes: Long): String {
        AudioTagFingerprint.extractVideoIdFromDisplayName(displayName)?.let { return it }
        val cached = hiddenIdCache[id]
        if (cached != null && cached.first == sizeBytes) {
            return cached.second
        }
        val fromTag = AudioTagFingerprint.extractVideoIdFromUri(appContext, contentUri, sizeBytes).orEmpty()
        hiddenIdCache[id] = sizeBytes to fromTag
        return fromTag
    }

    fun scanLibraryTracks(customTreeUri: String = ""): List<LibraryTrack> {
        if (customTreeUri.isNotBlank() && hasValidTreePermission(customTreeUri)) {
            return scanSafTracks(Uri.parse(customTreeUri))
        }
        return scanDefaultMediaStoreTracks()
    }

    private fun scanSafTracks(treeUri: Uri): List<LibraryTrack> {
        val rootDocId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return emptyList()
        val resolver = appContext.contentResolver
        val tracks = mutableListOf<LibraryTrack>()
        val visitedDirs = HashSet<String>()
        val queue = ArrayDeque<Pair<String, Int>>()
        queue.add(rootDocId to 0)
        visitedDirs.add(rootDocId)

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        while (queue.isNotEmpty()) {
            val (dirDocId, depth) = queue.removeFirst()
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, dirDocId)
            runCatching {
                resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                    if (idCol < 0 || nameCol < 0) return@use

                    while (cursor.moveToNext()) {
                        val docId = cursor.getString(idCol) ?: continue
                        val displayName = cursor.getString(nameCol).orEmpty()
                        if (displayName.startsWith(".mdownload_tmp_")) continue
                        val mimeType = if (mimeCol >= 0) cursor.getString(mimeCol).orEmpty() else ""

                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            if (depth < MAX_SAF_SCAN_DEPTH && visitedDirs.add(docId)) {
                                queue.add(docId to (depth + 1))
                            }
                            continue
                        }

                        val ext = displayName.substringAfterLast('.', "").lowercase(Locale.US)
                        val isAudio = ext in VALID_AUDIO_EXTENSIONS || mimeType.startsWith("audio/")
                        if (!isAudio) continue

                        val sizeBytes = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L
                        if (sizeBytes < 1024L) continue
                        val lastModMs = if (modCol >= 0) cursor.getLong(modCol) else 0L

                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        val uriStr = docUri.toString()
                        val cached = safTrackCache[uriStr]
                        if (cached != null && cached.sizeBytes == sizeBytes && cached.lastModifiedMs == lastModMs) {
                            tracks.add(cached.track)
                            continue
                        }

                        val track = extractSafTrack(
                            docUri = docUri,
                            displayName = displayName,
                            mimeType = mimeType.ifBlank { mimeTypeForExtension(ext) },
                            sizeBytes = sizeBytes,
                            lastModifiedMs = lastModMs
                        )
                        safTrackCache[uriStr] = CachedSafTrack(sizeBytes, lastModMs, track)
                        tracks.add(track)
                    }
                }
            }
        }

        return tracks.sortedByDescending { it.dateAddedSec }
    }

    private fun extractSafTrack(
        docUri: Uri,
        displayName: String,
        mimeType: String,
        sizeBytes: Long,
        lastModifiedMs: Long
    ): LibraryTrack {
        var title = ""
        var artist = ""
        var album = ""
        var durationMs = 0L

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(appContext, docUri)
            title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE).orEmpty()
            artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST).orEmpty()
            album = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM).orEmpty()
            durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } catch (_: Exception) {
            // Fall back to filename if metadata tags cannot be read.
        } finally {
            runCatching { retriever.release() }
        }

        val fallbackTitle = displayName.substringBeforeLast('.')
        val videoId = AudioTagFingerprint.extractVideoIdFromDisplayName(displayName)
            ?: AudioTagFingerprint.extractVideoIdFromUri(appContext, docUri, sizeBytes).orEmpty()

        return LibraryTrack(
            id = stableLongId(docUri.toString()),
            uri = docUri.toString(),
            displayName = displayName,
            title = title.ifBlank { fallbackTitle },
            artist = artist.takeIf { it.isNotBlank() && it != "<unknown>" } ?: "Unknown artist",
            album = album.takeIf { it.isNotBlank() && it != "<unknown>" }.orEmpty(),
            durationMs = durationMs,
            sizeBytes = sizeBytes,
            dateAddedSec = lastModifiedMs / 1000L,
            mimeType = mimeType,
            videoId = videoId
        )
    }

    private fun scanDefaultMediaStoreTracks(): List<LibraryTrack> {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.DISPLAY_NAME,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.MIME_TYPE
        )
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.IS_PENDING} = 0"
        val selectionArgs = arrayOf("%$PUBLIC_SUBFOLDER%")
        val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

        val tracks = mutableListOf<LibraryTrack>()
        runCatching {
            resolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val contentUri = ContentUris.withAppendedId(collection, id)
                    val displayName = cursor.getString(nameCol).orEmpty()
                    val sizeBytes = cursor.getLong(sizeCol)
                    val title = cursor.getString(titleCol)?.takeIf { it.isNotBlank() }
                        ?: displayName.substringBeforeLast('.')
                    val artist = cursor.getString(artistCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" }
                        ?: "Unknown artist"
                    val album = cursor.getString(albumCol)
                        ?.takeIf { it.isNotBlank() && it != "<unknown>" && it != PUBLIC_SUBFOLDER }
                        .orEmpty()
                    val videoId = resolveVideoId(id, contentUri, displayName, sizeBytes)
                    tracks.add(
                        LibraryTrack(
                            id = id,
                            uri = contentUri.toString(),
                            displayName = displayName,
                            title = title,
                            artist = artist,
                            album = album,
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = sizeBytes,
                            dateAddedSec = cursor.getLong(dateCol),
                            mimeType = cursor.getString(mimeCol).orEmpty(),
                            videoId = videoId
                        )
                    )
                }
            }
        }
        return tracks
    }

    fun deleteTrack(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        safTrackCache.remove(uriString)
        return runCatching {
            if (DocumentsContract.isDocumentUri(appContext, uri)) {
                DocumentsContract.deleteDocument(appContext.contentResolver, uri)
            } else {
                appContext.contentResolver.delete(uri, null, null) > 0
            }
        }.getOrDefault(false)
    }

    fun loadEmbeddedArtwork(uriString: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, Uri.parse(uriString))
            val bytes = retriever.embeddedPicture ?: return null
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun deleteExistingByDisplayName(displayName: String, except: Uri) {
        val resolver = appContext.contentResolver
        val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.Audio.Media.DISPLAY_NAME} = ? AND ${MediaStore.Audio.Media._ID} != ?"
        val args = arrayOf("%$PUBLIC_SUBFOLDER%", displayName, ContentUris.parseId(except).toString())
        runCatching {
            resolver.delete(collection, selection, args)
        }
    }

    private fun sha256(input: InputStream): ByteArray = input.use { stream ->
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(65536)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
        digest.digest()
    }

    private fun stableLongId(key: String): Long {
        var h = -0x340d631b7bdddcdbL
        for (ch in key) {
            h = h xor ch.code.toLong()
            h *= 0x100000001b3L
        }
        return h and Long.MAX_VALUE
    }

    private fun mimeTypeForExtension(ext: String): String = when (ext.lowercase(Locale.US)) {
        "mp3" -> "audio/mpeg"
        "m4a", "mp4" -> "audio/mp4"
        "opus" -> "audio/opus"
        "ogg" -> "audio/ogg"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "wav" -> "audio/wav"
        "webm" -> "audio/webm"
        else -> "audio/mpeg"
    }
}

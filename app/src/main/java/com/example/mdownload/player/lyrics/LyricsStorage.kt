package com.example.mdownload.player.lyrics

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.example.mdownload.core.LibraryTrack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/**
 * Atomic UTF-8 local lyrics cache & sidecar `.lrc` storage/discovery helper.
 *
 * - Cache lives under `filesDir/hifideck_lyrics/cache/<sha256>.json` (atomic temp-file swap).
 * - Sidecar `.lrc` discovery checks:
 *   1. User-configured SAF folder tree (`storageTreeUri`) for `<audioBaseName>.lrc`.
 *   2. Adjacent file on filesystem if the audio track resolves to a readable path.
 *   3. App-managed sidecar directory (`filesDir/hifideck_lyrics/sidecars/<safeBaseName>.lrc`).
 */
class LyricsStorage(private val appContext: Context) {

    private val rootDir = File(appContext.filesDir, "hifideck_lyrics")
    private val cacheDir = File(rootDir, "cache")
    private val sidecarDir = File(rootDir, "sidecars")
    private val lock = Any()

    fun trackKeyFor(track: LibraryTrack): String = track.uri

    fun loadCached(trackKey: String): TrackLyrics? = synchronized(lock) {
        val file = cacheFileFor(trackKey)
        if (!file.isFile) return null
        return runCatching {
            val obj = JSONObject(file.readText(Charsets.UTF_8))
            val linesArr = obj.optJSONArray("lines") ?: JSONArray()
            val lines = buildList(linesArr.length()) {
                for (i in 0 until linesArr.length()) {
                    val item = linesArr.optJSONObject(i) ?: continue
                    val ts = if (item.has("ts") && !item.isNull("ts")) item.optLong("ts") else null
                    add(LyricLine(timestampMs = ts, text = item.optString("text", "")))
                }
            }
            if (lines.isEmpty()) return null
            TrackLyrics(
                trackKey = obj.optString("track_key", trackKey),
                lines = lines,
                rawLrc = obj.optString("raw_lrc", "").takeIf { it.isNotBlank() },
                plainText = obj.optString("plain_text", ""),
                isSynced = obj.optBoolean("is_synced", false),
                source = LyricsSource.fromName(obj.optString("source", LyricsSource.CACHE.name)),
                offsetMs = obj.optLong("offset_ms", 0L),
                providerName = obj.optString("provider_name", ""),
                matchedTitle = obj.optString("matched_title", ""),
                matchedArtist = obj.optString("matched_artist", ""),
                confidence = obj.optDouble("confidence", 1.0).toFloat(),
                updatedAtMs = obj.optLong("updated_at_ms", System.currentTimeMillis())
            )
        }.getOrNull()
    }

    fun saveCached(lyrics: TrackLyrics) = synchronized(lock) {
        runCatching {
            val obj = JSONObject().apply {
                put("track_key", lyrics.trackKey)
                put("raw_lrc", lyrics.rawLrc.orEmpty())
                put("plain_text", lyrics.plainText)
                put("is_synced", lyrics.isSynced)
                put("source", lyrics.source.name)
                put("offset_ms", lyrics.offsetMs)
                put("provider_name", lyrics.providerName)
                put("matched_title", lyrics.matchedTitle)
                put("matched_artist", lyrics.matchedArtist)
                put("confidence", lyrics.confidence.toDouble())
                put("updated_at_ms", lyrics.updatedAtMs)
                put("lines", JSONArray().apply {
                    lyrics.lines.forEach { line ->
                        put(JSONObject().apply {
                            if (line.timestampMs != null) put("ts", line.timestampMs)
                            put("text", line.text)
                        })
                    }
                })
            }
            cacheDir.mkdirs()
            val target = cacheFileFor(lyrics.trackKey)
            val tmp = File(cacheDir, "${target.name}.tmp")
            tmp.writeText(obj.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(target)) {
                target.writeText(obj.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    fun clearCached(trackKey: String) = synchronized(lock) {
        runCatching { cacheFileFor(trackKey).delete() }
    }

    /**
     * Attempts to locate a matching sidecar `.lrc` file for [track] in:
     * 1. The user's configured SAF storage folder (`customTreeUri`),
     * 2. Same directory if the track's folder path resolves on external storage,
     * 3. App-managed sidecar directory.
     */
    fun findSidecarLrc(track: LibraryTrack, customTreeUri: String = ""): TrackLyrics? {
        val baseName = track.displayName.substringBeforeLast('.').trim()
        if (baseName.isBlank()) return null
        val targetFileName = "$baseName.lrc"

        // 1. SAF folder tree check
        if (customTreeUri.isNotBlank()) {
            readSidecarFromSafTree(customTreeUri, targetFileName)?.let { content ->
                LyricsParser.toTrackLyrics(
                    trackKey = trackKeyFor(track),
                    rawInput = content,
                    source = LyricsSource.SIDECAR
                )?.let { return it }
            }
        }

        // 2. Public external storage relative path check (e.g. Music/MDownload/<name>.lrc)
        if (track.folderPath.isNotBlank()) {
            val externalRoot = Environment.getExternalStorageDirectory()
            val candidate = File(File(externalRoot, track.folderPath), targetFileName)
            if (candidate.isFile && candidate.canRead()) {
                runCatching { candidate.readText(Charsets.UTF_8) }.getOrNull()?.let { content ->
                    LyricsParser.toTrackLyrics(
                        trackKey = trackKeyFor(track),
                        rawInput = content,
                        source = LyricsSource.SIDECAR
                    )?.let { return it }
                }
            }
        }

        // 3. App sidecar store
        val appSidecar = File(sidecarDir, "${sha256(trackKeyFor(track))}.lrc")
        if (appSidecar.isFile) {
            runCatching { appSidecar.readText(Charsets.UTF_8) }.getOrNull()?.let { content ->
                LyricsParser.toTrackLyrics(
                    trackKey = trackKeyFor(track),
                    rawInput = content,
                    source = LyricsSource.SIDECAR
                )?.let { return it }
            }
        }

        return null
    }

    /** Writes a `.lrc` file into the app sidecar folder (and also to SAF tree when writable). */
    fun saveAppSidecarLrc(track: LibraryTrack, lyrics: TrackLyrics): File? = synchronized(lock) {
        runCatching {
            sidecarDir.mkdirs()
            val content = lyrics.toExportableLrc()
            val outFile = File(sidecarDir, "${sha256(trackKeyFor(track))}.lrc")
            outFile.writeText(content, Charsets.UTF_8)
            outFile
        }.getOrNull()
    }

    /** Reads user-imported `.lrc` or `.txt` file via Android SAF `OpenDocument`. */
    fun readImportedTextUri(uri: Uri): String? {
        return runCatching {
            appContext.contentResolver.openInputStream(uri)?.use { stream ->
                stream.bufferedReader(Charsets.UTF_8).readText()
            }
        }.getOrNull()
    }

    /** Exports `.lrc` text to a user-chosen Android SAF `CreateDocument` URI. */
    fun exportLrcToUri(uri: Uri, lyrics: TrackLyrics): Boolean {
        return runCatching {
            val content = lyrics.toExportableLrc()
            appContext.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                out.write(content.toByteArray(Charsets.UTF_8))
                out.flush()
                true
            } ?: false
        }.getOrDefault(false)
    }

    private fun readSidecarFromSafTree(treeUriStr: String, targetFileName: String): String? {
        return runCatching {
            val treeUri = Uri.parse(treeUriStr)
            val treeDocId = DocumentsContract.getTreeDocumentId(treeUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, treeDocId)
            val projection = arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME
            )
            appContext.contentResolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameCol = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = if (nameCol >= 0) cursor.getString(nameCol).orEmpty() else ""
                    if (name.equals(targetFileName, ignoreCase = true)) {
                        val docId = cursor.getString(idCol)
                        val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                        return appContext.contentResolver.openInputStream(docUri)?.use {
                            it.bufferedReader(Charsets.UTF_8).readText()
                        }
                    }
                }
            }
            null
        }.getOrNull()
    }

    private fun cacheFileFor(trackKey: String): File =
        File(cacheDir, "${sha256(trackKey)}.json")

    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }
}

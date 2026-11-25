package com.example.mdownload.engine

import android.content.Context
import android.os.StatFs
import com.example.mdownload.core.*
import org.json.JSONObject
import java.io.File

data class PendingAudio(val id: String, val title: String, val bytes: Long)
data class StorageSnapshot(val freeBytes: Long = 0, val temporaryBytes: Long = 0,
    val recoveryBytes: Long = 0, val pending: List<PendingAudio> = emptyList())

class StorageTools(private val context: Context) {
    val workRoot = File(context.filesDir, "hifideck_work")
    private fun size(file: File) = if (file.exists()) file.walkTopDown().filter { it.isFile }.sumOf { it.length() } else 0L
    private fun folders() = workRoot.listFiles()?.filter { it.isDirectory }.orEmpty()
    fun snapshot(): StorageSnapshot {
        val pending = folders().mapNotNull { dir -> runCatching {
            val json = JSONObject(File(dir, "export.json").readText())
            PendingAudio(dir.name, json.getString("title"), size(dir))
        }.getOrNull() }
        val recover = pending.sumOf { it.bytes }
        return StorageSnapshot(StatFs(context.filesDir.absolutePath).availableBytes,
            (size(context.cacheDir) + size(workRoot) - recover).coerceAtLeast(0), recover, pending)
    }
    fun retain(job: DownloadJob, file: File) {
        workRoot.mkdirs()
        require(file.canonicalFile.parentFile == File(workRoot, job.id).canonicalFile)
        val json = JSONObject().apply {
            put("file", file.name); put("title", job.title); put("url", job.url)
            put("format", job.options.audioFormat.id); put("quality", job.options.audioQuality)
            put("ascii", job.options.asciiNames); put("duplicates", job.options.skipDuplicates)
            put("artist", job.metadata.artist); put("album", job.metadata.album)
            put("override_title", job.metadata.title)
        }
        val temp = File(file.parentFile, "export.tmp"); temp.writeText(json.toString())
        check(temp.renameTo(File(file.parentFile, "export.json")))
    }
    fun retry(id: String, exporter: MediaStoreExporter, customTreeUri: String = ""): ExportedAudioResult {
        require(Regex("[A-Za-z0-9-]+").matches(id))
        val dir = File(workRoot, id)
        val json = JSONObject(File(dir, "export.json").readText())
        val file = File(dir, json.getString("file"))
        require(file.canonicalFile.parentFile == dir.canonicalFile)
        val verified = exporter.verifyAudioFile(file) ?: error("Saved audio is no longer valid")
        val options = JobOptions(audioFormat=AudioFormat.fromId(json.getString("format")),
            audioQuality=json.getInt("quality"), asciiNames=json.getBoolean("ascii"),
            skipDuplicates=json.getBoolean("duplicates"))
        val videoId = YouTubeUrlParser.extractVideoId(json.getString("url"))
        if (options.skipDuplicates && exporter.hasExistingExport(videoId, options, customTreeUri)) {
            error("A matching file already exists. Remove the pending copy if it is no longer needed.")
        }
        val exported = exporter.exportToPublicMusic(file, videoId, options,
            TrackMetadata(json.optString("override_title"), json.optString("artist"), json.optString("album")),
            verified, json.getString("title"), verified.artist, customTreeUri = customTreeUri)
        dir.deleteRecursively()
        return exported
    }
    fun discard(id: String) {
        require(Regex("[A-Za-z0-9-]+").matches(id))
        File(workRoot, id).deleteRecursively()
    }
    fun clearTemporary() {
        context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        folders().filterNot { File(it, "export.json").isFile }.forEach { it.deleteRecursively() }
    }
}

package com.example.mdownload.engine

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.example.mdownload.core.AudioTagFingerprint
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.TrackMetadata
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Handles high-resolution cover art preparation (local gallery import or HD YouTube upgrade)
 * and lossless FFmpeg stream-copy tag/cover embedding (`-c:a copy`) for Title, Artist, Album,
 * Year, hidden video ID fingerprint, and custom cover art.
 */
object CoverArtHelper {

    private const val TAG = "CoverArtHelper"
    private const val MAX_COVER_DIMENSION = 1400
    private const val COVER_JPEG_QUALITY = 92

    fun coverCacheDir(context: Context): File =
        File(context.filesDir, "hifideck_covers").apply { mkdirs() }

    fun artistCoverCacheDir(context: Context): File =
        File(context.filesDir, "hifideck_artist_covers").apply { mkdirs() }

    /**
     * Imports a user-selected image URI into an app-owned high-resolution JPEG file so it remains
     * readable across process restarts and queue sessions.
     */
    fun importPickedCover(context: Context, uri: Uri): File? =
        importImageToDir(context, uri, coverCacheDir(context), "cover_", MAX_COVER_DIMENSION)

    /**
     * Imports a user-selected artist cover photo into an app-owned JPEG file.
     */
    fun importArtistCover(context: Context, uri: Uri): File? =
        importImageToDir(context, uri, artistCoverCacheDir(context), "artist_", 1024)

    private fun importImageToDir(
        context: Context,
        uri: Uri,
        targetDir: File,
        prefix: String,
        maxDimension: Int
    ): File? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val sampleSize = calculateInSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val decoded = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOpts)
            } ?: return null

            try {
                val scaled = scaleDownIfNeeded(decoded, maxDimension)
                val outFile = File(targetDir, "${prefix}${UUID.randomUUID().toString().take(12)}.jpg")
                FileOutputStream(outFile).use { fos ->
                    scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, fos)
                    fos.flush()
                }
                if (scaled !== decoded) scaled.recycle()
                outFile.takeIf { it.isFile && it.length() > 512L }
            } finally {
                decoded.recycle()
            }
        }.onFailure { Log.w(TAG, "Failed to import cover image", it) }.getOrNull()
    }

    /**
     * Fetches a high-resolution artwork image from a URL (trying maxresdefault -> sddefault -> hqdefault
     * for YouTube videos) and saves it as a clean JPEG file.
     */
    fun fetchHighResCoverToFile(
        rawUrl: String,
        videoId: String?,
        outFile: File
    ): File? {
        val candidates = buildList {
            if (!videoId.isNullOrBlank()) {
                add("https://i.ytimg.com/vi/$videoId/maxresdefault.jpg")
                add("https://i.ytimg.com/vi/$videoId/sddefault.jpg")
            }
            if (rawUrl.isNotBlank()) {
                val upgraded = TextUtils.upgradeThumbnailUrl(rawUrl, videoId)
                add(upgraded)
                if (upgraded != rawUrl) add(rawUrl)
            }
        }.distinct()

        for (candidate in candidates) {
            val saved = downloadImageAsJpeg(candidate, outFile)
            if (saved != null) return saved
        }
        return null
    }

    /**
     * Resolves a customArtworkUri (local file path, content:// URI, or https:// URL) to a local JPEG file.
     */
    fun resolveCustomCoverFile(context: Context, customArtworkUri: String, workDir: File): File? {
        val trimmed = customArtworkUri.trim()
        if (trimmed.isBlank()) return null
        val localFile = File(trimmed)
        if (localFile.isFile && localFile.length() > 512L) return localFile

        if (trimmed.startsWith("content://", ignoreCase = true)) {
            return importPickedCover(context, Uri.parse(trimmed))
        }
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            val target = File(workDir, "custom_cover.jpg")
            return downloadImageAsJpeg(trimmed, target)
        }
        return null
    }

    /**
     * Applies user metadata overrides (title, artist, album, year) and optional custom HD cover art
     * losslessly (`-c:a copy`) using the bundled FFmpeg binary from youtubedl-android.
     * Falls back gracefully to [audioFile] if nothing needs rewriting or FFmpeg fails.
     */
    fun applyMetadataAndCover(
        context: Context,
        audioFile: File,
        metadata: TrackMetadata,
        videoId: String?,
        embedArtwork: Boolean,
        workDir: File,
        onLog: (String) -> Unit = {}
    ): File {
        val cleanMeta = TextUtils.cleanMetadata(metadata)
        val customCoverFile = if (embedArtwork && cleanMeta.customArtworkUri.isNotBlank()) {
            resolveCustomCoverFile(context, cleanMeta.customArtworkUri, workDir)
        } else null

        val hasTextOverrides = cleanMeta.title.isNotBlank() ||
            cleanMeta.artist.isNotBlank() ||
            cleanMeta.album.isNotBlank() ||
            cleanMeta.year.isNotBlank()

        if (!hasTextOverrides && customCoverFile == null) {
            return audioFile
        }

        val ffmpegBin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        if (!ffmpegBin.isFile) {
            onLog("METADATA: Bundled FFmpeg binary not found; keeping original tags.")
            return audioFile
        }

        val ext = audioFile.extension.lowercase(Locale.US)
        val supportsAttachedPic = ext in setOf("mp3", "m4a", "mp4", "flac")
        val attachCustomCover = customCoverFile != null && supportsAttachedPic

        val outFile = File(workDir, "${audioFile.nameWithoutExtension}_tagged.${audioFile.extension}")
        val cmd = mutableListOf(
            ffmpegBin.absolutePath,
            "-y",
            "-v", "error",
            "-i", audioFile.absolutePath
        )

        if (attachCustomCover) {
            cmd.addAll(listOf("-i", customCoverFile.absolutePath))
            cmd.addAll(listOf("-map", "0:a:0", "-map", "1:v:0", "-c:a", "copy", "-c:v", "mjpeg"))
            if (ext == "mp3") {
                cmd.addAll(
                    listOf(
                        "-id3v2_version", "3",
                        "-metadata:s:v", "title=Album cover",
                        "-metadata:s:v", "comment=Cover (front)"
                    )
                )
            } else {
                cmd.addAll(listOf("-disposition:v:0", "attached_pic"))
            }
        } else {
            cmd.addAll(listOf("-map", "0", "-c", "copy"))
            if (ext == "mp3") {
                cmd.addAll(listOf("-id3v2_version", "3"))
            }
        }

        if (cleanMeta.title.isNotBlank()) {
            cmd.addAll(listOf("-metadata", "title=${cleanMeta.title}"))
        }
        if (cleanMeta.artist.isNotBlank()) {
            cmd.addAll(listOf("-metadata", "artist=${cleanMeta.artist}"))
            cmd.addAll(listOf("-metadata", "album_artist=${cleanMeta.artist}"))
        }
        if (cleanMeta.album.isNotBlank()) {
            cmd.addAll(listOf("-metadata", "album=${cleanMeta.album}"))
        }
        if (cleanMeta.year.isNotBlank()) {
            cmd.addAll(listOf("-metadata", "date=${cleanMeta.year}"))
            cmd.addAll(listOf("-metadata", "year=${cleanMeta.year}"))
        }
        if (!videoId.isNullOrBlank()) {
            cmd.addAll(listOf("-metadata", "comment=${AudioTagFingerprint.markerValue(videoId)}"))
        }

        cmd.add(outFile.absolutePath)

        return try {
            val packagesDir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")
            val ldPath = listOf(
                File(packagesDir, "ffmpeg/usr/lib").absolutePath,
                File(packagesDir, "python/usr/lib").absolutePath
            ).joinToString(":")

            val pb = ProcessBuilder(cmd).redirectErrorStream(true)
            pb.environment()["LD_LIBRARY_PATH"] = ldPath
            val process = pb.start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val exitCode = process.waitFor()
            if (exitCode == 0 && outFile.isFile && outFile.length() > 1024L) {
                if (audioFile.delete() && outFile.renameTo(audioFile)) {
                    audioFile
                } else {
                    outFile
                }
            } else {
                outFile.delete()
                if (output.isNotBlank()) onLog("METADATA: FFmpeg tag pass skipped ($output)")
                audioFile
            }
        } catch (e: Exception) {
            outFile.delete()
            onLog("METADATA: FFmpeg tag pass error (${e.message})")
            audioFile
        }
    }

    private fun downloadImageAsJpeg(imageUrl: String, outFile: File): File? {
        return runCatching {
            val conn = (URL(imageUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 10_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Android 15; Mobile)")
            }
            try {
                if (conn.responseCode !in 200..299) return null
                val bytes = conn.inputStream.use { it.readBytes() }
                if (bytes.size < 1024) return null
                val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
                try {
                    // Reject YouTube's 120x90 grey placeholder thumbnail when maxresdefault does not exist
                    if (decoded.width <= 120 && decoded.height <= 90) return null
                    val scaled = scaleDownIfNeeded(decoded, MAX_COVER_DIMENSION)
                    FileOutputStream(outFile).use { fos ->
                        scaled.compress(Bitmap.CompressFormat.JPEG, COVER_JPEG_QUALITY, fos)
                        fos.flush()
                    }
                    if (scaled !== decoded) scaled.recycle()
                    outFile.takeIf { it.isFile && it.length() > 1024L }
                } finally {
                    decoded.recycle()
                }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    private fun calculateInSampleSize(width: Int, height: Int, maxDim: Int): Int {
        var sample = 1
        while (width / sample > maxDim * 2 || height / sample > maxDim * 2) {
            sample *= 2
        }
        return sample
    }

    private fun scaleDownIfNeeded(bitmap: Bitmap, maxDim: Int): Bitmap {
        val maxSide = maxOf(bitmap.width, bitmap.height)
        if (maxSide <= maxDim) return bitmap
        val ratio = maxDim.toFloat() / maxSide.toFloat()
        val w = (bitmap.width * ratio).toInt().coerceAtLeast(1)
        val h = (bitmap.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(bitmap, w, h, true)
    }
}

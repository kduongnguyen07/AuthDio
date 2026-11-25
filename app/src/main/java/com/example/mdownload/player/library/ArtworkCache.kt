package com.example.mdownload.player.library

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.LruCache
import com.example.mdownload.player.playback.MediaItemMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Off-main-thread, downsampled, memory-bounded album artwork loader & cache.
 * Decodes embedded audio tags first, falling back to MediaStore album art URIs when available.
 */
class ArtworkCache(private val appContext: Context) {

    companion object {
        private const val MAX_CACHE_KB = 12 * 1024 // 12 MiB cap for smooth large-library scrolling
        private const val THUMBNAIL_MAX_PX = 320
        private const val NOW_PLAYING_MAX_PX = 768
        private const val NOTIFICATION_MAX_PX = 512
    }

    private val bitmapCache = object : LruCache<String, Bitmap>(MAX_CACHE_KB) {
        override fun sizeOf(key: String, value: Bitmap): Int =
            (value.allocationByteCount / 1024).coerceAtLeast(1)
    }

    private val missingUris = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    suspend fun loadArtwork(
        uriString: String,
        albumId: Long = 0L,
        highRes: Boolean = false
    ): Bitmap? {
        if (uriString.isBlank()) return null
        val cacheKey = "${if (highRes) "hi" else "lo"}|$uriString"
        bitmapCache.get(cacheKey)?.let { return it }
        if (missingUris.contains(uriString)) return null

        return withContext(Dispatchers.IO) {
            bitmapCache.get(cacheKey)?.let { return@withContext it }
            val maxPx = if (highRes) NOW_PLAYING_MAX_PX else THUMBNAIL_MAX_PX
            val bytes = extractArtworkBytes(uriString, albumId)
            if (bytes == null || bytes.isEmpty()) {
                missingUris.add(uriString)
                return@withContext null
            }
            val decoded = decodeSampledBitmap(bytes, maxPx)
            if (decoded != null) {
                bitmapCache.put(cacheKey, decoded)
            } else {
                missingUris.add(uriString)
            }
            decoded
        }
    }

    suspend fun loadNotificationArtworkBytes(
        uriString: String,
        albumId: Long = 0L
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (uriString.isBlank() || missingUris.contains(uriString)) return@withContext null
        val raw = extractArtworkBytes(uriString, albumId) ?: run {
            missingUris.add(uriString)
            return@withContext null
        }
        val sampled = decodeSampledBitmap(raw, NOTIFICATION_MAX_PX) ?: return@withContext null
        return@withContext runCatching {
            val out = ByteArrayOutputStream()
            sampled.compress(Bitmap.CompressFormat.JPEG, 86, out)
            sampled.recycle()
            out.toByteArray()
        }.getOrNull()
    }

    fun invalidate(uriString: String) {
        if (uriString.isBlank()) return
        missingUris.remove(uriString)
        bitmapCache.remove("lo|$uriString")
        bitmapCache.remove("hi|$uriString")
    }

    fun clear() {
        bitmapCache.evictAll()
        missingUris.clear()
    }

    private fun extractArtworkBytes(uriString: String, albumId: Long): ByteArray? {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return null
        val retriever = MediaMetadataRetriever()
        val embedded = try {
            retriever.setDataSource(appContext, uri)
            retriever.embeddedPicture
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        if (embedded != null && embedded.isNotEmpty()) return embedded

        val albumArtUri = MediaItemMapper.albumArtUri(albumId) ?: return null
        return runCatching {
            appContext.contentResolver.openInputStream(albumArtUri)?.use { it.readBytes() }
        }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun decodeSampledBitmap(bytes: ByteArray, maxDim: Int): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null

        var inSampleSize = 1
        while ((width / inSampleSize) > maxDim * 2 || (height / inSampleSize) > maxDim * 2) {
            inSampleSize *= 2
        }
        val opts = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
            inPreferredConfig = Bitmap.Config.RGB_565
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
    }.getOrNull()
}

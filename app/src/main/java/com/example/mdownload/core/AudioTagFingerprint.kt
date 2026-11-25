package com.example.mdownload.core

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.RandomAccessFile

/**
 * Embeds and extracts a hidden YouTube video ID fingerprint inside audio file metadata tags
 * (ID3v2 / MP4 atoms / Vorbis comments / Matroska tags) so duplicate detection remains accurate
 * across formats, quality profiles, and even if files are renamed or copied.
 */
object AudioTagFingerprint {

    const val TAG_PREFIX = "MDOWNLOAD_YT_ID:"
    private const val HEAD_SCAN_BYTES = 512 * 1024
    private const val TAIL_SCAN_BYTES = 256 * 1024

    private val MARKER_REGEX = Regex("""MDOWNLOAD_YT_ID:([A-Za-z0-9_-]{6,64})""")
    private val YOUTUBE_PURL_REGEX = Regex("""(?:youtube\.com/watch\?v=|youtu\.be/)([A-Za-z0-9_-]{11})""")
    private val FILENAME_ID_REGEX = Regex("""\[([A-Za-z0-9_-]{11})](?:\.[^.]+)?$""")

    fun markerValue(videoId: String): String = "$TAG_PREFIX${videoId.trim()}"

    fun extractVideoIdFromDisplayName(displayName: String): String? {
        val match = FILENAME_ID_REGEX.find(displayName.trim()) ?: return null
        return match.groupValues[1]
    }

    fun extractVideoIdFromBytes(bytes: ByteArray): String? {
        if (bytes.isEmpty()) return null
        val latin1 = String(bytes, Charsets.ISO_8859_1)
        MARKER_REGEX.find(latin1)?.let { return it.groupValues[1] }
        YOUTUBE_PURL_REGEX.find(latin1)?.let { return it.groupValues[1] }
        return null
    }

    fun extractVideoIdFromFile(file: File): String? {
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                val headSize = minOf(len, HEAD_SCAN_BYTES.toLong()).toInt()
                val headBuf = ByteArray(headSize)
                raf.readFully(headBuf)
                extractVideoIdFromBytes(headBuf)?.let { return@use it }

                if (len > headSize) {
                    val tailSize = minOf(len - headSize, TAIL_SCAN_BYTES.toLong()).toInt()
                    val tailBuf = ByteArray(tailSize)
                    raf.seek(len - tailSize)
                    raf.readFully(tailBuf)
                    extractVideoIdFromBytes(tailBuf)
                } else {
                    null
                }
            }
        }.getOrNull()
    }

    fun extractVideoIdFromUri(context: Context, uri: Uri, fileSizeBytes: Long = -1L): String? {
        return runCatching {
            // Fast path via seekable ParcelFileDescriptor so we only read head + tail metadata regions.
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                java.io.FileInputStream(pfd.fileDescriptor).use { fis ->
                    val channel = fis.channel
                    val totalSize = if (fileSizeBytes > 0L) fileSizeBytes else channel.size()
                    if (totalSize <= 0L) return@use null

                    val headLen = minOf(totalSize, HEAD_SCAN_BYTES.toLong()).toInt()
                    val headBuf = java.nio.ByteBuffer.allocate(headLen)
                    while (headBuf.hasRemaining()) {
                        if (channel.read(headBuf) <= 0) break
                    }
                    extractVideoIdFromBytes(headBuf.array())?.let { return@use it }

                    if (totalSize > headLen) {
                        val tailLen = minOf(totalSize - headLen, TAIL_SCAN_BYTES.toLong()).toInt()
                        channel.position(totalSize - tailLen)
                        val tailBuf = java.nio.ByteBuffer.allocate(tailLen)
                        while (tailBuf.hasRemaining()) {
                            if (channel.read(tailBuf) <= 0) break
                        }
                        extractVideoIdFromBytes(tailBuf.array())
                    } else {
                        null
                    }
                }
            }
        }.getOrNull()
    }
}

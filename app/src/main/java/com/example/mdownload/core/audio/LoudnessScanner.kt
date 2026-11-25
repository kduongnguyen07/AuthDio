package com.example.mdownload.core.audio

import android.content.Context
import android.net.Uri
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import kotlin.math.pow

/**
 * Structured outcome of an offline EBU R128 loudness scan (AUD-09).
 */
sealed class LoudnessScanResult {
    data class Success(val info: ReplayGainInfo) : LoudnessScanResult()
    data object Unavailable : LoudnessScanResult()
    data class InvalidMedia(val reason: String) : LoudnessScanResult()
    data class TimedOut(val timeoutMs: Long) : LoudnessScanResult()
    data class Failed(val reason: String) : LoudnessScanResult()
}

/**
 * Optional, cancellable, bounded-timeout, off-main-thread EBU R128 loudness scanner
 * using the bundled `libffmpeg.so` via [BoundedSubprocessRunner] (AUD-09).
 *
 * Conventions:
 * - Measures Integrated Loudness (LUFS) and Sample Peak (dBFS) via FFmpeg's `ebur128=peak=sample` filter.
 * - Target reference loudness: `-18.0 LUFS` (ReplayGain 2.0 convention).
 * - Reports both measured integrated loudness (`measuredIntegratedLufs`) and the derived gain estimate
 *   (`trackGainDb = -18.0 - measuredIntegratedLufs`) separately so measurements and estimates are never conflated.
 */
object LoudnessScanner {

    const val TARGET_LOUDNESS_LUFS = -18.0f
    const val DEFAULT_TIMEOUT_MS = 45_000L
    const val MIN_TIMEOUT_MS = 30_000L
    const val MAX_TIMEOUT_MS = 180_000L

    private val INTEGRATED_REGEX = Regex("""I:\s*(-?\d+(?:\.\d+)?)\s*LUFS""")
    private val SAMPLE_PEAK_REGEX = Regex("""Peak:\s*(-?\d+(?:\.\d+)?)\s*dBFS""")

    /**
     * Computes a duration-aware bounded wall-clock timeout for EBU R128 FFmpeg scanning.
     * - Unknown or non-positive duration -> [DEFAULT_TIMEOUT_MS] (45 s)
     * - Known duration -> `(durationMs / 4 + 20_000)` clamped to `[30_000 ms, 180_000 ms]`
     */
    fun computeTimeoutMs(durationMs: Long?): Long {
        if (durationMs == null || durationMs <= 0L) return DEFAULT_TIMEOUT_MS
        val scaled = durationMs / 4L + 20_000L
        return scaled.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS)
    }

    /**
     * Filters FFmpeg `ebur128=peak=sample` log lines down to the final summary measurements,
     * discarding per-100ms progress frames (`t: ... M: ...`) so long tracks never fill the
     * bounded output buffer before the final `Summary:` block is emitted.
     */
    fun isRelevantEbuR128Line(line: String): Boolean {
        if (line.contains("t:") && line.contains("M:")) return false
        return line.contains("Summary:") || line.contains("I:") || line.contains("Peak:")
    }

    fun isAvailable(context: Context): Boolean {
        val ffmpegBin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val libDir = File(File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages"), "ffmpeg/usr/lib")
        return ffmpegBin.isFile && libDir.isDirectory
    }

    /**
     * Parses FFmpeg `ebur128=peak=sample` summary output into a [ReplayGainInfo].
     * Pure function for direct JVM unit testing.
     */
    fun parseEbuR128Output(output: String): ReplayGainInfo? {
        val integratedMatch = INTEGRATED_REGEX.findAll(output).lastOrNull() ?: return null
        val integratedLufs = integratedMatch.groupValues[1].toFloatOrNull() ?: return null
        if (!integratedLufs.isFinite() || integratedLufs < -70f || integratedLufs > 0f) return null

        val peakDbfs = SAMPLE_PEAK_REGEX.findAll(output).lastOrNull()
            ?.groupValues?.getOrNull(1)?.toFloatOrNull()
        val samplePeakLinear = peakDbfs?.takeIf { it.isFinite() }?.let { dbfs ->
            ReplayGainCalculator.sanitizePeak(10.0.pow(dbfs.toDouble() / 20.0).toFloat())
        }

        val estimatedTrackGainDb = ReplayGainCalculator.sanitizeGainDb(TARGET_LOUDNESS_LUFS - integratedLufs)
            ?: return null

        return ReplayGainInfo(
            trackGainDb = estimatedTrackGainDb,
            albumGainDb = null,
            trackPeak = samplePeakLinear,
            albumPeak = null,
            source = "Measured (EBU R128 scan)",
            measuredIntegratedLufs = integratedLufs,
            targetLoudnessLufs = TARGET_LOUDNESS_LUFS
        )
    }

    suspend fun scanTrackUri(
        context: Context,
        uriString: String,
        displayName: String,
        durationMs: Long? = null
    ): ReplayGainInfo? {
        val result = scanTrackUriDetailed(
            context = context,
            uriString = uriString,
            displayName = displayName,
            durationMs = durationMs
        )
        return (result as? LoudnessScanResult.Success)?.info
    }

    suspend fun scanTrackUriDetailed(
        context: Context,
        uriString: String,
        displayName: String,
        durationMs: Long? = null,
        timeoutMsOverride: Long? = null
    ): LoudnessScanResult = withContext(Dispatchers.IO) {
        currentCoroutineContext().ensureActive()
        if (!isAvailable(context)) return@withContext LoudnessScanResult.Unavailable

        val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            ?: return@withContext LoudnessScanResult.InvalidMedia("Invalid track URI")
        val ffmpegBin = File(context.applicationInfo.nativeLibraryDir, "libffmpeg.so")
        val packagesDir = File(File(context.noBackupFilesDir, YoutubeDL.baseName), "packages")

        val localFile: File
        var tempCreated = false
        if (uri.scheme.equals("file", ignoreCase = true) || uri.scheme.isNullOrBlank()) {
            localFile = File(uri.path ?: uriString)
            if (!localFile.isFile) {
                return@withContext LoudnessScanResult.InvalidMedia("Audio file not found")
            }
        } else {
            val ext = displayName.substringAfterLast('.', "tmp").lowercase(Locale.US).take(6)
            val tmp = File(context.cacheDir, "rg_scan_${System.nanoTime()}.$ext")
            val copied = try {
                context.contentResolver.openInputStream(uri)?.use { inp ->
                    FileOutputStream(tmp).use { out ->
                        val buf = ByteArray(32 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = inp.read(buf)
                            if (read <= 0) break
                            out.write(buf, 0, read)
                        }
                    }
                }
                tmp.isFile && tmp.length() > 64L
            } catch (e: CancellationException) {
                tmp.delete()
                throw e
            } catch (_: Exception) {
                false
            }
            if (!copied) {
                tmp.delete()
                return@withContext LoudnessScanResult.InvalidMedia("Unable to read audio stream")
            }
            localFile = tmp
            tempCreated = true
        }

        val timeoutMs = timeoutMsOverride?.coerceAtLeast(100L) ?: computeTimeoutMs(durationMs)
        try {
            currentCoroutineContext().ensureActive()
            val ldPath = listOf(
                File(packagesDir, "ffmpeg/usr/lib").absolutePath,
                File(packagesDir, "python/usr/lib").absolutePath
            ).joinToString(":")

            val runResult = BoundedSubprocessRunner.run(
                command = listOf(
                    ffmpegBin.absolutePath,
                    "-nostats",
                    "-i", localFile.absolutePath,
                    "-filter_complex", "ebur128=peak=sample",
                    "-f", "null",
                    "-"
                ),
                environment = mapOf("LD_LIBRARY_PATH" to ldPath),
                timeoutMs = timeoutMs,
                mergeErrorStream = true,
                maxStdoutChars = 128 * 1024,
                stdoutLineFilter = ::isRelevantEbuR128Line
            )

            when (runResult) {
                is BoundedSubprocessRunner.SubprocessResult.Success -> {
                    val parsed = parseEbuR128Output(runResult.stdout)
                    if (parsed != null) {
                        LoudnessScanResult.Success(parsed)
                    } else {
                        LoudnessScanResult.InvalidMedia("FFmpeg completed without a valid EBU R128 loudness summary")
                    }
                }

                is BoundedSubprocessRunner.SubprocessResult.TimedOut -> LoudnessScanResult.TimedOut(timeoutMs)

                is BoundedSubprocessRunner.SubprocessResult.NonZeroExit ->
                    LoudnessScanResult.InvalidMedia("FFmpeg exited with code ${runResult.exitCode}")

                is BoundedSubprocessRunner.SubprocessResult.Failed -> LoudnessScanResult.Failed(runResult.reason)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LoudnessScanResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            if (tempCreated) localFile.delete()
        }
    }
}

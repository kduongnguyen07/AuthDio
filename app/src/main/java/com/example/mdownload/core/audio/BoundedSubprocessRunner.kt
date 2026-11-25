package com.example.mdownload.core.audio

import com.example.mdownload.core.LogSanitizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Bounded, cancellable, deadlock-free subprocess execution engine shared by `ffprobe` (`AUD-08`)
 * and FFmpeg EBU R128 loudness scanning (`AUD-09`).
 *
 * Guarantees:
 * - Concurrent draining of `stdout` and `stderr` on dedicated daemon threads so neither 64 KiB
 *   OS pipe can fill and deadlock the child process.
 * - Hard upper bounds on retained memory for both streams while continuing to drain excess output
 *   to EOF (preventing both pipe deadlock and OOM).
 * - Real wall-clock deadline enforced via incremental `process.waitFor(...)` polling (independent
 *   of blocking stream reads) plus responsive coroutine cancellation.
 * - Escalating termination (`destroy()` -> `destroyForcibly()`), reaping, stream closure, and
 *   bounded reader-thread join in `finally` to prevent zombie processes and FD leaks.
 */
object BoundedSubprocessRunner {

    private const val POLL_SLICE_MS = 50L
    private const val GRACEFUL_EXIT_WAIT_MS = 200L
    private const val REAP_WAIT_MS = 500L
    private const val READER_JOIN_MS = 500L

    sealed interface SubprocessResult {
        data class Success(
            val exitCode: Int,
            val stdout: String,
            val stderr: String,
            val stdoutTruncated: Boolean = false,
            val stderrTruncated: Boolean = false
        ) : SubprocessResult

        data class NonZeroExit(
            val exitCode: Int,
            val stdout: String,
            val stderr: String
        ) : SubprocessResult

        data class TimedOut(
            val timeoutMs: Long,
            val partialStderr: String
        ) : SubprocessResult

        data class Failed(
            val reason: String,
            val cause: Throwable? = null
        ) : SubprocessResult
    }

    suspend fun run(
        command: List<String>,
        environment: Map<String, String> = emptyMap(),
        timeoutMs: Long,
        mergeErrorStream: Boolean = false,
        maxStdoutChars: Int = 512 * 1024,
        maxStderrChars: Int = 32 * 1024,
        stdoutLineFilter: ((String) -> Boolean)? = null
    ): SubprocessResult = withContext(Dispatchers.IO) {
        if (command.isEmpty()) {
            return@withContext SubprocessResult.Failed("Empty command.")
        }
        val safeTimeoutMs = timeoutMs.coerceAtLeast(100L)
        if (!currentCoroutineContext().isActive) {
            throw CancellationException("Subprocess cancelled before start.")
        }

        val pb = ProcessBuilder(command).redirectErrorStream(mergeErrorStream)
        if (environment.isNotEmpty()) {
            pb.environment().putAll(environment)
        }

        val proc = try {
            pb.start()
        } catch (e: Exception) {
            return@withContext SubprocessResult.Failed(
                reason = "Failed to start subprocess: ${LogSanitizer.sanitize(e.message ?: e.javaClass.simpleName)}",
                cause = e
            )
        }

        // Immediately close child stdin so subprocesses never hang waiting for interactive input.
        runCatching { proc.outputStream.close() }

        val stdoutBuf = StringBuilder()
        val stderrBuf = StringBuilder()
        val stdoutTruncated = AtomicBoolean(false)
        val stderrTruncated = AtomicBoolean(false)

        val stdoutThread = startDrainThread(
            name = "hifideck-subproc-out",
            stream = proc.inputStream,
            target = stdoutBuf,
            maxChars = maxStdoutChars,
            truncatedFlag = stdoutTruncated,
            lineFilter = stdoutLineFilter
        )
        val stderrThread = if (!mergeErrorStream) {
            startDrainThread(
                name = "hifideck-subproc-err",
                stream = proc.errorStream,
                target = stderrBuf,
                maxChars = maxStderrChars,
                truncatedFlag = stderrTruncated,
                lineFilter = null
            )
        } else {
            null
        }

        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(safeTimeoutMs)
        var timedOut = false
        var finished = false

        try {
            while (!finished) {
                if (!currentCoroutineContext().isActive) {
                    throw CancellationException("Subprocess cancelled during execution.")
                }
                val remainingMs = TimeUnit.NANOSECONDS.toMillis(deadlineNanos - System.nanoTime())
                if (remainingMs <= 0L) {
                    timedOut = true
                    break
                }
                val slice = minOf(POLL_SLICE_MS, remainingMs)
                finished = try {
                    proc.waitFor(slice, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw CancellationException("Subprocess interrupted.")
                }
            }

            if (timedOut) {
                terminateAndReap(proc)
                joinReader(stdoutThread)
                joinReader(stderrThread)
                val partialErr = synchronized(stderrBuf) {
                    LogSanitizer.sanitize(stderrBuf.toString(), maxLength = 512)
                }
                return@withContext SubprocessResult.TimedOut(
                    timeoutMs = safeTimeoutMs,
                    partialStderr = partialErr
                )
            }

            // Process exited normally within deadline: wait briefly for drain threads to finish EOF.
            joinReader(stdoutThread, waitMs = 2_000L)
            joinReader(stderrThread, waitMs = 2_000L)

            val exitCode = proc.exitValue()
            val outText = synchronized(stdoutBuf) { stdoutBuf.toString() }
            val errText = synchronized(stderrBuf) { stderrBuf.toString() }

            return@withContext if (exitCode == 0) {
                SubprocessResult.Success(
                    exitCode = 0,
                    stdout = outText,
                    stderr = errText,
                    stdoutTruncated = stdoutTruncated.get(),
                    stderrTruncated = stderrTruncated.get()
                )
            } else {
                SubprocessResult.NonZeroExit(
                    exitCode = exitCode,
                    stdout = outText,
                    stderr = errText
                )
            }
        } catch (ce: CancellationException) {
            terminateAndReap(proc)
            closeStreamsQuietly(proc)
            joinReader(stdoutThread)
            joinReader(stderrThread)
            throw ce
        } catch (e: Exception) {
            terminateAndReap(proc)
            closeStreamsQuietly(proc)
            joinReader(stdoutThread)
            joinReader(stderrThread)
            SubprocessResult.Failed(
                reason = "Subprocess execution error: ${LogSanitizer.sanitize(e.message ?: e.javaClass.simpleName)}",
                cause = e
            )
        } finally {
            if (proc.isAlive) {
                terminateAndReap(proc)
            }
            closeStreamsQuietly(proc)
            joinReader(stdoutThread, waitMs = 200L)
            joinReader(stderrThread, waitMs = 200L)
        }
    }

    private fun startDrainThread(
        name: String,
        stream: InputStream,
        target: StringBuilder,
        maxChars: Int,
        truncatedFlag: AtomicBoolean,
        lineFilter: ((String) -> Boolean)?
    ): Thread = Thread({
        runCatching {
            if (lineFilter != null) {
                BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { reader ->
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (lineFilter(line)) {
                            synchronized(target) {
                                val remaining = maxChars - target.length
                                if (remaining > 0) {
                                    val toAppend = if (line.length + 1 <= remaining) {
                                        "$line\n"
                                    } else {
                                        truncatedFlag.set(true)
                                        line.take(remaining)
                                    }
                                    target.append(toAppend)
                                } else {
                                    truncatedFlag.set(true)
                                }
                            }
                        }
                    }
                }
            } else {
                InputStreamReader(stream, StandardCharsets.UTF_8).use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val read = reader.read(buf)
                        if (read <= 0) break
                        synchronized(target) {
                            val remaining = maxChars - target.length
                            if (remaining > 0) {
                                val count = minOf(read, remaining)
                                target.appendRange(buf, 0, count)
                                if (count < read) truncatedFlag.set(true)
                            } else {
                                truncatedFlag.set(true)
                            }
                        }
                    }
                }
            }
        }
    }, name).apply {
        isDaemon = true
        start()
    }

    private fun terminateAndReap(proc: Process) {
        runCatching {
            if (proc.isAlive) {
                proc.destroy()
                if (!proc.waitFor(GRACEFUL_EXIT_WAIT_MS, TimeUnit.MILLISECONDS)) {
                    proc.destroyForcibly()
                    proc.waitFor(REAP_WAIT_MS, TimeUnit.MILLISECONDS)
                }
            }
        }
    }

    private fun closeStreamsQuietly(proc: Process) {
        runCatching { proc.inputStream.close() }
        runCatching { proc.errorStream.close() }
        runCatching { proc.outputStream.close() }
    }

    private fun joinReader(thread: Thread?, waitMs: Long = READER_JOIN_MS) {
        if (thread == null || !thread.isAlive) return
        runCatching { thread.join(waitMs) }
    }
}

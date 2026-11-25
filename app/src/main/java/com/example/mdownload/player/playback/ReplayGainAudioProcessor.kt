package com.example.mdownload.player.playback

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Custom Media3 [BaseAudioProcessor] applying ReplayGain and Sleep Timer fade-out gain
 * directly in ExoPlayer's PCM audio pipeline.
 *
 * Threading & real-time safety contract:
 * - [currentGain] is **exclusively owned by ExoPlayer's audio thread** (`queueInput`, `onFlush`,
 *   `onReset`) and is never read or written from UI / coordinator threads.
 * - Cross-thread updates from [PlaybackGainCoordinator] communicate strictly through lock-free
 *   primitives (`@Volatile targetGain` and `AtomicBoolean snapRequested`), with zero locks and
 *   zero per-buffer heap allocations inside [queueInput].
 * - [setPcmStateListener] notifies [PlaybackGainCoordinator] on `onConfigure` and `onReset`
 *   transitions so the coordinator can atomically hand off between `PLAYER_VOLUME_FALLBACK`
 *   and `DSP_PCM_PROCESSOR` without ever double-attenuating.
 *
 * Unity-gain handling (honest documentation):
 * - Because Media3's `DefaultAudioSink` evaluates [isActive] only at `configure()` / `flush()` time,
 *   the processor stays active for supported PCM formats (`PCM_16BIT`, `PCM_FLOAT`) so live mid-track
 *   ReplayGain toggling (including positive `+dB` preamp) and Sleep Timer fade-out engage immediately
 *   without tearing down the sink.
 * - When `currentGain == 1.0f` and `targetGain == 1.0f` (unity / ReplayGain OFF & no fade),
 *   [queueInput] executes a bit-exact bulk `ByteBuffer.put` fast-path with zero floating-point
 *   sample scaling or rounding math (this is a bit-exact fast-path copy, not a zero-copy sink bypass).
 */
@OptIn(UnstableApi::class)
class ReplayGainAudioProcessor : BaseAudioProcessor() {

    companion object {
        private const val UNITY_EPSILON = 0.0005f
        private const val RAMP_DURATION_MS = 30
        private const val MAX_LINEAR_GAIN = 8.0f // +18 dB hard safety ceiling
    }

    @Volatile
    private var targetGain: Float = 1.0f

    @Volatile
    var isProcessingPcm: Boolean = false
        private set

    @Volatile
    private var pcmStateListener: ((Boolean) -> Unit)? = null

    private val snapRequested = AtomicBoolean(false)
    private val unityFastPathBuffers = AtomicLong(0L)
    private val scaledBuffers = AtomicLong(0L)

    // Exclusively owned by ExoPlayer's audio thread (queueInput / onFlush / onReset).
    private var currentGain: Float = 1.0f

    val currentTargetLinearGain: Float
        get() = targetGain

    val unityFastPathBufferCount: Long
        get() = unityFastPathBuffers.get()

    val scaledBufferCount: Long
        get() = scaledBuffers.get()

    fun setPcmStateListener(listener: ((Boolean) -> Unit)?) {
        pcmStateListener = listener
    }

    fun setTargetLinearGain(linearGain: Float) {
        val safe = if (linearGain.isFinite()) linearGain.coerceIn(0f, MAX_LINEAR_GAIN) else 1.0f
        targetGain = safe
    }

    /**
     * Requests that the audio thread snap `currentGain` directly to `targetGain` on the next
     * buffer (e.g. on track switch or fallback->DSP handoff). Thread-safe and lock-free.
     */
    fun snapToTargetGain() {
        snapRequested.set(true)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            updateProcessingPcm(false)
            return AudioProcessor.AudioFormat.NOT_SET
        }
        updateProcessingPcm(true)
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return

        val outputBuffer = replaceOutputBuffer(remaining)
        val goal = targetGain
        if (snapRequested.getAndSet(false)) {
            currentGain = goal
        }

        val atUnity = abs(currentGain - 1.0f) < UNITY_EPSILON && abs(goal - 1.0f) < UNITY_EPSILON
        if (atUnity) {
            currentGain = 1.0f
            unityFastPathBuffers.incrementAndGet()
            outputBuffer.put(inputBuffer)
            outputBuffer.flip()
            return
        }

        scaledBuffers.incrementAndGet()
        val channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        val sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        val rampFrames = ((sampleRate.toLong() * RAMP_DURATION_MS) / 1000L).toInt().coerceAtLeast(1)

        when (inputAudioFormat.encoding) {
            C.ENCODING_PCM_16BIT -> processPcm16(inputBuffer, outputBuffer, channels, goal, rampFrames)
            C.ENCODING_PCM_FLOAT -> processPcmFloat(inputBuffer, outputBuffer, channels, goal, rampFrames)
            else -> outputBuffer.put(inputBuffer)
        }

        outputBuffer.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // Snap gain cleanly on seek/track flush so the next track begins directly at its target gain.
        snapRequested.set(false)
        currentGain = targetGain
    }

    override fun onReset() {
        snapRequested.set(false)
        currentGain = targetGain
        updateProcessingPcm(false)
    }

    private fun updateProcessingPcm(active: Boolean) {
        val prev = isProcessingPcm
        isProcessingPcm = active
        // Always notify on state transition; also notify when PCM becomes active so coordinator
        // guarantees player.volume == 1.0f even after sink reconfiguration.
        if (prev != active || active) {
            pcmStateListener?.invoke(active)
        }
    }

    private fun processPcm16(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        goal: Float,
        rampFrames: Int
    ) {
        val bytesPerFrame = channels * 2
        val totalFrames = input.remaining() / bytesPerFrame
        var gain = currentGain
        val maxStep = (abs(goal - gain) / rampFrames.toFloat()).coerceAtLeast(1.0f / rampFrames.toFloat())

        for (frame in 0 until totalFrames) {
            gain = stepGain(gain, goal, maxStep)
            for (ch in 0 until channels) {
                val sample = input.short.toInt()
                val scaled = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                output.putShort(scaled.toShort())
            }
        }
        // Copy any trailing partial bytes defensively
        while (input.hasRemaining()) {
            output.put(input.get())
        }
        currentGain = gain
    }

    private fun processPcmFloat(
        input: ByteBuffer,
        output: ByteBuffer,
        channels: Int,
        goal: Float,
        rampFrames: Int
    ) {
        val bytesPerFrame = channels * 4
        val totalFrames = input.remaining() / bytesPerFrame
        var gain = currentGain
        val maxStep = (abs(goal - gain) / rampFrames.toFloat()).coerceAtLeast(1.0f / rampFrames.toFloat())

        for (frame in 0 until totalFrames) {
            gain = stepGain(gain, goal, maxStep)
            for (ch in 0 until channels) {
                val sample = input.float
                val scaled = (sample * gain).coerceIn(-1.0f, 1.0f)
                output.putFloat(scaled)
            }
        }
        while (input.hasRemaining()) {
            output.put(input.get())
        }
        currentGain = gain
    }

    private fun stepGain(current: Float, target: Float, step: Float): Float {
        val diff = target - current
        return when {
            abs(diff) <= step -> target
            diff > 0f -> current + step
            else -> current - step
        }
    }
}

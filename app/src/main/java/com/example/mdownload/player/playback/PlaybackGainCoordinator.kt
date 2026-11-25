package com.example.mdownload.player.playback

import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.example.mdownload.core.audio.GainApplicationPath
import com.example.mdownload.core.audio.ReplayGainCalculator
import com.example.mdownload.core.audio.ReplayGainConfig
import com.example.mdownload.core.audio.ReplayGainInfo
import com.example.mdownload.core.audio.ReplayGainResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.abs

data class GainCoordinatorState(
    val config: ReplayGainConfig = ReplayGainConfig(),
    val activeTrackUri: String? = null,
    val activeTrackReplayGainInfo: ReplayGainInfo? = null,
    val replayGainResult: ReplayGainResult = ReplayGainResult(
        appliedGainDb = 0f,
        linearGain = 1f,
        usedAlbumGain = false,
        usedTrackGain = false,
        usedFallback = false,
        peakLimited = false,
        reason = "Off"
    ),
    val sleepFadeFactor: Float = 1.0f,
    val effectiveLinearGain: Float = 1.0f,
    val applicationPath: GainApplicationPath = GainApplicationPath.BYPASS
)

/**
 * Single centralized authority coordinating ReplayGain normalization and Sleep Timer fade-out.
 *
 * ### Strict Single-Authority & Zero Double-Attenuation Invariants
 * At every point in time, exactly ONE gain actuator applies non-unity gain:
 * - `BYPASS`: `audioProcessor.targetGain == 1.0f` and `player.volume == 1.0f`.
 * - `DSP_PCM_PROCESSOR`: `audioProcessor.targetGain == effective` and `player.volume == 1.0f`.
 * - `PLAYER_VOLUME_FALLBACK`: `audioProcessor.targetGain == 1.0f` and `player.volume == effective.coerceIn(0f, 1f)`.
 *
 * ### Ordered Handoff Across ExoPlayer PCM Configuration Transitions
 * When `ReplayGainAudioProcessor.onConfigure()` transitions `isProcessingPcm` from `false -> true`
 * on ExoPlayer's audio thread:
 * 1. While previously in `PLAYER_VOLUME_FALLBACK`, `audioProcessor.targetGain` was held at `1.0f`
 *    (unity), so no double attenuation occurs while the handoff message is queued to `player.applicationLooper`.
 * 2. On `player.applicationLooper`, the coordinator atomically sets `player.volume = 1.0f`, promotes
 *    `audioProcessor.targetGain = effective` (with snap), and marks the active path `DSP_PCM_PROCESSOR`.
 * 3. Conversely, on `onReset()` (`true -> false`), the coordinator demotes `audioProcessor.targetGain = 1.0f`
 *    and engages `player.volume = effective.coerceIn(0f, 1f)` on `player.applicationLooper`.
 */
@OptIn(UnstableApi::class)
class PlaybackGainCoordinator(
    private val audioProcessor: ReplayGainAudioProcessor = ReplayGainAudioProcessor(),
    private val playerDispatcher: (Player, () -> Unit) -> Unit = ::defaultPlayerDispatcher
) {
    companion object {
        private const val UNITY_EPSILON = 0.0005f

        private fun defaultPlayerDispatcher(player: Player, block: () -> Unit) {
            val looper = runCatching { player.applicationLooper }.getOrNull()
            if (looper == null || Looper.myLooper() == looper) {
                block()
            } else {
                Handler(looper).post(block)
            }
        }
    }

    private enum class ActuatorOwner {
        NONE,
        PLAYER_FALLBACK,
        DSP_PCM
    }

    private val lock = Any()
    private var attachedPlayer: Player? = null
    private var actuatorOwner: ActuatorOwner = ActuatorOwner.NONE
    private var transitionGeneration: Long = 0L

    private val _state = MutableStateFlow(GainCoordinatorState())
    val state: StateFlow<GainCoordinatorState> = _state.asStateFlow()

    val processor: ReplayGainAudioProcessor
        get() = audioProcessor

    init {
        audioProcessor.setPcmStateListener { isProcessingPcm ->
            onProcessorPcmStateChanged(isProcessingPcm)
        }
    }

    fun attachPlayer(player: Player?) {
        synchronized(lock) {
            attachedPlayer = player
            if (player == null) {
                actuatorOwner = if (audioProcessor.isProcessingPcm) ActuatorOwner.DSP_PCM else ActuatorOwner.NONE
            } else {
                // Unknown initial player.volume on freshly attached player: force explicit reconciliation
                actuatorOwner = ActuatorOwner.NONE
            }
            reapplyLocked(snapProcessor = false)
        }
    }

    fun updateConfig(config: ReplayGainConfig) {
        synchronized(lock) {
            val prev = _state.value
            val result = ReplayGainCalculator.compute(prev.activeTrackReplayGainInfo, config)
            _state.value = prev.copy(config = config, replayGainResult = result)
            reapplyLocked(snapProcessor = false)
        }
    }

    fun updateTrackReplayGain(trackUri: String?, info: ReplayGainInfo?, snapOnTrackSwitch: Boolean = false) {
        synchronized(lock) {
            val prev = _state.value
            val result = ReplayGainCalculator.compute(info, prev.config)
            _state.value = prev.copy(
                activeTrackUri = trackUri,
                activeTrackReplayGainInfo = info,
                replayGainResult = result
            )
            reapplyLocked(snapProcessor = snapOnTrackSwitch)
        }
    }

    fun setSleepFadeFactor(factor: Float, snap: Boolean = false) {
        val clamped = if (factor.isFinite()) factor.coerceIn(0f, 1f) else 1f
        synchronized(lock) {
            val prev = _state.value
            if (!snap && abs(prev.sleepFadeFactor - clamped) < UNITY_EPSILON) return
            _state.value = prev.copy(sleepFadeFactor = clamped)
            reapplyLocked(snapProcessor = snap)
        }
    }

    fun resetSleepFade(snap: Boolean = false) {
        setSleepFadeFactor(1.0f, snap = snap)
    }

    /**
     * Called from ExoPlayer's audio thread via [ReplayGainAudioProcessor.onConfigure] / `onReset`.
     * Never mutates [Player.setVolume] directly from the audio thread; always dispatches handoff
     * onto [Player.getApplicationLooper] via [playerDispatcher].
     */
    private fun onProcessorPcmStateChanged(isProcessingPcm: Boolean) {
        val player: Player
        val gen: Long
        synchronized(lock) {
            val attached = attachedPlayer
            gen = ++transitionGeneration
            if (attached == null) {
                // Headless/unit-test mode with no attached player: apply directly to processor
                reapplyHeadlessLocked(isProcessingPcm, snapProcessor = true)
                return
            }
            player = attached
        }
        playerDispatcher(player) {
            synchronized(lock) {
                if (attachedPlayer !== player || gen != transitionGeneration) return@synchronized
                reconcileOnPlayerThreadLocked(player, snapOnHandoff = true)
            }
        }
    }

    private fun reapplyLocked(snapProcessor: Boolean) {
        val current = _state.value
        val effective = ReplayGainCalculator.composeEffectiveGain(
            replayGainLinear = current.replayGainResult.linearGain,
            sleepFadeFactor = current.sleepFadeFactor
        )
        val isUnity = abs(effective - 1.0f) < UNITY_EPSILON
        val player = attachedPlayer
        val pcmActive = audioProcessor.isProcessingPcm

        if (player == null) {
            reapplyHeadlessLocked(pcmActive, snapProcessor = snapProcessor)
            return
        }

        // Fast-path: already cleanly owned by DSP_PCM (player.volume is guaranteed 1.0f),
        // so live gain / fade updates can update the lock-free processor target directly.
        if (pcmActive && actuatorOwner == ActuatorOwner.DSP_PCM) {
            audioProcessor.setTargetLinearGain(effective)
            if (snapProcessor) audioProcessor.snapToTargetGain()
            _state.value = current.copy(
                effectiveLinearGain = effective,
                applicationPath = if (isUnity) GainApplicationPath.BYPASS else GainApplicationPath.DSP_PCM_PROCESSOR
            )
            return
        }

        // Otherwise dispatch onto player.applicationLooper so player.volume and processor handoff
        // occur together in strict order without ever double-attenuating.
        val gen = ++transitionGeneration
        _state.value = current.copy(
            effectiveLinearGain = effective,
            applicationPath = computeProjectedPath(isUnity, pcmActive, hasPlayer = true)
        )
        playerDispatcher(player) {
            synchronized(lock) {
                if (attachedPlayer !== player || gen != transitionGeneration) return@synchronized
                reconcileOnPlayerThreadLocked(player, snapOnHandoff = snapProcessor)
            }
        }
    }

    private fun reapplyHeadlessLocked(pcmActive: Boolean, snapProcessor: Boolean) {
        val current = _state.value
        val effective = ReplayGainCalculator.composeEffectiveGain(
            replayGainLinear = current.replayGainResult.linearGain,
            sleepFadeFactor = current.sleepFadeFactor
        )
        val isUnity = abs(effective - 1.0f) < UNITY_EPSILON
        if (pcmActive) {
            audioProcessor.setTargetLinearGain(effective)
            if (snapProcessor) audioProcessor.snapToTargetGain()
            actuatorOwner = ActuatorOwner.DSP_PCM
        } else {
            audioProcessor.setTargetLinearGain(1.0f)
            actuatorOwner = ActuatorOwner.NONE
        }
        _state.value = current.copy(
            effectiveLinearGain = effective,
            applicationPath = computeProjectedPath(isUnity, pcmActive, hasPlayer = false)
        )
    }

    /**
     * Executes strictly on [Player.getApplicationLooper] while holding [lock].
     */
    private fun reconcileOnPlayerThreadLocked(player: Player, snapOnHandoff: Boolean) {
        val current = _state.value
        val effective = ReplayGainCalculator.composeEffectiveGain(
            replayGainLinear = current.replayGainResult.linearGain,
            sleepFadeFactor = current.sleepFadeFactor
        )
        val isUnity = abs(effective - 1.0f) < UNITY_EPSILON
        val pcmActive = audioProcessor.isProcessingPcm

        if (isUnity) {
            // BYPASS: both processor and player volume strictly unity (1.0f)
            audioProcessor.setTargetLinearGain(1.0f)
            if (snapOnHandoff) audioProcessor.snapToTargetGain()
            setPlayerVolumeDirect(player, 1.0f)
            actuatorOwner = if (pcmActive) ActuatorOwner.DSP_PCM else ActuatorOwner.PLAYER_FALLBACK
            _state.value = current.copy(
                effectiveLinearGain = effective,
                applicationPath = GainApplicationPath.BYPASS
            )
            return
        }

        if (pcmActive) {
            // Hand off to DSP_PCM_PROCESSOR: reset player.volume to 1.0f FIRST, then apply DSP gain
            val wasFallback = actuatorOwner != ActuatorOwner.DSP_PCM
            setPlayerVolumeDirect(player, 1.0f)
            audioProcessor.setTargetLinearGain(effective)
            if (snapOnHandoff || wasFallback) {
                audioProcessor.snapToTargetGain()
            }
            actuatorOwner = ActuatorOwner.DSP_PCM
            _state.value = current.copy(
                effectiveLinearGain = effective,
                applicationPath = GainApplicationPath.DSP_PCM_PROCESSOR
            )
        } else {
            // PLAYER_VOLUME_FALLBACK: hold processor strictly at 1.0f so onConfigure() cannot double-attenuate
            audioProcessor.setTargetLinearGain(1.0f)
            audioProcessor.snapToTargetGain()
            setPlayerVolumeDirect(player, effective.coerceIn(0f, 1f))
            actuatorOwner = ActuatorOwner.PLAYER_FALLBACK
            _state.value = current.copy(
                effectiveLinearGain = effective,
                applicationPath = GainApplicationPath.PLAYER_VOLUME_FALLBACK
            )
        }
    }

    private fun computeProjectedPath(
        isUnity: Boolean,
        pcmActive: Boolean,
        hasPlayer: Boolean
    ): GainApplicationPath = when {
        isUnity -> GainApplicationPath.BYPASS
        pcmActive -> GainApplicationPath.DSP_PCM_PROCESSOR
        hasPlayer -> GainApplicationPath.PLAYER_VOLUME_FALLBACK
        else -> GainApplicationPath.DSP_PCM_PROCESSOR
    }

    private fun setPlayerVolumeDirect(player: Player, targetVolume: Float) {
        val safeVol = targetVolume.coerceIn(0f, 1f)
        runCatching {
            if (abs(player.volume - safeVol) > 0.0005f) {
                player.volume = safeVol
            }
        }
    }
}

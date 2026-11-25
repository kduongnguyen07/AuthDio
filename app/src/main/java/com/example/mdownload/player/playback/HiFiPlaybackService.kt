package com.example.mdownload.player.playback

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.mdownload.MDownloadApp
import com.example.mdownload.MainActivity
import com.example.mdownload.core.LibraryTrack
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * AndroidX Media3 MediaSessionService hosting the single ExoPlayer & MediaSession for HiFiDeck.
 *
 * Responsibilities:
 * - Owns ExoPlayer & MediaSession lifecycle independent of Activity/Compose UI.
 * - Integrates [ReplayGainAudioProcessor] into ExoPlayer's [DefaultAudioSink] via [PlaybackGainCoordinator].
 * - Drives [SleepTimerController] and [ListeningSessionTracker] cleanly during foreground & background playback.
 * - Handles AudioFocus, becoming-noisy (headphone/Bluetooth disconnect), and wake lock.
 * - Publishes Android-standard MediaStyle notifications, lock-screen controls, and Bluetooth AVRCP metadata.
 * - Supports Media3 playback resumption from [PlaybackStateStore].
 */
@OptIn(UnstableApi::class)
class HiFiPlaybackService : MediaSessionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var exoPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null
    private var artworkEnrichJob: Job? = null
    private var replayGainLoadJob: Job? = null
    private var periodicSaveJob: Job? = null
    private var listeningSampleJob: Job? = null
    private var lastPersistedQueueSignature: String? = null

    private val app: MDownloadApp
        get() = application as MDownloadApp

    private val stateStore: PlaybackStateStore
        get() = app.playbackStateStore

    private val artworkCache
        get() = app.artworkCache

    private val gainCoordinator: PlaybackGainCoordinator
        get() = app.gainCoordinator

    private val sleepTimerController: SleepTimerController
        get() = app.sleepTimerController

    private val audioAnalysisRepository
        get() = app.audioAnalysisRepository

    private val listeningHistoryRepository
        get() = app.listeningHistoryRepository

    private val listeningTracker by lazy {
        ListeningSessionTracker(
            onQualifiedPlay = { trackId, uri, ts ->
                serviceScope.launch(Dispatchers.IO) {
                    listeningHistoryRepository.recordQualifiedPlay(trackId, uri, ts)
                }
            },
            onCommitListenedMs = { trackId, uri, deltaMs, ts ->
                serviceScope.launch(Dispatchers.IO) {
                    listeningHistoryRepository.addListenedDuration(
                        trackId = trackId,
                        uri = uri,
                        deltaMs = deltaMs,
                        timestampMs = ts,
                        flushImmediately = true
                    )
                }
            },
            onPeriodicCommitListenedMs = { trackId, uri, deltaMs, ts ->
                serviceScope.launch(Dispatchers.IO) {
                    listeningHistoryRepository.addListenedDuration(
                        trackId = trackId,
                        uri = uri,
                        deltaMs = deltaMs,
                        timestampMs = ts,
                        flushImmediately = false
                    )
                }
            }
        )
    }

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val renderersFactory = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioTrackPlaybackParams: Boolean
            ): AudioSink {
                return DefaultAudioSink.Builder(context)
                    .setEnableFloatOutput(enableFloatOutput)
                    .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                    .setAudioProcessors(arrayOf(gainCoordinator.processor))
                    .build()
            }
        }

        val player = ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()

        player.addListener(playerListener)
        exoPlayer = player

        gainCoordinator.attachPlayer(player)
        sleepTimerController.attachPlayer(player)

        val sessionActivityIntent = PendingIntent.getActivity(
            this,
            1001,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSession.Builder(this, player)
            .setId("hifideck_audio_session")
            .setSessionActivity(sessionActivityIntent)
            .setCallback(sessionCallback)
            .build()

        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build()
        )
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = exoPlayer
        if (player == null ||
            !player.playWhenReady ||
            player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            listeningTracker.flushPending(System.currentTimeMillis())
            serviceScope.launch(Dispatchers.IO) { listeningHistoryRepository.flushNow() }
            persistCurrentState(player)
            stopSelf()
        }
    }

    override fun onDestroy() {
        periodicSaveJob?.cancel()
        listeningSampleJob?.cancel()
        artworkEnrichJob?.cancel()
        replayGainLoadJob?.cancel()
        listeningTracker.clear(System.currentTimeMillis())
        listeningHistoryRepository.flushNow()
        val player = exoPlayer
        persistCurrentState(player)
        gainCoordinator.attachPlayer(null)
        sleepTimerController.attachPlayer(null)
        player?.removeListener(playerListener)
        mediaSession?.run {
            release()
            mediaSession = null
        }
        player?.release()
        exoPlayer = null
        serviceScope.cancel()
        super.onDestroy()
    }

    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            sleepTimerController.onMediaItemTransition(mediaItem?.mediaId, reason)
            enrichCurrentItemArtwork(mediaItem)
            applyReplayGainForMediaItem(mediaItem, snapOnTrackSwitch = true)
            val track = mediaItem?.let { MediaItemMapper.fromMediaItem(it) }
            val p = exoPlayer
            if (track != null) {
                listeningTracker.onTrackStarted(
                    trackId = track.stableId,
                    uri = track.uri,
                    durationMs = p?.duration?.takeIf { it > 0L } ?: track.durationMs,
                    initialPositionMs = p?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                    nowMs = System.currentTimeMillis()
                )
            } else {
                listeningTracker.clear(System.currentTimeMillis())
            }
            persistCurrentState(exoPlayer)
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int
        ) {
            sleepTimerController.onPositionDiscontinuity(
                oldMediaItemIndex = oldPosition.mediaItemIndex,
                newMediaItemIndex = newPosition.mediaItemIndex,
                reason = reason
            )
            val p = exoPlayer ?: return
            when (reason) {
                Player.DISCONTINUITY_REASON_SEEK,
                Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT -> {
                    listeningTracker.onSeek(newPosition.positionMs)
                }
                Player.DISCONTINUITY_REASON_AUTO_TRANSITION -> {
                    // Repeat-One loops the same MediaItem without firing onMediaItemTransition;
                    // start a fresh listening session for the new repeat iteration.
                    if (oldPosition.mediaItemIndex == newPosition.mediaItemIndex &&
                        p.repeatMode == Player.REPEAT_MODE_ONE
                    ) {
                        val track = p.currentMediaItem?.let { MediaItemMapper.fromMediaItem(it) }
                        if (track != null) {
                            listeningTracker.onTrackStarted(
                                trackId = track.stableId,
                                uri = track.uri,
                                durationMs = p.duration.takeIf { it > 0L } ?: track.durationMs,
                                initialPositionMs = newPosition.positionMs,
                                nowMs = System.currentTimeMillis()
                            )
                        }
                    }
                }
            }
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            sleepTimerController.onPlayWhenReadyChanged(playWhenReady)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            sleepTimerController.onIsPlayingChanged(isPlaying)
            if (isPlaying) {
                startPeriodicSave()
                startListeningSampler()
                // Ensure active track ReplayGain is evaluated if starting fresh
                val activeItem = exoPlayer?.currentMediaItem
                if (activeItem != null && gainCoordinator.state.value.activeTrackUri != activeItem.mediaId) {
                    applyReplayGainForMediaItem(activeItem, snapOnTrackSwitch = false)
                }
            } else {
                periodicSaveJob?.cancel()
                periodicSaveJob = null
                listeningSampleJob?.cancel()
                listeningSampleJob = null
                listeningTracker.flushPending(System.currentTimeMillis())
                serviceScope.launch(Dispatchers.IO) { listeningHistoryRepository.flushNow() }
                persistCurrentState(exoPlayer)
            }
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                persistCurrentState(exoPlayer, forceFullQueue = false)
            }
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            persistCurrentState(exoPlayer)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            persistCurrentState(exoPlayer)
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            persistCurrentState(exoPlayer)
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            sleepTimerController.onPlaybackStateChanged(playbackState)
            if (playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED) {
                if (playbackState == Player.STATE_ENDED) {
                    listeningTracker.flushPending(System.currentTimeMillis())
                    serviceScope.launch(Dispatchers.IO) { listeningHistoryRepository.flushNow() }
                }
                persistCurrentState(exoPlayer)
            }
        }
    }

    private val sessionCallback = object : MediaSession.Callback {
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            isForPlayback: Boolean
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
            val future = SettableFuture.create<MediaSession.MediaItemsWithStartPosition>()
            serviceScope.launch {
                try {
                    val restored = withContext(Dispatchers.IO) {
                        val saved = stateStore.load()
                        val accessible = saved.queue.filter { isTrackUriAccessible(it.uri) }
                        if (accessible.isEmpty()) return@withContext null

                        val targetTrack = saved.queue.getOrNull(saved.currentIndex)
                        val newIndex = if (targetTrack != null) {
                            accessible.indexOfFirst { it.uri == targetTrack.uri }.coerceAtLeast(0)
                        } else 0
                        val startPos = if (accessible.getOrNull(newIndex)?.uri == targetTrack?.uri) {
                            saved.positionMs.coerceAtLeast(0L)
                        } else 0L

                        val currentArt = accessible.getOrNull(newIndex)?.let {
                            artworkCache.loadNotificationArtworkBytes(it.uri, it.albumId)
                        }
                        val items = accessible.mapIndexed { idx, track ->
                            MediaItemMapper.toMediaItem(
                                track = track,
                                artworkBytes = if (idx == newIndex) currentArt else null
                            )
                        }
                        Triple(saved, MediaSession.MediaItemsWithStartPosition(items, newIndex, startPos), Unit)
                    }

                    if (restored == null) {
                        future.setException(IllegalStateException("No saved playable tracks to resume"))
                        return@launch
                    }

                    val (saved, mediaItemsWithStart, _) = restored
                    exoPlayer?.let { p ->
                        p.shuffleModeEnabled = saved.shuffleEnabled
                        p.repeatMode = saved.repeatMode.playerRepeatMode
                        p.playbackParameters = PlaybackParameters(saved.playbackSpeed.coerceIn(0.5f, 2.0f))
                    }
                    future.set(mediaItemsWithStart)
                } catch (e: Exception) {
                    future.setException(e)
                }
            }
            return future
        }
    }

    private fun applyReplayGainForMediaItem(mediaItem: MediaItem?, snapOnTrackSwitch: Boolean) {
        replayGainLoadJob?.cancel()
        val track = mediaItem?.let { MediaItemMapper.fromMediaItem(it) }
        if (track == null) {
            gainCoordinator.updateTrackReplayGain(null, null, snapOnTrackSwitch)
            return
        }
        // Apply cached RG immediately if present to prevent any audible jump at track start
        val freshTrack = app.libraryRepository.tracks.value.firstOrNull { it.uri == track.uri } ?: track
        val cachedInfo = audioAnalysisRepository.getCachedInfo(freshTrack)
        if (cachedInfo != null) {
            gainCoordinator.updateTrackReplayGain(freshTrack.uri, cachedInfo.replayGain, snapOnTrackSwitch)
            return
        }
        // Clear previous track's ReplayGain immediately so stale gain never bleeds into the new track
        // while asynchronous tag lookup runs on Dispatchers.IO.
        gainCoordinator.updateTrackReplayGain(freshTrack.uri, null, snapOnTrackSwitch)
        replayGainLoadJob = serviceScope.launch {
            val rg = audioAnalysisRepository.resolveReplayGain(freshTrack)
            if (exoPlayer?.currentMediaItem?.mediaId == freshTrack.uri) {
                gainCoordinator.updateTrackReplayGain(freshTrack.uri, rg, snapOnTrackSwitch = false)
            }
        }
    }

    private fun enrichCurrentItemArtwork(mediaItem: MediaItem?) {
        val player = exoPlayer ?: return
        val item = mediaItem ?: return
        if (item.mediaMetadata.artworkData != null) return
        val track = MediaItemMapper.fromMediaItem(item) ?: return
        val index = player.currentMediaItemIndex
        if (index !in 0 until player.mediaItemCount) return

        artworkEnrichJob?.cancel()
        artworkEnrichJob = serviceScope.launch {
            val bytes = artworkCache.loadNotificationArtworkBytes(track.uri, track.albumId) ?: return@launch
            val activePlayer = exoPlayer ?: return@launch
            if (index in 0 until activePlayer.mediaItemCount &&
                activePlayer.getMediaItemAt(index).mediaId == item.mediaId
            ) {
                val updated = MediaItemMapper.withArtworkData(activePlayer.getMediaItemAt(index), bytes)
                activePlayer.replaceMediaItem(index, updated)
            }
        }
    }

    private fun startListeningSampler() {
        if (listeningSampleJob?.isActive == true) return
        listeningSampleJob = serviceScope.launch {
            while (true) {
                val p = exoPlayer ?: break
                if (!p.isPlaying) break
                val track = p.currentMediaItem?.let { MediaItemMapper.fromMediaItem(it) }
                if (track != null) {
                    listeningTracker.onProgressSample(
                        trackId = track.stableId,
                        uri = track.uri,
                        durationMs = p.duration.takeIf { it > 0L } ?: track.durationMs,
                        positionMs = p.currentPosition,
                        isPlaying = p.isPlaying,
                        playbackSpeed = p.playbackParameters.speed,
                        nowMs = System.currentTimeMillis()
                    )
                }
                delay(1_000L)
            }
            listeningSampleJob = null
        }
    }

    private fun startPeriodicSave() {
        if (periodicSaveJob?.isActive == true) return
        periodicSaveJob = serviceScope.launch {
            while (true) {
                delay(5_000L)
                persistCurrentState(exoPlayer)
            }
        }
    }

    private fun persistCurrentState(player: Player?, forceFullQueue: Boolean = false) {
        if (player == null) return
        val count = player.mediaItemCount
        if (count == 0) return

        val descriptors = ArrayList<PlaybackStateStore.QueueItemDescriptor>(count)
        for (i in 0 until count) {
            val item = player.getMediaItemAt(i)
            val uri = item.localConfiguration?.uri?.toString()
                ?: item.mediaId.takeIf { it.isNotBlank() }
                ?: continue
            val meta = item.mediaMetadata
            val title = meta.title?.toString()?.takeIf { it.isNotBlank() }
                ?: meta.displayTitle?.toString()?.takeIf { it.isNotBlank() }
                ?: "Unknown track"
            val artist = meta.artist?.toString()?.takeIf { it.isNotBlank() } ?: "Unknown artist"
            val album = meta.albumTitle?.toString().orEmpty()
            val dateModifiedSec = meta.extras?.getLong("hifideck.track.date_modified", 0L) ?: 0L
            descriptors.add(
                PlaybackStateStore.QueueItemDescriptor(
                    uri = uri,
                    title = title,
                    artist = artist,
                    album = album,
                    dateModifiedSec = dateModifiedSec
                )
            )
        }
        if (descriptors.isEmpty()) return

        val signature = PlaybackStateStore.computeQueueSignatureFromDescriptors(descriptors)
        val safeIndex = player.currentMediaItemIndex.coerceIn(0, descriptors.lastIndex)
        val positionMs = player.currentPosition.coerceAtLeast(0L)
        val shuffleEnabled = player.shuffleModeEnabled
        val repeatMode = RepeatModeOption.fromPlayerMode(player.repeatMode)
        val speed = player.playbackParameters.speed.coerceIn(0.5f, 2.0f)

        if (!forceFullQueue && signature == lastPersistedQueueSignature) {
            val cursor = PlaybackCursorState(
                queueSignature = signature,
                currentIndex = safeIndex,
                positionMs = positionMs,
                shuffleEnabled = shuffleEnabled,
                repeatMode = repeatMode,
                playbackSpeed = speed
            )
            serviceScope.launch(Dispatchers.IO) {
                stateStore.saveCursorOnly(cursor)
            }
            return
        }

        val queue = ArrayList<LibraryTrack>(count)
        for (i in 0 until count) {
            MediaItemMapper.fromMediaItem(player.getMediaItemAt(i))?.let { queue.add(it) }
        }
        if (queue.isEmpty()) return

        lastPersistedQueueSignature = PlaybackStateStore.computeQueueSignature(queue)
        val snapshot = PersistedPlaybackState(
            queue = queue,
            currentIndex = player.currentMediaItemIndex.coerceIn(0, queue.lastIndex),
            positionMs = positionMs,
            shuffleEnabled = shuffleEnabled,
            repeatMode = repeatMode,
            playbackSpeed = speed
        )
        serviceScope.launch(Dispatchers.IO) {
            stateStore.save(snapshot)
        }
    }

    private fun isTrackUriAccessible(uriString: String): Boolean {
        val uri = runCatching { Uri.parse(uriString) }.getOrNull() ?: return false
        return runCatching {
            contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length != 0L } ?: false
        }.getOrDefault(false)
    }
}

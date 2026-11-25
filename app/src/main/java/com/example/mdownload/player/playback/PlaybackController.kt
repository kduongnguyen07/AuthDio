package com.example.mdownload.player.playback

import android.content.ComponentName
import android.content.Context
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.library.ArtworkCache
import com.example.mdownload.player.library.MusicLibraryRepository
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Single UI-facing playback controller bridged to [HiFiPlaybackService] via Media3 [MediaController].
 *
 * - Owns zero duplicate player instances; all state mirrors the active MediaSession.
 * - Reconnects automatically if the background service was stopped by the OS.
 * - Only runs a lightweight position ticker while playback is actively playing.
 */
class PlaybackController(
    private val appContext: Context,
    private val stateStore: PlaybackStateStore,
    private val artworkCache: ArtworkCache,
    private val libraryRepository: MusicLibraryRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    /**
     * Structural playback UI state updated on discrete player/queue transitions (track switch,
     * play/pause, buffering, shuffle, repeat, speed, seek, queue mutation, error) and NOT on
     * periodic playback ticks. Collecting this in root screens prevents whole-tree recomposition
     * every 250ms while keeping [positionMs] isolated to progress leaf composables.
     */
    private val _structuralState = MutableStateFlow(PlaybackUiState())
    val structuralState: StateFlow<PlaybackUiState> = _structuralState.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs.asStateFlow()

    private fun updateDiscreteState(transform: (PlaybackUiState) -> PlaybackUiState) {
        var updated: PlaybackUiState? = null
        _state.update { prev -> transform(prev).also { updated = it } }
        updated?.let { snap ->
            _structuralState.value = snap
            _positionMs.value = snap.positionMs
        }
    }

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private var pendingConnection: CompletableDeferred<MediaController?>? = null
    private var tickerJob: Job? = null
    private var sessionRestoreAttempted = false

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            syncStateFromPlayer()
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            syncStateFromPlayer()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            syncStateFromPlayer()
        }

        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            syncStateFromPlayer()
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            syncStateFromPlayer()
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            syncStateFromPlayer()
        }

        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) {
            syncStateFromPlayer()
        }

        override fun onTracksChanged(tracks: Tracks) {
            val currentTrack = _state.value.currentTrack
            val info = MediaItemMapper.extractAudioOutputInfo(tracks, currentTrack)
            updateDiscreteState { it.copy(audioOutputInfo = info) }
        }

        override fun onPlayerError(error: PlaybackException) {
            val failedTrack = _state.value.currentTrack
            val message = error.localizedMessage?.takeIf { it.isNotBlank() }
                ?: "Unable to play this audio file."
            updateDiscreteState {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    playbackError = message
                )
            }
            if (failedTrack != null) {
                scope.launch {
                    val accessible = libraryRepository.isUriAccessible(failedTrack.uri)
                    if (!accessible) {
                        libraryRepository.removeMissingTrack(failedTrack.uri)
                    }
                }
            }
        }
    }

    fun connect(restoreLastSession: Boolean = true, defaultSpeed: Float = 1.0f) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (restoreLastSession && !sessionRestoreAttempted && c.mediaItemCount == 0) {
                sessionRestoreAttempted = true
                restoreSavedSession(c, defaultSpeed)
            } else {
                syncStateFromPlayer()
            }
        }
    }

    fun playQueue(
        tracks: List<LibraryTrack>,
        startIndex: Int = 0,
        startPositionMs: Long = 0L,
        playWhenReady: Boolean = true,
        shuffle: Boolean? = null,
        playbackSpeed: Float? = null
    ) {
        if (tracks.isEmpty()) return
        val safeIndex = startIndex.coerceIn(0, tracks.lastIndex)
        scope.launch {
            val c = ensureConnected() ?: return@launch
            val firstTrack = tracks[safeIndex]
            val artworkBytes = artworkCache.loadNotificationArtworkBytes(firstTrack.uri, firstTrack.albumId)
            val mediaItems = tracks.mapIndexed { idx, track ->
                MediaItemMapper.toMediaItem(
                    track = track,
                    artworkBytes = if (idx == safeIndex) artworkBytes else null
                )
            }
            if (shuffle != null) {
                c.shuffleModeEnabled = shuffle
            }
            if (playbackSpeed != null) {
                c.playbackParameters = PlaybackParameters(playbackSpeed.coerceIn(0.5f, 2.0f))
            }
            c.setMediaItems(mediaItems, safeIndex, startPositionMs.coerceAtLeast(0L))
            c.prepare()
            c.playWhenReady = playWhenReady
            updateDiscreteState { it.copy(playbackError = null) }
            syncStateFromPlayer()
        }
    }

    fun playTrack(
        track: LibraryTrack,
        contextQueue: List<LibraryTrack> = emptyList(),
        defaultSpeed: Float? = null
    ) {
        val current = _state.value.currentTrack
        if (current != null && current.uri == track.uri && contextQueue.isEmpty()) {
            togglePlayPause()
            return
        }
        val queue = if (contextQueue.isNotEmpty() && contextQueue.any { it.uri == track.uri }) {
            contextQueue
        } else {
            listOf(track)
        }
        val idx = queue.indexOfFirst { it.uri == track.uri }.coerceAtLeast(0)
        val speed = if (_state.value.queue.isEmpty()) defaultSpeed else null
        playQueue(tracks = queue, startIndex = idx, playWhenReady = true, playbackSpeed = speed)
    }

    fun playNext(track: LibraryTrack) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (c.mediaItemCount == 0) {
                playQueue(listOf(track), startIndex = 0, playWhenReady = true)
                return@launch
            }
            val insertIndex = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
            c.addMediaItem(insertIndex, MediaItemMapper.toMediaItem(track))
            syncStateFromPlayer()
            persistControllerState(c)
        }
    }

    fun addToQueue(track: LibraryTrack) {
        addAllToQueue(listOf(track))
    }

    fun addAllToQueue(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (c.mediaItemCount == 0) {
                playQueue(tracks, startIndex = 0, playWhenReady = false)
                return@launch
            }
            c.addMediaItems(tracks.map { MediaItemMapper.toMediaItem(it) })
            syncStateFromPlayer()
            persistControllerState(c)
        }
    }

    fun removeFromQueue(index: Int) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (index !in 0 until c.mediaItemCount) return@launch
            c.removeMediaItem(index)
            syncStateFromPlayer()
            if (c.mediaItemCount == 0) {
                withContext(Dispatchers.IO) { stateStore.clear() }
            } else {
                persistControllerState(c)
            }
        }
    }

    fun moveQueueItem(fromIndex: Int, toIndex: Int) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (fromIndex !in 0 until c.mediaItemCount || toIndex !in 0 until c.mediaItemCount) return@launch
            if (fromIndex == toIndex) return@launch
            c.moveMediaItem(fromIndex, toIndex)
            syncStateFromPlayer()
            persistControllerState(c)
        }
    }

    fun skipToQueueItem(index: Int) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (index !in 0 until c.mediaItemCount) return@launch
            c.seekToDefaultPosition(index)
            c.prepare()
            c.play()
            syncStateFromPlayer()
        }
    }

    /**
     * Commits a track selected via Orbital Navigation:
     * - If [index] equals the currently active item, returns without restarting playback.
     * - If [index] differs, switches cleanly to [index] while preserving the active play/pause state.
     */
    fun selectQueueItemPreservingPlayState(index: Int) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (index !in 0 until c.mediaItemCount) return@launch
            if (index == c.currentMediaItemIndex) {
                syncStateFromPlayer()
                return@launch
            }
            val shouldPlay = c.isPlaying || c.playWhenReady
            c.seekToDefaultPosition(index)
            c.prepare()
            c.playWhenReady = shouldPlay
            syncStateFromPlayer()
            persistControllerState(c)
        }
    }

    fun clearQueue() {
        scope.launch {
            stopTicker()
            val c = controller
            c?.stop()
            c?.clearMediaItems()
            updateDiscreteState {
                PlaybackUiState(
                    shuffleEnabled = it.shuffleEnabled,
                    repeatMode = it.repeatMode,
                    playbackSpeed = it.playbackSpeed,
                    isServiceConnected = c?.isConnected == true
                )
            }
            withContext(Dispatchers.IO) { stateStore.clear() }
        }
    }

    fun togglePlayPause() {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (c.mediaItemCount == 0) return@launch
            if (c.isPlaying) {
                c.pause()
            } else {
                if (c.playbackState == Player.STATE_ENDED) {
                    c.seekTo(0L)
                }
                if (c.playbackState == Player.STATE_IDLE) {
                    c.prepare()
                }
                c.play()
            }
            syncStateFromPlayer()
        }
    }

    fun skipToNext() {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (c.hasNextMediaItem()) {
                c.seekToNextMediaItem()
                if (!c.isPlaying && c.playWhenReady) c.play()
            } else if (c.mediaItemCount > 0) {
                c.seekTo(0, 0L)
            }
            syncStateFromPlayer()
        }
    }

    fun skipToPrevious() {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            if (c.currentPosition > 3_000L || !c.hasPreviousMediaItem()) {
                c.seekTo(0L)
            } else {
                c.seekToPreviousMediaItem()
            }
            syncStateFromPlayer()
        }
    }

    fun seekTo(positionMs: Long) {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            val clamped = positionMs.coerceAtLeast(0L)
            c.seekTo(clamped)
            updateDiscreteState { it.copy(positionMs = clamped) }
        }
    }

    fun toggleShuffle() {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            c.shuffleModeEnabled = !c.shuffleModeEnabled
            syncStateFromPlayer()
        }
    }

    fun cycleRepeatMode() {
        scope.launch {
            val c = ensureConnected() ?: return@launch
            val next = RepeatModeOption.fromPlayerMode(c.repeatMode).next()
            c.repeatMode = next.playerRepeatMode
            syncStateFromPlayer()
        }
    }

    fun setPlaybackSpeed(speed: Float) {
        scope.launch {
            val clamped = speed.coerceIn(0.5f, 2.0f)
            val c = ensureConnected() ?: return@launch
            c.playbackParameters = PlaybackParameters(clamped)
            syncStateFromPlayer()
        }
    }

    fun clearError() {
        updateDiscreteState { it.copy(playbackError = null) }
    }

    fun stop() {
        clearQueue()
    }

    fun removeTrackIfPresent(uriString: String) {
        scope.launch {
            val c = controller ?: return@launch
            for (i in c.mediaItemCount - 1 downTo 0) {
                if (c.getMediaItemAt(i).mediaId == uriString) {
                    c.removeMediaItem(i)
                }
            }
            syncStateFromPlayer()
        }
    }

    /**
     * Synchronizes updated library metadata into both UI state and active Media3 [MediaController]
     * queue items via `replaceMediaItem` (updating notification, lock screen, and Bluetooth AVRCP
     * metadata in place without interrupting or restarting playback).
     */
    fun refreshTrackMetadataFromLibrary(
        editedTrackUri: String? = null,
        artworkModified: Boolean = false
    ) {
        syncStateFromPlayer()
        val c = controller ?: return
        if (!c.isConnected || c.mediaItemCount == 0) return

        val latestByUri = libraryRepository.tracks.value.associateBy { it.uri }
        val targetIndices = mutableListOf<Pair<Int, LibraryTrack>>()
        for (i in 0 until c.mediaItemCount) {
            val existing = c.getMediaItemAt(i)
            val uri = existing.mediaId
            if (editedTrackUri != null && uri != editedTrackUri) continue
            val fresh = latestByUri[uri] ?: continue
            val meta = existing.mediaMetadata
            val changed = artworkModified ||
                meta.title?.toString() != fresh.title ||
                meta.artist?.toString() != fresh.artist ||
                meta.albumTitle?.toString().orEmpty() != fresh.album ||
                meta.albumArtist?.toString().orEmpty() != fresh.albumArtist ||
                meta.genre?.toString().orEmpty() != fresh.genre
            if (changed) {
                targetIndices.add(i to fresh)
            }
        }
        if (targetIndices.isEmpty()) return

        scope.launch {
            val activeIndex = c.currentMediaItemIndex
            for ((index, freshTrack) in targetIndices) {
                val needArtBytes = artworkModified || index == activeIndex
                val artBytes = if (needArtBytes) {
                    if (artworkModified) artworkCache.invalidate(freshTrack.uri)
                    artworkCache.loadNotificationArtworkBytes(freshTrack.uri, freshTrack.albumId)
                } else {
                    null
                }
                val liveController = controller ?: break
                if (!liveController.isConnected || index !in 0 until liveController.mediaItemCount) continue
                val currentItem = liveController.getMediaItemAt(index)
                if (currentItem.mediaId != freshTrack.uri) continue

                val existingArt = if (artworkModified) null else currentItem.mediaMetadata.artworkData
                val updated = MediaItemMapper.toMediaItem(
                    track = freshTrack,
                    artworkBytes = artBytes ?: existingArt
                )
                // Media3 replaces MediaMetadata in place when the media URI is unchanged without restarting playback
                liveController.replaceMediaItem(index, updated)
            }
            syncStateFromPlayer()
            controller?.takeIf { it.isConnected }?.let { persistControllerState(it) }
        }
    }

    private suspend fun restoreSavedSession(c: MediaController, defaultSpeed: Float) {
        val saved = withContext(Dispatchers.IO) { stateStore.load() }
        if (saved.queue.isEmpty()) return

        val accessible = withContext(Dispatchers.IO) {
            saved.queue.filter { libraryRepository.isUriAccessible(it.uri) }
        }
        if (accessible.isEmpty()) {
            withContext(Dispatchers.IO) { stateStore.clear() }
            return
        }

        val targetTrack = saved.queue.getOrNull(saved.currentIndex)
        val restoredIndex = if (targetTrack != null) {
            accessible.indexOfFirst { it.uri == targetTrack.uri }.coerceAtLeast(0)
        } else 0
        val restoredPos = if (accessible.getOrNull(restoredIndex)?.uri == targetTrack?.uri) {
            saved.positionMs.coerceAtLeast(0L)
        } else 0L

        val artworkBytes = accessible.getOrNull(restoredIndex)?.let {
            artworkCache.loadNotificationArtworkBytes(it.uri, it.albumId)
        }
        val items = accessible.mapIndexed { idx, track ->
            MediaItemMapper.toMediaItem(
                track = track,
                artworkBytes = if (idx == restoredIndex) artworkBytes else null
            )
        }

        c.shuffleModeEnabled = saved.shuffleEnabled
        c.repeatMode = saved.repeatMode.playerRepeatMode
        c.playbackParameters = PlaybackParameters(
            saved.playbackSpeed.takeIf { it in 0.5f..2.0f } ?: defaultSpeed.coerceIn(0.5f, 2.0f)
        )
        c.setMediaItems(items, restoredIndex, restoredPos)
        c.prepare()
        c.playWhenReady = false
        syncStateFromPlayer()
    }

    private suspend fun ensureConnected(): MediaController? {
        controller?.takeIf { it.isConnected }?.let { return it }
        pendingConnection?.let { return it.await() }

        val deferred = CompletableDeferred<MediaController?>()
        pendingConnection = deferred

        val token = SessionToken(appContext, ComponentName(appContext, HiFiPlaybackService::class.java))
        val future = MediaController.Builder(appContext, token).buildAsync()
        controllerFuture = future

        future.addListener(
            {
                val connected = runCatching { future.get() }.getOrNull()
                if (connected != null) {
                    controller?.removeListener(playerListener)
                    controller = connected
                    connected.addListener(playerListener)
                    updateDiscreteState { it.copy(isServiceConnected = true) }
                    syncStateFromPlayer()
                    if (connected.isPlaying) startTicker()
                } else {
                    updateDiscreteState { it.copy(isServiceConnected = false) }
                }
                pendingConnection = null
                deferred.complete(connected)
            },
            ContextCompat.getMainExecutor(appContext)
        )

        return deferred.await()
    }

    private fun syncStateFromPlayer() {
        val c = controller ?: return
        if (!c.isConnected) {
            updateDiscreteState { it.copy(isServiceConnected = false) }
            return
        }
        val count = c.mediaItemCount
        val latestLibraryByUri = libraryRepository.tracks.value.associateBy { it.uri }
        val queue = buildList(count) {
            for (i in 0 until count) {
                MediaItemMapper.fromMediaItem(c.getMediaItemAt(i))?.let { mapped ->
                    val fresh = latestLibraryByUri[mapped.uri]
                    add(
                        if (fresh != null) {
                            mapped.copy(
                                title = fresh.title,
                                artist = fresh.artist,
                                album = fresh.album,
                                albumArtist = fresh.albumArtist,
                                genre = fresh.genre,
                                year = fresh.year,
                                trackNumber = fresh.trackNumber,
                                dateModifiedSec = fresh.dateModifiedSec
                            )
                        } else {
                            mapped
                        }
                    )
                }
            }
        }
        val currentIdx = if (queue.isEmpty()) -1 else c.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
        val orderedIndices = computeOrderedQueueIndices(c, queue.size)
        val currentTrack = queue.getOrNull(currentIdx)
        val reportedDuration = c.duration.takeIf { it > 0L }
            ?: currentTrack?.durationMs
            ?: 0L
        val seekable = currentTrack != null &&
            reportedDuration > 0L &&
            (c.isCurrentMediaItemSeekable || c.playbackState == Player.STATE_IDLE || c.playbackState == Player.STATE_BUFFERING)
        val outputInfo = MediaItemMapper.extractAudioOutputInfo(c.currentTracks, currentTrack)

        updateDiscreteState { prev ->
            prev.copy(
                currentTrack = currentTrack,
                queue = queue,
                currentIndex = currentIdx,
                orderedQueueIndices = orderedIndices,
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                positionMs = c.currentPosition.coerceAtLeast(0L),
                durationMs = reportedDuration,
                isSeekable = seekable,
                shuffleEnabled = c.shuffleModeEnabled,
                repeatMode = RepeatModeOption.fromPlayerMode(c.repeatMode),
                playbackSpeed = c.playbackParameters.speed,
                audioOutputInfo = if (outputInfo.isAvailable) outputInfo else MediaItemMapper.fallbackAudioOutputInfo(currentTrack),
                isServiceConnected = true
            )
        }
    }

    private fun computeOrderedQueueIndices(c: MediaController, count: Int): List<Int> {
        if (count <= 0) return emptyList()
        if (!c.shuffleModeEnabled || count == 1) return (0 until count).toList()
        val timeline = c.currentTimeline
        if (timeline.isEmpty || timeline.windowCount != count) return (0 until count).toList()
        return runCatching {
            val result = ArrayList<Int>(count)
            val seen = HashSet<Int>(count)
            var cursor = timeline.getFirstWindowIndex(true)
            while (cursor != C.INDEX_UNSET && cursor in 0 until count && seen.add(cursor)) {
                result.add(cursor)
                cursor = timeline.getNextWindowIndex(cursor, Player.REPEAT_MODE_OFF, true)
            }
            if (result.size == count) result else (0 until count).toList()
        }.getOrElse { (0 until count).toList() }
    }

    private fun persistControllerState(c: MediaController) {
        val count = c.mediaItemCount
        if (count == 0) return
        val queue = buildList(count) {
            for (i in 0 until count) {
                MediaItemMapper.fromMediaItem(c.getMediaItemAt(i))?.let { add(it) }
            }
        }
        if (queue.isEmpty()) return
        val snapshot = PersistedPlaybackState(
            queue = queue,
            currentIndex = c.currentMediaItemIndex.coerceIn(0, queue.lastIndex),
            positionMs = c.currentPosition.coerceAtLeast(0L),
            shuffleEnabled = c.shuffleModeEnabled,
            repeatMode = RepeatModeOption.fromPlayerMode(c.repeatMode),
            playbackSpeed = c.playbackParameters.speed.coerceIn(0.5f, 2.0f)
        )
        scope.launch(Dispatchers.IO) { stateStore.save(snapshot) }
    }

    private fun startTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            while (true) {
                val c = controller
                if (c == null || !c.isConnected || !c.isPlaying) {
                    break
                }
                val pos = c.currentPosition.coerceAtLeast(0L)
                val dur = c.duration.takeIf { it > 0L } ?: _state.value.durationMs
                val seekable = dur > 0L && c.isCurrentMediaItemSeekable
                _positionMs.value = pos
                _state.update {
                    it.copy(
                        positionMs = pos,
                        durationMs = dur,
                        isSeekable = seekable
                    )
                }
                val structural = _structuralState.value
                val crossedPrevThreshold = (structural.positionMs > 3_000L) != (pos > 3_000L)
                if (structural.durationMs != dur || structural.isSeekable != seekable || crossedPrevThreshold) {
                    _structuralState.value = structural.copy(
                        positionMs = if (crossedPrevThreshold) pos else structural.positionMs,
                        durationMs = dur,
                        isSeekable = seekable
                    )
                }
                delay(250L)
            }
            tickerJob = null
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }
}

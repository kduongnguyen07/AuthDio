package com.example.mdownload

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.AudioFormat
import com.example.mdownload.core.DiscoverResultItem
import com.example.mdownload.core.DiscoverSearchFilter
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.DownloadPreset
import com.example.mdownload.core.EngineState
import com.example.mdownload.core.HistoryRecord
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.PlaylistTrackItem
import com.example.mdownload.core.SourcePreviewInfo
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.TrackMetadata
import com.example.mdownload.core.YouTubeUrlParser
import com.example.mdownload.core.audio.AudioInspectorEngine
import com.example.mdownload.core.audio.MissingReplayGainBehavior
import com.example.mdownload.core.audio.PlaybackOutputSnapshot
import com.example.mdownload.core.audio.ReplayGainConfig
import com.example.mdownload.core.audio.ReplayGainMode
import com.example.mdownload.core.metadata.BatchMetadataPatch
import com.example.mdownload.core.metadata.EditableTrackMetadata
import com.example.mdownload.core.metadata.MetadataWriteResult
import com.example.mdownload.engine.CoverArtHelper
import com.example.mdownload.engine.YouTubeSearchClient
import com.example.mdownload.player.library.ArtistCatalogState
import com.example.mdownload.player.library.ListeningStats
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistEvalContext
import com.example.mdownload.player.library.SmartPlaylistEvaluator
import com.example.mdownload.player.library.UserPlaylist
import com.example.mdownload.player.lyrics.LyricsCandidate
import com.example.mdownload.player.lyrics.LyricsUiState
import com.example.mdownload.player.playback.GainCoordinatorState
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.playback.SleepTimerState
import com.example.mdownload.player.ui.AudioInspectorUiState
import com.example.mdownload.player.ui.BatchMetadataEditorUiState
import com.example.mdownload.player.ui.TrackMetadataEditorUiState
import com.yausername.youtubedl_android.YoutubeDL
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

enum class AppTab(val label: String) {
    HOME("Home"),
    DISCOVER("Discover"),
    DOWNLOADS("Downloads"),
    LIBRARY("Library"),
    SETTINGS("Settings")
}

data class PlaylistPickerState(
    val sourceUrl: String,
    val title: String,
    val isMix: Boolean,
    val singleVideoUrl: String?,
    val tracks: List<PlaylistTrackItem>,
    val isLoading: Boolean = false,
    val error: String? = null
)

data class MetadataEditorState(
    val jobId: String? = null, // null means editing composer or discover pending metadata
    val sourceTitle: String,
    val sourceThumbnailUrl: String = "",
    val sourceVideoId: String? = null,
    val metadata: TrackMetadata,
    val discoverItem: DiscoverResultItem? = null
)

data class HomeUiState(
    val urlInput: String = "",
    val preview: SourcePreviewInfo? = null,
    val isInspecting: Boolean = false,
    val previewError: String? = null,
    val pendingMetadata: TrackMetadata = TrackMetadata()
)

data class DiscoverUiState(
    val query: String = "",
    val submittedQuery: String = "",
    val filter: DiscoverSearchFilter = DiscoverSearchFilter.ALL,
    val results: List<DiscoverResultItem> = emptyList(),
    val suggestions: List<String> = emptyList(),
    val recentQueries: List<String> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as MDownloadApp
    private val engine = app.engine
    private val queue = app.queueManager
    private val exporter = app.exporter
    private val library = app.libraryRepository
    private val player = app.playbackController
    private val lyricsRepo = app.lyricsRepository
    private val gainCoordinator = app.gainCoordinator
    private val sleepTimerController = app.sleepTimerController
    private val audioAnalysisRepo = app.audioAnalysisRepository
    private val metadataRepo = app.metadataRepository
    private val listeningHistoryRepo = app.listeningHistoryRepository
    private val smartPlaylistRepo = app.smartPlaylistRepository

    val engineState: StateFlow<EngineState> = engine.state
    val settings: StateFlow<AppSettings> = queue.settings
    val jobs: StateFlow<List<DownloadJob>> = queue.jobs
    val history: StateFlow<List<HistoryRecord>> = queue.history
    val isQueueRunning: StateFlow<Boolean> = queue.isRunning
    val isQueuePaused: StateFlow<Boolean> = queue.isPaused
    val totalSpeed: StateFlow<Double> = queue.totalSpeed
    val logs: StateFlow<List<String>> = queue.logs
    val libraryTracks: StateFlow<List<LibraryTrack>> = app.libraryTracks
    val isLibrarySyncing: StateFlow<Boolean> = app.isLibrarySyncing
    val hasAudioPermission: StateFlow<Boolean> = app.hasAudioPermission
    val userPlaylists: StateFlow<List<UserPlaylist>> = app.userPlaylists
    val artistCatalog: StateFlow<ArtistCatalogState> = app.artistCatalog
    val playbackState: StateFlow<PlaybackUiState> = player.structuralState
    val playbackPositionMs: StateFlow<Long> = player.positionMs
    val lyricsState: StateFlow<LyricsUiState> = lyricsRepo.uiState
    val gainState: StateFlow<GainCoordinatorState> = gainCoordinator.state
    val sleepTimerState: StateFlow<SleepTimerState> = sleepTimerController.state
    val listeningStats: StateFlow<Map<String, ListeningStats>> = listeningHistoryRepo.statsByTrackId
    val customSmartPlaylists: StateFlow<List<SmartPlaylistDefinition>> = smartPlaylistRepo.customPlaylists
    val builtInSmartPlaylists: List<SmartPlaylistDefinition> = smartPlaylistRepo.builtInPlaylists
    val audioAnalysisCacheVersion: StateFlow<Long> = audioAnalysisRepo.cacheVersion

    private data class SmartPlaylistEvalSnapshot(
        val tracks: List<LibraryTrack>,
        val stats: Map<String, ListeningStats>,
        val playlists: List<UserPlaylist>,
        val definitions: List<SmartPlaylistDefinition>
    )

    /**
     * Off-Main-Thread reactive evaluation of all built-in and custom Smart Playlists.
     * Uses `.conflate()` + `.mapLatest` on [Dispatchers.Default] so burst library/listening
     * updates coalesce cleanly without blocking Compose frames.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val evaluatedSmartPlaylists: StateFlow<Map<String, List<LibraryTrack>>> = combine(
        libraryTracks,
        listeningStats,
        userPlaylists,
        customSmartPlaylists,
        audioAnalysisCacheVersion
    ) { tracks, stats, playlists, customDefs, _ ->
        SmartPlaylistEvalSnapshot(
            tracks = tracks,
            stats = stats,
            playlists = playlists,
            definitions = builtInSmartPlaylists + customDefs
        )
    }
        .conflate()
        .mapLatest { snap ->
            withContext(Dispatchers.Default) {
                val favUris = snap.playlists
                    .firstOrNull { it.name.equals("Favorites", ignoreCase = true) }
                    ?.trackUris
                    ?.toSet()
                    ?: emptySet()
                val ctx = SmartPlaylistEvalContext(
                    statsByTrackId = snap.stats,
                    favoriteUris = favUris,
                    analysisByUri = audioAnalysisRepo.getCachedSnapshotByUri()
                )
                SmartPlaylistEvaluator.evaluateAll(snap.definitions, snap.tracks, ctx)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000L),
            initialValue = emptyMap()
        )

    init {
        gainCoordinator.updateConfig(ReplayGainConfig.fromAppSettings(settings.value))
        viewModelScope.launch {
            playbackState
                .map { it.currentTrack }
                .distinctUntilChanged { old, new -> old?.uri == new?.uri }
                .collect { track ->
                    lyricsRepo.loadForTrack(
                        track = track,
                        customTreeUri = settings.value.storageTreeUri,
                        allowOnlineLookup = true
                    )
                }
        }
    }

    private val _selectedTab = MutableStateFlow(AppTab.HOME)
    val selectedTab: StateFlow<AppTab> = _selectedTab.asStateFlow()

    private val _isNowPlayingOpen = MutableStateFlow(false)
    val isNowPlayingOpen: StateFlow<Boolean> = _isNowPlayingOpen.asStateFlow()

    private val _playlistPickerTrack = MutableStateFlow<LibraryTrack?>(null)
    val playlistPickerTrack: StateFlow<LibraryTrack?> = _playlistPickerTrack.asStateFlow()

    private val _homeState = MutableStateFlow(HomeUiState())
    val homeState: StateFlow<HomeUiState> = _homeState.asStateFlow()

    private val _discoverState = MutableStateFlow(DiscoverUiState())
    val discoverState: StateFlow<DiscoverUiState> = _discoverState.asStateFlow()

    private val _playlistPicker = MutableStateFlow<PlaylistPickerState?>(null)
    val playlistPicker: StateFlow<PlaylistPickerState?> = _playlistPicker.asStateFlow()

    private val _metadataEditor = MutableStateFlow<MetadataEditorState?>(null)
    val metadataEditor: StateFlow<MetadataEditorState?> = _metadataEditor.asStateFlow()

    private val _audioInspectorState = MutableStateFlow(AudioInspectorUiState())
    val audioInspectorState: StateFlow<AudioInspectorUiState> = _audioInspectorState.asStateFlow()

    private val _isSleepTimerOpen = MutableStateFlow(false)
    val isSleepTimerOpen: StateFlow<Boolean> = _isSleepTimerOpen.asStateFlow()

    private val _trackMetadataEditorState = MutableStateFlow<TrackMetadataEditorUiState?>(null)
    val trackMetadataEditorState: StateFlow<TrackMetadataEditorUiState?> = _trackMetadataEditorState.asStateFlow()

    private val _batchMetadataEditorState = MutableStateFlow<BatchMetadataEditorUiState?>(null)
    val batchMetadataEditorState: StateFlow<BatchMetadataEditorUiState?> = _batchMetadataEditorState.asStateFlow()

    private val _pendingWritePermissionUris = MutableStateFlow<List<Uri>?>(null)
    val pendingWritePermissionUris: StateFlow<List<Uri>?> = _pendingWritePermissionUris.asStateFlow()

    private val _bannerMessage = MutableStateFlow<String?>(null)
    val bannerMessage: StateFlow<String?> = _bannerMessage.asStateFlow()

    private var previewJob: Job? = null
    private var previewProcessId: String? = null
    private var playlistInspectJob: Job? = null
    private var playlistProcessId: String? = null
    private var discoverSearchJob: Job? = null
    private var discoverSearchProcessId: String? = null
    private var discoverSuggestJob: Job? = null
    private var inspectorJob: Job? = null
    private var loudnessScanJob: Job? = null
    private var batchMetadataJob: Job? = null
    private var pendingSingleSave: Pair<LibraryTrack, EditableTrackMetadata>? = null
    private var pendingBatchSave: Pair<List<LibraryTrack>, BatchMetadataPatch>? = null
    private var pendingLyricsEmbed: Boolean = false

    fun selectTab(tab: AppTab) {
        _selectedTab.value = tab
        if (tab == AppTab.LIBRARY) {
            refreshLibrary()
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun showMessage(message: String) {
        _bannerMessage.value = message
    }

    fun onSharedTextReceived(text: String) {
        val payload = YouTubeUrlParser.extractSharePayload(text)
        if (payload.urls.isNotEmpty()) {
            _selectedTab.value = AppTab.HOME
            onUrlInputChanged(payload.urls.joinToString("\n"))
            showMessage("Loaded shared link.")
        }
    }

    fun openSharedPlaylist(url: String) {
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(url) }.getOrNull() ?: return
        _selectedTab.value = AppTab.HOME
        openPlaylistPicker(normalized)
    }

    fun onUrlInputChanged(value: String) {
        _homeState.update {
            it.copy(
                urlInput = value,
                previewError = null,
                pendingMetadata = if (value != it.urlInput) TrackMetadata() else it.pendingMetadata
            )
        }
        schedulePreviewInspection(value)
    }

    fun clearUrlInput() {
        cancelPreviewInspection()
        _homeState.value = HomeUiState()
    }

    private fun cancelPreviewInspection() {
        previewJob?.cancel()
        previewJob = null
        previewProcessId?.let { engine.cancelProcess(it) }
        previewProcessId = null
        _homeState.update { it.copy(isInspecting = false) }
    }

    private fun schedulePreviewInspection(rawText: String) {
        cancelPreviewInspection()
        val lines = YouTubeUrlParser.extractUrlsFromText(rawText)
        if (lines.size != 1) {
            _homeState.update { it.copy(preview = null, isInspecting = false, previewError = null) }
            return
        }
        val candidate = lines.first()
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(candidate) }.getOrNull()
        if (normalized == null) {
            _homeState.update { it.copy(preview = null, isInspecting = false) }
            return
        }

        // Do not run competing network probes while queue is held or downloading in gentle mode.
        if (settings.value.queueBlockReason.isNotBlank() ||
            (settings.value.gentleRequests && isQueueRunning.value)
        ) {
            _homeState.update { it.copy(preview = null, isInspecting = false) }
            return
        }

        previewJob = viewModelScope.launch {
            delay(550L)
            val procId = "preview_${UUID.randomUUID().toString().take(8)}"
            previewProcessId = procId
            _homeState.update { it.copy(isInspecting = true, previewError = null) }

            // For mixed watch+playlist links, inspect the single video first for quick preview
            // (matching HiFi Deck's mixed video/Mix preview fix in DESIGN.md).
            val previewTarget = YouTubeUrlParser.singleVideoUrl(normalized) ?: normalized
            val result = engine.inspectSource(
                rawUrl = previewTarget,
                gentleRequests = settings.value.gentleRequests,
                processId = procId
            )
            if (previewProcessId != procId) return@launch
            previewProcessId = null

            result.onSuccess { info ->
                _homeState.update { current ->
                    val autoMeta = if (current.pendingMetadata.isEmpty) {
                        TrackMetadata(
                            title = TextUtils.cleanSourceTitle(info.title),
                            artist = info.artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty(),
                            album = info.album,
                            year = info.releaseYear
                        )
                    } else {
                        current.pendingMetadata
                    }
                    current.copy(
                        isInspecting = false,
                        preview = info.copy(
                            sourceUrl = candidate,
                            normalizedUrl = normalized,
                            isPlaylist = info.isPlaylist || YouTubeUrlParser.isPlaylist(normalized),
                            isMix = YouTubeUrlParser.isMixPlaylist(normalized),
                            singleVideoUrl = YouTubeUrlParser.singleVideoUrl(normalized)
                        ),
                        pendingMetadata = autoMeta,
                        previewError = null
                    )
                }
            }.onFailure { err ->
                _homeState.update {
                    it.copy(
                        isInspecting = false,
                        preview = null,
                        previewError = err.message ?: "Could not preview source."
                    )
                }
            }
        }
    }

    fun applyPreset(preset: DownloadPreset) {
        queue.updateSettings {
            it.copy(
                audioFormat = preset.format,
                audioQuality = preset.quality,
                asciiNames = preset.asciiNames
            )
        }
    }

    fun submitComposerUrls(onlySingleVideo: Boolean = false, autoStart: Boolean = false) {
        val raw = _homeState.value.urlInput
        val urls = YouTubeUrlParser.extractUrlsFromText(raw)
        if (urls.isEmpty()) {
            showMessage("Paste a YouTube, YouTube Music, or supported media link first.")
            return
        }

        val validNormalized = mutableListOf<String>()
        val invalid = mutableListOf<String>()
        for (u in urls) {
            runCatching { YouTubeUrlParser.normalizeUrl(u) }
                .onSuccess { validNormalized.add(it) }
                .onFailure { invalid.add(u) }
        }
        if (validNormalized.isEmpty()) {
            showMessage("Enter a valid YouTube, YouTube Music, or yt-dlp-supported media link.")
            return
        }

        val preview = _homeState.value.preview
        val isPreviewPlaylist = preview != null &&
            validNormalized.size == 1 &&
            preview.normalizedUrl == validNormalized.first() &&
            preview.isPlaylist

        // Single playlist link -> open explicit playlist track selector (never queue whole playlist silently!)
        if (validNormalized.size == 1 &&
            (YouTubeUrlParser.isPlaylist(validNormalized.first()) || isPreviewPlaylist) &&
            !onlySingleVideo
        ) {
            openPlaylistPicker(validNormalized.first())
            return
        }

        val options = settings.value.toJobOptions()
        val pendingMeta = TextUtils.cleanMetadata(_homeState.value.pendingMetadata)

        val jobsToAdd = mutableListOf<DownloadJob>()
        val seenKeys = mutableSetOf<String>()
        var skippedPlaylists = 0
        var skippedDuplicateQueue = 0
        var skippedDuplicateLibrary = 0
        for (normalized in validNormalized) {
            val single = YouTubeUrlParser.singleVideoUrl(normalized)
            if (single == null) {
                skippedPlaylists++
                continue
            }
            val videoId = YouTubeUrlParser.extractVideoId(single)
            val dedupKey = videoId ?: single
            if (!seenKeys.add(dedupKey)) {
                skippedDuplicateQueue++
                continue
            }
            when (duplicateStatus(videoId, single)) {
                DuplicateStatus.IN_QUEUE -> {
                    skippedDuplicateQueue++
                    continue
                }
                DuplicateStatus.IN_LIBRARY -> {
                    skippedDuplicateLibrary++
                    continue
                }
                DuplicateStatus.NONE -> Unit
            }
            val matchedPreview = preview?.takeIf { it.normalizedUrl == normalized || it.singleVideoUrl == single }
            val sourceTitle = matchedPreview?.title?.takeIf { it.isNotBlank() } ?: single
            val meta = if (validNormalized.size == 1) pendingMeta else TrackMetadata()
            jobsToAdd.add(
                DownloadJob(
                    url = single,
                    title = meta.title.ifBlank { TextUtils.cleanSourceTitle(sourceTitle) },
                    sourceTitle = sourceTitle,
                    artist = matchedPreview?.artist.orEmpty(),
                    thumbnailUrl = matchedPreview?.thumbnailUrl.orEmpty(),
                    options = options,
                    metadata = meta
                )
            )
        }

        if (jobsToAdd.isEmpty()) {
            when {
                skippedPlaylists > 0 -> openPlaylistPicker(validNormalized.first())
                skippedDuplicateLibrary > 0 -> showMessage("Already downloaded.")
                skippedDuplicateQueue > 0 -> showMessage("Already in queue.")
            }
            return
        }

        cancelPreviewInspection()
        queue.enqueueTracks(jobsToAdd, autoStart = autoStart)
        _homeState.value = HomeUiState()
        val totalDuplicates = skippedDuplicateQueue + skippedDuplicateLibrary
        val msg = buildString {
            append("Added ${jobsToAdd.size} track")
            if (jobsToAdd.size > 1) append("s")
            append(" to queue")
            if (totalDuplicates > 0) append(" ($totalDuplicates duplicate skipped)")
            if (skippedPlaylists > 0) append(" ($skippedPlaylists playlist link skipped — paste alone to pick tracks)")
            if (invalid.isNotEmpty()) append(" (${invalid.size} invalid line ignored)")
        }
        showMessage(msg)
        if (autoStart) {
            _selectedTab.value = AppTab.DOWNLOADS
        }
    }

    private enum class DuplicateStatus { NONE, IN_QUEUE, IN_LIBRARY }

    private fun duplicateStatus(videoId: String?, normalizedUrl: String? = null): DuplicateStatus {
        val inQueue = jobs.value.any { job ->
            !job.isConnectionCheck && (
                (!videoId.isNullOrBlank() && YouTubeUrlParser.extractVideoId(job.url) == videoId) ||
                    (!normalizedUrl.isNullOrBlank() && job.url == normalizedUrl)
                )
        }
        if (inQueue) return DuplicateStatus.IN_QUEUE
        if (!videoId.isNullOrBlank() &&
            settings.value.skipDuplicates &&
            libraryTracks.value.any { it.videoId == videoId }
        ) {
            return DuplicateStatus.IN_LIBRARY
        }
        return DuplicateStatus.NONE
    }

    fun addFromDiscover(url: String, pageTitle: String, onlySingleVideo: Boolean) {
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(url) }.getOrNull() ?: return
        if (YouTubeUrlParser.isPlaylist(normalized) && !onlySingleVideo) {
            openPlaylistPicker(normalized)
            return
        }
        val single = YouTubeUrlParser.singleVideoUrl(normalized) ?: return
        val cleanTitle = pageTitle.removeSuffix(" - YouTube").trim().ifBlank { single }
        val videoId = YouTubeUrlParser.extractVideoId(single)
        when (duplicateStatus(videoId)) {
            DuplicateStatus.IN_QUEUE -> {
                showMessage("Already in queue: \"${TextUtils.cleanSourceTitle(cleanTitle)}\"")
                return
            }
            DuplicateStatus.IN_LIBRARY -> {
                showMessage("Already downloaded: \"${TextUtils.cleanSourceTitle(cleanTitle)}\"")
                return
            }
            DuplicateStatus.NONE -> Unit
        }
        val thumb = if (!videoId.isNullOrBlank()) "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" else ""
        val job = DownloadJob(
            url = single,
            title = TextUtils.cleanSourceTitle(cleanTitle),
            sourceTitle = cleanTitle,
            thumbnailUrl = thumb,
            options = settings.value.toJobOptions()
        )
        queue.enqueueTracks(listOf(job), autoStart = false)
        showMessage("Added \"${job.title}\" to queue.")
    }

    fun onDiscoverQueryChanged(value: String) {
        _discoverState.update { it.copy(query = value) }
        discoverSuggestJob?.cancel()
        val trimmed = value.trim()
        if (trimmed.length < 2 || YouTubeUrlParser.isValidUrl(trimmed)) {
            _discoverState.update { it.copy(suggestions = emptyList()) }
            return
        }
        discoverSuggestJob = viewModelScope.launch {
            delay(250L)
            val list = YouTubeSearchClient.fetchSuggestions(trimmed)
            if (_discoverState.value.query.trim() == trimmed) {
                _discoverState.update { it.copy(suggestions = list) }
            }
        }
    }

    fun clearDiscoverQuery() {
        discoverSuggestJob?.cancel()
        _discoverState.update { it.copy(query = "", suggestions = emptyList()) }
    }

    fun cancelDiscoverSearch() {
        discoverSearchJob?.cancel()
        discoverSearchJob = null
        discoverSearchProcessId?.let { engine.cancelProcess(it) }
        discoverSearchProcessId = null
        _discoverState.update { it.copy(isLoading = false) }
    }

    fun setDiscoverFilter(filter: DiscoverSearchFilter) {
        val current = _discoverState.value
        if (current.filter == filter) return
        _discoverState.update { it.copy(filter = filter) }
        val activeQuery = current.submittedQuery.ifBlank { current.query.trim() }
        if (activeQuery.isNotBlank()) {
            submitDiscoverSearch(activeQuery, filter)
        }
    }

    fun submitDiscoverSearch(
        explicitQuery: String? = null,
        explicitFilter: DiscoverSearchFilter? = null
    ) {
        val raw = (explicitQuery ?: _discoverState.value.query).trim()
        val filter = explicitFilter ?: _discoverState.value.filter
        if (raw.isEmpty()) return

        discoverSuggestJob?.cancel()
        cancelDiscoverSearch()

        val updatedRecent = (listOf(raw) + _discoverState.value.recentQueries.filterNot {
            it.equals(raw, ignoreCase = true)
        }).take(8)

        _discoverState.update {
            it.copy(
                query = raw,
                submittedQuery = raw,
                filter = filter,
                suggestions = emptyList(),
                recentQueries = updatedRecent,
                isLoading = true,
                error = null
            )
        }

        // Direct YouTube link pasted into search -> inspect natively via yt-dlp!
        val directNormalized = runCatching { YouTubeUrlParser.normalizeUrl(raw) }.getOrNull()
        if (directNormalized != null) {
            if (YouTubeUrlParser.isPlaylist(directNormalized)) {
                _discoverState.update { it.copy(isLoading = false) }
                openPlaylistPicker(directNormalized)
                return
            }
            discoverSearchJob = viewModelScope.launch {
                val procId = "discover_url_${UUID.randomUUID().toString().take(8)}"
                discoverSearchProcessId = procId
                val res = engine.inspectSource(
                    rawUrl = directNormalized,
                    gentleRequests = settings.value.gentleRequests,
                    processId = procId
                )
                if (discoverSearchProcessId != procId) return@launch
                discoverSearchProcessId = null
                res.onSuccess { info ->
                    val videoId = YouTubeUrlParser.extractVideoId(info.singleVideoUrl ?: directNormalized).orEmpty()
                    val item = DiscoverResultItem(
                        id = videoId.ifBlank { directNormalized },
                        url = info.singleVideoUrl ?: directNormalized,
                        title = info.title,
                        artist = info.artist,
                        durationText = TextUtils.formatDurationSeconds(info.durationSec).takeIf { it != "—" }.orEmpty(),
                        durationSec = info.durationSec,
                        thumbnailUrl = info.thumbnailUrl,
                        isPlaylist = false
                    )
                    _discoverState.update { it.copy(isLoading = false, results = listOf(item), error = null) }
                }.onFailure { err ->
                    _discoverState.update {
                        it.copy(isLoading = false, results = emptyList(), error = err.message ?: "Search failed.")
                    }
                }
            }
            return
        }

        // Keyword search: fast background YouTube ytInitialData fetch, with automatic yt-dlp ytsearch fallback!
        discoverSearchJob = viewModelScope.launch {
            val lang = app.resources.configuration.locales[0]?.language ?: "vi"
            val directResult = YouTubeSearchClient.search(
                query = raw,
                filter = filter,
                languageTag = lang
            )
            val directItems = directResult.getOrNull().orEmpty()
            if (directItems.isNotEmpty()) {
                _discoverState.update {
                    it.copy(isLoading = false, results = directItems, error = null)
                }
                return@launch
            }

            // Fallback to bundled yt-dlp ytsearch if HTML fetch failed or returned empty (videos/all).
            if (filter != DiscoverSearchFilter.PLAYLISTS) {
                val procId = "discover_ytdlp_${UUID.randomUUID().toString().take(8)}"
                discoverSearchProcessId = procId
                val fallback = engine.searchYouTube(
                    query = raw,
                    limit = 15,
                    gentleRequests = settings.value.gentleRequests,
                    processId = procId
                )
                if (discoverSearchProcessId != procId) return@launch
                discoverSearchProcessId = null
                fallback.onSuccess { entries ->
                    val mapped = YouTubeSearchClient.fromYtDlpEntries(entries)
                    _discoverState.update {
                        it.copy(
                            isLoading = false,
                            results = mapped,
                            error = if (mapped.isEmpty()) "No matching results found." else null
                        )
                    }
                }.onFailure { err ->
                    _discoverState.update {
                        it.copy(
                            isLoading = false,
                            results = emptyList(),
                            error = directResult.exceptionOrNull()?.message ?: err.message ?: "Could not search YouTube."
                        )
                    }
                }
            } else {
                _discoverState.update {
                    it.copy(
                        isLoading = false,
                        results = emptyList(),
                        error = directResult.exceptionOrNull()?.message ?: "No playlists found."
                    )
                }
            }
        }
    }

    fun addDiscoverResult(item: DiscoverResultItem, autoStart: Boolean) {
        if (item.isPlaylist) {
            openPlaylistPicker(item.url)
            return
        }
        val single = YouTubeUrlParser.singleVideoUrl(item.url) ?: item.url
        val videoId = YouTubeUrlParser.extractVideoId(single) ?: item.id
        val cleanTitle = TextUtils.cleanSourceTitle(item.title)
        when (duplicateStatus(videoId)) {
            DuplicateStatus.IN_QUEUE -> {
                showMessage("Already in queue: \"$cleanTitle\"")
                return
            }
            DuplicateStatus.IN_LIBRARY -> {
                showMessage("Already downloaded: \"$cleanTitle\"")
                return
            }
            DuplicateStatus.NONE -> Unit
        }
        val job = DownloadJob(
            url = single,
            title = cleanTitle,
            sourceTitle = item.title,
            artist = item.artist,
            thumbnailUrl = item.thumbnailUrl,
            options = settings.value.toJobOptions()
        )
        queue.enqueueTracks(listOf(job), autoStart = autoStart)
        if (autoStart) {
            showMessage("Downloading \"${job.title}\"…")
        } else {
            showMessage("Added \"${job.title}\" to queue.")
        }
    }

    fun openDiscoverMetadataEditor(item: DiscoverResultItem) {
        val videoId = YouTubeUrlParser.extractVideoId(item.url) ?: item.id.takeIf { it.length == 11 }
        _metadataEditor.value = MetadataEditorState(
            jobId = null,
            sourceTitle = item.title,
            sourceThumbnailUrl = TextUtils.upgradeThumbnailUrl(item.thumbnailUrl, videoId),
            sourceVideoId = videoId,
            metadata = TrackMetadata(
                title = TextUtils.cleanSourceTitle(item.title),
                artist = TextUtils.cleanArtistName(item.artist)
                    .takeUnless { it.equals("Unknown artist", ignoreCase = true) }
                    .orEmpty()
            ),
            discoverItem = item
        )
    }

    fun openPlaylistPicker(url: String) {
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(url) }.getOrNull() ?: return
        cancelPlaylistInspection()
        val isMix = YouTubeUrlParser.isMixPlaylist(normalized)
        val singleVideo = YouTubeUrlParser.singleVideoUrl(normalized)
        _playlistPicker.value = PlaylistPickerState(
            sourceUrl = normalized,
            title = if (isMix) "YouTube Mix (up to 50 tracks)" else "Loading playlist tracks…",
            isMix = isMix,
            singleVideoUrl = singleVideo,
            tracks = emptyList(),
            isLoading = true
        )

        playlistInspectJob = viewModelScope.launch {
            val procId = "playlist_${UUID.randomUUID().toString().take(8)}"
            playlistProcessId = procId
            val result = engine.inspectSource(
                rawUrl = normalized,
                gentleRequests = settings.value.gentleRequests,
                processId = procId
            )
            if (playlistProcessId != procId) return@launch
            playlistProcessId = null

            result.onSuccess { info ->
                _playlistPicker.update { current ->
                    current?.copy(
                        title = info.title.ifBlank { if (isMix) "YouTube Mix" else "Playlist" },
                        tracks = info.entries,
                        isLoading = false,
                        error = if (info.entries.isEmpty()) "No selectable tracks were found in this playlist." else null
                    )
                }
            }.onFailure { err ->
                _playlistPicker.update { current ->
                    current?.copy(
                        isLoading = false,
                        error = err.message ?: "Failed to load playlist tracks."
                    )
                }
            }
        }
    }

    fun dismissPlaylistPicker() {
        cancelPlaylistInspection()
        _playlistPicker.value = null
    }

    private fun cancelPlaylistInspection() {
        playlistInspectJob?.cancel()
        playlistInspectJob = null
        playlistProcessId?.let { engine.cancelProcess(it) }
        playlistProcessId = null
    }

    fun confirmPlaylistTracks(selected: List<PlaylistTrackItem>, startImmediately: Boolean = false) {
        if (selected.isEmpty()) return
        val options = settings.value.toJobOptions()
        val seenIds = mutableSetOf<String>()
        var skippedDuplicates = 0
        val jobsToAdd = mutableListOf<DownloadJob>()
        for (item in selected) {
            if (!item.available || item.url.isBlank()) continue
            val vid = item.videoId.ifBlank { YouTubeUrlParser.extractVideoId(item.url).orEmpty() }
            if (vid.isNotBlank() && (!seenIds.add(vid) || duplicateStatus(vid) != DuplicateStatus.NONE)) {
                skippedDuplicates++
                continue
            }
            jobsToAdd.add(
                DownloadJob(
                    url = item.url,
                    title = TextUtils.cleanSourceTitle(item.title),
                    sourceTitle = item.title,
                    artist = item.artist,
                    thumbnailUrl = item.thumbnailUrl,
                    options = options
                )
            )
        }
        dismissPlaylistPicker()
        if (jobsToAdd.isNotEmpty()) {
            queue.enqueueTracks(jobsToAdd, autoStart = startImmediately)
            _homeState.value = HomeUiState()
            val dupNote = if (skippedDuplicates > 0) " ($skippedDuplicates duplicate skipped)" else ""
            showMessage("Added ${jobsToAdd.size} selected playlist track(s) to queue$dupNote.")
            if (startImmediately) {
                _selectedTab.value = AppTab.DOWNLOADS
            }
        } else if (skippedDuplicates > 0) {
            showMessage("All selected tracks are already downloaded or queued.")
        }
    }

    fun openComposerMetadataEditor() {
        val current = _homeState.value
        val preview = current.preview
        val sourceTitle = preview?.title ?: current.urlInput.trim()
        val videoId = preview?.singleVideoUrl?.let { YouTubeUrlParser.extractVideoId(it) }
            ?: YouTubeUrlParser.extractVideoId(current.urlInput)
        val initialMeta = if (current.pendingMetadata.isEmpty && preview != null) {
            TrackMetadata(
                title = TextUtils.cleanSourceTitle(preview.title),
                artist = preview.artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty(),
                album = preview.album,
                year = preview.releaseYear
            )
        } else {
            current.pendingMetadata
        }
        _metadataEditor.value = MetadataEditorState(
            jobId = null,
            sourceTitle = sourceTitle,
            sourceThumbnailUrl = preview?.thumbnailUrl.orEmpty(),
            sourceVideoId = videoId,
            metadata = initialMeta
        )
    }

    fun openJobMetadataEditor(job: DownloadJob) {
        val videoId = YouTubeUrlParser.extractVideoId(job.url)
        val initialMeta = if (job.metadata.isEmpty) {
            TrackMetadata(
                title = TextUtils.cleanSourceTitle(job.title),
                artist = job.artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty()
            )
        } else {
            job.metadata
        }
        _metadataEditor.value = MetadataEditorState(
            jobId = job.id,
            sourceTitle = job.sourceTitle,
            sourceThumbnailUrl = TextUtils.upgradeThumbnailUrl(job.thumbnailUrl, videoId),
            sourceVideoId = videoId,
            metadata = initialMeta
        )
    }

    suspend fun importCustomCover(uri: Uri): String? = withContext(Dispatchers.IO) {
        CoverArtHelper.importPickedCover(app, uri)?.absolutePath
    }

    suspend fun fetchHighResCover(sourceUrl: String, videoId: String?): String? = withContext(Dispatchers.IO) {
        val outFile = File(CoverArtHelper.coverCacheDir(app), "hd_${UUID.randomUUID().toString().take(12)}.jpg")
        CoverArtHelper.fetchHighResCoverToFile(sourceUrl, videoId, outFile)?.absolutePath
    }

    fun saveMetadataEditor(metadata: TrackMetadata) {
        val editor = _metadataEditor.value ?: return
        val cleaned = TextUtils.cleanMetadata(metadata)
        val discoverItem = editor.discoverItem
        when {
            discoverItem != null -> {
                val single = YouTubeUrlParser.singleVideoUrl(discoverItem.url) ?: discoverItem.url
                val videoId = YouTubeUrlParser.extractVideoId(single) ?: discoverItem.id
                val finalTitle = cleaned.title.ifBlank { TextUtils.cleanSourceTitle(discoverItem.title) }
                when (duplicateStatus(videoId)) {
                    DuplicateStatus.IN_QUEUE -> {
                        showMessage("Already in queue: \"$finalTitle\"")
                        _metadataEditor.value = null
                        return
                    }
                    DuplicateStatus.IN_LIBRARY -> {
                        showMessage("Already downloaded: \"$finalTitle\"")
                        _metadataEditor.value = null
                        return
                    }
                    DuplicateStatus.NONE -> Unit
                }
                val job = DownloadJob(
                    url = single,
                    title = finalTitle,
                    sourceTitle = discoverItem.title,
                    artist = cleaned.artist.ifBlank { discoverItem.artist },
                    thumbnailUrl = cleaned.customArtworkUri.ifBlank { discoverItem.thumbnailUrl },
                    options = settings.value.toJobOptions(),
                    metadata = cleaned
                )
                queue.enqueueTracks(listOf(job), autoStart = false)
                showMessage("Added \"${job.title}\" to queue.")
            }
            editor.jobId == null -> {
                _homeState.update { it.copy(pendingMetadata = cleaned) }
            }
            else -> {
                queue.updateJobMetadata(editor.jobId, cleaned)
            }
        }
        _metadataEditor.value = null
    }

    fun dismissMetadataEditor() {
        _metadataEditor.value = null
    }

    // Queue controls
    fun startQueue() {
        cancelPreviewInspection()
        queue.startQueue()
    }

    fun pauseQueue() {
        queue.pauseQueue()
        showMessage("Queue paused.")
    }

    fun stopQueue() {
        queue.stopAll()
    }

    fun removeJob(jobId: String) {
        queue.removeJob(jobId)
    }

    fun moveJobUp(jobId: String) {
        queue.moveJob(jobId, -1)
    }

    fun moveJobDown(jobId: String) {
        queue.moveJob(jobId, 1)
    }

    fun moveJobToTop(jobId: String) {
        queue.moveJobToTop(jobId)
    }

    fun clearWaitingJobs() {
        queue.clearWaitingJobs()
    }

    fun retryHistoryRecord(record: HistoryRecord) {
        val videoId = YouTubeUrlParser.extractVideoId(record.url)
        when (duplicateStatus(videoId, record.url)) {
            DuplicateStatus.IN_QUEUE -> {
                showMessage("Already in queue: \"${record.title}\"")
                return
            }
            DuplicateStatus.IN_LIBRARY -> {
                showMessage("Already downloaded: \"${record.title}\"")
                return
            }
            DuplicateStatus.NONE -> Unit
        }
        queue.retryFromHistory(record, autoStart = true)
        showMessage("Queued \"${record.title}\" again.")
    }

    fun retryAllFailed() {
        val downloadedIds = libraryTracks.value
            .mapNotNull { it.videoId.takeIf(String::isNotBlank) }
            .toSet()
        val count = queue.retryAllFailed(downloadedVideoIds = downloadedIds, autoStart = true)
        if (count > 0) {
            showMessage("Retrying $count failed track(s)…")
        } else {
            showMessage("No failed tracks to retry.")
        }
    }

    fun clearHistory() {
        queue.clearHistory()
    }

    val storageState = MutableStateFlow(com.example.mdownload.engine.StorageSnapshot())
    val settingsBusy = MutableStateFlow(false)
    val policyMessage = queue.policyMessage
    fun updatePreferences(transform: (AppSettings) -> AppSettings) = queue.updateSettings(transform)
    fun refreshStorage() { viewModelScope.launch(Dispatchers.IO) { storageState.value = queue.storage.snapshot() } }
    fun storageAction(action: String, id: String = "") {
        if (settingsBusy.value || queue.hasActiveTasks || isQueueRunning.value) { showMessage("Stop downloads before changing stored files."); return }
        settingsBusy.value = true
        queue.storageBusy = true
        viewModelScope.launch {
            try {
                val message = withContext(Dispatchers.IO) {
                    when(action) {
                        "retry" -> "Saved: " + queue.retryExport(id)
                        "discard" -> { queue.storage.discard(id); "Pending audio removed" }
                        "clear" -> { queue.storage.clearTemporary(); "Temporary files cleared" }
                        else -> error("Unknown storage action")
                    }
                }
                showMessage(message)
            } catch(e: Exception) { showMessage(e.message ?: "Storage operation failed") }
            finally { queue.storageBusy=false; settingsBusy.value=false; refreshStorage() }
        }
    }
    fun setLanguage(language: String) {
        queue.updateSettings { it.copy(language=language) }
        app.getSystemService(android.app.LocaleManager::class.java).applicationLocales =
            android.os.LocaleList.forLanguageTags(if(language == "system") "" else language)
    }
    fun resetPreferences() {
        val prevTreeUri = settings.value.storageTreeUri
        queue.updateSettings { AppSettings(lastUpdateCheck=it.lastUpdateCheck, lastUpdateResult=it.lastUpdateResult) }
        val resetSettings = settings.value
        gainCoordinator.updateConfig(ReplayGainConfig.fromAppSettings(resetSettings))
        sleepTimerController.updateFadeOutSeconds(resetSettings.sleepTimerDefaultFadeSec)
        if (prevTreeUri.isNotBlank()) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(
                    Uri.parse(prevTreeUri),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
            }
        }
        setLanguage("system")
        app.refreshLibrary()
    }

    fun selectStorageFolder(treeUri: Uri) {
        if (queue.hasActiveTasks || isQueueRunning.value || settingsBusy.value) {
            showMessage("Stop downloads before changing storage folder.")
            return
        }
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val persisted = runCatching {
            app.contentResolver.takePersistableUriPermission(treeUri, flags)
            true
        }.getOrDefault(false)
        if (!persisted || !exporter.hasValidTreePermission(treeUri.toString())) {
            showMessage("Selected folder does not grant read/write access.")
            return
        }
        val prevTreeUri = settings.value.storageTreeUri
        val newTreeUri = treeUri.toString()
        if (prevTreeUri.isNotBlank() && prevTreeUri != newTreeUri) {
            runCatching {
                app.contentResolver.releasePersistableUriPermission(Uri.parse(prevTreeUri), flags)
            }
        }
        val label = exporter.formatTreeLabel(treeUri)
        queue.updateSettings {
            it.copy(
                storageTreeUri = newTreeUri,
                storageFolderLabel = label
            )
        }
        syncStorageFolder(showFeedback = true)
    }

    fun resetStorageFolderToDefault() {
        if (queue.hasActiveTasks || isQueueRunning.value || settingsBusy.value) {
            showMessage("Stop downloads before changing storage folder.")
            return
        }
        val prevTreeUri = settings.value.storageTreeUri
        if (prevTreeUri.isBlank()) {
            syncStorageFolder(showFeedback = true)
            return
        }
        queue.updateSettings {
            it.copy(
                storageTreeUri = "",
                storageFolderLabel = ""
            )
        }
        runCatching {
            app.contentResolver.releasePersistableUriPermission(
                Uri.parse(prevTreeUri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        syncStorageFolder(showFeedback = true)
    }

    fun syncStorageFolder(showFeedback: Boolean = true) {
        val folderLabel = settings.value.effectiveStorageLabel
        app.refreshLibrary { count ->
            if (showFeedback) {
                showMessage("Synced $count track(s) from $folderLabel")
            }
        }
    }
    fun diagnosticsReport(): String = buildString {
        appendLine("MDownload 1.1 (2)")
        appendLine("Android ${android.os.Build.VERSION.RELEASE} / SDK ${android.os.Build.VERSION.SDK_INT}")
        appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        appendLine("Engine: ${engine.state.value}")
        appendLine("Network policy: Wi-Fi only=${settings.value.wifiOnly}, background=${settings.value.allowBackground}")
        appendLine("Logs may contain video titles and URLs.")
        logs.value.takeLast(100).forEach { appendLine(it) }
    }

    // Settings controls
    fun setAudioFormat(format: AudioFormat) {
        queue.updateSettings {
            val quality = if (format == AudioFormat.ORIGINAL) 128
            else if (it.audioQuality in format.bitrates) it.audioQuality
            else format.defaultBitrate
            it.copy(audioFormat = format, audioQuality = quality)
        }
    }

    fun setAudioQuality(quality: Int) {
        queue.updateSettings { it.copy(audioQuality = quality) }
    }

    fun setGentleRequests(enabled: Boolean) {
        queue.updateSettings { it.copy(gentleRequests = enabled) }
    }

    fun setParallelDownloads(count: Int) {
        val clamped = count.coerceIn(1, AppSettings.MAX_PARALLEL_DOWNLOADS)
        queue.updateSettings {
            it.copy(
                parallelDownloads = clamped,
                gentleRequests = if (clamped > 1) false else it.gentleRequests
            )
        }
    }

    fun setDownloadThreads(count: Int) {
        val clamped = count.coerceIn(1, AppSettings.MAX_DOWNLOAD_THREADS)
        queue.updateSettings {
            it.copy(
                downloadThreads = clamped,
                gentleRequests = if (clamped > 1) false else it.gentleRequests
            )
        }
    }

    fun setAsciiNames(enabled: Boolean) {
        queue.updateSettings { it.copy(asciiNames = enabled) }
    }

    fun setEmbedArtwork(enabled: Boolean) {
        queue.updateSettings { it.copy(embedArtwork = enabled) }
    }

    fun setSkipDuplicates(enabled: Boolean) {
        queue.updateSettings { it.copy(skipDuplicates = enabled) }
    }

    fun setInstantShareDownload(enabled: Boolean) {
        queue.updateSettings { it.copy(instantShareDownload = enabled) }
    }

    fun updateYtDlp(nightly: Boolean) {
        if (queue.hasActiveTasks || isQueueRunning.value || engine.state.value !is EngineState.Ready) return
        viewModelScope.launch {
            queue.updateSettings { it.copy(lastUpdateCheck=java.time.Instant.now().toString()) }
            val channel = if (nightly) YoutubeDL.UpdateChannel.NIGHTLY else YoutubeDL.UpdateChannel.STABLE
            val res = engine.updateEngine(channel) { line -> queue.appendLog(line) }
            val summary = res.getOrElse { "Update failed: ${it.message}" }
            queue.updateSettings { it.copy(lastUpdateResult=summary) }
            showMessage(summary)
        }
    }

    fun testRealDownload() {
        if (!queue.startConnectionCheck()) {
            showMessage("Stop active downloads before running a connection test.")
        } else {
            showMessage("Running real YouTube MP3 download test…")
            _selectedTab.value = AppTab.DOWNLOADS
        }
    }

    fun clearLogs() {
        queue.clearLogs()
    }

    // Library & Native Media3 Player controls
    fun refreshLibrary() {
        app.refreshLibrary()
    }

    fun onAudioPermissionChanged() {
        library.checkAudioPermission()
        app.refreshLibrary()
    }

    fun setScanAllDeviceMusic(enabled: Boolean) {
        queue.updateSettings { it.copy(scanAllDeviceMusic = enabled) }
        app.refreshLibrary()
    }

    fun setRestorePlaybackOnLaunch(enabled: Boolean) {
        queue.updateSettings { it.copy(restorePlaybackOnLaunch = enabled) }
    }

    fun setDefaultPlaybackSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        queue.updateSettings { it.copy(defaultPlaybackSpeed = clamped) }
        player.setPlaybackSpeed(clamped)
    }

    fun setAutoFetchLyricsAfterDownload(enabled: Boolean) {
        queue.updateSettings { it.copy(autoFetchLyricsAfterDownload = enabled) }
    }

    fun setEmbedLyricsAfterDownload(enabled: Boolean) {
        queue.updateSettings { it.copy(embedLyricsAfterDownload = enabled) }
    }

    // Lyrics controls
    fun searchOnlineLyrics(customTitle: String? = null, customArtist: String? = null) {
        lyricsRepo.searchOnlineForActiveTrack(customTitle, customArtist)
    }

    fun selectLyricsCandidate(candidate: LyricsCandidate) {
        lyricsRepo.selectCandidate(candidate)
    }

    fun saveManualLyrics(rawText: String) {
        if (!lyricsRepo.saveManualLyrics(rawText)) {
            showMessage("Could not save lyrics.")
        }
    }

    fun importLyricsFromUri(uri: Uri) {
        lyricsRepo.importLyricsFromUri(uri)
    }

    fun exportActiveLyricsToUri(uri: Uri) {
        lyricsRepo.exportActiveLyricsToUri(uri)
    }

    fun embedActiveLyricsIntoAudioFile() {
        lyricsRepo.embedActiveLyricsIntoAudioFile(
            onRequiresWritePermission = { uris ->
                pendingLyricsEmbed = true
                _pendingWritePermissionUris.value = uris
            }
        )
    }

    fun adjustLyricsOffset(deltaMs: Long) {
        lyricsRepo.adjustOffset(deltaMs)
    }

    fun resetLyricsOffset() {
        lyricsRepo.resetOffset()
    }

    fun clearActiveLyrics() {
        lyricsRepo.clearLyricsForActiveTrack()
    }

    fun dismissLyricsNotice() {
        lyricsRepo.clearNotices()
    }

    fun clearArtworkCache() {
        library.clearArtworkCache()
        showMessage("Artwork cache cleared.")
    }

    fun openNowPlaying() {
        if (playbackState.value.currentTrack != null) {
            _isNowPlayingOpen.value = true
        }
    }

    fun closeNowPlaying() {
        _isNowPlayingOpen.value = false
    }

    fun playTrackInQueue(track: LibraryTrack, contextQueue: List<LibraryTrack>) {
        player.playTrack(
            track = track,
            contextQueue = contextQueue,
            defaultSpeed = settings.value.defaultPlaybackSpeed
        )
    }

    fun playQueue(tracks: List<LibraryTrack>, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        val startIndex = if (shuffle && tracks.size > 1) {
            kotlin.random.Random.nextInt(tracks.size)
        } else 0
        player.playQueue(
            tracks = tracks,
            startIndex = startIndex,
            playWhenReady = true,
            shuffle = shuffle,
            playbackSpeed = settings.value.defaultPlaybackSpeed
        )
    }

    fun playNextInQueue(track: LibraryTrack) {
        player.playNext(track)
        showMessage("Will play \"${track.title}\" next.")
    }

    fun addTrackToPlaybackQueue(track: LibraryTrack) {
        player.addToQueue(track)
        showMessage("Added \"${track.title}\" to playing queue.")
    }

    private fun resolveTrackForHistory(record: HistoryRecord): LibraryTrack? {
        if (record.savedUri.isBlank()) return null
        return libraryTracks.value.firstOrNull { it.uri == record.savedUri }
            ?: LibraryTrack(
                id = record.id.hashCode().toLong(),
                uri = record.savedUri,
                displayName = record.savedDisplayName.ifBlank { record.title },
                title = record.title,
                artist = record.artist.ifBlank { "Unknown artist" },
                album = record.metadata.album,
                durationMs = 0L,
                sizeBytes = 0L,
                dateAddedSec = System.currentTimeMillis() / 1000L,
                mimeType = "audio/mpeg",
                isDownloadedByApp = true
            )
    }

    fun playUriFromHistory(record: HistoryRecord) {
        val track = resolveTrackForHistory(record) ?: return
        player.playTrack(
            track = track,
            contextQueue = listOf(track),
            defaultSpeed = settings.value.defaultPlaybackSpeed
        )
        _isNowPlayingOpen.value = true
    }

    fun addHistoryToPlaybackQueue(record: HistoryRecord) {
        val track = resolveTrackForHistory(record) ?: return
        player.addToQueue(track)
        showMessage("Added \"${track.title}\" to playing queue.")
    }

    fun togglePlayPause() = player.togglePlayPause()

    fun skipToNext() = player.skipToNext()

    fun skipToPrevious() = player.skipToPrevious()

    fun seekTo(ms: Long) = player.seekTo(ms)

    fun toggleShuffle() = player.toggleShuffle()

    fun cycleRepeatMode() = player.cycleRepeatMode()

    fun setPlaybackSpeed(speed: Float) = player.setPlaybackSpeed(speed)

    fun skipToQueueItem(index: Int) = player.skipToQueueItem(index)

    fun selectQueueItemPreservingPlayState(index: Int) = player.selectQueueItemPreservingPlayState(index)

    fun movePlaybackQueueItem(from: Int, to: Int) = player.moveQueueItem(from, to)

    fun removePlaybackQueueItem(index: Int) = player.removeFromQueue(index)

    fun clearPlaybackQueue() {
        player.clearQueue()
        _isNowPlayingOpen.value = false
    }

    fun dismissPlaybackError() = player.clearError()

    fun isTrackFavorite(trackUri: String): Boolean =
        userPlaylists.value.any {
            it.name.equals("Favorites", ignoreCase = true) && trackUri in it.trackUris
        }

    fun toggleFavoriteTrack(track: LibraryTrack) {
        val existing = userPlaylists.value.firstOrNull {
            it.name.equals("Favorites", ignoreCase = true)
        }
        if (existing == null) {
            library.createPlaylist("Favorites", listOf(track.uri))
            showMessage("Added \"${track.title}\" to Favorites.")
        } else if (track.uri in existing.trackUris) {
            library.removeTrackFromPlaylist(existing.id, track.uri)
            showMessage("Removed \"${track.title}\" from Favorites.")
        } else {
            library.addTracksToPlaylist(existing.id, listOf(track.uri))
            showMessage("Added \"${track.title}\" to Favorites.")
        }
    }

    // User playlist controls
    fun openAddTrackToPlaylist(track: LibraryTrack) {
        _playlistPickerTrack.value = track
    }

    fun dismissAddTrackToPlaylist() {
        _playlistPickerTrack.value = null
    }

    fun createUserPlaylist(name: String, initialTrack: LibraryTrack? = null) {
        val created = library.createPlaylist(
            name = name,
            initialTrackUris = initialTrack?.let { listOf(it.uri) } ?: emptyList()
        )
        if (created != null) {
            showMessage("Created playlist \"${created.name}\".")
        }
        if (initialTrack != null) {
            _playlistPickerTrack.value = null
        }
    }

    fun addTrackToUserPlaylist(playlistId: String, track: LibraryTrack) {
        val added = library.addTracksToPlaylist(playlistId, listOf(track.uri))
        _playlistPickerTrack.value = null
        if (added > 0) {
            showMessage("Added \"${track.title}\" to playlist.")
        } else {
            showMessage("Already in this playlist.")
        }
    }

    fun renameUserPlaylist(playlistId: String, newName: String) {
        if (library.renamePlaylist(playlistId, newName)) {
            showMessage("Playlist renamed.")
        }
    }

    fun deleteUserPlaylist(playlistId: String) {
        library.deletePlaylist(playlistId)
        showMessage("Playlist deleted.")
    }

    fun removeTrackFromUserPlaylist(playlistId: String, trackUri: String) {
        library.removeTrackFromPlaylist(playlistId, trackUri)
    }

    fun deleteLibraryTrack(track: LibraryTrack) {
        viewModelScope.launch {
            player.removeTrackIfPresent(track.uri)
            val deleted = library.deleteTrack(track)
            if (deleted) {
                audioAnalysisRepo.invalidate(track.uri)
                listeningHistoryRepo.removeTrackStats(track.stableId, track.uri)
                showMessage("Deleted \"${track.title}\".")
            } else {
                showMessage("Could not delete \"${track.title}\".")
            }
        }
    }

    // Artist management controls
    suspend fun importArtistCover(uri: Uri): String? = withContext(Dispatchers.IO) {
        CoverArtHelper.importArtistCover(app, uri)?.absolutePath
    }

    fun createArtist(name: String, coverPath: String = "", initialTrackUris: List<String> = emptyList()) {
        val created = library.createArtist(
            name = name,
            coverPath = coverPath,
            initialTrackUris = initialTrackUris
        )
        if (created != null) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Saved artist \"${created.name}\".")
        }
    }

    fun editArtist(oldName: String, newName: String, coverPath: String? = null, clearCover: Boolean = false) {
        if (library.editArtist(oldName = oldName, newName = newName, coverPath = coverPath, clearCover = clearCover)) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Updated artist \"${newName.trim()}\".")
        }
    }

    fun deleteArtist(artistName: String) {
        if (library.deleteArtist(artistName)) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Removed artist \"$artistName\".")
        }
    }

    fun setArtistTracks(artistName: String, selectedTrackUris: Set<String>) {
        val (added, removed) = library.setArtistTracks(artistName, selectedTrackUris)
        if (added > 0 || removed > 0) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Updated songs for \"$artistName\".")
        }
    }

    fun setTrackArtist(track: LibraryTrack, targetArtistName: String) {
        if (library.setTrackArtist(track.uri, targetArtistName)) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Assigned \"${track.title}\" to \"${targetArtistName.trim()}\".")
        }
    }

    fun createArtistAndAssignTrack(track: LibraryTrack, artistName: String) {
        val created = library.createArtist(name = artistName, initialTrackUris = listOf(track.uri))
        if (created != null) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Assigned \"${track.title}\" to \"${created.name}\".")
        }
    }

    fun resetTrackArtist(track: LibraryTrack) {
        if (library.resetTrackArtist(track.uri)) {
            player.refreshTrackMetadataFromLibrary()
            showMessage("Restored original artist for \"${track.title}\".")
        }
    }

    fun originalArtistForTrack(trackUri: String): String? =
        library.originalArtistForTrack(trackUri)

    suspend fun loadTrackArtwork(uri: String, albumId: Long = 0L, highRes: Boolean = false): Bitmap? =
        library.loadArtworkByUri(uri = uri, albumId = albumId, highRes = highRes)

    // -------------------------------------------------------------------------
    // Feature 1: ReplayGain / Volume Normalization settings
    // -------------------------------------------------------------------------

    private fun updateReplayGainSettings(transform: (AppSettings) -> AppSettings) {
        queue.updateSettings(transform)
        gainCoordinator.updateConfig(ReplayGainConfig.fromAppSettings(settings.value))
    }

    fun setReplayGainMode(mode: ReplayGainMode) {
        updateReplayGainSettings { it.copy(replayGainMode = mode.id) }
    }

    fun setReplayGainPreampDb(db: Float) {
        val clamped = db.coerceIn(-12f, 12f)
        updateReplayGainSettings { it.copy(replayGainPreampDb = clamped) }
    }

    fun setReplayGainMissingBehavior(behavior: MissingReplayGainBehavior) {
        updateReplayGainSettings { it.copy(replayGainMissingBehavior = behavior.id) }
    }

    fun setReplayGainFallbackPreampDb(db: Float) {
        val clamped = db.coerceIn(-12f, 6f)
        updateReplayGainSettings { it.copy(replayGainFallbackPreampDb = clamped) }
    }

    fun setReplayGainPreventClipping(enabled: Boolean) {
        updateReplayGainSettings { it.copy(replayGainPreventClipping = enabled) }
    }

    // -------------------------------------------------------------------------
    // Feature 2: Audio Quality Inspector & Optional Loudness Scan
    // -------------------------------------------------------------------------

    private fun buildPlaybackOutputSnapshot(track: LibraryTrack): PlaybackOutputSnapshot? {
        val pb = playbackState.value
        val isActiveTrack = pb.currentTrack?.uri == track.uri
        if (!isActiveTrack) return null
        val (deviceName, deviceType) = AudioInspectorEngine.queryActiveAudioOutputDevice(app)
        val gain = gainCoordinator.state.value
        val out = pb.audioOutputInfo
        return PlaybackOutputSnapshot(
            outputDeviceName = deviceName,
            outputDeviceType = deviceType,
            reportedCodec = out.codecLabel.takeIf { it.isNotBlank() },
            reportedSampleRateHz = out.sampleRateHz.takeIf { it > 0 },
            reportedChannelCount = out.channelCount.takeIf { it > 0 },
            reportedBitrateKbps = out.bitrateKbps.takeIf { it > 0 },
            audioOffloadStatus = "Standard PCM AudioTrack (software ReplayGain & fade active)",
            gainApplicationPath = gain.applicationPath,
            activeReplayGainDb = gain.replayGainResult.appliedGainDb,
            activeSleepFadeFactor = gain.sleepFadeFactor,
            effectiveLinearGain = gain.effectiveLinearGain,
            replayGainReason = gain.replayGainResult.reason
        )
    }

    fun openAudioInspector(track: LibraryTrack) {
        inspectorJob?.cancel()
        loudnessScanJob?.cancel()
        val cached = audioAnalysisRepo.getCachedInfo(track)
        val canScan = audioAnalysisRepo.isLoudnessScanAvailable()
        _audioInspectorState.value = AudioInspectorUiState(
            track = track,
            info = cached,
            outputSnapshot = buildPlaybackOutputSnapshot(track),
            isLoading = cached == null,
            canScanLoudness = canScan
        )
        runInspectorLoad(track, forceRefresh = false)
    }

    fun refreshAudioInspector() {
        val track = _audioInspectorState.value.track ?: return
        runInspectorLoad(track, forceRefresh = true)
    }

    private fun runInspectorLoad(track: LibraryTrack, forceRefresh: Boolean) {
        inspectorJob?.cancel()
        inspectorJob = viewModelScope.launch {
            _audioInspectorState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    outputSnapshot = buildPlaybackOutputSnapshot(track)
                )
            }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val (hasLyrics, lyricsSource) = lyricsRepo.inspectLyricsAvailability(
                        track = track,
                        customTreeUri = settings.value.storageTreeUri
                    )
                    audioAnalysisRepo.inspectTrack(
                        track = track,
                        hasLyrics = hasLyrics,
                        lyricsSourceLabel = lyricsSource,
                        forceRefresh = forceRefresh
                    )
                }
            }
            if (_audioInspectorState.value.track?.uri != track.uri) return@launch
            result.onSuccess { info ->
                _audioInspectorState.update {
                    it.copy(
                        info = info,
                        outputSnapshot = buildPlaybackOutputSnapshot(track),
                        isLoading = false,
                        error = null
                    )
                }
            }.onFailure { err ->
                _audioInspectorState.update {
                    it.copy(
                        isLoading = false,
                        error = err.message ?: "Could not inspect audio file."
                    )
                }
            }
        }
    }

    fun scanActiveInspectorLoudness() {
        val track = _audioInspectorState.value.track ?: return
        if (_audioInspectorState.value.isScanningLoudness) return
        loudnessScanJob?.cancel()
        loudnessScanJob = viewModelScope.launch {
            _audioInspectorState.update { it.copy(isScanningLoudness = true, error = null) }
            val scanned = withContext(Dispatchers.IO) {
                runCatching { audioAnalysisRepo.scanLoudnessForTrack(track) }.getOrNull()
            }
            if (_audioInspectorState.value.track?.uri != track.uri) return@launch
            if (scanned != null) {
                val updatedInfo = audioAnalysisRepo.getCachedInfo(track)
                    ?: _audioInspectorState.value.info?.copy(replayGain = scanned)
                if (playbackState.value.currentTrack?.uri == track.uri) {
                    gainCoordinator.updateTrackReplayGain(track.uri, scanned, snapOnTrackSwitch = false)
                }
                _audioInspectorState.update {
                    it.copy(
                        info = updatedInfo,
                        outputSnapshot = buildPlaybackOutputSnapshot(track),
                        isScanningLoudness = false
                    )
                }
                showMessage("Loudness scan complete.")
            } else {
                _audioInspectorState.update {
                    it.copy(
                        isScanningLoudness = false,
                        error = "Loudness scan did not return valid measurements."
                    )
                }
            }
        }
    }

    fun cancelInspectorLoudnessScan() {
        loudnessScanJob?.cancel()
        loudnessScanJob = null
        _audioInspectorState.update { it.copy(isScanningLoudness = false) }
    }

    fun dismissAudioInspector() {
        inspectorJob?.cancel()
        loudnessScanJob?.cancel()
        _audioInspectorState.value = AudioInspectorUiState()
    }

    // -------------------------------------------------------------------------
    // Feature 5: Sleep Timer controls
    // -------------------------------------------------------------------------

    fun openSleepTimer() {
        _isSleepTimerOpen.value = true
    }

    fun dismissSleepTimer() {
        _isSleepTimerOpen.value = false
    }

    fun startSleepTimerDuration(minutes: Int, fadeSeconds: Int) {
        queue.updateSettings { it.copy(sleepTimerDefaultFadeSec = fadeSeconds.coerceIn(0, 30)) }
        sleepTimerController.startDuration(minutes, fadeSeconds)
        _isSleepTimerOpen.value = false
        showMessage("Sleep timer set for $minutes min.")
    }

    fun startSleepTimerEndOfTrack(fadeSeconds: Int) {
        queue.updateSettings { it.copy(sleepTimerDefaultFadeSec = fadeSeconds.coerceIn(0, 30)) }
        sleepTimerController.startEndOfTrack(fadeSeconds)
        _isSleepTimerOpen.value = false
        showMessage("Sleep timer set for end of current song.")
    }

    fun startSleepTimerEndOfQueue(fadeSeconds: Int) {
        queue.updateSettings { it.copy(sleepTimerDefaultFadeSec = fadeSeconds.coerceIn(0, 30)) }
        sleepTimerController.startEndOfQueue(fadeSeconds)
        _isSleepTimerOpen.value = false
        showMessage("Sleep timer set for end of queue.")
    }

    fun updateSleepTimerFade(fadeSeconds: Int) {
        val clamped = fadeSeconds.coerceIn(0, 30)
        queue.updateSettings { it.copy(sleepTimerDefaultFadeSec = clamped) }
        sleepTimerController.updateFadeOutSeconds(clamped)
    }

    fun extendSleepTimer(additionalMinutes: Int) {
        sleepTimerController.extendMinutes(additionalMinutes)
        showMessage("Extended sleep timer by +$additionalMinutes min.")
    }

    fun cancelSleepTimer() {
        sleepTimerController.cancel()
        showMessage("Sleep timer cancelled.")
    }

    // -------------------------------------------------------------------------
    // Feature 3: Metadata Editor (Single & Batch + MediaStore Write Consent)
    // -------------------------------------------------------------------------

    fun openTrackMetadataEditor(track: LibraryTrack) {
        pendingSingleSave = null
        _trackMetadataEditorState.value = TrackMetadataEditorUiState(
            track = track,
            isLoading = true
        )
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { metadataRepo.loadEditableMetadata(track) }
            }
            if (_trackMetadataEditorState.value?.track?.uri != track.uri) return@launch
            loaded.onSuccess { (editable, snapshot) ->
                _trackMetadataEditorState.value = TrackMetadataEditorUiState(
                    track = track,
                    original = editable,
                    snapshot = snapshot,
                    isLoading = false
                )
            }.onFailure { err ->
                _trackMetadataEditorState.value = TrackMetadataEditorUiState(
                    track = track,
                    isLoading = false,
                    error = err.message ?: "Could not load track metadata."
                )
            }
        }
    }

    suspend fun prepareTrackEditorArtwork(uri: Uri, cropSquare: Boolean): Pair<ByteArray, String>? =
        withContext(Dispatchers.IO) {
            metadataRepo.prepareArtwork(uri, cropSquare)
        }

    fun saveTrackMetadata(draft: EditableTrackMetadata) {
        val current = _trackMetadataEditorState.value ?: return
        executeSingleMetadataSave(
            track = current.track,
            draft = draft,
            snapshot = current.snapshot
        )
    }

    private fun executeSingleMetadataSave(
        track: LibraryTrack,
        draft: EditableTrackMetadata,
        snapshot: com.example.mdownload.core.metadata.FileSnapshot?
    ) {
        _trackMetadataEditorState.update { it?.copy(isSaving = true, error = null) }
        viewModelScope.launch {
            val isDownloading = isQueueRunning.value || queue.hasActiveTasks
            val result = withContext(Dispatchers.IO) {
                metadataRepo.saveSingleMetadata(
                    track = track,
                    draft = draft,
                    expectedSnapshot = snapshot,
                    isDownloadInProgress = isDownloading
                )
            }
            when (result) {
                is MetadataWriteResult.Success -> {
                    pendingSingleSave = null
                    postCommitMetadataRefresh(
                        track = track,
                        title = draft.title.trim().ifBlank { track.title },
                        artist = draft.artist.trim().ifBlank { "Unknown artist" },
                        album = draft.album.trim(),
                        albumArtist = draft.albumArtist.trim(),
                        genre = draft.genre.trim(),
                        year = draft.year.trim().take(4).toIntOrNull() ?: 0,
                        trackNumber = draft.trackNumber.trim().toIntOrNull() ?: 0,
                        artworkChanged = draft.artworkChanged || draft.artworkRemoved
                    )
                    _trackMetadataEditorState.value = null
                    showMessage("Metadata saved for \"${draft.title.trim().ifBlank { track.title }}\".")
                }

                is MetadataWriteResult.RequiresWritePermission -> {
                    pendingSingleSave = track to draft
                    _trackMetadataEditorState.update { it?.copy(isSaving = false, error = null) }
                    _pendingWritePermissionUris.value = result.uris
                }

                is MetadataWriteResult.Conflict -> {
                    _trackMetadataEditorState.update {
                        it?.copy(isSaving = false, error = result.reason)
                    }
                }

                is MetadataWriteResult.Unsupported -> {
                    _trackMetadataEditorState.update {
                        it?.copy(isSaving = false, error = result.reason)
                    }
                }

                is MetadataWriteResult.Failed -> {
                    _trackMetadataEditorState.update {
                        it?.copy(isSaving = false, error = result.reason)
                    }
                }
            }
        }
    }

    fun dismissTrackMetadataEditor() {
        pendingSingleSave = null
        _trackMetadataEditorState.value = null
    }

    fun openBatchMetadataEditor(tracks: List<LibraryTrack>) {
        if (tracks.isEmpty()) return
        pendingBatchSave = null
        batchMetadataJob?.cancel()
        _batchMetadataEditorState.value = BatchMetadataEditorUiState(tracks = tracks)
    }

    fun applyBatchMetadata(patch: BatchMetadataPatch) {
        val current = _batchMetadataEditorState.value ?: return
        executeBatchMetadataSave(current.tracks, patch, skipPermissionPrecheck = false)
    }

    private fun executeBatchMetadataSave(
        tracks: List<LibraryTrack>,
        patch: BatchMetadataPatch,
        skipPermissionPrecheck: Boolean
    ) {
        batchMetadataJob?.cancel()
        batchMetadataJob = viewModelScope.launch {
            _batchMetadataEditorState.update {
                it?.copy(isSaving = true, completedCount = 0, totalCount = tracks.size, error = null, result = null)
            }
            if (!skipPermissionPrecheck) {
                val needingConsent = withContext(Dispatchers.IO) {
                    metadataRepo.findUrisRequiringWritePermission(tracks)
                }
                if (needingConsent.isNotEmpty()) {
                    pendingBatchSave = tracks to patch
                    _batchMetadataEditorState.update { it?.copy(isSaving = false) }
                    _pendingWritePermissionUris.value = needingConsent
                    return@launch
                }
            }

            val isDownloading = isQueueRunning.value || queue.hasActiveTasks
            val committedInBatch = java.util.Collections.synchronizedList(
                mutableListOf<Pair<LibraryTrack, EditableTrackMetadata>>()
            )
            var finalResult: com.example.mdownload.core.metadata.BatchMetadataResult? = null
            try {
                finalResult = withContext(Dispatchers.IO) {
                    metadataRepo.saveBatchMetadata(
                        tracks = tracks,
                        patch = patch,
                        isDownloadInProgress = isDownloading,
                        onTrackCommitted = { committedTrack, appliedDraft ->
                            committedInBatch.add(committedTrack to appliedDraft)
                        }
                    ) { completed, total, currentTitle ->
                        _batchMetadataEditorState.update {
                            it?.copy(
                                completedCount = completed,
                                totalCount = total,
                                currentTrackTitle = currentTitle
                            )
                        }
                    }
                }
            } finally {
                // Always synchronize every already-committed track (even if batch was cancelled mid-run!)
                val snapshotCommitted = synchronized(committedInBatch) { committedInBatch.toList() }
                if (snapshotCommitted.isNotEmpty()) {
                    withContext(kotlinx.coroutines.NonCancellable) {
                        snapshotCommitted.forEach { (track, appliedDraft) ->
                            postCommitMetadataRefresh(
                                track = track,
                                title = appliedDraft.title.trim().ifBlank { track.title },
                                artist = appliedDraft.artist.trim().ifBlank { "Unknown artist" },
                                album = appliedDraft.album.trim(),
                                albumArtist = appliedDraft.albumArtist.trim(),
                                genre = appliedDraft.genre.trim(),
                                year = appliedDraft.year.trim().take(4).toIntOrNull() ?: track.year,
                                trackNumber = appliedDraft.trackNumber.trim().toIntOrNull() ?: track.trackNumber,
                                artworkChanged = patch.replaceArtwork || patch.removeArtwork
                            )
                        }
                    }
                }
            }
            pendingBatchSave = null
            val res = finalResult
            if (res != null) {
                val succeededCount = res.succeededCount
                _batchMetadataEditorState.update {
                    it?.copy(
                        isSaving = false,
                        result = res,
                        completedCount = res.trackResults.size
                    )
                }
                if (res.cancelled) {
                    showMessage("Batch edit stopped after updating $succeededCount of ${tracks.size} track(s).")
                } else if (succeededCount > 0) {
                    showMessage("Updated metadata for $succeededCount track(s).")
                }
            }
        }
    }

    fun cancelBatchMetadata() {
        batchMetadataJob?.cancel()
        batchMetadataJob = null
        pendingBatchSave = null
        _batchMetadataEditorState.update { it?.copy(isSaving = false) }
    }

    fun dismissBatchMetadataEditor() {
        batchMetadataJob?.cancel()
        batchMetadataJob = null
        pendingBatchSave = null
        _batchMetadataEditorState.value = null
    }

    fun onWritePermissionResult(granted: Boolean) {
        _pendingWritePermissionUris.value = null
        if (!granted) {
            pendingSingleSave = null
            pendingBatchSave = null
            pendingLyricsEmbed = false
            _trackMetadataEditorState.update {
                it?.copy(isSaving = false, error = "Write permission was denied by the system.")
            }
            _batchMetadataEditorState.update {
                it?.copy(isSaving = false, error = "Write permission was denied by the system.")
            }
            showMessage("Write permission denied — no files were modified.")
            return
        }
        val single = pendingSingleSave
        if (single != null) {
            pendingSingleSave = null
            val snapshot = _trackMetadataEditorState.value?.snapshot
            executeSingleMetadataSave(
                track = single.first,
                draft = single.second,
                snapshot = snapshot
            )
            return
        }
        val batch = pendingBatchSave
        if (batch != null) {
            pendingBatchSave = null
            executeBatchMetadataSave(
                tracks = batch.first,
                patch = batch.second,
                skipPermissionPrecheck = true
            )
            return
        }
        if (pendingLyricsEmbed) {
            pendingLyricsEmbed = false
            lyricsRepo.embedActiveLyricsIntoAudioFile()
        }
    }

    private fun postCommitMetadataRefresh(
        track: LibraryTrack,
        title: String,
        artist: String,
        album: String,
        albumArtist: String,
        genre: String,
        year: Int,
        trackNumber: Int,
        artworkChanged: Boolean
    ) {
        val identityChanged = !title.equals(track.title, ignoreCase = false) ||
            !artist.equals(track.artist, ignoreCase = false) ||
            !album.equals(track.album, ignoreCase = false)

        audioAnalysisRepo.invalidate(track.uri)
        library.applyEditedMetadata(
            trackUri = track.uri,
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            artworkModified = artworkChanged
        )
        val updatedTrack = libraryTracks.value.firstOrNull { it.uri == track.uri } ?: track.copy(
            title = title,
            artist = artist,
            album = album,
            albumArtist = albumArtist,
            genre = genre,
            year = year,
            trackNumber = trackNumber,
            dateModifiedSec = System.currentTimeMillis() / 1000L
        )
        lyricsRepo.onTrackMetadataEdited(
            track = updatedTrack,
            identityChanged = identityChanged,
            customTreeUri = settings.value.storageTreeUri
        )
        player.refreshTrackMetadataFromLibrary(
            editedTrackUri = track.uri,
            artworkModified = artworkChanged
        )

        if (playbackState.value.currentTrack?.uri == track.uri) {
            viewModelScope.launch(Dispatchers.IO) {
                val rg = audioAnalysisRepo.resolveReplayGain(updatedTrack)
                gainCoordinator.updateTrackReplayGain(updatedTrack.uri, rg, snapOnTrackSwitch = false)
            }
        }
        if (_audioInspectorState.value.track?.uri == track.uri) {
            runInspectorLoad(updatedTrack, forceRefresh = true)
        }
    }

    // -------------------------------------------------------------------------
    // Feature 4: Smart Playlists CRUD & Evaluation
    // -------------------------------------------------------------------------

    fun currentSmartPlaylistEvalContext(): SmartPlaylistEvalContext {
        val favUris = userPlaylists.value
            .firstOrNull { it.name.equals("Favorites", ignoreCase = true) }
            ?.trackUris
            ?.toSet()
            ?: emptySet()
        return SmartPlaylistEvalContext(
            statsByTrackId = listeningStats.value,
            favoriteUris = favUris,
            analysisByUri = audioAnalysisRepo.getCachedSnapshotByUri()
        )
    }

    fun evaluateSmartPlaylist(definition: SmartPlaylistDefinition): List<LibraryTrack> =
        SmartPlaylistEvaluator.evaluate(
            definition = definition,
            tracks = libraryTracks.value,
            ctx = currentSmartPlaylistEvalContext()
        )

    fun saveCustomSmartPlaylist(definition: SmartPlaylistDefinition) {
        val saved = smartPlaylistRepo.createOrUpdateCustom(definition)
        if (saved != null) {
            showMessage("Saved smart playlist \"${saved.name}\".")
        }
    }

    fun renameCustomSmartPlaylist(id: String, newName: String) {
        if (smartPlaylistRepo.renameCustom(id, newName)) {
            showMessage("Smart playlist renamed.")
        }
    }

    fun deleteCustomSmartPlaylist(id: String) {
        if (smartPlaylistRepo.deleteCustom(id)) {
            showMessage("Smart playlist deleted.")
        }
    }
}

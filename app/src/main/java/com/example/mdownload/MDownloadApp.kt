package com.example.mdownload

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.SettingsStore
import com.example.mdownload.core.audio.AudioAnalysisRepository
import com.example.mdownload.core.audio.ReplayGainConfig
import com.example.mdownload.core.metadata.MetadataRepository
import com.example.mdownload.engine.DownloadQueueManager
import com.example.mdownload.engine.MediaStoreExporter
import com.example.mdownload.engine.YtDlpEngine
import com.example.mdownload.player.library.ArtistCatalogState
import com.example.mdownload.player.library.ArtistStore
import com.example.mdownload.player.library.ArtworkCache
import com.example.mdownload.player.library.ListeningHistoryRepository
import com.example.mdownload.player.library.MusicLibraryRepository
import com.example.mdownload.player.library.PlaylistStore
import com.example.mdownload.player.library.SmartPlaylistRepository
import com.example.mdownload.player.library.UserPlaylist
import com.example.mdownload.player.lyrics.LyricsRepository
import com.example.mdownload.player.playback.PlaybackController
import com.example.mdownload.player.playback.PlaybackGainCoordinator
import com.example.mdownload.player.playback.PlaybackStateStore
import com.example.mdownload.player.playback.SleepTimerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MDownloadApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var settingsStore: SettingsStore
        private set
    lateinit var engine: YtDlpEngine
        private set
    lateinit var exporter: MediaStoreExporter
        private set
    lateinit var queueManager: DownloadQueueManager
        private set

    // Native offline-first music player & library stack (independent from Python/yt-dlp)
    lateinit var artworkCache: ArtworkCache
        private set
    lateinit var playlistStore: PlaylistStore
        private set
    lateinit var artistStore: ArtistStore
        private set
    lateinit var libraryRepository: MusicLibraryRepository
        private set
    lateinit var playbackStateStore: PlaybackStateStore
        private set
    lateinit var gainCoordinator: PlaybackGainCoordinator
        private set
    lateinit var sleepTimerController: SleepTimerController
        private set
    lateinit var audioAnalysisRepository: AudioAnalysisRepository
        private set
    lateinit var metadataRepository: MetadataRepository
        private set
    lateinit var listeningHistoryRepository: ListeningHistoryRepository
        private set
    lateinit var smartPlaylistRepository: SmartPlaylistRepository
        private set
    lateinit var playbackController: PlaybackController
        private set
    lateinit var lyricsRepository: LyricsRepository
        private set

    val libraryTracks: StateFlow<List<LibraryTrack>>
        get() = libraryRepository.tracks

    val isLibrarySyncing: StateFlow<Boolean>
        get() = libraryRepository.isSyncing

    val hasAudioPermission: StateFlow<Boolean>
        get() = libraryRepository.hasAudioPermission

    val userPlaylists: StateFlow<List<UserPlaylist>>
        get() = libraryRepository.playlists

    val artistCatalog: StateFlow<ArtistCatalogState>
        get() = libraryRepository.artistCatalog

    override fun onCreate() {
        super.onCreate()
        installDebugStrictModeIfEnabled()

        // All store & repository constructors are non-blocking: zero Main-Thread disk reads/writes.
        settingsStore = SettingsStore(this)
        engine = YtDlpEngine(this)
        exporter = MediaStoreExporter(this)

        artworkCache = ArtworkCache(this)
        playlistStore = PlaylistStore(this)
        artistStore = ArtistStore(this)
        libraryRepository = MusicLibraryRepository(
            appContext = this,
            exporter = exporter,
            artworkCache = artworkCache,
            playlistStore = playlistStore,
            artistStore = artistStore
        )
        playbackStateStore = PlaybackStateStore(this)
        gainCoordinator = PlaybackGainCoordinator()
        sleepTimerController = SleepTimerController(
            context = this,
            gainCoordinator = gainCoordinator
        )
        audioAnalysisRepository = AudioAnalysisRepository(this)
        metadataRepository = MetadataRepository(this)
        listeningHistoryRepository = ListeningHistoryRepository(this)
        smartPlaylistRepository = SmartPlaylistRepository(this)
        playbackController = PlaybackController(
            appContext = this,
            stateStore = playbackStateStore,
            artworkCache = artworkCache,
            libraryRepository = libraryRepository
        )
        lyricsRepository = LyricsRepository(this)

        queueManager = DownloadQueueManager(
            appContext = this,
            engine = engine,
            exporter = exporter,
            store = settingsStore,
            onLibraryUpdated = { refreshLibrary() },
            onTrackExported = { exported ->
                audioAnalysisRepository.invalidate(exported.uri)
                libraryRepository.ingestExportedTrack(
                    uriString = exported.uri,
                    fallbackTitle = exported.title,
                    fallbackArtist = exported.artist,
                    fallbackAlbum = exported.album,
                    fallbackDisplayName = exported.displayName,
                    videoId = exported.videoId,
                    customFolderLabel = exported.folderLabel
                )
                val currentSettings = queueManager.settings.value
                if (currentSettings.autoFetchLyricsAfterDownload) {
                    lyricsRepository.fetchPostDownloadLyricsAsync(
                        uriString = exported.uri,
                        displayName = exported.displayName,
                        title = exported.title,
                        artist = exported.artist,
                        album = exported.album,
                        durationMs = exported.durationMs,
                        mimeType = exported.mimeType,
                        embedIntoFile = currentSettings.embedLyricsAfterDownload,
                        onLog = { line -> queueManager.appendLog(line) }
                    )
                }
            }
        )

        // Stage 1 (IO): Recover any metadata/lyrics writes interrupted by crash or process kill.
        // SafeAudioWritePipeline coordinates this gate so incoming writes wait for recovery completion.
        appScope.launch {
            val report = metadataRepository.recoverInterruptedWrites()
            if (report.scannedCount > 0) {
                report.details.forEach { detail -> queueManager.appendLog("RECOVERY: $detail") }
                if (report.restoredCount > 0) {
                    refreshLibrary()
                }
            }
        }

        // Stage 2 (IO -> Main): Wait for persisted AppSettings hydration before applying ReplayGain,
        // Sleep Timer defaults, playback session restoration, and MediaStore/SAF library scan.
        appScope.launch {
            val persistedSettings = queueManager.awaitHydrated()
            gainCoordinator.updateConfig(ReplayGainConfig.fromAppSettings(persistedSettings))
            sleepTimerController.updateFadeOutSeconds(persistedSettings.sleepTimerDefaultFadeSec)

            playbackController.connect(
                restoreLastSession = persistedSettings.restorePlaybackOnLaunch,
                defaultSpeed = persistedSettings.defaultPlaybackSpeed
            )
            refreshLibrary()
        }

        // Stage 3 (IO): Initialize yt-dlp environment asynchronously for downloader features
        engine.initializeAsync { line -> queueManager.appendLog(line) }
        appScope.launch {
            val persistedSettings = queueManager.awaitHydrated()
            if (engine.awaitReady() && persistedSettings.autoUpdate && !queueManager.hasActiveTasks) {
                val previous = runCatching { java.time.Instant.parse(persistedSettings.lastUpdateCheck) }.getOrNull()
                val due = previous == null || java.time.Duration.between(previous, java.time.Instant.now()).toHours() >= 24
                val cm = getSystemService(android.net.ConnectivityManager::class.java)
                val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                if (due && caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true) {
                    queueManager.updateSettings { it.copy(lastUpdateCheck = java.time.Instant.now().toString()) }
                    val result = engine.updateEngine(com.yausername.youtubedl_android.YoutubeDL.UpdateChannel.STABLE) { queueManager.appendLog(it) }
                    queueManager.updateSettings { it.copy(lastUpdateResult = result.getOrElse { "Update failed: ${it.message}" }) }
                }
            }
        }
    }

    /**
     * Connects [playbackController] only after persisted settings are hydrated, preventing
     * `MainActivity.onStart()` from racing with startup settings loading.
     */
    fun connectPlaybackAfterHydration() {
        appScope.launch {
            val persistedSettings = queueManager.awaitHydrated()
            playbackController.connect(
                restoreLastSession = persistedSettings.restorePlaybackOnLaunch,
                defaultSpeed = persistedSettings.defaultPlaybackSpeed
            )
        }
    }

    fun refreshLibrary(onComplete: ((Int) -> Unit)? = null) {
        appScope.launch {
            val currentSettings = queueManager.awaitHydrated()
            libraryRepository.refreshLibrary(
                customTreeUri = currentSettings.storageTreeUri,
                scanAllDeviceMusic = currentSettings.scanAllDeviceMusic,
                onComplete = onComplete
            )
        }
    }

    private fun installDebugStrictModeIfEnabled() {
        val isDebuggable = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        if (!isDebuggable) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build()
        )
    }
}

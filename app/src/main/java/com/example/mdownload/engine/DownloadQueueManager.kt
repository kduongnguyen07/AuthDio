package com.example.mdownload.engine

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.HistoryRecord
import com.example.mdownload.core.JobOptions
import com.example.mdownload.core.JobStage
import com.example.mdownload.core.JobStatus
import com.example.mdownload.core.LogSanitizer
import com.example.mdownload.core.PersistedState
import com.example.mdownload.core.QueueOrderHelper
import com.example.mdownload.core.RepositoryLoadState
import com.example.mdownload.core.SettingsStore
import com.example.mdownload.core.TaskFeedback
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.TrackMetadata
import com.example.mdownload.core.YouTubeUrlParser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

data class ExportedTrackNotification(
    val uri: String,
    val displayName: String,
    val title: String,
    val artist: String,
    val album: String,
    val videoId: String,
    val folderLabel: String,
    val durationMs: Long = 0L,
    val mimeType: String = ""
)

/**
 * Bounded concurrent download queue with gentle YouTube pacing, HTTP 403 retries,
 * HTTP 429/bot circuit-breaker, audio verification, and MediaStore export.
 * Direct Kotlin port of HiFi Deck downloads.py.
 */
class DownloadQueueManager(
    private val appContext: Context,
    private val engine: YtDlpEngine,
    private val exporter: MediaStoreExporter,
    private val store: SettingsStore,
    private val onLibraryUpdated: () -> Unit,
    private val onTrackExported: ((ExportedTrackNotification) -> Unit)? = null
) {
    companion object {
        private const val GENTLE_PACING_MS = 7_000L
        private const val SPEED_STALE_MS = 3_000L
        const val DEFAULT_TEST_VIDEO_URL = "https://www.youtube.com/watch?v=uelHwf8o7_U"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val scheduleMutex = Mutex()
    private val persistMutex = Mutex()
    private val hydrationLock = Any()
    private val hydratedDeferred = CompletableDeferred<AppSettings>()

    private val archiveDir = File(appContext.filesDir, "hifideck_archives")
    val storage = StorageTools(appContext)
    private val workRoot = storage.workRoot
    val hasActiveTasks: Boolean get() = activeTasks.isNotEmpty()
    @Volatile var storageBusy = false
    @Volatile private var appVisible = true
    val isAppVisible: Boolean get() = appVisible
    private val connectivity = appContext.getSystemService(android.net.ConnectivityManager::class.java)
    val policyMessage = MutableStateFlow("")
    private fun policyReason(): String {
        val caps = connectivity.getNetworkCapabilities(connectivity.activeNetwork)
        return com.example.mdownload.core.DownloadPolicy.waitReason(_settings.value.wifiOnly,
            _settings.value.allowBackground, appVisible,
            caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true,
            caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true)
    }
    fun setAppVisible(visible: Boolean) { appVisible = visible; enforcePolicy() }
    private fun enforcePolicy() {
        val reason = policyReason()
        policyMessage.value = reason
        if (reason.isNotBlank()) activeTasks.values.toList().forEach { task ->
            val stage = _jobs.value.firstOrNull { it.id == task.jobId }?.stage
            if (stage in listOf(JobStage.CONNECTING, JobStage.FETCHING_METADATA, JobStage.DOWNLOADING, JobStage.WAITING)) {
                task.policyPaused = true
                stopSingleTask(task)
            }
        }
        if (_isRunning.value) scope.launch { schedule() }
    }

    private val _loadState = MutableStateFlow(RepositoryLoadState.LOADING)
    val loadState: StateFlow<RepositoryLoadState> = _loadState.asStateFlow()

    @Volatile
    private var isHydrated: Boolean = false
    private var needsPersistAfterHydration: Boolean = false
    private val pendingSettingsTransforms = mutableListOf<(AppSettings) -> AppSettings>()
    private val pendingEarlyJobs = mutableListOf<DownloadJob>()
    private val pendingEarlyLogs = mutableListOf<String>()
    private var clearedLogsBeforeHydration: Boolean = false

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    private val _jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val jobs: StateFlow<List<DownloadJob>> = _jobs.asStateFlow()

    private val _history = MutableStateFlow<List<HistoryRecord>>(emptyList())
    val history: StateFlow<List<HistoryRecord>> = _history.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _totalSpeed = MutableStateFlow(0.0)
    val totalSpeed: StateFlow<Double> = _totalSpeed.asStateFlow()

    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()

    private val activeTasks = ConcurrentHashMap<String, ActiveTask>()
    private val speedSamples = ConcurrentHashMap<String, Pair<Double, Long>>()
    @Volatile
    private var nextNetworkStartMs: Long = 0L
    @Volatile
    private var pacingJob: Job? = null

    private data class ActiveTask(
        val jobId: String,
        val processId: String,
        val coroutineJob: Job,
        @Volatile var cancelled: Boolean = false,
        @Volatile var accessBlocked: Boolean = false,
        @Volatile var policyPaused: Boolean = false,
        @Volatile var userPaused: Boolean = false
    )

    init {
        // Hydrate persisted settings, queue, history, and logs asynchronously off Main Thread.
        scope.launch {
            hydrateFromDisk()
        }
        connectivity.registerDefaultNetworkCallback(object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: android.net.Network) { enforcePolicy() }
            override fun onLost(network: android.net.Network) { enforcePolicy() }
            override fun onCapabilitiesChanged(network: android.net.Network, caps: android.net.NetworkCapabilities) { enforcePolicy() }
        })
        // Periodic speed aggregator so idle/converting jobs decay to 0 B/s cleanly.
        scope.launch {
            while (true) {
                delay(1000L)
                val now = SystemClock.elapsedRealtime()
                val sum = speedSamples.values
                    .filter { now - it.second < SPEED_STALE_MS }
                    .sumOf { it.first }
                _totalSpeed.value = sum
            }
        }
    }

    suspend fun awaitHydrated(): AppSettings = hydratedDeferred.await()

    private fun hydrateFromDisk() {
        var failed = false
        val persisted = runCatching { store.load() }.getOrElse {
            failed = true
            PersistedState()
        }
        val diskLogs = runCatching { store.readLogs() }.getOrDefault(emptyList())

        var shouldPersist = false
        val finalSettings: AppSettings
        synchronized(hydrationLock) {
            var mergedSettings = persisted.settings
            for (transform in pendingSettingsTransforms) {
                mergedSettings = transform(mergedSettings)
            }
            pendingSettingsTransforms.clear()
            _settings.value = mergedSettings
            finalSettings = mergedSettings

            val mergedJobs = if (pendingEarlyJobs.isEmpty()) {
                persisted.jobs
            } else {
                val existingIds = persisted.jobs.mapTo(HashSet()) { it.id }
                persisted.jobs + pendingEarlyJobs.filter { it.id !in existingIds }
            }
            pendingEarlyJobs.clear()
            _jobs.value = mergedJobs

            _history.value = persisted.history

            val baseLogs = if (clearedLogsBeforeHydration) emptyList() else diskLogs
            _logs.value = (baseLogs + pendingEarlyLogs).takeLast(250)
            pendingEarlyLogs.clear()

            isHydrated = true
            shouldPersist = needsPersistAfterHydration
            needsPersistAfterHydration = false
        }
        _loadState.value = if (failed) RepositoryLoadState.FAILED else RepositoryLoadState.READY
        hydratedDeferred.complete(finalSettings)
        enforcePolicy()
        if (shouldPersist) {
            persist()
        }
    }

    fun appendLog(line: String) {
        val sanitized = LogSanitizer.sanitize(line)
        synchronized(hydrationLock) {
            if (!isHydrated) {
                pendingEarlyLogs.add(sanitized)
            }
        }
        _logs.update { (it + sanitized).takeLast(250) }
        scope.launch { store.appendLog(sanitized) }
    }

    fun clearLogs() {
        synchronized(hydrationLock) {
            if (!isHydrated) {
                clearedLogsBeforeHydration = true
                pendingEarlyLogs.clear()
            }
        }
        _logs.value = emptyList()
        scope.launch { store.clearLogs() }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        synchronized(hydrationLock) {
            if (!isHydrated) {
                pendingSettingsTransforms.add(transform)
                needsPersistAfterHydration = true
            }
            _settings.update { transform(it) }
        }
        if (!_settings.value.gentleRequests) {
            pacingJob?.cancel()
            pacingJob = null
        }
        persist()
        enforcePolicy()
    }

    fun enqueueTracks(
        items: List<DownloadJob>,
        autoStart: Boolean = false
    ) {
        if (items.isEmpty()) return
        synchronized(hydrationLock) {
            if (!isHydrated) {
                pendingEarlyJobs.addAll(items)
                needsPersistAfterHydration = true
            }
            _jobs.update { existing -> existing + items }
        }
        persist()
        if (autoStart) {
            startQueue()
        }
    }

    fun updateJobMetadata(jobId: String, metadata: TrackMetadata) {
        val cleaned = TextUtils.cleanMetadata(metadata)
        _jobs.update { list ->
            list.map { job ->
                if (job.id == jobId && job.status == JobStatus.QUEUED) {
                    job.copy(
                        metadata = cleaned,
                        title = cleaned.title.ifBlank { job.sourceTitle },
                        artist = cleaned.artist.ifBlank { job.artist },
                        thumbnailUrl = cleaned.customArtworkUri.ifBlank { job.thumbnailUrl }
                    )
                } else job
            }
        }
        persist()
    }

    fun removeJob(jobId: String) {
        activeTasks[jobId]?.let { stopSingleTask(it) }
        _jobs.update { list -> list.filterNot { it.id == jobId } }
        if (_jobs.value.isEmpty()) _isPaused.value = false
        persist()
    }

    fun moveJob(jobId: String, delta: Int) {
        _jobs.update { list -> QueueOrderHelper.moveQueuedJob(list, jobId, delta) }
        persist()
    }

    fun moveJobToTop(jobId: String) {
        _jobs.update { list -> QueueOrderHelper.moveQueuedJobToNext(list, jobId) }
        persist()
    }

    fun clearWaitingJobs() {
        _jobs.update { list -> list.filter { it.status.isActive } }
        if (_jobs.value.isEmpty()) _isPaused.value = false
        persist()
    }

    fun retryFromHistory(record: HistoryRecord, autoStart: Boolean = true) {
        val newJob = DownloadJob(
            url = record.url,
            title = record.metadata.title.ifBlank { record.sourceTitle },
            sourceTitle = record.sourceTitle,
            artist = record.artist,
            thumbnailUrl = record.thumbnailUrl,
            options = record.options.normalized(),
            metadata = record.metadata
        )
        _history.update { rows -> rows.filterNot { it.id == record.id && it.status == JobStatus.FAILED } }
        enqueueTracks(listOf(newJob), autoStart = autoStart)
    }

    fun retryAllFailed(
        downloadedVideoIds: Set<String> = emptySet(),
        autoStart: Boolean = true
    ): Int {
        val failed = _history.value.filter { it.status == JobStatus.FAILED && !it.isConnectionCheck }
        if (failed.isEmpty()) return 0

        val skipDups = _settings.value.skipDuplicates
        val existingJobs = _jobs.value.filterNot { it.isConnectionCheck }
        val seenKeys = mutableSetOf<String>()
        val jobsToAdd = mutableListOf<DownloadJob>()
        val consumedHistoryIds = mutableSetOf<String>()

        for (record in failed) {
            val videoId = YouTubeUrlParser.extractVideoId(record.url)
            val key = videoId ?: record.url
            if (!seenKeys.add(key)) {
                consumedHistoryIds.add(record.id)
                continue
            }
            val inQueue = existingJobs.any {
                (videoId != null && YouTubeUrlParser.extractVideoId(it.url) == videoId) ||
                    it.url == record.url
            }
            if (inQueue) continue
            if (skipDups && videoId != null && videoId in downloadedVideoIds) {
                consumedHistoryIds.add(record.id)
                continue
            }
            consumedHistoryIds.add(record.id)
            jobsToAdd.add(
                DownloadJob(
                    url = record.url,
                    title = record.metadata.title.ifBlank { record.sourceTitle },
                    sourceTitle = record.sourceTitle,
                    artist = record.artist,
                    thumbnailUrl = record.thumbnailUrl,
                    options = record.options.normalized(),
                    metadata = record.metadata
                )
            )
        }

        if (consumedHistoryIds.isNotEmpty()) {
            _history.update { rows -> rows.filterNot { it.id in consumedHistoryIds } }
        }
        if (jobsToAdd.isNotEmpty()) {
            enqueueTracks(jobsToAdd, autoStart = autoStart)
        } else {
            persist()
        }
        return jobsToAdd.size
    }

    fun clearHistory() {
        _history.value = emptyList()
        persist()
    }

    fun startQueue() {
        if (storageBusy) return
        _settings.update { it.copy(queueBlockReason = "") }
        _isPaused.value = false
        _jobs.update { list ->
            list.map { job ->
                if (job.status == JobStatus.QUEUED && job.detail.startsWith("Paused")) {
                    job.copy(detail = "Waiting for session")
                } else job
            }
        }
        persist()
        if (_jobs.value.none { it.status == JobStatus.QUEUED } && activeTasks.isEmpty()) {
            return
        }
        _isRunning.value = true
        ensureForegroundServiceRunning()
        scope.launch { schedule() }
    }

    /**
     * Pauses the queue without discarding tracks into History:
     * active downloads stop cleanly and remain at the top of Queue ready to resume.
     */
    fun pauseQueue() {
        if (!_isRunning.value && activeTasks.isEmpty()) return
        _isRunning.value = false
        _isPaused.value = true
        pacingJob?.cancel()
        pacingJob = null
        activeTasks.values.toList().forEach { task ->
            val stage = _jobs.value.firstOrNull { it.id == task.jobId }?.stage
            if (stage != JobStage.EXPORTING) {
                task.userPaused = true
                stopSingleTask(task)
            }
        }
        _jobs.update { list ->
            list.map { job ->
                if (job.status == JobStatus.QUEUED) job.copy(detail = "Paused — tap Resume") else job
            }
        }
        appendLog("QUEUE: Paused by user.")
        persist()
        if (activeTasks.isEmpty()) {
            stopForegroundService()
        }
    }

    fun stopAll() {
        _isRunning.value = false
        _isPaused.value = false
        pacingJob?.cancel()
        pacingJob = null
        activeTasks.values.toList().forEach { stopSingleTask(it) }
        if (activeTasks.isEmpty()) {
            stopForegroundService()
        }
    }

    fun startConnectionCheck(url: String = DEFAULT_TEST_VIDEO_URL): Boolean {
        if (storageBusy || _isRunning.value || activeTasks.isNotEmpty()) return false
        val normalized = runCatching { YouTubeUrlParser.normalizeUrl(url) }.getOrDefault(DEFAULT_TEST_VIDEO_URL)
        val checkJob = DownloadJob(
            url = normalized,
            title = "YouTube connection test",
            sourceTitle = "YouTube connection test",
            options = JobOptions(
                audioFormat = com.example.mdownload.core.AudioFormat.MP3,
                audioQuality = 128,
                asciiNames = true,
                embedArtwork = false,
                skipDuplicates = false
            ),
            isConnectionCheck = true
        )
        _jobs.update { listOf(checkJob) + it }
        _isRunning.value = true
        ensureForegroundServiceRunning()
        scope.launch { schedule(onlyJobId = checkJob.id) }
        return true
    }

    private fun stopSingleTask(task: ActiveTask) {
        if (task.cancelled) return
        task.cancelled = true
        updateJob(task.jobId) {
            it.copy(
                status = JobStatus.STOPPING,
                detail = "Stopping download and converter…",
                speedBytesPerSec = 0.0
            )
        }
        engine.cancelProcess(task.processId)
        task.coroutineJob.cancel()
    }

    private suspend fun schedule(onlyJobId: String? = null): Unit = scheduleMutex.withLock {
        if (!_isRunning.value) {
            if (activeTasks.isEmpty()) stopForegroundService()
            return@withLock
        }
        val reason = policyReason()
        policyMessage.value = reason
        if (reason.isNotBlank()) {
            _jobs.update { list -> list.map { if (it.status == JobStatus.QUEUED) it.copy(detail=reason) else it } }
            return@withLock
        }
        if (engine.state.value is com.example.mdownload.core.EngineState.Updating) {
            pacingJob = scope.launch { delay(1000); schedule(onlyJobId) }
            return@withLock
        }
        val currentSettings = _settings.value
        val limit = currentSettings.effectiveParallelDownloads

        while (_isRunning.value && activeTasks.size < limit) {
            val nextJob = _jobs.value.firstOrNull { job ->
                job.status == JobStatus.QUEUED &&
                    !activeTasks.containsKey(job.id) &&
                    (onlyJobId == null || job.id == onlyJobId)
            } ?: break

            val now = SystemClock.elapsedRealtime()
            val waitMs = nextNetworkStartMs - now
            if (currentSettings.gentleRequests && waitMs > 0L) {
                updateJob(nextJob.id) {
                    it.copy(detail = "Waiting briefly between YouTube requests…")
                }
                pacingJob?.cancel()
                pacingJob = scope.launch {
                    delay(waitMs)
                    schedule(onlyJobId)
                }
                return@withLock
            }

            launchWorker(nextJob)
        }

        if (activeTasks.isEmpty() && pacingJob?.isActive != true) {
            _isRunning.value = false
            stopForegroundService()
        }
    }

    private fun launchWorker(initialJob: DownloadJob) {
        val processId = "mdownload_${initialJob.id}_${UUID.randomUUID().toString().take(8)}"
        lateinit var task: ActiveTask
        val coroutineJob = scope.launch(start = CoroutineStart.LAZY) {
            runWorker(initialJob, task)
        }
        task = ActiveTask(
            jobId = initialJob.id,
            processId = processId,
            coroutineJob = coroutineJob
        )
        activeTasks[initialJob.id] = task
        coroutineJob.start()
    }

    private suspend fun runWorker(initialJob: DownloadJob, task: ActiveTask) {
        val options = initialJob.options.normalized()
        val videoId = YouTubeUrlParser.extractVideoId(initialJob.url)
        val jobWorkDir = File(workRoot, initialJob.id)
        var currentTitle = initialJob.title
        var currentArtist = initialJob.artist
        var finalStatus = JobStatus.FAILED
        var finalDetail = "No audio file was created."
        var savedUri = ""
        var savedName = ""
        var accessWarning = ""

        try {
            val activeTreeUri = _settings.value.storageTreeUri
            val activeFolderLabel = _settings.value.effectiveStorageLabel
            // Duplicate check by YouTube video code (filename + hidden audio metadata) before hitting the network
            if (options.skipDuplicates && !initialJob.isConnectionCheck && exporter.hasExistingVideoId(videoId, activeTreeUri)) {
                finalStatus = JobStatus.SKIPPED
                finalDetail = "Already downloaded (YouTube ID: $videoId)"
                appendLog("SKIPPED: $currentTitle ($finalDetail)")
                return
            }

            var attempt = 0
            while (attempt <= 2) {
                if (task.cancelled) {
                    finalStatus = JobStatus.CANCELLED
                    finalDetail = "Stopped; retry from History"
                    return
                }

                jobWorkDir.deleteRecursively()
                jobWorkDir.mkdirs()

                val errors = mutableListOf<String>()
                var producedFilePath: String? = null
                var reportedTitle: String? = null

                updateJob(initialJob.id) {
                    it.copy(
                        status = if (attempt == 0) JobStatus.RUNNING else JobStatus.RETRYING,
                        stage = JobStage.CONNECTING,
                        detail = if (attempt == 0) "Connecting…" else "HTTP 403 · Refreshing source ($attempt/2)…",
                        progress = 0f,
                        speedBytesPerSec = 0.0
                    )
                }

                appendLog("SOURCE: ${initialJob.url}" + if (attempt > 0) " (retry $attempt/2)" else "")

                archiveDir.mkdirs()
                val result = engine.executeDownload(
                    job = initialJob,
                    settings = _settings.value,
                    attempt = attempt,
                    workDir = jobWorkDir,
                    archiveDir = archiveDir,
                    processId = task.processId
                ) { progress, _, rawLine ->
                    val line = rawLine.trim()
                    if (line.isEmpty()) return@executeDownload

                    if ((line.startsWith("WARNING:") || line.startsWith("ERROR:")) &&
                        TaskFeedback.isYoutubeBlocked(line) &&
                        !task.accessBlocked
                    ) {
                        task.accessBlocked = true
                        accessWarning = line
                        onYoutubeBlocked(
                            "YouTube is limiting requests or requires verification. Waiting tracks are kept; resume manually later."
                        )
                    }

                    when {
                        line.startsWith("HF_PROGRESS:") -> {
                            val parts = line.removePrefix("HF_PROGRESS:").split('|', limit = 3)
                            val pctText = parts.getOrNull(0)?.trim().orEmpty()
                            val pctFloat = pctText.removeSuffix("%").trim().toFloatOrNull()?.div(100f)
                                ?: (progress / 100f).coerceIn(0f, 1f)
                            val speed = parts.getOrNull(1)?.trim()?.toDoubleOrNull()
                                ?.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
                            val liveTitle = parts.getOrNull(2)?.trim().orEmpty()
                            if (liveTitle.isNotBlank() && initialJob.metadata.title.isBlank()) {
                                currentTitle = TextUtils.cleanSourceTitle(liveTitle)
                            }
                            speedSamples[initialJob.id] = speed to SystemClock.elapsedRealtime()
                            updateJob(initialJob.id) {
                                it.copy(
                                    title = currentTitle,
                                    stage = JobStage.DOWNLOADING,
                                    progress = pctFloat.coerceIn(0f, 1f),
                                    speedBytesPerSec = speed,
                                    detail = "${pctText.ifBlank { "${(pctFloat * 100).toInt()}%" }} · ${TextUtils.formatSpeed(speed)}"
                                )
                            }
                        }

                        line.startsWith("HF_TITLE:") -> {
                            val t = line.removePrefix("HF_TITLE:").trim()
                            if (t.isNotBlank()) {
                                reportedTitle = t
                                if (initialJob.metadata.title.isBlank()) {
                                    currentTitle = TextUtils.cleanSourceTitle(t)
                                }
                                updateJob(initialJob.id) {
                                    it.copy(
                                        title = currentTitle,
                                        stage = JobStage.FETCHING_METADATA,
                                        detail = "Media identified · Waiting for audio stream…"
                                    )
                                }
                            }
                        }

                        line.startsWith("HF_FILE:") -> {
                            producedFilePath = OutputPathGuard.parseMarkerLine(line)
                            speedSamples.remove(initialJob.id)
                            appendLog(line)
                        }

                        line.startsWith("[ExtractAudio]") -> {
                            speedSamples.remove(initialJob.id)
                            val stage = if (options.audioFormat == com.example.mdownload.core.AudioFormat.ORIGINAL) {
                                JobStage.PROCESSING
                            } else JobStage.CONVERTING
                            updateJob(initialJob.id) {
                                it.copy(
                                    stage = stage,
                                    progress = 1f,
                                    speedBytesPerSec = 0.0,
                                    detail = "${stage.label}…"
                                )
                            }
                            appendLog(line)
                        }

                        line.startsWith("[Metadata]") -> {
                            speedSamples.remove(initialJob.id)
                            updateJob(initialJob.id) {
                                it.copy(
                                    stage = JobStage.WRITING_METADATA,
                                    progress = 1f,
                                    speedBytesPerSec = 0.0,
                                    detail = "Writing metadata…"
                                )
                            }
                            appendLog(line)
                        }

                        line.startsWith("[EmbedThumbnail]") || line.startsWith("[ThumbnailsConvertor]") -> {
                            speedSamples.remove(initialJob.id)
                            updateJob(initialJob.id) {
                                it.copy(
                                    stage = JobStage.EMBEDDING_ARTWORK,
                                    progress = 1f,
                                    speedBytesPerSec = 0.0,
                                    detail = "Embedding artwork…"
                                )
                            }
                            appendLog(line)
                        }

                        line.startsWith("[") &&
                            !line.startsWith("[download]") &&
                            !line.startsWith("[Fixup") -> {
                            updateJob(initialJob.id) {
                                if (it.stage == JobStage.CONNECTING || it.stage == JobStage.WAITING) {
                                    it.copy(stage = JobStage.FETCHING_METADATA, detail = "Fetching metadata…")
                                } else it
                            }
                            appendLog(line)
                        }

                        else -> {
                            if ("ERROR:" in line) {
                                errors.add(line)
                            }
                            appendLog(line)
                        }
                    }
                }

                speedSamples.remove(initialJob.id)

                if (task.cancelled) {
                    finalStatus = JobStatus.CANCELLED
                    finalDetail = "Stopped; retry from History"
                    return
                }

                // Locate produced file from HF_FILE marker or stdout fallback or workDir scan.
                val rawOutputFile = resolveProducedFile(producedFilePath, result.getOrNull(), jobWorkDir)

                if (rawOutputFile != null) {
                    val cleanMeta = TextUtils.cleanMetadata(initialJob.metadata)
                    val outputFile = if (!initialJob.isConnectionCheck && !cleanMeta.isEmpty) {
                        val tagStage = if (options.embedArtwork && cleanMeta.customArtworkUri.isNotBlank()) {
                            JobStage.EMBEDDING_ARTWORK
                        } else {
                            JobStage.WRITING_METADATA
                        }
                        updateJob(initialJob.id) {
                            it.copy(
                                stage = tagStage,
                                progress = 1f,
                                speedBytesPerSec = 0.0,
                                detail = "${tagStage.label}…"
                            )
                        }
                        CoverArtHelper.applyMetadataAndCover(
                            context = appContext,
                            audioFile = rawOutputFile,
                            metadata = cleanMeta,
                            videoId = videoId,
                            embedArtwork = options.embedArtwork,
                            workDir = jobWorkDir,
                            onLog = ::appendLog
                        )
                    } else {
                        rawOutputFile
                    }
                    val verified = exporter.verifyAudioFile(outputFile)
                    if (verified != null) {
                        if (initialJob.isConnectionCheck) {
                            // Keep connection check self-contained in cache without polluting Music/MDownload.
                            finalStatus = JobStatus.DONE
                            finalDetail = "Verified ${TextUtils.formatLabel(options)} (${TextUtils.formatFileSize(outputFile.length())}, ${TextUtils.formatDurationMs(verified.durationMs)})"
                            savedName = outputFile.name
                            _settings.update { it.copy(queueBlockReason = "") }
                            appendLog("CHECK OK: $finalDetail")
                            return
                        }

                        updateJob(initialJob.id) {
                            it.copy(
                                stage = JobStage.EXPORTING,
                                progress = 1f,
                                speedBytesPerSec = 0.0,
                                detail = "Verifying and saving to $activeFolderLabel…"
                            )
                        }
                        storage.retain(initialJob.copy(title=currentTitle), outputFile)
                        val exported = exporter.exportToPublicMusic(
                            sourceFile = outputFile,
                            videoId = videoId,
                            options = options,
                            metadataOverride = initialJob.metadata,
                            verifiedInfo = verified,
                            fallbackTitle = reportedTitle ?: currentTitle,
                            fallbackArtist = currentArtist,
                            customTreeUri = activeTreeUri
                        )
                        savedUri = exported.uri
                        savedName = exported.displayName
                        finalStatus = JobStatus.DONE
                        finalDetail = "Saved · ${TextUtils.formatLabel(options)} · ${TextUtils.formatFileSize(exported.sizeBytes)}"
                        appendLog("SAVED: ${exported.displayName}")
                        if (onTrackExported != null) {
                            onTrackExported.invoke(
                                ExportedTrackNotification(
                                    uri = exported.uri,
                                    displayName = exported.displayName,
                                    title = initialJob.metadata.title.ifBlank { reportedTitle ?: currentTitle },
                                    artist = initialJob.metadata.artist.ifBlank { verified.artist }.ifBlank { currentArtist },
                                    album = initialJob.metadata.album.ifBlank { verified.album },
                                    videoId = videoId.orEmpty(),
                                    folderLabel = activeFolderLabel,
                                    durationMs = verified.durationMs,
                                    mimeType = options.audioFormat.mimeType
                                )
                            )
                        } else {
                            onLibraryUpdated()
                        }
                        return
                    } else {
                        errors.add("No valid audio file was produced (verification failed).")
                    }
                } else if (result.isSuccess && options.skipDuplicates && !initialJob.isConnectionCheck &&
                    exporter.hasExistingExport(videoId, options, activeTreeUri)
                ) {
                    finalStatus = JobStatus.SKIPPED
                    finalDetail = "Already downloaded for ${TextUtils.formatLabel(options)}"
                    appendLog("SKIPPED: $currentTitle")
                    return
                } else {
                    result.exceptionOrNull()?.message?.let { msg ->
                        if (msg.isNotBlank()) {
                            errors.add(msg)
                            if (TaskFeedback.isYoutubeBlocked(msg) && !task.accessBlocked) {
                                task.accessBlocked = true
                                accessWarning = msg
                                onYoutubeBlocked("YouTube is limiting requests or requires verification. Resume manually later.")
                            }
                        }
                    }
                }

                // HTTP 403 retry logic (up to 2 retries, unless rate-limited/blocked)
                val isTransient403 = errors.any { "403" in it && ("HTTP" in it || "Forbidden" in it) }
                if (!task.cancelled && !task.accessBlocked && isTransient403 && attempt < 2) {
                    attempt++
                    appendLog("RETRY: HTTP 403; refreshing extraction, retry $attempt/2")
                    delay(2000L * attempt)
                    continue
                }

                finalStatus = JobStatus.FAILED
                finalDetail = errors.lastOrNull()
                    ?: accessWarning.ifBlank { "No audio file was created. Disable Skip duplicates if you deleted a previous file." }
                return
            }
        } catch (_: CancellationException) {
            finalStatus = JobStatus.CANCELLED
            finalDetail = "Stopped; retry from History"
        } catch (e: Exception) {
            if (task.cancelled) {
                finalStatus = JobStatus.CANCELLED
                finalDetail = "Stopped; retry from History"
            } else {
                finalStatus = JobStatus.FAILED
                finalDetail = e.message ?: "Unexpected download error"
                appendLog("ERROR: $finalDetail")
            }
        } finally {
            speedSamples.remove(initialJob.id)
            if (finalStatus != JobStatus.FAILED) {
                runCatching { jobWorkDir.deleteRecursively() }
            } else if (jobWorkDir.exists()) {
                appendLog("RECOVERY: Retained working audio at ${jobWorkDir.absolutePath}")
            }
            activeTasks.remove(initialJob.id)
            nextNetworkStartMs = SystemClock.elapsedRealtime() + GENTLE_PACING_MS

            if (task.policyPaused || task.userPaused) {
                val detailText = if (task.userPaused) {
                    "Paused — tap Resume"
                } else {
                    policyReason().ifBlank { "Waiting for session" }
                }
                updateJob(initialJob.id) {
                    it.copy(
                        status = JobStatus.QUEUED,
                        stage = JobStage.WAITING,
                        progress = 0f,
                        speedBytesPerSec = 0.0,
                        detail = detailText
                    )
                }
                persist()
                if (task.userPaused) {
                    if (activeTasks.isEmpty() && !_isRunning.value) {
                        stopForegroundService()
                    }
                } else {
                    scope.launch { schedule() }
                }
            } else {
            // Move completed/failed/cancelled/skipped job out of Queue and into History.
            _jobs.update { list -> list.filterNot { it.id == initialJob.id } }
            val record = HistoryRecord(
                id = initialJob.id,
                url = initialJob.url,
                title = currentTitle,
                sourceTitle = initialJob.sourceTitle,
                artist = currentArtist,
                thumbnailUrl = initialJob.thumbnailUrl,
                status = finalStatus,
                detail = finalDetail,
                completedAtIso = Instant.now().toString(),
                formatLabel = TextUtils.formatLabel(options),
                savedUri = savedUri,
                savedDisplayName = savedName,
                options = options,
                metadata = initialJob.metadata,
                isConnectionCheck = initialJob.isConnectionCheck
            )
            _history.update { listOf(record) + it.take(299) }
            persist()

            if (!initialJob.isConnectionCheck) {
                scope.launch { schedule() }
            } else {
                _isRunning.value = false
                stopForegroundService()
            }
            }
        }
    }

    suspend fun retryExport(id: String): String = kotlinx.coroutines.withContext(Dispatchers.IO) {
        check(!hasActiveTasks && !_isRunning.value) { "Stop downloads before retrying an export" }
        val exported = storage.retry(id, exporter, _settings.value.storageTreeUri)
        val matched = _history.value.firstOrNull { it.id == id }
        _history.update { rows -> rows.map { if (it.id == id) it.copy(status=JobStatus.DONE,
            savedUri=exported.uri, savedDisplayName=exported.displayName, detail="Saved from local recovery") else it } }
        persist()
        if (onTrackExported != null) {
            onTrackExported.invoke(
                ExportedTrackNotification(
                    uri = exported.uri,
                    displayName = exported.displayName,
                    title = matched?.title.orEmpty(),
                    artist = matched?.artist.orEmpty(),
                    album = matched?.metadata?.album.orEmpty(),
                    videoId = matched?.url?.let { YouTubeUrlParser.extractVideoId(it) }.orEmpty(),
                    folderLabel = _settings.value.effectiveStorageLabel
                )
            )
        } else {
            onLibraryUpdated()
        }
        exported.displayName
    }

    private fun resolveProducedFile(markerPath: String?, stdout: String?, workDir: File): File? {
        return OutputPathGuard.resolveProducedFile(
            markerPath = markerPath,
            stdout = stdout,
            workDir = workDir,
            onRejected = { reason -> appendLog("SECURITY: $reason") }
        )
    }

    private fun onYoutubeBlocked(reason: String) {
        _isRunning.value = false
        pacingJob?.cancel()
        pacingJob = null
        _settings.update { it.copy(queueBlockReason = reason) }
        appendLog("QUEUE HELD: $reason")
        persist()
    }

    private fun updateJob(jobId: String, transform: (DownloadJob) -> DownloadJob) {
        _jobs.update { list ->
            list.map { if (it.id == jobId) transform(it) else it }
        }
    }

    private fun persist() {
        synchronized(hydrationLock) {
            if (!isHydrated) {
                needsPersistAfterHydration = true
                return
            }
        }
        scope.launch {
            persistMutex.withLock {
                store.save(
                    PersistedState(
                        settings = _settings.value,
                        jobs = _jobs.value,
                        history = _history.value
                    )
                )
            }
        }
    }

    private fun ensureForegroundServiceRunning() {
        runCatching {
            val intent = Intent(appContext, DownloadForegroundService::class.java).apply {
                action = DownloadForegroundService.ACTION_START
            }
            appContext.startForegroundService(intent)
        }.onFailure { err ->
            appendLog("SERVICE: Could not start foreground service (${err.message})")
        }
    }

    private fun stopForegroundService() {
        runCatching {
            appContext.stopService(Intent(appContext, DownloadForegroundService::class.java))
        }
    }
}

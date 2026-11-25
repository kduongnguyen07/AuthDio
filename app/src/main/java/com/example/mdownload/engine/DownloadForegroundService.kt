package com.example.mdownload.engine

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.example.mdownload.MDownloadApp
import com.example.mdownload.MainActivity
import com.example.mdownload.R
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.JobStage
import com.example.mdownload.core.JobStatus
import com.example.mdownload.core.TextUtils
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Pure notification update throttler that caps high-frequency progress/speed notification posts
 * to at most once per [minIntervalMs] while immediately allowing structural state transitions
 * (active job list/stage changes, completion, cancellation).
 */
internal class NotificationUpdateThrottler(
    private val minIntervalMs: Long = 500L
) {
    private var lastEmitMs: Long = Long.MIN_VALUE
    private var lastStructureKey: String? = null

    fun shouldEmitNow(
        nowMs: Long,
        structureKey: String,
        force: Boolean = false
    ): Boolean {
        val structureChanged = lastStructureKey != structureKey
        val elapsedEnough = lastEmitMs == Long.MIN_VALUE || (nowMs - lastEmitMs) >= minIntervalMs
        return if (force || structureChanged || elapsedEnough) {
            lastEmitMs = nowMs
            lastStructureKey = structureKey
            true
        } else {
            false
        }
    }

    fun remainingDelayMs(nowMs: Long): Long {
        if (lastEmitMs == Long.MIN_VALUE) return 0L
        return (minIntervalMs - (nowMs - lastEmitMs)).coerceAtLeast(0L)
    }

    fun reset() {
        lastEmitMs = Long.MIN_VALUE
        lastStructureKey = null
    }
}

/**
 * OriginOS-resilient Foreground Service (`dataSync`) that keeps downloads and FFmpeg tagging alive
 * while the screen is off or the user switches apps. Manages WakeLock + WifiLock lifecycle safely,
 * displays live multi-track progress with a Cancel action, and posts a completion summary when done
 * in the background.
 */
class DownloadForegroundService : Service() {

    companion object {
        const val ACTION_START = "com.example.mdownload.action.START_DOWNLOAD_SERVICE"
        const val ACTION_STOP = "com.example.mdownload.action.STOP_DOWNLOAD_SERVICE"
        const val ACTION_CANCEL_ALL = "com.example.mdownload.action.CANCEL_ALL"
        private const val CHANNEL_PROGRESS_ID = "mdownload_active_downloads"
        private const val CHANNEL_COMPLETE_ID = "mdownload_completed_downloads"
        private const val NOTIFICATION_PROGRESS_ID = 4102
        private const val NOTIFICATION_COMPLETE_ID = 4103
        private const val WAKELOCK_TIMEOUT_MS = 60L * 60L * 1000L // 60 min safety cap per acquisition
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var observing = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sessionCompletedBaseline = 0
    private var userCancelled = false
    private val notificationThrottler = NotificationUpdateThrottler(minIntervalMs = 500L)
    private var pendingNotificationJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as MDownloadApp
        when (intent?.action) {
            ACTION_CANCEL_ALL -> {
                userCancelled = true
                pendingNotificationJob?.cancel()
                notificationThrottler.reset()
                app.queueManager.appendLog("SERVICE: Downloads cancelled from notification.")
                app.queueManager.stopAll()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                pendingNotificationJob?.cancel()
                notificationThrottler.reset()
                releaseLocks()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }

        userCancelled = false
        if (!observing) {
            sessionCompletedBaseline = app.queueManager.history.value.count {
                it.status == JobStatus.DONE && !it.isConnectionCheck
            }
        }

        acquireLocks()
        val initialJobs = app.queueManager.jobs.value
        val initialSpeed = app.queueManager.totalSpeed.value
        val initial = buildProgressNotification(jobs = initialJobs, speed = initialSpeed)
        notificationThrottler.shouldEmitNow(
            nowMs = SystemClock.elapsedRealtime(),
            structureKey = notificationStructureKey(initialJobs),
            force = true
        )
        startForeground(
            NOTIFICATION_PROGRESS_ID,
            initial,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )

        if (!observing) {
            observing = true
            serviceScope.launch {
                combine(
                    app.queueManager.jobs,
                    app.queueManager.isRunning,
                    app.queueManager.totalSpeed
                ) { jobs, running, speed ->
                    Triple(jobs, running, speed)
                }.collect { (jobs, running, speed) ->
                    val hasActive = jobs.any { it.status.isActive }
                    if (!running && !hasActive) {
                        pendingNotificationJob?.cancel()
                        pendingNotificationJob = null
                        notificationThrottler.reset()
                        val completedNow = app.queueManager.history.value.count {
                            it.status == JobStatus.DONE && !it.isConnectionCheck
                        }
                        val newlySaved = (completedNow - sessionCompletedBaseline).coerceAtLeast(0)
                        if (!userCancelled && newlySaved > 0 && !app.queueManager.isAppVisible) {
                            postCompletionNotification(newlySaved, app.queueManager.settings.value.effectiveStorageLabel)
                        }
                        releaseLocks()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    } else {
                        acquireLocks()
                        postProgressNotificationThrottled(jobs, speed)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun notificationStructureKey(jobs: List<DownloadJob>): String {
        val active = jobs.filter { it.status.isActive }
        return buildString {
            append(jobs.size)
            append('|')
            for (job in active) {
                append(job.id)
                append(':')
                append(job.stage.name)
                append(':')
                append(job.status.name)
                append(',')
            }
        }
    }

    private fun postProgressNotificationThrottled(jobs: List<DownloadJob>, speed: Double) {
        val nowMs = SystemClock.elapsedRealtime()
        val structureKey = notificationStructureKey(jobs)
        if (notificationThrottler.shouldEmitNow(nowMs = nowMs, structureKey = structureKey)) {
            pendingNotificationJob?.cancel()
            pendingNotificationJob = null
            val notification = buildProgressNotification(jobs, speed)
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_PROGRESS_ID, notification)
        } else if (pendingNotificationJob?.isActive != true) {
            val waitMs = notificationThrottler.remainingDelayMs(nowMs).coerceAtLeast(50L)
            pendingNotificationJob = serviceScope.launch {
                delay(waitMs)
                pendingNotificationJob = null
                val app = application as MDownloadApp
                val latestJobs = app.queueManager.jobs.value
                if (!app.queueManager.isRunning.value && latestJobs.none { it.status.isActive }) {
                    return@launch
                }
                val latestSpeed = app.queueManager.totalSpeed.value
                notificationThrottler.shouldEmitNow(
                    nowMs = SystemClock.elapsedRealtime(),
                    structureKey = notificationStructureKey(latestJobs),
                    force = true
                )
                val notification = buildProgressNotification(latestJobs, latestSpeed)
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_PROGRESS_ID, notification)
            }
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        val app = application as MDownloadApp
        val allowBg = app.queueManager.settings.value.allowBackground
        val active = app.queueManager.isRunning.value || app.queueManager.hasActiveTasks
        if (allowBg && active) {
            pendingNotificationJob?.cancel()
            pendingNotificationJob = null
            val currentJobs = app.queueManager.jobs.value
            notificationThrottler.shouldEmitNow(
                nowMs = SystemClock.elapsedRealtime(),
                structureKey = notificationStructureKey(currentJobs),
                force = true
            )
            // Keep foreground notification refreshed so OriginOS / Android preserves the active download session.
            val notification = buildProgressNotification(
                jobs = currentJobs,
                speed = app.queueManager.totalSpeed.value
            )
            getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_PROGRESS_ID, notification)
        } else {
            pendingNotificationJob?.cancel()
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        pendingNotificationJob?.cancel()
        val app = application as MDownloadApp
        app.queueManager.appendLog("SERVICE: System foreground time limit reached; stopping cleanly so remaining tracks stay queued.")
        app.queueManager.stopAll()
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        pendingNotificationJob?.cancel()
        releaseLocks()
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        runCatching {
            val pm = getSystemService(PowerManager::class.java)
            if (wakeLock == null && pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MDownload::BackgroundDownloadWakeLock").apply {
                    setReferenceCounted(false)
                }
            }
            if (wakeLock?.isHeld != true) {
                wakeLock?.acquire(WAKELOCK_TIMEOUT_MS)
            }
        }
        runCatching {
            val wm = applicationContext.getSystemService(WifiManager::class.java)
            if (wifiLock == null && wm != null) {
                wifiLock = wm.createWifiLock(
                    WifiManager.WIFI_MODE_FULL_LOW_LATENCY,
                    "MDownload::BackgroundDownloadWifiLock"
                ).apply {
                    setReferenceCounted(false)
                }
            }
            if (wifiLock?.isHeld != true) {
                wifiLock?.acquire()
            }
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        }
        runCatching {
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun isVietnamese(): Boolean =
        resources.configuration.locales[0]?.language == "vi"

    private fun buildProgressNotification(
        jobs: List<DownloadJob>,
        speed: Double
    ): Notification {
        val vi = isVietnamese()
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val cancelIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, DownloadForegroundService::class.java).apply {
                action = ACTION_CANCEL_ALL
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activeJobs = jobs.filter { it.status.isActive }
        val primary = activeJobs.firstOrNull()
        val totalRemaining = jobs.size
        val speedSuffix = if (speed > 0.0) " · ${TextUtils.formatSpeed(speed)}" else ""

        val title = when {
            activeJobs.size > 1 ->
                if (vi) "Đang tải ${activeJobs.size} bài cùng lúc" else "Downloading ${activeJobs.size} tracks"
            primary != null -> primary.title
            else -> if (vi) "Đang chuẩn bị tải nhạc" else "Preparing downloads"
        }

        val downloadingJobs = activeJobs.filter { it.stage == JobStage.DOWNLOADING && it.progress > 0f }
        val avgProgress = if (downloadingJobs.isNotEmpty()) {
            downloadingJobs.map { it.progress.coerceIn(0f, 1f) }.average().toFloat()
        } else {
            primary?.progress?.coerceIn(0f, 1f) ?: 0f
        }
        val pct = (avgProgress * 100).toInt().coerceIn(0, 100)
        val indeterminate = activeJobs.isEmpty() ||
            (downloadingJobs.isEmpty() && primary?.stage != JobStage.EXPORTING && primary?.stage != JobStage.CONVERTING)

        val content = when {
            activeJobs.size > 1 -> {
                val pctPart = if (!indeterminate) "$pct%" else (if (vi) "Đang xử lý" else "Working")
                val remPart = if (vi) "còn $totalRemaining bài" else "$totalRemaining remaining"
                "$pctPart$speedSuffix · $remPart"
            }
            primary != null -> {
                val stagePart = if (primary.stage == JobStage.DOWNLOADING && pct > 0) "$pct%" else primary.stage.label
                val remPart = if (vi) "còn $totalRemaining bài" else "$totalRemaining left"
                "$stagePart$speedSuffix · $remPart"
            }
            else -> if (vi) "Đang chuẩn bị ($totalRemaining bài)" else "Preparing queue ($totalRemaining tracks)"
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_PROGRESS_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setContentIntent(openIntent)
            .setProgress(100, pct, indeterminate)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                if (vi) "Hủy tải" else "Cancel",
                cancelIntent
            )

        if (activeJobs.size > 1) {
            val detailLines = activeJobs.take(4).joinToString("\n") { job ->
                val p = (job.progress * 100).toInt().coerceIn(0, 100)
                val statusText = if (job.stage == JobStage.DOWNLOADING && p > 0) "$p%" else job.stage.label
                "• ${job.title} ($statusText)"
            }
            builder.setStyle(NotificationCompat.BigTextStyle().bigText("$content\n$detailLines"))
        }

        return builder.build()
    }

    private fun postCompletionNotification(savedCount: Int, folderLabel: String) {
        val vi = isVietnamese()
        val openIntent = PendingIntent.getActivity(
            this,
            2,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val title = if (vi) "Hoàn tất tải xuống" else "Downloads complete"
        val body = if (vi) {
            "Đã lưu $savedCount bài vào $folderLabel"
        } else {
            "Saved $savedCount track(s) to $folderLabel"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_COMPLETE_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setAutoCancel(true)
            .setContentIntent(openIntent)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_COMPLETE_ID, notification)
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val progressChannel = NotificationChannel(
            CHANNEL_PROGRESS_ID,
            "Music Downloads",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows live progress and Cancel action for active background downloads."
            setShowBadge(false)
        }
        val completeChannel = NotificationChannel(
            CHANNEL_COMPLETE_ID,
            "Completed Downloads",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Notifies when background music downloads finish."
        }
        manager.createNotificationChannel(progressChannel)
        manager.createNotificationChannel(completeChannel)
    }
}

package com.example.mdownload

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.automirrored.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.example.mdownload.core.AudioFormat
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.JobOptions
import com.example.mdownload.core.SharedLinkPayload
import com.example.mdownload.core.SourcePreviewInfo
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.TrackMetadata
import com.example.mdownload.core.YouTubeUrlParser
import com.example.mdownload.ui.AppText as Text
import com.example.mdownload.ui.components.StatusPill
import com.example.mdownload.ui.localText
import com.example.mdownload.ui.theme.AccentSubtleLight
import com.example.mdownload.ui.theme.MDownloadTheme
import com.example.mdownload.ui.theme.PrimaryAccent
import com.example.mdownload.ui.theme.StatusSuccess
import com.example.mdownload.ui.theme.StatusSuccessSubtle
import com.example.mdownload.ui.translateText
import java.util.UUID

private data class QuickFormatChoice(
    val label: String,
    val format: AudioFormat,
    val quality: Int
)

private val QUICK_FORMATS = listOf(
    QuickFormatChoice("MP3 128k", AudioFormat.MP3, 128),
    QuickFormatChoice("MP3 192k", AudioFormat.MP3, 192),
    QuickFormatChoice("MP3 320k", AudioFormat.MP3, 320),
    QuickFormatChoice("M4A 192k", AudioFormat.M4A, 192),
    QuickFormatChoice("Opus 160k", AudioFormat.OPUS, 160),
    QuickFormatChoice("Original", AudioFormat.ORIGINAL, 128)
)

/**
 * Translucent Android Share Sheet receiver ("Share -> HiFiDeck").
 * Accepts links from YouTube, YouTube Music, and any yt-dlp-supported source,
 * allowing one-tap or instant background audio download without leaving the source app.
 */
class ShareDownloadActivity : ComponentActivity() {

    private val app: MDownloadApp get() = application as MDownloadApp

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val payload = extractPayloadFromIntent(intent)
        if (payload.urls.isEmpty()) {
            showToast("No supported media link found.")
            finish()
            return
        }

        val currentSettings = app.queueManager.settings.value
        val firstUrl = payload.urls.first()
        val isSinglePurePlaylist = payload.urls.size == 1 &&
            YouTubeUrlParser.isPlaylist(firstUrl) &&
            YouTubeUrlParser.singleVideoUrl(firstUrl) == null

        // Instant zero-tap background download when enabled (for single/multi tracks; playlists open picker).
        if (currentSettings.instantShareDownload && !isSinglePurePlaylist) {
            val outcome = enqueueUrls(
                urls = payload.urls,
                titleHint = payload.titleHint,
                preview = null,
                options = currentSettings.toJobOptions(),
                autoStart = true
            )
            showToast(outcome)
            finish()
            return
        }

        setContent {
            val settings by app.queueManager.settings.collectAsState()
            val jobs by app.queueManager.jobs.collectAsState()
            val libraryTracks by app.libraryTracks.collectAsState()
            val dark = when (settings.themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            MDownloadTheme(darkTheme = dark) {
                ShareDownloadOverlay(
                    payload = payload,
                    initialOptions = settings.toJobOptions(),
                    storageLabel = settings.effectiveStorageLabel,
                    instantShareDownload = settings.instantShareDownload,
                    gentleRequests = settings.gentleRequests,
                    jobs = jobs,
                    downloadedVideoIds = remember(libraryTracks) {
                        libraryTracks.mapNotNull { it.videoId.takeIf(String::isNotBlank) }.toSet()
                    },
                    inspectSource = { targetUrl, procId ->
                        app.engine.inspectSource(
                            rawUrl = targetUrl,
                            gentleRequests = settings.gentleRequests,
                            processId = procId
                        )
                    },
                    cancelInspect = { procId -> app.engine.cancelProcess(procId) },
                    onToggleInstantShare = { enabled ->
                        app.queueManager.updateSettings { it.copy(instantShareDownload = enabled) }
                    },
                    onDownload = { urls, preview, options, autoStart ->
                        val msg = enqueueUrls(
                            urls = urls,
                            titleHint = payload.titleHint,
                            preview = preview,
                            options = options,
                            autoStart = autoStart
                        )
                        showToast(msg)
                        finish()
                    },
                    onChoosePlaylistInApp = { playlistUrl ->
                        openInMainApp(playlistUrl = playlistUrl)
                        finish()
                    },
                    onOpenInApp = {
                        openInMainApp(sharedText = payload.urls.joinToString("\n"))
                        finish()
                    },
                    onDismiss = { finish() }
                )
            }
        }
    }

    private fun extractPayloadFromIntent(intent: Intent?): SharedLinkPayload {
        if (intent == null) return SharedLinkPayload(emptyList())
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        val parts = mutableListOf<String>()

        when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
                intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
            Intent.ACTION_SEND_MULTIPLE -> {
                intent.getStringArrayListExtra(Intent.EXTRA_TEXT)?.forEach { item ->
                    if (!item.isNullOrBlank()) parts.add(item)
                }
            }
            Intent.ACTION_VIEW -> {
                intent.dataString?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
            }
        }

        val clip = intent.clipData
        if (parts.isEmpty() && clip != null) {
            for (i in 0 until clip.itemCount) {
                val text = clip.getItemAt(i)?.coerceToText(this)?.toString().orEmpty()
                if (text.isNotBlank()) parts.add(text)
            }
        }

        val combined = parts.distinct().joinToString("\n")
        return YouTubeUrlParser.extractSharePayload(combined, subject)
    }

    private fun enqueueUrls(
        urls: List<String>,
        titleHint: String,
        preview: SourcePreviewInfo?,
        options: JobOptions,
        autoStart: Boolean
    ): String {
        val queue = app.queueManager
        val existingJobs = queue.jobs.value.filterNot { it.isConnectionCheck }
        val existingLibraryIds = app.libraryTracks.value
            .mapNotNull { it.videoId.takeIf(String::isNotBlank) }
            .toSet()

        val seenKeys = mutableSetOf<String>()
        val jobsToAdd = mutableListOf<DownloadJob>()
        var skippedQueue = 0
        var skippedLibrary = 0

        for (raw in urls) {
            val single = YouTubeUrlParser.singleVideoUrl(raw) ?: continue
            val videoId = YouTubeUrlParser.extractVideoId(single)
            val dedupKey = videoId ?: single
            if (!seenKeys.add(dedupKey)) continue

            val inQueue = existingJobs.any {
                (videoId != null && YouTubeUrlParser.extractVideoId(it.url) == videoId) ||
                    it.url == single
            }
            if (inQueue) {
                skippedQueue++
                continue
            }
            if (options.skipDuplicates && videoId != null && videoId in existingLibraryIds) {
                skippedLibrary++
                continue
            }

            val matchedPreview = preview?.takeIf {
                it.normalizedUrl == raw || it.singleVideoUrl == single || urls.size == 1
            }
            val rawTitle = matchedPreview?.title?.takeIf { it.isNotBlank() }
                ?: titleHint.takeIf { urls.size == 1 && it.isNotBlank() }
                ?: "${YouTubeUrlParser.sourceName(single)} audio"
            val cleanTitle = TextUtils.cleanSourceTitle(rawTitle)
            val thumb = matchedPreview?.thumbnailUrl?.takeIf { it.isNotBlank() }
                ?: if (videoId != null) "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" else ""

            val smartMetadata = if (matchedPreview != null) {
                TrackMetadata(
                    title = cleanTitle,
                    artist = matchedPreview.artist.takeUnless { it.equals("Unknown artist", ignoreCase = true) }.orEmpty(),
                    album = matchedPreview.album,
                    year = matchedPreview.releaseYear
                )
            } else {
                TrackMetadata()
            }

            jobsToAdd.add(
                DownloadJob(
                    url = single,
                    title = cleanTitle,
                    sourceTitle = rawTitle,
                    artist = matchedPreview?.artist.orEmpty(),
                    thumbnailUrl = thumb,
                    options = options.normalized(),
                    metadata = smartMetadata
                )
            )
        }

        if (jobsToAdd.isEmpty()) {
            return when {
                skippedLibrary > 0 -> "Already downloaded."
                skippedQueue > 0 -> "Already in queue."
                else -> "No downloadable track found."
            }
        }

        queue.enqueueTracks(jobsToAdd, autoStart = autoStart)
        return if (jobsToAdd.size == 1) {
            if (autoStart) "Downloading \"${jobsToAdd.first().title}\"…"
            else "Added \"${jobsToAdd.first().title}\" to queue."
        } else {
            val verb = if (autoStart) "Downloading" else "Queued"
            val dupNote = if (skippedQueue + skippedLibrary > 0) {
                " (${skippedQueue + skippedLibrary} duplicate skipped)"
            } else ""
            "$verb ${jobsToAdd.size} track(s)$dupNote"
        }
    }

    private fun openInMainApp(sharedText: String? = null, playlistUrl: String? = null) {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (!sharedText.isNullOrBlank()) putExtra(MainActivity.EXTRA_SHARED_TEXT, sharedText)
            if (!playlistUrl.isNullOrBlank()) putExtra(MainActivity.EXTRA_OPEN_PLAYLIST_URL, playlistUrl)
        }
        startActivity(openIntent)
    }

    private fun showToast(englishMessage: String) {
        val isVi = resources.configuration.locales[0]?.language == "vi"
        Toast.makeText(applicationContext, translateText(englishMessage, isVi), Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun ShareDownloadOverlay(
    payload: SharedLinkPayload,
    initialOptions: JobOptions,
    storageLabel: String,
    instantShareDownload: Boolean,
    gentleRequests: Boolean,
    jobs: List<DownloadJob>,
    downloadedVideoIds: Set<String>,
    inspectSource: suspend (targetUrl: String, processId: String) -> Result<SourcePreviewInfo>,
    cancelInspect: (processId: String) -> Unit,
    onToggleInstantShare: (Boolean) -> Unit,
    onDownload: (urls: List<String>, preview: SourcePreviewInfo?, options: JobOptions, autoStart: Boolean) -> Unit,
    onChoosePlaylistInApp: (playlistUrl: String) -> Unit,
    onOpenInApp: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryUrl = payload.urls.first()
    val isMulti = payload.urls.size > 1
    val singleTarget = remember(primaryUrl) { YouTubeUrlParser.singleVideoUrl(primaryUrl) }
    val sourceLabel = remember(payload.urls) {
        if (isMulti) "${payload.urls.size} links" else YouTubeUrlParser.sourceName(primaryUrl)
    }
    val initialVideoId = remember(singleTarget) { singleTarget?.let { YouTubeUrlParser.extractVideoId(it) } }
    val initialThumb = remember(initialVideoId) {
        if (!initialVideoId.isNullOrBlank()) "https://i.ytimg.com/vi/$initialVideoId/mqdefault.jpg" else ""
    }

    var selectedOptions by remember { mutableStateOf(initialOptions) }
    var preview by remember { mutableStateOf<SourcePreviewInfo?>(null) }
    var isInspecting by remember { mutableStateOf(!isMulti) }
    var inspectError by remember { mutableStateOf<String?>(null) }

    val procId = remember(primaryUrl) { "share_${UUID.randomUUID().toString().take(8)}" }

    DisposableEffect(procId) {
        onDispose { cancelInspect(procId) }
    }

    LaunchedEffect(primaryUrl, gentleRequests) {
        if (isMulti) {
            isInspecting = false
            return@LaunchedEffect
        }
        isInspecting = true
        inspectError = null
        val target = singleTarget ?: primaryUrl
        inspectSource(target, procId)
            .onSuccess { info ->
                preview = info.copy(
                    normalizedUrl = primaryUrl,
                    isPlaylist = info.isPlaylist || YouTubeUrlParser.isPlaylist(primaryUrl),
                    isMix = YouTubeUrlParser.isMixPlaylist(primaryUrl),
                    singleVideoUrl = singleTarget
                )
                isInspecting = false
            }
            .onFailure { err ->
                inspectError = err.message
                isInspecting = false
            }
    }

    val isPlaylist = !isMulti && (YouTubeUrlParser.isPlaylist(primaryUrl) || preview?.isPlaylist == true)
    val isPurePlaylist = isPlaylist && singleTarget == null
    val hasBothSingleAndPlaylist = isPlaylist && singleTarget != null

    val inQueue = remember(jobs, singleTarget, initialVideoId) {
        !isMulti && singleTarget != null && jobs.any {
            !it.isConnectionCheck && (
                (initialVideoId != null && YouTubeUrlParser.extractVideoId(it.url) == initialVideoId) ||
                    it.url == singleTarget
                )
        }
    }
    val alreadyDownloaded = remember(downloadedVideoIds, initialVideoId, selectedOptions.skipDuplicates) {
        !isMulti && selectedOptions.skipDuplicates && initialVideoId != null && initialVideoId in downloadedVideoIds
    }

    val displayTitle = when {
        isMulti -> "${payload.urls.size} shared media links"
        !preview?.title.isNullOrBlank() -> TextUtils.cleanSourceTitle(preview!!.title)
        payload.titleHint.isNotBlank() -> TextUtils.cleanSourceTitle(payload.titleHint)
        else -> primaryUrl
    }
    val displayArtist = when {
        isMulti -> payload.urls.joinToString(" · ") { YouTubeUrlParser.sourceName(it) }
        !preview?.artist.isNullOrBlank() -> buildList {
            add(preview!!.artist)
            if (preview!!.album.isNotBlank()) add(preview!!.album)
            if (preview!!.releaseYear.isNotBlank()) add(preview!!.releaseYear)
            preview?.durationSec?.let { add(TextUtils.formatDurationSeconds(it)) }
        }.joinToString(" · ")
        isInspecting -> "Fetching track info…"
        !inspectError.isNullOrBlank() -> "Ready to download via yt-dlp"
        else -> sourceLabel
    }
    val displayThumb = preview?.thumbnailUrl?.takeIf { it.isNotBlank() } ?: initialThumb

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.25f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.BottomCenter
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                ),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header: HiFiDeck + detected source badge + dismiss
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "HiFiDeck",
                        style = MaterialTheme.typography.titleLarge
                    )
                    StatusPill(
                        text = sourceLabel,
                        backgroundColor = AccentSubtleLight,
                        contentColor = PrimaryAccent
                    )
                    if (isPlaylist) {
                        StatusPill(
                            text = if (YouTubeUrlParser.isMixPlaylist(primaryUrl)) "Mix" else "Playlist",
                            backgroundColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = localText("Cancel"))
                    }
                }

                // Track / link summary card
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(width = 68.dp, height = 50.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center
                        ) {
                            if (displayThumb.isNotBlank()) {
                                AsyncImage(
                                    model = displayThumb,
                                    contentDescription = localText("Artwork"),
                                    contentScale = ContentScale.Crop,
                                    modifier = Modifier.fillMaxSize()
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Rounded.MusicNote,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = displayTitle,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Spacer(Modifier.height(2.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                if (isInspecting) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(12.dp),
                                        strokeWidth = 1.8.dp
                                    )
                                }
                                Text(
                                    text = displayArtist,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (alreadyDownloaded || inQueue) {
                                Spacer(Modifier.height(4.dp))
                                StatusPill(
                                    text = if (alreadyDownloaded) "Downloaded" else "In queue",
                                    backgroundColor = if (alreadyDownloaded) StatusSuccessSubtle else AccentSubtleLight,
                                    contentColor = if (alreadyDownloaded) StatusSuccess else PrimaryAccent
                                )
                            }
                        }
                    }
                }

                // Quick format selector (for downloadable tracks)
                if (!isPurePlaylist) {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(QUICK_FORMATS) { choice ->
                            val selected = selectedOptions.audioFormat == choice.format &&
                                (choice.format == AudioFormat.ORIGINAL || selectedOptions.audioQuality == choice.quality)
                            FilterChip(
                                selected = selected,
                                onClick = {
                                    selectedOptions = selectedOptions.copy(
                                        audioFormat = choice.format,
                                        audioQuality = choice.quality
                                    ).normalized()
                                },
                                label = { Text(choice.label) }
                            )
                        }
                    }
                }

                // Folder & instant-share row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.FolderOpen,
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = storageLabel,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "Instant Share",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Switch(
                        checked = instantShareDownload,
                        onCheckedChange = onToggleInstantShare
                    )
                }

                // Action buttons
                if (isPurePlaylist) {
                    Button(
                        onClick = { onChoosePlaylistInApp(primaryUrl) },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            Icons.AutoMirrored.Rounded.PlaylistPlay,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text("Choose tracks in app…")
                    }
                } else {
                    val blockedByDuplicate = alreadyDownloaded || inQueue
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                onDownload(payload.urls, preview, selectedOptions, true)
                            },
                            enabled = !blockedByDuplicate,
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(
                                imageVector = if (alreadyDownloaded) Icons.Rounded.CheckCircle else Icons.Rounded.Download,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = when {
                                    alreadyDownloaded -> "Downloaded"
                                    inQueue -> "In queue"
                                    isMulti -> "Download (${payload.urls.size})"
                                    hasBothSingleAndPlaylist -> "Download this track"
                                    else -> "Download now"
                                },
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        if (hasBothSingleAndPlaylist) {
                            OutlinedButton(
                                onClick = { onChoosePlaylistInApp(primaryUrl) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Rounded.PlaylistPlay,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(4.dp))
                                Text("Playlist")
                            }
                        } else if (!blockedByDuplicate) {
                            OutlinedButton(
                                onClick = {
                                    onDownload(payload.urls, preview, selectedOptions, false)
                                },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Queue")
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onOpenInApp) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("Open in app")
                        }
                    }
                }
            }
        }
    }
}

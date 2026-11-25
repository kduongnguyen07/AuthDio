package com.example.mdownload.engine

import android.content.Context
import android.util.Log
import com.example.mdownload.R
import kotlinx.coroutines.CancellationException
import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.AudioFormat
import com.example.mdownload.core.DownloadJob
import com.example.mdownload.core.EngineState
import com.example.mdownload.core.PlaylistTrackItem
import com.example.mdownload.core.SourcePreviewInfo
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.YouTubeUrlParser
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Android yt-dlp + FFmpeg + QuickJS engine wrapper, mirroring HiFi Deck's core.py & worker.py
 * command construction and reliability flags.
 */
class YtDlpEngine(private val appContext: Context) {

    companion object {
        private const val TAG = "YtDlpEngine"
        private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")

        /**
         * Anchored whole-string regex for Python `re.sub` (used by yt-dlp `--replace-in-metadata`).
         * Uses `\A` and `\Z` so Python 3.7+ performs exactly one substitution across the entire
         * metadata value rather than matching twice (`(?s).*` + trailing empty match).
         */
        const val FULL_STRING_PYTHON_REGEX = "(?s)\\A.*\\Z"

        /**
         * Escapes a literal user-supplied string for safe use as a Python `re.sub` replacement
         * template in yt-dlp `--replace-in-metadata` (SEC-02).
         * Strips control characters and doubles every backslash so backreferences (`\1`, `\g<0>`),
         * escape sequences (`\n`), and trailing backslashes (`\`) are treated strictly as literals.
         */
        fun escapePythonRegexReplacement(literal: String): String {
            val cleaned = literal
                .replace('\r', ' ')
                .replace('\n', ' ')
                .filter { it >= ' ' && it != '\u007F' }
                .trim()
            return cleaned.replace("\\", "\\\\")
        }

        /**
         * Builds the `--replace-in-metadata` argument list to safely replace `title` with a
         * literal user-supplied title string.
         */
        fun buildTitleReplaceCommand(title: String): List<String> =
            listOf("--replace-in-metadata", "title", FULL_STRING_PYTHON_REGEX, escapePythonRegexReplacement(title))
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initDeferred = CompletableDeferred<Boolean>()

    private val _state = MutableStateFlow<EngineState>(EngineState.Initializing)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun initializeAsync(onLog: (String) -> Unit = {}) {
        scope.launch {
            try {
                onLog("ENGINE: Initializing yt-dlp, Python, QuickJS, and FFmpeg…")
                YoutubeDL.getInstance().init(appContext)
                FFmpeg.getInstance().init(appContext)
                var version = installedVersion()
                if (version < "2026.10.26") {
                    // Library init retains the old downloaded script across APK upgrades.
                    val script = File(appContext.noBackupFilesDir, "youtubedl-android/yt-dlp/yt-dlp")
                    val temporary = File(script.parentFile, "yt-dlp.new")
                    appContext.resources.openRawResource(R.raw.ytdlp).use { input ->
                        temporary.outputStream().use { input.copyTo(it) }
                    }
                    check(temporary.renameTo(script)) { "Could not install bundled yt-dlp update" }
                    version = installedVersion()
                    onLog("ENGINE: Installed bundled yt-dlp $version")
                }
                _state.value = EngineState.Ready(version)
                onLog("ENGINE: Ready (yt-dlp $version)")
                initDeferred.complete(true)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize yt-dlp/FFmpeg", e)
                val msg = e.message ?: e.javaClass.simpleName
                _state.value = EngineState.Error(msg)
                onLog("ERROR: Engine init failed: $msg")
                initDeferred.complete(false)
            }
        }
    }

    private fun installedVersion(): String {
        val request = YoutubeDLRequest(emptyList<String>()).apply { addOption("--version") }
        return YoutubeDL.getInstance().execute(request).out.trim()
    }

    suspend fun awaitReady(): Boolean = initDeferred.await()

    suspend fun updateEngine(
        channel: YoutubeDL.UpdateChannel,
        onLog: (String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!awaitReady()) {
            return@withContext Result.failure(IllegalStateException("Engine failed to initialize."))
        }
        val current = (state.value as? EngineState.Ready)?.ytDlpVersion ?: "unknown"
        _state.value = EngineState.Updating(current)
        try {
            val channelName = channel.javaClass.simpleName
            onLog("ENGINE: Checking for yt-dlp updates ($channelName)…")
            val status = YoutubeDL.getInstance().updateYoutubeDL(appContext, channel)
            val newVersion = installedVersion()
            _state.value = EngineState.Ready(newVersion)
            val summary = when (status) {
                YoutubeDL.UpdateStatus.DONE -> "Updated yt-dlp to $newVersion"
                YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE -> "yt-dlp is already up to date ($newVersion)"
                null -> "Update check finished ($newVersion)"
            }
            onLog("ENGINE: $summary")
            Result.success(summary)
        } catch (e: Exception) {
            _state.value = EngineState.Ready(current)
            val msg = e.message ?: "Update failed"
            onLog("ERROR: yt-dlp update failed: $msg")
            Result.failure(e)
        }
    }

    /**
     * Metadata-only inspection matching core.py's inspect_arguments().
     */
    suspend fun inspectSource(
        rawUrl: String,
        gentleRequests: Boolean,
        processId: String
    ): Result<SourcePreviewInfo> = withContext(Dispatchers.IO) {
        if (!awaitReady()) {
            return@withContext Result.failure(IllegalStateException("Download engine is not ready."))
        }
        try {
            val normalized = YouTubeUrlParser.normalizeUrl(rawUrl)
            val isPlaylist = YouTubeUrlParser.isPlaylist(normalized)
            val isMix = YouTubeUrlParser.isMixPlaylist(normalized)
            val singleVideo = YouTubeUrlParser.singleVideoUrl(normalized)
            val targetUrl = if (isPlaylist) {
                YouTubeUrlParser.playlistUrl(normalized)
            } else {
                singleVideo ?: normalized
            }
            YouTubeUrlParser.validateResolvedHost(targetUrl)

            val request = YoutubeDLRequest(targetUrl).apply {
                addOption("--ignore-config")
                addOption("--no-color")
                addOption("--no-warnings")
                addOption("--force-ipv4")
                addOption("--skip-download")
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--ignore-errors")
                addOption("--ignore-no-formats-error")
                addOption("--socket-timeout", "15")
                addOption("--retries", "1")
                addOption("--extractor-retries", "1")
                if (isPlaylist) {
                    addOption("--yes-playlist")
                    if (isMix) {
                        addOption("--playlist-end", "50")
                    }
                } else {
                    addOption("--no-playlist")
                }
                if (gentleRequests) {
                    addOption("--sleep-requests", "1")
                }
            }

            val response = YoutubeDL.getInstance().execute(request, processId, null)
            val rawOut = response.out.trim()
            val jsonLine = rawOut.lines().lastOrNull { it.trimStart().startsWith("{") }
                ?: throw IllegalStateException("No metadata returned from source.")
            val info = JSONObject(jsonLine)

            val rawEntries = info.optJSONArray("entries")
            val parsedEntries = parsePlaylistTracks(rawEntries, isYouTube = YouTubeUrlParser.isYouTubeUrl(normalized))
            val resolvedIsPlaylist = isPlaylist || (info.optString("_type") == "playlist" && parsedEntries.size > 1)
            val entries = if (resolvedIsPlaylist) parsedEntries else emptyList()
            val rawTitle = info.optString("track")
                .ifBlank { info.optString("title") }
                .ifBlank { "Untitled" }
            val title = TextUtils.cleanSourceTitle(rawTitle)
            val rawArtist = info.optString("artist")
                .ifBlank { info.optString("creator") }
                .ifBlank { info.optString("channel") }
                .ifBlank { info.optString("uploader") }
                .ifBlank { "Unknown artist" }
            val artist = TextUtils.cleanArtistName(rawArtist).ifBlank { "Unknown artist" }
            val album = info.optString("album").trim()
            val releaseYear = TextUtils.extractYear(
                info.opt("release_year")?.toString(),
                info.optString("release_date"),
                info.optString("upload_date")
            )
            val durationSec = info.optDouble("duration", -1.0)
                .takeIf { it.isFinite() && it >= 0.0 && it < 1e8 }?.toInt()
            val videoId = YouTubeUrlParser.extractVideoId(singleVideo ?: normalized)
            val thumb = TextUtils.upgradeThumbnailUrl(extractBestThumbnail(info), videoId)

            Result.success(
                SourcePreviewInfo(
                    sourceUrl = rawUrl,
                    normalizedUrl = normalized,
                    isPlaylist = resolvedIsPlaylist,
                    isMix = isMix,
                    singleVideoUrl = singleVideo,
                    title = title,
                    artist = artist,
                    album = album,
                    releaseYear = releaseYear,
                    durationSec = durationSec,
                    thumbnailUrl = thumb,
                    entries = entries
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * yt-dlp ytsearch fallback when direct YouTube HTML parsing returns empty or is blocked.
     */
    suspend fun searchYouTube(
        query: String,
        limit: Int = 15,
        gentleRequests: Boolean,
        processId: String
    ): Result<List<PlaylistTrackItem>> = withContext(Dispatchers.IO) {
        if (!awaitReady()) {
            return@withContext Result.failure(IllegalStateException("Download engine is not ready."))
        }
        try {
            val cleanQuery = query.trim()
            if (cleanQuery.isEmpty()) return@withContext Result.success(emptyList())
            val request = YoutubeDLRequest("ytsearch$limit:$cleanQuery").apply {
                addOption("--ignore-config")
                addOption("--no-color")
                addOption("--no-warnings")
                addOption("--force-ipv4")
                addOption("--skip-download")
                addOption("--dump-single-json")
                addOption("--flat-playlist")
                addOption("--ignore-errors")
                addOption("--ignore-no-formats-error")
                addOption("--socket-timeout", "15")
                addOption("--retries", "1")
                addOption("--extractor-retries", "1")
                if (gentleRequests) {
                    addOption("--sleep-requests", "1")
                }
            }
            val response = YoutubeDL.getInstance().execute(request, processId, null)
            val rawOut = response.out.trim()
            val jsonLine = rawOut.lines().lastOrNull { it.trimStart().startsWith("{") }
                ?: return@withContext Result.success(emptyList())
            val info = JSONObject(jsonLine)
            Result.success(parsePlaylistTracks(info.optJSONArray("entries")))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Executes a single-video audio download matching core.py's download_arguments().
     */
    suspend fun executeDownload(
        job: DownloadJob,
        settings: AppSettings,
        attempt: Int,
        workDir: File,
        archiveDir: File,
        processId: String,
        onProgressLine: (progress: Float, etaSec: Long, line: String) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        if (!awaitReady()) {
            return@withContext Result.failure(IllegalStateException("Download engine is not ready."))
        }
        try {
            workDir.mkdirs()
            archiveDir.mkdirs()

            val options = job.options.normalized()
            val normalizedJobUrl = YouTubeUrlParser.normalizeUrl(job.url)
            val targetUrl = YouTubeUrlParser.singleVideoUrl(normalizedJobUrl) ?: normalizedJobUrl
            YouTubeUrlParser.validateResolvedHost(targetUrl)
            val suffix = TextUtils.profileSuffix(options)
            val formatSpec = if (attempt == 1) {
                "bestaudio[ext=m4a]/bestaudio/best"
            } else {
                "bestaudio/best"
            }

            val request = YoutubeDLRequest(targetUrl).apply {
                addOption("--ignore-config")
                addOption("--no-color")
                addOption("--newline")
                addOption("--progress")
                addOption("--force-ipv4")
                addOption("--no-simulate")
                addOption("--no-playlist")
                addOption("--no-mtime")
                addOption("--socket-timeout", "25")
                if (attempt > 0) {
                    addOption("--check-formats")
                    addOption("--no-cache-dir")
                }

                if (settings.gentleRequests) {
                    addOption("--retries", "1")
                    addOption("--fragment-retries", "2")
                    addOption("--concurrent-fragments", "1")
                    addOption("--extractor-retries", "1")
                    addOption("--sleep-requests", "1")
                } else {
                    val threads = settings.effectiveDownloadThreads
                    addOption("--retries", "3")
                    addOption("--fragment-retries", "3")
                    addOption("--concurrent-fragments", threads.toString())
                    addOption("--http-chunk-size", "10M")
                }

                addOption("--format", formatSpec)
                addOption("--extract-audio")
                val audioFmtArg = if (options.audioFormat == AudioFormat.ORIGINAL) "best" else options.audioFormat.id
                addOption("--audio-format", audioFmtArg)
                if (options.audioFormat != AudioFormat.ORIGINAL) {
                    addOption("--audio-quality", "${options.audioQuality}K")
                }
                if (options.audioFormat == AudioFormat.MP3) {
                    addOption("--postprocessor-args", "ExtractAudio+ffmpeg_o:-ar 44100 -ac 2")
                }

                addOption("--embed-metadata")
                addOption("--trim-filenames", "160")
                addCommands(listOf("--replace-in-metadata", "title", TextUtils.TITLE_CLEANUP_PATTERN, ""))
                // Embed hidden YouTube video ID fingerprint inside the container comment metadata tag
                // so duplicate detection works reliably even if the exported file is renamed.
                addCommands(listOf("--parse-metadata", "%(id)s:(?P<meta_comment>.+)"))
                addCommands(
                    listOf(
                        "--replace-in-metadata",
                        "meta_comment",
                        "^(.+)$",
                        "${com.example.mdownload.core.AudioTagFingerprint.TAG_PREFIX}\\1"
                    )
                )

                val cleanMeta = TextUtils.cleanMetadata(job.metadata)
                if (cleanMeta.title.isNotBlank()) {
                    addCommands(buildTitleReplaceCommand(cleanMeta.title))
                }

                if (options.embedArtwork && cleanMeta.customArtworkUri.isBlank()) {
                    addOption("--embed-thumbnail")
                    addOption("--convert-thumbnails", "jpg")
                }

                // MediaStore is the duplicate authority. An extraction archive can
                // claim success before export, or after the public file was deleted.
                addOption("--force-overwrites")

                addOption("--paths", workDir.absolutePath)
                addOption("--output", "%(title).120B$suffix [%(id)s].%(ext)s")
                addOption(
                    "--progress-template",
                    "download:HF_PROGRESS:%(progress._percent_str)s|%(progress.speed)s|%(info.title)s"
                )
                addOption("--print", "before_dl:HF_TITLE:%(title)s")
                addOption("--print", "after_move:HF_FILE:%(filepath)s")
            }

            val response = YoutubeDL.getInstance().execute(request, processId) { progress, eta, line ->
                onProgressLine(progress, eta, line)
            }
            response.err.lines().filter { it.isNotBlank() }.forEach {
                onProgressLine(0f, 0L, it)
            }
            Result.success(response.out)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.message?.let { onProgressLine(0f, 0L, it) }
            Result.failure(e)
        }
    }

    fun cancelProcess(processId: String): Boolean {
        return runCatching {
            YoutubeDL.getInstance().destroyProcessById(processId)
        }.getOrDefault(false)
    }

    private fun parsePlaylistTracks(array: JSONArray?, isYouTube: Boolean = true): List<PlaylistTrackItem> {
        if (array == null) return emptyList()
        val result = mutableListOf<PlaylistTrackItem>()
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            val videoId = entry.optString("id", "")
            val directUrl = entry.optString("webpage_url")
                .ifBlank { entry.optString("url") }
                .takeIf { it.startsWith("http://", ignoreCase = true) || it.startsWith("https://", ignoreCase = true) }
                .orEmpty()
            val title = entry.optString("title", "").ifBlank { "Unavailable track ${i + 1}" }
            val availability = entry.optString("availability", "")
            val hasValidTarget = directUrl.isNotBlank() || (isYouTube && VIDEO_ID_REGEX.matches(videoId))
            val unavailable = !hasValidTarget ||
                title.lowercase() in setOf("[private video]", "[deleted video]", "[unavailable video]") ||
                availability in setOf("private", "needs_auth", "premium_only", "subscriber_only")
            val durationSec = entry.optDouble("duration", -1.0)
                .takeIf { it.isFinite() && it >= 0.0 }?.toInt()
            val artist = entry.optString("channel")
                .ifBlank { entry.optString("uploader") }
                .ifBlank { entry.optString("artist") }
                .ifBlank { "Unknown artist" }
            val resolvedUrl = when {
                unavailable -> ""
                directUrl.isNotBlank() -> directUrl
                else -> "https://www.youtube.com/watch?v=$videoId"
            }
            val thumb = extractBestThumbnail(entry).ifBlank {
                if (!unavailable && isYouTube && VIDEO_ID_REGEX.matches(videoId)) {
                    "https://i.ytimg.com/vi/$videoId/mqdefault.jpg"
                } else ""
            }

            result.add(
                PlaylistTrackItem(
                    index = i + 1,
                    videoId = videoId,
                    url = resolvedUrl,
                    title = title,
                    artist = artist,
                    durationSec = durationSec,
                    available = !unavailable,
                    thumbnailUrl = thumb
                )
            )
        }
        return result
    }

    private fun extractBestThumbnail(obj: JSONObject): String {
        val thumbs = obj.optJSONArray("thumbnails")
        if (thumbs != null && thumbs.length() > 0) {
            var bestUrl = ""
            var bestArea = -1L
            for (i in 0 until thumbs.length()) {
                val item = thumbs.optJSONObject(i) ?: continue
                val url = item.optString("url", "")
                if (!url.startsWith("https://")) continue
                val w = item.optLong("width", 0L)
                val h = item.optLong("height", 0L)
                val area = if (w > 0L && h > 0L) w * h else i.toLong()
                if (area >= bestArea) {
                    bestArea = area
                    bestUrl = url
                }
            }
            if (bestUrl.isNotBlank()) return bestUrl
        }
        val direct = obj.optString("thumbnail", "")
        if (direct.startsWith("https://")) return direct
        return ""
    }
}

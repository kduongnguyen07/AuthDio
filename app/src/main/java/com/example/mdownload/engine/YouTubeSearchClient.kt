package com.example.mdownload.engine

import com.example.mdownload.core.DiscoverResultItem
import com.example.mdownload.core.DiscoverSearchFilter
import com.example.mdownload.core.TextUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Fetches accurate YouTube search results and autocomplete suggestions in the background
 * (from YouTube's search pages / ytInitialData) so Discover can render a fast, native UI
 * instead of embedding the m.youtube.com webpage.
 */
object YouTubeSearchClient {

    private const val DESKTOP_USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    private const val MOBILE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 12_000

    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{6,64}$")
    private val HEX_ESCAPE_REGEX = Regex("""\\x([0-9a-fA-F]{2})""")

    suspend fun fetchSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.length < 2) return@withContext emptyList()
        runCatching {
            val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
            val url = "https://suggestqueries-clients6.youtube.com/complete/search?client=firefox&ds=yt&q=$encoded"
            val body = httpGet(url, DESKTOP_USER_AGENT)
            val root = JSONArray(body)
            val arr = root.optJSONArray(1) ?: return@withContext emptyList()
            buildList {
                for (i in 0 until arr.length().coerceAtMost(8)) {
                    val item = arr.optString(i).trim()
                    if (item.isNotEmpty() && !item.equals(trimmed, ignoreCase = true)) {
                        add(item)
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun search(
        query: String,
        filter: DiscoverSearchFilter = DiscoverSearchFilter.ALL,
        languageTag: String = "vi"
    ): Result<List<DiscoverResultItem>> = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return@withContext Result.success(emptyList())

        val encoded = URLEncoder.encode(trimmed, StandardCharsets.UTF_8.name())
        val spParam = if (filter.spParam.isNotBlank()) "&sp=${filter.spParam}" else ""
        val hl = if (languageTag.startsWith("vi", ignoreCase = true)) "vi" else "en"

        // Try desktop YouTube search HTML first (richer metadata), then m.youtube.com as backup.
        val attempts = listOf(
            "https://www.youtube.com/results?search_query=$encoded$spParam&hl=$hl" to DESKTOP_USER_AGENT,
            "https://m.youtube.com/results?search_query=$encoded$spParam&hl=$hl" to MOBILE_USER_AGENT
        )

        var lastError: Throwable? = null
        for ((url, ua) in attempts) {
            try {
                val html = httpGet(url, ua)
                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    val items = parseYtInitialData(initialData, filter)
                    if (items.isNotEmpty()) {
                        return@withContext Result.success(items)
                    }
                }
            } catch (e: Throwable) {
                lastError = e
            }
        }

        if (lastError != null) {
            Result.failure(lastError)
        } else {
            Result.success(emptyList())
        }
    }

    private fun httpGet(urlString: String, userAgent: String): String {
        val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Accept", "text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
            setRequestProperty("Accept-Language", "vi-VN,vi;q=0.9,en-US;q=0.8,en;q=0.7")
            setRequestProperty("Cookie", "CONSENT=PENDING+987; SOCS=CAI")
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("HTTP $code from YouTube")
            }
            connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    internal fun extractYtInitialData(html: String): JSONObject? {
        // Case 1: Desktop / modern YouTube: var ytInitialData = {...};
        val markers = listOf(
            "var ytInitialData = ",
            "window[\"ytInitialData\"] = ",
            "ytInitialData = "
        )
        for (marker in markers) {
            val startIdx = html.indexOf(marker)
            if (startIdx < 0) continue
            val valueStart = skipWhitespace(html, startIdx + marker.length)
            if (valueStart >= html.length) continue

            val firstChar = html[valueStart]
            if (firstChar == '{') {
                val jsonSlice = extractBalancedJsonObject(html, valueStart) ?: continue
                runCatching { return JSONObject(jsonSlice) }
            } else if (firstChar == '\'' || firstChar == '"') {
                // Case 2: m.youtube.com hex-escaped JS string: var ytInitialData = '\x7b...';
                val decoded = extractJsQuotedString(html, valueStart, firstChar) ?: continue
                runCatching { return JSONObject(decoded) }
            }
        }
        return null
    }

    internal fun parseYtInitialData(
        root: JSONObject,
        filter: DiscoverSearchFilter = DiscoverSearchFilter.ALL
    ): List<DiscoverResultItem> {
        val results = mutableListOf<DiscoverResultItem>()
        val seenKeys = HashSet<String>()

        fun addUnique(item: DiscoverResultItem) {
            if (filter == DiscoverSearchFilter.VIDEOS && item.isPlaylist) return
            if (filter == DiscoverSearchFilter.PLAYLISTS && !item.isPlaylist) return
            val key = (if (item.isPlaylist) "PL:" else "V:") + item.id
            if (seenKeys.add(key)) {
                results.add(item)
            }
        }

        fun walk(node: Any?) {
            if (results.size >= 30) return
            when (node) {
                is JSONObject -> {
                    when {
                        node.has("videoRenderer") -> {
                            parseVideoRenderer(node.optJSONObject("videoRenderer"))?.let(::addUnique)
                            return
                        }
                        node.has("videoWithContextRenderer") -> {
                            parseVideoWithContextRenderer(node.optJSONObject("videoWithContextRenderer"))?.let(::addUnique)
                            return
                        }
                        node.has("compactVideoRenderer") -> {
                            parseVideoRenderer(node.optJSONObject("compactVideoRenderer"))?.let(::addUnique)
                            return
                        }
                        node.has("playlistRenderer") -> {
                            parsePlaylistRenderer(node.optJSONObject("playlistRenderer"))?.let(::addUnique)
                            return
                        }
                        node.has("compactPlaylistRenderer") -> {
                            parsePlaylistRenderer(node.optJSONObject("compactPlaylistRenderer"))?.let(::addUnique)
                            return
                        }
                        node.has("lockupViewModel") -> {
                            parseLockupViewModel(node.optJSONObject("lockupViewModel"))?.let(::addUnique)
                            return
                        }
                    }
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        // Skip irrelevant heavy subtrees
                        if (key == "responseContext" || key == "topbar" || key == "trackingParams") continue
                        walk(node.opt(key))
                    }
                }
                is JSONArray -> {
                    for (i in 0 until node.length()) {
                        walk(node.opt(i))
                    }
                }
            }
        }

        walk(root)
        return results
    }

    private fun parseVideoRenderer(obj: JSONObject?): DiscoverResultItem? {
        if (obj == null) return null
        val videoId = obj.optString("videoId", "")
        if (!VIDEO_ID_REGEX.matches(videoId)) return null

        val title = readText(obj.optJSONObject("title")).ifBlank { return null }
        val artist = readText(obj.optJSONObject("ownerText"))
            .ifBlank { readText(obj.optJSONObject("longBylineText")) }
            .ifBlank { readText(obj.optJSONObject("shortBylineText")) }
            .ifBlank { "Unknown artist" }
        val durationText = readText(obj.optJSONObject("lengthText"))
        val views = readText(obj.optJSONObject("shortViewCountText"))
            .ifBlank { readText(obj.optJSONObject("viewCountText")) }
        val published = readText(obj.optJSONObject("publishedTimeText"))
        val metaLine = listOf(views, published).filter { it.isNotBlank() }.joinToString(" · ")
        val thumb = extractBestThumbnail(obj.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" }

        return DiscoverResultItem(
            id = videoId,
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            artist = artist,
            durationText = durationText,
            durationSec = parseDurationToSeconds(durationText),
            metaLine = metaLine,
            thumbnailUrl = thumb,
            isPlaylist = false
        )
    }

    private fun parseVideoWithContextRenderer(obj: JSONObject?): DiscoverResultItem? {
        if (obj == null) return null
        val videoId = obj.optString("videoId", "")
        if (!VIDEO_ID_REGEX.matches(videoId)) return null

        val title = readText(obj.optJSONObject("headline")).ifBlank { readText(obj.optJSONObject("title")) }
        if (title.isBlank()) return null

        val artist = readText(obj.optJSONObject("shortBylineText"))
            .ifBlank { readText(obj.optJSONObject("longBylineText")) }
            .ifBlank { "Unknown artist" }
        val durationText = readText(obj.optJSONObject("lengthText"))
        val views = readText(obj.optJSONObject("shortViewCountText"))
        val published = readText(obj.optJSONObject("publishedTimeText"))
        val metaLine = listOf(views, published).filter { it.isNotBlank() }.joinToString(" · ")
        val thumb = extractBestThumbnail(obj.optJSONObject("thumbnail"))
            .ifBlank { "https://i.ytimg.com/vi/$videoId/mqdefault.jpg" }

        return DiscoverResultItem(
            id = videoId,
            url = "https://www.youtube.com/watch?v=$videoId",
            title = title,
            artist = artist,
            durationText = durationText,
            durationSec = parseDurationToSeconds(durationText),
            metaLine = metaLine,
            thumbnailUrl = thumb,
            isPlaylist = false
        )
    }

    private fun parsePlaylistRenderer(obj: JSONObject?): DiscoverResultItem? {
        if (obj == null) return null
        val playlistId = obj.optString("playlistId", "")
        if (playlistId.isBlank()) return null
        val title = readText(obj.optJSONObject("title")).ifBlank { return null }
        val artist = readText(obj.optJSONObject("shortBylineText"))
            .ifBlank { readText(obj.optJSONObject("longBylineText")) }
            .ifBlank { "YouTube Playlist" }
        val count = obj.optString("videoCount", "").toIntOrNull()
            ?: readText(obj.optJSONObject("videoCountText")).filter { it.isDigit() }.toIntOrNull()

        var thumb = extractBestThumbnail(obj.optJSONObject("thumbnail"))
        if (thumb.isBlank()) {
            val thumbsArray = obj.optJSONArray("thumbnails")
            if (thumbsArray != null && thumbsArray.length() > 0) {
                thumb = extractBestThumbnail(thumbsArray.optJSONObject(0))
            }
        }

        val countLabel = if (count != null && count > 0) "$count tracks" else "Playlist"
        return DiscoverResultItem(
            id = playlistId,
            url = "https://www.youtube.com/playlist?list=$playlistId",
            title = title,
            artist = artist,
            durationText = countLabel,
            durationSec = null,
            metaLine = countLabel,
            thumbnailUrl = thumb,
            isPlaylist = true,
            trackCount = count
        )
    }

    /**
     * Supports YouTube's newer 2025/2026 lockupViewModel format used for playlists and mixes in search.
     */
    private fun parseLockupViewModel(obj: JSONObject?): DiscoverResultItem? {
        if (obj == null) return null
        val contentType = obj.optString("contentType", "")
        if (!contentType.contains("PLAYLIST", ignoreCase = true) && !contentType.contains("MIX", ignoreCase = true)) {
            return null
        }
        val contentId = obj.optString("contentId", "").ifBlank { return null }
        val meta = obj.optJSONObject("metadata")?.optJSONObject("lockupMetadataViewModel") ?: return null
        val title = meta.optJSONObject("title")?.optString("content", "")?.trim().orEmpty()
        if (title.isBlank()) return null

        val rows = meta.optJSONObject("metadata")
            ?.optJSONObject("contentMetadataViewModel")
            ?.optJSONArray("metadataRows")
        val rowParts = mutableListOf<String>()
        if (rows != null) {
            val firstRowParts = rows.optJSONObject(0)?.optJSONArray("metadataParts")
            if (firstRowParts != null) {
                for (j in 0 until firstRowParts.length()) {
                    val text = firstRowParts.optJSONObject(j)
                        ?.optJSONObject("text")
                        ?.optString("content", "")
                        ?.trim()
                        .orEmpty()
                    if (text.isNotBlank() && text !in rowParts) rowParts.add(text)
                }
            }
        }
        val artist = rowParts.firstOrNull().orEmpty().ifBlank { "YouTube Playlist" }

        val thumb = findFirstHttpsUrl(obj.optJSONObject("contentImage"))
        val badgeText = findBadgeText(obj.optJSONObject("contentImage"))
        val count = badgeText.filter { it.isDigit() }.toIntOrNull()
        val label = badgeText.ifBlank { if (count != null) "$count tracks" else "Playlist" }

        return DiscoverResultItem(
            id = contentId,
            url = "https://www.youtube.com/playlist?list=$contentId",
            title = title,
            artist = artist,
            durationText = label,
            durationSec = null,
            metaLine = rowParts.drop(1).joinToString(" · ").ifBlank { label },
            thumbnailUrl = thumb,
            isPlaylist = true,
            trackCount = count
        )
    }

    private fun findFirstHttpsUrl(node: Any?): String {
        when (node) {
            is JSONObject -> {
                val direct = node.optString("url", "")
                if (direct.startsWith("https://")) return direct
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findFirstHttpsUrl(node.opt(keys.next()))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> {
                for (i in node.length() - 1 downTo 0) {
                    val found = findFirstHttpsUrl(node.opt(i))
                    if (found.isNotBlank()) return found
                }
            }
        }
        return ""
    }

    private fun findBadgeText(node: Any?): String {
        when (node) {
            is JSONObject -> {
                if (node.has("thumbnailBadgeViewModel")) {
                    val text = node.optJSONObject("thumbnailBadgeViewModel")?.optString("text", "").orEmpty()
                    if (text.isNotBlank()) return text
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findBadgeText(node.opt(keys.next()))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findBadgeText(node.opt(i))
                    if (found.isNotBlank()) return found
                }
            }
        }
        return ""
    }

    private fun readText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText", "")
        if (simple.isNotBlank()) return simple.trim()
        val runs = obj.optJSONArray("runs") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until runs.length()) {
            sb.append(runs.optJSONObject(i)?.optString("text", "").orEmpty())
        }
        return sb.toString().trim()
    }

    private fun extractBestThumbnail(obj: JSONObject?): String {
        val thumbs = obj?.optJSONArray("thumbnails") ?: return ""
        for (i in thumbs.length() - 1 downTo 0) {
            val url = thumbs.optJSONObject(i)?.optString("url", "").orEmpty()
            if (url.startsWith("https://")) return url
            if (url.startsWith("//")) return "https:$url"
        }
        return ""
    }

    private fun parseDurationToSeconds(text: String): Int? {
        if (text.isBlank()) return null
        val parts = text.trim().split(':').mapNotNull { it.toIntOrNull() }
        return when (parts.size) {
            2 -> parts[0] * 60 + parts[1]
            3 -> parts[0] * 3600 + parts[1] * 60 + parts[2]
            else -> null
        }
    }

    private fun skipWhitespace(s: String, start: Int): Int {
        var i = start
        while (i < s.length && s[i].isWhitespace()) i++
        return i
    }

    private fun extractBalancedJsonObject(text: String, startIndex: Int): String? {
        if (startIndex >= text.length || text[startIndex] != '{') return null
        var depth = 0
        var inString = false
        var escaped = false
        for (i in startIndex until text.length) {
            val ch = text[i]
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                }
            } else {
                when (ch) {
                    '"' -> inString = true
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            return text.substring(startIndex, i + 1)
                        }
                    }
                }
            }
        }
        return null
    }

    private fun extractJsQuotedString(text: String, quoteIndex: Int, quoteChar: Char): String? {
        val sb = StringBuilder()
        var i = quoteIndex + 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                sb.append(ch).append(text[i + 1])
                i += 2
                continue
            }
            if (ch == quoteChar) {
                val raw = sb.toString()
                return HEX_ESCAPE_REGEX.replace(raw) { match ->
                    match.groupValues[1].toInt(16).toChar().toString()
                }.replace("\\'", "'").replace("\\\\", "\\")
            }
            sb.append(ch)
            i++
        }
        return null
    }

    fun fromYtDlpEntries(entries: List<com.example.mdownload.core.PlaylistTrackItem>): List<DiscoverResultItem> {
        return entries.filter { it.available && it.url.isNotBlank() }.map { item ->
            DiscoverResultItem(
                id = item.videoId,
                url = item.url,
                title = item.title,
                artist = item.artist,
                durationText = TextUtils.formatDurationSeconds(item.durationSec).takeIf { it != "—" }.orEmpty(),
                durationSec = item.durationSec,
                metaLine = "",
                thumbnailUrl = item.thumbnailUrl,
                isPlaylist = false
            )
        }
    }
}

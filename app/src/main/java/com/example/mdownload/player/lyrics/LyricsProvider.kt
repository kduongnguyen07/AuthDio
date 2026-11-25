package com.example.mdownload.player.lyrics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

interface OnlineLyricsProvider {
    val providerName: String
    suspend fun lookup(query: LyricsQuery): Result<List<LyricsCandidate>>
    suspend fun search(freeTextQuery: String, artistHint: String = ""): Result<List<LyricsCandidate>>
}

/**
 * Public, open-source LRCLIB (`https://lrclib.net`) lyrics client.
 *
 * - Sends metadata-only queries (never uploads audio files).
 * - Uses descriptive User-Agent header per LRCLIB guidelines.
 * - Deduplicates concurrent in-flight lookups for identical queries.
 * - Fails gracefully on offline/network errors without throwing into callers.
 */
class LrcLibLyricsProvider : OnlineLyricsProvider {

    override val providerName: String = "LRCLIB"

    companion object {
        private const val BASE_URL = "https://lrclib.net/api"
        private const val USER_AGENT = "AuthDio/1.2 (Android; offline-first audio player)"
        private const val CONNECT_TIMEOUT_MS = 6_000
        private const val READ_TIMEOUT_MS = 8_000
    }

    private val inFlightLookups = ConcurrentHashMap<String, CompletableDeferred<Result<List<LyricsCandidate>>>>()

    override suspend fun lookup(query: LyricsQuery): Result<List<LyricsCandidate>> {
        if (query.title.isBlank()) return Result.success(emptyList())
        val dedupKey = "${query.title.lowercase()}|${query.artist.lowercase()}|${query.durationSec ?: 0}"

        val existing = inFlightLookups[dedupKey]
        if (existing != null) {
            return existing.await()
        }

        val deferred = CompletableDeferred<Result<List<LyricsCandidate>>>()
        val raced = inFlightLookups.putIfAbsent(dedupKey, deferred)
        if (raced != null) {
            return raced.await()
        }

        return try {
            val result = withContext(Dispatchers.IO) {
                executeLookup(query)
            }
            deferred.complete(result)
            result
        } catch (e: Exception) {
            val failure = Result.failure<List<LyricsCandidate>>(e)
            deferred.complete(failure)
            failure
        } finally {
            inFlightLookups.remove(dedupKey, deferred)
        }
    }

    override suspend fun search(freeTextQuery: String, artistHint: String): Result<List<LyricsCandidate>> {
        val trimmed = freeTextQuery.trim()
        if (trimmed.isBlank()) return Result.success(emptyList())
        return withContext(Dispatchers.IO) {
            runCatching {
                val urlStr = if (artistHint.isNotBlank()) {
                    "$BASE_URL/search?track_name=${encode(trimmed)}&artist_name=${encode(artistHint.trim())}"
                } else {
                    "$BASE_URL/search?q=${encode(trimmed)}"
                }
                val body = httpGet(urlStr) ?: return@runCatching emptyList()
                parseCandidateArray(body)
            }
        }
    }

    private fun executeLookup(query: LyricsQuery): Result<List<LyricsCandidate>> {
        val collected = LinkedHashMap<String, LyricsCandidate>()
        var lastError: Throwable? = null

        // 1. Exact /api/get when artist + duration are available (fastest & highest precision)
        if (query.artist.isNotBlank()) {
            runCatching {
                val params = buildString {
                    append("track_name=").append(encode(query.title))
                    append("&artist_name=").append(encode(query.artist))
                    if (query.album.isNotBlank()) {
                        append("&album_name=").append(encode(query.album))
                    }
                    if (query.durationSec != null && query.durationSec > 0) {
                        append("&duration=").append(query.durationSec)
                    }
                }
                val body = httpGet("$BASE_URL/get?$params")
                if (!body.isNullOrBlank()) {
                    parseSingleCandidate(JSONObject(body))?.let { cand ->
                        collected[cand.id] = cand
                    }
                }
            }.onFailure { lastError = it }
        }

        // 2. Broader /api/search for candidate ranking and Vietnamese/accent-tolerant matching
        if (collected.values.none { it.isSynced }) {
            runCatching {
                val searchUrl = if (query.artist.isNotBlank()) {
                    "$BASE_URL/search?track_name=${encode(query.title)}&artist_name=${encode(query.artist)}"
                } else {
                    "$BASE_URL/search?q=${encode(query.title)}"
                }
                val body = httpGet(searchUrl)
                if (!body.isNullOrBlank()) {
                    for (cand in parseCandidateArray(body)) {
                        collected.putIfAbsent(cand.id, cand)
                    }
                }
            }.onFailure { lastError = it }
        }

        // 3. Fallback title-only search if artist-constrained search returned nothing
        if (collected.isEmpty() && query.artist.isNotBlank()) {
            runCatching {
                val q = "${query.title} ${query.artist}".trim()
                val body = httpGet("$BASE_URL/search?q=${encode(q)}")
                if (!body.isNullOrBlank()) {
                    for (cand in parseCandidateArray(body)) {
                        collected.putIfAbsent(cand.id, cand)
                    }
                }
            }.onFailure { lastError = it }
        }

        return if (collected.isEmpty() && lastError != null) {
            Result.failure(lastError)
        } else {
            Result.success(collected.values.toList())
        }
    }

    private fun httpGet(urlString: String): String? {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "application/json")
        }
        return try {
            val code = conn.responseCode
            when (code) {
                in 200..299 -> conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                404 -> null
                else -> throw IllegalStateException("LRCLIB HTTP $code")
            }
        } finally {
            conn.disconnect()
        }
    }

    private fun parseCandidateArray(jsonText: String): List<LyricsCandidate> {
        val arr = JSONArray(jsonText)
        val result = ArrayList<LyricsCandidate>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            parseSingleCandidate(obj)?.let { result.add(it) }
        }
        return result
    }

    private fun parseSingleCandidate(obj: JSONObject): LyricsCandidate? {
        val synced = obj.optString("syncedLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val plain = obj.optString("plainLyrics", "").takeIf { it.isNotBlank() && it != "null" }
        val instrumental = obj.optBoolean("instrumental", false)
        if (synced == null && plain == null) return null

        val idVal = obj.optLong("id", 0L).takeIf { it > 0L }?.toString()
            ?: "${obj.optString("trackName")}_${obj.optString("artistName")}".hashCode().toString()
        val durationSec = obj.optDouble("duration", -1.0).takeIf { it > 0.0 }?.toInt()

        return LyricsCandidate(
            id = idVal,
            title = obj.optString("trackName", obj.optString("name", "")).trim(),
            artist = obj.optString("artistName", "").trim(),
            album = obj.optString("albumName", "").trim(),
            durationSec = durationSec,
            syncedLyrics = synced,
            plainLyrics = plain,
            instrumental = instrumental,
            providerName = providerName
        )
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
}

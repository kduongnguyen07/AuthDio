package com.example.mdownload.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.IDN
import java.net.InetAddress
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

data class SharedLinkPayload(
    val urls: List<String>,
    val titleHint: String = ""
)

/**
 * Smart, security-hardened URL parser supporting YouTube, YouTube Music, and general
 * yt-dlp-supported public HTTP(S) sources (SoundCloud, TikTok, Bandcamp, Bilibili,
 * Vimeo, Facebook, Instagram, X, Mixcloud, Twitch, etc.).
 *
 * Security hardening (SEC-04):
 * - Strictly offline lexical & structural validation in [normalizeUrl] (zero blocking DNS):
 *   blocks non-HTTP(S) schemes, embedded credentials (`user:pass@`), backslashes, control chars,
 *   malformed `%` encodings, invalid ports, trailing-dot/single-label/local hostnames,
 *   alternative IPv4 encodings (integer/hex/octal/shortened dotted), private/loopback/link-local/
 *   CGNAT/multicast/reserved IPv4, and loopback/unspecified/ULA/link-local/multicast/zone-scoped/
 *   IPv4-mapped private IPv6 literals, with IDN/Punycode normalization.
 * - Optional off-main-thread pre-flight DNS validation in [validateResolvedHost] blocks
 *   hostnames whose DNS answers resolve to any disallowed IPv4/IPv6 address.
 * - Security boundary: pre-flight DNS checks reduce local/private network targeting, but cannot
 *   by themselves prevent TOCTOU DNS rebinding or HTTP 3xx redirects executed inside the external
 *   `yt-dlp` subprocess.
 */
object YouTubeUrlParser {

    private val YOUTUBE_HOSTS = setOf(
        "youtube.com",
        "www.youtube.com",
        "m.youtube.com",
        "music.youtube.com",
        "youtu.be",
        "www.youtu.be"
    )

    private val DISALLOWED_HOST_EXACT = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback",
        "broadcasthost",
        "metadata",
        "metadata.google.internal",
        "metadata.goog"
    )

    private val DISALLOWED_HOST_SUFFIXES = listOf(
        ".localhost",
        ".local",
        ".localdomain",
        ".internal",
        ".arpa",
        ".home.arpa",
        ".lan",
        ".corp",
        ".intranet"
    )

    private val VIDEO_ID_REGEX = Regex("^[A-Za-z0-9_-]{1,64}$")
    private val URL_EXTRACT_REGEX = Regex("""https?://[^\s<>"']+""", RegexOption.IGNORE_CASE)
    private val BARE_KNOWN_URL_REGEX = Regex(
        """(?<![@\w/])(?:www\.|m\.|music\.|on\.|vt\.|vm\.)?(?:youtube\.com|youtu\.be|soundcloud\.com|tiktok\.com|bandcamp\.com|bilibili\.com|b23\.tv|vimeo\.com|fb\.watch|facebook\.com|instagram\.com|x\.com|twitter\.com|mixcloud\.com|twitch\.tv|dailymotion\.com|dai\.ly)/[^\s<>"']+""",
        RegexOption.IGNORE_CASE
    )
    private val CANONICAL_IPV4_REGEX = Regex("""^(?:0|[1-9]\d{0,2})(?:\.(?:0|[1-9]\d{0,2})){3}$""")
    private val NON_CANONICAL_NUMERIC_HOST_REGEX = Regex(
        """^(?:0x[0-9a-f]+|\d+)(?:\.(?:0x[0-9a-f]+|\d+)){0,3}$""",
        RegexOption.IGNORE_CASE
    )
    private val VALID_DOMAIN_LABEL_REGEX = Regex("^[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$")

    fun extractUrlsFromText(text: String): List<String> {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val result = mutableListOf<String>()
        for (line in lines) {
            val httpMatches = URL_EXTRACT_REGEX.findAll(line)
                .map { cleanExtractedUrl(it.value) }
                .filter { it.isNotBlank() }
                .toList()
            if (httpMatches.isNotEmpty()) {
                result.addAll(httpMatches)
                continue
            }
            val bareMatches = BARE_KNOWN_URL_REGEX.findAll(line)
                .map { "https://${cleanExtractedUrl(it.value)}" }
                .filter { it.isNotBlank() }
                .toList()
            if (bareMatches.isNotEmpty()) {
                result.addAll(bareMatches)
            } else {
                result.add(line)
            }
        }
        return result.distinct()
    }

    /**
     * Extracts supported media URLs plus an instant title hint from an Android Share intent's
     * EXTRA_TEXT and EXTRA_SUBJECT fields.
     */
    fun extractSharePayload(sharedText: String, subject: String? = null): SharedLinkPayload {
        val extracted = extractUrlsFromText(sharedText)
        val validUrls = extracted.mapNotNull { runCatching { normalizeUrl(it) }.getOrNull() }.distinct()

        val subjectHint = cleanShareTitleHint(subject.orEmpty())
        val textHint = if (subjectHint.isNotBlank()) {
            subjectHint
        } else {
            val withoutUrls = URL_EXTRACT_REGEX.replace(sharedText, " ")
                .let { BARE_KNOWN_URL_REGEX.replace(it, " ") }
            cleanShareTitleHint(withoutUrls)
        }
        return SharedLinkPayload(urls = validUrls, titleHint = textHint)
    }

    fun isYouTubeHost(host: String): Boolean = host.lowercase(Locale.US) in YOUTUBE_HOSTS

    fun isYouTubeUrl(raw: String): Boolean {
        val normalized = runCatching { normalizeUrl(raw) }.getOrNull() ?: return false
        val host = runCatching { URI(normalized).host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        return isYouTubeHost(host)
    }

    fun sourceName(raw: String): String {
        val normalized = runCatching { normalizeUrl(raw) }.getOrNull() ?: return "Web"
        val host = runCatching { URI(normalized).host?.lowercase(Locale.US).orEmpty() }.getOrDefault("")
        return when {
            host == "music.youtube.com" -> "YouTube Music"
            isYouTubeHost(host) -> "YouTube"
            host == "soundcloud.com" || host.endsWith(".soundcloud.com") -> "SoundCloud"
            host == "tiktok.com" || host.endsWith(".tiktok.com") -> "TikTok"
            host == "bandcamp.com" || host.endsWith(".bandcamp.com") -> "Bandcamp"
            host == "bilibili.com" || host.endsWith(".bilibili.com") || host == "b23.tv" -> "Bilibili"
            host == "vimeo.com" || host.endsWith(".vimeo.com") -> "Vimeo"
            host == "facebook.com" || host.endsWith(".facebook.com") || host == "fb.watch" -> "Facebook"
            host == "instagram.com" || host.endsWith(".instagram.com") -> "Instagram"
            host == "x.com" || host.endsWith(".x.com") || host == "twitter.com" || host.endsWith(".twitter.com") -> "X"
            host == "mixcloud.com" || host.endsWith(".mixcloud.com") -> "Mixcloud"
            host == "twitch.tv" || host.endsWith(".twitch.tv") -> "Twitch"
            host == "dailymotion.com" || host.endsWith(".dailymotion.com") || host == "dai.ly" -> "Dailymotion"
            else -> host.removePrefix("www.").removePrefix("m.").ifBlank { "Web" }
        }
    }

    fun normalizeUrl(raw: String): String {
        val trimmed = cleanExtractedUrl(raw.trim())
        if (trimmed.isEmpty() || containsControlCharsOrWhitespace(trimmed) || trimmed.contains('\\')) {
            throw IllegalArgumentException("Use a full YouTube, YouTube Music, or supported media URL (https://…).")
        }
        if (hasMalformedPercentEncoding(trimmed)) {
            throw IllegalArgumentException("Malformed percent-encoding in URL.")
        }

        val withScheme = if (
            !trimmed.contains("://") &&
            BARE_KNOWN_URL_REGEX.matches(trimmed)
        ) {
            "https://$trimmed"
        } else {
            trimmed
        }

        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            throw IllegalArgumentException("Use a full YouTube, YouTube Music, or supported media URL (https://…).")
        }

        val scheme = uri.scheme?.lowercase(Locale.US)
        val rawAuthority = uri.rawAuthority
        if (scheme == null || scheme !in setOf("http", "https") || rawAuthority.isNullOrBlank()) {
            throw IllegalArgumentException("Use a full YouTube, YouTube Music, or supported media URL (https://…).")
        }
        if (uri.userInfo != null || rawAuthority.contains('@')) {
            throw IllegalArgumentException("URLs with embedded credentials are not supported.")
        }

        val (rawHost, port) = splitHostAndPort(rawAuthority)
            ?: throw IllegalArgumentException("Use a full YouTube, YouTube Music, or supported media URL (https://…).")

        val normalizedHost = normalizeAndValidateHost(rawHost)
        val normalizedAuthority = if (port != null) {
            val defaultPort = if (scheme == "https") 443 else 80
            if (port == defaultPort) normalizedHost else "$normalizedHost:$port"
        } else {
            normalizedHost
        }

        val path = uri.rawPath ?: ""
        val query = parseQuery(uri.rawQuery)

        if (isYouTubeHost(normalizedHost)) {
            val valid = (normalizedHost.endsWith("youtu.be") && path.trim('/').isNotEmpty()) ||
                (path == "/watch" && !query["v"].isNullOrBlank()) ||
                (path == "/playlist" && !query["list"].isNullOrBlank()) ||
                ((path.startsWith("/shorts/") || path.startsWith("/live/")) &&
                    path.split('/').getOrNull(2)?.isNotBlank() == true)

            if (!valid) {
                throw IllegalArgumentException("Use a video, Shorts, live-video, or playlist link rather than a channel URL.")
            }

            return buildNormalizedUrlString(
                scheme = "https",
                authority = if (port != null && port != 443 && port != 80) "$normalizedHost:$port" else normalizedHost,
                rawPath = path,
                rawQuery = uri.rawQuery
            )
        }

        val hasTargetPath = path.trim('/').isNotEmpty() || !uri.rawQuery.isNullOrBlank()
        if (!hasTargetPath) {
            throw IllegalArgumentException("Use a direct track, video, or playlist link rather than a homepage URL.")
        }

        return buildNormalizedUrlString(
            scheme = scheme,
            authority = normalizedAuthority,
            rawPath = path,
            rawQuery = uri.rawQuery
        )
    }

    /**
     * Off-main-thread pre-flight DNS validation (SEC-04).
     * Resolves the URL's host and rejects the URL if any returned address falls inside
     * a disallowed local/private/link-local/CGNAT/ULA/multicast/reserved range.
     *
     * Note: This reduces static/split-horizon SSRF targeting, but cannot alone prevent
     * TOCTOU DNS rebinding or HTTP 3xx redirects executed inside `yt-dlp`.
     */
    suspend fun validateResolvedHost(
        rawOrNormalizedUrl: String,
        resolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) }
    ): String = withContext(Dispatchers.IO) {
        val normalized = normalizeUrl(rawOrNormalizedUrl)
        val uri = URI(normalized)
        val rawHost = splitHostAndPort(uri.rawAuthority.orEmpty())?.first
            ?: throw IllegalArgumentException("Invalid media URL host.")
        val bareHost = rawHost.removePrefix("[").removeSuffix("]")

        // Literal IPv4/IPv6 addresses were already validated offline in normalizeUrl();
        // re-check via byte array without touching DNS.
        parseCanonicalIpv4Bytes(bareHost)?.let { bytes ->
            if (isDisallowedIpv4(bytes)) {
                throw IllegalArgumentException("Local or private network URLs are not supported.")
            }
            return@withContext normalized
        }
        if (rawHost.startsWith("[")) {
            val bytes = parseIpv6Bytes(bareHost)
                ?: throw IllegalArgumentException("Local or private network URLs are not supported.")
            if (isDisallowedIpv6(bytes)) {
                throw IllegalArgumentException("Local or private network URLs are not supported.")
            }
            return@withContext normalized
        }

        val resolved = try {
            resolver(bareHost)
        } catch (_: Exception) {
            // Let yt-dlp surface normal connectivity/DNS errors when offline or transiently unreachable.
            return@withContext normalized
        }
        if (resolved.any { isDisallowedAddress(it) }) {
            throw IllegalArgumentException("Local or private network URLs are not supported.")
        }
        normalized
    }

    /**
     * Pure address-policy check shared by offline IP literal checks and pre-flight DNS validation.
     */
    fun isDisallowedAddress(address: InetAddress): Boolean {
        val bytes = address.address ?: return true
        return when (bytes.size) {
            4 -> isDisallowedIpv4(bytes)
            16 -> isDisallowedIpv6(bytes)
            else -> true
        }
    }

    fun isValidUrl(raw: String): Boolean = runCatching { normalizeUrl(raw) }.isSuccess

    fun isPlaylist(raw: String): Boolean {
        val normalized = runCatching { normalizeUrl(raw) }.getOrNull() ?: return false
        val uri = URI(normalized)
        val host = uri.host?.lowercase(Locale.US) ?: ""
        if (isYouTubeHost(host)) {
            val listId = parseQuery(uri.rawQuery)["list"]
            return !listId.isNullOrBlank()
        }
        val path = (uri.rawPath ?: "").lowercase(Locale.US)
        return (host.endsWith("soundcloud.com") && path.contains("/sets/")) ||
            (host.endsWith("bandcamp.com") && path.startsWith("/album/")) ||
            (host.endsWith("bilibili.com") && path.contains("/lists/"))
    }

    fun isMixPlaylist(raw: String): Boolean {
        val normalized = runCatching { normalizeUrl(raw) }.getOrNull() ?: return false
        val uri = URI(normalized)
        if (!isYouTubeHost(uri.host.orEmpty())) return false
        val listId = parseQuery(uri.rawQuery)["list"] ?: return false
        return listId.startsWith("RD")
    }

    fun extractVideoId(raw: String): String? {
        val normalized = runCatching { normalizeUrl(raw) }.getOrNull() ?: return null
        val uri = URI(normalized)
        val host = uri.host?.lowercase(Locale.US) ?: ""
        if (!isYouTubeHost(host)) return null
        val path = uri.rawPath ?: ""
        val query = parseQuery(uri.rawQuery)
        val id = when {
            host == "youtu.be" || host == "www.youtu.be" ->
                path.trim('/').split('/').firstOrNull()
            path.startsWith("/shorts/") || path.startsWith("/live/") ->
                path.split('/').getOrNull(2)
            else -> query["v"]
        }
        return id?.takeIf { VIDEO_ID_REGEX.matches(it) }
    }

    fun singleVideoUrl(raw: String): String? {
        val normalized = runCatching { normalizeUrl(raw) }.getOrNull() ?: return null
        if (isYouTubeUrl(normalized)) {
            val videoId = extractVideoId(normalized) ?: return null
            return "https://www.youtube.com/watch?v=${encode(videoId)}"
        }
        return if (isPlaylist(normalized)) null else normalized
    }

    fun playlistUrl(raw: String): String {
        val normalized = normalizeUrl(raw)
        val uri = URI(normalized)
        if (!isYouTubeHost(uri.host.orEmpty())) {
            return normalized
        }
        val listId = parseQuery(uri.rawQuery)["list"]
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("This link does not contain a playlist.")
        val video = singleVideoUrl(normalized)
        return if (listId.startsWith("RD") && video != null) {
            "$video&list=${encode(listId)}"
        } else {
            "https://www.youtube.com/playlist?list=${encode(listId)}"
        }
    }

    fun youtubeSearchUrl(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return "https://m.youtube.com/"
        return runCatching { normalizeUrl(trimmed) }.getOrElse {
            "https://m.youtube.com/results?search_query=${encode(trimmed)}"
        }
    }

    // -------------------------------------------------------------------------
    // Offline host / IPv4 / IPv6 normalization and validation
    // -------------------------------------------------------------------------

    private fun containsControlCharsOrWhitespace(s: String): Boolean =
        s.any { it.code <= 0x20 || it.code == 0x7F }

    private fun hasMalformedPercentEncoding(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            if (s[i] == '%') {
                if (i + 2 >= s.length || !isHexDigit(s[i + 1]) || !isHexDigit(s[i + 2])) {
                    return true
                }
                i += 3
            } else {
                i++
            }
        }
        return false
    }

    private fun isHexDigit(c: Char): Boolean =
        c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

    private fun splitHostAndPort(rawAuthority: String): Pair<String, Int?>? {
        if (rawAuthority.isEmpty() || rawAuthority.contains('@')) return null
        return if (rawAuthority.startsWith("[")) {
            val closeIdx = rawAuthority.indexOf(']')
            if (closeIdx <= 1) return null
            val hostPart = rawAuthority.substring(0, closeIdx + 1)
            val rest = rawAuthority.substring(closeIdx + 1)
            when {
                rest.isEmpty() -> hostPart to null
                rest.startsWith(":") -> {
                    val portStr = rest.substring(1)
                    if (portStr.isEmpty() || !portStr.all { it.isDigit() }) return null
                    val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                    hostPart to port
                }
                else -> null
            }
        } else {
            val colonIdx = rawAuthority.indexOf(':')
            if (colonIdx < 0) {
                rawAuthority to null
            } else {
                // Multiple colons outside brackets is invalid
                if (rawAuthority.indexOf(':', colonIdx + 1) >= 0) return null
                val hostPart = rawAuthority.substring(0, colonIdx)
                val portStr = rawAuthority.substring(colonIdx + 1)
                if (hostPart.isEmpty() || portStr.isEmpty() || !portStr.all { it.isDigit() }) return null
                val port = portStr.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
                hostPart to port
            }
        }
    }

    private fun normalizeAndValidateHost(rawHost: String): String {
        if (rawHost.isBlank()) {
            throw IllegalArgumentException("Enter a valid public media link (https://…).")
        }
        // Reject percent-encoded hostnames (e.g. %31%32%37.0.0.1 or zone IDs %25eth0)
        if (rawHost.contains('%')) {
            throw IllegalArgumentException("Local or private network URLs are not supported.")
        }

        // 1. Bracketed IPv6 literal
        if (rawHost.startsWith("[")) {
            if (!rawHost.endsWith("]")) {
                throw IllegalArgumentException("Enter a valid public media link (https://…).")
            }
            val bareV6 = rawHost.substring(1, rawHost.length - 1).lowercase(Locale.US)
            val bytes = parseIpv6Bytes(bareV6)
                ?: throw IllegalArgumentException("Enter a valid public media link (https://…).")
            if (isDisallowedIpv6(bytes)) {
                throw IllegalArgumentException("Local or private network URLs are not supported.")
            }
            return "[$bareV6]"
        }

        // 2. Reject trailing/leading/empty dots and check stripped form for disallowed names
        val loweredRaw = rawHost.lowercase(Locale.US)
        if (loweredRaw.startsWith('.') || loweredRaw.endsWith('.') || loweredRaw.contains("..")) {
            val stripped = loweredRaw.trim('.')
            if (stripped in DISALLOWED_HOST_EXACT || DISALLOWED_HOST_SUFFIXES.any { stripped.endsWith(it) }) {
                throw IllegalArgumentException("Local or private network URLs are not supported.")
            }
            throw IllegalArgumentException("Enter a valid public media link (https://…).")
        }

        // 3. IDN / Punycode normalization (offline, RFC 3490)
        val asciiHost = try {
            IDN.toASCII(loweredRaw, IDN.ALLOW_UNASSIGNED).lowercase(Locale.US)
        } catch (_: Exception) {
            throw IllegalArgumentException("Enter a valid public media link (https://…).")
        }
        if (asciiHost.isBlank() || asciiHost.startsWith('.') || asciiHost.endsWith('.') || asciiHost.contains("..")) {
            throw IllegalArgumentException("Enter a valid public media link (https://…).")
        }

        // 4. Reject disallowed local/internal hostnames
        if (asciiHost in DISALLOWED_HOST_EXACT || DISALLOWED_HOST_SUFFIXES.any { asciiHost.endsWith(it) }) {
            throw IllegalArgumentException("Local or private network URLs are not supported.")
        }

        // 5. Canonical IPv4 literal check
        parseCanonicalIpv4Bytes(asciiHost)?.let { bytes ->
            if (isDisallowedIpv4(bytes)) {
                throw IllegalArgumentException("Local or private network URLs are not supported.")
            }
            return asciiHost
        }

        // 6. Reject ALL alternative/obfuscated numeric IPv4 notations:
        // integer (2130706433), hex (0x7f000001), octal (0177.0.0.1), shortened (127.1, 127.0.1)
        if (NON_CANONICAL_NUMERIC_HOST_REGEX.matches(asciiHost) || looksLikeObfuscatedIpv4(asciiHost)) {
            throw IllegalArgumentException("Local or private network URLs are not supported.")
        }

        // 7. Standard public domain validation (must have >= 2 valid DNS labels and non-numeric TLD)
        val labels = asciiHost.split('.')
        if (labels.size < 2 || labels.any { !VALID_DOMAIN_LABEL_REGEX.matches(it) }) {
            throw IllegalArgumentException("Enter a valid public media link (https://…).")
        }
        val tld = labels.last()
        if (tld.all { it.isDigit() }) {
            throw IllegalArgumentException("Local or private network URLs are not supported.")
        }

        return asciiHost
    }

    private fun looksLikeObfuscatedIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size !in 1..4) return false
        return parts.all { part ->
            part.isNotEmpty() && (
                part.all { it.isDigit() } ||
                    (part.startsWith("0x", ignoreCase = true) && part.length > 2 && part.drop(2).all { isHexDigit(it) })
                )
        }
    }

    internal fun parseCanonicalIpv4Bytes(host: String): ByteArray? {
        if (!CANONICAL_IPV4_REGEX.matches(host)) return null
        val parts = host.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0 until 4) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            bytes[i] = value.toByte()
        }
        return bytes
    }

    internal fun isDisallowedIpv4(bytes: ByteArray): Boolean {
        if (bytes.size != 4) return true
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF
        val c = bytes[2].toInt() and 0xFF
        return when {
            a == 0 -> true                                  // 0.0.0.0/8 ("this network")
            a == 10 -> true                                 // 10.0.0.0/8 (RFC 1918)
            a == 100 && b in 64..127 -> true                // 100.64.0.0/10 (RFC 6598 CGNAT)
            a == 127 -> true                                // 127.0.0.0/8 (loopback)
            a == 169 && b == 254 -> true                    // 169.254.0.0/16 (link-local / cloud metadata)
            a == 172 && b in 16..31 -> true                 // 172.16.0.0/12 (RFC 1918)
            a == 192 && b == 0 && c == 0 -> true            // 192.0.0.0/24 (IETF protocol)
            a == 192 && b == 0 && c == 2 -> true            // 192.0.2.0/24 (TEST-NET-1)
            a == 192 && b == 168 -> true                    // 192.168.0.0/16 (RFC 1918)
            a == 198 && (b == 18 || b == 19) -> true        // 198.18.0.0/15 (benchmarking)
            a == 198 && b == 51 && c == 100 -> true         // 198.51.100.0/24 (TEST-NET-2)
            a == 203 && b == 0 && c == 113 -> true          // 203.0.113.0/24 (TEST-NET-3)
            a >= 224 -> true                                // 224.0.0.0/4 multicast + 240.0.0.0/4 reserved + broadcast
            else -> false
        }
    }

    /**
     * Pure offline RFC 4291 IPv6 literal parser (supporting `::` compression and embedded IPv4 tail).
     * Never triggers DNS lookups.
     */
    internal fun parseIpv6Bytes(literal: String): ByteArray? {
        if (literal.isEmpty() || literal.contains('%')) return null
        val doubleColonIdx = literal.indexOf("::")
        if (doubleColonIdx >= 0 && literal.indexOf("::", doubleColonIdx + 2) >= 0) return null

        val headStr: String
        val tailStr: String
        if (doubleColonIdx >= 0) {
            headStr = literal.substring(0, doubleColonIdx)
            tailStr = literal.substring(doubleColonIdx + 2)
        } else {
            headStr = literal
            tailStr = ""
        }

        val headGroups = mutableListOf<Int>()
        val tailGroups = mutableListOf<Int>()

        if (!parseIpv6Groups(headStr, allowEmbeddedIpv4 = doubleColonIdx < 0, out = headGroups)) return null
        if (doubleColonIdx >= 0 && !parseIpv6Groups(tailStr, allowEmbeddedIpv4 = true, out = tailGroups)) return null

        val totalGroups = headGroups.size + tailGroups.size
        if (doubleColonIdx < 0) {
            if (totalGroups != 8) return null
        } else {
            if (totalGroups > 7) return null
        }

        val groups = IntArray(8)
        for (i in headGroups.indices) {
            groups[i] = headGroups[i]
        }
        val tailStart = 8 - tailGroups.size
        for (i in tailGroups.indices) {
            groups[tailStart + i] = tailGroups[i]
        }

        val bytes = ByteArray(16)
        for (i in 0 until 8) {
            bytes[i * 2] = ((groups[i] ushr 8) and 0xFF).toByte()
            bytes[i * 2 + 1] = (groups[i] and 0xFF).toByte()
        }
        return bytes
    }

    private fun parseIpv6Groups(part: String, allowEmbeddedIpv4: Boolean, out: MutableList<Int>): Boolean {
        if (part.isEmpty()) return true
        val tokens = part.split(':')
        for (idx in tokens.indices) {
            val tok = tokens[idx]
            if (tok.isEmpty()) return false
            if (tok.contains('.')) {
                if (!allowEmbeddedIpv4 || idx != tokens.lastIndex) return false
                val v4 = parseCanonicalIpv4Bytes(tok) ?: return false
                val hi = ((v4[0].toInt() and 0xFF) shl 8) or (v4[1].toInt() and 0xFF)
                val lo = ((v4[2].toInt() and 0xFF) shl 8) or (v4[3].toInt() and 0xFF)
                out.add(hi)
                out.add(lo)
            } else {
                if (tok.length > 4 || !tok.all { isHexDigit(it) }) return false
                val group = tok.toIntOrNull(16) ?: return false
                if (group !in 0..0xFFFF) return false
                out.add(group)
            }
        }
        return true
    }

    internal fun isDisallowedIpv6(bytes: ByteArray): Boolean {
        if (bytes.size != 16) return true
        val b0 = bytes[0].toInt() and 0xFF
        val b1 = bytes[1].toInt() and 0xFF

        // Unspecified (::) or Loopback (::1)
        val first15Zero = (0 until 15).all { bytes[it].toInt() == 0 }
        if (first15Zero && (bytes[15].toInt() == 0 || bytes[15].toInt() == 1)) return true

        // IPv4-mapped IPv6 (::ffff:a.b.c.d) -> check embedded IPv4
        val isIpv4Mapped = (0 until 10).all { bytes[it].toInt() == 0 } &&
            (bytes[10].toInt() and 0xFF) == 0xFF &&
            (bytes[11].toInt() and 0xFF) == 0xFF
        if (isIpv4Mapped) {
            return isDisallowedIpv4(bytes.copyOfRange(12, 16))
        }

        // IPv4-translated / SIIT (::ffff:0:a.b.c.d) -> check embedded IPv4
        val isIpv4Translated = (0 until 8).all { bytes[it].toInt() == 0 } &&
            (bytes[8].toInt() and 0xFF) == 0xFF &&
            (bytes[9].toInt() and 0xFF) == 0xFF &&
            bytes[10].toInt() == 0 && bytes[11].toInt() == 0
        if (isIpv4Translated) {
            return isDisallowedIpv4(bytes.copyOfRange(12, 16))
        }

        // Deprecated IPv4-compatible IPv6 (::a.b.c.d) -> block
        val first12Zero = (0 until 12).all { bytes[it].toInt() == 0 }
        if (first12Zero) return true

        // NAT64 well-known prefix (64:ff9b::/96) -> check embedded IPv4
        val isNat64 = b0 == 0x00 && b1 == 0x64 &&
            (bytes[2].toInt() and 0xFF) == 0xFF && (bytes[3].toInt() and 0xFF) == 0x9B &&
            (4 until 12).all { bytes[it].toInt() == 0 }
        if (isNat64) {
            return isDisallowedIpv4(bytes.copyOfRange(12, 16))
        }

        // 6to4 (2002::/16) -> check embedded IPv4 in bytes 2..5
        if (b0 == 0x20 && b1 == 0x02) {
            return isDisallowedIpv4(bytes.copyOfRange(2, 6))
        }

        // Unique Local Addresses (fc00::/7 -> fc00..fdff)
        if ((b0 and 0xFE) == 0xFC) return true

        // Link-local unicast (fe80::/10) and deprecated site-local (fec0::/10)
        if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return true
        if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return true

        // Multicast (ff00::/8)
        if (b0 == 0xFF) return true

        // Documentation prefix (2001:db8::/32)
        if (b0 == 0x20 && b1 == 0x01 && (bytes[2].toInt() and 0xFF) == 0x0D && (bytes[3].toInt() and 0xFF) == 0xB8) {
            return true
        }

        // Discard-only prefix (100::/64)
        if (b0 == 0x01 && b1 == 0x00 && (2 until 8).all { bytes[it].toInt() == 0 }) return true

        return false
    }

    private fun buildNormalizedUrlString(
        scheme: String,
        authority: String,
        rawPath: String,
        rawQuery: String?
    ): String {
        val sb = StringBuilder()
        sb.append(scheme).append("://").append(authority)
        if (rawPath.isNotEmpty()) {
            if (!rawPath.startsWith("/")) sb.append('/')
            sb.append(rawPath)
        }
        if (!rawQuery.isNullOrEmpty()) {
            sb.append('?').append(rawQuery)
        }
        return URI(sb.toString()).toASCIIString()
    }

    private fun cleanExtractedUrl(raw: String): String =
        raw.trim().trimStart('<', '(', '[', '"', '\'').trimEnd('.', ',', ';', ')', ']', '>', '"', '\'', '!')

    private fun cleanShareTitleHint(raw: String): String {
        val collapsed = raw.replace(Regex("""\s+"""), " ")
            .trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .removeSuffix(" - YouTube")
            .removeSuffix(" - YouTube Music")
            .removePrefix("Watch ")
            .removePrefix("Listen to ")
            .trim(' ', '-', '|', ':', '"', '\'')
        return if (collapsed.length in 2..160 && !collapsed.startsWith("http", ignoreCase = true)) {
            collapsed
        } else {
            ""
        }
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        val map = LinkedHashMap<String, String>()
        for (pair in rawQuery.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            val key = decode(if (idx >= 0) pair.substring(0, idx) else pair)
            val value = if (idx >= 0) decode(pair.substring(idx + 1)) else ""
            if (key.isNotEmpty() && !map.containsKey(key)) {
                map[key] = value
            }
        }
        return map
    }

    private fun decode(value: String): String =
        runCatching { URLDecoder.decode(value, StandardCharsets.UTF_8.name()) }.getOrDefault(value)

    private fun encode(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}

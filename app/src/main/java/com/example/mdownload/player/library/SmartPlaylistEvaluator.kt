package com.example.mdownload.player.library

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.audio.AudioInspectorEngine
import com.example.mdownload.core.audio.AudioQualityInfo
import java.util.Locale

data class SmartPlaylistEvalContext(
    val statsByTrackId: Map<String, ListeningStats> = emptyMap(),
    val favoriteUris: Set<String> = emptySet(),
    val analysisByUri: Map<String, AudioQualityInfo> = emptyMap(),
    val nowMs: Long = System.currentTimeMillis()
) {
    /**
     * Reusable O(1) secondary lookup index (`lastKnownUri -> ListeningStats`) built lazily once
     * per context snapshot and shared across all Smart Playlist evaluations.
     * Preserves exact first-match semantics of scanning `statsByTrackId.values`.
     */
    val statsByUri: Map<String, ListeningStats> by lazy(LazyThreadSafetyMode.PUBLICATION) {
        if (statsByTrackId.isEmpty()) {
            emptyMap()
        } else {
            val map = HashMap<String, ListeningStats>((statsByTrackId.size * 4) / 3 + 1)
            for (entry in statsByTrackId.values) {
                val uri = entry.lastKnownUri
                if (uri.isNotEmpty() && !map.containsKey(uri)) {
                    map[uri] = entry
                }
            }
            map
        }
    }

    fun resolveStats(track: LibraryTrack): ListeningStats? {
        if (statsByTrackId.isEmpty()) return null
        return statsByTrackId[track.stableId]
            ?: statsByTrackId[track.uri]
            ?: statsByUri[track.uri]
    }
}

/**
 * Pure, typed, offline-first Smart Playlist rule evaluator and sorter.
 *
 * Performance architecture:
 * - O(1) stats lookup via [SmartPlaylistEvalContext.statsByUri] (eliminates O(N log N * M) scans).
 * - Pre-compiles rule constants (trimmed strings, cutoff timestamps) once per evaluation.
 * - Uses Schwartzian transform (decorate-sort-undecorate) so comparator sort keys (stats, bitrate,
 *   lossless classification, lowercased titles/artists) are computed at most once per matched track
 *   in O(K) instead of O(K log K) comparator invocations.
 */
object SmartPlaylistEvaluator {

    private const val DAY_MS = 86_400_000L

    private class CompiledRule(
        val type: SmartRuleType,
        val trimmedTarget: String,
        val intValue: Int,
        val boolValue: Boolean,
        val cutoffLong: Long
    )

    private fun compileRule(rule: PlaylistRule, nowMs: Long): CompiledRule {
        val trimmed = rule.stringValue.trim()
        val cutoff = when (rule.type) {
            SmartRuleType.DATE_ADDED_WITHIN_DAYS -> {
                val days = rule.intValue.coerceAtLeast(1)
                (nowMs - days * DAY_MS) / 1000L
            }
            SmartRuleType.LAST_PLAYED_WITHIN_DAYS -> {
                val days = rule.intValue.coerceAtLeast(1)
                nowMs - days * DAY_MS
            }
            SmartRuleType.DURATION_AT_LEAST_SEC,
            SmartRuleType.DURATION_AT_MOST_SEC -> rule.intValue.coerceAtLeast(0) * 1000L
            else -> 0L
        }
        return CompiledRule(
            type = rule.type,
            trimmedTarget = trimmed,
            intValue = rule.intValue,
            boolValue = rule.boolValue,
            cutoffLong = cutoff
        )
    }

    fun evaluateAll(
        definitions: List<SmartPlaylistDefinition>,
        tracks: List<LibraryTrack>,
        ctx: SmartPlaylistEvalContext
    ): Map<String, List<LibraryTrack>> {
        if (definitions.isEmpty()) return emptyMap()
        if (tracks.isEmpty()) return definitions.associate { it.id to emptyList() }
        // Warm shared statsByUri index once if any stats exist
        ctx.statsByUri
        val result = LinkedHashMap<String, List<LibraryTrack>>(definitions.size)
        for (def in definitions) {
            result[def.id] = evaluate(def, tracks, ctx)
        }
        return result
    }

    fun evaluate(
        definition: SmartPlaylistDefinition,
        tracks: List<LibraryTrack>,
        ctx: SmartPlaylistEvalContext
    ): List<LibraryTrack> {
        if (tracks.isEmpty()) return emptyList()

        val matched: List<LibraryTrack> = if (definition.rules.isEmpty()) {
            tracks
        } else {
            val compiled = Array(definition.rules.size) { i ->
                compileRule(definition.rules[i], ctx.nowMs)
            }
            val matchAll = definition.matchMode == MatchMode.ALL
            val out = ArrayList<LibraryTrack>(tracks.size.coerceAtMost(256))
            for (i in tracks.indices) {
                val track = tracks[i]
                var statsResolved = false
                var cachedStats: ListeningStats? = null
                fun getStats(): ListeningStats? {
                    if (!statsResolved) {
                        cachedStats = ctx.resolveStats(track)
                        statsResolved = true
                    }
                    return cachedStats
                }

                var passed = matchAll
                for (rIdx in compiled.indices) {
                    val m = matchesCompiledRule(compiled[rIdx], track, ctx, ::getStats)
                    if (matchAll) {
                        if (!m) {
                            passed = false
                            break
                        }
                    } else {
                        if (m) {
                            passed = true
                            break
                        }
                    }
                }
                if (passed) out.add(track)
            }
            out
        }

        if (matched.isEmpty()) return emptyList()

        val sorted = sortMatched(definition.sortOrder, matched, ctx)

        val lim = definition.limit
        return if (lim != null && lim > 0 && sorted.size > lim) sorted.subList(0, lim).toList() else sorted
    }

    fun matchesRule(
        rule: PlaylistRule,
        track: LibraryTrack,
        ctx: SmartPlaylistEvalContext
    ): Boolean {
        val compiled = compileRule(rule, ctx.nowMs)
        return matchesCompiledRule(compiled, track, ctx) { ctx.resolveStats(track) }
    }

    private inline fun matchesCompiledRule(
        rule: CompiledRule,
        track: LibraryTrack,
        ctx: SmartPlaylistEvalContext,
        statsProvider: () -> ListeningStats?
    ): Boolean = when (rule.type) {
        SmartRuleType.ARTIST_EQUALS ->
            rule.trimmedTarget.isNotEmpty() && track.artist.trim().equals(rule.trimmedTarget, ignoreCase = true)
        SmartRuleType.ARTIST_CONTAINS ->
            rule.trimmedTarget.isNotEmpty() && track.artist.contains(rule.trimmedTarget, ignoreCase = true)
        SmartRuleType.ALBUM_EQUALS ->
            rule.trimmedTarget.isNotEmpty() && track.album.trim().equals(rule.trimmedTarget, ignoreCase = true)
        SmartRuleType.GENRE_EQUALS ->
            rule.trimmedTarget.isNotEmpty() && track.genre.trim().equals(rule.trimmedTarget, ignoreCase = true)
        SmartRuleType.DATE_ADDED_WITHIN_DAYS ->
            track.dateAddedSec >= rule.cutoffLong
        SmartRuleType.PLAY_COUNT_AT_LEAST ->
            (statsProvider()?.playCount ?: 0) >= rule.intValue.coerceAtLeast(0)
        SmartRuleType.PLAY_COUNT_AT_MOST ->
            (statsProvider()?.playCount ?: 0) <= rule.intValue.coerceAtLeast(0)
        SmartRuleType.LAST_PLAYED_WITHIN_DAYS -> {
            val lastPlayedAt = statsProvider()?.lastPlayedAt
            lastPlayedAt != null && lastPlayedAt >= rule.cutoffLong
        }
        SmartRuleType.HAS_BEEN_PLAYED -> {
            val stats = statsProvider()
            val played = stats?.lastPlayedAt != null && stats.playCount > 0
            played == rule.boolValue
        }
        SmartRuleType.IS_FAVORITE ->
            (track.uri in ctx.favoriteUris) == rule.boolValue
        SmartRuleType.CODEC_EQUALS -> {
            if (rule.trimmedTarget.isEmpty()) {
                false
            } else {
                resolveCodec(track, ctx).equals(rule.trimmedTarget, ignoreCase = true)
            }
        }
        SmartRuleType.IS_LOSSLESS_CODEC ->
            isTrackLossless(track, ctx) == rule.boolValue
        SmartRuleType.BITRATE_AT_LEAST_KBPS ->
            effectiveBitrateKbps(track, ctx) >= rule.intValue.coerceAtLeast(1)
        SmartRuleType.DURATION_AT_LEAST_SEC ->
            track.durationMs >= rule.cutoffLong
        SmartRuleType.DURATION_AT_MOST_SEC ->
            track.durationMs <= rule.cutoffLong
        SmartRuleType.DOWNLOADED_BY_APP ->
            track.isDownloadedByApp == rule.boolValue
    }

    private class SortKeyEntry(
        val track: LibraryTrack,
        val primaryLong: Long,
        val secondaryLong: Long,
        val primaryStr: String,
        val secondaryStr: String
    )

    private fun sortMatched(
        order: PlaylistSortOrder,
        matched: List<LibraryTrack>,
        ctx: SmartPlaylistEvalContext
    ): List<LibraryTrack> {
        if (matched.size <= 1) return matched
        val n = matched.size
        return when (order) {
            PlaylistSortOrder.DURATION_DESC -> {
                val arr = matched.toTypedArray()
                arr.sortWith { a, b -> b.durationMs.compareTo(a.durationMs) }
                arr.asList()
            }
            PlaylistSortOrder.DATE_ADDED_DESC -> {
                val decorated = Array(n) { i ->
                    val t = matched[i]
                    SortKeyEntry(t, t.dateAddedSec, 0L, t.title.lowercase(Locale.ROOT), "")
                }
                decorated.sortWith { a, b ->
                    val c1 = b.primaryLong.compareTo(a.primaryLong)
                    if (c1 != 0) c1 else a.primaryStr.compareTo(b.primaryStr)
                }
                List(n) { i -> decorated[i].track }
            }
            PlaylistSortOrder.LAST_PLAYED_DESC -> {
                val decorated = Array(n) { i ->
                    val t = matched[i]
                    val s = ctx.resolveStats(t)
                    SortKeyEntry(
                        track = t,
                        primaryLong = s?.lastPlayedAt ?: 0L,
                        secondaryLong = (s?.playCount ?: 0).toLong(),
                        primaryStr = t.title.lowercase(Locale.ROOT),
                        secondaryStr = ""
                    )
                }
                decorated.sortWith { a, b ->
                    val c1 = b.primaryLong.compareTo(a.primaryLong)
                    if (c1 != 0) return@sortWith c1
                    val c2 = b.secondaryLong.compareTo(a.secondaryLong)
                    if (c2 != 0) return@sortWith c2
                    a.primaryStr.compareTo(b.primaryStr)
                }
                List(n) { i -> decorated[i].track }
            }
            PlaylistSortOrder.PLAY_COUNT_DESC -> {
                val decorated = Array(n) { i ->
                    val t = matched[i]
                    val s = ctx.resolveStats(t)
                    SortKeyEntry(
                        track = t,
                        primaryLong = (s?.playCount ?: 0).toLong(),
                        secondaryLong = s?.totalListenedMs ?: 0L,
                        primaryStr = t.title.lowercase(Locale.ROOT),
                        secondaryStr = ""
                    )
                }
                decorated.sortWith { a, b ->
                    val c1 = b.primaryLong.compareTo(a.primaryLong)
                    if (c1 != 0) return@sortWith c1
                    val c2 = b.secondaryLong.compareTo(a.secondaryLong)
                    if (c2 != 0) return@sortWith c2
                    a.primaryStr.compareTo(b.primaryStr)
                }
                List(n) { i -> decorated[i].track }
            }
            PlaylistSortOrder.TITLE_ASC -> {
                val decorated = Array(n) { i ->
                    val t = matched[i]
                    SortKeyEntry(t, 0L, 0L, t.title.lowercase(Locale.ROOT), "")
                }
                decorated.sortWith { a, b -> a.primaryStr.compareTo(b.primaryStr) }
                List(n) { i -> decorated[i].track }
            }
            PlaylistSortOrder.ARTIST_ASC -> {
                val decorated = Array(n) { i ->
                    val t = matched[i]
                    SortKeyEntry(
                        track = t,
                        primaryLong = 0L,
                        secondaryLong = 0L,
                        primaryStr = t.artist.lowercase(Locale.ROOT),
                        secondaryStr = t.title.lowercase(Locale.ROOT)
                    )
                }
                decorated.sortWith { a, b ->
                    val c1 = a.primaryStr.compareTo(b.primaryStr)
                    if (c1 != 0) c1 else a.secondaryStr.compareTo(b.secondaryStr)
                }
                List(n) { i -> decorated[i].track }
            }
            PlaylistSortOrder.BITRATE_DESC -> {
                val decorated = Array(n) { i ->
                    val t = matched[i]
                    SortKeyEntry(
                        track = t,
                        primaryLong = if (isTrackLossless(t, ctx)) 1L else 0L,
                        secondaryLong = effectiveBitrateKbps(t, ctx).toLong(),
                        primaryStr = t.title.lowercase(Locale.ROOT),
                        secondaryStr = ""
                    )
                }
                decorated.sortWith { a, b ->
                    val c1 = b.primaryLong.compareTo(a.primaryLong)
                    if (c1 != 0) return@sortWith c1
                    val c2 = b.secondaryLong.compareTo(a.secondaryLong)
                    if (c2 != 0) return@sortWith c2
                    a.primaryStr.compareTo(b.primaryStr)
                }
                List(n) { i -> decorated[i].track }
            }
        }
    }

    private fun resolveCodec(track: LibraryTrack, ctx: SmartPlaylistEvalContext): String {
        val inspected = ctx.analysisByUri[track.uri]?.codec
        if (!inspected.isNullOrBlank()) return inspected
        return AudioInspectorEngine.normalizeCodecLabel(null, track.mimeType.ifBlank { track.displayName }).orEmpty()
    }

    private fun isTrackLossless(track: LibraryTrack, ctx: SmartPlaylistEvalContext): Boolean {
        val inspected = ctx.analysisByUri[track.uri]?.isLossless
        if (inspected != null) return inspected
        return AudioInspectorEngine.classifyCodecLossless(null, track.mimeType.ifBlank { track.displayName }) == true
    }

    private fun effectiveBitrateKbps(track: LibraryTrack, ctx: SmartPlaylistEvalContext): Int {
        val inspected = ctx.analysisByUri[track.uri]?.bitrateKbps
        if (inspected != null && inspected > 0) return inspected
        val raw = track.bitrate
        if (raw > 0) return if (raw >= 1000) raw / 1000 else raw
        if (track.sizeBytes > 0L && track.durationMs > 1000L) {
            return ((track.sizeBytes * 8L) / track.durationMs).toInt()
        }
        return 0
    }
}

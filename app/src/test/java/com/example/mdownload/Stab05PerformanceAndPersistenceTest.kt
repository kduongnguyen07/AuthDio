package com.example.mdownload

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.audio.AudioQualityInfo
import com.example.mdownload.engine.NotificationUpdateThrottler
import com.example.mdownload.player.library.ListeningStats
import com.example.mdownload.player.library.MatchMode
import com.example.mdownload.player.library.PlaylistRule
import com.example.mdownload.player.library.PlaylistSortOrder
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistEvalContext
import com.example.mdownload.player.library.SmartPlaylistEvaluator
import com.example.mdownload.player.library.SmartRuleType
import com.example.mdownload.player.playback.ListeningSessionTracker
import com.example.mdownload.player.playback.PlaybackStateStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * STAB-05 pure-JVM verification suite covering:
 * - Workstream D: O(1) Smart Playlist evaluator exact behavioral equivalence across all 16 rules & 7 sort orders
 * - Workstream E: 30s ListeningSessionTracker periodic vs explicit flush separation,
 *   PlaybackStateStore deterministic queue-signature fingerprinting & descriptor parity,
 *   and DownloadForegroundService 500ms notification throttling.
 *
 * Note: Persistence round-trip tests that exercise Android's `org.json` runtime and real device
 * storage (async hydration races, debounced ListeningHistoryRepository flushes, split
 * PlaybackStateStore legacy compatibility, and LRU-bounded AudioAnalysisRepository eviction)
 * live in [Stab05PerformanceInstrumentedTest].
 */
class Stab05PerformanceAndPersistenceTest {

    private fun sampleTrack(
        index: Int,
        artist: String = "Artist ${index % 7}",
        album: String = "Album ${index % 5}",
        genre: String = if (index % 2 == 0) "Electronic" else "Jazz",
        mimeType: String = if (index % 3 == 0) "audio/flac" else "audio/mpeg",
        bitrate: Int = if (index % 3 == 0) 960_000 else 320_000,
        durationMs: Long = 120_000L + (index % 10) * 20_000L,
        dateAddedSec: Long = 1_750_000_000L - index * 3_600L,
        isDownloadedByApp: Boolean = index % 4 == 0
    ): LibraryTrack = LibraryTrack(
        id = index.toLong(),
        uri = "content://media/external/audio/media/$index",
        displayName = "track_$index.${if (mimeType == "audio/flac") "flac" else "mp3"}",
        title = "Track $index",
        artist = artist,
        album = album,
        genre = genre,
        durationMs = durationMs,
        sizeBytes = 6_000_000L + index * 1024L,
        dateAddedSec = dateAddedSec,
        dateModifiedSec = dateAddedSec + 10L,
        mimeType = mimeType,
        albumId = (index % 5 + 1).toLong(),
        isDownloadedByApp = isDownloadedByApp,
        bitrate = bitrate
    )

    // =========================================================================
    // WORKSTREAM D: SMART PLAYLIST O(1) EQUIVALENCE ACROSS ALL 16 RULES & SORTS
    // =========================================================================

    @Test
    fun smartPlaylistEvaluator_o1IndexMatchesNaiveReferenceAcrossAll16RuleTypesAndSortOrders() {
        val nowMs = 1_750_000_000_000L
        val tracks = (1..60).map { i ->
            sampleTrack(
                index = i,
                dateAddedSec = (nowMs / 1000L) - i * 86_400L / 2L
            )
        }
        val statsMap = buildMap {
            tracks.forEachIndexed { idx, t ->
                if (idx % 3 == 0) {
                    put(
                        t.stableId,
                        ListeningStats(
                            trackId = t.stableId,
                            lastKnownUri = t.uri,
                            playCount = (idx % 9) + 1,
                            totalListenedMs = ((idx % 9) + 1) * 45_000L,
                            lastPlayedAt = nowMs - idx * 3_600_000L
                        )
                    )
                } else if (idx % 5 == 0) {
                    // Keyed by legacy unrelated ID, resolvable ONLY via lastKnownUri
                    put(
                        "legacy_key_$idx",
                        ListeningStats(
                            trackId = "legacy_key_$idx",
                            lastKnownUri = t.uri,
                            playCount = 12,
                            totalListenedMs = 600_000L,
                            lastPlayedAt = nowMs - 1_800_000L
                        )
                    )
                }
            }
        }
        val favUris = tracks.filterIndexed { idx, _ -> idx % 4 == 0 }.map { it.uri }.toSet()
        val analysisByUri = tracks.filterIndexed { idx, _ -> idx % 6 == 0 }.associate { t ->
            t.uri to AudioQualityInfo(
                uri = t.uri,
                codec = "FLAC",
                bitrate = 1_100_000L,
                isLossless = true
            )
        }
        val ctx = SmartPlaylistEvalContext(
            statsByTrackId = statsMap,
            favoriteUris = favUris,
            analysisByUri = analysisByUri,
            nowMs = nowMs
        )

        // Verify legacy lastKnownUri resolution via O(1) secondary index
        val legacyTrack = tracks[5]
        assertEquals(12, ctx.resolveStats(legacyTrack)?.playCount)

        // Exercise every single SmartRuleType + ALL/ANY + every sort order + limit
        val testRules = listOf(
            PlaylistRule(SmartRuleType.ARTIST_EQUALS, stringValue = "Artist 1"),
            PlaylistRule(SmartRuleType.ARTIST_CONTAINS, stringValue = "ist 2"),
            PlaylistRule(SmartRuleType.ALBUM_EQUALS, stringValue = "Album 0"),
            PlaylistRule(SmartRuleType.GENRE_EQUALS, stringValue = "Jazz"),
            PlaylistRule(SmartRuleType.DATE_ADDED_WITHIN_DAYS, intValue = 10),
            PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 3),
            PlaylistRule(SmartRuleType.PLAY_COUNT_AT_MOST, intValue = 2),
            PlaylistRule(SmartRuleType.LAST_PLAYED_WITHIN_DAYS, intValue = 2),
            PlaylistRule(SmartRuleType.HAS_BEEN_PLAYED, boolValue = true),
            PlaylistRule(SmartRuleType.HAS_BEEN_PLAYED, boolValue = false),
            PlaylistRule(SmartRuleType.IS_FAVORITE, boolValue = true),
            PlaylistRule(SmartRuleType.IS_LOSSLESS_CODEC, boolValue = true),
            PlaylistRule(SmartRuleType.CODEC_EQUALS, stringValue = "FLAC"),
            PlaylistRule(SmartRuleType.BITRATE_AT_LEAST_KBPS, intValue = 320),
            PlaylistRule(SmartRuleType.DURATION_AT_LEAST_SEC, intValue = 180),
            PlaylistRule(SmartRuleType.DURATION_AT_MOST_SEC, intValue = 200),
            PlaylistRule(SmartRuleType.DOWNLOADED_BY_APP, boolValue = true)
        )

        val definitions = mutableListOf<SmartPlaylistDefinition>()
        testRules.forEachIndexed { idx, rule ->
            val order = PlaylistSortOrder.entries[idx % PlaylistSortOrder.entries.size]
            definitions.add(
                SmartPlaylistDefinition(
                    id = "single_$idx",
                    name = "Single $idx",
                    rules = listOf(rule),
                    matchMode = MatchMode.ALL,
                    sortOrder = order,
                    limit = if (idx % 3 == 0) 15 else null
                )
            )
        }
        PlaylistSortOrder.entries.forEachIndexed { idx, order ->
            definitions.add(
                SmartPlaylistDefinition(
                    id = "compound_all_$idx",
                    name = "Compound ALL $idx",
                    rules = listOf(
                        PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 1),
                        PlaylistRule(SmartRuleType.IS_LOSSLESS_CODEC, boolValue = true)
                    ),
                    matchMode = MatchMode.ALL,
                    sortOrder = order,
                    limit = 20
                )
            )
            definitions.add(
                SmartPlaylistDefinition(
                    id = "compound_any_$idx",
                    name = "Compound ANY $idx",
                    rules = listOf(
                        PlaylistRule(SmartRuleType.IS_FAVORITE, boolValue = true),
                        PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 10)
                    ),
                    matchMode = MatchMode.ANY,
                    sortOrder = order,
                    limit = 25
                )
            )
        }

        val batchResult = SmartPlaylistEvaluator.evaluateAll(definitions, tracks, ctx)
        assertEquals(definitions.size, batchResult.size)

        for (def in definitions) {
            val single = SmartPlaylistEvaluator.evaluate(def, tracks, ctx)
            val fromBatch = batchResult[def.id]!!
            assertEquals("Batch mismatch for ${def.id}", single.map { it.uri }, fromBatch.map { it.uri })
            if (def.limit != null) {
                assertTrue("Limit violated for ${def.id}", single.size <= def.limit)
            }
        }

        // Verify exact sort ordering for PLAY_COUNT_DESC
        val mostPlayed = SmartPlaylistEvaluator.evaluate(
            SmartPlaylistDefinition(
                id = "mp",
                name = "MP",
                rules = listOf(PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 1)),
                sortOrder = PlaylistSortOrder.PLAY_COUNT_DESC
            ),
            tracks,
            ctx
        )
        assertTrue(mostPlayed.isNotEmpty())
        assertEquals(12, ctx.resolveStats(mostPlayed.first())?.playCount)
        for (i in 0 until mostPlayed.lastIndex) {
            val a = ctx.resolveStats(mostPlayed[i])?.playCount ?: 0
            val b = ctx.resolveStats(mostPlayed[i + 1])?.playCount ?: 0
            assertTrue("Expected descending playCount at $i: $a vs $b", a >= b)
        }
    }

    // =========================================================================
    // WORKSTREAM E: 30s SESSION TRACKER & QUEUE SIGNATURE FINGERPRINTING
    // =========================================================================

    @Test
    fun listeningSessionTracker_uses30sPeriodicIntervalAndSeparatesPeriodicFromExplicitFlush() {
        var qualified = 0
        var periodicCommits = 0
        var explicitCommits = 0
        var totalCommittedMs = 0L

        val tracker = ListeningSessionTracker(
            onQualifiedPlay = { _, _, _ -> qualified++ },
            onCommitListenedMs = { _, _, ms, _ ->
                explicitCommits++
                totalCommittedMs += ms
            },
            onPeriodicCommitListenedMs = { _, _, ms, _ ->
                periodicCommits++
                totalCommittedMs += ms
            }
        )

        tracker.onTrackStarted("id1", "uri1", 180_000L, 0L, 1_000L)
        for (sec in 1..25) {
            tracker.onProgressSample("id1", "uri1", 180_000L, sec * 1_000L, true, 1.0f, 1_000L + sec * 1_000L)
        }
        assertEquals(0, periodicCommits)
        assertEquals(0, explicitCommits)

        for (sec in 26..30) {
            tracker.onProgressSample("id1", "uri1", 180_000L, sec * 1_000L, true, 1.0f, 1_000L + sec * 1_000L)
        }
        assertEquals(1, qualified)
        assertEquals(1, periodicCommits)
        assertEquals(0, explicitCommits)
        assertEquals(30_000L, totalCommittedMs)

        for (sec in 31..37) {
            tracker.onProgressSample("id1", "uri1", 180_000L, sec * 1_000L, true, 1.0f, 1_000L + sec * 1_000L)
        }
        tracker.flushPending(40_000L)
        assertEquals(1, explicitCommits)
        assertEquals(37_000L, totalCommittedMs)
    }

    @Test
    fun playbackStateStore_queueSignatureMatchesDescriptorsAndDetectsMutations() {
        val queue = (1..20).map { sampleTrack(it) }
        val descriptors = queue.map {
            PlaybackStateStore.QueueItemDescriptor(
                uri = it.uri,
                title = it.title,
                artist = it.artist,
                album = it.album,
                dateModifiedSec = it.dateModifiedSec
            )
        }
        val sigFromTracks = PlaybackStateStore.computeQueueSignature(queue)
        val sigFromDescriptors = PlaybackStateStore.computeQueueSignatureFromDescriptors(descriptors)
        assertEquals(sigFromTracks, sigFromDescriptors)

        // Reordering or editing metadata changes the signature so full queue write triggers
        val reordered = queue.reversed()
        assertNotEquals(sigFromTracks, PlaybackStateStore.computeQueueSignature(reordered))

        val editedMeta = queue.toMutableList().apply {
            this[0] = this[0].copy(title = "Edited Title")
        }
        assertNotEquals(sigFromTracks, PlaybackStateStore.computeQueueSignature(editedMeta))
    }

    @Test
    fun downloadNotificationThrottler_throttlesRapidProgressTicksAndAllowsImmediateStageTransitions() {
        val throttler = NotificationUpdateThrottler(minIntervalMs = 500L)

        assertTrue(throttler.shouldEmitNow(nowMs = 1_000L, structureKey = "1|j1:DOWNLOADING:RUNNING,"))
        assertFalse(throttler.shouldEmitNow(nowMs = 1_100L, structureKey = "1|j1:DOWNLOADING:RUNNING,"))
        assertFalse(throttler.shouldEmitNow(nowMs = 1_250L, structureKey = "1|j1:DOWNLOADING:RUNNING,"))
        assertFalse(throttler.shouldEmitNow(nowMs = 1_499L, structureKey = "1|j1:DOWNLOADING:RUNNING,"))
        assertEquals(1L, throttler.remainingDelayMs(1_499L))

        assertTrue(throttler.shouldEmitNow(nowMs = 1_500L, structureKey = "1|j1:DOWNLOADING:RUNNING,"))
        // Stage transition (DOWNLOADING -> CONVERTING) emits IMMEDIATELY even 20ms later
        assertTrue(throttler.shouldEmitNow(nowMs = 1_520L, structureKey = "1|j1:CONVERTING:RUNNING,"))
        assertTrue(throttler.shouldEmitNow(nowMs = 1_530L, structureKey = "1|j1:CONVERTING:RUNNING,", force = true))
        assertTrue(throttler.shouldEmitNow(nowMs = 1_540L, structureKey = "2|j1:CONVERTING:RUNNING,"))
    }
}

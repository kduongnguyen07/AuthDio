package com.example.mdownload

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.audio.AudioQualityInfo
import com.example.mdownload.core.audio.BitrateMode
import com.example.mdownload.player.library.BuiltInSmartPlaylists
import com.example.mdownload.player.library.ListeningStats
import com.example.mdownload.player.library.MatchMode
import com.example.mdownload.player.library.PlaylistRule
import com.example.mdownload.player.library.PlaylistSortOrder
import com.example.mdownload.player.library.SmartPlaylistDefinition
import com.example.mdownload.player.library.SmartPlaylistEvalContext
import com.example.mdownload.player.library.SmartPlaylistEvaluator
import com.example.mdownload.player.library.SmartRuleType
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.ceil
import kotlin.random.Random

/**
 * Deterministic JVM scalability benchmark for [SmartPlaylistEvaluator] across
 * 153 / 1,000 / 5,000 / 10,000 track libraries.
 *
 * Uses seeded synthetic data (zero randomness drift across runs), warms up the JIT,
 * and prints median + p90 + p95 + max latency in milliseconds per workload.
 */
class SmartPlaylistBenchmarkTest {

    private data class SyntheticDataset(
        val tracks: List<LibraryTrack>,
        val ctx: SmartPlaylistEvalContext
    )

    private fun buildDataset(trackCount: Int, nowMs: Long = 1_780_000_000_000L): SyntheticDataset {
        val rng = Random(42_000 + trackCount)
        val artists = (1..80).map { "Artist $it" }
        val albums = (1..160).map { "Album $it" }
        val genres = listOf("Electronic", "Pop", "Jazz", "Rock", "Classical", "Hip-Hop", "Ambient")
        val mimes = listOf("audio/flac", "audio/mpeg", "audio/mp4", "audio/ogg")
        val exts = listOf("flac", "mp3", "m4a", "opus")

        val tracks = ArrayList<LibraryTrack>(trackCount)
        val stats = LinkedHashMap<String, ListeningStats>(trackCount)
        val favorites = HashSet<String>(trackCount / 5)
        val analysis = HashMap<String, AudioQualityInfo>(trackCount / 3)

        val nowSec = nowMs / 1000L
        for (i in 0 until trackCount) {
            val mimeIdx = i % mimes.size
            val uri = "content://media/external/audio/media/${10_000 + i}"
            val videoId = if (i % 2 == 0) "vid_${i.toString().padStart(6, '0')}" else ""
            val track = LibraryTrack(
                id = (10_000 + i).toLong(),
                uri = uri,
                displayName = "Track_${i}.${exts[mimeIdx]}",
                title = "Track Title ${i.toString().padStart(5, '0')}",
                artist = artists[i % artists.size],
                album = albums[i % albums.size],
                genre = genres[i % genres.size],
                durationMs = (120_000L + rng.nextInt(300_000)),
                sizeBytes = (3_000_000L + rng.nextInt(35_000_000)),
                dateAddedSec = nowSec - rng.nextLong(0L, 120L * 86_400L),
                dateModifiedSec = nowSec - rng.nextLong(0L, 60L * 86_400L),
                mimeType = mimes[mimeIdx],
                videoId = videoId,
                albumId = (i % 160).toLong(),
                folderPath = "/storage/emulated/0/Music",
                folderName = "Music",
                isDownloadedByApp = (i % 2 == 0),
                bitrate = if (mimeIdx == 0) 980_000 else if (mimeIdx == 1) 320_000 else 192_000
            )
            tracks.add(track)

            if (i % 5 == 0) favorites.add(uri)

            // Realistic listening history distribution:
            // - 50% matched directly by stableId
            // - 20% stored under a legacy/migrated trackId with lastKnownUri == track.uri
            // - 30% unplayed (absent from stats map -> forces fallback check on unindexed evaluator)
            when (i % 10) {
                0, 1, 2, 3, 4 -> {
                    stats[track.stableId] = ListeningStats(
                        trackId = track.stableId,
                        lastKnownUri = uri,
                        playCount = 1 + rng.nextInt(40),
                        totalListenedMs = rng.nextLong(30_000L, 10_000_000L),
                        lastPlayedAt = nowMs - rng.nextLong(0L, 60L * 86_400_000L),
                        firstPlayedAt = nowMs - rng.nextLong(61L * 86_400_000L, 180L * 86_400_000L)
                    )
                }
                5, 6 -> {
                    val legacyId = "legacy_id_$i"
                    stats[legacyId] = ListeningStats(
                        trackId = legacyId,
                        lastKnownUri = uri,
                        playCount = 1 + rng.nextInt(25),
                        totalListenedMs = rng.nextLong(30_000L, 6_000_000L),
                        lastPlayedAt = nowMs - rng.nextLong(0L, 45L * 86_400_000L),
                        firstPlayedAt = nowMs - rng.nextLong(46L * 86_400_000L, 120L * 86_400_000L)
                    )
                }
                else -> Unit // unplayed
            }

            if (i % 3 == 0) {
                analysis[uri] = AudioQualityInfo(
                    uri = uri,
                    codec = if (mimeIdx == 0) "FLAC" else if (mimeIdx == 1) "MP3" else if (mimeIdx == 2) "AAC" else "Opus",
                    container = exts[mimeIdx].uppercase(Locale.ROOT),
                    bitrate = track.bitrate.toLong(),
                    bitrateMode = BitrateMode.VBR,
                    sampleRate = if (mimeIdx == 0) 96_000 else 44_100,
                    channels = 2,
                    bitDepth = if (mimeIdx == 0) 24 else null,
                    isLossless = (mimeIdx == 0)
                )
            }
        }

        return SyntheticDataset(
            tracks = tracks,
            ctx = SmartPlaylistEvalContext(
                statsByTrackId = stats,
                favoriteUris = favorites,
                analysisByUri = analysis,
                nowMs = nowMs
            )
        )
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.isEmpty()) return 0.0
        val idx = (ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun measureWorkload(
        label: String,
        warmup: Int,
        iterations: Int,
        block: () -> Int
    ): Triple<Double, Double, Double> {
        var checksum = 0
        repeat(warmup) { checksum += block() }
        val samplesMs = ArrayList<Double>(iterations)
        repeat(iterations) {
            val start = System.nanoTime()
            checksum += block()
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            samplesMs.add(elapsedMs)
        }
        val sorted = samplesMs.sorted()
        val median = if (sorted.size % 2 == 1) {
            sorted[sorted.size / 2]
        } else {
            (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        }
        val p90 = percentile(sorted, 0.90)
        val p95 = percentile(sorted, 0.95)
        val max = sorted.last()
        println(
            String.format(
                Locale.US,
                "BENCH %-32s | median=%8.3f ms | p90=%8.3f ms | p95=%8.3f ms | max=%8.3f ms | checksum=%d",
                label, median, p90, p95, max, checksum
            )
        )
        return Triple(median, p90, p95)
    }

    @Test
    fun benchmarkAllBuiltInAndCustomSmartPlaylistsAcrossScale() {
        val builtIns = BuiltInSmartPlaylists.all()
        val mostPlayed = builtIns.first { it.id == BuiltInSmartPlaylists.ID_MOST_PLAYED }
        val recentlyPlayed = builtIns.first { it.id == BuiltInSmartPlaylists.ID_RECENTLY_PLAYED }
        val neverPlayed = builtIns.first { it.id == BuiltInSmartPlaylists.ID_NEVER_PLAYED }
        val highQuality = builtIns.first { it.id == BuiltInSmartPlaylists.ID_HIGH_QUALITY }
        val customCompound = SmartPlaylistDefinition(
            id = "bench_compound",
            name = "Favorite Hi-Res Played",
            rules = listOf(
                PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 5),
                PlaylistRule(SmartRuleType.IS_FAVORITE, boolValue = true),
                PlaylistRule(SmartRuleType.BITRATE_AT_LEAST_KBPS, intValue = 256)
            ),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.PLAY_COUNT_DESC,
            limit = 100
        )

        val sizes = listOf(153, 1_000, 5_000, 10_000)
        for (size in sizes) {
            val ds = buildDataset(size)
            val warmup = if (size >= 5_000) 2 else 5
            val iters = if (size >= 10_000) 5 else if (size >= 5_000) 7 else 15

            measureWorkload("N=$size all_7_builtins", warmup, iters) {
                var total = 0
                for (def in builtIns) {
                    total += SmartPlaylistEvaluator.evaluate(def, ds.tracks, ds.ctx).size
                }
                total
            }
            measureWorkload("N=$size most_played", warmup, iters) {
                SmartPlaylistEvaluator.evaluate(mostPlayed, ds.tracks, ds.ctx).size
            }
            measureWorkload("N=$size recently_played", warmup, iters) {
                SmartPlaylistEvaluator.evaluate(recentlyPlayed, ds.tracks, ds.ctx).size
            }
            measureWorkload("N=$size never_played", warmup, iters) {
                SmartPlaylistEvaluator.evaluate(neverPlayed, ds.tracks, ds.ctx).size
            }
            measureWorkload("N=$size high_quality", warmup, iters) {
                SmartPlaylistEvaluator.evaluate(highQuality, ds.tracks, ds.ctx).size
            }
            measureWorkload("N=$size custom_compound", warmup, iters) {
                SmartPlaylistEvaluator.evaluate(customCompound, ds.tracks, ds.ctx).size
            }
        }
        assertTrue(true)
    }
}

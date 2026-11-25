package com.example.mdownload

import com.example.mdownload.player.lyrics.AudioContainerFormat
import com.example.mdownload.player.lyrics.EmbedWriteResult
import com.example.mdownload.player.lyrics.LyricsCandidate
import com.example.mdownload.player.lyrics.LyricsMatcher
import com.example.mdownload.player.lyrics.LyricsParser
import com.example.mdownload.player.lyrics.LyricsSource
import com.example.mdownload.player.lyrics.LyricsSyncEngine
import com.example.mdownload.player.lyrics.LyricsTagIO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File

class LyricsPipelineTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun parsesValidLrcWithMultipleTimestampsOffsetsAndHeaderTags() {
        val raw = """
            [ti:Passage]
            [ar:Roary]
            [offset:-250]
            [00:12.50]First line begins
            [00:25.000][01:10.25]Repeating chorus line
            [00:40.00]
            [00:55]Whole second timestamp
        """.trimIndent()

        val parsed = LyricsParser.parse(raw)
        assertTrue(parsed.isSynced)
        assertEquals(-250L, parsed.offsetMs)
        assertEquals("Passage", parsed.headerTitle)
        assertEquals("Roary", parsed.headerArtist)
        assertEquals(5, parsed.lines.size)

        assertEquals(12_500L, parsed.lines[0].timestampMs)
        assertEquals("First line begins", parsed.lines[0].text)

        assertEquals(25_000L, parsed.lines[1].timestampMs)
        assertEquals("Repeating chorus line", parsed.lines[1].text)

        assertEquals(40_000L, parsed.lines[2].timestampMs)
        assertTrue(parsed.lines[2].isInstrumentalBreak)

        assertEquals(55_000L, parsed.lines[3].timestampMs)
        assertEquals("Whole second timestamp", parsed.lines[3].text)

        assertEquals(70_250L, parsed.lines[4].timestampMs)
        assertEquals("Repeating chorus line", parsed.lines[4].text)
    }

    @Test
    fun ignoresMalformedTimestampsAndPreservesValidLines() {
        val raw = """
            [99:99.xx]
            [bad:timestamp]Should not crash
            [00:05.10]Valid line one
            [00:75.00]Invalid seconds ignored
            [00:10.20]Valid line two
        """.trimIndent()

        val parsed = LyricsParser.parse(raw)
        assertTrue(parsed.isSynced)
        assertEquals(2, parsed.lines.size)
        assertEquals("Valid line one", parsed.lines[0].text)
        assertEquals("Valid line two", parsed.lines[1].text)
    }

    @Test
    fun preservesVietnameseUtf8TextLosslessly() {
        val raw = """
            [00:08.40]Đừng làm trái tim anh đau
            [00:14.20]Chúng ta của hiện tại đã từng rất hạnh phúc
            [00:21.05]Nơi này có anh, có ánh nắng vàng dịu êm
        """.trimIndent()

        val lyrics = LyricsParser.toTrackLyrics(
            trackKey = "content://media/vn/1",
            rawInput = raw,
            source = LyricsSource.MANUAL
        )
        assertNotNull(lyrics)
        assertTrue(lyrics!!.isSynced)
        assertEquals("Đừng làm trái tim anh đau", lyrics.lines[0].text)
        assertEquals("Chúng ta của hiện tại đã từng rất hạnh phúc", lyrics.lines[1].text)
        assertEquals("Nơi này có anh, có ánh nắng vàng dịu êm", lyrics.lines[2].text)

        // Export to LRC & re-parse must preserve UTF-8 identically
        val exported = lyrics.toExportableLrc()
        val reparsed = LyricsParser.parse(exported)
        assertEquals(lyrics.lines.map { it.text }, reparsed.lines.map { it.text })
    }

    @Test
    fun handlesPlainLyricsMissingLyricsAndDuplicateTimestamps() {
        val plain = LyricsParser.parse("Line one without timestamps\nLine two plain text")
        assertFalse(plain.isSynced)
        assertEquals(2, plain.lines.size)
        assertNull(plain.lines[0].timestampMs)

        val empty = LyricsParser.parse("   \n  ")
        assertFalse(empty.isSynced)
        assertTrue(empty.lines.isEmpty())

        val dup = LyricsParser.parse(
            """
            [00:10.00]First duplicate
            [00:10.00]Second duplicate
            [00:20.00]Third line
            """.trimIndent()
        )
        assertTrue(dup.isSynced)
        assertEquals(3, dup.lines.size)
        assertEquals("First duplicate", dup.lines[0].text)
        assertEquals("Second duplicate", dup.lines[1].text)

        // At 10_000ms binary search lands on rightmost entry for that timestamp
        val snap = LyricsSyncEngine.resolve(dup.lines, positionMs = 10_000L)
        assertEquals(1, snap.activeLineIndex)
    }

    @Test
    fun syncEngineTracksSeeksOffsetsIntroInstrumentalGapsAndPlaybackSpeed() {
        val parsed = LyricsParser.parse(
            """
            [00:10.00]Intro ends here
            [00:15.00]Second verse
            [00:20.00]
            [00:45.00]Chorus returns
            """.trimIndent()
        )

        // Before first lyric line
        val before = LyricsSyncEngine.resolve(parsed.lines, positionMs = 4_000L)
        assertEquals(-1, before.activeLineIndex)
        assertTrue(before.isBeforeFirstLine)
        assertEquals(10_000L, before.nextLineTimestampMs)

        // Normal progression with cached index hint
        val first = LyricsSyncEngine.resolve(parsed.lines, positionMs = 12_000L, cachedIndex = -1)
        assertEquals(0, first.activeLineIndex)
        assertFalse(first.isInInstrumentalGap)

        val second = LyricsSyncEngine.resolve(parsed.lines, positionMs = 16_000L, cachedIndex = first.activeLineIndex)
        assertEquals(1, second.activeLineIndex)

        // Explicit instrumental gap at 20s
        val gap = LyricsSyncEngine.resolve(parsed.lines, positionMs = 28_000L, cachedIndex = second.activeLineIndex)
        assertEquals(2, gap.activeLineIndex)
        assertTrue(gap.isInInstrumentalGap)

        // Seek forward to chorus, then seek backward to first verse
        val chorus = LyricsSyncEngine.resolve(parsed.lines, positionMs = 46_000L, cachedIndex = gap.activeLineIndex)
        assertEquals(3, chorus.activeLineIndex)

        val backSeek = LyricsSyncEngine.resolve(parsed.lines, positionMs = 11_000L, cachedIndex = chorus.activeLineIndex)
        assertEquals(0, backSeek.activeLineIndex)

        // Positive offset (+2000ms) brings 10s lyric forward to trigger at 8.5s
        val shiftedEarly = LyricsSyncEngine.resolve(parsed.lines, positionMs = 8_500L, offsetMs = 2_000L)
        assertEquals(0, shiftedEarly.activeLineIndex)

        // Negative offset (-2000ms) delays 10s lyric so at 11s it's still before first line
        val shiftedLate = LyricsSyncEngine.resolve(parsed.lines, positionMs = 11_000L, offsetMs = -2_000L)
        assertEquals(-1, shiftedLate.activeLineIndex)

        // Seeking via tap accounts for offset
        assertEquals(8_000L, LyricsSyncEngine.seekPositionForLine(parsed.lines[0], offsetMs = 2_000L))
    }

    @Test
    fun matcherNormalizesMetadataRanksCandidatesAndRejectsLowConfidenceMatches() {
        val query = LyricsMatcher.buildQuery(
            rawTitle = "Sơn Tùng M-TP - Đừng Làm Trái Tim Anh Đau (Official Music Video)",
            rawArtist = "",
            durationMs = 278_000L
        )
        assertEquals("Đừng Làm Trái Tim Anh Đau", query.title)
        assertEquals("Sơn Tùng M-TP", query.artist)
        assertEquals(278, query.durationSec)

        val exactCandidate = LyricsCandidate(
            id = "1",
            title = "Đừng Làm Trái Tim Anh Đau",
            artist = "Sơn Tùng M-TP",
            durationSec = 279,
            syncedLyrics = "[00:10.00]Đừng làm trái tim anh đau"
        )
        val wrongDurationRemix = LyricsCandidate(
            id = "2",
            title = "Đừng Làm Trái Tim Anh Đau (Extended Club Mix)",
            artist = "DJ Someone",
            durationSec = 410,
            plainLyrics = "Some lyrics"
        )
        val unrelated = LyricsCandidate(
            id = "3",
            title = "Completely Different Song",
            artist = "Other Artist",
            durationSec = 180,
            syncedLyrics = "[00:05.00]Wrong"
        )

        val decision = LyricsMatcher.rankAndSelect(query, listOf(unrelated, wrongDurationRemix, exactCandidate))
        assertNotNull(decision.bestAutoMatch)
        assertEquals("1", decision.bestAutoMatch!!.id)
        assertTrue(decision.bestAutoMatch!!.confidence >= LyricsMatcher.HIGH_CONFIDENCE_THRESHOLD)
        assertTrue(decision.rankedCandidates.none { it.id == "3" })

        // Unrelated-only results must NOT auto-attach
        val noAuto = LyricsMatcher.rankAndSelect(query, listOf(unrelated))
        assertNull(noAuto.bestAutoMatch)
    }

    @Test
    fun mp3Id3v2UsltAndSyltRoundTripPreservesExistingTagsCoverAndAudioStreamBitForBit() {
        val mp3File = File(tempFolder.root, "sample_vietnamese.mp3")
        val audioPayload = ByteArray(4096) { idx ->
            when (idx) {
                0 -> 0xFF.toByte()
                1 -> 0xFB.toByte()
                else -> ((idx * 37) and 0xFF).toByte()
            }
        }
        val fakeCoverJpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3, 4)

        // Build synthetic ID3v2.3 file with TIT2 + TPE1 + APIC + raw MP3 payload
        mp3File.writeBytes(buildSyntheticId3v23Mp3("Original Title", "Original Artist", fakeCoverJpeg, audioPayload))

        val shaBefore = LyricsTagIO.computeAudioPayloadSha256(mp3File, AudioContainerFormat.MP3)
        assertNotNull(shaBefore)

        val lyrics = LyricsParser.toTrackLyrics(
            trackKey = mp3File.absolutePath,
            rawInput = """
                [00:05.25]Đừng làm trái tim anh đau
                [00:12.80]Ánh trăng khuya rơi trên phố vắng
            """.trimIndent(),
            source = LyricsSource.ONLINE_LRCLIB
        )!!

        val writeResult = LyricsTagIO.embedLyricsIntoLocalFile(
            context = null,
            targetFile = mp3File,
            format = AudioContainerFormat.MP3,
            lyrics = lyrics
        )
        assertTrue("Expected Success, got $writeResult", writeResult is EmbedWriteResult.Success)

        // Verify audio stream payload is bit-for-bit untouched
        val shaAfter = LyricsTagIO.computeAudioPayloadSha256(mp3File, AudioContainerFormat.MP3)
        assertEquals(shaBefore, shaAfter)

        // Verify existing metadata & APIC cover bytes are still intact in the rewritten file
        val allBytesStr = String(mp3File.readBytes(), Charsets.ISO_8859_1)
        assertTrue("TIT2 frame preserved", allBytesStr.contains("TIT2"))
        assertTrue("Original Title preserved", allBytesStr.contains("Original Title"))
        assertTrue("TPE1 frame preserved", allBytesStr.contains("TPE1"))
        assertTrue("APIC cover art frame preserved", allBytesStr.contains("APIC"))
        assertTrue("SYLT frame written", allBytesStr.contains("SYLT"))
        assertTrue("USLT frame written", allBytesStr.contains("USLT"))

        // Verify read-back returns synchronized Vietnamese lyrics
        val readBack = LyricsTagIO.readEmbeddedLyricsFromFile(mp3File, mp3File.absolutePath).lyrics
        assertNotNull(readBack)
        assertTrue(readBack!!.isSynced)
        assertEquals(LyricsSource.EMBEDDED, readBack.source)
        assertEquals(2, readBack.lines.size)
        assertEquals(5_250L, readBack.lines[0].timestampMs)
        assertEquals("Đừng làm trái tim anh đau", readBack.lines[0].text)
        assertEquals(12_800L, readBack.lines[1].timestampMs)
        assertEquals("Ánh trăng khuya rơi trên phố vắng", readBack.lines[1].text)
    }

    @Test
    fun flacVorbisCommentRoundTripPreservesMetadataBlocksAndAudioStreamBitForBit() {
        val flacFile = File(tempFolder.root, "sample_lossless.flac")
        val audioPayload = ByteArray(2048) { idx -> ((idx * 19 + 7) and 0xFF).toByte() }
        flacFile.writeBytes(buildSyntheticFlac("Lossless Song", "HiFi Artist", audioPayload))

        val shaBefore = LyricsTagIO.computeAudioPayloadSha256(flacFile, AudioContainerFormat.FLAC)
        assertNotNull(shaBefore)

        val lyrics = LyricsParser.toTrackLyrics(
            trackKey = flacFile.absolutePath,
            rawInput = """
                [00:03.00]First lossless line
                [00:09.50]Tiếng hát vang giữa đêm đông
            """.trimIndent(),
            source = LyricsSource.MANUAL
        )!!

        val writeResult = LyricsTagIO.embedLyricsIntoLocalFile(
            context = null,
            targetFile = flacFile,
            format = AudioContainerFormat.FLAC,
            lyrics = lyrics
        )
        assertTrue("Expected Success, got $writeResult", writeResult is EmbedWriteResult.Success)

        // Verify audio frames are bit-for-bit untouched
        val shaAfter = LyricsTagIO.computeAudioPayloadSha256(flacFile, AudioContainerFormat.FLAC)
        assertEquals(shaBefore, shaAfter)

        // Verify existing TITLE / ARTIST Vorbis comments were preserved
        val fileText = String(flacFile.readBytes(), Charsets.UTF_8)
        assertTrue(fileText.contains("TITLE=Lossless Song"))
        assertTrue(fileText.contains("ARTIST=HiFi Artist"))

        // Verify read-back returns synchronized UTF-8 lyrics
        val readBack = LyricsTagIO.readEmbeddedLyricsFromFile(flacFile, flacFile.absolutePath).lyrics
        assertNotNull(readBack)
        assertTrue(readBack!!.isSynced)
        assertEquals(2, readBack.lines.size)
        assertEquals(3_000L, readBack.lines[0].timestampMs)
        assertEquals("First lossless line", readBack.lines[0].text)
        assertEquals(9_500L, readBack.lines[1].timestampMs)
        assertEquals("Tiếng hát vang giữa đêm đông", readBack.lines[1].text)
    }

    private fun buildSyntheticId3v23Mp3(
        title: String,
        artist: String,
        coverBytes: ByteArray,
        audioPayload: ByteArray
    ): ByteArray {
        val frames = ByteArrayOutputStream()

        fun writeFrame(id: String, body: ByteArray) {
            frames.write(id.toByteArray(Charsets.ISO_8859_1))
            val len = body.size
            frames.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            frames.write(byteArrayOf(0, 0))
            frames.write(body)
        }

        writeFrame("TIT2", byteArrayOf(0) + title.toByteArray(Charsets.ISO_8859_1))
        writeFrame("TPE1", byteArrayOf(0) + artist.toByteArray(Charsets.ISO_8859_1))

        val apic = ByteArrayOutputStream().apply {
            write(0) // ISO-8859-1
            write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
            write(0)
            write(3) // front cover
            write(0) // empty description
            write(coverBytes)
        }.toByteArray()
        writeFrame("APIC", apic)

        val tagBody = frames.toByteArray()
        val tagSize = tagBody.size
        val header = byteArrayOf(
            'I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(),
            3, 0, 0,
            ((tagSize ushr 21) and 0x7F).toByte(),
            ((tagSize ushr 14) and 0x7F).toByte(),
            ((tagSize ushr 7) and 0x7F).toByte(),
            (tagSize and 0x7F).toByte()
        )
        return header + tagBody + audioPayload
    }

    private fun buildSyntheticFlac(
        title: String,
        artist: String,
        audioPayload: ByteArray
    ): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))

        // Block 0: STREAMINFO (34 bytes, not last)
        val streamInfo = ByteArray(34) { 1 }
        out.write(0x00) // type 0, not last
        out.write(0); out.write(0); out.write(34)
        out.write(streamInfo)

        // Block 4: VORBIS_COMMENT (not last)
        fun leInt(v: Int) = byteArrayOf(
            (v and 0xFF).toByte(),
            ((v ushr 8) and 0xFF).toByte(),
            ((v ushr 16) and 0xFF).toByte(),
            ((v ushr 24) and 0xFF).toByte()
        )
        val vc = ByteArrayOutputStream().apply {
            val vendor = "HiFiDeckTest".toByteArray(Charsets.UTF_8)
            write(leInt(vendor.size)); write(vendor)
            val entries = listOf("TITLE=$title", "ARTIST=$artist")
            write(leInt(entries.size))
            for (e in entries) {
                val eb = e.toByteArray(Charsets.UTF_8)
                write(leInt(eb.size)); write(eb)
            }
        }.toByteArray()
        out.write(0x04) // type 4, not last
        out.write((vc.size ushr 16) and 0xFF)
        out.write((vc.size ushr 8) and 0xFF)
        out.write(vc.size and 0xFF)
        out.write(vc)

        // Block 6: PICTURE (last metadata block)
        val pic = byteArrayOf(0, 0, 0, 3, 1, 2, 3, 4)
        out.write(0x80 or 0x06) // type 6, last
        out.write(0); out.write(0); out.write(pic.size)
        out.write(pic)

        out.write(audioPayload)
        return out.toByteArray()
    }
}

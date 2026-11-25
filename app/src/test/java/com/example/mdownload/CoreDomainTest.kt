package com.example.mdownload

import com.example.mdownload.core.AudioFormat
import com.example.mdownload.core.HistoryRecord
import com.example.mdownload.core.JobOptions
import com.example.mdownload.core.JobStatus
import com.example.mdownload.core.TaskFeedback
import com.example.mdownload.core.TextUtils
import com.example.mdownload.core.YouTubeUrlParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreDomainTest {

    @Test
    fun urlParser_validatesAndNormalizesSupportedYoutubeLinks() {
        assertEquals(
            "https://www.youtube.com/watch?v=JGwWNGJdvx8",
            YouTubeUrlParser.singleVideoUrl("http://youtu.be/JGwWNGJdvx8?t=30#frag")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=abc123XYZ",
            YouTubeUrlParser.singleVideoUrl("https://music.youtube.com/watch?v=abc123XYZ")
        )
        assertEquals(
            "https://www.youtube.com/watch?v=shortId99",
            YouTubeUrlParser.singleVideoUrl("https://www.youtube.com/shorts/shortId99")
        )
        assertFalse(YouTubeUrlParser.isValidUrl("https://www.youtube.com/@SomeChannel"))
        assertFalse(YouTubeUrlParser.isValidUrl("https://localhost/watch?v=JGwWNGJdvx8"))
        assertFalse(YouTubeUrlParser.isValidUrl("https://soundcloud.com/"))
        assertFalse(YouTubeUrlParser.isValidUrl("ftp://soundcloud.com/artist/track"))
    }

    @Test
    fun urlParser_supportsYtDlpSourcesAndSmartSharePayloads() {
        val sc = "https://soundcloud.com/monstercat/crab-rave#frag"
        assertTrue(YouTubeUrlParser.isValidUrl(sc))
        assertEquals("https://soundcloud.com/monstercat/crab-rave", YouTubeUrlParser.singleVideoUrl(sc))
        assertEquals("SoundCloud", YouTubeUrlParser.sourceName(sc))
        assertFalse(YouTubeUrlParser.isPlaylist(sc))

        val scSet = "https://soundcloud.com/monstercat/sets/best-of-2024"
        assertTrue(YouTubeUrlParser.isPlaylist(scSet))
        assertNull(YouTubeUrlParser.singleVideoUrl(scSet))

        val tiktok = "https://vt.tiktok.com/ZS2abcXYZ/"
        assertTrue(YouTubeUrlParser.isValidUrl(tiktok))
        assertEquals("TikTok", YouTubeUrlParser.sourceName(tiktok))

        val ytMusic = "https://music.youtube.com/watch?v=abc123XYZ&si=sharetoken"
        assertEquals("YouTube Music", YouTubeUrlParser.sourceName(ytMusic))

        val payload = YouTubeUrlParser.extractSharePayload(
            sharedText = "Check out \"Nơi Này Có Anh\" https://music.youtube.com/watch?v=FN7ALfpGxiI&si=abc.",
            subject = "Nơi Này Có Anh - YouTube Music"
        )
        assertEquals(listOf("https://music.youtube.com/watch?v=FN7ALfpGxiI&si=abc"), payload.urls)
        assertEquals("Nơi Này Có Anh", payload.titleHint)
    }

    @Test
    fun urlParser_preservesVideoIdForRdMixPlaylists() {
        val mixed = "https://www.youtube.com/watch?v=399s6qrSbgs&list=RDiMzMRhBx9mQ"
        assertTrue(YouTubeUrlParser.isPlaylist(mixed))
        assertTrue(YouTubeUrlParser.isMixPlaylist(mixed))
        assertEquals(
            "https://www.youtube.com/watch?v=399s6qrSbgs",
            YouTubeUrlParser.singleVideoUrl(mixed)
        )
        assertEquals(
            "https://www.youtube.com/watch?v=399s6qrSbgs&list=RDiMzMRhBx9mQ",
            YouTubeUrlParser.playlistUrl(mixed)
        )
        assertNull(YouTubeUrlParser.singleVideoUrl("https://www.youtube.com/playlist?list=PL12345"))
    }

    @Test
    fun urlParser_extractsUrlsFromSharedYoutubeText() {
        val shared = "Check out this song! https://youtu.be/uelHwf8o7_U?si=test123"
        val extracted = YouTubeUrlParser.extractUrlsFromText(shared)
        assertEquals(listOf("https://youtu.be/uelHwf8o7_U?si=test123"), extracted)
        assertEquals("uelHwf8o7_U", YouTubeUrlParser.extractVideoId(extracted.first()))
    }

    @Test
    fun textUtils_handlesVietnameseAsciiAndTitleCleanup() {
        assertEquals(
            "Ha Anh Tuan - Thang Tu La Loi Noi Doi Cua Em",
            TextUtils.asciiText("Hà Anh Tuấn – Tháng Tư Là Lời Nói Dối Của Em")
        )
        assertEquals(
            "Dung Quen Ten Anh",
            TextUtils.asciiText("Đừng Quên Tên Anh")
        )
        assertEquals(
            "Ed Sheeran - Shape of You",
            TextUtils.cleanSourceTitle("Ed Sheeran - Shape of You (Official Music Video)")
        )
        assertEquals(
            "Track Name",
            TextUtils.cleanSourceTitle("Track Name [Lyric Video]")
        )
        assertEquals(
            "Sơn Tùng M-TP - Chúng Ta Của Hiện Tại",
            TextUtils.cleanSourceTitle("Sơn Tùng M-TP - Chúng Ta Của Hiện Tại (Official MV) [4K]")
        )
        assertEquals(
            "Nơi Này Có Anh",
            TextUtils.cleanSourceTitle("Nơi Này Có Anh | Official Lyrics Video")
        )
        assertEquals(
            "Shape of You (Acoustic)",
            TextUtils.cleanSourceTitle("Shape of You (Acoustic) [Official Audio]")
        )
        assertEquals("Sơn Tùng M-TP", TextUtils.cleanArtistName("Sơn Tùng M-TP - Topic"))
        assertEquals("2024", TextUtils.extractYear("", "20240519", "20230101"))
        assertEquals("2021", TextUtils.extractYear("2021", "20240519"))
        assertEquals(
            "https://lh3.googleusercontent.com/abc=w1200-h1200-l90-rj",
            TextUtils.upgradeThumbnailUrl("https://lh3.googleusercontent.com/abc=w120-h120-l90-rj")
        )
        assertEquals(
            "Chung Ta Cua Hien Tai [vid12345678].mp3",
            TextUtils.buildExportFileName(
                title = "Chúng Ta Của Hiện Tại (Official MV) [4K]",
                videoId = "vid12345678",
                extension = "mp3",
                options = JobOptions(AudioFormat.MP3, 128, asciiNames = true)
            )
        )
    }

    @Test
    fun textUtils_profileSuffixesAndArchiveNamesMatchHiFiDeck() {
        val mp3128 = JobOptions(AudioFormat.MP3, 128)
        val mp3192 = JobOptions(AudioFormat.MP3, 192)
        val original = JobOptions(AudioFormat.ORIGINAL, 128)

        assertEquals("", TextUtils.profileSuffix(mp3128))
        assertEquals(".hifideck-archive.txt", TextUtils.archiveFileName(mp3128))

        assertEquals(" - MP3 192k", TextUtils.profileSuffix(mp3192))
        assertEquals(".hifideck-archive-mp3-192.txt", TextUtils.archiveFileName(mp3192))

        assertEquals(" - Original", TextUtils.profileSuffix(original))
        assertEquals(".hifideck-archive-original.txt", TextUtils.archiveFileName(original))
    }

    @Test
    fun taskFeedback_detectsRateLimitAndBotVerification() {
        assertTrue(TaskFeedback.isYoutubeBlocked("ERROR: HTTP Error 429: Too Many Requests"))
        assertTrue(TaskFeedback.isYoutubeBlocked("WARNING: Sign in to confirm you're not a bot"))
        assertFalse(TaskFeedback.isYoutubeBlocked("ERROR: HTTP Error 403: Forbidden"))

        val record = HistoryRecord(
            url = "https://www.youtube.com/watch?v=abc",
            title = "Song",
            status = JobStatus.FAILED,
            detail = "HTTP Error 429: Too Many Requests",
            completedAtIso = "",
            formatLabel = "MP3 128 kbps"
        )
        assertTrue(TaskFeedback.recoveryHint(record).contains("Gentle mode", ignoreCase = true))
    }

    @Test
    fun appSettings_effectiveMultithreadClampingAndGentleOverride() {
        val defaults = com.example.mdownload.core.AppSettings()
        assertFalse(defaults.gentleRequests)
        assertEquals(3, defaults.effectiveParallelDownloads)
        assertEquals(4, defaults.effectiveDownloadThreads)

        val high = defaults.copy(parallelDownloads = 99, downloadThreads = 99)
        assertEquals(com.example.mdownload.core.AppSettings.MAX_PARALLEL_DOWNLOADS, high.effectiveParallelDownloads)
        assertEquals(com.example.mdownload.core.AppSettings.MAX_DOWNLOAD_THREADS, high.effectiveDownloadThreads)

        val gentle = high.copy(gentleRequests = true)
        assertEquals(1, gentle.effectiveParallelDownloads)
        assertEquals(1, gentle.effectiveDownloadThreads)
    }

    @Test
    fun audioTagFingerprint_extractsHiddenVideoIdFromBytesAndFilename() {
        val fakeMp3Header = ByteArray(64) { 0 } +
            "COMM\u0000eng\u0000MDOWNLOAD_YT_ID:dQw4w9WgXcQ\u0000".toByteArray(Charsets.ISO_8859_1) +
            ByteArray(128) { 0x55 }
        assertEquals("dQw4w9WgXcQ", com.example.mdownload.core.AudioTagFingerprint.extractVideoIdFromBytes(fakeMp3Header))

        val legacyPurl = "purl=https://www.youtube.com/watch?v=uelHwf8o7_U".toByteArray(Charsets.ISO_8859_1)
        assertEquals("uelHwf8o7_U", com.example.mdownload.core.AudioTagFingerprint.extractVideoIdFromBytes(legacyPurl))

        assertEquals(
            "399s6qrSbgs",
            com.example.mdownload.core.AudioTagFingerprint.extractVideoIdFromDisplayName("Chán Gái 707 [399s6qrSbgs].m4a")
        )
        assertNull(com.example.mdownload.core.AudioTagFingerprint.extractVideoIdFromDisplayName("Renamed Song.mp3"))
    }

    @Test
    fun libraryQueryHelper_filtersByScopeAccentInsensitivelyAndSortsAscDesc() {
        val tracks = listOf(
            com.example.mdownload.core.LibraryTrack(
                id = 1L, uri = "u1", displayName = "a.mp3",
                title = "Chán Gái 707", artist = "Low G", album = "",
                durationMs = 215_000L, sizeBytes = 3_800_000L, dateAddedSec = 100L, mimeType = "audio/mpeg", videoId = "vid1"
            ),
            com.example.mdownload.core.LibraryTrack(
                id = 2L, uri = "u2", displayName = "b.mp3",
                title = "Ánh Nắng Của Anh", artist = "Đức Phúc", album = "",
                durationMs = 260_000L, sizeBytes = 4_500_000L, dateAddedSec = 300L, mimeType = "audio/mpeg", videoId = "vid2"
            ),
            com.example.mdownload.core.LibraryTrack(
                id = 3L, uri = "u3", displayName = "c.mp3",
                title = "Simp Gái 808", artist = "Low G", album = "",
                durationMs = 161_000L, sizeBytes = 3_200_000L, dateAddedSec = 200L, mimeType = "audio/mpeg", videoId = "vid3"
            )
        )

        // Accent-insensitive song search
        val bySong = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "chan gai",
            com.example.mdownload.core.LibrarySearchScope.TITLE,
            com.example.mdownload.core.LibrarySortField.TITLE,
            com.example.mdownload.core.SortDirection.ASC
        )
        assertEquals(listOf(1L), bySong.map { it.id })

        // Artist-only search does not match song title
        val byArtist = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "duc phuc",
            com.example.mdownload.core.LibrarySearchScope.ARTIST,
            com.example.mdownload.core.LibrarySortField.TITLE,
            com.example.mdownload.core.SortDirection.ASC
        )
        assertEquals(listOf(2L), byArtist.map { it.id })

        val artistNotInTitle = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "707",
            com.example.mdownload.core.LibrarySearchScope.ARTIST,
            com.example.mdownload.core.LibrarySortField.TITLE,
            com.example.mdownload.core.SortDirection.ASC
        )
        assertTrue(artistNotInTitle.isEmpty())

        // Sort by Song ASC vs DESC
        val titleAsc = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "",
            com.example.mdownload.core.LibrarySearchScope.ALL,
            com.example.mdownload.core.LibrarySortField.TITLE,
            com.example.mdownload.core.SortDirection.ASC
        )
        assertEquals(listOf(2L, 1L, 3L), titleAsc.map { it.id })

        val titleDesc = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "",
            com.example.mdownload.core.LibrarySearchScope.ALL,
            com.example.mdownload.core.LibrarySortField.TITLE,
            com.example.mdownload.core.SortDirection.DESC
        )
        assertEquals(listOf(3L, 1L, 2L), titleDesc.map { it.id })

        // Sort by Artist ASC & Size DESC
        val artistAsc = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "",
            com.example.mdownload.core.LibrarySearchScope.ALL,
            com.example.mdownload.core.LibrarySortField.ARTIST,
            com.example.mdownload.core.SortDirection.ASC
        )
        assertEquals(listOf(2L, 1L, 3L), artistAsc.map { it.id })

        val sizeDesc = com.example.mdownload.core.LibraryQueryHelper.filterAndSort(
            tracks, "",
            com.example.mdownload.core.LibrarySearchScope.ALL,
            com.example.mdownload.core.LibrarySortField.SIZE,
            com.example.mdownload.core.SortDirection.DESC
        )
        assertEquals(listOf(2L, 1L, 3L), sizeDesc.map { it.id })
    }

    @Test
    fun storageFolder_formatsTreeDocumentIdsAndEffectiveLabels() {
        assertEquals("Music/EDM", com.example.mdownload.engine.MediaStoreExporter.formatTreeDocumentId("primary:Music/EDM"))
        assertEquals("Internal storage", com.example.mdownload.engine.MediaStoreExporter.formatTreeDocumentId("primary:"))
        assertEquals("SD Card/Nhac", com.example.mdownload.engine.MediaStoreExporter.formatTreeDocumentId("1234-ABCD:Nhac"))
        assertEquals("SD Card (1234-ABCD)", com.example.mdownload.engine.MediaStoreExporter.formatTreeDocumentId("1234-ABCD:"))

        val defaultSettings = com.example.mdownload.core.AppSettings()
        assertEquals("Music/MDownload", defaultSettings.effectiveStorageLabel)

        val customSettings = defaultSettings.copy(
            storageTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FChill",
            storageFolderLabel = "Music/Chill"
        )
        assertEquals("Music/Chill", customSettings.effectiveStorageLabel)

        val customWithoutLabel = defaultSettings.copy(
            storageTreeUri = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FChill",
            storageFolderLabel = ""
        )
        assertEquals("Custom folder", customWithoutLabel.effectiveStorageLabel)
    }

    @Test
    fun queueOrderHelper_reordersQueuedJobsWithoutCrossingActiveDownloads() {
        val active = com.example.mdownload.core.DownloadJob(
            id = "a1", url = "https://www.youtube.com/watch?v=1", title = "Active", status = JobStatus.RUNNING
        )
        val q1 = com.example.mdownload.core.DownloadJob(
            id = "q1", url = "https://www.youtube.com/watch?v=2", title = "Q1", status = JobStatus.QUEUED
        )
        val q2 = com.example.mdownload.core.DownloadJob(
            id = "q2", url = "https://www.youtube.com/watch?v=3", title = "Q2", status = JobStatus.QUEUED
        )
        val q3 = com.example.mdownload.core.DownloadJob(
            id = "q3", url = "https://www.youtube.com/watch?v=4", title = "Q3", status = JobStatus.QUEUED
        )
        val initial = listOf(active, q1, q2, q3)

        // Cannot move active job or move q1 above active job
        assertFalse(com.example.mdownload.core.QueueOrderHelper.canMoveUp(initial, "a1"))
        assertFalse(com.example.mdownload.core.QueueOrderHelper.canMoveUp(initial, "q1"))
        assertTrue(com.example.mdownload.core.QueueOrderHelper.canMoveDown(initial, "q1"))
        assertTrue(com.example.mdownload.core.QueueOrderHelper.canMoveUp(initial, "q3"))
        assertFalse(com.example.mdownload.core.QueueOrderHelper.canMoveDown(initial, "q3"))

        // Move q2 up -> [a1, q2, q1, q3]
        val movedUp = com.example.mdownload.core.QueueOrderHelper.moveQueuedJob(initial, "q2", -1)
        assertEquals(listOf("a1", "q2", "q1", "q3"), movedUp.map { it.id })

        // Move q3 to next -> [a1, q3, q2, q1]
        val movedTop = com.example.mdownload.core.QueueOrderHelper.moveQueuedJobToNext(movedUp, "q3")
        assertEquals(listOf("a1", "q3", "q2", "q1"), movedTop.map { it.id })

        // Move q3 down -> [a1, q2, q3, q1]
        val movedDown = com.example.mdownload.core.QueueOrderHelper.moveQueuedJob(movedTop, "q3", 1)
        assertEquals(listOf("a1", "q2", "q3", "q1"), movedDown.map { it.id })
    }
}

package com.example.mdownload

import com.example.mdownload.core.AppSettings
import com.example.mdownload.core.LibraryQueryHelper
import com.example.mdownload.core.LibrarySearchScope
import com.example.mdownload.core.LibrarySortField
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.SortDirection
import com.example.mdownload.player.library.LibraryGroupingHelper
import com.example.mdownload.player.playback.AudioOutputInfo
import com.example.mdownload.player.playback.PlaybackUiState
import com.example.mdownload.player.playback.RepeatModeOption
import com.example.mdownload.ui.translateText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicPlayerDomainTest {

    private fun sampleTrack(
        id: Long,
        uri: String,
        title: String,
        artist: String = "Artist A",
        album: String = "Album 1",
        displayName: String = "$title.mp3",
        sizeBytes: Long = 5_000_000L,
        durationMs: Long = 210_000L,
        folderPath: String = "Music/MDownload",
        folderName: String = "MDownload",
        videoId: String = "",
        isDownloaded: Boolean = false
    ) = LibraryTrack(
        id = id,
        uri = uri,
        displayName = displayName,
        title = title,
        artist = artist,
        album = album,
        durationMs = durationMs,
        sizeBytes = sizeBytes,
        dateAddedSec = 1000L + id,
        mimeType = "audio/mpeg",
        videoId = videoId,
        albumId = id * 10,
        folderPath = folderPath,
        folderName = folderName,
        isDownloadedByApp = isDownloaded
    )

    @Test
    fun deduplicateTracks_mergesDuplicateUrisAndOverlappingSafMediaStoreEntries() {
        val mediaStore = sampleTrack(
            id = 1L,
            uri = "content://media/external/audio/media/1",
            title = "Midnight",
            album = "",
            displayName = "Midnight.mp3",
            sizeBytes = 4_200_000L,
            isDownloaded = false
        )
        val safCopy = sampleTrack(
            id = 99L,
            uri = "content://com.android.externalstorage.documents/tree/primary/document/Midnight.mp3",
            title = "Midnight",
            album = "After Hours",
            displayName = "Midnight.mp3",
            sizeBytes = 4_200_000L,
            videoId = "dQw4w9WgXcQ",
            isDownloaded = true
        )
        val distinct = sampleTrack(
            id = 2L,
            uri = "content://media/external/audio/media/2",
            title = "Sunrise",
            displayName = "Sunrise.flac",
            sizeBytes = 28_000_000L
        )

        val deduped = LibraryGroupingHelper.deduplicateTracks(listOf(mediaStore, safCopy, distinct))
        assertEquals(2, deduped.size)
        val merged = deduped.first { it.title == "Midnight" }
        assertEquals("After Hours", merged.album)
        assertEquals("dQw4w9WgXcQ", merged.videoId)
        assertTrue(merged.isDownloadedByApp)
    }

    @Test
    fun libraryGrouping_groupsAlbumsArtistsAndFoldersCleanly() {
        val tracks = listOf(
            sampleTrack(1, "u1", "Track 1", artist = "Sơn Tùng M-TP", album = "Sky Tour", folderPath = "Music/MDownload", folderName = "MDownload"),
            sampleTrack(2, "u2", "Track 2", artist = "Sơn Tùng M-TP", album = "Sky Tour", folderPath = "Music/MDownload", folderName = "MDownload"),
            sampleTrack(3, "u3", "Track 3", artist = "Sơn Tùng M-TP", album = "", folderPath = "Music/FLAC", folderName = "FLAC"),
            sampleTrack(4, "u4", "Track 4", artist = "Đen Vâu", album = "Sky Tour", folderPath = "Music/FLAC", folderName = "FLAC")
        )

        val albums = LibraryGroupingHelper.groupAlbums(tracks)
        assertEquals(2, albums.size)
        val skyTour = albums.first { it.title == "Sky Tour" }
        assertEquals(3, skyTour.tracks.size)
        assertEquals("Various Artists", skyTour.artist)
        assertEquals(630_000L, skyTour.totalDurationMs)

        val unknownAlbum = albums.first { it.title == "Singles & Unknown Album" }
        assertEquals(1, unknownAlbum.tracks.size)

        val artists = LibraryGroupingHelper.groupArtists(tracks)
        assertEquals(2, artists.size)
        val sonTung = artists.first { it.name == "Sơn Tùng M-TP" }
        assertEquals(3, sonTung.tracks.size)
        assertEquals(2, sonTung.albumCount)

        val folders = LibraryGroupingHelper.groupFolders(tracks)
        assertEquals(2, folders.size)
        assertEquals(listOf("FLAC", "MDownload"), folders.map { it.displayName })
    }

    @Test
    fun libraryQueryHelper_sortsByAlbumAndMissingAlbumLast() {
        val t1 = sampleTrack(1, "u1", "Song B", album = "Zeta")
        val t2 = sampleTrack(2, "u2", "Song A", album = "Alpha")
        val t3 = sampleTrack(3, "u3", "Song C", album = "")

        val asc = LibraryQueryHelper.filterAndSort(
            listOf(t1, t2, t3),
            query = "",
            scope = LibrarySearchScope.ALL,
            sortField = LibrarySortField.ALBUM,
            sortDirection = SortDirection.ASC
        )
        assertEquals(listOf(2L, 1L, 3L), asc.map { it.id })
    }

    @Test
    fun repeatModeAndAudioOutputInfo_formatAndCycleAccurately() {
        assertEquals(RepeatModeOption.ALL, RepeatModeOption.OFF.next())
        assertEquals(RepeatModeOption.ONE, RepeatModeOption.ALL.next())
        assertEquals(RepeatModeOption.OFF, RepeatModeOption.ONE.next())
        assertEquals(RepeatModeOption.ONE, RepeatModeOption.fromName("one"))
        assertEquals(RepeatModeOption.OFF, RepeatModeOption.fromName("invalid"))

        val flacInfo = AudioOutputInfo(
            codecLabel = "FLAC",
            sampleRateHz = 48_000,
            channelCount = 2,
            bitrateKbps = 950
        )
        assertTrue(flacInfo.isAvailable)
        assertEquals("FLAC · 950 kbps · 48 kHz · Stereo", flacInfo.summaryText)

        val mp3Info = AudioOutputInfo(
            codecLabel = "MP3",
            sampleRateHz = 44_100,
            channelCount = 2,
            bitrateKbps = 320
        )
        assertEquals("MP3 · 320 kbps · 44.1 kHz · Stereo", mp3Info.summaryText)
        assertFalse(AudioOutputInfo().isAvailable)
    }

    @Test
    fun playbackUiState_computesNavigationAvailabilityProperly() {
        val t1 = sampleTrack(1, "u1", "One")
        val t2 = sampleTrack(2, "u2", "Two")

        val idle = PlaybackUiState()
        assertFalse(idle.hasNext)
        assertFalse(idle.hasPrevious)

        val atFirst = PlaybackUiState(currentTrack = t1, queue = listOf(t1, t2), currentIndex = 0, positionMs = 500L)
        assertTrue(atFirst.hasNext)
        assertFalse(atFirst.hasPrevious)

        val atFirstAfter3s = atFirst.copy(positionMs = 5_000L)
        assertTrue(atFirstAfter3s.hasPrevious)

        val atLastWithRepeatAll = PlaybackUiState(
            currentTrack = t2,
            queue = listOf(t1, t2),
            currentIndex = 1,
            repeatMode = RepeatModeOption.ALL
        )
        assertTrue(atLastWithRepeatAll.hasNext)
        assertTrue(atLastWithRepeatAll.hasPrevious)
    }

    @Test
    fun playerSettingsDefaultsAndVietnameseLocalizationWork() {
        val defaults = AppSettings()
        assertTrue(defaults.scanAllDeviceMusic)
        assertTrue(defaults.restorePlaybackOnLaunch)
        assertEquals(1.0f, defaults.defaultPlaybackSpeed, 0.001f)

        assertEquals("Đang phát", translateText("Now playing", true))
        assertEquals("Phát ngay", translateText("Play now", true))
        assertEquals("Thêm vào hàng phát", translateText("Add to queue", true))
        assertEquals("Đã thêm \"Lo-Fi\" vào hàng phát.", translateText("Added \"Lo-Fi\" to playing queue.", true))
    }
}

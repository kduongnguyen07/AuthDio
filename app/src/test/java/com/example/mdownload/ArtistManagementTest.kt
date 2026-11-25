package com.example.mdownload

import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.player.library.ArtistCatalogState
import com.example.mdownload.player.library.ArtistLibraryHelper
import com.example.mdownload.player.library.LibraryGroupingHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArtistManagementTest {

    private fun track(
        uri: String,
        title: String,
        artist: String,
        album: String = ""
    ) = LibraryTrack(
        id = uri.hashCode().toLong(),
        uri = uri,
        displayName = "$title.mp3",
        title = title,
        artist = artist,
        album = album,
        durationMs = 180_000L,
        sizeBytes = 3_000_000L,
        dateAddedSec = 1_700_000_000L,
        mimeType = "audio/mpeg"
    )

    @Test
    fun createCustomArtist_persistsCoverAndAppearsEvenWhenEmpty() {
        val (catalog, created) = ArtistLibraryHelper.createOrUpdateArtist(
            catalog = ArtistCatalogState(),
            name = "Sơn Tùng M-TP",
            coverPath = "/tmp/st_cover.jpg"
        )
        assertNotNull(created)
        assertEquals("Sơn Tùng M-TP", created?.name)
        assertEquals("/tmp/st_cover.jpg", created?.coverPath)

        val groups = LibraryGroupingHelper.groupArtists(emptyList(), catalog.profiles)
        assertEquals(1, groups.size)
        assertEquals("Sơn Tùng M-TP", groups.first().name)
        assertEquals("/tmp/st_cover.jpg", groups.first().customCoverPath)
        assertTrue(groups.first().isCustomProfile)
        assertTrue(groups.first().tracks.isEmpty())
    }

    @Test
    fun editAutoDiscoveredArtist_renamesTracksAndAppliesCustomCover() {
        val raw = listOf(
            track("content://1", "Thu Cuối", "MR.T Official"),
            track("content://2", "Nothing In Your Eyes", "MR.T Official"),
            track("content://3", "Trời Giấu Trời Mang Đi", "ST.319 Entertainment")
        )

        val edited = ArtistLibraryHelper.editArtist(
            catalog = ArtistCatalogState(),
            oldName = "MR.T Official",
            newName = "Mr.T",
            newCoverPath = "/tmp/mrt.jpg"
        )

        val effective = ArtistLibraryHelper.applyCatalog(raw, edited)
        assertEquals("Mr.T", effective[0].artist)
        assertEquals("Mr.T", effective[1].artist)
        assertEquals("ST.319 Entertainment", effective[2].artist)

        val groups = LibraryGroupingHelper.groupArtists(effective, edited.profiles)
        val mrtGroup = groups.first { it.name == "Mr.T" }
        assertEquals(2, mrtGroup.tracks.size)
        assertEquals("/tmp/mrt.jpg", mrtGroup.customCoverPath)
        assertFalse(groups.any { it.name == "MR.T Official" })
    }

    @Test
    fun editArtist_mergesIntoExistingArtistWhenNameMatches() {
        val raw = listOf(
            track("content://1", "Song A", "Mr.T"),
            track("content://2", "Song B", "MR.T Official")
        )

        val (c1, _) = ArtistLibraryHelper.createOrUpdateArtist(
            catalog = ArtistCatalogState(),
            name = "Mr.T",
            coverPath = "/tmp/existing_cover.jpg"
        )
        val merged = ArtistLibraryHelper.editArtist(
            catalog = c1,
            oldName = "MR.T Official",
            newName = "Mr.T"
        )

        val effective = ArtistLibraryHelper.applyCatalog(raw, merged)
        assertTrue(effective.all { it.artist == "Mr.T" })
        val groups = LibraryGroupingHelper.groupArtists(effective, merged.profiles)
        assertEquals(1, groups.size)
        assertEquals(2, groups.first().tracks.size)
        assertEquals("/tmp/existing_cover.jpg", groups.first().customCoverPath)
    }

    @Test
    fun syncArtistTracks_addsMislabeledSongsAndRemovesUncheckedSongs() {
        val raw = listOf(
            track("content://1", "Thu Cuối", "MR.T Official"),
            track("content://2", "Anh Nhà Ở Đâu Thế", "ST.319 Entertainment"),
            track("content://3", "Đen Đá Không Đường", "AMEE")
        )

        val (c1, _) = ArtistLibraryHelper.createOrUpdateArtist(
            catalog = ArtistCatalogState(),
            name = "AMEE",
            coverPath = "/tmp/amee.jpg"
        )

        // Add track 2 (which had wrong artist "ST.319 Entertainment") into AMEE, keep track 3
        val (c2, added1, removed1) = ArtistLibraryHelper.syncArtistTracks(
            catalog = c1,
            artistName = "AMEE",
            selectedTrackUris = setOf("content://2", "content://3"),
            rawTracks = raw
        )
        assertEquals(1, added1)
        assertEquals(0, removed1)
        val eff2 = ArtistLibraryHelper.applyCatalog(raw, c2)
        assertEquals("AMEE", eff2.first { it.uri == "content://2" }.artist)
        assertEquals("AMEE", eff2.first { it.uri == "content://3" }.artist)

        // Now uncheck both track 2 (returns to original "ST.319 Entertainment") and track 3 (original was AMEE -> Unknown artist)
        val (c3, added2, removed2) = ArtistLibraryHelper.syncArtistTracks(
            catalog = c2,
            artistName = "AMEE",
            selectedTrackUris = emptySet(),
            rawTracks = raw
        )
        assertEquals(0, added2)
        assertEquals(2, removed2)
        val eff3 = ArtistLibraryHelper.applyCatalog(raw, c3)
        assertEquals("ST.319 Entertainment", eff3.first { it.uri == "content://2" }.artist)
        assertEquals(ArtistLibraryHelper.UNKNOWN_ARTIST, eff3.first { it.uri == "content://3" }.artist)
    }

    @Test
    fun assignSingleTrackAndReset_restoresOriginalTag() {
        val raw = listOf(track("content://1", "Lạc Trôi", "Sơn Tùng Official Channel"))
        val assigned = ArtistLibraryHelper.assignTrackArtist(
            catalog = ArtistCatalogState(),
            trackUri = "content://1",
            targetArtistName = "Sơn Tùng M-TP"
        )
        assertEquals("Sơn Tùng M-TP", ArtistLibraryHelper.applyCatalog(raw, assigned).first().artist)

        val reset = ArtistLibraryHelper.resetTrackArtist(assigned, "content://1")
        assertEquals("Sơn Tùng Official Channel", ArtistLibraryHelper.applyCatalog(raw, reset).first().artist)
    }

    @Test
    fun deleteArtist_revertsOverridesAndAliasesWithoutDeletingTracks() {
        val raw = listOf(
            track("content://1", "Song 1", "Channel A"),
            track("content://2", "Song 2", "Channel B")
        )
        val renamed = ArtistLibraryHelper.editArtist(
            catalog = ArtistCatalogState(),
            oldName = "Channel A",
            newName = "Real Artist",
            newCoverPath = "/tmp/cover.jpg"
        )
        val assigned = ArtistLibraryHelper.assignTrackArtist(renamed, "content://2", "Real Artist")
        assertTrue(ArtistLibraryHelper.applyCatalog(raw, assigned).all { it.artist == "Real Artist" })

        val (deleted, removedCover) = ArtistLibraryHelper.deleteArtist(assigned, "Real Artist")
        assertEquals("/tmp/cover.jpg", removedCover)
        val reverted = ArtistLibraryHelper.applyCatalog(raw, deleted)
        assertEquals("Channel A", reverted[0].artist)
        assertEquals("Channel B", reverted[1].artist)
    }

    @Test
    fun clearCoverAndRemoveDeletedTrack_cleanStateDeterministically() {
        val (c1, _) = ArtistLibraryHelper.createOrUpdateArtist(
            catalog = ArtistCatalogState(),
            name = "Đen Vâu",
            coverPath = "/tmp/den_cover.jpg",
            initialTrackUris = listOf("content://media/external/audio/media/42")
        )
        val cleared = ArtistLibraryHelper.editArtist(
            catalog = c1,
            oldName = "Đen Vâu",
            newName = "Đen Vâu",
            clearCover = true
        )
        assertEquals("", cleared.profiles.first().coverPath)

        val cleaned = ArtistLibraryHelper.removeDeletedTrack(
            cleared,
            "content://media/external/audio/media/42"
        )
        assertTrue(cleaned.trackArtistOverrides.isEmpty())
    }
}

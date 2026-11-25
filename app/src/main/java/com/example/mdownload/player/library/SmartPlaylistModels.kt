package com.example.mdownload.player.library

import java.util.UUID

enum class MatchMode(val label: String) {
    ALL("Match all rules (AND)"),
    ANY("Match any rule (OR)")
}

enum class PlaylistSortOrder(val label: String) {
    DATE_ADDED_DESC("Date added (newest)"),
    LAST_PLAYED_DESC("Recently played"),
    PLAY_COUNT_DESC("Most played"),
    TITLE_ASC("Title (A–Z)"),
    ARTIST_ASC("Artist (A–Z)"),
    DURATION_DESC("Longest first"),
    BITRATE_DESC("Highest bitrate")
}

enum class SmartRuleType(val label: String, val valueHint: String) {
    ARTIST_EQUALS("Artist equals", "e.g. BIGBANG"),
    ARTIST_CONTAINS("Artist contains", "e.g. Flume"),
    ALBUM_EQUALS("Album equals", "e.g. Skin"),
    GENRE_EQUALS("Genre equals", "e.g. Electronic"),
    DATE_ADDED_WITHIN_DAYS("Added within days", "e.g. 30"),
    PLAY_COUNT_AT_LEAST("Play count ≥", "e.g. 3"),
    PLAY_COUNT_AT_MOST("Play count ≤", "e.g. 0"),
    LAST_PLAYED_WITHIN_DAYS("Played within days", "e.g. 14"),
    HAS_BEEN_PLAYED("Has been played", "true / false"),
    IS_FAVORITE("Is favorite", "true / false"),
    CODEC_EQUALS("Codec equals", "e.g. FLAC, MP3, Opus"),
    IS_LOSSLESS_CODEC("Lossless codec", "true / false"),
    BITRATE_AT_LEAST_KBPS("Bitrate ≥ (kbps)", "e.g. 256"),
    DURATION_AT_LEAST_SEC("Duration ≥ (sec)", "e.g. 180"),
    DURATION_AT_MOST_SEC("Duration ≤ (sec)", "e.g. 300"),
    DOWNLOADED_BY_APP("Downloaded by AuthDio", "true / false")
}

data class PlaylistRule(
    val type: SmartRuleType,
    val stringValue: String = "",
    val intValue: Int = 0,
    val boolValue: Boolean = true
) {
    fun describe(): String = when (type) {
        SmartRuleType.ARTIST_EQUALS -> "Artist = \"$stringValue\""
        SmartRuleType.ARTIST_CONTAINS -> "Artist contains \"$stringValue\""
        SmartRuleType.ALBUM_EQUALS -> "Album = \"$stringValue\""
        SmartRuleType.GENRE_EQUALS -> "Genre = \"$stringValue\""
        SmartRuleType.DATE_ADDED_WITHIN_DAYS -> "Added in last $intValue day(s)"
        SmartRuleType.PLAY_COUNT_AT_LEAST -> "Plays ≥ $intValue"
        SmartRuleType.PLAY_COUNT_AT_MOST -> "Plays ≤ $intValue"
        SmartRuleType.LAST_PLAYED_WITHIN_DAYS -> "Played in last $intValue day(s)"
        SmartRuleType.HAS_BEEN_PLAYED -> if (boolValue) "Has been played" else "Never played"
        SmartRuleType.IS_FAVORITE -> if (boolValue) "Favorited" else "Not favorited"
        SmartRuleType.CODEC_EQUALS -> "Codec = ${stringValue.uppercase()}"
        SmartRuleType.IS_LOSSLESS_CODEC -> if (boolValue) "Lossless codec" else "Lossy codec"
        SmartRuleType.BITRATE_AT_LEAST_KBPS -> "Bitrate ≥ $intValue kbps"
        SmartRuleType.DURATION_AT_LEAST_SEC -> "Duration ≥ ${intValue}s"
        SmartRuleType.DURATION_AT_MOST_SEC -> "Duration ≤ ${intValue}s"
        SmartRuleType.DOWNLOADED_BY_APP -> if (boolValue) "Downloaded via AuthDio" else "External device file"
    }
}

data class SmartPlaylistDefinition(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val description: String = "",
    val rules: List<PlaylistRule>,
    val matchMode: MatchMode = MatchMode.ALL,
    val sortOrder: PlaylistSortOrder = PlaylistSortOrder.DATE_ADDED_DESC,
    val limit: Int? = null,
    val isBuiltIn: Boolean = false
)

object BuiltInSmartPlaylists {

    const val ID_RECENTLY_ADDED = "builtin_recently_added"
    const val ID_RECENTLY_PLAYED = "builtin_recently_played"
    const val ID_MOST_PLAYED = "builtin_most_played"
    const val ID_NEVER_PLAYED = "builtin_never_played"
    const val ID_FAVORITES = "builtin_favorites"
    const val ID_HIGH_QUALITY = "builtin_high_quality"
    const val ID_RECENTLY_DOWNLOADED = "builtin_recently_downloaded"

    fun all(): List<SmartPlaylistDefinition> = listOf(
        SmartPlaylistDefinition(
            id = ID_RECENTLY_ADDED,
            name = "Recently Added",
            description = "Added within the last 30 days",
            rules = listOf(PlaylistRule(SmartRuleType.DATE_ADDED_WITHIN_DAYS, intValue = 30)),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.DATE_ADDED_DESC,
            limit = 50,
            isBuiltIn = true
        ),
        SmartPlaylistDefinition(
            id = ID_RECENTLY_PLAYED,
            name = "Recently Played",
            description = "Tracks with recent qualified plays",
            rules = listOf(PlaylistRule(SmartRuleType.HAS_BEEN_PLAYED, boolValue = true)),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.LAST_PLAYED_DESC,
            limit = 50,
            isBuiltIn = true
        ),
        SmartPlaylistDefinition(
            id = ID_MOST_PLAYED,
            name = "Most Played",
            description = "Ranked by qualified play count",
            rules = listOf(PlaylistRule(SmartRuleType.PLAY_COUNT_AT_LEAST, intValue = 1)),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.PLAY_COUNT_DESC,
            limit = 50,
            isBuiltIn = true
        ),
        SmartPlaylistDefinition(
            id = ID_NEVER_PLAYED,
            name = "Never Played",
            description = "Tracks with zero recorded plays",
            rules = listOf(PlaylistRule(SmartRuleType.PLAY_COUNT_AT_MOST, intValue = 0)),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.DATE_ADDED_DESC,
            limit = null,
            isBuiltIn = true
        ),
        SmartPlaylistDefinition(
            id = ID_FAVORITES,
            name = "Favorites",
            description = "Tracks marked with the heart icon",
            rules = listOf(PlaylistRule(SmartRuleType.IS_FAVORITE, boolValue = true)),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.DATE_ADDED_DESC,
            limit = null,
            isBuiltIn = true
        ),
        SmartPlaylistDefinition(
            id = ID_HIGH_QUALITY,
            name = "High Quality",
            description = "Lossless codecs (FLAC/ALAC/WAV) or ≥ 256 kbps (codec only; does not guarantee lossless master)",
            rules = listOf(
                PlaylistRule(SmartRuleType.IS_LOSSLESS_CODEC, boolValue = true),
                PlaylistRule(SmartRuleType.BITRATE_AT_LEAST_KBPS, intValue = 256)
            ),
            matchMode = MatchMode.ANY,
            sortOrder = PlaylistSortOrder.BITRATE_DESC,
            limit = null,
            isBuiltIn = true
        ),
        SmartPlaylistDefinition(
            id = ID_RECENTLY_DOWNLOADED,
            name = "Recently Downloaded",
            description = "Tracks downloaded through AuthDio",
            rules = listOf(PlaylistRule(SmartRuleType.DOWNLOADED_BY_APP, boolValue = true)),
            matchMode = MatchMode.ALL,
            sortOrder = PlaylistSortOrder.DATE_ADDED_DESC,
            limit = 50,
            isBuiltIn = true
        )
    )
}

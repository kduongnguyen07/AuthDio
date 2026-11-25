package com.example.mdownload.core

import java.util.Locale

enum class LibrarySearchScope(val label: String) {
    ALL("All"),
    TITLE("Song"),
    ARTIST("Artist")
}

enum class LibrarySortField(val label: String) {
    DATE_ADDED("Date"),
    TITLE("Song"),
    ARTIST("Artist"),
    ALBUM("Album"),
    DURATION("Duration"),
    SIZE("Size")
}

enum class SortDirection(val label: String) {
    DESC("Desc"),
    ASC("Asc");

    fun toggled(): SortDirection = if (this == DESC) ASC else DESC
}

/**
 * Accent-insensitive search (by all fields, song title, or artist) and ASC/DESC sorting
 * for the offline music library.
 */
object LibraryQueryHelper {

    fun filterAndSort(
        tracks: List<LibraryTrack>,
        query: String,
        scope: LibrarySearchScope,
        sortField: LibrarySortField,
        sortDirection: SortDirection
    ): List<LibraryTrack> {
        val rawQuery = query.trim().lowercase(Locale.ROOT)
        val asciiQuery = TextUtils.asciiText(rawQuery).lowercase(Locale.ROOT)

        val filtered = if (rawQuery.isEmpty()) {
            tracks
        } else {
            tracks.filter { track ->
                when (scope) {
                    LibrarySearchScope.ALL ->
                        matches(track.title, rawQuery, asciiQuery) ||
                            matches(track.artist, rawQuery, asciiQuery) ||
                            matches(track.album, rawQuery, asciiQuery) ||
                            matches(track.displayName, rawQuery, asciiQuery) ||
                            (track.videoId.isNotBlank() && track.videoId.lowercase(Locale.ROOT).contains(rawQuery))

                    LibrarySearchScope.TITLE ->
                        matches(track.title, rawQuery, asciiQuery)

                    LibrarySearchScope.ARTIST ->
                        matches(track.artist, rawQuery, asciiQuery)
                }
            }
        }

        val comparator = when (sortField) {
            LibrarySortField.DATE_ADDED -> compareBy<LibraryTrack> { it.dateAddedSec }
                .thenBy { normalizeForSort(it.title) }

            LibrarySortField.TITLE -> compareBy<LibraryTrack> { normalizeForSort(it.title) }
                .thenBy { normalizeForSort(it.artist) }

            LibrarySortField.ARTIST -> compareBy<LibraryTrack> { normalizeForSort(it.artist) }
                .thenBy { normalizeForSort(it.title) }

            LibrarySortField.ALBUM -> compareBy<LibraryTrack> { normalizeForSort(it.album.ifBlank { "zzz" }) }
                .thenBy { normalizeForSort(it.title) }

            LibrarySortField.DURATION -> compareBy<LibraryTrack> { it.durationMs }
                .thenBy { normalizeForSort(it.title) }

            LibrarySortField.SIZE -> compareBy<LibraryTrack> { it.sizeBytes }
                .thenBy { normalizeForSort(it.title) }
        }

        return if (sortDirection == SortDirection.ASC) {
            filtered.sortedWith(comparator)
        } else {
            filtered.sortedWith(comparator.reversed())
        }
    }

    private fun matches(field: String, rawQuery: String, asciiQuery: String): Boolean {
        if (field.isBlank()) return false
        val lower = field.lowercase(Locale.ROOT)
        if (lower.contains(rawQuery)) return true
        if (asciiQuery.isNotBlank()) {
            val asciiField = TextUtils.asciiText(lower).lowercase(Locale.ROOT)
            if (asciiField.contains(asciiQuery)) return true
        }
        return false
    }

    private fun normalizeForSort(value: String): String =
        TextUtils.asciiText(value).lowercase(Locale.ROOT).ifBlank { value.lowercase(Locale.ROOT) }
}

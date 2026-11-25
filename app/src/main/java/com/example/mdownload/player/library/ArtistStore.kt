package com.example.mdownload.player.library

import android.content.Context
import com.example.mdownload.core.LibraryTrack
import com.example.mdownload.core.TextUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.UUID

data class CustomArtistProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val coverPath: String = "",
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

data class ArtistCatalogState(
    val profiles: List<CustomArtistProfile> = emptyList(),
    /** Explicit per-track artist override: trackUri -> display artist name. */
    val trackArtistOverrides: Map<String, String> = emptyMap(),
    /** Artist-level rename alias: normalized original tag artist key -> target display artist name. */
    val artistNameAliases: Map<String, String> = emptyMap()
)

/**
 * Pure, deterministic artist catalog mutations & track-artist resolution logic.
 */
object ArtistLibraryHelper {

    const val UNKNOWN_ARTIST = "Unknown artist"

    fun normalizeKey(raw: String): String =
        TextUtils.asciiText(raw).trim().lowercase(Locale.ROOT)
            .ifBlank { raw.trim().lowercase(Locale.ROOT) }

    fun effectiveArtistForTrack(
        track: LibraryTrack,
        catalog: ArtistCatalogState
    ): String {
        catalog.trackArtistOverrides[track.uri]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val origKey = normalizeKey(track.artist.trim().ifBlank { UNKNOWN_ARTIST })
        catalog.artistNameAliases[origKey]?.trim()?.takeIf { it.isNotBlank() }?.let { return it }
        val profileExact = catalog.profiles.firstOrNull { normalizeKey(it.name) == origKey }
        if (profileExact != null) return profileExact.name
        return track.artist.trim().ifBlank { UNKNOWN_ARTIST }
    }

    fun applyCatalog(
        rawTracks: List<LibraryTrack>,
        catalog: ArtistCatalogState
    ): List<LibraryTrack> {
        if (
            catalog.profiles.isEmpty() &&
            catalog.trackArtistOverrides.isEmpty() &&
            catalog.artistNameAliases.isEmpty()
        ) {
            return rawTracks
        }
        return rawTracks.map { track ->
            val resolved = effectiveArtistForTrack(track, catalog)
            if (resolved == track.artist) track else track.copy(artist = resolved)
        }
    }

    /**
     * Creates a new custom artist profile or updates an existing profile with the same normalized name.
     * Optionally assigns [initialTrackUris] to this artist.
     */
    fun createOrUpdateArtist(
        catalog: ArtistCatalogState,
        name: String,
        coverPath: String = "",
        initialTrackUris: List<String> = emptyList(),
        nowMs: Long = System.currentTimeMillis()
    ): Pair<ArtistCatalogState, CustomArtistProfile?> {
        val cleanName = name.trim()
        if (cleanName.isBlank()) return catalog to null
        val targetKey = normalizeKey(cleanName)

        val existing = catalog.profiles.firstOrNull { normalizeKey(it.name) == targetKey }
        val updatedProfile = if (existing != null) {
            existing.copy(
                name = cleanName,
                coverPath = coverPath.ifBlank { existing.coverPath },
                updatedAtMs = nowMs
            )
        } else {
            CustomArtistProfile(
                name = cleanName,
                coverPath = coverPath.trim(),
                createdAtMs = nowMs,
                updatedAtMs = nowMs
            )
        }

        val updatedProfiles = catalog.profiles
            .filterNot { normalizeKey(it.name) == targetKey } + updatedProfile

        val updatedOverrides = catalog.trackArtistOverrides.toMutableMap()
        initialTrackUris.filter { it.isNotBlank() }.forEach { uri ->
            updatedOverrides[uri] = cleanName
        }

        // Update any existing overrides/aliases that matched case-insensitively to use the latest display casing.
        for ((uri, value) in updatedOverrides.toList()) {
            if (normalizeKey(value) == targetKey) {
                updatedOverrides[uri] = cleanName
            }
        }
        val updatedAliases = catalog.artistNameAliases.mapValues { (_, value) ->
            if (normalizeKey(value) == targetKey) cleanName else value
        }

        return catalog.copy(
            profiles = updatedProfiles,
            trackArtistOverrides = updatedOverrides,
            artistNameAliases = updatedAliases
        ) to updatedProfile
    }

    /**
     * Edits an existing artist's name and/or cover image.
     * Supports renaming auto-discovered artists, renaming custom artists, and merging into an
     * existing artist if [newName] matches another artist's normalized key.
     */
    fun editArtist(
        catalog: ArtistCatalogState,
        oldName: String,
        newName: String,
        newCoverPath: String? = null,
        clearCover: Boolean = false,
        nowMs: Long = System.currentTimeMillis()
    ): ArtistCatalogState {
        val cleanOld = oldName.trim()
        val cleanNew = newName.trim()
        if (cleanOld.isBlank() || cleanNew.isBlank()) return catalog

        val oldKey = normalizeKey(cleanOld)
        val newKey = normalizeKey(cleanNew)

        val oldProfile = catalog.profiles.firstOrNull { normalizeKey(it.name) == oldKey }
        val targetProfile = catalog.profiles.firstOrNull { normalizeKey(it.name) == newKey }

        val resolvedCover = when {
            clearCover -> ""
            !newCoverPath.isNullOrBlank() -> newCoverPath.trim()
            oldProfile != null && oldProfile.coverPath.isNotBlank() -> oldProfile.coverPath
            targetProfile != null && targetProfile.coverPath.isNotBlank() -> targetProfile.coverPath
            else -> ""
        }

        val mergedProfile = CustomArtistProfile(
            id = targetProfile?.id ?: oldProfile?.id ?: UUID.randomUUID().toString(),
            name = cleanNew,
            coverPath = resolvedCover,
            createdAtMs = targetProfile?.createdAtMs ?: oldProfile?.createdAtMs ?: nowMs,
            updatedAtMs = nowMs
        )

        val updatedProfiles = catalog.profiles
            .filterNot {
                val k = normalizeKey(it.name)
                k == oldKey || k == newKey
            } + mergedProfile

        val updatedOverrides = catalog.trackArtistOverrides.mapValues { (_, assigned) ->
            val k = normalizeKey(assigned)
            if (k == oldKey || k == newKey) cleanNew else assigned
        }

        val updatedAliases = catalog.artistNameAliases.toMutableMap()
        // Remap any existing alias that pointed to oldKey or newKey
        for ((k, v) in updatedAliases.toList()) {
            val vk = normalizeKey(v)
            if (vk == oldKey || vk == newKey) {
                updatedAliases[k] = cleanNew
            }
        }
        // If the artist name actually changed (or casing changed), record an alias from oldKey -> cleanNew
        if (oldKey != newKey || cleanOld != cleanNew) {
            updatedAliases[oldKey] = cleanNew
        }
        // Remove redundant self-alias if exact match
        if (updatedAliases[newKey] == cleanNew && oldKey == newKey && cleanOld == cleanNew) {
            updatedAliases.remove(newKey)
        }

        return catalog.copy(
            profiles = updatedProfiles,
            trackArtistOverrides = updatedOverrides,
            artistNameAliases = updatedAliases
        )
    }

    /**
     * Deletes a custom artist profile and reverts any overrides/aliases targeting it back to the
     * tracks' original file tags. Never deletes local audio files.
     */
    fun deleteArtist(
        catalog: ArtistCatalogState,
        artistName: String
    ): Pair<ArtistCatalogState, String?> {
        val targetKey = normalizeKey(artistName)
        val removedProfile = catalog.profiles.firstOrNull { normalizeKey(it.name) == targetKey }
        val updatedProfiles = catalog.profiles.filterNot { normalizeKey(it.name) == targetKey }
        val updatedOverrides = catalog.trackArtistOverrides.filterValues { normalizeKey(it) != targetKey }
        val updatedAliases = catalog.artistNameAliases.filterValues { normalizeKey(it) != targetKey }

        return catalog.copy(
            profiles = updatedProfiles,
            trackArtistOverrides = updatedOverrides,
            artistNameAliases = updatedAliases
        ) to removedProfile?.coverPath?.takeIf { it.isNotBlank() }
    }

    /**
     * Assigns a single track to [targetArtistName].
     */
    fun assignTrackArtist(
        catalog: ArtistCatalogState,
        trackUri: String,
        targetArtistName: String,
        nowMs: Long = System.currentTimeMillis()
    ): ArtistCatalogState {
        val cleanName = targetArtistName.trim()
        if (trackUri.isBlank() || cleanName.isBlank()) return catalog
        val targetKey = normalizeKey(cleanName)
        val existingProfile = catalog.profiles.firstOrNull { normalizeKey(it.name) == targetKey }
        val displayName = existingProfile?.name ?: cleanName
        val updatedOverrides = catalog.trackArtistOverrides.toMutableMap()
        updatedOverrides[trackUri] = displayName
        return catalog.copy(
            trackArtistOverrides = updatedOverrides,
            profiles = if (existingProfile != null) {
                catalog.profiles.map {
                    if (it.id == existingProfile.id) it.copy(updatedAtMs = nowMs) else it
                }
            } else {
                catalog.profiles
            }
        )
    }

    /**
     * Clears any manual artist override for [trackUri], restoring its original tag or alias.
     */
    fun resetTrackArtist(
        catalog: ArtistCatalogState,
        trackUri: String
    ): ArtistCatalogState {
        if (trackUri !in catalog.trackArtistOverrides) return catalog
        val updated = catalog.trackArtistOverrides.toMutableMap()
        updated.remove(trackUri)
        return catalog.copy(trackArtistOverrides = updated)
    }

    /**
     * Synchronizes the exact membership of [selectedTrackUris] for [artistName]:
     * - Checked tracks not yet in [artistName] are explicitly assigned to [artistName].
     * - Previously belonging tracks that are now unchecked are removed from [artistName]:
     *   if they had an override whose original tag belongs elsewhere, the override is cleared;
     *   otherwise they are reassigned to [UNKNOWN_ARTIST] so unchecking always takes effect.
     *
     * Returns the updated catalog plus (addedCount, removedCount).
     */
    fun syncArtistTracks(
        catalog: ArtistCatalogState,
        artistName: String,
        selectedTrackUris: Set<String>,
        rawTracks: List<LibraryTrack>
    ): Triple<ArtistCatalogState, Int, Int> {
        val cleanName = artistName.trim()
        if (cleanName.isBlank()) return Triple(catalog, 0, 0)
        val targetKey = normalizeKey(cleanName)
        val displayName = catalog.profiles.firstOrNull { normalizeKey(it.name) == targetKey }?.name ?: cleanName

        val updatedOverrides = catalog.trackArtistOverrides.toMutableMap()
        var added = 0
        var removed = 0

        for (raw in rawTracks) {
            val currentlyEffective = effectiveArtistForTrack(raw, catalog)
            val currentlyInArtist = normalizeKey(currentlyEffective) == targetKey
            val wantsInArtist = raw.uri in selectedTrackUris

            if (wantsInArtist && !currentlyInArtist) {
                updatedOverrides[raw.uri] = displayName
                added++
            } else if (!wantsInArtist && currentlyInArtist) {
                // Remove override first and see where the raw tag + alias would place it
                val withoutOverride = catalog.copy(trackArtistOverrides = updatedOverrides - raw.uri)
                val fallbackArtist = effectiveArtistForTrack(raw, withoutOverride)
                if (normalizeKey(fallbackArtist) == targetKey) {
                    // Original tag (or alias) also points to this artist; move to Unknown artist
                    updatedOverrides[raw.uri] = UNKNOWN_ARTIST
                } else {
                    updatedOverrides.remove(raw.uri)
                }
                removed++
            }
        }

        return Triple(
            catalog.copy(trackArtistOverrides = updatedOverrides),
            added,
            removed
        )
    }

    fun removeDeletedTrack(
        catalog: ArtistCatalogState,
        trackUri: String
    ): ArtistCatalogState {
        if (trackUri !in catalog.trackArtistOverrides) return catalog
        return catalog.copy(trackArtistOverrides = catalog.trackArtistOverrides - trackUri)
    }
}

/**
 * Atomic JSON persistence for custom artists (name + cover image), per-track artist overrides,
 * and artist rename aliases.
 */
class ArtistStore(private val file: File) {

    constructor(context: Context) : this(
        File(File(context.filesDir, "hifideck_data"), "artists.json")
    )

    private val lock = Any()

    fun load(): ArtistCatalogState = synchronized(lock) {
        if (!file.isFile) return ArtistCatalogState()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))

            val profilesArr = root.optJSONArray("profiles") ?: JSONArray()
            val profiles = mutableListOf<CustomArtistProfile>()
            for (i in 0 until profilesArr.length()) {
                val obj = profilesArr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isBlank()) continue
                profiles.add(
                    CustomArtistProfile(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        coverPath = obj.optString("cover_path", "").trim(),
                        createdAtMs = obj.optLong("created_at_ms", 0L),
                        updatedAtMs = obj.optLong("updated_at_ms", 0L)
                    )
                )
            }

            val overridesObj = root.optJSONObject("track_artist_overrides") ?: JSONObject()
            val overrides = LinkedHashMap<String, String>()
            overridesObj.keys().forEach { uri ->
                val artist = overridesObj.optString(uri, "").trim()
                if (uri.isNotBlank() && artist.isNotBlank()) {
                    overrides[uri] = artist
                }
            }

            val aliasesObj = root.optJSONObject("artist_name_aliases") ?: JSONObject()
            val aliases = LinkedHashMap<String, String>()
            aliasesObj.keys().forEach { key ->
                val target = aliasesObj.optString(key, "").trim()
                if (key.isNotBlank() && target.isNotBlank()) {
                    aliases[key] = target
                }
            }

            ArtistCatalogState(
                profiles = profiles,
                trackArtistOverrides = overrides,
                artistNameAliases = aliases
            )
        }.getOrDefault(ArtistCatalogState())
    }

    fun save(state: ArtistCatalogState) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("profiles", JSONArray().apply {
                    state.profiles.forEach { profile ->
                        put(JSONObject().apply {
                            put("id", profile.id)
                            put("name", profile.name)
                            put("cover_path", profile.coverPath)
                            put("created_at_ms", profile.createdAtMs)
                            put("updated_at_ms", profile.updatedAtMs)
                        })
                    }
                })
                put("track_artist_overrides", JSONObject().apply {
                    state.trackArtistOverrides.forEach { (uri, artist) ->
                        put(uri, artist)
                    }
                })
                put("artist_name_aliases", JSONObject().apply {
                    state.artistNameAliases.forEach { (fromKey, toName) ->
                        put(fromKey, toName)
                    }
                })
            }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}

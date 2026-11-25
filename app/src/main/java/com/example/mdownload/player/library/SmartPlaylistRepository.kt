package com.example.mdownload.player.library

import android.content.Context
import com.example.mdownload.core.RepositoryLoadState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Persists custom Smart Playlist definitions and combines them with the 7 built-in playlists.
 *
 * Production [Context] constructor hydrates asynchronously on [Dispatchers.IO] without blocking
 * Main-Thread startup, while guarding every mutation with [ensureHydratedLocked] so early writes
 * never overwrite persisted custom definitions.
 */
class SmartPlaylistRepository(
    private val file: File,
    loadAsync: Boolean = false,
    ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(context: Context) : this(
        file = File(File(context.filesDir, "hifideck_data"), "smart_playlists.json"),
        loadAsync = true
    )

    private val lock = Any()
    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher)
    private val readyDeferred = CompletableDeferred<Unit>()

    private val _loadState = MutableStateFlow(
        if (loadAsync) RepositoryLoadState.LOADING else RepositoryLoadState.READY
    )
    val loadState: StateFlow<RepositoryLoadState> = _loadState.asStateFlow()

    private val _customPlaylists = MutableStateFlow<List<SmartPlaylistDefinition>>(emptyList())
    val customPlaylists: StateFlow<List<SmartPlaylistDefinition>> = _customPlaylists.asStateFlow()

    val builtInPlaylists: List<SmartPlaylistDefinition> = BuiltInSmartPlaylists.all()

    private var hydrated: Boolean = false

    init {
        if (loadAsync) {
            scope.launch(ioDispatcher) {
                hydrateFromDisk()
            }
        } else {
            hydrateFromDisk()
        }
    }

    suspend fun awaitReady() {
        readyDeferred.await()
    }

    fun allDefinitions(): List<SmartPlaylistDefinition> = builtInPlaylists + _customPlaylists.value

    fun findById(id: String): SmartPlaylistDefinition? =
        builtInPlaylists.firstOrNull { it.id == id }
            ?: _customPlaylists.value.firstOrNull { it.id == id }

    fun createOrUpdateCustom(definition: SmartPlaylistDefinition): SmartPlaylistDefinition? {
        val cleanName = definition.name.trim()
        if (cleanName.isBlank()) return null
        val sanitized = definition.copy(
            id = definition.id.ifBlank { UUID.randomUUID().toString() },
            name = cleanName,
            isBuiltIn = false,
            limit = definition.limit?.takeIf { it > 0 }
        )
        synchronized(lock) {
            ensureHydratedLocked()
            val list = _customPlaylists.value.toMutableList()
            val existingIdx = list.indexOfFirst { it.id == sanitized.id }
            if (existingIdx >= 0) {
                list[existingIdx] = sanitized
            } else {
                list.add(0, sanitized)
            }
            _customPlaylists.value = list
            saveToDiskLocked(list)
        }
        return sanitized
    }

    fun renameCustom(id: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isBlank()) return false
        synchronized(lock) {
            ensureHydratedLocked()
            val list = _customPlaylists.value.toMutableList()
            val idx = list.indexOfFirst { it.id == id }
            if (idx < 0) return false
            list[idx] = list[idx].copy(name = clean)
            _customPlaylists.value = list
            saveToDiskLocked(list)
            return true
        }
    }

    fun deleteCustom(id: String): Boolean {
        synchronized(lock) {
            ensureHydratedLocked()
            val list = _customPlaylists.value
            val filtered = list.filterNot { it.id == id }
            if (filtered.size == list.size) return false
            _customPlaylists.value = filtered
            saveToDiskLocked(filtered)
            return true
        }
    }

    private fun hydrateFromDisk() {
        synchronized(lock) {
            ensureHydratedLocked()
        }
    }

    private fun ensureHydratedLocked() {
        if (hydrated) return
        var failed = false
        if (file.isFile) {
            runCatching {
                val root = JSONObject(file.readText(Charsets.UTF_8))
                val arr = root.optJSONArray("smart_playlists")
                if (arr != null) {
                    val list = mutableListOf<SmartPlaylistDefinition>()
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val name = obj.optString("name", "").trim()
                        if (name.isBlank()) continue
                        val rulesArr = obj.optJSONArray("rules") ?: JSONArray()
                        val rules = mutableListOf<PlaylistRule>()
                        for (j in 0 until rulesArr.length()) {
                            val ro = rulesArr.optJSONObject(j) ?: continue
                            val type = SmartRuleType.entries.firstOrNull { it.name == ro.optString("type") } ?: continue
                            rules.add(
                                PlaylistRule(
                                    type = type,
                                    stringValue = ro.optString("string_value", ""),
                                    intValue = ro.optInt("int_value", 0),
                                    boolValue = ro.optBoolean("bool_value", true)
                                )
                            )
                        }
                        list.add(
                            SmartPlaylistDefinition(
                                id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                                name = name,
                                description = obj.optString("description", ""),
                                rules = rules,
                                matchMode = MatchMode.entries.firstOrNull { it.name == obj.optString("match_mode") }
                                    ?: MatchMode.ALL,
                                sortOrder = PlaylistSortOrder.entries.firstOrNull { it.name == obj.optString("sort_order") }
                                    ?: PlaylistSortOrder.DATE_ADDED_DESC,
                                limit = if (obj.has("limit")) obj.optInt("limit").takeIf { it > 0 } else null,
                                isBuiltIn = false
                            )
                        )
                    }
                    _customPlaylists.value = list
                }
            }.onFailure {
                failed = true
            }
        }
        hydrated = true
        _loadState.value = if (failed) RepositoryLoadState.FAILED else RepositoryLoadState.READY
        readyDeferred.complete(Unit)
    }

    private fun saveToDiskLocked(list: List<SmartPlaylistDefinition>) {
        runCatching {
            file.parentFile?.mkdirs()
            val arr = JSONArray()
            for (pl in list) {
                arr.put(JSONObject().apply {
                    put("id", pl.id)
                    put("name", pl.name)
                    put("description", pl.description)
                    put("match_mode", pl.matchMode.name)
                    put("sort_order", pl.sortOrder.name)
                    pl.limit?.let { put("limit", it) }
                    put("rules", JSONArray().apply {
                        pl.rules.forEach { r ->
                            put(JSONObject().apply {
                                put("type", r.type.name)
                                put("string_value", r.stringValue)
                                put("int_value", r.intValue)
                                put("bool_value", r.boolValue)
                            })
                        }
                    })
                })
            }
            val root = JSONObject().apply { put("smart_playlists", arr) }
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(root.toString(2), Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(root.toString(2), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }
}

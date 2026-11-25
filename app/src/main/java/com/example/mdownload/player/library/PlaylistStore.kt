package com.example.mdownload.player.library

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class UserPlaylist(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val trackUris: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
)

/**
 * Atomic JSON persistence for offline user-created music playlists.
 */
class PlaylistStore(private val file: File) {

    constructor(context: Context) : this(
        File(File(context.filesDir, "hifideck_data"), "playlists.json")
    )

    private val lock = Any()

    fun load(): List<UserPlaylist> = synchronized(lock) {
        if (!file.isFile) return emptyList()
        return runCatching {
            val root = JSONObject(file.readText(Charsets.UTF_8))
            val arr = root.optJSONArray("playlists") ?: JSONArray()
            val list = mutableListOf<UserPlaylist>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val name = obj.optString("name", "").trim()
                if (name.isBlank()) continue
                val uriArr = obj.optJSONArray("track_uris") ?: JSONArray()
                val uris = mutableListOf<String>()
                for (j in 0 until uriArr.length()) {
                    val uri = uriArr.optString(j, "")
                    if (uri.isNotBlank()) uris.add(uri)
                }
                list.add(
                    UserPlaylist(
                        id = obj.optString("id").ifBlank { UUID.randomUUID().toString() },
                        name = name,
                        trackUris = uris.distinct(),
                        createdAtMs = obj.optLong("created_at_ms", 0L),
                        updatedAtMs = obj.optLong("updated_at_ms", 0L)
                    )
                )
            }
            list
        }.getOrDefault(emptyList())
    }

    fun save(playlists: List<UserPlaylist>) = synchronized(lock) {
        runCatching {
            file.parentFile?.mkdirs()
            val root = JSONObject().apply {
                put("playlists", JSONArray().apply {
                    playlists.forEach { pl ->
                        put(JSONObject().apply {
                            put("id", pl.id)
                            put("name", pl.name)
                            put("created_at_ms", pl.createdAtMs)
                            put("updated_at_ms", pl.updatedAtMs)
                            put("track_uris", JSONArray().apply {
                                pl.trackUris.forEach { put(it) }
                            })
                        })
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

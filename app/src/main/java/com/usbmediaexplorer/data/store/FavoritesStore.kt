package com.usbmediaexplorer.data.store

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class FavoriteEntry(
    val key: String,
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val volumeId: String,
    val displayPath: String,
    val addedAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("uri", uri)
        put("name", name)
        put("isDirectory", isDirectory)
        put("volumeId", volumeId)
        put("displayPath", displayPath)
        put("addedAt", addedAt)
    }

    companion object {
        fun fromJson(obj: JSONObject): FavoriteEntry = FavoriteEntry(
            key = obj.optString("key", ""),
            uri = obj.optString("uri", ""),
            name = obj.optString("name", ""),
            isDirectory = obj.optBoolean("isDirectory", false),
            volumeId = obj.optString("volumeId", ""),
            displayPath = obj.optString("displayPath", ""),
            addedAt = obj.optLong("addedAt", 0L),
        )
    }
}

/** Files and folders the user pinned. Keyed by the stable URI so renames do not duplicate. */
class FavoritesStore(context: Context) : JsonStore(context, "favorites.json") {

    val favorites: Flow<List<FavoriteEntry>> = root.map { json ->
        json.optArray("items").objects().map(FavoriteEntry::fromJson)
            .sortedByDescending { it.addedAt }
    }

    suspend fun snapshot(): List<FavoriteEntry> = root.value.let { json ->
        json.optJSONArray("items")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }.map(FavoriteEntry::fromJson)
        } ?: emptyList()
    }

    suspend fun add(entry: FavoriteEntry) = mutate { json ->
        val items = json.array("items")
        removeWhere(items) { it.string("key") == entry.key || it.string("uri") == entry.uri }
        items.put(entry.toJson())
    }

    suspend fun remove(key: String) = mutate { json ->
        val items = json.array("items")
        removeWhere(items) { it.string("key") == key || it.string("uri") == key }
    }

    suspend fun removeAll(keys: Set<String>) = mutate { json ->
        val items = json.array("items")
        removeWhere(items) { keys.contains(it.string("key")) || keys.contains(it.string("uri")) }
    }

    suspend fun contains(uri: String): Boolean = snapshot().any { it.uri == uri || it.key == uri }

    suspend fun clear() = mutate { it.put("items", JSONArray()) }

    private inline fun removeWhere(array: JSONArray, predicate: (JSONObject) -> Boolean) {
        for (i in array.length() - 1 downTo 0) {
            val obj = array.optJSONObject(i) ?: continue
            if (predicate(obj)) array.remove(i)
        }
    }
}

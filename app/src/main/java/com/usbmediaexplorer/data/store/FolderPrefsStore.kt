package com.usbmediaexplorer.data.store

import android.content.Context
import com.usbmediaexplorer.data.settings.SortMode
import com.usbmediaexplorer.data.settings.ViewMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class FolderPrefs(
    val viewMode: ViewMode? = null,
    val sortMode: SortMode? = null,
)

/**
 * Per-folder view/sort overrides (spec §13 and §2: movie folders default to a large grid).
 * Falls back to the global default when a folder has no override.
 */
class FolderPrefsStore(context: Context) : JsonStore(context, "folder_prefs.json") {

    val prefs: Flow<Map<String, FolderPrefs>> = root.map { json ->
        json.optJSONObject("items")?.let { items -> readAll(items) } ?: emptyMap()
    }

    fun prefsFor(folderKey: String): FolderPrefs {
        val items = root.value.optJSONObject("items") ?: return FolderPrefs()
        val obj = items.optJSONObject(folderKey) ?: return FolderPrefs()
        return FolderPrefs(
            viewMode = obj.optString("view").takeIf { it.isNotEmpty() }?.let {
                runCatching { ViewMode.valueOf(it) }.getOrNull()
            },
            sortMode = obj.optString("sort").takeIf { it.isNotEmpty() }?.let {
                runCatching { SortMode.valueOf(it) }.getOrNull()
            },
        )
    }

    suspend fun setViewMode(folderKey: String, viewMode: ViewMode) = mutate { json ->
        val items = json.optJSONObject("items") ?: JSONObject().also { json.put("items", it) }
        val obj = items.optJSONObject(folderKey) ?: JSONObject().also { items.put(folderKey, it) }
        obj.put("view", viewMode.name)
    }

    suspend fun setSortMode(folderKey: String, sortMode: SortMode) = mutate { json ->
        val items = json.optJSONObject("items") ?: JSONObject().also { json.put("items", it) }
        val obj = items.optJSONObject(folderKey) ?: JSONObject().also { items.put(folderKey, it) }
        obj.put("sort", sortMode.name)
    }

    suspend fun clear(folderKey: String) = mutate { json ->
        json.optJSONObject("items")?.remove(folderKey)
    }

    private fun readAll(items: JSONObject): Map<String, FolderPrefs> =
        items.keys().asSequence().mapNotNull { key ->
            val obj = items.optJSONObject(key) ?: return@mapNotNull null
            key to FolderPrefs(
                viewMode = obj.optString("view").takeIf { it.isNotEmpty() }?.let {
                    runCatching { ViewMode.valueOf(it) }.getOrNull()
                },
                sortMode = obj.optString("sort").takeIf { it.isNotEmpty() }?.let {
                    runCatching { SortMode.valueOf(it) }.getOrNull()
                },
            )
        }.toMap()
}

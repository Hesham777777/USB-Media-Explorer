package com.usbmediaexplorer.data.store

import android.content.Context
import android.net.Uri
import com.usbmediaexplorer.data.doc.DocNode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

data class RecentEntry(
    val key: String,
    val uri: String,
    val name: String,
    val isDirectory: Boolean,
    val volumeId: String,
    val displayPath: String,
    val size: Long,
    val lastOpenedAt: Long,
    val kindName: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("key", key)
        put("uri", uri)
        put("name", name)
        put("isDirectory", isDirectory)
        put("volumeId", volumeId)
        put("displayPath", displayPath)
        put("size", size)
        put("lastOpenedAt", lastOpenedAt)
        put("kind", kindName)
    }

    companion object {
        fun fromJson(obj: JSONObject): RecentEntry = RecentEntry(
            key = obj.optString("key", ""),
            uri = obj.optString("uri", ""),
            name = obj.optString("name", ""),
            isDirectory = obj.optBoolean("isDirectory", false),
            volumeId = obj.optString("volumeId", ""),
            displayPath = obj.optString("displayPath", ""),
            size = obj.optLong("size", 0L),
            lastOpenedAt = obj.optLong("lastOpenedAt", 0L),
            kindName = obj.optString("kind", ""),
        )
    }
}

/**
 * The node this entry points at, rebuilt from the stored fields — no disk access, so the "recent
 * folders" card can render its Folder Cover (and its folder-icon fallback) even before a volume
 * answers, and a temporarily unmounted USB stick costs nothing here.
 *
 * The entry keeps the node's own identity string ([DocNode.key] = `uri|size|lastModified`) but
 * stores `size = -1`, so size and modified time are read back from the key. That makes the
 * thumbnail cache key identical to the one produced while browsing the same folder: one cover is
 * generated once, and the two screens share it.
 */
fun RecentEntry.asDocNode(): DocNode {
    val parts = key.split('|')
    val keySize = parts.getOrNull(parts.size - 2)?.toLongOrNull()
    val keyModified = parts.lastOrNull()?.toLongOrNull()
    return DocNode(
        uri = Uri.parse(uri),
        name = name,
        isDirectory = isDirectory,
        size = keySize ?: size,
        lastModified = keyModified ?: 0L,
        mimeType = null,
        volumeId = volumeId,
        displayPath = displayPath,
    )
}

/** "Last opened" lists: watched videos and browsed folders (spec §18). */
class RecentStore(context: Context) : JsonStore(context, "recent.json") {

    private val maxItems = 60

    val recentVideos: Flow<List<RecentEntry>> = root.map { json ->
        json.optArray("videos").objects().map(RecentEntry::fromJson)
            .sortedByDescending { it.lastOpenedAt }
    }

    val recentFolders: Flow<List<RecentEntry>> = root.map { json ->
        json.optArray("folders").objects().map(RecentEntry::fromJson)
            .sortedByDescending { it.lastOpenedAt }
    }

    suspend fun recordVideo(entry: RecentEntry) = record("videos", entry)

    suspend fun recordFolder(entry: RecentEntry) = record("folders", entry)

    private suspend fun record(arrayKey: String, entry: RecentEntry) = mutate { json ->
        val items = json.array(arrayKey)
        for (i in items.length() - 1 downTo 0) {
            val obj = items.optJSONObject(i) ?: continue
            if (obj.string("uri") == entry.uri) items.remove(i)
        }
        items.put(0, entry.toJson())
        while (items.length() > maxItems) items.remove(items.length() - 1)
    }

    suspend fun removeVideo(key: String) = removeKey("videos", key)

    suspend fun removeFolder(key: String) = removeKey("folders", key)

    private suspend fun removeKey(arrayKey: String, key: String) = mutate { json ->
        val items = json.array(arrayKey)
        for (i in items.length() - 1 downTo 0) {
            val obj = items.optJSONObject(i) ?: continue
            if (obj.string("key") == key || obj.string("uri") == key) items.remove(i)
        }
    }

    suspend fun clearAll() = mutate {
        it.put("videos", JSONArray())
        it.put("folders", JSONArray())
    }
}

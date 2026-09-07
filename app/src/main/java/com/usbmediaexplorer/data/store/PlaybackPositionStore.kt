package com.usbmediaexplorer.data.store

import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONObject

data class PlaybackPosition(
    val key: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
) {
    val remainingMs: Long get() = (durationMs - positionMs).coerceAtLeast(0)

    /** A video counts as "watched" once the user got past 96% of it. */
    val isFinished: Boolean
        get() = durationMs > 0 && positionMs >= durationMs * 0.96

    val progress: Float
        get() = if (durationMs <= 0) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject().apply {
        put("positionMs", positionMs)
        put("durationMs", durationMs)
        put("updatedAt", updatedAt)
    }
}

/**
 * Resume positions (spec §19). Keyed by [com.usbmediaexplorer.data.doc.DocNode.stableKey] so the
 * bookmark survives a file being edited or re-downloaded onto the drive.
 */
class PlaybackPositionStore(context: Context) : JsonStore(context, "playback.json") {

    val positions: Flow<Map<String, PlaybackPosition>> = root.map { json ->
        json.optJSONObject("items")?.let { items ->
            items.keys().asSequence().mapNotNull { key ->
                val obj = items.optJSONObject(key) ?: return@mapNotNull null
                key to PlaybackPosition(
                    key = key,
                    positionMs = obj.long("positionMs"),
                    durationMs = obj.long("durationMs"),
                    updatedAt = obj.long("updatedAt"),
                )
            }.toMap()
        } ?: emptyMap()
    }

    fun positionOf(key: String): PlaybackPosition? =
        root.value.optJSONObject("items")?.optJSONObject(key)?.let {
            PlaybackPosition(
                key = key,
                positionMs = it.long("positionMs"),
                durationMs = it.long("durationMs"),
                updatedAt = it.long("updatedAt"),
            )
        }

    suspend fun save(position: PlaybackPosition) = mutate { json ->
        val items = json.optJSONObject("items") ?: JSONObject().also { json.put("items", it) }
        items.put(position.key, position.toJson())
        pruneOldest(items, limit = 400)
    }

    suspend fun clear(key: String) = mutate { json ->
        json.optJSONObject("items")?.remove(key)
    }

    suspend fun clearFinished() = mutate { json ->
        val items = json.optJSONObject("items") ?: return@mutate
        items.keys().asSequence().toList().forEach { key ->
            val obj = items.optJSONObject(key) ?: return@forEach
            val duration = obj.long("durationMs")
            val pos = obj.long("positionMs")
            if (duration > 0 && pos >= duration * 0.96) items.remove(key)
        }
    }

    private fun pruneOldest(items: JSONObject, limit: Int) {
        if (items.length() <= limit) return
        val sorted = items.keys().asSequence()
            .mapNotNull { key -> items.optJSONObject(key)?.let { key to it.long("updatedAt") } }
            .sortedBy { it.second }
            .toList()
        val excess = sorted.size - limit
        if (excess > 0) sorted.take(excess).forEach { items.remove(it.first) }
    }
}

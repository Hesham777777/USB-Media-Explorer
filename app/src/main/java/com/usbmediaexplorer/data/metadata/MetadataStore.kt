package com.usbmediaexplorer.data.metadata

import android.content.Context
import com.usbmediaexplorer.data.store.JsonStore
import org.json.JSONArray
import org.json.JSONObject

/**
 * Disk cache for parsed media metadata.
 *
 * Reading a header from a USB stick costs tens of milliseconds; the grid needs duration and
 * resolution for every visible card, so results are cached under the same key as thumbnails
 * (`uri|size|lastModified`) and invalidated automatically when the file changes.
 */
class MetadataStore(context: Context) : JsonStore(context, "metadata.json") {

    private val limit = 4000

    fun get(key: String): MediaMetadata? =
        root.value.optJSONObject("items")?.optJSONObject(key)?.let { fromJSON(key, it) }

    suspend fun put(metadata: MediaMetadata) = mutate { json ->
        val items = json.optJSONObject("items") ?: JSONObject().also { json.put("items", it) }
        items.put(metadata.key, toJSON(metadata))
        prune(items)
    }

    suspend fun remove(key: String) = mutate { json ->
        json.optJSONObject("items")?.remove(key)
    }

    suspend fun removeByUri(uri: String) = mutate { json ->
        val items = json.optJSONObject("items") ?: return@mutate
        items.keys().asSequence().toList().forEach { key ->
            if (key.startsWith("$uri|")) items.remove(key)
        }
    }

    suspend fun clear() = mutate { it.put("items", JSONObject()) }

    private fun prune(items: JSONObject) {
        if (items.length() <= limit) return
        val oldest = items.keys().asSequence()
            .mapNotNull { key -> items.optJSONObject(key)?.let { key to it.optLong("updatedAt") } }
            .sortedBy { it.second }
            .take(items.length() - limit)
            .toList()
        oldest.forEach { items.remove(it.first) }
    }

    private fun toJSON(metadata: MediaMetadata): JSONObject = JSONObject().apply {
        put("durationMs", metadata.durationMs)
        put("width", metadata.width)
        put("height", metadata.height)
        put("rotation", metadata.rotation)
        put("fps", metadata.fps.toDouble())
        put("bitrate", metadata.bitrate)
        put("videoCodec", metadata.videoCodec)
        put("containerMime", metadata.containerMime)
        put("hasArtwork", metadata.hasEmbeddedArtwork)
        put("source", metadata.source.name)
        put("updatedAt", System.currentTimeMillis())
        put(
            "tracks",
            JSONArray().apply {
                metadata.tracks.forEach { track ->
                    put(
                        JSONObject().apply {
                            put("index", track.index)
                            put("type", track.type.name)
                            put("mime", track.mime)
                            put("language", track.language)
                            put("width", track.width)
                            put("height", track.height)
                            put("durationMs", track.durationMs)
                            put("channelCount", track.channelCount)
                            put("sampleRate", track.sampleRate)
                            put("bitrate", track.bitrate)
                        },
                    )
                }
            },
        )
    }

    private fun fromJSON(key: String, obj: JSONObject): MediaMetadata? = runCatching {
        val tracksArray = obj.optJSONArray("tracks")
        val tracks = if (tracksArray == null) {
            emptyList()
        } else {
            (0 until tracksArray.length()).mapNotNull { i ->
                val t = tracksArray.optJSONObject(i) ?: return@mapNotNull null
                TrackInfo(
                    index = t.optInt("index"),
                    type = runCatching { TrackType.valueOf(t.optString("type")) }
                        .getOrDefault(TrackType.UNKNOWN),
                    mime = t.optString("mime").takeIf { !t.isNull("mime") },
                    language = t.optString("language").takeIf { !t.isNull("language") },
                    width = t.optInt("width"),
                    height = t.optInt("height"),
                    durationMs = t.optLong("durationMs"),
                    channelCount = t.optInt("channelCount"),
                    sampleRate = t.optInt("sampleRate"),
                    bitrate = t.optLong("bitrate"),
                )
            }
        }
        MediaMetadata(
            key = key,
            durationMs = obj.optLong("durationMs"),
            width = obj.optInt("width"),
            height = obj.optInt("height"),
            rotation = obj.optInt("rotation"),
            fps = obj.optDouble("fps", 0.0).toFloat(),
            bitrate = obj.optLong("bitrate"),
            videoCodec = obj.optString("videoCodec").takeIf { !obj.isNull("videoCodec") },
            containerMime = obj.optString("containerMime").takeIf { !obj.isNull("containerMime") },
            tracks = tracks,
            hasEmbeddedArtwork = obj.optBoolean("hasArtwork", false),
            source = runCatching { MetadataSource.valueOf(obj.optString("source")) }
                .getOrDefault(MetadataSource.NONE),
        )
    }.getOrNull()
}

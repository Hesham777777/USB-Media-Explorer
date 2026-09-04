package com.usbmediaexplorer.data.thumb

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File

/**
 * Disk cache for generated thumbnails (spec §5).
 *
 * Layout: `<cacheDir>/thumbs/<md5>.webp` plus a JSON index that records the owning file key,
 * the byte size and the last access time. The index makes three requirements cheap:
 *  - report the cache size in settings,
 *  - evict least-recently-used entries once the user's limit is exceeded,
 *  - drop entries whose video no longer exists ("if the video is deleted, delete its cache").
 */
class ThumbnailCache(
    context: Context,
    private val scope: CoroutineScope,
) {

    private data class Entry(
        val key: String,
        val nodeKey: String,
        val sizeBytes: Long,
        var lastAccess: Long,
    )

    private val dir = File(context.cacheDir, DIR_NAME).apply { mkdirs() }
    private val indexFile = File(context.cacheDir, INDEX_NAME)
    private val entries = LinkedHashMap<String, Entry>()
    private val mutex = Mutex()
    private var dirty = false
    private var flushJob: Job? = null

    init {
        scope.launch(Dispatchers.IO) { loadIndex() }
    }

    // ------------------------------------------------------------------

    suspend fun fileFor(key: String): File? = mutex.withLock {
        val file = File(dir, "$key.webp")
        if (!file.exists()) {
            entries.remove(key)
            dirty = true
            return@withLock null
        }
        val existing = entries[key]
        if (existing != null) {
            existing.lastAccess = System.currentTimeMillis()
        } else {
            entries[key] = Entry(key, "", file.length(), System.currentTimeMillis())
        }
        dirty = true
        file
    }

    suspend fun put(key: String, nodeKey: String, bytes: ByteArray): File? = mutex.withLock {
        runCatching {
            val file = File(dir, "$key.webp")
            file.writeBytes(bytes)
            entries[key] = Entry(key, nodeKey, bytes.size.toLong(), System.currentTimeMillis())
            dirty = true
            file
        }.getOrNull()
    }

    suspend fun remove(key: String) = mutex.withLock {
        entries.remove(key)
        runCatching { File(dir, "$key.webp").delete() }
        dirty = true
    }

    /** Drops every cached size/strategy variant of one source file. */
    suspend fun removeForNode(nodeKey: String): Int = mutex.withLock {
        val doomed = entries.values.filter { it.nodeKey == nodeKey }.map { it.key }
        doomed.forEach { key ->
            entries.remove(key)
            runCatching { File(dir, "$key.webp").delete() }
        }
        if (doomed.isNotEmpty()) dirty = true
        doomed.size
    }

    suspend fun sizeBytes(): Long = mutex.withLock {
        entries.values.sumOf { it.sizeBytes }
            .takeIf { it > 0 }
            ?: runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }

    suspend fun count(): Int = mutex.withLock { entries.size }

    /** LRU eviction down to [limitBytes]. Returns how many bytes were freed. */
    suspend fun pruneTo(limitBytes: Long): Long = mutex.withLock {
        val total = entries.values.sumOf { it.sizeBytes }
        if (total <= limitBytes) return@withLock 0L
        var freed = 0L
        val byAccess = entries.values.sortedBy { it.lastAccess }
        for (entry in byAccess) {
            if (total - freed <= limitBytes) break
            val file = File(dir, entry.key + ".webp")
            val size = if (file.exists()) file.length() else entry.sizeBytes
            runCatching { file.delete() }
            entries.remove(entry.key)
            freed += size
            dirty = true
        }
        freed
    }

    /**
     * Removes entries for files that no longer exist. Existence is probed lazily through
     * [existsProbe] so we only touch the drive for keys we actually hold.
     */
    suspend fun cleanOrphans(existsProbe: suspend (String) -> Boolean): Int = mutex.withLock {
        val nodeKeys = entries.values.map { it.nodeKey }.filter { it.isNotEmpty() }.distinct()
        val missing = ArrayList<String>()
        for (nodeKey in nodeKeys) {
            val uri = nodeKey.substringBefore('|')
            if (!existsProbe(uri)) missing += nodeKey
        }
        var removed = 0
        for (nodeKey in missing) {
            entries.values.filter { it.nodeKey == nodeKey }.map { it.key }.forEach { key ->
                runCatching { File(dir, "$key.webp").delete() }
                entries.remove(key)
                removed++
            }
        }
        if (removed > 0) dirty = true
        removed
    }

    suspend fun clear(): Int = mutex.withLock {
        val count = entries.size
        entries.clear()
        runCatching { dir.listFiles()?.forEach { it.delete() } }
        dirty = true
        count
    }

    suspend fun flush() {
        flushJob?.cancel()
        persist()
    }

    /** Debounced write so scrolling through 500 videos does not thrash the index file. */
    fun schedulePersist() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch(Dispatchers.IO) {
            delay(2_000)
            persist()
        }
    }

    private suspend fun persist() = mutex.withLock {
        if (!dirty) return@withLock
        runCatching {
            val json = JSONObject()
            entries.values.forEach { entry ->
                json.put(
                    entry.key,
                    JSONObject().apply {
                        put("node", entry.nodeKey)
                        put("size", entry.sizeBytes)
                        put("access", entry.lastAccess)
                    },
                )
            }
            val tmp = File(indexFile.parentFile, indexFile.name + ".tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (indexFile.exists()) indexFile.delete()
            if (!tmp.renameTo(indexFile)) {
                indexFile.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
            dirty = false
        }
    }

    private fun loadIndex() {
        runCatching {
            if (!indexFile.exists()) return
            val json = JSONObject(indexFile.readText(Charsets.UTF_8))
            json.keys().forEach { key ->
                val obj = json.optJSONObject(key) ?: return@forEach
                entries[key] = Entry(
                    key = key,
                    nodeKey = obj.optString("node"),
                    sizeBytes = obj.optLong("size"),
                    lastAccess = obj.optLong("access"),
                )
            }
            // Drop index rows whose file vanished (cache cleared by the OS).
            val stale = entries.keys.filter { !File(dir, "$it.webp").exists() }
            stale.forEach { entries.remove(it) }
        }
    }

    private companion object {
        const val DIR_NAME = "thumbs"
        const val INDEX_NAME = "thumbs_index.json"
    }
}

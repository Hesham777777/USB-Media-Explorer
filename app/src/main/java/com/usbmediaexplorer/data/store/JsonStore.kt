package com.usbmediaexplorer.data.store

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File

/**
 * Tiny JSON-file backed store.
 *
 * The app has no server and no Room database: favorites, recents, resume positions and
 * per-folder view preferences are small key/value collections that must survive a restart and
 * must never touch the USB drive. A single JSON document per store keeps that trivially simple,
 * atomic (write to temp + rename) and easy to inspect while debugging.
 */
open class JsonStore(
    context: Context,
    fileName: String,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
) {
    protected val file: File = File(context.filesDir, fileName)
    private val mutex = Mutex()

    private val _root = MutableStateFlow(JSONObject())
    val root: StateFlow<JSONObject> = _root.asStateFlow()

    init {
        scope.launch { reload() }
    }

    suspend fun reload() = mutex.withLock {
        val parsed = readFromDisk()
        _root.value = parsed
    }

    private fun readFromDisk(): JSONObject {
        if (!file.exists()) return JSONObject()
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrElse { JSONObject() }
    }

    /** Reads the store, mutating a copy and persisting it atomically. */
    suspend fun <T> mutate(block: (JSONObject) -> T): T = mutex.withLock {
        val current = if (_root.value.length() == 0) readFromDisk() else JSONObject(_root.value.toString())
        val result = block(current)
        writeToDisk(current)
        _root.value = current
        result
    }

    private fun writeToDisk(json: JSONObject) {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.toString(), Charsets.UTF_8)
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(tmp.readText(Charsets.UTF_8), Charsets.UTF_8)
                tmp.delete()
            }
        }
    }

    /** Mutating accessor: creates and stores an empty array when missing. Use inside [mutate] only. */
    protected fun JSONObject.array(key: String): JSONArray =
        optJSONArray(key) ?: JSONArray().also { put(key, it) }

    /** Read-only accessor: never mutates the live object. */
    protected fun JSONObject.optArray(key: String): JSONArray = optJSONArray(key) ?: JSONArray()

    protected fun JSONArray.objects(): List<JSONObject> {
        val out = ArrayList<JSONObject>(length())
        for (i in 0 until length()) {
            val item = optJSONObject(i)
            if (item != null) out.add(item)
        }
        return out
    }

    protected fun JSONObject.string(key: String, fallback: String = ""): String =
        optString(key, fallback)

    protected fun JSONObject.long(key: String, fallback: Long = 0L): Long =
        if (has(key)) optLong(key, fallback) else fallback

    protected fun safeParse(block: () -> JSONObject): JSONObject =
        try {
            block()
        } catch (_: JSONException) {
            JSONObject()
        }
}

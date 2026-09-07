package com.usbmediaexplorer.data.ops

import android.content.Context
import com.usbmediaexplorer.data.store.JsonStore
import org.json.JSONObject

/**
 * Persistent record of in-flight operations (audit item 4).
 *
 * A process death mid-copy used to leave staged temporary files and half-moved folders with no
 * trace. Now every operation is journalled STAGING → COMMITTED/FAILED *on disk*: on the next
 * start [FileOpsManager] reads the surviving STAGING entries, deletes the staging leftovers they
 * name (matched by their [Entry.token]) and marks them FAILED.
 *
 * Deliberately built on [JsonStore] instead of Room: no new dependency, no schema/migration
 * surface, and the journal sees at most a couple of writes per operation — durability here comes
 * from the atomic JsonStore write, not from a database engine.
 */
class OpsJournal(context: Context) : JsonStore(context, "opsjournal.json") {

    enum class State { STAGING, COMMITTED, FAILED }

    data class Entry(
        val id: String,
        val type: String,
        val state: State,
        val token: String,
        val destUri: String,
        val at: Long,
    )

    suspend fun begin(id: String, type: String, token: String, destUri: String) = mutate { json ->
        json.put(
            id,
            JSONObject().apply {
                put("type", type)
                put("state", State.STAGING.name)
                put("token", token)
                put("dest", destUri)
                put("at", System.currentTimeMillis())
            },
        )
        pruneInPlace(json)
        Unit
    }

    suspend fun finish(id: String, state: State) = mutate { json ->
        json.optJSONObject(id)?.put("state", state.name)
        Unit
    }

    /** In-memory snapshot of the journal (the store loads once and lives in a StateFlow). */
    fun entries(): List<Entry> {
        val current = root.value
        val out = ArrayList<Entry>(current.length())
        current.keys().forEach { id ->
            current.optJSONObject(id)?.let { obj ->
                out += Entry(
                    id = id,
                    type = obj.optString("type"),
                    state = runCatching { State.valueOf(obj.optString("state")) }
                        .getOrDefault(State.FAILED),
                    token = obj.optString("token"),
                    destUri = obj.optString("dest"),
                    at = obj.optLong("at"),
                )
            }
        }
        return out
    }

    suspend fun pruneFinishedBefore(cutoff: Long) = mutate { json ->
        json.keys().asSequence().toList().forEach { id ->
            val obj = json.optJSONObject(id) ?: return@forEach
            if (obj.optString("state") != State.STAGING.name && obj.optLong("at") < cutoff) {
                json.remove(id)
            }
        }
        Unit
    }

    /** Keeps the journal bounded even if pruning by age somehow missed entries. */
    private fun pruneInPlace(json: JSONObject) {
        if (json.length() <= MAX_ENTRIES) return
        val oldestFirst = json.keys().asSequence()
            .mapNotNull { id -> json.optJSONObject(id)?.let { id to it.optLong("at") } }
            .sortedBy { it.second }
            .toList()
        val excess = json.length() - MAX_ENTRIES
        if (excess > 0) oldestFirst.take(excess).forEach { (id, _) -> json.remove(id) }
    }

    private companion object {
        const val MAX_ENTRIES = 200
    }
}

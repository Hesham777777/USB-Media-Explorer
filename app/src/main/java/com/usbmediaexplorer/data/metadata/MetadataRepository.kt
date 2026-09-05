package com.usbmediaexplorer.data.metadata

import com.usbmediaexplorer.data.doc.DocNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

/**
 * Lazy media-info loader (spec §22).
 *
 * The grid asks for duration/resolution only for the rows it is about to draw. Requests are
 * deduplicated, capped and served from a memory map first, then from [MetadataStore], and only
 * then by actually opening the file on the drive. A semaphore keeps at most two concurrent
 * header reads so a slow USB stick is never hammered by parallel seeks.
 */
class MetadataRepository(
    private val reader: MediaMetadataReader,
    private val store: MetadataStore,
    private val scope: CoroutineScope,
    private val workers: Int = 2,
) {

    private val memory = Collections.synchronizedMap(
        object : LinkedHashMap<String, MediaMetadata>(64, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaMetadata>?): Boolean =
                size > MEMORY_LIMIT
        },
    )

    private val _published = MutableStateFlow<Map<String, MediaMetadata>>(emptyMap())

    /** Snapshot of everything resolved so far, observed by browse/search view models. */
    val published: StateFlow<Map<String, MediaMetadata>> = _published.asStateFlow()

    private val queue = Channel<DocNode>(
        capacity = 256,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    private val inFlight = ConcurrentHashMap.newKeySet<String>()
    private val readGate = Semaphore(2)

    init {
        repeat(workers) {
            scope.launch {
                for (node in queue) {
                    runCatching { load(node) }
                }
            }
        }
    }

    fun cached(node: DocNode): MediaMetadata? {
        memory[node.key]?.let { return it }
        return store.get(node.key)?.also { memory[node.key] = it }
    }

    /** Fast, non-blocking lookup used while composing a card. */
    fun peek(node: DocNode): MediaMetadata? = memory[node.key] ?: store.get(node.key)?.also {
        memory[node.key] = it
    }

    /** Queue a lazy request; safe to call from composition. */
    fun enqueue(node: DocNode) {
        if (node.isDirectory) return
        if (node.key in inFlight) return
        if (peek(node) != null) return
        queue.trySend(node)
    }

    fun enqueueAll(nodes: List<DocNode>) {
        nodes.forEach { enqueue(it) }
    }

    suspend fun load(node: DocNode, force: Boolean = false): MediaMetadata? {
        if (node.isDirectory) return null
        if (!force) cached(node)?.let { publish(node, it); return it }
        if (!inFlight.add(node.key)) return peek(node)
        return try {
            readGate.acquire()
            try {
                val metadata = reader.read(node).copy(key = node.key)
                memory[node.key] = metadata
                store.put(metadata)
                publish(node, metadata)
                metadata
            } finally {
                readGate.release()
            }
        } catch (t: Throwable) {
            null
        } finally {
            inFlight.remove(node.key)
        }
    }

    /** Resolved entries waiting for the next batched publish. */
    private val pending = ConcurrentHashMap<String, MediaMetadata>()
    private val publishGate = Any()
    private var publishScheduled = false

    /**
     * Publishing a fresh snapshot per resolved file means a full map copy per file — O(n²) churn
     * on a 2 000-file folder, and a list-rebuild trigger for every single duration resolved.
     * Workers deposit into [pending]; one flusher emits at most a single snapshot per
     * [PUBLISH_BATCH_MS] carrying everything resolved in that window.
     */
    private fun publish(node: DocNode, metadata: MediaMetadata) {
        if (pending[node.key] == metadata || _published.value[node.key] == metadata) return
        pending[node.key] = metadata
        schedulePublishFlush()
    }

    private fun schedulePublishFlush() {
        synchronized(publishGate) {
            if (publishScheduled) return
            publishScheduled = true
        }
        scope.launch {
            delay(PUBLISH_BATCH_MS)
            flushPending()
            val more = pending.isNotEmpty()
            synchronized(publishGate) { publishScheduled = false }
            if (more) schedulePublishFlush()
        }
    }

    private fun flushPending() {
        if (pending.isEmpty()) return
        val next = LinkedHashMap(_published.value)
        val iterator = pending.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            next[entry.key] = entry.value
            iterator.remove()
        }
        // Keep the published map bounded: cards that scrolled far away drop out of memory anyway.
        if (next.size > PUBLISH_LIMIT) {
            val evict = next.entries.iterator()
            while (next.size > PUBLISH_LIMIT && evict.hasNext()) {
                evict.next()
                evict.remove()
            }
        }
        _published.value = next
    }

    /** Called after a delete/rename so stale info does not survive. */
    suspend fun invalidate(node: DocNode) {
        memory.remove(node.key)
        pending.remove(node.key)
        store.remove(node.key)
        _published.value = _published.value - node.key
    }

    suspend fun invalidateUri(uri: String) {
        store.removeByUri(uri)
        memory.keys.filter { it.startsWith("$uri|") }.forEach { memory.remove(it) }
        pending.keys.filter { it.startsWith("$uri|") }.forEach { pending.remove(it) }
    }

    suspend fun clear() {
        memory.clear()
        pending.clear()
        _published.value = emptyMap()
        store.clear()
    }

    private companion object {
        const val MEMORY_LIMIT = 512
        const val PUBLISH_LIMIT = 400
        const val PUBLISH_BATCH_MS = 200L
    }
}

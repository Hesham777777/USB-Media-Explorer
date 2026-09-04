package com.usbmediaexplorer.data.ops

import android.content.Context
import android.content.Intent
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.SpeedTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Copy/cut buffer used by "paste" (spec §14). */
data class Clipboard(
    val items: List<DocNode>,
    val sourceParent: DocNode?,
    val isCut: Boolean,
)

/** Emitted so open screens can refresh themselves after a mutation. */
sealed interface OpsEvent {
    data class Completed(val type: OpType, val destinationUri: String?, val success: Boolean) : OpsEvent
    data class Failed(val type: OpType, val message: String?) : OpsEvent
    data object ClipboardChanged : OpsEvent
}

/**
 * Job registry for every mutation the app performs (spec §14).
 *
 * Jobs run on a background dispatcher, publish a throttled progress snapshot (percent, speed,
 * ETA, current file) and support pause / resume / cancel. While at least one job is active a
 * foreground service is running, so a long copy from a 2 TB USB drive survives the UI being
 * backgrounded.
 */
class FileOpsManager(
    private val context: Context,
    private val engine: FileOpsEngine,
    private val docRepository: DocRepository,
    private val scope: CoroutineScope,
) {

    private class Handle(
        val id: String,
        val progress: MutableStateFlow<JobProgress>,
        val paused: MutableStateFlow<Boolean>,
    ) {
        val transferred = AtomicLong()
        val tracker = SpeedTracker()
        var job: Job? = null
        var canceled = false
        var lastPublish = 0L
    }

    private val handles = LinkedHashMap<String, Handle>()

    private val _jobs = MutableStateFlow<List<JobProgress>>(emptyList())
    val jobs: StateFlow<List<JobProgress>> = _jobs.asStateFlow()

    private val _activeJob = MutableStateFlow<JobProgress?>(null)
    val activeJob: StateFlow<JobProgress?> = _activeJob.asStateFlow()

    private val _events = MutableSharedFlow<OpsEvent>(
        replay = 0,
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<OpsEvent> = _events.asSharedFlow()

    private val _clipboard = MutableStateFlow<Clipboard?>(null)
    val clipboard: StateFlow<Clipboard?> = _clipboard.asStateFlow()

    // ------------------------------------------------------------------
    // Public API used by the UI
    // ------------------------------------------------------------------

    fun copyToClipboard(items: List<DocNode>, sourceParent: DocNode?, cut: Boolean) {
        _clipboard.value = Clipboard(items, sourceParent, cut)
        _events.tryEmit(OpsEvent.ClipboardChanged)
    }

    fun clearClipboard() {
        _clipboard.value = null
        _events.tryEmit(OpsEvent.ClipboardChanged)
    }

    fun paste(destination: DocNode) {
        val clip = _clipboard.value ?: return
        if (clip.isCut) move(clip.items, destination) else copy(clip.items, destination)
        if (clip.isCut) clearClipboard()
    }

    fun copy(items: List<DocNode>, destination: DocNode): String =
        start(OpType.COPY, items, destination) { ctx -> engine.copy(items, destination, ctx) }

    fun move(items: List<DocNode>, destination: DocNode): String =
        start(OpType.MOVE, items, destination) { ctx -> engine.move(items, destination, ctx) }

    fun delete(items: List<DocNode>): String =
        start(OpType.DELETE, items, null) { ctx -> engine.delete(items, ctx) }

    fun zip(items: List<DocNode>, destination: DocNode, archiveName: String): String =
        start(OpType.ZIP, items, destination) { ctx ->
            engine.zip(items, destination, archiveName, ctx)
        }

    fun unzip(archive: DocNode, destination: DocNode): String =
        start(OpType.UNZIP, listOf(archive), destination) { ctx ->
            engine.unzip(archive, destination, ctx)
        }

    fun bulkRename(items: List<DocNode>, rules: BulkRenameRules): String =
        start(OpType.BULK_RENAME, items, null) { ctx -> engine.bulkRename(items, rules, ctx) }

    suspend fun estimate(items: List<DocNode>): Long = engine.estimateBytes(items)

    fun pause(jobId: String) = withHandle(jobId) { handle ->
        handle.paused.value = true
        update(handle) { it.copy(state = JobState.PAUSED) }
    }

    fun resume(jobId: String) = withHandle(jobId) { handle ->
        handle.paused.value = false
        update(handle) { it.copy(state = JobState.RUNNING) }
    }

    fun pauseAll() = jobs.value.filter { it.isActive }.forEach { pause(it.jobId) }

    fun cancel(jobId: String) = withHandle(jobId) { handle ->
        handle.canceled = true
        handle.paused.value = false
        update(handle) { it.copy(state = JobState.CANCELED, finishedAt = System.currentTimeMillis()) }
        handle.job?.cancel()
        publishJobs()
        maybeStopService()
    }

    fun cancelAll() = jobs.value.filter { it.isActive }.forEach { cancel(it.jobId) }

    fun dismiss(jobId: String) = synchronized(handles) {
        handles.remove(jobId)
        publishJobs()
    }

    fun clearFinished() = synchronized(handles) {
        handles.entries.removeAll { !it.value.progress.value.isActive }
        publishJobs()
    }

    // ------------------------------------------------------------------
    // Internals
    // ------------------------------------------------------------------

    private fun start(
        type: OpType,
        items: List<DocNode>,
        destination: DocNode?,
        block: suspend (OpContext) -> OpResult,
    ): String {
        val id = UUID.randomUUID().toString()
        val handle = Handle(
            id = id,
            progress = MutableStateFlow(
                JobProgress(
                    jobId = id,
                    type = type,
                    state = JobState.QUEUED,
                    totalItems = items.size,
                    doneItems = 0,
                    totalBytes = 0L,
                    transferredBytes = 0L,
                    currentItemName = items.firstOrNull()?.name.orEmpty(),
                    destinationLabel = destination?.name.orEmpty(),
                    speedBytesPerSec = 0.0,
                    etaMs = -1L,
                    startedAt = System.currentTimeMillis(),
                ),
            ),
            paused = MutableStateFlow(false),
        )
        synchronized(handles) {
            handles[id] = handle
            publishJobs()
        }

        handle.job = scope.launch(Dispatchers.IO) {
            val ctx = JobContext(handle)
            var result: OpResult? = null
            try {
                if (type != OpType.DELETE) {
                    val total = engine.estimateBytes(items)
                    update(handle) { it.copy(totalBytes = total, state = JobState.RUNNING) }
                } else {
                    update(handle) { it.copy(state = JobState.RUNNING) }
                }
                startServiceIfNeeded()
                result = block(ctx)
            } catch (cancel: CancellationException) {
                handle.canceled = true
            } catch (t: Throwable) {
                result = OpResult(false, error = t.message ?: type.name)
            }

            val finalState = when {
                handle.canceled -> JobState.CANCELED
                result == null -> JobState.CANCELED
                result.success -> JobState.DONE
                else -> JobState.FAILED
            }
            update(handle) {
                it.copy(
                    state = finalState,
                    finishedAt = System.currentTimeMillis(),
                    error = result?.error,
                    transferredBytes = handle.transferred.get(),
                    doneItems = result?.processedItems ?: it.doneItems,
                )
            }
            val destinationUri = destination?.uri?.toString()
            if (finalState == JobState.DONE) {
                _events.tryEmit(OpsEvent.Completed(type, destinationUri, true))
            } else if (finalState == JobState.FAILED) {
                _events.tryEmit(OpsEvent.Failed(type, result?.error))
            } else {
                _events.tryEmit(OpsEvent.Completed(type, destinationUri, false))
            }
            maybeStopService()
        }
        return id
    }

    private inner class JobContext(private val handle: Handle) : OpContext {

        override suspend fun reportBytes(delta: Long) {
            val total = handle.transferred.addAndGet(delta)
            val now = System.currentTimeMillis()
            if (now - handle.lastPublish < PUBLISH_INTERVAL_MS) return
            handle.lastPublish = now
            val speed = handle.tracker.sample(total)
            val current = handle.progress.value
            val remaining = (current.totalBytes - total).coerceAtLeast(0)
            update(handle) {
                it.copy(
                    transferredBytes = total,
                    speedBytesPerSec = speed,
                    etaMs = handle.tracker.etaMs(remaining, speed),
                )
            }
        }

        override suspend fun reportItem(name: String, index: Int) {
            update(handle) { it.copy(currentItemName = name, doneItems = index) }
        }

        override suspend fun awaitResume() {
            if (handle.paused.value) {
                handle.paused.first { !it }
                handle.tracker.reset()
            }
        }
    }

    private fun update(handle: Handle, transform: (JobProgress) -> JobProgress) {
        handle.progress.value = transform(handle.progress.value)
        publishJobs()
    }

    private fun publishJobs() {
        val snapshot = synchronized(handles) { handles.values.map { it.progress.value } }
        _jobs.value = snapshot.sortedByDescending { it.startedAt }
        _activeJob.value = snapshot.firstOrNull { it.isActive }
    }

    private fun withHandle(jobId: String, block: (Handle) -> Unit) {
        synchronized(handles) { handles[jobId] }?.let(block)
    }

    private fun startServiceIfNeeded() {
        runCatching {
            context.startForegroundService(Intent(context, FileOpsService::class.java))
        }
    }

    private fun maybeStopService() {
        val stillActive = jobs.value.any { it.isActive }
        if (!stillActive) {
            runCatching { context.stopService(Intent(context, FileOpsService::class.java)) }
        }
    }

    fun describe(job: JobProgress): String {
        val percent = job.percent
        val speed = if (job.speedBytesPerSec > 0) Formatters.speed(job.speedBytesPerSec) else ""
        val eta = if (job.etaMs > 0) Formatters.eta(job.etaMs) else ""
        return listOfNotNull("$percent%", speed.takeIf { it.isNotEmpty() }, eta.takeIf { it.isNotEmpty() })
            .joinToString(" • ")
    }

    private companion object {
        const val PUBLISH_INTERVAL_MS = 120L
    }
}

package com.usbmediaexplorer.data.ops

import com.usbmediaexplorer.data.doc.DocNode

enum class OpType { COPY, MOVE, DELETE, ZIP, UNZIP, BULK_RENAME }

enum class JobState { QUEUED, RUNNING, PAUSED, CANCELED, FAILED, DONE }

/** Immutable snapshot of a running/finished job, published to the UI and the notification. */
data class JobProgress(
    val jobId: String,
    val type: OpType,
    val state: JobState,
    val totalItems: Int,
    val doneItems: Int,
    val totalBytes: Long,
    val transferredBytes: Long,
    val currentItemName: String,
    val destinationLabel: String,
    val speedBytesPerSec: Double,
    val etaMs: Long,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val error: String? = null,
) {
    val percent: Int
        get() = when {
            totalBytes > 0 -> ((transferredBytes * 100) / totalBytes).toInt().coerceIn(0, 100)
            totalItems > 0 -> ((doneItems * 100) / totalItems).toInt().coerceIn(0, 100)
            state == JobState.DONE -> 100
            else -> 0
        }

    val isActive: Boolean
        get() = state == JobState.QUEUED || state == JobState.RUNNING || state == JobState.PAUSED
}

/** Feedback channel handed to the engine while a job runs. */
interface OpContext {
    suspend fun reportBytes(delta: Long)
    suspend fun reportItem(name: String, index: Int)
    /** Blocks while the user has paused the job; throws on cancellation. */
    suspend fun awaitResume()
}

data class OpResult(
    val success: Boolean,
    val processedItems: Int = 0,
    val bytesTransferred: Long = 0L,
    val error: String? = null,
)

/** Rules for the batch renamer (spec §16). */
data class BulkRenameRules(
    val find: String = "",
    val replace: String = "",
    val prefix: String = "",
    val suffix: String = "",
    val numbering: Boolean = false,
    val startAt: Int = 1,
    val padding: Int = 2,
    val numberingPosition: NumberingPosition = NumberingPosition.BEFORE_NAME,
    val lowercase: Boolean = false,
    val trimSpaces: Boolean = true,
    val keepExtension: Boolean = true,
) {
    enum class NumberingPosition { BEFORE_NAME, AFTER_NAME }

    val isEmpty: Boolean
        get() = find.isEmpty() && prefix.isEmpty() && suffix.isEmpty() && !numbering &&
            !lowercase && !trimSpaces
}

/**
 * Pure planning: turns rules into new names so the UI can preview before anything is written.
 *
 * [planParts] works on (name, extension) pairs, which keeps it unit-testable without Android
 * types; [plan] is the thin [DocNode] wrapper used at runtime.
 */
object BulkRenamePlanner {

    /** [parts] holds `nameWithoutExtension` to `extension` (without the dot). */
    fun planParts(parts: List<Pair<String, String>>, rules: BulkRenameRules): List<String> {
        var counter = rules.startAt
        return parts.map { (rawName, rawExt) ->
            val base = if (rules.keepExtension) rawName else joinName(rawName, rawExt)
            val ext = if (rules.keepExtension && rawExt.isNotEmpty()) ".$rawExt" else ""

            var name = if (rules.find.isNotEmpty()) base.replace(rules.find, rules.replace) else base
            if (rules.trimSpaces) name = name.trim().replace(Regex("\\s+"), " ")
            if (rules.lowercase) name = name.lowercase()

            if (rules.numbering) {
                val number = counter.toString().padStart(rules.padding.coerceIn(1, 6), '0')
                counter++
                name = if (rules.numberingPosition == BulkRenameRules.NumberingPosition.BEFORE_NAME) {
                    "$number $name"
                } else {
                    "$name $number"
                }
            }

            val result = (rules.prefix + name + rules.suffix + ext)
                .replace(Regex("[/:\\\\]"), "_")
                .trim()
            result.ifEmpty { joinName(rawName, rawExt) }
        }
    }

    fun plan(nodes: List<DocNode>, rules: BulkRenameRules): List<Pair<DocNode, String>> {
        val names = planParts(nodes.map { it.nameWithoutExtension to it.extension }, rules)
        return nodes.mapIndexed { index, node -> node to names.getOrElse(index) { node.name } }
    }

    private fun joinName(name: String, ext: String): String =
        if (ext.isEmpty()) name else "$name.$ext"
}

package com.usbmediaexplorer.data.ops

import android.content.Context
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRelation
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.metadata.MetadataRepository
import com.usbmediaexplorer.data.thumb.ThumbnailRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext

/**
 * Low level file operations (spec §14, §16).
 *
 * Everything is stream based with a large buffer, works across backends (file ↔ SAF) and across
 * volumes, never touches the original when only a thumbnail is needed, and reports byte-level
 * progress so the UI can show percentage, speed and ETA.
 *
 * Cancellation is cooperative: [coroutineContext.ensureActive] between chunks, and
 * [OpContext.awaitResume] implements pause/resume.
 */
class FileOpsEngine(
    private val context: Context,
    private val docRepository: DocRepository,
    private val thumbnailRepository: ThumbnailRepository,
    private val metadataRepository: MetadataRepository,
) {

    // ------------------------------------------------------------------
    // Copy / move
    // ------------------------------------------------------------------

    suspend fun copy(items: List<DocNode>, destination: DocNode, ctx: OpContext): OpResult =
        withContext(Dispatchers.IO) {
            // Per-job cache: concurrent operations must not share (or clear) one mutable map
            // (audit item 4).
            val cache = HashMap<String, MutableSet<String>>()
            var done = 0
            var bytes = 0L
            var error: String? = null
            items.forEachIndexed { index, item ->
                ctx.reportItem(item.name, index)
                if (DocRelation.isSameOrDescendant(destination.uri.toString(), item.uri.toString())) {
                    // Copying a folder into itself or its own subtree recurses until the drive
                    // is full (audit item 3): refuse instead.
                    if (error == null) error = item.name
                    return@forEachIndexed
                }
                val result = copyNode(item, destination, ctx, cache)
                if (result.first) {
                    done++
                    bytes += result.second
                } else if (error == null) {
                    error = item.name
                }
            }
            OpResult(error == null, done, bytes, error)
        }

    suspend fun move(items: List<DocNode>, destination: DocNode, ctx: OpContext): OpResult =
        withContext(Dispatchers.IO) {
            val cache = HashMap<String, MutableSet<String>>()
            var done = 0
            var bytes = 0L
            var error: String? = null
            items.forEachIndexed { index, item ->
                ctx.reportItem(item.name, index)
                coroutineContext.ensureActive()
                ctx.awaitResume()
                if (DocRelation.isSameOrDescendant(destination.uri.toString(), item.uri.toString())) {
                    if (error == null) error = item.name
                    return@forEachIndexed
                }
                val fastMove = runCatching { docRepository.moveTo(item, destination) }.getOrNull()
                if (fastMove != null) {
                    done++
                    invalidateFor(item)
                } else {
                    // Cross-volume: a staged, verified copy first — the source is deleted only
                    // after the destination is complete and committed (audit item 2). A full
                    // destination or an unplugged drive now leaves the source untouched.
                    val result = copyNode(item, destination, ctx, cache)
                    if (result.first) {
                        val deleted = deleteSingle(item)
                        if (deleted) {
                            done++
                            bytes += result.second
                            invalidateFor(item)
                        } else if (error == null) {
                            error = item.name
                        }
                    } else if (error == null) {
                        error = item.name
                    }
                }
            }
            OpResult(error == null, done, bytes, error)
        }

    /**
     * Staged copy: everything is written under a hidden `.<token>-name` temporary name,
     * verified, and only then renamed to its final collision-free name. A failure or a
     * cancellation leaves only staging files behind, which cleanup recognizes by the token;
     * the final namespace never shows a half-written file, and a move may delete its source
     * only after this returned success.
     *
     * Returns (success, bytesWritten).
     */
    private suspend fun copyNode(
        source: DocNode,
        destination: DocNode,
        ctx: OpContext,
        cache: HashMap<String, MutableSet<String>>,
    ): Pair<Boolean, Long> {
        coroutineContext.ensureActive()
        ctx.awaitResume()
        val token = ctx.stagingToken.ifEmpty { OpsSafety.newToken() }
        val finalName = uniqueName(destination, source.name, cache)
        // Staging names are capped so a 250-char source name cannot exceed the 255-char limit
        // most file systems enforce; the final rename restores the full name.
        val stagedName = OpsSafety.stagingName(token, finalName.take(200))
        return if (source.isDirectory) {
            val created = docRepository.createDirectory(destination, stagedName)
                ?: return false to 0L
            var total = 0L
            var ok = true
            docRepository.children(source).forEach { child ->
                val childResult = copyNode(child, created, ctx, cache)
                ok = ok && childResult.first
                total += childResult.second
            }
            if (!ok) {
                runCatching { docRepository.deleteRecursive(created) }
                return false to total
            }
            val committed = docRepository.rename(created, finalName)
            if (committed == null) {
                runCatching { docRepository.deleteRecursive(created) }
                false to total
            } else {
                true to total
            }
        } else {
            val staged = docRepository.createFile(destination, stagedName, source.mimeType)
                ?: return false to 0L
            val written = streamCopy(source, staged, ctx)
            // Verification: a truncated stream (full destination, unplugged drive) must never be
            // committed — compare against the source size whenever the source reports one.
            val verified = written >= 0 && (source.size <= 0 || written == source.size)
            if (!verified) {
                runCatching { docRepository.delete(staged) }
                return false to 0L
            }
            val committed = docRepository.rename(staged, finalName)
            if (committed == null) {
                runCatching { docRepository.delete(staged) }
                return false to written
            }
            preserveTimestamp(source, committed)
            true to written
        }
    }

    private suspend fun streamCopy(source: DocNode, target: DocNode, ctx: OpContext): Long {
        val input: InputStream = docRepository.openInput(source.uri) ?: return -1L
        val output: OutputStream = docRepository.openOutput(target.uri) ?: run {
            runCatching { input.close() }
            return -1L
        }
        var written = 0L
        try {
            input.use { i ->
                output.use { o ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    while (true) {
                        coroutineContext.ensureActive()
                        ctx.awaitResume()
                        val read = i.read(buffer)
                        if (read <= 0) break
                        o.write(buffer, 0, read)
                        written += read
                        ctx.reportBytes(read.toLong())
                    }
                    o.flush()
                }
            }
        } catch (t: Throwable) {
            return if (t is kotlinx.coroutines.CancellationException) throw t else -1L
        }
        return written
    }

    private fun preserveTimestamp(source: DocNode, target: DocNode) {
        if (target.uri.scheme != "file" || source.lastModified <= 0) return
        val path = target.uri.path ?: return
        runCatching { java.io.File(path).setLastModified(source.lastModified) }
    }

    // ------------------------------------------------------------------
    // Delete
    // ------------------------------------------------------------------

    suspend fun delete(items: List<DocNode>, ctx: OpContext): OpResult = withContext(Dispatchers.IO) {
        var done = 0
        var error: String? = null
        items.forEachIndexed { index, item ->
            ctx.reportItem(item.name, index)
            coroutineContext.ensureActive()
            ctx.awaitResume()
            if (deleteSingle(item)) {
                done++
                invalidateFor(item)
            } else if (error == null) {
                error = item.name
            }
        }
        OpResult(error == null, done, 0L, error)
    }

    private suspend fun deleteSingle(node: DocNode): Boolean =
        if (node.isDirectory) docRepository.deleteRecursive(node) else docRepository.delete(node)

    /** Spec §5: when a video disappears, its cached thumbnail and metadata go with it. */
    private suspend fun invalidateFor(node: DocNode) {
        runCatching { thumbnailRepository.invalidate(node) }
        runCatching { metadataRepository.invalidate(node) }
        if (node.isDirectory) runCatching { metadataRepository.invalidateUri(node.uri.toString()) }
    }

    // ------------------------------------------------------------------
    // ZIP / UNZIP
    // ------------------------------------------------------------------

    suspend fun zip(
        items: List<DocNode>,
        destination: DocNode,
        archiveName: String,
        ctx: OpContext,
    ): OpResult = withContext(Dispatchers.IO) {
        if (items.any { DocRelation.isSameOrDescendant(destination.uri.toString(), it.uri.toString()) }) {
            // Zipping a folder into itself writes the archive inside the tree being read.
            return@withContext OpResult(false, 0, 0, archiveName)
        }
        val cache = HashMap<String, MutableSet<String>>()
        val name = if (archiveName.endsWith(".zip", true)) archiveName else "$archiveName.zip"
        val archive = docRepository.createFile(destination, uniqueName(destination, name, cache), "application/zip")
            ?: return@withContext OpResult(false, 0, 0, archiveName)
        var bytes = 0L
        var count = 0
        var failed = false
        val output = docRepository.openOutput(archive.uri)
            ?: return@withContext OpResult(false, 0, 0, archiveName)
        try {
            ZipOutputStream(output.buffered(BUFFER_SIZE)).use { zip ->
                items.forEachIndexed { index, item ->
                    ctx.reportItem(item.name, index)
                    val written = addZipEntry(item, "", zip, ctx)
                    if (written < 0) {
                        failed = true
                    } else {
                        bytes += written
                        count++
                    }
                }
                zip.finish()
            }
            // An unreadable source fails the operation instead of quietly leaving a short or
            // empty archive behind (audit item 5).
            if (failed) {
                runCatching { docRepository.delete(archive) }
                return@withContext OpResult(false, count, bytes, archiveName)
            }
            OpResult(true, count, bytes, null)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            runCatching { docRepository.delete(archive) }
            OpResult(false, count, bytes, t.message ?: archiveName)
        }
    }

    private suspend fun addZipEntry(
        node: DocNode,
        prefix: String,
        zip: ZipOutputStream,
        ctx: OpContext,
    ): Long {
        coroutineContext.ensureActive()
        ctx.awaitResume()
        val entryName = prefix + node.name
        if (node.isDirectory) {
            zip.putNextEntry(ZipEntry("$entryName/"))
            zip.closeEntry()
            var total = 0L
            docRepository.children(node).forEach { child ->
                val written = addZipEntry(child, "$entryName/", zip, ctx)
                if (written < 0) total = -1L else if (total >= 0) total += written
            }
            return total
        }
        val entry = ZipEntry(entryName).apply {
            if (node.lastModified > 0) time = node.lastModified
        }
        zip.putNextEntry(entry)
        val input = docRepository.openInput(node.uri)
        if (input == null) {
            // Unreadable source: report failure upward instead of storing an empty entry.
            zip.closeEntry()
            return -1L
        }
        var written = 0L
        try {
            input.use { stream ->
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    coroutineContext.ensureActive()
                    ctx.awaitResume()
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    zip.write(buffer, 0, read)
                    written += read
                    ctx.reportBytes(read.toLong())
                }
            }
        } catch (t: Throwable) {
            zip.closeEntry()
            if (t is kotlinx.coroutines.CancellationException) throw t
            return -1L
        }
        zip.closeEntry()
        return written
    }

    suspend fun unzip(archive: DocNode, destination: DocNode, ctx: OpContext): OpResult =
        withContext(Dispatchers.IO) {
            val input = docRepository.openInput(archive.uri)
                ?: return@withContext OpResult(false, 0, 0, archive.name)
            val token = ctx.stagingToken.ifEmpty { OpsSafety.newToken() }
            val cache = HashMap<String, MutableSet<String>>()
            // Everything lands in a hidden staging folder first: on failure or cancellation the
            // whole extraction is deleted in one go, and the destination never shows a partial
            // tree (audit item 5).
            val staging = docRepository.createDirectory(
                destination,
                OpsSafety.stagingName(token, "unzip"),
            ) ?: run {
                runCatching { input.close() }
                return@withContext OpResult(false, 0, 0, archive.name)
            }
            val budget = OpsSafety.UnzipLimits.budgetBytes(docRepository.freeBytes(destination))
            val dirCache = HashMap<String, DocNode>()
            var count = 0
            var bytes = 0L
            try {
                if (budget <= 0) throw IllegalStateException("insufficient free space")
                ZipInputStream(input.buffered(BUFFER_SIZE)).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        ctx.awaitResume()
                        val entry = zip.nextEntry ?: break
                        if (count >= OpsSafety.UnzipLimits.MAX_ENTRIES) {
                            throw IllegalStateException("archive has too many entries")
                        }
                        val relative = entry.name.replace('\\', '/').trimStart('/')
                        val segments = relative.split('/').filter { it.isNotEmpty() }
                        // Zip-slip guard: never let an entry escape the destination folder.
                        if (relative.split('/').any { it == ".." }) {
                            zip.closeEntry()
                            continue
                        }
                        // Depth and name-length quotas: hostile archives use pathologically
                        // deep or long paths to exhaust the file system.
                        if (segments.size > OpsSafety.UnzipLimits.MAX_DEPTH ||
                            segments.any { it.length > OpsSafety.UnzipLimits.MAX_SEGMENT_LENGTH }
                        ) {
                            throw IllegalStateException("entry path too deep or too long")
                        }
                        if (entry.isDirectory) {
                            ensureDirectory(staging, relative.trimEnd('/'), dirCache)
                            zip.closeEntry()
                            continue
                        }
                        val parentPath = relative.substringBeforeLast('/', "")
                        val parent = if (parentPath.isEmpty()) {
                            staging
                        } else {
                            ensureDirectory(staging, parentPath, dirCache) ?: staging
                        }
                        val fileName = relative.substringAfterLast('/')
                        if (fileName.isEmpty()) {
                            zip.closeEntry()
                            continue
                        }
                        ctx.reportItem(fileName, count)
                        val target = docRepository.createFile(parent, fileName, null)
                        if (target == null) {
                            zip.closeEntry()
                            continue
                        }
                        val output = docRepository.openOutput(target.uri)
                        if (output == null) {
                            zip.closeEntry()
                            continue
                        }
                        output.use { o ->
                            val buffer = ByteArray(BUFFER_SIZE)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                // The budget is enforced on bytes actually written: declared
                                // entry sizes can lie, so a ZIP bomb dies here instead of at
                                // zero free space.
                                if (!OpsSafety.UnzipLimits.fits(budget, bytes, read.toLong())) {
                                    throw IllegalStateException("extraction exceeds free space")
                                }
                                o.write(buffer, 0, read)
                                bytes += read
                                ctx.reportBytes(read.toLong())
                            }
                        }
                        count++
                        zip.closeEntry()
                    }
                }
                // Commit: move the staged children into the destination under collision-free
                // names, then drop the staging folder.
                docRepository.children(staging).forEach { child ->
                    coroutineContext.ensureActive()
                    val finalName = uniqueName(destination, child.name, cache)
                    val prepared = if (finalName != child.name) {
                        docRepository.rename(child, finalName) ?: child
                    } else {
                        child
                    }
                    val moved = docRepository.moveTo(prepared, destination)
                    if (moved == null) {
                        val copied = copyNode(prepared, destination, ctx, cache)
                        if (copied.first) {
                            if (prepared.isDirectory) {
                                docRepository.deleteRecursive(prepared)
                            } else {
                                docRepository.delete(prepared)
                            }
                        }
                    }
                }
                runCatching { docRepository.deleteRecursive(staging) }
                OpResult(true, count, bytes, null)
            } catch (t: Throwable) {
                runCatching { docRepository.deleteRecursive(staging) }
                if (t is kotlinx.coroutines.CancellationException) throw t
                OpResult(false, count, bytes, t.message ?: archive.name)
            }
        }

    private suspend fun ensureDirectory(
        root: DocNode,
        relativePath: String,
        cache: HashMap<String, DocNode>,
    ): DocNode? {
        if (relativePath.isEmpty()) return root
        cache[relativePath]?.let { return it }
        val segments = relativePath.split('/').filter { it.isNotEmpty() && it != ".." }
        var current = root
        val path = StringBuilder()
        for (segment in segments) {
            if (path.isNotEmpty()) path.append('/')
            path.append(segment)
            val key = path.toString()
            val cached = cache[key]
            if (cached != null) {
                current = cached
            } else {
                val existing = docRepository.childByName(current, segment)
                current = existing ?: docRepository.createDirectory(current, segment) ?: return null
                cache[key] = current
            }
        }
        cache[relativePath] = current
        return current
    }

    // ------------------------------------------------------------------
    // Bulk rename (spec §16)
    // ------------------------------------------------------------------

    suspend fun bulkRename(
        items: List<DocNode>,
        rules: BulkRenameRules,
        ctx: OpContext,
    ): OpResult = withContext(Dispatchers.IO) {
        val plan = BulkRenamePlanner.plan(items, rules)
        var done = 0
        var error: String? = null
        // Rename in reverse order when numbering shrinks names, to avoid transient collisions.
        val ordered = if (rules.numbering) plan.reversed() else plan
        ordered.forEachIndexed { index, (node, newName) ->
            ctx.reportItem(newName, index)
            coroutineContext.ensureActive()
            ctx.awaitResume()
            if (newName == node.name) {
                done++
                return@forEachIndexed
            }
            val renamed = docRepository.rename(node, newName)
            if (renamed != null) {
                done++
                invalidateFor(node)
            } else if (error == null) {
                error = node.name
            }
        }
        OpResult(error == null, done, 0L, error)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Total bytes a job will move, used for the progress denominator. */
    suspend fun estimateBytes(items: List<DocNode>): Long = withContext(Dispatchers.IO) {
        var total = 0L
        items.forEach { item ->
            total += if (item.isDirectory) {
                docRepository.directorySize(item).coerceAtLeast(0)
            } else {
                item.size.coerceAtLeast(0)
            }
        }
        total
    }

    suspend fun countItems(items: List<DocNode>): Int = withContext(Dispatchers.IO) {
        var count = 0
        val stack = ArrayDeque<DocNode>()
        items.forEach { stack.addLast(it) }
        while (stack.isNotEmpty() && count < 100_000) {
            val node = stack.removeLast()
            count++
            if (node.isDirectory) docRepository.children(node).forEach { stack.addLast(it) }
        }
        count
    }

    private suspend fun uniqueName(
        destination: DocNode,
        desired: String,
        cache: HashMap<String, MutableSet<String>>,
    ): String {
        val names = cache.getOrPut(destination.uri.toString()) {
            docRepository.children(destination).map { it.name }.toMutableSet()
        }
        if (desired !in names) {
            names += desired
            return desired
        }
        val base = desired.substringBeforeLast('.', desired)
        val ext = if (desired.contains('.') && desired.substringAfterLast('.').length <= 5) {
            "." + desired.substringAfterLast('.')
        } else {
            ""
        }
        var index = 1
        var candidate: String
        do {
            candidate = "$base ($index)$ext"
            index++
        } while (candidate in names && index < 1000)
        names += candidate
        return candidate
    }

    private companion object {
        /** 256 KB: large enough to keep USB throughput up, small enough to stay cache friendly. */
        const val BUFFER_SIZE = 256 * 1024
    }
}

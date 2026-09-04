package com.usbmediaexplorer.data.ops

import android.content.Context
import com.usbmediaexplorer.data.doc.DocNode
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

    private val listingCache = HashMap<String, MutableSet<String>>()

    // ------------------------------------------------------------------
    // Copy / move
    // ------------------------------------------------------------------

    suspend fun copy(items: List<DocNode>, destination: DocNode, ctx: OpContext): OpResult =
        withContext(Dispatchers.IO) {
            listingCache.clear()
            var done = 0
            var bytes = 0L
            var error: String? = null
            items.forEachIndexed { index, item ->
                ctx.reportItem(item.name, index)
                val result = copyNode(item, destination, ctx)
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
            listingCache.clear()
            var done = 0
            var bytes = 0L
            var error: String? = null
            items.forEachIndexed { index, item ->
                ctx.reportItem(item.name, index)
                coroutineContext.ensureActive()
                ctx.awaitResume()
                val fastMove = runCatching { docRepository.moveTo(item, destination) }.getOrNull()
                if (fastMove != null) {
                    done++
                    invalidateFor(item)
                } else {
                    val result = copyNode(item, destination, ctx)
                    if (result.first) {
                        val deleted = deleteSingle(item)
                        if (deleted) {
                            done++
                            bytes += result.second
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

    /** Returns (success, bytesWritten). */
    private suspend fun copyNode(
        source: DocNode,
        destination: DocNode,
        ctx: OpContext,
    ): Pair<Boolean, Long> {
        coroutineContext.ensureActive()
        ctx.awaitResume()
        return if (source.isDirectory) {
            val created = docRepository.createDirectory(destination, uniqueName(destination, source.name))
                ?: return false to 0L
            var total = 0L
            var ok = true
            docRepository.children(source).forEach { child ->
                val childResult = copyNode(child, created, ctx)
                ok = ok && childResult.first
                total += childResult.second
            }
            ok to total
        } else {
            val target = docRepository.createFile(
                destination,
                uniqueName(destination, source.name),
                source.mimeType,
            ) ?: return false to 0L
            val written = streamCopy(source, target, ctx)
            if (written < 0) {
                runCatching { docRepository.delete(target) }
                false to 0L
            } else {
                preserveTimestamp(source, target)
                true to written
            }
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
        listingCache.clear()
        val name = if (archiveName.endsWith(".zip", true)) archiveName else "$archiveName.zip"
        val archive = docRepository.createFile(destination, uniqueName(destination, name), "application/zip")
            ?: return@withContext OpResult(false, 0, 0, archiveName)
        var bytes = 0L
        var count = 0
        val output = docRepository.openOutput(archive.uri)
            ?: return@withContext OpResult(false, 0, 0, archiveName)
        try {
            ZipOutputStream(output.buffered(BUFFER_SIZE)).use { zip ->
                items.forEachIndexed { index, item ->
                    ctx.reportItem(item.name, index)
                    val written = addZipEntry(item, "", zip, ctx)
                    bytes += written
                    count++
                }
                zip.finish()
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
                total += addZipEntry(child, "$entryName/", zip, ctx)
            }
            return total
        }
        val entry = ZipEntry(entryName).apply {
            if (node.lastModified > 0) time = node.lastModified
        }
        zip.putNextEntry(entry)
        var written = 0L
        docRepository.openInput(node.uri)?.use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                coroutineContext.ensureActive()
                ctx.awaitResume()
                val read = input.read(buffer)
                if (read <= 0) break
                zip.write(buffer, 0, read)
                written += read
                ctx.reportBytes(read.toLong())
            }
        }
        zip.closeEntry()
        return written
    }

    suspend fun unzip(archive: DocNode, destination: DocNode, ctx: OpContext): OpResult =
        withContext(Dispatchers.IO) {
            val input = docRepository.openInput(archive.uri)
                ?: return@withContext OpResult(false, 0, 0, archive.name)
            val dirCache = HashMap<String, DocNode>()
            var count = 0
            var bytes = 0L
            try {
                ZipInputStream(input.buffered(BUFFER_SIZE)).use { zip ->
                    while (true) {
                        coroutineContext.ensureActive()
                        ctx.awaitResume()
                        val entry = zip.nextEntry ?: break
                        val relative = entry.name.replace('\\', '/').trimStart('/')
                        // Zip-slip guard: never let an entry escape the destination folder.
                        if (relative.split('/').any { it == ".." }) {
                            zip.closeEntry()
                            continue
                        }
                        if (entry.isDirectory) {
                            ensureDirectory(destination, relative.trimEnd('/'), dirCache)
                            zip.closeEntry()
                            continue
                        }
                        val parentPath = relative.substringBeforeLast('/', "")
                        val parent = if (parentPath.isEmpty()) {
                            destination
                        } else {
                            ensureDirectory(destination, parentPath, dirCache) ?: destination
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
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                o.write(buffer, 0, read)
                                bytes += read
                                ctx.reportBytes(read.toLong())
                            }
                        }
                        count++
                        zip.closeEntry()
                    }
                }
                OpResult(true, count, bytes, null)
            } catch (t: Throwable) {
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

    private suspend fun uniqueName(destination: DocNode, desired: String): String {
        val names = listingCache.getOrPut(destination.uri.toString()) {
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

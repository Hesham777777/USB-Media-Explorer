package com.usbmediaexplorer.data.doc

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.StatFs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * Backend for storage that is reachable through the classic file system: internal storage and
 * removable volumes whose mount point the app may read (typical for SD cards up to Android 10,
 * and for many USB drives when the OEM exposes `/storage/XXXX-XXXX`).
 */
class FileDocProvider(
    private val volumeResolver: VolumeResolver,
) : DocProvider {

    override fun supports(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null

    override suspend fun node(uri: Uri): DocNode? = withContext(Dispatchers.IO) {
        val file = uri.path?.let(::File) ?: return@withContext null
        if (!file.exists()) return@withContext null
        toNode(file)
    }

    override suspend fun children(node: DocNode): List<DocNode> = withContext(Dispatchers.IO) {
        val file = node.uri.path?.let(::File) ?: return@withContext emptyList()
        file.listFiles()?.map { toNode(it) } ?: emptyList()
    }

    override suspend fun parentOf(node: DocNode): DocNode? = withContext(Dispatchers.IO) {
        node.uri.path?.let(::File)?.parentFile?.takeIf { it.exists() }?.let { toNode(it) }
    }

    override suspend fun childByName(node: DocNode, name: String): DocNode? =
        withContext(Dispatchers.IO) {
            val file = File(node.uri.path ?: return@withContext null, name)
            if (file.exists()) toNode(file) else null
        }

    override suspend fun exists(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        uri.path?.let { File(it).exists() } ?: false
    }

    override suspend fun mediaChildren(node: DocNode, limit: Int): List<DocNode> =
        withContext(Dispatchers.IO) {
            val file = node.uri.path?.let(::File) ?: return@withContext emptyList()
            val out = ArrayList<DocNode>()
            file.listFiles()?.forEach { child ->
                if (out.size >= limit) return@forEach
                val kind = MediaKind.ofExtension(child.extension)
                if (kind == MediaKind.VIDEO || kind == MediaKind.IMAGE) out.add(toNode(child))
            }
            out
        }

    override suspend fun mediaCount(node: DocNode): MediaCount = withContext(Dispatchers.IO) {
        val file = node.uri.path?.let(::File) ?: return@withContext MediaCount()
        var videos = 0
        var images = 0
        var audios = 0
        var folders = 0
        var others = 0
        file.listFiles()?.forEach { child ->
            when {
                child.isDirectory -> folders++
                else -> when (MediaKind.ofExtension(child.extension)) {
                    MediaKind.VIDEO -> videos++
                    MediaKind.IMAGE -> images++
                    MediaKind.AUDIO -> audios++
                    else -> others++
                }
            }
        }
        MediaCount(videos, images, audios, folders, others)
    }

    override suspend fun directorySize(node: DocNode): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val stack = ArrayDeque<File>()
        node.uri.path?.let { stack.addLast(File(it)) }
        while (stack.isNotEmpty()) {
            val current = stack.removeLast()
            if (current.isDirectory) {
                current.listFiles()?.forEach { stack.addLast(it) }
            } else {
                total += current.length()
            }
        }
        total
    }

    override fun openInput(uri: Uri): InputStream? = runCatching {
        uri.path?.let { FileInputStream(File(it)) }
    }.getOrNull()

    override fun openOutput(uri: Uri, append: Boolean): OutputStream? = runCatching {
        uri.path?.let { FileOutputStream(File(it), append) }
    }.getOrNull()

    override fun openFd(uri: Uri, mode: String): ParcelFileDescriptor? = runCatching {
        val file = uri.path?.let(::File) ?: return@runCatching null
        val pfdMode = when (mode.lowercase(Locale.US)) {
            "r" -> ParcelFileDescriptor.MODE_READ_ONLY
            "w", "wt" -> ParcelFileDescriptor.MODE_WRITE_ONLY or
                ParcelFileDescriptor.MODE_CREATE or ParcelFileDescriptor.MODE_TRUNCATE
            "wa" -> ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
                ParcelFileDescriptor.MODE_APPEND
            "rw" -> ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
            else -> ParcelFileDescriptor.MODE_READ_ONLY
        }
        ParcelFileDescriptor.open(file, pfdMode)
    }.getOrNull()

    override suspend fun createDirectory(parent: DocNode, name: String): DocNode? =
        withContext(Dispatchers.IO) {
            val dir = File(parent.uri.path ?: return@withContext null, sanitize(name))
            if (dir.exists()) return@withContext null
            if (dir.mkdirs()) toNode(dir) else null
        }

    override suspend fun createFile(parent: DocNode, name: String, mimeType: String?): DocNode? =
        withContext(Dispatchers.IO) {
            val file = File(parent.uri.path ?: return@withContext null, sanitize(name))
            if (file.exists()) return@withContext null
            runCatching {
                file.parentFile?.mkdirs()
                if (file.createNewFile()) toNode(file) else null
            }.getOrNull()
        }

    override suspend fun rename(node: DocNode, newName: String): DocNode? =
        withContext(Dispatchers.IO) {
            val source = File(node.uri.path ?: return@withContext null)
            val target = File(source.parentFile ?: return@withContext null, sanitize(newName))
            if (target.exists()) return@withContext null
            if (source.renameTo(target)) toNode(target) else null
        }

    override suspend fun delete(node: DocNode): Boolean = withContext(Dispatchers.IO) {
        node.uri.path?.let { File(it).delete() } ?: false
    }

    override suspend fun deleteRecursive(node: DocNode): Boolean = withContext(Dispatchers.IO) {
        node.uri.path?.let { File(it).deleteRecursively() } ?: false
    }

    override suspend fun moveTo(node: DocNode, targetParent: DocNode): DocNode? =
        withContext(Dispatchers.IO) {
            if (targetParent.uri.scheme != ContentResolver.SCHEME_FILE) return@withContext null
            val source = File(node.uri.path ?: return@withContext null)
            val target = File(targetParent.uri.path ?: return@withContext null, source.name)
            if (target.exists()) return@withContext null
            // A plain rename is used when possible: same volume => instant move.
            val moved = if (source.renameTo(target)) true else copyTree(source, target)
            if (moved) toNode(target) else null
        }

    override fun freeBytes(node: DocNode): Long? = runCatching {
        node.uri.path?.let { StatFs(it).availableBytes }
    }.getOrNull()

    override fun totalBytes(node: DocNode): Long? = runCatching {
        node.uri.path?.let { StatFs(it).totalBytes }
    }.getOrNull()

    override fun fileSystemLabel(node: DocNode): String? = null

    // ------------------------------------------------------------------

    private fun copyTree(source: File, target: File): Boolean = runCatching {
        if (source.isDirectory) {
            target.mkdirs()
            source.listFiles()?.forEach { child ->
                if (!copyTree(child, File(target, child.name))) return@runCatching false
            }
            source.deleteRecursively()
        } else {
            source.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            source.delete()
        }
        true
    }.getOrDefault(false)

    fun toNode(file: File): DocNode {
        val ref = volumeResolver.resolve(Uri.fromFile(file))
        return DocNode(
            uri = Uri.fromFile(file),
            name = file.name.ifEmpty { file.path },
            isDirectory = file.isDirectory,
            size = if (file.isDirectory) DocNode.UNKNOWN_SIZE else file.length(),
            lastModified = file.lastModified(),
            mimeType = if (file.isDirectory) DocumentsContractMime.DIR else guessMime(file.name),
            volumeId = ref.id,
            displayPath = file.absolutePath,
            isWritable = file.canWrite(),
            canCreateChildren = file.isDirectory && file.canWrite(),
        )
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[/:\\\\*?\"<>|]"), "_").ifEmpty { "untitled" }

    private fun guessMime(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        if (ext.isEmpty() || ext == name.lowercase(Locale.US)) return null
        return MediaKind.mimeTypeFor(ext)
    }
}

/** Small constant holder so the file backend does not need DocumentsContract. */
internal object DocumentsContractMime {
    const val DIR = "vnd.android.document/directory"
}

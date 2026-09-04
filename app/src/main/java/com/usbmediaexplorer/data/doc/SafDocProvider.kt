package com.usbmediaexplorer.data.doc

import android.content.ContentResolver
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework backend — the only way to reach a USB OTG drive on modern Android.
 *
 * Key points:
 *  - Listing uses [DocumentsContract.buildChildDocumentsUriUsingTree] with an explicit
 *    projection, so one cursor read gives name/size/date/mime/flags for the whole folder.
 *    This is what keeps a 5 000-file folder usable (spec §22).
 *  - [openFd] returns a real file descriptor. [android.media.MediaMetadataRetriever] and Media3
 *    can then seek *inside* the video on the stick and decode a frame without copying anything.
 *  - All mutating calls go through DocumentsContract so the provider keeps its own metadata
 *    (MediaStore, thumbnails on the drive, FAT/exFAT timestamps) consistent.
 */
class SafDocProvider(
    private val context: Context,
    private val volumeResolver: VolumeResolver,
    private val grantedTrees: () -> List<Uri>,
) : DocProvider {

    private val resolver: ContentResolver get() = context.contentResolver

    private val projection = arrayOf(
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_SIZE,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
    )

    override fun supports(uri: Uri): Boolean = uri.scheme == ContentResolver.SCHEME_CONTENT

    override suspend fun node(uri: Uri): DocNode? = withContext(Dispatchers.IO) {
        val docUri = documentUriFor(uri) ?: return@withContext null
        query(docUri) { cursor -> if (cursor.moveToFirst()) rowToNode(docUri, cursor) else null }
    }

    override suspend fun children(node: DocNode): List<DocNode> = withContext(Dispatchers.IO) {
        val treeUri = treeFor(node.uri) ?: return@withContext emptyList()
        val docId = DocUri.documentIdOf(node.uri) ?: return@withContext emptyList()
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val nodes = ArrayList<DocNode>()
        query(childrenUri) { cursor ->
            while (cursor.moveToNext()) {
                val id = cursor.getString(0) ?: continue
                val childUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id)
                nodes.add(rowToNode(childUri, cursor, docIdOverride = id))
            }
        }
        nodes
    }

    override suspend fun parentOf(node: DocNode): DocNode? = withContext(Dispatchers.IO) {
        val treeUri = treeFor(node.uri) ?: return@withContext null
        val docId = DocUri.documentIdOf(node.uri) ?: return@withContext null
        val parentPath = docId.substringBeforeLast('/', "")
        if (parentPath.isEmpty()) return@withContext null
        val parentUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, parentPath)
        node(parentUri)
    }

    override suspend fun childByName(node: DocNode, name: String): DocNode? =
        children(node).firstOrNull { it.name.equals(name, ignoreCase = true) }

    override suspend fun exists(uri: Uri): Boolean = node(uri) != null

    override suspend fun mediaChildren(node: DocNode, limit: Int): List<DocNode> =
        withContext(Dispatchers.IO) {
            val out = ArrayList<DocNode>()
            for (child in children(node)) {
                if (out.size >= limit) break
                if (child.kind == MediaKind.VIDEO || child.kind == MediaKind.IMAGE) out.add(child)
            }
            out
        }

    override suspend fun mediaCount(node: DocNode): MediaCount = withContext(Dispatchers.IO) {
        var videos = 0
        var images = 0
        var audios = 0
        var folders = 0
        var others = 0
        for (child in children(node)) {
            when (child.kind) {
                MediaKind.DIRECTORY -> folders++
                MediaKind.VIDEO -> videos++
                MediaKind.IMAGE -> images++
                MediaKind.AUDIO -> audios++
                else -> others++
            }
        }
        MediaCount(videos, images, audios, folders, others)
    }

    override suspend fun directorySize(node: DocNode): Long = withContext(Dispatchers.IO) {
        var total = 0L
        val stack = ArrayDeque<DocNode>()
        stack.addLast(node)
        // Hard stop so a pathological tree can never hang the UI.
        var visited = 0
        while (stack.isNotEmpty() && visited < 20_000) {
            val current = stack.removeLast()
            visited++
            val kids = runCatching { children(current) }.getOrDefault(emptyList())
            for (child in kids) {
                if (child.isDirectory) stack.addLast(child) else total += child.size.coerceAtLeast(0)
            }
        }
        total
    }

    override fun openInput(uri: Uri): InputStream? = runCatching {
        resolver.openInputStream(documentUriFor(uri) ?: uri)
    }.getOrNull()

    override fun openOutput(uri: Uri, append: Boolean): OutputStream? = runCatching {
        val docUri = documentUriFor(uri) ?: uri
        resolver.openOutputStream(docUri, if (append) "wa" else "w")
    }.getOrNull()

    override fun openFd(uri: Uri, mode: String): ParcelFileDescriptor? = runCatching {
        resolver.openFileDescriptor(documentUriFor(uri) ?: uri, mode)
    }.getOrNull()

    override suspend fun createDirectory(parent: DocNode, name: String): DocNode? =
        create(parent, sanitize(name), DocumentsContract.Document.MIME_TYPE_DIR)

    override suspend fun createFile(parent: DocNode, name: String, mimeType: String?): DocNode? {
        val clean = sanitize(name)
        val ext = clean.substringAfterLast('.', "").lowercase()
        val mime = mimeType ?: if (ext.isNotEmpty() && ext != clean.lowercase()) {
            MediaKind.mimeTypeFor(ext)
        } else {
            "application/octet-stream"
        }
        return create(parent, clean, mime)
    }

    private suspend fun create(parent: DocNode, name: String, mime: String): DocNode? =
        withContext(Dispatchers.IO) {
            val parentUri = documentUriFor(parent.uri) ?: return@withContext null
            runCatching {
                val created = DocumentsContract.createDocument(resolver, parentUri, mime, name)
                created?.let { node(it) }
            }.getOrNull()
        }

    override suspend fun rename(node: DocNode, newName: String): DocNode? = withContext(Dispatchers.IO) {
        val docUri = documentUriFor(node.uri) ?: return@withContext null
        runCatching {
            val renamed = DocumentsContract.renameDocument(resolver, docUri, sanitize(newName))
            when {
                renamed != null -> node(renamed)
                else -> {
                    // Some providers return null but still rename; re-read the parent to confirm.
                    val parent = parentOf(node) ?: return@runCatching null
                    childByName(parent, sanitize(newName))
                }
            }
        }.getOrNull()
    }

    override suspend fun delete(node: DocNode): Boolean = withContext(Dispatchers.IO) {
        val docUri = documentUriFor(node.uri) ?: return@withContext false
        runCatching { DocumentsContract.deleteDocument(resolver, docUri) }.getOrDefault(false)
    }

    /** SAF deleteDocument is already recursive for directories. */
    override suspend fun deleteRecursive(node: DocNode): Boolean = delete(node)

    override suspend fun moveTo(node: DocNode, targetParent: DocNode): DocNode? =
        withContext(Dispatchers.IO) {
            val sourceUri = documentUriFor(node.uri) ?: return@withContext null
            val targetUri = documentUriFor(targetParent.uri) ?: return@withContext null
            if (sourceUri.authority != targetUri.authority) return@withContext null
            runCatching {
                val sourceParent = parentOf(node)?.uri?.let { documentUriFor(it) }
                DocumentsContract.moveDocument(resolver, sourceUri, sourceParent, targetUri)
                    ?.let { node(it) }
            }.getOrNull()
        }

    override fun freeBytes(node: DocNode): Long? = statFs(node)?.second

    override fun totalBytes(node: DocNode): Long? = statFs(node)?.first

    /**
     * SAF has no API for free space, so fall back to the mount point when the device exposes one
     * (very common for SD cards and for USB drives on OEM builds that keep `/storage/XXXX-XXXX`
     * world-readable). Returns null when unavailable and the UI shows "—".
     */
    private fun statFs(node: DocNode): Pair<Long, Long>? {
        val file = DocUri.toFileOrNull(context, node.uri) ?: return null
        return runCatching {
            val stats = android.os.StatFs(file.absolutePath)
            stats.totalBytes to stats.availableBytes
        }.getOrNull()
    }

    override fun fileSystemLabel(node: DocNode): String? = null

    // ------------------------------------------------------------------

    private fun <T> query(uri: Uri, block: (Cursor) -> T): T? = runCatching {
        resolver.query(uri, projection, null, null, null)?.use { cursor -> block(cursor) }
    }.getOrNull()

    private fun documentUriFor(uri: Uri): Uri? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val segments = uri.pathSegments
        return if (segments.firstOrNull() == "tree" && !segments.contains("document")) {
            runCatching {
                DocumentsContract.buildDocumentUriUsingTree(uri, DocumentsContract.getTreeDocumentId(uri))
            }.getOrNull()
        } else {
            uri
        }
    }

    private fun treeFor(uri: Uri): Uri? = DocUri.treeUriFor(uri, grantedTrees())

    private fun rowToNode(uri: Uri, cursor: Cursor, docIdOverride: String? = null): DocNode {
        val docId = docIdOverride ?: cursor.getString(0) ?: DocUri.documentIdOf(uri).orEmpty()
        val name = cursor.getString(1).orEmpty()
        val mime = cursor.getString(2)
        val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mime
        val size = if (cursor.isNull(3)) DocNode.UNKNOWN_SIZE else cursor.getLong(3)
        val modified = if (cursor.isNull(4)) 0L else cursor.getLong(4)
        val flags = if (cursor.isNull(5)) 0 else cursor.getInt(5)
        val ref = volumeResolver.resolve(uri)
        val treeUri = treeFor(uri)
        val display = if (treeUri != null) {
            DocUri.displayPath(ref.label, treeUri, uri)
        } else {
            uri.path ?: name
        }
        return DocNode(
            uri = uri,
            name = name.ifEmpty { docId.substringAfterLast('/') },
            isDirectory = isDir,
            size = if (isDir) DocNode.UNKNOWN_SIZE else size,
            lastModified = modified,
            mimeType = mime,
            volumeId = ref.id,
            displayPath = display,
            isWritable = flags and DocumentsContract.Document.FLAG_SUPPORTS_WRITE != 0,
            canCreateChildren = isDir &&
                flags and DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE != 0,
            documentId = docId,
        )
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[/:\\\\]"), "_").ifEmpty { "untitled" }
}

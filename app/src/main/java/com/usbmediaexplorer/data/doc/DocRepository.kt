package com.usbmediaexplorer.data.doc

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.volume.VolumeRepository
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Facade that routes every operation to the right backend ([FileDocProvider] or
 * [SafDocProvider]) and adds cross-cutting helpers the UI needs: breadcrumbs, share URIs and
 * "open with" URIs.
 */
class DocRepository(
    private val context: Context,
    private val volumeRepository: VolumeRepository,
) : DocProvider {

    val fileProvider = FileDocProvider(volumeRepository.resolver)
    val safProvider = SafDocProvider(context, volumeRepository.resolver) {
        volumeRepository.grantedTrees()
    }

    fun providerFor(uri: Uri): DocProvider =
        if (uri.scheme == ContentResolver.SCHEME_FILE || uri.scheme == null) fileProvider else safProvider

    fun providerFor(node: DocNode): DocProvider = providerFor(node.uri)

    override fun supports(uri: Uri) = true

    override suspend fun node(uri: Uri): DocNode? = providerFor(uri).node(uri)

    override suspend fun children(node: DocNode): List<DocNode> = providerFor(node).children(node)

    override suspend fun parentOf(node: DocNode): DocNode? = providerFor(node).parentOf(node)

    override suspend fun childByName(node: DocNode, name: String): DocNode? =
        providerFor(node).childByName(node, name)

    override suspend fun exists(uri: Uri): Boolean = providerFor(uri).exists(uri)

    override suspend fun coverScan(
        node: DocNode,
        imageLimit: Int,
        videoNameLimit: Int,
        folderLimit: Int,
    ): FolderScan = providerFor(node).coverScan(node, imageLimit, videoNameLimit, folderLimit)

    override suspend fun mediaChildren(node: DocNode, limit: Int): List<DocNode> =
        providerFor(node).mediaChildren(node, limit)

    override suspend fun mediaCount(node: DocNode): MediaCount = providerFor(node).mediaCount(node)

    override suspend fun directorySize(node: DocNode): Long = providerFor(node).directorySize(node)

    override fun openInput(uri: Uri): InputStream? = providerFor(uri).openInput(uri)

    override fun openOutput(uri: Uri, append: Boolean): OutputStream? =
        providerFor(uri).openOutput(uri, append)

    override fun openFd(uri: Uri, mode: String): ParcelFileDescriptor? =
        providerFor(uri).openFd(uri, mode)

    override suspend fun createDirectory(parent: DocNode, name: String): DocNode? =
        providerFor(parent).createDirectory(parent, name)

    override suspend fun createFile(parent: DocNode, name: String, mimeType: String?): DocNode? =
        providerFor(parent).createFile(parent, name, mimeType)

    override suspend fun rename(node: DocNode, newName: String): DocNode? =
        providerFor(node).rename(node, newName)

    override suspend fun delete(node: DocNode): Boolean = providerFor(node).delete(node)

    override suspend fun deleteRecursive(node: DocNode): Boolean =
        providerFor(node).deleteRecursive(node)

    /**
     * Move that also works across backends: a same-volume SAF move first, then a plain rename
     * for file storage, and finally a "not supported here" null so the caller can fall back to
     * copy + delete through [com.usbmediaexplorer.data.ops.FileOpsEngine].
     */
    override suspend fun moveTo(node: DocNode, targetParent: DocNode): DocNode? {
        if (providerFor(node) !== providerFor(targetParent)) return null
        return providerFor(node).moveTo(node, targetParent)
    }

    override fun freeBytes(node: DocNode): Long? = providerFor(node).freeBytes(node)

    override fun totalBytes(node: DocNode): Long? = providerFor(node).totalBytes(node)

    override fun fileSystemLabel(node: DocNode): String? = providerFor(node).fileSystemLabel(node)

    // ------------------------------------------------------------------

    /**
     * Breadcrumb trail from the volume root to [node].
     *
     * Ancestors are synthesised from the path/document-id instead of being queried one by one —
     * on a slow USB stick, N queries just to draw a path bar is not acceptable.
     */
    fun breadcrumb(node: DocNode): List<DocNode> {
        val volume = volumeRepository.volumeById(node.volumeId)
            ?: volumeRepository.volumeFor(node.uri)
        val rootLabel = volume?.name ?: context.getString(R.string.volume_internal)

        return if (node.uri.scheme == ContentResolver.SCHEME_FILE) {
            val path = node.uri.path ?: return listOf(node)
            val segments = path.split('/').filter { it.isNotEmpty() }
            val trail = ArrayList<DocNode>(segments.size + 1)
            trail += synthetic(rootLabel, Uri.fromFile(File("/")), isDirectory = true, node.volumeId, "/")
            var acc = ""
            segments.forEachIndexed { index, segment ->
                acc += "/$segment"
                trail += synthetic(
                    name = segment,
                    uri = Uri.fromFile(File(acc)),
                    isDirectory = index < segments.lastIndex,
                    volumeId = node.volumeId,
                    displayPath = acc,
                )
            }
            trail
        } else {
            val tree = DocUri.treeUriFor(node.uri, volumeRepository.grantedTrees())
            if (tree == null) return listOf(node)
            val docId = DocUri.documentIdOf(node.uri) ?: return listOf(node)
            val storagePart = docId.substringBefore(':', "primary")
            val relative = docId.substringAfter(':', "")
            val segments = relative.split('/').filter { it.isNotEmpty() }
            val trail = ArrayList<DocNode>(segments.size + 1)
            trail += synthetic(
                name = rootLabel,
                uri = DocumentsContract.buildDocumentUriUsingTree(tree, storagePart),
                isDirectory = true,
                volumeId = node.volumeId,
                displayPath = rootLabel,
                documentId = storagePart,
            )
            var acc = storagePart
            segments.forEachIndexed { index, segment ->
                acc += "/$segment"
                trail += synthetic(
                    name = segment,
                    uri = DocumentsContract.buildDocumentUriUsingTree(tree, acc),
                    isDirectory = index < segments.lastIndex,
                    volumeId = node.volumeId,
                    displayPath = "$rootLabel › " + segments.take(index + 1).joinToString(" › "),
                    documentId = acc,
                )
            }
            trail
        }
    }

    private fun synthetic(
        name: String,
        uri: Uri,
        isDirectory: Boolean,
        volumeId: String,
        displayPath: String,
        documentId: String? = null,
    ) = DocNode(
        uri = uri,
        name = name,
        isDirectory = isDirectory,
        size = DocNode.UNKNOWN_SIZE,
        lastModified = 0L,
        mimeType = if (isDirectory) DocumentsContract.Document.MIME_TYPE_DIR else null,
        volumeId = volumeId,
        displayPath = displayPath,
        documentId = documentId,
    )

    /**
     * URI suitable for ACTION_SEND / ACTION_VIEW from another app. File-backed nodes go through
     * the FileProvider so no `file://` URI ever leaks (FileUriExposedException).
     */
    fun externalUri(node: DocNode): Uri? = runCatching {
        if (node.uri.scheme == ContentResolver.SCHEME_FILE) {
            val file = File(node.uri.path ?: return@runCatching null)
            FileProvider.getUriForFile(context, context.packageName + ".fileprovider", file)
        } else {
            node.uri
        }
    }.getOrNull()

    suspend fun resolveOrNull(uriString: String?): DocNode? {
        if (uriString.isNullOrBlank()) return null
        return runCatching { node(Uri.parse(uriString)) }.getOrNull()
    }

    /** Directories that the app itself owns (used for ZIP extraction targets etc.). */
    fun internalCacheDir(subDir: String): File =
        File(context.cacheDir, subDir).apply { mkdirs() }
}

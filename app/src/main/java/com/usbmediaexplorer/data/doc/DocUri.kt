package com.usbmediaexplorer.data.doc

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File

/**
 * URI gymnastics for the Storage Access Framework.
 *
 * Every tree granted by the user produces URIs of the shape
 * `content://<authority>/tree/<treeId>/document/<docId>`. To list children we need the tree
 * part even when we only hold a document URI, so [treeUriFor] reconstructs it (falling back to
 * the persisted grants when a plain `…/document/…` URI shows up).
 */
object DocUri {

    fun isTree(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.pathSegments.firstOrNull() == "tree"

    fun isDocument(uri: Uri): Boolean =
        uri.scheme == ContentResolver.SCHEME_CONTENT && uri.pathSegments.firstOrNull() == "document"

    /** The volume UUID ("1A2B-3C4D") encoded in an external-storage document id, if any. */
    fun volumeUuid(uri: Uri): String? {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return null
        val first = docId.split(':', '/').firstOrNull() ?: return null
        return if (first.matches(Regex("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}"))) first.uppercase() else null
    }

    fun isPrimaryStorage(uri: Uri): Boolean {
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()
            ?: runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            ?: return false
        return docId.startsWith("primary")
    }

    /**
     * Returns the tree URI that owns [uri], or null when the app has no tree grant for it.
     * [grantedTrees] is consulted for standalone document URIs.
     */
    fun treeUriFor(uri: Uri, grantedTrees: List<Uri>): Uri? {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return null
        val segments = uri.pathSegments
        if (segments.size >= 2 && segments[0] == "tree") {
            return Uri.Builder()
                .scheme(ContentResolver.SCHEME_CONTENT)
                .authority(uri.authority)
                .appendPath("tree")
                .appendPath(segments[1])
                .build()
        }
        val docId = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        // Prefer the most specific (deepest) granted tree that is a prefix of this document.
        return grantedTrees
            .filter { it.authority == uri.authority }
            .mapNotNull { tree ->
                val treeId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
                    ?: return@mapNotNull null
                if (docId == treeId || docId.startsWith("$treeId/")) treeId.length to tree else null
            }
            .maxByOrNull { it.first }
            ?.second
    }

    fun documentIdOf(uri: Uri): String? = runCatching {
        if (isTree(uri) && !uri.pathSegments.contains("document")) {
            DocumentsContract.getTreeDocumentId(uri)
        } else {
            DocumentsContract.getDocumentId(uri)
        }
    }.getOrNull()

    /** Builds a display path such as "USB › Movies › 2025". */
    fun displayPath(volumeLabel: String, treeUri: Uri, nodeUri: Uri): String {
        val docId = documentIdOf(nodeUri) ?: return volumeLabel
        val treeId = documentIdOf(treeUri) ?: ""
        val relative = if (treeId.isNotEmpty() && docId.startsWith("$treeId/")) {
            docId.removePrefix("$treeId/")
        } else {
            docId.substringAfter(':')
        }
        val parts = relative.split('/').filter { it.isNotEmpty() }
        val label = volumeLabel.ifEmpty { treeId.substringAfter(':', treeId) }
        return if (parts.isEmpty()) label else listOf(label).plus(parts).joinToString(" › ")
    }

    /** Best-effort [File] for a content URI; only works for file-backed documents. */
    fun toFileOrNull(context: Context, uri: Uri): File? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let(::File)
        val path = queryFilePath(context, uri) ?: return null
        val file = File(path)
        return if (file.exists()) file else null
    }

    private fun queryFilePath(context: Context, uri: Uri): String? = runCatching {
        // "_data" is only exposed by MediaStore-style providers; SAF documents deliberately hide
        // the real path, which is exactly why the thumbnail pipeline works on file descriptors.
        @Suppress("DEPRECATION")
        context.contentResolver.query(uri, arrayOf("_data"), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && cursor.columnCount > 0) cursor.getString(0) else null
        }
    }.getOrNull()

    fun fileUri(path: String): Uri = Uri.fromFile(File(path))
}

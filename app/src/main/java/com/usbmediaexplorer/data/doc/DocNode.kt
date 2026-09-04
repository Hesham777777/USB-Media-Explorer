package com.usbmediaexplorer.data.doc

import android.net.Uri
import java.util.Locale

/**
 * A single entry in the file tree.
 *
 * The app talks to two very different worlds — plain [java.io.File] paths (internal storage,
 * SD cards that expose a readable path) and Storage Access Framework documents (USB OTG,
 * write-protected SD cards). [DocNode] hides that difference behind one immutable value object
 * so the UI, the cache and the file operations never branch on the storage backend.
 */
data class DocNode(
    val uri: Uri,
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: Long,
    val mimeType: String?,
    val volumeId: String,
    val displayPath: String,
    val isWritable: Boolean = true,
    val canCreateChildren: Boolean = true,
    val isTreeRoot: Boolean = false,
    val documentId: String? = null,
) {
    val extension: String
        get() = name.substringAfterLast('.', "").lowercase(Locale.US)

    val nameWithoutExtension: String
        get() = if (extension.isEmpty()) name else name.removeSuffix(".$extension")

    val kind: MediaKind
        get() = MediaKind.of(extension, mimeType, isDirectory)

    val isHidden: Boolean get() = name.startsWith(".")

    /** Identity used for cache keys, favorites, resume positions and metadata lookups. */
    val key: String get() = "$uri|$size|$lastModified"

    /** Stable identity that survives a file being modified (used for favorites). */
    val stableKey: String get() = uri.toString()

    companion object {
        const val UNKNOWN_SIZE = -1L
    }
}

/** Convenience checks used all over the UI. */
val DocNode.isVideo: Boolean get() = kind == MediaKind.VIDEO
val DocNode.isImage: Boolean get() = kind == MediaKind.IMAGE
val DocNode.isAudio: Boolean get() = kind == MediaKind.AUDIO
val DocNode.isArchive: Boolean get() = kind == MediaKind.ARCHIVE
val DocNode.isSubtitle: Boolean get() = kind == MediaKind.SUBTITLE

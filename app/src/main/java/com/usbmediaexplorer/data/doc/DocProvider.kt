package com.usbmediaexplorer.data.doc

import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.InputStream
import java.io.OutputStream

/** Which volume a URI belongs to, so nodes can be labelled "USB › Movies". */
data class VolumeRef(val id: String, val label: String)

fun interface VolumeResolver {
    fun resolve(uri: Uri): VolumeRef
}

/** Quick media tally for a folder, used by folder cards ("24 videos • 8 photos"). */
data class MediaCount(
    val videos: Int = 0,
    val images: Int = 0,
    val audios: Int = 0,
    val folders: Int = 0,
    val others: Int = 0,
) {
    val total: Int get() = videos + images + audios + folders + others
    val mediaTotal: Int get() = videos + images + audios
}

/**
 * Storage backend abstraction.
 *
 * Two implementations exist: [FileDocProvider] for path-accessible storage (internal, and SD
 * cards that expose a readable mount point) and [SafDocProvider] for everything else — most
 * importantly USB OTG drives, which on modern Android are only reachable through the Storage
 * Access Framework.
 *
 * All read/write operations are *direct* on the drive: nothing is ever copied to internal
 * storage to produce a thumbnail (spec §1 and §24).
 */
interface DocProvider {

    fun supports(uri: Uri): Boolean

    suspend fun node(uri: Uri): DocNode?

    suspend fun children(node: DocNode): List<DocNode>

    suspend fun parentOf(node: DocNode): DocNode?

    suspend fun childByName(node: DocNode, name: String): DocNode?

    suspend fun exists(uri: Uri): Boolean

    /** Media-only children, capped at [limit]; used for folder previews and playlists. */
    suspend fun mediaChildren(node: DocNode, limit: Int = Int.MAX_VALUE): List<DocNode>

    suspend fun mediaCount(node: DocNode): MediaCount

    suspend fun directorySize(node: DocNode): Long

    // ---- streams ---------------------------------------------------------
    fun openInput(uri: Uri): InputStream?

    /** [append] keeps existing bytes (used for resumable writes). */
    fun openOutput(uri: Uri, append: Boolean = false): OutputStream?

    /**
     * Random-access file descriptor. This is what makes frame extraction from a USB stick
     * possible without copying the file: MediaMetadataRetriever accepts an fd.
     */
    fun openFd(uri: Uri, mode: String = "r"): ParcelFileDescriptor?

    // ---- mutations -------------------------------------------------------
    suspend fun createDirectory(parent: DocNode, name: String): DocNode?

    suspend fun createFile(parent: DocNode, name: String, mimeType: String? = null): DocNode?

    suspend fun rename(node: DocNode, newName: String): DocNode?

    suspend fun delete(node: DocNode): Boolean

    suspend fun deleteRecursive(node: DocNode): Boolean

    /**
     * Moves within the same provider/volume. Returns null when the backend cannot move
     * (different providers) so the caller can fall back to copy + delete.
     */
    suspend fun moveTo(node: DocNode, targetParent: DocNode): DocNode?

    // ---- volume info -----------------------------------------------------
    fun freeBytes(node: DocNode): Long?

    fun totalBytes(node: DocNode): Long?

    fun fileSystemLabel(node: DocNode): String?
}

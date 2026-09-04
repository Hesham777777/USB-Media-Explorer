package com.usbmediaexplorer.data.thumb

import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.settings.FrameStrategy
import com.usbmediaexplorer.util.Hashing

/**
 * Everything that influences the bytes of a generated thumbnail. Two requests with the same
 * [cacheKey] are interchangeable, which is exactly what the disk cache and Coil rely on.
 */
data class ThumbRequest(
    val node: DocNode,
    val widthPx: Int,
    val heightPx: Int,
    val quality: Int,
    val strategy: FrameStrategy,
    val folderPreview: Boolean = false,
    val folderPreviewCount: Int = 4,
    val preferEmbeddedCover: Boolean = false,
) {
    val cacheKey: String = Hashing.md5Hex(
        buildString {
            append(node.uri.toString())
            append('|').append(node.size)
            append('|').append(node.lastModified)
            append('|').append(widthPx).append('x').append(heightPx)
            append('|').append(quality)
            append('|').append(strategy.name)
            append('|').append(if (folderPreview) "fp$folderPreviewCount" else "no")
            append('|').append(if (preferEmbeddedCover) "cover" else "frame")
            append('|').append(ENGINE_VERSION)
        },
    )

    /** Key used to drop cached entries when the file itself disappears. */
    val nodeKey: String get() = node.key

    companion object {
        /** Bumped whenever the extraction pipeline changes, invalidating old cache entries. */
        const val ENGINE_VERSION = 3
    }
}

/** Result of a thumbnail generation attempt. */
sealed interface ThumbResult {
    data class Success(val bytes: ByteArray, val fromCache: Boolean) : ThumbResult {
        override fun equals(other: Any?): Boolean =
            other is Success && fromCache == other.fromCache && bytes.contentEquals(other.bytes)

        override fun hashCode(): Int = bytes.contentHashCode() * 31 + fromCache.hashCode()
    }

    /** No mechanism could produce a preview — the UI must show the typed icon fallback. */
    data object Unavailable : ThumbResult

    /** Blocked by a user setting (previews disabled, charging-only while unplugged…). */
    data object Disabled : ThumbResult
}

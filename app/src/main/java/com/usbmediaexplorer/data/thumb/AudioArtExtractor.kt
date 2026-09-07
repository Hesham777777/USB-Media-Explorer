package com.usbmediaexplorer.data.thumb

import android.content.Context
import android.media.MediaMetadataRetriever
import android.os.ParcelFileDescriptor
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.util.AppDispatchers
import com.usbmediaexplorer.util.Bitmaps
import kotlinx.coroutines.withContext

/**
 * Album art carried by an audio file itself: ID3 `APIC` (mp3), `covr` (m4a/aac), the picture
 * block of FLAC/Vorbis, and whatever else the platform retriever knows how to read.
 *
 * Like every other preview in this app it is read from the file's own bytes — artwork is never
 * fetched from the internet (spec §4). A file without embedded art simply produces `null`, and
 * [ThumbnailRepository] remembers that so scrolling a music folder does not reopen every track.
 */
class AudioArtExtractor(
    private val context: Context,
    private val docRepository: DocRepository,
) {

    suspend fun extract(request: ThumbRequest): ByteArray? = withContext(AppDispatchers.thumbnail) {
        runCatching { extractBlocking(request.node, request) }.getOrNull()
    }

    private fun extractBlocking(node: DocNode, request: ThumbRequest): ByteArray? {
        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        try {
            // SAF documents have no usable path: go through a descriptor, then fall back to the
            // content-URI overload for the rare provider that refuses descriptors.
            pfd = docRepository.openFd(node.uri, "r")
            val fdOpened = pfd != null && runCatching {
                retriever.setDataSource(pfd.fileDescriptor)
                true
            }.getOrDefault(false)
            if (!fdOpened) {
                runCatching { retriever.setDataSource(context, node.uri) }
            }

            val picture = retriever.embeddedPicture ?: return null
            // Artwork can be a full-size 1500×1500 scan; decode it down to the card size.
            val bitmap = Bitmaps.decodeSampled(
                streamProvider = { picture.inputStream() },
                reqWidth = request.widthPx,
                reqHeight = request.heightPx,
            ) ?: return null
            val scaled = Bitmaps.fitInside(bitmap, request.widthPx, request.heightPx)
            return Bitmaps.encode(scaled, request.quality).also {
                if (scaled !== bitmap && !bitmap.isRecycled) bitmap.recycle()
                if (!scaled.isRecycled) scaled.recycle()
            }
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { retriever.release() }
            runCatching { pfd?.close() }
        }
    }
}

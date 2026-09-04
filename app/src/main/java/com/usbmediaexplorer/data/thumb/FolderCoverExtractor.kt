package com.usbmediaexplorer.data.thumb

import android.graphics.BitmapFactory
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.doc.FolderScan
import com.usbmediaexplorer.util.AppDispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Folder Cover — the artwork of a *folder*, taken from a poster image that lives inside it.
 *
 * Kept architecturally separate on purpose (three different jobs, three different classes):
 *  - [VideoFrameExtractor] → a real frame from inside a video file,
 *  - [ImageThumbExtractor] → the picture itself,
 *  - [FolderCoverExtractor] → this: which image inside a folder represents the folder.
 *
 * Guarantees:
 *  - **read-only**: the folder is only listed and the chosen image only read; nothing is copied,
 *    moved, renamed or written on the storage volume,
 *  - **no full-size decoding to choose**: candidates are ranked by name, then at most
 *    [CoverRules.rank]'s probe budget get a header-only read (`inJustDecodeBounds`) for their
 *    dimensions, so a library of thousands of movie folders never loads their posters at once,
 *  - **works on USB/OTG**: listing goes through [DocRepository] (File *or* SAF provider) and the
 *    pixels are read with `ContentResolver.openInputStream`, so a card that is not indexed in
 *    MediaStore still gets a cover,
 *  - **the original is never duplicated**: only a downsampled WebP (grid-sized) goes to the cache.
 */
class FolderCoverExtractor(
    private val docRepository: DocRepository,
    private val imageExtractor: ImageThumbExtractor,
) {

    /**
     * Returns the encoded cover bytes, or null when the folder holds no usable image — the caller
     * then draws the ordinary folder icon (no error, no empty card).
     */
    suspend fun extract(request: ThumbRequest): ByteArray? = withContext(AppDispatchers.thumbnail) {
        coroutineContext.ensureActive()

        // One bounded pass serves both jobs: the image candidates and the movie names used to
        // recognise "the poster that belongs to this movie". Providers stop early once both limits
        // are reached, so a folder with thousands of files costs the same as a small one.
        val scan = runCatching {
            docRepository.coverScan(request.node, request.coverScanLimit, MAX_MOVIE_NAMES)
        }.getOrDefault(FolderScan.EMPTY)

        val images = scan.images
        if (images.isEmpty()) return@withContext null
        val byName = images.associateBy { it.name }

        val ordered = CoverRules.rank(
            folderName = request.node.name,
            candidates = images.map {
                CoverRules.Candidate(
                    name = it.name,
                    sizeBytes = it.size,
                    hidden = it.isHidden,
                )
            },
            maxProbes = MAX_PROBES,
            videoBaseNames = scan.videoNames,
        ) { candidate ->
            val node = byName[candidate.name]
            if (node == null) candidate else measure(node, candidate)
        }

        // Try the best few: an exotic RAW or a truncated file must not cost the folder its cover.
        ordered.take(MAX_DECODE_ATTEMPTS).forEach { candidate ->
            coroutineContext.ensureActive()
            val node = byName[candidate.name] ?: return@forEach
            imageExtractor.extract(request.copy(node = node, folderCover = false))
                ?.let { return@withContext it }
        }
        null
    }

    /**
     * Header-only probe: reads just enough of the file to learn its dimensions. A few hundred bytes
     * per candidate, no bitmap, no memory pressure — and it works over SAF/USB like any other read.
     */
    private fun measure(node: DocNode, candidate: CoverRules.Candidate): CoverRules.Candidate {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            // With inJustDecodeBounds the decode returns null *by design*; only outWidth/outHeight
            // matter, and only the file header is read — the pixels are never touched here.
            docRepository.openInput(node.uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }
        }
        return if (options.outWidth > 0 && options.outHeight > 0) {
            candidate.copy(width = options.outWidth, height = options.outHeight)
        } else {
            candidate
        }
    }

    private companion object {
        /** How many movie names are used for the "poster belongs to this movie" match. */
        const val MAX_MOVIE_NAMES = 8

        /** Header probes spent per folder. Bounded, so a library of folders stays fast. */
        const val MAX_PROBES = 4

        /** How many ranked candidates may be decoded before giving up on the folder. */
        const val MAX_DECODE_ATTEMPTS = 3
    }
}

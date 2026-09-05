package com.usbmediaexplorer.data.thumb

import android.graphics.BitmapFactory
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.doc.FolderScan
import com.usbmediaexplorer.util.AppDispatchers
import com.usbmediaexplorer.util.CoverNames
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
 * The choice is a **name rule**, not "whatever image is in there" ([CoverNames], [CoverRules]):
 *
 * ```
 * poster  >  folder  >  cover        (any image extension, case-insensitive)
 * ```
 *
 * `movie.jpg`, `image.jpg`, `screenshot.jpg`, `photo.jpg` or any other picture is never used as a
 * cover; when nothing matches, this returns null and the UI draws the ordinary folder icon.
 *
 * Libraries are hierarchical, so the search is too: a multi-part movie (`Film/poster.jpg` with
 * `Part 1`, `Part 2`, `Part 3` inside) and a series (`Series/poster.jpg` with `Season 1`,
 * `Season 2` inside) are answered by the folder itself, and when the folder holds no cover the
 * search descends a bounded depth — a season folder that keeps its own `folder.jpg` still gives the
 * series a cover. No video file is required anywhere: a folder of folders is a valid movie folder.
 *
 * Guarantees:
 *  - **read-only**: folders are only listed and the chosen image only read; nothing is copied,
 *    moved, renamed or written on the storage volume,
 *  - **bounded**: at most [MAX_VISITS] folder listings and [CoverRules.MAX_PROBES] header probes
 *    per cover, so a library of thousands of movie folders stays fast,
 *  - **no full-size decoding to choose**: dimensions come from `inJustDecodeBounds`, i.e. from the
 *    file header only, and only for images that are already covers by name,
 *  - **works on USB/OTG**: listing goes through [DocRepository] (File *or* SAF provider) and the
 *    pixels are read with `ContentResolver.openInputStream`, so a card that is not indexed in
 *    MediaStore still gets a cover,
 *  - **the original is never duplicated**: only a downsampled WebP (grid-sized) goes to the cache,
 *    and [ThumbnailRepository] caches a "no cover" answer too, so scrolling never re-walks a folder.
 */
class FolderCoverExtractor(
    private val docRepository: DocRepository,
    private val imageExtractor: ImageThumbExtractor,
) {

    /** An image that is a cover by name, together with the node needed to read it. */
    private class Found(val node: DocNode, val candidate: CoverRules.Candidate)

    /**
     * Returns the encoded cover bytes, or null when the folder (and its bounded sub-folders) holds
     * no image named `poster`, `folder` or `cover` — the caller then draws the ordinary folder icon
     * (no error, no empty card, and never an unrelated picture).
     */
    suspend fun extract(request: ThumbRequest): ByteArray? = withContext(AppDispatchers.thumbnail) {
        coroutineContext.ensureActive()

        val found = findCovers(request)
        if (found.isEmpty()) return@withContext null

        val ordered = CoverRules.rankItems(
            items = found,
            candidateOf = { it.candidate },
            maxProbes = CoverRules.MAX_PROBES,
        ) { item -> measure(item.node, item.candidate) }

        // Try the best few: an exotic RAW or a truncated file must not cost the folder its cover.
        ordered.take(MAX_DECODE_ATTEMPTS).forEach { item ->
            coroutineContext.ensureActive()
            imageExtractor.extract(request.copy(node = item.node, folderCover = false))
                ?.let { return@withContext it }
        }
        null
    }

    /**
     * Breadth-first, depth-limited search for cover-named images.
     *
     * The folder itself always wins: a level is searched only while every level above it came up
     * empty, so `Film/poster.jpg` is used even though `Part 1`…`Part 3` exist, and the presence of
     * sub-folders never blocks the discovery of the folder's own cover. Within one level the name
     * rule decides between siblings (`Season 2/poster.jpg` beats `Season 1/folder.jpg`), and an
     * exact `poster.*` ends the level immediately — no need to open the remaining folders.
     */
    private suspend fun findCovers(request: ThumbRequest): List<Found> {
        val root = scan(request.node, request.coverScanLimit, folderLimit = MAX_SUBFOLDERS)
        var found = coversOf(root.images, depth = 0)
        var frontier = root.subFolders
        var depth = 1
        var visits = 1

        while (found.isEmpty() && frontier.isNotEmpty() && depth <= MAX_DEPTH && visits < MAX_VISITS) {
            val next = ArrayList<DocNode>()
            for (folder in frontier) {
                coroutineContext.ensureActive()
                if (visits >= MAX_VISITS) break
                visits++
                val deeper = depth < MAX_DEPTH
                val level = scan(
                    node = folder,
                    imageLimit = request.coverScanLimit,
                    folderLimit = if (deeper) MAX_SUBFOLDERS else 0,
                )
                found += coversOf(level.images, depth)
                // An exact `poster.*` is the best a name can be: stop opening folders.
                if (found.any { it.candidate.tier == CoverNames.POSTER }) break
                if (deeper) next += level.subFolders
            }
            if (found.isNotEmpty()) break
            frontier = next.sortedBy { it.name }
            depth++
        }
        return found
    }

    /** One listing pass. Movie names are not collected any more: the cover rule is name-based, so
     *  nothing needs to know which videos live next to the image. */
    private suspend fun scan(node: DocNode, imageLimit: Int, folderLimit: Int): FolderScan =
        runCatching { docRepository.coverScan(node, imageLimit, 0, folderLimit) }
            .getOrDefault(FolderScan.EMPTY)

    /** Keeps only the images whose *name* makes them a cover — everything else is dropped here, so
     *  it is never probed, never decoded and never cached. */
    private fun coversOf(images: List<DocNode>, depth: Int): List<Found> = images.mapNotNull { node ->
        val candidate = CoverRules.Candidate(
            name = node.name,
            sizeBytes = node.size,
            hidden = node.isHidden,
            depth = depth,
        )
        if (CoverRules.isUsable(candidate)) Found(node, candidate) else null
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

    companion object {
        /**
         * How deep the search may go: the folder, its sub-folders, and one level below those.
         * Public because a cover can be inherited from that deep, so cache invalidation has to
         * climb just as far (see `ThumbnailRepository.invalidateParentCover`).
         */
        const val MAX_DEPTH = 2

        /** Sub-folders inspected per level, in name order (`Season 1` before `Season 2`). */
        const val MAX_SUBFOLDERS = 8

        /** Total folder listings spent on one cover, whatever the depth. Keeps a slow USB stick usable. */
        const val MAX_VISITS = 12

        /** How many ranked candidates may be decoded before giving up on the folder. */
        const val MAX_DECODE_ATTEMPTS = 3
    }
}

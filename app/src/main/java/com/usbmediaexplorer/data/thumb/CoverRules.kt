package com.usbmediaexplorer.data.thumb

import com.usbmediaexplorer.util.CoverNames

/**
 * Folder-cover decision logic.
 *
 * This file is deliberately pure Kotlin — no Android, no I/O, no coroutine — so the whole priority
 * system is covered by JVM unit tests (`CoverRulesTest`). [FolderCoverExtractor] walks the folder
 * (and, when needed, a bounded number of sub-folders), does the cheap header probes, and asks
 * [rankItems] which image should become the folder's cover.
 *
 * The rule is a *name* rule (see [CoverNames]):
 *
 * ```
 * poster  >  folder  >  cover          (case-insensitive, any image extension)
 * ```
 *
 *  - an image is a cover **only** if its base name is one of those three; `movie.jpg`,
 *    `image.jpg`, `screenshot.jpg`, `photo.jpg` or any other picture inside the folder is ignored,
 *  - the folder itself always wins over its sub-folders, and sub-folders are searched only when
 *    the folder holds no cover — that is what supports a multi-part movie (`Film/Part 1`,
 *    `Film/Part 2`…) and a series (`Series/Season 1/…`) without ever requiring a video file in the
 *    folder that owns the cover,
 *  - when several files share the same cover name and differ only by extension, the best available
 *    format and the most detailed one win — decided from header-only probes, never by loading the
 *    images,
 *  - when nothing matches, the result is empty and the UI draws the ordinary folder icon.
 */
object CoverRules {

    /**
     * One image found while searching for a cover. [width]/[height] stay 0 until a header probe
     * succeeds, which keeps [rank] usable in tests with pre-measured candidates.
     */
    data class Candidate(
        val name: String,
        val sizeBytes: Long,
        val width: Int = 0,
        val height: Int = 0,
        val hidden: Boolean = false,
        /** 0 = the folder itself, 1 = a direct sub-folder, 2 = a sub-folder of a sub-folder. */
        val depth: Int = 0,
    ) {
        val extension: String get() = CoverNames.extensionOf(name)

        val baseName: String get() = CoverNames.baseNameOf(name)

        /** `poster` = 0, `folder` = 2, `cover` = 4 (odd values are prefixed variants); -1 = no match. */
        val tier: Int get() = CoverNames.tier(name)

        val isCoverName: Boolean get() = tier != CoverNames.NO_MATCH
    }

    /** Default header-probe budget: enough to separate same-name files, small enough to stay fast. */
    const val MAX_PROBES = 3

    /** Below this edge length an image is too small to be artwork (used only to break ties). */
    const val MIN_EDGE = 160

    /**
     * Orders cover-named images best-first and measures only the few that can win.
     *
     * [probe] must return the same candidate with [Candidate.width]/[Candidate.height] filled in (a
     * header-only read). It is called at most [maxProbes] times and **only** for images that are
     * already covers by name, so a library of thousands of movie folders never loads their posters
     * just to choose one — and never even reads the header of an unrelated picture.
     *
     * The returned list may be tried in order by the caller, so a cover that cannot be decoded (an
     * exotic RAW, a corrupt file) simply falls through to the next one. An empty list means "this
     * folder has no cover": the UI then draws the folder icon.
     */
    fun rank(
        candidates: List<Candidate>,
        maxProbes: Int = MAX_PROBES,
        probe: (Candidate) -> Candidate = { it },
    ): List<Candidate> = rankItems(candidates, { it }, maxProbes) { probe(it) }

    /**
     * Same ordering for a list of arbitrary items (the extractor ranks `image + node` pairs, so the
     * winning candidate can be decoded without a second lookup by name).
     */
    fun <T> rankItems(
        items: List<T>,
        candidateOf: (T) -> Candidate,
        maxProbes: Int = MAX_PROBES,
        probe: (T) -> Candidate = { candidateOf(it) },
    ): List<T> {
        // Only images whose *name* makes them a cover take part. A hidden file or a literal
        // zero-byte stub is never artwork, whatever it is called.
        val usable = items.filter { isUsable(candidateOf(it)) }
        if (usable.isEmpty()) return emptyList()

        val base = usable.sortedWith(compareBy<T, Candidate>(ORDER) { candidateOf(it) })

        // The probe budget goes to the best few; everything else keeps its unknown dimensions,
        // which sort neutrally instead of being punished.
        val budget = maxProbes.coerceAtLeast(1)
        val measured = ArrayList<Pair<T, Candidate>>(base.size)
        base.forEachIndexed { index, item ->
            val candidate = candidateOf(item)
            val probed = if (index < budget && candidate.width <= 0 && candidate.height <= 0) {
                runCatching { probe(item) }.getOrDefault(candidate)
            } else {
                candidate
            }
            measured += item to probed
        }
        return measured
            .sortedWith(compareBy<Pair<T, Candidate>, Candidate>(ORDER) { it.second })
            .map { it.first }
    }

    /** A cover-named image that is not hidden and not an empty file. */
    fun isUsable(candidate: Candidate): Boolean =
        candidate.isCoverName && !candidate.hidden && candidate.sizeBytes != 0L

    /**
     * Full priority: nearest level first, then the name rule, then quality. Dimensions come from a
     * header probe; unknown dimensions stay neutral (0) so an unprobed file is never disqualified.
     */
    private val ORDER: Comparator<Candidate> = compareBy<Candidate> { it.depth }
        .thenBy { it.tier }
        .thenByDescending { detailScore(it.width, it.height) }
        .thenByDescending { CoverNames.formatRank(it.extension) }
        .thenByDescending { it.sizeBytes }
        .thenByDescending { geometryScore(it.width, it.height) }
        .thenBy { it.name }

    /** Resolution preference between files that share a cover name: more detail wins. */
    fun detailScore(width: Int, height: Int): Int {
        if (width <= 0 || height <= 0) return 0
        if (width < MIN_EDGE || height < MIN_EDGE) return -2
        val shortest = if (width < height) width else height
        return when {
            shortest >= 1400 -> 4
            shortest >= 900 -> 3
            shortest >= 500 -> 2
            shortest >= 200 -> 1
            else -> 0
        }
    }

    /**
     * Shape preference, computed from a header-only probe (`inJustDecodeBounds`), so no pixels are
     * ever decoded just to choose a cover. It only separates files of equal name, format and
     * resolution — a 2:3 poster is the shape artwork is published in.
     */
    fun geometryScore(width: Int, height: Int): Float {
        if (width <= 0 || height <= 0) return 0f
        if (width < MIN_EDGE || height < MIN_EDGE) return -120f

        val aspect = width.toFloat() / height
        val shape = when {
            aspect >= 0.55f && aspect <= 0.80f -> 70f // classic 2:3 poster
            aspect < 0.55f -> 30f // unusually tall
            aspect <= 0.95f -> 45f // portrait, close enough
            aspect <= 1.10f -> 15f // square
            aspect <= 1.80f -> -10f // landscape backdrop
            else -> -60f // screenshot / film strip
        }
        val shortest = if (width < height) width else height
        val detail = when {
            shortest >= 1000 -> 18f
            shortest >= 500 -> 10f
            shortest >= 300 -> 0f
            else -> -25f
        }
        return shape + detail
    }
}

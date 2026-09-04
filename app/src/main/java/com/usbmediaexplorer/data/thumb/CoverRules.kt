package com.usbmediaexplorer.data.thumb

/**
 * Folder-cover decision logic.
 *
 * This file is deliberately pure Kotlin — no Android, no I/O, no coroutine — so the whole
 * priority system is covered by JVM unit tests (`CoverRulesTest`). [FolderCoverExtractor] does the
 * directory listing and the cheap header probes, then asks [rank] which image should become the
 * folder's cover.
 *
 * Design rules taken from the requirements:
 *  - no mandatory folder name and no mandatory file name: names are only *hints*,
 *  - an image that shares its base name with the movie file or with the folder is the strongest
 *    automatic signal there is (`Movie.2010.1080p.mkv` + `Movie.2010.1080p.jpg`),
 *  - geometry (a portrait ~2:3 poster) breaks ties and demotes screenshots/backdrops,
 *  - a folder with no usable image simply yields no cover — never an error.
 */
object CoverRules {

    /**
     * One image found inside the folder. [width]/[height] stay 0 until a header probe succeeds,
     * which keeps [rank] usable in tests with pre-measured candidates.
     */
    data class Candidate(
        val name: String,
        val sizeBytes: Long,
        val width: Int = 0,
        val height: Int = 0,
        val hidden: Boolean = false,
    ) {
        val extension: String
            get() {
                val dot = name.lastIndexOf('.')
                return if (dot <= 0 || dot == name.lastIndex) "" else name.substring(dot + 1).lowercase()
            }

        val baseName: String
            get() = if (extension.isEmpty()) name else name.removeSuffix(".$extension")
    }

    /** Below this an image is a stub/icon, not artwork. */
    const val MIN_BYTES = 3_000L

    /** Below this edge length an image is too small to be a poster. */
    const val MIN_EDGE = 160

    /** A name score at or above this means "this file announces itself as artwork". */
    const val HINTED = 100f

    private val STRONG_HINTS = listOf("poster", "cover", "front", "folder", "movie", "film")
    private val MEDIUM_HINTS = listOf("thumb", "banner", "backdrop", "fanart", "background", "disc", "back")
    private val NEGATIVE_HINTS = listOf(
        "screenshot", "screen", "snap", "sample", "logo", "icon", "subtitle", "proof",
        "wallpaper", "avatar", "actor", "cast", "sub",
    )
    private val DISQUALIFIED = -1_000f

    /**
     * Name-based priority. Higher is better; [DISQUALIFIED] means "never use this".
     *
     * Matching is done on a folded form of the name (lowercase, letters and digits only) so that
     * `Movie 2010 1080p.jpg`, `movie.2010.1080p.jpg` and `Movie_2010_1080p.jpg` all compare equal
     * to `Movie.2010.1080p.mkv` without any hard-coded naming scheme.
     */
    fun nameScore(folderName: String, candidate: Candidate, videoBaseNames: List<String>): Float {
        if (candidate.hidden) return DISQUALIFIED
        // A known-tiny file is a stub or an icon. An unknown size (some providers report -1)
        // must stay eligible instead of costing the folder its cover.
        if (candidate.sizeBytes > 0 && candidate.sizeBytes < MIN_BYTES) return DISQUALIFIED

        val base = fold(candidate.baseName)
        var score = 0f

        // 1. The file announces itself as artwork.
        score += when {
            STRONG_HINTS.any { base.contains(it) } -> 100f
            MEDIUM_HINTS.any { base.contains(it) } -> 45f
            else -> 0f
        }

        // 2. Same base name as one of the movies in the folder — the strongest signal.
        val matchesVideo = videoBaseNames
            .map(::fold)
            .filter { it.length >= 4 }
            .any { it == base || it.contains(base) || base.contains(it) }
        if (matchesVideo && base.length >= 4) score += 120f

        // 3. Named after the folder itself.
        val folder = fold(folderName)
        if (folder.length >= 4 && base.length >= 4 &&
            (folder == base || folder.contains(base) || base.contains(folder))
        ) {
            score += 90f
        }

        // 4. Demote screenshots, samples, logos and subtitle-related images.
        if (NEGATIVE_HINTS.any { base.contains(it) }) score -= 80f

        // 5. Container preference: plain stills first, animated or icon formats last.
        score += when (candidate.extension) {
            "jpg", "jpeg", "png", "webp" -> 12f
            "heic", "heif", "avif" -> 8f
            "bmp", "tif", "tiff" -> 0f
            "gif" -> -35f
            "ico" -> -90f
            else -> -20f
        }
        return score
    }

    /**
     * Geometry priority, computed from a header-only probe (`inJustDecodeBounds`), so no pixels are
     * ever decoded just to choose a cover. Unknown dimensions stay neutral instead of punishing.
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

    /** Total priority of a measured candidate. */
    fun score(folderName: String, candidate: Candidate, videoBaseNames: List<String>): Float =
        nameScore(folderName, candidate, videoBaseNames) +
            geometryScore(candidate.width, candidate.height)

    /**
     * Orders the images of a folder by cover suitability and measures only the few that can win.
     *
     * [probe] must return the same candidate with [Candidate.width]/[Candidate.height] filled in
     * (a header-only read). It is called at most [maxProbes] times, which is what keeps a library
     * of thousands of movie folders fast: no full image is ever loaded to pick a cover.
     *
     * The returned list is best-first and may be tried in order by the caller, so a cover that
     * cannot be decoded (an exotic RAW, a corrupt file) simply falls through to the next one.
     * An empty list means "this folder has no cover" — the UI then draws the folder icon.
     */
    fun rank(
        folderName: String,
        candidates: List<Candidate>,
        videoBaseNames: List<String>,
        maxProbes: Int = 4,
        probe: (Candidate) -> Candidate = { it },
    ): List<Candidate> {
        val usable = candidates.filter {
            !it.hidden && (it.sizeBytes <= 0 || it.sizeBytes >= MIN_BYTES)
        }
        if (usable.isEmpty()) return emptyList()

        val scored = usable
            .map { it to nameScore(folderName, it, videoBaseNames) }
            .sortedWith(compareByDescending<Pair<Candidate, Float>> { it.second }.thenBy { it.first.name })

        // Files that announce themselves as artwork win the probe budget. If nothing does, fall
        // back to "the biggest files first": in a plain photo folder the largest image is far more
        // likely to be the intended artwork than a 4 KB thumbnail.
        val hinted = scored.filter { it.second >= HINTED }
        val pool = if (hinted.isNotEmpty()) {
            hinted
        } else {
            scored.sortedByDescending { it.first.sizeBytes }
        }

        val measured = ArrayList<Pair<Candidate, Float>>(pool.size)
        pool.take(maxProbes.coerceAtLeast(1)).forEach { (candidate, nameScore) ->
            val probed = if (candidate.width > 0 && candidate.height > 0) {
                candidate
            } else {
                runCatching { probe(candidate) }.getOrDefault(candidate)
            }
            measured += probed to (nameScore + geometryScore(probed.width, probed.height))
        }

        val ranked = measured.sortedByDescending { it.second }.map { it.first }
        // Anything never probed still deserves a chance as a last resort (cheap: no header read),
        // so a winner that turns out to be undecodable can fall through to the next image.
        val chosen = ranked.mapTo(HashSet()) { it.name }
        val rest = scored.map { it.first }.filter { it.name !in chosen }
        return ranked + rest
    }

    /**
     * Comparison form of a name: lowercased, letters and digits only. Separators (`.` `_` ` ` `-`
     * `(`…`) disappear, so `Movie.2010.1080p.jpg` matches `Movie 2010 1080p.mkv`, and non-Latin
     * names (Arabic included) keep their letters instead of being folded away.
     */
    private fun fold(value: String): String {
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            if (ch.isLetterOrDigit()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }
}

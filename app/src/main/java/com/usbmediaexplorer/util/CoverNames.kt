package com.usbmediaexplorer.util

/**
 * The name rule behind Folder Cover.
 *
 * A folder's cover is never "any image that happens to be inside it". Real movie and series
 * libraries announce their artwork by file name, and exactly three names are accepted, in this
 * strict priority:
 *
 * ```
 * poster  >  folder  >  cover
 * ```
 *
 * The rule is independent of the extension (`poster.jpg`, `poster.jpeg`, `poster.png`,
 * `poster.webp`… all match) and of letter case (`Poster` = `poster` = `POSTER`). Everything else —
 * `movie.jpg`, `image.jpg`, `screenshot.jpg`, `photo.jpg`, a frame dump, a camera photo — is not a
 * cover, and the UI draws the ordinary folder icon instead.
 *
 * Pure Kotlin (no Android, no I/O) so the rule is covered by JVM unit tests and can be reused by
 * both the thumbnail pipeline and the directory scanners, which need to recognise a cover name
 * while they list a folder.
 */
object CoverNames {

    /** The accepted cover names, best first. The index in this list is the priority. */
    val KEYWORDS: List<String> = listOf("poster", "folder", "cover")

    /** Returned by [tier] when a file name does not make it a cover. */
    const val NO_MATCH: Int = -1

    /** [tier] of an exact `poster.*` — the best possible cover. */
    const val POSTER: Int = 0

    /**
     * Words that turn a "starts with a keyword" name back into a non-cover: `poster-backdrop.jpg`
     * or `folder-icon.png` are artwork *about* the movie, not the movie's cover.
     */
    private val NEGATIVE = listOf(
        "backdrop", "background", "fanart", "banner", "screenshot", "screen", "snap", "sample",
        "logo", "icon", "thumb", "subtitle", "sub", "wallpaper", "avatar", "actor", "cast",
        "proof", "small", "tiny", "back",
    )

    /**
     * Priority of a file name: lower is better, [NO_MATCH] means "not a cover".
     *
     * The keyword always dominates, so any `poster*` beats any `folder*` and any `folder*` beats
     * any `cover*`. Within one keyword an exact match (`poster.jpg`) beats a variant
     * (`poster-ar.jpg`, `movie-poster.jpg`, `The.Matrix.poster.jpg`), and a variant is only
     * accepted when the keyword is a whole word of the name and the rest of the name is not one of
     * [NEGATIVE] — so `discover.jpg` is not a `cover`, and `poster-backdrop.jpg` is not a poster.
     */
    fun tier(fileName: String): Int {
        val base = baseNameOf(fileName)
        val folded = fold(base)
        if (folded.isEmpty()) return NO_MATCH

        KEYWORDS.forEachIndexed { index, keyword ->
            if (folded == keyword) return index * 2
        }

        val words = wordsOf(base)
        KEYWORDS.forEachIndexed { index, keyword ->
            val announced = words.any { it.startsWith(keyword) }
            if (announced && NEGATIVE.none { folded.contains(it) }) return index * 2 + 1
        }
        return NO_MATCH
    }

    /**
     * The words of a name, lowercased: `The.Matrix.poster` → `the`, `matrix`, `poster`. Anything
     * that is not a letter or a digit separates words, which is why `discover` is one word and can
     * never be mistaken for a `cover`.
     */
    private fun wordsOf(value: String): List<String> {
        val words = ArrayList<String>()
        val word = StringBuilder()
        value.forEach { ch ->
            if (ch.isLetterOrDigit()) {
                word.append(ch.lowercaseChar())
            } else if (word.isNotEmpty()) {
                words.add(word.toString())
                word.setLength(0)
            }
        }
        if (word.isNotEmpty()) words.add(word.toString())
        return words
    }

    /** True when the file name alone makes this image a folder cover. */
    fun isCoverName(fileName: String): Boolean = tier(fileName) != NO_MATCH

    /** `Poster.JPG` → `poster`; the extension never matters, the case never matters. */
    fun baseNameOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0 || dot == fileName.lastIndex) fileName else fileName.substring(0, dot)
    }

    /** `Poster.JPG` → `jpg`; `poster` (no dot) → `""`. */
    fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        return if (dot <= 0 || dot == fileName.lastIndex) "" else fileName.substring(dot + 1).lowercase()
    }

    /**
     * Format preference used when several files carry the *same* cover name and only the extension
     * differs (`poster.png` next to `poster.jpg`): lossless and modern stills first, animated or
     * icon formats last, unknown containers (RAW…) still eligible but tried last.
     */
    fun formatRank(extension: String): Int = when (extension.lowercase()) {
        "png" -> 60
        "webp" -> 55
        "jpg", "jpeg" -> 50
        "avif" -> 45
        "heic", "heif" -> 40
        "tif", "tiff" -> 20
        "bmp" -> 15
        "gif" -> 5
        "ico" -> 0
        else -> -10
    }

    /**
     * Comparison form of a name: lowercased, letters and digits only. Separators (`.`, `_`, ` `,
     * `-`, `(`…`) disappear, so `poster.jpg`, `Poster.JPG` and `POSTER.jpeg` all compare equal,
     * while non-Latin names (Arabic included) keep their letters instead of being folded away.
     */
    fun fold(value: String): String {
        val out = StringBuilder(value.length)
        value.forEach { ch ->
            if (ch.isLetterOrDigit()) out.append(ch.lowercaseChar())
        }
        return out.toString()
    }
}

package com.usbmediaexplorer.data.doc

/**
 * Pure ancestry check between two document URIs (audit item 3: never copy/move/zip a folder
 * into itself or into one of its own descendants — that recursion fills the drive).
 *
 * Works on the *string* form of the URIs so it is unit-testable without Android:
 *  - `file://` URIs and raw paths compare normalized, case-insensitive paths (USB file systems
 *    are case-insensitive, and erring toward "blocked" is the data-safe direction),
 *  - SAF document/tree URIs compare authority plus the decoded document id, where `primary:rel`
 *    maps onto `/storage/emulated/0/rel` and `<UUID>:rel` onto `/storage/<UUID>/rel`, so the
 *    same physical folder reached through both backends is still recognized as the same,
 *  - unknown or cross-bucket pairs answer `false` (the operation is allowed): a false block
 *    would break legitimate cross-volume copies, while the realistic self-copy cases all share
 *    one of the buckets above.
 */
object DocRelation {

    /** True when [candidateUri] is the same location as, or lives inside, [ancestorUri]. */
    fun isSameOrDescendant(candidateUri: String, ancestorUri: String): Boolean {
        if (candidateUri.isEmpty() || ancestorUri.isEmpty()) return false
        if (candidateUri == ancestorUri) return true
        val candidate = normalize(candidateUri) ?: return false
        val ancestor = normalize(ancestorUri) ?: return false
        if (candidate.first != ancestor.first) return false
        val a = ancestor.second
        val c = candidate.second
        if (a.isEmpty() || c.isEmpty()) return false
        return c == a || c.startsWith(if (a.endsWith("/")) a else "$a/")
    }

    /** Returns (bucket, normalized path) or null when the URI cannot be interpreted. */
    private fun normalize(uri: String): Pair<String, String>? = when {
        uri.startsWith("file://") -> "file" to normalizePath(percentDecode(uri.removePrefix("file://")))
        uri.startsWith("/") -> "file" to normalizePath(uri)
        uri.startsWith("content://") -> normalizeContent(uri)
        else -> null
    }

    private fun normalizeContent(uri: String): Pair<String, String>? {
        val rest = uri.removePrefix("content://")
        val authority = rest.substringBefore('/', "").lowercase()
        val path = rest.substringAfter('/', "")
        if (authority.isEmpty() || path.isEmpty()) return null
        // /tree/<treeId>/document/<docId> | /document/<docId> | /tree/<treeId>
        val segments = path.split('/').filter { it.isNotEmpty() }
        val docId: String = when {
            segments.size >= 4 && segments[0] == "tree" && segments[2] == "document" ->
                percentDecode(segments.drop(3).joinToString("/"))

            segments.size >= 2 && segments[0] == "document" ->
                percentDecode(segments.drop(1).joinToString("/"))

            segments.size >= 2 && segments[0] == "tree" ->
                percentDecode(segments.drop(1).joinToString("/"))

            else -> return "saf:$authority" to percentDecode(path).lowercase()
        }
        // Known volume prefixes map onto the classic mount points, which makes SAF and file://
        // views of the same folder compare equal.
        val volume = docId.substringBefore(':', "")
        val relative = docId.substringAfter(':', "")
        val mappedRoot = when {
            volume.isEmpty() -> null
            volume.equals("primary", ignoreCase = true) -> "/storage/emulated/0"
            else -> "/storage/$volume"
        }
        return if (mappedRoot != null) {
            "file" to normalizePath("$mappedRoot/$relative")
        } else {
            "saf:$authority" to ("/" + docId).lowercase()
        }
    }

    /** Collapses `.`/`..`/duplicate slashes; compares case-insensitively (see KDoc). */
    private fun normalizePath(path: String): String {
        val out = ArrayList<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out += segment
            }
        }
        return ("/" + out.joinToString("/")).lowercase()
    }

    /**
     * Minimal %XX decoder. Multi-byte UTF-8 sequences decode byte-wise into chars; the result is
     * not pretty but it is deterministic on both sides of a comparison, which is all the ancestry
     * check needs. `+` is left alone on purpose: it is a legal path character, not a space here.
     */
    private fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val builder = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    builder.append(hex.toChar())
                    i += 3
                    continue
                }
            }
            builder.append(c)
            i++
        }
        return builder.toString()
    }
}

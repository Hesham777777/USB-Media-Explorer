package com.usbmediaexplorer.data.doc

import com.usbmediaexplorer.data.metadata.MediaMetadata
import com.usbmediaexplorer.data.settings.SortMode
import java.text.Collator
import java.util.Locale

/**
 * Sorting for the browse grid (spec §13).
 *
 * Duration sorting needs metadata that is loaded lazily, so the caller passes a lookup function
 * and items without metadata yet sort last — the list re-orders itself once the queue fills in.
 */
object DocSorter {

    fun sort(
        nodes: List<DocNode>,
        mode: SortMode,
        foldersFirst: Boolean,
        metadata: (DocNode) -> MediaMetadata? = { null },
    ): List<DocNode> {
        val comparator: Comparator<DocNode> = when (mode) {
            SortMode.NAME_ASC -> compareByNaturalName(ascending = true)
            SortMode.NAME_DESC -> compareByNaturalName(ascending = false)
            SortMode.NEWEST -> compareByDescending { it.lastModified }
            SortMode.OLDEST -> compareBy { it.lastModified }
            SortMode.LARGEST -> compareByDescending { it.size.coerceAtLeast(0) }
            SortMode.SMALLEST -> compareBy { it.size.coerceAtLeast(0) }
            SortMode.TYPE -> compareBy<DocNode> { it.kind.ordinal }
                .thenByNaturalName(ascending = true)
            SortMode.DURATION -> compareByDescending<DocNode> { metadata(it)?.durationMs ?: -1L }
                .thenByNaturalName(ascending = true)
        }
        val withFolders = if (foldersFirst) {
            compareByDescending<DocNode> { it.isDirectory }.then(comparator)
        } else {
            comparator
        }
        return nodes.sortedWith(withFolders)
    }

    /**
     * Natural (human) ordering: "Movie 2" before "Movie 10". Episode numbers on a USB stick are
     * the whole reason a plain string compare is not good enough.
     */
    fun compareByNaturalName(ascending: Boolean): Comparator<DocNode> =
        Comparator { a, b -> naturalCompare(a.name, b.name).let { if (ascending) it else -it } }

    private fun Comparator<DocNode>.thenByNaturalName(ascending: Boolean): Comparator<DocNode> =
        then(
            Comparator { a, b ->
                naturalCompare(a.name, b.name).let { if (ascending) it else -it }
            },
        )

    private val collator: Collator = Collator.getInstance(Locale.getDefault()).apply {
        strength = Collator.SECONDARY
    }

    fun naturalCompare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var endA = i
                while (endA < a.length && a[endA].isDigit()) endA++
                var endB = j
                while (endB < b.length && b[endB].isDigit()) endB++
                val numA = a.substring(i, endA).trimStart('0').ifEmpty { "0" }
                val numB = b.substring(j, endB).trimStart('0').ifEmpty { "0" }
                val cmp = when {
                    numA.length != numB.length -> numA.length - numB.length
                    else -> numA.compareTo(numB)
                }
                if (cmp != 0) return cmp
                i = endA
                j = endB
            } else {
                val cmp = collator.compare(ca.toString(), cb.toString())
                if (cmp != 0) return cmp
                i++
                j++
            }
        }
        return (a.length - i) - (b.length - j)
    }
}

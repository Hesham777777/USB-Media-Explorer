package com.usbmediaexplorer

import com.usbmediaexplorer.data.doc.DocSorter
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.ops.BulkRenamePlanner
import com.usbmediaexplorer.data.ops.BulkRenameRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BulkRenamePlannerTest {

    private val movieNames = listOf(
        "Movie 01" to "mkv",
        "Movie 02" to "mkv",
        "Movie 03" to "mkv",
    )

    @Test
    fun `replaces text while keeping the extension`() {
        val rules = BulkRenameRules(find = "Movie", replace = "My Movies")
        val result = BulkRenamePlanner.planParts(movieNames, rules)
        assertEquals(
            listOf("My Movies 01.mkv", "My Movies 02.mkv", "My Movies 03.mkv"),
            result,
        )
    }

    @Test
    fun `adds a prefix and a suffix`() {
        val rules = BulkRenameRules(prefix = "USB ", suffix = " [1080p]")
        val result = BulkRenamePlanner.planParts(movieNames, rules)
        assertEquals("USB Movie 01 [1080p].mkv", result.first())
    }

    @Test
    fun `automatic numbering pads and continues across the list`() {
        val names = listOf("Episode" to "mkv", "Episode" to "mkv", "Episode" to "mkv")
        val rules = BulkRenameRules(numbering = true, startAt = 7, padding = 3)
        val result = BulkRenamePlanner.planParts(names, rules)
        assertEquals("007 Episode.mkv", result[0])
        assertEquals("008 Episode.mkv", result[1])
        assertEquals("009 Episode.mkv", result[2])
    }

    @Test
    fun `numbering can be appended after the name`() {
        val rules = BulkRenameRules(
            numbering = true,
            startAt = 1,
            padding = 2,
            numberingPosition = BulkRenameRules.NumberingPosition.AFTER_NAME,
        )
        val result = BulkRenamePlanner.planParts(listOf("Ballerina" to "mp4"), rules)
        assertEquals("Ballerina 01.mp4", result.single())
    }

    @Test
    fun `path separators are neutralised so SAF creates succeed`() {
        val rules = BulkRenameRules(prefix = "A/B:")
        val result = BulkRenamePlanner.planParts(listOf("safe" to "mp4"), rules)
        assertFalse(result.single().contains('/'))
        assertFalse(result.single().contains(':'))
    }

    @Test
    fun `names never collapse to an empty string`() {
        val rules = BulkRenameRules(find = "everything", replace = "")
        val result = BulkRenamePlanner.planParts(listOf("everything" to ""), rules)
        assertEquals("everything", result.single())
    }
}

class DocSorterTest {

    @Test
    fun `natural ordering puts episode 2 before episode 10`() {
        assertTrue(DocSorter.naturalCompare("Movie 2.mkv", "Movie 10.mkv") < 0)
        assertTrue(DocSorter.naturalCompare("S01E09.mkv", "S01E10.mkv") < 0)
    }

    @Test
    fun `natural ordering is case and locale tolerant`() {
        assertEquals(0, DocSorter.naturalCompare("abc", "abc"))
        assertTrue(DocSorter.naturalCompare("apple", "Banana") < 0)
    }

    @Test
    fun `equal numbers with different padding still compare by value`() {
        assertEquals(0, DocSorter.naturalCompare("01", "1"))
    }
}

class MediaKindTest {

    @Test
    fun `video containers are recognised by extension`() {
        listOf("mkv", "mp4", "avi", "mov", "webm", "ts", "m2ts", "rmvb").forEach { ext ->
            assertEquals("extension $ext", MediaKind.VIDEO, MediaKind.ofExtension(ext))
        }
    }

    @Test
    fun `images include modern phone formats`() {
        listOf("heic", "heif", "avif", "webp", "dng").forEach { ext ->
            assertEquals("extension $ext", MediaKind.IMAGE, MediaKind.ofExtension(ext))
        }
    }

    @Test
    fun `mime type wins over an unknown extension`() {
        assertEquals(
            MediaKind.VIDEO,
            MediaKind.of("", "video/x-matroska", isDirectory = false),
        )
        assertEquals(
            MediaKind.DIRECTORY,
            MediaKind.of("mkv", "video/mp4", isDirectory = true),
        )
    }

    @Test
    fun `subtitle mime types are mapped for Media3`() {
        assertEquals("application/x-subrip", MediaKind.subtitleMime("srt"))
        assertEquals("text/vtt", MediaKind.subtitleMime("VTT"))
        assertEquals("text/x-ssa", MediaKind.subtitleMime("ass"))
        assertEquals(null, MediaKind.subtitleMime("mp4"))
    }
}

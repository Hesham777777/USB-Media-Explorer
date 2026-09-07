package com.usbmediaexplorer.data.doc

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The copy-into-self guard (audit item 3) is pure string logic, so every realistic URI shape is
 * pinned here: file paths, SAF tree/document URIs, and the cross-backend case where the same
 * physical folder is reachable through both.
 */
class DocRelationTest {

    @Test
    fun `file paths - same, child and grandchild are descendants`() {
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/A", "file:///storage/A"))
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/A/B", "file:///storage/A"))
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/A/B/C", "file:///storage/A"))
        assertTrue(DocRelation.isSameOrDescendant("/storage/A/B", "/storage/A"))
    }

    @Test
    fun `file paths - sibling, prefix-name and parent are not descendants`() {
        assertFalse(DocRelation.isSameOrDescendant("file:///storage/B", "file:///storage/A"))
        // "AB" starts with "A" but is not inside it.
        assertFalse(DocRelation.isSameOrDescendant("file:///storage/AB", "file:///storage/A"))
        assertFalse(DocRelation.isSameOrDescendant("file:///storage", "file:///storage/A"))
    }

    @Test
    fun `file paths - trailing slashes and dot segments normalize`() {
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/A/", "file:///storage/A"))
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/A/./B", "file:///storage/A"))
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/B", "file:///storage/A/../B"))
    }

    @Test
    fun `file paths - comparison is case-insensitive (FAT safety bias)`() {
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/AAA/b", "file:///storage/aaa"))
    }

    @Test
    fun `saf - document inside a tree of the same authority`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        val doc = "content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2Fx"
        assertTrue(DocRelation.isSameOrDescendant(doc, tree))
        assertFalse(DocRelation.isSameOrDescendant(tree, doc))
    }

    @Test
    fun `saf primary maps onto the emulated storage path (cross-backend)`() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
        assertTrue(
            DocRelation.isSameOrDescendant("file:///storage/emulated/0/Download/Movies", tree),
        )
        assertTrue(
            DocRelation.isSameOrDescendant(tree, "file:///storage/emulated/0/Download"),
        )
    }

    @Test
    fun `saf uuid volume maps onto its mount point (cross-backend)`() {
        val tree = "content://com.android.externalstorage.documents/tree/1A2B-3C4D%3AMovies"
        assertTrue(DocRelation.isSameOrDescendant("file:///storage/1A2B-3C4D/Movies/2024", tree))
        assertFalse(DocRelation.isSameOrDescendant("file:///storage/1A2B-3C4D/Music", tree))
    }

    @Test
    fun `different buckets are never descendants`() {
        // A file:// path cannot be "inside" a SAF tree on another authority.
        assertFalse(
            DocRelation.isSameOrDescendant(
                "content://com.other.provider/tree/xyz/document/xyz%2Ff",
                "file:///storage/A",
            ),
        )
        assertFalse(DocRelation.isSameOrDescendant("file:///storage/A", "https://x/y"))
    }

    @Test
    fun `unparsable input answers false instead of throwing`() {
        assertFalse(DocRelation.isSameOrDescendant("", ""))
        assertFalse(DocRelation.isSameOrDescendant("junk", "file:///storage/A"))
    }
}

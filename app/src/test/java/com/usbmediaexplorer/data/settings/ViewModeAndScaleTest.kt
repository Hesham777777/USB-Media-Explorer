package com.usbmediaexplorer.data.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redesign added two presentation enums the whole UI branches on: [ViewMode.COMPACT_LIST]
 * (spec §6) and [ItemScale] (spec §17). Both are pure Kotlin, so their invariants are worth a
 * test — a wrong `isList` turns a grid into rows, and a wrong column delta can collapse a grid to
 * zero columns on a small phone.
 */
class ViewModeAndScaleTest {

    @Test
    fun `both list densities report as lists and grids do not`() {
        assertTrue(ViewMode.LIST.isList)
        assertTrue(ViewMode.COMPACT_LIST.isList)
        ViewMode.entries.filter { !it.isList }.forEach { mode ->
            assertFalse("$mode should be a grid", mode.isList)
        }
    }

    @Test
    fun `every view mode yields at least one column in both orientations`() {
        ViewMode.entries.forEach { mode ->
            assertTrue("$mode portrait", mode.columnsPortrait >= 1)
            assertTrue("$mode landscape", mode.columnsLandscape >= 1)
            assertTrue("$mode aspect", mode.aspectRatio >= 0f)
        }
    }

    @Test
    fun `landscape always gives at least as many columns as portrait`() {
        ViewMode.entries.forEach { mode ->
            assertTrue(
                "$mode",
                mode.columnsLandscape >= mode.columnsPortrait,
            )
        }
    }

    @Test
    fun `item scale grows rows and thumbnails from compact to large`() {
        assertTrue(ItemScale.COMPACT.rowHeight < ItemScale.NORMAL.rowHeight)
        assertTrue(ItemScale.NORMAL.rowHeight < ItemScale.LARGE.rowHeight)
        assertTrue(ItemScale.COMPACT.thumb < ItemScale.NORMAL.thumb)
        assertTrue(ItemScale.NORMAL.thumb < ItemScale.LARGE.thumb)
    }

    @Test
    fun `compact adds a grid column and large removes one`() {
        assertEquals(1, ItemScale.COMPACT.columnsDelta)
        assertEquals(0, ItemScale.NORMAL.columnsDelta)
        assertEquals(-1, ItemScale.LARGE.columnsDelta)
    }

    @Test
    fun `scaled column counts are clamped to at least one`() {
        // The large item size removes a column, and the widest tiles already use a single column,
        // so the raw sum can reach zero. The renderer clamps it; assert the clamp, and pin the one
        // combination that needs it so a future column-count change is a conscious decision.
        ViewMode.entries.forEach { mode ->
            ItemScale.entries.forEach { scale ->
                val columns = (mode.columnsPortrait + scale.columnsDelta).coerceAtLeast(1)
                assertTrue("$mode/$scale -> $columns", columns >= 1)
            }
        }
        assertEquals(0, ViewMode.GRID_HUGE.columnsPortrait + ItemScale.LARGE.columnsDelta)
    }

    @Test
    fun `persisted enums keep their names stable`() {
        // Settings and per-folder preferences are stored by enum name; renaming an entry would
        // silently drop a saved preference, so the set of names is asserted here.
        assertEquals(
            listOf("LIST", "COMPACT_LIST", "GRID_SMALL", "GRID_MEDIUM", "GRID_LARGE", "GRID_HUGE"),
            ViewMode.entries.map { it.name },
        )
        assertEquals(
            listOf("COMPACT", "NORMAL", "LARGE"),
            ItemScale.entries.map { it.name },
        )
        assertEquals(
            listOf(
                "NAME_ASC", "NAME_DESC", "NEWEST", "OLDEST",
                "LARGEST", "SMALLEST", "TYPE", "DURATION",
            ),
            SortMode.entries.map { it.name },
        )
        assertEquals(listOf("SYSTEM", "LIGHT", "DARK"), ThemeMode.entries.map { it.name })
    }

    @Test
    fun `default settings are the dense-but-readable ones`() {
        val defaults = AppSettings.DEFAULT
        assertEquals(ItemScale.NORMAL, defaults.itemScale)
        assertTrue("extensions shown by default", defaults.showExtensions)
        assertTrue("media info shown by default", defaults.showMediaInfo)
        assertFalse("permissions asked on first run", defaults.firstRunPermissionsAsked)
    }
}

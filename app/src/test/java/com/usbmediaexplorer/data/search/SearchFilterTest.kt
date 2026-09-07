package com.usbmediaexplorer.data.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The redesign added three search filters (spec §10): folders, files and a "last 7 days" window.
 * The size and date thresholds live on the enum itself, so they are pure Kotlin and cheap to pin —
 * a wrong window silently hides files from a search the user believes is exhaustive.
 */
class SearchFilterTest {

    @Test
    fun `only the large filter imposes a size threshold`() {
        assertEquals(1L * 1024 * 1024 * 1024, SearchFilter.LARGE.minSizeBytes)
        SearchFilter.entries.filter { it != SearchFilter.LARGE }.forEach { filter ->
            assertEquals("$filter should not filter by size", 0L, filter.minSizeBytes)
        }
    }

    @Test
    fun `only the recent filter imposes a date window`() {
        SearchFilter.entries.filter { it != SearchFilter.RECENT }.forEach { filter ->
            assertEquals("$filter should not filter by date", 0L, filter.modifiedAfterMillis)
        }
    }

    @Test
    fun `the recent window is seven days back from now`() {
        val sevenDays = 7L * 24 * 60 * 60 * 1000
        val before = System.currentTimeMillis() - sevenDays
        val threshold = SearchFilter.RECENT.modifiedAfterMillis
        val after = System.currentTimeMillis() - sevenDays
        assertTrue("threshold $threshold not in [$before, $after]", threshold in before..after)
    }

    @Test
    fun `filter names stay stable for the chips`() {
        assertEquals(
            listOf(
                "ALL", "VIDEOS", "MOVIES", "SERIES", "PHOTOS",
                "MUSIC", "LARGE", "FOLDERS", "FILES", "RECENT",
            ),
            SearchFilter.entries.map { it.name },
        )
    }
}

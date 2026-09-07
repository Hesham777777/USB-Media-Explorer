package com.usbmediaexplorer

import com.usbmediaexplorer.util.Formatters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormattersTest {

    @Test
    fun `sizes use binary units and two decimals below 10`() {
        assertEquals("0 B", Formatters.size(0, Locale.US))
        assertEquals("512 B", Formatters.size(512, Locale.US))
        assertEquals("1 KB", Formatters.size(1024, Locale.US))
        assertEquals("2 KB", Formatters.size(1536, Locale.US))
        assertEquals("1.00 GB", Formatters.size(1024L * 1024 * 1024, Locale.US))
        assertEquals("2.41 GB", Formatters.size(2_587_918_336L, Locale.US))
    }

    @Test
    fun `durations render like a media player`() {
        assertEquals("0:00", Formatters.duration(0))
        assertEquals("0:45", Formatters.duration(45_000))
        assertEquals("4:18", Formatters.duration(258_000))
        assertEquals("2:04:18", Formatters.duration(((2 * 3600) + (4 * 60) + 18) * 1000L))
    }

    @Test
    fun `durations pad every field that needs it`() {
        // The formatter is hand rolled for speed, so the padding boundaries are pinned here:
        // a missing zero reads as a different length ("4:18" vs "4:8").
        assertEquals("0:05", Formatters.duration(5_000))
        assertEquals("0:59", Formatters.duration(59_000))
        assertEquals("1:00", Formatters.duration(60_000))
        assertEquals("10:00", Formatters.duration(600_000))
        assertEquals("59:59", Formatters.duration(3_599_000))
        assertEquals("1:00:00", Formatters.duration(3_600_000))
        assertEquals("1:00:05", Formatters.duration(3_605_000))
        assertEquals("1:09:05", Formatters.duration(((3600) + (9 * 60) + 5) * 1000L))
        assertEquals("12:34:56", Formatters.duration(((12 * 3600) + (34 * 60) + 56) * 1000L))
    }

    @Test
    fun `sizes keep locale grouping and the decimal rule`() {
        // Grouping separators and the "two decimals below ten" rule must survive the switch from
        // String.format to a cached DecimalFormat.
        assertEquals("5.00 GB", Formatters.size(5L * 1024 * 1024 * 1024, Locale.US))
        assertEquals("15.0 GB", Formatters.size(15L * 1024 * 1024 * 1024, Locale.US))
        assertEquals("1,000 MB", Formatters.size(1000L * 1024 * 1024, Locale.US))
        assertEquals("900 MB", Formatters.size(900L * 1024 * 1024, Locale.US))
    }

    @Test
    fun `sizes stay locale aware without throwing`() {
        // The Arabic UI must not lose its translated units when formatters are cached per locale.
        assertTrue(Formatters.size(1024, Locale("ar")).endsWith("ك.ب"))
        assertTrue(Formatters.size(1024L * 1024 * 1024, Locale("ar")).endsWith("ج.ب"))
    }

    @Test
    fun `resolution labels match marketing names`() {
        assertEquals("1080p", Formatters.resolution(1920, 1080))
        assertEquals("720p", Formatters.resolution(1280, 720))
        assertEquals("4K", Formatters.resolution(3840, 2160))
        assertEquals("8K", Formatters.resolution(7680, 4320))
        // Portrait phone video: the short side still decides the label.
        assertEquals("1080p", Formatters.resolution(1080, 1920))
        assertEquals("", Formatters.resolution(0, 0))
    }

    @Test
    fun `frame rates keep common broadcast values exact`() {
        assertEquals("24 fps", Formatters.fps(24f))
        assertEquals("23.98 fps", Formatters.fps(23.976f))
        assertEquals("", Formatters.fps(0f))
    }

    @Test
    fun `eta stays readable and never negative`() {
        assertEquals("--", Formatters.eta(-1, Locale.US))
        assertTrue(Formatters.eta(90_000, Locale.US).endsWith("s"))
        assertTrue(Formatters.eta(6 * 60_000, Locale.US).contains("m"))
    }

    @Test
    fun `percent clamps to the 0-100 range`() {
        assertEquals(0, Formatters.percent(0, 0))
        assertEquals(50, Formatters.percent(5, 10))
        assertEquals(100, Formatters.percent(20, 10))
    }
}

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

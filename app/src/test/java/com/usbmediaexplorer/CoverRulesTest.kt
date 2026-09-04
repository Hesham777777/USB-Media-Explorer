package com.usbmediaexplorer

import com.usbmediaexplorer.data.thumb.CoverRules
import com.usbmediaexplorer.data.thumb.CoverRules.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder Cover priority system (pure logic — no Android, no I/O).
 *
 * These are the acceptance cases of the feature: one image, several images, no image at all,
 * posters that are only recognisable by geometry, names that match the movie or the folder
 * (including Arabic), and the probe budget that keeps a big library fast.
 */
class CoverRulesTest {

    private fun rank(
        folder: String,
        candidates: List<Candidate>,
        videos: List<String> = emptyList(),
        probes: Int = 4,
    ): List<Candidate> = CoverRules.rank(folder, candidates, videos, probes)

    // ---- basic acceptance cases -------------------------------------------

    @Test
    fun `a folder with a single image uses it`() {
        val only = Candidate("artwork.jpg", 240_000, 600, 900)
        val result = rank("Some Movie", listOf(only))
        assertEquals(1, result.size)
        assertEquals("artwork.jpg", result.first().name)
    }

    @Test
    fun `a folder with no usable image yields no cover instead of an error`() {
        assertTrue(rank("Documents", emptyList()).isEmpty())
        // Only stubs and hidden files inside: still no cover.
        val unusable = listOf(
            Candidate(".hidden.jpg", 90_000, 600, 900, hidden = true),
            Candidate("tiny.png", 800, 32, 32),
        )
        assertTrue(rank("Documents", unusable).isEmpty())
    }

    @Test
    fun `an image whose size is unknown is still eligible`() {
        val result = rank("movie", listOf(Candidate("poster.jpg", -1, 675, 1000)))
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `an announced poster wins over the other images of the folder`() {
        val result = rank(
            folder = "The.Matrix.1999",
            candidates = listOf(
                Candidate("screenshot001.png", 900_000, 1920, 1080),
                Candidate("poster.jpg", 320_000, 675, 1000),
                Candidate("IMG_2041.jpg", 2_400_000, 4000, 3000),
            ),
        )
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `the image named after the movie file is recognised without any fixed name`() {
        val result = rank(
            folder = "movies",
            candidates = listOf(
                Candidate("fanart-landscape.jpg", 700_000, 1920, 1080),
                Candidate("Dune.Part.Two.2024.1080p.jpg", 300_000, 800, 1200),
            ),
            videos = listOf("Dune.Part.Two.2024.1080p.BluRay.x264"),
        )
        assertEquals("Dune.Part.Two.2024.1080p.jpg", result.first().name)
    }

    @Test
    fun `the image named after the folder is preferred`() {
        val result = rank(
            folder = "Interstellar 2014",
            candidates = listOf(
                Candidate("random-photo.jpg", 500_000, 1600, 1200),
                Candidate("Interstellar 2014.jpg", 260_000, 700, 1000),
            ),
        )
        assertEquals("Interstellar 2014.jpg", result.first().name)
    }

    @Test
    fun `Arabic folder and file names match each other`() {
        val result = rank(
            folder = "فيلم الرسالة 1976",
            candidates = listOf(
                Candidate("صورة عشوائية.jpg", 480_000, 1600, 900),
                Candidate("فيلم الرسالة 1976.jpg", 250_000, 800, 1200),
            ),
        )
        assertEquals("فيلم الرسالة 1976.jpg", result.first().name)
    }

    // ---- geometry ----------------------------------------------------------

    @Test
    fun `with uninformative names a portrait poster beats a landscape screenshot`() {
        val result = rank(
            folder = "library",
            candidates = listOf(
                Candidate("a.jpg", 900_000, 1920, 1080), // landscape backdrop
                Candidate("b.jpg", 300_000, 640, 960), // 2:3 poster
            ),
        )
        assertEquals("b.jpg", result.first().name)
    }

    @Test
    fun `posters are never distorted by the score - a 2 to 3 image scores best`() {
        val poster = CoverRules.geometryScore(675, 1000)
        val square = CoverRules.geometryScore(1000, 1000)
        val wide = CoverRules.geometryScore(1920, 1080)
        val strip = CoverRules.geometryScore(4000, 800)
        val icon = CoverRules.geometryScore(48, 48)
        assertTrue(poster > square)
        assertTrue(square > wide)
        assertTrue(wide > strip)
        assertTrue(strip > icon)
    }

    @Test
    fun `unknown dimensions stay neutral instead of disqualifying the file`() {
        assertEquals(0f, CoverRules.geometryScore(0, 0), 0.0001f)
    }

    // ---- demotions ---------------------------------------------------------

    @Test
    fun `screenshots samples logos and animated or icon files are demoted`() {
        val base = Candidate("poster.jpg", 300_000, 675, 1000)
        val screenshot = Candidate("screenshot.jpg", 300_000, 675, 1000)
        val sample = Candidate("sample-cover.jpg", 300_000, 675, 1000)
        val gif = Candidate("poster.gif", 300_000, 675, 1000)
        val ico = Candidate("poster.ico", 300_000, 675, 1000)
        val videos = emptyList<String>()
        val scoreOf = { c: Candidate -> CoverRules.score("movie", c, videos) }
        assertTrue(scoreOf(base) > scoreOf(screenshot))
        assertTrue(scoreOf(base) > scoreOf(sample))
        assertTrue(scoreOf(base) > scoreOf(gif))
        assertTrue(scoreOf(base) > scoreOf(ico))
    }

    @Test
    fun `hidden files and stubs are never chosen even when named poster`() {
        val result = rank(
            folder = "movie",
            candidates = listOf(
                Candidate(".poster.jpg", 400_000, 675, 1000, hidden = true),
                Candidate("poster-tiny.jpg", 900, 675, 1000),
                Candidate("real-photo.jpg", 2_000_000, 1200, 1600),
            ),
        )
        assertEquals("real-photo.jpg", result.first().name)
    }

    // ---- performance contract ---------------------------------------------

    @Test
    fun `only the top candidates are probed - a big library never reads every header`() {
        val many = (1..40).map { Candidate("IMG_%04d.jpg".format(it), 500_000L + it, 0, 0) } +
            Candidate("poster.jpg", 300_000, 0, 0)
        var probes = 0
        val result = CoverRules.rank(
            folderName = "movie",
            candidates = many,
            videoBaseNames = emptyList(),
            maxProbes = 4,
        ) { candidate ->
            probes++
            candidate.copy(width = 675, height = 1000)
        }
        assertTrue("probed $probes times, budget is 4", probes <= 4)
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `unprobed candidates stay available so an undecodable winner can fall through`() {
        val result = rank(
            folder = "movie",
            candidates = listOf(
                Candidate("poster.jpg", 300_000, 675, 1000),
                Candidate("cover.png", 300_000, 675, 1000),
                Candidate("backup.jpg", 300_000, 675, 1000),
            ),
            probes = 1,
        )
        assertEquals(3, result.size)
        assertEquals(result.map { it.name }.toSet(), setOf("poster.jpg", "cover.png", "backup.jpg"))
    }

    @Test
    fun `no fixed folder name and no fixed file name is required`() {
        // Nothing here matches any keyword: the choice is made on geometry and size alone.
        val result = rank(
            folder = "xyz-9",
            candidates = listOf(
                Candidate("q1.jpg", 1_200_000, 800, 1200),
                Candidate("q2.jpg", 900_000, 1920, 1080),
            ),
        )
        assertEquals("q1.jpg", result.first().name)
    }
}

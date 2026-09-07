package com.usbmediaexplorer

import com.usbmediaexplorer.data.thumb.CoverRules
import com.usbmediaexplorer.data.thumb.CoverRules.Candidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Folder Cover selection (pure logic — no Android, no I/O).
 *
 * These are the acceptance cases of the feature. The rule is a **name** rule:
 *
 * ```
 * poster  >  folder  >  cover
 * ```
 *
 * so an image is a cover only when its base name is one of those three (any extension, any case),
 * the folder itself always beats its sub-folders, sub-folders are used when the folder holds
 * nothing — which is what supports a multi-part movie and a multi-season series — and a folder with
 * no cover-named image yields no cover at all, so the UI draws the ordinary folder icon.
 */
class CoverRulesTest {

    private fun rank(vararg candidates: Candidate): List<Candidate> =
        CoverRules.rank(candidates.toList())

    private fun chosen(vararg candidates: Candidate): String? = rank(*candidates).firstOrNull()?.name

    // ---- the three names, and their priority --------------------------------

    @Test
    fun `poster jpg is chosen as the folder cover`() {
        val result = rank(
            Candidate("screenshot001.png", 900_000, 1920, 1080),
            Candidate("poster.jpg", 320_000, 675, 1000),
            Candidate("IMG_2041.jpg", 2_400_000, 4000, 3000),
        )
        // Only the cover-named image survives: the others are not candidates at all.
        assertEquals(1, result.size)
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `folder jpg is chosen when there is no poster`() {
        val result = rank(
            Candidate("photo.jpg", 3_000_000, 4000, 3000),
            Candidate("folder.jpg", 240_000, 675, 1000),
            Candidate("movie.jpg", 500_000, 800, 1200),
        )
        assertEquals(1, result.size)
        assertEquals("folder.jpg", result.first().name)
    }

    @Test
    fun `cover jpg is chosen when there is neither poster nor folder`() {
        val result = rank(
            Candidate("image.jpg", 1_200_000, 1600, 1200),
            Candidate("cover.jpg", 260_000, 700, 1000),
        )
        assertEquals(1, result.size)
        assertEquals("cover.jpg", result.first().name)
    }

    @Test
    fun `poster has priority over folder`() {
        assertEquals("poster.jpg", chosen(Candidate("folder.jpg", 900_000, 1000, 1500), Candidate("poster.jpg", 120_000, 400, 600)))
    }

    @Test
    fun `folder has priority over cover`() {
        assertEquals("folder.jpg", chosen(Candidate("cover.png", 900_000, 1000, 1500), Candidate("folder.jpg", 120_000, 400, 600)))
    }

    @Test
    fun `the full priority chain holds in one folder`() {
        val result = rank(
            Candidate("cover.webp", 300_000, 675, 1000),
            Candidate("folder.png", 300_000, 675, 1000),
            Candidate("poster.jpg", 300_000, 675, 1000),
        )
        assertEquals(listOf("poster.jpg", "folder.png", "cover.webp"), result.map { it.name })
    }

    // ---- nothing else is ever a cover ---------------------------------------

    @Test
    fun `an image with any other name is never used as a cover`() {
        val result = rank(
            Candidate("movie.jpg", 800_000, 675, 1000),
            Candidate("image.jpg", 700_000, 675, 1000),
            Candidate("screenshot.jpg", 600_000, 675, 1000),
            Candidate("photo.jpg", 500_000, 675, 1000),
        )
        assertTrue("no cover name means no cover", result.isEmpty())
    }

    @Test
    fun `a folder full of pictures but without a cover name yields no cover`() {
        val many = (1..40).map { Candidate("IMG_%04d.jpg".format(it), 500_000L + it, 675, 1000) }
        assertTrue(rank(*many.toTypedArray()).isEmpty())
    }

    @Test
    fun `no known cover name anywhere means the folder icon is drawn`() {
        val result = rank(
            Candidate("movie.jpg", 800_000, 675, 1000, depth = 0),
            Candidate("photo.jpg", 800_000, 675, 1000, depth = 1),
            Candidate("screenshot.png", 800_000, 1920, 1080, depth = 2),
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `hidden files and empty files are not covers even when named poster`() {
        assertTrue(
            rank(
                Candidate(".poster.jpg", 400_000, 675, 1000, hidden = true),
                Candidate("poster.jpg", 0, 675, 1000),
            ).isEmpty(),
        )
    }

    @Test
    fun `an unknown size does not disqualify a cover name`() {
        assertEquals("poster.jpg", chosen(Candidate("poster.jpg", -1)))
    }

    // ---- case and extension --------------------------------------------------

    @Test
    fun `letter case never matters`() {
        assertEquals("POSTER.JPG", chosen(Candidate("folder.png", 300_000, 675, 1000), Candidate("POSTER.JPG", 300_000, 675, 1000)))
        assertTrue(CoverRules.isUsable(Candidate("Cover.PNG", 300_000)))
        assertTrue(CoverRules.isUsable(Candidate("FOLDER.WEBP", 300_000)))
    }

    @Test
    fun `the search never depends on one extension`() {
        listOf("jpg", "jpeg", "png", "webp", "heic", "avif", "bmp", "tiff", "gif").forEach { ext ->
            assertEquals(
                "poster.$ext must be usable",
                "poster.$ext",
                chosen(Candidate("poster.$ext", 300_000, 675, 1000)),
            )
        }
    }

    @Test
    fun `several extensions for the same cover name are all candidates`() {
        val result = rank(
            Candidate("poster.jpeg", 300_000, 675, 1000),
            Candidate("poster.webp", 300_000, 675, 1000),
            Candidate("poster.png", 300_000, 675, 1000),
        )
        assertEquals(3, result.size)
        // Same name, same dimensions: the best available format wins.
        assertEquals("poster.png", result.first().name)
    }

    @Test
    fun `for the same name the most detailed image wins over the format`() {
        val result = rank(
            Candidate("poster.png", 120_000, 300, 450),
            Candidate("poster.jpg", 900_000, 1200, 1800),
        )
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `exact cover names beat their variants`() {
        val result = rank(
            Candidate("poster-ar.jpg", 900_000, 1200, 1800),
            Candidate("poster.jpg", 120_000, 400, 600),
        )
        assertEquals("poster.jpg", result.first().name)
        assertEquals("poster-ar.jpg", result.last().name)
    }

    // ---- hierarchy: multi-part movies and multi-season series ----------------

    @Test
    fun `the cover of the folder itself wins over the covers of its sub-folders`() {
        val result = rank(
            Candidate("poster.jpg", 900_000, 1200, 1800, depth = 1),
            Candidate("cover.jpg", 120_000, 400, 600, depth = 0),
        )
        assertEquals("cover.jpg", result.first().name)
    }

    @Test
    fun `a multi-part movie uses the poster of the parent folder`() {
        // Film/poster.jpg next to Part 1, Part 2, Part 3 — the parent's own poster is used, and a
        // poster found deeper never replaces it.
        val result = rank(
            Candidate("poster.jpg", 200_000, 675, 1000, depth = 1),
            Candidate("poster.jpg", 320_000, 675, 1000, depth = 0),
        )
        assertEquals(0, result.first().depth)
    }

    @Test
    fun `a sub-folder cover is used when the folder itself has none`() {
        // Series/ holds no cover, Season 1 holds folder.jpg: the season gives the series its cover.
        val result = rank(
            Candidate("movie.jpg", 800_000, 675, 1000, depth = 0),
            Candidate("folder.jpg", 240_000, 675, 1000, depth = 1),
        )
        assertEquals(1, result.size)
        assertEquals("folder.jpg", result.first().name)
    }

    @Test
    fun `a cover two levels deep is used when nothing is found above`() {
        // Series/Season 1/Episodes/poster.jpg
        assertEquals(
            "poster.jpg",
            chosen(
                Candidate("episode01.jpg", 800_000, 1920, 1080, depth = 1),
                Candidate("poster.jpg", 240_000, 675, 1000, depth = 2),
            ),
        )
    }

    @Test
    fun `within one level the name rule decides between seasons`() {
        // Season 1/folder.jpg and Season 2/poster.jpg are equally deep: poster wins.
        val result = rank(
            Candidate("folder.jpg", 900_000, 1200, 1800, depth = 1),
            Candidate("poster.jpg", 120_000, 400, 600, depth = 1),
        )
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `a folder that only contains sub-folders still gets their cover`() {
        // No video and no image in the folder itself: nothing is required but the name rule.
        val result = rank(
            Candidate("cover.jpg", 260_000, 700, 1000, depth = 1),
            Candidate("cover.jpg", 260_000, 700, 1000, depth = 2),
        )
        assertEquals(2, result.size)
        assertEquals(1, result.first().depth)
    }

    // ---- the performance contract -------------------------------------------

    @Test
    fun `only cover-named images are probed - a big library never reads unrelated headers`() {
        val many = (1..40).map { Candidate("IMG_%04d.jpg".format(it), 500_000L + it, 0, 0) } +
            Candidate("poster.jpg", 300_000, 0, 0)
        var probes = 0
        val result = CoverRules.rank(many, maxProbes = 3) { candidate ->
            probes++
            candidate.copy(width = 675, height = 1000)
        }
        assertEquals("exactly the one cover-named image may be probed", 1, probes)
        assertEquals(1, result.size)
        assertEquals("poster.jpg", result.first().name)
    }

    @Test
    fun `the probe budget is respected even with many cover-named files`() {
        val many = (1..12).map { Candidate("poster-%02d.jpg".format(it), 300_000L + it, 0, 0) }
        var probes = 0
        CoverRules.rank(many, maxProbes = 3) { candidate ->
            probes++
            candidate.copy(width = 675, height = 1000)
        }
        assertTrue("probed $probes times, budget is 3", probes <= 3)
    }

    @Test
    fun `unprobed covers stay available so an undecodable winner can fall through`() {
        val result = CoverRules.rank(
            listOf(
                Candidate("poster.jpg", 300_000),
                Candidate("poster.png", 300_000),
                Candidate("cover.jpg", 300_000),
            ),
            maxProbes = 1,
        ) { it.copy(width = 675, height = 1000) }
        assertEquals(3, result.size)
        assertEquals("poster.png", result.first().name)
        assertEquals("cover.jpg", result.last().name)
    }

    // ---- geometry is a tie-breaker, never a veto -----------------------------

    @Test
    fun `a landscape image named poster is still the cover`() {
        assertEquals("poster.jpg", chosen(Candidate("poster.jpg", 900_000, 1920, 1080)))
    }

    @Test
    fun `unknown dimensions stay neutral instead of disqualifying the file`() {
        assertEquals(0, CoverRules.detailScore(0, 0))
        assertEquals(0f, CoverRules.geometryScore(0, 0), 0.0001f)
    }

    @Test
    fun `more detail ranks higher`() {
        assertTrue(CoverRules.detailScore(1200, 1800) > CoverRules.detailScore(300, 450))
        assertTrue(CoverRules.detailScore(300, 450) > CoverRules.detailScore(80, 80))
    }

    // ---- what the extractor relies on ----------------------------------------

    @Test
    fun `isUsable accepts cover names only`() {
        assertTrue(CoverRules.isUsable(Candidate("poster.jpg", 300_000)))
        assertTrue(CoverRules.isUsable(Candidate("folder.jpg", -1)))
        assertTrue(!CoverRules.isUsable(Candidate("movie.jpg", 300_000)))
        assertTrue(!CoverRules.isUsable(Candidate("poster.jpg", 0)))
        assertTrue(!CoverRules.isUsable(Candidate("poster.jpg", 300_000, hidden = true)))
    }

    @Test
    fun `rankItems keeps the caller item identity so the winner can be decoded`() {
        val items = listOf(
            "Season 2/poster.jpg" to Candidate("poster.jpg", 300_000, depth = 1),
            "Series/cover.jpg" to Candidate("cover.jpg", 300_000, depth = 0),
        )
        val ordered = CoverRules.rankItems(items, { it.second })
        assertEquals(2, ordered.size)
        assertEquals("Series/cover.jpg", ordered.first().first)
    }
}

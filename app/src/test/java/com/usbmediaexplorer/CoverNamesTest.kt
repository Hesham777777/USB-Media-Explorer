package com.usbmediaexplorer

import com.usbmediaexplorer.util.CoverNames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Folder Cover *name rule* (pure logic — no Android, no I/O).
 *
 * A cover is recognised by its file name and by nothing else:
 *
 * ```
 * poster  >  folder  >  cover        (any image extension, any letter case)
 * ```
 *
 * `movie.jpg`, `image.jpg`, `screenshot.jpg`, `photo.jpg` or any other picture inside the folder is
 * never a cover, which is what these tests pin down.
 */
class CoverNamesTest {

    // ---- the three accepted names, in priority order -----------------------

    @Test
    fun `poster folder and cover are the only cover names`() {
        assertTrue(CoverNames.isCoverName("poster.jpg"))
        assertTrue(CoverNames.isCoverName("folder.jpg"))
        assertTrue(CoverNames.isCoverName("cover.jpg"))
    }

    @Test
    fun `the exact priority is poster then folder then cover`() {
        val poster = CoverNames.tier("poster.jpg")
        val folder = CoverNames.tier("folder.jpg")
        val cover = CoverNames.tier("cover.jpg")
        assertTrue("poster=$poster folder=$folder", poster < folder)
        assertTrue("folder=$folder cover=$cover", folder < cover)
        assertEquals(CoverNames.POSTER, poster)
    }

    // ---- case and extension never matter -----------------------------------

    @Test
    fun `matching ignores letter case`() {
        assertEquals(CoverNames.tier("poster.jpg"), CoverNames.tier("POSTER.JPG"))
        assertEquals(CoverNames.tier("poster.jpg"), CoverNames.tier("Poster.png"))
        assertEquals(CoverNames.tier("poster.jpg"), CoverNames.tier("pOsTeR.WebP"))
        assertEquals(CoverNames.tier("folder.jpg"), CoverNames.tier("FOLDER.JPEG"))
        assertEquals(CoverNames.tier("cover.jpg"), CoverNames.tier("Cover.PNG"))
    }

    @Test
    fun `matching never depends on one extension`() {
        val extensions = listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "avif", "bmp", "tif", "tiff", "gif")
        extensions.forEach { ext ->
            assertTrue("poster.$ext must be a cover", CoverNames.isCoverName("poster.$ext"))
            assertTrue("folder.$ext must be a cover", CoverNames.isCoverName("folder.$ext"))
            assertTrue("cover.$ext must be a cover", CoverNames.isCoverName("cover.$ext"))
        }
        // A name with no extension at all is still recognised by its base name.
        assertTrue(CoverNames.isCoverName("poster"))
    }

    // ---- everything else is not a cover ------------------------------------

    @Test
    fun `ordinary pictures are not covers whatever their size or shape`() {
        listOf(
            "movie.jpg", "image.jpg", "screenshot.jpg", "photo.jpg", "IMG_2041.jpg",
            "artwork.jpg", "Dune.Part.Two.2024.1080p.jpg", "fanart.jpg", "backdrop.jpg",
            "front.jpg", "disc.png", "banner.jpg", "thumb.jpg", "logo.png",
        ).forEach { name ->
            assertEquals("$name must not be a cover", CoverNames.NO_MATCH, CoverNames.tier(name))
            assertFalse(CoverNames.isCoverName(name))
        }
    }

    @Test
    fun `a keyword must be a whole word - discover is not a cover`() {
        listOf("discover.jpg", "recovery.png", "subfolder.jpg", "posters-back.jpg", "uncover.jpg")
            .forEach { name ->
                assertEquals("$name must not be a cover", CoverNames.NO_MATCH, CoverNames.tier(name))
            }
    }

    @Test
    fun `artwork about the movie is not a cover even when it starts with a keyword`() {
        listOf(
            "poster-backdrop.jpg", "poster-screenshot.png", "poster-sample.jpg",
            "folder-icon.png", "cover-logo.jpg", "poster-thumb.jpg", "cover-subtitle.png",
        ).forEach { name ->
            assertEquals("$name must not be a cover", CoverNames.NO_MATCH, CoverNames.tier(name))
        }
    }

    // ---- variants of a real cover name -------------------------------------

    @Test
    fun `variants of a cover name are accepted but rank below the exact name`() {
        val poster = CoverNames.tier("poster.jpg")
        val variant = CoverNames.tier("poster-ar.jpg")
        assertTrue(variant > poster)
        assertTrue(CoverNames.isCoverName("poster-ar.jpg"))
        assertTrue(CoverNames.isCoverName("movie-poster.jpg"))
        assertTrue(CoverNames.isCoverName("The.Matrix.poster.jpg"))
        assertTrue(CoverNames.isCoverName("posters.jpg"))
    }

    @Test
    fun `the keyword dominates its variants - any poster beats any folder`() {
        assertTrue(CoverNames.tier("poster-ar.jpg") < CoverNames.tier("folder.jpg"))
        assertTrue(CoverNames.tier("folder-ar.jpg") < CoverNames.tier("cover.jpg"))
    }

    // ---- helpers -----------------------------------------------------------

    @Test
    fun `format preference is lossless first and animated or icon last`() {
        assertTrue(CoverNames.formatRank("png") > CoverNames.formatRank("webp"))
        assertTrue(CoverNames.formatRank("webp") > CoverNames.formatRank("jpg"))
        assertEquals(CoverNames.formatRank("jpg"), CoverNames.formatRank("jpeg"))
        assertTrue(CoverNames.formatRank("jpg") > CoverNames.formatRank("heic"))
        assertTrue(CoverNames.formatRank("heic") > CoverNames.formatRank("bmp"))
        assertTrue(CoverNames.formatRank("bmp") > CoverNames.formatRank("gif"))
        assertTrue(CoverNames.formatRank("gif") > CoverNames.formatRank("ico"))
        assertTrue(CoverNames.formatRank("ico") > CoverNames.formatRank("raw"))
    }

    @Test
    fun `the base name and the extension are split on the last dot`() {
        assertEquals("Poster", CoverNames.baseNameOf("Poster.JPG"))
        assertEquals("jpg", CoverNames.extensionOf("Poster.JPG"))
        assertEquals("The.Matrix.poster", CoverNames.baseNameOf("The.Matrix.poster.jpg"))
        assertEquals("poster", CoverNames.baseNameOf("poster"))
        assertEquals("", CoverNames.extensionOf("poster"))
    }

    @Test
    fun `folding keeps non-Latin letters instead of dropping them`() {
        assertEquals("poster", CoverNames.fold("Poster"))
        assertEquals("posterar", CoverNames.fold("poster-ar"))
        assertEquals("بوستر", CoverNames.fold("بوستر"))
    }
}

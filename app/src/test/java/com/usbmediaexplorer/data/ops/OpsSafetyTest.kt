package com.usbmediaexplorer.data.ops

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Staging-name and unzip-quota invariants (audit items 2 and 5), pure JVM. */
class OpsSafetyTest {

    @Test
    fun `staging names are hidden and token-scoped`() {
        val token = OpsSafety.newToken()
        assertEquals(8, token.length)
        val staged = OpsSafety.stagingName(token, "movie.mkv")
        assertTrue(staged.startsWith("."))
        assertTrue(OpsSafety.isStagingName(staged, token))
        // Another operation's token must never match — cleanup is scoped per op.
        assertFalse(OpsSafety.isStagingName(staged, OpsSafety.newToken()))
        // A user file that merely starts with a dot is not ours.
        assertFalse(OpsSafety.isStagingName(".bashrc", token))
        // An empty token matches nothing.
        assertFalse(OpsSafety.isStagingName("anything", ""))
    }

    @Test
    fun `unzip budget respects free space, the margin and the cap`() {
        // Unknown free space: fall back to the hard cap.
        assertEquals(OpsSafety.UnzipLimits.TOTAL_CAP_BYTES, OpsSafety.UnzipLimits.budgetBytes(null))
        // Plenty of space: the cap still applies.
        assertEquals(
            OpsSafety.UnzipLimits.TOTAL_CAP_BYTES,
            OpsSafety.UnzipLimits.budgetBytes(1_000L * 1024 * 1024 * 1024),
        )
        // Tight space: free minus margin.
        assertEquals(
            36L * 1024 * 1024,
            OpsSafety.UnzipLimits.budgetBytes(100L * 1024 * 1024),
        )
        // Less free space than the margin: nothing may be extracted.
        assertEquals(0L, OpsSafety.UnzipLimits.budgetBytes(32L * 1024 * 1024))
        assertEquals(0L, OpsSafety.UnzipLimits.budgetBytes(0L))
    }

    @Test
    fun `fits enforces the budget against bytes actually written`() {
        val budget = 1_000L
        assertTrue(OpsSafety.UnzipLimits.fits(budget, 0, 1_000))
        assertTrue(OpsSafety.UnzipLimits.fits(budget, 500, 500))
        assertFalse(OpsSafety.UnzipLimits.fits(budget, 500, 501))
        assertFalse(OpsSafety.UnzipLimits.fits(budget, 1_000, 1))
        // Declared entry sizes can lie; the check is chunk-based so a huge claim still stops
        // exactly at the budget.
        assertFalse(OpsSafety.UnzipLimits.fits(budget, 999, Long.MAX_VALUE / 2))
    }

    @Test
    fun `quota constants keep sane relations`() {
        assertTrue(OpsSafety.UnzipLimits.MAX_ENTRIES > 0)
        assertTrue(OpsSafety.UnzipLimits.MAX_DEPTH >= 8)
        assertTrue(OpsSafety.UnzipLimits.FREE_MARGIN_BYTES > 0)
        assertTrue(OpsSafety.UnzipLimits.TOTAL_CAP_BYTES > OpsSafety.UnzipLimits.FREE_MARGIN_BYTES)
    }
}

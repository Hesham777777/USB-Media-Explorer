package com.usbmediaexplorer.data.ops

/**
 * Safety primitives for staged file operations (audit items 2, 4, 5).
 *
 * Staged means: a copy is written under a hidden, tokenized temporary name, verified, and only
 * then renamed to its final name — and only then may a *move* delete the source. If the process
 * dies mid-operation, the token in the name is what lets the next start recognize and remove the
 * leftovers (see [OpsJournal]).
 *
 * Pure Kotlin so the invariants are unit-tested without Android.
 */
object OpsSafety {

    /** Short per-operation token embedded in every staging name this operation creates. */
    fun newToken(): String =
        java.util.UUID.randomUUID().toString().replace("-", "").take(8)

    /** `.<token>-<finalName>`: hidden from normal listings, unmistakably ours, unique per op. */
    fun stagingName(token: String, finalName: String): String = ".$token-$finalName"

    /** True for names this operation staged — the cleanup filter after failure or process death. */
    fun isStagingName(name: String, token: String): Boolean =
        token.isNotEmpty() && name.startsWith(".$token-")

    /**
     * Extraction quotas. A ZIP is untrusted input: declared entry sizes can lie, so the budget
     * is enforced against bytes *actually written*, and the operation aborts before exceeding the
     * free space of the destination.
     */
    object UnzipLimits {
        const val MAX_ENTRIES = 10_000
        const val MAX_DEPTH = 32
        const val MAX_SEGMENT_LENGTH = 255

        /** Hard ceiling even when the destination reports plenty of free space. */
        const val TOTAL_CAP_BYTES = 8L * 1024 * 1024 * 1024

        /** Never let an extraction fill the volume completely. */
        const val FREE_MARGIN_BYTES = 64L * 1024 * 1024

        /**
         * Extraction budget in bytes; 0 means "cannot safely extract anything".
         * Unknown free space (null) falls back to the hard cap alone — the margin only makes
         * sense against a known amount; a reported 0 or negative free space extracts nothing.
         */
        fun budgetBytes(freeBytes: Long?): Long {
            if (freeBytes == null) return TOTAL_CAP_BYTES
            if (freeBytes <= 0) return 0L
            return minOf(freeBytes - FREE_MARGIN_BYTES, TOTAL_CAP_BYTES).coerceAtLeast(0L)
        }

        /** Chunk-level check used while streaming an entry out. */
        fun fits(budgetBytes: Long, writtenBytes: Long, nextChunkBytes: Long): Boolean =
            writtenBytes + nextChunkBytes <= budgetBytes
    }
}

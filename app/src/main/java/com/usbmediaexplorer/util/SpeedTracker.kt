package com.usbmediaexplorer.util

/**
 * Rolling throughput estimator for transfer jobs.
 *
 * USB mass storage is bursty: a naive "total/elapsed" average hides stalls, while an
 * instantaneous rate flickers. We keep a short time window of cumulative-byte samples and
 * derive the rate from the window edges.
 */
class SpeedTracker(private val windowMs: Long = 3_000L) {

    private data class Sample(val atMs: Long, val totalBytes: Long)

    private val samples = ArrayDeque<Sample>()
    private var lastTotal = 0L

    /** Records a cumulative byte count and returns bytes/second over the window. */
    fun sample(totalBytes: Long): Double {
        val now = System.currentTimeMillis()
        lastTotal = totalBytes
        samples.addLast(Sample(now, totalBytes))
        while (samples.size > 2 && now - samples.first().atMs > windowMs) samples.removeFirst()
        if (samples.size < 2) return 0.0
        val first = samples.first()
        val last = samples.last()
        val dt = (last.atMs - first.atMs) / 1000.0
        if (dt <= 0.05) return 0.0
        return ((last.totalBytes - first.totalBytes) / dt).coerceAtLeast(0.0)
    }

    fun etaMs(remainingBytes: Long, speedBytesPerSec: Double): Long {
        if (speedBytesPerSec <= 1024.0 || remainingBytes <= 0) return -1L
        return (remainingBytes / speedBytesPerSec * 1000).toLong()
    }

    fun reset() {
        samples.clear()
        lastTotal = 0L
    }

    val lastKnownTotal: Long get() = lastTotal
}

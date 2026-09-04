package com.usbmediaexplorer.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.ParcelFileDescriptor
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.settings.FrameStrategy
import com.usbmediaexplorer.util.AppDispatchers
import com.usbmediaexplorer.util.Bitmaps
import kotlinx.coroutines.withContext

/**
 * THE core requirement of the app (spec §3, §4, §25, §27): produce a real frame from a video
 * that lives on a USB stick, without copying the file and without touching MediaStore.
 *
 * Mechanism order:
 *  1. [MediaMetadataRetriever] fed with a [ParcelFileDescriptor] obtained from the SAF URI.
 *     Works for MP4/MKV/AVI/MOV/WEBM on any storage the app may read, indexed or not.
 *  2. `ContentResolver.loadThumbnail` (API 29+) — the modern platform path, useful when the
 *     document provider ships its own thumbnailer.
 *  3. Embedded cover art, if the container has one and every frame attempt failed.
 *
 * Only when all three fail does the UI fall back to a typed icon (spec §25).
 */
class VideoFrameExtractor(
    private val context: Context,
    private val docRepository: DocRepository,
) {

    /** Candidate positions probed by [FrameStrategy.AUTO], best-first. */
    private val autoFractions = doubleArrayOf(0.10, 0.25, 0.50, 0.03, 0.75)

    /** Score above which AUTO stops probing early — keeps scrolling smooth. */
    private val goodEnoughScore = 0.68f

    suspend fun extract(request: ThumbRequest, durationHintMs: Long): ByteArray? =
        withContext(AppDispatchers.thumbnail) {
            val node = request.node
            val viaRetriever = runCatching { extractWithRetriever(node, request, durationHintMs) }
                .getOrNull()
            if (viaRetriever != null) return@withContext viaRetriever

            extractWithLoadThumbnail(node, request)
                ?: extractEmbeddedCover(node, request)
        }

    // ------------------------------------------------------------------

    private fun extractWithRetriever(
        node: DocNode,
        request: ThumbRequest,
        durationHintMs: Long,
    ): ByteArray? {
        val retriever = MediaMetadataRetriever()
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = docRepository.openFd(node.uri, "r")
            val fdOpened = pfd != null && runCatching {
                retriever.setDataSource(pfd.fileDescriptor)
                true
            }.getOrDefault(false)
            if (!fdOpened) {
                // No fd (rare providers): fall back to the content URI overload.
                runCatching { retriever.setDataSource(context, node.uri) }
            }

            // Cover art first when asked for (the poster view turns this on by default, see the
            // "poster covers first" setting) — always artwork carried by the file itself.
            if (request.preferEmbeddedCover) {
                coverBytes(retriever, request)?.let { return it }
            }

            val durationMs = durationHintMs.takeIf { it > 0 }
                ?: retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                ?: 0L
            val rotation = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                ?.toFloatOrNull()?.toInt() ?: 0

            val frame = when (request.strategy) {
                FrameStrategy.AUTO -> pickBestFrame(retriever, durationMs, request)
                else -> frameWithRetries(retriever, positionMicros(durationMs, request.strategy), request)
            } ?: run {
                // Last attempt inside the same open file: something in the middle.
                frameWithRetries(retriever, durationMs * 1000 / 2, request)
            }

            return frame?.let { finish(it, request, rotation) }
                ?: coverBytes(retriever, request)
        } catch (t: Throwable) {
            return null
        } finally {
            runCatching { retriever.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun positionMicros(durationMs: Long, strategy: FrameStrategy): Long {
        if (durationMs <= 0) return 0L
        val fraction = when (strategy) {
            FrameStrategy.FIRST -> 0.0
            FrameStrategy.P5 -> 0.05
            FrameStrategy.P10 -> 0.10
            FrameStrategy.P25 -> 0.25
            FrameStrategy.MIDDLE -> 0.50
            FrameStrategy.AUTO -> 0.10
        }
        return (durationMs * 1000L * fraction).toLong()
    }

    /**
     * AUTO: probe a few positions at low resolution, score them and decode the winner at full
     * thumbnail size. Cheap because the probes are 128 px and stop as soon as one looks good.
     */
    private fun pickBestFrame(
        retriever: MediaMetadataRetriever,
        durationMs: Long,
        request: ThumbRequest,
    ): Bitmap? {
        if (durationMs <= 0) {
            return frameWithRetries(retriever, 0L, request)
        }
        var bestMicros = -1L
        var bestScore = -1f
        for (fraction in autoFractions) {
            val micros = (durationMs * 1000L * fraction).toLong()
            val probe = probeFrame(retriever, micros) ?: continue
            val score = Bitmaps.frameScore(probe)
            if (!probe.isRecycled) probe.recycle()
            if (score > bestScore) {
                bestScore = score
                bestMicros = micros
            }
            if (bestScore >= goodEnoughScore) break
        }
        if (bestMicros < 0) return frameWithRetries(retriever, durationMs * 1000 / 10, request)
        return frameWithRetries(retriever, bestMicros, request)
    }

    private fun probeFrame(retriever: MediaMetadataRetriever, micros: Long): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(
                micros,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                PROBE_SIZE,
                PROBE_SIZE,
            )
        } else {
            retriever.getFrameAtTime(micros, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?.let { Bitmaps.fitInside(it, PROBE_SIZE, PROBE_SIZE) }
        }
    }.getOrNull()

    /**
     * A single decode rarely succeeds on exotic containers, so each position is retried with
     * slightly different options/offsets before giving up (spec §25: try more than one mechanism).
     */
    private fun frameWithRetries(
        retriever: MediaMetadataRetriever,
        micros: Long,
        request: ThumbRequest,
    ): Bitmap? {
        val offsets = longArrayOf(0L, 1_500_000L, -1_500_000L, 4_000_000L, -4_000_000L)
        for (offset in offsets) {
            val target = (micros + offset).coerceAtLeast(0L)
            decodeAt(retriever, target, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, request)
                ?.let { return it }
            decodeAt(retriever, target, MediaMetadataRetriever.OPTION_CLOSEST, request)
                ?.let { return it }
            decodeAt(retriever, target, MediaMetadataRetriever.OPTION_PREVIOUS_SYNC, request)
                ?.let { return it }
        }
        return null
    }

    private fun decodeAt(
        retriever: MediaMetadataRetriever,
        micros: Long,
        option: Int,
        request: ThumbRequest,
    ): Bitmap? = runCatching {
        val raw = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            retriever.getScaledFrameAtTime(micros, option, request.widthPx, request.heightPx)
        } else {
            retriever.getFrameAtTime(micros, option)
        }
        raw?.let { Bitmaps.fitInside(it, request.widthPx, request.heightPx) }
    }.getOrNull()

    private fun finish(bitmap: Bitmap, request: ThumbRequest, rotation: Int): ByteArray? {
        val oriented = if (rotation != 0) Bitmaps.rotate(bitmap, rotation.toFloat()) else bitmap
        val scaled = fit(oriented, request)
        return runCatching { Bitmaps.encode(scaled, request.quality) }.also {
            if (!scaled.isRecycled) scaled.recycle()
        }.getOrNull()
    }

    private fun coverBytes(retriever: MediaMetadataRetriever, request: ThumbRequest): ByteArray? =
        runCatching {
            val picture = retriever.embeddedPicture ?: return@runCatching null
            val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size) ?: return@runCatching null
            val scaled = fit(bitmap, request)
            Bitmaps.encode(scaled, request.quality).also {
                if (!scaled.isRecycled) scaled.recycle()
            }
        }.getOrNull()

    /** API 29+: let the platform thumbnail the document (some providers do this very well). */
    private fun extractWithLoadThumbnail(node: DocNode, request: ThumbRequest): ByteArray? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return runCatching {
            val bitmap = context.contentResolver.loadThumbnail(
                node.uri,
                android.util.Size(request.widthPx, request.heightPx),
                null,
            )
            val scaled = fit(bitmap, request)
            Bitmaps.encode(scaled, request.quality).also {
                if (!scaled.isRecycled) scaled.recycle()
            }
        }.getOrNull()
    }

    /**
     * Poster tiles are cropped to their ratio so a 16:9 frame still fills a 2:3 card; everything
     * else keeps the whole picture inside the requested box.
     */
    private fun fit(bitmap: Bitmap, request: ThumbRequest): Bitmap =
        if (request.poster) {
            Bitmaps.centerCrop(bitmap, request.widthPx, request.heightPx)
        } else {
            Bitmaps.fitInside(bitmap, request.widthPx, request.heightPx)
        }

    private fun extractEmbeddedCover(node: DocNode, request: ThumbRequest): ByteArray? =
        runCatching {
            val retriever = MediaMetadataRetriever()
            var pfd: ParcelFileDescriptor? = null
            try {
                pfd = docRepository.openFd(node.uri, "r")
                if (pfd != null) retriever.setDataSource(pfd.fileDescriptor)
                else retriever.setDataSource(context, node.uri)
                coverBytes(retriever, request)
            } finally {
                runCatching { retriever.release() }
                runCatching { pfd?.close() }
            }
        }.getOrNull()

    private companion object {
        const val PROBE_SIZE = 128
    }
}

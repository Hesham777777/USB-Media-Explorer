package com.usbmediaexplorer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.os.Build
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Bitmap helpers shared by the thumbnail pipeline. Everything runs on a background dispatcher. */
object Bitmaps {

    /** Scales [source] so it fits inside maxW×maxH keeping the aspect ratio. */
    fun fitInside(source: Bitmap, maxW: Int, maxH: Int): Bitmap {
        if (source.width <= maxW && source.height <= maxH) return source
        val ratio = min(maxW.toFloat() / source.width, maxH.toFloat() / source.height)
        val w = max(1, (source.width * ratio).toInt())
        val h = max(1, (source.height * ratio).toInt())
        val scaled = Bitmap.createScaledBitmap(source, w, h, true)
        if (scaled !== source && !source.isRecycled) source.recycle()
        return scaled
    }

    /** Center-crops [source] into exactly w×h. Used for the huge media grid. */
    fun centerCrop(source: Bitmap, w: Int, h: Int): Bitmap {
        if (source.width == w && source.height == h) return source
        val scale = max(w.toFloat() / source.width, h.toFloat() / source.height)
        val scaledW = max(1, (source.width * scale).toInt())
        val scaledH = max(1, (source.height * scale).toInt())
        val scaled = Bitmap.createScaledBitmap(source, scaledW, scaledH, true)
        val x = ((scaledW - w) / 2).coerceAtLeast(0)
        val y = ((scaledH - h) / 2).coerceAtLeast(0)
        val cropW = min(w, scaledW - x)
        val cropH = min(h, scaledH - y)
        val cropped = Bitmap.createBitmap(scaled, x, y, cropW, cropH)
        if (scaled !== source && !scaled.isRecycled) scaled.recycle()
        if (cropped !== source && !source.isRecycled && source !== scaled) source.recycle()
        return cropped
    }

    fun rotate(source: Bitmap, degrees: Float): Bitmap {
        if (degrees == 0f) return source
        val matrix = Matrix().apply { postRotate(degrees) }
        val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        if (rotated !== source && !source.isRecycled) source.recycle()
        return rotated
    }

    /**
     * Encodes a thumbnail. WebP keeps the on-USB cache small; JPEG is the fallback for
     * very old platforms where the lossy WebP encoder is missing.
     */
    fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val stream = ByteArrayOutputStream(max(4096, bitmap.width * bitmap.height / 8))
        val format = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> @Suppress("DEPRECATION") Bitmap.CompressFormat.WEBP
        }
        val ok = runCatching { bitmap.compress(format, quality.coerceIn(20, 100), stream) }.getOrDefault(false)
        if (!ok) {
            stream.reset()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality.coerceIn(20, 100), stream)
        }
        return stream.toByteArray()
    }

    /**
     * Downsamples an image stream without ever decoding the full resolution bitmap —
     * essential when the source is a 60 MP photo sitting on a slow USB stick.
     */
    fun decodeSampled(streamProvider: () -> InputStream?, reqWidth: Int, reqHeight: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        streamProvider()?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            // Unparseable header: let the real decode attempt produce the error.
            return streamProvider()?.use { BitmapFactory.decodeStream(it) }
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, reqWidth, reqHeight)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return streamProvider()?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    fun sampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var sample = 1
        var halfW = width / 2
        var halfH = height / 2
        while (halfW / sample >= reqWidth && halfH / sample >= reqHeight) {
            sample *= 2
        }
        return sample.coerceAtLeast(1)
    }

    /**
     * Scores a candidate video frame so the "automatic" strategy can reject black frames,
     * studio-logo fades and flat colour cards. Higher is better.
     *
     * The score combines three cheap signals computed on a downscaled copy:
     *  - mean luma (frames that are almost black or blown out are penalised),
     *  - luma standard deviation (flat/uniform frames score low),
     *  - colourfulness (grey test patterns score lower than real content).
     */
    fun frameScore(source: Bitmap): Float {
        val probe = if (source.width > 96 || source.height > 96) {
            source.let { fitInside(it.copy(it.config ?: Bitmap.Config.ARGB_8888, false), 96, 96) }
        } else {
            source
        }
        val w = probe.width
        val h = probe.height
        if (w <= 0 || h <= 0) return 0f
        val pixels = IntArray(w * h)
        probe.getPixels(pixels, 0, w, 0, 0, w, h)
        if (probe !== source && !probe.isRecycled) probe.recycle()

        var sumLuma = 0.0
        var sumSq = 0.0
        var sumR = 0.0
        var sumG = 0.0
        var sumB = 0.0
        var sumAbsRG = 0.0
        var sumAbsBY = 0.0
        for (p in pixels) {
            val r = Color.red(p)
            val g = Color.green(p)
            val b = Color.blue(p)
            val luma = 0.299 * r + 0.587 * g + 0.114 * b
            sumLuma += luma
            sumSq += luma * luma
            sumR += r
            sumG += g
            sumB += b
            sumAbsRG += kotlin.math.abs(r - g)
            val y = (r + g) / 2.0
            sumAbsBY += kotlin.math.abs(b - y)
        }
        val n = pixels.size.toDouble()
        val mean = sumLuma / n
        val variance = (sumSq / n) - (mean * mean)
        val std = sqrt(max(0.0, variance))
        val meanR = sumR / n
        val meanG = sumG / n
        val meanB = sumB / n
        val rg = sumAbsRG / n
        val by = sumAbsBY / n
        val colourfulness = sqrt(rg * rg + by * by) + 0.3 * (rg + by)

        // Brightness term peaks around mid-grey.
        val brightnessScore = when {
            mean < 8.0 -> (mean / 8.0 * 0.15).toFloat()
            mean > 245.0 -> (((255.0 - mean) / 10.0).coerceAtLeast(0.0) * 0.15).toFloat()
            else -> (0.55 + (1.0 - kotlin.math.abs(mean - 128.0) / 128.0) * 0.25).toFloat()
        }
        val detailScore = (std / 74.0).coerceIn(0.0, 1.0).toFloat()
        val colourScore = (colourfulness / 40.0).coerceIn(0.0, 1.0).toFloat()

        return brightnessScore * 0.35f + detailScore * 0.45f + colourScore * 0.20f
    }
}

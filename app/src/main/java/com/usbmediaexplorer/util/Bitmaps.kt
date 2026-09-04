package com.usbmediaexplorer.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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

    /** Composes up to four bitmaps into a 2×2 folder preview. */
    fun composeGrid(parts: List<Bitmap?>, width: Int, height: Int, background: Int): Bitmap {
        val out = Bitmap.createBitmap(max(1, width), max(1, height), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(background)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        val cellW = out.width / 2
        val cellH = out.height / 2
        val slots = listOf(
            Rect(0, 0, cellW, cellH),
            Rect(cellW, 0, out.width, cellH),
            Rect(0, cellH, cellW, out.height),
            Rect(cellW, cellH, out.width, out.height),
        )
        parts.take(4).forEachIndexed { index, bmp ->
            if (bmp == null || bmp.isRecycled) return@forEachIndexed
            val target = slots[index]
            // Center-crop each cell so the mosaic has no letterboxing.
            val scale = max(
                target.width().toFloat() / bmp.width,
                target.height().toFloat() / bmp.height,
            )
            val srcW = (target.width() / scale).toInt().coerceIn(1, bmp.width)
            val srcH = (target.height() / scale).toInt().coerceIn(1, bmp.height)
            val srcX = (bmp.width - srcW) / 2
            val srcY = (bmp.height - srcH) / 2
            canvas.drawBitmap(bmp, Rect(srcX, srcY, srcX + srcW, srcY + srcH), target, paint)
        }
        return out
    }

    /**
     * Windows-Explorer-style folder preview (spec §7): an actual folder shape whose pocket is
     * filled with previews of the folder's *own* media — a video frame or a photo from inside the
     * folder, never a stock icon and never anything fetched from the network.
     *
     * Pure Canvas drawing, so it works on every API level, and the result keeps its alpha channel
     * (WebP is encoded with alpha) so the folder sits naturally on any card colour.
     */
    fun composeFolderWindows(parts: List<Bitmap?>, width: Int, height: Int): Bitmap {
        val w = max(1, width)
        val h = max(1, height)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val wf = w.toFloat()
        val hf = h.toFloat()
        val radius = wf * 0.075f

        // Geometry: a tab sticking out top-left, the body below it, and a "lip" across the bottom
        // that hides the lower edge of the previews so they look tucked inside the folder.
        val bodyTop = hf * 0.24f
        val body = RectF(0f, bodyTop, wf, hf)
        val tab = RectF(0f, hf * 0.10f, wf * 0.44f, bodyTop + radius)
        val back = Path().apply {
            addRoundRect(tab, topRadii(radius), Path.Direction.CW)
            addRoundRect(body, bottomRadii(radius), Path.Direction.CW)
        }
        val backPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, hf * 0.10f, 0f, hf,
                0xFFF1D68E.toInt(), 0xFFBF954A.toInt(),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawPath(back, backPaint)

        // Pocket the previews live in.
        val padX = wf * 0.075f
        val pocket = RectF(padX, bodyTop + hf * 0.055f, wf - padX, hf * 0.745f)
        val pocketPath = Path().apply {
            addRoundRect(pocket, radius * 0.55f, radius * 0.55f, Path.Direction.CW)
        }
        canvas.drawPath(
            pocketPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1D2127.toInt() },
        )

        val available = parts.take(4)
        val slots = pocketSlots(pocket, available.size, wf * 0.018f)
        val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.save()
        canvas.clipPath(pocketPath)
        available.forEachIndexed { index, bmp ->
            val target = slots.getOrElse(index) { return@forEachIndexed }
            if (bmp == null || bmp.isRecycled) return@forEachIndexed
            drawCropped(canvas, bmp, target, imagePaint)
        }
        canvas.restore()

        // Front lip.
        val lipTop = hf * 0.70f
        val lipPath = Path().apply {
            addRoundRect(RectF(0f, lipTop, wf, hf), bottomRadii(radius), Path.Direction.CW)
        }
        canvas.drawPath(
            lipPath,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    0f, lipTop, 0f, hf,
                    0xFFF9E9B4.toInt(), 0xFFCFA95F.toInt(),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        canvas.drawLine(
            radius, lipTop + hf * 0.004f, wf - radius, lipTop + hf * 0.004f,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0x66FFFFFF
                strokeWidth = max(1f, hf * 0.006f)
            },
        )

        // Outline so the folder reads on light backgrounds too.
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = max(1f, wf * 0.009f)
            color = 0x45000000
        }
        canvas.drawPath(back, stroke)
        canvas.drawPath(lipPath, stroke)
        return out
    }

    /** Slot layout inside the folder pocket for 1–4 previews. */
    private fun pocketSlots(pocket: RectF, count: Int, gap: Float): List<RectF> {
        val cells = max(1, count)
        return when (cells) {
            1 -> listOf(RectF(pocket))

            2 -> {
                val halfW = (pocket.width() - gap) / 2f
                listOf(
                    RectF(pocket.left, pocket.top, pocket.left + halfW, pocket.bottom),
                    RectF(pocket.left + halfW + gap, pocket.top, pocket.right, pocket.bottom),
                )
            }

            3 -> {
                val halfW = (pocket.width() - gap) / 2f
                val halfH = (pocket.height() - gap) / 2f
                listOf(
                    RectF(pocket.left, pocket.top, pocket.left + halfW, pocket.top + halfH),
                    RectF(pocket.left + halfW + gap, pocket.top, pocket.right, pocket.top + halfH),
                    RectF(pocket.left, pocket.top + halfH + gap, pocket.right, pocket.bottom),
                )
            }

            else -> {
                val halfW = (pocket.width() - gap) / 2f
                val halfH = (pocket.height() - gap) / 2f
                listOf(
                    RectF(pocket.left, pocket.top, pocket.left + halfW, pocket.top + halfH),
                    RectF(pocket.left + halfW + gap, pocket.top, pocket.right, pocket.top + halfH),
                    RectF(pocket.left, pocket.top + halfH + gap, pocket.left + halfW, pocket.bottom),
                    RectF(
                        pocket.left + halfW + gap, pocket.top + halfH + gap,
                        pocket.right, pocket.bottom,
                    ),
                )
            }
        }
    }

    /** Draws [bmp] into [target] with a centre crop, so no slot is ever letterboxed. */
    private fun drawCropped(canvas: Canvas, bmp: Bitmap, target: RectF, paint: Paint) {
        val scale = max(target.width() / bmp.width, target.height() / bmp.height)
        val srcW = (target.width() / scale).toInt().coerceIn(1, bmp.width)
        val srcH = (target.height() / scale).toInt().coerceIn(1, bmp.height)
        val srcX = (bmp.width - srcW) / 2
        val srcY = (bmp.height - srcH) / 2
        canvas.drawBitmap(
            bmp,
            Rect(srcX, srcY, srcX + srcW, srcY + srcH),
            RectF(target),
            paint,
        )
    }

    private fun topRadii(r: Float) = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)

    private fun bottomRadii(r: Float) = floatArrayOf(0f, 0f, 0f, 0f, r, r, r, r)
}

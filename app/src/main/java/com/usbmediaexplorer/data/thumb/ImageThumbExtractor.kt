package com.usbmediaexplorer.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.util.AppDispatchers
import com.usbmediaexplorer.util.Bitmaps
import kotlinx.coroutines.withContext

/**
 * Image previews (spec §6): the picture itself is the thumbnail — JPG/PNG/WEBP/GIF/BMP and,
 * where the platform supports it, HEIC/HEIF and AVIF.
 *
 * Decoding is always downsampled: a 60 MP photo on a USB stick must never be decoded at full
 * size just to draw a 512 px card.
 */
class ImageThumbExtractor(
    private val context: Context,
    private val docRepository: DocRepository,
) {

    suspend fun extract(request: ThumbRequest): ByteArray? = withContext(AppDispatchers.thumbnail) {
        val node = request.node
        val bitmap = decode(node, request)
        bitmap?.let {
            runCatching {
                val scaled = Bitmaps.fitInside(it, request.widthPx, request.heightPx)
                Bitmaps.encode(scaled, request.quality).also { bytes ->
                    if (!scaled.isRecycled) scaled.recycle()
                }
            }.getOrNull()
        }
    }

    private fun decode(node: DocNode, request: ThumbRequest): Bitmap? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            decodeWithImageDecoder(node, request)?.let { return it }
        }
        return decodeWithBitmapFactory(node, request)
    }

    /** Handles HEIF/AVIF/animated GIF and applies EXIF orientation automatically. */
    private fun decodeWithImageDecoder(node: DocNode, request: ThumbRequest): Bitmap? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return null
        return runCatching {
            val source = ImageDecoder.createSource(context.contentResolver, node.uri)
            ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                val size = info.size
                val sample = Bitmaps.sampleSize(
                    size.width,
                    size.height,
                    request.widthPx,
                    request.heightPx,
                )
                if (sample > 1) decoder.setTargetSampleSize(sample)
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE)
                decoder.isMutableRequired = false
            }
        }.getOrNull()
    }

    private fun decodeWithBitmapFactory(node: DocNode, request: ThumbRequest): Bitmap? =
        runCatching {
            val bitmap = Bitmaps.decodeSampled(
                streamProvider = { docRepository.openInput(node.uri) },
                reqWidth = request.widthPx,
                reqHeight = request.heightPx,
            ) ?: return@runCatching null
            applyExifRotation(node, bitmap)
        }.getOrNull()

    private fun applyExifRotation(node: DocNode, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            docRepository.openInput(node.uri)?.use { stream ->
                ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL,
                )
            } ?: ExifInterface.ORIENTATION_NORMAL
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)

        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> 0f
        }
        return if (degrees == 0f) bitmap else Bitmaps.rotate(bitmap, degrees)
    }
}

package com.usbmediaexplorer.data.metadata

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads duration, resolution, frame rate, codecs and track lists straight from the media file
 * (spec §8, §21, §27).
 *
 * Three mechanisms, in order of preference:
 *  1. [MediaMetadataRetriever] fed with a **file descriptor** obtained from the SAF URI. This is
 *     the only reliable way to inspect a file on a USB stick that MediaStore has never indexed.
 *  2. [MediaExtractor] for per-track detail (codec MIME, language, channels, duration).
 *
 * Both read through the *same* ParcelFileDescriptor that SAF hands out for the USB document, so
 * a file MediaStore has never seen is still fully described. Media3 is deliberately not used
 * here: playback needs its own extractor chain, metadata does not.
 *
 * Every step is read-only: the source file is never modified (spec §24).
 */
class MediaMetadataReader(
    private val context: Context,
    private val docRepository: DocRepository,
) {

    suspend fun read(node: DocNode): MediaMetadata = withContext(Dispatchers.IO) {
        val retrieverData = readWithRetriever(node)
        val tracks = readTracks(node)
        val videoTrack = tracks.firstOrNull { it.type == TrackType.VIDEO }
        val duration = retrieverData.durationMs
            .takeIf { it > 0 }
            ?: tracks.maxOfOrNull { it.durationMs }
            ?: 0L

        val width = retrieverData.width.takeIf { it > 0 } ?: videoTrack?.width ?: 0
        val height = retrieverData.height.takeIf { it > 0 } ?: videoTrack?.height ?: 0

        MediaMetadata(
            key = node.key,
            durationMs = duration,
            width = width,
            height = height,
            rotation = retrieverData.rotation.takeIf { it != 0 } ?: readRotation(tracks) ?: 0,
            fps = retrieverData.fps,
            bitrate = retrieverData.bitrate,
            videoCodec = videoTrack?.mime ?: retrieverData.mimeType,
            containerMime = retrieverData.mimeType ?: node.mimeType,
            tracks = tracks,
            hasEmbeddedArtwork = retrieverData.hasArtwork,
            source = when {
                retrieverData.durationMs > 0 || width > 0 -> MetadataSource.RETRIEVER
                tracks.isNotEmpty() -> MetadataSource.EXTRACTOR
                else -> MetadataSource.NONE
            },
        )
    }

    // ------------------------------------------------------------------

    private class RetrieverData(
        val durationMs: Long = 0L,
        val width: Int = 0,
        val height: Int = 0,
        val rotation: Int = 0,
        val fps: Float = 0f,
        val bitrate: Long = 0L,
        val mimeType: String? = null,
        val hasArtwork: Boolean = false,
    )

    private fun readWithRetriever(node: DocNode): RetrieverData {
        val retriever = MediaMetadataRetriever()
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = docRepository.openFd(node.uri, "r")
            if (pfd != null) {
                retriever.setDataSource(pfd.fileDescriptor)
            } else {
                retriever.setDataSource(context, node.uri)
            }
            RetrieverData(
                durationMs = retriever.long(MediaMetadataRetriever.METADATA_KEY_DURATION),
                width = retriever.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),
                height = retriever.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),
                rotation = retriever.int(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION),
                fps = retriever.float(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE),
                bitrate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    retriever.long(MediaMetadataRetriever.METADATA_KEY_BITRATE)
                } else {
                    0L
                },
                mimeType = retriever.string(MediaMetadataRetriever.METADATA_KEY_MIMETYPE),
                hasArtwork = runCatching { retriever.embeddedPicture?.isNotEmpty() == true }
                    .getOrDefault(false),
            )
        } catch (t: Throwable) {
            RetrieverData()
        } finally {
            runCatching { retriever.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun readTracks(node: DocNode): List<TrackInfo> {
        val extractor = MediaExtractor()
        var pfd: android.os.ParcelFileDescriptor? = null
        return try {
            pfd = docRepository.openFd(node.uri, "r")
            val opened = if (pfd != null) {
                runCatching { extractor.setDataSource(pfd.fileDescriptor); true }.getOrDefault(false)
            } else {
                false
            }
            if (!opened) {
                runCatching { extractor.setDataSource(context, node.uri, null) }
            }
            (0 until extractor.trackCount).mapNotNull { index ->
                runCatching { extractor.getTrackFormat(index) }.getOrNull()?.let { format ->
                    trackInfo(index, format)
                }
            }
        } catch (t: Throwable) {
            emptyList()
        } finally {
            runCatching { extractor.release() }
            runCatching { pfd?.close() }
        }
    }

    private fun trackInfo(index: Int, format: MediaFormat): TrackInfo {
        val mime = format.optString(MediaFormat.KEY_MIME)
        val type = when {
            mime == null -> TrackType.UNKNOWN
            mime.startsWith("video/") -> TrackType.VIDEO
            mime.startsWith("audio/") -> TrackType.AUDIO
            mime.startsWith("text/") || mime.contains("subrip") || mime.contains("cea") ||
                mime.contains("ttml") || mime.contains("x-ssa") || mime.contains("vtt") ->
                TrackType.TEXT

            mime.startsWith("image/") -> TrackType.IMAGE
            else -> TrackType.UNKNOWN
        }
        return TrackInfo(
            index = index,
            type = type,
            mime = mime,
            language = format.optString(MediaFormat.KEY_LANGUAGE)?.takeIf { it.isNotBlank() && it != "und" },
            width = format.optInt(MediaFormat.KEY_WIDTH),
            height = format.optInt(MediaFormat.KEY_HEIGHT),
            durationMs = format.optLong(MediaFormat.KEY_DURATION) / 1000L,
            channelCount = format.optInt(MediaFormat.KEY_CHANNEL_COUNT),
            sampleRate = format.optInt(MediaFormat.KEY_SAMPLE_RATE),
            bitrate = format.optLong(MediaFormat.KEY_BIT_RATE),
        )
    }

    private fun readRotation(tracks: List<TrackInfo>): Int? = null

    // ---- MediaMetadataRetriever returns Strings; parse defensively -------

    private fun MediaMetadataRetriever.long(key: Int): Long =
        extractMetadata(key)?.toLongOrNull() ?: 0L

    private fun MediaMetadataRetriever.int(key: Int): Int =
        extractMetadata(key)?.toFloatOrNull()?.toInt() ?: 0

    private fun MediaMetadataRetriever.float(key: Int): Float =
        extractMetadata(key)?.toFloatOrNull() ?: 0f

    private fun MediaMetadataRetriever.string(key: Int): String? = extractMetadata(key)

    private fun MediaFormat.optString(key: String): String? =
        if (containsKey(key)) runCatching { getString(key) }.getOrNull() else null

    private fun MediaFormat.optInt(key: String): Int =
        if (containsKey(key)) runCatching { getInteger(key) }.getOrDefault(0) else 0

    private fun MediaFormat.optLong(key: String): Long =
        if (containsKey(key)) runCatching { getLong(key) }.getOrDefault(0L) else 0L
}

package com.usbmediaexplorer.data.metadata

import com.usbmediaexplorer.util.Formatters

enum class TrackType { VIDEO, AUDIO, TEXT, IMAGE, UNKNOWN }

data class TrackInfo(
    val index: Int,
    val type: TrackType,
    val mime: String?,
    val language: String?,
    val label: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0L,
    val channelCount: Int = 0,
    val sampleRate: Int = 0,
    val bitrate: Long = 0L,
) {
    /** "English", "video/hevc", "Track 2" — whatever is most informative. */
    val displayName: String
        get() = listOfNotNull(
            label?.takeIf { it.isNotBlank() },
            language?.takeIf { it.isNotBlank() && it != "und" },
            codecName,
        ).firstOrNull() ?: "Track ${index + 1}"

    val codecName: String?
        get() = mime?.substringAfterLast('/')?.replace("-", " ")?.uppercase()
}

enum class MetadataSource { RETRIEVER, EXTRACTOR, NONE }

/**
 * Everything the grid card, the details sheet and the player need to know about a media file,
 * read *from the file itself* — never from MediaStore and never from the network (spec §27, §23).
 */
data class MediaMetadata(
    val key: String,
    val durationMs: Long = 0L,
    val width: Int = 0,
    val height: Int = 0,
    val rotation: Int = 0,
    val fps: Float = 0f,
    val bitrate: Long = 0L,
    val videoCodec: String? = null,
    val containerMime: String? = null,
    val tracks: List<TrackInfo> = emptyList(),
    val hasEmbeddedArtwork: Boolean = false,
    val source: MetadataSource = MetadataSource.NONE,
) {
    val audioTracks: List<TrackInfo> get() = tracks.filter { it.type == TrackType.AUDIO }
    val subtitleTracks: List<TrackInfo> get() = tracks.filter { it.type == TrackType.TEXT }

    val resolutionLabel: String get() = Formatters.resolution(effectiveWidth, effectiveHeight)

    /** Rotation-aware dimensions, so a portrait phone video reports 1080x1920 correctly. */
    val effectiveWidth: Int get() = if (rotation == 90 || rotation == 270) height else width
    val effectiveHeight: Int get() = if (rotation == 90 || rotation == 270) width else height

    val fpsLabel: String get() = Formatters.fps(fps)

    val codecLabel: String
        get() = videoCodec?.substringAfterLast('/')?.replace("-", " ")?.uppercase() ?: ""

    val audioLabel: String
        get() = audioTracks.firstOrNull()?.codecName ?: ""

    val subtitleLabel: String
        get() = when (subtitleTracks.size) {
            0 -> ""
            1 -> subtitleTracks.first().language?.uppercase() ?: "1"
            else -> "${subtitleTracks.size}"
        }

    /** "2.41 GB • 2:04:18 • 1080p" */
    fun summaryLine(locale: java.util.Locale = java.util.Locale.getDefault()): String =
        listOfNotNull(
            Formatters.duration(durationMs).takeIf { durationMs > 0 },
            resolutionLabel.takeIf { it.isNotEmpty() },
        ).joinToString(" • ")

    companion object {
        val EMPTY = MediaMetadata(key = "")
    }
}

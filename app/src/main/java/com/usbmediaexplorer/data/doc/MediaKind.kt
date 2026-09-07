package com.usbmediaexplorer.data.doc

import java.util.Locale

/** Coarse classification used for icons, filters, sorting and thumbnail routing. */
enum class MediaKind {
    DIRECTORY,
    VIDEO,
    IMAGE,
    AUDIO,
    SUBTITLE,
    ARCHIVE,
    DOCUMENT,
    APK,
    OTHER,
    ;

    val isMedia: Boolean get() = this == VIDEO || this == IMAGE || this == AUDIO

    val isVisual: Boolean get() = this == VIDEO || this == IMAGE

    companion object {
        val VIDEO_EXT = setOf(
            "mp4", "m4v", "mkv", "webm", "avi", "mov", "qt", "3gp", "3g2", "ts", "mts", "m2ts",
            "flv", "f4v", "wmv", "asf", "mpg", "mpeg", "mpe", "ogv", "vob", "divx", "rm", "rmvb",
            "mxf", "avchd", "evo", "mp2v",
        )
        val IMAGE_EXT = setOf(
            "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic", "heif", "avif", "tif", "tiff",
            "dng", "cr2", "nef", "arw", "raf", "orf", "rw2", "ico",
        )
        val AUDIO_EXT = setOf(
            "mp3", "flac", "aac", "m4a", "m4b", "ogg", "oga", "opus", "wav", "wave", "wma",
            "aiff", "aif", "ape", "alac", "mid", "midi", "amr", "awb", "mka", "dsf", "dff",
        )
        val SUBTITLE_EXT = setOf("srt", "ass", "ssa", "vtt", "sub", "idx", "sup", "ttml", "dfxp")
        val ARCHIVE_EXT = setOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "cab")
        val DOCUMENT_EXT = setOf(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "csv", "md",
            "epub", "mobi", "json", "xml", "html", "htm", "log", "ini", "nfo",
        )
        val APK_EXT = setOf("apk", "apks", "xapk")

        fun ofExtension(ext: String): MediaKind {
            val e = ext.lowercase(Locale.US).removePrefix(".")
            return when (e) {
                in VIDEO_EXT -> VIDEO
                in IMAGE_EXT -> IMAGE
                in AUDIO_EXT -> AUDIO
                in SUBTITLE_EXT -> SUBTITLE
                in ARCHIVE_EXT -> ARCHIVE
                in DOCUMENT_EXT -> DOCUMENT
                in APK_EXT -> APK
                else -> OTHER
            }
        }

        fun of(extension: String, mimeType: String?, isDirectory: Boolean): MediaKind {
            if (isDirectory) return DIRECTORY
            if (!mimeType.isNullOrBlank()) {
                val mt = mimeType.lowercase(Locale.US)
                if (mt.startsWith("video/")) return VIDEO
                if (mt.startsWith("image/")) return IMAGE
                if (mt.startsWith("audio/")) return AUDIO
                if (mt == "application/x-subrip" || mt == "text/vtt" || mt == "text/x-ssa" ||
                    mt == "application/ass" || mt == "text/x-ass"
                ) {
                    return SUBTITLE
                }
                if (mt == "application/zip" || mt == "application/x-7z-compressed" ||
                    mt == "application/rar" || mt == "application/vnd.android.package-archive"
                ) {
                    return if (mt.endsWith("package-archive")) APK else ARCHIVE
                }
            }
            return ofExtension(extension)
        }

        /** MIME type used when creating files and when sharing. */
        fun mimeTypeFor(ext: String): String {
            val e = ext.lowercase(Locale.US).removePrefix(".")
            return when (e) {
                "mp4", "m4v" -> "video/mp4"
                "mkv" -> "video/x-matroska"
                "webm" -> "video/webm"
                "avi" -> "video/x-msvideo"
                "mov", "qt" -> "video/quicktime"
                "3gp" -> "video/3gpp"
                "ts", "m2ts", "mts" -> "video/mp2t"
                "flv" -> "video/x-flv"
                "wmv" -> "video/x-ms-wmv"
                "mpg", "mpeg" -> "video/mpeg"
                "ogv" -> "video/ogg"
                "jpg", "jpeg" -> "image/jpeg"
                "png" -> "image/png"
                "webp" -> "image/webp"
                "gif" -> "image/gif"
                "bmp" -> "image/bmp"
                "heic" -> "image/heic"
                "heif" -> "image/heif"
                "avif" -> "image/avif"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                "m4a" -> "audio/mp4"
                "aac" -> "audio/aac"
                "ogg", "oga" -> "audio/ogg"
                "opus" -> "audio/opus"
                "wav" -> "audio/x-wav"
                "wma" -> "audio/x-ms-wma"
                "zip" -> "application/zip"
                "7z" -> "application/x-7z-compressed"
                "rar" -> "application/vnd.rar"
                "apk" -> "application/vnd.android.package-archive"
                "pdf" -> "application/pdf"
                "txt", "log", "nfo" -> "text/plain"
                "srt" -> "application/x-subrip"
                "vtt" -> "text/vtt"
                "ass", "ssa" -> "text/x-ssa"
                else -> "application/octet-stream"
            }
        }

        /** Media3 subtitle MIME types. */
        fun subtitleMime(ext: String): String? = when (ext.lowercase(Locale.US).removePrefix(".")) {
            "srt" -> "application/x-subrip"
            "vtt" -> "text/vtt"
            "ass", "ssa" -> "text/x-ssa"
            "ttml", "dfxp" -> "application/ttml+xml"
            else -> null
        }
    }
}

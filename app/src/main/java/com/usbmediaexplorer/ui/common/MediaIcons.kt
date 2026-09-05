package com.usbmediaexplorer.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.PictureAsPdf
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.volume.VolumeKind
import com.usbmediaexplorer.ui.theme.AppTheme

/**
 * One icon system for the whole app (spec §26).
 *
 * Every kind has a single glyph and a single colour, so a video is recognisable before its name is
 * read, in the grid, in the list, in search results, in the details sheet and in the player alike.
 * Colours are semantic, never the primary colour: violet means "this app acts here", not "this is a
 * video".
 *
 * A typed icon only appears when a real preview could not be produced — never as a shortcut to
 * avoid decoding the file.
 */
fun iconFor(kind: MediaKind): ImageVector = when (kind) {
    MediaKind.DIRECTORY -> Icons.Outlined.Folder
    MediaKind.VIDEO -> Icons.Outlined.Movie
    MediaKind.IMAGE -> Icons.Outlined.Image
    MediaKind.AUDIO -> Icons.Outlined.AudioFile
    MediaKind.SUBTITLE -> Icons.Outlined.Subtitles
    MediaKind.ARCHIVE -> Icons.Outlined.FolderZip
    MediaKind.DOCUMENT -> Icons.Outlined.Description
    MediaKind.APK -> Icons.Outlined.Android
    MediaKind.OTHER -> Icons.Outlined.InsertDriveFile
}

/** Extension-aware glyph: a PDF, an APK and a `.7z` do not share one "document" icon. */
fun iconForNode(node: DocNode): ImageVector = when {
    node.isDirectory -> iconFor(MediaKind.DIRECTORY)
    else -> when (node.extension) {
        "pdf" -> Icons.Outlined.PictureAsPdf
        "apk", "apks", "xapk" -> Icons.Outlined.Android
        "zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "cab" -> Icons.Outlined.FolderZip
        "srt", "ass", "ssa", "vtt", "sub", "idx", "sup" -> Icons.Outlined.Subtitles
        "txt", "md", "nfo", "log", "ini", "json", "xml" -> Icons.Outlined.Article
        else -> iconFor(node.kind)
    }
}

/** Kind colour: video violet, photo teal, audio amber, archive orange, document blue-grey. */
@Composable
fun colorFor(kind: MediaKind): Color {
    val scheme = MaterialTheme.colorScheme
    val extended = AppTheme.extended
    return when (kind) {
        MediaKind.DIRECTORY -> scheme.primary
        MediaKind.VIDEO -> scheme.primary
        MediaKind.IMAGE -> scheme.tertiary
        MediaKind.AUDIO -> extended.warning
        MediaKind.SUBTITLE -> scheme.onSurfaceVariant
        MediaKind.ARCHIVE -> extended.sd
        MediaKind.DOCUMENT -> scheme.secondary
        MediaKind.APK -> extended.success
        MediaKind.OTHER -> scheme.onSurfaceVariant
    }
}

/** Soft container behind a kind icon, so a row of icons reads as a set, not as stickers. */
@Composable
fun containerFor(kind: MediaKind): Color = colorFor(kind).copy(alpha = if (AppTheme.isDark) 0.16f else 0.10f)

/** Storage-volume glyph. */
fun volumeIcon(kind: VolumeKind): ImageVector = when (kind) {
    VolumeKind.INTERNAL -> Icons.Outlined.Smartphone
    VolumeKind.SD_CARD -> Icons.Outlined.SdCard
    VolumeKind.USB -> Icons.Outlined.Usb
    VolumeKind.EXTERNAL -> Icons.Outlined.Storage
}

/** Storage-volume colour: USB teal, SD amber, internal violet. */
@Composable
fun volumeColor(kind: VolumeKind): Color {
    val extended = AppTheme.extended
    return when (kind) {
        VolumeKind.USB -> extended.usb
        VolumeKind.SD_CARD -> extended.sd
        VolumeKind.INTERNAL -> MaterialTheme.colorScheme.primary
        VolumeKind.EXTERNAL -> MaterialTheme.colorScheme.secondary
    }
}

/**
 * State glyphs (spec §26). Selection, favorite, loading, error and "offline" are the same icons
 * everywhere, so their meaning never has to be re-learned per screen.
 */
object MediaStates {
    val Selected: ImageVector = Icons.Outlined.TaskAlt
    val Favorite: ImageVector = Icons.Outlined.Favorite
    val FavoriteOff: ImageVector = Icons.Outlined.FavoriteBorder
    val Error: ImageVector = Icons.Outlined.Warning
    val Offline: ImageVector = Icons.Outlined.CloudOff
    val Locked: ImageVector = Icons.Outlined.Lock
}

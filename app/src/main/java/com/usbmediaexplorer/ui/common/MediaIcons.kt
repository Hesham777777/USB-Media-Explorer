package com.usbmediaexplorer.ui.common

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.SdCard
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.volume.VolumeKind
import com.usbmediaexplorer.ui.theme.AppTheme

/**
 * One icon system for the whole app (spec §26).
 *
 * File *types* use the coloured folded-corner document set (one colour and one glyph per kind,
 * extension aware): a video, a track or a spreadsheet is recognisable before its name is read, in
 * the grid, in the list, in search results, in the details sheet and in the context sheet alike.
 * They are vectors, so they stay crisp from a 20 dp row to a 160 dp tile in both themes.
 *
 * Everything that is an *action* or a *state* (select, favorite, error, locked…) stays a monochrome
 * glyph tinted by the theme, so colour keeps meaning "this is a kind of file", not "press me".
 *
 * A typed icon only appears when a real preview could not be produced — never as a shortcut to
 * avoid decoding the file.
 */
@DrawableRes
fun fileIconRes(kind: MediaKind): Int = when (kind) {
    MediaKind.DIRECTORY -> R.drawable.file_type_folder
    MediaKind.VIDEO -> R.drawable.file_type_video
    MediaKind.IMAGE -> R.drawable.file_type_image
    MediaKind.AUDIO -> R.drawable.file_type_audio
    MediaKind.SUBTITLE -> R.drawable.file_type_text
    MediaKind.ARCHIVE -> R.drawable.file_type_archive
    MediaKind.DOCUMENT -> R.drawable.file_type_text
    MediaKind.APK -> R.drawable.file_type_apk
    MediaKind.OTHER -> R.drawable.file_type_link
}

/** Extension aware: a `.pdf`, a workbook and a deck do not share one "document" icon. */
@DrawableRes
fun fileIconRes(node: DocNode): Int = when {
    node.isDirectory -> R.drawable.file_type_folder
    else -> when (node.extension) {
        "pdf" -> R.drawable.file_type_pdf
        "xls", "xlsx", "csv", "ods", "tsv" -> R.drawable.file_type_sheet
        "ppt", "pptx", "odp", "pps", "key" -> R.drawable.file_type_slides
        else -> fileIconRes(node.kind)
    }
}

/** The coloured document icon of a node, at whatever size the caller asks for. */
@Composable
fun FileTypeIcon(
    node: DocNode,
    modifier: Modifier = Modifier,
    contentDescription: String? = null,
) {
    Image(
        painter = painterResource(fileIconRes(node)),
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        modifier = modifier,
    )
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

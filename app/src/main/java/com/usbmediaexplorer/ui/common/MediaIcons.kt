package com.usbmediaexplorer.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Android
import androidx.compose.material.icons.outlined.AudioFile
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Subtitles
import androidx.compose.ui.graphics.vector.ImageVector
import com.usbmediaexplorer.data.doc.MediaKind

/**
 * Fallback iconography (spec §25). A typed icon only appears when a real preview could not be
 * produced — never as a shortcut to avoid decoding the file.
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

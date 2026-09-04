package com.usbmediaexplorer.ui.common

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.thumb.ThumbRequest
import com.usbmediaexplorer.ui.theme.Palette

/**
 * Builds the [ThumbRequest] a card should load. Returning null means "previews are disabled for
 * this kind", in which case the UI draws the typed icon instead.
 */
@Composable
fun rememberThumbRequest(
    node: DocNode,
    settings: com.usbmediaexplorer.data.settings.AppSettings,
    poster: Boolean = false,
): ThumbRequest? {
    val enabled = when (node.kind) {
        MediaKind.VIDEO -> settings.videoThumbnailsEnabled
        MediaKind.IMAGE -> settings.imageThumbnailsEnabled
        MediaKind.DIRECTORY -> settings.folderPreviewsEnabled
        else -> false
    }
    if (!enabled) return null
    val px = settings.thumbSize.px
    val posterTile = poster && node.kind == MediaKind.VIDEO
    return remember(
        node.key, settings.thumbSize, settings.thumbQuality, settings.frameStrategy,
        settings.folderPreviewsEnabled, settings.folderPreviewMaxChildren,
        settings.preferEmbeddedCover, settings.folderPreviewStyle, settings.posterCoversFirst,
        posterTile,
    ) {
        ThumbRequest(
            node = node,
            widthPx = px,
            heightPx = px,
            quality = settings.thumbQuality,
            strategy = settings.frameStrategy,
            folderPreview = node.isDirectory,
            folderPreviewCount = settings.folderPreviewMaxChildren,
            preferEmbeddedCover = settings.preferEmbeddedCover ||
                (posterTile && settings.posterCoversFirst),
            poster = posterTile,
            folderStyle = settings.folderPreviewStyle,
        )
    }
}

/**
 * The preview surface used by every card: real thumbnail while one can be produced, a shimmer
 * while it is being extracted from the drive, and a typed icon + extension badge when every
 * extraction mechanism failed.
 */
@Composable
fun MediaThumbnail(
    node: DocNode,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    poster: Boolean = false,
) {
    val settings = LocalSettings.current
    val request = rememberThumbRequest(node, settings, poster)
    val painter = rememberAsyncImagePainter(
        model = request,
        contentScale = contentScale,
    )
    val state = painter.state
    val failed = state is AsyncImagePainter.State.Error ||
        (request != null && state is AsyncImagePainter.State.Empty)

    Box(
        modifier = modifier.background(if (node.kind == MediaKind.VIDEO) Palette.VideoPlaceholder else MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        if (request != null && !failed) {
            Image(
                painter = painter,
                contentDescription = stringResource(R.string.cd_thumbnail),
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            FallbackIcon(node)
        }
        if (state is AsyncImagePainter.State.Loading) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.18f)),
            )
        }
    }
}

@Composable
private fun FallbackIcon(node: DocNode) {
    val tint = if (node.kind == MediaKind.VIDEO) Color.White.copy(alpha = 0.82f)
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(contentAlignment = Alignment.Center, modifier = Modifier.fillMaxSize()) {
        Icon(
            imageVector = iconFor(node.kind),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.fillMaxSize(0.34f),
        )
        if (node.extension.isNotEmpty() && !node.isDirectory) {
            Text(
                text = node.extension.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = tint,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

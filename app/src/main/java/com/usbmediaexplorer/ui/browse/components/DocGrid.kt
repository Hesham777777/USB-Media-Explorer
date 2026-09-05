package com.usbmediaexplorer.ui.browse.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.metadata.MediaMetadata
import com.usbmediaexplorer.data.settings.FOLDER_COVER_ASPECT
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.ui.browse.DocItem
import com.usbmediaexplorer.ui.common.LocalSettings
import com.usbmediaexplorer.ui.common.MediaStates
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.ui.common.PressableSurface
import com.usbmediaexplorer.ui.common.bidiLtr
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.util.Formatters

/**
 * The virtualised media browser (spec §6, §23, §24).
 *
 * One component renders every view mode — grid, list and compact list — and only the column count
 * and the row layout change between them. Lazy layouts plus the per-card [onVisible] callback are
 * what let a folder with thousands of videos scroll without preloading anything: metadata, covers
 * and frames are resolved for what is actually on screen and then cached, never recomputed while
 * the list recomposes.
 */
@Composable
fun DocItemsView(
    items: List<DocItem>,
    viewMode: ViewMode,
    selection: Set<String>,
    selecting: Boolean,
    onOpen: (DocItem) -> Unit,
    onLongPress: (DocItem) -> Unit,
    onToggleSelect: (DocItem) -> Unit,
    onMore: (DocItem) -> Unit,
    onVisible: (DocNode) -> Unit,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(AppSpacing.md),
) {
    val settings = LocalSettings.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (viewMode.isList) {
        val compact = viewMode == ViewMode.COMPACT_LIST
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(AppSpacing.listGap),
        ) {
            items(items, key = { it.node.key }) { item ->
                LaunchedEffect(item.node.key) { onVisible(item.node) }
                DocRow(
                    item = item,
                    selected = item.node.key in selection,
                    selecting = selecting,
                    compact = compact,
                    onOpen = { onOpen(item) },
                    onLongPress = { onLongPress(item) },
                    onToggleSelect = { onToggleSelect(item) },
                    onMore = { onMore(item) },
                    modifier = Modifier.animateItem(),
                )
            }
        }
        return
    }

    // Dynamic columns: orientation first, then the user's item-size preference (spec §24).
    val base = if (landscape) viewMode.columnsLandscape else viewMode.columnsPortrait
    val columns = (base + settings.itemScale.columnsDelta).coerceAtLeast(1)
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.md),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.md),
    ) {
        items(items, key = { it.node.key }) { item ->
            LaunchedEffect(item.node.key) { onVisible(item.node) }
            DocCard(
                item = item,
                viewMode = viewMode,
                selected = item.node.key in selection,
                selecting = selecting,
                onOpen = { onOpen(item) },
                onLongPress = { onLongPress(item) },
                onToggleSelect = { onToggleSelect(item) },
                modifier = Modifier.animateItem(),
            )
        }
    }
}

/* ---------------------------------------------------------------------------
 * Grid tile
 * ------------------------------------------------------------------------- */

@Composable
fun DocCard(
    item: DocItem,
    viewMode: ViewMode,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = item.node
    val settings = LocalSettings.current
    val compact = viewMode == ViewMode.GRID_SMALL

    // Folder Cover: a folder holding a poster inside is drawn as that poster, in poster
    // proportions, with the name underneath. It is still the folder — tapping opens it,
    // long-pressing selects it — only its artwork changed. Folders with no cover image fall back
    // to the ordinary folder icon on the same tile. No montage, no folder drawing, no cropping.
    val coverCard = node.isDirectory && settings.folderCoversEnabled && !viewMode.isList
    val mediaAspect = when {
        coverCard -> FOLDER_COVER_ASPECT
        viewMode.aspectRatio > 0f -> viewMode.aspectRatio
        else -> 1f
    }

    PressableSurface(
        onClick = { if (selecting) onToggleSelect() else onOpen() },
        onLongClick = onLongPress,
        selected = selected,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.lg),
    ) {
        Column {
            Box(Modifier.fillMaxWidth().aspectRatio(mediaAspect)) {
                MediaThumbnail(
                    node = node,
                    modifier = Modifier.fillMaxSize(),
                    // Fit for covers so a poster keeps its own ratio; Crop for files so a frame or
                    // a photo fills its tile.
                    contentScale = if (coverCard) ContentScale.Fit else ContentScale.Crop,
                )

                if (node.kind == MediaKind.VIDEO) {
                    val duration = item.metadata?.durationMs ?: 0L
                    if (duration > 0) {
                        OverlayChip(
                            text = Formatters.duration(duration),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                        )
                    }
                    val resume = item.resume
                    if (resume != null && resume.progress > 0.01f) {
                        LinearProgressIndicator(
                            progress = { resume.progress },
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .height(3.dp),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = Color.Black.copy(alpha = 0.4f),
                        )
                    }
                }

                if (node.isDirectory) {
                    val media = item.counts?.mediaTotal ?: 0
                    if (media > 0) {
                        OverlayChip(
                            text = media.toString(),
                            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                        )
                    }
                }

                if (item.favorite) {
                    Icon(
                        MediaStates.Favorite,
                        contentDescription = stringResource(R.string.action_favorite),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(6.dp)
                            .size(if (compact) 14.dp else 18.dp),
                    )
                }

                if (selecting || selected) {
                    SelectionDot(
                        selected = selected,
                        onClick = onToggleSelect,
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                    )
                }
            }

            Column(
                Modifier.padding(
                    horizontal = if (compact) 8.dp else 10.dp,
                    vertical = if (compact) 6.dp else 8.dp,
                ),
            ) {
                Text(
                    text = displayName(node, settings.showExtensions).bidiName(),
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Medium,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact && settings.showMediaInfo) {
                    val subtitle = itemSubtitle(item)
                    if (subtitle.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = subtitle.bidiLtr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * List row (and compact list row)
 * ------------------------------------------------------------------------- */

@Composable
fun DocRow(
    item: DocItem,
    selected: Boolean,
    selecting: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onToggleSelect: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val node = item.node
    val settings = LocalSettings.current
    val scale = settings.itemScale
    val rowHeight: Dp = if (compact) 44.dp else scale.rowHeight.dp
    val thumbSize: Dp = if (compact) 30.dp else scale.thumb.dp
    val thumbRadius = if (compact) AppRadius.xs else AppRadius.sm

    PressableSurface(
        onClick = { if (selecting) onToggleSelect() else onOpen() },
        onLongClick = onLongPress,
        selected = selected,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .height(rowHeight)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(thumbSize)
                    .clip(RoundedCornerShape(thumbRadius)),
            ) {
                MediaThumbnail(node = node, modifier = Modifier.fillMaxSize())
                val resume = item.resume
                if (resume != null && resume.progress > 0.01f) {
                    LinearProgressIndicator(
                        progress = { resume.progress },
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(2.dp),
                    )
                }
            }
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = displayName(node, settings.showExtensions).bidiName(),
                    style = if (compact) {
                        MaterialTheme.typography.bodyMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact && settings.showMediaInfo) {
                    val subtitle = itemSubtitle(item)
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle.bidiLtr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (item.favorite && !selecting) {
                Icon(
                    MediaStates.Favorite,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(15.dp),
                )
                Spacer(Modifier.width(6.dp))
            }
            if (selecting) {
                SelectionDot(selected = selected, onClick = onToggleSelect)
            } else if (!compact) {
                IconButton(onClick = onMore, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Small pieces
 * ------------------------------------------------------------------------- */

@Composable
private fun SelectionDot(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.Black.copy(alpha = 0.45f)
                },
            )
            // Tapping the dot toggles the item without opening it.
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = MediaStates.Selected,
                contentDescription = stringResource(R.string.cd_select),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/** A small dark pill over a thumbnail: duration, or a folder's media count. */
@Composable
private fun OverlayChip(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(AppRadius.xs),
        color = Color.Black.copy(alpha = 0.62f),
        modifier = modifier,
    ) {
        Text(
            text = text.bidiLtr(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * What the tile shows as its name: the extension is a setting (spec §17), and folders always keep
 * their full name because it is part of the identity the user searches for.
 */
private fun displayName(node: DocNode, showExtensions: Boolean): String = when {
    node.isDirectory -> node.name
    showExtensions -> node.name
    else -> node.nameWithoutExtension
}

/** "2.41 GB • 2:04:18 • 1080p" — the info line under every preview (spec §6). */
@Composable
fun itemSubtitle(item: DocItem): String {
    val node = item.node
    val settings = LocalSettings.current
    if (node.isDirectory) {
        val counts = item.counts ?: return ""
        val parts = ArrayList<String>(3)
        if (counts.videos > 0) parts += stringResource(R.string.videos_count, counts.videos)
        if (counts.images > 0) parts += stringResource(R.string.photos_count, counts.images)
        if (parts.isEmpty() && counts.folders > 0) {
            parts += stringResource(R.string.folders_count, counts.folders)
        }
        if (parts.isEmpty() && counts.total > 0) {
            parts += stringResource(R.string.items_count, counts.total)
        }
        return parts.joinToString(" • ")
    }
    val parts = ArrayList<String>(4)
    if (node.size >= 0) parts += Formatters.size(node.size)
    val metadata: MediaMetadata? = item.metadata
    when (node.kind) {
        MediaKind.VIDEO -> {
            metadata?.durationMs?.takeIf { it > 0 }?.let { parts += Formatters.duration(it) }
            metadata?.resolutionLabel?.takeIf { it.isNotEmpty() }?.let { parts += it }
            if (parts.size < 3 && settings.lazyMetadata && metadata == null) {
                // Nothing resolved yet: show the modified date so the line is never empty.
                parts += Formatters.date(node.lastModified)
            }
        }

        MediaKind.IMAGE -> {
            metadata?.resolutionLabel?.takeIf { it.isNotEmpty() }?.let { parts += it }
            parts += Formatters.date(node.lastModified)
        }

        MediaKind.AUDIO -> {
            metadata?.durationMs?.takeIf { it > 0 }?.let { parts += Formatters.duration(it) }
        }

        else -> parts += Formatters.date(node.lastModified)
    }
    return parts.filter { it.isNotBlank() }.joinToString(" • ")
}

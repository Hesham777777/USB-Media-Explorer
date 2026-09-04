package com.usbmediaexplorer.ui.browse.components

import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.metadata.MediaMetadata
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.ui.browse.DocItem
import com.usbmediaexplorer.ui.common.LocalSettings
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.util.Formatters

/**
 * The virtualised media browser (spec §2, §22).
 *
 * One component renders all five view modes; only the column count and the card layout change.
 * Lazy layouts plus per-card [onVisible] callbacks are what make a 5 000-video folder scroll
 * without preloading anything.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    contentPadding: PaddingValues = PaddingValues(12.dp),
) {
    val landscape = LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE

    if (viewMode == ViewMode.LIST) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(items, key = { it.node.key }) { item ->
                LaunchedEffect(item.node.key) { onVisible(item.node) }
                DocRow(
                    item = item,
                    selected = item.node.key in selection,
                    selecting = selecting,
                    onOpen = { onOpen(item) },
                    onLongPress = { onLongPress(item) },
                    onToggleSelect = { onToggleSelect(item) },
                    onMore = { onMore(item) },
                )
            }
        }
        return
    }

    val columns = if (landscape) viewMode.columnsLandscape else viewMode.columnsPortrait
    LazyVerticalGrid(
        columns = GridCells.Fixed(columns.coerceAtLeast(1)),
        modifier = modifier.fillMaxSize(),
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
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
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
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
    val compact = viewMode == ViewMode.GRID_SMALL
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onLongPress,
            ),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (selected) 2.dp else 0.dp),
    ) {
        Column {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (viewMode.aspectRatio > 0f) viewMode.aspectRatio else 1f),
            ) {
                MediaThumbnail(
                    node = node,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )

                if (node.kind == MediaKind.VIDEO) {
                    val duration = item.metadata?.durationMs ?: 0L
                    if (duration > 0) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.Black.copy(alpha = 0.62f),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp),
                        ) {
                            Text(
                                text = Formatters.duration(duration),
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
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
                        FolderBadge(
                            text = media.toString(),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(6.dp),
                        )
                    }
                }

                if (item.favorite) {
                    Icon(
                        Icons.Outlined.Favorite,
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
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(6.dp),
                    )
                }
            }

            Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Text(
                    text = if (node.isDirectory) node.name else node.nameWithoutExtension,
                    style = if (compact) {
                        MaterialTheme.typography.labelMedium
                    } else {
                        MaterialTheme.typography.titleSmall
                    },
                    fontWeight = FontWeight.Medium,
                    maxLines = if (compact) 1 else 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!compact) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = itemSubtitle(item),
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

@OptIn(ExperimentalFoundationApi::class)
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
) {
    val node = item.node
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(
                onClick = { if (selecting) onToggleSelect() else onOpen() },
                onLongClick = onLongPress,
            ),
        color = if (selected) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = RoundedCornerShape(14.dp),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp)),
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
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = itemSubtitle(item),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selecting) {
                SelectionDot(selected = selected, onClick = onToggleSelect)
            } else {
                IconButton(onClick = onMore) {
                    Icon(
                        Icons.Outlined.MoreVert,
                        contentDescription = stringResource(R.string.action_more),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectionDot(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary else Color.Black.copy(alpha = 0.45f),
            )
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Icon(
                imageVector = Icons.Outlined.Check,
                contentDescription = stringResource(R.string.cd_select),
                tint = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun FolderBadge(text: String, modifier: Modifier = Modifier) {
    if (text.isEmpty()) return
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = Color.Black.copy(alpha = 0.62f),
        modifier = modifier,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** "2.41 GB • 2:04:18 • 1080p" — the info line under every preview (spec §8). */
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
                // Nothing yet: show the modified date so the line is never empty.
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

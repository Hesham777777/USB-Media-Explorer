package com.usbmediaexplorer.ui.browse.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.isArchive
import com.usbmediaexplorer.data.settings.SortMode
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.ui.browse.DocItem

/** View mode picker (spec §2). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ViewModeSheet(
    current: ViewMode,
    onSelect: (ViewMode) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(stringResource(R.string.view_mode))
        Column(Modifier.padding(bottom = 24.dp)) {
            ViewMode.entries.forEach { mode ->
                SheetOption(
                    icon = iconForViewMode(mode),
                    label = stringResource(labelForViewMode(mode)),
                    selected = mode == current,
                    onClick = {
                        onSelect(mode)
                        onDismiss()
                    },
                )
            }
        }
    }
}

/** Sort picker with the "folders first" toggle (spec §13). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SortSheet(
    current: SortMode,
    foldersFirst: Boolean,
    onSelect: (SortMode) -> Unit,
    onToggleFoldersFirst: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(stringResource(R.string.sort_by))
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            SortMode.entries.forEach { mode ->
                SheetOption(
                    icon = null,
                    label = stringResource(labelForSort(mode)),
                    selected = mode == current,
                    onClick = {
                        onSelect(mode)
                        onDismiss()
                    },
                )
            }
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.sort_folders_first),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f),
                )
                Switch(checked = foldersFirst, onCheckedChange = onToggleFoldersFirst)
            }
        }
    }
}

/** Per-item action sheet opened from the row overflow button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemActionsSheet(
    items: List<DocItem>,
    canWrite: Boolean,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    onDetails: () -> Unit,
    onFavorite: () -> Unit,
    onZip: () -> Unit,
    onUnzip: (DocItem) -> Unit,
    onBulkRename: () -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    val single = items.singleOrNull()
    val node = single?.node
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetTitle(
            text = if (single == null) {
                stringResource(R.string.selection_count, items.size)
            } else {
                node?.name.orEmpty()
            },
        )
        if (single != null && node != null) {
            Text(
                text = node.displayPath,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 20.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            if (single != null) {
                SheetOption(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.action_open),
                    onClick = {
                        onOpen()
                        onDismiss()
                    },
                )
            }
            SheetOption(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.action_share),
                enabled = items.none { it.node.isDirectory },
                onClick = {
                    onShare()
                    onDismiss()
                },
            )
            if (canWrite) {
                SheetOption(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.action_copy),
                    onClick = {
                        onCopy()
                        onDismiss()
                    },
                )
                SheetOption(
                    icon = Icons.Outlined.ContentCut,
                    label = stringResource(R.string.action_cut),
                    onClick = {
                        onCut()
                        onDismiss()
                    },
                )
                SheetOption(
                    icon = Icons.Outlined.DriveFileRenameOutline,
                    label = if (items.size > 1) {
                        stringResource(R.string.action_bulk_rename)
                    } else {
                        stringResource(R.string.action_rename)
                    },
                    onClick = {
                        if (items.size > 1) onBulkRename() else onRename()
                        onDismiss()
                    },
                )
                SheetOption(
                    icon = Icons.Outlined.FolderZip,
                    label = stringResource(R.string.action_zip),
                    onClick = {
                        onZip()
                        onDismiss()
                    },
                )
                if (node != null && node.isArchive) {
                    SheetOption(
                        icon = Icons.Outlined.Unarchive,
                        label = stringResource(R.string.action_unzip),
                        onClick = {
                            onUnzip(items.first())
                            onDismiss()
                        },
                    )
                }
                SheetOption(
                    icon = Icons.Outlined.Delete,
                    label = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                )
            }
            SheetOption(
                icon = Icons.Outlined.Favorite,
                label = stringResource(R.string.action_favorite),
                onClick = {
                    onFavorite()
                    onDismiss()
                },
            )
            if (single != null) {
                SheetOption(
                    icon = Icons.Outlined.Info,
                    label = stringResource(R.string.action_details),
                    onClick = {
                        onDetails()
                        onDismiss()
                    },
                )
            }
        }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
    )
}

@Composable
private fun SheetOption(
    icon: ImageVector?,
    label: String,
    onClick: () -> Unit,
    selected: Boolean = false,
    enabled: Boolean = true,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        if (icon != null) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (enabled) tint else MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(16.dp))
        }
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = when {
                !enabled -> MaterialTheme.colorScheme.outline
                selected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.onSurface
            },
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Text(
                text = "✓",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleMedium,
            )
        }
    }
}

private fun labelForViewMode(mode: ViewMode): Int = when (mode) {
    ViewMode.LIST -> R.string.view_list
    ViewMode.GRID_SMALL -> R.string.view_grid_small
    ViewMode.GRID_MEDIUM -> R.string.view_grid_medium
    ViewMode.GRID_LARGE -> R.string.view_grid_large
    ViewMode.GRID_HUGE -> R.string.view_grid_huge
    ViewMode.POSTER -> R.string.view_poster
}

private fun iconForViewMode(mode: ViewMode): ImageVector = when (mode) {
    ViewMode.LIST -> Icons.Outlined.ViewList
    ViewMode.GRID_SMALL -> Icons.Outlined.ViewModule
    ViewMode.GRID_MEDIUM -> Icons.Outlined.Dashboard
    ViewMode.GRID_LARGE -> Icons.Outlined.GridView
    ViewMode.GRID_HUGE -> Icons.Outlined.GridOn
    ViewMode.POSTER -> Icons.Outlined.Movie
}

private fun labelForSort(mode: SortMode): Int = when (mode) {
    SortMode.NAME_ASC -> R.string.sort_name_asc
    SortMode.NAME_DESC -> R.string.sort_name_desc
    SortMode.NEWEST -> R.string.sort_newest
    SortMode.OLDEST -> R.string.sort_oldest
    SortMode.LARGEST -> R.string.sort_largest
    SortMode.SMALLEST -> R.string.sort_smallest
    SortMode.TYPE -> R.string.sort_type
    SortMode.DURATION -> R.string.sort_duration
}

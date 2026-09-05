package com.usbmediaexplorer.ui.browse.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.doc.isArchive
import com.usbmediaexplorer.data.doc.isVideo
import com.usbmediaexplorer.data.settings.SortMode
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.ui.browse.DocItem
import com.usbmediaexplorer.ui.common.SheetAction
import com.usbmediaexplorer.ui.common.SheetDivider
import com.usbmediaexplorer.ui.common.SheetGroupLabel
import com.usbmediaexplorer.ui.common.SheetHeader
import com.usbmediaexplorer.ui.common.bidiLtr
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.common.FileTypeIcon
import com.usbmediaexplorer.ui.theme.AppSpacing

/* ---------------------------------------------------------------------------
 * View modes (spec §6)
 * ------------------------------------------------------------------------- */

@Composable
fun ViewModeSheet(
    current: ViewMode,
    onSelect: (ViewMode) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetHeader(
            icon = Icons.Outlined.GridView,
            title = stringResource(R.string.view_mode),
            subtitle = stringResource(R.string.view_mode_hint),
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.xxl),
        ) {
            ViewMode.entries.forEach { mode ->
                SheetAction(
                    icon = iconForViewMode(mode),
                    label = stringResource(labelForViewMode(mode)),
                    onClick = {
                        onSelect(mode)
                        onDismiss()
                    },
                    trailing = {
                        if (mode == current) {
                            Text(
                                text = stringResource(R.string.label_current),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    },
                )
            }
        }
    }
}

private fun labelForViewMode(mode: ViewMode): Int = when (mode) {
    ViewMode.LIST -> R.string.view_list
    ViewMode.COMPACT_LIST -> R.string.view_compact_list
    ViewMode.GRID_SMALL -> R.string.view_grid_small
    ViewMode.GRID_MEDIUM -> R.string.view_grid_medium
    ViewMode.GRID_LARGE -> R.string.view_grid_large
    ViewMode.GRID_HUGE -> R.string.view_grid_huge
}

private fun iconForViewMode(mode: ViewMode): ImageVector = when (mode) {
    ViewMode.LIST -> Icons.Outlined.ViewList
    ViewMode.COMPACT_LIST -> Icons.Outlined.ViewAgenda
    ViewMode.GRID_SMALL -> Icons.Outlined.ViewModule
    ViewMode.GRID_MEDIUM -> Icons.Outlined.Dashboard
    ViewMode.GRID_LARGE -> Icons.Outlined.GridView
    ViewMode.GRID_HUGE -> Icons.Outlined.GridOn
}

/* ---------------------------------------------------------------------------
 * Sorting (spec §11): field, direction, folders-first — all persisted
 * ------------------------------------------------------------------------- */

/** The sort field, independent of direction. A pure UI concept over the persisted [SortMode]. */
enum class SortField {
    NAME, DATE, SIZE, TYPE, DURATION;

    /** Fields where "ascending" and "descending" mean something different. */
    val hasDirection: Boolean get() = this != TYPE && this != DURATION
}

fun SortMode.field(): SortField = when (this) {
    SortMode.NAME_ASC, SortMode.NAME_DESC -> SortField.NAME
    SortMode.NEWEST, SortMode.OLDEST -> SortField.DATE
    SortMode.LARGEST, SortMode.SMALLEST -> SortField.SIZE
    SortMode.TYPE -> SortField.TYPE
    SortMode.DURATION -> SortField.DURATION
}

fun SortMode.isAscending(): Boolean = when (this) {
    SortMode.NAME_ASC, SortMode.OLDEST, SortMode.SMALLEST -> true
    else -> false
}

fun SortField.toMode(ascending: Boolean): SortMode = when (this) {
    SortField.NAME -> if (ascending) SortMode.NAME_ASC else SortMode.NAME_DESC
    SortField.DATE -> if (ascending) SortMode.OLDEST else SortMode.NEWEST
    SortField.SIZE -> if (ascending) SortMode.SMALLEST else SortMode.LARGEST
    SortField.TYPE -> SortMode.TYPE
    SortField.DURATION -> SortMode.DURATION
}

@Composable
fun SortSheet(
    current: SortMode,
    foldersFirst: Boolean,
    onSelect: (SortMode) -> Unit,
    onToggleFoldersFirst: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(),
) {
    var field by remember { mutableStateOf(current.field()) }
    var ascending by remember { mutableStateOf(current.isAscending()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetHeader(
            icon = Icons.Outlined.Sort,
            title = stringResource(R.string.sort_by),
            subtitle = stringResource(labelForSort(current)),
        )
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.xxl),
        ) {
            SheetGroupLabel(stringResource(R.string.sort_group_field))
            LazyRow(
                contentPadding = PaddingValues(horizontal = AppSpacing.lg),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                items(SortField.entries.toList(), key = { it.name }) { entry ->
                    FilterChip(
                        selected = entry == field,
                        onClick = {
                            field = entry
                            onSelect(entry.toMode(ascending))
                        },
                        label = { Text(stringResource(labelForSortField(entry))) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                    )
                }
            }

            if (field.hasDirection) {
                Spacer(Modifier.height(AppSpacing.md))
                SheetGroupLabel(stringResource(R.string.sort_group_direction))
                Row(
                    Modifier.padding(horizontal = AppSpacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    listOf(true to R.string.sort_direction_asc, false to R.string.sort_direction_desc)
                        .forEach { (isAsc, labelRes) ->
                            FilterChip(
                                selected = ascending == isAsc,
                                onClick = {
                                    ascending = isAsc
                                    onSelect(field.toMode(isAsc))
                                },
                                label = { Text(stringResource(labelRes)) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                ),
                            )
                        }
                }
            }

            Spacer(Modifier.height(AppSpacing.md))
            SheetDivider()
            SheetAction(
                icon = Icons.Outlined.FolderOpen,
                label = stringResource(R.string.sort_folders_first),
                onClick = { onToggleFoldersFirst(!foldersFirst) },
                trailing = {
                    Switch(checked = foldersFirst, onCheckedChange = onToggleFoldersFirst)
                },
            )
        }
    }
}

private fun labelForSortField(field: SortField): Int = when (field) {
    SortField.NAME -> R.string.sort_field_name
    SortField.DATE -> R.string.sort_field_date
    SortField.SIZE -> R.string.sort_field_size
    SortField.TYPE -> R.string.sort_field_type
    SortField.DURATION -> R.string.sort_field_duration
}

/** One-line description of the active sort, for the action bar and the sheet header. */
@Composable
fun labelForSort(mode: SortMode): Int = when (mode) {
    SortMode.NAME_ASC -> R.string.sort_name_asc
    SortMode.NAME_DESC -> R.string.sort_name_desc
    SortMode.NEWEST -> R.string.sort_newest
    SortMode.OLDEST -> R.string.sort_oldest
    SortMode.LARGEST -> R.string.sort_largest
    SortMode.SMALLEST -> R.string.sort_smallest
    SortMode.TYPE -> R.string.sort_type
    SortMode.DURATION -> R.string.sort_duration
}

/* ---------------------------------------------------------------------------
 * Item / folder context sheet (spec §8, §9): grouped, never a flat wall
 * ------------------------------------------------------------------------- */

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
    favorite: Boolean = false,
    onOpenWith: ((DocItem) -> Unit)? = null,
    onShortcut: ((DocItem) -> Unit)? = null,
    onSelectContent: ((DocItem) -> Unit)? = null,
) {
    val single = items.singleOrNull()
    val node = single?.node
    val multiple = items.size > 1
    val anyDirectory = items.any { it.node.isDirectory }
    val onlyArchives = single != null && node != null && node.isArchive

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        SheetHeader(
            icon = if (node != null) null else Icons.Outlined.GridView,
            leading = if (node != null) {
                { FileTypeIcon(node, Modifier.size(40.dp)) }
            } else {
                null
            },
            title = if (multiple) {
                stringResource(R.string.selection_count, items.size)
            } else {
                node?.name.orEmpty().bidiName()
            },
            subtitle = when {
                multiple -> stringResource(R.string.sheet_subtitle_multiple)
                node != null -> typeLabel(node.kind) +
                    (node.displayPath.takeIf { it.isNotBlank() }?.let { " • $it" }.orEmpty())

                else -> null
            },
        )
        if (single != null && node != null) {
            Text(
                text = node.displayPath.bidiLtr(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = AppSpacing.lg),
            )
            Spacer(Modifier.height(AppSpacing.sm))
        }

        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = AppSpacing.xxl),
        ) {
            // ---- basic -----------------------------------------------------
            SheetGroupLabel(stringResource(R.string.sheet_group_basic))
            if (single != null) {
                SheetAction(
                    icon = if (node?.isVideo == true) Icons.Outlined.PlayArrow else Icons.Outlined.FolderOpen,
                    label = stringResource(
                        if (node?.isVideo == true) R.string.action_play else R.string.action_open,
                    ),
                    onClick = {
                        onOpen()
                        onDismiss()
                    },
                )
                // Folders: open with everything selected, so a whole folder can be copied or
                // deleted in two taps instead of a long-press and a select-all.
                if (node != null && node.isDirectory && onSelectContent != null) {
                    SheetAction(
                        icon = Icons.Outlined.SelectAll,
                        label = stringResource(R.string.action_select_content),
                        onClick = {
                            onSelectContent(single)
                            onDismiss()
                        },
                    )
                }
                if (node != null && !node.isDirectory && onOpenWith != null) {
                    SheetAction(
                        icon = Icons.Outlined.OpenInNew,
                        label = stringResource(R.string.action_open_with),
                        onClick = {
                            onOpenWith(single)
                            onDismiss()
                        },
                    )
                }
            }
            SheetAction(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.action_share),
                enabled = !anyDirectory,
                onClick = {
                    onShare()
                    onDismiss()
                },
            )

            // ---- manage ----------------------------------------------------
            // Always visible: hiding the whole group on a read-only volume made the sheet
            // look broken. Actions that genuinely cannot apply are shown greyed instead.
            SheetDivider()
            SheetGroupLabel(stringResource(R.string.sheet_group_manage))
            Row(Modifier.fillMaxWidth()) {
                SheetAction(
                    icon = Icons.Outlined.ContentCopy,
                    label = stringResource(R.string.action_copy),
                    onClick = {
                        onCopy()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
                SheetAction(
                    icon = Icons.Outlined.ContentCut,
                    label = stringResource(R.string.action_move),
                    enabled = canWrite,
                    onClick = {
                        onCut()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            SheetAction(
                icon = Icons.Outlined.DriveFileRenameOutline,
                label = stringResource(
                    if (multiple) R.string.action_bulk_rename else R.string.action_rename,
                ),
                enabled = canWrite,
                onClick = {
                    if (multiple) onBulkRename() else onRename()
                    onDismiss()
                },
            )
            SheetAction(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.action_delete),
                destructive = true,
                enabled = canWrite,
                onClick = {
                    onDelete()
                    onDismiss()
                },
            )

            // ---- organise --------------------------------------------------
            SheetDivider()
            SheetGroupLabel(stringResource(R.string.sheet_group_organise))
            SheetAction(
                icon = if (favorite) Icons.Outlined.Favorite else Icons.Outlined.FavoriteBorder,
                label = stringResource(
                    if (favorite && !multiple) R.string.action_unfavorite else R.string.action_favorite,
                ),
                onClick = {
                    onFavorite()
                    onDismiss()
                },
            )
            SheetAction(
                icon = Icons.Outlined.FolderZip,
                label = stringResource(R.string.action_zip),
                enabled = canWrite,
                onClick = {
                    onZip()
                    onDismiss()
                },
            )
            if (canWrite) {
                if (onlyArchives) {
                    SheetAction(
                        icon = Icons.Outlined.Unarchive,
                        label = stringResource(R.string.action_unzip),
                        onClick = {
                            onUnzip(items.first())
                            onDismiss()
                        },
                    )
                }
                if (single != null && onShortcut != null) {
                    SheetAction(
                        icon = Icons.Outlined.Link,
                        label = stringResource(R.string.action_shortcut),
                        onClick = {
                            onShortcut(single)
                            onDismiss()
                        },
                    )
                }
            }
            SheetAction(
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

/** Human type label for the sheet subtitle — never the raw mime string. */
@Composable
private fun typeLabel(kind: MediaKind): String = stringResource(
    when (kind) {
        MediaKind.DIRECTORY -> R.string.type_folder
        MediaKind.VIDEO -> R.string.type_video
        MediaKind.IMAGE -> R.string.type_image
        MediaKind.AUDIO -> R.string.type_audio
        MediaKind.SUBTITLE -> R.string.type_subtitle
        MediaKind.ARCHIVE -> R.string.type_archive
        MediaKind.DOCUMENT -> R.string.type_document
        MediaKind.APK -> R.string.type_apk
        MediaKind.OTHER -> R.string.type_other
    },
)

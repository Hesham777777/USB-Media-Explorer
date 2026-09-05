package com.usbmediaexplorer.ui.browse

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.isArchive
import com.usbmediaexplorer.ui.browse.components.BreadcrumbBar
import com.usbmediaexplorer.ui.browse.components.DetailsSheet
import com.usbmediaexplorer.ui.browse.components.DocItemsView
import com.usbmediaexplorer.ui.browse.components.ItemActionsSheet
import com.usbmediaexplorer.ui.browse.components.SortSheet
import com.usbmediaexplorer.ui.browse.components.ViewModeSheet
import com.usbmediaexplorer.ui.common.BulkRenameDialog
import com.usbmediaexplorer.ui.common.ConfirmDialog
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.TextInputDialog
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.Intents

/**
 * The Windows-Explorer-style browser (spec §2) with real previews (spec §3), multi-select
 * (spec §15) and the full file-operation set (spec §14, §16).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowseScreen(uri: String, snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel: BrowseViewModel = viewModel(
        key = "browse-$uri",
        factory = viewModelFactory { BrowseViewModel(container, uri) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    val details by viewModel.details.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Access can be missing or revoked (USB replugged, grant removed in system settings). The
    // error state offers the SAF picker right there instead of leaving the user stuck.
    val treeGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onTreeGranted(uri) }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    var showViewModes by remember { mutableStateOf(false) }
    var showSort by remember { mutableStateOf(false) }
    var actionsFor by remember { mutableStateOf<List<DocItem>?>(null) }
    var renameTarget by remember { mutableStateOf<DocNode?>(null) }
    var showNewFolder by remember { mutableStateOf(false) }
    var showNewFile by remember { mutableStateOf(false) }
    var showZipDialog by remember { mutableStateOf(false) }
    var showBulkRename by remember { mutableStateOf(false) }
    var deleteTargets by remember { mutableStateOf<List<DocNode>?>(null) }
    var showOverflow by remember { mutableStateOf(false) }

    LaunchedEffect(uri) { viewModel.load(uri) }
    LaunchedEffect(snackbarHostState) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    BackHandler(enabled = state.selecting) { viewModel.clearSelection() }

    fun openItem(item: DocItem) {
        when (val action = viewModel.onOpen(item)) {
            is OpenAction.Folder -> navigator.openFolder(action.uri)
            is OpenAction.Video -> navigator.playVideo(action.uri, action.folderUri)
            is OpenAction.Image -> navigator.viewImage(action.uri, action.folderUri)
            is OpenAction.External -> {
                val external = container.docRepository.externalUri(action.node)
                val opened = external != null && Intents.open(context, action.node, external)
                if (!opened) toast(context.getString(R.string.msg_shared_failed))
            }

            OpenAction.None -> Unit
        }
    }

    Scaffold(
        topBar = {
            if (state.selecting) {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { viewModel.clearSelection() }) {
                            Icon(Icons.Outlined.Close, contentDescription = stringResource(R.string.action_close))
                        }
                    },
                    title = {
                        Text(stringResource(R.string.selection_count, state.selection.size))
                    },
                    actions = {
                        IconButton(onClick = { viewModel.selectAll() }) {
                            Icon(Icons.Outlined.SelectAll, contentDescription = stringResource(R.string.action_select_all))
                        }
                        IconButton(onClick = { viewModel.invertSelection() }) {
                            Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.action_invert_selection))
                        }
                    },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = {
                            if (!navigator.back()) (context as? Activity)?.finish()
                        }) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = state.node?.name ?: stringResource(R.string.breadcrumb_root),
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = summaryLine(state),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    },
                    actions = {
                        state.node?.let { node ->
                            IconButton(onClick = { navigator.search(node.uri) }) {
                                Icon(Icons.Outlined.Search, contentDescription = stringResource(R.string.action_search))
                            }
                        }
                        IconButton(onClick = { showViewModes = true }) {
                            Icon(Icons.Outlined.GridView, contentDescription = stringResource(R.string.view_mode))
                        }
                        IconButton(onClick = { showSort = true }) {
                            Icon(Icons.Outlined.Sort, contentDescription = stringResource(R.string.action_sort))
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = showOverflow, onDismissRequest = { showOverflow = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_folder)) },
                                    leadingIcon = { Icon(Icons.Outlined.CreateNewFolder, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showNewFolder = true
                                    },
                                    enabled = state.canWrite,
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_file)) },
                                    leadingIcon = { Icon(Icons.Outlined.NoteAdd, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showNewFile = true
                                    },
                                    enabled = state.canWrite,
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                R.string.action_paste,
                                            ) + if (state.clipboardCount > 0) {
                                                " (${state.clipboardCount})"
                                            } else {
                                                ""
                                            },
                                        )
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.ContentPaste, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.paste()
                                    },
                                    enabled = state.canWrite && state.clipboardCount > 0,
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_refresh)) },
                                    leadingIcon = { Icon(Icons.Outlined.Refresh, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.reload()
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_details)) },
                                    leadingIcon = { Icon(Icons.Outlined.MoreVert, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        state.node?.let { viewModel.requestDetails(it) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_settings)) },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        navigator.settings()
                                    },
                                )
                            }
                        }
                    },
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                SelectionActionBar(
                    canWrite = state.canWrite,
                    onCopy = { viewModel.copySelection() },
                    onCut = { viewModel.cutSelection() },
                    onShare = {
                        val nodes = state.items.filter { it.node.key in state.selection }.map { it.node }
                        shareNodes(context, viewModel, nodes, ::toast)
                    },
                    onRename = {
                        val nodes = state.items.filter { it.node.key in state.selection }.map { it.node }
                        if (nodes.size == 1) renameTarget = nodes.first() else showBulkRename = true
                    },
                    onZip = { showZipDialog = true },
                    onDelete = {
                        deleteTargets = state.items.filter { it.node.key in state.selection }.map { it.node }
                    },
                )
            } else if (state.clipboardCount > 0) {
                ClipboardBar(
                    count = state.clipboardCount,
                    isCut = state.clipboardIsCut,
                    onPaste = { viewModel.paste() },
                    onClear = { container.fileOpsManager.clearClipboard() },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            if (state.breadcrumb.size > 1) {
                BreadcrumbBar(
                    trail = state.breadcrumb,
                    onNavigate = { node -> navigator.openFolder(node.uri) },
                )
            }
            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))

                    state.error != null -> MessageState(
                        title = state.error ?: "",
                        body = stringResource(R.string.hint_connect_usb),
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { viewModel.reload() },
                        secondaryActionLabel = stringResource(R.string.action_grant_access),
                        onSecondaryAction = { treeGrant.launch(null) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.items.isEmpty() -> MessageState(
                        title = stringResource(R.string.browse_empty_folder),
                        body = "",
                        actionLabel = null,
                        onAction = null,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    else -> DocItemsView(
                        items = state.items,
                        viewMode = state.viewMode,
                        selection = state.selection,
                        selecting = state.selecting,
                        onOpen = { item -> openItem(item) },
                        onLongPress = { item -> viewModel.startSelection(item.node) },
                        onToggleSelect = { item -> viewModel.toggleSelection(item.node) },
                        onMore = { item -> actionsFor = listOf(item) },
                        onVisible = { node -> viewModel.onItemVisible(node) },
                        contentPadding = PaddingValues(
                            start = 12.dp,
                            end = 12.dp,
                            top = 6.dp,
                            bottom = 24.dp,
                        ),
                    )
                }
            }
        }
    }

    // ---- sheets & dialogs -------------------------------------------------

    if (showViewModes) {
        ViewModeSheet(
            current = state.viewMode,
            onSelect = { viewModel.setViewMode(it) },
            onDismiss = { showViewModes = false },
        )
    }

    if (showSort) {
        SortSheet(
            current = state.sortMode,
            foldersFirst = state.foldersFirst,
            onSelect = { viewModel.setSortMode(it) },
            onToggleFoldersFirst = { viewModel.setFoldersFirst(it) },
            onDismiss = { showSort = false },
        )
    }

    actionsFor?.let { targets ->
        ItemActionsSheet(
            items = targets,
            canWrite = state.canWrite,
            onOpen = { targets.firstOrNull()?.let { openItem(it) } },
            onShare = { shareNodes(context, viewModel, targets.map { it.node }, ::toast) },
            onCopy = {
                container.fileOpsManager.copyToClipboard(
                    targets.map { it.node },
                    state.node,
                    cut = false,
                )
                viewModel.clearSelection()
            },
            onCut = {
                container.fileOpsManager.copyToClipboard(
                    targets.map { it.node },
                    state.node,
                    cut = true,
                )
                viewModel.clearSelection()
            },
            onRename = { renameTarget = targets.firstOrNull()?.node },
            onDelete = { deleteTargets = targets.map { it.node } },
            onDetails = { targets.firstOrNull()?.node?.let { viewModel.requestDetails(it) } },
            onFavorite = { targets.forEach { viewModel.toggleFavorite(it.node) } },
            onZip = { showZipDialog = true },
            onUnzip = { item -> if (item.node.isArchive) viewModel.unzip(item.node) },
            onBulkRename = { showBulkRename = true },
            onDismiss = {
                actionsFor = null
                viewModel.clearSelection()
            },
        )
    }

    details?.let { detailsState ->
        DetailsSheet(
            state = detailsState,
            onDismiss = { viewModel.clearDetails() },
            onOpen = {
                viewModel.clearDetails()
                detailsState.node.let { node ->
                    when {
                        node.isDirectory -> navigator.openFolder(node.uri)
                        else -> openItem(DocItem(node))
                    }
                }
            },
            onShare = {
                shareNodes(context, viewModel, listOf(detailsState.node), ::toast)
            },
            onRename = {
                renameTarget = detailsState.node
                viewModel.clearDetails()
            },
            onDelete = {
                deleteTargets = listOf(detailsState.node)
                viewModel.clearDetails()
            },
        )
    }

    renameTarget?.let { node ->
        TextInputDialog(
            title = stringResource(R.string.dialog_rename_title),
            label = stringResource(R.string.label_name),
            initial = node.name,
            confirmLabel = stringResource(R.string.action_rename),
            onConfirm = {
                viewModel.rename(node, it)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    if (showNewFolder) {
        TextInputDialog(
            title = stringResource(R.string.dialog_new_folder_title),
            label = stringResource(R.string.label_name),
            initial = "",
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = {
                viewModel.createFolder(it)
                showNewFolder = false
            },
            onDismiss = { showNewFolder = false },
        )
    }

    if (showNewFile) {
        TextInputDialog(
            title = stringResource(R.string.dialog_new_file_title),
            label = stringResource(R.string.label_name),
            initial = "",
            confirmLabel = stringResource(R.string.action_save),
            onConfirm = {
                viewModel.createFile(it)
                showNewFile = false
            },
            onDismiss = { showNewFile = false },
        )
    }

    if (showZipDialog) {
        TextInputDialog(
            title = stringResource(R.string.action_zip),
            label = stringResource(R.string.label_name),
            initial = (actionsFor?.firstOrNull()?.node?.nameWithoutExtension ?: "archive") + ".zip",
            confirmLabel = stringResource(R.string.action_zip),
            onConfirm = {
                if (state.selecting) viewModel.zipSelection(it) else {
                    val nodes = actionsFor?.map { item -> item.node }
                    val destination = state.node
                    if (nodes != null && destination != null) {
                        container.fileOpsManager.zip(nodes, destination, it)
                    }
                }
                showZipDialog = false
                actionsFor = null
                viewModel.clearSelection()
            },
            onDismiss = { showZipDialog = false },
        )
    }

    if (showBulkRename) {
        val nodes = state.items.filter { it.node.key in state.selection }.map { it.node }
            .ifEmpty { actionsFor?.map { it.node }.orEmpty() }
        BulkRenameDialog(
            items = nodes,
            onApply = { rules ->
                if (state.selecting) {
                    viewModel.bulkRename(rules)
                } else {
                    container.fileOpsManager.bulkRename(nodes, rules)
                }
                showBulkRename = false
                actionsFor = null
            },
            onDismiss = { showBulkRename = false },
        )
    }

    deleteTargets?.let { nodes ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title, nodes.size),
            body = stringResource(R.string.dialog_delete_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(nodes)
                deleteTargets = null
                actionsFor = null
            },
            onDismiss = { deleteTargets = null },
        )
    }
}

@Composable
private fun summaryLine(state: BrowseUiState): String {
    val counts = state.mediaCount
    val parts = ArrayList<String>(3)
    if (counts.videos > 0) parts += stringResource(R.string.videos_count, counts.videos)
    if (counts.images > 0) parts += stringResource(R.string.photos_count, counts.images)
    if (counts.folders > 0) parts += stringResource(R.string.folders_count, counts.folders)
    if (parts.isEmpty()) parts += stringResource(R.string.items_count, state.items.size)
    val volume = state.volume
    if (volume?.freeBytes != null && volume.totalBytes != null) {
        parts += stringResource(
            R.string.volume_free_of,
            Formatters.size(volume.freeBytes ?: 0),
            Formatters.size(volume.totalBytes ?: 0),
        )
    }
    return parts.filter { it.isNotBlank() }.joinToString(" • ")
}

@Composable
private fun SelectionActionBar(
    canWrite: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onZip: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 3.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 6.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(Icons.Outlined.ContentCopy, R.string.action_copy, onCopy, canWrite)
            BarAction(Icons.Outlined.ContentCut, R.string.action_cut, onCut, canWrite)
            BarAction(Icons.Outlined.Share, R.string.action_share, onShare, true)
            BarAction(Icons.Outlined.DriveFileRenameOutline, R.string.action_rename, onRename, canWrite)
            BarAction(Icons.Outlined.FolderZip, R.string.action_zip, onZip, canWrite)
            BarAction(Icons.Outlined.Delete, R.string.action_delete, onDelete, canWrite, destructive = true)
        }
    }
}

@Composable
private fun BarAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    labelRes: Int,
    onClick: () -> Unit,
    enabled: Boolean,
    destructive: Boolean = false,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = stringResource(labelRes),
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

@Composable
private fun ClipboardBar(
    count: Int,
    isCut: Boolean,
    onPaste: () -> Unit,
    onClear: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isCut) Icons.Outlined.ContentCut else Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(
                    if (isCut) R.string.msg_items_selected_for_move else R.string.msg_items_selected_for_copy,
                    count,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onClear) {
                Text(stringResource(R.string.action_cancel))
            }
            TextButton(onClick = onPaste) {
                Text(stringResource(R.string.action_paste))
            }
        }
    }
}

@Composable
private fun MessageState(
    title: String,
    body: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    modifier: Modifier = Modifier,
    secondaryActionLabel: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        if (body.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onAction) { Text(actionLabel) }
                if (secondaryActionLabel != null && onSecondaryAction != null) {
                    OutlinedButton(onClick = onSecondaryAction) { Text(secondaryActionLabel) }
                }
            }
        }
    }
}

private fun shareNodes(
    context: android.content.Context,
    viewModel: BrowseViewModel,
    nodes: List<DocNode>,
    toast: (String) -> Unit,
) {
    val uris = viewModel.shareUris(nodes)
    if (uris.isEmpty() || !Intents.share(context, uris, nodes.firstOrNull()?.mimeType)) {
        toast(context.getString(R.string.msg_shared_failed))
    }
}


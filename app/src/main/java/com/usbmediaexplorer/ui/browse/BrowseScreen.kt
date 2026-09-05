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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ContentCut
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.FolderZip
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.NoteAdd
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SelectAll
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Sort
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.outlined.ViewList
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaCount
import com.usbmediaexplorer.data.settings.ViewMode
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
import com.usbmediaexplorer.ui.common.SkeletonRows
import com.usbmediaexplorer.ui.common.SkeletonTiles
import com.usbmediaexplorer.ui.common.StateBlock
import com.usbmediaexplorer.ui.common.TextInputDialog
import com.usbmediaexplorer.ui.common.ToolAction
import com.usbmediaexplorer.ui.common.ToolSeparator
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.util.Formatters
import androidx.compose.ui.graphics.vector.ImageVector
import com.usbmediaexplorer.util.Intents
import com.usbmediaexplorer.util.Shortcuts
import kotlinx.coroutines.launch

/**
 * The browser (spec §5–§9, §11, §12): the screen the app is actually used on.
 *
 * Layout is two thin bars plus the content — a header that says where you are and how much is
 * here, and an action row that changes with the situation: filter chips while browsing, a search
 * field while searching, a contextual toolbar while selecting. Everything secondary lives in an
 * overflow menu rather than competing for space with the files.
 */
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
    val keyboard = LocalSoftwareKeyboardController.current

    // Access can be missing or revoked (USB replugged, grant removed in system settings). The
    // error state offers the SAF picker right there instead of leaving the user stuck.
    val treeGrant = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { granted -> viewModel.onTreeGranted(granted) }

    fun toast(message: String) {
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    var searching by remember(uri) { mutableStateOf(false) }
    var query by remember(uri) { mutableStateOf("") }
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
    var showSelectionOverflow by remember { mutableStateOf(false) }

    LaunchedEffect(uri) { viewModel.load(uri) }
    LaunchedEffect(query) { viewModel.setQuery(query) }
    LaunchedEffect(snackbarHostState) {
        viewModel.messages.collect { message -> snackbarHostState.showSnackbar(message) }
    }

    // Back means: leave selection first, then leave search, then leave the folder.
    BackHandler(enabled = state.selecting || searching) {
        when {
            state.selecting -> viewModel.clearSelection()
            searching -> {
                searching = false
                query = ""
                keyboard?.hide()
            }
        }
    }

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

    fun openWith(item: DocItem) {
        val external = container.docRepository.externalUri(item.node)
        val opened = external != null && Intents.openWith(context, item.node, external)
        if (!opened) toast(context.getString(R.string.msg_no_app_to_open))
    }

    val selectedItems = remember(state.items, state.selection) {
        state.items.filter { it.node.key in state.selection }
    }
    val selectedNodes = remember(selectedItems) { selectedItems.map { it.node } }

    Scaffold(
        topBar = {
            if (state.selecting) {
                SelectionTopBar(
                    count = state.selection.size,
                    sizeBytes = selectedNodes.sumOf { if (it.isDirectory) 0L else it.size.coerceAtLeast(0L) },
                    onClose = { viewModel.clearSelection() },
                    onSelectAll = { viewModel.selectAll() },
                    onInvert = { viewModel.invertSelection() },
                )
            } else {
                TopAppBar(
                    navigationIcon = {
                        IconButton(
                            onClick = {
                                if (!navigator.back()) (context as? Activity)?.finish()
                            },
                        ) {
                            Icon(
                                Icons.AutoMirrored.Outlined.ArrowBack,
                                contentDescription = stringResource(R.string.action_back),
                            )
                        }
                    },
                    title = {
                        Column {
                            Text(
                                text = (state.node?.name ?: stringResource(R.string.breadcrumb_root)).bidiName(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = summaryLine(state),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                searching = !searching
                                if (!searching) {
                                    query = ""
                                    keyboard?.hide()
                                }
                            },
                        ) {
                            Icon(
                                if (searching) Icons.Outlined.Close else Icons.Outlined.Search,
                                contentDescription = stringResource(R.string.action_search),
                                tint = if (searching) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                        IconButton(onClick = { showSort = true }) {
                            Icon(
                                Icons.Outlined.Sort,
                                contentDescription = stringResource(R.string.action_sort),
                            )
                        }
                        // One tap cycles the density; the overflow menu opens the full picker.
                        IconButton(onClick = { viewModel.cycleViewMode() }) {
                            Icon(
                                iconForCurrentViewMode(state.viewMode),
                                contentDescription = stringResource(R.string.view_mode),
                            )
                        }
                        Box {
                            IconButton(onClick = { showOverflow = true }) {
                                Icon(
                                    Icons.Outlined.MoreVert,
                                    contentDescription = stringResource(R.string.action_more),
                                )
                            }
                            DropdownMenu(
                                expanded = showOverflow,
                                onDismissRequest = { showOverflow = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_new_folder)) },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.CreateNewFolder, contentDescription = null)
                                    },
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
                                            stringResource(R.string.action_paste) +
                                                if (state.clipboardCount > 0) {
                                                    " (${state.clipboardCount})"
                                                } else {
                                                    ""
                                                },
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Outlined.ContentPaste, contentDescription = null)
                                    },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.paste()
                                    },
                                    enabled = state.canWrite && state.clipboardCount > 0,
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.view_mode)) },
                                    leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        showViewModes = true
                                    },
                                )
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (state.showHidden) {
                                                    R.string.action_hide_hidden
                                                } else {
                                                    R.string.action_show_hidden
                                                },
                                            ),
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(
                                            if (state.showHidden) {
                                                Icons.Outlined.VisibilityOff
                                            } else {
                                                Icons.Outlined.Visibility
                                            },
                                            contentDescription = null,
                                        )
                                    },
                                    onClick = {
                                        showOverflow = false
                                        scope.launch {
                                            container.settingsRepository.setShowHidden(!state.showHidden)
                                        }
                                    },
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
                                    text = { Text(stringResource(R.string.action_search_volume)) },
                                    leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        state.node?.let { navigator.search(it.uri) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_details)) },
                                    leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        state.node?.let { viewModel.requestDetails(it) }
                                    },
                                )
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_reset_folder_view)) },
                                    leadingIcon = { Icon(Icons.Outlined.RestartAlt, contentDescription = null) },
                                    onClick = {
                                        showOverflow = false
                                        viewModel.resetFolderPrefs()
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
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background,
                        scrolledContainerColor = MaterialTheme.colorScheme.background,
                    ),
                )
            }
        },
        bottomBar = {
            if (state.selecting) {
                SelectionActionBar(
                    count = state.selection.size,
                    canWrite = state.canWrite,
                    singleSelected = selectedItems.size == 1,
                    onCopy = { viewModel.copySelection() },
                    onCut = { viewModel.cutSelection() },
                    onShare = { shareNodes(context, viewModel, selectedNodes, ::toast) },
                    onRename = {
                        if (selectedNodes.size == 1) renameTarget = selectedNodes.first() else showBulkRename = true
                    },
                    onDelete = { deleteTargets = selectedNodes },
                    moreOpen = showSelectionOverflow,
                    onMoreOpen = { showSelectionOverflow = true },
                    onMoreDismiss = { showSelectionOverflow = false },
                    onZip = { showZipDialog = true },
                    onBulkRename = { showBulkRename = true },
                    onFavorite = { selectedItems.forEach { viewModel.toggleFavorite(it.node) } },
                    onDetails = { selectedItems.firstOrNull()?.node?.let { viewModel.requestDetails(it) } },
                    onActionsSheet = { actionsFor = selectedItems },
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
            if (state.breadcrumb.size > 1 && !state.selecting) {
                BreadcrumbBar(
                    trail = state.breadcrumb,
                    onNavigate = { node -> navigator.openFolder(node.uri) },
                )
            }

            // ---- the action row: search field or filter chips --------------
            if (!state.selecting) {
                if (searching) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = AppSpacing.md, vertical = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = {
                                Text(
                                    stringResource(R.string.search_in_folder),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            },
                            leadingIcon = {
                                Icon(
                                    Icons.Outlined.Search,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                            },
                            trailingIcon = {
                                if (query.isNotEmpty()) {
                                    IconButton(onClick = { query = "" }, modifier = Modifier.size(32.dp)) {
                                        Icon(
                                            Icons.Outlined.Close,
                                            contentDescription = stringResource(R.string.action_clear),
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                            textStyle = MaterialTheme.typography.bodyMedium,
                            shape = RoundedCornerShape(AppRadius.pill),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = MaterialTheme.colorScheme.primary,
                                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        )
                        Spacer(Modifier.width(AppSpacing.sm))
                        Text(
                            text = stringResource(R.string.search_results_count, state.items.size),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (state.items.isNotEmpty() || state.isFiltered) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = AppSpacing.md, end = AppSpacing.xs, top = AppSpacing.xs),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        FilterChips(
                            current = state.kindFilter,
                            counts = state.mediaCount,
                            total = state.totalCount,
                            onSelect = { viewModel.setKindFilter(it) },
                            modifier = Modifier.weight(1f),
                        )
                        ToolAction(
                            icon = Icons.Outlined.SelectAll,
                            label = stringResource(R.string.action_select),
                            onClick = {
                                state.items.firstOrNull()?.let { viewModel.startSelection(it.node) }
                            },
                            enabled = state.items.isNotEmpty(),
                        )
                        ToolAction(
                            icon = Icons.Outlined.CreateNewFolder,
                            label = stringResource(R.string.action_new_folder),
                            onClick = { showNewFolder = true },
                            enabled = state.canWrite,
                        )
                    }
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    state.loading -> if (state.viewMode.isList) {
                        SkeletonRows(count = 9, modifier = Modifier.align(Alignment.TopCenter))
                    } else {
                        SkeletonTiles(
                            columns = if (state.viewMode.columnsPortrait >= 3) 3 else 2,
                            rows = 3,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }

                    state.error != null -> StateBlock(
                        icon = Icons.Outlined.Info,
                        title = state.error ?: "",
                        body = stringResource(R.string.hint_connect_usb),
                        tint = MaterialTheme.colorScheme.error,
                        container = MaterialTheme.colorScheme.errorContainer,
                        actionLabel = stringResource(R.string.action_retry),
                        onAction = { viewModel.reload() },
                        secondaryLabel = stringResource(R.string.action_grant_access),
                        onSecondaryAction = { treeGrant.launch(null) },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.items.isEmpty() && state.isFiltered -> StateBlock(
                        icon = Icons.Outlined.Search,
                        title = stringResource(R.string.search_no_results),
                        body = stringResource(R.string.browse_no_match_body),
                        actionLabel = stringResource(R.string.action_clear_filters),
                        onAction = {
                            viewModel.clearFilters()
                            query = ""
                            searching = false
                        },
                        modifier = Modifier.align(Alignment.Center),
                    )

                    state.items.isEmpty() -> StateBlock(
                        icon = Icons.Outlined.CreateNewFolder,
                        title = stringResource(R.string.browse_empty_folder),
                        body = stringResource(R.string.browse_empty_body),
                        actionLabel = if (state.canWrite) {
                            stringResource(R.string.action_new_folder)
                        } else {
                            null
                        },
                        onAction = if (state.canWrite) {
                            { showNewFolder = true }
                        } else {
                            null
                        },
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
                            start = AppSpacing.md,
                            end = AppSpacing.md,
                            top = AppSpacing.sm,
                            bottom = AppSpacing.xxl,
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
            favorite = targets.firstOrNull()?.favorite == true,
            onOpen = { targets.firstOrNull()?.let { openItem(it) } },
            onOpenWith = { item -> openWith(item) },
            onShortcut = { item ->
                if (!Shortcuts.pin(context, item.node)) {
                    toast(context.getString(R.string.msg_shortcut_unsupported))
                }
            },
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
            onShare = { shareNodes(context, viewModel, listOf(detailsState.node), ::toast) },
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
            initial = (actionsFor?.firstOrNull()?.node?.nameWithoutExtension
                ?: selectedNodes.firstOrNull()?.nameWithoutExtension
                ?: "archive") + ".zip",
            confirmLabel = stringResource(R.string.action_zip),
            onConfirm = { name ->
                if (state.selecting) {
                    viewModel.zipSelection(name)
                } else {
                    val nodes = actionsFor?.map { item -> item.node }
                    val destination = state.node
                    if (nodes != null && destination != null) {
                        container.fileOpsManager.zip(nodes, destination, name)
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
        val nodes = selectedNodes.ifEmpty { actionsFor?.map { it.node }.orEmpty() }
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

/* ---------------------------------------------------------------------------
 * Header pieces
 * ------------------------------------------------------------------------- */

/** Contextual header while selecting: count, size, select-all, invert, close (spec §7). */
@Composable
private fun SelectionTopBar(
    count: Int,
    sizeBytes: Long,
    onClose: () -> Unit,
    onSelectAll: () -> Unit,
    onInvert: () -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Outlined.Close,
                    contentDescription = stringResource(R.string.action_close),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        title = {
            Column {
                Text(
                    text = stringResource(R.string.selection_count, count),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                )
                if (sizeBytes > 0) {
                    Text(
                        text = Formatters.size(sizeBytes),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
        },
        actions = {
            IconButton(onClick = onSelectAll) {
                Icon(
                    Icons.Outlined.SelectAll,
                    contentDescription = stringResource(R.string.action_select_all),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            IconButton(onClick = onInvert) {
                Icon(
                    Icons.Outlined.SwapHoriz,
                    contentDescription = stringResource(R.string.action_invert_selection),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            scrolledContainerColor = MaterialTheme.colorScheme.primaryContainer,
        ),
    )
}

/** The header icon reflects the current density, so cycling is never a blind guess. */
@Composable
private fun iconForCurrentViewMode(mode: ViewMode): ImageVector = when {
    mode == ViewMode.LIST -> Icons.Outlined.ViewList
    mode == ViewMode.COMPACT_LIST -> Icons.Outlined.ViewAgenda
    mode.columnsPortrait >= 3 -> Icons.Outlined.ViewModule
    else -> Icons.Outlined.GridView
}

/** Kind filters for the current folder — counts come from the tally already in memory. */
@Composable
private fun FilterChips(
    current: KindFilter,
    counts: MediaCount,
    total: Int,
    onSelect: (KindFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = remember(total, counts) {
        listOf(
            KindFilter.ALL to total,
            KindFilter.FOLDER to counts.folders,
            KindFilter.VIDEO to counts.videos,
            KindFilter.IMAGE to counts.images,
            KindFilter.AUDIO to counts.audios,
            KindFilter.FILE to (total - counts.folders).coerceAtLeast(0),
            KindFilter.ARCHIVE to -1,
        )
    }
    LazyRow(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        contentPadding = PaddingValues(vertical = AppSpacing.xs),
    ) {
        items(options.size) { index ->
            val (filter, count) = options[index]
            val label = stringResource(labelForFilter(filter))
            FilterChip(
                selected = filter == current,
                onClick = { onSelect(filter) },
                label = {
                    Text(
                        text = if (count > 0) "$label  $count" else label,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
                ),
                border = FilterChipDefaults.filterChipBorder(
                    enabled = true,
                    selected = filter == current,
                    borderColor = MaterialTheme.colorScheme.outlineVariant,
                    selectedBorderColor = MaterialTheme.colorScheme.primary,
                ),
            )
        }
    }
}

@Composable
private fun labelForFilter(filter: KindFilter): Int = when (filter) {
    KindFilter.ALL -> R.string.filter_all
    KindFilter.VIDEO -> R.string.filter_videos
    KindFilter.IMAGE -> R.string.filter_photos
    KindFilter.AUDIO -> R.string.filter_music
    KindFilter.FOLDER -> R.string.filter_folders
    KindFilter.ARCHIVE -> R.string.filter_archives
    KindFilter.FILE -> R.string.filter_files
}

/* ---------------------------------------------------------------------------
 * Bars
 * ------------------------------------------------------------------------- */

/** The operations that apply to the current selection (spec §7). */
@Composable
private fun SelectionActionBar(
    count: Int,
    canWrite: Boolean,
    singleSelected: Boolean,
    onCopy: () -> Unit,
    onCut: () -> Unit,
    onShare: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    moreOpen: Boolean,
    onMoreOpen: () -> Unit,
    onMoreDismiss: () -> Unit,
    onZip: () -> Unit,
    onBulkRename: () -> Unit,
    onFavorite: () -> Unit,
    onDetails: () -> Unit,
    onActionsSheet: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, tonalElevation = 2.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.xs, vertical = AppSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolAction(
                icon = Icons.Outlined.ContentCopy,
                label = stringResource(R.string.action_copy),
                onClick = onCopy,
                enabled = canWrite,
                modifier = Modifier.weight(1f),
            )
            ToolAction(
                icon = Icons.Outlined.ContentCut,
                label = stringResource(R.string.action_move),
                onClick = onCut,
                enabled = canWrite,
                modifier = Modifier.weight(1f),
            )
            ToolAction(
                icon = Icons.Outlined.Share,
                label = stringResource(R.string.action_share),
                onClick = onShare,
                modifier = Modifier.weight(1f),
            )
            ToolAction(
                icon = Icons.Outlined.DriveFileRenameOutline,
                label = stringResource(
                    if (singleSelected) R.string.action_rename else R.string.action_bulk_rename,
                ),
                onClick = onRename,
                enabled = canWrite,
                modifier = Modifier.weight(1f),
            )
            ToolAction(
                icon = Icons.Outlined.Delete,
                label = stringResource(R.string.action_delete),
                onClick = onDelete,
                enabled = canWrite,
                destructive = true,
                modifier = Modifier.weight(1f),
            )
            ToolSeparator()
            Box(Modifier.weight(1f)) {
                ToolAction(
                    icon = Icons.Outlined.MoreVert,
                    label = stringResource(R.string.action_more),
                    onClick = onMoreOpen,
                    modifier = Modifier.fillMaxWidth(),
                )
                DropdownMenu(expanded = moreOpen, onDismissRequest = onMoreDismiss) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_favorite)) },
                        leadingIcon = { Icon(Icons.Outlined.FavoriteBorder, contentDescription = null) },
                        onClick = {
                            onMoreDismiss()
                            onFavorite()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_zip)) },
                        leadingIcon = { Icon(Icons.Outlined.FolderZip, contentDescription = null) },
                        onClick = {
                            onMoreDismiss()
                            onZip()
                        },
                        enabled = canWrite,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_bulk_rename)) },
                        leadingIcon = {
                            Icon(Icons.Outlined.DriveFileRenameOutline, contentDescription = null)
                        },
                        onClick = {
                            onMoreDismiss()
                            onBulkRename()
                        },
                        enabled = canWrite && count > 1,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_details)) },
                        leadingIcon = { Icon(Icons.Outlined.Info, contentDescription = null) },
                        onClick = {
                            onMoreDismiss()
                            onDetails()
                        },
                        enabled = singleSelected,
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_all_actions)) },
                        leadingIcon = { Icon(Icons.Outlined.GridView, contentDescription = null) },
                        onClick = {
                            onMoreDismiss()
                            onActionsSheet()
                        },
                    )
                }
            }
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
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, tonalElevation = 1.dp) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isCut) Icons.Outlined.ContentCut else Icons.Outlined.ContentCopy,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(AppSpacing.md))
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

/* ---------------------------------------------------------------------------
 * Helpers
 * ------------------------------------------------------------------------- */

/** "340 عناصر • 12 فيديو • 4.2 GB • 128 GB free" — the whole folder in one line (spec §5). */
@Composable
private fun summaryLine(state: BrowseUiState): String {
    val counts = state.mediaCount
    val parts = ArrayList<String>(4)
    if (state.isFiltered) {
        parts += stringResource(R.string.browse_filtered_count, state.items.size, state.totalCount)
    } else {
        parts += stringResource(R.string.items_count, state.totalCount)
        if (counts.videos > 0) parts += stringResource(R.string.videos_count, counts.videos)
        if (counts.images > 0) parts += stringResource(R.string.photos_count, counts.images)
        if (parts.size < 3 && counts.folders > 0) {
            parts += stringResource(R.string.folders_count, counts.folders)
        }
    }
    if (state.totalSize > 0) parts += Formatters.size(state.totalSize)
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

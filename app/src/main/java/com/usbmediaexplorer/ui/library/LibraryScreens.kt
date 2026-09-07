package com.usbmediaexplorer.ui.library

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.settings.FOLDER_COVER_ASPECT
import com.usbmediaexplorer.data.store.FavoriteEntry
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.di.AppContainer
import com.usbmediaexplorer.ui.common.ConfirmDialog
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.ui.common.PressableSurface
import com.usbmediaexplorer.ui.common.SkeletonRows
import com.usbmediaexplorer.ui.common.StateBlock
import com.usbmediaexplorer.ui.common.bidiLtr
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.ui.theme.AppTheme
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.Intents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/* ===========================================================================
 * Favorites (spec §19)
 * ========================================================================= */

data class FavoritesState(
    val entries: List<FavoriteEntry> = emptyList(),
    val nodes: List<DocNode> = emptyList(),
    val missing: List<FavoriteEntry> = emptyList(),
    val loading: Boolean = true,
)

/**
 * Favorites. Entries pointing at an unplugged drive are reported in their own section instead of
 * disappearing silently — the user still knows the file exists, and can drop the dead entry.
 */
class FavoritesViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(FavoritesState())
    val state: StateFlow<FavoritesState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.favoritesStore.favorites.collect { entries ->
                _state.value = _state.value.copy(entries = entries, loading = true)
                resolve(entries)
            }
        }
    }

    private suspend fun resolve(entries: List<FavoriteEntry>) {
        val resolved = withContext(Dispatchers.IO) {
            entries.mapNotNull { entry ->
                runCatching { Uri.parse(entry.uri) }.getOrNull()?.let { uri ->
                    container.docRepository.node(uri)
                }
            }
        }
        val available = resolved.map { it.uri.toString() }.toHashSet()
        _state.value = _state.value.copy(
            nodes = resolved,
            missing = entries.filterNot { available.contains(it.uri) },
            loading = false,
        )
    }

    fun remove(entry: FavoriteEntry) {
        viewModelScope.launch { container.favoritesStore.remove(entry.key) }
    }

    fun removeMissing() {
        val keys = _state.value.missing.map { it.key }.toSet()
        viewModelScope.launch { container.favoritesStore.removeAll(keys) }
    }

    /** Share a favourite without leaving the screen; returns false when nothing can handle it. */
    fun shareUri(node: DocNode): Uri? = container.docRepository.externalUri(node)
}

@Composable
fun FavoritesScreen(snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val viewModel: FavoritesViewModel = viewModel(
        factory = viewModelFactory { FavoritesViewModel(container) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = stringResource(R.string.section_favorites),
                subtitle = if (state.nodes.isEmpty()) {
                    null
                } else {
                    stringResource(R.string.items_count, state.nodes.size)
                },
                onBack = { navigator.back() },
                actions = {
                    if (state.missing.isNotEmpty()) {
                        IconButton(onClick = { viewModel.removeMissing() }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.action_remove_unavailable),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            when {
                state.loading -> SkeletonRows(count = 6)

                state.nodes.isEmpty() && state.missing.isEmpty() -> StateBlock(
                    icon = Icons.Outlined.Favorite,
                    title = stringResource(R.string.empty_favorites_title),
                    body = stringResource(R.string.empty_favorites_body),
                    tint = MaterialTheme.colorScheme.primary,
                    container = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.align(Alignment.Center),
                )

                else -> LazyColumn(
                    contentPadding = PaddingValues(
                        start = AppSpacing.lg,
                        end = AppSpacing.lg,
                        top = AppSpacing.sm,
                        bottom = AppSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    items(state.nodes, key = { it.key }) { node ->
                        val entry = state.entries.firstOrNull { it.uri == node.uri.toString() }
                        MediaRow(
                            node = node,
                            subtitle = node.displayPath,
                            onClick = {
                                when (node.kind) {
                                    MediaKind.DIRECTORY -> navigator.openFolder(node.uri)
                                    MediaKind.VIDEO -> navigator.playVideo(node.uri, null)
                                    MediaKind.IMAGE -> navigator.viewImage(node.uri, null)
                                    else -> navigator.openFolder(node.uri)
                                }
                            },
                            trailing = {
                                if (!node.isDirectory) {
                                    IconButton(
                                        onClick = {
                                            val uri = viewModel.shareUri(node)
                                            val shared = uri != null &&
                                                Intents.share(context, listOf(uri), node.mimeType)
                                            if (!shared) {
                                                scope.launch {
                                                    snackbarHostState.showSnackbar(
                                                        context.getString(R.string.msg_shared_failed),
                                                    )
                                                }
                                            }
                                        },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Share,
                                            contentDescription = stringResource(R.string.action_share),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                                if (entry != null) {
                                    IconButton(
                                        onClick = { viewModel.remove(entry) },
                                        modifier = Modifier.size(36.dp),
                                    ) {
                                        Icon(
                                            Icons.Outlined.Favorite,
                                            contentDescription = stringResource(R.string.action_unfavorite),
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    }
                                }
                            },
                            modifier = Modifier.animateItem(),
                        )
                    }

                    if (state.missing.isNotEmpty()) {
                        item(key = "missing-header") {
                            Row(
                                Modifier.padding(top = AppSpacing.lg, bottom = AppSpacing.xs),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    Icons.Outlined.WarningAmber,
                                    contentDescription = null,
                                    tint = AppTheme.extended.warning,
                                    modifier = Modifier.size(16.dp),
                                )
                                Spacer(Modifier.width(AppSpacing.sm))
                                Text(
                                    text = stringResource(R.string.favorites_unavailable_title),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        items(state.missing, key = { "missing-${it.key}" }) { entry ->
                            MissingRow(entry = entry, onRemove = { viewModel.remove(entry) })
                        }
                    }
                }
            }
        }
    }
}

/* ===========================================================================
 * Recent (spec §20): watched videos and opened folders are never mixed
 * ========================================================================= */

data class RecentState(
    val videos: List<RecentEntry> = emptyList(),
    val folders: List<RecentEntry> = emptyList(),
    val tab: Int = 0,
)

class RecentViewModel(private val container: AppContainer) : ViewModel() {

    private val _state = MutableStateFlow(RecentState())
    val state: StateFlow<RecentState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            container.recentStore.recentVideos.collect { list ->
                _state.value = _state.value.copy(videos = list)
            }
        }
        viewModelScope.launch {
            container.recentStore.recentFolders.collect { list ->
                _state.value = _state.value.copy(folders = list)
            }
        }
    }

    fun setTab(tab: Int) {
        _state.value = _state.value.copy(tab = tab)
    }

    fun remove(entry: RecentEntry) {
        viewModelScope.launch {
            if (entry.isDirectory) container.recentStore.removeFolder(entry.key)
            else container.recentStore.removeVideo(entry.key)
        }
    }

    fun clearAll() {
        viewModelScope.launch { container.recentStore.clearAll() }
    }
}

@Composable
fun RecentScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val viewModel: RecentViewModel = viewModel(
        factory = viewModelFactory { RecentViewModel(container) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmClear by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            LibraryTopBar(
                title = stringResource(R.string.section_recent),
                subtitle = if (state.tab == 0) {
                    stringResource(R.string.items_count, state.videos.size)
                } else {
                    stringResource(R.string.items_count, state.folders.size)
                },
                onBack = { navigator.back() },
                actions = {
                    if (state.videos.isNotEmpty() || state.folders.isNotEmpty()) {
                        IconButton(onClick = { confirmClear = true }) {
                            Icon(
                                Icons.Outlined.DeleteSweep,
                                contentDescription = stringResource(R.string.action_clear_all),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            // Two histories, two tabs: a video thumbnail and a folder cover never share a list.
            Row(
                Modifier.padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
            ) {
                TabChip(
                    label = stringResource(R.string.recent_videos),
                    count = state.videos.size,
                    selected = state.tab == 0,
                    onClick = { viewModel.setTab(0) },
                )
                TabChip(
                    label = stringResource(R.string.recent_folders),
                    count = state.folders.size,
                    selected = state.tab == 1,
                    onClick = { viewModel.setTab(1) },
                )
            }

            val entries = if (state.tab == 0) state.videos else state.folders
            if (entries.isEmpty()) {
                StateBlock(
                    icon = if (state.tab == 0) Icons.Outlined.History else Icons.Outlined.Folder,
                    title = stringResource(
                        if (state.tab == 0) {
                            R.string.empty_recent_videos
                        } else {
                            R.string.empty_recent_folders
                        },
                    ),
                    body = stringResource(R.string.empty_recent_body),
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = AppSpacing.lg,
                        end = AppSpacing.lg,
                        top = AppSpacing.xs,
                        bottom = AppSpacing.xxl,
                    ),
                    verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        RecentRow(
                            entry = entry,
                            onClick = {
                                val uri = runCatching { Uri.parse(entry.uri) }.getOrNull()
                                    ?: return@RecentRow
                                if (entry.isDirectory) {
                                    navigator.openFolder(uri)
                                } else {
                                    navigator.playVideo(uri, null)
                                }
                            },
                            onRemove = { viewModel.remove(entry) },
                            modifier = Modifier.animateItem(),
                        )
                    }
                }
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = stringResource(R.string.dialog_clear_recents_title),
            body = stringResource(R.string.dialog_clear_data_body),
            confirmLabel = stringResource(R.string.action_clear_all),
            destructive = true,
            onConfirm = {
                viewModel.clearAll()
                confirmClear = false
            },
            onDismiss = { confirmClear = false },
        )
    }
}

/* ===========================================================================
 * Shared rows
 * ========================================================================= */

@Composable
private fun LibraryTopBar(
    title: String,
    subtitle: String?,
    onBack: () -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = stringResource(R.string.action_back),
                )
            }
        },
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        },
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            scrolledContainerColor = MaterialTheme.colorScheme.background,
        ),
    )
}

@Composable
private fun TabChip(label: String, count: Int, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text = if (count > 0) "$label  $count" else label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
            )
        },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun RecentRow(
    entry: RecentEntry,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = remember(entry.key) {
        DocNode(
            uri = runCatching { Uri.parse(entry.uri) }.getOrDefault(Uri.EMPTY),
            name = entry.name,
            isDirectory = entry.isDirectory,
            size = entry.size,
            lastModified = entry.lastOpenedAt,
            mimeType = null,
            volumeId = entry.volumeId,
            displayPath = entry.displayPath,
        )
    }
    MediaRow(
        node = node,
        subtitle = listOf(entry.displayPath, Formatters.dateTime(entry.lastOpenedAt))
            .filter { it.isNotBlank() }
            .joinToString(" • "),
        onClick = onClick,
        modifier = modifier,
        trailing = {
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
            }
        },
    )
}

@Composable
private fun MissingRow(entry: FavoriteEntry, onRemove: () -> Unit) {
    val warning = AppTheme.extended.warning
    PressableSurface(
        onClick = onRemove,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.WarningAmber,
                contentDescription = null,
                tint = warning,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name.bidiName(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.displayPath.bidiLtr(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Outlined.Delete,
                    contentDescription = stringResource(R.string.action_delete),
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * One library row. Folders are drawn with their Folder Cover in poster proportions, videos and
 * photos with their real preview — the same thumbnails the browser uses, from the same cache.
 */
@Composable
private fun MediaRow(
    node: DocNode,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCoverFolder = node.isDirectory
    PressableSurface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                if (isCoverFolder) {
                    Modifier
                        .height(58.dp)
                        .aspectRatio(FOLDER_COVER_ASPECT)
                        .clip(RoundedCornerShape(AppRadius.sm))
                } else {
                    Modifier
                        .size(52.dp)
                        .clip(RoundedCornerShape(AppRadius.sm))
                },
            ) {
                MediaThumbnail(
                    node = node,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = if (isCoverFolder) ContentScale.Fit else ContentScale.Crop,
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name.bidiName(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle.bidiLtr(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            trailing()
        }
    }
}

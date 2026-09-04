package com.usbmediaexplorer.ui.library

import android.net.Uri
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.store.FavoriteEntry
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.di.AppContainer
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.util.Formatters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class FavoritesState(
    val entries: List<FavoriteEntry> = emptyList(),
    val nodes: List<DocNode> = emptyList(),
    val missing: List<FavoriteEntry> = emptyList(),
    val loading: Boolean = true,
)

/** Favorites (spec §17). Entries pointing at unplugged drives are reported, not silently dropped. */
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Suppress("UNUSED_PARAMETER")
fun FavoritesScreen(snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val viewModel: FavoritesViewModel = viewModel(
        factory = viewModelFactory { FavoritesViewModel(container) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.section_favorites)) },
                actions = {
                    if (state.missing.isNotEmpty()) {
                        IconButton(onClick = { viewModel.removeMissing() }) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = stringResource(R.string.action_delete),
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
            if (state.nodes.isEmpty() && !state.loading) {
                EmptyHint(
                    icon = { Icon(Icons.Outlined.Favorite, contentDescription = null, modifier = Modifier.size(48.dp)) },
                    text = stringResource(R.string.section_favorites),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
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
                                if (entry != null) {
                                    IconButton(onClick = { viewModel.remove(entry) }) {
                                        Icon(
                                            Icons.Outlined.Favorite,
                                            contentDescription = stringResource(R.string.action_unfavorite),
                                            tint = MaterialTheme.colorScheme.primary,
                                        )
                                    }
                                }
                            },
                        )
                    }
                    if (state.missing.isNotEmpty()) {
                        item(key = "missing-header") {
                            Text(
                                text = stringResource(R.string.volume_unmounted),
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                            )
                        }
                        items(state.missing, key = { "missing-${it.key}" }) { entry ->
                            MissingRow(
                                entry = entry,
                                onRemove = { viewModel.remove(entry) },
                            )
                        }
                    }
                }
            }
        }
    }
}

data class RecentState(
    val videos: List<RecentEntry> = emptyList(),
    val folders: List<RecentEntry> = emptyList(),
    val tab: Int = 0,
)

/** "Last files" screen (spec §18). */
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentScreen() {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val viewModel: RecentViewModel = viewModel(
        factory = viewModelFactory { RecentViewModel(container) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.section_recent)) },
                actions = {
                    IconButton(onClick = { viewModel.clearAll() }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
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
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = state.tab == 0,
                    onClick = { viewModel.setTab(0) },
                    label = { Text(stringResource(R.string.recent_videos)) },
                )
                FilterChip(
                    selected = state.tab == 1,
                    onClick = { viewModel.setTab(1) },
                    label = { Text(stringResource(R.string.recent_folders)) },
                )
            }
            val entries = if (state.tab == 0) state.videos else state.folders
            if (entries.isEmpty()) {
                EmptyHint(
                    icon = { Icon(Icons.Outlined.History, contentDescription = null, modifier = Modifier.size(48.dp)) },
                    text = stringResource(R.string.section_recent),
                )
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.key }) { entry ->
                        RecentRow(
                            entry = entry,
                            onClick = {
                                val uri = runCatching { Uri.parse(entry.uri) }.getOrNull() ?: return@RecentRow
                                if (entry.isDirectory) navigator.openFolder(uri) else navigator.playVideo(uri, null)
                            },
                            onRemove = { viewModel.remove(entry) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentRow(entry: RecentEntry, onClick: () -> Unit, onRemove: () -> Unit) {
    val node = remember(entry) {
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
        trailing = {
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        },
    )
}

@Composable
private fun MissingRow(entry: FavoriteEntry, onRemove: () -> Unit) {
    Card(shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Outlined.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    text = entry.displayPath,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onRemove) {
                Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun MediaRow(
    node: DocNode,
    subtitle: String,
    onClick: () -> Unit,
    trailing: @Composable () -> Unit,
) {
    Card(onClick = onClick, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaThumbnail(
                node = node,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = node.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle.isNotBlank()) {
                    Text(
                        text = subtitle,
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

@Composable
private fun EmptyHint(icon: @Composable () -> Unit, text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Spacer(Modifier.height(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package com.usbmediaexplorer.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.search.SearchFilter
import com.usbmediaexplorer.ui.browse.DocItem
import com.usbmediaexplorer.ui.browse.components.DocItemsView
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.viewModelFactory

/** Search screen with the ready-made filters from spec §12. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(rootUri: String, snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val viewModel: SearchViewModel = viewModel(
        key = "search-$rootUri",
        factory = viewModelFactory { SearchViewModel(container, rootUri) },
    )
    val query by viewModel.query.collectAsStateWithLifecycle()
    val result by viewModel.result.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }

    val docItems = remember(result.matches) { result.matches.map { DocItem(node = it) } }

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
                title = {
                    OutlinedTextField(
                        value = text,
                        onValueChange = {
                            text = it
                            viewModel.setText(it)
                        },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                        trailingIcon = {
                            if (text.isNotEmpty()) {
                                IconButton(onClick = {
                                    text = ""
                                    viewModel.setText("")
                                }) {
                                    Icon(
                                        Icons.Outlined.Clear,
                                        contentDescription = stringResource(R.string.cd_clear),
                                    )
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            LazyRow(
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(SearchFilter.entries.toList(), key = { it.name }) { filter ->
                    FilterChip(
                        selected = query.filter == filter,
                        onClick = { viewModel.setFilter(filter) },
                        label = { Text(stringResource(filterLabel(filter))) },
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.search_scanned, result.scanned),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                if (result.isRunning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (result.truncated) {
                    Text(
                        text = "…",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Box(Modifier.fillMaxSize()) {
                when {
                    docItems.isEmpty() && result.isRunning -> CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                    )

                    docItems.isEmpty() -> Text(
                        text = stringResource(
                            if (query.text.isBlank() && query.filter == SearchFilter.ALL) {
                                R.string.search_hint
                            } else {
                                R.string.search_no_results
                            },
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(24.dp),
                    )

                    else -> DocItemsView(
                        items = docItems,
                        viewMode = if (docItems.all { it.node.kind == MediaKind.VIDEO }) {
                            ViewMode.GRID_MEDIUM
                        } else {
                            ViewMode.LIST
                        },
                        selection = emptySet(),
                        selecting = false,
                        onOpen = { item ->
                            viewModel.onOpen(item.node)
                            when (item.node.kind) {
                                MediaKind.DIRECTORY -> navigator.openFolder(item.node.uri)
                                MediaKind.VIDEO -> navigator.playVideo(item.node.uri, null)
                                MediaKind.IMAGE -> navigator.viewImage(item.node.uri, null)
                                else -> navigator.openFolder(item.node.uri)
                            }
                        },
                        onLongPress = { },
                        onToggleSelect = { },
                        onMore = { },
                        onVisible = { node -> container.metadataRepository.enqueue(node) },
                        contentPadding = PaddingValues(12.dp),
                    )
                }
            }
        }
    }
}

private fun filterLabel(filter: SearchFilter): Int = when (filter) {
    SearchFilter.ALL -> R.string.filter_all
    SearchFilter.VIDEOS -> R.string.filter_videos
    SearchFilter.MOVIES -> R.string.filter_movies
    SearchFilter.SERIES -> R.string.filter_series
    SearchFilter.PHOTOS -> R.string.filter_photos
    SearchFilter.MUSIC -> R.string.filter_music
    SearchFilter.LARGE -> R.string.filter_large
}

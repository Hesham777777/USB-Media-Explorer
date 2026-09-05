package com.usbmediaexplorer.ui.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.RotateRight
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.request.ImageRequest
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocSorter
import com.usbmediaexplorer.data.doc.isImage
import com.usbmediaexplorer.di.AppContainer
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.ConfirmDialog
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.Intents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ViewerState(
    val images: List<DocNode> = emptyList(),
    val index: Int = 0,
    val loading: Boolean = true,
)

/** Swipes through the images of a folder, resolved from the drive itself. */
class ImageViewerViewModel(
    private val container: AppContainer,
    uriString: String,
    folderUriString: String,
) : ViewModel() {

    private val _state = MutableStateFlow(ViewerState())
    val state: StateFlow<ViewerState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            val node = uri?.let { withContext(Dispatchers.IO) { container.docRepository.node(it) } }
            val folderUri = folderUriString.takeIf { it.isNotBlank() }?.let {
                runCatching { Uri.parse(it) }.getOrNull()
            }
            val siblings = folderUri?.let { parent ->
                withContext(Dispatchers.IO) {
                    val parentNode = container.docRepository.node(parent)
                    parentNode?.let { container.docRepository.children(it) }
                }
            } ?: emptyList()
            val images = siblings.filter { it.isImage }
                .let { DocSorter.sort(it, com.usbmediaexplorer.data.settings.SortMode.NAME_ASC, foldersFirst = false) }
            val list = images.ifEmpty { listOfNotNull(node) }
            val index = list.indexOfFirst { it?.uri?.toString() == uriString }.coerceAtLeast(0)
            _state.value = ViewerState(list, index, loading = false)
        }
    }

    fun setIndex(index: Int) {
        _state.value = _state.value.copy(index = index)
    }

    fun delete(node: DocNode, onDone: () -> Unit) {
        viewModelScope.launch {
            val ok = container.docRepository.delete(node)
            if (ok) {
                container.thumbnailRepository.invalidate(node)
                val remaining = _state.value.images.filterNot { it.key == node.key }
                _state.value = _state.value.copy(
                    images = remaining,
                    index = _state.value.index.coerceAtMost((remaining.size - 1).coerceAtLeast(0)),
                )
            }
            onDone()
        }
    }
}

/** Photo viewer: pinch-zoom, swipe between photos, share/delete (spec §6). */
@Composable
fun ImageViewerScreen(uri: String, folderUri: String) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel: ImageViewerViewModel = viewModel(
        key = "viewer-$uri",
        factory = viewModelFactory { ImageViewerViewModel(container, uri, folderUri) },
    )
    val state by viewModel.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<DocNode?>(null) }
    var rotation by remember { mutableIntStateOf(0) }
    var showInfo by remember { mutableStateOf<DocNode?>(null) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .systemBarsPadding(),
    ) {
        if (state.loading) {
            CircularProgressIndicator(Modifier.align(Alignment.Center))
        } else if (state.images.isEmpty()) {
            Text(
                text = stringResource(R.string.error_unsupported),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            val pagerState = rememberPagerState(
                initialPage = state.index,
                pageCount = { state.images.size },
            )
            // Rotation is per image and resets when you swipe to the next one.
            LaunchedEffect(pagerState.currentPage) {
                viewModel.setIndex(pagerState.currentPage)
                rotation = 0
            }

            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                val node = state.images[page]
                ZoomableImage(node = node, rotation = rotation)
            }

            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                            tint = Color.White,
                        )
                    }
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = state.images.getOrNull(pagerState.currentPage)?.name.orEmpty(),
                            color = Color.White,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = "${pagerState.currentPage + 1} / ${state.images.size} • " +
                                Formatters.size(
                                    state.images.getOrNull(pagerState.currentPage)
                                        ?.size?.coerceAtLeast(0) ?: 0,
                                ),
                            color = Color.White.copy(alpha = 0.7f),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                    IconButton(onClick = {
                        val node = state.images.getOrNull(pagerState.currentPage) ?: return@IconButton
                        val external = container.docRepository.externalUri(node)
                        if (external == null || !Intents.share(context, listOf(external), node.mimeType)) {
                            // Nothing to share with: fall back to the details of the failure.
                            return@IconButton
                        }
                    }) {
                        Icon(Icons.Outlined.Share, contentDescription = stringResource(R.string.action_share), tint = Color.White)
                    }
                    IconButton(onClick = { rotation = (rotation + 90) % 360 }) {
                        Icon(
                            Icons.Outlined.RotateRight,
                            contentDescription = stringResource(R.string.action_rotate),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = {
                        showInfo = state.images.getOrNull(pagerState.currentPage)
                    }) {
                        Icon(
                            Icons.Outlined.Info,
                            contentDescription = stringResource(R.string.action_details),
                            tint = Color.White,
                        )
                    }
                    IconButton(onClick = {
                        confirmDelete = state.images.getOrNull(pagerState.currentPage)
                    }) {
                        Icon(Icons.Outlined.Delete, contentDescription = stringResource(R.string.action_delete), tint = Color.White)
                    }
                }
            }
        }
    }

    showInfo?.let { node ->
        ConfirmDialog(
            title = node.name,
            body = listOf(
                Formatters.size(node.size.coerceAtLeast(0)),
                Formatters.dateTime(node.lastModified),
                node.displayPath,
            ).filter { it.isNotBlank() }.joinToString("\n"),
            confirmLabel = stringResource(R.string.action_close),
            onConfirm = { showInfo = null },
            onDismiss = { showInfo = null },
        )
    }

    confirmDelete?.let { node ->
        ConfirmDialog(
            title = stringResource(R.string.dialog_delete_title, 1),
            body = "${stringResource(R.string.dialog_delete_body)}\n${node.name}",
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                viewModel.delete(node) { confirmDelete = null }
            },
            onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun ZoomableImage(node: DocNode, rotation: Int = 0) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offsetX by remember { mutableFloatStateOf(0f) }
    var offsetY by remember { mutableFloatStateOf(0f) }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(node.key) {
                // Double tap toggles between fit and 2.5×, the gesture every photo app has.
                detectTapGestures(
                    onDoubleTap = {
                        if (scale > 1f) {
                            scale = 1f
                            offsetX = 0f
                            offsetY = 0f
                        } else {
                            scale = 2.5f
                        }
                    },
                )
            }
            .pointerInput(node.key, rotation) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 6f)
                    offsetX += pan.x
                    offsetY += pan.y
                    if (scale == 1f) {
                        offsetX = 0f
                        offsetY = 0f
                    }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        coil.compose.AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(node.uri)
                .crossfade(true)
                .build(),
            contentDescription = node.name,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    translationX = offsetX
                    translationY = offsetY
                    rotationZ = rotation.toFloat()
                },
        )
        if (scale > 1f) {
            Text(
                text = "${(scale * 100).toInt()}%",
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp),
            )
        }
    }
}

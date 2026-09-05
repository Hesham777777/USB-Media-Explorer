package com.usbmediaexplorer.ui.browse

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.doc.DocSorter
import com.usbmediaexplorer.data.doc.MediaCount
import com.usbmediaexplorer.data.doc.isArchive
import com.usbmediaexplorer.data.doc.isImage
import com.usbmediaexplorer.data.doc.isVideo
import com.usbmediaexplorer.data.metadata.MediaMetadata
import com.usbmediaexplorer.data.ops.BulkRenameRules
import com.usbmediaexplorer.data.ops.OpsEvent
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.data.settings.SortMode
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.data.store.FavoriteEntry
import com.usbmediaexplorer.data.store.FolderPrefs
import com.usbmediaexplorer.data.store.PlaybackPosition
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.data.volume.VolumeInfo
import com.usbmediaexplorer.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A row/card in the browser: the node plus everything the UI decorates it with. */
data class DocItem(
    val node: DocNode,
    val metadata: MediaMetadata? = null,
    val favorite: Boolean = false,
    val resume: PlaybackPosition? = null,
    /** Lazily resolved media tally for folders (spec §7). */
    val counts: MediaCount? = null,
)

sealed interface OpenAction {
    data class Folder(val uri: Uri) : OpenAction
    data class Video(val uri: Uri, val folderUri: Uri?) : OpenAction
    data class Image(val uri: Uri, val folderUri: Uri?) : OpenAction

    /** Hand the file to another app (documents, archives, subtitles, unknown types). */
    data class External(val node: DocNode) : OpenAction

    data object None : OpenAction
}

data class BrowseUiState(
    val node: DocNode? = null,
    val breadcrumb: List<DocNode> = emptyList(),
    val items: List<DocItem> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID_LARGE,
    val sortMode: SortMode = SortMode.NAME_ASC,
    val foldersFirst: Boolean = true,
    val selection: Set<String> = emptySet(),
    val selecting: Boolean = false,
    val mediaCount: MediaCount = MediaCount(),
    val volume: VolumeInfo? = null,
    val clipboardCount: Int = 0,
    val clipboardIsCut: Boolean = false,
    val canWrite: Boolean = false,
    val showHidden: Boolean = false,
) {
    val selectedNodesCount: Int get() = selection.size
}

data class DetailsState(
    val node: DocNode,
    val metadata: MediaMetadata?,
    val sizeBytes: Long?,
    val loading: Boolean,
    val volumeName: String?,
)

/**
 * Browse screen logic (spec §2, §12–§17).
 *
 * State is assembled from independent flows — folder contents, lazily resolved metadata,
 * favorites, resume positions, per-folder view preferences and the clipboard — so a metadata
 * result arriving for one card re-renders just that, without re-reading the drive.
 */
class BrowseViewModel(
    private val container: AppContainer,
    private val initialUri: String,
) : ViewModel() {

    private val context: Context = container.appContext
    private val docRepository: DocRepository = container.docRepository
    private val metadataRepository = container.metadataRepository
    private val opsManager = container.fileOpsManager

    private val currentNode = MutableStateFlow<DocNode?>(null)
    private val rawChildren = MutableStateFlow<List<DocNode>>(emptyList())
    private val breadcrumb = MutableStateFlow<List<DocNode>>(emptyList())
    private val loading = MutableStateFlow(true)
    private val error = MutableStateFlow<String?>(null)
    private val selection = MutableStateFlow<Set<String>>(emptySet())
    private val selecting = MutableStateFlow(false)

    private val _details = MutableStateFlow<DetailsState?>(null)
    val details: StateFlow<DetailsState?> = _details.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    /** Folder media counts, resolved only for folders the user actually scrolls to. */
    private val folderCounts = MutableStateFlow<Map<String, MediaCount>>(emptyMap())
    private val countQueue = Channel<DocNode>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    private val favorites = container.favoritesStore.favorites
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val positions = container.playbackPositionStore.positions
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val folderPrefs = container.folderPrefsStore.prefs
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private data class Presentation(
        val settings: AppSettings,
        val prefs: FolderPrefs,
        val folderKey: String,
    )

    private data class Core(
        val node: DocNode?,
        val loading: Boolean,
        val error: String?,
        val volume: VolumeInfo?,
        val breadcrumb: List<DocNode>,
    )

    private val presentation: kotlinx.coroutines.flow.Flow<Presentation> = combine(
        container.settingsRepository.settings,
        folderPrefs,
        currentNode,
    ) { settings, prefs, node ->
        val key = node?.stableKey.orEmpty()
        Presentation(settings, prefs[key] ?: FolderPrefs(), key)
    }

    private val core: kotlinx.coroutines.flow.Flow<Core> = combine(
        currentNode,
        loading,
        error,
        breadcrumb,
        container.volumeRepository.volumes,
    ) { node, isLoading, err, trail, volumes ->
        Core(
            node = node,
            loading = isLoading,
            error = err,
            volume = node?.let { current -> volumes.firstOrNull { it.id == current.volumeId } },
            breadcrumb = trail,
        )
    }

    private val selectionState = combine(selection, selecting) { sel, isSelecting -> sel to isSelecting }

    private val items: kotlinx.coroutines.flow.Flow<List<DocItem>> = combine(
        rawChildren,
        metadataRepository.published,
        favorites,
        positions,
        folderCounts,
    ) { children, metadata, favoriteList, resumeMap, counts ->
        val favoriteUris = favoriteList.map { it.uri }.toHashSet()
        children.map { node ->
            DocItem(
                node = node,
                metadata = metadata[node.key],
                favorite = favoriteUris.contains(node.uri.toString()),
                resume = resumeMap[node.stableKey]?.takeIf { !it.isFinished && it.positionMs > 0 },
                counts = counts[node.key],
            )
        }
    }

    val state: StateFlow<BrowseUiState> = combine(
        items,
        presentation,
        selectionState,
        core,
        opsManager.clipboard,
    ) { itemList, pres, sel, coreState, clipboard ->
        val settings = pres.settings
        val prefs = pres.prefs
        val visible = itemList.filter { settings.showHiddenFiles || !it.node.isHidden }
        val counts = mediaCountOf(visible.map { it.node })
        val sortMode = if (settings.rememberPerFolderView) {
            prefs.sortMode ?: settings.defaultSortMode
        } else {
            settings.defaultSortMode
        }
        val viewMode = if (settings.rememberPerFolderView) {
            prefs.viewMode ?: autoViewMode(counts, settings)
        } else {
            autoViewMode(counts, settings)
        }
        val sortedNodes = DocSorter.sort(
            nodes = visible.map { it.node },
            mode = sortMode,
            foldersFirst = settings.foldersFirst,
            metadata = { node -> metadataRepository.peek(node) },
        )
        val byKey = itemList.associateBy { it.node.key }
        BrowseUiState(
            node = coreState.node,
            breadcrumb = coreState.breadcrumb,
            items = sortedNodes.mapNotNull { byKey[it.key] },
            loading = coreState.loading,
            error = coreState.error,
            viewMode = viewMode,
            sortMode = sortMode,
            foldersFirst = settings.foldersFirst,
            selection = sel.first,
            selecting = sel.second,
            mediaCount = counts,
            volume = coreState.volume,
            clipboardCount = clipboard?.items?.size ?: 0,
            clipboardIsCut = clipboard?.isCut == true,
            canWrite = coreState.node?.isWritable == true && coreState.node.isDirectory,
            showHidden = settings.showHiddenFiles,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowseUiState())

    init {
        load(initialUri)
        // Single worker: folder tallies are cheap but must never compete with thumbnail I/O.
        viewModelScope.launch(Dispatchers.IO) {
            for (node in countQueue) {
                if (folderCounts.value.containsKey(node.key)) continue
                val counts = runCatching { docRepository.mediaCount(node) }.getOrNull() ?: continue
                val updated = LinkedHashMap(folderCounts.value)
                if (updated.size > 240) updated.clear()
                updated[node.key] = counts
                folderCounts.value = updated
            }
        }
        viewModelScope.launch {
            opsManager.events.collect { event ->
                when (event) {
                    is OpsEvent.Completed -> if (event.success) reload()
                    is OpsEvent.Failed -> Unit
                    OpsEvent.ClipboardChanged -> Unit
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    fun load(uriString: String) {
        if (uriString.isBlank()) {
            error.value = context.getString(R.string.error_no_permission)
            loading.value = false
            return
        }
        viewModelScope.launch {
            loading.value = true
            error.value = null
            val uri = runCatching { Uri.parse(uriString) }.getOrNull()
            val node = uri?.let { docRepository.node(it) }
            if (node == null) {
                error.value = context.getString(R.string.error_no_permission)
                loading.value = false
                rawChildren.value = emptyList()
                return@launch
            }
            currentNode.value = node
            breadcrumb.value = docRepository.breadcrumb(node)
            recordFolder(node)
            val children = runCatching {
                withContext(Dispatchers.IO) { docRepository.children(node) }
            }
            children.onSuccess { list ->
                rawChildren.value = list
                // Media folders get their info resolved progressively, never all at once.
                list.take(40).forEach { metadataRepository.enqueue(it) }
            }.onFailure {
                error.value = context.getString(R.string.error_device_disconnected)
                rawChildren.value = emptyList()
            }
            loading.value = false
            clearSelection()
        }
    }

    fun reload() {
        currentNode.value?.let { load(it.uri.toString()) }
    }

    /**
     * A SAF tree granted from the error state: make it permanent when Android allows it, then open
     * it — the folder the user was trying to reach is usually inside the granted tree.
     */
    fun onTreeGranted(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            if (!container.volumeRepository.persistTree(uri)) {
                message(context.getString(R.string.grant_not_persisted))
            }
            container.volumeRepository.refresh()
            load(uri.toString())
        }
    }

    /** Called when a card becomes visible: resolves info lazily instead of up front. */
    fun onItemVisible(node: DocNode) {
        if (node.isDirectory) {
            if (!folderCounts.value.containsKey(node.key)) countQueue.trySend(node)
        } else {
            metadataRepository.enqueue(node)
        }
    }

    fun navigateUp(): Uri? {
        val node = currentNode.value ?: return null
        val trail = breadcrumb.value
        if (trail.size >= 2) return trail[trail.size - 2].uri
        return null
    }

    // ------------------------------------------------------------------
    // Opening items
    // ------------------------------------------------------------------

    fun onOpen(item: DocItem): OpenAction {
        val node = item.node
        return when {
            node.isDirectory -> {
                OpenAction.Folder(node.uri)
            }

            node.isVideo -> {
                recordVideo(node)
                OpenAction.Video(node.uri, currentNode.value?.uri)
            }

            node.isImage -> OpenAction.Image(node.uri, currentNode.value?.uri)
            else -> OpenAction.External(node)
        }
    }

    /** Sibling videos of [node] in load order, used for next/previous in the player (spec §10). */
    fun playlistFor(node: DocNode): List<DocNode> {
        val sortMode = state.value.sortMode
        return DocSorter.sort(
            nodes = rawChildren.value.filter { it.isVideo },
            mode = sortMode,
            foldersFirst = false,
            metadata = { metadataRepository.peek(it) },
        )
    }

    // ------------------------------------------------------------------
    // Selection
    // ------------------------------------------------------------------

    fun startSelection(node: DocNode) {
        selecting.value = true
        selection.value = setOf(node.key)
    }

    fun toggleSelection(node: DocNode) {
        val current = selection.value.toMutableSet()
        if (!current.remove(node.key)) current += node.key
        selection.value = current
        selecting.value = current.isNotEmpty()
    }

    fun selectAll() {
        selection.value = state.value.items.map { it.node.key }.toSet()
        selecting.value = true
    }

    fun invertSelection() {
        val all = state.value.items.map { it.node.key }
        selection.value = all.filterNot { it in selection.value }.toSet()
        selecting.value = true
    }

    fun clearSelection() {
        selection.value = emptySet()
        selecting.value = false
    }

    private fun selectedNodes(): List<DocNode> {
        val keys = selection.value
        return state.value.items.map { it.node }.filter { it.key in keys }
    }

    // ------------------------------------------------------------------
    // File operations (spec §14, §15, §16)
    // ------------------------------------------------------------------

    fun copySelection() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        opsManager.copyToClipboard(nodes, currentNode.value, cut = false)
        message(context.getString(R.string.msg_items_selected_for_copy, nodes.size))
        clearSelection()
    }

    fun cutSelection() {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        opsManager.copyToClipboard(nodes, currentNode.value, cut = true)
        message(context.getString(R.string.msg_items_selected_for_move, nodes.size))
        clearSelection()
    }

    fun paste() {
        val destination = currentNode.value
        if (destination == null || !destination.isDirectory) {
            message(context.getString(R.string.msg_no_selection))
            return
        }
        val clip = opsManager.clipboard.value
        if (clip == null) {
            message(context.getString(R.string.msg_no_selection))
            return
        }
        opsManager.paste(destination)
        clearSelection()
    }

    fun delete(nodes: List<DocNode>) {
        if (nodes.isEmpty()) return
        opsManager.delete(nodes)
        clearSelection()
    }

    fun deleteSelection() = delete(selectedNodes())

    fun rename(node: DocNode, newName: String) {
        if (newName.isBlank()) {
            message(context.getString(R.string.error_empty_name))
            return
        }
        viewModelScope.launch {
            val result = docRepository.rename(node, newName)
            if (result != null) {
                container.thumbnailRepository.invalidate(node)
                message(context.getString(R.string.msg_renamed))
                reload()
            } else {
                message(context.getString(R.string.error_name_exists))
            }
        }
    }

    fun createFolder(name: String) {
        val parent = currentNode.value ?: return
        if (name.isBlank()) {
            message(context.getString(R.string.error_empty_name))
            return
        }
        viewModelScope.launch {
            val created = docRepository.createDirectory(parent, name)
            message(
                context.getString(
                    if (created != null) R.string.msg_created else R.string.error_name_exists,
                ),
            )
            if (created != null) reload()
        }
    }

    fun createFile(name: String) {
        val parent = currentNode.value ?: return
        if (name.isBlank()) {
            message(context.getString(R.string.error_empty_name))
            return
        }
        viewModelScope.launch {
            val created = docRepository.createFile(parent, name)
            message(
                context.getString(
                    if (created != null) R.string.msg_created else R.string.error_name_exists,
                ),
            )
            if (created != null) reload()
        }
    }

    fun zipSelection(archiveName: String) {
        val nodes = selectedNodes()
        val destination = currentNode.value ?: return
        if (nodes.isEmpty()) return
        opsManager.zip(nodes, destination, archiveName.ifBlank { nodes.first().nameWithoutExtension })
        clearSelection()
    }

    fun unzip(node: DocNode) {
        val destination = currentNode.value ?: return
        opsManager.unzip(node, destination)
    }

    fun bulkRename(rules: BulkRenameRules) {
        val nodes = selectedNodes()
        if (nodes.isEmpty()) return
        opsManager.bulkRename(nodes, rules)
        clearSelection()
    }

    /** URIs to hand to an ACTION_SEND / ACTION_VIEW intent. */
    fun shareUris(nodes: List<DocNode>): List<Uri> =
        nodes.mapNotNull { node ->
            if (node.isArchive || node.isDirectory) null else docRepository.externalUri(node)
        }

    fun toggleFavorite(node: DocNode) {
        viewModelScope.launch {
            val uri = node.uri.toString()
            val already = container.favoritesStore.contains(uri)
            if (already) {
                container.favoritesStore.remove(uri)
                message(context.getString(R.string.msg_removed_from_favorites))
            } else {
                container.favoritesStore.add(
                    FavoriteEntry(
                        key = node.key,
                        uri = uri,
                        name = node.name,
                        isDirectory = node.isDirectory,
                        volumeId = node.volumeId,
                        displayPath = node.displayPath,
                        addedAt = System.currentTimeMillis(),
                    ),
                )
                message(context.getString(R.string.msg_added_to_favorites))
            }
        }
    }

    // ------------------------------------------------------------------
    // View & sort preferences
    // ------------------------------------------------------------------

    /** Per-folder override when enabled, plus the new global default (spec §13). */
    fun setViewMode(mode: ViewMode) {
        viewModelScope.launch {
            state.value.node?.stableKey?.let { key ->
                container.folderPrefsStore.setViewMode(key, mode)
            }
            container.settingsRepository.setDefaultViewMode(mode)
        }
    }

    fun setSortMode(mode: SortMode) {
        val key = state.value.node?.stableKey
        viewModelScope.launch {
            if (key != null) container.folderPrefsStore.setSortMode(key, mode)
            container.settingsRepository.setDefaultSortMode(mode)
        }
    }

    fun setFoldersFirst(value: Boolean) {
        viewModelScope.launch { container.settingsRepository.setFoldersFirst(value) }
    }

    fun resetFolderPrefs() {
        val key = state.value.node?.stableKey ?: return
        viewModelScope.launch { container.folderPrefsStore.clear(key) }
    }

    // ------------------------------------------------------------------
    // Details sheet (spec §21)
    // ------------------------------------------------------------------

    fun requestDetails(node: DocNode) {
        val volumeName = container.volumeRepository.volumeById(node.volumeId)?.name
        _details.value = DetailsState(
            node = node,
            metadata = metadataRepository.peek(node),
            sizeBytes = if (node.isDirectory) null else node.size,
            loading = true,
            volumeName = volumeName,
        )
        viewModelScope.launch {
            val metadata = if (node.isDirectory) null else metadataRepository.load(node)
            val size = if (node.isDirectory) {
                runCatching { docRepository.directorySize(node) }.getOrNull()
            } else {
                node.size
            }
            _details.value = _details.value?.copy(
                metadata = metadata ?: _details.value?.metadata,
                sizeBytes = size,
                loading = false,
            )
        }
    }

    fun clearDetails() {
        _details.value = null
    }

    // ------------------------------------------------------------------
    // Bookkeeping
    // ------------------------------------------------------------------

    private fun recordFolder(node: DocNode) {
        if (!node.isDirectory) return
        viewModelScope.launch {
            container.recentStore.recordFolder(
                RecentEntry(
                    key = node.key,
                    uri = node.uri.toString(),
                    name = node.name,
                    isDirectory = true,
                    volumeId = node.volumeId,
                    displayPath = node.displayPath,
                    size = -1,
                    lastOpenedAt = System.currentTimeMillis(),
                    kindName = node.kind.name,
                ),
            )
        }
    }

    private fun recordVideo(node: DocNode) {
        viewModelScope.launch {
            container.recentStore.recordVideo(
                RecentEntry(
                    key = node.key,
                    uri = node.uri.toString(),
                    name = node.name,
                    isDirectory = false,
                    volumeId = node.volumeId,
                    displayPath = node.displayPath,
                    size = node.size,
                    lastOpenedAt = System.currentTimeMillis(),
                    kindName = node.kind.name,
                ),
            )
        }
    }

    fun savePlaybackPosition(node: DocNode, positionMs: Long, durationMs: Long) {
        viewModelScope.launch {
            container.playbackPositionStore.save(
                PlaybackPosition(
                    key = node.stableKey,
                    positionMs = positionMs,
                    durationMs = durationMs,
                    updatedAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    private fun message(text: String) {
        _messages.tryEmit(text)
    }

    /**
     * Per-folder default (spec §22). A folder of videos wants big tiles so the real frames are
     * readable; a folder made of movie folders wants the user's grid so its covers line up as a
     * poster grid; a folder with nothing visual falls back to the list.
     */
    private fun autoViewMode(counts: MediaCount, settings: AppSettings): ViewMode = when {
        counts.videos > 0 -> ViewMode.GRID_LARGE
        counts.images > 0 && counts.mediaTotal >= 6 -> ViewMode.GRID_MEDIUM
        counts.mediaTotal > 0 -> ViewMode.GRID_SMALL
        counts.folders > 0 -> settings.defaultViewMode
        else -> ViewMode.LIST
    }

    private fun mediaCountOf(nodes: List<DocNode>): MediaCount {
        var videos = 0
        var images = 0
        var audios = 0
        var folders = 0
        var others = 0
        nodes.forEach { node ->
            when (node.kind) {
                com.usbmediaexplorer.data.doc.MediaKind.DIRECTORY -> folders++
                com.usbmediaexplorer.data.doc.MediaKind.VIDEO -> videos++
                com.usbmediaexplorer.data.doc.MediaKind.IMAGE -> images++
                com.usbmediaexplorer.data.doc.MediaKind.AUDIO -> audios++
                else -> others++
            }
        }
        return MediaCount(videos, images, audios, folders, others)
    }
}

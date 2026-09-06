package com.usbmediaexplorer.ui.home

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.store.PlaybackPosition
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.data.volume.VolumeInfo
import com.usbmediaexplorer.data.volume.VolumeKind
import com.usbmediaexplorer.data.volume.VolumeState
import com.usbmediaexplorer.di.AppContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One "continue watching" card: the file plus its saved resume point. */
data class ContinueEntry(val node: DocNode, val position: PlaybackPosition)

data class HomeUiState(
    val volumes: List<VolumeInfo> = emptyList(),
    val refreshing: Boolean = false,
    val continueWatching: List<ContinueEntry> = emptyList(),
    val recentVideos: List<DocNode> = emptyList(),
    val recentFolders: List<RecentEntry> = emptyList(),
    val favoriteCount: Int = 0,
    val needsMediaPermission: Boolean = false,
    /** Android refuses to show the dialog any more: the only way left is the app settings. */
    val mediaPermissionBlocked: Boolean = false,
    val usbWaitingForGrant: VolumeInfo? = null,
)

/** Home screen: storage volumes, quick access and recent media (spec §1, §18). */
class HomeViewModel(private val container: AppContainer) : ViewModel() {

    private companion object {
        /** "Recent folders" means the last two hours, not the last hundred entries. */
        const val RECENT_WINDOW_MS = 2 * 60 * 60 * 1000L
        const val MAX_RECENT_FOLDERS = 8

        /** Cap for the resume row; older bookmarks stay in the store, just off the screen. */
        const val MAX_CONTINUE_WATCHING = 12
    }

    private val _state = MutableStateFlow(HomeUiState())
    val state: StateFlow<HomeUiState> = _state.asStateFlow()

    /** One-shot user feedback as string resource ids (grant problems and similar). */
    private val _messages = MutableSharedFlow<Int>(extraBufferCapacity = 4)
    val messages: Flow<Int> = _messages.asSharedFlow()

    private val volumeRepository = container.volumeRepository
    private val docRepository = container.docRepository

    val hasAnyVolume: StateFlow<Boolean> = volumeRepository.volumes
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init {
        viewModelScope.launch {
            volumeRepository.volumes.collect { volumes ->
                val usbPending = volumes.firstOrNull {
                    it.state == VolumeState.NEEDS_PERMISSION && it.isUsbAttached
                }
                _state.value = _state.value.copy(
                    volumes = volumes,
                    usbWaitingForGrant = usbPending,
                )
            }
        }
        viewModelScope.launch {
            volumeRepository.refreshing.collect { refreshing ->
                _state.value = _state.value.copy(refreshing = refreshing)
            }
        }
        viewModelScope.launch {
            container.recentStore.recentVideos.collect { entries ->
                val nodes = resolveRecent(entries)
                _state.value = _state.value.copy(recentVideos = nodes)
            }
        }
        viewModelScope.launch {
            // The positions map re-emits every few seconds while a video plays; joining the
            // top bookmarks against the node cache below is a cheap in-memory pass.
            container.playbackPositionStore.positions.collect { positions ->
                val unfinished = positions.values
                    .filter { !it.isFinished && it.positionMs > 0 }
                    .sortedByDescending { it.updatedAt }
                    .take(MAX_CONTINUE_WATCHING)
                _state.value = _state.value.copy(continueWatching = resolvePositions(unfinished))
            }
        }
        viewModelScope.launch {
            container.recentStore.recentFolders.collect { entries ->
                _state.value = _state.value.copy(recentFolders = recentWindow(entries))
            }
        }
        viewModelScope.launch {
            container.favoritesStore.favorites.collect { favorites ->
                _state.value = _state.value.copy(favoriteCount = favorites.size)
            }
        }
        viewModelScope.launch { refresh() }
    }

    fun refresh() {
        viewModelScope.launch { volumeRepository.refresh() }
    }

    private var mediaGranted: Boolean? = null

    /**
     * Records the media-permission state after a resume and reports whether it *changed*.
     *
     * Returning to the home screen must not rescan every volume: the topology already follows
     * mount/unmount events, and a rescan is only meaningful when a grant appeared or disappeared
     * (for example after a round trip through the system settings).
     */
    fun syncMediaPermission(granted: Boolean): Boolean {
        val changed = mediaGranted != null && mediaGranted != granted
        mediaGranted = granted
        setNeedsMediaPermission(!granted)
        if (granted) setMediaPermissionBlocked(false)
        return changed
    }

    /**
     * Re-applies the recency window. Called when the screen resumes, so a folder opened three
     * hours ago disappears from "recent folders" without waiting for the store to emit.
     */
    fun refreshRecents() {
        viewModelScope.launch {
            val entries = container.recentStore.recentFolders.first()
            _state.value = _state.value.copy(recentFolders = recentWindow(entries))
        }
    }

    /** Folders opened in the last two hours only — the section is a "continue where you were". */
    private fun recentWindow(entries: List<RecentEntry>): List<RecentEntry> {
        val cutoff = System.currentTimeMillis() - RECENT_WINDOW_MS
        return entries.filter { it.lastOpenedAt >= cutoff }.take(MAX_RECENT_FOLDERS)
    }

    /** Called once the first-launch permission request has been answered, whatever the answer. */
    fun markFirstRunPermissionsAsked() {
        viewModelScope.launch { container.settingsRepository.setFirstRunPermissionsAsked(true) }
    }

    /** The volume the header search button searches: internal first, then any ready volume. */
    fun searchRoot(): Uri? {
        val volumes = _state.value.volumes.filter { it.state == VolumeState.READY && it.rootUri != Uri.EMPTY }
        return (volumes.firstOrNull { it.kind == VolumeKind.INTERNAL } ?: volumes.firstOrNull())?.rootUri
    }

    fun setNeedsMediaPermission(value: Boolean) {
        _state.value = _state.value.copy(needsMediaPermission = value)
    }

    fun setMediaPermissionBlocked(value: Boolean) {
        if (_state.value.mediaPermissionBlocked != value) {
            _state.value = _state.value.copy(mediaPermissionBlocked = value)
        }
    }

    /** Intent that asks Android for a one-time grant on this volume (spec §1). */
    fun grantIntentFor(volume: VolumeInfo): Intent? = volume.grantIntent
        ?: Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
        }

    /** Called with the result of the SAF tree picker. */
    fun onTreeGranted(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val persisted = volumeRepository.persistTree(uri)
            volumeRepository.refresh()
            if (!persisted) {
                // The volume still opens for this session, but the user deserves to know that
                // Android refused to remember the grant.
                _messages.tryEmit(R.string.grant_not_persisted)
            }
        }
    }

    /** [granted] is re-read from the system by the caller, never inferred from the result map. */
    fun onMediaPermissionResult(granted: Boolean, blocked: Boolean = false) {
        _state.value = _state.value.copy(
            needsMediaPermission = !granted,
            mediaPermissionBlocked = !granted && blocked,
        )
        // Always refresh: a partial grant ("Select photos") or a denial both change what the
        // internal storage card can show.
        refresh()
    }

    /** Opens a volume, or returns null when a grant is required first. */
    fun openableUri(volume: VolumeInfo): Uri? =
        if (volume.state == VolumeState.READY && volume.rootUri != Uri.EMPTY) volume.rootUri else null

    fun releaseVolume(volume: VolumeInfo) {
        volumeRepository.releaseTree(volume)
    }

    fun forgetRecentVideo(node: DocNode) {
        viewModelScope.launch { container.recentStore.removeVideo(node.stableKey) }
    }

    /** Removes the resume point so the video leaves the "continue watching" row. */
    fun forgetContinue(entry: ContinueEntry) {
        continueNodes.remove(entry.position.key)
        viewModelScope.launch { container.playbackPositionStore.clear(entry.position.key) }
    }

    /**
     * Nodes for the resume row, cached by key: only brand-new bookmarks deserve a provider
     * query. A file that cannot be resolved right now (its USB drive is unplugged) is skipped
     * silently — the bookmark itself must survive until the drive returns.
     */
    private val continueNodes = HashMap<String, DocNode>()

    private suspend fun resolvePositions(positions: List<PlaybackPosition>): List<ContinueEntry> =
        withContext(Dispatchers.IO) {
            if (continueNodes.size > 64) continueNodes.clear()
            positions.mapNotNull { position ->
                val node = continueNodes[position.key]
                    ?: docRepository.node(Uri.parse(position.key))
                        ?.takeIf { !it.isDirectory }
                        ?.also { continueNodes[position.key] = it }
                node?.let { ContinueEntry(it, position) }
            }
        }

    private suspend fun resolveRecent(entries: List<RecentEntry>): List<DocNode> =
        withContext(Dispatchers.IO) {
            entries.take(12).mapNotNull { entry ->
                runCatching { Uri.parse(entry.uri) }.getOrNull()?.let { uri ->
                    docRepository.node(uri)?.takeIf { !it.isDirectory }
                }
            }
        }
}

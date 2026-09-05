package com.usbmediaexplorer.ui.home

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.data.volume.VolumeInfo
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class HomeUiState(
    val volumes: List<VolumeInfo> = emptyList(),
    val refreshing: Boolean = false,
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
            container.recentStore.recentFolders.collect { entries ->
                _state.value = _state.value.copy(recentFolders = entries.take(8))
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

    private suspend fun resolveRecent(entries: List<RecentEntry>): List<DocNode> =
        withContext(Dispatchers.IO) {
            entries.take(12).mapNotNull { entry ->
                runCatching { Uri.parse(entry.uri) }.getOrNull()?.let { uri ->
                    docRepository.node(uri)?.takeIf { !it.isDirectory }
                }
            }
        }
}

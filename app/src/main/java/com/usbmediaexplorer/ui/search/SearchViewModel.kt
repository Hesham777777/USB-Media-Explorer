package com.usbmediaexplorer.ui.search

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.data.search.SearchFilter
import com.usbmediaexplorer.data.search.SearchQuery
import com.usbmediaexplorer.data.search.SearchResult
import com.usbmediaexplorer.di.AppContainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Search across the whole volume with the ready-made filters from spec §12. */
class SearchViewModel(
    private val container: AppContainer,
    private val rootUriString: String,
) : ViewModel() {

    private val _query = MutableStateFlow(SearchQuery())
    val query: StateFlow<SearchQuery> = _query.asStateFlow()

    private val _result = MutableStateFlow(SearchResult())
    val result: StateFlow<SearchResult> = _result.asStateFlow()

    private companion object {
        /** How long typing must pause before a search starts. */
        const val SEARCH_DEBOUNCE_MS = 220L
    }

    private var searchJob: Job? = null

    init {
        // One walk per pause in typing, and a new pause cancels the previous search: the disk
        // is never re-read per keystroke once the engine holds a snapshot of the root.
        viewModelScope.launch {
            _query.map { it.text }
                .debounce(SEARCH_DEBOUNCE_MS)
                .collectLatest { runSearch() }
        }
    }

    fun setText(text: String) {
        _query.value = _query.value.copy(text = text)
    }

    fun setFilter(filter: SearchFilter) {
        _query.value = _query.value.copy(filter = filter)
        runSearch()
    }

    fun setMinSize(bytes: Long) {
        _query.value = _query.value.copy(minSizeBytes = bytes)
        runSearch()
    }

    fun setModifiedAfter(epochMillis: Long) {
        _query.value = _query.value.copy(modifiedAfter = epochMillis)
        runSearch()
    }

    fun includeFolders(value: Boolean) {
        _query.value = _query.value.copy(includeFolders = value)
        runSearch()
    }

    private fun runSearch() {
        val rootUri = runCatching { Uri.parse(rootUriString) }.getOrNull() ?: return
        searchJob?.cancel()
        val query = _query.value
        if (query.text.isBlank() && query.filter == SearchFilter.ALL) {
            _result.value = SearchResult()
            return
        }
        searchJob = viewModelScope.launch {
            val rootNode = container.docRepository.node(rootUri) ?: return@launch
            val roots = listOf(rootNode)
            container.searchEngine.search(roots, query).collect { partial ->
                _result.value = partial
            }
        }
    }

    /** Search results feed the same "recently watched" list as the browser (spec §18). */
    fun onOpen(node: DocNode) {
        if (node.kind != MediaKind.VIDEO) return
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
}

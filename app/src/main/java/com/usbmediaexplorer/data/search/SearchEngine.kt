package com.usbmediaexplorer.data.search

import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn

/** Ready-made filters from spec §12. */
enum class SearchFilter {
    ALL,
    VIDEOS,
    MOVIES,
    SERIES,
    PHOTOS,
    MUSIC,
    LARGE,
    ;

    val minSizeBytes: Long
        get() = if (this == LARGE) 1L * 1024 * 1024 * 1024 else 0L
}

data class SearchQuery(
    val text: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val extensions: Set<String> = emptySet(),
    val minSizeBytes: Long = 0L,
    val maxSizeBytes: Long = Long.MAX_VALUE,
    val modifiedAfter: Long = 0L,
    val includeFolders: Boolean = true,
    val maxResults: Int = 500,
)

data class SearchResult(
    val matches: List<DocNode> = emptyList(),
    val scanned: Int = 0,
    val isRunning: Boolean = false,
    val truncated: Boolean = false,
)

/**
 * Recursive search across a volume (spec §12).
 *
 * Implemented as a cold [Flow] that yields partial results while walking, so the UI fills in
 * progressively instead of waiting for a full traversal of a slow USB stick. The walk is
 * iterative (no recursion depth limit) and cooperative: cancelling the flow stops I/O at once.
 */
class SearchEngine(
    private val docRepository: DocRepository,
    private val settingsRepository: SettingsRepository,
) {

    fun search(roots: List<DocNode>, query: SearchQuery): Flow<SearchResult> = flow {
        val settings: AppSettings = runCatching { settingsRepository.settings.first() }
            .getOrDefault(AppSettings.DEFAULT)
        val matches = ArrayList<DocNode>()
        val stack = ArrayDeque<DocNode>()
        roots.reversed().forEach { stack.addLast(it) }
        var scanned = 0
        var truncated = false
        var lastEmit = System.currentTimeMillis()

        emit(SearchResult(emptyList(), 0, isRunning = true))

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = stack.removeLast()
            if (!node.isDirectory) continue
            val children = runCatching { docRepository.children(node) }.getOrDefault(emptyList())
            for (child in children) {
                currentCoroutineContext().ensureActive()
                scanned++
                if (!settings.showHiddenFiles && child.isHidden) continue
                if (matches(child, query, settings)) matches += child
                if (matches.size >= query.maxResults) {
                    truncated = true
                    break
                }
                if (child.isDirectory) stack.addLast(child)
            }
            if (truncated) break
            val now = System.currentTimeMillis()
            if (now - lastEmit > 250) {
                lastEmit = now
                emit(SearchResult(matches.toList(), scanned, isRunning = true, truncated = truncated))
            }
        }
        emit(SearchResult(matches.toList(), scanned, isRunning = false, truncated = truncated))
    }.flowOn(Dispatchers.IO)

    private fun matches(node: DocNode, query: SearchQuery, settings: AppSettings): Boolean {
        val kind = node.kind
        if (!node.isDirectory && kind == MediaKind.OTHER && query.text.length < 2) return false
        if (!query.includeFolders && node.isDirectory) return false

        if (query.text.isNotBlank()) {
            val needle = query.text.trim()
            val inName = node.name.contains(needle, ignoreCase = true)
            val inExtension = node.extension.contains(needle.trimStart('.'), ignoreCase = true)
            if (!inName && !inExtension) return false
        }

        if (query.extensions.isNotEmpty() && node.extension !in query.extensions) return false

        val minSize = maxOf(query.minSizeBytes, query.filter.minSizeBytes)
        if (minSize > 0 && node.size < minSize) return false
        if (query.maxSizeBytes < Long.MAX_VALUE && node.size > query.maxSizeBytes) return false
        if (query.modifiedAfter > 0 && node.lastModified < query.modifiedAfter) return false

        return when (query.filter) {
            SearchFilter.ALL -> true
            SearchFilter.VIDEOS -> kind == MediaKind.VIDEO
            SearchFilter.MOVIES -> kind == MediaKind.VIDEO && looksLikeMovie(node.name)
            SearchFilter.SERIES -> kind == MediaKind.VIDEO && looksLikeEpisode(node.name)
            SearchFilter.PHOTOS -> kind == MediaKind.IMAGE
            SearchFilter.MUSIC -> kind == MediaKind.AUDIO
            SearchFilter.LARGE -> !node.isDirectory
        }
    }

    companion object {
        private val episodePattern = Regex(
            """(?i)(s\d{1,2}[\s._-]?e\d{1,3}|\b\d{1,2}x\d{2,3}\b|episode[\s._-]?\d+|حلقة)""",
        )
        private val yearPattern = Regex("""(?i)[\[(]?(19|20)\d{2}[\])]?(?![\d])""")

        fun looksLikeEpisode(name: String): Boolean = episodePattern.containsMatchIn(name)

        /** A movie-ish name: has a year or a resolution tag, and no episode marker. */
        fun looksLikeMovie(name: String): Boolean {
            if (looksLikeEpisode(name)) return false
            val lower = name.lowercase()
            return yearPattern.containsMatchIn(name) ||
                listOf("1080p", "2160p", "720p", "4k", "bluray", "web-dl", "webrip", "hdtv")
                    .any { lower.contains(it) }
        }
    }
}

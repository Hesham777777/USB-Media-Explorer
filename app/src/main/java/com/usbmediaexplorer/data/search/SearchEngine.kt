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
    FOLDERS,
    FILES,
    RECENT,
    ;

    val minSizeBytes: Long
        get() = if (this == LARGE) 1L * 1024 * 1024 * 1024 else 0L

    /** "By date" filtering (spec §10): everything modified in the last week. */
    val modifiedAfterMillis: Long
        get() = if (this == RECENT) {
            System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        } else {
            0L
        }
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

    /**
     * A finished walk of one root, kept so the next keystroke filters memory instead of
     * re-reading the drive. Expired after [SNAPSHOT_TTL_MS]; a new root replaces it.
     */
    private data class Snapshot(val rootKey: String, val at: Long, val nodes: List<DocNode>)

    @Volatile
    private var snapshot: Snapshot? = null

    fun search(roots: List<DocNode>, query: SearchQuery): Flow<SearchResult> = flow {
        val settings: AppSettings = runCatching { settingsRepository.settings.first() }
            .getOrDefault(AppSettings.DEFAULT)
        val matches = ArrayList<DocNode>()
        var scanned = 0
        var truncated = false

        emit(SearchResult(emptyList(), 0, isRunning = true))

        val root = roots.firstOrNull()
        if (root == null) {
            emit(SearchResult(emptyList(), 0, isRunning = false))
            return@flow
        }

        val cached = snapshot?.takeIf {
            it.rootKey == root.key && System.currentTimeMillis() - it.at < SNAPSHOT_TTL_MS
        }
        if (cached != null) {
            // Every character after the first walk is pure in-memory filtering: no drive I/O,
            // no cancellation of an in-flight traversal, results in a single frame.
            for (node in cached.nodes) {
                currentCoroutineContext().ensureActive()
                scanned++
                if (!settings.showHiddenFiles && node.isHidden) continue
                if (matches(node, query, settings)) {
                    matches += node
                    if (matches.size >= query.maxResults) {
                        truncated = true
                        break
                    }
                }
            }
            emit(SearchResult(matches.toList(), scanned, isRunning = false, truncated = truncated))
            return@flow
        }

        // Cold walk: progressive as before, and everything seen is remembered for the next query.
        val walked = ArrayList<DocNode>()
        val stack = ArrayDeque<DocNode>()
        stack.addLast(root)
        var lastEmit = System.currentTimeMillis()

        while (stack.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            val node = stack.removeLast()
            if (!node.isDirectory) continue
            val children = runCatching { docRepository.children(node) }.getOrDefault(emptyList())
            for (child in children) {
                currentCoroutineContext().ensureActive()
                scanned++
                walked += child
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
        if (walked.isNotEmpty()) {
            snapshot = Snapshot(root.key, System.currentTimeMillis(), walked)
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
        val minModified = maxOf(query.modifiedAfter, query.filter.modifiedAfterMillis)
        if (minModified > 0 && node.lastModified < minModified) return false

        return when (query.filter) {
            SearchFilter.ALL -> true
            SearchFilter.VIDEOS -> kind == MediaKind.VIDEO
            SearchFilter.MOVIES -> kind == MediaKind.VIDEO && looksLikeMovie(node.name)
            SearchFilter.SERIES -> kind == MediaKind.VIDEO && looksLikeEpisode(node.name)
            SearchFilter.PHOTOS -> kind == MediaKind.IMAGE
            SearchFilter.MUSIC -> kind == MediaKind.AUDIO
            SearchFilter.LARGE -> !node.isDirectory
            SearchFilter.FOLDERS -> node.isDirectory
            SearchFilter.FILES -> !node.isDirectory
            // The date window was already applied above.
            SearchFilter.RECENT -> true
        }
    }

    companion object {
        /** How long a finished walk stays reusable for in-memory filtering. */
        const val SNAPSHOT_TTL_MS = 2 * 60_000L

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

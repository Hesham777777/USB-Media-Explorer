package com.usbmediaexplorer.data.thumb

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.doc.MediaKind
import com.usbmediaexplorer.data.metadata.MetadataRepository
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.data.settings.FolderPreviewStyle
import com.usbmediaexplorer.data.settings.SettingsRepository
import com.usbmediaexplorer.util.Bitmaps
import com.usbmediaexplorer.util.Power
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/**
 * The thumbnail pipeline (spec §3, §5, §7, §22, §26).
 *
 *  - memory/index-first: a repeated visit to a folder never re-decodes a video,
 *  - key = `uri|size|lastModified|geometry|quality|strategy`, so editing a file on the stick
 *    invalidates exactly that entry and nothing else,
 *  - generation is serialised per key and bounded globally, so 5 000 videos never freeze the UI,
 *  - the source file is opened read-only and never written to.
 */
class ThumbnailRepository(
    private val context: Context,
    private val docRepository: DocRepository,
    private val cache: ThumbnailCache,
    private val videoExtractor: VideoFrameExtractor,
    private val imageExtractor: ImageThumbExtractor,
    private val metadataRepository: MetadataRepository,
    private val settingsRepository: SettingsRepository,
    private val scope: CoroutineScope,
) {

    @Volatile
    private var settings: AppSettings = AppSettings.DEFAULT

    /** Guards generation per cache key so two cards requesting the same file share one decode. */
    private val generationLocks = HashMap<String, Mutex>()
    private val writeCounter = AtomicInteger()

    init {
        scope.launch {
            settingsRepository.settings.collect { settings = it }
        }
    }

    suspend fun currentSettings(): AppSettings =
        if (settings == AppSettings.DEFAULT) settingsRepository.settings.first() else settings

    /** Builds the request a card should use for the current settings. */
    suspend fun requestFor(
        node: DocNode,
        widthPx: Int,
        heightPx: Int,
        poster: Boolean = false,
    ): ThumbRequest {
        val s = currentSettings()
        return ThumbRequest(
            node = node,
            widthPx = widthPx,
            heightPx = heightPx,
            quality = s.thumbQuality,
            strategy = s.frameStrategy,
            folderPreview = s.folderPreviewsEnabled && node.isDirectory,
            folderPreviewCount = s.folderPreviewMaxChildren,
            preferEmbeddedCover = s.preferEmbeddedCover || (poster && s.posterCoversFirst),
            poster = poster,
            folderStyle = s.folderPreviewStyle,
        )
    }

    /**
     * Returns encoded thumbnail bytes, or null when no preview can be produced (the caller then
     * shows a typed icon — spec §25).
     */
    suspend fun thumbnail(request: ThumbRequest): ByteArray? {
        val s = currentSettings()
        val kind = request.node.kind
        if (!isPreviewAllowed(kind, s)) return null
        if (s.generateWhileChargingOnly && !Power.isCharging(context)) return null

        cache.fileFor(request.cacheKey)?.let { file ->
            runCatching { file.readBytes() }.getOrNull()?.let { return it }
        }

        val lock = lockFor(request.cacheKey)
        return lock.withLock {
            // Another coroutine may have generated it while we waited for the lock.
            cache.fileFor(request.cacheKey)?.let { file ->
                runCatching { file.readBytes() }.getOrNull()
            } ?: generate(request, kind)?.also { bytes ->
                cache.put(request.cacheKey, request.nodeKey, bytes)
                cache.schedulePersist()
                maybePrune(s.cacheLimitBytes)
            }
        }
    }

    suspend fun bitmap(request: ThumbRequest): Bitmap? {
        val bytes = thumbnail(request) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private suspend fun generate(request: ThumbRequest, kind: MediaKind): ByteArray? {
        coroutineContext.ensureActive()
        return when {
            request.node.isDirectory && request.folderPreview -> composeFolderPreview(request)
            kind == MediaKind.VIDEO -> {
                val duration = metadataRepository.peek(request.node)?.durationMs ?: 0L
                videoExtractor.extract(request, duration)
            }

            kind == MediaKind.IMAGE -> imageExtractor.extract(request)
            else -> null
        }
    }

    /**
     * Folder preview (spec §7): composed from the folder's *own* media — either a 2×2 mosaic or a
     * Windows-style folder whose pocket is filled with those previews. Cached like any other
     * thumbnail and switchable from settings.
     */
    private suspend fun composeFolderPreview(request: ThumbRequest): ByteArray? {
        val windows = request.folderStyle == FolderPreviewStyle.WINDOWS
        val children = docRepository.mediaChildren(request.node, request.folderPreviewCount)
        // A folder with nothing previewable inside still gets the folder shape in Windows style,
        // so the grid looks consistent instead of mixing folders and icons.
        if (children.isEmpty()) return if (windows) emptyWindowsFolder(request) else null
        val cell = (request.widthPx / 2).coerceAtLeast(64)
        val parts = children.take(request.folderPreviewCount).map { child ->
            coroutineContext.ensureActive()
            val sub = request.copy(
                node = child,
                widthPx = cell * 2,
                heightPx = cell * 2,
                folderPreview = false,
                poster = false,
            )
            bitmap(sub)
        }
        if (parts.all { it == null }) {
            return if (windows) emptyWindowsFolder(request) else null
        }
        val grid = if (windows) {
            Bitmaps.composeFolderWindows(
                parts = parts,
                width = request.widthPx,
                height = request.heightPx,
            )
        } else {
            Bitmaps.composeGrid(
                parts = parts,
                width = request.widthPx,
                height = request.heightPx,
                background = 0xFF101418.toInt(),
            )
        }
        return runCatching { Bitmaps.encode(grid, request.quality) }.also {
            parts.forEach { bmp -> bmp?.takeIf { !bmp.isRecycled }?.recycle() }
            if (!grid.isRecycled) grid.recycle()
        }.getOrNull()
    }

    /** Windows-style folder artwork with an empty pocket. */
    private fun emptyWindowsFolder(request: ThumbRequest): ByteArray? {
        val folder = Bitmaps.composeFolderWindows(emptyList(), request.widthPx, request.heightPx)
        return runCatching { Bitmaps.encode(folder, request.quality) }.also {
            if (!folder.isRecycled) folder.recycle()
        }.getOrNull()
    }

    private fun isPreviewAllowed(kind: MediaKind, s: AppSettings): Boolean = when (kind) {
        MediaKind.VIDEO -> s.videoThumbnailsEnabled
        MediaKind.IMAGE -> s.imageThumbnailsEnabled
        MediaKind.DIRECTORY -> s.folderPreviewsEnabled
        else -> false
    }

    private fun lockFor(key: String): Mutex = synchronized(generationLocks) {
        // Keep the lock table bounded: entries for keys nobody is generating any more are dropped.
        if (generationLocks.size > 256) {
            generationLocks.entries.removeAll { !it.value.isLocked }
        }
        generationLocks.getOrPut(key) { Mutex() }
    }

    private suspend fun maybePrune(limitBytes: Long) {
        if (writeCounter.incrementAndGet() % PRUNE_EVERY != 0) return
        val used = cache.sizeBytes()
        if (used > limitBytes) cache.pruneTo(limitBytes)
    }

    // ---- cache management surfaced in Settings (spec §5) -----------------

    suspend fun cacheSizeBytes(): Long = cache.sizeBytes()

    suspend fun cacheEntryCount(): Int = cache.count()

    suspend fun clearCache(): Int {
        val removed = cache.clear()
        cache.flush()
        return removed
    }

    suspend fun enforceLimit(limitBytes: Long): Long = cache.pruneTo(limitBytes)

    /** Deletes cache rows whose source file no longer exists on the drive. */
    suspend fun cleanOrphans(): Int {
        val removed = cache.cleanOrphans { uriString ->
            runCatching { docRepository.exists(Uri.parse(uriString)) }.getOrDefault(false)
        }
        cache.flush()
        return removed
    }

    /** Called right after a file is deleted or renamed on the drive. */
    suspend fun invalidate(node: DocNode) {
        cache.removeForNode(node.key)
        cache.removeForNode(node.stableKey)
        cache.schedulePersist()
        metadataRepository.invalidate(node)
    }

    private companion object {
        const val PRUNE_EVERY = 24
    }
}

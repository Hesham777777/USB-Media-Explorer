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
import com.usbmediaexplorer.data.settings.SettingsRepository
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
 * Three independent generators, one cache:
 *  - [VideoFrameExtractor] → a real frame from inside the video,
 *  - [ImageThumbExtractor] → the picture itself,
 *  - [FolderCoverExtractor] → the poster image found inside a folder (Folder Cover).
 *
 *  - memory/index-first: a repeated visit to a folder never re-decodes a video,
 *  - key = `uri|size|lastModified|geometry|quality|strategy|mode`, so editing a file on the stick
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
    private val audioArtExtractor: AudioArtExtractor,
    private val folderCoverExtractor: FolderCoverExtractor,
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
    suspend fun requestFor(node: DocNode, widthPx: Int, heightPx: Int): ThumbRequest {
        val s = currentSettings()
        return ThumbRequest(
            node = node,
            widthPx = widthPx,
            heightPx = heightPx,
            quality = s.thumbQuality,
            strategy = s.frameStrategy,
            folderCover = s.folderCoversEnabled && node.isDirectory,
            coverScanLimit = s.folderCoverScanLimit,
            preferEmbeddedCover = s.preferEmbeddedCover,
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

        when (val hit = readCache(request.cacheKey)) {
            is CacheHit.Bytes -> return hit.bytes
            CacheHit.NoCover -> return null
            null -> Unit
        }

        val lock = lockFor(request.cacheKey)
        return lock.withLock {
            // Another coroutine may have generated it while we waited for the lock.
            when (val hit = readCache(request.cacheKey)) {
                is CacheHit.Bytes -> return@withLock hit.bytes
                CacheHit.NoCover -> return@withLock null
                null -> Unit
            }

            val bytes = generate(request, kind)
            if (bytes != null) {
                cache.put(request.cacheKey, request.nodeKey, bytes, request.cacheKind)
                cache.schedulePersist()
                maybePrune(s.cacheLimitBytes)
                return@withLock bytes
            }
            if (request.folderCover || kind == MediaKind.AUDIO) {
                // Negative cache. A folder with no image inside — and a track with no embedded
                // art — must not be rescanned on every scroll pass or every return to the
                // screen; that is what keeps navigation smooth on a slow USB stick.
                cache.put(request.cacheKey, request.nodeKey, NO_COVER, request.cacheKind)
                cache.schedulePersist()
            }
            null
        }
    }

    /** What the disk cache holds for one key. */
    private sealed interface CacheHit {
        data class Bytes(val bytes: ByteArray) : CacheHit

        /** "This folder was scanned and holds no cover image" — see [NO_COVER]. */
        data object NoCover : CacheHit
    }

    /** `null` = not cached yet; [CacheHit.NoCover] = cached as a folder without any cover. */
    private suspend fun readCache(key: String): CacheHit? = cache.fileFor(key)?.let { file ->
        if (file.length() == 0L) {
            CacheHit.NoCover
        } else {
            runCatching { file.readBytes() }.getOrNull()?.let { CacheHit.Bytes(it) }
        }
    }

    suspend fun bitmap(request: ThumbRequest): Bitmap? {
        val bytes = thumbnail(request) ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private suspend fun generate(request: ThumbRequest, kind: MediaKind): ByteArray? {
        coroutineContext.ensureActive()
        return when {
            // Folder Cover: a poster image that lives inside the folder. Never a drawing of a
            // folder, never a mosaic of its contents, never a frame of one of its videos.
            request.node.isDirectory && request.folderCover -> folderCoverExtractor.extract(request)
            kind == MediaKind.VIDEO -> {
                val duration = metadataRepository.peek(request.node)?.durationMs ?: 0L
                videoExtractor.extract(request, duration)
            }

            kind == MediaKind.IMAGE -> imageExtractor.extract(request)

            // Album art that lives inside the file itself (ID3 APIC / covr / FLAC picture block).
            kind == MediaKind.AUDIO -> audioArtExtractor.extract(request)

            else -> null
        }
    }

    private fun isPreviewAllowed(kind: MediaKind, s: AppSettings): Boolean = when (kind) {
        MediaKind.VIDEO -> s.videoThumbnailsEnabled
        MediaKind.IMAGE -> s.imageThumbnailsEnabled
        MediaKind.AUDIO -> s.audioArtEnabled
        MediaKind.DIRECTORY -> s.folderCoversEnabled
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

    /** Per-kind cache statistics: video frames, image previews, folder covers, other. */
    suspend fun cacheStatsByKind(): Map<String, ThumbnailCache.KindStat> = cache.statsByKind()

    /** Clears one kind only and returns how many entries were dropped. */
    suspend fun clearCacheKind(kind: String): Int {
        val removed = cache.clearKind(kind)
        cache.schedulePersist()
        return removed
    }

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
        invalidateParentCover(node)
        cache.schedulePersist()
        metadataRepository.invalidate(node)
    }

    /**
     * A folder's cover is derived from its children, so deleting, renaming, moving or replacing a
     * file inside it must drop the parent's cached cover too — the next visit picks the new poster.
     *
     * A cover may also be inherited from a sub-folder (`Series/Season 1/folder.jpg` covers
     * `Series`), so the climb goes as deep as the cover search itself.
     */
    private suspend fun invalidateParentCover(node: DocNode) {
        var current = runCatching { docRepository.parentOf(node) }.getOrNull()
        var levels = FolderCoverExtractor.MAX_DEPTH
        while (levels > 0) {
            val parent = current ?: return
            cache.removeForNode(parent.key)
            cache.removeForNode(parent.stableKey)
            current = runCatching { docRepository.parentOf(parent) }.getOrNull()
            levels--
        }
    }

    private companion object {
        const val PRUNE_EVERY = 24

        /** Zero-length marker: "this folder was scanned and has no cover image". */
        val NO_COVER = ByteArray(0)
    }
}

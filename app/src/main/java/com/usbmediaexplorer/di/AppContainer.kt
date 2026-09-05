package com.usbmediaexplorer.di

import android.content.Context
import coil.ImageLoader
import com.usbmediaexplorer.data.doc.DocRepository
import com.usbmediaexplorer.data.metadata.MediaMetadataReader
import com.usbmediaexplorer.data.metadata.MetadataRepository
import com.usbmediaexplorer.data.metadata.MetadataStore
import com.usbmediaexplorer.data.ops.FileOpsEngine
import com.usbmediaexplorer.data.ops.FileOpsManager
import com.usbmediaexplorer.data.ops.OpsNotifications
import com.usbmediaexplorer.data.search.SearchEngine
import com.usbmediaexplorer.data.settings.SettingsRepository
import com.usbmediaexplorer.data.store.FavoritesStore
import com.usbmediaexplorer.data.store.FolderPrefsStore
import com.usbmediaexplorer.data.store.PlaybackPositionStore
import com.usbmediaexplorer.data.store.RecentStore
import com.usbmediaexplorer.data.thumb.CoilSetup
import com.usbmediaexplorer.data.thumb.FolderCoverExtractor
import com.usbmediaexplorer.data.thumb.ImageThumbExtractor
import com.usbmediaexplorer.data.thumb.ThumbnailCache
import com.usbmediaexplorer.data.thumb.ThumbnailRepository
import com.usbmediaexplorer.data.thumb.VideoFrameExtractor
import com.usbmediaexplorer.data.volume.VolumeMonitor
import com.usbmediaexplorer.data.volume.VolumeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Hand-rolled dependency graph.
 *
 * The app deliberately avoids an annotation-processor based DI framework: everything is a
 * singleton created once per process, wired here in dependency order, which keeps the build
 * fast and the object graph easy to follow.
 */
class AppContainer(private val context: Context) {

    /** Needed by view models that build user-visible strings. */
    val appContext: Context get() = context

    /** Application-wide scope for repositories, monitors and background jobs. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Settings & small stores -------------------------------------------
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(context) }
    val favoritesStore: FavoritesStore by lazy { FavoritesStore(context) }
    val recentStore: RecentStore by lazy { RecentStore(context) }
    val playbackPositionStore: PlaybackPositionStore by lazy { PlaybackPositionStore(context) }
    val folderPrefsStore: FolderPrefsStore by lazy { FolderPrefsStore(context) }

    // Storage ------------------------------------------------------------
    val volumeMonitor: VolumeMonitor by lazy { VolumeMonitor(context) }
    val volumeRepository: VolumeRepository by lazy {
        VolumeRepository(context, volumeMonitor, appScope)
    }
    val docRepository: DocRepository by lazy { DocRepository(context, volumeRepository) }

    // Metadata -----------------------------------------------------------
    val metadataStore: MetadataStore by lazy { MetadataStore(context) }
    val mediaMetadataReader: MediaMetadataReader by lazy {
        MediaMetadataReader(context, docRepository)
    }
    val metadataRepository: MetadataRepository by lazy {
        MetadataRepository(mediaMetadataReader, metadataStore, appScope)
    }

    // Thumbnails ---------------------------------------------------------
    val thumbnailCache: ThumbnailCache by lazy { ThumbnailCache(context, appScope) }
    val videoFrameExtractor: VideoFrameExtractor by lazy {
        VideoFrameExtractor(context, docRepository)
    }
    val imageThumbExtractor: ImageThumbExtractor by lazy {
        ImageThumbExtractor(context, docRepository)
    }

    /** Album art embedded in audio files — read from the track itself, never downloaded. */
    val audioArtExtractor: AudioArtExtractor by lazy {
        AudioArtExtractor(context, docRepository)
    }

    /** Folder Cover: which image inside a folder represents that folder (poster priority). */
    val folderCoverExtractor: FolderCoverExtractor by lazy {
        FolderCoverExtractor(docRepository, imageThumbExtractor)
    }
    val thumbnailRepository: ThumbnailRepository by lazy {
        ThumbnailRepository(
            context = context,
            docRepository = docRepository,
            cache = thumbnailCache,
            videoExtractor = videoFrameExtractor,
            imageExtractor = imageThumbExtractor,
            audioArtExtractor = audioArtExtractor,
            folderCoverExtractor = folderCoverExtractor,
            metadataRepository = metadataRepository,
            settingsRepository = settingsRepository,
            scope = appScope,
        )
    }

    // Operations & search -------------------------------------------------
    val fileOpsEngine: FileOpsEngine by lazy {
        FileOpsEngine(context, docRepository, thumbnailRepository, metadataRepository)
    }
    val fileOpsManager: FileOpsManager by lazy {
        FileOpsManager(context, fileOpsEngine, docRepository, appScope)
    }
    val searchEngine: SearchEngine by lazy { SearchEngine(docRepository, settingsRepository) }

    // Images --------------------------------------------------------------
    val imageLoader: ImageLoader by lazy { CoilSetup.imageLoader(context, thumbnailRepository) }

    /** Called once from [com.usbmediaexplorer.UsbMediaExplorerApp.onCreate]. */
    fun onAppStart() {
        OpsNotifications.ensureChannel(context)
        volumeMonitor.start()
        // Touch the repositories that must be alive before any screen opens.
        appScope.launch {
            volumeRepository.volumes
            settingsRepository.settings.collect { settings ->
                // Keep the thumbnail cache within the user's configured budget.
                thumbnailRepository.enforceLimit(settings.cacheLimitBytes)
            }
        }
    }

    /** Called when the whole process is going away. */
    fun onAppTerminate() {
        appScope.launch { thumbnailCache.flush() }
        volumeMonitor.stop()
    }
}

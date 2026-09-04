package com.usbmediaexplorer.data.thumb

import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.FetchResult
import coil.decode.Fetcher
import coil.decode.ImageDecoderDecoder
import coil.decode.SourceResult
import coil.decode.VideoFrameDecoder
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.Options
import com.usbmediaexplorer.util.AppDispatchers
import okio.Buffer
import java.util.concurrent.Executors

/**
 * Bridges the thumbnail pipeline into Coil.
 *
 * Coil gives the grid everything the spec asks for free: lazy loading tied to composition,
 * request cancellation when a card scrolls off screen, a bounded memory cache and view
 * recycling. We only plug in the *source* of the bytes (our SAF-aware extractor + disk cache)
 * and the *cache key*, and disable Coil's own disk cache so nothing is stored twice.
 */
object CoilSetup {

    fun imageLoader(context: Context, repository: ThumbnailRepository): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(ThumbKeyer())
                add(ThumbFetcherFactory(repository))
                // Plain Uri models (image viewer) keep the standard decoders, including
                // animated GIF/WebP and video frames.
                add(ImageDecoderDecoder.Factory(enforceMinimumFrameDelay = true))
                add(VideoFrameDecoder.Factory())
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(if (isLowRam(context)) 0.10 else 0.20)
                    .build()
            }
            .diskCache(null)
            .crossfade(false)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            .fetcherDispatcher(Executors.newFixedThreadPool(FETCH_THREADS))
            .decoderDispatcher(AppDispatchers.default.asExecutor())
            .build()

    private fun isLowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager)
            ?.isLowRamDevice == true

    private const val FETCH_THREADS = 3
}

/** Cache key: identical requests share memory/disk entries. */
class ThumbKeyer : Keyer<ThumbRequest> {
    override fun key(data: ThumbRequest, options: Options): String = data.cacheKey
}

class ThumbFetcher(
    private val repository: ThumbnailRepository,
    private val request: ThumbRequest,
) : Fetcher {

    override suspend fun fetch(): FetchResult? {
        val bytes = repository.thumbnail(request) ?: return null
        val buffer = Buffer().apply { write(bytes) }
        return SourceResult(
            source = buffer,
            mimeType = "image/webp",
            dataSource = DataSource.DISK,
        )
    }
}

class ThumbFetcherFactory(private val repository: ThumbnailRepository) : Fetcher.Factory<ThumbRequest> {
    override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
        ThumbFetcher(repository, data)
}

private fun kotlinx.coroutines.CoroutineDispatcher.asExecutor(): java.util.concurrent.ExecutorService =
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "coil-decoder").apply { priority = Thread.NORM_PRIORITY - 1 }
    }

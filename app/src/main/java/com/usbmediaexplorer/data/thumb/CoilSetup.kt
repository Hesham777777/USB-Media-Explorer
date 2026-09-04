package com.usbmediaexplorer.data.thumb

import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageDecoderDecoder
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.key.Keyer
import coil.memory.MemoryCache
import coil.request.Options
import com.usbmediaexplorer.util.AppDispatchers
import okio.Buffer

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
                // Animated GIF/WebP for plain Uri models (the image viewer).
                add(ImageDecoderDecoder.Factory(enforceMinimumFrameDelay = true))
            }
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(if (isLowRam(context)) 0.10 else 0.20)
                    .build()
            }
            // Our ThumbnailCache owns the disk; a second cache would double every byte written.
            .diskCache(null)
            .crossfade(false)
            .respectCacheHeaders(false)
            .allowRgb565(true)
            // Frame extraction is I/O bound on the drive itself — keep the parallelism small so a
            // slow USB stick is never hammered by a dozen concurrent readers.
            .fetcherDispatcher(AppDispatchers.thumbnail)
            .decoderDispatcher(AppDispatchers.default)
            .build()

    private fun isLowRam(context: Context): Boolean =
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)
            ?.isLowRamDevice == true
}

/** Cache key: identical requests share memory/disk entries. */
class ThumbKeyer : Keyer<ThumbRequest> {
    override fun key(data: ThumbRequest, options: Options): String = data.cacheKey
}

/** Turns a [ThumbRequest] into encoded image bytes produced by our own extractors. */
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

class ThumbFetcherFactory(private val repository: ThumbnailRepository) :
    Fetcher.Factory<ThumbRequest> {

    override fun create(data: ThumbRequest, options: Options, imageLoader: ImageLoader): Fetcher =
        ThumbFetcher(repository, data)
}

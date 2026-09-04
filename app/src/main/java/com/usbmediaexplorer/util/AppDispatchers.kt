package com.usbmediaexplorer.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Single place to swap dispatchers in tests. Thumbnail decoding and every USB read must run
 * off the main thread, and heavy work is funneled through [thumbnail] so a slow flash drive
 * never saturates the shared IO pool.
 */
object AppDispatchers {
    val io: CoroutineDispatcher = Dispatchers.IO
    val default: CoroutineDispatcher = Dispatchers.Default
    val main: CoroutineDispatcher = Dispatchers.Main
    val thumbnail: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(3)
}

/** Limited, bounded dispatcher used for USB metadata probes. */
val metadataDispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(2)

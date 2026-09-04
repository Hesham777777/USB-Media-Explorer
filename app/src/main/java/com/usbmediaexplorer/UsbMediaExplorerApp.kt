package com.usbmediaexplorer

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.usbmediaexplorer.di.AppContainer

class UsbMediaExplorerApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        container.onAppStart()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Coil trims its own memory cache; nothing else here holds bitmaps long term.
    }

    override fun onTerminate() {
        container.onAppTerminate()
        super.onTerminate()
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader
}

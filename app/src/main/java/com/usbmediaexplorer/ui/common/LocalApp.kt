package com.usbmediaexplorer.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.di.AppContainer

/** Provides the application dependency graph to the composition. */
val LocalAppContainer = staticCompositionLocalOf<AppContainer> {
    error("AppContainer not provided — wrap your tree in CompositionLocalProvider")
}

/** Latest user settings; every screen reads this instead of collecting the DataStore itself. */
val LocalSettings = staticCompositionLocalOf { AppSettings.DEFAULT }

/** Tiny factory helper so screens can build their ViewModel with explicit dependencies. */
fun <VM : ViewModel> viewModelFactory(creator: () -> VM): ViewModelProvider.Factory =
    object : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = creator() as T
    }

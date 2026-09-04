package com.usbmediaexplorer

import android.content.Intent
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.data.settings.LanguageMode
import com.usbmediaexplorer.data.volume.VolumeEventBus
import com.usbmediaexplorer.data.volume.VolumeEvent
import com.usbmediaexplorer.ui.AppRoot
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.common.LocalSettings
import com.usbmediaexplorer.ui.nav.Routes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Single-activity host.
 *
 * Extends [AppCompatActivity] on purpose: `AppCompatDelegate.setApplicationLocales` only applies
 * below API 33 when the activity is AppCompat-hosted, and in-app language switching (spec §21)
 * has to work on the old USB-capable phones this app targets.
 *
 * Also the entry point for the two intents that matter for a USB explorer:
 *  - `ACTION_VIEW` on `video/*`, so the built-in player can be used from any other app,
 *  - `USB_DEVICE_ATTACHED`, so plugging a stick in can drop the user straight onto the drive.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_OPEN_TRANSFERS = "open_transfers"
    }

    private val pendingRoute = MutableStateFlow<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val container = (application as UsbMediaExplorerApp).container

        setContent {
            CompositionLocalProvider(LocalAppContainer provides container) {
                val settings by container.settingsRepository.settings
                    .collectAsStateWithLifecycle(AppSettings.DEFAULT)

                LaunchedEffect(settings.languageMode) { applyLanguage(settings.languageMode) }

                CompositionLocalProvider(LocalSettings provides settings) {
                    AppRoot(pendingRoute = pendingRoute)
                }
            }
        }

        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                val uri = intent.data ?: return
                val type = intent.type ?: contentResolver.getType(uri).orEmpty()
                pendingRoute.value = if (type.startsWith("image/")) {
                    Routes.image(uri, null)
                } else {
                    Routes.player(uri, null)
                }
            }

            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                VolumeEventBus.publish(VolumeEvent.Attached(null))
                pendingRoute.value = Routes.HOME
            }

            Intent.ACTION_MAIN -> {
                if (intent.getBooleanExtra(EXTRA_OPEN_TRANSFERS, false)) {
                    pendingRoute.value = Routes.TRANSFERS
                }
            }
        }
    }

    /** Per-app language (Android 13+ system setting, AppCompat fallback below). */
    private fun applyLanguage(mode: LanguageMode) {
        val tags = mode.tag ?: return
        val current = AppCompatDelegate.getApplicationLocales()
        if (current.toLanguageTags() == tags) return
        runCatching { AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tags)) }
    }

    override fun onStop() {
        super.onStop()
        // Flush the thumbnail index so a killed process does not lose the cache map.
        runCatching {
            val container = (application as? UsbMediaExplorerApp)?.container ?: return@runCatching
            container.appScope.launch { container.thumbnailCache.flush() }
        }
    }

    /** Convenience for screens that need to open a raw URI (e.g. subtitle picker). */
    fun openUri(uri: Uri) {
        pendingRoute.value = Routes.player(uri, null)
    }
}

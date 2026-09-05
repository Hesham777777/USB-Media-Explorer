package com.usbmediaexplorer.ui.nav

import android.net.Uri
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.navigation.NavHostController

object Routes {
    const val HOME = "home"
    const val BROWSE = "browse"
    const val PLAYER = "player"
    const val IMAGE = "image"
    const val SEARCH = "search"
    const val FAVORITES = "favorites"
    const val RECENT = "recent"
    const val SETTINGS = "settings"
    const val TRANSFERS = "transfers"

    const val ARG_URI = "uri"
    const val ARG_FOLDER = "folder"

    /** Set when the browser should open a folder with every item already selected (spec §8). */
    const val ARG_SELECT = "select"

    fun browse(uri: Uri, selectAll: Boolean = false): String = buildString {
        append("$BROWSE?$ARG_URI=${Uri.encode(uri.toString())}")
        if (selectAll) append("&$ARG_SELECT=true")
    }

    fun player(uri: Uri, folderUri: Uri?): String = buildString {
        append("$PLAYER?$ARG_URI=${Uri.encode(uri.toString())}")
        if (folderUri != null) append("&$ARG_FOLDER=${Uri.encode(folderUri.toString())}")
    }

    fun image(uri: Uri, folderUri: Uri?): String = buildString {
        append("$IMAGE?$ARG_URI=${Uri.encode(uri.toString())}")
        if (folderUri != null) append("&$ARG_FOLDER=${Uri.encode(folderUri.toString())}")
    }

    fun search(rootUri: Uri): String = "$SEARCH?$ARG_URI=${Uri.encode(rootUri.toString())}"

    fun browseRoute(): String = "$BROWSE?$ARG_URI={$ARG_URI}&$ARG_SELECT={$ARG_SELECT}"
    fun playerRoute(): String = "$PLAYER?$ARG_URI={$ARG_URI}&$ARG_FOLDER={$ARG_FOLDER}"
    fun imageRoute(): String = "$IMAGE?$ARG_URI={$ARG_URI}&$ARG_FOLDER={$ARG_FOLDER}"
    fun searchRoute(): String = "$SEARCH?$ARG_URI={$ARG_URI}"
}

/** Thin, type-safe wrapper over [NavHostController] shared by every screen. */
class AppNavigator(private val navController: NavHostController) {

    fun openVolume(uri: Uri) = navController.navigate(Routes.browse(uri))

    fun openFolder(uri: Uri) = navController.navigate(Routes.browse(uri))

    /** Opens a folder and selects its content — the "select content" context action. */
    fun openFolderSelecting(uri: Uri) = navController.navigate(Routes.browse(uri, selectAll = true))

    fun playVideo(uri: Uri, folderUri: Uri? = null) = navController.navigate(Routes.player(uri, folderUri))

    fun viewImage(uri: Uri, folderUri: Uri? = null) = navController.navigate(Routes.image(uri, folderUri))

    fun search(rootUri: Uri) = navController.navigate(Routes.search(rootUri))

    fun favorites() = navController.navigate(Routes.FAVORITES)

    fun recent() = navController.navigate(Routes.RECENT)

    fun settings() = navController.navigate(Routes.SETTINGS)

    fun transfers() = navController.navigate(Routes.TRANSFERS)

    fun back(): Boolean = runCatching { navController.popBackStack() }.getOrDefault(false)

    fun root(): Boolean = runCatching {
        navController.popBackStack(Routes.HOME, inclusive = false)
    }.getOrDefault(false)
}

val LocalNavigator = staticCompositionLocalOf<AppNavigator> {
    error("AppNavigator not provided")
}

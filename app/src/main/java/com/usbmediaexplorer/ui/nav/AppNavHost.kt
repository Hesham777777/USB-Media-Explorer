package com.usbmediaexplorer.ui.nav

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.usbmediaexplorer.ui.home.HomeScreen
import com.usbmediaexplorer.ui.library.FavoritesScreen
import com.usbmediaexplorer.ui.library.RecentScreen
import com.usbmediaexplorer.ui.browse.BrowseScreen
import com.usbmediaexplorer.ui.ops.TransfersScreen
import com.usbmediaexplorer.ui.player.PlayerScreen
import com.usbmediaexplorer.ui.search.SearchScreen
import com.usbmediaexplorer.ui.settings.SettingsScreen
import com.usbmediaexplorer.ui.viewer.ImageViewerScreen

@Composable
fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    snackbarHostState: SnackbarHostState,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier,
    ) {
        composable(Routes.HOME) {
            HomeScreen(snackbarHostState = snackbarHostState)
        }

        composable(
            route = Routes.browseRoute(),
            arguments = listOf(
                navArgument(Routes.ARG_URI) {
                    type = NavType.StringType
                    defaultValue = ""
                },
            ),
        ) { entry ->
            BrowseScreen(
                uri = entry.arguments?.getString(Routes.ARG_URI).orEmpty(),
                snackbarHostState = snackbarHostState,
            )
        }

        composable(
            route = Routes.playerRoute(),
            arguments = listOf(
                navArgument(Routes.ARG_URI) { type = NavType.StringType; defaultValue = "" },
                navArgument(Routes.ARG_FOLDER) {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
            ),
        ) { entry ->
            PlayerScreen(
                uri = entry.arguments?.getString(Routes.ARG_URI).orEmpty(),
                folderUri = entry.arguments?.getString(Routes.ARG_FOLDER).orEmpty(),
            )
        }

        composable(
            route = Routes.imageRoute(),
            arguments = listOf(
                navArgument(Routes.ARG_URI) { type = NavType.StringType; defaultValue = "" },
                navArgument(Routes.ARG_FOLDER) {
                    type = NavType.StringType
                    defaultValue = ""
                    nullable = true
                },
            ),
        ) { entry ->
            ImageViewerScreen(
                uri = entry.arguments?.getString(Routes.ARG_URI).orEmpty(),
                folderUri = entry.arguments?.getString(Routes.ARG_FOLDER).orEmpty(),
            )
        }

        composable(
            route = Routes.searchRoute(),
            arguments = listOf(
                navArgument(Routes.ARG_URI) { type = NavType.StringType; defaultValue = "" },
            ),
        ) { entry ->
            SearchScreen(
                rootUri = entry.arguments?.getString(Routes.ARG_URI).orEmpty(),
                snackbarHostState = snackbarHostState,
            )
        }

        composable(Routes.FAVORITES) {
            FavoritesScreen(snackbarHostState = snackbarHostState)
        }

        composable(Routes.RECENT) {
            RecentScreen()
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(snackbarHostState = snackbarHostState)
        }

        composable(Routes.TRANSFERS) {
            TransfersScreen()
        }
    }
}

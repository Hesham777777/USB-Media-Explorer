package com.usbmediaexplorer.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.usbmediaexplorer.data.ops.JobState
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.AppNavHost
import com.usbmediaexplorer.ui.nav.AppNavigator
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.nav.Routes
import com.usbmediaexplorer.ui.ops.TransferBar
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Application shell: navigation host, snackbar host and the global transfer bar.
 *
 * The [Scaffold] is *always* present — swapping between two different trees when the player
 * opens would destroy the NavHost and lose the whole back stack, so only its contents change.
 */
@Composable
fun AppRoot(pendingRoute: MutableStateFlow<String?>) {
    val navController = rememberNavController()
    val navigator = remember(navController) { AppNavigator(navController) }
    val snackbarHostState = remember { SnackbarHostState() }
    val container = LocalAppContainer.current

    val activeJob by container.fileOpsManager.activeJob.collectAsStateWithLifecycle()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val pending by pendingRoute.collectAsState()

    LaunchedEffect(pending) {
        val route = pending
        if (!route.isNullOrEmpty()) {
            navController.navigate(route)
            pendingRoute.value = null
        }
    }

    val immersive = currentRoute?.startsWith(Routes.PLAYER) == true

    CompositionLocalProvider(LocalNavigator provides navigator) {
        Scaffold(
            snackbarHost = { if (!immersive) SnackbarHost(snackbarHostState) },
            contentWindowInsets = if (immersive) {
                WindowInsets(0, 0, 0, 0)
            } else {
                androidx.compose.material3.ScaffoldDefaults.contentWindowInsets
            },
            bottomBar = {
                val job = activeJob
                if (!immersive && job != null && job.state != JobState.DONE) {
                    TransferBar(
                        job = job,
                        onClick = { navigator.transfers() },
                        onPause = { container.fileOpsManager.pause(job.jobId) },
                        onResume = { container.fileOpsManager.resume(job.jobId) },
                        onCancel = { container.fileOpsManager.cancel(job.jobId) },
                    )
                }
            },
        ) { padding ->
            val contentPadding = if (immersive) PaddingValues(0.dp) else padding
            AppNavHost(
                navController = navController,
                snackbarHostState = snackbarHostState,
                modifier = Modifier.padding(contentPadding),
            )
        }
    }
}

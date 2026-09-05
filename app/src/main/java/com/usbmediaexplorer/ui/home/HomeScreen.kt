package com.usbmediaexplorer.ui.home

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.settings.FOLDER_COVER_ASPECT
import com.usbmediaexplorer.data.store.RecentEntry
import com.usbmediaexplorer.data.store.asDocNode
import com.usbmediaexplorer.data.volume.GrantKind
import com.usbmediaexplorer.data.volume.VolumeInfo
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.Permissions

/** Home: volumes + quick access + recently watched (spec §1, §18). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val viewModel: HomeViewModel = viewModel(factory = viewModelFactory { HomeViewModel(container) })
    val state by viewModel.state.collectAsStateWithLifecycle()

    val treePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri -> viewModel.onTreeGranted(uri) }

    val intentPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result -> viewModel.onTreeGranted(result.data?.data) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        // Re-read the real state instead of trusting the result map: on Android 14 the user may
        // choose partial access, and the map alone does not tell media access from audio-only.
        val granted = Permissions.hasMediaAccess(context)
        val activity = context as? Activity
        viewModel.onMediaPermissionResult(
            granted = granted,
            blocked = !granted && activity != null && Permissions.permanentlyDenied(activity),
        )
    }

    val syncPermissionState = {
        val granted = Permissions.hasMediaAccess(context)
        viewModel.setNeedsMediaPermission(!granted)
        // Coming back from the system settings: a grant there must clear the blocked flag.
        if (granted) viewModel.setMediaPermissionBlocked(false)
        viewModel.refresh()
    }

    /**
     * Asks for the media permission, or opens the app settings when Android no longer shows the
     * dialog — otherwise the button would silently do nothing, which reads as a broken grant flow.
     */
    fun requestMediaPermission() {
        val activity = context as? Activity
        if (state.mediaPermissionBlocked && activity != null) {
            runCatching { context.startActivity(Permissions.appSettingsIntent(context.packageName)) }
                .onFailure { permissionLauncher.launch(Permissions.runtimePermissions(context)) }
        } else {
            permissionLauncher.launch(Permissions.runtimePermissions(context))
        }
    }

    LaunchedEffect(Unit) { syncPermissionState() }

    // The user may grant (or revoke) permissions from the system settings and come back: the
    // volume cards must reflect reality without a manual refresh.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) syncPermissionState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Grant problems (for example a tree Android would not persist) surface as a snackbar.
    LaunchedEffect(Unit) {
        viewModel.messages.collect { resId ->
            snackbarHostState.showSnackbar(context.getString(resId))
        }
    }

    fun requestGrant(volume: VolumeInfo) {
        if (volume.grantKind == GrantKind.RUNTIME_MEDIA) {
            // Internal storage: the ordinary runtime permission dialog, not the SAF picker.
            requestMediaPermission()
            return
        }
        val intent = viewModel.grantIntentFor(volume)
        if (intent == null) {
            treePicker.launch(null)
        } else {
            runCatching { intentPicker.launch(intent) }.onFailure { treePicker.launch(null) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
                        Text(
                            stringResource(R.string.home_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.refresh() }) {
                        Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.action_refresh))
                    }
                    IconButton(onClick = { navigator.settings() }) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        if (state.volumes.isEmpty() && !state.refreshing) {
            EmptyStorage(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onGrant = { treePicker.launch(null) },
            )
            return@Scaffold
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.usbWaitingForGrant?.let { volume ->
                item(key = "usb-banner") {
                    UsbBanner(volume = volume, onGrant = { requestGrant(volume) })
                }
            }

            if (state.needsMediaPermission) {
                item(key = "media-permission") {
                    PermissionCard(
                        blocked = state.mediaPermissionBlocked,
                        onRequest = { requestMediaPermission() },
                    )
                }
            }

            item(key = "quick-access") {
                QuickAccessRow(
                    favoriteCount = state.favoriteCount,
                    onFavorites = { navigator.favorites() },
                    onRecent = { navigator.recent() },
                    onTransfers = { navigator.transfers() },
                )
            }

            item(key = "volumes-header") {
                SectionHeader(
                    title = stringResource(R.string.section_volumes),
                    // One SAF grant per folder: the way to reach anything raw paths cannot on
                    // scoped storage (Documents, Download, a USB stick, an SD card…).
                    actionLabel = stringResource(R.string.action_add_folder),
                    onAction = { treePicker.launch(null) },
                )
            }

            items(state.volumes, key = { it.id }) { volume ->
                VolumeCard(
                    volume = volume,
                    onOpen = {
                        val uri = viewModel.openableUri(volume)
                        if (uri != null) {
                            navigator.openVolume(uri)
                        } else {
                            requestGrant(volume)
                        }
                    },
                    onGrant = { requestGrant(volume) },
                )
            }

            if (state.recentVideos.isNotEmpty()) {
                item(key = "recent-header") {
                    SectionHeader(stringResource(R.string.recent_videos))
                }
                item(key = "recent-videos") {
                    RecentVideosRow(
                        nodes = state.recentVideos,
                        onPlay = { node -> navigator.playVideo(node.uri, null) },
                    )
                }
            }

            if (state.recentFolders.isNotEmpty()) {
                item(key = "recent-folders-header") {
                    SectionHeader(stringResource(R.string.recent_folders))
                }
                items(state.recentFolders, key = { "folder-${it.key}" }) { entry ->
                    RecentFolderRow(entry = entry, onOpen = {
                        runCatching { navigator.openFolder(android.net.Uri.parse(entry.uri)) }
                    })
                }
            }

            if (state.refreshing) {
                item(key = "refreshing") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
private fun UsbBanner(volume: VolumeInfo, onGrant: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = RoundedCornerShape(20.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Usb,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Text(
                    text = stringResource(R.string.usb_attached_title),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(
                text = volume.description ?: stringResource(R.string.usb_attached_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(onClick = onGrant) {
                Text(stringResource(R.string.action_grant_access))
            }
        }
    }
}

@Composable
private fun PermissionCard(blocked: Boolean, onRequest: () -> Unit) {
    Card(shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(16.dp)) {
            Text(
                stringResource(R.string.dialog_permission_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(
                    if (blocked) R.string.permission_blocked_body else R.string.dialog_permission_body,
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            TextButton(onClick = onRequest) {
                Text(
                    stringResource(
                        if (blocked) R.string.action_open_settings else R.string.action_grant_access,
                    ),
                )
            }
        }
    }
}

@Composable
private fun QuickAccessRow(
    favoriteCount: Int,
    onFavorites: () -> Unit,
    onRecent: () -> Unit,
    onTransfers: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        QuickAction(
            label = stringResource(R.string.section_favorites),
            badge = if (favoriteCount > 0) "$favoriteCount" else null,
            icon = { Icon(Icons.Outlined.Favorite, contentDescription = null) },
            onClick = onFavorites,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            label = stringResource(R.string.section_recent),
            badge = null,
            icon = { Icon(Icons.Outlined.History, contentDescription = null) },
            onClick = onRecent,
            modifier = Modifier.weight(1f),
        )
        QuickAction(
            label = stringResource(R.string.ops_title),
            badge = null,
            icon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
            onClick = onTransfers,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun QuickAction(
    label: String,
    badge: String?,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(
            Modifier.padding(vertical = 14.dp, horizontal = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box {
                icon()
                if (badge != null) {
                    Text(
                        text = badge,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.align(Alignment.TopEnd),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun RecentVideosRow(nodes: List<DocNode>, onPlay: (DocNode) -> Unit) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
    ) {
        items(nodes, key = { it.key }) { node ->
            Card(
                onClick = { onPlay(node) },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.width(168.dp),
            ) {
                Column {
                    MediaThumbnail(
                        node = node,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
                    )
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            text = node.nameWithoutExtension,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = Formatters.size(node.size.coerceAtLeast(0)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentFolderRow(entry: RecentEntry, onOpen: () -> Unit) {
    Card(onClick = onOpen, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Folder Cover applies to this card too: the poster inside the folder (`poster` >
            // `folder` > `cover`) is shown in poster proportions, and [MediaThumbnail] falls back
            // to the ordinary folder icon when the folder holds no cover by that name.
            val node = remember(entry.key) { entry.asDocNode() }
            Box(
                Modifier
                    .width(52.dp)
                    .aspectRatio(FOLDER_COVER_ASPECT)
                    .clip(RoundedCornerShape(10.dp)),
            ) {
                MediaThumbnail(
                    node = node,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    text = entry.displayPath.ifEmpty { Formatters.dateTime(entry.lastOpenedAt) },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyStorage(modifier: Modifier = Modifier, onGrant: () -> Unit) {
    Column(
        modifier = modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Outlined.Usb,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_no_volumes),
            style = MaterialTheme.typography.titleMedium,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.hint_connect_usb),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        OutlinedButton(onClick = onGrant) {
            Text(stringResource(R.string.action_grant_access))
        }
    }
}

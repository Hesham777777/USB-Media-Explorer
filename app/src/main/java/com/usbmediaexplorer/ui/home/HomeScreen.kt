package com.usbmediaexplorer.ui.home

import android.app.Activity
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Favorite
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.usbmediaexplorer.ui.common.CountBadge
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.common.LocalSettings
import com.usbmediaexplorer.ui.common.MediaThumbnail
import com.usbmediaexplorer.ui.common.PressableSurface
import com.usbmediaexplorer.ui.common.SectionHeader
import com.usbmediaexplorer.ui.common.SheetHeader
import com.usbmediaexplorer.ui.common.SkeletonTiles
import com.usbmediaexplorer.ui.common.StateBlock
import com.usbmediaexplorer.ui.common.bidiLtr
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.common.viewModelFactory
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.ui.theme.AppTheme
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.Permissions
import kotlinx.coroutines.launch

/**
 * Home: the storage dashboard (spec §1, §18).
 *
 * Density first. A phone screen should answer "where are my files, how full is the drive, what did
 * I just watch" in one glance, so:
 *  - volumes are compact cards, two per row, and the card itself is the open action,
 *  - "recent folders" is a two-hour window, not an archive of everything ever opened,
 *  - secondary destinations live in the header overflow instead of taking a card each,
 *  - a permission problem is a thin strip with one button, never a wall of text.
 */
@Composable
fun HomeScreen(snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = LocalSettings.current
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
        val granted = Permissions.hasStorageAccess(context)
        val activity = context as? Activity
        viewModel.onMediaPermissionResult(
            granted = granted,
            blocked = !granted && activity != null && Permissions.permanentlyDenied(activity),
        )
    }

    val syncPermissionState = {
        val granted = Permissions.hasStorageAccess(context)
        // Coming back from the system settings: a changed grant clears the blocked flag and is
        // the only resume case that deserves a full volume rescan.
        if (viewModel.syncMediaPermission(granted)) viewModel.refresh()
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

    LaunchedEffect(Unit) { syncPermissionState() }

    // The user may grant (or revoke) permissions from the system settings and come back: the
    // volume cards must reflect reality without a manual refresh, and the two-hour recent window
    // has to be recomputed.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                syncPermissionState()
                viewModel.refreshRecents()
            }
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

    var detailsVolume by remember { mutableStateOf<VolumeInfo?>(null) }
    var showOnboarding by remember { mutableStateOf(false) }

    // Every permission the app needs is asked for once, on first launch, in a single flow.
    LaunchedEffect(settings.firstRunPermissionsAsked) {
        if (!settings.firstRunPermissionsAsked) showOnboarding = true
    }

    val volumes = state.volumes
    val readyVolumes = volumes.filter { it.isReady }
    val freeTotal = readyVolumes.sumOf { it.freeBytes ?: 0L }
    val searchRoot = remember(volumes) { viewModel.searchRoot() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = stringResource(R.string.app_name),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = if (readyVolumes.isEmpty()) {
                                stringResource(R.string.home_subtitle)
                            } else if (readyVolumes.size == 1) {
                                stringResource(
                                    R.string.home_volumes_summary_one,
                                    Formatters.size(freeTotal),
                                )
                            } else {
                                stringResource(
                                    R.string.home_volumes_summary,
                                    readyVolumes.size,
                                    Formatters.size(freeTotal),
                                )
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = { searchRoot?.let { navigator.search(it) } },
                        enabled = searchRoot != null,
                    ) {
                        Icon(
                            Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.action_search),
                        )
                    }
                    IconButton(onClick = { viewModel.refresh() }) {
                        if (state.refreshing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        } else {
                            Icon(
                                Icons.Outlined.Refresh,
                                contentDescription = stringResource(R.string.action_refresh),
                            )
                        }
                    }
                    IconButton(onClick = { navigator.settings() }) {
                        Icon(
                            Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                        )
                    }
                    HomeOverflow(
                        favoriteCount = state.favoriteCount,
                        onFavorites = { navigator.favorites() },
                        onRecent = { navigator.recent() },
                        onTransfers = { navigator.transfers() },
                        onAddFolder = { treePicker.launch(null) },
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(
                start = AppSpacing.lg,
                end = AppSpacing.lg,
                top = AppSpacing.sm,
                bottom = AppSpacing.xxl,
            ),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            if (state.needsMediaPermission) {
                item(key = "permission-strip") {
                    PermissionStrip(
                        blocked = state.mediaPermissionBlocked,
                        onRequest = { requestMediaPermission() },
                        onOpenSettings = {
                            runCatching {
                                context.startActivity(
                                    Permissions.appSettingsIntent(context.packageName),
                                )
                            }
                        },
                    )
                }
            }

            state.usbWaitingForGrant?.let { volume ->
                item(key = "usb-strip") {
                    UsbStrip(volume = volume, onGrant = { requestGrant(volume) })
                }
            }

            item(key = "volumes-header") {
                SectionHeader(
                    title = stringResource(R.string.section_volumes),
                    count = volumes.size,
                    actionLabel = stringResource(R.string.action_add_folder),
                    onAction = { treePicker.launch(null) },
                )
            }

            when {
                volumes.isEmpty() && state.refreshing -> item(key = "volumes-skeleton") {
                    SkeletonTiles(columns = 2, rows = 1)
                }

                volumes.isEmpty() -> item(key = "volumes-empty") {
                    StateBlock(
                        icon = Icons.Outlined.Usb,
                        title = stringResource(R.string.empty_no_volumes),
                        body = stringResource(R.string.hint_connect_usb),
                        tint = MaterialTheme.colorScheme.primary,
                        container = MaterialTheme.colorScheme.primaryContainer,
                        actionLabel = stringResource(R.string.action_grant_access),
                        onAction = { treePicker.launch(null) },
                        secondaryLabel = stringResource(R.string.action_refresh),
                        onSecondaryAction = { viewModel.refresh() },
                    )
                }

                volumes.size == 1 -> item(key = "volume-${volumes.first().id}") {
                    val volume = volumes.first()
                    StorageCard(
                        volume = volume,
                        wide = true,
                        onOpen = { viewModel.openableUri(volume)?.let { navigator.openVolume(it) } },
                        onGrant = { requestGrant(volume) },
                        onDetails = { detailsVolume = volume },
                        onRelease = { viewModel.releaseVolume(volume) },
                        onRescan = { viewModel.refresh() },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                else -> volumes.chunked(2).forEachIndexed { rowIndex, pair ->
                    item(key = "volume-row-$rowIndex") {
                        Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
                            pair.forEach { volume ->
                                StorageCard(
                                    volume = volume,
                                    onOpen = {
                                        viewModel.openableUri(volume)?.let { navigator.openVolume(it) }
                                    },
                                    onGrant = { requestGrant(volume) },
                                    onDetails = { detailsVolume = volume },
                                    onRelease = { viewModel.releaseVolume(volume) },
                                    onRescan = { viewModel.refresh() },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // ---- recent folders: the last two hours only ---------------------
            if (state.recentFolders.isNotEmpty()) {
                item(key = "recent-folders-header") {
                    SectionHeader(
                        title = stringResource(R.string.recent_folders),
                        count = state.recentFolders.size,
                        actionLabel = stringResource(R.string.section_recent),
                        onAction = { navigator.recent() },
                    )
                }
                item(key = "recent-folders-row") {
                    RecentFoldersRow(
                        entries = state.recentFolders,
                        volumeNameOf = { id -> volumes.firstOrNull { it.id == id }?.name.orEmpty() },
                        onOpen = { entry ->
                            runCatching { Uri.parse(entry.uri) }.getOrNull()
                                ?.let { navigator.openFolder(it) }
                        },
                    )
                }
            }

            // ---- recently watched: real frames, never a poster ---------------
            if (state.recentVideos.isNotEmpty()) {
                item(key = "recent-videos-header") {
                    SectionHeader(
                        title = stringResource(R.string.recent_videos),
                        count = state.recentVideos.size,
                        actionLabel = stringResource(R.string.section_recent),
                        onAction = { navigator.recent() },
                    )
                }
                item(key = "recent-videos-row") {
                    RecentVideosRow(
                        nodes = state.recentVideos,
                        onPlay = { node -> navigator.playVideo(node.uri, null) },
                        onForget = { node ->
                            viewModel.forgetRecentVideo(node)
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    context.getString(R.string.msg_removed_from_recents),
                                )
                            }
                        },
                    )
                }
            }

            // ---- quick access ------------------------------------------------
            item(key = "quick-header") {
                SectionHeader(title = stringResource(R.string.section_quick_access))
            }
            item(key = "quick-row") {
                QuickAccessRow(
                    favoriteCount = state.favoriteCount,
                    onFavorites = { navigator.favorites() },
                    onRecent = { navigator.recent() },
                    onTransfers = { navigator.transfers() },
                    onSettings = { navigator.settings() },
                )
            }
        }
    }

    detailsVolume?.let { volume ->
        VolumeDetailsSheet(volume = volume, onDismiss = { detailsVolume = null })
    }

    if (showOnboarding) {
        OnboardingSheet(
            onGrantAll = {
                showOnboarding = false
                viewModel.markFirstRunPermissionsAsked()
                permissionLauncher.launch(Permissions.runtimePermissions(context))
            },
            onLater = {
                showOnboarding = false
                viewModel.markFirstRunPermissionsAsked()
                syncPermissionState()
            },
        )
    }
}

/* ---------------------------------------------------------------------------
 * Header
 * ------------------------------------------------------------------------- */

@Composable
private fun HomeOverflow(
    favoriteCount: Int,
    onFavorites: () -> Unit,
    onRecent: () -> Unit,
    onTransfers: () -> Unit,
    onAddFolder: () -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Icons.Outlined.MoreVert, contentDescription = stringResource(R.string.action_more))
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.section_favorites)) },
                leadingIcon = { Icon(Icons.Outlined.Favorite, contentDescription = null) },
                trailingIcon = { if (favoriteCount > 0) CountBadge(favoriteCount) },
                onClick = {
                    open = false
                    onFavorites()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.section_recent)) },
                leadingIcon = { Icon(Icons.Outlined.History, contentDescription = null) },
                onClick = {
                    open = false
                    onRecent()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.quick_transfers)) },
                leadingIcon = { Icon(Icons.Outlined.SwapHoriz, contentDescription = null) },
                onClick = {
                    open = false
                    onTransfers()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.action_add_folder)) },
                leadingIcon = { Icon(Icons.Outlined.Usb, contentDescription = null) },
                onClick = {
                    open = false
                    onAddFolder()
                },
            )
        }
    }
}

/* ---------------------------------------------------------------------------
 * Strips: permission and USB — thin, one action, never a wall of text
 * ------------------------------------------------------------------------- */

@Composable
private fun PermissionStrip(
    blocked: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val extended = AppTheme.extended
    PressableSurface(
        onClick = { if (blocked) onOpenSettings() else onRequest() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        color = extended.warningContainer.copy(alpha = 0.45f),
    ) {
        Row(
            Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(extended.warningContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Lock,
                    contentDescription = null,
                    tint = extended.onWarningContainer,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        if (blocked) R.string.action_open_settings else R.string.permission_strip_title,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(
                        if (blocked) {
                            R.string.permission_blocked_body
                        } else {
                            R.string.permission_strip_body
                        },
                    ),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(AppSpacing.sm))
            FilledTonalButton(
                onClick = { if (blocked) onOpenSettings() else onRequest() },
                contentPadding = PaddingValues(horizontal = AppSpacing.md),
            ) {
                Text(
                    text = stringResource(
                        if (blocked) R.string.action_open_settings else R.string.action_grant_access,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun UsbStrip(volume: VolumeInfo, onGrant: () -> Unit) {
    val accent = AppTheme.extended.usb
    PressableSurface(
        onClick = onGrant,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(accent.copy(alpha = if (AppTheme.isDark) 0.20f else 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Outlined.Usb,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(18.dp),
                )
            }
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(
                    text = volume.name.ifBlank { stringResource(R.string.usb_attached_title) }.bidiName(),
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.usb_attached_body),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(AppSpacing.sm))
            Button(
                onClick = onGrant,
                contentPadding = PaddingValues(horizontal = AppSpacing.md),
            ) {
                Text(
                    stringResource(R.string.action_grant_access),
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * First launch: every permission in one flow
 * ------------------------------------------------------------------------- */

@Composable
private fun OnboardingSheet(onGrantAll: () -> Unit, onLater: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onLater, sheetState = sheetState) {
        SheetHeader(
            icon = Icons.Outlined.Lock,
            title = stringResource(R.string.onboarding_title),
            subtitle = stringResource(R.string.onboarding_permissions_row),
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = AppSpacing.lg, end = AppSpacing.lg, bottom = AppSpacing.xxl),
        ) {
            Text(
                text = stringResource(R.string.onboarding_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.lg))
            Button(
                onClick = onGrantAll,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
            ) {
                Icon(Icons.Outlined.Lock, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(AppSpacing.sm))
                Text(stringResource(R.string.onboarding_grant_all))
            }
            Spacer(Modifier.height(AppSpacing.xs))
            TextButton(
                onClick = onLater,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = stringResource(R.string.onboarding_later),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/* ---------------------------------------------------------------------------
 * Recent folders (Folder Cover) and recent videos (real frames)
 * ------------------------------------------------------------------------- */

@Composable
private fun RecentFoldersRow(
    entries: List<RecentEntry>,
    volumeNameOf: (String) -> String,
    onOpen: (RecentEntry) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        contentPadding = PaddingValues(vertical = AppSpacing.xs),
    ) {
        items(entries, key = { it.key }) { entry ->
            val node = remember(entry.key) { entry.asDocNode() }
            val volume = volumeNameOf(entry.volumeId)
            PressableSurface(
                onClick = { onOpen(entry) },
                modifier = Modifier.width(236.dp),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Row(
                    Modifier.padding(AppSpacing.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .width(44.dp)
                            .aspectRatio(FOLDER_COVER_ASPECT)
                            .clip(RoundedCornerShape(AppRadius.sm)),
                    ) {
                        // Folder Cover: poster > folder > cover, found inside the folder — never a
                        // random picture, and the folder icon when there is no cover by that name.
                        MediaThumbnail(
                            node = node,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit,
                        )
                    }
                    Spacer(Modifier.width(AppSpacing.md))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = entry.name.bidiName(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val path = shortPath(entry.displayPath, volume)
                        if (path.isNotEmpty()) {
                            Text(
                                text = path.bidiLtr(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = Formatters.dateTime(entry.lastOpenedAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RecentVideosRow(
    nodes: List<DocNode>,
    onPlay: (DocNode) -> Unit,
    onForget: (DocNode) -> Unit,
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        contentPadding = PaddingValues(vertical = AppSpacing.xs),
    ) {
        items(nodes, key = { it.key }) { node ->
            PressableSurface(
                onClick = { onPlay(node) },
                onLongClick = { onForget(node) },
                modifier = Modifier.width(168.dp),
                shape = RoundedCornerShape(AppRadius.md),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
            ) {
                Column {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(16f / 9f)
                            .clip(
                                RoundedCornerShape(
                                    topStart = AppRadius.md,
                                    topEnd = AppRadius.md,
                                ),
                            ),
                    ) {
                        MediaThumbnail(node = node, modifier = Modifier.fillMaxSize())
                        Box(
                            Modifier
                                .align(Alignment.Center)
                                .size(30.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.72f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = stringResource(R.string.action_open),
                                tint = MaterialTheme.colorScheme.onSurface,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    Column(Modifier.padding(horizontal = AppSpacing.md, vertical = AppSpacing.sm)) {
                        Text(
                            text = node.nameWithoutExtension.bidiName(),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            text = Formatters.size(node.size.coerceAtLeast(0)).bidiLtr(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

/** The last two segments of `USB › Movies › The.Matrix` — the deepest part is the useful one. */
private fun shortPath(displayPath: String, volumeName: String): String {
    val source = displayPath.ifBlank { volumeName }
    if (source.isBlank()) return ""
    val parts = source.split("›", "/").map { it.trim() }.filter { it.isNotEmpty() }
    if (parts.size <= 2) return parts.joinToString(" › ")
    return parts.takeLast(2).joinToString(" › ")
}

/* ---------------------------------------------------------------------------
 * Quick access
 * ------------------------------------------------------------------------- */

@Composable
private fun QuickAccessRow(
    favoriteCount: Int,
    onFavorites: () -> Unit,
    onRecent: () -> Unit,
    onTransfers: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(AppSpacing.sm)) {
        QuickTile(Icons.Outlined.Favorite, stringResource(R.string.section_favorites), favoriteCount, onFavorites, Modifier.weight(1f))
        QuickTile(Icons.Outlined.History, stringResource(R.string.section_recent), null, onRecent, Modifier.weight(1f))
        QuickTile(Icons.Outlined.SwapHoriz, stringResource(R.string.quick_transfers), null, onTransfers, Modifier.weight(1f))
        QuickTile(Icons.Outlined.Settings, stringResource(R.string.action_settings), null, onSettings, Modifier.weight(1f))
    }
}

@Composable
private fun QuickTile(
    icon: ImageVector,
    label: String,
    count: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PressableSurface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(AppRadius.md),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(vertical = AppSpacing.md, horizontal = AppSpacing.xs),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (count != null && count > 0) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = count.toString(),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

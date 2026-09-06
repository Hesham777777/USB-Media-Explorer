package com.usbmediaexplorer.ui.settings

import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteForever
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.SettingsSuggest
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.data.thumb.ThumbnailCache
import com.usbmediaexplorer.data.settings.AspectMode
import com.usbmediaexplorer.data.settings.FrameStrategy
import com.usbmediaexplorer.data.settings.ItemScale
import com.usbmediaexplorer.data.settings.LanguageMode
import com.usbmediaexplorer.data.settings.SortMode
import com.usbmediaexplorer.data.settings.ThemeMode
import com.usbmediaexplorer.data.settings.ThumbSize
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.data.volume.VolumeState
import com.usbmediaexplorer.ui.common.ConfirmDialog
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.common.OptionDialog
import com.usbmediaexplorer.ui.common.bidiLtr
import com.usbmediaexplorer.ui.common.bidiName
import com.usbmediaexplorer.ui.common.volumeColor
import com.usbmediaexplorer.ui.common.volumeIcon
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.theme.AppRadius
import com.usbmediaexplorer.ui.theme.AppSpacing
import com.usbmediaexplorer.ui.theme.AppTheme
import com.usbmediaexplorer.util.Formatters
import com.usbmediaexplorer.util.Permissions
import kotlinx.coroutines.launch

/**
 * Settings (spec §15–§18): grouped sections, one control per setting, and the control that fits
 * the setting — a switch for on/off, a picker for a choice, a slider for a range, a row with a
 * status for permissions and storage. Nothing here is a wall of switches.
 */
@Composable
fun SettingsScreen(snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(AppSettings.DEFAULT)
    val volumes by container.volumeRepository.volumes.collectAsStateWithLifecycle(emptyList())

    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheCount by remember { mutableIntStateOf(0) }
    var cacheKinds by remember {
        mutableStateOf<Map<String, ThumbnailCache.KindStat>>(emptyMap())
    }
    var working by remember { mutableStateOf(false) }
    var picker by remember { mutableStateOf<Picker?>(null) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    // Permissions are read live and re-read whenever the screen resumes, because the user may have
    // changed them in the system settings.
    var permissionStates by remember {
        mutableStateOf(Permissions.runtimePermissions(context).associateWith { isGranted(context, it) })
    }
    val resyncPermissions = {
        permissionStates = Permissions.runtimePermissions(context).associateWith { isGranted(context, it) }
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { resyncPermissions() }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) resyncPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val refreshCache: suspend () -> Unit = {
        cacheBytes = container.thumbnailRepository.cacheSizeBytes()
        cacheCount = container.thumbnailRepository.cacheEntryCount()
        cacheKinds = container.thumbnailRepository.cacheStatsByKind()
    }
    LaunchedEffect(Unit) {
        refreshCache()
        container.volumeRepository.refresh()
    }

    fun toast(message: String) = scope.launch { snackbarHostState.showSnackbar(message) }
    val clearedLabel = stringResource(R.string.setting_clear_cache_done)
    val clearedDataLabel = stringResource(R.string.privacy_cleared)

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = { navigator.back() }) {
                        Icon(
                            Icons.AutoMirrored.Outlined.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.sm),
        ) {
            /* ---------------- appearance ---------------- */
            SectionTitle(stringResource(R.string.setting_appearance))
            SettingsCard {
                ClickRow(
                    title = stringResource(R.string.setting_theme),
                    subtitle = stringResource(R.string.setting_theme_desc),
                    value = stringResource(themeLabel(settings.themeMode)),
                    onClick = { picker = Picker.THEME },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_dynamic_color),
                    subtitle = stringResource(R.string.setting_dynamic_color_desc),
                    checked = settings.dynamicColor,
                    onChange = { scope.launch { container.settingsRepository.setDynamicColor(it) } },
                )
                ClickRow(
                    title = stringResource(R.string.setting_language),
                    subtitle = stringResource(R.string.setting_language_desc),
                    value = stringResource(languageLabel(settings.languageMode)),
                    onClick = { picker = Picker.LANGUAGE },
                )
                ClickRow(
                    title = stringResource(R.string.setting_item_scale),
                    subtitle = stringResource(R.string.setting_item_scale_desc),
                    value = stringResource(itemScaleLabel(settings.itemScale)),
                    onClick = { picker = Picker.ITEM_SCALE },
                )
            }

            /* ---------------- browsing ---------------- */
            SectionTitle(stringResource(R.string.settings_section_browse))
            SettingsCard {
                ClickRow(
                    title = stringResource(R.string.view_mode),
                    subtitle = stringResource(R.string.setting_default_view_desc),
                    value = stringResource(viewModeLabel(settings.defaultViewMode)),
                    onClick = { picker = Picker.VIEW_MODE },
                )
                ClickRow(
                    title = stringResource(R.string.sort_by),
                    subtitle = stringResource(R.string.setting_default_sort_desc),
                    value = stringResource(sortLabel(settings.defaultSortMode)),
                    onClick = { picker = Picker.SORT_MODE },
                )
                SwitchRow(
                    title = stringResource(R.string.sort_folders_first),
                    subtitle = stringResource(R.string.setting_folders_first_desc),
                    checked = settings.foldersFirst,
                    onChange = { scope.launch { container.settingsRepository.setFoldersFirst(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_show_hidden),
                    subtitle = stringResource(R.string.setting_show_hidden_desc),
                    checked = settings.showHiddenFiles,
                    onChange = { scope.launch { container.settingsRepository.setShowHidden(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_show_extensions),
                    subtitle = stringResource(R.string.setting_show_extensions_desc),
                    checked = settings.showExtensions,
                    onChange = { scope.launch { container.settingsRepository.setShowExtensions(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_show_media_info),
                    subtitle = stringResource(R.string.setting_show_media_info_desc),
                    checked = settings.showMediaInfo,
                    onChange = { scope.launch { container.settingsRepository.setShowMediaInfo(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_remember_folder_view),
                    subtitle = stringResource(R.string.setting_remember_folder_view_desc),
                    checked = settings.rememberPerFolderView,
                    onChange = { scope.launch { container.settingsRepository.setRememberPerFolderView(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_lazy_metadata),
                    subtitle = stringResource(R.string.setting_lazy_metadata_desc),
                    checked = settings.lazyMetadata,
                    onChange = { scope.launch { container.settingsRepository.setLazyMetadata(it) } },
                )
            }

            /* ---------------- media previews ---------------- */
            SectionTitle(stringResource(R.string.settings_section_media))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.setting_video_thumbs),
                    subtitle = stringResource(R.string.setting_video_thumbs_desc),
                    checked = settings.videoThumbnailsEnabled,
                    onChange = { scope.launch { container.settingsRepository.setVideoThumbnails(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_image_thumbs),
                    subtitle = stringResource(R.string.setting_image_thumbs_desc),
                    checked = settings.imageThumbnailsEnabled,
                    onChange = { scope.launch { container.settingsRepository.setImageThumbnails(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_folder_covers),
                    subtitle = stringResource(R.string.setting_folder_covers_desc),
                    checked = settings.folderCoversEnabled,
                    onChange = { scope.launch { container.settingsRepository.setFolderCovers(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_audio_art),
                    subtitle = stringResource(R.string.setting_audio_art_desc),
                    checked = settings.audioArtEnabled,
                    onChange = { scope.launch { container.settingsRepository.setAudioArt(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_prefer_embedded_cover),
                    subtitle = stringResource(R.string.setting_prefer_embedded_cover_desc),
                    checked = settings.preferEmbeddedCover,
                    onChange = { scope.launch { container.settingsRepository.setPreferEmbeddedCover(it) } },
                )
                ClickRow(
                    title = stringResource(R.string.setting_frame_position),
                    subtitle = stringResource(R.string.setting_frame_position_desc),
                    value = stringResource(frameStrategyLabel(settings.frameStrategy)),
                    onClick = { picker = Picker.FRAME },
                )
                ClickRow(
                    title = stringResource(R.string.setting_thumb_size),
                    subtitle = stringResource(R.string.setting_thumb_size_desc),
                    value = stringResource(thumbSizeLabel(settings.thumbSize)),
                    onClick = { picker = Picker.THUMB_SIZE },
                )
                SliderRow(
                    title = stringResource(R.string.setting_thumb_quality),
                    value = settings.thumbQuality.toFloat(),
                    valueLabel = "${settings.thumbQuality}%",
                    range = 40f..100f,
                    onValueChange = { quality ->
                        scope.launch { container.settingsRepository.setThumbQuality(quality.toInt()) }
                    },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_charging_only),
                    subtitle = stringResource(R.string.setting_charging_only_desc),
                    checked = settings.generateWhileChargingOnly,
                    onChange = { scope.launch { container.settingsRepository.setChargingOnly(it) } },
                )
            }

            /* ---------------- playback ---------------- */
            SectionTitle(stringResource(R.string.setting_player))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.setting_resume_prompt),
                    subtitle = stringResource(R.string.setting_resume_prompt_desc),
                    checked = settings.resumePromptEnabled,
                    onChange = { scope.launch { container.settingsRepository.setResumePrompt(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_subtitle_autodetect),
                    subtitle = stringResource(R.string.setting_subtitle_autodetect_desc),
                    checked = settings.autoDetectSubtitles,
                    onChange = { scope.launch { container.settingsRepository.setAutoDetectSubtitles(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_keep_screen_on),
                    subtitle = stringResource(R.string.setting_keep_screen_on_desc),
                    checked = settings.keepScreenOn,
                    onChange = { scope.launch { container.settingsRepository.setKeepScreenOn(it) } },
                )
                ClickRow(
                    title = stringResource(R.string.player_aspect),
                    subtitle = stringResource(R.string.setting_aspect_desc),
                    value = stringResource(aspectLabel(settings.defaultAspectMode)),
                    onClick = { picker = Picker.ASPECT },
                )
            }

            /* ---------------- storage ---------------- */
            SectionTitle(stringResource(R.string.settings_section_storage))
            SettingsCard {
                volumes.forEach { volume ->
                    VolumeRow(
                        name = volume.name,
                        icon = volumeIcon(volume.kind),
                        accent = volumeColor(volume.kind),
                        state = volume.state,
                        detail = listOfNotNull(
                            volume.fileSystem?.let { FileSystemLabel(it) },
                            if (volume.totalBytes != null && volume.freeBytes != null) {
                                stringResource(
                                    R.string.volume_free_of,
                                    Formatters.size(volume.freeBytes ?: 0),
                                    Formatters.size(volume.totalBytes ?: 0),
                                )
                            } else {
                                null
                            },
                        ).joinToString(" • "),
                    )
                }
                if (volumes.isEmpty()) {
                    EmptySettingsRow(stringResource(R.string.settings_no_volumes))
                }
                ActionRow(
                    title = stringResource(R.string.action_refresh),
                    icon = Icons.Outlined.Refresh,
                    enabled = !working,
                    onClick = { scope.launch { container.volumeRepository.refresh() } },
                )
            }

            /* ---------------- cache ---------------- */
            SectionTitle(stringResource(R.string.setting_cache))
            SettingsCard {
                InfoRow(
                    title = stringResource(R.string.setting_cache_used),
                    value = Formatters.size(cacheBytes),
                    subtitle = stringResource(R.string.items_count, cacheCount),
                )
                // One row per kind, so the user can drop folder covers without losing the frames
                // they already waited for (spec §18).
                CacheKind.entries.forEach { kind ->
                    val stat = cacheKinds[kind.key]
                    if (stat != null && stat.count > 0) {
                        CacheKindRow(
                            label = stringResource(kind.labelRes),
                            count = stat.count,
                            bytes = stat.bytes,
                            enabled = !working,
                            onClear = {
                                scope.launch {
                                    working = true
                                    val removed = container.thumbnailRepository.clearCacheKind(kind.key)
                                    working = false
                                    refreshCache()
                                    toast("$clearedLabel ($removed)")
                                }
                            },
                        )
                    }
                }
                ClickRow(
                    title = stringResource(R.string.setting_cache_limit),
                    subtitle = stringResource(R.string.setting_cache_limit_desc),
                    value = Formatters.size(settings.cacheLimitBytes),
                    onClick = { picker = Picker.CACHE_LIMIT },
                )
                ActionRow(
                    title = stringResource(R.string.setting_clear_cache),
                    icon = Icons.Outlined.DeleteSweep,
                    destructive = true,
                    enabled = !working && cacheBytes > 0,
                    onClick = { confirm = Confirm.CACHE },
                )
                ActionRow(
                    title = stringResource(R.string.setting_clean_orphans),
                    icon = Icons.Outlined.CleaningServices,
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            val removed = container.thumbnailRepository.cleanOrphans()
                            working = false
                            refreshCache()
                            toast("$clearedLabel ($removed)")
                        }
                    },
                )
                if (working) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = AppSpacing.sm),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            /* ---------------- permissions ---------------- */
            SectionTitle(stringResource(R.string.settings_section_permissions))
            SettingsCard {
                permissionStates.forEach { (permission, granted) ->
                    PermissionRow(
                        label = stringResource(permissionLabel(permission)),
                        granted = granted,
                        blocked = !granted &&
                            (context as? Activity)?.let { Permissions.permanentlyDenied(it) } == true,
                        onGrant = {
                            if (granted) return@PermissionRow
                            if ((context as? Activity)?.let { Permissions.permanentlyDenied(it) } == true) {
                                runCatching {
                                    context.startActivity(Permissions.appSettingsIntent(context.packageName))
                                }
                            } else {
                                permissionLauncher.launch(arrayOf(permission))
                            }
                        },
                    )
                }
                if (Permissions.supportsAllFilesAccess()) {
                    val allFiles = Permissions.hasAllFilesAccess()
                    PermissionRow(
                        label = stringResource(R.string.perm_all_files),
                        granted = allFiles,
                        blocked = false,
                        onGrant = {
                            if (!allFiles) {
                                runCatching {
                                    context.startActivity(
                                        Permissions.allFilesAccessIntent(context.packageName),
                                    )
                                }
                            }
                        },
                    )
                }
                InfoRow(
                    title = stringResource(R.string.settings_permissions_note),
                    value = "",
                    subtitle = stringResource(R.string.settings_permissions_note_desc),
                )
            }

            /* ---------------- privacy ---------------- */
            SectionTitle(stringResource(R.string.settings_section_privacy))
            SettingsCard {
                ActionRow(
                    title = stringResource(R.string.privacy_clear_recents),
                    icon = Icons.Outlined.History,
                    subtitle = stringResource(R.string.privacy_clear_recents_desc),
                    onClick = { confirm = Confirm.RECENTS },
                )
                ActionRow(
                    title = stringResource(R.string.privacy_clear_favorites),
                    icon = Icons.Outlined.Star,
                    subtitle = stringResource(R.string.privacy_clear_favorites_desc),
                    onClick = { confirm = Confirm.FAVORITES },
                )
                ActionRow(
                    title = stringResource(R.string.privacy_clear_positions),
                    icon = Icons.Outlined.DeleteForever,
                    subtitle = stringResource(R.string.privacy_clear_positions_desc),
                    onClick = {
                        scope.launch {
                            container.playbackPositionStore.clearFinished()
                            toast(clearedDataLabel)
                        }
                    },
                )
            }

            /* ---------------- about ---------------- */
            SectionTitle(stringResource(R.string.setting_about))
            SettingsCard {
                InfoRow(
                    title = stringResource(R.string.app_name),
                    value = stringResource(R.string.about_version, BuildConfig_VERSION),
                    subtitle = stringResource(R.string.setting_about_desc),
                )
                InfoRow(
                    title = stringResource(R.string.about_storage_layer),
                    value = "",
                    subtitle = stringResource(R.string.about_storage_layer_desc),
                )
            }
            Spacer(Modifier.height(AppSpacing.xxl))
        }
    }

    /* ---------------- pickers ---------------- */
    when (picker) {
        Picker.FRAME -> EnumPicker(
            title = R.string.setting_frame_position,
            options = FrameStrategy.entries.toList(),
            label = { stringResource(frameStrategyLabel(it)) },
            selected = settings.frameStrategy,
            onSelect = { scope.launch { container.settingsRepository.setFrameStrategy(it) } },
            onDismiss = { picker = null },
        )

        Picker.THUMB_SIZE -> EnumPicker(
            title = R.string.setting_thumb_size,
            options = ThumbSize.entries.toList(),
            label = { stringResource(thumbSizeLabel(it)) + " • ${it.px}px" },
            selected = settings.thumbSize,
            onSelect = { scope.launch { container.settingsRepository.setThumbSize(it) } },
            onDismiss = { picker = null },
        )

        Picker.CACHE_LIMIT -> {
            val options = listOf(64L, 128L, 256L, 512L, 1024L, 2048L, 4096L).map { it * 1024 * 1024 }
            OptionDialog(
                title = stringResource(R.string.setting_cache_limit),
                options = options.map { Formatters.size(it) },
                selectedIndex = options.indexOf(settings.cacheLimitBytes).coerceAtLeast(0),
                onSelect = {
                    scope.launch {
                        container.settingsRepository.setCacheLimit(options[it])
                        container.thumbnailRepository.enforceLimit(options[it])
                        cacheBytes = container.thumbnailRepository.cacheSizeBytes()
                    }
                },
                onDismiss = { picker = null },
            )
        }

        Picker.THEME -> EnumPicker(
            title = R.string.setting_theme,
            options = ThemeMode.entries.toList(),
            label = { stringResource(themeLabel(it)) },
            selected = settings.themeMode,
            onSelect = { scope.launch { container.settingsRepository.setThemeMode(it) } },
            onDismiss = { picker = null },
        )

        Picker.LANGUAGE -> EnumPicker(
            title = R.string.setting_language,
            options = LanguageMode.entries.toList(),
            label = { stringResource(languageLabel(it)) },
            selected = settings.languageMode,
            onSelect = { scope.launch { container.settingsRepository.setLanguage(it) } },
            onDismiss = { picker = null },
        )

        Picker.VIEW_MODE -> EnumPicker(
            title = R.string.view_mode,
            options = ViewMode.entries.toList(),
            label = { stringResource(viewModeLabel(it)) },
            selected = settings.defaultViewMode,
            onSelect = { scope.launch { container.settingsRepository.setDefaultViewMode(it) } },
            onDismiss = { picker = null },
        )

        Picker.SORT_MODE -> EnumPicker(
            title = R.string.sort_by,
            options = SortMode.entries.toList(),
            label = { stringResource(sortLabel(it)) },
            selected = settings.defaultSortMode,
            onSelect = { scope.launch { container.settingsRepository.setDefaultSortMode(it) } },
            onDismiss = { picker = null },
        )

        Picker.ITEM_SCALE -> EnumPicker(
            title = R.string.setting_item_scale,
            options = ItemScale.entries.toList(),
            label = { stringResource(itemScaleLabel(it)) },
            selected = settings.itemScale,
            onSelect = { scope.launch { container.settingsRepository.setItemScale(it) } },
            onDismiss = { picker = null },
        )

        Picker.ASPECT -> EnumPicker(
            title = R.string.player_aspect,
            options = AspectMode.entries.toList(),
            label = { stringResource(aspectLabel(it)) },
            selected = settings.defaultAspectMode,
            onSelect = { scope.launch { container.settingsRepository.setAspectMode(it) } },
            onDismiss = { picker = null },
        )

        null -> Unit
    }

    /* ---------------- confirmations ---------------- */
    when (confirm) {
        Confirm.CACHE -> ConfirmDialog(
            title = stringResource(R.string.dialog_clear_cache_title),
            body = stringResource(R.string.dialog_clear_cache_body, Formatters.size(cacheBytes)),
            confirmLabel = stringResource(R.string.setting_clear_cache),
            destructive = true,
            onConfirm = {
                confirm = null
                scope.launch {
                    working = true
                    val removed = container.thumbnailRepository.clearCache()
                    working = false
                    refreshCache()
                    toast(context.getString(R.string.setting_clear_cache_done, removed))
                }
            },
            onDismiss = { confirm = null },
        )

        Confirm.RECENTS -> ConfirmDialog(
            title = stringResource(R.string.privacy_clear_recents),
            body = stringResource(R.string.dialog_clear_data_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirm = null
                scope.launch {
                    container.recentStore.clearAll()
                    toast(clearedDataLabel)
                }
            },
            onDismiss = { confirm = null },
        )

        Confirm.FAVORITES -> ConfirmDialog(
            title = stringResource(R.string.privacy_clear_favorites),
            body = stringResource(R.string.dialog_clear_data_body),
            confirmLabel = stringResource(R.string.action_delete),
            destructive = true,
            onConfirm = {
                confirm = null
                scope.launch {
                    container.favoritesStore.clear()
                    toast(clearedDataLabel)
                }
            },
            onDismiss = { confirm = null },
        )

        null -> Unit
    }
}

/** Version comes from the generated BuildConfig, never from a hand-written string. */
private val BuildConfig_VERSION: String = com.usbmediaexplorer.BuildConfig.VERSION_NAME

private enum class Picker {
    FRAME, THUMB_SIZE, CACHE_LIMIT, THEME, LANGUAGE, VIEW_MODE, SORT_MODE, ASPECT, ITEM_SCALE
}

private enum class Confirm { CACHE, RECENTS, FAVORITES }

/** The cache buckets the user can clear on their own. Keys match the on-disk index. */
private enum class CacheKind(val key: String, val labelRes: Int) {
    VIDEO(ThumbnailCache.KIND_VIDEO, R.string.cache_kind_video),
    IMAGE(ThumbnailCache.KIND_IMAGE, R.string.cache_kind_image),
    COVER(ThumbnailCache.KIND_COVER, R.string.cache_kind_cover),
    AUDIO(ThumbnailCache.KIND_AUDIO, R.string.cache_kind_audio),
    OTHER(ThumbnailCache.KIND_OTHER, R.string.cache_kind_other),
}

/* ---------------------------------------------------------------------------
 * Labels
 * ------------------------------------------------------------------------- */

private fun FileSystemLabel(fileSystem: String): String =
    com.usbmediaexplorer.data.volume.FileSystemProbe.labelFor(fileSystem) ?: fileSystem

private fun isGranted(context: android.content.Context, permission: String): Boolean =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun permissionLabel(permission: String): Int = when {
    permission.endsWith("READ_MEDIA_VIDEO") -> R.string.perm_media_video
    permission.endsWith("READ_MEDIA_IMAGES") -> R.string.perm_media_images
    permission.endsWith("READ_MEDIA_AUDIO") -> R.string.perm_media_audio
    permission.endsWith("READ_MEDIA_VISUAL_USER_SELECTED") -> R.string.perm_media_selected
    permission.endsWith("READ_EXTERNAL_STORAGE") -> R.string.perm_storage_read
    permission.endsWith("POST_NOTIFICATIONS") -> R.string.perm_notifications
    permission.endsWith("MANAGE_EXTERNAL_STORAGE") -> R.string.perm_all_files
    else -> R.string.perm_other
}

private fun aspectLabel(mode: AspectMode): Int = when (mode) {
    AspectMode.FIT -> R.string.aspect_fit
    AspectMode.FILL -> R.string.aspect_fill
    AspectMode.STRETCH -> R.string.aspect_stretch
    AspectMode.ZOOM -> R.string.aspect_zoom
}

private fun itemScaleLabel(scale: ItemScale): Int = when (scale) {
    ItemScale.COMPACT -> R.string.item_scale_compact
    ItemScale.NORMAL -> R.string.item_scale_normal
    ItemScale.LARGE -> R.string.item_scale_large
}

private fun frameStrategyLabel(strategy: FrameStrategy): Int = when (strategy) {
    FrameStrategy.FIRST -> R.string.frame_first
    FrameStrategy.P5 -> R.string.frame_5
    FrameStrategy.P10 -> R.string.frame_10
    FrameStrategy.P25 -> R.string.frame_25
    FrameStrategy.MIDDLE -> R.string.frame_middle
    FrameStrategy.AUTO -> R.string.frame_auto
}

private fun thumbSizeLabel(size: ThumbSize): Int = when (size) {
    ThumbSize.SMALL -> R.string.thumb_size_small
    ThumbSize.MEDIUM -> R.string.thumb_size_medium
    ThumbSize.LARGE -> R.string.thumb_size_large
    ThumbSize.XLARGE -> R.string.thumb_size_xlarge
}

private fun themeLabel(mode: ThemeMode): Int = when (mode) {
    ThemeMode.SYSTEM -> R.string.theme_system
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
}

private fun languageLabel(mode: LanguageMode): Int = when (mode) {
    LanguageMode.SYSTEM -> R.string.language_system
    LanguageMode.EN -> R.string.language_en
    LanguageMode.AR -> R.string.language_ar
}

private fun viewModeLabel(mode: ViewMode): Int = when (mode) {
    ViewMode.LIST -> R.string.view_list
    ViewMode.COMPACT_LIST -> R.string.view_compact_list
    ViewMode.GRID_SMALL -> R.string.view_grid_small
    ViewMode.GRID_MEDIUM -> R.string.view_grid_medium
    ViewMode.GRID_LARGE -> R.string.view_grid_large
    ViewMode.GRID_HUGE -> R.string.view_grid_huge
}

private fun sortLabel(mode: SortMode): Int = when (mode) {
    SortMode.NAME_ASC -> R.string.sort_name_asc
    SortMode.NAME_DESC -> R.string.sort_name_desc
    SortMode.NEWEST -> R.string.sort_newest
    SortMode.OLDEST -> R.string.sort_oldest
    SortMode.LARGEST -> R.string.sort_largest
    SortMode.SMALLEST -> R.string.sort_smallest
    SortMode.TYPE -> R.string.sort_type
    SortMode.DURATION -> R.string.sort_duration
}

/* ---------------------------------------------------------------------------
 * Rows — one per setting, with the control that matches it
 * ------------------------------------------------------------------------- */

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = AppSpacing.md, bottom = 2.dp, start = AppSpacing.xs),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(AppRadius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(vertical = AppSpacing.xs)) { content() }
    }
}

@Composable
private fun SwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (!subtitle.isNullOrEmpty()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.md))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(title: String, subtitle: String?, value: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.width(AppSpacing.sm))
            Text(
                text = value.bidiLtr(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, subtitle: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        if (!subtitle.isNullOrEmpty()) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (value.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = value.bidiLtr(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    subtitle: String? = null,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = AppSpacing.sm, vertical = AppSpacing.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = when {
                    !enabled -> MaterialTheme.colorScheme.outline
                    destructive -> MaterialTheme.colorScheme.error
                    else -> MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(AppSpacing.md))
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = when {
                        !enabled -> MaterialTheme.colorScheme.outline
                        destructive -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
                )
                if (!subtitle.isNullOrEmpty()) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** One cache bucket: what it is, how much it holds, and a single clear action. */
@Composable
private fun CacheKindRow(
    label: String,
    count: Int,
    bytes: Long,
    enabled: Boolean,
    onClear: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg, end = AppSpacing.sm, top = AppSpacing.xs, bottom = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = stringResource(R.string.cache_kind_detail, count, Formatters.size(bytes)).bidiLtr(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(onClick = onClear, enabled = enabled) {
            Text(
                text = stringResource(R.string.action_clear),
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.outline
                },
            )
        }
    }
}

@Composable
private fun SliderRow(
    title: String,
    value: Float,
    valueLabel: String,
    range: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.xs),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = valueLabel.bidiLtr(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = ((range.endInclusive - range.start) / 2f).toInt() - 1,
        )
    }
}

/** One storage volume: identity, access state and space, without any action of its own. */
@Composable
private fun VolumeRow(
    name: String,
    icon: ImageVector,
    accent: androidx.compose.ui.graphics.Color,
    state: VolumeState,
    detail: String,
) {
    val extended = AppTheme.extended
    val stateColor = when (state) {
        VolumeState.READY -> extended.success
        VolumeState.NEEDS_PERMISSION -> extended.warning
        VolumeState.UNMOUNTED -> MaterialTheme.colorScheme.outline
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = AppSpacing.lg, vertical = AppSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = if (AppTheme.isDark) 0.18f else 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(AppSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = name.bidiName(),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail.bidiLtr(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(AppSpacing.sm))
        Box(
            Modifier
                .size(9.dp)
                .clip(CircleShape)
                .background(stateColor),
        )
    }
}

/** A permission with its real state and the only action that makes sense for that state. */
@Composable
private fun PermissionRow(label: String, granted: Boolean, blocked: Boolean, onGrant: () -> Unit) {
    val extended = AppTheme.extended
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = AppSpacing.lg, end = AppSpacing.sm, top = AppSpacing.xs, bottom = AppSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (granted) Icons.Outlined.SettingsSuggest else Icons.Outlined.Storage,
            contentDescription = null,
            tint = if (granted) extended.success else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(AppSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = stringResource(
                    when {
                        granted -> R.string.perm_state_granted
                        blocked -> R.string.perm_state_blocked
                        else -> R.string.perm_state_denied
                    },
                ),
                style = MaterialTheme.typography.labelSmall,
                color = when {
                    granted -> extended.success
                    blocked -> extended.warning
                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
        if (!granted) {
            TextButton(onClick = onGrant) {
                Text(
                    text = stringResource(
                        if (blocked) R.string.action_open_settings else R.string.action_grant_access,
                    ),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

@Composable
private fun EmptySettingsRow(text: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(AppSpacing.lg),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Folder,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(AppSpacing.md))
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** One picker for every enum setting, so choices all look and behave the same. */
@Composable
private fun <T> EnumPicker(
    title: Int,
    options: List<T>,
    label: @Composable (T) -> String,
    selected: T,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    // Labels are resolved here (not inside the dialog lambda) to keep the dialog dumb.
    val labels = options.map { option -> label(option) }
    OptionDialog(
        title = stringResource(title),
        options = labels,
        selectedIndex = options.indexOf(selected).coerceAtLeast(0),
        onSelect = { onSelect(options[it]) },
        onDismiss = onDismiss,
    )
}

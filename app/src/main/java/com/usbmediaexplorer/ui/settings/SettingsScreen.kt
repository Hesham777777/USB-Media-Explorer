package com.usbmediaexplorer.ui.settings

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.settings.AppSettings
import com.usbmediaexplorer.data.settings.AspectMode
import com.usbmediaexplorer.data.settings.FrameStrategy
import com.usbmediaexplorer.data.settings.LanguageMode
import com.usbmediaexplorer.data.settings.SortMode
import com.usbmediaexplorer.data.settings.ThemeMode
import com.usbmediaexplorer.data.settings.ThumbSize
import com.usbmediaexplorer.data.settings.ViewMode
import com.usbmediaexplorer.ui.common.LocalAppContainer
import com.usbmediaexplorer.ui.nav.LocalNavigator
import com.usbmediaexplorer.ui.common.OptionDialog
import com.usbmediaexplorer.util.Formatters
import kotlinx.coroutines.launch

/** Settings screen — thumbnails, cache, appearance, browsing and playback (spec §26, §20). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(snackbarHostState: SnackbarHostState) {
    val container = LocalAppContainer.current
    val navigator = LocalNavigator.current
    val scope = rememberCoroutineScope()
    val settings by container.settingsRepository.settings
        .collectAsStateWithLifecycle(AppSettings.DEFAULT)

    var cacheBytes by remember { mutableLongStateOf(0L) }
    var cacheCount by remember { mutableIntStateOf(0) }
    var working by remember { mutableStateOf(false) }

    var picker by remember { mutableStateOf<Picker?>(null) }
    val cacheClearedLabel = stringResource(R.string.setting_clear_cache_done)

    val refreshCache: suspend () -> Unit = {
        cacheBytes = container.thumbnailRepository.cacheSizeBytes()
        cacheCount = container.thumbnailRepository.cacheEntryCount()
    }

    LaunchedEffect(Unit) { refreshCache() }

    fun toast(message: String) = scope.launch { snackbarHostState.showSnackbar(message) }

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
                title = { Text(stringResource(R.string.settings_title)) },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_thumbnails))
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
                    title = stringResource(R.string.setting_folder_previews),
                    subtitle = stringResource(R.string.setting_folder_previews_desc),
                    checked = settings.folderPreviewsEnabled,
                    onChange = { scope.launch { container.settingsRepository.setFolderPreviews(it) } },
                )
                ClickRow(
                    title = stringResource(R.string.setting_frame_position),
                    value = stringResource(frameStrategyLabel(settings.frameStrategy)),
                    onClick = { picker = Picker.FRAME },
                )
                ClickRow(
                    title = stringResource(R.string.setting_thumb_size),
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
                    title = stringResource(R.string.setting_prefer_embedded_cover),
                    subtitle = stringResource(R.string.setting_prefer_embedded_cover_desc),
                    checked = settings.preferEmbeddedCover,
                    onChange = { scope.launch { container.settingsRepository.setPreferEmbeddedCover(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_charging_only),
                    subtitle = null,
                    checked = settings.generateWhileChargingOnly,
                    onChange = { scope.launch { container.settingsRepository.setChargingOnly(it) } },
                )
            }

            SectionTitle(stringResource(R.string.setting_cache))
            SettingsCard {
                InfoRow(
                    title = stringResource(R.string.setting_cache_used),
                    value = Formatters.size(cacheBytes),
                    subtitle = stringResource(R.string.items_count, cacheCount),
                )
                ClickRow(
                    title = stringResource(R.string.setting_cache_limit),
                    value = Formatters.size(settings.cacheLimitBytes),
                    onClick = { picker = Picker.CACHE_LIMIT },
                )
                ActionRow(
                    title = stringResource(R.string.setting_clear_cache),
                    icon = { Icon(Icons.Outlined.DeleteSweep, contentDescription = null) },
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            val removed = container.thumbnailRepository.clearCache()
                            working = false
                            refreshCache()
                            toast("$cacheClearedLabel ($removed)")
                        }
                    },
                )
                ActionRow(
                    title = stringResource(R.string.setting_clean_orphans),
                    icon = { Icon(Icons.Outlined.CleaningServices, contentDescription = null) },
                    enabled = !working,
                    onClick = {
                        scope.launch {
                            working = true
                            val removed = container.thumbnailRepository.cleanOrphans()
                            working = false
                            refreshCache()
                            toast("$removed")
                        }
                    },
                )
                if (working) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
            }

            SectionTitle(stringResource(R.string.setting_appearance))
            SettingsCard {
                ClickRow(
                    title = stringResource(R.string.setting_theme),
                    value = stringResource(themeLabel(settings.themeMode)),
                    onClick = { picker = Picker.THEME },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_dynamic_color),
                    subtitle = null,
                    checked = settings.dynamicColor,
                    onChange = { scope.launch { container.settingsRepository.setDynamicColor(it) } },
                )
                ClickRow(
                    title = stringResource(R.string.setting_language),
                    value = stringResource(languageLabel(settings.languageMode)),
                    onClick = { picker = Picker.LANGUAGE },
                )
            }

            SectionTitle(stringResource(R.string.view_mode))
            SettingsCard {
                ClickRow(
                    title = stringResource(R.string.view_mode),
                    value = stringResource(viewModeLabel(settings.defaultViewMode)),
                    onClick = { picker = Picker.VIEW_MODE },
                )
                ClickRow(
                    title = stringResource(R.string.sort_by),
                    value = stringResource(sortLabel(settings.defaultSortMode)),
                    onClick = { picker = Picker.SORT_MODE },
                )
                SwitchRow(
                    title = stringResource(R.string.sort_folders_first),
                    subtitle = null,
                    checked = settings.foldersFirst,
                    onChange = { scope.launch { container.settingsRepository.setFoldersFirst(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_show_hidden),
                    subtitle = null,
                    checked = settings.showHiddenFiles,
                    onChange = { scope.launch { container.settingsRepository.setShowHidden(it) } },
                )
                SwitchRow(
                    title = stringResource(R.string.setting_lazy_metadata),
                    subtitle = stringResource(R.string.setting_lazy_metadata_desc),
                    checked = settings.lazyMetadata,
                    onChange = { scope.launch { container.settingsRepository.setLazyMetadata(it) } },
                )
            }

            SectionTitle(stringResource(R.string.setting_player))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.setting_resume_prompt),
                    subtitle = null,
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
                    title = "Keep screen on",
                    subtitle = null,
                    checked = settings.keepScreenOn,
                    onChange = { scope.launch { container.settingsRepository.setKeepScreenOn(it) } },
                )
                ClickRow(
                    title = stringResource(R.string.player_aspect),
                    value = stringResource(
                        when (settings.defaultAspectMode) {
                            AspectMode.FIT -> R.string.aspect_fit
                            AspectMode.FILL -> R.string.aspect_fill
                            AspectMode.STRETCH -> R.string.aspect_stretch
                            AspectMode.ZOOM -> R.string.aspect_zoom
                        },
                    ),
                    onClick = { picker = Picker.ASPECT },
                )
            }

            SectionTitle(stringResource(R.string.setting_about))
            SettingsCard {
                InfoRow(
                    title = stringResource(R.string.app_name),
                    value = "0.1.0",
                    subtitle = stringResource(R.string.setting_about_desc),
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    when (picker) {
        Picker.FRAME -> {
            val options = FrameStrategy.entries.toList()
            OptionDialog(
                title = stringResource(R.string.setting_frame_position),
                options = options.map { stringResource(frameStrategyLabel(it)) },
                selectedIndex = options.indexOf(settings.frameStrategy),
                onSelect = { scope.launch { container.settingsRepository.setFrameStrategy(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        Picker.THUMB_SIZE -> {
            val options = ThumbSize.entries.toList()
            OptionDialog(
                title = stringResource(R.string.setting_thumb_size),
                options = options.map { stringResource(thumbSizeLabel(it)) + " • ${it.px}px" },
                selectedIndex = options.indexOf(settings.thumbSize),
                onSelect = { scope.launch { container.settingsRepository.setThumbSize(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        Picker.CACHE_LIMIT -> {
            val options = listOf(64L, 128L, 256L, 512L, 1024L, 2048L, 4096L)
                .map { it * 1024 * 1024 }
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

        Picker.THEME -> {
            val options = ThemeMode.entries.toList()
            OptionDialog(
                title = stringResource(R.string.setting_theme),
                options = options.map { stringResource(themeLabel(it)) },
                selectedIndex = options.indexOf(settings.themeMode),
                onSelect = { scope.launch { container.settingsRepository.setThemeMode(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        Picker.LANGUAGE -> {
            val options = LanguageMode.entries.toList()
            OptionDialog(
                title = stringResource(R.string.setting_language),
                options = options.map { stringResource(languageLabel(it)) },
                selectedIndex = options.indexOf(settings.languageMode),
                onSelect = { scope.launch { container.settingsRepository.setLanguage(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        Picker.VIEW_MODE -> {
            val options = ViewMode.entries.toList()
            OptionDialog(
                title = stringResource(R.string.view_mode),
                options = options.map { stringResource(viewModeLabel(it)) },
                selectedIndex = options.indexOf(settings.defaultViewMode),
                onSelect = { scope.launch { container.settingsRepository.setDefaultViewMode(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        Picker.SORT_MODE -> {
            val options = SortMode.entries.toList()
            OptionDialog(
                title = stringResource(R.string.sort_by),
                options = options.map { stringResource(sortLabel(it)) },
                selectedIndex = options.indexOf(settings.defaultSortMode),
                onSelect = { scope.launch { container.settingsRepository.setDefaultSortMode(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        Picker.ASPECT -> {
            val options = AspectMode.entries.toList()
            OptionDialog(
                title = stringResource(R.string.player_aspect),
                options = options.map {
                    stringResource(
                        when (it) {
                            AspectMode.FIT -> R.string.aspect_fit
                            AspectMode.FILL -> R.string.aspect_fill
                            AspectMode.STRETCH -> R.string.aspect_stretch
                            AspectMode.ZOOM -> R.string.aspect_zoom
                        },
                    )
                },
                selectedIndex = options.indexOf(settings.defaultAspectMode),
                onSelect = { scope.launch { container.settingsRepository.setAspectMode(options[it]) } },
                onDismiss = { picker = null },
            )
        }

        null -> Unit
    }
}

private enum class Picker {
    FRAME, THUMB_SIZE, CACHE_LIMIT, THEME, LANGUAGE, VIEW_MODE, SORT_MODE, ASPECT
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

// ---- rows ----------------------------------------------------------------

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) { content() }
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
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ClickRow(title: String, value: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

@Composable
private fun InfoRow(title: String, value: String, subtitle: String?) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}

@Composable
private fun ActionRow(
    title: String,
    icon: @Composable () -> Unit,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
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
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                text = valueLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = range,
            steps = ((range.endInclusive - range.startInclusive) / 2f).toInt() - 1,
        )
    }
}

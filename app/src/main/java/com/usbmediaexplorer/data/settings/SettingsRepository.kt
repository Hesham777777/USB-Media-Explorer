package com.usbmediaexplorer.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/** Persists [AppSettings] in a Jetpack DataStore. All reads are cold-safe and non-blocking. */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val VIDEO_THUMBS = booleanPreferencesKey("video_thumbnails")
        val IMAGE_THUMBS = booleanPreferencesKey("image_thumbnails")
        val FOLDER_PREVIEWS = booleanPreferencesKey("folder_previews")
        val FOLDER_PREVIEW_COUNT = intPreferencesKey("folder_preview_count")
        val THUMB_SIZE = stringPreferencesKey("thumb_size")
        val THUMB_QUALITY = intPreferencesKey("thumb_quality")
        val FRAME_STRATEGY = stringPreferencesKey("frame_strategy")
        val PREFER_COVER = booleanPreferencesKey("prefer_embedded_cover")
        val CHARGING_ONLY = booleanPreferencesKey("charging_only")
        val FOLDER_STYLE = stringPreferencesKey("folder_preview_style")
        val POSTER_COVERS = booleanPreferencesKey("poster_covers_first")
        val CACHE_LIMIT = longPreferencesKey("cache_limit")
        val CACHE_ENABLED = booleanPreferencesKey("cache_enabled")
        val THEME = stringPreferencesKey("theme")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val LANGUAGE = stringPreferencesKey("language")
        val VIEW_MODE = stringPreferencesKey("view_mode")
        val SORT_MODE = stringPreferencesKey("sort_mode")
        val FOLDERS_FIRST = booleanPreferencesKey("folders_first")
        val SHOW_HIDDEN = booleanPreferencesKey("show_hidden")
        val LAZY_METADATA = booleanPreferencesKey("lazy_metadata")
        val PER_FOLDER_VIEW = booleanPreferencesKey("per_folder_view")
        val RESUME_PROMPT = booleanPreferencesKey("resume_prompt")
        val AUTO_SUBTITLES = booleanPreferencesKey("auto_subtitles")
        val SPEED = floatPreferencesKey("speed")
        val ASPECT = stringPreferencesKey("aspect")
        val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data
        .catch { emit(androidx.datastore.preferences.core.emptyPreferences()) }
        .map { prefs -> prefs.toSettings() }

    suspend fun update(block: AppSettings.() -> AppSettings) {
        context.settingsDataStore.edit { prefs ->
            val current = prefs.toSettings()
            val next = current.block()
            prefs.write(next)
        }
    }

    // ---- individual setters used by the settings screen -------------------
    suspend fun setVideoThumbnails(value: Boolean) = set(Keys.VIDEO_THUMBS, value)
    suspend fun setImageThumbnails(value: Boolean) = set(Keys.IMAGE_THUMBS, value)
    suspend fun setFolderPreviews(value: Boolean) = set(Keys.FOLDER_PREVIEWS, value)
    suspend fun setThumbSize(value: ThumbSize) = set(Keys.THUMB_SIZE, value.name)
    suspend fun setThumbQuality(value: Int) = set(Keys.THUMB_QUALITY, value.coerceIn(30, 100))
    suspend fun setFrameStrategy(value: FrameStrategy) = set(Keys.FRAME_STRATEGY, value.name)
    suspend fun setPreferEmbeddedCover(value: Boolean) = set(Keys.PREFER_COVER, value)
    suspend fun setChargingOnly(value: Boolean) = set(Keys.CHARGING_ONLY, value)
    suspend fun setCacheLimit(bytes: Long) = set(Keys.CACHE_LIMIT, bytes)
    suspend fun setThemeMode(value: ThemeMode) = set(Keys.THEME, value.name)
    suspend fun setDynamicColor(value: Boolean) = set(Keys.DYNAMIC_COLOR, value)
    suspend fun setLanguage(value: LanguageMode) = set(Keys.LANGUAGE, value.name)
    suspend fun setDefaultViewMode(value: ViewMode) = set(Keys.VIEW_MODE, value.name)
    suspend fun setDefaultSortMode(value: SortMode) = set(Keys.SORT_MODE, value.name)
    suspend fun setFoldersFirst(value: Boolean) = set(Keys.FOLDERS_FIRST, value)
    suspend fun setShowHidden(value: Boolean) = set(Keys.SHOW_HIDDEN, value)
    suspend fun setLazyMetadata(value: Boolean) = set(Keys.LAZY_METADATA, value)
    suspend fun setRememberPerFolderView(value: Boolean) = set(Keys.PER_FOLDER_VIEW, value)
    suspend fun setResumePrompt(value: Boolean) = set(Keys.RESUME_PROMPT, value)
    suspend fun setAutoDetectSubtitles(value: Boolean) = set(Keys.AUTO_SUBTITLES, value)
    suspend fun setPlaybackSpeed(value: Float) = set(Keys.SPEED, value)
    suspend fun setAspectMode(value: AspectMode) = set(Keys.ASPECT, value.name)
    suspend fun setKeepScreenOn(value: Boolean) = set(Keys.KEEP_SCREEN_ON, value)

    suspend fun setFolderPreviewStyle(value: FolderPreviewStyle) =
        set(Keys.FOLDER_STYLE, value.name)

    suspend fun setPosterCoversFirst(value: Boolean) = set(Keys.POSTER_COVERS, value)

    private suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { it[key] = value }
    }

    private fun Preferences.toSettings(): AppSettings = AppSettings(
        videoThumbnailsEnabled = this[Keys.VIDEO_THUMBS] ?: true,
        imageThumbnailsEnabled = this[Keys.IMAGE_THUMBS] ?: true,
        folderPreviewsEnabled = this[Keys.FOLDER_PREVIEWS] ?: true,
        folderPreviewMaxChildren = this[Keys.FOLDER_PREVIEW_COUNT] ?: 4,
        thumbSize = enumOrDefault(this[Keys.THUMB_SIZE], ThumbSize.LARGE),
        thumbQuality = (this[Keys.THUMB_QUALITY] ?: 82).coerceIn(30, 100),
        frameStrategy = enumOrDefault(this[Keys.FRAME_STRATEGY], FrameStrategy.AUTO),
        preferEmbeddedCover = this[Keys.PREFER_COVER] ?: false,
        generateWhileChargingOnly = this[Keys.CHARGING_ONLY] ?: false,
        folderPreviewStyle = enumOrDefault(this[Keys.FOLDER_STYLE], FolderPreviewStyle.WINDOWS),
        posterCoversFirst = this[Keys.POSTER_COVERS] ?: true,
        cacheLimitBytes = (this[Keys.CACHE_LIMIT] ?: (512L * 1024 * 1024))
            .coerceIn(32L * 1024 * 1024, 8L * 1024 * 1024 * 1024),
        cacheEnabled = this[Keys.CACHE_ENABLED] ?: true,
        themeMode = enumOrDefault(this[Keys.THEME], ThemeMode.SYSTEM),
        dynamicColor = this[Keys.DYNAMIC_COLOR] ?: true,
        languageMode = enumOrDefault(this[Keys.LANGUAGE], LanguageMode.SYSTEM),
        defaultViewMode = enumOrDefault(this[Keys.VIEW_MODE], ViewMode.POSTER),
        defaultSortMode = enumOrDefault(this[Keys.SORT_MODE], SortMode.NAME_ASC),
        foldersFirst = this[Keys.FOLDERS_FIRST] ?: true,
        showHiddenFiles = this[Keys.SHOW_HIDDEN] ?: false,
        lazyMetadata = this[Keys.LAZY_METADATA] ?: true,
        rememberPerFolderView = this[Keys.PER_FOLDER_VIEW] ?: true,
        resumePromptEnabled = this[Keys.RESUME_PROMPT] ?: true,
        autoDetectSubtitles = this[Keys.AUTO_SUBTITLES] ?: true,
        defaultPlaybackSpeed = this[Keys.SPEED] ?: 1.0f,
        defaultAspectMode = enumOrDefault(this[Keys.ASPECT], AspectMode.FIT),
        keepScreenOn = this[Keys.KEEP_SCREEN_ON] ?: true,
    )

    private fun MutablePreferences.write(settings: AppSettings) {
        this[Keys.VIDEO_THUMBS] = settings.videoThumbnailsEnabled
        this[Keys.IMAGE_THUMBS] = settings.imageThumbnailsEnabled
        this[Keys.FOLDER_PREVIEWS] = settings.folderPreviewsEnabled
        this[Keys.FOLDER_PREVIEW_COUNT] = settings.folderPreviewMaxChildren
        this[Keys.THUMB_SIZE] = settings.thumbSize.name
        this[Keys.THUMB_QUALITY] = settings.thumbQuality
        this[Keys.FRAME_STRATEGY] = settings.frameStrategy.name
        this[Keys.PREFER_COVER] = settings.preferEmbeddedCover
        this[Keys.CHARGING_ONLY] = settings.generateWhileChargingOnly
        this[Keys.FOLDER_STYLE] = settings.folderPreviewStyle.name
        this[Keys.POSTER_COVERS] = settings.posterCoversFirst
        this[Keys.CACHE_LIMIT] = settings.cacheLimitBytes
        this[Keys.CACHE_ENABLED] = settings.cacheEnabled
        this[Keys.THEME] = settings.themeMode.name
        this[Keys.DYNAMIC_COLOR] = settings.dynamicColor
        this[Keys.LANGUAGE] = settings.languageMode.name
        this[Keys.VIEW_MODE] = settings.defaultViewMode.name
        this[Keys.SORT_MODE] = settings.defaultSortMode.name
        this[Keys.FOLDERS_FIRST] = settings.foldersFirst
        this[Keys.SHOW_HIDDEN] = settings.showHiddenFiles
        this[Keys.LAZY_METADATA] = settings.lazyMetadata
        this[Keys.PER_FOLDER_VIEW] = settings.rememberPerFolderView
        this[Keys.RESUME_PROMPT] = settings.resumePromptEnabled
        this[Keys.AUTO_SUBTITLES] = settings.autoDetectSubtitles
        this[Keys.SPEED] = settings.defaultPlaybackSpeed
        this[Keys.ASPECT] = settings.defaultAspectMode.name
        this[Keys.KEEP_SCREEN_ON] = settings.keepScreenOn
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(value: String?, fallback: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: fallback
}

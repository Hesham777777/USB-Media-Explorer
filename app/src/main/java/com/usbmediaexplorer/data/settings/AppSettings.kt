package com.usbmediaexplorer.data.settings

/** How the grid presents items. Column counts adapt to orientation. */
enum class ViewMode(val columnsPortrait: Int, val columnsLandscape: Int, val aspectRatio: Float) {
    LIST(1, 1, 0f),
    GRID_SMALL(4, 6, 1f),
    GRID_MEDIUM(3, 4, 1f),
    GRID_LARGE(2, 3, 16f / 9f),
    GRID_HUGE(1, 2, 16f / 9f),

    /**
     * Movie-poster cards: portrait 2:3 tiles like a poster wall. The artwork itself is still
     * taken from the file (embedded cover art first, then the best frame) — never from the
     * internet — it is only presented in poster proportions with the title over a scrim.
     */
    POSTER(2, 4, 2f / 3f),
}

/**
 * How a folder is drawn when previews are on (spec §7).
 *
 * [MONTAGE] is the plain 2×2 mosaic; [WINDOWS] draws an actual folder shape whose pocket is
 * filled with the folder's own media, the way Windows Explorer renders media folders.
 */
enum class FolderPreviewStyle { MONTAGE, WINDOWS }

enum class SortMode {
    NAME_ASC,
    NAME_DESC,
    NEWEST,
    OLDEST,
    LARGEST,
    SMALLEST,
    TYPE,
    DURATION,
}

/**
 * Which point in time a video thumbnail is taken from.
 * The spec forbids "always the first frame" because movies usually open on a black/studio card.
 */
enum class FrameStrategy(val fraction: Double) {
    FIRST(0.0),
    P5(0.05),
    P10(0.10),
    P25(0.25),
    MIDDLE(0.50),
    AUTO(-1.0),
}

/** Longest edge of a generated thumbnail, in pixels. */
enum class ThumbSize(val px: Int) {
    SMALL(256),
    MEDIUM(384),
    LARGE(512),
    XLARGE(768),
}

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LanguageMode(val tag: String?) {
    SYSTEM(null),
    EN("en"),
    AR("ar"),
}

/** Aspect ratio behaviour of the player surface (maps onto Media3 ResizeMode). */
enum class AspectMode { FIT, FILL, STRETCH, ZOOM }

/** Everything the user can configure, materialised from the DataStore. */
data class AppSettings(
    // Thumbnails
    val videoThumbnailsEnabled: Boolean = true,
    val imageThumbnailsEnabled: Boolean = true,
    val folderPreviewsEnabled: Boolean = true,
    val folderPreviewMaxChildren: Int = 4,
    val thumbSize: ThumbSize = ThumbSize.LARGE,
    val thumbQuality: Int = 82,
    val frameStrategy: FrameStrategy = FrameStrategy.AUTO,
    val preferEmbeddedCover: Boolean = false,
    val generateWhileChargingOnly: Boolean = false,
    /** Folder previews: mosaic (2×2) or a Windows-style folder filled with its own media. */
    val folderPreviewStyle: FolderPreviewStyle = FolderPreviewStyle.WINDOWS,
    /** In poster view, prefer the cover art stored inside the file over an extracted frame. */
    val posterCoversFirst: Boolean = true,

    // Cache
    val cacheLimitBytes: Long = 512L * 1024 * 1024,
    val cacheEnabled: Boolean = true,

    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,

    // Browsing
    val defaultViewMode: ViewMode = ViewMode.POSTER,
    val defaultSortMode: SortMode = SortMode.NAME_ASC,
    val foldersFirst: Boolean = true,
    val showHiddenFiles: Boolean = false,
    val lazyMetadata: Boolean = true,
    val rememberPerFolderView: Boolean = true,

    // Playback
    val resumePromptEnabled: Boolean = true,
    val autoDetectSubtitles: Boolean = true,
    val defaultPlaybackSpeed: Float = 1.0f,
    val defaultAspectMode: AspectMode = AspectMode.FIT,
    val keepScreenOn: Boolean = true,
) {
    companion object {
        val DEFAULT = AppSettings()
    }
}

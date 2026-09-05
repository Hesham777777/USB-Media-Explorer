package com.usbmediaexplorer.data.settings

/**
 * How items are presented (spec §6): two list densities and four grid densities.
 *
 * Column counts adapt to orientation, and the item-size preference shifts them by one. Values are
 * persisted by name, so an entry added later is simply ignored by older saved preferences.
 */
enum class ViewMode(val columnsPortrait: Int, val columnsLandscape: Int, val aspectRatio: Float) {
    LIST(1, 1, 0f),
    COMPACT_LIST(1, 1, 0f),
    GRID_SMALL(4, 6, 1f),
    GRID_MEDIUM(3, 4, 1f),
    GRID_LARGE(2, 3, 16f / 9f),
    GRID_HUGE(1, 2, 16f / 9f);

    /** Both list densities render as rows; everything else is a grid. */
    val isList: Boolean get() = this == LIST || this == COMPACT_LIST
}

/**
 * Tile ratio used for folders that have a cover image inside them (Folder Cover). A poster found
 * in the folder is shown whole — never stretched, never cropped into a folder drawing.
 */
const val FOLDER_COVER_ASPECT = 2f / 3f

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

/** How much room each row/tile gets. A dense list shows more files; a large one shows more art. */
enum class ItemScale(val rowHeight: Int, val thumb: Int, val columnsDelta: Int) {
    COMPACT(48, 40, 1),
    NORMAL(64, 52, 0),
    LARGE(80, 68, -1),
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
    /** Use a poster image found inside a folder as that folder's cover in grid views. */
    val folderCoversEnabled: Boolean = true,
    /** Read the album art embedded in audio files and show it as the track's cover. */
    val audioArtEnabled: Boolean = true,
    /** How many children of a folder are inspected while looking for its cover image. */
    val folderCoverScanLimit: Int = 24,
    val thumbSize: ThumbSize = ThumbSize.LARGE,
    val thumbQuality: Int = 82,
    val frameStrategy: FrameStrategy = FrameStrategy.AUTO,
    val preferEmbeddedCover: Boolean = false,
    val generateWhileChargingOnly: Boolean = false,

    // Cache
    val cacheLimitBytes: Long = 512L * 1024 * 1024,
    val cacheEnabled: Boolean = true,

    // Appearance
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val languageMode: LanguageMode = LanguageMode.SYSTEM,

    // Browsing
    val defaultViewMode: ViewMode = ViewMode.GRID_MEDIUM,
    val defaultSortMode: SortMode = SortMode.NAME_ASC,
    val foldersFirst: Boolean = true,
    val showHiddenFiles: Boolean = false,
    /** Show the extension next to a file name (`movie.mkv`) instead of the bare name. */
    val showExtensions: Boolean = true,
    /** Show size/duration/resolution under each item. Turning it off gives a cleaner grid. */
    val showMediaInfo: Boolean = true,
    val itemScale: ItemScale = ItemScale.NORMAL,
    val lazyMetadata: Boolean = true,
    val rememberPerFolderView: Boolean = true,

    // Onboarding
    /** True once the first-launch permission request has been answered (granted or denied). */
    val firstRunPermissionsAsked: Boolean = false,

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

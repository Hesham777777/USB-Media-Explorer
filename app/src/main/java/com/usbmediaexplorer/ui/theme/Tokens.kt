package com.usbmediaexplorer.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Design tokens shared by every screen.
 *
 * One spacing scale, one radius scale, one elevation scale: consistency comes from using the same
 * numbers everywhere instead of re-deciding per component. Elevation is deliberately almost
 * invisible — separation comes from surface tones and hairlines, not from shadows.
 */
object AppSpacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp

    /** Horizontal page margin. */
    val screen = 16.dp

    /** Inner padding of a card. */
    val card = 14.dp

    /** Gap between grid tiles. */
    val gridGap = 10.dp

    /** Gap between list rows. */
    val listGap = 6.dp
}

/** Corner radii: balanced, never pillowy. */
object AppRadius {
    val xs = 6.dp
    val sm = 10.dp
    val md = 14.dp
    val lg = 18.dp
    val xl = 24.dp
    val pill = 100.dp
}

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(AppRadius.sm),
    medium = RoundedCornerShape(AppRadius.md),
    large = RoundedCornerShape(AppRadius.lg),
    extraLarge = RoundedCornerShape(26.dp),
)

/** Shadows are nearly flat: a file manager is read at a glance, not admired for its depth. */
object AppElevation {
    val level0 = 0.dp
    val level1 = 1.dp
    val level2 = 2.dp
    val level3 = 4.dp
}

/** Touch targets: 48 dp for a primary target, 40 dp for dense toolbars (Material minimum). */
object AppTouch {
    val min = 48.dp
    val compact = 40.dp
    val chip = 32.dp
}

/** Sizes used by the media tiles so every screen agrees on what "compact" means. */
object AppSize {
    val listThumb = 52.dp
    val compactListThumb = 40.dp
    val rowIcon = 36.dp
    val chipIcon = 18.dp
    val usageRing = 46.dp
    val usageRingSmall = 34.dp
}

/**
 * Colours Material 3 does not define but a file manager needs: success, warning, the storage-kind
 * accents and the skeleton shimmer. They are semantic — never the primary colour.
 */
@Immutable
data class ExtendedColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val usb: Color,
    val sd: Color,
    val playerScrim: Color,
    val skeleton: Color,
    val skeletonShine: Color,
)

val LightExtended = ExtendedColors(
    success = Palette.SuccessLight,
    onSuccess = Palette.OnSuccessLight,
    successContainer = Palette.SuccessContainerLight,
    onSuccessContainer = Palette.OnSuccessContainerLight,
    warning = Palette.WarningLight,
    onWarning = Palette.OnWarningLight,
    warningContainer = Palette.WarningContainerLight,
    onWarningContainer = Palette.OnWarningContainerLight,
    usb = Palette.UsbLight,
    sd = Palette.SdLight,
    playerScrim = Palette.PlayerScrim,
    skeleton = Color(0xFFE7E4EE),
    skeletonShine = Color(0xFFF4F2F9),
)

val DarkExtended = ExtendedColors(
    success = Palette.SuccessDark,
    onSuccess = Palette.OnSuccessDark,
    successContainer = Palette.SuccessContainerDark,
    onSuccessContainer = Palette.OnSuccessContainerDark,
    warning = Palette.WarningDark,
    onWarning = Palette.OnWarningDark,
    warningContainer = Palette.WarningContainerDark,
    onWarningContainer = Palette.OnWarningContainerDark,
    usb = Palette.UsbDark,
    sd = Palette.SdDark,
    playerScrim = Palette.PlayerScrim,
    skeleton = Color(0xFF23232C),
    skeletonShine = Color(0xFF2E2E39),
)

val LocalExtendedColors = staticCompositionLocalOf { LightExtended }

/** True when the dark scheme is active (the colour scheme itself does not expose this). */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** One entry point for the whole design system: `AppTheme.extended`, `AppTheme.isDark`. */
object AppTheme {
    val extended: ExtendedColors
        @Composable @ReadOnlyComposable get() = LocalExtendedColors.current

    val isDark: Boolean
        @Composable @ReadOnlyComposable get() = LocalDarkTheme.current
}

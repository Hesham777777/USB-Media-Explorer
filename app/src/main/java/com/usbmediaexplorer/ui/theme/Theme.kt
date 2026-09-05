package com.usbmediaexplorer.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.usbmediaexplorer.data.settings.ThemeMode

/**
 * The application theme.
 *
 * Three layers, in this order of precedence:
 *  1. Material You dynamic colour (Android 12+) when the user leaves it on — the app follows the
 *     wallpaper, which is what a modern Android file manager is expected to do,
 *  2. the brand palette in [Palette] (violet identity over neutral surfaces),
 *  3. the semantic tokens in [ExtendedColors], which dynamic colour does not provide: success,
 *     warning, the USB/SD accents and the skeleton colours stay ours in every mode.
 */
private val LightColors = lightColorScheme(
    primary = Palette.PrimaryLight,
    onPrimary = Palette.OnPrimaryLight,
    primaryContainer = Palette.PrimaryContainerLight,
    onPrimaryContainer = Palette.OnPrimaryContainerLight,
    secondary = Palette.SecondaryLight,
    onSecondary = Palette.OnSecondaryLight,
    secondaryContainer = Palette.SecondaryContainerLight,
    onSecondaryContainer = Palette.OnSecondaryContainerLight,
    tertiary = Palette.TertiaryLight,
    onTertiary = Palette.OnTertiaryLight,
    tertiaryContainer = Palette.TertiaryContainerLight,
    onTertiaryContainer = Palette.OnTertiaryContainerLight,
    background = Palette.BackgroundLight,
    onBackground = Palette.OnBackgroundLight,
    surface = Palette.SurfaceLight,
    onSurface = Palette.OnSurfaceLight,
    surfaceVariant = Palette.SurfaceVariantLight,
    onSurfaceVariant = Palette.OnSurfaceVariantLight,
    outline = Palette.OutlineLight,
    outlineVariant = Palette.OutlineVariantLight,
    surfaceContainerLowest = Palette.SurfaceContainerLowestLight,
    surfaceContainerLow = Palette.SurfaceContainerLowLight,
    surfaceContainer = Palette.SurfaceContainerLight,
    surfaceContainerHigh = Palette.SurfaceContainerHighLight,
    surfaceContainerHighest = Palette.SurfaceContainerHighestLight,
    surfaceDim = Palette.SurfaceDimLight,
    surfaceBright = Palette.SurfaceBrightLight,
    error = Palette.ErrorLight,
    onError = Palette.OnErrorLight,
    errorContainer = Palette.ErrorContainerLight,
    onErrorContainer = Palette.OnErrorContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = Palette.PrimaryDark,
    onPrimary = Palette.OnPrimaryDark,
    primaryContainer = Palette.PrimaryContainerDark,
    onPrimaryContainer = Palette.OnPrimaryContainerDark,
    secondary = Palette.SecondaryDark,
    onSecondary = Palette.OnSecondaryDark,
    secondaryContainer = Palette.SecondaryContainerDark,
    onSecondaryContainer = Palette.OnSecondaryContainerDark,
    tertiary = Palette.TertiaryDark,
    onTertiary = Palette.OnTertiaryDark,
    tertiaryContainer = Palette.TertiaryContainerDark,
    onTertiaryContainer = Palette.OnTertiaryContainerDark,
    background = Palette.BackgroundDark,
    onBackground = Palette.OnBackgroundDark,
    surface = Palette.SurfaceDark,
    onSurface = Palette.OnSurfaceDark,
    surfaceVariant = Palette.SurfaceVariantDark,
    onSurfaceVariant = Palette.OnSurfaceVariantDark,
    outline = Palette.OutlineDark,
    outlineVariant = Palette.OutlineVariantDark,
    surfaceContainerLowest = Palette.SurfaceContainerLowestDark,
    surfaceContainerLow = Palette.SurfaceContainerLowDark,
    surfaceContainer = Palette.SurfaceContainerDark,
    surfaceContainerHigh = Palette.SurfaceContainerHighDark,
    surfaceContainerHighest = Palette.SurfaceContainerHighestDark,
    surfaceDim = Palette.SurfaceDimDark,
    surfaceBright = Palette.SurfaceBrightDark,
    error = Palette.ErrorDark,
    onError = Palette.OnErrorDark,
    errorContainer = Palette.ErrorContainerDark,
    onErrorContainer = Palette.OnErrorContainerDark,
)

/** True while the user is inside the player, where the UI goes fully dark regardless of theme. */
val LocalImmersive = staticCompositionLocalOf { false }

@Composable
fun LocalImmersiveProvider(value: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalImmersive provides value, content = content)
}

@Composable
fun UsbMediaExplorerTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)

        dark -> DarkColors
        else -> LightColors
    }

    CompositionLocalProvider(
        LocalExtendedColors provides if (dark) DarkExtended else LightExtended,
        LocalDarkTheme provides dark,
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

/** All-black scheme for the video player so letterboxing blends with the bezel. */
val PlayerColors = darkColorScheme(
    primary = Palette.PrimaryDark,
    onPrimary = Palette.OnPrimaryDark,
    primaryContainer = Palette.PrimaryContainerDark,
    onPrimaryContainer = Palette.OnPrimaryContainerDark,
    secondary = Palette.SecondaryDark,
    onSecondary = Palette.OnSecondaryDark,
    surface = Color.Black,
    onSurface = Color(0xFFEDEDF2),
    background = Color.Black,
    onBackground = Color(0xFFEDEDF2),
    surfaceVariant = Color(0xFF2A2A33),
    onSurfaceVariant = Color(0xFFC9C5D0),
    surfaceContainer = Color(0xFF141419),
    surfaceContainerHigh = Color(0xFF1E1E26),
    surfaceContainerHighest = Color(0xFF28282F),
    outline = Color(0xFF6E6B77),
    outlineVariant = Color(0xFF3A3A44),
    error = Palette.ErrorDark,
    onError = Palette.OnErrorDark,
    errorContainer = Palette.ErrorContainerDark,
    onErrorContainer = Palette.OnErrorContainerDark,
)

/**
 * Wraps a subtree (the player, the photo viewer) in the immersive dark scheme, whatever the user's
 * theme is: media is watched in the dark, and light letterboxing looks broken.
 */
@Composable
fun ImmersiveMediaTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalExtendedColors provides DarkExtended,
        LocalDarkTheme provides true,
        LocalImmersive provides true,
    ) {
        MaterialTheme(
            colorScheme = PlayerColors,
            typography = AppTypography,
            shapes = AppShapes,
            content = content,
        )
    }
}

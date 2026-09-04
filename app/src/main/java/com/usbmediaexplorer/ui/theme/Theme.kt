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
import androidx.compose.ui.platform.LocalContext
import com.usbmediaexplorer.data.settings.ThemeMode

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

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}

/** All-black scheme for the video player so letterboxing blends with the bezel. */
val PlayerColors = darkColorScheme(
    primary = Palette.PrimaryDark,
    onPrimary = Palette.OnPrimaryDark,
    surface = androidx.compose.ui.graphics.Color.Black,
    onSurface = Palette.OnSurfaceDark,
    background = androidx.compose.ui.graphics.Color.Black,
    onBackground = Palette.OnBackgroundDark,
    surfaceVariant = Palette.SurfaceVariantDark,
    onSurfaceVariant = Palette.OnSurfaceVariantDark,
)

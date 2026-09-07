package com.usbmediaexplorer.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * Brand palette — the single source of colour truth for the app.
 *
 * Design intent (a file manager, not a settings screen):
 *  - **violet stays the identity**, but it is reserved for what matters: the primary action, the
 *    active selection, the brand mark. Everything the user reads is neutral.
 *  - **surfaces are neutral**, barely tinted, so thumbnails and posters — the real content of this
 *    app — are the most saturated things on screen.
 *  - **meaning gets its own colour**: success, warning and danger are semantic and never borrow the
 *    primary, so "delete" can never be mistaken for "open".
 *  - storage kinds are distinguishable at a glance: internal = violet, USB = teal, SD = amber.
 *
 * Used when Material You dynamic colour is unavailable (Android 11 and below) or switched off.
 */
object Palette {

    // ---- light -------------------------------------------------------------
    val PrimaryLight = Color(0xFF5B4BC4)
    val OnPrimaryLight = Color(0xFFFFFFFF)
    val PrimaryContainerLight = Color(0xFFE5E0FF)
    val OnPrimaryContainerLight = Color(0xFF150A4E)

    val SecondaryLight = Color(0xFF5D5C72)
    val OnSecondaryLight = Color(0xFFFFFFFF)
    val SecondaryContainerLight = Color(0xFFE2E0F9)
    val OnSecondaryContainerLight = Color(0xFF1A1A2C)

    val TertiaryLight = Color(0xFF00687A)
    val OnTertiaryLight = Color(0xFFFFFFFF)
    val TertiaryContainerLight = Color(0xFFAFEDFF)
    val OnTertiaryContainerLight = Color(0xFF001F26)

    val BackgroundLight = Color(0xFFFAF8FD)
    val OnBackgroundLight = Color(0xFF1A1A20)
    val SurfaceLight = Color(0xFFFAF8FD)
    val OnSurfaceLight = Color(0xFF1A1A20)
    val SurfaceVariantLight = Color(0xFFE4E1EC)
    val OnSurfaceVariantLight = Color(0xFF47464F)
    val OutlineLight = Color(0xFF787680)
    val OutlineVariantLight = Color(0xFFC9C5D0)

    val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
    val SurfaceContainerLowLight = Color(0xFFF5F2FA)
    val SurfaceContainerLight = Color(0xFFEFEDF5)
    val SurfaceContainerHighLight = Color(0xFFE9E7F0)
    val SurfaceContainerHighestLight = Color(0xFFE3E1EA)
    val SurfaceDimLight = Color(0xFFDBD9E1)
    val SurfaceBrightLight = Color(0xFFFAF8FD)

    val ErrorLight = Color(0xFFB3261E)
    val OnErrorLight = Color(0xFFFFFFFF)
    val ErrorContainerLight = Color(0xFFF9DEDC)
    val OnErrorContainerLight = Color(0xFF410E0B)

    // ---- dark --------------------------------------------------------------
    val PrimaryDark = Color(0xFFC6C0FF)
    val OnPrimaryDark = Color(0xFF2A1E7C)
    val PrimaryContainerDark = Color(0xFF4237A0)
    val OnPrimaryContainerDark = Color(0xFFE5E0FF)

    val SecondaryDark = Color(0xFFC6C3DD)
    val OnSecondaryDark = Color(0xFF2F2E42)
    val SecondaryContainerDark = Color(0xFF454459)
    val OnSecondaryContainerDark = Color(0xFFE2E0F9)

    val TertiaryDark = Color(0xFF5CD5FF)
    val OnTertiaryDark = Color(0xFF003541)
    val TertiaryContainerDark = Color(0xFF004E5C)
    val OnTertiaryContainerDark = Color(0xFFAFEDFF)

    val BackgroundDark = Color(0xFF121218)
    val OnBackgroundDark = Color(0xFFE4E1E9)
    val SurfaceDark = Color(0xFF121218)
    val OnSurfaceDark = Color(0xFFE4E1E9)
    val SurfaceVariantDark = Color(0xFF47464F)
    val OnSurfaceVariantDark = Color(0xFFC9C5D0)
    val OutlineDark = Color(0xFF928F99)
    val OutlineVariantDark = Color(0xFF47464F)

    val SurfaceContainerLowestDark = Color(0xFF0C0C11)
    val SurfaceContainerLowDark = Color(0xFF1A1A21)
    val SurfaceContainerDark = Color(0xFF1E1E26)
    val SurfaceContainerHighDark = Color(0xFF292932)
    val SurfaceContainerHighestDark = Color(0xFF34343D)
    val SurfaceDimDark = Color(0xFF121218)
    val SurfaceBrightDark = Color(0xFF38383F)

    val ErrorDark = Color(0xFFF2B8B5)
    val OnErrorDark = Color(0xFF601410)
    val ErrorContainerDark = Color(0xFF8C1D18)
    val OnErrorContainerDark = Color(0xFFF9DEDC)

    // ---- semantic (extended) ----------------------------------------------
    val SuccessLight = Color(0xFF146C2E)
    val OnSuccessLight = Color(0xFFFFFFFF)
    val SuccessContainerLight = Color(0xFFC2F0CE)
    val OnSuccessContainerLight = Color(0xFF00210B)

    val SuccessDark = Color(0xFF6DD58C)
    val OnSuccessDark = Color(0xFF00391A)
    val SuccessContainerDark = Color(0xFF11512C)
    val OnSuccessContainerDark = Color(0xFFC2F0CE)

    val WarningLight = Color(0xFF8A5300)
    val OnWarningLight = Color(0xFFFFFFFF)
    val WarningContainerLight = Color(0xFFFFE0B2)
    val OnWarningContainerLight = Color(0xFF2C1700)

    val WarningDark = Color(0xFFFFB95E)
    val OnWarningDark = Color(0xFF4A2800)
    val WarningContainerDark = Color(0xFF693A00)
    val OnWarningContainerDark = Color(0xFFFFE0B2)

    // ---- storage kinds -----------------------------------------------------
    /** USB / OTG: cool teal, reads as "plugged in hardware". */
    val UsbLight = Color(0xFF0F766E)
    val UsbDark = Color(0xFF5EEAD4)

    /** SD card: amber, distinct from both USB and internal. */
    val SdLight = Color(0xFFB45309)
    val SdDark = Color(0xFFFCD34D)

    // ---- media surfaces ----------------------------------------------------
    val MediaOverlayScrim = Color(0x66000000)
    val VideoPlaceholder = Color(0xFF1B1B24)
    val PlayerScrim = Color(0xB3000000)

    /**
     * Colour of a storage-usage ring by how full the volume is: comfortable, getting full, full.
     * Keeping this out of the primary colour is what makes "92 % used" readable at a glance.
     */
    fun usageColor(fraction: Float, dark: Boolean): Color = when {
        fraction >= 0.92f -> if (dark) ErrorDark else ErrorLight
        fraction >= 0.75f -> if (dark) WarningDark else WarningLight
        else -> if (dark) SuccessDark else SuccessLight
    }
}

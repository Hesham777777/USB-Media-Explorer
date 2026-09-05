package com.usbmediaexplorer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime-permission helpers.
 *
 * The app never asks for MANAGE_EXTERNAL_STORAGE. Removable volumes (USB/OTG, SD) are unlocked
 * with one SAF tree grant each and need no runtime permission at all; only the internal storage
 * does, which is why the home screen shows a permission dialog there and a SAF picker elsewhere.
 */
object Permissions {

    /** Permissions needed to read the *internal* storage by path. */
    fun mediaPermissions(): Array<String> {
        val list = ArrayList<String>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14 "Select photos and videos" (partial access).
            list += Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            list += Manifest.permission.READ_MEDIA_VIDEO
            list += Manifest.permission.READ_MEDIA_IMAGES
            list += Manifest.permission.READ_MEDIA_AUDIO
        } else {
            list += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return list.toTypedArray()
    }

    /**
     * Everything the app asks for at runtime in one call: media access, plus notifications while
     * they are still missing (the transfer notification is useless without them on Android 13+).
     */
    fun runtimePermissions(context: Context): Array<String> {
        val list = mediaPermissions().toMutableList()
        if (needsNotificationPermission(context)) list += Manifest.permission.POST_NOTIFICATIONS
        return list.toTypedArray()
    }

    private fun granted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    /**
     * True when the app can really read media from the internal storage.
     *
     * Deliberately *not* "any of the permissions is granted": on Android 13+ an audio-only grant
     * says nothing about videos and photos, and on Android 14+ the user may pick partial access,
     * which is reported through READ_MEDIA_VISUAL_USER_SELECTED alone.
     */
    fun hasMediaAccess(context: Context): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            granted(context, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) ||
                (
                    granted(context, Manifest.permission.READ_MEDIA_VIDEO) &&
                        granted(context, Manifest.permission.READ_MEDIA_IMAGES)
                    )

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
            granted(context, Manifest.permission.READ_MEDIA_VIDEO) ||
                granted(context, Manifest.permission.READ_MEDIA_IMAGES)

        else -> granted(context, Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    fun missingMediaPermissions(context: Context): List<String> =
        mediaPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /** Android 13+ hides the ongoing-transfer notification until this is granted. */
    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
}

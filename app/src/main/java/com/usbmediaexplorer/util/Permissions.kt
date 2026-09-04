package com.usbmediaexplorer.util

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat

/** Runtime-permission helpers. The app never asks for MANAGE_EXTERNAL_STORAGE. */
object Permissions {

    /** Permissions needed to read the *internal* storage by path. */
    fun mediaPermissions(): Array<String> {
        val list = ArrayList<String>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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

    fun hasMediaAccess(context: Context): Boolean =
        mediaPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

    fun missingMediaPermissions(context: Context): List<String> =
        mediaPermissions().filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }

    /**
     * USB / SD access never needs a runtime permission — Android grants it through the SAF tree
     * picker, once per volume. This is why the home screen shows "Grant access" instead of a
     * permission dialog.
     */
    fun hasScopedStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else false

    fun needsNotificationPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.POST_NOTIFICATIONS,
            ) != PackageManager.PERMISSION_GRANTED
}

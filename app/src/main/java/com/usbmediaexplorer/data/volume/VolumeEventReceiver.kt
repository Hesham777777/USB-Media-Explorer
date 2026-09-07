package com.usbmediaexplorer.data.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Manifest-registered receiver for media mount broadcasts. `ACTION_MEDIA_*` intents are exempt
 * from the implicit-broadcast restrictions, so this runs even when the app is in the background
 * and lets the UI refresh the moment a drive appears or disappears (spec §1).
 */
class VolumeEventReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val label = intent.data?.lastPathSegment
        when (action) {
            Intent.ACTION_MEDIA_MOUNTED -> VolumeEventBus.publish(VolumeEvent.Attached(label))
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_REMOVED,
            Intent.ACTION_MEDIA_BAD_REMOVAL,
            Intent.ACTION_MEDIA_EJECT,
            -> VolumeEventBus.publish(VolumeEvent.Detached(label))
            else -> VolumeEventBus.publish(VolumeEvent.Refresh)
        }
    }
}

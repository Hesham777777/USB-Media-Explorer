package com.usbmediaexplorer.data.ops

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import com.usbmediaexplorer.R
import com.usbmediaexplorer.util.Formatters

/** Notification plumbing for background transfers. */
object OpsNotifications {

    const val CHANNEL_ID = "file_operations"
    const val NOTIFICATION_ID = 4711

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.ops_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.ops_channel_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    fun titleFor(context: Context, job: JobProgress?): String {
        if (job == null) return context.getString(R.string.ops_done_notification)
        val verb = when (job.type) {
            OpType.COPY -> R.string.op_copy
            OpType.MOVE -> R.string.op_move
            OpType.DELETE -> R.string.op_delete
            OpType.ZIP -> R.string.op_zip
            OpType.UNZIP -> R.string.op_unzip
            OpType.BULK_RENAME -> R.string.op_rename
        }
        return context.getString(verb)
    }

    fun textFor(context: Context, job: JobProgress): String {
        val parts = ArrayList<String>(4)
        parts += "${job.percent}%"
        parts += job.currentItemName.takeIf { it.isNotBlank() } ?: ""
        if (job.speedBytesPerSec > 1024) parts += Formatters.speed(job.speedBytesPerSec)
        if (job.etaMs > 0) parts += context.getString(R.string.transfer_eta, Formatters.eta(job.etaMs))
        return parts.filter { it.isNotBlank() }.joinToString(" • ")
    }
}

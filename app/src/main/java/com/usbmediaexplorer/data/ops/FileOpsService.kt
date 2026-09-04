package com.usbmediaexplorer.data.ops

import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.usbmediaexplorer.MainActivity
import com.usbmediaexplorer.R
import com.usbmediaexplorer.UsbMediaExplorerApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Keeps transfers alive while the app is in the background (spec §14: "operations on large files
 * must run in the background with progress"). The service owns no logic — it mirrors
 * [FileOpsManager.jobs] into an ongoing notification and stops itself when the queue drains.
 */
class FileOpsService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var collectJob: Job? = null
    private var manager: FileOpsManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        OpsNotifications.ensureChannel(this)
        manager = (application as? UsbMediaExplorerApp)?.container?.fileOpsManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val ops = manager ?: run {
            stopSelf()
            return START_NOT_STICKY
        }
        promoteToForeground(ops)

        if (collectJob == null) {
            collectJob = serviceScope.launch {
                ops.jobs.collect { jobs ->
                    val active = jobs.firstOrNull { it.isActive }
                    if (active == null) {
                        notify(buildNotification(null, jobs.size))
                        stopSelfCompat()
                    } else {
                        notify(buildNotification(active, jobs.count { it.isActive }))
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun promoteToForeground(ops: FileOpsManager) {
        val notification = buildNotification(ops.activeJob.value, 1)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                OpsNotifications.NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(OpsNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(job: JobProgress?, activeCount: Int): android.app.Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_MAIN
                putExtra(MainActivity.EXTRA_OPEN_TRANSFERS, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val cancelIntent = PendingIntent.getBroadcast(
            this,
            1,
            Intent(this, OpsActionReceiver::class.java).setAction(OpsActionReceiver.ACTION_CANCEL_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val pauseIntent = PendingIntent.getBroadcast(
            this,
            2,
            Intent(this, OpsActionReceiver::class.java).setAction(OpsActionReceiver.ACTION_PAUSE_ALL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val title = if (job == null) {
            getString(R.string.ops_done_notification)
        } else {
            getString(R.string.ops_notification_title, activeCount)
        }
        val text = job?.let { OpsNotifications.textFor(this, it) }
            ?: getString(R.string.ops_no_jobs)

        val builder = NotificationCompat.Builder(this, OpsNotifications.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_usb_drive)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(contentIntent)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        if (job != null) {
            builder.setOngoing(true)
                .setProgress(100, job.percent, job.totalBytes <= 0 && job.totalItems > 0)
                .addAction(0, getString(R.string.action_pause), pauseIntent)
                .addAction(0, getString(R.string.action_cancel), cancelIntent)
        } else {
            builder.setAutoCancel(true).setOngoing(false)
        }
        return builder.build()
    }

    private fun notify(notification: android.app.Notification) {
        runCatching {
            androidx.core.app.NotificationManagerCompat.from(this)
                .notify(OpsNotifications.NOTIFICATION_ID, notification)
        }
    }

    private fun stopSelfCompat() {
        collectJob?.cancel()
        collectJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}

/** Handles Pause / Cancel from the ongoing notification. */
class OpsActionReceiver : BroadcastReceiver() {

    companion object {
        const val ACTION_PAUSE_ALL = "com.usbmediaexplorer.ops.PAUSE_ALL"
        const val ACTION_CANCEL_ALL = "com.usbmediaexplorer.ops.CANCEL_ALL"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val manager = (context.applicationContext as? UsbMediaExplorerApp)?.container?.fileOpsManager
            ?: return
        when (intent.action) {
            ACTION_PAUSE_ALL -> manager.pauseAll()
            ACTION_CANCEL_ALL -> manager.cancelAll()
        }
    }
}

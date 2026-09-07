package com.usbmediaexplorer

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Process
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.usbmediaexplorer.di.AppContainer
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class UsbMediaExplorerApp : Application(), ImageLoaderFactory {

    lateinit var container: AppContainer
        private set

    private var systemCrashHandler: Thread.UncaughtExceptionHandler? = null

    override fun onCreate() {
        super.onCreate()
        // The :crash process only hosts CrashReportActivity. It must NOT re-run the startup
        // chain — if the crash lives in startup itself, re-running it would crash-loop the
        // report screen too.
        if (!isMainProcess()) return
        installCrashHandler()
        container = AppContainer(this)
        container.onAppStart()
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // Coil trims its own memory cache; nothing else here holds bitmaps long term.
    }

    override fun onTerminate() {
        if (::container.isInitialized) container.onAppTerminate()
        super.onTerminate()
    }

    override fun newImageLoader(): ImageLoader = container.imageLoader

    // ------------------------------------------------------------------
    // Crash reporting
    // ------------------------------------------------------------------

    /**
     * Captures every uncaught exception: the full trace is written to filesDir and shown by
     * [CrashReportActivity] in the separate :crash process, then this process is killed
     * cleanly. The user gets a readable, shareable report instead of only the system
     * "keeps stopping" dialog.
     */
    private fun installCrashHandler() {
        systemCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            var reportShown = false
            try {
                val trace = buildCrashTrace(thread, throwable)
                runCatching {
                    File(filesDir, CrashReportActivity.REPORT_FILE).writeText(trace)
                }
                val base = Intent(this, CrashReportActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                reportShown = try {
                    startActivity(base.putExtra(CrashReportActivity.EXTRA_TRACE, trace))
                    true
                } catch (_: Throwable) {
                    // The trace can exceed the intent size limit; the activity then reads the
                    // file copy written above.
                    runCatching { startActivity(base) }.isSuccess
                }
            } catch (_: Throwable) {
                reportShown = false
            }
            if (reportShown) {
                Process.killProcess(Process.myPid())
                exitProcess(10)
            } else {
                systemCrashHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    private fun buildCrashTrace(thread: Thread, throwable: Throwable): String {
        val writer = StringWriter()
        val print = PrintWriter(writer)
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrDefault("unknown")
        print.println("App: $packageName $version")
        print.println(
            "Time: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()),
        )
        print.println(
            "Device: ${Build.MANUFACTURER} ${Build.MODEL}, " +
                "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        )
        print.println("Thread: ${thread.name}")
        print.println()
        throwable.printStackTrace(print)
        print.flush()
        // Keep the report well under the 1 MB Binder limit for intent extras.
        return writer.toString().take(MAX_TRACE_CHARS)
    }

    private fun isMainProcess(): Boolean {
        val pid = Process.myPid()
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val name = manager.runningAppProcesses?.firstOrNull { it.pid == pid }?.processName
        return name == null || name == packageName
    }

    private companion object {
        const val MAX_TRACE_CHARS = 180_000
    }
}

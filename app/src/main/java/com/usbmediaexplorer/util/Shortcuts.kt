package com.usbmediaexplorer.util

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.usbmediaexplorer.MainActivity
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode

/**
 * Home-screen shortcuts for folders and files (spec §8, §9).
 *
 * The Windows habit of dropping a shortcut on the desktop maps onto Android's *pinned* shortcuts:
 * the launcher asks the user to confirm, and tapping the result re-enters the app straight at that
 * folder. Nothing here touches the storage layer — the shortcut only carries the same URI the
 * browser already navigates by, so SAF grants keep working exactly as before.
 */
object Shortcuts {

    /**
     * Requests a pinned shortcut. Returns false when the launcher does not support pinning
     * (Android 7 and below, or a launcher that disabled it) so the caller can say so.
     */
    fun pin(context: Context, node: DocNode): Boolean {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return false
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            data = node.uri
            // A folder must land in the browser, not in the player: MainActivity inspects the
            // document mime type and routes directories to the browse screen.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val label = node.name.ifBlank { context.getString(R.string.breadcrumb_root) }
        val info = ShortcutInfoCompat.Builder(context, "doc-${node.uri.toString().hashCode()}")
            .setShortLabel(label)
            .setLongLabel(label)
            .setIcon(IconCompat.createWithResource(context, R.mipmap.ic_launcher))
            .setIntent(intent)
            .build()
        return runCatching { ShortcutManagerCompat.requestPinShortcut(context, info, null) }
            .getOrDefault(false)
    }

    /** Whether the current launcher can pin shortcuts at all (used to hide the action). */
    fun isSupported(context: Context): Boolean =
        ShortcutManagerCompat.isRequestPinShortcutSupported(context)
}

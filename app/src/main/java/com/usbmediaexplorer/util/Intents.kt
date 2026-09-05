package com.usbmediaexplorer.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocNode
import com.usbmediaexplorer.data.doc.MediaKind

/** Intent helpers for "open with another app" and "share" (spec §14). */
object Intents {

    fun share(context: Context, uris: List<Uri>, mimeType: String?): Boolean {
        if (uris.isEmpty()) return false
        val intent = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).apply {
                putExtra(Intent.EXTRA_STREAM, uris.first())
                type = mimeType ?: "application/octet-stream"
            }
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
                type = mimeType ?: "*/*"
            }
        }
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        return runCatching {
            context.startActivity(Intent.createChooser(intent, null))
            true
        }.getOrElse { false }
    }

    /** Opens a document in whichever app can handle it (players, readers, archive tools…). */
    fun open(context: Context, node: DocNode, uri: Uri): Boolean {
        val mime = node.mimeType ?: MediaKind.mimeTypeFor(node.extension)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrElse { false }
    }

    /**
     * Always shows the chooser, even when a single app could handle the file (spec §8 "open with").
     * Returns false when nothing on the device can open it at all.
     */
    fun openWith(context: Context, node: DocNode, uri: Uri): Boolean {
        val mime = node.mimeType ?: MediaKind.mimeTypeFor(node.extension)
        val view = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, mime)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(view, context.getString(R.string.action_open_with)).apply {
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrElse { open(context, node, uri) }
    }

    fun catchActivityNotFound(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}

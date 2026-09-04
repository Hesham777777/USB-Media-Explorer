package com.usbmediaexplorer.data.volume

import android.content.Intent
import android.net.Uri

enum class VolumeKind { INTERNAL, SD_CARD, USB, EXTERNAL }

enum class VolumeState {
    /** Readable right now. */
    READY,

    /** Mounted, but Android wants one explicit SAF grant from the user. */
    NEEDS_PERMISSION,

    /** Known to the system but not currently mounted. */
    UNMOUNTED,
}

/**
 * A storage entry shown on the home screen: internal storage, SD cards and USB OTG drives.
 *
 * [rootUri] is what the browse screen opens — a `file://` URI when the mount point is readable
 * and a `content://…/tree/…` URI when access goes through the Storage Access Framework.
 */
data class VolumeInfo(
    val id: String,
    val name: String,
    val kind: VolumeKind,
    val rootUri: Uri,
    val state: VolumeState,
    val isRemovable: Boolean,
    val uuid: String? = null,
    val totalBytes: Long? = null,
    val freeBytes: Long? = null,
    val fileSystem: String? = null,
    val deviceLabel: String? = null,
    val description: String? = null,
    val grantIntent: Intent? = null,
    val isUsbAttached: Boolean = false,
) {
    val progress: Float
        get() {
            val total = totalBytes ?: return 0f
            val free = freeBytes ?: return 0f
            if (total <= 0) return 0f
            return ((total - free).toFloat() / total).coerceIn(0f, 1f)
        }

    val isReady: Boolean get() = state == VolumeState.READY
}

/** Emitted whenever the storage topology changes. */
sealed interface VolumeEvent {
    data object Refresh : VolumeEvent
    data class Attached(val label: String?) : VolumeEvent
    data class Detached(val label: String?) : VolumeEvent
    data class PermissionGranted(val treeUri: Uri) : VolumeEvent
}

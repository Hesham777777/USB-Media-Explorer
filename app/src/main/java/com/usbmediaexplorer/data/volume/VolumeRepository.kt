package com.usbmediaexplorer.data.volume

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.provider.DocumentsContract
import com.usbmediaexplorer.R
import com.usbmediaexplorer.data.doc.DocUri
import com.usbmediaexplorer.data.doc.VolumeRef
import com.usbmediaexplorer.data.doc.VolumeResolver
import com.usbmediaexplorer.util.Permissions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Enumerates every storage entry the user can browse and keeps the list in sync with plug events.
 *
 * Order of precedence for a removable volume:
 *  1. an already persisted SAF tree grant (instant, no dialog — spec §1 "ask once"),
 *  2. a readable mount point (`/storage/XXXX-YYYY`) when the OEM exposes it,
 *  3. otherwise a "needs permission" card that opens the SAF tree picker.
 */
class VolumeRepository(
    private val context: Context,
    private val monitor: VolumeMonitor,
    private val scope: CoroutineScope,
) {

    companion object {
        const val ID_INTERNAL = "internal"
        const val ID_EXTERNAL_PREFIX = "ext:"
        const val ID_USB_PREFIX = "usb:"
    }

    private val storageManager =
        context.getSystemService(Context.STORAGE_SERVICE) as StorageManager

    private val _volumes = MutableStateFlow<List<VolumeInfo>>(emptyList())
    val volumes: StateFlow<List<VolumeInfo>> = _volumes.asStateFlow()

    /**
     * Tree grants that Android would not make permanent. The URI returned by the picker is still
     * readable for the lifetime of the task, so the volume must open now and simply ask again
     * after a restart instead of staying locked forever.
     */
    private val sessionTrees = MutableStateFlow<List<Uri>>(emptyList())

    /** Set while a plug/unplug storm is being processed. */
    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    val resolver: VolumeResolver = VolumeResolver { uri -> resolveRef(uri) }

    init {
        scope.launch {
            VolumeEventBus.events.collect { event ->
                refresh()
                if (event is VolumeEvent.PermissionGranted) refresh()
            }
        }
        scope.launch { refresh() }
    }

    // ------------------------------------------------------------------

    fun grantedTrees(): List<Uri> = runCatching {
        val persisted = context.contentResolver.persistedUriPermissions
            .filter { it.isReadPermission && DocUri.isTree(it.uri) }
            .map { it.uri }
        (persisted + sessionTrees.value).distinctBy { it.toString() }
    }.getOrDefault(emptyList())

    fun volumeById(id: String): VolumeInfo? = _volumes.value.firstOrNull { it.id == id }

    fun volumeFor(uri: Uri): VolumeInfo? {
        val volumes = _volumes.value
        val uuid = DocUri.volumeUuid(uri)
        if (uuid != null) {
            volumes.firstOrNull { it.uuid != null && it.uuid.equals(uuid, ignoreCase = true) }
                ?.let { return it }
        }
        if (uri.scheme == "file") return volumes.firstOrNull { it.id == ID_INTERNAL }
        return volumes.firstOrNull { DocUri.isPrimaryStorage(it.rootUri) && it.id == ID_INTERNAL }
            ?: volumes.firstOrNull { it.rootUri.toString() == uri.toString() }
    }

    private fun resolveRef(uri: Uri): VolumeRef = volumeFor(uri)?.let { VolumeRef(it.id, it.name) }
        ?: VolumeRef(ID_INTERNAL, context.getString(R.string.volume_internal))

    /**
     * Persists a tree grant returned by ACTION_OPEN_DOCUMENT_TREE.
     *
     * Returns false when Android refused to make it permanent (a read-only volume, or a picker
     * result that carried no persistable flag). The grant is kept for this session either way, and
     * the caller tells the user what happened.
     */
    fun persistTree(uri: Uri): Boolean {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        val ok = runCatching {
            context.contentResolver.takePersistableUriPermission(uri, flags)
            true
        }.getOrElse {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)
        }
        sessionTrees.value = (sessionTrees.value + uri).distinctBy { it.toString() }
        if (ok) VolumeEventBus.publish(VolumeEvent.PermissionGranted(uri))
        scope.launch { refresh() }
        return ok
    }

    fun releaseTree(volume: VolumeInfo) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                volume.rootUri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        sessionTrees.value = sessionTrees.value.filterNot { it == volume.rootUri }
        scope.launch { refresh() }
    }

    suspend fun refresh() {
        if (_refreshing.value) return
        _refreshing.value = true
        try {
            _volumes.value = buildVolumes()
        } finally {
            _refreshing.value = false
        }
    }

    // ------------------------------------------------------------------

    private suspend fun buildVolumes(): List<VolumeInfo> = withContext(Dispatchers.IO) {
        val out = ArrayList<VolumeInfo>()
        val trees = grantedTrees()
        val mounts = FileSystemProbe.readMounts()
        val usbDevices = monitor.massStorageDevices()
        val usbLabel = usbDevices.firstOrNull()?.let { monitor.describeUsbDevice(it) }

        // 1. Internal storage ------------------------------------------------
        val internalDir = Environment.getExternalStorageDirectory()
        val internalStats = runCatching { StatFs(internalDir.path) }.getOrNull()
        val consumedTrees = HashSet<String>()

        // Two ways in: the runtime media permission (raw paths, media files), or a SAF tree grant
        // on primary storage, which needs no runtime permission at all and also exposes documents.
        val mediaAccess = Permissions.hasMediaAccess(context)
        val primaryTree = trees.firstOrNull { DocUri.isPrimaryStorage(it) }
        primaryTree?.let { consumedTrees += it.toString() }

        // Could raw /storage/emulated/0 paths ever work here?
        //  - up to Android 9: the classic model, the media permission is enough,
        //  - Android 10: only in legacy mode — scoped storage hides everything otherwise, which is
        //    exactly the trap where the user grants READ_EXTERNAL_STORAGE and "nothing changes",
        //  - Android 11+: media files are reachable by path again with the media permission.
        val rawPathsPossible = when {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.Q -> true
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> Environment.isExternalStorageLegacy()
            else -> true
        }

        // Ground truth beats prediction: actually try to list the shared storage. When the
        // permission is already granted we believe only this probe, so a device that refuses raw
        // paths (Android 10 scoped storage, some OEM builds) never shows a "ready" volume that
        // then lists nothing.
        val listsNow = runCatching { internalDir.listFiles() }.getOrNull()?.isNotEmpty() == true
        val rawPathsUsable = mediaAccess && listsNow
        val runtimeGrantWillHelp = !mediaAccess && rawPathsPossible

        // NOTE: File.canRead() is not a permission check — on Android 10+ it answers true for
        // /storage/emulated/0 even with every permission denied, which used to show an "accessible"
        // internal storage that then listed nothing.
        val internalState = if (rawPathsUsable || primaryTree != null) {
            VolumeState.READY
        } else {
            VolumeState.NEEDS_PERMISSION
        }
        out += VolumeInfo(
            id = ID_INTERNAL,
            name = context.getString(R.string.volume_internal),
            kind = VolumeKind.INTERNAL,
            rootUri = when {
                rawPathsUsable -> Uri.fromFile(internalDir)
                primaryTree != null -> primaryTree
                else -> Uri.EMPTY
            },
            state = internalState,
            // A runtime permission can only unlock the internal storage where raw paths are
            // allowed at all; where they are not (scoped-storage Android 10, or a granted
            // permission that still lists nothing) the working route is one SAF grant instead.
            grantKind = if (runtimeGrantWillHelp) GrantKind.RUNTIME_MEDIA else GrantKind.SAF_TREE,
            grantIntent = if (!runtimeGrantWillHelp && internalState == VolumeState.NEEDS_PERMISSION) {
                Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    )
                    // Opens the picker directly on the internal storage.
                    putExtra("android.provider.extra.INITIAL_URI", Uri.fromFile(internalDir))
                }
            } else {
                null
            },
            isRemovable = false,
            uuid = "primary",
            totalBytes = internalStats?.totalBytes,
            freeBytes = internalStats?.availableBytes,
            fileSystem = mounts.firstOrNull { it.second == internalDir.path }?.first ?: "fuse",
            description = if (internalState == VolumeState.NEEDS_PERMISSION) {
                context.getString(R.string.volume_needs_permission)
            } else {
                null
            },
        )

        // 2. Removable volumes reported by the platform ----------------------
        val matchedUuids = HashSet<String>()
        val removable = runCatching { storageManager.storageVolumes }.getOrDefault(emptyList())
            .filter { !it.isPrimary }

        removable.forEach { volume ->
            val uuid = uuidOf(volume)
            val description = runCatching { volume.getDescription(context) }.getOrNull()
            val mounted = runCatching { volume.state == Environment.MEDIA_MOUNTED }
                .getOrDefault(true)
            val tree = uuid?.let { id ->
                trees.firstOrNull { DocUri.volumeUuid(it)?.equals(id, ignoreCase = true) == true }
            }
            val dirFile = directoryOf(volume, uuid)
            val pathReadable = dirFile?.canRead() == true
            if (uuid != null) matchedUuids += uuid

            val state = when {
                tree != null -> VolumeState.READY
                pathReadable -> VolumeState.READY
                !mounted -> VolumeState.UNMOUNTED
                else -> VolumeState.NEEDS_PERMISSION
            }
            val rootUri = tree ?: dirFile?.let { Uri.fromFile(it) } ?: Uri.EMPTY
            val stats = runCatching { dirFile?.let { StatFs(it.path) } }.getOrNull()
            val kind = when {
                uuid != null && usbDevices.isNotEmpty() -> VolumeKind.USB
                description?.contains("SD", ignoreCase = true) == true -> VolumeKind.SD_CARD
                runCatching { volume.isRemovable }.getOrDefault(false) -> VolumeKind.SD_CARD
                else -> VolumeKind.EXTERNAL
            }
            val name = when (kind) {
                VolumeKind.USB -> context.getString(R.string.volume_usb)
                VolumeKind.SD_CARD -> context.getString(R.string.volume_sd_card)
                else -> description ?: context.getString(R.string.volume_unknown)
            }
            out += VolumeInfo(
                id = if (tree != null) ID_EXTERNAL_PREFIX + tree.toString().hashCode()
                else ID_EXTERNAL_PREFIX + (uuid ?: description ?: name),
                name = name,
                kind = kind,
                rootUri = rootUri,
                state = state,
                isRemovable = true,
                uuid = uuid,
                totalBytes = stats?.totalBytes,
                freeBytes = stats?.availableBytes,
                fileSystem = uuid?.let { id -> mounts.firstOrNull { it.second.endsWith(id) }?.first },
                deviceLabel = if (kind == VolumeKind.USB) usbLabel else null,
                description = description,
                grantIntent = if (state == VolumeState.NEEDS_PERMISSION) grantIntentFor(volume, dirFile) else null,
                isUsbAttached = kind == VolumeKind.USB && usbDevices.isNotEmpty(),
            )
        }

        // 3. Persisted tree grants that do not match a mounted volume --------
        trees.forEach { tree ->
            if (tree.toString() in consumedTrees) return@forEach
            val uuid = DocUri.volumeUuid(tree)
            if (uuid != null && matchedUuids.any { it.equals(uuid, ignoreCase = true) }) return@forEach
            val docId = runCatching { DocumentsContract.getTreeDocumentId(tree) }.getOrNull()
            val name = docId?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
                ?: uuid
                ?: context.getString(R.string.volume_unknown)
            out += VolumeInfo(
                id = ID_EXTERNAL_PREFIX + tree.toString().hashCode(),
                name = name,
                kind = VolumeKind.EXTERNAL,
                rootUri = tree,
                state = VolumeState.READY,
                isRemovable = true,
                uuid = uuid,
                totalBytes = null,
                freeBytes = null,
                description = docId,
            )
        }

        // 4. USB stick attached but the platform has not exposed a volume yet --
        if (usbDevices.isNotEmpty() && out.none { it.kind == VolumeKind.USB || it.isUsbAttached }) {
            out += VolumeInfo(
                id = ID_USB_PREFIX + usbDevices.first().deviceId,
                name = context.getString(R.string.volume_usb),
                kind = VolumeKind.USB,
                rootUri = Uri.EMPTY,
                state = VolumeState.NEEDS_PERMISSION,
                isRemovable = true,
                deviceLabel = usbLabel,
                description = context.getString(R.string.usb_attached_body),
                grantIntent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    )
                },
                isUsbAttached = true,
            )
        }

        out
    }

    /** SAF picker intent that pre-selects the volume on Android 11+. */
    private fun grantIntentFor(volume: StorageVolume, dirFile: File?): Intent {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                return volume.createOpenDocumentTreeIntent().apply {
                    // Some platform builds hand this intent back without the persistable flag;
                    // takePersistableUriPermission() then throws "No persistable permission
                    // grants" and the user's grant is silently lost.
                    addFlags(
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                            Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                    )
                }
            }
        }
        return Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
            addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
            )
            val dir = dirFile ?: uuidOf(volume)?.let { File("/storage/$it") }
            if (dir != null) {
                // Hidden extra honoured by the system picker: opens directly on this volume.
                putExtra("android.provider.extra.INITIAL_URI", Uri.fromFile(dir))
            }
        }
    }

    private fun uuidOf(volume: StorageVolume): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { volume.mediaStoreVolumeName }
                .getOrNull()
                ?.takeIf { it.isNotBlank() && it != "primary" }
                ?.let { return it.uppercase() }
        }
        runCatching {
            val method = volume.javaClass.getMethod("getUuid")
            val value = method.invoke(volume) as? String
            if (!value.isNullOrBlank()) return value.uppercase()
        }
        return null
    }

    private fun directoryOf(volume: StorageVolume, uuid: String?): File? {
        // StorageVolume#getDirectory()/getUuid() are hidden APIs; reflection is best-effort and
        // blocked on some builds, hence the /storage/<UUID> fallback.
        runCatching {
            val method = volume.javaClass.getMethod("getDirectory")
            (method.invoke(volume) as? File)?.let { return it }
        }
        uuid?.let {
            val candidate = File("/storage/$it")
            if (candidate.exists()) return candidate
        }
        return null
    }
}

/**
 * Reads `/proc/mounts` (world readable) to label a volume FAT32/exFAT/NTFS. Purely cosmetic,
 * every failure falls back to null.
 */
object FileSystemProbe {

    fun readMounts(): List<Pair<String, String>> = runCatching {
        File("/proc/mounts").readLines().mapNotNull { line ->
            val parts = line.split(" ").filter { it.isNotEmpty() }
            if (parts.size < 3) return@mapNotNull null
            val mountPoint = parts[1].replace("\\040", " ")
            val fsType = parts[2]
            if (fsType in setOf("vfat", "exfat", "ntfs", "ext4", "f2fs", "fuse", "sdcardfs", "esdfs")) {
                fsType to mountPoint
            } else {
                null
            }
        }
    }.getOrDefault(emptyList())

    fun labelFor(fileSystem: String?): String? = when (fileSystem) {
        "vfat" -> "FAT32"
        "exfat" -> "exFAT"
        "ntfs" -> "NTFS"
        "ext4" -> "ext4"
        "f2fs" -> "F2FS"
        else -> fileSystem?.uppercase()
    }
}

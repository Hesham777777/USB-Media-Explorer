package com.usbmediaexplorer.data.volume

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.storage.StorageManager
import android.os.storage.StorageVolume

/**
 * Watches for USB/SD plug events while the app is alive:
 *  - [UsbManager.ACTION_USB_DEVICE_ATTACHED] / DETACHED (OTG flash drives),
 *  - `ACTION_MEDIA_MOUNTED` / `REMOVED` (kernel mount notifications),
 *  - [StorageManager.registerStorageVolumeCallback] on Android 11+.
 *
 * Everything funnels into [VolumeEventBus], which the repository and the home screen observe.
 */
class VolumeMonitor(private val context: Context) {

    private val usbManager: UsbManager? =
        context.getSystemService(Context.USB_SERVICE) as? UsbManager

    private val storageManager: StorageManager? =
        context.getSystemService(Context.STORAGE_SERVICE) as? StorageManager

    private var receiver: BroadcastReceiver? = null
    private var volumeCallback: Any? = null

    /** True when a plugged USB device exposes a mass-storage interface (class 8). */
    fun massStorageDevices(): List<UsbDevice> {
        val manager = usbManager ?: return emptyList()
        return runCatching {
            manager.deviceList.values.filter { device ->
                (0 until device.interfaceCount).any { index ->
                    device.getInterface(index).interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE
                }
            }
        }.getOrDefault(emptyList())
    }

    fun describeUsbDevice(device: UsbDevice): String? {
        val product = runCatching { device.productName }.getOrNull()?.takeIf { it.isNotBlank() }
        val manufacturer = runCatching { device.manufacturerName }.getOrNull()?.takeIf { it.isNotBlank() }
        return listOfNotNull(manufacturer, product).joinToString(" ").ifEmpty { null }
    }

    fun removableStorageVolumes(): List<StorageVolume> = runCatching {
        storageManager?.storageVolumes?.filter { it.isRemovable } ?: emptyList()
    }.getOrDefault(emptyList())

    fun start() {
        if (receiver != null) return
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_SCANNER_FINISHED)
            addDataScheme("file")
        }
        val listener = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                        val label = device?.let { describeUsbDevice(it) }
                        if (device == null || massStorageDevices().any { it.deviceId == device.deviceId }) {
                            VolumeEventBus.publish(VolumeEvent.Attached(label))
                        }
                    }

                    UsbManager.ACTION_USB_DEVICE_DETACHED ->
                        VolumeEventBus.publish(VolumeEvent.Detached(null))

                    Intent.ACTION_MEDIA_MOUNTED ->
                        VolumeEventBus.publish(VolumeEvent.Attached(intent.data?.lastPathSegment))

                    Intent.ACTION_MEDIA_UNMOUNTED,
                    Intent.ACTION_MEDIA_REMOVED,
                    Intent.ACTION_MEDIA_BAD_REMOVAL,
                    Intent.ACTION_MEDIA_EJECT,
                    -> VolumeEventBus.publish(VolumeEvent.Detached(intent.data?.lastPathSegment))

                    else -> VolumeEventBus.publish(VolumeEvent.Refresh)
                }
            }
        }
        runCatching {
            context.registerReceiver(listener, filter)
            receiver = listener
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val callback = object : StorageManager.StorageVolumeCallback() {
                    override fun onStateChanged(volume: StorageVolume) {
                        val label = runCatching { volume.getDescription(context) }.getOrNull()
                        val mounted = runCatching {
                            volume.state == android.os.Environment.MEDIA_MOUNTED
                        }.getOrDefault(false)
                        VolumeEventBus.publish(
                            if (mounted) VolumeEvent.Attached(label) else VolumeEvent.Detached(label),
                        )
                    }
                }
                storageManager?.registerStorageVolumeCallback(context.mainExecutor, callback)
                volumeCallback = callback
            }
        }
    }

    fun stop() {
        receiver?.let { runCatching { context.unregisterReceiver(it) } }
        receiver = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && volumeCallback != null) {
            runCatching { storageManager?.unregisterStorageVolumeCallback(volumeCallback as StorageManager.StorageVolumeCallback) }
            volumeCallback = null
        }
    }
}

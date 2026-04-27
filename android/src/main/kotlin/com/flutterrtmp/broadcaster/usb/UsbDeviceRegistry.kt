package com.flutterrtmp.broadcaster.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.serenegiant.usb.USBMonitor
import java.util.concurrent.ConcurrentHashMap

class UsbDeviceRegistry(
    private val context: Context,
    private val onDeviceDetached: (Int) -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pendingPermissions = ConcurrentHashMap<Int, (Boolean) -> Unit>()

    private val usbMonitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {}

        override fun onDetach(device: UsbDevice) {
            pendingPermissions.remove(device.deviceId)?.let { cb ->
                mainHandler.post { cb(false) }
            }
            mainHandler.post { onDeviceDetached(device.deviceId) }
        }

        override fun onConnect(
            device: UsbDevice,
            ctrlBlock: USBMonitor.UsbControlBlock,
            createNew: Boolean
        ) {
            pendingPermissions.remove(device.deviceId)?.let { cb ->
                mainHandler.post { cb(true) }
            }
        }

        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {}

        override fun onCancel(device: UsbDevice) {
            pendingPermissions.remove(device.deviceId)?.let { cb ->
                mainHandler.post { cb(false) }
            }
        }
    })

    fun register() {
        try { usbMonitor.register() } catch (_: Exception) {}
    }

    fun unregister() {
        try { usbMonitor.unregister() } catch (_: Exception) {}
    }

    fun destroy() {
        pendingPermissions.clear()
        try { usbMonitor.destroy() } catch (_: Exception) {}
    }

    fun listUvcDevices(): List<Map<String, Any>> =
        usbManager.deviceList.values
            .filter { isUvcDevice(it) }
            .map { device ->
                mapOf(
                    "deviceId" to device.deviceId,
                    "vendorId" to device.vendorId,
                    "productId" to device.productId,
                    "productName" to (device.productName ?: "Unknown Camera"),
                    "manufacturerName" to (device.manufacturerName ?: "Unknown"),
                    "hasPermission" to usbManager.hasPermission(device)
                )
            }

    fun listUacDevices(): List<Map<String, Any>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return emptyList()
        return audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .filter { it.type == AudioDeviceInfo.TYPE_USB_DEVICE || it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
            .map { device ->
                mapOf(
                    "deviceId" to device.id,
                    "productName" to device.productName.toString(),
                    "type" to device.type
                )
            }
    }

    fun requestPermission(deviceId: Int, callback: (Boolean) -> Unit) {
        val device = findDevice(deviceId)
        if (device == null) {
            callback(false)
            return
        }
        if (usbManager.hasPermission(device)) {
            callback(true)
            return
        }
        pendingPermissions[deviceId] = callback
        usbMonitor.requestPermission(device)
    }

    fun findDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.find { it.deviceId == deviceId }

    fun openDevice(deviceId: Int): USBMonitor.UsbControlBlock? {
        val device = findDevice(deviceId) ?: return null
        return try { usbMonitor.openDevice(device) } catch (_: Exception) { null }
    }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 14 || device.deviceClass == 239) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }
}

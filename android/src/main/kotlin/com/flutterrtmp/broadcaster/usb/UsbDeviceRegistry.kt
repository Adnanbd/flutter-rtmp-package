package com.flutterrtmp.broadcaster.usb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.flutterrtmp.broadcaster.diag.DiagLogger
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
    private val cachedControlBlocks = ConcurrentHashMap<Int, USBMonitor.UsbControlBlock>()

    private val permissionAction = "${context.packageName}.flutter_rtmp_broadcaster.USB_PERMISSION"
    private var receiverRegistered = false

    // Only used for openDevice(), which needs nothing but USB permission.
    // USBMonitor.register() is never called: libuvc 3.2.0 builds its PendingIntent with
    // flags 0 and registers an unflagged receiver, which throws on targetSdk 31+ / 34+
    // and leaves requestPermission() cancelling without a system dialog (ADR 0021).
    private val usbMonitor = USBMonitor(context, object : USBMonitor.OnDeviceConnectListener {
        override fun onAttach(device: UsbDevice) {}
        override fun onDetach(device: UsbDevice) {}
        override fun onConnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock, createNew: Boolean) {}
        override fun onDisconnect(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {}
        override fun onCancel(device: UsbDevice) {}
    })

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            val device = intent.usbDevice() ?: return
            when (intent.action) {
                permissionAction -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) ||
                        usbManager.hasPermission(device)
                    DiagLogger.log(TAG, "permission result: deviceId=${device.deviceId} granted=$granted")
                    resolvePending(device.deviceId, granted)
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    DiagLogger.log(TAG, "detached: deviceId=${device.deviceId}")
                    invalidateDevice(device.deviceId)
                    resolvePending(device.deviceId, false)
                    mainHandler.post { onDeviceDetached(device.deviceId) }
                }
            }
        }
    }

    fun register() {
        if (receiverRegistered) return
        val filter = IntentFilter(permissionAction).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(usbReceiver, filter)
            }
            receiverRegistered = true
        } catch (e: Exception) {
            DiagLogger.logError("USB_REGISTER_FAILED", "registerReceiver", e)
        }
    }

    fun unregister() {
        if (!receiverRegistered) return
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (e: Exception) {
            DiagLogger.log(TAG, "unregisterReceiver failed", e)
        }
        receiverRegistered = false
    }

    fun destroy() {
        unregister()
        pendingPermissions.values.forEach { cb -> mainHandler.post { cb(false) } }
        pendingPermissions.clear()
        cachedControlBlocks.values.forEach { closeControlBlock(it) }
        cachedControlBlocks.clear()
        try { usbMonitor.destroy() } catch (e: Exception) { DiagLogger.log(TAG, "USBMonitor.destroy failed", e) }
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
        if (!receiverRegistered) register()
        pendingPermissions.put(deviceId, callback)?.let { previous -> mainHandler.post { previous(false) } }
        try {
            // Mutable so the system can fill in EXTRA_DEVICE / EXTRA_PERMISSION_GRANTED;
            // explicit package, as Android 14 forbids mutable implicit PendingIntents.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val intent = Intent(permissionAction).setPackage(context.packageName)
            val pendingIntent = PendingIntent.getBroadcast(context, deviceId, intent, flags)
            DiagLogger.log(TAG, "requesting permission: deviceId=$deviceId")
            usbManager.requestPermission(device, pendingIntent)
        } catch (e: Exception) {
            DiagLogger.logError("USB_PERMISSION_REQUEST_FAILED", "deviceId=$deviceId", e)
            resolvePending(deviceId, false)
        }
    }

    private fun resolvePending(deviceId: Int, granted: Boolean) {
        pendingPermissions.remove(deviceId)?.let { cb -> mainHandler.post { cb(granted) } }
    }

    fun findDevice(deviceId: Int): UsbDevice? =
        usbManager.deviceList.values.find { it.deviceId == deviceId }

    fun hasDevice(deviceId: Int): Boolean = findDevice(deviceId) != null

    fun hasPermission(deviceId: Int): Boolean =
        findDevice(deviceId)?.let { usbManager.hasPermission(it) } ?: false

    fun closeControlBlock(ctrlBlock: USBMonitor.UsbControlBlock) {
        try { ctrlBlock.close() } catch (e: Exception) { DiagLogger.log(TAG, "control block close failed", e) }
    }

    fun invalidateDevice(deviceId: Int) {
        cachedControlBlocks.remove(deviceId)?.let { closeControlBlock(it) }
    }

    fun openDevice(deviceId: Int): USBMonitor.UsbControlBlock? {
        val cached: USBMonitor.UsbControlBlock? = cachedControlBlocks[deviceId]
        if (cached != null) return cached
        val device = findDevice(deviceId) ?: return null
        return try {
            usbMonitor.openDevice(device)
        } catch (e: Exception) {
            DiagLogger.logError("OPEN_DEVICE_FAILED", "deviceId=$deviceId", e)
            null
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.usbDevice(): UsbDevice? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

    private fun isUvcDevice(device: UsbDevice): Boolean {
        if (device.deviceClass == 14 || device.deviceClass == 239) return true
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == 14) return true
        }
        return false
    }

    private companion object {
        const val TAG = "UsbDeviceRegistry"
    }
}

package com.flutterrtmp.broadcaster.usb

/** Plain copy of the `AudioDeviceInfo` fields routing needs, so selection is JVM-testable. */
data class AudioInputInfo(val id: Int, val type: Int, val productName: String) {
    val isUsb: Boolean get() = UsbAudioRouting.isUsbType(type)
    override fun toString(): String = "$productName(id=$id type=${UsbAudioRouting.typeName(type)})"
}

/**
 * Picks the USB input `UsbAudioSource` records from (docs/specs/usb-sources.md, ADR 0022).
 *
 * Composite UVC+UAC devices (HDMI switchers, capture cards, webcams) can re-enumerate when the
 * camera opens, so the `AudioDeviceInfo` id the app picked may be gone by the time audio starts.
 * Match order: same id → same product name → first USB input.
 */
object UsbAudioRouting {
    // AudioDeviceInfo constants, copied so this stays free of android.* for JVM tests.
    const val TYPE_BUILTIN_MIC = 15
    const val TYPE_USB_DEVICE = 11
    const val TYPE_USB_HEADSET = 22

    enum class Match { ID, NAME, FIRST_USB }

    data class Selection(val input: AudioInputInfo, val match: Match)

    fun isUsbType(type: Int): Boolean = type == TYPE_USB_DEVICE || type == TYPE_USB_HEADSET

    fun select(inputs: List<AudioInputInfo>, wantedId: Int?, wantedName: String?): Selection? {
        val usb = inputs.filter { it.isUsb }
        if (usb.isEmpty()) return null
        wantedId?.let { id -> usb.firstOrNull { it.id == id }?.let { return Selection(it, Match.ID) } }
        wantedName?.let { name -> usb.firstOrNull { it.productName == name }?.let { return Selection(it, Match.NAME) } }
        return Selection(usb.first(), Match.FIRST_USB)
    }

    fun typeName(type: Int): String = when (type) {
        TYPE_USB_DEVICE -> "USB_DEVICE"
        TYPE_USB_HEADSET -> "USB_HEADSET"
        TYPE_BUILTIN_MIC -> "BUILTIN_MIC"
        else -> "TYPE_$type"
    }
}

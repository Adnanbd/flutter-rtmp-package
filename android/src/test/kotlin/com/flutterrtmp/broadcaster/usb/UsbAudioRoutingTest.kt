package com.flutterrtmp.broadcaster.usb

import com.flutterrtmp.broadcaster.usb.UsbAudioRouting.Match
import com.flutterrtmp.broadcaster.usb.UsbAudioRouting.TYPE_BUILTIN_MIC
import com.flutterrtmp.broadcaster.usb.UsbAudioRouting.TYPE_USB_DEVICE
import com.flutterrtmp.broadcaster.usb.UsbAudioRouting.TYPE_USB_HEADSET
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UsbAudioRoutingTest {
    private val builtin = AudioInputInfo(1, TYPE_BUILTIN_MIC, "Pixel")
    private val switcher = AudioInputInfo(40, TYPE_USB_DEVICE, "MT-VIKI")
    private val headset = AudioInputInfo(41, TYPE_USB_HEADSET, "Headset")

    @Test
    fun `matches by id`() {
        val sel = UsbAudioRouting.select(listOf(builtin, switcher, headset), 41, "Headset")
        assertEquals(headset, sel?.input)
        assertEquals(Match.ID, sel?.match)
    }

    @Test
    fun `stale id falls back to same product name`() {
        val reEnumerated = switcher.copy(id = 57)
        val sel = UsbAudioRouting.select(listOf(builtin, headset, reEnumerated), 40, "MT-VIKI")
        assertEquals(reEnumerated, sel?.input)
        assertEquals(Match.NAME, sel?.match)
    }

    @Test
    fun `no id picks first usb input`() {
        val sel = UsbAudioRouting.select(listOf(builtin, switcher, headset), null, null)
        assertEquals(switcher, sel?.input)
        assertEquals(Match.FIRST_USB, sel?.match)
    }

    @Test
    fun `unknown id and name still prefers usb over builtin`() {
        val sel = UsbAudioRouting.select(listOf(builtin, switcher), 99, "Gone")
        assertEquals(switcher, sel?.input)
        assertEquals(Match.FIRST_USB, sel?.match)
    }

    @Test
    fun `builtin id is never selected`() {
        val sel = UsbAudioRouting.select(listOf(builtin, switcher), builtin.id, builtin.productName)
        assertEquals(switcher, sel?.input)
    }

    @Test
    fun `no usb input returns null`() {
        assertNull(UsbAudioRouting.select(listOf(builtin), 40, "MT-VIKI"))
        assertNull(UsbAudioRouting.select(emptyList(), null, null))
    }
}

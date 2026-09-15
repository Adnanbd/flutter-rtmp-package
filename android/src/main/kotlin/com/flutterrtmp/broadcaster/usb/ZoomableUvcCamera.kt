package com.flutterrtmp.broadcaster.usb

import com.serenegiant.usb.UVCCamera

/** [UVCCamera] with its protected zoom limits exposed. Limits are filled by libuvc on every `getZoom()` call. */
class ZoomableUvcCamera : UVCCamera() {
    val zoomMin: Int get() = mZoomMin
    val zoomMax: Int get() = mZoomMax
}

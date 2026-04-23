package com.flutterrtmp.broadcaster.rtmp

import android.os.Handler
import android.os.Looper
import com.pedro.common.ConnectChecker
import io.flutter.plugin.common.EventChannel

class RtmpConnectChecker(
    private val onConnectedCallback: () -> Unit,
    private val onDisconnectedCallback: () -> Unit
) : ConnectChecker {

    var sink: EventChannel.EventSink? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendEvent(event: Map<String, Any?>) {
        val s = sink ?: return
        mainHandler.post { s.success(event) }
    }

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        sendEvent(mapOf("type" to "connected"))
        onConnectedCallback()
    }

    override fun onConnectionFailed(reason: String) {
        sendEvent(mapOf("type" to "disconnected", "reason" to reason))
        onDisconnectedCallback()
    }

    override fun onDisconnect() {
        sendEvent(mapOf("type" to "disconnected", "reason" to "Server closed connection"))
        onDisconnectedCallback()
    }

    override fun onNewBitrate(bitrate: Long) {
        sendEvent(mapOf("type" to "bitrate", "kbps" to bitrate / 1000))
    }

    override fun onAuthError() {
        sendEvent(mapOf("type" to "error", "code" to "AUTH_ERROR", "message" to "Authentication failed"))
    }

    override fun onAuthSuccess() {}
}

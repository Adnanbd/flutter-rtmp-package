package com.flutterrtmp.broadcaster.rtmp

import android.os.Handler
import android.os.Looper
import com.pedro.common.ConnectChecker
import io.flutter.plugin.common.EventChannel

class RtmpConnectChecker(
    private val onConnectedCallback: () -> Unit,
    private val onDisconnectedCallback: (reason: String) -> Unit,
    private val onNewBitrateCallback: (bitrate: Long) -> Unit
) : ConnectChecker {

    private var _sink: EventChannel.EventSink? = null
    var sink: EventChannel.EventSink?
        get() = _sink
        set(value) {
            _sink = value
            if (value != null) {
                val drained = pending.toList()
                pending.clear()
                drained.forEach { mainHandler.post { value.success(it) } }
            }
        }
    private val pending = mutableListOf<Map<String, Any?>>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun sendEvent(event: Map<String, Any?>) {
        val s = _sink
        if (s == null) {
            // Buffer until Dart attaches a listener (e.g. warnings emitted during configure()).
            // Cap at 32 to avoid unbounded growth if listener never attaches.
            if (pending.size < 32) pending.add(event)
            return
        }
        mainHandler.post { s.success(event) }
    }

    override fun onConnectionStarted(url: String) {}

    override fun onConnectionSuccess() {
        sendEvent(mapOf("type" to "connected"))
        onConnectedCallback()
    }

    override fun onConnectionFailed(reason: String) {
        sendEvent(mapOf("type" to "disconnected", "reason" to reason))
        onDisconnectedCallback(reason)
    }

    override fun onDisconnect() {
        val reason = "Server closed connection"
        sendEvent(mapOf("type" to "disconnected", "reason" to reason))
        onDisconnectedCallback(reason)
    }

    override fun onNewBitrate(bitrate: Long) {
        sendEvent(mapOf("type" to "bitrate", "kbps" to bitrate / 1000))
        onNewBitrateCallback(bitrate)
    }

    override fun onAuthError() {
        sendEvent(mapOf("type" to "error", "code" to "AUTH_ERROR", "message" to "Authentication failed"))
    }

    override fun onAuthSuccess() {}
}

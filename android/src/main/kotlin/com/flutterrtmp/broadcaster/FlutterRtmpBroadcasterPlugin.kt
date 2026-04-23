package com.flutterrtmp.broadcaster

import android.app.Activity
import android.content.Context
import com.flutterrtmp.broadcaster.camera.CameraPreviewFactory
import com.flutterrtmp.broadcaster.camera.CameraStreamManager
import com.flutterrtmp.broadcaster.overlay.SponsorConfig
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

class FlutterRtmpBroadcasterPlugin :
    FlutterPlugin,
    MethodCallHandler,
    ActivityAware {

    private lateinit var methodChannel: MethodChannel
    private lateinit var eventChannel: EventChannel
    private var context: Context? = null
    private var activity: Activity? = null
    private var cameraStreamManager: CameraStreamManager? = null
    private var eventSink: EventChannel.EventSink? = null

    companion object {
        private const val METHOD_CHANNEL = "flutter_rtmp_broadcaster/control"
        private const val EVENT_CHANNEL = "flutter_rtmp_broadcaster/status"
        private const val VIEW_TYPE = "flutter_rtmp_broadcaster/camera_preview"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext

        methodChannel = MethodChannel(binding.binaryMessenger, METHOD_CHANNEL)
        methodChannel.setMethodCallHandler(this)

        eventChannel = EventChannel(binding.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                eventSink = events
                cameraStreamManager?.setSink(events)
            }
            override fun onCancel(arguments: Any?) {
                eventSink = null
                cameraStreamManager?.setSink(null)
            }
        })

        binding.platformViewRegistry.registerViewFactory(
            VIEW_TYPE,
            CameraPreviewFactory { cameraStreamManager }
        )
    }

    override fun onMethodCall(call: MethodCall, result: Result) {
        when (call.method) {
            "configure" -> handleConfigure(call, result)
            "startStream" -> handleStartStream(result)
            "stopStream" -> handleStopStream(result)
            "updateOverlay" -> handleUpdateOverlay(call, result)
            "updateSponsors" -> result.notImplemented()
            "switchCamera" -> handleSwitchCamera(call, result)
            "setAudioMute" -> handleSetAudioMute(call, result)
            else -> result.notImplemented()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleConfigure(call: MethodCall, result: Result) {
        val ctx = context ?: run {
            result.error("NO_CONTEXT", "Plugin context not available", null)
            return
        }
        val rtmpEndpoint = call.argument<String>("rtmpEndpoint") ?: run {
            result.error("INVALID_ARGS", "rtmpEndpoint is required", null)
            return
        }
        val rawSponsors = call.argument<List<Map<String, Any>>>("sponsors") ?: emptyList()
        val sponsors = rawSponsors.map { SponsorConfig.fromMap(it) }

        val width = call.argument<Int>("width") ?: 1280
        val height = call.argument<Int>("height") ?: 720
        val fps = call.argument<Int>("fps") ?: 30
        val videoBitrate = call.argument<Int>("videoBitrate") ?: 2_500_000
        val keyframeIntervalSeconds = call.argument<Int>("keyframeIntervalSeconds") ?: 2
        val orientation = call.argument<String>("orientation") ?: "landscape"
        val initialFacing = call.argument<String>("initialFacing") ?: "back"

        cameraStreamManager?.release()
        try {
            cameraStreamManager = CameraStreamManager(ctx).also { manager ->
                manager.configure(
                    rtmpEndpoint, sponsors,
                    width, height, fps, videoBitrate, keyframeIntervalSeconds,
                    orientation, initialFacing
                )
                eventSink?.let { manager.setSink(it) }
            }
            result.success(null)
        } catch (e: Exception) {
            cameraStreamManager = null
            result.error("CONFIGURE_ERROR", e.message ?: "Configure failed", null)
        }
    }

    private fun handleStartStream(result: Result) {
        val manager = cameraStreamManager ?: run {
            result.error("NOT_CONFIGURED", "configure() must be called before startStream()", null)
            return
        }
        if (manager.isStreaming) {
            result.error("ALREADY_STREAMING", "Stream is already active", null)
            return
        }
        try {
            manager.startStream()
            result.success(null)
        } catch (e: Exception) {
            result.error("STREAM_ERROR", e.message ?: "Failed to start stream", null)
        }
    }

    private fun handleStopStream(result: Result) {
        cameraStreamManager?.stopStream()
        result.success(null)
    }

    private fun handleUpdateOverlay(call: MethodCall, result: Result) {
        val layerId = call.argument<String>("layerId") ?: run {
            result.error("INVALID_ARGS", "layerId is required", null)
            return
        }
        val bytes = call.argument<ByteArray>("bytes") ?: run {
            result.error("INVALID_ARGS", "bytes is required", null)
            return
        }
        when (layerId) {
            "scoreband" -> {
                cameraStreamManager?.updateScoreband(bytes)
                result.success(null)
            }
            else -> result.error("UNKNOWN_LAYER", "Unknown layerId: $layerId", null)
        }
    }

    private fun handleSwitchCamera(call: MethodCall, result: Result) {
        val facing = call.argument<String>("facing") ?: "back"
        cameraStreamManager?.switchCamera(facing)
        result.success(null)
    }

    private fun handleSetAudioMute(call: MethodCall, result: Result) {
        val muted = call.argument<Boolean>("muted") ?: false
        cameraStreamManager?.setAudioMuted(muted)
        result.success(null)
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        methodChannel.setMethodCallHandler(null)
        eventChannel.setStreamHandler(null)
        cameraStreamManager?.release()
        cameraStreamManager = null
        eventSink = null
        context = null
    }

    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivityForConfigChanges() {
        activity = null
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        activity = binding.activity
    }

    override fun onDetachedFromActivity() {
        activity = null
    }
}

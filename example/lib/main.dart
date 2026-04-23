import 'dart:async';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:permission_handler/permission_handler.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'RTMP Broadcaster',
      theme: ThemeData(colorSchemeSeed: Colors.blue, useMaterial3: true),
      home: const _PermissionGatePage(),
    );
  }
}

// ---------------------------------------------------------------------------
// Permission gate
// ---------------------------------------------------------------------------

class _PermissionGatePage extends StatefulWidget {
  const _PermissionGatePage();

  @override
  State<_PermissionGatePage> createState() => _PermissionGatePageState();
}

class _PermissionGatePageState extends State<_PermissionGatePage> {
  bool _checking = true;
  bool _granted = false;

  @override
  void initState() {
    super.initState();
    _request();
  }

  Future<void> _request() async {
    final statuses = await [Permission.camera, Permission.microphone].request();
    final ok = statuses.values.every((s) => s.isGranted);
    setState(() {
      _granted = ok;
      _checking = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!_granted) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Camera and microphone permission required.'),
              const SizedBox(height: 12),
              ElevatedButton(onPressed: openAppSettings, child: const Text('Open Settings')),
            ],
          ),
        ),
      );
    }
    return const _RtmpConfigScreen();
  }
}

// ---------------------------------------------------------------------------
// RTMP Config Screen
// ---------------------------------------------------------------------------

class _RtmpConfigScreen extends StatefulWidget {
  const _RtmpConfigScreen();

  @override
  State<_RtmpConfigScreen> createState() => _RtmpConfigScreenState();
}

class _RtmpConfigScreenState extends State<_RtmpConfigScreen> {
  final _urlCtrl = TextEditingController(text: 'rtmp://a.rtmp.youtube.com/live2');
  final _keyCtrl = TextEditingController(text: '0rqt-kuhd-qkah-vqbk-1j7y');
  bool _connecting = false;

  Future<void> _connect() async {
    final url = _urlCtrl.text.trim();
    final key = _keyCtrl.text.trim();
    if (url.isEmpty || key.isEmpty) {
      _showSnack('Enter RTMP URL and stream key');
      return;
    }
    setState(() => _connecting = true);
    try {
      final controller = RtmpBroadcastController();
      final config = StreamConfig.defaultConfig;
      await controller.initPreview(config: config);
      await controller.configure(rtmpUrl: url, rtmpKey: key, sponsors: [], config: config);
      await controller.setAppOrientation(config.orientation);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => _CameraScreen(controller: controller)),
      );
    } on RtmpBroadcasterException catch (e) {
      _showSnack('Failed: ${e.code} — ${e.message}');
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    _keyCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('RTMP Configuration')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Enter your RTMP details to go live',
              style: TextStyle(fontSize: 16, color: Colors.grey),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            TextField(
              controller: _urlCtrl,
              decoration: const InputDecoration(
                labelText: 'RTMP URL',
                hintText: 'rtmp://a.rtmp.youtube.com/live2',
                border: OutlineInputBorder(),
              ),
              keyboardType: TextInputType.url,
              autocorrect: false,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _keyCtrl,
              decoration: const InputDecoration(
                labelText: 'Stream Key',
                hintText: 'your-stream-key',
                border: OutlineInputBorder(),
              ),
              obscureText: true,
            ),
            const SizedBox(height: 32),
            SizedBox(
              height: 50,
              child: FilledButton(
                onPressed: _connecting ? null : _connect,
                child: _connecting
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      )
                    : const Text('Go Live'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Camera Preview Screen (Full Screen)
// ---------------------------------------------------------------------------

class _CameraScreen extends StatefulWidget {
  const _CameraScreen({required this.controller});

  final RtmpBroadcastController controller;

  @override
  State<_CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<_CameraScreen> {
  final _scoreBandKey = GlobalKey();
  VideoResolution _selectedRes = VideoResolution.hd720;
  VideoOrientation _selectedOrient = VideoOrientation.portrait;
  CameraFacing _currentFacing = CameraFacing.back;
  bool _muted = false;
  bool _streaming = false;
  int _score = 0;
  Timer? _scoreTimer;
  StreamSubscription<RtmpStatus>? _statusSub;

  @override
  void initState() {
    super.initState();
    _statusSub = widget.controller.statusStream.listen(_onStatus);
  }

  void _onStatus(RtmpStatus s) {
    setState(() {
      switch (s.type) {
        case RtmpStatusType.connected:
          _streaming = true;
        case RtmpStatusType.disconnected:
          _streaming = false;
        case RtmpStatusType.error:
          _showSnack('Error: ${s.errorCode}');
        case RtmpStatusType.bitrate:
        case RtmpStatusType.reconnecting:
          break;
      }
    });
  }

  Future<void> _toggleStream() async {
    if (_streaming) {
      _scoreTimer?.cancel();
      await widget.controller.stopStream();
    } else {
      await widget.controller.startStream();
      _startScorebandTimer();
    }
  }

  Future<void> _flipCamera() async {
    final newFacing = _currentFacing == CameraFacing.back ? CameraFacing.front : CameraFacing.back;
    await widget.controller.switchCamera(facing: newFacing);
    setState(() => _currentFacing = newFacing);
  }

  Future<void> _toggleMute() async {
    _muted = !_muted;
    await widget.controller.setAudioMuted(_muted);
  }

  Future<void> _pushScoreband() async {
    try {
      final boundary = _scoreBandKey.currentContext?.findRenderObject() as RenderRepaintBoundary?;
      if (boundary == null) return;
      final image = await boundary.toImage(pixelRatio: 2.0);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) return;
      final bytes = byteData.buffer.asUint8List();
      await widget.controller.updateScoreband(bytes);
    } catch (_) {}
  }

  void _showSettingsModal() {
    showModalBottomSheet(
      context: context,
      builder: (ctx) => Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text('Settings', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
            const SizedBox(height: 16),
            Row(
              children: [
                const SizedBox(width: 80, child: Text('Resolution')),
                Expanded(
                  child: DropdownButton<VideoResolution>(
                    value: _selectedRes,
                    isExpanded: true,
                    items: const [
                      DropdownMenuItem(value: VideoResolution.hd720, child: Text('720p')),
                      DropdownMenuItem(value: VideoResolution.fhd1080, child: Text('1080p')),
                    ],
                    onChanged: (v) {
                      setState(() => _selectedRes = v!);
                      Navigator.pop(ctx);
                    },
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                const SizedBox(width: 80, child: Text('Orientation')),
                Expanded(
                  child: DropdownButton<VideoOrientation>(
                    value: _selectedOrient,
                    isExpanded: true,
                    items: const [
                      DropdownMenuItem(value: VideoOrientation.landscape, child: Text('Landscape')),
                      DropdownMenuItem(value: VideoOrientation.portrait, child: Text('Portrait')),
                    ],
                    onChanged: (v) async {
                      if (v == null) return;
                      await widget.controller.setAppOrientation(v);
                      setState(() => _selectedOrient = v);
                      Navigator.pop(ctx);
                    },
                  ),
                ),
              ],
            ),
            const SizedBox(height: 24),
          ],
        ),
      ),
    );
  }

  void _startScorebandTimer() {
    _scoreTimer?.cancel();
    _scoreTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      setState(() => _score++);
      _pushScoreband();
    });
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _scoreTimer?.cancel();
    _statusSub?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Stack(
        children: [
          // Camera preview (full screen)
          const Positioned.fill(
            child: RtmpBroadcastWidget(),
          ),

          // Settings icon (top-right, hidden when streaming)
          if (!_streaming)
            Positioned(
              top: MediaQuery.of(context).padding.top + 16,
              right: 16,
              child: FloatingActionButton.small(
                heroTag: 'settings',
                onPressed: _showSettingsModal,
                child: const Icon(Icons.settings),
              ),
            ),

          // Scoreband overlay (visible when streaming)
          if (_streaming)
            Positioned(
              top: MediaQuery.of(context).padding.top + 16,
              left: 16,
              right: 16,
              child: RepaintBoundary(
                key: _scoreBandKey,
                child: Container(
                  padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                  decoration: BoxDecoration(
                    color: Colors.black.withValues(alpha: 0.6),
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: Text(
                    'SCORE: $_score',
                    style: const TextStyle(
                      color: Colors.white,
                      fontSize: 18,
                      fontWeight: FontWeight.bold,
                    ),
                    textAlign: TextAlign.center,
                  ),
                ),
              ),
            ),

          // Three floating buttons (bottom)
          Positioned(
            bottom: 32,
            left: 16,
            right: 16,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.spaceEvenly,
              children: [
                // Flip Camera
                FloatingActionButton(
                  heroTag: 'flip',
                  onPressed: _flipCamera,
                  child: const Icon(Icons.flip_camera_android),
                ),
                // Start/Stop Stream
                FloatingActionButton(
                  heroTag: 'stream',
                  backgroundColor: _streaming ? Colors.red : Colors.green,
                  onPressed: _toggleStream,
                  child: Icon(_streaming ? Icons.stop : Icons.play_arrow),
                ),
                // Mute/Unmute
                FloatingActionButton(
                  heroTag: 'mute',
                  backgroundColor: _muted ? Colors.orange : Colors.blue,
                  onPressed: _toggleMute,
                  child: Icon(_muted ? Icons.mic_off : Icons.mic),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}
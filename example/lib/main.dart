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
      title: 'RTMP Broadcaster Test',
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
    return const _StreamPage();
  }
}

// ---------------------------------------------------------------------------
// Main streaming page
// ---------------------------------------------------------------------------

class _StreamPage extends StatefulWidget {
  const _StreamPage();

  @override
  State<_StreamPage> createState() => _StreamPageState();
}

class _StreamPageState extends State<_StreamPage> {
  final _urlCtrl = TextEditingController(text: 'rtmp://a.rtmp.youtube.com/live2');
  final _keyCtrl = TextEditingController(text: '0rqt-kuhd-qkah-vqbk-1j7y');
  final _controller = RtmpBroadcastController();
  final _scorebandKey = GlobalKey();

  bool _configured = false;
  bool _streaming = false;
  String _statusText = 'Not configured';
  int _score = 0;
  Timer? _scoreTimer;
  StreamSubscription<RtmpStatus>? _statusSub;

  @override
  void initState() {
    super.initState();
    _statusSub = _controller.statusStream.listen(_onStatus);
  }

  void _onStatus(RtmpStatus s) {
    setState(() {
      switch (s.type) {
        case RtmpStatusType.connected:
          _statusText = 'Connected';
          _streaming = true;
        case RtmpStatusType.disconnected:
          _statusText = 'Disconnected: ${s.reason ?? ''}';
          _streaming = false;
        case RtmpStatusType.error:
          _statusText = 'Error ${s.errorCode}: ${s.errorMessage}';
        case RtmpStatusType.bitrate:
          _statusText = 'Streaming • ${s.kbps} kbps';
        case RtmpStatusType.reconnecting:
          _statusText = 'Reconnecting (attempt ${s.reconnectAttempt})';
      }
    });
  }

  Future<void> _configure() async {
    final url = _urlCtrl.text.trim();
    final key = _keyCtrl.text.trim();
    if (url.isEmpty || key.isEmpty) {
      _showSnack('Enter RTMP URL and stream key');
      return;
    }
    try {
      await _controller.configure(rtmpUrl: url, rtmpKey: key, sponsors: []);
      setState(() {
        _configured = true;
        _statusText = 'Ready — tap Start Stream';
      });
    } on RtmpBroadcasterException catch (e) {
      _showSnack('Configure failed: ${e.code} — ${e.message}');
    }
  }

  Future<void> _startStream() async {
    try {
      await _controller.startStream();
      _startScorebandTimer();
    } on RtmpBroadcasterException catch (e) {
      _showSnack('Start failed: ${e.code} — ${e.message}');
    }
  }

  Future<void> _stopStream() async {
    _scoreTimer?.cancel();
    await _controller.stopStream();
    setState(() {
      _streaming = false;
      _statusText = 'Stopped';
    });
  }

  void _startScorebandTimer() {
    _scoreTimer?.cancel();
    _scoreTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      setState(() => _score++);
      _pushScoreband();
    });
    _pushScoreband();
  }

  //rtmpUrl = rtmp://a.rtmp.youtube.com/live2, rtmpKey = dgz0-58hb-vvzv-a033-3j8a
  Future<void> _pushScoreband() async {
    final ctx = _scorebandKey.currentContext;
    if (ctx == null) return;
    final boundary = ctx.findRenderObject() as RenderRepaintBoundary?;
    if (boundary == null) return;
    try {
      await WidgetsBinding.instance.endOfFrame;
      final img = await boundary.toImage(pixelRatio: 2.0);
      final bytes = await img.toByteData(format: ui.ImageByteFormat.png);
      if (bytes == null) return;
      await _controller.updateScoreband(bytes.buffer.asUint8List());
    } catch (_) {}
  }

  void _showSnack(String msg) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _scoreTimer?.cancel();
    _statusSub?.cancel();
    _controller.dispose();
    _urlCtrl.dispose();
    _keyCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('RTMP Broadcaster'),
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(24),
          child: Padding(
            padding: const EdgeInsets.only(bottom: 6),
            child: Text(
              _statusText,
              style: TextStyle(fontSize: 12, color: _streaming ? Colors.green.shade700 : Colors.grey.shade700),
            ),
          ),
        ),
      ),
      body: Column(
        children: [
          // Camera preview (only shown after configure so PlatformView is
          // created after GenericStream is prepared)
          Expanded(
            child: _configured
                ? RtmpBroadcastWidget(controller: _controller)
                : const ColoredBox(
                    color: Colors.black,
                    child: Center(child: Icon(Icons.videocam_off, size: 64, color: Colors.white38)),
                  ),
          ),

          // Scoreband — visible for testing; RepaintBoundary enables capture
          RepaintBoundary(
            key: _scorebandKey,
            child: _ScoreBand(score: _score),
          ),

          // Config / stream controls
          _ControlPanel(
            configured: _configured,
            streaming: _streaming,
            urlCtrl: _urlCtrl,
            keyCtrl: _keyCtrl,
            onConfigure: _configure,
            onStart: _startStream,
            onStop: _stopStream,
          ),
        ],
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Scoreband widget — demo only; renders score text for native overlay capture
// ---------------------------------------------------------------------------

class _ScoreBand extends StatelessWidget {
  const _ScoreBand({required this.score});
  final int score;

  @override
  Widget build(BuildContext context) {
    final runs = score * 7;
    final wickets = score % 10;
    final overs = (score * 0.3).toStringAsFixed(1);
    return SizedBox(
      width: double.infinity,
      height: 56,
      child: ColoredBox(
        color: const Color(0xDD003366),
        child: Row(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              'TEAM A  $runs/$wickets  •  $overs ov  •  TEAM B 0/0',
              style: const TextStyle(
                color: Colors.white,
                fontSize: 15,
                fontWeight: FontWeight.bold,
                letterSpacing: 0.5,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------------------------------------------------------------------
// Control panel — config form or stream buttons
// ---------------------------------------------------------------------------

class _ControlPanel extends StatelessWidget {
  const _ControlPanel({
    required this.configured,
    required this.streaming,
    required this.urlCtrl,
    required this.keyCtrl,
    required this.onConfigure,
    required this.onStart,
    required this.onStop,
  });

  final bool configured;
  final bool streaming;
  final TextEditingController urlCtrl;
  final TextEditingController keyCtrl;
  final VoidCallback onConfigure;
  final VoidCallback onStart;
  final VoidCallback onStop;

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.grey.shade100,
      padding: const EdgeInsets.fromLTRB(16, 12, 16, 20),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          if (!configured) ...[
            TextField(
              controller: urlCtrl,
              decoration: const InputDecoration(
                labelText: 'RTMP URL (e.g. rtmp://a.rtmp.youtube.com/live2)',
                isDense: true,
                border: OutlineInputBorder(),
              ),
              keyboardType: TextInputType.url,
              autocorrect: false,
            ),
            const SizedBox(height: 10),
            TextField(
              controller: keyCtrl,
              decoration: const InputDecoration(labelText: 'Stream Key', isDense: true, border: OutlineInputBorder()),
              obscureText: true,
            ),
            const SizedBox(height: 12),
            SizedBox(
              width: double.infinity,
              child: FilledButton(onPressed: onConfigure, child: const Text('Configure & Start Preview')),
            ),
          ] else ...[
            Row(
              children: [
                Expanded(
                  child: FilledButton(
                    onPressed: streaming ? null : onStart,
                    style: FilledButton.styleFrom(backgroundColor: Colors.green.shade700),
                    child: const Text('Start Stream'),
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: FilledButton(
                    onPressed: streaming ? onStop : null,
                    style: FilledButton.styleFrom(backgroundColor: Colors.red.shade700),
                    child: const Text('Stop Stream'),
                  ),
                ),
              ],
            ),
          ],
        ],
      ),
    );
  }
}

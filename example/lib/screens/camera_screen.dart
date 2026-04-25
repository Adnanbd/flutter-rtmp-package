import 'dart:async';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

import '../widgets/camera_controls_bar.dart';
import '../widgets/scoreband_widget.dart';
import '../widgets/settings_sheet.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key, required this.controller});

  final RtmpBroadcastController controller;

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  final _scoreBandKey = GlobalKey();
  VideoResolution _selectedRes = VideoResolution.hd720;
  VideoOrientation _selectedOrient = VideoOrientation.portrait;
  CameraFacing _currentFacing = CameraFacing.back;
  bool _muted = false;
  bool _streaming = false;
  Timer? _scoreTimer;
  StreamSubscription<RtmpStatus>? _statusSub;

  final String _homeTeam = 'TEAM A';
  final String _awayTeam = 'TEAM B';
  int _homeScore = 0;
  int _awayScore = 0;
  int _matchTime = 0;

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
          // Scoreband widget rebuilds with Opacity(1.0) on this setState.
          // Push after the next frame so the capture is non-transparent.
          WidgetsBinding.instance.addPostFrameCallback((_) => _pushScoreband());
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
    final ctx = _scoreBandKey.currentContext;
    if (ctx == null) {
      debugPrint('[scoreband] key has no context — widget not rendered yet');
      return;
    }
    final boundary = ctx.findRenderObject() as RenderRepaintBoundary?;
    if (boundary == null) {
      debugPrint('[scoreband] boundary null — no RenderRepaintBoundary');
      return;
    }
    if (boundary.debugNeedsPaint) {
      debugPrint('[scoreband] boundary needs paint — waiting one frame');
      await WidgetsBinding.instance.endOfFrame;
    }
    try {
      final image = await boundary.toImage(pixelRatio: 2.0);
      final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
      if (byteData == null) {
        debugPrint('[scoreband] toByteData returned null');
        return;
      }
      final bytes = byteData.buffer.asUint8List();
      debugPrint('[scoreband] captured ${image.width}x${image.height}, ${bytes.length} bytes — sending to native');
      await widget.controller.updateScoreband(bytes);
      debugPrint('[scoreband] updateScoreband returned OK');
    } catch (e, st) {
      debugPrint('[scoreband] PUSH FAILED: $e\n$st');
    }
  }

  void _showSettingsModal() {
    showModalBottomSheet(
      context: context,
      builder: (_) => SettingsSheet(
        selectedRes: _selectedRes,
        selectedOrient: _selectedOrient,
        onResChanged: (v) => setState(() => _selectedRes = v),
        onOrientChanged: (v) async {
          await widget.controller.setAppOrientation(v);
          if (!mounted) return;
          setState(() => _selectedOrient = v);
        },
      ),
    );
  }

  void _startScorebandTimer() {
    _scoreTimer?.cancel();
    _scoreTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      setState(() {
        _homeScore = Random().nextInt(5);
        _awayScore = Random().nextInt(5);
        _matchTime += 3;
      });
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
          const Positioned.fill(
            child: RtmpBroadcastWidget(),
          ),

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

          Positioned(
            bottom: _streaming ? -10000 : 100,
            left: 16,
            right: 16,
            child: ScorebandWidget(
              repaintBoundaryKey: _scoreBandKey,
              homeTeam: _homeTeam,
              awayTeam: _awayTeam,
              homeScore: _homeScore,
              awayScore: _awayScore,
              matchTime: _matchTime,
              streaming: _streaming,
            ),
          ),

          Positioned(
            bottom: 32,
            left: 16,
            right: 16,
            child: CameraControlsBar(
              streaming: _streaming,
              muted: _muted,
              onFlip: _flipCamera,
              onToggleStream: _toggleStream,
              onToggleMute: _toggleMute,
            ),
          ),
        ],
      ),
    );
  }
}

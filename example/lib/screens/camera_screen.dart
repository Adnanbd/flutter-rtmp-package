import 'dart:async';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/score.band.dart';

import '../widgets/camera_controls_bar.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key, required this.controller});

  final RtmpBroadcastController controller;

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen> {
  final _scoreBandKey = GlobalKey();
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
  bool _previewBound = false;

  @override
  void initState() {
    super.initState();
    _statusSub = widget.controller.statusStream.listen(_onStatus);
    widget.controller.previewBound.addListener(_onPreviewBoundChanged);
    _previewBound = widget.controller.previewBound.value;
  }

  void _onPreviewBoundChanged() {
    if (mounted) setState(() => _previewBound = widget.controller.previewBound.value);
  }

  void _onStatus(RtmpStatus s) {
    setState(() {
      switch (s.type) {
        case RtmpStatusType.connected:
          _streaming = true;
          WidgetsBinding.instance.addPostFrameCallback((_) => _pushScoreband());
        case RtmpStatusType.disconnected:
          _streaming = false;
        case RtmpStatusType.error:
          _showSnack('Error: ${s.errorCode} — ${s.errorMessage}');
        case RtmpStatusType.warning:
          _showSnack('Warning: ${s.errorCode} — ${s.errorMessage}');
        case RtmpStatusType.bitrate:
        case RtmpStatusType.reconnecting:
        case RtmpStatusType.previewBound:
        case RtmpStatusType.previewUnbound:
        case RtmpStatusType.usbDetached:
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
    setState(() => _muted = !_muted);
    await widget.controller.setAudioMuted(_muted);
  }

  Future<void> _goBack() async {
    _scoreTimer?.cancel();
    await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp, DeviceOrientation.portraitDown]);
    widget.controller.dispose();
    if (!mounted) return;
    Navigator.of(context).pop();
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
      final image = await boundary.toImage(pixelRatio: 1.0);
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

  void _startScorebandTimer() {
    _scoreTimer?.cancel();
    _scoreTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      setState(() {
        _homeScore = _homeScore + Random().nextInt(5);
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
    widget.controller.previewBound.removeListener(_onPreviewBoundChanged);
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_streaming,
      child: Scaffold(
        body: Stack(
          children: [
            const Positioned.fill(child: RtmpBroadcastWidget()),

            // Back button — only when not streaming
            if (!_streaming)
              Positioned(
                top: MediaQuery.of(context).padding.top + 16,
                left: 16,
                child: FloatingActionButton.small(
                  heroTag: 'back',
                  onPressed: _goBack,
                  backgroundColor: Colors.black54,
                  child: const Icon(Icons.arrow_back, color: Colors.white),
                ),
              ),

            // Positioned(
            //   bottom: _streaming ? -10000 : 100,
            //   left: 16,
            //   right: 16,
            //   child: ScoreBandView(
            //     repaintBoundaryKey: _scoreBandKey,
            //     homeTeam: _homeTeam,
            //     awayTeam: _awayTeam,
            //     homeScore: _homeScore,
            //     awayScore: _awayScore,
            //     matchTime: _matchTime,
            //     streaming: _streaming,
            //   ),
            // ),
            Positioned(
              bottom: _streaming ? -10000 : 16,
              left: 16,
              right: 16,
              child: ScoreBandView(repaintBoundaryKey: _scoreBandKey, homeScore: _homeScore),
            ),

            Positioned(
              bottom: 32,
              left: 16,
              right: 16,
              child: CameraControlsBar(
                streaming: _streaming,
                muted: _muted,
                currentFacing: _currentFacing,
                onFlip: _flipCamera,
                onToggleStream: _toggleStream,
                onToggleMute: _toggleMute,
                flipEnabled: widget.controller.config.videoInput == VideoInput.device,
                streamEnabled: _previewBound || _streaming,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

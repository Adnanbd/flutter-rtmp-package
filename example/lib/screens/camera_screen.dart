import 'dart:async';
import 'dart:math';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_rtmp_broadcaster_example/score.band/score.band.dart';

import '../overlay_studio/overlay_studio.dart';
import '../overlay_studio/overlay_studio_sheet.dart';
import '../overlay_studio/stream_hud.dart';
import '../overlay_studio/studio_scenarios.dart';
import '../widgets/camera_controls_bar.dart';
import '../widgets/zoom_control.dart';

class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key, required this.controller, this.scorebandWeight = 50});

  final RtmpBroadcastController controller;

  /// Initial scoreband layer weight (config screen); changed live from the Overlay Studio.
  final int scorebandWeight;

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
  bool _scorebandPushed = false;

  late final OverlayStudio _studio;
  late final AutoDemo _demo;
  late final ZoomModel _zoom;
  double _pinchBase = 1;

  @override
  void initState() {
    super.initState();
    _studio = OverlayStudio(widget.controller, onMessage: _showSnack, scorebandWeight: widget.scorebandWeight);
    _demo = AutoDemo(_studio);
    _zoom = ZoomModel(widget.controller);
    _studio.scorebandWeight.addListener(_onScorebandWeightChanged);
    _statusSub = widget.controller.statusStream.listen(_onStatus);
    widget.controller.previewBound.addListener(_onPreviewBoundChanged);
    _previewBound = widget.controller.previewBound.value;
  }

  void _onScorebandWeightChanged() {
    _studio.log('scoreband weight → ${_studio.scorebandWeight.value}');
    if (_scorebandPushed) _pushScoreband();
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
          // Overlay warnings are shown in the Overlay Studio log / HUD instead.
          if (!(s.errorCode ?? '').startsWith('OVERLAY_')) _showSnack('Warning: ${s.errorCode} — ${s.errorMessage}');
        case RtmpStatusType.bitrate:
        case RtmpStatusType.reconnecting:
        case RtmpStatusType.previewBound:
        case RtmpStatusType.previewUnbound:
        case RtmpStatusType.usbDetached:
        case RtmpStatusType.overlayShown:
        case RtmpStatusType.overlayHidden:
        case RtmpStatusType.overlayRemoved:
        case RtmpStatusType.zoomChanged: // handled by ZoomModel
          break;
      }
    });
  }

  Future<void> _toggleStream() async {
    if (_streaming) {
      _scoreTimer?.cancel();
      await widget.controller.stopStream();
      _studio.streamStopped();
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
    _demo.stop();
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
      await widget.controller.updateScoreband(bytes, weight: _studio.scorebandWeight.value);
      _scorebandPushed = true;
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
    _demo.stop();
    _studio.scorebandWeight.removeListener(_onScorebandWeightChanged);
    _studio.dispose();
    _zoom.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return PopScope(
      canPop: !_streaming,
      child: Scaffold(
        body: Stack(
          children: [
            // Pinch to zoom: the widget stays a bare platform view; the gesture lives in the host app.
            Positioned.fill(
              child: GestureDetector(
                onScaleStart: (_) => _pinchBase = _zoom.info?.current ?? 1,
                onScaleUpdate: (d) {
                  if (d.pointerCount >= 2) _zoom.setZoom(_pinchBase * d.scale);
                },
                child: const RtmpBroadcastWidget(),
              ),
            ),

            // Zoom slider + presets (hidden until the camera reports its range)
            Positioned(
              right: 16,
              top: MediaQuery.of(context).padding.top + 80,
              child: ZoomControl(model: _zoom),
            ),

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

            // Overlay status HUD (example chrome, not in the stream)
            Positioned(
              top: MediaQuery.of(context).padding.top + (_streaming ? 16 : 72),
              left: 16,
              child: StreamHud(studio: _studio),
            ),

            // Overlay Studio: scenarios, builder, active overlays, event log — usable before and during a stream
            Positioned(
              top: MediaQuery.of(context).padding.top + 16,
              right: 16,
              child: ListenableBuilder(
                listenable: _studio,
                builder: (context, _) => Badge(
                  isLabelVisible: _studio.overlays.isNotEmpty,
                  label: Text('${_studio.overlays.length}'),
                  child: FloatingActionButton.small(
                    heroTag: 'overlays',
                    tooltip: 'Overlay Studio',
                    onPressed: () => showOverlayStudio(context, _studio, _demo),
                    backgroundColor: Colors.black54,
                    child: const Icon(Icons.layers, color: Colors.white),
                  ),
                ),
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

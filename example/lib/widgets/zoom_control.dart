import 'dart:async';
import 'dart:math' as math;

import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

/// Example-side zoom state: what a host app would keep to drive a zoom UI.
///
/// Reads the range once the preview is bound (retrying while the camera opens), follows
/// `zoomChanged` events, and coalesces rapid pinch updates to one in-flight `setZoom` call.
class ZoomModel extends ChangeNotifier {
  ZoomModel(this.controller) {
    _statusSub = controller.statusStream.listen((s) {
      if (s.type == RtmpStatusType.zoomChanged && s.zoom != null) {
        _info = s.zoom;
        lastEvent = '${s.reason} → ${s.zoom!.current.toStringAsFixed(1)}×';
        notifyListeners();
      }
    });
    controller.previewBound.addListener(_onPreviewBound);
    if (controller.previewBound.value) refresh();
  }

  final RtmpBroadcastController controller;
  StreamSubscription<RtmpStatus>? _statusSub;
  ZoomInfo? _info;
  String? lastEvent;
  String? error;

  double? _pending;
  bool _inFlight = false;
  bool _disposed = false;

  ZoomInfo? get info => _info;

  void _onPreviewBound() {
    if (controller.previewBound.value) refresh();
  }

  /// Reads the zoom state; the camera opens asynchronously after `previewBound`, so retry `ZOOM_NOT_READY`.
  Future<void> refresh() async {
    for (var attempt = 0; attempt < 15 && !_disposed; attempt++) {
      try {
        _info = await controller.getZoom();
        error = null;
        notifyListeners();
        return;
      } on RtmpBroadcasterException catch (e) {
        if (e.code != 'ZOOM_NOT_READY') {
          error = e.code;
          notifyListeners();
          return;
        }
        await Future<void>.delayed(const Duration(milliseconds: 200));
      }
    }
  }

  /// Latest value wins; at most one call on the channel at a time.
  void setZoom(double level) {
    final i = _info;
    if (i == null || !i.supported) return;
    _pending = level.clamp(i.min, i.max);
    _info = ZoomInfo(supported: true, min: i.min, max: i.max, current: _pending!, source: i.source);
    notifyListeners();
    if (!_inFlight) _drain();
  }

  Future<void> _drain() async {
    _inFlight = true;
    while (_pending != null && !_disposed) {
      final level = _pending!;
      _pending = null;
      try {
        final applied = await controller.setZoom(level);
        if (_pending == null) _info = applied;
        error = null;
      } on RtmpBroadcasterException catch (e) {
        error = e.code;
      }
      if (!_disposed) notifyListeners();
    }
    _inFlight = false;
  }

  @override
  void dispose() {
    _disposed = true;
    _statusSub?.cancel();
    controller.previewBound.removeListener(_onPreviewBound);
    super.dispose();
  }
}

/// Vertical zoom slider (log scale) with preset chips and a readout. Hidden until the range is known.
class ZoomControl extends StatelessWidget {
  const ZoomControl({super.key, required this.model});

  final ZoomModel model;

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: model,
      builder: (context, _) {
        final info = model.info;
        if (info == null) return const SizedBox.shrink();
        if (!info.supported) {
          return const _Pill(child: Text('No zoom', style: TextStyle(color: Colors.white70, fontSize: 12)));
        }
        final lo = math.log(info.min);
        final hi = math.log(info.max);
        final presets = <double>{if (info.min < 1) info.min, 1, 2, 5}.where((p) => p >= info.min && p <= info.max);
        return _Pill(
          child: Column(mainAxisSize: MainAxisSize.min, children: [
            Text('${info.current.toStringAsFixed(1)}×',
                style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold)),
            SizedBox(
              height: 160,
              child: RotatedBox(
                quarterTurns: 3,
                child: Slider(
                  value: math.log(info.current).clamp(lo, hi),
                  min: lo,
                  max: hi,
                  onChanged: (v) => model.setZoom(math.exp(v)),
                ),
              ),
            ),
            for (final p in presets)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: ChoiceChip(
                  visualDensity: VisualDensity.compact,
                  label: Text('${p < 1 ? p.toStringAsFixed(1) : p.toStringAsFixed(0)}×'),
                  selected: (info.current - p).abs() < 0.05,
                  onSelected: (_) => model.setZoom(p),
                ),
              ),
            if (model.error != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text(model.error!, style: const TextStyle(color: Colors.redAccent, fontSize: 10)),
              ),
          ]),
        );
      },
    );
  }
}

class _Pill extends StatelessWidget {
  const _Pill({required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) => Container(
        padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 8),
        decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(20)),
        child: child,
      );
}

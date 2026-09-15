import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

import 'mock_match.dart';

enum StreamPhase { offline, live, reconnecting }

enum OverlayKind { image, gif, text, ticker, carousel }

/// What the studio knows about one dynamic overlay it added. Native state arrives via `statusStream`.
class StudioOverlay {
  StudioOverlay({
    required this.id,
    required this.label,
    required this.kind,
    required this.weight,
    this.duration,
    this.content,
    this.placement = const OverlayPlacement(),
  });

  final String id;
  final String label;
  final OverlayKind kind;
  int weight;
  Duration? duration;

  /// Last content / placement sent to the plugin — the base for quick updates.
  OverlayContent? content;
  OverlayPlacement placement;

  String? get text => switch (content) {
        TextContent(:final text) => text,
        TickerContent(:final text) => text,
        _ => null,
      };

  TextOverlayStyle? get style => switch (content) {
        TextContent(:final style) => style,
        TickerContent(:final style) => style,
        _ => null,
      };

  /// adding | visible | hidden | removing
  String state = 'adding';
}

class StudioEvent {
  StudioEvent(this.message, {this.isError = false}) : at = DateTime.now();

  final DateTime at;
  final String message;
  final bool isError;
}

/// State + actions behind the example's Overlay Studio (M11 test bed). Lives as long as the camera screen,
/// so the overlay list and event log survive closing the sheet.
class OverlayStudio extends ChangeNotifier {
  OverlayStudio(this.controller, {required this.onMessage, required int scorebandWeight})
      : scorebandWeight = ValueNotifier(scorebandWeight) {
    _sub = controller.statusStream.listen(_onStatus);
  }

  final RtmpBroadcastController controller;
  final ValueChanged<String> onMessage;
  final ValueNotifier<int> scorebandWeight;
  final match = MockMatch();

  late final StreamSubscription<RtmpStatus> _sub;
  final Map<String, StudioOverlay> overlays = {};
  final List<StudioEvent> events = [];
  StreamPhase phase = StreamPhase.offline;
  DateTime? liveSince;
  int reconnectAttempt = 0;

  /// Latest encoder bitrate from `bitrate` events (adaptive bitrate may lower it).
  int? kbps;
  int _counter = 0;
  bool _disposed = false;

  /// Ids re-added while their previous overlay was still animating out: skip that old `overlayRemoved`.
  final Set<String> _staleRemoval = {};

  bool get isDisposed => _disposed;

  int get hiddenCount => overlays.values.where((o) => o.state == 'hidden').length;

  String nextId(String prefix) => '${prefix}_${++_counter}';

  void log(String message, {bool isError = false}) {
    if (_disposed) return;
    events.insert(0, StudioEvent(message, isError: isError));
    if (events.length > 200) events.removeLast();
    notifyListeners();
  }

  void clearLog() {
    events.clear();
    notifyListeners();
  }

  /// Adds and tracks an overlay. Returns false (and logs the code) when the plugin rejects it.
  Future<bool> add(DynamicOverlay overlay, {required String label, required OverlayKind kind}) async {
    if (_disposed) return false;
    if (overlays[overlay.id]?.state == 'removing') _staleRemoval.add(overlay.id);
    // Track first: overlayShown can arrive before addOverlay's future completes.
    overlays[overlay.id] = StudioOverlay(
      id: overlay.id,
      label: label,
      kind: kind,
      weight: overlay.weight,
      duration: overlay.duration,
      content: overlay.content,
      placement: overlay.placement,
    );
    notifyListeners();
    try {
      await controller.addOverlay(overlay);
      log('add ${overlay.id} — $label');
      return true;
    } on RtmpBroadcasterException catch (e) {
      overlays.remove(overlay.id);
      _staleRemoval.remove(overlay.id);
      _fail('add ${overlay.id}', e);
      return false;
    }
  }

  /// Runs a controller call, logging success or the error code.
  Future<bool> run(String what, Future<void> Function() op) async {
    if (_disposed) return false;
    try {
      await op();
      log(what);
      return true;
    } on RtmpBroadcasterException catch (e) {
      _fail(what, e);
      return false;
    }
  }

  Future<void> toggle(StudioOverlay o) => o.state == 'hidden'
      ? run('show ${o.id}', () => controller.showOverlay(o.id))
      : run('hide ${o.id}', () => controller.hideOverlay(o.id));

  Future<void> setWeight(StudioOverlay o, int weight) async {
    if (await run('weight ${o.id} → $weight', () => controller.updateOverlay(o.id, weight: weight))) {
      o.weight = weight;
      notifyListeners();
    }
  }

  Future<void> setDuration(StudioOverlay o, Duration? d, {bool restart = false}) async {
    final update = d == null ? const OverlayDurationUpdate.infinite() : OverlayDurationUpdate.of(d);
    final label = '${d == null ? '∞' : '${d.inSeconds}s'}${restart ? ' + restart' : ''}';
    if (await run('duration ${o.id} → $label',
        () => controller.updateOverlay(o.id, duration: update, restartTimer: restart))) {
      o.duration = d;
      notifyListeners();
    }
  }

  /// `updateOverlay` with new content and/or placement; remembers them on success.
  Future<bool> update(StudioOverlay o, String what, {OverlayContent? content, OverlayPlacement? placement}) async {
    final ok = await run('update ${o.id}: $what', () => controller.updateOverlay(o.id, content: content, placement: placement));
    if (ok) {
      if (content != null) o.content = content;
      if (placement != null) o.placement = placement;
      notifyListeners();
    }
    return ok;
  }

  // ---- quick updates (text / ticker keep their other settings) ----

  Future<void> setText(StudioOverlay o, String text) => switch (o.content) {
        TextContent c => update(o, 'text', content: c.copyWith(text: text)),
        TickerContent c => update(o, 'text', content: c.copyWith(text: text)),
        _ => Future.value(),
      };

  Future<void> _restyle(StudioOverlay o, String what, TextOverlayStyle Function(TextOverlayStyle s) change) async {
    final style = o.style;
    if (style == null) return;
    final next = change(style);
    await switch (o.content) {
      TextContent c => update(o, what, content: c.copyWith(style: next)),
      TickerContent c => update(o, what, content: c.copyWith(style: next)),
      _ => Future.value(),
    };
  }

  Future<void> changeFontSize(StudioOverlay o, double delta) => _restyle(
      o, 'font ${delta > 0 ? '+' : ''}${delta.round()}', (s) => s.copyWith(fontSizePx: (s.fontSizePx + delta).clamp(12, 160)));

  static const _backgrounds = <Color?>[Color(0xCC1565C0), Color(0xE6C62828), Color(0xB3000000), Color(0xE62E7D32), null];

  Future<void> cycleBackground(StudioOverlay o) => _restyle(o, 'background', (s) {
        final i = _backgrounds.indexOf(s.background);
        final bg = _backgrounds[(i + 1) % _backgrounds.length];
        return TextOverlayStyle(
          fontSizePx: s.fontSizePx,
          color: s.color,
          background: bg,
          paddingPx: s.paddingPx,
          fontTtf: s.fontTtf,
          maxLines: s.maxLines,
          align: s.align,
        );
      });

  Future<void> toggleWrap(StudioOverlay o) =>
      _restyle(o, 'wrap', (s) => s.copyWith(maxLines: s.maxLines > 1 ? 1 : 4));

  Future<void> resize(StudioOverlay o, double deltaPercent) {
    final w = o.placement.width;
    final current = w is PercentLength ? w.value.toDouble() : 40.0;
    final next = (current + deltaPercent).clamp(5.0, 100.0);
    return update(o, 'width ${next.round()} %', placement: o.placement.copyWith(width: OverlayLength.percent(next)));
  }

  Future<void> moveTo(StudioOverlay o, String name) {
    const edge = OverlayLength.px(24);
    final p = o.placement;
    final placement = switch (name) {
      'top-left' => OverlayPlacement(left: edge, top: edge, width: p.width, height: p.height),
      'top-right' => OverlayPlacement(right: edge, top: edge, width: p.width, height: p.height),
      'bottom-left' => OverlayPlacement(left: edge, bottom: const OverlayLength.percent(25), width: p.width, height: p.height),
      'bottom' => OverlayPlacement(bottom: const OverlayLength.percent(25), width: p.width, height: p.height),
      _ => OverlayPlacement(width: p.width, height: p.height),
    };
    return update(o, 'move $name', placement: placement);
  }

  Future<void> restartTimer(StudioOverlay o) =>
      run('restart timer ${o.id}', () => controller.updateOverlay(o.id, restartTimer: true));

  Future<void> remove(StudioOverlay o, {bool animate = true}) async {
    if (animate) markRemoving(o.id);
    await run('remove ${o.id}${animate ? '' : ' (instant)'}', () => controller.removeOverlay(o.id, animate: animate));
  }

  Future<void> clear({required bool animate}) =>
      run('clear all${animate ? ' (animated)' : ''}', () => controller.clearOverlays(animate: animate));

  void _fail(String what, RtmpBroadcasterException e) {
    log('$what FAILED ${e.code}: ${e.message}', isError: true);
    onMessage('${e.code}: ${e.message}');
  }

  void _onStatus(RtmpStatus s) {
    final id = s.overlayId;
    switch (s.type) {
      case RtmpStatusType.connected:
        phase = StreamPhase.live;
        liveSince ??= DateTime.now();
        reconnectAttempt = 0;
        log('RTMP connected — timers and tickers run');
      case RtmpStatusType.disconnected:
        if (phase == StreamPhase.live) phase = StreamPhase.reconnecting;
        log('RTMP disconnected: ${s.reason} — timers and tickers pause');
      case RtmpStatusType.bitrate:
        kbps = s.kbps;
      case RtmpStatusType.reconnecting:
        phase = StreamPhase.reconnecting;
        reconnectAttempt = s.reconnectAttempt ?? reconnectAttempt + 1;
        log('reconnecting #$reconnectAttempt');
      case RtmpStatusType.overlayShown:
        overlays[id]?.state = 'visible';
        log('← shown $id');
      case RtmpStatusType.overlayHidden:
        overlays[id]?.state = 'hidden';
        log('← hidden $id');
      case RtmpStatusType.overlayRemoved:
        if (!_staleRemoval.remove(id)) overlays.remove(id);
        log('← removed $id (${s.reason})');
      case RtmpStatusType.warning:
        log('← warning ${s.errorCode}${id != null ? ' [$id]' : ''}: ${s.errorMessage}', isError: true);
      case RtmpStatusType.error:
        log('← error ${s.errorCode}: ${s.errorMessage}', isError: true);
      default:
        return;
    }
    notifyListeners();
  }

  /// Called by the camera screen when the user stops the stream (no `disconnected` event is guaranteed).
  void streamStopped() {
    phase = StreamPhase.offline;
    liveSince = null;
    kbps = null;
    log('stream stopped — timers and tickers pause, remaining time kept');
  }

  /// Marks overlays the app asked to remove, so the list can show them fading out.
  void markRemoving(String id) {
    overlays[id]?.state = 'removing';
    notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _sub.cancel();
    scorebandWeight.dispose();
    super.dispose();
  }
}

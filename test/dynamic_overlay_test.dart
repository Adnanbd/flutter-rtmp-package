import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('flutter_rtmp_broadcaster/control');
  late List<MethodCall> calls;
  PlatformException? nextError;

  setUp(() {
    calls = [];
    nextError = null;
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
      calls.add(call);
      if (nextError != null) throw nextError!;
      return null;
    });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  final png = Uint8List.fromList([137, 80, 78, 71]);

  Matcher throwsCode(String code) => throwsA(
      isA<RtmpBroadcasterException>().having((e) => e.code, 'code', code));

  group('addOverlay', () {
    test('sends full wire payload', () async {
      await RtmpBroadcastController().addOverlay(DynamicOverlay(
        id: 'goal',
        content: ImageContent(png),
        weight: 70,
        placement: const OverlayPlacement(
          right: OverlayLength.px(24),
          top: OverlayLength.percent(5),
          width: OverlayLength.percent(30.5),
        ),
      ));
      expect(calls.single.method, 'overlayAdd');
      final args = calls.single.arguments as Map;
      expect(args['id'], 'goal');
      expect(args['weight'], 70);
      expect(args['content'], {'type': 'image', 'bytes': png});
      expect(args['placement'], {
        'right': {'unit': 'px', 'value': 24.0},
        'top': {'unit': 'percent', 'value': 5.0},
        'width': {'unit': 'percent', 'value': 30.5},
      });
    });

    test('defaults: weight 50, empty placement', () async {
      await RtmpBroadcastController()
          .addOverlay(DynamicOverlay(id: 'a', content: ImageContent(png)));
      final args = calls.single.arguments as Map;
      expect(args['weight'], 50);
      expect(args['placement'], isEmpty);
    });

    test('validates before calling native', () async {
      final c = RtmpBroadcastController();
      expect(() => c.addOverlay(DynamicOverlay(id: 'scoreband', content: ImageContent(png))),
          throwsCode('OVERLAY_ID_RESERVED'));
      expect(() => c.addOverlay(DynamicOverlay(id: 'sponsor_0', content: ImageContent(png))),
          throwsCode('OVERLAY_ID_RESERVED'));
      expect(() => c.addOverlay(DynamicOverlay(id: '', content: ImageContent(png))),
          throwsCode('OVERLAY_ID_RESERVED'));
      expect(() => c.addOverlay(DynamicOverlay(id: 'x' * 65, content: ImageContent(png))),
          throwsCode('OVERLAY_ID_RESERVED'));
      expect(() => c.addOverlay(DynamicOverlay(id: 'a', content: ImageContent(png), weight: 101)),
          throwsCode('OVERLAY_INVALID_PLACEMENT'));
      expect(() => c.addOverlay(DynamicOverlay(id: 'a', content: ImageContent(Uint8List(0)))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(
          () => c.addOverlay(DynamicOverlay(
              id: 'a',
              content: ImageContent(png),
              placement: const OverlayPlacement(width: OverlayLength.percent(120)))),
          throwsCode('OVERLAY_INVALID_PLACEMENT'));
      expect(
          () => c.addOverlay(DynamicOverlay(
              id: 'a',
              content: ImageContent(png),
              placement: const OverlayPlacement(left: OverlayLength.px(-1)))),
          throwsCode('OVERLAY_INVALID_PLACEMENT'));
      expect(calls, isEmpty);
    });

    test('native errors surface as RtmpBroadcasterException', () async {
      nextError = PlatformException(code: 'OVERLAY_ID_EXISTS', message: 'dup');
      expect(
          () => RtmpBroadcastController()
              .addOverlay(DynamicOverlay(id: 'a', content: ImageContent(png))),
          throwsCode('OVERLAY_ID_EXISTS'));
    });
  });

  group('updateOverlay', () {
    test('sends only provided fields', () async {
      await RtmpBroadcastController().updateOverlay('a', weight: 90);
      expect(calls.single.method, 'overlayUpdate');
      expect(calls.single.arguments, {'id': 'a', 'weight': 90});
    });

    test('sends content and placement', () async {
      await RtmpBroadcastController().updateOverlay('a',
          content: ImageContent(png),
          placement: const OverlayPlacement(bottom: OverlayLength.px(0)));
      expect(calls.single.arguments, {
        'id': 'a',
        'content': {'type': 'image', 'bytes': png},
        'placement': {
          'bottom': {'unit': 'px', 'value': 0.0}
        },
      });
    });
  });

  group('duration', () {
    test('addOverlay sends durationMs only when set', () async {
      final c = RtmpBroadcastController();
      await c.addOverlay(DynamicOverlay(
          id: 'a', content: ImageContent(png), duration: const Duration(seconds: 15)));
      await c.addOverlay(DynamicOverlay(id: 'b', content: ImageContent(png)));
      expect((calls[0].arguments as Map)['durationMs'], 15000);
      expect((calls[1].arguments as Map).containsKey('durationMs'), isFalse);
    });

    test('addOverlay rejects durations under 1 ms', () {
      final c = RtmpBroadcastController();
      expect(
          () => c.addOverlay(DynamicOverlay(
              id: 'a', content: ImageContent(png), duration: Duration.zero)),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(
          () => c.addOverlay(DynamicOverlay(
              id: 'a',
              content: ImageContent(png),
              duration: const Duration(microseconds: 999))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(calls, isEmpty);
    });

    test('updateOverlay duration modes and restartTimer', () async {
      final c = RtmpBroadcastController();
      await c.updateOverlay('a', duration: const OverlayDurationUpdate.keep());
      await c.updateOverlay('a', duration: const OverlayDurationUpdate.infinite());
      await c.updateOverlay('a',
          duration: const OverlayDurationUpdate.of(Duration(seconds: 3)),
          restartTimer: true);
      await c.updateOverlay('a', restartTimer: true);
      expect(calls[0].arguments, {'id': 'a'});
      expect(calls[1].arguments, {
        'id': 'a',
        'duration': {'ms': null},
      });
      expect(calls[2].arguments, {
        'id': 'a',
        'duration': {'ms': 3000},
        'restartTimer': true,
      });
      expect(calls[3].arguments, {'id': 'a', 'restartTimer': true});
      expect(
          () => c.updateOverlay('a',
              duration: const OverlayDurationUpdate.of(Duration.zero)),
          throwsCode('OVERLAY_INVALID_CONTENT'));
    });

    test('expired removal event parses', () {
      final s = RtmpStatus.fromMap(
          {'type': 'overlayRemoved', 'id': 'promo', 'reason': 'expired'});
      expect(s.type, RtmpStatusType.overlayRemoved);
      expect(s.reason, 'expired');
    });
  });

  test('hide / show / remove / clear map to channel methods', () async {
    final c = RtmpBroadcastController();
    await c.hideOverlay('a');
    await c.showOverlay('a');
    await c.removeOverlay('a');
    await c.clearOverlays();
    await c.removeOverlay('b', animate: false);
    await c.clearOverlays(animate: true);
    expect(calls.map((c) => c.method), [
      'overlayHide',
      'overlayShow',
      'overlayRemove',
      'overlayClear',
      'overlayRemove',
      'overlayClear'
    ]);
    expect(calls[0].arguments, {'id': 'a'});
    expect(calls[2].arguments, {'id': 'a', 'animate': true});
    expect(calls[3].arguments, {'animate': false});
    expect(calls[4].arguments, {'id': 'b', 'animate': false});
    expect(calls[5].arguments, {'animate': true});
  });

  group('content types', () {
    test('text sends style with ARGB ints and omits null keys', () async {
      await RtmpBroadcastController().addOverlay(DynamicOverlay(
        id: 't',
        content: const TextContent('GOAL!',
            style: TextOverlayStyle(
                fontSizePx: 48, color: Color(0xFFFF0000), background: Color(0x80000000))),
      ));
      expect((calls.single.arguments as Map)['content'], {
        'type': 'text',
        'text': 'GOAL!',
        'style': {
          'fontSizePx': 48.0,
          'color': 0xFFFF0000,
          'background': 0x80000000,
          'paddingPx': 8.0,
        },
      });
    });

    test('gif and ticker payloads', () async {
      final ttf = Uint8List.fromList([0, 1, 0, 0]);
      final c = RtmpBroadcastController();
      await c.addOverlay(DynamicOverlay(id: 'g', content: GifContent(png)));
      await c.addOverlay(DynamicOverlay(
        id: 'news',
        content: TickerContent('Breaking news',
            style: TextOverlayStyle(fontTtf: ttf),
            cycleDuration: const Duration(seconds: 8),
            loop: false,
            loopGap: const OverlayLength.px(40),
            direction: TickerDirection.rtl),
      ));
      expect((calls[0].arguments as Map)['content'], {'type': 'gif', 'bytes': png});
      expect((calls[1].arguments as Map)['content'], {
        'type': 'ticker',
        'text': 'Breaking news',
        'style': {'fontSizePx': 32.0, 'color': 0xFFFFFFFF, 'paddingPx': 8.0, 'fontTtf': ttf},
        'cycleDurationMs': 8000,
        'loop': false,
        'loopGap': {'unit': 'px', 'value': 40.0},
        'direction': 'rtl',
      });
    });

    test('ticker default style has a 70% black band', () {
      final map = const TickerContent('x').toMap();
      expect(map['style'], {
        'fontSizePx': 32.0,
        'color': 0xFFFFFFFF,
        'background': 0xB3000000,
        'paddingPx': 8.0,
      });
      expect(map['loop'], true);
      expect(map['direction'], 'auto');
      expect(map.containsKey('speedPxPerSec'), isFalse);
    });

    test('content validation', () {
      final c = RtmpBroadcastController();
      Future<void> add(OverlayContent content) =>
          c.addOverlay(DynamicOverlay(id: 'x', content: content));
      expect(() => add(GifContent(Uint8List(0))), throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(const TextContent(' \n ')), throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(const TextContent('a', style: TextOverlayStyle(fontSizePx: 0))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(const TextContent('a', style: TextOverlayStyle(paddingPx: -1))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(TextContent('a', style: TextOverlayStyle(fontTtf: Uint8List(0)))),
          throwsCode('OVERLAY_FONT_INVALID'));
      expect(
          () => add(const TickerContent('a',
              speedPxPerSec: 10, cycleDuration: Duration(seconds: 1))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(const TickerContent('a', speedPxPerSec: 0)),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(const TickerContent('a', cycleDuration: Duration.zero)),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(() => add(const TickerContent('a', loopGap: OverlayLength.percent(101))),
          throwsCode('OVERLAY_INVALID_PLACEMENT'));
      expect(calls, isEmpty);
    });
  });

  group('wrapped text + copyWith', () {
    test('maxLines and align are sent only when not default', () {
      expect(const TextOverlayStyle().toMap().containsKey('maxLines'), isFalse);
      expect(const TextOverlayStyle().toMap().containsKey('align'), isFalse);
      final map = const TextOverlayStyle(maxLines: 4, align: TextOverlayAlign.center).toMap();
      expect(map['maxLines'], 4);
      expect(map['align'], 'center');
      expect(() => RtmpBroadcastController().addOverlay(DynamicOverlay(
              id: 'w', content: const TextContent('x', style: TextOverlayStyle(maxLines: 0)))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
    });

    test('copyWith keeps untouched fields', () {
      const style = TextOverlayStyle(fontSizePx: 40, background: Color(0xFF000000), maxLines: 3);
      final bigger = style.copyWith(fontSizePx: 60);
      expect(bigger.fontSizePx, 60);
      expect(bigger.background, const Color(0xFF000000));
      expect(bigger.maxLines, 3);

      const ticker = TickerContent('a', speedPxPerSec: 90, loop: false, direction: TickerDirection.ltr);
      final t2 = ticker.copyWith(text: 'b');
      expect([t2.text, t2.speedPxPerSec, t2.loop, t2.direction], ['b', 90, false, TickerDirection.ltr]);
      expect(const TextContent('a', style: style).copyWith(text: 'b').style.maxLines, 3);

      const placement = OverlayPlacement(left: OverlayLength.px(10), width: OverlayLength.percent(20));
      final wider = placement.copyWith(width: const OverlayLength.percent(30));
      expect(wider.toMap(), {
        'left': {'unit': 'px', 'value': 10.0},
        'width': {'unit': 'percent', 'value': 30.0},
      });
    });
  });

  group('animations', () {
    test('enter/exit default to none and are always sent', () async {
      await RtmpBroadcastController()
          .addOverlay(DynamicOverlay(id: 'a', content: ImageContent(png)));
      final args = calls.single.arguments as Map;
      const none = {'type': 'none', 'durationMs': 400, 'easing': 'easeOut', 'edge': 'bottom'};
      expect(args['enter'], none);
      expect(args['exit'], none);
    });

    test('named constructors map to wire values', () {
      expect(const OverlayAnimation.slide(edge: OverlayEdge.left, durationMs: 250).toMap(),
          {'type': 'slide', 'durationMs': 250, 'easing': 'easeOut', 'edge': 'left'});
      expect(const OverlayAnimation.pop(easing: OverlayEasing.easeInOut).toMap()['easing'],
          'easeInOut');
      expect(const OverlayAnimation.curtain().toMap()['type'], 'curtain');
    });

    test('duration range is validated', () {
      final c = RtmpBroadcastController();
      expect(
          () => c.addOverlay(DynamicOverlay(
              id: 'a', content: ImageContent(png), enter: const OverlayAnimation.pop(durationMs: 5001))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(
          () => c.addOverlay(DynamicOverlay(
              id: 'a', content: ImageContent(png), exit: const OverlayAnimation.slide(durationMs: -1))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(calls, isEmpty);
    });

    test('completed removal event parses', () {
      final s = RtmpStatus.fromMap({'type': 'overlayRemoved', 'id': 'news', 'reason': 'completed'});
      expect(s.reason, 'completed');
    });
  });

  group('carousel content', () {
    final gif = Uint8List.fromList([71, 73, 70]);

    test('sends full wire payload', () async {
      await RtmpBroadcastController().addOverlay(DynamicOverlay(
        id: 'sponsors',
        content: CarouselContent(
          [CarouselItem(ImageContent(png)), CarouselItem(GifContent(gif), interval: const Duration(seconds: 10))],
          interval: const Duration(seconds: 4),
          transition: const CarouselTransition.push(edge: OverlayEdge.top, durationMs: 600, easing: OverlayEasing.linear),
        ),
      ));
      final args = calls.single.arguments as Map;
      expect(args['content'], {
        'type': 'carousel',
        'intervalMs': 4000,
        'items': [
          {'content': {'type': 'image', 'bytes': png}},
          {'content': {'type': 'gif', 'bytes': gif}, 'intervalMs': 10000},
        ],
        'transition': {'type': 'push', 'durationMs': 600, 'easing': 'linear', 'edge': 'top'},
      });
    });

    test('defaults: 5 s interval, 500 ms crossfade', () {
      final map = CarouselContent([CarouselItem(ImageContent(png))]).toMap();
      expect(map['intervalMs'], 5000);
      expect(map['transition'], {'type': 'crossfade', 'durationMs': 500, 'easing': 'easeInOut', 'edge': 'right'});
      expect(const CarouselTransition.cut().toMap()['type'], 'cut');
    });

    test('update sends carousel content', () async {
      await RtmpBroadcastController()
          .updateOverlay('sponsors', content: CarouselContent([CarouselItem(ImageContent(png))]));
      final args = calls.single.arguments as Map;
      expect((args['content'] as Map)['type'], 'carousel');
    });

    test('validation mirrors native', () {
      final controller = RtmpBroadcastController();
      Future<void> add(CarouselContent c) => controller.addOverlay(DynamicOverlay(id: 'c', content: c));
      final one = CarouselItem(ImageContent(png));

      expect(add(const CarouselContent([])), throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(add(CarouselContent(List.filled(21, one))), throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(add(const CarouselContent([CarouselItem(TextContent('x'))])), throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(add(CarouselContent([CarouselItem(ImageContent(Uint8List(0)))])), throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(add(CarouselContent([one], interval: const Duration(milliseconds: 499))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(add(CarouselContent([CarouselItem(ImageContent(png), interval: const Duration(milliseconds: 100))])),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(add(CarouselContent([one], transition: const CarouselTransition.crossfade(durationMs: 5001))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(
          add(CarouselContent([one, CarouselItem(ImageContent(png), interval: const Duration(milliseconds: 600))],
              transition: const CarouselTransition.crossfade(durationMs: 600))),
          throwsCode('OVERLAY_INVALID_CONTENT'));
      expect(calls, isEmpty);
    });

    test('cut and single item ignore transition length', () async {
      final controller = RtmpBroadcastController();
      final short = CarouselItem(ImageContent(png), interval: const Duration(milliseconds: 600));
      await controller.addOverlay(DynamicOverlay(
          id: 'a',
          content: CarouselContent([short, short],
              transition: const CarouselTransition(type: CarouselTransitionType.cut, durationMs: 600))));
      await controller.addOverlay(DynamicOverlay(
          id: 'b', content: CarouselContent([short], transition: const CarouselTransition.crossfade(durationMs: 600))));
      expect(calls, hasLength(2));
    });
  });

  group('legacy weight', () {
    test('updateScoreband sends weight (default 50)', () async {
      final c = RtmpBroadcastController();
      await c.updateScoreband(png);
      await c.updateScoreband(png, weight: 80);
      expect((calls[0].arguments as Map)['weight'], 50);
      expect((calls[1].arguments as Map)['weight'], 80);
      expect(() => c.updateScoreband(png, weight: -1),
          throwsCode('OVERLAY_INVALID_PLACEMENT'));
    });

    test('SponsorPlacement sends weight (default 10)', () {
      expect(const SponsorPlacement(width: 10, height: 10).toMap()['weight'], 10);
      expect(const SponsorPlacement(width: 10, height: 10, weight: 60).toMap()['weight'], 60);
    });
  });

  group('overlay events', () {
    test('parse type, id and reason', () {
      final shown = RtmpStatus.fromMap({'type': 'overlayShown', 'id': 'a'});
      expect(shown.type, RtmpStatusType.overlayShown);
      expect(shown.overlayId, 'a');
      final hidden = RtmpStatus.fromMap({'type': 'overlayHidden', 'id': 'a'});
      expect(hidden.type, RtmpStatusType.overlayHidden);
      final removed =
          RtmpStatus.fromMap({'type': 'overlayRemoved', 'id': 'a', 'reason': 'cleared'});
      expect(removed.type, RtmpStatusType.overlayRemoved);
      expect(removed.reason, 'cleared');
      final warn = RtmpStatus.fromMap(
          {'type': 'warning', 'code': 'OVERLAY_DOWNSCALED', 'message': 'm', 'id': 'big'});
      expect(warn.errorCode, 'OVERLAY_DOWNSCALED');
      expect(warn.overlayId, 'big');
    });
  });
}

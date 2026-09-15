import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:flutter_rtmp_broadcaster_example/overlay_studio/build_tab.dart';
import 'package:flutter_rtmp_broadcaster_example/overlay_studio/overlay_studio.dart';
import 'package:flutter_rtmp_broadcaster_example/overlay_studio/overlay_studio_sheet.dart';
import 'package:flutter_rtmp_broadcaster_example/overlay_studio/studio_scenarios.dart';
import 'package:flutter_rtmp_broadcaster_example/overlay_studio/stream_hud.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const control = MethodChannel('flutter_rtmp_broadcaster/control');
  const statusName = 'flutter_rtmp_broadcaster/status';
  final messenger = TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger;
  late List<MethodCall> calls;
  late StreamController<Map<String, dynamic>> events;

  setUp(() {
    calls = [];
    events = StreamController.broadcast();
    messenger.setMockMethodCallHandler(control, (call) async {
      calls.add(call);
      if (call.method == 'overlayHide' && (call.arguments as Map)['id'] == 'ghost') {
        throw PlatformException(code: 'OVERLAY_NOT_FOUND', message: 'nope');
      }
      return null;
    });
    // Fake native event channel: forward whatever the test pushes into `events`.
    StreamSubscription<Map<String, dynamic>>? sub;
    messenger.setMockMessageHandler(statusName, (message) async {
      final call = const StandardMethodCodec().decodeMethodCall(message);
      if (call.method == 'listen') {
        sub = events.stream.listen((e) => messenger.handlePlatformMessage(
            statusName, const StandardMethodCodec().encodeSuccessEnvelope(e), (_) {}));
      } else if (call.method == 'cancel') {
        await sub?.cancel();
      }
      return const StandardMethodCodec().encodeSuccessEnvelope(null);
    });
  });

  tearDown(() {
    messenger.setMockMethodCallHandler(control, null);
    messenger.setMockMessageHandler(statusName, null);
    events.close();
  });

  DynamicOverlay badge(String id) => DynamicOverlay(id: id, content: ImageContent(Uint8List.fromList([1, 2, 3])));

  test('studio tracks overlay state from native events', () async {
    final studio = OverlayStudio(RtmpBroadcastController(), onMessage: (_) {}, scorebandWeight: 50);
    await Future<void>.delayed(Duration.zero);

    expect(await studio.add(badge('a'), label: 'A', kind: OverlayKind.image), isTrue);
    expect(studio.overlays['a']!.state, 'adding');
    events.add({'type': 'overlayShown', 'id': 'a'});
    await Future<void>.delayed(Duration.zero);
    expect(studio.overlays['a']!.state, 'visible');

    events.add({'type': 'connected'});
    await Future<void>.delayed(Duration.zero);
    expect(studio.phase, StreamPhase.live);

    await studio.remove(studio.overlays['a']!);
    expect(calls.last.arguments, {'id': 'a', 'animate': true});
    expect(studio.overlays['a']!.state, 'removing');

    // Re-add while the old one animates out: its late overlayRemoved must not drop the new entry.
    await studio.add(badge('a'), label: 'A again', kind: OverlayKind.image);
    events.add({'type': 'overlayRemoved', 'id': 'a', 'reason': 'removed'});
    await Future<void>.delayed(Duration.zero);
    expect(studio.overlays['a']!.label, 'A again');
    events.add({'type': 'overlayRemoved', 'id': 'a', 'reason': 'expired'});
    await Future<void>.delayed(Duration.zero);
    expect(studio.overlays, isEmpty);

    expect(await studio.run('hide ghost', () => studio.controller.hideOverlay('ghost')), isFalse);
    expect(studio.events.first.isError, isTrue);
    expect(studio.events.first.message, contains('OVERLAY_NOT_FOUND'));
    studio.dispose();
  });

  testWidgets('studio sheet renders all tabs and the HUD', (tester) async {
    await tester.binding.setSurfaceSize(const Size(900, 1600));
    final studio = OverlayStudio(RtmpBroadcastController(), onMessage: (_) {}, scorebandWeight: 50);
    final demo = AutoDemo(studio);
    studio.overlays['t'] = StudioOverlay(id: 't', label: 'Ticker', kind: OverlayKind.ticker, weight: 45)..state = 'visible';
    studio.log('hello');

    await tester.pumpWidget(MaterialApp(
      home: Scaffold(
        body: Stack(children: [
          Positioned(top: 10, left: 10, child: StreamHud(studio: studio)),
          Builder(
            builder: (context) => Center(
              child: ElevatedButton(onPressed: () => showOverlayStudio(context, studio, demo), child: const Text('open')),
            ),
          ),
        ]),
      ),
    ));
    expect(find.text('OFFLINE · timers paused'), findsOneWidget);
    expect(find.text('Overlays 1'), findsOneWidget);

    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
    expect(find.text('Wicket!'), findsOneWidget);

    await tester.tap(find.text('Build'));
    await tester.pumpAndSettle();
    final buildScroll = find.descendant(of: find.byType(BuildTab), matching: find.byType(Scrollable)).first;
    await tester.tap(find.widgetWithText(ChoiceChip, 'ticker'));
    await tester.pumpAndSettle();
    await tester.scrollUntilVisible(find.text('Speed by cycle duration'), 200, scrollable: buildScroll);
    expect(find.text('Speed by cycle duration'), findsOneWidget);
    await tester.scrollUntilVisible(find.text('Add overlay'), 300, scrollable: buildScroll);
    expect(find.text('Add overlay'), findsOneWidget);

    await tester.tap(find.text('Active (1)'));
    await tester.pumpAndSettle();
    expect(find.textContaining('Scoreband weight 50'), findsOneWidget);
    expect(find.textContaining('t  ·  Ticker'), findsOneWidget);

    await tester.ensureVisible(find.byTooltip('Hide'));
    await tester.pumpAndSettle();
    await tester.tap(find.byTooltip('Hide'));
    await tester.pumpAndSettle();
    expect(calls.last.method, 'overlayHide');

    await tester.tap(find.textContaining('Log ('));
    await tester.pumpAndSettle();
    expect(find.textContaining('hello'), findsOneWidget);

    await tester.tap(find.byTooltip('Expand'));
    await tester.pumpAndSettle();
    expect(find.byTooltip('Shrink'), findsOneWidget);

    demo.stop();
    studio.dispose();
    await tester.binding.setSurfaceSize(null);
  });
}

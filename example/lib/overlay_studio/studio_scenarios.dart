import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

import 'mock_match.dart';
import 'overlay_samples.dart';
import 'overlay_studio.dart';

/// One tappable test in the Scenarios tab.
class StudioScenario {
  const StudioScenario(this.group, this.title, this.tests, this.run);

  final String group;
  final String title;

  /// What to look for on the stream.
  final String tests;
  final Future<void> Function(OverlayStudio s) run;
}

TextOverlayStyle _banner(Color bg, {double size = 44}) =>
    TextOverlayStyle(fontSizePx: size, background: bg, paddingPx: 14);

Future<void> _wait(int ms) => Future.delayed(Duration(milliseconds: ms));

/// Fixed ids for overlays that scenarios update in place.
const scoreTextId = 'score_text';
const tickerEnId = 'ticker_en';
const widgetBandId = 'widget_band';

/// Captures the Go Live screen's scoreband widget as PNG, logging why it can't.
Future<Uint8List?> _captureBand(OverlayStudio s, {bool advance = false}) async {
  final capture = s.captureBand;
  if (capture == null) {
    s.log('no capture hook — open the studio from the Go Live screen', isError: true);
    return null;
  }
  final png = await capture(advance: advance);
  if (png == null) s.log('scoreband widget is not on screen yet', isError: true);
  return png;
}

final List<StudioScenario> studioScenarios = [
  // ---- match moments (mock data) -------------------------------------------------------------
  StudioScenario('Match moments', 'Wicket!', 'Text · pop in/out · 6 s live duration · weight 70', (s) async {
    await s.add(
      DynamicOverlay(
        id: s.nextId('wicket'),
        content: TextContent(s.match.wicketText(), style: _banner(const Color(0xE6C62828))),
        placement: const OverlayPlacement(top: OverlayLength.percent(30), width: OverlayLength.percent(92)),
        weight: 70,
        duration: const Duration(seconds: 6),
        enter: const OverlayAnimation.pop(durationMs: 350),
        exit: const OverlayAnimation.pop(durationMs: 250, easing: OverlayEasing.easeIn),
      ),
      label: 'Wicket banner',
      kind: OverlayKind.text,
    );
  }),
  StudioScenario('Match moments', 'Boundary 4 / 6', 'GIF + image together · slide from both edges · 5 s', (s) async {
    final six = Random().nextBool();
    await s.add(
      DynamicOverlay(
        id: s.nextId('boundary_gif'),
        content: GifContent(await OverlaySamples.spinnerGif()),
        placement: const OverlayPlacement(right: OverlayLength.px(24), top: OverlayLength.percent(42), width: OverlayLength.percent(16)),
        weight: 65,
        duration: const Duration(seconds: 5),
        enter: const OverlayAnimation.slide(edge: OverlayEdge.right),
        exit: const OverlayAnimation.slide(edge: OverlayEdge.right),
      ),
      label: 'Boundary GIF',
      kind: OverlayKind.gif,
    );
    await s.add(
      DynamicOverlay(
        id: s.nextId('boundary_badge'),
        content: ImageContent(await OverlaySamples.badgePng(six ? 'SIX!' : 'FOUR!', six ? Colors.purple : Colors.orange)),
        placement: const OverlayPlacement(left: OverlayLength.px(24), top: OverlayLength.percent(42), width: OverlayLength.percent(40)),
        weight: 65,
        duration: const Duration(seconds: 5),
        enter: const OverlayAnimation.slide(edge: OverlayEdge.left),
        exit: const OverlayAnimation.slide(edge: OverlayEdge.left),
      ),
      label: six ? 'SIX badge' : 'FOUR badge',
      kind: OverlayKind.image,
    );
  }),
  StudioScenario('Match moments', 'Sponsor break', 'Image · slide in from right, curtain out · 10 s · top-right', (s) async {
    final (name, color) = s.match.pick(MockMatch.sponsors);
    await s.add(
      DynamicOverlay(
        id: s.nextId('sponsor_break'),
        content: ImageContent(await OverlaySamples.badgePng('Powered by $name', color, w: 480, h: 120)),
        placement: const OverlayPlacement(right: OverlayLength.px(24), top: OverlayLength.percent(12), width: OverlayLength.percent(45)),
        weight: 60,
        duration: const Duration(seconds: 10),
        enter: const OverlayAnimation.slide(edge: OverlayEdge.right, durationMs: 500),
        exit: const OverlayAnimation.curtain(durationMs: 400),
      ),
      label: 'Sponsor $name',
      kind: OverlayKind.image,
    );
  }),
  StudioScenario('Match moments', 'Player lower third', 'Text · curtain in, slide out left · 8 s · above scoreband', (s) async {
    await s.add(
      DynamicOverlay(
        id: s.nextId('lower_third'),
        content: TextContent(s.match.playerCard(), style: _banner(const Color(0xDD0D47A1), size: 36)),
        placement: const OverlayPlacement(left: OverlayLength.px(24), bottom: OverlayLength.percent(24)),
        weight: 55,
        duration: const Duration(seconds: 8),
        enter: const OverlayAnimation.curtain(durationMs: 500),
        exit: const OverlayAnimation.slide(edge: OverlayEdge.left),
      ),
      label: 'Lower third',
      kind: OverlayKind.text,
    );
  }),
  StudioScenario('Match moments', 'Next ball (live score)',
      'Updates one text overlay in place (instant swap, no re-animation); a wicket also fires the Wicket banner', (s) async {
    final ball = s.match.nextBall();
    final content = TextContent(s.match.scoreLine, style: _banner(const Color(0xCC000000), size: 32));
    final existing = s.overlays[scoreTextId];
    if (existing != null) {
      await s.update(existing, s.match.scoreLine, content: content);
    } else {
      await s.add(
        DynamicOverlay(
          id: scoreTextId,
          content: content,
          placement: const OverlayPlacement(left: OverlayLength.px(16), top: OverlayLength.percent(5)),
          weight: 75,
          enter: const OverlayAnimation.slide(edge: OverlayEdge.top),
          exit: const OverlayAnimation.slide(edge: OverlayEdge.top),
        ),
        label: 'Live score text',
        kind: OverlayKind.text,
      );
    }
    if (ball < 0) await studioScenarios.first.run(s);
  }),

  // ---- tickers ---------------------------------------------------------------------------------
  StudioScenario('Tickers', 'News ticker (loop)',
      'Full-width band, loops; tap again = next headline (scroll resets). Frozen until live, pauses when hidden', (s) async {
    final text = s.match.pick(MockMatch.headlinesEnglish);
    final content = TickerContent(text);
    final existing = s.overlays[tickerEnId];
    if (existing != null) {
      await s.update(existing, 'next headline', content: content);
    } else {
      await s.add(
        DynamicOverlay(
          id: tickerEnId,
          content: content,
          placement: const OverlayPlacement(bottom: OverlayLength.percent(20)),
          weight: 45,
          enter: const OverlayAnimation.curtain(),
          exit: const OverlayAnimation.curtain(),
        ),
        label: 'English ticker (loop)',
        kind: OverlayKind.ticker,
      );
    }
  }),
  StudioScenario('Tickers', 'Restyle news ticker', 'Same text, bigger font + gold band → scroll position kept (rescaled)', (s) async {
    final existing = s.overlays[tickerEnId];
    if (existing == null) {
      s.log('add "News ticker (loop)" first', isError: true);
      return;
    }
    await s.update(existing, 'restyle (same text)',
        content: TickerContent(existing.text ?? MockMatch.headlinesEnglish.first,
            style: const TextOverlayStyle(fontSizePx: 48, color: Colors.black, background: Color(0xE6FFC107))));
  }),
  StudioScenario('Tickers', 'বাংলা ticker — one pass', 'loop: false · 160 px/s → removed with reason completed', (s) async {
    await s.add(
      DynamicOverlay(
        id: s.nextId('ticker_bn'),
        content: const TickerContent(MockMatch.headlineBangla, speedPxPerSec: 160, loop: false),
        placement: const OverlayPlacement(top: OverlayLength.percent(22)),
        weight: 46,
      ),
      label: 'Bangla ticker (once)',
      kind: OverlayKind.ticker,
    );
  }),
  StudioScenario('Tickers', 'Arabic ticker — 15 s', 'Auto direction (moves left→right) · 15 s duration → finishes the pass, then expired', (s) async {
    await s.add(
      DynamicOverlay(
        id: s.nextId('ticker_ar'),
        content: const TickerContent(MockMatch.headlineArabic,
            cycleDuration: Duration(seconds: 6), loopGap: OverlayLength.percent(10)),
        placement: const OverlayPlacement(top: OverlayLength.percent(30), width: OverlayLength.percent(80)),
        weight: 47,
        duration: const Duration(seconds: 15),
        enter: const OverlayAnimation.pop(),
        exit: const OverlayAnimation.pop(),
      ),
      label: 'Arabic ticker (15 s)',
      kind: OverlayKind.ticker,
    );
  }),

  // ---- sponsor carousels -----------------------------------------------------------------------
  StudioScenario('Carousels', 'Sponsor carousel (crossfade)',
      '4 sponsor logos · 4 s each · 600 ms crossfade · top-left · rotates before Go Live too', (s) async {
    await s.add(
      DynamicOverlay(
        id: s.nextId('carousel_fade'),
        content: CarouselContent(
          await OverlaySamples.sponsorItems(),
          interval: const Duration(seconds: 4),
          transition: const CarouselTransition.crossfade(durationMs: 600),
        ),
        placement: const OverlayPlacement(
          left: OverlayLength.px(24),
          top: OverlayLength.px(24),
          width: OverlayLength.percent(22),
          height: OverlayLength.percent(10),
        ),
        weight: 40,
        enter: const OverlayAnimation.slide(edge: OverlayEdge.left),
        exit: const OverlayAnimation.slide(edge: OverlayEdge.left),
      ),
      label: 'Carousel · crossfade',
      kind: OverlayKind.carousel,
    );
  }),
  StudioScenario('Carousels', 'Push carousel (from bottom)',
      '4 logos · 3 s · push up from the bottom edge, clipped to the slot · bottom-right', (s) async {
    await s.add(
      DynamicOverlay(
        id: s.nextId('carousel_push'),
        content: CarouselContent(
          await OverlaySamples.sponsorItems(),
          interval: const Duration(seconds: 3),
          transition: const CarouselTransition.push(edge: OverlayEdge.bottom, durationMs: 450),
        ),
        placement: const OverlayPlacement(
          right: OverlayLength.px(24),
          bottom: OverlayLength.percent(22),
          width: OverlayLength.percent(22),
          height: OverlayLength.percent(10),
        ),
        weight: 40,
        enter: const OverlayAnimation.pop(),
        exit: const OverlayAnimation.pop(),
      ),
      label: 'Carousel · push',
      kind: OverlayKind.carousel,
    );
  }),
  StudioScenario('Carousels', 'Title sponsor 10 s + GIF · cut',
      'First logo stays 10 s, others 2 s, spinner GIF item animates · hard cuts · top-right', (s) async {
    final items = await OverlaySamples.sponsorItems(firstInterval: const Duration(seconds: 10));
    await s.add(
      DynamicOverlay(
        id: s.nextId('carousel_cut'),
        content: CarouselContent(
          [...items, CarouselItem(GifContent(await OverlaySamples.spinnerGif()))],
          interval: const Duration(seconds: 2),
          transition: const CarouselTransition.cut(),
        ),
        placement: const OverlayPlacement(right: OverlayLength.px(24), top: OverlayLength.px(24), height: OverlayLength.percent(12)),
        weight: 40,
      ),
      label: 'Carousel · title sponsor + GIF',
      kind: OverlayKind.carousel,
    );
  }),

  // ---- behavior tests --------------------------------------------------------------------------
  StudioScenario('Behavior tests', 'Interrupt: reverse',
      'Slow pop (1.2 s): hide mid-enter → reverses; show mid-exit → reverses; remove; re-add same id while exiting', (s) async {
    const id = 'interrupt_reverse';
    Future<DynamicOverlay> make(Color c) async => DynamicOverlay(
          id: id,
          content: ImageContent(await OverlaySamples.badgePng('REVERSE', c)),
          placement: const OverlayPlacement(width: OverlayLength.percent(40)),
          weight: 80,
          enter: const OverlayAnimation.pop(durationMs: 1200, easing: OverlayEasing.linear),
          exit: const OverlayAnimation.pop(durationMs: 1200, easing: OverlayEasing.linear),
        );
    if (!await s.add(await make(Colors.teal), label: 'Interrupt (reverse)', kind: OverlayKind.image)) return;
    await _wait(500);
    await s.run('hide $id mid-enter (expect reverse, no overlayShown)', () => s.controller.hideOverlay(id));
    await _wait(300);
    await s.run('show $id mid-exit (expect reverse, no overlayHidden)', () => s.controller.showOverlay(id));
    await _wait(1800);
    s.markRemoving(id);
    await s.run('remove $id (animated)', () => s.controller.removeOverlay(id));
    await _wait(400);
    await s.add(await make(Colors.pink), label: 'Interrupt (re-added)', kind: OverlayKind.image);
    await _wait(3000);
    await s.run('remove $id instantly', () => s.controller.removeOverlay(id, animate: false));
  }),
  StudioScenario('Behavior tests', 'Interrupt: snap',
      'Slide in (1.2 s), curtain out: hide mid-enter → snaps visible then curtain closes; show again; remove', (s) async {
    const id = 'interrupt_snap';
    final ok = await s.add(
      DynamicOverlay(
        id: id,
        content: ImageContent(await OverlaySamples.badgePng('SNAP', Colors.indigo)),
        placement: const OverlayPlacement(width: OverlayLength.percent(40), bottom: OverlayLength.percent(40)),
        weight: 80,
        enter: const OverlayAnimation.slide(edge: OverlayEdge.left, durationMs: 1200),
        exit: const OverlayAnimation.curtain(durationMs: 800),
      ),
      label: 'Interrupt (snap)',
      kind: OverlayKind.image,
    );
    if (!ok) return;
    await _wait(500);
    await s.run('hide $id mid-enter (expect overlayShown then overlayHidden)', () => s.controller.hideOverlay(id));
    await _wait(1500);
    await s.run('show $id', () => s.controller.showOverlay(id));
    await _wait(2500);
    s.markRemoving(id);
    await s.run('remove $id', () => s.controller.removeOverlay(id));
  }),
  StudioScenario('Behavior tests', 'Layer order',
      'Three overlapping squares at weights 5 (behind sponsors), 50, 95; after 4 s the back one jumps to 100', (s) async {
    const weights = [5, 50, 95];
    const colors = [Colors.red, Colors.green, Colors.blue];
    const offsets = [0.0, 6.0, 12.0];
    final ids = <String>[];
    for (var i = 0; i < 3; i++) {
      final id = s.nextId('layer_w${weights[i]}');
      ids.add(id);
      await s.add(
        DynamicOverlay(
          id: id,
          content: ImageContent(await OverlaySamples.badgePng('w${weights[i]}', colors[i], w: 200, h: 200)),
          placement: OverlayPlacement(left: OverlayLength.percent(20 + offsets[i]), top: OverlayLength.percent(4 + offsets[i] / 2), width: const OverlayLength.percent(35)),
          weight: weights[i],
          duration: const Duration(seconds: 15),
        ),
        label: 'Layer weight ${weights[i]}',
        kind: OverlayKind.image,
      );
    }
    await _wait(4000);
    final back = s.overlays[ids.first];
    if (back != null) await s.setWeight(back, 100);
  }),
  StudioScenario('Behavior tests', 'Fill to limit (16)', 'Adds 17 small badges → the 17th fails with OVERLAY_LIMIT_REACHED', (s) async {
    final png = await OverlaySamples.badgePng('#', Colors.blueGrey, w: 96, h: 96);
    for (var i = 0; i < 17; i++) {
      final ok = await s.add(
        DynamicOverlay(
          id: s.nextId('limit'),
          content: ImageContent(png),
          placement: OverlayPlacement(left: OverlayLength.percent((i % 6) * 16.0), top: OverlayLength.percent(50 + (i ~/ 6) * 8.0), width: const OverlayLength.percent(12)),
        ),
        label: 'Limit badge ${i + 1}',
        kind: OverlayKind.image,
      );
      if (!ok) break;
    }
  }),
  StudioScenario('Behavior tests', 'Error codes', 'Each call below must fail with its code (see Log tab)', (s) async {
    final png = await OverlaySamples.badgePng('x', Colors.grey);
    Future<void> expect(String code, Future<void> Function() op) async {
      try {
        await op();
        s.log('✗ expected $code but call succeeded', isError: true);
      } on RtmpBroadcasterException catch (e) {
        s.log('${e.code == code ? '✓' : '✗'} expected $code, got ${e.code}', isError: e.code != code);
      }
    }

    final c = s.controller;
    await expect('OVERLAY_ID_RESERVED', () => c.addOverlay(DynamicOverlay(id: 'scoreband', content: ImageContent(png))));
    await expect('OVERLAY_INVALID_PLACEMENT',
        () => c.addOverlay(DynamicOverlay(id: 'bad_place', content: ImageContent(png), weight: 101)));
    await expect('OVERLAY_INVALID_CONTENT',
        () => c.addOverlay(DynamicOverlay(id: 'bad_anim', content: ImageContent(png), enter: const OverlayAnimation.pop(durationMs: 9000))));
    await expect('OVERLAY_NOT_FOUND', () => c.hideOverlay('ghost_overlay'));
    await expect('OVERLAY_DECODE_FAILED',
        () => c.addOverlay(DynamicOverlay(id: 'bad_png', content: ImageContent(OverlaySamples.garbage()))));
    await expect('OVERLAY_GIF_TOO_LARGE',
        () => c.addOverlay(DynamicOverlay(id: 'big_gif', content: GifContent(OverlaySamples.gifWithFrames(151)))));
    await expect('OVERLAY_DECODE_FAILED', () => c.addOverlay(DynamicOverlay(
        id: 'bad_carousel',
        content: CarouselContent([CarouselItem(ImageContent(png)), CarouselItem(ImageContent(OverlaySamples.garbage()))]))));
    await expect('OVERLAY_INVALID_CONTENT', () => c.addOverlay(DynamicOverlay(
        id: 'carousel_21', content: CarouselContent(List.filled(21, CarouselItem(ImageContent(png)))))));
    await expect('OVERLAY_FONT_INVALID', () => c.addOverlay(DynamicOverlay(
        id: 'bad_font', content: TextContent('font', style: TextOverlayStyle(fontTtf: OverlaySamples.garbage())))));
    await c.addOverlay(DynamicOverlay(id: 'dup_check', content: ImageContent(png), placement: const OverlayPlacement(width: OverlayLength.percent(1))))
        .catchError((_) {});
    await expect('OVERLAY_ID_EXISTS', () => c.addOverlay(DynamicOverlay(id: 'dup_check', content: ImageContent(png))));
    await c.removeOverlay('dup_check', animate: false).catchError((_) {});
    s.log('error-code check done');
  }),

  // ---- widget capture (the scoreband pattern, as a dynamic overlay) ---------------------------
  StudioScenario('Widget capture', 'Scoreband widget → dynamic overlay',
      'Captures the real ScoreBandView → ImageContent · slides up from the bottom · weight 55', (s) async {
    final png = await _captureBand(s);
    if (png == null) return;
    // Re-running replaces the previous one; the studio skips the late overlayRemoved.
    final existing = s.overlays[widgetBandId];
    if (existing != null) await s.remove(existing);
    await s.add(
      DynamicOverlay(
        id: widgetBandId,
        content: ImageContent(png),
        placement: const OverlayPlacement(bottom: OverlayLength.percent(6), width: OverlayLength.percent(90)),
        weight: 55,
        enter: const OverlayAnimation.slide(edge: OverlayEdge.bottom, durationMs: 450),
        exit: const OverlayAnimation.slide(edge: OverlayEdge.bottom, durationMs: 300),
      ),
      label: 'Captured scoreband',
      kind: OverlayKind.image,
    );
  }),
  StudioScenario('Widget capture', 'Re-capture → update in place',
      'Moves the score on, re-captures, swaps the image — no re-add, no re-animation', (s) async {
    final o = s.overlays[widgetBandId];
    if (o == null) {
      s.log('run "Scoreband widget → dynamic overlay" first', isError: true);
      return;
    }
    final png = await _captureBand(s, advance: true);
    if (png == null) return;
    await s.update(o, 'widget re-capture', content: ImageContent(png));
  }),
];

/// Runs a random match moment every [interval] while enabled — hands-free demo during a stream.
class AutoDemo {
  AutoDemo(this.studio);

  final OverlayStudio studio;
  Timer? _timer;
  final _rng = Random();

  bool get running => _timer != null;

  void start({Duration interval = const Duration(seconds: 7)}) {
    stop();
    studio.log('auto demo started');
    _timer = Timer.periodic(interval, (_) {
      if (studio.isDisposed) return stop();
      final moments = studioScenarios.where((x) => x.group == 'Match moments').toList();
      moments[_rng.nextInt(moments.length)].run(studio);
    });
  }

  void stop() {
    if (_timer == null) return;
    _timer?.cancel();
    _timer = null;
    studio.log('auto demo stopped');
  }
}

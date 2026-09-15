import 'dart:typed_data';
import 'dart:ui' show Color;

import 'rtmp_broadcaster_exception.dart';

/// A length along one frame axis of the **post-rotation** stream frame
/// (what viewers see).
///
/// - [OverlayLength.percent]: 0–100 % of the frame width (for left/right/width)
///   or height (for top/bottom/height).
/// - [OverlayLength.px]: encoder output pixels (e.g. of a 720×1280 stream), ≥ 0.
sealed class OverlayLength {
  const OverlayLength();

  const factory OverlayLength.percent(num value) = PercentLength;
  const factory OverlayLength.px(num value) = PxLength;

  num get value;
  String get unit;

  Map<String, dynamic> toMap() => {'unit': unit, 'value': value.toDouble()};

  void _validate(String field) {
    if (value.isNaN || value < 0) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_PLACEMENT', '$field must be ≥ 0 (got $value)');
    }
  }
}

/// Percent of the frame axis, 0–100.
class PercentLength extends OverlayLength {
  const PercentLength(this.value);

  @override
  final num value;

  @override
  String get unit => 'percent';

  @override
  void _validate(String field) {
    super._validate(field);
    if (value > 100) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_PLACEMENT', '$field percent must be ≤ 100 (got $value)');
    }
  }
}

/// Encoder output pixels, ≥ 0.
class PxLength extends OverlayLength {
  const PxLength(this.value);

  @override
  final num value;

  @override
  String get unit => 'px';
}

/// Where and how big a dynamic overlay is drawn.
///
/// **Size:** [width] + [height] → scaled to fit inside that box keeping aspect
/// (BoxFit.contain); only one → the other follows the content aspect; neither
/// → intrinsic content size in encoder px. Anything larger than the frame is
/// scaled down to fit (warning `OVERLAY_DOWNSCALED`).
///
/// **Position, per axis:** only [left] (or [top]) pins that edge; only [right]
/// (or [bottom]) pins the opposite edge; both or neither → centered.
class OverlayPlacement {
  const OverlayPlacement({
    this.left,
    this.right,
    this.top,
    this.bottom,
    this.width,
    this.height,
  });

  final OverlayLength? left;
  final OverlayLength? right;
  final OverlayLength? top;
  final OverlayLength? bottom;
  final OverlayLength? width;
  final OverlayLength? height;

  /// Copy with the given fields replaced. Like `TextStyle.copyWith`, it can't
  /// clear a field to `null`; build a new placement for that.
  OverlayPlacement copyWith({
    OverlayLength? left,
    OverlayLength? right,
    OverlayLength? top,
    OverlayLength? bottom,
    OverlayLength? width,
    OverlayLength? height,
  }) =>
      OverlayPlacement(
        left: left ?? this.left,
        right: right ?? this.right,
        top: top ?? this.top,
        bottom: bottom ?? this.bottom,
        width: width ?? this.width,
        height: height ?? this.height,
      );

  Map<String, dynamic> toMap() => {
        if (left != null) 'left': left!.toMap(),
        if (right != null) 'right': right!.toMap(),
        if (top != null) 'top': top!.toMap(),
        if (bottom != null) 'bottom': bottom!.toMap(),
        if (width != null) 'width': width!.toMap(),
        if (height != null) 'height': height!.toMap(),
      };

  /// Throws [RtmpBroadcasterException] (`OVERLAY_INVALID_PLACEMENT`).
  void validate() {
    left?._validate('left');
    right?._validate('right');
    top?._validate('top');
    bottom?._validate('bottom');
    width?._validate('width');
    height?._validate('height');
  }
}

/// What a dynamic overlay shows.
sealed class OverlayContent {
  const OverlayContent();

  Map<String, dynamic> toMap();

  /// Throws [RtmpBroadcasterException] (`OVERLAY_INVALID_CONTENT`).
  void validate();
}

/// Static image content: PNG, JPG or static WebP bytes.
class ImageContent extends OverlayContent {
  const ImageContent(this.bytes);

  final Uint8List bytes;

  @override
  Map<String, dynamic> toMap() => {'type': 'image', 'bytes': bytes};

  @override
  void validate() {
    if (bytes.isEmpty) {
      throw const RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', 'image content requires non-empty bytes');
    }
  }
}

/// Animated GIF content. Loops by the GIF's own frame delays, live or not.
///
/// Limits: at most 150 frames and 64 MB decoded (width × height × 4 × frames),
/// otherwise `OVERLAY_GIF_TOO_LARGE`.
class GifContent extends OverlayContent {
  const GifContent(this.bytes);

  final Uint8List bytes;

  @override
  Map<String, dynamic> toMap() => {'type': 'gif', 'bytes': bytes};

  @override
  void validate() {
    if (bytes.isEmpty) {
      throw const RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', 'gif content requires non-empty bytes');
    }
  }
}

/// Horizontal alignment of wrapped [TextContent] lines.
enum TextOverlayAlign { start, center, end }

/// Font and colors for [TextContent] and [TickerContent]. Sizes are encoder px.
class TextOverlayStyle {
  const TextOverlayStyle({
    this.fontSizePx = 32,
    this.color = const Color(0xFFFFFFFF),
    this.background,
    this.paddingPx = 8,
    this.fontTtf,
    this.maxLines = 1,
    this.align = TextOverlayAlign.start,
  });

  final double fontSizePx;
  final Color color;

  /// Filled behind the text (including padding); `null` = transparent.
  final Color? background;
  final double paddingPx;

  /// TrueType/OpenType font bytes; `null` = system font. Invalid bytes fail
  /// with `OVERLAY_FONT_INVALID`.
  final Uint8List? fontTtf;

  /// [TextContent] only (tickers are always one line). `1` = a single line
  /// that is scaled to fit. Above `1` the text wraps to the placement width
  /// (default: full frame width), keeps `\n` line breaks, and ends with `…`
  /// when it needs more lines. At least 1.
  final int maxLines;

  /// Alignment of wrapped lines ([maxLines] > 1).
  final TextOverlayAlign align;

  /// Copy with the given fields replaced. Can't clear [background] or
  /// [fontTtf]; build a new style for that.
  TextOverlayStyle copyWith({
    double? fontSizePx,
    Color? color,
    Color? background,
    double? paddingPx,
    Uint8List? fontTtf,
    int? maxLines,
    TextOverlayAlign? align,
  }) =>
      TextOverlayStyle(
        fontSizePx: fontSizePx ?? this.fontSizePx,
        color: color ?? this.color,
        background: background ?? this.background,
        paddingPx: paddingPx ?? this.paddingPx,
        fontTtf: fontTtf ?? this.fontTtf,
        maxLines: maxLines ?? this.maxLines,
        align: align ?? this.align,
      );

  Map<String, dynamic> toMap() => {
        'fontSizePx': fontSizePx,
        'color': color.toARGB32(),
        'background': ?background?.toARGB32(),
        'paddingPx': paddingPx,
        'fontTtf': ?fontTtf,
        if (maxLines != 1) 'maxLines': maxLines,
        if (align != TextOverlayAlign.start) 'align': align.name,
      };

  void validate() {
    if (fontSizePx.isNaN || fontSizePx <= 0) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', 'fontSizePx must be > 0 (got $fontSizePx)');
    }
    if (paddingPx.isNaN || paddingPx < 0) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', 'paddingPx must be ≥ 0 (got $paddingPx)');
    }
    if (fontTtf != null && fontTtf!.isEmpty) {
      throw const RtmpBroadcasterException('OVERLAY_FONT_INVALID', 'fontTtf is empty');
    }
    if (maxLines < 1) {
      throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT', 'maxLines must be ≥ 1 (got $maxLines)');
    }
  }
}

/// Text overlay.
///
/// With the default `style.maxLines == 1` it is one line: line breaks and runs
/// of whitespace become single spaces, and the line is scaled to fit the
/// placement (long text gets very small). Set [TextOverlayStyle.maxLines] above
/// 1 to wrap to the placement width instead.
class TextContent extends OverlayContent {
  const TextContent(this.text, {this.style = const TextOverlayStyle()});

  final String text;
  final TextOverlayStyle style;

  TextContent copyWith({String? text, TextOverlayStyle? style}) =>
      TextContent(text ?? this.text, style: style ?? this.style);

  @override
  Map<String, dynamic> toMap() => {'type': 'text', 'text': text, 'style': style.toMap()};

  @override
  void validate() {
    _validateText(text);
    style.validate();
  }
}

/// Which way ticker text moves.
enum TickerDirection {
  /// From the text: RTL scripts (Arabic, Hebrew, Urdu) move left→right,
  /// everything else right→left.
  auto,

  /// Text moves right→left.
  rtl,

  /// Text moves left→right.
  ltr,
}

/// Text scrolling through a band, like a news ticker.
///
/// The band is the placement `width` (default 100 % of the frame) and one line
/// tall (`fontSizePx` line height + 2 × `paddingPx`); placement `height` is
/// ignored. The text is flattened to one line.
///
/// Scrolling only advances while live and shown, so viewers always see the
/// start of the message. With [loop] the next pass starts [loopGap] after the
/// previous one (default 33 % of the band); without it the overlay is removed
/// after one pass with `RtmpStatusType.overlayRemoved`, reason `completed`.
/// When a `duration` runs out, the current pass finishes first.
class TickerContent extends OverlayContent {
  const TickerContent(
    this.text, {
    this.style = defaultStyle,
    this.speedPxPerSec,
    this.cycleDuration,
    this.loop = true,
    this.loopGap,
    this.direction = TickerDirection.auto,
  });

  /// White text on 70 % black.
  static const defaultStyle = TextOverlayStyle(background: Color(0xB3000000));

  final String text;
  final TextOverlayStyle style;

  /// Scroll speed in encoder px per second. Default 120 when neither this nor
  /// [cycleDuration] is set.
  final double? speedPxPerSec;

  /// Time for one full pass (text entering until it has left the band).
  final Duration? cycleDuration;
  final bool loop;

  /// Gap between passes; percent is of the band width.
  final OverlayLength? loopGap;
  final TickerDirection direction;

  /// Copy with a new [text] and/or [style]; speed, loop, gap and direction are kept.
  TickerContent copyWith({String? text, TextOverlayStyle? style}) => TickerContent(
        text ?? this.text,
        style: style ?? this.style,
        speedPxPerSec: speedPxPerSec,
        cycleDuration: cycleDuration,
        loop: loop,
        loopGap: loopGap,
        direction: direction,
      );

  @override
  Map<String, dynamic> toMap() => {
        'type': 'ticker',
        'text': text,
        'style': style.toMap(),
        'speedPxPerSec': ?speedPxPerSec,
        'cycleDurationMs': ?cycleDuration?.inMilliseconds,
        'loop': loop,
        'loopGap': ?loopGap?.toMap(),
        'direction': direction.name,
      };

  @override
  void validate() {
    _validateText(text);
    style.validate();
    if (speedPxPerSec != null && cycleDuration != null) {
      throw const RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
          'ticker takes speedPxPerSec or cycleDuration, not both');
    }
    if (speedPxPerSec != null && (speedPxPerSec!.isNaN || speedPxPerSec! <= 0)) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', 'speedPxPerSec must be > 0 (got $speedPxPerSec)');
    }
    if (cycleDuration != null && cycleDuration!.inMilliseconds <= 0) {
      throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
          'cycleDuration must be at least 1 ms (got $cycleDuration)');
    }
    loopGap?._validate('loopGap');
  }
}

/// How a [CarouselContent] moves from one item to the next.
enum CarouselTransitionType {
  /// Instant swap at the end of the item's interval.
  cut,

  /// The current item fades out while the next fades in.
  crossfade,

  /// The next item pushes the current one out of the slot, entering from
  /// [CarouselTransition.edge].
  push,
}

/// Transition between [CarouselContent] items. Runs natively.
class CarouselTransition {
  const CarouselTransition({
    this.type = CarouselTransitionType.crossfade,
    this.durationMs = 500,
    this.easing = OverlayEasing.easeInOut,
    this.edge = OverlayEdge.right,
  });

  const CarouselTransition.cut() : this(type: CarouselTransitionType.cut, durationMs: 0);

  const CarouselTransition.crossfade({int durationMs = 500, OverlayEasing easing = OverlayEasing.easeInOut})
      : this(type: CarouselTransitionType.crossfade, durationMs: durationMs, easing: easing);

  const CarouselTransition.push({
    OverlayEdge edge = OverlayEdge.right,
    int durationMs = 500,
    OverlayEasing easing = OverlayEasing.easeInOut,
  }) : this(type: CarouselTransitionType.push, edge: edge, durationMs: durationMs, easing: easing);

  final CarouselTransitionType type;

  /// 0–5000 ms, shorter than every item interval. Ignored by [CarouselTransitionType.cut].
  final int durationMs;
  final OverlayEasing easing;

  /// [CarouselTransitionType.push] only: the side the next item enters from.
  final OverlayEdge edge;

  Map<String, dynamic> toMap() => {
        'type': type.name,
        'durationMs': durationMs,
        'easing': easing.name,
        'edge': edge.name,
      };
}

/// One [CarouselContent] item: an [ImageContent] or [GifContent].
class CarouselItem {
  const CarouselItem(this.content, {this.interval});

  /// [ImageContent] or [GifContent].
  final OverlayContent content;

  /// How long this item stays; `null` = [CarouselContent.interval]. At least 500 ms.
  final Duration? interval;

  Map<String, dynamic> toMap() => {
        'content': content.toMap(),
        'intervalMs': ?interval?.inMilliseconds,
      };
}

/// Images and GIFs shown one after another in the same place, e.g. rotating
/// sponsor logos.
///
/// Each item stays for its interval; the [transition] into the next item takes
/// the last `durationMs` of that interval. Loops forever — give the overlay a
/// `duration` to stop it. The rotation runs on a wall clock from `addOverlay`
/// (or from a content update, which restarts at the first item), so it also
/// rotates before go-live and while hidden.
///
/// The slot is the placement box when both `width` and `height` are given;
/// otherwise it is as tall as the tallest item and as wide as the widest item
/// aspect. Every item is fitted (contain) and centered in the slot.
///
/// Limits: 1–20 items, 64 MB decoded in total (`OVERLAY_CAROUSEL_TOO_LARGE`),
/// each GIF within the [GifContent] limits.
class CarouselContent extends OverlayContent {
  const CarouselContent(
    this.items, {
    this.interval = const Duration(seconds: 5),
    this.transition = const CarouselTransition(),
  });

  static const maxItems = 20;
  static const minInterval = Duration(milliseconds: 500);

  final List<CarouselItem> items;

  /// Default time each item stays (including the transition out of it). At least 500 ms.
  final Duration interval;
  final CarouselTransition transition;

  @override
  Map<String, dynamic> toMap() => {
        'type': 'carousel',
        'intervalMs': interval.inMilliseconds,
        'items': [for (final item in items) item.toMap()],
        'transition': transition.toMap(),
      };

  @override
  void validate() {
    if (items.isEmpty || items.length > maxItems) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', 'carousel needs 1–$maxItems items (got ${items.length})');
    }
    _validateInterval(interval, 'interval');
    for (var i = 0; i < items.length; i++) {
      final item = items[i];
      if (item.content is! ImageContent && item.content is! GifContent) {
        throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
            'carousel item $i must be ImageContent or GifContent (got ${item.content.runtimeType})');
      }
      item.content.validate();
      if (item.interval != null) _validateInterval(item.interval!, 'items[$i].interval');
    }
    if (transition.durationMs < 0 || transition.durationMs > 5000) {
      throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
          'transition.durationMs must be 0–5000 (got ${transition.durationMs})');
    }
    if (items.length > 1 && transition.type != CarouselTransitionType.cut && transition.durationMs > 0) {
      final shortest = items
          .map((item) => (item.interval ?? interval).inMilliseconds)
          .reduce((a, b) => a < b ? a : b);
      if (transition.durationMs >= shortest) {
        throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
            'transition.durationMs (${transition.durationMs}) must be shorter than every interval (shortest $shortest ms)');
      }
    }
  }

  static void _validateInterval(Duration d, String field) {
    if (d < minInterval) {
      throw RtmpBroadcasterException(
          'OVERLAY_INVALID_CONTENT', '$field must be at least 500 ms (got ${d.inMilliseconds} ms)');
    }
  }
}

void _validateText(String text) {
  if (text.trim().isEmpty) {
    throw const RtmpBroadcasterException('OVERLAY_INVALID_CONTENT', 'text must not be empty');
  }
}

enum OverlayAnimationType { none, slide, pop, curtain }

/// Cubic easing curves.
enum OverlayEasing { linear, easeIn, easeOut, easeInOut }

enum OverlayEdge { left, right, top, bottom }

/// Enter or exit animation of a dynamic overlay. Runs natively, live or not.
///
/// - [OverlayAnimationType.slide]: moves in from / out to [edge].
/// - [OverlayAnimationType.pop]: scales from / to 0 around the center.
/// - [OverlayAnimationType.curtain]: reveals from the center outward / closes
///   to the center.
class OverlayAnimation {
  const OverlayAnimation({
    this.type = OverlayAnimationType.none,
    this.durationMs = 400,
    this.easing = OverlayEasing.easeOut,
    this.edge = OverlayEdge.bottom,
  });

  const OverlayAnimation.slide({
    OverlayEdge edge = OverlayEdge.bottom,
    int durationMs = 400,
    OverlayEasing easing = OverlayEasing.easeOut,
  }) : this(type: OverlayAnimationType.slide, edge: edge, durationMs: durationMs, easing: easing);

  const OverlayAnimation.pop({int durationMs = 400, OverlayEasing easing = OverlayEasing.easeOut})
      : this(type: OverlayAnimationType.pop, durationMs: durationMs, easing: easing);

  const OverlayAnimation.curtain({int durationMs = 400, OverlayEasing easing = OverlayEasing.easeOut})
      : this(type: OverlayAnimationType.curtain, durationMs: durationMs, easing: easing);

  static const none = OverlayAnimation();
  static const maxDurationMs = 5000;

  final OverlayAnimationType type;

  /// 0–5000 ms; 0 is instant.
  final int durationMs;
  final OverlayEasing easing;

  /// Used by [OverlayAnimationType.slide] only.
  final OverlayEdge edge;

  Map<String, dynamic> toMap() => {
        'type': type.name,
        'durationMs': durationMs,
        'easing': easing.name,
        'edge': edge.name,
      };

  void validate(String field) {
    if (durationMs < 0 || durationMs > maxDurationMs) {
      throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
          '$field.durationMs must be 0–$maxDurationMs (got $durationMs)');
    }
  }
}

/// An app-controlled overlay layer composited natively onto the stream.
///
/// Identified by [id], which must be 1–64 characters, not `scoreband`, and not
/// start with `sponsor_`. [weight] is the z-order, 0 (back) to 100 (front);
/// sponsors default to 10 and the scoreband to 50. On equal weight, sponsors are
/// below the scoreband, which is below dynamic overlays; among dynamic overlays
/// the one added later is on top.
///
/// [duration] counts **live time only**: while RTMP is connected and the
/// overlay is shown. It is paused before go-live, during reconnects, after
/// `stopStream`, and while hidden, and it survives `stopStream`/`startStream`.
/// When it runs out the overlay is removed with
/// `RtmpStatusType.overlayRemoved`, reason `expired`. `null` = never expires.
///
/// [enter] plays on add and show; [exit] on hide, remove, expiry and ticker
/// completion. `overlayShown` / `overlayHidden` / `overlayRemoved` fire when the
/// animation has finished.
class DynamicOverlay {
  const DynamicOverlay({
    required this.id,
    required this.content,
    this.placement = const OverlayPlacement(),
    this.weight = 50,
    this.duration,
    this.enter = OverlayAnimation.none,
    this.exit = OverlayAnimation.none,
  });

  static const maxIdLength = 64;

  final String id;
  final OverlayContent content;
  final OverlayPlacement placement;
  final int weight;

  /// Live time before the overlay expires; `null` = infinite. At least 1 ms.
  final Duration? duration;

  final OverlayAnimation enter;
  final OverlayAnimation exit;

  Map<String, dynamic> toMap() => {
        'id': id,
        'weight': weight,
        'durationMs': ?duration?.inMilliseconds,
        'content': content.toMap(),
        'placement': placement.toMap(),
        'enter': enter.toMap(),
        'exit': exit.toMap(),
      };

  /// Throws [RtmpBroadcasterException] with the same codes as the native side.
  void validate() {
    validateOverlayId(id);
    validateOverlayWeight(weight);
    if (duration != null) validateOverlayDuration(duration!);
    content.validate();
    placement.validate();
    enter.validate('enter');
    exit.validate('exit');
  }
}

/// How `RtmpBroadcastController.updateOverlay` changes an overlay's duration.
///
/// Time already shown live is kept, so a new total shorter than that expires
/// the overlay as soon as it is running. Pass `restartTimer: true` to count
/// from zero.
class OverlayDurationUpdate {
  /// Leave the duration as it is (same as omitting the argument).
  const OverlayDurationUpdate.keep()
      : duration = null,
        _keep = true;

  /// Never expire.
  const OverlayDurationUpdate.infinite()
      : duration = null,
        _keep = false;

  /// New total live duration.
  const OverlayDurationUpdate.of(Duration this.duration) : _keep = false;

  /// The new total, or `null` for [OverlayDurationUpdate.keep] and
  /// [OverlayDurationUpdate.infinite].
  final Duration? duration;
  final bool _keep;

  /// Wire value for `overlayUpdate.duration`; `null` means omit the key.
  Map<String, dynamic>? toMap() =>
      _keep ? null : {'ms': duration?.inMilliseconds};

  /// Throws `OVERLAY_INVALID_CONTENT` for a duration under 1 ms.
  void validate() {
    if (duration != null) validateOverlayDuration(duration!);
  }
}

/// Throws `OVERLAY_ID_RESERVED` for ids the overlay API can't use.
void validateOverlayId(String id) {
  if (id.isEmpty || id.length > DynamicOverlay.maxIdLength) {
    throw const RtmpBroadcasterException('OVERLAY_ID_RESERVED',
        'id must be 1–${DynamicOverlay.maxIdLength} characters');
  }
  if (id == 'scoreband' || id.startsWith('sponsor_')) {
    throw RtmpBroadcasterException('OVERLAY_ID_RESERVED', "id '$id' is reserved");
  }
}

/// Throws `OVERLAY_INVALID_CONTENT` when [duration] is under 1 ms.
void validateOverlayDuration(Duration duration) {
  if (duration.inMilliseconds <= 0) {
    throw RtmpBroadcasterException('OVERLAY_INVALID_CONTENT',
        'duration must be at least 1 ms (got $duration)');
  }
}

/// Throws `OVERLAY_INVALID_PLACEMENT` when [weight] is outside 0–100.
void validateOverlayWeight(int weight) {
  if (weight < 0 || weight > 100) {
    throw RtmpBroadcasterException(
        'OVERLAY_INVALID_PLACEMENT', 'weight must be 0–100 (got $weight)');
  }
}

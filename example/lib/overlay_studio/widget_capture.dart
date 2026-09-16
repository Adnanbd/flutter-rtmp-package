import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/rendering.dart';
import 'package:flutter/widgets.dart';

/// Renders the [RepaintBoundary] behind [key] to PNG bytes, ready for
/// `updateScoreband(bytes)` or `ImageContent(bytes)`.
///
/// Returns null when the boundary is not in the tree yet. The widget must be
/// mounted and painted: an off-screen `Positioned` works, `Opacity(opacity: 0)`
/// does not (it paints nothing).
///
/// [pixelRatio] multiplies the widget's logical size; the overlay is scaled to
/// its placement on the stream, so a higher ratio only buys sharpness.
Future<Uint8List?> captureBoundaryPng(GlobalKey key, {double pixelRatio = 2.0}) async {
  final boundary = key.currentContext?.findRenderObject() as RenderRepaintBoundary?;
  if (boundary == null) return null;
  if (boundary.debugNeedsPaint) {
    await WidgetsBinding.instance.endOfFrame;
  }
  final image = await boundary.toImage(pixelRatio: pixelRatio);
  try {
    final byteData = await image.toByteData(format: ui.ImageByteFormat.png);
    return byteData?.buffer.asUint8List();
  } finally {
    image.dispose();
  }
}

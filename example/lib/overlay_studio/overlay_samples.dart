import 'dart:math';
import 'dart:typed_data';
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:image_picker/image_picker.dart';

import 'mock_match.dart';

/// An image or GIF picked from the device gallery.
class PickedMedia {
  const PickedMedia(this.name, this.bytes);

  final String name;
  final Uint8List bytes;

  bool get isGif => OverlaySamples.isGif(bytes);
  String get sizeLabel => bytes.length >= 1024 * 1024
      ? '${(bytes.length / (1024 * 1024)).toStringAsFixed(1)} MB'
      : '${(bytes.length / 1024).toStringAsFixed(0)} KB';
}

/// Generated / bundled test content for the Overlay Studio.
class OverlaySamples {
  OverlaySamples._();

  static const english = 'Breaking: Team A win the final by 3 wickets — highlights after the break';
  static const bangla = 'সরাসরি সম্প্রচার: বাংলাদেশ বনাম ভারত — ফাইনাল ম্যাচ';
  static const arabic = 'بث مباشر: المباراة النهائية بين الفريقين';

  /// Rounded badge with a centered label, as PNG bytes.
  static Future<Uint8List> badgePng(String label, Color color, {double w = 240, double h = 96}) async {
    final recorder = ui.PictureRecorder();
    final canvas = Canvas(recorder);
    canvas.drawRRect(
      RRect.fromRectAndRadius(Rect.fromLTWH(0, 0, w, h), Radius.circular(h / 5)),
      Paint()..color = color,
    );
    final tp = TextPainter(
      text: TextSpan(
        text: label,
        style: TextStyle(color: Colors.white, fontSize: h / 2.4, fontWeight: FontWeight.bold),
      ),
      textDirection: TextDirection.ltr,
      maxLines: 1,
      ellipsis: '…',
    )..layout(maxWidth: w - 16);
    tp.paint(canvas, Offset((w - tp.width) / 2, (h - tp.height) / 2));
    final image = await recorder.endRecording().toImage(w.toInt(), h.toInt());
    final data = await image.toByteData(format: ui.ImageByteFormat.png);
    return data!.buffer.asUint8List();
  }

  /// One generated logo per mock sponsor, as carousel items.
  static Future<List<CarouselItem>> sponsorItems({bool shuffle = false, Duration? firstInterval}) async {
    final sponsors = [...MockMatch.sponsors];
    if (shuffle) sponsors.shuffle();
    return [
      for (final (i, (name, color)) in sponsors.indexed)
        CarouselItem(
          ImageContent(await badgePng(name, color, w: 360, h: 120)),
          interval: i == 0 ? firstInterval : null,
        ),
    ];
  }

  /// Pick several images / GIFs from the gallery, bytes untouched.
  static Future<List<PickedMedia>> pickManyFromGallery() async {
    final files = await ImagePicker().pickMultiImage(requestFullMetadata: false);
    return [for (final f in files) PickedMedia(f.name, await f.readAsBytes())];
  }

  static Future<Uint8List> spinnerGif() async =>
      (await rootBundle.load('assets/overlays/spinner.gif')).buffer.asUint8List();

  static Future<Uint8List> customFont() async =>
      (await rootBundle.load('assets/font/robotocondensed/RobotoCondensed-Bold.ttf')).buffer.asUint8List();

  /// Valid 1×1 GIF with [frames] frames — 151 triggers `OVERLAY_GIF_TOO_LARGE`.
  static Uint8List gifWithFrames(int frames) {
    final b = BytesBuilder()
      ..add('GIF89a'.codeUnits)
      ..add([1, 0, 1, 0, 0xF0, 0, 0]) // 1×1, 2-colour global table
      ..add([0, 0, 0, 255, 255, 255]);
    for (var i = 0; i < frames; i++) {
      b
        ..add([0x21, 0xF9, 0x04, 0x00, 0x0A, 0x00, 0x00, 0x00]) // 100 ms
        ..add([0x2C, 0, 0, 0, 0, 1, 0, 1, 0, 0]) // image descriptor
        ..add([0x02, 0x02, 0x44, 0x01, 0x00]); // LZW: clear, 0, end
    }
    b.addByte(0x3B);
    return b.toBytes();
  }

  /// Pick an image or GIF from the gallery, bytes untouched (no resize/quality args, so GIFs stay animated).
  static Future<PickedMedia?> pickFromGallery() async {
    final file = await ImagePicker().pickImage(source: ImageSource.gallery, requestFullMetadata: false);
    if (file == null) return null;
    return PickedMedia(file.name, await file.readAsBytes());
  }

  /// GIF87a / GIF89a signature.
  static bool isGif(Uint8List b) => b.length >= 6 && b[0] == 0x47 && b[1] == 0x49 && b[2] == 0x46 && b[3] == 0x38;

  /// Random bytes that no image decoder or font loader accepts.
  static Uint8List garbage([int length = 64]) {
    final rng = Random(42);
    return Uint8List.fromList(List.generate(length, (_) => rng.nextInt(256)));
  }
}

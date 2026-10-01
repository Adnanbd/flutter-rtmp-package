import 'package:flutter/material.dart';

/// Which input the stream is using, as far as the app knows from config + `warning` events.
enum AudioSourceState {
  phoneMic,
  usb,

  /// `USB_AUDIO_STALLED`: the package restarted USB capture.
  usbRestarted,

  /// `USB_AUDIO_FALLBACK_PHONE_MIC`: USB audio failed, the stream now carries the phone mic.
  phoneMicFallback,
}

/// Always-visible chip on the Go Live screen (example chrome, never in the stream).
class AudioSourceChip extends StatelessWidget {
  const AudioSourceChip({super.key, required this.state});

  final AudioSourceState state;

  @override
  Widget build(BuildContext context) {
    final (IconData icon, String text, Color color) = switch (state) {
      AudioSourceState.phoneMic => (Icons.smartphone, 'Audio: phone mic', Colors.black54),
      AudioSourceState.usb => (Icons.usb, 'Audio: USB', Colors.green.shade700),
      AudioSourceState.usbRestarted => (Icons.restart_alt, 'Audio: USB (restarted)', Colors.orange.shade800),
      AudioSourceState.phoneMicFallback => (Icons.warning_amber, 'Audio: PHONE MIC — USB failed', Colors.red.shade700),
    };
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(16)),
      child: Row(mainAxisSize: MainAxisSize.min, children: [
        Icon(icon, size: 16, color: Colors.white),
        const SizedBox(width: 6),
        Text(text, style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
      ]),
    );
  }
}

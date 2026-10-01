import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

/// Live mic-cleanup switch (Off / Basic / Voice), usable before and during a stream.
/// Screen chrome only — the processing itself runs natively on the audio before encoding.
class AudioCleanupButton extends StatelessWidget {
  const AudioCleanupButton({super.key, required this.mode, required this.onChanged});

  final AudioCleanup mode;
  final ValueChanged<AudioCleanup> onChanged;

  static String label(AudioCleanup m) => switch (m) {
        AudioCleanup.off => 'Cleanup: off',
        AudioCleanup.basic => 'Cleanup: basic',
        AudioCleanup.voice => 'Cleanup: voice',
      };

  @override
  Widget build(BuildContext context) {
    return PopupMenuButton<AudioCleanup>(
      tooltip: 'Mic noise cleanup',
      initialValue: mode,
      onSelected: onChanged,
      itemBuilder: (_) => [
        for (final m in AudioCleanup.values) PopupMenuItem(value: m, child: Text(label(m))),
      ],
      child: Container(
        padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
        decoration: BoxDecoration(
          color: mode == AudioCleanup.off ? Colors.black54 : Colors.blue.shade700,
          borderRadius: BorderRadius.circular(16),
        ),
        child: Row(mainAxisSize: MainAxisSize.min, children: [
          const Icon(Icons.graphic_eq, size: 16, color: Colors.white),
          const SizedBox(width: 6),
          Text(label(mode), style: const TextStyle(color: Colors.white, fontSize: 12, fontWeight: FontWeight.w600)),
        ]),
      ),
    );
  }
}

import 'package:flutter/material.dart';

class CameraControlsBar extends StatelessWidget {
  const CameraControlsBar({
    super.key,
    required this.streaming,
    required this.muted,
    required this.onFlip,
    required this.onToggleStream,
    required this.onToggleMute,
  });

  final bool streaming;
  final bool muted;
  final VoidCallback onFlip;
  final VoidCallback onToggleStream;
  final VoidCallback onToggleMute;

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        FloatingActionButton(
          heroTag: 'flip',
          onPressed: onFlip,
          child: const Icon(Icons.flip_camera_android),
        ),
        FloatingActionButton(
          heroTag: 'stream',
          backgroundColor: streaming ? Colors.red : Colors.green,
          onPressed: onToggleStream,
          child: Icon(streaming ? Icons.stop : Icons.play_arrow),
        ),
        FloatingActionButton(
          heroTag: 'mute',
          backgroundColor: muted ? Colors.orange : Colors.blue,
          onPressed: onToggleMute,
          child: Icon(muted ? Icons.mic_off : Icons.mic),
        ),
      ],
    );
  }
}

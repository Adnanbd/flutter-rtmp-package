import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

class CameraControlsBar extends StatelessWidget {
  const CameraControlsBar({
    super.key,
    required this.streaming,
    required this.muted,
    required this.currentFacing,
    required this.onFlip,
    required this.onToggleStream,
    required this.onToggleMute,
  });

  final bool streaming;
  final bool muted;
  final CameraFacing currentFacing;
  final VoidCallback onFlip;
  final VoidCallback onToggleStream;
  final VoidCallback onToggleMute;

  Widget _fab({
    required String heroTag,
    required IconData icon,
    required String label,
    required VoidCallback onPressed,
    Color? backgroundColor,
    Color? foregroundColor,
  }) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        FloatingActionButton(
          heroTag: heroTag,
          onPressed: onPressed,
          backgroundColor: backgroundColor,
          foregroundColor: foregroundColor,
          child: Icon(icon),
        ),
        const SizedBox(height: 6),
        Text(
          label,
          style: const TextStyle(
            color: Colors.white,
            fontSize: 12,
            fontWeight: FontWeight.w500,
            shadows: [Shadow(color: Colors.black54, blurRadius: 4)],
          ),
        ),
      ],
    );
  }

  @override
  Widget build(BuildContext context) {
    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        _fab(
          heroTag: 'flip',
          icon: Icons.flip_camera_android,
          label: currentFacing == CameraFacing.front ? 'Front' : 'Back',
          onPressed: onFlip,
        ),
        _fab(
          heroTag: 'stream',
          icon: streaming ? Icons.stop : Icons.play_arrow,
          label: streaming ? 'Stop' : 'Go Live',
          onPressed: onToggleStream,
          backgroundColor: streaming ? Colors.red : Colors.green,
          foregroundColor: Colors.white,
        ),
        _fab(
          heroTag: 'mute',
          icon: muted ? Icons.mic_off : Icons.mic,
          label: muted ? 'Mic Off' : 'Mic On',
          onPressed: onToggleMute,
          backgroundColor: muted ? Colors.orange : Colors.blue,
          foregroundColor: Colors.white,
        ),
      ],
    );
  }
}

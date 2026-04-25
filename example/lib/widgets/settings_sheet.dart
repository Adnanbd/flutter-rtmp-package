import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

class SettingsSheet extends StatelessWidget {
  const SettingsSheet({
    super.key,
    required this.selectedRes,
    required this.selectedOrient,
    required this.onResChanged,
    required this.onOrientChanged,
  });

  final VideoResolution selectedRes;
  final VideoOrientation selectedOrient;
  final ValueChanged<VideoResolution> onResChanged;
  final Future<void> Function(VideoOrientation) onOrientChanged;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(24),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('Settings', style: TextStyle(fontSize: 18, fontWeight: FontWeight.bold)),
          const SizedBox(height: 16),
          Row(
            children: [
              const SizedBox(width: 80, child: Text('Resolution')),
              Expanded(
                child: DropdownButton<VideoResolution>(
                  value: selectedRes,
                  isExpanded: true,
                  items: const [
                    DropdownMenuItem(value: VideoResolution.hd720, child: Text('720p')),
                    DropdownMenuItem(value: VideoResolution.fhd1080, child: Text('1080p')),
                  ],
                  onChanged: (v) {
                    if (v == null) return;
                    onResChanged(v);
                    Navigator.pop(context);
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 12),
          Row(
            children: [
              const SizedBox(width: 80, child: Text('Orientation')),
              Expanded(
                child: DropdownButton<VideoOrientation>(
                  value: selectedOrient,
                  isExpanded: true,
                  items: const [
                    DropdownMenuItem(value: VideoOrientation.landscape, child: Text('Landscape')),
                    DropdownMenuItem(value: VideoOrientation.portrait, child: Text('Portrait')),
                  ],
                  onChanged: (v) async {
                    if (v == null) return;
                    await onOrientChanged(v);
                    if (context.mounted) Navigator.pop(context);
                  },
                ),
              ),
            ],
          ),
          const SizedBox(height: 24),
        ],
      ),
    );
  }
}

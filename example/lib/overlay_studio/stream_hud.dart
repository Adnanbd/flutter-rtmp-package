import 'dart:async';

import 'package:flutter/material.dart';

import 'overlay_studio.dart';

/// On-screen status for testing overlays during a stream: live phase + time, overlay counts, last event.
/// Example chrome only — it is Flutter UI, never part of the encoded stream.
class StreamHud extends StatefulWidget {
  const StreamHud({super.key, required this.studio});

  final OverlayStudio studio;

  @override
  State<StreamHud> createState() => _StreamHudState();
}

class _StreamHudState extends State<StreamHud> {
  Timer? _clock;

  @override
  void initState() {
    super.initState();
    // Example-only: refresh the on-air clock once a second.
    _clock = Timer.periodic(const Duration(seconds: 1), (_) {
      if (mounted && widget.studio.liveSince != null) setState(() {});
    });
  }

  @override
  void dispose() {
    _clock?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return ListenableBuilder(
      listenable: widget.studio,
      builder: (context, _) {
        final s = widget.studio;
        final since = s.liveSince;
        final elapsed = since == null ? Duration.zero : DateTime.now().difference(since);
        final mmss = '${elapsed.inMinutes.toString().padLeft(2, '0')}:${(elapsed.inSeconds % 60).toString().padLeft(2, '0')}';
        final (label, color) = switch (s.phase) {
          StreamPhase.live => ('LIVE $mmss', Colors.red),
          StreamPhase.reconnecting => ('RECONNECTING #${s.reconnectAttempt}', Colors.orange),
          StreamPhase.offline => ('OFFLINE · timers paused', Colors.grey.shade700),
        };
        final last = s.events.isEmpty ? null : s.events.first;
        return IgnorePointer(
          child: Container(
            constraints: const BoxConstraints(maxWidth: 260),
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(color: Colors.black54, borderRadius: BorderRadius.circular(10)),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, mainAxisSize: MainAxisSize.min, children: [
              Container(
                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
                decoration: BoxDecoration(color: color, borderRadius: BorderRadius.circular(6)),
                child: Text(label, style: const TextStyle(color: Colors.white, fontWeight: FontWeight.bold, fontSize: 12)),
              ),
              const SizedBox(height: 4),
              Text(
                'Overlays ${s.overlays.length}${s.hiddenCount > 0 ? ' (${s.hiddenCount} hidden)' : ''}'
                '${s.kbps != null ? '  ·  ${s.kbps} kbps' : ''}',
                style: const TextStyle(color: Colors.white, fontSize: 12),
              ),
              if (last != null)
                Text(
                  last.message,
                  maxLines: 2,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: last.isError ? Colors.redAccent : Colors.white70, fontSize: 11),
                ),
            ]),
          ),
        );
      },
    );
  }
}

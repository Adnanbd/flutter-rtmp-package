import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

/// Shows the package's native diagnostics log (`exportDiagnostics`) with Copy / Clear / Close.
/// Used from the config screen and, mid-stream, from the Go Live screen.
Future<void> showDiagnosticsDialog(
  BuildContext context,
  RtmpBroadcastController controller, {
  required void Function(String message) onMessage,
}) async {
  final log = await controller.exportDiagnostics();
  if (!context.mounted) return;
  await showDialog<void>(
    context: context,
    builder: (dialogContext) => AlertDialog(
      title: const Text('Diagnostics'),
      content: SizedBox(
        width: double.maxFinite,
        height: 400,
        child: SingleChildScrollView(
          child: SelectableText(log, style: const TextStyle(fontSize: 11, fontFamily: 'monospace')),
        ),
      ),
      actions: [
        TextButton(
          onPressed: () {
            Clipboard.setData(ClipboardData(text: log));
            Navigator.of(dialogContext).pop();
            onMessage('Copied to clipboard');
          },
          child: const Text('Copy'),
        ),
        TextButton(
          // The log can hold old stream endpoints from builds before 2026-09-30.
          onPressed: () async {
            Navigator.of(dialogContext).pop();
            await controller.clearDiagnostics();
            onMessage('Diagnostics cleared');
          },
          child: const Text('Clear'),
        ),
        TextButton(
          onPressed: () => Navigator.of(dialogContext).pop(),
          child: const Text('Close'),
        ),
      ],
    ),
  );
}

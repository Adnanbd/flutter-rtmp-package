import 'package:flutter/material.dart';
import 'package:permission_handler/permission_handler.dart';
import 'rtmp_config_screen.dart';

class PermissionGateScreen extends StatefulWidget {
  const PermissionGateScreen({super.key});

  @override
  State<PermissionGateScreen> createState() => _PermissionGateScreenState();
}

class _PermissionGateScreenState extends State<PermissionGateScreen> {
  bool _checking = true;
  bool _granted = false;

  @override
  void initState() {
    super.initState();
    _request();
  }

  Future<void> _request() async {
    final statuses = await [Permission.camera, Permission.microphone].request();
    final ok = statuses.values.every((s) => s.isGranted);
    setState(() {
      _granted = ok;
      _checking = false;
    });
  }

  @override
  Widget build(BuildContext context) {
    if (_checking) {
      return const Scaffold(body: Center(child: CircularProgressIndicator()));
    }
    if (!_granted) {
      return Scaffold(
        body: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Text('Camera and microphone permission required.'),
              const SizedBox(height: 12),
              ElevatedButton(onPressed: openAppSettings, child: const Text('Open Settings')),
            ],
          ),
        ),
      );
    }
    return const RtmpConfigScreen();
  }
}

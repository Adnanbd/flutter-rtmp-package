import 'package:flutter/material.dart';
import 'screens/permission_gate_screen.dart';

void main() => runApp(const ExampleApp());

class ExampleApp extends StatelessWidget {
  const ExampleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'RTMP Broadcaster',
      theme: ThemeData(colorSchemeSeed: Colors.blue, useMaterial3: true),
      home: const PermissionGateScreen(),
    );
  }
}

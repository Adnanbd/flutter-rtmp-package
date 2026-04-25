import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'camera_screen.dart';

class RtmpConfigScreen extends StatefulWidget {
  const RtmpConfigScreen({super.key});

  @override
  State<RtmpConfigScreen> createState() => _RtmpConfigScreenState();
}

class _RtmpConfigScreenState extends State<RtmpConfigScreen> {
  final _urlCtrl = TextEditingController(text: 'rtmp://a.rtmp.youtube.com/live2');
  final _keyCtrl = TextEditingController(text: '0rqt-kuhd-qkah-vqbk-1j7y');
  bool _connecting = false;

  Future<void> _connect() async {
    final url = _urlCtrl.text.trim();
    final key = _keyCtrl.text.trim();
    if (url.isEmpty || key.isEmpty) {
      _showSnack('Enter RTMP URL and stream key');
      return;
    }
    setState(() => _connecting = true);
    try {
      final controller = RtmpBroadcastController();
      final config = StreamConfig.defaultConfig;
      await controller.initPreview(config: config);
      await controller.configure(rtmpUrl: url, rtmpKey: key, sponsors: [], config: config);
      await controller.setAppOrientation(config.orientation);
      if (!mounted) return;
      Navigator.of(context).pushReplacement(
        MaterialPageRoute(builder: (_) => CameraScreen(controller: controller)),
      );
    } on RtmpBroadcasterException catch (e) {
      _showSnack('Failed: ${e.code} — ${e.message}');
    } finally {
      if (mounted) setState(() => _connecting = false);
    }
  }

  void _showSnack(String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));
  }

  @override
  void dispose() {
    _urlCtrl.dispose();
    _keyCtrl.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('RTMP Configuration')),
      body: Padding(
        padding: const EdgeInsets.all(24),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            const Text(
              'Enter your RTMP details to go live',
              style: TextStyle(fontSize: 16, color: Colors.grey),
              textAlign: TextAlign.center,
            ),
            const SizedBox(height: 32),
            TextField(
              controller: _urlCtrl,
              decoration: const InputDecoration(
                labelText: 'RTMP URL',
                hintText: 'rtmp://a.rtmp.youtube.com/live2',
                border: OutlineInputBorder(),
              ),
              keyboardType: TextInputType.url,
              autocorrect: false,
            ),
            const SizedBox(height: 16),
            TextField(
              controller: _keyCtrl,
              decoration: const InputDecoration(
                labelText: 'Stream Key',
                hintText: 'your-stream-key',
                border: OutlineInputBorder(),
              ),
              obscureText: true,
            ),
            const SizedBox(height: 32),
            SizedBox(
              height: 50,
              child: FilledButton(
                onPressed: _connecting ? null : _connect,
                child: _connecting
                    ? const SizedBox(
                        width: 20,
                        height: 20,
                        child: CircularProgressIndicator(strokeWidth: 2, color: Colors.white),
                      )
                    : const Text('Go Live'),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

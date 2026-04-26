import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';
import 'package:image_picker/image_picker.dart';
import 'camera_screen.dart';

class _SponsorItem {
  Uint8List bytes;
  int positionIndex; // 0=Left 1=Middle 2=Right
  _SponsorItem(this.bytes, this.positionIndex);
}

class RtmpConfigScreen extends StatefulWidget {
  const RtmpConfigScreen({super.key});

  @override
  State<RtmpConfigScreen> createState() => _RtmpConfigScreenState();
}

class _RtmpConfigScreenState extends State<RtmpConfigScreen> {
  final _urlCtrl = TextEditingController(text: 'rtmp://a.rtmp.youtube.com/live2');
  final _keyCtrl = TextEditingController(text: '0rqt-kuhd-qkah-vqbk-1j7y');
  bool _connecting = false;

  final List<_SponsorItem> _sponsors = [];
  final _picker = ImagePicker();

  static const _posX = [0.02, 0.39, 0.76];
  static const _posLabels = ['Left', 'Middle', 'Right'];

  Future<void> _addSponsor() async {
    final file = await _picker.pickImage(source: ImageSource.gallery, imageQuality: 90);
    if (file == null) return;
    final bytes = await file.readAsBytes();
    setState(() => _sponsors.add(_SponsorItem(bytes, _sponsors.length.clamp(0, 2))));
  }

  Future<void> _replaceSponsorImage(int index) async {
    final file = await _picker.pickImage(source: ImageSource.gallery, imageQuality: 90);
    if (file == null) return;
    final bytes = await file.readAsBytes();
    setState(() => _sponsors[index].bytes = bytes);
  }

  List<SponsorOverlay> _buildSponsors() {
    return _sponsors.map((s) => SponsorOverlay(
      bytes: s.bytes,
      position: OverlayPosition(
        x: _posX[s.positionIndex],
        y: 0.02,
        width: 0.22,
        height: 0.08,
      ),
    )).toList();
  }

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
      await controller.configure(
        rtmpUrl: url,
        rtmpKey: key,
        sponsors: _buildSponsors(),
        config: config,
      );
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

  Widget _buildSponsorCard(int index) {
    final sponsor = _sponsors[index];
    return Card(
      margin: const EdgeInsets.only(bottom: 8),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 12, 8, 12),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            GestureDetector(
              onTap: () => _replaceSponsorImage(index),
              child: ClipRRect(
                borderRadius: BorderRadius.circular(8),
                child: SizedBox(
                  width: 80,
                  height: 80,
                  child: Image.memory(sponsor.bytes, fit: BoxFit.cover),
                ),
              ),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    'Sponsor ${index + 1}',
                    style: const TextStyle(fontWeight: FontWeight.w600),
                  ),
                  const SizedBox(height: 6),
                  const Text(
                    'Position on stream',
                    style: TextStyle(fontSize: 12, color: Colors.grey),
                  ),
                  const SizedBox(height: 4),
                  SegmentedButton<int>(
                    segments: [
                      for (var p = 0; p < 3; p++)
                        ButtonSegment<int>(
                          value: p,
                          label: Text(_posLabels[p], style: const TextStyle(fontSize: 12)),
                        ),
                    ],
                    selected: {sponsor.positionIndex},
                    onSelectionChanged: (s) =>
                        setState(() => sponsor.positionIndex = s.first),
                    style: const ButtonStyle(
                      visualDensity: VisualDensity.compact,
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ),
                ],
              ),
            ),
            IconButton(
              icon: const Icon(Icons.close, size: 20),
              onPressed: () => setState(() => _sponsors.removeAt(index)),
              visualDensity: VisualDensity.compact,
            ),
          ],
        ),
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('RTMP Configuration')),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: Column(
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
            const SizedBox(height: 28),
            Row(
              children: [
                const Text(
                  'Sponsor Images',
                  style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600),
                ),
                const SizedBox(width: 8),
                Text(
                  'optional · up to 3',
                  style: TextStyle(fontSize: 13, color: Colors.grey.shade500),
                ),
              ],
            ),
            const SizedBox(height: 10),
            for (var i = 0; i < _sponsors.length; i++) _buildSponsorCard(i),
            if (_sponsors.length < 3)
              OutlinedButton.icon(
                onPressed: _addSponsor,
                icon: const Icon(Icons.add_photo_alternate_outlined, size: 20),
                label: const Text('Add Sponsor Image'),
                style: OutlinedButton.styleFrom(
                  padding: const EdgeInsets.symmetric(vertical: 14),
                ),
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
            const SizedBox(height: 16),
          ],
        ),
      ),
    );
  }
}

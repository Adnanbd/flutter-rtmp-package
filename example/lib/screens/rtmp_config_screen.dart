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

  VideoResolution _selectedRes = VideoResolution.hd720;
  VideoOrientation _selectedOrient = VideoOrientation.portrait;
  VideoInput _selectedVideoInput = VideoInput.device;
  AudioInput _selectedAudioInput = AudioInput.mic;

  final List<_SponsorItem> _sponsors = [];
  final _picker = ImagePicker();

  List<UsbDeviceInfo> _usbVideoDevices = [];
  List<UsbAudioDeviceInfo> _usbAudioDevices = [];
  UsbDeviceInfo? _selectedUsbVideoDevice;
  UsbAudioDeviceInfo? _selectedUsbAudioDevice;
  bool _loadingUsbDevices = false;

  static const _posX = [0.02, 0.39, 0.76];
  static const _posLabels = ['Left', 'Middle', 'Right'];

  // Temporary controller for USB device discovery only
  final _controller = RtmpBroadcastController();

  StreamConfig _buildConfig() {
    StreamConfig base;
    if (_selectedOrient == VideoOrientation.portrait) {
      base = _selectedRes == VideoResolution.hd720 ? StreamConfig.youtube720Portrait : StreamConfig.youtube1080Portrait;
    } else {
      base = _selectedRes == VideoResolution.hd720
          ? StreamConfig.youtube720Landscape
          : StreamConfig.youtube1080Landscape;
    }
    return StreamConfig(
      width: base.width,
      height: base.height,
      fps: base.fps,
      videoBitrate: base.videoBitrate,
      keyframeIntervalSeconds: base.keyframeIntervalSeconds,
      orientation: base.orientation,
      initialFacing: base.initialFacing,
      videoInput: _selectedVideoInput,
      audioInput: _selectedAudioInput,
      usbVideoDeviceId: _selectedUsbVideoDevice?.deviceId,
      usbAudioDeviceId: _selectedUsbAudioDevice?.deviceId,
    );
  }

  Future<void> _loadUsbDevices() async {
    setState(() => _loadingUsbDevices = true);
    try {
      final video = await _controller.listUsbVideoDevices();
      final audio = await _controller.listUsbAudioDevices();
      setState(() {
        _usbVideoDevices = video;
        _usbAudioDevices = audio;
        if (_selectedUsbVideoDevice != null &&
            !video.any((d) => d.deviceId == _selectedUsbVideoDevice!.deviceId)) {
          _selectedUsbVideoDevice = null;
        }
        if (_selectedUsbAudioDevice != null &&
            !audio.any((d) => d.deviceId == _selectedUsbAudioDevice!.deviceId)) {
          _selectedUsbAudioDevice = null;
        }
      });
    } catch (_) {} finally {
      if (mounted) setState(() => _loadingUsbDevices = false);
    }
  }

  Future<void> _requestPermission(UsbDeviceInfo device) async {
    try {
      final granted = await _controller.requestUsbPermission(device.deviceId);
      if (granted) {
        setState(() => _selectedUsbVideoDevice = device);
        _showSnack('Permission granted: ${device.productName}');
      } else {
        _showSnack('Permission denied for ${device.productName}');
      }
    } catch (e) {
      _showSnack('Error: $e');
    }
  }

  Future<void> _addSponsor() async {
    final file = await _picker.pickImage(source: ImageSource.gallery, imageQuality: 90);
    if (file == null) return;
    final bytes = await file.readAsBytes();
    final taken = _sponsors.map((s) => s.positionIndex).toSet();
    final free = [0, 1, 2].firstWhere((p) => !taken.contains(p), orElse: () => 0);
    setState(() => _sponsors.add(_SponsorItem(bytes, free)));
  }

  Future<void> _replaceSponsorImage(int index) async {
    final file = await _picker.pickImage(source: ImageSource.gallery, imageQuality: 90);
    if (file == null) return;
    final bytes = await file.readAsBytes();
    setState(() => _sponsors[index].bytes = bytes);
  }

  List<SponsorOverlay> _buildSponsors() {
    return _sponsors
        .map(
          (s) => SponsorOverlay(
            bytes: s.bytes,
            position: OverlayPosition(x: _posX[s.positionIndex], y: 0.02, width: 0.22, height: 0.08),
          ),
        )
        .toList();
  }

  Future<void> _connect() async {
    if (_selectedVideoInput == VideoInput.usb && _selectedUsbVideoDevice == null) {
      _showSnack('Select and grant permission for a USB video device first');
      return;
    }
    final url = _urlCtrl.text.trim();
    final key = _keyCtrl.text.trim();
    if (url.isEmpty || key.isEmpty) {
      _showSnack('Enter RTMP URL and stream key');
      return;
    }
    setState(() => _connecting = true);
    try {
      final controller = RtmpBroadcastController();
      final config = _buildConfig();
      await controller.initPreview(config: config);
      await controller.configure(rtmpUrl: url, rtmpKey: key, sponsors: _buildSponsors(), config: config);
      await controller.setAppOrientation(config.orientation);
      if (!mounted) return;
      await Navigator.of(context).push(MaterialPageRoute(builder: (_) => CameraScreen(controller: controller)));
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
    _controller.dispose();
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
                child: SizedBox(width: 60, height: 60, child: Image.memory(sponsor.bytes, fit: BoxFit.contain)),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      Text('Sponsor ${index + 1}', style: const TextStyle(fontWeight: FontWeight.w600)),
                      const Spacer(),
                      IconButton(
                        icon: const Icon(Icons.close, size: 18),
                        onPressed: () => setState(() => _sponsors.removeAt(index)),
                        visualDensity: VisualDensity.compact,
                      ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  const Text('Position on stream', style: TextStyle(fontSize: 12, color: Colors.grey)),
                  const SizedBox(height: 4),
                  SegmentedButton<int>(
                    segments: [
                      for (var p = 0; p < 3; p++)
                        ButtonSegment<int>(
                          value: p,
                          label: Text(_posLabels[p], style: const TextStyle(fontSize: 10)),
                          enabled: p == sponsor.positionIndex ||
                              !_sponsors.asMap().entries
                                  .where((e) => e.key != index)
                                  .any((e) => e.value.positionIndex == p),
                        ),
                    ],
                    selected: {sponsor.positionIndex},
                    onSelectionChanged: (s) => setState(() => sponsor.positionIndex = s.first),
                    style: const ButtonStyle(
                      visualDensity: VisualDensity.compact,
                      tapTargetSize: MaterialTapTargetSize.shrinkWrap,
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildUsbVideoSection() {
    if (_selectedVideoInput != VideoInput.usb) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 12),
        Row(
          children: [
            const Text('USB Camera', style: TextStyle(fontSize: 13, color: Colors.grey)),
            const Spacer(),
            TextButton.icon(
              onPressed: _loadingUsbDevices ? null : _loadUsbDevices,
              icon: _loadingUsbDevices
                  ? const SizedBox(width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2))
                  : const Icon(Icons.refresh, size: 16),
              label: const Text('Scan', style: TextStyle(fontSize: 13)),
            ),
          ],
        ),
        if (_usbVideoDevices.isEmpty)
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              border: Border.all(color: Colors.grey.shade300),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Text(
              'No USB cameras detected. Connect a UVC camera and tap Scan.',
              style: TextStyle(fontSize: 13, color: Colors.grey),
            ),
          )
        else
          for (final device in _usbVideoDevices)
            _buildUsbDeviceTile(device),
      ],
    );
  }

  Widget _buildUsbDeviceTile(UsbDeviceInfo device) {
    final isSelected = _selectedUsbVideoDevice?.deviceId == device.deviceId;
    return Card(
      margin: const EdgeInsets.only(bottom: 6),
      color: isSelected ? Colors.blue.shade50 : null,
      child: ListTile(
        dense: true,
        leading: Icon(
          Icons.videocam_outlined,
          color: isSelected ? Colors.blue : Colors.grey,
        ),
        title: Text(device.productName, style: const TextStyle(fontSize: 14)),
        subtitle: Text('${device.manufacturerName} · id:${device.deviceId}',
            style: const TextStyle(fontSize: 11)),
        trailing: device.hasPermission
            ? (isSelected
                ? const Icon(Icons.check_circle, color: Colors.blue)
                : TextButton(
                    onPressed: () => setState(() => _selectedUsbVideoDevice = device),
                    child: const Text('Select', style: TextStyle(fontSize: 12)),
                  ))
            : TextButton(
                onPressed: () => _requestPermission(device),
                child: const Text('Allow', style: TextStyle(fontSize: 12)),
              ),
      ),
    );
  }

  Widget _buildUsbAudioSection() {
    if (_selectedAudioInput != AudioInput.usb) return const SizedBox.shrink();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const SizedBox(height: 12),
        const Text('USB Audio Device', style: TextStyle(fontSize: 13, color: Colors.grey)),
        const SizedBox(height: 6),
        if (_usbAudioDevices.isEmpty)
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(
              border: Border.all(color: Colors.grey.shade300),
              borderRadius: BorderRadius.circular(8),
            ),
            child: const Text(
              'No USB audio devices detected. Tap Scan on the camera section above.',
              style: TextStyle(fontSize: 13, color: Colors.grey),
            ),
          )
        else
          for (final device in _usbAudioDevices)
            Card(
              margin: const EdgeInsets.only(bottom: 6),
              color: _selectedUsbAudioDevice?.deviceId == device.deviceId ? Colors.blue.shade50 : null,
              child: ListTile(
                dense: true,
                leading: Icon(
                  Icons.mic_outlined,
                  color: _selectedUsbAudioDevice?.deviceId == device.deviceId ? Colors.blue : Colors.grey,
                ),
                title: Text(device.productName, style: const TextStyle(fontSize: 14)),
                trailing: _selectedUsbAudioDevice?.deviceId == device.deviceId
                    ? const Icon(Icons.check_circle, color: Colors.blue)
                    : TextButton(
                        onPressed: () => setState(() => _selectedUsbAudioDevice = device),
                        child: const Text('Select', style: TextStyle(fontSize: 12)),
                      ),
              ),
            ),
      ],
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
            const SizedBox(height: 24),
            const Text('Stream Settings', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
            const SizedBox(height: 12),
            _buildDropdownRow('Resolution', [
              DropdownMenuItem(value: VideoResolution.hd720, child: const Text('720p')),
              DropdownMenuItem(value: VideoResolution.fhd1080, child: const Text('1080p')),
            ], _selectedRes, (v) { if (v != null) setState(() => _selectedRes = v); }),
            const SizedBox(height: 12),
            _buildDropdownRow('Orientation', [
              DropdownMenuItem(value: VideoOrientation.portrait, child: const Text('Portrait')),
              DropdownMenuItem(value: VideoOrientation.landscape, child: const Text('Landscape')),
            ], _selectedOrient, (v) { if (v != null) setState(() => _selectedOrient = v); }),
            const SizedBox(height: 12),
            _buildDropdownRow('Video Input', [
              DropdownMenuItem(value: VideoInput.device, child: const Text('Device Camera')),
              DropdownMenuItem(value: VideoInput.usb, child: const Text('USB / HDMI Capture')),
            ], _selectedVideoInput, (v) {
              if (v != null) {
                setState(() {
                  _selectedVideoInput = v;
                  if (v == VideoInput.usb) _loadUsbDevices();
                });
              }
            }),
            _buildUsbVideoSection(),
            const SizedBox(height: 12),
            _buildDropdownRow('Audio Input', [
              DropdownMenuItem(value: AudioInput.mic, child: const Text('Phone Microphone')),
              DropdownMenuItem(value: AudioInput.usb, child: const Text('USB Audio Device')),
            ], _selectedAudioInput, (v) {
              if (v != null) setState(() => _selectedAudioInput = v);
            }),
            _buildUsbAudioSection(),
            const SizedBox(height: 28),
            Row(
              children: [
                const Text('Sponsor Images', style: TextStyle(fontSize: 15, fontWeight: FontWeight.w600)),
                const SizedBox(width: 8),
                Text('optional · up to 3', style: TextStyle(fontSize: 13, color: Colors.grey.shade500)),
              ],
            ),
            const SizedBox(height: 10),
            for (var i = 0; i < _sponsors.length; i++) _buildSponsorCard(i),
            if (_sponsors.length < 3)
              OutlinedButton.icon(
                onPressed: _addSponsor,
                icon: const Icon(Icons.add_photo_alternate_outlined, size: 20),
                label: const Text('Add Sponsor Image'),
                style: OutlinedButton.styleFrom(padding: const EdgeInsets.symmetric(vertical: 14)),
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

  Widget _buildDropdownRow<T>(String label, List<DropdownMenuItem<T>> items, T value, void Function(T?) onChanged) {
    return Row(
      children: [
        SizedBox(width: 100, child: Text(label)),
        Expanded(
          child: DropdownButtonFormField<T>(
            value: value,
            decoration: const InputDecoration(border: OutlineInputBorder()),
            items: items,
            onChanged: onChanged,
          ),
        ),
      ],
    );
  }
}

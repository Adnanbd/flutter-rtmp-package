import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

import 'build_tab.dart';
import 'mock_match.dart';
import 'overlay_samples.dart';
import 'overlay_studio.dart';
import 'studio_scenarios.dart';

/// Opens the Overlay Studio over the camera/stream screen. The stream keeps running underneath.
Future<void> showOverlayStudio(BuildContext context, OverlayStudio studio, AutoDemo demo) =>
    showModalBottomSheet<void>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      barrierColor: Colors.transparent,
      backgroundColor: Theme.of(context).colorScheme.surface.withValues(alpha: 0.94),
      builder: (_) => OverlayStudioSheet(studio: studio, demo: demo),
    );

class OverlayStudioSheet extends StatefulWidget {
  const OverlayStudioSheet({super.key, required this.studio, required this.demo});

  final OverlayStudio studio;
  final AutoDemo demo;

  @override
  State<OverlayStudioSheet> createState() => _OverlayStudioSheetState();
}

class _OverlayStudioSheetState extends State<OverlayStudioSheet> {
  /// Half height keeps the stream visible; tall is for editing.
  bool _tall = false;

  @override
  Widget build(BuildContext context) {
    final studio = widget.studio;
    return SizedBox(
      height: MediaQuery.sizeOf(context).height * (_tall ? 0.85 : 0.48),
      child: DefaultTabController(
        length: 4,
        child: ListenableBuilder(
          listenable: studio,
          builder: (context, _) => Column(children: [
            Row(children: [
              Expanded(
                child: TabBar(isScrollable: true, tabAlignment: TabAlignment.start, tabs: [
                  const Tab(text: 'Scenarios'),
                  const Tab(text: 'Build'),
                  Tab(text: 'Active (${studio.overlays.length})'),
                  Tab(text: 'Log (${studio.events.length})'),
                ]),
              ),
              IconButton(
                tooltip: _tall ? 'Shrink' : 'Expand',
                icon: Icon(_tall ? Icons.unfold_less : Icons.unfold_more),
                onPressed: () => setState(() => _tall = !_tall),
              ),
            ]),
            Expanded(
              child: TabBarView(children: [
                _ScenariosTab(studio: studio, demo: widget.demo),
                BuildTab(studio: studio),
                _ActiveTab(studio: studio),
                _LogTab(studio: studio),
              ]),
            ),
          ]),
        ),
      ),
    );
  }
}

class _ScenariosTab extends StatefulWidget {
  const _ScenariosTab({required this.studio, required this.demo});

  final OverlayStudio studio;
  final AutoDemo demo;

  @override
  State<_ScenariosTab> createState() => _ScenariosTabState();
}

class _ScenariosTabState extends State<_ScenariosTab> {
  @override
  Widget build(BuildContext context) {
    final s = widget.studio;
    final groups = <String, List<StudioScenario>>{};
    for (final sc in studioScenarios) {
      groups.putIfAbsent(sc.group, () => []).add(sc);
    }
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 32),
      children: [
        Card(
          child: SwitchListTile(
            title: const Text('Auto demo'),
            subtitle: const Text('A random match moment every 7 s (use while live)'),
            value: widget.demo.running,
            onChanged: (v) => setState(() => v ? widget.demo.start() : widget.demo.stop()),
          ),
        ),
        Row(children: [
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => s.clear(animate: true),
              icon: const Icon(Icons.layers_clear),
              label: const Text('Clear (animated)'),
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: OutlinedButton.icon(
              onPressed: () => s.clear(animate: false),
              icon: const Icon(Icons.clear_all),
              label: const Text('Clear (instant)'),
            ),
          ),
        ]),
        for (final entry in groups.entries) ...[
          Padding(
            padding: const EdgeInsets.fromLTRB(4, 16, 4, 4),
            child: Text(entry.key, style: Theme.of(context).textTheme.titleSmall),
          ),
          for (final sc in entry.value)
            Card(
              margin: const EdgeInsets.symmetric(vertical: 3),
              child: ListTile(
                dense: true,
                title: Text(sc.title),
                subtitle: Text(sc.tests),
                trailing: const Icon(Icons.play_circle_outline),
                onTap: () {
                  s.log('▶ ${sc.title}');
                  sc.run(s);
                },
              ),
            ),
        ],
      ],
    );
  }
}

class _ActiveTab extends StatelessWidget {
  const _ActiveTab({required this.studio});

  final OverlayStudio studio;

  @override
  Widget build(BuildContext context) {
    final s = studio;
    return ListView(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 32),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(12),
            child: ValueListenableBuilder<int>(
              valueListenable: s.scorebandWeight,
              builder: (context, w, _) => Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                Text('Scoreband weight $w  ·  sponsors keep their configure() weight',
                    style: Theme.of(context).textTheme.bodyMedium),
                Slider(
                  value: w.toDouble(),
                  max: 100,
                  divisions: 20,
                  label: '$w',
                  onChanged: (v) => s.scorebandWeight.value = v.round(),
                ),
                const Text('Applied on the next scoreband push (immediately if pushed before).',
                    style: TextStyle(fontSize: 11)),
              ]),
            ),
          ),
        ),
        if (s.overlays.isEmpty)
          const Padding(
            padding: EdgeInsets.all(24),
            child: Center(child: Text('No dynamic overlays. Use Scenarios or Build.')),
          ),
        for (final o in s.overlays.values.toList()) _OverlayCard(studio: s, overlay: o),
      ],
    );
  }
}

class _OverlayCard extends StatelessWidget {
  const _OverlayCard({required this.studio, required this.overlay});

  final OverlayStudio studio;
  final StudioOverlay overlay;

  Color _stateColor() => switch (overlay.state) {
        'visible' => Colors.green,
        'hidden' => Colors.grey,
        'removing' => Colors.red,
        _ => Colors.orange,
      };

  Future<OverlayContent?> _freshContent() async {
    final m = studio.match;
    return switch (overlay.kind) {
      OverlayKind.image => ImageContent(await OverlaySamples.badgePng('UPDATED ${DateTime.now().second}s', Colors.deepOrange)),
      OverlayKind.text => TextContent(m.playerCard(), style: overlay.style ?? const TextOverlayStyle(fontSizePx: 36, background: Color(0xCC2E7D32), paddingPx: 12)),
      OverlayKind.ticker => (overlay.content as TickerContent?)?.copyWith(text: m.pick(MockMatch.headlinesEnglish)) ??
          TickerContent(m.pick(MockMatch.headlinesEnglish)),
      OverlayKind.gif => null,
      // New sponsor order → the carousel restarts at its first item.
      OverlayKind.carousel => CarouselContent(
          await OverlaySamples.sponsorItems(shuffle: true),
          interval: const Duration(seconds: 3),
        ),
    };
  }

  @override
  Widget build(BuildContext context) {
    final o = overlay;
    final s = studio;
    final d = o.duration;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 4),
        child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
          Row(children: [
            Icon(Icons.circle, size: 10, color: _stateColor()),
            const SizedBox(width: 6),
            Expanded(child: Text('${o.id}  ·  ${o.label}', style: const TextStyle(fontWeight: FontWeight.w600))),
            Text('${o.state} · ${d == null ? '∞' : '${d.inSeconds}s'}', style: const TextStyle(fontSize: 12)),
          ]),
          Row(children: [
            const SizedBox(width: 56, child: Text('Weight')),
            Expanded(
              child: Slider(
                value: o.weight.toDouble(),
                max: 100,
                divisions: 20,
                label: '${o.weight}',
                onChanged: (_) {},
                onChangeEnd: (v) => s.setWeight(o, v.round()),
              ),
            ),
            Text('${o.weight}'),
          ]),
          Wrap(spacing: 4, runSpacing: 0, children: [
            for (final sec in const [null, 5, 15, 60])
              ActionChip(
                visualDensity: VisualDensity.compact,
                label: Text(sec == null ? '∞' : '${sec}s'),
                onPressed: () => s.setDuration(o, sec == null ? null : Duration(seconds: sec)),
              ),
            ActionChip(
              visualDensity: VisualDensity.compact,
              avatar: const Icon(Icons.timer_outlined, size: 16),
              label: const Text('Restart'),
              onPressed: () => s.restartTimer(o),
            ),
          ]),
          const Padding(
            padding: EdgeInsets.only(top: 4),
            child: Text('Quick update', style: TextStyle(fontSize: 12, fontWeight: FontWeight.w600)),
          ),
          Wrap(spacing: 4, runSpacing: 0, crossAxisAlignment: WrapCrossAlignment.center, children: [
            if (isText) ...[
              _chip(Icons.edit_note, 'Text…', () => _editText(context)),
              _chip(Icons.text_decrease, 'A−', () => s.changeFontSize(o, -6)),
              _chip(Icons.text_increase, 'A+', () => s.changeFontSize(o, 6)),
              _chip(Icons.format_color_fill, 'Background', () => s.cycleBackground(o)),
              if (o.kind == OverlayKind.text)
                _chip(Icons.wrap_text, (o.style?.maxLines ?? 1) > 1 ? 'Single line' : 'Wrap 4 lines', () => s.toggleWrap(o)),
            ],
            _chip(Icons.zoom_out, 'Size −', () => s.resize(o, -10)),
            _chip(Icons.zoom_in, 'Size +', () => s.resize(o, 10)),
            PopupMenuButton<String>(
              tooltip: 'Move',
              onSelected: (name) => s.moveTo(o, name),
              itemBuilder: (_) => [
                for (final name in const ['top-left', 'top-right', 'center', 'bottom', 'bottom-left'])
                  PopupMenuItem(value: name, child: Text(name)),
              ],
              child: const Chip(
                visualDensity: VisualDensity.compact,
                avatar: Icon(Icons.open_with, size: 16),
                label: Text('Move ▾'),
              ),
            ),
            if (o.kind == OverlayKind.image || o.kind == OverlayKind.gif)
              _chip(Icons.photo_library_outlined, 'Gallery…', () async {
                final media = await OverlaySamples.pickFromGallery();
                if (media == null) return;
                s.log('picked ${media.name} (${media.sizeLabel}, ${media.isGif ? 'GIF' : 'still image'})',
                    isError: o.kind == OverlayKind.gif && !media.isGif);
                await s.update(o, 'content ← ${media.name}',
                    content: o.kind == OverlayKind.gif ? GifContent(media.bytes) : ImageContent(media.bytes));
              }),
            if (o.kind != OverlayKind.gif)
              _chip(Icons.shuffle, 'Mock content', () async {
                final content = await _freshContent();
                if (content != null) await s.update(o, 'mock content', content: content);
              }),
          ]),
          Row(mainAxisAlignment: MainAxisAlignment.end, children: [
            IconButton(
              tooltip: o.state == 'hidden' ? 'Show' : 'Hide',
              icon: Icon(o.state == 'hidden' ? Icons.visibility : Icons.visibility_off),
              onPressed: () => s.toggle(o),
            ),
            IconButton(
              tooltip: 'Remove (animated)',
              icon: const Icon(Icons.delete_outline),
              onPressed: () => s.remove(o),
            ),
            IconButton(
              tooltip: 'Remove instantly',
              icon: const Icon(Icons.delete_forever),
              onPressed: () => s.remove(o, animate: false),
            ),
          ]),
        ]),
      ),
    );
  }

  bool get isText => overlay.kind == OverlayKind.text || overlay.kind == OverlayKind.ticker;

  Widget _chip(IconData icon, String label, VoidCallback onPressed) => ActionChip(
        visualDensity: VisualDensity.compact,
        avatar: Icon(icon, size: 16),
        label: Text(label),
        onPressed: onPressed,
      );

  Future<void> _editText(BuildContext context) async {
    final ctrl = TextEditingController(text: overlay.text ?? '');
    final text = await showDialog<String>(
      context: context,
      builder: (ctx) => AlertDialog(
        title: Text('Edit ${overlay.id}'),
        content: TextField(controller: ctrl, autofocus: true, minLines: 1, maxLines: 6),
        actions: [
          TextButton(onPressed: () => Navigator.pop(ctx), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(ctx, ctrl.text), child: const Text('Update')),
        ],
      ),
    );
    ctrl.dispose();
    if (text != null && text.trim().isNotEmpty) await studio.setText(overlay, text);
  }
}

class _LogTab extends StatelessWidget {
  const _LogTab({required this.studio});

  final OverlayStudio studio;

  @override
  Widget build(BuildContext context) {
    final events = studio.events;
    final start = events.isEmpty ? DateTime.now() : events.last.at;
    return Column(children: [
      Align(
        alignment: Alignment.centerRight,
        child: TextButton.icon(onPressed: studio.clearLog, icon: const Icon(Icons.delete_sweep), label: const Text('Clear log')),
      ),
      Expanded(
        child: ListView.builder(
          padding: const EdgeInsets.fromLTRB(12, 0, 12, 32),
          itemCount: events.length,
          itemBuilder: (context, i) {
            final e = events[i];
            final t = e.at.difference(start).inMilliseconds / 1000;
            return Padding(
              padding: const EdgeInsets.symmetric(vertical: 2),
              child: Text(
                '${t.toStringAsFixed(1).padLeft(6)}s  ${e.message}',
                style: TextStyle(fontFamily: 'monospace', fontSize: 12, color: e.isError ? Colors.red : null),
              ),
            );
          },
        ),
      ),
    ]);
  }
}

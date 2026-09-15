import 'package:flutter/material.dart';
import 'package:flutter_rtmp_broadcaster/flutter_rtmp_broadcaster.dart';

import 'overlay_samples.dart';
import 'overlay_studio.dart';

enum _Spot { topLeft, topRight, center, bottomBand }

/// Build any dynamic overlay from every option the API has.
class BuildTab extends StatefulWidget {
  const BuildTab({super.key, required this.studio});

  final OverlayStudio studio;

  @override
  State<BuildTab> createState() => _BuildTabState();
}

class _BuildTabState extends State<BuildTab> with AutomaticKeepAliveClientMixin {
  OverlayKind _kind = OverlayKind.text;
  _Spot _spot = _Spot.topRight;
  final _text = TextEditingController(text: OverlaySamples.english);
  final _id = TextEditingController();
  bool _usePx = false;
  double _width = 40;
  double _weight = 50;
  int? _durationSec;
  OverlayAnimationType _enter = OverlayAnimationType.slide;
  OverlayAnimationType _exit = OverlayAnimationType.slide;
  OverlayEdge _edge = OverlayEdge.right;
  OverlayEasing _easing = OverlayEasing.easeOut;
  double _animMs = 400;
  PickedMedia? _pickedImage;
  PickedMedia? _pickedGif;
  double _fontSize = 40;
  double _maxLines = 1;
  TextOverlayAlign _align = TextOverlayAlign.start;
  bool _background = true;
  bool _customFont = false;
  bool _tickerCycle = false;
  double _speed = 120;
  double _cycleSec = 10;
  bool _loop = true;
  double _gap = 33;
  TickerDirection _direction = TickerDirection.auto;
  List<PickedMedia> _carouselMedia = const [];
  double _intervalSec = 4;
  bool _firstOverride = false;
  double _firstSec = 10;
  CarouselTransitionType _transition = CarouselTransitionType.crossfade;
  double _transitionMs = 500;
  bool _slotBox = true;
  double _slotHeight = 10;

  @override
  bool get wantKeepAlive => true;

  @override
  void dispose() {
    _text.dispose();
    _id.dispose();
    super.dispose();
  }

  OverlayPlacement _placement() {
    final w = _usePx ? OverlayLength.px(_width * 12) : OverlayLength.percent(_width);
    final p = switch (_spot) {
      _Spot.topLeft => OverlayPlacement(left: const OverlayLength.px(24), top: const OverlayLength.px(24), width: w),
      _Spot.topRight => OverlayPlacement(right: const OverlayLength.px(24), top: const OverlayLength.px(24), width: w),
      _Spot.center => OverlayPlacement(width: w),
      _Spot.bottomBand => OverlayPlacement(bottom: const OverlayLength.percent(20), width: w),
    };
    return _kind == OverlayKind.carousel && _slotBox ? p.copyWith(height: OverlayLength.percent(_slotHeight)) : p;
  }

  Future<OverlayContent> _content() async {
    final font = _customFont ? await OverlaySamples.customFont() : null;
    final style = TextOverlayStyle(
      fontSizePx: _fontSize,
      background: _background ? const Color(0xCC1565C0) : null,
      paddingPx: 12,
      fontTtf: font,
      maxLines: _maxLines.round(),
      align: _align,
    );
    return switch (_kind) {
      OverlayKind.image => ImageContent(_pickedImage?.bytes ?? await OverlaySamples.badgePng('LIVE', Colors.red)),
      OverlayKind.gif => GifContent(_pickedGif?.bytes ?? await OverlaySamples.spinnerGif()),
      OverlayKind.text => TextContent(_text.text, style: style),
      OverlayKind.ticker => TickerContent(
          _text.text,
          style: _background ? TextOverlayStyle(fontSizePx: _fontSize, background: const Color(0xB3000000), fontTtf: font) : style,
          speedPxPerSec: _tickerCycle ? null : _speed,
          cycleDuration: _tickerCycle ? Duration(milliseconds: (_cycleSec * 1000).round()) : null,
          loop: _loop,
          loopGap: OverlayLength.percent(_gap),
          direction: _direction,
        ),
      OverlayKind.carousel => CarouselContent(
          _carouselMedia.isEmpty
              ? await OverlaySamples.sponsorItems(firstInterval: _firstOverride ? _secs(_firstSec) : null)
              : [
                  for (final (i, m) in _carouselMedia.indexed)
                    CarouselItem(m.isGif ? GifContent(m.bytes) : ImageContent(m.bytes),
                        interval: i == 0 && _firstOverride ? _secs(_firstSec) : null),
                ],
          interval: _secs(_intervalSec),
          transition: CarouselTransition(
            type: _transition,
            durationMs: _transitionMs.round(),
            easing: _easing,
            edge: _edge,
          ),
        ),
    };
  }

  static Duration _secs(double s) => Duration(milliseconds: (s * 1000).round());

  OverlayAnimation _anim(OverlayAnimationType type) =>
      OverlayAnimation(type: type, edge: _edge, easing: _easing, durationMs: _animMs.round());

  Future<void> _add() async {
    final s = widget.studio;
    final id = _id.text.trim().isEmpty ? s.nextId('custom_${_kind.name}') : _id.text.trim();
    await s.add(
      DynamicOverlay(
        id: id,
        content: await _content(),
        placement: _placement(),
        weight: _weight.round(),
        duration: _durationSec == null ? null : Duration(seconds: _durationSec!),
        enter: _anim(_enter),
        exit: _anim(_exit),
      ),
      label: switch (_kind) {
        OverlayKind.image when _pickedImage != null => 'Image ${_pickedImage!.name}',
        OverlayKind.gif when _pickedGif != null => 'GIF ${_pickedGif!.name}',
        OverlayKind.carousel when _carouselMedia.isNotEmpty => 'Carousel · ${_carouselMedia.length} picked',
        _ => 'Custom ${_kind.name}',
      },
      kind: _kind,
    );
  }

  @override
  Widget build(BuildContext context) {
    super.build(context);
    final isText = _kind == OverlayKind.text || _kind == OverlayKind.ticker;
    return ListView(
      padding: const EdgeInsets.fromLTRB(16, 8, 16, 32),
      children: [
        _chips('Content', OverlayKind.values, _kind, (v) => _kind = v),
        if (_kind == OverlayKind.image || _kind == OverlayKind.gif) _mediaSource(),
        if (_kind == OverlayKind.carousel) ..._carouselOptions(),
        TextField(
          controller: _id,
          decoration: const InputDecoration(labelText: 'Id (empty = auto; try "scoreband" for an error)', isDense: true),
        ),
        if (isText) ...[
          const SizedBox(height: 8),
          TextField(controller: _text, maxLines: 3, minLines: 1, decoration: const InputDecoration(labelText: 'Text', isDense: true)),
          Wrap(spacing: 8, children: [
            for (final (label, sample) in [
              ('English', OverlaySamples.english),
              ('বাংলা', OverlaySamples.bangla),
              ('العربية', OverlaySamples.arabic),
              ('Long', List.filled(12, OverlaySamples.english).join(' · ')),
            ])
              ActionChip(
                label: Text(label),
                onPressed: () => setState(() {
                  _text.text = sample;
                  if (label == 'Long' && _kind == OverlayKind.text && _maxLines < 2) _maxLines = 6;
                }),
              ),
          ]),
          _slider('Font ${_fontSize.round()} px', _fontSize, 16, 96, (v) => _fontSize = v),
          if (_kind == OverlayKind.text) ...[
            _slider(_maxLines < 2 ? 'Max lines 1 (no wrap)' : 'Max lines ${_maxLines.round()}', _maxLines, 1, 10, (v) => _maxLines = v),
            if (_maxLines >= 2) _chips('Align', TextOverlayAlign.values, _align, (v) => _align = v),
          ],
          _switch('Background band', _background, (v) => _background = v),
          _switch('Custom font (RobotoCondensed Bold)', _customFont, (v) => _customFont = v),
        ],
        if (_kind == OverlayKind.ticker) ...[
          _switch('Speed by cycle duration', _tickerCycle, (v) => _tickerCycle = v),
          if (_tickerCycle)
            _slider('Cycle ${_cycleSec.toStringAsFixed(1)} s', _cycleSec, 2, 30, (v) => _cycleSec = v)
          else
            _slider('Speed ${_speed.round()} px/s', _speed, 30, 600, (v) => _speed = v),
          _switch('Loop (off = one pass → completed)', _loop, (v) => _loop = v),
          _slider('Loop gap ${_gap.round()} % of band', _gap, 0, 100, (v) => _gap = v),
          _chips('Direction', TickerDirection.values, _direction, (v) => _direction = v),
        ],
        const Divider(),
        _chips('Position', _Spot.values, _spot, (v) => _spot = v),
        _switch('Width in encoder px (× 12)', _usePx, (v) => _usePx = v),
        _slider(_usePx ? 'Width ${(_width * 12).round()} px' : 'Width ${_width.round()} %', _width, 5, 100, (v) => _width = v),
        _slider('Weight ${_weight.round()}', _weight, 0, 100, (v) => _weight = v),
        _chips<int?>('Duration', const [null, 5, 10, 30, 60], _durationSec, (v) => _durationSec = v,
            label: (v) => v == null ? '∞' : '${v}s'),
        const Divider(),
        _chips('Enter', OverlayAnimationType.values, _enter, (v) => _enter = v),
        _chips('Exit', OverlayAnimationType.values, _exit, (v) => _exit = v),
        _chips('Slide / push edge', OverlayEdge.values, _edge, (v) => _edge = v),
        _chips('Easing', OverlayEasing.values, _easing, (v) => _easing = v),
        _slider('Anim ${_animMs.round()} ms', _animMs, 0, 3000, (v) => _animMs = v),
        const SizedBox(height: 8),
        FilledButton.icon(onPressed: _add, icon: const Icon(Icons.add), label: const Text('Add overlay')),
      ],
    );
  }

  /// Carousel items (sample sponsor logos or several gallery files), timing and transition.
  List<Widget> _carouselOptions() => [
        Card(
          margin: const EdgeInsets.symmetric(vertical: 6),
          child: Padding(
            padding: const EdgeInsets.all(8),
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(
                _carouselMedia.isEmpty
                    ? 'Items: 4 generated sponsor logos'
                    : 'Items: ${_carouselMedia.length} from gallery '
                        '(${_carouselMedia.where((m) => m.isGif).length} GIF)',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
              if (_carouselMedia.isNotEmpty)
                SizedBox(
                  height: 56,
                  child: ListView(scrollDirection: Axis.horizontal, children: [
                    for (final m in _carouselMedia)
                      Padding(
                        padding: const EdgeInsets.only(right: 6, top: 4),
                        child: Image.memory(m.bytes, width: 48, height: 48, fit: BoxFit.contain, gaplessPlayback: true),
                      ),
                  ]),
                ),
              Wrap(spacing: 6, children: [
                TextButton.icon(
                  onPressed: _pickCarousel,
                  icon: const Icon(Icons.photo_library_outlined, size: 18),
                  label: const Text('Gallery (multiple)'),
                ),
                if (_carouselMedia.isNotEmpty)
                  TextButton(onPressed: () => setState(() => _carouselMedia = const []), child: const Text('Use samples')),
              ]),
            ]),
          ),
        ),
        _slider('Interval ${_intervalSec.toStringAsFixed(1)} s', _intervalSec, 0.5, 20, (v) => _intervalSec = v),
        _switch('First item interval override', _firstOverride, (v) => _firstOverride = v),
        if (_firstOverride) _slider('First item ${_firstSec.toStringAsFixed(1)} s', _firstSec, 0.5, 30, (v) => _firstSec = v),
        _chips('Transition', CarouselTransitionType.values, _transition, (v) => _transition = v),
        if (_transition != CarouselTransitionType.cut)
          _slider('Transition ${_transitionMs.round()} ms', _transitionMs, 0, 3000, (v) => _transitionMs = v),
        _switch('Fixed slot height (off = from items)', _slotBox, (v) => _slotBox = v),
        if (_slotBox) _slider('Slot height ${_slotHeight.round()} %', _slotHeight, 3, 60, (v) => _slotHeight = v),
        const Text('Push edge and easing: see the animation section below. Transition must be shorter than every interval.',
            style: TextStyle(fontSize: 12)),
      ];

  Future<void> _pickCarousel() async {
    final media = await OverlaySamples.pickManyFromGallery();
    if (media.isEmpty || !mounted) return;
    setState(() => _carouselMedia = media);
    widget.studio.log('picked ${media.length} carousel items (${media.where((m) => m.isGif).length} GIF)');
  }

  /// Source for image / GIF content: generated sample or a file from the gallery, with a live preview.
  Widget _mediaSource() {
    final gif = _kind == OverlayKind.gif;
    final picked = gif ? _pickedGif : _pickedImage;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Row(children: [
          ClipRRect(
            borderRadius: BorderRadius.circular(6),
            child: Container(
              width: 72,
              height: 72,
              color: Colors.black26,
              child: picked != null
                  ? Image.memory(picked.bytes, fit: BoxFit.contain, gaplessPlayback: true)
                  : gif
                      ? Image.asset('assets/overlays/spinner.gif', fit: BoxFit.contain)
                      : const Center(child: Text('LIVE\nbadge', textAlign: TextAlign.center, style: TextStyle(fontSize: 11))),
            ),
          ),
          const SizedBox(width: 10),
          Expanded(
            child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
              Text(picked == null ? (gif ? 'Sample spinner GIF' : 'Generated LIVE badge') : picked.name,
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontWeight: FontWeight.w600)),
              Text(
                picked == null
                    ? 'Pick from gallery to test your own file'
                    : '${picked.sizeLabel} · ${picked.isGif ? 'GIF' : 'still image'}'
                        '${gif && !picked.isGif ? ' — not a GIF, expect OVERLAY_DECODE_FAILED' : ''}',
                style: TextStyle(fontSize: 12, color: gif && picked != null && !picked.isGif ? Colors.red : null),
              ),
              Wrap(spacing: 6, children: [
                TextButton.icon(
                  onPressed: _pick,
                  icon: const Icon(Icons.photo_library_outlined, size: 18),
                  label: const Text('Gallery'),
                ),
                if (picked != null)
                  TextButton(
                    onPressed: () => setState(() => gif ? _pickedGif = null : _pickedImage = null),
                    child: const Text('Use sample'),
                  ),
              ]),
            ]),
          ),
        ]),
      ),
    );
  }

  Future<void> _pick() async {
    final gif = _kind == OverlayKind.gif;
    final media = await OverlaySamples.pickFromGallery();
    if (media == null || !mounted) return;
    setState(() => gif ? _pickedGif = media : _pickedImage = media);
    widget.studio.log('picked ${media.name} (${media.sizeLabel}, ${media.isGif ? 'GIF' : 'still image'})',
        isError: gif && !media.isGif);
  }

  Widget _switch(String title, bool value, ValueSetter<bool> set) => SwitchListTile(
        dense: true,
        contentPadding: EdgeInsets.zero,
        title: Text(title),
        value: value,
        onChanged: (v) => setState(() => set(v)),
      );

  Widget _slider(String title, double value, double min, double max, ValueSetter<double> set) => Row(children: [
        SizedBox(width: 140, child: Text(title)),
        Expanded(child: Slider(value: value, min: min, max: max, onChanged: (v) => setState(() => set(v)))),
      ]);

  Widget _chips<T>(String title, List<T> values, T selected, ValueSetter<T> set, {String Function(T)? label}) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 4),
        child: Wrap(spacing: 6, runSpacing: 4, crossAxisAlignment: WrapCrossAlignment.center, children: [
          Text(title),
          for (final v in values)
            ChoiceChip(
              label: Text(label?.call(v) ?? (v is Enum ? v.name : '$v')),
              selected: v == selected,
              onSelected: (_) => setState(() => set(v)),
            ),
        ]),
      );
}

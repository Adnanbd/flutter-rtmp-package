enum VideoResolution { hd720, fhd1080 }

enum VideoOrientation { portrait, landscape }

enum CameraFacing { front, back }

class StreamConfig {
  const StreamConfig({
    required this.width,
    required this.height,
    required this.fps,
    required this.videoBitrate,
    required this.keyframeIntervalSeconds,
    required this.orientation,
    required this.initialFacing,
  });

  final int width;
  final int height;
  final int fps;
  final int videoBitrate;
  final int keyframeIntervalSeconds;
  final VideoOrientation orientation;
  final CameraFacing initialFacing;

  static const StreamConfig youtube720Landscape = StreamConfig(
    width: 1280,
    height: 720,
    fps: 30,
    videoBitrate: 2_500_000,
    keyframeIntervalSeconds: 2,
    orientation: VideoOrientation.landscape,
    initialFacing: CameraFacing.back,
  );

  static const StreamConfig youtube1080Landscape = StreamConfig(
    width: 1920,
    height: 1080,
    fps: 30,
    videoBitrate: 4_500_000,
    keyframeIntervalSeconds: 2,
    orientation: VideoOrientation.landscape,
    initialFacing: CameraFacing.back,
  );

  static const StreamConfig youtube720Portrait = StreamConfig(
    width: 720,
    height: 1280,
    fps: 30,
    videoBitrate: 2_500_000,
    keyframeIntervalSeconds: 2,
    orientation: VideoOrientation.portrait,
    initialFacing: CameraFacing.back,
  );

  static const StreamConfig youtube1080Portrait = StreamConfig(
    width: 1080,
    height: 1920,
    fps: 30,
    videoBitrate: 4_500_000,
    keyframeIntervalSeconds: 2,
    orientation: VideoOrientation.portrait,
    initialFacing: CameraFacing.back,
  );

  Map<String, dynamic> toMap() => {
        'width': width,
        'height': height,
        'fps': fps,
        'videoBitrate': videoBitrate,
        'keyframeIntervalSeconds': keyframeIntervalSeconds,
        'orientation': orientation.name,
        'initialFacing': initialFacing.name,
      };
}

import 'package:flutter/services.dart';
import 'package:flutter_webrtc/flutter_webrtc.dart';
import 'package:livekit_client/livekit_client.dart' as lk;

/// Snap Camera Kit `TrackProcessor` for LiveKit's
/// `CameraCaptureOptions(processor: …)`.
///
/// LiveKit calls our [init] with the local video track. We forward the
/// track's id to the native plugin via the same MethodChannel
/// [SnapCameraKitController] uses, so the native side can attach its
/// Snap-Camera-Kit-backed frame processor to the `flutter_webrtc`
/// LocalVideoTrack matching that id.
///
/// **iOS vs Android divergence:**
///   - iOS: LiveKit provides `LocalVideoTrack.createBufferTrack()` which
///     accepts a buffer publisher. Snap Camera Kit's
///     `Session.applyLens` outputs CVPixelBuffers which we publish into
///     that buffer track. Architecturally cleaner than the Android path.
///   - Android: LiveKit has no buffer-track equivalent. We implement a
///     custom `VideoCapturer` that owns a SurfaceTextureHelper, hands
///     its Surface to Snap's `Session.processor`, picks up rendered
///     frames in the helper's onFrame callback, and forwards via
///     `capturerObserver.onFrameCaptured`. See `SnapCameraVideoCapturer`
///     on the Android side. This open LiveKit issue tracks the gap:
///     livekit/client-sdk-android#843.
class SnapCameraKitProcessor
    implements lk.TrackProcessor<lk.VideoProcessorOptions> {
  const SnapCameraKitProcessor();

  static const _channel = MethodChannel('vibezo.net/snap_camera_kit_processor');

  /// Native side identifies the processor by this name in logs.
  @override
  String get name => 'snap_camera_kit_processor';

  /// LiveKit hands us the source track here. Forward its id so the native
  /// plugin can attach the Snap-Camera-Kit-backed `ExternalVideoFrameProcessing`
  /// callback (Android) or buffer publisher binding (iOS).
  @override
  Future<void> init(lk.VideoProcessorOptions options) async {
    final id = options.track.id;
    if (id == null) return;
    await _attachWithRetry(id);
  }

  /// Re-attach when the source track changes (e.g. after switchCamera).
  @override
  Future<void> restart(lk.VideoProcessorOptions options) async {
    final id = options.track.id;
    if (id == null) return;
    await _attachWithRetry(id);
  }

  /// `flutter_webrtc` registers the LocalVideoTrack in its singleton
  /// lookup map asynchronously. LiveKit calls `setProcessor` (which
  /// triggers this init) immediately after `createCameraTrack` returns
  /// — sometimes a few milliseconds before the registration completes,
  /// causing a `trackNotFound` error on the first attempt. Retry
  /// briefly to cover that race.
  Future<void> _attachWithRetry(String trackId) async {
    const maxAttempts = 10;
    const stepMs = 60;
    for (var attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        await _channel.invokeMethod<void>('attachProcessor', {
          'trackId': trackId,
        });
        return;
      } on PlatformException catch (e) {
        final transient = e.code == 'trackNotFound';
        if (!transient || attempt == maxAttempts) rethrow;
        await Future<void>.delayed(const Duration(milliseconds: stepMs));
      }
    }
  }

  /// Detach the native processor.
  @override
  Future<void> destroy() async {
    await _channel.invokeMethod<void>('detachProcessor');
  }

  @override
  Future<void> onPublish(lk.Room room) async {}

  @override
  Future<void> onUnpublish() async {}

  /// We don't substitute the track at the Dart level — frame interception
  /// happens natively via the registered processor, before the encoder.
  @override
  MediaStreamTrack? get processedTrack => null;
}

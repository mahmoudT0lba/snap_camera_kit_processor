/// Snap Camera Kit ⇄ LiveKit AR pipeline.
///
/// Public API for the vibezo app.
///
/// Internally:
///   - Dart side ([SnapCameraKitProcessor]) is a marker class implementing
///     LiveKit's `VideoProcessor` interface — registered with
///     `CameraCaptureOptions(processor: SnapCameraKitProcessor())`.
///   - Native side (Kotlin/Swift) hosts the Snap Camera Kit Session,
///     intercepts each camera frame, renders the active Lens, and
///     returns the processed frame to LiveKit's encoder.
///   - Lens loading / parameter control goes through
///     [SnapCameraKitController] via a MethodChannel.
library;

export 'src/snap_camera_kit_controller.dart';
export 'src/snap_camera_kit_processor.dart';
export 'src/snap_touch_overlay.dart';

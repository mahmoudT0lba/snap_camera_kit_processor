import 'dart:async';

import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Control surface for the Snap Camera Kit pipeline.
///
/// Talks to the native plugin over a MethodChannel. All methods are
/// awaited so callers can serialize lens changes if needed.
class SnapCameraKitController {
  SnapCameraKitController._() {
    _channel.setMethodCallHandler(_handleNativeCall);
  }
  static final SnapCameraKitController instance = SnapCameraKitController._();

  static const _channel = MethodChannel('vibezo.net/snap_camera_kit_processor');

  /// Lens groups for the current session, set by the host app from the
  /// backend prepareLive credentials. Each entry is ONE Snap Lens Group
  /// surfaced as a category in the filters picker. Empty → the picker falls
  /// back to enumerating the single initialized default group. Stored as
  /// records so this plugin needs no dependency on app-side models.
  List<({String groupId, String name, int sortOrder})> sessionGroups = const [];

  final StreamController<String?> _hints = StreamController<String?>.broadcast();

  /// Lens hint text pushed from native ("Tap to start", "Look left", …) so the
  /// app can render Snapchat-style prompts in its own UI. A `null` event means
  /// "hide all hints". Rendered by `SnapLensHintOverlay`.
  Stream<String?> get hints => _hints.stream;

  Future<dynamic> _handleNativeCall(MethodCall call) async {
    switch (call.method) {
      case 'onHint':
        final args = call.arguments as Map?;
        _hints.add(args?['text'] as String?);
        break;
    }
    return null;
  }

  /// Initialize the Snap Camera Kit Session.
  ///
  /// `apiToken` comes from the Snap Developer portal after the app's
  /// Camera Kit access application is approved. There is exactly ONE
  /// token per app — Snap does not split iOS/Android credentials.
  ///
  /// `defaultGroupId` is the default Lens Group the session loads at
  /// startup. Snap organizes lenses into groups, identified by a group
  /// ID returned from the developer portal. The backend's
  /// `/api/v1/lives/effects` endpoint returns `(lensId, groupId)` pairs;
  /// initialize uses the first available group as the warm-up default.
  ///
  /// Returns true on success. False if the API token is missing/invalid
  /// or the SDK failed to initialize (check device logs for native error
  /// details).
  Future<bool> initialize({
    required String apiToken,
    required String defaultGroupId,
  }) async {
    // Warm EVERY session group at init (not just the default) so the picker
    // settles fast on first open for any category. Sourced from [sessionGroups]
    // (set by the host before initialize); falls back to the default alone.
    final groupIds = <String>{
      defaultGroupId,
      for (final g in sessionGroups) g.groupId,
    }.where((id) => id.isNotEmpty).toList();
    final ok = await _channel.invokeMethod<bool>('initialize', {
      'apiToken': apiToken,
      'defaultGroupId': defaultGroupId,
      'groupIds': groupIds,
    });
    return ok ?? false;
  }

  /// Apply a Lens by ID + group ID.
  ///
  /// Snap fetches lens binaries from its own cloud at apply time. The
  /// first apply for a given lens may incur a brief network fetch;
  /// subsequent applies are local-cache hits.
  ///
  /// `lensId` is the lens UUID from the Snap developer portal or Lens
  /// Studio publish. `groupId` is the lens group containing it. Both are
  /// stored on our backend (in the `/api/v1/lives/effects` response)
  /// and surfaced to the picker UI.
  Future<void> applyLens({
    required String lensId,
    required String groupId,
  }) async {
    await _channel.invokeMethod<void>('applyLens', {
      'lensId': lensId,
      'groupId': groupId,
    });
  }

  /// Remove the current lens (return to raw camera).
  Future<void> clearLens() async {
    await _channel.invokeMethod<void>('clearLens');
  }

  /// Set a parameter on the currently-loaded lens. Snap calls these
  /// "remote API" values — lenses can expose tunables for the host app
  /// to drive (sliders, toggles).
  ///
  /// Common parameters depend on the lens. Lens authors expose tunables
  /// (smoothing, whitening, eyeEnlargement, etc.) via Lens Studio's
  /// remote-API blocks; the host app reads/writes them by name.
  Future<void> setLensParameter(String name, double value) async {
    await _channel.invokeMethod<void>('setLensParameter', {
      'name': name,
      'value': value,
    });
  }

  /// Switch front/back camera. Surfaced here so the host app can flip
  /// the camera through this MethodChannel rather than going through
  /// LiveKit's track API.
  Future<void> switchCamera() async {
    await _channel.invokeMethod<void>('switchCamera');
  }

  /// ANDROID ONLY — entry-device camera-input reduction. When enabled, every
  /// subsequent camera start (initial / flip / recovery) requests a 4:3
  /// CameraX preview instead of the SDK-default 16:9, cutting Camera Kit's
  /// per-frame GPU input work on weak SoCs. The Snap SDK exposes ONLY an
  /// aspect-ratio hint (no resolution parameter) — the HAL picks the actual
  /// 4:3 size; verify the selection via the `camIn=` diagnostic readout.
  /// Output/render dims are untouched. No-ops on iOS (no native handler) and
  /// on binaries older than this method (store-build gate).
  Future<void> setCameraInput43({required bool enabled}) async {
    try {
      await _channel.invokeMethod<void>('setCameraInput43', {'enabled': enabled});
    } on MissingPluginException {
      // iOS, or an Android binary that predates the handler — feature simply
      // stays off; the Dart flag is safe to ship ahead of the store build.
    }
  }

  /// Hard-reset Camera Kit's owned capture session to the FRONT camera, purging
  /// any stale rear-lens state the native singleton carried over from a previous
  /// session. Call at a fresh-session boundary, synchronously paired with the
  /// Flutter-side camera reset so both ecosystems start on the front lens before
  /// `getUserMedia` triggers.
  ///
  /// iOS-specific: Android already re-baselines its facing to front inside the
  /// native `initialize()`, so it ships no `forceFrontCamera` handler — the call
  /// no-ops there instead of throwing.
  Future<void> forceFrontCamera() async {
    try {
      await _channel.invokeMethod<void>('forceFrontCamera');
    } on MissingPluginException {
      // No native handler on this platform (Android handles it in initialize()).
    }
  }

  /// Restart Camera Kit's owned capture session if iOS stopped it while the app
  /// was backgrounded. Without this, a returning iOS streamer publishes black
  /// frames (Camera Kit gets no input) until a viewer forces a re-subscription.
  /// Call from the app-resume path; the native side ALSO restarts automatically
  /// via AVCaptureSession/`didBecomeActive` observers, so this is the explicit,
  /// ordered trigger on top of that safety net.
  ///
  /// iOS-specific: on Android Camera Kit manages its own CameraX lifecycle, so
  /// there is no handler and the call no-ops instead of throwing.
  Future<void> restartCaptureSession() async {
    try {
      await _channel.invokeMethod<void>('restartCaptureSession');
    } on MissingPluginException {
      // No native handler on this platform (Android's CameraX self-recovers).
    }
  }

  /// Power the camera producer ON (idempotent). Owned by the CameraKitEngine,
  /// NOT the WebRTC capturer — so the camera runs independently of publishing.
  /// iOS: ensures the AVCaptureSession is running. Android: binds CameraX
  /// `startPreview`. No-op where the plugin is absent.
  Future<void> startCamera() async {
    try {
      await _channel.invokeMethod<void>('startCamera');
    } on MissingPluginException {
      // No native handler on this platform.
    }
  }

  /// Power the camera producer OFF (full teardown / explicit stop). Only the
  /// engine calls this — never the publisher. No-op where the plugin is absent.
  Future<void> stopCamera() async {
    try {
      await _channel.invokeMethod<void>('stopCamera');
    } on MissingPluginException {
      // No native handler on this platform.
    }
  }

  /// Toggle the torch/flashlight on Camera Kit's owned camera. No-op on a
  /// device/lens with no torch (e.g. front camera). In the official pipeline
  /// Camera Kit owns the camera, so camera controls route here instead of
  /// LiveKit/WebRTC's track API.
  Future<void> setTorch({required bool on}) async {
    await _channel.invokeMethod<void>('setTorch', {'on': on});
  }

  /// ABSOLUTE zoom ratio on Camera Kit's owned camera — 1.0 is no zoom.
  /// Clamped natively to the device's supported range and to the app's own
  /// ceiling. Absolute (not a pinch delta) on both platforms, so the same
  /// number means the same framing everywhere.
  Future<void> setZoom(double zoom) async {
    await _channel.invokeMethod<void>('setZoom', {'zoom': zoom});
  }

  /// Exposure-compensation bias in EV on top of the camera's own auto metering.
  /// Positive is brighter. Auto-exposure stays ON — this only moves its target,
  /// so the camera keeps adapting to changing light. Clamped natively to the
  /// device's advertised range and to the app's bounds. Survives camera flips
  /// and preview restarts: the native side stores it and re-applies on rebind.
  Future<void> setExposureBias(double ev) async {
    await _channel.invokeMethod<void>('setExposureBias', {'ev': ev});
  }

  /// What the CURRENTLY BOUND camera can do — `minExposureEv`, `maxExposureEv`,
  /// `exposureEv`, `maxZoom`, `zoom`. Read (never assumed) so the settings sheet
  /// offers only ranges the hardware will honour; a control that does nothing at
  /// one end reads as a bug. Empty when the camera is not up yet.
  Future<Map<String, double>> getCameraCapabilities() async {
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('getCameraCapabilities');
      if (raw == null) return const {};
      return raw.map((k, v) => MapEntry(k.toString(), (v as num).toDouble()));
    } on MissingPluginException {
      return const {};
    } catch (_) {
      return const {};
    }
  }

  /// Reconfigure the Camera Kit output render resolution. Wires to:
  ///   - iOS: re-calls the appropriate Session configuration API
  ///     (Phase 3 detail).
  ///   - Android: replaces the Surface fed into the Camera Kit
  ///     processor at the new dimensions.
  ///
  /// Pass dimensions in OUTPUT (portrait) orientation, e.g. 720x1280
  /// for HD, 1080x1920 for Full HD.
  /// [applyLive] (Android only): resize BOTH Camera Kit outputs in place on a
  /// running capture — the thermal/adaptive resolution lever. `false` (the
  /// default) keeps the historical record-only behaviour where the dims apply
  /// at the next capture (re)start.
  Future<void> setRenderResolution({
    required int width,
    required int height,
    bool applyLive = false,
  }) async {
    await _channel.invokeMethod<void>('setRenderResolution', {
      'width': width,
      'height': height,
      'applyLive': applyLive,
    });
  }

  /// Cap the camera's CAPTURE frame rate (fps).
  ///
  /// THERMAL RELIEF. The app's framerate ladder (30→24→20→15) previously only
  /// reached the ENCODER's `maxFramerate`; the camera kept capturing at its
  /// native rate and Camera Kit kept running the lens shader over EVERY frame on
  /// the GPU, with the surplus frames simply discarded at the encoder. That
  /// discarded capture + lens work is pure heat and sat entirely outside the
  /// thermal ladder. Pushing the cap down to the capture layer is what actually
  /// reduces the work.
  ///
  ///   - iOS: pins `activeVideoMin/MaxFrameDuration` on the `AVCaptureDevice`
  ///     (the app owns the `AVCaptureSession`).
  ///   - Android: pushes a fixed `CONTROL_AE_TARGET_FPS_RANGE` through Camera2
  ///     interop onto the CameraX camera Camera Kit owns.
  ///
  /// Best-effort on BOTH platforms: a device that advertises no suitable fixed
  /// range keeps capturing as-is rather than risking a wedged capture session.
  /// Re-applied natively after a camera start / flip, so the cap survives a
  /// stop→start cycle.
  Future<void> setCaptureFrameRate(int fps) async {
    if (fps <= 0) return;
    await _channel.invokeMethod<void>('setCaptureFrameRate', {'fps': fps});
  }

  /// Let the OS stabilize (EIS/OIS) the frames Camera Kit sees, or not.
  ///
  /// STABILITY. Both stabilization techniques translate and micro-warp the frame
  /// per frame in a way that correlates with hand motion — EIS by shifting a
  /// crop window from gyro + image analysis, OIS by physically moving a lens
  /// element. Camera Kit's face tracker then has to chase that motion on top of
  /// the real head motion, so its landmarks jitter: on a warping lens the
  /// graphics stay glued to the landmarks while the silhouette and the
  /// background wobble around them. That is the reported "the filter itself is
  /// fine but the streamer's body trembles".
  ///
  /// The app has intended this off since the DeepAR era, but the switch went
  /// through the WebRTC fork's `CameraUtils.setFocusMode`, which only acts on the
  /// two OS camera capturers and silently no-ops for `SnapCameraVideoCapturer` —
  /// so on the Camera Kit path nothing was ever applied and the OEM HAL default
  /// won (finding CAM-01).
  ///
  ///   - Android: `CONTROL_VIDEO_STABILIZATION_MODE` +
  ///     `LENS_OPTICAL_STABILIZATION_MODE` through Camera2 interop, merged into
  ///     the same capture-request update as the pinned cadence. A real change.
  ///   - iOS: `AVCaptureConnection.preferredVideoStabilizationMode`. `off` is
  ///     already the default for a video data output, so `false` changes nothing
  ///     there — the call exists so the state is explicit and flippable.
  ///
  /// Best-effort on both: a value the device does not advertise is skipped
  /// rather than pushed, because an unsupported key in a repeating capture
  /// request can make the HAL reject the whole request. Re-applied natively
  /// after a camera start / flip, both of which reset the camera to HAL defaults.
  Future<void> setVideoStabilization({required bool enabled}) async {
    await _channel.invokeMethod<void>('setVideoStabilization', {'enabled': enabled});
  }

  /// True when the device can run Snap Camera Kit's processor. Snap has
  /// its own internal tier-detection (TFLite delegate availability, GPU
  /// features). On devices that don't meet the bar, the SDK falls back
  /// to a software path which is too slow for live broadcasting — we
  /// hide the feature on those devices.
  ///
  /// Cached after first call — the answer never changes for the lifetime
  /// of the process.
  bool? _gpuAccelerationCache;
  Future<bool> hasGpuAcceleration() async {
    final cached = _gpuAccelerationCache;
    if (cached != null) return cached;
    final ok = await _channel.invokeMethod<bool>('checkGpuAcceleration');
    final value = ok ?? false;
    _gpuAccelerationCache = value;
    return value;
  }

  /// Enumerate every lens in a Lens Group directly from Camera Kit, with
  /// no backend catalog. When [groupId] is null/empty the session's
  /// initialized default group is used. Returns one map per lens with
  /// keys `lensId`, `groupId`, and (when present) `name` and `iconUrl`.
  /// Returns an empty list if Snap isn't initialized for the session.
  Future<List<Map<String, String?>>> getGroupLenses({String? groupId}) async {
    final raw = await _channel.invokeMethod<List<dynamic>>('getGroupLenses', {
      if (groupId != null && groupId.isNotEmpty) 'groupId': groupId,
    });
    if (raw == null) return const [];
    return raw.whereType<Map>().map((m) {
      return <String, String?>{
        'lensId': m['lensId'] as String?,
        'groupId': m['groupId'] as String?,
        'name': m['name'] as String?,
        'iconUrl': m['iconUrl'] as String?,
      };
    }).toList();
  }

  /// Start the INDEPENDENT local-preview texture (Pipeline A) — iOS + Android.
  ///
  /// The native side fans Camera Kit's lens-processed frame to a Flutter
  /// external texture that has zero WebRTC coupling (no track, renderer, source,
  /// sink-wants, encoder or network). iOS retains the processed `CVPixelBuffer`
  /// and reports it as a `FlutterTexture`; Android connects a second Camera Kit
  /// output (RECORDING) straight into a `SurfaceTextureEntry`. Returns the
  /// Flutter texture id to render via a `Texture(textureId)` widget, or null if
  /// unavailable (Snap not initialized, or no texture registry).
  Future<int?> startPreviewTexture() async {
    try {
      return await _channel.invokeMethod<int>('startPreviewTexture');
    } on MissingPluginException {
      return null; // No native handler on this platform.
    }
  }

  /// Stop + release the independent preview texture. Idempotent.
  Future<void> stopPreviewTexture() async {
    try {
      await _channel.invokeMethod<void>('stopPreviewTexture');
    } on MissingPluginException {
      // No-op where the plugin is absent.
    }
  }

  /// Read the native preview/encoder instrumentation snapshot. iOS reports the
  /// full set (processed fps, encoder-input fps, preview push/pull/dropped,
  /// thermal, IOSurface); Android renders GPU-direct with no per-frame CPU hook,
  /// so it reports connection state + thermal only. Empty map when unavailable.
  /// Never throws.
  ///
  /// **This call DRAINS AND RESETS the cadence accumulators** (`drainCadenceMetrics`
  /// on both platforms), so the returned window is "since the previous call" —
  /// by ANY caller. Two pollers therefore do not merely double the cost, they
  /// silently halve each other's window and make `processedFpsWindow` /
  /// `processedIntervalStdDevMs` describe a period neither one knows the length
  /// of. There must be exactly ONE poller per app run
  /// (`CaptureCadenceMonitor` in the host app); every other consumer reads the
  /// value that poller caches.
  ///
  /// [caller] is a debug-only identity used by [_assertSinglePoller] to catch a
  /// second poller the moment it is added. It is never sent to the platform.
  Future<Map<String, dynamic>> getPocMetrics({Object? caller}) async {
    _assertSinglePoller(caller);
    try {
      final raw = await _channel.invokeMethod<Map<dynamic, dynamic>>('getPocMetrics');
      if (raw == null) return const {};
      return raw.map((k, v) => MapEntry(k.toString(), v));
    } on MissingPluginException {
      return const {};
    } catch (_) {
      return const {};
    }
  }

  /// Debug-only single-poller guard. A test that proves `getPocMetrics()` has
  /// exactly one caller is not practical, so the invariant is asserted at
  /// runtime instead: if two DIFFERENT objects poll within the same second, the
  /// window each of them reads is a fraction of the second it believes it read,
  /// and every cadence number derived from it is wrong. Debug builds only —
  /// compiled out of release by the `assert` and the `kDebugMode` guard.
  void _assertSinglePoller(Object? caller) {
    assert(() {
      if (caller == null) return true;
      final now = DateTime.now();
      final last = _lastPollCaller;
      final at = _lastPollAt;
      if (last != null &&
          !identical(last, caller) &&
          at != null &&
          now.difference(at) < const Duration(seconds: 1)) {
        debugPrint(
          'SnapCameraKitController.getPocMetrics: TWO pollers within 1s '
          '(${last.runtimeType} and ${caller.runtimeType}). getPocMetrics '
          'DRAINS the cadence accumulators — every window both of them read is '
          'truncated. Route the second consumer through the cached value.',
        );
      }
      _lastPollCaller = caller;
      _lastPollAt = now;
      return true;
    }());
  }

  Object? _lastPollCaller;
  DateTime? _lastPollAt;

  /// Release native resources. Call on session end.
  Future<void> dispose() async {
    await _channel.invokeMethod<void>('dispose');
  }
}

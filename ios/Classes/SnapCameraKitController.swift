import ARKit
import AVFoundation
import CoreMedia
import CoreVideo
import Flutter
import Foundation
import QuartzCore
import SCSDKCameraKit
import UIKit

/// Wraps the Snap Camera Kit iOS SDK using the **official, supported** pattern:
/// Camera Kit renders lenses in its own pipeline — exactly how the Snapchat app
/// runs — and the lens-processed output frames are emitted to a sink (a WebRTC
/// capturer) that publishes them to LiveKit.
///
/// ## Who owns the camera on iOS: WE do
/// Earlier revisions of this comment said "Camera Kit owns the camera (via
/// `AVSessionInput`)". That is wrong, and it drove design decisions (see the
/// background-recovery note below). `AVSessionInput` is a *wrapper* around an
/// `AVCaptureSession` the host app supplies; `SCCameraKitAVSessionInput.h` says
/// so verbatim — *"You are still responsible for doing the general configuration
/// of the capture session, such as adding devices."* This class creates the
/// `AVCaptureSession`, picks the preset, adds the device input, sets exposure /
/// torch / zoom, and drives start/stop. Camera Kit only attaches its own video
/// data output to it. Anything reachable through `AVCaptureDevice` is therefore
/// ours to set directly — no reflection, unlike the Android controller.
/// (Android is the opposite: there Camera Kit really does own the binding.)
///
/// This replaces the previous "headless" approach (LiveKit owned the camera,
/// we pushed frames into Camera Kit and read them back). That approach caused
/// two problems this design fixes:
///   • iOS: LensCore threw `processing_internal_failure` every frame because it
///     had no real camera/render context.
///   • Android/iOS: the double round-trip (copy-in → render → read-back) plus an
///     async "latest frame" handoff made motion lag behind ("high ping" feel).
///
/// ## Frame flow (official)
/// ```
///   AVCaptureSession (we configure the camera device)
///        │  wrapped as AVSessionInput
///        ▼
///   Camera Kit Session  ──(lens applied)──►  PixelBufferOutput
///        ▼                                         │ processed CVPixelBuffer
///   frameSink(pixelBuffer, ts)  ◄──────────────────┘
///        ▼
///   SnapCameraKitCapturer → RTCVideoSource → LiveKit encoder
/// ```
class SnapCameraKitController: NSObject {

    private var initialized = false

    /// Consecutive failed auto-restarts after AVCaptureSession runtime errors.
    /// See [handleCaptureRuntimeError] — bounded with escalating backoff so a
    /// persistent -11872 ("too many camera hardware resources") can never spin
    /// an infinite instant restart loop.
    private var runtimeErrorRestarts = 0
    private let maxRuntimeErrorRestarts = 6
    private var apiToken: String?
    private var defaultGroupId: String?
    private var loadedLensId: String?
    private var loadedGroupId: String?
    private var pluginRegistrar: FlutterPluginRegistrar?

    private var session: CameraKitProtocol?
    /// Camera Kit owns this capture session. We configure the device input
    /// (front/back); Camera Kit reads frames from it and renders.
    private let captureSession = AVCaptureSession()
    /// Serial queue for capture-session start/stop so the foreground-restart
    /// path and init never race a concurrent startRunning/stopRunning.
    private let sessionQueue = DispatchQueue(label: "net.vibezo.snap.captureSession")
    private var avInput: AVSessionInput?
    /// AR data is optional for 2D/face/world lenses; we never start ARKit (the
    /// capture session owns the camera). See NoOpARInput.
    private let arInput = NoOpARInput()
    private var output: PixelBufferOutput?
    private var viewportOutput: ViewportOutput?

    // MARK: - Phase-1 POC: independent preview texture + instrumentation
    //
    // Branch A of the fan-out. `previewTexture` is a Flutter external texture
    // fed the SAME lens-processed CVPixelBuffer as the encoder (Branch B), but
    // it has zero WebRTC coupling. All of this is INERT until
    // `startPreviewTexture()` registers a texture, so production behaviour is
    // byte-identical until the POC is switched on from Dart.
    private var previewTexture: SnapPreviewTexture?
    private var previewTextureId: Int64?
    private var textureRegistry: FlutterTextureRegistry? { pluginRegistrar?.textures() }
    private let pocLock = NSLock()
    private var processedFrameCount: Int = 0     // Camera Kit output cadence
    private var encoderInputFrameCount: Int = 0  // frames handed to the encoder sink

    // ── DELIVERY-BLOCKED accumulator (v2 diagnostics) ────────────────────────
    // Time each frame spent inside the WebRTC sink push, drained per ~1 Hz
    // window by `pocMetrics()`. Three primitive doubles under the existing
    // `pocLock`; no allocation, nothing ahead of frame delivery.
    private var blockedSumMs: Double = 0
    private var blockedCount: Int = 0
    private var blockedMaxMs: Double = 0
    private var encoderNilSinkCount: Int = 0     // frames with no encoder sink (paused/torn)
    private var pocStartedAtSec: Double = 0

    // MARK: - Stage 0: capture-cadence spread + real output geometry
    //
    // A stable MEAN fps hides an unpinned capture frame rate: the session preset
    // is free to run a variable frame duration, which yields a healthy-looking
    // average with a wide per-frame interval spread — and the spread is what
    // reads as jitter. So the interval min/max/mean/stddev is accumulated per
    // ~1 Hz window here and drained by `pocMetrics()`.
    //
    // These live in the existing PixelBufferOutput closure, INSIDE the pocLock
    // section that already runs there — a handful of arithmetic ops and no
    // allocation, so the per-frame path gains nothing measurable. Intervals come
    // from the frame's own presentation timestamp, so they measure CAPTURE
    // cadence rather than callback-queue scheduling.
    private var processedWindowFrames: Int = 0
    private var cadenceWindowStartSec: Double = 0
    private var cadenceLastTimestampNs: Int64 = 0
    private var cadenceSamples: Int = 0
    private var cadenceSumMs: Double = 0
    private var cadenceSumSqMs: Double = 0
    private var cadenceMinMs: Double = 0
    private var cadenceMaxMs: Double = 0
    private var cadenceStalls: Int = 0
    /// Camera Kit's ACTUAL delivered buffer size — which is not necessarily the
    /// `ViewportOutput` size it was asked for, and is the reading that tests the
    /// preview's hard-coded 9:16 fitted box against reality.
    private var processedFrameWidth: Int = 0
    private var processedFrameHeight: Int = 0

    /// An interval above this counts as a stall, not cadence — reported
    /// separately so it cannot silently inflate the spread.
    private let cadenceStallMs: Double = 250

    /// Catches Camera Kit's async failures (unauthorized token/bundle id, lens
    /// download errors) so they don't crash the app.
    private let snapErrorHandler = SnapErrorHandler()

    /// Where lens-processed frames go (set by the WebRTC capturer). Called on
    /// Camera Kit's output queue.
    private var frameSink: ((CVPixelBuffer, Int64) -> Void)?
    /// Identity of the capturer that currently owns `frameSink`. A capturer may
    /// only CLEAR the sink if it still owns it (see clearFrameSink). This stops a
    /// stale (previous session's) `SnapCameraKitCapturer.stopCapture()` — which
    /// LiveKit/flutter_webrtc fires ASYNC on iOS, often AFTER the next live's
    /// capturer is already constructed — from nulling the sink the new capturer
    /// just registered. That clobber was the iOS-only "white camera after
    /// end→restart" bug: Camera Kit kept rendering frames, but they reached a
    /// `nil` sink, so LiveKit published an empty track.
    private weak var frameSinkOwner: AnyObject?
    private let sinkLock = NSLock()

    private var pendingLensId: String?
    private var pendingGroupId: String?

    /// Monotonic epoch for lens selection — the iOS half of the same ordering
    /// guard Android's `lensGeneration` provides. Bumped by every `applyLens`
    /// and `clearLens`; async continuations capture the value they were issued
    /// under and no-op once it has moved on.
    ///
    /// Necessary because `repository.addObserver` → `didUpdate` →
    /// `processor.apply(completion:)` is a two-hop async chain whose completion
    /// order is not the tap order. The pre-existing `lens.id == pendingLensId`
    /// check filters a stale *fetch*, but cannot order two applies already in
    /// flight, and never guarded the `apply` completion that publishes
    /// `loadedLensId` — so a slow lens could still overwrite a newer selection.
    ///
    /// Guarded by `lensLock`: bumped on the platform thread, read on Camera
    /// Kit's repository-callback thread.
    private var lensGeneration: Int = 0
    private let lensLock = NSLock()

    /// Lens ids this controller has registered a specific-lens observer for.
    /// Camera Kit keeps every registration live, so without this the observer
    /// set grew with each tap and every past lens kept re-firing `didUpdate`
    /// for the life of the session. Cleared on `dispose`.
    private var observedLensIds: Set<String> = []

    private var groupObservers: [GroupLensesObserver] = []
    /// No-op observers kept alive for the session to warm each group's lens
    /// cache (see prefetchGroups); removed in dispose.
    private var warmObservers: [(observer: WarmGroupObserver, groupID: String)] = []

    private var cameraPosition: AVCaptureDevice.Position = .front
    private var renderWidth: Int = DEFAULT_RENDER_W
    private var renderHeight: Int = DEFAULT_RENDER_H

    /// Capture frame-rate cap in fps, pushed down from Dart's thermal ladder.
    /// 30 = the default full rate (no cap). Re-applied on every camera
    /// (re)configure so a camera flip can't silently restore the native rate.
    /// See [applyCaptureFrameRate].
    private var captureFrameRate: Int = 30

    /// Whether iOS may run video stabilization on the frames Camera Kit sees.
    ///
    /// Default OFF, which is also `AVCaptureConnection`'s own default for a video
    /// DATA output — so the default costs nothing and changes nothing here. It
    /// exists so the decision is explicit, logged, auditable in the Stage 0
    /// `iosStab` reading, and flippable at runtime for an A/B against Android,
    /// where the same knob is a real behaviour change (finding CAM-01).
    ///
    /// Stabilization crops and warps the frame per frame in a way that
    /// correlates with hand motion, which Camera Kit's tracker then has to chase
    /// on top of the real head motion — the "filter is fine but the body
    /// trembles" report.
    private var stabilizationEnabled: Bool = false

    /// Exposure-compensation bias in EV applied on top of the device's own auto
    /// metering. [DEFAULT_EXPOSURE_BIAS_EV] is what every stream shipped with
    /// before the control existed; the streamer can now move it from the camera
    /// settings sheet and the app persists their choice.
    ///
    /// Stored (not applied once) because a camera flip REMOVES the device input
    /// and adds a fresh one at the device default — [configureCaptureSession]
    /// re-applies this on every reconfigure, so the streamer's value survives.
    private var exposureBiasEv: Double = DEFAULT_EXPOSURE_BIAS_EV

    /// Transparent overlay view (from the PlatformView) whose touches/gestures
    /// Camera Kit forwards to the active lens — interactive/game lenses.
    private weak var touchView: UIView?

    /// Where lens hint text goes (→ Flutter [SnapLensHintOverlay]). `nil` = hide.
    var hintSink: ((String?) -> Void)?

    func setPluginRegistrar(_ registrar: FlutterPluginRegistrar) {
        pluginRegistrar = registrar
    }

    /// Register the transparent touch overlay (called by the PlatformView). When
    /// the session is ready, Camera Kit's gesture recognizers are attached to it.
    func attachTouchView(_ view: UIView) {
        touchView = view
        configureTouchHandlingIfReady()
    }

    /// Detach Camera Kit's touch handling from the overlay (PlatformView dispose).
    func detachTouchView() {
        guard let view = touchView else { return }
        let session = self.session
        DispatchQueue.main.async {
            session?.lenses.processor?.removeTouchHandling(in: view)
        }
        touchView = nil
    }

    private func configureTouchHandlingIfReady() {
        guard let view = touchView, let session = session else { return }
        DispatchQueue.main.async {
            session.lenses.processor?.configureTouchHandling(in: view, gestureRecognizerDelegate: nil)
        }
    }

    /// Register the consumer of lens-processed frames, tagged with the owning
    /// capturer. Last writer wins, but only the CURRENT owner may clear it (see
    /// [clearFrameSink]) — so the async stop of a stale capturer can never wipe
    /// the live capturer's sink. If frames are already flowing, the capturer
    /// starts receiving immediately.
    func setFrameSink(_ sink: @escaping (CVPixelBuffer, Int64) -> Void, owner: AnyObject) {
        sinkLock.lock()
        frameSink = sink
        frameSinkOwner = owner
        sinkLock.unlock()
    }

    /// Clear the sink ONLY if `owner` is the capturer that currently owns it.
    /// A stale capturer's teardown must never clobber a newer capturer's sink —
    /// this is the deterministic fix for the iOS white-camera-after-restart bug.
    func clearFrameSink(owner: AnyObject) {
        sinkLock.lock()
        if frameSinkOwner === owner {
            frameSink = nil
            frameSinkOwner = nil
        }
        sinkLock.unlock()
    }

    /// Unconditional clear — used only by [dispose], a full session teardown
    /// that owns no particular capturer.
    private func forceClearFrameSink() {
        sinkLock.lock()
        frameSink = nil
        frameSinkOwner = nil
        sinkLock.unlock()
    }

    // MARK: - Phase-1 POC preview texture

    /// Create + register the independent preview texture (Branch A) and return
    /// its Flutter texture id, or nil if the registry is unavailable. Idempotent.
    /// Resets the instrumentation window.
    func startPreviewTexture() -> Int64? {
        pocLock.lock()
        if let id = previewTextureId { pocLock.unlock(); return id }
        pocLock.unlock()
        guard let registry = textureRegistry else {
            print("[SnapPreviewPOC] startPreviewTexture: no texture registry")
            return nil
        }
        let tex = SnapPreviewTexture()
        let id = registry.register(tex)
        pocLock.lock()
        previewTexture = tex
        previewTextureId = id
        pocStartedAtSec = CACurrentMediaTime()
        processedFrameCount = 0
        encoderInputFrameCount = 0
        encoderNilSinkCount = 0
        // Re-arm the Stage 0 cadence window alongside the counters it sits with.
        processedWindowFrames = 0
        cadenceWindowStartSec = 0
        cadenceLastTimestampNs = 0
        cadenceSamples = 0
        cadenceSumMs = 0
        cadenceSumSqMs = 0
        cadenceMinMs = 0
        cadenceMaxMs = 0
        cadenceStalls = 0
        pocLock.unlock()
        print("[SnapPreviewPOC] startPreviewTexture: id=\(id)")
        return id
    }

    /// Unregister + release the preview texture. Idempotent.
    func stopPreviewTexture() {
        pocLock.lock()
        let id = previewTextureId
        let tex = previewTexture
        previewTexture = nil
        previewTextureId = nil
        // Unregister UNDER pocLock (paired with the fan-out notify's re-check of
        // previewTextureId): this makes teardown and any in-flight frame signal
        // mutually exclusive, so textureFrameAvailable can never fire for a
        // just-unregistered id (the SnapPreviewTexture.push EXC_BAD_ACCESS).
        if let id = id {
            textureRegistry?.unregisterTexture(id)
        }
        pocLock.unlock()
        guard let id = id else { return }
        tex?.clear()
        print("[SnapPreviewPOC] stopPreviewTexture: id=\(id)")
    }

    /// STAGE 0 per-frame sample. MUST be called with `pocLock` already held (it
    /// is, from the PixelBufferOutput closure's existing locked section).
    /// Records the delivered geometry and accumulates the interval statistics
    /// drained by `pocMetrics()`.
    private func noteProcessedFrameLocked(_ pixelBuffer: CVPixelBuffer, timestampNs: Int64) {
        processedFrameWidth = CVPixelBufferGetWidth(pixelBuffer)
        processedFrameHeight = CVPixelBufferGetHeight(pixelBuffer)
        if cadenceWindowStartSec == 0 { cadenceWindowStartSec = CACurrentMediaTime() }
        processedWindowFrames += 1
        let previous = cadenceLastTimestampNs
        cadenceLastTimestampNs = timestampNs
        guard previous > 0, timestampNs > previous else { return }
        let deltaMs = Double(timestampNs - previous) / 1_000_000.0
        cadenceSamples += 1
        cadenceSumMs += deltaMs
        cadenceSumSqMs += deltaMs * deltaMs
        if cadenceMinMs == 0 || deltaMs < cadenceMinMs { cadenceMinMs = deltaMs }
        if deltaMs > cadenceMaxMs { cadenceMaxMs = deltaMs }
        if deltaMs > cadenceStallMs { cadenceStalls += 1 }
    }

    /// Drain the cadence window: windowed fps + interval min/max/mean/stddev.
    /// Resets the window so each poll reports the LAST second, which is what
    /// makes a spread reading meaningful. Caller must hold `pocLock`.
    private func drainCadenceMetricsLocked(now: Double) -> [String: Any] {
        let windowSec = max(0.001, now - (cadenceWindowStartSec == 0 ? now : cadenceWindowStartSec))
        let samples = cadenceSamples
        let mean = samples > 0 ? cadenceSumMs / Double(samples) : 0
        // Population variance from the running sums; clamped at 0 because
        // floating-point cancellation can push a near-zero variance negative.
        let variance = samples > 0 ? max(0, cadenceSumSqMs / Double(samples) - mean * mean) : 0
        let out: [String: Any] = [
            "processedFpsWindow": Double(processedWindowFrames) / windowSec,
            "processedIntervalMeanMs": mean,
            "processedIntervalMinMs": cadenceMinMs,
            "processedIntervalMaxMs": cadenceMaxMs,
            "processedIntervalStdDevMs": variance.squareRoot(),
            "processedIntervalSamples": samples,
            "processedStallCount": cadenceStalls,
            "processedFrameWidth": processedFrameWidth,
            "processedFrameHeight": processedFrameHeight,
        ]
        processedWindowFrames = 0
        cadenceSamples = 0
        cadenceSumMs = 0
        cadenceSumSqMs = 0
        cadenceMinMs = 0
        cadenceMaxMs = 0
        cadenceStalls = 0
        cadenceWindowStartSec = now
        return out
    }

    /// STAGE 0 — the camera's real geometry and its stabilization / metering
    /// state, read straight off the `AVCaptureDevice` and the video connection
    /// we own. The Android half of this reading has to go through private-field
    /// reflection; on iOS it is all public API, because *we* own the capture
    /// session (the class doc above claiming Camera Kit owns it is wrong — see
    /// `SCCameraKitAVSessionInput.h`, which states the host app stays
    /// responsible for configuring the session).
    ///
    /// `captureFpsMin`/`captureFpsMax` are the reading that tests CAM-02: with
    /// nothing pinning the frame duration they are the format's full range, and
    /// the camera is free to vary its cadence anywhere inside it.
    ///
    /// Cheap: property reads only, at the existing ~1 Hz poll.
    private func captureStateMetrics() -> [String: Any] {
        var out: [String: Any] = [
            "renderWidth": renderWidth,
            "renderHeight": renderHeight,
            "sessionPreset": captureSession.sessionPreset.rawValue,
            "captureRunning": captureSession.isRunning,
        ]
        guard let device = currentDevice() else { return out }
        let dims = CMVideoFormatDescriptionGetDimensions(device.activeFormat.formatDescription)
        out["cameraInputWidth"] = Int(dims.width)
        out["cameraInputHeight"] = Int(dims.height)
        out["cameraFacingFront"] = device.position == .front
        let minDuration = CMTimeGetSeconds(device.activeVideoMinFrameDuration)
        let maxDuration = CMTimeGetSeconds(device.activeVideoMaxFrameDuration)
        // min frame DURATION ⇒ max fps, and vice versa.
        out["captureFpsMax"] = minDuration > 0 ? 1.0 / minDuration : 0
        out["captureFpsMin"] = maxDuration > 0 ? 1.0 / maxDuration : 0
        out["cameraExposureMode"] = device.exposureMode.rawValue
        out["cameraExposureBias"] = Double(device.exposureTargetBias)
        out["cameraZoomFactor"] = Double(device.videoZoomFactor)
        if let connection = captureSession.outputs
            .compactMap({ $0.connection(with: .video) })
            .first {
            out["cameraStabilizationSupported"] = connection.isVideoStabilizationSupported
            out["cameraActiveStabilization"] = connection.activeVideoStabilizationMode.rawValue
            out["cameraPreferredStabilization"] = connection.preferredVideoStabilizationMode.rawValue
        }
        return out
    }

    /// Snapshot of the POC instrumentation for the Dart debug overlay. Never
    /// throws; safe to poll every ~1s.
    func pocMetrics() -> [String: Any] {
        let now = CACurrentMediaTime()
        pocLock.lock()
        let elapsed = pocStartedAtSec > 0 ? max(0.001, now - pocStartedAtSec) : 0.001
        let processed = processedFrameCount
        let encIn = encoderInputFrameCount
        let encNil = encoderNilSinkCount
        let tex = previewTexture
        let cadence = drainCadenceMetricsLocked(now: now)
        let blockedMean = blockedCount > 0 ? blockedSumMs / Double(blockedCount) : 0
        let blockedMax = blockedMaxMs
        let blockedSamples = blockedCount
        blockedSumMs = 0
        blockedCount = 0
        blockedMaxMs = 0
        pocLock.unlock()
        var out: [String: Any] = [
            "elapsedSec": elapsed,
            "processedFps": Double(processed) / elapsed,
            "encoderInputFps": Double(encIn) / elapsed,
            "encoderNilSinkCount": encNil,
            "previewPushCount": tex?.pushCount ?? 0,
            "previewPullCount": tex?.pullCount ?? 0,
            "previewDroppedCount": tex?.droppedCount ?? 0,
            "previewLastDeltaMs": tex?.lastPushDeltaMs ?? 0,
            "previewIOSurfaceBacked": tex?.ioSurfaceBacked ?? false,
            "thermalState": ProcessInfo.processInfo.thermalState.rawValue,
            "deliveryBlockedMeanMs": blockedMean,
            "deliveryBlockedMaxMs": blockedMax,
            "deliveryBlockedSamples": blockedSamples,
        ]
        out.merge(cadence) { _, new in new }
        out.merge(captureStateMetrics()) { _, new in new }
        return out
    }

    func initialize(apiToken: String, defaultGroupId: String, groupIds: [String] = []) -> Bool {
        if initialized { return true }

        // Re-baseline the physical camera to FRONT for this fresh session.
        // `cameraPosition` lives on the singleton controller and is toggled by
        // switchCamera(), so it SURVIVES across go-live sessions — a back-camera
        // user's last stream would otherwise make the next fresh stream boot on
        // the BACK camera even though Flutter reset to front. The Dart side
        // (beauty.part.dart `initializeSnapForSession`) ASSUMES a fresh Snap
        // session boots front and re-applies the saved side via
        // `enforceSavedCameraPosition` on reconnect — this makes that assumption
        // true at the source, matching the Android controller's `facingFront =
        // true` re-baseline. Placed AFTER the `if initialized` early-return so a
        // mid-stream switchCamera() or a reconnect's saved-side restore is never
        // clobbered (it only runs on a genuinely fresh init).
        cameraPosition = .front

        self.apiToken = apiToken
        self.defaultGroupId = defaultGroupId

        let cameraKit = Session(
            sessionConfig: SessionConfig(apiToken: apiToken),
            lensesConfig: LensesConfig(cacheConfig: CacheConfig(lensContentMaxSize: 150 * 1024 * 1024)),
            errorHandler: snapErrorHandler)

        // Configure OUR AVCaptureSession with the front camera, then hand it to
        // Camera Kit as an input. Camera Kit attaches its own video data output
        // and makes any color-format adjustments it needs; the session, the
        // device input and every device-level control stay ours.
        configureCaptureSession(position: cameraPosition)
        // OUTPUT PURGE — the singleton reuses ONE AVCaptureSession across every
        // go-live in the app's lifetime, and each AVSessionInput below attaches
        // its own video data output to it. Only a fresh init reaches this line
        // (`initialized` guard above), so any output still on the session is a
        // stale leftover from a previous live whose dispose was skipped/raced.
        // Left in place they accumulate one per live until iOS aborts the
        // session with -11872 "Too many camera hardware resources".
        captureSession.beginConfiguration()
        for output in captureSession.outputs {
            captureSession.removeOutput(output)
        }
        captureSession.commitConfiguration()
        let avSessionInput = AVSessionInput(session: captureSession, audioEnabled: false)
        // Do NOT mirror Camera Kit's output. The broadcast/recorded stream must
        // be "normal" (un-mirrored), like a standard camera. The local selfie
        // preview is mirrored separately at the renderer
        // (VideoViewMirrorMode.mirror), so the streamer still sees a natural
        // mirror without mirroring what viewers receive.
        avSessionInput.automaticallyConfiguresVideoMirrored = false
        avSessionInput.videoMirrored = false
        self.avInput = avSessionInput

        let pixelOutput = PixelBufferOutput { [weak self] pixelBuffer, timestampNs in
            guard let self = self else { return }
            // ── FAN-OUT (Phase-1 POC) ─────────────────────────────────────────
            // One processed frame from Camera Kit's single render loop, split to
            // two independent consumers. This closure IS the split point; both
            // branches receive the identical `pixelBuffer`.
            //
            // Instrument the processed cadence (== the rate available to BOTH
            // branches, i.e. the ideal preview fps) and read the preview target
            // under the lock (it is written on the platform thread by
            // start/stopPreviewTexture — never touch a Swift reference across
            // threads unguarded).
            self.pocLock.lock()
            self.processedFrameCount += 1
            self.noteProcessedFrameLocked(pixelBuffer, timestampNs: timestampNs)
            let tex = self.previewTexture
            let texId = self.previewTextureId
            self.pocLock.unlock()

            // Branch A — INDEPENDENT preview. No RTCVideoTrack, no source, no
            // sink-wants, no encoder, no network. No-op until the POC registers a
            // texture, so this is free in production.
            if let tex = tex, let texId = texId, let registry = self.textureRegistry {
                tex.push(pixelBuffer, timestampNs: timestampNs) {
                    // Re-check under pocLock that this texture is STILL the
                    // registered preview before signalling. stopPreviewTexture
                    // nils previewTextureId AND unregisters under this same
                    // lock, so either this notify runs first (still current →
                    // safe) or teardown ran first (id no longer current →
                    // skip). Without this, a frame already in flight signalled
                    // a just-unregistered texture → EXC_BAD_ACCESS in push.
                    self.pocLock.lock()
                    if self.previewTextureId == texId {
                        registry.textureFrameAvailable(texId)
                    }
                    self.pocLock.unlock()
                }
            }

            // Branch B — WebRTC encoder (UNCHANGED path).
            self.sinkLock.lock()
            let sink = self.frameSink
            self.sinkLock.unlock()
            self.pocLock.lock()
            if sink != nil { self.encoderInputFrameCount += 1 } else { self.encoderNilSinkCount += 1 }
            self.pocLock.unlock()
            // DELIVERY-BLOCKED CLOCK (v2 diagnostics). The sink wraps the buffer
            // as an RTCVideoFrame and pushes it SYNCHRONOUSLY into the WebRTC
            // source, so this bracket measures how long Camera Kit's render
            // callback was held downstream — the iOS twin of the Android
            // capturer's measurement. Two `CACurrentMediaTime()` reads around a
            // call that already happens; nothing new sits in front of delivery.
            let blockedStart = CACurrentMediaTime()
            sink?(pixelBuffer, timestampNs)
            if sink != nil {
                let blockedMs = (CACurrentMediaTime() - blockedStart) * 1000.0
                if blockedMs >= 0, blockedMs <= 1000 {
                    self.pocLock.lock()
                    self.blockedSumMs += blockedMs
                    self.blockedCount += 1
                    if blockedMs > self.blockedMaxMs { self.blockedMaxMs = blockedMs }
                    self.pocLock.unlock()
                }
            }
        }

        // Headless render target: advertise output dimensions a PreviewView
        // would normally provide.
        let viewport = ViewportOutput(width: renderWidth, height: renderHeight)
        self.viewportOutput = viewport
        cameraKit.add(output: viewport)
        cameraKit.add(output: pixelOutput)
        // Use the overload that accepts a hint delegate so we receive lens hints
        // ("Tap to start", "Look left", …) and render them in our own Flutter UI.
        // front camera + portrait matches the previous simple start(input:arInput:).
        cameraKit.start(
            input: avSessionInput,
            arInput: arInput,
            cameraPosition: cameraPosition,
            videoOrientation: .portrait,
            dataProvider: nil,
            hintDelegate: self,
            textInputContextProvider: nil,
            agreementsPresentationContextProvider: nil)
        // Camera Kit attaches its video data output inside start(input:), so the
        // video CONNECTION only exists now — this is the earliest point the
        // stabilization decision can be applied.
        applyCameraStability()

        // startRunning is synchronous and must not run on the main thread.
        // Serialized on sessionQueue — NOT a detached global queue — so it can
        // never race a dispose()/restart from a superseded session generation.
        // That overlap requested the camera hardware twice at once, which is
        // exactly the -11872 "too many camera hardware resources" runtime
        // error observed after a live recovery reconnect.
        sessionQueue.async { [weak self] in
            self?.captureSession.startRunning()
        }

        self.session = cameraKit
        self.output = pixelOutput
        initialized = true
        // Fresh session — re-arm the runtime-error restart budget.
        runtimeErrorRestarts = 0
        // Auto-recover the camera when iOS resumes the app. Registered here (only
        // a genuinely fresh init reaches this point) and torn down in dispose().
        registerCaptureLifecycleObservers()
        // If the touch overlay was already registered (PlatformView built before
        // init), wire Camera Kit's gesture handling to it now.
        configureTouchHandlingIfReady()
        // Warm every session group (not just the default) so the picker settles
        // fast on first open for any category.
        prefetchGroups(groupIds.isEmpty ? [defaultGroupId] : groupIds)
        print("[SnapCameraKit] initialize: official session ready (app owns AVCaptureSession) group=\(defaultGroupId)")
        // Apply the lens the user picked while the session was still building
        // — without this their tap was silently lost.
        if let queuedLens = pendingLensId, let queuedGroup = pendingGroupId {
            print("[SnapCameraKit] initialize: applying queued lens \(queuedLens)/\(queuedGroup)")
            applyLens(lensId: queuedLens, groupId: queuedGroup)
        }
        return true
    }

    /// Warm the lens cache for every session group by registering a no-op
    /// observer per group — `addObserver` triggers Camera Kit to fetch the
    /// group's lens metadata from Snap's cloud. Kept alive for the session
    /// (removed in dispose) so the picker's later getGroupLenses() settles fast.
    private func prefetchGroups(_ groupIds: [String]) {
        guard let session = session else { return }
        for gid in groupIds where !gid.isEmpty {
            let observer = WarmGroupObserver()
            warmObservers.append((observer, gid))
            session.lenses.repository.addObserver(observer, groupID: gid)
        }
    }

    /// Best AVCaptureSession preset for the target render height, capped at
    /// 1080p — going higher needs custom AVCaptureDeviceFormat selection,
    /// out of scope for this fix. 720p stays the floor for lower tiers
    /// (unchanged behavior there).
    private func bestSessionPreset(forRenderHeight height: Int) -> AVCaptureSession.Preset {
        if height >= 1024, captureSession.canSetSessionPreset(.hd1920x1080) {
            return .hd1920x1080
        }
        return .hd1280x720
    }

    /// (Re)configure the capture session's camera device input for the given
    /// position. Camera Kit reads from whatever device input is attached.
    private func configureCaptureSession(position: AVCaptureDevice.Position) {
        captureSession.beginConfiguration()
        let preset = bestSessionPreset(forRenderHeight: renderHeight)
        if captureSession.canSetSessionPreset(preset) {
            captureSession.sessionPreset = preset
        }
        for input in captureSession.inputs {
            captureSession.removeInput(input)
        }
        if let device = cameraDevice(for: position),
           let deviceInput = try? AVCaptureDeviceInput(device: device),
           captureSession.canAddInput(deviceInput) {
            captureSession.addInput(deviceInput)
            applyExposureBias(to: device)
            applyCaptureFrameRate(to: device)
        } else {
            print("[SnapCameraKit] configureCaptureSession: no camera device for position \(position.rawValue)")
        }
        captureSession.commitConfiguration()
        // Removing the old device input DESTROYS its connections; adding the new
        // one creates fresh ones at their defaults. So the stabilization decision
        // has to be re-applied on every camera flip, and the focus settings
        // belong to the new device. A no-op on the initialize path (no outputs on
        // the session yet — start(input:) applies it there instead).
        applyCameraStability()
    }

    /// Cap the camera's CAPTURE frame rate.
    ///
    /// THERMAL: the encoder's framerate ladder (30→24→20→15, driven by the
    /// thermal tier) only ever set the ENCODER's `maxFramerate`. The camera kept
    /// capturing at its native rate and Camera Kit kept running the lens shader
    /// over EVERY frame on the GPU — so at the deepest thermal step the device
    /// was still doing full-rate capture + lens processing and simply throwing
    /// half those frames away at the encoder. That discarded work is pure heat,
    /// and it sat outside the thermal ladder entirely.
    ///
    /// Clamping BOTH min and max frame duration pins the rate: it stops the
    /// device's auto-exposure from silently ranging back up to 30 in good light.
    /// `activeVideoMinFrameDuration` must be set while the device is locked and
    /// AFTER the format/preset is chosen, which is why this runs inside
    /// `configureCaptureSession` right after the input is added.
    ///
    /// Only a rate the ACTIVE FORMAT genuinely supports is applied. Assigning an
    /// unsupported `activeVideoMin/MaxFrameDuration` raises an ObjC NSException,
    /// which a Swift `catch` does NOT trap (only `lockForConfiguration()` throws
    /// a Swift error) — so an out-of-range value here would be an app crash, not
    /// a soft failure. Hence the resolve-then-assign split below.
    private func applyCaptureFrameRate(to device: AVCaptureDevice) {
        let target = captureFrameRate
        guard target > 0 else { return }
        guard let fps = supportedCaptureFps(for: Double(target), on: device) else {
            print("[SnapCameraKit] applyCaptureFrameRate: no supported rate for \(target)fps — left as-is")
            return
        }
        do {
            try device.lockForConfiguration()
            defer { device.unlockForConfiguration() }
            let duration = CMTimeMake(value: 1, timescale: Int32(fps.rounded()))
            device.activeVideoMinFrameDuration = duration
            device.activeVideoMaxFrameDuration = duration
            print("[SnapCameraKit] applyCaptureFrameRate: pinned capture to \(Int(fps.rounded()))fps")
        } catch {
            print("[SnapCameraKit] applyCaptureFrameRate failed (non-fatal): \(error)")
        }
    }

    /// Resolve [target] fps to a rate the device's ACTIVE FORMAT actually
    /// supports, or nil when it supports nothing suitable.
    ///
    /// `videoSupportedFrameRateRanges` can hold several DISCONTIGUOUS ranges
    /// (e.g. `[[1,30],[60,60]]`), so a global min/max across all of them
    /// describes a span the device may not honour anywhere. Each range is
    /// therefore considered on its own:
    ///   • a range containing the target → the target exactly;
    ///   • otherwise the highest range ceiling still at or below the target
    ///     (never round UP — that would raise capture cost, the opposite of the
    ///     thermal intent).
    private func supportedCaptureFps(for target: Double, on device: AVCaptureDevice) -> Double? {
        let ranges = device.activeFormat.videoSupportedFrameRateRanges
        guard !ranges.isEmpty else { return nil }
        for range in ranges where target >= range.minFrameRate && target <= range.maxFrameRate {
            return target
        }
        let below = ranges.map(\.maxFrameRate).filter { $0 <= target }
        return below.max()
    }

    /// Set the capture frame-rate cap and apply it to the live device, if any.
    /// Called from Dart as the thermal ladder moves. No-op when unchanged.
    func setCaptureFrameRate(_ fps: Int) {
        guard fps > 0, fps != captureFrameRate else { return }
        captureFrameRate = fps
        guard let device = currentCaptureDevice() else { return }
        applyCaptureFrameRate(to: device)
    }

    /// The device backing the session's current video input, if attached.
    private func currentCaptureDevice() -> AVCaptureDevice? {
        for input in captureSession.inputs {
            if let deviceInput = input as? AVCaptureDeviceInput,
               deviceInput.device.hasMediaType(.video) {
                return deviceInput.device
            }
        }
        return nil
    }

    /// Move the device's exposure target by [exposureBiasEv] EV (the raw preview
    /// reads dark, especially on the front camera). The device KEEPS continuous
    /// auto-exposure — this only shifts its TARGET, so it still adapts to
    /// changing light instead of locking a fixed exposure. Clamped to the
    /// device's own supported bias range.
    private func applyExposureBias(to device: AVCaptureDevice) {
        do {
            try device.lockForConfiguration()
            if device.isExposureModeSupported(.continuousAutoExposure) {
                device.exposureMode = .continuousAutoExposure
            }
            let bias = min(max(Float(exposureBiasEv), device.minExposureTargetBias),
                           device.maxExposureTargetBias)
            device.setExposureTargetBias(bias, completionHandler: nil)
            device.unlockForConfiguration()
        } catch {
            print("[SnapCameraKit] applyExposureBias failed (non-fatal): \(error)")
        }
    }

    /// The streamer's exposure bias, in EV. Stored and pushed onto the live
    /// device immediately; every later camera (re)configure re-applies it.
    /// Clamped to [MIN_EXPOSURE_BIAS_EV]...[MAX_EXPOSURE_BIAS_EV] here as well as
    /// against the device's own range, so a bad value from Dart can never blow
    /// out the picture.
    func setExposureBias(_ ev: Double) {
        let clamped = min(max(ev, MIN_EXPOSURE_BIAS_EV), MAX_EXPOSURE_BIAS_EV)
        guard clamped != exposureBiasEv else { return }
        exposureBiasEv = clamped
        guard let device = currentDevice() else { return }
        applyExposureBias(to: device)
    }

    /// What this camera can actually do, for the settings sheet: the zoom and
    /// exposure ranges the ACTIVE device advertises. Reported rather than
    /// assumed so the sliders never offer a range the hardware will silently
    /// clamp — a control that does nothing at one end reads as a bug.
    func cameraCapabilities() -> [String: Any] {
        var out: [String: Any] = [
            "minExposureEv": MIN_EXPOSURE_BIAS_EV,
            "maxExposureEv": MAX_EXPOSURE_BIAS_EV,
            "exposureEv": exposureBiasEv,
            "maxZoom": 1.0,
            "zoom": 1.0,
        ]
        guard let device = currentDevice() else { return out }
        out["minExposureEv"] = max(MIN_EXPOSURE_BIAS_EV, Double(device.minExposureTargetBias))
        out["maxExposureEv"] = min(MAX_EXPOSURE_BIAS_EV, Double(device.maxExposureTargetBias))
        out["maxZoom"] = min(MAX_ZOOM_RATIO, Double(device.activeFormat.videoMaxZoomFactor))
        out["zoom"] = Double(device.videoZoomFactor)
        return out
    }

    /// Turn iOS video stabilization on/off for the frames Camera Kit sees.
    /// Called from Dart; see [stabilizationEnabled]. No-op when unchanged.
    func setVideoStabilization(_ enabled: Bool) {
        guard enabled != stabilizationEnabled else { return }
        stabilizationEnabled = enabled
        applyCameraStability()
    }

    /// Apply the stabilization decision to every video connection on our session,
    /// and give the focus behaviour the same "boring, constant image" treatment
    /// the Android tuning does.
    ///
    /// **Stabilization** lives on the CONNECTION, not the device, so it can only
    /// be set once an output exists — Camera Kit attaches its video data output
    /// inside `start(input:)`, which is why this runs after that call and not in
    /// `configureCaptureSession`. Deliberately NOT wrapped in a
    /// begin/commitConfiguration pair: `preferredVideoStabilizationMode` is
    /// settable outside one, and this file already carries a note that a needless
    /// configuration cycle produces a visible preview glitch.
    ///
    /// **Smooth auto-focus** makes iOS ramp focus gradually instead of stepping
    /// to the new position. A focus step changes per-frame sharpness, which both
    /// reads as instability and makes the landmark tracker jitter — the same
    /// reason the Android side pins `CONTINUOUS_VIDEO` auto-focus. Focus is NOT
    /// locked: a locked lens goes soft the moment the streamer moves, a worse
    /// regression than a slow ramp.
    ///
    /// Exposure metering is untouched here — [applyExposureBias] owns it and
    /// deliberately keeps continuous auto-exposure.
    func applyCameraStability() {
        let mode: AVCaptureVideoStabilizationMode = stabilizationEnabled ? .standard : .off
        var appliedTo = 0
        for output in captureSession.outputs {
            guard let connection = output.connection(with: .video),
                  connection.isVideoStabilizationSupported
            else { continue }
            connection.preferredVideoStabilizationMode = mode
            appliedTo += 1
        }
        if let device = currentCaptureDevice(), device.isSmoothAutoFocusSupported {
            do {
                try device.lockForConfiguration()
                device.isSmoothAutoFocusEnabled = true
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                }
                device.unlockForConfiguration()
            } catch {
                print("[SnapCameraKit] applyCameraStability focus failed (non-fatal): \(error)")
            }
        }
        print(
            "[SnapCameraKit] applyCameraStability: stabilization=\(stabilizationEnabled) "
                + "mode=\(mode.rawValue) connections=\(appliedTo)")
    }

    private func cameraDevice(for position: AVCaptureDevice.Position) -> AVCaptureDevice? {
        let discovery = AVCaptureDevice.DiscoverySession(
            deviceTypes: [.builtInWideAngleCamera],
            mediaType: .video,
            position: position)
        return discovery.devices.first ?? AVCaptureDevice.default(for: .video)
    }

    func applyLens(lensId: String, groupId: String) {
        // Claim the epoch before any async hop so ordering follows the user's
        // tap order, not Camera Kit's completion order.
        lensLock.lock()
        lensGeneration += 1
        lensLock.unlock()

        guard initialized, let session = session else {
            // Queue instead of dropping: the tap landed while the session was
            // (re)building — initialize() drains it the moment it completes.
            // Dropping meant the user's tap silently did nothing ("filter
            // won't apply" report).
            pendingLensId = lensId
            pendingGroupId = groupId
            print("[SnapCameraKit] applyLens: not initialized — queued \(lensId)/\(groupId) for post-init apply")
            return
        }
        pendingLensId = lensId
        pendingGroupId = groupId
        // Register once per lens id. Re-adding on every tap left a growing set
        // of live registrations, each re-firing `didUpdate` for the rest of the
        // session; the epoch check makes those no-ops, but not registering them
        // twice in the first place is what bounds the growth.
        if !observedLensIds.contains(lensId) {
            observedLensIds.insert(lensId)
            session.lenses.repository.addObserver(self, specificLensID: lensId, inGroupID: groupId)
        }
    }

    func clearLens() {
        // A clear is a selection too — bumping the epoch invalidates every
        // in-flight apply so a lens still loading cannot resurrect itself after
        // the user chose "None".
        lensLock.lock()
        lensGeneration += 1
        lensLock.unlock()

        // Clear BEFORE the guard: removing the filter while the session is
        // still initializing must also drop a queued lens, or it would
        // resurrect right after init.
        pendingLensId = nil
        pendingGroupId = nil
        guard initialized, let session = session else { return }
        session.lenses.processor?.clear { success in
            print("[SnapCameraKit] clearLens: cleared=\(success)")
        }
        loadedLensId = nil
        loadedGroupId = nil
    }

    func getGroupLenses(groupId: String?, completion: @escaping ([[String: Any]]) -> Void) {
        guard initialized, let session = session else {
            completion([])
            return
        }
        let gid = (groupId?.isEmpty == false) ? groupId! : (defaultGroupId ?? "")
        guard !gid.isEmpty else {
            completion([])
            return
        }
        var observerRef: GroupLensesObserver?
        let observer = GroupLensesObserver { [weak self] lenses in
            let out: [[String: Any]] = lenses.map { lens in
                var dict: [String: Any] = ["lensId": lens.id, "groupId": lens.groupId]
                if let n = lens.name { dict["name"] = n }
                if let u = lens.iconUrl?.absoluteString { dict["iconUrl"] = u }
                return dict
            }
            completion(out)
            // Stop observing once we've delivered the settled set. Registration
            // happened on the platform (main) thread — unregister there too.
            DispatchQueue.main.async {
                guard let self = self, let o = observerRef else { return }
                session.lenses.repository.removeObserver(o, groupID: gid)
                self.groupObservers.removeAll { $0 === o }
            }
        }
        observerRef = observer
        groupObservers.append(observer)
        session.lenses.repository.addObserver(observer, groupID: gid)
    }

    func setLensParameter(name: String, value: Float) {
        guard initialized, loadedLensId != nil else { return }
        print("[SnapCameraKit] setLensParameter: \(name)=\(value) (no live-param API; ignored)")
    }

    func switchCamera() {
        guard initialized else { return }
        cameraPosition = (cameraPosition == .front) ? .back : .front
        // Reconfigure the device input on a background queue; Camera Kit keeps
        // rendering from the same session.
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            self.configureCaptureSession(position: self.cameraPosition)
        }
        print("[SnapCameraKit] switchCamera: position=\(cameraPosition.rawValue)")
    }

    /// Hard-reset the OWNED capture session to the FRONT camera, purging any
    /// stale rear-lens state the singleton carried over from a previous session.
    ///
    /// This is the AVSessionInput-pattern equivalent of "force facing = front":
    /// Camera Kit reads frames from whatever device input our `captureSession`
    /// exposes, so swapping that input to the front device IS the native facing
    /// reset. (There is no `cameraLayout` / `setFacing` / `flipCamera` here —
    /// those belong to Camera Kit's higher-level `CameraController`, which this
    /// integration deliberately does not use; we own the `AVCaptureSession`.)
    ///
    /// Safe before OR after `initialize()`:
    ///   • before init → records `.front` so the session boots on the front lens.
    ///   • after  init → stops the running session (the explicit "stopRunning
    ///     before re-binding" purge), rebinds a clean front device input, then
    ///     restarts capture.
    func forceFrontCamera() {
        cameraPosition = .front
        guard initialized else {
            print("[SnapCameraKit] forceFrontCamera: pre-init — will boot front")
            return
        }
        DispatchQueue.global(qos: .userInitiated).async { [weak self] in
            guard let self = self else { return }
            let wasRunning = self.captureSession.isRunning
            // Stop stale layout/tracking before re-binding to a clean front lens.
            if wasRunning { self.captureSession.stopRunning() }
            self.configureCaptureSession(position: .front)
            if wasRunning { self.captureSession.startRunning() }
            print("[SnapCameraKit] forceFrontCamera: hard-reset to front (wasRunning=\(wasRunning))")
        }
    }

    func setRenderResolution(width: Int, height: Int) {
        guard width > 0, height > 0 else { return }
        renderWidth = width
        renderHeight = height
        print("[SnapCameraKit] setRenderResolution: \(width)x\(height)")
        // If the new render height wants a different sensor preset (qhd/2k
        // need 1080p capture instead of the 720p floor), reconfigure the live
        // session for the current camera — serialized on sessionQueue, the
        // same thread discipline the rest of the capture-session mutations
        // use. Skipped when the preset already matches: a needless
        // begin/commitConfiguration cycle causes a visible glitch.
        guard initialized else { return }
        sessionQueue.async { [weak self] in
            guard let self = self else { return }
            let ideal = self.bestSessionPreset(forRenderHeight: self.renderHeight)
            guard self.captureSession.sessionPreset != ideal else { return }
            let position = self.currentDevice()?.position ?? self.cameraPosition
            self.configureCaptureSession(position: position)
            print("[SnapCameraKit] setRenderResolution: sessionPreset → \(ideal.rawValue)")
        }
    }

    /// The capture device currently feeding Camera Kit (front or back).
    private func currentDevice() -> AVCaptureDevice? {
        (captureSession.inputs.first as? AVCaptureDeviceInput)?.device
    }

    /// Torch/flashlight on the owned camera. No-op if the device has no torch
    /// (e.g. front camera).
    func setTorch(on: Bool) {
        guard let device = currentDevice(), device.hasTorch,
              device.isTorchModeSupported(on ? .on : .off) else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = on ? .on : .off
            device.unlockForConfiguration()
        } catch {
            print("[SnapCameraKit] setTorch failed: \(error)")
        }
    }

    /// Pinch-zoom on the owned camera, clamped to the device's supported range.
    func setZoom(factor: CGFloat) {
        guard let device = currentDevice() else { return }
        do {
            try device.lockForConfiguration()
            let deviceMax = min(CGFloat(MAX_ZOOM_RATIO), device.activeFormat.videoMaxZoomFactor)
            let clamped = max(1.0, min(factor, deviceMax))
            device.videoZoomFactor = clamped
            device.unlockForConfiguration()
        } catch {
            print("[SnapCameraKit] setZoom failed: \(error)")
        }
    }

    // MARK: - Background / foreground capture recovery

    /// iOS interrupts and stops our `AVCaptureSession` whenever the app is
    /// backgrounded (also on calls, Control-Center camera grabs, etc.) and does
    /// NOT reliably auto-restart it on return. When that happens Camera Kit gets
    /// no input frames, so the published LiveKit track goes BLACK for viewers —
    /// the iOS-streamer-returns-from-background bug. Recreating the LiveKit track
    /// alone never fixed it because LiveKit doesn't own the camera — THIS class
    /// does (see the ownership note in the class doc; the older claim that Snap
    /// owned it was wrong, though the conclusion here — restart OUR session —
    /// is right either way).
    /// These observers restart the session the moment the camera is usable again,
    /// natively — so recovery happens even if the Flutter resume path is delayed
    /// or the engine was frozen.
    private func registerCaptureLifecycleObservers() {
        let nc = NotificationCenter.default
        nc.addObserver(self, selector: #selector(handleCaptureInterruptionEnded(_:)),
                       name: .AVCaptureSessionInterruptionEnded, object: captureSession)
        nc.addObserver(self, selector: #selector(handleCaptureRuntimeError(_:)),
                       name: .AVCaptureSessionRuntimeError, object: captureSession)
        nc.addObserver(self, selector: #selector(handleAppDidBecomeActive(_:)),
                       name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    private func unregisterCaptureLifecycleObservers() {
        NotificationCenter.default.removeObserver(
            self, name: .AVCaptureSessionInterruptionEnded, object: captureSession)
        NotificationCenter.default.removeObserver(
            self, name: .AVCaptureSessionRuntimeError, object: captureSession)
        NotificationCenter.default.removeObserver(
            self, name: UIApplication.didBecomeActiveNotification, object: nil)
    }

    @objc private func handleCaptureInterruptionEnded(_ note: Notification) {
        print("[SnapCameraKit] AVCaptureSession interruption ended — restarting")
        runtimeErrorRestarts = 0
        restartCaptureSession()
    }

    @objc private func handleCaptureRuntimeError(_ note: Notification) {
        // Backoff + cap. A tight error→restart→error loop (seen on-device as
        // an endless -11872 "Too many camera hardware resources" spam with a
        // BLACK stream for everyone) never gives the OS time to release the
        // competing session and burns battery forever. Escalating delays give
        // a transient holder time to let go; after the cap we stop until the
        // next fresh trigger (interruption ended / app became active / init).
        runtimeErrorRestarts += 1
        guard runtimeErrorRestarts <= maxRuntimeErrorRestarts else {
            print("[SnapCameraKit] AVCaptureSession runtime error — giving up "
                  + "after \(maxRuntimeErrorRestarts) restarts: \(String(describing: note.userInfo))")
            return
        }
        let delay = Double(runtimeErrorRestarts) * 0.8
        print("[SnapCameraKit] AVCaptureSession runtime error — restart "
              + "#\(runtimeErrorRestarts) in \(delay)s: \(String(describing: note.userInfo))")
        sessionQueue.asyncAfter(deadline: .now() + delay) { [weak self] in
            self?.restartCaptureSession()
        }
    }

    @objc private func handleAppDidBecomeActive(_ note: Notification) {
        // Some background→foreground transitions stop the session WITHOUT a
        // matching interruptionEnded, so restart here too. Idempotent. A real
        // foreground return is a fresh chance — re-arm the error budget.
        runtimeErrorRestarts = 0
        restartCaptureSession()
    }

    /// Restart the owned capture session if it has stopped. Idempotent and safe
    /// to call from BOTH the native notifications above and the Flutter resume
    /// path (MethodChannel `restartCaptureSession`) — whichever fires first wins;
    /// the `isRunning` guard makes the rest no-ops. `startRunning()` is
    /// synchronous and blocks, so it runs on the serial session queue.
    func restartCaptureSession() {
        guard initialized else { return }
        sessionQueue.async { [weak self] in
            guard let self = self, !self.captureSession.isRunning else { return }
            // If the OS tore down the device input while suspended, re-add it
            // before starting, otherwise the session restarts with no camera.
            if self.captureSession.inputs.isEmpty {
                self.configureCaptureSession(position: self.cameraPosition)
            }
            self.captureSession.startRunning()
            if self.captureSession.isRunning {
                // Healthy again — re-arm the runtime-error restart budget.
                self.runtimeErrorRestarts = 0
            }
            print("[SnapCameraKit] restartCaptureSession: running=\(self.captureSession.isRunning)")
        }
    }

    // MARK: - Camera producer lifecycle (engine-owned)
    //
    // The camera producer runs INDEPENDENTLY of any consumer (preview/encoder).
    // On iOS the AVCaptureSession is already started inside `initialize()` and is
    // never touched by the WebRTC capturer (which only sets `frameSink`), so the
    // camera already outlives publish start/stop. These methods give the Dart
    // CameraKitEngine an explicit producer on/off it can drive symmetrically with
    // Android — `startCamera` ensures the session is running, `stopCamera` powers
    // it down for a full teardown. Neither is called by the encoder path.

    /// Ensure the camera producer is running (idempotent). Same semantics as
    /// [restartCaptureSession]; exposed under the engine's producer vocabulary.
    func startCamera() {
        restartCaptureSession()
    }

    /// Power the camera producer down without disposing the Camera Kit session.
    /// Only the engine calls this (full teardown / explicit stop) — never the
    /// publisher. `stopRunning()` is synchronous, so it runs on the session queue.
    func stopCamera() {
        guard initialized else { return }
        sessionQueue.async { [weak self] in
            guard let self = self, self.captureSession.isRunning else { return }
            self.captureSession.stopRunning()
            print("[SnapCameraKit] stopCamera: running=\(self.captureSession.isRunning)")
        }
    }

    func dispose() {
        guard initialized else { return }
        unregisterCaptureLifecycleObservers()
        stopPreviewTexture()
        forceClearFrameSink()
        if let session = session {
            for entry in warmObservers {
                session.lenses.repository.removeObserver(entry.observer, groupID: entry.groupID)
            }
            session.lenses.processor?.clear { _ in }
            if captureSession.isRunning {
                captureSession.stopRunning()
            }
            if let output = output { session.remove(output: output) }
            if let viewport = viewportOutput { session.remove(output: viewport) }
            session.stop()
        }
        for input in captureSession.inputs {
            captureSession.removeInput(input)
        }
        // Also detach the AVCaptureSession-level OUTPUTS (the AVSessionInput's
        // video data output). Inputs alone are not enough: the singleton reuses
        // this session for the next live, and a leaked output per live is what
        // exhausted the camera pipeline (-11872) after several go-lives in one
        // app lifecycle.
        for output in captureSession.outputs {
            captureSession.removeOutput(output)
        }
        viewportOutput = nil
        session = nil
        output = nil
        avInput = nil
        apiToken = nil
        defaultGroupId = nil
        loadedLensId = nil
        loadedGroupId = nil
        pendingLensId = nil
        pendingGroupId = nil
        groupObservers.removeAll()
        warmObservers.removeAll()
        // The next initialize() builds a NEW session whose repository has no
        // registrations, so this set must not survive — a stale entry would
        // suppress the re-registration and the lens would never apply.
        observedLensIds.removeAll()
        // Invalidate any apply still in flight from the disposed session so its
        // completion cannot write lens state into the next one.
        lensLock.lock()
        lensGeneration += 1
        lensLock.unlock()
        initialized = false
        print("[SnapCameraKit] dispose: complete")
    }
}

// MARK: - Lens repository observer

extension SnapCameraKitController: LensRepositorySpecificObserver {
    func repository(_ repository: LensRepository, didUpdate lens: Lens, forGroupID groupID: String) {
        guard lens.id == pendingLensId else { return }
        // Capture the epoch this apply is issued under. A still-registered
        // observer for a previously-selected lens also lands here (Camera Kit
        // re-emits on cache refresh), so the identity check above is not on its
        // own sufficient to establish that this is the current selection.
        lensLock.lock()
        let gen = lensGeneration
        lensLock.unlock()

        session?.lenses.processor?.apply(lens: lens, launchData: nil) { [weak self] success in
            guard let self = self else { return }
            // Re-check before publishing: this completion can resolve after a
            // newer selection has already been applied, which is exactly how a
            // slow lens used to overwrite a fast one.
            self.lensLock.lock()
            let current = self.lensGeneration
            self.lensLock.unlock()
            guard current == gen else {
                print("[SnapCameraKit] applyLens: \(lens.id) stale (gen=\(gen) cur=\(current)) — dropped")
                return
            }
            if success {
                self.loadedLensId = lens.id
                self.loadedGroupId = groupID
            }
            print("[SnapCameraKit] applyLens: \(lens.id) applied=\(success) gen=\(gen)")
        }
    }

    func repository(_ repository: LensRepository, didFailToUpdateLensID lensID: String, forGroupID groupID: String, error: Error?) {
        print("[SnapCameraKit] applyLens: failed to fetch \(lensID)/\(groupID): \(String(describing: error))")
    }
}

// MARK: - Lens hint delegate

extension SnapCameraKitController: LensHintDelegate {
    func lensProcessor(_ lensProcessor: LensProcessor, shouldDisplayHint hint: String, for lens: Lens, autohide: Bool) {
        // Render in our own Flutter overlay (SnapLensHintOverlay), not Camera
        // Kit's view. Auto-hide is handled there.
        hintSink?(hint)
    }

    func lensProcessor(_ lensProcessor: LensProcessor, shouldHideAllHintsFor lens: Lens) {
        hintSink?(nil)
    }
}

// MARK: - Error handler

private final class SnapErrorHandler: NSObject, ErrorHandler {
    func handleError(_ error: NSException) {
        print("[SnapCameraKit] Camera Kit error (filters disabled for session): \(error.name.rawValue) — \(error.reason ?? "")")
    }
}

// MARK: - Group lens enumeration (settle-then-fire observer)

/// Snap delivers a group's lens metadata INCREMENTALLY via repeated
/// `didUpdateLenses` callbacks ("Notifies that an observed group's lenses have
/// changed"). The old observer took the FIRST callback and unsubscribed — a
/// partial list (the "~15 of ~40 lenses" bug). This one stays registered, keeps
/// the latest snapshot, and fires `onSettled` once the callbacks go quiet for
/// `settleDelay` (or `maxWait` elapses). All state is confined to a private
/// serial queue.
private final class GroupLensesObserver: NSObject, LensRepositoryGroupObserver {
    private let onSettled: ([Lens]) -> Void
    private let settleDelay: TimeInterval
    /// Quiet window for an EMPTY snapshot — deliberately wider than
    /// `settleDelay`: an early empty emission during a cold-repository sync is
    /// normal and the cloud batch usually lands within a couple of seconds, so
    /// an empty answer must stay open longer before it is believed.
    private let emptySettleDelay: TimeInterval
    private let minWait: TimeInterval
    private let maxWait: TimeInterval
    private let queue = DispatchQueue(label: "net.vibezo.snap.group-lenses")
    /// The LATEST snapshot Snap reported for this group. didUpdateLenses delivers
    /// the FULL current set on every change — it GROWS as the group syncs and
    /// SHRINKS when a lens is removed in the dashboard — so we keep the most
    /// recent snapshot, NOT a union. A union never drops a removed lens (the
    /// "still shows the deleted filter" bug). The settle + min-wait gates wait
    /// for the snapshot to stop changing, so we deliver the complete, CURRENT
    /// set (not an early partial during sync).
    private var latest: [Lens] = []
    private var latestIds: Set<String> = []
    private var settleItem: DispatchWorkItem?
    private var delivered = false
    /// Delivery requires BOTH: the snapshot has been quiet for `settleDelay`
    /// (quietReached) AND the `minWait` floor has passed (minWaitPassed). The
    /// floor stops us settling on the instantly-available cached subset (~5–10)
    /// before the cloud round-trip brings the rest of the group.
    private var quietReached = false
    private var minWaitPassed = false
    /// True once Snap has delivered ANY `didUpdateLenses` callback — including
    /// an explicit EMPTY set. An explicit empty is an ANSWER ("this group has
    /// zero lenses for this token"), not silence: without this flag the first
    /// empty emission never armed the quiet timer (`ids == latestIds`, both
    /// empty) and the call burned the full `maxWait` (12s × the Dart warm-up
    /// retries ≈ 36s of spinner) before reporting the same 0.
    private var sawCallback = false

    init(
        settleDelay: TimeInterval = 1.5,
        emptySettleDelay: TimeInterval = 4.0,
        minWait: TimeInterval = 2.5,
        maxWait: TimeInterval = 12.0,
        onSettled: @escaping ([Lens]) -> Void
    ) {
        self.onSettled = onSettled
        self.settleDelay = settleDelay
        self.emptySettleDelay = emptySettleDelay
        self.minWait = minWait
        self.maxWait = maxWait
        super.init()
        queue.asyncAfter(deadline: .now() + minWait) { [weak self] in
            guard let self = self else { return }
            self.minWaitPassed = true
            self.trySettle()
        }
        queue.asyncAfter(deadline: .now() + maxWait) { [weak self] in self?.deliver("maxWait") }
    }

    func repository(_ repository: LensRepository, didUpdateLenses lenses: [Lens], forGroupID groupID: String) {
        queue.async { [weak self] in
            guard let self = self, !self.delivered else { return }
            var snapshot: [Lens] = []
            var ids: Set<String> = []
            for lens in lenses {
                let id = lens.id
                if id.isEmpty || ids.contains(id) { continue }
                ids.insert(id)
                snapshot.append(lens)
            }
            print("[SnapCameraKit] groupLenses[\(groupID)] snapshot=\(snapshot.count)")
            // Re-arm the quiet timer whenever the snapshot CHANGES (grows OR
            // shrinks) — and also on the very FIRST callback even when it is
            // identical to the initial empty state, so an explicit "0 lenses"
            // answer arms the timer instead of silently waiting out maxWait.
            let isFirstCallback = !self.sawCallback
            self.sawCallback = true
            guard ids != self.latestIds || isFirstCallback else { return }
            self.latest = snapshot
            self.latestIds = ids
            self.quietReached = false
            self.settleItem?.cancel()
            let item = DispatchWorkItem { [weak self] in
                guard let self = self else { return }
                self.quietReached = true
                self.trySettle()
            }
            self.settleItem = item
            let delay = snapshot.isEmpty ? self.emptySettleDelay : self.settleDelay
            self.queue.asyncAfter(deadline: .now() + delay, execute: item)
        }
    }

    func repository(_ repository: LensRepository, didFailToUpdateLensesForGroupID groupID: String, error: Error?) {
        queue.async { [weak self] in self?.deliver("error") }
    }

    /// Settle only once the snapshot has gone quiet (`quietReached`) and the
    /// minimum wait floor has passed (`minWaitPassed`). An EMPTY snapshot is
    /// deliverable only when Snap actually answered (`sawCallback`, with the
    /// wider `emptySettleDelay` quiet window) — pure silence (no callback at
    /// all) still waits for the maxWait cap, since silence means the repo
    /// never responded, not that the group is empty.
    private func trySettle() {
        guard !delivered, quietReached, minWaitPassed, (!latest.isEmpty || sawCallback) else { return }
        deliver(latest.isEmpty ? "settle-empty" : "settle")
    }

    private func deliver(_ reason: String) {
        guard !delivered else { return }
        delivered = true
        settleItem?.cancel()
        print("[SnapCameraKit] groupLenses -> \(latest.count) lenses (\(reason))")
        onSettled(latest)
    }
}

// MARK: - Group lens warming (no-op observer triggers the fetch)

/// Registered solely to trigger (and keep warm) a group's lens fetch; its
/// callbacks are intentionally empty.
private final class WarmGroupObserver: NSObject, LensRepositoryGroupObserver {
    func repository(_ repository: LensRepository, didUpdateLenses lenses: [Lens], forGroupID groupID: String) {}
    func repository(_ repository: LensRepository, didFailToUpdateLensesForGroupID groupID: String, error: Error?) {}
}

// MARK: - Output (pull lens-processed CVPixelBuffers out of Camera Kit)

private final class PixelBufferOutput: NSObject, Output, OutputRequiringPixelBuffer {
    var currentlyRequiresPixelBuffer: Bool = true
    weak var delegate: SCCameraKitOutputRequiringPixelBufferDelegate?

    private let onPixelBuffer: (CVPixelBuffer, Int64) -> Void

    init(onPixelBuffer: @escaping (CVPixelBuffer, Int64) -> Void) {
        self.onPixelBuffer = onPixelBuffer
    }

    func cameraKit(_ cameraKit: CameraKitProtocol, didOutputTexture texture: Texture) {}

    func cameraKit(_ cameraKit: CameraKitProtocol, didOutputVideoSampleBuffer sampleBuffer: CMSampleBuffer) {
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        let pts = CMSampleBufferGetPresentationTimeStamp(sampleBuffer)
        let timestampNs = pts.isValid ? Int64(CMTimeGetSeconds(pts) * 1_000_000_000) : 0
        onPixelBuffer(pixelBuffer, timestampNs)
    }

    func cameraKit(_ cameraKit: CameraKitProtocol, didOutputAudioSampleBuffer sampleBuffer: CMSampleBuffer) {}
}

// MARK: - No-op AR input (fills the required arInput without starting ARKit)

private final class NoOpARInput: NSObject, ARInput {
    let session = ARSession()
    weak var arDelegate: ARInputDelegate?
    let frontCameraConfiguration: ARConfiguration = ARWorldTrackingConfiguration()

    func run(_ configuration: ARConfiguration, options: ARSession.RunOptions) {}
    func pause() {}

    weak var destination: InputDestination?
    var horizontalFieldOfView: CGFloat = 60.0
    var frameSize: CGSize = .zero
    var frameOrientation: AVCaptureVideoOrientation = .portrait
    var position: AVCaptureDevice.Position = .front
    private(set) var isRunning: Bool = false

    func startRunning() { isRunning = true }
    func stopRunning() { isRunning = false }
    func setVideoOrientation(_ videoOrientation: AVCaptureVideoOrientation) {
        frameOrientation = videoOrientation
    }
}

// MARK: - Viewport output (headless render target dimensions)

private final class ViewportOutput: NSObject, Output, OutputViewportProviding {
    weak var delegate: OutputViewportProvidingDelegate?
    let viewportSize: CGSize
    let outputResolution: CGSize
    let safeArea: CGRect = .zero

    init(width: Int, height: Int) {
        let size = CGSize(width: width, height: height)
        viewportSize = size
        outputResolution = size
    }

    func cameraKit(_ cameraKit: CameraKitProtocol, didOutputTexture texture: Texture) {}
    func cameraKit(_ cameraKit: CameraKitProtocol, didOutputVideoSampleBuffer sampleBuffer: CMSampleBuffer) {}
    func cameraKit(_ cameraKit: CameraKitProtocol, didOutputAudioSampleBuffer sampleBuffer: CMSampleBuffer) {}
}

private let DEFAULT_RENDER_W = 720
private let DEFAULT_RENDER_H = 1280

/// Exposure bias every stream runs at until the streamer changes it — the value
/// that shipped hard-coded before the control existed, kept as the default so an
/// untouched account looks exactly as it did.
private let DEFAULT_EXPOSURE_BIAS_EV: Double = 0.6

/// Bounds on what the streamer may ask for. Wider than the ±1 EV a phone camera
/// app usually offers (the front camera genuinely needs the headroom in a dim
/// room) but far short of the full range some devices advertise, where the
/// picture is pure noise or pure white and the streamer cannot tell the app is
/// doing what they asked.
private let MIN_EXPOSURE_BIAS_EV: Double = -2.0
private let MAX_EXPOSURE_BIAS_EV: Double = 2.0

/// Ceiling on digital zoom offered to the streamer. Devices advertise far more,
/// but past this the crop is upscaled mush at broadcast bitrates — and a live
/// encoder spends its bits on that mush instead of the face.
private let MAX_ZOOM_RATIO: Double = 6.0

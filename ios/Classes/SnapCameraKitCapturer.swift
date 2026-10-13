import CoreVideo
import Foundation
import WebRTC

/// Custom `RTCVideoCapturer` that publishes **Camera Kit's lens‑processed
/// output** into LiveKit — the heart of the "official" pipeline.
///
/// Camera Kit owns the camera and renders (see `SnapCameraKitController`). This
/// capturer registers a frame sink on the controller; each processed
/// `CVPixelBuffer` is wrapped as an `RTCVideoFrame` and handed to the WebRTC
/// delegate (the fork's `VideoProcessingAdapter`), which forwards it to the
/// `RTCVideoSource` → LiveKit encoder. No OS camera capturer is involved — that
/// removes the old double round‑trip and the lag it caused.
///
/// The flutter_webrtc fork creates this (via the registered factory) in place
/// of `RTCCameraVideoCapturer` and never calls `startCaptureWithDevice:`; this
/// capturer is "live" as soon as it's constructed. The fork only calls
/// `stopCaptureWithCompletionHandler:` on teardown.
final class SnapCameraKitCapturer: RTCVideoCapturer {

    private weak var controller: SnapCameraKitController?

    init(controller: SnapCameraKitController, delegate: RTCVideoCapturerDelegate) {
        self.controller = controller
        super.init(delegate: delegate)
        // Register THIS capturer as the sink owner. The owner tag lets the
        // controller reject a clear from a stale capturer (see stopCapture).
        controller.setFrameSink({ [weak self] pixelBuffer, timestampNs in
            guard let self = self, let delegate = self.delegate else { return }
            let rtcBuffer = RTCCVPixelBuffer(pixelBuffer: pixelBuffer)
            // Camera Kit outputs already‑upright, already‑mirrored frames
            // (AVSessionInput handles front‑camera mirroring), so rotation is 0.
            let frame = RTCVideoFrame(buffer: rtcBuffer, rotation: ._0, timeStampNs: timestampNs)
            delegate.capturer(self, didCapture: frame)
        }, owner: self)
    }

    /// Matches `RTCCameraVideoCapturer`'s stop signature so the fork's stop
    /// handler can call it uniformly. Unregisters the sink ONLY if this capturer
    /// still owns it — on iOS the fork fires this ASYNC, so a previous session's
    /// capturer can stop AFTER the next live's capturer has already taken over;
    /// an unconditional clear there nulled the live sink → white camera. The
    /// owner check makes the stale stop a no-op. The controller's camera/session
    /// lifecycle is owned separately (its `dispose`).
    @objc(stopCaptureWithCompletionHandler:)
    func stopCapture(completionHandler: (() -> Void)?) {
        controller?.clearFrameSink(owner: self)
        completionHandler?()
    }
}

import CoreVideo
import Flutter
import Foundation
import QuartzCore

/// Phase-1 POC — the **independent** local-preview sink.
///
/// This is Branch A of the fan-out in `SnapCameraKitController`: it receives the
/// SAME lens-processed `CVPixelBuffer` that Branch B (the WebRTC encoder) does,
/// but renders it as a Flutter external texture. It has **zero** references to
/// `RTCVideoTrack` / `RTCVideoRenderer` / `RTCVideoSource` / the encoder / sink
/// wants / the network. Nothing downstream of the encoder can reach it.
///
/// Ownership / lifetime (proven behaviour):
///  • Camera Kit owns the source buffer (its `CMSampleBuffer` pool). We RETAIN
///    the latest buffer here (holding it prevents Camera Kit's pool from
///    recycling it, so the frame Flutter reads can never tear), and release the
///    previous one on each push. Flutter takes its own +1 ref in
///    `copyPixelBuffer()`. Net extra retention: one in-flight buffer.
///  • `push(_:timestampNs:)` runs on Camera Kit's output queue.
///  • `copyPixelBuffer()` is called by the Flutter engine on its raster thread.
///  • A single `NSLock` guards the one-slot handoff between those two threads.
///
/// If Camera Kit's pool is too small for one extra retained buffer, the
/// instrumentation (`droppedByPoolStarvation` proxy: a fall in `pushCount`
/// cadence while attached) surfaces it — at which point the fallback is to
/// memcpy into an owned pool instead of retaining. POC keeps the cheaper
/// retain-latest path and measures.
final class SnapPreviewTexture: NSObject, FlutterTexture {

    private let lock = NSLock()
    private var latest: CVPixelBuffer?

    // --- Instrumentation (read via SnapCameraKitController.pocMetrics) ---
    /// Frames handed to this preview sink by the fan-out.
    private(set) var pushCount: Int = 0
    /// Frames the Flutter engine actually pulled (≈ one per vsync).
    private(set) var pullCount: Int = 0
    /// Frames superseded before Flutter pulled them (push without an intervening
    /// pull) — the preview's own drop count, independent of the encoder.
    private(set) var droppedCount: Int = 0
    /// Wall-clock of the last push (CACurrentMediaTime seconds) and the last
    /// inter-push delta (ms) — the preview cadence signal.
    private(set) var lastPushAtSec: Double = 0
    private(set) var lastPushDeltaMs: Double = 0
    /// True once we've confirmed the source buffer is IOSurface-backed (required
    /// for Flutter's iOS external-texture path). If false, the preview will be
    /// black and a copy/convert path is needed — logged once.
    private(set) var ioSurfaceBacked: Bool = false

    /// One-slot flag: has the current `latest` been pulled since it was pushed?
    private var latestPulled = true
    private var ioSurfaceChecked = false

    /// Fan-out entry (Branch A). Retains the newest processed frame and signals
    /// the engine. Never blocks Camera Kit's output queue beyond the lock.
    func push(_ pixelBuffer: CVPixelBuffer, timestampNs: Int64, notify: (() -> Void)?) {
        if !ioSurfaceChecked {
            ioSurfaceChecked = true
            ioSurfaceBacked = (CVPixelBufferGetIOSurface(pixelBuffer) != nil)
            if !ioSurfaceBacked {
                print("[SnapPreviewPOC] WARNING: source CVPixelBuffer is NOT IOSurface-backed "
                      + "(format=\(CVPixelBufferGetPixelFormatType(pixelBuffer))) — Flutter texture "
                      + "may render black; a copy/convert path is required.")
            }
        }
        let now = CACurrentMediaTime()
        lock.lock()
        if !latestPulled { droppedCount += 1 }   // previous frame never consumed
        latest = pixelBuffer                       // ARC retains new, releases old
        latestPulled = false
        pushCount += 1
        if lastPushAtSec > 0 { lastPushDeltaMs = (now - lastPushAtSec) * 1000.0 }
        lastPushAtSec = now
        lock.unlock()
        notify?()
    }

    // MARK: FlutterTexture

    func copyPixelBuffer() -> Unmanaged<CVPixelBuffer>? {
        lock.lock()
        defer { lock.unlock() }
        guard let buffer = latest else { return nil }
        latestPulled = true
        pullCount += 1
        return Unmanaged.passRetained(buffer)
    }

    /// Drop the retained frame so Camera Kit's pool can recycle it on teardown.
    func clear() {
        lock.lock()
        latest = nil
        latestPulled = true
        lock.unlock()
    }
}

import Flutter
import flutter_webrtc
import UIKit
import WebRTC

/// Plugin entry point on iOS (official "Camera Kit owns the camera" pattern).
///
/// On `initialize`, the controller builds a Camera Kit session that OWNS the
/// camera and renders lenses, and we register a custom-capturer factory on the
/// flutter_webrtc fork. When LiveKit then creates its camera track, the fork
/// builds a `SnapCameraKitCapturer` (instead of `RTCCameraVideoCapturer`) that
/// publishes Camera Kit's lens-processed output into the LiveKit pipeline.
///
/// There is no per-frame processor anymore — frames never round-trip through
/// LiveKit's camera capturer, which is what removes the lag.
public class SnapCameraKitProcessorPlugin: NSObject, FlutterPlugin {
    private let controller = SnapCameraKitController()

    public static func register(with registrar: FlutterPluginRegistrar) {
        let channel = FlutterMethodChannel(
            name: "vibezo.net/snap_camera_kit_processor",
            binaryMessenger: registrar.messenger())
        let instance = SnapCameraKitProcessorPlugin()
        instance.controller.setPluginRegistrar(registrar)
        registrar.addMethodCallDelegate(instance, channel: channel)

        // Transparent touch overlay PlatformView (interactive lenses).
        registrar.register(
            SnapTouchPlatformViewFactory(controller: instance.controller),
            withId: "vibezo.net/snap_touch_view")

        // Lens hints → Flutter (rendered by SnapLensHintOverlay). nil = hide.
        instance.controller.hintSink = { [weak channel] text in
            DispatchQueue.main.async {
                channel?.invokeMethod("onHint", arguments: ["text": text as Any])
            }
        }
    }

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        switch call.method {
        case "initialize":
            guard let args = call.arguments as? [String: Any],
                  let apiToken = args["apiToken"] as? String, !apiToken.isEmpty,
                  let defaultGroupId = args["defaultGroupId"] as? String, !defaultGroupId.isEmpty
            else {
                result(FlutterError(code: "BAD_ARGS", message: "apiToken and defaultGroupId required", details: nil))
                return
            }
            let groupIds = (args["groupIds"] as? [String]) ?? []
            let ok = controller.initialize(apiToken: apiToken, defaultGroupId: defaultGroupId, groupIds: groupIds)
            if ok {
                // Make the fork use Camera Kit's output capturer for the next
                // camera track. Weakly capture the controller; if Snap is gone,
                // the fork falls back to the OS camera.
                let snapController = controller
                FlutterWebRTCPlugin.setExternalVideoCapturerFactory { [weak snapController] delegate in
                    guard let snapController = snapController else { return nil }
                    return SnapCameraKitCapturer(controller: snapController, delegate: delegate)
                }
            } else {
                // Snap unavailable — ensure the fork uses the normal camera.
                FlutterWebRTCPlugin.setExternalVideoCapturerFactory(nil)
            }
            result(ok)

        case "applyLens":
            guard let args = call.arguments as? [String: Any],
                  let lensId = args["lensId"] as? String, !lensId.isEmpty,
                  let groupId = args["groupId"] as? String, !groupId.isEmpty
            else {
                result(FlutterError(code: "BAD_ARGS", message: "lensId and groupId required", details: nil))
                return
            }
            controller.applyLens(lensId: lensId, groupId: groupId)
            result(nil)

        case "clearLens":
            controller.clearLens()
            result(nil)

        case "setLensParameter":
            guard let args = call.arguments as? [String: Any],
                  let name = args["name"] as? String,
                  let value = args["value"] as? Double
            else {
                result(FlutterError(code: "BAD_ARGS", message: "name and value required", details: nil))
                return
            }
            controller.setLensParameter(name: name, value: Float(value))
            result(nil)

        case "switchCamera":
            controller.switchCamera()
            result(nil)

        case "forceFrontCamera":
            controller.forceFrontCamera()
            result(nil)

        case "restartCaptureSession":
            controller.restartCaptureSession()
            result(nil)

        case "startCamera":
            controller.startCamera()
            result(nil)

        case "stopCamera":
            controller.stopCamera()
            result(nil)

        case "setTorch":
            let on = (call.arguments as? [String: Any])?["on"] as? Bool ?? false
            controller.setTorch(on: on)
            result(nil)

        case "setZoom":
            let zoom = (call.arguments as? [String: Any])?["zoom"] as? Double ?? 1.0
            controller.setZoom(factor: CGFloat(zoom))
            result(nil)

        case "setExposureBias":
            guard let ev = (call.arguments as? [String: Any])?["ev"] as? Double else {
                result(FlutterError(code: "BAD_ARGS", message: "ev required", details: nil))
                return
            }
            controller.setExposureBias(ev)
            result(nil)

        case "getCameraCapabilities":
            result(controller.cameraCapabilities())

        case "setRenderResolution":
            guard let args = call.arguments as? [String: Any],
                  let width = args["width"] as? Int,
                  let height = args["height"] as? Int,
                  width > 0, height > 0
            else {
                result(FlutterError(code: "BAD_ARGS", message: "width and height required (>0)", details: nil))
                return
            }
            controller.setRenderResolution(width: width, height: height)
            result(nil)

        case "setCaptureFrameRate":
            guard let args = call.arguments as? [String: Any],
                  let fps = args["fps"] as? Int,
                  fps > 0
            else {
                result(FlutterError(code: "BAD_ARGS", message: "fps required (>0)", details: nil))
                return
            }
            controller.setCaptureFrameRate(fps)
            result(nil)

        case "setVideoStabilization":
            guard let args = call.arguments as? [String: Any],
                  let enabled = args["enabled"] as? Bool
            else {
                result(FlutterError(code: "BAD_ARGS", message: "enabled required", details: nil))
                return
            }
            controller.setVideoStabilization(enabled)
            result(nil)

        case "checkGpuAcceleration":
            result(true)

        // ── Phase-1 POC: independent preview texture ──────────────────────────
        case "startPreviewTexture":
            // Returns the Flutter external-texture id (or nil if unavailable).
            result(controller.startPreviewTexture())

        case "stopPreviewTexture":
            controller.stopPreviewTexture()
            result(nil)

        case "getPocMetrics":
            result(controller.pocMetrics())

        case "getGroupLenses":
            let groupId = (call.arguments as? [String: Any])?["groupId"] as? String
            // One-shot guard at the channel boundary. A FlutterResult MUST be
            // invoked exactly once — a second call hits Flutter's
            // "Reply already submitted" fatalError and crashes the app to the
            // home screen (the classic iOS "crash to home"). The lens observer is
            // single-delivery today, but guarding the boundary makes a double
            // delivery from any future/edge path (settle vs. maxWait vs. error
            // racing) impossible to crash the streamer mid-live.
            let resultLock = NSLock()
            var resultSent = false
            controller.getGroupLenses(groupId: groupId) { lenses in
                resultLock.lock()
                let alreadySent = resultSent
                resultSent = true
                resultLock.unlock()
                if alreadySent { return }
                DispatchQueue.main.async { result(lenses) }
            }

        case "dispose":
            FlutterWebRTCPlugin.setExternalVideoCapturerFactory(nil)
            controller.dispose()
            result(nil)

        default:
            result(FlutterMethodNotImplemented)
        }
    }
}

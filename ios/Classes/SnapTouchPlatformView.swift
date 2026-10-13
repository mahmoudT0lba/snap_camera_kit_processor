import Flutter
import UIKit

/// Embeds a transparent touch overlay (`UiKitView`, view type
/// `vibezo.net/snap_touch_view`) into the Flutter tree. The overlay is handed to
/// [SnapCameraKitController.attachTouchView], which calls Camera Kit's
/// `configureTouchHandling(in:)` so the active lens receives touches + gestures
/// (interactive/game lenses). It does not render anything itself — the lens
/// output is shown by the LiveKit local preview underneath.
class SnapTouchPlatformViewFactory: NSObject, FlutterPlatformViewFactory {
    private weak var controller: SnapCameraKitController?

    init(controller: SnapCameraKitController) {
        self.controller = controller
        super.init()
    }

    func create(withFrame frame: CGRect, viewIdentifier viewId: Int64, arguments args: Any?) -> FlutterPlatformView {
        return SnapTouchPlatformView(frame: frame, controller: controller)
    }

    func createArgsCodec() -> FlutterMessageCodec & NSObjectProtocol {
        return FlutterStandardMessageCodec.sharedInstance()
    }
}

class SnapTouchPlatformView: NSObject, FlutterPlatformView {
    private let touchView: UIView
    private weak var controller: SnapCameraKitController?

    init(frame: CGRect, controller: SnapCameraKitController?) {
        self.controller = controller
        touchView = UIView(frame: frame)
        touchView.backgroundColor = .clear
        touchView.isUserInteractionEnabled = true
        super.init()
        controller?.attachTouchView(touchView)
    }

    func view() -> UIView { return touchView }

    deinit {
        controller?.detachTouchView()
    }
}

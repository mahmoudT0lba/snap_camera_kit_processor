package net.vibezo.snap_camera_kit_processor

import android.content.Context
import android.view.View
import android.view.ViewGroup
import io.flutter.plugin.common.StandardMessageCodec
import io.flutter.plugin.platform.PlatformView
import io.flutter.plugin.platform.PlatformViewFactory

/**
 * Embeds the controller's preview container into the Flutter tree (via
 * `AndroidView`, view type `vibezo.net/snap_touch_view`).
 *
 * The container is OWNED by [SnapCameraKitController]: it holds the `ViewStub`
 * Camera Kit inflates (via `attachTo`) with its on-screen preview (camera +
 * lens). On Android this is the ONLY way lenses receive touch. The container is
 * transparent until Camera Kit inflates it, so the LiveKit preview shows through
 * underneath in the meantime.
 */
class SnapTouchPlatformViewFactory(
    private val controller: SnapCameraKitController,
) : PlatformViewFactory(StandardMessageCodec.INSTANCE) {

    override fun create(context: Context, viewId: Int, args: Any?): PlatformView {
        val view = controller.getOrCreatePreviewView(context)
        // The container is a singleton; detach from any previous Flutter parent
        // before re-embedding so it never briefly has two parents (would crash).
        (view.parent as? ViewGroup)?.removeView(view)
        return SnapTouchPlatformView(view)
    }
}

private class SnapTouchPlatformView(private val view: View) : PlatformView {

    override fun getView(): View = view

    override fun dispose() {
        // The controller owns the view's lifecycle; just detach it from Flutter's
        // container so the same instance can be re-embedded on the next session.
        (view.parent as? ViewGroup)?.removeView(view)
    }
}

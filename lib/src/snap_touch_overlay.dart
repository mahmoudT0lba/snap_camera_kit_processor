import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';

/// Hosts the native view Camera Kit needs for interactive/game lenses to receive
/// touch (High Score, Hand Gestures, Soundboard, typing lenses, …).
///
/// - **Android:** Camera Kit only delivers touch to lenses when it renders its
///   own on-screen preview into a `ViewStub` (`Session.Builder.attachTo`). So
///   this embeds Camera Kit's **preview** (camera + lens) via **Hybrid
///   Composition** (`initExpensiveAndroidView`) — needed for real `MotionEvent`s
///   + keyboard. Transparent until Camera Kit inflates it, so the LiveKit preview
///   shows through underneath until then.
/// - **iOS:** a transparent overlay; `configureTouchHandling(in:)` attaches
///   gesture recognizers at runtime and forwards to the lens (works headless).
///
/// Place it above the camera preview and below the Flutter controls so
/// buttons/lens-picker still win their hits. The native view is OWNED by the
/// Camera Kit controller — the factory returns that same instance.
class SnapTouchOverlay extends StatelessWidget {
  const SnapTouchOverlay({super.key});

  static const String _viewType = 'vibezo.net/snap_touch_view';

  static Set<Factory<OneSequenceGestureRecognizer>> get _gestures => {
    const Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
  };

  @override
  Widget build(BuildContext context) {
    switch (defaultTargetPlatform) {
      case TargetPlatform.iOS:
        return UiKitView(
          viewType: _viewType,
          creationParamsCodec: const StandardMessageCodec(),
          gestureRecognizers: _gestures,
        );
      case TargetPlatform.android:
        return PlatformViewLink(
          viewType: _viewType,
          surfaceFactory: (context, controller) => AndroidViewSurface(
            controller: controller as AndroidViewController,
            hitTestBehavior: PlatformViewHitTestBehavior.opaque,
            gestureRecognizers: _gestures,
          ),
          onCreatePlatformView: (params) {
            final controller = PlatformViewsService.initExpensiveAndroidView(
              id: params.id,
              viewType: _viewType,
              layoutDirection: TextDirection.ltr,
              creationParamsCodec: const StandardMessageCodec(),
              onFocus: () => params.onFocusChanged(true),
            );
            controller.addOnPlatformViewCreatedListener(
              params.onPlatformViewCreated,
            );
            controller.create();
            return controller;
          },
        );
      default:
        return const SizedBox.shrink();
    }
  }
}

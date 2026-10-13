# Consumer R8/ProGuard rules for snap_camera_kit_processor — shipped to any app
# that minifies its release build.
#
# SnapCameraKitController reaches CameraX's Camera/Preview objects by REFLECTION
# on CameraXImageProcessorSource's private fields (the SDK exposes no accessor),
# and reaches the Camera2 interop classes via Class.forName + getMethod. Without
# these rules R8 renames all of them and applyCameraTuning ends in
# "no bound camera — deferred" for the whole session: no sensor fps pin, no
# AF/EIS/AE locks, no camIn= readout. Found 2026-09-09 (release-only, X6532).
-keep class com.snap.camerakit.support.camerax.CameraXImageProcessorSource { *; }
-keepclassmembers class com.snap.camerakit.support.camerax.CameraXImageProcessorSource { *; }
-keep class androidx.camera.camera2.interop.Camera2CameraInfo { *; }
-keep class androidx.camera.camera2.interop.Camera2CameraControl { *; }
-keep class androidx.camera.camera2.interop.Camera2Interop** { *; }
-keep class androidx.camera.camera2.interop.CaptureRequestOptions** { *; }

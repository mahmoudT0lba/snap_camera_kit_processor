package net.vibezo.snap_camera_kit_processor

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.NonNull
import com.cloudwebrtc.webrtc.GetUserMediaImpl
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.embedding.engine.plugins.activity.ActivityAware
import io.flutter.embedding.engine.plugins.activity.ActivityPluginBinding
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import io.flutter.plugin.common.MethodChannel.MethodCallHandler
import io.flutter.plugin.common.MethodChannel.Result

/**
 * Plugin entry point — "official" Camera Kit pipeline.
 *
 * On `initialize` we register an [GetUserMediaImpl.ExternalVideoCapturerFactory]
 * on the flutter_webrtc fork. When LiveKit then creates a camera track, the fork
 * instantiates a [SnapCameraVideoCapturer] instead of the OS `Camera2Capturer`:
 * Camera Kit owns the camera, renders the active lens in its own GPU pipeline,
 * and pushes the processed frames straight into the WebRTC source. This removes
 * the old "feed frames in, pull processed frames back" round-trip (the lag).
 *
 * The MethodChannel still drives lens apply/clear, camera flip/torch/zoom, and
 * render resolution via [SnapCameraKitController].
 */
class SnapCameraKitProcessorPlugin : FlutterPlugin, MethodCallHandler, ActivityAware {

    private lateinit var channel: MethodChannel
    private lateinit var context: Context
    private val controller = SnapCameraKitController()
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onAttachedToEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        context = binding.applicationContext
        controller.setPluginBinding(binding)
        channel = MethodChannel(binding.binaryMessenger, "vibezo.net/snap_camera_kit_processor")
        channel.setMethodCallHandler(this)

        // Camera Kit on-screen preview PlatformView (interactive lenses on Android).
        binding.platformViewRegistry.registerViewFactory(
            "vibezo.net/snap_touch_view",
            SnapTouchPlatformViewFactory(controller),
        )

        // Lens hints → Flutter (rendered by SnapLensHintOverlay). null = hide.
        controller.hintSink = { text ->
            mainHandler.post {
                channel.invokeMethod("onHint", mapOf("text" to text))
            }
        }
        Log.d(TAG, "Attached to engine")
    }

    override fun onMethodCall(@NonNull call: MethodCall, @NonNull result: Result) {
        when (call.method) {
            "initialize" -> {
                val apiToken = call.argument<String>("apiToken")
                val defaultGroupId = call.argument<String>("defaultGroupId")
                val groupIds = call.argument<List<String>>("groupIds") ?: emptyList()
                if (apiToken.isNullOrBlank() || defaultGroupId.isNullOrBlank()) {
                    result.error("BAD_ARGS", "apiToken and defaultGroupId are required", null)
                    return
                }
                controller.initialize(context, apiToken, defaultGroupId, groupIds) { ok ->
                    if (ok) {
                        // Register the official-pipeline capturer factory: the next
                        // createCameraTrack on the fork will use Camera Kit's camera.
                        GetUserMediaImpl.setExternalVideoCapturerFactory {
                            SnapCameraVideoCapturer(controller)
                        }
                    }
                    mainHandler.post { result.success(ok) }
                }
            }

            "applyLens" -> {
                val lensId = call.argument<String>("lensId")
                val groupId = call.argument<String>("groupId")
                if (lensId.isNullOrBlank() || groupId.isNullOrBlank()) {
                    result.error("BAD_ARGS", "lensId and groupId are required", null)
                    return
                }
                controller.applyLens(lensId, groupId)
                result.success(null)
            }

            "clearLens" -> {
                controller.clearLens()
                result.success(null)
            }

            "setLensParameter" -> {
                val name = call.argument<String>("name")
                val value = call.argument<Double>("value")
                if (name == null || value == null) {
                    result.error("BAD_ARGS", "name and value are required", null)
                    return
                }
                controller.setLensParameter(name, value.toFloat())
                result.success(null)
            }

            "switchCamera" -> {
                controller.switchCamera()
                result.success(null)
            }

            // Entry-device input reduction: 4:3 CameraX preview instead of the
            // SDK-default 16:9 — see SnapCameraKitController.setCameraInput43.
            "setCameraInput43" -> {
                controller.setCameraInput43(call.argument<Boolean>("enabled") ?: false)
                result.success(null)
            }

            // Engine-owned camera producer lifecycle. Decouples camera start/stop
            // from the WebRTC capturer (which must only attach/detach its output).
            "startCamera" -> {
                controller.startCamera()
                result.success(null)
            }

            "stopCamera" -> {
                controller.stopCamera()
                result.success(null)
            }

            "setTorch" -> {
                val on = call.argument<Boolean>("on") ?: false
                controller.setTorch(on)
                result.success(null)
            }

            "setZoom" -> {
                val zoom = call.argument<Double>("zoom") ?: 1.0
                controller.setZoom(zoom.toFloat())
                result.success(null)
            }

            "getCameraCapabilities" -> {
                controller.cameraCapabilities { caps -> result.success(caps) }
            }

            "setExposureBias" -> {
                val ev = call.argument<Double>("ev")
                if (ev == null) {
                    result.error("BAD_ARGS", "ev required", null)
                    return
                }
                controller.setExposureBias(ev.toFloat())
                result.success(null)
            }

            "setCaptureFrameRate" -> {
                val fps = call.argument<Int>("fps") ?: 0
                if (fps <= 0) {
                    result.error("BAD_ARGS", "fps required (>0)", null)
                    return
                }
                controller.setCaptureFrameRate(fps)
                result.success(null)
            }

            "setVideoStabilization" -> {
                val enabled = call.argument<Boolean>("enabled") ?: false
                controller.setVideoStabilization(enabled)
                result.success(null)
            }

            "setRenderResolution" -> {
                val width = call.argument<Int>("width") ?: 0
                val height = call.argument<Int>("height") ?: 0
                if (width <= 0 || height <= 0) {
                    result.error("BAD_ARGS", "width and height required (>0)", null)
                    return
                }
                controller.setRenderResolution(
                    width,
                    height,
                    applyLive = call.argument<Boolean>("applyLive") ?: false,
                )
                result.success(null)
            }

            "checkGpuAcceleration" -> {
                result.success(controller.checkGpuAcceleration(context))
            }

            "getGroupLenses" -> {
                val groupId = call.argument<String>("groupId")
                controller.getGroupLenses(groupId) { lenses ->
                    mainHandler.post { result.success(lenses) }
                }
            }

            // ── Pipeline A: independent local preview texture ─────────────────
            "startPreviewTexture" -> {
                // Returns the Flutter external-texture id (or null if unavailable).
                result.success(controller.startPreviewTexture())
            }

            "stopPreviewTexture" -> {
                controller.stopPreviewTexture()
                result.success(null)
            }

            "getPocMetrics" -> {
                result.success(controller.pocMetrics())
            }

            "dispose" -> {
                // Stop routing new camera tracks through Camera Kit before tearing
                // down, so a subsequent plain createCameraTrack uses the OS camera.
                GetUserMediaImpl.setExternalVideoCapturerFactory(null)
                controller.dispose()
                result.success(null)
            }

            else -> result.notImplemented()
        }
    }

    override fun onDetachedFromEngine(@NonNull binding: FlutterPlugin.FlutterPluginBinding) {
        GetUserMediaImpl.setExternalVideoCapturerFactory(null)
        controller.hintSink = null
        controller.dispose()
        channel.setMethodCallHandler(null)
    }

    // ActivityAware — the preview container is built from the Activity context so
    // it themes/attaches correctly when embedded by the PlatformView.
    override fun onAttachedToActivity(binding: ActivityPluginBinding) {
        controller.setActivityContext(binding.activity)
    }

    override fun onReattachedToActivityForConfigChanges(binding: ActivityPluginBinding) {
        controller.setActivityContext(binding.activity)
    }

    override fun onDetachedFromActivityForConfigChanges() {
        controller.setActivityContext(null)
    }

    override fun onDetachedFromActivity() {
        controller.setActivityContext(null)
    }

    private companion object {
        const val TAG = "SnapCameraKitPlugin"
    }
}

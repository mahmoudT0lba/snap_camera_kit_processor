package net.vibezo.snap_camera_kit_processor

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer

/**
 * Custom WebRTC [VideoCapturer] that publishes **Camera Kit's lens-processed
 * output** into LiveKit — the Android half of the official Snap pipeline.
 *
 * This follows the **exact same structure** as WebRTC's own `Camera2Capturer`:
 *  1. WebRTC hands us a [SurfaceTextureHelper] in [initialize].
 *  2. On [startCapture] we make a [Surface] from that helper's `SurfaceTexture`
 *     and tell Camera Kit to render its lens output into it
 *     ([SnapCameraKitController.attachOutputSurface]).
 *  3. The helper picks up each rendered frame as an OES texture and we push it
 *     **straight** to `CapturerObserver.onFrameCaptured` — no blit, no copy, no
 *     pool. The helper holds a single texture and will not produce the next
 *     frame until the encoder releases the current one, so frame pacing /
 *     back-pressure is handled by WebRTC itself.
 *
 * The previous implementation did an OES→2D blit into a bounded texture pool;
 * when the encoder held frames the pool ran dry and dropped frames, which is
 * what produced the "zipping"/laggy motion. The official direct-push path
 * removes all of that.
 */
class SnapCameraVideoCapturer(private val controller: SnapCameraKitController) : VideoCapturer {

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var surface: Surface? = null

    /**
     * Whether a capture session (listener + output surface + camera) is live.
     *
     * WebRTC's [SurfaceTextureHelper] permits only ONE listener at a time and
     * throws `IllegalStateException("listener has already been set")` if
     * [SurfaceTextureHelper.startListening] is called twice without an
     * intervening [SurfaceTextureHelper.stopListening]. flutter_webrtc's
     * `FlutterWebRTCPlugin.LifeCycleObserver.onResume()` calls
     * `GetUserMediaImpl.reStartCamera()` → [startCapture] when the app returns
     * to the foreground, with NO paired [stopCapture] on background — so
     * [startCapture] must be re-entrant. This flag drives that guard.
     */
    private var isCapturing = false

    /**
     * The render dimensions currently applied to the live capture (the size of
     * the encoder output surface). Lets a re-entrant [startCapture] with
     * unchanged dims short-circuit to a NO-OP instead of tearing the surface
     * down and rebuilding it. Written only inside the @Synchronized capturer
     * methods alongside [isCapturing], so plain Ints are race-free here.
     */
    private var appliedRenderWidth = 0
    private var appliedRenderHeight = 0

    /**
     * Monotonic sequence for the [LIVE_TRACE] lifecycle log. Every lifecycle
     * callback on this capturer runs on WebRTC's single camera/signaling thread
     * (frame delivery on the helper thread is intentionally NOT traced), so a
     * plain Int is race-free here.
     */
    private var traceSeq = 0

    /**
     * Structured lifecycle log greppable alongside the Dart `[LIVE_TRACE]`
     * lines. This is the native detach/reload boundary the filter-drop bug is
     * decided at: a `releaseCapture`/`stopCapture` here is the exact moment
     * `detachOutputSurface()` cuts Camera Kit's output. Correlate the timestamp
     * with the Dart side — a teardown here with NO preceding Dart
     * `recreateLocalVideoTrack`/`enableCamera` log means the SDK (dynacast
     * pause on an unsubscribed track) drove it, not app code.
     */
    private fun trace(event: String, fields: String = "") {
        Log.d(TAG, "[LIVE_TRACE][SNAP_CAPTURER] $event { seq=${traceSeq++} isCapturing=$isCapturing$fields }")
    }

    override fun initialize(
        surfaceTextureHelper: SurfaceTextureHelper?,
        applicationContext: Context?,
        capturerObserver: CapturerObserver?,
    ) {
        this.surfaceTextureHelper = surfaceTextureHelper
        this.capturerObserver = capturerObserver
        trace("initialize", " helper=${surfaceTextureHelper != null} observer=${capturerObserver != null}")
    }

    /**
     * The per-frame delivery hook, extracted to a field so BOTH [startCapture]
     * and [applyRenderResolution] arm the exact same listener — a live resize
     * must not fork the delivery path.
     */
    private val frameListener = org.webrtc.VideoSink { frame ->
        // ARRIVAL CLOCK — read at the ABSOLUTE ENTRY of the listener, before
        // the encoder push, so the wall-clock interval series measures when
        // the frame ARRIVED and not arrival + encoder-submit latency. This
        // is a `clock_gettime(CLOCK_BOOTTIME)` vDSO read into a primitive
        // local: no allocation, no object, nothing that can meaningfully sit
        // in front of frame delivery. It is monotonic and, unlike
        // `nanoTime()`, keeps counting across device sleep — so a
        // backgrounded broadcast cannot fabricate a clean grid.
        val arrivalNs = SystemClock.elapsedRealtimeNanos()
        capturerObserver?.onFrameCaptured(frame)
        // DELIVERY-BLOCKED CLOCK. `onFrameCaptured` is SYNCHRONOUS: it runs the
        // whole `SimulcastEncoderAdapter` fan-out on THIS thread, and each rung's
        // `StreamEncoderWrapper.encode()` either blocks on `executor.submit().get()`
        // (stock path) or dispatches (parallel path). So the time between these
        // two reads is exactly "how long the capture thread was held downstream",
        // which is the quantity that decides whether the next camera frame can be
        // produced at all — the single-texture SurfaceTextureHelper will not
        // deliver another frame while this one is held.
        //
        // Nothing new sits in FRONT of frame delivery: `arrivalNs` was already
        // being read before the push, so this adds ONE `clock_gettime` vDSO read
        // after it, into a primitive local. No allocation, no branch on the hot
        // path, no logging. It is the measurement the micro-jitter investigation
        // never had — everything else observes the frames that SURVIVED.
        val deliveredNs = SystemClock.elapsedRealtimeNanos()
        // STAGE 0 instrumentation. This is the ONLY per-frame CPU hook on the
        // Android path (Camera Kit renders GPU-direct into both output
        // Surfaces), so the capture-cadence spread and the delivered frame
        // geometry are sampled here. Allocation-free, a handful of arithmetic
        // ops, and deliberately AFTER the encoder push so it can never sit in
        // front of frame delivery — which is exactly why `arrivalNs` had to
        // be captured above rather than read in here.
        controller.noteProcessedFrame(
            frame.buffer.width,
            frame.buffer.height,
            frame.rotation,
            frame.timestampNs,
            arrivalNs,
            deliveredNs - arrivalNs,
        )
    }

    @Synchronized
    override fun startCapture(width: Int, height: Int, framerate: Int) {
        trace("startCapture.enter", " req=${width}x$height@$framerate")
        val helper = surfaceTextureHelper
        val observer = capturerObserver
        if (helper == null || observer == null) {
            Log.e(TAG, "startCapture called before initialize()")
            trace("startCapture.abort_no_init")
            return
        }

        // Size the surface Camera Kit renders into. Use the controller's render
        // dims (driven by quality preset) so the encoder gets the intended size.
        val (rw, rh) = controller.renderDims()

        // Re-entrancy handling. The ONLY caller that re-enters while a session is
        // live is flutter_webrtc's LifeCycleObserver.onResume() → reStartCamera()
        // (no paired stopCapture on background). The listener is already armed,
        // the output already attached, and the camera is owned by CameraKitEngine
        // — so when the render dims are unchanged there is genuinely nothing to
        // restart: NO-OP. The previous "restart cleanly" here did
        // releaseCapture() → new Surface over the SAME SurfaceTexture →
        // startListening while the old output's close was still queued on
        // snapThread — the exact surface-abandon race that made the next
        // updateTexImage() throw. (releaseCapture existed only to dodge
        // startListening's "listener has already been set" throw; a no-op dodges
        // it with zero teardown.)
        if (isCapturing) {
            if (rw == appliedRenderWidth && rh == appliedRenderHeight) {
                trace("startCapture.reentrant_noop", " render=${rw}x$rh")
                return
            }
            // Genuine render-dimension change: route through the proven in-place
            // reattach (stopListening → setTextureSize → close old output →
            // connect new surface → release old AFTER close → startListening).
            // Never the old release-then-async-close path.
            trace("startCapture.reentrant_resize", " from=${appliedRenderWidth}x$appliedRenderHeight to=${rw}x$rh")
            applyRenderResolution(rw, rh)
            return
        }

        helper.setTextureSize(rw, rh)

        val surface = Surface(helper.surfaceTexture)
        this.surface = surface

        // A capture (re)start begins a NEW interval series. Without this the
        // first frame is differenced against the last frame of the PREVIOUS
        // session, and that one gap-sized sample swamps the window's mean and
        // deviation — see SnapCameraKitController.resetCadenceSeries().
        controller.resetCadenceSeries()

        // Direct push — like Camera2Capturer. The helper's single-texture
        // back-pressure paces frames; the encoder retains/releases as needed.
        helper.startListening(frameListener)

        // ATTACH the encoder output only — the capturer is a CONSUMER, it does
        // NOT own the camera. CameraKitEngine.start() powers the camera producer
        // independently (at session init), so the camera runs even when nothing
        // is publishing, survives publish stop / encoder recreate, etc. Starting
        // the camera here (the old `controller.startCamera()`) is exactly the
        // ownership bug M1 removes.
        controller.attachOutputSurface(surface)
        controller.registerActiveCapturer(this)
        appliedRenderWidth = rw
        appliedRenderHeight = rh
        isCapturing = true
        observer.onCapturerStarted(true)
        Log.d(TAG, "startCapture: encoder output attached (${rw}x$rh) — camera owned by engine")
        trace("startCapture.started", " render=${rw}x$rh")
    }

    @Synchronized
    override fun stopCapture() {
        trace("stopCapture.enter")
        if (!isCapturing) {
            // Defensive: a redundant stop (double dispose, mute on an already
            // stopped track) must still guarantee no stale listener survives to
            // break the next startListening().
            trace("stopCapture.redundant")
            surfaceTextureHelper?.stopListening()
            return
        }
        releaseCapture()
        capturerObserver?.onCapturerStopped()
    }

    /**
     * Tears the live capture down — stops the camera, disconnects Camera Kit's
     * output, removes the [SurfaceTextureHelper] listener and releases the
     * [Surface] — WITHOUT emitting onCapturerStarted/Stopped. Idempotent.
     *
     * [SurfaceTextureHelper.stopListening] is the load-bearing call: it removes
     * the listener (so the next [startCapture] can attach a fresh one) and
     * blocks until any in-flight frame delivery on the helper's handler thread
     * drains. It must NOT run on that handler thread — startCapture/stopCapture
     * are invoked on WebRTC's camera/signaling thread, so this is safe.
     */
    private fun releaseCapture() {
        // DETACH the encoder output only — never stop the camera. The camera
        // producer is owned by CameraKitEngine and must keep running when the
        // publisher tears its capture down (publish stop, encoder recreate,
        // reconnect). Stopping the camera here was the ownership bug M1 removes.
        trace("releaseCapture", " detach=$isCapturing")
        if (isCapturing) {
            // Hand encoder-surface ownership to the controller: it closes Camera
            // Kit's output and releases the Surface AFTER that close, on
            // snapThread — never here, synchronously ahead of the posted close
            // (that inversion was the updateTexImage crash).
            controller.detachOutputSurface()
        }
        surfaceTextureHelper?.stopListening()
        // The controller owns the encoder Surface's release now (see
        // detachOutputSurface / teardownNativeResources). Drop our non-owning
        // reference WITHOUT releasing it — releasing here is the ordering bug,
        // and would also double-free against the controller's release.
        surface = null
        isCapturing = false
        appliedRenderWidth = 0
        appliedRenderHeight = 0
        controller.unregisterActiveCapturer(this)
    }

    /**
     * LIVE render-resolution apply — the Android half of the thermal/adaptive
     * RESOLUTION lever, previously inert on the Snap path (`setRenderResolution`
     * only recorded the value and nothing ever recreated the track, so a hot
     * device kept paying full-resolution lens-render + encode work while the
     * ladder believed it had shed — the open defect from the 2026-08-17/18
     * micro-jitter forensics §6.1/§7.2).
     *
     * In-place: no track recreate, no camera cycle, no renegotiation — so the
     * streamer's preview never freezes (the constraint that forbade the
     * recreate path). Sequence:
     *  1. [SurfaceTextureHelper.stopListening] — blocks until any in-flight
     *     frame drains, so no frame can straddle the surface swap.
     *  2. `setTextureSize` — the helper's SurfaceTexture now vends buffers of
     *     the new size.
     *  3. New [Surface] over the same SurfaceTexture; the OLD surface is handed
     *     to [SnapCameraKitController.reattachOutputSurface], which closes the
     *     old Camera Kit output THEN connects the new one on its single
     *     snapThread (one queue = ordered) and releases the old Surface only
     *     after the close — Camera Kit can never render into a released
     *     surface.
     *  4. Re-arm the SAME [frameListener]. WebRTC sees the size change on the
     *     next frame and re-runs `initEncode` on every rung; the Dart side
     *     re-asserts bitrates right after (`_applyPublishEncoding`).
     *
     * Cost: a handful of dropped frames (~100 ms) once per thermal/adaptive
     * step — comparable to the iOS preset-change glitch, and incomparably
     * cheaper than the frozen-preview track recreate this replaces.
     *
     * Returns false when there is no live capture — the recorded dims then
     * apply at the next [startCapture] exactly as before.
     */
    @Synchronized
    fun applyRenderResolution(width: Int, height: Int): Boolean {
        val helper = surfaceTextureHelper ?: return false
        if (!isCapturing) return false
        trace("applyRenderResolution.enter", " to=${width}x$height")
        helper.stopListening()
        helper.setTextureSize(width, height)
        val old = surface
        val next = Surface(helper.surfaceTexture)
        surface = next
        controller.reattachOutputSurface(next, old)
        appliedRenderWidth = width
        appliedRenderHeight = height
        // New size ⇒ new interval series (same rationale as startCapture: the
        // swap gap must not pollute the cadence window).
        controller.resetCadenceSeries()
        helper.startListening(frameListener)
        trace("applyRenderResolution.done", " render=${width}x$height")
        return true
    }

    override fun changeCaptureFormat(width: Int, height: Int, framerate: Int) {
        // Render resolution is driven via the MethodChannel (setRenderResolution
        // → applyRenderResolution above); WebRTC's own callers of this hook are
        // not on the Snap path's control plane.
        trace("changeCaptureFormat", " req=${width}x$height@$framerate")
    }

    override fun dispose() {
        trace("dispose")
        releaseCapture()
        surfaceTextureHelper = null
        capturerObserver = null
    }

    override fun isScreencast(): Boolean = false

    private companion object {
        const val TAG = "SnapCameraVideoCapturer"
    }
}

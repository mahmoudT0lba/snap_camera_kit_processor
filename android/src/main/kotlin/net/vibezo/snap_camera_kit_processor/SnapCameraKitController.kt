package net.vibezo.snap_camera_kit_processor

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewStub
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.ProcessLifecycleOwner
import io.flutter.view.TextureRegistry
import com.snap.camerakit.ImageProcessor
import com.snap.camerakit.Session
import com.snap.camerakit.lenses.LensesComponent
import com.snap.camerakit.lenses.whenHasFirst
import com.snap.camerakit.newBuilder
import com.snap.camerakit.outputFrom
import com.snap.camerakit.support.camera.AllowsCameraPreview
import com.snap.camerakit.support.camera.AspectRatio
import com.snap.camerakit.support.camera.Crop
import com.snap.camerakit.support.camerax.CameraXImageProcessorSource
import com.snap.camerakit.supported
import io.flutter.embedding.engine.plugins.FlutterPlugin
import java.io.Closeable
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Wraps the Snap Camera Kit Android SDK using the **official, supported**
 * pattern: Camera Kit OWNS the camera (via `CameraXImageProcessorSource`) and
 * renders lenses in its own GPU pipeline — exactly how the Snapchat app runs.
 * Its lens-processed output is pushed straight to LiveKit's encoder (via
 * [SnapCameraVideoCapturer] → `CapturerObserver.onFrameCaptured`).
 *
 * This replaces the previous "headless" approach (LiveKit owned the camera; we
 * rendered each WebRTC frame into a Snap input SurfaceTexture and pulled the
 * latest processed frame back). That double round-trip + the async
 * "latest frame" pull put the lens behind the motion ("high ping" lag). The
 * official pipeline is single-direction and frame-synchronous, which removes
 * the lag.
 *
 * ## Frame flow (official)
 * ```
 *   CameraXImageProcessorSource (Camera Kit owns the camera)
 *        ▼   lens applied in Camera Kit's GPU pipeline
 *   session.processor.connectOutput(surface, Purpose.RECORDING)
 *        ▼   Camera Kit renders directly into the WebRTC SurfaceTextureHelper's
 *            Surface (un-mirrored RECORDING output — the real camera, not selfie)
 *   SurfaceTextureHelper → CapturerObserver.onFrameCaptured  →  LiveKit encoder
 * ```
 * The output [Surface] is owned by [SnapCameraVideoCapturer] (made from the
 * helper WebRTC hands it). This controller only connects/disconnects Camera
 * Kit's output to that surface and drives the camera/lens/controls. There is
 * **no intermediate blit or texture pool** — that was the laggy part.
 *
 * ## Threading
 * Snap `Session` API calls are marshalled onto the dedicated [snapThread].
 * CameraX operations (construct source, startPreview/zoom/flash) must run on
 * the MAIN thread, so those are posted to [mainHandler].
 *
 * ## On-device verification needed (TODO device)
 * The CameraX source threading (build on snapThread, startPreview on main),
 * front/back mirroring, and torch/zoom semantics are hardware-dependent and
 * must be confirmed on a real device.
 */
class SnapCameraKitController {

    private var initialized = false
    private var apiToken: String? = null
    private var defaultGroupId: String? = null
    /** Every session group to warm at init (default + admin categories). */
    private var prefetchGroupIds: List<String> = emptyList()
    private var loadedLensId: String? = null
    private var loadedGroupId: String? = null

    /**
     * Lens requested while the session was still initializing (or being
     * rebuilt after a live recovery). Applied automatically the moment
     * [initialize] completes instead of being dropped — dropping meant the
     * user's tap silently did nothing ("filter won't apply" report).
     * Volatile: written from the platform channel thread, read on the Snap
     * thread after init.
     */
    @Volatile private var pendingLensId: String? = null
    @Volatile private var pendingGroupId: String? = null

    /**
     * Monotonic epoch for lens selection. Bumped by EVERY [applyLens] and
     * [clearLens]; each async continuation captures the value it was issued
     * under and no-ops if the epoch has moved on.
     *
     * Load-bearing: `repository.get { … }` → `processor.apply { … }` are two
     * chained async hops whose completion order is NOT the call order. A slow
     * (uncached, network-fetched) lens A tapped before a fast cached lens B
     * would land AFTER B and replace the user's newer selection — the filter
     * visibly flips back on its own. The `loadedLensId` bookkeeping inherited
     * the same inversion, so the state field ended up naming the stale lens.
     *
     * An epoch (not an id comparison) is required: re-tapping the same lens is
     * a legitimate distinct request, and identity checks cannot order two
     * in-flight applies of the SAME lens.
     *
     * `AtomicInteger` because the bump happens on the platform-channel thread
     * while the comparisons run on the Snap thread and on Camera Kit's own
     * callback threads.
     */
    private val lensGeneration = AtomicInteger(0)
    private var pluginBinding: FlutterPlugin.FlutterPluginBinding? = null

    /** Dedicated thread for ALL Snap Session API calls (single-thread req). */
    private var snapThread: HandlerThread? = null
    private var snapHandler: Handler? = null

    /** CameraX + lens controls must run on the main thread. */
    private val mainHandler = Handler(Looper.getMainLooper())

    private var session: Session? = null
    private var cameraSource: CameraXImageProcessorSource? = null
    private var connectOutputCloseable: Closeable? = null

    /**
     * The Surface currently connected as Camera Kit's encoder RECORDING output.
     * The controller — not [SnapCameraVideoCapturer] — owns its RELEASE, and only
     * ever releases it AFTER the output using it is closed, in [attachOutputSurface]
     * (defensive), [reattachOutputSurface], [detachOutputSurface] and
     * [teardownNativeResources], always on [snapThread]. That is what guarantees
     * Camera Kit can never render into a released surface — including when
     * [dispose] has already torn [snapThread] down before the capturer's
     * releaseCapture() runs (the dead-handler case): [teardownNativeResources],
     * already queued by dispose(), performs the release after its own output
     * close. Null-after-release makes a repeated detach/teardown a harmless no-op
     * (never a double free). All access is on [snapThread].
     */
    @Volatile private var encoderOutputSurface: Surface? = null

    /**
     * The capturer currently feeding the encoder branch, registered by
     * [SnapCameraVideoCapturer.startCapture] and cleared by its releaseCapture.
     * Written on WebRTC's camera thread, read on the platform thread by
     * [setRenderResolution] — hence @Volatile. Identity-checked on unregister
     * so a stale capturer's teardown can never clear a newer registration.
     */
    @Volatile private var activeCapturer: SnapCameraVideoCapturer? = null

    fun registerActiveCapturer(capturer: SnapCameraVideoCapturer) {
        activeCapturer = capturer
    }

    fun unregisterActiveCapturer(capturer: SnapCameraVideoCapturer) {
        if (activeCapturer === capturer) activeCapturer = null
    }

    /**
     * Monotonic session epoch. Bumped on every [initialize] that proceeds to
     * (re)build a session. A deferred [dispose] teardown captures the epoch it
     * was issued for and SKIPS if a newer [initialize] has since bumped it.
     *
     * Without this, the async teardown posted to the PREVIOUS snap thread (and
     * the `stopPreview()` it posts to the main thread) can run AFTER a fresh
     * [initialize] has already built a new session + restarted the camera — and
     * because `session`/`cameraSource` are shared fields read at execution time,
     * it would `session.close()` the LIVE session and `stopPreview()` the LIVE
     * camera. That leaves `initialized=true` with `session=null` and an unbound
     * camera: the "black preview / camera won't init after several go-live
     * cycles" leak. The epoch makes a stale teardown a no-op.
     */
    @Volatile private var sessionGeneration = 0

    /** Held while the torch is ON (closing it turns the torch off). */
    private var torchCloseable: Closeable? = null

    /** CameraX analysis/IO executor. */
    private val cameraExecutor = Executors.newSingleThreadExecutor()

    /** Activity context (via ActivityAware) used to build the preview view. */
    @Volatile private var activityContext: Context? = null

    /**
     * Camera Kit's on-screen preview. On Android lenses only receive touch when
     * Camera Kit renders an on-screen preview into a [ViewStub] given to
     * `Session.Builder.attachTo`. The controller owns a container [FrameLayout]
     * holding a [ViewStub]; the PlatformView embeds it as the streamer's preview,
     * Camera Kit inflates the stub with its preview (camera + lens) and captures
     * touch. The encoder is fed by a SEPARATE [attachOutputSurface] RECORDING
     * output, so the broadcast pipeline is unchanged.
     */
    @Volatile private var previewContainer: FrameLayout? = null
    @Volatile private var previewStub: ViewStub? = null
    @Volatile private var stubAttached: Boolean = false

    /**
     * True when the session was built WITH `attachTo`. Camera Kit then OWNS the
     * camera start itself — so [startCamera]/[stopCamera] must NOT also call
     * startPreview/stopPreview, or CameraX binds a 2nd PREVIEW+IMAGE_CAPTURE set
     * and crashes with "too many use cases". (That double-start was the launch
     * crash.)
     */
    @Volatile private var attachActive: Boolean = false

    /**
     * Where lens hint text goes (set by the plugin → MethodChannel → Flutter
     * [SnapLensHintOverlay]). `null` text means "hide all hints". We render hints
     * in our own UI instead of Camera Kit's view for a consistent look.
     */
    @Volatile var hintSink: ((String?) -> Unit)? = null

    @Volatile private var renderWidth: Int = DEFAULT_RENDER_W
    @Volatile private var renderHeight: Int = DEFAULT_RENDER_H
    @Volatile private var facingFront: Boolean = true

    /**
     * Capture frame-rate cap in fps, pushed down from Dart's thermal ladder.
     * 30 = full rate (no cap). Re-applied after every camera start and camera
     * flip, so a stop→start cycle or a switchCamera cannot silently restore the
     * device's native cadence. See [applyCameraTuning].
     */
    @Volatile private var captureFrameRate: Int = 30

    /**
     * Whether the OS may run electronic / optical image stabilization on the
     * frames Camera Kit sees. Pushed down from Dart; see [applyCameraTuning].
     *
     * Default OFF. EIS works by cropping a larger sensor area and shifting the
     * crop window per frame from gyro + image analysis; OIS physically moves a
     * lens element. Either way the scene translates and micro-warps in a way
     * that correlates with hand motion — and Camera Kit's face tracker then has
     * to chase that motion on top of the real head motion. On a warping lens
     * the graphics stay glued to the landmarks while the silhouette and the
     * background wobble around them, which is exactly the reported "the filter
     * is fine but the body trembles".
     *
     * The app has intended this to be off since the DeepAR era, but the switch
     * was wired to the WebRTC fork's `CameraUtils.setFocusMode`, which only acts
     * on `Camera2Capturer`/`Camera1Capturer` and silently no-ops for
     * `SnapCameraVideoCapturer` — so on the Camera Kit path nothing was ever
     * applied and each OEM's HAL default won. This is that switch, moved to
     * where the camera actually lives (finding CAM-01).
     */
    @Volatile private var stabilizationEnabled: Boolean = false

    /**
     * Exposure-compensation bias in EV applied on top of the camera's own auto
     * metering. [DEFAULT_EXPOSURE_BIAS_EV] is what every stream shipped with
     * before the control existed; the streamer can now move it from the live
     * settings sheet and the app persists their choice.
     *
     * Stored (not applied once) because EVERY rebind resets it: a camera flip,
     * a preview restart and a session rebuild all hand back a fresh CameraX
     * `Camera` at the device default. [applyExposureBias] is the single writer
     * and reads this field, so all three re-apply points converge on the
     * streamer's value instead of the shipped constant.
     *
     * Written on the platform-channel thread, read on the main thread — hence
     * @Volatile.
     */
    @Volatile private var exposureBiasEv: Float = DEFAULT_EXPOSURE_BIAS_EV

    /**
     * Token for the pending [scheduleCameraTuning] poll chain. Bumped on every
     * schedule so a rapid start→flip converges on one chain instead of racing
     * two. Main-thread only — every reader and writer runs on [mainHandler].
     */
    private var cameraTuningGeneration = 0

    // ── Pipeline A: independent local preview (Branch A) ─────────────────────
    //
    // A SECOND Camera Kit output rendered straight into a Flutter external
    // texture. Camera Kit's GPU pipeline fans its single lens-processed frame to
    // BOTH the encoder surface (Branch B, `connectOutputCloseable`, RECORDING)
    // and this preview surface — so the host's on-screen preview has ZERO WebRTC
    // coupling: no track, source, sink-wants, encoder, simulcast or network edge.
    // A stalled/degrading encoder cannot touch it; only the camera/GPU/thermal
    // can. Uses `Purpose.RECORDING` (the same proven un-mirrored output the
    // encoder uses) so the render path is identical to Branch B; the selfie
    // mirror is a display-only transform applied in Dart, exactly like iOS.
    //
    // All INERT until [startPreviewTexture] registers a texture, so production
    // behaviour is byte-identical until the independent preview is switched on.
    @Volatile private var previewTextureEntry: TextureRegistry.SurfaceTextureEntry? = null
    @Volatile private var previewSurface: Surface? = null
    /** Camera Kit's preview-output connection; closing it stops the fan-out.
     *  Written on [snapThread], also read from [pocMetrics] on the platform
     *  thread — hence @Volatile. */
    @Volatile private var previewOutputCloseable: Closeable? = null

    // ── Preview-branch liveness sampling (Branch A freeze detector) ──────────
    //
    // Android's Camera Kit output is GPU-DIRECT into the Surface: there is NO
    // per-frame CPU callback here (unlike iOS's PixelBufferOutput closure, which
    // increments a push counter per frame). And the preview Surface wraps the
    // FLUTTER-owned SurfaceTexture, whose single OnFrameAvailableListener slot is
    // held by the engine to drive `markTextureFrameAvailable` — hijacking it to
    // count frames would break the very texture we render. So a true per-frame
    // `previewPushCount` is not cheaply available on Android.
    //
    // Instead we SAMPLE `SurfaceTexture.getTimestamp()` (the producer timestamp
    // of the last frame the compositor latched via updateTexImage) at each
    // ~1 Hz `pocMetrics()` poll — a plain field read, zero hot-path cost, no
    // listener conflict. A healthy preview advances this timestamp every frame;
    // a frozen preview stops advancing it, so `previewLastDeltaMs` grows
    // unbounded and `previewAdvanceRatio` collapses toward 0. This measures
    // frames DELIVERED-AND-LATCHED to the preview (what the user actually sees),
    // which is exactly the Branch-A freeze signal the diagnostic needs.
    @Volatile private var lastPreviewFrameTsNs: Long = 0L // getTimestamp() of the last DISTINCT preview frame seen
    private var previewTsLastChangedWallMs: Long = 0L // elapsedRealtime when it last changed
    private var pocLastPreviewTsNs: Long = 0L // getTimestamp() at the previous poll (advance-ratio window)
    private var pocLastSampleWallMs: Long = 0L // elapsedRealtime at the previous poll

    // ── STAGE 0 — capture-cadence spread (CAM-02 hypothesis test) ────────────
    //
    // A stable MEAN fps hides an unpinned auto-exposure frame-rate range: the
    // camera is free to run e.g. a 15–30 fps AE range indoors, which delivers a
    // healthy-looking average with a wide per-frame interval spread — and that
    // spread is what reads as jitter. So we report min/max/mean/stddev of the
    // processed-frame INTERVAL over each ~1 Hz poll window, not just the mean.
    //
    // The samples come from [noteProcessedFrame], called by
    // [SnapCameraVideoCapturer]'s frame listener — the only per-frame CPU hook
    // that exists on the Android path (Camera Kit renders GPU-direct into both
    // output Surfaces; see the preview-sampling note above). Cost per frame is a
    // handful of arithmetic ops in an uncontended `synchronized` block and NO
    // allocation. Intervals are computed from the frame's OWN timestamp (the
    // camera producer clock), not from a wall-clock read, so the numbers measure
    // capture cadence rather than delivery-thread scheduling.
    //
    // CAVEAT for the baseline: this hook only fires while a camera track is
    // published. With no publisher (e.g. a go-live screen that has not created a
    // track yet) the cadence + processed-frame-geometry fields read 0.
    private val cadenceLock = Any()

    // ── DELIVERY-BLOCKED accumulator (v2 diagnostics) ────────────────────────
    //
    // Sum/count/max of the time each frame spent inside
    // `CapturerObserver.onFrameCaptured` — i.e. how long the capture thread was
    // HELD by the encoder fan-out. Three primitive longs under the existing
    // cadence monitor: no allocation, no extra lock, and nothing in front of
    // frame delivery (the caller brackets a push it was already timing).
    //
    // WHY IT MATTERS. Every other number in this file describes the frames that
    // SURVIVED. This one describes the pressure that decides how many will: the
    // SurfaceTextureHelper holds ONE texture, so while a frame is held here the
    // camera cannot deliver the next one. A mean approaching the frame budget
    // (33 ms at 30 fps, 41.7 at 24) means the delivery rate is being set by the
    // encoder, not by the sensor — which no WebRTC stat can tell you, because
    // the frames die upstream of the encoder's own quality limiter.
    private var blkSumNs = 0L
    private var blkCount = 0L
    private var blkMaxNs = 0L

    private var cadWindowFrames = 0L // frames since the last pocMetrics() drain
    private var cadTotalFrames = 0L // frames since the first sample (session)
    private var cadFirstWallMs = 0L
    private var cadWindowStartWallMs = 0L

    /**
     * PRODUCER interval series — fed the frame's own `timestampNs`. On Android
     * that is `SurfaceTexture.getTimestamp()`, i.e. the time Camera Kit's GPU
     * render actually PRESENTED the buffer, and it is what WebRTC turns into the
     * RTP timestamp. So this series is the spacing every viewer's jitter buffer
     * will faithfully reproduce.
     */
    private val cadProducer = IntervalAccumulator(CADENCE_STALL_MS)

    /**
     * WALL interval series — fed `SystemClock.elapsedRealtimeNanos()` read at
     * the ABSOLUTE ENTRY of the frame listener, before the encoder push, so it
     * measures frame ARRIVAL and not arrival-plus-encoder-submit.
     *
     * This is the discriminator the whole micro-jitter investigation is blocked
     * on (`docs/live-video-microjitter-forensics-2026-08-17.md` §8):
     *
     *   wallIvlSd ~= ivlSd   -> frames genuinely arrive scattered. The render
     *                           loop / encoder back-pressure is real; the fix is
     *                           contention or pacing.
     *   wallIvlSd ~= 1 ms    -> frames arrive on a clean grid but carry
     *                           scattered timestamps. The media timeline is
     *                           being corrupted; the fix is re-stamping.
     *   ivlSd ~= 1 ms        -> delivery-thread scheduling only; the published
     *                           timeline is fine.
     *
     * No stall threshold: a stall is already counted once, on the producer
     * series, and counting it twice would imply two distinct events.
     */
    private val cadWall = IntervalAccumulator(0.0)

    /** Geometry of the last frame Camera Kit actually delivered to the encoder. */
    @Volatile private var lastFrameWidth = 0
    @Volatile private var lastFrameHeight = 0
    @Volatile private var lastFrameRotation = 0

    /** Size the independent-preview Surface was created at (CAM-05 divergence). */
    @Volatile private var previewSurfaceWidth = 0
    @Volatile private var previewSurfaceHeight = 0

    /** CameraCharacteristics-derived readings, cached per camera id (immutable). */
    private var cachedCameraStaticsId: String? = null
    private var cachedCameraStatics: Map<String, Any>? = null

    /**
     * Whether the LAST [cameraCharacteristic] resolution had to fall back to
     * facing because camera2-interop could not name the bound camera. Null =
     * not yet resolved. Drives a one-shot warning per transition (not per call —
     * this is read several times inside every [applyCameraTuning]).
     */
    private var lastCharacteristicsIdWasFallback: Boolean? = null

    /**
     * Outcome of the most recent [applyCameraTuning] submission, surfaced in
     * [pocMetrics] as `camTuning`. One of `never` / `pending` / `applied` /
     * `rejected` / `deferred` / `unsupported`.
     *
     * Before this existed the capture-request submission was fire-and-forget:
     * `addCaptureRequestOptions` returns a `ListenableFuture` that was dropped
     * on the floor, so an OEM HAL rejecting the batch (which takes the AE fps
     * range, EIS, OIS and AF down together, and can drop the capture session
     * outright) was indistinguishable from success. The log said "applied" in
     * both cases.
     */
    @Volatile private var cameraTuningState: String = "never"

    /** Failure detail for [cameraTuningState] == `rejected`; empty otherwise. */
    @Volatile private var cameraTuningError: String = ""

    /**
     * Main-thread executor for the capture-request future callback. A single
     * shared instance — the future completes once per tuning apply (a thermal
     * step or a camera bind), never per frame.
     */
    private val mainExecutor = java.util.concurrent.Executor { mainHandler.post(it) }

    fun setPluginBinding(binding: FlutterPlugin.FlutterPluginBinding) {
        pluginBinding = binding
    }

    /** Provide an Activity context (via ActivityAware) for the preview view. */
    fun setActivityContext(context: Context?) {
        activityContext = context
    }

    /**
     * Get (or lazily create, on the main thread) the preview container — a
     * transparent [FrameLayout] holding the [ViewStub] Camera Kit inflates with
     * its on-screen preview (camera + lens) via `attachTo`. Returned to the
     * PlatformView factory for embedding; transparent until Camera Kit inflates,
     * so the LiveKit preview shows through underneath meanwhile.
     */
    @Synchronized
    fun getOrCreatePreviewView(context: Context): View {
        previewContainer?.let { return it }
        val stub = ViewStub(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        val container = object : FrameLayout(context) {
            override fun onAttachedToWindow() {
                super.onAttachedToWindow()
                stubAttached = true
                Log.d(TAG, "previewContainer attached to window (size=${width}x$height)")
            }

            override fun onDetachedFromWindow() {
                stubAttached = false
                super.onDetachedFromWindow()
            }
        }
        container.addView(stub)
        previewContainer = container
        previewStub = stub
        return container
    }

    /** Current render dimensions (driven by the quality preset). */
    fun renderDims(): Pair<Int, Int> = renderWidth to renderHeight

    /**
     * Connect Camera Kit's lens-processed output to the WebRTC capturer's
     * [surface]. Uses [ImageProcessor.Output.Purpose.RECORDING] — the canonical
     * un-mirrored output (what gets broadcast), NOT the mirrored selfie PREVIEW.
     * Called by [SnapCameraVideoCapturer.startCapture].
     */
    fun attachOutputSurface(surface: Surface) {
        runOnSnapThread {
            try {
                connectOutputCloseable?.close()
                // Defensive: any surface still tracked here had its output closed
                // just above, so releasing it now honours close-before-release.
                // In practice this is null on a first attach — the re-entrant
                // restart path uses reattach / no-op, never a second attach.
                encoderOutputSurface?.let { stale -> mainHandler.post { runCatching { stale.release() } } }
                // Register release ownership BEFORE connect so a connectOutput
                // that throws still leaves the surface owned (and thus released)
                // by detach/teardown rather than leaked.
                encoderOutputSurface = surface
                connectOutputCloseable = session?.processor?.connectOutput(
                    outputFrom(surface, ImageProcessor.Output.Purpose.RECORDING),
                )
                Log.d(TAG, "attachOutputSurface: connected (RECORDING, un-mirrored)")
            } catch (e: Throwable) {
                Log.w(TAG, "attachOutputSurface failed", e)
            }
        }
    }

    /**
     * Atomically swap the encoder output surface for a LIVE render-resolution
     * change ([SnapCameraVideoCapturer.applyRenderResolution]). Runs the close
     * and the connect on the single [snapThread] queue so they cannot
     * interleave with any other output operation, and releases [oldSurface]
     * only AFTER the old output is closed — Camera Kit can never render into a
     * released surface (same rule as [stopPreviewTexture]).
     */
    fun reattachOutputSurface(surface: Surface, oldSurface: Surface?) {
        runOnSnapThread {
            try {
                connectOutputCloseable?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "reattachOutputSurface: close failed", e)
            }
            connectOutputCloseable = try {
                session?.processor?.connectOutput(
                    outputFrom(surface, ImageProcessor.Output.Purpose.RECORDING),
                )
            } catch (e: Throwable) {
                Log.w(TAG, "reattachOutputSurface: connect failed", e)
                null
            }
            // [oldSurface] is the previous [encoderOutputSurface]; take over
            // release ownership of the new one and release the old AFTER the
            // close above.
            encoderOutputSurface = surface
            mainHandler.post {
                try { oldSurface?.release() } catch (_: Throwable) {}
            }
            Log.d(TAG, "reattachOutputSurface: swapped encoder output surface")
        }
    }

    /**
     * Disconnect AND release the encoder output surface (called by the capturer's
     * releaseCapture when it stops). Closes Camera Kit's output FIRST, then
     * releases the tracked [encoderOutputSurface] — both on the single [snapThread]
     * block, so Camera Kit can never render into a released surface. If [snapThread]
     * is already gone (dispose() ran first), this is a no-op and
     * [teardownNativeResources] releases the surface after its own close instead;
     * the capturer must NOT release the surface itself.
     */
    fun detachOutputSurface() {
        runOnSnapThread {
            try {
                connectOutputCloseable?.close()
            } catch (e: Throwable) {
                Log.w(TAG, "detachOutputSurface failed", e)
            }
            connectOutputCloseable = null
            val s = encoderOutputSurface
            encoderOutputSurface = null
            s?.let { mainHandler.post { try { it.release() } catch (_: Throwable) {} } }
        }
    }

    // ── Pipeline A: independent local preview (Branch A) ─────────────────────

    /**
     * Create + register the independent preview external texture and connect it
     * as a SECOND Camera Kit output (RECORDING), fully decoupled from the WebRTC
     * encoder. Returns the Flutter texture id to render via a `Texture(id)`
     * widget, or null if the texture registry is unavailable. Idempotent — a
     * second call returns the existing id.
     *
     * The texture entry is created synchronously on the calling (platform)
     * thread so the id can be returned immediately; the Camera Kit output is
     * connected on [snapThread], matching [attachOutputSurface].
     */
    @Synchronized
    fun startPreviewTexture(): Long? {
        previewTextureEntry?.let { return it.id() }
        val registry = pluginBinding?.textureRegistry ?: run {
            Log.w(TAG, "startPreviewTexture: no texture registry")
            return null
        }
        val entry = registry.createSurfaceTexture()
        val (rw, rh) = renderDims()
        val surfaceTexture = entry.surfaceTexture()
        surfaceTexture.setDefaultBufferSize(rw, rh)
        val surface = Surface(surfaceTexture)
        previewTextureEntry = entry
        previewSurface = surface
        // STAGE 0: record the size this output was created at so the metrics can
        // expose the CAM-05 divergence (preview surface sized here, encoder
        // surface sized in SnapCameraVideoCapturer.startCapture — a quality
        // change between those two moments leaves Camera Kit rendering the same
        // frame at two different sizes).
        previewSurfaceWidth = rw
        previewSurfaceHeight = rh
        runOnSnapThread {
            try {
                previewOutputCloseable?.close()
                previewOutputCloseable = session?.processor?.connectOutput(
                    outputFrom(surface, ImageProcessor.Output.Purpose.RECORDING),
                )
                Log.d(TAG, "startPreviewTexture: connected independent preview output (id=${entry.id()}, ${rw}x$rh)")
            } catch (e: Throwable) {
                Log.w(TAG, "startPreviewTexture: connectOutput failed", e)
            }
        }
        return entry.id()
    }

    /**
     * Disconnect + release the independent preview texture. Idempotent. Camera
     * Kit's output is closed on [snapThread] FIRST, then the surface/entry are
     * released on the main thread, so Camera Kit can never render into a released
     * surface.
     */
    @Synchronized
    fun stopPreviewTexture() {
        val entry = previewTextureEntry
        val surface = previewSurface
        previewTextureEntry = null
        previewSurface = null
        if (entry == null && surface == null) return
        runOnSnapThread {
            try { previewOutputCloseable?.close() } catch (_: Throwable) {}
            previewOutputCloseable = null
            mainHandler.post {
                try { surface?.release() } catch (_: Throwable) {}
                try { entry?.release() } catch (_: Throwable) {}
                Log.d(TAG, "stopPreviewTexture: released independent preview texture")
            }
        }
    }

    /**
     * STAGE 0 per-frame sample. Called from [SnapCameraVideoCapturer]'s frame
     * listener for every frame Camera Kit delivers to the encoder output — the
     * only per-frame CPU hook on the Android path. Records the delivered
     * geometry and accumulates the interval statistics drained by [pocMetrics].
     *
     * Deliberately allocation-free and lock-light: one uncontended monitor and a
     * handful of adds on primitive fields (see [IntervalAccumulator]'s
     * allocation contract). [timestampNs] is the frame's own producer timestamp;
     * [arrivalNs] is a monotonic wall-clock read taken by the CALLER at the
     * absolute entry of the frame listener, BEFORE the encoder push.
     *
     * Why [arrivalNs] is passed in rather than read here: this method is invoked
     * *after* `observer.onFrameCaptured(frame)` on purpose, so that
     * instrumentation can never sit in front of frame delivery. Reading the
     * clock here would therefore measure arrival + encoder-submit latency, which
     * is exactly the quantity the wall series exists to exclude. One primitive
     * `long` crosses the call; nothing is allocated on either side.
     */
    fun noteProcessedFrame(
        width: Int,
        height: Int,
        rotationDegrees: Int,
        timestampNs: Long,
        arrivalNs: Long,
        blockedNs: Long = 0L,
    ) {
        lastFrameWidth = width
        lastFrameHeight = height
        lastFrameRotation = rotationDegrees
        synchronized(cadenceLock) {
            val nowMs = SystemClock.elapsedRealtime()
            if (cadFirstWallMs == 0L) {
                cadFirstWallMs = nowMs
                cadWindowStartWallMs = nowMs
            }
            cadTotalFrames++
            cadWindowFrames++
            // WALL first, and unconditionally. The producer series legitimately
            // rejects a non-monotonic timestamp — and that rejection is itself a
            // symptom we need the wall series to have survived, so the two must
            // not share a guard.
            cadWall.accept(arrivalNs)
            cadProducer.accept(timestampNs)
            // Guard against a clock read that straddles a suspend: a negative or
            // absurd value is dropped rather than poisoning the window's mean.
            if (blockedNs in 0..MAX_BLOCKED_NS) {
                blkSumNs += blockedNs
                blkCount++
                if (blockedNs > blkMaxNs) blkMaxNs = blockedNs
            }
        }
    }

    /**
     * Drop both interval series' anchors because frame delivery is (re)starting.
     *
     * Called by [SnapCameraVideoCapturer.startCapture]. Without it the first
     * frame after a capture restart (background resume, dynacast resume, track
     * recreate) is differenced against the LAST frame of the previous session,
     * producing one interval the length of the entire gap. That single sample
     * dominates the window's mean and deviation, which would make `ivlSd` /
     * `wallIvlSd` unreadable in precisely the windows worth reading.
     */
    fun resetCadenceSeries() {
        synchronized(cadenceLock) {
            cadWall.resetSeries()
            cadProducer.resetSeries()
        }
    }

    /**
     * Drain the cadence window: per-window fps + interval min/max/mean/stddev,
     * plus a session-cumulative fps. Resets the window so each ~1 Hz poll
     * reports the LAST second, which is what makes a spread reading meaningful.
     */
    private fun drainCadenceMetrics(nowWallMs: Long): Map<String, Any> {
        var frames = 0L
        var samples = 0
        var meanMs = 0.0
        var minMs = 0.0
        var maxMs = 0.0
        var sdMs = 0.0
        var stalls = 0
        var wallSamples = 0
        var wallMeanMs = 0.0
        var wallMinMs = 0.0
        var wallMaxMs = 0.0
        var wallSdMs = 0.0
        var windowMs = 1.0
        var blockedMeanMs = 0.0
        var blockedMaxMs = 0.0
        var blockedSamples = 0L
        var totalFrames = 0L
        var sessionMs = 1.0
        var started = false
        // Read BOTH series and reset BOTH inside one monitor hold, so the pair
        // always describes the same window. Reading them under separate locks
        // would let a frame land between the two and make wallIvlSd and ivlSd
        // incomparable — which is the only thing they are for.
        synchronized(cadenceLock) {
            frames = cadWindowFrames
            samples = cadProducer.samples
            meanMs = cadProducer.meanMs
            minMs = cadProducer.minMs
            maxMs = cadProducer.maxMs
            sdMs = cadProducer.stdDevMs
            stalls = cadProducer.stalls
            wallSamples = cadWall.samples
            wallMeanMs = cadWall.meanMs
            wallMinMs = cadWall.minMs
            wallMaxMs = cadWall.maxMs
            wallSdMs = cadWall.stdDevMs
            windowMs = (nowWallMs - cadWindowStartWallMs).toDouble().coerceAtLeast(1.0)
            totalFrames = cadTotalFrames
            started = cadFirstWallMs != 0L
            sessionMs = (nowWallMs - cadFirstWallMs).toDouble().coerceAtLeast(1.0)
            blockedMeanMs = if (blkCount > 0) blkSumNs / blkCount / 1_000_000.0 else 0.0
            blockedMaxMs = blkMaxNs / 1_000_000.0
            blockedSamples = blkCount
            cadWindowFrames = 0
            cadProducer.resetWindow()
            cadWall.resetWindow()
            blkSumNs = 0L
            blkCount = 0L
            blkMaxNs = 0L
            cadWindowStartWallMs = nowWallMs
        }
        val sessionFps = if (started) totalFrames * 1000.0 / sessionMs else 0.0
        return mapOf(
            "processedFps" to sessionFps,
            "processedFpsWindow" to frames * 1000.0 / windowMs,
            "processedIntervalMeanMs" to meanMs,
            "processedIntervalMinMs" to minMs,
            "processedIntervalMaxMs" to maxMs,
            "processedIntervalStdDevMs" to sdMs,
            "processedIntervalSamples" to samples,
            "processedStallCount" to stalls,
            // WALL series — the arrival-time twin of the four fields above.
            "processedWallIntervalMeanMs" to wallMeanMs,
            "processedWallIntervalMinMs" to wallMinMs,
            "processedWallIntervalMaxMs" to wallMaxMs,
            "processedWallIntervalStdDevMs" to wallSdMs,
            "processedWallIntervalSamples" to wallSamples,
            // v2 diagnostics — the capture thread's downstream hold time.
            "deliveryBlockedMeanMs" to blockedMeanMs,
            "deliveryBlockedMaxMs" to blockedMaxMs,
            "deliveryBlockedSamples" to blockedSamples,
        )
    }

    /**
     * STAGE 0 — the camera's real geometry and its stabilization / metering
     * state, both reached through the SAME private-field reflection hook
     * [applyExposureBias] already uses. This is the reading that decides whether
     * CAM-01 (dead stabilization control) is the actual cause of "the preview
     * shakes".
     *
     * Three groups:
     *   • `cameraInput*`   — the CameraX `Preview` use case's real resolution,
     *     crop rect and rotation. Tests whether the preview's hard-coded 9:16
     *     fitted box matches what the camera actually produces.
     *   • `cameraAvail*`   — CameraCharacteristics: which stabilization / AF
     *     modes and metering locks this device even offers, and its AE target
     *     fps ranges (the unpinned-cadence smoking gun).
     *   • `cameraApplied*` — the capture-request options currently set through
     *     Camera2 interop. Before Stage 1 these are all `null`, which IS the
     *     finding: nothing configures stabilization or cadence today.
     *
     * `camera2Interop` reports whether `androidx.camera.camera2.interop` is on
     * the runtime classpath — the prerequisite Stage 1's capture-request-options
     * path depends on. Resolved by reflection so Stage 0 adds no new Gradle
     * dependency and cannot fail the build if the artifact is absent.
     *
     * Called from [pocMetrics] at ~1 Hz on the platform (main) thread, which is
     * also the thread CameraX's `@MainThread` getters require. The
     * characteristics half is immutable per camera id and cached.
     */
    private fun cameraStateMetrics(): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        val camera = reflectCameraXField("camera") as? androidx.camera.core.Camera
        val preview = reflectCameraXField("preview") as? androidx.camera.core.Preview
        out["cameraFacingFront"] = facingFront

        try {
            val info = preview?.resolutionInfo
            if (info != null) {
                out["cameraInputWidth"] = info.resolution.width
                out["cameraInputHeight"] = info.resolution.height
                out["cameraInputRotationDegrees"] = info.rotationDegrees
                out["cameraInputCropRect"] = info.cropRect.flattenToString()
            }
        } catch (e: Throwable) {
            Log.w(TAG, "cameraStateMetrics: resolutionInfo unavailable: $e")
        }

        if (camera == null) return out
        val interopInfo = try {
            Class.forName("androidx.camera.camera2.interop.Camera2CameraInfo")
        } catch (_: Throwable) {
            null
        }
        out["camera2Interop"] = interopInfo != null

        // ONE resolver, shared with the tuning path — see [activeCameraId]. These
        // two used to resolve the bound camera independently, which is how the
        // diagnostic could report one camera's capabilities while the tuning
        // acted on another's.
        val cameraId = activeCameraId()
        if (cameraId != null) out["cameraId"] = cameraId
        // Whether the characteristics below describe the camera CameraX actually
        // bound (`interop`) or merely the first one with the right facing
        // (`facingFallback`). Anything read under `facingFallback` on a
        // multi-camera device is indicative only.
        out["cameraIdSource"] = if (cameraId != null) "interop" else "facingFallback"

        out.putAll(cameraStaticMetrics(cameraId))
        out.putAll(appliedCaptureRequestMetrics(camera))
        return out
    }

    /**
     * CameraCharacteristics readings for [cameraId] (falling back to the first
     * camera matching the current facing when the interop camera id is
     * unavailable). Cached — characteristics never change for a camera, and the
     * `CameraManager` lookup is a binder call we do not want at every poll.
     */
    private fun cameraStaticMetrics(cameraId: String?): Map<String, Any> {
        // Keyed by facing as well as id: without the interop artifact the id is
        // null on both cameras, and a flip would otherwise serve the stale side.
        val cacheKey = "$cameraId/$facingFront"
        cachedCameraStatics?.let { if (cachedCameraStaticsId == cacheKey) return it }
        val ctx = activityContext ?: pluginBinding?.applicationContext ?: return emptyMap()
        val out = LinkedHashMap<String, Any>()
        try {
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return emptyMap()
            val wantFacing =
                if (facingFront) CameraCharacteristics.LENS_FACING_FRONT
                else CameraCharacteristics.LENS_FACING_BACK
            val id = cameraId ?: manager.cameraIdList.firstOrNull {
                manager.getCameraCharacteristics(it)
                    .get(CameraCharacteristics.LENS_FACING) == wantFacing
            } ?: return emptyMap()
            val c = manager.getCameraCharacteristics(id)
            out["cameraResolvedId"] = id
            c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)
                ?.let { out["cameraAvailEisModes"] = it.joinToString(",") }
            c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
                ?.let { out["cameraAvailOisModes"] = it.joinToString(",") }
            c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
                ?.let { out["cameraAvailAfModes"] = it.joinToString(",") }
            c.get(CameraCharacteristics.CONTROL_AE_LOCK_AVAILABLE)
                ?.let { out["cameraAeLockAvailable"] = it }
            c.get(CameraCharacteristics.CONTROL_AWB_LOCK_AVAILABLE)
                ?.let { out["cameraAwbLockAvailable"] = it }
            c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                ?.let { ranges -> out["cameraAvailAeFpsRanges"] = ranges.joinToString(",") { "${it.lower}-${it.upper}" } }
        } catch (e: Throwable) {
            Log.w(TAG, "cameraStaticMetrics failed (non-fatal): $e")
            return emptyMap()
        }
        cachedCameraStaticsId = cacheKey
        cachedCameraStatics = out
        return out
    }

    /**
     * The capture-request options currently applied through Camera2 interop —
     * i.e. what THIS app asked the camera for. All null before Stage 1.
     *
     * Note the honest limit: this is the requested state, not the per-frame
     * `CaptureResult` the device reports back. Reading the result would require
     * a `Camera2Interop.Extender` session-capture-callback set at use-case BUILD
     * time, and Camera Kit's `CameraXImageProcessorSource` owns that build. So
     * "what the camera actually did" is only inferable here as
     * requested + available; Stage 4 (owning the binding) is what would make the
     * true result readable.
     */
    private fun appliedCaptureRequestMetrics(camera: androidx.camera.core.Camera): Map<String, Any> {
        val out = LinkedHashMap<String, Any>()
        try {
            val controlClass = Class.forName("androidx.camera.camera2.interop.Camera2CameraControl")
            val control = controlClass
                .getMethod("from", androidx.camera.core.CameraControl::class.java)
                .invoke(null, camera.cameraControl) ?: return out
            val options = controlClass.getMethod("getCaptureRequestOptions").invoke(control) ?: return out
            val getOption = options.javaClass.getMethod("getCaptureRequestOption", CaptureRequest.Key::class.java)
            fun read(name: String, key: CaptureRequest.Key<*>) {
                getOption.invoke(options, key)?.let { out[name] = it.toString() }
            }
            read("cameraAppliedEisMode", CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE)
            read("cameraAppliedOisMode", CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE)
            read("cameraAppliedAfMode", CaptureRequest.CONTROL_AF_MODE)
            read("cameraAppliedAeLock", CaptureRequest.CONTROL_AE_LOCK)
            read("cameraAppliedAwbLock", CaptureRequest.CONTROL_AWB_LOCK)
            read("cameraAppliedAeFpsRange", CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE)
        } catch (e: Throwable) {
            Log.w(TAG, "appliedCaptureRequestMetrics failed (non-fatal): $e")
        }
        return out
    }

    /**
     * Read a private field off Camera Kit's [CameraXImageProcessorSource]. This
     * is the same hook [applyExposureBias] has always used — the SDK exposes no
     * public accessor for its CameraX objects, so reflection is the only path.
     * Fully best-effort; every caller must tolerate null.
     */
    private fun reflectCameraXField(name: String): Any? {
        val src = cameraSource ?: return null
        return try {
            CameraXImageProcessorSource::class.java
                .getDeclaredField(name)
                .apply { isAccessible = true }
                .get(src)
        } catch (e: Throwable) {
            Log.w(TAG, "reflectCameraXField($name) failed (non-fatal): $e")
            null
        }
    }

    /**
     * Instrumentation snapshot for the preview/encoder overlay + the release
     * `[PREVIEW_POC]` diagnostic log. Android renders GPU-direct into the Surface
     * (no per-frame CPU hook — see the field docs above), so instead of a true
     * per-frame push counter it samples the Flutter SurfaceTexture's frame
     * timestamp here, at poll time, to detect a preview freeze:
     *
     *   • `previewLastDeltaMs`    — wall-clock ms since the preview frame
     *     timestamp last ADVANCED. ~0 while healthy (frames arrive every poll);
     *     grows unbounded when the preview is frozen. Same field name/units the
     *     Dart overlay + iOS already use.
     *   • `previewAdvanceRatio`   — (producer-time advanced) ÷ (wall-time
     *     elapsed) across the last poll window. ~1.0 when the preview keeps up,
     *     → 0 when it stalls. Android-only "keeping up" health number.
     *   • `previewLastFrameTsNs`  — raw last latched producer timestamp (ns).
     *
     * Producer/encoder-branch cadence is read separately on the Dart side from
     * the WebRTC outbound-rtp stats (`encOutFps` in the log line) — the encoder
     * output is the OTHER consumer off the same shared GPU producer and is also
     * GPU-direct, so it has no Kotlin hook either. The diagnostic split is:
     * encOutFps healthy + preview delta growing → preview-consumer-specific
     * stall; both stall → shared-producer stall; neither stalls → the freeze is
     * outside this pipeline (UI layer).
     *
     * All reads are cheap field/getTimestamp reads on the polling thread — no
     * locks, no per-frame work, nothing that could itself add jank.
     */
    fun pocMetrics(): Map<String, Any> {
        val nowWallMs = SystemClock.elapsedRealtime()
        val tsNs = try {
            previewTextureEntry?.surfaceTexture()?.timestamp ?: 0L
        } catch (_: Throwable) {
            0L
        }

        // Advance ratio over the window since the previous poll: how much
        // producer time the preview gained vs. how much wall time actually
        // passed. -1 = no prior sample yet (first poll).
        var advanceRatio = -1.0
        if (pocLastSampleWallMs != 0L && nowWallMs > pocLastSampleWallMs) {
            val wallDeltaMs = (nowWallMs - pocLastSampleWallMs).toDouble()
            val tsDeltaMs = (tsNs - pocLastPreviewTsNs).coerceAtLeast(0L) / 1_000_000.0
            advanceRatio = tsDeltaMs / wallDeltaMs
        }
        pocLastPreviewTsNs = tsNs
        pocLastSampleWallMs = nowWallMs

        // Track when the timestamp last CHANGED so a freeze grows the delta.
        if (tsNs != lastPreviewFrameTsNs) {
            lastPreviewFrameTsNs = tsNs
            previewTsLastChangedWallMs = nowWallMs
        }
        val previewLastDeltaMs =
            if (previewTsLastChangedWallMs == 0L) 0.0
            else (nowWallMs - previewTsLastChangedWallMs).toDouble()

        val out = LinkedHashMap<String, Any>()
        out["previewConnected"] = previewOutputCloseable != null || previewTextureEntry != null
        out["previewLastFrameTsNs"] = tsNs
        out["previewLastDeltaMs"] = previewLastDeltaMs
        out["previewAdvanceRatio"] = advanceRatio
        out["thermalState"] = currentThermalStatus()
        // STAGE 0 additions — all at this same ~1 Hz cadence, none per-frame.
        out["renderWidth"] = renderWidth
        out["renderHeight"] = renderHeight
        out["previewSurfaceWidth"] = previewSurfaceWidth
        out["previewSurfaceHeight"] = previewSurfaceHeight
        out["processedFrameWidth"] = lastFrameWidth
        out["processedFrameHeight"] = lastFrameHeight
        out["processedFrameRotation"] = lastFrameRotation
        // Did the LAST capture-request submission actually reach the HAL? See
        // [cameraTuningState]. `rejected` means the AE fps pin / EIS / OIS / AF
        // are NOT in the state the rest of this class believes.
        out["cameraTuningState"] = cameraTuningState
        out["cameraTuningError"] = cameraTuningError
        out.putAll(drainCadenceMetrics(nowWallMs))
        out.putAll(cameraStateMetrics())
        return out
    }

    private fun currentThermalStatus(): Int {
        return try {
            val ctx = activityContext ?: pluginBinding?.applicationContext ?: return -1
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return -1
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return -1
            // Fold Android's 0..6 THERMAL_STATUS_* onto the iOS nominal/fair/
            // serious/critical (0..3) the overlay labels.
            val statusTier = when (pm.currentThermalStatus) {
                PowerManager.THERMAL_STATUS_NONE, PowerManager.THERMAL_STATUS_LIGHT -> 0
                PowerManager.THERMAL_STATUS_MODERATE -> 1
                PowerManager.THERMAL_STATUS_SEVERE -> 2
                else -> 3
            }
            // Whichever signal reports MORE pressure wins — see
            // [thermalHeadroomTier]; the fallback must never LOWER a real
            // status reading.
            maxOf(statusTier, thermalHeadroomTier(pm))
        } catch (_: Throwable) {
            -1
        }
    }

    /** Last [thermalHeadroomTier] result + timestamp, so the ~1 Hz pocMetrics
     *  poll can never trip `getThermalHeadroom`'s once-per-second rate limit
     *  (which returns NaN and would erase the reading). */
    @Volatile private var headroomTier = 0
    private var headroomAtMs = 0L

    /**
     * FALLBACK heat signal: `PowerManager.getThermalHeadroom` folded onto the
     * same 0..3 tier scale, where 1.0 is the platform's definition of "the
     * temperature at which THERMAL_STATUS_SEVERE triggers".
     *
     * Why: on at least one OEM (Realme RMX3630, measured 2026-08-20)
     * `currentThermalStatus` stays 0 while the vendor HAL is simultaneously
     * notifying `SKIN 47.3 °C SEVERE` and hardware-throttling the SoC — the
     * status listener is dead, so the app's thermal ladder never engaged.
     * Headroom is served by a different HAL path and can be alive when the
     * status listener is not.
     *
     * Best-effort: NaN (unsupported / rate-limited) keeps the last known tier
     * (initially 0) — exactly the old behaviour, never worse. Kept IDENTICAL
     * to `DeviceMetricsHandler.thermalHeadroomTier` in the app — change both
     * or neither.
     */
    private fun thermalHeadroomTier(pm: PowerManager): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return 0
        val now = SystemClock.elapsedRealtime()
        if (now - headroomAtMs < 5_000L) return headroomTier
        headroomAtMs = now
        val h = try {
            pm.getThermalHeadroom(10)
        } catch (_: Throwable) {
            Float.NaN
        }
        if (!h.isFinite()) return headroomTier
        headroomTier = when {
            h >= 1.10f -> 3
            h >= 1.00f -> 2
            h >= 0.90f -> 1
            else -> 0
        }
        return headroomTier
    }

    fun initialize(
        context: Context,
        apiToken: String,
        defaultGroupId: String,
        groupIds: List<String>,
        onResult: (Boolean) -> Unit,
    ) {
        if (initialized) {
            onResult(true)
            return
        }
        if (!supported(context)) {
            Log.w(TAG, "initialize: Camera Kit not supported on this device")
            onResult(false)
            return
        }

        // New session epoch. Any teardown still pending from a previous dispose()
        // becomes stale from this point and will no-op instead of clobbering the
        // session/camera this initialize() is about to (re)build.
        val gen = ++sessionGeneration
        Log.d(TAG, "[LIVE_TRACE][CAMERA_INIT] initialize gen=$gen — building session (reusing singleton camera source)")

        // Re-baseline the physical camera facing to FRONT for this fresh session.
        // `facingFront` lives on the process-lifetime singleton and is toggled by
        // switchCamera(), so it SURVIVES across go-live sessions. The reused
        // CameraXImageProcessorSource therefore re-opens on the PREVIOUS session's
        // side — a back-camera user's last stream makes the next fresh stream boot
        // `Facing: Back` even though Flutter reset to front (the Logcat latch bug).
        // The Dart side (beauty.part.dart) already ASSUMES a fresh Snap session
        // boots front and re-applies the saved side via enforceSavedCameraPosition
        // on reconnect — this makes that assumption true at the source.
        //
        // Placed here, NOT in startCamera()/dispose(): the `if (initialized)`
        // early-return above means this only runs on a genuinely fresh init, so a
        // mid-stream switchCamera() or a reconnect's saved-side restore is never
        // clobbered.
        facingFront = true
        Log.d(TAG, "[LIVE_TRACE][CAMERA_INIT] facing re-baselined to FRONT (gen=$gen)")

        this.apiToken = apiToken
        this.defaultGroupId = defaultGroupId
        this.prefetchGroupIds = groupIds

        // Close the previous session/output, but REUSE the camera source (below).
        // The source binds to ProcessLifecycleOwner (process-global) and its
        // CameraX use cases never auto-release; creating a SECOND source while the
        // first is still bound is exactly the "too many use cases" crash on
        // re-entry. So there is ever only ONE source for the whole app lifetime —
        // a new session re-wires to it and startPreview rebinds its own use cases.
        try { connectOutputCloseable?.close() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        connectOutputCloseable = null
        session = null

        if (snapThread == null) {
            val t = HandlerThread("SnapCameraKitThread")
            t.start()
            snapThread = t
            snapHandler = Handler(t.looper)
        }
        if (snapHandler == null) {
            onResult(false)
            return
        }

        // CameraX source needs a LifecycleOwner; the plugin has no Activity, so
        // bind to the process lifecycle. Create it ONCE and reuse it across every
        // go-live session — see the note above on why a second source crashes.
        // ProcessLifecycleOwner.get() is a main-thread API and initialize() runs
        // on the platform/main thread.
        val source = cameraSource ?: try {
            CameraXImageProcessorSource(
                context,
                ProcessLifecycleOwner.get(),
                cameraExecutor,
            )
        } catch (e: Throwable) {
            Log.e(TAG, "initialize: CameraXImageProcessorSource failed", e)
            onResult(false)
            return
        }
        cameraSource = source

        // Create the preview container/ViewStub now so attachTo has it; the
        // PlatformView embeds this same instance. attachTo is build-time AND needs
        // the stub attached to a window, so poll briefly for it, then build.
        getOrCreatePreviewView(activityContext ?: context)
        awaitStubThenBuild(context, source, onResult, attempts = 0)
    }

    private fun awaitStubThenBuild(
        context: Context,
        source: CameraXImageProcessorSource,
        onResult: (Boolean) -> Unit,
        attempts: Int,
    ) {
        if (stubAttached || attempts >= MAX_STUB_WAIT_ATTEMPTS) {
            buildSession(context, source, useAttach = stubAttached, onResult = onResult)
        } else {
            mainHandler.postDelayed(
                { awaitStubThenBuild(context, source, onResult, attempts + 1) },
                STUB_WAIT_INTERVAL_MS,
            )
        }
    }

    private fun buildSession(
        context: Context,
        source: CameraXImageProcessorSource,
        useAttach: Boolean,
        onResult: (Boolean) -> Unit,
    ) {
        val handler = snapHandler ?: run { onResult(false); return }
        val token = apiToken ?: run { onResult(false); return }
        val group = defaultGroupId.orEmpty()
        handler.post {
            try {
                // attachTo(stub, withPreview = FALSE): Camera Kit attaches the view
                // hierarchy for LENS TOUCH only — it does NOT render/manage the
                // camera preview (so it does NOT start its own CameraX camera, which
                // is what double-bound and crashed). WE keep driving the camera via
                // startCamera()->startPreview(), and the encoder is fed by our own
                // connectOutput(RECORDING) (attachOutputSurface). The streamer sees
                // the lens through the LiveKit preview underneath; this view is a
                // transparent touch layer on top.
                var builder = newBuilder(context)
                    .apiToken(token)
                    .imageProcessorSource(source)
                val stub = previewStub
                if (useAttach && stub != null) {
                    builder = builder.attachTo(stub, false)
                    attachActive = true
                    Log.d(TAG, "buildSession: attachTo(stub, withPreview=false) — touch only, we drive the camera")
                } else {
                    attachActive = false
                    Log.w(TAG, "buildSession: WITHOUT attachTo — lenses render but no touch this session")
                }
                val built = builder
                    .configureLenses { lenses ->
                        // attachTo inflates Camera Kit's default UI chrome. We supply
                        // our own lens picker + hint UI, so strip the carousel and
                        // the animated loading overlay (the moving Snap "ghost" logo).
                        // The small static "Powered by Snap" attribution stays.
                        lenses.configureCarousel { it.enabled = false }
                        lenses.configureLoadingOverlay { it.enabled = false }
                        configureHintsOn(lenses)
                    }
                    .handleErrorsWith { t ->
                        Log.w(TAG, "Camera Kit error (filters disabled for session)", t)
                    }
                    .build()
                session = built

                val warm = linkedSetOf<String>()
                if (group.isNotBlank()) warm.add(group)
                warm.addAll(prefetchGroupIds.filter { it.isNotBlank() })
                prefetchGroups(built, warm)

                initialized = true
                Log.d(TAG, "initialize: official session ready (render=${renderWidth}x$renderHeight, attach=$attachActive)")
                Log.d(TAG, "[LIVE_TRACE][CAMERA_INIT] session created successfully gen=$sessionGeneration attach=$attachActive render=${renderWidth}x$renderHeight")
                onResult(true)
                // Apply the lens the user picked while the session was still
                // building — without this their tap was silently lost.
                val queuedLens = pendingLensId
                val queuedGroup = pendingGroupId
                if (queuedLens != null && queuedGroup != null) {
                    pendingLensId = null
                    pendingGroupId = null
                    Log.d(TAG, "initialize: applying queued lens $queuedLens/$queuedGroup")
                    applyLens(queuedLens, queuedGroup)
                }
            } catch (e: Throwable) {
                Log.e(TAG, "initialize failed", e)
                // Tear down THIS failed build (current epoch) so the source is freed.
                teardownNativeResources(sessionGeneration)
                onResult(false)
            }
        }
    }

    /**
     * Enable lens hints and redirect them to [hintSink] (→ Flutter) instead of
     * Camera Kit's own view, so the "Tap to start / Look left / Open your mouth"
     * prompts render in our UI. `Hints.View` is a `Consumer<Model>`: `Displayed`
     * carries the localized text, `Hidden` means clear.
     */
    private fun configureHintsOn(lenses: LensesComponent.Builder) {
        lenses.configureHints { cfg ->
            cfg.enabled = true
            cfg.view = object : LensesComponent.Hints.View {
                override fun accept(model: LensesComponent.Hints.View.Model) {
                    when (model) {
                        is LensesComponent.Hints.View.Model.Displayed ->
                            hintSink?.invoke(model.text)
                        is LensesComponent.Hints.View.Model.Hidden ->
                            hintSink?.invoke(null)
                        else -> {}
                    }
                }
            }
        }
    }

    private fun prefetchGroups(session: Session, groupIds: Set<String>) {
        if (groupIds.isEmpty()) return
        try {
            // One Available() query warms every group's cache at once; the actual
            // complete read happens later in getGroupLenses() via observe()+settle.
            session.lenses.repository.get(
                LensesComponent.Repository.QueryCriteria.Available(groupIds),
            ) { /* warm the cache; ignore result */ }
        } catch (e: Throwable) {
            Log.w(TAG, "prefetchGroups failed", e)
        }
    }

    fun applyLens(lensId: String, groupId: String) {
        // Claim the epoch on the CALLING thread, before any async hop, so the
        // ordering is the user's tap order rather than the completion order.
        val gen = lensGeneration.incrementAndGet()
        val session = session
        if (!initialized || session == null) {
            // Queue instead of dropping: the tap landed while the session was
            // (re)building — apply it right after initialize() completes.
            pendingLensId = lensId
            pendingGroupId = groupId
            Log.w(TAG, "applyLens: not initialized — queued $lensId/$groupId for post-init apply")
            return
        }
        runOnSnapThread {
            if (lensGeneration.get() != gen) {
                Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] applyLens.stale_predispatch $lensId gen=$gen cur=${lensGeneration.get()}")
                return@runOnSnapThread
            }
            try {
                session.lenses.repository.get(
                    LensesComponent.Repository.QueryCriteria.ById(lensId, groupId),
                ) { result ->
                    // Hop 1 resolved. A newer tap (or a clear) since dispatch
                    // makes this fetch's result obsolete — dropping it here also
                    // avoids paying for the GPU apply.
                    if (lensGeneration.get() != gen) {
                        Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] applyLens.stale_fetch $lensId gen=$gen cur=${lensGeneration.get()}")
                        return@get
                    }
                    result.whenHasFirst { lens ->
                        session.lenses.processor.apply(lens) { applied ->
                            // Hop 2 resolved. Re-check before publishing state:
                            // this completion can land after a newer selection
                            // has already been applied.
                            if (lensGeneration.get() != gen) {
                                Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] applyLens.stale_apply $lensId gen=$gen cur=${lensGeneration.get()}")
                                return@apply
                            }
                            if (applied) {
                                loadedLensId = lensId
                                loadedGroupId = groupId
                            }
                            Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] applyLens.result $lensId applied=$applied gen=$gen")
                        }
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "applyLens failed for $lensId/$groupId", e)
            }
        }
    }

    fun clearLens() {
        // A clear is a selection too: bumping the epoch invalidates every
        // in-flight apply, so a lens still loading cannot resurrect itself
        // after the user chose "None".
        val gen = lensGeneration.incrementAndGet()
        // The user removed the filter — a lens queued during init must not
        // resurrect it after the session comes up.
        pendingLensId = null
        pendingGroupId = null
        val session = session ?: return
        if (!initialized) return
        runOnSnapThread {
            if (lensGeneration.get() != gen) {
                Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] clearLens.stale_predispatch gen=$gen cur=${lensGeneration.get()}")
                return@runOnSnapThread
            }
            try {
                session.lenses.processor.clear {
                    if (lensGeneration.get() != gen) {
                        Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] clearLens.stale gen=$gen cur=${lensGeneration.get()}")
                        return@clear
                    }
                    loadedLensId = null
                    loadedGroupId = null
                    Log.d(TAG, "[LIVE_TRACE][SNAP_LENS] clearLens.result cleared=$it gen=$gen")
                }
            } catch (e: Throwable) {
                Log.w(TAG, "clearLens failed", e)
            }
        }
    }

    fun setLensParameter(name: String, value: Float) {
        if (!initialized || loadedLensId == null) return
        Log.d(TAG, "setLensParameter: $name=$value (no live-param API; ignored)")
    }

    fun switchCamera() {
        facingFront = !facingFront
        // A camera flip rebinds a fresh CameraX Camera, resetting exposure AND
        // every capture-request option — [startPreviewGuarded] re-applies the
        // brighten bias and the whole tuning set once it settles. The
        // characteristics behind the tuning are read per facing, so this also
        // re-resolves the new camera's supported modes.
        startPreviewGuarded("switchCamera")
        Log.d(TAG, "switchCamera: facingFront=$facingFront")
    }

    /**
     * Move the camera's exposure target by [exposureBiasEv] EV.
     *
     * The raw preview reads dark (front camera especially). Camera Kit's
     * [CameraXImageProcessorSource] exposes no exposure API, so reach its
     * private CameraX `camera` field and shift the exposure-compensation index.
     * Continuous auto-exposure stays ON — this only moves its TARGET, so the
     * camera still adapts to changing light instead of freezing an exposure.
     * Reflection is the only path (no public setter); fully best-effort.
     */
    fun applyExposureBias() {
        mainHandler.post {
            try {
                // Same private-field hook the Stage 0 metrics read through.
                val camera = reflectCameraXField("camera") as? androidx.camera.core.Camera ?: return@post
                val info = camera.cameraInfo
                val range = info.exposureState.exposureCompensationRange
                val step = info.exposureState.exposureCompensationStep
                if (!info.exposureState.isExposureCompensationSupported ||
                    step.toFloat() <= 0f
                ) {
                    return@post
                }
                // The requested EV in the device's own compensation steps,
                // clamped to the range it advertises. Rounded (not truncated) so
                // a coarse-step device still moves on a small request instead of
                // silently collapsing to 0.
                val ev = exposureBiasEv
                val target = Math.round(ev / step.toFloat()).coerceIn(range.lower, range.upper)
                camera.cameraControl.setExposureCompensationIndex(target)
                Log.d(TAG, "applyExposureBias: ev=$ev index=$target (range=$range step=$step)")
            } catch (e: Throwable) {
                Log.w(TAG, "applyExposureBias failed (non-fatal): $e")
            }
        }
    }

    /**
     * The streamer's exposure bias, in EV. Stored and pushed onto the live
     * camera immediately; every later rebind re-applies it through
     * [applyExposureBias]. Clamped to [MIN_EXPOSURE_BIAS_EV]..[MAX_EXPOSURE_BIAS_EV]
     * here as well as against the device's own advertised range, so a bad value
     * from Dart can never blow out the picture.
     */
    fun setExposureBias(ev: Float) {
        val clamped = ev.coerceIn(MIN_EXPOSURE_BIAS_EV, MAX_EXPOSURE_BIAS_EV)
        if (clamped == exposureBiasEv) return
        exposureBiasEv = clamped
        applyExposureBias()
    }

    /**
     * Cap the camera's CAPTURE frame rate. Applied by [applyCameraTuning].
     *
     * THERMAL. The app's framerate ladder (30→24→20→15, driven by the thermal
     * tier) only ever reached the ENCODER's `maxFramerate`. The camera kept
     * capturing at its native rate and Camera Kit kept running the lens shader
     * over EVERY frame on the GPU — so at the deepest thermal step the device
     * still paid full-rate capture + lens processing and simply discarded half
     * those frames at the encoder. That discarded work is pure heat, and it sat
     * outside the thermal ladder entirely. This closes it.
     */
    fun setCaptureFrameRate(fps: Int) {
        if (fps <= 0 || fps == captureFrameRate) return
        captureFrameRate = fps
        applyCameraTuning()
    }

    /**
     * Turn OS image stabilization on/off for the frames Camera Kit sees.
     * Applied by [applyCameraTuning]. See [stabilizationEnabled] for why the
     * default is off and why this could not live in the WebRTC fork.
     */
    fun setVideoStabilization(enabled: Boolean) {
        if (enabled == stabilizationEnabled) return
        stabilizationEnabled = enabled
        applyCameraTuning()
    }

    /**
     * Push the whole camera-tuning set onto the live CameraX camera in ONE
     * capture-request update: pinned cadence, stabilization state, and a
     * video-appropriate auto-focus mode.
     *
     * The three move together deliberately. Each `addCaptureRequestOptions` call
     * replaces the repeating request, which costs a HAL reconfigure (and can
     * cost a frame), and two of them racing would also mean two in-flight
     * futures with no defined ordering. One merged push has neither problem, and
     * it means every re-apply point (camera start, camera flip, a thermal step,
     * a stabilization flip) converges on the same complete state instead of a
     * partial one.
     *
     * **Cadence.** The range's lower bound is set equal to its upper so the rate
     * is PINNED: a `[15,30]` range lets auto-exposure range straight back up to
     * 30 in good light, which is the unpinned cadence the Stage 0 metrics
     * flagged. Only a range the device ADVERTISES is applied.
     *
     * **Stabilization.** EIS and OIS both follow [stabilizationEnabled] — they
     * are one product decision ("may the OS move the image under the lens
     * tracker"), and mixing them would leave motion-correlated warp in the feed
     * either way.
     *
     * **Auto-focus.** Always `CONTINUOUS_VIDEO`, never the OEM default. Most
     * HALs default a preview stream to `CONTINUOUS_PICTURE`, which hunts far
     * more aggressively; every hunt changes per-frame sharpness, which both
     * reads as instability and makes the landmark tracker jitter. We do NOT
     * one-shot-and-lock focus here even though the fork's dead branch did: a
     * locked lens goes soft the moment the streamer moves, which is a worse and
     * far more visible regression than a slow hunt. If hunting still shows up in
     * the numbers, the next lever is CameraX's `startFocusAndMetering` with
     * auto-cancel disabled — a separate, deliberate change.
     *
     * **Metering is untouched.** No `CONTROL_AE_LOCK`, no `CONTROL_AWB_LOCK`.
     * The fork's dead branch set both; the AWB lock is the prime suspect in the
     * open yellow skin-tone investigation (see the diagnostic flags in
     * `CameraUtils.java`), and continuous auto-exposure with a positive bias is
     * what [applyExposureBias] deliberately keeps. Re-enabling either is a
     * separate decision with its own A/B, not a side effect of a stability fix.
     *
     * Every option is applied only if the device advertises support for the
     * value: an unsupported key in a repeating request can make the HAL reject
     * the whole request, which would break capture entirely — much worse than
     * leaving one knob at its default. Fully best-effort; reflection is limited
     * to the `camera` field, the same hook [applyExposureBias] already uses.
     */
    /**
     * Wait for CameraX to finish binding, then apply the exposure bias and the
     * whole tuning set. Cancels any earlier pending chain.
     *
     * **Why this is not just a `postDelayed(…, 400)`.** `startPreview()` binds
     * asynchronously and Camera Kit's private `camera` field stays null until it
     * completes. Both [applyExposureBias] and [applyCameraTuning] resolve that
     * field and return silently when it is null — so a single fixed 400 ms shot
     * meant that on any device (or any cold start, or any thermally throttled
     * moment) where the bind took longer, the camera was left at the OEM's HAL
     * defaults: EIS ON, cadence unpinned, focus on the picture-mode default. No
     * error, no log, nothing to see — the stream just shook, intermittently, on
     * some launches and not others.
     *
     * That is the exact failure mode this whole workstream exists to remove: a
     * camera-stability call that silently does nothing. So the wait polls until
     * the camera is genuinely bound, and if it never binds it says so loudly
     * instead of leaving a stability feature that is quietly off.
     *
     * The generation token makes a rapid start→flip sequence converge on one
     * chain rather than racing two.
     */
    fun scheduleCameraTuning() {
        val gen = ++cameraTuningGeneration
        pollForBoundCamera(gen, attempt = 0)
    }

    private fun pollForBoundCamera(gen: Int, attempt: Int) {
        val delay = if (attempt == 0) CAMERA_TUNING_FIRST_DELAY_MS else CAMERA_TUNING_RETRY_MS
        mainHandler.postDelayed({
            if (gen != cameraTuningGeneration) return@postDelayed
            if (reflectCameraXField("camera") == null) {
                if (attempt + 1 < CAMERA_TUNING_MAX_ATTEMPTS) {
                    pollForBoundCamera(gen, attempt + 1)
                } else {
                    Log.w(
                        TAG,
                        "scheduleCameraTuning: CameraX never bound a camera after " +
                            "${CAMERA_TUNING_MAX_ATTEMPTS} attempts — exposure bias, pinned " +
                            "cadence and stabilization-off were NOT applied; the camera is " +
                            "running on OEM defaults (expect EIS shake).",
                    )
                }
                return@postDelayed
            }
            if (attempt > 0) {
                Log.d(TAG, "scheduleCameraTuning: camera bound after ${attempt + 1} attempts")
            }
            applyExposureBias()
            applyCameraTuning()
        }, delay)
    }

    fun applyCameraTuning() {
        mainHandler.post {
            try {
                val camera = reflectCameraXField("camera") as? androidx.camera.core.Camera ?: run {
                    // Reachable when a Dart-driven setter (thermal step, A/B
                    // toggle) lands while the camera is down. The value is stored,
                    // and the next bind's scheduleCameraTuning re-applies it — so
                    // this is a deferral, not a loss. Logged so it is never
                    // mistaken for "applied".
                    cameraTuningState = "deferred"
                    Log.d(TAG, "applyCameraTuning: no bound camera — deferred to the next camera start")
                    return@post
                }
                val builder = androidx.camera.camera2.interop.CaptureRequestOptions.Builder()
                val applied = StringBuilder()

                val target = captureFrameRate
                if (target > 0) {
                    val chosen = supportedFpsRangeFor(target)
                    if (chosen != null) {
                        builder.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, chosen)
                        applied.append("aeFps=$chosen ")
                        // A range whose bounds differ CAPS the rate without
                        // pinning it — auto-exposure may still drop to `lower` as
                        // the light falls, and a varying cadence is visible as
                        // judder even though every other knob here is correct. The
                        // device's advertised list is logged with it so a
                        // "still shakes on this handset" report can be diagnosed
                        // from a release logcat instead of a repro session.
                        if (chosen.lower != chosen.upper) {
                            Log.w(
                                TAG,
                                "applyCameraTuning: cadence CAPPED but NOT pinned — chose $chosen for " +
                                    "${target}fps; this device advertises no fixed range. Available: " +
                                    availableAeFpsRanges()?.joinToString().orEmpty(),
                            )
                        }
                    } else {
                        Log.w(
                            TAG,
                            "applyCameraTuning: no usable AE fps range at or below ${target}fps — " +
                                "cadence left UNPINNED. Available: " +
                                availableAeFpsRanges()?.joinToString().orEmpty(),
                        )
                    }
                }

                val eisWant =
                    if (stabilizationEnabled) CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    else CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
                if (supportsMode(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, eisWant)) {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, eisWant)
                    applied.append("eis=$eisWant ")
                }

                val oisWant =
                    if (stabilizationEnabled) CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON
                    else CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_OFF
                if (supportsMode(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, oisWant)) {
                    builder.setCaptureRequestOption(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, oisWant)
                    applied.append("ois=$oisWant ")
                }

                val afWant = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                if (supportsMode(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, afWant)) {
                    builder.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, afWant)
                    applied.append("af=$afWant ")
                }

                if (applied.isEmpty()) {
                    cameraTuningState = "unsupported"
                    cameraTuningError = ""
                    Log.w(TAG, "applyCameraTuning: this device supports NONE of the tuning knobs — camera left on OEM defaults")
                    return@post
                }
                val control = androidx.camera.camera2.interop.Camera2CameraControl.from(camera.cameraControl)
                val requested = applied.toString().trim()
                cameraTuningState = "pending"
                cameraTuningError = ""
                // OBSERVE the future. `addCaptureRequestOptions` completes when the
                // options are actually submitted to the HAL, and FAILS when the
                // camera rejects them or the session is closed/unbound underneath
                // us (`CameraControl.OperationCanceledException`). Dropping it —
                // which this call did — made a HAL rejection look exactly like a
                // success: the "applied" line below printed either way, so a
                // handset whose cadence pin never took, or whose capture session
                // was dropped by the batch, reported clean.
                //
                // Cheap: fires once per tuning apply (camera bind, thermal step,
                // A/B toggle), never per frame. `get()` inside the listener does
                // not block — the future is complete by definition there.
                val future = control.addCaptureRequestOptions(builder.build())
                future.addListener({
                    try {
                        future.get()
                        cameraTuningState = "applied"
                        cameraTuningError = ""
                        Log.d(
                            TAG,
                            "applyCameraTuning: HAL ACCEPTED $requested " +
                                "(targetFps=$target stabilization=$stabilizationEnabled)",
                        )
                    } catch (e: Throwable) {
                        // Deliberately W, not D, and it carries the requested set:
                        // this is the line that turns "the pin silently did not
                        // take on this OEM" into a one-grep diagnosis.
                        cameraTuningState = "rejected"
                        cameraTuningError = e.javaClass.simpleName
                        Log.w(
                            TAG,
                            "applyCameraTuning: HAL REJECTED $requested " +
                                "(targetFps=$target stabilization=$stabilizationEnabled) — " +
                                "capture cadence / stabilization / AF are NOT in the state this " +
                                "code believes. cause=$e",
                        )
                    }
                }, mainExecutor)
                Log.d(TAG, "applyCameraTuning: submitted $requested (targetFps=$target stabilization=$stabilizationEnabled)")
            } catch (e: Throwable) {
                cameraTuningState = "rejected"
                cameraTuningError = e.javaClass.simpleName
                Log.w(TAG, "applyCameraTuning failed (non-fatal): $e")
            }
        }
    }

    /**
     * Whether the ACTIVE camera advertises [mode] in the int-array
     * characteristic [key]. Absent characteristic ⇒ false: we would rather leave
     * a knob at its default than push a value the HAL may reject.
     */
    private fun supportsMode(key: CameraCharacteristics.Key<IntArray>, mode: Int): Boolean =
        cameraCharacteristic(key)?.contains(mode) == true

    /**
     * The advertised AE FPS range that best matches [target]: prefer a FIXED
     * range (lower == upper) at or below the target — that is what actually pins
     * the cadence — and fall back to the range whose upper bound is closest at
     * or below it. Returns null when the device advertises nothing usable.
     */
    private fun supportedFpsRangeFor(target: Int): android.util.Range<Int>? {
        val ranges = availableAeFpsRanges() ?: return null
        if (ranges.isEmpty()) return null
        val fixed = ranges.filter { it.lower == it.upper && it.upper <= target }
            .maxByOrNull { it.upper }
        if (fixed != null) return fixed
        // No FIXED range on this device. Take the highest ceiling at or below the
        // target — and among the ties, the one with the HIGHEST FLOOR.
        //
        // That tie-break is load-bearing, and getting it wrong is why a device
        // could still judder with this whole feature "on": `[15,30]` and
        // `[24,30]` have the same ceiling, so `maxByOrNull { it.upper }` returned
        // whichever the HAL happened to list first. Pick `[15,30]` and
        // auto-exposure is still free to halve the capture rate the moment the
        // room dims — which is the unpinned cadence this code exists to stop,
        // silently reintroduced by an arbitrary iteration order. `[24,30]` bounds
        // the same drop to a fifth.
        val candidates = ranges.filter { it.upper <= target }
        val bestUpper = candidates.maxOfOrNull { it.upper } ?: return null
        return candidates.filter { it.upper == bestUpper }.maxByOrNull { it.lower }
    }

    /** Advertised `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` for the active camera. */
    private fun availableAeFpsRanges(): Array<android.util.Range<Int>>? =
        cameraCharacteristic(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)

    /**
     * The camera id CameraX has ACTUALLY bound, read through camera2-interop's
     * `Camera2CameraInfo`. Null when the interop artifact is missing or nothing
     * is bound yet — callers then fall back to facing.
     *
     * Single source of truth for "which physical camera are we talking about",
     * shared by [cameraCharacteristic] (the TUNING path) and [cameraStateMetrics]
     * (the DIAGNOSTIC path). Those two used to resolve the id independently and
     * differently, which is how the tuning path ended up reading a different
     * camera's capabilities than the one it was tuning.
     *
     * Main-thread only: CameraX's `cameraInfo` getters are `@MainThread`. Every
     * caller runs inside a `mainHandler.post { }` or the ~1 Hz platform-thread
     * poll.
     */
    private fun activeCameraId(): String? {
        val camera = reflectCameraXField("camera") as? androidx.camera.core.Camera ?: return null
        return try {
            val cls = Class.forName("androidx.camera.camera2.interop.Camera2CameraInfo")
            val wrapped = cls
                .getMethod("from", androidx.camera.core.CameraInfo::class.java)
                .invoke(null, camera.cameraInfo)
            cls.getMethod("getCameraId").invoke(wrapped) as? String
        } catch (e: Throwable) {
            Log.w(TAG, "activeCameraId failed (non-fatal): $e")
            null
        }
    }

    /**
     * Read [key] off the CameraCharacteristics of the camera CameraX has
     * actually bound.
     *
     * **This used to resolve by facing alone** — `cameraIdList.firstOrNull { it
     * has the wanted LENS_FACING }` — which is wrong on every multi-camera
     * device. Modern handsets expose several ids per facing (wide / ultra-wide /
     * tele / depth, plus logical-vs-physical duplicates), and the first id in
     * the list is frequently NOT the one CameraX bound. The consequences were
     * silent and both directions were bad:
     *
     *  * [supportedFpsRangeFor] chose a `CONTROL_AE_TARGET_FPS_RANGE` from
     *    ANOTHER camera's advertised list, so the range could be one the live
     *    camera never advertised — an undefined value to push at a HAL, and the
     *    likeliest cause of a cadence pin that silently does not take;
     *  * [supportsMode] gated EIS / OIS / AF on the wrong camera's capability
     *    list, so a supported knob could be skipped or an unsupported one sent;
     *  * `camAeFps` in the `PREVIEW_POC` line reported the wrong camera's
     *    available ranges, which would have poisoned the very measurement the
     *    micro-jitter investigation is using to test the "capture is not really
     *    pinned" hypothesis.
     *
     * Now: the interop-resolved ACTIVE id wins; facing is only a fallback for
     * when the interop artifact is genuinely unavailable, and taking that
     * fallback is logged once per change so a reading can never be mistaken for
     * an authoritative one.
     *
     * Best-effort; null on any failure so every caller degrades to "leave the
     * knob alone".
     */
    private fun <T> cameraCharacteristic(key: CameraCharacteristics.Key<T>): T? {
        return try {
            val ctx = activityContext ?: pluginBinding?.applicationContext ?: return null
            val manager = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager ?: return null
            val active = activeCameraId()
            val id = active ?: run {
                val wantFacing =
                    if (facingFront) CameraCharacteristics.LENS_FACING_FRONT
                    else CameraCharacteristics.LENS_FACING_BACK
                manager.cameraIdList.firstOrNull { camId ->
                    manager.getCameraCharacteristics(camId)
                        .get(CameraCharacteristics.LENS_FACING) == wantFacing
                }
            } ?: return null
            if (active == null && lastCharacteristicsIdWasFallback != true) {
                lastCharacteristicsIdWasFallback = true
                Log.w(
                    TAG,
                    "cameraCharacteristic: camera2-interop id unavailable — falling back to the " +
                        "FIRST id with facing front=$facingFront (id=$id). On a multi-camera device " +
                        "this may not be the bound camera; treat camAeFps/camEis/camOis/camAf " +
                        "readings from this session as indicative, not authoritative.",
                )
            } else if (active != null && lastCharacteristicsIdWasFallback != false) {
                lastCharacteristicsIdWasFallback = false
            }
            manager.getCameraCharacteristics(id).get(key)
        } catch (e: Throwable) {
            Log.w(TAG, "cameraCharacteristic(${key.name}) failed (non-fatal): $e")
            null
        }
    }

    fun setTorch(on: Boolean) {
        mainHandler.post {
            try {
                if (on) {
                    if (torchCloseable == null) {
                        torchCloseable = cameraSource?.enableCameraFlash()
                    }
                } else {
                    torchCloseable?.close()
                    torchCloseable = null
                }
            } catch (e: Throwable) {
                Log.w(TAG, "setTorch($on) failed", e)
            }
        }
    }

    /**
     * ABSOLUTE zoom ratio (1.0 = no zoom), clamped to what the bound camera
     * advertises and to [MAX_ZOOM_RATIO].
     *
     * Deliberately NOT `CameraXImageProcessorSource.zoomBy`, which the SDK
     * defines as a RELATIVE scale factor multiplied onto the current ratio — the
     * pinch-gesture shape. A settings slider reports an absolute position, so
     * feeding it to a relative API compounds every frame of the drag and runs
     * the zoom away; it would also disagree with iOS, where `videoZoomFactor` is
     * absolute. Both platforms take an absolute ratio, so Dart holds one number
     * with one meaning.
     *
     * Reaches the CameraX `Camera` through the same private-field hook
     * [applyExposureBias] uses — the SDK exposes no absolute setter. Fully
     * best-effort; a failure leaves the current zoom untouched.
     */
    fun setZoom(factor: Float) {
        mainHandler.post {
            try {
                val camera = reflectCameraXField("camera") as? androidx.camera.core.Camera ?: return@post
                val zoomState = camera.cameraInfo.zoomState.value ?: return@post
                val max = minOf(zoomState.maxZoomRatio, MAX_ZOOM_RATIO)
                val clamped = factor.coerceIn(zoomState.minZoomRatio, max)
                camera.cameraControl.setZoomRatio(clamped)
                Log.d(TAG, "setZoom: ratio=$clamped (requested=$factor max=$max)")
            } catch (e: Throwable) {
                Log.w(TAG, "setZoom($factor) failed (non-fatal): $e")
            }
        }
    }

    /**
     * What this camera can actually do, for the settings sheet: the zoom and
     * exposure ranges the BOUND camera advertises. Reported rather than assumed
     * so the sliders never offer a range the hardware will silently clamp — a
     * control that does nothing at one end reads as a bug.
     *
     * Runs on the main thread (CameraX's `@MainThread` getters require it) and
     * hands the result back through [result]; the caller is the platform channel.
     * Falls back to the app-level bounds when the camera is not bound yet, so
     * the sheet always has a usable range instead of an error.
     */
    fun cameraCapabilities(result: (Map<String, Any>) -> Unit) {
        mainHandler.post {
            val out = mutableMapOf<String, Any>(
                "minExposureEv" to MIN_EXPOSURE_BIAS_EV.toDouble(),
                "maxExposureEv" to MAX_EXPOSURE_BIAS_EV.toDouble(),
                "exposureEv" to exposureBiasEv.toDouble(),
                "maxZoom" to 1.0,
                "zoom" to 1.0,
            )
            try {
                val camera = reflectCameraXField("camera") as? androidx.camera.core.Camera
                if (camera != null) {
                    val exposure = camera.cameraInfo.exposureState
                    val step = exposure.exposureCompensationStep.toFloat()
                    if (exposure.isExposureCompensationSupported && step > 0f) {
                        val range = exposure.exposureCompensationRange
                        out["minExposureEv"] =
                            maxOf(MIN_EXPOSURE_BIAS_EV, range.lower * step).toDouble()
                        out["maxExposureEv"] =
                            minOf(MAX_EXPOSURE_BIAS_EV, range.upper * step).toDouble()
                    } else {
                        // No exposure compensation on this camera: report a
                        // zero-width range so the sheet hides the slider rather
                        // than offering one that cannot move.
                        out["minExposureEv"] = 0.0
                        out["maxExposureEv"] = 0.0
                    }
                    val zoomState = camera.cameraInfo.zoomState.value
                    if (zoomState != null) {
                        out["maxZoom"] = minOf(zoomState.maxZoomRatio, MAX_ZOOM_RATIO).toDouble()
                        out["zoom"] = zoomState.zoomRatio.toDouble()
                    }
                }
            } catch (e: Throwable) {
                Log.w(TAG, "cameraCapabilities failed (non-fatal): $e")
            }
            result(out)
        }
    }

    /** Start the camera (called when the WebRTC capturer starts). We drive it —
     * attachTo uses withPreview=false, so Camera Kit does NOT start its own. */
    fun startCamera() {
        startPreviewGuarded("startCamera")
    }

    /**
     * Start Camera Kit's CameraX preview on a lens facing this device actually
     * has — never on a facing CameraX cannot resolve.
     *
     * **Why the guard exists** (fatal, Crashlytics 2026-08-19):
     *
     * ```
     * java.lang.UnsupportedOperationException
     *   at java.util.Collections$UnmodifiableCollection.retainAll
     *   at androidx.camera.core.CameraSelector.filter(CameraSelector.java:169)
     *   at androidx.camera.lifecycle.LifecycleCameraProviderImpl.bindToLifecycleInternal
     *   at com.snap.camerakit.support.camerax.CameraXImageProcessorSource.startPreview$lambda$12
     * ```
     *
     * Camera Kit builds its selector with its own `LenientLensFacingCameraFilter`,
     * which returns the INPUT list **unchanged** when no camera matches the
     * requested facing ("lenient" = fall back to every camera). CameraX hands
     * every filter a `Collections.unmodifiableList(...)` view and then calls
     * `retainAll` on whatever the filter handed back — so the instant that
     * lenient branch fires, CameraX mutates an unmodifiable list and throws.
     *
     * The throw lands inside a runnable Camera Kit posts to the main looper, so
     * a `try`/`catch` at our call site cannot catch it: the process dies.
     * Prevention is the only lever, and the trigger is exactly "requested facing
     * matches zero CameraX cameras" — resolving the facing against
     * [ProcessCameraProvider.hasCamera] first makes the lenient branch
     * unreachable.
     *
     * This is NOT a CameraX version regression: the same `retainAll` sits in
     * camera-core 1.4.2, 1.5.3 and 1.6.1 (the app resolves 1.6.1 — mobile_scanner
     * and camera_android_camerax both pin it); only the line number moves.
     * Downgrading CameraX would not fix it.
     */
    private fun startPreviewGuarded(reason: String) {
        val ctx = activityContext ?: pluginBinding?.applicationContext
        if (ctx == null) {
            // Nothing to resolve against — behave exactly as before the guard.
            Log.w(TAG, "startPreviewGuarded($reason): no context, starting unguarded")
            startPreviewNow(facingFront, reason)
            return
        }
        val future = ProcessCameraProvider.getInstance(ctx)
        // Main-thread executor: CameraX binds (and Camera Kit expects to be
        // driven) on the main looper, and the listener runs inline when the
        // provider future is already complete.
        future.addListener({
            val provider = try {
                future.get()
            } catch (e: Throwable) {
                Log.w(TAG, "startPreviewGuarded($reason): camera provider unavailable", e)
                null
            }
            val facing = if (provider == null) facingFront else availableFacing(provider, facingFront)
            if (facing == null) {
                // No camera on either side: binding would take the process down.
                Log.e(TAG, "startPreviewGuarded($reason): no CameraX camera available, preview not started")
                return@addListener
            }
            if (facing != facingFront) {
                Log.w(
                    TAG,
                    "startPreviewGuarded($reason): facingFront=$facingFront has no camera, falling back to facingFront=$facing",
                )
                facingFront = facing
            }
            startPreviewNow(facing, reason)
        }, Executor { mainHandler.post(it) })
    }

    /**
     * Entry-device camera-input reduction: when true, `startPreviewNow` asks
     * CameraX for a 4:3 preview instead of the SDK-default 16:9. The SDK maps
     * `Configuration.aspectRatio` → `Preview.Builder.setTargetAspectRatio`
     * (verified in support-camerax 1.50.0 bytecode) — the ONLY public input
     * knob; there is no resolution parameter, so the HAL picks the 4:3 size
     * (verify what it chose via the `camIn=` readout, `cameraInputWidth/
     * Height` from [cameraStateMetrics]). On the measured Helio G36 the 16:9
     * pick is 1280x720; a 4:3 pick (typically 960x720 or 640x480) cuts Camera
     * Kit's per-frame GPU work proportionally. Output/render dims are
     * untouched — this is INPUT only; the processor crops to the 9:16 output
     * as it always has.
     *
     * Set once from Dart before/at engine init (`setCameraInput43`); volatile
     * because the setter runs on the platform thread while starts can be
     * posted. Applies to EVERY subsequent start (initial, switchCamera,
     * recovery) because they all funnel through [startPreviewNow].
     */
    @Volatile private var cameraInput43 = false

    fun setCameraInput43(enabled: Boolean) {
        cameraInput43 = enabled
        Log.d(TAG, "setCameraInput43: $enabled")
    }

    /** The actual start, on the main thread, plus the post-bind tuning pass. */
    private fun startPreviewNow(facing: Boolean, reason: String) {
        try {
            if (cameraInput43) {
                cameraSource?.startPreview(
                    AllowsCameraPreview.Configuration.Default(
                        facing,
                        AspectRatio.RATIO_4_3,
                        Crop.None,
                    ),
                    emptySet(),
                ) { started ->
                    Log.d(TAG, "startPreviewNow($reason): 4:3-input start → $started")
                }
            } else {
                cameraSource?.startPreview(facing)
            }
            // Brighten + re-tune once CameraX has bound its Camera (a beat after
            // startPreview). Re-applied on every start so a stop→start cycle
            // keeps the lift, the pinned cadence and the stabilization state — a
            // fresh binding resets all of them to HAL defaults.
            scheduleCameraTuning()
        } catch (e: Throwable) {
            Log.w(TAG, "startPreviewGuarded($reason): startPreview failed", e)
        }
    }

    /**
     * The facing CameraX can bind: the wanted one when it exists, otherwise the
     * other one, otherwise `null` (no camera at all). `hasCamera` is safe to ask
     * — `DEFAULT_FRONT_CAMERA`/`DEFAULT_BACK_CAMERA` carry the strict
     * `LensFacingCameraFilter`, which always returns a fresh mutable list and so
     * never hits the `retainAll` trap described in [startPreviewGuarded].
     */
    private fun availableFacing(provider: ProcessCameraProvider, wantFront: Boolean): Boolean? {
        fun has(front: Boolean): Boolean = try {
            provider.hasCamera(
                if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "availableFacing: hasCamera(front=$front) failed", e)
            false
        }
        return when {
            has(wantFront) -> wantFront
            has(!wantFront) -> !wantFront
            else -> null
        }
    }

    /** Stop the camera (called when the WebRTC capturer stops). */
    fun stopCamera() {
        mainHandler.post {
            try {
                cameraSource?.stopPreview()
            } catch (e: Throwable) {
                Log.w(TAG, "stopCamera failed", e)
            }
        }
    }

    fun setRenderResolution(width: Int, height: Int, applyLive: Boolean = false) {
        if (width <= 0 || height <= 0) return
        val changed = width != renderWidth || height != renderHeight
        renderWidth = width
        renderHeight = height
        if (!applyLive || !changed) {
            // Record-only (pre-init sizing, or the caller opted out): the next
            // SnapCameraVideoCapturer.startCapture picks the dims up — the
            // pre-2026-08-20 behaviour, byte-identical.
            Log.d(TAG, "setRenderResolution: recorded ${width}x$height (applyLive=$applyLive changed=$changed)")
            return
        }
        // LIVE apply — the thermal/adaptive resolution lever that used to be
        // inert on Android (record-only while the Snap path deliberately never
        // recreates the track; forensics 2026-08-17 §6.1). Resize BOTH Camera
        // Kit outputs in place:
        //  • encoder branch — via the active capturer's surface swap (no track
        //    recreate, no camera cycle, ~100 ms of dropped frames);
        //  • independent preview branch — same swap on its own surface, below.
        val appliedToEncoder = activeCapturer?.applyRenderResolution(width, height) ?: false
        applyPreviewRenderResolution(width, height)
        Log.d(
            TAG,
            "setRenderResolution: LIVE ${width}x$height " +
                "(encoder=$appliedToEncoder preview=${previewTextureEntry != null})",
        )
    }

    /**
     * Resize the independent-preview output (Branch A) in place. Same
     * close-then-connect-then-release ordering as [reattachOutputSurface];
     * the Flutter texture id is unchanged, so the Dart `Texture` widget keeps
     * rendering without a remount.
     */
    @Synchronized
    private fun applyPreviewRenderResolution(width: Int, height: Int) {
        val entry = previewTextureEntry ?: return
        val st = try { entry.surfaceTexture() } catch (_: Throwable) { return }
        st.setDefaultBufferSize(width, height)
        val old = previewSurface
        val next = Surface(st)
        previewSurface = next
        previewSurfaceWidth = width
        previewSurfaceHeight = height
        runOnSnapThread {
            try { previewOutputCloseable?.close() } catch (_: Throwable) {}
            previewOutputCloseable = try {
                session?.processor?.connectOutput(
                    outputFrom(next, ImageProcessor.Output.Purpose.RECORDING),
                )
            } catch (e: Throwable) {
                Log.w(TAG, "applyPreviewRenderResolution: connect failed", e)
                null
            }
            mainHandler.post {
                try { old?.release() } catch (_: Throwable) {}
            }
        }
    }

    fun checkGpuAcceleration(context: Context): Boolean = supported(context)

    /**
     * Enumerate every lens in [groupId]. Snap streams a group's lens metadata in
     * INCREMENTALLY from its cloud, so the one-shot `repository.get()` returns
     * only whatever has synced so far (the SDK explicitly does NOT guarantee a
     * complete set) — that was the "~15 of ~40 lenses" bug. We use
     * `repository.observe()`, which fires on every change, coalesce the
     * emissions, and deliver ONCE the stream goes quiet for [LENS_SETTLE_MS] (or
     * [LENS_MAX_WAIT_MS] elapses), then close the subscription. All state is
     * confined to the single snap thread via [snapHandler].
     */
    fun getGroupLenses(
        groupId: String?,
        onResult: (List<Map<String, String?>>) -> Unit,
    ) {
        val activeSession = session
        if (!initialized || activeSession == null) {
            onResult(emptyList())
            return
        }
        val gid = if (!groupId.isNullOrBlank()) groupId else defaultGroupId
        if (gid.isNullOrBlank()) {
            onResult(emptyList())
            return
        }
        val handler = snapHandler ?: run { onResult(emptyList()); return }

        handler.post {
            var subscription: Closeable? = null
            var delivered = false
            // Delivery requires BOTH gates: the stream has gone quiet for
            // LENS_SETTLE_MS since the union last GREW (quietReached), AND the
            // minimum-wait floor has elapsed (minWaitPassed). See trySettle().
            var quietReached = false
            var minWaitPassed = false
            // The LATEST snapshot Snap reported for this group. observe() emits
            // the FULL current set on every change — it GROWS as the group syncs
            // and SHRINKS when a lens is removed in the dashboard — so we keep the
            // most recent snapshot, NOT a union. A union never drops a removed
            // lens (the "still shows the deleted filter" bug). The settle +
            // min-wait gates below wait for the snapshot to stop changing, so we
            // deliver the complete, CURRENT set (not an early partial during sync).
            var latest: List<Map<String, String?>> = emptyList()
            var latestIds: Set<String> = emptySet()
            // True once Snap has delivered ANY observe() result — including an
            // explicit EMPTY set. An explicit empty is an ANSWER ("this group
            // has zero lenses for this token"), not silence: without it the
            // first empty emission never armed the quiet timer (ids ==
            // latestIds, both empty) and the call burned the full
            // LENS_MAX_WAIT_MS (12s × the Dart warm-up retries ≈ 36s of
            // spinner) before reporting the same 0.
            var sawCallback = false

            fun deliver(reason: String) {
                if (delivered) return
                delivered = true
                try { subscription?.close() } catch (_: Throwable) {}
                Log.d(TAG, "getGroupLenses[$gid] -> ${latest.size} lenses ($reason)")
                onResult(latest)
            }

            // Deliver once the snapshot has been stable for its settle window
            // *and* the LENS_MIN_WAIT_MS floor has passed (or the
            // LENS_MAX_WAIT_MS cap fires). The floor matters because the first
            // emission is usually the instantly-available cached subset (~10);
            // the rest of the group lands a cloud round-trip later. An EMPTY
            // snapshot is deliverable only when Snap actually answered
            // (sawCallback, with the wider LENS_EMPTY_SETTLE_MS window) — pure
            // silence still waits for the cap.
            fun trySettle() {
                if (!delivered && quietReached && minWaitPassed && (latest.isNotEmpty() || sawCallback)) {
                    deliver(if (latest.isEmpty()) "settle-empty" else "settle")
                }
            }

            val settle = Runnable { quietReached = true; trySettle() }
            val minWaitGate = Runnable { minWaitPassed = true; trySettle() }
            val hardTimeout = Runnable { deliver("maxWait") }

            try {
                subscription = activeSession.lenses.repository.observe(
                    LensesComponent.Repository.QueryCriteria.Available(setOf(gid)),
                ) { result ->
                    // observe() may dispatch off the snap thread — marshal back so
                    // all state mutation + timer ops stay single-threaded.
                    handler.post {
                        if (delivered) return@post
                        val lenses =
                            (result as? LensesComponent.Repository.Result.Some)?.lenses
                        val snapshot = ArrayList<Map<String, String?>>()
                        val ids = LinkedHashSet<String>()
                        if (lenses != null) {
                            for (lens in lenses) {
                                val id = lens.id
                                if (id.isNullOrEmpty() || !ids.add(id)) continue
                                snapshot.add(
                                    mapOf(
                                        "lensId" to lens.id,
                                        "groupId" to lens.groupId,
                                        "name" to lens.name,
                                        "iconUrl" to lens.icons.firstOrNull()?.uri,
                                    ),
                                )
                            }
                        }
                        Log.d(TAG, "getGroupLenses[$gid] snapshot=${snapshot.size}")
                        // Re-arm the quiet timer whenever the snapshot CHANGES
                        // (grows OR shrinks) — and also on the very FIRST
                        // callback even when it is identical to the initial
                        // empty state, so an explicit "0 lenses" answer arms
                        // the timer instead of silently waiting out the cap.
                        val isFirstCallback = !sawCallback
                        sawCallback = true
                        if (ids != latestIds || isFirstCallback) {
                            latest = snapshot
                            latestIds = ids
                            quietReached = false
                            handler.removeCallbacks(settle)
                            handler.postDelayed(
                                settle,
                                if (snapshot.isEmpty()) LENS_EMPTY_SETTLE_MS else LENS_SETTLE_MS,
                            )
                        }
                    }
                }
                // Minimum-wait floor (hold delivery open for the cloud batch) and
                // absolute cap (a perpetually-changing group still resolves).
                handler.postDelayed(minWaitGate, LENS_MIN_WAIT_MS)
                handler.postDelayed(hardTimeout, LENS_MAX_WAIT_MS)
            } catch (e: Throwable) {
                Log.w(TAG, "getGroupLenses failed", e)
                deliver("error")
            }
        }
    }

    private inline fun runOnSnapThread(crossinline block: () -> Unit) {
        val handler = snapHandler ?: return
        handler.post { block() }
    }

    private fun teardownNativeResources(expectedGen: Int) {
        // GENERATION GUARD: if a newer initialize() has begun since this teardown
        // was issued, the session + camera now belong to that newer epoch. Tearing
        // them down here would close the LIVE session and unbind the LIVE camera —
        // the black-preview leak. Skip; the newer epoch owns its own cleanup.
        if (expectedGen != sessionGeneration) {
            Log.d(TAG, "[LIVE_TRACE][CAMERA_INIT] stale teardown SKIPPED (gen=$expectedGen current=$sessionGeneration) — leaving the live session intact")
            return
        }
        Log.d(TAG, "[LIVE_TRACE][CAMERA_INIT] Tearing down old camera resources to prevent memory leaks... (gen=$expectedGen)")
        try { torchCloseable?.close() } catch (_: Throwable) {}
        // Free the camera (unbind the CameraX use cases) but KEEP the source
        // instance alive to reuse next session. stopPreview() MUST run on the MAIN
        // thread — CameraX bind/unbind is main-thread only, and off-main it throws
        // (silently swallowed here), leaving the camera bound. That lingering bind,
        // plus a freshly-created second source, was the "too many use cases"
        // re-entry crash. Keeping ONE source means startPreview just rebinds its own
        // use cases. The cameraExecutor MUST stay alive for the unbind to complete.
        val src = cameraSource
        mainHandler.post { try { src?.stopPreview() } catch (_: Throwable) {} }
        try { connectOutputCloseable?.close() } catch (_: Throwable) {}
        try { session?.close() } catch (_: Throwable) {}
        torchCloseable = null
        connectOutputCloseable = null
        // Release the encoder output surface AFTER its output close above. This is
        // the dead-handler-safe release: dispose() posts this teardown then nulls
        // snapHandler, so a releaseCapture()/detachOutputSurface() that runs after
        // (the normal destroy order — camera engine disposed before the video
        // track is stopped) cannot post its own close, and this path owns the
        // release instead. Null-after-release keeps it single.
        val encSurface = encoderOutputSurface
        encoderOutputSurface = null
        encSurface?.let { mainHandler.post { runCatching { it.release() } } }
        session = null
        attachActive = false
    }

    fun dispose() {
        if (!initialized && session == null) return
        val genAtDispose = sessionGeneration
        Log.d(TAG, "[LIVE_TRACE][CAMERA_INIT] dispose requested (gen=$genAtDispose)")
        // Disconnect + release the independent preview texture first so its
        // Camera Kit output is closed before the session is torn down below.
        stopPreviewTexture()
        val handler = snapHandler
        val thread = snapThread
        if (handler != null) {
            handler.post {
                // No-ops if a newer initialize() has bumped the epoch in between,
                // so this can never close a live session / unbind a live camera.
                teardownNativeResources(genAtDispose)
                if (genAtDispose == sessionGeneration) {
                    apiToken = null
                    defaultGroupId = null
                    prefetchGroupIds = emptyList()
                    loadedLensId = null
                    loadedGroupId = null
                }
                // Always quit the OLD thread we captured — a newer session runs on
                // its own freshly-created thread, so this never kills the live one.
                thread?.quitSafely()
            }
        } else {
            teardownNativeResources(genAtDispose)
        }
        // Do NOT shut down cameraExecutor here. It was being killed on the main
        // thread BEFORE the posted teardown ran stopPreview() on the snap thread,
        // so the CameraX unbind couldn't complete → use cases lingered → next
        // session crashed. The executor is cheap (one thread) and is reused across
        // sessions; keeping it alive lets every stopPreview() unbind cleanly.
        hintSink = null
        previewContainer = null
        previewStub = null
        stubAttached = false
        initialized = false
        snapThread = null
        snapHandler = null
    }

    companion object {
        private const val TAG = "SnapCameraKitController"
        private const val DEFAULT_RENDER_W = 720
        private const val DEFAULT_RENDER_H = 1280

        /**
         * Exposure bias every stream runs at until the streamer changes it —
         * the value that shipped hard-coded before the control existed, kept as
         * the default so an untouched account looks exactly as it did.
         */
        const val DEFAULT_EXPOSURE_BIAS_EV = 0.6f

        /**
         * Bounds on what the streamer may ask for. Wider than the ±1 EV a phone
         * camera app usually offers (the front camera genuinely needs the extra
         * headroom in a dim room) but far short of the ±4 EV some HALs
         * advertise, where the picture is pure noise or pure white and the
         * streamer has no way to know the app is doing what they asked.
         */
        const val MIN_EXPOSURE_BIAS_EV = -2.0f
        const val MAX_EXPOSURE_BIAS_EV = 2.0f

        /**
         * Ceiling on digital zoom offered to the streamer. Devices advertise far
         * more, but past this the crop is upscaled mush at broadcast bitrates —
         * and the live encoder then spends its bits on that mush instead of the
         * face.
         */
        const val MAX_ZOOM_RATIO = 6.0f

        /** First wait before the post-bind camera tuning — the settle window the
         *  exposure bias has always used. */
        private const val CAMERA_TUNING_FIRST_DELAY_MS = 400L

        /** Poll interval + attempt cap while waiting for CameraX to bind (~2.6 s
         *  total). Long enough for a cold or thermally throttled bind, short
         *  enough that a genuinely failed bind is reported, not waited on. */
        private const val CAMERA_TUNING_RETRY_MS = 200L
        private const val CAMERA_TUNING_MAX_ATTEMPTS = 12

        /** A processed-frame interval above this counts as a stall, not cadence
         *  (reported separately so it cannot silently inflate the spread). */
        private const val CADENCE_STALL_MS = 250.0

        /**
         * Upper bound on a credible delivery-blocked reading (1 s). Anything
         * above it means the two clock reads straddled a process freeze or a
         * device suspend, not an encoder hold, and it is discarded rather than
         * dominating the window's mean the way one fabricated interval would.
         */
        private const val MAX_BLOCKED_NS = 1_000_000_000L

        /** Poll cadence + cap (~1.2s) waiting for the preview ViewStub to attach. */
        private const val STUB_WAIT_INTERVAL_MS = 50L
        private const val MAX_STUB_WAIT_ATTEMPTS = 24

        /** Deliver once the union of observed lenses has stopped GROWING for this
         *  long — wide enough to bridge Snap's inter-batch network gaps so a
         *  group that streams in several batches is fully accumulated, not cut
         *  off after the first batch. */
        private const val LENS_SETTLE_MS = 1500L

        /** Quiet window for an EMPTY snapshot — deliberately wider than
         *  [LENS_SETTLE_MS]: an early empty emission during a cold-repository
         *  sync is normal and the cloud batch usually lands within a couple of
         *  seconds, so an empty answer must stay open longer before it is
         *  believed. */
        private const val LENS_EMPTY_SETTLE_MS = 4000L

        /** Minimum time to keep observing before a quiet window may deliver. The
         *  first emission is usually the instantly-available cached subset (~10);
         *  this floor holds delivery open long enough for the cloud round-trip to
         *  bring the rest of the group, so we never settle on the partial subset. */
        private const val LENS_MIN_WAIT_MS = 2500L

        /** Hard cap so a never-quiet group still resolves with what it has. Sized
         *  for a large group that streams many batches over a slow connection. */
        private const val LENS_MAX_WAIT_MS = 12000L
    }
}

package net.vibezo.snap_camera_kit_processor

/**
 * Allocation-free running statistics for ONE series of frame intervals.
 *
 * Extracted out of [SnapCameraKitController] so the per-frame hot path is
 * unit-testable on a plain JVM (no Android, no CameraX, no Camera Kit session).
 * The controller owns two instances:
 *
 *  * the **producer** series, fed the frame's own `timestampNs` — what the media
 *    timeline says the spacing was, and therefore what every viewer's jitter
 *    buffer will reproduce;
 *  * the **wall** series, fed `SystemClock.elapsedRealtimeNanos()` read at frame
 *    listener entry — what the spacing actually was on this device.
 *
 * Comparing the two is the whole point: equal spreads mean frames genuinely
 * arrive scattered; a clean wall series against a scattered producer series
 * means the frames arrive on a grid and the timestamps are lying.
 *
 * ## Allocation contract (load-bearing — see `IntervalAccumulatorTest`)
 * Every field is a primitive `long` / `double` / `int`, every accessor returns a
 * primitive, and [accept] performs no boxing, no varargs, no collection access
 * and no string work. This runs on WebRTC's `SurfaceTextureHelper` handler
 * thread once per captured frame, in front of nothing, and it must never be the
 * reason a GC pause lands mid-broadcast. Do not add a nullable field, a generic
 * container, or a log call to this class.
 *
 * Not thread-safe by itself — the caller holds the monitor (the controller's
 * existing `cadenceLock`, uncontended except against the ~1 Hz drain).
 */
internal class IntervalAccumulator(
    /**
     * Intervals above this are counted in [stalls] as well as in the series. Set
     * to `0.0` to disable stall counting entirely (the wall series does).
     *
     * NOTE the deliberate asymmetry: a stall is *also* summed into the mean and
     * the deviation. That is the historical behaviour the 2026-08-17 baseline
     * (`ivlSd` 7.9–10.8 ms) was measured with, so it is preserved exactly —
     * changing it would silently redefine the number every prior reading is
     * compared against.
     */
    private val stallThresholdMs: Double,
) {
    /** Last accepted timestamp, ns. `0` = no sample yet / series reset. */
    private var lastNs: Long = 0L

    private var sumMs: Double = 0.0
    private var sumSqMs: Double = 0.0

    /** Intervals accumulated in the CURRENT window. */
    var samples: Int = 0
        private set

    /** Smallest interval this window, ms. `0.0` when the window is empty. */
    var minMs: Double = 0.0
        private set

    /** Largest interval this window, ms. `0.0` when the window is empty. */
    var maxMs: Double = 0.0
        private set

    /** Intervals above [stallThresholdMs] this window. */
    var stalls: Int = 0
        private set

    /** Mean interval this window, ms. `0.0` when the window is empty. */
    val meanMs: Double
        get() = if (samples > 0) sumMs / samples else 0.0

    /**
     * Population standard deviation of the interval this window, ms.
     *
     * Computed from the running sums and clamped at zero: floating-point
     * cancellation in `E[x²] - E[x]²` can push a genuinely-zero variance
     * slightly negative, and `sqrt` of that is `NaN`, which would poison the
     * emitted diagnostic line.
     */
    val stdDevMs: Double
        get() {
            if (samples <= 0) return 0.0
            val mean = sumMs / samples
            val variance = (sumSqMs / samples - mean * mean).coerceAtLeast(0.0)
            return Math.sqrt(variance)
        }

    /**
     * Feed one timestamp. Non-monotonic and first-of-series samples advance
     * [lastNs] but contribute no interval — a producer clock that goes backwards
     * must not manufacture a negative delta.
     */
    fun accept(tsNs: Long) {
        val prev = lastNs
        lastNs = tsNs
        if (prev <= 0L || tsNs <= prev) return
        val deltaMs = (tsNs - prev) / 1_000_000.0
        samples++
        sumMs += deltaMs
        sumSqMs += deltaMs * deltaMs
        if (minMs == 0.0 || deltaMs < minMs) minMs = deltaMs
        if (deltaMs > maxMs) maxMs = deltaMs
        if (stallThresholdMs > 0.0 && deltaMs > stallThresholdMs) stalls++
    }

    /**
     * Clear the WINDOW statistics but keep [lastNs], so the next sample still
     * produces a real interval across the drain boundary. This is what the ~1 Hz
     * `pocMetrics()` poll calls.
     */
    fun resetWindow() {
        samples = 0
        sumMs = 0.0
        sumSqMs = 0.0
        minMs = 0.0
        maxMs = 0.0
        stalls = 0
    }

    /**
     * Clear the window AND the series anchor. Call when frame delivery genuinely
     * restarts (a new capture session), so the gap across the restart is not
     * accumulated as one enormous interval — which would swamp the deviation for
     * the whole window and make the reading useless exactly when it matters.
     */
    fun resetSeries() {
        resetWindow()
        lastNs = 0L
    }
}

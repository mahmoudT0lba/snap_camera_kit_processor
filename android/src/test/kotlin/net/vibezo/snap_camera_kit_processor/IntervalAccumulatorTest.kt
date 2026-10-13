package net.vibezo.snap_camera_kit_processor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Unit tests for the per-frame hot path of the micro-jitter instrumentation.
 *
 * Run: `./gradlew :snap_camera_kit_processor:test`
 *
 * Two things are under test and they matter for different reasons:
 *
 *  1. **Correctness of the statistics.** `ivlSd` / `wallIvlSd` are the numbers a
 *     root-cause decision will be made on, so the mean/σ/min/max must be right
 *     for the exact shapes we expect to see in the field — including the evenly
 *     paced case that must read ~0 and the alternating case that must NOT be
 *     hidden by a healthy average.
 *  2. **The allocation contract.** This code runs on WebRTC's
 *     `SurfaceTextureHelper` handler thread once per captured frame on a device
 *     that is already thermally saturated. If it allocates, it can be the reason
 *     a GC pause lands mid-broadcast — i.e. the diagnostic would manufacture the
 *     defect it was added to measure.
 */
class IntervalAccumulatorTest {

    private companion object {
        const val MS = 1_000_000L // ns per ms
        const val STALL_MS = 250.0
        const val EPS = 1e-9
    }

    private fun producer() = IntervalAccumulator(STALL_MS)

    // ── Correctness ─────────────────────────────────────────────────────────

    @Test
    fun `first sample establishes the anchor and yields no interval`() {
        val a = producer()
        a.accept(1_000 * MS)
        assertEquals(0, a.samples)
        assertEquals(0.0, a.meanMs, EPS)
        assertEquals(0.0, a.stdDevMs, EPS)
        assertEquals(0.0, a.minMs, EPS)
        assertEquals(0.0, a.maxMs, EPS)
    }

    @Test
    fun `perfectly paced 20fps reads mean 50ms and zero deviation`() {
        val a = producer()
        var t = 0L
        repeat(21) { a.accept(t); t += 50 * MS }
        assertEquals(20, a.samples)
        assertEquals(50.0, a.meanMs, EPS)
        // The headline property: an evenly spaced series must not manufacture
        // spread out of floating-point cancellation in E[x²] - E[x]².
        assertEquals(0.0, a.stdDevMs, 1e-6)
        assertEquals(50.0, a.minMs, EPS)
        assertEquals(50.0, a.maxMs, EPS)
        assertEquals(0, a.stalls)
    }

    @Test
    fun `alternating 16-50ms pacing is caught even though the average looks healthy`() {
        // The §11 failure mode from the forensics doc: ~30 fps on average, but
        // frames land in pairs. An average-only metric calls this healthy; the
        // deviation is what exposes it. This test is the regression guard for
        // ever "simplifying" the accumulator back to a mean.
        val a = producer()
        var t = 0L
        val pattern = longArrayOf(16, 50)
        a.accept(t)
        repeat(20) { i ->
            t += pattern[i % 2] * MS
            a.accept(t)
        }
        assertEquals(20, a.samples)
        assertEquals(33.0, a.meanMs, EPS)            // ~30 fps — looks fine
        assertEquals(17.0, a.stdDevMs, 1e-6)          // ...and is not fine
        assertEquals(16.0, a.minMs, EPS)
        assertEquals(50.0, a.maxMs, EPS)
        assertEquals(0, a.stalls)                     // none crosses 250 ms
    }

    @Test
    fun `non-monotonic timestamp is rejected but still re-anchors the series`() {
        val a = producer()
        a.accept(1_000 * MS)
        a.accept(1_050 * MS)      // +50 ms, accepted
        a.accept(900 * MS)        // clock went BACKWARDS — no negative delta
        assertEquals(1, a.samples)
        assertEquals(50.0, a.meanMs, EPS)
        // Re-anchored at 900 ms, so the next sample measures from THERE.
        a.accept(950 * MS)
        assertEquals(2, a.samples)
        assertEquals(50.0, a.meanMs, EPS)
    }

    @Test
    fun `duplicate timestamp contributes no interval`() {
        val a = producer()
        a.accept(1_000 * MS)
        a.accept(1_000 * MS)
        assertEquals(0, a.samples)
    }

    @Test
    fun `stall is counted and, per the preserved 2026-08-17 semantics, still summed`() {
        // Deliberate: the historical baseline (ivlSd 7.9-10.8 ms) was measured
        // with stalls included in the mean/σ. Excluding them here would silently
        // redefine the number every prior reading is compared against.
        val a = producer()
        var t = 0L
        a.accept(t)
        t += 50 * MS; a.accept(t)
        t += 300 * MS; a.accept(t)   // stall
        assertEquals(1, a.stalls)
        assertEquals(2, a.samples)
        assertEquals(175.0, a.meanMs, EPS)
        assertEquals(300.0, a.maxMs, EPS)
    }

    @Test
    fun `wall series with a zero threshold never counts a stall`() {
        val a = IntervalAccumulator(0.0)
        var t = 0L
        a.accept(t)
        t += 5_000 * MS; a.accept(t)
        assertEquals(0, a.stalls)
        assertEquals(1, a.samples)
    }

    @Test
    fun `resetWindow clears statistics but keeps the anchor across the drain`() {
        val a = producer()
        var t = 0L
        a.accept(t)
        t += 50 * MS; a.accept(t)
        a.resetWindow()
        assertEquals(0, a.samples)
        assertEquals(0.0, a.minMs, EPS)
        assertEquals(0.0, a.maxMs, EPS)
        assertEquals(0, a.stalls)
        // Anchor survived: the next frame is measured from the last one, not
        // dropped. A ~1 Hz drain must not blind the first frame of each window.
        t += 50 * MS; a.accept(t)
        assertEquals(1, a.samples)
        assertEquals(50.0, a.meanMs, EPS)
    }

    @Test
    fun `resetSeries drops the anchor so a capture restart gap is not accumulated`() {
        // The reason SnapCameraVideoCapturer.startCapture calls this: without it
        // the first frame after a background resume differences against the last
        // frame before it, and that one gap-sized sample swamps the window.
        val a = producer()
        a.accept(1_000 * MS)
        a.resetSeries()
        a.accept(60_000 * MS)     // 59 s later — must NOT become an interval
        assertEquals(0, a.samples)
        assertEquals(0.0, a.maxMs, EPS)
        a.accept(60_050 * MS)
        assertEquals(1, a.samples)
        assertEquals(50.0, a.meanMs, EPS)
    }

    @Test
    fun `variance never goes negative on a long constant series`() {
        // E[x²] - E[x]² cancels catastrophically when the mean is large and the
        // variance is zero; unclamped this returns a tiny negative and sqrt()
        // yields NaN, which would poison the emitted PREVIEW_POC line.
        val a = producer()
        var t = 0L
        repeat(100_000) { a.accept(t); t += 50 * MS }
        assertTrue("stdDev must not be NaN", !a.stdDevMs.isNaN())
        assertTrue("stdDev must not be negative", a.stdDevMs >= 0.0)
        assertEquals(0.0, a.stdDevMs, 1e-6)
    }

    // ── Allocation contract ─────────────────────────────────────────────────

    @Test
    fun `accept allocates nothing per frame`() {
        val bean = threadAllocationBean()
        assumeTrue("JVM does not expose per-thread allocation counters", bean != null)

        val a = producer()
        var t = 0L
        // Warm up: let the JIT compile accept() and let any one-time class-init
        // and boxing-cache work happen OUTSIDE the measured window, so what we
        // measure is steady-state per-frame cost — which is the only state a
        // broadcast ever runs in.
        repeat(200_000) { a.accept(t); t += 50 * MS }

        val before = allocatedBytes(bean!!)
        repeat(1_000_000) { a.accept(t); t += 50 * MS }
        val after = allocatedBytes(bean)
        val perCall = (after - before).toDouble() / 1_000_000.0

        // Strictly zero is the intent; a small non-zero budget absorbs the
        // sampling profiler's own bookkeeping on some JVMs. If this ever fails,
        // something in accept() started boxing (a nullable field, a generic
        // container, a vararg, a string) — that is the regression to hunt, not a
        // threshold to raise.
        assertTrue(
            "accept() allocated ~$perCall bytes/frame — the hot path must not allocate",
            perCall < 1.0,
        )
    }

    @Test
    fun `reading the statistics allocates nothing`() {
        val bean = threadAllocationBean()
        assumeTrue("JVM does not expose per-thread allocation counters", bean != null)

        val a = producer()
        var t = 0L
        repeat(1_000) { a.accept(t); t += 50 * MS }
        // Warm up the getters too — they are what the ~1 Hz drain calls.
        var sink = 0.0
        repeat(200_000) { sink += a.meanMs + a.stdDevMs + a.minMs + a.maxMs }

        val before = allocatedBytes(bean!!)
        repeat(1_000_000) { sink += a.meanMs + a.stdDevMs + a.minMs + a.maxMs }
        val after = allocatedBytes(bean)
        assertTrue("sink only exists to defeat dead-code elimination", sink.isFinite())

        val perRead = (after - before).toDouble() / 1_000_000.0
        // Guards against a Double/Int property ever becoming nullable, which
        // would silently box on every read.
        assertTrue(
            "statistics getters allocated ~$perRead bytes/read — they must return primitives",
            perRead < 1.0,
        )
    }

    @Test
    fun `accept creates no threads`() {
        // The accumulator must stay a plain data holder. A thread, executor or
        // handler appearing in here would put scheduling on the frame path.
        val a = producer()
        val before = Thread.activeCount()
        var t = 0L
        repeat(100_000) { a.accept(t); t += 50 * MS }
        assertEquals(
            "accept() must not create threads",
            before.toLong(),
            Thread.activeCount().toLong(),
        )
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * `com.sun.management.ThreadMXBean`, reached reflectively so the test still
     * compiles and runs (skipped via [assumeTrue]) on a JVM that does not ship
     * it. Returns null when unavailable.
     */
    private fun threadAllocationBean(): Any? = try {
        val bean = java.lang.management.ManagementFactory.getThreadMXBean()
        val cls = Class.forName("com.sun.management.ThreadMXBean")
        if (cls.isInstance(bean)) bean else null
    } catch (_: Throwable) {
        null
    }

    private fun allocatedBytes(bean: Any): Long = try {
        val cls = Class.forName("com.sun.management.ThreadMXBean")
        cls.getMethod("getCurrentThreadAllocatedBytes").invoke(bean) as Long
    } catch (_: Throwable) {
        -1L
    }
}

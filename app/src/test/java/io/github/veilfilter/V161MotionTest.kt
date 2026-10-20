package io.github.veilfilter

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.6.1: a dancing body (150x300 capture px, +-80 px sway at up to ~0.4 px/ms,
 * vertical bob) seen by a detector with 60 ms latency every 23 ms, +-3 px
 * edge noise. The box shown at display time (track prediction, as the
 * renderer extrapolates it) is compared with the true body.
 */
class V161MotionTest {
    private fun cx(t: Double) = 300 + 80 * sin(2 * PI * t / 1200.0)
    private fun cy(t: Double) = 640 + 25 * sin(2 * PI * t / 500.0)

    private class Res(val lag: Double, val uncovered: Double)

    var noise = 6f
    var stillMode = true
    private fun run(update: (FloatArray, Long, Long) -> Unit, raw: (Long) -> FloatArray?, box: (Long) -> FloatArray?): Res {
        val rnd = Random(5)
        var lagSum = 0.0; var uncSum = 0.0; var n = 0
        val pending = ArrayDeque<Pair<Long, FloatArray>>()
        var nextCap = 0L
        for (t in 0L until 6000L step 4L) {
            if (t >= nextCap) {
                val n4 = FloatArray(4) { (rnd.nextFloat() - 0.5f) * noise }
                val x = cx(t.toDouble()).toFloat(); val y = cy(t.toDouble()).toFloat()
                pending.addLast(t to floatArrayOf(x - 75 + n4[0], y - 150 + n4[1], x + 75 + n4[2], y + 150 + n4[3]))
                nextCap += 23
            }
            while (pending.isNotEmpty() && pending.first().first + 60 <= t) {
                val (tc, d) = pending.removeFirst()
                update(d, tc, t)
            }
            if (t < 1000 || t % 8L != 0L) continue
            val b = box(t) ?: continue
            val c = raw(t)!!
            val x = cx(t.toDouble()); val y = cy(t.toDouble())
            lagSum += max(abs((c[0] + c[2]) / 2 - x), abs((c[1] + c[3]) / 2 - y))
            // body pixels outside the shown box (left/right/top/bottom overhang)
            uncSum += max(0.0, b[0] - (x - 75)) + max(0.0, (x + 75) - b[2]) + max(0.0, b[1] - (y - 150)) + max(0.0, (y + 150) - b[3])
            n++
        }
        return Res(lagSum / n, uncSum / n)
    }

    @Test
    fun diag() {
        for (nz in listOf(0f, 6f)) {
            noise = nz
            val nw = Tracker()
            var errSum = 0.0; var cnt = 0; var movingFrac = 0
            val r = run({ d, tc, now -> nw.update(1, floatArrayOf(d[0]), floatArrayOf(d[1]), floatArrayOf(d[2]), floatArrayOf(d[3]), floatArrayOf(0.8f), intArrayOf(3), now, false, measT = tc); if (nw.tracks.first().moving) movingFrac++; cnt++ },
                { t -> nw.tracks.firstOrNull()?.let { floatArrayOf(it.predictX1(t, 200), it.predictY1(t, 200), it.predictX2(t, 200), it.predictY2(t, 200)) } }) { t ->
                nw.tracks.firstOrNull()?.let { floatArrayOf(it.predictX1(t, 200), it.predictY1(t, 200), it.predictX2(t, 200), it.predictY2(t, 200)) } }
            println("V161 DIAG noise $nz lag ${r.lag} moving ${movingFrac}/$cnt")
        }
        noise = 6f
    }

    @Test
    fun movingBodyIsFollowedWithLessLag() {
        // published box = prediction + the pipeline's padding (PAD 10 % + MIN_PAD, plus
        // lead padding ahead of the motion: v1.6 used the detector age (~60 ms), v1.6.1 half of it)
        fun pad(b: FloatArray, vx: Float, vy: Float, moving: Boolean, padMs: Float): FloatArray {
            val px = (b[2] - b[0]) * 0.10f + 3f; val py = (b[3] - b[1]) * 0.10f + 3f
            val lx = if (moving) vx * padMs else 0f; val ly = if (moving) vy * padMs else 0f
            return floatArrayOf(b[0] - px - max(-lx, 0f), b[1] - py - max(-ly, 0f), b[2] + px + max(lx, 0f), b[3] + py + max(ly, 0f))
        }
        val old = TrackerV16()
        val o = run({ d, _, now -> old.update(1, floatArrayOf(d[0]), floatArrayOf(d[1]), floatArrayOf(d[2]), floatArrayOf(d[3]), floatArrayOf(0.8f), intArrayOf(3), now, false) }, { t -> old.tracks.firstOrNull()?.let { floatArrayOf(it.predictX1(t, 200), it.predictY1(t, 200), it.predictX2(t, 200), it.predictY2(t, 200)) } }) { t ->
            old.tracks.firstOrNull()?.let { pad(floatArrayOf(it.predictX1(t, 200), it.predictY1(t, 200), it.predictX2(t, 200), it.predictY2(t, 200)), it.vx, it.vy, it.speed() > 0.05f, 60f) }
        }
        val nw = Tracker()
        val r = run({ d, tc, now -> nw.update(1, floatArrayOf(d[0]), floatArrayOf(d[1]), floatArrayOf(d[2]), floatArrayOf(d[3]), floatArrayOf(0.8f), intArrayOf(3), now, false, measT = tc) }, { t -> nw.tracks.firstOrNull()?.let { floatArrayOf(it.predictX1(t, 200), it.predictY1(t, 200), it.predictX2(t, 200), it.predictY2(t, 200)) } }) { t ->
            nw.tracks.firstOrNull()?.let { pad(floatArrayOf(it.predictX1(t, 200), it.predictY1(t, 200), it.predictX2(t, 200), it.predictY2(t, 200)), it.vx, it.vy, it.speed() > 0.05f, 30f) }
        }
        println("V161 MOTION centre lag old %.1f px new %.1f px | uncovered old %.1f px new %.1f px".format(o.lag, r.lag, o.uncovered, r.uncovered))
        assertTrue("lag ${r.lag} vs ${o.lag}", r.lag < 0.5 * o.lag)
        assertTrue("uncovered ${r.uncovered} vs ${o.uncovered}", r.uncovered <= o.uncovered + 0.5)
    }
}

class FreshWaitTest {
    @org.junit.Test
    fun rules() {
        assertTrue(FreshWait.timeoutMs(2f, 16.7f) == 0L)      // fresh enough: go
        assertTrue(FreshWait.timeoutMs(10f, 16.7f) in 7L..9L)  // old, next due in ~6.7 ms
        assertTrue(FreshWait.timeoutMs(16.5f, 16.7f) == 0L)    // next is imminent/late: go
        assertTrue(FreshWait.timeoutMs(30f, 60f) == 0L)        // slow capture: never wait
    }

    /** Simulated detector: 22 ms inference, frames READY every [iv] ms. Mean age of the shown detection. */
    private fun staleness(iv: Double, useWait: Boolean): Pair<Double, Double> {
        var t = 0.0; var shownAge = 0.0; var shownSince = 0.0; var acc = 0.0; var dets = 0
        val phase = 3.7
        while (t < 60_000) {
            val lastReady = Math.floor((t - phase) / iv) * iv + phase
            var start = t; var frame = lastReady
            if (useWait) {
                val w = FreshWait.timeoutMs((t - lastReady).toFloat(), iv.toFloat())
                if (w > 0) { frame = lastReady + iv; start = maxOf(t, frame) }
            }
            val done = start + 22.0
            // integrate staleness of the currently shown result until 'done'
            acc += (done - shownSince) * (shownAge + (done - shownSince) / 2)
            shownSince = done; shownAge = done - frame
            t = done; dets++
        }
        return acc / t to dets * 1000.0 / t
    }

    @org.junit.Test
    fun waitingLowersMeanStaleness() {
        for (iv in listOf(16.7, 20.0, 8.3)) {
            val (a0, f0) = staleness(iv, false); val (a1, f1) = staleness(iv, true)
            println("V161 FRESH iv %.1f: mean shown-detection age %.1f -> %.1f ms, det rate %.1f -> %.1f /s".format(iv, a0, a1, f0, f1))
            assertTrue(a1 <= a0)
        }
    }
}

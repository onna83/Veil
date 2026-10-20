package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sin

/**
 * End-to-end simulation of the v1.1 scroll path WITHOUT Android: a synthetic
 * page scrolls at constant speed, frames arrive every 16 ms, the "detector"
 * returns the true box of frame N two frames later. Replicates what Pipeline
 * does per frame (shift estimate -> tracker.applyShift; detections shifted by
 * the cumulative scroll since their frame) and checks that the tracked box
 * stays on the content, and that velocity extrapolation predicts ahead.
 */
class ScrollTrackingSimTest {
    private val w = 270
    private val h = 585
    private val stride = w * 4

    private fun page(x: Double, y: Double): Int {
        val v = 120 + 50 * sin(x * 0.031 + y * 0.017) + 40 * sin(y * 0.053) * sin(x * 0.011 + 1.3) +
            (if (((y / 23).toInt() % 3 == 0) && ((x / 7).toInt() % 4 != 0)) 35 else 0) +
            25 * sin(y * 0.21 + x * 0.002)
        return v.toInt().coerceIn(0, 255)
    }

    private fun render(oy: Double, f: ByteArray) {
        for (y in 0 until h) {
            val sy = y - oy
            val y0 = floor(sy); val fy = sy - y0
            for (x in 0 until w) {
                val v = page(x.toDouble(), y0) * (1 - fy) + page(x.toDouble(), y0 + 1) * fy
                val p = y * stride + x * 4
                f[p] = (v * 0.8).toInt().toByte(); f[p + 1] = v.toInt().toByte(); f[p + 2] = (255 - v).toInt().toByte(); f[p + 3] = -1
            }
        }
    }

    @Test
    fun boxesFollowScrollAtCaptureRateWithLatencyCompensation() {
        val v = -0.6 // px/ms, content moving up (a brisk scroll at this capture scale)
        val dt = 16L
        val objTop = 400.0; val objBot = 470.0 // page coordinates of the "object"
        val est = ShiftEstimator()
        val tr = Tracker()
        val frame = ByteArray(stride * h)
        val none = FloatArray(0)
        var cum = 0.0
        val cumAt = HashMap<Int, Double>()
        val offAt = HashMap<Int, Double>()
        var gv = 0f
        var maxErr = 0f
        var maxNaiveErr = 0.0
        var maxPredErr = 0f
        var maxUnder = 0f
        for (n in 0 until 24) {
            val t = n * dt
            val off = v * t
            render(off, frame)
            est.analyze(frame, w, h, stride, none, 0)
            if (est.confident && !est.static) {
                cum += est.dy
                tr.applyShift(0f, est.dy)
                gv = gv * 0.4f + (est.dy / dt) * 0.6f
            }
            cumAt[n] = cum; offAt[n] = off
            // detection of frame n-2 arrives now (detector latency ~2 frames)
            val src = n - 2
            if (src >= 0 && src % 2 == 0) {
                val o = offAt[src]!!
                val comp = (cum - cumAt[src]!!).toFloat()
                val x1 = floatArrayOf(100f); val x2 = floatArrayOf(160f)
                val y1 = floatArrayOf((objTop + o).toFloat() + comp)
                val y2 = floatArrayOf((objBot + o).toFloat() + comp)
                tr.update(1, x1, y1, x2, y2, floatArrayOf(0.8f), intArrayOf(3), t, false)
                maxNaiveErr = max(maxNaiveErr, abs(off - o)) // v1: box from frame n-2, no compensation
            }
            if (n >= 6) {
                assertEquals(1, tr.tracks.size)
                val k = tr.tracks[0]
                val err = abs(k.y1 - (objTop + off).toFloat())
                maxErr = max(maxErr, err)
                // v1.5 edge hysteresis may over-cover by a few px while still; never under-cover
                maxUnder = max(maxUnder, k.y1 - (objTop + off).toFloat())
                // renderer-style forward prediction to t + 40 ms (velocity = residual + global)
                val lead = 40f
                val pred = k.y1 + (k.vy + gv) * lead
                maxPredErr = max(maxPredErr, abs(pred - (objTop + v * (t + lead)).toFloat()))
            }
        }
        println("sim: naive=%.1f tracked=%.2f predicted=%.2f px".format(maxNaiveErr, maxErr, maxPredErr))
        // Without compensation the box trails by ~ v * 2 frames = 19 px; with it it sticks.
        assertTrue("naive error $maxNaiveErr", maxNaiveErr > 15)
        // tracking error <3 px plus the anti-jitter hysteresis margin (0.75 x 4.8 px here)
        assertTrue("tracked error $maxErr px", maxErr < 3f + 3.6f)
        assertTrue("under-coverage $maxUnder px", maxUnder < 1f)
        assertTrue("predicted error $maxPredErr px", maxPredErr < 4f + 3.6f)
    }
}

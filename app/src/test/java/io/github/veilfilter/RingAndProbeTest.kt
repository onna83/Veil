package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class RingAndProbeTest {
    private val w = 300; private val h = 600; private val stride = w * 4

    private fun frame(oy: Int): ByteArray {
        val f = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val v = (128 + 60 * sin((y - oy) * 0.07) + 50 * sin(x * 0.11 + (y - oy) * 0.02)).toInt()
            f[y * stride + x * 4 + 1] = v.toByte()
        }
        return f
    }

    private val none = FloatArray(0)

    @Test
    fun ringFollowsScrollAndDetectsChange() {
        val sig = RingSignature()
        val a = frame(0)
        RingCheck.record(sig, a, w, h, stride, 100f, 200f, 180f, 300f, none, 0, 0.0, 0.0)
        assertEquals(0f, RingCheck.diff(sig, a, w, h, stride, none, 0, 0.0, 0.0), 0.01f)
        // content scrolled up by 40 px, and cumulative shift says so
        val b = frame(-40)
        assertTrue(RingCheck.diff(sig, b, w, h, stride, none, 0, 0.0, -40.0) < 1f)
        // but if our estimate were wrong by 15 px, the ring no longer matches
        assertTrue(RingCheck.diff(sig, b, w, h, stride, none, 0, 0.0, -25.0) > RingCheck.MAX_DIFF)
    }

    @Test
    fun ringIgnoresPointsUnderCensors() {
        val sig = RingSignature()
        val a = frame(0)
        // everything excluded -> no decision
        val all = floatArrayOf(0f, 0f, w.toFloat(), h.toFloat())
        RingCheck.record(sig, a, w, h, stride, 100f, 200f, 180f, 300f, all, 1, 0.0, 0.0)
        assertTrue(RingCheck.diff(sig, a, w, h, stride, none, 0, 0.0, 0.0).isNaN())
    }

    @Test
    fun probeDetectsFullScreenAndMeasuresLatency() {
        val p = CaptureProbe()
        var t = 0L
        var shownColor = p.color()
        p.onShown(p.currentIndex(), t)
        var screen = shownColor // what the capture shows (our overlay is captured)
        repeat(40) {
            t += 16
            // capture shows the overlay with ~30 ms delay
            if (t >= 30 && screen != shownColor) screen = shownColor
            if (p.onFrame(screen, t)) {
                shownColor = p.color(); p.onShown(p.currentIndex(), t)
            }
            if (t >= 30) screen = shownColor
        }
        assertEquals(CaptureProbe.Mode.FULL_SCREEN, p.mode)
        assertTrue(p.latencyMs >= 0)
    }

    @Test
    fun probeDetectsSingleApp() {
        val p = CaptureProbe()
        var t = 0L
        p.onShown(p.currentIndex(), t)
        repeat(200) {
            t += 16
            // capture shows app content (white), never our probe
            if (p.onFrame(0xFFFFFFFF.toInt(), t)) p.onShown(p.currentIndex(), t)
        }
        assertEquals(CaptureProbe.Mode.SINGLE_APP, p.mode)
    }

    @Test
    fun probeNotFooledByStaticDarkContent() {
        // App content that happens to equal one probe colour must not look like FULL.
        val p = CaptureProbe()
        var t = 0L
        p.onShown(p.currentIndex(), t)
        repeat(300) {
            t += 16
            if (p.onFrame(CaptureProbe.COLORS[0], t)) p.onShown(p.currentIndex(), t)
        }
        assertTrue(p.mode != CaptureProbe.Mode.FULL_SCREEN)
    }
}

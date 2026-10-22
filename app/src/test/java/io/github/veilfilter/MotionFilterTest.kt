package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class MotionFilterTest {
    @Test fun oneEuroConvergesAndSmooths() {
        val f = OneEuro(); f.reset(0f)
        var maxDev = 0f
        val rnd = java.util.Random(3)
        repeat(200) {
            val out = f.filter(1f + rnd.nextGaussian().toFloat() * 0.1f, 16.7f, 2.5f, 0f, 4f)
            if (it > 60) maxDev = maxOf(maxDev, abs(out - 1f))
        }
        assertEquals(1f, f.value, 0.06f)
        assertTrue("filtered noise $maxDev should be well under raw 3 sigma (0.3)", maxDev < 0.15f)
    }

    @Test fun deadZoneGainIsSmooth() {
        assertEquals(0f, OneEuro.deadZoneGain(0.01f, 0.025f, 0.08f), 0f)
        assertEquals(1f, OneEuro.deadZoneGain(0.2f, 0.025f, 0.08f), 0f)
        val mid = OneEuro.deadZoneGain(0.0525f, 0.025f, 0.08f)
        assertTrue(mid > 0.4f && mid < 0.6f)
    }

    @Test fun globalMotionLegacyMatchesV18Ema() {
        val g = GlobalMotion()
        val t = MotionTuning.V18
        var vy = 0f
        val dys = floatArrayOf(10f, 12f, 9f, 11f, 10f)
        for (dy in dys) {
            g.update(0f, dy, 16f, confident = true, static = false, hintScroll = false, t = t)
            vy += 0.6f * (dy / 16f - vy)
            assertEquals(vy, g.vy, 1e-5f)
        }
        g.update(0f, 0f, 16f, confident = false, static = false, hintScroll = false, t = t)
        assertEquals(vy * 0.5f, g.vy, 1e-5f)
        g.update(0f, 0f, 16f, confident = true, static = true, hintScroll = false, t = t)
        assertEquals(0f, g.vy, 0f)
    }

    @Test fun globalMotionStopsAndReversesWithoutLag() {
        val g = GlobalMotion()
        val t = MotionTuning.NORMAL
        repeat(20) { g.update(0f, 16f, 16f, confident = true, static = false, hintScroll = false, t = t) }
        assertEquals(1f, g.vy, 0.05f)
        g.update(0f, 0f, 16f, confident = true, static = true, hintScroll = false, t = t)
        assertEquals(0f, g.vy, 0f)
        repeat(3) { g.update(0f, 16f, 16f, confident = true, static = false, hintScroll = false, t = t) }
        g.update(0f, -16f, 16f, confident = true, static = false, hintScroll = false, t = t)
        assertTrue("a reversal is followed at once, got ${g.vy}", g.vy < -0.9f)
    }

    @Test fun gapTermCoversRecentWorstGapAndDecays() {
        val gt = GapTracker()
        gt.add(16.7f); gt.add(50f)
        assertEquals(50f, gt.gapMs, 0.01f)
        repeat(200) { gt.add(16.7f) }
        assertTrue(gt.gapMs < 20f)
        assertEquals(48f, GapTracker.term(100f, 16f), 0.01f)
    }

    @Test fun blockCountHysteresis() {
        assertEquals(10, BoxShaper.blockCount(10, 9.8f, 0.35f))   // small shrink: keep
        assertEquals(11, BoxShaper.blockCount(10, 10.2f, 0.35f))  // any growth: at once
        assertEquals(9, BoxShaper.blockCount(10, 8.5f, 0.35f))    // clear shrink
    }

    @Test fun edgeHysteresisKeepsSnappedEdge() {
        assertEquals(4, BoxShaper.edgeLo(4.9f, 4, 1.35f))   // near the old line: keep
        assertEquals(3, BoxShaper.edgeLo(3.9f, 4, 1.35f))   // must still cover: move out at once
        assertEquals(5, BoxShaper.edgeLo(5.5f, 4, 1.35f))   // well past: move in
        assertEquals(10, BoxShaper.edgeHi(9.2f, 10, 1.35f))
        assertEquals(11, BoxShaper.edgeHi(10.1f, 10, 1.35f))
    }

    @Test fun stablePlacementCoversAndKeepsSize() {
        val out = IntArray(4)
        var w0 = -1
        for (i in 0 until 100) {
            val x = 100f + i * 0.137f
            OverlayRenderer.place(x, 50f, x + 110f, 200f, 0f, 0f, 0f, 3f, 7f, 1.83f, 1.83f, true, out)
            val fl = 3f + x * 1.83f; val fr = 3f + (x + 110f) * 1.83f
            assertTrue(out[0] <= fl && out[0] + out[2] >= fr)
            if (w0 < 0) w0 = out[2] else assertEquals(w0, out[2])
        }
    }
}

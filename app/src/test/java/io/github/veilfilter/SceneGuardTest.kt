package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ceil
import kotlin.math.floor

class SceneGuardTest {
    private val w = 540; private val h = 1200; private val stride = w * 4 + 32

    private fun frame(seed: Int): ByteArray {
        val f = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * stride + x * 4
            f[p] = ((x * 3 + y + seed * 101) % 256).toByte()
            f[p + 1] = ((y * 2 + seed * 53) % 256).toByte()
            f[p + 2] = ((x + y * 5 + seed * 29) % 256).toByte()
            f[p + 3] = -1
        }
        return f
    }

    @Test
    fun staticContentDoesNotTrigger() {
        val g = SceneGuard()
        val f = frame(0)
        assertFalse(g.analyze(f, w, h, stride)) // first frame: no reference
        assertFalse(g.analyze(f, w, h, stride))
        assertEquals(0f, g.changedFraction, 0f)
    }

    @Test
    fun cutTriggersWithBoundingBox() {
        val g = SceneGuard()
        val a = frame(0)
        g.analyze(a, w, h, stride)
        // change only the lower half (e.g. a video player)
        val b = a.copyOf()
        val other = frame(1)
        for (y in h / 2 until h) System.arraycopy(other, y * stride, b, y * stride, stride)
        assertTrue(g.analyze(b, w, h, stride))
        assertTrue(g.changedT >= 15 && g.changedB == 31)
        assertEquals(0, g.changedL); assertEquals(31, g.changedR)
    }

    @Test
    fun ownGuardPixelationDoesNotRetrigger() {
        // Full-screen capture: after the guard is shown, the captured frame
        // contains our solid cell blocks. That must not count as a new cut.
        val g = SceneGuard()
        g.analyze(frame(0), w, h, stride)
        assertTrue(g.analyze(frame(7), w, h, stride))
        val colors = g.cellColor.copyOf()
        val rendered = ByteArray(stride * h)
        for (cj in 0 until 32) for (ci in 0 until 32) {
            val c = colors[cj * 32 + ci]
            // nearest-neighbour upscale like the overlay, boundaries rounded as on screen
            val x0 = floor(g.cellX(ci, w)).toInt(); val x1 = ceil(g.cellX(ci + 1, w)).toInt().coerceAtMost(w)
            val y0 = floor(g.cellY(cj, h)).toInt(); val y1 = ceil(g.cellY(cj + 1, h)).toInt().coerceAtMost(h)
            for (y in y0 until y1) for (x in x0 until x1) {
                val p = y * stride + x * 4
                rendered[p] = (c shr 16).toByte(); rendered[p + 1] = (c shr 8).toByte(); rendered[p + 2] = c.toByte()
            }
        }
        assertFalse(g.analyze(rendered, w, h, stride))
        assertTrue("fraction ${g.changedFraction}", g.changedFraction < 0.02f)
    }
}

package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

class ShiftEstimatorTest {
    private val w = 540
    private val h = 1170
    private val stride = w * 4 + 32

    /** A "page": smooth blobs + text-like stripes, defined on an infinite plane. */
    private fun page(x: Double, y: Double): Int {
        val v = 120 + 50 * sin(x * 0.031 + y * 0.017) + 40 * sin(y * 0.053) * sin(x * 0.011 + 1.3) +
            (if (((y / 23).toInt() % 3 == 0) && ((x / 7).toInt() % 4 != 0)) 35 else 0) +
            25 * sin(y * 0.21 + x * 0.002)
        return v.toInt().coerceIn(0, 255)
    }

    /** Render the page scrolled by (ox, oy) with bilinear sampling (sub-pixel shifts). */
    private fun render(ox: Double, oy: Double, f: ByteArray = ByteArray(stride * h)): ByteArray {
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - ox; val sy = y - oy
            val x0 = floor(sx); val y0 = floor(sy)
            val fx = sx - x0; val fy = sy - y0
            val v = (page(x0, y0) * (1 - fx) * (1 - fy) + page(x0 + 1, y0) * fx * (1 - fy) +
                page(x0, y0 + 1) * (1 - fx) * fy + page(x0 + 1, y0 + 1) * fx * fy)
            val p = y * stride + x * 4
            f[p] = (v * 0.8).toInt().toByte(); f[p + 1] = v.toInt().toByte(); f[p + 2] = (255 - v).toInt().toByte(); f[p + 3] = -1
        }
        return f
    }

    private val none = FloatArray(0)

    @Test
    fun staticIsStatic() {
        val e = ShiftEstimator()
        val a = render(0.0, 0.0)
        e.analyze(a, w, h, stride, none, 0)
        e.analyze(a, w, h, stride, none, 0)
        assertTrue(e.static); assertTrue(e.confident)
        assertEquals(0f, e.dy, 0f)
    }

    @Test
    fun integerVerticalScroll() {
        for (d in intArrayOf(-37, -3, 12, 120)) {
            val e = ShiftEstimator()
            e.analyze(render(0.0, 0.0), w, h, stride, none, 0)
            e.analyze(render(0.0, d.toDouble()), w, h, stride, none, 0)
            assertTrue("confident for $d", e.confident)
            assertEquals("dy for $d", d.toFloat(), e.dy, 0.3f)
            assertEquals("dx for $d", 0f, e.dx, 0.3f)
        }
    }

    @Test
    fun subPixelVerticalAndHorizontal() {
        var e = ShiftEstimator()
        e.analyze(render(0.0, 0.0), w, h, stride, none, 0)
        e.analyze(render(0.0, -10.5), w, h, stride, none, 0)
        assertTrue(e.confident)
        assertEquals(-10.5f, e.dy, 0.35f)
        assertEquals(0f, e.dx, 0f)
        e = ShiftEstimator()
        e.analyze(render(0.0, 0.0), w, h, stride, none, 0)
        e.analyze(render(-6.5, 0.0), w, h, stride, none, 0)
        assertTrue(e.confident)
        assertEquals(-6.5f, e.dx, 0.35f)
        assertEquals(0f, e.dy, 0f)
    }

    @Test
    fun ignoresOwnCensorRegions() {
        // A static opaque block (our overlay captured in full-screen mode)
        // covering a big part of the frame must not pull the estimate to 0.
        val rects = floatArrayOf(40f, 300f, 400f, 700f, 300f, 50f, 520f, 200f)
        fun paint(f: ByteArray): ByteArray {
            for (k in 0 until 2) for (y in rects[4 * k + 1].toInt() until rects[4 * k + 3].toInt())
                for (x in rects[4 * k].toInt() until rects[4 * k + 2].toInt()) {
                    val p = y * stride + x * 4; f[p] = 50; f[p + 1] = 60; f[p + 2] = 70
                }
            return f
        }
        val e = ShiftEstimator()
        e.analyze(paint(render(0.0, 0.0)), w, h, stride, rects, 2)
        e.analyze(paint(render(0.0, -25.0)), w, h, stride, rects, 2)
        assertTrue(e.confident)
        assertEquals(-25f, e.dy, 0.3f)
    }

    @Test
    fun unrelatedContentIsNotAConfidentScroll() {
        val e = ShiftEstimator()
        val r = Random(5)
        val a = ByteArray(stride * h) { r.nextInt(256).toByte() }
        val b = ByteArray(stride * h) { r.nextInt(256).toByte() }
        e.analyze(render(0.0, 0.0), w, h, stride, none, 0)
        e.analyze(a, w, h, stride, none, 0)
        assertFalse("cut is not a scroll", e.confident && e.dy != 0f)
        e.analyze(b, w, h, stride, none, 0)
        assertFalse(e.static)
    }

    @Test
    fun runtimeIsSmall() {
        val e = ShiftEstimator()
        val a = render(0.0, 0.0); val b = render(0.0, -9.0)
        repeat(20) { e.analyze(if (it % 2 == 0) a else b, w, h, stride, none, 0) }
        val t0 = System.nanoTime()
        repeat(50) { e.analyze(if (it % 2 == 0) a else b, w, h, stride, none, 0) }
        val ms = (System.nanoTime() - t0) / 50 / 1e6
        println("ShiftEstimator: %.2f ms/frame on the build JVM".format(ms))
        assertTrue(ms < 20)
    }
}

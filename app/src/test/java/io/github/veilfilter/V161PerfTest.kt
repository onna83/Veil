package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sin

/**
 * v1.6.1: shift-estimator accuracy + cost on the phone's real capture size
 * (590x1280, padded stride), old (v1.2-v1.6) vs new estimator.
 */
class V161PerfTest {
    private val w = 590
    private val h = 1280
    private val stride = w * 4 + 64

    private fun page(x: Double, y: Double): Int {
        val v = 120 + 50 * sin(x * 0.031 + y * 0.017) + 40 * sin(y * 0.053) * sin(x * 0.011 + 1.3) +
            (if (((y / 23).toInt() % 3 == 0) && ((x / 7).toInt() % 4 != 0)) 35 else 0) +
            25 * sin(y * 0.21 + x * 0.002)
        return v.toInt().coerceIn(0, 255)
    }

    // tall integer-pixel page for fast crops
    private val tallH = h + 400
    private val tall: ByteArray by lazy {
        val f = ByteArray(stride * tallH)
        for (y in 0 until tallH) for (x in 0 until w) {
            val v = page(x.toDouble(), y.toDouble()); val p = y * stride + x * 4
            f[p] = (v * 0.8).toInt().toByte(); f[p + 1] = v.toByte(); f[p + 2] = (255 - v).toByte(); f[p + 3] = -1
        }
        f
    }

    private fun crop(off: Int, dst: ByteArray) = System.arraycopy(tall, off * stride, dst, 0, stride * h)

    private fun render(ox: Double, oy: Double): ByteArray {
        val f = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - ox; val sy = y - oy
            val x0 = floor(sx); val y0 = floor(sy); val fx = sx - x0; val fy = sy - y0
            val v = page(x0, y0) * (1 - fx) * (1 - fy) + page(x0 + 1, y0) * fx * (1 - fy) +
                page(x0, y0 + 1) * (1 - fx) * fy + page(x0 + 1, y0 + 1) * fx * fy
            val p = y * stride + x * 4
            f[p] = (v * 0.8).toInt().toByte(); f[p + 1] = v.toInt().toByte(); f[p + 2] = (255 - v).toInt().toByte(); f[p + 3] = -1
        }
        return f
    }

    private val none = FloatArray(0)

    @Test
    fun compareOldNewAccuracy() {
        val base = render(0.0, 0.0)
        for (d in listOf(-0.6, 0.5, 1.0, -3.0, 2.5, -7.25, 61.0)) {
            val o = ShiftEstimatorV12(); o.analyze(base, w, h, stride, none, 0); val f = render(0.0, d); o.analyze(f, w, h, stride, none, 0)
            val n = ShiftEstimator(); n.analyze(base, w, h, stride, none, 0); n.analyze(f, w, h, stride, none, 0)
            println("V161 ACC d=$d old ${o.dy} ${o.confident} s0 ${o.sad0} sb ${o.sadBest} | new ${n.dy} ${n.confident} s0 ${n.sad0} sb ${n.sadBest}")
        }
    }

    @Test
    fun accuracyAt590x1280() {
        val base = render(0.0, 0.0)
        var worst = 0f
        for (d in listOf(1.0, -1.0, -3.0, 2.5, -7.25, 13.0, -33.5, 61.0, -140.0, 0.75)) {
            val e = ShiftEstimator()
            e.analyze(base, w, h, stride, none, 0)
            e.analyze(render(0.0, d), w, h, stride, none, 0)
            assertTrue("confident $d", e.confident)
            val err = abs(e.dy - d.toFloat())
            worst = max(worst, err)
            assertEquals("dy $d", d.toFloat(), e.dy, 0.35f)
            assertEquals("dx for dy $d", 0f, e.dx, 0f)
        }
        for (d in listOf(-6.5, 4.0, 21.0)) {
            val e = ShiftEstimator()
            e.analyze(base, w, h, stride, none, 0)
            e.analyze(render(d, 0.0), w, h, stride, none, 0)
            assertTrue(e.confident)
            assertEquals("dx $d", d.toFloat(), e.dx, 0.35f)
        }
        println("V161 worst dy error ${"%.3f".format(worst)} px")
    }

    @Test
    fun liteModeStillAccurate() {
        val base = render(0.0, 0.0)
        for (d in listOf(1.0, -3.0, 2.5, -7.25, 33.5, -61.0, 140.0)) {
            val e = ShiftEstimator().apply { lite = true }
            e.analyze(base, w, h, stride, none, 0)
            e.analyze(render(0.0, d), w, h, stride, none, 0)
            assertTrue("lite confident $d", e.confident)
            assertEquals("lite dy $d", d.toFloat(), e.dy, 0.5f)
        }
        val e = ShiftEstimator().apply { lite = true }
        e.analyze(base, w, h, stride, none, 0); e.analyze(render(-9.0, 0.0), w, h, stride, none, 0)
        assertEquals(-9f, e.dx, 0.5f)
        val t = timeScroll { e.analyze(it, w, h, stride, none, 0) }
        println("V161 BENCH lite shift %.3f ms".format(t))
    }

    private fun max(a: Float, b: Float) = if (a > b) a else b

    /** ms per frame on a scrolling sequence (search always runs) */
    private fun timeScroll(analyze: (ByteArray) -> Unit): Double {
        val a = ByteArray(stride * h); val b = ByteArray(stride * h)
        var off = 0
        fun step(n: Int) { repeat(n) { k -> off = (off + 7) % 380; val f = if (k % 2 == 0) a else b; crop(off, f); analyze(f) } }
        step(400) // JIT warm-up
        // crops cost the same for both; measure them separately and subtract
        val tc0 = System.nanoTime(); repeat(400) { k -> crop((k * 7) % 380, if (k % 2 == 0) a else b) }
        val cropMs = (System.nanoTime() - tc0) / 1e6 / 400
        val t0 = System.nanoTime(); step(400)
        return (System.nanoTime() - t0) / 1e6 / 400 - cropMs
    }

    @Test
    fun shiftCostOldVsNew() {
        val old = ShiftEstimatorV12(); val new = ShiftEstimator()
        val tOld = timeScroll { old.analyze(it, w, h, stride, none, 0) }
        val tNew = timeScroll { new.analyze(it, w, h, stride, none, 0) }
        val tOld2 = timeScroll { old.analyze(it, w, h, stride, none, 0) }
        val tNew2 = timeScroll { new.analyze(it, w, h, stride, none, 0) }
        val o = minOf(tOld, tOld2); val n = minOf(tNew, tNew2)
        // hardware-independent work counts (per frame)
        val oldLines = h * ((w * 4 + 63) / 64) + (h / 8) * ((w * 4 + 63) / 64)
        val newLines = (h / new.rowStep) * 4 * 3 + (h / 16) * ((w * 4 + 63) / 64)
        val oldReads = h * (w / 8) + (h / 8) * w
        val newReads = (h / new.rowStep) * 4 * 12 + (h / 16) * (w / new.colStep)
        println("V161 BENCH shift 590x1280 scrolling: old %.3f ms, new %.3f ms (x%.1f) | cache lines/frame old %d new %d | byte reads old %d new %d | new prof %.3f search %.3f"
            .format(o, n, o / n, oldLines, newLines, oldReads, newReads, new.profMs, new.searchMs))
        assertTrue("new estimator must be clearly cheaper: old $o new $n", n < o * 0.6)
        assertTrue("box budget", n < 0.5)
        assertTrue(newLines * 5 < oldLines)
    }

    @Test
    fun gridDetector() {
        // 3-column grid with 4-px white gutters vs a single full-width image with a flat background
        val grid = ByteArray(stride * h)
        val single = ByteArray(stride * h)
        for (y in 0 until h) for (x in 0 until w) {
            val col = x / (w / 3); val inGutter = x % (w / 3) < 4 && col > 0
            val gv = if (inGutter) 250 else page(x * 1.7 + col * 300.0, y * 1.3)
            val p = y * stride + x * 4
            grid[p + 1] = gv.toByte(); grid[p] = gv.toByte(); grid[p + 2] = gv.toByte()
            // dancer on a green screen: flat background, textured body in the middle
            val body = x in 220..370 && y in 200..1150
            val sv = if (body) page(x.toDouble(), y.toDouble()) else 150
            single[p + 1] = sv.toByte(); single[p] = 60; single[p + 2] = 60
        }
        val e = ShiftEstimator()
        repeat(5) { e.analyze(grid, w, h, stride, none, 0) }
        assertTrue("grid seen (gutters ${e.gutters})", e.gridLike)
        val e2 = ShiftEstimator()
        repeat(5) { e2.analyze(single, w, h, stride, none, 0) }
        assertTrue("full-screen video is not a grid (gutters ${e2.gutters})", !e2.gridLike)
        // switching from grid to video drops the flag after a few frames
        repeat(8) { e.analyze(single, w, h, stride, none, 0) }
        assertTrue(!e.gridLike)
    }

    /**
     * Capture-thread work per frame at 590x1280 (shift + scene guard + skin grid +
     * preprocess + pixel sampling of 4 boxes) and the resulting "post" wait: a detection
     * result waits for the frame in progress, E[wait] ~= busy^2 / (2 x interval).
     */
    @Test
    fun captureThreadBusyAndPostWait() {
        val sg = SceneGuard(); val ag = AppearanceGuard(); val pre = Preprocessor(320)
        val colors = IntArray(64 * 64)
        var id = 0L
        fun rest(f: ByteArray) {
            sg.analyze(f, w, h, stride)
            ag.analyze(f, w, h, stride, none, 0, none, 0, 0.0, 0.0, ++id, id, trigger = false)
            pre.process(f, w, h, stride)
            repeat(4) { k -> Pixelator.sampleBlocks(f, w, h, stride, 100f + k * 50, 200f, 250f + k * 50, 500f, 12, 24, colors) }
        }
        val old = ShiftEstimatorV12(); val new = ShiftEstimator()
        val tOld = timeScroll { old.analyze(it, w, h, stride, none, 0); rest(it) }
        val tNew = timeScroll { new.analyze(it, w, h, stride, none, 0); rest(it) }
        val iv = 1000.0 / 52.5
        println("V161 BENCH capture thread 590x1280 (box): old %.3f ms new %.3f ms | modelled post wait old %.3f new %.3f ms at %.1f ms interval"
            .format(tOld, tNew, tOld * tOld / (2 * iv), tNew * tNew / (2 * iv), iv))
        assertTrue(tNew < tOld)
    }
}

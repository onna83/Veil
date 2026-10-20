package io.github.veilfilter

import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.random.Random

/** box-side timing only; run with VEIL_BENCH=1. Not a device measurement. */
class CostBench {
    @Test
    fun bench() {
        assumeTrue(System.getenv("VEIL_BENCH") == "1")
        for ((w, h) in listOf(443 to 960, 590 to 1280)) {
            val s = w * 4; val f = ByteArray(s * h).also { Random(1).nextBytes(it) }
            val pre = Preprocessor(320); val sh = ShiftEstimator(); val sg = SceneGuard(); val ag = AppearanceGuard()
            val none = FloatArray(0)
            fun t(n: Int, b: () -> Unit): Double { repeat(30) { b() }; val t0 = System.nanoTime(); repeat(n) { b() }; return (System.nanoTime() - t0) / 1e6 / n }
            val full = t(100) { pre.process(f, w, h, s) }
            val side = (w / 2 * 1.15).toInt().coerceAtMost(w)
            val tile = t(100) { pre.process(f, side, side, s, 100 * s) }
            val shift = t(100) { sh.analyze(f, w, h, s, none, 0) }
            val scene = t(100) { sg.analyze(f, w, h, s) }
            var id = 0L
            val app = t(100) { ag.analyze(f, w, h, s, none, 0, none, 0, 0.0, 0.0, ++id, id, trigger = false) }
            println("BENCH ${w}x$h: preprocess full %.2f tile(%d) %.2f | shift %.2f | scene %.2f | appear-grid %.2f ms".format(full, side, tile, shift, scene, app))
        }
    }
}

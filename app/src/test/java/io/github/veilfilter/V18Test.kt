package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** v1.8: portrait input layouts, fold, seam merge, prediction horizon, accessibility hints. */
class V18Test {
    @Test fun portraitFrameFitsPortraitModelWithLittlePadding() {
        // S23 FE capture 590 x 1280 into 224 x 480
        val l = InputLayout.choose(590, 1280, 224, 480)
        assertFalse(l.folded)
        assertEquals(0.375f, l.minScale, 0.002f)
        assertTrue("fill ${l.fill}", l.fill > 0.97f)
        // vs v1.7 square 320: 320 / 1280 = 0.25 -> 1.5x linear resolution
        val sq = InputLayout.choose(590, 1280, 320, 320)
        assertEquals(0.25f, sq.minScale, 0.001f)
        assertTrue(sq.fill < 0.5f)
        assertTrue(l.minScale / sq.minScale > 1.49f)
    }

    @Test fun squareFitIsTheNudeNetLetterbox() {
        val l = InputLayout.fit(540, 1170, 320, 320)
        val p = l.parts.single()
        assertEquals(1170.0 / 320, p.inv, 1e-9)
        assertEquals(0, p.dx); assertEquals(0, p.dy)
        assertEquals(320, p.dh)
        assertTrue(p.dw in 147..149)
    }

    @Test fun landscapeIsFoldedAndMapsBack() {
        val w = 1280; val h = 590
        val l = InputLayout.choose(w, h, 224, 480)
        assertTrue(l.folded)
        val fit = InputLayout.fit(w, h, 224, 480)
        assertTrue(l.minScale >= InputLayout.FOLD_MIN_GAIN * fit.minScale)
        // better than v1.7's square 320 on landscape (320 / 1280)
        assertTrue(l.minScale > 0.25f)
        val (top, bottom) = l.parts
        assertEquals(0, top.sx); assertEquals(w, bottom.sx + bottom.sw)
        assertTrue("halves overlap", top.sx + top.sw > bottom.sx)
        assertTrue(bottom.dy >= top.dh)
        assertTrue(bottom.dy + bottom.dh <= 480 + 1)
        // a box in the right half (source) -> model (bottom band) -> back
        val sx1 = 1000f; val sy1 = 200f; val sx2 = 1100f; val sy2 = 300f
        val inv = bottom.inv.toFloat()
        val m = floatArrayOf((sx1 - bottom.sx) / inv + bottom.dx, (sy1 - bottom.sy) / inv + bottom.dy,
            (sx2 - bottom.sx) / inv + bottom.dx, (sy2 - bottom.sy) / inv + bottom.dy)
        val out = FloatArray(4)
        assertEquals(1, l.toSource(m[0], m[1], m[2], m[3], out))
        assertEquals(sx1, out[0], 0.5f); assertEquals(sy1, out[1], 0.5f)
        assertEquals(sx2, out[2], 0.5f); assertEquals(sy2, out[3], 0.5f)
        // a box in the left half maps to part 0
        assertEquals(0, l.toSource(10f, 10f, 50f, 60f, out))
        assertEquals(10f * top.inv.toFloat(), out[0], 0.5f)
        // padding only (right of a fit's content): clipped to nothing (the pipeline drops it)
        val f2 = InputLayout.fit(100, 1000, 224, 480)
        f2.toSource(200f, 10f, 220f, 20f, out)
        assertTrue(out[2] - out[0] < 1f)
        // a fold-seam gap box (between the bands) belongs to no part
        if (bottom.dy > top.dh + 2) assertEquals(-1, l.toSource(10f, top.dh + 0.5f, 20f, top.dh + 1f, out))
    }

    @Test fun portraitModelNeverFoldsPortraitFramesAndSquareNeverFolds() {
        assertFalse(InputLayout.choose(1280, 590, 320, 320).folded)
        assertFalse(InputLayout.choose(720, 1280, 288, 608).folded)
        assertTrue(InputLayout.choose(1280, 720, 288, 608).folded)
    }

    @Test fun preprocessorPortraitOutputAndPadding() {
        val w = 120; val h = 256; val stride = w * 4
        val src = ByteArray(stride * h) { 200.toByte() }
        val pre = Preprocessor(56, 120, nchw = true)
        pre.process(src, stride, InputLayout.fit(w, h, 56, 120))
        assertEquals(3 * 56 * 120, pre.output.size)
        val l = pre.layout!!
        val p = l.parts.single()
        // content area ~ 200/255, padding 0
        assertEquals(200f / 255f, pre.output[0], 0.01f)
        if (p.dw < 56) assertEquals(0f, pre.output[55], 0f)
        // the legacy square entry point still works
        val sq = Preprocessor(64)
        sq.process(src, w, h, stride)
        assertEquals(3 * 64 * 64, sq.output.size)
        assertEquals(200f / 255f, sq.output[0], 0.01f)
    }

    @Test fun seamMergeJoinsHalvesOfOneSubjectOnly() {
        val x1 = floatArrayOf(600f, 640f, 100f, 610f); val y1 = floatArrayOf(100f, 110f, 100f, 100f)
        val x2 = floatArrayOf(716f, 760f, 200f, 700f); val y2 = floatArrayOf(300f, 310f, 200f, 300f)
        val sc = floatArrayOf(0.5f, 0.7f, 0.6f, 0.4f); val cls = intArrayOf(3, 3, 3, 5)
        val part = intArrayOf(0, 1, 0, 1)
        val n = SeamMerge.merge(x1, y1, x2, y2, sc, cls, part, 4)
        assertEquals(3, n)
        assertEquals(600f, x1[0], 0f); assertEquals(760f, x2[0], 0f); assertEquals(0.7f, sc[0], 0f)
        // the other class at the same place is kept apart
        assertEquals(5, cls[2])
    }

    @Test fun decodeXYUsesSeparateAxisScales() {
        val n = ModelSize.anchorsFor(224, 480)
        assertEquals(ModelSize.P224.anchors, n)
        val out = FloatArray((4 + Labels.NUM_CLASSES) * n)
        // one anchor: centre (0.5, 0.25), size (0.25, 0.125) normalised
        val i = 7
        out[0 * n + i] = 0.5f; out[1 * n + i] = 0.25f; out[2 * n + i] = 0.25f; out[3 * n + i] = 0.125f
        out[(4 + 3) * n + i] = 0.9f
        val d = YoloDecoder(numAnchors = n)
        val k = d.decodeXY(out, 224f, 480f, 0.3f, BooleanArray(Labels.NUM_CLASSES) { true }, true, 0.45f, 224f, 480f)
        assertEquals(1, k)
        assertEquals(112f - 28f, d.x1[0], 0.01f); assertEquals(120f - 30f, d.y1[0], 0.01f)
        assertEquals(112f + 28f, d.x2[0], 0.01f); assertEquals(120f + 30f, d.y2[0], 0.01f)
    }

    @Test fun rectTilesFollowTheModelAspect() {
        val t = TileScheduler()
        t.layout(590, 1280, 480f / 224f)
        assertTrue(t.tiles.size >= 2)
        for (x in t.tiles) {
            assertEquals(480f / 224f, x.h.toFloat() / x.w, 0.05f)
            assertTrue(x.x >= 0 && x.y >= 0 && x.x + x.w <= 590 && x.y + x.h <= 1280)
        }
        assertEquals(590, t.tiles.maxOf { it.x + it.w })
        assertEquals(1280, t.tiles.maxOf { it.y + it.h })
        // square models keep the v1.7 tiles
        val s = TileScheduler(); s.layout(590, 1280)
        assertTrue(s.tiles.all { it.w == it.h })
    }

    @Test fun predictionLeadsExactlyThenSaturates() {
        val knee = 60f; val cap = 200f
        assertEquals(0f, Prediction.effectiveDt(-5f, knee, cap), 0f)
        assertEquals(30f, Prediction.effectiveDt(30f, knee, cap), 0f)
        assertEquals(60f, Prediction.effectiveDt(60f, knee, cap), 0f)
        var prev = 0f
        for (raw in 0..2000 step 10) {
            val v = Prediction.effectiveDt(raw.toFloat(), knee, cap)
            assertTrue(v >= prev - 1e-4f); assertTrue(v <= cap + 1e-3f); prev = v
        }
        assertTrue(Prediction.effectiveDt(2000f, knee, cap) > 199f)
        // no scroll hint: tail bounded to knee + 40 ms; app scrolling: full cap
        assertEquals(100f, Prediction.capFor(200f, 60f, false), 0f)
        assertEquals(200f, Prediction.capFor(200f, 60f, true), 0f)
        assertEquals(180f, Prediction.capFor(180f, 170f, false), 0f)
        val k = Prediction.knee(8f, 3f, 2f, 40f, 16.7f, 16.7f, 200f)
        assertEquals(8f + 3f + 2f + 40f + 25.05f, k, 0.01f)
        assertEquals(200f, Prediction.knee(100f, 100f, 0f, 0f, 16f, 16f, 200f), 0f)
        assertEquals(33.4f, Prediction.knee(0f, 0f, 0f, 0f, 0f, 16.7f, 200f), 0.01f)
        // per-model caps in the 180-230 ms range
        for (m in ModelSize.entries) assertTrue(m.id, m.predictCapMs in 180L..230L)
    }

    @Test fun hintStateScrollAndContentChanges() {
        val s = HintState()
        assertFalse(s.scrolling(1000))
        s.active = true
        s.onScroll(1000)
        assertTrue(s.scrolling(1100)); assertTrue(s.scrolling(990)); assertFalse(s.scrolling(1300))
        assertFalse(s.onWindowState("a", "A", 0)) // the first screen is not a change
        assertEquals(0, s.contentSeq)
        assertFalse(s.onWindowState("a", "A", 10))
        assertTrue(s.onWindowState("b", "B", 20))
        assertEquals(1, s.contentSeq); assertEquals(20L, s.lastContentMs)
        assertFalse(s.onForegroundWindow(5, 30))
        assertTrue(s.onForegroundWindow(6, 40))
        assertEquals(2, s.contentSeq)
        s.active = false
        assertFalse(s.scrolling(1100))
    }

    @Test fun portraitCatalog() {
        assertEquals(ModelSize.P224, ModelSize.DEFAULT)
        assertEquals(Source.BUNDLED, ModelSize.P224.source)
        for (m in listOf(ModelSize.P224, ModelSize.P256, ModelSize.P288, ModelSize.P320)) {
            assertTrue(m.portrait)
            // ~1:2.17 phone screens: input aspect close to the capture aspect
            assertTrue(m.id, abs(m.h.toFloat() / m.w - 2.17f) < 0.15f)
        }
        assertEquals("https://github.com/onna83/Veil/releases/download/v1.8", ModelSize.DEFAULT_BASE_URL)
    }
}

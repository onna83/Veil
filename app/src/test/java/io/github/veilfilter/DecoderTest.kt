package io.github.veilfilter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.max

/** Decode + NMS against NudeNet's reference post-processing (tools/make_reference.py). */
class DecoderTest {

    @Test
    fun labelsMatchNudeNetSource() {
        assertArrayEquals(TestData.lines("labels.txt").toTypedArray(), Labels.NAMES)
        assertEquals(18, Labels.NAMES.size)
    }

    private class Ref(val cls: Int, val score: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float)

    private fun load(case: Int): Triple<FloatArray, Map<String, List<Ref>>, Pair<Int, Int>> {
        val raw = TestData.floats("decode_case$case.bin")
        val map = HashMap<String, MutableList<Ref>>()
        var fw = 0; var fh = 0
        for (l in TestData.lines("decode_case$case.txt")) {
            val p = l.split(" ")
            when (p[0]) {
                "frame" -> { fw = p[1].toInt(); fh = p[2].toInt() }
                "nudenet" -> {
                    // NudeNet: int x, y, w, h (top-left + size)
                    val x = p[3].toFloat(); val y = p[4].toFloat()
                    map.getOrPut("nudenet") { ArrayList() }.add(
                        Ref(p[1].toInt(), p[2].toFloat(), x, y, x + p[5].toFloat(), y + p[6].toFloat()),
                    )
                }
                else -> map.getOrPut(p[0]) { ArrayList() }.add(
                    Ref(p[1].toInt(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat(), p[6].toFloat()),
                )
            }
        }
        return Triple(raw, map, fw to fh)
    }

    private fun check(d: YoloDecoder, n: Int, refs: List<Ref>, tol: Float) {
        assertEquals("detection count", refs.size, n)
        // both are sorted by descending score
        for (i in 0 until n) {
            val r = refs[i]
            assertEquals("class #$i", r.cls, d.cls[i])
            assertEquals("score #$i", r.score, d.score[i], 1e-4f)
            assertTrue("x1 #$i ${d.x1[i]} vs ${r.x1}", abs(d.x1[i] - r.x1) <= tol)
            assertTrue("y1 #$i ${d.y1[i]} vs ${r.y1}", abs(d.y1[i] - r.y1) <= tol)
            assertTrue("x2 #$i ${d.x2[i]} vs ${r.x2}", abs(d.x2[i] - r.x2) <= tol)
            assertTrue("y2 #$i ${d.y2[i]} vs ${r.y2}", abs(d.y2[i] - r.y2) <= tol)
        }
    }

    @Test
    fun matchesNudeNetReferencePostprocess() {
        val d = YoloDecoder()
        for (c in 0 until 4) {
            val (raw, refs, size) = load(c)
            val side = max(size.first, size.second).toFloat()
            // NudeNet: argmax over all 18 classes, NMSBoxes(score 0.25, IoU 0.45), class-agnostic
            val n = d.decode(raw, 1f, side / 320f, 0.25f, Labels.allMask(), false, 0.45f,
                size.first.toFloat(), size.second.toFloat())
            // NudeNet truncates x, y, w, h to int separately -> up to 2 px on x2/y2
            check(d, n, refs.getValue("nudenet"), 2.01f)
            // and exactly against the float reference with the same semantics
            check(d, n, refs.getValue("agnostic"), 0.01f)
        }
    }

    @Test
    fun censoredClassAwareMatchesReference() {
        val d = YoloDecoder()
        val mask = Labels.mask(Labels.CENSORED_NAMES)
        for (c in 0 until 4) {
            val (raw, refs, size) = load(c)
            val side = max(size.first, size.second).toFloat()
            val n = d.decode(raw, 1f, side / 320f, 0.2f, mask, true, 0.45f,
                size.first.toFloat(), size.second.toFloat())
            check(d, n, refs["censored"] ?: emptyList(), 0.01f)
            for (i in 0 until n) assertTrue(Labels.NAMES[d.cls[i]] in Labels.CENSORED_NAMES)
        }
    }

    @Test
    fun normalisedOutputsDecodeTheSame() {
        // LiteRT export emits boxes / 320: coordScale = 320 must give identical results.
        val (raw, _, size) = load(0)
        val norm = raw.copyOf()
        for (i in 0 until 4 * 2100) norm[i] = raw[i] / 320f
        val a = YoloDecoder(); val b = YoloDecoder()
        val side = max(size.first, size.second) / 320f
        val na = a.decode(raw, 1f, side, 0.2f, Labels.allMask(), true, 0.45f, 540f, 1200f)
        val nb = b.decode(norm, 320f, side, 0.2f, Labels.allMask(), true, 0.45f, 540f, 1200f)
        assertEquals(na, nb)
        for (i in 0 until na) {
            assertEquals(a.x1[i], b.x1[i], 1e-3f); assertEquals(a.y2[i], b.y2[i], 1e-3f)
        }
    }

    @Test
    fun classAwareKeepsOverlappingDifferentClasses() {
        val n = 2100
        val raw = FloatArray(22 * n)
        fun put(i: Int, cx: Float, cy: Float, w: Float, h: Float, cls: Int, s: Float) {
            raw[i] = cx; raw[n + i] = cy; raw[2 * n + i] = w; raw[3 * n + i] = h; raw[(4 + cls) * n + i] = s
        }
        put(0, 100f, 100f, 50f, 50f, 3, 0.9f)
        put(1, 102f, 101f, 50f, 50f, 3, 0.8f) // same class, suppressed
        put(2, 101f, 100f, 50f, 50f, 4, 0.7f) // other class, kept when class-aware
        val d = YoloDecoder()
        assertEquals(2, d.decode(raw, 1f, 1f, 0.3f, Labels.allMask(), true, 0.45f, 320f, 320f))
        assertEquals(1, d.decode(raw, 1f, 1f, 0.3f, Labels.allMask(), false, 0.45f, 320f, 320f))
    }

    @Test
    fun realLiteRtOutputMatchesNudeNetPostprocessOfOnnx() {
        // Raw output of the bundled .tflite on an SFW photo vs NudeNet-style
        // decoding of the original 320n.onnx output (tools/make_tflite_reference.py).
        val raw = TestData.floats("tflite_bus_output.bin")
        val lines = TestData.lines("tflite_bus_expected.txt")
        val (fw, fh) = lines[0].split(" ").let { it[1].toInt() to it[2].toInt() }
        val side = max(fw, fh).toFloat()
        val d = YoloDecoder()
        val n = d.decode(raw, 320f, side / 320f, 0.05f, Labels.allMask(), false, 0.45f, fw.toFloat(), fh.toFloat())
        val refs = lines.drop(1).map { l ->
            val p = l.split(" ")
            Ref(p[0].toInt(), p[1].toFloat(), p[2].toFloat(), p[3].toFloat(), p[4].toFloat(), p[5].toFloat())
        }
        assertTrue(refs.isNotEmpty())
        check(d, n, refs, 0.05f)
    }
}

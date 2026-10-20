package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

class V15Test {
    private fun Tracker.det(x1: Float, y1: Float, x2: Float, y2: Float, s: Float, cls: Int, now: Long) =
        update(1, floatArrayOf(x1), floatArrayOf(y1), floatArrayOf(x2), floatArrayOf(y2), floatArrayOf(s), intArrayOf(cls), now, false)

    private fun sd(v: List<Float>): Float { val m = v.average().toFloat(); return sqrt(v.map { (it - m) * (it - m) }.average().toFloat()) }

    // ---------------- 1. jitter ----------------
    @Test
    fun stillObjectWithNoisyDetectionsIsStableAndAlwaysCovered() {
        val rnd = Random(11)
        fun run(tr: Tracker): Pair<Float, Boolean> {
            val x1s = ArrayList<Float>(); val ws = ArrayList<Float>(); var covered = true
            for (k in 0 until 120) {
                val n = { (rnd.nextFloat() - 0.5f) * 8f } // +-4 px edge noise
                val a = 200f + n(); val b = 300f + n(); val c = 320f + n(); val d = 450f + n()
                tr.det(a, b, c, d, 0.7f, 3, k * 33L)
                val t = tr.tracks.single()
                if (t.x1 > a + 0.01f || t.y1 > b + 0.01f || t.x2 < c - 0.01f || t.y2 < d - 0.01f) covered = false
                if (k > 10) { x1s += t.x1; ws += t.x2 - t.x1 }
            }
            return (sd(x1s) + sd(ws)) to covered
        }
        val (newJ, newCov) = run(Tracker())
        // v1.4 behaviour (no deadband, no shrink hysteresis) for comparison
        val (oldJ, _) = run(Tracker(deadbandPx = 0f, deadbandFrac = 0f, fastSmoothing = 0.6f, stillShrink = 0.5f))
        assertTrue("always contains the measurement", newCov)
        println("jitter new $newJ vs old $oldJ")
        assertTrue("jitter new $newJ vs old $oldJ", newJ < 0.6f * oldJ)
    }

    @Test
    fun fastMotionIsFollowedWithoutLag() {
        val tr = Tracker()
        var x = 100f
        for (k in 0 until 20) {
            x += 30f // ~1 px/ms at 33 ms
            tr.det(x, 300f, x + 100f, 420f, 0.7f, 3, k * 33L)
            val t = tr.tracks.single()
            assertTrue(t.x1 <= x && t.x2 >= x + 100f)
            if (k > 3) assertTrue("box not much bigger than the object: ${t.x2 - t.x1}", t.x2 - t.x1 < 100f + 30f)
        }
        assertTrue("velocity tracks the motion: ${tr.tracks.single().vx}", tr.tracks.single().vx > 0.6f)
    }

    @Test
    fun firstAppearanceIsImmediate() {
        val tr = Tracker()
        tr.det(10f, 20f, 110f, 220f, 0.5f, 3, 0)
        val t = tr.tracks.single()
        assertEquals(10f, t.x1, 0f); assertEquals(220f, t.y2, 0f)
    }

    @Test
    fun contentAnchoredGridMovesWithTheScroll() {
        val b = 16f
        // the same content edge at two scroll offsets snaps to the same content-relative line
        for (cum in listOf(0.0, 7.0, -23.5, 1000.25)) {
            val o = (cum % b).toFloat()
            val lo = Pixelator.floorTo(105f + cum.toFloat(), o, b); val hi = Pixelator.ceilTo(170f + cum.toFloat(), o, b)
            assertEquals(96f, lo - cum.toFloat(), 1e-3f)
            assertEquals(176f, hi - cum.toFloat(), 1e-3f)
        }
        assertEquals(5, Pixelator.blocksOf(80f, 16f))
        assertEquals(1, Pixelator.blocksOf(3f, 16f))
        assertEquals(Pixelator.MAX_BLOCKS, Pixelator.blocksOf(10_000f, 4f))
        val o = IntArray(1); Pixelator.blend(intArrayOf(0xFF000000.toInt()), intArrayOf(0xFFFEFEFE.toInt()), o)
        assertEquals(0xFF7F7F7F.toInt(), o[0])
    }

    @Test
    fun pixelSizeSetting() {
        // 10 dp at density 2.625 on a 1080 px wide screen captured at 590 px
        assertEquals(10 * 2.625f * 590 / 1080, AppSettings.blockCapturePx(10, 2.625f, 590, 1080), 1e-3f)
        assertTrue(AppSettings.PIXEL_DEFAULT_DP in AppSettings.PIXEL_MIN_DP..AppSettings.PIXEL_MAX_DP)
    }

    // ---------------- 2. faces / male chest ----------------
    @Test
    fun exposedDoesNotIncludeMaleChestAndItIsSeparate() {
        val i = Labels.index("MALE_BREAST_EXPOSED")
        assertFalse(CensorConfig().mask[i])
        assertTrue(CensorConfig(maleChest = true).mask[i])
        assertEquals("exposed+male chest", CensorConfig(maleChest = true).label())
        assertFalse(AppSettings.censorFrom { _, d -> d }.maleChest)
    }

    @Test
    fun disabledFacesVetoAnchorsTheyDominate() {
        val n = 3; val out = FloatArray((4 + Labels.NUM_CLASSES) * n)
        for (a in 0 until n) { out[a] = 50f + 100f * a; out[n + a] = 50f; out[2 * n + a] = 40f; out[3 * n + a] = 40f }
        val face = Labels.index("FACE_MALE"); val butt = Labels.index("BUTTOCKS_EXPOSED")
        // anchor 0: male face 0.8 with a spurious 0.25 buttocks score -> must NOT come out as buttocks
        out[(4 + face) * n + 0] = 0.8f; out[(4 + butt) * n + 0] = 0.25f
        // anchor 1: real buttocks 0.5 next to a weaker face score 0.4 -> kept (safety)
        out[(4 + face) * n + 1] = 0.4f; out[(4 + butt) * n + 1] = 0.5f
        val dec = YoloDecoder(numAnchors = n)
        val def = CensorConfig()
        val k = dec.decode(out, 1f, 1f, def.decodeThreshold, def.mask, true, 0.45f, 640f, 640f, veto = def.veto)
        assertEquals(1, k); assertEquals(butt, dec.cls[0]); assertEquals(0.5f, dec.score[0], 0f)
        // v1.4 decode (no veto) would have emitted the face as buttocks
        assertEquals(2, dec.decode(out, 1f, 1f, def.decodeThreshold, def.mask, true, 0.45f, 640f, 640f))
        // faces enabled: no veto, anchor 0 is a face
        val fc = CensorConfig(faces = true)
        assertFalse(fc.veto[face])
        assertEquals(2, dec.decode(out, 1f, 1f, fc.decodeThreshold, fc.mask, true, 0.45f, 640f, 640f, veto = fc.veto))
        // male faces off, female on: male face still vetoes
        assertTrue(CensorConfig(faces = true, maleFaces = false).veto[face])
    }

    // ---------------- 4. models ----------------
    @Test
    fun modelOptions() {
        assertEquals(ModelSize.S320, ModelSize.byId("320n_320"))
        assertNull(ModelSize.byId("nope"))
        assertEquals(ModelSize.entries.size, ModelSize.entries.map { it.id }.toSet().size)
        assertEquals(1280f, ModelSize.captureLong(ModelSize.S320, 4f), 0f)
        assertEquals(960f, ModelSize.captureLong(ModelSize.S320, 3f), 0f)
        assertEquals(1280f, ModelSize.captureLong(ModelSize.N640, 4f), 0f)
        assertEquals(8400, ModelSize.N640.anchors); assertEquals(8400, ModelSize.M640.anchors)
        assertTrue(ModelSize.M640.external); assertFalse(ModelSize.M640.tiles); assertFalse(ModelSize.N640.external)
    }

    private fun zipOf(name: String, data: ByteArray): ByteArray {
        val bo = ByteArrayOutputStream()
        ZipOutputStream(bo).use { it.putNextEntry(ZipEntry(name)); it.write(data); it.closeEntry() }
        return bo.toByteArray()
    }

    @Test
    fun importerJoinsPartsInOrderAndVerifiesChecksum() {
        val data = ByteArray(300_000).also { Random(3).nextBytes(it) }
        val sha = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
        val parts = listOf(data.copyOfRange(0, 100_000), data.copyOfRange(100_000, 200_000), data.copyOfRange(200_000, 300_000))
        // picked in random order, as zips
        val src = listOf(2, 0, 1).map { i ->
            val z = zipOf("p$i", parts[i]); ModelImporter.Source("veil-model-640m.part${i + 1}of3.zip") { ByteArrayInputStream(z) }
        }
        val dir = kotlin.io.path.createTempDirectory("veil").toFile(); val dst = File(dir, "m.tflite")
        assertNull(ModelImporter.import(src, dst, sha))
        assertTrue(dst.readBytes().contentEquals(data))
        // a missing part -> rejected, previous file untouched
        val err = ModelImporter.import(src.take(2), dst, sha)
        assertNotNull(err); assertTrue(dst.readBytes().contentEquals(data))
        // a plain .tflite works too
        val dst2 = File(dir, "m2.tflite")
        assertNull(ModelImporter.import(listOf(ModelImporter.Source("x.tflite") { ByteArrayInputStream(data) }), dst2, sha))
        dir.deleteRecursively()
    }
}

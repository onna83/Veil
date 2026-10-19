package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AppearanceGuardTest {
    private val w = 444
    private val h = 960
    private val stride = w * 4 + 16
    private val none = FloatArray(0)

    /** Monk Skin Tone scale (10 tones, light to very dark), SFW colour swatches. */
    private val mst = intArrayOf(
        0xf6ede4, 0xf3e7db, 0xf7ead0, 0xeadaba, 0xd7bd96,
        0xa07e56, 0x825c43, 0x604134, 0x3a312a, 0x292420,
    )
    private val nonSkin = mapOf(
        "white" to 0xffffff, "paper" to 0xfafafa, "light grey" to 0xc8c8c8, "grey" to 0x808080,
        "black" to 0x101010, "sky" to 0x87ceeb, "navy" to 0x1f2a44, "grass" to 0x3c8d2f,
        "ui blue" to 0x1a73e8, "red" to 0xff0000, "yellow" to 0xffd400, "purple" to 0x7b3fa0,
        "teal" to 0x008080, "orange" to 0xff8c00,
    )

    private fun skin(c: Int, f: Float = 1f) = AppearanceGuard.isSkin(
        (((c shr 16) and 0xFF) * f).toInt().coerceIn(0, 255),
        (((c shr 8) and 0xFF) * f).toInt().coerceIn(0, 255),
        ((c and 0xFF) * f).toInt().coerceIn(0, 255),
    )

    @Test
    fun allMonkSkinTonesAreSkinIncludingShaded() {
        for ((i, c) in mst.withIndex()) {
            assertTrue("MST ${i + 1}", skin(c))
            assertTrue("MST ${i + 1} shaded", skin(c, 0.75f))
        }
        // moderately deep shadow on all but the darkest tone
        for (i in 0 until 9) assertTrue("MST ${i + 1} deep shadow", skin(mst[i], 0.55f))
    }

    @Test
    fun commonNonSkinColoursAreNot() {
        for ((name, c) in nonSkin) assertFalse(name, skin(c))
    }

    // ---- frames ----
    private fun background(seed: Int = 1): ByteArray {
        val f = ByteArray(stride * h)
        val rnd = Random(seed)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * stride + x * 4
            // bluish-grey UI with stripes (not skin)
            val v = 90 + ((y / 9) % 3) * 20 + rnd.nextInt(8)
            f[p] = (v * 0.8f).toInt().toByte(); f[p + 1] = v.toByte(); f[p + 2] = (v + 40).toByte(); f[p + 3] = -1
        }
        return f
    }

    private fun paint(f: ByteArray, x1: Int, y1: Int, x2: Int, y2: Int, c: Int, seed: Int = 7) {
        val rnd = Random(seed)
        for (y in maxOf(0, y1) until minOf(h, y2)) for (x in maxOf(0, x1) until minOf(w, x2)) {
            val p = y * stride + x * 4
            val n = rnd.nextInt(-6, 7)
            f[p] = (((c shr 16) and 0xFF) + n).coerceIn(0, 255).toByte()
            f[p + 1] = (((c shr 8) and 0xFF) + n).coerceIn(0, 255).toByte()
            f[p + 2] = ((c and 0xFF) + n).coerceIn(0, 255).toByte()
        }
    }

    /** a page (background + skin patch) scrolled by dy */
    private fun page(dy: Int, patchY: Int = 300, color: Int = mst[6]): ByteArray {
        val f = background()
        paint(f, 150, patchY + dy, 270, patchY + 140 + dy, color)
        return f
    }

    private fun AppearanceGuard.run(f: ByteArray, frame: Long, now: Long, cumY: Double = 0.0, excl: FloatArray = none, nExcl: Int = 0) =
        analyze(f, w, h, stride, excl, nExcl, none, 0, 0.0, cumY, frame, now)

    @Test
    fun newSkinIsCoveredImmediatelyAndReleasedByDetector() {
        for (tone in mst.indices) {
            val g = AppearanceGuard()
            val bg = background()
            g.run(bg, 1, 0)
            g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 10)
            g.run(bg, 2, 16)
            assertEquals("static background", 0, g.boxes.size)
            val f = page(0, color = mst[tone])
            g.run(f, 3, 32)
            assertEquals("MST ${tone + 1}: one box", 1, g.boxes.size)
            val b = g.boxes[0]
            assertTrue("covers patch MST ${tone + 1}", b.x1 <= 150 && b.x2 >= 270 && b.y1 <= 300 && b.y2 >= 440)
            val snap3 = g.snapshotInto(FloatArray(0))
            // still visible while the detector has only seen older frames
            g.onDetected(FloatArray(snap3.size), 0.0, 0.0, 2, 48)
            g.run(f, 4, 48)
            assertEquals(1, g.boxes.size)
            // detector result for frame 3: released (after the minimum on-time)
            g.onDetected(snap3, 0.0, 0.0, 3, 200)
            assertEquals(0, g.boxes.size)
            g.run(f, 5, 216)
            assertEquals("no re-trigger on the same, checked content", 0, g.boxes.size)
        }
    }

    @Test
    fun scrollOfCheckedContentDoesNotTriggerButUncompensatedWould() {
        val g = AppearanceGuard()
        val f0 = page(0)
        g.run(f0, 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 100)
        g.boxes.clear()
        val f1 = page(-60)
        g.run(f1, 2, 116, cumY = -60.0)
        assertEquals("compensated scroll", 0, g.boxes.size)

        val g2 = AppearanceGuard()
        g2.run(f0, 1, 0)
        g2.onDetected(g2.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 100)
        g2.boxes.clear()
        g2.run(f1, 2, 116, cumY = 0.0) // pretend we don't know about the scroll
        assertTrue("without compensation it would fire", g2.boxes.size >= 1)
    }

    @Test
    fun movingEdgeSliverDoesNotTrigger() {
        val g = AppearanceGuard()
        g.run(page(0), 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 100)
        g.boxes.clear()
        // the object itself moves 8 px (e.g. a person in a video), no scroll
        g.run(page(8), 2, 116)
        assertEquals(0, g.boxes.size)
    }

    @Test
    fun ownCensorRegionsAreIgnored() {
        val g = AppearanceGuard()
        val bg = background()
        g.run(bg, 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 100)
        // our own (skin-coloured) pixelation appears in full-screen capture, masked
        val excl = floatArrayOf(140f, 290f, 280f, 450f)
        g.run(page(0), 2, 116, excl = excl, nExcl = 1)
        assertEquals(0, g.boxes.size)
    }

    @Test
    fun nonSkinChangeDoesNotTrigger() {
        val g = AppearanceGuard()
        val bg = background()
        g.run(bg, 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 100)
        val f = background()
        paint(f, 50, 100, 400, 700, 0x1a73e8) // big blue thing appears
        g.run(f, 2, 116)
        assertEquals(0, g.boxes.size)
    }

    @Test
    fun boxesFollowScroll() {
        val g = AppearanceGuard()
        g.run(background(), 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 100)
        g.run(page(0), 2, 116)
        val y = g.boxes.single().y1
        g.applyShift(0f, -25f)
        assertEquals(y - 25f, g.boxes.single().y1, 0.01f)
    }

    @Test
    fun runtimeIsSmall() {
        val g = AppearanceGuard()
        val f = page(0)
        repeat(50) { g.run(f, it.toLong(), it * 16L) }
        val t0 = System.nanoTime()
        repeat(200) { g.run(f, 100L + it, 2000L + it * 16) }
        val ms = (System.nanoTime() - t0) / 1e6 / 200
        println("appearance guard: %.3f ms/frame".format(ms))
        assertTrue("$ms ms", ms < 2.0)
    }
}

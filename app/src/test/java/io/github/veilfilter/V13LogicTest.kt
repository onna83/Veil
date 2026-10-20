package io.github.veilfilter

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class V13LogicTest {
    private fun one(x1: Float, y1: Float, x2: Float, y2: Float, s: Float) =
        arrayOf(floatArrayOf(x1), floatArrayOf(y1), floatArrayOf(x2), floatArrayOf(y2), floatArrayOf(s))

    private fun Tracker.feed(d: Array<FloatArray>, now: Long, clipped: BooleanArray? = null, keepMs: Long = 0) =
        update(1, d[0], d[1], d[2], d[3], d[4], intArrayOf(3), now, false, clipped, keepMs)

    private fun Tracker.none(now: Long) =
        update(0, FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), IntArray(0), now, false)

    /** a track moving right at [v] px/ms, observed every 20 ms until t=100 */
    private fun moving(v: Float): Tracker {
        val tr = Tracker()
        for (k in 0..5) { val t = k * 20L; val x = 100f + v * t; tr.feed(one(x, 300f, x + 60f, 380f, 0.6f), t) }
        return tr
    }

    @Test
    fun fastTracksSurviveBlurredMissesSlowOnesDont() {
        val fast = moving(0.6f); val slow = moving(0.02f)
        assertTrue(fast.tracks.single().speed() > fast.fastSpeed)
        for (t in 120L..700L step 20) { fast.none(t); slow.none(t) }
        assertEquals("fast kept through ~600 ms of misses", 1, fast.tracks.size)
        assertEquals("slow dropped after 400 ms", 0, slow.tracks.size)
    }

    @Test
    fun weakDetectionsRefreshOnlyFastTracks() {
        val fast = moving(0.6f); val slow = moving(0.02f)
        val fx = fast.tracks.single().x1 + 0.6f * 20; val sx = slow.tracks.single().x1
        fast.feed(one(fx, 300f, fx + 60f, 380f, 0.14f), 120)
        slow.feed(one(sx, 300f, sx + 60f, 380f, 0.14f), 120)
        assertEquals(120L, fast.tracks.single().lastSeen)
        assertEquals(100L, slow.tracks.single().lastSeen)
    }

    @Test
    fun fasterVelocityReaction() {
        val tr = Tracker()
        tr.feed(one(100f, 300f, 160f, 380f, 0.6f), 0)
        tr.feed(one(100f, 300f, 160f, 380f, 0.6f), 20)
        // sudden fast motion: 1 px/ms
        tr.feed(one(120f, 300f, 180f, 380f, 0.6f), 40)
        assertTrue("vx ${tr.tracks.single().vx}", tr.tracks.single().vx >= 0.75f)
    }

    @Test
    fun clippedTileDetectionNeverShrinksTrackAndTileKeepAlive() {
        val tr = Tracker()
        tr.feed(one(100f, 300f, 200f, 420f, 0.6f), 0, keepMs = 1200)
        // tile edge cuts the object at y=360
        tr.feed(one(100f, 300f, 200f, 360f, 0.6f), 20, booleanArrayOf(true), keepMs = 1200)
        val t = tr.tracks.single()
        assertTrue("bottom kept: ${t.y2}", t.y2 >= 419f)
        for (k in 40L..1100L step 40) tr.none(k)
        assertEquals("tile track lives until the tile is revisited", 1, tr.tracks.size)
        tr.none(1300)
        assertEquals(0, tr.tracks.size)
    }

    @Test
    fun regionPreprocessEqualsCroppedCopy() {
        val w = 120; val h = 90; val stride = w * 4 + 8
        val rnd = Random(5)
        val f = ByteArray(stride * h) { rnd.nextInt(256).toByte() }
        val x0 = 37; val y0 = 21; val side = 50
        val crop = ByteArray(side * 4 * side)
        for (y in 0 until side) System.arraycopy(f, (y0 + y) * stride + x0 * 4, crop, y * side * 4, side * 4)
        val a = Preprocessor(32); a.process(f, side, side, stride, y0 * stride + x0 * 4)
        val b = Preprocessor(32); b.process(crop, side, side, side * 4)
        assertArrayEquals(b.output, a.output, 0f)
    }

    // ---- appearance guard: scroll suppression + tile release ----
    private val gw = 240; private val gh = 480; private val gs = gw * 4
    private fun frame(skinAt: IntArray?): ByteArray {
        val f = ByteArray(gs * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val p = y * gs + x * 4
            val skin = skinAt != null && x in skinAt[0] until skinAt[2] && y in skinAt[1] until skinAt[3]
            if (skin) { f[p] = 0x82.toByte(); f[p + 1] = 0x5c; f[p + 2] = 0x43 } else { f[p] = 60; f[p + 1] = 80; f[p + 2] = 120 }
        }
        return f
    }
    private val none = FloatArray(0)

    @Test
    fun appearanceGuardDoesNothingWhenTriggerIsOff() {
        val g = AppearanceGuard()
        g.analyze(frame(null), gw, gh, gs, none, 0, none, 0, 0.0, 0.0, 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 10)
        g.analyze(frame(intArrayOf(50, 100, 150, 200)), gw, gh, gs, none, 0, none, 0, 0.0, 0.0, 2, 20, trigger = false)
        assertEquals(0, g.boxes.size)
        g.analyze(frame(intArrayOf(50, 100, 150, 200)), gw, gh, gs, none, 0, none, 0, 0.0, 0.0, 3, 40, trigger = true)
        assertEquals(1, g.boxes.size)
    }

    @Test
    fun tilePassReleasesOnlyBoxesItCovered() {
        val g = AppearanceGuard()
        g.analyze(frame(null), gw, gh, gs, none, 0, none, 0, 0.0, 0.0, 1, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 1, 10)
        val f = frame(intArrayOf(40, 300, 120, 380))
        g.analyze(f, gw, gh, gs, none, 0, none, 0, 0.0, 0.0, 2, 20)
        assertEquals(1, g.boxes.size)
        val snap = g.snapshotInto(FloatArray(0))
        // a tile pass over the TOP half: does not cover the box -> stays
        g.onDetected(snap, 0.0, 0.0, 2, 200, 0f, 0f, 240f, 240f)
        assertEquals(1, g.boxes.size)
        // tile over the bottom half: released
        g.onDetected(snap, 0.0, 0.0, 2, 220, 0f, 240f, 240f, 480f)
        assertEquals(0, g.boxes.size)
    }
}

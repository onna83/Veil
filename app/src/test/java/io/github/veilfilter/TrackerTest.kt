package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerTest {
    private val x1 = FloatArray(4); private val y1 = FloatArray(4)
    private val x2 = FloatArray(4); private val y2 = FloatArray(4)
    private val s = FloatArray(4); private val c = IntArray(4)

    private fun det(i: Int, l: Float, t: Float, r: Float, b: Float, score: Float = 0.8f) {
        x1[i] = l; y1[i] = t; x2[i] = r; y2[i] = b; s[i] = score; c[i] = 3
    }

    @Test
    fun showsOnFirstHitAndHidesAfterTimeout() {
        val tr = Tracker()
        det(0, 100f, 100f, 150f, 150f)
        tr.update(1, x1, y1, x2, y2, s, c, 1000, false)
        assertEquals(1, tr.tracks.size)
        tr.update(0, x1, y1, x2, y2, s, c, 1300, false)
        assertEquals("kept within 400 ms", 1, tr.tracks.size)
        tr.update(0, x1, y1, x2, y2, s, c, 1401, false)
        assertEquals("dropped after 400 ms", 0, tr.tracks.size)
    }

    @Test
    fun lowScoreOnlyRefreshesExistingTracks() {
        val tr = Tracker()
        det(0, 10f, 10f, 50f, 50f, 0.25f)
        tr.update(1, x1, y1, x2, y2, s, c, 0, false)
        assertEquals("0.25 does not start a track", 0, tr.tracks.size)
        det(0, 10f, 10f, 50f, 50f, 0.35f)
        tr.update(1, x1, y1, x2, y2, s, c, 0, false)
        assertEquals(1, tr.tracks.size)
        det(0, 12f, 10f, 52f, 50f, 0.22f)
        tr.update(1, x1, y1, x2, y2, s, c, 350, false)
        tr.update(0, x1, y1, x2, y2, s, c, 700, false)
        assertEquals("refreshed at 350 ms by a 0.22 detection", 1, tr.tracks.size)
    }

    @Test
    fun velocityLeadsMotionAndBoxCoversMeasurement() {
        val tr = Tracker()
        for (k in 0 until 6) {
            val x = 100f + k * 10f // 10 px per 33 ms
            det(0, x, 100f, x + 40f, 140f)
            tr.update(1, x1, y1, x2, y2, s, c, k * 33L, false)
            val t = tr.tracks.single()
            assertTrue("covers measurement", t.x1 <= x && t.x2 >= x + 40f)
        }
        val t = tr.tracks.single()
        assertTrue("velocity estimated ${t.vx}", t.vx > 0.15f)
        val now = 5 * 33L
        assertTrue("prediction moves ahead", t.predictX1(now + 33, 200) > t.predictX1(now, 200) + 4f)
    }

    @Test
    fun occludedTracksSurviveUntilSceneCutOrTimeout() {
        val tr = Tracker()
        det(0, 100f, 100f, 150f, 150f)
        tr.update(1, x1, y1, x2, y2, s, c, 0, false)
        val t = tr.tracks.single()
        // our censor is now in the capture: detector stops seeing it
        var now = 0L
        while (now < 2500) {
            now += 50
            tr.markOccluded(t, now)
            tr.update(0, x1, y1, x2, y2, s, c, now, false)
        }
        assertEquals("kept while occluded", 1, tr.tracks.size)
        now += 50; tr.markOccluded(t, now)
        tr.update(0, x1, y1, x2, y2, s, c, now, true)
        assertEquals("dropped on scene cut", 0, tr.tracks.size)

        tr.update(1, x1, y1, x2, y2, s, c, 10_000, false)
        val t2 = tr.tracks.single()
        now = 10_000
        while (now < 13_100) { now += 50; tr.markOccluded(t2, now); tr.update(0, x1, y1, x2, y2, s, c, now, false) }
        assertEquals("occluded timeout (3 s)", 0, tr.tracks.size)
    }

    @Test
    fun separateObjectsMakeSeparateTracks() {
        val tr = Tracker()
        det(0, 0f, 0f, 30f, 30f); det(1, 200f, 200f, 260f, 260f)
        tr.update(2, x1, y1, x2, y2, s, c, 0, false)
        assertEquals(2, tr.tracks.size)
        det(0, 3f, 0f, 33f, 30f); det(1, 205f, 200f, 265f, 260f)
        tr.update(2, x1, y1, x2, y2, s, c, 33, false)
        assertEquals(2, tr.tracks.size)
        assertTrue(tr.tracks.all { it.hits == 2 })
    }
}

package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4 bug: male faces got pixelated with Faces OFF. Root cause: the skin-colour
 * appearance guard (class-agnostic) treated the leading edge of a MOVING face as
 * "new skin" vs the detector's last frame, so a box popped on the face every few
 * frames in reels. These tests pin the fix.
 */
class FaceBugTest {
    private val w = 360; private val h = 640; private val s = w * 4
    private val none = FloatArray(0)
    private fun frame(fx: Int, fy: Int, size: Int = 96, extra: IntArray? = null): ByteArray {
        val f = ByteArray(s * h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = y * s + x * 4
            val face = x in fx until fx + size && y in fy until fy + (size * 1.25f).toInt()
            val ex = extra != null && x in extra[0] until extra[2] && y in extra[1] until extra[3]
            if (face || ex) { f[p] = 0xC8.toByte(); f[p + 1] = 0x96.toByte(); f[p + 2] = 0x7D.toByte() }
            else { f[p] = 40; f[p + 1] = 60; f[p + 2] = 90 }
        }
        return f
    }

    @Test
    fun movingFaceInAReelDoesNotTriggerTheGuard() {
        val g = AppearanceGuard()
        var x = 60; var y = 120
        g.analyze(frame(x, y), w, h, s, none, 0, none, 0, 0.0, 0.0, 0, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 0, 0)
        val initial = g.triggers // first appearance of the face: one legit box
        for (k in 1..60) {
            // head bobbing / camera drift: ~0.5 px/ms, direction changes
            x += if ((k / 8) % 2 == 0) 8 else -8; y += if ((k / 5) % 2 == 0) 4 else -4
            g.analyze(frame(x, y), w, h, s, none, 0, none, 0, 0.0, 0.0, k.toLong(), k * 16L)
            if (k % 3 == 0) g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, k.toLong(), k * 16L)
        }
        assertEquals("first appearance boxed once", 1, initial)
        assertEquals("no re-triggers on the moving, already-checked face", initial, g.triggers)
        assertTrue("released", g.boxes.isEmpty())
    }

    @Test
    fun genuinelyNewSkinElsewhereStillTriggersInstantly() {
        val g = AppearanceGuard()
        g.analyze(frame(60, 120), w, h, s, none, 0, none, 0, 0.0, 0.0, 0, 0)
        g.onDetected(g.snapshotInto(FloatArray(0)), 0.0, 0.0, 0, 0)
        g.analyze(frame(60, 120, extra = intArrayOf(200, 420, 320, 560)), w, h, s, none, 0, none, 0, 0.0, 0.0, 1, 16)
        assertTrue("new skin far from checked skin", g.boxes.isNotEmpty())
    }
}

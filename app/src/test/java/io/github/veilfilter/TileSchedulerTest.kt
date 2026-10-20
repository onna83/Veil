package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileSchedulerTest {
    @Test
    fun layoutCoversFrameWithOverlap() {
        val t = TileScheduler.compute(590, 1280, 2, 0.15f)
        assertEquals(10, t.size)
        val side = t[0][2]
        assertTrue("about 320 px tiles: $side", side in 315..325)
        // covers every pixel
        for (y in 0 until 1280 step 7) for (x in 0 until 590 step 7) {
            assertTrue("($x,$y)", t.any { x >= it[0] && x < it[0] + it[2] && y >= it[1] && y < it[1] + it[2] })
        }
        // inside the frame, overlapping neighbours
        for (r in t) assertTrue(r[0] >= 0 && r[1] >= 0 && r[0] + r[2] <= 590 && r[1] + r[2] <= 1280)
        assertTrue(t[2][1] - t[0][1] < side * 0.86)
        // landscape: transposed
        val l = TileScheduler.compute(1280, 590, 2, 0.15f)
        assertEquals(10, l.size)
        for (r in l) assertTrue(r[0] + r[2] <= 1280 && r[1] + r[2] <= 590)
    }

    private val cell = 12
    private fun grid(skinTiles: Set<Int>, s: TileScheduler, cols: Int, rows: Int): FloatArray {
        val g = FloatArray(cols * rows)
        for (k in skinTiles) {
            val t = s.tiles[k]
            // a small skin area in the middle of the tile
            for (j in (t.y + t.side / 4) / cell until (t.y + 3 * t.side / 4) / cell) for (i in (t.x + t.side / 4) / cell until (t.x + 3 * t.side / 4) / cell) g[j * cols + i] = 0.9f
        }
        return g
    }

    @Test
    fun fullFrameDominatesAndHotTilesAreVisitedFirst() {
        val s = TileScheduler()
        s.layout(590, 1280)
        val cols = 590 / cell; val rows = 1280 / cell
        val g = grid(setOf(7), s, cols, rows)
        var now = 0L
        val seq = ArrayList<Int>()
        repeat(7) {
            s.observe(g, cols, rows, cell, 0.0, 0.0, now)
            seq.add(s.next(now, 0.0, 0.0)); now += 20
        }
        // v1.6.1: F, F, T7 (full passes keep >= 2/3), then full passes while nothing is hot;
        // the 4th pass after the tile sweeps a never-visited (cold) tile
        assertEquals(listOf(-1, -1, 7, -1, -1, -1), seq.subList(0, 6))
        assertTrue(seq[6] >= 0 && seq[6] != 7)
    }

    @Test
    fun scrolledTilesBecomeHotAgainAndColdTilesAreRevisitedSlowly() {
        val s = TileScheduler()
        s.layout(590, 1280)
        val cols = 590 / cell; val rows = 1280 / cell
        val g = grid(setOf(3), s, cols, rows)
        var now = 0L
        s.observe(g, cols, rows, cell, 0.0, 0.0, now); s.next(now, 0.0, 0.0); now += 20
        s.observe(g, cols, rows, cell, 0.0, 0.0, now); assertEquals(-1, s.next(now, 0.0, 0.0)); now += 20
        s.observe(g, cols, rows, cell, 0.0, 0.0, now); assertEquals(3, s.next(now, 0.0, 0.0)); now += 200
        // scrolled by more than 10 % of a tile: hot again (after 2 full passes)
        s.observe(g, cols, rows, cell, 0.0, -50.0, now)
        assertEquals(-1, s.next(now, 0.0, -50.0)); now += 20
        assertEquals(-1, s.next(now, 0.0, -50.0)); now += 20
        assertEquals(3, s.next(now, 0.0, -50.0)); now += 20
        // nothing hot: cold tiles only after coldRevisitMs, one per coldEvery passes
        var tiles = 0; var passes = 0
        while (now < 4000) {
            s.observe(g, cols, rows, cell, 0.0, -50.0, now)
            if (s.next(now, 0.0, -50.0) >= 0) tiles++
            passes++; now += 20
        }
        assertTrue("cold tile share ${tiles}/$passes", tiles in 1..passes / 4)
    }

    @Test
    fun disabledOrCutMeansFull() {
        val s = TileScheduler(); s.layout(590, 1280)
        val cols = 590 / cell; val rows = 1280 / cell
        s.observe(grid(setOf(0, 1, 2), s, cols, rows), cols, rows, cell, 0.0, 0.0, 0)
        s.next(0, 0.0, 0.0)
        s.requestFull()
        assertEquals(-1, s.next(20, 0.0, 0.0))
        s.enabled = false
        repeat(5) { assertEquals(-1, s.next(40L + it * 20, 0.0, 0.0)) }
    }

    @Test
    fun v161NoTilesWithoutGridOrWithLargeSubject() {
        val s = TileScheduler(); s.layout(590, 1280)
        val cols = 590 / cell; val rows = 1280 / cell
        val g = grid(setOf(2, 5), s, cols, rows)
        var now = 0L
        // full-screen video: not grid-like -> never a tile, however hot
        s.gridLike = false
        repeat(50) { s.observe(g, cols, rows, cell, 0.0, now * -1.0, now); assertEquals(-1, s.next(now, 0.0, now * -1.0)); now += 20 }
        // grid, but a full pass just found a large subject: held off for BIG_HOLD_MS
        s.gridLike = true
        s.noteBig(now)
        val until = now + TileScheduler.BIG_HOLD_MS
        while (now < until) { s.observe(g, cols, rows, cell, 0.0, 0.0, now); assertEquals(-1, s.next(now, 0.0, 0.0)); now += 20 }
        // afterwards tiles come back, with full passes >= 2/3
        var t = 0; var f = 0
        repeat(90) { s.observe(g, cols, rows, cell, 0.0, -now.toDouble(), now); if (s.next(now, 0.0, -now.toDouble()) >= 0) t++ else f++; now += 20 }
        assertTrue("tiles resumed: $t", t > 0)
        assertTrue("full share ${f}/${t + f}", f * 3 >= 2 * (t + f))
    }
}

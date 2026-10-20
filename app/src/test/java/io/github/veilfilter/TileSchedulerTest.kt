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
        repeat(6) {
            s.observe(g, cols, rows, cell, 0.0, 0.0, now)
            seq.add(s.next(now, 0.0, 0.0)); now += 20
        }
        // F, T7, then full passes while nothing is hot; the 4th pass sweeps a never-visited (cold) tile
        assertEquals(listOf(-1, 7, -1, -1, -1), seq.subList(0, 5))
        assertTrue(seq[5] >= 0 && seq[5] != 7)
    }

    @Test
    fun scrolledTilesBecomeHotAgainAndColdTilesAreRevisitedSlowly() {
        val s = TileScheduler()
        s.layout(590, 1280)
        val cols = 590 / cell; val rows = 1280 / cell
        val g = grid(setOf(3), s, cols, rows)
        var now = 0L
        s.observe(g, cols, rows, cell, 0.0, 0.0, now); s.next(now, 0.0, 0.0); now += 20
        s.observe(g, cols, rows, cell, 0.0, 0.0, now); assertEquals(3, s.next(now, 0.0, 0.0)); now += 200
        // scrolled by more than 10 % of a tile: hot again
        s.observe(g, cols, rows, cell, 0.0, -50.0, now)
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
}

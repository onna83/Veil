package io.github.veilfilter

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * "Small images / grid boost": the full-frame pass sees a 1280-px tall capture
 * at 320 px, so feed/gallery thumbnails become ~25 px objects and are missed.
 * Tiled passes run the same detector on square tiles (2 across the short side,
 * 15 % overlap; 10 tiles of ~320 px at 590x1280), i.e. ~4x more detail.
 *
 * Scheduling (decided per inference pass; thread-safe):
 *  - full-frame passes always dominate: F,T,F,T while some tile is "hot",
 *    F,F,T when full passes are finding big content, otherwise a cold tile
 *    only every [coldEvery] passes once its last visit is older than
 *    [coldRevisitMs];
 *  - a tile is hot when it contains skin-like cells and its content moved
 *    (scroll) or changed since its last visit, or it was never visited;
 *  - among candidates the hottest / longest-unvisited tile wins;
 *  - a pending scene cut forces a full pass.
 */
class TileScheduler(
    val overlap: Float = 0.15f,
    val across: Int = 2,
    var coldRevisitMs: Long = 1500,
    var coldEvery: Int = 4,
    var minRevisitMs: Long = 100,
) {
    class Tile(val x: Int, val y: Int, val side: Int) {
        var lastVisit = Long.MIN_VALUE / 2
        var visitCumX = 0.0
        var visitCumY = 0.0
        var visitSkin = -1f
        var skinNow = 0f
        var hot = false
    }

    @Volatile var enabled = true
    var tiles: List<Tile> = emptyList(); private set
    private var w = 0
    private var h = 0
    private var passesSinceTile = 0
    private var forceFull = false
    @Volatile var bigContent = false
    /**
     * v1.6.1: the frame looks like a grid of small images (fed every frame from
     * [ShiftEstimator.gridLike]). Tiles never run otherwise: full-screen video
     * or a single large photo keep 100 % of the detector for full-frame passes.
     */
    @Volatile var gridLike = true
    /** no tiles until this time after a full pass found large content */
    @Volatile var bigUntilMs = Long.MIN_VALUE / 2
    fun noteBig(nowMs: Long) { bigUntilMs = nowMs + BIG_HOLD_MS }

    // stats
    var tilePasses = 0; private set
    var fullPasses = 0; private set
    /** ms between visits of the same hot tile (EMA) */
    var cycleMsEma = 0f; private set

    @Synchronized fun layout(fw: Int, fh: Int) {
        if (fw == w && fh == h) return
        w = fw; h = fh
        tiles = compute(fw, fh, across, overlap).map { Tile(it[0], it[1], it[2]) }
    }

    /**
     * Capture thread, every frame: update hotness from the skin grid
     * ([skin] cols x rows of [cell] px, -1 = masked) and the cumulative scroll.
     */
    @Synchronized fun observe(skin: FloatArray, cols: Int, rows: Int, cell: Int, cumX: Double, cumY: Double, nowMs: Long) {
        for (t in tiles) {
            val i0 = t.x / cell; val i1 = min(cols - 1, (t.x + t.side) / cell - 1)
            val j0 = t.y / cell; val j1 = min(rows - 1, (t.y + t.side) / cell - 1)
            var n = 0; var sk = 0
            for (j in j0..j1) for (i in i0..i1) {
                val v = skin[j * cols + i]
                n++
                if (v >= SKIN_CELL) sk++
            }
            val frac = if (n == 0) 0f else sk.toFloat() / n
            t.skinNow = frac
            val moved = abs(cumX - t.visitCumX) > 0.1 * t.side || abs(cumY - t.visitCumY) > 0.1 * t.side
            val changed = t.visitSkin < 0f || abs(frac - t.visitSkin) > 0.05f
            t.hot = frac >= MIN_SKIN_FRACTION && (moved || changed) && nowMs - t.lastVisit >= minRevisitMs
        }
    }

    @Synchronized fun requestFull() { forceFull = true }

    /** Region for the next pass (-1 = full frame) and commit it (tests / single-threaded use). */
    @Synchronized fun next(nowMs: Long, cumX: Double, cumY: Double): Int {
        val r = peek(nowMs)
        commit(r, nowMs, cumX, cumY)
        return r
    }

    /** Capture thread: which region to prepare next. No side effects. */
    @Synchronized fun peek(nowMs: Long): Int {
        if (!enabled || tiles.isEmpty() || forceFull) return -1
        if (!gridLike || bigContent || nowMs < bigUntilMs) return -1
        val anyHot = tiles.any { it.hot }
        // full passes keep >= 2/3 of the detector even on a grid
        val needFullFirst = if (anyHot) 2 else coldEvery - 1
        if (passesSinceTile < needFullFirst) return -1
        var best = -1; var bestScore = -1.0
        for ((k, t) in tiles.withIndex()) {
            val age = (nowMs - t.lastVisit).toDouble()
            val score = when {
                t.hot -> 1e9 + age + t.skinNow * 1000
                !anyHot && age >= coldRevisitMs -> age
                else -> -1.0
            }
            if (score > bestScore) { bestScore = score; best = k }
        }
        return best
    }

    /** Inference thread: the pass for [region] is starting now. */
    @Synchronized fun commit(region: Int, nowMs: Long, cumX: Double, cumY: Double) {
        if (region < 0 || region >= tiles.size) {
            forceFull = false
            passesSinceTile++; fullPasses++
            return
        }
        val t = tiles[region]
        if (t.hot && t.lastVisit > 0) cycleMsEma = ema(cycleMsEma, (nowMs - t.lastVisit).toFloat())
        t.lastVisit = nowMs; t.visitCumX = cumX; t.visitCumY = cumY; t.visitSkin = t.skinNow; t.hot = false
        passesSinceTile = 0
        tilePasses++
    }

    /** hot tile count (HUD) */
    @Synchronized fun hotCount() = tiles.count { it.hot }

    @Synchronized fun reset() {
        for (t in tiles) { t.lastVisit = Long.MIN_VALUE / 2; t.visitSkin = -1f; t.hot = false }
        passesSinceTile = 0
    }

    companion object {
        const val BIG_HOLD_MS = 1500L
        const val SKIN_CELL = 0.4f
        const val MIN_SKIN_FRACTION = 0.03f
        private fun ema(o: Float, v: Float) = if (o == 0f) v else o * 0.8f + v * 0.2f

        /** Square tiles: [across] over the short side with [ov] overlap, as many as needed along the long side. */
        fun compute(fw: Int, fh: Int, across: Int, ov: Float): List<IntArray> {
            val short = min(fw, fh); val long = max(fw, fh)
            val side = if (across <= 1) short else (short / (across - (across - 1) * ov)).toInt()
            val a = if (across <= 1) listOf(0) else (0 until across).map { (it * (short - side).toFloat() / (across - 1)).roundToInt() }
            val n = max(1, ceil((long - side) / (side * (1 - ov))).toInt() + 1)
            val b = if (n == 1) listOf(0) else (0 until n).map { (it * (long - side).toFloat() / (n - 1)).roundToInt() }
            val out = ArrayList<IntArray>()
            for (q in b) for (p in a) out.add(if (fw <= fh) intArrayOf(p, q, side) else intArrayOf(q, p, side))
            return out
        }
    }
}

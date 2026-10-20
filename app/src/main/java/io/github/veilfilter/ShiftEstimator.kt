package io.github.veilfilter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cheap global scroll estimator (v1.6.1 rewrite, same contract as before).
 *
 * Every frame two sets of 1-D luma (green channel) profiles are built, in
 * [BANDS] bands each:
 *  - row profiles (for dy): every [rowStep]-th row (2 at 1280 px, 1 for small
 *    captures), mean of 3 short stripes per vertical band; each stripe is 4
 *    pixels inside ONE 64-byte cache line, so a row costs 12 cache lines
 *    instead of all ~37 (the old every-8th-column sampling touched every
 *    line of the 3 MB frame, which is what made it ~16 ms on the phone
 *    while the GPU saturates memory);
 *  - column profiles (for dx): every [colStep]-th column, mean over every
 *    16th row, per horizontal band (row-major walk). The same pass collects
 *    per-column variance, used to spot the narrow uniform gutters of an image
 *    grid ([gridLike]).
 * Cells touching an excluded rectangle (our own censors in full-screen
 * capture) are invalid for that frame; with nothing excluded a branch-free
 * fast path is used.
 *
 * Search per axis: 3-level pyramid (x16 full range, x4 +-5, x1 +-3) on mean
 * |cur[i] - prev[i - d]|, then equiangular (V) sub-sample interpolation,
 * which is exact for SAD minima, so half-resolution rows still give
 * < 0.3 px error.
 *
 * Convention: content moved by (dx, dy) from the previous frame to this one,
 * i.e. cur(x, y) ~= prev(x - dx, y - dy). Scrolling a list up gives dy < 0.
 *
 * Cost at 590x1280: ~45k byte reads touching ~6.5k cache lines plus ~40k
 * add/abs ops (was ~190k reads / ~53k lines / ~280k ops).
 */
class ShiftEstimator(
    /** max search range as a fraction of the frame dimension */
    private val maxShiftFraction: Float = 0.18f,
) {
    var dx = 0f; private set
    var dy = 0f; private set
    /** a shift (possibly zero) was measured reliably */
    var confident = false; private set
    /** content did not change at all (confident zero shift) */
    var static = false; private set
    /** mean abs difference at zero shift and at the best shift (luma levels) */
    var sad0 = 0f; private set
    var sadBest = 0f; private set
    /** last call: profile build / search time, ms (HUD) */
    var profMs = 0f; private set
    var searchMs = 0f; private set
    /** the frame looks like a grid of images (narrow uniform vertical gutters), debounced */
    var gridLike = false; private set
    var gutters = 0; private set
    /** row / column decimation in use; [lite] (set by the pipeline when the measured cost is high) doubles both */
    var rowStep = 1; private set
    var colStep = 1; private set
    @Volatile var lite = false

    private var w = -1
    private var h = -1
    private var stride = 0
    private var liteApplied = false
    private var hasPrev = false
    private var lr = 0 // decimated rows
    private var lc = 0 // decimated cols

    private var rowCur = FloatArray(0); private var rowPrev = FloatArray(0)
    private var rowValCur = BooleanArray(0); private var rowValPrev = BooleanArray(0)
    private var colCur = FloatArray(0); private var colPrev = FloatArray(0)
    private var colValCur = BooleanArray(0); private var colValPrev = BooleanArray(0)
    private var allValidCur = true; private var allValidPrev = true
    private var colAcc = IntArray(0)
    private var colSq = IntArray(0)
    private val colCnt = IntArray(BANDS)
    private var stripeX = IntArray(0)
    // pyramid scratch: [level][band*len]
    private val pyrCur = arrayOfNulls<FloatArray>(2); private val pyrPrev = arrayOfNulls<FloatArray>(2)
    private val pyrVC = arrayOfNulls<BooleanArray>(2); private val pyrVP = arrayOfNulls<BooleanArray>(2)
    private var gridVotes = 0

    fun reset() { hasPrev = false; confident = false; static = false; dx = 0f; dy = 0f; gridVotes = 0; gridLike = false }

    private fun alloc(nw: Int, nh: Int, ns: Int) {
        w = nw; h = nh; stride = ns
        liteApplied = lite
        rowStep = if (h > 720) (if (lite) 4 else 2) else (if (lite) 2 else 1)
        colStep = if (w > 360) 2 else 1 // horizontal: 4 px steps lose too much accuracy (tested)
        lr = h / rowStep; lc = w / colStep
        rowCur = FloatArray(BANDS * lr); rowPrev = FloatArray(BANDS * lr)
        rowValCur = BooleanArray(BANDS * lr) { true }; rowValPrev = BooleanArray(BANDS * lr) { true }
        colCur = FloatArray(BANDS * lc); colPrev = FloatArray(BANDS * lc)
        colValCur = BooleanArray(BANDS * lc) { true }; colValPrev = BooleanArray(BANDS * lc) { true }
        colAcc = IntArray(BANDS * lc); colSq = IntArray(BANDS * lc)
        // 3 stripes per band, each starting on a 16-px (64-byte) boundary
        val bandW = w / BANDS
        stripeX = IntArray(BANDS * STRIPES) { k ->
            val b = k / STRIPES; val s = k % STRIPES
            val x = b * bandW + ((s + 0.5f) * bandW / STRIPES).toInt()
            min(max(0, (x / 16) * 16), max(0, w - 16))
        }
        hasPrev = false
        gridVotes = 0
    }

    /**
     * @param rects excluded rectangles as [x1,y1,x2,y2]*n in frame pixels
     */
    fun analyze(src: ByteArray, fw: Int, fh: Int, stride: Int, rects: FloatArray, nRects: Int) {
        val t0 = System.nanoTime()
        if (fw != w || fh != h || stride != this.stride || lite != liteApplied) alloc(fw, fh, stride)
        swap()
        buildProfiles(src, rects, nRects)
        val t1 = System.nanoTime()
        profMs = (t1 - t0) / 1e6f
        if (!hasPrev) {
            hasPrev = true
            confident = false; static = false; dx = 0f; dy = 0f
            searchMs = 0f
            return
        }
        val fast = allValidCur && allValidPrev
        val vy = search(rowCur, rowPrev, rowValCur, rowValPrev, lr, fast) * rowStep
        val vs0 = lastSad0; val vsb = lastSadBest; val vok = lastOk
        if (vok && vs0 < STATIC_SAD && earlyStatic()) {
            // vertical says "identical"; confirm on columns at zero shift only
            val hs = sadAt(colCur, colPrev, colValCur, colValPrev, lc, 0, fast)
            if (hs < STATIC_SAD) {
                sad0 = max(vs0, hs); sadBest = sad0
                static = true; confident = true; dx = 0f; dy = 0f
                searchMs = (System.nanoTime() - t1) / 1e6f
                return
            }
        }
        val vx = search(colCur, colPrev, colValCur, colValPrev, lc, fast) * colStep
        val hs0 = lastSad0; val hsb = lastSadBest; val hok = lastOk
        searchMs = (System.nanoTime() - t1) / 1e6f

        sad0 = max(vs0, hs0)
        sadBest = max(vsb, hsb)
        if (vs0 < STATIC_SAD && hs0 < STATIC_SAD) {
            static = true; confident = true; dx = 0f; dy = 0f
            return
        }
        static = false
        // A real scroll explains the change much better than "no motion".
        // Axes are resolved one at a time; the axis whose best shift explains
        // the change best wins. Diagonal pans are approximated by that axis.
        val okV = vok && vsb < MAX_SAD && (abs(vy) < 0.5f || vsb < RATIO * vs0)
        val okH = hok && hsb < MAX_SAD && (abs(vx) < 0.5f || hsb < RATIO * hs0)
        val rV = if (okV && abs(vy) >= 0.5f) vsb / max(vs0, 1e-3f) else Float.MAX_VALUE
        val rH = if (okH && abs(vx) >= 0.5f) hsb / max(hs0, 1e-3f) else Float.MAX_VALUE
        dx = 0f; dy = 0f
        when {
            rV <= rH && rV != Float.MAX_VALUE -> { dy = vy; confident = true }
            rH < rV -> { dx = vx; confident = true }
            else -> confident = okV && okH
        }
    }

    private fun earlyStatic() = true

    private fun swap() {
        var t = rowCur; rowCur = rowPrev; rowPrev = t
        var b = rowValCur; rowValCur = rowValPrev; rowValPrev = b
        t = colCur; colCur = colPrev; colPrev = t
        b = colValCur; colValCur = colValPrev; colValPrev = b
        val a = allValidCur; allValidCur = allValidPrev; allValidPrev = a
    }

    private fun buildProfiles(src: ByteArray, rects: FloatArray, nRects: Int) {
        val rc = rowCur; val sx = stripeX
        val lr = lr; val rs = rowStep; val str = stride
        // rows: 3 x 4 green reads per band, each stripe in one cache line
        for (k in 0 until lr) {
            val row = k * rs * str + 1
            for (b in 0 until BANDS) {
                var s = 0
                for (q in 0 until STRIPES) {
                    val p = row + sx[b * STRIPES + q] * 4
                    s += (src[p].toInt() and 0xFF) + (src[p + 16].toInt() and 0xFF) +
                        (src[p + 32].toInt() and 0xFF) + (src[p + 48].toInt() and 0xFF)
                }
                rc[b * lr + k] = s * (1f / (4 * STRIPES))
            }
        }
        // columns: every colStep-th column, every 16th row, per horizontal band (row-major)
        val acc = colAcc; val sq = colSq; val cc = colCur
        java.util.Arrays.fill(acc, 0); java.util.Arrays.fill(sq, 0); java.util.Arrays.fill(colCnt, 0)
        val lc = lc; val cs4 = colStep * 4
        val bandH = h / BANDS
        for (b in 0 until BANDS) {
            val y0 = b * bandH
            val y1 = if (b == BANDS - 1) h else y0 + bandH
            val base = b * lc
            val step = if (liteApplied) 2 * COL_ROW_STEP else COL_ROW_STEP
            var y = y0 + step / 2
            while (y < y1) {
                var p = y * str + 1
                for (x in 0 until lc) { val v = src[p].toInt() and 0xFF; acc[base + x] += v; sq[base + x] += v * v; p += cs4 }
                colCnt[b]++
                y += step
            }
            val n = colCnt[b]
            val inv = if (n > 0) 1f / n else 0f
            for (x in 0 until lc) cc[base + x] = acc[base + x] * inv
        }
        // validity
        if (nRects == 0) {
            if (!allValidCur) { java.util.Arrays.fill(rowValCur, true); java.util.Arrays.fill(colValCur, true) }
            allValidCur = true
        } else {
            java.util.Arrays.fill(rowValCur, true); java.util.Arrays.fill(colValCur, true)
            allValidCur = false
            val bandW = w / BANDS
            for (k in 0 until nRects) {
                val rx1 = rects[4 * k]; val ry1 = rects[4 * k + 1]
                val rx2 = rects[4 * k + 2]; val ry2 = rects[4 * k + 3]
                val ya = max(0, ry1.toInt() - 1); val yb = min(h - 1, ry2.toInt() + 1)
                val xa = max(0, rx1.toInt() - 1); val xb = min(w - 1, rx2.toInt() + 1)
                if (ya > yb || xa > xb) continue
                for (b in 0 until BANDS) {
                    val bx0 = b * bandW
                    val bx1 = if (b == BANDS - 1) w - 1 else bx0 + bandW - 1
                    if (xb >= bx0 && xa <= bx1) for (r in ya / rs..min(lr - 1, yb / rs)) rowValCur[b * lr + r] = false
                    val by0 = b * bandH
                    val by1 = if (b == BANDS - 1) h - 1 else by0 + bandH - 1
                    if (yb >= by0 && ya <= by1) for (c in xa / colStep..min(lc - 1, xb / colStep)) colValCur[b * lc + c] = false
                }
            }
        }
        detectGutters()
    }

    /**
     * Image grids (galleries, explore pages) have narrow (<= ~8 px) uniform vertical
     * gutters spanning the content, flanked by textured thumbnails. A full-screen
     * video or a single large photo has none (flat backgrounds are wide, not narrow).
     */
    private fun detectGutters() {
        val lc = lc
        val maxRun = max(1, 8 / colStep)
        val near = max(2, 6 / colStep)
        var bandsWith = 0
        var found = 0
        for (b in 0 until BANDS) {
            val n = colCnt[b]
            if (n < 4) continue
            val base = b * lc
            var hasGutter = false
            var x = (lc * 0.08f).toInt()
            val xEnd = (lc * 0.92f).toInt()
            while (x < xEnd) {
                if (variance(base + x, n) < LOW_VAR) {
                    var e = x
                    while (e + 1 < xEnd && variance(base + e + 1, n) < LOW_VAR) e++
                    if (e - x + 1 <= maxRun) {
                        var left = false; var right = false
                        for (q in 1..near) {
                            if (x - q >= 0 && variance(base + x - q, n) > HIGH_VAR) left = true
                            if (e + q < lc && variance(base + e + q, n) > HIGH_VAR) right = true
                        }
                        if (left && right) { hasGutter = true; found++ }
                    }
                    x = e + 1
                } else x++
            }
            if (hasGutter) bandsWith++
        }
        gutters = found
        val vote = bandsWith >= 2
        gridVotes = if (vote) min(gridVotes + 1, GRID_ON + GRID_OFF) else max(gridVotes - 1, 0)
        if (gridVotes >= GRID_ON) gridLike = true else if (gridVotes == 0) gridLike = false
    }

    private fun variance(i: Int, n: Int): Float {
        val m = colAcc[i].toFloat() / n
        return colSq[i].toFloat() / n - m * m
    }

    private var lastSad0 = 0f
    private var lastSadBest = 0f
    private var lastOk = false
    private var lastCount = 0

    /** Mean |cur[i] - prev[i - d]| over valid pairs, all bands. */
    private fun sadAt(cur: FloatArray, prev: FloatArray, vc: BooleanArray, vp: BooleanArray, len: Int, d: Int, fast: Boolean): Float {
        var s = 0f; var n = 0
        val i0 = max(0, d); val i1 = min(len, len + d)
        if (i1 <= i0) { lastCount = 0; return Float.MAX_VALUE }
        if (fast) {
            for (b in 0 until BANDS) {
                val base = b * len
                for (i in base + i0 until base + i1) s += abs(cur[i] - prev[i - d])
            }
            n = BANDS * (i1 - i0)
        } else {
            for (b in 0 until BANDS) {
                val base = b * len
                for (i in i0 until i1) {
                    val a = base + i; val p = a - d
                    if (vc[a] && vp[p]) { s += abs(cur[a] - prev[p]); n++ }
                }
            }
        }
        lastCount = n
        return if (n == 0) Float.MAX_VALUE else s / n
    }

    /** f (BANDS x len) averaged by [c] into dst (BANDS x len/c); invalid if any source invalid. */
    private fun down(f: FloatArray, v: BooleanArray, len: Int, c: Int, dst: FloatArray, dv: BooleanArray, fast: Boolean) {
        val l2 = len / c
        val inv = 1f / c
        for (b in 0 until BANDS) for (i in 0 until l2) {
            var s = 0f; var ok = true
            val k0 = b * len + i * c
            for (j in k0 until k0 + c) { s += f[j]; if (!fast && !v[j]) ok = false }
            dst[b * l2 + i] = s * inv
            dv[b * l2 + i] = ok
        }
    }

    private fun ensure(level: Int, n: Int) {
        if ((pyrCur[level]?.size ?: -1) != n) {
            pyrCur[level] = FloatArray(n); pyrPrev[level] = FloatArray(n)
            pyrVC[level] = BooleanArray(n); pyrVP[level] = BooleanArray(n)
        }
    }

    /** best shift in [lo, hi] (in units of the given level), min overlap [minCount] */
    private fun best(cur: FloatArray, prev: FloatArray, vc: BooleanArray, vp: BooleanArray, len: Int,
                     lo: Int, hi: Int, minCount: Int, fast: Boolean, out: FloatArray?): Int {
        var bd = Int.MIN_VALUE; var bs = Float.MAX_VALUE
        for (d in lo..hi) {
            val s = sadAt(cur, prev, vc, vp, len, d, fast)
            if (out != null) out[d - lo] = if (lastCount < minCount || lastCount == 0) Float.MAX_VALUE else s
            if (lastCount < minCount || lastCount == 0) continue
            val sb = s + abs(d) * 1e-3f // tiny bias towards zero so flat content does not wander
            if (sb < bs) { bs = sb; bd = d }
        }
        return bd
    }

    private val win = FloatArray(16)

    private fun search(cur: FloatArray, prev: FloatArray, vc: BooleanArray, vp: BooleanArray, len: Int, fast: Boolean): Float {
        val fullMax = max(2, (len * maxShiftFraction).toInt())
        // level 0: x16 (or coarser if short), level 1: x4
        val c0 = if (len >= 16 * 24) 16 else 4
        val c1 = if (c0 == 16) 4 else 1
        val l0 = len / c0
        ensure(0, BANDS * l0)
        val p0c = pyrCur[0]!!; val p0p = pyrPrev[0]!!; val v0c = pyrVC[0]!!; val v0p = pyrVP[0]!!
        down(cur, vc, len, c0, p0c, v0c, fast); down(prev, vp, len, c0, p0p, v0p, fast)
        sadAt(p0c, p0p, v0c, v0p, l0, 0, fast)
        val minCount0 = lastCount / 2
        val m0 = max(1, fullMax / c0)
        val b0 = best(p0c, p0p, v0c, v0p, l0, -m0, m0, minCount0, fast, null)
        if (b0 == Int.MIN_VALUE) { lastOk = false; lastSad0 = 0f; lastSadBest = 0f; return 0f }
        var center = b0 * (c0 / c1)
        if (c1 > 1) {
            val l1 = len / c1
            ensure(1, BANDS * l1)
            val p1c = pyrCur[1]!!; val p1p = pyrPrev[1]!!; val v1c = pyrVC[1]!!; val v1p = pyrVP[1]!!
            down(cur, vc, len, c1, p1c, v1c, fast); down(prev, vp, len, c1, p1p, v1p, fast)
            sadAt(p1c, p1p, v1c, v1p, l1, 0, fast)
            val mc = lastCount / 2
            val r = c0 / c1 + 1
            val m1 = fullMax / c1
            val b1 = best(p1c, p1p, v1c, v1p, l1, max(-m1, center - r), min(m1, center + r), mc, fast, null)
            if (b1 != Int.MIN_VALUE) center = b1
            center *= c1
        }
        // full resolution of this profile: +-3 around, keep the SADs for interpolation
        val s0 = sadAt(cur, prev, vc, vp, len, 0, fast)
        val n0 = lastCount
        lastSad0 = if (n0 == 0) 0f else s0
        val lo = max(-fullMax, center - 3); val hi = min(fullMax, center + 3)
        val fb = best(cur, prev, vc, vp, len, lo, hi, 1, fast, win)
        if (n0 == 0 || fb == Int.MIN_VALUE) { lastOk = false; lastSadBest = lastSad0; return 0f }
        val fs = win[fb - lo]
        lastSadBest = fs
        lastOk = true
        // equiangular (V) interpolation: exact for a V-shaped SAD minimum
        val sm = if (fb - 1 >= lo) win[fb - 1 - lo] else sadAt(cur, prev, vc, vp, len, fb - 1, fast)
        val sp = if (fb + 1 <= hi) win[fb + 1 - lo] else sadAt(cur, prev, vc, vp, len, fb + 1, fast)
        var off = 0f
        if (sm != Float.MAX_VALUE && sp != Float.MAX_VALUE) {
            val k = max(sm, sp) - fs // V slope per sample
            if (k > 1e-6f) {
                off = ((sm - sp) / (2f * k)).coerceIn(-0.5f, 0.5f)
                // SAD at the interpolated minimum (decimated profiles: the true minimum
                // usually lies between two samples)
                lastSadBest = max(0f, fs - k * abs(off))
            }
        }
        return fb + off
    }

    companion object {
        const val BANDS = 4
        private const val STRIPES = 3
        private const val COL_ROW_STEP = 16
        /** below this (luma levels) nothing changed */
        const val STATIC_SAD = 0.35f
        /** best shift must explain the change this much better than zero */
        const val RATIO = 0.6f
        /** above this the frames do not match at any shift (cut, video) */
        const val MAX_SAD = 6f
        private const val LOW_VAR = 6f
        private const val HIGH_VAR = 80f
        private const val GRID_ON = 3
        private const val GRID_OFF = 6
    }
}

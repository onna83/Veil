package io.github.veilfilter

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Cheap global scroll estimator.
 *
 * Every frame we build 1-D luma (green channel) profiles:
 *  - row profiles: for each row, the mean over every 8th column, separately
 *    in [BANDS] vertical bands;
 *  - column profiles: for each column, the mean over every 8th row, in
 *    [BANDS] horizontal bands.
 * A (row, band) cell that touches any excluded rectangle (our own censor
 * windows, which are captured in full-screen mode) is marked invalid in that
 * frame, and is skipped when comparing frames.
 *
 * The vertical shift d minimises the mean |cur[r] - prev[r - d]| over valid
 * rows: coarse search on 4-row averages, refinement at full resolution,
 * then parabolic sub-pixel interpolation. Same for horizontal.
 *
 * Convention: content moved by (dx, dy) from the previous frame to this one,
 * i.e. cur(x, y) ~= prev(x - dx, y - dy). Scrolling a list up gives dy < 0.
 *
 * Cost (444x960 capture): ~105k sequential byte reads plus ~0.15M add/abs
 * ops per frame; no allocation after the first frame of a given size.
 */
class ShiftEstimatorV12(
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

    private var w = -1
    private var h = -1
    private var hasPrev = false

    // row profiles [band][row], column profiles [band][col]; valid flags
    private var rowCur = FloatArray(0); private var rowPrev = FloatArray(0)
    private var rowValCur = BooleanArray(0); private var rowValPrev = BooleanArray(0)
    private var colCur = FloatArray(0); private var colPrev = FloatArray(0)
    private var colValCur = BooleanArray(0); private var colValPrev = BooleanArray(0)
    // coarse (4x) versions
    private var rowCCur = FloatArray(0); private var rowCPrev = FloatArray(0)
    private var rowCValCur = BooleanArray(0); private var rowCValPrev = BooleanArray(0)
    private var colCCur = FloatArray(0); private var colCPrev = FloatArray(0)
    private var colCValCur = BooleanArray(0); private var colCValPrev = BooleanArray(0)

    private var colAcc = IntArray(0)
    private val colCnt = IntArray(BANDS)

    fun reset() { hasPrev = false; confident = false; static = false; dx = 0f; dy = 0f }

    private fun alloc(nw: Int, nh: Int) {
        w = nw; h = nh
        rowCur = FloatArray(BANDS * h); rowPrev = FloatArray(BANDS * h)
        rowValCur = BooleanArray(BANDS * h); rowValPrev = BooleanArray(BANDS * h)
        colCur = FloatArray(BANDS * w); colPrev = FloatArray(BANDS * w)
        colValCur = BooleanArray(BANDS * w); colValPrev = BooleanArray(BANDS * w)
        colAcc = IntArray(BANDS * w)
        val hc = h / C; val wc = w / C
        rowCCur = FloatArray(BANDS * hc); rowCPrev = FloatArray(BANDS * hc)
        rowCValCur = BooleanArray(BANDS * hc); rowCValPrev = BooleanArray(BANDS * hc)
        colCCur = FloatArray(BANDS * wc); colCPrev = FloatArray(BANDS * wc)
        colCValCur = BooleanArray(BANDS * wc); colCValPrev = BooleanArray(BANDS * wc)
        hasPrev = false
    }

    /**
     * @param rects excluded rectangles as [x1,y1,x2,y2]*n in frame pixels
     */
    fun analyze(src: ByteArray, fw: Int, fh: Int, stride: Int, rects: FloatArray, nRects: Int) {
        if (fw != w || fh != h) alloc(fw, fh)
        swap()
        buildProfiles(src, stride, rects, nRects)
        if (!hasPrev) {
            hasPrev = true
            confident = false; static = false; dx = 0f; dy = 0f
            return
        }
        val vy = search(rowCur, rowPrev, rowValCur, rowValPrev, rowCCur, rowCPrev, rowCValCur, rowCValPrev, h)
        val vs0 = lastSad0; val vsb = lastSadBest; val vok = lastOk
        val vx = search(colCur, colPrev, colValCur, colValPrev, colCCur, colCPrev, colCValCur, colCValPrev, w)
        val hs0 = lastSad0; val hsb = lastSadBest; val hok = lastOk

        sad0 = max(vs0, hs0)
        sadBest = max(vsb, hsb)
        if (vs0 < STATIC_SAD && hs0 < STATIC_SAD) {
            static = true; confident = true; dx = 0f; dy = 0f
            return
        }
        static = false
        // A real scroll explains the change much better than "no motion".
        // Axes are resolved one at a time (a vertical scroll also changes the
        // column profiles and vice versa); the axis whose best shift explains
        // the change best wins. Diagonal pans are approximated by that axis.
        val okV = vok && vsb < MAX_SAD && (abs(vy) < 0.5f || vsb < RATIO * vs0)
        val okH = hok && hsb < MAX_SAD && (abs(vx) < 0.5f || hsb < RATIO * hs0)
        val rV = if (okV && abs(vy) >= 0.5f) vsb / max(vs0, 1e-3f) else Float.MAX_VALUE
        val rH = if (okH && abs(vx) >= 0.5f) hsb / max(hs0, 1e-3f) else Float.MAX_VALUE
        dx = 0f; dy = 0f
        when {
            rV <= rH && rV != Float.MAX_VALUE -> { dy = vy; confident = true }
            rH < rV -> { dx = vx; confident = true }
            // both axes say "no shift": confident zero if they also match well
            else -> confident = okV && okH
        }
    }

    private fun swap() {
        var t = rowCur; rowCur = rowPrev; rowPrev = t
        var b = rowValCur; rowValCur = rowValPrev; rowValPrev = b
        t = colCur; colCur = colPrev; colPrev = t
        b = colValCur; colValCur = colValPrev; colValPrev = b
        t = rowCCur; rowCCur = rowCPrev; rowCPrev = t
        b = rowCValCur; rowCValCur = rowCValPrev; rowCValPrev = b
        t = colCCur; colCCur = colCPrev; colCPrev = t
        b = colCValCur; colCValCur = colCValPrev; colCValPrev = b
    }

    private fun buildProfiles(src: ByteArray, stride: Int, rects: FloatArray, nRects: Int) {
        val bandW = w / BANDS
        val bandH = h / BANDS
        // rows: every row, every 8th column, per vertical band (sequential reads)
        for (y in 0 until h) {
            val row = y * stride + 1 // green channel
            for (b in 0 until BANDS) {
                val x0 = b * bandW
                val x1 = if (b == BANDS - 1) w else x0 + bandW
                var s = 0; var n = 0
                var x = x0
                while (x < x1) { s += src[row + x * 4].toInt() and 0xFF; n++; x += ROW_STEP }
                rowCur[b * h + y] = if (n > 0) s.toFloat() / n else 0f
                rowValCur[b * h + y] = n > 0
            }
        }
        // columns: every column, every 8th row, per horizontal band.
        // Built ROW-MAJOR: walking the frame column by column (17 KB jumps)
        // missed the cache on every read and cost ~18 ms on an Exynos 2200.
        java.util.Arrays.fill(colAcc, 0)
        java.util.Arrays.fill(colCnt, 0)
        for (b in 0 until BANDS) {
            val y0 = b * bandH
            val y1 = if (b == BANDS - 1) h else y0 + bandH
            val base = b * w
            var y = y0
            while (y < y1) {
                var p = y * stride + 1
                for (x in 0 until w) { colAcc[base + x] += src[p].toInt() and 0xFF; p += 4 }
                colCnt[b]++
                y += COL_STEP
            }
            val n = colCnt[b]
            for (x in 0 until w) {
                colCur[base + x] = if (n > 0) colAcc[base + x].toFloat() / n else 0f
                colValCur[base + x] = n > 0
            }
        }
        // invalidate cells touched by excluded rects
        for (k in 0 until nRects) {
            val rx1 = rects[4 * k]; val ry1 = rects[4 * k + 1]
            val rx2 = rects[4 * k + 2]; val ry2 = rects[4 * k + 3]
            val ya = max(0, ry1.toInt() - 1); val yb = min(h - 1, ry2.toInt() + 1)
            val xa = max(0, rx1.toInt() - 1); val xb = min(w - 1, rx2.toInt() + 1)
            if (ya > yb || xa > xb) continue
            for (b in 0 until BANDS) {
                val bx0 = b * bandW
                val bx1 = if (b == BANDS - 1) w - 1 else bx0 + bandW - 1
                if (xb >= bx0 && xa <= bx1) for (y in ya..yb) rowValCur[b * h + y] = false
                val by0 = b * bandH
                val by1 = if (b == BANDS - 1) h - 1 else by0 + bandH - 1
                if (yb >= by0 && ya <= by1) for (x in xa..xb) colValCur[b * w + x] = false
            }
        }
        coarsen(rowCur, rowValCur, rowCCur, rowCValCur, h)
        coarsen(colCur, colValCur, colCCur, colCValCur, w)
    }

    private fun coarsen(f: FloatArray, v: BooleanArray, c: FloatArray, cv: BooleanArray, len: Int) {
        val lc = len / C
        for (b in 0 until BANDS) for (i in 0 until lc) {
            var s = 0f; var ok = true
            for (j in 0 until C) {
                val k = b * len + i * C + j
                if (!v[k]) { ok = false; break }
                s += f[k]
            }
            c[b * lc + i] = s / C
            cv[b * lc + i] = ok
        }
    }

    private var lastSad0 = 0f
    private var lastSadBest = 0f
    private var lastOk = false
    private var lastCount = 0

    /** Mean |cur[i] - prev[i - d]| over valid pairs, all bands. */
    private fun sad(cur: FloatArray, prev: FloatArray, vc: BooleanArray, vp: BooleanArray, len: Int, d: Int): Float {
        var s = 0f; var n = 0
        val i0 = max(0, d); val i1 = min(len, len + d)
        for (b in 0 until BANDS) {
            val base = b * len
            for (i in i0 until i1) {
                val a = base + i; val p = base + i - d
                if (vc[a] && vp[p]) { s += abs(cur[a] - prev[p]); n++ }
            }
        }
        lastCount = n
        return if (n == 0) Float.MAX_VALUE else s / n
    }

    private fun search(
        cur: FloatArray, prev: FloatArray, vc: BooleanArray, vp: BooleanArray,
        cc: FloatArray, cp: FloatArray, cvc: BooleanArray, cvp: BooleanArray, len: Int,
    ): Float {
        val lc = len / C
        val maxC = max(1, (len * maxShiftFraction / C).toInt())
        // minimum overlap: half of the valid cells at zero shift
        sad(cc, cp, cvc, cvp, lc, 0)
        val minCount = lastCount / 2
        var best = 0; var bestS = Float.MAX_VALUE
        for (d in -maxC..maxC) {
            val s = sad(cc, cp, cvc, cvp, lc, d)
            if (lastCount < minCount || lastCount == 0) continue
            // tiny bias towards zero so flat content does not wander
            val sb = s + abs(d) * 1e-3f
            if (sb < bestS) { bestS = sb; best = d }
        }
        // refine at full resolution around best*C
        val center = best * C
        var fb = center; var fs = Float.MAX_VALUE
        val fullMax = (len * maxShiftFraction).toInt()
        for (d in center - C - 1..center + C + 1) {
            if (abs(d) > fullMax) continue
            val s = sad(cur, prev, vc, vp, len, d)
            if (lastCount == 0) continue
            if (s < fs) { fs = s; fb = d }
        }
        val s0 = sad(cur, prev, vc, vp, len, 0)
        val n0 = lastCount
        lastSad0 = if (n0 == 0) 0f else s0
        lastSadBest = if (fs == Float.MAX_VALUE) lastSad0 else fs
        lastOk = n0 > 0 && fs != Float.MAX_VALUE
        if (!lastOk) return 0f
        // sub-pixel: parabola through (fb-1, fb, fb+1)
        val sm = sad(cur, prev, vc, vp, len, fb - 1)
        val sp = sad(cur, prev, vc, vp, len, fb + 1)
        var off = 0f
        if (sm != Float.MAX_VALUE && sp != Float.MAX_VALUE) {
            val den = sm - 2 * fs + sp
            if (den > 1e-6f) off = (0.5f * (sm - sp) / den).coerceIn(-0.5f, 0.5f)
        }
        return fb + off
    }

    companion object {
        const val BANDS = 4
        private const val ROW_STEP = 8
        private const val COL_STEP = 8
        private const val C = 4 // coarse factor
        /** below this (luma levels) nothing changed */
        const val STATIC_SAD = 0.35f
        /** best shift must explain the change this much better than zero */
        const val RATIO = 0.6f
        /** above this the frames do not match at any shift (cut, video) */
        const val MAX_SAD = 6f
    }
}

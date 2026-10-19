package io.github.veilfilter

import kotlin.math.abs
import kotlin.math.max

/**
 * Re-validates an occluded track (full-screen capture: our censor is in the
 * frame, so the detector cannot see under it) using the UNCOVERED ring of
 * content just outside the censor.
 *
 * When the track is last confirmed by the detector we record the luma of
 * [N] points on a ring around the box, in frame coordinates, together with the
 * cumulative global scroll at that moment. Later, the same points moved by the
 * scroll accumulated since then must still show the same content. If they
 * don't (the scroll estimate drifted, the page changed, an app switch...), the
 * track is no longer trustworthy and is released so the detector can look.
 */
class RingSignature {
    val xs = FloatArray(N)
    val ys = FloatArray(N)
    val luma = IntArray(N)
    val valid = BooleanArray(N)
    var cumX = 0.0
    var cumY = 0.0
    var has = false
    var mismatches = 0

    companion object { const val N = 24 }
}

object RingCheck {
    /** Minimum number of usable ring points for a decision. */
    const val MIN_POINTS = 8
    /** Mean abs luma difference above which the surroundings changed. */
    const val MAX_DIFF = 28f

    private fun inRects(x: Float, y: Float, rects: FloatArray, n: Int): Boolean {
        for (k in 0 until n) {
            if (x >= rects[4 * k] && x <= rects[4 * k + 2] && y >= rects[4 * k + 1] && y <= rects[4 * k + 3]) return true
        }
        return false
    }

    /** 3x3 mean of the green channel around (x, y), or -1 if outside. */
    private fun sample(src: ByteArray, w: Int, h: Int, stride: Int, x: Float, y: Float): Int {
        val xi = x.toInt(); val yi = y.toInt()
        if (xi < 1 || yi < 1 || xi >= w - 1 || yi >= h - 1) return -1
        var s = 0
        for (dy in -1..1) {
            val row = (yi + dy) * stride + 1
            for (dx in -1..1) s += src[row + (xi + dx) * 4].toInt() and 0xFF
        }
        return s / 9
    }

    /** Ring point i around box (x1,y1)-(x2,y2) at distance [m]. */
    private fun point(i: Int, x1: Float, y1: Float, x2: Float, y2: Float, m: Float, out: FloatArray) {
        val l = x1 - m; val r = x2 + m; val t = y1 - m; val b = y2 + m
        when {
            i < 7 -> { out[0] = l + (r - l) * (i + 0.5f) / 7f; out[1] = t }            // top
            i < 14 -> { out[0] = l + (r - l) * (i - 7 + 0.5f) / 7f; out[1] = b }       // bottom
            i < 19 -> { out[0] = l; out[1] = t + (b - t) * (i - 14 + 0.5f) / 5f }      // left
            else -> { out[0] = r; out[1] = t + (b - t) * (i - 19 + 0.5f) / 5f }        // right
        }
    }

    private val tmp = FloatArray(2)

    fun margin(x1: Float, y1: Float, x2: Float, y2: Float) = max(6f, 0.08f * max(x2 - x1, y2 - y1))

    /** Record the ring around a box that was just confirmed by the detector. Capture thread only. */
    fun record(
        sig: RingSignature, src: ByteArray, w: Int, h: Int, stride: Int,
        x1: Float, y1: Float, x2: Float, y2: Float,
        excl: FloatArray, nExcl: Int, cumX: Double, cumY: Double,
    ) {
        val m = margin(x1, y1, x2, y2)
        for (i in 0 until RingSignature.N) {
            point(i, x1, y1, x2, y2, m, tmp)
            sig.xs[i] = tmp[0]; sig.ys[i] = tmp[1]
            val v = if (inRects(tmp[0], tmp[1], excl, nExcl)) -1 else sample(src, w, h, stride, tmp[0], tmp[1])
            sig.luma[i] = v
            sig.valid[i] = v >= 0
        }
        sig.cumX = cumX; sig.cumY = cumY
        sig.has = true
        sig.mismatches = 0
    }

    /**
     * Mean abs difference of the ring now (moved by the scroll since it was
     * recorded) vs the recording, or NaN if too few points are usable.
     */
    fun diff(
        sig: RingSignature, src: ByteArray, w: Int, h: Int, stride: Int,
        excl: FloatArray, nExcl: Int, cumX: Double, cumY: Double,
    ): Float {
        if (!sig.has) return Float.NaN
        val ox = (cumX - sig.cumX).toFloat()
        val oy = (cumY - sig.cumY).toFloat()
        var s = 0; var n = 0
        for (i in 0 until RingSignature.N) {
            if (!sig.valid[i]) continue
            val x = sig.xs[i] + ox; val y = sig.ys[i] + oy
            if (inRects(x, y, excl, nExcl)) continue
            val v = sample(src, w, h, stride, x, y)
            if (v < 0) continue
            s += abs(v - sig.luma[i]); n++
        }
        return if (n < MIN_POINTS) Float.NaN else s.toFloat() / n
    }
}

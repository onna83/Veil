package io.github.veilfilter

import kotlin.math.abs

/**
 * Cheap scene-change detector. Every frame it sub-samples the capture into a
 * GRID x GRID raster of cells (a few sample points per cell), giving an
 * average colour and luma per cell, and compares luma with the previous
 * frame. Costs ~GRID*GRID*SAMPLES^2 pixel reads (16k for 32x32x4x4), i.e.
 * far cheaper than inference.
 *
 * When more than [triggerFraction] of cells change by more than
 * [lumaDelta], [analyze] returns true and [changedL..changedB] give the
 * bounding box (in cells, inclusive) of the changed cells. [cellColor]
 * holds the per-cell average colours of the CURRENT frame, used to draw the
 * guard pixelation (cells are rendered as solid blocks, so when our own
 * guard overlay is captured again in full-screen mode it reproduces almost
 * exactly the same cell averages and does not re-trigger itself).
 */
class SceneGuard(
    val grid: Int = 32,
    private val samples: Int = 4,
    var lumaDelta: Int = 28,
    var triggerFraction: Float = 0.18f,
) {
    val cellColor = IntArray(grid * grid) // 0xAARRGGBB
    private var cur = IntArray(grid * grid)
    private var prev = IntArray(grid * grid)
    private var hasPrev = false
    val changed = BooleanArray(grid * grid)

    var changedFraction = 0f
        private set
    var changedL = 0; private set
    var changedT = 0; private set
    var changedR = 0; private set
    var changedB = 0; private set

    fun reset() { hasPrev = false }

    /** Cell bounds in source pixels: cell i spans [i*w/grid, (i+1)*w/grid). */
    fun cellX(i: Int, w: Int): Float = i.toFloat() * w / grid
    fun cellY(j: Int, h: Int): Float = j.toFloat() * h / grid

    /**
     * @param excl rectangles [x1,y1,x2,y2]*n (frame px) to ignore, e.g. our own
     *   censors in full-screen capture; cells whose centre lies inside one are
     *   not counted in this frame's comparison.
     */
    fun analyze(src: ByteArray, w: Int, h: Int, rowStride: Int, excl: FloatArray = NONE, nExcl: Int = 0): Boolean {
        val g = grid
        val s = samples
        val n = s * s
        for (cj in 0 until g) {
            for (ci in 0 until g) {
                var r = 0; var gg = 0; var b = 0
                for (sj in 0 until s) {
                    // sample points at cell interior: (k + 0.5) / s
                    val y = (((cj * s + sj) * 2 + 1).toLong() * h / (2L * g * s)).toInt()
                    val row = y * rowStride
                    for (si in 0 until s) {
                        val x = (((ci * s + si) * 2 + 1).toLong() * w / (2L * g * s)).toInt()
                        val p = row + x * 4
                        r += src[p].toInt() and 0xFF
                        gg += src[p + 1].toInt() and 0xFF
                        b += src[p + 2].toInt() and 0xFF
                    }
                }
                r /= n; gg /= n; b /= n
                val idx = cj * g + ci
                cellColor[idx] = (0xFF shl 24) or (r shl 16) or (gg shl 8) or b
                cur[idx] = (r * 77 + gg * 150 + b * 29) shr 8
            }
        }
        var triggered = false
        if (hasPrev) {
            var cnt = 0
            var valid = 0
            var l = g; var t = g; var rr = -1; var bb = -1
            for (cj in 0 until g) for (ci in 0 until g) {
                val idx = cj * g + ci
                if (nExcl > 0 && excluded((ci + 0.5f) * w / g, (cj + 0.5f) * h / g, excl, nExcl)) {
                    changed[idx] = false
                    continue
                }
                valid++
                val c = abs(cur[idx] - prev[idx]) > lumaDelta
                changed[idx] = c
                if (c) {
                    cnt++
                    if (ci < l) l = ci
                    if (ci > rr) rr = ci
                    if (cj < t) t = cj
                    if (cj > bb) bb = cj
                }
            }
            changedFraction = if (valid == 0) 0f else cnt.toFloat() / valid
            if (changedFraction >= triggerFraction) {
                triggered = true
                changedL = l; changedT = t; changedR = rr; changedB = bb
            }
        } else {
            changedFraction = 0f
            changed.fill(false)
        }
        val tmp = prev; prev = cur; cur = tmp
        hasPrev = true
        return triggered
    }

    private fun excluded(x: Float, y: Float, r: FloatArray, n: Int): Boolean {
        for (k in 0 until n) if (x >= r[4 * k] && x <= r[4 * k + 2] && y >= r[4 * k + 1] && y <= r[4 * k + 3]) return true
        return false
    }

    companion object { private val NONE = FloatArray(0) }
}

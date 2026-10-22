package io.github.veilfilter

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Publish-time box geometry for one track (pulled out of Pipeline in v1.8.1 so
 * the jitter replay in tools/ runs the exact production code): prediction to
 * the capture time, padding, motion lead, block grid and frame clamp.
 */
object BoxShaper {
    const val PAD = 0.10f
    const val MIN_PAD = 3f
    const val LEAD_PAD_GLOBAL_MS = 20f
    /** residual speed (px/ms) below which no motion lead padding is added */
    const val LEAD_MIN_SPEED = 0.05f
    /** grid index marker: block grid anchored to the (moving) box itself */
    const val ANCHORED = Int.MIN_VALUE + 1

    /** per-track grid memory (published block edges), for hysteresis */
    class Grid {
        var cols = 0; var rows = 0
        /** content-anchored block indices of the last snapped edges */
        var ix1 = 0; var ix2 = 0; var iy1 = 0; var iy2 = 0
        var snapped = false
    }

    class Shape {
        var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
        /** velocity handed to the renderer (object + page), px/ms */
        var vx = 0f; var vy = 0f
        var cols = 1; var rows = 1
        /** grid indices (content-anchored still boxes), [ANCHORED], or Int.MIN_VALUE (none) */
        var gix = Int.MIN_VALUE; var giy = Int.MIN_VALUE
        var snap = false
    }

    /**
     * @param grid the track's grid memory (updated); null = none
     * @return false if the box is degenerate (off frame)
     */
    fun shape(
        tr: Tracker.Track, tCap: Long, maxExtrapolateMs: Long, occluded: Boolean,
        gvx: Float, gvy: Float, detAgeMs: Float, blockPx: Float, cumX: Double, cumY: Double,
        fw: Float, fh: Float, grid: Grid?, tu: MotionTuning, out: Shape,
    ): Boolean {
        val prevCols = grid?.cols ?: 0; val prevRows = grid?.rows ?: 0
        // residual (object) motion + global (page) motion
        out.vx = (if (occluded) 0f else tr.vx) + gvx
        out.vy = (if (occluded) 0f else tr.vy) + gvy
        var x1 = tr.predictX1(tCap, maxExtrapolateMs); var y1 = tr.predictY1(tCap, maxExtrapolateMs)
        var x2 = tr.predictX2(tCap, maxExtrapolateMs); var y2 = tr.predictY2(tCap, maxExtrapolateMs)
        val bw = x2 - x1; val bh = y2 - y1
        val px = bw * PAD + MIN_PAD
        val py = bh * PAD + MIN_PAD
        // pad ahead of the motion: positions are already predicted over the detector
        // latency, so the pad only covers velocity error: half the age, bounded
        val padMs = (0.5f * detAgeMs).coerceIn(15f, 60f)
        // object-motion lead only for real motion (velocity noise must not breathe the box)
        val moving = !occluded && tr.speed() > LEAD_MIN_SPEED
        val rvx = if (moving) tr.vx else 0f; val rvy = if (moving) tr.vy else 0f
        val leadX = rvx * padMs + gvx * LEAD_PAD_GLOBAL_MS
        val leadY = rvy * padMs + gvy * LEAD_PAD_GLOBAL_MS
        x1 -= px + max(-leadX, 0f); x2 += px + max(leadX, 0f)
        y1 -= py + max(-leadY, 0f); y2 += py + max(leadY, 0f)
        val bpx = blockPx.coerceAtLeast(2f)
        // still tracks: snap outward to a block grid anchored to the CONTENT (moves with
        // the scroll): blocks stay glued to what they cover, box resizes in whole blocks
        val snap = !tr.moving
        out.snap = snap
        out.gix = Int.MIN_VALUE; out.giy = Int.MIN_VALUE
        var anchored = false
        if (snap && !tu.legacy && grid != null) {
            // v1.8.1: snapped edges with block hysteresis. v1.8 re-snapped every frame, so an
            // edge hovering near a grid line flipped a whole block (detector noise, tile vs
            // full scale): the still box breathed by 16 dp several times a second. Now an
            // edge keeps its block unless the box grows past it (cover at once) or the box
            // is clearly (1 + hysteresis blocks) smaller.
            val gox = (cumX % bpx).toFloat(); val goy = (cumY % bpx).toFloat()
            val bx = floor(cumX / bpx).toInt(); val by = floor(cumY / bpx).toInt()
            val keep = grid.snapped
            val h = 1f + tu.blockHysteresis
            val n1 = edgeLo((x1 - gox) / bpx, if (keep) grid.ix1 + bx else null, h)
            val n2 = edgeHi((x2 - gox) / bpx, if (keep) grid.ix2 + bx else null, h)
            val m1 = edgeLo((y1 - goy) / bpx, if (keep) grid.iy1 + by else null, h)
            val m2 = edgeHi((y2 - goy) / bpx, if (keep) grid.iy2 + by else null, h)
            x1 = gox + n1 * bpx; x2 = gox + n2 * bpx; y1 = goy + m1 * bpx; y2 = goy + m2 * bpx
            grid.ix1 = n1 - bx; grid.ix2 = n2 - bx; grid.iy1 = m1 - by; grid.iy2 = m2 - by
            out.gix = n1 - bx; out.giy = m1 - by
        } else if (snap) {
            val gox = (cumX % bpx).toFloat(); val goy = (cumY % bpx).toFloat()
            x1 = Pixelator.floorTo(x1, gox, bpx); y1 = Pixelator.floorTo(y1, goy, bpx)
            x2 = Pixelator.ceilTo(x2, gox, bpx); y2 = Pixelator.ceilTo(y2, goy, bpx)
            out.gix = kotlin.math.round((x1 - gox) / bpx).toInt() - floor(cumX / bpx).toInt()
            out.giy = kotlin.math.round((y1 - goy) / bpx).toInt() - floor(cumY / bpx).toInt()
        } else if (tu.anchoredBlocks) {
            // v1.8.1 moving tracks: whole blocks around the box centre with hysteresis, so
            // the pixel pattern moves rigidly with the box instead of being re-cut (and
            // re-counted) every frame. Always covers the padded box.
            val c = blockCount(prevCols, (x2 - x1) / bpx, tu.blockHysteresis)
            val r = blockCount(prevRows, (y2 - y1) / bpx, tu.blockHysteresis)
            if (c in 1..Pixelator.MAX_BLOCKS && r in 1..Pixelator.MAX_BLOCKS) {
                val cx = (x1 + x2) * 0.5f; val cy = (y1 + y2) * 0.5f
                x1 = cx - c * bpx * 0.5f; x2 = x1 + c * bpx
                y1 = cy - r * bpx * 0.5f; y2 = y1 + r * bpx
                out.cols = c; out.rows = r
                out.gix = ANCHORED; out.giy = ANCHORED
                anchored = true
            }
        }
        val cx1 = max(x1, 0f); val cy1 = max(y1, 0f); val cx2 = min(x2, fw); val cy2 = min(y2, fh)
        if (cx2 - cx1 < 1f || cy2 - cy1 < 1f) return false
        if (!anchored || cx1 != x1 || cx2 != x2) out.cols = Pixelator.blocksOf(cx2 - cx1, bpx)
        if (!anchored || cy1 != y1 || cy2 != y2) out.rows = Pixelator.blocksOf(cy2 - cy1, bpx)
        out.x1 = cx1; out.y1 = cy1; out.x2 = cx2; out.y2 = cy2
        if (grid != null) {
            grid.cols = out.cols; grid.rows = out.rows
            grid.snapped = snap && !tu.legacy
        }
        return true
    }

    /** left/top edge in grid units: floor, or keep [prev] while it still covers and is not far outside */
    fun edgeLo(f: Float, prev: Int?, h: Float): Int {
        val n = floor(f + 1e-4f).toInt()
        if (prev == null) return n
        return if (prev <= f + 1e-4f && f - prev < h) prev else n
    }

    /** right/bottom edge in grid units: ceil, or keep [prev] likewise */
    fun edgeHi(f: Float, prev: Int?, h: Float): Int {
        val n = ceil(f - 1e-4f).toInt()
        if (prev == null) return n
        return if (prev >= f - 1e-4f && prev - f < h) prev else n
    }

    /** smallest block count covering [f] blocks; keeps [prev] unless f grew past it or fell [hyst] below prev - 1 */
    fun blockCount(prev: Int, f: Float, hyst: Float): Int {
        val need = max(1, ceil(f - 1e-3f).toInt())
        if (prev <= 0 || need > prev) return need
        return if (f < prev - 1 - hyst) need else prev
    }
}

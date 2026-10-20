package io.github.veilfilter

import kotlin.math.max
import kotlin.math.min

/**
 * Appearance guard: covers NEW skin-coloured content before the detector has
 * looked at it, so first coverage costs ~one capture frame + overlay time
 * instead of a full inference round trip.
 *
 * Every frame the capture is reduced to a grid of [cell]-px cells (3x3
 * samples each, read row by row) holding the fraction of skin-tone samples.
 * The detector's view is a REFERENCE grid: the skin grid of the last frame the
 * detector processed, moved with the page scroll since. A cell is "new skin"
 * when it is clearly skin now and was clearly less skin in the reference:
 * i.e. skin appeared since the detector last looked. New-skin cells are
 * grouped (8-connected), small groups are ignored (moving edges of an already
 * checked face/arm), the rest become padded boxes that stay up until a
 * detector result for that frame or newer arrives (and at least [minOnMs]).
 *
 * Does not fire on static content (nothing new vs the reference), ignores
 * cells under our own censors (masked rects) and inside current tracks.
 * Capture thread only.
 */
class AppearanceGuard(
    val cell: Int = 12,
    var skinMin: Float = 0.40f,
    var deltaMin: Float = 0.30f,
    var minCells: Int = 4,
    var minOnMs: Long = 80,
    var maxBoxes: Int = 4,
    /**
     * Motion tolerance (cells): skin counts as "new" only if the detector saw no
     * skin within this radius. Without it, the leading edge of a moving face or
     * arm (already checked, nothing to censor) re-triggered the guard every few
     * frames in reels; that is what pixelated faces with Faces disabled (v1.4).
     */
    var motionCells: Int = 3,
) {
    var cols = 0; private set
    var rows = 0; private set
    private var w = 0
    private var h = 0
    /** skin fraction per cell of the current frame, -1 = masked */
    var skin = FloatArray(0); private set
    private var ref = FloatArray(0)
    /** [ref] max-filtered over +-[motionCells] (masked = 1) */
    private var refDil = FloatArray(0)
    private var tmpDil = FloatArray(0)
    private var refValid = false
    private var refCumX = 0.0
    private var refCumY = 0.0
    private var newCell = BooleanArray(0)
    private var label = IntArray(0)
    private var stack = IntArray(0)

    class Box(val id: Int, var x1: Float, var y1: Float, var x2: Float, var y2: Float, var lastTriggerFrame: Long, val shownAt: Long)
    private var nextId = 0
    val boxes = ArrayList<Box>()

    // stats
    var lastNewCells = 0; private set
    var lastMs = 0f; private set
    /** boxes created since start (for a rate in the HUD) */
    var triggers = 0; private set

    fun reset() {
        refValid = false
        boxes.clear()
    }

    private fun alloc(fw: Int, fh: Int) {
        w = fw; h = fh
        cols = max(1, fw / cell); rows = max(1, fh / cell)
        val n = cols * rows
        skin = FloatArray(n); ref = FloatArray(n); refDil = FloatArray(n); tmpDil = FloatArray(n)
        newCell = BooleanArray(n); label = IntArray(n); stack = IntArray(n)
        refValid = false
        boxes.clear()
    }

    /** Copy of the current skin grid, for the detector slot. */
    fun snapshotInto(dst: FloatArray): FloatArray {
        val d = if (dst.size == skin.size) dst else FloatArray(skin.size)
        System.arraycopy(skin, 0, d, 0, skin.size)
        return d
    }

    /**
     * The detector finished the frame whose skin grid was [grid] (captured at
     * cumulative scroll [cumX],[cumY]). It becomes the new reference, and boxes
     * triggered at or before [frameId] are released (after [minOnMs]).
     */
    fun onDetected(
        grid: FloatArray, cumX: Double, cumY: Double, frameId: Long, nowMs: Long,
        /** region the detector looked at (frame px of that frame); default whole frame */
        rx1: Float = 0f, ry1: Float = 0f, rx2: Float = Float.MAX_VALUE, ry2: Float = Float.MAX_VALUE,
    ) {
        if (grid.size != ref.size) return
        val whole = rx1 <= 0f && ry1 <= 0f && rx2 >= w && ry2 >= h
        if (whole || !refValid) {
            System.arraycopy(grid, 0, ref, 0, grid.size)
            refCumX = cumX; refCumY = cumY
            refValid = true
        } else {
            // tile pass: only the cells it covered become "checked"; bring them into
            // the reference's coordinate frame (the reference may be from another scroll offset)
            val ox = ((refCumX - cumX) / cell).toFloat()
            val oy = ((refCumY - cumY) / cell).toFloat()
            for (j in 0 until rows) for (i in 0 until cols) {
                val si = i - ox; val sj = j - oy           // cell of [grid] that is now at (i, j) in ref coords
                val cx = (si + 0.5f) * cell; val cy = (sj + 0.5f) * cell
                if (cx < rx1 || cx > rx2 || cy < ry1 || cy > ry2) continue
                val gi = si.toInt(); val gj = sj.toInt()
                if (gi < 0 || gj < 0 || gi >= cols || gj >= rows) continue
                ref[j * cols + i] = grid[gj * cols + gi]
            }
        }
        dilate()
        // release a box once the detector has seen its content: either the
        // detected frame is at least as new as the box's last trigger, or the
        // current frame has nothing "new" left inside the box vs the new reference
        val sx = (lastCumX - cumX).toFloat(); val sy = (lastCumY - cumY).toFloat()
        boxes.removeAll { b ->
            val inside = whole || (b.x1 - sx >= rx1 && b.x2 - sx <= rx2 && b.y1 - sy >= ry1 && b.y2 - sy <= ry2)
            nowMs - b.shownAt >= minOnMs && ((inside && b.lastTriggerFrame <= frameId) || newCellsIn(b) < minCells)
        }
    }

    private fun dilate() {
        val r = motionCells
        // rows then columns (separable max filter); masked (-1) counts as checked skin
        for (j in 0 until rows) {
            val o = j * cols
            for (i in 0 until cols) {
                var m = 0f
                for (d in max(0, i - r)..min(cols - 1, i + r)) {
                    val v = ref[o + d]; val vv = if (v < 0f) 1f else v
                    if (vv > m) m = vv
                }
                tmpDil[o + i] = m
            }
        }
        for (i in 0 until cols) for (j in 0 until rows) {
            var m = 0f
            for (d in max(0, j - r)..min(rows - 1, j + r)) { val v = tmpDil[d * cols + i]; if (v > m) m = v }
            refDil[j * cols + i] = m
        }
    }

    private var lastCumX = 0.0
    private var lastCumY = 0.0

    private fun newCellsIn(b: Box): Int {
        if (!refValid || skin.isEmpty()) return 0
        val ox = ((lastCumX - refCumX) / cell).toFloat()
        val oy = ((lastCumY - refCumY) / cell).toFloat()
        val i0 = max(0, (b.x1 / cell).toInt()); val i1 = min(cols - 1, (b.x2 / cell).toInt())
        val j0 = max(0, (b.y1 / cell).toInt()); val j1 = min(rows - 1, (b.y2 / cell).toInt())
        var n = 0
        for (j in j0..j1) for (i in i0..i1) {
            val sv = skin[j * cols + i]
            if (sv >= skinMin && sv - refAt(i - ox, j - oy) >= deltaMin) n++
        }
        return n
    }

    /** Move existing boxes with the page. */
    fun applyShift(dx: Float, dy: Float) {
        for (b in boxes) { b.x1 += dx; b.x2 += dx; b.y1 += dy; b.y2 += dy }
        boxes.removeAll { it.x2 < 0 || it.y2 < 0 || it.x1 > w || it.y1 > h }
    }

    /**
     * @param excl rects to ignore (our censors in full-screen capture) [x1,y1,x2,y2]*n
     * @param tracks rects already censored by tracks
     */
    fun analyze(
        src: ByteArray, fw: Int, fh: Int, stride: Int,
        excl: FloatArray, nExcl: Int, tracks: FloatArray, nTracks: Int,
        cumX: Double, cumY: Double, frameId: Long, nowMs: Long,
        /** false: only build the grid (e.g. while scrolling: no pre-emptive covering) */
        trigger: Boolean = true,
    ) {
        val t0 = System.nanoTime()
        if (fw != w || fh != h || skin.isEmpty()) alloc(fw, fh)
        buildGrid(src, stride)
        mask(excl, nExcl)
        lastCumX = cumX; lastCumY = cumY
        if (!trigger) {
            boxes.clear()
            lastNewCells = 0
            lastMs = (System.nanoTime() - t0) / 1e6f
            return
        }
        // find new skin
        var nNew = 0
        val ox = ((cumX - refCumX) / cell).toFloat()
        val oy = ((cumY - refCumY) / cell).toFloat()
        for (j in 0 until rows) for (i in 0 until cols) {
            val k = j * cols + i
            val s = skin[k]
            var isNew = false
            if (s >= skinMin) {
                val r = if (refValid) refAt(i - ox, j - oy) else 0f
                isNew = s - r >= deltaMin
                if (isNew && nTracks > 0 && inRects((i + 0.5f) * cell, (j + 0.5f) * cell, tracks, nTracks)) isNew = false
            }
            newCell[k] = isNew
            if (isNew) nNew++
        }
        lastNewCells = nNew
        if (nNew >= minCells) groupAndAdd(frameId, nowMs)
        lastMs = (System.nanoTime() - t0) / 1e6f
    }

    private fun buildGrid(src: ByteArray, stride: Int) {
        java.util.Arrays.fill(skin, 0f)
        // 3 sample rows per cell row, 3 samples per cell along each row: sequential reads
        for (j in 0 until rows) {
            for (sj in 0 until 3) {
                val y = j * cell + (cell * (2 * sj + 1)) / 6
                val row = y * stride
                for (i in 0 until cols) {
                    var c = 0
                    for (si in 0 until 3) {
                        val x = i * cell + (cell * (2 * si + 1)) / 6
                        val p = row + x * 4
                        if (isSkin(src[p].toInt() and 0xFF, src[p + 1].toInt() and 0xFF, src[p + 2].toInt() and 0xFF)) c++
                    }
                    skin[j * cols + i] += c.toFloat()
                }
            }
        }
        for (k in skin.indices) skin[k] /= 9f
    }

    private fun mask(excl: FloatArray, n: Int) {
        if (n == 0) return
        for (q in 0 until n) {
            val i0 = max(0, (excl[4 * q] / cell).toInt()); val i1 = min(cols - 1, (excl[4 * q + 2] / cell).toInt())
            val j0 = max(0, (excl[4 * q + 1] / cell).toInt()); val j1 = min(rows - 1, (excl[4 * q + 3] / cell).toInt())
            for (j in j0..j1) for (i in i0..i1) skin[j * cols + i] = -1f
        }
    }

    /** Bilinear lookup in the reference grid; outside / masked = unknown -> 0 (treat as new). */
    private fun refAt(fi: Float, fj: Float): Float {
        val i0 = kotlin.math.floor(fi).toInt(); val j0 = kotlin.math.floor(fj).toInt()
        val ax = fi - i0; val ay = fj - j0
        fun g(i: Int, j: Int): Float {
            if (i < 0 || j < 0 || i >= cols || j >= rows) return 0f
            return refDil[j * cols + i] // masked cells already count as 1
        }
        return g(i0, j0) * (1 - ax) * (1 - ay) + g(i0 + 1, j0) * ax * (1 - ay) +
            g(i0, j0 + 1) * (1 - ax) * ay + g(i0 + 1, j0 + 1) * ax * ay
    }

    private fun groupAndAdd(frameId: Long, nowMs: Long) {
        java.util.Arrays.fill(label, 0)
        var next = 0
        for (start in newCell.indices) {
            if (!newCell[start] || label[start] != 0) continue
            next++
            var sp = 0
            stack[sp++] = start; label[start] = next
            var cnt = 0
            var minI = cols; var minJ = rows; var maxI = -1; var maxJ = -1
            while (sp > 0) {
                val k = stack[--sp]
                cnt++
                val i = k % cols; val j = k / cols
                if (i < minI) minI = i; if (i > maxI) maxI = i
                if (j < minJ) minJ = j; if (j > maxJ) maxJ = j
                for (dj in -1..1) for (di in -1..1) {
                    val ni = i + di; val nj = j + dj
                    if (ni < 0 || nj < 0 || ni >= cols || nj >= rows) continue
                    val nk = nj * cols + ni
                    if (newCell[nk] && label[nk] == 0) { label[nk] = next; stack[sp++] = nk }
                }
            }
            // thin slivers (moving edges) are not new objects
            if (cnt < minCells || maxI - minI < 1 || maxJ - minJ < 1) continue
            val x1 = max(0f, ((minI - 1) * cell).toFloat()); val y1 = max(0f, ((minJ - 1) * cell).toFloat())
            val x2 = min(w.toFloat(), ((maxI + 2) * cell).toFloat()); val y2 = min(h.toFloat(), ((maxJ + 2) * cell).toFloat())
            addBox(x1, y1, x2, y2, frameId, nowMs)
        }
    }

    private fun addBox(x1: Float, y1: Float, x2: Float, y2: Float, frameId: Long, nowMs: Long) {
        for (b in boxes) {
            if (x1 <= b.x2 && x2 >= b.x1 && y1 <= b.y2 && y2 >= b.y1) {
                b.x1 = min(b.x1, x1); b.y1 = min(b.y1, y1); b.x2 = max(b.x2, x2); b.y2 = max(b.y2, y2)
                b.lastTriggerFrame = frameId
                return
            }
        }
        if (boxes.size >= maxBoxes) {
            // too many: merge into the first box (cap the number of windows)
            val b = boxes[0]
            b.x1 = min(b.x1, x1); b.y1 = min(b.y1, y1); b.x2 = max(b.x2, x2); b.y2 = max(b.y2, y2)
            b.lastTriggerFrame = frameId
            return
        }
        boxes.add(Box(nextId++ and 0xFFFF, x1, y1, x2, y2, frameId, nowMs))
        triggers++
    }

    private fun inRects(x: Float, y: Float, r: FloatArray, n: Int): Boolean {
        for (k in 0 until n) if (x >= r[4 * k] && x <= r[4 * k + 2] && y >= r[4 * k + 1] && y <= r[4 * k + 3]) return true
        return false
    }

    companion object {
        /**
         * Skin-tone test in YCbCr (BT.601, integer), widened from the classic
         * Chai-Ngan box so that very dark and very light skin both pass:
         * chroma near the skin locus (Cr above neutral, Cb at or below
         * neutral), R >= G >= B ordering with a minimum warmth, and not
         * near-black.
         */
        fun isSkin(r: Int, g: Int, b: Int): Boolean {
            if (r < 30 || r < g || r <= b) return false
            if (g * 10 < b * 8) return false            // bluish
            if ((r - b) * 100 < r * 5 + 300) return false // too grey (r-b >= 5% r + 3)
            if (r - g > (r shr 1) + 20) return false      // saturated red/orange
            val cb = 128 + ((-43 * r - 85 * g + 128 * b) shr 8)
            val cr = 128 + ((128 * r - 107 * g - 21 * b) shr 8)
            return cr in 129..180 && cb in 77..130
        }
    }
}

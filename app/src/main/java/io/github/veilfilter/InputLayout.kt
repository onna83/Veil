package io.github.veilfilter

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * v1.8: where source pixels go in the (possibly non-square) model input.
 *
 * A layout is one or two [Part]s, each a source rectangle copied at ONE
 * uniform scale to a destination offset; everything else is black padding.
 *  - [fit]: the NudeNet letterbox generalised to W x H: scale so the source
 *    fits, top-left aligned, pad right/bottom. For a square input this is
 *    exactly NudeNet's own pre-processing (pad to max(w,h), resize).
 *  - [fold]: for a LANDSCAPE frame on a PORTRAIT model (full-screen video):
 *    the frame is cut into a left and a right half (with a small overlap so a
 *    body on the seam is still seen whole by one half) and the halves are
 *    stacked vertically. Content stays upright (NudeNet is not rotation
 *    invariant, so rotating the frame would cost recall), the model does not
 *    have to be reloaded when the phone rotates (a GPU re-init would leave
 *    a gap), and the scale is about the same as a dedicated landscape model.
 *
 * Mapping back ([toSource]) uses the same convention as v1.7's square path:
 * source = part origin + (model px - part offset) / scale.
 */
class InputLayout(val outW: Int, val outH: Int, val parts: List<Part>) {
    class Part(
        /** source rectangle (frame pixels) */
        val sx: Int, val sy: Int, val sw: Int, val sh: Int,
        /** destination offset in the model input */
        val dx: Int, val dy: Int,
        /** source px per model px (exact, as v1.7's side / size) */
        val inv: Double,
    ) {
        /** model px per source px */
        val scale: Float = (1.0 / inv).toFloat()
        /** destination columns / rows that sample this part */
        val dw: Int = span(sw, inv)
        val dh: Int = span(sh, inv)
    }

    /** smallest scale of all parts (model px per source px) */
    val minScale: Float get() = parts.minOf { it.scale }

    /** fraction of the model input that carries image content */
    val fill: Float get() = parts.sumOf { (min(it.dw, outW - it.dx) * min(it.dh, outH - it.dy)).toDouble() }.toFloat() / (outW * outH)

    val folded: Boolean get() = parts.size > 1

    /** cache key: same key = same sampling tables */
    val key: String = buildString {
        append(outW).append('x').append(outH)
        for (p in parts) append('|').append(p.sx).append(',').append(p.sy).append(',').append(p.sw).append(',').append(p.sh)
            .append('>').append(p.dx).append(',').append(p.dy).append('@').append(p.inv)
    }

    /** part a model-input point belongs to (by destination row band), or -1 */
    fun partAt(mx: Float, my: Float): Int {
        for ((i, p) in parts.withIndex()) {
            if (mx >= p.dx && mx < p.dx + p.dw + 1 && my >= p.dy && my < p.dy + p.dh + 1) return i
        }
        return if (parts.size == 1) 0 else -1
    }

    /**
     * Model-input box -> source (frame) box, clipped to its part's source
     * rectangle. The part is chosen by the box centre. [out] = x1,y1,x2,y2.
     * @return part index, or -1 if the box lies in padding only
     */
    fun toSource(x1: Float, y1: Float, x2: Float, y2: Float, out: FloatArray): Int {
        val k = partAt((x1 + x2) * 0.5f, (y1 + y2) * 0.5f)
        if (k < 0) return -1
        val p = parts[k]
        val inv = p.inv.toFloat()
        // clip to the part's destination area first (a box may spill over the stacking seam)
        val cx1 = max(x1, p.dx.toFloat()); val cy1 = max(y1, p.dy.toFloat())
        val cx2 = min(x2, (p.dx + p.dw).toFloat()); val cy2 = min(y2, (p.dy + p.dh).toFloat())
        out[0] = (p.sx + (cx1 - p.dx) * inv).coerceIn(p.sx.toFloat(), (p.sx + p.sw).toFloat())
        out[1] = (p.sy + (cy1 - p.dy) * inv).coerceIn(p.sy.toFloat(), (p.sy + p.sh).toFloat())
        out[2] = (p.sx + (cx2 - p.dx) * inv).coerceIn(p.sx.toFloat(), (p.sx + p.sw).toFloat())
        out[3] = (p.sy + (cy2 - p.dy) * inv).coerceIn(p.sy.toFloat(), (p.sy + p.sh).toFloat())
        return k
    }

    companion object {
        /** fold overlap: each half is extended by this fraction of the frame width */
        const val FOLD_OVERLAP = 0.06f
        /** fold only if it beats the plain fit by this factor in scale */
        const val FOLD_MIN_GAIN = 1.25f

        /**
         * Number of destination pixels that sample a source extent [n] at [scale]
         * (cv2 INTER_LINEAR centre convention, same as v1.7's square letterbox).
         */
        fun span(n: Int, inv: Double): Int {
            if (n <= 0 || inv <= 0.0) return 0
            // largest d with floor((d + 0.5) * inv - 0.5) < n
            var d = max(0, floor((n + 0.5) / inv - 0.5).toInt() - 2)
            while (floor((d + 1 + 0.5) * inv - 0.5) < n) d++
            return d + 1
        }

        /** NudeNet letterbox into outW x outH: uniform scale, top-left, pad right/bottom. */
        fun fit(sx: Int, sy: Int, w: Int, h: Int, outW: Int, outH: Int): InputLayout {
            val inv = max(max(1, w).toDouble() / outW, max(1, h).toDouble() / outH)
            return InputLayout(outW, outH, listOf(Part(sx, sy, w, h, 0, 0, inv)))
        }

        fun fit(w: Int, h: Int, outW: Int, outH: Int) = fit(0, 0, w, h, outW, outH)

        /** Left and right half (with overlap) stacked top / bottom. Landscape frame, portrait input. */
        fun fold(w: Int, h: Int, outW: Int, outH: Int, overlap: Float = FOLD_OVERLAP): InputLayout {
            val half = min(w, (w + 1) / 2 + (overlap * w).toInt())
            val inv = max(half.toDouble() / outW, 2.0 * h / outH)
            val top = Part(0, 0, half, h, 0, 0, inv)
            val bottom = Part(w - half, 0, half, h, 0, min(top.dh, outH), inv)
            return InputLayout(outW, outH, listOf(top, bottom))
        }

        /**
         * Full-frame layout for a [w] x [h] frame: [fold] when the frame is
         * landscape, the input clearly portrait and folding gains real
         * resolution; otherwise [fit].
         */
        fun choose(w: Int, h: Int, outW: Int, outH: Int): InputLayout {
            val f = fit(w, h, outW, outH)
            if (w <= h || outH < outW * 3 / 2) return f
            val g = fold(w, h, outW, outH)
            return if (g.minScale >= f.minScale * FOLD_MIN_GAIN) g else f
        }
    }
}

/**
 * v1.8: a subject that straddles the seam of a [InputLayout.fold] layout is
 * seen (partly) by both halves; the halves overlap, so the two partial boxes
 * overlap in frame coordinates. Same class + intersection >= [MIN_OVERLAP] of
 * the smaller box = one subject: merged into the union, best score kept.
 */
object SeamMerge {
    const val MIN_OVERLAP = 0.5f

    /** in place; @return the new count */
    fun merge(
        x1: FloatArray, y1: FloatArray, x2: FloatArray, y2: FloatArray,
        score: FloatArray, cls: IntArray, part: IntArray, n: Int,
    ): Int {
        if (n < 2) return n
        val alive = BooleanArray(n) { true }
        for (i in 0 until n) {
            if (!alive[i]) continue
            var j = i + 1
            while (j < n) {
                if (alive[j] && part[i] != part[j] && cls[i] == cls[j]) {
                    val iw = min(x2[i], x2[j]) - max(x1[i], x1[j])
                    val ih = min(y2[i], y2[j]) - max(y1[i], y1[j])
                    if (iw > 0f && ih > 0f) {
                        val ai = (x2[i] - x1[i]) * (y2[i] - y1[i])
                        val aj = (x2[j] - x1[j]) * (y2[j] - y1[j])
                        if (iw * ih >= MIN_OVERLAP * min(ai, aj)) {
                            x1[i] = min(x1[i], x1[j]); y1[i] = min(y1[i], y1[j])
                            x2[i] = max(x2[i], x2[j]); y2[i] = max(y2[i], y2[j])
                            score[i] = max(score[i], score[j])
                            alive[j] = false
                        }
                    }
                }
                j++
            }
        }
        var k = 0
        for (i in 0 until n) {
            if (!alive[i]) continue
            if (k != i) {
                x1[k] = x1[i]; y1[k] = y1[i]; x2[k] = x2[i]; y2[k] = y2[i]
                score[k] = score[i]; cls[k] = cls[i]; part[k] = part[i]
            }
            k++
        }
        return k
    }
}

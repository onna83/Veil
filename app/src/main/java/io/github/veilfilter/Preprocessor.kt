package io.github.veilfilter

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * NudeNet-compatible letterbox + normalisation, written straight into the
 * model input array without intermediate bitmaps.
 *
 * NudeNet (nudenet.py `_read_image`): pad the image on the RIGHT and BOTTOM
 * with black to a square of side max(w, h), resize to 320x320
 * (cv2 INTER_LINEAR via blobFromImage), RGB, scale 1/255, no mean.
 * So model coordinate m maps back to source coordinate m * side / 320.
 *
 * v1.8: the input can be [outW] x [outH] (portrait models), and the source
 * placement is an [InputLayout] (letterbox fit, or the landscape fold: two
 * halves stacked). The square fit is sample-for-sample what v1.7 did.
 *
 * Source frame: RGBA_8888 bytes, rowStride bytes per row.
 * Output: NCHW (planar) or NHWC (interleaved) float array of size 3*W*H.
 * Sampling tables are rebuilt only when the layout changes.
 */
class Preprocessor(val outW: Int, val outH: Int, val nchw: Boolean = true) {
    /** v1.7 square constructor */
    constructor(size: Int = 320, nchw: Boolean = true) : this(size, size, nchw)

    val output = FloatArray(3 * outW * outH)

    /** v1.7 name: the (square) input side */
    val size: Int get() = outW

    private class Tables(p: InputLayout.Part, outW: Int, outH: Int) {
        val dx = p.dx; val dy = p.dy
        val cols = max(0, min(p.dw, outW - p.dx))
        val rows = max(0, min(p.dh, outH - p.dy))
        val x0 = IntArray(cols); val x1 = IntArray(cols); val wx = FloatArray(cols); val v1x = BooleanArray(cols)
        val y0 = IntArray(rows); val y1 = IntArray(rows); val wy = FloatArray(rows); val v1y = BooleanArray(rows)
        // the (virtually padded) canvas behind this part, in source px (v1.7: side x side)
        private val canvasX = Math.round((outW - p.dx) * p.inv).toInt()
        private val canvasY = Math.round((outH - p.dy) * p.inv).toInt()
        init {
            // cv2 INTER_LINEAR: src = (dst + 0.5) * inv - 0.5, clamped at the borders of the
            // (virtually padded) part; neighbours beyond the part are black padding
            for (d in 0 until cols) {
                val f = (d + 0.5) * p.inv - 0.5
                var i0 = floor(f).toInt()
                var a = (f - i0).toFloat()
                if (i0 < 0) { i0 = 0; a = 0f }
                var i1 = i0 + 1
                if (i1 > canvasX - 1) i1 = canvasX - 1 // edge of the padded canvas: replicate (cv2)
                x0[d] = p.sx + i0; x1[d] = p.sx + i1; wx[d] = a; v1x[d] = i1 < p.sw
            }
            for (d in 0 until rows) {
                val f = (d + 0.5) * p.inv - 0.5
                var i0 = floor(f).toInt()
                var a = (f - i0).toFloat()
                if (i0 < 0) { i0 = 0; a = 0f }
                var i1 = i0 + 1
                if (i1 > canvasY - 1) i1 = canvasY - 1
                y0[d] = p.sy + i0; y1[d] = p.sy + i1; wy[d] = a; v1y[d] = i1 < p.sh
            }
        }
    }

    private var key: String? = null
    private var tables: List<Tables> = emptyList()
    /** layout of the last [process] call */
    var layout: InputLayout? = null
        private set

    /** v1.7: padded square side in source pixels (single-part layouts) */
    val side: Int get() = layout?.parts?.firstOrNull()?.let { max(it.sw, it.sh) } ?: 0

    /** v1.7: source px per model px (single-part layouts) */
    val scale: Float get() = layout?.parts?.firstOrNull()?.inv?.toFloat() ?: 1f

    /** Fill [output] from the whole frame (NudeNet letterbox). Pixels outside the image (padding) are 0. */
    fun process(src: ByteArray, w: Int, h: Int, rowStride: Int) = process(src, w, h, rowStride, 0)

    /**
     * Same, for a [w] x [h] region starting at byte offset [base] of the frame
     * (base = y0 * rowStride + x0 * 4): used for tile passes.
     */
    fun process(src: ByteArray, w: Int, h: Int, rowStride: Int, base: Int) {
        val y0 = base / rowStride
        val x0 = (base - y0 * rowStride) / 4
        process(src, rowStride, InputLayout.fit(x0, y0, w, h, outW, outH))
    }

    /** Fill [output] from [src] (RGBA rows of [rowStride] bytes) placed as [lay]. */
    fun process(src: ByteArray, rowStride: Int, lay: InputLayout) {
        require(lay.outW == outW && lay.outH == outH) { "layout ${lay.outW}x${lay.outH} for a ${outW}x$outH input" }
        if (lay.key != key) {
            key = lay.key
            tables = lay.parts.map { Tables(it, outW, outH) }
            output.fill(0f)
        }
        layout = lay
        val plane = outW * outH
        val out = output
        val stride = rowStride
        for (t in tables) {
            for (dy in 0 until t.rows) {
                val sy0 = t.y0[dy]
                val sy1 = t.y1[dy]
                val fy = t.wy[dy]
                val row0 = sy0 * stride
                val row1 = sy1 * stride
                val valid1y = t.v1y[dy]
                val orow = (t.dy + dy) * outW + t.dx
                for (dx in 0 until t.cols) {
                    val fx = t.wx[dx]
                    val valid1x = t.v1x[dx]
                    val p00 = row0 + t.x0[dx] * 4
                    val p01 = row0 + t.x1[dx] * 4
                    val p10 = row1 + t.x0[dx] * 4
                    val p11 = row1 + t.x1[dx] * 4
                    var r = 0f; var g = 0f; var b = 0f
                    val w00 = (1f - fx) * (1f - fy)
                    val w01 = fx * (1f - fy)
                    val w10 = (1f - fx) * fy
                    val w11 = fx * fy
                    r += w00 * (src[p00].toInt() and 0xFF)
                    g += w00 * (src[p00 + 1].toInt() and 0xFF)
                    b += w00 * (src[p00 + 2].toInt() and 0xFF)
                    if (valid1x && w01 != 0f) {
                        r += w01 * (src[p01].toInt() and 0xFF)
                        g += w01 * (src[p01 + 1].toInt() and 0xFF)
                        b += w01 * (src[p01 + 2].toInt() and 0xFF)
                    }
                    if (valid1y && w10 != 0f) {
                        r += w10 * (src[p10].toInt() and 0xFF)
                        g += w10 * (src[p10 + 1].toInt() and 0xFF)
                        b += w10 * (src[p10 + 2].toInt() and 0xFF)
                        if (valid1x && w11 != 0f) {
                            r += w11 * (src[p11].toInt() and 0xFF)
                            g += w11 * (src[p11 + 1].toInt() and 0xFF)
                            b += w11 * (src[p11 + 2].toInt() and 0xFF)
                        }
                    }
                    val k = orow + dx
                    if (nchw) {
                        out[k] = r * INV255
                        out[plane + k] = g * INV255
                        out[2 * plane + k] = b * INV255
                    } else {
                        val j = k * 3
                        out[j] = r * INV255
                        out[j + 1] = g * INV255
                        out[j + 2] = b * INV255
                    }
                }
            }
        }
    }

    companion object {
        private const val INV255 = 1f / 255f
    }
}

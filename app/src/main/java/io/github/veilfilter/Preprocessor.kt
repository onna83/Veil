package io.github.veilfilter

import kotlin.math.floor
import kotlin.math.max

/**
 * NudeNet-compatible letterbox + normalisation, written straight into the
 * model input array without intermediate bitmaps.
 *
 * NudeNet (nudenet.py `_read_image`): pad the image on the RIGHT and BOTTOM
 * with black to a square of side max(w, h), resize to 320x320
 * (cv2 INTER_LINEAR via blobFromImage), RGB, scale 1/255, no mean.
 * So model coordinate m maps back to source coordinate m * side / 320.
 *
 * Source frame: RGBA_8888 bytes, [rowStride] bytes per row.
 * Output: NCHW (planar) or NHWC (interleaved) float array of size 3*S*S.
 * Sampling tables are rebuilt only when the source size changes.
 */
class Preprocessor(val size: Int = 320, val nchw: Boolean = true) {
    val output = FloatArray(3 * size * size)

    private var srcW = -1
    private var srcH = -1
    private var stride = -1
    /** padded square side in source pixels */
    var side = 0
        private set
    private var outW = 0 // number of output columns that touch the image
    private var outH = 0
    private val x0 = IntArray(size)
    private val x1 = IntArray(size)
    private val wx = FloatArray(size)
    private val y0 = IntArray(size)
    private val y1 = IntArray(size)
    private val wy = FloatArray(size)

    /** source px per model px */
    val scale: Float get() = side.toFloat() / size

    private fun setup(w: Int, h: Int, rowStride: Int) {
        srcW = w; srcH = h; stride = rowStride
        side = max(w, h)
        val s = side.toDouble() / size
        // cv2 INTER_LINEAR: src = (dst + 0.5) * scale - 0.5, clamped at borders.
        // The padded image is side x side; the image occupies [0,w) x [0,h).
        outW = 0
        outH = 0
        for (d in 0 until size) {
            val f = (d + 0.5) * s - 0.5
            var i0 = floor(f).toInt()
            var a = (f - i0).toFloat()
            if (i0 < 0) { i0 = 0; a = 0f }
            var i1 = i0 + 1
            if (i1 > side - 1) { i1 = side - 1 }
            if (i0 > side - 1) { i0 = side - 1; a = 0f }
            x0[d] = i0; x1[d] = i1; wx[d] = a
            if (i0 < w) outW = d + 1
            y0[d] = i0; y1[d] = i1; wy[d] = a
            if (i0 < h) outH = d + 1
        }
        output.fill(0f)
    }

    /**
     * Fill [output] from the frame. Pixels outside the image (padding) are 0.
     */
    fun process(src: ByteArray, w: Int, h: Int, rowStride: Int) {
        if (w != srcW || h != srcH || rowStride != stride) setup(w, h, rowStride)
        val plane = size * size
        val out = output
        val ww = srcW
        val hh = srcH
        for (dy in 0 until outH) {
            val sy0 = y0[dy]
            val sy1 = y1[dy]
            val fy = wy[dy]
            // rows beyond the image are black padding
            val row0 = sy0 * stride
            val row1 = sy1 * stride
            val valid1y = sy1 < hh
            for (dx in 0 until outW) {
                val sx0 = x0[dx]
                val sx1 = x1[dx]
                val fx = wx[dx]
                val valid1x = sx1 < ww
                val p00 = row0 + sx0 * 4
                val p01 = row0 + sx1 * 4
                val p10 = row1 + sx0 * 4
                val p11 = row1 + sx1 * 4
                var r = 0f; var g = 0f; var b = 0f
                // weights
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
                val k = dy * size + dx
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

    companion object {
        private const val INV255 = 1f / 255f
    }
}

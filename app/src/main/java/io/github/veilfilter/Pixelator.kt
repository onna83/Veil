package io.github.veilfilter

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** Pixel helpers on an RGBA_8888 frame copy (no allocation). */
object Pixelator {

    /** Number of blocks along an axis of [len] capture px. */
    fun blocksFor(len: Float, longSide: Float): Int {
        val blockPx = max(longSide / 7f, 8f)
        return min(max(ceil(len / blockPx).toInt(), 1), 16)
    }

    /**
     * Average each of cols x rows blocks of rect (x1,y1)-(x2,y2) using 3x3
     * sample points per block. Writes opaque 0xAARRGGBB into [out].
     */
    fun sampleBlocks(
        src: ByteArray, w: Int, h: Int, stride: Int,
        x1: Float, y1: Float, x2: Float, y2: Float,
        cols: Int, rows: Int, out: IntArray,
    ) {
        val bw = (x2 - x1) / cols
        val bh = (y2 - y1) / rows
        for (r in 0 until rows) for (c in 0 until cols) {
            var sr = 0; var sg = 0; var sb = 0
            for (j in 0 until 3) {
                val y = clampI((y1 + bh * (r + (j + 0.5f) / 3f)).toInt(), 0, h - 1)
                val row = y * stride
                for (i in 0 until 3) {
                    val x = clampI((x1 + bw * (c + (i + 0.5f) / 3f)).toInt(), 0, w - 1)
                    val p = row + x * 4
                    sr += src[p].toInt() and 0xFF
                    sg += src[p + 1].toInt() and 0xFF
                    sb += src[p + 2].toInt() and 0xFF
                }
            }
            out[r * cols + c] = (0xFF shl 24) or ((sr / 9) shl 16) or ((sg / 9) shl 8) or (sb / 9)
        }
    }

    /**
     * Mean absolute per-channel difference between the frame and the solid
     * blocks we drew, measured at 4 interior points per block (away from block
     * edges so compositor filtering does not matter). Small (< ~10) means the
     * capture shows our own censor (full-screen capture self-occlusion).
     */
    fun occlusionDiff(
        src: ByteArray, w: Int, h: Int, stride: Int,
        x1: Float, y1: Float, x2: Float, y2: Float,
        cols: Int, rows: Int, colors: IntArray,
    ): Float {
        val bw = (x2 - x1) / cols
        val bh = (y2 - y1) / rows
        var sum = 0L
        var n = 0
        for (r in 0 until rows) for (c in 0 until cols) {
            val col = colors[r * cols + c]
            val cr = (col shr 16) and 0xFF
            val cg = (col shr 8) and 0xFF
            val cb = col and 0xFF
            for (j in 0 until 2) {
                val y = (y1 + bh * (r + 0.3f + 0.4f * j)).toInt()
                if (y < 0 || y >= h) continue
                for (i in 0 until 2) {
                    val x = (x1 + bw * (c + 0.3f + 0.4f * i)).toInt()
                    if (x < 0 || x >= w) continue
                    val p = y * stride + x * 4
                    sum += abs((src[p].toInt() and 0xFF) - cr) +
                        abs((src[p + 1].toInt() and 0xFF) - cg) +
                        abs((src[p + 2].toInt() and 0xFF) - cb)
                    n += 3
                }
            }
        }
        return if (n == 0) 255f else sum.toFloat() / n
    }

    private fun clampI(v: Int, lo: Int, hi: Int) = if (v < lo) lo else if (v > hi) hi else v
}

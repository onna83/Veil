package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PixelatorTest {
    private val w = 200; private val h = 200; private val stride = w * 4

    @Test
    fun occlusionDetectsOwnBlocksButNotTexture() {
        val f = ByteArray(stride * h)
        for (i in f.indices) f[i] = ((i * 131) % 256).toByte() // texture
        val cols = 4; val rows = 3
        val colors = IntArray(cols * rows)
        Pixelator.sampleBlocks(f, w, h, stride, 40f, 50f, 120f, 110f, cols, rows, colors)
        assertTrue("texture is not our censor", Pixelator.occlusionDiff(f, w, h, stride, 40f, 50f, 120f, 110f, cols, rows, colors) > 20f)
        // draw the blocks into the frame like the overlay would appear in the capture
        for (r in 0 until rows) for (c in 0 until cols) for (y in 50 + r * 20 until 50 + (r + 1) * 20) for (x in 40 + c * 20 until 40 + (c + 1) * 20) {
            val p = y * stride + x * 4; val col = colors[r * cols + c]
            f[p] = (col shr 16).toByte(); f[p + 1] = (col shr 8).toByte(); f[p + 2] = col.toByte()
        }
        assertEquals(0f, Pixelator.occlusionDiff(f, w, h, stride, 40f, 50f, 120f, 110f, cols, rows, colors), 0.01f)
    }

    @Test
    fun blockCounts() {
        assertEquals(7, Pixelator.blocksFor(700f, 700f))
        assertEquals(1, Pixelator.blocksFor(5f, 5f))
        assertTrue(Pixelator.blocksFor(10000f, 10000f) <= 16)
    }
}

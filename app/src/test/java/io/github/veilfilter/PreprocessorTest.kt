package io.github.veilfilter

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max

/** Letterbox + resize + normalise against NudeNet's _read_image (cv2) output. */
class PreprocessorTest {
    @Test
    fun matchesNudeNetBlob() {
        val all = TestData.bytes("preprocess_input.bin")
        val hdr = ByteBuffer.wrap(all, 0, 12).order(ByteOrder.LITTLE_ENDIAN)
        val w = hdr.int; val h = hdr.int; val stride = hdr.int
        val rgba = all.copyOfRange(12, all.size)
        val expected = TestData.floats("preprocess_expected.bin")
        val p = Preprocessor(320, nchw = true)
        p.process(rgba, w, h, stride)
        assertEquals(expected.size, p.output.size)
        var maxDiff = 0f
        var sum = 0.0
        for (i in expected.indices) {
            val d = abs(expected[i] - p.output[i])
            maxDiff = max(maxDiff, d)
            sum += d
        }
        val mean = sum / expected.size
        println("preprocess max diff ${maxDiff * 255}/255, mean ${mean * 255}/255")
        // cv2 uses 11-bit fixed-point bilinear weights for 8-bit images
        assertTrue("max diff ${maxDiff * 255}/255", maxDiff <= 2.5f / 255f)
        assertTrue("mean diff ${mean * 255}/255", mean <= 0.5 / 255)
        assertEquals(600f / 320f, p.scale, 1e-6f)
    }

    @Test
    fun nhwcIsTransposeOfNchw() {
        val w = 50; val h = 30; val stride = w * 4
        val src = ByteArray(stride * h) { (it * 37 % 251).toByte() }
        val a = Preprocessor(320, true); val b = Preprocessor(320, false)
        a.process(src, w, h, stride); b.process(src, w, h, stride)
        val plane = 320 * 320
        for (k in 0 until plane step 97) for (c in 0 until 3) {
            assertEquals(a.output[c * plane + k], b.output[k * 3 + c], 0f)
        }
    }
}

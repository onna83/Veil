package io.github.veilfilter

/**
 * Fresh-frame wait for the inference thread (v1.6.1). When the detector frees
 * up, the newest READY frame is on average half a capture interval old. If it
 * is already old and the next frame is due soon, waiting for that one gives a
 * fresher detection for a small throughput cost. With age a (since READY) and
 * interval I, waiting pays off on average when a > I/3; we wait from 0.4 I on,
 * at most until ~1.2 x the expected arrival.
 */
object FreshWait {
    /** @return how long to wait (ms), or 0 to start on the current frame now */
    fun timeoutMs(sinceReadyMs: Float, intervalMs: Float): Long {
        if (intervalMs !in 4f..40f) return 0L
        val due = intervalMs - sinceReadyMs
        if (sinceReadyMs <= 0.4f * intervalMs || due < 0.5f) return 0L
        return kotlin.math.ceil(due * 1.2f).toLong().coerceAtLeast(1L)
    }
}

package io.github.veilfilter

/** Pure decision logic for the scene-cut guard (unit-tested). */
object CutGate {
    /** a cut may only fire when nothing is scrolling now or within [Pipeline.SCROLL_QUIET_MS] */
    fun fire(enabled: Boolean, cutRaw: Boolean, scrolling: Boolean, tCapMs: Long, lastScrollMs: Long): Boolean =
        enabled && cutRaw && !scrolling && tCapMs - lastScrollMs >= Pipeline.SCROLL_QUIET_MS

    /** released by the first FULL detector result computed on the cut frame or later */
    fun releases(resultFrameId: Long, cutFrameId: Long): Boolean = resultFrameId >= cutFrameId
}

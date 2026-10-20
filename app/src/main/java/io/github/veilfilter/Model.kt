package io.github.veilfilter

/**
 * One censor rectangle handed from the pipeline to the UI thread.
 * Coordinates are capture-frame pixels at time [tMs] (monotonic,
 * System.nanoTime base); the renderer extrapolates with [vx]/[vy] (px/ms).
 * [colors] is a cols x rows pixelation (immutable once published), or null
 * for a solid dark fill.
 */
class CensorBox(
    val id: Int,
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val vx: Float, val vy: Float,
    val tMs: Long,
    val cols: Int, val rows: Int,
    val colors: IntArray?,
)

class RenderState(
    val frameW: Int,
    val frameH: Int,
    val boxes: List<CensorBox>,
    val captureTimeMs: Long,
    /** System.nanoTime() when the pipeline published this state */
    val publishedNs: Long,
)

/** What the renderer actually put on screen for one box, in capture coords. */
class DrawnBox(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val cols: Int, val rows: Int,
    val colors: IntArray?,
    /** when this id first became visible (ms, nanoTime base) */
    val firstShownMs: Long,
)

const val GUARD_ID = -1
const val SCROLL_GUARD_ID = -2
/** full-frame cover while a newly switched model loads */
const val WARM_ID = -3
/** appearance-guard box k gets id APPEAR_ID_BASE - k */
const val APPEAR_ID_BASE = -100

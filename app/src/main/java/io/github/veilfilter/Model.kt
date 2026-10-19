package io.github.veilfilter

/**
 * One censor rectangle handed from the pipeline thread to the UI thread.
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
)

/** What the renderer actually put on screen for one box, in capture coords. */
class DrawnBox(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val cols: Int, val rows: Int,
    val colors: IntArray?,
)

class Stats(
    val backend: String,
    val detectorMs: Float,
    val fps: Float,
    val latencyMs: Float,
    val tracks: Int,
    val occluded: Int,
    val frameW: Int,
    val frameH: Int,
)

const val GUARD_ID = -1

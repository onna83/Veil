package io.github.veilfilter

import android.media.ImageReader
import kotlin.math.max
import kotlin.math.min

/**
 * Per-frame work, all on the dedicated inference thread:
 *  1. copy the latest captured frame (older ones are dropped),
 *  2. scene guard (cheap) -> immediate guard censor on abrupt change,
 *  3. self-occlusion check + refresh pixelation of existing tracks and
 *     publish them BEFORE inference (so the censor follows the content
 *     with only capture latency),
 *  4. inference, decode, NMS, tracker update, publish again.
 */
class Pipeline(
    private val detectorFactory: () -> Detector,
    private val publish: (RenderState) -> Unit,
    private val drawn: (Int) -> DrawnBox?,
    private val onStats: (Stats) -> Unit,
) {
    private var detector: Detector? = null
    private val pre = Preprocessor(320, nchw = true)
    private val decoder = YoloDecoder()
    private val tracker = Tracker()
    private val guard = SceneGuard()
    private val classMask = Labels.mask(Labels.CENSORED_NAMES)

    private var frame = ByteArray(0)
    private var fw = 0
    private var fh = 0
    private var stride = 0

    /** Last pixelation per track id (kept while the track is occluded). */
    private class Pix(var cols: Int, var rows: Int, var colors: IntArray?)
    private val pix = HashMap<Int, Pix>()
    private val tmpIds = HashSet<Int>()

    @Volatile var sceneGuardEnabled = true
    @Volatile var paused = false

    // stats
    private var detMsEma = 0f
    private var latMsEma = 0f
    private var frames = 0
    private var fpsWindowStart = 0L
    private var lastFps = 0f

    var backendName = "?"
        private set

    /** Call on the pipeline thread. */
    fun init() {
        if (detector == null) {
            val d = detectorFactory()
            detector = d
            backendName = d.backend
        }
    }

    fun release() {
        detector?.close()
        detector = null
        tracker.clear()
        pix.clear()
    }

    /** Forget all state (content switched, visibility lost, resize...). */
    fun reset() {
        tracker.clear()
        pix.clear()
        guard.reset()
        publish(RenderState(fw, fh, emptyList(), nowMs()))
    }

    fun onImageAvailable(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        val tCap: Long
        try {
            if (paused) return
            val nowNs = System.nanoTime()
            val ts = img.timestamp
            tCap = (if (ts in (nowNs - 1_000_000_000L)..nowNs) ts else nowNs) / 1_000_000L
            val plane = img.planes[0]
            val buf = plane.buffer
            val w = img.width
            val h = img.height
            val rs = plane.rowStride
            val need = rs * (h - 1) + w * 4
            if (frame.size < need) frame = ByteArray(rs * h)
            buf.rewind()
            buf.get(frame, 0, min(need, buf.remaining()))
            fw = w; fh = h; stride = rs
        } finally {
            img.close()
        }
        processFrame(tCap)
    }

    private fun processFrame(tCap: Long) {
        val det = detector ?: return
        val w = fw; val h = fh

        // 1. scene guard: cheaper than inference, runs first
        val cut = sceneGuardEnabled && guard.analyze(frame, w, h, stride)
        val guardBox = if (cut) buildGuardBox(tCap) else null

        // 2. self-occlusion + pixel refresh; publish before inference
        for (tr in tracker.tracks) {
            val d = drawn(tr.id) ?: continue
            val colors = d.colors ?: continue
            val diff = Pixelator.occlusionDiff(frame, w, h, stride, d.x1, d.y1, d.x2, d.y2, d.cols, d.rows, colors)
            if (diff < OCCLUSION_DIFF) tracker.markOccluded(tr, tCap)
        }
        if (tracker.tracks.isNotEmpty() || guardBox != null) {
            publishTracks(tCap, guardBox)
        }

        // 3. inference
        pre.process(frame, w, h, stride)
        val t0 = System.nanoTime()
        det.run(pre.output)
        val detMs = (System.nanoTime() - t0) / 1e6f
        val n = decoder.decode(
            det.output, det.coordScale, pre.scale, tracker.keepThreshold, classMask,
            classAware = true, iouThreshold = 0.45f, maxX = w.toFloat(), maxY = h.toFloat(),
        )
        tracker.update(n, decoder.x1, decoder.y1, decoder.x2, decoder.y2, decoder.score, decoder.cls, tCap, cut)
        publishTracks(tCap, null) // detector result clears the guard

        // stats
        val now = nowMs()
        detMsEma = if (detMsEma == 0f) detMs else detMsEma * 0.9f + detMs * 0.1f
        val lat = (now - tCap).toFloat()
        latMsEma = if (latMsEma == 0f) lat else latMsEma * 0.9f + lat * 0.1f
        frames++
        if (fpsWindowStart == 0L) fpsWindowStart = now
        if (now - fpsWindowStart >= 1000) {
            lastFps = frames * 1000f / (now - fpsWindowStart)
            frames = 0
            fpsWindowStart = now
            var occ = 0
            for (tr in tracker.tracks) if (tr.isOccluded(tCap, tracker.occludedGraceMs)) occ++
            onStats(Stats(backendName, detMsEma, lastFps, latMsEma, tracker.tracks.size, occ, w, h))
        }
    }

    private fun buildGuardBox(tCap: Long): CensorBox {
        val g = guard.grid
        // one cell of margin around the changed area
        val l = max(guard.changedL - 1, 0)
        val t = max(guard.changedT - 1, 0)
        val r = min(guard.changedR + 1, g - 1)
        val b = min(guard.changedB + 1, g - 1)
        val cols = r - l + 1
        val rows = b - t + 1
        val colors = IntArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) {
            colors[j * cols + i] = guard.cellColor[(t + j) * g + (l + i)]
        }
        return CensorBox(
            GUARD_ID,
            guard.cellX(l, fw), guard.cellY(t, fh), guard.cellX(r + 1, fw), guard.cellY(b + 1, fh),
            0f, 0f, tCap, cols, rows, colors,
        )
    }

    private fun publishTracks(tCap: Long, guardBox: CensorBox?) {
        val w = fw.toFloat(); val h = fh.toFloat()
        val out = ArrayList<CensorBox>(tracker.tracks.size + 1)
        tmpIds.clear()
        for (tr in tracker.tracks) {
            tmpIds.add(tr.id)
            val mx = tracker.maxExtrapolateMs
            var x1 = tr.predictX1(tCap, mx); var y1 = tr.predictY1(tCap, mx)
            var x2 = tr.predictX2(tCap, mx); var y2 = tr.predictY2(tCap, mx)
            // padding: 10% per side (20% total) + lead in the motion direction
            val bw = x2 - x1; val bh = y2 - y1
            val px = bw * PAD + MIN_PAD
            val py = bh * PAD + MIN_PAD
            val leadX = tr.vx * LEAD_PAD_MS
            val leadY = tr.vy * LEAD_PAD_MS
            x1 -= px + max(-leadX, 0f); x2 += px + max(leadX, 0f)
            y1 -= py + max(-leadY, 0f); y2 += py + max(leadY, 0f)
            x1 = max(x1, 0f); y1 = max(y1, 0f); x2 = min(x2, w); y2 = min(y2, h)
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue

            val occluded = tr.isOccluded(tCap, tracker.occludedGraceMs)
            var p = pix[tr.id]
            val colors: IntArray?
            if (occluded) {
                // Capture shows our own censor here: keep the last clean
                // pixels; if we never had any, use a solid dark fill.
                if (p == null) { p = Pix(1, 1, null); pix[tr.id] = p }
                colors = p.colors
            } else {
                val long = max(x2 - x1, y2 - y1)
                val cols = Pixelator.blocksFor(x2 - x1, long)
                val rows = Pixelator.blocksFor(y2 - y1, long)
                val c = IntArray(cols * rows) // published arrays are immutable
                Pixelator.sampleBlocks(frame, fw, fh, stride, x1, y1, x2, y2, cols, rows, c)
                if (p == null) { p = Pix(cols, rows, c); pix[tr.id] = p } else { p.cols = cols; p.rows = rows; p.colors = c }
                colors = c
            }
            out.add(CensorBox(tr.id, x1, y1, x2, y2, tr.vx, tr.vy, tCap, p.cols, p.rows, colors))
        }
        // forget pixel state of dead tracks
        if (pix.size > tmpIds.size) pix.keys.retainAll(tmpIds)
        if (guardBox != null) out.add(guardBox)
        publish(RenderState(fw, fh, out, tCap))
    }

    companion object {
        const val OCCLUSION_DIFF = 10f
        const val PAD = 0.10f
        const val MIN_PAD = 3f
        const val LEAD_PAD_MS = 50f
        fun nowMs() = System.nanoTime() / 1_000_000L
    }
}

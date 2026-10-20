package io.github.veilfilter

import android.media.ImageReader
import android.os.Handler
import android.os.Process
import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v1.1 pipelined design (see README "How it works"):
 *
 * CAPTURE thread (this class' Handler, URGENT_DISPLAY) per frame:
 *   copy -> global shift estimate (masking our own censors) -> shift all
 *   tracks -> scene-cut guard -> capture probe -> occlusion + ring
 *   re-validation -> scroll-entry guard -> publish to the renderer ->
 *   preprocess into a free input slot.
 * INFERENCE thread: takes the newest ready slot, runs the detector, decodes,
 *   and posts the detections back to the capture thread, where they are
 *   shifted by the scroll that happened while the detector ran and merged
 *   into the tracker.
 *
 * So existing censors follow scrolling at capture rate (not detector rate),
 * the detector never waits for capture/UI work, and preprocessing of frame
 * N+1 overlaps inference of frame N.
 */
class Pipeline(
    private val detectorFactory: (status: (String) -> Unit) -> Detector,
    private val model: ModelSize,
    private val renderer: OverlayRenderer,
    private val probe: CaptureProbe,
    private val capture: Handler,
    private val onHud: (String?) -> Unit,
    private val onFatal: (String) -> Unit,
    private val socInfo: String,
) {
    // ---------------- capture-thread state ----------------
    private val decoderAnchors = model.anchors
    private val tracker = Tracker()
    private val guard = SceneGuard()
    private val shift = ShiftEstimator()
    private val appear = AppearanceGuard()
    private val trackRects = FloatArray(4 * 20)
    private val classMask = Labels.mask(Labels.CENSORED_NAMES)

    private var frame = ByteArray(0)
    private var fw = 0
    private var fh = 0
    private var stride = 0
    private var frameId = 0L
    private var lastCap = 0L
    private var generation = 0

    /** cumulative global content shift since start, capture px */
    private var cumX = 0.0
    private var cumY = 0.0
    /** global velocity, px/ms (EMA) */
    private var gvx = 0f
    private var gvy = 0f
    private var lastDx = 0f
    private var lastDy = 0f
    private var scrolling = false
    private var lastScrollT = Long.MIN_VALUE / 2

    // what the detector has already seen
    private var lastDetCumX = 0.0
    private var lastDetCumY = 0.0
    private var haveDet = false

    private var cutGuard: CensorBox? = null
    private var cutGuardFrame = 0L
    private var pendingCut = false

    private val excl = FloatArray(4 * 40)
    private var nExcl = 0

    private class Pix(var cols: Int, var rows: Int, var colors: IntArray?)
    private val pix = HashMap<Int, Pix>()
    private val tmpIds = HashSet<Int>()
    private val toRemove = ArrayList<Tracker.Track>()

    @Volatile var sceneGuardEnabled = true
    /** pre-emptive strip at the scroll-entry edge (off by default since v1.3) */
    @Volatile var scrollGuardEnabled = false
    /** small images / grid boost (tiled passes) */
    @Volatile var gridBoostEnabled = true
    val tiles = TileScheduler()
    @Volatile var appearanceGuardEnabled = true
    @Volatile var csvLog = false
    @Volatile var paused = false
    /** display refresh period, ms */
    @Volatile var vsyncMs = 16.7f
    /** user-tunable extra prediction (Advanced > Scroll prediction) */
    @Volatile var extraLeadMs = 0f

    // ---------------- inference hand-off ----------------
    private class Slot(size: Int) {
        val pre = Preprocessor(size, nchw = true)
        var state = FREE
        var frameId = 0L
        var tCap = 0L
        var cumX = 0.0
        var cumY = 0.0
        var gen = 0
        var cut = false
        var w = 0
        var h = 0
        var readyNs = 0L
        var skin = FloatArray(0)
        var region = -1
        var rx = 0; var ry = 0; var rw = 0; var rh = 0
    }

    private val lock = Object()
    private val slots = arrayOf(Slot(model.px), Slot(model.px))
    @Volatile private var running = false
    private var inferThread: Thread? = null
    @Volatile var backendName = "loading…"
        private set

    private class DetResult(n: Int) {
        val x1 = FloatArray(n); val y1 = FloatArray(n); val x2 = FloatArray(n); val y2 = FloatArray(n)
        val score = FloatArray(n); val cls = IntArray(n)
        var count = 0
        var frameId = 0L; var tCap = 0L; var cumX = 0.0; var cumY = 0.0; var gen = 0; var cut = false
        var queueMs = 0f; var inferMs = 0f; var decodeMs = 0f; var doneNs = 0L
        var skin = FloatArray(0)
        var region = -1
        var rx = 0; var ry = 0; var rw = 0; var rh = 0
        val clipped = BooleanArray(n)
    }

    // ---------------- metrics ----------------
    private class Ema { var v = 0f; fun add(x: Float) { v = if (v == 0f) x else v * 0.9f + x * 0.1f } }
    private val mInterval = Ema(); private val mDeliver = Ema(); private val mCopy = Ema()
    private val mShift = Ema(); private val mGuard = Ema(); private val mAnalyze = Ema(); private val mPre = Ema()
    private val mQueue = Ema(); private val mInfer = Ema(); private val mDecode = Ema(); private val mPost = Ema()
    private val mDetAge = Ema()
    private val mAppear = Ema()
    @Volatile private var inferMsShared = 16f
    private var capFrames = 0
    private var tilePassCount = 0
    private var fullPassCount = 0
    private var detFrames = 0
    private var ringDrops = 0
    private var statStart = 0L
    private var capFps = 0f
    private var detFps = 0f

    // ======================================================================

    fun start() {
        running = true
        val t = Thread({ inferLoop() }, "veil-infer")
        inferThread = t
        t.start()
    }

    /** Stop the inference thread (it closes the detector on its own thread). */
    fun release() {
        running = false
        synchronized(lock) { lock.notifyAll() }
        inferThread?.join(1000)
        inferThread = null
        tracker.clear()
        pix.clear()
    }

    /** Forget all state (content switched, visibility lost, resize...). Capture thread. */
    fun reset() {
        generation++
        tracker.clear()
        pix.clear()
        guard.reset()
        shift.reset()
        appear.reset()
        tiles.reset()
        cutGuard = null
        pendingCut = false
        haveDet = false
        gvx = 0f; gvy = 0f
        synchronized(lock) { for (s in slots) if (s.state == READY) s.state = FREE }
        renderer.submit(RenderState(fw, fh, emptyList(), nowMs(), System.nanoTime()))
    }

    fun onImageAvailable(reader: ImageReader) {
        val img = try { reader.acquireLatestImage() } catch (e: IllegalStateException) { null } ?: return
        val tCap: Long
        val deliverMs: Float
        val copyMs: Float
        try {
            if (paused) return
            val nowNs = System.nanoTime()
            val ts = img.timestamp
            val tsOk = ts in (nowNs - 1_000_000_000L)..nowNs
            tCap = (if (tsOk) ts else nowNs) / 1_000_000L
            deliverMs = if (tsOk) (nowNs - ts) / 1e6f else 0f
            val plane = img.planes[0]
            val buf = plane.buffer
            val w = img.width
            val h = img.height
            val rs = plane.rowStride
            val need = rs * (h - 1) + w * 4
            if (frame.size < need) frame = ByteArray(rs * h)
            if (w != fw || h != fh || rs != stride) {
                fw = w; fh = h; stride = rs
                reset()
            }
            buf.rewind()
            buf.get(frame, 0, min(need, buf.remaining()))
            copyMs = (System.nanoTime() - nowNs) / 1e6f
        } finally {
            img.close()
        }
        processFrame(tCap, deliverMs, copyMs)
    }

    // ======================================================================
    // capture thread

    private fun processFrame(tCap: Long, deliverMs: Float, copyMs: Float) {
        val t0 = System.nanoTime()
        frameId++
        val w = fw; val h = fh
        val interval = if (lastCap > 0) (tCap - lastCap).toFloat() else 0f
        lastCap = tCap
        val mode = probe.mode
        val selfVisible = mode != CaptureProbe.Mode.SINGLE_APP

        // 1. what to mask: our own censors (+ probe) when they may be in the capture
        nExcl = 0
        if (selfVisible) {
            nExcl = renderer.drawnRects(excl, excl.size / 4 - 1)
            val lat = if (probeLatency() > 0) probeLatency() else 2 * vsyncMs
            val dilX = abs(gvx) * (lat + vsyncMs) + 4f
            val dilY = abs(gvy) * (lat + vsyncMs) + 4f
            for (k in 0 until nExcl) {
                excl[4 * k] -= dilX; excl[4 * k + 1] -= dilY; excl[4 * k + 2] += dilX; excl[4 * k + 3] += dilY
            }
            val pr = OverlayRenderer.probeRect(w, h)
            excl[4 * nExcl] = pr[0] - 2; excl[4 * nExcl + 1] = pr[1] - 2
            excl[4 * nExcl + 2] = pr[2] + 2; excl[4 * nExcl + 3] = pr[3] + 2
            nExcl++
        }

        // 2. global shift: move every track (incl. occluded ones) with the page
        shift.analyze(frame, w, h, stride, excl, nExcl)
        val t1 = System.nanoTime()
        var dx = 0f; var dy = 0f
        if (shift.confident) {
            if (!shift.static) { dx = shift.dx; dy = shift.dy }
            if (dx != 0f || dy != 0f) {
                cumX += dx; cumY += dy
                tracker.applyShift(dx, dy)
                appear.applyShift(dx, dy)
            }
            if (interval in 1f..200f) {
                // fast response: scroll start/stop must not lag the page
                val k = 0.6f
                gvx = gvx * (1 - k) + (dx / interval) * k
                gvy = gvy * (1 - k) + (dy / interval) * k
            } else { gvx = 0f; gvy = 0f }
            if (shift.static) { gvx = 0f; gvy = 0f }
        } else {
            gvx *= 0.5f; gvy *= 0.5f
        }
        if (abs(gvx) < 0.005f) gvx = 0f
        if (abs(gvy) < 0.005f) gvy = 0f
        lastDx = dx; lastDy = dy
        scrolling = shift.confident && !shift.static && (abs(dx) >= 0.5f || abs(dy) >= 0.5f)
        if (scrolling) lastScrollT = tCap
        // no pre-emptive covering while scrolling (or just after): only real
        // detections + scroll-compensated tracks cover content then
        val recentScroll = tCap - lastScrollT < SCROLL_QUIET_MS

        // 3. scene-cut guard (our own censors masked; a confident scroll is not a cut)
        val t2 = System.nanoTime()
        val cutRaw = guard.analyze(frame, w, h, stride, excl, nExcl)
        val cut = CutGate.fire(sceneGuardEnabled, cutRaw, scrolling, tCap, lastScrollT)
        if (scrolling) cutGuard = null // never hold a full-screen cover while the user scrolls
        if (cut) {
            cutGuard = buildGuardBox(tCap)
            cutGuardFrame = frameId
            pendingCut = true
        }
        val t3 = System.nanoTime()

        // 3b. appearance guard: new skin-coloured content since the detector last looked
        if (appearanceGuardEnabled || gridBoostEnabled) {
            var nt = 0
            for (tr in tracker.tracks) {
                if (nt >= trackRects.size / 4) break
                trackRects[4 * nt] = tr.x1; trackRects[4 * nt + 1] = tr.y1
                trackRects[4 * nt + 2] = tr.x2; trackRects[4 * nt + 3] = tr.y2
                nt++
            }
            appear.analyze(
                frame, w, h, stride, excl, nExcl, trackRects, nt, cumX, cumY, frameId, tCap,
                trigger = appearanceGuardEnabled && !recentScroll,
            )
            mAppear.add(appear.lastMs)
            if (gridBoostEnabled) {
                tiles.layout(w, h)
                tiles.observe(appear.skin, appear.cols, appear.rows, appear.cell, cumX, cumY, tCap)
            }
        } else if (appear.boxes.isNotEmpty()) appear.reset()
        tiles.enabled = gridBoostEnabled

        // 4. capture probe (are we captured? draw->capture latency)
        if (mode != CaptureProbe.Mode.SINGLE_APP) {
            val pr = OverlayRenderer.probeRect(w, h)
            val px = ((pr[0] + pr[2]) / 2).toInt().coerceIn(0, w - 1)
            val py = ((pr[1] + pr[3]) / 2).toInt().coerceIn(0, h - 1)
            val p = py * stride + px * 4
            val c = (0xFF shl 24) or ((frame[p].toInt() and 0xFF) shl 16) or
                ((frame[p + 1].toInt() and 0xFF) shl 8) or (frame[p + 2].toInt() and 0xFF)
            if (probe.onFrame(c, tCap)) renderer.probeChanged()
            if (probe.mode == CaptureProbe.Mode.SINGLE_APP) renderer.hideProbeAsync()
        }
        updateLead()

        // 5. occlusion (full screen only) + ring re-validation + off-frame removal
        toRemove.clear()
        val probeLat = if (probe.latencyMs > 0) probe.latencyMs else 3 * vsyncMs // only used in FULL_SCREEN
        for (tr in tracker.tracks) {
            if (tr.x2 < 0 || tr.y2 < 0 || tr.x1 > w || tr.y1 > h) { toRemove.add(tr); continue }
            if (selfVisible) {
                val d = renderer.drawnFor(tr.id)
                if (d != null) {
                    val occ = if (mode == CaptureProbe.Mode.FULL_SCREEN) {
                        // our censor is in this frame once it has been up longer than the capture latency
                        tCap - d.firstShownMs > probeLat + vsyncMs
                    } else {
                        val colors = d.colors
                        colors != null && Pixelator.occlusionDiff(frame, w, h, stride, d.x1, d.y1, d.x2, d.y2, d.cols, d.rows, colors) < OCCLUSION_DIFF
                    }
                    if (occ) tracker.markOccluded(tr, tCap)
                }
                if (tr.isOccluded(tCap, tracker.occludedGraceMs) && tr.ring.has) {
                    val diff = RingCheck.diff(tr.ring, frame, w, h, stride, excl, nExcl, cumX, cumY)
                    if (!diff.isNaN()) {
                        if (diff > RingCheck.MAX_DIFF) {
                            tr.ring.mismatches++
                            if (tr.ring.mismatches >= 2) { toRemove.add(tr); ringDrops++ }
                        } else tr.ring.mismatches = 0
                    }
                }
            }
        }
        for (tr in toRemove) tracker.remove(tr)

        // 6. publish (censors follow the content at capture rate)
        publish(tCap, buildScrollGuard(tCap, mode, interval))
        val t4 = System.nanoTime()

        // 7. hand the frame to the detector
        dispatch(tCap)
        val t5 = System.nanoTime()

        // metrics
        if (interval > 0f) mInterval.add(interval)
        mDeliver.add(deliverMs); mCopy.add(copyMs)
        mShift.add((t1 - t0) / 1e6f); mGuard.add((t3 - t2) / 1e6f)
        mAnalyze.add((t4 - t0) / 1e6f); mPre.add((t5 - t4) / 1e6f)
        capFrames++
        if (csvLog) {
            Log.i(CSV, "F,$frameId,$tCap,${f1(interval)},${f1(deliverMs)},${f2(copyMs)},${f2((t1 - t0) / 1e6f)}," +
                "${f2((t3 - t2) / 1e6f)},${f2((t4 - t0) / 1e6f)},${f2((t5 - t4) / 1e6f)},${f1(dx)},${f1(dy)}," +
                "${if (shift.confident) 1 else 0},${tracker.tracks.size},${mode.name},${if (cut) 1 else 0}," +
                "${f2(appear.lastMs)},${appear.boxes.size}")
        }
        maybeStats(tCap)
    }

    /**
     * Forward-prediction horizon for the renderer. Boxes are published with
     * their capture time tCap and velocity (page scroll + residual object
     * motion); on every vsync the renderer places them at
     *   pos(tCap) + v * (frameTime + leadMs - tCap)
     * i.e. where the content will be when the frame being drawn actually
     * reaches the glass. tCap -> frameTime already covers capture delivery,
     * analysis and the wait for vsync; leadMs adds what happens after
     * our draw: render + composition + scan-out (measured by the probe in
     * full-screen mode, assumed 2 vsyncs otherwise), half a vsync for
     * display -> capture timestamp, and the user's extra lead setting.
     */
    private fun probeLatency(): Float =
        if (probe.mode == CaptureProbe.Mode.FULL_SCREEN) probe.latencyMs else -1f

    private fun updateLead() {
        // the probe can be fooled by dark content before single-app is confirmed; only trust it in full screen
        val l = probeLatency()
        val toScreen = if (l > 0) max(l, vsyncMs) else 2 * vsyncMs
        renderer.leadMs = min(toScreen + 0.5f * vsyncMs + extraLeadMs, 100f)
        renderer.vsyncMs = vsyncMs
    }

    /** Preprocess into a free slot; the inference thread takes the newest. */
    private fun dispatch(tCap: Long) {
        val slot: Slot = synchronized(lock) {
            // a READY cut frame must not be overwritten (it is the clean frame the guard waits for)
            val sl = slots.firstOrNull { it.state == FREE }
                ?: slots.firstOrNull { it.state == READY && !it.cut }
                ?: return
            sl.state = WRITING
            sl
        }
        if (pendingCut) tiles.requestFull()
        val region = if (gridBoostEnabled) tiles.peek(tCap) else -1
        val tl = if (region >= 0) tiles.tiles.getOrNull(region) else null
        if (tl != null) {
            slot.region = region; slot.rx = tl.x; slot.ry = tl.y; slot.rw = tl.side; slot.rh = tl.side
            slot.pre.process(frame, tl.side, tl.side, stride, tl.y * stride + tl.x * 4)
        } else {
            slot.region = -1; slot.rx = 0; slot.ry = 0; slot.rw = fw; slot.rh = fh
            slot.pre.process(frame, fw, fh, stride)
        }
        slot.frameId = frameId; slot.tCap = tCap; slot.cumX = cumX; slot.cumY = cumY
        slot.gen = generation; slot.w = fw; slot.h = fh
        slot.cut = pendingCut
        pendingCut = false
        slot.skin = appear.snapshotInto(slot.skin)
        slot.readyNs = System.nanoTime()
        synchronized(lock) { slot.state = READY; lock.notifyAll() }
    }

    /** Detections arrive here (posted at the front of the capture queue). */
    private fun onDetections(r: DetResult) {
        val tPost = (System.nanoTime() - r.doneNs) / 1e6f
        if (r.gen != generation) return
        val now = lastCap
        // compensate for the scroll that happened while the detector ran
        val ox = (cumX - r.cumX).toFloat()
        val oy = (cumY - r.cumY).toFloat()
        val w = fw.toFloat(); val h = fh.toFloat()
        for (i in 0 until r.count) {
            r.x1[i] += ox; r.x2[i] += ox; r.y1[i] += oy; r.y2[i] += oy
        }
        val tile = r.region >= 0
        tracker.update(
            r.count, r.x1, r.y1, r.x2, r.y2, r.score, r.cls, now, r.cut && !tile,
            clipped = if (tile) r.clipped else null, keepMs = if (tile) TILE_KEEP_MS else 0L,
        )
        if (!tile) {
            var big = false
            for (i in 0 until r.count) if ((r.x2[i] - r.x1[i]) * (r.y2[i] - r.y1[i]) > 0.02f * w * h && r.score[i] >= 0.3f) big = true
            tiles.bigContent = big
        }
        // record the uncovered ring of every track confirmed just now
        for (tr in tracker.tracks) {
            if (tr.confirmedAt != now) continue
            val bw = tr.x2 - tr.x1; val bh = tr.y2 - tr.y1
            val px = bw * (PAD + 0.02f) + MIN_PAD + 2f
            val py = bh * (PAD + 0.02f) + MIN_PAD + 2f
            RingCheck.record(
                tr.ring, frame, fw, fh, stride,
                max(tr.x1 - px, 0f), max(tr.y1 - py, 0f), min(tr.x2 + px, w), min(tr.y2 + py, h),
                excl, nExcl, cumX, cumY,
            )
        }
        if (tile) {
            appear.onDetected(r.skin, r.cumX, r.cumY, r.frameId, now,
                r.rx.toFloat(), r.ry.toFloat(), (r.rx + r.rw).toFloat(), (r.ry + r.rh).toFloat())
            tilePassCount++
        } else {
            appear.onDetected(r.skin, r.cumX, r.cumY, r.frameId, now)
            lastDetCumX = r.cumX; lastDetCumY = r.cumY; haveDet = true
            // scene-cut guard: released by the first FULL detector pass on a post-cut frame
            if (cutGuard != null && CutGate.releases(r.frameId, cutGuardFrame)) cutGuard = null
            fullPassCount++
        }
        publish(now, buildScrollGuard(now, probe.mode, mInterval.v))

        mQueue.add(r.queueMs); mInfer.add(r.inferMs); mDecode.add(r.decodeMs); mPost.add(tPost)
        mDetAge.add((nowMs() - r.tCap).toFloat())
        detFrames++
        if (csvLog) {
            Log.i(CSV, "D,${r.frameId},${r.tCap},${f2(r.queueMs)},${f2(r.inferMs)},${f2(r.decodeMs)},${f2(tPost)}," +
                "${nowMs() - r.tCap},${r.count},${f1(ox)},${f1(oy)}")
        }
    }

    /**
     * Scroll-entry guard (single-app capture only): pixelate the strip of
     * newly revealed content at the entering edge until the detector has seen
     * it. Size = scroll the detector has not seen yet + speed x latency + 3%.
     * In full-screen capture it is disabled: the guard itself would be
     * captured and hide the new content from the detector.
     */
    private fun buildScrollGuard(tCap: Long, mode: CaptureProbe.Mode, interval: Float): CensorBox? {
        if (!scrollGuardEnabled || mode != CaptureProbe.Mode.SINGLE_APP || !haveDet) return null
        val pendX = (cumX - lastDetCumX).toFloat()
        val pendY = (cumY - lastDetCumY).toFloat()
        val lat = renderer.leadMs + inferMsShared + max(interval, vsyncMs)
        val sy = abs(pendY) + abs(gvy) * lat
        val sx = abs(pendX) + abs(gvx) * lat
        if (max(sx, sy) < 2f) return null
        val w = fw.toFloat(); val h = fh.toFloat()
        val vertical = sy >= sx
        var x1 = 0f; var y1 = 0f; var x2 = w; var y2 = h
        if (vertical) {
            val strip = min(sy + 0.03f * h, 0.5f * h)
            // content moving up (dy < 0) reveals new content at the bottom
            val up = if (gvy != 0f) gvy < 0 else pendY < 0
            if (up) y1 = h - strip else y2 = strip
        } else {
            val strip = min(sx + 0.03f * w, 0.5f * w)
            val left = if (gvx != 0f) gvx < 0 else pendX < 0
            if (left) x1 = w - strip else x2 = strip
        }
        val long = max(x2 - x1, y2 - y1)
        val cols = Pixelator.blocksFor(x2 - x1, long * 0.5f)
        val rows = Pixelator.blocksFor(y2 - y1, long * 0.5f)
        val c = IntArray(cols * rows)
        Pixelator.sampleBlocks(frame, fw, fh, stride, x1, y1, x2, y2, cols, rows, c)
        return CensorBox(SCROLL_GUARD_ID, x1, y1, x2, y2, 0f, 0f, tCap, cols, rows, c)
    }

    private fun buildGuardBox(tCap: Long): CensorBox {
        val g = guard.grid
        val l = max(guard.changedL - 1, 0)
        val t = max(guard.changedT - 1, 0)
        val r = min(guard.changedR + 1, g - 1)
        val b = min(guard.changedB + 1, g - 1)
        val cols = r - l + 1
        val rows = b - t + 1
        val colors = IntArray(cols * rows)
        for (j in 0 until rows) for (i in 0 until cols) colors[j * cols + i] = guard.cellColor[(t + j) * g + (l + i)]
        return CensorBox(
            GUARD_ID,
            guard.cellX(l, fw), guard.cellY(t, fh), guard.cellX(r + 1, fw), guard.cellY(b + 1, fh),
            0f, 0f, tCap, cols, rows, colors,
        )
    }

    private fun publish(tCap: Long, scrollGuard: CensorBox?) {
        val w = fw.toFloat(); val h = fh.toFloat()
        val out = ArrayList<CensorBox>(tracker.tracks.size + 2)
        tmpIds.clear()
        val mx = tracker.maxExtrapolateMs
        for (tr in tracker.tracks) {
            tmpIds.add(tr.id)
            val occluded = tr.isOccluded(tCap, tracker.occludedGraceMs)
            // residual (object) motion + global (page) motion
            val vx = (if (occluded) 0f else tr.vx) + gvx
            val vy = (if (occluded) 0f else tr.vy) + gvy
            var x1 = tr.predictX1(tCap, mx); var y1 = tr.predictY1(tCap, mx)
            var x2 = tr.predictX2(tCap, mx); var y2 = tr.predictY2(tCap, mx)
            val bw = x2 - x1; val bh = y2 - y1
            val px = bw * PAD + MIN_PAD
            val py = bh * PAD + MIN_PAD
            // pad ahead of the motion: object motion x detector latency (fast movement leaks
            // otherwise), page motion only a little (it is predicted precisely)
            val padMs = mDetAge.v.coerceIn(30f, 120f)
            val rvx = if (occluded) 0f else tr.vx; val rvy = if (occluded) 0f else tr.vy
            val leadX = rvx * padMs + gvx * LEAD_PAD_GLOBAL_MS
            val leadY = rvy * padMs + gvy * LEAD_PAD_GLOBAL_MS
            x1 -= px + max(-leadX, 0f); x2 += px + max(leadX, 0f)
            y1 -= py + max(-leadY, 0f); y2 += py + max(leadY, 0f)
            x1 = max(x1, 0f); y1 = max(y1, 0f); x2 = min(x2, w); y2 = min(y2, h)
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue

            var p = pix[tr.id]
            val colors: IntArray?
            if (occluded) {
                // The capture shows our own censor here: keep the last clean pixels
                // (they move with the page); if we never had any, solid dark.
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
            out.add(CensorBox(tr.id, x1, y1, x2, y2, vx, vy, tCap, p.cols, p.rows, colors))
        }
        if (pix.size > tmpIds.size) pix.keys.retainAll(tmpIds)
        cutGuard?.let { out.add(it) }
        scrollGuard?.let { out.add(it) }
        for (b in appear.boxes) {
            val x1 = max(b.x1, 0f); val y1 = max(b.y1, 0f); val x2 = min(b.x2, w); val y2 = min(b.y2, h)
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue
            val long = max(x2 - x1, y2 - y1)
            val cols = Pixelator.blocksFor(x2 - x1, long)
            val rows = Pixelator.blocksFor(y2 - y1, long)
            val c = IntArray(cols * rows)
            Pixelator.sampleBlocks(frame, fw, fh, stride, x1, y1, x2, y2, cols, rows, c)
            out.add(CensorBox(APPEAR_ID_BASE - b.id, x1, y1, x2, y2, gvx, gvy, tCap, cols, rows, c))
        }
        renderer.submit(RenderState(fw, fh, out, tCap, System.nanoTime()))
    }

    // ======================================================================
    // inference thread

    private fun inferLoop() {
        try { Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY) } catch (_: Throwable) {}
        val det = try { detectorFactory { st -> backendName = "auto: $st" } } catch (t: Throwable) {
            Log.e(TAG, "detector init failed", t)
            onFatal("Model failed to load: ${t.message}")
            return
        }
        backendName = det.backend
        val decoder = YoloDecoder(numAnchors = decoderAnchors)
        try {
            while (running) {
                val slot = synchronized(lock) {
                    var s: Slot? = null
                    while (running) {
                        s = null
                        for (c in slots) if (c.state == READY && (s == null || c.frameId > s.frameId || c.cut)) s = c
                        if (s != null) break
                        lock.wait()
                    }
                    s?.also { taken ->
                        taken.state = INFER
                        // an older READY slot for the same region is now pointless
                        for (c in slots) if (c !== taken && c.state == READY && !c.cut && c.region == taken.region) c.state = FREE
                    }
                } ?: break
                tiles.commit(slot.region, slot.tCap, slot.cumX, slot.cumY)
                val tStart = System.nanoTime()
                val out = det.run(slot.pre.output)
                val tInf = System.nanoTime()
                val n = decoder.decode(
                    out, det.coordScale, slot.pre.scale, tracker.fastKeepThreshold, classMask,
                    classAware = true, iouThreshold = 0.45f, maxX = slot.rw.toFloat(), maxY = slot.rh.toFloat(),
                )
                val r = DetResult(max(n, 1))
                r.count = n
                System.arraycopy(decoder.x1, 0, r.x1, 0, n); System.arraycopy(decoder.y1, 0, r.y1, 0, n)
                System.arraycopy(decoder.x2, 0, r.x2, 0, n); System.arraycopy(decoder.y2, 0, r.y2, 0, n)
                System.arraycopy(decoder.score, 0, r.score, 0, n); System.arraycopy(decoder.cls, 0, r.cls, 0, n)
                r.frameId = slot.frameId; r.tCap = slot.tCap; r.cumX = slot.cumX; r.cumY = slot.cumY
                r.gen = slot.gen; r.cut = slot.cut
                r.skin = slot.skin.copyOf()
                r.region = slot.region; r.rx = slot.rx; r.ry = slot.ry; r.rw = slot.rw; r.rh = slot.rh
                if (slot.region >= 0) {
                    // tile coords -> frame coords; flag boxes cut by an interior tile edge
                    val ox = slot.rx.toFloat(); val oy = slot.ry.toFloat()
                    for (i in 0 until n) {
                        r.clipped[i] = (slot.rx > 0 && r.x1[i] <= 2f) || (slot.ry > 0 && r.y1[i] <= 2f) ||
                            (slot.rx + slot.rw < slot.w && r.x2[i] >= slot.rw - 2f) ||
                            (slot.ry + slot.rh < slot.h && r.y2[i] >= slot.rh - 2f)
                        r.x1[i] += ox; r.x2[i] += ox; r.y1[i] += oy; r.y2[i] += oy
                    }
                }
                r.queueMs = (tStart - slot.readyNs) / 1e6f
                r.inferMs = (tInf - tStart) / 1e6f
                r.decodeMs = (System.nanoTime() - tInf) / 1e6f
                inferMsShared = r.inferMs
                synchronized(lock) { slot.state = FREE; slot.cut = false }
                r.doneNs = System.nanoTime()
                capture.postAtFrontOfQueue { onDetections(r) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "inference failed", t)
            onFatal("Detector error: ${t.message}")
        } finally {
            try { det.close() } catch (_: Throwable) {}
        }
    }

    // ======================================================================
    // HUD

    private fun maybeStats(tCap: Long) {
        if (statStart == 0L) statStart = tCap
        val dt = tCap - statStart
        if (dt < 500) return
        capFps = capFrames * 1000f / dt
        detFps = detFrames * 1000f / dt
        capFrames = 0; detFrames = 0; statStart = tCap
        var occ = 0
        for (tr in tracker.tracks) if (tr.isOccluded(tCap, tracker.occludedGraceMs)) occ++
        val r = renderer
        val mode = when (probe.mode) {
            CaptureProbe.Mode.SINGLE_APP -> "SINGLE APP"
            CaptureProbe.Mode.FULL_SCREEN -> "FULL SCREEN"
            CaptureProbe.Mode.UNKNOWN -> "detecting…"
        }
        val pl = probeLatency()
        // latency chain estimates (ms)
        val toScreen = if (pl > 0) pl else 2 * vsyncMs                   // overlay draw -> on screen
        val dispToCap = vsyncMs * 0.5f + mDeliver.v                         // screen -> frame in our hands
        val overlay = r.hopMsEma + r.applyMsEma + 0.5f * vsyncMs // + wait for the next traversal
        val trackPath = dispToCap + mCopy.v + mAnalyze.v + overlay + toScreen
        val appearPath = trackPath // guard boxes are published in the same pass as tracks
        val detPath = dispToCap + mCopy.v + mAnalyze.v + mPre.v + mQueue.v + mInfer.v + mDecode.v + mPost.v +
            overlay + toScreen
        val ag = if (!appearanceGuardEnabled) "off" else String.format(
            java.util.Locale.US, "%.2f ms, boxes %d, new cells %d, fired %d",
            mAppear.v, appear.boxes.size, appear.lastNewCells, appear.triggers,
        )
        val tps = tilePassCount * 1000f / max(dt, 1L); val fps = fullPassCount * 1000f / max(dt, 1L)
        tilePassCount = 0; fullPassCount = 0
        val gb = if (!gridBoostEnabled) "off" else String.format(
            java.util.Locale.US, "%d tiles of %d px, hot %d, full %.1f/s tile %.1f/s, hot-tile cycle %.0f ms",
            tiles.tiles.size, tiles.tiles.firstOrNull()?.side ?: 0, tiles.hotCount(), fps, tps, tiles.cycleMsEma,
        )
        val hud = String.format(
            java.util.Locale.US,
            "%s | %s\ncapture: %s | %.0f x %d\n" +
                "cap %.1f fps (int %.1f) deliver %.1f copy %.2f\n" +
                "shift %.2f guard %.2f analyze+publish %.2f pre %.2f\n" +
                "appear guard: %s\n" +
                "grid boost: %s\n" +
                "det %.1f fps: queue %.1f infer %.1f decode %.2f post %.2f\n" +
                "overlay: hop %.1f apply %.2f layout %.2f relayout %.0f/s draw %.0f/s\n" +
                "probe draw→cap %s lead %.0f vsync %.1f\n" +
                "e2e est: tracked %.0f | new obj: guard %.0f, detector %.0f ms\n" +
                "scroll %+.1f,%+.1f px/f %s | tracks %d (occ %d) ring-drop %d",
            backendName, socInfo, mode, fw.toFloat(), fh,
            capFps, mInterval.v, mDeliver.v, mCopy.v,
            mShift.v, mGuard.v, mAnalyze.v, mPre.v,
            ag, gb,
            detFps, mQueue.v, mInfer.v, mDecode.v, mPost.v,
            r.hopMsEma, r.applyMsEma, r.layoutMsEma, r.relayoutsPerSec, r.drawsPerSec,
            if (pl > 0) String.format(java.util.Locale.US, "%.0f ms", pl) else "n/a", r.leadMs, vsyncMs,
            trackPath, appearPath, detPath,
            lastDx, lastDy, if (shift.confident) "ok" else "?", tracker.tracks.size, occ, ringDrops,
        )
        onHud(hud)
    }

    companion object {
        private const val TAG = "VeilPipeline"
        const val CSV = "VeilCSV"
        const val CSV_HEADER_F = "F,frame,tCapMs,intervalMs,deliverMs,copyMs,shiftMs,guardMs,analyzeMs,preMs,dx,dy,confident,tracks,mode,cut,appearMs,appearBoxes"
        const val CSV_HEADER_D = "D,frame,tCapMs,queueMs,inferMs,decodeMs,postMs,ageMs,dets,compX,compY"
        const val OCCLUSION_DIFF = 10f
        const val PAD = 0.10f
        const val MIN_PAD = 3f
        const val LEAD_PAD_GLOBAL_MS = 20f
        /** quiet time after a scroll before pre-emptive guards may fire again */
        const val SCROLL_QUIET_MS = 150L
        /** keep-alive of tracks confirmed by tile passes (tiles revisit slowly) */
        const val TILE_KEEP_MS = 1200L
        private const val FREE = 0
        private const val WRITING = 1
        private const val READY = 2
        private const val INFER = 3
        fun nowMs() = System.nanoTime() / 1_000_000L
        private fun f1(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
        private fun f2(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    }
}

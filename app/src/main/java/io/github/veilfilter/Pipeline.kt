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
    private val tracker = Tracker(maxExtrapolateMs = model.predictCapMs)
    private val guard = SceneGuard()
    private val shift = ShiftEstimator()
    private val appear = AppearanceGuard()
    private val trackRects = FloatArray(4 * 20)
    /** what to censor; swapped live from settings (read on both threads) */
    @Volatile var censor = CensorConfig()
    private var appliedCensor: CensorConfig? = null

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

    // ---- v1.8 accessibility mode (hints + clean-frame verification) ----
    /** accessibility mode on AND the service connected (set by CaptureService) */
    @Volatile var a11yMode = false
    private val hints = A11yHints.state
    private var hintScroll = false
    private var seenContentSeq = 0
    /** tracks that existed when the app reported a different screen */
    private val staleIds = HashSet<Int>()
    private var staleAfterT = 0L
    /**
     * Requests an overlay-free screenshot of the foreground app window, scaled to
     * (w, h) capture coordinates; the result must be posted to [onCleanFrame] on
     * the capture thread (null on failure). Set by CaptureService (Android 14+,
     * accessibility mode).
     */
    @Volatile var cleanShotRequester: ((w: Int, h: Int) -> Unit)? = null
    private var verifyPending = false
    private var verifyReqT = Long.MIN_VALUE / 2
    private var verifyReqCumX = 0.0
    private var verifyReqCumY = 0.0
    private var verifyAt = Long.MAX_VALUE
    private var verifyStrict = false
    private var verifyCount = 0
    private var verifyDrops = 0
    private var verifyFails = 0
    /** the last clean frame (in memory only) while the page has not moved since */
    private var cleanBytes: ByteArray? = null
    private var cleanStride = 0
    private var cleanCumX = 0.0
    private var cleanCumY = 0.0
    private var cleanWin: android.graphics.RectF? = null
    private var fullLay: InputLayout? = null

    // what the detector has already seen
    private var lastDetCumX = 0.0
    private var lastDetCumY = 0.0
    private var haveDet = false
    /** after a live model switch: cover the whole frame until the new model's first detection */
    @Volatile var coverUntilFirstDetection = false
    private var warmDone = false

    private var cutGuard: CensorBox? = null
    private var cutGuardFrame = 0L
    private var pendingCut = false

    private val excl = FloatArray(4 * 40)
    private var nExcl = 0

    private class Pix(var cols: Int, var rows: Int, var colors: IntArray?) {
        var gx = Int.MIN_VALUE; var gy = Int.MIN_VALUE
        /** v1.8.1 block-grid memory for hysteresis */
        val grid = BoxShaper.Grid()
    }
    private val pix = HashMap<Int, Pix>()
    private val tmpIds = HashSet<Int>()
    private val toRemove = ArrayList<Tracker.Track>()

    @Volatile var sceneGuardEnabled = true
    /** pre-emptive strip at the scroll-entry edge (off by default since v1.3) */
    @Volatile var scrollGuardEnabled = false
    /** v1.6.1 fresh-frame wait; switchable since v1.7 (Performance screen) */
    @Volatile var freshWaitEnabled = true
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
    /** pixelation block size in capture px (from the Pixel size setting), read live */
    @Volatile var blockPx = 16f
    /** v1.8.1 anti-jitter tuning (Performance › Box smoothing), read live */
    @Volatile var tuning: MotionTuning = MotionTuning.NORMAL
    private val gmotion = GlobalMotion()
    private val gaps = GapTracker()
    private val shape = BoxShaper.Shape()

    // ---------------- inference hand-off ----------------
    private class Slot(w: Int, h: Int) {
        val pre = Preprocessor(w, h, nchw = true)
        /** v1.8: an overlay-free verification frame (accessibility screenshot) */
        var clean = false
        var strict = false
        var cleanWin: android.graphics.RectF? = null
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
    private val slots = arrayOf(Slot(model.w, model.h), Slot(model.w, model.h))
    @Volatile private var running = false
    private var inferThread: Thread? = null
    // inference thread only
    private var waitedFor = -1L
    private var lastWaitMs = 0f
    @Volatile var backendName = "loading…"
        private set

    private class DetResult(n: Int) {
        val x1 = FloatArray(n); val y1 = FloatArray(n); val x2 = FloatArray(n); val y2 = FloatArray(n)
        val score = FloatArray(n); val cls = IntArray(n)
        var count = 0
        var frameId = 0L; var tCap = 0L; var cumX = 0.0; var cumY = 0.0; var gen = 0; var cut = false
        var queueMs = 0f; var inferMs = 0f; var decodeMs = 0f; var doneNs = 0L; var waitMs = 0f
        var skin = FloatArray(0)
        var region = -1
        var rx = 0; var ry = 0; var rw = 0; var rh = 0
        val clipped = BooleanArray(n)
        val part = IntArray(n)
        var clean = false; var strict = false; var cleanWin: android.graphics.RectF? = null
    }

    // ---------------- metrics ----------------
    private class Ema { var v = 0f; fun add(x: Float) { v = if (v == 0f) x else v * 0.9f + x * 0.1f } }
    private val mInterval = Ema(); private val mDeliver = Ema(); private val mCopy = Ema()
    private val mShift = Ema(); private val mGuard = Ema(); private val mAnalyze = Ema(); private val mPre = Ema()
    private val mQueue = Ema(); private val mInfer = Ema(); private val mDecode = Ema(); private val mPost = Ema()
    private val mDetAge = Ema()
    private val mPublish = Ema(); private val mShiftProf = Ema(); private val mShiftSearch = Ema(); private val mWait = Ema()
    private var framesSinceLite = 0
    /** capture interval EMA, read by the inference thread (fresh-frame wait) */
    @Volatile private var capIntervalShared = 0f
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
        gmotion.reset(); gaps.reset()
        staleIds.clear(); verifyAt = Long.MAX_VALUE; cleanBytes = null; fullLay = null
        synchronized(lock) { for (s in slots) if (s.state == READY) { s.state = FREE; s.clean = false } }
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
        val tu = tuning
        tracker.tuning = tu
        renderer.stableRounding = tu.stableRounding
        // v1.8 accessibility hints (no-ops while the mode is off)
        hintScroll = a11yMode && hints.scrolling(tCap)
        renderer.scrollActive = hintScroll
        if (a11yMode) onContentHint(tCap)
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
        val shiftMs = (t1 - t0) / 1e6f
        // self-tuning: if the estimator is still expensive on this device, halve its rows
        framesSinceLite++
        if (framesSinceLite > 60) {
            if (!shift.lite && mShift.v > LITE_ON_MS) { shift.lite = true; framesSinceLite = 0; mShift.v = 0f }
            else if (shift.lite && mShift.v in 0.01f..LITE_OFF_MS) { shift.lite = false; framesSinceLite = 0; mShift.v = 0f }
        }
        mShiftProf.add(shift.profMs); mShiftSearch.add(shift.searchMs)
        tiles.gridLike = shift.gridLike
        var dx = 0f; var dy = 0f
        if (shift.confident) {
            if (!shift.static) { dx = shift.dx; dy = shift.dy }
            if (dx != 0f || dy != 0f) {
                cumX += dx; cumY += dy
                tracker.applyShift(dx, dy)
                appear.applyShift(dx, dy)
            }
        }
        // page velocity: v1.8 per-frame EMA, or (v1.8.1) a 1-euro filter on the velocity
        // only: start / stop / reversal are taken at once, steady scrolls are smoothed.
        // (while the app reports scrolling, an unsure estimate is not "stopped")
        gmotion.update(dx, dy, interval, shift.confident, shift.static, hintScroll, tu)
        gvx = gmotion.vx; gvy = gmotion.vy
        if (interval > 0f) gaps.add(interval)
        lastDx = dx; lastDy = dy
        scrolling = shift.confident && !shift.static && (abs(dx) >= 0.5f || abs(dy) >= 0.5f)
        if (scrolling || hintScroll) lastScrollT = tCap
        if ((dx != 0f || dy != 0f) && cleanBytes != null) cleanBytes = null // clean pixels no longer line up
        // no pre-emptive covering while scrolling (or just after): only real
        // detections + scroll-compensated tracks cover content then
        val recentScroll = tCap - lastScrollT < SCROLL_QUIET_MS

        // 3. scene-cut guard (our own censors masked; a confident scroll is not a cut)
        val t2 = System.nanoTime()
        val cutRaw = guard.analyze(frame, w, h, stride, excl, nExcl)
        val cfg = censor
        // (an app-reported scroll is never a cut either; a different screen is a content hint instead)
        val cut = CutGate.fire(sceneGuardEnabled && cfg.any, cutRaw, scrolling || hintScroll, tCap, lastScrollT)
        if (scrolling || hintScroll) cutGuard = null // never hold a full-screen cover while the user scrolls
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
                trigger = appearanceGuardEnabled && cfg.skinGuard && !recentScroll,
            )
            mAppear.add(appear.lastMs)
            if (gridBoostEnabled) {
                tiles.layout(w, h, model.h.toFloat() / model.w)
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
                    // (the accessibility overlay's capture latency is not probed: check colours there)
                    val occ = if (mode == CaptureProbe.Mode.FULL_SCREEN && !renderer.sheetActive) {
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
        // 5b. v1.8: verify what is under our own censors with an overlay-free screenshot
        maybeVerify(tCap, selfVisible)

        // 6. publish (censors follow the content at capture rate)
        val tp = System.nanoTime()
        publish(tCap, buildScrollGuard(tCap, mode, interval))
        mPublish.add((System.nanoTime() - tp) / 1e6f)
        val t4 = System.nanoTime()

        // 7. hand the frame to the detector
        dispatch(tCap)
        val t5 = System.nanoTime()

        // metrics
        if (interval > 0f) mInterval.add(interval)
        mDeliver.add(deliverMs); mCopy.add(copyMs)
        mShift.add(shiftMs); mGuard.add((t3 - t2) / 1e6f)
        capIntervalShared = mInterval.v
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
        val lead = min(toScreen + 0.5f * vsyncMs + extraLeadMs, 100f)
        renderer.leadMs = lead
        renderer.vsyncMs = vsyncMs
        // v1.8 horizon: exact lead up to the knee, then a bounded tail (see Prediction)
        val cap = model.predictCapMs.toFloat()
        renderer.capMs = cap
        // v1.8.1: the knee also covers the recent worst capture gap (irregular MediaProjection
        // delivery), so a steadily moving box is not braked and then snapped forward
        val gap = if (tuning.gapKnee) GapTracker.term(gaps.gapMs, mInterval.v) else 0f
        renderer.kneeMs = Prediction.knee(mDeliver.v, mAnalyze.v, renderer.hopMsEma, lead, mInterval.v, vsyncMs, cap, gap)
    }

    /** the full-frame model input layout for the current capture size */
    private fun fullLayout(): InputLayout {
        val l = fullLay
        if (l != null) return l
        return InputLayout.choose(fw, fh, model.w, model.h).also { fullLay = it }
    }

    /** v1.8: the app reported a different screen -> boxes of the old one must be re-confirmed */
    private fun onContentHint(tCap: Long) {
        val seq = hints.contentSeq
        if (seq == seenContentSeq) return
        seenContentSeq = seq
        if (tracker.tracks.isEmpty()) return
        staleIds.clear()
        for (tr in tracker.tracks) staleIds.add(tr.id)
        // judge on frames captured after the transition had time to draw
        staleAfterT = max(hints.lastContentMs, tCap) + CONTENT_SETTLE_MS
        tiles.requestFull()
        verifyAt = staleAfterT
        verifyStrict = true
    }

    private fun maybeVerify(tCap: Long, selfVisible: Boolean) {
        val req = cleanShotRequester ?: return
        if (!a11yMode || !selfVisible || verifyPending || fw <= 0) return
        var anyOcc = false
        for (tr in tracker.tracks) if (tr.isOccluded(tCap, tracker.occludedGraceMs)) { anyOcc = true; break }
        if (!anyOcc) { if (tCap >= verifyAt) { verifyAt = Long.MAX_VALUE; verifyStrict = false }; return }
        val due = tCap >= verifyAt || (tCap - verifyReqT >= VERIFY_INTERVAL_MS && !scrolling && !hintScroll)
        if (!due) return
        verifyPending = true
        verifyReqT = tCap; verifyReqCumX = cumX; verifyReqCumY = cumY
        if (tCap >= verifyAt) verifyAt = Long.MAX_VALUE
        req(fw, fh)
    }

    /**
     * Capture thread: an overlay-free screenshot of the foreground app window
     * (accessibility mode, Android 14+), already scaled to capture coordinates.
     * Refreshes the pixels shown in occluded censors and runs one full detector
     * pass on it; tracks under our censors that it does not find are retired.
     * Never stored: the bytes live until the page moves or the next shot.
     */
    fun onCleanFrame(shot: VeilAccessibilityService.CleanShot?) {
        verifyPending = false
        if (shot == null) { verifyFails++; return }
        if (shot.w != fw || shot.h != fh || paused) return
        // the page moved since the request: the shot no longer lines up
        if (abs(cumX - verifyReqCumX) > 1.0 || abs(cumY - verifyReqCumY) > 1.0) return
        cleanBytes = shot.bytes; cleanStride = shot.stride; cleanCumX = cumX; cleanCumY = cumY; cleanWin = shot.window
        val slot: Slot = synchronized(lock) {
            val sl = slots.firstOrNull { it.state == FREE }
                ?: slots.firstOrNull { it.state == READY && !it.cut && !it.clean }
                ?: return
            sl.state = WRITING
            sl
        }
        slot.pre.process(shot.bytes, shot.stride, fullLayout())
        slot.region = -1; slot.rx = 0; slot.ry = 0; slot.rw = fw; slot.rh = fh
        slot.frameId = frameId; slot.tCap = verifyReqT; slot.cumX = verifyReqCumX; slot.cumY = verifyReqCumY
        slot.gen = generation; slot.w = fw; slot.h = fh
        slot.cut = false
        slot.clean = true; slot.strict = verifyStrict; slot.cleanWin = shot.window
        verifyStrict = false
        slot.readyNs = System.nanoTime()
        synchronized(lock) { slot.state = READY; lock.notifyAll() }
    }

    private fun onCleanDetections(r: DetResult, now: Long) {
        // tracks under our censors when the shot was requested
        val occ = ArrayList<Tracker.Track>()
        for (tr in tracker.tracks) if (tr.isOccluded(now, tracker.occludedGraceMs)) occ.add(tr)
        tracker.update(r.count, r.x1, r.y1, r.x2, r.y2, r.score, r.cls, now, false, measT = r.tCap)
        val win = r.cleanWin
        val limit = if (r.strict) 1 else VERIFY_MISSES
        for (tr in occ) {
            if (tr !in tracker.tracks) continue
            if (tr.confirmedAt == now) { tr.verifyMiss = 0; continue }
            val cx = (tr.x1 + tr.x2) * 0.5f; val cy = (tr.y1 + tr.y2) * 0.5f
            if (win != null && !win.contains(cx, cy)) continue
            tr.verifyMiss++
            if (tr.verifyMiss >= limit) { tracker.remove(tr); verifyDrops++ }
        }
        verifyCount++
        publish(now, null)
    }

    /** Preprocess into a free slot; the inference thread takes the newest. */
    private fun dispatch(tCap: Long) {
        val slot: Slot = synchronized(lock) {
            // a READY cut frame must not be overwritten (it is the clean frame the guard waits for)
            val sl = slots.firstOrNull { it.state == FREE }
                ?: slots.firstOrNull { it.state == READY && !it.cut && !it.clean }
                ?: return
            sl.state = WRITING
            sl
        }
        slot.clean = false; slot.strict = false; slot.cleanWin = null
        if (pendingCut) tiles.requestFull()
        val region = if (gridBoostEnabled) tiles.peek(tCap) else -1
        val tl = if (region >= 0) tiles.tiles.getOrNull(region) else null
        if (tl != null) {
            slot.region = region; slot.rx = tl.x; slot.ry = tl.y; slot.rw = tl.w; slot.rh = tl.h
            slot.pre.process(frame, stride, InputLayout.fit(tl.x, tl.y, tl.w, tl.h, model.w, model.h))
        } else {
            slot.region = -1; slot.rx = 0; slot.ry = 0; slot.rw = fw; slot.rh = fh
            slot.pre.process(frame, stride, fullLayout())
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
        val cfg = censor
        if (cfg != appliedCensor) {
            tracker.applyClasses(cfg.mask, cfg.start, cfg.keep, cfg.fastKeep)
            appliedCensor = cfg
        }
        // drop detections decoded under a previous class set, and (full-screen capture,
        // where our own UI is in the frame) anything that is mostly our HUD / floating control
        val ui = if (probe.mode != CaptureProbe.Mode.SINGLE_APP) renderer.uiRectsNow() else NO_RECTS
        var kept = 0
        for (i in 0 until r.count) {
            if (!cfg.mask[r.cls[i]]) continue
            if (ui.isNotEmpty() && mostlyInside(r.x1[i], r.y1[i], r.x2[i], r.y2[i], ui)) continue
            r.x1[kept] = r.x1[i]; r.y1[kept] = r.y1[i]; r.x2[kept] = r.x2[i]; r.y2[kept] = r.y2[i]
            r.score[kept] = r.score[i]; r.cls[kept] = r.cls[i]; r.clipped[kept] = r.clipped[i]
            kept++
        }
        r.count = kept
        if (r.clean) { onCleanDetections(r, now); return }
        val tile = r.region >= 0
        tracker.update(
            r.count, r.x1, r.y1, r.x2, r.y2, r.score, r.cls, now, r.cut && !tile,
            clipped = if (tile) r.clipped else null, keepMs = if (tile) TILE_KEEP_MS else 0L,
            measT = r.tCap,
        )
        if (!tile && staleIds.isNotEmpty() && r.tCap >= staleAfterT) {
            // v1.8 content hint: boxes of the previous screen that this pass did not find
            // again go now (occluded ones are checked by the strict verification shot)
            toRemove.clear()
            for (tr in tracker.tracks) {
                if (tr.id in staleIds && tr.confirmedAt != now && !tr.isOccluded(now, tracker.occludedGraceMs)) toRemove.add(tr)
            }
            for (tr in toRemove) tracker.remove(tr)
            staleIds.clear()
        }
        if (!tile) {
            var big = false
            for (i in 0 until r.count) if ((r.x2[i] - r.x1[i]) * (r.y2[i] - r.y1[i]) > 0.02f * w * h && r.score[i] >= 0.3f) big = true
            tiles.bigContent = big
            if (big) tiles.noteBig(now)
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

        mQueue.add(r.queueMs); mWait.add(r.waitMs); mInfer.add(r.inferMs); mDecode.add(r.decodeMs); mPost.add(tPost)
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
        // accessibility mode: never pre-emptively censor during scrolls (only real detections)
        if (a11yMode) return null
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
        val tu = tuning
        val bpx = blockPx.coerceAtLeast(2f)
        for (tr in tracker.tracks) {
            tmpIds.add(tr.id)
            val occluded = tr.isOccluded(tCap, tracker.occludedGraceMs)
            var p = pix[tr.id]
            if (p == null) { p = Pix(1, 1, null); pix[tr.id] = p }
            // geometry (prediction, pad, motion lead, block grid): see BoxShaper
            val sh = shape
            if (!BoxShaper.shape(tr, tCap, mx, occluded, gvx, gvy, mDetAge.v, blockPx, cumX, cumY, w, h,
                    if (occluded) null else p.grid, tu, sh)) continue
            val x1 = sh.x1; val y1 = sh.y1; val x2 = sh.x2; val y2 = sh.y2
            val vx = sh.vx; val vy = sh.vy
            val snap = sh.snap || sh.gix == BoxShaper.ANCHORED
            val gix = sh.gix; val giy = sh.giy

            val colors: IntArray?
            if (occluded) {
                // The capture shows our own censor here: keep the last clean pixels
                // (they move with the page); if we never had any, solid dark.
                if (p == null) { p = Pix(1, 1, null); pix[tr.id] = p }
                val cb = cleanBytes
                val win = cleanWin
                if (cb != null && win != null && win.contains(x1, y1) && win.contains(x2 - 0.5f, y2 - 0.5f) &&
                    abs(cumX - cleanCumX) < 1.0 && abs(cumY - cleanCumY) < 1.0) {
                    // v1.8: the overlay-free screenshot shows what is under our censor now
                    val cols = Pixelator.blocksOf(x2 - x1, bpx)
                    val rows = Pixelator.blocksOf(y2 - y1, bpx)
                    val c = IntArray(cols * rows)
                    Pixelator.sampleBlocks(cb, fw, fh, cleanStride, x1, y1, x2, y2, cols, rows, c)
                    p.cols = cols; p.rows = rows; p.colors = c; p.gx = gix; p.gy = giy
                }
                colors = p.colors
            } else {
                val cols = sh.cols
                val rows = sh.rows
                val c = IntArray(cols * rows) // published arrays are immutable
                Pixelator.sampleBlocks(frame, fw, fh, stride, x1, y1, x2, y2, cols, rows, c)
                // same blocks over the same content as last frame: smooth colours (no shimmer)
                val prev = p?.colors
                if (snap && p != null && prev != null && p.cols == cols && p.rows == rows && p.gx == gix && p.gy == giy &&
                    prev.size == c.size) Pixelator.blend(prev, c, c)
                if (p == null) { p = Pix(cols, rows, c); pix[tr.id] = p } else { p.cols = cols; p.rows = rows; p.colors = c }
                p.gx = gix; p.gy = giy
                colors = c
            }
            out.add(CensorBox(tr.id, x1, y1, x2, y2, vx, vy, tCap, p.cols, p.rows, colors))
        }
        if (pix.size > tmpIds.size) pix.keys.retainAll(tmpIds)
        cutGuard?.let { out.add(it) }
        scrollGuard?.let { out.add(it) }
        if (haveDet) warmDone = true
        if (coverUntilFirstDetection && !warmDone) {
            val cols = 12
            val rows = max(1, (12f * h / max(1f, w)).toInt())
            val c = IntArray(cols * rows)
            Pixelator.sampleBlocks(frame, fw, fh, stride, 0f, 0f, w, h, cols, rows, c)
            out.add(CensorBox(WARM_ID, 0f, 0f, w, h, 0f, 0f, tCap, cols, rows, c))
        }
        for (b in appear.boxes) {
            val x1 = max(b.x1, 0f); val y1 = max(b.y1, 0f); val x2 = min(b.x2, w); val y2 = min(b.y2, h)
            if (x2 - x1 < 1f || y2 - y1 < 1f) continue
            val cols = Pixelator.blocksOf(x2 - x1, blockPx)
            val rows = Pixelator.blocksOf(y2 - y1, blockPx)
            val c = IntArray(cols * rows)
            Pixelator.sampleBlocks(frame, fw, fh, stride, x1, y1, x2, y2, cols, rows, c)
            out.add(CensorBox(APPEAR_ID_BASE - b.id, x1, y1, x2, y2, gvx, gvy, tCap, cols, rows, c))
        }
        // v1.7 pause (floating control): everything above still runs (tracks, pixel
        // caches stay warm), only nothing is shown, so resume is instant
        if (ServiceState.paused) out.clear()
        renderer.submit(RenderState(fw, fh, out, tCap, System.nanoTime()))
    }

    /** Capture thread (post it there): show nothing now, without waiting for a new frame. */
    fun blankNow() {
        if (fw > 0) renderer.submit(RenderState(fw, fh, ArrayList(), lastCap, System.nanoTime()))
    }

    /** Capture thread (post it there): re-publish current tracks now (instant resume on a static screen). */
    fun republishNow() {
        if (fw > 0 && lastCap > 0) publish(lastCap, null)
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
        val box4 = FloatArray(4)
        try {
            while (running) {
                val slot = synchronized(lock) {
                    var s: Slot? = null
                    while (running) {
                        s = null
                        for (c in slots) if (c.state == READY && (s == null || c.frameId > s.frameId || c.cut || c.clean)) s = c
                        if (s != null) {
                            // Fresh-frame wait: if the newest frame is already old (> 1/3 of a
                            // capture interval) and the next one is due soon, wait for it. On
                            // average this lowers detection age by ~1/3 interval (see README).
                            val iv = capIntervalShared
                            val cand = s
                            if (freshWaitEnabled && !cand.cut && !cand.clean && waitedFor != cand.frameId) {
                                val waitMs = FreshWait.timeoutMs((System.nanoTime() - cand.readyNs) / 1e6f, iv)
                                if (waitMs > 0) {
                                    waitedFor = cand.frameId
                                    val tw = System.nanoTime()
                                    lock.wait(waitMs)
                                    lastWaitMs = (System.nanoTime() - tw) / 1e6f
                                    continue
                                }
                            }
                            break
                        }
                        lock.wait()
                    }
                    s?.also { taken ->
                        taken.state = INFER
                        // an older READY slot for the same region is now pointless
                        for (c in slots) if (c !== taken && c.state == READY && !c.cut && !c.clean && !taken.clean && c.region == taken.region) c.state = FREE
                    }
                } ?: break
                if (!slot.clean) tiles.commit(slot.region, slot.tCap, slot.cumX, slot.cumY)
                val tStart = System.nanoTime()
                val waitMs = lastWaitMs; lastWaitMs = 0f
                val cfgNow = censor
                val out = det.run(slot.pre.output)
                val tInf = System.nanoTime()
                // v1.8: decode in model-input px (non-square inputs), then map through the layout
                val n = decoder.decodeXY(
                    out, det.coordScaleX, det.coordScaleY, cfgNow.decodeThreshold, cfgNow.mask,
                    classAware = true, iouThreshold = 0.45f, maxX = det.inW.toFloat(), maxY = det.inH.toFloat(),
                    veto = cfgNow.veto,
                )
                val lay = slot.pre.layout
                val r = DetResult(max(n, 1))
                var m = 0
                for (i in 0 until n) {
                    val k = lay?.toSource(decoder.x1[i], decoder.y1[i], decoder.x2[i], decoder.y2[i], box4) ?: -1
                    if (k < 0 || box4[2] - box4[0] < 1f || box4[3] - box4[1] < 1f) continue
                    r.x1[m] = box4[0]; r.y1[m] = box4[1]; r.x2[m] = box4[2]; r.y2[m] = box4[3]
                    r.score[m] = decoder.score[i]; r.cls[m] = decoder.cls[i]; r.part[m] = k
                    m++
                }
                if (lay != null && lay.folded) m = SeamMerge.merge(r.x1, r.y1, r.x2, r.y2, r.score, r.cls, r.part, m)
                r.count = m
                r.frameId = slot.frameId; r.tCap = slot.tCap; r.cumX = slot.cumX; r.cumY = slot.cumY
                r.gen = slot.gen; r.cut = slot.cut
                r.clean = slot.clean; r.strict = slot.strict; r.cleanWin = slot.cleanWin
                r.skin = slot.skin.copyOf()
                r.region = slot.region; r.rx = slot.rx; r.ry = slot.ry; r.rw = slot.rw; r.rh = slot.rh
                if (slot.region >= 0) {
                    // (boxes are in frame coords) flag boxes cut by an interior tile edge
                    val tx2 = (slot.rx + slot.rw).toFloat(); val ty2 = (slot.ry + slot.rh).toFloat()
                    for (i in 0 until m) {
                        r.clipped[i] = (slot.rx > 0 && r.x1[i] <= slot.rx + 2f) || (slot.ry > 0 && r.y1[i] <= slot.ry + 2f) ||
                            (slot.rx + slot.rw < slot.w && r.x2[i] >= tx2 - 2f) ||
                            (slot.ry + slot.rh < slot.h && r.y2[i] >= ty2 - 2f)
                    }
                }
                r.queueMs = (tStart - slot.readyNs) / 1e6f
                r.waitMs = waitMs
                r.inferMs = (tInf - tStart) / 1e6f
                r.decodeMs = (System.nanoTime() - tInf) / 1e6f
                inferMsShared = r.inferMs
                synchronized(lock) { slot.state = FREE; slot.cut = false; slot.clean = false }
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
        val ag = if (!appearanceGuardEnabled || !censor.skinGuard) "off" else String.format(
            java.util.Locale.US, "%.2f ms, boxes %d, new cells %d, fired %d",
            mAppear.v, appear.boxes.size, appear.lastNewCells, appear.triggers,
        )
        val tps = tilePassCount * 1000f / max(dt, 1L); val fps = fullPassCount * 1000f / max(dt, 1L)
        tilePassCount = 0; fullPassCount = 0
        val gb = if (!gridBoostEnabled) "off" else String.format(
            java.util.Locale.US, "%s, %d tiles %d px wide, hot %d, full %.1f/s tile %.1f/s, hot-tile cycle %.0f ms",
            if (!shift.gridLike) "idle (no grid)" else if (tiles.bigContent || lastCap < tiles.bigUntilMs) "idle (large subject)" else "grid",
            tiles.tiles.size, tiles.tiles.firstOrNull()?.w ?: 0, tiles.hotCount(), fps, tps, tiles.cycleMsEma,
        )
        val fl = fullLay
        val inputLine = String.format(java.util.Locale.US, "input %dx%d %s scale %.2f fill %.0f%% | predict knee %.0f cap %.0f%s",
            model.w, model.h, if (fl?.folded == true) "fold" else "fit", fl?.minScale ?: 0f, 100f * (fl?.fill ?: 0f),
            r.kneeMs, Prediction.capFor(r.capMs, r.kneeMs, hintScroll), if (hintScroll) " (app scrolling)" else "")
        val a11yLine = if (!a11yMode) "a11y mode: off" else String.format(java.util.Locale.US,
            "a11y mode: %s, scroll ev %d, screens %d, verify %d (drop %d, fail %d)",
            if (r.sheetActive) "one overlay" else "per-box", hints.scrollEvents, hints.contentSeq, verifyCount, verifyDrops, verifyFails)
        val hud = hudCompact(model.id, detFps, detPath, ServiceState.paused) + "\n" + inputLine + "\n" + a11yLine + "\n" + String.format(
            java.util.Locale.US,
            "%s | model %s | %s\ncapture: %s | %.0f x %d\n" +
                "cap %.1f fps (int %.1f) deliver %.1f copy %.2f\n" +
                "shift %.2f (prof %.2f srch %.2f%s) guard %.2f appear %.2f publish %.2f analyze+publish %.2f pre %.2f\n" +
                "censor: %s\n" +
                "appear guard: %s\n" +
                "grid boost: %s\n" +
                "det %.1f fps: queue %.1f (fresh-wait %.1f) infer %.1f decode %.2f post %.2f\n" +
                "overlay: hop %.1f apply %.2f layout %.2f relayout %.0f/s draw %.0f/s\n" +
                "probe draw→cap %s lead %.0f vsync %.1f\n" +
                "e2e est: tracked %.0f | new obj: guard %.0f, detector %.0f ms\n" +
                "scroll %+.1f,%+.1f px/f %s | tracks %d (occ %d) ring-drop %d",
            backendName, model.id, socInfo, mode, fw.toFloat(), fh,
            capFps, mInterval.v, mDeliver.v, mCopy.v,
            mShift.v, mShiftProf.v, mShiftSearch.v, if (shift.lite) " lite" else "", mGuard.v, mAppear.v, mPublish.v, mAnalyze.v, mPre.v,
            censor.label(), ag, gb,
            detFps, mQueue.v, mWait.v, mInfer.v, mDecode.v, mPost.v,
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
        const val PAD = BoxShaper.PAD
        const val MIN_PAD = BoxShaper.MIN_PAD
        /** quiet time after a scroll before pre-emptive guards may fire again */
        const val SCROLL_QUIET_MS = 150L
        /** v1.8: a new screen is judged on frames captured this long after the window change */
        const val CONTENT_SETTLE_MS = 200L
        /** v1.8: clean-frame verification of occluded tracks, at most every ... */
        const val VERIFY_INTERVAL_MS = 500L
        /** ... and an occluded track goes after this many verifications without a detection */
        const val VERIFY_MISSES = 2
        /** keep-alive of tracks confirmed by tile passes (tiles revisit slowly) */
        const val TILE_KEEP_MS = 1200L
        /** shift estimator self-tuning thresholds (ms EMA) */
        const val LITE_ON_MS = 2.0f
        const val LITE_OFF_MS = 0.6f
        private const val FREE = 0
        private const val WRITING = 1
        private const val READY = 2
        private const val INFER = 3
        fun nowMs() = System.nanoTime() / 1_000_000L
        private val NO_RECTS = FloatArray(0)
        /** true if >= 60 % of the box lies inside one of [rects] ([x1,y1,x2,y2]*n) */
        fun mostlyInside(x1: Float, y1: Float, x2: Float, y2: Float, rects: FloatArray): Boolean {
            val a = max(0f, x2 - x1) * max(0f, y2 - y1)
            if (a <= 0f) return false
            var k = 0
            while (k + 3 < rects.size) {
                val iw = min(x2, rects[k + 2]) - max(x1, rects[k]); val ih = min(y2, rects[k + 3]) - max(y1, rects[k + 1])
                if (iw > 0f && ih > 0f && iw * ih >= 0.6f * a) return true
                k += 4
            }
            return false
        }
        /** first HUD line = the collapsed HUD: model, detector fps, detector e2e estimate */
        fun hudCompact(model: String, detFps: Float, e2eMs: Float, paused: Boolean = false): String =
            String.format(java.util.Locale.US, "%s · det %.0f fps · e2e %.0f ms%s", model, detFps, e2eMs, if (paused) " · PAUSED" else "")
        private fun f1(v: Float) = String.format(java.util.Locale.US, "%.1f", v)
        private fun f2(v: Float) = String.format(java.util.Locale.US, "%.2f", v)
    }
}

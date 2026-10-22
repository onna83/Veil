package io.github.veilfilter

import java.util.PriorityQueue
import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * v1.8.1 jitter / lag replay. Drives the PRODUCTION motion code (Tracker,
 * GlobalMotion, GapTracker, BoxShaper, Prediction, OverlayRenderer.place) with a
 * synthetic S23 FE-like timeline:
 *  - 120 Hz display; the app renders every vsync (content moves in vsync steps)
 *  - MediaProjection capture composed on vsyncs, ~60 fps (every 2nd vsync), 12 %
 *    of frames one vsync late, 3 % two; timestamp noise 0.3 ms; 590 x 1280
 *    capture, 1080 x 2340 screen
 *  - shift estimator: true page shift + N(0, 0.35 px)
 *  - detector: one inference thread, 20 +- 4 ms, newest frame; boxes with
 *    centre noise 1.5 px, size noise 2 px and occasional +-4 % scale flips
 *    (tile vs full pass / fold seam)
 * Metrics per rendered vsync (screen px), against where the content really is
 * when that vsync reaches the glass.
 */
object JitterSim {
    const val VSYNC = 1000.0 / 120.0
    const val SX = 1080f / 590f
    const val SY = 2340f / 1280f
    const val OBJ_W = 110f
    const val OBJ_H = 150f
    const val BLOCK = 9f // capture px (= 16 dp on the S23 FE)

    enum class Kind { STILL, SLOW_SCROLL, SCROLL, FLING, OBJECT_MOVE }

    class Scenario(val kind: Kind, val label: String)

    val scenarios = listOf(
        Scenario(Kind.STILL, "stationary (video playing, nothing moves)"),
        Scenario(Kind.SLOW_SCROLL, "slow scroll 0.15 px/ms (~275 screen px/s)"),
        Scenario(Kind.SCROLL, "steady scroll 0.8 px/ms (~1460 screen px/s)"),
        Scenario(Kind.FLING, "fling 2.5 px/ms decaying (tau 350 ms) then stop"),
        Scenario(Kind.OBJECT_MOVE, "object moving in a video 0.25 px/ms, page still"),
    )

    class Metrics {
        var n = 0
        var jitSq = 0.0; var jitMax = 0.0; val jit = ArrayList<Double>()
        var sizeSq = 0.0; var sizeChanges = 0
        var lagSum = 0.0; var lagN = 0; var lagMsSum = 0.0; var lagMsN = 0
        var leaks = 0
        var moves = 0
        var phaseSq = 0.0; var blockChanges = 0
        var overshoot = 0.0
        var durationMs = 0.0
        val jitRms get() = sqrt(jitSq / max(n, 1))
        val jitP95 get() = if (jit.isEmpty()) 0.0 else jit.sorted()[(jit.size * 0.95).toInt().coerceAtMost(jit.size - 1)]
        val sizeRms get() = sqrt(sizeSq / max(n, 1))
        val lagPx get() = lagSum / max(lagN, 1)
        val lagMs get() = lagMsSum / max(lagMsN, 1)
        val leakPct get() = 100.0 * leaks / max(n, 1)
        val movesPerS get() = moves * 1000.0 / max(durationMs, 1.0)
        val phaseRms get() = sqrt(phaseSq / max(n, 1))
        val blockChangesPerS get() = blockChanges * 1000.0 / max(durationMs, 1.0)
        fun add(o: Metrics) {
            n += o.n; jitSq += o.jitSq; jitMax = max(jitMax, o.jitMax); jit.addAll(o.jit)
            sizeSq += o.sizeSq; sizeChanges += o.sizeChanges
            lagSum += o.lagSum; lagN += o.lagN; lagMsSum += o.lagMsSum; lagMsN += o.lagMsN
            leaks += o.leaks; moves += o.moves; phaseSq += o.phaseSq; blockChanges += o.blockChanges
            overshoot = max(overshoot, o.overshoot); durationMs += o.durationMs
        }
    }

    // ---------------- content ----------------
    private class Content(val kind: Kind) {
        val t0 = 500.0 // motion starts (track warm-up before)
        val end = when (kind) { Kind.STILL -> 3500.0; Kind.SLOW_SCROLL -> 3500.0; Kind.SCROLL -> 1500.0; Kind.FLING -> 2300.0; Kind.OBJECT_MOVE -> 1700.0 }
        val y0 = when (kind) { Kind.SLOW_SCROLL -> 900.0; Kind.SCROLL, Kind.FLING -> 1080.0; else -> 640.0 }
        val x0 = if (kind == Kind.OBJECT_MOVE) 150.0 else 295.0
        val tau = 350.0; val v0 = 2.5
        /** fling: stops when speed < 0.05 px/ms */
        val tStop = t0 + tau * kotlin.math.ln(v0 / 0.05)
        /** page offset (y) at time t, before screen quantisation */
        fun page(t: Double): Double {
            if (t <= t0) return 0.0
            return when (kind) {
                Kind.SLOW_SCROLL -> -0.15 * (t - t0)
                Kind.SCROLL -> -0.8 * (t - t0)
                Kind.FLING -> { val tt = min(t, tStop) - t0; -v0 * tau * (1 - exp(-tt / tau)) }
                else -> 0.0
            }
        }
        fun own(t: Double): Double = if (kind == Kind.OBJECT_MOVE && t > t0) 0.25 * (t - t0) else 0.0
        fun pageSpeed(t: Double): Double = when (kind) {
            Kind.SLOW_SCROLL -> if (t > t0) 0.15 else 0.0
            Kind.SCROLL -> if (t > t0) 0.8 else 0.0
            Kind.FLING -> if (t > t0 && t < tStop) v0 * exp(-(t - t0) / tau) else 0.0
            Kind.OBJECT_MOVE -> if (t > t0) 0.25 else 0.0
            else -> 0.0
        }
        /** what the screen shows at time t (app frames at vsync times) */
        fun q(t: Double) = floor(t / VSYNC) * VSYNC
        fun objX(t: Double) = x0 + own(q(t))
        fun objY(t: Double) = y0 + page(q(t))
    }

    private sealed class Ev(val t: Double) : Comparable<Ev> {
        override fun compareTo(other: Ev) = t.compareTo(other.t)
    }
    private class FrameEv(t: Double, val idx: Int) : Ev(t)
    private class DetEv(t: Double, val frame: Int) : Ev(t)
    private class VsyncEv(t: Double) : Ev(t)

    private class Frame(val tc: Double, val ts: Long, val deliver: Double, val objX: Double, val objY: Double, val pageY: Double)

    /** one run */
    var debug = false

    fun run(s: Scenario, tu: MotionTuning, seed: Long): Metrics {
        val rnd = Random(seed)
        val c = Content(s.kind)
        // capture timeline
        val frames = ArrayList<Frame>()
        var k = 3
        while (k * VSYNC < c.end + 300) {
            val tc = k * VSYNC + 0.2
            val ts = (tc + rnd.nextGaussian() * 0.3).toLong()
            frames.add(Frame(tc, ts, 6.0 + abs(rnd.nextGaussian()) * 2.0, c.objX(tc), c.objY(tc), c.page(c.q(tc))))
            val u = rnd.nextDouble()
            // ~60 fps: every 2nd vsync; 12 % of frames one vsync late, 3 % two (dropped compositions)
            k += if (u < 0.03) 4 else if (u < 0.15) 3 else 2
            // after the fling stops / still page: content unchanged, MediaProjection keeps delivering
            // only if something on screen changes; STILL = video playing, keeps frames coming
        }
        val q = PriorityQueue<Ev>()
        for ((i, f) in frames.withIndex()) q.add(FrameEv(f.tc + f.deliver, i))
        var vt = VSYNC * 3
        while (vt < c.end + 300) { q.add(VsyncEv(vt)); vt += VSYNC }

        val tracker = Tracker(maxExtrapolateMs = 180L).also { it.tuning = tu }
        val gm = GlobalMotion(); val gaps = GapTracker(); val shape = BoxShaper.Shape()
        var cumY = 0.0; var cumX = 0.0
        var lastCap = 0L; var lastFrame: Frame? = null
        var intervalEma = 0f; var detAgeEma = 0f; var deliverEma = 0f
        val lead = (2 * VSYNC + 0.5 * VSYNC).toFloat()
        val grids = HashMap<Int, BoxShaper.Grid>()
        // published state
        class Pub(val boxes: List<CensorBox>, val visibleAt: Double, val knee: Float)
        var pub: Pub? = null; var pending: Pub? = null
        // detector
        var detBusyUntil = -1.0; var detPendingFrame = -1; var lastDetFrame = -1
        val cumAt = HashMap<Int, Pair<Double, Double>>()
        val m = Metrics()
        var prevBox: IntArray? = null; var prevTruthCy = 0.0; var prevTruthCx = 0.0
        var prevPhase = Double.NaN; var prevBlocks = -1

        fun publish(tNow: Double) {
            val knee = Prediction.knee(deliverEma, 2f, 2f, lead, intervalEma, VSYNC.toFloat(), 180f,
                if (tu.gapKnee) GapTracker.term(gaps.gapMs, intervalEma) else 0f)
            val out = ArrayList<CensorBox>()
            for (tr in tracker.tracks) {
                val g = grids.getOrPut(tr.id) { BoxShaper.Grid() }
                if (!BoxShaper.shape(tr, lastCap, tracker.maxExtrapolateMs, false, gm.vx, gm.vy, detAgeEma, BLOCK, cumX, cumY,
                        590f, 1280f, g, tu, shape)) continue
                out.add(CensorBox(tr.id, shape.x1, shape.y1, shape.x2, shape.y2, shape.vx, shape.vy, lastCap, shape.cols, shape.rows, null))
            }
            pending = Pub(out, tNow + 2.0 /* analyze */ + 2.0 /* hop */, knee)
        }

        fun startDet(fi: Int, t: Double) {
            detBusyUntil = t + 1.0 + 20.0 + rnd.nextGaussian() * 4.0
            lastDetFrame = fi
            q.add(DetEv(detBusyUntil + 0.5, fi))
        }

        while (q.isNotEmpty()) {
            val e = q.poll()
            val p = pending
            if (p != null && p.visibleAt <= e.t) { pub = p; pending = null }
            when (e) {
                is FrameEv -> {
                    val f = frames[e.idx]
                    val interval = if (lastCap > 0) (f.ts - lastCap).toFloat() else 0f
                    lastCap = f.ts
                    if (interval > 0) intervalEma = if (intervalEma == 0f) interval else intervalEma * 0.9f + interval * 0.1f
                    deliverEma = if (deliverEma == 0f) f.deliver.toFloat() else deliverEma * 0.9f + f.deliver.toFloat() * 0.1f
                    val lf = lastFrame
                    var dy = 0f; var static = true
                    if (lf != null) {
                        val trueDy = f.pageY - lf.pageY
                        if (trueDy != 0.0) { static = false; dy = (trueDy + rnd.nextGaussian() * 0.35).toFloat() }
                    }
                    lastFrame = f
                    if (dy != 0f) { cumY += dy; tracker.applyShift(0f, dy) }
                    gm.update(0f, dy, interval, true, static, false, tu)
                    if (interval > 0) gaps.add(interval)
                    cumAt[e.idx] = cumX to cumY
                    publish(e.t)
                    if (e.t >= detBusyUntil) startDet(e.idx, e.t + 1.0) else detPendingFrame = e.idx
                }
                is DetEv -> {
                    val f = frames[e.frame]
                    val scale = if (rnd.nextDouble() < 0.2) 1.0 + (if (rnd.nextBoolean()) 0.04 else -0.04) else 1.0
                    val w = OBJ_W * scale + rnd.nextGaussian() * 2.0; val h = OBJ_H * scale + rnd.nextGaussian() * 2.0
                    val cx = f.objX + OBJ_W / 2 + rnd.nextGaussian() * 1.5; val cy = f.objY + OBJ_H / 2 + rnd.nextGaussian() * 1.5
                    val (cxAt, cyAt) = cumAt[e.frame] ?: (cumX to cumY)
                    val ox = (cumX - cxAt).toFloat(); val oy = (cumY - cyAt).toFloat()
                    val x1 = floatArrayOf((cx - w / 2).toFloat() + ox); val x2 = floatArrayOf((cx + w / 2).toFloat() + ox)
                    val y1 = floatArrayOf((cy - h / 2).toFloat() + oy); val y2 = floatArrayOf((cy + h / 2).toFloat() + oy)
                    tracker.update(1, x1, y1, x2, y2, floatArrayOf(0.8f), intArrayOf(3), lastCap, false, measT = f.ts)
                    val age = (lastCap - f.ts).toFloat() + (e.t - f.tc - f.deliver).toFloat()
                    detAgeEma = if (detAgeEma == 0f) age else detAgeEma * 0.9f + age * 0.1f
                    publish(e.t)
                    if (detPendingFrame > lastDetFrame) startDet(detPendingFrame, e.t)
                }
                is VsyncEv -> {
                    val pb = pub ?: continue
                    val b = pb.boxes.firstOrNull() ?: continue
                    val target = e.t + lead
                    val cap = Prediction.capFor(180f, pb.knee, false)
                    val dt = Prediction.effectiveDt((target - b.tMs).toFloat(), pb.knee, cap)
                    val o = IntArray(4)
                    OverlayRenderer.place(b.x1, b.y1, b.x2, b.y2, b.vx, b.vy, dt, 0f, 0f, SX, SY, tu.stableRounding, o)
                    // truth at the glass
                    val tx = c.objX(target) * SX; val ty = c.objY(target) * SY
                    val tw = OBJ_W * SX; val th = OBJ_H * SY
                    val tcx = tx + tw / 2; val tcy = ty + th / 2
                    val bcx = o[0] + o[2] / 2.0; val bcy = o[1] + o[3] / 2.0
                    if (e.t < c.t0 - 100 || e.t > c.end) { prevBox = null; continue }
                    m.n++; m.durationMs += VSYNC
                    if (o[0] > tx + 0.5 || o[1] > ty + 0.5 || o[0] + o[2] < tx + tw - 0.5 || o[1] + o[3] < ty + th - 0.5) {
                        m.leaks++
                        if (debug) println(String.format(java.util.Locale.US, "LEAK %s t=%.1f box y %d..%d truth y %.1f..%.1f x %d..%d truth %.1f..%.1f v=%.3f dtb=%.1f",
                            s.kind, e.t, o[1], o[1] + o[3], ty, ty + th, o[0], o[0] + o[2], tx, tx + tw, b.vy, target - b.tMs))
                    }
                    val pbx = prevBox
                    if (pbx != null) {
                        val dxb = (bcx - (pbx[0] + pbx[2] / 2.0)) - (tcx - prevTruthCx)
                        val dyb = (bcy - (pbx[1] + pbx[3] / 2.0)) - (tcy - prevTruthCy)
                        val j = sqrt(dxb * dxb + dyb * dyb)
                        if (debug && j > 20) println(String.format(java.util.Locale.US,
                            "JIT %s t=%.1f j=%.1f box %d,%d %dx%d prev %d,%d %dx%d id=%d tracks=%d moving=%s v=(%.3f,%.3f) dtb=%.1f knee=%.1f cols=%dx%d",
                            s.kind, e.t, j, o[0], o[1], o[2], o[3], pbx[0], pbx[1], pbx[2], pbx[3], b.id, tracker.tracks.size,
                            tracker.tracks.firstOrNull()?.moving, b.vx, b.vy, (target - b.tMs), pb.knee, b.cols, b.rows))
                        m.jitSq += j * j; m.jitMax = max(m.jitMax, j); m.jit.add(j)
                        val dw = (o[2] - pbx[2]).toDouble(); val dh = (o[3] - pbx[3]).toDouble()
                        m.sizeSq += dw * dw + dh * dh
                        if (dw != 0.0 || dh != 0.0) m.sizeChanges++
                        if (o[0] != pbx[0] || o[1] != pbx[1] || o[2] != pbx[2] || o[3] != pbx[3]) m.moves++
                    }
                    // lag along the motion (positive = box behind the content)
                    val vPage = c.pageSpeed(target)
                    when (s.kind) {
                        Kind.SLOW_SCROLL, Kind.SCROLL, Kind.FLING -> if (vPage > 0) {
                            val lag = (bcy - tcy) // content moves up: box below = behind
                            m.lagSum += lag; m.lagN++; m.lagMsSum += lag / (vPage * SY); m.lagMsN++
                        } else if (s.kind == Kind.FLING && target > c.tStop) {
                            m.overshoot = max(m.overshoot, tcy - bcy)
                        }
                        Kind.OBJECT_MOVE -> if (target > c.t0 + 100) {
                            val lag = tcx - bcx
                            m.lagSum += lag; m.lagN++; m.lagMsSum += lag / (0.25 * SX); m.lagMsN++
                        }
                        else -> {}
                    }
                    // block pattern: grid phase relative to the content, block count changes
                    val bs = o[2].toDouble() / max(b.cols, 1)
                    val ph = ((o[0] - tx) % bs + bs) % bs
                    if (!prevPhase.isNaN()) {
                        var d = abs(ph - prevPhase); if (d > bs / 2) d = bs - d
                        m.phaseSq += d * d
                    }
                    prevPhase = ph
                    val blocks = b.cols * 1000 + b.rows
                    if (prevBlocks >= 0 && blocks != prevBlocks) m.blockChanges++
                    prevBlocks = blocks
                    prevBox = o; prevTruthCx = tcx; prevTruthCy = tcy
                }
            }
        }
        return m
    }

    fun runAll(tu: MotionTuning, seeds: Int = 12): Map<Kind, Metrics> =
        scenarios.associate { s -> s.kind to Metrics().also { acc -> for (k in 0 until seeds) acc.add(run(s, tu, 1000L + k)) } }

    fun report(): String {
        val modes = listOf("v1.8 (Off)" to MotionTuning.V18, "v1.8.1 Normal" to MotionTuning.NORMAL, "v1.8.1 Strong" to MotionTuning.STRONG)
        val res = modes.map { it.first to runAll(it.second) }
        val sb = StringBuilder()
        sb.append("# Veil jitter replay (tools/jitter_sim.sh)\n\n")
        sb.append("Screen px (1080 x 2340), 120 Hz, 12 seeds per scenario. jitter = frame-to-frame box-centre displacement error vs the content " +
            "(0 = moves exactly with it); size = frame-to-frame width/height change; lag = box behind the content at the glass " +
            "(negative = ahead); leak = vsyncs where the object is not fully covered; block shimmer = frame-to-frame shift of the " +
            "pixel grid relative to the content; moves/s = vsyncs on which the box changed at all.\n\n")
        for (s in scenarios) {
            sb.append("## ${s.label}\n\n")
            sb.append("| mode | jitter RMS | jitter p95 | jitter max | size RMS | lag px | lag ms | overshoot px | leak % | block shimmer RMS | block-count changes/s | moves/s |\n")
            sb.append("|---|---|---|---|---|---|---|---|---|---|---|---|\n")
            for ((name, r) in res) {
                val x = r.getValue(s.kind)
                sb.append(String.format(java.util.Locale.US, "| %s | %.2f | %.2f | %.1f | %.2f | %.1f | %.1f | %.1f | %.2f | %.2f | %.2f | %.1f |\n",
                    name, x.jitRms, x.jitP95, x.jitMax, x.sizeRms, x.lagPx, x.lagMs, x.overshoot, x.leakPct, x.phaseRms, x.blockChangesPerS, x.movesPerS))
            }
            sb.append('\n')
        }
        return sb.toString()
    }
}

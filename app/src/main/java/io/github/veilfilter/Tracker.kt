package io.github.veilfilter

import kotlin.math.max
import kotlin.math.min

/**
 * Minimal multi-object tracker tuned for "never show a flash":
 *  - a track is shown on its FIRST hit (score >= [startThreshold]);
 *  - detections between [keepThreshold] and [startThreshold] only refresh
 *    existing tracks;
 *  - greedy IoU matching against constant-velocity predictions;
 *  - v1.6.1 motion-adaptive: a MOVING track follows the detector with no
 *    smoothing (velocity + centre from a least-squares line through the last
 *    3 measurements, which is unbiased for steady motion), and its state is
 *    kept at the frame's CAPTURE time and predicted forward, so object motion
 *    during detector latency is compensated. Only a near-stationary track
 *    gets the anti-jitter deadband / edge hysteresis. The box always
 *    contains the latest measurement, so it can never under-cover;
 *  - a track that is not re-detected stays for [missTimeoutMs];
 *  - a track whose region currently shows our own pixelation in the capture
 *    (full-screen capture self-occlusion, see [markOccluded]) is kept for up
 *    until [occludedTimeoutMs]; its residual motion is frozen but global
 *    scroll still moves it ([applyShift]); it is dropped on a scene cut or when
 *    its surroundings stop matching (RingCheck, in the pipeline).
 *
 * All coordinates are capture-frame pixels, times are milliseconds on one
 * monotonic clock.
 */
class Tracker(
    var startThreshold: Float = 0.30f,
    var keepThreshold: Float = 0.20f,
    var matchIou: Float = 0.15f,
    var missTimeoutMs: Long = 400,
    /** occluded tracks are re-validated by their surroundings (RingCheck), so this is long */
    var occludedTimeoutMs: Long = 20_000,
    var occludedGraceMs: Long = 300,
    var smoothing: Float = 0.6f, // weight of the new measurement
    var maxExtrapolateMs: Long = 200,
    /** residual speed (px/ms) above which a track counts as fast-moving */
    var fastSpeed: Float = 0.25f,
    /** fast tracks: match even weak detections (motion blur lowers scores) */
    var fastKeepThreshold: Float = 0.12f,
    /** fast tracks survive this long without a detection (blurred frames get missed) */
    var fastMissTimeoutMs: Long = 800,
    val maxTracks: Int = 16,
    /** innovation below max(deadbandPx, deadbandFrac x size) counts as detector noise */
    var deadbandPx: Float = 3f,
    var deadbandFrac: Float = 0.04f,
    /** edge smoothing (weight of the measurement) for normal / large motion */
    var fastSmoothing: Float = 0.9f,
    /** fraction of a size decrease applied per detection while still (grow is immediate) */
    var stillShrink: Float = 0.3f,
    /** fitted motion over the history window above this fraction of the smaller box side = moving */
    var motionFrac: Float = 0.05f,
) {
    /** per-class thresholds (null = use the scalar defaults above) */
    var startByClass: FloatArray? = null
    var keepByClass: FloatArray? = null
    var fastKeepByClass: FloatArray? = null
    private fun startOf(c: Int) = startByClass?.getOrNull(c) ?: startThreshold
    private fun keepOf(c: Int) = keepByClass?.getOrNull(c) ?: keepThreshold
    private fun fastKeepOf(c: Int) = fastKeepByClass?.getOrNull(c) ?: fastKeepThreshold

    /** Apply a class set live: thresholds, and drop tracks of classes no longer censored. */
    fun applyClasses(mask: BooleanArray, start: FloatArray, keep: FloatArray, fastKeep: FloatArray) {
        startByClass = start; keepByClass = keep; fastKeepByClass = fastKeep
        tracks.removeAll { it.cls !in mask.indices || !mask[it.cls] }
    }

    class Track(val id: Int) {
        var cls = 0
        var score = 0f
        // smoothed box at time [t]
        var x1 = 0f; var y1 = 0f; var x2 = 0f; var y2 = 0f
        var t = 0L
        // centre velocity, px per ms
        var vx = 0f; var vy = 0f
        var hits = 0
        var lastSeen = 0L
        var born = 0L
        var lastOccluded = Long.MIN_VALUE / 2
        var occludedSince = Long.MIN_VALUE / 2
        /** time of the last detector confirmation (match or birth) */
        var confirmedAt = Long.MIN_VALUE / 2
        /** extra keep-alive (ms) for tracks found by tile passes, which revisit slowly */
        var keepMs = 0L
        fun speed() = kotlin.math.sqrt(vx * vx + vy * vy)
        /** speed at the last detection (not damped while unobserved) */
        var seenSpeed = 0f
        val ring = RingSignature()
        /** last raw measurement: centre (scroll-compensated) and its capture time */
        var lmx = 0f; var lmy = 0f; var lmt = Long.MIN_VALUE / 2
        /** v1.6.1 motion state: moving tracks follow the measurement exactly, no smoothing */
        var moving = false
        var stillCount = 0
        /** last [HIST] measurement centres (scroll-compensated) + capture times, ring */
        val hT = LongArray(HIST); val hX = FloatArray(HIST); val hY = FloatArray(HIST)
        var hN = 0; var hI = 0
        fun push(t: Long, x: Float, y: Float) { hT[hI] = t; hX[hI] = x; hY[hI] = y; hI = (hI + 1) % HIST; if (hN < HIST) hN++ }

        fun isOccluded(now: Long, graceMs: Long) = now - lastOccluded <= graceMs

        /** Box predicted at time [now] (constant velocity, bounded). */
        fun predictX1(now: Long, maxMs: Long) = x1 + vx * dt(now, maxMs)
        fun predictY1(now: Long, maxMs: Long) = y1 + vy * dt(now, maxMs)
        fun predictX2(now: Long, maxMs: Long) = x2 + vx * dt(now, maxMs)
        fun predictY2(now: Long, maxMs: Long) = y2 + vy * dt(now, maxMs)
        fun dt(now: Long, maxMs: Long): Float = min(max(now - t, 0L), maxMs).toFloat()
    }

    companion object { const val HIST = 3 }

    private var nextId = 1
    val tracks = ArrayList<Track>(maxTracks)

    // scratch for matching
    private val detUsed = BooleanArray(64)
    private val trackUsed = BooleanArray(64)
    private val pairIou = FloatArray(64 * 64)

    fun clear() { tracks.clear() }

    /** Record that the capture at [now] shows our own censor over [track]. */
    fun markOccluded(track: Track, now: Long) {
        if (!track.isOccluded(now, occludedGraceMs)) track.occludedSince = now
        track.lastOccluded = now
        // We cannot observe the object's own motion under our censor: zero the
        // residual velocity. Global scroll still moves it (applyShift).
        track.vx = 0f; track.vy = 0f
    }

    /**
     * Global content shift (scroll) measured on the newest frame: move every
     * track, occluded or not, so censors stay glued to the content.
     */
    fun applyShift(dx: Float, dy: Float) {
        if (dx == 0f && dy == 0f) return
        for (tr in tracks) {
            tr.x1 += dx; tr.x2 += dx; tr.y1 += dy; tr.y2 += dy
            tr.lmx += dx; tr.lmy += dy
            for (k in 0 until tr.hN) { tr.hX[k] += dx; tr.hY[k] += dy }
        }
    }

    fun remove(track: Track) { tracks.remove(track) }

    /**
     * Update with detections from the frame captured at [now].
     * @param sceneCut the scene guard fired on this frame
     */
    fun update(
        n: Int, dx1: FloatArray, dy1: FloatArray, dx2: FloatArray, dy2: FloatArray,
        dScore: FloatArray, dCls: IntArray, now: Long, sceneCut: Boolean,
        /** per detection: touches an interior tile edge (partial view); may be null */
        clipped: BooleanArray? = null,
        /** keep-alive to give tracks confirmed by this pass (tile passes), 0 = default */
        keepMs: Long = 0L,
        /**
         * capture time of the frame the detections come from (v1.6.1). Track state is
         * kept in this time base and predicted forward, so the object's own motion
         * during detector latency is compensated (not only the page scroll).
         */
        measT: Long = now,
    ) {
        val nd = min(n, detUsed.size)
        val nt = min(tracks.size, trackUsed.size)
        for (i in 0 until nd) detUsed[i] = false
        for (j in 0 until nt) trackUsed[j] = false

        // IoU between predicted tracks and detections
        for (j in 0 until nt) {
            val tr = tracks[j]
            val px1 = tr.predictX1(measT, maxExtrapolateMs)
            val py1 = tr.predictY1(measT, maxExtrapolateMs)
            val px2 = tr.predictX2(measT, maxExtrapolateMs)
            val py2 = tr.predictY2(measT, maxExtrapolateMs)
            val fast = tr.seenSpeed > fastSpeed
            for (i in 0 until nd) {
                // weak detections only keep fast (blurred) tracks alive
                val c = dCls[i]
                pairIou[j * 64 + i] = if (dScore[i] < keepOf(c) && !(fast && dScore[i] >= fastKeepOf(c))) 0f
                else YoloDecoder.iou(px1, py1, px2, py2, dx1[i], dy1[i], dx2[i], dy2[i])
            }
        }
        // greedy: best pair first
        while (true) {
            var best = matchIou
            var bj = -1; var bi = -1
            for (j in 0 until nt) {
                if (trackUsed[j]) continue
                for (i in 0 until nd) {
                    if (detUsed[i]) continue
                    val v = pairIou[j * 64 + i]
                    if (v > best) { best = v; bj = j; bi = i }
                }
            }
            if (bj < 0) break
            trackUsed[bj] = true
            detUsed[bi] = true
            val tr = tracks[bj]
            if (clipped != null && clipped[bi]) {
                // partial view at a tile edge: may grow the track, never shrink it
                val d = tr.dt(measT, maxExtrapolateMs)
                correct(
                    tr, min(dx1[bi], tr.x1 + tr.vx * d), min(dy1[bi], tr.y1 + tr.vy * d),
                    max(dx2[bi], tr.x2 + tr.vx * d), max(dy2[bi], tr.y2 + tr.vy * d), dScore[bi], dCls[bi], measT, now,
                )
            } else correct(tr, dx1[bi], dy1[bi], dx2[bi], dy2[bi], dScore[bi], dCls[bi], measT, now)
            if (keepMs > tr.keepMs) tr.keepMs = keepMs
        }

        // unmatched tracks: age out
        var j = nt - 1
        while (j >= 0) {
            val tr = tracks[j]
            if (!trackUsed[j]) {
                val occluded = tr.isOccluded(now, occludedGraceMs)
                val keep = if (occluded) {
                    !sceneCut && now - tr.lastSeen <= occludedTimeoutMs
                } else {
                    var limit = missTimeoutMs
                    if (tr.seenSpeed > fastSpeed) limit = max(limit, fastMissTimeoutMs)
                    if (tr.keepMs > limit) limit = tr.keepMs
                    now - tr.lastSeen <= limit
                }
                if (!keep) {
                    tracks.removeAt(j)
                } else {
                    // advance state to now so later predictions stay bounded,
                    // and damp velocity while unobserved
                    val d = tr.dt(measT, maxExtrapolateMs)
                    tr.x1 += tr.vx * d; tr.x2 += tr.vx * d
                    tr.y1 += tr.vy * d; tr.y2 += tr.vy * d
                    if (measT > tr.t) tr.t = measT
                    // damp slower while fast-moving so a blurred object stays covered
                    val damp = if (tr.seenSpeed > fastSpeed) 0.8f else 0.5f
                    tr.vx *= damp; tr.vy *= damp
                }
            }
            j--
        }

        // unmatched detections: new tracks (only confident ones)
        for (i in 0 until nd) {
            if (detUsed[i] || dScore[i] < startOf(dCls[i])) continue
            if (tracks.size >= maxTracks) break
            val tr = Track(nextId++) // rare; ids are never reused
            tr.x1 = dx1[i]; tr.y1 = dy1[i]; tr.x2 = dx2[i]; tr.y2 = dy2[i]
            tr.vx = 0f; tr.vy = 0f
            tr.t = measT; tr.lastSeen = now; tr.born = now
            tr.lmx = (dx1[i] + dx2[i]) * 0.5f; tr.lmy = (dy1[i] + dy2[i]) * 0.5f; tr.lmt = measT
            tr.moving = false; tr.stillCount = 0
            tr.hN = 0; tr.hI = 0; tr.push(measT, tr.lmx, tr.lmy)
            tr.hits = 1; tr.score = dScore[i]; tr.cls = dCls[i]
            tr.lastOccluded = Long.MIN_VALUE / 2
            tr.occludedSince = Long.MIN_VALUE / 2
            tr.confirmedAt = now
            tr.keepMs = keepMs
            tracks.add(tr)
        }
    }

    private fun correct(tr: Track, mx1: Float, my1: Float, mx2: Float, my2: Float, s: Float, c: Int, measT: Long, now: Long) {
        // track state predicted to the measurement's capture time
        val px1 = tr.predictX1(measT, maxExtrapolateMs)
        val py1 = tr.predictY1(measT, maxExtrapolateMs)
        val px2 = tr.predictX2(measT, maxExtrapolateMs)
        val py2 = tr.predictY2(measT, maxExtrapolateMs)
        val mcx = (mx1 + mx2) * 0.5f; val mcy = (my1 + my2) * 0.5f
        // measurement-to-measurement velocity (both scroll-compensated, real capture times)
        val mdt = (measT - tr.lmt).toFloat()
        val haveMv = mdt in 4f..400f
        val mvx = if (haveMv) (mcx - tr.lmx) / mdt else 0f
        val mvy = if (haveMv) (mcy - tr.lmy) / mdt else 0f
        // displacement vs the track's own (predicted) box: one measurement's noise, not two
        val disp = max(kotlin.math.abs(mcx - (px1 + px2) * 0.5f), kotlin.math.abs(mcy - (py1 + py2) * 0.5f))
        val dead = max(deadbandPx, deadbandFrac * max(mx2 - mx1, my2 - my1))
        val wasMoving = tr.moving
        // a gap (track lost for a while, then re-found) restarts the motion history
        if (!haveMv) tr.hN = 0
        tr.push(measT, mcx, mcy)
        // least-squares line through the last measurements: velocity + centre at measT
        var fvx = 0f; var fvy = 0f; var fcx = mcx; var fcy = mcy; var span = 0f
        if (tr.hN >= 2) {
            var st = 0.0; var sx = 0.0; var sy = 0.0
            for (k in 0 until tr.hN) { st += (tr.hT[k] - measT).toDouble(); sx += tr.hX[k]; sy += tr.hY[k] }
            val n = tr.hN.toDouble(); val mt = st / n; val mxm = sx / n; val mym = sy / n
            var stt = 0.0; var stx = 0.0; var sty = 0.0; var tmin = 0L
            for (k in 0 until tr.hN) {
                val dt = (tr.hT[k] - measT) - mt
                stt += dt * dt; stx += dt * (tr.hX[k] - mxm); sty += dt * (tr.hY[k] - mym)
                tmin = min(tmin, tr.hT[k] - measT)
            }
            if (stt > 1.0) {
                fvx = (stx / stt).toFloat(); fvy = (sty / stt).toFloat()
                fcx = (mxm - fvx * mt).toFloat(); fcy = (mym - fvy * mt).toFloat()
                span = (-tmin).toFloat()
            }
        }
        // --- motion-adaptive smoothing ---
        // moving: the fitted motion over the history window clearly exceeds detector noise
        // (threshold from the box's SMALLER side, so a tall body is not "still" while it
        // sways), or one big jump. Leaves the moving state only after 2 still measurements.
        val small = min(mx2 - mx1, my2 - my1)
        val motion = max(kotlin.math.abs(fvx), kotlin.math.abs(fvy)) * span
        val movingNow = motion > max(deadbandPx + 2f, motionFrac * small) || disp > dead
        if (movingNow) { tr.moving = true; tr.stillCount = 0 } else if (++tr.stillCount >= 2) tr.moving = false
        if (tr.moving && !wasMoving && tr.hN > 2) {
            // motion onset: the still part of the history would halve the first estimate
            fvx = mvx; fvy = mvy; fcx = mcx; fcy = mcy
            tr.hN = 0; tr.hI = 0; tr.push(tr.lmt, tr.lmx, tr.lmy); tr.push(measT, mcx, mcy)
        }
        if (tr.moving || tr.hits == 1) {
            // instant follow, no smoothing lag: velocity + centre from the line fit
            // (unbiased for steady motion, about half the noise of a 2-point difference),
            // size from the latest measurement
            if (tr.hN >= 2) { tr.vx = fvx; tr.vy = fvy } else if (haveMv) { tr.vx = mvx; tr.vy = mvy }
            val hw = (mx2 - mx1) * 0.5f; val hh = (my2 - my1) * 0.5f
            // (never under-cover the latest measurement: the fit is unbiased, so this only
            // adds its few px of noise offset, not lag)
            tr.x1 = min(fcx - hw, mx1); tr.x2 = max(fcx + hw, mx2); tr.y1 = min(fcy - hh, my1); tr.y2 = max(fcy + hh, my2)
        } else {
            // near-stationary: detector noise, not motion. Velocity decays; edges get
            // hysteresis (grow at once to the measurement + margin, shrink slowly), so
            // a still box does not shimmer. Always contains the measurement.
            tr.vx *= 0.5f; tr.vy *= 0.5f
            val hm = 0.75f * dead
            fun lo(p: Float, m: Float) = when {
                m - hm < p -> m - hm
                m - hm - p > 2f * hm -> p + (m - hm - p) * stillShrink
                else -> p
            }
            fun hi(p: Float, m: Float) = when {
                m + hm > p -> m + hm
                p - (m + hm) > 2f * hm -> p + (m + hm - p) * stillShrink
                else -> p
            }
            tr.x1 = min(lo(px1, mx1), mx1); tr.y1 = min(lo(py1, my1), my1)
            tr.x2 = max(hi(px2, mx2), mx2); tr.y2 = max(hi(py2, my2), my2)
        }
        // limit to something physically plausible
        tr.vx = tr.vx.coerceIn(-2f, 2f)
        tr.vy = tr.vy.coerceIn(-2f, 2f)
        tr.seenSpeed = tr.speed()
        tr.lmx = mcx; tr.lmy = mcy; tr.lmt = measT
        tr.t = measT
        tr.lastSeen = now
        tr.confirmedAt = now
        tr.hits++
        tr.score = s
        tr.cls = c
    }
}

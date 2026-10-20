package io.github.veilfilter

import kotlin.math.max
import kotlin.math.min

/**
 * Minimal multi-object tracker tuned for "never show a flash":
 *  - a track is shown on its FIRST hit (score >= [startThreshold]);
 *  - detections between [keepThreshold] and [startThreshold] only refresh
 *    existing tracks;
 *  - greedy IoU matching against constant-velocity predictions;
 *  - EMA smoothing, but the published box always contains the latest
 *    measurement (union), so smoothing can never under-cover;
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

        fun isOccluded(now: Long, graceMs: Long) = now - lastOccluded <= graceMs

        /** Box predicted at time [now] (constant velocity, bounded). */
        fun predictX1(now: Long, maxMs: Long) = x1 + vx * dt(now, maxMs)
        fun predictY1(now: Long, maxMs: Long) = y1 + vy * dt(now, maxMs)
        fun predictX2(now: Long, maxMs: Long) = x2 + vx * dt(now, maxMs)
        fun predictY2(now: Long, maxMs: Long) = y2 + vy * dt(now, maxMs)
        fun dt(now: Long, maxMs: Long): Float = min(max(now - t, 0L), maxMs).toFloat()
    }

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
    ) {
        val nd = min(n, detUsed.size)
        val nt = min(tracks.size, trackUsed.size)
        for (i in 0 until nd) detUsed[i] = false
        for (j in 0 until nt) trackUsed[j] = false

        // IoU between predicted tracks and detections
        for (j in 0 until nt) {
            val tr = tracks[j]
            val px1 = tr.predictX1(now, maxExtrapolateMs)
            val py1 = tr.predictY1(now, maxExtrapolateMs)
            val px2 = tr.predictX2(now, maxExtrapolateMs)
            val py2 = tr.predictY2(now, maxExtrapolateMs)
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
                val d = tr.dt(now, maxExtrapolateMs)
                correct(
                    tr, min(dx1[bi], tr.x1 + tr.vx * d), min(dy1[bi], tr.y1 + tr.vy * d),
                    max(dx2[bi], tr.x2 + tr.vx * d), max(dy2[bi], tr.y2 + tr.vy * d), dScore[bi], dCls[bi], now,
                )
            } else correct(tr, dx1[bi], dy1[bi], dx2[bi], dy2[bi], dScore[bi], dCls[bi], now)
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
                    val d = tr.dt(now, maxExtrapolateMs)
                    tr.x1 += tr.vx * d; tr.x2 += tr.vx * d
                    tr.y1 += tr.vy * d; tr.y2 += tr.vy * d
                    tr.t = now
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
            tr.t = now; tr.lastSeen = now; tr.born = now
            tr.hits = 1; tr.score = dScore[i]; tr.cls = dCls[i]
            tr.lastOccluded = Long.MIN_VALUE / 2
            tr.occludedSince = Long.MIN_VALUE / 2
            tr.confirmedAt = now
            tr.keepMs = keepMs
            tracks.add(tr)
        }
    }

    private fun correct(tr: Track, mx1: Float, my1: Float, mx2: Float, my2: Float, s: Float, c: Int, now: Long) {
        val dtMs = (now - tr.t).toFloat()
        val px1 = tr.predictX1(now, maxExtrapolateMs)
        val py1 = tr.predictY1(now, maxExtrapolateMs)
        val px2 = tr.predictX2(now, maxExtrapolateMs)
        val py2 = tr.predictY2(now, maxExtrapolateMs)
        if (dtMs > 0f) {
            val ocx = (tr.x1 + tr.x2) * 0.5f
            val ocy = (tr.y1 + tr.y2) * 0.5f
            val ncx = (mx1 + mx2) * 0.5f
            val ncy = (my1 + my2) * 0.5f
            val ivx = (ncx - ocx) / dtMs
            val ivy = (ncy - ocy) / dtMs
            // react faster to large motion (fast pans / moving bodies)
            val a = if (kotlin.math.sqrt(ivx * ivx + ivy * ivy) > fastSpeed) 0.8f else 0.5f
            tr.vx = if (tr.hits == 1) ivx * a else tr.vx * (1 - a) + ivx * a
            tr.vy = if (tr.hits == 1) ivy * a else tr.vy * (1 - a) + ivy * a
            // limit to something physically plausible (~4 screen-widths/s at 540 px)
            tr.vx = tr.vx.coerceIn(-2f, 2f)
            tr.vy = tr.vy.coerceIn(-2f, 2f)
            tr.seenSpeed = tr.speed()
        }
        val k = smoothing
        val sx1 = px1 + (mx1 - px1) * k
        val sy1 = py1 + (my1 - py1) * k
        val sx2 = px2 + (mx2 - px2) * k
        val sy2 = py2 + (my2 - py2) * k
        // never smaller than the measurement
        tr.x1 = min(sx1, mx1); tr.y1 = min(sy1, my1)
        tr.x2 = max(sx2, mx2); tr.y2 = max(sy2, my2)
        tr.t = now
        tr.lastSeen = now
        tr.confirmedAt = now
        tr.hits++
        tr.score = s
        tr.cls = c
    }
}

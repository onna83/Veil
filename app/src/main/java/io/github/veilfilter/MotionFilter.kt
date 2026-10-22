package io.github.veilfilter

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * v1.8.1 box smoothing level (Performance › Box smoothing).
 * OFF reproduces v1.8 exactly; NORMAL is the default.
 */
enum class Smoothing(val id: String) {
    OFF("off"), NORMAL("normal"), STRONG("strong");

    val tuning: MotionTuning get() = when (this) {
        OFF -> MotionTuning.V18
        NORMAL -> MotionTuning.NORMAL
        STRONG -> MotionTuning.STRONG
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id }
    }
}

/**
 * Anti-jitter parameters. All filtering acts on VELOCITY (and box size), never
 * on the box position itself: positions still come straight from the newest
 * frame (page shift) and the newest detection, so smoothing adds no position lag
 * for steady motion. Velocities are in capture px per ms.
 */
class MotionTuning(
    /** true = v1.8 behaviour everywhere below */
    val legacy: Boolean,
    // ---- page (global) velocity: 1-euro filter on dx / interval ----
    val gMinCutoffHz: Float = 4f,
    val gBeta: Float = 0.6f,
    val gDCutoffHz: Float = 6f,
    // ---- object (track) velocity: 1-euro filter on the line-fit velocity per detection ----
    val tMinCutoffHz: Float = 2.5f,
    val tBeta: Float = 2f,
    val tDCutoffHz: Float = 4f,
    /** soft dead zone on object velocity: below v0 nothing is extrapolated, above v1 all of it */
    val vDead0: Float = 0.025f,
    val vDead1: Float = 0.08f,
    /** a motion onset seen in ONE measurement only gets this share of its velocity */
    val onsetTrust: Float = 0.5f,
    /** entering the moving state needs the fitted motion to beat the threshold by this factor */
    val onsetGate: Float = 1.5f,
    /** moving boxes: size grows at once, shrinks by this fraction of the gap per detection */
    val sizeShrink: Float = 0.35f,
    /** knee also covers the recent worst capture gap, so irregular frames do not brake the box */
    val gapKnee: Boolean = true,
    /** screen rounding: width/height from the box size (not floor(left) / ceil(right)) */
    val stableRounding: Boolean = true,
    /** moving boxes: block grid anchored to the box (whole blocks, hysteresis) instead of re-cut every frame */
    val anchoredBlocks: Boolean = true,
    /** cols/rows only drop when the box is this many blocks smaller */
    val blockHysteresis: Float = 0.35f,
) {
    companion object {
        val V18 = MotionTuning(legacy = true, gapKnee = false, stableRounding = false, anchoredBlocks = false)
        val NORMAL = MotionTuning(legacy = false)
        val STRONG = MotionTuning(
            legacy = false, gMinCutoffHz = 2.5f, gBeta = 0.4f, tMinCutoffHz = 1.5f, tBeta = 1.5f,
            vDead0 = 0.04f, vDead1 = 0.12f, onsetTrust = 0.35f, onsetGate = 1.8f, sizeShrink = 0.2f, blockHysteresis = 0.5f,
        )
    }
}

/**
 * 1-euro filter (Casiez et al. 2012) for one velocity component: a low-pass
 * whose cutoff rises with the rate of change, so a fling start/stop is followed
 * at once while steady motion is smoothed.
 */
class OneEuro {
    var value = 0f; private set
    private var deriv = 0f
    var ready = false; private set

    fun reset(v: Float) { value = v; deriv = 0f; ready = true }
    fun clear() { ready = false; value = 0f; deriv = 0f }

    fun filter(v: Float, dtMs: Float, minCutoffHz: Float, beta: Float, dCutoffHz: Float): Float {
        if (!ready || dtMs <= 0f) { reset(v); return v }
        val dt = dtMs / 1000f
        val d = (v - value) / dt
        deriv += alpha(dCutoffHz, dt) * (d - deriv)
        val fc = minCutoffHz + beta * abs(deriv)
        value += alpha(fc, dt) * (v - value)
        return value
    }

    companion object {
        fun alpha(fcHz: Float, dtS: Float): Float {
            val tau = 1f / (2f * PI.toFloat() * max(fcHz, 1e-3f))
            return 1f / (1f + tau / dtS)
        }

        /** 0 below [v0], 1 above [v1], smooth in between (applied to |v|) */
        fun deadZoneGain(speed: Float, v0: Float, v1: Float): Float {
            if (speed <= v0) return 0f
            if (speed >= v1) return 1f
            val x = (speed - v0) / (v1 - v0)
            return x * x * (3f - 2f * x)
        }
    }
}

/**
 * Page (global) velocity from the shift estimator, capture px / ms.
 * Legacy = v1.8: per-frame EMA (k 0.6) of dx / interval.
 */
class GlobalMotion {
    var vx = 0f; private set
    var vy = 0f; private set
    private val fx = OneEuro()
    private val fy = OneEuro()

    fun reset() { vx = 0f; vy = 0f; fx.clear(); fy.clear() }

    /**
     * @param confident the shift estimate is trustworthy, [static] = nothing moved
     * @param hintScroll the app reports scrolling (accessibility mode)
     */
    fun update(dx: Float, dy: Float, intervalMs: Float, confident: Boolean, static: Boolean, hintScroll: Boolean, t: MotionTuning) {
        if (t.legacy) {
            if (confident) {
                if (intervalMs in 1f..200f) {
                    val k = 0.6f
                    vx = vx * (1 - k) + (dx / intervalMs) * k
                    vy = vy * (1 - k) + (dy / intervalMs) * k
                } else { vx = 0f; vy = 0f }
                if (static) { vx = 0f; vy = 0f }
            } else {
                val d = if (hintScroll) 0.9f else 0.5f
                vx *= d; vy *= d
            }
            if (abs(vx) < 0.005f) vx = 0f
            if (abs(vy) < 0.005f) vy = 0f
            return
        }
        if (confident) {
            if (static || intervalMs !in 1f..200f) {
                // a stop is a stop: no smoothing tail (that would overshoot)
                vx = 0f; vy = 0f; fx.clear(); fy.clear()
            } else {
                vx = axis(fx, dx / intervalMs, intervalMs, t)
                vy = axis(fy, dy / intervalMs, intervalMs, t)
            }
        } else {
            val d = if (hintScroll) 0.9f else 0.5f
            vx *= d; vy *= d
            if (fx.ready) fx.reset(vx)
            if (fy.ready) fy.reset(vy)
        }
        if (abs(vx) < 0.005f) { vx = 0f }
        if (abs(vy) < 0.005f) { vy = 0f }
    }

    private fun axis(f: OneEuro, v: Float, dt: Float, t: MotionTuning): Float {
        // start from rest or a reversal: take the measurement (no lag on onset)
        if (!f.ready || f.value == 0f || (v != 0f && (v > 0f) != (f.value > 0f) && abs(f.value) > 0.02f)) {
            f.reset(v); return v
        }
        return f.filter(v, dt, t.gMinCutoffHz, t.gBeta, t.gDCutoffHz)
    }
}

/** Recent worst capture gap (ms), decaying slowly (for the prediction knee). */
class GapTracker {
    var gapMs = 0f; private set
    fun add(intervalMs: Float) {
        if (intervalMs !in 1f..250f) return
        gapMs = max(intervalMs, gapMs * 0.97f + intervalMs * 0.03f)
    }
    fun reset() { gapMs = 0f }

    companion object {
        /** cap the gap term: a long gap is a stop, not irregular delivery */
        fun term(gapMs: Float, intervalMs: Float) = min(gapMs * 1.1f, max(intervalMs, 16f) * 3f)
    }
}

package io.github.veilfilter

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * v1.8 vsync extrapolation horizon.
 *
 * Every vsync the renderer places a box at pos(tCap) + v * dt. Up to the
 * [knee] (the age a box normally has when it reaches the glass: capture
 * delivery + analysis + hop + draw-to-screen lead + one capture interval)
 * dt is the real age, so boxes LEAD the motion exactly. Past the knee no
 * newer frame has come although one was due; with MediaProjection that
 * usually means the content stopped (unchanged screens deliver no frames),
 * so dt saturates smoothly instead of running on: the overshoot after a
 * fling stops is bounded by v * [tail] instead of v * cap. While the app
 * itself reports scrolling (accessibility hint) the motion is known to go
 * on, so the tail may use the full per-model cap ([ModelSize.predictCapMs],
 * 180-230 ms).
 */
object Prediction {
    /** tail time constant when nothing says the motion continues */
    const val SETTLE_TAIL_MS = 40f

    /** dt actually extrapolated for a box of age [rawMs] */
    fun effectiveDt(rawMs: Float, kneeMs: Float, capMs: Float): Float {
        if (rawMs <= 0f) return 0f
        val k = min(max(kneeMs, 0f), capMs)
        if (rawMs <= k) return rawMs
        val tau = capMs - k
        if (tau <= 0f) return k
        return k + tau * (1f - exp(-(rawMs - k) / tau))
    }

    /** cap for this frame: full per-model cap while the app reports scrolling, else knee + a short tail */
    fun capFor(modelCapMs: Float, kneeMs: Float, scrollActive: Boolean): Float =
        if (scrollActive) modelCapMs else min(modelCapMs, kneeMs + SETTLE_TAIL_MS)

    /**
     * Knee from the pipeline's measured chain (ms): capture delivery + analysis,
     * main-thread hop, draw-to-screen lead, plus 1.5 capture intervals (the next
     * frame is due by then). Clamped to [2 vsync, cap].
     */
    fun knee(
        deliverMs: Float, analyzeMs: Float, hopMs: Float, leadMs: Float, intervalMs: Float, vsyncMs: Float, capMs: Float,
        /** v1.8.1: recent worst capture gap term ([GapTracker.term]); 0 = v1.8 */
        gapMs: Float = 0f,
    ): Float {
        val iv = if (intervalMs in 1f..200f) intervalMs else vsyncMs
        return (deliverMs + analyzeMs + hopMs + leadMs + max(1.5f * iv, gapMs)).coerceIn(2f * vsyncMs, capMs)
    }
}

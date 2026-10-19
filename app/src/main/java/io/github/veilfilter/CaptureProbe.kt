package io.github.veilfilter

import kotlin.math.abs

/**
 * Finds out whether our own overlays are captured (full-screen capture) or
 * not (single-app capture), and measures how long a change in our overlay
 * takes to show up in the capture (= draw -> composition latency).
 *
 * A tiny probe window alternates between two dark colours. If the captured
 * pixel at its location follows the toggles, the capture contains our
 * overlays. Thread-safe (renderer on main, pipeline on the capture thread).
 */
class CaptureProbe(
    private val hitsForFull: Int = 2,
    private val missesForSingle: Int = 3,
    private val missTimeoutMs: Long = 600,
    private val fullCooldownMs: Long = 1000,
) {
    enum class Mode { UNKNOWN, FULL_SCREEN, SINGLE_APP }

    @Volatile var mode = Mode.UNKNOWN
        private set
    /** draw -> appears in capture, ms (EMA), or -1 if unknown */
    @Volatile var latencyMs = -1f
        private set

    private var index = 0          // colour currently requested
    private var shownAt = -1L      // when the renderer drew it (ms), -1 = not yet
    private var hit = false
    private var hits = 0
    private var misses = 0
    private var nextToggleAt = 0L

    /** Colour the probe should show now. */
    @Synchronized fun color(): Int = COLORS[index]

    /** Renderer: the probe was drawn with colour [i] at [tMs]. */
    @Synchronized fun onShown(i: Int, tMs: Long) {
        if (i == index && shownAt < 0) shownAt = tMs
    }

    @Synchronized fun currentIndex() = index

    private fun close(c: Int, ref: Int): Boolean {
        val dr = abs(((c shr 16) and 0xFF) - ((ref shr 16) and 0xFF))
        val dg = abs(((c shr 8) and 0xFF) - ((ref shr 8) and 0xFF))
        val db = abs((c and 0xFF) - (ref and 0xFF))
        return dr + dg + db <= TOL
    }

    /**
     * Pipeline: captured colour at the probe location in a frame composed at
     * [tCapMs]. Returns true if the probe should toggle colour now.
     */
    @Synchronized fun onFrame(captured: Int, tCapMs: Long): Boolean {
        if (mode == Mode.SINGLE_APP) return false
        if (shownAt < 0) return false // not drawn yet
        if (!hit) {
            if (tCapMs >= shownAt && close(captured, COLORS[index]) && !close(captured, COLORS[1 - index])) {
                hit = true
                hits++; misses = 0
                val lat = (tCapMs - shownAt).toFloat()
                latencyMs = if (latencyMs < 0) lat else latencyMs * 0.7f + lat * 0.3f
                if (hits >= hitsForFull) mode = Mode.FULL_SCREEN
                nextToggleAt = tCapMs + if (mode == Mode.FULL_SCREEN) fullCooldownMs else 100
            } else if (tCapMs - shownAt > missTimeoutMs) {
                misses++; hits = 0
                if (misses >= missesForSingle && mode != Mode.FULL_SCREEN) mode = Mode.SINGLE_APP
                return toggle()
            }
            return false
        }
        return if (tCapMs >= nextToggleAt) toggle() else false
    }

    private fun toggle(): Boolean {
        index = 1 - index
        shownAt = -1
        hit = false
        return mode != Mode.SINGLE_APP
    }

    companion object {
        val COLORS = intArrayOf(0xFF141414.toInt(), 0xFF464646.toInt())
        private const val TOL = 36
        /** probe size in capture pixels */
        const val SIZE = 6
    }
}

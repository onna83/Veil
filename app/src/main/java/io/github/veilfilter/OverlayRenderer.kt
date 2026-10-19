package io.github.veilfilter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Draws censors as a pool of small TYPE_APPLICATION_OVERLAY windows, one per
 * box (Android 12+ blocks touches through opaque overlays, so per-box windows
 * keep the rest of the screen touchable; there is never a full-screen window).
 *
 * Latency work in v1.1:
 *  - All updates are batched into ONE Choreographer frame callback (the
 *    freshest pipeline state wins), extrapolated to the moment the frame will
 *    actually be on screen ([leadMs]).
 *  - Moving windows: a window is sized box + a margin in the direction of
 *    motion; while the box stays inside it only the content offset changes
 *    (invalidate = one draw, no WindowManager relayout IPC). A relayout
 *    happens only when the box leaves its window or changes size.
 *  - Unused windows are parked off-screen instead of GONE, so their surfaces
 *    stay allocated and re-showing them costs one relayout, not a new surface.
 *  - A tiny probe window lets the pipeline detect whether our overlays are
 *    captured (full-screen capture) and measure draw->capture latency.
 *
 * Main thread only, except [submit], [drawnFor], [drawnRects] and metrics.
 */
class OverlayRenderer(private val ctx: Context, private val probe: CaptureProbe) : Choreographer.FrameCallback {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    @Volatile private var state: RenderState? = null
    private val drawnMap = ConcurrentHashMap<Int, DrawnBox>()

    /** Where the captured content sits on screen, in screen pixels. Main thread. */
    val contentRect = Rect()
    /** Capture frame size, used to place the probe (main thread). */
    @Volatile var frameW = 0
    @Volatile var frameH = 0

    /** How far ahead of the frame time boxes are extrapolated (set by the pipeline). */
    @Volatile var leadMs = 16f
    @Volatile var vsyncMs = 16.7f

    // ---- metrics (read from any thread) ----
    @Volatile var applyMsEma = 0f; private set
    @Volatile var layoutMsEma = 0f; private set      // cost of one updateViewLayout call
    /** pipeline publish -> applied on the main thread (new states only) */
    @Volatile var hopMsEma = 0f; private set
    @Volatile var relayoutsPerSec = 0f; private set
    @Volatile var drawsPerSec = 0f; private set
    private var relayouts = 0
    private var draws = 0
    private var metricWindowStart = 0L

    private class Slot(val view: PixelView, val lp: WindowManager.LayoutParams) {
        var id = NONE
        var used = false
        var parked = true
        var firstShown = 0L
    }

    private val slots = ArrayList<Slot>()
    private var frameScheduled = false
    private var hud: TextView? = null
    private var probeView: ProbeView? = null
    private var probeLp: WindowManager.LayoutParams? = null
    private var released = false

    fun drawnFor(id: Int): DrawnBox? = drawnMap[id]

    /** Capture-coordinate rects of everything currently drawn (for masking). Any thread. */
    fun drawnRects(out: FloatArray, maxN: Int): Int {
        var n = 0
        for (d in drawnMap.values) {
            if (n >= maxN) break
            out[4 * n] = d.x1; out[4 * n + 1] = d.y1; out[4 * n + 2] = d.x2; out[4 * n + 3] = d.y2
            n++
        }
        return n
    }

    /**
     * New state: apply right away on the main thread instead of waiting for
     * the next frame callback. The windows' draws then go out with the very
     * next vsync traversal; a new box (appearance/scene guard, fresh
     * detection) costs one hop + one vsync, not two. Motion continues from
     * Choreographer frame callbacks.
     */
    private val applyNew = Runnable {
        if (released) return@Runnable
        val t0 = System.nanoTime()
        val s = state
        if (s != null && s.publishedNs != lastAppliedPublish) {
            lastAppliedPublish = s.publishedNs
            hopMsEma = ema(hopMsEma, (t0 - s.publishedNs) / 1e6f)
        }
        val moving = apply(t0)
        applyMsEma = ema(applyMsEma, (System.nanoTime() - t0) / 1e6f)
        updateRates(t0)
        if (moving) scheduleFrame()
    }
    private var lastAppliedPublish = 0L

    /** Thread-safe: hand over a new state; applied immediately on main. */
    fun submit(s: RenderState) {
        state = s
        main.removeCallbacks(applyNew) // only the newest state matters
        main.postAtFrontOfQueue(applyNew)
    }

    private fun scheduleFrame() {
        if (released || frameScheduled) return
        frameScheduled = true
        choreographer.postFrameCallback(this)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        if (released) return
        val t0 = System.nanoTime()
        val moving = apply(frameTimeNanos)
        applyMsEma = ema(applyMsEma, (System.nanoTime() - t0) / 1e6f)
        updateRates(t0)
        if (moving) scheduleFrame()
    }

    private fun updateRates(nowNs: Long) {
        val now = nowNs / 1_000_000L
        if (metricWindowStart == 0L) metricWindowStart = now
        val dt = now - metricWindowStart
        if (dt >= 1000) {
            relayoutsPerSec = relayouts * 1000f / dt
            drawsPerSec = draws * 1000f / dt
            relayouts = 0; draws = 0; metricWindowStart = now
        }
    }

    /** @return true if anything is moving (keep animating). */
    private fun apply(frameTimeNs: Long): Boolean {
        val s = state
        if (s == null || s.frameW <= 0 || contentRect.isEmpty) { parkAll(); return false }
        val sx = contentRect.width().toFloat() / s.frameW
        val sy = contentRect.height().toFloat() / s.frameH
        val target = frameTimeNs / 1_000_000L + leadMs.toLong()
        val now = frameTimeNs / 1_000_000L
        for (sl in slots) sl.used = false
        var anyMoving = false
        val maxMargin = 0.25f * max(contentRect.width(), contentRect.height())
        for (b in s.boxes) {
            val slot = slotFor(b.id, s) ?: break
            slot.used = true
            val dt = min(max(target - b.tMs, 0L), MAX_EXTRAPOLATE_MS).toFloat()
            val vx = b.vx; val vy = b.vy
            if (vx != 0f || vy != 0f) anyMoving = true
            val l = floor(contentRect.left + (b.x1 + vx * dt) * sx).toInt()
            val t = floor(contentRect.top + (b.y1 + vy * dt) * sy).toInt()
            val r = ceil(contentRect.left + (b.x2 + vx * dt) * sx).toInt()
            val bt = ceil(contentRect.top + (b.y2 + vy * dt) * sy).toInt()
            val w = max(r - l, 1)
            val h = max(bt - t, 1)
            val lp = slot.lp
            val v = slot.view
            // Does the box still fit the current window (and roughly its size)?
            val fits = !slot.parked && l >= lp.x && t >= lp.y && l + w <= lp.x + lp.width && t + h <= lp.y + lp.height &&
                abs(v.boxW - w) <= SIZE_SLACK && abs(v.boxH - h) <= SIZE_SLACK
            if (!fits) {
                // margin ahead of the motion (screen px for ~MARGIN_MS of travel)
                val mx = min(abs(vx * sx) * MARGIN_MS, maxMargin).toInt()
                val my = min(abs(vy * sy) * MARGIN_MS, maxMargin).toInt()
                val wl = if (vx < 0) l - mx else l
                val wt = if (vy < 0) t - my else t
                val ww = w + mx
                val wh = h + my
                lp.x = wl; lp.y = wt; lp.width = ww; lp.height = wh
                val t1 = System.nanoTime()
                try { wm.updateViewLayout(v, lp) } catch (e: Exception) { Log.w(TAG, "update", e) }
                layoutMsEma = ema(layoutMsEma, (System.nanoTime() - t1) / 1e6f)
                relayouts++
                if (slot.parked) { slot.parked = false; slot.firstShown = now }
            }
            if (v.set(l - lp.x, t - lp.y, w, h, b.cols, b.rows, b.colors)) draws++
            val dl = (l - contentRect.left) / sx
            val dtp = (t - contentRect.top) / sy
            drawnMap[b.id] = DrawnBox(
                dl, dtp, dl + w / sx, dtp + h / sy,
                if (b.colors == null) 1 else b.cols, if (b.colors == null) 1 else b.rows,
                b.colors ?: DARK_BLOCK, slot.firstShown,
            )
        }
        for (sl in slots) if (!sl.used) park(sl)
        return anyMoving
    }

    private fun slotFor(id: Int, st: RenderState): Slot? {
        for (sl in slots) if (sl.id == id) return sl
        // prefer a parked slot whose id is gone
        for (sl in slots) {
            if (sl.used) continue
            if (sl.id == NONE || st.boxes.none { it.id == sl.id }) {
                if (sl.id != NONE) drawnMap.remove(sl.id)
                sl.id = id
                return sl
            }
        }
        if (slots.size >= MAX_WINDOWS) return null
        val sl = createSlot() ?: return null
        sl.id = id
        slots.add(sl)
        return sl
    }

    private fun park(sl: Slot) {
        if (sl.id != NONE) { drawnMap.remove(sl.id); sl.id = NONE }
        if (sl.parked) return
        sl.parked = true
        sl.view.clear()
        sl.lp.x = PARK_X; sl.lp.y = PARK_Y
        try { wm.updateViewLayout(sl.view, sl.lp) } catch (e: Exception) { Log.w(TAG, "park", e) }
        relayouts++
    }

    private fun parkAll() { for (sl in slots) park(sl) }

    @SuppressLint("RtlHardcoded")
    private fun baseParams(w: Int, h: Int, format: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        format,
    ).apply {
        // absolute screen coordinates, not RTL-relative
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        windowAnimations = 0
        setCanPlayMoveAnimation(false)
    }

    private fun createSlot(): Slot? {
        val view = PixelView(ctx)
        // translucent: the margin around the box (motion headroom) is transparent
        val lp = baseParams(64, 64, PixelFormat.TRANSLUCENT).apply {
            title = "Veil censor"
            x = PARK_X; y = PARK_Y
        }
        return try {
            wm.addView(view, lp)
            Slot(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed (overlay permission?)", e)
            null
        }
    }

    /** Add [n] parked windows up front so the first censors appear without addView/surface latency. */
    fun prewarm(n: Int) {
        while (slots.size < min(n, MAX_WINDOWS)) {
            val sl = createSlot() ?: return
            slots.add(sl)
        }
    }

    fun clear() {
        state = null
        main.post { parkAll() }
    }

    // ---- capture probe ----

    /** Show the probe (main thread). Placed at the left edge, mid-height of the content. */
    fun showProbe() {
        if (released || probeView != null || contentRect.isEmpty || frameW <= 0) return
        val v = ProbeView(ctx, probe)
        val lp = baseParams(1, 1, PixelFormat.OPAQUE).apply { title = "Veil probe" }
        placeProbe(lp)
        try { wm.addView(v, lp); probeView = v; probeLp = lp } catch (e: Exception) { Log.w(TAG, "probe", e) }
    }

    private fun placeProbe(lp: WindowManager.LayoutParams) {
        val sx = contentRect.width().toFloat() / max(1, frameW)
        val sy = contentRect.height().toFloat() / max(1, frameH)
        val r = probeRect(frameW, frameH)
        lp.x = floor(contentRect.left + r[0] * sx).toInt()
        lp.y = floor(contentRect.top + r[1] * sy).toInt()
        lp.width = ceil((r[2] - r[0]) * sx).toInt()
        lp.height = ceil((r[3] - r[1]) * sy).toInt()
    }

    /** Content rect / frame size changed (main thread). */
    fun relayoutProbe() {
        val v = probeView ?: return
        val lp = probeLp ?: return
        placeProbe(lp)
        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    fun hideProbe() {
        probeView?.let { try { wm.removeView(it) } catch (_: Exception) {} }
        probeView = null
        probeLp = null
    }

    fun hideProbeAsync() { main.post { hideProbe() } }

    /** Any thread: the probe colour changed. */
    fun probeChanged() { main.post { probeView?.invalidate() } }

    // ---- debug HUD ----

    fun showHud(text: String?) {
        if (released) return
        if (text == null) {
            hud?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            hud = null
            return
        }
        var v = hud
        if (v == null) {
            v = TextView(ctx).apply {
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.argb(200, 0, 0, 0))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                typeface = android.graphics.Typeface.MONOSPACE
                val p = (4 * resources.displayMetrics.density).toInt()
                setPadding(p, p, p, p)
            }
            val lp = baseParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                PixelFormat.TRANSLUCENT,
            ).apply {
                title = "Veil HUD"
                x = (8 * ctx.resources.displayMetrics.density).toInt()
                y = (48 * ctx.resources.displayMetrics.density).toInt()
                // <= 0.8 so touches still pass through (Android 12+ rule)
                alpha = 0.75f
            }
            try { wm.addView(v, lp); hud = v } catch (e: Exception) { Log.w(TAG, "hud", e); return }
        }
        v.text = text
    }

    fun release() {
        released = true
        choreographer.removeFrameCallback(this)
        main.removeCallbacks(applyNew)
        for (sl in slots) try { wm.removeViewImmediate(sl.view) } catch (_: Exception) {}
        slots.clear()
        hud?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        hud = null
        probeView?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        probeView = null
        drawnMap.clear()
    }

    /** Pixel blocks (nearest-neighbour upscaled) drawn at an offset inside a possibly larger window. */
    private class PixelView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private var colors: IntArray? = null
        private var hasColors = false
        private var empty = true
        var boxX = 0; private set
        var boxY = 0; private set
        var boxW = 0; private set
        var boxH = 0; private set
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
        private val dark = Paint().apply { color = DARK }
        private val dst = Rect()

        /** @return true if a redraw was requested. */
        fun set(x: Int, y: Int, w: Int, h: Int, cols: Int, rows: Int, c: IntArray?): Boolean {
            val sameColors = c === colors && (c != null) == hasColors
            val sameGeom = x == boxX && y == boxY && w == boxW && h == boxH
            if (sameColors && sameGeom && !empty) return false
            empty = false
            boxX = x; boxY = y; boxW = w; boxH = h
            if (!sameColors) {
                colors = c
                hasColors = c != null
                if (c != null) {
                    var b = bmp
                    if (b == null || b.width != cols || b.height != rows) {
                        b?.recycle()
                        b = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888)
                        bmp = b
                    }
                    b.setPixels(c, 0, cols, 0, 0, cols, rows)
                }
            }
            invalidate()
            return true
        }

        fun clear() {
            if (empty) return
            empty = true
            colors = null; hasColors = false
            boxW = 0; boxH = 0
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            if (empty) return
            dst.set(boxX, boxY, boxX + boxW, boxY + boxH)
            val b = bmp
            if (!hasColors || b == null) canvas.drawRect(dst, dark) else canvas.drawBitmap(b, null, dst, paint)
        }
    }

    private class ProbeView(ctx: Context, private val probe: CaptureProbe) : View(ctx) {
        override fun onDraw(canvas: Canvas) {
            val i = probe.currentIndex()
            canvas.drawColor(CaptureProbe.COLORS[i])
            probe.onShown(i, System.nanoTime() / 1_000_000L)
        }
    }

    companion object {
        private const val TAG = "VeilOverlay"
        private const val NONE = Int.MIN_VALUE
        const val MAX_WINDOWS = 16
        const val MAX_EXTRAPOLATE_MS = 200L
        /** window headroom along the motion, in ms of travel */
        const val MARGIN_MS = 120f
        /** box size change (screen px) tolerated without a relayout */
        const val SIZE_SLACK = 3
        const val PARK_X = -20000
        const val PARK_Y = -20000
        val DARK = Color.rgb(24, 24, 28)
        private val DARK_BLOCK = intArrayOf(DARK)

        private fun ema(old: Float, v: Float) = if (old == 0f) v else old * 0.9f + v * 0.1f

        /** Probe location in capture pixels: left edge, mid-height. [x1,y1,x2,y2] */
        fun probeRect(fw: Int, fh: Int): FloatArray {
            val s = CaptureProbe.SIZE.toFloat()
            val y = fh / 2f - s / 2f
            return floatArrayOf(0f, y, s, y + s)
        }
    }
}

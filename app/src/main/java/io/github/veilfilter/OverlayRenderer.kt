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
    /** v1.8 prediction ([Prediction]): normal box age at the glass, per-model cap, app scroll hint */
    @Volatile var kneeMs = 50f
    @Volatile var capMs = MAX_EXTRAPOLATE_MS.toFloat()
    @Volatile var scrollActive = false

    // ---- v1.8 accessibility mode: ONE full-screen TYPE_ACCESSIBILITY_OVERLAY window ----
    private var sheet: CensorSheet? = null
    private var sheetWm: WindowManager? = null
    private var sheetLp: WindowManager.LayoutParams? = null
    private val sheetOrigin = IntArray(2)
    private val sheetFirstShown = HashMap<Int, Long>()
    private val sheetSeen = HashSet<Int>()
    /** censors are drawn by the single accessibility overlay (any thread) */
    @Volatile var sheetActive = false; private set

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
        val ui = uiRects
        var k = 0
        while (k + 3 < ui.size && n < maxN) {
            out[4 * n] = ui[k]; out[4 * n + 1] = ui[k + 1]; out[4 * n + 2] = ui[k + 2]; out[4 * n + 3] = ui[k + 3]
            n++; k += 4
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
        if (s == null || s.frameW <= 0 || contentRect.isEmpty) { parkAll(); sheet?.clearAll(); drawnMap.clear(); return false }
        if (sheet != null) return applySheet(s, frameTimeNs)
        val sx = contentRect.width().toFloat() / s.frameW
        val sy = contentRect.height().toFloat() / s.frameH
        val target = frameTimeNs / 1_000_000L + leadMs.toLong()
        val now = frameTimeNs / 1_000_000L
        for (sl in slots) sl.used = false
        var anyMoving = false
        val maxMargin = 0.25f * max(contentRect.width(), contentRect.height())
        val knee = kneeMs
        val cap = Prediction.capFor(capMs, knee, scrollActive)
        for (b in s.boxes) {
            val slot = slotFor(b.id, s) ?: break
            slot.used = true
            val dt = Prediction.effectiveDt((target - b.tMs).toFloat(), knee, cap)
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
            // margin ahead of the motion (screen px for ~MARGIN_MS of travel)
            val mx = min(abs(vx * sx) * MARGIN_MS, maxMargin).toInt()
            val my = min(abs(vy * sy) * MARGIN_MS, maxMargin).toInt()
            // static margin on every side: small size/position changes are redrawn inside
            // the (transparent) window instead of resizing it (relayouts look jumpy)
            val sm = max(STATIC_MARGIN_MIN, (STATIC_MARGIN_FRAC * max(w, h)).toInt())
            // Does the box still fit the current window, without the window being far too big?
            val fits = !slot.parked && l >= lp.x && t >= lp.y && l + w <= lp.x + lp.width && t + h <= lp.y + lp.height &&
                lp.width - w <= 4 * sm + mx + SIZE_SLACK && lp.height - h <= 4 * sm + my + SIZE_SLACK
            if (!fits) {
                val wl = (if (vx < 0) l - mx else l) - sm
                val wt = (if (vy < 0) t - my else t) - sm
                val ww = w + mx + 2 * sm
                val wh = h + my + 2 * sm
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

    /**
     * Accessibility mode: every censor is a rectangle in ONE full-screen view, so a
     * move is a redraw of that view on the next vsync, never a WindowManager relayout.
     */
    private fun applySheet(s: RenderState, frameTimeNs: Long): Boolean {
        val v = sheet ?: return false
        val sx = contentRect.width().toFloat() / s.frameW
        val sy = contentRect.height().toFloat() / s.frameH
        val target = frameTimeNs / 1_000_000L + leadMs.toLong()
        val now = frameTimeNs / 1_000_000L
        val knee = kneeMs
        val cap = Prediction.capFor(capMs, knee, scrollActive)
        var anyMoving = false
        val ox = sheetOrigin[0]; val oy = sheetOrigin[1]
        v.begin()
        sheetSeen.clear()
        for (b in s.boxes) {
            if (sheetSeen.size >= MAX_SHEET_BOXES) break
            val dt = Prediction.effectiveDt((target - b.tMs).toFloat(), knee, cap)
            val vx = b.vx; val vy = b.vy
            if (vx != 0f || vy != 0f) anyMoving = true
            val l = floor(contentRect.left + (b.x1 + vx * dt) * sx).toInt()
            val t = floor(contentRect.top + (b.y1 + vy * dt) * sy).toInt()
            val r = ceil(contentRect.left + (b.x2 + vx * dt) * sx).toInt()
            val bt = ceil(contentRect.top + (b.y2 + vy * dt) * sy).toInt()
            val w = max(r - l, 1)
            val h = max(bt - t, 1)
            v.put(b.id, l - ox, t - oy, w, h, b.cols, b.rows, b.colors)
            val first = sheetFirstShown.getOrPut(b.id) { now }
            val dl = (l - contentRect.left) / sx
            val dtp = (t - contentRect.top) / sy
            drawnMap[b.id] = DrawnBox(
                dl, dtp, dl + w / sx, dtp + h / sy,
                if (b.colors == null) 1 else b.cols, if (b.colors == null) 1 else b.rows,
                b.colors ?: DARK_BLOCK, first,
            )
            sheetSeen.add(b.id)
        }
        if (v.end()) draws++
        if (drawnMap.size > sheetSeen.size) drawnMap.keys.retainAll(sheetSeen)
        if (sheetFirstShown.size > sheetSeen.size) sheetFirstShown.keys.retainAll(sheetSeen)
        return anyMoving
    }

    /**
     * Main thread. Switch to the single full-screen accessibility overlay, created
     * through the accessibility service's own window manager ([a11yCtx] = the
     * service). Touch-through (NOT_TOUCHABLE) and fully opaque censors: accessibility
     * overlays are trusted windows, so Android's untrusted-overlay touch blocking does
     * not apply. @return false if the window could not be added (caller keeps the
     * per-box windows).
     */
    fun attachSheet(a11yCtx: Context): Boolean {
        if (released) return false
        if (sheet != null) return true
        val wmA = a11yCtx.getSystemService(WindowManager::class.java) ?: return false
        val v = CensorSheet(a11yCtx)
        val b = wmA.maximumWindowMetrics.bounds
        val lp = sheetParams(b.width(), b.height())
        try { wmA.addView(v, lp) } catch (e: Exception) { Log.w(TAG, "accessibility overlay failed", e); return false }
        v.addOnLayoutChangeListener { vv, _, _, _, _, _, _, _, _ -> vv.getLocationOnScreen(sheetOrigin) }
        sheet = v; sheetWm = wmA; sheetLp = lp; sheetActive = true
        parkAll()
        drawnMap.clear(); sheetFirstShown.clear()
        main.removeCallbacks(applyNew); main.post(applyNew)
        return true
    }

    /** Main thread: back to the per-box windows (mode switched off / service gone). */
    fun detachSheet() {
        val v = sheet ?: return
        try { sheetWm?.removeViewImmediate(v) } catch (_: Exception) {}
        sheet = null; sheetWm = null; sheetLp = null; sheetActive = false
        drawnMap.clear(); sheetFirstShown.clear()
        main.removeCallbacks(applyNew); main.post(applyNew)
    }

    /** Main thread: display rotated / resized. */
    fun resizeSheet() {
        val v = sheet ?: return
        val wmA = sheetWm ?: return
        val lp = sheetLp ?: return
        val b = wmA.maximumWindowMetrics.bounds
        if (lp.width == b.width() && lp.height == b.height()) return
        lp.width = b.width(); lp.height = b.height()
        try { wmA.updateViewLayout(v, lp) } catch (_: Exception) {}
    }

    @SuppressLint("RtlHardcoded")
    private fun sheetParams(w: Int, h: Int) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        x = 0; y = 0
        alpha = 1f
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        windowAnimations = 0
        setCanPlayMoveAnimation(false)
        title = "Veil censors (accessibility)"
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
        main.post { parkAll(); sheet?.clearAll(); if (sheet != null) drawnMap.clear() }
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

    private var hudText: String? = null
    /** HUD starts collapsed to its first line (model · det fps · e2e); tap toggles */
    var hudExpanded = false
        private set

    fun showHud(text: String?) {
        if (released) return
        hudText = text
        if (text == null) {
            hud?.let { try { wm.removeView(it) } catch (_: Exception) {} }
            hud = null
            hudRect.setEmpty(); publishUiRects()
            return
        }
        var v = hud
        if (v == null) {
            val d = ctx.resources.displayMetrics.density
            v = makeHudView(ctx).apply {
                setOnClickListener { hudExpanded = !hudExpanded; renderHudText() }
                addOnLayoutChangeListener { vv, _, _, _, _, _, _, _, _ -> trackHudRect(vv) }
            }
            // touchable (tap = expand/collapse) but only within its own bounds; not focusable,
            // so every touch outside goes to the app underneath
            val lp = baseParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                PixelFormat.TRANSLUCENT,
            ).apply {
                flags = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                title = "Veil HUD"
                x = (8 * d).toInt()
                y = (48 * d).toInt()
            }
            try { wm.addView(v, lp); hud = v } catch (e: Exception) { Log.w(TAG, "hud", e); return }
        }
        renderHudText()
    }

    private fun renderHudText() {
        val v = hud ?: return
        val t = hudText ?: return
        v.text = if (hudExpanded) t else hudCollapsed(t)
    }

    private fun trackHudRect(v: View) {
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        hudRect.set(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
        publishUiRects()
    }

    // ---- our own non-censor UI (HUD, floating control): masked like our censors ----
    private val hudRect = Rect()
    private val extraUi = ArrayList<Rect>()
    /** capture-coordinate rects [x1,y1,x2,y2]*n, replaced atomically (read on the capture thread) */
    @Volatile private var uiRects = FloatArray(0)

    /** our HUD / floating-control rects in capture coordinates (any thread) */
    fun uiRectsNow(): FloatArray = uiRects

    /** Main thread: screen rects of other own UI windows (floating control); empty list = none. */
    fun setUiRects(rects: List<Rect>) {
        extraUi.clear(); extraUi.addAll(rects.map { Rect(it) })
        publishUiRects()
    }

    private fun publishUiRects() {
        val all = ArrayList<Rect>(extraUi.size + 1)
        if (!hudRect.isEmpty) all.add(hudRect)
        all.addAll(extraUi)
        uiRects = screenToCapture(all, contentRect, frameW, frameH)
    }

    fun release() {
        released = true
        choreographer.removeFrameCallback(this)
        main.removeCallbacks(applyNew)
        sheet?.let { try { sheetWm?.removeViewImmediate(it) } catch (_: Exception) {} }
        sheet = null; sheetActive = false
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

    /**
     * v1.8: all censors in one full-screen view (accessibility mode). Each entry is a
     * cols x rows pixelation bitmap (nearest-neighbour upscaled) or a dark fill.
     */
    private class CensorSheet(ctx: Context) : View(ctx) {
        private class E {
            val r = Rect()
            var colors: IntArray? = null
            var bmp: Bitmap? = null
            var live = false
        }
        private val entries = HashMap<Int, E>()
        private val order = ArrayList<E>()
        private var changed = false
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
        private val dark = Paint().apply { color = DARK }

        fun begin() {
            for (e in entries.values) e.live = false
            order.clear()
        }

        fun put(id: Int, x: Int, y: Int, w: Int, h: Int, cols: Int, rows: Int, c: IntArray?) {
            var e = entries[id]
            if (e == null) { e = E(); entries[id] = e; changed = true }
            e.live = true
            if (e.r.left != x || e.r.top != y || e.r.width() != w || e.r.height() != h) {
                e.r.set(x, y, x + w, y + h); changed = true
            }
            if (c !== e.colors) {
                e.colors = c; changed = true
                if (c != null && cols > 0 && rows > 0 && c.size >= cols * rows) {
                    var b = e.bmp
                    if (b == null || b.width != cols || b.height != rows) {
                        b = Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888); e.bmp = b
                    }
                    b.setPixels(c, 0, cols, 0, 0, cols, rows)
                }
            }
            order.add(e)
        }

        /** @return true if a redraw was requested */
        fun end(): Boolean {
            val it = entries.values.iterator()
            while (it.hasNext()) if (!it.next().live) { it.remove(); changed = true }
            if (!changed) return false
            changed = false
            invalidate()
            return true
        }

        fun clearAll() {
            if (entries.isEmpty()) return
            entries.clear(); order.clear()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(Color.TRANSPARENT, BlendMode.CLEAR)
            for (e in order) {
                val b = e.bmp
                if (e.colors == null || b == null) canvas.drawRect(e.r, dark) else canvas.drawBitmap(b, null, e.r, paint)
            }
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
        /** accessibility mode draws every box in one view; bounded anyway */
        const val MAX_SHEET_BOXES = 64
        const val MAX_EXTRAPOLATE_MS = 200L
        /** window headroom along the motion, in ms of travel */
        const val MARGIN_MS = 120f
        /** box size change (screen px) tolerated without a relayout */
        const val SIZE_SLACK = 3
        /** transparent margin around each box window (px / fraction of the box) */
        const val STATIC_MARGIN_MIN = 8
        const val STATIC_MARGIN_FRAC = 0.08f
        const val PARK_X = -20000
        const val PARK_Y = -20000
        val DARK = Color.rgb(24, 24, 28)
        private val DARK_BLOCK = intArrayOf(DARK)

        private fun ema(old: Float, v: Float) = if (old == 0f) v else old * 0.9f + v * 0.1f

        /** Probe location in capture pixels: left edge, mid-height. [x1,y1,x2,y2] */
        /** the HUD text view (shared with the screenshot test) */
        fun makeHudView(ctx: Context): TextView = TextView(ctx).apply {
            val d = ctx.resources.displayMetrics.density
            setTextColor(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.argb(215, 18, 12, 14)); cornerRadius = 10 * d
            }
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = android.graphics.Typeface.MONOSPACE
            val ph = (8 * d).toInt(); val pv = (5 * d).toInt()
            setPadding(ph, pv, ph, pv)
        }

        /** collapsed HUD = its first line */
        fun hudCollapsed(text: String): String = text.substringBefore('\n')

        /** screen rects -> capture-frame rects (the capture shows [content] scaled to fw x fh) */
        fun screenToCapture(rects: List<Rect>, content: Rect, fw: Int, fh: Int): FloatArray {
            if (fw <= 0 || fh <= 0 || content.width() <= 0 || content.height() <= 0) return FloatArray(0)
            val sx = fw.toFloat() / content.width(); val sy = fh.toFloat() / content.height()
            val out = ArrayList<Float>()
            for (r in rects) {
                val x1 = ((r.left - content.left) * sx).coerceIn(0f, fw.toFloat())
                val y1 = ((r.top - content.top) * sy).coerceIn(0f, fh.toFloat())
                val x2 = ((r.right - content.left) * sx).coerceIn(0f, fw.toFloat())
                val y2 = ((r.bottom - content.top) * sy).coerceIn(0f, fh.toFloat())
                if (x2 - x1 >= 1f && y2 - y1 >= 1f) { out.add(x1); out.add(y1); out.add(x2); out.add(y2) }
            }
            return out.toFloatArray()
        }

        fun probeRect(fw: Int, fh: Int): FloatArray {
            val s = CaptureProbe.SIZE.toFloat()
            val y = fh / 2f - s / 2f
            return floatArrayOf(0f, y, s, y + s)
        }
    }
}

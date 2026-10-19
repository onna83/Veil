package io.github.veilfilter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
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
 * keep the rest of the screen touchable). Main thread only, except [submit]
 * and [drawnFor] which are thread-safe.
 *
 * Between pipeline results the boxes are extrapolated every vsync with their
 * constant-velocity estimate, aimed one frame ahead so they lead motion.
 */
class OverlayRenderer(private val ctx: Context) : Choreographer.FrameCallback {
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()

    @Volatile private var state: RenderState? = null
    private val drawnMap = ConcurrentHashMap<Int, DrawnBox>()

    /** Where the captured content sits on screen, in screen pixels. Main thread. */
    val contentRect = Rect()

    private class Slot(val view: PixelView, val lp: WindowManager.LayoutParams) {
        var id = NONE
        var shown = false
        var used = false
    }

    private val slots = ArrayList<Slot>()
    private var frameScheduled = false
    private var hud: TextView? = null
    private var hudLp: WindowManager.LayoutParams? = null
    private var released = false

    private val applyNow = Runnable { apply(System.nanoTime()) }

    fun drawnFor(id: Int): DrawnBox? = drawnMap[id]

    /** Thread-safe: hand over a new state; applied immediately on main. */
    fun submit(s: RenderState) {
        state = s
        main.postAtFrontOfQueue(applyNow)
    }

    override fun doFrame(frameTimeNanos: Long) {
        frameScheduled = false
        apply(frameTimeNanos)
    }

    private fun apply(nowNs: Long) {
        if (released) return
        val s = state
        if (s == null || s.frameW <= 0 || contentRect.isEmpty) { hideAll(); return }
        val sx = contentRect.width().toFloat() / s.frameW
        val sy = contentRect.height().toFloat() / s.frameH
        val target = nowNs / 1_000_000L + LEAD_MS
        for (sl in slots) sl.used = false
        var anyMoving = false
        for (b in s.boxes) {
            val slot = slotFor(b.id) ?: break
            slot.used = true
            val dt = min(max(target - b.tMs, 0L), MAX_EXTRAPOLATE_MS).toFloat()
            val x1 = b.x1 + b.vx * dt
            val y1 = b.y1 + b.vy * dt
            val x2 = b.x2 + b.vx * dt
            val y2 = b.y2 + b.vy * dt
            if (b.vx != 0f || b.vy != 0f) anyMoving = true
            val l = floor(contentRect.left + x1 * sx).toInt()
            val t = floor(contentRect.top + y1 * sy).toInt()
            val r = ceil(contentRect.left + x2 * sx).toInt()
            val bt = ceil(contentRect.top + y2 * sy).toInt()
            val w = max(r - l, 1)
            val h = max(bt - t, 1)
            val lp = slot.lp
            val moved = abs(lp.x - l) > MOVE_PX || abs(lp.y - t) > MOVE_PX ||
                abs(lp.width - w) > MOVE_PX || abs(lp.height - h) > MOVE_PX
            slot.view.setBlocks(b.cols, b.rows, b.colors)
            if (!slot.shown || moved) {
                lp.x = l; lp.y = t; lp.width = w; lp.height = h
                try { wm.updateViewLayout(slot.view, lp) } catch (e: Exception) { Log.w(TAG, "update", e) }
            }
            if (!slot.shown) {
                slot.view.visibility = View.VISIBLE
                slot.shown = true
            }
            val dl = (lp.x - contentRect.left) / sx
            val dtp = (lp.y - contentRect.top) / sy
            drawnMap[b.id] = DrawnBox(
                dl, dtp, dl + lp.width / sx, dtp + lp.height / sy,
                if (b.colors == null) 1 else b.cols, if (b.colors == null) 1 else b.rows,
                b.colors ?: DARK_BLOCK,
            )
        }
        for (sl in slots) {
            if (!sl.used) {
                if (sl.shown) { sl.view.visibility = View.GONE; sl.shown = false }
                if (sl.id != NONE) { drawnMap.remove(sl.id); sl.id = NONE }
            }
        }
        if (anyMoving && !frameScheduled) {
            frameScheduled = true
            choreographer.postFrameCallback(this)
        }
    }

    private fun slotFor(id: Int): Slot? {
        for (sl in slots) if (sl.id == id) return sl
        val st = state
        for (sl in slots) {
            if (sl.used) continue
            // free if its id is not in the current state
            if (sl.id == NONE || st == null || st.boxes.none { it.id == sl.id }) {
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
        view.visibility = View.GONE
        val lp = baseParams(1, 1, PixelFormat.OPAQUE).apply { title = "Veil censor" }
        return try {
            wm.addView(view, lp)
            Slot(view, lp)
        } catch (e: Exception) {
            Log.e(TAG, "addView failed (overlay permission?)", e)
            null
        }
    }

    /** Add [n] hidden windows up front so the first censor appears without addView latency. */
    fun prewarm(n: Int) {
        while (slots.size < min(n, MAX_WINDOWS)) {
            val sl = createSlot() ?: return
            slots.add(sl)
        }
    }

    private fun hideAll() {
        for (sl in slots) {
            if (sl.shown) { sl.view.visibility = View.GONE; sl.shown = false }
            sl.id = NONE
        }
        drawnMap.clear()
    }

    fun clear() {
        state = null
        main.post { hideAll() }
    }

    // ---- debug HUD ----

    @SuppressLint("SetTextI18n")
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
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
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
            try { wm.addView(v, lp); hud = v; hudLp = lp } catch (e: Exception) { Log.w(TAG, "hud", e); return }
        }
        v.text = text
    }

    fun release() {
        released = true
        choreographer.removeFrameCallback(this)
        main.removeCallbacks(applyNow)
        for (sl in slots) try { wm.removeViewImmediate(sl.view) } catch (_: Exception) {}
        slots.clear()
        hud?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        hud = null
        drawnMap.clear()
    }

    /** Solid pixel blocks, nearest-neighbour upscaled. */
    private class PixelView(ctx: Context) : View(ctx) {
        private var bmp: Bitmap? = null
        private var colors: IntArray? = null
        private var hasColors = false
        private val paint = Paint().apply { isFilterBitmap = false; isAntiAlias = false; isDither = false }
        private val dst = Rect()

        fun setBlocks(cols: Int, rows: Int, c: IntArray?) {
            if (c === colors && (c != null) == hasColors) return
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
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val b = bmp
            if (!hasColors || b == null) {
                canvas.drawColor(DARK)
                return
            }
            dst.set(0, 0, width, height)
            canvas.drawBitmap(b, null, dst, paint)
        }
    }

    companion object {
        private const val TAG = "VeilOverlay"
        private const val NONE = Int.MIN_VALUE
        const val MAX_WINDOWS = 16
        const val MOVE_PX = 2
        const val LEAD_MS = 16L
        const val MAX_EXTRAPOLATE_MS = 200L
        val DARK = Color.rgb(24, 24, 28)
        private val DARK_BLOCK = intArrayOf(DARK)
    }
}

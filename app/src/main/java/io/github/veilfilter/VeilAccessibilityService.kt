package io.github.veilfilter

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.Executors

/**
 * v1.8 optional "Accessibility mode" (opt-in in Performance → Accessibility
 * mode, with a disclosure screen; does nothing while the mode is off).
 *
 * What it is used for, and nothing else:
 *  1. its window manager can add ONE full-screen TYPE_ACCESSIBILITY_OVERLAY
 *     (trusted, touch-through, fully opaque) that draws every censor
 *     ([OverlayRenderer.attachSheet]);
 *  2. event TYPES + times as hints ([A11yHints]): an app is scrolling, a
 *     different screen took over;
 *  3. on Android 14+ a screenshot of the foreground APP window only
 *     ([AccessibilityService.takeScreenshotOfWindow]); it does not contain
 *     Veil's overlay, so the detector can check what is under our own
 *     censors. Kept in memory, scaled to the capture size, never stored or sent.
 *
 * It never reads window content: no node tree, no text, no view ids.
 * Window info used: id, type, layer and bounds (to find the foreground app
 * window), plus the package/class name of window-state events.
 */
class VeilAccessibilityService : AccessibilityService() {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "veil-a11y-shot") }
    @Volatile private var shotInFlight = false
    @Volatile private var backoffUntil = 0L

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        A11yHints.state.reset()
        A11yHints.state.active = AppSettings.a11yMode
        Log.i(TAG, "accessibility service connected")
        notifyListeners()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        gone()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        gone()
        exec.shutdownNow()
        super.onDestroy()
    }

    private fun gone() {
        if (instance === this) instance = null
        A11yHints.state.active = false
        notifyListeners()
    }

    override fun onInterrupt() {}

    /** event time (uptimeMillis) -> System.nanoTime() ms */
    private fun toNanoMs(eventTime: Long): Long {
        val age = (SystemClock.uptimeMillis() - eventTime).coerceIn(0L, 1000L)
        return System.nanoTime() / 1_000_000L - age
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || !AppSettings.a11yMode) return
        val pkg = e.packageName?.toString()
        if (pkg == packageName) return // our own windows
        val hs = A11yHints.state
        hs.active = true
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> hs.onScroll(toNanoMs(e.eventTime))
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                // only a NEW full-screen-ish activity counts; dialogs / menus have their own
                // (small) windows and are filtered by the foreground-window check below
                val t = toNanoMs(e.eventTime)
                val win = try { foregroundAppWindow() } catch (_: Throwable) { null }
                if (win != null && win.id == e.windowId) hs.onWindowState(pkg, e.className?.toString(), t)
                if (win != null) hs.onForegroundWindow(win.id, t)
            }
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val win = try { foregroundAppWindow() } catch (_: Throwable) { null }
                if (win != null) hs.onForegroundWindow(win.id, toNanoMs(e.eventTime))
            }
            else -> {} // not subscribed (see res/xml/accessibility_service.xml)
        }
    }

    /** the top-most application window covering >= 80 % of the display (not ours), or null */
    private fun foregroundAppWindow(): AccessibilityWindowInfo? {
        val ws = windows ?: return null
        val disp = (getSystemService(android.view.WindowManager::class.java)).maximumWindowMetrics.bounds
        val area = disp.width().toLong() * disp.height()
        val r = Rect()
        var best: AccessibilityWindowInfo? = null
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
            if (w.displayId != Display.DEFAULT_DISPLAY) continue
            w.getBoundsInScreen(r)
            if (r.width().toLong() * r.height() < 0.8 * area) continue
            if (best == null || w.layer > best.layer) best = w
        }
        return best
    }

    /** A clean (overlay-free) frame scaled into capture coordinates. */
    class CleanShot(val bytes: ByteArray, val w: Int, val h: Int, val stride: Int, val window: RectF, val tMs: Long)

    /**
     * Screenshot of the foreground app window, scaled to a [capW] x [capH] RGBA
     * frame that shows [content] (screen px), like the MediaProjection capture.
     * [done] runs on the screenshot thread with null on any failure / throttling.
     */
    fun requestCleanShot(content: Rect, capW: Int, capH: Int, done: (CleanShot?) -> Unit) {
        val now = System.nanoTime() / 1_000_000L
        if (shotInFlight || now < backoffUntil || capW <= 0 || capH <= 0 || content.isEmpty) { done(null); return }
        val win = try { foregroundAppWindow() } catch (_: Throwable) { null }
        if (win == null) { done(null); return }
        val bounds = Rect(); win.getBoundsInScreen(bounds)
        shotInFlight = true
        val t0 = now
        try {
            takeScreenshotOfWindow(win.id, exec, object : TakeScreenshotCallback {
                override fun onSuccess(res: ScreenshotResult) {
                    var out: CleanShot? = null
                    try { out = toCapture(res, bounds, content, capW, capH, t0) } catch (t: Throwable) { Log.w(TAG, "clean shot", t) }
                    shotInFlight = false
                    done(out)
                }

                override fun onFailure(errorCode: Int) {
                    shotInFlight = false
                    // throttled by the system (or window gone): try again later, never hammer
                    backoffUntil = System.nanoTime() / 1_000_000L + if (errorCode == ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) 1000L else 2000L
                    done(null)
                }
            })
        } catch (t: Throwable) {
            shotInFlight = false
            backoffUntil = now + 5000L
            Log.w(TAG, "takeScreenshotOfWindow", t)
            done(null)
        }
    }

    private fun toCapture(res: ScreenshotResult, bounds: Rect, content: Rect, capW: Int, capH: Int, t0: Long): CleanShot? {
        val hb = res.hardwareBuffer
        try {
            val hw = Bitmap.wrapHardwareBuffer(hb, res.colorSpace) ?: return null
            val sw = hw.copy(Bitmap.Config.ARGB_8888, false)
            hw.recycle()
            if (sw == null) return null
            // window rect in capture coordinates (the capture shows `content` scaled to capW x capH)
            val sx = capW.toFloat() / content.width(); val sy = capH.toFloat() / content.height()
            val dst = RectF((bounds.left - content.left) * sx, (bounds.top - content.top) * sy,
                (bounds.right - content.left) * sx, (bounds.bottom - content.top) * sy)
            val cap = Bitmap.createBitmap(capW, capH, Bitmap.Config.ARGB_8888)
            Canvas(cap).drawBitmap(sw, null, dst, Paint(Paint.FILTER_BITMAP_FLAG))
            sw.recycle()
            val buf = java.nio.ByteBuffer.allocate(capW * capH * 4)
            cap.copyPixelsToBuffer(buf) // ARGB_8888 memory order is R,G,B,A = the RGBA_8888 capture layout
            cap.recycle()
            dst.intersect(0f, 0f, capW.toFloat(), capH.toFloat())
            return CleanShot(buf.array(), capW, capH, capW * 4, dst, t0)
        } finally {
            try { hb.close() } catch (_: Throwable) {}
        }
    }

    companion object {
        private const val TAG = "VeilA11y"
        @Volatile var instance: VeilAccessibilityService? = null
            private set
        private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()
        fun addListener(l: () -> Unit) { listeners.add(l) }
        fun removeListener(l: () -> Unit) { listeners.remove(l) }
        private fun notifyListeners() { for (l in listeners) l() }

        /** Veil's service is switched on in Android's accessibility settings */
        fun enabledInSettings(ctx: Context): Boolean {
            val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            val me = ComponentName(ctx, VeilAccessibilityService::class.java)
            return s.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}

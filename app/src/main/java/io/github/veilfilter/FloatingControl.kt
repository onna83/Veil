package io.github.veilfilter

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * v1.7 floating quick-action bubble (AssistiveTouch-style), shown only while
 * protection runs.
 *
 *  - Two small TYPE_APPLICATION_OVERLAY windows: the bubble and, when open,
 *    the menu panel. Both are NOT_FOCUSABLE, so every touch outside their own
 *    bounds goes to the app underneath; the menu closes on ACTION_OUTSIDE
 *    (FLAG_WATCH_OUTSIDE_TOUCH observes the outside tap, it never consumes it).
 *  - Everything here runs on the main thread from user input. Nothing is
 *    posted to the capture or detector threads except pause/resume's
 *    one-shot re-publish, so the censor pipeline is not slowed.
 *  - Its screen rects are registered with the renderer like our own censors:
 *    in full-screen capture they are masked from the shift / scene / skin
 *    guards and detections that are mostly the bubble are dropped. In
 *    single-app capture it is not in the captured image at all.
 *  - The glyph is neutral grey pixels, never skin tones.
 */
class FloatingControl(
    baseCtx: Context,
    private val renderer: OverlayRenderer?,
    private val actions: Actions,
) {
    interface Actions {
        fun togglePause()
        fun switchModel(m: ModelSize)
        fun stop()
        fun openApp()
    }

    private val ctx: Context = baseCtx
    private val app: Context = baseCtx.applicationContext ?: baseCtx
    private val wm = ctx.getSystemService(WindowManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val d = ctx.resources.displayMetrics.density
    private val size = (BUBBLE_DP * d).roundToInt()
    private val margin = (6 * d).roundToInt()
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    private var bubble: BubbleView? = null
    private var bubbleLp: WindowManager.LayoutParams? = null
    private var menu: QuickMenuView? = null
    private var menuLp: WindowManager.LayoutParams? = null
    private var lastOutsideClose = 0L
    private val fade = Runnable { bubble?.animate()?.alpha(if (ServiceState.paused) PAUSED_IDLE_ALPHA else IDLE_ALPHA)?.setDuration(400)?.start() }

    val showing: Boolean get() = bubble != null
    val menuOpen: Boolean get() = menu != null

    fun show() {
        if (bubble != null) return
        val b = BubbleView(ctx)
        b.paused = ServiceState.paused
        val lp = params(size, size, watchOutside = false).apply { title = "Veil control" }
        val (x, y) = savedPosition()
        lp.x = x; lp.y = y
        b.setOnTouchListener(DragListener())
        try { wm.addView(b, lp) } catch (_: Exception) { return }
        bubble = b; bubbleLp = lp
        wake()
        publishRects()
    }

    fun hide() {
        closeMenu()
        main.removeCallbacks(fade)
        bubble?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        bubble = null; bubbleLp = null
        renderer?.setUiRects(emptyList())
    }

    /** state changed elsewhere (pause, settings): redraw bubble and open menu */
    fun refresh() {
        bubble?.let { it.paused = ServiceState.paused; it.invalidate() }
        menu?.let { m -> if (!m.inModelList) m.showGrid(items()) }
        wake()
    }

    /** rotation / display change: re-place from the remembered side + height */
    fun onDisplayChanged() {
        closeMenu()
        val lp = bubbleLp ?: return
        val (x, y) = savedPosition()
        lp.x = x; lp.y = y
        bubble?.let { try { wm.updateViewLayout(it, lp) } catch (_: Exception) {} }
        publishRects()
    }

    // ------------------------------------------------------------------ menu

    private fun items(): List<QuickItem> = quickItems(
        paused = ServiceState.paused, pixelDp = AppSettings.pixelDp,
        faces = AppSettings.censor.faces, covered = AppSettings.censor.covered, hud = AppSettings.debugHud,
        modelLabel = AppSettings.effectiveModel(app).id,
    )

    private fun toggleMenu() {
        if (menu != null) { closeMenu(); return }
        if (SystemClock.uptimeMillis() - lastOutsideClose < 350) return // the tap that just closed it
        val m = QuickMenuView(ctx) { action -> onAction(action) }
        m.showGrid(items())
        m.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) { lastOutsideClose = SystemClock.uptimeMillis(); closeMenu(); true }
            else { if (ev.actionMasked == MotionEvent.ACTION_UP) m.performClick(); false }
        }
        val lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, watchOutside = true)
            .apply { title = "Veil quick menu" }
        placeMenu(m, lp)
        try { wm.addView(m, lp) } catch (_: Exception) { return }
        menu = m; menuLp = lp
        bubble?.animate()?.cancel(); bubble?.alpha = 1f
        main.removeCallbacks(fade)
        m.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            val l = menuLp ?: return@addOnLayoutChangeListener
            if (placeMenu(v as QuickMenuView, l)) try { wm.updateViewLayout(v, l) } catch (_: Exception) {}
            publishRects()
        }
        publishRects()
    }

    fun closeMenu() {
        val m = menu ?: return
        try { wm.removeViewImmediate(m) } catch (_: Exception) {}
        menu = null; menuLp = null
        publishRects()
        wake()
    }

    private fun onAction(a: QuickAction) {
        when (a) {
            QuickAction.PAUSE -> { actions.togglePause(); closeMenu() }
            QuickAction.MODEL -> menu?.showModels(
                switchableModels(app), AppSettings.effectiveModel(app),
            )
            QuickAction.PIXEL -> AppSettings.setPixelDp(app, AppSettings.nextPixelPreset(AppSettings.pixelDp))
            QuickAction.FACES -> AppSettings.setCensorFlag(app, AppSettings.KEY_C_FACES, !AppSettings.censor.faces)
            QuickAction.COVERED -> AppSettings.setCensorFlag(app, AppSettings.KEY_C_COVERED, !AppSettings.censor.covered)
            QuickAction.HUD -> AppSettings.setDebugHud(app, !AppSettings.debugHud)
            QuickAction.STOP -> { closeMenu(); actions.stop() }
            QuickAction.OPEN -> { closeMenu(); actions.openApp() }
            QuickAction.BACK -> menu?.showGrid(items())
            is QuickAction.UseModel -> { closeMenu(); actions.switchModel(a.model) }
        }
        // settings setters notify listeners -> refresh(); make sure labels follow anyway
        menu?.let { if (!it.inModelList) it.showGrid(items()) }
    }

    /** returns true if the position changed */
    private fun placeMenu(m: View, lp: WindowManager.LayoutParams): Boolean {
        val b = bubbleLp ?: return false
        val area = safeArea()
        m.measure(
            View.MeasureSpec.makeMeasureSpec(area.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(area.height(), View.MeasureSpec.AT_MOST),
        )
        val mw = m.measuredWidth; val mh = m.measuredHeight
        val gap = (8 * d).roundToInt()
        val right = b.x + size / 2 > area.centerX()
        var x = if (right) b.x - mw - gap else b.x + size + gap
        x = x.coerceIn(area.left, maxOf(area.left, area.right - mw))
        val y = (b.y + size / 2 - mh / 2).coerceIn(area.top, maxOf(area.top, area.bottom - mh))
        val changed = lp.x != x || lp.y != y
        lp.x = x; lp.y = y
        return changed
    }

    // ------------------------------------------------------------------ bubble

    private fun wake() {
        val b = bubble ?: return
        main.removeCallbacks(fade)
        b.animate().cancel()
        b.alpha = 1f
        if (menu == null) main.postDelayed(fade, IDLE_MS)
    }

    private fun savedPosition(): Pair<Int, Int> {
        val a = safeArea()
        val x = if (AppSettings.bubbleRight) a.right - size else a.left
        val y = (a.top + AppSettings.bubbleYFrac * maxOf(0, a.height() - size)).roundToInt()
        return x to y
    }

    /** where the bubble may sit: the screen minus system bars / cutout, minus a margin */
    private fun safeArea(): Rect {
        val m = wm.currentWindowMetrics
        val b = Rect(m.bounds)
        val ins = m.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Rect(b.left + ins.left + margin, b.top + ins.top + margin, b.right - ins.right - margin, b.bottom - ins.bottom - margin)
    }

    private fun publishRects() {
        val r = renderer ?: return
        val out = ArrayList<Rect>(2)
        bubbleLp?.let { out.add(Rect(it.x, it.y, it.x + size, it.y + size)) }
        val m = menu; val ml = menuLp
        if (m != null && ml != null) {
            val w = if (m.width > 0) m.width else m.measuredWidth
            val h = if (m.height > 0) m.height else m.measuredHeight
            out.add(Rect(ml.x, ml.y, ml.x + w, ml.y + h))
        }
        r.setUiRects(out)
    }

    private inner class DragListener : View.OnTouchListener {
        private var downX = 0f; private var downY = 0f
        private var startX = 0; private var startY = 0
        private var dragging = false

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouch(v: View, ev: MotionEvent): Boolean {
            val lp = bubbleLp ?: return false
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX; downY = ev.rawY; startX = lp.x; startY = lp.y; dragging = false
                    main.removeCallbacks(fade); v.animate().cancel(); v.alpha = 1f
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX; val dy = ev.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) { dragging = true; closeMenu() }
                    if (dragging) {
                        lp.x = (startX + dx).roundToInt(); lp.y = (startY + dy).roundToInt()
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        val a = safeArea()
                        val (right, yFrac) = snap(lp.x + size / 2f, lp.y.toFloat(), a, size)
                        AppSettings.setBubblePos(app, right, yFrac)
                        val (x, y) = savedPosition()
                        lp.x = x; lp.y = y
                        try { wm.updateViewLayout(v, lp) } catch (_: Exception) {}
                        publishRects()
                        wake()
                    } else if (ev.actionMasked == MotionEvent.ACTION_UP) {
                        v.performClick()
                        toggleMenu()
                    }
                    dragging = false
                }
            }
            return true
        }
    }

    private fun params(w: Int, h: Int, watchOutside: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED or
            (if (watchOutside) WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH else 0),
        PixelFormat.TRANSLUCENT,
    ).apply {
        @SuppressLint("RtlHardcoded")
        gravity = Gravity.TOP or Gravity.LEFT
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
    }

    companion object {
        const val BUBBLE_DP = 52
        const val IDLE_MS = 2500L
        const val IDLE_ALPHA = 0.35f
        const val PAUSED_IDLE_ALPHA = 0.85f

        /** snap a dropped bubble to the nearer side edge; y as a fraction of the free height */
        fun snap(centerX: Float, y: Float, area: Rect, size: Int): Pair<Boolean, Float> {
            val right = centerX > area.exactCenterX()
            val free = maxOf(1, area.height() - size)
            return right to ((y - area.top) / free).coerceIn(0f, 1f)
        }

        /** models the quick menu offers: present on the device and runnable by this build */
        fun switchableModels(ctx: Context): List<ModelSize> =
            ModelSize.entries.filter { (it.runtime == ModelRuntime.TFLITE || OrtDetector.SUPPORTED) && it.available(ctx) }

        /** pure: the quick-menu grid for the current state */
        fun quickItems(paused: Boolean, pixelDp: Int, faces: Boolean, covered: Boolean, hud: Boolean, modelLabel: String) = listOf(
            QuickItem(QuickAction.PAUSE, if (paused) R.drawable.ic_play else R.drawable.ic_pause, if (paused) "Resume" else "Pause", paused),
            QuickItem(QuickAction.MODEL, R.drawable.ic_memory, modelLabel, false),
            QuickItem(QuickAction.PIXEL, R.drawable.ic_grid, "Pixel $pixelDp", false),
            QuickItem(QuickAction.FACES, R.drawable.ic_face, "Faces", faces),
            QuickItem(QuickAction.COVERED, R.drawable.ic_layers, "Covered", covered),
            QuickItem(QuickAction.HUD, R.drawable.ic_speed, "HUD", hud),
            QuickItem(QuickAction.OPEN, R.drawable.ic_tune, "Settings", false),
            QuickItem(QuickAction.STOP, R.drawable.ic_stop, "Stop", false),
        )
    }
}

sealed class QuickAction {
    data object PAUSE : QuickAction()
    data object MODEL : QuickAction()
    data object PIXEL : QuickAction()
    data object FACES : QuickAction()
    data object COVERED : QuickAction()
    data object HUD : QuickAction()
    data object STOP : QuickAction()
    data object OPEN : QuickAction()
    data object BACK : QuickAction()
    data class UseModel(val model: ModelSize) : QuickAction()
}

class QuickItem(val action: QuickAction, val icon: Int, val label: String, val active: Boolean)

/** The bubble: a small dark circle with a neutral pixel glyph; amber with pause bars while paused. */
class BubbleView(ctx: Context) : View(ctx) {
    var paused = false
    private val d = ctx.resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d }
    private val px = Paint()
    private val r = RectF()

    init { contentDescription = "Veil quick actions"; isClickable = true }

    override fun performClick(): Boolean { super.performClick(); return true }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f; val cy = height / 2f; val rad = minOf(cx, cy) - ring.strokeWidth
        fill.color = if (paused) Color.argb(240, 242, 169, 59) else Color.argb(205, 30, 22, 26)
        ring.color = if (paused) Color.argb(255, 255, 230, 180) else Color.argb(170, 255, 151, 115)
        c.drawCircle(cx, cy, rad, fill)
        c.drawCircle(cx, cy, rad, ring)
        if (paused) {
            px.color = Color.argb(255, 40, 26, 10)
            val bw = 4f * d; val bh = 16f * d; val g = 3f * d
            r.set(cx - g - bw, cy - bh / 2, cx - g, cy + bh / 2); c.drawRoundRect(r, d, d, px)
            r.set(cx + g, cy - bh / 2, cx + g + bw, cy + bh / 2); c.drawRoundRect(r, d, d, px)
        } else {
            // 3x3 mosaic in neutral greys (never skin-like, so the detector ignores it)
            val cell = 5.5f * d; val o = -1.5f * cell
            val shades = intArrayOf(235, 170, 210, 150, 245, 185, 200, 140, 225)
            for (j in 0 until 3) for (i in 0 until 3) {
                val s = shades[j * 3 + i]
                px.color = Color.rgb(s, s, s)
                r.set(cx + o + i * cell, cy + o + j * cell, cx + o + (i + 1) * cell - 0.8f * d, cy + o + (j + 1) * cell - 0.8f * d)
                c.drawRect(r, px)
            }
        }
    }
}

/** Menu panel: a 4x2 grid of quick actions, or the model list. Plain views (no theme needed). */
class QuickMenuView(ctx: Context, private val onAction: (QuickAction) -> Unit) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    var inModelList = false; private set

    init {
        background = GradientDrawable().apply { setColor(Color.argb(242, 30, 22, 26)); cornerRadius = 22 * d }
        val p = (10 * d).roundToInt()
        setPadding(p, p, p, p)
        elevation = 8 * d
    }

    override fun performClick(): Boolean { super.performClick(); return true }

    fun showGrid(items: List<QuickItem>) {
        inModelList = false
        removeAllViews()
        val g = GridLayout(context).apply { columnCount = 4 }
        for (it in items) g.addView(cell(it))
        addView(g)
    }

    fun showModels(models: List<ModelSize>, current: ModelSize) {
        inModelList = true
        removeAllViews()
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(row(R.drawable.ic_arrow_back, "Switch model", null, bold = true) { onAction(QuickAction.BACK) })
        for (m in models) {
            val sub = if (m.external) "%.0f MB".format(java.util.Locale.US, m.bytes / 1e6) else "in app"
            col.addView(row(if (m == current) R.drawable.ic_check else 0, m.id, sub, bold = m == current) {
                if (m != current) onAction(QuickAction.UseModel(m)) else onAction(QuickAction.BACK)
            })
        }
        if (models.size <= 1) col.addView(TextView(context).apply {
            text = "Download more in Veil → Detection model"
            setTextColor(Color.argb(170, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            val p = (8 * d).roundToInt(); setPadding(p, p, p, p)
        })
        addView(col, LayoutParams((232 * d).roundToInt(), LayoutParams.WRAP_CONTENT))
    }

    private fun cell(item: QuickItem): View {
        val w = (68 * d).roundToInt()
        val col = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val p = (4 * d).roundToInt(); setPadding(p, p, p, p)
            layoutParams = GridLayout.LayoutParams().apply { width = w }
            isClickable = true
            contentDescription = item.label
            setOnClickListener { onAction(item.action) }
        }
        val disc = (44 * d).roundToInt()
        val icon = ImageView(context).apply {
            setImageResource(item.icon)
            val p = (10 * d).roundToInt(); setPadding(p, p, p, p)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                if (item.active) setColor(ACTIVE) else { setColor(Color.argb(40, 255, 255, 255)) }
            }
            imageTintList = ColorStateList.valueOf(if (item.active) Color.rgb(50, 20, 12) else Color.WHITE)
            if (item.action == QuickAction.STOP) imageTintList = ColorStateList.valueOf(Color.rgb(255, 138, 128))
        }
        col.addView(icon, LinearLayout.LayoutParams(disc, disc))
        col.addView(TextView(context).apply {
            text = item.label
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            gravity = Gravity.CENTER
            setPadding(0, (4 * d).roundToInt(), 0, 0)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return col
    }

    private fun row(icon: Int, title: String, sub: String?, bold: Boolean, click: () -> Unit): View {
        val r = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = (44 * d).roundToInt()
            val p = (8 * d).roundToInt(); setPadding(p, 0, p, 0)
            isClickable = true
            setOnClickListener { click() }
        }
        val iv = ImageView(context).apply {
            if (icon != 0) setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ACTIVE)
        }
        r.addView(iv, LinearLayout.LayoutParams((20 * d).roundToInt(), (20 * d).roundToInt()).apply { marginEnd = (12 * d).roundToInt() })
        r.addView(TextView(context).apply {
            text = title
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (sub != null) r.addView(TextView(context).apply {
            text = sub
            setTextColor(Color.argb(170, 255, 255, 255)); setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        })
        return r
    }

    companion object {
        val ACTIVE = Color.rgb(255, 180, 162)
    }
}

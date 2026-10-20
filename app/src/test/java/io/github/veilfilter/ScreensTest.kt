package io.github.veilfilter

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.core.widget.NestedScrollView
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowSettings
import java.io.File

/**
 * Renders the v1.7 screens (light + dark), the floating control and the
 * collapsed/expanded HUD to PNGs with Robolectric's native graphics.
 * Output: $VEIL_SCREENS_DIR, default app/build/veil-screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w384dp-h832dp-xhdpi")
class ScreensTest {
    private val out = File(System.getenv("VEIL_SCREENS_DIR") ?: "build/veil-screens").apply { mkdirs() }

    @Before fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        AppSettings.resetForTest()
        AppSettings.load(RuntimeEnvironment.getApplication())
    }

    @After fun tearDown() { ServiceState.paused = false; ServiceState.set(false) }

    private fun <T : Activity> shoot(cls: Class<T>, name: String, night: Boolean, prep: (T) -> Unit = {}): T {
        if (night) RuntimeEnvironment.setQualifiers("+night") else RuntimeEnvironment.setQualifiers("+notnight")
        val a = Robolectric.buildActivity(cls).setup().get()
        prep(a)
        val decor = a.window.decorView
        val w = decor.width; val h = decor.height
        relayout(decor, w, h)
        val suffix = if (night) "_dark" else ""
        save(render(decor, w, h), "$name$suffix")
        // full-length version when the page scrolls
        val scroll = findScroll(decor)
        if (scroll != null && scroll.getChildAt(0).height > scroll.height + 4) {
            val child = scroll.getChildAt(0)
            val top = IntArray(2).also { scroll.getLocationInWindow(it) }[1]
            val fullH = top + child.height
            val bmp = Bitmap.createBitmap(w, fullH, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(bg(a))
            c.save(); c.clipRect(0, 0, w, top); decor.draw(c); c.restore()
            c.save(); c.translate(0f, top.toFloat()); child.draw(c); c.restore()
            save(bmp, "${name}_full$suffix")
        }
        return a
    }

    private fun bg(a: Activity): Int {
        val tv = android.util.TypedValue()
        a.theme.resolveAttribute(android.R.attr.colorBackground, tv, true)
        return tv.data
    }

    private fun relayout(v: View, w: Int, h: Int) {
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
    }

    private fun render(v: View, w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return b
    }

    private fun findScroll(v: View): NestedScrollView? {
        if (v is NestedScrollView) return v
        if (v is ViewGroup) for (i in 0 until v.childCount) findScroll(v.getChildAt(i))?.let { return it }
        return null
    }

    private fun save(b: Bitmap, name: String) {
        File(out, "$name.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun mainScreen() {
        for (night in listOf(false, true)) {
            val a = shoot(MainActivity::class.java, "01_main", night)
            // only the requested sections are on the main screen
            assertTrue(a.findViewById<View>(R.id.start_stop).isShown)
            assertTrue(a.findViewById<View>(R.id.pixel_preview).isShown)
            assertEquals(4, a.findViewById<ViewGroup>(R.id.nav_rows).childCount)
        }
    }

    @Test fun mainRunningWithFaces() {
        val ctx = RuntimeEnvironment.getApplication()
        AppSettings.setCensorFlag(ctx, AppSettings.KEY_C_FACES, true)
        ServiceState.set(true)
        try {
            shoot(MainActivity::class.java, "02_main_running_faces", night = false)
            shoot(MainActivity::class.java, "02_main_running_faces", night = true)
        } finally {
            AppSettings.setCensorFlag(ctx, AppSettings.KEY_C_FACES, false)
        }
    }

    @Test fun secondaryScreens() {
        for (night in listOf(false, true)) {
            shoot(ModelsActivity::class.java, "03_models", night)
            shoot(PerformanceActivity::class.java, "04_performance", night)
            shoot(DebugActivity::class.java, "05_debug", night)
            shoot(AboutActivity::class.java, "06_about", night)
        }
    }

    /** Floating control over a neutral (SFW, synthetic) feed: idle bubble, open menu, paused, model list. */
    @Test fun floatingControlAndHud() {
        RuntimeEnvironment.setQualifiers("+notnight")
        val a = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val d = a.resources.displayMetrics.density
        val w = (384 * d).toInt(); val h = (832 * d).toInt()
        fun backdrop(): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Color.rgb(242, 242, 245))
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            var y = 60 * d
            var k = 0
            while (y < h) {
                p.color = Color.WHITE; c.drawRoundRect(RectF(12 * d, y, w - 12 * d, y + 220 * d), 14 * d, 14 * d, p)
                p.color = listOf(Color.rgb(160, 190, 220), Color.rgb(180, 210, 170), Color.rgb(220, 200, 150))[k % 3]
                c.drawRoundRect(RectF(20 * d, y + 8 * d, w - 20 * d, y + 170 * d), 10 * d, 10 * d, p)
                p.color = Color.rgb(200, 200, 205)
                c.drawRoundRect(RectF(20 * d, y + 182 * d, w * 0.6f, y + 196 * d), 6 * d, 6 * d, p)
                y += 236 * d; k++
            }
            return b
        }
        fun place(c: Canvas, v: View, x: Int, y: Int, vw: Int = -1, vh: Int = -1) {
            val ws = if (vw > 0) View.MeasureSpec.makeMeasureSpec(vw, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST)
            val hs = if (vh > 0) View.MeasureSpec.makeMeasureSpec(vh, View.MeasureSpec.EXACTLY) else View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
            v.measure(ws, hs); v.layout(0, 0, v.measuredWidth, v.measuredHeight)
            c.save(); c.translate(x.toFloat(), y.toFloat()); v.draw(c); c.restore()
        }
        val size = (FloatingControl.BUBBLE_DP * d).toInt()
        val bx = w - size - (6 * d).toInt(); val by = (300 * d).toInt()
        val items = FloatingControl.quickItems(paused = false, pixelDp = 10, faces = false, covered = true, hud = true, modelLabel = "320n_320")

        // 1. idle bubble (faded) + collapsed HUD
        run {
            val b = backdrop(); val c = Canvas(b)
            val bubble = BubbleView(a); bubble.alpha = FloatingControl.IDLE_ALPHA
            c.saveLayerAlpha(null, (255 * FloatingControl.IDLE_ALPHA).toInt())
            place(c, bubble, bx, by, size, size); c.restore()
            val hud = OverlayRenderer.makeHudView(a); hud.text = OverlayRenderer.hudCollapsed(SAMPLE_HUD)
            place(c, hud, (8 * d).toInt(), (48 * d).toInt())
            save(b, "07_bubble_idle_hud_collapsed")
        }
        // 2. menu open
        run {
            val b = backdrop(); val c = Canvas(b)
            place(c, BubbleView(a), bx, by, size, size)
            val m = QuickMenuView(a) {}; m.showGrid(items)
            m.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
            place(c, m, bx - m.measuredWidth - (8 * d).toInt(), by + size / 2 - m.measuredHeight / 2)
            save(b, "08_bubble_menu")
        }
        // 3. model list
        run {
            val b = backdrop(); val c = Canvas(b)
            place(c, BubbleView(a), bx, by, size, size)
            val m = QuickMenuView(a) {}
            m.showModels(listOf(ModelSize.S320, ModelSize.entries[1], ModelSize.entries[2]), ModelSize.S320)
            m.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
            place(c, m, bx - m.measuredWidth - (8 * d).toInt(), by + size / 2 - m.measuredHeight / 2)
            save(b, "09_bubble_models")
        }
        // 4. paused + expanded HUD
        run {
            val b = backdrop(); val c = Canvas(b)
            val bubble = BubbleView(a); bubble.paused = true
            place(c, bubble, bx, by, size, size)
            val hud = OverlayRenderer.makeHudView(a); hud.text = SAMPLE_HUD
            place(c, hud, (8 * d).toInt(), (48 * d).toInt())
            save(b, "10_bubble_paused_hud_expanded")
        }
        assertTrue(File(out, "08_bubble_menu.png").length() > 1000)
    }

    companion object {
        /** HUD text in the real format; numbers are illustrative (v1.6.1 expectations), not measured here */
        val SAMPLE_HUD = Pipeline.hudCompact("320n_320", 46f, 72f) + "\n" +
            "auto: gpu_cl | model 320n_320 | Samsung s5e9925\n" +
            "capture: SINGLE APP | 590 x 1280\n" +
            "cap 59.8 fps (int 16.7) deliver 8.1 copy 0.52\n" +
            "shift 1.71 (prof 1.02 srch 0.69 lite) guard 0.13 appear 0.42 publish 0.60 analyze+publish 3.10 pre 1.02\n" +
            "censor: exposed\n" +
            "appear guard: 0.42 ms, boxes 0, new cells 0, fired 0\n" +
            "grid boost: idle (no grid), 10 tiles of 318 px, hot 0, full 46.0/s tile 0.0/s\n" +
            "det 46.0 fps: queue 2.1 (fresh-wait 3.4) infer 22.0 decode 1.10 post 0.40\n" +
            "overlay: hop 1.2 apply 0.30 layout 0.20 relayout 40/s draw 110/s\n" +
            "probe draw→cap n/a lead 38 vsync 8.3\n" +
            "e2e est: tracked 45 | new obj: guard 45, detector 72 ms\n" +
            "scroll +0.0,+0.0 px/f ok | tracks 4 (occ 0) ring-drop 0"
    }
}

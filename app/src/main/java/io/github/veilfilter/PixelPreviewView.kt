package io.github.veilfilter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.max

/**
 * Live preview of the censor pixelation at the chosen block size (dp, as on
 * screen). Left third: the synthetic scene as is; the rest: pixelated with
 * the same block size the overlay will use. The scene is drawn in code
 * (sky, sun, hills, flowers), no image assets.
 */
class PixelPreviewView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var blockDp: Int = AppSettings.PIXEL_DEFAULT_DP
        set(v) { if (field != v) { field = v; pixelated = null; invalidate() } }

    private var scene: Bitmap? = null
    private var pixelated: Bitmap? = null
    private val d = resources.displayMetrics.density
    private val clip = Path()
    private val rect = RectF()
    private val plain = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nearest = Paint().apply { isFilterBitmap = false }
    private val divider = Paint().apply { color = Color.WHITE; strokeWidth = 2 * resources.displayMetrics.density }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        scene = null; pixelated = null
        clip.reset(); rect.set(0f, 0f, w.toFloat(), h.toFloat())
        clip.addRoundRect(rect, 16 * d, 16 * d, Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        if (width <= 0 || height <= 0) return
        val s = scene ?: drawScene(width, height).also { scene = it }
        val p = pixelated ?: pixelate(s, blockPx(blockDp, d)).also { pixelated = it }
        c.save()
        c.clipPath(clip)
        val split = width / 3f
        c.drawBitmap(s, 0f, 0f, plain)
        c.save()
        c.clipRect(split, 0f, width.toFloat(), height.toFloat())
        rect.set(0f, 0f, width.toFloat(), height.toFloat())
        c.drawBitmap(p, null, rect, nearest)
        c.restore()
        c.drawLine(split, 0f, split, height.toFloat(), divider)
        c.restore()
    }

    companion object {
        fun blockPx(dp: Int, density: Float): Int = max(1, (dp * density + 0.5f).toInt())

        /** average-down to blocks of [block] px (the overlay averages each block too) */
        fun pixelate(src: Bitmap, block: Int): Bitmap {
            val cw = max(1, (src.width + block - 1) / block)
            val ch = max(1, (src.height + block - 1) / block)
            return Bitmap.createScaledBitmap(src, cw, ch, true)
        }

        fun drawScene(w: Int, h: Int): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), Color.rgb(98, 160, 230), Color.rgb(250, 205, 160), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
            p.color = Color.rgb(255, 214, 90); c.drawCircle(w * 0.78f, h * 0.30f, h * 0.16f, p)
            p.color = Color.WHITE; c.drawOval(w * 0.08f, h * 0.14f, w * 0.30f, h * 0.30f, p)
            c.drawOval(w * 0.40f, h * 0.10f, w * 0.58f, h * 0.24f, p)
            val hill = Path()
            p.color = Color.rgb(84, 160, 96)
            hill.moveTo(0f, h * 0.70f); hill.quadTo(w * 0.30f, h * 0.42f, w * 0.62f, h * 0.68f)
            hill.quadTo(w * 0.85f, h * 0.55f, w.toFloat(), h * 0.62f); hill.lineTo(w.toFloat(), h.toFloat()); hill.lineTo(0f, h.toFloat()); hill.close()
            c.drawPath(hill, p)
            p.color = Color.rgb(52, 120, 70)
            c.drawRect(0f, h * 0.86f, w.toFloat(), h.toFloat(), p)
            val colors = intArrayOf(Color.rgb(230, 60, 90), Color.rgb(250, 240, 90), Color.rgb(150, 90, 220), Color.WHITE)
            var x = w * 0.05f; var k = 0
            while (x < w) {
                p.color = colors[k % colors.size]
                c.drawCircle(x, h * (0.80f + 0.06f * ((k * 37) % 3)), h * 0.035f, p)
                x += w * 0.07f; k++
            }
            return b
        }
    }
}

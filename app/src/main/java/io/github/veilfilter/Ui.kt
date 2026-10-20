package io.github.veilfilter

import android.app.Activity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider

/** Small builders so every screen uses the same Material 3 rows, headers, cards and spacing. */
object Ui {
    /** draw behind the system bars; pad [top] by the status bar and [bottom] by the nav bar */
    fun edgeToEdge(a: Activity, top: View, bottom: View) {
        WindowCompat.setDecorFitsSystemWindows(a.window, false)
        val t0 = top.paddingTop; val b0 = bottom.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(top) { v, ins ->
            val b = ins.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, t0 + b.top, b.right, v.paddingBottom)
            bottom.setPadding(bottom.paddingLeft, bottom.paddingTop, bottom.paddingRight, b0 + b.bottom)
            ins
        }
    }

    fun header(parent: ViewGroup, text: Int): TextView {
        val tv = TextView(parent.context, null, 0, R.style.Veil_SectionHeader)
        tv.setText(text)
        parent.addView(tv, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        return tv
    }

    /** a section card; returns the vertical container to add rows to */
    fun card(parent: ViewGroup): LinearLayout {
        val c = LayoutInflater.from(parent.context).inflate(R.layout.card_section, parent, false)
        parent.addView(c)
        return c.findViewById(R.id.card_content)
    }

    class SwitchRow(val view: View, val toggle: MaterialSwitch)

    fun switchRow(parent: ViewGroup, title: Int, summary: Int?, checked: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit): SwitchRow {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_switch, parent, false)
        v.findViewById<TextView>(R.id.title).setText(title)
        val s = v.findViewById<TextView>(R.id.summary)
        if (summary != null) s.setText(summary) else s.visibility = View.GONE
        val sw = v.findViewById<MaterialSwitch>(R.id.toggle)
        sw.isChecked = checked
        sw.contentDescription = parent.context.getString(title)
        sw.setOnCheckedChangeListener { _, on -> onChange(on) }
        v.setOnClickListener { sw.toggle() }
        if (indent) v.setPaddingRelative((56 * parent.resources.displayMetrics.density).toInt(), v.paddingTop, v.paddingEnd, v.paddingBottom)
        parent.addView(v)
        return SwitchRow(v, sw)
    }

    fun navRow(parent: ViewGroup, icon: Int, title: Int, summary: CharSequence, onClick: () -> Unit): TextView {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_nav, parent, false)
        v.findViewById<ImageView>(R.id.icon).setImageResource(icon)
        v.findViewById<TextView>(R.id.title).setText(title)
        val s = v.findViewById<TextView>(R.id.summary)
        s.text = summary
        v.setOnClickListener { onClick() }
        parent.addView(v)
        return s
    }

    /** title + secondary text; clickable when [onClick] is given. Returns the summary view. */
    fun infoRow(parent: ViewGroup, title: Int, summary: CharSequence, onClick: (() -> Unit)? = null): TextView {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_info, parent, false)
        v.findViewById<TextView>(R.id.title).setText(title)
        val s = v.findViewById<TextView>(R.id.summary)
        s.text = summary
        if (onClick != null) {
            val tv = android.util.TypedValue()
            parent.context.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
            v.setBackgroundResource(tv.resourceId)
            s.setTextIsSelectable(false)
            v.setOnClickListener { onClick() }
        }
        parent.addView(v)
        return s
    }

    fun sliderRow(parent: ViewGroup, title: Int, summary: Int, from: Int, to: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit): Slider {
        val v = LayoutInflater.from(parent.context).inflate(R.layout.row_slider, parent, false)
        v.findViewById<TextView>(R.id.title).setText(title)
        v.findViewById<TextView>(R.id.summary).setText(summary)
        val value0 = v.findViewById<TextView>(R.id.value)
        val sl = v.findViewById<Slider>(R.id.slider)
        sl.valueFrom = from.toFloat(); sl.valueTo = to.toFloat()
        sl.value = value.coerceIn(from, to).toFloat()
        sl.contentDescription = parent.context.getString(title)
        value0.text = fmt(value)
        sl.addOnChangeListener { _, f, fromUser ->
            value0.text = fmt(f.toInt())
            if (fromUser) onChange(f.toInt())
        }
        parent.addView(v)
        return sl
    }

    /** small one-line note under a section */
    fun note(parent: ViewGroup, text: CharSequence): TextView {
        val tv = TextView(parent.context)
        tv.text = text
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
        val tvv = android.util.TypedValue()
        parent.context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tvv, true)
        tv.setTextColor(tvv.data)
        val d = parent.resources.displayMetrics.density
        tv.setPadding((32 * d).toInt(), (8 * d).toInt(), (32 * d).toInt(), 0)
        parent.addView(tv)
        return tv
    }
}

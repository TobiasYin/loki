package dev.thor.agent

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Native settings use the chat stylesheet palette (including its final dark-mode overrides). */
internal class SettingsUi(private val context: Context) {
    private val palette = Appearance.palette(context)
    private val dark = palette.dark
    val background = palette.background
    val surface = palette.surface
    val ink = palette.ink
    val muted = palette.muted
    val accent = palette.accent
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int, radius: Int = 24) = GradientDrawable().apply { setColor(color); cornerRadius = dp(radius).toFloat() }
    fun column() = LinearLayout(context).apply { tag = "settings-designed"; orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(28)) }
    fun text(value: String, size: Float = 14f, secondary: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(if (secondary) muted else ink)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun heading(parent: LinearLayout, value: String) {
        parent.addView(text(value, 13f).apply { setTextColor(muted); setTypeface(null, Typeface.BOLD); setPadding(dp(8), dp(24), dp(8), dp(10)) })
    }
    fun card(parent: LinearLayout) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL; background = shape(surface)
        parent.addView(this, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
    }
    fun row(parent: LinearLayout, title: String, summary: String, symbol: String, action: () -> Unit) {
        val row = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL; minimumHeight = dp(88); setPadding(dp(18), dp(16), dp(18), dp(16))
            background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x22ffffff else 0x18000000), shape(surface), null)
            isFocusable = true; isClickable = true; setOnClickListener { action() }
        }
        row.addView(text(symbol, 22f).apply { setTextColor(ink); gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }, LinearLayout.LayoutParams(dp(40), dp(40)))
        row.addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            addView(text(title, 16f)); addView(text(summary, 13f, true).apply { setPadding(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(text("›", 24f, true).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        parent.addView(row)
        row.clipToOutline = true; row.outlineProvider = android.view.ViewOutlineProvider.BACKGROUND
    }
    fun button(label: String, action: () -> Unit) = Button(context).apply {
        text = label; isAllCaps = false; textSize = 14f; minimumHeight = dp(48)
        setTextColor(accent); backgroundTintList = null
        background = RippleDrawable(ColorStateList.valueOf(if (dark) 0x22ffffff else 0x18000000), shape(surface), null)
        setOnClickListener { action() }
    }
    fun styleForm(view: View) {
        if (view.tag == "settings-designed") return
        when (view) {
            is EditText -> { view.setTextColor(ink); view.setHintTextColor(muted); view.minimumHeight = dp(56); view.backgroundTintList = ColorStateList.valueOf(accent) }
            is Button -> { view.isAllCaps = false; view.minimumHeight = dp(48); view.setTextColor(accent); view.backgroundTintList = ColorStateList.valueOf(surface) }
            is TextView -> view.setTextColor(ink)
        }
        if (view is android.view.ViewGroup) for (i in 0 until view.childCount) styleForm(view.getChildAt(i))
    }
}

package com.sinat.osrsbubbletool

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils

// The app's building blocks: writing in Inter, buttons, icons and the ripple when you tap. Redesigned screens
// are made from these, so they all match and a change here reaches every one of them.
class Ui(val context: Context) {

    // GOLD: the screen's main action. PLAIN: everything else. DANGER: stop, delete and the like.
    enum class ButtonStyle { GOLD, PLAIN, DANGER }

    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // Inter at a weight: 400 normal, 500 medium, 600 semi-bold, 700 bold
    fun font(weight: Int): Typeface = fonts.getOrPut(weight) {
        Typeface.Builder(context.assets, "fonts/Inter.ttf").setFontVariationSettings("'wght' $weight").build()
    }

    fun text(value: String, size: Float = 14f, colour: Int = Palette.TEXT, weight: Int = 400) = TextView(context).apply {
        text = value
        textSize = size
        setTextColor(colour)
        typeface = font(weight)
    }

    // Writing that stays on one line, ending in "..." if there isn't room
    fun oneLine(view: TextView) = view.apply {
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    // A quick ripple where you tap, on top of the view's own colour (fill = null: no colour of its own)
    fun press(view: View, fill: Int?, radius: Int = 0) {
        val corner = dp(radius).toFloat()
        val shape = fill?.let { GradientDrawable().apply { setColor(it); cornerRadius = corner } }
        val mask = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = corner }
        view.background = RippleDrawable(ColorStateList.valueOf(ColorUtils.setAlphaComponent(Palette.TEXT, 0x40)), shape, mask)
        view.isClickable = true
        view.isFocusable = true
    }

    fun icon(name: String, colour: Int = Palette.TEXT, size: Int = 20) = ImageView(context).apply {
        setImageDrawable(StrokeIcon(Icons.path(name), colour))
        scaleType = ImageView.ScaleType.FIT_XY
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // (the writing or button beside it says what it is)
    }

    // An icon you can tap: a 44 x 44 area with the icon in the middle. `label` is what a screen reader says.
    fun iconButton(name: String, label: String, colour: Int = Palette.TEXT, fill: Int? = null, onClick: () -> Unit) = FrameLayout(context).apply {
        layoutParams = LinearLayout.LayoutParams(dp(TAP_SIZE), dp(TAP_SIZE))
        contentDescription = label
        press(this, fill, 8)
        addView(icon(name, colour), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    // A small heading over a group of things
    fun heading(value: String) = text(value, 12f, Palette.MUTED, 700)

    // Quieter writing that explains something
    fun note(value: String) = text(value, 12f, Palette.MUTED)

    // A block that stands apart from the window behind it, by its darker shade (no outline)
    fun card(fill: LinearLayout.() -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(10), dp(12), dp(10))
        background = GradientDrawable().apply { setColor(Palette.BG); cornerRadius = dp(8).toFloat() }
        fill()
    }

    // A box to type in
    fun textBox(hintText: String = "") = EditText(context).apply {
        hint = hintText
        textSize = 14f
        typeface = font(400)
        setTextColor(Palette.TEXT)
        setHintTextColor(Palette.MUTED)
        minHeight = dp(TAP_SIZE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { setColor(Palette.BG); cornerRadius = dp(8).toFloat() }
    }

    // A row of options to pick one of; the picked one is gold
    fun <T> choices(options: List<Pair<T, String>>, picked: T, onPick: (T) -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        background = GradientDrawable().apply { setColor(Palette.RAISED); cornerRadius = dp(8).toFloat() }
        var now = picked
        val cells = options.map { (_, name) -> oneLine(text(name, 13f)).apply { gravity = Gravity.CENTER; minHeight = dp(TAP_SIZE) } }
        fun look() = options.forEachIndexed { i, (value, _) ->
            val on = value == now
            cells[i].setTextColor(if (on) Palette.ON_GOLD else Palette.TEXT)
            cells[i].typeface = font(if (on) 700 else 500)
            press(cells[i], if (on) Palette.GOLD else null, 8)
        }
        options.forEachIndexed { i, (value, _) ->
            cells[i].setOnClickListener {
                now = value
                look()
                onPick(value)
            }
            addView(cells[i], LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        look()
    }

    // A slider in the app's gold
    fun slider(bar: SeekBar) = bar.apply {
        progressTintList = ColorStateList.valueOf(Palette.ACCENT)
        thumbTintList = ColorStateList.valueOf(Palette.GOLD)
        progressBackgroundTintList = ColorStateList.valueOf(Palette.RAISED)
        minimumHeight = dp(TAP_SIZE)
    }

    // A message that needs attention, with a warning sign; onClick = null if tapping it does nothing
    fun banner(value: String, onClick: (() -> Unit)? = null) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(TAP_SIZE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        if (onClick != null) {
            press(this, Palette.BG, 8)
            setOnClickListener { onClick() }
        } else background = GradientDrawable().apply { setColor(Palette.BG); cornerRadius = dp(8).toFloat() }
        addView(icon("warning", Palette.CAUTION))
        addView(text(value, 12f).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    }

    fun button(label: String, style: ButtonStyle = ButtonStyle.PLAIN, onClick: () -> Unit) = text(
        label, 13f, when (style) {
            ButtonStyle.GOLD -> Palette.ON_GOLD
            ButtonStyle.DANGER -> Palette.BAD
            ButtonStyle.PLAIN -> Palette.TEXT
        }, 600
    ).apply {
        gravity = Gravity.CENTER
        minHeight = dp(TAP_SIZE)
        minWidth = dp(92)
        setPadding(dp(10), 0, dp(10), 0)
        press(this, if (style == ButtonStyle.GOLD) Palette.GOLD else Palette.RAISED, 8)
        setOnClickListener { onClick() }
    }

    companion object {
        const val TAP_SIZE = 44   // the smallest a button can be, in dp
        private val fonts = HashMap<Int, Typeface>()   // (each weight is only made once)
    }
}

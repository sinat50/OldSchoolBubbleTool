package com.sinat.osrsbubbletool

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.text.DecimalFormat

// A basic calculator. It also understands OSRS shorthand: 1.5k = 1,500, 2m = 2,000,000, 1b = 1,000,000,000.
// What you typed is kept while the bubble is running.
class CalculatorTool(private val context: Context) {

    private val ui = Ui(context)

    private var expression = ""      // what's been typed, e.g. "1.5m*3"
    private var lastSum = ""         // the previous calculation, shown small above
    private var justAnswered = false // after =, a digit starts fresh and an operator carries on from the answer

    private lateinit var sumView: TextView
    private lateinit var expressionView: TextView
    private lateinit var resultView: TextView

    private fun dp(v: Int) = ui.dp(v)

    private lateinit var displayCard: LinearLayout
    private val keys = mutableListOf<TextView>()

    // The whole calculator always fits its window: when the window's size changes, the display and the keys both
    // grow or shrink to share it, so no key is ever cut off. Worked out while the window is being measured, before
    // anything is drawn at the new size (done a moment later, a resize drag showed every step at the old sizes first,
    // so the writing flashed).
    fun buildView(): View = object : LinearLayout(context) {
        private var fittedW = -1   // (the size the writing was last fitted to, for this view)
        private var fittedH = -1

        override fun onMeasure(widthSpec: Int, heightSpec: Int) {
            val w = MeasureSpec.getSize(widthSpec) - paddingLeft - paddingRight
            val h = MeasureSpec.getSize(heightSpec) - paddingTop - paddingBottom
            if (w > 0 && h > 0 && (w != fittedW || h != fittedH)) {
                fittedW = w; fittedH = h
                fitTo(h, w)
            }
            super.onMeasure(widthSpec, heightSpec)
        }
    }.apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Palette.SURFACE)
        setPadding(dp(4), dp(4), dp(4), dp(4))

        // The display: the sum (with the previous one small above it) and the answer
        displayCard = ui.card {
            setPadding(dp(10), dp(2), dp(10), dp(2))
            gravity = Gravity.CENTER_VERTICAL
            sumView = text(12f, Palette.MUTED)
            expressionView = text(14f, Palette.TEXT)
            resultView = text(18f, Palette.TEXT, 700)
            addView(sumView); addView(expressionView); addView(resultView)
        }
        addView(displayCard, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(2)
        })

        // The keys: five rows that share the rest of the window's height equally ("=" is two keys wide)
        val rows = listOf(
            listOf("C", "⌫", "(", ")", "k"),
            listOf("7", "8", "9", "÷", "m"),
            listOf("4", "5", "6", "×", "b"),
            listOf("1", "2", "3", "−", "00"),
            listOf("0", ".", "=", "+")
        )
        for (row in rows) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (k in row) addView(key(k).also { keys.add(it) },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, if (k == "=") 2f else 1f).apply { setMargins(dp(1), dp(1), dp(1), dp(1)) })
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        refresh()
    }

    // Shares out `height` pixels between the display and the keys, and sizes the writing to fit. A tall display shows
    // the last sum, what you typed and the answer; a shorter one drops the last sum. What you typed and the answer are
    // always on two lines (side by side, a narrow window cut both off, and switching between the two made the writing
    // jump while the window was resized).
    private fun fitTo(height: Int, width: Int) {
        val displayHeight = (height * 0.24f).toInt().coerceIn(dp(44), dp(84))
        val roomInside = displayHeight - dp(4)
        val keyHeight = (height - displayHeight - dp(2)) / 5 - dp(2)

        sumView.visibility = if (displayHeight >= dp(66)) View.VISIBLE else View.GONE
        displayCard.orientation = LinearLayout.VERTICAL
        shape(expressionView, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 0)
        shape(resultView, ViewGroup.LayoutParams.MATCH_PARENT, 0f, 0)
        // the answer's room across: the display's inside width, known from the window's width (not from the answer
        // itself), less a little so it's never cut off by rounding
        answerRoom = width - dp(20) - dp(4)

        // what you typed (never under 12 sp), then the answer as big as the rest of the display allows (the biggest
        // thing in the window; fitAnswer shrinks it for a long number)
        val sumLine = if (sumView.visibility == View.VISIBLE) { writing(sumView, 12f, roomInside * 0.2f); sumView.textSize * 1.2f } else 0f
        val typed = maxOf(size(12f, 0f), size(14f, roomInside * 0.28f))
        if (kotlin.math.abs(expressionView.textSize - typed) > 0.5f) expressionView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, typed)
        answerMax = maxOf(size(12f, 0f), size(30f, (roomInside - sumLine - typed * 1.2f) * 0.8f))
        fitAnswer()
        keys.forEach { writing(it, 15f, keyHeight * 0.5f) }

        val card = displayCard.layoutParams as LinearLayout.LayoutParams
        if (card.height != displayHeight) { card.height = displayHeight; displayCard.layoutParams = card }
    }

    // A line of the display's width and share of the row, changed only if it's different
    private fun shape(view: View, width: Int, weight: Float, left: Int) {
        val p = view.layoutParams as? LinearLayout.LayoutParams
        if (p != null && p.width == width && p.weight == weight && p.leftMargin == left) return
        view.layoutParams = LinearLayout.LayoutParams(width, ViewGroup.LayoutParams.WRAP_CONTENT, weight).apply { leftMargin = left }
    }

    // The answer at its biggest for the display's height (answerMax), made smaller if a long number wouldn't fit across
    // (answerRoom, worked out from the window's width in fitTo). Worked out in one go, from fixed sizes, so the writing
    // never jumps between sizes.
    private var answerMax = 0f
    private var answerRoom = 0
    private fun fitAnswer() {
        val room = answerRoom
        if (answerMax <= 0f || room <= 0) return
        val words = resultView.text.toString()
        val paint = android.graphics.Paint(resultView.paint).apply { textSize = answerMax }
        val wide = paint.measureText(words)
        val smallest = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
        val size = if (wide <= room) answerMax else maxOf(smallest, answerMax * room / wide)
        if (kotlin.math.abs(size - resultView.textSize) > 0.5f) resultView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
    }

    // Writing at its normal size, or smaller if it has less than that much height (in pixels) to fit in (changed only
    // if it's different)
    private fun size(normalSp: Float, mostPixels: Float): Float {
        val normal = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, normalSp, context.resources.displayMetrics)
        return if (mostPixels <= 0f) normal else minOf(normal, mostPixels)
    }

    private fun writing(view: TextView, normalSp: Float, mostPixels: Float) {
        val px = size(normalSp, mostPixels)
        if (kotlin.math.abs(view.textSize - px) > 0.5f) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, px)
    }

    private fun text(size: Float, color: Int, weight: Int = 400) = ui.text("", size, color, weight).apply {
        gravity = Gravity.END
        maxLines = 1
        includeFontPadding = false
        ellipsize = android.text.TextUtils.TruncateAt.START   // a long sum shows its end
    }

    // One key. "=" is the gold main key, C is in the stop-and-clear colour, and the maths signs are in gold writing.
    private fun key(k: String): TextView {
        val colour = when (k) {
            "=" -> Palette.ON_GOLD
            "C" -> Palette.BAD
            "÷", "×", "−", "+", "(", ")", "⌫" -> Palette.ACCENT
            else -> Palette.TEXT
        }
        // (the rub-out key is still called "⌫" in the workings below; it just says Del on screen)
        return ui.text(if (k == "⌫") "Del" else k, 15f, colour, 600).apply {
            gravity = Gravity.CENTER
            includeFontPadding = false
            contentDescription = when (k) { "⌫" -> "Delete"; "C" -> "Clear"; else -> k }
            ui.press(this, if (k == "=") Palette.GOLD else Palette.RAISED, 8)
            setOnClickListener { press(k) }
        }
    }

    // ---------------- Keys ----------------

    private fun press(k: String) {
        when (k) {
            "C" -> { expression = ""; justAnswered = false }
            "⌫" -> { expression = expression.dropLast(1); justAnswered = false }
            "=" -> {
                val value = evaluate(expression) ?: return
                lastSum = display(expression) + " ="
                expression = plain(value)
                justAnswered = true
            }
            else -> {
                val op = k in listOf("÷", "×", "−", "+")
                // after =, a number starts a new sum; an operator carries on from the answer
                if (justAnswered && !op && k !in listOf("k", "m", "b")) expression = ""
                justAnswered = false
                val c = when (k) { "÷" -> "/"; "×" -> "*"; "−" -> "-"; else -> k }
                // don't allow two operators in a row (swap the old one), except a minus for a negative number
                if (op && c != "-") while (expression.isNotEmpty() && expression.last() in "+-*/") expression = expression.dropLast(1)
                if (op && expression.isEmpty() && c != "-") expression = "0"
                expression += c
            }
        }
        refresh()
    }

    private fun refresh() {
        sumView.text = lastSum
        expressionView.text = if (expression.isEmpty()) "0" else display(expression)
        val value = evaluate(expression)
        resultView.text = when {
            expression.isEmpty() -> ""
            value == null -> if (justAnswered) "" else "…"
            else -> "= " + pretty(value)
        }
        fitAnswer()
    }

    // What you typed, with proper maths signs
    private fun display(e: String) = e.replace("*", " × ").replace("/", " ÷ ").replace("+", " + ")
        .replace(Regex("(?<=[0-9kmb)])-"), " − ")

    // ---------------- Working it out ----------------

    // Works out the sum, or null if it isn't finished (e.g. ends in "+") or divides by zero
    fun evaluate(e: String): BigDecimal? = try {
        val parser = Parser(e)
        val v = parser.sum()
        if (parser.pos != e.length) null else v
    } catch (ex: Exception) { null }

    private class Parser(val s: String) {
        var pos = 0
        private val mc = MathContext(34)

        fun sum(): BigDecimal {
            var v = product()
            while (pos < s.length && s[pos] in "+-") {
                val op = s[pos++]
                val r = product()
                v = if (op == '+') v.add(r, mc) else v.subtract(r, mc)
            }
            return v
        }

        private fun product(): BigDecimal {
            var v = unary()
            while (pos < s.length && s[pos] in "*/") {
                val op = s[pos++]
                val r = unary()
                v = if (op == '*') v.multiply(r, mc) else {
                    if (r.signum() == 0) throw ArithmeticException()
                    v.divide(r, mc)
                }
            }
            return v
        }

        private fun unary(): BigDecimal {
            if (pos < s.length && s[pos] == '-') { pos++; return unary().negate() }
            return atom()
        }

        private fun atom(): BigDecimal {
            if (pos < s.length && s[pos] == '(') {
                pos++
                val v = sum()
                if (pos < s.length && s[pos] == ')') pos++   // a missing ")" at the end is fine
                return suffix(v)
            }
            val start = pos
            while (pos < s.length && (s[pos].isDigit() || s[pos] == '.')) pos++
            if (pos == start) throw IllegalArgumentException()
            return suffix(BigDecimal(s.substring(start, pos)))
        }

        // 1.5k, 2m, 1b
        private fun suffix(v: BigDecimal): BigDecimal {
            var r = v
            while (pos < s.length && s[pos] in "kmb") {
                r = r.multiply(when (s[pos]) { 'k' -> BigDecimal(1_000); 'm' -> BigDecimal(1_000_000); else -> BigDecimal(1_000_000_000) })
                pos++
            }
            return r
        }
    }

    // The answer as it should be typed back in (no commas)
    // (A whole number is kept exactly, however long: big GP totals. Anything else is rounded to 15 digits, so a
    // third doesn't carry on as 0.333... to 34 places.)
    private fun plain(v: BigDecimal): String {
        val whole = v.stripTrailingZeros()
        if (whole.scale() <= 0) return whole.toBigInteger().toString()
        val r = v.round(MathContext(15)).stripTrailingZeros()
        return if (r.scale() <= 0) r.toBigInteger().toString() else r.toPlainString()
    }

    // The answer for reading: 1,234,567.5
    fun pretty(v: BigDecimal): String =
        DecimalFormat("#,##0.######").format(v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros())
}

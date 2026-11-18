package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.view.View
import android.view.WindowManager

// The Mastering Mixology tool's drawing over the game: each order's recipe in coloured letters after its name, like
// "(MLL)", a coloured dot with its letter on each lever, and each potion's recipe over it in the inventory. One see-through window over the whole screen that taps
// pass straight through (guideWindowParams). Positions are in screen pixels, the same as the screen pictures.
//
// With the whole screen shared, the next look sees this drawing too, so it's drawn in colours the readers ignore:
// the brackets are mid grey (the name reader only counts light grey to white as letters) and the letters and dots
// are the paste colours, which aren't white and aren't the funnels' paste shades (`itsOwnDotsArentFunnels`). The
// flashing arrow over the station to use next is a yellow with blue in it, which the progress bar's finder never
// follows (`MixologyStations.ARROW_COLOUR`). The bag
// tags are white like the plugin's, written over the bottom of each potion, below the part the bag reader compares
// (`MixologyBag.TAG_BASELINE`; `itsOwnTagsDontChangeWhatsRead`).
class MixologyOverlay(private val context: Context, private val windowManager: WindowManager) {

    companion object {
        private const val BRACKET = 0x909090   // mid grey: below the name reader's "whitish" (every channel over 170),
                                               // even at the window's 80% over a pale wall
        private const val DOT_SIZE = 0.17      // a dot's radius, in funnel spacings
        private const val DONE = 0x00E676      // "done!": the Aga green (too much blue to count as the stations' pure
                                               // green sparkle, MixologyStations.green)
        private const val NEXT = 0xFF00FF      // the next order's arrow: magenta, like the plugin's station highlight
                                               // (no paste's colour, and not the stations' green or the bar's yellow)
        // The arrow over the next station flashes: shown this long, then gone this long. Gone for longer than the
        // tool's looks are apart (MixologyTool.LOOK_MS), so in every flash at least one look sees the game with no
        // arrow on it at all.
        const val ARROW_ON_MS = 550L
        const val ARROW_OFF_MS = 400L
    }

    // One recipe to draw: after `x`, on the line from `top` to `bottom`; `scale` = screen pixels per game pixel.
    // `done` = "(done!)" instead of the letters (a finished potion for it is in the bag); `next` = an arrow after it:
    // this order's station is the one to go to next.
    class Recipe(val text: String, val x: Int, val top: Int, val bottom: Int, val scale: Double,
                 val done: Boolean = false, val next: Boolean = false)

    // The quick-time signal: a white frame round a station's progress bar (screen pixels)
    class Signal(val left: Float, val top: Float, val right: Float, val bottom: Float)

    // The Alembic's countdown: a white ring beside the bar that empties as the sparkle comes closer (`left` = the
    // part of the ring still there, 1 to 0), with the seconds to go in it
    class Countdown(val x: Float, val y: Float, val radius: Float, val left: Float, val seconds: Int)

    // One dot: its middle, radius and paste
    class Dot(val x: Float, val y: Float, val radius: Float, val paste: MixologyData.Paste)

    // One bag tag: a potion's recipe, starting at `x` on the line `baseline`, `size` pixels tall
    class Tag(val text: String, val x: Float, val baseline: Float, val size: Float)

    private var view: OverlayView? = null
    private var params: WindowManager.LayoutParams? = null
    private var area = Rect()
    private var recipes: List<Recipe> = emptyList()
    private var dots: List<Dot> = emptyList()
    private var tags: List<Tag> = emptyList()
    private var signal: Signal? = null
    private var countdown: Countdown? = null
    private var arrow: MixologyStations.Arrow? = null   // the arrow over the station to use next (null = none)
    private var arrowLit = false                        // ...showing now (it flashes)

    // The flashing: one redraw each time the arrow comes or goes, and only while there is an arrow
    private val flash = object : Runnable {
        override fun run() {
            val v = view ?: return
            if (arrow == null) return
            arrowLit = !arrowLit
            v.invalidate()
            v.postDelayed(this, if (arrowLit) ARROW_ON_MS else ARROW_OFF_MS)
        }
    }

    fun show(width: Int, height: Int) {
        hide()
        area = Rect(0, 0, width, height)
        val p = guideWindowParams(area)
        val v = OverlayView()
        try {
            windowManager.addView(v, p)
            view = v
            params = p
        } catch (e: Exception) {
            // couldn't draw over the screen: the tool's window still says what it sees
        }
    }

    fun hide() {
        view?.removeCallbacks(flash)
        arrow = null
        view?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        view = null
        params = null
    }

    // What to draw now (redrawn only if it changed)
    fun set(recipes: List<Recipe>, dots: List<Dot>, tags: List<Tag> = emptyList(), signal: Signal? = null,
            countdown: Countdown? = null, arrow: MixologyStations.Arrow? = null) {
        setArrow(arrow)
        if (sameRecipes(recipes) && sameDots(dots) && sameTags(tags) && sameSignal(signal) &&
            sameCountdown(countdown)) return
        this.recipes = recipes
        this.dots = dots
        this.tags = tags
        this.signal = signal
        this.countdown = countdown
        view?.invalidate()
    }

    // The arrow over the station to use next. A new arrow starts shown and flashing; one that only moved keeps its
    // flashing going; none stops it.
    private fun setArrow(new: MixologyStations.Arrow?) {
        val old = arrow
        if (new == null && old == null) return
        if (new != null && old != null && kotlin.math.abs(new.tipX - old.tipX) < 1.5f &&
            kotlin.math.abs(new.tipY - old.tipY) < 1.5f && new.width == old.width) return
        arrow = new
        val v = view ?: return
        if (new == null) {
            v.removeCallbacks(flash)
        } else if (old == null) {
            arrowLit = true
            v.removeCallbacks(flash)
            v.postDelayed(flash, ARROW_ON_MS)
        }
        v.invalidate()
    }

    private fun sameCountdown(new: Countdown?): Boolean {
        val old = countdown
        if (new == null || old == null) return new == null && old == null
        return new.x == old.x && new.y == old.y && new.radius == old.radius && new.seconds == old.seconds &&
            kotlin.math.abs(new.left - old.left) < 0.02f
    }

    // The countdown for a bar: a ring just past its right end, as tall as two and a half bars
    fun countdownFor(bar: MixologyStations.Bar, secondsLeft: Double, from: Double): Countdown {
        val r = (bar.bottom - bar.top) * 1.3f
        return Countdown(bar.right + r * 1.6f, (bar.top + bar.bottom) / 2f, r,
            (secondsLeft / from).toFloat().coerceIn(0f, 1f), kotlin.math.ceil(secondsLeft).toInt())
    }

    private fun sameSignal(new: Signal?): Boolean {
        val old = signal
        if (new == null || old == null) return new == null && old == null
        return new.left == old.left && new.top == old.top && new.right == old.right && new.bottom == old.bottom
    }

    // The quick-time signal for a progress bar: a frame round it, with room to spare so it can't be missed
    fun signalFor(bar: MixologyStations.Bar): Signal {
        val pad = bar.length * 0.25f
        return Signal(bar.left - pad, bar.top - pad, bar.right + pad, bar.bottom + pad)
    }

    private fun sameTags(new: List<Tag>) = new.size == tags.size && new.indices.all {
        val a = new[it]; val b = tags[it]
        a.text == b.text && a.x == b.x && a.baseline == b.baseline && a.size == b.size
    }

    // The tag for a potion found in the bag: its recipe over the bottom of its icon
    fun tagFor(found: MixologyBag.Tag) = Tag(found.recipe, found.left + MixologyBag.TAG_LEFT * found.scale,
        found.top + MixologyBag.TAG_BASELINE * found.scale, MixologyBag.TAG_SIZE * found.scale)

    private fun sameRecipes(new: List<Recipe>) = new.size == recipes.size && new.indices.all {
        val a = new[it]; val b = recipes[it]
        a.text == b.text && a.x == b.x && a.top == b.top && a.bottom == b.bottom && a.done == b.done && a.next == b.next
    }

    private fun sameDots(new: List<Dot>) = new.size == dots.size && new.indices.all {
        val a = new[it]; val b = dots[it]
        a.paste == b.paste && a.radius == b.radius && kotlin.math.abs(a.x - b.x) < 1 && kotlin.math.abs(a.y - b.y) < 1
    }

    // Each lever's dot, from the funnels found: Mox, Aga, Lye from left to right facing south, the other way round
    // and above the funnels facing north
    fun dotsFor(levers: MixologyLevers.Levers, north: Boolean, minRadius: Float, maxRadius: Float): List<Dot> {
        val r = (levers.spacing * DOT_SIZE).toFloat().coerceIn(minRadius, maxOf(minRadius, maxRadius))
            .coerceAtMost((levers.spacing * 0.45).toFloat())
        return (0..2).map { i ->
            Dot(levers.dotX(i, north).toFloat(), levers.dotY(i, north).toFloat(), r, levers.lever(i, north))
        }
    }

    @SuppressLint("ViewConstructor")
    private inner class OverlayView : View(context) {
        private val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.BLACK }
        private var aligned = false

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!aligned) {
                aligned = true
                params?.let { lineUpGuide(this, it, area, windowManager) }
            }
            for (r in recipes) drawRecipe(canvas, r)
            for (d in dots) drawDot(canvas, d)
            for (t in tags) drawTag(canvas, t)
            signal?.let { drawSignal(canvas, it) }
            countdown?.let { drawCountdown(canvas, it) }
            if (arrowLit) arrow?.let { drawArrow(canvas, it) }
        }

        // The arrow over the station to use next: yellow with a dark edge, pointing down at it (or, for a station at
        // the top of the screen, below it and pointing up). Its shape and colour are MixologyStations' (chosen so
        // the tool's own readers never take it for anything).
        private val arrowPath = android.graphics.Path()
        private fun drawArrow(canvas: Canvas, a: MixologyStations.Arrow) {
            val p = a.outline()
            arrowPath.rewind()
            arrowPath.moveTo(p[0], p[1])
            for (i in 2 until p.size step 2) arrowPath.lineTo(p[i], p[i + 1])
            arrowPath.close()
            fill.color = MixologyStations.ARROW_COLOUR or (0xFF shl 24)
            canvas.drawPath(arrowPath, fill)
            ring.color = Color.BLACK
            ring.strokeWidth = a.width * MixologyStations.ARROW_EDGE
            ring.strokeJoin = Paint.Join.ROUND   // (a pointed corner would stick out past the arrow's size)
            canvas.drawPath(arrowPath, ring)
            ring.strokeJoin = Paint.Join.MITER
        }

        // The white part of the ring still to go (emptying clockwise from the top) and the seconds, with no dark
        // background: dark next to the bar's dark olive end could make the bar look longer to the next look
        // (MixologyStations.findBar), and it would be lost
        private fun drawCountdown(canvas: Canvas, c: Countdown) {
            ring.color = Color.WHITE
            ring.strokeWidth = maxOf(3f, c.radius * 0.28f)
            val inset = ring.strokeWidth / 2
            canvas.drawArc(c.x - c.radius + inset, c.y - c.radius + inset, c.x + c.radius - inset, c.y + c.radius - inset,
                -90f, 360f * c.left, false, ring)
            ring.color = Color.BLACK
            letters.textAlign = Paint.Align.CENTER
            letters.textSize = c.radius * 1.1f
            val baseline = c.y - (letters.descent() + letters.ascent()) / 2
            val shadow = maxOf(1f, c.radius / 10f)
            letters.color = Color.BLACK
            canvas.drawText(c.seconds.toString(), c.x + shadow, baseline + shadow, letters)
            letters.color = Color.WHITE
            canvas.drawText(c.seconds.toString(), c.x, baseline, letters)
        }

        // A thick white frame with rounded corners and a dark edge, round the station's bar: tap the station now
        private fun drawSignal(canvas: Canvas, s: Signal) {
            val width = maxOf(4f, (s.bottom - s.top) * 0.18f)
            val corner = (s.bottom - s.top) / 3
            ring.color = Color.BLACK
            ring.strokeWidth = width + 3f
            canvas.drawRoundRect(s.left, s.top, s.right, s.bottom, corner, corner, ring)
            ring.color = Color.WHITE
            ring.strokeWidth = width
            canvas.drawRoundRect(s.left, s.top, s.right, s.bottom, corner, corner, ring)
            ring.color = Color.BLACK
        }

        // "MAL" in white with a dark shadow below-right, like the plugin's tags
        private fun drawTag(canvas: Canvas, t: Tag) {
            letters.textSize = t.size
            letters.textAlign = Paint.Align.LEFT
            val shadow = maxOf(1f, t.size / 10f)
            letters.color = Color.BLACK
            canvas.drawText(t.text, t.x + shadow, t.baseline + shadow, letters)
            letters.color = Color.WHITE
            canvas.drawText(t.text, t.x, t.baseline, letters)
        }

        // "(MLL)": the brackets grey, each letter in its paste's colour, each with a dark shadow below-right like the
        // game's own text, so it reads over any background. About as tall as the game's letters, a space after the name.
        private fun drawRecipe(canvas: Canvas, r: Recipe) {
            val s = r.scale.toFloat()
            letters.textSize = 13f * s
            letters.textAlign = Paint.Align.LEFT
            val baseline = r.top + 11f * s
            val shadow = maxOf(1f, s)
            var x = r.x + 4f * s
            val inside = if (r.done) listOf("done!" to DONE) else r.text.map { c ->
                c.toString() to (MixologyData.Paste.entries.firstOrNull { it.letter == c }?.colour ?: BRACKET)
            }
            val parts = listOf("(" to BRACKET) + inside + listOf(")" to BRACKET) +
                (if (r.next) listOf(" ◀" to NEXT) else emptyList())
            for ((t, colour) in parts) {
                letters.color = Color.BLACK
                canvas.drawText(t, x + shadow, baseline + shadow, letters)
                letters.color = colour or (0xFF shl 24)
                canvas.drawText(t, x, baseline, letters)
                x += letters.measureText(t)
            }
        }

        // A solid dot in its paste's colour with a thin dark ring, and its letter in black in the middle
        private fun drawDot(canvas: Canvas, d: Dot) {
            fill.color = d.paste.colour or (0xFF shl 24)
            canvas.drawCircle(d.x, d.y, d.radius, fill)
            ring.strokeWidth = maxOf(1.5f, d.radius * 0.12f)
            canvas.drawCircle(d.x, d.y, d.radius, ring)
            letters.color = Color.BLACK
            letters.textAlign = Paint.Align.CENTER
            letters.textSize = d.radius * 1.3f
            canvas.drawText(d.paste.letter.toString(), d.x, d.y - (letters.descent() + letters.ascent()) / 2, letters)
        }
    }
}

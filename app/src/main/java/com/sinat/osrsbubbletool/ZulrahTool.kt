/*
 * Zulrah arena layout adapted from the "Zulrah Helper" RuneLite plugin
 * (github.com/while-loop/runelite-plugins).
 *
 * Copyright (c) 2020, Anthony Alves
 * Copyright (c) 2026, Ron Young <https://github.com/raiyni>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import androidx.core.graphics.withRotation

// Zulrah Helper, made for a thin window against the side of the screen during the fight. Like the RuneLite plugin, every
// map shows only three things: Zulrah (the coloured circle), where to stand (the cross) and the prayer (an icon in the top
// left corner; on the "Jad" phase the first prayer is top left and the second top right). From the top: the phase you're
// on, then a map for each thing Zulrah could do next, to tap when it does it. The buttons you
// shouldn't press by accident are at the far end, and Reset asks twice. The fight itself is in ZulrahFight.
// To change sides, drag the strip's top bar across: it settles against the nearer side (BubbleService.dockZulrah).
class ZulrahTool(private val context: Context) {
    private companion object {
        const val TOOL_ROW_DP = 32
        const val GAP_DP = 3
        const val FOOTER_DP = 10        // a little room under the next-phase buttons for the window's resize corner (taps
                                        // on the corner reach the buttons under it anyway)
        const val TAP_GUARD_MS = 350L   // a second tap this soon is a slip, not a second phase
        const val RESET_WAIT_MS = 2500L

        // The prayer icons: our own drawings, made to look like the game's Protect from Magic (a fiery bolt) and
        // Protect from Missiles (an arrow with a green head), both with a purple rim as in the plugin
        val ICON_EDGE = "#140616".toColorInt()
        val ICON_RIM = "#8A2BB4".toColorInt()
        val BOLT_OUTER = "#B8300C".toColorInt()
        val BOLT_INNER = "#E8661A".toColorInt()
        val BOLT_CORE = "#F2C21E".toColorInt()
        val SHAFT = "#3A1446".toColorInt()
        val HEAD = "#3E9A22".toColorInt()
        val HEAD_SHADE = "#1F5A10".toColorInt()
        val NOCK = "#6E3A14".toColorInt()
        const val ICON_SIZE = 26f   // in the drawing's units
        const val NEXT_SCALE = 0.7f // a next phase's map is drawn at most this big next to the current one

        // The drawing's colours stay the same in light and dark mode, like a map
        val WATER = "#2B2B2B".toColorInt()
        val ISLAND = "#111111".toColorInt()
        val SHORE = "#6E6E6E".toColorInt()
        val PILLAR = "#878787".toColorInt()
        val GRID = "#3A3A3A".toColorInt()
        val RING = "#D8D8D8".toColorInt()

        const val MAP_SIZE = 105f   // the drawing's own units
        const val ISLAND_X = 13f
        const val ISLAND_Y = 22f

        // A game tile's place in the drawing's units: 5 units a tile, laid out like the plugin's picture (south at the
        // top, so east is on the left), the pillar at 2271, 3071 at 23, 14
        const val TILE = 5f
        fun tileLeft(x: Int) = 23f + (2271 - x) * TILE
        fun tileTop(y: Int) = 14f + (y - 3071) * TILE

        // The island's shore as the game draws it, rounder and a little wider than the floor tiles (traced on the
        // plugin's picture, in the same units)
        val OUTLINE = floatArrayOf(37f, 0f, 54f, 2f, 60f, 6f, 70f, 9f, 74f, 13f, 74f, 29f, 76f, 30f, 79f, 37f, 80f, 48f, 78f, 52f,
            75f, 54f, 75f, 56f, 68f, 61f, 59f, 61f, 55f, 57f, 54f, 51f, 56f, 48f, 56f, 38f, 58f, 35f, 58f, 30f, 56f, 26f, 48f, 18f,
            44f, 18f, 32f, 19f, 24f, 29f, 23f, 35f, 25f, 37f, 25f, 48f, 27f, 51f, 27f, 55f, 24f, 59f, 12f, 60f, 9f, 57f, 2f, 57f,
            0f, 54f, 1f, 45f, 6f, 37f, 5f, 24f, 8f, 15f, 11f, 13f, 11f, 11f, 15f, 10f, 19f, 6f)


        fun colourOf(form: ZulrahFight.Form) = when (form) {
            ZulrahFight.Form.GREEN -> Color.rgb(25, 194, 4)
            ZulrahFight.Form.RED -> Color.rgb(251, 0, 7)
            ZulrahFight.Form.BLUE -> Color.rgb(0, 51, 255)
        }
    }

    private val ui = Ui(context)
    private val fight = ZulrahFight()
    private val prefs = context.getSharedPreferences("zulrah", Context.MODE_PRIVATE)

    // How far the map is turned, in quarter turns (older versions could only turn it upside down)
    private var turn = prefs.getInt("turn", if (prefs.getBoolean("north_up", false)) 2 else 0)

    private lateinit var body: LinearLayout
    private lateinit var nowColumn: LinearLayout
    private lateinit var map: MapView
    private lateinit var nextColumn: LinearLayout
    private lateinit var resetWord: TextView
    private lateinit var resetIcon: ImageView

    private var lastTap = 0L
    private var resetArmed = false
    private var bodyW = 0
    private var bodyH = 0

    private fun dp(value: Int) = ui.dp(value)

    fun buildView(): View {
        map = MapView()
        nowColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(map, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0))
        }
        nextColumn = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        body = object : LinearLayout(context) {
            override fun onSizeChanged(w: Int, h: Int, oldW: Int, oldH: Int) {
                super.onSizeChanged(w, h, oldW, oldH)
                bodyW = w; bodyH = h
                post { fit() }
            }
        }.apply {
            addView(nowColumn)
            addView(nextColumn)
        }
        refresh()
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(dp(4), dp(3), dp(4), 0)
            addView(toolRow(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(TOOL_ROW_DP)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(GAP_DP) })
            addView(View(context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(FOOTER_DP)))
        }
    }

    // ◀ in the window's top bar takes back the last tap
    fun goBack() {
        fight.undo()
        refresh()
    }

    // ---------------- The row of rarely used buttons, at the far end from the next-phase buttons ----------------

    private fun toolRow() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        fun cell(view: View, gapBefore: Boolean) = addView(view,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (gapBefore) leftMargin = dp(GAP_DP) })
        cell(toolButton("turn_map", "Turn the map") {
            turn = (turn + 1) % 4
            prefs.edit { putInt("turn", turn) }
            refresh()
        }, false)

        resetIcon = ui.icon("reset", Palette.TEXT, 18)
        resetWord = ui.text("Sure?", 12f, Palette.BAD, 700).apply { gravity = Gravity.CENTER; visibility = View.GONE }
        cell(FrameLayout(context).apply {
            contentDescription = "Reset the fight"
            ui.press(this, Palette.RAISED, 8)
            addView(resetIcon, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            addView(resetWord, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            setOnClickListener { pressReset(this) }
        }, true)
    }

    private fun toolButton(icon: String, label: String, onClick: () -> Unit) = FrameLayout(context).apply {
        contentDescription = label
        ui.press(this, Palette.RAISED, 8)
        addView(ui.icon(icon, Palette.TEXT, 18), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    // The first tap asks, a second one within a moment resets
    private fun pressReset(button: View) {
        if (resetArmed) {
            disarmReset()
            fight.reset()
            refresh()
            return
        }
        resetArmed = true
        resetIcon.visibility = View.GONE
        resetWord.visibility = View.VISIBLE
        button.removeCallbacks(disarm)
        button.postDelayed(disarm, RESET_WAIT_MS)
    }

    private val disarm = Runnable { disarmReset() }

    private fun disarmReset() {
        resetArmed = false
        resetIcon.visibility = View.VISIBLE
        resetWord.visibility = View.GONE
    }

    // ---------------- What's on screen ----------------

    private fun refresh() {
        val phase = fight.current
        map.show(phase)
        showNext()
        fit()
    }

    private fun showNext() {
        nextColumn.removeAllViews()
        // side by side, each as wide as the others
        fight.nextOptions().forEachIndexed { i, option ->
            nextColumn.addView(nextButton(option), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply { if (i > 0) leftMargin = dp(GAP_DP) })
        }
    }

    // A button for one thing Zulrah could do next: just its map (no box round it), zoomed in on the island and that
    // phase's Zulrah, with the prayer in the water above the island, smaller than the current map (MapView.fitted)
    private fun nextButton(option: ZulrahFight.Option) = FrameLayout(context).apply {
        ui.press(this, null, 6)
        val phase = option.phase
        val name = when {
            option.restart -> "Restart"
            phase.title != null -> phase.title
            else -> phase.form.word
        }
        addView(MapView(fitted = true).apply { show(phase) }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        contentDescription = "Next: $name, ${prayerWords(phase)}"
        setOnClickListener {
            val now = SystemClock.uptimeMillis()
            if (now - lastTap < TAP_GUARD_MS) return@setOnClickListener
            lastTap = now
            if (AppSettings.vibrates(context)) performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            fight.choose(option)
            refresh()
        }
    }

    // Shares the window out. The phase you're on always gets the biggest map. Taller than it is wide: it's on top, with
    // the next phases in a row under it, at most 3/4 of its height. Wider than it is tall: it's on the left, with the
    // row of next phases on the right, at most 3/4 of its width.
    private fun fit() {
        if (bodyW <= 0 || bodyH <= 0) return
        val sideBySide = bodyW > bodyH * 1.6f
        val gap = dp(GAP_DP)
        body.orientation = if (sideBySide) LinearLayout.HORIZONTAL else LinearLayout.VERTICAL
        if (sideBySide) {
            val mapSize = minOf(bodyH, (bodyW - gap) * 4 / 7)
            (map.layoutParams as LinearLayout.LayoutParams).height = bodyH
            nowColumn.layoutParams = LinearLayout.LayoutParams(mapSize, ViewGroup.LayoutParams.MATCH_PARENT)
            val nextW = minOf(bodyW - mapSize - gap, mapSize * 3 / 4)
            nextColumn.layoutParams = LinearLayout.LayoutParams(nextW, ViewGroup.LayoutParams.MATCH_PARENT).apply { leftMargin = gap }
            // every next map the same size, whatever the phase and however many there are: as if three shared the row
            nextScale = minOf(NEXT_SCALE * mapSize / MAP_SIZE, (nextW - gap * 2) / 3f / pictureW(), bodyH.toFloat() / pictureH())
        } else {
            // the current map as wide as the strip, and the next phases' row under it, just as tall as its maps (the
            // window is made just tall enough for both: heightFor); squeezed only if the window is shorter than that
            val nextH = nextRowHeight(bodyW, bodyW)
            val mapSize = minOf(bodyW, bodyH - gap - nextH)
            (map.layoutParams as LinearLayout.LayoutParams).height = mapSize
            nowColumn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            // every next map the same size, whatever the phase and however many there are (as if three shared the
            // row), smaller than the current map
            nextScale = nextScaleFor(bodyW, mapSize)
            nextColumn.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (pictureH() * nextScale).toInt() + 1).apply { topMargin = gap }
        }
        for (i in 0 until nextColumn.childCount) (nextColumn.getChildAt(i) as? ViewGroup)?.getChildAt(0)?.invalidate()
        body.requestLayout()
    }

    // How big the next phases' maps are drawn (pixels per drawing unit) in a strip bodyW wide under a current map
    // mapSize across: smaller than the current map, and three to a row
    private fun nextScaleFor(bodyW: Int, mapSize: Int) =
        minOf(NEXT_SCALE * mapSize / MAP_SIZE, (bodyW - dp(GAP_DP) * 2) / 3f / pictureW())

    private fun nextRowHeight(bodyW: Int, mapSize: Int) = (pictureH() * nextScaleFor(bodyW, mapSize)).toInt() + 1

    // How tall the strip's contents are when the window is windowW wide: the row of buttons, the current map as wide
    // as the strip, the next phases' row and the space kept for the resize corner. The window is made this tall
    // (BubbleService), so there's no empty space at the bottom, and widening the strip makes everything bigger.
    fun heightFor(windowW: Int): Int {
        val bodyW = windowW - dp(4) * 2
        if (bodyW <= 0) return 0
        val gap = dp(GAP_DP)
        return dp(3) + dp(TOOL_ROW_DP) + gap + bodyW + gap + nextRowHeight(bodyW, bodyW) + dp(FOOTER_DP)
    }

    // The prayer in words, for screen readers
    private fun prayerWords(phase: ZulrahFight.Phase) =
        if (phase.prayers.isEmpty()) "no prayer" else phase.prayers.joinToString(" then ") { "pray ${it.word}" }

    // ---------------- The arena drawing ----------------

    // The island's shape with its floor tiles, where to stand (one white tile) and Zulrah (the coloured circle). Original drawing, made
    // from the game's own floor and the plugin's arena layout.
    // ---------------- Fitting a next phase's map to its button ----------------

    // The part of the drawing a phase needs, in the drawing's units, as turned on screen: the island, and Zulrah's
    // circle (it can be out in the water beside the island)
    private fun contentBox(spot: ZulrahFight.Spot): RectF {
        val r = 7.5f + 0.7f
        val box = RectF(0f, 0f, 80f, 61f)   // the island's outline
        box.union(spot.x - r, spot.y - r, spot.x + r, spot.y + r)
        box.offset(ISLAND_X, ISLAND_Y)
        // turned with the map, a quarter turn at a time round the drawing's middle
        val c = MAP_SIZE / 2f
        var l = box.left; var t = box.top; var rt = box.right; var b = box.bottom
        repeat(turn) {
            val nl = c - (b - c); val nr = c - (t - c); val nt = c + (l - c); val nb = c + (rt - c)
            l = nl; rt = nr; t = nt; b = nb
        }
        return RectF(l, t, rt, b)
    }

    // Every next map's picture is the same size, in the drawing's units, so the buttons never change size: wide and
    // tall enough for the island with Zulrah in any of its places, plus a band of open water along the top for the prayer
    // icons (as big as on the current map; left empty when there's no prayer). Nothing of the map is ever under the
    // icons.
    private val FIT_PAD = 2f
    private val BAND = ICON_SIZE + 2 * FIT_PAD
    private fun pictureW() = ZulrahFight.Spot.entries.maxOf { contentBox(it).width() } + 2 * FIT_PAD
    private fun pictureH() = BAND + ZulrahFight.Spot.entries.maxOf { contentBox(it).height() } + FIT_PAD

    // How big the next maps are drawn (pixels per drawing unit), set by fit()
    private var nextScale = 1f

    @SuppressLint("ViewConstructor")
    private inner class MapView(private val fitted: Boolean = false) : View(context) {
        private var phase: ZulrahFight.Phase? = null
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }
        private fun tile(x: Int, y: Int) = RectF(tileLeft(x), tileTop(y), tileLeft(x) + TILE, tileTop(y) + TILE)
        private val island = Path().apply {
            moveTo(OUTLINE[0], OUTLINE[1])
            for (i in 2 until OUTLINE.size step 2) lineTo(OUTLINE[i], OUTLINE[i + 1])
            close()
        }

        fun show(phase: ZulrahFight.Phase) {
            this.phase = phase
            contentDescription = prayerWords(phase)
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val shown = phase ?: return
            if (fitted) { drawFitted(canvas, shown); return }
            val size = minOf(width, height).toFloat()
            val scale = size / MAP_SIZE
            canvas.save()
            canvas.translate((width - size) / 2f, (height - size) / 2f)
            canvas.scale(scale, scale)

            paint.style = Paint.Style.FILL
            paint.color = WATER
            canvas.drawRoundRect(0f, 0f, MAP_SIZE, MAP_SIZE, 6f, 6f, paint)
            drawArena(canvas, shown)

            // the prayer icons stay upright in the corners however the map is turned
            shown.prayers.forEachIndexed { i, pray ->
                val x = if (i == 0) 2f else MAP_SIZE - ICON_SIZE - 2f
                canvas.save()
                canvas.translate(x, 2f)
                canvas.scale(ICON_SIZE / 24f, ICON_SIZE / 24f)
                if (pray == ZulrahFight.Pray.MAGIC) magicIcon(canvas) else missilesIcon(canvas)
                canvas.restore()
            }
            canvas.restore()
        }

        // A next phase's map: the picture (pictureW x pictureH, at nextScale: the same for every next map) in the middle
        // of the button, the island and its Zulrah in the middle of the picture below the icon band, and the prayer
        // icons in that band (first top left, Jad's second top right)
        private fun drawFitted(canvas: Canvas, shown: ZulrahFight.Phase) {
            val w = width.toFloat(); val h = height.toFloat()
            val pw = pictureW(); val ph = pictureH()
            val scale = minOf(nextScale, w / pw, h / ph)
            if (scale <= 0f) return
            canvas.save()
            canvas.translate((w - pw * scale) / 2f, (h - ph * scale) / 2f)
            canvas.scale(scale, scale)
            paint.style = Paint.Style.FILL
            paint.color = WATER
            canvas.drawRoundRect(0f, 0f, pw, ph, 6f, 6f, paint)
            shown.prayers.forEachIndexed { i, pray ->
                canvas.save()
                canvas.translate(if (i == 0) FIT_PAD else pw - FIT_PAD - ICON_SIZE, FIT_PAD)
                canvas.scale(ICON_SIZE / 24f, ICON_SIZE / 24f)
                if (pray == ZulrahFight.Pray.MAGIC) magicIcon(canvas) else missilesIcon(canvas)
                canvas.restore()
            }
            val box = contentBox(shown.spot)
            canvas.translate((pw - box.width()) / 2f - box.left, BAND + (ph - BAND - FIT_PAD - box.height()) / 2f - box.top)
            drawArena(canvas, shown)
            canvas.restore()
        }

        // The island, its tiles and pillars, where to stand and Zulrah, turned as the player chose (in the drawing's
        // units, MAP_SIZE square)
        private fun drawArena(canvas: Canvas, shown: ZulrahFight.Phase) {
            canvas.withRotation(turn * 90f, MAP_SIZE / 2f, MAP_SIZE / 2f) {
                translate(ISLAND_X, ISLAND_Y)
                paint.style = Paint.Style.FILL
                paint.color = ISLAND
                drawPath(island, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = SHORE
                drawPath(island, paint)
                // faint lines between the tiles across the whole island, so you can count them from the pillars
                paint.strokeWidth = 0.35f
                paint.color = GRID
                save()
                clipPath(island)
                var line = tileLeft(2271) - 5 * TILE
                while (line <= 85f) { drawLine(line, -5f, line, 70f, paint); line += TILE }
                line = tileTop(3071) - 4 * TILE
                while (line <= 70f) { drawLine(-5f, line, 85f, line, paint); line += TILE }
                restore()
                paint.style = Paint.Style.FILL
                paint.color = PILLAR
                ZulrahFight.Arena.pillars.forEach { (x, y) -> drawRect(tile(x, y), paint) }

                // the tile to stand on: white, with a dark edge so it shows on the dark floor
                for (stand in shown.stands) {
                    val spot = tile(stand.x, stand.y)
                    paint.style = Paint.Style.FILL
                    paint.color = Color.WHITE
                    drawRect(spot, paint)
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 0.8f
                    paint.color = Color.BLACK
                    drawRect(spot, paint)
                }

                paint.style = Paint.Style.FILL
                paint.color = colourOf(shown.form)
                drawCircle(shown.spot.x, shown.spot.y, 7.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.4f
                paint.color = RING
                drawCircle(shown.spot.x, shown.spot.y, 7.5f, paint)
            }
        }

        // Shapes on a 24 x 24 grid, pointing up and to the right like the game's icons
        // A ball of magic flying down to the bottom left: a round glowing head with a tail that fades to the top right
        private fun comet(x: Float, y: Float, r: Float, tipX: Float, tipY: Float) = Path().apply {
            addCircle(x, y, r, Path.Direction.CW)
            op(Path().apply {
                moveTo(tipX, tipY); lineTo(x + r * 0.707f, y + r * 0.707f); lineTo(x - r * 0.707f, y - r * 0.707f); close()
            }, Path.Op.UNION)
        }
        private val bolt = comet(7.4f, 16.6f, 5.4f, 19.6f, 4.4f)
        private val boltInside = comet(7.6f, 16.4f, 3.6f, 15.4f, 8.6f)
        private val arrowHead = Path().apply {     // a small head at the top right, with two barbs
            moveTo(21.8f, 2.2f); lineTo(20.6f, 9.6f); lineTo(17.6f, 8.2f); lineTo(15.8f, 6.4f); lineTo(14.4f, 3.4f); close()
        }
        private val arrowHeadShade = Path().apply {
            moveTo(21.8f, 2.2f); lineTo(20.6f, 9.6f); lineTo(17.6f, 8.2f); close()
        }

        private fun outlined(canvas: Canvas, path: Path, fill: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.4f
            paint.color = ICON_EDGE
            canvas.drawPath(path, paint)
            paint.strokeWidth = 1.6f
            paint.color = ICON_RIM
            canvas.drawPath(path, paint)
            paint.style = Paint.Style.FILL
            paint.color = fill
            canvas.drawPath(path, paint)
        }

        private fun magicIcon(canvas: Canvas) {
            outlined(canvas, bolt, BOLT_OUTER)
            paint.style = Paint.Style.FILL
            paint.color = BOLT_INNER
            canvas.drawPath(boltInside, paint)
            paint.color = BOLT_CORE                               // the bright middle of the ball
            canvas.drawCircle(7.8f, 16.2f, 2f, paint)
            // two sparks trailing behind it
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.6f
            paint.color = ICON_EDGE
            canvas.drawPoint(21.2f, 2.8f, paint)
            canvas.drawPoint(15.6f, 3.4f, paint)
            paint.style = Paint.Style.FILL
            paint.color = BOLT_INNER
            canvas.drawCircle(21.2f, 2.8f, 1f, paint)
            canvas.drawCircle(15.6f, 3.4f, 0.9f, paint)
        }

        private fun missilesIcon(canvas: Canvas) {
            // the shaft: a dark stick with a purple rim, from the nock at the bottom left to the head
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6.4f
            paint.color = ICON_EDGE
            canvas.drawLine(4.2f, 19.8f, 17f, 7f, paint)
            paint.strokeWidth = 4.6f
            paint.color = ICON_RIM
            canvas.drawLine(4.2f, 19.8f, 17f, 7f, paint)
            paint.strokeWidth = 2f
            paint.color = SHAFT
            canvas.drawLine(4.2f, 19.8f, 17f, 7f, paint)
            // the nock, a small brown square at the end
            paint.style = Paint.Style.FILL
            paint.color = NOCK
            canvas.drawRect(2.6f, 19.2f, 5.0f, 21.6f, paint)
            outlined(canvas, arrowHead, HEAD)
            paint.style = Paint.Style.FILL
            paint.color = HEAD_SHADE
            canvas.drawPath(arrowHeadShade, paint)
        }
    }
}

package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Reads the Mastering Mixology lab's Potion Orders box from the screen: finds the box by its orange title, then
// reads the three potion names under it by their shape, compared with the shapes in assets/mixology/names.tsv
// (made from the game's font by tools/mixology/build_name_shapes.py). No Android parts, so it's tested on a
// computer (MixologyReaderTest, on the owner's screenshots).
//
// Sizes are measured in "game pixels": the box is drawn by the game at a fixed size and then scaled to the screen,
// so the title's width tells how many screen pixels one game pixel is (`Box.scale`; about 2.1 on the owner's phone)
// and everything else follows from it. That way it works on any phone, and wherever the box is (it moves down when
// the chat is open).
object MixologyReader {

    // Must match tools/mixology/build_name_shapes.py
    const val BINS = 48
    private const val WIDTH_WEIGHT = 3.0

    // The box's layout in game pixels, measured on the owner's screenshots (orders_1.png, at 2.115 screen pixels a
    // game pixel: title 344-544 across, lines 55 px apart, names starting at x = 306)
    private const val TITLE_WIDTH = 94.6       // "Potion Orders"
    private const val FIRST_LINE = 20.3        // from the title's top to the first name's top (a pixel above it)
    private const val LINE_GAP = 26.0          // from one name to the next (also the plugin's spacing)
    private const val LINE_HEIGHT = 15.0       // a name with its tails (g, q, y) and a little room
    private const val TEXT_LEFT = -20.0        // where to start looking for a name, from the title's left (the
                                               // names start 18 left of it; the station icons end 27 left of it)
    private const val LONGEST = 140.0          // the longest name is 120 wide
    private const val WORD_GAP = 7.0           // a wider gap than this ends the name (the space between words is ~4)

    // How sure it must be: how well the best name fits (1 = exactly), and by how much it beats the next best.
    // On the owner's screenshots the right name fits 0.53-0.94 and beats the next by 0.37 or more.
    private const val MIN_FIT = 0.45
    private const val MIN_MARGIN = 0.2

    // A potion name's shape: its width and ink per column, squeezed to BINS columns
    class Shape(val name: String, val width: Double, val profile: DoubleArray)

    // Reads names.tsv
    fun readShapes(text: String): List<Shape> = text.lineSequence()
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .map { line ->
            val parts = line.split('\t')
            Shape(parts[0], parts[1].toDouble(), DoubleArray(BINS) { parts[2 + it].toDouble() / 100 })
        }
        .toList()

    // Where the box is: its title's edges (right and bottom just outside it) and screen pixels per game pixel
    class Box(val left: Int, val top: Int, val right: Int, val bottom: Int, val scale: Double)

    // One order line, read: the potion's name (null if it couldn't be read surely), where its text ends on the
    // right (the recipe is drawn after it), its top and bottom rows, and how well it fit
    class Order(val name: String?, val textRight: Int, val top: Int, val bottom: Int, val fit: Double)

    // The title's orange ("Potion Orders": about 220, 136, 33 on the owner's phone)
    private fun orange(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        return r > 170 && r - b > 110 && g * 100 > r * 45 && g * 100 < r * 75
    }

    // The names' white (really light grey, 208-236 on the owner's phone)
    private fun whitish(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val lo = minOf(r, g, b)
        return lo > 170 && maxOf(r, g, b) - lo < 30
    }

    private fun dark(rgb: Int): Boolean =
        maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF) < 70

    // Finds the box: looks for the orange title in the left part of the screen (left 45%, top 80%). `last` = where it
    // was last time: looked at first, so a box that hasn't moved is found without searching the whole area.
    fun findOrderBox(p: LightBoxReader.PixelSource, last: Box? = null): Box? {
        if (last != null) {
            val pad = (last.bottom - last.top) * 2
            findTitle(p, last.left - pad, last.top - pad, last.right + pad, last.bottom + pad)?.let { return it }
        }
        return findTitle(p, 0, 0, (p.width * 0.45).toInt(), (p.height * 0.8).toInt())
    }

    // The title in an area: a band of rows with orange in them, whose orange (the two words, gaps allowed) is
    // 6.5-10.5 times as wide as the band is tall, like "Potion Orders". The one with the most orange wins.
    private fun findTitle(p: LightBoxReader.PixelSource, left: Int, top: Int, right: Int, bottom: Int): Box? {
        val x0 = left.coerceAtLeast(0)
        val y0 = top.coerceAtLeast(0)
        val x1 = right.coerceAtMost(p.width)
        val y1 = bottom.coerceAtMost(p.height)
        if (x1 - x0 < 10 || y1 - y0 < 8) return null
        val rows = IntArray(y1 - y0)
        for (y in y0 until y1) {
            var n = 0
            for (x in x0 until x1) if (orange(p.rgb(x, y))) n++
            rows[y - y0] = n
        }
        var best: Box? = null
        var bestOrange = 0
        var y = 0
        while (y < rows.size) {
            if (rows[y] < 2) { y++; continue }
            val bandTop = y
            while (y < rows.size && rows[y] >= 2) y++
            val h = y - bandTop
            if (h < 8) continue
            // Which columns have orange in this band, joined into runs (the gap between the two words is allowed)
            var runStart = -1
            var runEnd = -1
            var runOrange = 0
            fun endRun() {
                if (runStart < 0) return
                val w = runEnd - runStart + 1
                val scale = w / TITLE_WIDTH
                val ratio = w.toDouble() / h
                if (ratio in 6.5..10.5 && h / scale in 8.0..14.0 && runOrange > bestOrange) {
                    bestOrange = runOrange
                    best = Box(x0 + runStart, y0 + bandTop, x0 + runEnd + 1, y0 + y, scale)
                }
            }
            for (x in x0 until x1) {
                var n = 0
                for (yy in y0 + bandTop until y0 + y) if (orange(p.rgb(x, yy))) n++
                if (n == 0) continue
                val cx = x - x0
                if (runStart >= 0 && cx - runEnd > 1.6 * h) { endRun(); runStart = -1; runOrange = 0 }
                if (runStart < 0) runStart = cx
                runEnd = cx
                runOrange += n
            }
            endRun()
        }
        return best
    }

    // Reads the three order lines under the box's title
    fun readOrders(p: LightBoxReader.PixelSource, box: Box, shapes: List<Shape>): List<Order?> =
        (0 until 3).map { readLine(p, box, it, shapes) }

    private fun readLine(p: LightBoxReader.PixelSource, box: Box, line: Int, shapes: List<Shape>): Order? {
        val s = box.scale
        val top = (box.top + (FIRST_LINE + LINE_GAP * line) * s).roundToInt()
        val bottom = (top + LINE_HEIGHT * s).roundToInt().coerceAtMost(p.height)
        val left = (box.left + TEXT_LEFT * s).roundToInt().coerceAtLeast(0)
        val right = (left + LONGEST * s).roundToInt().coerceAtMost(p.width)
        if (top < 0 || bottom - top < 4 || right - left < 10) return null

        // Ink per column: whitish pixels with a dark one just below and to the right (the game's shadow under every
        // letter), so a pale wall or pot behind the box isn't taken for letters
        val reach = ceil(2 * s).toInt()
        val ink = IntArray(right - left)
        for (x in left until right) {
            var n = 0
            for (y in top until bottom) {
                if (!whitish(p.rgb(x, y))) continue
                for (k in 1..reach) {
                    if (x + k >= p.width || y + k >= p.height) break
                    if (dark(p.rgb(x + k, y + k))) { n++; break }
                }
            }
            ink[x - left] = n
        }

        // The name: from the first inked column to the last before a gap wider than between two words
        val start = ink.indexOfFirst { it > 0 }
        if (start < 0) return null
        var end = start
        var gap = 0
        for (c in start until ink.size) {
            if (ink[c] > 0) { end = c; gap = 0 } else if (++gap > WORD_GAP * s) break
        }
        val profile = squeeze(DoubleArray(end - start + 1) { ink[start + it].toDouble() })
        val width = (end - start + 1) / s

        var best: Shape? = null
        var bestFit = -9.0
        var nextFit = -9.0
        for (shape in shapes) {
            val f = fit(profile, width, shape)
            if (f > bestFit) { nextFit = bestFit; bestFit = f; best = shape } else if (f > nextFit) nextFit = f
        }
        val sure = best != null && bestFit >= MIN_FIT && bestFit - nextFit >= MIN_MARGIN
        return Order(if (sure) best!!.name else null, left + end + 1, top, bottom, bestFit)
    }

    // Squeezes (or stretches) a row of numbers to BINS numbers, each the average over its share of the row
    // (same as the build script's squeeze)
    fun squeeze(row: DoubleArray): DoubleArray {
        val n = row.size
        val total = DoubleArray(n + 1)
        for (i in 0 until n) total[i + 1] = total[i] + row[i]
        fun upTo(x: Double): Double {   // the sum of the row from 0 to x (x in columns, may be a fraction)
            val i = minOf(x.toInt(), n - 1)
            return total[i] + (x - i) * row[i]
        }
        val step = n.toDouble() / BINS
        return DoubleArray(BINS) { k -> (upTo((k + 1) * step) - upTo(k * step)) / step }
    }

    // How well a line fits a name's shape (same as the build script's fit): how alike the two ink rows go up and
    // down (1 = exactly alike), less a penalty for a different width
    private fun fit(profile: DoubleArray, width: Double, shape: Shape): Double {
        val ma = profile.average()
        val mb = shape.profile.average()
        var ab = 0.0
        var aa = 0.0
        var bb = 0.0
        for (i in 0 until BINS) {
            val a = profile[i] - ma
            val b = shape.profile[i] - mb
            ab += a * b; aa += a * a; bb += b * b
        }
        val alike = if (aa > 0 && bb > 0) ab / sqrt(aa * bb) else 0.0
        return alike - WIDTH_WEIGHT * abs(width - shape.width) / shape.width
    }
}

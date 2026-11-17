package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.roundToInt

// The Mastering Mixology lab's three stations, read from the screen. No Android parts, so it's tested on a computer
// (MixologyStationsTest, on the owner's recording play_2.mp4). Three jobs:
//
//  1. Which station each order wants: the small icon left of every name in the Potion Orders box (`stationOf`).
//  2. The quick-time moment (the Alembic's sparkle, the Agitator's bubbles): the game draws a yellow progress bar
//     over a station while it works on your potion (`findBar`), and both effects are a pure bright green near that
//     bar (`green`). `Watch` turns the looks into "tap it now" and "a potion just came out of this station". The
//     Alembic's sparkle shows too late to tap on, so its "tap it now" comes from the bar instead (`AlembicClock`).
//  3. Which orders are done and which station is next, from the orders and the potions in the bag (`plan`), the way
//     the Mastering Mixology plugin by Hexagon decides it (BSD 2-Clause). Reading the bag isn't done here.
//
// Every measurement is in .scratch/mixology/stations.md. Sizes are in "game pixels" like MixologyReader's
// (`scale` = MixologyReader.Box.scale, screen pixels per game pixel, about 2.1 on the owner's phone).
object MixologyStations {

    // The stations, with what each does to a potion. Only the Alembic and the Agitator have a moment to tap.
    enum class Station(val title: String, val makes: String, val quickTime: Boolean) {
        ALEMBIC("Alembic", "Crystalised", true),
        AGITATOR("Agitator", "Homogenous", true),
        RETORT("Retort", "Concentrated", false)
    }

    // ---------------------------------------------------------------------------------------------------------
    // 1. The station icon beside an order
    // ---------------------------------------------------------------------------------------------------------

    // The icon is a light grey picture 16 game pixels square with a black edge, always in the same place beside its
    // order: it starts 46 game pixels left of the title's left edge and 1.5 above the order's top row (measured on
    // 650 frames of play_2.mp4). It's summed up as 8 x 8 squares, each "how much of this square is light grey" (0 to
    // 100), and compared with the three icons' own squares below. Which icon is which station was read from the
    // recording: the potions with the flask went into the Alembic, the pot on legs into the Retort, the frame with
    // a "v" into the Agitator.
    private const val ICON_LEFT = -46.0
    private const val ICON_UP = -1.5
    private const val ICON_SIZE = 16.0
    private const val SQUARES = 8

    // How sure it must be: the squares differ from the best icon's by at most this on average (0 to 1), and from
    // the next best by this much more. Measured: the right icon 0.00-0.13, the other two 0.45 or more.
    private const val ICON_FIT = 0.20
    private const val ICON_MARGIN = 0.15

    // Each icon's 8 x 8 squares, row by row from the top (the middle value of 600 or more sightings each)
    private val ICONS: Map<Station, IntArray> = mapOf(
        // A flask: thin neck on top, wide flat bottom
        Station.ALEMBIC to intArrayOf(
            0, 0, 0, 44, 60, 0, 0, 0,
            0, 0, 0, 25, 40, 0, 0, 0,
            0, 0, 0, 60, 64, 0, 0, 0,
            0, 19, 69, 100, 100, 75, 25, 0,
            19, 94, 100, 100, 100, 100, 100, 20,
            35, 100, 100, 100, 100, 100, 100, 50,
            20, 100, 100, 100, 100, 100, 100, 50,
            15, 75, 75, 75, 75, 75, 75, 32),
        // A round pot with a wide rim, on three legs
        Station.RETORT to intArrayOf(
            15, 75, 75, 75, 75, 75, 75, 19,
            0, 38, 100, 100, 100, 100, 56, 0,
            0, 40, 100, 100, 100, 100, 55, 0,
            5, 94, 100, 100, 100, 100, 100, 19,
            25, 100, 100, 100, 100, 100, 100, 50,
            10, 94, 100, 100, 100, 100, 100, 25,
            0, 65, 100, 60, 68, 90, 90, 0,
            30, 6, 25, 31, 55, 19, 6, 31),
        // A frame (a bar across the top and down the left) with a "v" inside
        Station.AGITATOR to intArrayOf(
            55, 75, 75, 75, 75, 75, 75, 0,
            80, 100, 56, 50, 50, 50, 50, 0,
            72, 95, 0, 0, 0, 0, 0, 0,
            60, 75, 0, 6, 45, 0, 25, 50,
            60, 75, 0, 19, 95, 0, 69, 100,
            80, 100, 0, 0, 80, 25, 25, 56,
            72, 100, 0, 0, 52, 65, 75, 25,
            45, 75, 25, 0, 15, 75, 75, 0)
    )

    // The icons' light grey (170-235 on the owner's phone, the three colours nearly equal)
    private fun light(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val lo = minOf(r, g, b)
        return lo > 150 && maxOf(r, g, b) - lo < 30
    }

    // Which station the order wants, from the icon beside it; null if it isn't surely one of the three (never a
    // guess). `box` and `order` are what MixologyReader found on the same picture.
    fun stationOf(p: LightBoxReader.PixelSource, box: MixologyReader.Box, order: MixologyReader.Order): Station? {
        val s = box.scale
        val x0 = box.left + ICON_LEFT * s
        val y0 = order.top + ICON_UP * s
        val cell = ICON_SIZE * s / SQUARES
        if (x0 < 0 || y0 < 0 || x0 + ICON_SIZE * s > p.width || y0 + ICON_SIZE * s > p.height || cell < 2) return null
        val squares = IntArray(SQUARES * SQUARES)
        for (gy in 0 until SQUARES) {
            val top = (y0 + gy * cell).roundToInt()
            val bottom = (y0 + (gy + 1) * cell).roundToInt()
            for (gx in 0 until SQUARES) {
                val left = (x0 + gx * cell).roundToInt()
                val right = (x0 + (gx + 1) * cell).roundToInt()
                var n = 0
                for (y in top until bottom) for (x in left until right) if (light(p.rgb(x, y))) n++
                squares[gy * SQUARES + gx] = n * 100 / ((bottom - top) * (right - left))
            }
        }
        var best: Station? = null
        var bestDiff = 9.0
        var nextDiff = 9.0
        for ((station, icon) in ICONS) {
            var sum = 0
            for (i in squares.indices) sum += abs(squares[i] - icon[i])
            val diff = sum / 100.0 / squares.size
            if (diff < bestDiff) { nextDiff = bestDiff; bestDiff = diff; best = station } else if (diff < nextDiff) nextDiff = diff
        }
        return if (bestDiff <= ICON_FIT && nextDiff - bestDiff >= ICON_MARGIN) best else null
    }

    // The three orders' stations (null where the order line or its icon couldn't be read)
    fun stationsOf(p: LightBoxReader.PixelSource, box: MixologyReader.Box, orders: List<MixologyReader.Order?>): List<Station?> =
        orders.map { if (it == null) null else stationOf(p, box, it) }

    // ---------------------------------------------------------------------------------------------------------
    // 2. The progress bar and the quick-time moment
    // ---------------------------------------------------------------------------------------------------------

    // While a station works on your potion (9 seconds), the game draws a bar over it: yellow from the left as far
    // as it's got, dark olive for the rest. 50 game pixels long and 5 tall, the same at every zoom. In play_2.mp4 a
    // bar was on screen exactly during the owner's 12 station uses and never otherwise (3,250 frames).
    private const val BAR_LENGTH = 49.6
    private const val BAR_LENGTH_SLACK = 6.0
    private const val BAR_MIN_HEIGHT = 3.5
    private const val BAR_MAX_HEIGHT = 7.0

    // The bar: its edges on screen (right and bottom just outside it) and how full it is (0 to 1)
    class Bar(val left: Int, val top: Int, val right: Int, val bottom: Int, val fill: Double) {
        val length: Int get() = right - left
    }

    // The bar's yellow (218, 209, 0) and its dark olive (44, 42, 0)
    private fun yellow(rgb: Int): Boolean =
        (rgb shr 16 and 0xFF) > 190 && (rgb shr 8 and 0xFF) > 180 && (rgb and 0xFF) < 70

    private fun olive(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        return r in 26..70 && g in 26..70 && (rgb and 0xFF) < 25 && abs(r - g) < 14
    }

    // Finds the progress bar. `last` = the bar from the look before: the area round it is searched first (the bar
    // moves with the camera), which costs almost nothing; without it, or if it's gone from there, the whole screen
    // is searched, looking at one pixel in about 80 (the bar is long and tall enough to be hit anyway).
    fun findBar(p: LightBoxReader.PixelSource, scale: Double, last: Bar? = null): Bar? {
        if (last != null) {
            val pad = last.length * 3 / 2
            searchBar(p, scale, last.left - pad, last.top - pad, last.right + pad, last.bottom + pad)?.let { return it }
        }
        return searchBar(p, scale, 0, 0, p.width, p.height)
    }

    private fun searchBar(p: LightBoxReader.PixelSource, scale: Double, left: Int, top: Int, right: Int, bottom: Int): Bar? {
        val x0 = left.coerceAtLeast(0)
        val y0 = top.coerceAtLeast(0)
        val x1 = right.coerceAtMost(p.width)
        val y1 = bottom.coerceAtMost(p.height)
        val rowStep = (2 * scale).toInt().coerceAtLeast(1)     // the bar is 5 game pixels tall: two rows hit it
        val colStep = (10 * scale).toInt().coerceAtLeast(2)    // and 50 long: four or five pixels a row hit it
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val rgb = p.rgb(x, y)
                if (yellow(rgb) || olive(rgb)) {
                    val bar = barAt(p, scale, x, y)
                    if (bar != null) return bar
                }
                x += colStep
            }
            y += rowStep
        }
        return null
    }

    // The bar through this pixel, if it is one: a row of yellow then olive of the bar's length and height, with at
    // least a little yellow at its left end (a station that's working always shows some)
    private fun barAt(p: LightBoxReader.PixelSource, scale: Double, x: Int, y: Int, again: Boolean = true): Bar? {
        val most = ((BAR_LENGTH + BAR_LENGTH_SLACK) * scale).toInt() + 1
        var left = x
        while (left > 0 && x - left <= most && barColour(p.rgb(left - 1, y))) left--
        var right = x + 1
        while (right < p.width && right - left <= most && barColour(p.rgb(right, y))) right++
        val length = (right - left) / scale
        if (abs(length - BAR_LENGTH) > BAR_LENGTH_SLACK) return null

        // Yellow from the left, then only olive (one or two pixels between them are a mix of both)
        var filled = left
        while (filled < right && yellowish(p.rgb(filled, y))) filled++
        if (filled - left < 2) return null
        for (xx in filled until right) if (yellowish(p.rgb(xx, y))) return null

        // Its height, measured in the yellow part
        val column = left + (filled - left) / 2
        val tallest = (BAR_MAX_HEIGHT * scale).toInt() + 1
        var top = y
        while (top > 0 && y - top <= tallest && yellowish(p.rgb(column, top - 1))) top--
        var bottom = y + 1
        while (bottom < p.height && bottom - top <= tallest && yellowish(p.rgb(column, bottom))) bottom++
        val height = (bottom - top) / scale
        if (height < BAR_MIN_HEIGHT || height > BAR_MAX_HEIGHT) return null
        // Measured again along its middle row, so the same bar gives the same answer whichever row was hit first
        // (its top and bottom rows are a pixel shorter)
        val middle = (top + bottom) / 2
        if (again && middle != y) return barAt(p, scale, column, middle, false)
        return Bar(left, top, right, bottom, (filled - left).toDouble() / (right - left))
    }

    // Any pixel of the bar: its yellow, its olive, or the mix of the two where they meet. Red and green nearly
    // equal and next to no blue.
    private fun barColour(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        return r >= 26 && abs(r - g) < 18 && (rgb and 0xFF) * 4 < r + 40
    }

    // The yellow half of that (the bar's yellow is 218, its olive 44)
    private fun yellowish(rgb: Int): Boolean = barColour(rgb) && (rgb shr 16 and 0xFF) > 130

    // The quick-time effects' green: bright and pure (about 58, 207, 50; up to 56, 241, 55), both the Alembic's
    // sparkle in its first 0.3 s and the Agitator's bubbles. Purer than anything else seen near a working station:
    // the Retort's green liquid is about (38, 175, 75), the Alembic's green tube (75, 174, 81), and the tool's own
    // green (the Aga dot and letters, #00E676) has far too much blue. So the tool's drawing never counts.
    private fun pureGreen(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        return g > 170 && r * 100 < g * 40 && b * 100 < g * 33
    }

    // How far round the bar the effects show, in bar lengths (the sparkle within 0.6 of a length of the bar's
    // middle; the bubbles rise up to 1.4 lengths above it)
    private const val GLOW_SIDE = 1.3
    private const val GLOW_ABOVE = 1.6
    private const val GLOW_BELOW = 0.8

    // How many pure green pixels there are round the bar (an area of about 69,000 pixels on the owner's phone).
    // Give the number to Watch.look.
    fun green(p: LightBoxReader.PixelSource, bar: Bar): Int {
        val len = bar.length
        val middle = bar.left + len / 2
        val x0 = (middle - GLOW_SIDE * len).toInt().coerceAtLeast(0)
        val x1 = (middle + GLOW_SIDE * len).toInt().coerceAtMost(p.width)
        val y0 = (bar.top - GLOW_ABOVE * len).toInt().coerceAtLeast(0)
        val y1 = (bar.top + GLOW_BELOW * len).toInt().coerceAtMost(p.height)
        // A quick look first, at every third pixel each way (a ninth of the work): nearly every look has no pure
        // green at all, and then that's the answer. Only when some turns up is every pixel counted.
        var any = false
        var y = y0
        while (y < y1 && !any) {
            var x = x0
            while (x < x1) {
                if (pureGreen(p.rgb(x, y))) { any = true; break }
                x += 3
            }
            y += 3
        }
        if (!any) return 0
        var n = 0
        for (yy in y0 until y1) for (xx in x0 until x1) if (pureGreen(p.rgb(xx, yy))) n++
        return n
    }

    // The pure green round a working station's bar, leaving out the potion's own green. A green potion (Alco-augmentator)
    // in the Agitator is pure green: still at first, then swirling in time with the game's ticks (play_4.mp4: 70 to
    // 235 pixels, then almost none, every 0.58 s), which `green` alone takes for bubbles over and over. So the area
    // round the bar is split into squares of CELL pixels, and each square remembers when it last had green. Green in
    // a square counts only if it's new there: not green for STILL_MS on end already (a still potion), and not green
    // a moment before, between GAP_MS and MEMORY_MS ago (a swirl coming round again). The effects pass that: the
    // sparkle stays green 0.3 s in one place, the bubbles' specks move. Nothing counts in the first LEARN_MS of a
    // bar's time either, while the squares learn the potion (the effects never come that early: the bubbles 1.3 s or
    // more after the bar starts, the sparkle 2.5 s, stations.md). The squares are kept relative to the bar, so they
    // follow it when the camera moves.
    class Glow {
        companion object {
            const val LEARN_MS = 1_000L
            private const val CELL = 4
            private const val NEW_BAR_MS = 400L   // the bar missing this long = the next bar is another station's work
            private const val GAP_MS = 150L       // green again after this long = a new spell of green in that square
            private const val STILL_MS = 400L     // green this long on end = something still (the sparkle's green: 0.3 s)
            private const val MEMORY_MS = 1_300L  // green again within this = a swirl coming round (it's every 0.58 s)
        }

        private var cols = 0
        private var rows = 0
        private var barLength = 0
        private var spellStart = LongArray(0)   // each square: when its green spell began,
        private var lastGreen = LongArray(0)    // when it last had green,
        private var spellBefore = LongArray(0)  // and when its spell before that ended
        private var since = -1L        // when this bar was first seen (-1 = none yet)
        private var lastSeen = 0L

        fun reset() { since = -1 }

        // Notes green in square `i` now, and says whether it's the potion's (still, or back again soon after)
        private fun potions(i: Int, now: Long): Boolean {
            if (now - lastGreen[i] > GAP_MS) {
                spellBefore[i] = lastGreen[i]
                spellStart[i] = now
            }
            lastGreen[i] = now
            return now - spellStart[i] >= STILL_MS || now - spellBefore[i] <= MEMORY_MS
        }

        private fun known(i: Int, now: Long): Boolean =
            now - spellStart[i] >= STILL_MS && now - lastGreen[i] <= GAP_MS || now - spellBefore[i] <= MEMORY_MS

        // One look: the time, the bar (null = not seen) and the screen. Returns the count to give Watch.look.
        fun look(p: LightBoxReader.PixelSource, bar: Bar?, now: Long): Int {
            if (bar == null) return 0
            val len = bar.length
            val w = (2 * GLOW_SIDE * len).toInt()
            val h = ((GLOW_ABOVE + GLOW_BELOW) * len).toInt()
            // (the bar's length read can differ by a pixel or two from look to look: only a real change, a zoom,
            // starts the learning again)
            if (since < 0 || now - lastSeen > NEW_BAR_MS || abs(barLength - len) * 10 > barLength) {
                since = now
                barLength = len
                cols = w / CELL + 2
                rows = h / CELL + 2
                val never = Long.MIN_VALUE / 4
                spellStart = LongArray(cols * rows) { never }
                lastGreen = LongArray(cols * rows) { never }
                spellBefore = LongArray(cols * rows) { never }
            }
            lastSeen = now
            val x0 = (bar.left + len / 2 - GLOW_SIDE * len).toInt()
            val y0 = (bar.top - GLOW_ABOVE * len).toInt()
            val learning = now - since < LEARN_MS
            // A quick look first, as in `green`: with no pure green at every third pixel, there's nothing to count
            // or learn
            var any = false
            var qy = maxOf(0, y0)
            while (qy < minOf(p.height, y0 + h) && !any) {
                var qx = maxOf(0, x0)
                while (qx < minOf(p.width, x0 + w)) {
                    if (pureGreen(p.rgb(qx, qy))) { any = true; break }
                    qx += 3
                }
                qy += 3
            }
            if (!any) return 0
            var n = 0
            for (yy in 0 until h) {
                val y = y0 + yy
                if (y < 0 || y >= p.height) continue
                for (xx in 0 until w) {
                    val x = x0 + xx
                    if (x < 0 || x >= p.width || !pureGreen(p.rgb(x, y))) continue
                    val cx = xx / CELL
                    val cy = yy / CELL
                    if (cx >= cols || cy >= rows) continue
                    // The potion's if its own square says so, or a square next to it (the swirl's edge wanders a
                    // little from turn to turn)
                    var theirs = potions(cy * cols + cx, now)
                    if (!theirs) for (dy in -1..1) for (dx in -1..1) {
                        val ax = cx + dx
                        val ay = cy + dy
                        if ((dx != 0 || dy != 0) && ax in 0 until cols && ay in 0 until rows && known(ay * cols + ax, now)) theirs = true
                    }
                    if (!theirs) n++
                }
            }
            return if (learning) 0 else n
        }
    }

    // The Alembic's sparkle: how many seconds until the first one shows, from how full its bar is. The sparkle came
    // with the bar 0.29 to 0.31 full every time (15 first sparkles in play_2, play_3, play_5, play_7 and play_8),
    // and the bar fills about 0.105 of its length a second until then (0.19-0.21 a second before, 0.07-0.10 two
    // seconds before). Null once the sparkle is due or past. Only for the first sparkle: a mistimed tap sets the bar
    // back, and the next sparkle is timed from that (AlembicClock).
    fun alembicCountdown(fill: Double): Double? =
        if (fill >= SPARKLE_FILL) null else (SPARKLE_FILL - fill) / FILL_PER_SECOND

    const val SPARKLE_FILL = 0.30
    const val FILL_PER_SECOND = 0.105

    // When to tap the Alembic. **The sparkle is too late to go by**: the game counts a tap only if it arrives during
    // the pump before the sparkle shows (the wiki: "when the bellows fully opens for the 4th time"; the bellows open
    // once a game tick, 0.6 s). Measured at 60 frames a second in play_5, play_7 and play_8 (stations.md, 16 November
    // 2026): the owner's taps landed 0.2 to 0.9 s after the sparkle began (one 0.23 s before it) and all 21 set the
    // bar back, also the five the game dealt with on the very next tick. A tap takes effect on the first tick that
    // shows at least 0.25 s (0.22-0.28) after the finger comes down. So the finger has to come down 0.85 to 0.25 s
    // before the sparkle shows.
    //
    // So the clock gives its cue (a buzz and the white frame, together) LEAD_MS before the sparkle is due: the owner's
    // finger came down 0.28 to 0.42 s after the frame showed (8 taps in play_5), which lands about 0.7 to 0.4 s
    // before the sparkle, the middle of what counts. The ring counts down to the cue, not to the sparkle. (Replayed
    // on the 10 uses of play_5, play_7 and play_8, the cue came 0.80 to 1.11 s before the first sparkle and 0.80 to
    // 1.11 s before a later one: the bar and the game's ticks don't allow closer.)
    //
    // When the sparkle is due:
    //  - the first: when the bar is SPARKLE_FILL full. The bar moves a game pixel (0.019 of its length) about every
    //    0.19 s, so between its moves the fill is carried on at FILL_PER_SECOND from when it last moved;
    //  - after a mistimed tap (the bar goes back by 0.18): 7 game ticks after the bar went back (4.10 to 4.33 s in
    //    all 12 such sparkles; the wiki's "7th pump after the initial failure"). Another mistimed tap starts the 7
    //    ticks again (play_7 at 45.5 s). Without a mistimed tap there's no second sparkle (play_2);
    //  - after a hit (the bar jumps forward, as the Agitator's does by 0.36): nothing more, the bonus is given once.
    //
    // No hit at the Alembic has been recorded yet, so "the pump before the sparkle" rests on the misses, the wiki and
    // the bellows (fully open 0.47 s before the sparkle showed). If taps on the cue set the bar back when the sparkle
    // shows (not 0.6 s after), the cue is still too late or the window is another pump earlier: change LEAD_MS.
    class AlembicClock {
        companion object {
            const val LEAD_MS = 900L                 // the cue comes this long before the sparkle is due
            const val SECOND_CHANCE_MS = 4_130L      // from the look that sees the bar go back to the next sparkle
            const val TAP_MS = 400L                  // the white frame stays this long from the cue (a tap that
                                                     // starts any later than that is likely too late)
            private const val TICK_MS = 600L
            private const val MOVED = 0.004          // the bar moved (half a pixel's worth on the owner's phone)
            private const val JUMP = 0.08            // moved this far at once: a hit (forward) or a mistimed tap (back)
            private const val TOO_LATE_MS = 200L     // first seen with the cue's time this long gone: no cue
        }

        private var fill = -1.0          // the bar's fill as last read, and when it last moved
        private var movedAt = 0L
        private var setBackAt = -1L      // when the bar last went back (-1 = it hasn't)
        private var hit = false          // the bar jumped forward: the moment was hit
        private var cued = false         // the cue for the sparkle now awaited was given (or its time had gone)
        private var cueAt = -1L          // when the last cue was given (-1 = none to show)

        // Seconds until the cue (null = none coming: given already, or the moment was hit)
        var secondsLeft: Double? = null
            private set
        var buzzNow = false              // true on the one call the cue is given: buzz
            private set
        var tapNow = false               // the cue was given less than TAP_MS ago: show the white frame
            private set

        // Milliseconds until the cue, for a timer between two looks (-1 = none coming)
        val cueInMs: Long get() = secondsLeft?.let { kotlin.math.ceil(it * 1000).toLong() } ?: -1L

        fun reset() {
            fill = -1.0; setBackAt = -1; hit = false; cued = false; cueAt = -1
            secondsLeft = null; buzzNow = false; tapNow = false
        }

        // A look: the time and the bar's fill
        fun look(now: Long, barFill: Double) {
            if (fill >= 0 && barFill > fill + JUMP) hit = true
            if (fill >= 0 && barFill < fill - JUMP) { setBackAt = now; cued = false; cueAt = -1 }
            if (fill < 0 || abs(barFill - fill) > MOVED) { fill = barFill; movedAt = now }
            at(now)
        }

        // The time alone, between two looks (the tool's timer, so the cue isn't up to a look late)
        fun at(now: Long) {
            buzzNow = false
            if (fill < 0) return
            val left: Double? = when {
                hit -> null
                setBackAt >= 0 -> (setBackAt + SECOND_CHANCE_MS - LEAD_MS - now) / 1000.0
                else -> {
                    val carried = fill + FILL_PER_SECOND * minOf(TICK_MS, now - movedAt) / 1000.0
                    (SPARKLE_FILL - carried) / FILL_PER_SECOND - LEAD_MS / 1000.0
                }
            }
            if (left != null && left <= 0.001 && !cued) {
                cued = true
                if (left * 1000 > -TOO_LATE_MS) { buzzNow = true; cueAt = now }
            }
            secondsLeft = if (left != null && left > 0.001 && !cued) left else null
            tapNow = !hit && cueAt >= 0 && now - cueAt < TAP_MS
        }
    }

    // What the tool should do after a look
    class Seen(
        val working: Boolean,       // a station is working on a potion (its bar is on screen): look fast (FAST_MS)
        val tapNow: Boolean,        // the quick-time moment is on: show the signal
        val started: Boolean,       // true on the one look where the moment began: buzz once
        val finished: Station?      // set on the one look where a station's work ended with a full bar: the station
                                    // it most likely was (see Watch)
    )

    // Follows one station's work from look to look. Make one when the tool starts; call `look` after every look
    // with the time, the bar (or null) and the green count round it; call `reset` when the looking stops.
    //
    // The moment starts when the green appears (at least GREEN_ON pixels, after a look in this bar's time with
    // almost none: something green that was there all along isn't a new sparkle) and lasts while it's there plus
    // HOLD_MS. Measured at 60 frames a second: the sparkle is green for 0.30 s, then pink for 0.30 s (together the
    // game's tick), then turquoise; the bubbles are green, pink and turquoise together for 0.7 to 1.0 s. The moment
    // is followed for "green plus a quarter second" (that tells the stations apart, below), but `tapNow` only for its
    // first TAP_MS: after that a tap comes too late and sets the bar back. (For the Alembic even the sparkle's start
    // is too late: its signal comes from AlembicClock instead, when the tool knows the station is the Alembic.)
    //
    // `finished` tells the stations apart by that: green for under 0.45 s = Alembic, longer = Agitator, none in a
    // whole 9 seconds = Retort (right for all 12 station uses in play_2.mp4).
    class Watch(scale: Double) {

        companion object {
            const val FAST_MS = 83L          // look this often while `working` (about 12 times a second)
            private const val HOLD_MS = 250L
            // The signal goes this long after the moment began, even if the bubbles are still there: an Agitator tap
            // counted when the finger came down up to 0.58-0.69 s after the frame showed (3 hits in play_6, play_7
            // and play_8) and set the bar back at 1.1 s (play_6 at 32.9 s)
            private const val TAP_MS = 600L
            private const val LONGEST_MS = 1300L     // no moment lasts longer than the Agitator's two ticks
            private const val BAR_GONE_MS = 400L     // the bar missing this long = the station stopped
            private const val FULL = 0.8             // the bar was last seen at least this full = it finished
            private const val SPARKLE_MS = 450L
            // The owner's phone's scale, where the pixel counts were measured
            private const val MEASURED_AT = 2.115
        }

        // Pixel counts measured on the owner's phone (weakest moment 41, at the furthest zoom used; the most
        // without one 16), for other screens in proportion to their area
        private val greenOn = (25 * scale * scale / (MEASURED_AT * MEASURED_AT)).roundToInt().coerceAtLeast(4)
        private val greenOff = (12 * scale * scale / (MEASURED_AT * MEASURED_AT)).roundToInt().coerceAtLeast(2)

        private var barSince = -1L      // when this bar was first seen (-1 = no station working)
        private var barLast = 0L        // when it was last seen
        private var fill = 0.0          // how full it was then
        private var calm = false        // a look in this bar's time had almost no green
        private var momentSince = -1L   // when the moment began (-1 = none on)
        private var greenLast = 0L      // when the green was last seen
        private var longestGreen = 0L   // the longest the green stayed in one moment of this bar's time

        fun reset() {
            barSince = -1; momentSince = -1; calm = false; longestGreen = 0
        }

        // `backInBag` = the bag shows the potion this station was working on back in it, finished (the tool checks
        // that). The game hands it back on the same tick the bar goes (play_2 to play_10, read 10 times a second), so
        // then a look with no bar is the end at once, without waiting BAR_GONE_MS: the arrow to the next station can
        // show 0.4 s sooner. A bar only hidden for a moment can't have its potion back in the bag.
        fun look(now: Long, bar: Bar?, green: Int, backInBag: Boolean = false): Seen {
            var finished: Station? = null
            if (bar == null) {
                if (barSince >= 0 && (now - barLast > BAR_GONE_MS || (backInBag && fill >= FULL))) {
                    if (fill >= FULL) finished = when {
                        longestGreen == 0L -> Station.RETORT
                        longestGreen < SPARKLE_MS -> Station.ALEMBIC
                        else -> Station.AGITATOR
                    }
                    reset()
                }
                // The bar hidden for a moment: the signal stays as it was, for at most its hold
                val on = momentSince >= 0 && now - greenLast <= HOLD_MS && now - momentSince <= LONGEST_MS
                val tap = on && now - momentSince <= TAP_MS
                if (!on) momentSince = -1
                return Seen(barSince >= 0, tap, false, finished)
            }

            if (barSince < 0) { barSince = now; calm = false; longestGreen = 0; momentSince = -1 }
            barLast = now
            fill = bar.fill

            var started = false
            if (momentSince < 0) {
                if (green < greenOff) calm = true
                else if (green >= greenOn && calm) {
                    momentSince = now; greenLast = now; started = true; calm = false
                    longestGreen = maxOf(longestGreen, 1L)
                }
            } else {
                if (green >= greenOff) {
                    greenLast = now
                    longestGreen = maxOf(longestGreen, now - momentSince)
                }
                if (now - greenLast > HOLD_MS || now - momentSince > LONGEST_MS) {
                    momentSince = -1
                    calm = green < greenOff
                }
            }
            return Seen(true, momentSince >= 0 && now - momentSince <= TAP_MS, started, null)
        }
    }

    // ---------------------------------------------------------------------------------------------------------
    // 3. Which orders are done, and the next station
    // ---------------------------------------------------------------------------------------------------------

    // An order: the potion's name as the box writes it, and its station
    class Wanted(val potion: String, val station: Station)

    // A potion in the bag: its name, and whether a station has finished it (in the bag's pictures a finished
    // potion has a brown cork and paler glass; an unfinished one an open grey neck)
    class Carried(val potion: String, val finished: Boolean)

    // What to show: which of the three orders are done ("done!" instead of the recipe), and the order and station
    // to go to next (null: nothing in the bag needs a station, so mix a potion or deliver)
    class Plan(val done: List<Boolean>, val nextOrder: Int?, val nextStation: Station?)

    // Decides it from the orders (top to bottom, null = unread), the bag's potions (in the bag's order, left to
    // right then down) and `used`: the stations Watch reported `finished` since these orders appeared, oldest
    // first (empty is fine).
    //
    // Done: an order is done when the bag holds a finished potion for it. The bag can't say which station finished
    // a potion, so when two orders want the same potion at different stations and only one is finished, `used`
    // settles it (the order whose station was used); with nothing to go by, the upper order counts as done, since
    // that's the one the tool sends you to first.
    //
    // `made`: the potions the tool saw a station finish since these orders appeared, with the station (the plugin's
    // rule: an order is only done when its own station made its potion; a potion finished at another station
    // doesn't count, and the game won't take it for that order). A finished potion in the bag with no such record
    // (the tool was started after it was made) counts as before.
    //
    // Next (as the plugin's): the first unfinished potion in the bag that an order which isn't done still wants;
    // of two such orders for the same potion, the upper one. With `byOrder` (right after a station finished, as
    // the plugin's tryHighlightNextStation does) the other way round: the first order from the top that isn't done
    // and whose unfinished potion is in the bag.
    class Made(val potion: String, val station: Station)

    fun plan(orders: List<Wanted?>, bag: List<Carried>, used: List<Station> = emptyList(),
             made: List<Made> = emptyList(), byOrder: Boolean = false): Plan {
        val done = BooleanArray(orders.size)
        val spare = used.toMutableList()
        val potions = orders.filterNotNull().map { it.potion }.distinct()

        // First the potions the tool saw made: each one done for the first order with that potion at that station,
        // or for none if no order wants it there (made at the wrong station). The finished potions in the bag
        // limit it (one dropped or banked no longer counts).
        val left = HashMap<String, Int>()
        for (potion in potions) left[potion] = bag.count { it.finished && it.potion == potion }
        for (m in made) {
            val n = left[m.potion] ?: continue
            if (n <= 0) continue
            left[m.potion] = n - 1
            val i = orders.indices.firstOrNull { !done[it] && orders[it]?.potion == m.potion && orders[it]?.station == m.station }
            if (i != null) done[i] = true
        }

        // The rest (finished potions the tool didn't see made): first the potions with no doubt (as many finished
        // as orders still open), which also explains their stations' use
        val unsure = ArrayList<String>()
        for (potion in potions) {
            val lines = orders.indices.filter { orders[it]?.potion == potion && !done[it] }
            val finished = left[potion] ?: 0
            if (lines.isEmpty() || finished <= 0) continue
            if (finished >= lines.size) for (i in lines) { done[i] = true; spare.remove(orders[i]!!.station) }
            else unsure.add(potion)
        }
        // Then the ones where fewer are finished than ordered: by the stations used, then from the top
        for (potion in unsure) {
            val lines = orders.indices.filter { orders[it]?.potion == potion && !done[it] }
            var n = left[potion] ?: 0
            for (i in lines) if (n > 0 && spare.remove(orders[i]!!.station)) { done[i] = true; n-- }
            for (i in lines) if (n > 0 && !done[i]) { done[i] = true; n-- }
        }

        if (byOrder) {
            for (i in orders.indices) {
                val o = orders[i] ?: continue
                if (!done[i] && bag.any { !it.finished && it.potion == o.potion }) return Plan(done.toList(), i, o.station)
            }
            return Plan(done.toList(), null, null)
        }
        for (carried in bag) {
            if (carried.finished) continue
            val i = orders.indices.firstOrNull { !done[it] && orders[it]?.potion == carried.potion } ?: continue
            return Plan(done.toList(), i, orders[i]!!.station)
        }
        return Plan(done.toList(), null, null)
    }

    // The station a potion was just handed to. The potion leaves the bag up to 0.4 s before the station's bar shows
    // (35 station uses in play_2 to play_9, looked at 10 times a second: the same look 20 times, 0.1 to 0.4 s
    // earlier 15 times), and in that gap the plan already points at the next potion's station, or at none. So when an
    // unfinished potion leaves the bag, the station the plan pointed at just before is remembered for HOLD_MS: a
    // bar that shows in that time is that station's.
    class Sent {
        companion object { const val HOLD_MS = 1_200L }

        private var next: Station? = null     // the plan's next station, as last told
        private var nextPotion: String? = null // ...and the potion it was for
        private var unfinished = -1           // how many unfinished potions the bag had then (-1 = bag not seen)
        private var held: Station? = null
        private var heldPotion: String? = null
        private var heldAt = 0L

        // After every plan: its next station and potion, and how many unfinished potions the bag holds
        fun planned(now: Long, nextStation: Station?, unfinishedInBag: Int, potion: String? = null) {
            if (unfinished >= 0 && unfinishedInBag < unfinished && next != null) {
                held = next; heldPotion = nextPotion; heldAt = now
            }
            next = nextStation
            nextPotion = potion
            unfinished = unfinishedInBag
        }

        // The bag can't be seen now (inventory closed, no order box)
        fun unseen() { next = null; nextPotion = null; unfinished = -1 }

        // A station has started work on the potion: the one that left the bag is accounted for. (Without this, when
        // the potion left on the same look the bar first showed, the bag's count from before was kept all through
        // the station's work, and the first plan after it took the missing potion for one just handed over: the
        // arrow to the next station was held back HOLD_MS for nothing. play_10.mp4 at 64 s: 1.2 s late.) What was
        // remembered as just sent stays.
        fun stationStarted() { next = null; nextPotion = null; unfinished = -1 }

        // A potion left the bag less than HOLD_MS ago: the station it was sent to (null = none did)
        fun justSent(now: Long): Station? = held?.takeIf { now - heldAt <= HOLD_MS }

        // ...and that potion's name (null = none, or not known)
        fun justSentPotion(now: Long): String? = heldPotion?.takeIf { now - heldAt <= HOLD_MS }

        fun reset() { unseen(); held = null; heldPotion = null }
    }

    // ---------------------------------------------------------------------------------------------------------
    // 4. Where the stations are on the screen (for the arrow over the station to use next)
    // ---------------------------------------------------------------------------------------------------------

    // The stations stand round the lever table, so once the row of funnels is found (MixologyLevers) a station's
    // place on the screen can be worked out. A set distance from the funnels isn't enough: the game's camera
    // makes near things bigger, so the Agitator is 3.9 funnel spacings from the row seen from one side of the room
    // and 6.9 from the other. So it's worked out the way the game draws it: the camera looks at the character from
    // a set distance, turned as the compass says and tilted down by some angle, and things shrink with distance.
    //
    // Everything but the tilt is known: the compass and the slant of the row of funnels give the turn (`place`),
    // the funnels' spacing the zoom, their place on the screen where the camera is looking. The tilt can't be seen
    // from the funnels (they're three points in a line), and the owner's own recordings have it anywhere from
    // about 27° to 67°. It's read straight from how the stations look (MixologyStationLooks: the Alembic's bulb,
    // the Retort's pot and the Agitator's left beaker), so the arrow needn't wait for a station to work. Failing
    // that, while a station works its progress bar is on the screen, and there's just one tilt that puts that
    // station's bar where it is (`Tilt`): that one is kept until the camera is turned or zoomed (the fingers that
    // do that tilt it too).
    //
    // Measured on every recording (16 November 2026; .scratch/mixology/spec.md, the last note): with the tilt read
    // from the stations' looks, a working station's bar is where this says to within 0.07 of a funnel spacing
    // typically and 0.25 at the worst, facing north or south (717 looks with a bar). Three things made it that
    // close, each measured on the owner's footage of the camera turned all the way round at each station
    // (stations_360.mp4): the turn taken from the row's slant and not from the compass alone; the row measured by
    // its rings, with a full funnel's middle a set height above its ring; and the camera's distance refitted over
    // tilts from 27° to 67°.
    //
    // The numbers: each station's bar, from the middle ring on the lever table (the grey rings the funnels stand
    // in), in funnel spacings: east, north and up (facing north, east is to the right on the screen). The funnels
    // are one game square apart. Fitted on every recording at once, facing north and south together (680 views,
    // 16 November 2026).
    private val STATION_AT: Map<Station, DoubleArray> = mapOf(
        Station.ALEMBIC to doubleArrayOf(-3.367, 3.532, 0.369),
        Station.AGITATOR to doubleArrayOf(0.027, 6.930, 0.339),
        Station.RETORT to doubleArrayOf(3.329, 3.565, 0.386)
    )
    // How far the camera is from what it looks at, in game squares: further when it's tilted more. The game's own
    // rule is 600 + 3 x the tilt in its units (a square being 128); what fits the owner's phone, over tilts from
    // 27 to 67 degrees, is nearly the same start and a faster climb. With the game's own 3, a station seen with
    // the camera tilted low came out a twentieth to a tenth too far from the table sideways (stations_360.mp4).
    // Then: how high the rings are above the point the camera looks at, and how far above its ring the middle of
    // a full funnel's paste is. That last one was measured directly: the camera left alone while the funnels went
    // from full to empty, 67 times in the recordings, and the tilt read from the stations must come out the same
    // before and after.
    private fun cameraDistance(tilt: Double) = (606.7 + 5.361 * tilt * 2048.0 / 360.0) / 128.0
    private const val RING_HEIGHT = 0.159
    private const val FUNNEL_ABOVE_RING = 0.435
    const val TILT_MIN = 25.0
    const val TILT_MAX = 68.0

    // A place on the screen
    class Spot(val x: Double, val y: Double)

    // Where a station's progress bar would be (its middle), for the row of funnels found, the compass's reading
    // (MixologyLevers.Compass.angle) and the camera's tilt in degrees, on a screen `width` by `height` pixels
    fun stationSpot(row: MixologyLevers.Levers, compassAngle: Double, tilt: Double, station: Station,
                    width: Int, height: Int): Spot {
        val at = place(row, compassAngle, tilt, STATION_AT.getValue(station), width, height)
        return Spot(at[0], at[1])
    }

    // The same for any place in the room, given like the stations' (east, north and up from the middle funnel, in
    // funnel spacings): where it is on the screen (x, y), and how many screen pixels one spacing is there (things
    // further from the camera are drawn smaller). For MixologyStationLooks, which finds the stations themselves.
    fun roomSpot(row: MixologyLevers.Levers, compassAngle: Double, tilt: Double, at: DoubleArray,
                 width: Int, height: Int): DoubleArray {
        val found = place(row, compassAngle, tilt, at, width, height)
        return doubleArrayOf(found[0], found[1], found[3])
    }

    // How far the camera is zoomed in, for the same things: a number that stays the same wherever the character
    // walks, and changes only when the player zooms
    fun zoomOf(row: MixologyLevers.Levers, compassAngle: Double, tilt: Double, width: Int, height: Int): Double =
        place(row, compassAngle, tilt, NOWHERE, width, height)[2]

    // The working-out for all three: the place on the screen (x, y) of `s` (east, north, up from the middle funnel),
    // the zoom, and the size of one spacing there in screen pixels
    private fun place(row: MixologyLevers.Levers, compassAngle: Double, tilt: Double, s: DoubleArray,
                      width: Int, height: Int): DoubleArray {
        // The camera's turn. The compass gives it only to within a degree or two (its rim marks are a few pixels
        // long), and a degree is a tenth of a funnel spacing at the Agitator. The row of funnels says it better: it
        // runs east-west, so how much it slants on the screen shows how far the camera is turned from north or
        // south. So: start from the compass, then turn until the row would slant as it does (never more than
        // TURN_SLACK from the compass). Measured on 420 views where both the Alembic's bulb and the Retort's pot
        // were found (16 November 2026): the turn that fitted both was 1.0° from the compass's typically and
        // 0.4° from the row's.
        val slant = kotlin.math.atan2(row.funnels[2].y - row.funnels[0].y, row.funnels[2].x - row.funnels[0].x)
        val cam = DoubleArray(14)
        var angle = compassAngle
        camera(row, angle, tilt, width, height, cam)
        repeat(2) {
            val now = cam[12]
            camera(row, angle + 1.0, tilt, width, height, cam)
            val perDegree = cam[12] - now
            if (kotlin.math.abs(perDegree) > 1e-6) {
                angle = (angle + (slant - now) / perDegree).coerceIn(compassAngle - TURN_SLACK, compassAngle + TURN_SLACK)
            }
            camera(row, angle, tilt, width, height, cam)
        }
        val sRight = cam[0] + s[0] * cam[4] + s[1] * cam[5]
        val high = s[2] - cam[13]    // (the places are given from the rings; this row may be measured from higher up)
        val sUp = cam[1] + s[0] * cam[6] + s[1] * cam[7] + high * cam[8]
        val sAhead = cam[2] + s[0] * cam[9] + s[1] * cam[10] + high * cam[11]
        val zoom = cam[3]
        return doubleArrayOf(width / 2.0 + zoom * sRight / sAhead, height / 2.0 - zoom * sUp / sAhead, zoom, zoom / sAhead)
    }
    private val NOWHERE = doubleArrayOf(0.0, 0.0, 0.0)

    // The camera's view when the lever table isn't on the screen (MixologyStationLooks.readWithoutTable works it
    // out from two or three stations found by their looks): where the table's middle ring would be on the screen
    // (it may be off it), how many screen pixels its funnels would be apart, the camera's turn as the compass
    // gives it (`angle`), and its tilt, both in degrees
    class View(val midX: Double, val midY: Double, val spacing: Double, val angle: Double, val tilt: Double)

    // Where a place in the room (east, north and up from the middle ring, in funnel spacings) is on the screen in
    // that view (x, y), and how many screen pixels one spacing is there. Like `roomSpot`, with the turn given
    // instead of read from the row's slant.
    fun viewSpot(view: View, at: DoubleArray, width: Int, height: Int): DoubleArray {
        val cam = DoubleArray(14)
        cameraAt(view.midX, view.midY, view.spacing, 0.0, view.angle, view.tilt, width, height, cam)
        val sRight = cam[0] + at[0] * cam[4] + at[1] * cam[5]
        val sUp = cam[1] + at[0] * cam[6] + at[1] * cam[7] + at[2] * cam[8]
        val sAhead = cam[2] + at[0] * cam[9] + at[1] * cam[10] + at[2] * cam[11]
        return doubleArrayOf(width / 2.0 + cam[3] * sRight / sAhead, height / 2.0 - cam[3] * sUp / sAhead, cam[3] / sAhead)
    }

    // Where a station's progress bar would be in that view (its middle)
    fun viewStationSpot(view: View, station: Station, width: Int, height: Int): Spot {
        val at = viewSpot(view, STATION_AT.getValue(station), width, height)
        return Spot(at[0], at[1])
    }
    // How far the camera's turn may be taken from the compass's reading, in degrees (the compass was never further
    // than 2.6° from the turn that fitted both stations)
    private const val TURN_SLACK = 3.0

    // The camera for one turn (`angle`, as the compass gives it) and tilt, from the row of funnels on the screen.
    // Fills `cam`: where the middle of the row is from the camera (0 right, 1 up, 2 ahead), the zoom (3), the
    // camera's three directions each in east, north, up (4-5 to its right, which has no up part; 6-8 up the
    // screen; 9-11 straight ahead), how the row of funnels would slant on the screen for this turn (12, like atan2
    // of down over right, from the left funnel to the right one), and how far above the rings the row was
    // measured (13).
    private fun camera(row: MixologyLevers.Levers, angle: Double, tilt: Double, width: Int, height: Int, cam: DoubleArray) {
        // What the row is measured by: the funnels' own middles when they're full of paste, or, when the row was
        // found by its rings, the rings' middles. A ring lies on the table, FUNNEL_ABOVE_RING lower than the
        // middle of a full funnel's paste. (MixologyLevers puts an empty funnel's middle a set distance straight
        // up the screen from its ring, which is right for drawing the dots but isn't a place in the room: seen
        // from the north and from the south it would be two different places, half a square apart.) Every place
        // in the room is given from the middle ring.
        val rings = row.rings
        val a0x = rings?.get(0)?.x ?: row.funnels[0].x
        val a0y = rings?.get(0)?.y ?: row.funnels[0].y
        val c0x = rings?.get(2)?.x ?: row.funnels[2].x
        val c0y = rings?.get(2)?.y ?: row.funnels[2].y
        cameraAt((a0x + c0x) / 2, (a0y + c0y) / 2, kotlin.math.hypot(c0x - a0x, c0y - a0y) / 2,
            if (rings != null) 0.0 else FUNNEL_ABOVE_RING, angle, tilt, width, height, cam)
    }

    // The same from numbers: the middle of the row on the screen (`midX`, `midY`), its spacing in screen pixels,
    // and how far above the rings it was measured (`lift`)
    private fun cameraAt(midX: Double, midY: Double, spacing: Double, lift: Double, angle: Double, tilt: Double,
                         width: Int, height: Int, cam: DoubleArray) {
        val a = Math.toRadians(angle)
        val sinTurn = -kotlin.math.cos(a)    // the turn from north: the compass's north mark points up facing north
        val cosTurn = -kotlin.math.sin(a)
        val t = Math.toRadians(tilt)
        val sinTilt = kotlin.math.sin(t)
        val cosTilt = kotlin.math.cos(t)
        // The camera's three directions, each in east, north, up: to its right, up the screen, and straight ahead
        val rx = cosTurn; val ry = -sinTurn
        val ux = sinTurn * sinTilt; val uy = cosTurn * sinTilt; val uz = cosTilt
        val vx = sinTurn * cosTilt; val vy = cosTurn * cosTilt; val vz = -sinTilt
        val cx = width / 2.0
        val cy = height / 2.0
        val rowHeight = RING_HEIGHT + lift
        val distance = cameraDistance(tilt)
        // Where the middle funnel is from the camera (right, up, ahead) and the zoom, from the row on the screen.
        // `k` = how long one spacing along the row looks from here; a few rounds settle it.
        var k = kotlin.math.hypot(rx, ux)
        var right = 0.0; var up = 0.0; var ahead = 1.0; var zoom = 1.0
        var lx = 0.0; var ly = 0.0; var hx = 1.0; var hy = 0.0
        repeat(4) {
            right = (midX - cx) * k / spacing
            up = -(midY - cy) * k / spacing
            ahead = distance + (up * cosTilt - rowHeight) / sinTilt
            zoom = ahead * spacing / k
            lx = cx + zoom * (right - rx) / (ahead - vx)
            ly = cy - zoom * (up - ux) / (ahead - vx)
            hx = cx + zoom * (right + rx) / (ahead + vx)
            hy = cy - zoom * (up + ux) / (ahead + vx)
            k *= spacing / (kotlin.math.hypot(hx - lx, hy - ly) / 2)
        }
        cam[0] = right; cam[1] = up; cam[2] = ahead; cam[3] = zoom
        cam[4] = rx; cam[5] = ry
        cam[6] = ux; cam[7] = uy; cam[8] = uz
        cam[9] = vx; cam[10] = vy; cam[11] = vz
        // (facing south, east is to the left on the screen: the row is still read from its left funnel)
        cam[13] = lift
        cam[12] = if (hx >= lx) kotlin.math.atan2(hy - ly, hx - lx) else kotlin.math.atan2(ly - hy, lx - hx)
    }

    // The tilt that puts `station`'s bar nearest to where a bar is on the screen (`barX`, `barY` = its middle), and
    // how far off it still is, in funnel spacings
    private fun bestTilt(row: MixologyLevers.Levers, compassAngle: Double, station: Station, barX: Double, barY: Double,
                         width: Int, height: Int): DoubleArray {
        var best = TILT_MIN
        var bestOff = Double.MAX_VALUE
        // every degree first, then every twentieth of a degree round the best
        var step = 1.0
        var from = TILT_MIN
        var to = TILT_MAX
        repeat(2) {
            var tilt = from
            while (tilt <= to + 1e-9) {
                val spot = stationSpot(row, compassAngle, tilt, station, width, height)
                val off = kotlin.math.hypot(spot.x - barX, spot.y - barY) / row.spacing
                if (off < bestOff) { bestOff = off; best = tilt }
                tilt += step
            }
            from = (best - 1.0).coerceAtLeast(TILT_MIN)
            to = (best + 1.0).coerceAtMost(TILT_MAX)
            step = 0.05
        }
        return doubleArrayOf(best, bestOff)
    }

    // What a bar on the screen says: which station it's over and the camera's tilt
    class Learned(val station: Station, val tilt: Double)

    // Reads the tilt from a working station's bar: the station and tilt that put the bar where it is. Null unless
    // one station fits within FITS of a spacing and the other two are both out by FAR or more (the stations are
    // three spacings or more apart, so a real bar fits just one: all 712 bars it named in the recordings were named
    // right; with the table at the very bottom of the screen it sometimes can't tell and says nothing).
    fun learnTilt(row: MixologyLevers.Levers, compassAngle: Double, bar: Bar, width: Int, height: Int): Learned? {
        val barX = (bar.left + bar.right) / 2.0
        val barY = (bar.top + bar.bottom) / 2.0
        var best: Station? = null
        var bestTilt = 0.0
        var bestOff = Double.MAX_VALUE
        var nextOff = Double.MAX_VALUE
        for (station in Station.entries) {
            val fit = bestTilt(row, compassAngle, station, barX, barY, width, height)
            if (fit[1] < bestOff) { nextOff = bestOff; bestOff = fit[1]; bestTilt = fit[0]; best = station }
            else if (fit[1] < nextOff) nextOff = fit[1]
        }
        // (a tilt at either end of what's tried is no answer, just the end)
        if (best == null || bestOff > FITS || nextOff < FAR || bestTilt <= TILT_MIN + 0.01 || bestTilt >= TILT_MAX - 0.01) return null
        return Learned(best, bestTilt)
    }
    private const val FITS = 0.25
    private const val FAR = 1.0

    // The camera's tilt, as learned from the stations' bars, and the rules for trusting it:
    //  - it's learned when two looks running read the same station and nearly the same tilt (AGREE) from a bar,
    //    with the row of funnels found and the compass read from its rim marks, facing south or north;
    //  - it's forgotten as soon as the compass is more than TURNED from where it was when learned, or the zoom is
    //    more than ZOOMED from what it was: fingers that turn or zoom the camera tilt it too (play_5.mp4 between 190
    //    and 200 s: zoomed in by a sixth, the compass exactly where it was, and the tilt went from 67° to 48°, which
    //    would have put the arrow 0.8 to 1.4 spacings off);
    //  - with no tilt, or the compass not read exactly, there's no place for any station (never a guess).
    // What's left: tilting the camera straight up or down without turning or zooming it at all isn't noticed until
    // the next station works.
    class Tilt {
        companion object {
            const val TURNED = 0.25    // degrees (the compass reads the same to a tenth of a degree while untouched)
            const val ZOOMED = 0.10    // the zoom this much bigger or smaller than when the tilt was learned
            const val AGREE = 2.0      // degrees between two looks' tilts
            const val STEADY = 1.0     // a tilt read from a station's look within this of the one known changes nothing
        }

        var degrees: Double? = null    // the tilt (null = not known)
            private set
        private var compassThen = 0.0
        private var zoomThen = 0.0
        private var before: Learned? = null    // what the look before read, and the compass then
        private var beforeCompass = 0.0
        private var beforeSeen: Double? = null // the same for a tilt read from a station's look (`seen`)
        private var beforeSeenCompass = 0.0

        fun forget() { degrees = null; before = null; beforeSeen = null }

        // Every look that reads the compass (null = not found: nothing is known about a turn, so nothing changes)
        fun compass(angle: Double?) {
            if (angle != null && degrees != null && turn(angle, compassThen) > TURNED) forget()
        }

        // A look with a working station's bar and the row of funnels both on screen. Returns the station the bar
        // is over when it's sure (two looks agree), else null.
        fun bar(row: MixologyLevers.Levers, compass: MixologyLevers.Compass, bar: Bar, width: Int, height: Int): Station? {
            if (!usable(compass)) { before = null; return null }
            val now = learnTilt(row, compass.angle, bar, width, height)
            val last = before
            before = now
            val lastCompass = beforeCompass
            beforeCompass = compass.angle
            if (now == null || last == null || now.station != last.station || kotlin.math.abs(now.tilt - last.tilt) > AGREE ||
                turn(compass.angle, lastCompass) > TURNED) return null
            degrees = now.tilt
            compassThen = compass.angle
            zoomThen = zoomOf(row, compass.angle, now.tilt, width, height)
            return now.station
        }

        // A look that read the tilt from how a station looks (MixologyStationLooks), with no bar needed; `tiltNow`
        // null = this look couldn't read it (nothing is forgotten: the station may just be behind something). The
        // same rule as for a bar: two looks running must agree. A tilt already known is only changed when the new
        // one differs by more than STEADY or the camera was turned or zoomed: the reading wavers by a few tenths
        // of a degree from look to look, and the arrow shouldn't waver with it.
        fun seen(row: MixologyLevers.Levers, compass: MixologyLevers.Compass, tiltNow: Double?, width: Int, height: Int) {
            if (tiltNow == null || !usable(compass)) { beforeSeen = null; return }
            val last = beforeSeen
            val lastCompass = beforeSeenCompass
            beforeSeen = tiltNow
            beforeSeenCompass = compass.angle
            if (last == null || abs(tiltNow - last) > AGREE || turn(compass.angle, lastCompass) > TURNED) return
            val known = degrees
            if (known != null && abs(tiltNow - known) <= STEADY && turn(compass.angle, compassThen) <= TURNED &&
                abs(zoomOf(row, compass.angle, known, width, height) / zoomThen - 1) <= ZOOMED) return
            degrees = tiltNow
            compassThen = compass.angle
            zoomThen = zoomOf(row, compass.angle, tiltNow, width, height)
        }

        // Where `station` is on the screen now, or null if that can't be said for sure
        fun spot(row: MixologyLevers.Levers, compass: MixologyLevers.Compass?, station: Station, width: Int, height: Int): Spot? {
            val tilt = degrees ?: return null
            if (compass == null || !usable(compass) || turn(compass.angle, compassThen) > TURNED) return null
            // Zoomed since: the two fingers that zoom can tilt the camera too, so the tilt is no longer sure
            val zoom = zoomOf(row, compass.angle, tilt, width, height)
            if (kotlin.math.abs(zoom / zoomThen - 1) > ZOOMED) { forget(); return null }
            return stationSpot(row, compass.angle, tilt, station, width, height)
        }

        private fun usable(compass: MixologyLevers.Compass) =
            compass.exact && (MixologyLevers.facingSouth(compass.angle) || MixologyLevers.facingNorth(compass.angle))

        private fun turn(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }
    }

    // The arrow at a station: its tip and its size, in screen pixels. It points down at the station from above,
    // or (`up`) up at it from below, for a station so near the top of the screen that there's no room above it.
    class Arrow(val tipX: Float, val tipY: Float, val width: Float, val height: Float, val up: Boolean = false) {
        val left get() = tipX - width / 2
        val right get() = tipX + width / 2
        val top get() = if (up) tipY else tipY - height
        val bottom get() = if (up) tipY + height else tipY

        // Its outline's seven corners, x then y, starting at the tip and going round: a wide head on a short
        // shaft. The dark edge drawn round it (EDGE of the width) stays inside `width` too.
        fun outline(): FloatArray {
            val edge = width * ARROW_EDGE / 2
            val half = width / 2 - edge
            val shaft = width * 0.2f
            val way = if (up) -1f else 1f    // which way the arrow's body goes from its tip: up the screen, or down
            val neck = tipY - way * height * 0.5f
            val end = tipY - way * (height - edge)
            return floatArrayOf(tipX, tipY - way * edge, tipX - half, neck, tipX - shaft, neck, tipX - shaft, end,
                tipX + shaft, end, tipX + shaft, neck, tipX + half, neck)
        }
    }

    // The arrow's yellow. Not the progress bar's yellow (218, 209, 0): this one has blue in it, and `findBar` only
    // follows colours with next to no blue (blue x 4 under red + 40). Android shows the tool's drawing 80% solid,
    // so on screen the arrow's blue is at least 0.8 x 96 = 77, times 4 = 307, and red + 40 can't pass 295: no
    // pixel of the arrow can count as part of a bar, whatever is behind it (`theArrowsYellowIsNeverABarsColour`).
    // It isn't a paste colour, the stations' pure green, the compass's red or blue, or near white either.
    const val ARROW_COLOUR = 0xFFD660
    const val ARROW_EDGE = 0.09f     // the dark edge's width, as a part of the arrow's width

    // Whether `findBar` would follow this colour as part of a progress bar (for the tests of the tool's own drawing)
    fun barColoured(rgb: Int): Boolean = yellow(rgb) || olive(rgb) || barColour(rgb)

    // The arrow's size in game pixels (`scale` screen pixels each), and how far its tip stays above the place the
    // station's bar will be. It's no wider than ARROW_WIDTH so that no row of it can ever be as long as a progress
    // bar (50 game pixels, 43.6 at the least: `findBar`), whatever its colour came out as; and it stays above the
    // bar's place, so a bar that starts under a still-showing arrow isn't covered.
    const val ARROW_WIDTH = 24.0
    const val ARROW_HEIGHT = 34.0
    private const val ARROW_LIFT = 0.30      // of a funnel spacing, plus
    private const val ARROW_LIFT_MORE = 6.0  // game pixels (the bar's own height and a little)

    fun arrowOver(spot: Spot, spacing: Double, scale: Double) = Arrow(spot.x.toFloat(),
        (spot.y - ARROW_LIFT * spacing - ARROW_LIFT_MORE * scale).toFloat(), (ARROW_WIDTH * scale).toFloat(),
        (ARROW_HEIGHT * scale).toFloat())

    // The same arrow the other way up, below the station and pointing up at it: for a station at the top of the
    // screen (in the owner's games that's the Agitator, facing north: the arrow above it would be off the screen
    // on a third of the looks in play_2.mp4). Its tip stays ARROW_DROP of a funnel spacing (and the same few game
    // pixels) below the bar's place, on the station's bench: a bar that starts while it still shows isn't covered.
    private const val ARROW_DROP = 0.45
    fun arrowUnder(spot: Spot, spacing: Double, scale: Double) = Arrow(spot.x.toFloat(),
        (spot.y + ARROW_DROP * spacing + ARROW_LIFT_MORE * scale).toFloat(), (ARROW_WIDTH * scale).toFloat(),
        (ARROW_HEIGHT * scale).toFloat(), up = true)

    // The same arrow moved straight up (or, pointing up, straight down) until it's clear of `keep`: without the
    // lever table the station is placed from its own bulb, pot or beakers, which the next look must still see
    fun arrowClearOf(arrow: Arrow, keep: Area): Arrow {
        if (arrow.left >= keep.right || arrow.right <= keep.left || arrow.top >= keep.bottom || arrow.bottom <= keep.top) return arrow
        return Arrow(arrow.tipX, if (arrow.up) keep.bottom + 1 else keep.top - 1, arrow.width, arrow.height, arrow.up)
    }

    // A part of the screen the arrow keeps out of (right and bottom just outside it)
    class Area(val left: Float, val top: Float, val right: Float, val bottom: Float)

    // The game's own interface, as far as the tool knows where it is, in game pixels from things it has found (the
    // owner's phone, 2340 x 1080; other screens in proportion to `scale`):
    //  - the Potion Orders box with the tool's recipes after the names, and the buttons left of it;
    //  - the chat above the box, when it's open (the box is then lower on the screen);
    //  - the minimap with its orbs, and the XP bar beside the compass, from the compass's place;
    //  - the side panel with the tab buttons right of it and the small button left of it (`panel` = where the
    //    inventory was found, or null).
    fun interfaceAreas(box: MixologyReader.Box, compass: MixologyLevers.Compass?, panel: PanelFinder.Box?,
                       scale: Double, width: Int, height: Int): List<Area> {
        val s = scale.toFloat()
        val areas = ArrayList<Area>()
        areas.add(Area(0f, box.top - 12 * s, box.left + 205 * s, box.top + 118 * s))
        areas.add(Area(0f, 0f, box.left - 30 * s, height.toFloat()))
        if (box.top > 60 * s) areas.add(Area(0f, 0f, box.left + 430 * s, box.top.toFloat()))
        if (compass != null) {
            val x = compass.x.toFloat()
            val y = compass.y.toFloat()
            areas.add(Area(x - 78 * s, 0f, width.toFloat(), y + 168 * s))
            areas.add(Area(x - 205 * s, 0f, width.toFloat(), y + 22 * s))
        }
        if (panel != null) areas.add(Area(panel.left - 42 * s, panel.top - 6 * s, width.toFloat(), height.toFloat()))
        return areas
    }

    // The lever table as the tool needs to see it: the funnels, the rings under them with the table round them, and
    // the lever dots in front and behind. An arrow over any of that would hide the very things it's placed from
    // (zoomed right out, the stations are close enough to the table for that).
    fun tableArea(row: MixologyLevers.Levers): Area {
        val a = row.funnels[0]
        val c = row.funnels[2]
        val s = row.spacing
        return Area((minOf(a.x, c.x) - 0.9 * s).toFloat(), (minOf(a.y, c.y) - 1.0 * s).toFloat(),
            (maxOf(a.x, c.x) + 0.9 * s).toFloat(), (maxOf(a.y, c.y) + 1.6 * s).toFloat())
    }

    // Whether the arrow may be drawn: all of it on the screen (with a little room), and neither it nor the
    // station's own place inside any of `areas` (the game's interface, the tool's window)
    fun arrowClear(arrow: Arrow, spot: Spot, areas: List<Area>, width: Int, height: Int): Boolean {
        val room = 4f
        if (arrow.left < room || arrow.right > width - room || arrow.top < room || arrow.bottom > height - room ||
            spot.y < room || spot.y > height - room) return false
        for (a in areas) {
            if (arrow.left < a.right && arrow.right > a.left && arrow.top < a.bottom && arrow.bottom > a.top) return false
            if (spot.x >= a.left && spot.x < a.right && spot.y >= a.top && spot.y < a.bottom) return false
        }
        return true
    }
}

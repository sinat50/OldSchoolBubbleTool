package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.ln

// Finds the Mastering Mixology lab's three levers on the screen, for the Mastering Mixology tool's coloured dots.
// No Android parts, so it's tested on a computer (MixologyLeversTest, on the owner's screenshots and recordings).
//
// The levers are hard to see, but each sits straight in front of a glass funnel on the table, and the funnels are
// almost always full of paste: bright pink (Lye), green (Aga) or purple-blue (Mox), colours nothing else in the room
// has. So it looks for three paste-coloured funnels of the same size in a level row, evenly spaced, and puts the
// dots below them.
//
// But the funnels aren't always full: they hold the last three pastes pulled, like a queue, and all three empty when
// the potion is taken out of the mixing vessel (and they're empty at the start of a visit). That's just when the
// dots are needed, for the next pulls. So when there's no row of three full funnels it looks for what never changes:
// the three grey rings the funnels' stems stand in, each with its dark hole (or a paste-filled stem in it), in a
// level row on the sand-coloured table (`ringRow`). A row found that way gives the same dots.
//
// It works with the camera facing south, tilted high (as most players play): then the levers read Mox, Aga, Lye
// from left to right and sit in front of the funnels, below them on screen. Or facing north: then they read Lye, Aga,
// Mox and sit behind the funnels, just above them on screen. The compass tells which (`findCompass`).
object MixologyLevers {

    // How far below a funnel's middle its lever's dot goes, in funnel spacings (measured on the owner's recording:
    // the game's outline of each lever when long-pressed, levers_2.mp4 at 13-20 s)
    const val DOT_BELOW = 1.18

    // Facing north, how far above a funnel's middle its lever's dot goes, in funnel spacings. The levers are on the
    // far side of the table then, mostly hidden behind the funnels: only their tops show. Measured on north_1.mp4 at
    // 4.5, 6.5 and 8 s (funnels 101 px apart, their middles at y 597): the game outlined Lye from y 515 to 560, Aga
    // from 545 to 600 (right behind its funnel) and Mox from 520 to 560. Half a spacing up (y 546) is on all three,
    // and keeps the dots clear of the funnels' paste (it starts about a third of a spacing above the middle).
    const val DOT_ABOVE = 0.5

    // How much further sideways a dot goes when the row of funnels slants (see `Levers`)
    private const val SIDEWAYS = 1.25

    // The middle funnel hidden: two funnels as far apart, in the same direction and the same size as `last`'s outer
    // two (within a tenth of a spacing, plus the looked-at pixels' spacing for the quick look). The middle one is
    // put halfway between them, where it was.
    private fun outerPair(funnels: List<Funnel>, last: Levers, step: Int): Levers? {
        val l = last.funnels[0]
        val r = last.funnels[2]
        val wantX = r.x - l.x
        val wantY = r.y - l.y
        var bestErr = 0.1 * last.spacing + step
        var best: Levers? = null
        for (a in funnels) {
            if (a.area * 2 < l.area || l.area * 2 < a.area) continue
            for (c in funnels) {
                if (c === a || c.area * 2 < r.area || r.area * 2 < c.area) continue
                val err = hypot(c.x - a.x - wantX, c.y - a.y - wantY)
                if (err >= bestErr) continue
                bestErr = err
                val m = last.funnels[1]
                val middle = Funnel((a.x + c.x) / 2, (a.y + c.y) / 2, m.width, m.height, m.area, m.paste)
                best = Levers(listOf(a, middle, c), hypot(c.x - a.x, c.y - a.y) / 2, hidden = 1)
            }
        }
        return best
    }

    // The compass needle's direction when facing south: straight down; facing north: straight up. Allowed off by
    // SLACK either way. Measured with the compass read from its rim marks (`findCompass`), against the game's own
    // outline of each long-pressed lever: the dots were on their levers at every turn tried, facing south from 22°
    // one way to 30° the other (levers_3.mp4 at 35.5 s and 36.5-40 s; by eye still at 33° and, with the camera tilted
    // low, at 30°: levers_2.mp4 at 35.5 and 44 s) and facing north from 25° one way to 22° the other (north_3.mp4 at
    // 52-56 s and 21-24 s). 25° keeps a little room. The compass is still needed besides the row's slant
    // (`bestRow`): with the camera tilted low a turned table looks much less slanted than it is (turned 18°, slanted
    // 6°: levers_3.mp4 at 15-19 s).
    private const val SOUTH = 90.0
    private const val NORTH = -90.0
    private const val SLACK = 25.0

    // One funnel: its middle, its size (screen pixels) and its paste (null = not known: the row was found by the
    // rings, and this funnel's place worked out from its ring)
    class Funnel(val x: Double, val y: Double, val width: Int, val height: Int, val area: Int,
                 val paste: MixologyData.Paste?)

    // One of the grey rings on the table that the funnels' stems stand in: the box round it, in screen pixels
    class Ring(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val x get() = (left + right) / 2.0
        val y get() = (top + bottom) / 2.0
        val width get() = right - left
        val height get() = bottom - top
    }

    // How far below a funnel's middle its ring's middle is, in funnel spacings: straight down on the screen, however
    // the row slants (the funnels stand upright). Measured on all seven recordings, on the 400 pictures where both
    // the full funnels and the rings were found: 0.31 on average, never under 0.26 or over 0.39, at every zoom, tilt
    // and slant, facing south or north.
    const val RING_BELOW = 0.31

    // The three funnels, left to right, and the distance from one to the next. The dots: Mox, Aga, Lye, left to right.
    // Each dot goes DOT_BELOW spacings from its funnel, out from the row of funnels towards the front of the table:
    // straight down when the row is level. When the camera is turned a little the table turns with it and the row
    // slants, so the dot goes to one side as well: as far as the slant says, and a quarter more (SIDEWAYS), because
    // the table is seen from above at an angle, which makes a turn look smaller than it is. Measured on levers_2.mp4
    // at 33, 41 and 46 s by the two lever handles either side of the middle lever: they sat 1.1, 1.5 and 1.3 times
    // the slant's amount to the side. Dots put straight down slid off the side levers there.
    //
    // `hidden` = which funnel wasn't seen but worked out from the other two (-1 = all three seen; see `find`'s `last`)
    // `rings` = the three rings, when the row was found by them and not by full funnels (null otherwise; see `ringRow`)
    // Facing north the dots go the other way, DOT_ABOVE spacings out from the row towards the back of the table
    // (the sideways part is the same as facing south: on north_3.mp4, turned up to 25° either way, the game's outline
    // of each long-pressed lever was under or touching its dot).
    class Levers(val funnels: List<Funnel>, val spacing: Double, val hidden: Int = -1, val rings: List<Ring>? = null) {
        // Which way the row runs, as one step along it (1 across and 0 down = level)
        private val alongX = (funnels[2].x - funnels[0].x) / (2 * spacing)
        private val alongY = (funnels[2].y - funnels[0].y) / (2 * spacing)
        private fun out(north: Boolean) = if (north) -DOT_ABOVE else DOT_BELOW
        fun dotX(i: Int, north: Boolean = false) = funnels[i].x - out(north) * spacing * alongY * SIDEWAYS
        fun dotY(i: Int, north: Boolean = false) = funnels[i].y + out(north) * spacing * alongX
        // Which lever is i-th from the left: Mox, Aga, Lye facing south; Lye, Aga, Mox facing north
        fun lever(i: Int, north: Boolean = false) = MixologyData.Paste.entries[if (north) 2 - i else i]
    }

    // Which paste a pixel is (0 = none, else the paste's number + 1), by its hue (the colour's place round the colour
    // wheel) and how strong the colour is. Measured at the game's lowest and highest brightness: pink 2°, green
    // 111-113°, purple-blue 250-255°; the table and floor are 29-43°, the leaves 131° and darker. It's worked out
    // in whole numbers, with no dividing (this runs for every pixel looked at): the hue limits in degrees are
    //   pink 345-15°:        red strongest, green and blue no more than a quarter of the colour's spread apart
    //   green 95-125°:       green strongest, blue from 5/12 of the spread below red to 1/12 above it
    //   purple-blue 243-268°: blue strongest, red above green by 3/60 to 28/60 of the spread
    // (the sea's softer purple-blue, 231°, mustn't count: the Puzzle Box screenshots). The tool's own dots (198°,
    // 151° and 340° in MixologyData) are none of these, so it doesn't take its own drawing for funnels.
    private fun pasteOf(rgb: Int): Int {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val max = maxOf(r, g, b)
        if (max < 90) return 0
        val spread = max - minOf(r, g, b)
        // Too grey for any paste (the colour's strength, spread / max, must be over 0.12; over 0.15 for pink and green)
        if (spread * 100 <= max * 12) return 0
        return when (max) {
            r -> if (abs(g - b) * 4 <= spread && spread * 100 > max * 15) LYE else 0
            g -> if (max > 102 && spread * 100 > max * 15 && (b - r) * 12 >= -5 * spread && (b - r) * 12 <= spread) AGA else 0
            else -> if ((r - g) * 60 >= 3 * spread && (r - g) * 60 <= 28 * spread) MOX else 0
        }
    }
    private const val MOX = 1
    private const val AGA = 2
    private const val LYE = 3
    private const val GREY = 4   // (not a paste: the rings' grey, see `kindOf`)

    // A looked-at pixel's kind: its paste, or GREY if it could be one of the rings (0 = neither). The rings are a
    // plain grey with hardly any colour (the colour's strength, spread / max, is 6-8%; under 14% counts), neither
    // very dark nor very light: (128,118,118) at normal brightness, (142,134,133) at the highest. The table round
    // them is sand-coloured (strength 26-37%), the glass of an empty funnel over the table too (20%).
    private fun kindOf(rgb: Int): Int {
        val paste = pasteOf(rgb)
        if (paste != 0) return paste
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val max = maxOf(r, g, b)
        if (max < 70 || max > 215) return 0
        return if ((max - minOf(r, g, b)) * 100 <= max * 14) GREY else 0
    }

    // Dark, as the holes in the rings are (nearly black; the thin slit of a hole seen from the side (53,51,52))
    private fun dark(rgb: Int) = maxOf(rgb shr 16 and 0xFF, rgb shr 8 and 0xFF, rgb and 0xFF) < 0x48

    // The table's sand colour (and the lab floor's): red over green over blue, the colour's strength 17-50%, green
    // about halfway between red and blue. (149,122,94) at normal brightness, (180,159,133) at the highest.
    private fun sand(rgb: Int): Boolean {
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val spread = r - b
        return r >= 70 && r > g && g > b && spread * 100 >= r * 17 && spread * 100 <= r * 50 &&
            (g - b) * 100 >= spread * 35 && (g - b) * 100 <= spread * 75
    }

    // Finds the three funnels in an area of the screen (the whole screen if none is given). It looks twice: first
    // quickly, at every 4th pixel across and down (a 16th of the pixels, and most looks end here with nothing found:
    // the table isn't on screen); then, only if that finds a row, carefully at every 2nd pixel just around it, for
    // the exact places and the shapes. `step` = the careful look's spacing (2 is plenty: a funnel is at least 20
    // pixels across on the owner's phone, even zoomed right out); the quick look uses twice that; on a screen much
    // smaller than the owner's (1080 pixels high), 1 would suit better.
    //
    // Full funnels are tried first (three paste-coloured funnels in a row); if there's no such row, the rings
    // (`ringRow`): the quick look's likely rows of rings, the best few, are each looked at carefully and checked
    // pixel by pixel (`isRing`), and the first that holds is the row. On the PC a look at the whole screen takes
    // 1.1 to 2 ms (1.5 with a row of full funnels, 1.8 with empty ones; 1 to 1.5 before the rings were looked for).
    //
    // `last` = the last row found with all three funnels seen. Standing at the middle (Aga) lever, the character's
    // head hides the middle funnel (levers_3.mp4 at 0-14 s): then the two outer funnels are enough, if they're the
    // same distance apart, in the same direction and the same size as in `last` (the camera hasn't been zoomed or
    // turned since). Where they are on the screen may have changed: the camera follows the character, so walking
    // from lever to lever moves the whole table. Only the outer two count, not two neighbours: the funnels are one
    // spacing apart, and so are the levers, so two neighbours seen after a step to the side could be either pair.
    // The same goes for the rings: the two outer rings are enough when they're as far apart and in the same
    // direction as `last`'s outer two.
    fun find(p: LightBoxReader.PixelSource, left: Int = 0, top: Int = 0, right: Int = p.width, bottom: Int = p.height,
             step: Int = 2, last: Levers? = null): Levers? {
        val quick = look(p, left, top, right, bottom, step * 2, careful = false) ?: return null
        val rough = bestRow(quick.funnels) ?: last?.let { outerPair(quick.funnels, it, step * 2) }
        if (rough != null) {
            val a = rough.funnels[0]
            val c = rough.funnels[2]
            val margin = 1.5 * rough.spacing
            val near = look(p, (a.x - margin).toInt(), (minOf(a.y, c.y) - margin).toInt(),
                (c.x + margin).toInt(), (maxOf(a.y, c.y) + margin).toInt(), step, careful = true)
            if (near != null) {
                (bestRow(near.funnels) ?: last?.let { outerPair(near.funnels, it, step) })?.let { return it }
            }
        }
        // No row of full funnels: the rings
        for (guess in ringGuesses(quick.rings, last, step * 2)) {
            val a = guess[0]
            val c = guess[1]
            val margin = (0.4 * (c.x - a.x)).toInt()
            val near = look(p, a.left - margin, minOf(a.top, c.top) - margin, c.right + margin,
                maxOf(a.bottom, c.bottom) + margin, step, careful = true) ?: continue
            val sure = near.rings.filter { isRing(p, it) }
            (ringRow(sure, step, quick = false).firstOrNull { !inTooltip(p, it.asList()) }?.let { ringLevers(it[0], it[1], it[2]) }
                ?: last?.let { ringPair(sure, it, step) }?.takeIf { !inTooltip(p, it.rings!!) })?.let { return it }
        }
        return null
    }

    // Whether a row of rings is really three letters in the game's tooltip: when a station or lever is pressed, the
    // game writes what it does ("Homogenise-potion Agitator") in grey letters with dark shadows on a see-through
    // grey box, and three of its round letters ("o", "e") can pass for the rings, with their shadows for the holes
    // and the table seen through the box for the sand. The box has a straight dark edge above and below the
    // letters, right across them, which the table never has. Measured on every row of rings found in play_1 to
    // play_10 (10 looks a second, 7,763 rows): 30 of the 33 rows in tooltips had a line at least 0.89 dark (most
    // 1.00) both above and below within two ring heights; on the 7,730 rows of the table the weaker of the two
    // lines was 0.30 dark at most (the table's own dark edge can be on one side, up to 0.91). So: a line at least
    // 3/4 dark on both sides. The other three tooltip rows: one fading out (its lower edge pale, play_4.mp4 at
    // 13.8 s) and two with the character and a progress bar over its upper edge (play_6.mp4 at 33.5 s); the dots
    // rule (`Dots`, a second look) keeps rows like those off the screen.
    private fun inTooltip(p: LightBoxReader.PixelSource, rings: List<Ring>): Boolean {
        val left = rings[0].left
        val right = rings[2].right
        val h = rings.maxOf { it.height }
        val top = rings.minOf { it.top }
        val bottom = rings.maxOf { it.bottom }
        return darkLine(p, left, right, top - 2 * h, top - 1) && darkLine(p, left, right, bottom + 1, bottom + 2 * h)
    }

    // Whether any line across from `left` to `right`, between the heights `from` and `to`, is at least 3/4 dark. A
    // line stops being looked at once a quarter of it is found not dark, so on the table this is quick.
    private fun darkLine(p: LightBoxReader.PixelSource, left: Int, right: Int, from: Int, to: Int): Boolean {
        val x0 = left.coerceAtLeast(0)
        val x1 = right.coerceAtMost(p.width)
        if (x1 <= x0) return false
        val allowed = (right - left) / 4
        for (y in from.coerceAtLeast(0)..to.coerceAtMost(p.height - 1)) {
            var light = 0
            var x = x0
            while (x < x1 && light <= allowed) {
                if (!dark(p.rgb(x, y))) light++
                x++
            }
            if (light <= allowed) return true
        }
        return false
    }

    // What one look at an area found: the funnel-shaped patches of paste and the ring-shaped patches of grey
    private class Looked(val funnels: List<Funnel>, val rings: List<Ring>)

    // One look at an area, at every step-th pixel across and down. `careful` = also check each funnel's shape, and
    // hold the rings' shape to tighter limits. Null if the area is too small.
    private fun look(p: LightBoxReader.PixelSource, left: Int, top: Int, right: Int, bottom: Int, step: Int,
                     careful: Boolean): Looked? {
        val x0 = left.coerceAtLeast(0)
        val y0 = top.coerceAtLeast(0)
        val gw = (right.coerceAtMost(p.width) - x0) / step
        val gh = (bottom.coerceAtMost(p.height) - y0) / step
        if (gw < 4 || gh < 4) return null
        // Each looked-at pixel's kind: a paste, the rings' grey, or 0 = neither
        val grid = ByteArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            grid[gy * gw + gx] = kindOf(p.rgb(x0 + gx * step, y0 + gy * step)).toByte()
        }
        val patches = patches(grid, gw, gh)
        return Looked(funnels(patches, x0, y0, step, careful), rings(patches, x0, y0, step, careful))
    }

    // The ring-shaped patches of grey: a ring is a squat round thing, as wide as it's tall or up to one and a half
    // times as wide (0.92-1.58 in the owner's recordings; 0.85-2 allowed), and its grey fills at least 0.4 of the
    // box round it, even with its hole or a stem of paste in the middle. From 8 pixels across (zoomed right out) to
    // 130 (zoomed right in). The quick look only sees a few pixels of each ring, so its limits are looser.
    private fun rings(patches: List<Patch>, x0: Int, y0: Int, step: Int, careful: Boolean): List<Ring> {
        val found = ArrayList<Ring>()
        for (patch in patches) {
            if (patch.kind != GREY) continue
            val w = (patch.maxX - patch.minX + 1) * step
            val h = (patch.maxY - patch.minY + 1) * step
            if (w < 8 || w > 130 || h < 8 || h > 110) continue
            if (careful) { if (w * 100 < h * 85 || w > h * 2) continue } else if (w * 100 < h * 65 || w * 10 > h * 26) continue
            if (patch.n * step * step * 10 < w * h * 4) continue
            found.add(Ring(x0 + patch.minX * step, y0 + patch.minY * step, x0 + (patch.maxX + 1) * step,
                y0 + (patch.maxY + 1) * step))
        }
        return found
    }

    // The rows of three rings among these, best first, each as its left, middle and right ring: the outer two about
    // the same width, nearly level (the same slant as a row of funnels may have, see `bestRow`), 2.5 to 4.8 ring
    // widths from one ring to the next (2.97-4.49 in every row of the owner's recordings, 3.4 mostly), and the third
    // close to halfway between them (within 0.12 of a spacing; 0.05 at most in the recordings) and about as wide.
    // Best = the middle one nearest to halfway. The quick look's limits are looser (it measures roughly).
    private fun ringRow(rings: List<Ring>, step: Int, quick: Boolean): List<Array<Ring>> {
        val sorted = rings.sortedBy { it.x }
        val rows = ArrayList<Array<Ring>>()
        val offs = ArrayList<Double>()
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]
            val c = sorted[j]
            val dx = c.x - a.x
            val dy = c.y - a.y
            val d = hypot(dx, dy) / 2
            if (d < 16 || abs(dy) > 0.4 * dx) continue
            val wide = maxOf(a.width, c.width)
            if (minOf(a.width, c.width) * 10 < wide * 6) continue
            val apart = d / wide
            if (apart < (if (quick) 2.0 else 2.5) || apart > (if (quick) 5.6 else 4.8)) continue
            for (b in sorted) {
                if (b === a || b === c) continue
                val off = hypot(b.x - (a.x + c.x) / 2, b.y - (a.y + c.y) / 2)
                if (off > 0.12 * d + (if (quick) step else 0) || b.width * 10 < wide * 6 || b.width * 10 > wide * 16) continue
                // (kept in order, nearest to halfway first)
                var at = 0
                while (at < offs.size && offs[at] <= off / d) at++
                offs.add(at, off / d)
                rows.add(at, arrayOf(a, b, c))
            }
        }
        return rows
    }

    // Where the quick look thinks a row of rings could be, as pairs of outer rings, at most RING_TRIES of them: its
    // rows of three, then (with `last`) pairs of rings as far apart as `last`'s outer two, for a row with its middle
    // hidden. Each gets a careful look. On a screen with lots of small grey things there can be many such rows;
    // trying only the best few keeps a look cheap (in the lab the real row is the first or the only one).
    private fun ringGuesses(rings: List<Ring>, last: Levers?, step: Int): List<Array<Ring>> {
        val guesses = ArrayList<Array<Ring>>()
        for (row in ringRow(rings, step, quick = true)) {
            if (guesses.size >= RING_TRIES) return guesses
            if (guesses.none { it[0] === row[0] && it[1] === row[2] }) guesses.add(arrayOf(row[0], row[2]))
        }
        if (last != null) {
            val wantX = last.funnels[2].x - last.funnels[0].x
            val wantY = last.funnels[2].y - last.funnels[0].y
            for (a in rings) for (c in rings) {
                if (guesses.size >= RING_TRIES) return guesses
                if (c === a || hypot(c.x - a.x - wantX, c.y - a.y - wantY) >= 0.1 * last.spacing + 2 * step) continue
                if (guesses.none { it[0] === a && it[1] === c }) guesses.add(arrayOf(a, c))
            }
        }
        return guesses
    }
    private const val RING_TRIES = 3

    // Whether the grey patch in this box really is one of the table's rings, looking at every pixel of it:
    //  - at least 0.4 of the box is grey;
    //  - in its middle there's the dark hole: at least 1.2% of the box (5-15% in the owner's recordings with the
    //    funnel empty), or, with a full funnel's stem of paste in the box (at least 4% of it), the slivers of hole
    //    left beside the stem (at least 0.3% and 2 pixels; 0.6% or more in 99 of 100 rows). Paste alone isn't
    //    enough: the pale purple studs on the lab's conveyor belt count as paste and grey, in a row, on sand colour
    //    (levers_2.mp4 at 42 s), but they have no holes;
    //  - just outside the box, all round, it's the table: at least half sand-coloured (0.68-1 in the recordings; the
    //    rest is the funnel's glass above, or the character) and no more than 15% dark.
    // This is what tells the rings from everything else that's small, grey and in a row: without it 843 of the
    // owner's 2,291 pictures from elsewhere had such a row (buttons, rocks, armour); with it none has.
    private fun isRing(p: LightBoxReader.PixelSource, ring: Ring): Boolean {
        val w = ring.width
        val h = ring.height
        val band = maxOf(2, w * 12 / 100)
        if (ring.left - band < 0 || ring.top - band < 0 || ring.right + band > p.width || ring.bottom + band > p.height) return false
        val coreLeft = ring.left + w / 5
        val coreRight = ring.left + w * 4 / 5
        val coreTop = ring.top + h * 15 / 100
        val coreBottom = ring.top + h * 85 / 100
        var grey = 0
        var hole = 0
        var paste = 0
        var around = 0
        var sandy = 0
        var darkAround = 0
        for (y in ring.top - band until ring.bottom + band) for (x in ring.left - band until ring.right + band) {
            val rgb = p.rgb(x, y)
            if (x < ring.left || x >= ring.right || y < ring.top || y >= ring.bottom) {
                around++
                if (dark(rgb)) darkAround++ else if (sand(rgb)) sandy++
            } else {
                val kind = kindOf(rgb)
                if (kind == GREY) grey++
                else if (kind != 0) paste++
                else if (x in coreLeft..coreRight && y in coreTop..coreBottom && dark(rgb)) hole++
            }
        }
        val box = w * h
        return grey * 10 >= box * 4 && (hole * 1000 >= box * 12 || (paste * 100 >= box * 4 && hole >= 2 && hole * 1000 >= box * 3)) &&
            sandy * 2 >= around && darkAround * 100 <= around * 15
    }

    // The row a left, middle and right ring make: each funnel's middle is RING_BELOW spacings straight above its
    // ring's. A funnel's size isn't seen this way, so it's worked out from the spacing as full funnels measure
    // (2.26 widths apart, 1.16 times as tall as wide, their paste filling 0.45 of that box: levers3_53.5s.png).
    private fun ringLevers(a: Ring, b: Ring, c: Ring, hidden: Int = -1): Levers {
        val d = hypot(c.x - a.x, c.y - a.y) / 2
        val w = (d / 2.26).toInt()
        val h = (w * 1.16).toInt()
        val rings = listOf(a, b, c)
        return Levers(rings.map { Funnel(it.x, it.y - RING_BELOW * d, w, h, w * h * 45 / 100, null) }, d, hidden, rings)
    }

    // The middle ring hidden (the character stands over it): two rings as far apart and in the same direction as
    // `last`'s outer two (within a tenth of a spacing, plus the looked-at pixels' spacing), and about as wide as
    // each other. The middle one is put halfway between them.
    private fun ringPair(rings: List<Ring>, last: Levers, step: Int): Levers? {
        val wantX = last.funnels[2].x - last.funnels[0].x
        val wantY = last.funnels[2].y - last.funnels[0].y
        var bestErr = 0.1 * last.spacing + step
        var best: Levers? = null
        for (a in rings) for (c in rings) {
            if (c === a || minOf(a.width, c.width) * 10 < maxOf(a.width, c.width) * 6) continue
            val err = hypot(c.x - a.x - wantX, c.y - a.y - wantY)
            if (err >= bestErr) continue
            bestErr = err
            val middle = Ring((a.left + c.left) / 2, (a.top + c.top) / 2, (a.right + c.right) / 2, (a.bottom + c.bottom) / 2)
            best = ringLevers(a, middle, c, hidden = 1)
        }
        return best
    }

    // Touching pixels of the same kind, joined into one patch: how many, where their middle is (the sums, divided by
    // how many) and the box round them. All in looked-at pixels, not screen pixels.
    private class Patch(val kind: Int, val n: Int, val sumX: Long, val sumY: Long,
                        val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
        val x get() = sumX.toDouble() / n
        val y get() = sumY.toDouble() / n
    }

    // Joins touching pixels of the same kind into patches (for the funnels and for the compass needle). It empties
    // the grid as it goes (a pixel that's been counted is set to 0), which is how it knows not to count one twice.
    private fun patches(grid: ByteArray, gw: Int, gh: Int): List<Patch> {
        var stack = IntArray(1024)
        val found = ArrayList<Patch>()
        for (start in grid.indices) {
            val kind = grid[start]
            if (kind.toInt() == 0) continue
            var n = 0
            var sumX = 0L
            var sumY = 0L
            var minX = Int.MAX_VALUE; var maxX = 0; var minY = Int.MAX_VALUE; var maxY = 0
            var top = 0
            stack[top++] = start
            grid[start] = 0
            while (top > 0) {
                val i = stack[--top]
                val gx = i % gw
                val gy = i / gw
                n++; sumX += gx; sumY += gy
                if (gx < minX) minX = gx
                if (gx > maxX) maxX = gx
                if (gy < minY) minY = gy
                if (gy > maxY) maxY = gy
                // Room for the 4 neighbours (left, right, up, down)
                if (top + 4 > stack.size) stack = stack.copyOf(stack.size * 2)
                if (gx > 0 && grid[i - 1] == kind) { grid[i - 1] = 0; stack[top++] = i - 1 }
                if (gx < gw - 1 && grid[i + 1] == kind) { grid[i + 1] = 0; stack[top++] = i + 1 }
                if (gy > 0 && grid[i - gw] == kind) { grid[i - gw] = 0; stack[top++] = i - gw }
                if (gy < gh - 1 && grid[i + gw] == kind) { grid[i + gw] = 0; stack[top++] = i + gw }
            }
            found.add(Patch(kind.toInt(), n, sumX, sumY, minX, maxX, minY, maxY))
        }
        return found
    }

    // The funnel-shaped patches: taller than wide (0.9-2 times), filling at least a quarter of their outline, not
    // too small or big. `careful` = also wide at the top and thin at the bottom, as a funnel is: the patch's middle
    // must be in its upper 45% (36-43% down for every funnel in the owner's recordings, even with the player's head
    // over its stem; 45% or further down for the things that made false rows of three in the owner's other
    // screenshots: runes in the inventory, flowers, small interface pictures; 77 false rows in 2,263 pictures
    // before this check, none after). The quick look doesn't check that, and lets a funnel be a little wider than
    // tall (0.75 times): a funnel is too few pixels there to tell, and the careful look decides anyway.
    private fun funnels(patches: List<Patch>, x0: Int, y0: Int, step: Int, careful: Boolean): List<Funnel> {
        val found = ArrayList<Funnel>()
        for (patch in patches) {
            if (patch.kind == GREY) continue
            val w = (patch.maxX - patch.minX + 1) * step
            val h = (patch.maxY - patch.minY + 1) * step
            val area = patch.n * step * step
            if (area < 30 || w > 200 || h > 300) continue
            if (area * 4 < w * h || h * 100 < w * (if (careful) 90 else 75) || h > w * 2) continue
            if (careful && (patch.y - patch.minY + 0.5) * step > 0.45 * h) continue
            found.add(Funnel(x0 + (patch.x + 0.5) * step, y0 + (patch.y + 0.5) * step, w, h, area,
                MixologyData.Paste.entries[patch.kind - 1]))
        }
        return found
    }

    // The best row of three: the outer two about the same size, nearly level (no more than about 22° slant: zoomed
    // out and turned 21° from north the row slants 20°, north_3.mp4 at 51-71 s; still no false rows in the owner's
    // 2,263 pictures from elsewhere, and the compass decides which way the camera faces), 1.8-2.8
    // funnel widths apart (2.2-2.3 in every shot), and a third of about the same size close to halfway between them
    private fun bestRow(funnels: List<Funnel>): Levers? {
        val sorted = funnels.sortedBy { it.x }
        var best: Levers? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (i in sorted.indices) for (j in i + 1 until sorted.size) {
            val a = sorted[i]
            val c = sorted[j]
            val dx = c.x - a.x
            val dy = c.y - a.y
            val d = hypot(dx, dy) / 2
            if (d < 10 || abs(dy) > 0.4 * dx) continue
            if (a.area * 2 < c.area || c.area * 2 < a.area) continue
            val apart = d / maxOf(a.width, c.width)
            if (apart < 1.8 || apart > 2.8) continue
            val midX = (a.x + c.x) / 2
            val midY = (a.y + c.y) / 2
            for (b in sorted) {
                if (b === a || b === c) continue
                val off = hypot(b.x - midX, b.y - midY)
                if (off > 0.12 * d || b.area * 2 < a.area || a.area * 2 < b.area) continue
                val score = -off / d - abs(ln(b.area.toDouble() / a.area)) - abs(ln(a.area.toDouble() / c.area))
                if (score > bestScore) { bestScore = score; best = Levers(listOf(a, b, c), d) }
            }
        }
        return best
    }

    // The compass: which way its needle points and where its middle is on the screen. The direction is in degrees,
    // measured like a clock's hand from the right, going down: 90 = the red (north) half points straight down, which
    // is facing south; -90 = straight up, facing north. `exact` = read from the marks on the rim (see `findCompass`).
    class Compass(val angle: Double, val x: Double, val y: Double, val exact: Boolean)

    // How far round the needle's middle the compass is looked for when it's known where it was (`near`), in game
    // pixels: the rim marks are 14 from the middle, the dial's edge 17
    private const val COMPASS_REACH = 20

    // Finds the compass and reads it. Null if it isn't found. It's the red and blue halves of the needle, side by
    // side, in the top right of the screen (left 60% and bottom 75% aren't looked at). `uiScale` = screen pixels per
    // game pixel (MixologyReader.Box.scale; the same for the whole interface): the halves are about 4 game pixels
    // apart.
    //
    // The needle itself is a small rough picture: red half against blue half, it reads up to 18° wrong either way
    // (with the funnels in a dead level row it read anywhere from 80° to 106° on the owner's recordings). So the
    // direction is taken from the dial's rim instead: it has three small red marks, at east, west and south (north
    // has the letter N), 14 game pixels from the middle, which turn with the needle. The line from the east mark to
    // the west one is 27 game pixels long, so it gives the direction to a degree or two (checked on all six
    // recordings, 775 pictures: read this way, the compass and the slant of the funnels' row agree everywhere; a
    // level row reads 88-92°). The needle only says which mark is which. With one of that pair hidden, the marks
    // that do show are measured from the needle's middle (a few degrees less sure); with none, the needle alone.
    //
    // `near` = the compass found last time: then only a small square round it is looked at (about 7,000 pixels
    // instead of 250,000), so it can be read on every look. Null if it isn't there any more.
    fun findCompass(p: LightBoxReader.PixelSource, uiScale: Double, near: Compass? = null): Compass? {
        val reach = (COMPASS_REACH * uiScale).toInt()
        val x0 = (near?.let { it.x.toInt() - reach } ?: (p.width * 0.6).toInt()).coerceAtLeast(0)
        val y0 = (near?.let { it.y.toInt() - reach } ?: 0).coerceAtLeast(0)
        val gw = (near?.let { it.x.toInt() + reach } ?: p.width).coerceAtMost(p.width) - x0
        val gh = (near?.let { it.y.toInt() + reach } ?: (p.height / 4)).coerceAtMost(p.height) - y0
        if (gw < 4 || gh < 4) return null
        // 1 = needle red (the rim marks are the same red), 2 = needle blue
        val grid = ByteArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val rgb = p.rgb(x0 + x, y0 + y)
            val r = rgb shr 16 and 0xFF
            val g = rgb shr 8 and 0xFF
            val b = rgb and 0xFF
            if (r > 150 && g < 90 && b < 90) grid[y * gw + x] = 1
            else if (b > 140 && r < 100 && g < 130) grid[y * gw + x] = 2
        }
        val patches = patches(grid, gw, gh)
        val s2 = uiScale * uiScale
        var bestGap = 10 * uiScale
        var needleRed: Patch? = null
        var needleBlue: Patch? = null
        for (red in patches) {
            if (red.kind != 1 || red.n < 5 * s2) continue
            for (blue in patches) {
                if (blue.kind != 2 || blue.n < 2 * s2) continue
                val gap = hypot(red.x - blue.x, red.y - blue.y)
                if (gap < bestGap) { bestGap = gap; needleRed = red; needleBlue = blue }
            }
        }
        val red = needleRed ?: return null
        val blue = needleBlue ?: return null
        val rough = Math.toDegrees(atan2(red.y - blue.y, red.x - blue.x))
        val midX = (red.x * red.n + blue.x * blue.n) / (red.n + blue.n)
        val midY = (red.y * red.n + blue.y * blue.n) / (red.n + blue.n)

        // The rim marks: small red patches 11.5-16.5 game pixels from the needle's middle
        val marks = ArrayList<Patch>()
        for (mark in patches) {
            if (mark.kind != 1 || mark.n > 6 * s2) continue
            val d = hypot(mark.x - midX, mark.y - midY)
            if (d >= 11.5 * uiScale && d <= 16.5 * uiScale) marks.add(mark)
        }
        // East and west: the two marks furthest apart (neighbouring marks are 19 game pixels apart, these 27). The
        // needle is square to the line between them, on the side the rough reading says.
        var exact = Double.NaN
        var longest = 23 * uiScale
        for (i in marks.indices) for (j in i + 1 until marks.size) {
            val dx = marks[j].x - marks[i].x
            val dy = marks[j].y - marks[i].y
            val length = hypot(dx, dy)
            if (length <= longest) continue
            val off = quarterTurn(Math.toDegrees(atan2(dy, dx)) - rough)
            if (abs(off) > MARK_SLACK) continue
            longest = length
            exact = rough + off
        }
        if (exact.isNaN() && marks.isNotEmpty()) {
            // Each mark by itself, from the needle's middle: a quarter turn, a half turn or none from the needle
            var sum = 0.0
            var n = 0
            for (mark in marks) {
                val off = quarterTurn(Math.toDegrees(atan2(mark.y - midY, mark.x - midX)) - rough)
                if (abs(off) <= MARK_SLACK) { sum += off; n++ }
            }
            if (n > 0) exact = rough + sum / n
        }
        val angle = if (exact.isNaN()) rough else if (exact > 180) exact - 360 else if (exact <= -180) exact + 360 else exact
        return Compass(angle, x0 + midX, y0 + midY, !exact.isNaN())
    }

    // The rough reading of the needle is never further out than this from what the rim marks say (18° at most on the
    // owner's recordings); a "mark" further out isn't one
    private const val MARK_SLACK = 30.0

    // An angle with whole quarter turns taken off, leaving -45° to 45°
    private fun quarterTurn(degrees: Double): Double {
        var d = degrees % 90
        if (d > 45) d -= 90
        if (d < -45) d += 90
        return d
    }

    // Just the direction (see `findCompass`)
    fun needleAngle(p: LightBoxReader.PixelSource, uiScale: Double): Double? = findCompass(p, uiScale)?.angle

    // Facing south: the needle within SLACK of straight down. Facing north: within SLACK of straight up.
    fun facingSouth(angle: Double): Boolean = abs(angle - SOUTH) <= SLACK
    fun facingNorth(angle: Double): Boolean = abs(angle - NORTH) <= SLACK

    // Whether the funnels of `row`, found a moment ago, are still where they were: down the middle of at least one
    // of them (not counting one that was only worked out) a quarter or more is paste. The tool asks this when a look
    // finds no row. After a lever is pulled all three funnels drain and fill again, which takes about half a second:
    // there's no row of three full funnels then, but the last of the old paste or the first of the new is still in
    // them (in funnels_1.mp4 the fullest of the three was never under half paste down its middle), so the dots stay.
    // When the camera has moved or the table has left the screen, there's floor or table where the funnels were, and
    // the dots must go at once instead of hanging in the air. (A quarter, not just any paste: the lab's floor has
    // small pink stains, 4 pixels of one down a funnel's old middle in levers2_26s.png.)
    //
    // A row found by its rings (the funnels empty or partly full): at least one of its rings (not counting one that
    // was only worked out) is still a ring where it was (`isRing`). That's when the game's menu or a name tag covers
    // some of the three for a moment (play_1.mp4 at 18.5 s: "Operate Mox lever" over the left and middle rings).
    fun stillThere(p: LightBoxReader.PixelSource, row: Levers): Boolean {
        if (row.rings != null) {
            for ((i, ring) in row.rings.withIndex()) if (i != row.hidden && isRing(p, ring)) return true
            return false
        }
        for ((i, funnel) in row.funnels.withIndex()) {
            if (i == row.hidden) continue
            val x = funnel.x.toInt()
            if (x < 0 || x >= p.width) continue
            // (a funnel's middle is about 40% of the way down it: this runs from its top to the foot of its stem)
            var y = (funnel.y - 0.45 * funnel.height).toInt().coerceAtLeast(0)
            val end = (funnel.y + 0.65 * funnel.height).toInt().coerceAtMost(p.height - 1)
            var paste = 0
            var looked = 0
            while (y <= end) {
                if (pasteOf(p.rgb(x, y)) != 0) paste++
                looked++
                y += 2
            }
            if (paste * 4 >= looked && paste > 0) return true
        }
        return false
    }

    // Which dots the tool shows, from one look to the next (no Android parts: tested on a computer). The rules:
    //  - a row of funnels (full ones, or empty ones found by their rings) with the compass facing south or north:
    //    its dots are shown, the south or the north way;
    //  - a row with the camera turned away, or the compass not found: no dots (they'd be on the wrong places);
    //  - no row, but the last row's funnels or rings still there (`stillThere`: something hides one of them for a
    //    moment, like the game's menu) and the compass where it was: the last dots stay, for KEEP_MS at most;
    //  - no row and the funnels gone, or the compass turned or not found: no dots. Turning the camera on the spot
    //    can leave a funnel's paste where another's was, so the funnels alone don't tell (levers_3.mp4 at 20 s).
    //  - a row far from where the dots are (or were, up to KEEP_MS ago), or with no dots before, is only shown once
    //    a second look finds it in the same place (`sameRow`): the look before that found a row, no longer than
    //    SAME_MS ago. Until then the dots stay as they were. This is so a row found on one look alone, which can be
    //    something else (`find` takes care of the game's tooltip, `inTooltip`, but a wall or the minimap's buttons
    //    have passed for a row once each in ten recordings), never shows. It costs one look (a tenth of a second,
    //    MixologyTool's CONFIRM_MS) when the dots first come up.
    class Dots {
        companion object {
            const val KEEP_MS = 2_000L
            const val TURNED = 4.0   // the compass this many degrees from where it was = the camera has turned
            const val SAME_MS = 500L // "the look before" = the last look that found a row, no longer ago than this
        }

        var levers: Levers? = null   // the row whose dots are shown now (null = none)
            private set
        var north = false            // ...shown the north way (above the funnels, reversed)
            private set
        var fullRow: Levers? = null  // the last row with all three funnels seen, for `find`'s `last`
            private set
        var waiting = false          // a row was found but isn't shown yet: it needs the next look to agree
            private set
        private var seenAt = 0L
        private var seenAngle = 0.0  // the compass when the row was last seen
        private var lastFound: Levers? = null  // the last row found (shown or not), and when
        private var lastFoundAt = 0L
        private var lastShown: Levers? = null  // the row last shown (shown at `seenAt`), kept after the dots go

        // One look. `now` in milliseconds; `row` = what `find` found; `compass` = what `findCompass` read (null =
        // not found); `stillThere` only matters when there's no row.
        fun look(now: Long, row: Levers?, compass: Compass?, stillThere: Boolean) {
            val before = lastFound?.takeIf { now - lastFoundAt <= SAME_MS }
            // (looks that find nothing don't count against it: while the camera swings round, the table is often
            // found only on every second to fourth look, play_3.mp4 at 104 s and play_8.mp4 at 47 s)
            if (row != null) { lastFound = row; lastFoundAt = now }
            waiting = false
            if (row == null) {
                if (!stillThere || now - seenAt > KEEP_MS || compass == null || turn(compass.angle, seenAngle) > TURNED) {
                    levers = null
                }
                return
            }
            val facingNorth = compass != null && facingNorth(compass.angle)
            if (compass == null || !(facingNorth || facingSouth(compass.angle))) {
                levers = null
                return
            }
            // Far from where the dots are (or were, up to KEEP_MS ago: they go for a look or two when something hides
            // the funnels for a moment), or shown the other way: only if the look before found it too
            val shown = (levers ?: lastShown?.takeIf { now - seenAt <= KEEP_MS })?.takeIf { facingNorth == north }
            if (!(shown != null && sameRow(row, shown)) && !(before != null && sameRow(row, before))) {
                waiting = true
                // (the dots shown stay as when the funnels are hidden for a moment: not past KEEP_MS, nor with the
                // camera turned)
                if (now - seenAt > KEEP_MS || turn(compass.angle, seenAngle) > TURNED) levers = null
                return
            }
            // A remembered row is from the other side of the table once the camera has gone round: forget it
            if (facingNorth != north) fullRow = null
            levers = row
            lastShown = row
            north = facingNorth
            seenAt = now
            seenAngle = compass.angle
            if (row.hidden < 0) fullRow = row
        }

        // How far apart two compass directions are, in degrees (0 to 180)
        private fun turn(a: Double, b: Double): Double {
            val d = abs(a - b) % 360
            return if (d > 180) 360 - d else d
        }

        // No dots now (the order box left the screen); the remembered row stays for when it's back
        fun hide() { levers = null; lastFound = null; waiting = false }

        // Forgets everything (a new start, the phone turned)
        fun forget() {
            levers = null; fullRow = null; north = false; seenAt = 0L; lastFound = null; lastShown = null; waiting = false
        }

        // Whether two rows found a moment apart are the same table, moved as the camera moved in between: the middle
        // funnels no more than SAME_PLACE times the bigger of the two spacings apart, and the bigger spacing no more
        // than SAME_SIZE times the smaller
        fun sameRow(a: Levers, b: Levers): Boolean {
            val big = maxOf(a.spacing, b.spacing)
            val small = minOf(a.spacing, b.spacing)
            return hypot(a.funnels[1].x - b.funnels[1].x, a.funnels[1].y - b.funnels[1].y) <= SAME_PLACE * big &&
                big <= SAME_SIZE * small
        }
    }

    // How far the table really moves on the screen from one look to the next, measured on play_1 to play_10 (every
    // row found at 10 looks a second, the camera following the character, turning and zooming): between looks a
    // tenth of a second apart the middle funnel moved 0.29 spacings or less 99 times in 100, 0.46 at most; a third
    // of a second apart 0.74 or less 99 times in 100, 1.09 at most (walking, play_3.mp4 at 98 s). While the camera
    // swings round, the row can be found only every third or fourth look and move a spacing in between. A zoom can
    // change the spacing 1.6 times between two looks (play_1.mp4 at 25-30 s). The rows that weren't the table landed
    // further off: the tooltip's letters 1.9 to 4.4 spacings from the table (play_10 at 61.7 and 36.75 s), the wall
    // and the minimap's buttons (play_3 at 78.1 and 79.9 s) 6.7 and 8.2.
    const val SAME_PLACE = 1.5
    const val SAME_SIZE = 2.0
}

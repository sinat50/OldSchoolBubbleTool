package com.sinat.osrsbubbletool

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

// Finds the Mastering Mixology lab's stations by how they look, and from them reads how far down the camera is
// tilted, the one thing MixologyStations needs to place all three stations (its part 4). With this the arrow over
// the next station can show from the first look, without waiting for a station to work. No Android parts, so it's
// tested on a computer (MixologyStationLooksTest, on the owner's recordings). No pictures are kept: only the
// numbers below, measured on the owner's recordings (.scratch/mixology/spec.md, 16 November 2026).
//
// What it looks for:
//  - the Alembic's big bulb and the Retort's pot: round lumps of plain dark grey standing on a sand-coloured
//    bench. Nothing else on the lab's floor is that colour and that size (the floor, benches and shadows are all
//    brownish);
//  - the Agitator's left beaker (the plain one at the west end of its row of three), facing north only: a pale,
//    nearly colourless patch, smaller than the lumps. From the south the beakers are mostly outlines.
// Each is found as "the biggest circle that fits inside a patch of that colour" (`cores`), which doesn't mind the
// tubes, legs and handles stuck to it.
//
// How the tilt comes out of that: the row of funnels and the compass already say where each station would be on
// the screen for every possible tilt (a short curved path, MixologyStations.roomSpot). The tilt is the one that
// puts the thing on the circle found (`read`). A circle counts only if it's on its station's path and the size
// that station's thing would be there.
//
// It can't name the wrong station: the Alembic's path and the Retort's are six funnel spacings apart, on opposite
// sides of the room, the Agitator's is between and beyond them, and each is only looked for along its own path.
object MixologyStationLooks {

    // One colour test for every pixel looked at (a `fun interface`, not a Kotlin function type: see ToaReader.Skip)
    fun interface Colour { fun fits(rgb: Int): Boolean }

    // The stations' grey: the three colours nearly equal (the bulb is about 89, 80, 81 at the game's default
    // brightness, 50, 45, 45 at the lowest and 115, 109, 109 at the highest; the pot a little darker), red never the
    // smallest. The floor, the benches and their shadows have 25 or more between their biggest and smallest colour.
    val GREY = Colour { rgb ->
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val hi = maxOf(r, g, b)
        hi in GREY_DARKEST..GREY_PALEST && hi - minOf(r, g, b) <= GREY_SPREAD && r >= g && r >= b
    }
    private const val GREY_DARKEST = 28
    private const val GREY_PALEST = 150
    private const val GREY_SPREAD = 16

    // The Agitator's beakers' pale: paler than the grey and nearly as colourless (a beaker over its bench is about
    // 183, 172, 166 at the game's default brightness, the only one it was measured at; the bench itself 158, 131,
    // 99). The game's white writing is brighter still. The mixing vessel's flask, papers and some clothes are the
    // same pale: the place and the size tell the beaker from those.
    val PALE = Colour { rgb ->
        val r = rgb shr 16 and 0xFF
        val g = rgb shr 8 and 0xFF
        val b = rgb and 0xFF
        val hi = maxOf(r, g, b)
        hi in PALE_DARKEST..PALE_PALEST && hi - minOf(r, g, b) <= PALE_SPREAD
    }
    private const val PALE_DARKEST = 150
    private const val PALE_PALEST = 235
    private const val PALE_SPREAD = 24

    // What it finds, one thing per station: its middle in the room (east, north and up from the middle ring on
    // the lever table, in funnel spacings, like MixologyStations' own numbers), its radius in spacings, how far
    // from that a circle's radius may be, how near the station's path a circle's middle must be (in spacings),
    // its colour, and whether it's looked for facing south as well as north.
    // The places were fitted on 680 views from every recording at once, north and south together (spec.md, 16
    // November 2026): one place per thing puts it within 0.035 of a spacing of its circle facing north (the
    // middle of 557 views) and 0.06 to 0.07 facing south (91 views).
    private class Lump(val at: DoubleArray, val radius: Double, val sizeFits: Double, val onPath: Double,
                       val colour: Colour, val south: Boolean)

    private val LUMPS = listOf(
        // the Alembic's big bulb
        Lump(doubleArrayOf(-3.288, 3.245, 0.290), 0.325, 0.12, 0.06, GREY, true),
        // the Retort's pot
        Lump(doubleArrayOf(3.378, 3.582, 0.414), 0.325, 0.12, 0.06, GREY, true),
        // the Agitator's left beaker (0.15 to 0.2 of a spacing in radius in 9 views of 10; the right-hand beaker,
        // with the stirrer in it, comes out smaller, 0.11, and isn't taken for it). It's far off and small, so it's
        // allowed a little further from its path: it was within 0.06 in 78 of 100 views it was in and within 0.10
        // in 91. The middle beaker is a third of a spacing along the row from it, four times what's allowed.
        Lump(doubleArrayOf(-0.740, 7.043, 0.267), 0.177, 0.16, 0.08, PALE, false)
    )

    // A circle found: its middle and radius, in screen pixels
    class Core(val x: Double, val y: Double, val radius: Double)

    // The biggest circles that fit inside the patches of one colour (the stations' grey unless another is given)
    // in a part of the screen (at most MOST of them, biggest first, none smaller than `least` pixels in radius).
    // Looks at every `step`th pixel each way.
    //
    // For every pixel of the colour it works out how far the nearest pixel of another colour is (first straight
    // up and down, then sideways from that); the pixel furthest from any edge is the middle of the biggest circle.
    // The circle's middle is the average of the pixels that are nearly as far in, which steadies it.
    // (`most` = how many at most; `biggest` = circles bigger than this, in pixels, are passed over: the cave's dark
    // walls round the lab are the stations' grey too)
    fun cores(p: LightBoxReader.PixelSource, left: Int, top: Int, right: Int, bottom: Int, step: Int, least: Double,
              colour: Colour = GREY, most: Int = MOST, biggest: Double = Double.MAX_VALUE): List<Core> {
        val x0 = left.coerceAtLeast(0)
        val y0 = top.coerceAtLeast(0)
        val w = (right.coerceAtMost(p.width) - x0) / step
        val h = (bottom.coerceAtMost(p.height) - y0) / step
        if (w < 3 || h < 3) return emptyList()
        // How many squares of the colour in a line up to and including this one, counted down the column, then the same
        // counted up: the smaller is how far the nearest edge is, straight up or down
        val upDown = IntArray(w * h)
        for (x in 0 until w) {
            var run = 0
            for (y in 0 until h) {
                run = if (colour.fits(p.rgb(x0 + x * step, y0 + y * step))) run + 1 else 0
                upDown[y * w + x] = run
            }
            run = 0
            for (y in h - 1 downTo 0) {
                run = if (upDown[y * w + x] > 0) run + 1 else 0
                if (run < upDown[y * w + x]) upDown[y * w + x] = run
            }
        }
        // Then sideways: the nearest edge in any direction (its distance squared, in squares)
        val far = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val own = upDown[y * w + x]
            if (own == 0) continue
            var best = own * own
            var dx = 1
            while (dx * dx < best) {
                val a = if (x - dx >= 0) upDown[y * w + x - dx] else 0
                val b = if (x + dx < w) upDown[y * w + x + dx] else 0
                val near = minOf(a, b)
                val d = dx * dx + near * near
                if (d < best) best = d
                dx++
            }
            far[y * w + x] = best
        }
        val found = ArrayList<Core>()
        val leastSquares = least / step
        // (only the squares far enough in to be a circle's middle are gone through for each circle: a few hundred,
        // not the whole area)
        val leastSquared = (leastSquares * leastSquares).toInt().coerceAtLeast(1)
        var deep = 0
        for (i in far.indices) if (far[i] >= leastSquared) deep++
        val middles = IntArray(deep)
        deep = 0
        for (i in far.indices) if (far[i] >= leastSquared) middles[deep++] = i
        var passedOver = 0
        while (found.size < most && passedOver < TOO_BIG_MOST) {
            var furthest = 0
            var at = -1
            for (i in middles) if (far[i] > furthest) { furthest = far[i]; at = i }
            val radius = sqrt(furthest.toDouble())
            if (at < 0 || radius < leastSquares) break
            val mx = at % w
            val my = at / w
            // The middle: the average of the squares nearly as far in, round the furthest one
            val reach = radius.toInt() + 1
            val nearly = (radius - 0.6) * (radius - 0.6)
            var sumX = 0.0
            var sumY = 0.0
            var n = 0
            for (y in (my - reach).coerceAtLeast(0)..(my + reach).coerceAtMost(h - 1)) {
                for (x in (mx - reach).coerceAtLeast(0)..(mx + reach).coerceAtMost(w - 1)) {
                    if (far[y * w + x] >= nearly) { sumX += x; sumY += y; n++ }
                }
            }
            val cx = sumX / n
            val cy = sumY / n
            if (radius * step <= biggest) found.add(Core(x0 + cx * step, y0 + cy * step, radius * step)) else passedOver++
            // This lump is dealt with: nothing else is looked for inside it or right beside it
            val clear = (radius * 1.6).toInt() + 1
            for (y in (my - clear).coerceAtLeast(0)..(my + clear).coerceAtMost(h - 1)) {
                for (x in (mx - clear).coerceAtLeast(0)..(mx + clear).coerceAtMost(w - 1)) {
                    if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= clear * clear) far[y * w + x] = 0
                }
            }
        }
        return found
    }
    private const val MOST = 4
    private const val TOO_BIG_MOST = 40   // (how many too-big circles are passed over before giving up)

    // What a look at the stations says, for each of the three: the camera's tilt in degrees that puts that station
    // on its circle (null = that station wasn't surely seen)
    class Read(val alembic: Double?, val retort: Double?, val agitator: Double? = null)

    // How far beyond its path's ends and sides a station is looked for, in funnel spacings (the lump's own radius
    // and a little)
    private const val ROUND_PATH = 0.6
    // Facing south a thing may be this far from its path, in funnel spacings, where its own number is smaller:
    // the south side fits a little less closely (the lumps 0.04 from their places typically and 0.14 in one view
    // of ten, against 0.025 and 0.09 facing north)
    private const val ON_PATH_SOUTH = 0.10

    // Looks for the Alembic's bulb, the Retort's pot and (facing north) the Agitator's left beaker, and reads the
    // tilt each one gives. Null when none is surely there.
    //
    // `row` = the funnels found, `compassAngle` = the compass's reading (MixologyLevers.Compass.angle, read from its
    // rim marks; facing south or north only). `skip` = parts of the screen where the game's interface, a working
    // station's bar or the tool's own drawing is: a circle touching one of those isn't used (something drawn over
    // a lump changes its shape).
    fun read(p: LightBoxReader.PixelSource, row: MixologyLevers.Levers, compassAngle: Double,
             skip: List<MixologyStations.Area>): Read? {
        val south = when {
            MixologyLevers.facingSouth(compassAngle) -> true
            MixologyLevers.facingNorth(compassAngle) -> false
            else -> return null
        }
        val seen = candidates(p, row, compassAngle).map { all -> all.filter { clear(it, skip, p.width, p.height) } }
        val tilts = arrayOfNulls<Double>(LUMPS.size)
        for (i in LUMPS.indices) {
            if (seen[i].isEmpty() || (south && !LUMPS[i].south)) continue
            // The tilt that puts the lump nearest a right-sized circle: every degree, then every tenth round the best
            var bestTilt = 0.0
            var bestOff = Double.MAX_VALUE
            var from = MixologyStations.TILT_MIN
            var to = MixologyStations.TILT_MAX
            var by = 1.0
            repeat(2) {
                var tilt = from
                while (tilt <= to + 1e-9) {
                    val off = nearest(row, compassAngle, tilt, LUMPS[i], seen[i], p.width, p.height)
                    if (off < bestOff) { bestOff = off; bestTilt = tilt }
                    tilt += by
                }
                from = (bestTilt - 1.0).coerceAtLeast(MixologyStations.TILT_MIN)
                to = (bestTilt + 1.0).coerceAtMost(MixologyStations.TILT_MAX)
                by = 0.1
            }
            // (a tilt at either end of what's tried is no answer, just the end)
            if (bestOff <= (if (south) maxOf(LUMPS[i].onPath, ON_PATH_SOUTH) else LUMPS[i].onPath) && bestTilt > MixologyStations.TILT_MIN + 0.5 && bestTilt < MixologyStations.TILT_MAX - 0.5) {
                tilts[i] = bestTilt
            }
        }
        return if (tilts.all { it == null }) null else Read(tilts[0], tilts[1], tilts[2])
    }
    private val PATH_TILTS = doubleArrayOf(22.0, 34.0, 46.0, 58.0, 70.0)

    // The circles along each station's path (the Alembic's, the Retort's, then the Agitator's): every place the
    // station's thing could be for the tilts tried, with room round it
    fun candidates(p: LightBoxReader.PixelSource, row: MixologyLevers.Levers, compassAngle: Double): List<List<Core>> {
        val spacing = row.spacing
        val seen = ArrayList<List<Core>>()
        for (lump in LUMPS) {
            var left = Double.MAX_VALUE
            var top = Double.MAX_VALUE
            var right = -Double.MAX_VALUE
            var bottom = -Double.MAX_VALUE
            var size = Double.MAX_VALUE
            for (tilt in PATH_TILTS) {
                val at = MixologyStations.roomSpot(row, compassAngle, tilt, lump.at, p.width, p.height)
                left = minOf(left, at[0]); right = maxOf(right, at[0])
                top = minOf(top, at[1]); bottom = maxOf(bottom, at[1])
                size = minOf(size, at[2])
            }
            val pad = ROUND_PATH * spacing
            // (looked at finely enough for the smallest the thing can be: about eight looked-at pixels across)
            val step = (lump.radius * size / 4).toInt().coerceIn(2, 5)
            seen.add(cores(p, (left - pad).toInt(), (top - pad).toInt(), (right + pad).toInt(), (bottom + pad).toInt(),
                step, lump.radius * size * (1 - lump.sizeFits) - step, lump.colour))
        }
        return seen
    }

    // How far the nearest right-sized circle is from where `lump` would be at this tilt, in funnel spacings
    // (a big number if there's none)
    private fun nearest(row: MixologyLevers.Levers, compassAngle: Double, tilt: Double, lump: Lump,
                        cores: List<Core>, width: Int, height: Int): Double {
        if (cores.isEmpty()) return 9.0
        val at = MixologyStations.roomSpot(row, compassAngle, tilt, lump.at, width, height)
        val radius = lump.radius * at[2]
        var best = 9.0
        for (core in cores) {
            if (core.radius < radius * (1 - lump.sizeFits) || core.radius > radius * (1 + lump.sizeFits)) continue
            val d = hypot(core.x - at[0], core.y - at[1]) / row.spacing
            if (d < best) best = d
        }
        return best
    }

    // Whether a circle is all on the screen and clear of every area in `skip`, with a little room round it
    private fun clear(core: Core, skip: List<MixologyStations.Area>, width: Int, height: Int): Boolean {
        val r = core.radius * 1.25 + 2
        if (core.x - r < 0 || core.y - r < 0 || core.x + r > width || core.y + r > height) return false
        for (a in skip) {
            if (core.x + r > a.left && core.x - r < a.right && core.y + r > a.top && core.y - r < a.bottom) return false
        }
        return true
    }

    // -----------------------------------------------------------------------------------------------------
    // Without the lever table
    // -----------------------------------------------------------------------------------------------------
    //
    // Zoomed in at a station, the lever table is often off the screen, and everything above starts from its row of
    // funnels. But the stations stand in set places in the room, so two of the three things above, found anywhere
    // on the screen, say just as much as the row does: with the compass giving the camera's turn, there is one
    // place, zoom and tilt of the camera that puts both where they are (`readWithoutTable`).
    //
    // What's found, and what it's good for:
    //  - the Alembic's bulb and the Retort's pot together: two grey balls of the same size, level with each other
    //    and twenty of their own radiuses apart. They're in a line across the room, so (like the funnels) they
    //    don't give the tilt; but the Alembic's and the Retort's arrows go right by the things found, where the
    //    tilt hardly matters. The Agitator isn't placed from these two alone.
    //  - either of them with the Agitator's left beaker (facing north; the beaker told by the next beaker
    //    beside it): the beaker is far behind them, so the tilt comes out too.
    //  - all three: also the camera's turn, better than the compass gives it.
    // Each answer has something that's told by more than being a grey ball: the beakers standing in a row, or
    // the Alembic's big bulb with its small bulb by it.
    // Nothing is placed from one thing alone: a lone grey ball could be anything. And a station only gets a place
    // when its own thing is one of those found (`Sights`): the arrow then goes right by something that was seen,
    // and what's a little out (the compass by up to 4 degrees, the tilt when it's assumed) hardly moves it.
    // Measured (spec.md, 17 November 2026): the Alembic and the Retort within 0.1 of a funnel spacing of where
    // the lever table puts them in views that have both, the Agitator within 0.11.
    //
    // It can't name the wrong station by taking a thing for another: the things must be where the room puts them
    // from each other for the compass's turn, each the size the room makes it there; if more than one answer
    // fits, none is given; and the tool wants two looks running to agree (`Sights`).

    // What a look without the table says: the camera's view, and which stations' own things were found
    // (`tiltRead` = the beaker was one of them, so the tilt was worked out and not assumed). `off` = how far the
    // things found are from where the view puts them, at worst, in funnel spacings.
    // `keep` = the part of the screen each found thing is in, with room round it (the bulb's, the pot's, the
    // beakers'): the tool's arrow stays out of those, or the next look couldn't see the thing.
    class Sight(val view: MixologyStations.View, private val keep: Array<MixologyStations.Area?>, val off: Double) {
        val alembic: Boolean get() = keep[0] != null
        val retort: Boolean get() = keep[1] != null
        val agitator: Boolean get() = keep[2] != null
        val things: Int get() = keep.count { it != null }

        // Where a station's own thing was found (null = it wasn't)
        fun found(station: MixologyStations.Station): MixologyStations.Area? = when (station) {
            MixologyStations.Station.ALEMBIC -> keep[0]
            MixologyStations.Station.RETORT -> keep[1]
            MixologyStations.Station.AGITATOR -> keep[2]
        }
    }

    // The smallest grey ball looked for over the whole screen, in screen pixels (zoomed out so far that they're
    // smaller, the lever table is on the screen too), how finely the screen is looked at for them, and the biggest
    private const val FREE_LEAST = 13.0
    private const val FREE_STEP = 4
    private const val FREE_BIGGEST = 80.0
    private const val FREE_MOST = 120
    // How much a thing's radius may differ from what the room makes it, without the table (the screen is looked
    // at more coarsely than along a path)
    private const val FREE_SIZE_LUMP = 0.16
    // The beaker's pale patch is whatever shows through the glass, so its size says little: from half to nearly
    // one and a half times the usual is let through (play_11.mp4: 0.11 of a spacing; stations_360.mp4: up to 0.22).
    // What tells the beaker is the next beaker beside it (`beakersFrom`).
    private const val FREE_BEAKER_SMALLEST = 0.5
    private const val FREE_BEAKER_BIGGEST = 1.45
    // A grey ball stands by itself: of the places one and a half radiuses out from its middle, at most this part
    // may be grey too (the cave's walls, a dark floor and the interface's stone are grey all round)
    private const val FREE_ALONE = 0.4
    // How far the things found may be from where the view puts them, in funnel spacings (only three things can
    // be off at all: two fit exactly)
    private const val FREE_OFF = 0.12
    // The tilt assumed when only the bulb and the pot are found (the middle of the owner's games: 41 to 55 degrees)
    private const val FREE_TILT = 47.0
    // How far the camera's turn may be taken from the compass's reading, in degrees. More than with the table:
    // the compass's reading moves in steps of about 4 degrees (play_12.mp4 at 72 to 75 s: -92.4, -88.6 and -83.8
    // within two seconds), and the line from the bulb to the pot, 6.7 spacings long, gives the turn far better.
    private const val FREE_TURN = 6.0
    // Two answers that put a station further apart than this, in funnel spacings, can't both be right
    private const val FREE_SAME = 0.6

    // Looks for the stations with no lever table on the screen. Null unless two or three of the things are found
    // in the places the room puts them from each other. `compassAngle` and `skip` as for `read`.
    fun readWithoutTable(p: LightBoxReader.PixelSource, compassAngle: Double, skip: List<MixologyStations.Area>): Sight? {
        val south = when {
            MixologyLevers.facingSouth(compassAngle) -> true
            MixologyLevers.facingNorth(compassAngle) -> false
            else -> return null
        }
        val w = p.width
        val h = p.height
        val allGreys = cores(p, 0, 0, w, h, FREE_STEP, FREE_LEAST, GREY, FREE_MOST, FREE_BIGGEST)
        val greys = allGreys.filter { clear(it, skip, w, h) && alone(p, it) }
        if (greys.isEmpty()) return null
        val a = Math.toRadians(compassAngle)
        val sinTurn = -kotlin.math.cos(a)
        val cosTurn = -kotlin.math.sin(a)
        val bulb = LUMPS[0]
        val pot = LUMPS[1]
        val beaker = LUMPS[2]
        // The Agitator's beaker, looked for only where it could be from a grey ball taken for the bulb or the pot
        // (a small part of the screen, looked at finely)
        fun beakersFrom(lump: Lump, core: Core): List<Core> {
            if (south) return emptyList()
            val s = core.radius / lump.radius    // screen pixels to a funnel spacing, at the ball
            val east = beaker.at[0] - lump.at[0]
            val north = beaker.at[1] - lump.at[1]
            val across = (cosTurn * east - sinTurn * north) * s
            val away = (sinTurn * east + cosTurn * north) * s
            if (away <= 0) return emptyList()
            val x1 = core.x + across * 0.65
            val x2 = core.x + across * 1.05
            val pad = 0.4 * s
            val step = (beaker.radius * s * 0.7 / 4).toInt().coerceIn(2, 4)
            val pales = cores(p, (minOf(x1, x2) - pad).toInt(), (core.y - away * 0.95 - pad).toInt(),
                (maxOf(x1, x2) + pad + 0.6 * s).toInt(), (core.y - away * 0.25 + pad).toInt(), step,
                beaker.radius * s * 0.35, PALE, 8)
            // The left beaker has the middle one beside it: another pale patch about as big, 0.3 to 0.55 of the
            // ball's spacing to its right and a little lower (measured on four views: 0.39 to 0.47, and 0.35 to
            // 0.5 as far down as across). A pale patch by itself is a flask, a paper or someone's clothes.
            return pales.filter { c ->
                clear(c, skip, w, h) && pales.any { d ->
                    val dx = d.x - c.x
                    val dy = d.y - c.y
                    d !== c && hypot(dx, dy) in 0.3 * s..0.55 * s && dx > 0 && dy > -0.3 * dx && dy < 1.1 * dx &&
                        d.radius > 0.6 * c.radius && d.radius < 1.6 * c.radius
                }
            }
        }
        // The Alembic's big bulb has its small bulb by it: a grey ball about 0.6 times as big (0.60 to 0.68 in
        // nine views), one and a half to three of the big one's radiuses from it, higher up the screen facing
        // north and lower facing south (it stands north of the big one)
        fun hasSmallBulb(big: Core) = allGreys.any { small ->
            val dy = (small.y - big.y) / big.radius
            small.radius > 0.5 * big.radius && small.radius < 0.8 * big.radius &&
                hypot(small.x - big.x, small.y - big.y) in 1.4 * big.radius..3.0 * big.radius &&
                (if (south) dy >= 0.7 else dy <= -1.0)
        }
        val fits = ArrayList<Sight>()
        fun tryThings(things: List<Pair<Lump, Core>>) {
            solve(things, compassAngle, sinTurn, cosTurn, south, w, h)?.let { fits.add(it) }
        }
        // The bulb and the pot together (the bulb is on the left facing north, on the right facing south), with
        // and without the beaker
        for (left in greys) for (right in greys) {
            if (left === right || left.x >= right.x) continue
            val r = (left.radius + right.radius) / 2
            val apart = right.x - left.x
            if (abs(left.radius - right.radius) > 0.3 * r || apart < 12 * r || apart > 27 * r ||
                abs(right.y - left.y) > 0.3 * apart) continue
            val b = if (south) right else left
            val q = if (south) left else right
            // (two grey balls alone could be a player's helmet and a pot on a shelf: with no beakers, the bulb
            // must have its small bulb)
            if (hasSmallBulb(b)) tryThings(listOf(bulb to b, pot to q))
            for (c in beakersFrom(bulb, b)) tryThings(listOf(bulb to b, pot to q, beaker to c))
        }
        // One of them with the beaker. Then the other must be out of sight: if it would be in plain view and
        // wasn't found with these two just above, something is wrong.
        for (g in greys) for (lump in listOf(bulb, pot)) {
            for (c in beakersFrom(lump, g)) {
                val sight = solve(listOf(lump to g, beaker to c), compassAngle, sinTurn, cosTurn, south, w, h) ?: continue
                val other = if (lump === bulb) pot else bulb
                val at = MixologyStations.viewSpot(sight.view, other.at, w, h)
                if (!clear(Core(at[0], at[1], other.radius * at[2] * 0.5), skip, w, h)) fits.add(sight)
            }
        }
        // The answer with the most things found, then the closest fit; every other answer with as many things
        // must put the Alembic and the Retort in the same places, or it isn't known which is right. (All three
        // things in their places is proof enough to pass over answers from two.)
        val best = fits.maxWithOrNull(compareBy<Sight> { it.things }.thenBy { -it.off }) ?: return null
        for (other in fits) {
            if (other === best || other.things < best.things) continue
            for (station in listOf(MixologyStations.Station.ALEMBIC, MixologyStations.Station.RETORT)) {
                val one = MixologyStations.viewStationSpot(best.view, station, w, h)
                val two = MixologyStations.viewStationSpot(other.view, station, w, h)
                if (hypot(one.x - two.x, one.y - two.y) > FREE_SAME * best.view.spacing) return null
            }
        }
        return best
    }

    // The camera's view that puts each thing on its circle, or null if none does. What's worked out depends on
    // what was found: always where the table would be and the zoom; the tilt if the beaker is one of the things
    // (else FREE_TILT is assumed); the turn if there are three things or the bulb and the pot (else the compass's).
    private fun solve(things: List<Pair<Lump, Core>>, compassAngle: Double, sinTurn: Double, cosTurn: Double,
                      south: Boolean, width: Int, height: Int): Sight? {
        val hasBeaker = things.any { it.first === LUMPS[2] }
        val free = booleanArrayOf(true, true, true, hasBeaker, things.size == 3 || !hasBeaker)
        // A start: the table's place and size guessed from the first thing
        val (lump0, core0) = things[0]
        val s0 = core0.radius / lump0.radius
        val v = DoubleArray(5)
        v[0] = core0.x + (cosTurn * -lump0.at[0] - sinTurn * -lump0.at[1]) * s0 * 1.1
        v[1] = core0.y - (sinTurn * -lump0.at[0] + cosTurn * -lump0.at[1]) * s0 * 0.75
        v[2] = s0 * (if (south) 0.8 else 1.25)
        v[3] = FREE_TILT
        v[4] = 0.0
        val n = things.size * 2
        fun view(q: DoubleArray) = MixologyStations.View(q[0], q[1], q[2].coerceAtLeast(5.0), compassAngle + q[4],
            q[3].coerceIn(MixologyStations.TILT_MIN - 8, MixologyStations.TILT_MAX + 8))
        fun misses(q: DoubleArray, out: DoubleArray) {
            val vw = view(q)
            for (i in things.indices) {
                val at = MixologyStations.viewSpot(vw, things[i].first.at, width, height)
                out[i * 2] = at[0] - things[i].second.x
                out[i * 2 + 1] = at[1] - things[i].second.y
            }
        }
        fun sum(r: DoubleArray): Double { var t = 0.0; for (x in r) t += x * x; return t }
        // Step by step towards the fit (each step: how the misses change with each number, then the change that
        // would cancel them; a step that makes things worse is tried again smaller)
        val idx = (0 until 5).filter { free[it] }
        val m = idx.size
        val nudge = doubleArrayOf(1.0, 1.0, 0.5, 0.2, 0.2)
        val r = DoubleArray(n)
        val r2 = DoubleArray(n)
        val jac = Array(n) { DoubleArray(m) }
        misses(v, r)
        var cost = sum(r)
        var damp = 1e-3
        var rounds = 0
        while (rounds++ < 30 && cost > 1e-4) {
            for (j in 0 until m) {
                val q = v.copyOf()
                q[idx[j]] += nudge[idx[j]]
                misses(q, r2)
                for (i in 0 until n) jac[i][j] = (r2[i] - r[i]) / nudge[idx[j]]
            }
            var better = false
            var tries = 0
            while (!better && tries++ < 8) {
                // (the usual sums, with a little added down the middle to keep the step short when it's unsure)
                val lhs = Array(m) { DoubleArray(m + 1) }
                for (x in 0 until m) {
                    for (y in 0 until m) { var t = 0.0; for (i in 0 until n) t += jac[i][x] * jac[i][y]; lhs[x][y] = t }
                    lhs[x][x] *= 1 + damp
                    var t = 0.0; for (i in 0 until n) t += jac[i][x] * r[i]
                    lhs[x][m] = -t
                }
                val step = solveLinear(lhs) ?: break
                val q = v.copyOf()
                for (j in 0 until m) q[idx[j]] += step[j]
                misses(q, r2)
                val c2 = sum(r2)
                if (c2 < cost) {
                    for (i in v.indices) v[i] = q[i]
                    for (i in 0 until n) r[i] = r2[i]
                    val gain = cost - c2
                    cost = c2
                    damp = (damp / 4).coerceAtLeast(1e-6)
                    better = true
                    if (gain < 1e-5) rounds = 99
                } else damp *= 6
            }
            if (!better) break
        }
        // Is it an answer? The tilt and the turn within what's possible, every thing on its circle and the size
        // the room makes it there
        if (free[3] && (v[3] <= MixologyStations.TILT_MIN + 0.5 || v[3] >= MixologyStations.TILT_MAX - 0.5)) return null
        if (abs(v[4]) > FREE_TURN || v[2] < 20) return null
        val vw = view(v)
        var worst = 0.0
        val keep = arrayOfNulls<MixologyStations.Area>(LUMPS.size)
        for ((lump, core) in things) {
            val at = MixologyStations.viewSpot(vw, lump.at, width, height)
            if (at[2] <= 0) return null
            // (the beakers: the left one, the middle one beside it, and their tops, which the circle doesn't reach)
            val room = core.radius * 1.25 + 3
            keep[LUMPS.indexOf(lump)] = if (lump === LUMPS[2]) {
                MixologyStations.Area((core.x - room).toFloat(), (core.y - 0.45 * at[2]).toFloat(),
                    (core.x + 0.65 * at[2]).toFloat(), (core.y + 0.4 * at[2]).toFloat())
            } else if (lump === LUMPS[0]) {
                // (the big bulb with its small bulb: above it facing north, below and to the right facing south)
                val r = core.radius
                MixologyStations.Area((core.x - 1.4 * r - 3).toFloat(), (core.y - (if (south) 1.25 else 3.3) * r - 3).toFloat(),
                    (core.x + (if (south) 2.8 else 2.0) * r + 3).toFloat(), (core.y + (if (south) 2.9 else 1.25) * r + 3).toFloat())
            } else {
                MixologyStations.Area((core.x - room).toFloat(), (core.y - room).toFloat(), (core.x + room).toFloat(),
                    (core.y + room).toFloat())
            }
            val size = core.radius / (lump.radius * at[2])
            if (lump === LUMPS[2]) { if (size < FREE_BEAKER_SMALLEST || size > FREE_BEAKER_BIGGEST) return null }
            else if (size < 1 - FREE_SIZE_LUMP || size > 1 + FREE_SIZE_LUMP) return null
            worst = maxOf(worst, hypot(at[0] - core.x, at[1] - core.y) / at[2])
        }
        if (worst > FREE_OFF) return null
        return Sight(vw, keep, worst)
    }

    // Whether a grey circle stands by itself: little grey a radius and a half out from its middle
    private fun alone(p: LightBoxReader.PixelSource, core: Core): Boolean {
        var grey = 0
        for (i in 0 until ALONE_PLACES) {
            val turn = 2 * Math.PI * i / ALONE_PLACES
            val x = (core.x + 1.5 * core.radius * kotlin.math.cos(turn)).toInt()
            val y = (core.y + 1.5 * core.radius * kotlin.math.sin(turn)).toInt()
            if (x >= 0 && y >= 0 && x < p.width && y < p.height && GREY.fits(p.rgb(x, y))) grey++
        }
        return grey <= FREE_ALONE * ALONE_PLACES
    }
    private const val ALONE_PLACES = 24

    // Solves a few sums at once (each row: the numbers, then what they must add up to). Null if they can't be.
    private fun solveLinear(rows: Array<DoubleArray>): DoubleArray? {
        val m = rows.size
        for (col in 0 until m) {
            var pivot = col
            for (i in col + 1 until m) if (abs(rows[i][col]) > abs(rows[pivot][col])) pivot = i
            if (abs(rows[pivot][col]) < 1e-12) return null
            val t = rows[col]; rows[col] = rows[pivot]; rows[pivot] = t
            for (i in 0 until m) {
                if (i == col) continue
                val f = rows[i][col] / rows[col][col]
                for (k in col..m) rows[i][k] -= f * rows[col][k]
            }
        }
        return DoubleArray(m) { rows[it][m] / rows[it][it] }
    }

    // The looks without the table, kept from look to look, and the rules for trusting them:
    //  - a station gets a place only when its own thing was found, on this look and on the one before (no longer
    //    ago than AGAIN_MS), in nearly the same place (the camera follows the character, so it may have moved a
    //    little): one look alone can be something else;
    //  - one look that misses (the character walked in front, the arrow's flash) changes nothing for HOLD_MS:
    //    the place from the look before stays.
    class Sights {
        companion object {
            const val AGAIN_MS = 600L
            const val HOLD_MS = 450L
            const val MOVED = 1.5     // funnel spacings a station may have moved on the screen between two looks
        }

        // A station's place: where its bar will be, how many screen pixels a funnel spacing is, and the part
        // of the screen its own thing is in (the arrow stays out of it)
        class Placed(val spot: MixologyStations.Spot, val spacing: Double, val keep: MixologyStations.Area)

        private var last: Sight? = null
        private var lastAt = 0L
        private var before: Sight? = null
        private var beforeAt = 0L
        private var held: Placed? = null
        private var heldFor: MixologyStations.Station? = null
        private var heldAt = 0L

        fun forget() { last = null; before = null; held = null; heldFor = null }

        // A look that tried (`sight` null = nothing surely found)
        fun look(now: Long, sight: Sight?) {
            before = last
            beforeAt = lastAt
            last = sight
            lastAt = now
        }

        // Where `station` is on the screen now, or null if the looks don't say for sure
        fun spot(now: Long, station: MixologyStations.Station, width: Int, height: Int): Placed? {
            val one = last
            val two = before
            if (one != null && two != null && now == lastAt && lastAt - beforeAt <= AGAIN_MS) {
                val keep = one.found(station)
                if (keep != null && two.found(station) != null) {
                    val a = MixologyStations.viewStationSpot(one.view, station, width, height)
                    val b = MixologyStations.viewStationSpot(two.view, station, width, height)
                    if (hypot(a.x - b.x, a.y - b.y) <= MOVED * one.view.spacing) {
                        val placed = Placed(a, one.view.spacing, keep)
                        held = placed
                        heldFor = station
                        heldAt = now
                        return placed
                    }
                }
            }
            return held?.takeIf { heldFor == station && now - heldAt <= HOLD_MS }
        }
    }

    // The tilts read from the stations' looks, kept from look to look, and which one places which station.
    // Each is kept by MixologyStations.Tilt's rules (two looks running must agree; forgotten when the camera is
    // turned or zoomed).
    //
    // Which tilt places which station (measured on the owner's recordings, spec.md, 16 November 2026):
    //  - each station by its own look first: the arrow then goes where the thing itself was found, whatever else
    //    is a little out;
    //  - a station whose own thing can't be seen, by another station's look: the Alembic's first, then the
    //    Retort's, then the Agitator's (the far, small beaker is the least exact). Since the camera's turn is taken
    //    from the row of funnels, the three agree: the Alembic's and the Retort's tilts are within 1.5 degrees of
    //    each other in 9 views of 10. If two of them are known and disagree by more than APART, one of them has
    //    hold of something else, and no place is given.
    class Tilts {
        companion object {
            // A look's tilt is used only while the table is within this many funnel spacings of where it was on
            // the screen when the station was last seen. The camera follows the character, and the tilt a lump
            // gives fits best in the view it was read in. While the lump is in sight it's read afresh on every
            // look, so this only matters once it's hidden.
            const val MOVED = 1.5
            // Two stations' looks further apart than this, in degrees, can't both be right
            const val APART = 3.0
        }

        // One station's look: its tilt, and where the table's middle was when the station was last seen
        private class Kept {
            val tilt = MixologyStations.Tilt()
            var seen = false
            var x = 0.0
            var y = 0.0

            fun look(row: MixologyLevers.Levers, compass: MixologyLevers.Compass, tiltNow: Double?, width: Int, height: Int) {
                tilt.seen(row, compass, tiltNow, width, height)
                if (tiltNow != null) { seen = true; x = row.funnels[1].x; y = row.funnels[1].y }
            }

            // Whether this look's tilt can be used for the row as it is now
            fun fresh(row: MixologyLevers.Levers) =
                seen && tilt.degrees != null && hypot(row.funnels[1].x - x, row.funnels[1].y - y) <= MOVED * row.spacing

            fun spot(row: MixologyLevers.Levers, compass: MixologyLevers.Compass, station: MixologyStations.Station,
                     width: Int, height: Int): MixologyStations.Spot? {
                if (!fresh(row)) return null
                return tilt.spot(row, compass, station, width, height)
            }

            fun forget() { tilt.forget(); seen = false }
        }

        private val alembic = Kept()
        private val retort = Kept()
        private val agitator = Kept()
        private val all = listOf(alembic, retort, agitator)

        // Whether any tilt is known (then the compass is worth reading even with no table on screen: a turn
        // makes it stale)
        val known: Boolean get() = all.any { it.tilt.degrees != null }

        fun forget() { for (k in all) k.forget() }

        // Every look that reads the compass (null = not found)
        fun compass(angle: Double?) { for (k in all) k.tilt.compass(angle) }

        // A look that tried to read the stations (`read` null = none was surely seen)
        fun look(row: MixologyLevers.Levers, compass: MixologyLevers.Compass, read: Read?, width: Int, height: Int) {
            alembic.look(row, compass, read?.alembic, width, height)
            retort.look(row, compass, read?.retort, width, height)
            agitator.look(row, compass, read?.agitator, width, height)
        }

        // Where `station` is on the screen now, or null if the looks don't say for sure
        fun spot(row: MixologyLevers.Levers, compass: MixologyLevers.Compass?, station: MixologyStations.Station,
                 width: Int, height: Int): MixologyStations.Spot? {
            if (compass == null) return null
            val own = when (station) {
                MixologyStations.Station.ALEMBIC -> alembic
                MixologyStations.Station.RETORT -> retort
                MixologyStations.Station.AGITATOR -> agitator
            }
            own.spot(row, compass, station, width, height)?.let { return it }
            // Another station's look: only if the ones that are known agree
            var first: MixologyStations.Spot? = null
            var firstTilt = 0.0
            for (other in all) {
                if (other === own) continue
                val spot = other.spot(row, compass, station, width, height) ?: continue
                val tilt = other.tilt.degrees ?: continue
                if (first == null) { first = spot; firstTilt = tilt }
                else if (abs(tilt - firstTilt) > APART) return null
            }
            return first
        }
    }
}

package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.LightBoxReader.PixelSource
import com.sinat.osrsbubbletool.PanelFinder.Box
import kotlin.math.abs
import kotlin.math.roundToInt

// The Mastering Mixology tool's bag tags: which Mixology potions are in the inventory, and where, so the tool can
// write each one's recipe ("MAL") over it, as the Mastering Mixology RuneLite plugin by Hexagon does.
//
// Each potion is known by its icon in brief (assets/mixology/potions.tsv, made by
// tools/mixology/build_potion_summaries.py): 9 x 8 squares of 4 x 4 game pixels, each with the item's average colour
// there and how much of the square it covers. No item pictures are kept. Both the potions fresh from the mixing
// vessel (no cork) and the ones a station has worked on (with a cork) are in the file, as the plugin tags both.
//
// The side panel can be see-through (it is on the owner's phone), so what's behind an item is the game's scene and
// can't be compared. Instead each inventory space is split into "the item" and "what's behind it" by the black
// outline the game draws round every item: everything that can't be reached from the edge of the space without
// crossing the outline is the item. Its shape and its colours are then compared with each potion's squares.
//
// Only the top 5 rows of squares are compared (the top 20 of the icon's 32 game pixels): the tag is written over the
// bottom of the icon, and with the whole screen shared the next look sees it. So the tag never changes what's read.
//
// No Android parts, so it's tested on a computer with the owner's recording (MixologyBagTest).
object MixologyBag {

    // The inventory: 4 across, 7 down. The game draws every item on a 36 x 32 canvas; the first one's corner is
    // (FIRST_X, FIRST_Y) game pixels from the side panel's top-left corner (as PanelFinder finds it), and they're
    // 42 apart across and 36 down. The panel is about 202.7 game pixels wide. Measured on the owner's phone.
    const val COLUMNS = 4
    const val ROWS = 7
    private const val PANEL_WIDTH = 202.7f
    private const val FIRST_X = 22.2f
    private const val FIRST_Y = 14.4f
    private const val STEP_X = 42f
    private const val STEP_Y = 36f

    private const val SQUARES_X = 9
    private const val SQUARES_Y = 8
    private const val SQUARE = 4          // game pixels a side
    const val FIRST_FINISHED = 30021      // the finished potions' item IDs start here (the plugin's MixologyPotion)
    const val USED_ROWS = 5               // rows of squares compared (must match build_potion_summaries.py)
    private const val USED = SQUARES_X * USED_ROWS

    // The part of a space that's looked at, in game pixels from where the canvas should be: a little more than the
    // rows compared, as the canvas is looked for up to NUDGE either way
    private const val NUDGE = 2f
    private const val NUDGE_STEP = 0.5f
    private const val AREA_LEFT = -3f
    private const val AREA_RIGHT = 39f
    private const val AREA_TOP = -3f
    private const val AREA_BOTTOM = USED_ROWS * SQUARE + NUDGE + 0.5f

    private const val EDGE_PARTS = 3       // the quick look: a pixel under 1 / this as bright as one 3 along
    private const val MIN_EDGES = 8        // fewer of those than this in every other row: an empty space
    private const val OUTLINE_PARTS = 2    // outline = under 1 / this as bright as the brightest pixel near it

    // How well a space must fit to be tagged (see MixologyBagTest for what the owner's recording gives)
    const val MAX_SHAPE = 0.22f            // its shape: 0 = the same squares covered, 1 = nothing in common
    const val MAX_COLOUR = 22f             // its colours: how far apart, a square (see colourApart)
    const val MIN_MARGIN = 5f              // every other kind's colours must fit this much worse,
    const val MIN_TIMES = 1.3f             // and this many times worse
    private const val WHOLE = 0.99f        // a square the item covers this much of counts as covered whole
    const val STILL_APART = 30             // a tagged potion's pixel may change this much and still be "the same"

    // Where the tag is written, in game pixels from the canvas's corner: below the rows compared, so the tool's own
    // letters (which the next look sees) never change what's read. Near where the plugin writes its tags.
    const val TAG_LEFT = 4f
    const val TAG_BASELINE = 31f
    const val TAG_SIZE = 10.5f             // the letters' size (capitals come out about 7.5 tall)

    // How bright the game draws a colour at each brightness setting (as GearSummaryReader): the icons in the file
    // are RuneLite's, drawn at one setting; the player's may differ
    private val GAMMAS = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f)

    // One potion's icon in brief: its recipe, and for each square its colour and how much of it the item covers (0-1)
    class Summary(val id: Int, val recipe: String, val red: IntArray, val green: IntArray, val blue: IntArray, val cover: FloatArray) {
        // (its colours at each brightness setting, worked out once)
        internal val shaded: Array<IntArray> = Array(GAMMAS.size) { g ->
            val lut = GearSummaryReader.shading(GAMMAS[g])
            IntArray(USED * 3) { i -> lut[when (i % 3) { 0 -> red[i / 3]; 1 -> green[i / 3]; else -> blue[i / 3] }] }
        }
    }

    // Reads assets/mixology/potions.tsv
    fun readSummaries(text: String): List<Summary> = text.lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.map { line ->
        val f = line.split('\t')
        val hex = f[2].trim()
        val n = SQUARES_X * SQUARES_Y
        require(hex.length == n * 8) { "not a potion summary" }
        fun byte(i: Int) = hex.substring(i * 2, i * 2 + 2).toInt(16)
        Summary(f[0].toInt(), f[1], IntArray(n) { byte(it * 4) }, IntArray(n) { byte(it * 4 + 1) }, IntArray(n) { byte(it * 4 + 2) },
            FloatArray(n) { byte(it * 4 + 3) / 255f })
    }.toList()

    // One potion found: its inventory space (0-27, row by row), its recipe, where its 36 x 32 canvas is on screen
    // (left, top; `scale` = screen pixels per game pixel), how well it fitted, and a few spots inside it with their
    // colours, for `stillThere`. `finished` = a station has worked on it (the corked bottle, item IDs 30021-30030;
    // fresh from the mixing vessel it's 30011-30020, an open neck).
    class Tag(val slot: Int, val recipe: String, val finished: Boolean, val left: Float, val top: Float, val scale: Float,
              val shape: Float, val colour: Float, val margin: Float,
              internal val spotX: IntArray, internal val spotY: IntArray, internal val spotRgb: IntArray)

    // Where space `slot`'s canvas should be (left, top), and the scale
    private fun scaleOf(panel: Box) = panel.width / PANEL_WIDTH
    private fun canvasLeft(panel: Box, slot: Int) = panel.left + (FIRST_X + (slot % COLUMNS) * STEP_X) * scaleOf(panel)
    private fun canvasTop(panel: Box, slot: Int) = panel.top + (FIRST_Y + (slot / COLUMNS) * STEP_Y) * scaleOf(panel)

    // Every Mixology potion in the inventory. `panel` = the side panel (PanelFinder.findPanel), with the inventory
    // tab open. A space it isn't sure about is left out: no tag is better than a wrong one.
    fun read(p: PixelSource, panel: Box, summaries: List<Summary>): List<Tag> {
        val scratch = Scratch()
        val tags = ArrayList<Tag>()
        for (slot in 0 until COLUMNS * ROWS) readSlot(p, panel, slot, summaries, scratch)?.let { tags.add(it) }
        return tags
    }

    // Whether a tagged potion still looks as it did when it was read (a few pixels: far cheaper than reading the bag)
    fun stillThere(p: PixelSource, tag: Tag): Boolean {
        for (i in tag.spotX.indices) {
            val x = tag.spotX[i]; val y = tag.spotY[i]
            if (x < 0 || y < 0 || x >= p.width || y >= p.height) return false
            val c = p.rgb(x, y); val was = tag.spotRgb[i]
            val apart = abs(((c shr 16) and 0xFF) - ((was shr 16) and 0xFF)) + abs(((c shr 8) and 0xFF) - ((was shr 8) and 0xFF)) +
                abs((c and 0xFF) - (was and 0xFF))
            if (apart > STILL_APART) return false
        }
        return true
    }

    // The tool's rules for the bag, look after look: where the side panel is, when the bag is read again, and which
    // tags are shown. Finding the side panel means going over the whole screen (as much work as several looks), and
    // reading the bag every look would cost too much too, so:
    //  - the side panel is looked for only until it's found (every PANEL_EVERY_MS at first, then every
    //    PANEL_LATER_MS). It doesn't move, so after that a look only checks its tab buttons are still beside it and
    //    the inventory's is the open one;
    //  - the bag is read once every READ_EVERY_MS, or at once when a tagged potion no longer looks as it did, or
    //    when the tool asks (`soon`: on every full look while there's no station to go to, and at a station's end);
    //  - between reads, each tag stays only while a few pixels inside its potion haven't changed.
    // A potion that was tagged keeps its tag through a read that isn't sure of it (a lever dot or the game's menu over
    // part of it), as long as those pixels haven't changed.
    class Watch {
        companion object {
            const val READ_EVERY_MS = 1_000L
            const val PANEL_EVERY_MS = 2_000L
            const val PANEL_TRIES = 5            // after this many searches without finding it,
            const val PANEL_LATER_MS = 10_000L   // look for it only this often
            const val RECHECK_MS = 60_000L       // with a panel but nothing tagged: look for it afresh this often
            // The side panel is 1.35 times as tall as wide (578 by 427 pixels on the owner's phone). In a dark scene
            // the finder can take a taller box (676 by 431 was seen) that has the tab buttons beside it too.
            private const val MIN_SHAPE = 1.28f
            private const val MAX_SHAPE = 1.43f
            private const val SAME_PLACE = 3     // pixels a panel's corner may differ and still be the same panel
        }

        var panel: Box? = null
            private set
        var tags: List<Tag> = emptyList()
            private set
        var open = false     // the inventory was open on the last look, so `tags` is the whole bag's potions
            private set
        private var searchedAt = Long.MIN_VALUE / 2
        private var readAt = Long.MIN_VALUE / 2
        private var tries = 0
        var searches = 0     // how many times the panel was looked for and the bag was read (for the tests)
            private set
        var reads = 0
            private set

        fun forget() {
            panel = null
            tags = emptyList()
            open = false
            searchedAt = Long.MIN_VALUE / 2
            readAt = Long.MIN_VALUE / 2
            tries = 0
        }

        // Nothing to tag now (no order box on screen, the game hidden): the tags go, the panel is remembered
        fun hide() { tags = emptyList(); open = false }

        private fun search(p: PixelSource, now: Long): Box? {
            searchedAt = now
            searches++
            return PanelFinder.findPanel(p)?.takeIf { it.height >= it.width * MIN_SHAPE && it.height <= it.width * MAX_SHAPE }
        }

        // One look, with the Potion Orders box on screen. Returns the tags to show. `soon` = read the bag on this
        // look even if it was read less than READ_EVERY_MS ago (the tool asks for that while a potion arriving in
        // the bag would change where to go next).
        fun look(p: PixelSource, now: Long, summaries: List<Summary>, soon: Boolean = false): List<Tag> {
            var box = panel
            open = false
            if (box == null) {
                tags = emptyList()
                if (now - searchedAt < (if (tries < PANEL_TRIES) PANEL_EVERY_MS else PANEL_LATER_MS)) return tags
                tries++
                box = search(p, now) ?: return tags
                panel = box
                readAt = Long.MIN_VALUE / 2
            }
            if (PanelFinder.stillThere(p, box) && PanelFinder.tabOpen(p, box, 0, PanelFinder.INVENTORY_TAB)) {
                open = true
                val kept = tags.filter { stillThere(p, it) }
                if (kept.size != tags.size || soon || now - readAt >= READ_EVERY_MS) {
                    readAt = now
                    reads++
                    val fresh = read(p, box, summaries)
                    // (a potion read as before keeps its old tag, so its letters don't hop about by a pixel)
                    tags = (fresh.map { f -> kept.firstOrNull { it.slot == f.slot && it.recipe == f.recipe && it.finished == f.finished } ?: f } +
                        kept.filter { k -> fresh.none { it.slot == k.slot } }).sortedBy { it.slot }
                } else tags = kept
            } else tags = emptyList()
            // Nothing tagged for a long while: in case the panel remembered is a wrong one after all, look afresh now
            // and then, and take what's found if it's somewhere else
            if (tags.isEmpty() && now - searchedAt >= RECHECK_MS) {
                val found = search(p, now)
                if (found != null && (abs(found.left - box.left) > SAME_PLACE || abs(found.top - box.top) > SAME_PLACE ||
                        abs(found.width - box.width) > 2 * SAME_PLACE)) {
                    panel = found
                    readAt = Long.MIN_VALUE / 2
                }
            }
            return tags
        }
    }

    // Working space, made once a read and used for every inventory space
    private class Scratch {
        var size = 0
        var open = BooleanArray(0)      // not outline
        var roomy = BooleanArray(0)     // open, with open pixels all round it
        var reached = BooleanArray(0)   // roomy and joined to the area's edge
        var stack = IntArray(0)
        var count = IntArray(0)         // the four running totals (each one row and one column bigger than the area):
        var red = IntArray(0)           // how many item pixels, and their red, green and blue, above-left of each spot
        var green = IntArray(0)
        var blue = IntArray(0)
        var rgb = IntArray(0)
        var light = IntArray(0)         // red + green + blue
        var rowMost = IntArray(0)
        val cover = FloatArray(USED)
        var shapes: List<Summary> = emptyList()   // the summaries with different shapes, of the list `shapesOf`
        var shapesOf: List<Summary>? = null
        fun fit(pixels: Int, totals: Int) {
            if (pixels > size) {
                size = pixels
                open = BooleanArray(pixels); roomy = BooleanArray(pixels); reached = BooleanArray(pixels)
                stack = IntArray(pixels); rgb = IntArray(pixels); light = IntArray(pixels); rowMost = IntArray(pixels)
            }
            if (totals > count.size) { count = IntArray(totals); red = IntArray(totals); green = IntArray(totals); blue = IntArray(totals) }
        }
    }

    // The fit of one space, for the tests and for tuning: the best kind's recipe with its shape and colour fit and
    // how much worse the next kind fits, whether or not it's good enough to tag (null for an empty space)
    class Fit(val recipe: String, val shape: Float, val colour: Float, val margin: Float)

    fun fitOf(p: PixelSource, panel: Box, slot: Int, summaries: List<Summary>): Fit? {
        val found = arrayOfNulls<Fit>(1)
        readSlot(p, panel, slot, summaries, Scratch(), found)
        return found[0]
    }

    private fun readSlot(p: PixelSource, panel: Box, slot: Int, summaries: List<Summary>, s: Scratch, report: Array<Fit?>? = null): Tag? {
        val scale = scaleOf(panel)
        val cx = canvasLeft(panel, slot); val cy = canvasTop(panel, slot)
        val x0 = (cx + AREA_LEFT * scale).toInt(); val y0 = (cy + AREA_TOP * scale).toInt()
        val x1 = (cx + AREA_RIGHT * scale).toInt() + 1; val y1 = (cy + AREA_BOTTOM * scale).toInt() + 1
        if (x0 < 0 || y0 < 0 || x1 > p.width || y1 > p.height) return null
        val w = x1 - x0; val h = y1 - y0
        s.fit(w * h, (w + 1) * (h + 1))

        // A quick look first (every other row): an item's outline shows as pixels far darker than the one 3 to their
        // left or right. Hardly any = nothing in this space.
        var edges = 0
        var py = y0
        while (py < y1) {
            var before3 = 0; var before2 = 0; var before1 = 0
            for (px in x0 until x1) {
                val c = p.rgb(px, py)
                val light = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
                if (px >= x0 + 3 && (light * EDGE_PARTS < before3 || before3 * EDGE_PARTS < light)) edges++
                before3 = before2; before2 = before1; before1 = light
            }
            py += 2
        }
        if (edges < MIN_EDGES) return null

        // The outline: every pixel under half as bright as the brightest within 2 pixels of it. (Not "darker than
        // some set value": stretched to the screen's size the outline's corners are only half dark, and a dark cave
        // behind the item is darker than that.)
        var a = 0
        for (yy in y0 until y1) for (xx in x0 until x1) {
            val c = p.rgb(xx, yy)
            s.rgb[a] = c
            s.light[a++] = ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
        }
        for (yy in 0 until h) for (xx in 0 until w) {      // the brightest of 5 in a row,
            var most = 0
            for (k in maxOf(0, xx - 2)..minOf(w - 1, xx + 2)) if (s.light[yy * w + k] > most) most = s.light[yy * w + k]
            s.rowMost[yy * w + xx] = most
        }
        for (yy in 0 until h) for (xx in 0 until w) {      // then of 5 rows
            var most = 0
            for (k in maxOf(0, yy - 2)..minOf(h - 1, yy + 2)) if (s.rowMost[k * w + xx] > most) most = s.rowMost[k * w + xx]
            s.open[yy * w + xx] = s.light[yy * w + xx] * OUTLINE_PARTS >= most
        }

        // What's behind the item: everything a 3 x 3 pixel brush can reach from the top, left and right edges without
        // touching the outline. (A brush, not a single pixel: the outline's corners only touch at their tips, and
        // stretched to the screen's size a single pixel slips between them. The bottom edge is left out: the item
        // carries on below it.)
        for (y in 0 until h) for (x in 0 until w) {
            var roomy = true
            var yy = y - 1
            while (roomy && yy <= y + 1) {
                var xx = x - 1
                while (xx <= x + 1) {
                    if (yy in 0 until h && xx in 0 until w && !s.open[yy * w + xx]) { roomy = false; break }
                    xx++
                }
                yy++
            }
            s.roomy[y * w + x] = roomy
            s.reached[y * w + x] = false
        }
        var top = 0
        for (y in 0 until h) for (x in 0 until w) {
            if (y != 0 && x != 0 && x != w - 1) continue
            val i = y * w + x
            if (s.roomy[i] && !s.reached[i]) { s.reached[i] = true; s.stack[top++] = i }
        }
        while (top > 0) {
            val i = s.stack[--top]
            val x = i % w; val y = i / w
            if (x > 0 && s.roomy[i - 1] && !s.reached[i - 1]) { s.reached[i - 1] = true; s.stack[top++] = i - 1 }
            if (x < w - 1 && s.roomy[i + 1] && !s.reached[i + 1]) { s.reached[i + 1] = true; s.stack[top++] = i + 1 }
            if (y > 0 && s.roomy[i - w] && !s.reached[i - w]) { s.reached[i - w] = true; s.stack[top++] = i - w }
            if (y < h - 1 && s.roomy[i + w] && !s.reached[i + w]) { s.reached[i + w] = true; s.stack[top++] = i + w }
        }
        // The item: every pixel the brush didn't cover. Kept as running totals, so any square's share and colour
        // take four look-ups.
        val tw = w + 1
        for (x in 0..w) { s.count[x] = 0; s.red[x] = 0; s.green[x] = 0; s.blue[x] = 0 }
        for (y in 0 until h) {
            var rowCount = 0; var rowRed = 0; var rowGreen = 0; var rowBlue = 0
            val t = (y + 1) * tw
            s.count[t] = 0; s.red[t] = 0; s.green[t] = 0; s.blue[t] = 0
            for (x in 0 until w) {
                var behindIt = false
                var yy = maxOf(0, y - 1)
                while (!behindIt && yy <= minOf(h - 1, y + 1)) {
                    var xx = maxOf(0, x - 1)
                    while (xx <= minOf(w - 1, x + 1)) { if (s.reached[yy * w + xx]) { behindIt = true; break }; xx++ }
                    yy++
                }
                if (!behindIt) {
                    val c = s.rgb[y * w + x]
                    rowCount++; rowRed += (c shr 16) and 0xFF; rowGreen += (c shr 8) and 0xFF; rowBlue += c and 0xFF
                }
                s.count[t + x + 1] = s.count[t - tw + x + 1] + rowCount
                s.red[t + x + 1] = s.red[t - tw + x + 1] + rowRed
                s.green[t + x + 1] = s.green[t - tw + x + 1] + rowGreen
                s.blue[t + x + 1] = s.blue[t - tw + x + 1] + rowBlue
            }
        }

        // Where the canvas is: the place (up to NUDGE game pixels either way) where the item's shape fits some
        // potion's best
        var bestShape = Float.MAX_VALUE
        var bestDx = 0f; var bestDy = 0f
        val steps = (2 * NUDGE / NUDGE_STEP).roundToInt()
        // (the ten kinds share two shapes, with a cork and without: each different shape is tried once)
        if (s.shapesOf !== summaries) {
            s.shapes = summaries.filterIndexed { i, m -> (0 until i).none { summaries[it].cover.contentEquals(m.cover) } }
            s.shapesOf = summaries
        }
        for (iy in 0..steps) for (ix in 0..steps) {
            val dx = -NUDGE + ix * NUDGE_STEP; val dy = -NUDGE + iy * NUDGE_STEP
            covers(s, w, h, cx + dx * scale - x0, cy + dy * scale - y0, scale)
            for (m in s.shapes) {
                val shape = shapeApart(s.cover, m.cover)
                if (shape < bestShape) { bestShape = shape; bestDx = dx; bestDy = dy }
            }
        }
        if (bestShape > MAX_SHAPE && report == null) return null

        // Which potion: the one whose colours fit best there (at the brightness setting that suits it best), if it
        // fits well and every other kind fits clearly worse
        val left = cx + bestDx * scale; val topY = cy + bestDy * scale
        covers(s, w, h, left - x0, topY - y0, scale)
        var best: Summary? = null
        var bestColour = Float.MAX_VALUE
        var nextColour = Float.MAX_VALUE
        val fits = FloatArray(summaries.size) { colourApart(s, w, h, left - x0, topY - y0, scale, summaries[it]) }
        for (i in summaries.indices) if (fits[i] < bestColour) { bestColour = fits[i]; best = summaries[i] }
        val kind = best ?: return null
        for (i in summaries.indices) if (summaries[i].recipe != kind.recipe && fits[i] < nextColour) nextColour = fits[i]
        val shape = shapeApart(s.cover, kind.cover)
        val margin = nextColour - bestColour
        report?.set(0, Fit(kind.recipe, shape, bestColour, margin))
        if (shape > MAX_SHAPE || bestColour > MAX_COLOUR || margin < MIN_MARGIN || nextColour < bestColour * MIN_TIMES) return null

        // A few spots well inside the item (four in each square it covers whole), to tell cheaply if it's still
        // there next look
        val xs = ArrayList<Int>(); val ys = ArrayList<Int>()
        for (sq in 0 until USED) {
            if (kind.cover[sq] < WHOLE) continue
            for (k in 0 until 4) {
                xs.add((left + ((sq % SQUARES_X) * SQUARE + 1 + 2 * (k % 2)) * scale).toInt())
                ys.add((topY + ((sq / SQUARES_X) * SQUARE + 1 + 2 * (k / 2)) * scale).toInt())
            }
        }
        val spotX = xs.toIntArray(); val spotY = ys.toIntArray()
        return Tag(slot, kind.recipe, kind.id >= FIRST_FINISHED, left, topY, scale, shape, bestColour, margin, spotX, spotY,
            IntArray(spotX.size) { p.rgb(spotX[it].coerceIn(0, p.width - 1), spotY[it].coerceIn(0, p.height - 1)) })
    }

    // A square's edges in the area's pixels, for a canvas whose corner is at (left, top) in the area
    private fun edgeAt(start: Float, squares: Int, scale: Float, max: Int) = (start + squares * SQUARE * scale).roundToInt().coerceIn(0, max)

    private fun total(t: IntArray, tw: Int, xa: Int, ya: Int, xb: Int, yb: Int) = t[yb * tw + xb] - t[ya * tw + xb] - t[yb * tw + xa] + t[ya * tw + xa]

    // How much of each compared square is item, with the canvas's corner at (left, top) in the area
    private fun covers(s: Scratch, w: Int, h: Int, left: Float, top: Float, scale: Float) {
        for (sq in 0 until USED) {
            val xa = edgeAt(left, sq % SQUARES_X, scale, w); val xb = edgeAt(left, sq % SQUARES_X + 1, scale, w)
            val ya = edgeAt(top, sq / SQUARES_X, scale, h); val yb = edgeAt(top, sq / SQUARES_X + 1, scale, h)
            val area = (xb - xa) * (yb - ya)
            s.cover[sq] = if (area <= 0) 0f else total(s.count, w + 1, xa, ya, xb, yb) / area.toFloat()
        }
    }

    // How unlike two shapes are: 0 = the same squares covered as much, 1 = nothing in common
    private fun shapeApart(seen: FloatArray, icon: FloatArray): Float {
        var apart = 0f; var all = 0f
        for (sq in 0 until USED) { apart += abs(seen[sq] - icon[sq]); all += maxOf(seen[sq], icon[sq]) }
        return if (all <= 0f) 1f else apart / all
    }

    // How unlike the item's colours and a potion's are, a square, over the squares the potion covers whole (its glass
    // and what's in it, clear of the outline): mostly how the colour itself differs (red against green, green
    // against blue), and half as much how bright it is, since brightness is what the game's brightness setting and
    // the blurred outline change. At the brightness setting that fits best.
    private fun colourApart(s: Scratch, w: Int, h: Int, left: Float, top: Float, scale: Float, m: Summary): Float {
        var best = Float.MAX_VALUE
        for (g in GAMMAS.indices) {
            val shaded = m.shaded[g]
            var apart = 0f; var squares = 0
            for (sq in 0 until USED) {
                if (m.cover[sq] < WHOLE) continue
                val xa = edgeAt(left, sq % SQUARES_X, scale, w); val xb = edgeAt(left, sq % SQUARES_X + 1, scale, w)
                val ya = edgeAt(top, sq / SQUARES_X, scale, h); val yb = edgeAt(top, sq / SQUARES_X + 1, scale, h)
                val n = total(s.count, w + 1, xa, ya, xb, yb)
                squares++
                if (n == 0) { apart += 255f; continue }
                val dr = total(s.red, w + 1, xa, ya, xb, yb) / n.toFloat() - shaded[sq * 3]
                val dg = total(s.green, w + 1, xa, ya, xb, yb) / n.toFloat() - shaded[sq * 3 + 1]
                val db = total(s.blue, w + 1, xa, ya, xb, yb) / n.toFloat() - shaded[sq * 3 + 2]
                apart += abs(dr - dg) + abs(dg - db) + abs(dr + dg + db) / 6f
            }
            if (squares > 0 && apart / squares < best) best = apart / squares
        }
        return best
    }
}

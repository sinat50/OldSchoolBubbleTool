package com.sinat.osrsbubbletool

import android.graphics.Rect
import com.sinat.osrsbubbletool.PanelFinder.Box
import kotlin.math.abs
import kotlin.math.roundToInt

// Finds an open puzzle box on the screen by the lines between its tiles, and spots the empty space quickly.
// The game's brightness setting changes the colour of the lines and the frame (checked on the owner's phone,
// November 2026), but not the empty space, which is always the same dark brown.
object PuzzleFinder {

    private const val GRID = 5
    private const val MIN_LINE = 40          // a line between tiles is at least this long (in half-size pixels)
    private const val MAX_LINE_WIDTH = 8     // and at most this thick
    private const val FRAME_SPOTS_NEEDED = 8 // of the 12 spots around the tiles, for the frame to count as there
    private const val FRAME_CHANGE = 40      // how much a frame spot's colour may change (red + green + blue)

    // The empty space's dark brown, which the game's brightness setting doesn't change
    private fun isWood(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return b < 14 && r in 28..100 && g in 15..65 && r * 10 >= g * 14
    }

    // The lines between the tiles: dark brown with no blue in it. The brightness setting makes them darker or
    // lighter (about 30,16,0 at its lowest, 53,33,0 at 50%, 94,70,0 at its highest), but never adds blue.
    private fun isLine(c: Int): Boolean {
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF
        return b <= 6 && r in 22..115 && g * 100 >= r * 40 && g * 100 <= r * 85
    }

    // Where the 5 x 5 tiles are, or null if there's no puzzle box. Found by the lines between the tiles, which
    // works at any brightness. (It used to be found by the frame's colour, which only worked near 50% brightness
    // and once mistook a frame-coloured corner of a wall in the Tombs of Amascut for a tiny puzzle.)
    fun findTiles(p: LightBoxReader.PixelSource): Rect? = findBox(p)?.toRect()

    // The same as a plain box (so it can be tested on the PC)
    // The 4 lines each way between the tiles are evenly spaced, with the same spacing across and down, each about
    // 5 tiles long (a bit more when the frame is the same colour), and crossing each other.
    fun findBox(p: LightBoxReader.PixelSource): Box? {
        // 1. Line-coloured pixels, on a half-size grid to keep it quick
        val w = p.width / 2
        val h = p.height / 2
        if (w < 20 || h < 20) return null
        val mask = BooleanArray(w * h)
        for (y in 0 until h) for (x in 0 until w) mask[y * w + x] = isLine(p.rgb(x * 2, y * 2))

        // 2. The longest straight run of them down each column and along each row
        val downLength = IntArray(w); val downStart = IntArray(w)
        val run = IntArray(w)
        for (y in 0 until h) for (x in 0 until w) {
            run[x] = if (mask[y * w + x]) run[x] + 1 else 0
            if (run[x] > downLength[x]) { downLength[x] = run[x]; downStart[x] = y - run[x] + 1 }
        }
        val acrossLength = IntArray(h); val acrossStart = IntArray(h)
        for (y in 0 until h) {
            var n = 0
            for (x in 0 until w) {
                n = if (mask[y * w + x]) n + 1 else 0
                if (n > acrossLength[y]) { acrossLength[y] = n; acrossStart[y] = x - n + 1 }
            }
        }

        // 3. Sets of 4 evenly spaced lines each way, matched up: same spacing, and crossing each other
        val downs = evenlySpaced(lines(downLength, downStart))
        val acrosses = evenlySpaced(lines(acrossLength, acrossStart))
        var best: Box? = null
        var bestSpacing = 0f
        for ((sx, vertical) in downs) for ((sy, horizontal) in acrosses) {
            if (abs(sx - sy) > 0.06f * sx) continue
            val left = vertical[0].middle - sx
            val right = vertical[3].middle + sx
            val top = horizontal[0].middle - sy
            val bottom = horizontal[3].middle + sy
            if (vertical.any { it.start > top + sy * 0.6f || it.end < bottom - sy * 0.6f }) continue
            if (horizontal.any { it.start > left + sx * 0.6f || it.end < right - sx * 0.6f }) continue
            if (sx > bestSpacing) {   // the biggest, if there's ever more than one
                bestSpacing = sx
                best = Box((left * 2 + 1).roundToInt(), (top * 2 + 1).roundToInt(),
                    (right * 2 + 1).roundToInt(), (bottom * 2 + 1).roundToInt())
            }
        }
        return best
    }

    // A line between tiles: where its middle is, and where it starts and ends (half-size pixels)
    private class Line(val middle: Float, val start: Int, val end: Int) {
        val length get() = end - start
    }

    // Lines from the longest run in each column (or row): the strongest first, each with the columns beside it
    // that are nearly as long. Too thick to be a line (the empty space beside a line, say) is left out.
    private fun lines(length: IntArray, start: IntArray): List<Line> {
        val used = BooleanArray(length.size)
        val found = ArrayList<Line>()
        for (x in length.indices.sortedByDescending { length[it] }) {
            if (length[x] < MIN_LINE) break
            if (used[x]) continue
            val peak = length[x]
            var a = x
            var b = x
            while (a - 1 >= 0 && !used[a - 1] && length[a - 1] * 10 >= peak * 7) a--
            while (b + 1 < length.size && !used[b + 1] && length[b + 1] * 10 >= peak * 7) b++
            for (i in maxOf(0, a - 2)..minOf(length.size - 1, b + 2)) used[i] = true
            if (b - a + 1 > MAX_LINE_WIDTH) continue
            val starts = (a..b).map { start[it] }.sorted()
            val ends = (a..b).map { start[it] + length[it] }.sorted()
            found.add(Line((a + b) / 2f, starts[starts.size / 2], ends[ends.size / 2]))
        }
        return found.sortedBy { it.middle }
    }

    // Every set of 4 lines with even spacing that are each about 5 spacings long, with the spacing
    private fun evenlySpaced(lines: List<Line>): List<Pair<Float, List<Line>>> {
        val sets = ArrayList<Pair<Float, List<Line>>>()
        for (i in lines.indices) for (j in i + 1 until lines.size) {
            val spacing = (lines[j].middle - lines[i].middle) / 3f
            if (spacing < 10f) continue
            val tolerance = maxOf(1.5f, spacing * 0.04f)
            val set = ArrayList<Line>(4)
            for (n in 0 until 4) {
                val want = lines[i].middle + n * spacing
                val nearest = lines.minByOrNull { abs(it.middle - want) } ?: break
                if (abs(nearest.middle - want) > tolerance) break
                set.add(nearest)
            }
            if (set.size < 4) continue
            if (set.any { it.length < 4.3f * spacing || it.length > 6.6f * spacing }) continue
            sets.add(spacing to set)
        }
        return sets
    }

    // Which space (0-24) is empty, checked from 9 pixels in each tile: the empty space is plain
    // dark wood. Returns -1 if there isn't exactly one (a tile is mid-slide, or something covers it).
    // Tiles in `covered` have a guide dot in the middle, so they're read from a ring of 8 pixels
    // around the dot instead.
    fun emptySpace(p: LightBoxReader.PixelSource, tiles: Rect, covered: Set<Int> = emptySet()) =
        emptySpace(p, tiles.box(), covered)

    fun emptySpace(p: LightBoxReader.PixelSource, tiles: Box, covered: Set<Int> = emptySet()): Int {
        val tileW = tiles.width / GRID.toFloat()
        val tileH = tiles.height / GRID.toFloat()
        var found = -1
        for (pos in 0 until GRID * GRID) {
            val col = pos % GRID
            val row = pos / GRID
            var empty = true
            var minR = 255; var maxR = 0
            val spots = if (pos in covered) RING else GRID_SPOTS
            loop@ for ((fx, fy) in spots) {
                val x = (tiles.left + (col + fx) * tileW).toInt()
                val y = (tiles.top + (row + fy) * tileH).toInt()
                if (x < 0 || y < 0 || x >= p.width || y >= p.height) return -1
                val c = p.rgb(x, y)
                if (!isWood(c)) { empty = false; break@loop }
                val r = (c shr 16) and 0xFF
                if (r < minR) minR = r
                if (r > maxR) maxR = r
            }
            if (empty && abs(maxR - minR) <= 30) {
                if (found != -1) return -1
                found = pos
            }
        }
        return found
    }

    // The frame's colour at 12 spots around the tiles, read when the puzzle is found, so frameVisible can tell
    // whether it's still there (the brightness setting changes the frame's colour, so it can't be fixed in the
    // code). A spot off the screen is -1.
    fun frameColours(p: LightBoxReader.PixelSource, tiles: Rect) = frameColours(p, tiles.box())

    fun frameColours(p: LightBoxReader.PixelSource, tiles: Box): IntArray {
        val spots = frameSpots(tiles)
        return IntArray(spots.size / 2) { i ->
            val x = spots[i * 2]
            val y = spots[i * 2 + 1]
            if (x in 0 until p.width && y in 0 until p.height) p.rgb(x, y) and 0xFFFFFF else -1
        }
    }

    // Is the wooden frame still there around these tiles, looking as it did when the puzzle was found?
    // (It disappears when the puzzle closes.)
    fun frameVisible(p: LightBoxReader.PixelSource, tiles: Rect, colours: IntArray) = frameVisible(p, tiles.box(), colours)

    fun frameVisible(p: LightBoxReader.PixelSource, tiles: Box, colours: IntArray): Boolean {
        val spots = frameSpots(tiles)
        var same = 0
        for (i in colours.indices) {
            val x = spots[i * 2]
            val y = spots[i * 2 + 1]
            if (colours[i] < 0 || x !in 0 until p.width || y !in 0 until p.height) continue
            val c = p.rgb(x, y)
            val was = colours[i]
            val change = abs(((c shr 16) and 0xFF) - ((was shr 16) and 0xFF)) +
                abs(((c shr 8) and 0xFF) - ((was shr 8) and 0xFF)) + abs((c and 0xFF) - (was and 0xFF))
            if (change <= FRAME_CHANGE) same++
        }
        return same >= FRAME_SPOTS_NEEDED
    }

    // 12 spots in the middle of the frame, just outside the tiles (3 on each side), as x, y, x, y...
    private fun frameSpots(tiles: Box): IntArray {
        val out = tiles.width * 0.035f
        val spots = IntArray(24)
        var i = 0
        for (t in FRACTIONS) {
            val x = tiles.left + tiles.width * t
            val y = tiles.top + tiles.height * t
            for ((sx, sy) in listOf(tiles.left - out to y, tiles.right + out to y, x to tiles.top - out, x to tiles.bottom + out)) {
                spots[i++] = sx.toInt()
                spots[i++] = sy.toInt()
            }
        }
        return spots
    }

    private fun Rect.box() = Box(left, top, right, bottom)

    private val FRACTIONS = floatArrayOf(0.25f, 0.5f, 0.75f)
    private val GRID_SPOTS = FRACTIONS.flatMap { fy -> FRACTIONS.map { fx -> fx to fy } }
    // Just inside the tile's edge, clear of the biggest guide dot (radius 0.28 of a tile)
    private val RING = listOf(0.15f to 0.15f, 0.5f to 0.13f, 0.85f to 0.15f, 0.13f to 0.5f,
        0.87f to 0.5f, 0.15f to 0.85f, 0.5f to 0.87f, 0.85f to 0.85f)
}

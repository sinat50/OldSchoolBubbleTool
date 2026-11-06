package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.LightBoxReader.PixelSource
import com.sinat.osrsbubbletool.PanelFinder.Box
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

// The DPS Calculator's Import my gear: what's worn, read from the equipment tab with each wearable item's icon in
// brief (assets/dps/gear_summaries.bin, made by tools/dps/build_gear_summaries.py): 9 x 8 squares of 4 x 4 game pixels,
// each with the item's average colour there and how much of the square it covers. No item pictures are kept. Each
// slot on screen is shrunk to the same squares at the item's exact place, and compared with every item for that
// slot: a square the item half covers should look half item, half the slot's stone behind it.
// No Android parts, so it's tested on a computer with the owner's screenshots (GearImportTest).
object GearSummaryReader {

    // The game draws every item on a 36 x 32 canvas, at a fixed place in its equipment slot. Places measured on the
    // owner's screenshots, in game pixels from the side panel's top-left (the panel is about 202.7 game pixels wide).
    private const val CANVAS_PANEL_WIDTH = 202.7f
    private const val CANVAS_W = 36
    private const val CANVAS_H = 32
    private const val NUDGE = 1                 // the canvas is looked for up to this far from where it should be
    private const val GRID_W = CANVAS_W + 2 * NUDGE
    private const val GRID_H = CANVAS_H + 2 * NUDGE
    private const val TEXT_ROWS = 12            // an amount is written in the top rows of a slot
    private val GAMMAS = floatArrayOf(0.8f, 0.9f, 1.0f, 1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f)

    private val EQUIPMENT_PLACES = mapOf(
        "head" to (85.5f to 12.5f), "cape" to (44.5f to 51.5f), "neck" to (85.5f to 51.5f), "ammo" to (126.5f to 51.5f),
        "weapon" to (29f to 91f), "body" to (85.5f to 90.5f), "shield" to (141.5f to 90.5f), "legs" to (85.5f to 130.5f),
        "hands" to (29f to 171f), "feet" to (85.5f to 170.5f), "ring" to (141.5f to 170.5f))

    // A test build's details (set by GearImportTest to print them)
    var debugLog: ((String) -> Unit)? = null

    // What's worn: each slot's guesses (item ID to fit, best first; none for an empty slot), and the game's
    // brightness setting found
    class Equipment(val slots: Map<String, List<Pair<Int, Float>>>, val gamma: Float = 1.2f)

    // A space at the canvas's place, shrunk to game pixels (the middle half of each, as the edges are blurred), with a
    // pixel round it to look a little either side; and where an amount's text is (left out when comparing).
    // Two of them for each space, half a game pixel apart each way, as the canvas may not fall on whole game pixels.
    // dark: each game pixel's darkest screen pixel (r + g + b), so a thin outline split between two still shows
    private class CanvasGrid(val rgb: IntArray, val text: BooleanArray, val dark: IntArray)

    // The same for a canvas whose top-left is (gx, gy) game pixels from the side panel's top-left
    private fun canvasGridsAt(p: PixelSource, panel: Box, gx: Float, gy: Float): List<CanvasGrid>? {
        val s = panel.width / CANVAS_PANEL_WIDTH
        val grids = ArrayList<CanvasGrid>()
        for (phase in floatArrayOf(0f, 0.5f)) {
            val x0 = panel.left + (gx - NUDGE + phase) * s
            val y0 = panel.top + (gy - NUDGE + phase) * s
            val rgb = IntArray(GRID_W * GRID_H)
            val yellow = BooleanArray(GRID_W * GRID_H)
            val darkest = IntArray(GRID_W * GRID_H)
            for (j in 0 until GRID_H) for (i in 0 until GRID_W) {
                val xa = (x0 + i * s).roundToInt(); val xb = maxOf(xa + 1, (x0 + (i + 1) * s).roundToInt())
                val ya = (y0 + j * s).roundToInt(); val yb = maxOf(ya + 1, (y0 + (j + 1) * s).roundToInt())
                if (xa < 0 || ya < 0 || xb > p.width || yb > p.height) return null
                val qx = (xb - xa) / 4; val qy = (yb - ya) / 4
                var r = 0; var g = 0; var b = 0; var count = 0; var text = false; var least = Int.MAX_VALUE
                for (y in ya until yb) for (x in xa until xb) {
                    val c = p.rgb(x, y)
                    val cr = (c shr 16) and 0xFF; val cg = (c shr 8) and 0xFF; val cb = c and 0xFF
                    if (cr + cg + cb < least) least = cr + cg + cb
                    if (minOf(cr, cg) - cb > 150 || (minOf(cr, cg, cb) > 200 && maxOf(cr, cg, cb) - minOf(cr, cg, cb) < 40) ||
                        (cg > 160 && cr * 100 <= cg * 45 && cb * 100 >= cg * 30 && cb * 100 <= cg * 75)) text = true
                    if (x >= xa + qx && x < xb - qx && y >= ya + qy && y < yb - qy) { r += cr; g += cg; b += cb; count++ }
                }
                if (count == 0) { val c = p.rgb(xa, ya); r = (c shr 16) and 0xFF; g = (c shr 8) and 0xFF; b = c and 0xFF; count = 1 }
                val a = j * GRID_W + i
                rgb[a] = ((r / count) shl 16) or ((g / count) shl 8) or (b / count)
                darkest[a] = least
                yellow[a] = text && j < TEXT_ROWS + NUDGE
            }
            // the text, and its black shadow one game pixel below and to the right
            val text = BooleanArray(GRID_W * GRID_H) { a ->
                val i = a % GRID_W; val j = a / GRID_W
                yellow[a] || (j > 0 && yellow[a - GRID_W]) || (i > 0 && yellow[a - 1]) || (j > 0 && i > 0 && yellow[a - GRID_W - 1])
            }
            grids.add(CanvasGrid(rgb, text, darkest))
        }
        return grids
    }

    // How the game draws a colour value at a brightness setting
    private fun shading(gamma: Float) = IntArray(256) { v -> (0.94f * 255f * (v / 255f).pow(gamma)).roundToInt().coerceIn(0, 255) }

    // ---------------- The gear summaries ----------------

    class GearSummaries(bytes: ByteArray) {
        val count: Int
        val ids: IntArray
        val slots: IntArray          // index into the slot list the file was made with (CalculatorItems.SLOT_ORDER)
        val cells: ByteArray         // SUMMARY_BYTES an icon: red, green, blue, cover for each square

        init {
            val b = ByteBuffer.wrap(bytes)
            require(b.remaining() >= 8 && b.get() == 'G'.code.toByte() && b.get() == 'S'.code.toByte() &&
                b.get() == 'M'.code.toByte() && b.get() == '1'.code.toByte()) { "not a gear summaries file" }
            count = b.int
            ids = IntArray(count); slots = IntArray(count)
            cells = ByteArray(count * SUMMARY_BYTES)
            for (i in 0 until count) {
                ids[i] = b.int
                slots[i] = b.get().toInt() and 0xFF
                b.get(cells, i * SUMMARY_BYTES, SUMMARY_BYTES)
            }
        }
    }

    private const val SQUARES_X = 9
    private const val SQUARES_Y = 8
    private const val SQUARE = 4                 // game pixels a side
    private const val SQUARES = SQUARES_X * SQUARES_Y
    private const val SUMMARY_BYTES = SQUARES * 4
    private const val SUMMARY_SHORTLIST = 12     // per slot, used to find the brightness setting
    private const val SUMMARY_GUESSES = 5        // kept per slot
    private const val OUTLINE_BLACK = 40         // r + g + b below this on screen: an item's black outline
    private const val MIN_OUTLINE = 15           // game pixels of it in a slot with an item in it

    // One way of laying the squares on a slot (a half-pixel grid and a nudge): each square's colour on screen (-1 where
    // an amount's digits cover most of it)
    private class Squares(val r: FloatArray, val g: FloatArray, val b: FloatArray, val used: BooleanArray)

    private fun squaresOf(grid: CanvasGrid, dx: Int, dy: Int): Squares {
        val r = FloatArray(SQUARES); val g = FloatArray(SQUARES); val b = FloatArray(SQUARES); val used = BooleanArray(SQUARES)
        for (sq in 0 until SQUARES) {
            val x0 = dx + (sq % SQUARES_X) * SQUARE; val y0 = dy + (sq / SQUARES_X) * SQUARE
            var sr = 0; var sg = 0; var sb = 0; var n = 0
            for (y in y0 until y0 + SQUARE) for (x in x0 until x0 + SQUARE) {
                val a = y * GRID_W + x
                if (grid.text[a]) continue
                val c = grid.rgb[a]
                sr += (c shr 16) and 0xFF; sg += (c shr 8) and 0xFF; sb += c and 0xFF; n++
            }
            if (n * 2 < SQUARE * SQUARE) continue
            r[sq] = sr.toFloat() / n; g[sq] = sg.toFloat() / n; b[sq] = sb.toFloat() / n; used[sq] = true
        }
        return Squares(r, g, b, used)
    }

    // The slot's stone round the item: the middle colour of the grid's outer ring (no item reaches it)
    private fun stoneOf(grid: CanvasGrid): IntArray {
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (a in grid.rgb.indices) {
            val x = a % GRID_W; val y = a / GRID_W
            if ((x != 0 && x != GRID_W - 1 && y != 0 && y != GRID_H - 1) || grid.text[a]) continue
            val c = grid.rgb[a]
            rs.add((c shr 16) and 0xFF); gs.add((c shr 8) and 0xFF); bs.add(c and 0xFF)
        }
        if (rs.isEmpty()) return intArrayOf(0, 0, 0)
        return intArrayOf(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
    }

    // How far an item's summary is from the slot (the best of the ways of laying the squares): the average colour
    // difference per square, each square the item's colour where it covers it and the stone elsewhere
    // solidOnly: only the squares the item covers completely (for finding the brightness setting: the stone round an
    // item isn't changed by it, and blurs the answer)
    private fun summaryFit(ways: List<Squares>, stone: IntArray, g: GearSummaries, i: Int, lut: IntArray, solidOnly: Boolean = false): Float {
        var best = Float.MAX_VALUE
        val base = i * SUMMARY_BYTES
        for (w in ways) {
            var sum = 0f; var n = 0
            for (sq in 0 until SQUARES) {
                if (!w.used[sq]) continue
                val k = base + sq * 4
                if (solidOnly && (g.cells[k + 3].toInt() and 0xFF) < 255) continue
                val cover = (g.cells[k + 3].toInt() and 0xFF) / 255f
                val pr = cover * lut[g.cells[k].toInt() and 0xFF] + (1 - cover) * stone[0]
                val pg = cover * lut[g.cells[k + 1].toInt() and 0xFF] + (1 - cover) * stone[1]
                val pb = cover * lut[g.cells[k + 2].toInt() and 0xFF] + (1 - cover) * stone[2]
                sum += abs(pr - w.r[sq]) + abs(pg - w.g[sq]) + abs(pb - w.b[sq])
                n++
            }
            if (n > 0 && sum / (3f * n) < best) best = sum / (3f * n)
        }
        return best
    }

    // An empty equipment slot: no black outline in it, as every item has one (the owner's screenshots: empty slots
    // 0-1 pixels of it, worn items 37 or more, at any brightness). No picture of an empty slot is needed.
    private fun looksEmpty(grid: CanvasGrid) = grid.dark.indices.count { !grid.text[it] && grid.dark[it] < OUTLINE_BLACK } < MIN_OUTLINE

    // What's worn, read from the gear summaries: each slot's best guesses (icon's item ID to fit, best first) and the
    // brightness setting found. slotOrder: the slot list the file was made with. Null if the panel can't be looked at.
    // An empty slot has no guesses.
    fun readEquipmentBySummaries(p: PixelSource, panel: Box, g: GearSummaries, slotOrder: List<String>): Equipment? {
        val ways = HashMap<String, List<Squares>>()
        val stones = HashMap<String, IntArray>()
        for ((slot, at) in EQUIPMENT_PLACES) {
            val grids = canvasGridsAt(p, panel, at.first, at.second) ?: return null
            ways[slot] = grids.flatMap { grid -> (0..2 * NUDGE).flatMap { dy -> (0..2 * NUDGE).map { dx -> squaresOf(grid, dx, dy) } } }
            stones[slot] = stoneOf(grids[0])
            if (looksEmpty(grids[0])) ways.remove(slot)
        }
        val bySlot = ways.keys.associateWith { slot -> (0 until g.count).filter { slotOrder.getOrNull(g.slots[it]) == slot } }
        fun rank(slot: String, lut: IntArray, among: List<Int>, solidOnly: Boolean = false) =
            among.map { it to summaryFit(ways.getValue(slot), stones.getValue(slot), g, it, lut, solidOnly) }.sortedBy { it.second }

        // the brightness setting: the one the likeliest items fit best, over all slots
        // (the likeliest at a dark, middle and bright setting, as for the inventory)
        val likely = bySlot.mapValues { (slot, all) ->
            floatArrayOf(0.9f, 1.2f, 1.5f).flatMap { gm -> rank(slot, shading(gm), all).take(SUMMARY_SHORTLIST).map { it.first } }.distinct()
        }
        var gamma = 1.2f
        var bestTotal = Float.MAX_VALUE
        for (gm in GAMMAS) {
            val lut = shading(gm)
            var total = 0f
            for ((slot, few) in likely) total += rank(slot, lut, few, solidOnly = true).firstOrNull()?.second?.takeIf { it < Float.MAX_VALUE } ?: 0f
            if (total < bestTotal) { bestTotal = total; gamma = gm }
        }
        val lut = shading(gamma)
        val slots = EQUIPMENT_PLACES.keys.associateWith { slot ->
            bySlot[slot]?.let { all -> rank(slot, lut, all).take(SUMMARY_GUESSES).map { (i, fit) -> g.ids[i] to fit } } ?: emptyList()
        }
        debugLog?.invoke("summaries, brightness $gamma: " + slots.entries.joinToString { (k, v) -> "$k ${v.take(2).joinToString { "${it.first} %.1f".format(it.second) }}" })
        return Equipment(slots, gamma)
    }
}

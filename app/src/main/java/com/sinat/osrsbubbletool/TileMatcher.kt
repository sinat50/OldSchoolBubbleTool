package com.sinat.osrsbubbletool

import android.graphics.Bitmap
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max

// Works out which puzzle is on screen and where each of its tiles belongs.
object TileMatcher {

    const val GRID = 5
    const val TILES = GRID * GRID
    const val EMPTY = TILES - 1        // the empty space belongs in the bottom-right corner

    private const val SAMPLE = 8       // each tile is shrunk to 8 x 8 colours for comparing
    private const val INNER = 0.7f     // only the middle 70% of each tile is compared (ignores edges)
    private const val TILE_BYTES = SAMPLE * SAMPLE * 3

    // board[position] = the tile sitting at that position (positions and tiles are numbered
    // left to right, top to bottom, 0-24). Tile 24 is the empty space.
    class Result(
        val puzzleName: String,
        val board: IntArray,
        val difference: Float,         // average colour difference of the best puzzle (lower = better)
        val solvable: Boolean,
        val correctedPositions: List<Int>  // tiles that were swapped to make the layout solvable
    )

    // Runs in the background. Compares the tiles on screen with each puzzle's colour summaries.
    fun identify(scan: Bitmap, puzzles: List<PuzzleReferences.Puzzle>): Result? {
        val pixels = IntArray(scan.width * scan.height)
        scan.getPixels(pixels, 0, scan.width, 0, 0, scan.width, scan.height)
        return identify(pixels, scan.width, scan.height, puzzles)
    }

    // The same with plain pixels (so it can be tested on the PC)
    fun identify(pixels: IntArray, width: Int, height: Int, puzzles: List<PuzzleReferences.Puzzle>): Result? {
        if (puzzles.isEmpty()) return null
        val scanned = features(pixels, width, height)
        val ranks = ranks(scanned)

        var bestName = ""
        var bestBoard = IntArray(TILES)
        var bestCost: Array<DoubleArray>? = null
        var bestDiff = Float.MAX_VALUE

        for (puzzle in puzzles) {
            val reference = puzzle.tiles
            val evened = inOrder(ranks, reference)
            val cost = Array(TILES) { pos ->
                DoubleArray(TILES) { tile -> difference(evened[pos], reference[tile]).toDouble() }
            }
            val board = assign(cost)
            var total = 0.0
            for (pos in 0 until TILES) total += cost[pos][board[pos]]
            val average = (total / TILES).toFloat()

            if (average < bestDiff) {
                bestName = puzzle.name
                bestBoard = board
                bestCost = cost
                bestDiff = average
            }
        }

        // An unsolvable reading means two tiles were mixed up. Swap the pair whose
        // swap costs the least; that is almost always two similar-looking tiles.
        var corrected = emptyList<Int>()
        if (!isSolvable(bestBoard) && bestCost != null) {
            val fix = cheapestSwap(bestBoard, bestCost)
            if (fix != null) {
                val (a, b) = fix
                val t = bestBoard[a]
                bestBoard[a] = bestBoard[b]
                bestBoard[b] = t
                corrected = listOf(a, b)
            }
        }

        return Result(bestName, bestBoard, bestDiff,
            isSolvable(bestBoard), corrected)
    }

    // Finds the two positions (neither holding the empty space) whose tiles can be swapped
    // with the smallest increase in difference. Swapping two tiles always flips solvability.
    private fun cheapestSwap(board: IntArray, cost: Array<DoubleArray>): Pair<Int, Int>? {
        var best: Pair<Int, Int>? = null
        var bestIncrease = Double.MAX_VALUE
        for (a in 0 until TILES) {
            if (board[a] == EMPTY) continue
            for (b in a + 1 until TILES) {
                if (board[b] == EMPTY) continue
                val increase = cost[a][board[b]] + cost[b][board[a]] - cost[a][board[a]] - cost[b][board[b]]
                if (increase < bestIncrease) {
                    bestIncrease = increase
                    best = a to b
                }
            }
        }
        return best
    }

    // The game's brightness setting (and a phone's night mode or colour settings) changes the tiles' colours, and
    // not evenly: at the lowest brightness, dark colours lose about a third and light ones a tenth (measured on the
    // owner's phone, November 2026). What it doesn't change is which colours are darker than which. So the colours
    // on screen are lined up from darkest to lightest (red, green and blue separately), and each one takes the
    // puzzle's colour in the same place in its line-up: the darkest red on screen becomes the puzzle's darkest
    // red, and so on. ranks[colour][i] = where value i of the scanned tiles comes in that line-up.
    private fun ranks(scanned: Array<FloatArray>): Array<IntArray> {
        val values = TILES * SAMPLE * SAMPLE
        return Array(3) { colour ->
            val value = FloatArray(values) { i -> scanned[i / (SAMPLE * SAMPLE)][(i % (SAMPLE * SAMPLE)) * 3 + colour] }
            val order = (0 until values).sortedBy { value[it] }
            IntArray(values).also { rank -> order.forEachIndexed { place, i -> rank[i] = place } }
        }
    }

    // The scanned tiles in the reference puzzle's colours, by line-up (see ranks). The puzzle's own empty
    // corner tile is left out: the wiki picture shows that part of the picture, the screen shows an empty space.
    private fun inOrder(ranks: Array<IntArray>, reference: Array<FloatArray>): Array<FloatArray> {
        val perTile = SAMPLE * SAMPLE
        val result = Array(TILES) { FloatArray(TILE_BYTES) }
        for (colour in 0 until 3) {
            val sorted = FloatArray((TILES - 1) * perTile)
            var n = 0
            for (tile in 0 until TILES) {
                if (tile == EMPTY) continue
                for (k in 0 until perTile) sorted[n++] = reference[tile][k * 3 + colour]
            }
            sorted.sort()
            val rank = ranks[colour]
            val last = rank.size - 1
            for (i in rank.indices) {
                // the same place in the puzzle's line-up (which has one tile fewer), between two values if need be
                val at = rank[i].toFloat() / last * (sorted.size - 1)
                val low = at.toInt()
                val high = minOf(low + 1, sorted.size - 1)
                val value = sorted[low] + (sorted[high] - sorted[low]) * (at - low)
                result[i / perTile][(i % perTile) * 3 + colour] = value
            }
        }
        return result
    }

    // Shrinks each of the 25 tiles to an 8 x 8 grid of average colours
    private fun features(pixels: IntArray, w: Int, h: Int): Array<FloatArray> {
        val tileW = w / GRID.toFloat()
        val tileH = h / GRID.toFloat()

        return Array(TILES) { tile ->
            val x0 = (tile % GRID) * tileW + tileW * (1 - INNER) / 2
            val y0 = (tile / GRID) * tileH + tileH * (1 - INNER) / 2
            val innerW = tileW * INNER
            val innerH = tileH * INNER
            val result = FloatArray(SAMPLE * SAMPLE * 3)

            for (cy in 0 until SAMPLE) {
                val sy0 = (y0 + innerH * cy / SAMPLE).toInt().coerceIn(0, h - 1)
                val sy1 = max(sy0 + 1, (y0 + innerH * (cy + 1) / SAMPLE).toInt()).coerceAtMost(h)
                for (cx in 0 until SAMPLE) {
                    val sx0 = (x0 + innerW * cx / SAMPLE).toInt().coerceIn(0, w - 1)
                    val sx1 = max(sx0 + 1, (x0 + innerW * (cx + 1) / SAMPLE).toInt()).coerceAtMost(w)
                    var r = 0L
                    var g = 0L
                    var b = 0L
                    var count = 0
                    for (y in sy0 until sy1) {
                        for (x in sx0 until sx1) {
                            val c = pixels[y * w + x]
                            r += (c shr 16) and 0xFF
                            g += (c shr 8) and 0xFF
                            b += c and 0xFF
                            count++
                        }
                    }
                    val i = (cy * SAMPLE + cx) * 3
                    val n = max(1, count).toFloat()
                    result[i] = r / n
                    result[i + 1] = g / n
                    result[i + 2] = b / n
                }
            }
            result
        }
    }

    // Reads assets/puzzle_tiles.bin (made by tools/puzzle-box/build_tile_fingerprints.py; the layout is written there).
    fun readPuzzles(bytes: ByteArray): List<PuzzleReferences.Puzzle> {
        val b = ByteBuffer.wrap(bytes)
        require(b.remaining() >= 5 && b.get() == 'P'.code.toByte() && b.get() == 'Z'.code.toByte() &&
            b.get() == 'L'.code.toByte() && b.get() == '1'.code.toByte()) { "not a puzzle tiles file" }
        return List(b.get().toInt() and 0xFF) {
            val name = ByteArray(b.get().toInt() and 0xFF).also { b.get(it) }.toString(Charsets.UTF_8)
            PuzzleReferences.Puzzle(name, Array(TILES) {
                val colours = ByteArray(TILE_BYTES).also { b.get(it) }
                FloatArray(TILE_BYTES) { i -> (colours[i].toInt() and 0xFF).toFloat() }
            })
        }
    }

    private fun difference(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += abs(a[i] - b[i])
        return sum / a.size
    }

    // Pairs every screen position with a different tile so the total difference is as small
    // as possible (the "Hungarian algorithm"). Returns the tile for each position.
    private fun assign(cost: Array<DoubleArray>): IntArray {
        val n = cost.size
        val u = DoubleArray(n + 1)
        val v = DoubleArray(n + 1)
        val p = IntArray(n + 1)
        val way = IntArray(n + 1)
        for (i in 1..n) {
            p[0] = i
            var j0 = 0
            val minV = DoubleArray(n + 1) { Double.MAX_VALUE }
            val used = BooleanArray(n + 1)
            do {
                used[j0] = true
                val i0 = p[j0]
                var delta = Double.MAX_VALUE
                var j1 = 0
                for (j in 1..n) {
                    if (!used[j]) {
                        val current = cost[i0 - 1][j - 1] - u[i0] - v[j]
                        if (current < minV[j]) { minV[j] = current; way[j] = j0 }
                        if (minV[j] < delta) { delta = minV[j]; j1 = j }
                    }
                }
                for (j in 0..n) {
                    if (used[j]) { u[p[j]] += delta; v[j] -= delta } else minV[j] -= delta
                }
                j0 = j1
            } while (p[j0] != 0)
            do {
                val j1 = way[j0]
                p[j0] = p[j1]
                j0 = j1
            } while (j0 != 0)
        }
        val result = IntArray(n)
        for (j in 1..n) result[p[j] - 1] = j - 1
        return result
    }

    // On a 5 x 5 puzzle, a layout can be solved only if the tiles (ignoring the empty space)
    // are out of order an even number of times.
    fun isSolvable(board: IntArray): Boolean {
        var inversions = 0
        for (i in board.indices) {
            if (board[i] == EMPTY) continue
            for (j in i + 1 until board.size) {
                if (board[j] != EMPTY && board[j] < board[i]) inversions++
            }
        }
        return inversions % 2 == 0
    }
}

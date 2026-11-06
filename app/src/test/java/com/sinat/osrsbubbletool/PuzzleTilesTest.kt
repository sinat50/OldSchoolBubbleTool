package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs
import kotlin.random.Random

// Checks the Puzzle Box Solver's tile summaries on the PC: paints a scrambled "screen" from a puzzle's summaries, with
// the tiles' true places known because the test put them there, and makes sure every tile is recognised.
class PuzzleTilesTest {

    private val puzzles by lazy { TileMatcher.readPuzzles(File("src/main/assets/puzzle_tiles.bin").readBytes()) }

    private fun scramble(random: Random): IntArray {
        val board = IntArray(25) { it }
        var empty = 24
        repeat(300) {
            val next = listOfNotNull(empty - 5, empty + 5, if (empty % 5 > 0) empty - 1 else null,
                if (empty % 5 < 4) empty + 1 else null).filter { it in 0..24 }.random(random)
            board[empty] = board[next]
            board[next] = 24
            empty = next
        }
        return board
    }

    // A square "screen" with 7 pixels for each of a tile's 8 x 8 colours, tinted by red, green and blue
    private fun paint(puzzle: PuzzleReferences.Puzzle, board: IntArray, tint: FloatArray): IntArray {
        val tileSize = 80
        val side = tileSize * 5
        val margin = (tileSize - 56) / 2      // the part of each tile the summary doesn't cover
        return IntArray(side * side) { i ->
            val x = i % side
            val y = i / side
            val tile = board[(y / tileSize) * 5 + x / tileSize]
            val cellX = ((x % tileSize - margin) / 7).coerceIn(0, 7)
            val cellY = ((y % tileSize - margin) / 7).coerceIn(0, 7)
            val at = (cellY * 8 + cellX) * 3
            val colours = puzzle.tiles[tile]
            val red = (colours[at] * tint[0]).toInt().coerceIn(0, 255)
            val green = (colours[at + 1] * tint[1]).toInt().coerceIn(0, 255)
            val blue = (colours[at + 2] * tint[2]).toInt().coerceIn(0, 255)
            (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
        }
    }

    // Two tiles that look alike can't be told apart and it wouldn't matter
    private fun sameLook(puzzle: PuzzleReferences.Puzzle, a: Int, b: Int): Boolean {
        var sum = 0f
        for (i in puzzle.tiles[a].indices) sum += abs(puzzle.tiles[a][i] - puzzle.tiles[b][i])
        return sum / puzzle.tiles[a].size < 1.5f
    }

    private fun checkAll(tint: FloatArray) {
        val random = Random(5)
        for (puzzle in puzzles) repeat(5) {
            val board = scramble(random)
            val found = TileMatcher.identify(paint(puzzle, board, tint), 400, 400, puzzles)!!
            assertEquals(puzzle.name, found.puzzleName)
            assertTrue("${puzzle.name} can be solved", found.solvable)
            for (place in 0 until 25) {
                assertTrue("${puzzle.name}: place $place", found.board[place] == board[place] ||
                    sameLook(puzzle, found.board[place], board[place]))
            }
        }
    }

    @Test fun the_file_has_all_seven_puzzles() {
        assertEquals(listOf("Castle", "Tree", "Troll", "Zulrah", "Cerberus", "Gnome child", "Theatre of Blood"), puzzles.map { it.name })
        for (puzzle in puzzles) assertEquals(25, puzzle.tiles.size)
    }

    @Test fun reads_every_puzzle() = checkAll(floatArrayOf(1f, 1f, 1f))

    @Test fun reads_a_dim_tinted_screen() = checkAll(floatArrayOf(0.82f, 0.78f, 0.73f))

    @Test(expected = IllegalArgumentException::class)
    fun turns_away_another_file() { TileMatcher.readPuzzles(ByteArray(40)) }
}

package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test

// How many planned moves a look shows were made (PuzzleFinder.movesMade). A fast player can make several moves within
// one game tick, so the guide allows for being a few moves behind; the tiles that changed tell which moves really
// happened. Both cases below were found with the practice puzzle on the emulator (November 2026). Worked out by hand
// on a 5 x 5 board, places numbered 0-24 left to right, top to bottom.
class PuzzleRoundTripTest {

    // From 18, the plan goes round the square 18, 19, 24, 23 (tap 19, 24, 23, then 18 again), then on to 13 and 12
    private val square = listOf(19, 24, 23, 18, 13, 12)

    @Test
    fun roundASquareCountsAsFourMoves() {
        // the empty space is back at 18; the tiles at 19, 24 and 23 moved round
        assertEquals(4, PuzzleFinder.movesMade(square, 0, 18, 18, setOf(19, 24, 23), 6))
        // two of the three changed visibly (the third looks like the tile before it)
        assertEquals(4, PuzzleFinder.movesMade(square, 0, 18, 18, setOf(19, 23), 6))
    }

    @Test
    fun nothingHappensUntilTheTilesChange() {
        assertEquals(0, PuzzleFinder.movesMade(square, 0, 18, 18, emptySet(), 6))
        assertEquals(0, PuzzleFinder.movesMade(square, 0, 18, 18, null, 6))
        // something over tile 7 (the bubble dragged over the puzzle, say): not a move
        assertEquals(0, PuzzleFinder.movesMade(square, 0, 18, 18, setOf(7), 6))
        assertEquals(0, PuzzleFinder.movesMade(square, 0, 18, 18, setOf(19, 24, 23, 7), 6))
        // going round takes 4 moves; with only 3 looked ahead it can't be counted
        assertEquals(0, PuzzleFinder.movesMade(square, 0, 18, 18, setOf(19, 24, 23), 3))
    }

    @Test
    fun plannedMovesAhead() {
        // one move: the empty space goes from 18 to 19, the tile from 19 is now at 18
        assertEquals(1, PuzzleFinder.movesMade(square, 0, 18, 19, setOf(18, 19), 6))
        // two at once: 18 -> 19 -> 24; the tile at 19 changed on the way
        assertEquals(2, PuzzleFinder.movesMade(square, 0, 18, 24, setOf(18, 19, 24), 6))
        // without the tiles' looks, as before
        assertEquals(2, PuzzleFinder.movesMade(square, 0, 18, 24, null, 6))
    }

    // The empty space at 6, the plan: 1, 0, 5, 10, 11, 12 (round the square 0, 1, 5, 6, then 10, 11 below it)
    private val plan = listOf(1, 0, 5, 10, 11, 12)

    @Test
    fun anUndoIsNotFiveMovesAhead() {
        // the player tapped 11, putting back the tile they had just moved: the empty space is at 11, which is also
        // where 5 planned moves would leave it, but only 6 and 11 changed
        assertEquals(0, PuzzleFinder.movesMade(plan, 0, 6, 11, setOf(6, 11), 6))
        // the 5 planned moves really made: the tiles on the way changed too
        assertEquals(5, PuzzleFinder.movesMade(plan, 0, 6, 11, setOf(6, 1, 0, 5, 10, 11), 6))
    }

    @Test
    fun changedTilesComparesTheEightSpotsOfEachTile() {
        val before = IntArray(200) { 0x404040 }
        val after = before.copyOf()
        after[19 * 8 + 2] = 0x444444      // tile 19's third spot: +4 each, 12 in all: not quite enough
        after[3 * 8 + 7] = 0x454545       // tile 3's last spot: +5 each, 15 in all: changed
        after[8 * 8 + 1] = -1             // off the screen: not compared
        assertEquals(setOf(3), PuzzleFinder.changedTiles(before, after))
    }
}

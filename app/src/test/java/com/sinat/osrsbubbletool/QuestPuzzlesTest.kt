package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Test

// Where the Song of the Elves light puzzle maps draw the pillars. Expected places are the game's, read from the plugin.
class QuestPuzzlesTest {

    private fun place(floor: Int, col: Char, row: Int) = QuestPuzzles.pillarX(col) to QuestPuzzles.pillarY(floor, col, row)

    @Test
    fun pillarsOnTheEvenGrid() {
        assertEquals(2567 to 6088, place(1, 'A', 0))
        assertEquals(2595 to 6102, place(1, 'C', 1))
        assertEquals(2651 to 6102, place(2, 'G', 1))
        assertEquals(2679 to 6158, place(2, 'I', 5))
    }

    @Test
    fun theTopFloorsSouthEastRoomIsSmaller() {
        assertEquals(2651 to 6094, place(2, 'G', 0))
        assertEquals(2665 to 6094, place(2, 'H', 0))
        assertEquals(2665 to 6110, place(2, 'H', 2))
        assertEquals(2651 to 6110, place(2, 'G', 2))
    }
}

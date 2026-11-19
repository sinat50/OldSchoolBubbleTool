package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.QuestProgress.Move
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

// Keeping your place in a quest guide when an update changes it. Expected answers are worked out by hand.
class QuestProgressTest {

    private val guide = listOf("Talk to Bob in Lumbridge.", "Climb the ladder.", "Search the crate.", "Climb the ladder.", "Return to Bob.")

    @Test
    fun tableLinesAreReadAndNotesSkipped() {
        val table = QuestProgress.readTable(sequenceOf("# a note", "Cook's Assistant\t0:0,3:5", "", "Rune Mysteries\t2:1"))
        assertEquals(setOf("Cook's Assistant", "Rune Mysteries"), table.keys)
        assertEquals(5, table.getValue("Cook's Assistant")[1].to)
        assertEquals(1, table.getValue("Rune Mysteries")[0].to)
    }

    @Test
    fun oldPlacesFollowTheTable() {
        // Two steps were added before old step 3, and old step 6 was split, adding one more after it
        val moves = listOf(Move(0, 0), Move(3, 5), Move(7, 10))
        assertEquals(0, QuestProgress.moved(0, moves))
        assertEquals(2, QuestProgress.moved(2, moves))
        assertEquals(5, QuestProgress.moved(3, moves))
        assertEquals(8, QuestProgress.moved(6, moves))
        assertEquals(10, QuestProgress.moved(7, moves))
        assertEquals(12, QuestProgress.moved(9, moves))
    }

    @Test
    fun aPlaceBeforeTheFirstMoveStaysPut() {
        assertEquals(1, QuestProgress.moved(1, listOf(Move(4, 9))))
    }

    @Test
    fun noSavedWordingKeepsTheNumber() {
        assertEquals(2, QuestProgress.find(guide, 2, null))
        assertEquals(guide.size, QuestProgress.find(guide, 99, null))
    }

    @Test
    fun theStepIsFoundWhereItWasSaved() {
        assertEquals(3, QuestProgress.find(guide, 3, "Climb the ladder."))
    }

    @Test
    fun aStepThatMovedIsFoundByItsWording() {
        assertEquals(2, QuestProgress.find(guide, 0, "Search the crate."))
    }

    @Test
    fun repeatedWordingPicksTheNearestOne() {
        assertEquals(1, QuestProgress.find(guide, 0, "Climb the ladder."))
        assertEquals(3, QuestProgress.find(guide, 4, "Climb the ladder."))
    }

    @Test
    fun aRewordedStepIsFound() {
        assertEquals(0, QuestProgress.find(guide, 4, "Talk to Bob in Lumbridge now."))
    }

    @Test
    fun aStepThatIsGoneKeepsTheNumber() {
        assertEquals(2, QuestProgress.find(guide, 2, "Fight the dragon on Crandor."))
    }

    @Test
    fun aFinishedGuideStaysFinishedWhenItGrows() {
        assertEquals(guide.size, QuestProgress.find(guide, 3, QuestProgress.FINISHED))
    }

    @Test
    fun theWordingSavedForTheEndMeansFinished() {
        assertEquals(QuestProgress.FINISHED, QuestProgress.wordingAt(guide, guide.size))
        assertEquals("Search the crate.", QuestProgress.wordingAt(guide, 2))
    }

    @Test
    fun scrambledKeepsItsTipsAndTravelInTheNormalGuideFormat() {
        val guide = org.json.JSONObject(QuestGuides.json("Scrambled!") ?: error("Scrambled! has no guide"))
        val sections = guide.getJSONArray("sec")
        val all = (0 until sections.length()).map { sections.getJSONObject(it) }
        val steps = all.flatMap { s -> s.getJSONArray("st").let { st -> (0 until st.length()).map { st.getJSONObject(it) } } }
        assertTrue("every part says how to get there", all.all { it.has("tr") })
        assertTrue("the tips are still there", steps.count { it.has("tip") } >= 8)
        assertTrue("the optional shortcut is still marked", steps.any { it.optInt("o") == 1 })
    }

    @Test
    fun theRealTableFitsTheGuides() {
        val table = File("src/main/assets/quest_step_moves.tsv").useLines { QuestProgress.readTable(it) }
        assertTrue("the table should cover the changed guides", table.size > 100)
        for ((quest, moves) in table) {
            val guide = org.json.JSONObject(QuestGuides.json(quest) ?: error("$quest has no guide"))
            val sections = guide.getJSONArray("sec")
            val steps = (0 until sections.length()).sumOf { sections.getJSONObject(it).getJSONArray("st").length() }
            assertTrue("$quest: old steps out of order", moves.zipWithNext().all { (a, b) -> a.from < b.from })
            assertTrue("$quest: new steps out of order", moves.zipWithNext().all { (a, b) -> a.to <= b.to })
            assertTrue("$quest: goes past the end", moves.last().to <= steps)
        }
    }
}

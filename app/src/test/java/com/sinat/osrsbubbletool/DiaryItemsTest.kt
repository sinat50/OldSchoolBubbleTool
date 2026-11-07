package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.QuestBook.DiaryTask
import com.sinat.osrsbubbletool.QuestBook.DiaryTier
import org.junit.Assert.assertEquals
import org.junit.Test

// A diary tier's "Items you still need" list (QuestBook.itemsStillNeeded), on a small made-up tier.
// The expected lists are worked out by hand.
class DiaryItemsTest {

    private fun task(name: String, vararg items: String) = DiaryTask(name, emptyList(), emptyList(), items.toList())

    private val tier = DiaryTier("Easy", emptyList(), emptyList(), listOf(
        task("Mine some iron", "Any pickaxe"),                                   // task 1
        task("Cast a spell", "Water runes (2)", "Law rune (1)"),                 // task 2
        task("Mine some gold", "Any pickaxe", "Coins (1,000)"),                  // task 3
        task("Talk to someone"),                                                 // task 4: no items
        task("Cast another spell", "Water rune (6)", "Food")                     // task 5
    ))

    private fun list(done: List<Boolean>?) = QuestBook.itemsStillNeeded(tier, done)

    @Test
    fun notSyncedListsEveryTasksItemsOnce() {
        // the pickaxe (tasks 1 and 3) is listed once; the water runes are added up (2 + 6) though written two ways
        assertEquals(listOf("Any pickaxe", "Water runes (8)", "Law rune", "Coins (1,000)", "Food"), list(null))
    }

    @Test
    fun finishedTasksAreLeftOut() {
        // tasks 1, 2 and 4 done: the pickaxe is still needed for task 3, the water runes only for task 5
        assertEquals(listOf("Any pickaxe", "Coins (1,000)", "Water rune (6)", "Food"), list(listOf(true, true, false, true, false)))
    }

    @Test
    fun nothingLeftWhenEveryTaskIsDone() {
        assertEquals(emptyList<String>(), list(List(5) { true }))
    }
}

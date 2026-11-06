package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

// The guided scan's rules, look by look: a part is only saved when it shows on two looks in a row (a tab caught while
// it's still being drawn isn't kept), and the countdown for the optional rune pouch. Expected answers worked out by
// hand from the rules.
class GuidedScanTest {

    // Each look: whether the part showed; what the scan does with it ("save" or nothing)
    private fun looks(vararg showing: Boolean): List<String?> {
        val steady = GuidedScan.Steady()
        return showing.map { if (steady.check(it)) "save" else null }
    }

    @Test
    fun aPartIsSavedOnTheSecondLookInARow() {
        assertEquals(listOf(null, "save"), looks(true, true))
    }

    @Test
    fun aLookInBetweenStartsTheCountAgain() {
        // a look where it didn't show (the tab closed, the pouch window open) breaks the row
        assertEquals(listOf(null, null, null, "save"), looks(true, false, true, true))
    }

    @Test
    fun nothingIsSavedWhileItDoesntShow() {
        assertEquals(listOf(null, null, null), looks(false, false, false))
    }

    @Test
    fun theRunePouchCountdown() {
        val p = GuidedScan.Progress()
        assertNull(p.pouchSecondsLeft(1_000))                  // still waiting for the other parts
        p.saved.addAll(listOf(GuidedScan.Part.INVENTORY, GuidedScan.Part.EQUIPMENT, GuidedScan.Part.SPELLBOOK))
        p.othersSavedAt = 10_000
        assertEquals(8, p.pouchSecondsLeft(10_000))           // the whole 8 seconds
        assertEquals(6, p.pouchSecondsLeft(12_500))           // 5.5 s left shows as 6
        assertEquals(0, p.pouchSecondsLeft(30_000))           // never below 0
        p.saved.add(GuidedScan.Part.RUNE_POUCH)
        assertNull(p.pouchSecondsLeft(12_500))                 // the pouch is saved: no countdown
    }
}

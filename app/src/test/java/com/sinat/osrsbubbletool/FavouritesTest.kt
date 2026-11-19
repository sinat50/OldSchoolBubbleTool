package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FavouritesTest {

    @Test fun nothing_saved_means_no_favourites() {
        assertEquals(emptyList<String>(), Favourites.read(null))
        assertEquals(emptyList<String>(), Favourites.read(""))
    }

    @Test fun saved_favourites_come_back_in_the_same_order() {
        val names = listOf("FARMING", "QUESTS", "PRICES")
        assertEquals(names, Favourites.read(Favourites.write(names)))
    }

    @Test fun a_damaged_save_keeps_six_different_tools_at_most() {
        assertEquals(listOf("A", "B", "C", "D", "E", "F"), Favourites.read("A,A,B,,C,D,E,F,G"))
    }

    @Test fun holding_a_new_tool_adds_it_at_the_end() {
        assertEquals(listOf("A", "B"), Favourites.toggled(listOf("A"), "B"))
    }

    @Test fun holding_a_favourite_takes_it_out() {
        assertEquals(listOf("A", "C"), Favourites.toggled(listOf("A", "B", "C"), "B"))
    }

    @Test fun a_seventh_favourite_is_refused() {
        assertNull(Favourites.toggled(listOf("A", "B", "C", "D", "E", "F"), "G"))
    }

    @Test fun with_six_favourites_one_can_still_be_taken_out() {
        assertEquals(listOf("B", "C", "D", "E", "F"), Favourites.toggled(listOf("A", "B", "C", "D", "E", "F"), "A"))
    }
}

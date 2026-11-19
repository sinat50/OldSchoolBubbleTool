package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.ZulrahFight.Form
import com.sinat.osrsbubbletool.ZulrahFight.Pray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Expected answers read from the Zulrah Helper RuneLite plugin's PatternTree (v3.0.0), not from this app's code
class ZulrahFightTest {

    // Plays on, taking the option with this title whenever there's a choice, until the rotation is over
    private fun playTo(fight: ZulrahFight, vararg titles: String): Int {
        val wanted = titles.toMutableList()
        var phases = 1
        while (true) {
            val options = fight.nextOptions()
            if (options.first().restart) return phases
            fight.choose(if (options.size == 1) options.first() else options.first { it.phase.title == wanted.first() }.also { wanted.removeAt(0) })
            phases++
        }
    }

    @Test fun the_fight_starts_green_with_no_prayer() {
        val fight = ZulrahFight()
        assertEquals(Form.GREEN, fight.current.form)
        assertTrue(fight.current.prayers.isEmpty())
        assertEquals(1, fight.phaseNumber)
    }

    @Test fun the_second_phase_is_a_choice_of_three() {
        assertEquals(listOf("Magma", "Serp", "Tanz"), ZulrahFight().nextOptions().map { it.phase.title })
    }

    @Test fun magma_splits_into_a_and_b_at_the_fourth_phase() {
        val fight = ZulrahFight()
        fight.choose(fight.nextOptions().first { it.phase.title == "Magma" })
        assertEquals(1, fight.nextOptions().size)
        fight.choose(fight.nextOptions().single())
        assertEquals(listOf("Magma A", "Magma B"), fight.nextOptions().map { it.phase.title })
    }

    @Test fun each_rotation_is_as_long_as_the_plugins() {
        assertEquals(10, playTo(ZulrahFight(), "Magma", "Magma A"))
        assertEquals(10, playTo(ZulrahFight(), "Magma", "Magma B"))
        assertEquals(11, playTo(ZulrahFight(), "Serp"))
        assertEquals(12, playTo(ZulrahFight(), "Tanz"))
    }

    @Test fun the_jad_phase_says_which_prayer_comes_first() {
        assertEquals(listOf(Pray.MISSILES, Pray.MAGIC), magmaPhase(9, "Magma A").prayers)
        assertEquals(listOf(Pray.MISSILES, Pray.MAGIC), magmaPhase(9, "Magma B").prayers)
        val serp = ZulrahFight()
        serp.choose(serp.nextOptions().first { it.phase.title == "Serp" })
        repeat(8) { serp.choose(serp.nextOptions().single()) }
        assertEquals(10, serp.phaseNumber)
        assertEquals(listOf(Pray.MAGIC, Pray.MISSILES), serp.current.prayers)
        assertEquals(10, serp.current.attacks)
    }

    // The phase with this number in a magma rotation
    private fun magmaPhase(number: Int, rotation: String): ZulrahFight.Phase {
        val fight = ZulrahFight()
        fight.choose(fight.nextOptions().first { it.phase.title == "Magma" })
        fight.choose(fight.nextOptions().single())
        fight.choose(fight.nextOptions().first { it.phase.title == rotation })
        while (fight.phaseNumber < number) fight.choose(fight.nextOptions().single())
        return fight.current
    }

    @Test fun the_rotation_name_stays_once_it_is_known() {
        val fight = ZulrahFight()
        fight.choose(fight.nextOptions().first { it.phase.title == "Tanz" })
        fight.choose(fight.nextOptions().single())
        assertEquals("Tanz", fight.title)
        assertEquals(3, fight.phaseNumber)
    }

    @Test fun after_the_last_phase_zulrah_starts_again_needing_range_prayer() {
        val fight = ZulrahFight()
        playTo(fight, "Serp")
        val again = fight.nextOptions().single()
        assertTrue(again.restart)
        fight.choose(again)
        assertEquals(1, fight.phaseNumber)
        assertEquals(listOf(Pray.MISSILES), fight.current.prayers)
        assertEquals(3, fight.nextOptions().size)
    }

    @Test fun undo_goes_back_one_tap_at_a_time_even_over_a_restart() {
        val fight = ZulrahFight()
        playTo(fight, "Tanz")
        val last = fight.phaseNumber
        fight.choose(fight.nextOptions().single())
        fight.undo()
        assertEquals(last, fight.phaseNumber)
        assertEquals("Tanz", fight.title)
    }

    @Test fun later_phases_show_only_once_the_rotation_is_certain() {
        val fight = ZulrahFight()
        assertTrue(fight.upcoming().isEmpty())                     // three choices
        fight.choose(fight.nextOptions().first { it.phase.title == "Magma" })
        assertTrue(fight.upcoming().isEmpty())                     // Magma A and B split right after the next phase
        val tanz = ZulrahFight()
        tanz.choose(tanz.nextOptions().first { it.phase.title == "Tanz" })
        assertEquals(9, tanz.upcoming().size)                      // phase 2 of 12: the next is 3, then 4 to 12
        assertEquals(Form.BLUE, tanz.upcoming().first().form)
    }

    // The island from the game cache: 69 floor tiles, as Shortest Path's walking map also has them
    @Test fun every_place_to_stand_is_one_floor_tile_of_the_island() {
        assertEquals(69, ZulrahFight.Arena.tiles.size)
        for (stand in ZulrahFight.Stand.entries) {
            assertTrue(stand.name, (stand.x to stand.y) in ZulrahFight.Arena.tiles)
            assertFalse(stand.name, (stand.x to stand.y) in ZulrahFight.Arena.pillars)
        }
    }

    @Test fun reset_forgets_everything() {
        val fight = ZulrahFight()
        playTo(fight, "Tanz")
        fight.reset()
        assertEquals(1, fight.phaseNumber)
        assertFalse(fight.canUndo)
        assertTrue(fight.current.prayers.isEmpty())
    }

    @Test fun docking_keeps_the_window_clear_of_bars_and_the_camera() {
        val bars = Bars(left = 10, right = 30)
        assertEquals(40, ScreenBars.dockX(200, onRight = false, screenW = 2000, bars = bars, cutoutLeft = 40, cutoutRight = 0))
        assertEquals(1770, ScreenBars.dockX(200, onRight = true, screenW = 2000, bars = bars, cutoutLeft = 0, cutoutRight = 0))
        assertTrue(ScreenBars.nearerRight(1200, 200, 2000))
        assertFalse(ScreenBars.nearerRight(700, 200, 2000))
    }
}

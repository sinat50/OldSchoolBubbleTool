package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.Loadout.Stack
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Gear Loadouts' saved loadouts on the PC: a loadout must come back exactly as it was saved, the setups saved by the
// older Inventory Setups (only an id and a name) must still open, and the item lists of loadouts saved before
// screenshots must read as text, part by part, until a scan replaces each part.
class LoadoutTest {

    private fun roundTrip(l: Loadout) = Loadout.fromJson(JSONObject(l.toJson().toString()))

    @Test
    fun aLoadoutComesBackAsItWasSaved() {
        val l = Loadout("123", "Vorkath")
        l.notes = "Bring antifire\nand a stamina"
        l.spellbook = 3
        l.inventory[0] = Stack(12791)              // rune pouch
        l.inventory[5] = Stack(2434)               // prayer potion(4)
        l.inventory[27] = Stack(995, 1_250_000)    // coins
        l.equipment["weapon"] = Stack(4151)
        l.equipment["ammo"] = Stack(21944, 87)
        l.runePouch = mutableListOf(Stack(560, 1000), null, Stack(565, 250))
        l.quiver = Stack(892, 500)
        val back = roundTrip(l)
        assertEquals("123", back.id)
        assertEquals("Vorkath", back.name)
        assertEquals("Bring antifire\nand a stamina", back.notes)
        assertEquals(3, back.spellbook)
        assertEquals(l.inventory.toList(), back.inventory.toList())
        assertEquals(l.equipment, back.equipment)
        assertEquals(l.runePouch, back.runePouch)
        assertNull(back.boltPouch)
        assertEquals(Stack(892, 500), back.quiver)
        assertTrue(back.hasItems)
    }

    @Test
    fun anOldInventorySetupOpensWithNoItems() {
        val old = Loadout.fromJson(JSONObject("""{"id":"1712345678901","name":"Zulrah"}"""))
        assertEquals("Zulrah", old.name)
        assertFalse(old.hasItems)
        assertNull(old.spellbook)
        assertEquals("", old.notes)
        assertTrue(old.inventory.all { it == null })
    }

    @Test
    fun anEmptyLoadoutSavesOnlyItsName() {
        val json = Loadout("9", "New").toJson()
        assertEquals(setOf("id", "name"), json.keys().asSequence().toSet())
    }

    @Test
    fun damagedValuesAreLeftOutRatherThanCrashing() {
        val l = Loadout.fromJson(JSONObject("""{"id":"2","name":"x","sb":9,"inv":[["oops"],null,[385,0]],"eq":{"head":"bad","weapon":[4151]}}"""))
        assertNull(l.spellbook)                         // there are only 4 spellbooks
        assertNull(l.inventory[0])
        assertEquals(Stack(385, 1), l.inventory[2])     // an amount below 1 counts as 1
        assertNull(l.equipment["head"])
        assertEquals(Stack(4151, 1), l.equipment["weapon"])
    }

    private val names = mapOf(12791 to "Rune pouch", 385 to "Shark", 995 to "Coins", 4151 to "Abyssal whip",
        21944 to "Ruby dragon bolts (e)", 560 to "Death rune", 565 to "Blood rune", 892 to "Rune arrow")
    private fun nameOf(id: Int) = names[id] ?: "Item $id"

    @Test
    fun anOldItemListReadsAsText() {
        val l = Loadout("1", "Vorkath")
        l.inventory[0] = Stack(12791)
        for (i in 4..7) l.inventory[i] = Stack(385)    // four sharks in four spaces: counted together
        l.inventory[27] = Stack(995, 1_250_000)
        l.equipment["weapon"] = Stack(4151)
        l.equipment["ammo"] = Stack(21944, 87)
        l.runePouch = mutableListOf(Stack(560, 1000), null, Stack(565, 250))
        l.quiver = Stack(892, 500)
        l.spellbook = 2
        val parts = l.itemLines(::nameOf)
        assertEquals(listOf("Inventory", "Equipment", "Rune pouch", "Spellbook"), parts.map { it.heading })
        assertEquals(listOf("Rune pouch", "Shark x 4", "Coins x 1,250,000"), parts[0].lines)
        // in the game's order, top to bottom: ammo comes before the weapon
        assertEquals(listOf("Ammo: Ruby dragon bolts (e) x 87", "Weapon: Abyssal whip", "Quiver: Rune arrow x 500"), parts[1].lines)
        assertEquals(listOf("Death rune x 1,000", "Blood rune x 250"), parts[2].lines)
        assertEquals(listOf("Lunar"), parts[3].lines)
        assertEquals("Item 999", nameOf(999))
    }

    @Test
    fun eachScreenshotReplacesItsPartOfTheOldList() {
        val l = Loadout("1", "Vorkath")
        l.inventory[3] = Stack(385)
        l.boltPouch = mutableListOf(Stack(21944, 20))
        l.equipment["weapon"] = Stack(4151)
        l.quiver = Stack(892, 500)
        l.runePouch = mutableListOf(Stack(560, 1000))
        l.spellbook = 1
        l.forgetInventory()
        assertEquals(listOf("Equipment", "Rune pouch", "Spellbook"), l.itemLines(::nameOf).map { it.heading })
        l.forgetEquipment()
        l.forgetRunePouch()
        assertTrue(l.hasItems)                         // the spellbook is still listed
        l.forgetSpellbook()
        assertFalse(l.hasItems)
        assertTrue(l.itemLines(::nameOf).isEmpty())
        // and nothing of the old list is saved any more
        assertEquals(setOf("id", "name"), l.toJson().keys().asSequence().toSet())
    }
}

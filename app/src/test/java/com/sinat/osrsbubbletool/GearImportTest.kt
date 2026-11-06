package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// The DPS Calculator's Import my gear, from the gear summaries alone (no item pictures), on screenshots of the owner's
// equipment tab: Documents\Androiddev\loadout_shots (Gear Loadouts' screenshots) and
// Documents\Androiddev\gear_lookalike_shots (look-alike gear at the game's lowest, 50% and highest brightness, described
// in its notes.md). Skipped without them. The gear expected is what the owner wore (their notes and Gear Loadouts' tests).
class GearImportTest {

    private val assets = File("src/main/assets")
    private val items by lazy { ItemData(File(assets, "items/items.tsv").readText(), File(assets, "items/noted.tsv").readText()) }
    private val summaries by lazy { InventoryReader.GearSummaries(File(assets, "dps/gear_summaries.bin").readBytes()) }
    private val alike by lazy {
        items.lookAlikes(File(assets, "items/canvas.tsv").readLines().filter { !it.startsWith("#") && it.isNotBlank() }
            .associate { it.split('\t').let { p -> p[0].toInt() to p[1].toInt() } })
    }

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        private val data = IntArray(width * height).also { image.getRGB(0, 0, width, height, it, 0, width) }
        override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
    }

    // Each slot's item name as imported ("" for an empty slot). An item read as a charged or numbered copy of the one
    // expected (Amulet of glory(1) for Amulet of glory) is right: the calculator gives them the same stats.
    private fun imported(file: File): Map<String, String> {
        val p = Shot(file)
        val worn = InventoryReader.readEquipmentBySummaries(p, PanelFinder.findPanel(p)!!, summaries, GearRecognizer.SLOT_ORDER)!!
        return GearImport.choose(worn.slots, items, alike) { id, _, _, _ -> id }.mapValues { (_, id) -> id?.let { items[it]?.name } ?: "" }
    }

    private fun check(file: File, expected: Map<String, String>): List<String> {
        val got = imported(file)
        return GearRecognizer.SLOT_ORDER.mapNotNull { slot ->
            val want = expected[slot] ?: ""
            val name = got[slot] ?: ""
            val ok = if (want.isEmpty()) name.isEmpty() else name == want || (name.startsWith(want) && !name.contains("(broken)"))
            if (ok) null else "${file.name} $slot: expected '${want}', imported '$name'"
        }
    }

    private val rangeSet = mapOf("cape" to "Ava's assembler", "neck" to "Amulet of glory", "weapon" to "Blazing blowpipe",
        "legs" to "Black d'hide chaps", "hands" to "Black d'hide vambraces")
    private val meleeSet = mapOf("head" to "Neitiznot faceguard", "cape" to "Fire cape", "neck" to "Bonecrusher necklace",
        "ammo" to "Rada's blessing 4", "weapon" to "Osmumten's fang", "body" to "Sunfire fanatic cuirass",
        "shield" to "Avernic defender", "legs" to "Sunfire fanatic chausses", "hands" to "Ferocious gloves",
        "feet" to "Devout boots", "ring" to "Celestial ring")

    private val expected = mapOf(
        // (ruby dragon bolts, plain or enchanted, are imported enchanted)
        "gear_lookalike_shots/dhide_ruby_dragon_e_b50.png" to rangeSet + mapOf("body" to "Black d'hide body", "ammo" to "Ruby dragon bolts (e)"),
        "gear_lookalike_shots/chainbody_ruby_dragon_plain_b50.png" to rangeSet + mapOf("body" to "Black chainbody", "ammo" to "Ruby dragon bolts (e)"),
        "gear_lookalike_shots/chainbody_ruby_dragon_plain_blow.png" to rangeSet + mapOf("body" to "Black chainbody", "ammo" to "Ruby dragon bolts (e)"),
        "gear_lookalike_shots/chainbody_ruby_dragon_plain_bmax.png" to rangeSet + mapOf("body" to "Black chainbody", "ammo" to "Ruby dragon bolts (e)"),
        "loadout_shots/12_equipment_melee.png" to meleeSet,
        "loadout_shots/22_equipment_brightness_high.png" to meleeSet,
        "loadout_shots/19_equipment_brightness_low.png" to emptyMap(),   // nothing worn
        "loadout_shots/2_equipment.png" to mapOf("head" to "Crystal helm", "cape" to "Ava's assembler", "neck" to "Necklace of rupture",
            "ammo" to "Rada's blessing 4", "weapon" to "Bow of Faerdhinen", "body" to "Crystal body", "legs" to "Crystal legs",
            "hands" to "Barrows gloves", "feet" to "Pegasian boots", "ring" to "Ring of suffering")
    )

    @Test
    fun importsTheOwnersGear() {
        val root = File(System.getenv("GEAR_SHOTS") ?: "C:/Users/Tanis/Documents/Androiddev")
        val found = expected.filterKeys { File(root, it).isFile }
        assumeTrue(found.isNotEmpty())
        val wrong = found.flatMap { (name, gear) -> check(File(root, name), gear) }
        println("Checked ${found.size} equipment screenshots, ${found.size * 11} slots")
        assertEquals("", wrong.joinToString("\n"))
    }
}

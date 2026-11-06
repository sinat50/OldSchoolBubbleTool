package com.sinat.osrsbubbletool

import android.content.Context
import org.json.JSONArray

// The DPS Calculator's list of items (assets/dps/equipment.json, from the OSRS Wiki DPS calculator's repository,
// made by tools/dps/build_equipment_json.py), and finding the calculator's item for one read from the game.
// Text only: no item pictures. Read once, the first time Import my gear needs it.
class CalculatorItems private constructor(context: Context) {

    companion object {
        // The version of the DPS calculator's repository the item list comes from
        // (tools/dps/build_equipment_json.py and the update check read it from here)
        @Suppress("unused") const val COMMIT = "741f2f2d49da14bf872b7534fc9e1cb50d67bceb"

        @Volatile private var sharedOne: CalculatorItems? = null

        fun shared(context: Context): CalculatorItems = sharedOne ?: synchronized(this) {
            sharedOne ?: CalculatorItems(context.applicationContext).also { sharedOne = it }
        }

        // The bubble closed: let go of the list
        fun releaseShared() { sharedOne = null }

        // The equipment slots, in the order assets/dps/gear_summaries.bin numbers them (tools/dps/build_gear_summaries.py)
        val SLOT_ORDER = listOf("head", "cape", "neck", "ammo", "weapon", "body", "shield", "legs", "hands", "feet", "ring")

        val SLOT_LABELS = Loadout.SLOT_LABELS
    }

    // iconFile: the name of the item's picture in the calculator's repository (only its name: no pictures are kept)
    class Item(val id: Int, val name: String, val version: String, val slot: String, val iconFile: String) {
        val label: String get() = if (version.isEmpty()) name else "$name ($version)"
    }

    private val assets = context.assets   // (not the context itself: this one is kept for the whole bubble session)
    private var itemsBySlot: Map<String, List<Item>>? = null

    @Synchronized
    private fun items(): Map<String, List<Item>> {
        itemsBySlot?.let { return it }
        val text = assets.open("dps/equipment.json").bufferedReader().use { it.readText() }
        val array = JSONArray(text)
        val list = ArrayList<Item>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            list.add(Item(o.getInt("id"), o.getString("name"), o.optString("version", ""),
                o.getString("slot"), o.getString("icon")))
        }
        // Items whose versions are just numbers are charges or degradation (Amulet of glory 1-6,
        // Barrows 0-100), which don't change damage. Keep only the highest number of each.
        val chargeFamilies = list.filter { it.version.toIntOrNull() != null }
            .groupBy { it.slot to it.name }
        val keep = chargeFamilies.values.map { family -> family.maxByOrNull { it.version.toInt() }!! }.toSet()
        val trimmed = list.filter { it.version.toIntOrNull() == null || it in keep }

        val grouped = trimmed.groupBy { it.slot }
        itemsBySlot = grouped
        return grouped
    }

    // The calculator's item for an item read from the game (from the app's item list: its ID, name and wiki
    // picture's name), or null if the calculator doesn't have it. By ID first; then, for a copy the calculator doesn't
    // list (a charge it leaves out, say), the item with the same wiki picture name, or the same name.
    fun calculatorItem(id: Int, slot: String, name: String, picture: String?): Item? {
        val list = items()[slot] ?: return null
        list.firstOrNull { it.id == id }?.let { return it }
        if (picture != null) list.firstOrNull { it.iconFile == picture }?.let { return it }
        fun plain(s: String) = s.lowercase().replace(" ", "")
        return list.firstOrNull { plain(it.label) == plain(name) } ?: list.firstOrNull { plain(it.name) == plain(name) }
    }
}

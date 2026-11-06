package com.sinat.osrsbubbletool

import org.json.JSONArray
import org.json.JSONObject

// One Gear Loadouts loadout: its name and notes. What's in it is screenshots, kept in files/setups/<id>/ (see
// GearLoadoutsTool); nothing about them is saved here. No Android parts, so it can be tested on a computer.
//
// Saved as one JSON object per loadout, in the list Gear Loadouts keeps in its settings (backed up with them).
// Loadouts saved by v1.0.9 to v1.2.0 also list their items by game item ID (inventory, equipment, pouches, quiver,
// spellbook): those are kept and shown as a plain list of names (itemLines) until a scan replaces each part with its
// screenshot. Loadouts saved by the older Inventory Setups have only an id and a name.
class Loadout(val id: String, var name: String) {

    // One item in one place, and how many
    data class Stack(val id: Int, val amount: Int = 1)

    // A heading ("Inventory") and its lines ("Shark x 4")
    class ItemList(val heading: String, val lines: List<String>)

    companion object {
        const val INVENTORY_SIZE = 28
        // The equipment slots, top to bottom as the game shows them
        val SLOTS = listOf("head", "cape", "neck", "ammo", "weapon", "body", "shield", "legs", "hands", "feet", "ring")
        // Each slot's name as shown
        val SLOT_LABELS = mapOf("head" to "Head", "cape" to "Cape", "neck" to "Neck", "ammo" to "Ammo",
            "weapon" to "Weapon", "body" to "Body", "shield" to "Shield", "legs" to "Legs", "hands" to "Hands",
            "feet" to "Feet", "ring" to "Ring")
        // Spellbooks, numbered as the older loadouts saved them
        val SPELLBOOKS = listOf("Standard", "Ancient", "Lunar", "Arceuus")

        fun fromJson(o: JSONObject): Loadout {
            val l = Loadout(o.getString("id"), o.optString("name", "Loadout"))
            l.notes = o.optString("notes", "")
            l.spellbook = if (o.has("sb")) o.getInt("sb").takeIf { it in SPELLBOOKS.indices } else null
            o.optJSONArray("inv")?.let { a -> for (i in 0 until minOf(a.length(), INVENTORY_SIZE)) l.inventory[i] = stack(a.opt(i)) }
            o.optJSONObject("eq")?.let { e -> for (slot in SLOTS) stack(e.opt(slot))?.let { l.equipment[slot] = it } }
            l.runePouch = list(o.optJSONArray("rp"))
            l.boltPouch = list(o.optJSONArray("bp"))
            l.quiver = stack(o.opt("qv"))
            return l
        }

        // A stack is saved as [id, amount], or null for an empty place
        private fun stack(v: Any?): Stack? {
            val a = v as? JSONArray ?: return null
            val id = a.optInt(0, -1)
            return if (id < 0) null else Stack(id, a.optInt(1, 1).coerceAtLeast(1))
        }

        private fun list(a: JSONArray?): MutableList<Stack?>? =
            a?.let { MutableList(it.length()) { i -> stack(it.opt(i)) } }

        private fun json(s: Stack?): Any = if (s == null) JSONObject.NULL else JSONArray().put(s.id).put(s.amount)
        private fun json(list: List<Stack?>) = JSONArray().apply { list.forEach { put(json(it)) } }

        // "Shark", or "Coins x 1,250,000"
        private fun line(name: String, amount: Int) = if (amount > 1) "$name x ${"%,d".format(amount)}" else name

        // The same item in several places counted together, in the order they first come
        private fun counted(stacks: List<Stack?>, nameOf: (Int) -> String): List<String> {
            val total = LinkedHashMap<Int, Long>()
            for (s in stacks) if (s != null) total[s.id] = (total[s.id] ?: 0L) + s.amount
            return total.map { (id, n) -> line(nameOf(id), n.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
        }
    }

    var notes = ""
    // The item lists of loadouts saved before screenshots (see above): emptied part by part as scans replace them
    var spellbook: Int? = null                         // 0-3 (see SPELLBOOKS), or null if not set
    val inventory = arrayOfNulls<Stack>(INVENTORY_SIZE)
    val equipment = HashMap<String, Stack>()           // by slot ("head", "weapon"...)
    var runePouch: MutableList<Stack?>? = null         // null if the loadout has no rune pouch contents
    var boltPouch: MutableList<Stack?>? = null
    var quiver: Stack? = null

    // Whether it still has an item list from before screenshots
    val hasItems get() = inventory.any { it != null } || equipment.isNotEmpty() || quiver != null ||
        runePouch?.any { it != null } == true || boltPouch?.any { it != null } == true || spellbook != null

    // A scan saved a screenshot of the inventory: the old list of it (and of the bolt pouch, kept in the inventory) goes
    fun forgetInventory() { inventory.fill(null); boltPouch = null }
    // ...of the equipment (the quiver is worn too)
    fun forgetEquipment() { equipment.clear(); quiver = null }
    fun forgetRunePouch() { runePouch = null }
    fun forgetSpellbook() { spellbook = null }

    // The item list from before screenshots, as text: each part that has anything, with its items by name.
    // nameOf: an item's name from its ID.
    fun itemLines(nameOf: (Int) -> String): List<ItemList> {
        val parts = ArrayList<ItemList>()
        counted(inventory.toList(), nameOf).takeIf { it.isNotEmpty() }?.let { parts.add(ItemList("Inventory", it)) }
        val worn = SLOTS.mapNotNull { slot -> equipment[slot]?.let { "${SLOT_LABELS[slot]}: ${line(nameOf(it.id), it.amount)}" } } +
            listOfNotNull(quiver?.let { "Quiver: ${line(nameOf(it.id), it.amount)}" })
        if (worn.isNotEmpty()) parts.add(ItemList("Equipment", worn))
        runePouch?.let { counted(it, nameOf) }?.takeIf { it.isNotEmpty() }?.let { parts.add(ItemList("Rune pouch", it)) }
        boltPouch?.let { counted(it, nameOf) }?.takeIf { it.isNotEmpty() }?.let { parts.add(ItemList("Bolt pouch", it)) }
        spellbook?.let { parts.add(ItemList("Spellbook", listOf(SPELLBOOKS[it]))) }
        return parts
    }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("name", name)
        if (notes.isNotEmpty()) put("notes", notes)
        spellbook?.let { put("sb", it) }
        if (inventory.any { it != null }) put("inv", json(inventory.toList()))
        if (equipment.isNotEmpty()) put("eq", JSONObject().apply { for ((slot, s) in equipment) put(slot, json(s)) })
        runePouch?.let { put("rp", json(it)) }
        boltPouch?.let { put("bp", json(it)) }
        quiver?.let { put("qv", json(it)) }
    }
}

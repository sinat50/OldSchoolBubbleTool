package com.sinat.osrsbubbletool

// The DPS Calculator's Import my gear: from what the equipment tab was read as (GearSummaryReader.readEquipmentBySummaries)
// to the calculator's item for each slot. No Android parts, so it's tested on the PC (GearImportTest).
object GearImport {

    // Bolts that fit about this much (or less) worse than the best guess count as just as likely: the kinds of gem
    // bolts differ only in their tips, which the ammo's amount covers
    private const val AMMO_MARGIN = 0.6f

    // The gem bolts people use in fights, preferred over the others when several fit about as well
    private val COMMON_BOLTS = listOf("ruby", "diamond", "dragonstone", "onyx")

    // slots: each slot's guesses as read (an icon's item ID and its fit, best first; none when the slot is empty).
    // alike: items that share an icon (ItemData.lookAlikes). calculatorId: the calculator's item ID for an item read
    // (its ID, slot, name and wiki picture), or null if the calculator doesn't have it.
    // Returns each slot's calculator item ID, or null for nothing there (or nothing the calculator has).
    fun choose(slots: Map<String, List<Pair<Int, Float>>>, data: ItemData, alike: Map<Int, Int>,
               calculatorId: (id: Int, slot: String, name: String, picture: String?) -> Int?): Map<String, Int?> {
        val out = LinkedHashMap<String, Int?>()
        for (slot in CalculatorItems.SLOT_ORDER) {
            val fits = slots[slot].orEmpty()
            if (fits.isEmpty()) { out[slot] = null; continue }
            // each guess as the item to pick for its icon (charged before uncharged), with its best fit
            val fitOf = HashMap<Int, Float>()
            for ((id, fit) in fits) { val shown = alike[id] ?: id; fitOf[shown] = minOf(fitOf[shown] ?: fit, fit) }
            var guesses = data.fromIcons(fits.map { it.first }, fits.map { it.second }, alike).map { it to (fitOf[it] ?: Float.MAX_VALUE) }
            if (slot == "ammo") guesses = ammo(guesses, data)
            out[slot] = guesses.firstNotNullOfOrNull { (id, _) ->
                data[id]?.let { item -> calculatorId(id, slot, item.name, item.pictures.firstOrNull()) }
            }
        }
        return out
    }

    // Bolts as their enchanted version (nobody fights with plain gem bolts), and the common kinds first when several
    // fit about as well
    private fun ammo(guesses: List<Pair<Int, Float>>, data: ItemData): List<Pair<Int, Float>> {
        val enchanted = guesses.map { (id, fit) -> (enchantedOf(id, data) ?: id) to fit }
            .groupBy { it.first }.map { (id, list) -> id to list.minOf { it.second } }.sortedBy { it.second }
        if (enchanted.isEmpty()) return enchanted
        val best = enchanted[0].second
        val common = enchanted.firstOrNull { (id, fit) ->
            fit <= best + AMMO_MARGIN && data[id]?.name?.lowercase()?.let { n -> n.endsWith("bolts (e)") && COMMON_BOLTS.any { n.startsWith(it) } } == true
        } ?: return enchanted
        return listOf(common) + enchanted.filter { it != common }
    }

    // A plain gem bolt's enchanted version ("Ruby dragon bolts" to "Ruby dragon bolts (e)"), or null if it has none
    private fun enchantedOf(id: Int, data: ItemData): Int? {
        val name = data[id]?.name ?: return null
        if (!name.endsWith(" bolts")) return null
        return data.all.firstOrNull { it.name == "$name (e)" && it.slot == "ammo" }?.id
    }
}

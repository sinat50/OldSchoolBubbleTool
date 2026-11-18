package com.sinat.osrsbubbletool

// The Mastering Mixology minigame's potions, from the Mastering Mixology RuneLite plugin by Hexagon (BSD 2-Clause)
// and the OSRS Wiki. No Android parts, so it can be tested on a computer.
object MixologyData {

    // The three pastes, with the colours the plugin uses for them (also the levers' dot colours)
    enum class Paste(val letter: Char, val colour: Int) {
        MOX('M', 0x03A9F4),   // blue
        AGA('A', 0x00E676),   // green
        LYE('L', 0xE91E63)    // pink
    }

    // A potion: its name exactly as the Potion Orders box writes it, its recipe (the three pastes, in the
    // plugin's order) and the Herblore level it needs
    class Potion(val name: String, val recipe: String, val level: Int) {
        val pastes: List<Paste> get() = recipe.map { c -> Paste.entries.first { it.letter == c } }
    }

    // The names must match tools/mixology/build_name_shapes.py (it makes the shapes the tool reads them by)
    val POTIONS = listOf(
        Potion("Mammoth-might mix", "MMM", 60),
        Potion("Mystic mana amalgam", "MMA", 63),
        Potion("Marley's moonlight", "MML", 66),
        Potion("Alco-augmentator", "AAA", 60),
        Potion("Azure aura mix", "AAM", 69),
        Potion("Aqualux amalgam", "ALA", 72),
        Potion("Liplack liquor", "LLL", 60),
        Potion("Megalite liquid", "MLL", 75),
        Potion("Anti-leech lotion", "ALL", 78),
        Potion("Mixalot", "MAL", 81)
    )

    fun potion(name: String): Potion? = POTIONS.firstOrNull { it.name == name }
}

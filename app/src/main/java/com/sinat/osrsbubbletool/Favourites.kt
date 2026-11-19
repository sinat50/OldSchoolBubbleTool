package com.sinat.osrsbubbletool

// The bubble menu's favourite tools: up to 6, in the order they were added. Saved as the tools' names with commas
// between them. No Android parts, so FavouritesTest can check the rules on the PC.
object Favourites {
    const val MOST = 6

    fun read(saved: String?): List<String> = saved.orEmpty().split(',').filter { it.isNotBlank() }.distinct().take(MOST)

    fun write(names: List<String>) = names.joinToString(",")

    // A tool held in the menu: taken out if it's a favourite, otherwise added at the end. Null: there's no room for it.
    fun toggled(names: List<String>, name: String): List<String>? = when {
        name in names -> names - name
        names.size >= MOST -> null
        else -> names + name
    }
}

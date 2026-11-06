package com.sinat.osrsbubbletool

import android.content.Context
import android.os.Handler
import android.os.Looper

// The item list (assets/items/items.tsv: every item's ID, name and equipment slot, text only), read once for the
// whole app the first time it's needed. The DPS Calculator's Import my gear uses it to name what it read, and Gear
// Loadouts to list the items of loadouts saved before it kept screenshots. Reading it takes a moment, so it's done
// in the background; get() calls back on the main thread when it's ready.
object ItemCatalog {
    private var data: ItemData? = null
    private var loading = false
    private val waiting = ArrayList<(ItemData) -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    // Call from the main thread
    fun get(context: Context, ready: (ItemData) -> Unit) {
        data?.let { ready(it); return }
        waiting.add(ready)
        if (loading) return
        loading = true
        val assets = context.applicationContext.assets
        Thread {
            val read = try {
                val noted = try { assets.open("items/noted.tsv").bufferedReader().use { it.readText() } } catch (_: Exception) { "" }
                ItemData(assets.open("items/items.tsv").bufferedReader().use { it.readText() }, noted)
            } catch (_: Exception) {
                ItemData("")   // missing or damaged: no items rather than a crash
            }
            main.post {
                data = read
                loading = false
                waiting.toList().forEach { it(read) }
                waiting.clear()
            }
        }.start()
    }

    // Items that look exactly alike in the game (assets/items/canvas.tsv: an item's ID, then the ID of the one it
    // looks just like; made by tools/items/build_icons.py). Import my gear can't tell these apart, so it picks one.
    fun sharedIcons(context: Context): Map<Int, Int> {
        val map = HashMap<Int, Int>()
        try {
            context.applicationContext.assets.open("items/canvas.tsv").bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (line.startsWith("#")) continue
                    val parts = line.split('\t')
                    val a = parts.getOrNull(0)?.toIntOrNull() ?: continue
                    map[a] = parts.getOrNull(1)?.toIntOrNull() ?: continue
                }
            }
        } catch (_: Exception) { }
        return map
    }
}

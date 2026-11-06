package com.sinat.osrsbubbletool

import android.content.Context
import android.os.Handler
import android.os.Looper

// What the solver knows about every puzzle box: a short colour summary of each tile, not the pictures. They come with
// the app (assets/puzzle_tiles.bin, made by tools/puzzle-box/build_tile_fingerprints.py: add a new puzzle there).
class PuzzleReferences(context: Context) {

    // tiles[tile] = the tile's 8 x 8 average colours, in tile order (left to right, top to bottom; 24 is the empty space)
    class Puzzle(val name: String, val tiles: Array<FloatArray>)

    private val mainHandler = Handler(Looper.getMainLooper())
    private val assets = context.applicationContext.assets
    private var loaded: List<Puzzle>? = null
    private var waiting: MutableList<(List<Puzzle>?, String?) -> Unit>? = null   // asked while the file is being read

    // Reads the file (the first time, in the background).
    // Calls onDone on the main thread with the puzzles, or with an error message. (Asked again while it's still
    // being read, it waits for that same reading instead of starting another.)
    fun load(onDone: (List<Puzzle>?, String?) -> Unit) {
        loaded?.let { onDone(it, null); return }
        waiting?.let { it.add(onDone); return }
        val callers = mutableListOf(onDone)
        waiting = callers
        Thread {
            var puzzles: List<Puzzle>? = null
            var error: String? = null
            try {
                puzzles = TileMatcher.readPuzzles(assets.open("puzzle_tiles.bin").use { it.readBytes() })
            } catch (e: Exception) {
                error = e.message ?: "the file is missing"
            }
            mainHandler.post {
                loaded = puzzles
                waiting = null
                callers.forEach { it(puzzles, error) }
            }
        }.start()
    }
}

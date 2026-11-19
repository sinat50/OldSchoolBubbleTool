package com.sinat.osrsbubbletool

import kotlin.math.abs

// Keeps your place in a quest guide when an app update changes the guide. No Android parts, so it's tested on the PC.
//
// Your place used to be saved as a step number only, so a new step early in a guide moved you to the wrong step.
// Two things fix that: a table made once for v1.3.0 (assets/quest_step_moves.tsv) moves places saved by older
// versions, and from now on the wording of your step is saved too, so it can be found again after any update.
object QuestProgress {

    // Saved instead of a step's wording when the whole guide is done
    const val FINISHED = "\u0000finished"

    // How alike two wordings must be (0 to 1) to count as the same step reworded
    private const val SAME_STEP = 0.7

    // From old step "from" on, steps follow on one by one from new step "to", until the next Move
    class Move(val from: Int, val to: Int)

    // Reads the table's lines: quest name, tab, then pairs like 0:0,5:7. Lines starting with # are notes.
    fun readTable(lines: Sequence<String>): Map<String, List<Move>> = lines
        .filter { it.isNotBlank() && !it.startsWith("#") }
        .associate { line ->
            val (quest, pairs) = line.split('\t', limit = 2)
            quest to pairs.split(',').map { pair ->
                val (from, to) = pair.split(':')
                Move(from.toInt(), to.toInt())
            }
        }

    // A place saved by an older version, as a place in the new guide
    fun moved(place: Int, moves: List<Move>): Int {
        val move = moves.lastOrNull { it.from <= place } ?: return place
        return move.to + (place - move.from)
    }

    // What to save alongside the step number
    fun wordingAt(steps: List<String>, place: Int): String = steps.getOrElse(place) { FINISHED }

    // Finds your step: where it was saved, or where the same wording is now, or the step most like it.
    // With nothing to go on, the saved number stands.
    fun find(steps: List<String>, saved: Int, wording: String?): Int {
        val place = saved.coerceIn(0, steps.size)
        if (wording == null) return place
        if (wording == FINISHED) return steps.size
        if (steps.getOrNull(place) == wording) return place
        val same = steps.indices.filter { steps[it] == wording }.minByOrNull { abs(it - place) }
        if (same != null) return same
        // Reworded: the most alike step, and the nearest one if two are equally alike
        val mine = words(wording)
        return steps.indices
            .map { it to likeness(mine, words(steps[it])) }
            .filter { it.second >= SAME_STEP }
            .maxWithOrNull(compareBy({ it.second }, { -abs(it.first - place) }))
            ?.first ?: place
    }

    private fun words(text: String) = text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.isNotEmpty() }.toSet()

    private fun likeness(a: Set<String>, b: Set<String>): Double =
        if (a.isEmpty() || b.isEmpty()) 0.0 else 2.0 * a.intersect(b).size / (a.size + b.size)
}

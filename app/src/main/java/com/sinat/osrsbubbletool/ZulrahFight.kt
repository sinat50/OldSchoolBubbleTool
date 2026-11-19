/*
 * Zulrah rotation data and arena layout adapted from the "Zulrah Helper" RuneLite plugin
 * (github.com/while-loop/runelite-plugins).
 *
 * Copyright (c) 2020, Anthony Alves
 * Copyright (c) 2026, Ron Young <https://github.com/raiyni>
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.sinat.osrsbubbletool

// The Zulrah fight: the four rotations and where you are in them. No Android parts, so ZulrahFightTest checks it on
// the PC. The player says what Zulrah did; nothing here reads the game.
class ZulrahFight {

    enum class Form(val word: String) { GREEN("Serp"), RED("Magma"), BLUE("Tanz") }

    // Where Zulrah surfaces, on the arena drawing (ZulrahTool.MapView)
    enum class Spot(val x: Float, val y: Float) {
        MIDDLE(40f, 30f),
        SOUTH(39f, -10f),
        WEST(82f, 25f),
        EAST(-5f, 35f)
    }

    // Places to stand: one game tile each, by its map position (x east, y north). Worked out from the plugin's marks on
    // its arena picture, whose tiles are 5 pixels and whose pillars sit on the game's pillar tiles (from the game cache).
    // The names are the plugin's, as seen on its picture (south at the top).
    enum class Stand(val x: Int, val y: Int) {
        START(2272, 3078),
        START_MAGMA(2274, 3076),
        PILLAR_1_SOUTH(2272, 3072),
        NORTH(2268, 3070),
        PILLAR_2_SOUTH(2264, 3072),
        PILLAR_2_EAST(2264, 3071)
    }

    // The island's floor you can walk on, from the game cache (map square 9007), and the two pillars, which stand on
    // tiles of their own
    object Arena {
        // each row: its y, then pairs of first and last x
        private val ROWS = listOf(
            intArrayOf(3068, 2266, 2270),
            intArrayOf(3069, 2264, 2272),
            intArrayOf(3070, 2262, 2273),
            intArrayOf(3071, 2262, 2264, 2272, 2274),
            intArrayOf(3072, 2262, 2264, 2272, 2274),
            intArrayOf(3073, 2262, 2264, 2272, 2274),
            intArrayOf(3074, 2262, 2264, 2272, 2274),
            intArrayOf(3075, 2262, 2264, 2272, 2274),
            intArrayOf(3076, 2262, 2264, 2272, 2274),
            intArrayOf(3077, 2263, 2264, 2272, 2274),
            intArrayOf(3078, 2264, 2264, 2272, 2272)
        )
        val tiles: List<Pair<Int, Int>> = ROWS.flatMap { row ->
            (1 until row.size step 2).flatMap { i -> (row[i]..row[i + 1]).map { x -> x to row[0] } }
        }
        val pillars = listOf(2271 to 3071, 2265 to 3071)
    }

    enum class Pray(val word: String) { MISSILES("Range"), MAGIC("Mage") }

    class Phase(
        val form: Form,
        val spot: Spot,
        val prayers: List<Pray>,      // none: safe to turn prayer off. Two: the first, then the second (the "Jad" phase)
        val attacks: Int,
        val venom: Int,               // venom clouds
        val snakelings: Int,
        val stands: List<Stand>,
        val title: String? = null     // the name the rotation goes by from this phase on
    ) {
        fun sameLook(other: Phase) = form == other.form && spot == other.spot
    }

    // One possible next phase. restart: the rotation is over, and Zulrah starts again from the first phase.
    class Option(val phase: Phase, val rotations: List<Int>, val restart: Boolean)

    private class State(val candidates: List<Int>, val index: Int, val looped: Boolean)

    private var state = fresh(looped = false)
    private val history = ArrayDeque<State>()

    private fun fresh(looped: Boolean) = State(ROTATIONS.indices.toList(), 0, looped)
    private fun phases() = ROTATIONS[state.candidates.first()]

    // The phase you're on. The first phase of the fight needs no prayer; when Zulrah comes round to it again, it does.
    val current: Phase get() = if (state.index == 0 && state.looped) AGAIN else phases()[state.index]
    val phaseNumber: Int get() = state.index + 1
    val canUndo: Boolean get() = history.isNotEmpty()

    // The rotation's name so far, like "Magma A"
    val title: String get() = (state.index downTo 0).firstNotNullOfOrNull { phases()[it].title } ?: ""

    // What Zulrah could do next. Phases that look the same on screen are one choice.
    fun nextOptions(): List<Option> {
        val groups = mutableListOf<Pair<Phase, MutableList<Int>>>()
        for (r in state.candidates) {
            val list = ROTATIONS[r]
            if (state.index + 1 >= list.size) return listOf(Option(AGAIN, ROTATIONS.indices.toList(), restart = true))
            val next = list[state.index + 1]
            val group = groups.firstOrNull { it.first.sameLook(next) }
            if (group != null) group.second.add(r) else groups.add(next to mutableListOf(r))
        }
        return groups.map { Option(it.first, it.second, restart = false) }
    }

    // The phases after the next one, for as long as the rotation is certain
    fun upcoming(): List<Phase> {
        if (nextOptions().size != 1 || nextOptions().first().restart) return emptyList()
        val later = mutableListOf<Phase>()
        var index = state.index + 2
        while (state.candidates.all { index < ROTATIONS[it].size }) {
            val phase = ROTATIONS[state.candidates.first()][index]
            if (!state.candidates.all { ROTATIONS[it][index].sameLook(phase) }) break
            later.add(phase)
            index++
        }
        return later
    }

    fun choose(option: Option) {
        history.addLast(state)
        state = if (option.restart) fresh(looped = true) else State(option.rotations, state.index + 1, state.looped)
    }

    fun undo() {
        if (history.isNotEmpty()) state = history.removeLast()
    }

    fun reset() {
        history.clear()
        state = fresh(looped = false)
    }

    companion object {
        private val none = emptyList<Pray>()
        private val range = listOf(Pray.MISSILES)
        private val mage = listOf(Pray.MAGIC)
        private val rangeThenMage = listOf(Pray.MISSILES, Pray.MAGIC)
        private val mageThenRange = listOf(Pray.MAGIC, Pray.MISSILES)

        private fun p(form: Form, spot: Spot, prayers: List<Pray>, attacks: Int, venom: Int, snakes: Int, vararg stands: Stand) =
            Phase(form, spot, prayers, attacks, venom, snakes, stands.toList())

        private fun named(title: String, form: Form, spot: Spot, prayers: List<Pray>, attacks: Int, venom: Int, snakes: Int, vararg stands: Stand) =
            Phase(form, spot, prayers, attacks, venom, snakes, stands.toList(), title)

        private val FIRST = named("Start", Form.GREEN, Spot.MIDDLE, none, 5, 4, 0, Stand.START)
        private val AGAIN = named("Start", Form.GREEN, Spot.MIDDLE, range, 5, 4, 0, Stand.START)

        private val MAGMA_START = listOf(
            FIRST,
            named("Magma", Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.START, Stand.START_MAGMA),
            p(Form.BLUE, Spot.MIDDLE, mage, 4, 0, 0, Stand.START)
        )

        private val ROTATIONS = listOf(
            MAGMA_START + listOf(
                named("Magma A", Form.GREEN, Spot.SOUTH, range, 5, 2, 2, Stand.PILLAR_2_SOUTH),
                p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.PILLAR_2_SOUTH),
                p(Form.BLUE, Spot.WEST, mage, 5, 0, 0, Stand.NORTH),
                p(Form.GREEN, Spot.SOUTH, none, 0, 3, 2, Stand.PILLAR_1_SOUTH),
                p(Form.BLUE, Spot.SOUTH, mage, 5, 2, 2, Stand.PILLAR_1_SOUTH),
                p(Form.GREEN, Spot.WEST, rangeThenMage, 10, 4, 0, Stand.PILLAR_2_SOUTH),
                p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.START, Stand.START_MAGMA)
            ),
            MAGMA_START + listOf(
                named("Magma B", Form.GREEN, Spot.WEST, none, 0, 3, 2, Stand.PILLAR_2_SOUTH),
                p(Form.BLUE, Spot.SOUTH, mage, 5, 2, 2, Stand.PILLAR_2_SOUTH),
                p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.PILLAR_2_SOUTH),
                p(Form.GREEN, Spot.EAST, range, 5, 0, 0, Stand.NORTH),
                p(Form.BLUE, Spot.SOUTH, mage, 5, 2, 2, Stand.PILLAR_2_SOUTH),
                p(Form.GREEN, Spot.WEST, rangeThenMage, 10, 4, 0, Stand.PILLAR_2_SOUTH),
                p(Form.RED, Spot.MIDDLE, none, 2, 0, 0, Stand.START, Stand.START_MAGMA)
            ),
            listOf(
                FIRST,
                named("Serp", Form.GREEN, Spot.EAST, range, 5, 0, 2, Stand.START),
                p(Form.RED, Spot.MIDDLE, none, 2, 3, 2, Stand.PILLAR_1_SOUTH),
                p(Form.BLUE, Spot.WEST, mage, 5, 0, 0, Stand.NORTH),
                p(Form.GREEN, Spot.SOUTH, range, 5, 0, 0, Stand.NORTH),
                p(Form.BLUE, Spot.EAST, mage, 5, 0, 0, Stand.NORTH),
                p(Form.GREEN, Spot.MIDDLE, none, 0, 3, 2, Stand.PILLAR_2_SOUTH),
                p(Form.GREEN, Spot.WEST, range, 5, 0, 0, Stand.PILLAR_2_SOUTH),
                p(Form.BLUE, Spot.MIDDLE, mage, 5, 2, 2, Stand.PILLAR_1_SOUTH),
                p(Form.GREEN, Spot.EAST, mageThenRange, 10, 0, 0, Stand.PILLAR_1_SOUTH),
                p(Form.BLUE, Spot.MIDDLE, none, 0, 0, 2, Stand.START)
            ),
            listOf(
                FIRST,
                named("Tanz", Form.BLUE, Spot.EAST, mage, 6, 0, 2, Stand.START),
                p(Form.GREEN, Spot.SOUTH, range, 4, 2, 0, Stand.PILLAR_2_SOUTH),
                p(Form.BLUE, Spot.WEST, mage, 4, 0, 2, Stand.PILLAR_2_SOUTH),
                p(Form.RED, Spot.MIDDLE, none, 2, 2, 0, Stand.PILLAR_1_SOUTH),
                p(Form.GREEN, Spot.EAST, range, 4, 0, 0, Stand.PILLAR_1_SOUTH),
                p(Form.GREEN, Spot.SOUTH, none, 0, 3, 2, Stand.PILLAR_1_SOUTH),
                p(Form.BLUE, Spot.WEST, mage, 5, 4, 0, Stand.PILLAR_2_EAST),
                p(Form.GREEN, Spot.MIDDLE, range, 5, 0, 0, Stand.PILLAR_1_SOUTH),
                p(Form.BLUE, Spot.MIDDLE, mage, 4, 3, 0, Stand.PILLAR_1_SOUTH),
                p(Form.GREEN, Spot.EAST, mageThenRange, 8, 0, 0, Stand.PILLAR_1_SOUTH),
                p(Form.BLUE, Spot.MIDDLE, none, 0, 0, 2, Stand.START)
            )
        )
    }
}

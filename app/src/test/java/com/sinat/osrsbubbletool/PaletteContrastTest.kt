package com.sinat.osrsbubbletool

import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

// Every colour pair people read must reach 4.5:1, the usual minimum for normal text, in light mode and dark mode.
class PaletteContrastTest {

    @After fun backToLight() { Palette.dark = false }

    private fun light(channel: Int): Double {
        val c = channel / 255.0
        return if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)
    }

    private fun brightness(c: Int) =
        0.2126 * light(c shr 16 and 255) + 0.7152 * light(c shr 8 and 255) + 0.0722 * light(c and 255)

    private fun ratio(a: Int, b: Int): Double {
        val high = maxOf(brightness(a), brightness(b))
        val low = minOf(brightness(a), brightness(b))
        return (high + 0.05) / (low + 0.05)
    }

    private fun check(what: String, writing: Int, behind: Int) {
        val r = ratio(writing, behind)
        assertTrue("$what (dark = ${Palette.dark}) is only ${"%.2f".format(r)}:1", r >= 4.5)
    }

    private fun inBothModes(checks: () -> Unit) {
        for (mode in listOf(false, true)) {
            Palette.dark = mode
            checks()
        }
    }

    @Test fun writing_reads_on_every_background() = inBothModes {
        val writing = mapOf("text" to Palette.TEXT, "muted" to Palette.MUTED, "accent" to Palette.ACCENT,
            "ok" to Palette.OK, "bad" to Palette.BAD, "caution" to Palette.CAUTION)
        val behind = mapOf("bg" to Palette.BG, "surface" to Palette.SURFACE, "raised" to Palette.RAISED)
        for ((w, colour) in writing) for ((b, back) in behind) check("$w on $b", colour, back)
    }

    // A window's frame needs 3:1 against what's beside it (the usual minimum for shapes), so it shows on dark scenes
    @Test fun the_window_frame_shows_against_every_background() = inBothModes {
        for (back in listOf(Palette.BG, Palette.SURFACE, Palette.RAISED)) {
            val r = ratio(Palette.BORDER, back)
            assertTrue("frame (dark = ${Palette.dark}) is only ${"%.2f".format(r)}:1", r >= 3.0)
        }
    }

    @Test fun gold_buttons_read() = inBothModes { check("gold button", Palette.ON_GOLD, Palette.GOLD) }

    @Test fun white_writing_reads_on_the_green_and_red_tags() = inBothModes {
        check("white on the green tag", colour("#FFFFFF"), Palette.TAG_GREEN)
        check("white on the red tag", colour("#FFFFFF"), Palette.TAG_RED)
    }
}

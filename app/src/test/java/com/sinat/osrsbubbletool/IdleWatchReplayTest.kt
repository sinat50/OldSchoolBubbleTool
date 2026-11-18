package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// The Idle Notifier on the owner's own recordings (pictures taken from the phone while woodcutting, about 2 a second,
// in Documents\Androiddev\afk_recordings: see .scratch/afk-alert/spec.md). Skipped on a computer without them.
// What really happened was worked out from the pictures and the XP drops (tools/idle-detection/run_all.py), and the
// character's height in each stretch measured by eye (the recordings weren't made with the zoom guide).
// The check: no alert while working, and an alert in each stretch of standing, about as soon as the PC detector's.
class IdleWatchReplayTest {

    private val folder = File(System.getProperty("user.home"), "Documents/Androiddev/afk_recordings")

    private class Picture(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        private val pixels = image.getRGB(0, 0, width, height, null, 0, width)
        override fun rgb(x: Int, y: Int) = pixels[y * width + x] and 0xFFFFFF
    }

    // Plays a recording's pictures, about one a second from `from` seconds on, and gives back the alert times
    // (extraWait: the Wait slider's seconds on top of the detector's own 5)
    private fun alerts(name: String, characterHeight: Int, from: Double = 0.0, extraWait: Double = 0.0): List<Double> {
        val dir = File(folder, name)
        val files = dir.listFiles { f -> f.name.startsWith("f_") && f.name.endsWith(".png") }?.sortedBy { it.name }
        assumeTrue("no recording at $dir", !files.isNullOrEmpty())
        val ms = files!!.map { it.name.substring(2, it.name.length - 4).toLong() }
        val watch = IdleWatch(characterHeight / 1080.0)
        watch.extraWait = extraWait
        val found = ArrayList<Double>()
        var next = from
        for ((i, f) in files.withIndex()) {
            val t = (ms[i] - ms[0]) / 1000.0
            if (t + 1e-6 < next) continue
            next = t + 1.0
            if (watch.look(Picture(f), t).alert) found.add(t)
        }
        println("$name: alerts at ${found.map { "%.1f".format(it) }}")
        return found
    }

    @Test
    fun woodcutting2() {
        // standing until 8 s, chopping 9.5-56 s, standing 57-86 s; the character about 110 px tall
        val at = alerts("woodcutting_2", 110)
        assertTrue("no alert while chopping", at.none { it in 11.5..56.0 })
        assertTrue("alert for the first standing", at.any { it <= 11.5 })
        assertTrue("alert for the last standing, soon enough", at.any { it in 57.0..72.0 })
    }

    @Test
    fun fire1() {
        // someone lit a fire under the character (pictures taken from the video, 2 a second: notes.md in its folder).
        // Chopping until about 17 s (the camera turned at 5-9 s), then standing in the fire's smoke to the end (37 s),
        // with other players walking through the box until 25 s. The box is the guide's own (the app's): the smoke
        // alone keeps 5-25% of it changing, and only the colour reading (IdleWatch, step 3b) tells it from work.
        // Both ways of landing one look a second on the pictures.
        for (from in listOf(0.0, 0.5)) {
            val at = alerts("fire_1", 162, from)
            assertTrue("no alert while chopping", at.none { it < 17.5 })
            assertTrue("alert for the standing in the smoke", at.any { it in 25.0..33.0 })
            assertEquals("one alert", 1, at.size)
        }
    }

    @Test
    fun fire2() {
        // alone, at the guide's size, standing on a fire (bright flames and smoke at the feet), recorded with the app
        // running: it never alerted. Start at about 5 s; chopping 7-12.5 s; then tending the fire (bending down with a
        // log at 13.3 s and at 19.3 s, standing still in between); standing from about 22 s to the end (55.5 s).
        // The flames and smoke keep 1.5-13% of the box changing, but hardly any of it changes colour (0-2.3%).
        for (from in listOf(5.0, 5.5)) {
            val at = alerts("fire_2", 162, from)
            assertTrue("no alert while chopping", at.none { it < 14.0 })
            assertTrue("alert for the standing, soon enough", at.any { it <= 30.0 })
            assertEquals("one alert", 1, at.size)
        }
        // with the Wait slider at 15 s (10 more): the smoke mustn't break the wait, so the alert comes 10 s later
        val late = alerts("fire_2", 162, 5.0, extraWait = 10.0)
        assertEquals("one alert with the longest wait", 1, late.size)
        assertTrue("about 10 s later", late[0] in 32.0..36.0)
    }

    @Test
    fun woodcutting1() {
        // chopping a magic tree until 240.8 s (with an 89 s gap between XP drops), then standing to 275 s; from 87 s
        // on (the zoom changed before that) the character is about 90-100 px tall
        val at = alerts("woodcutting_1", 95, from = 87.0)
        assertTrue("no alert while chopping", at.none { it < 241.0 })
        assertTrue("alert for the standing, soon enough", at.any { it in 241.0..253.0 })
    }
}

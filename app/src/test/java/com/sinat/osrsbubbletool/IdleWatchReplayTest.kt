package com.sinat.osrsbubbletool

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
    private fun alerts(name: String, characterHeight: Int, from: Double = 0.0): List<Double> {
        val dir = File(folder, name)
        val files = dir.listFiles { f -> f.name.startsWith("f_") && f.name.endsWith(".png") }?.sortedBy { it.name }
        assumeTrue("no recording at $dir", !files.isNullOrEmpty())
        val ms = files!!.map { it.name.substring(2, it.name.length - 4).toLong() }
        val watch = IdleWatch(characterHeight / 1080.0)
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
    fun woodcutting1() {
        // chopping a magic tree until 240.8 s (with an 89 s gap between XP drops), then standing to 275 s; from 87 s
        // on (the zoom changed before that) the character is about 90-100 px tall
        val at = alerts("woodcutting_1", 95, from = 87.0)
        assertTrue("no alert while chopping", at.none { it < 241.0 })
        assertTrue("alert for the standing, soon enough", at.any { it in 241.0..253.0 })
    }
}

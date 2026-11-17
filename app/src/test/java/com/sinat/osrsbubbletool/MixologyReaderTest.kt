package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// The Mastering Mixology tool's order reader. The screenshots are the owner's (Documents\Androiddev\mixology_shots,
// or set MIXOLOGY_SHOTS=<folder>; described in its notes.md), not part of the project: those tests are skipped
// without them. The orders on screen were read by eye: Alco-augmentator, Megalite liquid, Alco-augmentator.
class MixologyReaderTest {

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
    }

    private val dir = File(System.getenv("MIXOLOGY_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\mixology_shots")
    private val shapes by lazy { MixologyReader.readShapes(File("src/main/assets/mixology/names.tsv").readText()) }
    private val expected = listOf("Alco-augmentator", "Megalite liquid", "Alco-augmentator")

    // Every shot: the screenshots (the game's brightness at its lowest and highest in brightness_min/max: it changes
    // the scene, not the box), and frames every 2 s from levers_2.mp4 (chat open at 8 and 10 s; the camera moving
    // from 22 s, with a pale wall behind the box at 36 s)
    private fun shots(): List<File> =
        listOf("orders_1", "orders_chat_open", "brightness_min", "brightness_max").map { File(dir, "$it.png") } +
            (File(dir, "frames").listFiles { f -> f.name.endsWith(".png") }?.sortedBy { it.name } ?: emptyList())

    @Test
    fun theShapesAreTheAppsPotions() {
        assertEquals(MixologyData.POTIONS.map { it.name }.toSet(), shapes.map { it.name }.toSet())
        assertEquals(10, shapes.size)
    }

    @Test
    fun readsTheOrdersOnEveryShot() {
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (file in shots().filter { it.isFile }) {
            val shot = Shot(file)
            val box = MixologyReader.findOrderBox(shot)
            assertNotNull("${file.name}: no Potion Orders box found", box)
            val orders = MixologyReader.readOrders(shot, box!!, shapes)
            assertEquals(file.name, expected, orders.map { it?.name })
            checked++
        }
        assertTrue("only $checked shots found", checked >= 20)
    }

    @Test
    fun followsTheBoxWhenTheChatOpens() {
        val closed = File(dir, "orders_1.png")
        val open = File(dir, "orders_chat_open.png")
        assumeTrue(closed.isFile && open.isFile)
        val before = MixologyReader.findOrderBox(Shot(closed))!!
        // Told where it was before, it must still find it moved down (about 306 px) and read it
        val shot = Shot(open)
        val after = MixologyReader.findOrderBox(shot, before)!!
        assertTrue("moved ${after.top - before.top} px", after.top - before.top in 280..330)
        assertEquals(before.left, after.left)
        assertEquals(expected, MixologyReader.readOrders(shot, after, shapes).map { it?.name })
        // On the owner's phone one game pixel is about 2.1 screen pixels
        assertEquals(2.115, after.scale, 0.03)
    }

    @Test
    fun theRecipeGoesAfterEachName() {
        val file = File(dir, "orders_1.png")
        assumeTrue(file.isFile)
        val shot = Shot(file)
        val orders = MixologyReader.readOrders(shot, MixologyReader.findOrderBox(shot)!!, shapes)
        // Measured on the screenshot: the names end at x = 515, 471 and 515 (their faint edges 3 px further), on rows
        // 93-121, 148-176 and 203-231. A few pixels either way doesn't matter for placing the recipe.
        for ((order, end) in orders.zip(listOf(516, 472, 516))) {
            assertTrue("ends at ${order!!.textRight}", order.textRight in end - 4..end + 4)
        }
        for ((order, row) in orders.zip(listOf(93, 148, 203))) {
            assertTrue(order!!.top <= row && order.bottom >= row + 28)
        }
    }

    @Test
    fun noBoxOutsideTheLab() {
        // A Puzzle Box screenshot from the owner's phone (Documents\Androiddev\puzzle_shots): no Potion Orders there
        val file = File(dir.parentFile, "puzzle_shots\\Troll_1.png")
        assumeTrue(file.isFile)
        assertNull(MixologyReader.findOrderBox(Shot(file)))
    }

    @Test
    fun squeezeKeepsTheAverage() {
        // 5 columns squeezed to BINS: each share is an average, so the overall average stays the same
        val row = doubleArrayOf(1.0, 4.0, 0.0, 2.0, 3.0)
        val squeezed = MixologyReader.squeeze(row)
        assertEquals(MixologyReader.BINS, squeezed.size)
        assertEquals(2.0, squeezed.average(), 1e-9)
        assertEquals(1.0, squeezed.first(), 1e-9)
        assertEquals(3.0, squeezed.last(), 1e-9)
    }
}

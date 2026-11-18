package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

// The Mastering Mixology tool's bag tags (MixologyBag): which potions are in the inventory. The pictures are frames
// of the owner's recording play_2.mp4 (Documents\Androiddev\mixology_shots\frames3, or set MIXOLOGY_SHOTS=<folder>;
// described in its notes.md), not part of the project: those tests are skipped without them. What's in the bag in
// each frame was read by eye (each kind's colours, and the orders on screen at the time), never taken from the
// reader's own answers.
class MixologyBagTest {

    private class Shot(val image: BufferedImage) : LightBoxReader.PixelSource {
        override val width: Int = image.width
        override val height: Int = image.height
        private val data = IntArray(width * height).also { image.getRGB(0, 0, width, height, it, 0, width) }
        override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
    }

    private val dir = File(System.getenv("MIXOLOGY_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\mixology_shots")
    private val potions by lazy { MixologyBag.readSummaries(File("src/main/assets/mixology/potions.tsv").readText()) }

    private fun picture(name: String): BufferedImage {
        val src = ImageIO.read(File(dir, "frames3/$name.png"))
        val out = BufferedImage(src.width, src.height, BufferedImage.TYPE_INT_RGB)
        out.createGraphics().apply { drawImage(src, 0, 0, null); dispose() }
        return out
    }

    // What's in the bag's first three spaces in each frame, by eye ("" = empty). Every other space is empty in all
    // of them. The kinds by their looks: MLL pale lilac with a red band, AAA plain green, ALA olive green with a
    // yellow band, MMA teal, MML purple with a magenta band, ALL pale with red and green bands, MMM blue, LLL pink.
    // Corked (a station has worked on it) or not, the recipe is the same.
    private val bags = linkedMapOf(
        "play2_006.5s" to listOf("", "", ""),             // nothing in the bag
        "play2_029.5s" to listOf("MLL", "AAA", "AAA"),    // fresh from the vessel, no corks
        "play2_049.5s" to listOf("MLL", "", "AAA"),       // the first one corked; a dark wall behind the bag
        "play2_110.5s" to listOf("MLL", "ALA", ""),
        "play2_129.5s" to listOf("MLL", "", ""),          // the cave's black behind it
        "play2_142.0s" to listOf("MLL", "ALA", "MMA"),
        "play2_182.0s" to listOf("MML", "ALL", ""),       // shelves of pots behind
        "play2_189.5s" to listOf("MML", "ALL", "MMA"),    // none corked
        "play2_221.5s" to listOf("MML", "ALL", "MMA"),    // the first two corked, dark behind
        "play2_254.5s" to listOf("MMM", "", ""),
        "play2_273.5s" to listOf("MMM", "LLL", "MLL"),
        "play2_276.0s" to listOf("", "LLL", "MLL"),
        "play2_286.0s" to listOf("MMM", "LLL", "MLL"),    // the first corked; a station behind the bag
        "play2_312.0s" to listOf("MMM", "LLL", "MLL")     // all corked
    )

    private fun panelOf(shot: Shot, name: String): PanelFinder.Box {
        val panel = PanelFinder.findPanel(shot)
        assertNotNull("$name: no side panel found", panel)
        assertTrue("$name: the inventory tab isn't read as open", PanelFinder.tabOpen(shot, panel!!, 0, PanelFinder.INVENTORY_TAB))
        return panel
    }

    private fun asRow(tags: List<MixologyBag.Tag>): List<String> {
        assertTrue("a tag outside the first three spaces: ${tags.map { it.slot }}", tags.all { it.slot < 3 })
        return (0..2).map { slot -> tags.firstOrNull { it.slot == slot }?.recipe ?: "" }
    }

    // The tool's own tags as the next screen picture shows them: white letters with a black shadow, over the bottom
    // of each potion, at the 80% Android shows overlay windows at
    private fun drawTags(image: BufferedImage, tags: List<MixologyBag.Tag>) {
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.8f)
        for (t in tags) {
            g.font = Font("SansSerif", Font.BOLD, (MixologyBag.TAG_SIZE * t.scale).toInt())
            val x = t.left + MixologyBag.TAG_LEFT * t.scale
            val y = t.top + MixologyBag.TAG_BASELINE * t.scale
            g.color = Color.BLACK
            g.drawString(t.recipe, x + t.scale, y + t.scale)
            g.color = Color.WHITE
            g.drawString(t.recipe, x, y)
        }
        g.dispose()
    }

    @Test
    fun thePotionsFileIsTheAppsPotions() {
        // each of the ten potions twice: without a cork and with one
        assertEquals(20, potions.size)
        assertEquals(MixologyData.POTIONS.map { it.recipe }.sorted(), potions.map { it.recipe }.distinct().sorted())
        for (p in MixologyData.POTIONS) assertEquals(p.name, 2, potions.count { it.recipe == p.recipe })
        // the item numbers the plugin tags: 30011-30020 without a cork, 30021-30030 with
        assertEquals((30011..30030).toList(), potions.map { it.id }.sorted())
        // Mixalot (dark blue with gold bands, MM_POTION_MAL in RuneLite's item list) is items 30020 and 30030
        assertEquals(listOf("MAL", "MAL"), potions.filter { it.id == 30020 || it.id == 30030 }.map { it.recipe })
    }

    @Test
    fun readsThePotionsInTheBag() {
        assumeTrue(File(dir, "frames3/play2_006.5s.png").isFile)
        for ((name, row) in bags) {
            val shot = Shot(picture(name))
            assertEquals(name, row, asRow(MixologyBag.read(shot, panelOf(shot, name), potions)))
        }
    }

    // Which potions a station has finished (corked) and which are fresh from the vessel (an open grey neck), as seen
    // by eye on enlarged crops of the bag's first row: none corked at 29.5 s, the first two at 221.5 s, only the first
    // at 286 s. "done!" and the next station depend on it.
    @Test
    fun tellsFinishedPotionsFromFreshOnes() {
        assumeTrue(File(dir, "frames3/play2_029.5s.png").isFile)
        for ((name, corked) in listOf(
            "play2_029.5s" to listOf(false, false, false),
            "play2_221.5s" to listOf(true, true, false),
            "play2_286.0s" to listOf(true, false, false))) {
            val shot = Shot(picture(name))
            val tags = MixologyBag.read(shot, panelOf(shot, name), potions)
            assertEquals(name, corked, (0..2).map { slot -> tags.first { it.slot == slot }.finished })
        }
    }

    // With the whole screen shared, the next look sees the tool's own letters over the potions. They must change
    // nothing: the same potions read, and each tag's "still there" pixels unchanged.
    @Test
    fun itsOwnTagsDontChangeWhatsRead() {
        assumeTrue(File(dir, "frames3/play2_006.5s.png").isFile)
        var drawn = 0
        for ((name, row) in bags) {
            val image = picture(name)
            val clean = Shot(image)
            val panel = panelOf(clean, name)
            val tags = MixologyBag.read(clean, panel, potions)
            drawTags(image, tags)
            drawn += tags.size
            val tagged = Shot(image)
            assertEquals("$name with its tags drawn", row, asRow(MixologyBag.read(tagged, panel, potions)))
            for (t in tags) assertTrue("$name: space ${t.slot} no longer looks the same", MixologyBag.stillThere(tagged, t))
        }
        assertTrue(drawn >= 25)
    }

    // A tag is for the potion that was read. When another potion (or nothing) is in its space, the few pixels
    // remembered from inside it must show that at once.
    @Test
    fun aTagDoesntOutliveItsPotion() {
        assumeTrue(File(dir, "frames3/play2_189.5s.png").isFile)
        val before = Shot(picture("play2_189.5s"))            // MML, ALL, MMA
        val tags = MixologyBag.read(before, panelOf(before, "play2_189.5s"), potions)
        assertEquals(3, tags.size)
        val same = Shot(picture("play2_221.5s"))              // half a minute on: the first two corked now, the third
        assertTrue(MixologyBag.stillThere(same, tags[2]))     // as it was, with a different scene behind it
        val others = Shot(picture("play2_273.5s"))            // MMM, LLL, MLL in the same three spaces
        for (t in tags) assertFalse("space ${t.slot}", MixologyBag.stillThere(others, t))
        val empty = Shot(picture("play2_006.5s"))
        for (t in tags) assertFalse("space ${t.slot}", MixologyBag.stillThere(empty, t))
    }

    // The tool's rules, look after look (3 a second): the side panel is looked for once, the bag read once a second,
    // and at once when a tagged potion changes.
    @Test
    fun theBagIsReadOnceASecondAndWhenAPotionChanges() {
        assumeTrue(File(dir, "frames3/play2_275.5s.png").isFile)
        val watch = MixologyBag.Watch()
        val full = Shot(picture("play2_275.5s"))              // MMM, LLL, MLL
        assertEquals(listOf("MMM", "LLL", "MLL"), asRow(watch.look(full, 0, potions)))
        assertEquals(1, watch.searches)
        assertEquals(1, watch.reads)
        assertEquals(listOf("MMM", "LLL", "MLL"), asRow(watch.look(full, 333, potions)))
        assertEquals(listOf("MMM", "LLL", "MLL"), asRow(watch.look(full, 666, potions)))
        assertEquals("looks between reads only check the tags' pixels", 1, watch.reads)
        watch.look(full, 1_000, potions)
        assertEquals(2, watch.reads)
        // half a second on, the Mammoth-might mix has gone (handed to a station): its tag goes on the very next look
        val gone = Shot(picture("play2_276.0s"))
        assertEquals(listOf("", "LLL", "MLL"), asRow(watch.look(gone, 1_333, potions)))
        assertEquals(3, watch.reads)
        assertEquals("the side panel is only looked for once", 1, watch.searches)
    }

    // The side panel is found by its outline, and that can be covered: in play2_284.0s one of the tool's own lever
    // dots sits on the panel's left edge, and no panel is found in that frame alone. The tool remembers where the
    // panel was (and checks its tab buttons), so the tags stay.
    @Test
    fun thePanelIsRememberedWhenItsOutlineIsCovered() {
        assumeTrue(File(dir, "frames3/play2_284.0s.png").isFile)
        val dark = Shot(picture("play2_284.0s"))              // nothing, LLL, MLL; the "A" dot on the panel's edge
        assertNull("this frame was picked because the panel isn't found in it alone", PanelFinder.findPanel(dark))
        val watch = MixologyBag.Watch()
        assertEquals(listOf("", "LLL", "MLL"), asRow(watch.look(Shot(picture("play2_283.5s")), 0, potions)))
        assertEquals(listOf("", "LLL", "MLL"), asRow(watch.look(dark, 333, potions)))
        assertEquals(listOf("", "LLL", "MLL"), asRow(watch.look(dark, 1_333, potions)))   // (a fresh read of it too)
        assertEquals(1, watch.searches)
    }

    // Nothing outside the lab is ever tagged: the owner's other screenshots with the side panel on screen (inventories
    // full of other items, at the game's lowest and highest brightness; worn gear; the puzzle box)
    @Test
    fun otherItemsAreNeverTagged() {
        val folders = listOf("loadout_shots", "gear_lookalike_shots", "puzzle_shots").map { File(dir.parentFile, it) }
        assumeTrue(folders[0].isDirectory)
        var inventories = 0
        for (file in folders.flatMap { it.listFiles { f -> f.name.endsWith(".png") }?.sortedBy { f -> f.name } ?: emptyList() }) {
            val shot = Shot(ImageIO.read(file))
            val panel = PanelFinder.findPanel(shot) ?: continue
            if (PanelFinder.tabOpen(shot, panel, 0, PanelFinder.INVENTORY_TAB)) inventories++
            // (read whichever tab is open: nothing there is a Mixology potion either)
            assertEquals(file.name, emptyList<String>(), MixologyBag.read(shot, panel, potions).map { "${it.slot}:${it.recipe}" })
        }
        assertTrue("only $inventories inventories among the screenshots", inventories >= 8)
    }

    // How long the looks take on this computer (printed, not checked)
    @Test
    fun timings() {
        assumeTrue(File(dir, "frames3/play2_275.5s.png").isFile)
        val shot = Shot(picture("play2_275.5s"))
        val panel = panelOf(shot, "play2_275.5s")
        val tags = MixologyBag.read(shot, panel, potions)
        fun time(times: Int, work: Runnable): Double {
            repeat(times / 4) { work.run() }
            val start = System.nanoTime()
            repeat(times) { work.run() }
            return (System.nanoTime() - start) / 1e6 / times
        }
        val empty = Shot(picture("play2_006.5s"))
        val find = time(40) { PanelFinder.findPanel(shot) }
        val read = time(2000) { MixologyBag.read(shot, panel, potions) }
        val readEmpty = time(2000) { MixologyBag.read(empty, panel, potions) }
        val check = time(4000) {
            PanelFinder.stillThere(shot, panel); PanelFinder.tabOpen(shot, panel, 0, 0); tags.forEach { MixologyBag.stillThere(shot, it) }
        }
        println("MixologyBag timings: finding the side panel %.2f ms, reading the bag %.3f ms with 3 potions and %.3f ms empty, a look between reads %.4f ms".format(find, read, readEmpty, check))
    }
}

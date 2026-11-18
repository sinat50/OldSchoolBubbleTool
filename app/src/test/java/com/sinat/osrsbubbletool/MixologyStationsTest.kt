package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.MixologyStations.Carried
import com.sinat.osrsbubbletool.MixologyStations.Station
import com.sinat.osrsbubbletool.MixologyStations.Station.AGITATOR
import com.sinat.osrsbubbletool.MixologyStations.Station.ALEMBIC
import com.sinat.osrsbubbletool.MixologyStations.Station.RETORT
import com.sinat.osrsbubbletool.MixologyStations.Wanted
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// The Mastering Mixology tool's station parts (MixologyStations). The pictures are the owner's
// (Documents\Androiddev\mixology_shots, or set MIXOLOGY_SHOTS=<folder>; described in its notes.md), not part of the
// project: those tests are skipped without them. Every expected answer was read from the pictures by eye (the
// icons), measured on them with a ruler drawn over an enlarged copy (the bars), or worked out by hand (the plans
// and the timings, which are the ones measured in play_2.mp4 at 60 frames a second).
class MixologyStationsTest {

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
    }

    private val dir = File(System.getenv("MIXOLOGY_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\mixology_shots")
    private val shapes by lazy { MixologyReader.readShapes(File("src/main/assets/mixology/names.tsv").readText()) }

    private fun shot(name: String): Shot? = File(dir, name).takeIf { it.isFile }?.let { Shot(it) }

    // The owner's phone: screen pixels per game pixel (the order box's title is 200 pixels wide there)
    private val scale = 2.115

    // ---- 1. The station icons ----

    // The frames kept from play_2.mp4 (frames3/play2_<seconds>s.png), with the three icons read by eye from top to
    // bottom: a flask = Alembic, a pot on legs = Retort, a frame with a "v" = Agitator
    private val play2Icons = mapOf(
        "000.0" to listOf(ALEMBIC, RETORT, ALEMBIC),
        "037.40" to listOf(ALEMBIC, RETORT, ALEMBIC),
        "037.70" to listOf(ALEMBIC, RETORT, ALEMBIC),
        "038.00" to listOf(ALEMBIC, RETORT, ALEMBIC),
        "038.30" to listOf(ALEMBIC, RETORT, ALEMBIC),
        "051.65" to listOf(ALEMBIC, RETORT, ALEMBIC),
        "059.5" to listOf(ALEMBIC, RETORT, ALEMBIC),      // the box over sand and a pillar
        "119.30" to listOf(AGITATOR, AGITATOR, AGITATOR),
        "120.00" to listOf(AGITATOR, AGITATOR, AGITATOR),
        "130.50" to listOf(AGITATOR, AGITATOR, AGITATOR),
        "131.50" to listOf(AGITATOR, AGITATOR, AGITATOR),
        "149.5" to listOf(AGITATOR, AGITATOR, AGITATOR),
        "199.5" to listOf(RETORT, AGITATOR, ALEMBIC),
        "224.50" to listOf(RETORT, AGITATOR, ALEMBIC),
        "224.85" to listOf(RETORT, AGITATOR, ALEMBIC),
        "249.5" to listOf(ALEMBIC, RETORT, RETORT),       // the box over a pale wall and pots
        "293.90" to listOf(ALEMBIC, RETORT, RETORT),
        "319.5" to listOf(AGITATOR, AGITATOR, ALEMBIC)    // the box over leaves
    )

    private fun stations(shot: Shot): List<Station?>? {
        val box = MixologyReader.findOrderBox(shot) ?: return null
        return MixologyStations.stationsOf(shot, box, MixologyReader.readOrders(shot, box, shapes))
    }

    @Test
    fun readsTheStationIconsInPlay2() {
        assumeTrue(File(dir, "frames3").isDirectory)
        var checked = 0
        for ((time, expected) in play2Icons) {
            val shot = shot("frames3/play2_${time}s.png") ?: continue
            assertEquals("play2 at $time s", expected, stations(shot))
            checked++
        }
        assertTrue("only $checked frames found", checked >= 15)
    }

    // Every older picture (13 to 16 November: the screenshots, and the frames of levers_2, levers_3, funnels_1,
    // north_1 to 3 and play_1) has the same three orders, with the icons Agitator, Agitator, Retort: all 57 were
    // looked at side by side. Chat open, both ends of the game's brightness, zoomed, turned, pale walls behind.
    @Test
    fun readsTheStationIconsInTheOlderPictures() {
        assumeTrue(dir.isDirectory)
        val files = listOf("orders_1", "orders_chat_open", "brightness_min", "brightness_max").map { File(dir, "$it.png") } +
            (File(dir, "frames").listFiles { f -> f.name.endsWith(".png") }?.toList() ?: emptyList()) +
            (File(dir, "frames3").listFiles { f -> f.name.endsWith(".png") && !f.name.startsWith("play2_") }?.toList() ?: emptyList())
        var checked = 0
        for (file in files.filter { it.isFile }.sortedBy { it.name }) {
            assertEquals(file.name, listOf(AGITATOR, AGITATOR, RETORT), stations(Shot(file)))
            checked++
        }
        assertTrue("only $checked pictures found", checked >= 40)
    }

    // Half a line lower there's no icon, only the gap between two: no station, rather than the nearest-looking one
    @Test
    fun noIconIsNoStation() {
        val shot = shot("orders_1.png")
        assumeTrue(shot != null)
        val box = MixologyReader.findOrderBox(shot!!)!!
        val order = MixologyReader.readOrders(shot, box, shapes)[0]!!
        val half = (13 * box.scale).toInt()   // the lines are 26 game pixels apart
        val between = MixologyReader.Order(order.name, order.textRight, order.top + half, order.bottom + half, order.fit)
        assertNull(MixologyStations.stationOf(shot, box, between))
        // And in the game's scene, far from the box
        val nowhere = MixologyReader.Box(box.left + 600, box.top + 300, box.right + 600, box.bottom + 300, box.scale)
        val there = MixologyReader.Order(order.name, order.textRight + 600, order.top + 300, order.bottom + 300, order.fit)
        assertNull(MixologyStations.stationOf(shot, nowhere, there))
    }

    // ---- 2. The progress bar ----

    // Where the bar is in the kept frames, measured with a ruler drawn over each frame enlarged six times: its
    // left end, its top row, its length, and how much of it is yellow
    private class Measured(val time: String, val left: Int, val top: Int, val length: Int, val fill: Double)

    private val bars = listOf(
        Measured("037.40", 930, 396, 106, 0.30),    // the Alembic, zoomed in, just before its sparkle
        Measured("037.70", 952, 386, 105, 0.32),    // the sparkle, green
        Measured("224.50", 1230, 590, 106, 0.28),   // the Alembic from further away
        Measured("119.30", 932, 442, 106, 0.32),    // the Agitator
        Measured("130.50", 1036, 472, 106, 0.30),   // the Agitator from further away
        Measured("051.65", 1225, 461, 107, 0.37)    // the Retort
    )

    @Test
    fun findsTheBarOverAWorkingStation() {
        var checked = 0
        for (m in bars) {
            val shot = shot("frames3/play2_${m.time}s.png") ?: continue
            val bar = MixologyStations.findBar(shot, scale)
            assertNotNull("play2 at ${m.time} s: no bar found", bar)
            assertEquals("${m.time} left", m.left.toDouble(), bar!!.left.toDouble(), 4.0)
            assertEquals("${m.time} top", m.top.toDouble(), bar.top.toDouble(), 4.0)
            assertEquals("${m.time} length", m.length.toDouble(), bar.length.toDouble(), 4.0)
            assertEquals("${m.time} fill", m.fill, bar.fill, 0.04)
            // Looking near where it was a moment ago finds the same bar
            val near = MixologyStations.Bar(bar.left + 40, bar.top - 30, bar.right + 40, bar.bottom - 30, 0.0)
            assertEquals(bar.left, MixologyStations.findBar(shot, scale, near)?.left)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // No station is working in any of these: the four play_2 frames between station uses, and every older picture
    // (nobody used a station in those recordings; north_1 is a busy world with other players about)
    @Test
    fun noBarWhenNoStationIsWorking() {
        assumeTrue(dir.isDirectory)
        val files = listOf("000.0", "059.5", "249.5", "319.5").map { File(dir, "frames3/play2_${it}s.png") } +
            listOf("orders_1", "orders_chat_open", "brightness_min", "brightness_max").map { File(dir, "$it.png") } +
            (File(dir, "frames").listFiles { f -> f.name.endsWith(".png") }?.toList() ?: emptyList()) +
            (File(dir, "frames3").listFiles { f -> f.name.endsWith(".png") && !f.name.startsWith("play2_") }?.toList() ?: emptyList())
        for (file in files.filter { it.isFile }) assertNull(file.name, MixologyStations.findBar(Shot(file), scale))
    }

    // ---- 2. The quick-time moment's green ----

    private fun greenAt(time: String): Int? {
        val shot = shot("frames3/play2_${time}s.png") ?: return null
        return MixologyStations.green(shot, MixologyStations.findBar(shot, scale)!!)
    }

    // The Alembic's sparkle is a small square beside the still: green for 0.3 s (about 20 x 23 pixels zoomed in,
    // 12 x 13 further out), then pink, then turquoise. Only the green counts.
    @Test
    fun theSparkleIsGreenAtFirst() {
        assumeTrue(shot("frames3/play2_037.70s.png") != null)
        assertTrue("before the sparkle", greenAt("037.40")!! < 12)
        assertTrue("the sparkle, green, zoomed in", greenAt("037.70")!! >= 200)
        assertTrue("the sparkle turned pink", greenAt("038.00")!! < 12)
        assertTrue("the sparkle turned turquoise", greenAt("038.30")!! < 12)
        assertTrue("further away, before", greenAt("224.50")!! < 12)
        assertTrue("further away, green", greenAt("224.85")!! >= 80)
    }

    // The Agitator's bubbles: green, pink and turquoise specks rising from the jug for about a second
    @Test
    fun theBubblesHaveGreenInThem() {
        assumeTrue(shot("frames3/play2_120.00s.png") != null)
        assertTrue("before the bubbles", greenAt("119.30")!! < 12)
        assertTrue("the bubbles", greenAt("120.00")!! >= 60)
        assertTrue("further away: only the jug's turquoise potion", greenAt("130.50")!! < 12)
        assertTrue("further away: the bubbles (the faintest in the recording)", greenAt("131.50")!! >= 25)
    }

    // The Retort has no moment, but its potion glows: green Alco-augmentator at its brightest (51.65 s) and pink
    // Megalite liquid (293.9 s) must not count
    @Test
    fun theRetortsGlowingPotionIsNotAMoment() {
        assumeTrue(shot("frames3/play2_051.65s.png") != null)
        val watch = MixologyStations.Watch(scale)
        val bar = MixologyStations.Bar(1225, 461, 1332, 472, 0.37)
        assertFalse(watch.look(0, bar, 0).tapNow)
        assertFalse("green potion in the Retort", watch.look(83, bar, greenAt("051.65")!!).tapNow)
        assertTrue("pink potion in the Retort", greenAt("293.90")!! < 12)
    }

    // ---- 2. Following a station's work (made-up looks with the measured timings) ----

    private val anyBar = MixologyStations.Bar(900, 400, 1005, 411, 0.3)
    private fun bar(fill: Double) = MixologyStations.Bar(900, 400, 1005, 411, fill)

    // Runs looks every 83 ms from `from` to `to` (ms), the green count given by `green` for each time, the bar
    // filling over 9.1 s. Returns the times the moment started, and for how long in all it said "tap now".
    private class Run(val starts: List<Long>, val tapMs: Long, val finished: Station?, val sawWorking: Boolean)

    private fun interface GreenAt { fun at(ms: Long): Int }

    private fun run(watch: MixologyStations.Watch, barUntil: Long, green: GreenAt, lastFill: Double = 0.97): Run {
        val starts = ArrayList<Long>()
        var tap = 0L
        var finished: Station? = null
        var working = false
        var t = 0L
        while (t <= barUntil + 1000) {
            val b = if (t <= barUntil) bar(lastFill * t / barUntil) else null
            val seen = watch.look(t, b, if (b != null) green.at(t) else 0)
            if (seen.started) starts.add(t)
            if (seen.tapNow) tap += 83
            if (seen.finished != null) finished = seen.finished
            if (t <= barUntil) working = working or seen.working
            t += 83
        }
        return Run(starts, tap, finished, working)
    }

    // The Alembic: the sparkle came 2.5 s after the bar in all four uses, green for 0.30 s (about 200 pixels)
    @Test
    fun theAlembicsSparkle() {
        val r = run(MixologyStations.Watch(scale), 9100, { if (it in 2500..2799) 200 else 0 })
        assertTrue(r.sawWorking)
        assertEquals("one moment", 1, r.starts.size)
        assertTrue("caught on the first look after it began", r.starts[0] in 2500..2583)
        // Green for 0.3 s plus a quarter second: the signal is on for about half a second
        assertTrue("signal for ${r.tapMs} ms", r.tapMs in 400..650)
        assertEquals(ALEMBIC, r.finished)
    }

    // The Agitator: bubbles 3.2 s in, green in them for 0.9 s. The signal must not stay that long: the owner's taps
    // counted when the finger came down 0.30, 0.42 and 0.68 s after the white frame showed (play_8 at 51.95 s,
    // play_7 at 32.9 s, play_6 at 54.7 s), and one 1.1 s after it set the bar back (play_6 at 32.9 s, the frame
    // having stayed 1.07 s). So it shows for about 0.6 s, and is gone well before a second.
    @Test
    fun theAgitatorsBubbles() {
        val r = run(MixologyStations.Watch(scale), 9100, { if (it in 3200..4099) 120 else 0 })
        assertEquals(1, r.starts.size)
        assertTrue("signal for ${r.tapMs} ms", r.tapMs in 500..750)
        assertEquals(AGITATOR, r.finished)
    }

    // The Agitator can bubble twice
    @Test
    fun twoMomentsInOneGo() {
        val r = run(MixologyStations.Watch(scale), 9100, { if (it in 2000..2899 || it in 6000..6899) 120 else 0 })
        assertEquals(2, r.starts.size)
        assertEquals(AGITATOR, r.finished)
    }

    // The Retort: the green potion's glow comes and goes every 0.6 s, at most 16 pure green pixels
    @Test
    fun theRetortHasNoMoment() {
        val r = run(MixologyStations.Watch(scale), 9000, { if (it % 600 < 300) 16 else 3 })
        assertTrue(r.sawWorking)
        assertEquals(0, r.starts.size)
        assertEquals(0L, r.tapMs)
        assertEquals(RETORT, r.finished)
    }

    // Something green that was beside the station from the first look isn't a sparkle (it never "appeared")
    @Test
    fun greenThatWasAlwaysThereIsNotAMoment() {
        val r = run(MixologyStations.Watch(scale), 9100, { 300 })
        assertEquals(0, r.starts.size)
        assertEquals(0L, r.tapMs)
    }

    // Walking away (the bar leaves the screen partway): nothing finished
    @Test
    fun aBarLostPartwayFinishedNothing() {
        val r = run(MixologyStations.Watch(scale), 4000, { 0 }, lastFill = 0.44)
        assertNull(r.finished)
    }

    // The bar covered for a fifth of a second (a menu, a passing player) is still the same go at the station
    @Test
    fun aBriefGapIsTheSameBar() {
        val watch = MixologyStations.Watch(scale)
        assertTrue(watch.look(0, anyBar, 0).working)
        assertTrue(watch.look(83, null, 0).working)
        assertTrue(watch.look(166, null, 0).working)
        val back = watch.look(249, bar(0.9), 0)
        assertTrue(back.working)
        assertNull(back.finished)
        // Gone for good: finished, once
        assertNull(watch.look(332, null, 0).finished)
        assertEquals(RETORT, watch.look(700, null, 0).finished)
        val after = watch.look(783, null, 0)
        assertNull(after.finished)
        assertFalse(after.working)
    }

    // The game hands the potion back on the same tick its bar goes (play_10.mp4: the Agitator's bar last seen 98%
    // full at 44.1 s, the corked Azure aura mix in the bag at 44.2 s). With the potion seen back in the bag, the first
    // look without the bar is the end, with no 0.4 s wait; without it, the old wait.
    @Test
    fun aPotionBackInTheBagEndsTheWorkAtOnce() {
        val quick = MixologyStations.Watch(scale)
        assertTrue(quick.look(0, bar(0.98), 0).working)
        val end = quick.look(83, null, 0, backInBag = true)
        assertEquals(RETORT, end.finished)
        assertFalse(end.working)
        val usual = MixologyStations.Watch(scale)
        usual.look(0, bar(0.98), 0)
        assertTrue(usual.look(83, null, 0).working)
        assertEquals(RETORT, usual.look(483, null, 0).finished)
    }

    // ...but only a nearly full bar: a bar lost partway (walking away) finishes nothing
    @Test
    fun theBagOnlyEndsAFullBar() {
        val partway = MixologyStations.Watch(scale)
        partway.look(0, bar(0.44), 0)
        assertNull(partway.look(83, null, 0, backInBag = true).finished)
    }

    // A smaller screen has fewer pixels in the same sparkle: the limits shrink with it
    @Test
    fun aSmallerScreenNeedsFewerPixels() {
        val small = MixologyStations.Watch(scale / 2)
        small.look(0, anyBar, 0)
        assertTrue("a quarter of the pixels", small.look(83, anyBar, 10).tapNow)
        val big = MixologyStations.Watch(scale)
        big.look(0, anyBar, 0)
        assertFalse(big.look(83, anyBar, 10).tapNow)
    }

    // ---- 3. Done, and the next station ----

    private val megalite = "Megalite liquid"
    private val alco = "Alco-augmentator"

    // play_2.mp4's first orders: Megalite liquid at the Alembic, Alco-augmentator at the Retort, Alco-augmentator at
    // the Alembic. The owner's bag held them in that order.
    private val first = listOf(Wanted(megalite, ALEMBIC), Wanted(alco, RETORT), Wanted(alco, ALEMBIC))

    @Test
    fun nothingFinishedYet() {
        val plan = MixologyStations.plan(first, listOf(Carried(megalite, false), Carried(alco, false), Carried(alco, false)))
        assertEquals(listOf(false, false, false), plan.done)
        assertEquals(0, plan.nextOrder)
        assertEquals(ALEMBIC, plan.nextStation)
    }

    @Test
    fun oneFinishedMovesOn() {
        val plan = MixologyStations.plan(first, listOf(Carried(megalite, true), Carried(alco, false), Carried(alco, false)))
        assertEquals(listOf(true, false, false), plan.done)
        assertEquals(1, plan.nextOrder)
        assertEquals(RETORT, plan.nextStation)
    }

    // Two orders for the same potion at different stations, one potion finished: the bag can't say which. With
    // nothing else to go by it's the upper order (the one the tool sent you to)...
    @Test
    fun theSamePotionTwiceCountsFromTheTop() {
        val bag = listOf(Carried(megalite, true), Carried(alco, true), Carried(alco, false))
        val plan = MixologyStations.plan(first, bag)
        assertEquals(listOf(true, true, false), plan.done)
        assertEquals(2, plan.nextOrder)
        assertEquals(ALEMBIC, plan.nextStation)
    }

    // ...but if the stations seen working were the Alembic twice (the Megalite, then an Alco), the Alco that's left
    // is the Retort's
    @Test
    fun theSamePotionTwiceGoesByTheStationsUsed() {
        val bag = listOf(Carried(megalite, true), Carried(alco, true), Carried(alco, false))
        val plan = MixologyStations.plan(first, bag, used = listOf(ALEMBIC, ALEMBIC))
        assertEquals(listOf(true, false, true), plan.done)
        assertEquals(1, plan.nextOrder)
        assertEquals(RETORT, plan.nextStation)
        // The Alembic then the Retort (what the owner did): the usual way round
        val usual = MixologyStations.plan(first, bag, used = listOf(ALEMBIC, RETORT))
        assertEquals(listOf(true, true, false), usual.done)
        assertEquals(ALEMBIC, usual.nextStation)
    }

    @Test
    fun allFinishedMeansDeliver() {
        val plan = MixologyStations.plan(first, listOf(Carried(megalite, true), Carried(alco, true), Carried(alco, true)))
        assertEquals(listOf(true, true, true), plan.done)
        assertNull(plan.nextOrder)
        assertNull(plan.nextStation)
    }

    @Test
    fun anEmptyBagMeansMix() {
        val plan = MixologyStations.plan(first, emptyList())
        assertEquals(listOf(false, false, false), plan.done)
        assertNull(plan.nextStation)
    }

    // Like the plugin, the bag's order decides: the first unfinished potion in the bag that an order wants
    @Test
    fun theBagsFirstPotionGoesFirst() {
        val orders = listOf(Wanted("Marley's moonlight", RETORT), Wanted("Anti-leech lotion", AGITATOR), Wanted("Mystic mana amalgam", ALEMBIC))
        val plan = MixologyStations.plan(orders, listOf(Carried("Mystic mana amalgam", false), Carried("Marley's moonlight", false)))
        assertEquals(2, plan.nextOrder)
        assertEquals(ALEMBIC, plan.nextStation)
    }

    // An order that couldn't be read is left alone, and so are potions nobody ordered (finished or not)
    @Test
    fun unreadOrdersAndSparePotionsAreLeftAlone() {
        val orders = listOf(null, Wanted("Liplack liquor", RETORT), Wanted(megalite, RETORT))
        val bag = listOf(Carried("Mammoth-might mix", false), Carried("Mixalot", true), Carried(megalite, false))
        val plan = MixologyStations.plan(orders, bag)
        assertEquals(listOf(false, false, false), plan.done)
        assertEquals(2, plan.nextOrder)
        assertEquals(RETORT, plan.nextStation)
    }

    // The plugin's rule (tryFulfillOrder): an order is only done when its own station made its potion. A Megalite
    // seen finished at the Retort doesn't do the Alembic's Megalite order.
    @Test
    fun aPotionMadeAtTheWrongStationIsNotDone() {
        val bag = listOf(Carried(megalite, true), Carried(alco, false), Carried(alco, false))
        val plan = MixologyStations.plan(first, bag, made = listOf(MixologyStations.Made(megalite, RETORT)))
        assertEquals(listOf(false, false, false), plan.done)
        // Made at the Alembic, it is
        val right = MixologyStations.plan(first, bag, made = listOf(MixologyStations.Made(megalite, ALEMBIC)))
        assertEquals(listOf(true, false, false), right.done)
    }

    // With two orders for the same potion, the one whose station made it is done (the plugin: the first order with
    // that potion at that station), not the upper one
    @Test
    fun theStationThatMadeItPicksTheOrder() {
        val bag = listOf(Carried(megalite, true), Carried(alco, true), Carried(alco, false))
        val made = listOf(MixologyStations.Made(megalite, ALEMBIC), MixologyStations.Made(alco, ALEMBIC))
        val plan = MixologyStations.plan(first, bag, made = made)
        assertEquals(listOf(true, false, true), plan.done)
        assertEquals(RETORT, plan.nextStation)
    }

    // A potion seen made but no longer in the bag (dropped) counts for nothing
    @Test
    fun aMadePotionThatLeftTheBagIsNotDone() {
        val bag = listOf(Carried(alco, false), Carried(alco, false))
        val plan = MixologyStations.plan(first, bag, made = listOf(MixologyStations.Made(megalite, ALEMBIC)))
        assertEquals(listOf(false, false, false), plan.done)
    }

    // Right after a station finished the plugin goes down the orders (tryHighlightNextStation): the first order not
    // done whose unfinished potion is in the bag, whatever the bag's order. Otherwise the bag's order decides.
    @Test
    fun afterAStationFinishesTheOrdersGoFirst() {
        val orders = listOf(Wanted("Marley's moonlight", RETORT), Wanted("Anti-leech lotion", AGITATOR), Wanted("Mystic mana amalgam", ALEMBIC))
        val bag = listOf(Carried("Mystic mana amalgam", false), Carried("Anti-leech lotion", false))
        assertEquals(ALEMBIC, MixologyStations.plan(orders, bag).nextStation)
        val after = MixologyStations.plan(orders, bag, byOrder = true)
        assertEquals(1, after.nextOrder)
        assertEquals(AGITATOR, after.nextStation)
        // Nothing unfinished that an order wants: nowhere to go
        assertNull(MixologyStations.plan(orders, listOf(Carried("Mixalot", false)), byOrder = true).nextStation)
    }

    // Sent remembers the potion handed over, not only the station
    @Test
    fun sentRemembersThePotion() {
        val sent = MixologyStations.Sent()
        sent.planned(0, ALEMBIC, 2, megalite)
        sent.planned(100, RETORT, 1, alco)       // the Megalite left the bag
        assertEquals(ALEMBIC, sent.justSent(200))
        assertEquals(megalite, sent.justSentPotion(200))
        assertNull(sent.justSentPotion(100 + MixologyStations.Sent.HOLD_MS + 1))
    }

    // Two finished potions of a kind ordered once: that one order is done, nothing else
    @Test
    fun aSpareFinishedPotionDoesNotFinishAnotherOrder() {
        val orders = listOf(Wanted(megalite, AGITATOR), Wanted("Aqualux amalgam", AGITATOR), Wanted("Mystic mana amalgam", AGITATOR))
        val bag = listOf(Carried(megalite, true), Carried(megalite, true), Carried("Aqualux amalgam", false))
        val plan = MixologyStations.plan(orders, bag)
        assertEquals(listOf(true, false, false), plan.done)
        assertEquals(1, plan.nextOrder)
        assertEquals(AGITATOR, plan.nextStation)
    }

    // The tool's quick-time watch at its 12 looks a second (one picture = one look, 83 ms apart), with the green
    // counted by Glow. Returns the pictures where a moment started.
    private fun moments(folder: String): List<Int> {
        val files = File(dir, "frames3/$folder").listFiles { f -> f.name.endsWith(".png") }!!.sortedBy { it.name }
        val watch = MixologyStations.Watch(scale)
        val glow = MixologyStations.Glow()
        var last: MixologyStations.Bar? = null
        val started = ArrayList<Int>()
        for ((i, f) in files.withIndex()) {
            val p = Shot(f)
            val now = i * 83L
            val bar = MixologyStations.findBar(p, scale, last)
            last = bar
            if (watch.look(now, bar, glow.look(p, bar, now)).started) started.add(i)
        }
        return started
    }

    // A green potion (Alco-augmentator) swirling in the Agitator is pure green every 0.58 s: no moment for that, only
    // for the real bubbles, first seen by eye in picture 57 (play_4.mp4 at 25.5 s). The green count alone found a
    // moment in every turn of the swirl (13 in play_4). The real sparkle and bubbles of play_2 are still found where
    // they first show by eye (picture 17 in both). Each may be found up to two looks (0.17 s) after it first shows: the
    // first specks can be too few to count.
    @Test
    fun aSwirlingGreenPotionIsntTheBubbles() {
        assumeTrue(File(dir, "frames3/play4_swirl_bubbles").isDirectory)
        val swirl = moments("play4_swirl_bubbles")
        assertEquals(swirl.toString(), 1, swirl.size)
        assertTrue(swirl.toString(), swirl[0] in 57..59)
        for (folder in listOf("play2_alembic_276s", "play2_agitator_203s")) {
            val found = moments(folder)
            assertEquals("$folder: $found", 1, found.size)
            assertTrue("$folder: $found", found[0] in 17..19)
        }
    }

    // The Alembic's countdown, from how full its bar is, against the recordings' own clocks: the sparkle's first green
    // was timed at 60 frames a second, and these pictures are the bar then and 1 and 2 seconds before (9 first
    // sparkles in play_2, play_3 and play_5; frames3/alembic_bars, described in notes.md). It must be right to about
    // a game tick either way (0.35 s), and say nothing more once the sparkle is due.
    @Test
    fun theAlembicCountdownMatchesTheRecordings() {
        val folder = File(dir, "frames3/alembic_bars")
        assumeTrue(folder.isDirectory)
        var checked = 0
        for (f in folder.listFiles { x -> x.name.endsWith(".png") }!!.sortedBy { it.name }) {
            val bar = MixologyStations.findBar(Shot(f), scale)
            assertNotNull(f.name, bar)
            val left = MixologyStations.alembicCountdown(bar!!.fill)
            when {
                f.name.endsWith("_at.png") -> assertTrue("${f.name}: $left", left == null || left < 0.35)
                f.name.endsWith("_1s_before.png") -> assertEquals(f.name, 1.0, left!!, 0.35)
                f.name.endsWith("_2s_before.png") -> assertEquals(f.name, 2.0, left!!, 0.35)
            }
            checked++
        }
        assertEquals(27, checked)
    }

    // ---- When to tap the Alembic (the bar as timed at 60 frames a second in the owner's recordings) ----

    // One Alembic use: the bar's fill each time it moved (recording's clock in ms to fill), when the first sparkle
    // turned green, when the bar went back after the owner's mistimed tap, and when the next sparkle turned green
    private class AlembicUse(val name: String, val bar: List<Pair<Int, Double>>, val sparkle: Int, val wentBack: Int,
                             val secondSparkle: Int)

    private val alembicUses = listOf(
        AlembicUse("play_8 at 13 s", listOf(
            13367 to 0.029, 13550 to 0.057, 13750 to 0.076, 13933 to 0.095, 14133 to 0.114, 14333 to 0.133,
            14517 to 0.152, 14717 to 0.171, 14900 to 0.191, 15100 to 0.209, 15300 to 0.229, 15467 to 0.257,
            15667 to 0.276, 15850 to 0.295, 16050 to 0.314, 16250 to 0.333, 16433 to 0.352, 16633 to 0.371,
            16817 to 0.391, 17017 to 0.419, 17167 to 0.238, 17367 to 0.257, 17550 to 0.276, 17750 to 0.295,
            17933 to 0.314, 18133 to 0.333, 18317 to 0.352, 18517 to 0.371, 18683 to 0.391, 18883 to 0.419,
            19067 to 0.438, 19267 to 0.457, 19450 to 0.476, 19650 to 0.495, 19833 to 0.514, 20033 to 0.533,
            20217 to 0.552, 20417 to 0.571, 20583 to 0.591, 20767 to 0.619, 20967 to 0.638, 21150 to 0.657,
            21350 to 0.676, 21533 to 0.695), sparkle = 15917, wentBack = 17167, secondSparkle = 21317),
        AlembicUse("play_8 at 83 s", listOf(
            82917 to 0.029, 83100 to 0.048, 83300 to 0.067, 83483 to 0.086, 83683 to 0.114, 83883 to 0.133,
            84067 to 0.152, 84267 to 0.171, 84450 to 0.191, 84650 to 0.209, 84850 to 0.229, 85017 to 0.248,
            85217 to 0.267, 85400 to 0.286, 85600 to 0.314, 85800 to 0.333, 85983 to 0.352, 86150 to 0.171,
            86350 to 0.191, 86550 to 0.209, 86717 to 0.229, 86917 to 0.248, 87100 to 0.267, 87300 to 0.286,
            87483 to 0.314, 87683 to 0.333, 87867 to 0.352, 88067 to 0.371, 88250 to 0.391, 88450 to 0.409,
            88617 to 0.429, 88817 to 0.448, 89000 to 0.476, 89200 to 0.495, 89383 to 0.514, 89583 to 0.533,
            89767 to 0.552, 89967 to 0.571, 90150 to 0.591, 90350 to 0.610, 90517 to 0.629),
            sparkle = 85550, wentBack = 86150, secondSparkle = 90317),
        AlembicUse("play_7 at 146 s", listOf(
            145900 to 0.019, 146050 to 0.038, 146217 to 0.057, 146417 to 0.076, 146600 to 0.095, 146800 to 0.114,
            147000 to 0.133, 147183 to 0.152, 147383 to 0.171, 147567 to 0.200, 147767 to 0.219, 147967 to 0.238,
            148150 to 0.257, 148350 to 0.276, 148517 to 0.295, 148717 to 0.314, 148917 to 0.333, 149100 to 0.362,
            149300 to 0.381, 149483 to 0.400, 149683 to 0.419, 149850 to 0.238, 150050 to 0.257, 150217 to 0.276,
            150417 to 0.295, 150600 to 0.314, 150800 to 0.333, 150983 to 0.362, 151183 to 0.381, 151367 to 0.400,
            151567 to 0.419, 151750 to 0.438, 151950 to 0.457, 152117 to 0.476, 152317 to 0.495, 152500 to 0.514,
            152700 to 0.533, 152883 to 0.562, 153083 to 0.581, 153267 to 0.600, 153450 to 0.619, 153650 to 0.638,
            153817 to 0.657, 154017 to 0.676, 154200 to 0.695, 154400 to 0.724),
            sparkle = 148650, wentBack = 149850, secondSparkle = 154150)
    )

    // What the clock did over a replayed use: when it gave its cues (buzz), and every time it said "tap now"
    private class Cues(val buzzes: List<Long>, val tapTimes: List<Long>)

    // Replays a bar to the clock the way the tool does: a look every 83 ms (the first `offset` ms after the bar
    // shows), and the tool's timer in between when the cue is due before the next look. `jumpAt`: from then on the
    // bar reads 0.36 further on (a hit).
    private fun replay(bar: List<Pair<Int, Double>>, offset: Int, until: Int, jumpAt: Int = Int.MAX_VALUE): Cues {
        val clock = MixologyStations.AlembicClock()
        val buzzes = ArrayList<Long>()
        val taps = ArrayList<Long>()
        var t = (bar[0].first + offset).toLong()
        while (t <= until) {
            var fill = bar.last { it.first <= t }.second
            if (t >= jumpAt) fill += 0.36
            clock.look(t, fill)
            if (clock.buzzNow) buzzes.add(t)
            if (clock.tapNow) taps.add(t)
            val wait = clock.cueInMs
            if (wait in 1..82) {
                clock.at(t + wait)
                if (clock.buzzNow) buzzes.add(t + wait)
            }
            t += 83
        }
        return Cues(buzzes, taps)
    }

    // The Alembic's cue has to come BEFORE the sparkle. Worked out from the recordings, not from the code:
    //  - a tap counts on the first game tick that shows at least 0.25 s (0.22 to 0.30) after the finger came down
    //    (16 taps in play_5, play_7 and play_8), and every tap dealt with on the tick after the sparkle's, or later,
    //    set the bar back (21 of 21). So the finger must come down 0.80 to 0.30 s before the sparkle shows;
    //  - the owner's finger came down 0.28 to 0.42 s after the white frame showed (8 taps in play_5), and the phone
    //    shows the tool's drawing up to 0.07 s after its look.
    // So the cue must come 0.79 s (0.30 + 0.42 + 0.07) to 1.08 s (0.80 + 0.28) before the sparkle turned green, from
    // any of the looks' possible timings. (Before 16 November 2026 it came within 0.1 s of the sparkle: too late.)
    @Test
    fun theAlembicsCueComesBeforeTheSparkle() {
        for (use in alembicUses) for (offset in 0 until 83 step 9) {
            val cues = replay(use.bar, offset, use.wentBack - 100)
            assertEquals("${use.name} +$offset: ${cues.buzzes}", 1, cues.buzzes.size)
            val before = use.sparkle - cues.buzzes[0]
            assertTrue("${use.name} +$offset: cue $before ms before the sparkle", before in 790..1080)
            // The white frame shows from the cue, and is gone before a tap would be too late (0.25 s before the
            // sparkle); nothing shows on the sparkle itself
            assertTrue(cues.tapTimes.isNotEmpty())
            assertTrue("frame from the cue", cues.tapTimes.first() - cues.buzzes[0] <= 83)
            assertTrue("${use.name} +$offset: frame until ${use.sparkle - cues.tapTimes.last()} ms before the sparkle",
                cues.tapTimes.last() <= use.sparkle - 250)
        }
    }

    // After a mistimed tap the bar goes back and the next sparkle comes 7 game ticks later (4.10 to 4.33 s after
    // the bar went back, in all 12 such sparkles of play_5, play_7 and play_8). The game's own timing varies by a
    // quarter second there, so the cue is allowed 0.75 to 1.15 s before that sparkle; one cue only, and with a buzz.
    @Test
    fun theAlembicsSecondChanceIsTimedFromTheBarGoingBack() {
        for (use in alembicUses) for (offset in 0 until 83 step 9) {
            val cues = replay(use.bar, offset, use.secondSparkle + 300)
            assertEquals("${use.name} +$offset: ${cues.buzzes}", 2, cues.buzzes.size)
            val before = use.secondSparkle - cues.buzzes[1]
            assertTrue("${use.name} +$offset: second cue $before ms before its sparkle", before in 750..1150)
            assertTrue(cues.tapTimes.last() <= use.secondSparkle - 150)
        }
    }

    // A hit (made up: no Alembic hit has been recorded; the Agitator's bar jumps on by 0.36 when hit): the frame goes
    // at once and there's no cue for a second chance
    @Test
    fun aHitEndsTheAlembicsCues() {
        val use = alembicUses[0]
        // (the bar's own going back at 17.167 s is left out: only its first 16 moves, then the jump)
        val cues = replay(use.bar.take(16), 0, use.sparkle + 6000, jumpAt = use.sparkle - 100)
        assertEquals(cues.buzzes.toString(), 1, cues.buzzes.size)
        assertTrue(cues.tapTimes.all { it < use.sparkle - 100 })
    }

    // The tool started (or the bar first seen) with the cue's time long gone: no cue, rather than a late one
    @Test
    fun noLateAlembicCue() {
        val use = alembicUses[0]
        val late = use.bar.filter { it.first >= 15467 }     // the bar first seen 0.45 s before the sparkle
        val cues = replay(late, 0, use.wentBack - 100)
        assertTrue(cues.buzzes.toString(), cues.buzzes.isEmpty())
        assertTrue(cues.tapTimes.isEmpty())
    }
}

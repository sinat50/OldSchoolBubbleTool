package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.MixologyStations.Station
import com.sinat.osrsbubbletool.MixologyStations.Station.AGITATOR
import com.sinat.osrsbubbletool.MixologyStations.Station.ALEMBIC
import com.sinat.osrsbubbletool.MixologyStations.Station.RETORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

// The Mastering Mixology tool's flashing arrow over the station to use next (MixologyStations, part 4): where the
// stations are on the screen, the rules for showing the arrow, and that the tool's own readers aren't fooled by it.
//
// The pictures are the owner's (Documents\Androiddev\mixology_shots\frames3\station_arrow, from play_2, play_5,
// play_8 and play_9; or set MIXOLOGY_SHOTS=<folder>; described in its notes.md), not part of the project: those
// tests are skipped without them. Every expected place was measured by hand on the pictures: the bars with a ruler
// drawn over each frame enlarged eight times, the stations with no bar on them by eye on a three times enlargement
// (the spot on each station where its bar sits in the frames that have one). Which station is which comes from the
// game's own menu in the recordings ("Crystalise-potion Alembic").
class MixologyArrowTest {

    private class Shot(val data: IntArray, override val width: Int, override val height: Int) : LightBoxReader.PixelSource {
        override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
    }

    private val dir = File(System.getenv("MIXOLOGY_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\mixology_shots")
    private val shapes by lazy { MixologyReader.readShapes(File("src/main/assets/mixology/names.tsv").readText()) }
    private val potions by lazy { MixologyBag.readSummaries(File("src/main/assets/mixology/potions.tsv").readText()) }

    private fun load(file: File): Shot? {
        if (!file.isFile) return null
        val image = ImageIO.read(file)
        val data = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, data, 0, image.width)
        return Shot(data, image.width, image.height)
    }

    private fun frame(name: String) = load(File(dir, "frames3/station_arrow/$name.png"))

    // What the tool's readers find in a picture
    private class Seen(val shot: Shot, val box: MixologyReader.Box, val row: MixologyLevers.Levers?,
                       val compass: MixologyLevers.Compass?, val bar: MixologyStations.Bar?)

    // (`last` = the funnels of the look before, as the tool gives them: with the middle funnel behind something,
    // the outer two are enough if they're as far apart as they were)
    private fun see(shot: Shot, last: MixologyLevers.Levers? = null): Seen {
        val box = MixologyReader.findOrderBox(shot)!!
        return Seen(shot, box, MixologyLevers.find(shot, last = last), MixologyLevers.findCompass(shot, box.scale),
            MixologyStations.findBar(shot, box.scale))
    }

    // The frames with a station working: which station, and the middle of its progress bar by the ruler
    private class Working(val name: String, val station: Station, val x: Double, val y: Double)

    private val working = listOf(
        Working("play5_038.0s", ALEMBIC, 1035.8, 429.2),    // facing north, standing at the Alembic
        Working("play5_055.0s", RETORT, 1303.9, 490.2),     // the same camera, at the Retort
        Working("play5_200.0s", ALEMBIC, 1312.7, 604.2),    // facing south, looking steeply down
        Working("play8_018.0s", ALEMBIC, 1033.7, 408.1),    // facing north
        Working("play8_050.0s", AGITATOR, 1089.0, 427.1),   // the same camera, at the Agitator (the table at the bottom)
        Working("play9_017.0s", ALEMBIC, 1038.2, 404.1),
        Working("play2_200.3s", RETORT, 1042.5, 514.0),     // facing south, the camera lower
        Working("play2_203.4s", AGITATOR, 1362.8, 682.2)    // the same camera three seconds later, at the Agitator
    )

    private fun seenWorking(name: String): Pair<Working, Seen>? {
        val w = working.first { it.name == name }
        // (in play2_203.4s the character's head hides the middle funnel: the tool had the row from 3 seconds before)
        val last = if (name == "play2_203.4s") frame("play2_200.3s")?.let { see(it).row } else null
        return frame(name)?.let { w to see(it, last) }
    }

    // The tilt a frame's bar gives (the frame must have its bar, funnels and compass)
    private fun tiltOf(name: String): Double? {
        val (_, s) = seenWorking(name) ?: return null
        return MixologyStations.learnTilt(s.row!!, s.compass!!.angle, s.bar!!, s.shot.width, s.shot.height)?.tilt
    }

    private fun off(spot: MixologyStations.Spot, x: Double, y: Double, row: MixologyLevers.Levers) =
        hypot(spot.x - x, spot.y - y) / row.spacing

    // ---- Where the stations are ----

    // The ruler and the bar finder agree (so the places below are the bars' real places)
    @Test
    fun theBarsAreWhereTheRulerSays() {
        var checked = 0
        for (w in working) {
            val s = frame(w.name)?.let { see(it) } ?: continue
            assertNotNull(w.name, s.bar)
            assertEquals(w.name, w.x, (s.bar!!.left + s.bar.right) / 2.0, 3.0)
            assertEquals(w.name, w.y, (s.bar.top + s.bar.bottom) / 2.0, 3.0)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // A working station's bar says which station it is, from where it is beside the funnels alone. One of the eight
    // says nothing: in play8_050.0s the table is at the very bottom of the screen, and the bar could be the Agitator's
    // or, with the camera tilted right down, the Retort's. Never a wrong name.
    @Test
    fun aBarNamesItsStation() {
        var checked = 0
        for (w in working) {
            val s = seenWorking(w.name)?.second ?: continue
            assertNotNull("${w.name}: funnels", s.row)
            assertNotNull("${w.name}: compass", s.compass)
            val learned = MixologyStations.learnTilt(s.row!!, s.compass!!.angle, s.bar!!, s.shot.width, s.shot.height)
            if (w.name != "play8_050.0s") assertNotNull("${w.name}: nothing learned", learned)
            if (learned != null) assertEquals(w.name, w.station, learned.station)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // The tilt learned at one station puts another station's bar where the ruler found it, with the character (and
    // so the camera) somewhere else. In funnel spacings (82 to 129 pixels in these frames).
    @Test
    fun aStationIsPlacedFromTheTiltLearnedAtAnother() {
        // learned in, placed in, and how far off it may be
        val pairs = listOf(
            Triple("play5_038.0s", "play5_055.0s", 0.2),    // Alembic -> Retort
            Triple("play5_055.0s", "play5_038.0s", 0.2),    // Retort -> Alembic
            Triple("play8_018.0s", "play8_050.0s", 0.2),    // Alembic -> Agitator, right across the room
            // Facing south, the Agitator from the Retort
            Triple("play2_200.3s", "play2_203.4s", 0.2),
            Triple("play2_203.4s", "play2_200.3s", 0.2)
        )
        var checked = 0
        for ((from, to, most) in pairs) {
            val tilt = tiltOf(from) ?: continue
            val (w, s) = seenWorking(to) ?: continue
            val spot = MixologyStations.stationSpot(s.row!!, s.compass!!.angle, tilt, w.station, s.shot.width, s.shot.height)
            val by = off(spot, w.x, w.y, s.row)
            assertTrue("$from -> $to: ${w.station} off by $by spacings", by <= most)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // With no station working: the three stations from the lever table's side of the room. The places are where
    // each station's bar sits when it works, picked by eye (the join of the Alembic's two bulbs, just under the
    // Retort's bowl, between the Agitator's beakers), so half a spacing is allowed.
    @Test
    fun theStationsArePlacedWithNoneWorking() {
        class Still(val name: String, val tiltFrom: String, val places: Map<Station, Pair<Double, Double>>)
        val stills = listOf(
            // facing north, standing at the mixing vessel
            Still("play9_012.0s", "play9_017.0s", mapOf(ALEMBIC to (826.0 to 387.0), AGITATOR to (1208.0 to 196.0),
                RETORT to (1563.0 to 415.0)))
        )
        var checked = 0
        for (still in stills) {
            val tilt = tiltOf(still.tiltFrom) ?: continue
            val s = frame(still.name)?.let { see(it) } ?: continue
            assertNull(still.name, s.bar)
            for ((station, place) in still.places) {
                val spot = MixologyStations.stationSpot(s.row!!, s.compass!!.angle, tilt, station, s.shot.width, s.shot.height)
                val by = off(spot, place.first, place.second, s.row)
                assertTrue("${still.name}: $station off by $by spacings (${spot.x}, ${spot.y})", by <= 0.5)
            }
            checked++
        }
        assumeTrue(checked > 0)
    }

    // ---- When the tilt is trusted ----

    // play_5.mp4, facing south: at 190 s the owner stands at the levers, zoomed out, looking almost straight down
    // (the game's square under the character is 0.92 times as tall as wide); by 200 s, at the Alembic, they've
    // zoomed in and the camera is lower (the square 0.74 times as tall as wide), with the compass exactly where it
    // was. The Alembic's bar sits on the still's big bulb (10 pixels right of its middle and 17 below, in the 200 s
    // frame); at 190 s that bulb's middle is at (1477, 692) by the ruler. The tilt of 200 s would put the Alembic
    // about a spacing from there, by the bellows: so a zoom since the tilt was learned means no place at all.
    @Test
    fun zoomingForgetsTheTilt() {
        val (_, then) = seenWorking("play5_200.0s") ?: run { assumeTrue(false); return }
        val now = frame("play5_190.0s")?.let { see(it) } ?: run { assumeTrue(false); return }
        val w = now.shot.width
        val h = now.shot.height
        val tilt = MixologyStations.Tilt()
        tilt.bar(then.row!!, then.compass!!, then.bar!!, w, h)
        assertEquals(ALEMBIC, tilt.bar(then.row, then.compass, then.bar, w, h))
        assertNotNull(tilt.spot(then.row, then.compass, RETORT, w, h))
        // The same compass, but zoomed: nothing for any station, and the tilt is gone
        assertEquals(then.compass.angle, now.compass!!.angle, 0.2)
        assertNull(tilt.spot(now.row!!, now.compass, ALEMBIC, w, h))
        assertNull(tilt.degrees)
        // (what it would have said: well off the bulb)
        val wrong = MixologyStations.stationSpot(now.row, now.compass.angle, tiltOf("play5_200.0s")!!, ALEMBIC, w, h)
        assertTrue(off(wrong, 1486.0, 707.0, now.row) > 0.6)
    }

    // Walking about doesn't count as zooming: play_9 at 12 s (at the mixing vessel) and at 17 s (at the Alembic),
    // the camera untouched in between, and play_5 at 38 s (Alembic) and 55 s (Retort)
    @Test
    fun walkingKeepsTheTilt() {
        for ((learnedIn, usedIn) in listOf("play9_017.0s" to "play9_012.0s", "play5_038.0s" to "play5_055.0s")) {
            val (_, then) = seenWorking(learnedIn) ?: continue
            val now = frame(usedIn)?.let { see(it) } ?: continue
            val tilt = MixologyStations.Tilt()
            tilt.bar(then.row!!, then.compass!!, then.bar!!, now.shot.width, now.shot.height)
            assertNotNull(learnedIn, tilt.bar(then.row, then.compass, then.bar, now.shot.width, now.shot.height))
            assertNotNull("$learnedIn -> $usedIn", tilt.spot(now.row!!, now.compass, RETORT, now.shot.width, now.shot.height))
        }
    }

    @Test
    fun theTiltNeedsTwoLooksAndGoesWhenTheCameraTurns() {
        val (_, s) = seenWorking("play9_017.0s") ?: run { assumeTrue(false); return }
        val w = s.shot.width
        val h = s.shot.height
        val row = s.row!!
        val compass = s.compass!!
        val tilt = MixologyStations.Tilt()
        // Nothing known: no place for any station
        assertNull(tilt.spot(row, compass, RETORT, w, h))
        // One look at a bar isn't enough; the second, saying the same, is
        assertNull(tilt.bar(row, compass, s.bar!!, w, h))
        assertNull(tilt.spot(row, compass, RETORT, w, h))
        assertEquals(ALEMBIC, tilt.bar(row, compass, s.bar, w, h))
        assertNotNull(tilt.spot(row, compass, RETORT, w, h))
        // The compass not found, or read only roughly: no place, but nothing is forgotten
        assertNull(tilt.spot(row, null, RETORT, w, h))
        assertNull(tilt.spot(row, MixologyLevers.Compass(compass.angle, compass.x, compass.y, false), RETORT, w, h))
        tilt.compass(null)
        assertNotNull(tilt.spot(row, compass, RETORT, w, h))
        // Turned half a degree (the compass reads the same to a tenth while the camera is left alone): no place
        val turned = MixologyLevers.Compass(compass.angle + 0.5, compass.x, compass.y, true)
        assertNull(tilt.spot(row, turned, RETORT, w, h))
        // ...and once the tool has seen the turn, the tilt is gone for good, even if the camera comes back
        tilt.compass(turned.angle)
        assertNull(tilt.degrees)
        assertNull(tilt.spot(row, compass, RETORT, w, h))
    }

    // Two looks that don't agree teach nothing: a bar where no station is, or a different station each time
    @Test
    fun aBarInTheWrongPlaceTeachesNothing() {
        val (_, s) = seenWorking("play9_017.0s") ?: run { assumeTrue(false); return }
        val w = s.shot.width
        val h = s.shot.height
        val bar = s.bar!!
        // The same bar moved four bar lengths to the right: out in the middle of the floor, between the stations
        val nowhere = MixologyStations.Bar(bar.left + 420, bar.top, bar.right + 420, bar.bottom, bar.fill)
        assertNull(MixologyStations.learnTilt(s.row!!, s.compass!!.angle, nowhere, w, h))
        val tilt = MixologyStations.Tilt()
        assertNull(tilt.bar(s.row, s.compass, nowhere, w, h))
        assertNull(tilt.bar(s.row, s.compass, nowhere, w, h))
        assertNull(tilt.degrees)
        // Facing east there are no lever dots and no arrow: nothing is learned either
        val east = MixologyLevers.Compass(180.0, s.compass.x, s.compass.y, true)
        assertNull(tilt.bar(s.row, east, bar, w, h))
        assertNull(tilt.bar(s.row, east, bar, w, h))
        assertNull(tilt.degrees)
    }

    // ---- The station a potion was just handed to ----

    // play_5.mp4 at 32.4 s: the Megalite liquid left the bag (for the Alembic) and the plan moved on to the Mammoth-
    // might mix's Retort; the Alembic's bar showed 0.4 s later. The bar is the Alembic's.
    @Test
    fun thePotionLeavesTheBagBeforeTheBarShows() {
        val sent = MixologyStations.Sent()
        sent.planned(1_000, ALEMBIC, 3)
        assertNull(sent.justSent(1_000))
        sent.planned(1_333, RETORT, 2)      // one unfinished potion fewer: it went to the Alembic
        assertEquals(ALEMBIC, sent.justSent(1_333))
        assertEquals(ALEMBIC, sent.justSent(1_733))
        assertNull(sent.justSent(1_333 + MixologyStations.Sent.HOLD_MS + 1))
    }

    @Test
    fun aPlanThatOnlyChangesSentNothing() {
        val sent = MixologyStations.Sent()
        // A new potion taken from the vessel (one more), a finished one handed in (as many unfinished as before)
        sent.planned(1_000, ALEMBIC, 1)
        sent.planned(1_333, ALEMBIC, 2)
        sent.planned(1_666, RETORT, 2)
        assertNull(sent.justSent(1_666))
        // The inventory closed and opened again with fewer potions: not seen leaving, so nothing is said
        sent.unseen()
        sent.planned(5_000, RETORT, 1)
        assertNull(sent.justSent(5_000))
        // Nothing was being sent anywhere (no next station): nothing to remember
        sent.planned(6_000, null, 1)
        sent.planned(6_333, null, 0)
        assertNull(sent.justSent(6_333))
    }

    // ---- The arrow and the tool's own readers ----

    // What the screen shows where the tool draws: Android makes its drawing 80% solid over the game
    private fun blend(over: Int, under: Int, solid: Double = 0.8): Int {
        fun mix(shift: Int) = ((over shr shift and 0xFF) * solid + (under shr shift and 0xFF) * (1 - solid) + 0.5).toInt()
        return (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    // Over every colour the game could have behind it (each of red, green and blue in steps of 5), at 80% and
    // fully solid, the arrow's yellow is never a colour the bar finder follows. The progress bar's own yellow is,
    // of course: that's what a plain yellow arrow would have been.
    @Test
    fun theArrowsYellowIsNeverABarsColour() {
        assertTrue(MixologyStations.barColoured(0xDAD100))
        assertTrue(MixologyStations.barColoured(blend(0xDAD100, 0x957A5E)))   // (the bar's yellow over the lab's floor)
        for (r in 0..255 step 5) for (g in 0..255 step 5) for (b in 0..255 step 5) {
            val under = (r shl 16) or (g shl 8) or b
            for (solid in doubleArrayOf(0.8, 1.0)) {
                val seen = blend(MixologyStations.ARROW_COLOUR, under, solid)
                assertFalse("over %06X at $solid: %06X".format(under, seen), MixologyStations.barColoured(seen))
            }
        }
    }

    // No row of the arrow is as long as the shortest thing the bar finder takes for a bar (43.6 game pixels), and
    // its tip stays above where the station's bar will be (5 game pixels tall, its middle at the station's spot)
    @Test
    fun theArrowIsSmallerThanABarAndStaysAboveIt() {
        for (scale in doubleArrayOf(1.4, 2.115, 3.2)) {
            for (spacing in doubleArrayOf(36.0, 97.0, 160.0)) {
                val spot = MixologyStations.Spot(900.0, 500.0)
                val arrow = MixologyStations.arrowOver(spot, spacing, scale)
                val p = arrow.outline()
                var left = Float.MAX_VALUE
                var right = -Float.MAX_VALUE
                var bottom = -Float.MAX_VALUE
                for (i in p.indices step 2) {
                    left = minOf(left, p[i]); right = maxOf(right, p[i]); bottom = maxOf(bottom, p[i + 1])
                }
                val edge = arrow.width * MixologyStations.ARROW_EDGE
                assertTrue((right - left + edge) / scale < 43.6 - 15)
                assertTrue(arrow.right - arrow.left <= MixologyStations.ARROW_WIDTH * scale + 0.01)
                assertTrue("tip ${bottom + edge / 2} at scale $scale", bottom + edge / 2 < spot.y - 2.5 * scale - 2)
            }
        }
    }

    // A station at the top of the screen has no room above it for the arrow: then the arrow goes below the station
    // and points up at it. It's the same size, its tip stays below where the station's bar will be (5 game pixels
    // tall, its middle at the station's spot), and it keeps to the screen and off the interface like the other.
    @Test
    fun anArrowWithNoRoomAboveGoesBelowAndPointsUp() {
        val scale = 2.115
        val spacing = 100.0
        val width = 2340
        val height = 1080
        // The Agitator as it sits in the owner's games facing north, standing at the lever table: 60 pixels down
        val spot = MixologyStations.Spot(1160.0, 60.0)
        val over = MixologyStations.arrowOver(spot, spacing, scale)
        assertFalse(over.up)
        assertTrue(over.top < 0)
        assertFalse(MixologyStations.arrowClear(over, spot, emptyList(), width, height))
        val under = MixologyStations.arrowUnder(spot, spacing, scale)
        assertTrue(under.up)
        assertTrue(MixologyStations.arrowClear(under, spot, emptyList(), width, height))
        assertEquals(over.width, under.width, 0.001f)
        assertEquals(over.height, under.height, 0.001f)
        assertEquals(under.tipY, under.top, 0.001f)
        assertEquals(under.tipY + under.height, under.bottom, 0.001f)
        // every corner inside its box, the tip uppermost, and all of it below the bar's place
        val p = under.outline()
        val edge = under.width * MixologyStations.ARROW_EDGE
        for (i in p.indices step 2) {
            assertTrue(p[i] >= under.left && p[i] <= under.right)
            assertTrue(p[i + 1] >= under.top && p[i + 1] <= under.bottom)
            assertTrue(p[i + 1] >= p[1])
        }
        assertTrue(p[1] - edge / 2 > spot.y + 2.5 * scale + 2)
        // It keeps off the interface too: an area under the station stops it, and one off to the side doesn't
        val below = MixologyStations.Area(1100f, 100f, 1220f, 160f)
        assertFalse(MixologyStations.arrowClear(under, spot, listOf(below), width, height))
        assertTrue(MixologyStations.arrowClear(under, spot, listOf(MixologyStations.Area(0f, 0f, 400f, 300f)), width, height))
        // A station right off the top of the screen gets no arrow at all, and one at the very bottom none below it
        val gone = MixologyStations.Spot(1160.0, -20.0)
        assertFalse(MixologyStations.arrowClear(MixologyStations.arrowUnder(gone, spacing, scale), gone, emptyList(), width, height))
        val low = MixologyStations.Spot(1160.0, 1040.0)
        assertFalse(MixologyStations.arrowClear(MixologyStations.arrowUnder(low, spacing, scale), low, emptyList(), width, height))
        assertTrue(MixologyStations.arrowClear(MixologyStations.arrowOver(low, spacing, scale), low, emptyList(), width, height))
    }

    // A picture with arrows drawn on it as the phone would show them: the yellow shape with its dark edge, 80% solid
    private fun withArrows(shot: Shot, arrows: List<MixologyStations.Arrow>): Shot {
        val data = shot.data.copyOf()
        for (a in arrows) {
            val p = a.outline()
            val half = a.width * MixologyStations.ARROW_EDGE / 2
            for (y in (a.top - 2).toInt().coerceAtLeast(0)..(a.bottom + 2).toInt().coerceAtMost(shot.height - 1)) {
                for (x in (a.left - 2).toInt().coerceAtLeast(0)..(a.right + 2).toInt().coerceAtMost(shot.width - 1)) {
                    val px = x + 0.5f
                    val py = y + 0.5f
                    // inside the outline? and how far from its nearest side?
                    var inside = false
                    var near = Float.MAX_VALUE
                    var j = p.size - 2
                    for (i in p.indices step 2) {
                        val x1 = p[i]; val y1 = p[i + 1]; val x2 = p[j]; val y2 = p[j + 1]
                        if ((y1 > py) != (y2 > py) && px < (x2 - x1) * (py - y1) / (y2 - y1) + x1) inside = !inside
                        val dx = x2 - x1
                        val dy = y2 - y1
                        val t = (((px - x1) * dx + (py - y1) * dy) / (dx * dx + dy * dy)).coerceIn(0f, 1f)
                        near = minOf(near, hypot(px - (x1 + t * dx), py - (y1 + t * dy)))
                        j = i
                    }
                    val colour = if (near <= half) 0x000000 else if (inside) MixologyStations.ARROW_COLOUR else continue
                    data[y * shot.width + x] = blend(colour, data[y * shot.width + x] and 0xFFFFFF)
                }
            }
        }
        return Shot(data, shot.width, shot.height)
    }

    // Everything the tool's readers say about a picture, as words to compare. `arrows` = the arrows the tool has
    // on screen: the Alembic and the Retort aren't looked for under them (as in the tool), so both pictures are read
    // with the same parts left out.
    private fun everything(shot: Shot, bar: MixologyStations.Bar?, arrows: List<MixologyStations.Arrow>): String {
        val box = MixologyReader.findOrderBox(shot) ?: return "no box"
        val orders = MixologyReader.readOrders(shot, box, shapes)
        val row = MixologyLevers.find(shot)
        val compass = MixologyLevers.findCompass(shot, box.scale)
        val found = MixologyStations.findBar(shot, box.scale)
        val bag = MixologyBag.Watch()
        val tags = bag.look(shot, 0, potions)
        val under = arrows.map { MixologyStations.Area(it.left, it.top, it.right, it.bottom) }
        val looks = if (row != null && compass != null) MixologyStationLooks.read(shot, row, compass.angle, under) else null
        return listOf(
            "box ${box.left},${box.top},${box.right},${box.bottom}",
            "orders " + orders.map { it?.name },
            "stations " + MixologyStations.stationsOf(shot, box, orders),
            "funnels " + row?.funnels?.map { "%.0f,%.0f".format(it.x, it.y) } + " rings " + (row?.rings != null),
            "compass " + compass?.let { "%.1f %s".format(it.angle, it.exact) },
            "bar " + found?.let { "${it.left},${it.top},${it.right},${it.bottom} %.2f".format(it.fill) },
            "bar near " + bar?.let { MixologyStations.findBar(shot, box.scale, it)?.left },
            "green " + found?.let { MixologyStations.green(shot, it) },
            "looks " + looks?.let { "${it.alembic} ${it.retort} ${it.agitator}" },
            "panel ${bag.panel} open ${bag.open} tags " + tags.map { "${it.slot}${it.recipe}${it.finished}" }
        ).joinToString("\n")
    }

    // The arrows the tool could have on screen in a picture: over each station where the arrow's rules allow one
    // (clear of the game's interface), and, when a station is working, over that station's bar as well: the arrow is
    // still up on the look that first sees the bar. The arrow below a station, pointing up, is drawn too wherever
    // the rules allow it (the tool only uses it when there's no room above; here both go on, which asks more).
    private fun arrowsFor(shot: Shot, tilt: Double): List<MixologyStations.Arrow> {
        val s = see(shot)
        val row = s.row ?: return emptyList()
        val compass = s.compass ?: return emptyList()
        val bag = MixologyBag.Watch()
        bag.look(shot, 0, potions)
        val areas = MixologyStations.interfaceAreas(s.box, compass, bag.panel, s.box.scale, shot.width, shot.height) +
            MixologyStations.tableArea(row)
        val spots = Station.entries.map { MixologyStations.stationSpot(row, compass.angle, tilt, it, shot.width, shot.height) } +
            listOfNotNull(s.bar?.let { MixologyStations.Spot((it.left + it.right) / 2.0, (it.top + it.bottom) / 2.0) })
        return spots.flatMap { spot ->
            listOf(MixologyStations.arrowOver(spot, row.spacing, s.box.scale), MixologyStations.arrowUnder(spot, row.spacing, s.box.scale))
                .filter { MixologyStations.arrowClear(it, spot, areas, shot.width, shot.height) }
        }
    }

    // With the arrows drawn on, every reader says exactly what it said without them: the orders and their stations,
    // the funnels, the compass, the progress bar (and its green), the side panel and the bag's potions, and the
    // tilts read from the stations' looks
    @Test
    fun itsOwnArrowsDontChangeWhatsRead() {
        val files = (File(dir, "frames3/station_arrow").listFiles { f -> f.name.endsWith(".png") }?.toList() ?: emptyList()) +
            listOf("000.0", "029.5", "037.40", "051.65", "119.30", "142.0", "224.50", "286.0").map { File(dir, "frames3/play2_${it}s.png") }
        var drawn = 0
        var pictures = 0
        for (file in files.sortedBy { it.name }) {
            val shot = load(file) ?: continue
            val s = see(shot)
            val tilt = if (s.row != null && s.compass != null && s.bar != null)
                MixologyStations.learnTilt(s.row, s.compass.angle, s.bar, shot.width, shot.height)?.tilt ?: 57.0 else 57.0
            val arrows = arrowsFor(shot, tilt)
            if (arrows.isEmpty()) continue
            assertEquals(file.name, everything(shot, s.bar, arrows), everything(withArrows(shot, arrows), s.bar, arrows))
            drawn += arrows.size
            pictures++
        }
        assumeTrue(pictures > 0)
        assertTrue("only $drawn arrows on $pictures pictures", drawn >= 25)
    }

    // The same check has teeth: an arrow in the progress bar's own yellow, as wide as a bar, is taken for one
    @Test
    fun aBarYellowArrowWouldBeTakenForABar() {
        val shot = frame("play9_012.0s") ?: run { assumeTrue(false); return }
        val box = MixologyReader.findOrderBox(shot)!!
        assertNull(MixologyStations.findBar(shot, box.scale))
        val data = shot.data.copyOf()
        // a plain block 50 by 5 game pixels, 30% of it the bar's yellow and the rest its olive, out on the floor
        val wide = (50 * box.scale).toInt()
        val tall = (5 * box.scale).toInt()
        for (y in 300 until 300 + tall) for (x in 1100 until 1100 + wide) {
            data[y * shot.width + x] = blend(if (x < 1100 + wide * 3 / 10) 0xDAD100 else 0x2C2A00, data[y * shot.width + x] and 0xFFFFFF, 1.0)
        }
        assertNotNull(MixologyStations.findBar(Shot(data, shot.width, shot.height), box.scale))
    }

    // ---- Keeping off the interface ----

    // play9_012.0s, places read off the picture by hand: the middle of the minimap (2120, 193), the second order's
    // name (410, 160), the middle of the open inventory (1907, 760), the left-hand buttons (170, 700)
    @Test
    fun theArrowKeepsOffTheGamesInterface() {
        val shot = frame("play9_012.0s") ?: run { assumeTrue(false); return }
        val s = see(shot)
        val bag = MixologyBag.Watch()
        bag.look(shot, 0, potions)
        assertNotNull("the side panel", bag.panel)
        val areas = MixologyStations.interfaceAreas(s.box, s.compass, bag.panel, s.box.scale, shot.width, shot.height)
        fun allowed(x: Double, y: Double, more: List<MixologyStations.Area> = emptyList()): Boolean {
            val spot = MixologyStations.Spot(x, y)
            return MixologyStations.arrowClear(MixologyStations.arrowOver(spot, s.row!!.spacing, s.box.scale), spot,
                areas + more, shot.width, shot.height)
        }
        assertFalse("minimap", allowed(2120.0, 193.0))
        assertFalse("order box", allowed(410.0, 160.0))
        assertFalse("inventory", allowed(1907.0, 760.0))
        assertFalse("left buttons", allowed(170.0, 700.0))
        // A station just under the top of the screen: the arrow above it would be cut off
        assertFalse("top edge", allowed(1170.0, 60.0))
        // Off the screen altogether
        assertFalse(allowed(-50.0, 500.0))
        assertFalse(allowed(1170.0, 1200.0))
        // The stations themselves, by eye in this picture, are all clear
        assertTrue("Alembic", allowed(826.0, 387.0))
        assertTrue("Retort", allowed(1563.0, 415.0))
        assertTrue("Agitator", allowed(1208.0, 196.0))
        // The lever table isn't interface, but the arrow keeps off it too: it's what the arrow is placed from
        // (the middle lever's dot in this picture is at about (1176, 632))
        assertFalse("the table", allowed(1176.0, 660.0, listOf(MixologyStations.tableArea(s.row!!))))
        assertTrue(allowed(826.0, 387.0, listOf(MixologyStations.tableArea(s.row))))
        // ...unless the tool's own window is over one
        val window = MixologyStations.Area(700f, 250f, 1000f, 450f)
        assertFalse(allowed(826.0, 387.0, listOf(window)))
        assertTrue(allowed(1563.0, 415.0, listOf(window)))
    }

    // With the chat open the order box sits lower and the chat is above it (orders_chat_open.png: the chat's tabs
    // and lines run from the left edge to about x = 1200, down to y = 320)
    @Test
    fun theArrowKeepsOffTheOpenChat() {
        val shot = load(File(dir, "orders_chat_open.png")) ?: run { assumeTrue(false); return }
        val s = see(shot)
        val areas = MixologyStations.interfaceAreas(s.box, s.compass, null, s.box.scale, shot.width, shot.height)
        fun allowed(x: Double, y: Double): Boolean {
            val spot = MixologyStations.Spot(x, y)
            return MixologyStations.arrowClear(MixologyStations.arrowOver(spot, 97.0, s.box.scale), spot, areas, shot.width, shot.height)
        }
        assertFalse(allowed(900.0, 250.0))     // on the chat's lines
        assertFalse(allowed(1100.0, 300.0))
        assertTrue(allowed(1400.0, 500.0))     // out in the room
    }
}

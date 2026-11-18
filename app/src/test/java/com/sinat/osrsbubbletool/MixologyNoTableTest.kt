package com.sinat.osrsbubbletool

import com.sinat.osrsbubbletool.MixologyStations.Station.AGITATOR
import com.sinat.osrsbubbletool.MixologyStations.Station.ALEMBIC
import com.sinat.osrsbubbletool.MixologyStations.Station.RETORT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.hypot

// The arrow over the next Mastering Mixology station with the lever table off the screen (zoomed in at a station):
// MixologyStationLooks.readWithoutTable and Sights.
//
// The pictures are the owner's (Documents\Androiddev\mixology_shots\frames3\no_table, or set
// MIXOLOGY_SHOTS=<folder>; described in its notes.md), not part of the project: those tests are skipped without
// them. Every expected place was measured by hand, on a four times enlargement with lines every 10 pixels: the
// spot where that station's progress bar sits when it works (facing north the join of the Alembic's two bulbs,
// just under the middle of the Retort's pot, between the Agitator's middle and right beakers a third of the way
// down; facing south a little right of and below the middle of the Alembic's big bulb, the middle of the Retort's
// pot). My picks are good to about 0.15 of a funnel spacing.
class MixologyNoTableTest {

    private class Shot(val data: IntArray, override val width: Int, override val height: Int) : LightBoxReader.PixelSource {
        override fun rgb(x: Int, y: Int) = data[y * width + x] and 0xFFFFFF
    }

    private val dir = File(System.getenv("MIXOLOGY_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\mixology_shots")
    private val potions by lazy { MixologyBag.readSummaries(File("src/main/assets/mixology/potions.tsv").readText()) }

    private fun load(path: String): Shot? {
        val file = File(dir, path)
        if (!file.isFile) return null
        val image = ImageIO.read(file)
        val data = IntArray(image.width * image.height)
        image.getRGB(0, 0, image.width, image.height, data, 0, image.width)
        return Shot(data, image.width, image.height)
    }

    // What the tool has when it looks: the order box, the compass, and the parts of the screen it keeps out of
    private class Seen(val shot: Shot, val box: MixologyReader.Box, val compass: MixologyLevers.Compass,
                       val skip: List<MixologyStations.Area>)

    private fun see(shot: Shot): Seen {
        val box = MixologyReader.findOrderBox(shot)!!
        val compass = MixologyLevers.findCompass(shot, box.scale)!!
        val bag = MixologyBag.Watch()
        bag.look(shot, 0, potions)
        return Seen(shot, box, compass, MixologyStations.interfaceAreas(box, compass, bag.panel, box.scale, shot.width, shot.height))
    }

    private fun sight(s: Seen, skip: List<MixologyStations.Area> = s.skip) =
        MixologyStationLooks.readWithoutTable(s.shot, s.compass.angle, skip)

    // A picture with no lever table found in it: where each station that should get a place is, by the ruler
    private class Still(val path: String, val places: Map<MixologyStations.Station, Pair<Double, Double>>)

    private val stills = listOf(
        // play_11.mp4, zoomed in at the Agitator facing north, the table off the bottom of the screen
        Still("frames3/no_table/play11_018.0s.png", mapOf(ALEMBIC to (706.0 to 665.0), AGITATOR to (1085.0 to 419.0), RETORT to (1421.0 to 676.0))),
        Still("frames3/no_table/play11_028.5s.png", mapOf(ALEMBIC to (668.0 to 710.0), AGITATOR to (1068.0 to 413.0), RETORT to (1464.0 to 672.0))),
        // play_12.mp4, just after the Agitator finished; in the first the Retort's pot is too near the side panel
        // to be used, so the Retort gets no place
        Still("frames3/no_table/play12_072.0s.png", mapOf(ALEMBIC to (460.0 to 747.0), AGITATOR to (1040.0 to 372.0))),
        Still("frames3/no_table/play12_075.5s.png", mapOf(ALEMBIC to (565.0 to 628.0), AGITATOR to (1072.0 to 403.0), RETORT to (1491.0 to 667.0))),
        Still("frames3/no_table/play12_077.5s.png", mapOf(ALEMBIC to (698.0 to 670.0), AGITATOR to (1085.0 to 413.0), RETORT to (1424.0 to 697.0))),
        // stations_360.mp4: facing north (the character in front of the Agitator's middle beaker in the first)
        Still("frames3/no_table/s360_078.0s.png", mapOf(ALEMBIC to (793.0 to 617.0), AGITATOR to (1164.0 to 433.0), RETORT to (1524.0 to 622.0))),
        Still("frames3/no_table/s360_063.0s.png", mapOf(ALEMBIC to (727.0 to 572.0), AGITATOR to (1097.0 to 418.0), RETORT to (1361.0 to 635.0))),
        // and facing south, where the Agitator has no look of its own: only the Alembic and the Retort
        Still("frames3/no_table/s360_025.0s.png", mapOf(ALEMBIC to (1595.0 to 600.0), RETORT to (751.0 to 569.0))),
        Still("frames3/no_table/s360_109.0s.png", mapOf(ALEMBIC to (1660.0 to 649.0), RETORT to (607.0 to 487.0)))
    )

    // The places after the tool has looked at the same picture twice
    private fun placed(s: Seen, station: MixologyStations.Station): MixologyStationLooks.Sights.Placed? {
        val sights = MixologyStationLooks.Sights()
        sights.look(1_000, sight(s))
        sights.look(1_333, sight(s))
        return sights.spot(1_333, station, s.shot.width, s.shot.height)
    }

    @Test
    fun theStationsArePlacedWithTheLeverTableOffTheScreen() {
        assumeTrue(load(stills[0].path) != null)
        for (still in stills) {
            val shot = load(still.path)!!
            // (the way it was: no row of funnels, so no arrow at all)
            assertNull(still.path, MixologyLevers.find(shot))
            val s = see(shot)
            val found = sight(s)
            assertNotNull(still.path, found)
            for (station in MixologyStations.Station.entries) {
                val at = placed(s, station)
                val want = still.places[station]
                if (want == null) { assertNull("${still.path} $station", at); continue }
                assertNotNull("${still.path} $station", at)
                val off = hypot(at!!.spot.x - want.first, at.spot.y - want.second) / found!!.view.spacing
                assertTrue("${still.path} $station is $off off", off <= 0.3)
            }
        }
    }

    @Test
    fun itTakesTwoLooksThatAgree() {
        val shot = load(stills[0].path)
        assumeTrue(shot != null)
        val s = see(shot!!)
        val sights = MixologyStationLooks.Sights()
        sights.look(1_000, sight(s))
        assertNull(sights.spot(1_000, RETORT, shot.width, shot.height))
        // the second look too long after the first: still nothing
        sights.look(2_000, sight(s))
        assertNull(sights.spot(2_000, RETORT, shot.width, shot.height))
        sights.look(2_333, sight(s))
        assertNotNull(sights.spot(2_333, RETORT, shot.width, shot.height))
        // a look of another view, with the stations somewhere else on the screen: nothing until that one's seen twice
        val other = see(load(stills[7].path)!!)
        sights.forget()
        sights.look(3_000, sight(s))
        sights.look(3_333, sight(other))
        assertNull(sights.spot(3_333, ALEMBIC, shot.width, shot.height))
    }

    @Test
    fun oneLookThatMissesChangesNothing() {
        val shot = load(stills[0].path)
        assumeTrue(shot != null)
        val s = see(shot!!)
        val sights = MixologyStationLooks.Sights()
        sights.look(1_000, sight(s))
        sights.look(1_333, sight(s))
        val before = sights.spot(1_333, ALEMBIC, shot.width, shot.height)!!
        sights.look(1_666, null)
        val held = sights.spot(1_666, ALEMBIC, shot.width, shot.height)
        assertNotNull(held)
        assertEquals(before.spot.x, held!!.spot.x, 0.0)
        // (only for the station it was for, and not for long)
        assertNull(sights.spot(1_666, RETORT, shot.width, shot.height))
        sights.look(2_000, null)
        assertNull(sights.spot(2_000, ALEMBIC, shot.width, shot.height))
    }

    @Test
    fun onlyFacingSouthOrNorth() {
        val shot = load(stills[0].path)
        assumeTrue(shot != null)
        val s = see(shot!!)
        assertNull(MixologyStationLooks.readWithoutTable(shot, 0.0, s.skip))
        assertNull(MixologyStationLooks.readWithoutTable(shot, 180.0, s.skip))
        // told it faces south when it faces north, nothing fits the room
        assertNull(MixologyStationLooks.readWithoutTable(shot, 90.0, s.skip))
    }

    // The arrow is drawn over the game, and the next look sees it: wherever it would go for each station (above
    // and below), the stations are still found, in the same places
    @Test
    fun itsOwnArrowDoesntHideTheStations() {
        assumeTrue(load(stills[0].path) != null)
        for (still in stills) {
            val shot = load(still.path)!!
            val s = see(shot)
            val w = shot.width
            val h = shot.height
            for (station in still.places.keys) {
                val at = placed(s, station)!!
                for (arrow in listOf(
                    MixologyStations.arrowClearOf(MixologyStations.arrowOver(at.spot, at.spacing, s.box.scale), at.keep),
                    MixologyStations.arrowClearOf(MixologyStations.arrowUnder(at.spot, at.spacing, s.box.scale), at.keep))) {
                    val drawn = Shot(shot.data.copyOf(), w, h)
                    for (y in arrow.top.toInt().coerceAtLeast(0)..arrow.bottom.toInt().coerceAtMost(h - 1)) {
                        for (x in arrow.left.toInt().coerceAtLeast(0)..arrow.right.toInt().coerceAtMost(w - 1)) {
                            drawn.data[y * w + x] = MixologyStations.ARROW_COLOUR
                        }
                    }
                    val skip = s.skip + MixologyStations.Area(arrow.left, arrow.top, arrow.right, arrow.bottom)
                    val again = MixologyStationLooks.readWithoutTable(drawn, s.compass.angle, skip)
                    assertNotNull("${still.path} $station", again)
                    assertNotNull("${still.path} $station", again!!.found(station))
                    val spot = MixologyStations.viewStationSpot(again.view, station, w, h)
                    assertTrue("${still.path} $station", hypot(spot.x - at.spot.x, spot.y - at.spot.y) <= 0.1 * at.spacing)
                }
            }
        }
    }

    @Test
    fun theArrowKeepsOffTheThingFound() {
        val keep = MixologyStations.Area(100f, 100f, 200f, 200f)
        val over = MixologyStations.arrowClearOf(MixologyStations.Arrow(150f, 150f, 40f, 60f), keep)
        assertTrue(over.bottom <= keep.top)
        val under = MixologyStations.arrowClearOf(MixologyStations.Arrow(150f, 150f, 40f, 60f, up = true), keep)
        assertTrue(under.top >= keep.bottom)
        // one that's clear already stays where it is
        val clear = MixologyStations.Arrow(150f, 90f, 40f, 60f)
        assertEquals(90f, MixologyStations.arrowClearOf(clear, keep).tipY, 0f)
    }

    // Never the wrong station. In the pictures that do have the lever table, the table-less way is tried as well
    // (it doesn't use the table), and wherever both give a station a place, it's the same station: within one
    // funnel spacing (the stations are three or more apart; measured, they agree to 0.1 in most).
    @Test
    fun itAgreesWithTheLeverTable() {
        val folders = listOf("frames3/station_arrow", "frames3/station_looks", "frames3/stations_360")
        assumeTrue(File(dir, folders[0]).isDirectory)
        var compared = 0
        for (folder in folders) for (file in File(dir, folder).listFiles()!!.sortedBy { it.name }) {
            if (!file.name.endsWith(".png")) continue
            val shot = load("$folder/${file.name}")!!
            val box = MixologyReader.findOrderBox(shot) ?: continue
            val row = MixologyLevers.find(shot) ?: continue
            val compass = MixologyLevers.findCompass(shot, box.scale) ?: continue
            if (!compass.exact) continue
            val s = see(shot)
            val found = sight(s) ?: continue
            val tilts = MixologyStationLooks.Tilts()
            val read = MixologyStationLooks.read(shot, row, compass.angle, s.skip)
            repeat(2) { tilts.look(row, compass, read, shot.width, shot.height) }
            for (station in MixologyStations.Station.entries) {
                if (found.found(station) == null) continue
                val byTable = tilts.spot(row, compass, station, shot.width, shot.height) ?: continue
                val bySight = MixologyStations.viewStationSpot(found.view, station, shot.width, shot.height)
                val off = hypot(byTable.x - bySight.x, byTable.y - bySight.y) / row.spacing
                assertTrue("$folder/${file.name} $station is $off from the table's place", off <= 1.0)
                compared++
            }
        }
        assertTrue("only $compared places compared", compared >= 10)
    }
}

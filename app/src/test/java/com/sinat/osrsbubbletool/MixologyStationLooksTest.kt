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

// Finding the Mastering Mixology stations by how they look (MixologyStationLooks), so the arrow over the next station
// shows from the first looks, with no station having worked yet.
//
// The pictures are the owner's (Documents\Androiddev\mixology_shots, or set MIXOLOGY_SHOTS=<folder>; described in
// its notes.md), not part of the project: those tests are skipped without them. Every expected place was measured
// by hand: each station enlarged four times with a ruler drawn over it, and the spot picked by eye where that
// station's progress bar sits when it works (learned from the frames that have a bar: facing north the join of the
// Alembic's two bulbs, just under the middle of the Retort's pot, between the Agitator's middle and right beakers a
// third of the way down; facing south a little right of and below the middle of the Alembic's big bulb, the middle
// of the Retort's pot, between the Agitator's left and middle beakers). My picks are good to about 0.15 of a
// funnel spacing.
class MixologyStationLooksTest {

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

    // What the tool has when it looks for the stations: the funnels, the compass, and the parts of the screen it
    // keeps out of (the game's interface and a working station's bar)
    private class Seen(val shot: Shot, val row: MixologyLevers.Levers, val compass: MixologyLevers.Compass,
                       val bar: MixologyStations.Bar?, val skip: List<MixologyStations.Area>)

    private fun see(shot: Shot, last: MixologyLevers.Levers? = null): Seen {
        val box = MixologyReader.findOrderBox(shot)!!
        val row = MixologyLevers.find(shot, last = last)!!
        val compass = MixologyLevers.findCompass(shot, box.scale)!!
        val bar = MixologyStations.findBar(shot, box.scale)
        val bag = MixologyBag.Watch()
        bag.look(shot, 0, potions)
        val skip = ArrayList(MixologyStations.interfaceAreas(box, compass, bag.panel, box.scale, shot.width, shot.height))
        if (bar != null) {
            val room = bar.length.toFloat()
            skip.add(MixologyStations.Area(bar.left - room, bar.top - room, bar.right + room, bar.bottom + room))
        }
        return Seen(shot, row, compass, bar, skip)
    }

    private fun read(s: Seen) = MixologyStationLooks.read(s.shot, s.row, s.compass.angle, s.skip)

    // The tilts after the tool has looked at the same picture `looks` times
    private fun tilts(s: Seen, looks: Int = 2): MixologyStationLooks.Tilts {
        val tilts = MixologyStationLooks.Tilts()
        repeat(looks) { tilts.look(s.row, s.compass, read(s), s.shot.width, s.shot.height) }
        return tilts
    }

    private fun off(spot: MixologyStations.Spot, x: Double, y: Double, row: MixologyLevers.Levers) =
        hypot(spot.x - x, spot.y - y) / row.spacing

    // A picture with nothing learned: where each station is by the ruler, and how far off the arrow's place may be
    private class Still(val path: String, val places: Map<MixologyStations.Station, Triple<Double, Double, Double>>)

    private val stills = listOf(
        // Facing north, standing at the mixing vessel (the places are MixologyArrowTest's)
        Still("frames3/station_arrow/play9_012.0s.png", mapOf(ALEMBIC to Triple(826.0, 387.0, 0.3),
            AGITATOR to Triple(1208.0, 196.0, 0.5), RETORT to Triple(1563.0, 415.0, 0.3))),
        // Facing north in another recording, another player beside the Alembic
        Still("frames3/station_looks/north1_017s.png", mapOf(ALEMBIC to Triple(882.0, 352.0, 0.3),
            AGITATOR to Triple(1174.0, 185.0, 0.5), RETORT to Triple(1470.0, 360.0, 0.3))),
        // Facing south, zoomed out (the Alembic used to land by its small bulb here, two thirds of a spacing out)
        Still("frames3/station_looks/levers2_039s.png", mapOf(ALEMBIC to Triple(1463.0, 590.0, 0.3),
            AGITATOR to Triple(1232.0, 872.0, 0.4), RETORT to Triple(925.0, 640.0, 0.3)))
    )

    // Frames of stations_360.mp4 (the owner turning the camera all the way round at each station, at many tilts and
    // zooms; the frames are in frames3/stations_360). The places were picked by eye like the ones above, each
    // station enlarged three or four times under a ruler, before the tool's answers were looked at.
    private val turned = listOf(
        // Facing north at the lever table, the Agitator near the top of the screen
        Still("frames3/stations_360/s360_003.0s.png", mapOf(ALEMBIC to Triple(869.0, 369.0, 0.3),
            AGITATOR to Triple(1165.0, 212.0, 0.4), RETORT to Triple(1447.0, 383.0, 0.3))),
        // Facing north with the camera tilted low (about 30 degrees), standing at the Retort, which the game has
        // outlined; a player stands by the Agitator. The old numbers had both lumps a fifth of a spacing off their
        // paths here and said nothing.
        Still("frames3/stations_360/s360_040.0s.png", mapOf(ALEMBIC to Triple(641.0, 497.0, 0.3),
            AGITATOR to Triple(993.0, 355.0, 0.4), RETORT to Triple(1287.0, 488.0, 0.3))),
        // Facing north, zoomed right in, turned 7 degrees
        Still("frames3/stations_360/s360_115.2s.png", mapOf(ALEMBIC to Triple(652.0, 411.0, 0.3),
            AGITATOR to Triple(1145.0, 192.0, 0.4), RETORT to Triple(1676.0, 380.0, 0.3))),
        // Facing south, zoomed in, the Agitator at the very bottom of the screen
        Still("frames3/stations_360/s360_108.8s.png", mapOf(ALEMBIC to Triple(1676.0, 568.0, 0.3),
            AGITATOR to Triple(1143.0, 972.0, 0.4), RETORT to Triple(603.0, 576.0, 0.3))),
        // Facing south, zoomed out, turned 6 degrees, standing by the Retort
        Still("frames3/stations_360/s360_051.0s.png", mapOf(ALEMBIC to Triple(1637.0, 481.0, 0.3),
            AGITATOR to Triple(1435.0, 702.0, 0.4), RETORT to Triple(1058.0, 507.0, 0.3)))
    )

    // ---- From the first looks, with no station working ----

    // Two looks at a picture where nothing has worked yet, and every station has its place, where the ruler says.
    // Before this, there was no place at all until a station had worked with the table on screen.
    @Test
    fun theStationsArePlacedWithoutAnyStationHavingWorked() {
        var checked = 0
        for (still in stills) {
            val s = load(still.path)?.let { see(it) } ?: continue
            assertNull(still.path, s.bar)
            // The old way knows nothing here: no bar, no tilt
            assertNull(MixologyStations.Tilt().spot(s.row, s.compass, RETORT, s.shot.width, s.shot.height))
            val tilts = tilts(s)
            for ((station, place) in still.places) {
                val spot = tilts.spot(s.row, s.compass, station, s.shot.width, s.shot.height)
                assertNotNull("${still.path}: no place for $station", spot)
                val by = off(spot!!, place.first, place.second, s.row)
                assertTrue("${still.path}: $station off by $by spacings (${spot.x}, ${spot.y})", by <= place.third)
            }
            checked++
        }
        assumeTrue(checked > 0)
    }

    // The same for the frames of the camera turned all the way round at each station: north and south, tilted low
    // and high, zoomed in and out, every station has its place, where the ruler says
    @Test
    fun theStationsArePlacedFromEverySideInTheTurningFootage() {
        var checked = 0
        for (still in turned) {
            val s = load(still.path)?.let { see(it) } ?: continue
            val tilts = tilts(s)
            for ((station, place) in still.places) {
                val spot = tilts.spot(s.row, s.compass, station, s.shot.width, s.shot.height)
                assertNotNull("${still.path}: no place for $station", spot)
                val by = off(spot!!, place.first, place.second, s.row)
                assertTrue("${still.path}: $station off by $by spacings (${spot.x}, ${spot.y})", by <= place.third)
            }
            checked++
        }
        assumeTrue(checked > 0)
    }

    // The Agitator has a look of its own facing north: the plain beaker at the left end of its row. It gives the
    // same tilt as the Alembic's bulb and the Retort's pot (within 2 degrees), it goes when the beaker is covered,
    // and it isn't looked for facing south.
    @Test
    fun theAgitatorIsFoundByItsLeftBeakerFacingNorth() {
        var checked = 0
        for (path in listOf("frames3/station_looks/north1_017s.png", "frames3/station_arrow/play9_012.0s.png",
            "frames3/stations_360/s360_003.0s.png")) {
            val s = load(path)?.let { see(it) } ?: continue
            val read = read(s)
            assertNotNull(path, read)
            assertNotNull("$path: the Agitator's beaker", read!!.agitator)
            assertNotNull("$path: the Alembic's bulb", read.alembic)
            assertEquals(path, read.alembic!!, read.agitator!!, 2.0)
            assertEquals(path, read.retort!!, read.agitator, 2.0)
            checked++
        }
        // (in north1_017s the left beaker's middle is at about (1114, 193), 12 pixels in radius)
        load("frames3/station_looks/north1_017s.png")?.let { see(it) }?.let { s ->
            val covered = MixologyStationLooks.read(s.shot, s.row, s.compass.angle, s.skip + MixologyStations.Area(1095f, 175f, 1132f, 212f))!!
            assertNull(covered.agitator)
            assertNotNull(covered.alembic)
        }
        for (path in listOf("frames3/station_looks/levers2_039s.png", "frames3/stations_360/s360_108.8s.png", "brightness_max.png")) {
            val s = load(path)?.let { see(it) } ?: continue
            assertNull("$path: facing south", read(s)?.agitator)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // The camera left alone while the funnels go from empty to full (play_10.mp4 at 31.5 and 32 s): the stations
    // haven't moved, so they must be read the same. The row is measured by its rings when the funnels are empty
    // and by the paste when they're full, and the paste's middle is higher up: before that was allowed for, the
    // tilt read 7 to 8 degrees apart in the two pictures.
    @Test
    fun fullAndEmptyFunnelsReadTheSame() {
        val empty = load("frames3/stations_360/play10_031.5s.png")?.let { see(it) }
        val full = load("frames3/stations_360/play10_032.0s.png")?.let { see(it) }
        assumeTrue(empty != null && full != null)
        assertNotNull(empty!!.row.rings)
        assertNull(full!!.row.rings)
        val a = read(empty)!!
        val b = read(full)!!
        assertEquals(a.retort!!, b.retort!!, 1.5)
        assertEquals(a.agitator!!, b.agitator!!, 1.5)
        for (station in MixologyStations.Station.entries) {
            val one = tilts(empty).spot(empty.row, empty.compass, station, empty.shot.width, empty.shot.height)!!
            val two = tilts(full).spot(full.row, full.compass, station, full.shot.width, full.shot.height)!!
            val by = hypot(one.x - two.x, one.y - two.y) / empty.row.spacing
            assertTrue("$station moved $by spacings", by <= 0.1)
        }
    }

    // The compass is only read to within a degree or two, and a degree would be a tenth of a spacing at the
    // Agitator. The camera's turn is taken from the row of funnels' own slant (never more than 3 degrees from the
    // compass), so a compass reading a degree further out either way puts every station in the same place
    // (within a twentieth of a spacing).
    @Test
    fun aCompassADegreeOutChangesNothing() {
        var checked = 0
        for (path in listOf("frames3/stations_360/s360_003.0s.png", "frames3/station_looks/levers2_039s.png")) {
            val s = load(path)?.let { see(it) } ?: continue
            for (station in MixologyStations.Station.entries) {
                val right = MixologyStations.stationSpot(s.row, s.compass.angle, 45.0, station, s.shot.width, s.shot.height)
                for (out in doubleArrayOf(-1.0, 1.0)) {
                    val spot = MixologyStations.stationSpot(s.row, s.compass.angle + out, 45.0, station, s.shot.width, s.shot.height)
                    val by = hypot(spot.x - right.x, spot.y - right.y) / s.row.spacing
                    assertTrue("$path: $station moved $by spacings with the compass $out degrees out", by <= 0.05)
                }
            }
            checked++
        }
        assumeTrue(checked > 0)
    }

    // One look isn't enough (it could be anything grey and round): the second, agreeing, is
    @Test
    fun itTakesTwoLooksThatAgree() {
        val s = load("frames3/station_arrow/play9_012.0s.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        val w = s.shot.width
        val h = s.shot.height
        assertNull(tilts(s, 1).spot(s.row, s.compass, ALEMBIC, w, h))
        assertNotNull(tilts(s, 2).spot(s.row, s.compass, ALEMBIC, w, h))
        // Two looks that read tilts 5 degrees apart teach nothing
        val tilts = MixologyStationLooks.Tilts()
        tilts.look(s.row, s.compass, MixologyStationLooks.Read(40.0, null), w, h)
        tilts.look(s.row, s.compass, MixologyStationLooks.Read(45.0, null), w, h)
        assertNull(tilts.spot(s.row, s.compass, ALEMBIC, w, h))
        // ...and a look that reads nothing in between starts the count again
        tilts.look(s.row, s.compass, null, w, h)
        tilts.look(s.row, s.compass, MixologyStationLooks.Read(45.0, null), w, h)
        assertNull(tilts.spot(s.row, s.compass, ALEMBIC, w, h))
        tilts.look(s.row, s.compass, MixologyStationLooks.Read(45.5, null), w, h)
        assertNotNull(tilts.spot(s.row, s.compass, ALEMBIC, w, h))
    }

    // The arrow's place before a station is used is where that station's bar shows once it works. Each picture
    // here has no bar, and the same view (the character standing at the station, the camera untouched) as one of
    // MixologyArrowTest's pictures with a bar, whose middle was measured with the ruler.
    @Test
    fun theArrowIsWhereTheBarWillBe() {
        class Before(val path: String, val station: MixologyStations.Station, val x: Double, val y: Double)
        val pictures = listOf(
            Before("play5_046s", ALEMBIC, 1035.8, 429.2),    // facing north; the bar of play5_038.0s
            Before("play8_010s", ALEMBIC, 1033.7, 408.1),    // facing north; play8_018.0s
            Before("play5_056s", RETORT, 1303.9, 490.2),     // facing north; play5_055.0s
            Before("play5_209s", ALEMBIC, 1312.7, 604.2)     // facing south; play5_200.0s
        )
        var checked = 0
        for (b in pictures) {
            val s = load("frames3/station_looks/${b.path}.png")?.let { see(it) } ?: continue
            assertNull(b.path, s.bar)
            val spot = tilts(s).spot(s.row, s.compass, b.station, s.shot.width, s.shot.height)
            assertNotNull("${b.path}: no place for ${b.station}", spot)
            val by = off(spot!!, b.x, b.y, s.row)
            assertTrue("${b.path}: ${b.station} off by $by spacings (${spot.x}, ${spot.y})", by <= 0.15)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // ---- Never a guess ----

    // play_5.mp4 at 190 s: facing south, zoomed out, the camera looking almost straight down. The Alembic and the
    // Retort are each placed by their own look (the Alembic's bar sits 10 pixels right of its big bulb's middle
    // and 17 below, by play5_200.0s: the bulb's middle is at (1477, 692) here; the Retort's pot is at (860, 684)).
    // But the two looks read tilts 6 degrees apart, so one of them is out, and the Agitator, which has no look of
    // its own from the south, gets no place: no arrow rather than one in a doubtful place.
    @Test
    fun looksThatDisagreePlaceNoOtherStation() {
        val s = load("frames3/station_arrow/play5_190.0s.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        val w = s.shot.width
        val h = s.shot.height
        val read = read(s)!!
        assertTrue(kotlin.math.abs(read.alembic!! - read.retort!!) > MixologyStationLooks.Tilts.APART)
        val tilts = tilts(s)
        val alembic = tilts.spot(s.row, s.compass, ALEMBIC, w, h)!!
        assertTrue(off(alembic, 1487.0, 709.0, s.row) <= 0.3)
        val retort = tilts.spot(s.row, s.compass, RETORT, w, h)!!
        assertTrue(off(retort, 860.0, 684.0, s.row) <= 0.3)
        assertNull(tilts.spot(s.row, s.compass, AGITATOR, w, h))
        // The same with made-up readings: 2 degrees apart is agreement, 4 isn't
        val near = MixologyStationLooks.Tilts()
        repeat(2) { near.look(s.row, s.compass, MixologyStationLooks.Read(48.0, 50.0), w, h) }
        assertNotNull(near.spot(s.row, s.compass, AGITATOR, w, h))
        val far = MixologyStationLooks.Tilts()
        repeat(2) { far.look(s.row, s.compass, MixologyStationLooks.Read(46.0, 50.0), w, h) }
        assertNull(far.spot(s.row, s.compass, AGITATOR, w, h))
        assertNotNull(far.spot(s.row, s.compass, ALEMBIC, w, h))
        assertNotNull(far.spot(s.row, s.compass, RETORT, w, h))
    }

    // A lump under the game's interface, the tool's window or its arrow isn't read: here an area laid over each
    // station in turn takes that station's reading away and leaves the other's as it was
    @Test
    fun aCoveredStationIsntRead() {
        val s = load("frames3/station_looks/north1_017s.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        val both = read(s)!!
        assertNotNull(both.alembic)
        assertNotNull(both.retort)
        // (the Alembic's big bulb is at about (887, 385) in this picture, the Retort's pot at (1470, 350))
        val overAlembic = s.skip + MixologyStations.Area(860f, 360f, 915f, 410f)
        val noAlembic = MixologyStationLooks.read(s.shot, s.row, s.compass.angle, overAlembic)!!
        assertNull(noAlembic.alembic)
        assertEquals(both.retort!!, noAlembic.retort!!, 0.001)
        val overRetort = s.skip + MixologyStations.Area(1440f, 320f, 1500f, 385f)
        val noRetort = MixologyStationLooks.read(s.shot, s.row, s.compass.angle, overRetort)!!
        assertNull(noRetort.retort)
        assertEquals(both.alembic!!, noRetort.alembic!!, 0.001)
    }

    // Facing east or west there are no lever dots and no arrow: nothing is read either
    @Test
    fun onlyFacingSouthOrNorth() {
        val s = load("frames3/station_looks/north1_017s.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        assertNull(MixologyStationLooks.read(s.shot, s.row, 180.0, s.skip))
        assertNull(MixologyStationLooks.read(s.shot, s.row, 0.0, s.skip))
    }

    // Which look places which station: its own first; failing that another station's (the three agree now that
    // the camera's turn comes from the row of funnels: before, the Retort's pot seen from across the room read the
    // tilt about 5 degrees higher than the Alembic's bulb, and neither was used for the other)
    @Test
    fun aStationIsPlacedByItsOwnLookOrElseAnothers() {
        var checked = 0
        for (path in listOf("frames3/station_looks/north1_017s.png", "frames3/station_looks/levers2_039s.png")) {
            val s = load(path)?.let { see(it) } ?: continue
            val w = s.shot.width
            val h = s.shot.height
            fun at(tilt: Double, station: MixologyStations.Station) = MixologyStations.stationSpot(s.row, s.compass.angle, tilt, station, w, h)
            // Only the Retort seen: it places all three
            val one = MixologyStationLooks.Tilts()
            repeat(2) { one.look(s.row, s.compass, MixologyStationLooks.Read(null, 50.0), w, h) }
            for (station in MixologyStations.Station.entries) {
                val spot = one.spot(s.row, s.compass, station, w, h)
                assertNotNull("$path: $station", spot)
                assertEquals(at(50.0, station).x, spot!!.x, 0.01)
            }
            // The Alembic and the Retort both seen, a degree apart: each by its own, the Agitator by the Alembic's
            val two = MixologyStationLooks.Tilts()
            repeat(2) { two.look(s.row, s.compass, MixologyStationLooks.Read(49.0, 50.0), w, h) }
            assertEquals(at(49.0, ALEMBIC).y, two.spot(s.row, s.compass, ALEMBIC, w, h)!!.y, 0.01)
            assertEquals(at(50.0, RETORT).y, two.spot(s.row, s.compass, RETORT, w, h)!!.y, 0.01)
            assertEquals(at(49.0, AGITATOR).y, two.spot(s.row, s.compass, AGITATOR, w, h)!!.y, 0.01)
            // The Agitator's own look comes first for the Agitator
            val three = MixologyStationLooks.Tilts()
            repeat(2) { three.look(s.row, s.compass, MixologyStationLooks.Read(49.0, 50.0, 48.5), w, h) }
            assertEquals(at(48.5, AGITATOR).y, three.spot(s.row, s.compass, AGITATOR, w, h)!!.y, 0.01)
            checked++
        }
        assumeTrue(checked > 0)
    }

    // The tilt goes when the camera is turned, and isn't used once the table has moved well away from where it was
    // when the station was last seen (the camera follows the character)
    @Test
    fun aTurnOrALongWalkEndsIt() {
        val s = load("frames3/station_looks/north1_017s.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        val w = s.shot.width
        val h = s.shot.height
        val tilts = tilts(s)
        assertNotNull(tilts.spot(s.row, s.compass, AGITATOR, w, h))
        // The table one spacing to the side: still used. Two spacings: not any more.
        fun moved(by: Double) = MixologyLevers.Levers(s.row.funnels.map {
            MixologyLevers.Funnel(it.x + by * s.row.spacing, it.y, it.width, it.height, it.area, it.paste)
        }, s.row.spacing)
        assertNotNull(tilts.spot(moved(1.0), s.compass, AGITATOR, w, h))
        assertNull(tilts.spot(moved(2.0), s.compass, AGITATOR, w, h))
        assertNotNull(tilts.spot(s.row, s.compass, AGITATOR, w, h))
        // No compass: no place, nothing forgotten. Turned half a degree and seen: gone for good.
        assertNull(tilts.spot(s.row, null, AGITATOR, w, h))
        tilts.compass(null)
        assertNotNull(tilts.spot(s.row, s.compass, AGITATOR, w, h))
        tilts.compass(s.compass.angle + 0.5)
        assertNull(tilts.spot(s.row, s.compass, AGITATOR, w, h))
        assertTrue(!tilts.known)
    }

    // ---- The grey circles ----

    // A drawn picture: sand-coloured floor, a grey ball of radius 30 with a thin grey pipe stuck to it, a brown
    // ball the same size, and a small grey ball. The grey ball is found, its middle and radius right to a pixel or
    // two, pipe or no pipe; the small one comes second; the brown one not at all.
    @Test
    fun theBiggestCircleInsideAGreyPatch() {
        val w = 400
        val h = 300
        val data = IntArray(w * h) { 0x84725A }
        fun ball(cx: Int, cy: Int, r: Int, rgb: Int) {
            for (y in cy - r..cy + r) for (x in cx - r..cx + r) {
                if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= r * r) data[y * w + x] = rgb
            }
        }
        ball(120, 150, 30, 0x595051)
        for (y in 60..150) for (x in 117..123) data[y * w + x] = 0x595051      // the pipe
        ball(280, 150, 30, 0x7A4A20)
        ball(300, 60, 12, 0x595051)
        val cores = MixologyStationLooks.cores(Shot(data, w, h), 0, 0, w, h, 2, 8.0)
        assertEquals(2, cores.size)
        assertEquals(120.0, cores[0].x, 2.5)
        assertEquals(150.0, cores[0].y, 2.5)
        assertEquals(30.0, cores[0].radius, 3.0)
        assertEquals(300.0, cores[1].x, 2.5)
        assertEquals(12.0, cores[1].radius, 3.0)
    }

    // The game's brightness changes the grey a great deal (the bulb is about 50 at the lowest setting and 115 at the
    // highest): the same view at both settings gives the same circles, the Alembic's big bulb and the Retort's pot
    // (by the ruler the bulb's middle is at about (1457, 555) and the pot's at (840, 573), each about 30 pixels in
    // radius)
    @Test
    fun theGamesBrightnessDoesntMatter() {
        val dark = load("brightness_min.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        val pale = load("brightness_max.png")?.let { see(it) } ?: run { assumeTrue(false); return }
        val a = MixologyStationLooks.candidates(dark.shot, dark.row, dark.compass.angle)
        val b = MixologyStationLooks.candidates(pale.shot, pale.row, pale.compass.angle)
        for (found in listOf(a, b)) {
            assertEquals(1457.0, found[0][0].x, 6.0)
            assertEquals(555.0, found[0][0].y, 6.0)
            assertEquals(30.0, found[0][0].radius, 4.0)
            assertEquals(840.0, found[1][0].x, 6.0)
            assertEquals(573.0, found[1][0].y, 6.0)
            assertEquals(30.0, found[1][0].radius, 4.0)
        }
    }

    // ---- Never the wrong station, and what it costs ----

    // Over every picture of the arrow folders, the turning footage's frames and the play_2 frames: whenever a
    // station is read, the places its tilt gives keep the room's order across the screen: the Alembic west of
    // the Agitator and the Agitator west of the Retort, a spacing and a half or more between each (they're 2.2
    // and 3.3 apart with the camera turned 18 degrees, north3_05.0s, and further when it's straight).
    @Test
    fun aStationIsNeverPlacedOnTheOtherSide() {
        val files = listOf("frames3/station_arrow", "frames3/station_looks", "frames3/stations_360", "frames3").flatMap { folder ->
            File(dir, folder).listFiles { f -> f.name.endsWith(".png") }?.map { "$folder/${it.name}" } ?: emptyList()
        } + listOf("brightness_min.png", "brightness_max.png")
        var reads = 0
        var nanos = 0L
        var timed = 0
        for (path in files.sorted()) {
            val shot = load(path) ?: continue
            val box = MixologyReader.findOrderBox(shot) ?: continue
            val row = MixologyLevers.find(shot) ?: continue
            val compass = MixologyLevers.findCompass(shot, box.scale) ?: continue
            if (!compass.exact) continue
            val start = System.nanoTime()
            val read = MixologyStationLooks.read(shot, row, compass.angle, emptyList())
            nanos += System.nanoTime() - start
            timed++
            if (read == null) continue
            // (facing north, west is to the left on the screen; facing south, to the right)
            val west = if (MixologyLevers.facingNorth(compass.angle)) -1 else 1
            for (tilt in listOfNotNull(read.alembic, read.retort, read.agitator)) {
                fun across(station: MixologyStations.Station) =
                    MixologyStations.stationSpot(row, compass.angle, tilt, station, shot.width, shot.height).x * -west / row.spacing
                assertTrue("$path: the Alembic isn't west of the Agitator", across(AGITATOR) - across(ALEMBIC) >= 1.5)
                assertTrue("$path: the Agitator isn't west of the Retort", across(RETORT) - across(AGITATOR) >= 1.5)
                reads++
            }
        }
        assumeTrue(timed > 0)
        println("MixologyStationLooks.read: ${"%.2f".format(nanos / 1e6 / timed)} ms a look on this computer ($timed pictures, $reads stations read)")
        assertTrue("only $reads stations read", reads >= 20)
    }
}

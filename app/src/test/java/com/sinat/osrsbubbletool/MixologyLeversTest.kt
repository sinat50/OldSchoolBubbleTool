package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO

// The Mastering Mixology tool's lever finder, on the owner's screenshots and frames cut from their recordings
// (Documents\Androiddev\mixology_shots, or set MIXOLOGY_SHOTS=<folder>; described in its notes.md). Skipped without
// them. Expected places were measured by hand: the game's own outline of each lever when long-pressed (levers_2.mp4),
// and the funnels read off the screenshots by eye.
class MixologyLeversTest {

    private class Shot(file: File) : LightBoxReader.PixelSource {
        private val image = ImageIO.read(file)
        override val width: Int = image.width
        override val height: Int = image.height
        override fun rgb(x: Int, y: Int) = image.getRGB(x, y) and 0xFFFFFF
    }

    private val dir = File(System.getenv("MIXOLOGY_SHOTS") ?: "C:\\Users\\Tanis\\Documents\\Androiddev\\mixology_shots")
    private val ui = 2.115   // screen pixels per game pixel on the owner's phone (MixologyReaderTest)

    private fun shot(name: String): Shot? = File(dir, name).takeIf { it.isFile }?.let { Shot(it) }

    @Test
    fun theDotsLandOnTheLevers() {
        val shot = shot("frames/levers2_12s.png")
        assumeTrue(shot != null)
        val levers = MixologyLevers.find(shot!!)
        assertNotNull(levers)
        // Each lever is straight below its funnel's hole (holes at x 1060, 1158 and 1255, measured by hand). The game
        // outlined the levers from y 385 (Mox), 350 (Aga) and 380 (Lye) down to about 455 (Lye, the one not hidden
        // by the menu), so a dot between 385 and 455 is on its lever.
        for ((i, holeX) in listOf(1060.0, 1158.0, 1255.0).withIndex()) {
            assertEquals("dot $i across", holeX, levers!!.dotX(i), 0.15 * levers.spacing)
            assertTrue("dot $i at y ${levers.dotY(i)}", levers.dotY(i) in 385.0..455.0)
        }
    }

    @Test
    fun theDotsFollowATurnedTable() {
        // The camera turned a little (levers2_41s.png: the row of funnels slants about 13°, the table with it), so
        // the levers aren't straight below the funnels any more but below and to the left. Where each lever's block
        // of the table is, measured by eye on the picture: Mox (the left wing's top and front) x up to 1110, y 470
        // to 600; Aga (the middle, between the two lever handles at x 1100 and 1310) x 1150 to 1340, y 480 to 560;
        // Lye (the right wing, with the book) x 1345 to 1545, y 515 to 615. A dot straight below the Mox funnel
        // (x 1124) would sit in the gap beside its lever.
        val shot = shot("frames/levers2_41s.png")
        assumeTrue(shot != null)
        val levers = MixologyLevers.find(shot!!)
        assertNotNull(levers)
        val across = listOf(960.0..1110.0, 1150.0..1340.0, 1345.0..1545.0)
        val down = listOf(470.0..600.0, 480.0..560.0, 515.0..615.0)
        for (i in 0..2) {
            assertTrue("dot $i at x ${levers!!.dotX(i)}", levers.dotX(i) in across[i])
            assertTrue("dot $i at y ${levers.dotY(i)}", levers.dotY(i) in down[i])
        }
    }

    @Test
    fun theMiddleFunnelBehindTheHead() {
        // Standing at the Aga lever (levers3_53.5s.png, cut from levers_3.mp4): the character's head hides most of
        // the middle funnel, so no row of three is found. Measured by eye: the green funnel's paste spans x 950-1045,
        // y 295-405; the purple one's x 1385-1475, y 310-420; a little of the pink one shows right of the head.
        val shot = shot("frames3/levers3_53.5s.png")
        assumeTrue(shot != null)
        assertNull(MixologyLevers.find(shot!!))
        // Told how the row looked with all three seen at this zoom (made from those measurements), the outer two are
        // enough: the middle is worked out halfway between them
        fun funnel(x: Double, y: Double, paste: MixologyData.Paste) = MixologyLevers.Funnel(x, y, 95, 110, 4700, paste)
        val last = MixologyLevers.Levers(listOf(funnel(998.0, 340.0, MixologyData.Paste.AGA),
            funnel(1213.0, 348.0, MixologyData.Paste.LYE), funnel(1428.0, 355.0, MixologyData.Paste.MOX)), 215.0)
        val levers = MixologyLevers.find(shot, last = last)
        assertNotNull(levers)
        assertEquals(1, levers!!.hidden)
        assertEquals(998.0, levers.funnels[0].x, 20.0)
        assertEquals(340.0, levers.funnels[0].y, 25.0)
        assertEquals(1428.0, levers.funnels[2].x, 20.0)
        assertEquals(355.0, levers.funnels[2].y, 25.0)
        // The camera zoomed since then (a row half as far apart): nothing
        val zoomed = MixologyLevers.Levers(last.funnels.map {
            MixologyLevers.Funnel(1213 + (it.x - 1213) / 2, it.y, 47, 55, 1200, it.paste) }, 107.5)
        assertNull(MixologyLevers.find(shot, last = zoomed))
    }

    @Test
    fun facingNorthTheDotsGoAboveReversed() {
        // Facing north (north1_05.5s.png, cut from north_1.mp4): the camera didn't move from 2 to 18 s, and at 4.5,
        // 6.5 and 8 s the owner long-pressed each lever. The game outlined Lye at x 1015-1110, y 515-560 (left), Aga
        // at x 1110-1225, y 545-600 (middle, behind its funnel) and Mox at x 1210-1310, y 520-560 (right).
        val shot = shot("frames3/north1_05.5s.png")
        assumeTrue(shot != null)
        val angle = MixologyLevers.needleAngle(shot!!, ui)!!
        assertTrue("needle at $angle", MixologyLevers.facingNorth(angle))
        assertFalse(MixologyLevers.facingSouth(angle))
        val levers = MixologyLevers.find(shot)
        assertNotNull(levers)
        assertEquals(listOf(MixologyData.Paste.LYE, MixologyData.Paste.AGA, MixologyData.Paste.MOX),
            (0..2).map { levers!!.lever(it, north = true) })
        val across = listOf(1015.0..1110.0, 1110.0..1225.0, 1210.0..1310.0)
        val down = listOf(515.0..560.0, 540.0..600.0, 520.0..560.0)   // (Aga's top edge allowed 5 px higher: the dot
                                                                       // is a circle, it still covers the outline)
        for (i in 0..2) {
            assertTrue("dot $i at x ${levers!!.dotX(i, true)}", levers.dotX(i, true) in across[i])
            assertTrue("dot $i at y ${levers.dotY(i, true)}", levers.dotY(i, true) in down[i])
        }
        // Turned a little (north3_05.0s.png, cut from north_3.mp4: the needle about 21° off straight up, the row of
        // funnels slanting). The owner long-pressed each lever at 4.5, 6.5 and 8 s, the camera still; the game
        // outlined Lye at x 1005-1120, y 550-575, Aga at x 1120-1255, y 550-610 and Mox at x 1220-1320, y 500-545.
        // Still counts as facing north, and each dot is on (or, for Aga, touching the top of) its lever's outline.
        val slanted = shot("frames3/north3_05.0s.png")!!
        val slantedAngle = MixologyLevers.needleAngle(slanted, ui)!!
        assertTrue("needle at $slantedAngle", MixologyLevers.facingNorth(slantedAngle))
        val turnedRow = MixologyLevers.find(slanted)!!
        val boxes = listOf(1005.0..1120.0 to 550.0..575.0, 1120.0..1255.0 to 540.0..610.0, 1220.0..1320.0 to 500.0..545.0)
        for ((i, b) in boxes.withIndex()) {
            assertTrue("dot $i at x ${turnedRow.dotX(i, true)}", turnedRow.dotX(i, true) in b.first)
            assertTrue("dot $i at y ${turnedRow.dotY(i, true)}", turnedRow.dotY(i, true) in b.second)
        }
        // Turned well away from north (north1_22.5s.png, needle about 70° off straight up): neither way round
        val turned = shot("frames3/north1_22.5s.png")!!
        val turnedAngle = MixologyLevers.needleAngle(turned, ui)!!
        assertFalse("needle at $turnedAngle", MixologyLevers.facingNorth(turnedAngle) || MixologyLevers.facingSouth(turnedAngle))
    }

    @Test
    fun findsTheFunnelsAtEveryBrightness() {
        // The same view at the game's lowest and highest brightness: the funnels' middles by eye at about x 1082,
        // 1158 and 1234 (pink, green, pink)
        for (name in listOf("brightness_min.png", "brightness_max.png")) {
            val shot = shot(name) ?: continue
            val levers = MixologyLevers.find(shot)
            assertNotNull(name, levers)
            assertEquals(listOf(MixologyData.Paste.LYE, MixologyData.Paste.AGA, MixologyData.Paste.LYE),
                levers!!.funnels.map { it.paste })
            for ((funnel, x) in levers.funnels.zip(listOf(1082.0, 1158.0, 1234.0))) assertEquals(name, x, funnel.x, 12.0)
            assertTrue(name, MixologyLevers.facingSouth(MixologyLevers.needleAngle(shot, ui)!!))
        }
    }

    @Test
    fun staysPutWhileStandingStill() {
        // Pulling levers without moving (funnels1_*.png, cut from funnels_1.mp4): the funnels change colour after
        // every pull, but the dots must stay where they are
        val files = File(dir, "frames").listFiles { f -> f.name.startsWith("funnels1_") }?.sortedBy { it.name }
        assumeTrue(files != null && files.size >= 5)
        val found = files!!.map { MixologyLevers.find(Shot(it)) }
        assertTrue("found in ${found.count { it != null }} of ${files.size}", found.all { it != null })
        val first = found.first()!!
        for ((file, levers) in files.zip(found)) {
            for (i in 0..2) {
                assertEquals(file.name, first.dotX(i), levers!!.dotX(i), 5.0)
                assertEquals(file.name, first.dotY(i), levers.dotY(i), 12.0)
            }
        }
    }

    @Test
    fun knowsWhenTheCameraIsTurned() {
        // Facing south (the compass within 25° of straight down) in every frame of the camera test except these:
        // at 34, 36 and 42 s the camera was turned well away (no dots). At 44 s it's turned 22° (by the compass's rim
        // marks, see theCompassIsReadFromItsRimMarks), which still counts: see theDotsAreRightTurned22Degrees.
        val turned = setOf("levers2_34s.png", "levers2_36s.png", "levers2_42s.png")
        val files = File(dir, "frames").listFiles { f -> f.name.startsWith("levers2_") }?.sortedBy { it.name }
        assumeTrue(files != null && files.size >= 20)
        for (file in files!!) {
            val shot = Shot(file)
            val angle = MixologyLevers.needleAngle(shot, ui)
            assertNotNull(file.name, angle)
            assertEquals("${file.name} at ${angle!!.toInt()}°", file.name !in turned, MixologyLevers.facingSouth(angle))
            // Facing south with the table on screen (the table is in view in all of these): the funnels are found
            if (file.name !in turned) assertNotNull(file.name, MixologyLevers.find(shot))
        }
    }

    // Whether a dot is inside a box measured on the picture
    private fun assertDotIn(what: String, x: Double, y: Double, across: ClosedFloatingPointRange<Double>,
                            down: ClosedFloatingPointRange<Double>) {
        assertTrue("$what at x $x", x in across)
        assertTrue("$what at y $y", y in down)
    }

    @Test
    fun emptyFunnelsAtTheStartOfAVisit() {
        // The very first screenshot (orders_1.png, facing south): before any lever was pulled the funnels are empty
        // glass. The row is found by the three grey rings the funnels stand in, each with its dark hole. The holes'
        // middles, measured by hand: (1095, 319), (1211, 327) and (1327, 334).
        val shot = shot("orders_1.png")
        assumeTrue(shot != null)
        val levers = MixologyLevers.find(shot!!)
        assertNotNull(levers)
        val rings = levers!!.rings
        assertNotNull(rings)
        val holes = listOf(1095.0 to 319.0, 1211.0 to 327.0, 1327.0 to 334.0)
        for ((ring, hole) in rings!!.zip(holes)) {
            assertEquals(hole.first, ring.x, 6.0)
            assertEquals(hole.second, ring.y, 8.0)
        }
        assertEquals(116.0, levers.spacing, 4.0)
        assertEquals(-1, levers.hidden)
        assertTrue(levers.funnels.all { it.paste == null })
        assertTrue(MixologyLevers.facingSouth(MixologyLevers.needleAngle(shot, ui)!!))
        // The dots are on the levers. Each lever's block of the table, by eye on the picture: Mox (the left wing's
        // front, left of the purple-handled lever) x 1040 to 1130, y 380 to 480; Aga (the front of the middle, above
        // the notch between the two handles) x 1137 to 1270, y 400 to 440; Lye (the right wing's front, with the
        // book) x 1280 to 1382, y 390 to 485.
        assertDotIn("Mox", levers.dotX(0), levers.dotY(0), 1040.0..1130.0, 380.0..480.0)
        assertDotIn("Aga", levers.dotX(1), levers.dotY(1), 1137.0..1270.0, 400.0..440.0)
        assertDotIn("Lye", levers.dotX(2), levers.dotY(2), 1280.0..1382.0, 390.0..485.0)
    }

    @Test
    fun emptyFunnelsAfterThePotionIsTakenOut() {
        // play1_19.5s.png (cut from play_1.mp4, facing north): the potion was just taken out of the mixing vessel,
        // so all three funnels are empty, just when the dots are needed for the next pulls. The holes' middles,
        // measured by hand: x 1081, 1178 and 1275, y 681.
        val shot = shot("frames3/play1_19.5s.png")
        assumeTrue(shot != null)
        val levers = MixologyLevers.find(shot!!)
        assertNotNull(levers)
        val rings = levers!!.rings
        assertNotNull(rings)
        for ((ring, x) in rings!!.zip(listOf(1081.0, 1178.0, 1275.0))) {
            assertEquals(x, ring.x, 6.0)
            assertEquals(681.0, ring.y, 8.0)
        }
        assertEquals(97.0, levers.spacing, 4.0)
        val compass = MixologyLevers.findCompass(shot, ui)
        assertTrue(MixologyLevers.facingNorth(compass!!.angle))
        // A second earlier (play1_18.5s.png, the camera in the same place) the owner long-pressed the Mox lever and
        // the game outlined it: x 1262 to 1314 (right of its handle), from y 582 down behind the right funnel
        // (to about 640). Lye is the same block on the other side of the middle (x 1042 to 1094); Aga, by eye, is
        // the back of the middle between the two handles: x 1130 to 1230, y 590 to 660.
        assertEquals(MixologyData.Paste.MOX, levers.lever(2, north = true))
        assertDotIn("Lye", levers.dotX(0, true), levers.dotY(0, true), 1042.0..1094.0, 582.0..640.0)
        assertDotIn("Aga", levers.dotX(1, true), levers.dotY(1, true), 1130.0..1230.0, 590.0..660.0)
        assertDotIn("Mox", levers.dotX(2, true), levers.dotY(2, true), 1262.0..1314.0, 582.0..640.0)
        // The tool's rules show them
        val dots = MixologyLevers.Dots()
        dots.lookTwice(1_000, levers, compass)
        assertTrue(dots.levers === levers)
        assertTrue(dots.north)
        assertTrue(dots.fullRow === levers)

        // In play1_18.5s.png the game's "Operate Mox lever" tag covers the left and middle rings: no row of three,
        // but the right ring is still where it was, so the dots stay
        val tag = shot("frames3/play1_18.5s.png")!!
        assertNull(MixologyLevers.find(tag))
        assertTrue(MixologyLevers.stillThere(tag, levers))
        dots.look(1_333, null, MixologyLevers.findCompass(tag, ui), MixologyLevers.stillThere(tag, levers))
        assertTrue(dots.levers === levers)
        // Against a picture with the table somewhere else (orders_1.png: floor where these rings were): gone
        shot("orders_1.png")?.let { assertFalse(MixologyLevers.stillThere(it, levers)) }

        // Small, grey, in a row and on sand colour, but not rings: the pale studs on the lab's conveyor belt
        // (levers2_42.0s.png, cut from levers_2.mp4, at x 240 to 370, y 640 to 690). They have no dark holes.
        shot("frames3/levers2_42.0s.png")?.let { assertNull(MixologyLevers.find(it)) }
    }

    @Test
    fun oneFullFunnelAfterZoomingIn() {
        // play1_39.5s.png (play_1.mp4, facing north): the owner has zoomed in and pulled Mox once. The funnels
        // show the last pastes pulled, not one per lever, so only the left one is full (purple) and the other two
        // are empty. The holes' middles, measured by hand: (1066, 624), (1191, 621) and (1315.5, 619).
        val shot = shot("frames3/play1_39.5s.png")
        assumeTrue(shot != null)
        val levers = MixologyLevers.find(shot!!)
        assertNotNull(levers)
        val rings = levers!!.rings
        assertNotNull(rings)
        val holes = listOf(1066.0 to 624.0, 1191.0 to 621.0, 1315.5 to 619.0)
        for ((ring, hole) in rings!!.zip(holes)) {
            assertEquals(hole.first, ring.x, 6.0)
            assertEquals(hole.second, ring.y, 8.0)
        }
        assertEquals(125.0, levers.spacing, 4.0)
        assertTrue(MixologyLevers.facingNorth(MixologyLevers.needleAngle(shot, ui)!!))
        // The levers are along the table's back edge (y 526 on the picture; the two lever handles' tops, at x 1117
        // and 1252, are at y 535 to 545). A dot is 21 pixels in radius here, so one whose middle is between y 505 and
        // 565 is on the edge. Across: Lye is the left wing (with the book), x 1010 to 1100; Aga the middle between
        // the handles, x 1125 to 1245; Mox the right wing, x 1265 to 1370.
        assertDotIn("Lye", levers.dotX(0, true), levers.dotY(0, true), 1010.0..1100.0, 505.0..565.0)
        assertDotIn("Aga", levers.dotX(1, true), levers.dotY(1, true), 1125.0..1245.0, 505.0..565.0)
        assertDotIn("Mox", levers.dotX(2, true), levers.dotY(2, true), 1265.0..1370.0, 505.0..565.0)
    }

    @Test
    fun twoRingsWithTheMiddleOneHidden() {
        // funnels1_24.0s_pull.png (cut from funnels_1.mp4, facing south, standing at the Aga lever): the left funnel
        // is empty, the middle one's ring is behind the character's head, the right one is full (purple). Measured
        // by hand: the left hole's middle (1001, 414), the right one's (1276, 404). Two rings alone aren't a row.
        val shot = shot("frames3/funnels1_24.0s_pull.png")
        assumeTrue(shot != null)
        assertNull(MixologyLevers.find(shot!!))
        // Told how the row looked a moment before with all three funnels full (their middles by eye in
        // funnels1_24.0s.png: (1000, 370), (1140, 362) and (1279, 358)), the two outer rings are enough
        fun funnel(x: Double, y: Double) = MixologyLevers.Funnel(x, y, 62, 72, 2000, MixologyData.Paste.MOX)
        val last = MixologyLevers.Levers(listOf(funnel(1000.0, 370.0), funnel(1140.0, 362.0), funnel(1279.0, 358.0)), 139.6)
        val levers = MixologyLevers.find(shot, last = last)
        assertNotNull(levers)
        assertEquals(1, levers!!.hidden)
        assertEquals(1001.0, levers.rings!![0].x, 6.0)
        assertEquals(414.0, levers.rings!![0].y, 8.0)
        assertEquals(1276.0, levers.rings!![2].x, 6.0)
        assertEquals(404.0, levers.rings!![2].y, 8.0)
        // The owner was long-pressing the Aga lever here, and the game outlined it: x 1061 to 1226, from y 436 down
        // past 560. The middle dot is on it.
        assertDotIn("Aga", levers.dotX(1), levers.dotY(1), 1061.0..1226.0, 436.0..560.0)
        // Each funnel's place is worked out a third of a spacing above its ring: within 12 pixels of where the full
        // funnels' middles were
        for (i in 0..2) {
            assertEquals(last.funnels[i].x, levers.funnels[i].x, 12.0)
            assertEquals(last.funnels[i].y, levers.funnels[i].y, 12.0)
        }
        // The camera zoomed since then (a row half as far apart): nothing
        val zoomed = MixologyLevers.Levers(last.funnels.map { funnel(1140 + (it.x - 1140) / 2, it.y) }, 69.8)
        assertNull(MixologyLevers.find(shot, last = zoomed))
    }

    @Test
    fun noFunnelsOutsideTheLab() {
        // The owner's Puzzle Box screenshots (Documents\Androiddev\puzzle_shots): a purple-blue sea, a colourful
        // picture, the inventory's potions. And the Gear Loadouts and Import my gear ones (loadout_shots,
        // gear_lookalike_shots): three runes side by side in the inventory or the rune pouch are small coloured
        // things in a level row, evenly spaced, but they aren't funnel-shaped. No funnels anywhere.
        var looked = 0
        for (folder in listOf("puzzle_shots", "loadout_shots", "gear_lookalike_shots")) {
            val files = File(dir.parentFile, folder).listFiles { f -> f.name.endsWith(".png") } ?: continue
            for (file in files) {
                assertNull("$folder ${file.name}", MixologyLevers.find(Shot(file)))
                looked++
            }
        }
        assumeTrue(looked > 0)
    }

    @Test
    fun itsOwnDotsArentFunnels() {
        // With the whole screen shared the tool sees its own dots: three round coloured things in a level row, evenly
        // spaced, right below the funnels. Drawn onto a real picture where the dots would go (x 999, 1139 and 1279,
        // y 530, 26 pixels across: the funnels' middles by eye, and the levers below them), in the dots' own colours
        // as they reach the screen (Android shows the tool's drawing window at 80%, so a fifth of the table shows
        // through), they must change nothing: the same funnels are found, not the dots.
        val shot = shot("frames/funnels1_02.0s.png")
        assumeTrue(shot != null)
        val real = shot!!
        val dotted = object : LightBoxReader.PixelSource {
            override val width = real.width
            override val height = real.height
            override fun rgb(x: Int, y: Int): Int {
                for ((i, dotX) in intArrayOf(999, 1139, 1279).withIndex()) {
                    if ((x - dotX) * (x - dotX) + (y - 530) * (y - 530) <= 13 * 13) {
                        val dot = MixologyData.Paste.entries[i].colour
                        val under = real.rgb(x, y)
                        var mixed = 0
                        for (shift in intArrayOf(16, 8, 0)) {
                            mixed = mixed or (((dot shr shift and 0xFF) * 8 + (under shr shift and 0xFF) * 2) / 10 shl shift)
                        }
                        return mixed
                    }
                }
                return real.rgb(x, y)
            }
        }
        val plain = MixologyLevers.find(real)!!
        val withDots = MixologyLevers.find(dotted)
        assertNotNull(withDots)
        for (i in 0..2) {
            assertEquals(plain.funnels[i].x, withDots!!.funnels[i].x, 1.0)
            assertEquals(plain.funnels[i].y, withDots.funnels[i].y, 1.0)
        }
        // Facing north the dots are drawn just above the funnels: they mustn't change the funnels found either
        val north = shot("frames3/north1_05.5s.png")
        if (north != null) {
            val plainNorth = MixologyLevers.find(north)!!
            val spots = (0..2).map { plainNorth.dotX(it, true).toInt() to plainNorth.dotY(it, true).toInt() }
            val r = (plainNorth.spacing * 0.17).toInt()
            val dottedNorth = object : LightBoxReader.PixelSource {
                override val width = north.width
                override val height = north.height
                override fun rgb(x: Int, y: Int): Int {
                    for ((i, spot) in spots.withIndex()) {
                        val (dx, dy) = spot
                        if ((x - dx) * (x - dx) + (y - dy) * (y - dy) <= r * r) {
                            val dot = plainNorth.lever(i, true).colour
                            val under = north.rgb(x, y)
                            var mixed = 0
                            for (shift in intArrayOf(16, 8, 0)) {
                                mixed = mixed or (((dot shr shift and 0xFF) * 8 + (under shr shift and 0xFF) * 2) / 10 shl shift)
                            }
                            return mixed
                        }
                    }
                    return north.rgb(x, y)
                }
            }
            val withNorthDots = MixologyLevers.find(dottedNorth)
            assertNotNull(withNorthDots)
            for (i in 0..2) {
                assertEquals(plainNorth.funnels[i].x, withNorthDots!!.funnels[i].x, 1.0)
                assertEquals(plainNorth.funnels[i].y, withNorthDots.funnels[i].y, 1.0)
            }
        }
        // And on their own (the funnels painted over in the table's colour) the dots aren't found as funnels
        val onlyDots = object : LightBoxReader.PixelSource {
            override val width = real.width
            override val height = real.height
            override fun rgb(x: Int, y: Int): Int = if (y < 450) 0x957B5E else dotted.rgb(x, y)
        }
        assertNull(MixologyLevers.find(onlyDots))
    }

    @Test
    fun searchingNearTheLastPlaceFindsTheSame() {
        // The tool looks near where the funnels were last time first (quicker); that must find the same row
        val shot = shot("brightness_max.png")
        assumeTrue(shot != null)
        val all = MixologyLevers.find(shot!!)!!
        val s = all.spacing
        val near = MixologyLevers.find(shot, (all.dotX(0) - 2 * s).toInt(), (all.funnels[0].y - 2 * s).toInt(),
            (all.dotX(2) + 2 * s).toInt(), (all.funnels[2].y + 2 * s).toInt())!!
        for (i in 0..2) assertEquals(all.dotX(i), near.dotX(i), 1.0)
        assertFalse(near.spacing <= 0)
    }
    @Test
    fun theDotsAreRightTurned22Degrees() {
        // levers2_44s.png: the camera turned 22° from south and tilted low. Where each lever's block of the table is,
        // measured by eye on the picture: Mox (the left wing's front) x 1005 to 1100, y 510 to 610; Aga (the notch in
        // the middle, between the two lever handles) x 1110 to 1220, y 490 to 545; Lye (the right wing's front, below
        // the book) x 1225 to 1315, y 470 to 550. The dots must be shown (facing south) and be on them.
        val shot = shot("frames/levers2_44s.png")
        assumeTrue(shot != null)
        assertTrue(MixologyLevers.facingSouth(MixologyLevers.needleAngle(shot!!, ui)!!))
        val levers = MixologyLevers.find(shot)!!
        val across = listOf(1005.0..1100.0, 1110.0..1220.0, 1225.0..1315.0)
        val down = listOf(510.0..610.0, 490.0..545.0, 470.0..550.0)
        for (i in 0..2) {
            assertTrue("dot $i at x ${levers.dotX(i)}", levers.dotX(i) in across[i])
            assertTrue("dot $i at y ${levers.dotY(i)}", levers.dotY(i) in down[i])
        }
    }

    @Test
    fun theCompassIsReadFromItsRimMarks() {
        // The compass's needle is a rough little picture, but the dial has small red marks on its rim at east and
        // west, which turn with it. Their middles were measured on each picture (the red pixels' middle, to half a
        // pixel); the needle points square to the line from the west mark to the east one. Picture, west mark, east
        // mark, and facing north (needle up) or south (needle down):
        class Marks(val name: String, val westX: Double, val westY: Double, val eastX: Double, val eastY: Double,
                    val north: Boolean)
        val shots = listOf(
            // (the needle alone read 104° here, 14° out: the row of funnels is dead level in this picture)
            Marks("frames/levers2_12s.png", 1928.5, 64.5, 1985.5, 66.5, false),
            Marks("brightness_max.png", 1926.5, 65.7, 1982.9, 63.9, false),
            Marks("orders_1.png", 1928.3, 61.6, 1982.8, 68.0, false),
            // (the needle alone read 119° here, which used to count as turned away: no dots)
            Marks("frames3/levers2_46.5s.png", 1928.5, 56.0, 1983.5, 74.5, false),
            Marks("frames/levers2_44s.png", 1929.2, 76.4, 1981.5, 56.0, false),
            // (facing north the marks swap sides: the one on the left is the east one)
            Marks("frames3/north1_05.5s.png", 1985.5, 66.5, 1928.8, 66.4, true),
            Marks("frames3/north3_05.0s.png", 1985.5, 58.5, 1930.8, 76.4, true))
        var checked = 0
        for (m in shots) {
            val shot = shot(m.name) ?: continue
            // The line from the mark on the left to the one on the right slants by this much; the needle with it
            val leftX = minOf(m.westX, m.eastX)
            val leftY = if (m.westX < m.eastX) m.westY else m.eastY
            val rightX = maxOf(m.westX, m.eastX)
            val rightY = if (m.westX < m.eastX) m.eastY else m.westY
            val slant = Math.toDegrees(Math.atan2(rightY - leftY, rightX - leftX))
            val expected = (if (m.north) -90.0 else 90.0) + slant
            val compass = MixologyLevers.findCompass(shot, ui)
            assertNotNull(m.name, compass)
            assertTrue(m.name, compass!!.exact)
            assertEquals(m.name, expected, compass.angle, 1.5)
            // The dial's middle is halfway between the two marks
            assertEquals(m.name, (m.westX + m.eastX) / 2, compass.x, 4.0)
            assertEquals(m.name, (m.westY + m.eastY) / 2, compass.y, 4.0)
            // Looked for only where it was, it reads the same
            assertEquals(m.name, compass.angle, MixologyLevers.findCompass(shot, ui, compass)!!.angle, 0.01)
            checked++
        }
        assumeTrue(checked > 0)
        // 18.6° from south in levers2_46.5s.png: facing south
        shot("frames3/levers2_46.5s.png")?.let { assertTrue(MixologyLevers.facingSouth(MixologyLevers.needleAngle(it, ui)!!)) }
        // Told to look where there's no compass (the middle of the screen): nothing
        shot("orders_1.png")?.let {
            assertNull(MixologyLevers.findCompass(it, ui, MixologyLevers.Compass(90.0, 1170.0, 540.0, true)))
        }
    }

    // A made-up row of funnels (for the tests of the dots' rules)
    private fun row(x: Double, y: Double, spacing: Double, hidden: Int = -1) = MixologyLevers.Levers(
        (0..2).map { MixologyLevers.Funnel(x + it * spacing, y, 64, 80, 3000, MixologyData.Paste.LYE) }, spacing, hidden)

    private fun compass(angle: Double) = MixologyLevers.Compass(angle, 1956.0, 66.0, true)

    // A row found on two looks running, a tenth of a second apart, the second at `now`. A row far from where the
    // dots were (or with none shown) is only shown once the look before found it too (see
    // aRowSeenOnceFarAwayDoesntMoveTheDots), so this is how a row's dots first come up.
    private fun MixologyLevers.Dots.lookTwice(now: Long, row: MixologyLevers.Levers?, compass: MixologyLevers.Compass?) {
        look(now - 100, row, compass, stillThere = false)
        look(now, row, compass, stillThere = false)
    }

    @Test
    fun facingNorthShowsDots() {
        // The rules alone: a row with the needle straight up (-90°) shows its dots the north way
        val dots = MixologyLevers.Dots()
        dots.lookTwice(1_000, row(1000.0, 500.0, 100.0), compass(-90.0))
        assertNotNull(dots.levers)
        assertTrue(dots.north)
        // And on a real picture, facing north (north1_05.5s.png: the levers were long-pressed there, see
        // facingNorthTheDotsGoAboveReversed)
        val shot = shot("frames3/north1_05.5s.png")
        assumeTrue(shot != null)
        val real = MixologyLevers.Dots()
        real.lookTwice(1_000, MixologyLevers.find(shot!!), MixologyLevers.findCompass(shot, ui))
        assertNotNull(real.levers)
        assertTrue(real.north)
        // Straight down: the south way
        dots.lookTwice(5_000, row(1000.0, 500.0, 100.0), compass(90.0))
        assertNotNull(dots.levers)
        assertFalse(dots.north)
    }

    @Test
    fun noDotsWithTheCameraTurnedOrNoCompass() {
        val dots = MixologyLevers.Dots()
        val row = row(1000.0, 500.0, 100.0)
        dots.lookTwice(1_000, row, compass(90.0))
        assertNotNull(dots.levers)
        // The funnels are still found, but the camera faces east now: the dots go at once, not a while later
        dots.look(1_333, row, compass(0.0), stillThere = false)
        assertNull(dots.levers)
        dots.look(1_666, row, compass(90.0), stillThere = false)
        assertNotNull(dots.levers)
        // The compass can't be found (something covers it): no telling south from north, so no dots
        dots.look(2_000, row, null, stillThere = false)
        assertNull(dots.levers)
    }

    @Test
    fun theDotsStayThroughADrainOnly() {
        val row = row(1000.0, 500.0, 100.0)
        // No row for a moment with the funnels still there and the compass unmoved: the dots stay...
        val dots = MixologyLevers.Dots()
        dots.lookTwice(1_000, row, compass(88.0))
        dots.look(1_333, null, compass(88.5), stillThere = true)
        assertNotNull(dots.levers)
        dots.look(2_900, null, compass(88.0), stillThere = true)
        assertNotNull(dots.levers)
        // ...but not for longer than 2 seconds
        dots.look(3_100, null, compass(88.0), stillThere = true)
        assertNull(dots.levers)
        // The funnels gone from where they were (the camera moved, the table left the screen): no dots at once
        dots.lookTwice(5_000, row, compass(88.0))
        dots.look(5_333, null, compass(88.0), stillThere = false)
        assertNull(dots.levers)
        // The camera turned on the spot, even if there's paste where a funnel was: no dots at once
        dots.lookTwice(6_000, row, compass(88.0))
        dots.look(6_333, null, compass(110.0), stillThere = true)
        assertNull(dots.levers)
    }

    @Test
    fun theRememberedRowIsForOneSideOfTheTable() {
        val dots = MixologyLevers.Dots()
        val full = row(1000.0, 500.0, 100.0)
        dots.lookTwice(1_000, full, compass(90.0))
        assertTrue(dots.fullRow === full)
        // A row with its middle funnel only worked out isn't remembered as a full one
        dots.look(1_333, row(1010.0, 500.0, 100.0, hidden = 1), compass(90.0), stillThere = false)
        assertTrue(dots.fullRow === full)
        // The order box gone for a moment: no dots, the row still remembered
        dots.hide()
        assertNull(dots.levers)
        assertTrue(dots.fullRow === full)
        // Seen from the north side: the south side's row is forgotten (a row with a worked-out middle doesn't
        // replace it)
        dots.lookTwice(9_000, row(1010.0, 500.0, 100.0, hidden = 1), compass(-90.0))
        assertNull(dots.fullRow)
    }

    @Test
    fun drainingFunnelsAreStillThere() {
        // funnels1_20.0s.png and, half a second later, funnels1_20.5s.png (frames3): a lever was just pulled and all
        // three funnels are draining. By eye in the second picture: the glass funnels are almost empty, with the last
        // pink paste in their stems (x 1000, 1140 and 1278, y 390 to 415; the right one from y 345). No row of three
        // full funnels, but the dots must stay: the funnels haven't moved.
        val before = shot("frames/funnels1_20.0s.png")
        val drain = shot("frames3/funnels1_20.5s.png")
        assumeTrue(before != null && drain != null)
        val row = MixologyLevers.find(before!!)!!
        // (the draining row is found by its rings now, with the dots where they were: the stems end in the rings,
        // at x 1000, 1140 and 1278)
        val draining = MixologyLevers.find(drain!!)
        assertNotNull(draining)
        assertNotNull(draining!!.rings)
        for ((i, x) in listOf(1000.0, 1140.0, 1278.0).withIndex()) {
            assertEquals(x, draining.rings!![i].x, 6.0)
            assertEquals(row.dotX(i), draining.dotX(i), 8.0)
            assertEquals(row.dotY(i), draining.dotY(i), 14.0)
        }
        // And if a look misses the row, the last of the paste says the funnels are still there
        assertTrue(MixologyLevers.stillThere(drain, row))
        val dots = MixologyLevers.Dots()
        dots.lookTwice(1_000, row, MixologyLevers.findCompass(before, ui))
        dots.look(1_500, null, MixologyLevers.findCompass(drain, ui), MixologyLevers.stillThere(drain, row))
        assertTrue(dots.levers === row)
        // The same row against a picture where the table is somewhere else (levers2_26s.png: the funnels are at the
        // top of the screen there, y about 160, and there's floor where this row was): not there
        shot("frames/levers2_26s.png")?.let { assertFalse(MixologyLevers.stillThere(it, row)) }
    }

    @Test
    fun theDotsGoWhenTheCameraTurnsOnTheSpot() {
        // levers3_19.5s.png and, half a second later, levers3_20.0s.png (frames3): the owner swung the camera round
        // (the compass's rim marks: 19° from south, then 47°). In the second picture the row of funnels slants too
        // much to be found, and the table has turned under where the dots were. They mustn't stay there.
        val before = shot("frames3/levers3_19.5s.png")
        val after = shot("frames3/levers3_20.0s.png")
        assumeTrue(before != null && after != null)
        val row = MixologyLevers.find(before!!)!!
        val dots = MixologyLevers.Dots()
        dots.lookTwice(1_000, row, MixologyLevers.findCompass(before, ui))
        assertNotNull(dots.levers)
        assertNull(MixologyLevers.find(after!!))
        dots.look(1_500, null, MixologyLevers.findCompass(after, ui), MixologyLevers.stillThere(after, row))
        assertNull(dots.levers)
    }

    // ---------------- The game's tooltip taken for the row of rings (16 November 2026) ----------------
    // Pictures in frames3/dots_tooltip (described in notes.md), cut from the owner's recordings at the time in the name.

    private fun tip(name: String) = shot("frames3/dots_tooltip/$name")

    @Test
    fun theTooltipIsntTakenForTheRings() {
        // When a station is pressed the game writes what it does ("Homogenise-potion Agitator") in grey letters on
        // a see-through grey box. Three of its round letters used to pass for the table's rings now and then, and
        // the dots jumped onto the words for a look.
        // By eye, play10_36.75s.png (facing north): the tooltip near the top, its box from y 256 to 307 (x 1202 to
        // 1603); the table lower down, the dark holes of its three rings at x 1037, 1154 and 1271, y 716.
        // play7_128.95s.png: "Crystalise-potion Alembic" over the middle, its box from y 511 to 559 (x 1076 to
        // 1447); the holes at x 1342, 1503 and 1663, y 800.
        val p10 = tip("play10_36.75s.png")
        val p7 = tip("play7_128.95s.png")
        assumeTrue(p10 != null && p7 != null)
        for ((shot, holes, y) in listOf(Triple(p10!!, listOf(1037.0, 1154.0, 1271.0), 716.0),
                                        Triple(p7!!, listOf(1342.0, 1503.0, 1663.0), 800.0))) {
            val row = MixologyLevers.find(shot)
            assertNotNull(row)
            assertNotNull(row!!.rings)
            for (i in 0..2) {
                assertEquals(holes[i], row.rings!![i].x, 10.0)
                assertEquals(y, row.rings[i].y, 15.0)
            }
        }
        // The tooltip with the table not on the screen at all (the character at a station, the camera on it):
        // play4_5.10s.png and play4_13.60s.png ("Homogenise-potion Agitator" over the floor and the Agitator),
        // play6_55.30s.png (the same words): no row
        for (name in listOf("play4_5.10s.png", "play4_13.60s.png", "play6_55.30s.png")) {
            tip(name)?.let { assertNull(name, MixologyLevers.find(it)) }
        }
    }

    @Test
    fun aRowSeenOnceFarAwayDoesntMoveTheDots() {
        // The rules alone, with made-up rows: the table's row (middle funnel at 1100, 500), and a row where a
        // tooltip's letters might be taken for one: far up the screen and half the size (in play10_36.75s.png the
        // tooltip's letters were 4 spacings from the table)
        val table = row(1000.0, 500.0, 100.0)
        val letters = row(1300.0, 100.0, 55.0)
        val dots = MixologyLevers.Dots()
        // The table's first look: nothing yet (one look alone can be something else); on the second, its dots
        dots.look(1_000, table, compass(90.0), stillThere = false)
        assertNull(dots.levers)
        assertTrue(dots.waiting)
        dots.look(1_100, table, compass(90.0), stillThere = false)
        assertTrue(dots.levers === table)
        assertFalse(dots.waiting)
        // The letters for one look: the dots stay on the table, and the next look finds the table again
        dots.look(1_433, letters, compass(90.0), stillThere = false)
        assertTrue(dots.levers === table)
        val next = row(1004.0, 502.0, 100.0)
        dots.look(1_766, next, compass(90.0), stillThere = false)
        assertTrue(dots.levers === next)
        // With no dots on screen, the letters on one look alone show nothing
        val fresh = MixologyLevers.Dots()
        fresh.look(1_000, letters, compass(90.0), stillThere = false)
        assertNull(fresh.levers)
        fresh.look(1_333, null, compass(90.0), stillThere = false)
        assertNull(fresh.levers)
        // The dots gone for a look (the funnels hidden by the game's menu, say): back at once where they were
        dots.look(2_100, null, compass(90.0), stillThere = false)
        assertNull(dots.levers)
        val back = row(1010.0, 505.0, 100.0)
        dots.look(2_200, back, compass(90.0), stillThere = false)
        assertTrue(dots.levers === back)
        // Somewhere far from them (the camera swung round to the table, say), on two looks running: the dots go
        // there on the second
        val far = row(400.0, 300.0, 100.0)
        dots.look(2_300, far, compass(90.0), stillThere = false)
        assertTrue(dots.levers === back)
        val farNext = row(410.0, 305.0, 100.0)
        dots.look(2_400, farNext, compass(90.0), stillThere = false)
        assertTrue(dots.levers === farNext)
    }

    @Test
    fun theDotsKeepUpWhileWalking() {
        // play3_97.70s.png, 98.00s, 98.30s and 98.40s (facing north): the owner walking, the camera following the
        // character, so the table slides down the screen. The dark holes of its three rings, by eye:
        //   97.70 s: x 1260, 1347, 1435, y 745      98.00 s: x 1262, 1352, 1445, y 816
        //   98.30 s: x 1263, 1360, 1458, y 907      98.40 s: x 1263, 1362, 1462, y 938
        // Looks a third of a second apart (the tool's pace when the table starts to move: from 97.70 to 98.00 s it
        // moved 0.8 of a spacing, from 98.00 to 98.30 s nearly one) and then a tenth (its pace while it moves): the
        // dots must be on the table in each picture, not a look behind.
        class Seen(val name: String, val at: Long, val holes: List<Double>, val y: Double)
        val seen = listOf(Seen("play3_97.70s.png", 1_000, listOf(1260.0, 1347.0, 1435.0), 745.0),
            Seen("play3_98.00s.png", 1_333, listOf(1262.0, 1352.0, 1445.0), 816.0),
            Seen("play3_98.30s.png", 1_666, listOf(1263.0, 1360.0, 1458.0), 907.0),
            Seen("play3_98.40s.png", 1_766, listOf(1263.0, 1362.0, 1462.0), 938.0))
        val shots = seen.map { tip(it.name) }
        assumeTrue(shots.all { it != null })
        val dots = MixologyLevers.Dots()
        val first = shots[0]!!
        dots.lookTwice(seen[0].at, MixologyLevers.find(first), MixologyLevers.findCompass(first, ui))
        for ((k, s) in seen.withIndex()) {
            val shot = shots[k]!!
            if (k > 0) dots.look(s.at, MixologyLevers.find(shot, last = dots.fullRow), MixologyLevers.findCompass(shot, ui), stillThere = false)
            val l = dots.levers
            assertNotNull(s.name, l)
            assertTrue(dots.north)
            for (i in 0..2) {
                // (a funnel stands straight above its ring, RING_BELOW spacings up)
                assertEquals(s.name, s.holes[i], l!!.funnels[i].x, 12.0)
                assertEquals(s.name, s.y, l.funnels[i].y + MixologyLevers.RING_BELOW * l.spacing, 15.0)
            }
        }
    }
}

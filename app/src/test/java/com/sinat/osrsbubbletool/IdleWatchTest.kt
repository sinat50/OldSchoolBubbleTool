package com.sinat.osrsbubbletool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// The Idle Notifier's rules, on made-up screens the size of the owner's phone (2340 x 1080) with a look every second:
// a still screen, the character's box changing (working), and the whole screen changing (the camera turned).
// Expected answers worked out by hand from the rules in IdleWatch (8 seconds of looks, half quiet = idle, one alert
// per idle stretch, three quarters busy = worked again).
class IdleWatchTest {

    private val w = 2340
    private val h = 1080
    private val box = IdleWatch.watchedBox(w, h)

    // A screen of plain grey, with the character's box painted in `boxColour` and the rest in `restColour`
    private fun screen(boxColour: Int, restColour: Int = 0x606060) = object : LightBoxReader.PixelSource {
        override val width = w
        override val height = h
        override fun rgb(x: Int, y: Int) =
            if (x >= box[0] && x < box[2] && y >= box[1] && y < box[3]) boxColour else restColour
    }

    // Working: the box flips between two colours every look. Standing: nothing changes.
    private val still = screen(0x808080)
    private val workA = screen(0x808080)
    private val workB = screen(0xF0F0F0)

    // Runs the looks (one a second) and gives back what each one said: "alert", "idle", or "-"
    private fun run(watch: IdleWatch, from: Int, screens: List<LightBoxReader.PixelSource?>): List<String> =
        screens.mapIndexed { i, s ->
            val r = if (s == null) watch.lookSame((from + i).toDouble()) else watch.look(s, (from + i).toDouble())
            when { r.alert -> "alert"; r.idle -> "idle"; else -> "-" }
        }

    private fun working(seconds: Int) = List(seconds) { if (it % 2 == 0) workA else workB }

    @Test
    fun theGuideBoxIsUprightAndAboveTheMiddle() {
        val g = IdleWatch.guideBox(w, h)
        val tall = g[3] - g[1]
        assertEquals(162.0, tall.toDouble(), 1.0)           // 15% of 1080
        assertEquals(0.75, (g[2] - g[0]) / tall.toDouble(), 0.02)
        assertTrue(g[1] < h / 2 && g[3] > h / 2)           // from a little below the middle upwards
        assertTrue(box[0] < g[0] && box[1] < g[1] && box[2] > g[2] && box[3] > g[3])   // the watched box holds it
    }

    @Test
    fun workingNeverAlerts() {
        val said = run(IdleWatch(), 0, working(60))
        assertFalse(said.contains("alert"))
        assertFalse(said.contains("idle"))
    }

    @Test
    fun standingStillAlertsOnceAfterAboutEightSeconds() {
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val said = run(watch, 20, List(40) { still })
        assertEquals(1, said.count { it == "alert" })
        // half of the 8 seconds of looks quiet: about 4 to 8 seconds after stopping
        val at = said.indexOf("alert")
        assertTrue("alerted after $at s", at in 3..9)
        assertTrue(said.drop(at + 1).all { it == "idle" })   // stays idle, but no second buzz
    }

    @Test
    fun aLongerWaitPutsTheAlertOffByThatMuch() {
        // the Wait setting: 15 s adds 10 seconds after the detector first says idle (made-up screens: a look every
        // second, so exactly 10 looks later than with no extra wait)
        fun alertAt(extra: Double): Int {
            val watch = IdleWatch()
            watch.extraWait = extra
            run(watch, 0, working(20))
            val said = run(watch, 20, List(40) { still })
            assertEquals(1, said.count { it == "alert" })
            return said.indexOf("alert")
        }
        assertEquals(alertAt(0.0) + 10, alertAt(10.0))
        assertEquals(alertAt(0.0) + 3, alertAt(3.0))
    }

    @Test
    fun workingDuringTheLongerWaitCancelsIt() {
        // idle for a few seconds, then back to work before the extra 10 seconds are up: no alert at all
        val watch = IdleWatch()
        watch.extraWait = 10.0
        run(watch, 0, working(20))
        val said = run(watch, 20, List(10) { still } + working(20))
        assertFalse(said.contains("alert"))
    }

    @Test
    fun anUnchangedScreenCountsAsStill() {
        // the capture has no new picture when nothing on screen changed at all
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val said = run(watch, 20, List(30) { null })
        assertEquals(1, said.count { it == "alert" })
    }

    @Test
    fun workingAgainAllowsTheNextAlert() {
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val first = run(watch, 20, List(20) { still })
        val back = run(watch, 40, working(20))
        val second = run(watch, 60, List(20) { still })
        assertEquals(1, first.count { it == "alert" })
        assertFalse(back.contains("alert"))
        assertEquals(1, second.count { it == "alert" })
    }

    @Test
    fun aMomentOfMovementDoesntAllowAnotherAlert() {
        // someone walking past for two seconds isn't "worked again"
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val said = run(watch, 20, List(15) { still } + listOf(workB, workA) + List(20) { still })
        assertEquals(1, said.count { it == "alert" })
    }

    @Test
    fun turningTheCameraDoesntAllowAnotherAlert() {
        // standing, alerted; the camera turns (the whole screen changes) while still standing: no second buzz
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val turned = screen(0x808080, 0xA0A0A0)
        val said = run(watch, 20, List(15) { still } + listOf(turned, still) + List(20) { still })
        assertEquals(1, said.count { it == "alert" })
    }

    @Test
    fun leavingTheGameAndComingBackDoesntAllowAnotherAlert() {
        // standing, alerted; you check another app (or the screen goes off) and come back, still standing: no second
        // buzz. Pressing Start again does allow one.
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val first = run(watch, 20, List(15) { still })
        watch.forget()
        val back = run(watch, 40, List(20) { still })
        watch.start()
        val restarted = run(watch, 60, List(20) { still })
        assertEquals(1, first.count { it == "alert" })
        assertFalse(back.contains("alert"))
        assertEquals(1, restarted.count { it == "alert" })
    }

    @Test
    fun turningTheCameraPutsOffAnAlert() {
        // a camera turn means you're holding the phone: the 8 seconds start again
        val watch = IdleWatch()
        run(watch, 0, working(20))
        val turned = screen(0x808080, 0xA0A0A0)
        val said = run(watch, 20, List(3) { still } + listOf(turned, still) + List(20) { still })
        val at = said.indexOf("alert")
        assertTrue("alerted $at s after stopping", at >= 5 + 6)   // at least about 6 s of looks after the turn
    }

    // Smoke on your own square (someone lit a fire under you): work that swaps colours in the box (green and red
    // here), then the box only getting lighter and darker, as see-through grey smoke does to what's behind it
    private val colourWork = List(20) { if (it % 2 == 0) screen(0x408020) else screen(0x903030) }
    private fun smoke(seconds: Int) = List(seconds) { if (it % 2 == 0) screen(0x505050) else screen(0x909090) }

    @Test
    fun smokeOverYouAfterColourfulWorkStillAlerts() {
        val watch = IdleWatch()
        assertFalse(run(watch, 0, colourWork).contains("alert"))
        val said = run(watch, 20, smoke(40))
        assertEquals(1, said.count { it == "alert" })
        // the first smoky look still differs in colour from the last look of work; from the next one on they're quiet,
        // and 5 of the 9 looks in 8 seconds are quiet at the sixth
        assertEquals(5, said.indexOf("alert"))
        // working again in the smoke, then stopping again: a second alert, and none while working
        val back = run(watch, 60, colourWork)
        val again = run(watch, 80, smoke(20))
        assertFalse(back.contains("alert"))
        assertEquals(1, again.count { it == "alert" })
    }

    @Test
    fun lighterAndDarkerAloneIsStillWorkIfThatsAllYourWorkEverShowed() {
        // work that never changed the box's colours (grey on grey) isn't judged by colour: it never alerts
        // (workingNeverAlerts is the same thing from the start; here after a stretch of standing and an alert)
        val watch = IdleWatch()
        run(watch, 0, working(20))
        run(watch, 20, List(20) { still })
        val said = run(watch, 40, working(60))
        assertFalse(said.contains("alert"))
    }

    @Test
    fun aCameraMoveForgetsWhatWasLearnedAboutColour() {
        // colourful work, the camera turns, then the box only gets lighter and darker: from the new view nothing says
        // that's not your work, so no alert
        val watch = IdleWatch()
        run(watch, 0, colourWork)
        val turned = screen(0x408020, 0xA0A0A0)
        val said = run(watch, 20, listOf(turned, screen(0x408020)) + smoke(40))
        assertFalse(said.contains("alert"))
    }

    @Test
    fun aDarkenedScreenStillShowsWork() {
        // the phone darkens the picture after a while without touches: work is still seen as work
        fun dim(c: Int) = ((c shr 16 and 0xFF) / 10 shl 16) or ((c shr 8 and 0xFF) / 10 shl 8) or ((c and 0xFF) / 10)
        val a = screen(dim(0x808080), dim(0x606060))
        val b = screen(dim(0xF0F0F0), dim(0x606060))
        val said = run(IdleWatch(), 0, List(40) { if (it % 2 == 0) a else b })
        assertFalse(said.contains("alert"))
    }
}

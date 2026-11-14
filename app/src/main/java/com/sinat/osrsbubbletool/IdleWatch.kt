package com.sinat.osrsbubbletool

import kotlin.math.abs

// The Idle Notifier's detector: notices when your character stops working, from the screen alone. No Android parts,
// so it's tested on the PC (IdleWatchTest). Worked out on recordings first: tools/idle-detection/detect_idle.py
// (run_guide) and .scratch/afk-alert/spec.md, where every number below was measured.
//
// The zoom guide: before you start, you zoom until your character is as tall as a box the app shows (guideBox).
// The game's camera always aims just above your feet, at the middle of the screen, so from then on the detector
// knows how big your character is and where, and nothing has to be searched for. Once a second (look):
//
// 1. Your character's box (a bit bigger than the guide, to hold the character seen from the side or from above,
//    with the swing of its tool) is compared with how it looked a second earlier. A pixel "changed" if its colour
//    moved enough, and only SOLID change counts: a changed pixel whose neighbours all changed too. Standing still,
//    the outline shimmers (the slow standing sway) but the body doesn't; any work moves the body. Standing sway
//    changes 0-4% of the box, stringing bows (only the hands move) 5-35%, chopping or mining more.
// 2. A thin camera check: a few thousand pixels spread around the box. If nearly a third changed at once, the camera
//    turned or zoomed, or you walked: you're holding the phone, so you're active, and the looks so far are forgotten.
// 3. A look is quiet when under 3% of the box changed. You're idle once at least half the looks over the last
//    8 seconds were quiet (people walking past make the other half busy).
// 4. One alert for each idle stretch: it goes off the first time you're idle, and can't go off again until the box
//    was busy in three quarters of the looks over 8 seconds (you worked again). A camera move alone doesn't count:
//    you'd get a second buzz after nudging the camera while still standing there.
//
// It reads only the box (about 28,000 pixels at the guide's size) and the thin check (about 4,000), not the whole
// screen. The XP counter isn't used (the owner's rule: some skills go a long time between XP drops).
// guideShare: the character's height as a share of the screen's height. The app always uses the guide's (GUIDE_SHARE);
// tests replaying old recordings, which weren't made with the guide, give the height the character really was.
class IdleWatch(private val guideShare: Double = GUIDE_SHARE) {

    companion object {
        const val GUIDE_SHARE = 0.15         // the guide box's height as a share of the screen's height
        const val PIXEL_CHANGED = 40         // red + green + blue difference that counts as a change
        const val COMPARE_AFTER = 0.8        // seconds: compare with the latest look at least this old (about 1 s)
        const val CAMERA_MOVED = 0.30        // this share of the thin check changed at once = the camera moved
        const val QUIET = 3.0                // % of the box changed: under this, a look is quiet
        const val WINDOW = 8.0               // seconds of looks considered
        const val QUIET_MOST = 0.5           // idle when this share of the looks in WINDOW were quiet
        const val WORKED_AGAIN = 0.75        // the next alert waits until this share of the looks in WINDOW were busy
        const val THIN_STEP = 16             // the camera check reads one spot every 16 pixels each way
        const val NORMAL_BRIGHTNESS = 70.0   // average brightness (0-255) of an ordinary game picture
        const val MOST_GAIN = 12.0           // a darkened picture is brightened back at most this much

        // The guide box drawn for you (left, top, right, bottom), on a screen this big. Your character should be as
        // tall as it: an upright box three quarters as wide as tall, from a little below the middle upwards (the
        // camera aims a little above the feet: at this zoom they're 0.23 x the height below the middle, measured on
        // the owner's phone, 13 November 2026).
        fun guideBox(width: Int, height: Int): IntArray {
            val g = GUIDE_SHARE * height
            val cx = width / 2.0
            val cy = height / 2.0
            return intArrayOf((cx - 0.375 * g).toInt(), (cy - 0.77 * g).toInt(), (cx + 0.375 * g).toInt(), (cy + 0.23 * g).toInt())
        }

        // The box that's watched: 0.9 x the guide's height wide and 1.25 x tall, from 0.9 x the height above the
        // middle to 0.35 x below (room for feet when you zoom in a little too far)
        fun watchedBox(width: Int, height: Int, share: Double = GUIDE_SHARE): IntArray {
            val g = share * height
            val cx = width / 2.0
            val cy = height / 2.0
            return intArrayOf((cx - 0.45 * g).toInt().coerceAtLeast(1), (cy - 0.9 * g).toInt().coerceAtLeast(1),
                (cx + 0.45 * g).toInt().coerceAtMost(width - 1), (cy + 0.35 * g).toInt().coerceAtMost(height - 1))
        }
    }

    // What one look found
    class Result(
        val idle: Boolean,      // you're idle (judged afresh each look)
        val alert: Boolean,     // buzz now: the first idle look since you last worked
        val reading: Double,    // % of the box that changed (-1: not measured this look)
        val note: String        // "camera" (the camera moved), "same" (an unchanged screen, left out), "" or "starting"
    )

    // One look's pixels: the box (with a pixel of margin all round, for the solid-change test) and the thin check,
    // each colour channel already brightened if the picture was darkened
    private class Look(val time: Double, val box: ShortArray, val thin: ShortArray)

    private var screenW = 0
    private var screenH = 0
    private var box = IntArray(4)          // the watched box (left, top, right, bottom), right and bottom not included
    private var thinX = IntArray(0)        // where the camera check reads (outside the box)
    private var thinY = IntArray(0)
    private var older: Look? = null        // the look compared with
    private var last: Look? = null         // the most recent look (an unchanged screen is a copy of it)
    private var skippedLast = false
    private val looks = ArrayDeque<Pair<Double, Boolean>>()   // (time, quiet?) since the last camera move
    private val work = ArrayDeque<Pair<Double, Boolean>>()    // (time, quiet?), camera moves or not: "worked again"
    private var armed = true               // may the alert go off? False from an alert until you've worked again

    // Memory made once and reused every look (battery): three sets of box and camera-check pixels (the look being
    // taken, `last` and `older` hold at most the other two), and the solid-change map
    private val boxBuffers = arrayOfNulls<ShortArray>(3)
    private val thinBuffers = arrayOfNulls<ShortArray>(3)
    private var moved = BooleanArray(0)

    // Watching starts (Start pressed): everything forgotten, and the alert may go off
    fun start() {
        forget()
        armed = true
    }

    // Forgets the looks so far (the screen changed size, you went back to the game, the screen came back on). An
    // alert that already went off stays gone until you've worked again: still standing there isn't a new stretch.
    fun forget() {
        older = null
        last = null
        skippedLast = false
        looks.clear()
        work.clear()
    }

    // One look at the screen, `time` in seconds
    fun look(screen: LightBoxReader.PixelSource, time: Double): Result {
        if (screen.width != screenW || screen.height != screenH) setScreen(screen.width, screen.height)
        val gain = gainFor(screen)
        val free = freeBuffer()
        val boxPixels = boxBuffers[free]!!
        val thin = thinBuffers[free]!!
        var i = 0
        for (y in box[1] - 1 until box[3] + 1) for (x in box[0] - 1 until box[2] + 1) {
            i = put(boxPixels, i, screen.rgb(x, y), gain)
        }
        i = 0
        for (k in thinX.indices) i = put(thin, i, averageOf4(screen, thinX[k], thinY[k]), gain)
        return judge(Look(time, boxPixels, thin))
    }

    // The screen hasn't changed at all since the last look (the capture had no new picture)
    fun lookSame(time: Double): Result {
        val l = last ?: return Result(false, false, -1.0, "starting")
        return judge(Look(time, l.box, l.thin))
    }

    private fun setScreen(width: Int, height: Int) {
        screenW = width
        screenH = height
        box = watchedBox(width, height, guideShare)
        // The camera check: one spot every THIN_STEP pixels of the area around the middle (1200 x 900 on a 1080-tall
        // screen), leaving out the box and a step around it
        val k = height / 1080.0
        val left = maxOf(1, (width / 2 - 600 * k).toInt())
        val right = minOf(width - 2, (width / 2 + 600 * k).toInt())
        val top = maxOf(1, (height / 2 - 450 * k).toInt())
        val bottom = minOf(height - 2, (height / 2 + 450 * k).toInt())
        val xs = ArrayList<Int>()
        val ys = ArrayList<Int>()
        for (y in top until bottom step THIN_STEP) for (x in left until right step THIN_STEP) {
            val inBox = x >= box[0] - THIN_STEP && x < box[2] + THIN_STEP && y >= box[1] - THIN_STEP && y < box[3] + THIN_STEP
            if (!inBox) { xs.add(x); ys.add(y) }
        }
        thinX = xs.toIntArray()
        thinY = ys.toIntArray()
        val bw = box[2] - box[0] + 2
        val bh = box[3] - box[1] + 2
        for (n in 0 until 3) { boxBuffers[n] = ShortArray(bw * bh * 3); thinBuffers[n] = ShortArray(thinX.size * 3) }
        moved = BooleanArray(bw * bh)
        forget()
    }

    // Which of the three sets of pixels no kept look is using
    private fun freeBuffer(): Int {
        for (n in 0 until 3) {
            val b = boxBuffers[n]
            if (b !== last?.box && b !== older?.box) return n
        }
        return 0   // (can't happen: two kept looks use at most two of the three)
    }

    // The phone darkens the whole picture after a while without touches (to a tenth, in one recording), which would
    // shrink every change: a clearly darkened picture is brightened back before comparing
    private fun gainFor(screen: LightBoxReader.PixelSource): Double {
        var sum = 0L
        var n = 0
        val step = maxOf(16, screen.height / 34)
        for (y in step / 2 until screen.height step step) for (x in step / 2 until screen.width step step) {
            val c = screen.rgb(x, y)
            sum += ((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)
            n++
        }
        val mean = if (n == 0) NORMAL_BRIGHTNESS else sum / (3.0 * n)
        val gain = (NORMAL_BRIGHTNESS / maxOf(1.0, mean)).coerceIn(1.0, MOST_GAIN)
        return if (gain > 1.5) gain else 1.0   // ordinary dark scenes stay as they are
    }

    private fun put(into: ShortArray, at: Int, c: Int, gain: Double): Int {
        into[at] = (((c shr 16) and 0xFF) * gain).toInt().toShort()
        into[at + 1] = (((c shr 8) and 0xFF) * gain).toInt().toShort()
        into[at + 2] = ((c and 0xFF) * gain).toInt().toShort()
        return at + 3
    }

    // A 2 x 2 average (the recordings were checked on half-size pictures)
    private fun averageOf4(s: LightBoxReader.PixelSource, x: Int, y: Int): Int {
        val a = s.rgb(x, y); val b = s.rgb(x + 1, y); val c = s.rgb(x, y + 1); val d = s.rgb(x + 1, y + 1)
        val red = ((a shr 16) and 0xFF) + ((b shr 16) and 0xFF) + ((c shr 16) and 0xFF) + ((d shr 16) and 0xFF)
        val green = ((a shr 8) and 0xFF) + ((b shr 8) and 0xFF) + ((c shr 8) and 0xFF) + ((d shr 8) and 0xFF)
        val blue = (a and 0xFF) + (b and 0xFF) + (c and 0xFF) + (d and 0xFF)
        return ((red / 4) shl 16) or ((green / 4) shl 8) or (blue / 4)
    }

    private fun changed(a: ShortArray, b: ShortArray, pixel: Int): Boolean {
        val i = pixel * 3
        return abs(a[i] - b[i]) + abs(a[i + 1] - b[i + 1]) + abs(a[i + 2] - b[i + 2]) > PIXEL_CHANGED
    }

    private fun judge(look: Look): Result {
        val t = look.time
        val prev = last
        // A look identical to the last one (the game not redrawing) says nothing, so it's left out; two in a row do
        // say something: the screen really is still
        if (prev != null) {
            var same = (0 until thinX.size).none { changed(look.thin, prev.thin, it) }
            if (same) {
                var n = 0
                for (p in 0 until look.box.size / 3) if (changed(look.box, prev.box, p) && ++n > 2) { same = false; break }
            }
            if (same && !skippedLast) {
                skippedLast = true
                return Result(isIdleNow(t), false, -1.0, "same")
            }
        }
        skippedLast = false
        last = look
        val o = older
        if (o == null || t - o.time < COMPARE_AFTER) {
            if (o == null) older = look
            return Result(false, false, -1.0, "starting")
        }
        older = look

        // 2. The camera check
        val moved = (0 until thinX.size).count { changed(look.thin, o.thin, it) }
        if (thinX.isNotEmpty() && moved > CAMERA_MOVED * thinX.size) {
            looks.clear()   // you're here: an alert that hasn't gone off yet has to wait again
            return Result(false, false, -1.0, "camera")
        }

        // 1. and 3. How much of the box changed, solid change only
        val reading = solidChange(look.box, o.box)
        val quiet = reading < QUIET
        looks.addLast(t to quiet)
        work.addLast(t to quiet)
        for (kept in arrayOf(looks, work)) while (kept.first().first < t - WINDOW) kept.removeFirst()
        val idle = isIdleNow(t)

        // 4. One alert for each idle stretch. Looks during a camera move aren't counted, but they don't wipe out the
        // work seen before them either (turning the camera every few seconds while fishing is still fishing)
        if (work.last().first - work.first().first >= WINDOW * 0.8 && work.size >= 5 &&
            work.count { !it.second } >= WORKED_AGAIN * work.size) armed = true
        val alert = idle && armed
        if (alert) armed = false
        return Result(idle, alert, reading, "")
    }

    private fun isIdleNow(t: Double): Boolean {
        if (looks.isEmpty() || looks.last().first - looks.first().first < WINDOW * 0.8) return false
        if (t - looks.last().first > WINDOW) return false
        return looks.count { it.second } >= QUIET_MOST * looks.size
    }

    // The share (%) of the box whose pixel changed along with its 8 neighbours
    private fun solidChange(now: ShortArray, before: ShortArray): Double {
        val bw = box[2] - box[0] + 2
        val bh = box[3] - box[1] + 2
        for (p in 0 until bw * bh) moved[p] = changed(now, before, p)
        var firm = 0
        for (y in 1 until bh - 1) for (x in 1 until bw - 1) {
            var all = true
            loop@ for (dy in -1..1) for (dx in -1..1) if (!moved[(y + dy) * bw + x + dx]) { all = false; break@loop }
            if (all) firm++
        }
        return 100.0 * firm / ((bw - 2) * (bh - 2))
    }
}

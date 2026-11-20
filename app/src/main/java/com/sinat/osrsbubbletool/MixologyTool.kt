package com.sinat.osrsbubbletool

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.View
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit

// Mastering Mixology: while you're in the Mastering Mixology lab, writes each order's recipe after its name in the
// game's Potion Orders box ("Megalite liquid (MLL)") and puts a coloured dot on each lever (blue M = Mox, green
// A = Aga, pink L = Lye), and writes each potion's recipe over it in your inventory ("bag tags"), like the Mastering
// Mixology RuneLite plugin by Hexagon. It reads the screen (MixologyReader for the orders, MixologyLevers for the
// levers, MixologyBag for the inventory) and only draws: nothing is ever tapped. The plan and every
// measurement: .scratch/mixology/spec.md.
class MixologyTool(
    private val context: Context,
    private val windowManager: WindowManager,
    private val capture: CaptureManager,
    private val hideWindow: () -> Unit
) {
    companion object {
        const val LOOK_MS = 333L            // 3 looks a second in the lab, so the dots follow the table
        const val AWAY_MS = 2_000L          // no order box: one look every 2 seconds, the capture resting in between
        const val AWAY_AFTER_MS = 5_000L    // ...once there's been none for this long
        const val WAKE_AHEAD_MS = 200L      // the capture starts again this long before a look after resting
        const val RETRY_MS = 100L           // no fresh picture just after waking: try again this much later,
        const val RETRIES = 3               // at most this many times
        const val COUNT_FROM_S = 3.0        // the Alembic's countdown shows for the last 3 seconds before its cue
        const val FOLLOW_EVERY_MS = 100L    // while the table moves on the screen: a look this often (for the dots)
        const val FOLLOW_MS = 1_000L        // ...until it has been still this long
        const val CONFIRM_MS = 200L         // a row not shown yet (it needs a second look, MixologyLevers.Dots): the
                                            // next look comes FOLLOW_EVERY_MS later, not LOOK_MS
        const val MOVED_BY = 0.06           // the row moved more than this many spacings = the table is moving
        const val COMPASS_EVERY_MS = 1_000L // the compass is searched for at most this often when it isn't where it
                                            // was (that costs as much as a look; reading it where it was costs little)
        const val SAME_ROW_MS = 500L        // the station arrow needs the funnels found on two looks running: the
        const val SAME_ROW = 0.1            // look before no longer ago than this, its spacing within a tenth
    }

    private val ui = Ui(context)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("mixology", Context.MODE_PRIVATE)
    private val overlay = MixologyOverlay(context, windowManager)
    private val shapes by lazy {
        MixologyReader.readShapes(context.assets.open("mixology/names.tsv").bufferedReader().use { it.readText() })
    }

    // Each potion's inventory icon in brief (colour squares, no pictures), read the first time the bag is looked at
    private val potions by lazy {
        MixologyBag.readSummaries(context.assets.open("mixology/potions.tsv").bufferedReader().use { it.readText() })
    }

    private var running = false
    private var destroyed = false
    private var note: String? = null        // why it stopped, shown under the buttons
    private var status = ""                 // what it sees now
    private var holder: LinearLayout? = null

    // What the looks have found so far
    private var box: MixologyReader.Box? = null          // the order box, last seen here
    private var boxSeenAt = 0L
    private var orders: List<MixologyReader.Order?> = emptyList()
    private val dots = MixologyLevers.Dots()             // which lever dots are shown, and its rules
    private var compass: MixologyLevers.Compass? = null  // the compass, where it was last found
    // The bag (MixologyBag.Watch) is read on the worker thread (see "The worker thread" below); these are what its
    // last look found, kept here on the main thread: the potions tagged, whether the inventory was open, and where
    // the side panel is
    private var bagSeen = BagSeen(emptyList(), false, null)
    private var compassSearchedAt = 0L
    // The stations (MixologyStations): which station each order needs, the progress bar of the station working now,
    // and what the bag says is done and where to go next
    private var stations: List<MixologyStations.Station?> = emptyList()
    private var stationWatch: MixologyStations.Watch? = null  // made for the box's scale (screen pixels per game pixel)
    private var watchScale = 0.0
    private var bar: MixologyStations.Bar? = null             // the working station's bar, last seen here
    private var working = false                               // a station is working: look fast (Watch.FAST_MS)
    private var tapNow = false                                // the quick-time moment is on: show the signal
    private var workingAt: MixologyStations.Station? = null   // the station working now, as far as the plan says
    private val alembic = MixologyStations.AlembicClock()     // when to tap the Alembic
    private val used = ArrayList<MixologyStations.Station>()  // the stations that finished a potion for these orders
    private var usedFor: List<String?> = emptyList()          // ...these orders (their names)
    private val made = ArrayList<MixologyStations.Made>()     // the potions finished for these orders, and where
    private var inStation: String? = null                     // the potion handed to the station working now
    // How many finished potions of that kind the bag held when the station started (null = the bag wasn't seen then):
    // one more in the bag with the bar gone = the station has finished, at once (MixologyStations.Watch, backInBag)
    private var finishedAtStart: Int? = null
    // A station has just finished: the next look comes quickly and is a full one, reading the bag, so the arrow to
    // the next station needn't wait for the next third of a second and the bag's next once-a-second read
    private var bagDue = false
    private var namedStation: MixologyStations.Station? = null // that station, as its bar's place says (null = unsure)
    // The bag as last seen, or, with the inventory closed since, as worked out from the stations' work (a potion
    // handed over leaves it, a finished one comes back): so "done!" and the arrow stay with the inventory closed,
    // like the plugin's
    private var lastBag: List<MixologyStations.Carried>? = null
    // Right after a station finished, the next order is picked from the top of the orders (the plugin's
    // tryHighlightNextStation), until the bag's unfinished potions change (then from the bag again, as the plugin's
    // inventory rule does)
    private var byOrder = false
    private var byOrderBag: List<String>? = null
    private var plan: MixologyStations.Plan? = null           // "done!" and the next order (null = bag not seen)
    // The arrow over the station to use next: the camera's tilt (read from how the three stations look,
    // MixologyStationLooks, or else learned from a working station's bar), the station a potion was just handed to,
    // the row of funnels of the look before, and the arrow itself (null = none)
    private val lookTilts = MixologyStationLooks.Tilts()
    private val sights = MixologyStationLooks.Sights()       // the same with the lever table off the screen
    private var arrowBySight = false                         // the arrow now showing was placed that way
    private val tilt = MixologyStations.Tilt()
    private val sent = MixologyStations.Sent()
    private var arrowRow: MixologyLevers.Levers? = null
    private var arrowRowAt = 0L
    private var arrow: MixologyStations.Arrow? = null
    private var screenW = 0
    private var screenH = 0
    private var fullLookAt = 0L                              // the last look at everything (not only the bar)
    private var dotsMovingUntil = 0L                         // the table is moving: follow the dots fast until then
    private var lastRowSeen: MixologyLevers.Levers? = null   // the last row of funnels found, and when
    private var lastRowAt = 0L
    private var resting = false
    private var retries = 0

    private val captureStopped: () -> Unit = {
        if (!destroyed && running) stop("Screen capture is off. Tap Start to turn it back on.")
    }

    init {
        capture.addStopListener(captureStopped)
    }

    private var showRecipes: Boolean
        get() = prefs.getBoolean("recipes", true)
        set(v) = prefs.edit { putBoolean("recipes", v) }
    private var showDots: Boolean
        get() = prefs.getBoolean("dots", true)
        set(v) = prefs.edit { putBoolean("dots", v) }
    private var showTags: Boolean
        get() = prefs.getBoolean("tags", true)
        set(v) = prefs.edit { putBoolean("tags", v) }
    private var showStations: Boolean   // "done!", the next order's arrow and the quick-time frame
        get() = prefs.getBoolean("stations", true)
        set(v) = prefs.edit { putBoolean("stations", v) }
    private var buzz: Boolean           // a short buzz when the quick-time moment starts
        get() = prefs.getBoolean("buzz", true)
        set(v) = prefs.edit { putBoolean("buzz", v) }
    private var showArrow: Boolean      // the flashing arrow over the station to use next (needs "Stations" on)
        get() = prefs.getBoolean("arrow", true)
        set(v) = prefs.edit { putBoolean("arrow", v) }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Window contents ----------------

    fun buildView(): View {
        holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        show()
        return ScrollView(context).apply {
            setBackgroundColor(Palette.SURFACE)
            addView(holder)
        }
    }

    // (three buttons share a row, so they drop the usual button's smallest width, and long words can wrap)
    private fun button(label: String, style: Ui.ButtonStyle = Ui.ButtonStyle.PLAIN, onClick: () -> Unit) =
        ui.button(label, style, onClick).apply {
            minWidth = 0
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

    // An on/off button sharing a row with two others (the same narrow fit as button)
    private fun toggle(label: String, on: Boolean, onClick: () -> Unit) = ui.toggle(label, on, onClick).apply {
        setPadding(dp(6), dp(4), dp(6), dp(4))
    }

    private fun text(words: String, size: Float = 12f) = ui.text(words, size).apply {
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(4) }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        isBaselineAligned = false
        views.forEachIndexed { i, v ->
            // (all as tall as the tallest, when one's words wrap)
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f).apply {
                if (i > 0) leftMargin = dp(6)
            })
        }
    }

    private var introOpen = false   // the explanation under the ? is showing

    private fun show() {
        val h = holder ?: return
        h.removeAllViews()
        if (running) {
            h.addView(text(status.ifEmpty { "Starting..." }, 13f), full())
        } else {
            // the one thing to remember, with the full explanation behind the ? (so it doesn't push the buttons down)
            h.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.note("Face the camera north or south."), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("?", 15f, Palette.TEXT, 700).apply {
                    gravity = Gravity.CENTER
                    contentDescription = if (introOpen) "Hide instructions" else "Show instructions"
                    ui.press(this, Palette.RAISED, 8, if (introOpen) Palette.ACCENT else null)
                    setOnClickListener { introOpen = !introOpen; show() }
                }, LinearLayout.LayoutParams(dp(Ui.TAP_SIZE), dp(Ui.TAP_SIZE)).apply { leftMargin = dp(4) })
            }, full())
            if (introOpen) h.addView(ui.card {
                addView(ui.text("Shows each order's recipe, marks the levers and your potions, points to the next " +
                    "station, and tells you when to tap the Alembic or Agitator.", 12f))
            }, full())
        }
        // what it shows: each one ticked in gold while it's on, faded while it's off
        h.addView(row(
            toggle("Recipes", showRecipes) { showRecipes = !showRecipes; redraw(); show() },
            toggle("Levers", showDots) { showDots = !showDots; redraw(); show() },
            toggle("Tags", showTags) { showTags = !showTags; redraw(); show() }
        ), full())
        h.addView(row(
            toggle("Stations", showStations) { showStations = !showStations; redraw(); show() },
            toggle("Arrow", showArrow) { showArrow = !showArrow; arrow = null; redraw(); show() },
            toggle("Vibrate", buzz) { buzz = !buzz; show() }
        ), full())
        h.addView(if (running) button("Stop", Ui.ButtonStyle.DANGER) { stop(null) }
                  else button("Start", Ui.ButtonStyle.GOLD) { start() }, full())
        // (a problem gets the warning sign; "Waiting for permission" is only news)
        note?.let { h.addView(if (it.startsWith("Waiting")) text(it) else ui.banner(it), full()) }
    }

    private fun setStatus(words: String) {
        if (words == status) return
        status = words
        show()
    }

    // ---------------- Watching ----------------

    private fun start() {
        note = null
        if (!capture.isActive) {
            note = "Waiting for screen-capture permission..."
            show()
            capture.request { ok ->
                if (destroyed) return@request
                if (ok) start() else { note = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        running = true
        status = ""
        worker?.quit()
        worker = Worker()   // a fresh thread for the screen reading, with a fresh bag and glow
        forget()
        boxSeenAt = SystemClock.uptimeMillis()   // look quickly at first
        capture.matchScreenSize()
        val (w, h) = fullScreenSize(windowManager)
        screenW = w
        screenH = h
        overlay.show(w, h)
        show()
        hideWindow()   // out of the way; tap the bubble to see what it sees, or to stop
        handler.removeCallbacks(lookRunnable)
        handler.removeCallbacks(wakeRunnable)
        retries = 0
        handler.postDelayed(lookRunnable, LOOK_MS)
    }

    private fun stop(message: String?) {
        running = false
        worker?.quit()   // (a look under way finishes on it and is thrown away)
        worker = null
        handler.removeCallbacks(lookRunnable)
        handler.removeCallbacks(wakeRunnable)
        if (resting) { capture.wake(); resting = false }   // (leave it as other tools expect it)
        overlay.hide()
        forget()
        note = message
        show()
    }

    // Forgets everything seen (a new start, the phone turned)
    private fun forget() {
        box = null
        dotsMovingUntil = 0L
        lastRowSeen = null
        orders = emptyList()
        dots.forget()
        compass = null
        compassSearchedAt = 0L
        // (the bag's own memory is on the worker: forgotten at the start of its next look; a look under way now is
        // thrown away when it's back)
        bagSeen = BagSeen(emptyList(), false, null)
        bagHideDue = false
        bagForgetDue = true
        lookGen++
        stationsGone()
        stations = emptyList()
        used.clear()
        usedFor = emptyList()
        made.clear()
        byOrder = false
        tilt.forget()
        lookTilts.forget()
        sights.forget()
        sent.reset()
        arrowRow = null
    }

    // No station to follow now (the box is gone, the game hidden): its bar, signal and plan go
    private fun stationsGone() {
        stationWatch?.reset()
        glowResetDue = true   // (the glow is on the worker: reset at the start of its next look)
        alembic.reset()
        bar = null
        working = false
        tapNow = false
        workingAt = null
        inStation = null
        finishedAtStart = null
        bagDue = false
        namedStation = null
        plan = null
        lastBag = null
        sent.unseen()
        arrow = null
    }

    // ---------------- The worker thread ----------------
    //
    // The screen is read on a thread of its own, so the bubble, the windows and the drawing over the game stay smooth
    // (reading it on the main thread took about a sixth of that thread's time, November 2026). Who does what:
    //  - the main thread: the timing of the looks, copying the newest screen picture (ScreenCapturer.copy: one
    //    quick block copy), and everything the tool keeps and draws (box, dots, stations, plan, arrow, overlay);
    //  - the worker thread: the readers (MixologyReader, MixologyLevers, MixologyStations, MixologyStationLooks) on
    //    that copy, plus the two things that keep their own memory from look to look while reading the screen: the
    //    bag (MixologyBag.Watch) and the green round a station's bar (MixologyStations.Glow). Only the worker ever
    //    touches those two; the main thread keeps what the bag's last look found (`bagSeen`) and asks for them to
    //    be cleared with `bagHideDue`, `bagForgetDue` and `glowResetDue`, done at the start of the next look.
    // What a look needs from the tool (the box and bar last seen, the dots shown, the settings...) is handed over
    // as fixed values when the look is sent, and what it found comes back to the main thread, where it's used as
    // before. Only one look is ever under way: the next one is timed from the end of the last (as it always was),
    // and the copy isn't written again until the worker has finished with it.
    private class Worker {
        val thread = HandlerThread("Mixology looks", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
        val handler = Handler(thread.looper)
        val store = ScreenCapturer.FrameStore()   // the copy of the screen (main thread writes, worker reads)
        val bag = MixologyBag.Watch()              // worker thread only
        val glow = MixologyStations.Glow()         // worker thread only
        var busy = false                           // main thread only: a look is under way on the worker

        // Stops the thread once the look it may be doing is finished (its result is then thrown away)
        fun quit() { thread.quitSafely() }
    }

    // What the bag's last look found, handed back to the main thread (the lists are never changed afterwards)
    private class BagSeen(val tags: List<MixologyBag.Tag>, val open: Boolean, val panel: PanelFinder.Box?) {
        constructor(watch: MixologyBag.Watch) : this(watch.tags, watch.open, watch.panel)
    }

    private var worker: Worker? = null
    private var lookGen = 0               // goes up when everything is forgotten: a look sent before that is ignored
    private var bagHideDue = false        // for the worker's next look: hide the bag's tags first,
    private var bagForgetDue = false      // or forget the bag entirely,
    private var glowResetDue = false      // and start the green round the bar afresh

    // Sends a look to the worker: `read` runs there on the copied screen, `use` back on the main thread with what
    // it found (not if the tool stopped, or forgot everything, meanwhile). The next look is timed when it's back.
    private fun <T : Any> send(w: Worker, read: () -> T, use: (T) -> Unit) {
        val gen = lookGen
        val hide = bagHideDue
        val forgetBag = bagForgetDue
        val resetGlow = glowResetDue
        bagHideDue = false; bagForgetDue = false; glowResetDue = false
        w.busy = true
        w.handler.post {
            if (forgetBag) w.bag.forget() else if (hide) w.bag.hide()
            if (resetGlow) w.glow.reset()
            // (an odd picture that trips a reader: skip this look rather than crash, as ScreenCapturer.grab does)
            val found = try { read() } catch (e: RuntimeException) { null }
            handler.post {
                if (worker !== w) return@post   // stopped meanwhile (a new start has its own worker and looks)
                w.busy = false
                if (!running || destroyed) return@post
                if (found != null && gen == lookGen) use(found)
                afterLook(true)
            }
        }
    }

    private val lookRunnable = Runnable {
        if (running && !destroyed) {
            when (lookOnce()) {
                Began.NO_PICTURE -> afterLook(false)
                Began.DONE -> afterLook(true)
                Began.SENT -> {}   // the worker has it: the next look is timed when it's back (send)
            }
        }
    }

    // How a look began: no new picture, done already (nothing to read), or sent to the worker
    private enum class Began { NO_PICTURE, DONE, SENT }

    // Times the next look, after one (`looked` = false when there was no new picture)
    private fun afterLook(looked: Boolean) {
        val now = SystemClock.uptimeMillis()
        val away = now - boxSeenAt > AWAY_AFTER_MS
        if (!looked && away && retries < RETRIES) {
            // just woke and no fresh picture yet: try again shortly
            retries++
            handler.postDelayed(lookRunnable, RETRY_MS)
            return
        }
        retries = 0
        if (away) {
            // Not in the lab (or the game hidden, or the screen off): look now and then, resting in between
            capture.rest()
            resting = true
            handler.postDelayed(wakeRunnable, AWAY_MS - WAKE_AHEAD_MS)
            handler.postDelayed(lookRunnable, AWAY_MS)
        } else {
            // While a station works, about 12 looks a second (only at its bar, mostly): the Alembic's moment
            // lasts 0.6 s
            // While the table moves on the screen, 10 looks a second, so the lever dots keep up
            // Just after a station finished: the next (full) look soon, for the arrow to the next station
            handler.postDelayed(lookRunnable, when {
                working || bagDue -> MixologyStations.Watch.FAST_MS
                showDots && SystemClock.uptimeMillis() < dotsMovingUntil -> FOLLOW_EVERY_MS
                else -> LOOK_MS
            })
        }
    }

    private val wakeRunnable = Runnable {
        if (running && !destroyed && !capture.gameHidden && screenOn()) { capture.wake(); resting = false }
    }

    private fun screenOn() = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    // Nothing to tag now: the tags go at once here, and in the bag's own memory on the worker's next look
    private fun hideBag() {
        bagSeen = BagSeen(emptyList(), false, bagSeen.panel)
        bagHideDue = true
    }

    // One look: copies the screen and sends the reading to the worker (SENT), or, with the game not on screen,
    // clears what's drawn (DONE). NO_PICTURE when there was no new picture (nothing changed on screen, or the capture
    // only just woke).
    private fun lookOnce(): Began {
        val w = worker ?: return Began.DONE
        if (w.busy) return Began.SENT   // (can't happen: the next look is only timed once the last is back)
        if (capture.gameHidden || !screenOn()) {
            // (the recipes too: they'd be left drawn over whatever is on the screen instead of the game)
            box = null
            orders = emptyList()
            dots.hide()
            hideBag()
            stationsGone()
            redraw()
            setStatus("The game isn't on screen.")
            return Began.DONE
        }
        val now = SystemClock.uptimeMillis()
        val b0 = box
        val followDots = showDots && now < dotsMovingUntil
        if ((working || followDots) && !bagDue && b0 != null && now - fullLookAt < LOOK_MS) {
            // A fast look between the full ones: while a station works, only its bar and the green round it; while
            // the table moves on the screen, only the funnels (near where they were) and the compass (where it was)
            val last = bar
            val near = compass
            val isWorking = working
            // The bar gone: is the station's potion back in the bag, finished? (Then it's done now.) The bag is
            // only read on these few looks at the end of a station's work.
            val bagCheck = isWorking && finishedAtStart != null && showStations
            val fullRow = dots.fullRow
            val shown = dots.levers
            val frame = capture.copy(w.store) ?: return Began.NO_PICTURE
            send(w, read = {
                // (on the worker thread: only the copy, the values above and the worker's own bag and glow)
                val found = if (isWorking) MixologyStations.findBar(frame, b0.scale, last) else null
                val green = if (isWorking) w.glow.look(frame, found, now) else 0
                if (bagCheck && found == null) w.bag.look(frame, now, potions, soon = true)
                var row: MixologyLevers.Levers? = null
                var still = false
                var needle: MixologyLevers.Compass? = null
                if (followDots) {
                    // The whole screen, as in a full look (2 ms on the PC). A search only round the last row costs a
                    // third of that but found rows of rings the whole-screen search doesn't (24 of 650 frames of
                    // play_2, some doubtful), and only the whole-screen search is checked against the owner's
                    // pictures from outside the lab.
                    row = MixologyLevers.find(frame, last = fullRow)
                    if (row == null) still = shown?.let { MixologyLevers.stillThere(frame, it) } ?: false
                    if ((row != null || still) && near != null) needle = MixologyLevers.findCompass(frame, b0.scale, near)
                }
                FastLook(found, green, row, still, needle, BagSeen(w.bag))
            }, use = { fast ->
                // (back on the main thread)
                bagSeen = fast.bag
                if (isWorking) stationLook(now, b0.scale, fast.bar, fast.green, backInBag(fast.bar))
                if (followDots) {
                    // (no compass where it was: the full look searches for it; the dots go meanwhile, as in a full look)
                    if (fast.levers != null || fast.stillThere) compass = fast.compass
                    dots.look(now, fast.levers, fast.compass, fast.stillThere)
                    noteRowMoved(now, fast.levers)
                    // The station arrow follows the table too
                    if (showStations && showArrow) placeArrow(now, fast.levers, fast.compass, null, false, null, false, null)
                }
                redraw()
            })
            return Began.SENT
        }
        val wantDots = showDots
        val wantTags = showTags
        val wantStations = showStations
        val wantArrow = wantStations && showArrow
        val tiltKnown = tilt.degrees != null || lookTilts.known
        // With the lever table off the screen, the stations are looked for by themselves (MixologyStationLooks.
        // readWithoutTable): only when an arrow is wanted, that is a station to go to next and none working
        val noNext = plan?.nextStation == null
        val wantFree = wantArrow && !working && !noNext
        val lastBar = bar
        val lastBox = box
        val near = compass
        val fullRow = dots.fullRow
        val shown = dots.levers
        // What decides whether the bag is read on this look (`soon`, below)
        val isWorking = working
        val due = bagDue
        val waitingForPotion = finishedAtStart != null
        // Where the tool's own window and arrow are now: the stations aren't looked for under them
        val window = if (wantArrow) ownWindow() else null
        val arrowNow = arrow
        val searchCompass = now - compassSearchedAt >= COMPASS_EVERY_MS
        fullLookAt = now
        val frame = capture.copy(w.store) ?: return Began.NO_PICTURE
        send(w, read = {
            // (on the worker thread: only the copy, the values above and the worker's own bag and glow)
            // The order box: near where it was first, then anywhere on the left
            val b = MixologyReader.findOrderBox(frame, lastBox)
            var read: List<MixologyReader.Order?> = emptyList()
            var row: MixologyLevers.Levers? = null
            var needle: MixologyLevers.Compass? = null
            var searched = false
            var still = false
            var st: List<MixologyStations.Station?> = emptyList()
            var found: MixologyStations.Bar? = null
            var green = 0
            var looked = false
            var looks: MixologyStationLooks.Read? = null
            var lookedFree = false
            var sight: MixologyStationLooks.Sight? = null
            if (b != null) {
                // The names, on every look (it's a small part of a look's work): after a delivery the orders change,
                // and the old recipes mustn't stay beside the new names
                read = MixologyReader.readOrders(frame, b, shapes)
                // The levers: only looked for in the lab (with the order box on screen), never elsewhere
                // (also for the arrow over the next station, which is placed from the funnels)
                if (wantDots || wantArrow) {
                    row = MixologyLevers.find(frame, last = fullRow)
                    // No row, but dots on screen: they only stay if the funnels (or their rings) are still where
                    // they were
                    if (row == null) still = shown?.let { MixologyLevers.stillThere(frame, it) } ?: false
                    // (with the camera's tilt known, the compass is read on every look: a turn makes the tilt
                    // stale, table on screen or not)
                    if (row != null || still || (wantArrow && tiltKnown) || wantFree) {
                        // The compass, on every look that needs it: where it was last time, or (now and then)
                        // anywhere in the top right
                        needle = near?.let { MixologyLevers.findCompass(frame, b.scale, it) }
                        if (needle == null && searchCompass && (row != null || still || wantFree)) {
                            needle = MixologyLevers.findCompass(frame, b.scale)
                            searched = true
                        }
                    }
                }
                // The stations: each order's station icon, and a working station's bar with the green round it
                if (wantStations) {
                    st = MixologyStations.stationsOf(frame, b, read)
                    found = MixologyStations.findBar(frame, b.scale, lastBar)
                    green = w.glow.look(frame, found, now)
                }
                // The bag: only in the lab too. Its own rules keep this cheap (MixologyBag.Watch): most looks only
                // check a few pixels, and it's read once a second. Read on every full look (`soon`) only while a
                // potion arriving would change where to go next: no station to go to (you're mixing a potion or
                // delivering), a station just finished or its bar just went, or one just started (so what's in the
                // bag then is known).
                val soon = wantStations && (due || (!isWorking && found != null) ||
                    (isWorking && found == null && waitingForPotion) || (!isWorking && noNext))
                if (wantTags || wantStations) w.bag.look(frame, now, potions, soon) else w.bag.hide()
                // The Alembic and the Retort by their looks, for the camera's tilt (so the arrow needn't wait for a
                // station to work): only with the funnels found and the compass read exactly, and never under the
                // game's interface, this tool's window or arrow, or a working station's bar
                val r = row
                val n = needle
                if (wantArrow && n != null && n.exact && (r != null || wantFree)) {
                    val skip = ArrayList(MixologyStations.interfaceAreas(b, n, w.bag.panel, b.scale, frame.width, frame.height))
                    if (window != null) skip.add(window)
                    if (arrowNow != null) skip.add(MixologyStations.Area(arrowNow.left, arrowNow.top, arrowNow.right, arrowNow.bottom))
                    found?.let {
                        val room = it.length.toFloat()
                        skip.add(MixologyStations.Area(it.left - room, it.top - room, it.right + room, it.bottom + room))
                    }
                    if (r != null) {
                        looks = MixologyStationLooks.read(frame, r, n.angle, skip)
                        looked = true
                    } else if (found == null) {
                        // No lever table on screen: the stations by themselves, two of them at least
                        sight = MixologyStationLooks.readWithoutTable(frame, n.angle, skip)
                        lookedFree = true
                    }
                }
            }
            Look(b, read, row, needle, searched, still, st, found, green, looked, looks, lookedFree, sight, BagSeen(w.bag))
        }, use = { seen -> useLook(now, seen) })
        return Began.SENT
    }

    // A full look's findings, back on the main thread
    private fun useLook(now: Long, seen: Look) {
        bagSeen = seen.bag
        bagDue = false
        if (seen.box == null) {
            box = null
            orders = emptyList()
            dots.hide()
            hideBag()
            stationsGone()
            redraw()
            setStatus("No Potion Orders box on screen. Go to the Mastering Mixology lab.")
            return
        }
        box = seen.box
        boxSeenAt = now
        orders = seen.orders
        if (showStations) {
            stations = seen.stations
            stationLook(now, seen.box.scale, seen.bar, seen.green, backInBag(seen.bar))
            planStations(now)
        } else {
            stationsGone()
            stations = emptyList()
        }
        val row = seen.levers
        if (showStations && showArrow) placeArrow(now, row, seen.compass, seen.bar, seen.looked, seen.looks, true, seen.sight.takeIf { seen.lookedFree })
        else { arrow = null; tilt.forget(); lookTilts.forget(); sights.forget() }
        if (seen.searched) compassSearchedAt = now
        if (row != null || seen.stillThere) compass = seen.compass
        // (found while looking for the stations without the table: where it is is kept for the next look)
        else if (compass == null && seen.compass != null) compass = seen.compass
        dots.look(now, row, seen.compass, seen.stillThere)
        if (showDots) noteRowMoved(now, row)
        redraw()
        setStatus(when {
            !showDots -> "Watching the orders."
            row != null && seen.compass == null -> "Can't see the compass (top right of the game): no lever dots."
            row != null && dots.levers == null -> "Turn the camera to face south (or north) for the lever dots."
            dots.levers != null -> "Watching the orders and the levers."
            else -> "Watching the orders. Lever dots show when the table with the three funnels is on screen, " +
                "with the camera facing south or north."
        })
    }

    // What a fast look saw: the working station's bar and its green, and the row of funnels, whether the last row is
    // still there, and the compass; and the bag as it is after the look
    private class FastLook(val bar: MixologyStations.Bar?, val green: Int, val levers: MixologyLevers.Levers?,
                           val stillThere: Boolean, val compass: MixologyLevers.Compass?, val bag: BagSeen)

    // Whether the table is moving on the screen (the camera follows the character, and turns and zooms): the row's
    // middle more than MOVED_BY of a spacing from where the last look saw it, or the row lost after being seen. Then
    // the dots are followed with fast looks for FOLLOW_MS, so they keep up instead of trailing a third of a second.
    private fun noteRowMoved(now: Long, row: MixologyLevers.Levers?) {
        val before = lastRowSeen
        if (row != null && before != null) {
            val moved = kotlin.math.hypot(row.funnels[1].x - before.funnels[1].x, row.funnels[1].y - before.funnels[1].y)
            if (moved > MOVED_BY * before.spacing || kotlin.math.abs(row.spacing - before.spacing) > MOVED_BY * before.spacing) {
                dotsMovingUntil = now + FOLLOW_MS
            }
        } else if (row == null && before != null && dots.levers == null && now - lastRowAt < FOLLOW_MS) {
            dotsMovingUntil = maxOf(dotsMovingUntil, lastRowAt + FOLLOW_MS)
        }
        // A row found far from where the dots were (or with none shown): it's only shown once the next look finds it
        // too, so that look comes quickly (a tenth of a second instead of a third)
        if (dots.waiting) dotsMovingUntil = maxOf(dotsMovingUntil, now + CONFIRM_MS)
        if (row != null) { lastRowSeen = row; lastRowAt = now }
    }

    // What one look saw: the order box and its names, the row of funnels, the compass (`searched` = looked for
    // anywhere, not only where it was), with no row, whether the last row's funnels are still there, each order's
    // station, a working station's bar with the green round it, and whether the Alembic and the Retort were looked
    // for (`looked`) with the tilts their looks gave (`looks`); the same with no lever table on screen (`lookedFree`,
    // `sight`); and the bag as it is after the look
    private class Look(val box: MixologyReader.Box?, val orders: List<MixologyReader.Order?>,
                       val levers: MixologyLevers.Levers?, val compass: MixologyLevers.Compass?,
                       val searched: Boolean, val stillThere: Boolean,
                       val stations: List<MixologyStations.Station?>, val bar: MixologyStations.Bar?, val green: Int,
                       val looked: Boolean, val looks: MixologyStationLooks.Read?,
                       val lookedFree: Boolean, val sight: MixologyStationLooks.Sight?, val bag: BagSeen)

    // After a look at the station's bar: follows the station's work (MixologyStations.Watch), buzzes once when the
    // quick-time moment starts, and notes which station finished a potion
    // Whether the potion the working station has is back in the bag, finished (its bar not seen on this look, the
    // inventory open): one more finished potion of its kind than when the station started
    private fun backInBag(found: MixologyStations.Bar?): Boolean {
        val before = finishedAtStart ?: return false
        if (!working || found != null || !bagSeen.open) return false
        return finishedOf(bagNow(), inStation) > before
    }

    // How many finished potions of this kind the bag holds (any kind when it isn't known)
    private fun finishedOf(carried: List<MixologyStations.Carried>, potion: String?) =
        carried.count { it.finished && (potion == null || it.potion == potion) }

    private fun stationLook(now: Long, scale: Double, found: MixologyStations.Bar?, green: Int, backInBag: Boolean = false) {
        var w = stationWatch
        if (w == null || kotlin.math.abs(scale - watchScale) > 0.01) {
            w = MixologyStations.Watch(scale)
            stationWatch = w
            watchScale = scale
        }
        val s = w.look(now, found, green, backInBag)
        if (found != null) bar = found else if (!s.working) bar = null
        // The station just started on: the one the arrow pointed at (the plan from just before, while the potion was
        // still in the bag). The Retort has no quick-time moment, and a green potion pulsing in it looks like one
        // (play_5.mp4 at 64 s), so it gets no signal. Unknown (inventory closed) = signal as usual: the plan kept
        // from the bag as last seen is only a guess of where the potion went, so it isn't used for this. Once the
        // bar's place on the screen names its station (placeArrow, `namedStation`), that's used instead.
        // (the potion can leave the bag a look or two before the bar shows, and by then the plan has moved on to
        // the next potion: `sent` remembers where this one went)
        if (s.working && !working) {
            workingAt = sent.justSent(now) ?: plan?.nextStation?.takeIf { bagSeen.open }
            // The potion handed over: the one the plan sent there. With the inventory closed it leaves the bag
            // worked out here (with it open, the bag itself shows that).
            val potion = sent.justSentPotion(now) ?: plan?.nextOrder?.let { orders.getOrNull(it)?.name }
            inStation = potion
            namedStation = null
            // (the potion that left the bag is in the station now: the next plan mustn't take it for one just sent)
            sent.stationStarted()
            // (the bag was read on this look, as a station started: `soon`)
            finishedAtStart = if (bagSeen.open) finishedOf(bagNow(), potion) else null
            if (!bagSeen.open && potion != null) lastBag = lastBag?.let { b ->
                val i = b.indexOfFirst { !it.finished && it.potion == potion }
                if (i < 0) b else b.filterIndexed { j, _ -> j != i }
            }
        }
        if (!s.working) workingAt = null
        working = s.working
        val retort = workingAt == MixologyStations.Station.RETORT
        // The Alembic: its buzz and frame come from its clock, most of a second before the sparkle (a tap on the
        // sparkle itself is too late and sets the bar back), so the sparkle seen gives no signal there. If the cue
        // is due before the next look, a timer gives it on time.
        val atAlembic = workingAt == MixologyStations.Station.ALEMBIC
        val b = bar
        handler.removeCallbacks(alembicCue)
        if (s.working && b != null && atAlembic) {
            alembic.look(now, b.fill)
            if (alembic.buzzNow && buzz) vibrate()
            // (counted from when the screen was copied: the reading on the worker has taken a few ms since)
            val wait = alembic.cueInMs
            if (wait in 1..MixologyStations.Watch.FAST_MS)
                handler.postDelayed(alembicCue, (wait - (SystemClock.uptimeMillis() - now)).coerceAtLeast(0L))
        } else if (!s.working) alembic.reset()
        tapNow = if (atAlembic) alembic.tapNow && bar != null else s.tapNow && bar != null && !retort
        if (s.started && buzz && !retort && !atAlembic) vibrate()
        s.finished?.let { guess ->
            // Which station it was: by its bar's place on the screen when that was read, else by its quick-time
            // moments (Watch)
            val station = namedStation ?: guess
            val potion = inStation
            if (potion != null) {
                made.add(MixologyStations.Made(potion, station))
                if (!bagSeen.open) lastBag = lastBag?.plus(MixologyStations.Carried(potion, true))
            } else used.add(station)
            byOrder = true
            byOrderBag = null
            bagDue = true
        }
        if (!s.working) { inStation = null; namedStation = null; finishedAtStart = null }
    }

    // The Alembic's cue, when it's due between two looks
    private val alembicCue = Runnable {
        if (running && !destroyed && working && bar != null && workingAt == MixologyStations.Station.ALEMBIC) {
            alembic.at(SystemClock.uptimeMillis())
            if (alembic.buzzNow && buzz) vibrate()
            tapNow = alembic.tapNow
            redraw()
        }
    }

    // "done!" and the next order, from the orders, their stations and the bag's potions (MixologyStations.plan).
    // Not while a station works: the potion it's working on is out of the bag then, which would point at the wrong
    // order. With the inventory closed, from the bag as last seen (`lastBag`); never seen = no plan.
    private fun planStations(now: Long) {
        val names = orders.map { it?.name }
        if (names != usedFor) {   // new orders: the stations used were for the old, and the bag has changed
            used.clear(); made.clear(); usedFor = names; byOrder = false; lastBag = null
        }
        if (working) return
        val carried = if (bagSeen.open) bagNow().also { lastBag = it } else lastBag
        if (carried == null) { plan = null; sent.unseen(); return }
        val unfinished = carried.filter { !it.finished }.map { it.potion }
        if (byOrder) { if (byOrderBag == null) byOrderBag = unfinished else if (unfinished != byOrderBag) byOrder = false }
        val wanted = orders.indices.map { i ->
            val name = orders[i]?.name
            val station = stations.getOrNull(i)
            if (name != null && station != null) MixologyStations.Wanted(name, station) else null
        }
        val next = MixologyStations.plan(wanted, carried, used, made, byOrder)
        plan = next
        if (bagSeen.open) sent.planned(now, next.nextStation, unfinished.size, next.nextOrder?.let { wanted[it]?.potion })
        else sent.unseen()
    }

    // The Mixology potions in the inventory now, in its order
    private fun bagNow(): List<MixologyStations.Carried> = bagSeen.tags.mapNotNull { t ->
        MixologyData.POTIONS.firstOrNull { it.recipe == t.recipe }?.let { MixologyStations.Carried(it.name, t.finished) }
    }

    // The arrow over the station to use next (MixologyStations, part 4). `row` and `needle` = the funnels and the
    // compass as this look found them. The camera's tilt comes from how the Alembic, the Retort and the Agitator
    // look (`looked` = this look tried, `looks` = what it read; MixologyStationLooks), so the arrow can show before
    // any station has worked; failing that, from a working station's bar (`barSeen`), as before. The arrow shows
    // only when everything is sure:
    //  - the plan names a next station (the inventory was seen) and no station is working (its bar shows it then),
    //    nor was a potion handed over a moment ago;
    //  - the funnels were found on this look and the one before (one look alone can be something else, like the
    //    words of the game's menu), the compass was read exactly, facing south or north, and it hasn't turned
    //    since the tilt was learned;
    //  - the station's place and the whole arrow are on the screen, clear of the game's interface, of the lever
    //    table itself and of this tool's own window. The arrow goes above the station, pointing down; if there's
    //    no room there (the station is at the top of the screen), below it, pointing up.
    // With the lever table off the screen (zoomed in at a station), the arrow can still show: `full` = this was a
    // look at everything, `sight` = what it found of the stations by themselves (MixologyStationLooks.
    // readWithoutTable). Then the next station's own bulb, pot or beakers must have been found on this look and the
    // one before, with a second station's to prove the first (`Sights`), and the arrow keeps off the thing found.
    private fun placeArrow(now: Long, row: MixologyLevers.Levers?, needle: MixologyLevers.Compass?, barSeen: MixologyStations.Bar?,
                           looked: Boolean, looks: MixologyStationLooks.Read?, full: Boolean, sight: MixologyStationLooks.Sight?) {
        tilt.compass(needle?.angle)
        lookTilts.compass(needle?.angle)
        if (row != null && needle != null && barSeen != null) {
            val named = tilt.bar(row, needle, barSeen, screenW, screenH)
            // The bar's place names its station (right for 712 of 712 looks in the recordings): that beats where the
            // plan sent the potion, which is a guess with the inventory closed or if the player went elsewhere
            if (working && named != null) { namedStation = named; workingAt = named }
        }
        if (looked && row != null && needle != null) lookTilts.look(row, needle, looks, screenW, screenH)
        val before = arrowRow
        val steady = row != null && before != null && now - arrowRowAt <= SAME_ROW_MS &&
            kotlin.math.abs(row.spacing - before.spacing) <= SAME_ROW * before.spacing
        arrowRow = row
        arrowRowAt = now
        val wasBySight = arrowBySight && arrow != null
        val arrowBefore = arrow
        arrow = null
        arrowBySight = false
        if (full) sights.look(now, if (row == null) sight else null)
        val b = box
        val station = plan?.nextStation
        if (b == null || station == null || working || sent.justSent(now) != null) return
        if (row == null) {
            // No lever table on screen. (A quick look between the full ones doesn't look for the stations: the
            // arrow stays where the last full look put it, a third of a second at most.)
            if (!full) { if (wasBySight) { arrow = arrowBefore; arrowBySight = true }; return }
            val placed = sights.spot(now, station, screenW, screenH) ?: return
            val areas = MixologyStations.interfaceAreas(b, needle, bagSeen.panel, b.scale, screenW, screenH) + listOfNotNull(ownWindow())
            val over = MixologyStations.arrowClearOf(MixologyStations.arrowOver(placed.spot, placed.spacing, b.scale), placed.keep)
            val under = MixologyStations.arrowClearOf(MixologyStations.arrowUnder(placed.spot, placed.spacing, b.scale), placed.keep)
            arrow = when {
                MixologyStations.arrowClear(over, placed.spot, areas, screenW, screenH) -> over
                MixologyStations.arrowClear(under, placed.spot, areas, screenW, screenH) -> under
                else -> null
            }
            arrowBySight = arrow != null
            return
        }
        if (!steady) return
        val spot = lookTilts.spot(row, needle, station, screenW, screenH)
            ?: tilt.spot(row, needle, station, screenW, screenH) ?: return
        val areas = MixologyStations.interfaceAreas(b, needle, bagSeen.panel, b.scale, screenW, screenH) +
            MixologyStations.tableArea(row) + listOfNotNull(ownWindow())
        val over = MixologyStations.arrowOver(spot, row.spacing, b.scale)
        val under = MixologyStations.arrowUnder(spot, row.spacing, b.scale)
        arrow = when {
            MixologyStations.arrowClear(over, spot, areas, screenW, screenH) -> over
            MixologyStations.arrowClear(under, spot, areas, screenW, screenH) -> under
            else -> null
        }
    }

    // Where this tool's own window is on the screen, while it's open (with a little room round it)
    private fun ownWindow(): MixologyStations.Area? {
        val root = holder?.takeIf { it.isAttachedToWindow }?.rootView?.takeIf { it.isShown } ?: return null
        val at = IntArray(2)
        root.getLocationOnScreen(at)
        val room = dp(4).toFloat()
        return MixologyStations.Area(at[0] - room, at[1] - room, at[0] + root.width + room, at[1] + root.height + room)
    }

    // One short buzz (the quick-time moment has started)
    private fun vibrate() {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createOneShot(120, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    // Draws what's known now (or clears it)
    private fun redraw() {
        if (!running) return
        val b = box
        val pl = if (showStations) plan else null
        val recipes = if (showRecipes && b != null) orders.indices.mapNotNull { i ->
            val o = orders[i]
            val potion = o?.name?.let { MixologyData.potion(it) } ?: return@mapNotNull null
            MixologyOverlay.Recipe(potion.recipe, o.textRight, o.top, o.bottom, b.scale,
                done = pl?.done?.getOrNull(i) == true, next = pl?.nextOrder == i)
        } else emptyList()
        val sig = bar?.takeIf { showStations && tapNow }?.let { overlay.signalFor(it) }
        // The Alembic's countdown to its cue, for the last COUNT_FROM_S seconds (only when the arrow sent the
        // potion to the Alembic: the other stations' moments come at no set time)
        val b2 = bar
        val secondsLeft = if (b2 != null && showStations && working && !tapNow && workingAt == MixologyStations.Station.ALEMBIC)
            alembic.secondsLeft else null
        val count = if (b2 != null && secondsLeft != null && secondsLeft <= COUNT_FROM_S)
            overlay.countdownFor(b2, secondsLeft, COUNT_FROM_S) else null
        val l = dots.levers
        val spots = if (showDots && l != null) overlay.dotsFor(l, dots.north, dp(7).toFloat(), dp(16).toFloat()) else emptyList()
        val tags = if (showTags && b != null) bagSeen.tags.map { overlay.tagFor(it) } else emptyList()
        overlay.set(recipes, spots, tags, sig, count, arrow.takeIf { showStations && showArrow && b != null })
    }

    // ---------------- Called by BubbleService ----------------

    // The phone turned: the screen's size changed, so start over with a new overlay
    fun onRotated() {
        if (!running) return
        forget()
        capture.matchScreenSize()
        val (w, h) = fullScreenSize(windowManager)
        screenW = w
        screenH = h
        overlay.show(w, h)
        redraw()
    }

    // Still working with its window closed
    fun isWatching() = running

    fun destroy() {
        destroyed = true
        capture.removeStopListener(captureStopped)
        running = false
        worker?.quit()
        worker = null
        handler.removeCallbacksAndMessages(null)
        overlay.hide()
    }
}

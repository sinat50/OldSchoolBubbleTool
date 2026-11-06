package com.sinat.osrsbubbletool

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log

// Gear Loadouts' guided scan ("Scan Current Loadout"): looks at the screen about twice a second while you open your
// inventory, equipment and rune pouch in the game, in any order, and saves a screenshot of each part as it shows:
// the side panel with the inventory tab open, the side panel with the equipment tab open, an open rune pouch's
// window, and the spellbook tab button (its picture shows which spellbook you're on). Nothing on them is read: which
// tab is open is told by its red button (PanelFinder), and the panel and the pouch's window by their black outlines.
// A part is only saved when it shows on two looks in a row, so a tab caught while it's still being drawn isn't kept.
// The rune pouch is optional: once the other three are saved, the scan waits POUCH_WAIT_MS for one, then stops by
// itself (or after SCAN_SECONDS). Each look's timings go to a test build's log (adb logcat -s GearLoadouts).
class GuidedScan(private val capture: CaptureManager, private val debugBuild: Boolean, private val listener: Listener) {

    companion object {
        private const val FIRST_LOOK_MS = 500L   // wait after the window shrinks before the first look
        private const val LOOK_EVERY_MS = 400L   // between one look's end and the next
        private const val SCAN_SECONDS = 90
        const val POUCH_WAIT_MS = 8000L          // after the other parts are saved, how long to wait for a rune pouch
    }

    // The parts of a loadout, as saved in its folder (files/setups/<id>/<file>.webp; Inventory Setups saved .png)
    enum class Part(val label: String, val file: String) {
        INVENTORY("Inventory", "inventory"),
        EQUIPMENT("Equipment", "equipment"),
        RUNE_POUCH("Rune pouch", "rune_pouch"),
        SPELLBOOK("Spellbook", "spellbook")
    }

    // Told on the main thread
    interface Listener {
        fun saved(part: Part, picture: Bitmap)   // a part's screenshot (the listener keeps it and recycles it)
        fun looked()                             // after every look (the checklist counts down the rune pouch's wait)
        fun finished()                           // everything is saved, or the time is up
    }

    // A part showing on two looks in a row
    class Steady {
        private var seen = false   // the last look showed it

        // Whether to save it from this look (it showed on the last one too)
        fun check(showing: Boolean): Boolean {
            val take = showing && seen
            seen = showing
            return take
        }
    }

    // What the scan has saved so far
    class Progress {
        val started = System.nanoTime()
        var looks = 0
        val saved = HashSet<Part>()
        val steady = Part.entries.associateWith { Steady() }
        var othersSavedAt = 0L         // when the spellbook, inventory and equipment were all saved (0 until then)
        var pouchShowing = false       // the last look saw an open rune pouch (not saved yet)
        val othersSaved get() = Part.INVENTORY in saved && Part.EQUIPMENT in saved && Part.SPELLBOOK in saved
        val seconds get() = (System.nanoTime() - started) / 1e9

        // Seconds left to open a rune pouch before the scan stops by itself, or null if it isn't waiting for one
        fun pouchSecondsLeft(now: Long): Int? =
            if (othersSavedAt == 0L || Part.RUNE_POUCH in saved) null
            else ((POUCH_WAIT_MS - (now - othersSavedAt) + 999) / 1000).coerceAtLeast(0).toInt()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val tick = Runnable { look() }
    private var ends = 0L
    private var busy = false

    // The scan under way, or null
    var progress: Progress? = null
        private set

    fun start() {
        if (progress != null) return
        progress = Progress()
        ends = System.currentTimeMillis() + SCAN_SECONDS * 1000L
        handler.postDelayed(tick, FIRST_LOOK_MS)
    }

    // Stops it; what it saved so far (null if none was under way)
    fun stop(): Progress? {
        val p = progress ?: return null
        progress = null
        handler.removeCallbacks(tick)
        if (debugBuild) Log.d("GearLoadouts", "scan finished at %.1f s after ${p.looks} looks".format(p.seconds))
        return p
    }

    // A box cut out of the screenshot (kept inside it), or null if nothing of it is on the screen
    private fun cut(shot: Bitmap, box: PanelFinder.Box): Bitmap? {
        val l = box.left.coerceIn(0, shot.width); val t = box.top.coerceIn(0, shot.height)
        val r = box.right.coerceIn(0, shot.width); val b = box.bottom.coerceIn(0, shot.height)
        if (r - l < 8 || b - t < 8) return null
        val part = Bitmap.createBitmap(shot, l, t, r - l, b - t)
        // (a cut the size of the whole screenshot can be the screenshot itself, which is let go after this look)
        return if (part === shot) shot.copy(Bitmap.Config.ARGB_8888, false) else part
    }

    private fun look() {
        val sc = progress ?: return
        // a stopped scan's last look still finishing: try again shortly
        if (busy) { handler.postDelayed(tick, LOOK_EVERY_MS); return }
        if (System.currentTimeMillis() > ends) { listener.finished(); return }
        val grabStarted = System.nanoTime()
        capture.matchScreenSize()
        val shot = capture.grab()
        if (shot == null) { handler.postDelayed(tick, LOOK_EVERY_MS); return }
        val grabMs = (System.nanoTime() - grabStarted) / 1_000_000
        busy = true
        val number = ++sc.looks
        Thread {
            val pictures = HashMap<Part, Bitmap>()
            var pouchShowing = false
            // how long each part of this look takes, for a test build's log
            val times = StringBuilder("look $number at %.1f s: screenshot $grabMs ms".format((grabStarted - sc.started) / 1e9))
            var t = System.nanoTime()
            fun lap(what: String) { times.append(", $what ${(System.nanoTime() - t) / 1_000_000}"); t = System.nanoTime() }
            try {
                val pixels = LightBoxReader.Pixels(shot)
                lap("copy")
                val panel = PanelFinder.findPanel(pixels)
                lap("find panel${if (panel == null) " (none)" else ""}")
                // an open rune pouch's window dims the game behind it: the rest is only saved with the window closed
                val pouchWindow = PanelFinder.runePouchWindow(pixels)
                lap("find pouch${if (pouchWindow == null) " (none)" else ""}")
                val showing = HashMap<Part, PanelFinder.Box?>()
                if (pouchWindow != null) showing[Part.RUNE_POUCH] = pouchWindow
                else if (panel != null) {
                    showing[Part.SPELLBOOK] = PanelFinder.spellbookTab(panel)
                    if (PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.INVENTORY_TAB)) showing[Part.INVENTORY] = panel
                    if (PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.EQUIPMENT_TAB)) showing[Part.EQUIPMENT] = panel
                }
                pouchShowing = pouchWindow != null
                for (part in Part.entries) {
                    if (part in sc.saved) continue
                    val box = showing[part]
                    if (sc.steady.getValue(part).check(box != null) && box != null) cut(shot, box)?.let { pictures[part] = it }
                }
                if (pictures.isNotEmpty()) lap("cut ${pictures.keys.joinToString { it.label.lowercase() }}")
            } catch (e: Throwable) {
                // e.g. out of memory on an older phone: this look is skipped, the next one tries again
                times.append(", failed: $e")
            }
            shot.recycle()   // the parts are cut out: the screenshot isn't needed any more
            if (debugBuild) Log.d("GearLoadouts", times.toString())
            handler.post {
                busy = false
                if (progress !== sc) { pictures.values.forEach { it.recycle() }; return@post }   // stopped meanwhile
                sc.pouchShowing = pouchShowing && Part.RUNE_POUCH !in pictures
                for ((part, picture) in pictures) {
                    sc.saved.add(part)
                    listener.saved(part, picture)
                }
                if (sc.othersSaved && sc.othersSavedAt == 0L) sc.othersSavedAt = System.currentTimeMillis()
                listener.looked()
                if (progress !== sc) return@post   // the listener stopped it
                // done: everything saved, or the other parts saved and no rune pouch opened in time
                val waitedForPouch = sc.othersSaved && !sc.pouchShowing &&
                    System.currentTimeMillis() - sc.othersSavedAt >= POUCH_WAIT_MS
                if (sc.saved.size == Part.entries.size || waitedForPouch) listener.finished()
                else handler.postDelayed(tick, LOOK_EVERY_MS)
            }
        }.start()
    }
}

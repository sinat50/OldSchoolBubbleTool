package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.get
import java.util.Locale
import kotlin.math.abs

// The Puzzle Box Solver tool: finds the puzzle on screen, reads the tiles, solves it,
// and guides you with outlines on the puzzle while this window stays out of the way.
class PuzzleBoxTool(
    private val context: Context,
    windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit,
    private val hideWindow: () -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L  // wait after hiding the bubble before taking a screenshot
        const val GRID = 5                 // puzzle boxes are 5 x 5
        const val MIN_FIT_CONFIDENCE = 2f  // below this, the line-up step is ignored
        const val TRACK_INTERVAL_MS = 80L  // how often to check the puzzle while guiding you
        const val SETTLE_MS = 160L         // a move counts once seen twice in a row, or after this long
        const val LOOKAHEAD = 6            // how many planned moves ahead a fast tapper can get
        const val LOST_AFTER_MS = 6_000L   // stop guiding if the tiles can't be read for this long
        const val GONE_READS = 2           // frame missing this many looks in a row (~0.16 s) = closed
        const val SOLVED_SHOW_MS = 2_500L  // how long "Solved!" stays up
        const val REPLAN_WAIT_MS = 150L    // let the outlines disappear before re-reading the tiles
    }

    private val handler = Handler(Looper.getMainLooper())
    private val puzzleArea = PuzzleAreaOverlay(context, windowManager)
    private val guide = MoveGuideOverlay(context, windowManager)
    private val prefs = context.getSharedPreferences("puzzle_box", Context.MODE_PRIVATE)
    private var styleButtons: List<TextView> = emptyList()
    private val references = PuzzleReferences(context)
    private var status: TextView? = null
    private var scanButton: TextView? = null
    private var areaButton: TextView? = null
    private var destroyed = false
    private var hiddenForPicture = false   // the bubble and window are hidden while a picture is about to be taken

    // Guiding state
    private var lastArea: Rect? = null        // where the tiles are, in screenshot pixels
    private var lastFrame = IntArray(0)       // the frame's colours around them when found (PuzzleFinder.frameColours)
    private var puzzleName = ""
    private var solution: List<Int> = emptyList()
    private var step = 0
    private var currentEmpty = -1
    private var candidateEmpty = -1
    private var candidateSince = 0L
    private var candidateSeen = 0
    private var lastSeen = 0L
    private var tracking = false
    private var solving = false
    private var scanning = false               // from tapping Scan until the tiles are read (or the scan fails)
    private var misses = 0                     // looks in a row where the puzzle's frame wasn't there
    private var finished = false               // solved, showing "Solved!" for a moment
    private var replanAt = 0L                  // when to re-read the tiles, or 0 if not needed
    private var planId = 0                     // changes whenever guiding stops, so old results are ignored

    private val captureStopped: () -> Unit = { onCaptureStopped() }

    init {
        capture.addStopListener(captureStopped)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Window contents ----------------

    fun buildView(): View {
        fun button(label: String, size: Float, onClick: () -> Unit) = TextView(context).apply {
            text = label
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(dp(6), dp(7), dp(6), dp(7))
            background = GradientDrawable().apply {
                setColor(Palette.BUTTON_BROWN)
                cornerRadius = dp(6).toFloat()
            }
            setOnClickListener { onClick() }
        }

        scanButton = button("Scan puzzle", 13f) { if (tracking || solving) stopGuide("Stopped.") else scan() }
        areaButton = button("Set area by hand", 11f) { toggleAreaSetup() }.apply {
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

        // Guide style: coloured boxes around the tiles, or dots that shrink with each move
        guide.dots = prefs.getBoolean("dots", false)
        fun styleButton(label: String, dots: Boolean) = button(label, 11f) {
            guide.dots = dots
            prefs.edit { putBoolean("dots", dots) }
            refreshStyleButtons()
        }.apply { setPadding(dp(4), dp(5), dp(4), dp(5)) }
        val boxesButton = styleButton("▢ Boxes", false)
        val dotsButton = styleButton("● Dots", true)
        styleButtons = listOf(boxesButton, dotsButton)
        refreshStyleButtons()
        val styleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(context).apply {
                text = "Show moves as:"
                textSize = 11f
                setTextColor(Palette.DARK_BROWN)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(4) })
            addView(boxesButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            addView(dotsButton, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }

        status = TextView(context).apply {
            text = "Open a puzzle box, then tap Scan puzzle. The moves appear on the puzzle."
            textSize = 11f
            setTextColor(Palette.DARK_BROWN)
            setPadding(0, dp(6), 0, dp(6))
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.PARCHMENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            addView(scanButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(status, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(styleRow, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
            addView(areaButton, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) })
        }
    }

    // The chosen style's button is darker
    private fun refreshStyleButtons() {
        styleButtons.forEachIndexed { i, b ->
            val chosen = (i == 1) == guide.dots
            (b.background as? GradientDrawable)?.setColor(if (chosen) Palette.SELECTED_BROWN else Palette.LIGHT_BUTTON)
            b.setTypeface(null, if (chosen) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
    }

    // ---------------- Puzzle area by hand (only needed if it can't be found automatically) ----------------

    private fun toggleAreaSetup() {
        if (puzzleArea.isShowing) {
            puzzleArea.saveAndHide()
            areaButton?.text = "Set area by hand"
            status?.text = "Area saved. Tap Scan puzzle."
        } else {
            stopGuide(null)
            puzzleArea.show()
            areaButton?.text = "Save area"
            status?.text = "Only needed if Scan can't find the puzzle. Drag the gold frame roughly " +
                "over the tiles, then tap Save area."
        }
    }

    // ---------------- Scanning ----------------

    private fun scan() {
        stopGuide(null)
        if (puzzleArea.isShowing) toggleAreaSetup() // save and hide the frame so it's not in the screenshot

        // Capture is off (first scan since the bubble started): ask Android once
        if (!capture.isActive) {
            status?.text = "Waiting for screen-capture permission..."
            capture.request { ok ->
                if (destroyed) return@request   // closed down (dark mode, text size) while asking
                if (ok) scan()
                else status?.text = "Couldn't turn on screen capture: ${capture.lastError}."
            }
            return
        }

        status?.text = "Looking for the puzzle..."
        scanning = true
        val id = planId   // changes if you stop, rotate or set the area meanwhile: then this scan is dropped
        capture.matchScreenSize()
        setOverlaysVisible(false)
        hiddenForPicture = true
        handler.postDelayed({
            if (destroyed) return@postDelayed
            val shot = capture.grab()
            setOverlaysVisible(true)
            hiddenForPicture = false
            if (id != planId) return@postDelayed
            if (shot == null) {
                scanning = false
                status?.text = "Couldn't capture the screen. Try again."
                return@postDelayed
            }
            // Finding and lining up the puzzle takes a moment, so it's done in the background
            // (on the main thread it could make the game stutter)
            Thread {
                val found = PuzzleFinder.findTiles(LightBoxReader.Pixels(shot))
                val result = prepare(shot, found)
                handler.post { if (!destroyed && id == planId) showResult(result, id) }
            }.start()
        }, CAPTURE_DELAY_MS)
    }

    // What a scan found. rough: where the puzzle roughly is (null = not found); area: the tiles, lined up
    // exactly; puzzle: the tiles cut out (null = off the screen); frame: the frame's colours around them.
    // (Nothing is shown in the window: the screenshot is let go once the tiles are read.)
    private class Scan(val rough: Rect?, val area: Rect?, val lined: Boolean, val fitConfidence: Float,
                       val puzzle: Bitmap?, val frame: IntArray = IntArray(0))

    // The slow part of a scan (background thread): line the grid up and cut out the tiles
    private fun prepare(shot: Bitmap, found: Rect?): Scan {
        val rough = found ?: puzzleArea.savedArea(shot.width, shot.height)
            ?: return Scan(null, null, false, 0f, null)

        // Line the grid up exactly. When the puzzle was found by its frame, that's already
        // exact, so the line-up is only used if it agrees closely.
        val fit = GridFinder.find(shot, rough)
        val fitWorked = fit != null && fit.confidence >= MIN_FIT_CONFIDENCE
        val area = when {
            found != null && fitWorked && closeTo(fit!!.area, found) -> fit.area
            found != null -> found
            fitWorked -> fit!!.area
            else -> rough
        }
        val lined = found != null || fitWorked
        val confidence = fit?.confidence ?: 0f

        val puzzle = crop(shot, area) ?: return Scan(rough, area, lined, confidence, null)
        return Scan(rough, area, lined, confidence, puzzle, PuzzleFinder.frameColours(BitmapPixels(shot), area))
    }

    private fun showResult(scan: Scan, id: Int) {
        if (scan.rough == null) {
            scanning = false
            status?.text = "Couldn't find the puzzle. Make sure it's open and not covered, then scan again. " +
                "If it still isn't found, use Set area by hand."
            return
        }
        val area = scan.area
        val puzzle = scan.puzzle
        if (area == null || puzzle == null) {
            scanning = false
            status?.text = "The puzzle area is off the screen. Scan again."
            return
        }
        lastArea = Rect(area)
        lastFrame = scan.frame

        val lined = scan.lined
        if (!lined) {
            val score = String.format(Locale.US, "%.1f", scan.fitConfidence)
            status?.text = "Couldn't line up the grid (confidence $score), so your frame was used."
        } else {
            status?.text = "Reading the tiles..."
        }

        // Identify the tiles by comparing them with each puzzle's colour summaries
        references.load { puzzles, error ->
            if (destroyed || id != planId) return@load   // stopped while the file was being read
            if (puzzles == null) {
                scanning = false
                status?.text = "Couldn't read the puzzle data: $error."
                return@load
            }
            identify(puzzle, puzzles) { match ->
                if (id != planId) return@identify   // stopped meanwhile
                if (match == null) {
                    scanning = false
                    status?.text = "Couldn't read the tiles. Try scanning again."
                    return@identify
                }
                showMatch(match, lined)
            }
        }
    }

    // Two areas are the same puzzle position to within 4% of its size
    private fun closeTo(a: Rect, b: Rect): Boolean {
        val tol = b.width() * 0.04f
        return abs(a.left - b.left) <= tol && abs(a.top - b.top) <= tol &&
            abs(a.right - b.right) <= tol && abs(a.bottom - b.bottom) <= tol
    }

    private fun showMatch(match: TileMatcher.Result, lined: Boolean) {
        scanning = false   // (solving takes over from here)

        val diff = String.format(Locale.US, "%.1f", match.difference)
        val gridNote = if (lined) "" else " (Grid wasn't lined up, so the reading may be off.)"
        val fixNote = if (match.correctedPositions.isEmpty()) "" else
            " Two look-alike tiles were swapped to make it solvable."

        if (match.solvable) {
            status?.text = "${match.puzzleName} (difference $diff).$gridNote$fixNote Working out the moves..."
            solveAndGuide(match.board, match.puzzleName)
        } else {
            status?.text = "Looks like ${match.puzzleName} (difference $diff), but a tile was misread, " +
                "because this layout can't be solved.$gridNote Try scanning again."
        }
    }

    // ---------------- Solving and guiding ----------------

    private fun solveAndGuide(board: IntArray, name: String) {
        val id = planId
        solving = true
        scanButton?.text = "Stop"
        Thread {
            val moves = try { PuzzleSolver.solve(board) } catch (e: OutOfMemoryError) { null }   // a very hard scramble on a low-memory phone
            handler.post {
                if (id != planId || destroyed) return@post  // guiding was stopped meanwhile
                solving = false
                when {
                    moves == null -> stopGuide("Couldn't work out a solution. Try scanning again.")
                    moves.isEmpty() -> {
                        stopGuide("This puzzle is already solved!")
                    }
                    else -> startGuide(moves, board.indexOf(TileMatcher.EMPTY), name)
                }
            }
        }.start()
    }

    private fun startGuide(moves: List<Int>, emptyPosition: Int, name: String) {
        val area = lastArea ?: return
        val firstTime = !guide.isShowing
        puzzleName = name
        solution = moves
        step = 0
        currentEmpty = emptyPosition
        candidateEmpty = -1
        lastSeen = SystemClock.uptimeMillis()
        if (firstTime) guide.show(area)
        updateGuide()
        scanButton?.text = "Stop"
        if (!tracking) {
            tracking = true
            handler.postDelayed(trackRunnable, TRACK_INTERVAL_MS)
        }
        if (firstTime) hideWindow()   // out of the way; tap the bubble to bring it back
    }

    private fun updateGuide() {
        guide.setMoves(solution.drop(step))
        guide.setMessage("Move ${step + 1} of ${solution.size}")
        status?.text = "Move ${step + 1} of ${solution.size}: tap the tile outlined in green. " +
            "Yellow, orange and red are the next moves."
    }

    // Stops guiding. message = what to show in the window, or null to leave it as it is.
    private fun stopGuide(message: String?) {
        scanning = false
        finished = false
        misses = 0
        tracking = false
        solving = false
        replanAt = 0L
        handler.removeCallbacks(trackRunnable)
        guide.hide()
        solution = emptyList()
        step = 0
        planId++
        scanButton?.text = "Scan puzzle"
        if (message != null) status?.text = message
    }

    private val trackRunnable = object : Runnable {
        override fun run() {
            if (!tracking) return
            trackOnce()
            if (tracking) handler.postDelayed(this, TRACK_INTERVAL_MS)
        }
    }

    // Checks whether the empty space has moved, reading only a few pixels per tile
    private fun trackOnce() {
        val area = lastArea ?: return
        val now = SystemClock.uptimeMillis()

        // You left the game (when only the game is shared): take the outlines off the screen and stop,
        // rather than leaving them over other apps and watching a screen that can't be seen
        if (capture.gameHidden) {
            stopGuide("You left the game, so the guide stopped. Tap Scan puzzle to carry on.")
            return
        }

        if (solving || replanAt != 0L) {
            // Time to re-read the tiles. This needs the newest picture of the screen, so it goes before the
            // check below, which would use that picture up and leave the re-plan waiting for another one.
            if (!solving && now >= replanAt) {
                // no fresh picture of the screen for a long while: give up rather than show "Re-planning..." for good
                if (now - replanAt > LOST_AFTER_MS) {
                    stopGuide("Lost sight of the puzzle. If it's still open, tap Scan puzzle to carry on.")
                    return
                }
                replanNow(area)
                return
            }
            // While re-planning, still notice if the puzzle is closed, so the guide doesn't linger
            val gone = capture.sample { !PuzzleFinder.frameVisible(it, area, lastFrame) }
            if (gone == true && ++misses >= GONE_READS) {
                stopGuide("The puzzle was closed. Tap Scan puzzle to start again.")
                return
            }
            if (gone == false) misses = 0
            return
        }

        // -2 = the puzzle's frame is gone (closed); -1 = can't tell (a tile is sliding);
        // null = screen unchanged
        val empty = capture.sample {
            if (!PuzzleFinder.frameVisible(it, area, lastFrame)) -2
            else if (finished) -3 else PuzzleFinder.emptySpace(it, area, guide.coveredTiles)
        }
        if (empty == -2) {
            // closed: clear everything off the screen straight away
            if (++misses >= GONE_READS) {
                stopGuide(if (finished) "Puzzle solved! Scan again for another one."
                          else "The puzzle was closed. Tap Scan puzzle to start again.")
            }
            return
        }
        if (empty != null) misses = 0
        if (finished || empty == -3) return   // just watching for it to close
        if (empty == -1) {
            if (now - lastSeen > LOST_AFTER_MS) {
                stopGuide("Lost sight of the puzzle. If it's still open, tap Scan puzzle to carry on.")
            }
            return
        }
        lastSeen = now
        if (empty != null) {
            if (empty != candidateEmpty) {
                candidateEmpty = empty
                candidateSince = now
                candidateSeen = 1
            } else {
                candidateSeen++
            }
        }
        if (candidateEmpty == -1 || candidateEmpty == currentEmpty) return
        if (candidateSeen < 2 && now - candidateSince < SETTLE_MS) return
        onEmptyMoved(candidateEmpty)
    }

    // After a move, the empty space sits where the tapped tile was
    private fun onEmptyMoved(newEmpty: Int) {
        val remaining = solution.size - step
        for (k in 0 until minOf(LOOKAHEAD, remaining)) {
            if (solution[step + k] == newEmpty) {
                step += k + 1
                currentEmpty = newEmpty
                if (step >= solution.size) finish() else updateGuide()
                return
            }
        }
        // A different move was made: read the tiles again and work out a new plan from here
        currentEmpty = newEmpty
        guide.setMoves(emptyList())
        guide.setMessage("Re-planning...", MoveGuideOverlay.MOVE_COLORS[1])
        status?.text = "That wasn't the planned move, so the app is working out a new plan..."
        replanAt = SystemClock.uptimeMillis() + REPLAN_WAIT_MS
    }

    private fun replanNow(area: Rect) {
        val shot = capture.grab() ?: return   // wait for a fresh picture
        // the puzzle closed just after the move: there's nothing to read (the tiles would be read from the game)
        if (!PuzzleFinder.frameVisible(BitmapPixels(shot), area, lastFrame)) {
            stopGuide("The puzzle was closed. Tap Scan puzzle to start again.")
            return
        }
        val puzzle = crop(shot, area) ?: return
        replanAt = 0L
        solving = true
        val id = planId   // changes if you stop or scan again meanwhile: then this re-plan is dropped
        references.load { puzzles, _ ->
            if (!tracking || id != planId) return@load
            if (puzzles == null) {
                stopGuide("Lost track of the puzzle. Tap Scan puzzle to start again.")
                return@load
            }
            // it's the same puzzle as before, so only compare with that one (quicker, and a half-slid tile
            // can't make it look like a different puzzle)
            val same = puzzles.filter { it.name == puzzleName }.ifEmpty { puzzles }
            identify(puzzle, same) { match ->
                if (!tracking || id != planId) return@identify
                if (match == null || !match.solvable) {
                    stopGuide("Lost track of the puzzle. Tap Scan puzzle to start again.")
                    return@identify
                }
                solveAndGuide(match.board, match.puzzleName)
            }
        }
    }

    // Reads the tiles by comparing them with the colour summaries. It takes a moment, so it's done in the
    // background (on the main thread it could make the game stutter). onDone runs on the main thread.
    private fun identify(puzzle: Bitmap, puzzles: List<PuzzleReferences.Puzzle>, onDone: (TileMatcher.Result?) -> Unit) {
        Thread {
            val match = try { TileMatcher.identify(puzzle, puzzles) } catch (e: Exception) { null }
            handler.post { if (!destroyed) onDone(match) }
        }.start()
    }

    private fun finish() {
        finished = true   // keep watching only so it disappears the moment the puzzle closes
        guide.setMoves(emptyList())
        guide.setMessage("Solved!", MoveGuideOverlay.GREEN)
        status?.text = "Puzzle solved!"
        val id = planId
        handler.postDelayed({ if (id == planId) stopGuide("Puzzle solved! Scan again for another one.") }, SOLVED_SHOW_MS)
    }

    // Reads a few pixels straight from a picture (LightBoxReader.Pixels copies the whole picture first,
    // which is only worth it when most of it is looked at)
    private class BitmapPixels(private val bitmap: Bitmap) : LightBoxReader.PixelSource {
        override val width get() = bitmap.width
        override val height get() = bitmap.height
        override fun rgb(x: Int, y: Int) = bitmap[x, y]
    }

    private fun crop(shot: Bitmap, area: Rect): Bitmap? {
        val r = Rect(area)
        if (!r.intersect(0, 0, shot.width, shot.height) || r.width() < GRID || r.height() < GRID) return null
        return Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height())
    }

    // Screen capture was turned off (for example from the status bar)
    private fun onCaptureStopped() {
        if (destroyed) return
        stopGuide("Screen capture is off. Tap Scan puzzle to turn it back on.")
    }

    // ---------------- Called by BubbleService ----------------

    // Closing the window keeps the guide going on the puzzle. Tap the bubble to bring
    // the window back, and Stop to end it.
    fun onWindowClosed() {
        if (puzzleArea.isShowing) {
            puzzleArea.hide()
            areaButton?.text = "Set area by hand"
        }
    }

    fun onRotated() {
        stopGuide("Screen rotated. Tap Scan puzzle to start again.")
        if (puzzleArea.isShowing) {
            puzzleArea.hide()
            areaButton?.text = "Set area by hand"
        }
    }

    // Scanning, solving or guiding (the bubble waits for this before rebuilding the tools for dark mode)
    fun isGuiding() = scanning || solving || tracking || guide.isShowing

    fun destroy() {
        destroyed = true
        capture.removeStopListener(captureStopped)
        // closed down in the half second before a picture: show the bubble and window again, since the
        // step that would have done it is cancelled below
        if (hiddenForPicture) { setOverlaysVisible(true); hiddenForPicture = false }
        handler.removeCallbacksAndMessages(null)   // including a scan that was about to take its picture
        stopGuide(null)
        puzzleArea.hide()
    }
}

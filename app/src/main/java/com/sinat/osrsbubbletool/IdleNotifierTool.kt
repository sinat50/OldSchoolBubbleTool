package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.core.content.edit
import kotlin.random.Random

// Idle Notifier: buzzes when your character stops working, so you can put the phone down while skilling. You zoom until
// your character fits a box drawn over the game, tap Start, and from then on it looks at the screen once a second
// (IdleWatch does the looking). It only watches: nothing is ever tapped or pressed in the game.
class IdleNotifierTool(
    private val context: Context,
    private val windowManager: WindowManager,
    private val capture: CaptureManager,
    private val setWindowCompact: (Int) -> Unit,
    private val hideWindow: () -> Unit
) {
    companion object {
        const val LOOK_EVERY_MS = 1_000L    // one look a second on average: as good as two on the recordings, for half the work
        // Each gap is picked at random within this much either side of a second. Looking exactly once a second can
        // line up with the game: Spin Flax waves the arms in a loop of exactly 1 s, so every look caught them in the
        // same spot and they seemed still; uneven gaps catch each animation at a different moment every time
        // (.scratch/afk-alert/animations.md). Never under 0.85 s, so each look still has one about a second old to
        // compare with (IdleWatch.COMPARE_AFTER is 0.8 s).
        const val LOOK_SPREAD_MS = 150L
        const val FIRST_LOOK_MS = 600L      // after Start, wait for the guide box to leave the screen
        const val WAKE_AHEAD_MS = 200L      // the capture rests between looks and starts again this long before one
        const val RETRY_MS = 100L           // no fresh picture yet after waking: try again this much later,
        const val RETRIES = 3               // at most this many times (then the screen really hasn't changed)
        const val SILENT = "Silent"

        // The Wait slider: about how many seconds after you stop the alert goes off, from SHORTEST_WAIT to LONGEST_WAIT.
        // The detector itself takes about 5 (IdleWatch: half its 8 seconds of looks quiet); anything longer is waited on
        // top (IdleWatch.extraWait). The long end is for minigames like Tempoross, where you stand a few seconds between jobs.
        const val SHORTEST_WAIT = 5
        const val LONGEST_WAIT = 15
        const val DETECTOR_WAIT = 5

        // The alert sounds (made by the owner for the app, in res/raw), in the order the Sound dropdown lists
        // them. "Silent" comes last: no sound, and the vibration is always on so the alert can't be missed.
        val SOUNDS = listOf(
            "Ringtone" to R.raw.idle_ringtone,
            "Plucked Strings" to R.raw.idle_plucked_strings,
            "One Pluck Man" to R.raw.idle_one_pluck_man,
            "Wood Mallet" to R.raw.idle_wood_mallet,
            "Big Tuba" to R.raw.idle_big_tuba,
            "Grocery Store" to R.raw.idle_grocery_store,
            "Island Dream" to R.raw.idle_island_dream,
            "Alien Flyby" to R.raw.idle_alien_flyby
        )
    }

    private enum class Mode { READY, SETTING_ZOOM, WATCHING }

    private val ui = Ui(context)
    private val handler = Handler(Looper.getMainLooper())
    private val prefs = context.getSharedPreferences("idle_notifier", Context.MODE_PRIVATE)
    private val watch = IdleWatch()
    private var mode = Mode.READY
    private var destroyed = false
    private var startedAt = ""     // the time of day watching started, and of the last alert (shown in the window)
    private var alerts = 0
    private var lastAlertAt = ""
    private var gameWasHidden = false

    private var holder: LinearLayout? = null
    private var guide: GuideBoxView? = null

    private val captureStopped: () -> Unit = {
        if (!destroyed && mode != Mode.READY) stoppedByItself("Screen capture is off. Tap Set up to turn it back on.")
    }

    init {
        capture.addStopListener(captureStopped)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // The chosen sound's name (one of SOUNDS, or SILENT)
    private var soundName: String
        get() = prefs.getString("sound_name", null)?.takeIf { n -> n == SILENT || SOUNDS.any { it.first == n } } ?: SOUNDS[0].first
        set(v) = prefs.edit { putString("sound_name", v) }
    private var vibrateSetting: Boolean
        get() = prefs.getBoolean("vibrate", true)
        set(v) = prefs.edit { putBoolean("vibrate", v) }
    // How loud the sound plays, 5 to 100 (% of the phone's media volume)
    private var volume: Int
        get() = prefs.getInt("volume", 100).coerceIn(5, 100)
        set(v) = prefs.edit { putInt("volume", v) }
    private val vibrateOn get() = vibrateSetting || soundName == SILENT
    // The Wait slider's seconds (5 to start with: the owner's choice, 17 November 2026, like RuneLite's 5 s)
    private var waitSeconds: Int
        get() = prefs.getInt("wait_seconds", SHORTEST_WAIT).coerceIn(SHORTEST_WAIT, LONGEST_WAIT)
        set(v) = prefs.edit { putInt("wait_seconds", v) }

    private var player: MediaPlayer? = null   // the sound playing now, if any

    // ---------------- Window contents ----------------

    private var note: String? = null   // a message under the buttons (why it stopped, and so on)
    private var soundListOpen = false  // the Sound dropdown is open

    fun buildView(): View {
        holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        show()
        // (in a scroll box, so the open Sound list can be scrolled in a small window)
        return ScrollView(context).apply {
            setBackgroundColor(Palette.SURFACE)
            addView(holder)
        }
    }

    // (buttons share a row, so they drop the usual button's smallest width, and long words can wrap)
    private fun button(label: String, style: Ui.ButtonStyle = Ui.ButtonStyle.PLAIN, onClick: () -> Unit) =
        ui.button(label, style, onClick).apply {
            minWidth = 0
            setPadding(dp(6), dp(4), dp(6), dp(4))
        }

    private fun text(words: String, size: Float = 12f) = ui.text(words, size).apply {
        setPadding(0, dp(4), 0, dp(4))
    }

    // The Sound dropdown's button: the chosen sound, and an arrow that points up while the list is open
    private fun soundButton() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        minimumHeight = dp(Ui.TAP_SIZE)
        setPadding(dp(6), dp(4), dp(6), dp(4))
        ui.press(this, Palette.RAISED, 8)
        addView(ui.text("Sound: $soundName", 13f, Palette.TEXT, 600).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.icon("down", Palette.TEXT, 16).apply { if (soundListOpen) rotation = 180f })
        setOnClickListener { soundListOpen = !soundListOpen; show() }
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

    // Draws the window for the current mode
    private fun show() {
        val h = holder ?: return
        h.removeAllViews()
        when (mode) {
            Mode.READY -> {
                h.addView(ui.note("Buzzes once when your character stops working, so you can put the phone down while " +
                    "skilling. It watches your character in the middle of the screen; it never taps anything."), full())
                h.addView(button("Set up", Ui.ButtonStyle.GOLD) { setUp() }, full())
                h.addView(row(
                    soundButton(),
                    // ticked in gold while it's on, faded while it's off
                    ui.toggle(if (soundName == SILENT) "Vibrate (always)" else "Vibrate",
                        soundName == SILENT || vibrateSetting) {
                        // with Silent chosen the vibration can't be turned off (the alert would do nothing)
                        if (soundName != SILENT) { vibrateSetting = !vibrateSetting; show() }
                    }
                ), full())
                if (soundListOpen) h.addView(soundList(), full())
                if (soundName != SILENT) h.addView(volumeSlider(), full())
                h.addView(waitSlider(), full())
                h.addView(button("Test alert") { alert() }, full())
            }
            Mode.SETTING_ZOOM -> {
                // (the window is shrunk to these few buttons in a corner, out of the way of the box)
                // (no gap above the words, and short words: the taller buttons mustn't make this any bigger)
                h.addView(ui.text("Zoom until you're as tall as the box.", 12f),
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                h.addView(row(button("Start", Ui.ButtonStyle.GOLD) { startWatching() }, button("Cancel") { stop(null) }), full())
            }
            Mode.WATCHING -> {
                h.addView(text("Watching since $startedAt. It buzzes after about $waitSeconds seconds of standing still, " +
                    "then waits until you've worked again."), full())
                if (alerts > 0) h.addView(text("Alerted $alerts time" + (if (alerts == 1) "" else "s") + ", last at $lastAlertAt."), full())
                h.addView(ui.note("If you zoom in or out, tap Stop and set it up again."), full())
                h.addView(button("Stop", Ui.ButtonStyle.DANGER) { stop(null) }, full())
            }
        }
        // (a problem gets the warning sign; "Waiting for permission" is only news)
        note?.let { h.addView(if (it.startsWith("Waiting")) text(it) else ui.banner(it), full()) }
    }

    // ---------------- Setting the zoom ----------------

    private fun setUp() {
        note = null
        soundListOpen = false
        stopSound()
        if (!capture.isActive) {
            note = "Waiting for screen-capture permission..."
            show()
            capture.request { ok ->
                if (destroyed) return@request
                if (ok) setUp() else { note = "Couldn't turn on screen capture: ${capture.lastError}."; show() }
            }
            return
        }
        mode = Mode.SETTING_ZOOM
        setWindowCompact(BubbleService.COMPACT_TOP_LEFT)
        showGuide()
        show()
    }

    private fun showGuide() {
        hideGuide()
        val (w, h) = fullScreenSize(windowManager)
        val textPx = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, context.resources.displayMetrics)
        val view = GuideBoxView(context, IdleWatch.guideBox(w, h), dp(2).toFloat(), textPx)
        val area = Rect(0, 0, w, h)
        val params = guideWindowParams(area)
        try {
            windowManager.addView(view, params)
            view.post { if (view.isAttachedToWindow) lineUpGuide(view, params, area, windowManager) }
            guide = view
        } catch (e: Exception) {
            // couldn't draw over the screen: the window still explains what to do
        }
    }

    private fun hideGuide() {
        guide?.let { try { windowManager.removeView(it) } catch (e: Exception) { } }
        guide = null
    }

    // ---------------- Watching ----------------

    private fun clock(): String = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date())

    private fun startWatching() {
        hideGuide()   // (it would be seen by the capture)
        mode = Mode.WATCHING
        note = null
        alerts = 0
        startedAt = clock()
        watch.extraWait = (waitSeconds - DETECTOR_WAIT).toDouble()
        watch.start()
        capture.matchScreenSize()
        show()
        hideWindow()   // out of the way; tap the bubble to see how it's going, or to stop
        handler.removeCallbacks(lookRunnable)
        handler.removeCallbacks(wakeRunnable)
        retries = 0
        handler.postDelayed(lookRunnable, FIRST_LOOK_MS)
    }

    // About once a second (the gaps are a little uneven, see LOOK_SPREAD_MS): look, let the capture rest, and wake it
    // just before the next look (battery: in between, Android isn't copying every frame of the screen for nothing)
    private val lookRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.WATCHING || destroyed) return
            if (!lookOnce()) {
                // no fresh picture yet (the capture only just woke): try again shortly
                handler.postDelayed(this, RETRY_MS)
                return
            }
            capture.rest()
            val gap = LOOK_EVERY_MS + Random.nextLong(-LOOK_SPREAD_MS, LOOK_SPREAD_MS + 1)
            handler.postDelayed(wakeRunnable, gap - WAKE_AHEAD_MS)
            handler.postDelayed(this, gap)
        }
    }

    private val wakeRunnable = Runnable {
        if (mode == Mode.WATCHING && !destroyed && !capture.gameHidden && screenOn()) capture.wake()
    }

    private var retries = 0

    private fun screenOn() = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true

    // One look. False when there was no fresh picture and it's worth waiting a moment for one.
    private fun lookOnce(): Boolean {
        // Only the game is shared and you've left it, or the screen is off: nothing can be seen, so the looks so far
        // are forgotten when it can be seen again (an alert that already went off stays gone)
        if (capture.gameHidden || !screenOn()) {
            gameWasHidden = true
            return true
        }
        if (gameWasHidden) { gameWasHidden = false; watch.forget() }
        val time = SystemClock.uptimeMillis() / 1000.0
        val seen = capture.sample { watch.look(it, time) }
        if (seen == null && retries < RETRIES) { retries++; return false }
        retries = 0
        val result = seen ?: watch.lookSame(time)
        if (result.alert) {
            alerts++
            lastAlertAt = clock()
            alert()
            show()
        }
        return true
    }

    // The Sound dropdown's list: every sound, then Silent, with a tick by the chosen one. Tapping one chooses it and
    // plays it (Silent gives one buzz); the list stays open to try others, and the Sound button closes it.
    private fun soundList() = ui.card {
        setPadding(0, 0, 0, 0)   // (the rows reach the card's edges, so the whole row can be tapped)
        for (name in SOUNDS.map { it.first } + SILENT) {
            val chosen = name == soundName
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.TAP_SIZE)
                setPadding(dp(10), 0, dp(10), 0)
                ui.press(this, null, 8)
                // (the tick's space is kept on every row, so the names line up)
                addView(ui.icon("check", Palette.ACCENT, 16).apply { visibility = if (chosen) View.VISIBLE else View.INVISIBLE })
                addView(ui.text(name, 14f, if (chosen) Palette.ACCENT else Palette.TEXT, if (chosen) 700 else 500).apply {
                    setPadding(dp(8), 0, 0, 0)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                setOnClickListener { chooseSound(name) }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
    }

    // The volume slider: "Volume: 80%" and a bar to drag. The sound plays when you let go, to hear how loud it is.
    private fun volumeSlider() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val label = text("Volume: $volume%", 13f)
        addView(label, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ui.slider(SeekBar(context)).apply {
            max = 95                     // 0..95 on the bar = 5..100%
            progress = volume - 5
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) { volume = value + 5; label.text = "Volume: $volume%" }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) { playSound() }
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // How long you stand still before the alert, 5 to 15 seconds, a second at a time
    private fun waitSlider() = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val label = text("Wait: $waitSeconds s", 13f)
        addView(label, LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ui.slider(SeekBar(context)).apply {
            max = LONGEST_WAIT - SHORTEST_WAIT     // 0..10 on the bar = 5..15 s
            progress = waitSeconds - SHORTEST_WAIT
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) { waitSeconds = value + SHORTEST_WAIT; label.text = "Wait: $waitSeconds s" }
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun chooseSound(name: String) {
        soundName = name
        show()
        if (name == SILENT) { stopSound(); vibrate() } else playSound()
    }

    // Plays the chosen sound once, at the slider's share of the phone's media volume: the same volume as the game, so
    // it's heard whenever the game is (players often keep the phone on vibrate, which mutes notification sounds). A new sound stops the one before it.
    private fun playSound() {
        stopSound()
        val res = SOUNDS.firstOrNull { it.first == soundName }?.second ?: return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        try {
            player = MediaPlayer.create(context, res, attributes, 0)?.apply {
                val level = volume / 100f
                setVolume(level, level)
                setOnCompletionListener { done -> done.release(); if (player === done) player = null }
                start()
            }
        } catch (e: Exception) {
            stopSound()   // couldn't play it: the vibration still works
        }
    }

    private fun stopSound() {
        player?.let { try { it.stop() } catch (e: Exception) { }; it.release() }
        player = null
    }

    // The alert: the chosen sound and the vibration (the vibration can be turned off, except with Silent)
    private fun alert() {
        playSound()
        if (vibrateOn) vibrate()
    }

    // three short buzzes, easy to tell apart from a message
    private fun vibrate(pattern: LongArray = longArrayOf(0, 250, 150, 250, 150, 250)) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
    }

    // Watching ended without you tapping Stop: one long buzz, since the phone is probably down and the window closed
    private fun stoppedByItself(message: String) {
        val wasWatching = mode == Mode.WATCHING
        stop(message)
        if (wasWatching) vibrate(longArrayOf(0, 900))
    }

    private fun stop(message: String?) {
        mode = Mode.READY
        soundListOpen = false
        handler.removeCallbacks(lookRunnable)
        handler.removeCallbacks(wakeRunnable)
        hideGuide()
        setWindowCompact(BubbleService.COMPACT_OFF)
        note = message
        show()
    }

    // ---------------- Called by BubbleService ----------------

    fun onRotated() {
        when (mode) {
            Mode.SETTING_ZOOM -> showGuide()   // the box moves to the new middle
            // the box was fitted to the other way round (and the capture still has the old size): set it up again
            Mode.WATCHING -> stoppedByItself("The phone turned. Tap Set up to fit the box again.")
            Mode.READY -> {}
        }
    }

    // Closing the window while setting the zoom puts the box away; while watching, it keeps watching
    fun onWindowClosed() {
        if (mode == Mode.SETTING_ZOOM) stop(null)
    }

    // Still working with its window closed (the bubble waits for this before rebuilding the tools for dark mode)
    fun isWatching() = mode != Mode.READY

    fun destroy() {
        // (the bubble closing, or the tools rebuilt after a dark mode or text size change in Settings)
        if (mode == Mode.WATCHING) vibrate(longArrayOf(0, 900))
        destroyed = true
        capture.removeStopListener(captureStopped)
        mode = Mode.READY
        handler.removeCallbacksAndMessages(null)
        hideGuide()
        stopSound()
    }

    // The guide box over the game, with the instruction above it. Taps go straight through it.
    private class GuideBoxView(context: Context, private val box: IntArray, private val line: Float, textPx: Float) : View(context) {
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = line * 3; color = Color.BLACK }
        private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = line; color = Color.YELLOW }
        private val words = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.YELLOW; textSize = textPx; textAlign = Paint.Align.CENTER
            setShadowLayer(4f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            val l = box[0].toFloat(); val t = box[1].toFloat(); val r = box[2].toFloat(); val b = box[3].toFloat()
            canvas.drawRect(l, t, r, b, dark)
            canvas.drawRect(l, t, r, b, light)
            val cx = (l + r) / 2
            canvas.drawText("Zoom until your character", cx, t - words.textSize * 1.6f, words)
            canvas.drawText("is as tall as this box", cx, t - words.textSize * 0.5f, words)
        }
    }
}

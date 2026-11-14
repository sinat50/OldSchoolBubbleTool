package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
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
import android.widget.TextView
import androidx.core.content.edit

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
        const val LOOK_EVERY_MS = 1_000L    // one look a second: as good as two on the recordings, for half the work
        const val FIRST_LOOK_MS = 600L      // after Start, wait for the guide box to leave the screen
        const val WAKE_AHEAD_MS = 200L      // the capture rests between looks and starts again this long before one
        const val RETRY_MS = 100L           // no fresh picture yet after waking: try again this much later,
        const val RETRIES = 3               // at most this many times (then the screen really hasn't changed)
        const val SILENT = "Silent"

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

    private var player: MediaPlayer? = null   // the sound playing now, if any

    // ---------------- Window contents ----------------

    private var note: String? = null   // a message under the buttons (why it stopped, and so on)
    private var soundListOpen = false  // the Sound dropdown is open

    fun buildView(): View {
        holder = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.PARCHMENT)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        show()
        // (in a scroll box, so the open Sound list can be scrolled in a small window)
        return ScrollView(context).apply {
            setBackgroundColor(Palette.PARCHMENT)
            addView(holder)
        }
    }

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(8), dp(8), dp(8))
        background = GradientDrawable().apply {
            setColor(Palette.BUTTON_BROWN)
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun text(words: String, size: Float = 12f) = TextView(context).apply {
        text = words
        textSize = size
        setTextColor(Palette.DARK_BROWN)
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun full() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = dp(4) }

    private fun row(vararg views: View) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEachIndexed { i, v ->
            addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
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
                h.addView(text("Buzzes once when your character stops working, so you can put the phone down while " +
                    "skilling. It watches your character in the middle of the screen; it never taps anything."), full())
                h.addView(button("Set up") { setUp() }, full())
                h.addView(row(
                    button("Sound: $soundName " + if (soundListOpen) "▴" else "▾") { soundListOpen = !soundListOpen; show() },
                    button("Vibrate: " + when {
                        soundName == SILENT -> "always on"
                        vibrateSetting -> "on"
                        else -> "off"
                    }) {
                        // with Silent chosen the vibration can't be turned off (the alert would do nothing)
                        if (soundName != SILENT) { vibrateSetting = !vibrateSetting; show() }
                    }
                ), full())
                if (soundListOpen) h.addView(soundList(), full())
                if (soundName != SILENT) h.addView(volumeSlider(), full())
                h.addView(button("Test alert") { alert() }, full())
            }
            Mode.SETTING_ZOOM -> {
                // (the window is shrunk to these few buttons in a corner, out of the way of the box)
                h.addView(text("Zoom until your character is as tall as the box.", 11f), full())
                h.addView(row(button("Start") { startWatching() }, button("Cancel") { stop(null) }), full())
            }
            Mode.WATCHING -> {
                h.addView(text("Watching since $startedAt. It buzzes after about 8 seconds of standing still, " +
                    "then waits until you've worked again."), full())
                if (alerts > 0) h.addView(text("Alerted $alerts time" + (if (alerts == 1) "" else "s") + ", last at $lastAlertAt."), full())
                h.addView(text("If you zoom in or out, tap Stop and set it up again."), full())
                h.addView(button("Stop") { stop(null) }, full())
            }
        }
        note?.let { h.addView(text(it), full()) }
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
        watch.start()
        capture.matchScreenSize()
        show()
        hideWindow()   // out of the way; tap the bubble to see how it's going, or to stop
        handler.removeCallbacks(lookRunnable)
        handler.removeCallbacks(wakeRunnable)
        retries = 0
        handler.postDelayed(lookRunnable, FIRST_LOOK_MS)
    }

    // Once a second: look, let the capture rest, and wake it just before the next look (battery: in between,
    // Android isn't copying every frame of the screen for nothing)
    private val lookRunnable = object : Runnable {
        override fun run() {
            if (mode != Mode.WATCHING || destroyed) return
            if (!lookOnce()) {
                // no fresh picture yet (the capture only just woke): try again shortly
                handler.postDelayed(this, RETRY_MS)
                return
            }
            capture.rest()
            handler.postDelayed(wakeRunnable, LOOK_EVERY_MS - WAKE_AHEAD_MS)
            handler.postDelayed(this, LOOK_EVERY_MS)
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
    private fun soundList() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(Palette.PARCHMENT)
            setStroke(dp(1), Palette.DARK_BROWN)
            cornerRadius = dp(6).toFloat()
        }
        for (name in SOUNDS.map { it.first } + SILENT) {
            val chosen = name == soundName
            addView(TextView(context).apply {
                text = (if (chosen) "✓  " else "     ") + name
                textSize = 14f
                setTextColor(Palette.DARK_BROWN)
                if (chosen) setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(10), dp(7), dp(10), dp(7))
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
        addView(SeekBar(context).apply {
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

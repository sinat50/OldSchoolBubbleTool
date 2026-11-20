package com.sinat.osrsbubbletool

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.net.toUri
import java.util.Calendar
import java.util.Date

// Farming Timers: tap "Start Timer" after a run and get a notification when it's ready.
class FarmingTool(private val context: Context) {

    companion object {
        private const val REFRESH_MS = 20_000L
        private const val UNDO_MS = 6000L   // how long Undo stays after stopping or restarting a timer
        private const val REMOVE_ASK_MS = 3000L   // how long the bin waits for its second tap
    }

    private val ui = Ui(context)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var list: LinearLayout
    private lateinit var addBar: LinearLayout
    private lateinit var scroll: ScrollView
    private var showCropsNext = false    // the crop list was just opened: bring it into view
    private var choosingCropFor = -1     // timer whose crop list is open
    private var addingTimer = false      // the list of timers to add is open
    private var showing = false

    // The last running timer stopped or restarted, for a few seconds: its Undo puts its time back
    private class Undo(val id: Int, val end: Long, val notified: Boolean, val words: String)
    private var undo: Undo? = null
    private val forgetUndo = Runnable { undo = null; if (showing) rebuild() }

    // Removing a timer takes two taps on its bin: the first turns the bin red and asks, for a few seconds
    private var removeAsk = -1
    private val forgetRemove = Runnable { removeAsk = -1; if (showing) rebuild() }

    private fun offerUndo(t: FarmingTimers.Timer, words: String) {
        undo = Undo(t.id, t.end, t.notified, words)
        handler.removeCallbacks(forgetUndo)
        handler.postDelayed(forgetUndo, UNDO_MS)
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (!showing) return
            rebuild()
            handler.postDelayed(this, REFRESH_MS)
        }
    }

    private fun dp(value: Int) = ui.dp(value)

    fun buildView(): View {
        list = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        addBar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(Ui.TAP_SIZE)
            ui.press(this, Palette.RAISED)
            setOnClickListener {
                addingTimer = !addingTimer
                rebuild()
            }
        }
        val window = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
            scroll = ScrollView(context).apply { addView(list) }
            addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(addBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        // refresh the countdowns only while the window is on screen
        window.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {
                showing = true
                handler.removeCallbacks(ticker)
                ticker.run()
            }
            override fun onViewDetachedFromWindow(v: View) {
                showing = false
                handler.removeCallbacks(ticker)
            }
        })
        rebuild()
        return window
    }

    fun onWindowClosed() {
        choosingCropFor = -1
        addingTimer = false
    }

    fun destroy() {
        showing = false
        handler.removeCallbacks(ticker)
    }

    // ---------------- Screen ----------------

    private fun rebuild() {
        list.removeAllViews()

        // Permission problems, shown only when there is one
        if (!FarmingTimers.notificationsAllowed(context)) {
            list.addView(warning("Notifications are off for this app. Tap to turn them on.") {
                openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
            })
        }
        if (!FarmingTimers.canUseExactAlarms(context)) {   // (always allowed before Android 12, so this only shows on 12+)
            list.addView(warning("Alerts may be a few minutes late. Tap and allow \"Alarms & reminders\" for on-time alerts.") {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${context.packageName}".toUri()))
            })
        }

        // "Herb run stopped. Undo", for a few seconds after a running timer was stopped or restarted
        undo?.let { u ->
            list.addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.TAP_SIZE)
                setPadding(dp(12), 0, dp(12), 0)
                ui.press(this, Palette.RAISED)
                addView(ui.text(u.words, 13f, Palette.TEXT, 500), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Undo", 13f, Palette.ACCENT, 700))
                setOnClickListener {
                    FarmingTimers.restore(context, u.id, u.end, u.notified)
                    undo = null
                    handler.removeCallbacks(forgetUndo)
                    rebuild()
                }
            })
        }

        // Adding timers: the choices come first, so they're in view straight away
        if (addingTimer) {
            list.addView(ui.text("Choose a timer", 12f, Palette.MUTED, 700).apply {
                setPadding(dp(12), dp(10), dp(12), dp(4))
            })
            for (kind in FarmingTimers.KINDS) list.addView(kindRow(kind))
        }

        val now = System.currentTimeMillis()
        val timers = FarmingTimers.load(context)
        timers.forEachIndexed { i, t -> list.addView(timerRow(t, now, striped = i % 2 == 1)) }
        if (timers.isEmpty()) list.addView(ui.text("No timers yet. Tap Add timer.", 14f, Palette.MUTED).apply {
            setPadding(dp(12), dp(14), dp(12), dp(14))
        })

        list.addView(offsetRow())

        addBar.removeAllViews()
        addBar.addView(ui.icon(if (addingTimer) "close" else "plus", Palette.ACCENT))
        addBar.addView(ui.text(if (addingTimer) "Cancel" else "Add timer", 14f, Palette.ACCENT, 600).apply {
            setPadding(dp(6), 0, 0, 0)
        })
    }

    private fun kindRow(kind: FarmingTimers.Kind) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.TAP_SIZE)
        setPadding(dp(12), 0, dp(12), 0)
        ui.press(this, Palette.BG)
        addView(ui.text(kind.label, 14f, Palette.TEXT, 500), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.icon("plus", Palette.ACCENT))
        setOnClickListener {
            FarmingTimers.add(context, kind.key)
            addingTimer = false
            rebuild()
        }
    }

    private fun timerRow(t: FarmingTimers.Timer, now: Long, striped: Boolean): View {
        val kind = t.kindInfo()
        val crop = t.cropInfo()
        val running = t.end > 0
        val ready = running && t.end <= now
        val hasCrops = kind.crops.size > 1
        val shade = if (striped) Palette.BG else Palette.SURFACE

        // The name and how it's going. On a timer with crops, tapping here opens the list of crops.
        val details = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(12), dp(6), dp(4), dp(6))
            addView(ui.oneLine(ui.text(kind.label, 14f, Palette.TEXT, 600)))
            if (hasCrops) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.oneLine(ui.text(crop.name, 12f, Palette.MUTED, 500)))
                addView(ui.icon("down", Palette.MUTED, 16))
            })
            addView(when {
                removeAsk == t.id -> ui.text("Tap again to remove", 12f, Palette.BAD, 600)
                ready -> LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(ui.icon("check", Palette.OK, 16))
                    addView(ui.text("Ready", 12f, Palette.OK, 600).apply { setPadding(dp(4), 0, 0, 0) })
                }
                running -> ui.oneLine(ui.text("${remaining(t.end - now)} left, ${clock(t.end)}", 12f, Palette.MUTED))
                else -> ui.text("Not running", 12f, Palette.MUTED)
            })
            if (hasCrops) {
                ui.press(this, null)
                setOnClickListener {
                    choosingCropFor = if (choosingCropFor == t.id) -1 else t.id
                    showCropsNext = choosingCropFor != -1
                    rebuild()
                }
            }
        }

        val top = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, dp(8), 0)
            addView(details, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // Gold only when something is waiting for you
            addView(ui.button(if (running) "Restart Timer" else "Start Timer", if (ready) Ui.ButtonStyle.GOLD else Ui.ButtonStyle.PLAIN) {
                // (restarting a timer that hadn't finished loses its time: it can be undone for a few seconds)
                if (running && !ready) offerUndo(t, "${kind.label} restarted.")
                FarmingTimers.start(context, t.id)
                choosingCropFor = -1
                rebuild()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { rightMargin = dp(8) })
            // stop or remove: quiet grey, so it doesn't draw the eye next to the main button (stopping can be undone)
            addView(ui.iconButton(if (running) "stop" else "trash", (if (running) "Stop " else "Remove ") + kind.label,
                if (removeAsk == t.id) Palette.BAD else Palette.MUTED) {
                handler.removeCallbacks(forgetRemove)
                when {
                    running -> {
                        offerUndo(t, "${kind.label} stopped.")
                        FarmingTimers.stop(context, t.id)
                        removeAsk = -1
                    }
                    removeAsk == t.id -> { FarmingTimers.remove(context, t.id); removeAsk = -1 }
                    else -> { removeAsk = t.id; handler.postDelayed(forgetRemove, REMOVE_ASK_MS) }
                }
                rebuild()
            })
        }

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(shade)
            addView(top)
            if (choosingCropFor == t.id && showCropsNext) {
                showCropsNext = false
                post { scroll.smoothScrollTo(0, this.top) }
            }
            if (choosingCropFor == t.id) {
                kind.crops.forEachIndexed { i, c -> addView(cropRow(c, kind, picked = i == t.crop) {
                    FarmingTimers.setCrop(context, t.id, i)
                    choosingCropFor = -1
                    rebuild()
                }) }
            }
        }
    }

    private fun cropRow(crop: FarmingTimers.Crop, kind: FarmingTimers.Kind, picked: Boolean, onPick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.TAP_SIZE)
        setPadding(dp(12), 0, dp(12), 0)
        ui.press(this, Palette.RAISED)
        val colour = if (picked) Palette.ACCENT else Palette.TEXT
        if (picked) addView(ui.icon("check", colour, 16))
        addView(ui.oneLine(ui.text(crop.name, 13f, colour, if (picked) 700 else 500)).apply {
            setPadding(if (picked) dp(6) else 0, 0, dp(6), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.text(duration(kind, crop), 12f, Palette.MUTED))
        setOnClickListener { onPick() }
    }

    private fun offsetRow(): View {
        val offset = FarmingTimers.tickOffset(context)
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text("Tick offset: $offset min", 13f, Palette.TEXT, 500), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.iconButton("minus", "Less tick offset", fill = Palette.RAISED) {
                FarmingTimers.setTickOffset(context, (offset - 1).coerceAtLeast(0))
                rebuild()
            })
            addView(ui.iconButton("plus", "More tick offset", fill = Palette.RAISED) {
                FarmingTimers.setTickOffset(context, (offset + 1).coerceAtMost(59))
                rebuild()
            }, LinearLayout.LayoutParams(dp(Ui.TAP_SIZE), dp(Ui.TAP_SIZE)).apply { leftMargin = dp(8) })
        }
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(12))
            addView(row)
            addView(ui.text("Leave at 0 unless your timers are always early. Changes apply to timers you start after.", 12f, Palette.MUTED).apply {
                setPadding(0, dp(4), dp(24), 0)   // (clear of the window's resize corner)
            })
        }
    }

    // ---------------- Helpers ----------------

    private fun openSettings(intent: Intent) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                "package:${context.packageName}".toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    private fun remaining(ms: Long): String {
        val minutes = (ms + 59_999) / 60_000
        val d = minutes / (60 * 24)
        val h = (minutes / 60) % 24
        val m = minutes % 60
        return when {
            d > 0 -> "${d}d ${h}h"
            h > 0 -> "${h}h ${m}m"
            else -> "${m}m"
        }
    }

    // "3:45 PM", or "Sun 3:45 PM" if it's not today
    private fun clock(time: Long): String {
        val date = Date(time)
        val timeText = android.text.format.DateFormat.getTimeFormat(context).format(date)
        val then = Calendar.getInstance().apply { timeInMillis = time }
        val today = Calendar.getInstance()
        val sameDay = then.get(Calendar.YEAR) == today.get(Calendar.YEAR) &&
            then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
        return if (sameDay) timeText
               else android.text.format.DateFormat.format("EEE", date).toString() + " " + timeText
    }

    private fun duration(kind: FarmingTimers.Kind, crop: FarmingTimers.Crop): String {
        val minutes = if (kind.fixedMinutes > 0) kind.fixedMinutes else crop.stageMinutes * (crop.stages - 1)
        return remaining(minutes * 60_000L)
    }

    private fun warning(text: String, onClick: () -> Unit) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.TAP_SIZE)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        ui.press(this, Palette.BG)
        addView(ui.icon("warning", Palette.CAUTION))
        addView(ui.text(text, 12f, Palette.TEXT).apply { setPadding(dp(10), 0, 0, 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        setOnClickListener { onClick() }
    }
}

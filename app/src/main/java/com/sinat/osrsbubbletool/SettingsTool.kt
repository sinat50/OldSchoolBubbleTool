package com.sinat.osrsbubbletool

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit

// The app's settings, saved on the phone. The bubble watches them (BubbleService.settingsChanged), so a change
// made on the app's screen reaches the bubble and its tools straight away.
object AppSettings {
    private const val PREFS = "settings"
    const val DARK_MODE = "dark_mode"          // OFF, ON or MATCH_PHONE
    const val TEXT_SIZE = "web_text_size"      // web page text size, in percent of normal
    const val TOOL_TEXT_SIZE = "tool_text_size" // the tools' own text size, in percent of normal
    const val BUBBLE_SIZE = "bubble_size"      // the bubble's size in dp
    const val BUBBLE_FADE = "bubble_fade"      // the bubble fades while you're not using it
    const val LONG_PRESS = "long_press_ms"     // how long to hold the bubble for the menu
    const val VIBRATE = "vibrate"              // a little buzz when the menu opens
    const val WINDOW_WIDTH = "window_width"    // tool windows' width in landscape, in hundredths of an inch
    const val HIDDEN_TOOLS = "hidden_tools"    // tools left out of the menu (their names)
    const val FAVOURITE_TOOLS = "favourite_tools"  // the menu's favourites, in order (see Favourites)
    const val CHECK_UPDATES = "check_updates"  // look for a newer version when the app opens
    const val WINDOWS_RESET = "windows_reset"  // changed when "Reset windows" is tapped, so the bubble hears of it
    const val OFF = 0
    const val ON = 1
    const val MATCH_PHONE = 2
    const val TEXT_NORMAL = 100
    const val TEXT_MIN = 70
    const val TEXT_MAX = 200
    const val TEXT_STEP = 10
    const val TOOL_TEXT_MIN = 80
    const val TOOL_TEXT_MAX = 150

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun darkMode(context: Context) = prefs(context).getInt(DARK_MODE, MATCH_PHONE)

    // Whether the app should be dark right now (with "Match phone", that's whatever the phone is set to)
    fun isDark(context: Context) = when (darkMode(context)) {
        ON -> true
        MATCH_PHONE -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        else -> false
    }

    fun textSize(context: Context) = prefs(context).getInt(TEXT_SIZE, TEXT_NORMAL).coerceIn(TEXT_MIN, TEXT_MAX)
    fun toolTextSize(context: Context) = prefs(context).getInt(TOOL_TEXT_SIZE, TEXT_NORMAL).coerceIn(TOOL_TEXT_MIN, TOOL_TEXT_MAX)
    fun bubbleSizeDp(context: Context) = prefs(context).getInt(BUBBLE_SIZE, 36)
    fun bubbleFades(context: Context) = prefs(context).getBoolean(BUBBLE_FADE, false)
    fun longPressMs(context: Context) = prefs(context).getInt(LONG_PRESS, 600).toLong()
    fun vibrates(context: Context) = prefs(context).getBoolean(VIBRATE, true)
    fun windowWidthInches(context: Context) = prefs(context).getInt(WINDOW_WIDTH, 200) / 100f
    fun hiddenTools(context: Context): Set<String> = prefs(context).getStringSet(HIDDEN_TOOLS, emptySet()) ?: emptySet()
    fun favourites(context: Context) = Favourites.read(prefs(context).getString(FAVOURITE_TOOLS, null))
    fun setFavourites(context: Context, names: List<String>) = prefs(context).edit { putString(FAVOURITE_TOOLS, Favourites.write(names)) }
    fun checksUpdates(context: Context) = prefs(context).getBoolean(CHECK_UPDATES, true)
}

// The Settings page: the bubble's Settings window, and the Settings screen in the app. Both show the same
// settings. onRedraw: the page needs building again (dark mode or the tools' text size changed; in the bubble,
// the bubble rebuilds its windows on its own).
// wide: there's room for the tool list in two columns.
class SettingsTool(private val context: Context, private val wide: Boolean = false, private val onRedraw: () -> Unit = {}) {

    companion object {
        private var savedScroll = 0   // where the bubble's Settings window was scrolled to (kept when it's rebuilt)
    }

    private val ui = Ui(context)
    private fun dp(v: Int) = ui.dp(v)
    private val prefs = AppSettings.prefs(context)

    // For the bubble's window: the settings on a scrolling page
    fun buildView(): View = ScrollView(context).apply {
        setBackgroundColor(Palette.SURFACE)
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), dp(24))
            addSettings(this)
        })
        val saved = savedScroll   // (not scrollY: in here that would be the page's own)
        post { scrollTo(0, saved) }
        setOnScrollChangeListener { _, _, y, _, _ -> savedScroll = y }
    }

    // The settings themselves, added to a column (the app's screen adds its own title and Back button)
    fun addSettings(column: LinearLayout) = column.apply {
        // ---- Look ----
        section("Look")
        heading("Dark mode")
        addView(choices(listOf(AppSettings.OFF to "Off", AppSettings.ON to "On", AppSettings.MATCH_PHONE to "Match phone"),
            AppSettings.darkMode(context), wide = AppSettings.MATCH_PHONE) {
            prefs.edit { putInt(AppSettings.DARK_MODE, it) }
            onRedraw()
        }, full(4))
        note("Websites in the tools (the Wiki, GE Prices...) turn dark too, if they have a dark look of their own.")

        heading("Tool text size")
        addView(stepper({ AppSettings.toolTextSize(context) }, AppSettings.TOOL_TEXT_MIN, AppSettings.TOOL_TEXT_MAX) {
            prefs.edit { putInt(AppSettings.TOOL_TEXT_SIZE, it) }
            onRedraw()
        }, full(4))
        note("The writing in the app's own tools, like the Quest Helper and Notepad.")

        heading("Web page text size")
        addView(stepper({ AppSettings.textSize(context) }, AppSettings.TEXT_MIN, AppSettings.TEXT_MAX) {
            prefs.edit { putInt(AppSettings.TEXT_SIZE, it) }
        }, full(4))
        note("For the Wiki, DPS Calculator, XP Calculator, GE Prices, Shooting Star Tracker and Tears of Guthix Worlds. You can also pinch a page to zoom it.")

        // ---- Bubble ----
        section("Bubble")
        heading("Size")
        addView(choices(listOf(30 to "Small", 36 to "Normal", 46 to "Large"), AppSettings.bubbleSizeDp(context)) {
            prefs.edit { putInt(AppSettings.BUBBLE_SIZE, it) }
        }, full(4))
        heading("Fade when not in use")
        addView(onOff(AppSettings.bubbleFades(context)) { prefs.edit { putBoolean(AppSettings.BUBBLE_FADE, it) } }, full(4))
        note("The bubble turns see-through a few seconds after you last touched it, so it hides less of the game.")
        heading("Hold time for the menu")
        addView(choices(listOf(400 to "Short", 600 to "Normal", 900 to "Long"), AppSettings.longPressMs(context).toInt()) {
            prefs.edit { putInt(AppSettings.LONG_PRESS, it) }
        }, full(4))
        heading("Vibrate when the menu opens")
        addView(onOff(AppSettings.vibrates(context)) { prefs.edit { putBoolean(AppSettings.VIBRATE, it) } }, full(4))

        // ---- Windows ----
        section("Windows")
        heading("Width in landscape")
        addView(choices(listOf(160 to "Narrow", 200 to "Normal", 250 to "Wide"), (AppSettings.windowWidthInches(context) * 100).toInt()) {
            prefs.edit { putInt(AppSettings.WINDOW_WIDTH, it) }
        }, full(4))
        note("For windows you haven't resized yourself.")
        val reset = ui.button("Reset all window sizes and places") {}
        reset.setOnClickListener {
            context.getSharedPreferences("window_sizes", Context.MODE_PRIVATE).edit { clear() }
            prefs.edit { putLong(AppSettings.WINDOWS_RESET, System.currentTimeMillis()) }
            reset.text = "Windows reset"
        }
        addView(reset, full(10))

        // ---- Menu ----
        section("Tools in the menu")
        note("Untick the tools you don't use to keep the bubble's menu short.")
        addView(toolTicks(), full(4))

        // ---- Updates ----
        section("Updates")
        heading("Check for a new version when the app opens")
        addView(onOff(AppSettings.checksUpdates(context)) { prefs.edit { putBoolean(AppSettings.CHECK_UPDATES, it) } }, full(4))
        note("Asks GitHub for the latest version number. Nothing about you is sent.")
    }

    // The tools, ticked when they're in the menu (two columns when there's room)
    private fun toolTicks(): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply { setColor(Palette.RAISED); cornerRadius = dp(8).toFloat() }
        clipToOutline = true
        val tools = BubbleService.Tool.entries.filter { it.inMenu }
        for (row in tools.chunked(if (wide) 2 else 1)) {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                for (tool in row) addView(toolTick(tool), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (row.size < (if (wide) 2 else 1)) addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
            })
        }
    }

    private fun toolTick(tool: BubbleService.Tool) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(Ui.TAP_SIZE)
        setPadding(dp(12), 0, dp(12), 0)
        ui.press(this, null)
        addView(ui.icon(tool.icon))
        addView(ui.oneLine(ui.text(tool.label, 13f, Palette.TEXT, 500)).apply { setPadding(dp(10), 0, dp(6), 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val tick = ui.icon("check", Palette.ACCENT)
        fun look() {
            val shown = tool.name !in AppSettings.hiddenTools(context)
            tick.alpha = if (shown) 1f else 0f
            contentDescription = tool.label + if (shown) ", in the menu" else ", left out of the menu"
        }
        addView(tick)
        look()
        setOnClickListener {
            val hidden = AppSettings.hiddenTools(context).toMutableSet()
            if (!hidden.remove(tool.name)) hidden.add(tool.name)
            prefs.edit { putStringSet(AppSettings.HIDDEN_TOOLS, hidden) }
            look()
        }
    }

    // A row of options to pick one of; the picked one has gold writing, a tick and a gold outline
    private fun choices(options: List<Pair<Int, String>>, current: Int, wide: Int? = null, onPick: (Int) -> Unit): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            background = GradientDrawable().apply { setColor(Palette.RAISED); cornerRadius = dp(8).toFloat() }
            var picked = current
            val cells = options.map { (_, name) -> ui.oneLine(ui.text(name, 13f)).apply { gravity = Gravity.CENTER; minHeight = dp(Ui.TAP_SIZE) } }
            fun look() = options.forEachIndexed { i, (value, name) -> ui.pickLook(cells[i], name, value == picked) }
            options.forEachIndexed { i, (value, _) ->
                cells[i].setOnClickListener {
                    picked = value
                    look()
                    onPick(value)
                }
                addView(cells[i], LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, if (value == wide) 1.6f else 1f))
            }
            look()
        }

    private fun onOff(on: Boolean, onChange: (Boolean) -> Unit) =
        choices(listOf(0 to "Off", 1 to "On"), if (on) 1 else 0) { onChange(it == 1) }

    // − 100% +, side by side
    private fun stepper(get: () -> Int, min: Int, max: Int, onChange: (Int) -> Unit): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val sizeText = ui.text("", 14f, Palette.TEXT, 600).apply { gravity = Gravity.CENTER }
        fun showSize() {
            val size = get()
            sizeText.text = if (size == AppSettings.TEXT_NORMAL) "$size% (normal)" else "$size%"
        }
        fun change(by: Int) {
            val size = (get() + by).coerceIn(min, max)
            if (size == get()) return
            onChange(size)
            showSize()
        }
        showSize()
        addView(ui.iconButton("minus", "Smaller", fill = Palette.RAISED) { change(-AppSettings.TEXT_STEP) })
        addView(sizeText, LinearLayout.LayoutParams(dp(128), ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(ui.iconButton("plus", "Bigger", fill = Palette.RAISED) { change(AppSettings.TEXT_STEP) })
    }

    // (in quiet grey, like the bubble menu's headings: gold is kept for main buttons and picked options)
    private fun LinearLayout.section(title: String) = addView(ui.heading(title), full(20))

    private fun LinearLayout.heading(text: String) = addView(ui.text(text, 13f, Palette.TEXT, 600), full(12))

    private fun LinearLayout.note(text: String) = addView(ui.text(text, 12f, Palette.MUTED), full(4))

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}

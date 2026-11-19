package com.sinat.osrsbubbletool

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit

// Hunters' Rumours: pick your tier (and guild hunter), pick the rumour you're on, and see where the
// creature is, how to get there and what to bring. Everything is built in, so it works offline.
class HunterRumourTool(private val context: Context, private val openSync: () -> Unit = {}) {

    private val ui = Ui(context)
    private val prefs = context.getSharedPreferences("hunter_rumours", Context.MODE_PRIVATE)
    private val sync = AccountSync(context)   // your Hunter level, if you've scanned your account (Bubble Sync)
    private lateinit var holder: FrameLayout

    // What you picked, remembered for next time
    private var tier: String
        get() = prefs.getString("tier", null)?.takeIf { it in HunterRumours.TIERS } ?: "Novice"
        set(v) { prefs.edit { putString("tier", v) } }
    private var hunter: HunterRumours.Hunter
        get() = HunterRumours.huntersFor(tier).let { list ->
            list.firstOrNull { it.name == prefs.getString("hunter_$tier", null) } ?: list.first()
        }
        set(v) { prefs.edit { putString("hunter_$tier", v.name) } }
    private var current: HunterRumours.Rumour?   // the rumour you're on
        get() = prefs.getString("current", null)?.let { HunterRumours.byName(it) }
        set(v) { prefs.edit { putString("current", v?.name) } }

    private var showing: HunterRumours.Rumour? = null   // null = the list

    private fun dp(v: Int) = ui.dp(v)

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(Palette.SURFACE) }
        showing = null
        show()
        // back from the Bubble Sync tool: redraw so the tag and faded rumours are up to date
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { if (syncState() != shownSyncState) show() }
            override fun onViewDetachedFromWindow(v: View) {}
        })
        return holder
    }

    // The window's ◀ button: from a rumour back to the list
    fun goBack() {
        if (showing != null) { showing = null; show() }
    }

    private var shownSyncState: String? = null
    private fun syncState() = sync.state

    private fun show() {
        shownSyncState = syncState()
        holder.removeAllViews()
        val r = showing
        holder.addView(if (r == null) list() else detail(r))
    }

    private fun hunterLevel(): Int? = sync.cached?.level("Hunter")

    // ---------------- The list ----------------

    private fun list(): View = scrolling {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(android.view.View(context), LinearLayout.LayoutParams(0, 1, 1f))   // (the window's top bar has the name)
            addView(SyncBadge.view(context, sync) { openSync() })
        }, full())

        // Your current rumour, one tap away
        current?.let { cur ->
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.TAP_SIZE)
                setPadding(dp(12), dp(6), dp(8), dp(6))
                ui.press(this, Palette.GOLD, 8)
                addView(ui.text("Your rumour: ${cur.name}", 13f, Palette.ON_GOLD, 700),
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.text("Open", 12f, Palette.ON_GOLD, 600))
                addView(ui.icon("next", Palette.ON_GOLD, 16))
                setOnClickListener { showing = cur; show() }
            }, full(6))
        }

        // Tier, then which hunter (Adept and Expert each have two, with different rumours)
        addView(ui.heading("Your tier"), full(10))
        addView(choiceRow(HunterRumours.TIERS, tier) { tier = it; show() }, full(3))
        val hunters = HunterRumours.huntersFor(tier)
        if (hunters.size > 1) {
            addView(ui.heading("Guild hunter"), full(8))
            addView(choiceRow(hunters.map { it.label.substringAfterLast(' ') }, hunter.label.substringAfterLast(' ')) { name ->
                hunters.firstOrNull { it.label.endsWith(name) }?.let { hunter = it; show() }
            }, full(3))
        }
        val h = hunter
        val level = hunterLevel()
        addView(ui.note("${h.label} · needs ${h.level} Hunter" +
            (if (h.tier == "Master") " and At First Light" else "") +
            (if (h == HunterRumours.Hunter.GILMAN) " · can give every rumour" else "")), full(4))

        addView(ui.heading("Tap the rumour you're on:"), full(10))
        val cur = current
        for (r in HunterRumours.ALL.filter { h in it.hunters }) {
            val tooHigh = level != null && level < r.level
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(Ui.TAP_SIZE)
                setPadding(dp(12), dp(6), dp(12), dp(6))
                // the rumour you're on stands out by its lighter shade and a tick
                ui.press(this, if (r == cur) Palette.RAISED else Palette.BG, 8)
                if (tooHigh) alpha = 0.5f
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(ui.text(r.name, 14f, Palette.TEXT, 600))
                    addView(ui.note(r.method.name + (r.requires?.let { " · needs $it" } ?: "")))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                if (r == cur) addView(ui.icon("check", Palette.ACCENT, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) })
                addView(ui.text("Lv ${r.level}", 12f, Palette.MUTED, 600))
                setOnClickListener {
                    current = r
                    showing = r
                    show()
                }
            }, full(4))
        }
        if (level != null) addView(ui.note("Faded rumours need a higher Hunter level than your $level (from Bubble Sync)."), full(6))
        addView(ui.note("Rumour lists, travel and equipment from the OSRS Wiki."), full(10))
    }

    // ---------------- One rumour ----------------

    private fun detail(r: HunterRumours.Rumour): View = scrolling {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ui.text(r.name, 16f, Palette.TEXT, 700), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(ui.text("Lv ${r.level}", 12f, Palette.MUTED, 600))
        }, full())
        addView(ui.note("${r.method.name} · given by " + r.hunters.joinToString(", ") { it.label.substringAfterLast(' ') }), full(2))
        r.requires?.let { addView(ui.text("Needs: $it", 12f, Palette.CAUTION, 600), full(6)) }

        // Where, and how to get there
        addView(ui.heading("Where to find them"), full(12))
        for (p in r.places) {
            addView(ui.card {
                addView(ui.text(p.name, 13f, Palette.TEXT, 600))
                p.note?.let { addView(ui.text(it, 12f, Palette.CAUTION), full(1)) }
                for (t in p.travel) addView(ui.text("•  $t", 12f), full(2))
            }, full(5))
        }

        // What to bring
        addView(ui.heading("What to bring"), full(12))
        addView(ui.card {
            addView(ui.text("Required", 12f, Palette.TEXT, 600))
            for (item in r.method.required) addView(ui.text("•  $item", 12f), full(2))
            val level = hunterLevel()
            if (r.method in listOf(HunterRumours.BOX_TRAP, HunterRumours.BIRD_SNARE, HunterRumours.NET_TRAP)) {
                // with a synced account, the number at your level; without, how it goes up
                val text = if (level != null) HunterRumours.trapsAt(level).let { n ->
                    "At level $level you can lay $n trap${if (n == 1) "" else "s"} (one more in the Wilderness)."
                } else "You can lay 1 trap, and one more at levels 20, 40, 60 and 80 Hunter (one more again in the Wilderness)."
                addView(ui.note(text), full(3))
            }
        }, full(5))
        if (r.method.usesLogs) {
            // The traps use logs: most people cut them at the hunting spot
            addView(ui.card {
                addView(ui.text("You'll need logs for every trap", 12f, Palette.TEXT, 600))
                addView(ui.text("Cut them at the hunting spot with your axe. Wear Kandarin headgear (from the Kandarin Diary) to get " +
                    "double logs from normal trees.", 12f), full(2))
            }, full(5))
        }
        val useful = r.method.useful.filter { !(r.method.usesLogs && (it.startsWith("Any axe") || it.startsWith("Kandarin"))) }
        if (useful.isNotEmpty()) addView(ui.card {
            addView(ui.text("Useful", 12f, Palette.TEXT, 600))
            for (item in useful) addView(ui.text("•  $item", 12f), full(2))
        }, full(5))
        addView(ui.card {
            addView(ui.text("Good for every rumour", 12f, Palette.TEXT, 600))
            for (item in HunterRumours.ALWAYS_USEFUL) addView(ui.text("•  $item", 12f), full(2))
        }, full(5))

        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(ui.button("All rumours") { showing = null; show() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { rightMargin = dp(3) })
            addView(ui.button("Done with it", Ui.ButtonStyle.GOLD) { current = null; showing = null; show() },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(3) })
        }, full(12))
        addView(ui.note("From the OSRS Wiki's Hunters' Rumours pages."), full(8))
    }

    // ---------------- Small building blocks ----------------

    // A row of options where each option's name is also its value
    private fun choiceRow(options: List<String>, selected: String, onPick: (String) -> Unit) =
        ui.choices(options.map { it to it }, selected, onPick)

    private fun scrolling(build: LinearLayout.() -> Unit): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            build()
        })
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}

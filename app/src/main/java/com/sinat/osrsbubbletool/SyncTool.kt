package com.sinat.osrsbubbletool

import android.content.Context
import android.content.Intent
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

// Bubble Sync: scan the QR code the Bubble Tool Sync plugin shows in RuneLite on a PC, and the tools that use your
// quests, levels and diaries (Quest Helper, Hunter Rumours, Teleport Finder) all read it from here.
// The same page is on the app's main screen (Bubble Sync, under Start Bubble): there inBubble is false.
class SyncTool(
    private val context: Context,
    private val hideWindow: () -> Unit = {},   // gets the bubble's window out of the camera's way
    private val inBubble: Boolean = true
) {

    private val ui = Ui(context)
    private val sync = AccountSync(context)
    private lateinit var holder: FrameLayout
    private var pasting = false          // the "paste the code" box is open
    private var message: String? = null
    private var redraw: (() -> Unit)? = null   // on the main screen: draws the whole page again
    private var column: LinearLayout? = null   // on the main screen: where the page was last drawn

    private fun dp(v: Int) = ui.dp(v)

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(Palette.SURFACE) }
        show()
        // back from the camera: show what was scanned
        holder.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { cameBack(); show() }
            override fun onViewDetachedFromWindow(v: View) { hideKeyboard() }
        })
        return holder
    }

    // The main screen's page: adds everything to its column; redraw draws the main screen's page again
    fun addTo(column: LinearLayout, redraw: () -> Unit) {
        this.redraw = redraw
        this.column = column
        column.build()
    }

    // Back from the camera or the file chooser: a file that couldn't be read leaves its message
    fun cameBack() {
        message = AccountSync.notice
        AccountSync.notice = null
    }

    private fun show() {
        redraw?.let { it(); return }
        if (!::holder.isInitialized) return
        holder.removeAllViews()
        holder.addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(8), dp(8), dp(12))
                build()
            })
        })
    }

    private fun LinearLayout.build() {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // (in the bubble, the window's top bar has the name)
            addView(ui.text(if (inBubble) "" else "Bubble Sync", 20f, Palette.TEXT, 700), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(SyncBadge.view(context, sync, onClick = null))
        }, full())
        addView(ui.note("Your quests, levels and achievement diaries, for the Quest Helper, Hunter Rumours and " +
            "Teleport Finder. Scanned from the Bubble Tool Sync plugin in RuneLite: nothing goes over the internet."), full(3))

        val data = sync.cached
        if (data != null) {
            addView(ui.card {
                addView(ui.text(data.name.ifEmpty { "Your account" }, 15f, Palette.TEXT, 700))
                addView(ui.note("Read in RuneLite " + ago(data.readAt)), full(1))
                val quests = QuestBook.tally(data, miniquests = false)
                val miniquests = QuestBook.tally(data, miniquests = true)
                val tiers = QuestBook.diaries.flatMap { d -> d.tiers.map { t -> data.diaryTasks(d.region, t.tier) } }
                addView(stat("Quests done", "${quests.done} / ${quests.total}"), full(8))
                addView(stat("Miniquests done", "${miniquests.done} / ${miniquests.total}"), full(2))
                addView(stat("Quest points", "${QuestBook.questPoints(data)}"), full(2))
                QuestBook.combatLevel(data)?.let { addView(stat("Combat level", "$it"), full(2)) }
                addView(stat("Total level", "${data.levels.values.sum()}"), full(2))
                addView(stat("Diaries complete", "${tiers.count { it != null && it.all { done -> done } }} / ${tiers.size}"), full(2))
            }, full(10))
        }

        // Gold marks the one main action: Scan, or Save while the paste box is open
        addView(ui.button(if (data == null) "Scan from RuneLite" else "Scan again",
            if (pasting) Ui.ButtonStyle.PLAIN else Ui.ButtonStyle.GOLD) { scan() }, full(10))
        if (pasting) {
            val field = ui.textBox("BTS1:…").apply {
                textSize = 12f
                maxLines = 3
                imeOptions = EditorInfo.IME_ACTION_DONE
            }
            val save = {
                val text = field.text.toString()
                if (text.isNotBlank()) {
                    message = try {
                        sync.save(text); pasting = false; hideKeyboard(); null
                    } catch (e: SyncCode.BadCode) { e.message }
                    show()
                }
            }
            field.setOnEditorActionListener { _, _, _ -> save(); true }
            addView(ui.text("Paste the text from the plugin's Copy text button (press and hold the box, then Paste):", 12f), full(8))
            addView(field, full(3))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("Save", Ui.ButtonStyle.GOLD) { save() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.button("Cancel") { pasting = false; message = null; hideKeyboard(); show() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            }, full(4))
        } else {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(ui.button("Paste code") { pasting = true; message = null; show() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.button("Import file") { importFile() },
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { leftMargin = dp(6) })
            }, full(6))
            if (data != null) addView(ui.button("Forget this account", Ui.ButtonStyle.DANGER) { sync.forget(); message = null; show() }, full(6))
        }
        message?.let { addView(ui.banner(it), full(8)) }

        addView(ui.card {
            addView(ui.text("How to sync", 12f, Palette.TEXT, 600))
            addView(ui.text("1. On your PC, install the Bubble Tool Sync plugin in RuneLite (Plugin Hub).\n" +
                "2. Log in to the game and click the plugin's icon in RuneLite's side bar: it shows a QR code.\n" +
                "3. Tap Scan here and point your phone's camera at the code.\n\n" +
                "Or use the plugin's Copy text (then Paste code here), or its Export file: get the account-….txt " +
                "file onto your phone (for example through Google Drive or a USB cable), then tap Import file.\n\n" +
                "Scan again after finishing quests or diary tasks, or gaining levels. The app only knows what " +
                "your last scan showed.", 12f), full(2))
        }, full(12))
    }

    // Opens the phone's file chooser for the plugin's exported file; the bubble brings this window back after
    private fun importFile() = open(SyncFileActivity::class.java)

    // Opens the camera; the bubble brings this window back when it closes (SyncScanActivity)
    private fun scan() = open(SyncScanActivity::class.java)

    private fun open(screen: Class<*>) {
        hideKeyboard()
        if (inBubble) hideWindow()
        // from the bubble (a service), the screen opens on its own; from the main screen, on top of it
        context.startActivity(Intent(context, screen).putExtra(SyncFileActivity.EXTRA_FROM_BUBBLE, inBubble)
            .apply { if (inBubble) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
    }

    private fun stat(name: String, value: String) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(ui.text(name, 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.text(value, 12f, Palette.TEXT, 600))
    }

    private fun ago(time: Long): String {
        val minutes = (System.currentTimeMillis() - time) / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 60 * 24 -> "${minutes / 60} h ago"
            else -> "${minutes / (60 * 24)} days ago"
        }
    }

    private fun hideKeyboard() {
        val token = (if (::holder.isInitialized) holder.windowToken else null) ?: column?.windowToken ?: return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(token, 0)
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}

package com.sinat.osrsbubbletool

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

// A notepad: a list of notes, each with a title and text. Notes save by themselves as you type.
// While you're typing, the keyboard covers about half the screen, so the note sits in the top half.
// onTyping(false): you've stopped writing a note (Done, ◀, deleted, window closed), so the window can go back to its
// normal size (it's widened while you type: BubbleService.widenNotes)
class NotesTool(private val context: Context, private val onTyping: (Boolean) -> Unit = {}) {

    companion object {
        private const val SAVE_DELAY_MS = 600L
        private const val TYPING_ROW_DP = 36   // the title row while writing: slimmer than usual, for the note's sake

        // How far down the Notepad window sits: below the status bar (or 5% of the screen, whichever is more)
        fun topOffset(context: Context): Int {
            val res = context.resources
            // Android 11+ says how tall the status bar is, even while the game has it hidden.
            // Older phones: 24dp, the usual height.
            val bar = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                context.getSystemService(android.view.WindowManager::class.java).currentWindowMetrics.windowInsets
                    .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.statusBars()).top
            } else (24 * res.displayMetrics.density).toInt()
            return maxOf(bar, (res.displayMetrics.heightPixels * 0.05f).toInt())
        }
    }

    class Note(val id: Long, var title: String, var body: String, var updated: Long)

    private val ui = Ui(context)
    private val prefs = context.getSharedPreferences("notes", Context.MODE_PRIVATE)
    private val handler = Handler(Looper.getMainLooper())
    private val notes: MutableList<Note> = load()
    private var open: Note? = null           // the note being read or edited, or null for the list
    private var editing = false              // false = reading the note, true = typing in it
    private var confirmDelete = false
    private lateinit var holder: FrameLayout

    private fun dp(v: Int) = ui.dp(v)

    fun buildView(): View {
        holder = FrameLayout(context).apply { setBackgroundColor(Palette.SURFACE) }
        show()
        return holder
    }

    // The window's ◀ button: from typing back to reading, and from reading back to the list
    fun canGoBack() = open != null

    // A note is open for writing (the bubble widens the window while the keyboard is up)
    fun isEditing() = open != null && editing

    fun goBack() {
        when {
            open != null && editing -> closeNote()
            open != null -> { open = null; show() }
        }
    }

    // The window was closed (✕, or the bubble closing): save straight away
    fun onWindowClosed() {
        // a note with nothing written in it isn't kept (the same as tapping Done)
        open?.takeIf { it.title.isBlank() && it.body.isBlank() }?.let {
            notes.remove(it)
            open = null
            editing = false
            show()
        }
        saveNow()
        hideKeyboard()
    }

    private fun show() {
        if (!(open != null && editing)) onTyping(false)
        holder.removeAllViews()
        val note = open
        holder.addView(when {
            note == null -> list()
            editing -> editor(note)
            else -> reader(note)
        })
    }

    // ---------------- The list of notes ----------------

    private fun list(): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(12))
            addView(ui.button("New note", Ui.ButtonStyle.GOLD) {
                val note = Note(System.currentTimeMillis(), "", "", System.currentTimeMillis())
                notes.add(0, note)
                openNote(note, edit = true)
            }, full())
            if (notes.isEmpty()) {
                addView(ui.note("No notes yet. Tap New note to write one: a shopping list, a drop you're chasing, " +
                    "what to do next time you log in…"), full(10))
            }
            for (note in notes.sortedByDescending { it.updated }) {
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER_VERTICAL
                    minimumHeight = dp(Ui.TAP_SIZE)
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                    ui.press(this, Palette.BG, 8)
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                        addView(ui.oneLine(ui.text(titleOf(note), 14f, Palette.TEXT, 600)),
                            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                        addView(ui.note(dateOf(note.updated)).apply { setPadding(dp(8), 0, 0, 0) })
                    })
                    val preview = note.body.lines().firstOrNull { it.isNotBlank() && it.trim() != note.title.trim() }
                    if (preview != null) addView(ui.oneLine(ui.note(preview.trim())), full(2))
                    setOnClickListener { openNote(note) }
                }, full(4))
            }
        })
    }

    private fun titleOf(n: Note) = n.title.trim().ifEmpty { n.body.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "Untitled" }

    private fun dateOf(time: Long): String {
        val now = System.currentTimeMillis()
        val pattern = if (now - time < 24 * 3600_000L && SimpleDateFormat("yyyyMMdd", Locale.getDefault()).let { it.format(Date(now)) == it.format(Date(time)) })
            "h:mm a" else "MMM d"
        return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(time))
    }

    // ---------------- Writing a note ----------------

    private fun openNote(note: Note, edit: Boolean = false) {
        open = note
        editing = edit
        confirmDelete = false
        show()
    }

    // Done typing: show the note plainly (or go back to the list if nothing was written)
    private fun closeNote() {
        val note = open ?: return
        saveNow()
        hideKeyboard()
        editing = false
        confirmDelete = false
        if (note.title.isBlank() && note.body.isBlank()) {   // nothing written: don't keep it
            notes.remove(note); persist()
            open = null
        }
        show()
    }

    // ---------------- Reading a note ----------------

    private fun reader(note: Note): View = ScrollView(context).apply {
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(14))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(ui.text(titleOf(note), 16f, Palette.TEXT, 700), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(ui.button("Edit") { openNote(note, edit = true) }.apply { minWidth = dp(64) },
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { leftMargin = dp(6) })
            })
            addView(ui.note("Edited " + dateOf(note.updated)), full(1))
            addView(ui.text(note.body.ifBlank { "(no text)" }, 14f, if (note.body.isBlank()) Palette.MUTED else Palette.TEXT).apply {
                setTextIsSelectable(true)   // press and hold to copy
                setLineSpacing(0f, 1.15f)
            }, full(10))
        })
    }

    // Writing a note. Slim, as the keyboard leaves only the top of the screen: a low title row, the note filling the
    // rest (it scrolls inside), and no extra lines (the title's hint says it saves as you type).
    private fun editor(note: Note): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(4), dp(4), dp(4), dp(4))
        val row = dp(TYPING_ROW_DP)

        // Title, delete and Done on one row, so the note itself gets as much room as possible
        val title = ui.textBox("Title (saves as you type)").apply {
            setText(note.title)
            typeface = ui.font(600)
            setSingleLine()
            minHeight = row
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        // The first tap on the bin asks "Delete?"; the second deletes the note
        val deleteNote: () -> Unit = {
            if (confirmDelete) {
                handler.removeCallbacksAndMessages(null)
                notes.remove(note); persist()
                hideKeyboard()
                open = null
                show()
            } else { confirmDelete = true; show() }
        }
        val deleteButton: View =
            if (confirmDelete) ui.button("Delete?", Ui.ButtonStyle.DANGER) { deleteNote() }.apply { minWidth = dp(64); minHeight = row }
            else ui.iconButton("trash", "Delete note", Palette.BAD, Palette.RAISED) { deleteNote() }
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(title, LinearLayout.LayoutParams(0, row, 1f))
            addView(deleteButton, LinearLayout.LayoutParams(if (confirmDelete) ViewGroup.LayoutParams.WRAP_CONTENT else row, row).apply { leftMargin = dp(4) })
            addView(ui.button("Done", Ui.ButtonStyle.GOLD) { closeNote() }.apply { minWidth = dp(64); minHeight = row },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, row).apply { leftMargin = dp(4) })
        }, full())

        // The note. It fills the rest of the window (resize the window to make it bigger) and scrolls inside.
        val body = ui.textBox("Write your note…").apply {
            setText(note.body)
            gravity = Gravity.TOP or Gravity.START
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = false
            isVerticalScrollBarEnabled = true
            minHeight = 0
            setPadding(dp(10), dp(6), dp(10), dp(6))
        }
        addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(4) })

        fun changed() {
            note.title = title.text.toString()
            note.body = body.text.toString()
            note.updated = System.currentTimeMillis()
            handler.removeCallbacksAndMessages(null)
            handler.postDelayed({ persist() }, SAVE_DELAY_MS)
        }
        title.addTextChangedListener(watcher { changed() })
        body.addTextChangedListener(watcher { changed() })

        // a new note: start typing straight away
        if (note.title.isEmpty() && note.body.isEmpty()) post { title.requestFocus(); showKeyboard(title) }
    }

    // ---------------- Saving ----------------

    private fun saveNow() {
        handler.removeCallbacksAndMessages(null)
        persist()
    }

    private fun persist() {
        val a = JSONArray()
        for (n in notes) a.put(JSONObject().put("id", n.id).put("title", n.title).put("body", n.body).put("updated", n.updated))
        prefs.edit { putString("notes", a.toString()) }
    }

    private fun load(): MutableList<Note> {
        val text = prefs.getString("notes", null) ?: return mutableListOf()
        val a = try { JSONArray(text) } catch (e: Exception) {
            // unreadable: keep a copy before anything overwrites it, so the notes aren't lost for good
            prefs.edit { putString("notes_backup_" + System.currentTimeMillis(), text) }
            return mutableListOf()
        }
        // each note on its own, so one damaged note doesn't lose the others
        val list = mutableListOf<Note>()
        for (i in 0 until a.length()) {
            try {
                val o = a.getJSONObject(i)
                list.add(Note(o.getLong("id"), o.optString("title"), o.optString("body"), o.optLong("updated")))
            } catch (e: Exception) { }
        }
        return list
    }

    // ---------------- Keyboard ----------------

    private fun showKeyboard(v: View) {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.showSoftInput(v, 0)   // (0: a plain request; the old SHOW_IMPLICIT is out of date)
    }

    private fun hideKeyboard() {
        if (!::holder.isInitialized) return
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)?.hideSoftInputFromWindow(holder.windowToken, 0)
    }

    // ---------------- Small building blocks ----------------

    private fun watcher(onChange: () -> Unit) = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { onChange() }
    }

    private fun full(top: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }
}

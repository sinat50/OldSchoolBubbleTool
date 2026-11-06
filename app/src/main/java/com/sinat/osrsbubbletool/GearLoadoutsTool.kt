package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import com.sinat.osrsbubbletool.GuidedScan.Part
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.DEEP_RED
import com.sinat.osrsbubbletool.Palette.PAPER
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import com.sinat.osrsbubbletool.Palette.ROW_BROWN
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONArray

// Gear Loadouts: screenshots of your inventory, equipment, rune pouch and spellbook for a trip, saved under a name
// with your notes, to look at while you gear up. Scan Current Loadout takes them: the window shrinks to a checklist
// in a corner while the guided scan (GuidedScan) watches the game, and saves each part as you open it. Nothing on the
// screenshots is read or recognised, and the app comes with no game pictures: they're your own screen, kept on your
// phone (files/setups/<id>/, backed up with the app).
// (It was Inventory Setups, which saved pictures of areas you set by hand; those pictures are still shown. From v1.0.9
// to v1.2.0 it listed the items it read instead: those lists are shown as text until a scan replaces them.)
// setWindowCompact shrinks the window to the checklist while the scan looks at the game.
class GearLoadoutsTool(
    private val context: Context,
    private val capture: CaptureManager,
    private val setWindowCompact: (Int) -> Unit
) {

    companion object {
        private const val PICTURE_QUALITY = 90       // WebP: the screenshots look the same, at a fraction of a PNG's size
        private const val MAX_PICTURE_HEIGHT = 360   // dp: a screenshot is shown no taller than this
        private const val SPELLBOOK_SIZE = 44        // dp: the spellbook button's screenshot, about as big as in the game
    }

    private val handler = Handler(Looper.getMainLooper())
    // the scan's details go to the phone's log (adb logcat -s GearLoadouts) only in a debug build
    private val debugBuild = (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
    // (the settings file and folder keep Inventory Setups' names, so its setups carry over)
    private val prefs = context.getSharedPreferences("inventory_setups", Context.MODE_PRIVATE)
    private val folder = File(context.filesDir, "setups")
    private val loadouts = loadLoadouts()
    private var items: ItemData? = null       // item names, only loaded for loadouts that still list items
    // screenshots are written one at a time, away from the main thread, in the order they were taken
    private val writer = Executors.newSingleThreadExecutor()

    private var root: FrameLayout? = null
    private var current: Loadout? = null      // the loadout being viewed, or null for the list
    private var deleteArmed = false
    private var nameField: EditText? = null
    private var notesField: EditText? = null
    private var readMessage: String? = null   // what the last Scan Current Loadout saved, shown on the loadout once

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Called by BubbleService ----------------

    fun buildView(): View {
        val r = FrameLayout(context).apply {
            setBackgroundColor(PARCHMENT)
            // take focus first, so the keyboard doesn't pop up until you tap a text box
            isFocusable = true
            isFocusableInTouchMode = true
        }
        root = r
        showList()
        return r
    }

    // The ◀ button: from a loadout, back to the list
    fun goBack() {
        if (current == null) return
        saveTyping()
        showList()
    }

    fun onWindowClosed() {
        stopScan()
        saveTyping()
    }

    fun destroy() {
        stopScan()
        saveTyping()
        writer.shutdown()   // (screenshots already taken are still written)
    }

    // ---------------- Small building blocks ----------------

    private fun button(label: String, color: Int = BUTTON_BROWN, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 14f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun label(text: String, size: Float = 13f, bold: Boolean = false) = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(DARK_BROWN)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun textBox(hint: String) = EditText(context).apply {
        this.hint = hint
        setHintTextColor(Palette.FADED)
        setTextColor(DARK_BROWN)
        textSize = 15f
        background = GradientDrawable().apply {
            setColor(PAPER)
            cornerRadius = dp(6).toFloat()
        }
        setPadding(dp(8), dp(6), dp(8), dp(6))
    }

    private fun hideKeyboard(view: View) {
        view.clearFocus()
        context.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun fullWidth(topMarginDp: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(topMarginDp) }

    private fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

    private fun setContent(content: View) {
        root?.removeAllViews()
        root?.addView(content, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    // ---------------- The list of loadouts ----------------

    private fun showList() {
        current = null
        nameField = null
        notesField = null

        val list = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }
        list.addView(button("+ New loadout") { createLoadout() }, fullWidth())
        if (loadouts.isEmpty()) {
            list.addView(label("No loadouts yet. Tap New loadout, then Scan Current Loadout to save screenshots of " +
                "your inventory, equipment, rune pouch and spellbook."), fullWidth(10))
        }
        for (l in loadouts) {
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(12), dp(10))
                background = GradientDrawable().apply {
                    setColor(ROW_BROWN)
                    cornerRadius = dp(6).toFloat()
                }
                setOnClickListener { showLoadout(l) }
            }
            row.addView(label(l.name, 15f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            list.addView(row, fullWidth(8))
        }
        setContent(ScrollView(context).apply { addView(list) })
    }

    private fun createLoadout() {
        val l = Loadout(System.currentTimeMillis().toString(), "Loadout ${loadouts.size + 1}")
        loadouts.add(l)
        saveLoadouts()
        showLoadout(l)
    }

    // ---------------- One loadout ----------------

    private fun showLoadout(l: Loadout) {
        current = l
        deleteArmed = false

        val page = column().apply { setPadding(dp(10), dp(10), dp(10), dp(10)) }

        nameField = textBox("Name").apply {
            setText(l.name)
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { view, _, _ -> saveTyping(); hideKeyboard(view); true }
        }
        page.addView(nameField, fullWidth())

        // Take the screenshots from the game (the guided scan); a line under it says what happened, when there's news
        val readStatus = label(readMessage ?: "").apply { visibility = if (readMessage == null) View.GONE else View.VISIBLE }
        readMessage = null
        page.addView(button("Scan Current Loadout") { startScan(l, readStatus) }, fullWidth(8))
        page.addView(readStatus, fullWidth(4))

        val pictures = Part.entries.mapNotNull { part -> pictureFile(l, part)?.let { part to it } }.toMap()
        if (pictures.isEmpty() && !l.hasItems) {
            page.addView(label("Tap Scan Current Loadout, then open your inventory, equipment and rune pouch in the game. " +
                "Each one is saved as a screenshot as it shows."), fullWidth(10))
        }

        // Inventory on the left and equipment on the right, side by side if the window is wide enough; the rune
        // pouch and spellbook under them the same way
        fun part(p: Part): View? {
            val file = pictures[p] ?: return null
            return column().apply {
                addView(label(p.label, 14f, bold = true))
                addView(picture(file, small = p == Part.SPELLBOOK), if (p == Part.SPELLBOOK)
                    LinearLayout.LayoutParams(dp(SPELLBOOK_SIZE), dp(SPELLBOOK_SIZE)).apply { topMargin = dp(4) } else fullWidth(4))
            }
        }
        fun pair(a: View?, b: View?) {
            when {
                a != null && b != null -> page.addView(TwoColumns(context, a, b, dp(130), dp(12)), fullWidth(10))
                a != null -> page.addView(a, fullWidth(10))
                b != null -> page.addView(b, fullWidth(10))
            }
        }
        pair(part(Part.INVENTORY), part(Part.EQUIPMENT))
        pair(part(Part.RUNE_POUCH), part(Part.SPELLBOOK))

        // A loadout saved before screenshots: its items as text, until a scan replaces each part
        if (l.hasItems) {
            page.addView(label("Saved items", 14f, bold = true), fullWidth(14))
            page.addView(label("Saved before Gear Loadouts kept screenshots. Scan Current Loadout replaces each part " +
                "with its screenshot.", 11f).apply { setTextColor(Palette.FADED) }, fullWidth(2))
            val data = items
            if (data == null) {
                page.addView(label("Loading item names…"), fullWidth(6))
                ItemCatalog.get(context) { loaded -> items = loaded; if (current === l && scan.progress == null) showLoadout(l) }
            } else {
                for (list in l.itemLines { id -> data.shown(id)?.let { (item, noted) -> if (noted) "${item.name} (noted)" else item.name } ?: "Item $id" }) {
                    page.addView(label(list.heading, 13f, bold = true), fullWidth(8))
                    page.addView(label(list.lines.joinToString("\n"), 13f), fullWidth(2))
                }
            }
        }

        page.addView(label("Notes", 14f, bold = true), fullWidth(12))
        notesField = textBox("Anything to remember for this trip").apply {
            setText(l.notes)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            gravity = Gravity.TOP or Gravity.START
        }
        page.addView(notesField, fullWidth(4))

        val deleteButton = button("Delete loadout", DEEP_RED) {}
        deleteButton.setOnClickListener {
            if (!deleteArmed) {
                deleteArmed = true
                deleteButton.text = "Tap again to delete"
            } else {
                deleteLoadout(l)
            }
        }
        page.addView(deleteButton, fullWidth(20))

        setContent(ScrollView(context).apply { addView(page) })
    }

    // A saved screenshot, as big as fits (the spellbook button small, as in the game)
    private fun picture(file: File, small: Boolean) = ImageView(context).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_CENTER
        if (!small) maxHeight = dp(MAX_PICTURE_HEIGHT)
        setImageBitmap(decodeForScreen(file))
        contentDescription = file.nameWithoutExtension.replace('_', ' ')
    }

    // Name and notes are saved when you leave the loadout (and when Done is tapped on the name)
    private fun saveTyping() {
        val l = current ?: return
        val typedName = nameField?.text?.toString()?.trim().orEmpty()
        val typedNotes = notesField?.text?.toString()?.trimEnd() ?: l.notes
        if ((typedName.isNotEmpty() && typedName != l.name) || typedNotes != l.notes) {
            if (typedName.isNotEmpty()) l.name = typedName
            l.notes = typedNotes
            saveLoadouts()
        }
    }

    private fun deleteLoadout(l: Loadout) {
        loadouts.remove(l)
        saveLoadouts()
        val gone = File(folder, l.id)
        writer.execute { gone.deleteRecursively() }   // (after any screenshot still being written)
        current = null
        showList()
    }

    // ---------------- Taking the screenshots ----------------
    //
    // One tap: the window shrinks to a checklist in a corner (spellbook, inventory, rune pouch, equipment) while the
    // guided scan looks at the game; each part ticks off as it's saved.

    private var scanning: Loadout? = null   // the loadout the scan saves into
    private val scan = GuidedScan(capture, debugBuild, object : GuidedScan.Listener {
        override fun saved(part: Part, picture: Bitmap) {
            val l = scanning ?: run { picture.recycle(); return }
            // the screenshot replaces that part of an item list saved before screenshots
            when (part) {
                Part.INVENTORY -> l.forgetInventory()
                Part.EQUIPMENT -> l.forgetEquipment()
                Part.RUNE_POUCH -> l.forgetRunePouch()
                Part.SPELLBOOK -> l.forgetSpellbook()
            }
            saveLoadouts()
            val dir = File(folder, l.id)
            writer.execute { writePicture(dir, part, picture) }
            updateChecklist()
        }

        override fun looked() = updateChecklist()

        override fun finished() = finishScan()
    })

    private fun startScan(l: Loadout, status: TextView) {
        if (scan.progress != null) return
        fun say(text: String) { status.text = text; status.visibility = View.VISIBLE }
        if (!capture.isActive) {
            say("Waiting for screen-capture permission...")
            capture.request { ok ->
                if (ok) startScan(l, status)
                else say("Couldn't turn on screen capture: ${capture.lastError}.")
            }
            return
        }
        saveTyping()
        scanning = l
        scan.start()
        showChecklist()
        setWindowCompact(BubbleService.COMPACT_TOP_LEFT)
    }

    // The checklist shown while scanning: each part, ticked once saved, or what to open for it
    private var checklistLines: TextView? = null

    private fun showChecklist() {
        val page = column().apply { setPadding(dp(10), dp(8), dp(10), dp(8)) }
        page.addView(label("Saving from the game", 14f, bold = true))
        val lines = label("", 13f)
        checklistLines = lines
        page.addView(lines, fullWidth(4))
        page.addView(button("Done") { finishScan() }, fullWidth(6))
        root?.removeAllViews()
        root?.addView(page, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        updateChecklist()
    }

    private fun updateChecklist() {
        val sc = scan.progress ?: return
        fun has(p: Part) = p in sc.saved
        val pouchLine = when {
            has(Part.RUNE_POUCH) -> "✓ Rune pouch"
            else -> sc.pouchSecondsLeft(System.currentTimeMillis())?.let { "• Rune pouch, if you have one: tap it, Open (finishing in $it s)" }
                ?: "• Rune pouch, if you have one: tap it, Open"
        }
        checklistLines?.text = listOf(
            if (has(Part.SPELLBOOK)) "✓ Spellbook" else "• Spellbook: keep the side panel open",
            if (has(Part.INVENTORY)) "✓ Inventory" else "• Inventory: open its tab",
            pouchLine,
            if (has(Part.EQUIPMENT)) "✓ Equipment" else "• Equipment: open its tab"
        ).joinToString("\n")
    }

    // Back to the loadout, saying what was saved and what wasn't (once every screenshot is written)
    private fun finishScan() {
        val sc = scan.stop() ?: return
        val l = scanning ?: return
        scanning = null
        checklistLines = null
        setWindowCompact(BubbleService.COMPACT_OFF)
        val order = listOf(Part.INVENTORY, Part.EQUIPMENT, Part.RUNE_POUCH, Part.SPELLBOOK)
        val saved = order.filter { it in sc.saved }.map { it.label.lowercase() }
        val missing = order.filter { it !in sc.saved && it != Part.RUNE_POUCH }.map { it.label.lowercase() }
        readMessage = (if (saved.isEmpty()) "Nothing saved. " else "Saved: ${saved.joinToString(", ")}. ") +
            (if (missing.isEmpty()) "" else "Not saved: ${missing.joinToString(", ")} (tap Scan Current Loadout again).")
        writer.execute { handler.post { if (current === l && scan.progress == null) showLoadout(l) } }
    }

    // Stops a scan without a word (the window closed, or the bubble is closing)
    private fun stopScan() {
        scan.stop()
        scanning = null
        checklistLines = null
    }

    // ---------------- The screenshots on the phone ----------------

    // A part's screenshot: a scan's (.webp), or Inventory Setups' (.png); null if it has none
    private fun pictureFile(l: Loadout, part: Part): File? {
        val dir = File(folder, l.id)
        return File(dir, "${part.file}.webp").takeIf { it.exists() } ?: File(dir, "${part.file}.png").takeIf { it.exists() }
    }

    // Writes a screenshot (on the writer thread), in place of the part's older one, then lets it go
    private fun writePicture(dir: File, part: Part, picture: Bitmap) {
        try {
            dir.mkdirs()
            // written under another name first, so a half-written file is never shown
            val temp = File(dir, "${part.file}.tmp")
            temp.outputStream().use { picture.compress(webp(), PICTURE_QUALITY, it) }
            val file = File(dir, "${part.file}.webp")
            if (!temp.renameTo(file)) { file.delete(); temp.renameTo(file) }
            File(dir, "${part.file}.png").delete()   // Inventory Setups' older one
        } catch (_: Exception) {
            // the phone is out of space, say: that part just isn't saved
        } finally {
            picture.recycle()
        }
    }

    @Suppress("DEPRECATION")
    private fun webp() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP

    // Saved pictures are full size; shown on screen, half size is plenty on high-resolution phones
    private fun decodeForScreen(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        val maxWidth = context.resources.displayMetrics.widthPixels
        while (bounds.outWidth / (sample * 2) >= maxWidth) sample *= 2
        return BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }

    // ---------------- Saving the list ----------------

    private fun loadLoadouts(): MutableList<Loadout> {
        val list = mutableListOf<Loadout>()
        val json = prefs.getString("list", null) ?: return list
        try {
            val array = JSONArray(json)
            for (i in 0 until array.length()) {
                try { list.add(Loadout.fromJson(array.getJSONObject(i))) } catch (_: Exception) { }   // a damaged one is skipped
            }
        } catch (_: Exception) {
            // a damaged list is treated as empty rather than crashing the app
        }
        return list
    }

    private fun saveLoadouts() {
        val array = JSONArray()
        loadouts.forEach { array.put(it.toJson()) }
        prefs.edit { putString("list", array.toString()) }
    }
}

// Two columns side by side when the window is wide enough for both (minColumn each), otherwise one under
// the other. Gear Loadouts puts the inventory on the left and the equipment on the right, as there's
// little height to spare with the phone sideways.
@SuppressLint("ViewConstructor")
class TwoColumns(context: Context, private val left: View, private val right: View,
                 private val minColumn: Int, private val gap: Int) : ViewGroup(context) {
    init {
        addView(left)
        addView(right)
    }

    private var sideBySide = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        sideBySide = width >= minColumn * 2 + gap
        val any = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        if (sideBySide) {
            val half = MeasureSpec.makeMeasureSpec((width - gap) / 2, MeasureSpec.EXACTLY)
            left.measure(half, any)
            right.measure(half, any)
            setMeasuredDimension(width, maxOf(left.measuredHeight, right.measuredHeight))
        } else {
            val full = MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY)
            left.measure(full, any)
            right.measure(full, any)
            setMeasuredDimension(width, left.measuredHeight + gap + right.measuredHeight)
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        if (sideBySide) {
            left.layout(0, 0, left.measuredWidth, left.measuredHeight)
            val x = r - l - right.measuredWidth
            right.layout(x, 0, r - l, right.measuredHeight)
        } else {
            left.layout(0, 0, left.measuredWidth, left.measuredHeight)
            val y = left.measuredHeight + gap
            right.layout(0, y, right.measuredWidth, y + right.measuredHeight)
        }
    }
}

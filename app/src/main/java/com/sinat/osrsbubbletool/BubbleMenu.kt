package com.sinat.osrsbubbletool

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

// The headings in the bubble's long-press menu
enum class ToolGroup(val label: String) {
    GUIDES("Guides"),
    CALCULATORS("Calculators"),
    TRACKERS("Trackers"),
    UTILITIES("Utilities")
}

// What's in the bubble's long-press menu: Favourites, the tools under their headings, then Settings and Close bubble.
// Tap a tool to open it; hold one to add it to Favourites or take it out. Each heading folds: tap it to show or hide
// its tools (so the whole menu can fit on the screen). Favourites always show.
class BubbleMenu(
    context: Context,
    private val openTool: BubbleService.Tool,      // shown in gold
    private val hidden: Set<String>,               // tools left out in Settings
    private val favourites: List<String>,
    private val openGroups: Set<ToolGroup>,        // the headings that are unfolded
    private val onOpen: (BubbleService.Tool) -> Unit,
    private val onHold: (BubbleService.Tool) -> Unit,
    private val onToggleGroup: (ToolGroup, Boolean) -> Unit,   // a heading was folded (false) or unfolded (true)
    private val onCloseBubble: () -> Unit
) {
    private val ui = Ui(context)

    fun build() = LinearLayout(ui.context).apply {
        orientation = LinearLayout.VERTICAL
        val shown = BubbleService.Tool.entries.filter { it.inMenu && it.name !in hidden }

        addView(heading("Favourites"))
        val chosen = favourites.mapNotNull { name -> shown.firstOrNull { it.name == name } }
        if (chosen.isEmpty()) addView(ui.text("Hold a tool to add it here.", 13f, Palette.MUTED).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = ui.dp(Ui.TAP_SIZE)
            setPadding(ui.dp(12), 0, ui.dp(12), 0)
        })
        chosen.forEach { addView(toolRow(it)) }

        // each group's tools are all built, and folding just hides or shows them in place (so the menu doesn't
        // flash by being built again); onToggleGroup is then told, to remember it and resize the menu's window
        for (group in ToolGroup.entries) {
            val tools = shown.filter { it.group == group }
            if (tools.isEmpty()) continue
            var open = group in openGroups
            val rows = tools.map { toolRow(it) }
            val head = groupHeading(group, tools.size)
            fun look() {
                rows.forEach { it.visibility = if (open) View.VISIBLE else View.GONE }
                head.contentDescription = group.label + if (open) ", open" else ", folded"
                head.getChildAt(1).visibility = if (open) View.GONE else View.VISIBLE   // (the count, while folded)
                (head.getChildAt(2) as android.widget.ImageView).rotation = if (open) 0f else -90f
            }
            head.setOnClickListener {
                open = !open
                look()
                onToggleGroup(group, open)
            }
            addView(head)
            rows.forEach { addView(it) }
            look()
        }

        // Settings and Close bubble end the list, a little apart from the tools
        addView(View(ui.context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)))
        addView(row(BubbleService.Tool.SETTINGS.icon, BubbleService.Tool.SETTINGS.label, openTool == BubbleService.Tool.SETTINGS).apply {
            setOnClickListener { onOpen(BubbleService.Tool.SETTINGS) }
        })
        addView(closeRow())
    }

    // Close bubble asks for a second tap: it's the last row, where a quick scroll ends, and closing stops everything
    // (a running guide or Idle Notifier included). The first tap changes it to "Tap again to close"; after 3 seconds
    // it goes back to "Close bubble".
    private fun closeRow() = row("close", CLOSE_LABEL, false, Palette.BAD).apply {
        val label = getChildAt(1) as TextView
        var asking = false
        val stopAsking = Runnable {
            asking = false
            label.text = CLOSE_LABEL
        }
        setOnClickListener {
            if (asking) {
                removeCallbacks(stopAsking)
                onCloseBubble()
            } else {
                asking = true
                label.text = "Tap again to close"
                postDelayed(stopAsking, CLOSE_ASK_MS)
            }
        }
    }

    private companion object {
        const val CLOSE_LABEL = "Close bubble"
        const val CLOSE_ASK_MS = 3000L
        const val HEADING_DP = 36   // a heading's height: it's tapped to fold, but kept slimmer than a tool's row
    }

    private fun heading(label: String) = ui.text(label, 12f, Palette.MUTED, 700).apply {
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(4))
    }

    // A heading you can tap to fold or unfold its tools: its name, how many tools it holds (shown while folded), and
    // an arrow (down when open, pointing right when folded)
    private fun groupHeading(group: ToolGroup, count: Int) = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(HEADING_DP)
        setPadding(ui.dp(12), 0, ui.dp(10), 0)
        // (no tap ripple: the group folding or unfolding shows the tap worked, and the heading moves as it does,
        // which left the ripple floating where the heading had been)
        isClickable = true
        isFocusable = true
        addView(ui.text(group.label, 12f, Palette.MUTED, 700), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(ui.text("$count", 12f, Palette.MUTED, 500).apply { setPadding(0, 0, ui.dp(6), 0) })
        addView(ui.icon("down", Palette.MUTED, 16))
    }

    private fun toolRow(tool: BubbleService.Tool) = row(tool.icon, tool.label, tool == openTool, beta = tool.beta).apply {
        setOnClickListener { onOpen(tool) }
        setOnLongClickListener { onHold(tool); true }
    }

    private fun row(icon: String, label: String, open: Boolean, colour: Int = Palette.TEXT, beta: Boolean = false) = LinearLayout(ui.context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = ui.dp(Ui.TAP_SIZE)
        setPadding(ui.dp(12), 0, ui.dp(6), 0)
        val shade = if (open) Palette.ACCENT else colour
        ui.press(this, if (open) Palette.BG else null)
        addView(ui.icon(icon, shade))
        addView(ui.oneLine(ui.text(label, 13f, shade, if (open) 700 else 500)).apply {
            setPadding(ui.dp(10), 0, ui.dp(4), 0)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (beta) addView(ui.text("Beta", 12f, Palette.MUTED, 500).apply {
            setPadding(ui.dp(5), 0, ui.dp(5), 0)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Palette.BG)
                cornerRadius = ui.dp(4).toFloat()
            }
        })
    }
}

package com.sinat.osrsbubbletool

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout

// The headings in the bubble's long-press menu
enum class ToolGroup(val label: String) {
    GUIDES("Guides"),
    CALCULATORS("Calculators"),
    TRACKERS("Trackers"),
    UTILITIES("Utilities")
}

// What's in the bubble's long-press menu: Favourites, the tools under their headings, then Settings and Close bubble.
// Tap a tool to open it; hold one to add it to Favourites or take it out.
class BubbleMenu(
    context: Context,
    private val openTool: BubbleService.Tool,      // shown in gold
    private val hidden: Set<String>,               // tools left out in Settings
    private val favourites: List<String>,
    private val onOpen: (BubbleService.Tool) -> Unit,
    private val onHold: (BubbleService.Tool) -> Unit,
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

        for (group in ToolGroup.entries) {
            val tools = shown.filter { it.group == group }
            if (tools.isEmpty()) continue
            addView(heading(group.label))
            tools.forEach { addView(toolRow(it)) }
        }

        // Settings and Close bubble end the list, a little apart from the tools
        addView(View(ui.context), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ui.dp(8)))
        addView(row(BubbleService.Tool.SETTINGS.icon, BubbleService.Tool.SETTINGS.label, openTool == BubbleService.Tool.SETTINGS).apply {
            setOnClickListener { onOpen(BubbleService.Tool.SETTINGS) }
        })
        addView(row("close", "Close bubble", false, Palette.BAD).apply { setOnClickListener { onCloseBubble() } })
    }

    private fun heading(label: String) = ui.text(label, 12f, Palette.MUTED, 700).apply {
        setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(4))
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

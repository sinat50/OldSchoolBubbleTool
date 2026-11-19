package com.sinat.osrsbubbletool

// The app's colours, in one place: the Warm Charcoal look, with a light and a dark version of each (Settings → Dark mode).
// Screens read them as they're built, so after dark mode is switched the bubble builds its tool windows again
// (BubbleService.rebuildToolWindows) and the app's screen redraws. Pictures drawn like the game (puzzle maps, the Zen
// Garden, game icons) keep their own colours. No Android parts, so PaletteContrastTest can check them on the PC.
object Palette {

    // Set from the Dark mode setting (AppSettings.isDark) before anything is built
    @Volatile var dark = false

    private fun shade(light: String, dark: String) = intArrayOf(colour(light), colour(dark))
    private fun IntArray.now() = this[if (dark) 1 else 0]

    // ---------------- The redesign's colours: new and redesigned screens use these ----------------

    private val bg = shade("#F1EFEB", "#131210")        // the darkest layer: search boxes, every other row
    private val surface = shade("#FBFAF7", "#1D1B18")   // a window's background
    private val raised = shade("#E4E1DB", "#2A2723")    // top bars, plain buttons, the open item
    private val text = shade("#1C1A17", "#F3EEE7")      // normal writing
    private val muted = shade("#5B564F", "#BFB7AC")     // quieter writing
    private val border = shade("#837C71", "#7A7064")    // the thin frame round the bubble's menu and tool windows
    private val accent = shade("#7A5200", "#E3B341")    // gold writing and icons
    private val ok = shade("#2B6A31", "#86CF8A")        // done, ready
    private val bad = shade("#A32D25", "#F4938A")       // stop, remove
    private val caution = shade("#8A4700", "#F2AE6B")   // a warning

    val BG get() = bg.now()
    val SURFACE get() = surface.now()
    val RAISED get() = raised.now()
    val TEXT get() = text.now()
    val MUTED get() = muted.now()
    val BORDER get() = border.now()
    val ACCENT get() = accent.now()
    val OK get() = ok.now()
    val BAD get() = bad.now()
    val CAUTION get() = caution.now()

    // The gold button, the same in light and dark
    val GOLD = colour("#E3B341")
    val ON_GOLD = colour("#1E1500")

    // Filled tags and squares with white writing: the Bubble Sync ✓/✗ tag (its green and red are the owner's choice) and
    // the marked squares in the Quest Helper's puzzle grids
    private val tagGreen = shade("#2F6B34", "#3F7F33")
    private val tagRed = shade("#A32D25", "#B5473A")
    val TAG_GREEN get() = tagGreen.now()
    val TAG_RED get() = tagRed.now()

    // Behind a tool window's contents (seen for a moment while a web page loads)
    val WINDOW get() = SURFACE
}

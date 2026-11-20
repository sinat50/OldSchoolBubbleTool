package com.sinat.osrsbubbletool

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.edit
import androidx.core.graphics.toColorInt
import kotlin.math.abs
import kotlin.math.roundToInt

class BubbleService : Service() {

    companion object {
        const val WIKI_URL = "https://oldschool.runescape.wiki/"
        const val XP_CALCULATOR_URL = "https://oldschool.tools/calculators/skill"
        const val DPS_CALCULATOR_URL = "https://tools.runescape.wiki/osrs-dps/"
        // Tool windows: touches outside reach the game (and the window hears of them, ACTION_OUTSIDE); the game keeps
        // the keyboard, and a window takes it only while you type in it (setWindowTakesKeyboard)
        const val WINDOW_FLAGS = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        const val DPS_WIDTH_FRACTION = 0.6f  // DPS Calculator window: 60% of the screen width in landscape
        const val DPS_ZOOM_PERCENT = 100     // DPS Calculator text size
        const val TEXT_SIZE_WAIT_MS = 700L   // Tool text size: the tools are built again this long after the last − or + tap
        const val SHOOTING_STARS_URL = "https://07.gg/trackers/shooting-star"
        const val SHOOTING_STARS_ZOOM_PERCENT = 85  // Shooting Star Tracker text size
        const val TEARS_OF_GUTHIX_URL = "https://togcrowdsourcing.com/"   // this week's stream orders, by world
        const val TEARS_OF_GUTHIX_ZOOM_PERCENT = 85 // Tears of Guthix Worlds text size
        const val PRICES_URL = "https://prices.runescape.wiki/osrs/"
        const val PRICES_ZOOM_PERCENT = 85          // GE Prices text size
        const val ZULRAH_WIDTH_DP = 136      // the Zulrah Helper is a thin strip down the side of the screen
        const val ZULRAH_MIN_WIDTH_DP = 124  // (narrow enough for the game; wide enough for "Zulrah" in its top bar)
        const val LIGHT_BOX_WIDTH_INCHES = 1.3f  // the Light Box Solver is a small box, top right
        const val PUZZLE_BOX_WIDTH_INCHES = 1.5f // so is the Puzzle Box Solver
        // where a tool's window can shrink to (setWindowCompact)
        const val COMPACT_OFF = 0
        const val COMPACT_TOP_LEFT = 1
        const val COMPACT_BOTTOM_LEFT = 2
        const val BUBBLE_FADED_ALPHA = 0.4f // how see-through the bubble fades to (Settings → Fade when not in use)
        const val BUBBLE_FADE_MS = 3000L    // after this long without being touched
        const val BAR_HEIGHT_DP = 30        // height of the window's top bar (thin, to leave room for the tool)
        const val WINDOW_CORNER_DP = 12     // how rounded the windows and the menu are
        const val PAGE_ZOOM_PERCENT = 70    // wiki text size: 100 = normal, lower = smaller
        const val XP_CALCULATOR_ZOOM_PERCENT = 85  // XP Calculator text size
        const val BACK_TAG = "back"         // (finds a window's ◀ button)
        const val KEYBOARD_FREE_PART = 0.4f // the top part of the screen the keyboard leaves free (sideways)
        const val NOTES_TYPING_WIDTH = 0.6f // how much of the screen's width the Notepad takes while you type (sideways)
        const val MENU_WIDTH_DP = 224       // width of the long-press menu (at the normal tool text size)
        // tools whose window starts only as tall as what's in them
        val FIT_TO_CONTENTS = setOf(Tool.IDLE_NOTIFIER, Tool.MIXOLOGY, Tool.INVENTORY_SETUPS)
        const val MENU_MAX_PORTRAIT_DP = 340 // tallest the long-press menu gets in portrait (it scrolls past that)
        private const val CARVED = "carved"  // a window corner carved into the Zen Garden's frame (see applyFrameless)

        // Messages from CapturePermissionActivity
        const val ACTION_CAPTURE_RESULT = "com.sinat.osrsbubbletool.CAPTURE_RESULT"

        // A wiki link opened from another app (like the game's wiki button), sent by WikiLinkActivity
        const val ACTION_OPEN_WIKI = "com.sinat.osrsbubbletool.OPEN_WIKI"
        const val EXTRA_URL = "url"

        // Sent by SyncScanActivity when the camera closes (scanned or not): the Bubble Sync window comes back
        const val ACTION_SYNC_SCAN_DONE = "com.sinat.osrsbubbletool.SYNC_SCAN_DONE"

        // True while the bubble is on (so you're most likely playing)
        @Volatile var isRunning = false
        // whether the bubble's tool windows were built dark (the app's main screen puts the shared colours back to
        // this when it goes to the background, see MainActivity.onPause)
        @Volatile var builtDark = false
            private set
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        // Dropdown menus normally open as a pop-up, which Android can't show from a floating
        // window. This makes a dropdown open as a list right on the page instead.
        const val INLINE_DROPDOWNS_JS = """
            (function() {
                if (window.__inlineSelects) return;
                window.__inlineSelects = true;
                function openList(e) {
                    var s = e.target.closest ? e.target.closest('select') : null;
                    if (!s || s.multiple || s.size > 1) return;
                    e.preventDefault();
                    s.size = Math.min(Math.max(s.options.length, 2), 8);
                    s.focus();
                }
                function closeList(s) { s.removeAttribute('size'); }
                document.addEventListener('touchstart', openList, { capture: true, passive: false });
                document.addEventListener('mousedown', openList, true);
                document.addEventListener('change', function(e) {
                    if (e.target.tagName === 'SELECT') closeList(e.target);
                }, true);
                document.addEventListener('focusout', function(e) {
                    var s = e.target;
                    if (s.tagName === 'SELECT') setTimeout(function() { closeList(s); }, 150);
                }, true);
            })();
        """

        // Shooting Star Tracker: a star's details sheet is drawn over the map, pinned to the
        // bottom and unable to scroll, so in a short window its top and bottom get cut off.
        // This makes the sheet fill the window, starts the details below the Close button,
        // lets them scroll, and keeps swipes on them from moving the map instead.
        const val STAR_DETAILS_FIX_JS = """
            (function() {
                if (window.__bubbleStarFix) return;
                window.__bubbleStarFix = true;
                var s = document.createElement('style');
                s.textContent =
                    '[vaul-drawer]{top:0!important;height:100%!important;margin-top:0!important;}' +
                    '[vaul-drawer] .leaflet-bottom.leaflet-left{top:52px!important;bottom:0!important;' +
                    'overflow-y:auto!important;pointer-events:auto!important;padding-bottom:70px!important;}' +
                    '[vaul-drawer] .leaflet-bottom.leaflet-left .leaflet-overlay{pointer-events:auto!important;}';
                document.head.appendChild(s);
                function keepForPanel(e) {
                    var panel = e.target.closest && e.target.closest('[vaul-drawer] .leaflet-bottom.leaflet-left');
                    if (panel) e.stopPropagation();
                }
                ['wheel','touchstart','touchmove','pointerdown','pointermove','mousedown','mousemove']
                    .forEach(function(t) { document.addEventListener(t, keepForPanel, true); });
            })();
        """

        // Like FIT_WIDTH_JS, but also counts things sticking out past the LEFT edge.
        // (A page that centres something wider than the window pushes half of it off the left
        // side, where you can't scroll to it. The GE Prices search bar does this.)
        const val FIT_BOTH_SIDES_JS = """
            (function() {
                var root = document.documentElement;
                root.style.zoom = '';
                var windowWidth = root.clientWidth;
                var pageWidth = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);
                var left = 0, right = Math.max(pageWidth, windowWidth);
                var all = document.body ? document.body.getElementsByTagName('*') : [];
                for (var i = 0; i < all.length && i < 4000; i++) {
                    var r = all[i].getBoundingClientRect();
                    // only things partly on screen: ignores hidden off-screen menus and skip-links
                    if (r.width <= 0 || r.height <= 0 || r.right <= 0 || r.width > windowWidth * 3) continue;
                    if (r.left < left) left = r.left;
                }
                var needed = right - left;
                if (needed > windowWidth + 1) {
                    root.style.zoom = windowWidth / needed;
                }
            })();
        """

        // GE Prices: the price graphs grab every touch (to show the price under your finger),
        // so once you'd scrolled down to them you couldn't scroll back up. This lets an up/down
        // swipe on a graph scroll the page, while a sideways drag still reads the prices.
        const val CHART_SCROLL_FIX_JS = """
            (function() {
                if (window.__bubbleChartScroll) return;
                window.__bubbleChartScroll = true;
                var charts = 'canvas, svg, .highcharts-container, .recharts-wrapper, [class*="chart"], [class*="Chart"]';
                var style = document.createElement('style');
                style.textContent = charts.split(',').map(function(s) {
                    return s.trim() + ', ' + s.trim() + ' *';
                }).join(', ') + ' { touch-action: pan-y !important; }';
                document.head.appendChild(style);

                var startX = 0, startY = 0, mode = null;
                function onChart(t) { return t && t.closest ? t.closest(charts) : null; }
                window.addEventListener('touchstart', function(e) {
                    var t = e.touches[0];
                    startX = t.clientX; startY = t.clientY;
                    mode = onChart(e.target) ? 'undecided' : null;
                }, { capture: true, passive: true });
                function move(e, x, y) {
                    if (mode === null) return;
                    var dx = Math.abs(x - startX), dy = Math.abs(y - startY);
                    if (mode === 'undecided' && (dx > 8 || dy > 8)) mode = dy > dx ? 'scroll' : 'chart';
                    // an up/down swipe: keep it away from the graph so the page scrolls
                    if (mode === 'scroll') e.stopImmediatePropagation();
                }
                window.addEventListener('touchmove', function(e) {
                    move(e, e.touches[0].clientX, e.touches[0].clientY);
                }, { capture: true, passive: true });
                window.addEventListener('pointermove', function(e) {
                    if (e.pointerType === 'touch') move(e, e.clientX, e.clientY);
                }, { capture: true, passive: true });
                window.addEventListener('touchend', function() { mode = null; }, true);
            })();
        """

        // Runs as a page starts loading: touch handlers the page adds from then on can't block
        // scrolling (the browser ignores their attempts to), for the GE Prices graphs
        const val PASSIVE_TOUCH_JS = """
            (function() {
                if (window.__bubblePassive) return;
                window.__bubblePassive = true;
                var add = EventTarget.prototype.addEventListener;
                EventTarget.prototype.addEventListener = function(type, fn, opts) {
                    if (type === 'touchstart' || type === 'touchmove') {
                        if (typeof opts === 'object' && opts !== null) opts = Object.assign({}, opts, { passive: true });
                        else opts = { capture: !!opts, passive: true };
                    }
                    return add.call(this, type, fn, opts);
                };
            })();
        """

        // Measures the page and scales it down so nothing sticks out past the window's edges
        const val FIT_WIDTH_JS = """
            (function() {
                var root = document.documentElement;
                root.style.zoom = '';
                var pageWidth = Math.max(root.scrollWidth, document.body ? document.body.scrollWidth : 0);
                var windowWidth = root.clientWidth;
                if (pageWidth > windowWidth) {
                    root.style.zoom = windowWidth / pageWidth;
                }
            })();
        """
    }

    // The tools the bubble can open, in menu order under their headings (`group`). A new tool gets added here and in
    // makeParts() below (the build stops with an error until it's in both). Settings is last: the menu shows it apart
    // from the tools, at the bottom. `icon` is its picture in Icons.
    // inMenu = false: not one of the tools (left out of the tool list in the menu and in Settings → Tools in the menu)
    enum class Tool(val label: String, val group: ToolGroup?, val icon: String, val beta: Boolean = false, val inMenu: Boolean = true) {
        PUZZLE_BOX("Puzzle Box Solver", ToolGroup.GUIDES, "puzzle"),
        LIGHT_BOX("Light Box Solver", ToolGroup.GUIDES, "bulb"),
        TOA_PUZZLES("ToA Puzzle Helper", ToolGroup.GUIDES, "pyramid"),
        MIXOLOGY("Mastering Mixology", ToolGroup.GUIDES, "flask"),   // recipes beside the orders and dots on the levers
        ZULRAH("Zulrah Helper", ToolGroup.GUIDES, "island"),
        QUESTS("Quest Helper", ToolGroup.GUIDES, "book", beta = true),
        XP_CALCULATOR("XP Calculator", ToolGroup.CALCULATORS, "xp"),
        DPS_CALCULATOR("DPS Calculator", ToolGroup.CALCULATORS, "sword"),
        PRICES("GE Prices", ToolGroup.CALCULATORS, "chart"),
        CALCULATOR("Calculator", ToolGroup.CALCULATORS, "calc"),
        FARMING("Timers", ToolGroup.TRACKERS, "clock"),
        IDLE_NOTIFIER("Idle Notifier", ToolGroup.TRACKERS, "bell"),   // buzzes when your character stops working
        SHOOTING_STARS("Shooting Star Tracker", ToolGroup.TRACKERS, "star"),
        TEARS_OF_GUTHIX("Tears of Guthix Worlds", ToolGroup.TRACKERS, "drop"),
        HUNTER("Hunter Rumours", ToolGroup.TRACKERS, "target"),
        SYNC("Bubble Sync", ToolGroup.UTILITIES, "sync"),   // your quests, levels and diaries, scanned from the Bubble Tool Sync plugin
        WIKI("Wiki", ToolGroup.UTILITIES, "globe"),
        INVENTORY_SETUPS("Gear Loadouts", ToolGroup.UTILITIES, "helmet"),   // (named Inventory Setups before; the name stays, it's saved in settings)
        TELEPORTS("Teleport Finder", ToolGroup.UTILITIES, "pin", beta = true),
        NOTES("Notepad", ToolGroup.UTILITIES, "note"),
        GAMES("Game Room", ToolGroup.UTILITIES, "dice"),
        SETTINGS("Settings", null, "gear", inMenu = false)   // (in the menu below the tools, by Close bubble)
    }

    private lateinit var windowManager: WindowManager
    private lateinit var bubble: ImageView
    private lateinit var bubbleParams: WindowManager.LayoutParams
    private lateinit var capture: CaptureManager   // screen capture shared by all tools
    private var syncReturnTo: Tool? = null   // the tool that opened Bubble Sync, for the back button

    // What the bubble needs from one tool: what goes in its window, and what to do when things happen.
    // (Tools that don't care about something leave it out.)
    private class ToolParts(
        val buildView: () -> View,
        val onBack: (() -> Unit)?,         // the window's ◀ button; null hides it
        val onClosed: () -> Unit = {},     // its window was closed
        val onRotated: () -> Unit = {},    // the phone turned
        val onDestroy: () -> Unit = {},    // the bubble is closing: tidy up
        val isBusy: () -> Boolean = { false },  // still working with its window closed (a puzzle guide on screen)
        val heightFor: ((Int) -> Int)? = null,  // the window's contents' height for a window this wide (the window then
                                                // fits it, and the resize corner only changes the width: Zulrah)
        val canGoBack: () -> Boolean = { true } // ◀ would do something here (it's hidden on a tool's first page)
    )
    // Each tool is only set up the first time you open it, so tools you don't use cost nothing
    private val toolParts = mutableMapOf<Tool, ToolParts>()
    private fun partsFor(tool: Tool) = toolParts.getOrPut(tool) { makeParts(tool) }

    private val toolWindows = mutableMapOf<Tool, LinearLayout>() // built once, reused
    private var currentTool = Tool.WIKI
    private var shownWindow: LinearLayout? = null
    private var shownParams: WindowManager.LayoutParams? = null
    private var windowX = 0            // remembers where you dragged the window
    private var windowY: Int? = null   // …and how far down (null = centred)
    private val sizes by lazy { getSharedPreferences("window_sizes", MODE_PRIVATE) }   // sizes you've dragged windows to
    private var menu: View? = null
    private var menuList: ScrollView? = null   // the menu's scrolling list
    private val webViews = mutableListOf<WebView>()

    // The tools are built with this instead of the bubble itself: the same, except that its writing is the size
    // set in Settings → Tool text size (Android's font size, scaled). Kept up to date when the phone turns.
    private var scaledResources: android.content.res.Resources? = null
    private val toolContext = object : android.content.ContextWrapper(this) {
        override fun getResources(): android.content.res.Resources = scaledResources ?: super.getResources()
    }

    private fun updateToolTextSize() {
        val scale = AppSettings.toolTextSize(this) / AppSettings.TEXT_NORMAL.toFloat()
        scaledResources = if (scale == 1f) null else createConfigurationContext(
            Configuration(resources.configuration).apply { fontScale *= scale }).resources
    }
    private val webZoom = mutableMapOf<WebView, Int>()   // each page's own text size, before the Settings text size
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        updateForeground(capturing = false)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        Palette.dark = AppSettings.isDark(this)
        builtDark = Palette.dark
        updateToolTextSize()
        AppSettings.prefs(this).registerOnSharedPreferenceChangeListener(settingsChanged)
        windowX = dp(8)
        lastScreenSize = screenWidth() to screenHeight()
        // Android forgets an app's alarms if it's force-stopped; set the farming ones again
        try { FarmingTimers.rescheduleAll(this) } catch (e: Exception) { }
        capture = CaptureManager(this) { capturing ->
            if (!destroyed) updateForeground(capturing)
        }
        createBubble()
        addBarProbes()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CAPTURE_RESULT) capture.onPermissionResult(intent)
        // (checked again here: only real OSRS Wiki pages open in the Wiki window, see WikiAddress)
        if (intent?.action == ACTION_OPEN_WIKI) intent.getStringExtra(EXTRA_URL)?.let { WikiAddress.safe(it) }?.let { openWikiLink(it) }
        if (intent?.action == ACTION_SYNC_SCAN_DONE) openTool(Tool.SYNC)
        return START_NOT_STICKY
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun dp(value: Float) = value * resources.displayMetrics.density

    // The full screen size, including the camera cutout area at the edge
    private fun screenWidth(): Int = fullScreenSize(windowManager).first
    private fun screenHeight(): Int = fullScreenSize(windowManager).second

    // Let a window go right up to the screen edges, even beside the camera cutout
    private fun allowScreenEdges(p: WindowManager.LayoutParams) {
        p.flags = p.flags or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            p.fitInsetsTypes = 0
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    // Android requires a small notification while an app runs in the background.
    // While screen capture is on, the notification must also say so.
    private fun updateForeground(capturing: Boolean) {
        val channelId = "bubble"
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channelId, "Tool bubble", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = Notification.Builder(this, channelId)
            .setContentTitle("Old School Bubble Tool")
            .setContentText(
                if (capturing) "Screen capture is on. Long-press the bubble → Close bubble to turn it off."
                else "Long-press the bubble for the tool menu"
            )
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .build()

        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                if (capturing) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                startForeground(1, notification, type)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && capturing ->
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            else -> startForeground(1, notification)
        }
    }

    // ---------------- Bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun createBubble() {
        bubble = ImageView(this).apply {
            setImageResource(R.drawable.ic_bubble)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        bubbleParams = WindowManager.LayoutParams(
            bubbleSize(), bubbleSize(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(8)
            y = dp(100)
        }
        allowScreenEdges(bubbleParams)
        restoreBubblePlace()

        // Tap = open/hide current tool, drag = move, long-press = tool menu
        val touchSlop = ViewConfiguration.get(this).scaledTouchSlop
        var startX = 0
        var startY = 0
        var touchX = 0f
        var touchY = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            longPressed = true
            if (AppSettings.vibrates(this)) bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            showMenu()
        }

        bubble.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    unfadeBubble()
                    startX = bubbleParams.x
                    startY = bubbleParams.y
                    touchX = event.rawX
                    touchY = event.rawY
                    dragging = false
                    longPressed = false
                    bubble.postDelayed(longPress, AppSettings.longPressMs(this))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (longPressed) return@setOnTouchListener true // menu is open; don't drag
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!dragging && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
                        dragging = true
                        bubble.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        bubbleParams.x = startX + dx.toInt()
                        bubbleParams.y = startY + dy.toInt()
                        clampBubble()   // stay on screen, so the next drag starts from where it really is
                        windowManager.updateViewLayout(bubble, bubbleParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    bubble.removeCallbacks(longPress)
                    if (dragging) {
                        saveBubblePlace()     // so it starts here next time
                        bubbleAside = false   // dropped beside an open window: this is its home now, it stays here
                    }
                    if (!dragging && !longPressed) toggleToolWindow()
                    fadeBubbleLater()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    bubble.removeCallbacks(longPress)
                    fadeBubbleLater()
                    true
                }
                else -> false
            }
        }

        windowManager.addView(bubble, bubbleParams)
        fadeBubbleLater()
    }

    private fun bubbleSize() = dp(AppSettings.bubbleSizeDp(this))

    // Settings → Fade when not in use: the bubble turns see-through a few seconds after you last touched it
    // (not while its menu is open), and solid again as soon as you touch it
    private val fadeBubble = Runnable {
        if (AppSettings.bubbleFades(this) && menu == null && !destroyed)
            bubble.animate().alpha(BUBBLE_FADED_ALPHA).setDuration(400).start()
    }

    private fun unfadeBubble() {
        bubble.removeCallbacks(fadeBubble)
        bubble.animate().cancel()
        bubble.alpha = 1f
    }

    private fun fadeBubbleLater() {
        bubble.removeCallbacks(fadeBubble)
        if (AppSettings.bubbleFades(this)) bubble.postDelayed(fadeBubble, BUBBLE_FADE_MS)
    }

    // ---------------- Long-press menu ----------------

    @SuppressLint("ClickableViewAccessibility")
    private fun showMenu(fresh: Boolean = true) {
        hideMenu()
        // as wide as the tool text size needs: 224 dp at 100%, wider for bigger writing and narrower for smaller, so
        // the names fit at any size (never wider than most of the screen)
        val textScale = AppSettings.toolTextSize(this) / AppSettings.TEXT_NORMAL.toFloat()
        val menuWidth = minOf(dp((MENU_WIDTH_DP * textScale).roundToInt()), screenWidth() - dp(16))

        // the headings that are unfolded: as you left them, plus the open tool's own group when the menu opens
        // (shown again after a heading or a favourite changes, it keeps what was showing)
        if (fresh) menuShowing = menuOpenGroups() + listOfNotNull(currentTool.group)
        val list = menuContents()

        // The list scrolls when there are more tools than fit on the screen
        val scroll = ScrollView(this).apply {
            background = GradientDrawable().apply {
                setColor(Palette.RAISED)   // lighter than the main screen and windows, so the menu stands out from them
                cornerRadius = dp(WINDOW_CORNER_DP).toFloat()
            }
            clipToOutline = true   // (so the open tool's shade keeps to the rounded corners)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false   // always show the scrollbar so it's clear there's more
            addView(list)

        }

        // The thin frame goes on a holder round the list, so it stays still while the list scrolls
        val framed = FrameLayout(this).apply {
            addView(scroll, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            foreground = thinFrame()
            // tapping anywhere outside the menu closes it
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_OUTSIDE) hideMenu()
                false
            }
        }

        // How tall the menu needs to be, limited to what fits on the screen
        list.measure(
            View.MeasureSpec.makeMeasureSpec(menuWidth - dp(2), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val screenH = screenHeight()
        val (top, bottom) = menuSpace()
        // In portrait the whole list would reach down most of the screen; keep it to a size
        // that's easy to reach, and let it scroll
        val portrait = screenH > screenWidth()
        val limit = if (portrait) minOf(bottom - top, dp(MENU_MAX_PORTRAIT_DP)) else bottom - top
        val menuHeight = minOf(list.measuredHeight + dp(2), limit)
        scroll.isScrollbarFadingEnabled = list.measuredHeight + dp(2) <= menuHeight  // fade if nothing to scroll

        // place the menu beside the bubble, flipping to the left side if there's no room (a navigation bar down the
        // right side counts as no room: it would sit under it. Top and bottom use the insets above, which also keep
        // clear of the camera cutout, which the bars don't track.)
        val gap = dp(6)
        var x = bubbleParams.x + bubbleSize() + gap
        if (x + menuWidth > bars.clearRight(screenWidth())) x = maxOf(bars.left, bubbleParams.x - menuWidth - gap)

        val params = WindowManager.LayoutParams(
            menuWidth,
            menuHeight,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            // start level with the bubble, but move up if it would run off the bottom
            y = bubbleParams.y.coerceIn(top, maxOf(top, bottom - menuHeight))
        }
        allowScreenEdges(params)

        windowManager.addView(framed, params)
        menu = framed
        menuList = scroll
    }

    // Which of the menu's headings are unfolded, remembered between menus (all folded to start with)
    private val menuPrefs by lazy { getSharedPreferences("bubble_menu", MODE_PRIVATE) }
    private fun menuOpenGroups(): Set<ToolGroup> = (menuPrefs.getStringSet("open_groups", null) ?: emptySet())
        .mapNotNull { name -> ToolGroup.entries.firstOrNull { it.name == name } }.toSet()

    private var menuShowing: Set<ToolGroup> = emptySet()   // the headings unfolded in the menu on screen

    // The menu's list: Favourites, the groups (as menuShowing says), Settings and Close bubble
    private fun menuContents() = BubbleMenu(toolContext, currentTool, AppSettings.hiddenTools(this), AppSettings.favourites(this),
        menuShowing,
        onOpen = { tool -> hideMenu(); openTool(tool) },
        onHold = ::holdMenuTool,
        onToggleGroup = ::toggleMenuGroup,
        onCloseBubble = { hideMenu(); stopSelf() }
    ).build()

    // How far down the menu may start and end: clear of the status bar and the navigation bar / gesture strip when
    // they're showing (and of the camera cutout, which the bars don't track), with a small margin
    private fun menuSpace(): Pair<Int, Int> {
        var topInset = 0
        var bottomInset = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bars = windowManager.currentWindowMetrics.windowInsets.getInsets(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
            topInset = bars.top
            bottomInset = bars.bottom
        }
        val margin = dp(8)
        return (topInset + margin) to (screenHeight() - bottomInset - margin)
    }

    // A heading was folded or unfolded (the menu already shows it): remember it for next time, and make the menu's
    // window as tall as its list now is (in place, without building the menu again)
    private fun toggleMenuGroup(group: ToolGroup, open: Boolean) {
        menuShowing = if (open) menuShowing + group else menuShowing - group
        menuPrefs.edit { putStringSet("open_groups", menuShowing.map { it.name }.toSet()) }
        fitMenuWindow()
    }

    // The menu's window as tall as its list now is (up to what fits on the screen), changed in place
    private fun fitMenuWindow() {
        val framed = menu ?: return
        val scroll = menuList ?: return
        val list = scroll.getChildAt(0) ?: return
        val params = framed.layoutParams as? WindowManager.LayoutParams ?: return
        list.measure(View.MeasureSpec.makeMeasureSpec(params.width - dp(2), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val (top, bottom) = menuSpace()
        val portrait = screenHeight() > screenWidth()
        val limit = if (portrait) minOf(bottom - top, dp(MENU_MAX_PORTRAIT_DP)) else bottom - top
        params.height = minOf(list.measuredHeight + dp(2), limit)
        scroll.isScrollbarFadingEnabled = list.measuredHeight + dp(2) <= params.height
        // grown past the bottom of the screen: moved up just enough
        params.y = params.y.coerceIn(top, maxOf(top, bottom - params.height))
        windowManager.updateViewLayout(framed, params)
    }

    // A tool was held in the menu: it joins or leaves Favourites, and the menu shows the change where you were
    private fun holdMenuTool(tool: Tool) {
        val hidden = AppSettings.hiddenTools(this)
        val changed = Favourites.toggled(AppSettings.favourites(this).filter { it !in hidden }, tool.name)
        if (changed == null) {
            Toast.makeText(this, "Up to ${Favourites.MOST} favourites", Toast.LENGTH_SHORT).show()
            return
        }
        AppSettings.setFavourites(this, changed)
        if (AppSettings.vibrates(this)) bubble.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        // the list changes in place, in the same menu window (built again as a new window, the menu flashed)
        val scroll = menuList ?: return
        val scrolledTo = scroll.scrollY
        scroll.removeAllViews()
        scroll.addView(menuContents())
        fitMenuWindow()
        scroll.post { scroll.scrollTo(0, scrolledTo) }
    }

    // The thin frame round the menu and the tool windows, so they stand out from the game: a dark line on the very
    // edge (for bright scenes) and the lighter frame just inside it (for dark ones). Drawn over the window's own edge,
    // so the window is no bigger.
    private fun thinFrame(): android.graphics.drawable.Drawable {
        val line = dp(1)
        val corner = dp(WINDOW_CORNER_DP).toFloat()
        val outer = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(line, Palette.EDGE)
            cornerRadius = corner
        }
        val inner = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            setStroke(line, Palette.BORDER)
            cornerRadius = corner - line
        }
        return android.graphics.drawable.LayerDrawable(arrayOf(outer, inner)).apply { setLayerInset(1, line, line, line, line) }
    }

    private fun hideMenu() {
        val wasOpen = menu != null
        menu?.let { windowManager.removeView(it) }
        menu = null
        if (wasOpen) fadeBubbleLater()
    }

    // ---------------- Tool windows ----------------

    // Shows a wiki page in the Wiki window (from the game's wiki button, for example)
    private var wikiWeb: WebView? = null
    private var pendingWikiUrl: String? = null

    // Opens a wiki page in the Wiki window. If another tool sent you there (returnTo), the ◀ button
    // on that first page takes you back to it.
    private fun openWikiLink(url: String, returnTo: Tool? = null) {
        hideMenu()
        val web = wikiWeb
        wikiReturnIndex = web?.copyBackForwardList()?.currentIndex ?: -1   // the page before the one we're opening
        if (web == null) pendingWikiUrl = url else web.loadUrl(url)   // built with this page if new
        if (currentTool != Tool.WIKI || shownWindow == null) openTool(Tool.WIKI)
        wikiReturnTo = returnTo
        backButtonSoon()   // (◀ goes back to the tool that sent you, or the page before)
    }

    private var wikiReturnTo: Tool? = null
    private var wikiReturnIndex = -1

    // The Wiki window's ◀ button
    private fun wikiBack(web: WebView) {
        val back = wikiReturnTo
        if (back != null && web.copyBackForwardList().currentIndex <= wikiReturnIndex + 1) {
            wikiReturnTo = null
            openTool(back)
        } else if (web.canGoBack()) web.goBack()
    }

    private fun openTool(tool: Tool) {
        if (tool != Tool.WIKI) wikiReturnTo = null   // went somewhere else: forget the way back
        if (tool != Tool.SYNC) syncReturnTo = null
        hideToolWindow()
        currentTool = tool
        showToolWindow()
    }

    // A tool's ✓/✗ sync tag was tapped: open Bubble Sync, with ◀ going back to that tool
    private fun openSync(from: Tool) {
        openTool(Tool.SYNC)
        syncReturnTo = from
        updateBackButton()   // (◀ goes back to that tool)
    }

    private fun toggleToolWindow() {
        if (shownWindow != null) { hideToolWindow(); return }
        // the last tool (the Wiki at first) was left out of the menu in Settings: tapping the bubble opens the first
        // tool still in the menu instead (or Settings, if every tool was left out)
        val hidden = AppSettings.hiddenTools(this)
        if (currentTool.name in hidden) {
            currentTool = Tool.entries.firstOrNull { it.inMenu && it.name !in hidden } ?: Tool.SETTINGS
        }
        showToolWindow()
    }

    private fun showToolWindow() {
        // the phone's dark mode changed while a window was open (still waits if a puzzle guide is on screen)
        if (darkModeWaiting) applyDarkMode(fromPhone = true)
        // the tool text size changed while a guide was running: done now, unless one still is
        if (textSizeWaiting) rebuildForTextSize.run()
        val window = toolWindows.getOrPut(currentTool) { buildToolWindow(currentTool) }
        if (currentTool == Tool.GAMES) applyFrameless(window)
        if (currentTool == Tool.ZULRAH) placeResizeCorner(window)
        val params = makeWindowParams()
        fitToContents(window, params)
        rememberWanted(params)
        placeClearOfBars(params)
        windowManager.addView(window, params)
        shownWindow = window
        shownParams = params
        updateBackButton()
        stepBubbleAside(params)
        setWebPagesRunning(true)
        // re-add the bubble so it stays on top of the window
        windowManager.removeView(bubble)
        windowManager.addView(bubble, bubbleParams)
    }

    // Tools with little in them start only as tall as what's in them (until you resize them yourself), so they don't
    // cover the game with empty window; what's in them can still scroll if it grows later
    private fun fitToContents(window: View, params: WindowManager.LayoutParams) {
        if (currentTool !in FIT_TO_CONTENTS || sizes.contains(sizeKey(currentTool) + "_w") || params.height <= 0) return
        window.measure(View.MeasureSpec.makeMeasureSpec(params.width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val wanted = window.measuredHeight + dp(10)   // (a little room under the last line, clear of the resize corner)
        if (wanted <= 0 || wanted >= params.height) return
        val screenH = screenHeight()
        val centred = (screenH - params.height) / 2
        params.height = maxOf(minWindowHeight, wanted)
        // (still in the middle, if it was put there for its full height and not moved since)
        if (windowY == null && params.y == centred) params.y = (screenH - params.height) / 2
    }

    private fun hideToolWindow() {
        if (compactSaved != null) setWindowCompact(COMPACT_OFF)   // a shrunk window opens whole, at its normal size, next time
        liftedFrom = null   // (moved up for the keyboard: it opens at its own place next time, which wasn't changed)
        notesNarrow = null  // (the same for the Notepad widened for typing)
        val wasShowing = shownWindow != null
        shownWindow?.let { windowManager.removeView(it) }
        shownWindow = null
        shownParams = null
        if (wasShowing) {
            setWebPagesRunning(false)
            toolParts[currentTool]?.onClosed?.invoke()
            // the bubble waited beside the window: back home now (not while the bubble itself is closing)
            if (bubbleAside && !destroyed) {
                bubbleGoHome()
                windowManager.updateViewLayout(bubble, bubbleParams)
            }
        }
    }

    // ---------------- Keeping the bubble off the open window ----------------

    // A window opens where you last left it, and that may be right where the bubble sits, so the bubble would hide the
    // start of its writing and catch taps meant for it. The bubble then waits beside the window's top bar (right if
    // there's room, else left) and goes back home when the window closes. Dragging it while it waits there makes that
    // spot its new home (a drag saves the bubble's place).
    private var bubbleAside = false

    private fun stepBubbleAside(params: WindowManager.LayoutParams) {
        val (sw, sh) = fullScreenSize(windowManager)
        val spot = ScreenBars.bubbleBeside(bubbleParams.x, bubbleParams.y, bubbleSize(),
            Box(params.x, params.y, params.width, params.height), dp(BAR_HEIGHT_DP), dp(6), sw, sh, bars) ?: return
        bubbleParams.x = spot.x
        bubbleParams.y = spot.y
        bubbleAside = true
        windowManager.updateViewLayout(bubble, bubbleParams)
    }

    // The bubble back where you left it (or where it starts, if it was never moved)
    private fun bubbleGoHome() {
        bubbleAside = false
        bubbleParams.x = dp(8)
        bubbleParams.y = dp(100)
        restoreBubblePlace()
        clampBubble()
    }

    // The Game Room's Zen Garden shows with no window around it: no top bar or background. Its move and resize
    // symbols are carved into the garden frame's top-right and bottom-right corners (SandGardenView draws them), so
    // the window's own move and resize corners become invisible touch areas the size of those carved corners, on the
    // tray's corners (see pinGamesCorners). Asked for while the window is being built, so it's remembered and
    // applied once the window exists.
    private var gamesFrameless = false
    private var gamesMover = true   // the window's own move corner answers (the garden); chess moves by its top bar

    private fun setGamesFrameless(on: Boolean, mover: Boolean) {
        gamesFrameless = on
        gamesMover = mover
        toolWindows[Tool.GAMES]?.let { applyFrameless(it) }
    }

    // Chess's top bar was dragged (dx, dy from where the finger went down; start: the drag is beginning)
    private var gamesDragX = 0
    private var gamesDragY = 0

    private fun dragGamesWindow(dx: Int, dy: Int, start: Boolean) {
        val params = shownParams ?: return
        if (start) { gamesDragX = params.x; gamesDragY = params.y; return }
        moveWindowTo(params, gamesDragX + dx, gamesDragY + dy, moveY = true)
    }

    private fun applyFrameless(window: LinearLayout) {
        val on = gamesFrameless
        window.getChildAt(0).visibility = if (on) View.GONE else View.VISIBLE
        window.background?.alpha = if (on) 0 else 255
        window.clipToOutline = !on
        window.foreground = if (on) null else thinFrame()   // (the Zen Garden shows with no window round it)
        (window.getChildAt(1) as? ViewGroup)?.let { body ->
            if (body.childCount < 3) return@let
            val corner = body.getChildAt(1)
            val mover = body.getChildAt(2)
            mover.visibility = if (on && gamesMover) View.VISIBLE else View.GONE
            // carved into the frame (invisible here, and only their triangle answers), or the window's own symbols
            val carved = dp((SandGardenView.CORNER_DP + SandGardenView.FRAME_DP).roundToInt())
            for ((v, size) in listOf(corner to dp(26), mover to dp(30))) {
                v.tag = if (on) CARVED else null
                (v.layoutParams as? FrameLayout.LayoutParams)?.let {
                    it.width = if (on) carved else size
                    it.height = if (on) carved else size
                    v.layoutParams = it
                }
                v.invalidate()
            }
        }
        if (!on) pinGamesCorners(-1, -1, -1, -1)   // the resize corner back in the window's corner
    }

    // The Zen Garden's tray may not fill its window (it keeps its shape when scaled): put the move and resize
    // symbols on the tray's own top-right and bottom-right corners (given in the window's content; -1 = the window's)
    private fun pinGamesCorners(left: Int, top: Int, right: Int, bottom: Int) {
        val body = toolWindows[Tool.GAMES]?.getChildAt(1) as? ViewGroup ?: return
        if (body.childCount < 3) return
        val corner = body.getChildAt(1)
        val mover = body.getChildAt(2)
        gamesTray[0] = left; gamesTray[1] = top; gamesTray[2] = right; gamesTray[3] = bottom
        // the window shrinks to the tray (then the symbols sit in its own corners)
        val reset = right < 0 || (!resizingWindow && fitGamesWindowToTray())
        (corner.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.rightMargin = if (reset) 0 else maxOf(0, body.width - right)
            it.bottomMargin = if (reset) 0 else maxOf(0, body.height - bottom)
            corner.layoutParams = it
        }
        (mover.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.rightMargin = if (reset) 0 else maxOf(0, body.width - right)
            it.topMargin = if (reset) 0 else maxOf(0, top)
            mover.layoutParams = it
        }
    }

    // A garden scaled to fit a window of a different shape leaves a see-through band beside it, which you couldn't
    // move off the screen edge (so the garden couldn't reach the top) and which caught taps meant for the game.
    // So in zen mode the window is shrunk to the tray itself, keeping the tray where it is on screen. Done when the
    // tray is placed, but not while the resize corner is being dragged (it's done when you let go instead).
    // Returns true if the window was shrunk.
    private val gamesTray = IntArray(4) { -1 }   // the tray's edges in the window, last time it was placed
    private var resizingWindow = false

    private fun fitGamesWindowToTray(): Boolean {
        if (!gamesFrameless || currentTool != Tool.GAMES) return false
        val window = shownWindow ?: return false
        if (window !== toolWindows[Tool.GAMES]) return false
        val params = shownParams ?: return false
        val body = window.getChildAt(1) as? ViewGroup ?: return false
        val (l, t, r, b) = gamesTray
        if (l !in 0 until r || b <= t) return false
        // already a snug fit (a few pixels either way is just rounding)
        val slack = dp(4)
        if (l < slack && t < slack && body.width - r < slack && body.height - b < slack) return false
        // made smaller or moved for now to stay clear of the status or navigation bar: left as it is, so it goes back
        // to the size and place you gave it when the bars hide (it's shrunk to the tray then)
        if (params.x != shownWantX || params.y != shownWantY || params.width != shownWantW || params.height != shownWantH)
            return false
        params.x += l
        params.y += t
        params.width = r - l
        params.height = b - t
        windowX = params.x
        windowY = params.y
        rememberWanted(params)
        windowManager.updateViewLayout(window, params)
        return true
    }

    // A tool asks for its window to shrink to just what's in it (no title bar) in a corner (COMPACT_TOP_LEFT or
    // COMPACT_BOTTOM_LEFT), so the game can be seen while it shows its answer; or (COMPACT_OFF) to go back to
    // where and how big it was
    private var compactSaved: IntArray? = null   // x, y, width, height, and the wanted x, y, width, height before shrinking
    private var compactPlace = COMPACT_OFF       // which corner it's shrunk into (to put it back there when the phone turns)

    // A shrunk window's saved place and size (compactSaved) become `fresh`: where it goes when it's done shrinking
    private fun backToSize(saved: IntArray, fresh: WindowManager.LayoutParams) {
        saved[0] = fresh.x; saved[1] = fresh.y; saved[2] = fresh.width; saved[3] = fresh.height
        saved[4] = fresh.x; saved[5] = fresh.y; saved[6] = fresh.width; saved[7] = fresh.height
    }

    private fun setWindowCompact(place: Int) {
        val on = place != COMPACT_OFF
        val params = shownParams ?: return
        val window = shownWindow ?: return
        val bar = window.getChildAt(0)
        val corner = (window.getChildAt(1) as? ViewGroup)?.let { if (it.childCount > 1) it.getChildAt(1) else null }
        if (!on) {
            val saved = compactSaved ?: return
            compactSaved = null
            compactPlace = COMPACT_OFF
            bar.visibility = View.VISIBLE
            corner?.visibility = View.VISIBLE
            params.x = saved[0]; params.y = saved[1]; params.width = saved[2]; params.height = saved[3]
            shownWantX = saved[4]; shownWantY = saved[5]; shownWantW = saved[6]; shownWantH = saved[7]
            placeClearOfBars(params)   // (the bars may have come or gone meanwhile)
            windowManager.updateViewLayout(window, params)
            return
        }
        if (compactSaved == null) compactSaved = intArrayOf(params.x, params.y, params.width, params.height,
            shownWantX, shownWantY, shownWantW, shownWantH)
        compactPlace = place
        bar.visibility = View.GONE
        corner?.visibility = View.GONE
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT
        params.height = ViewGroup.LayoutParams.WRAP_CONTENT
        params.x = bars.left
        params.y = bars.top
        shownWantX = 0
        shownWantY = 0
        shownWantW = ViewGroup.LayoutParams.WRAP_CONTENT
        shownWantH = ViewGroup.LayoutParams.WRAP_CONTENT
        windowManager.updateViewLayout(window, params)
        if (place == COMPACT_BOTTOM_LEFT) {
            // down to the bottom once it knows how tall it is (still measured from the top, so dragging works)
            window.post {
                if (shownWindow !== window || compactSaved == null) return@post
                params.y = maxOf(bars.top, bars.clearBottom(screenHeight()) - window.height)
                shownWantY = params.y
                windowManager.updateViewLayout(window, params)
            }
        }
    }

    // Web pages (Wiki, GE Prices, star tracker...) keep running their scripts and live updates
    // even when their window is hidden. Only the page in the window you're looking at runs; the rest are paused.
    private fun setWebPagesRunning(running: Boolean) {
        val window = shownWindow
        val visible = if (running && window != null) webViews.filter { isInside(it, window) } else emptyList()
        webViews.forEach { if (it in visible) it.onResume() else it.onPause() }
        // these two affect every page at once
        if (visible.isNotEmpty()) webViews.first().resumeTimers() else webViews.firstOrNull()?.pauseTimers()
    }

    private fun isInside(v: View, window: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === window) return true; p = p.parent }
        return false
    }

    // Where the bubble was left, remembered separately for landscape and portrait. Saved as how far
    // across and down the screen it is, so it lands in the same spot even if the screen size changes.
    private val bubblePlace by lazy { getSharedPreferences("bubble_place", MODE_PRIVATE) }
    private fun placeKey() = if (screenWidth() > screenHeight()) "land" else "port"

    private fun saveBubblePlace() {
        val size = bubbleSize()
        val across = bubbleParams.x.toFloat() / maxOf(1, screenWidth() - size)
        val down = bubbleParams.y.toFloat() / maxOf(1, screenHeight() - size)
        bubblePlace.edit { putFloat("x_" + placeKey(), across).putFloat("y_" + placeKey(), down) }
    }

    private fun restoreBubblePlace() {
        val key = placeKey()
        // not moved in this orientation yet: use where it was in the other one
        val use = if (bubblePlace.contains("x_$key")) key else if (key == "land") "port" else "land"
        if (!bubblePlace.contains("x_$use")) return   // never moved: keep the starting spot
        val size = bubbleSize()
        bubbleParams.x = (bubblePlace.getFloat("x_$use", 0f) * (screenWidth() - size)).toInt()
        bubbleParams.y = (bubblePlace.getFloat("y_$use", 0f) * (screenHeight() - size)).toInt()
        clampBubble()
    }

    private fun clampBubble() {
        // (never under the status bar or the navigation bar while they're showing, where it couldn't be touched)
        val (sw, sh) = fullScreenSize(windowManager)   // (both at once: asking for each works it all out twice)
        val spot = ScreenBars.keepBubble(bubbleParams.x, bubbleParams.y, bubbleSize(), sw, sh, bars)
        bubbleParams.x = spot.x
        bubbleParams.y = spot.y
    }

    // ---------------- Keeping clear of the status bar and the navigation bar ----------------

    // Android draws the status bar (along the top) and the navigation bar (the home and back buttons, or the gesture
    // strip: along the bottom, or down one side in landscape) on top of everything, so anything under them can't be
    // touched. While the game is open the phone hides them and you can use the whole screen; elsewhere (the app's
    // own screen, the home screen) they show, and windows and the bubble are kept clear of them.
    private var bars = Bars()        // how far each bar reaches in right now (all 0 = hidden; the maths: ScreenBars)
    private var shownWantX = 0       // where you put the open window; it goes back there when the bars hide
    private var shownWantY = 0
    private var shownWantW = 0       // and how big you made it (it may be smaller for a while to fit between the bars)
    private var shownWantH = 0

    // You moved or resized the open window (or it opened): this is where and how big you want it
    private fun rememberWanted(params: WindowManager.LayoutParams) {
        shownWantX = params.x
        shownWantY = params.y
        shownWantW = params.width
        shownWantH = params.height
    }

    // Overlay windows aren't told when the bars come and go, so two invisible strips watch for them. Android keeps
    // them clear of the bars like a normal window. One is a pixel wide, from the top of the screen to the bottom:
    // when the status bar appears its top gets pushed down, and when the navigation bar appears along the bottom
    // its bottom gets pushed up. The other is a pixel tall, across the top of the screen: a navigation bar down
    // one side pushes in that end. Taps go straight through them.
    private val barProbes = mutableListOf<View>()

    private fun addBarProbes() {
        addBarProbe(across = false)
        addBarProbe(across = true)
    }

    private fun addBarProbe(across: Boolean) {
        val probe = View(this)
        val p = WindowManager.LayoutParams(
            if (across) ViewGroup.LayoutParams.MATCH_PARENT else 1,
            if (across) 1 else ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            alpha = 0f   // fully see-through, so Android lets taps through to the game
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // only the bars push it (not the camera cutout)
                fitInsetsTypes = if (across) android.view.WindowInsets.Type.navigationBars()
                                 else android.view.WindowInsets.Type.statusBars() or android.view.WindowInsets.Type.navigationBars()
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        // the strip changes size whenever a bar appears or hides. Read just after, not in the middle of the layout:
        // when the phone turns, the strip can be laid out before the screen size has caught up.
        probe.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            v.removeCallbacks(checkBars)
            v.post(checkBars)
        }
        windowManager.addView(probe, p)
        barProbes.add(probe)
    }

    // Works out from both strips how far each bar reaches in (also checked again after the phone turns)
    private val checkBars = Runnable {
        if (destroyed || barProbes.size < 2) return@Runnable
        val down = barProbes[0]
        val across = barProbes[1]
        if (down.height == 0 || across.width == 0) return@Runnable   // not laid out yet
        val loc = IntArray(2)
        down.getLocationOnScreen(loc)
        val downTop = loc[1]
        across.getLocationOnScreen(loc)
        // (null: read while the phone turns, against the old screen size: the layout that follows, or the check after
        // the turn, reads them again)
        val (sw, sh) = fullScreenSize(windowManager)
        val now = ScreenBars.fromStrips(downTop, down.height, loc[0], across.width, sw, sh)
            ?: return@Runnable
        onBarsChanged(now)
    }

    // How far the bars reach in, read from the strips: if a bar appeared or hid, everything moves
    private fun onBarsChanged(now: Bars) {
        if (now == bars) return
        bars = now
        // move things just after this, not while Android is in the middle of laying out windows
        bubble.post {
            if (destroyed) return@post
            // the bubble: clear of the bars while they show, back where you left it when they hide
            bubbleGoHome()
            windowManager.updateViewLayout(bubble, bubbleParams)
            val params = shownParams ?: return@post
            // a shrunk window (a ToA answer) goes back into its corner, against the bars as they are now (after the
            // phone turns, the new bars are only read a moment after it was put there)
            if (compactSaved != null) { setWindowCompact(compactPlace); return@post }
            placeClearOfBars(params)
            shownWindow?.let { windowManager.updateViewLayout(it, params) }
            stepBubbleAside(params)   // and off the window again, if it's now on it
        }
    }

    // Puts the open window where you left it (shownWantX/Y/W/H), but clear of the bars that are showing: moved out
    // from under them, and if it's then too big to fit, made smaller for now. It goes back to its own place and
    // size when they hide again.
    private fun placeClearOfBars(params: WindowManager.LayoutParams) {
        // (a window as big as what's in it, like a shrunk one, is kept clear once its size is known)
        val (sw, sh) = fullScreenSize(windowManager)
        val b = ScreenBars.place(Box(shownWantX, shownWantY, shownWantW, shownWantH), shownWindow?.width ?: 0,
            shownWindow?.height ?: 0, sw, sh, bars)
        params.x = b.x
        params.y = b.y
        if (shownWantW > 0) params.width = b.w
        if (shownWantH > 0) params.height = b.h
    }

    private fun buildToolWindow(tool: Tool): LinearLayout {
        val parts = partsFor(tool)
        val content = parts.buildView()
        return buildFrame(content, parts.onBack, tool.label).also { backButtons[tool] = it.findViewWithTag(BACK_TAG) }
    }

    // ◀ shows only where it would do something: on a tool's inner pages (a quest, a rumour, a note, search results, a
    // game) or after following a link in a web page, not on the first page, where it did nothing. Checked when the
    // window opens and after every tap in it, page loaded or ◀ (pages change in answer to those).
    private val backButtons = HashMap<Tool, View?>()

    private fun updateBackButton() {
        val parts = toolParts[currentTool] ?: return
        val button = backButtons[currentTool] ?: return
        if (parts.onBack == null) return
        button.visibility = if (parts.canGoBack()) View.VISIBLE else View.INVISIBLE   // (keeps its space: the title stays put)
    }

    private val checkBackButton = Runnable { if (!destroyed) updateBackButton() }

    // After a tap or a page load: now, and again a moment later (some pages change just after the tap)
    private fun backButtonSoon() {
        bubble.removeCallbacks(checkBackButton)
        bubble.post(checkBackButton)
        bubble.postDelayed(checkBackButton, 400)
    }

    // Every tool, and how the bubble works with it. This is the one place a new tool gets set up.
    private fun makeParts(tool: Tool): ToolParts = when (tool) {
        Tool.WIKI -> ToolParts(
            buildView = {
                buildWebView(pendingWikiUrl ?: WIKI_URL, PAGE_ZOOM_PERCENT).also { wikiWeb = it; pendingWikiUrl = null }
            },
            onBack = { wikiWeb?.let { wikiBack(it) } },
            canGoBack = { wikiReturnTo != null || wikiWeb?.canGoBack() == true }
        )
        Tool.XP_CALCULATOR -> webPage(XP_CALCULATOR_URL, XP_CALCULATOR_ZOOM_PERCENT)
        Tool.SHOOTING_STARS -> webPage(SHOOTING_STARS_URL, SHOOTING_STARS_ZOOM_PERCENT)
        Tool.TEARS_OF_GUTHIX -> webPage(TEARS_OF_GUTHIX_URL, TEARS_OF_GUTHIX_ZOOM_PERCENT)
        Tool.PRICES -> webPage(PRICES_URL, PRICES_ZOOM_PERCENT)
        Tool.DPS_CALCULATOR -> DpsTool(toolContext, capture, ::setOverlaysVisible).let { t ->
            var web: WebView? = null
            ToolParts(
                buildView = { t.buildView(buildWebView(DPS_CALCULATOR_URL, DPS_ZOOM_PERCENT).also { web = it }) },
                onBack = { web?.let { if (it.canGoBack()) it.goBack() } },
                canGoBack = { web?.canGoBack() == true }
            )
        }
        Tool.PUZZLE_BOX -> PuzzleBoxTool(toolContext, windowManager, capture, ::setOverlaysVisible) { hideToolWindow() }.let { t ->
            ToolParts(t::buildView, onBack = null, onClosed = t::onWindowClosed, onRotated = t::onRotated, onDestroy = t::destroy,
                isBusy = t::isGuiding)
        }
        // (closing the Light Box window keeps it solving: the instructions stay on the light box; tap the bubble to
        // bring the window back, and Stop to end it)
        Tool.LIGHT_BOX -> LightBoxTool(toolContext, windowManager, capture, ::setOverlaysVisible) { hideToolWindow() }.let { t ->
            ToolParts(t::buildView, onBack = null, onRotated = t::onRotated, onDestroy = t::destroy, isBusy = t::isGuiding)
        }
        Tool.INVENTORY_SETUPS -> GearLoadoutsTool(toolContext, capture, ::setWindowCompact).let { t ->
            ToolParts(t::buildView, t::goBack, onClosed = t::onWindowClosed, onDestroy = t::destroy, canGoBack = t::canGoBack)
        }
        Tool.ZULRAH -> ZulrahTool(toolContext).let { t -> ToolParts(t::buildView, t::goBack, heightFor = t::heightFor) }
        Tool.TOA_PUZZLES -> ToaPuzzleTool(toolContext, capture, ::setOverlaysVisible, ::setWindowCompact).let { t ->
            ToolParts(t::buildView, t::goBack, onClosed = t::onWindowClosed, onRotated = t::onRotated, onDestroy = t::destroy,
                isBusy = t::isWatching, canGoBack = t::canGoBack)
        }
        Tool.FARMING -> FarmingTool(toolContext).let { t ->
            ToolParts(t::buildView, onBack = null, onClosed = t::onWindowClosed, onDestroy = t::destroy)
        }
        // (closing the window while it watches keeps it watching; tap the bubble to see how it's going, or to stop)
        Tool.IDLE_NOTIFIER -> IdleNotifierTool(toolContext, windowManager, capture, ::setWindowCompact) { hideToolWindow() }.let { t ->
            ToolParts(t::buildView, onBack = null, onClosed = t::onWindowClosed, onRotated = t::onRotated, onDestroy = t::destroy,
                isBusy = t::isWatching)
        }
        // (closing the window keeps it watching; tap the bubble to see what it sees, or to stop)
        Tool.MIXOLOGY -> MixologyTool(toolContext, windowManager, capture) { hideToolWindow() }.let { t ->
            ToolParts(t::buildView, onBack = null, onRotated = t::onRotated, onDestroy = t::destroy, isBusy = t::isWatching)
        }
        Tool.QUESTS -> QuestHelperTool(toolContext, openWiki = { url -> openWikiLink(url, returnTo = Tool.QUESTS) },
            hideWindow = { hideToolWindow() }, openSync = { openSync(Tool.QUESTS) }).let { t ->
            ToolParts(t::buildView, t::goBack, canGoBack = t::canGoBack)
        }
        Tool.SYNC -> ToolParts(SyncTool(toolContext, hideWindow = { hideToolWindow() })::buildView,
            onBack = { syncReturnTo?.let { openTool(it) } }, canGoBack = { syncReturnTo != null })
        Tool.CALCULATOR -> ToolParts(CalculatorTool(toolContext)::buildView, onBack = null)
        Tool.NOTES -> NotesTool(toolContext, onTyping = { if (!it) narrowNotes() }).let { t ->
            notesEditing = t::isEditing
            ToolParts(t::buildView, t::goBack, onClosed = t::onWindowClosed, canGoBack = t::canGoBack)
        }
        Tool.GAMES -> GameRoomTool(toolContext, ::setGamesFrameless, ::pinGamesCorners, { hideToolWindow() }, ::dragGamesWindow).let { t ->
            ToolParts(t::buildView, t::goBack, onDestroy = t::destroy, canGoBack = t::canGoBack)
        }
        Tool.HUNTER -> HunterRumourTool(toolContext, openSync = { openSync(Tool.HUNTER) }).let { t ->
            ToolParts(t::buildView, t::goBack, canGoBack = t::canGoBack)
        }
        Tool.TELEPORTS -> TeleportFinderTool(toolContext, openSync = { openSync(Tool.TELEPORTS) }).let { t ->
            ToolParts(t::buildView, t::goBack, onDestroy = t::destroy, canGoBack = t::canGoBack)
        }
        Tool.SETTINGS -> ToolParts(SettingsTool(toolContext)::buildView, onBack = null)
    }

    // A tool that's just a web page, with ◀ going back a page
    private fun webPage(url: String, zoomPercent: Int): ToolParts {
        var web: WebView? = null
        return ToolParts(
            buildView = { buildWebView(url, zoomPercent).also { web = it } },
            onBack = { web?.let { if (it.canGoBack()) it.goBack() } },
            canGoBack = { web?.canGoBack() == true }
        )
    }

    // The shared window frame: a slim top bar (back, the tool's name, close), with the tool's content below it
    @SuppressLint("ClickableViewAccessibility")
    private fun buildFrame(content: View, onBack: (() -> Unit)?, title: String): LinearLayout {
        val ui = Ui(toolContext)
        val backButton = ui.iconButton("back", "Back") { onBack?.invoke(); backButtonSoon() }
        backButton.tag = BACK_TAG
        if (onBack == null) backButton.visibility = View.INVISIBLE // keep its space, hide it
        val closeButton = ui.iconButton("close", "Close") { hideToolWindow() }

        // The tool's name: drag it to move the window
        val dragHandle = ui.text(title, 14f, Palette.TEXT, 600).apply {
            gravity = Gravity.CENTER
            maxLines = 1
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
            // a long name in a narrow window gets smaller instead of being cut off
            setAutoSizeTextTypeUniformWithConfiguration(dp(10), textSize.toInt().coerceAtLeast(dp(11)), 1, android.util.TypedValue.COMPLEX_UNIT_PX)
            // and if even its smallest size won't fit, just the first word ("Zulrah" for a very thin Zulrah Helper)
            val short = title.substringBefore(' ')
            addOnLayoutChangeListener { v, l, _, r, _, _, _, _, _ ->
                val tv = v as TextView
                val room = r - l - tv.paddingLeft - tv.paddingRight
                if (room <= 0) return@addOnLayoutChangeListener
                val fits = android.graphics.Paint(tv.paint).apply { textSize = dp(10).toFloat() }.measureText(title) <= room
                val wanted = if (fits) title else short
                if (tv.text.toString() != wanted) tv.post { tv.text = wanted }
            }
        }

        var downRawX = 0f
        var downRawY = 0f
        var startWindowX = 0
        var startWindowY = 0
        val canResize = isResizable(currentTool)
        dragHandle.setOnTouchListener { _, event ->
            val params = shownParams ?: return@setOnTouchListener false
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startWindowX = params.x
                    startWindowY = params.y
                }
                MotionEvent.ACTION_MOVE -> moveWindowTo(params, startWindowX + (event.rawX - downRawX).toInt(),
                    startWindowY + (event.rawY - downRawY).toInt(), moveY = true)
                // the Zulrah Helper settles against whichever side of the screen it was let go nearer to; the Puzzle
                // Box and Light Box Solvers remember their own spot
                MotionEvent.ACTION_UP -> when {
                    currentTool == Tool.ZULRAH -> dockZulrah(ScreenBars.nearerRight(params.x, params.width, screenWidth()))
                    isSolver(currentTool) -> saveSolverPlace(params)
                }
            }
            true
        }

        val topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Palette.RAISED)
            // the buttons keep their full width to be easy to hit, but are only as tall as the bar
            addView(backButton, LinearLayout.LayoutParams(dp(Ui.TAP_SIZE), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(dragHandle)
            addView(closeButton, LinearLayout.LayoutParams(dp(Ui.TAP_SIZE), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        // The content, with a resize corner at the bottom right (for the tools that can be resized)
        val body = FrameLayout(this).apply {
            addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (canResize) addView(resizeCorner(), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.BOTTOM or Gravity.END))
            // the move symbol, only shown in a window without its top bar (see setGamesFrameless)
            if (canResize) addView(moveCorner().apply { visibility = View.GONE }, FrameLayout.LayoutParams(dp(30), dp(30), Gravity.TOP or Gravity.END))
        }

        return object : LinearLayout(this) {
            // Tapping a text box or web page in the window lets it take the keyboard; tapping outside it (the game)
            // hands the keyboard back, so the game's own typing (the bank search...) works with the window open
            override fun dispatchTouchEvent(event: MotionEvent): Boolean {
                when (event.actionMasked) {
                    MotionEvent.ACTION_OUTSIDE -> { setWindowTakesKeyboard(false); return true }
                    MotionEvent.ACTION_DOWN -> if (takesTyping(viewAt(this, event.x, event.y))) setWindowTakesKeyboard(true)
                }
                val handled = super.dispatchTouchEvent(event)
                // a tap may have opened or left an inner page: ◀ shows or hides to match
                if (event.actionMasked == MotionEvent.ACTION_UP) backButtonSoon()
                return handled
            }
        }.apply {
            // a tool puts you in a text box itself (the Notepad's new note): the window takes the keyboard too
            viewTreeObserver.addOnGlobalFocusChangeListener { _, now ->
                if (now is EditText) keyboardBesideTheGame(now)
                if (now != null && shownWindow === this && now.onCheckIsTextEditor()) setWindowTakesKeyboard(true)
                if (now is EditText && shownWindow === this) post { liftForTyping(now) }
            }
            // once the window has the keyboard, show it for the text box you tapped (asked for too early, it's lost).
            // Only right after a tap handed it over: not when the window gets it back later (from the Sync camera,
            // say), which would pop the keyboard up for a box you'd finished with.
            viewTreeObserver.addOnWindowFocusChangeListener { has ->
                if (!has || !keyboardJustTaken) return@addOnWindowFocusChangeListener
                keyboardJustTaken = false
                val box = findFocus()
                if (box != null && box.onCheckIsTextEditor()) inputMethods.showSoftInput(box, 0)
            }
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.WINDOW)
            outlineProvider = object : android.view.ViewOutlineProvider() {   // gently rounded corners
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(WINDOW_CORNER_DP).toFloat())
            }
            clipToOutline = true
            foreground = thinFrame()
            addView(topBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(BAR_HEIGHT_DP)))
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    // ---------------- The keyboard ----------------

    // Android gives the keyboard to one window at a time. A tool window sits above the game, so if it could always
    // take the keyboard, the game's own (the bank search...) couldn't open while it's on screen. So a tool window
    // starts without it (FLAG_NOT_FOCUSABLE), takes it while you type in it, and gives it back when you tap the game.
    private val inputMethods by lazy { getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager }

    // The window was just handed the keyboard by a tap, and hasn't shown it yet
    private var keyboardJustTaken = false

    private fun setWindowTakesKeyboard(on: Boolean) {
        val window = shownWindow ?: return
        val params = shownParams ?: return
        val hasIt = params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE == 0
        // (a note's box tapped again after Done: the window may still have the keyboard, but widens again)
        if (on && currentTool == Tool.NOTES && notesEditing()) widenNotes()
        if (hasIt == on) return
        keyboardJustTaken = on
        if (!on) inputMethods.hideSoftInputFromWindow(window.windowToken, 0)   // the tool's keyboard goes away
        if (!on) { lowerAfterTyping(); narrowNotes() }
        params.flags = if (on) params.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
                       else params.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(window, params)
    }

    // The keyboard comes up from the bottom of the screen, so a text box in a window sitting low would be under it. When
    // you tap a box in the lower part of the screen, the window moves up to the top while you type, and goes back to
    // where it was when you're done (you tap the game, which puts the keyboard away). Dragged somewhere else while you
    // type, it stays where you put it. The place the window remembers (windowY) isn't changed by this.
    // Writing a note sideways, the keyboard leaves only a strip across the top of the screen, so the Notepad window
    // widens across it while you type (more words to a line) and goes back to its own size when you tap Done or the
    // game. Resized by hand while wide, it keeps the size you gave it. The size it remembers isn't changed by this.
    private var notesEditing: () -> Boolean = { false }
    private var notesNarrow: IntArray? = null   // its x and width before it widened
    private var notesWideW = 0

    private fun widenNotes() {
        val window = shownWindow ?: return
        val params = shownParams ?: return
        if (notesNarrow != null || compactSaved != null) return
        val screenW = screenWidth()
        if (screenW <= screenHeight()) return   // (upright, the window is already nearly as wide as the screen)
        val wide = (screenW * NOTES_TYPING_WIDTH).toInt()
        if (params.width >= wide) return
        notesNarrow = intArrayOf(params.x, params.width)
        notesWideW = wide
        params.width = wide
        params.x = params.x.coerceAtMost(maxOf(0, bars.clearRight(screenW) - wide))
        shownWantX = params.x; shownWantW = wide
        windowManager.updateViewLayout(window, params)
        stepBubbleAside(params)
    }

    private fun narrowNotes() {
        val narrow = notesNarrow ?: return
        notesNarrow = null
        val window = shownWindow ?: return
        val params = shownParams ?: return
        if (currentTool != Tool.NOTES || params.width != notesWideW) return   // (resized by hand: left as it is)
        params.x = narrow[0]; params.width = narrow[1]
        shownWantX = params.x; shownWantW = params.width
        windowManager.updateViewLayout(window, params)
    }

    private var liftedFrom: Int? = null   // where the window was before it moved up for the keyboard
    private var liftedTo = 0

    private fun liftForTyping(box: View) {
        val window = shownWindow ?: return
        val params = shownParams ?: return
        if (liftedFrom != null || !box.isAttachedToWindow) return
        val at = IntArray(2)
        box.getLocationOnScreen(at)
        if (at[1] + box.height <= screenHeight() * KEYBOARD_FREE_PART) return   // (already above the keyboard)
        val top = bars.top
        if (params.y <= top) return
        liftedFrom = params.y
        liftedTo = top
        params.y = top
        shownWantY = top
        windowManager.updateViewLayout(window, params)
    }

    private fun lowerAfterTyping() {
        val from = liftedFrom ?: return
        liftedFrom = null
        val window = shownWindow ?: return
        val params = shownParams ?: return
        if (params.y != liftedTo) return   // moved by hand while typing: left there
        params.y = from
        shownWantY = from
        windowManager.updateViewLayout(window, params)
    }

    // With the phone sideways, the keyboard normally covers the whole screen with its own big text box, so you can't
    // tap the game to put it away. Every text box in a tool window asks for just the keys instead, typing straight
    // into the box in the window (done when it's tapped, before the keyboard opens, so every tool's boxes get it).
    // Web pages already ask for this themselves.
    private fun keyboardBesideTheGame(box: EditText) {
        val wanted = box.imeOptions or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        if (box.imeOptions == wanted) return
        box.imeOptions = wanted
        inputMethods.restartInput(box)   // (in case the keyboard already read the old setting)
    }

    // Things you type into: text boxes, and web pages (their search boxes)
    private fun takesTyping(v: View) = v is EditText || v is WebView || v.onCheckIsTextEditor()

    // The innermost visible view under a point (x, y measured from v's top left)
    private fun viewAt(v: View, x: Float, y: Float): View {
        if (v !is ViewGroup) return v
        for (i in v.childCount - 1 downTo 0) {
            val c = v.getChildAt(i)
            val cx = x + v.scrollX - c.left
            val cy = y + v.scrollY - c.top
            if (c.visibility == View.VISIBLE && cx >= 0 && cy >= 0 && cx < c.width && cy < c.height) return viewAt(c, cx, cy)
        }
        return v
    }

    // ---------------- Resizing windows ----------------

    // The Light Box and Puzzle Box Solvers are already small
    private fun isResizable(tool: Tool) = !isSolver(tool)

    // The Puzzle Box and Light Box Solvers: small, a fixed size, starting in the top-right corner (clear of the box,
    // which opens in the middle of the game). They can be dragged by their top bar, and each remembers its own spot
    // for each way round (not the spot the other tools share, so moving one doesn't move the others).
    private fun isSolver(tool: Tool) = tool == Tool.LIGHT_BOX || tool == Tool.PUZZLE_BOX
    private val solverPlaces by lazy { getSharedPreferences("solver_place", MODE_PRIVATE) }
    private fun solverKey(tool: Tool) = tool.name + if (screenWidth() > screenHeight()) "_land" else "_port"

    private fun saveSolverPlace(params: WindowManager.LayoutParams) {
        val key = solverKey(currentTool)
        solverPlaces.edit { putInt(key + "_x", params.x).putInt(key + "_y", params.y) }
    }

    // Each tool remembers its own size, separately for landscape and portrait
    private fun sizeKey(tool: Tool): String {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // (the Zulrah Helper's sizes from before it became a thin strip are left behind)
        return tool.name + (if (tool == Tool.ZULRAH) "_strip" else "") + if (landscape) "_land" else "_port"
    }

    // The window's height for a tool that fits its contents (the top bar and what's under it), or null
    private fun fittedHeight(windowW: Int): Int? {
        val fit = toolParts[currentTool]?.heightFor ?: return null
        val inside = fit(windowW)
        return if (inside > 0) dp(BAR_HEIGHT_DP) + inside else null
    }

    private val minWindowWidth get() = dp(if (currentTool == Tool.ZULRAH) ZULRAH_MIN_WIDTH_DP else 150)
    private val minWindowHeight get() = dp(140)

    // Moves the open window while it's dragged (by its top bar or its move symbol), up and down too if moveY. Not under
    // the status bar or the navigation bar while they're showing (you couldn't grab it there).
    private fun moveWindowTo(params: WindowManager.LayoutParams, x: Int, y: Int, moveY: Boolean) {
        val (sw, sh) = fullScreenSize(windowManager)   // (once per finger movement, both at once)
        // (a window as tall as its contents, like the two solvers, by its height on screen)
        val h = if (params.height < 0) shownWindow?.height ?: 0 else params.height
        val spot = ScreenBars.drag(x, y, params.width, h, sw, sh, bars, moveY)
        params.x = spot.x
        // (others share one position; the solvers keep their own, saveSolverPlace)
        if (!isSolver(currentTool) && currentTool != Tool.ZULRAH) windowX = params.x
        shownWantX = params.x
        if (moveY) {
            params.y = spot.y
            if (!isSolver(currentTool) && currentTool != Tool.ZULRAH) windowY = params.y
            shownWantY = params.y
        }
        shownWindow?.let { windowManager.updateViewLayout(it, params) }
    }

    // The move symbol (for windows without a top bar): drag it to move the window anywhere
    @SuppressLint("ClickableViewAccessibility")
    private fun moveCorner(): View = object : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(2).toFloat()
            strokeCap = android.graphics.Paint.Cap.ROUND
            style = android.graphics.Paint.Style.STROKE
        }
        private val back = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = "#B33E2C12".toColorInt() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (tag == CARVED) return   // (carved into the garden's frame instead)
            val w = width.toFloat()
            val c = w / 2
            // a dark circle with a four-way arrow, like the top bar's move symbol
            canvas.drawCircle(c, c, c * 0.95f, back)
            paint.color = "#F2E3C0".toColorInt()
            val r = w * 0.3f
            val head = w * 0.1f
            canvas.drawLine(c - r, c, c + r, c, paint)
            canvas.drawLine(c, c - r, c, c + r, paint)
            for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                val tx = c + dx * r; val ty = c + dy * r
                canvas.drawLine(tx, ty, tx - dx * head + dy * head, ty - dy * head + dx * head, paint)
                canvas.drawLine(tx, ty, tx - dx * head - dy * head, ty - dy * head - dx * head, paint)
            }
        }
    }.apply {
        var downRawX = 0f; var downRawY = 0f
        var startX = 0; var startY = 0
        setOnTouchListener { _, e ->
            val params = shownParams ?: return@setOnTouchListener false
            // carved into the garden's frame: only its top-right triangle (the sand beside it is still raked)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && tag == CARVED && (width - e.x) + e.y > width * 1.1f)
                return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = e.rawX; downRawY = e.rawY
                    startX = params.x; startY = params.y
                }
                MotionEvent.ACTION_MOVE -> moveWindowTo(params, startX + (e.rawX - downRawX).toInt(),
                    startY + (e.rawY - downRawY).toInt(), moveY = true)
            }
            true
        }
    }

    // Drag the corner to resize. It's only three thin see-through lines, so what's under it shows; a tap on it isn't a
    // resize, so it goes through to whatever is under it (the Calculator's + key, a list's last row...).
    @SuppressLint("ClickableViewAccessibility")
    private fun resizeCorner(): View = object : View(this) {
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            strokeWidth = dp(1.5f)
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            if (tag == CARVED) return   // (carved into the garden's frame instead)
            val w = width.toFloat(); val h = height.toFloat()
            // three short diagonal lines in the corner, at half strength (full while it's being dragged)
            paint.color = if (isPressed) Palette.TEXT else (Palette.MUTED and 0x00FFFFFF) or (0x80 shl 24)
            val edge = dp(4f)
            for (i in 1..3) {
                val d = w * 0.2f * i
                canvas.drawLine(w - d, h - edge, w - edge, h - d, paint)
            }
        }
    }.apply {
        var downX = 0f; var downY = 0f
        var startW = 0; var startH = 0
        var startRight = 0   // (a window against the right side grows to the left, keeping its right edge there)
        var moved = false
        var downAt = 0L
        var tapX = 0f; var tapY = 0f
        var lastTap = 0L   // (the Zen Garden's carved corner still goes back to the normal size on a double-tap)
        setOnTouchListener { _, e ->
            val params = shownParams ?: return@setOnTouchListener false
            // carved into the garden's frame: only its bottom-right triangle (the sand beside it is still raked)
            if (e.actionMasked == MotionEvent.ACTION_DOWN && tag == CARVED && (width - e.x) + (height - e.y) > width * 1.1f)
                return@setOnTouchListener false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    tapX = e.x; tapY = e.y
                    downAt = e.downTime
                    startW = params.width; startH = params.height
                    startRight = params.x + params.width
                    moved = false
                    resizingWindow = true
                    isPressed = true; invalidate()
                }
                MotionEvent.ACTION_CANCEL -> { resizingWindow = false; isPressed = false; invalidate() }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (e.rawX - downX).toInt()
                    val dy = (e.rawY - downY).toInt()
                    if (abs(dx) > dp(3) || abs(dy) > dp(3)) moved = true
                    if (moved) {
                        val (sw, sh) = fullScreenSize(windowManager)
                        val onRight = currentTool == Tool.ZULRAH && zulrahOnRight
                        val size = ScreenBars.resize(if (onRight) startW - dx else startW + dx, startH + dy,
                            if (onRight) bars.left else params.x, params.y, minWindowWidth, minWindowHeight, sw, sh, bars)
                        params.width = size.w
                        params.height = size.h
                        // a window that fits its contents (Zulrah): the drag sets the width, and the height follows
                        fittedHeight(size.w)?.let { params.height = it.coerceAtMost(maxOf(minWindowHeight, bars.clearBottom(sh) - params.y)) }
                        if (onRight) { params.x = startRight - size.w; shownWantX = params.x }
                        shownWantW = params.width
                        shownWantH = params.height
                        shownWindow?.let { windowManager.updateViewLayout(it, params) }
                    }
                }
                MotionEvent.ACTION_UP -> {
                    resizingWindow = false
                    isPressed = false; invalidate()
                    // the Zen Garden: once the tray has settled into the new size, the window shrinks to it
                    if (gamesFrameless) postDelayed({ fitGamesWindowToTray() }, 150)
                    if (moved) {
                        sizes.edit {
                            putInt(sizeKey(currentTool) + "_w", params.width)
                            putInt(sizeKey(currentTool) + "_h", params.height)
                        }
                        refitWebPages()
                        stepBubbleAside(params)   // (grown under the bubble: it moves beside the top bar again)
                    } else if (tag != CARVED) {
                        // a tap, not a drag: it goes to whatever is under the corner, as if the corner weren't there
                        tapThrough(this, tapX, tapY, downAt, e.eventTime)
                    } else {
                        val now = System.currentTimeMillis()
                        if (now - lastTap < 350) {
                            // the garden's corner double-tapped: back to the normal size
                            sizes.edit { remove(sizeKey(currentTool) + "_w").remove(sizeKey(currentTool) + "_h") }
                            windowY = null   // and back to the middle of the screen
                            val fresh = makeWindowParams()
                            rememberWanted(fresh)
                            placeClearOfBars(params)
                            shownWindow?.let { windowManager.updateViewLayout(it, params) }
                            lastTap = 0
                        } else lastTap = now
                    }
                }
            }
            true
        }
    }

    // Passes a tap on the resize corner (at x, y on it) to the tool's contents under it: a press and a let-go there
    private fun tapThrough(corner: View, x: Float, y: Float, downAt: Long, upAt: Long) {
        val parent = corner.parent as? ViewGroup ?: return
        val under = parent.getChildAt(0) ?: return
        if (under === corner) return
        val ux = x + corner.left - under.left
        val uy = y + corner.top - under.top
        for (action in intArrayOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(downAt, if (action == MotionEvent.ACTION_DOWN) downAt else upAt, action, ux, uy, 0)
            under.dispatchTouchEvent(event)
            event.recycle()
        }
    }

    // Web pages are shrunk to fit the window's width, so fit them again after a resize
    private fun refitWebPages() {
        val window = shownWindow ?: return
        for (web in webViews) {
            if (!isInside(web, window)) continue
            val fit = if (web.url?.contains("prices.runescape.wiki") == true) FIT_BOTH_SIDES_JS else FIT_WIDTH_JS
            web.postDelayed({ web.evaluateJavascript(fit, null) }, 150)
        }
    }

    // Size and position of tool windows, kept inside the screen edges
    private fun makeWindowParams(): WindowManager.LayoutParams {
        val metrics = resources.displayMetrics
        val screenW = screenWidth()
        val screenH = screenHeight()
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Light Box Solver: a small box in the top-right corner, only as tall as it needs,
        // so it stays clear of the light box (which opens in the middle of the game)
        if (currentTool == Tool.LIGHT_BOX || currentTool == Tool.PUZZLE_BOX) {
            val inches = if (currentTool == Tool.LIGHT_BOX) LIGHT_BOX_WIDTH_INCHES else PUZZLE_BOX_WIDTH_INCHES
            val w = (inches * metrics.xdpi).toInt().coerceAtMost(screenW)
            val margin = dp(8)
            return WindowManager.LayoutParams(
                w,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WINDOW_FLAGS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.START or Gravity.TOP
                // where you last dragged it this way round, or the top-right corner
                val key = solverKey(currentTool)
                x = solverPlaces.getInt(key + "_x", screenW - w - margin).coerceIn(0, maxOf(0, screenW - w))
                y = solverPlaces.getInt(key + "_y", margin).coerceIn(0, maxOf(0, screenH - dp(80)))
                allowScreenEdges(this)
            }
        }

        val width = when {
            currentTool == Tool.ZULRAH -> dp(ZULRAH_WIDTH_DP)
            !landscape -> (screenW * 0.9f).toInt()
            currentTool == Tool.DPS_CALCULATOR -> (screenW * DPS_WIDTH_FRACTION).toInt()
            else -> (AppSettings.windowWidthInches(this) * metrics.xdpi).toInt()
        }.coerceAtMost(screenW)

        var w = width
        // The Notepad starts at the top, only as tall as the space above the keyboard (about the top half),
        // so the keyboard doesn't cover what you're typing
        val notesTop = NotesTool.topOffset(this)
        var h = if (currentTool == Tool.NOTES) ((screenH * 0.48f).toInt() - notesTop).coerceAtLeast(minWindowHeight)
                else if (currentTool == Tool.ZULRAH) screenH   // (its height then fits its contents: fittedHeight)
                else (screenH * 0.9f).toInt()
        // a size you've dragged this tool to
        if (isResizable(currentTool)) {
            val key = sizeKey(currentTool)
            if (sizes.contains(key + "_w")) {
                w = sizes.getInt(key + "_w", w).coerceIn(minOf(minWindowWidth, screenW), screenW)
                h = sizes.getInt(key + "_h", h).coerceIn(minOf(minWindowHeight, screenH), screenH)
            }
        }
        windowX = windowX.coerceIn(0, maxOf(0, screenW - w))
        fittedHeight(w)?.let { h = it.coerceAtMost(screenH) }

        return WindowManager.LayoutParams(
            w,
            h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WINDOW_FLAGS, // touches outside still reach the game, and the game keeps the keyboard
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.START or Gravity.TOP
            x = if (currentTool == Tool.ZULRAH) zulrahDockX(w) else windowX
            // windows sit where you last dragged them. To start with they're centred, except the Notepad,
            // which starts at the top, just below the status bar (it catches touches at the very top).
            val start = if (currentTool == Tool.NOTES) notesTop else (screenH - h) / 2
            y = (if (currentTool == Tool.ZULRAH) zulrahPrefs.getInt("dock_y", 0) else windowY ?: start).coerceIn(0, maxOf(0, screenH - h))
            allowScreenEdges(this)
        }
    }

    // ---------------- The Zulrah Helper: a thin strip against the left or right side of the screen ----------------

    private val zulrahPrefs by lazy { getSharedPreferences("zulrah", MODE_PRIVATE) }
    private val zulrahOnRight get() = zulrahPrefs.getBoolean("dock_right", false)

    private fun zulrahDockX(w: Int): Int {
        var left = 0
        var right = 0
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val hole = windowManager.currentWindowMetrics.windowInsets.getInsets(android.view.WindowInsets.Type.displayCutout())
            left = hole.left
            right = hole.right
        }
        return ScreenBars.dockX(w, zulrahOnRight, screenWidth(), bars, left, right)
    }

    private fun dockZulrah(onRight: Boolean) {
        val params = shownParams ?: return
        val window = shownWindow ?: return
        zulrahPrefs.edit { putBoolean("dock_right", onRight).putInt("dock_y", params.y) }
        params.x = zulrahDockX(params.width)
        shownWantX = params.x
        shownWantY = params.y
        placeResizeCorner(window)
        windowManager.updateViewLayout(window, params)
        stepBubbleAside(params)   // the strip moved to where the bubble is: it waits beside it instead
    }

    // Against the right side, the resize corner goes to the window's bottom left, the corner that's free to move
    private fun placeResizeCorner(window: LinearLayout) {
        val corner = (window.getChildAt(1) as? ViewGroup)?.getChildAt(1) ?: return
        (corner.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.gravity = Gravity.BOTTOM or if (zulrahOnRight) Gravity.START else Gravity.END
            corner.layoutParams = it
        }
        corner.scaleX = if (zulrahOnRight) -1f else 1f
    }

    // Hide/show everything we draw, so it doesn't end up in screenshots
    private fun setOverlaysVisible(visible: Boolean) {
        val v = if (visible) View.VISIBLE else View.INVISIBLE
        bubble.visibility = v
        shownWindow?.visibility = v
        menu?.visibility = v
    }

    // ---------------- Web tools: OSRS Wiki and XP Calculator ----------------

    @SuppressLint("SetJavaScriptEnabled")
    private fun buildWebView(startUrl: String, textZoomPercent: Int): WebView {
        // A web page shows its dark look when the app around it has a dark theme (websites without a
        // dark look stay as they are)
        val theme = if (Palette.dark) android.R.style.Theme_DeviceDefault_NoActionBar
                    else android.R.style.Theme_DeviceDefault_Light_NoActionBar
        // the wiki's own theme: dark with dark mode on; back to light if dark mode set it dark before (even if dark
        // mode was turned off while the bubble was closed)
        if (Palette.dark) setWikiTheme(dark = true) else if (wikiThemeSetDark()) setWikiTheme(dark = false)
        val web = WebView(android.view.ContextThemeWrapper(this, theme)).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            if (Palette.dark) setBackgroundColor(Palette.WINDOW)   // no white flash while a page loads
            settings.builtInZoomControls = true   // allow pinch-to-zoom
            settings.displayZoomControls = false  // hide the +/- zoom buttons
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    if (url?.contains("prices.runescape.wiki") == true) view.evaluateJavascript(PASSIVE_TOUCH_JS, null)
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    backButtonSoon()   // (a link was followed: ◀ can go back now)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    if (url?.contains("prices.runescape.wiki") == true) view.evaluateJavascript(CHART_SCROLL_FIX_JS, null)
                    view.evaluateJavascript(INLINE_DROPDOWNS_JS, null)
                    if (url?.contains("07.gg") == true) view.evaluateJavascript(STAR_DETAILS_FIX_JS, null)
                    // Shrink the page so its full width fits the window.
                    // Runs twice: once quickly, and again after images and tables have loaded.
                    val fit = if (url?.contains("prices.runescape.wiki") == true) FIT_BOTH_SIDES_JS else FIT_WIDTH_JS
                    view.postDelayed({ view.evaluateJavascript(fit, null) }, 300)
                    view.postDelayed({ view.evaluateJavascript(fit, null) }, 1500)
                }
            }
            loadUrl(startUrl)
        }
        webViews.add(web)
        webZoom[web] = textZoomPercent
        applyTextSize(web)
        return web
    }

    // The page's own text size, made bigger or smaller by the Settings text size
    private fun applyTextSize(web: WebView) {
        val base = webZoom[web] ?: return
        web.settings.textZoom = base * AppSettings.textSize(this) / AppSettings.TEXT_NORMAL
    }

    // ---------------- Settings ----------------

    // A setting was changed (in the Settings window, or on the app's screen)
    private val settingsChanged = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (destroyed) return@OnSharedPreferenceChangeListener
        when (key) {
            AppSettings.DARK_MODE -> applyDarkMode()
            AppSettings.TEXT_SIZE -> {
                webViews.forEach { applyTextSize(it) }
                refitWebPages()
            }
            AppSettings.TOOL_TEXT_SIZE -> {
                // built again once the − and + taps stop, not after every tap (it reloads the web pages too)
                bubble.removeCallbacks(rebuildForTextSize)
                bubble.postDelayed(rebuildForTextSize, TEXT_SIZE_WAIT_MS)
            }
            AppSettings.BUBBLE_SIZE -> {
                bubbleParams.width = bubbleSize()
                bubbleParams.height = bubbleSize()
                bubbleGoHome()
                windowManager.updateViewLayout(bubble, bubbleParams)
                shownParams?.let { if (compactSaved == null) stepBubbleAside(it) }   // (bigger, it may reach the window)
            }
            AppSettings.BUBBLE_FADE -> if (AppSettings.bubbleFades(this)) fadeBubbleLater() else unfadeBubble()
            AppSettings.WINDOW_WIDTH -> resetShownWindow()
            AppSettings.WINDOWS_RESET -> {
                // every window back to its normal size (Settings already forgot the sizes), in the middle of the screen
                windowX = dp(8)
                windowY = null
                solverPlaces.edit { clear() }   // (the two solvers back to the top-right corner too)
                resetShownWindow()
            }
        }
    }

    private val rebuildForTextSize = Runnable {
        if (destroyed) return@Runnable
        // a puzzle guide is on screen with its window closed: building the tools again would stop it, so that waits
        // until a window is next opened with no guide running (see showToolWindow)
        textSizeWaiting = toolParts.values.any { it.isBusy() }
        if (textSizeWaiting) return@Runnable
        updateToolTextSize()
        rebuildToolWindows()
    }
    private var textSizeWaiting = false

    // The open window goes to its normal place and size again (after a Settings change to those)
    private fun resetShownWindow() {
        val params = shownParams ?: return
        val window = shownWindow ?: return
        val fresh = makeWindowParams()
        compactSaved?.let { saved ->
            // a shrunk window stays shrunk, but when it's done it goes back to the new normal size and place
            backToSize(saved, fresh)
            return
        }
        rememberWanted(fresh)
        placeClearOfBars(params)
        windowManager.updateViewLayout(window, params)
        refitWebPages()
    }

    // Dark mode turned on or off: every tool window is built again in the new colours.
    // fromPhone: the phone's own dark mode changed ("Match phone", perhaps on a timer at sunset). Building the tools
    // again would stop whatever the open one is doing (a ToA Watch, a puzzle guide), so that waits until no tool
    // window is open (see showToolWindow).
    private var darkModeWaiting = false

    private fun applyDarkMode(fromPhone: Boolean = false) {
        val dark = AppSettings.isDark(this)
        darkModeWaiting = false
        if (dark == builtDark) { Palette.dark = dark; return }
        if (fromPhone && (shownWindow != null || toolParts.values.any { it.isBusy() })) { darkModeWaiting = true; return }
        Palette.dark = dark
        builtDark = dark
        rebuildToolWindows()   // (the web pages built again set the wiki's theme)
    }

    // The OSRS Wiki has a dark look of its own, but doesn't follow the app's theme: it remembers your choice in
    // a "theme" cookie (the one its own theme menu sets; see meta.weirdgloop.org/w/Themes). This sets it. Only
    // done when dark mode is on or has been turned off, so with dark mode off a theme picked in the wiki's own menu
    // stays. Whether the app last set it dark is remembered, so it's set back to light even if dark mode was turned
    // off while the bubble was closed.
    private val wikiThemePrefs by lazy { getSharedPreferences("wiki_theme", MODE_PRIVATE) }
    private fun wikiThemeSetDark() = wikiThemePrefs.getBoolean("set_dark", false)

    private fun setWikiTheme(dark: Boolean) {
        val cookies = android.webkit.CookieManager.getInstance()
        cookies.setCookie(WIKI_URL, "theme=${if (dark) "dark" else "light"}; path=/; max-age=315360000")
        cookies.flush()
        wikiThemePrefs.edit { putBoolean("set_dark", dark) }
    }

    // Puts every tool away and starts them afresh (each is set up again when next opened, like after starting
    // the bubble). The window that was open opens again, and the Wiki keeps the page it was on.
    private fun rebuildToolWindows() {
        val reopen = if (shownWindow != null) currentTool else null
        val wikiPage = wikiWeb?.url
        hideMenu()
        hideToolWindow()
        toolParts.values.forEach { it.onDestroy() }
        toolParts.clear()
        toolWindows.clear()
        webViews.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        webViews.clear()
        webZoom.clear()
        wikiWeb = null
        pendingWikiUrl = wikiPage
        wikiReturnTo = null
        syncReturnTo = null
        gamesFrameless = false
        reopen?.let { openTool(it) }
    }

    // ---------------- Housekeeping ----------------

    // Resize the window when the phone rotates
    private var lastScreenSize = 0 to 0

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (scaledResources != null) updateToolTextSize()   // (so the tools see the new orientation too)
        // the phone's dark mode, for the "Match phone" setting
        if (AppSettings.darkMode(this) == AppSettings.MATCH_PHONE) applyDarkMode(fromPhone = true)
        // only a rotation (or folding the phone) moves things; dark mode, font or language changes don't
        val size = screenWidth() to screenHeight()
        if (size == lastScreenSize) return
        lastScreenSize = size
        // the bars, read again against the new screen size (in case the strips moved before it had changed)
        barProbes.firstOrNull()?.let { it.removeCallbacks(checkBars); it.post(checkBars) }
        hideMenu()
        clampBubble()
        windowManager.updateViewLayout(bubble, bubbleParams)
        toolParts.values.forEach { it.onRotated() }
        val shrunk = compactSaved
        if (shownWindow != null && shrunk != null) {
            // a shrunk window (a ToA answer) stays shrunk in its corner; when it's done it goes back to its
            // normal size and place for the new way round, not the old one's
            val fresh = makeWindowParams()
            backToSize(shrunk, fresh)
            setWindowCompact(compactPlace)
        } else if (shownWindow != null) {
            val params = makeWindowParams()
            shownWindow?.let { fitToContents(it, params) }
            rememberWanted(params)
            placeClearOfBars(params)
            shownParams = params
            shownWindow?.let { windowManager.updateViewLayout(it, params) }
            // the bubble was waiting beside the window: from its home for this way round, beside the window again
            if (bubbleAside) {
                bubbleGoHome()
                windowManager.updateViewLayout(bubble, bubbleParams)
            }
            stepBubbleAside(params)
        }
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        // close the window first, while the tools can still tidy up after it
        hideMenu()
        hideToolWindow()
        toolParts.values.forEach { it.onDestroy() }
        AppSettings.prefs(this).unregisterOnSharedPreferenceChangeListener(settingsChanged)
        capture.shutdown()
        CalculatorItems.releaseShared()
        windowManager.removeView(bubble)
        barProbes.forEach { windowManager.removeView(it) }
        webViews.forEach { (it.parent as? ViewGroup)?.removeView(it); it.destroy() }
        super.onDestroy()
    }
}

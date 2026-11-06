package com.sinat.osrsbubbletool

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.LinearLayout
import android.widget.TextView
import com.sinat.osrsbubbletool.Palette.BUTTON_BROWN
import com.sinat.osrsbubbletool.Palette.DARK_BROWN
import com.sinat.osrsbubbletool.Palette.PARCHMENT
import org.json.JSONObject

// The DPS Calculator tool: the wiki's calculator, plus "Import my gear", which reads your equipment tab from the screen
// and loads it straight into the calculator. It keeps no item pictures: each item is recognised from a 9 x 8 colour
// summary of its icon (assets/dps/gear_summaries.bin; see GearSummaryReader and GearImport).
class DpsTool(
    private val context: Context,
    private val capture: CaptureManager,
    private val setOverlaysVisible: (Boolean) -> Unit
) {
    companion object {
        const val CAPTURE_DELAY_MS = 500L

        // Puts gear into the calculator's saved session, then reloads the page to show it.
        // __GEAR__ is replaced with {"head": 1234, "cape": null, ...}, and __DONE__ with what to say once it's saved
        // (said through an alert, which the status line shows, so it only appears if loading worked)
        // How the calculator saves (src/state/RootStore.ts in weirdgloop/osrs-dps-calc, November 2026): under
        // 'dps-calc-state' in its localforage store, only once something on the page has changed, so a page nobody has
        // touched has nothing saved: then a session with just this gear is made (format version 13, which the calculator
        // brings up to date itself when it loads). The selected loadout is its uuid (a number, its place in the list,
        // before version 13).
        private const val LOAD_GEAR_JS = """
            (async function() { try {
                var gear = __GEAR__;
                var db = await new Promise(function(res, rej) {
                    var r = indexedDB.open('localforage');
                    r.onsuccess = function() { res(r.result); };
                    r.onerror = function() { rej(r.error); };
                });
                function store() {
                    return db.transaction('keyvaluepairs', 'readwrite').objectStore('keyvaluepairs');
                }
                var state = await new Promise(function(res, rej) {
                    var q = store().get('dps-calc-state');
                    q.onsuccess = function() { res(q.result); };
                    q.onerror = function() { rej(q.error); };
                });
                if (!state || !state.loadouts || !state.loadouts.length) {
                    state = { serializationVersion: 13, loadouts: [{}] };
                }
                var picked = state.selectedLoadout;
                var loadout = typeof picked === 'string'
                    ? state.loadouts.find(function(l) { return l && l.uuid === picked; })
                    : state.loadouts[picked || 0];
                if (!loadout) {   // (shows the one filled in)
                    loadout = state.loadouts[0];
                    state.selectedLoadout = loadout.uuid !== undefined ? loadout.uuid : 0;
                }
                if (!loadout.equipment) loadout.equipment = {};
                for (var slot in gear) {
                    loadout.equipment[slot] = gear[slot] === null ? null : { id: gear[slot] };
                }
                await new Promise(function(res, rej) {
                    var q = store().put(state, 'dps-calc-state');
                    q.onsuccess = function() { res(); };
                    q.onerror = function() { rej(q.error); };
                });
                alert(__DONE__);
                location.reload();
            } catch (e) { alert("Couldn't load your gear into the calculator. Open the calculator once, then try again."); } })();
        """
    }

    private val handler = Handler(Looper.getMainLooper())
    private val calculatorItems = CalculatorItems.shared(context)   // the calculator's item list
    // Items that look exactly alike share an icon (assets/items/canvas.tsv; no pictures are read)
    private val sharedIcon: Map<Int, Int> by lazy { ItemCatalog.sharedIcons(context) }
    // Every wearable item's icon in brief, read the first time Import is used
    @Volatile private var summaries: GearSummaryReader.GearSummaries? = null

    private var web: WebView? = null
    private var status: TextView? = null
    private var busy = false

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    // ---------------- Called by BubbleService ----------------

    fun buildView(webView: WebView): View {
        web = webView
        // The script that loads your gear says what went wrong in an alert: show it in the status line
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                showStatus(message)
                result?.confirm()
                return true
            }
            // ("leave this page?": always yes, as before this client was set, or the reload that shows the
            // imported gear could be cancelled)
            override fun onJsBeforeUnload(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                result?.confirm()
                return true
            }
        }

        val buttons = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(6), dp(4), dp(6), dp(4))
            setBackgroundColor(PARCHMENT)
            addView(button("Import my gear") { importGear() }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        val statusView = TextView(context).apply {
            textSize = 12f
            setTextColor(DARK_BROWN)
            setBackgroundColor(PARCHMENT)
            setPadding(dp(8), 0, dp(8), dp(4))
            visibility = View.GONE
        }
        status = statusView

        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(buttons, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(statusView, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(webView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    // ---------------- Small building blocks ----------------

    private fun button(label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        textSize = 13f
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(dp(6), dp(6), dp(6), dp(6))
        background = GradientDrawable().apply {
            setColor(BUTTON_BROWN)
            cornerRadius = dp(6).toFloat()
        }
        setOnClickListener { onClick() }
    }

    private fun showStatus(text: String?) {
        status?.text = text ?: ""
        status?.visibility = if (text == null) View.GONE else View.VISIBLE
    }

    // ---------------- Importing ----------------

    private fun importGear() {
        if (busy) return
        if (!capture.isActive) {
            showStatus("Waiting for screen-capture permission...")
            capture.request { ok ->
                if (ok) importGear()
                else showStatus("Couldn't turn on screen capture: ${capture.lastError}.")
            }
            return
        }

        busy = true
        capture.matchScreenSize()
        setOverlaysVisible(false)
        handler.postDelayed({
            val shot = capture.grab()
            setOverlaysVisible(true)
            if (shot == null) {
                busy = false
                showStatus("Couldn't capture the screen. Try again.")
                return@postDelayed
            }
            showStatus("Reading your gear...")
            // (the item list is loaded first, in the background, if no tool has loaded it yet)
            ItemCatalog.get(context) { data -> Thread {
                val result = try {
                    val pixels = LightBoxReader.Pixels(shot)
                    shot.recycle()   // (the screenshot is let go once its pixels are read)
                    read(pixels, data)
                } catch (_: Throwable) {
                    // e.g. out of memory on an older phone: say so, and let Import be tapped again
                    Result(null, "Couldn't read your gear. Try again.")
                }
                handler.post {
                    busy = false
                    if (result.gear != null) loadIntoCalculator(result.gear, result.text) else showStatus(result.text)
                }
            }.start() }
        }, CAPTURE_DELAY_MS)
    }

    // What an import found: the calculator's item for each slot (null when nothing could be read), and what to say
    private class Result(val gear: Map<String, Int?>?, val text: String)

    // Reads the equipment tab (background thread)
    private fun read(pixels: LightBoxReader.Pixels, data: ItemData): Result {
        val panel = PanelFinder.findPanel(pixels)
            ?: return Result(null, "Couldn't find your side panel. Make sure the equipment tab is open and not covered, then try again.")
        if (!PanelFinder.tabOpen(pixels, panel, 0, PanelFinder.EQUIPMENT_TAB))
            return Result(null, "Open your equipment tab in the game, then tap Import my gear.")
        val brief = summaries ?: GearSummaryReader.GearSummaries(
            context.assets.open("dps/gear_summaries.bin").use { it.readBytes() }).also { summaries = it }
        val worn = GearSummaryReader.readEquipmentBySummaries(pixels, panel, brief, CalculatorItems.SLOT_ORDER)
            ?: return Result(null, "Couldn't read your equipment slots. Make sure the equipment tab is open and not covered, then try again.")
        val gear = GearImport.choose(worn.slots, data, data.lookAlikes(sharedIcon)) { id, slot, name, picture ->
            calculatorItems.calculatorItem(id, slot, name, picture)?.id
        }
        // worn, but not something the calculator has
        val missing = worn.slots.filter { (slot, fits) -> fits.isNotEmpty() && gear[slot] == null }.keys
        val text = when {
            gear.values.all { it == null } && missing.isEmpty() -> "Nothing is equipped, so the calculator's gear was cleared."
            missing.isEmpty() -> "Loaded your gear into the calculator."
            else -> "Loaded your gear into the calculator. It doesn't have the item in your " +
                missing.joinToString(", ") { (CalculatorItems.SLOT_LABELS[it] ?: it).lowercase() } + " slot, so that's left empty."
        }
        return Result(gear, text)
    }

    private fun loadIntoCalculator(gear: Map<String, Int?>, done: String) {
        val json = JSONObject()
        for ((slot, id) in gear) json.put(slot, id ?: JSONObject.NULL)
        showStatus("Loading your gear into the calculator...")
        // (the page says when it's done, or what went wrong, through onJsAlert)
        web?.evaluateJavascript(LOAD_GEAR_JS.replace("__GEAR__", json.toString())
            .replace("__DONE__", JSONObject.quote(done)), null)
        // clear it after a moment, unless something else is showing by then (an error, or a new import)
        handler.postDelayed({ if (status?.text?.toString() == done) showStatus(null) }, 6000)
    }
}

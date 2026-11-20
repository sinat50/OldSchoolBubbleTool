package com.sinat.osrsbubbletool

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.core.view.isGone
import androidx.core.view.WindowCompat

// The app's own screens: the main screen, Settings, Permissions, and Legal.
class MainActivity : Activity() {

    companion object {
        private const val REQUEST_NOTIFICATIONS = 1
        // The app's Discord server (also in the README and at the top of every release's notes)
        const val DISCORD_INVITE = "https://discord.gg/MM6mFmkGh"   // (Quest Helper's help page uses it too)

        // The BSD 2-Clause licence conditions and disclaimer (used by the Zulrah, ToA Puzzle Helper, Mastering Mixology, RuneLite, Teleport Finder and Quest Helper credits)
        private const val BSD_TERMS =
            "Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:\n\n" +
                "1. Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.\n\n" +
                "2. Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.\n\n" +
                "THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS \"AS IS\" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE."
    }

    private enum class Screen { MAIN, SETTINGS, PERMISSIONS, LEGAL, SYNC }
    private var screen = Screen.MAIN
    private var backCallback: OnBackInvokedCallback? = null

    private val ui by lazy { Ui(this) }
    private fun dp(value: Int) = ui.dp(value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // turning the phone rebuilds the screen; stay on the same page
        val saved = savedInstanceState?.getString("screen")
        show(Screen.entries.firstOrNull { it.name == saved } ?: Screen.MAIN)
        if (savedInstanceState == null) Thread { removeOldDownloads(); removeWikiSyncData() }.start()
    }

    // Test versions of the app downloaded the game pictures instead of coming with them. Those copies
    // aren't used any more: free the space (once).
    private fun removeOldDownloads() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        if (prefs.getBoolean("old_downloads_removed", false)) return
        try {
            for (name in listOf("dps_icons", "puzzles", "wiki_images")) java.io.File(filesDir, name).deleteRecursively()
            filesDir.listFiles()?.filter { it.name.startsWith("osrs-dps-calc-") }?.forEach { it.delete() }
            prefs.edit { putBoolean("old_downloads_removed", true) }
        } catch (_: Exception) {
            // try again next time
        }
    }

    // Older versions read your quests and levels from WikiSync, which the OSRS Wiki doesn't allow other
    // apps to use. The RuneScape name and data they saved are deleted (nothing happens if there are none).
    private fun removeWikiSyncData() {
        try { deleteSharedPreferences("wikisync") } catch (_: Exception) {}
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("screen", screen.name)
    }

    // Coming back from a settings page: update the permission ticks (and the colours, if dark mode was
    // switched in the bubble's Settings window meanwhile)
    override fun onResume() {
        super.onResume()
        Palette.dark = AppSettings.isDark(this)   // (this screen's colours while it's in front, see show)
        // back from Bubble Sync's camera or file chooser: show what was read (or why a file couldn't be)
        if (screen == Screen.SYNC) syncPage.cameBack()
        if (screen == Screen.MAIN || screen == Screen.PERMISSIONS || screen == Screen.SYNC || AppSettings.isDark(this) != shownDark) show(screen)
        // look for a newer version on GitHub each time the app is opened (unless that's switched off in Settings)
        if (AppSettings.checksUpdates(this)) UpdateChecker.check(this) { if (!isDestroyed) styleUpdateButton() }
    }

    // Gone to the background: the shared colours go back to the bubble's (its windows were built in those)
    override fun onPause() {
        super.onPause()
        if (BubbleService.isRunning) Palette.dark = BubbleService.builtDark
    }

    private var shownDark = false   // whether this screen was last drawn in the dark colours

    private fun show(s: Screen) {
        screen = s
        // The colours are shared with the bubble. While this screen is in front it uses its own (they can differ when
        // the bubble is holding off a dark mode switch, the phone's having changed while a tool was in use); the
        // bubble's are put back when it goes to the background (onPause).
        Palette.dark = AppSettings.isDark(this)
        val dark = Palette.dark
        shownDark = dark
        val content = when (s) {
            Screen.MAIN -> mainScreen()
            Screen.SETTINGS -> settingsScreen()
            Screen.PERMISSIONS -> permissionsScreen()
            Screen.LEGAL -> legalScreen()
            Screen.SYNC -> syncScreen()
        }
        setContentView(content)
        // the clock and icons along the top (and bottom) of the screen: dark on the light page, light on the dark one
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        updateBackHandling()
    }

    // ---------------- Back button: sub-screens go back to the main screen ----------------

    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        backCallback?.let { onBackInvokedDispatcher.unregisterOnBackInvokedCallback(it) }
        backCallback = null
        if (screen != Screen.MAIN) {
            val cb = OnBackInvokedCallback { show(Screen.MAIN) }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb)
            backCallback = cb
        }
    }

    // Only used on Android 12 and older. Newer phones use the callback in updateBackHandling() above,
    // so the warning about back gestures doesn't apply here.
    @android.annotation.SuppressLint("GestureBackNavigation")
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onBackPressed() {
        if (screen != Screen.MAIN) show(Screen.MAIN) else super.onBackPressed()
    }

    // ---------------- Main screen ----------------

    private fun mainScreen(): View {
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val disclaimer = ui.text("Unofficial fan-made tool. Not affiliated with Jagex.", 12f, Palette.MUTED).apply { gravity = Gravity.CENTER }
        if (!landscape) return page {
            addView(mainHero(), matchWidth(12))
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                background = GradientDrawable().apply { setColor(Palette.SURFACE); cornerRadius = dp(12).toFloat() }
                clipToOutline = true
                addView(mainList())
                addView(mainLinks())
            }, matchWidth(24))
            addView(disclaimer, matchWidth(16))
        }

        // Sideways: Start Bubble on the left, everything else in a panel on the right
        val left = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(8), dp(24), dp(10))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(mainHero(), matchWidth(0))
            addView(View(this@MainActivity), LinearLayout.LayoutParams(1, 0, 1f))
            addView(disclaimer, matchWidth(8))
        }
        val right = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Palette.SURFACE)
            addView(ScrollView(this@MainActivity).apply { addView(mainList()) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            addView(mainLinks())
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Palette.BG)
            addView(ScrollView(this@MainActivity).apply { isFillViewport = true; addView(left) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            addView(right, LinearLayout.LayoutParams(dp(264), ViewGroup.LayoutParams.MATCH_PARENT))
            keepClearOfBars(this)
        }
    }

    // The logo, a few words on how it works, Start Bubble, and whether the permissions are ready
    private fun mainHero() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@MainActivity).apply { setImageResource(R.drawable.ic_bubble) }, LinearLayout.LayoutParams(dp(56), dp(56)))
        addView(ui.text("Old School Bubble Tool", 22f, Palette.TEXT, 700).apply { gravity = Gravity.CENTER }, matchWidth(8))
        addView(ui.text("Tap the bubble to open a tool, drag it to move it, and hold it for the menu.", 13f, Palette.MUTED).apply {
            gravity = Gravity.CENTER
        }, matchWidth(4))
        addView(LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            ui.press(this, Palette.GOLD, 10)
            addView(ui.icon("play", Palette.ON_GOLD))
            addView(ui.text("Start Bubble", 16f, Palette.ON_GOLD, 700).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { startBubble() }
        }, LinearLayout.LayoutParams(dp(280), dp(56)).apply { topMargin = dp(16) })
        // while the bubble is on: say so, so nobody wonders whether to tap Start Bubble again
        if (BubbleService.isRunning) addView(ui.text("The bubble is running", 13f, Palette.MUTED, 500).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
        addView(permissionStatus(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(4) })
    }

    // Says whether the app has what it needs. Tap it to see the Permissions page.
    private fun permissionStatus(): View {
        val allReady = FarmingTimers.notificationsAllowed(this) && FarmingTimers.canUseExactAlarms(this)
        val canShow = Settings.canDrawOverlays(this)
        val words = if (!canShow) "Allow the bubble to show over apps" else if (allReady) "Permissions ready" else "Some permissions are off"
        val colour = if (canShow && allReady) Palette.OK else Palette.CAUTION
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(Ui.TAP_SIZE)
            setPadding(dp(10), 0, dp(10), 0)
            ui.press(this, null, 8)
            addView(ui.icon(if (canShow && allReady) "check" else "warning", colour, 16))
            addView(ui.text(words, 13f, colour, 600).apply { setPadding(dp(6), 0, 0, 0) })
            // an arrow at the end, so it reads as something to tap (it opens the Permissions page)
            addView(ui.icon("next", colour, 16).apply { (layoutParams as LinearLayout.LayoutParams).leftMargin = dp(2) })
            setOnClickListener { show(Screen.PERMISSIONS) }
        }
    }

    // The main screen's list of pages
    private fun mainList() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(mainRow("sync", "Bubble Sync") { show(Screen.SYNC) })   // your quests, levels and diaries, without starting the bubble
        addView(mainRow("gear", "Settings") { show(Screen.SETTINGS) })
        addView(mainRow("shield", "Permissions") { show(Screen.PERMISSIONS) })
        addView(mainRow("flag", "Report An Issue") { chooseWhereToReport() })
        if (AppSettings.checksUpdates(this@MainActivity)) {
            val state = ui.text("", 12f, Palette.MUTED, 600)
            updateStatus = state
            styleUpdateButton()
            addView(mainRow("download", "Updates", state) { openReleasePage() })
        } else updateStatus = null
        addView(mainRow("doc", "Legal") { show(Screen.LEGAL) })
    }

    private fun mainRow(icon: String, title: String, status: TextView? = null, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(48)
        setPadding(dp(16), 0, dp(14), 0)
        ui.press(this, null)
        addView(ui.icon(icon))
        addView(ui.text(title, 14f, Palette.TEXT, 500).apply { setPadding(dp(12), 0, dp(8), 0) },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(status ?: ui.icon("next", Palette.MUTED))
        setOnClickListener { onClick() }
    }

    // The Ko-fi and Discord links, always in view
    private fun mainLinks() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Palette.RAISED)
        fun link(icon: String, words: String, onClick: () -> Unit) = LinearLayout(this@MainActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            minimumHeight = dp(52)
            ui.press(this, null)
            addView(ui.icon(icon))
            addView(ui.text(words, 13f, Palette.TEXT, 600).apply { setPadding(dp(8), 0, 0, 0) })
            setOnClickListener { onClick() }
        }
        addView(link("coffee", "Buy me a coffee") { openKofi() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.6f))
        addView(link("chat", "Discord") { openDiscord() }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    // How the update check went, in the Updates row
    private var updateStatus: TextView? = null

    private fun styleUpdateButton() {
        val status = updateStatus ?: return
        val state = UpdateChecker.state(this)
        status.text = when (state) {
            UpdateChecker.State.UPDATE_AVAILABLE -> "Update available"
            UpdateChecker.State.UP_TO_DATE -> "Up to date"
            UpdateChecker.State.NOT_CHECKED -> "Check now"
        }
        status.setTextColor(when (state) {
            UpdateChecker.State.UPDATE_AVAILABLE -> Palette.ACCENT
            UpdateChecker.State.UP_TO_DATE -> Palette.OK
            UpdateChecker.State.NOT_CHECKED -> Palette.MUTED
        })
    }

    private fun openReleasePage() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, UpdateChecker.RELEASES_PAGE.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // The owner's Ko-fi page, for anyone who'd like to say thanks
    private fun openKofi() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, "https://ko-fi.com/sinat50".toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // "Report An Issue": a small box asking where to report it, GitHub (the report form) or the Discord server (a chat, the same link as the README's and the release notes')
    private fun chooseWhereToReport() {
        var box: AlertDialog? = null
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            addView(ui.text("Report An Issue", 17f, Palette.TEXT, 700))
            addView(label("Where would you like to tell us about it?", 13f), matchWidth(6))
            addView(button("GitHub") { box?.dismiss(); reportIssue() }, matchWidth(14))
            addView(button("Discord") { box?.dismiss(); openDiscord() }, matchWidth(8))
            addView(ui.button("Cancel") { box?.dismiss() }, matchWidth(8))
        }
        box = AlertDialog.Builder(this).setView(choices).create().apply {
            // the box's edge like a tool window's (a dark line outside a lighter one), so it shows on the dimmed
            // screen behind it in dark mode too
            window?.setBackgroundDrawable(android.graphics.drawable.LayerDrawable(arrayOf(
                GradientDrawable().apply { setColor(Palette.EDGE); cornerRadius = dp(12).toFloat() },
                GradientDrawable().apply { setColor(Palette.BORDER); cornerRadius = dp(11).toFloat() },
                GradientDrawable().apply { setColor(Palette.SURFACE); cornerRadius = dp(10).toFloat() }
            )).apply { setLayerInset(1, dp(1), dp(1), dp(1), dp(1)); setLayerInset(2, dp(2), dp(2), dp(2), dp(2)) })
            show()
        }
    }

    private fun openDiscord() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, DISCORD_INVITE.toUri()))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open Discord or a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // Opens GitHub's "new issue" page with the app's report form (.github/ISSUE_TEMPLATE/report_an_issue.yml),
    // its "Phone and app" box already filled in. The player sees everything before sending it, and can change it.
    private fun reportIssue() {
        val address = "https://github.com/sinat50/OldSchoolBubbleTool/issues/new".toUri().buildUpon()
            .appendQueryParameter("template", "report_an_issue.yml")
            .appendQueryParameter("device", deviceDetails())
            .build()
        try {
            startActivity(Intent(Intent.ACTION_VIEW, address))
        } catch (e: Exception) {
            Toast.makeText(this, "Couldn't open a browser", Toast.LENGTH_SHORT).show()
        }
    }

    // The phone and app details that help track down a problem (nothing personal: no account or phone number)
    private fun deviceDetails(): String {
        val info = packageManager.getPackageInfo(packageName, 0)
        @Suppress("DEPRECATION") val build = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else info.versionCode.toLong()
        // the whole screen in pixels, the way round the phone is held now
        val (width, height) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
            windowManager.maximumWindowMetrics.bounds.let { it.width() to it.height() }
        else @Suppress("DEPRECATION") android.util.DisplayMetrics().also { windowManager.defaultDisplay.getRealMetrics(it) }.let { it.widthPixels to it.heightPixels }
        val density = resources.displayMetrics.density
        return "App: ${info.versionName} (build $build)\n" +
            "Phone: ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n" +
            "Screen: ${width} × ${height} pixels, ${resources.configuration.densityDpi} dpi (×$density)\n" +
            "Phone text size: ×${resources.configuration.fontScale}\n" +
            "App dark mode: ${if (AppSettings.isDark(this)) "on" else "off"}\n" +
            "Bubble running: ${if (BubbleService.isRunning) "yes" else "no"}"
    }

    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "First allow \"Display over other apps\"", Toast.LENGTH_LONG).show()
            show(Screen.PERMISSIONS)
            return
        }
        startForegroundService(Intent(this, BubbleService::class.java))
        Toast.makeText(this, "Bubble started", Toast.LENGTH_SHORT).show()
        // a moment later, once it's up: the main screen says it's running
        window.decorView.postDelayed({ if (!isDestroyed && screen == Screen.MAIN) show(Screen.MAIN) }, 600)
    }

    // ---------------- Settings screen ----------------

    // The same settings as the bubble's Settings window
    private fun settingsScreen(): View = page {
        addView(pageTop("Settings"), matchWidth(0))
        // dark mode switched: draw this screen again in the new colours
        SettingsTool(this@MainActivity, wide = resources.configuration.screenWidthDp >= 600) {
            if (AppSettings.isDark(this@MainActivity) != shownDark) show(Screen.SETTINGS)
        }.addSettings(this)
    }

    // The same page as the bubble's Bubble Sync window (kept between redraws, so a half-pasted code stays)
    private val syncPage by lazy { SyncTool(this, inBubble = false) }

    private fun syncScreen(): View = page {
        addView(pageTop(""), matchWidth(0))   // (the page has its own title)
        syncPage.addTo(this) { show(Screen.SYNC) }
    }

    // ---------------- Permissions screen ----------------

    private fun permissionsScreen(): View = page {
        addView(pageTop("Permissions"), matchWidth(0))
        addView(ui.text("What the app asks for, and why. Nothing here is sent anywhere.", 13f, Palette.MUTED), matchWidth(4))

        addView(permissionCard(
            "Display over other apps",
            "Needed for the bubble and every tool window to show on top of the game. The app can't work without this.",
            allowed = Settings.canDrawOverlays(this@MainActivity)
        ) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, "package:$packageName".toUri()))
        }, matchWidth(12))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            addView(permissionCard(
                "Notifications",
                "Lets Timers tell you when a farming patch or birdhouse run is ready. Also shows the small notification Android requires while the bubble is running.",
                allowed = FarmingTimers.notificationsAllowed(this@MainActivity)
            ) { askForNotifications() }, matchWidth(10))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addView(permissionCard(
                "Alarms & reminders",
                "Lets Timers alert you right on time. Without it, Android may deliver timer alerts a few minutes late to save battery.",
                allowed = FarmingTimers.canUseExactAlarms(this@MainActivity)
            ) {
                openSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:$packageName".toUri()))
            }, matchWidth(10))
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            addView(permissionCard(
                "Open wiki links in the bubble",
                "When you use the game's wiki button (under the world map), the page opens in the bubble's " +
                    "Wiki window instead of your browser. Tap Allow, then Add link, and tick oldschool.runescape.wiki. " +
                    "Wiki links from other apps still open in your normal browser.\n\n" + lastWikiLink(),
                allowed = wikiLinksAllowed()
            ) {
                openSettings(Intent(Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS, "package:$packageName".toUri()))
            }, matchWidth(10))
        }

        addView(permissionCard(
            "Screen capture",
            "Used by the Puzzle Box and Light Box Solvers, the ToA Puzzle Helper, Gear Loadouts' \"Scan Current Loadout\" and the DPS Calculator's \"Import my gear\" to look at the game screen. Android doesn't allow apps to turn this on ahead of time, so it asks the first time you use one of those tools, once each time the bubble is started. On Android 14 and newer you can choose \"A single app\" and pick Old School RuneScape, so only the game is ever captured. Pictures stay on your phone.",
            allowed = null, onAllow = null
        ), matchWidth(10))
    }

    // What happened to the last wiki link that reached the app (to help sort out problems)
    private fun lastWikiLink(): String {
        val prefs = getSharedPreferences("wiki_links", MODE_PRIVATE)
        if (!prefs.contains("time")) return "Last link: none have reached the app yet."
        val time = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(prefs.getLong("time", 0)))
        val where = if (prefs.getBoolean("to_bubble", false)) "opened in the bubble" else "sent to your browser"
        return "Last link: at $time from ${prefs.getString("from", "?")}, $where."
    }

    // Has "Open by default" been turned on for the wiki's web address?
    private fun wikiLinksAllowed(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return try {
            val manager = getSystemService(android.content.pm.verify.domain.DomainVerificationManager::class.java)
            val state = manager.getDomainVerificationUserState(packageName) ?: return false
            val host = state.hostToStateMap["oldschool.runescape.wiki"]
            host == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED ||
                host == android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        } catch (e: Exception) {
            false
        }
    }

    private fun askForNotifications() {
        val prefs = getSharedPreferences("app", MODE_PRIVATE)
        val askedBefore = prefs.getBoolean("asked_notifications", false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            (!askedBefore || shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS))) {
            prefs.edit { putBoolean("asked_notifications", true) }
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
        } else {
            // Android won't show the question again once it's been refused; open the settings page instead
            openSettings(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATIONS && screen == Screen.PERMISSIONS) show(Screen.PERMISSIONS)
    }

    // allowed: true/false shows a tick or an "Allow" button; null means there's nothing to press
    private fun permissionCard(title: String, text: String, allowed: Boolean?, onAllow: (() -> Unit)?): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = GradientDrawable().apply { setColor(Palette.SURFACE); cornerRadius = dp(12).toFloat() }
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(Ui.TAP_SIZE)
            addView(ui.text(title, 14f, Palette.TEXT, 600).apply { setPadding(0, 0, dp(8), 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            when (allowed) {
                true -> {
                    addView(ui.icon("check", Palette.OK, 16))
                    addView(ui.text("Allowed", 13f, Palette.OK, 600).apply { setPadding(dp(4), 0, 0, 0) })
                }
                false -> if (onAllow != null) addView(ui.button("Allow", Ui.ButtonStyle.GOLD, onAllow))
                null -> addView(ui.text("Asked when needed", 12f, Palette.MUTED))
            }
        }
        card.addView(top)
        card.addView(ui.text(text, 13f, Palette.MUTED), matchWidth(4))
        return card
    }

    private fun openSettings(intent: Intent) {
        try {
            startActivity(intent)
        } catch (e: Exception) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri()))
        }
    }

    // ---------------- Legal screen ----------------

    private fun legalScreen(): View = page {
        addView(pageTop("Legal"), matchWidth(0))

        fun section(title: String, text: String, size: Float = 13f) {
            addView(label(title, 15f, bold = true), matchWidth(16))
            addView(ui.text(text, maxOf(size, 12f), Palette.MUTED), matchWidth(4))
        }

        // A button that shows (and hides again) the full text of a licence kept in assets/licences/
        fun fullText(file: String) {
            val showLabel = "Show the full licence"
            val hideLabel = "Hide the full licence"
            val text = ui.text("", 12f, Palette.MUTED).apply { isGone = true }
            val toggle = button(showLabel) {}
            toggle.setOnClickListener {
                // read only when first asked for (the GPL is 35 KB of text)
                if (text.text.isEmpty()) text.text = assets.open("licences/$file").bufferedReader().use { it.readText() }
                text.isGone = !text.isGone
                toggle.text = if (text.isGone) showLabel else hideLabel
            }
            addView(toggle, wrap().apply { topMargin = dp(8) })
            addView(text, matchWidth(6))
        }

        section("Jagex",
            "Old School RuneScape and RuneScape are trademarks of Jagex Limited. Item, monster and game pictures are (c) Jagex Limited. " +
            "The app comes with no game pictures. The Puzzle Box Solver's colour summaries of each tile were worked out " +
            "from the OSRS Wiki's solved puzzle pictures, and Import my gear's colour summaries of each item from " +
            "RuneLite's item icons; those pictures are Jagex's. Gear Loadouts' screenshots are your own screen, kept on " +
            "your phone.\n\n" +
            "Old School Bubble Tool is free and has no ads or paid features. It is not endorsed by or affiliated with Jagex, RuneLite, the " +
            "RuneLite plugin authors credited below, or the Old School RuneScape Wiki.")

        section("Privacy",
            "The app has no analytics, no ads and no account of its own, and the developer never receives anything from it. " +
            "It talks to the internet only when you use a tool that needs it: the OSRS Wiki, the DPS and XP calculators, " +
            "GE Prices, the Shooting Star Tracker and Tears of Guthix Worlds load in the app like a browser (they see your internet address and may " +
            "set cookies, as in any browser); the Teleport " +
            "Finder sends your search words to the wiki; and when the app opens it asks GitHub whether there's a newer " +
            "version (you can turn that off in Settings). Bubble Sync uses the camera only while you scan the Bubble Tool " +
            "Sync plugin's QR code, and the account it reads (name, levels, quests and diary tasks) stays on the phone. " +
            "Screenshots are read on the phone and let go straight away. The only ones kept are pictures you save " +
            "with Save picture to phone, and Gear Loadouts' screenshots of your inventory, equipment, rune pouch and " +
            "spellbook, kept inside the app for your loadouts.\n\n" +
            "Your settings, notes, timers, loadouts (with their screenshots) and synced account are saved on the phone, and go into your phone's normal Android backup if you have that on. Nothing else is stored or sent.")

        section("This app's licence",
            "Old School Bubble Tool is free software: (c) 2026 sinat50, licensed under the GNU General Public License, version 3 " +
            "or any later version (gnu.org/licenses/gpl-3.0). You can use, change and share it; if you share a changed " +
            "version, share its source code under the same licence. It comes with no warranty. Its source code is at " +
            "github.com/sinat50/OldSchoolBubbleTool.\n\n" +
            "That covers the app's own code. What it includes from others keeps its own terms, listed below.")
        fullText("gpl-3.0.txt")

        section("Libraries",
            "The app is built with Google's AndroidX and Material Components libraries and JetBrains' Kotlin standard " +
            "library, and Bubble Sync reads QR codes with ZXing (github.com/zxing/zxing), all used under the Apache " +
            "License, Version 2.0 (apache.org/licenses/LICENSE-2.0).\n\n" +
            "Copyright The Android Open Source Project\n" +
            "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors\n" +
            "Copyright ZXing authors", size = 11f)
        fullText("apache-2.0.txt")

        section("Inter font",
            "The app's writing uses Inter (github.com/rsms/inter), used under the SIL Open Font License, Version 1.1 " +
            "(openfontlicense.org).\n\n" +
            "Copyright 2020 The Inter Project Authors", size = 11f)
        fullText("ofl-1.1.txt")

        section("OSRS Wiki",
            "Text and data from the Old School RuneScape Wiki (oldschool.runescape.wiki) are used under the Creative Commons " +
            "Attribution-NonCommercial-ShareAlike 3.0 licence (creativecommons.org/licenses/by-nc-sa/3.0). " +
            "They have been shortened and reformatted for the app, and anything adapted from them is shared under the same licence. " +
            "(The wiki's pictures aren't under this licence: they're Jagex's, see above.) It includes:\n\n" +
            "\u2022 DPS Calculator's Import my gear and older Gear Loadouts' item lists: every item's name, ID and equipment slot, from the wiki's item pages\n" +
            "\u2022 Quest Helper: some puzzle answers and step directions from the wiki's quest guides (such as Lunar Diplomacy's)\n" +
            "\u2022 Hunter Rumours: rumour lists, locations, travel and equipment from the pages Hunters' Rumours and Hunters' Rumours/Strategies\n" +
            "\u2022 Teleport Finder: search suggestions, where NPCs, monsters and places are on the map, monster levels, and descriptions of some teleport destinations\n" +
            "\u2022 ToA Puzzle Helper: the outline of the Path of Het room, traced from the wiki's map")

        section("OSRS Wiki DPS calculator",
            "DPS Calculator gear import: the item list comes from the OSRS Wiki DPS calculator's " +
            "repository (github.com/weirdgloop/osrs-dps-calc), which is licensed under the GNU General Public License v3.0 " +
            "(gnu.org/licenses/gpl-3.0). That list is itself made from the OSRS Wiki. Gear is recognised from short colour " +
            "summaries of each item, worked out from RuneLite's item icons (static.runelite.net), which are (c) Jagex Ltd.", size = 11f)

        section("Websites",
            "The OSRS Wiki, Real-time Prices, the DPS calculator, the XP calculator (oldschool.tools), the Shooting Star Tracker (07.gg) and " +
            "Tears of Guthix Worlds (togcrowdsourcing.com, crowdsourced with the ToG Crowdsourcing RuneLite plugin) " +
            "are websites run by their own owners. The app opens them as they are, like a browser.")

        section("RuneLite",
            "Farming growth times based on RuneLite's Time Tracking plugin. Teleport Finder: newer teleport destinations " +
            "and the list of dungeon entrances, from RuneLite's world map. Older Gear Loadouts' item lists: which items can be noted, and their noted item IDs, from RuneLite's item list (github.com/runelite/runelite). Import my gear: which items look exactly alike, worked out from RuneLite's item icons (static.runelite.net; the pictures are (c) Jagex Ltd and aren't included). Bubble Sync: each quest's RuneLite quest ID, from RuneLite's quest list.\n\n" +
            "Copyright (c) 2016-2017, Adam <Adam@sigterm.info>\nCopyright (c) 2018-2019, Abex\n" +
            "Copyright (c) 2018, NotFoxtrot <https://github.com/NotFoxtrot>\nCopyright (c) 2018, Morgan Lewis <https://github.com/MESLewis>\n" +
            "Copyright (c) 2020, Arman S <https://github.com/Rman887>\n" +
            "All rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("2048",
            "The Game Room's 2048 is the app's own version of the game created by Gabriele Cirulli.", size = 11f)

        section("Chess",
            "The Game Room's chess computer player is CuckooChess, a chess engine by Peter Österlund, included unchanged " +
            "(its chess folder) from the DroidFish project (github.com/peterosterlund2/droidfish), with its opening book and " +
            "endgame tables. The strength of each level comes from CuckooChess's rating table (in DroidFish's " +
            "EngineControl.java). The board, the pieces and the rest of the game are the app's own.\n\n" +
            "CuckooChess - A java chess program.\nCopyright (C) 2011 Peter Österlund, peterosterlund2@gmail.com\n\n" +
            "This program is free software: you can redistribute it and/or modify it under the terms of the GNU General " +
            "Public License as published by the Free Software Foundation, either version 3 of the License, or (at your " +
            "option) any later version. This program is distributed in the hope that it will be useful, but WITHOUT ANY " +
            "WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the " +
            "GNU General Public License for more details (gnu.org/licenses/gpl-3.0).", size = 11f)

        section("Zulrah Helper",
            "Zulrah Helper rotation data and arena layout adapted from the Zulrah Helper RuneLite plugin " +
            "(github.com/while-loop/runelite-plugins).\n\n" +
            "Copyright (c) 2020, Anthony Alves\nCopyright (c) 2026, Ron Young\nAll rights reserved.\n\n" +
            BSD_TERMS,
            size = 11f)

        section("ToA Puzzle Helper",
            "Path of Scabaras puzzle rules, the addition puzzle's answers and the Path of Het's mirror answers adapted from the Tombs of Amascut RuneLite plugin " +
            "(github.com/LlemonDuck/tombs-of-amascut).\n\n" +
            "Copyright (c) 2022, LlemonDuck\nCopyright (c) 2022, TheStonedTurtle\n" +
            "Copyright (c) 2019, Ron Young <https://github.com/raiyni>\nAll rights reserved.\n\n" +
            BSD_TERMS,
            size = 11f)

        section("Mastering Mixology",
            "Each potion's recipe and the lever colours, from the Mastering Mixology RuneLite plugin by Hexagon " +
            "(github.com/hex-agon/mastering-mixology), used under the BSD 2-Clause License. The potion names are " +
            "recognised by their shapes, worked out on a computer from the game font in RuneLite " +
            "(github.com/runelite/runelite), used under the BSD 2-Clause License (Copyright (c) 2016-2017, Adam); " +
            "the font itself isn't in the app. The potions in your inventory are recognised by colour summaries of " +
            "each potion (a few squares of colour, not pictures), worked out from RuneLite's item icons " +
            "(static.runelite.net), which are (c) Jagex Ltd and aren't in the app; which items are the potions is " +
            "from the same plugin.\n\n" +
            "Copyright (c) 2024, hexagon@fking.work\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Teleport Finder",
            "Teleport destinations, the walking map, and the doors, ladders, cave entrances, boats, portals and levers that join places, from the Shortest Path RuneLite plugin " +
            "(github.com/Skretzo/shortest-path), used under the BSD 2-Clause License.\n\n" +
            "Copyright (c) Skretzo and the Shortest Path contributors\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Teleport Finder: cave entrances",
            "Some cave and boss-room entrances were checked against the crowdsourced transport data of the RuneScape map project (github.com/mejrs/data_osrs).",
            size = 11f)

        section("Teleport Finder: obstacles",
            "Where the game's obstacles are (used to join closed-off areas of the walking map to the rest), from the Golems Don't Die RuneLite plugin " +
            "(github.com/Varzeki/golems-dont-die), used under the BSD 2-Clause License.\n\n" +
            "Copyright (c) 2026, Varzeki\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Quest Helper",
            "Quest guides, quest requirements and achievement diary tasks adapted from the Quest Helper RuneLite plugin (github.com/Zoinkwiz/quest-helper), and which game values record each diary task (for Bubble Sync).\n\n" +
            QuestGuides.COPYRIGHTS.joinToString("\n") + "\nAll rights reserved.\n\n" +
            BSD_TERMS, size = 11f)

        section("Zen Garden: wood",
            "The Zen Garden's wooden frame uses the \"Dark Wood\" texture from Poly Haven (polyhaven.com), by Dimitrios Savva " +
            "(photography), Rico Cilliers (tiling) and Dario Barresi, released into the public domain (CC0). Its colour was " +
            "toned for the app.", size = 11f)
    }

    // ---------------- Small building blocks ----------------

    // A full screen with the app's background, scrolling, kept clear of the status and navigation bars
    private fun page(padTop: Int = 8, padBottom: Int = 16, fill: LinearLayout.() -> Unit): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(padTop), dp(16), dp(padBottom))
            fill()
        }
        // (the scroll view stretches short pages to fill the screen, so they can still be centred)
        val root: ViewGroup = ScrollView(this).apply { isFillViewport = true; addView(column) }
        root.setBackgroundColor(Palette.BG)   // the darkest brown, so the bubble's menu and windows stand out against it
        keepClearOfBars(root)
        return root
    }

    // Sideways, the camera hole keeps one side clear; the other side gets the same gap, so the page
    // stays centred on the screen rather than shifted away from the camera
    private fun keepClearOfBars(root: View) {
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                val side = maxOf(bars.left, bars.right)
                v.setPadding(side, bars.top, side, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                val side = maxOf(insets.systemWindowInsetLeft, insets.systemWindowInsetRight)
                @Suppress("DEPRECATION")
                v.setPadding(side, insets.systemWindowInsetTop, side, insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // The top of a page: back to the main screen, and the page's name
    private fun pageTop(title: String) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ui.iconButton("back", "Back") { show(Screen.MAIN) })
        addView(ui.text(title, 20f, Palette.TEXT, 700).apply { setPadding(dp(4), 0, 0, 0) })
    }

    private fun matchWidth(top: Int) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(top) }

    private fun wrap() = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

    private fun button(label: String, onClick: () -> Unit) = ui.button(label, onClick = onClick)

    private fun label(text: String, size: Float, bold: Boolean = false) = ui.text(text, size, Palette.TEXT, if (bold) 700 else 400)
}

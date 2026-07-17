package com.blankspeaker.grld

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.text.SpannableString
import android.text.Spanned
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var repo: UsageRepository
    private lateinit var history: DailyHistoryStore
    private lateinit var title: TextView
    private lateinit var summary: TextView
    private lateinit var status: TextView
    private lateinit var usageBarImage: ImageView
    private lateinit var usageBarTrack: FrameLayout
    private lateinit var products: ChipGroup
    private lateinit var progress: ProgressBar
    private lateinit var weekChart: WeekChartView
    private lateinit var weekLabel: TextView
    private lateinit var btnSettings: ImageButton
    private lateinit var btnDonate: Button
    private lateinit var btnSubscribe: Button
    private lateinit var footerCredit: TextView

    private var compactSupport = false // hide donate/subscribe; chart fills leftover
    /** 0 = latest window ending today; more negative = older windows. */
    private var windowOffset = 0

    /** Secret: 8 taps on the horizontal usage bar → compact support. */
    private var barTapCount = 0
    private var barTapWindowStartMs = 0L

    /** Secret: 8 taps on the week chart → unlock Settings update row. */
    private var chartTapCount = 0
    private var chartTapWindowStartMs = 0L

    private val notifPerm = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        onLoginFlowFinished()
    }

    /** Track session so we re-prompt after logout without looping if user cancels. */
    private var wasSignedIn = false
    private var loginPromptedWhileSignedOut = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        repo = UsageRepository.get(this)
        history = DailyHistoryStore.get(this)
        compactSupport = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
            .getBoolean(KEY_COMPACT_SUPPORT, false)
        wasSignedIn = GrokAuth.isSignedIn(this)
        // LoginActivity may CLEAR_TOP back here after Chrome sign-in
        if (intent?.getBooleanExtra(EXTRA_LOGIN_JUST_FINISHED, false) == true) {
            onLoginFlowFinished()
        }

        title = findViewById(R.id.titleText)
        summary = findViewById(R.id.summaryText)
        status = findViewById(R.id.statusText)
        usageBarImage = findViewById(R.id.usageBarImage)
        usageBarTrack = findViewById(R.id.usageBarTrack)
        products = findViewById(R.id.productsList)
        progress = findViewById(R.id.progress)
        weekChart = findViewById(R.id.weekChart)
        weekLabel = findViewById(R.id.weekLabel)
        btnSettings = findViewById(R.id.btnSettings)
        btnDonate = findViewById(R.id.btnDonate)
        btnSubscribe = findViewById(R.id.btnSubscribe)
        footerCredit = findViewById(R.id.footerCredit)

        findViewById<Button>(R.id.btnRefresh).setOnClickListener {
            if (GrokAuth.isSignedIn(this)) {
                refresh()
            } else {
                startLogin()
            }
        }
        findViewById<Button>(R.id.btnOpenGrok).setOnClickListener {
            // Same usage panel deep-link the Mac app uses (not the generic chat home)
            openGrokUsage()
        }
        btnDonate.setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://buymeacoffee.com/blank_speaker")))
        }
        btnSubscribe.setOnClickListener {
            startActivity(
                Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://x.com/blankspeaker/creator-subscriptions/subscribe")
                )
            )
        }
        findViewById<ImageButton>(R.id.btnWeekPrev).setOnClickListener {
            val minOff = minWindowOffset()
            if (windowOffset > minOff) {
                windowOffset -= 1
                updateWeekChart()
            }
        }
        findViewById<ImageButton>(R.id.btnWeekNext).setOnClickListener {
            if (windowOffset < 0) {
                windowOffset += 1
                updateWeekChart()
            }
        }
        btnSettings.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }
        btnSettings.imageAlpha = 180

        // 8 taps on the horizontal usage bar → compact support layout
        usageBarTrack.isClickable = true
        usageBarTrack.setOnClickListener { onUsageBarTapped() }
        // 8 taps on the week chart → unlock Check for updates in Settings
        weekChart.isClickable = true
        weekChart.setOnClickListener { onWeekChartTapped() }

        setupFooterCredit()
        maybeAskNotifPermission()
        applyCompactSupport()
        render(repo.state.value)
        // Recompute 1/2/3-week window once chart has a real width (fold open / tablet)
        weekChart.post { updateWeekChart() }
        weekChart.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateWeekChart()
        }

        // First-run disclaimer, then session / login
        presentDisclaimerIfNeeded {
            if (GrokAuth.isSignedIn(this)) {
                UsageMonitorService.start(this)
                refresh()
            } else {
                // Stay on main screen — user taps Sign In (opens Chrome)
                promptLoginIfNeeded(force = true)
                render(repo.state.value)
            }
        }
    }

    /** One-time third-party disclaimer before any login/session work. */
    private fun presentDisclaimerIfNeeded(then: () -> Unit) {
        val prefs = getSharedPreferences(UI_PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_DISCLAIMER_OK, false)) {
            then()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.disclaimer_title)
            .setMessage(R.string.disclaimer_body)
            .setIcon(R.mipmap.ic_launcher)
            .setCancelable(false)
            .setPositiveButton(R.string.disclaimer_accept) { _, _ ->
                prefs.edit().putBoolean(KEY_DISCLAIMER_OK, true).apply()
                then()
            }
            .show()
    }

    /**
     * Do **not** auto-open a browser/WebView. User taps Sign In when ready.
     * [force] only resets the "already prompted" flag after logout so the
     * Sign In button label stays correct.
     */
    private fun promptLoginIfNeeded(force: Boolean = false) {
        if (GrokAuth.isSignedIn(this)) return
        if (force) loginPromptedWhileSignedOut = false
        updatePrimaryButton()
    }

    /** Chrome device-code login (no in-app WebView). */
    private fun startLogin() {
        loginLauncher.launch(Intent(this, LoginActivity::class.java))
    }

    private fun updatePrimaryButton() {
        val btn = findViewById<Button>(R.id.btnRefresh)
        if (GrokAuth.isSignedIn(this)) {
            btn.setText(R.string.refresh)
        } else {
            btn.setText(R.string.sign_in_button)
        }
    }

    private fun openAuthorProfile() {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AUTHOR_X_URL)))
    }

    private fun setupFooterCredit() {
        val full = getString(R.string.footer_credit)
        val handle = getString(R.string.footer_author_handle)
        val start = full.indexOf(handle)
        if (start < 0) {
            footerCredit.text = full
            return
        }
        val end = start + handle.length
        val span = SpannableString(full)
        span.setSpan(object : ClickableSpan() {
            override fun onClick(widget: View) {
                openAuthorProfile()
            }

            override fun updateDrawState(ds: TextPaint) {
                super.updateDrawState(ds)
                ds.color = 0xFF64B5F6.toInt()
                ds.isUnderlineText = false
                ds.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
        }, start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        footerCredit.text = span
        footerCredit.movementMethod = LinkMovementMethod.getInstance()
        footerCredit.highlightColor = 0x00000000
    }

    private fun onUsageBarTapped() {
        val now = SystemClock.elapsedRealtime()
        if (now - barTapWindowStartMs > BAR_TAP_WINDOW_MS) {
            barTapCount = 0
            barTapWindowStartMs = now
        }
        barTapCount += 1
        if (barTapCount >= BAR_TAP_TARGET) {
            barTapCount = 0
            compactSupport = !compactSupport
            // Same secret also unlocks developer update rows in Settings.
            getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_COMPACT_SUPPORT, compactSupport)
                .putBoolean(KEY_UPDATES_UNLOCKED, true)
                .apply()
            applyCompactSupport()
        }
    }

    private fun onWeekChartTapped() {
        // Same unlock path if the user taps the week chart instead of the bar.
        val now = SystemClock.elapsedRealtime()
        if (now - chartTapWindowStartMs > BAR_TAP_WINDOW_MS) {
            chartTapCount = 0
            chartTapWindowStartMs = now
        }
        chartTapCount += 1
        if (chartTapCount >= BAR_TAP_TARGET) {
            chartTapCount = 0
            getSharedPreferences(UI_PREFS, MODE_PRIVATE).edit()
                .putBoolean(KEY_UPDATES_UNLOCKED, true)
                .apply()
        }
    }

    private fun applyCompactSupport() {
        // Hide support buttons; week chart has layout_weight=1 so it automatically
        // expands into the freed space and keeps the footer on the bottom edge.
        val supportVis = if (compactSupport) View.GONE else View.VISIBLE
        btnDonate.visibility = supportVis
        btnSubscribe.visibility = supportVis
        weekChart.requestLayout()
        findViewById<View>(R.id.rootColumn).requestLayout()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_LOGIN_JUST_FINISHED, false)) {
            onLoginFlowFinished()
        }
    }

    /** After device-code login (Chrome) returns via result or CLEAR_TOP intent. */
    private fun onLoginFlowFinished() {
        // Drop any "tap to return" leftover
        try {
            getSystemService(android.app.NotificationManager::class.java)
                ?.cancel(72)
        } catch (_: Exception) { }
        if (GrokAuth.isSignedIn(this)) {
            wasSignedIn = true
            loginPromptedWhileSignedOut = false
            UsageMonitorService.start(this)
            updatePrimaryButton()
            refresh()
        } else {
            updatePrimaryButton()
        }
    }

    override fun onResume() {
        super.onResume()
        // Pick up history imported from Settings (cross-platform file)
        history.reload()
        updateWeekChart()
        val signedIn = GrokAuth.isSignedIn(this)
        if (signedIn) {
            wasSignedIn = true
            loginPromptedWhileSignedOut = false
            UsageMonitorService.start(this)
            updatePrimaryButton()
            refresh()
        } else {
            if (wasSignedIn) {
                wasSignedIn = false
                loginPromptedWhileSignedOut = false
            }
            updatePrimaryButton()
            render(repo.state.value)
        }
    }

    /** Prefer detected SuperGrok / SuperGrok Heavy; otherwise app name (never assume SuperGrok). */
    private fun displayTitle(tierName: String?): String {
        val t = tierName?.trim().orEmpty()
        return when {
            t.contains("heavy", ignoreCase = true) ->
                getString(R.string.weekly_limit_title, "SuperGrok Heavy")
            t.equals("SuperGrok", ignoreCase = true) ||
                t.contains("supergrok", ignoreCase = true) ->
                getString(R.string.weekly_limit_title, "SuperGrok")
            t.isNotEmpty() -> getString(R.string.weekly_limit_title, t)
            else -> getString(R.string.app_name)
        }
    }

    /**
     * Opens grok.com usage / limits UI (not just the chat home).
     * Prefer in-browser / app handling of the deep link; fall back to plain grok.com.
     */
    private fun openGrokUsage() {
        val urls = listOf(
            "https://grok.com/?_s=usage",
            "https://grok.com/"
        )
        for (u in urls) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
                return
            } catch (_: Exception) { }
        }
    }

    private fun maybeAskNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!ok) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        progress.visibility = View.VISIBLE
        lifecycleScope.launch {
            val state = repo.refresh()
            // Record daily history like macOS
            state.usage?.let { u ->
                if (u.weeklyUsageAvailable) {
                    history.record(
                        usedPercent = u.usedPercent ?: 0,
                        periodStart = u.currentPeriod?.start,
                        productUsage = u.productUsage
                    )
                }
            }
            progress.visibility = View.GONE
            render(state)
            if (state.signedIn) UsageMonitorService.start(this@MainActivity)
        }
    }

    private fun render(state: UsageRepository.UsageUiState) {
        val u = state.usage
        title.text = displayTitle(u?.tierName)

        if (!state.signedIn) {
            summary.text = getString(R.string.not_signed_in)
            status.text = getString(R.string.sign_in_hint)
            products.removeAllViews()
            paintBar(emptyList(), 0, emptyMap())
            weekChart.setBars(emptyList())
            weekLabel.text = ""
            updatePrimaryButton()
            return
        }
        updatePrimaryButton()

        if (u != null) {
            val used = u.usedPercent ?: 0
            val rem = u.remainingPercent ?: (100 - used)
            summary.text = if (repo.showUsedPercent()) {
                getString(R.string.summary_used_first, used, rem)
            } else {
                getString(R.string.summary_rem_first, rem, used)
            }
            // Live products + any ids from imported/sampled history (0% if not used today)
            val list = mergeCategoriesWithHistory(u.productUsage)
            val palette = NotifPalette.indexMapFromUsageAndHistory(
                list, history.knownProductIds()
            )
            // Bar only colors positive segments; legend shows full set
            paintBar(list.filter { it.usagePercent > 0 }, used, palette)
            products.removeAllViews()
            if (list.isEmpty()) {
                val t = TextView(this)
                t.text = getString(R.string.no_categories)
                t.setTextColor(0xFFAAAAAA.toInt())
                products.addView(t)
            } else {
                for (p in list) {
                    val chip = layoutInflater.inflate(R.layout.item_product_chip, products, false)
                    chip.findViewById<View>(R.id.dot).background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(NotifPalette.argb(p.product, palette))
                    }
                    chip.findViewById<TextView>(R.id.name).text = p.name
                    chip.findViewById<TextView>(R.id.pct).text = "${p.usagePercent}%"
                    products.addView(chip)
                }
            }
            val end = u.currentPeriod?.end
            status.text = buildString {
                if (end != null) append(getString(R.string.resets, end.take(16)))
                if (u.cached) {
                    if (isNotEmpty()) append(" · ")
                    append(getString(R.string.cached))
                }
                if (state.error != null) {
                    if (isNotEmpty()) append("\n")
                    append(state.error)
                }
            }
            updateWeekChart()
        } else {
            summary.text = state.error ?: getString(R.string.notif_loading)
            status.text = state.error ?: ""
            products.removeAllViews()
            paintBar(emptyList(), 0, emptyMap())
            updateWeekChart()
        }
    }

    /**
     * Category chips:
     * - Live products with usage% > 0 (from REST)
     * - Plus any product ids seen in imported/sampled history (0% if not used today)
     * Does **not** invent categories from API rows that omit usagePercent.
     */
    private fun mergeCategoriesWithHistory(live: List<ProductUsage>): List<ProductUsage> {
        val byId = linkedMapOf<Int, ProductUsage>()
        // History first → 0% placeholders for categories used earlier this week/period
        for (id in history.knownProductIds().sorted()) {
            byId[id] = ProductUsage(
                product = id,
                name = ProductColors.displayName(id),
                usagePercent = 0
            )
        }
        // Live non-zero overwrites / adds
        for (p in live) {
            if (p.usagePercent <= 0) continue
            val name = p.name.ifBlank { ProductColors.displayName(p.product) }
            byId[p.product] = ProductUsage(p.product, name, p.usagePercent.coerceIn(0, 100))
        }
        return byId.values.sortedWith(
            compareByDescending<ProductUsage> { it.usagePercent }.thenBy { it.product }
        )
    }

    /**
     * How many weeks to show based on usable width (tablet / unfolded foldable).
     * Cover phone: 1 · unfolded fold / small tablet (≥560dp): 2 · wide (≥800dp): 3
     * Pixel Fold inner (~850dp) → 3 weeks; cover (~440dp) → 1 week.
     */
    private fun weeksToShow(): Int {
        val widthDp = resources.configuration.screenWidthDp
        // Also consider the chart's actual pixel width once laid out
        val chartW = weekChart.width
        val density = resources.displayMetrics.density
        val chartDp = if (chartW > 0) (chartW / density).toInt() else widthDp
        val w = maxOf(widthDp, chartDp)
        return when {
            w >= 800 -> 3
            w >= 560 -> 2
            else -> 1
        }
    }

    private fun minWindowOffset(): Int {
        // Keep ~1 year of history navigable regardless of window size
        val weeks = weeksToShow()
        return -(52 / weeks).coerceAtLeast(8)
    }

    private fun updateWeekChart() {
        val weeks = weeksToShow()
        val dayCount = weeks * 7
        val stepDays = dayCount
        val endKey = DailyHistoryStore.addDays(
            DailyHistoryStore.dayKey(),
            windowOffset * stepDays
        ) ?: DailyHistoryStore.dayKey()
        val startKey = DailyHistoryStore.addDays(endKey, -(dayCount - 1)) ?: endKey
        val bars = history.bars(endKey, dayCount)
        val weekPalette = NotifPalette.indexMap(
            bars.flatMap { b -> b.segments.map { it.product } } + history.knownProductIds()
        )
        weekChart.setBars(bars, weeks, weekPalette)
        val legend = findViewById<View>(R.id.weekChartLegend)
        val legendDot = findViewById<ImageView>(R.id.weekChartLegendDot)
        val hasPrevWeekReset = bars.any { it.isPreResetFragment && it.dayDelta > 0 }
        if (hasPrevWeekReset) {
            legend.visibility = View.VISIBLE
            // Dot color matches WeekChartView previous-week bar segments
            val density = resources.displayMetrics.density
            val dotPx = (10f * density).toInt().coerceIn(16, 40)
            legendDot.setImageBitmap(
                PercentIconFactory.categoryDotBitmap(
                    WeekChartView.PRE_RESET_COLOR,
                    dotPx
                )
            )
        } else {
            legend.visibility = View.GONE
        }
        val df = SimpleDateFormat("MMM d", Locale.US)
        val start = DailyHistoryStore.parseDayKey(startKey)
        val end = DailyHistoryStore.parseDayKey(endKey)
        weekLabel.text = if (start != null && end != null) {
            val range = "${df.format(start)} – ${df.format(end)}"
            if (weeks > 1) "$range · $weeks wks" else range
        } else {
            "$startKey – $endKey"
        }
        findViewById<ImageButton>(R.id.btnWeekNext).isEnabled = windowOffset < 0
        findViewById<ImageButton>(R.id.btnWeekPrev).isEnabled = windowOffset > minWindowOffset()
    }

    /**
     * Main-app usage bar — thicker stadium bar (not the thinner notif/widget bar).
     */
    private fun paintBar(
        list: List<ProductUsage>,
        usedFallback: Int,
        palette: Map<Int, Int>
    ) {
        val positive = list.filter { it.usagePercent > 0 }.sortedBy { it.product }
        val usedHeadline = when {
            usedFallback > 0 -> usedFallback.coerceIn(0, 100)
            positive.isNotEmpty() -> positive.sumOf { it.usagePercent }.coerceIn(0, 100)
            else -> 0
        }
        // Same bitmap recipe as the home-screen widget (user prefers that look).
        val density = resources.displayMetrics.density
        val barW = resources.displayMetrics.widthPixels.coerceAtLeast(480)
        val barH = (32f * density).toInt().coerceIn(64, 160)
        usageBarImage.setImageBitmap(
            PercentIconFactory.usageBarBitmap(
                products = positive.sortedBy { it.product },
                usedPercent = usedHeadline,
                width = barW,
                height = barH,
                opaqueBackground = false,
                palette = palette
                // fullHeight=false — same thin stadium capsules as the widget
            )
        )
        findViewById<TextView>(R.id.barLabel).text = getString(R.string.bar_used, usedHeadline)
    }

    companion object {
        /** Set by LoginActivity when bringing the app back after Chrome auth. */
        const val EXTRA_LOGIN_JUST_FINISHED = "login_just_finished"
        private const val UI_PREFS = "grld_ui"
        private const val KEY_COMPACT_SUPPORT = "compact_support"
        private const val KEY_UPDATES_UNLOCKED = "updates_unlocked"
        private const val KEY_DISCLAIMER_OK = "disclaimer_accepted_v1"
        private const val AUTHOR_X_URL = "https://x.com/blankspeaker"
        private const val BAR_TAP_TARGET = 8
        private const val BAR_TAP_WINDOW_MS = 4_000L
    }
}

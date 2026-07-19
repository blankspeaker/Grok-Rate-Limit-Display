package com.blankspeaker.grld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import kotlin.math.ceil
import kotlin.math.max

/**
 * Live Update notification:
 *   Status pill: [gauge icon] 51%
 *   Title: Grok · 51% Used
 *   Categories: circle dots in fixed palette (no red):
 *     green → blue → purple → brown → orange → yellow → black
 *   Bar: multi-color ProgressStyle using the same palette by product id
 *
 * No custom RemoteViews on the Live Update path (kills the status pill).
 * Separate alert channel posts milestone / daily-goal notifications with sound.
 */
class UsageMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var loop: Job? = null
    private lateinit var repo: UsageRepository
    private lateinit var history: DailyHistoryStore

    override fun onCreate() {
        super.onCreate()
        repo = UsageRepository.get(this)
        history = DailyHistoryStore.get(this)
        createChannel()
        createAlertChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = buildNotification(repo.state.value)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
        if (loop == null) {
            loop = scope.launch {
                while (isActive) {
                    pollOnce()
                    delay(nextPollDelayMs())
                }
            }
        } else {
            scope.launch { pollOnce() }
        }
        return START_STICKY
    }

    private suspend fun pollOnce() {
        if (GrokAuth.isSignedIn(this@UsageMonitorService)) {
            val state = repo.refresh()
            recordHistory(state)
            updateNotification(state)
            maybeFireAlerts(state)
        } else {
            updateNotification(repo.state.value)
        }
        maybeAutoUpdate()
    }

    /**
     * Adaptive poll: weekly limits change slowly. Screen-off phones should not
     * hit the network every few minutes (major battery cost with FGS + radio).
     */
    private fun nextPollDelayMs(): Long {
        val interactive = try {
            getSystemService(PowerManager::class.java)?.isInteractive == true
        } catch (_: Exception) {
            true
        }
        return if (interactive) POLL_INTERACTIVE_MS else POLL_IDLE_MS
    }

    /** Once per day when secret unlock + auto-update are on. */
    private fun maybeAutoUpdate() {
        if (!AppUpdater.shouldAutoCheck(this)) return
        scope.launch(Dispatchers.IO) {
            try {
                AppUpdater.markChecked(this@UsageMonitorService)
                val release = AppUpdater.checkForUpdate(this@UsageMonitorService) ?: return@launch
                if (!AppUpdater.canInstallPackages(this@UsageMonitorService)) return@launch
                val apk = AppUpdater.downloadApk(this@UsageMonitorService, release)
                AppUpdater.installApk(this@UsageMonitorService, apk)
            } catch (_: Exception) { }
        }
    }

    private fun recordHistory(state: UsageRepository.UsageUiState) {
        val u = state.usage ?: return
        if (!u.weeklyUsageAvailable) return
        history.record(
            usedPercent = u.usedPercent ?: 0,
            periodStart = u.currentPeriod?.start,
            productUsage = u.productUsage
        )
    }

    override fun onDestroy() {
        loop?.cancel()
        loop = null
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        for (v in 3..28) {
            try { nm.deleteNotificationChannel("grld_usage_v$v") } catch (_: Exception) { }
        }
        for (id in listOf("grld_usage", "grld_usage_live")) {
            try { nm.deleteNotificationChannel(id) } catch (_: Exception) { }
        }
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = getString(R.string.notif_channel_desc)
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
        )
    }

    private fun createAlertChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        // Sound is baked into the channel — delete + recreate when user changes sound
        try { nm.deleteNotificationChannel(ALERT_CHANNEL_ID) } catch (_: Exception) { }
        val soundUri = resolveAlertSoundUri()
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_NOTIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        nm.createNotificationChannel(
            NotificationChannel(
                ALERT_CHANNEL_ID,
                getString(R.string.notif_channel_alerts),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.notif_channel_alerts_desc)
                setShowBadge(true)
                enableVibration(true)
                if (soundUri != null) {
                    setSound(soundUri, attrs)
                } else {
                    setSound(null, null)
                }
            }
        )
    }

    private fun resolveAlertSoundUri(): Uri? {
        val stored = repo.alertSoundUri()
        if (stored != null) {
            // Empty string means user picked Silent
            if (stored.isEmpty()) return null
            return runCatching { Uri.parse(stored) }.getOrNull()
        }
        return RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
    }

    /**
     * Milestone every X% and/or over linear daily pace.
     * Uses a separate non-ongoing notification so it can sound.
     */
    private fun maybeFireAlerts(state: UsageRepository.UsageUiState) {
        if (!repo.alertsEnabled()) return
        val u = state.usage ?: return
        if (!u.weeklyUsageAvailable) return
        val used = u.usedPercent ?: return

        // Week reset: used dropped — clear milestone tracker
        val lastMile = repo.lastAlertMilestone()
        if (used < lastMile - 2) {
            repo.setLastAlertMilestone(0)
            repo.setLastDailyGoalAlertDay(null)
        }

        val every = repo.alertEveryPercent()
        if (every > 0) {
            val crossed = (used / every) * every
            if (crossed > 0 && crossed > repo.lastAlertMilestone()) {
                repo.setLastAlertMilestone(crossed)
                postAlert(
                    getString(R.string.alert_milestone_title, crossed),
                    getString(R.string.alert_milestone_body, crossed)
                )
            }
        }

        if (repo.alertOverDailyGoal()) {
            val pace = dailyPaceInfo(u) ?: return
            if (used > pace.expectedUsed) {
                val dayKey = localDayKey()
                if (repo.lastDailyGoalAlertDay() != dayKey) {
                    repo.setLastDailyGoalAlertDay(dayKey)
                    postAlert(
                        getString(R.string.alert_daily_title),
                        getString(
                            R.string.alert_daily_body,
                            used,
                            pace.expectedUsed,
                            pace.remainingLabel
                        )
                    )
                }
            }
        }
    }

    private data class DailyPace(
        val expectedUsed: Int,
        val remainingLabel: String
    )

    /**
     * Linear pace: expected used% = elapsed / period × 100.
     * Remaining budget should scale with remaining time
     * (remaining% vs remaining fraction of the week).
     */
    private fun dailyPaceInfo(u: UsageResponse): DailyPace? {
        val startMs = parseIsoMs(u.currentPeriod?.start) ?: return null
        val endMs = parseIsoMs(u.currentPeriod?.end) ?: return null
        val now = System.currentTimeMillis()
        val total = (endMs - startMs).coerceAtLeast(1L)
        val elapsed = (now - startMs).coerceIn(0L, total)
        val remainingMs = (endMs - now).coerceAtLeast(0L)
        val expected = ((elapsed.toDouble() / total) * 100.0).toInt().coerceIn(0, 100)
        val daysLeft = remainingMs.toDouble() / TimeUnit.DAYS.toMillis(1)
        val remainingLabel = when {
            remainingMs < TimeUnit.HOURS.toMillis(1) ->
                "${max(1, TimeUnit.MILLISECONDS.toMinutes(remainingMs).toInt())}m"
            daysLeft < 1.0 ->
                "${max(1, ceil(remainingMs / TimeUnit.HOURS.toMillis(1).toDouble()).toInt())}h"
            daysLeft < 1.5 -> "1 day"
            else -> "${ceil(daysLeft).toInt()} days"
        }
        return DailyPace(expectedUsed = expected, remainingLabel = remainingLabel)
    }

    private fun parseIsoMs(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ssX"
        )
        for (p in patterns) {
            try {
                val df = SimpleDateFormat(p, Locale.US)
                df.timeZone = TimeZone.getTimeZone("UTC")
                return df.parse(iso)?.time
            } catch (_: Exception) { }
        }
        return null
    }

    private fun localDayKey(): String {
        val df = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return df.format(Date())
    }

    private fun postAlert(title: String, body: String) {
        // Ensure channel matches current sound preference
        createAlertChannel()
        val notif = NotificationCompat.Builder(this, ALERT_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(PercentIconFactory.logoIcon(this))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(contentPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_NOTIF_ID, notif)
    }

    /** Last remaining bucket used for notification smallIcon (force refresh on change). */
    private var lastNotifIconBucket: Int? = null

    private fun updateNotification(state: UsageRepository.UsageUiState) {
        val rem = state.usage?.remainingPercent
            ?: state.usage?.usedPercent?.let { (100 - it).coerceIn(0, 100) }
        val bucket = DynamicAppIcon.bucketFor(rem)
        val nm = getSystemService(NotificationManager::class.java)
        val notif = buildNotification(state)
        // When the 10% bucket changes, re-assert FGS so OEMs don't keep a cached smallIcon
        if (bucket != lastNotifIconBucket) {
            lastNotifIconBucket = bucket
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } else {
            nm.notify(NOTIF_ID, notif)
        }
        // Keep home-screen widgets in sync with the same data
        UsageWidgetProvider.updateAll(this, state)
        // Home-screen app icon needle (activity-alias swap, same 10% steps)
        DynamicAppIcon.update(this, rem)
    }

    private fun buildNotification(state: UsageRepository.UsageUiState): Notification {
        val wantPill = repo.showStatusPill()
        val wantTray = repo.showTrayNotification()
        if (!wantPill && !wantTray) {
            return buildMinimalNotification(state)
        }
        // Two mutually exclusive rich styles (platform limit on Android 16+):
        //
        // 1) Status pill ON → Live Update / promoted ongoing.
        //    Appears on Always On Display + lock screen + clock chip.
        //    Must NOT use custom RemoteViews (demotes promotion). Shade uses
        //    ProgressStyle segments when tray is also on.
        //
        // 2) Pill OFF + tray ON → custom RemoteViews thin multi-color bar.
        //    Best-looking shade bar; does not promote to AOD / clock chip.
        if (wantPill && Build.VERSION.SDK_INT >= 36) {
            buildPromotedNotification(state, includeTray = wantTray)?.let { return it }
        }
        if (wantTray) {
            return buildLegacyNotification(state)
        }
        // Pill on older OS (no Live Update) — still show a useful card
        return buildLegacyNotification(state)
    }

    /**
     * Android 16+ Live Update status chip (black pill + "%").
     *
     * Platform rule: Live Updates must use ProgressStyle/MetricStyle and must
     * **not** set customContentView — custom RemoteViews demotes the chip to a
     * tiny static icon. Tray detail uses ProgressStyle segments + title text.
     */
    private fun buildPromotedNotification(
        state: UsageRepository.UsageUiState,
        includeTray: Boolean
    ): Notification? {
        return try {
            val showUsed = repo.showUsedPercent()
            val used = state.usage?.usedPercent
            val rem = state.usage?.remainingPercent
            val pct = if (showUsed) used else rem
            // Always include reset time in the shade title
            val title = titleLine(state, showUsed, pct)

            val productsSorted = activeProducts(state)
            val productsBar = productsSorted.sortedBy { it.product }
            val palette = paletteFor(productsSorted)
            val usedVal = (used ?: 0).coerceIn(0, 100)
            val remaining = rem ?: used?.let { (100 - it).coerceIn(0, 100) }

            // Colored ● dots + labels (Live Update forbids custom RemoteViews;
            // custom views demote the status pill to a tiny static icon.)
            val categoryLine: CharSequence =
                if (includeTray) detailLineWithColoredDots(productsSorted, palette)
                else getString(R.string.notif_tap_open)

            val builder = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(categoryLine)
                .setSmallIcon(statusGaugeIcon(remaining))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(contentPendingIntent())
                .setCategory(Notification.CATEGORY_STATUS)
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setColor(Color.TRANSPARENT)
                .setShowWhen(false)
                .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)

            // ProgressStyle: multi-color when tray detail is on; solid used% bar
            // otherwise. Helps Live Update stay eligible and gives shade a bar.
            // Do NOT set customContentView — that demotes the status pill / AOD.
            tryApplyProgressStyle(builder, if (includeTray) productsBar else emptyList(), usedVal, palette)

            // Live Update / status chip APIs (API 36+) — AOD + lock screen + clock
            val bCls = Notification.Builder::class.java
            bCls.getMethod("setRequestPromotedOngoing", Boolean::class.javaPrimitiveType)
                .invoke(builder, true)
            if (pct != null) {
                bCls.getMethod("setShortCriticalText", String::class.java)
                    .invoke(builder, "${pct}%")
            }

            builder.build()
        } catch (_: Exception) {
            null
        }
    }

    private fun buildMinimalNotification(state: UsageRepository.UsageUiState): Notification {
        val showUsed = repo.showUsedPercent()
        val used = state.usage?.usedPercent
        val pct = if (showUsed) used else state.usage?.remainingPercent
        val title = titleLine(state, showUsed, pct)
        val remaining = state.usage?.remainingPercent
            ?: used?.let { (100 - it).coerceIn(0, 100) }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(getString(R.string.notif_monitoring_quiet))
            .setSmallIcon(DynamicAppIcon.notificationIconRes(remaining))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun activeProducts(state: UsageRepository.UsageUiState): List<ProductUsage> {
        return state.usage?.productUsage
            ?.filter { it.usagePercent > 0 }
            ?.sortedByDescending { it.usagePercent }
            ?.take(ProductColors.NOTIF_SLOT_COUNT)
            .orEmpty()
    }

    /** Palette ranks include history product ids so colors match the main app after import. */
    private fun paletteFor(products: List<ProductUsage>): Map<Int, Int> =
        NotifPalette.indexMapFromUsageAndHistory(products, history.knownProductIds())

    /** Emoji dots + labels (legacy). */
    private fun detailLineWithDots(
        products: List<ProductUsage>,
        palette: Map<Int, Int>
    ): CharSequence {
        if (products.isEmpty()) return getString(R.string.notif_tap_open)
        return products.joinToString(" · ") { p ->
            val name = p.name.ifBlank { ProductColors.displayName(p.product) }
            "${NotifPalette.emoji(p.product, palette)} $name ${p.usagePercent}%"
        }
    }

    /**
     * Colored category dots for Live Update shade text.
     *
     * Android notification templates force a single text color, so
     * ForegroundColorSpan on "●" is wiped to white. Emoji dots keep their
     * colors and match [NotifPalette] rank (same order as ProgressStyle segments).
     */
    private fun detailLineWithColoredDots(
        products: List<ProductUsage>,
        palette: Map<Int, Int>
    ): CharSequence {
        if (products.isEmpty()) return getString(R.string.notif_tap_open)
        return products.joinToString("  ") { p ->
            val name = p.name.ifBlank { ProductColors.displayName(p.product) }
            "${NotifPalette.emoji(p.product, palette)} $name ${p.usagePercent}%"
        }
    }

    /**
     * ProgressStyle segments must sum to 100 and the colored portion must equal
     * [used]. Product usage %s are per-category and often sum to more (or less)
     * than headline used% — using them raw causes a mid-segment cut that looks
     * like an unexplained extra color (e.g. muted blue between Build and Chat).
     */
    private fun tryApplyProgressStyle(
        builder: Notification.Builder,
        products: List<ProductUsage>,
        used: Int,
        palette: Map<Int, Int>
    ): Boolean {
        return try {
            val styleCls = Class.forName("android.app.Notification\$ProgressStyle")
            val segCls = Class.forName("android.app.Notification\$ProgressStyle\$Segment")
            val style = styleCls.getDeclaredConstructor().newInstance()
            val addSeg = styleCls.getMethod("addProgressSegment", segCls)
            val setProgress = styleCls.getMethod("setProgress", Int::class.javaPrimitiveType)
            val setStyled = styleCls.getMethod("setStyledByProgress", Boolean::class.javaPrimitiveType)
            val setColor = segCls.getMethod("setColor", Int::class.javaPrimitiveType)
            val segCtor = segCls.getConstructor(Int::class.javaPrimitiveType)

            fun addSegment(length: Int, color: Int) {
                if (length <= 0) return
                val seg = segCtor.newInstance(length)
                setColor.invoke(seg, color)
                addSeg.invoke(style, seg)
            }

            val usedC = used.coerceIn(0, 100)
            val positive = products.filter { it.usagePercent > 0 }.sortedBy { it.product }
            if (usedC <= 0) {
                addSegment(100, 0xFF444444.toInt())
            } else if (positive.isEmpty()) {
                addSegment(usedC, NotifPalette.defaultArgb)
                addSegment(100 - usedC, 0xFF444444.toInt())
            } else {
                // Scale product weights so colored segments sum exactly to usedC
                val lengths = proportionalLengths(positive.map { it.usagePercent }, usedC)
                positive.forEachIndexed { i, p ->
                    addSegment(lengths[i], NotifPalette.argb(p.product, palette))
                }
                addSegment(100 - usedC, 0xFF444444.toInt())
            }

            // Segments already encode used vs remaining — don't gray mid-segment
            setProgress.invoke(style, usedC)
            setStyled.invoke(style, false)
            try {
                styleCls.getMethod("setProgressMax", Int::class.javaPrimitiveType)
                    .invoke(style, 100)
            } catch (_: Exception) { }
            Notification.Builder::class.java
                .getMethod("setStyle", Notification.Style::class.java)
                .invoke(builder, style)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Largest-remainder allocation: map positive weights → ints that sum to [total].
     * Guarantees every positive weight gets ≥1 when total ≥ count.
     */
    private fun proportionalLengths(weights: List<Int>, total: Int): IntArray {
        val n = weights.size
        if (n == 0 || total <= 0) return IntArray(n)
        val w = weights.map { it.coerceAtLeast(0) }
        val sumW = w.sum().coerceAtLeast(1).toDouble()
        if (total < n) {
            // Too little room for every product — give to largest weights first
            val out = IntArray(n)
            val order = w.indices.sortedByDescending { w[it] }
            for (i in 0 until total) out[order[i % n]]++
            return out
        }
        val exact = DoubleArray(n) { total * w[it] / sumW }
        val out = IntArray(n) { exact[it].toInt().coerceAtLeast(1) }
        var allocated = out.sum()
        // If mins overshot, shave from largest segments (keep ≥1)
        if (allocated > total) {
            val order = out.indices.sortedByDescending { out[it] }
            var i = 0
            while (allocated > total) {
                val idx = order[i % n]
                if (out[idx] > 1) {
                    out[idx]--
                    allocated--
                }
                i++
                if (i > n * (allocated - total + 2)) break
            }
        }
        // Distribute remainder by largest fractional parts
        if (allocated < total) {
            val order = exact.indices.sortedByDescending { exact[it] - exact[it].toInt() }
            var i = 0
            while (allocated < total) {
                out[order[i % n]]++
                allocated++
                i++
            }
        }
        return out
    }

    private fun buildLegacyNotification(state: UsageRepository.UsageUiState): Notification {
        val showUsed = repo.showUsedPercent()
        val used = state.usage?.usedPercent
        val pct = if (showUsed) used else state.usage?.remainingPercent
        val title = titleLine(state, showUsed, pct)
        val usedVal = (used ?: 0).coerceIn(0, 100)
        val productsSorted = activeProducts(state)
        val productsBar = productsSorted.sortedBy { it.product }
        val palette = paletteFor(productsSorted)
        val remaining = state.usage?.remainingPercent
            ?: used?.let { (100 - it).coerceIn(0, 100) }
        // Compact + expanded both show full title with reset (fits 2 lines).
        val compact = buildCategoryRemoteViews(
            title, productsSorted, productsBar, usedVal, palette, expanded = false
        )
        val big = buildCategoryRemoteViews(
            title, productsSorted, productsBar, usedVal, palette, expanded = true
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(detailLinePlain(productsSorted))
            .setSmallIcon(DynamicAppIcon.notificationIconRes(remaining))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentPendingIntent())
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setColor(Color.TRANSPARENT)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCustomContentView(compact)
            .setCustomBigContentView(big)
            .build()
    }

    /** Status-bar / promoted path: same 10% stepped resource icons. */
    private fun statusGaugeIcon(remaining: Int?): android.graphics.drawable.Icon {
        return android.graphics.drawable.Icon.createWithResource(
            this,
            DynamicAppIcon.notificationIconRes(remaining)
        )
    }

    /**
     * Tray body.
     * Compact (collapsed): title + thin bar only — no category dots (they clip when shrunk).
     * Expanded (chevron): title + bar + category dots/labels wrapping.
     */
    private fun buildCategoryRemoteViews(
        headline: String,
        productsForLabels: List<ProductUsage>,
        productsForBar: List<ProductUsage>,
        usedVal: Int,
        palette: Map<Int, Int>,
        expanded: Boolean
    ): RemoteViews {
        val layout = if (expanded) R.layout.notification_usage_big else R.layout.notification_usage
        val views = RemoteViews(packageName, layout)
        views.setTextViewText(R.id.notif_headline, headline)

        // Thin rounded-segment bar in both states
        views.setImageViewBitmap(
            R.id.notif_bar,
            PercentIconFactory.usageBarBitmap(
                productsForBar,
                usedVal,
                width = 960,
                height = 64,
                palette = palette
            )
        )

        // Category dots only on the expanded layout — never in collapsed row
        if (!expanded) return views

        val density = resources.displayMetrics.density
        val dotPx = (16f * density).toInt().coerceIn(32, 64)
        val slots = listOf(
            Triple(R.id.cat0, R.id.cat0_dot, R.id.cat0_label),
            Triple(R.id.cat1, R.id.cat1_dot, R.id.cat1_label),
            Triple(R.id.cat2, R.id.cat2_dot, R.id.cat2_label),
            Triple(R.id.cat3, R.id.cat3_dot, R.id.cat3_label),
            Triple(R.id.cat4, R.id.cat4_dot, R.id.cat4_label),
            Triple(R.id.cat5, R.id.cat5_dot, R.id.cat5_label),
            Triple(R.id.cat6, R.id.cat6_dot, R.id.cat6_label),
            Triple(R.id.cat7, R.id.cat7_dot, R.id.cat7_label)
        )
        slots.forEachIndexed { i, (rowId, dotId, labelId) ->
            val p = productsForLabels.getOrNull(i)
            if (p == null) {
                views.setViewVisibility(rowId, android.view.View.GONE)
            } else {
                views.setViewVisibility(rowId, android.view.View.VISIBLE)
                views.setImageViewBitmap(
                    dotId,
                    PercentIconFactory.categoryDotBitmap(
                        NotifPalette.argb(p.product, palette),
                        dotPx
                    )
                )
                val name = p.name.ifBlank { ProductColors.displayName(p.product) }
                views.setTextViewText(labelId, "$name ${p.usagePercent}%")
            }
        }
        views.setViewVisibility(
            R.id.cat_row1,
            if (productsForLabels.size > 4) android.view.View.VISIBLE
            else android.view.View.GONE
        )
        return views
    }

    private fun contentPendingIntent(): PendingIntent {
        val open = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this, 0, open,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Tray / Live Update title: "62% Used · Resets Thu 2:25 PM" */
    private fun titleLine(
        state: UsageRepository.UsageUiState,
        showUsed: Boolean,
        pct: Int?
    ): String {
        return UsageFormatting.usageHeadline(
            usedPercent = state.usage?.usedPercent,
            remainingPercent = state.usage?.remainingPercent ?: pct,
            showUsed = showUsed,
            periodEndIso = state.usage?.currentPeriod?.end,
            signedIn = state.signedIn,
            notSignedInLabel = getString(R.string.notif_sign_in),
            fallbackLabel = getString(R.string.app_name)
        )
    }

    private fun detailLinePlain(products: List<ProductUsage>): String {
        if (products.isEmpty()) return getString(R.string.notif_tap_open)
        return products.joinToString(" · ") {
            val name = it.name.ifBlank { ProductColors.displayName(it.product) }
            "$name ${it.usagePercent}%"
        }
    }

    companion object {
        const val CHANNEL_ID = "grld_usage_v29"
        const val ALERT_CHANNEL_ID = "grld_alerts_v1"
        const val NOTIF_ID = 69
        const val ALERT_NOTIF_ID = 70
        /** While screen on: still conservative — limits change slowly over a week. */
        private const val POLL_INTERACTIVE_MS = 15 * 60 * 1000L
        /** Screen off / doze: much rarer network + notification rebuilds. */
        private const val POLL_IDLE_MS = 30 * 60 * 1000L

        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, UsageMonitorService::class.java))
            } catch (_: Exception) {
                // Screen-off / background start restrictions (Android 12+) — retry from UI later.
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, UsageMonitorService::class.java))
            } catch (_: Exception) { }
        }

        /** Rebuild notification after display toggles change. */
        fun refreshNow(context: Context) {
            start(context)
        }

        /** Delete + recreate alert channel so a new sound takes effect. */
        fun recreateAlertChannel(context: Context) {
            val app = context.applicationContext
            try {
                val nm = app.getSystemService(NotificationManager::class.java)
                try { nm.deleteNotificationChannel(ALERT_CHANNEL_ID) } catch (_: Exception) { }
                val repo = UsageRepository.get(app)
                val stored = repo.alertSoundUri()
                val soundUri = when {
                    stored == null -> RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    stored.isEmpty() -> null // silent
                    else -> runCatching { Uri.parse(stored) }.getOrNull()
                }
                val attrs = AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
                nm.createNotificationChannel(
                    NotificationChannel(
                        ALERT_CHANNEL_ID,
                        app.getString(R.string.notif_channel_alerts),
                        NotificationManager.IMPORTANCE_HIGH
                    ).apply {
                        description = app.getString(R.string.notif_channel_alerts_desc)
                        setShowBadge(true)
                        enableVibration(true)
                        if (soundUri != null) setSound(soundUri, attrs)
                        else setSound(null, null)
                    }
                )
            } catch (_: Exception) {
                if (GrokAuth.isSignedIn(app)) start(app)
            }
        }

        fun openLiveUpdateSettings(context: Context) {
            try {
                val intent = Intent("android.settings.MANAGE_APP_PROMOTED_NOTIFICATIONS").apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (intent.resolveActivity(context.packageManager) != null) {
                    context.startActivity(intent)
                    return
                }
            } catch (_: Exception) { }
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
    }
}


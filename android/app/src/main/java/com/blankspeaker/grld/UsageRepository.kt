package com.blankspeaker.grld

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory + SharedPreferences last usage for the notification service & UI. */
class UsageRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("grld_usage", Context.MODE_PRIVATE)
    private val client = GrokClient(app)

    private val _state = MutableStateFlow(readCached())
    val state: StateFlow<UsageUiState> = _state.asStateFlow()

    data class UsageUiState(
        val usage: UsageResponse? = null,
        val error: String? = null,
        val loading: Boolean = false,
        val signedIn: Boolean = false
    )

    suspend fun refresh(): UsageUiState {
        _state.value = _state.value.copy(loading = true, error = null, signedIn = GrokAuth.isSignedIn(app))
        return try {
            val usage = client.fetch()
            cache(usage)
            val s = UsageUiState(usage = usage, error = null, loading = false, signedIn = true)
            _state.value = s
            s
        } catch (e: Exception) {
            val freePlan = e is GrokClient.ClientError.FreePlan
            val s = UsageUiState(
                // Free plan has no usable weekly limits — never keep stale SuperGrok cache.
                usage = if (freePlan) null else _state.value.usage,
                error = e.message,
                loading = false,
                signedIn = GrokAuth.isSignedIn(app)
            )
            _state.value = s
            s
        }
    }

    fun displayPercent(showUsed: Boolean = true): Int? {
        val u = _state.value.usage ?: return null
        return if (showUsed) u.usedPercent else u.remainingPercent
    }

    fun showUsedPercent(): Boolean =
        prefs.getBoolean("show_used", true)

    fun setShowUsedPercent(v: Boolean) {
        prefs.edit().putBoolean("show_used", v).apply()
    }

    /** Status-bar Live Update pill (promoted ongoing + shortCriticalText). */
    fun showStatusPill(): Boolean = prefs.getBoolean(KEY_STATUS_PILL, true)
    fun setShowStatusPill(v: Boolean) {
        prefs.edit().putBoolean(KEY_STATUS_PILL, v).apply()
    }

    /** Expanded notification tray content (bar + category legend). */
    fun showTrayNotification(): Boolean = prefs.getBoolean(KEY_TRAY, true)
    fun setShowTrayNotification(v: Boolean) {
        prefs.edit().putBoolean(KEY_TRAY, v).apply()
    }

    // --- Alert notifications ---

    fun alertsEnabled(): Boolean = prefs.getBoolean(KEY_ALERTS, false)
    fun setAlertsEnabled(v: Boolean) {
        prefs.edit().putBoolean(KEY_ALERTS, v).apply()
    }

    /** 0 = disabled; else fire when used% crosses each multiple of this value. */
    fun alertEveryPercent(): Int = prefs.getInt(KEY_ALERT_EVERY, 10)
    fun setAlertEveryPercent(v: Int) {
        prefs.edit().putInt(KEY_ALERT_EVERY, v.coerceIn(0, 50)).apply()
    }

    fun alertOverDailyGoal(): Boolean = prefs.getBoolean(KEY_ALERT_DAILY, true)
    fun setAlertOverDailyGoal(v: Boolean) {
        prefs.edit().putBoolean(KEY_ALERT_DAILY, v).apply()
    }

    /**
     * Alert sound URI string.
     * null = system default notification sound;
     * empty = silent;
     * otherwise a content/file URI from the ringtone picker.
     */
    fun alertSoundUri(): String? =
        if (!prefs.contains(KEY_ALERT_SOUND)) null
        else prefs.getString(KEY_ALERT_SOUND, null) ?: ""

    fun setAlertSoundUri(uri: String?) {
        if (uri == null) {
            prefs.edit().remove(KEY_ALERT_SOUND).apply()
        } else {
            prefs.edit().putString(KEY_ALERT_SOUND, uri).apply()
        }
    }

    fun lastAlertMilestone(): Int = prefs.getInt(KEY_ALERT_MILESTONE, 0)
    fun setLastAlertMilestone(v: Int) {
        prefs.edit().putInt(KEY_ALERT_MILESTONE, v).apply()
    }

    fun lastDailyGoalAlertDay(): String? = prefs.getString(KEY_ALERT_DAILY_DAY, null)
    fun setLastDailyGoalAlertDay(day: String?) {
        prefs.edit().putString(KEY_ALERT_DAILY_DAY, day).apply()
    }

    private fun cache(u: UsageResponse) {
        prefs.edit()
            .putInt("used", u.usedPercent ?: -1)
            .putInt("remaining", u.remainingPercent ?: -1)
            .putString("tier", u.tierName)
            .putString("fetched", u.fetchedAt)
            // Persist period so widget / tray can show "Resets …" offline
            .putString("period_start", u.currentPeriod?.start)
            .putString("period_end", u.currentPeriod?.end)
            .apply()
    }

    private fun readCached(): UsageUiState {
        val used = prefs.getInt("used", -1)
        if (used < 0) return UsageUiState(signedIn = GrokAuth.isSignedIn(app))
        val rem = prefs.getInt("remaining", 100 - used)
        val pStart = prefs.getString("period_start", null)
        val pEnd = prefs.getString("period_end", null)
        val period = if (pStart != null || pEnd != null) {
            UsagePeriod("weekly", pStart, pEnd)
        } else null
        return UsageUiState(
            usage = UsageResponse(
                ok = true,
                remainingPercent = rem,
                usedPercent = used,
                weeklyUsageAvailable = true,
                tierName = prefs.getString("tier", null),
                currentPeriod = period,
                productUsage = emptyList(),
                fetchedAt = prefs.getString("fetched", "") ?: "",
                cached = true
            ),
            signedIn = GrokAuth.isSignedIn(app)
        )
    }

    companion object {
        private const val KEY_STATUS_PILL = "show_status_pill"
        private const val KEY_TRAY = "show_tray_notification"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_ALERT_EVERY = "alert_every_percent"
        private const val KEY_ALERT_DAILY = "alert_over_daily_goal"
        private const val KEY_ALERT_SOUND = "alert_sound_uri"
        private const val KEY_ALERT_MILESTONE = "alert_last_milestone"
        private const val KEY_ALERT_DAILY_DAY = "alert_last_daily_day"

        @Volatile private var instance: UsageRepository? = null
        fun get(context: Context): UsageRepository =
            instance ?: synchronized(this) {
                instance ?: UsageRepository(context.applicationContext).also { instance = it }
            }
    }
}

package com.blankspeaker.grld

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

/**
 * Restarts the usage monitor after reboot / app update / unlock.
 *
 * Plain BOOT_COMPLETED alone is flaky on modern Android (FGS start can fail
 * briefly after boot, package updates don't fire BOOT_COMPLETED, and some
 * devices use QUICKBOOT). We listen to several intents and schedule short
 * delayed retries so the notification comes back without opening the UI.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in START_ACTIONS) return

        val app = context.applicationContext
        if (!GrokAuth.isSignedIn(app)) {
            Log.i(TAG, "skip $action — not signed in")
            return
        }

        val pending = goAsync()
        try {
            Log.i(TAG, "start monitor from $action")
            UsageMonitorService.start(app)
            // Boot is noisy: FGS start sometimes fails for ~1–2 min. Retry a few times.
            if (action != ACTION_RETRY_START) {
                scheduleRetry(app, delayMs = 30_000L, requestCode = REQ_RETRY_30S)
                scheduleRetry(app, delayMs = 120_000L, requestCode = REQ_RETRY_2M)
            }
        } catch (e: Exception) {
            Log.w(TAG, "boot start failed ($action)", e)
            scheduleRetry(app, delayMs = 60_000L, requestCode = REQ_RETRY_FAIL)
        } finally {
            pending.finish()
        }
    }

    companion object {
        private const val TAG = "GRLD-Boot"
        const val ACTION_RETRY_START = "com.blankspeaker.grld.RETRY_START_MONITOR"
        private const val REQ_RETRY_30S = 7101
        private const val REQ_RETRY_2M = 7102
        private const val REQ_RETRY_FAIL = 7103

        private val START_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON",
            ACTION_RETRY_START
        )

        fun scheduleRetry(context: Context, delayMs: Long, requestCode: Int = REQ_RETRY_FAIL) {
            try {
                val app = context.applicationContext
                val intent = Intent(app, BootReceiver::class.java).setAction(ACTION_RETRY_START)
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val pi = PendingIntent.getBroadcast(app, requestCode, intent, flags)
                val am = app.getSystemService(AlarmManager::class.java) ?: return
                val whenElapsed = SystemClock.elapsedRealtime() + delayMs.coerceAtLeast(5_000L)
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, whenElapsed, pi)
                Log.i(TAG, "scheduled retry in ${delayMs}ms (code=$requestCode)")
            } catch (e: Exception) {
                Log.w(TAG, "scheduleRetry failed", e)
            }
        }
    }
}

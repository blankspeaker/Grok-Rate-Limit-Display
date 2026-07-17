package com.blankspeaker.grld

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Polls device-code token endpoint while the user is in Chrome.
 * Runs as a short FGS so the OS does not freeze network when LoginActivity is stopped.
 * On success: saves tokens, brings MainActivity to the front (closes Custom Tab).
 */
class LoginPollService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            stopSelfSafe()
            return START_NOT_STICKY
        }

        val deviceCode = intent?.getStringExtra(EXTRA_DEVICE_CODE)
        val userCode = intent?.getStringExtra(EXTRA_USER_CODE).orEmpty()
        val verificationUri = intent?.getStringExtra(EXTRA_VERIFICATION_URI).orEmpty()
        val verificationComplete = intent?.getStringExtra(EXTRA_VERIFICATION_COMPLETE)
        val intervalSec = intent?.getIntExtra(EXTRA_INTERVAL, 5) ?: 5
        val expiresInSec = intent?.getLongExtra(EXTRA_EXPIRES, 1800L) ?: 1800L

        if (deviceCode.isNullOrBlank()) {
            Log.e(TAG, "missing device_code")
            stopSelfSafe()
            return START_NOT_STICKY
        }

        val pending = DeviceAuth.Pending(
            deviceCode = deviceCode,
            userCode = userCode,
            verificationUri = verificationUri,
            verificationUriComplete = verificationComplete,
            intervalSec = intervalSec,
            expiresInSec = expiresInSec
        )

        startAsForeground(userCode)

        if (job?.isActive == true) {
            Log.i(TAG, "poll already running")
            return START_STICKY
        }

        job = scope.launch {
            pollUntilDone(pending)
        }
        return START_STICKY
    }

    private suspend fun pollUntilDone(pending: DeviceAuth.Pending) {
        var intervalMs = pending.intervalSec.coerceAtLeast(2) * 1000L
        val deadline = System.currentTimeMillis() + pending.expiresInSec * 1000L
        Log.i(TAG, "poll start user=${pending.userCode} interval=${intervalMs}ms")

        // First wait — immediate poll is always pending
        delay(intervalMs)

        while (true) {
            coroutineContext.ensureActive()
            if (System.currentTimeMillis() > deadline) {
                Log.w(TAG, "poll expired")
                notifyFailure(getString(R.string.login_browser_expired))
                stopSelfSafe()
                return
            }

            val result = withContext(Dispatchers.IO) {
                try {
                    DeviceAuth.pollOnce(pending)
                } catch (e: Exception) {
                    Log.w(TAG, "poll error", e)
                    DeviceAuth.PollResult.Error(e.message ?: "network")
                }
            }

            when (result) {
                is DeviceAuth.PollResult.Success -> {
                    Log.i(TAG, "poll SUCCESS — saving tokens and returning to app")
                    onSuccess(result.tokens)
                    return
                }
                DeviceAuth.PollResult.PendingAuth -> {
                    Log.i(TAG, "authorization_pending")
                }
                DeviceAuth.PollResult.SlowDown -> {
                    intervalMs += 5_000L
                    Log.i(TAG, "slow_down → interval=${intervalMs}ms")
                }
                is DeviceAuth.PollResult.Denied -> {
                    Log.w(TAG, "denied: ${result.message}")
                    notifyFailure(result.message)
                    stopSelfSafe()
                    return
                }
                is DeviceAuth.PollResult.Expired -> {
                    Log.w(TAG, "expired: ${result.message}")
                    notifyFailure(getString(R.string.login_browser_expired))
                    stopSelfSafe()
                    return
                }
                is DeviceAuth.PollResult.Error -> {
                    Log.w(TAG, "error: ${result.message}")
                    // keep trying a bit
                }
            }
            delay(intervalMs)
        }
    }

    private fun onSuccess(tokens: OidcSession.Tokens) {
        OidcSession.save(
            applicationContext,
            accessToken = tokens.accessToken,
            refreshToken = tokens.refreshToken,
            expiresInSec = ((tokens.expiresAtMs - System.currentTimeMillis()) / 1000L)
                .coerceAtLeast(60L),
            userId = tokens.userId,
            email = tokens.email
        )
        UsageMonitorService.start(applicationContext)

        // Tell LoginActivity (if alive) to finish without double-save
        sendBroadcast(
            Intent(ACTION_LOGIN_SUCCESS).setPackage(packageName)
        )

        bringMainToFront()
        // Keep a brief "signed in" heads-up in case BAL blocks startActivity
        notifySignedIn()
        stopSelfSafe()
    }

    private fun bringMainToFront() {
        val home = Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP or
                Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
            putExtra(MainActivity.EXTRA_LOGIN_JUST_FINISHED, true)
        }
        try {
            val am = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            am.appTasks
                .firstOrNull {
                    it.taskInfo.baseActivity?.packageName == packageName ||
                        it.taskInfo.topActivity?.packageName == packageName
                }
                ?.moveToFront()
        } catch (e: Exception) {
            Log.w(TAG, "moveToFront", e)
        }
        try {
            startActivity(home)
            Log.i(TAG, "startActivity MainActivity")
        } catch (e: Exception) {
            Log.w(TAG, "startActivity blocked", e)
        }
    }

    private fun startAsForeground(userCode: String) {
        ensureChannel()
        val openLogin = PendingIntent.getActivity(
            this, 0,
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(PercentIconFactory.logoIcon(this))
            .setContentTitle(getString(R.string.login_browser_waiting))
            .setContentText(
                if (userCode.isNotBlank()) getString(R.string.login_poll_code, userCode)
                else getString(R.string.login_browser_waiting)
            )
            .setContentIntent(openLogin)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        // dataSync — same type as UsageMonitorService (already permitted)
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun notifySignedIn() {
        ensureChannel()
        val open = PendingIntent.getActivity(
            this, 71,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_LOGIN_JUST_FINISHED, true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(PercentIconFactory.logoIcon(this))
            .setContentTitle(getString(R.string.login_ok))
            .setContentText(getString(R.string.login_return_tap))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_DONE, notif)
    }

    private fun notifyFailure(msg: String) {
        ensureChannel()
        val notif = NotificationCompat.Builder(this, CHANNEL_DONE)
            .setSmallIcon(PercentIconFactory.logoIcon(this))
            .setContentTitle(getString(R.string.sign_in))
            .setContentText(msg)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIF_DONE, notif)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL,
                getString(R.string.login_poll_channel),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.login_poll_channel_desc)
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_DONE,
                getString(R.string.login_return_channel),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = getString(R.string.login_return_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun stopSelfSafe() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) { }
        stopSelf()
    }

    override fun onDestroy() {
        job?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "GRLD-LoginPoll"
        private const val CHANNEL = "grld_login_poll_v1"
        private const val CHANNEL_DONE = "grld_login_v1"
        private const val NOTIF_ID = 73
        private const val NOTIF_DONE = 72

        const val ACTION_CANCEL = "com.blankspeaker.grld.LOGIN_POLL_CANCEL"
        const val ACTION_LOGIN_SUCCESS = "com.blankspeaker.grld.LOGIN_SUCCESS"

        private const val EXTRA_DEVICE_CODE = "device_code"
        private const val EXTRA_USER_CODE = "user_code"
        private const val EXTRA_VERIFICATION_URI = "verification_uri"
        private const val EXTRA_VERIFICATION_COMPLETE = "verification_complete"
        private const val EXTRA_INTERVAL = "interval"
        private const val EXTRA_EXPIRES = "expires"

        fun start(context: Context, pending: DeviceAuth.Pending) {
            val i = Intent(context, LoginPollService::class.java).apply {
                putExtra(EXTRA_DEVICE_CODE, pending.deviceCode)
                putExtra(EXTRA_USER_CODE, pending.userCode)
                putExtra(EXTRA_VERIFICATION_URI, pending.verificationUri)
                putExtra(EXTRA_VERIFICATION_COMPLETE, pending.verificationUriComplete)
                putExtra(EXTRA_INTERVAL, pending.intervalSec)
                putExtra(EXTRA_EXPIRES, pending.expiresInSec)
            }
            context.startForegroundService(i)
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, LoginPollService::class.java).setAction(ACTION_CANCEL)
            )
            // Also stop if not running as service start
            context.stopService(Intent(context, LoginPollService::class.java))
        }
    }
}

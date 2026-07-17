package com.blankspeaker.grld

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Checks monorepo `Binaries/android-latest.json` and installs updates via
 * [PackageInstaller] sessions (same path solid sideload apps use).
 *
 * Prefer session install over ACTION_VIEW — cleaner self-update, less friction
 * with the package manager / Play Protect UI for same-signature upgrades.
 */
object AppUpdater {
    private const val TAG = "GRLD-Updater"
    const val REPO_OWNER = "blankspeaker"
    const val REPO_NAME = "Grok-Rate-Limit-Display"

    /** Prefer raw.githubusercontent.com (no HTML redirect hop). */
    val versionManifestUrl =
        "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/Binaries/android-latest.json"

    val defaultApkUrl =
        "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/Binaries/GRLD-android.apk"

    private const val PREFS = "grld_updater"
    private const val UI_PREFS = "grld_ui"
    private const val KEY_LAST_CHECK_MS = "last_check_ms"
    private const val KEY_AUTO_UPDATE = "auto_update_enabled"
    private const val KEY_UPDATES_UNLOCKED = "updates_unlocked"
    private const val AUTO_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000
    private const val ACTION_INSTALL_STATUS = "com.blankspeaker.grld.UPDATE_INSTALL_STATUS"

    fun updatesUnlocked(context: Context): Boolean =
        context.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_UPDATES_UNLOCKED, false)

    fun autoUpdateEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_AUTO_UPDATE, false)

    fun setAutoUpdateEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AUTO_UPDATE, enabled)
            .apply()
    }

    fun shouldAutoCheck(context: Context): Boolean {
        if (!updatesUnlocked(context) || !autoUpdateEnabled(context)) return false
        val last = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_LAST_CHECK_MS, 0L)
        return System.currentTimeMillis() - last >= AUTO_CHECK_INTERVAL_MS
    }

    fun markChecked(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_CHECK_MS, System.currentTimeMillis())
            .apply()
    }

    data class ReleaseInfo(
        val tag: String,
        val version: String,
        val versionCode: Int,
        val apkUrl: String,
        val notes: String?
    )

    fun currentVersionName(context: Context): String {
        return try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"
        } catch (_: Exception) {
            "0"
        }
    }

    fun currentVersionCode(context: Context): Int {
        return try {
            val pi = context.packageManager.getPackageInfo(context.packageName, 0)
            if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode.toInt() else {
                @Suppress("DEPRECATION")
                pi.versionCode
            }
        } catch (_: Exception) {
            0
        }
    }

    fun isNewerVersion(remote: String, local: String): Boolean {
        val r = remote.trim().trimStart('v', 'V')
        val l = local.trim().trimStart('v', 'V')
        val rp = r.split('.').map { it.toIntOrNull() ?: 0 }
        val lp = l.split('.').map { it.toIntOrNull() ?: 0 }
        val n = maxOf(rp.size, lp.size)
        for (i in 0 until n) {
            val a = rp.getOrElse(i) { 0 }
            val b = lp.getOrElse(i) { 0 }
            if (a != b) return a > b
        }
        return false
    }

    fun isNewer(remote: ReleaseInfo, context: Context): Boolean {
        val localCode = currentVersionCode(context)
        if (remote.versionCode > 0 && remote.versionCode > localCode) return true
        if (remote.versionCode > 0 && remote.versionCode < localCode) return false
        return isNewerVersion(remote.version, currentVersionName(context))
    }

    suspend fun fetchLatestRelease(): ReleaseInfo = withContext(Dispatchers.IO) {
        val conn = (URL(versionManifestUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "GRLD-Android/${System.getProperty("http.agent") ?: ""}")
            instanceFollowRedirects = true
            useCaches = false
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw IllegalStateException("Version check HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            val version = json.getString("version").trim().trimStart('v', 'V')
            val tag = json.optString("tag", "v$version")
            val versionCode = json.optInt("versionCode", 0)
            // Prefer raw.githubusercontent.com over github.com/raw (fewer redirects)
            val apkUrl = when {
                json.optString("apk_url").isNotBlank() ->
                    normalizeApkUrl(json.getString("apk_url"))
                else -> {
                    val name = json.optString("apk", "GRLD-android.apk")
                    "https://raw.githubusercontent.com/$REPO_OWNER/$REPO_NAME/main/Binaries/$name"
                }
            }
            val notes = json.optString("notes").ifBlank { null }
            ReleaseInfo(tag, version, versionCode, apkUrl, notes)
        } finally {
            conn.disconnect()
        }
    }

    /** github.com/.../raw/main/... → raw.githubusercontent.com/.../main/... */
    private fun normalizeApkUrl(url: String): String {
        val m = Regex(
            """https?://github\.com/([^/]+)/([^/]+)/raw/([^/]+)/(.+)"""
        ).matchEntire(url.trim())
        return if (m != null) {
            "https://raw.githubusercontent.com/${m.groupValues[1]}/${m.groupValues[2]}/${m.groupValues[3]}/${m.groupValues[4]}"
        } else url
    }

    suspend fun checkForUpdate(context: Context): ReleaseInfo? {
        val latest = fetchLatestRelease()
        return if (isNewer(latest, context)) latest else null
    }

    /**
     * Download APK to cache. Verifies ZIP magic so we never try to install HTML.
     */
    suspend fun downloadApk(context: Context, release: ReleaseInfo): File =
        withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val out = File(dir, "GRLD-${release.version}-vc${release.versionCode}.apk")
            if (out.exists()) out.delete()

            val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 180_000
                setRequestProperty("User-Agent", "GRLD-Android")
                setRequestProperty("Accept", "application/vnd.android.package-archive,application/octet-stream,*/*")
                instanceFollowRedirects = true
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw IllegalStateException("APK download HTTP $code")
                }
                val contentType = conn.contentType?.lowercase() ?: ""
                if (contentType.contains("text/html")) {
                    throw IllegalStateException("Download returned HTML, not an APK (bad URL?)")
                }
                conn.inputStream.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                }
            } finally {
                conn.disconnect()
            }
            if (!out.exists() || out.length() < 50_000) {
                throw IllegalStateException("APK download incomplete (${out.length()} bytes)")
            }
            // ZIP / APK local header "PK"
            val header = ByteArray(4)
            out.inputStream().use { stream ->
                if (stream.read(header) != 4) throw IllegalStateException("APK unreadable")
            }
            if (header[0] != 0x50.toByte() || header[1] != 0x4B.toByte()) {
                out.delete()
                throw IllegalStateException("Downloaded file is not a valid APK (bad magic)")
            }
            Log.i(TAG, "Downloaded ${out.length()} bytes → ${out.name}")
            out
        }

    @Deprecated("Use downloadApk", ReplaceWith("downloadApk(context, release)"))
    suspend fun downloadAndInstall(context: Context, release: ReleaseInfo): File =
        downloadApk(context, release)

    /**
     * Install via [PackageInstaller] session (preferred for self-updates).
     * Falls back to ACTION_VIEW FileProvider intent if the session path fails.
     */
    suspend fun installApk(context: Context, apkFile: File): String =
        withContext(Dispatchers.Main) {
            try {
                installWithPackageInstaller(context, apkFile)
                "Installer started — confirm if Android asks"
            } catch (e: Exception) {
                Log.w(TAG, "PackageInstaller failed, falling back to ACTION_VIEW", e)
                installWithActionView(context, apkFile)
                "Opening system installer…"
            }
        }

    private suspend fun installWithPackageInstaller(context: Context, apkFile: File): Unit =
        withContext(Dispatchers.IO) {
            val appCtx = context.applicationContext
            val installer = appCtx.packageManager.packageInstaller
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            params.setAppPackageName(appCtx.packageName)
            // Self-update of the same package: skip extra confirm when the OS allows it.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                try {
                    params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                } catch (_: Exception) { }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    params.setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
                } catch (_: Exception) { }
            }

            val sessionId = installer.createSession(params)
            val session = installer.openSession(sessionId)
            try {
                apkFile.inputStream().use { input ->
                    session.openWrite("grld.apk", 0, apkFile.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                // Do not close the session after commit — commit seals it.
                suspendCancellableCoroutine { cont ->
                    val receiver = object : BroadcastReceiver() {
                        override fun onReceive(ctx: Context?, intent: Intent?) {
                            if (intent?.action != ACTION_INSTALL_STATUS) return
                            try {
                                appCtx.unregisterReceiver(this)
                            } catch (_: Exception) { }
                            val status = intent.getIntExtra(
                                PackageInstaller.EXTRA_STATUS,
                                PackageInstaller.STATUS_FAILURE
                            )
                            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                            Log.i(TAG, "Install status=$status msg=$message")
                            when (status) {
                                PackageInstaller.STATUS_SUCCESS -> {
                                    if (cont.isActive) cont.resume(Unit)
                                }
                                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                                    // System / Play Protect confirmation UI
                                    val confirm = if (Build.VERSION.SDK_INT >= 33) {
                                        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                                    } else {
                                        @Suppress("DEPRECATION")
                                        intent.getParcelableExtra(Intent.EXTRA_INTENT)
                                    }
                                    if (confirm != null) {
                                        confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        try {
                                            appCtx.startActivity(confirm)
                                            if (cont.isActive) cont.resume(Unit)
                                        } catch (e: Exception) {
                                            if (cont.isActive) cont.resumeWithException(e)
                                        }
                                    } else if (cont.isActive) {
                                        cont.resume(Unit)
                                    }
                                }
                                else -> {
                                    if (cont.isActive) {
                                        cont.resumeWithException(
                                            IllegalStateException(
                                                message ?: "Install failed status=$status"
                                            )
                                        )
                                    }
                                }
                            }
                        }
                    }
                    ContextCompat.registerReceiver(
                        appCtx,
                        receiver,
                        IntentFilter(ACTION_INSTALL_STATUS),
                        ContextCompat.RECEIVER_NOT_EXPORTED
                    )
                    cont.invokeOnCancellation {
                        try {
                            appCtx.unregisterReceiver(receiver)
                        } catch (_: Exception) { }
                        try {
                            installer.abandonSession(sessionId)
                        } catch (_: Exception) { }
                    }
                    val flags = if (Build.VERSION.SDK_INT >= 31) {
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    } else {
                        PendingIntent.FLAG_UPDATE_CURRENT
                    }
                    val callback = PendingIntent.getBroadcast(
                        appCtx,
                        sessionId,
                        Intent(ACTION_INSTALL_STATUS).setPackage(appCtx.packageName),
                        flags
                    )
                    session.commit(callback.intentSender)
                }
            } catch (e: Exception) {
                try {
                    installer.abandonSession(sessionId)
                } catch (_: Exception) { }
                throw e
            }
        }

    private fun installWithActionView(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        // Prefer package installer component when present
        context.startActivity(intent)
    }

    fun canInstallPackages(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    fun openUnknownSourcesSettings(activity: Activity) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val intent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${activity.packageName}")
            )
            activity.startActivity(intent)
        }
    }

    /**
     * Full flow: check → download → PackageInstaller session.
     * @return status message for UI, or null if already up to date (non-interactive)
     */
    suspend fun checkDownloadAndInstall(
        activity: Activity,
        interactive: Boolean
    ): String? {
        val release = checkForUpdate(activity)
            ?: return if (interactive) {
                "Up to date (v${currentVersionName(activity)})"
            } else null

        if (!canInstallPackages(activity)) {
            if (interactive) {
                openUnknownSourcesSettings(activity)
            }
            return "Allow install from this app, then check again for v${release.version}"
        }

        val apk = downloadApk(activity, release)
        val msg = installApk(activity, apk)
        return "Update v${release.version}: $msg"
    }
}

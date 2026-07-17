package com.blankspeaker.grld

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Stepped gauge icons for **home-screen launcher** and **notification tray**.
 *
 * Both surfaces use the same 10% remaining buckets (0, 10, …, 100):
 *  - Launcher: activity-aliases with pre-rendered black adaptive icons
 *  - Notification smallIcon: @drawable/ic_stat_rem_* (white-on-transparent)
 *
 * Resource icons update reliably on Samsung; live bitmaps often do not.
 */
object DynamicAppIcon {

    /** remaining% steps shared by launcher + notification tray */
    val STEPS = intArrayOf(0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100)

    private const val PREFS = "dynamic_app_icon"
    private const val KEY_BUCKET = "bucket"

    private fun aliasName(context: Context, bucket: Int): String =
        "${context.packageName}.LauncherRem$bucket"

    private fun defaultAliasName(context: Context): String =
        "${context.packageName}.LauncherDefault"

    private fun allAliasNames(context: Context): List<String> =
        STEPS.map { aliasName(context, it) } + listOf(defaultAliasName(context))

    /**
     * Snap remaining% to nearest step (0/10/…/100).
     * null → default Lucide pose.
     */
    fun bucketFor(remaining: Int?): Int? {
        if (remaining == null) return null
        val r = remaining.coerceIn(0, 100)
        return STEPS.minByOrNull { kotlin.math.abs(it - r) } ?: 100
    }

    /**
     * Notification [setSmallIcon] resource for [remaining] %
     * (white-on-transparent monochrome gauge at that needle angle).
     */
    fun notificationIconRes(remaining: Int?): Int {
        return when (bucketFor(remaining)) {
            0 -> R.drawable.ic_stat_rem_0
            10 -> R.drawable.ic_stat_rem_10
            20 -> R.drawable.ic_stat_rem_20
            30 -> R.drawable.ic_stat_rem_30
            40 -> R.drawable.ic_stat_rem_40
            50 -> R.drawable.ic_stat_rem_50
            60 -> R.drawable.ic_stat_rem_60
            70 -> R.drawable.ic_stat_rem_70
            80 -> R.drawable.ic_stat_rem_80
            90 -> R.drawable.ic_stat_rem_90
            100 -> R.drawable.ic_stat_rem_100
            else -> R.drawable.ic_stat_rem_default
        }
    }

    /**
     * Switch the enabled launcher activity-alias to match [remaining] %.
     * No-op if already on that bucket (avoids launcher flicker).
     */
    fun update(context: Context, remaining: Int?) {
        val bucket = bucketFor(remaining) // null = default pose
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val prev = if (prefs.contains(KEY_BUCKET)) prefs.getInt(KEY_BUCKET, -1) else -999
        val key = bucket ?: -1
        if (prev == key) return

        val pm = context.packageManager
        val pkg = context.packageName

        // Disable every launcher alias, then enable the matching one
        for (name in allAliasNames(context)) {
            val cn = ComponentName(pkg, name)
            try {
                pm.setComponentEnabledSetting(
                    cn,
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) { /* alias missing in older installs */ }
        }

        val enableName = if (bucket != null) aliasName(context, bucket)
        else defaultAliasName(context)
        try {
            pm.setComponentEnabledSetting(
                ComponentName(pkg, enableName),
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP
            )
            prefs.edit().putInt(KEY_BUCKET, key).apply()
        } catch (_: Exception) {
            // Fall back to default if something is wrong with the alias set
            try {
                pm.setComponentEnabledSetting(
                    ComponentName(pkg, defaultAliasName(context)),
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                    PackageManager.DONT_KILL_APP
                )
            } catch (_: Exception) { }
        }
    }
}

package com.blankspeaker.grld

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Shared title / reset formatting for tray, widget, and status chip. */
object UsageFormatting {

    /**
     * e.g. "Thu 2:25 PM" from ISO period end.
     * Returns null if unparseable.
     */
    fun formatResetLabel(endIso: String?): String? {
        val ms = parseIsoMs(endIso) ?: return null
        return try {
            SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(ms))
        } catch (_: Exception) {
            endIso?.take(16)
        }
    }

    /**
     * Full headline for tray / widget:
     * "62% Used · Resets Thu 2:25 PM"
     */
    fun usageHeadline(
        usedPercent: Int?,
        remainingPercent: Int?,
        showUsed: Boolean,
        periodEndIso: String?,
        signedIn: Boolean,
        notSignedInLabel: String,
        fallbackLabel: String
    ): String {
        if (!signedIn) return notSignedInLabel
        val pct = if (showUsed) usedPercent else remainingPercent
        if (pct == null) return fallbackLabel
        val mode = if (showUsed) "Used" else "Remaining"
        val reset = formatResetLabel(periodEndIso)
        return if (reset != null) "$pct% $mode · Resets $reset"
        else "$pct% $mode"
    }

    fun parseIsoMs(iso: String?): Long? {
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
}

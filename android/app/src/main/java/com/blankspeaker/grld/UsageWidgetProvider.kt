package com.blankspeaker.grld

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget mirroring the notification tray summary:
 * headline + multi-color bar + category line.
 */
class UsageWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        val state = UsageRepository.get(context).state.value
        for (id in appWidgetIds) {
            appWidgetManager.updateAppWidget(id, buildViews(context, state))
        }
    }

    companion object {
        fun updateAll(context: Context, state: UsageRepository.UsageUiState? = null) {
            val app = context.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, UsageWidgetProvider::class.java))
            if (ids.isEmpty()) return
            val s = state ?: UsageRepository.get(app).state.value
            val views = buildViews(app, s)
            for (id in ids) mgr.updateAppWidget(id, views)
        }

        fun hasWidgets(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context.applicationContext)
            return mgr.getAppWidgetIds(
                ComponentName(context.applicationContext, UsageWidgetProvider::class.java)
            ).isNotEmpty()
        }

        /** Opens the system “pin widget” flow when supported. */
        fun requestPin(context: Context): Boolean {
            val mgr = AppWidgetManager.getInstance(context)
            if (!mgr.isRequestPinAppWidgetSupported) return false
            val provider = ComponentName(context, UsageWidgetProvider::class.java)
            val success = PendingIntent.getBroadcast(
                context,
                0,
                Intent(context, UsageWidgetProvider::class.java).setAction(ACTION_PINNED),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return mgr.requestPinAppWidget(provider, null, success)
        }

        private const val ACTION_PINNED = "com.blankspeaker.grld.WIDGET_PINNED"

        private fun buildViews(
            context: Context,
            state: UsageRepository.UsageUiState
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_usage)
            val repo = UsageRepository.get(context)
            val showUsed = repo.showUsedPercent()
            val used = state.usage?.usedPercent
            val rem = state.usage?.remainingPercent
            // Same headline as tray: "62% Used · Resets Thu 2:25 PM"
            val title = UsageFormatting.usageHeadline(
                usedPercent = used,
                remainingPercent = rem,
                showUsed = showUsed,
                periodEndIso = state.usage?.currentPeriod?.end,
                signedIn = state.signedIn,
                notSignedInLabel = context.getString(R.string.notif_sign_in),
                fallbackLabel = context.getString(R.string.app_name)
            )
            views.setTextViewText(R.id.widget_headline, title)

            val products = state.usage?.productUsage
                ?.filter { it.usagePercent > 0 }
                ?.sortedByDescending { it.usagePercent }
                .orEmpty()
            val historyIds = DailyHistoryStore.get(context).knownProductIds()
            val palette = NotifPalette.indexMapFromUsageAndHistory(products, historyIds)
            val usedVal = (used ?: 0).coerceIn(0, 100)
            val density = context.resources.displayMetrics.density
            // Match main-app bar thickness (32dp); notif tray stays thinner
            val barW = (360 * density).toInt().coerceIn(240, 1200)
            val barH = (32 * density).toInt().coerceIn(64, 160)
            views.setImageViewBitmap(
                R.id.widget_bar,
                PercentIconFactory.usageBarBitmap(
                    products.sortedBy { it.product },
                    usedVal,
                    width = barW,
                    height = barH,
                    opaqueBackground = false,
                    palette = palette
                )
            )

            val catLine = if (products.isEmpty()) {
                context.getString(R.string.notif_tap_open)
            } else {
                // Single line — keep widget short
                products.take(5).joinToString(" · ") { p ->
                    val name = p.name.ifBlank { ProductColors.displayName(p.product) }
                    "${NotifPalette.emoji(p.product, palette)} $name ${p.usagePercent}%"
                }
            }
            views.setTextViewText(R.id.widget_categories, catLine)

            val open = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            val pi = PendingIntent.getActivity(
                context, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_root, pi)
            return views
        }
    }
}

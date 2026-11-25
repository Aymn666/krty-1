package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.PortalBrowserActivity
import com.example.R

/**
 * Home screen shortcut widget for instant Wi-Fi card scanning or portal+scanner launch.
 */
class KartiScanWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        for (appWidgetId in appWidgetIds) {
            updateWidget(context, appWidgetManager, appWidgetId)
        }
    }

    companion object {
        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_karti_scan)

            val directScanIntent = Intent(context, MainActivity::class.java).apply {
                action = "com.example.action.WIDGET_QUICK_SCAN"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_IN_APP_SCANNER, true)
            }
            val directScanPendingIntent = PendingIntent.getActivity(
                context,
                3001,
                directScanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val portalScannerIntent = Intent(context, PortalBrowserActivity::class.java).apply {
                action = "com.example.action.WIDGET_PORTAL_SCAN"
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(PortalBrowserActivity.EXTRA_AUTO_START_SCANNER, true)
            }
            val portalScannerPendingIntent = PendingIntent.getActivity(
                context,
                3002,
                portalScannerIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_scan_root, directScanPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_scan_button, directScanPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_portal_button, portalScannerPendingIntent)
            return views
        }

        fun updateWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            appWidgetManager.updateAppWidget(appWidgetId, buildRemoteViews(context))
        }
    }
}

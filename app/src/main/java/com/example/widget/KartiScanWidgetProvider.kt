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
import com.example.service.ScannerLaunchMode

/**
 * Home screen shortcut widget offering two direct instant workflows:
 * 1) Silent Scan & Login (مسح صامت ⚡): opens scanner in SILENT_AUTO mode and runs background WebView login.
 * 2) Scan & Preview (مسح ومعاينة 👁️): opens portal WebView + scanner and auto-submits in front of the user.
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
        const val ACTION_WIDGET_SILENT_SCAN = "com.example.action.WIDGET_SILENT_SCAN"
        const val ACTION_WIDGET_PREVIEW_SCAN = "com.example.action.WIDGET_PREVIEW_SCAN"

        fun buildRemoteViews(context: Context): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_karti_scan)

            val silentScanIntent = Intent(context, MainActivity::class.java).apply {
                action = ACTION_WIDGET_SILENT_SCAN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(MainActivity.EXTRA_OPEN_IN_APP_SCANNER, true)
                putExtra(MainActivity.EXTRA_SCANNER_MODE, ScannerLaunchMode.SILENT_AUTO.name)
                putExtra(MainActivity.EXTRA_FROM_WIDGET, true)
            }
            val silentScanPendingIntent = PendingIntent.getActivity(
                context,
                3001,
                silentScanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val previewScanIntent = Intent(context, PortalBrowserActivity::class.java).apply {
                action = ACTION_WIDGET_PREVIEW_SCAN
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(PortalBrowserActivity.EXTRA_AUTO_START_SCANNER, true)
                putExtra(PortalBrowserActivity.EXTRA_AUTO_SUBMIT_ON_LOCK, true)
                putExtra(PortalBrowserActivity.EXTRA_FORCE_AUTO_CLICK, true)
            }
            val previewScanPendingIntent = PendingIntent.getActivity(
                context,
                3002,
                previewScanIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            views.setOnClickPendingIntent(R.id.widget_scan_root, silentScanPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_silent_scan_button, silentScanPendingIntent)
            views.setOnClickPendingIntent(R.id.widget_preview_scan_button, previewScanPendingIntent)
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

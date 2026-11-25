package com.example

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.widget.KartiScanWidgetProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class KartiScanWidgetTest {

    @Test
    fun widgetProviderUpdatesRemoteViewsWithoutError() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val views = KartiScanWidgetProvider.buildRemoteViews(context)
        assertEquals(context.packageName, views.`package`)
        assertEquals(R.layout.widget_karti_scan, views.layoutId)

        val manager = AppWidgetManager.getInstance(context)
        val shadowManager = Shadows.shadowOf(manager)
        val widgetId = shadowManager.createWidget(KartiScanWidgetProvider::class.java, R.layout.widget_karti_scan)
        KartiScanWidgetProvider().onUpdate(context, manager, intArrayOf(widgetId))
        assertNotNull(shadowManager.getViewFor(widgetId))
    }
}

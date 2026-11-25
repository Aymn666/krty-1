package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.KartiApplication
import com.example.PortalBrowserActivity
import com.example.R
import com.example.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class HotspotNotificationListener : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastAlertTimestampMs = 0L

    private val triggerKeywords = listOf(
        "تسجيل الدخول",
        "سجّل الدخول",
        "سجل الدخول",
        "wi-fi",
        "wifi",
        "captive portal",
        "sign in to",
        "hotspot",
        "شبكة wi-fi",
        "دخول إلى شبكة"
    )

    private val systemPackages = setOf(
        "android",
        "com.android.systemui",
        "com.android.captiveportallogin",
        "com.google.android.captiveportallogin",
        "com.android.settings",
        "com.android.networkstack",
        "com.google.android.networkstack"
    )

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            activeNotifications?.forEach { sbn ->
                inspectNotification(sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            inspectNotification(sbn)
        }
    }

    private fun inspectNotification(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return

        val extras = sbn.notification?.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString().orEmpty()
        val subText = extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString().orEmpty()

        val combined = "$title\n$text\n$bigText\n$subText".lowercase()
        val isFromSystem = sbn.packageName in systemPackages
        val matchesKeyword = triggerKeywords.any { combined.contains(it) }

        val isCaptivePortalNotification = matchesKeyword && (
            isFromSystem ||
                combined.contains("wi-fi") ||
                combined.contains("captive portal") ||
                combined.contains("تسجيل الدخول إلى شبكة")
            )

        if (!isCaptivePortalNotification) return

        val detectedNetwork = extractNetworkName(title, text, bigText)
        if (detectedNetwork.isNotBlank()) {
            CardDispatchCoordinator.updateDetectedNetwork(this, detectedNetwork)
        }

        scope.launch {
            val app = applicationContext as? KartiApplication
            val bubbleEnabled = app?.settingsDataStore?.settingsFlow?.first()?.floatingBubbleEnabled ?: true
            if (bubbleEnabled && PermissionHelper.isOverlayGranted(this@HotspotNotificationListener)) {
                val intent = Intent(this@HotspotNotificationListener, OverlayBubbleService::class.java).apply {
                    putExtra(OverlayBubbleService.EXTRA_NETWORK_NAME, detectedNetwork)
                }
                val started = runCatching {
                    ContextCompat.startForegroundService(this@HotspotNotificationListener, intent)
                    true
                }.getOrDefault(false)
                if (!started) {
                    showFallbackPortalNotification(detectedNetwork)
                }
            } else {
                showFallbackPortalNotification(detectedNetwork)
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun showFallbackPortalNotification(networkName: String) {
        if (!PermissionHelper.isNotificationGranted(this)) return
        val now = System.currentTimeMillis()
        if (now - lastAlertTimestampMs < 6_000L) return
        lastAlertTimestampMs = now

        val portalIntent = Intent(this, PortalBrowserActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(PortalBrowserActivity.EXTRA_AUTO_START_SCANNER, true)
            putExtra(PortalBrowserActivity.EXTRA_NETWORK_NAME, networkName)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            PORTAL_ALERT_NOTIF_ID,
            portalIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val title = if (networkName.isNotBlank()) {
            "شبكة $networkName تتطلب كرت"
        } else {
            getString(R.string.portal_alert_title)
        }

        val notification = NotificationCompat.Builder(this, KartiApplication.CHANNEL_PORTAL_ALERT)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(title)
            .setContentText(getString(R.string.portal_alert_text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        runCatching {
            NotificationManagerCompat.from(this).notify(PORTAL_ALERT_NOTIF_ID, notification)
        }
    }

    private fun extractNetworkName(title: String, text: String, bigText: String): String {
        val candidates = listOf(text, bigText, title)
        for (raw in candidates) {
            val trimmed = raw.trim()
            if (trimmed.isNotBlank() &&
                !trimmed.contains("تسجيل الدخول إلى شبكة", ignoreCase = true) &&
                !trimmed.contains("sign in to wi-fi", ignoreCase = true)
            ) {
                return trimmed.take(40)
            }
        }
        return ""
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val PORTAL_ALERT_NOTIF_ID = 4003
    }
}

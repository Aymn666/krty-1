package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import com.example.KartiApplication
import com.example.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed interface AutoFillStatus {
    data object Idle : AutoFillStatus
    data class Pending(val code: String) : AutoFillStatus
    data class FilledAndSubmitted(val code: String, val networkName: String) : AutoFillStatus
    data class FilledOnly(val code: String) : AutoFillStatus
    data class PortalNotFound(val code: String) : AutoFillStatus
    data class AccessibilityDisabled(val code: String) : AutoFillStatus
}

object CardDispatchCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _pendingCardCode = MutableStateFlow<String?>(null)
    val pendingCardCode: StateFlow<String?> = _pendingCardCode.asStateFlow()

    private val _detectedNetworkName = MutableStateFlow<String>("")
    val detectedNetworkName: StateFlow<String> = _detectedNetworkName.asStateFlow()

    private val _autoFillStatus = MutableStateFlow<AutoFillStatus>(AutoFillStatus.Idle)
    val autoFillStatus: StateFlow<AutoFillStatus> = _autoFillStatus.asStateFlow()

    private val _isBubbleRunning = MutableStateFlow(false)
    val isBubbleRunning: StateFlow<Boolean> = _isBubbleRunning.asStateFlow()

    private val _isScannerOverlayRunning = MutableStateFlow(false)
    val isScannerOverlayRunning: StateFlow<Boolean> = _isScannerOverlayRunning.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    fun setBubbleRunning(running: Boolean) {
        _isBubbleRunning.value = running
    }

    fun setScannerOverlayRunning(running: Boolean) {
        _isScannerOverlayRunning.value = running
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun updateDetectedNetwork(context: Context, networkName: String) {
        if (networkName.isBlank()) return
        _detectedNetworkName.value = networkName.trim()
        val app = context.applicationContext as? KartiApplication ?: return
        scope.launch {
            app.settingsDataStore.setLastNetworkName(networkName.trim())
        }
    }

    fun dispatchScannedCode(
        context: Context,
        code: String,
        confidence: Float = 1.0f,
        saveToHistory: Boolean = true
    ) {
        val cleanCode = code.trim()
        _pendingCardCode.value = cleanCode
        val app = context.applicationContext as? KartiApplication

        scope.launch {
            val settings = app?.settingsDataStore?.settingsFlow?.first()
            val network = _detectedNetworkName.value.ifBlank { settings?.lastNetworkName.orEmpty() }
            if (saveToHistory && (settings?.saveHistoryEnabled != false)) {
                app?.cardHistoryRepository?.saveScannedCard(
                    cardCode = cleanCode,
                    networkName = network,
                    isAutoSubmitted = false,
                    confidence = confidence
                )
            }
        }

        val accessibilityReady = PermissionHelper.isAccessibilityServiceEnabled(context) ||
            _isAccessibilityConnected.value

        if (!accessibilityReady) {
            copyToClipboard(context, cleanCode, showToast = false)
            _autoFillStatus.value = AutoFillStatus.AccessibilityDisabled(cleanCode)
            return
        }

        _autoFillStatus.value = AutoFillStatus.Pending(cleanCode)
        PortalAccessibilityService.requestAutoFill(cleanCode)
    }

    fun onAutoFillResult(context: Context, code: String, filled: Boolean, submitted: Boolean) {
        val app = context.applicationContext as? KartiApplication
        val network = _detectedNetworkName.value

        if (filled && submitted) {
            _pendingCardCode.value = null
            _autoFillStatus.value = AutoFillStatus.FilledAndSubmitted(code, network)
            scope.launch {
                app?.cardHistoryRepository?.markSubmitted(code)
            }
        } else if (filled) {
            _autoFillStatus.value = AutoFillStatus.FilledOnly(code)
        } else {
            copyToClipboard(context, code, showToast = false)
            _autoFillStatus.value = AutoFillStatus.PortalNotFound(code)
        }
    }

    fun clearPendingCode() {
        _pendingCardCode.value = null
        _autoFillStatus.value = AutoFillStatus.Idle
    }

    fun copyToClipboard(context: Context, code: String, showToast: Boolean = true) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clipboard?.setPrimaryClip(ClipData.newPlainText("Wi-Fi Card Code", code))
        if (showToast) {
            Toast.makeText(context, "تم نسخ الكود: $code", Toast.LENGTH_SHORT).show()
        }
    }

    fun openCaptivePortalOrWifi(context: Context) {
        // Try finding current Wi-Fi gateway IP (e.g. 10.0.0.1 or 192.168.88.1) to launch MikroTik portal
        val gatewayUrl = resolveWifiGatewayUrl(context)
        val urlIntent = Intent(Intent.ACTION_VIEW, Uri.parse(gatewayUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedPortal = runCatching {
            context.startActivity(urlIntent)
            true
        }.getOrDefault(false)

        if (!launchedPortal) {
            val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(wifiIntent) }
        }
    }

    fun openWifiSettings(context: Context) {
        val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(wifiIntent) }
    }

    private fun resolveWifiGatewayUrl(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "http://10.0.0.1"
        @Suppress("DEPRECATION")
        val networks = cm.allNetworks
        for (network in networks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val linkProps = cm.getLinkProperties(network) ?: continue
                for (route in linkProps.routes) {
                    val gateway = route.gateway
                    if (route.isDefaultRoute && gateway != null && !gateway.isLoopbackAddress) {
                        val host = gateway.hostAddress
                        if (!host.isNullOrBlank() && !host.contains(':')) {
                            return "http://$host"
                        }
                    }
                }
            }
        }
        return "http://10.0.0.1"
    }
}

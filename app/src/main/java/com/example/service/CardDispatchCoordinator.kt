package com.example.service

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import com.example.KartiApplication
import com.example.PortalBrowserActivity
import com.example.utils.PermissionHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

import androidx.core.content.ContextCompat

enum class ScannerLaunchMode {
    REVIEW,
    SILENT_AUTO,
    PREVIEW_AUTO
}

sealed interface SilentLoginState {
    data object Idle : SilentLoginState
    data class Connecting(val code: String, val networkName: String = "") : SilentLoginState
    data class Success(val code: String, val networkName: String = "") : SilentLoginState
    data class Error(val code: String, val message: String) : SilentLoginState
}

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

    private val _detectedNetworkName = MutableStateFlow("")
    val detectedNetworkName: StateFlow<String> = _detectedNetworkName.asStateFlow()

    private val _autoFillStatus = MutableStateFlow<AutoFillStatus>(AutoFillStatus.Idle)
    val autoFillStatus: StateFlow<AutoFillStatus> = _autoFillStatus.asStateFlow()

    private val _silentLoginState = MutableStateFlow<SilentLoginState>(SilentLoginState.Idle)
    val silentLoginState: StateFlow<SilentLoginState> = _silentLoginState.asStateFlow()

    private val _isBubbleRunning = MutableStateFlow(false)
    val isBubbleRunning: StateFlow<Boolean> = _isBubbleRunning.asStateFlow()

    private val _isScannerOverlayRunning = MutableStateFlow(false)
    val isScannerOverlayRunning: StateFlow<Boolean> = _isScannerOverlayRunning.asStateFlow()

    private val _isPortalBrowserOpen = MutableStateFlow(false)
    val isPortalBrowserOpen: StateFlow<Boolean> = _isPortalBrowserOpen.asStateFlow()

    private val _isAccessibilityConnected = MutableStateFlow(false)
    val isAccessibilityConnected: StateFlow<Boolean> = _isAccessibilityConnected.asStateFlow()

    @Volatile
    private var lastAutoCopiedCode: String? = null

    fun setBubbleRunning(running: Boolean) {
        _isBubbleRunning.value = running
    }

    fun setScannerOverlayRunning(running: Boolean) {
        _isScannerOverlayRunning.value = running
    }

    fun setPortalBrowserOpen(open: Boolean) {
        _isPortalBrowserOpen.value = open
    }

    fun setAccessibilityConnected(connected: Boolean) {
        _isAccessibilityConnected.value = connected
    }

    fun updateSilentLoginState(state: SilentLoginState) {
        _silentLoginState.value = state
    }

    fun clearSilentLoginState() {
        _silentLoginState.value = SilentLoginState.Idle
    }

    fun startSilentLogin(
        context: Context,
        code: String,
        confidence: Float = 1.0f
    ) {
        val cleanCode = code.trim()
        if (cleanCode.isBlank()) return
        val network = _detectedNetworkName.value
        _silentLoginState.value = SilentLoginState.Connecting(cleanCode, network)
        saveScannedCardToHistory(
            context = context,
            code = cleanCode,
            confidence = confidence,
            submitted = false
        )
        val serviceIntent = Intent(context, SilentPortalLoginService::class.java).apply {
            putExtra(SilentPortalLoginService.EXTRA_CARD_CODE, cleanCode)
            putExtra(SilentPortalLoginService.EXTRA_NETWORK_NAME, network)
        }
        runCatching {
            ContextCompat.startForegroundService(context, serviceIntent)
        }.onFailure {
            _silentLoginState.value = SilentLoginState.Error(
                code = cleanCode,
                message = "تعذر بدء الدخول الصامت — يمكنك فتح البوابة يدوياً"
            )
        }
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
        lastAutoCopiedCode = null
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
            copyOnce(context, cleanCode)
            _pendingCardCode.value = null
            _autoFillStatus.value = AutoFillStatus.AccessibilityDisabled(cleanCode)
            return
        }

        _autoFillStatus.value = AutoFillStatus.Pending(cleanCode)
        PortalAccessibilityService.requestAutoFill(cleanCode)
    }

    fun markPortalLoginSuccess(context: Context, code: String) {
        val cleanCode = code.trim()
        val app = context.applicationContext as? KartiApplication
        val network = _detectedNetworkName.value
        _pendingCardCode.value = null
        _autoFillStatus.value = AutoFillStatus.FilledAndSubmitted(cleanCode, network)
        scope.launch {
            app?.cardHistoryRepository?.markSubmitted(cleanCode)
        }
    }

    fun saveScannedCardToHistory(
        context: Context,
        code: String,
        confidence: Float,
        submitted: Boolean
    ) {
        val cleanCode = code.trim()
        val app = context.applicationContext as? KartiApplication ?: return
        scope.launch {
            val settings = app.settingsDataStore.settingsFlow.first()
            val network = _detectedNetworkName.value.ifBlank { settings.lastNetworkName }
            if (settings.saveHistoryEnabled) {
                app.cardHistoryRepository.saveScannedCard(
                    cardCode = cleanCode,
                    networkName = network,
                    isAutoSubmitted = submitted,
                    confidence = confidence
                )
            }
        }
    }

    fun onAutoFillResult(context: Context, code: String, filled: Boolean, submitted: Boolean) {
        val app = context.applicationContext as? KartiApplication
        val network = _detectedNetworkName.value

        // Always clear pending code after the attempt completes so AccessibilityService
        // never loops repeatedly copying to clipboard or reopening the keyboard.
        _pendingCardCode.value = null

        if (filled && submitted) {
            _autoFillStatus.value = AutoFillStatus.FilledAndSubmitted(code, network)
            scope.launch {
                app?.cardHistoryRepository?.markSubmitted(code)
            }
        } else if (filled) {
            _autoFillStatus.value = AutoFillStatus.FilledOnly(code)
        } else {
            copyOnce(context, code)
            _autoFillStatus.value = AutoFillStatus.PortalNotFound(code)
        }
    }

    private fun copyOnce(context: Context, code: String) {
        if (lastAutoCopiedCode == code) return
        lastAutoCopiedCode = code
        copyToClipboard(context, code, showToast = false)
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

    fun openInAppPortal(
        context: Context,
        autoStartScanner: Boolean = false,
        prefillCode: String? = null,
        autoSubmitOnLock: Boolean = false,
        forceAutoClick: Boolean = false
    ) {
        val intent = Intent(context, PortalBrowserActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(PortalBrowserActivity.EXTRA_AUTO_START_SCANNER, autoStartScanner)
            putExtra(PortalBrowserActivity.EXTRA_AUTO_SUBMIT_ON_LOCK, autoSubmitOnLock)
            putExtra(PortalBrowserActivity.EXTRA_FORCE_AUTO_CLICK, forceAutoClick)
            putExtra(PortalBrowserActivity.EXTRA_NETWORK_NAME, _detectedNetworkName.value)
            if (!prefillCode.isNullOrBlank()) {
                putExtra(PortalBrowserActivity.EXTRA_PREFILL_CODE, prefillCode)
            }
        }
        runCatching {
            context.startActivity(intent)
        }.onFailure {
            openWifiSettings(context)
        }
    }

    fun openCaptivePortalOrWifi(context: Context) {
        openInAppPortal(context = context, autoStartScanner = false)
    }

    fun openWifiSettings(context: Context) {
        val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        runCatching { context.startActivity(wifiIntent) }
    }
}

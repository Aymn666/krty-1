package com.example.ui

import android.app.Application
import android.content.Intent
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.KartiApplication
import com.example.data.local.CardScanEntity
import com.example.data.local.KartiSettings
import com.example.service.AutoFillStatus
import com.example.service.CardDispatchCoordinator
import com.example.service.OverlayBubbleService
import com.example.service.ScannerOverlayService
import com.example.utils.PermissionHelper
import com.example.utils.PermissionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MainUiState(
    val settings: KartiSettings = KartiSettings(),
    val cardHistory: List<CardScanEntity> = emptyList(),
    val permissions: PermissionStatus = PermissionStatus(),
    val isBubbleRunning: Boolean = false,
    val pendingCode: String? = null,
    val autoFillStatus: AutoFillStatus = AutoFillStatus.Idle,
    val detectedNetwork: String = ""
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as KartiApplication
    private val settingsStore = app.settingsDataStore
    private val historyRepo = app.cardHistoryRepository

    private val _permissions = MutableStateFlow(PermissionHelper.checkAllPermissions(application))

    val uiState: StateFlow<MainUiState> = combine(
        settingsStore.settingsFlow,
        historyRepo.allCards,
        _permissions,
        CardDispatchCoordinator.isBubbleRunning,
        CardDispatchCoordinator.pendingCardCode,
        CardDispatchCoordinator.autoFillStatus,
        CardDispatchCoordinator.detectedNetworkName
    ) { values ->
        val settings = values[0] as KartiSettings
        @Suppress("UNCHECKED_CAST")
        val history = values[1] as List<CardScanEntity>
        val perms = values[2] as PermissionStatus
        val bubbleRunning = values[3] as Boolean
        val pending = values[4] as String?
        val fillStatus = values[5] as AutoFillStatus
        val network = values[6] as String

        MainUiState(
            settings = settings,
            cardHistory = history,
            permissions = perms,
            isBubbleRunning = bubbleRunning,
            pendingCode = pending,
            autoFillStatus = fillStatus,
            detectedNetwork = network.ifBlank { settings.lastNetworkName }
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = MainUiState(permissions = PermissionHelper.checkAllPermissions(application))
    )

    fun refreshPermissions() {
        _permissions.value = PermissionHelper.checkAllPermissions(app)
    }

    fun setAutoFlash(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoFlash(enabled) }
    }

    fun setVibration(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setVibration(enabled) }
    }

    fun setAutoFill(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoFill(enabled) }
    }

    fun setAutoClick(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setAutoClick(enabled) }
    }

    fun setSaveHistory(enabled: Boolean) {
        viewModelScope.launch { settingsStore.setSaveHistory(enabled) }
    }

    fun setFloatingBubbleEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsStore.setFloatingBubble(enabled)
            if (!enabled) {
                stopFloatingBubble()
            }
        }
    }

    fun startFloatingBubble() {
        if (!PermissionHelper.isOverlayGranted(app)) {
            Toast.makeText(app, "فعّل صلاحية الظهور فوق التطبيقات أولاً", Toast.LENGTH_SHORT).show()
            PermissionHelper.openOverlaySettings(app)
            return
        }
        val intent = Intent(app, OverlayBubbleService::class.java)
        runCatching {
            ContextCompat.startForegroundService(app, intent)
        }.onFailure {
            Toast.makeText(app, "تعذر تشغيل الأيقونة العائمة", Toast.LENGTH_SHORT).show()
        }
    }

    fun stopFloatingBubble() {
        app.stopService(Intent(app, OverlayBubbleService::class.java))
    }

    fun launchScannerOverlay(): Boolean {
        if (!PermissionHelper.isOverlayGranted(app) || !PermissionHelper.isCameraGranted(app)) {
            return false
        }
        val intent = Intent(app, ScannerOverlayService::class.java)
        return runCatching {
            ContextCompat.startForegroundService(app, intent)
            true
        }.getOrDefault(false)
    }

    fun copyCardCode(code: String) {
        CardDispatchCoordinator.copyToClipboard(app, code, showToast = true)
    }

    fun reDispatchCardCode(code: String) {
        CardDispatchCoordinator.dispatchScannedCode(
            context = app,
            code = code,
            saveToHistory = false
        )
        CardDispatchCoordinator.openCaptivePortalOrWifi(app)
    }

    fun openCaptivePortal() {
        CardDispatchCoordinator.openCaptivePortalOrWifi(app)
    }

    fun clearPendingDispatch() {
        CardDispatchCoordinator.clearPendingCode()
    }

    fun deleteCard(id: Long) {
        viewModelScope.launch { historyRepo.deleteCard(id) }
    }

    fun clearAllHistory() {
        viewModelScope.launch { historyRepo.clearAll() }
    }
}

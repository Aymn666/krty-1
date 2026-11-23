package com.example

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.service.CardDispatchCoordinator
import com.example.utils.PermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Lightweight zero-permission Photo Picker bridge for the floating ScannerOverlayService.
 */
class GalleryPickerActivity : ComponentActivity() {

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            val state = OcrAnalyzer.scanImageUri(this@GalleryPickerActivity, uri)
            when (state) {
                is OcrScanState.Locked -> {
                    val app = applicationContext as? KartiApplication
                    val vibrate = app?.settingsDataStore?.settingsFlow?.first()?.vibrationEnabled ?: true
                    if (vibrate) {
                        PermissionHelper.triggerSuccessVibration(this@GalleryPickerActivity)
                    }
                    CardDispatchCoordinator.dispatchScannedCode(
                        context = this@GalleryPickerActivity,
                        code = state.code,
                        confidence = state.confidence
                    )
                    val accessibilityReady = PermissionHelper.isAccessibilityServiceEnabled(this@GalleryPickerActivity) ||
                        CardDispatchCoordinator.isAccessibilityConnected.value
                    if (accessibilityReady) {
                        Toast.makeText(
                            this@GalleryPickerActivity,
                            "تمت قراءة الكود: ${state.code} — جاري التعبئة التلقائية",
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        Toast.makeText(
                            this@GalleryPickerActivity,
                            "تمت قراءة ونسخ الكود: ${state.code}",
                            Toast.LENGTH_SHORT
                        ).show()
                        startActivity(
                            Intent(this@GalleryPickerActivity, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        )
                    }
                }
                is OcrScanState.Error -> {
                    Toast.makeText(this@GalleryPickerActivity, state.message, Toast.LENGTH_LONG).show()
                }
                else -> Unit
            }
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pickMediaLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }
}

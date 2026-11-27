package com.example

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.service.CardDispatchCoordinator
import com.example.ui.components.ScannedCodeReviewPanel
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.KartiTheme
import com.example.ui.theme.MidnightBg
import com.example.utils.PermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Zero-permission Photo Picker + multi-pass gallery OCR + editable review dialog
 * launched from the floating ScannerOverlayService.
 */
class GalleryPickerActivity : ComponentActivity() {

    private var isAnalyzing by mutableStateOf(true)
    private var scanResult by mutableStateOf<OcrScanState?>(null)

    private val pickMediaLauncher = registerForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            finish()
            return@registerForActivityResult
        }

        isAnalyzing = true
        scanResult = null

        lifecycleScope.launch {
            val state = OcrAnalyzer.scanImageUri(this@GalleryPickerActivity, uri)
            isAnalyzing = false
            scanResult = state

            if (state is OcrScanState.Locked) {
                val app = applicationContext as? KartiApplication
                val vibrate = app?.settingsDataStore?.settingsFlow?.first()?.vibrationEnabled ?: true
                if (vibrate) {
                    PermissionHelper.triggerSuccessVibration(this@GalleryPickerActivity)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(MidnightBg.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(MidnightBg.toArgb())
        )

        if (savedInstanceState != null) {
            isAnalyzing = savedInstanceState.getBoolean(KEY_IS_ANALYZING, false)
            val savedCode = savedInstanceState.getString(KEY_LOCKED_CODE)
            val savedError = savedInstanceState.getString(KEY_ERROR_MESSAGE)
            scanResult = when {
                !savedCode.isNullOrBlank() -> OcrScanState.Locked(
                    code = savedCode,
                    confidence = savedInstanceState.getFloat(KEY_LOCKED_CONFIDENCE, 0.95f),
                    alternatives = savedInstanceState.getStringArrayList(KEY_LOCKED_ALTS).orEmpty(),
                    sourceLabel = savedInstanceState.getString(KEY_LOCKED_SOURCE, "المعرض")
                )
                !savedError.isNullOrBlank() -> OcrScanState.Error(savedError)
                else -> null
            }
        }

        setContent {
            KartiTheme {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MidnightBg.copy(alpha = 0.88f))
                        .windowInsetsPadding(WindowInsets.statusBars)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .imePadding()
                        .padding(16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when (val current = scanResult) {
                        null -> {
                            if (isAnalyzing) {
                                Surface(
                                    shape = RoundedCornerShape(22.dp),
                                    color = DeepNavySurface,
                                    tonalElevation = 8.dp
                                ) {
                                    Column(
                                        modifier = Modifier.padding(24.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = EmeraldPrimary,
                                            modifier = Modifier.size(36.dp)
                                        )
                                        Text(
                                            text = "جاري تحليل صورة الكرت بذكاء...",
                                            style = MaterialTheme.typography.titleMedium,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                        }

                        is OcrScanState.Locked -> {
                            ScannedCodeReviewPanel(
                                initialCode = current.code,
                                confidence = current.confidence,
                                alternatives = current.alternatives,
                                sourceLabel = "المعرض",
                                rescanLabel = "صورة أخرى",
                                onSilentLogin = { finalCode ->
                                    CardDispatchCoordinator.startSilentLogin(
                                        context = this@GalleryPickerActivity,
                                        code = finalCode,
                                        confidence = current.confidence
                                    )
                                    finish()
                                },
                                onConnectDirect = { finalCode ->
                                    CardDispatchCoordinator.saveScannedCardToHistory(
                                        context = this@GalleryPickerActivity,
                                        code = finalCode,
                                        confidence = current.confidence,
                                        submitted = false
                                    )
                                    CardDispatchCoordinator.openInAppPortal(
                                        context = this@GalleryPickerActivity,
                                        autoStartScanner = false,
                                        prefillCode = finalCode,
                                        forceAutoClick = true
                                    )
                                    finish()
                                },
                                onCopyOnly = { finalCode ->
                                    CardDispatchCoordinator.saveScannedCardToHistory(
                                        context = this@GalleryPickerActivity,
                                        code = finalCode,
                                        confidence = current.confidence,
                                        submitted = false
                                    )
                                    CardDispatchCoordinator.copyToClipboard(
                                        context = this@GalleryPickerActivity,
                                        code = finalCode,
                                        showToast = true
                                    )
                                    finish()
                                },
                                onRescan = {
                                    launchPicker()
                                },
                                modifier = Modifier
                                    .widthIn(max = 520.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }

                        is OcrScanState.Error -> {
                            Surface(
                                shape = RoundedCornerShape(22.dp),
                                color = DeepNavySurface,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .widthIn(max = 460.dp)
                                    .border(1.dp, AmberWarning.copy(alpha = 0.5f), RoundedCornerShape(22.dp))
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.WarningAmber,
                                        contentDescription = null,
                                        tint = AmberWarning,
                                        modifier = Modifier.size(36.dp)
                                    )
                                    Text(
                                        text = current.message,
                                        style = MaterialTheme.typography.bodyLarge,
                                        color = Color.White,
                                        textAlign = TextAlign.Center
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Button(
                                            onClick = { launchPicker() },
                                            modifier = Modifier
                                                .weight(1f)
                                                .height(48.dp)
                                                .testTag("gallery_retry_button"),
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = EmeraldPrimary,
                                                contentColor = MidnightBg
                                            )
                                        ) {
                                            Icon(Icons.Default.PhotoLibrary, contentDescription = null, modifier = Modifier.size(18.dp))
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("اختيار صورة أخرى", fontWeight = FontWeight.Bold)
                                        }
                                        OutlinedButton(
                                            onClick = { finish() },
                                            modifier = Modifier
                                                .height(48.dp)
                                                .testTag("gallery_close_button")
                                        ) {
                                            Text("إغلاق")
                                        }
                                    }
                                }
                            }
                        }

                        is OcrScanState.Scanning -> Unit
                    }
                }
            }
        }

        if (savedInstanceState == null) {
            launchPicker()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IS_ANALYZING, isAnalyzing)
        when (val current = scanResult) {
            is OcrScanState.Locked -> {
                outState.putString(KEY_LOCKED_CODE, current.code)
                outState.putFloat(KEY_LOCKED_CONFIDENCE, current.confidence)
                outState.putStringArrayList(KEY_LOCKED_ALTS, ArrayList(current.alternatives))
                outState.putString(KEY_LOCKED_SOURCE, current.sourceLabel)
            }
            is OcrScanState.Error -> {
                outState.putString(KEY_ERROR_MESSAGE, current.message)
            }
            else -> Unit
        }
    }

    private fun launchPicker() {
        isAnalyzing = true
        scanResult = null
        pickMediaLauncher.launch(
            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
        )
    }

    companion object {
        private const val KEY_IS_ANALYZING = "key_is_analyzing"
        private const val KEY_LOCKED_CODE = "key_locked_code"
        private const val KEY_LOCKED_CONFIDENCE = "key_locked_confidence"
        private const val KEY_LOCKED_ALTS = "key_locked_alts"
        private const val KEY_LOCKED_SOURCE = "key_locked_source"
        private const val KEY_ERROR_MESSAGE = "key_error_message"
    }
}

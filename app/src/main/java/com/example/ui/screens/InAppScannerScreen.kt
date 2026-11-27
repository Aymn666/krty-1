package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.service.AccessibilityFallbackPanel
import com.example.service.CardDispatchCoordinator
import com.example.service.OcrLiveStatusBanner
import com.example.ui.components.CameraScannerViewfinderBox
import com.example.ui.components.ScannedCodeReviewPanel
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MidnightBg
import com.example.utils.PermissionHelper
import com.example.service.ScannerLaunchMode
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun InAppScannerScreen(
    initialFlashEnabled: Boolean,
    vibrationEnabled: Boolean,
    scanMode: ScannerLaunchMode = ScannerLaunchMode.REVIEW,
    onSilentSubmitted: (() -> Unit)? = null,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchEnabled by rememberSaveable { mutableStateOf(initialFlashEnabled) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    // Live camera scanning status (transient)
    var liveScanState by remember { mutableStateOf<OcrScanState>(OcrScanState.Scanning()) }

    // Extracted code state — strictly separated from camera lifecycle/cleanup so gallery & camera results persist
    val lockedCodeState = rememberSaveable { mutableStateOf<String?>(null) }
    val lockedConfidenceState = rememberSaveable { mutableFloatStateOf(0.95f) }
    val lockedAlternativesState = rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val lockedSourceLabelState = rememberSaveable { mutableStateOf("الكاميرا") }

    var vibratedCode by rememberSaveable { mutableStateOf<String?>(null) }
    var copiedFallbackCode by rememberSaveable { mutableStateOf<String?>(null) }
    val isAnalyzingGalleryState = remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val ocrAnalyzer = remember {
        OcrAnalyzer { newState ->
            // Do not allow live camera callbacks to overwrite an extracted code or active gallery scan
            if (lockedCodeState.value != null || isAnalyzingGalleryState.value) {
                return@OcrAnalyzer
            }
            when (newState) {
                is OcrScanState.Locked -> {
                    lockedCodeState.value = newState.code
                    lockedConfidenceState.floatValue = newState.confidence
                    lockedAlternativesState.value = ArrayList(newState.alternatives)
                    lockedSourceLabelState.value = newState.sourceLabel
                }
                is OcrScanState.Error -> {
                    Toast.makeText(context, newState.message, Toast.LENGTH_SHORT).show()
                    liveScanState = OcrScanState.Scanning()
                }
                is OcrScanState.Scanning -> {
                    liveScanState = newState
                }
            }
        }
    }

    LaunchedEffect(lockedCodeState.value) {
        if (lockedCodeState.value != null) {
            ocrAnalyzer.lockExternalCode()
        }
    }

    BackHandler {
        when {
            copiedFallbackCode != null -> {
                copiedFallbackCode = null
                vibratedCode = null
                lockedCodeState.value = null
                ocrAnalyzer.reset()
            }
            lockedCodeState.value != null -> {
                vibratedCode = null
                lockedCodeState.value = null
                ocrAnalyzer.reset()
            }
            else -> onClose()
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri == null) {
            if (lockedCodeState.value == null) {
                ocrAnalyzer.resumeFromGallery()
            }
            return@rememberLauncherForActivityResult
        }

        ocrAnalyzer.pauseForGallery()
        coroutineScope.launch {
            isAnalyzingGalleryState.value = true
            val result = OcrAnalyzer.scanImageUri(context, uri)
            isAnalyzingGalleryState.value = false
            when (result) {
                is OcrScanState.Locked -> {
                    ocrAnalyzer.lockExternalCode()
                    lockedCodeState.value = result.code
                    lockedConfidenceState.floatValue = result.confidence
                    lockedAlternativesState.value = ArrayList(result.alternatives)
                    lockedSourceLabelState.value = result.sourceLabel
                }
                is OcrScanState.Error -> {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                    if (lockedCodeState.value == null) {
                        ocrAnalyzer.resumeFromGallery()
                    }
                }
                is OcrScanState.Scanning -> {
                    if (lockedCodeState.value == null) {
                        ocrAnalyzer.resumeFromGallery()
                    }
                }
            }
        }
    }

    // Camera/analyzer cleanup NEVER clears or resets the extracted code
    DisposableEffect(Unit) {
        onDispose {
            ocrAnalyzer.close()
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled, boundCamera) {
        boundCamera?.cameraControl?.enableTorch(torchEnabled)
    }

    val activeLockedCode = lockedCodeState.value
    val lockedState = remember(
        activeLockedCode,
        lockedConfidenceState.floatValue,
        lockedAlternativesState.value,
        lockedSourceLabelState.value
    ) {
        activeLockedCode?.let { code ->
            OcrScanState.Locked(
                code = code,
                confidence = lockedConfidenceState.floatValue,
                alternatives = lockedAlternativesState.value,
                sourceLabel = lockedSourceLabelState.value
            )
        }
    }

    var autoActionDispatched by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(activeLockedCode, scanMode) {
        if (activeLockedCode != null && vibratedCode != activeLockedCode) {
            vibratedCode = activeLockedCode
            if (vibrationEnabled) {
                PermissionHelper.triggerSuccessVibration(context)
            }
        }
        if (activeLockedCode != null && !autoActionDispatched) {
            when (scanMode) {
                ScannerLaunchMode.SILENT_AUTO -> {
                    autoActionDispatched = true
                    CardDispatchCoordinator.startSilentLogin(
                        context = context,
                        code = activeLockedCode,
                        confidence = lockedConfidenceState.floatValue
                    )
                    (onSilentSubmitted ?: onClose).invoke()
                }
                ScannerLaunchMode.PREVIEW_AUTO -> {
                    autoActionDispatched = true
                    CardDispatchCoordinator.saveScannedCardToHistory(
                        context = context,
                        code = activeLockedCode,
                        confidence = lockedConfidenceState.floatValue,
                        submitted = false
                    )
                    CardDispatchCoordinator.openInAppPortal(
                        context = context,
                        autoStartScanner = false,
                        prefillCode = activeLockedCode,
                        forceAutoClick = true
                    )
                    onClose()
                }
                ScannerLaunchMode.REVIEW -> Unit
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        CameraScannerViewfinderBox(
            lifecycleOwner = lifecycleOwner,
            cameraExecutor = cameraExecutor,
            ocrAnalyzer = ocrAnalyzer,
            torchEnabled = torchEnabled,
            scanState = lockedState ?: liveScanState,
            onBoundCameraChanged = { boundCamera = it },
            modifier = Modifier.fillMaxSize()
        )

        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = DeepNavySurface.copy(alpha = 0.85f)
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("inapp_scanner_back")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(50),
                color = DeepNavySurface.copy(alpha = 0.85f)
            ) {
                Text(
                    text = when (scanMode) {
                        ScannerLaunchMode.SILENT_AUTO -> "مسح صامت ⚡"
                        ScannerLaunchMode.PREVIEW_AUTO -> "مسح ومعاينة 👁️"
                        ScannerLaunchMode.REVIEW -> "ماسح الكرت الذكي"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            Surface(
                shape = CircleShape,
                color = DeepNavySurface.copy(alpha = 0.85f)
            ) {
                IconButton(
                    onClick = { torchEnabled = !torchEnabled },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("inapp_scanner_flash")
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = if (torchEnabled) "إطفاء الفلاش" else "تشغيل الفلاش",
                        tint = if (torchEnabled) AmberWarning else Color.White
                    )
                }
            }
        }

        // Gallery / Burst Analyzing Overlay
        AnimatedVisibility(
            visible = isAnalyzingGalleryState.value || (liveScanState as? OcrScanState.Scanning)?.isAnalyzingPhoto == true,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.65f)),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = DeepNavySurface
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(
                            color = EmeraldPrimary,
                            modifier = Modifier.size(26.dp),
                            strokeWidth = 3.dp
                        )
                        Text(
                            text = "جاري تحليل الكرت بذكاء...",
                            style = MaterialTheme.typography.titleSmall,
                            color = Color.White
                        )
                    }
                }
            }
        }

        // Bottom Live Scanning Controls (hidden when review dialog or fallback is active)
        if (lockedState == null && copiedFallbackCode == null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                shape = RoundedCornerShape(24.dp),
                color = DeepNavySurface.copy(alpha = 0.95f)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OcrLiveStatusBanner(scanState = liveScanState)

                    val candidate = (liveScanState as? OcrScanState.Scanning)?.candidateCode

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!candidate.isNullOrBlank()) {
                                    ocrAnalyzer.lockExternalCode()
                                    lockedCodeState.value = candidate
                                    lockedConfidenceState.floatValue = 0.92f
                                    lockedAlternativesState.value = ArrayList()
                                    lockedSourceLabelState.value = "التقاط مباشر"
                                } else {
                                    ocrAnalyzer.requestBurstCapture()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                                .testTag("inapp_capture_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = MidnightBg
                            )
                        ) {
                            Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (candidate != null) "مراجعة ($candidate)" else "التقاط ذكي",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                if (!isAnalyzingGalleryState.value) {
                                    ocrAnalyzer.pauseForGallery()
                                    galleryPickerLauncher.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                }
                            },
                            enabled = !isAnalyzingGalleryState.value,
                            modifier = Modifier
                                .height(50.dp)
                                .testTag("inapp_gallery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "اختيار صورة من المعرض",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("المعرض", fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }

        // Editable Review Window OR Copied Fallback Window
        if (copiedFallbackCode != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(24.dp),
                    color = DeepNavySurface,
                    modifier = Modifier
                        .fillMaxWidth()
                        .widthIn(max = 520.dp)
                ) {
                    Box(modifier = Modifier.padding(16.dp)) {
                        AccessibilityFallbackPanel(
                            code = copiedFallbackCode!!,
                            onCopy = { CardDispatchCoordinator.copyToClipboard(context, copiedFallbackCode!!) },
                            onOpenPortal = {
                                CardDispatchCoordinator.openInAppPortal(
                                    context = context,
                                    autoStartScanner = false,
                                    prefillCode = copiedFallbackCode!!
                                )
                                onClose()
                            },
                            onEnableAccessibility = { PermissionHelper.openAccessibilitySettings(context) },
                            onRescan = {
                                copiedFallbackCode = null
                                vibratedCode = null
                                lockedCodeState.value = null
                                ocrAnalyzer.reset()
                            }
                        )
                    }
                }
            }
        } else if (lockedState != null && scanMode == ScannerLaunchMode.REVIEW) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .imePadding()
                    .padding(16.dp),
                contentAlignment = Alignment.Center
            ) {
                ScannedCodeReviewPanel(
                    initialCode = lockedState.code,
                    confidence = lockedState.confidence,
                    alternatives = lockedState.alternatives,
                    sourceLabel = lockedState.sourceLabel,
                    onSilentLogin = { finalCode ->
                        CardDispatchCoordinator.startSilentLogin(
                            context = context,
                            code = finalCode,
                            confidence = lockedState.confidence
                        )
                        (onSilentSubmitted ?: onClose).invoke()
                    },
                    onConnectDirect = { finalCode ->
                        CardDispatchCoordinator.saveScannedCardToHistory(
                            context = context,
                            code = finalCode,
                            confidence = lockedState.confidence,
                            submitted = false
                        )
                        CardDispatchCoordinator.openInAppPortal(
                            context = context,
                            autoStartScanner = false,
                            prefillCode = finalCode,
                            forceAutoClick = true
                        )
                        onClose()
                    },
                    onCopyOnly = { finalCode ->
                        CardDispatchCoordinator.saveScannedCardToHistory(
                            context = context,
                            code = finalCode,
                            confidence = lockedState.confidence,
                            submitted = false
                        )
                        CardDispatchCoordinator.copyToClipboard(context, finalCode, showToast = true)
                    },
                    onRescan = {
                        vibratedCode = null
                        lockedCodeState.value = null
                        ocrAnalyzer.reset()
                    },
                    modifier = Modifier
                        .widthIn(max = 520.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}

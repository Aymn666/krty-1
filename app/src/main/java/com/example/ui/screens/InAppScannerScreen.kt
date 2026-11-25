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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

@Composable
fun InAppScannerScreen(
    initialFlashEnabled: Boolean,
    vibrationEnabled: Boolean,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchEnabled by remember { mutableStateOf(initialFlashEnabled) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var scanState by remember { mutableStateOf<OcrScanState>(OcrScanState.Scanning()) }
    var vibratedCode by remember { mutableStateOf<String?>(null) }
    var copiedFallbackCode by remember { mutableStateOf<String?>(null) }
    var isAnalyzingGallery by remember { mutableStateOf(false) }

    val coroutineScope = rememberCoroutineScope()
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val ocrAnalyzer = remember {
        OcrAnalyzer { newState ->
            scanState = newState
        }
    }

    BackHandler {
        when {
            copiedFallbackCode != null -> {
                copiedFallbackCode = null
                vibratedCode = null
                ocrAnalyzer.reset()
            }
            scanState is OcrScanState.Locked -> {
                vibratedCode = null
                ocrAnalyzer.reset()
            }
            else -> onClose()
        }
    }

    val galleryPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                isAnalyzingGallery = true
                val result = OcrAnalyzer.scanImageUri(context, uri)
                isAnalyzingGallery = false
                if (result is OcrScanState.Error) {
                    Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                } else {
                    scanState = result
                }
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            ocrAnalyzer.close()
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled, boundCamera) {
        boundCamera?.cameraControl?.enableTorch(torchEnabled)
    }

    LaunchedEffect(scanState) {
        val current = scanState
        if (current is OcrScanState.Locked && vibratedCode != current.code) {
            vibratedCode = current.code
            if (vibrationEnabled) {
                PermissionHelper.triggerSuccessVibration(context)
            }
        } else if (current is OcrScanState.Error) {
            Toast.makeText(context, current.message, Toast.LENGTH_SHORT).show()
            ocrAnalyzer.reset()
        }
    }

    val lockedState = scanState as? OcrScanState.Locked

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
            scanState = scanState,
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
                    text = "ماسح الكرت الذكي",
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
            visible = isAnalyzingGallery || (scanState as? OcrScanState.Scanning)?.isAnalyzingPhoto == true,
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
                    OcrLiveStatusBanner(scanState = scanState)

                    val candidate = (scanState as? OcrScanState.Scanning)?.candidateCode

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                if (!candidate.isNullOrBlank()) {
                                    scanState = OcrScanState.Locked(
                                        code = candidate,
                                        confidence = 0.92f,
                                        sourceLabel = "التقاط مباشر"
                                    )
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
                                galleryPickerLauncher.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
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
                                ocrAnalyzer.reset()
                            }
                        )
                    }
                }
            }
        } else if (lockedState != null) {
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
                            prefillCode = finalCode
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
                        copiedFallbackCode = finalCode
                    },
                    onRescan = {
                        vibratedCode = null
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

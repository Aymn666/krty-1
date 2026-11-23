package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.service.AccessibilityFallbackPanel
import com.example.service.CardDispatchCoordinator
import com.example.service.OcrLiveStatusBanner
import com.example.service.ViewfinderOverlay
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.MidnightBg
import com.example.utils.PermissionHelper
import kotlinx.coroutines.delay
import java.util.concurrent.Executors

@Composable
fun InAppScannerScreen(
    initialFlashEnabled: Boolean,
    vibrationEnabled: Boolean,
    onClose: () -> Unit
) {
    BackHandler(onBack = onClose)

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var torchEnabled by remember { mutableStateOf(initialFlashEnabled) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var scanState by remember { mutableStateOf<OcrScanState>(OcrScanState.Scanning()) }
    var lockedCode by remember { mutableStateOf<String?>(null) }
    var showFallback by remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val ocrAnalyzer = remember {
        OcrAnalyzer { newState ->
            scanState = newState
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
        if (current is OcrScanState.Locked && lockedCode == null) {
            lockedCode = current.code
            if (vibrationEnabled) {
                PermissionHelper.triggerSuccessVibration(context)
            }
            CardDispatchCoordinator.dispatchScannedCode(
                context = context,
                code = current.code,
                confidence = current.confidence
            )
            val accessibilityReady = PermissionHelper.isAccessibilityServiceEnabled(context) ||
                CardDispatchCoordinator.isAccessibilityConnected.value
            if (accessibilityReady) {
                delay(550L)
                CardDispatchCoordinator.openCaptivePortalOrWifi(context)
                onClose()
            } else {
                showFallback = true
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        if (!showFallback) {
            AndroidView(
                factory = { ctx ->
                    val previewView = PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }
                    val providerFuture = ProcessCameraProvider.getInstance(ctx)
                    providerFuture.addListener({
                        val provider = providerFuture.get()
                        val preview = Preview.Builder().build().also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }
                        val analysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .build()
                            .also {
                                it.setAnalyzer(cameraExecutor, ocrAnalyzer)
                            }
                        runCatching {
                            provider.unbindAll()
                            val camera = provider.bindToLifecycle(
                                lifecycleOwner,
                                CameraSelector.DEFAULT_BACK_CAMERA,
                                preview,
                                analysis
                            )
                            boundCamera = camera
                            camera.cameraControl.enableTorch(torchEnabled)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                    previewView
                },
                modifier = Modifier.fillMaxSize()
            )

            ViewfinderOverlay(
                isLocked = scanState is OcrScanState.Locked,
                isLowLight = (scanState as? OcrScanState.Scanning)?.isLowLight == true,
                modifier = Modifier.fillMaxSize()
            )
        }

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
                    text = "ماسح الكرت المباشر",
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

        // Bottom Controls / Fallback
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
                if (showFallback && lockedCode != null) {
                    AccessibilityFallbackPanel(
                        code = lockedCode!!,
                        onCopy = { CardDispatchCoordinator.copyToClipboard(context, lockedCode!!) },
                        onOpenPortal = {
                            CardDispatchCoordinator.openCaptivePortalOrWifi(context)
                            onClose()
                        },
                        onEnableAccessibility = { PermissionHelper.openAccessibilitySettings(context) },
                        onRescan = {
                            lockedCode = null
                            showFallback = false
                            ocrAnalyzer.reset()
                        }
                    )
                } else {
                    OcrLiveStatusBanner(scanState = scanState)

                    val candidate = (scanState as? OcrScanState.Scanning)?.candidateCode
                        ?: (scanState as? OcrScanState.Locked)?.code

                    Button(
                        onClick = {
                            if (!candidate.isNullOrBlank()) {
                                scanState = OcrScanState.Locked(candidate, 0.95f)
                            } else {
                                ocrAnalyzer.reset()
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("inapp_capture_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = EmeraldPrimary,
                            contentColor = MidnightBg
                        )
                    ) {
                        Icon(Icons.Default.CameraAlt, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = if (candidate != null) "اعتماد الرقم ($candidate)" else "التقاط وإعادة التحليل",
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

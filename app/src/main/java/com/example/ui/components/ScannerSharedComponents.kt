package com.example.ui.components

import android.annotation.SuppressLint
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.service.ViewfinderOverlay
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavyCard
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.MidnightBg
import kotlinx.coroutines.delay
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

/**
 * High-resolution CameraX viewfinder with tap-to-focus, automatic center focus,
 * and quick zoom presets (1x, 1.5x, 2x, 3x) for crisp distant/small card OCR.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraScannerViewfinderBox(
    lifecycleOwner: LifecycleOwner,
    cameraExecutor: ExecutorService,
    ocrAnalyzer: OcrAnalyzer,
    torchEnabled: Boolean,
    scanState: OcrScanState,
    onBoundCameraChanged: (Camera?) -> Unit,
    modifier: Modifier = Modifier
) {
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var selectedZoom by remember { mutableFloatStateOf(1f) }
    var maxZoom by remember { mutableFloatStateOf(3f) }
    var focusTapOffset by remember { mutableStateOf<Offset?>(null) }

    LaunchedEffect(focusTapOffset) {
        if (focusTapOffset != null) {
            delay(900L)
            focusTapOffset = null
        }
    }

    LaunchedEffect(selectedZoom, boundCamera) {
        val cam = boundCamera ?: return@LaunchedEffect
        val clamped = selectedZoom.coerceIn(1f, maxZoom)
        cam.cameraControl.setZoomRatio(clamped)
    }

    LaunchedEffect(torchEnabled, boundCamera) {
        boundCamera?.cameraControl?.enableTorch(torchEnabled)
    }

    Box(modifier = modifier.background(Color.Black)) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }

                previewView.setOnTouchListener { view, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val cam = boundCamera
                        if (cam != null) {
                            val point = previewView.meteringPointFactory.createPoint(event.x, event.y)
                            val action = FocusMeteringAction.Builder(
                                point,
                                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                            )
                                .setAutoCancelDuration(4, TimeUnit.SECONDS)
                                .build()
                            cam.cameraControl.startFocusAndMetering(action)
                            focusTapOffset = Offset(event.x, event.y)
                        }
                        view.performClick()
                    }
                    true
                }

                val providerFuture = ProcessCameraProvider.getInstance(ctx)
                providerFuture.addListener({
                    val provider = providerFuture.get()
                    val resolutionSelector = ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(1920, 1080),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                            )
                        )
                        .build()

                    val preview = Preview.Builder()
                        .setResolutionSelector(resolutionSelector)
                        .build()
                        .also {
                            it.surfaceProvider = previewView.surfaceProvider
                        }

                    val analysis = ImageAnalysis.Builder()
                        .setResolutionSelector(resolutionSelector)
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
                        onBoundCameraChanged(camera)
                        maxZoom = camera.cameraInfo.zoomState.value?.maxZoomRatio?.coerceAtMost(4f) ?: 3f
                        camera.cameraControl.enableTorch(torchEnabled)

                        // Trigger initial center focus for crisp macro/card capture
                        previewView.post {
                            if (previewView.width > 0 && previewView.height > 0) {
                                val centerPoint = previewView.meteringPointFactory.createPoint(
                                    previewView.width / 2f,
                                    previewView.height / 2f
                                )
                                val action = FocusMeteringAction.Builder(
                                    centerPoint,
                                    FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                                )
                                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                                    .build()
                                camera.cameraControl.startFocusAndMetering(action)
                            }
                        }
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

        // Tap-to-focus indicator ring
        focusTapOffset?.let { tap ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(
                    color = EmeraldPrimary.copy(alpha = 0.9f),
                    radius = 28.dp.toPx(),
                    center = tap,
                    style = Stroke(width = 2.5.dp.toPx())
                )
            }
        }

        // Quick zoom pills for distant / small cards
        val zoomOptions = remember(maxZoom) {
            listOf(1f, 1.5f, 2f, 3f).filter { it <= maxZoom + 0.05f }
        }
        if (zoomOptions.size > 1) {
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MidnightBg.copy(alpha = 0.72f))
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                zoomOptions.forEach { zoom ->
                    val isSelected = kotlin.math.abs(selectedZoom - zoom) < 0.15f
                    val label = if (zoom == 1.5f) "1.5x" else "${zoom.toInt()}x"
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (isSelected) EmeraldPrimary else Color.Transparent)
                            .clickable { selectedZoom = zoom }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = if (isSelected) MidnightBg else Color.White
                        )
                    }
                }
            }
        }
    }
}

/**
 * Editable confirmation panel shown after OCR extracts a card code (camera or gallery),
 * letting the user verify/edit the code or connect directly.
 */
@Composable
fun ScannedCodeReviewPanel(
    initialCode: String,
    confidence: Float,
    alternatives: List<String>,
    sourceLabel: String,
    rescanLabel: String = "إعادة المسح",
    onConnectDirect: (String) -> Unit,
    onCopyOnly: (String) -> Unit,
    onRescan: () -> Unit,
    modifier: Modifier = Modifier
) {
    var editedCode by remember(initialCode) { mutableStateOf(initialCode) }
    val trimmedCode = editedCode.trim()
    val confidencePct = (confidence.coerceIn(0f, 1f) * 100f).roundToInt()

    val codeFormatDescription = remember(trimmedCode) {
        when {
            trimmedCode.isEmpty() -> "أدخل رمز الكرت"
            trimmedCode.all { it.isDigit() } -> "${trimmedCode.length} أرقام"
            trimmedCode.any { it.isDigit() } && trimmedCode.any { it.isLetter() } ->
                "${trimmedCode.length} أحرف وأرقام"
            else -> "${trimmedCode.length} خانات"
        }
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(1.dp, EmeraldPrimary.copy(alpha = 0.55f), RoundedCornerShape(22.dp)),
        shape = RoundedCornerShape(22.dp),
        color = DeepNavySurface,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = EmeraldPrimary,
                        modifier = Modifier.size(22.dp)
                    )
                    Column {
                        Text(
                            text = "مراجعة رقم الكرت المستخرج",
                            style = MaterialTheme.typography.titleMedium,
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "المصدر: $sourceLabel • دقة التعرّف $confidencePct%",
                            style = MaterialTheme.typography.labelSmall,
                            color = CyanSecondary
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = ElevatedNavyCard
                ) {
                    Text(
                        text = codeFormatDescription,
                        style = MaterialTheme.typography.labelSmall,
                        color = EmeraldPrimary,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Editable Monospace Code Input
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                OutlinedTextField(
                    value = editedCode,
                    onValueChange = { next ->
                        editedCode = next.filter { !it.isWhitespace() }.take(24)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("review_code_input"),
                    singleLine = true,
                    textStyle = TextStyle(
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 23.sp,
                        letterSpacing = 1.8.sp,
                        color = Color.White
                    ),
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "تعديل الرقم",
                            tint = CyanSecondary
                        )
                    },
                    trailingIcon = {
                        if (editedCode.isNotEmpty()) {
                            IconButton(
                                onClick = { editedCode = "" },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "مسح النص",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Ascii,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (trimmedCode.length >= 4) {
                                onConnectDirect(trimmedCode)
                            }
                        }
                    ),
                    shape = RoundedCornerShape(16.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = EmeraldPrimary,
                        unfocusedBorderColor = CyanSecondary.copy(alpha = 0.55f),
                        focusedContainerColor = ElevatedNavyCard,
                        unfocusedContainerColor = ElevatedNavyCard,
                        cursorColor = EmeraldPrimary
                    )
                )
            }

            Text(
                text = "يمكنك تعديل أي رقم أو حرف يدوياً قبل الاتصال المباشر.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Optional alternative candidates detected on the card
            AnimatedVisibility(
                visible = alternatives.isNotEmpty(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "خيارات أخرى رُصدت على البطاقة:",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        alternatives.forEach { alt ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = if (trimmedCode == alt) EmeraldPrimary.copy(alpha = 0.2f) else ElevatedNavyCard,
                                modifier = Modifier
                                    .border(
                                        1.dp,
                                        if (trimmedCode == alt) EmeraldPrimary else Color.White.copy(alpha = 0.12f),
                                        RoundedCornerShape(12.dp)
                                    )
                                    .clickable { editedCode = alt }
                            ) {
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    Text(
                                        text = alt,
                                        fontFamily = JetBrainsMonoFontFamily,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // Primary Direct Connect CTA
            Button(
                onClick = {
                    if (trimmedCode.length >= 4) {
                        onConnectDirect(trimmedCode)
                    }
                },
                enabled = trimmedCode.length >= 4,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .testTag("review_connect_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = MidnightBg
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "اتصال مباشر",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }

            // Secondary Row: Copy & Rescan
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                FilledTonalButton(
                    onClick = {
                        if (trimmedCode.isNotEmpty()) {
                            onCopyOnly(trimmedCode)
                        }
                    },
                    enabled = trimmedCode.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("review_copy_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ContentCopy,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نسخ فقط", fontWeight = FontWeight.SemiBold)
                }

                OutlinedButton(
                    onClick = onRescan,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("review_rescan_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(rescanLabel, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

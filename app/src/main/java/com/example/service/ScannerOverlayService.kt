package com.example.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.example.GalleryPickerActivity
import com.example.KartiApplication
import com.example.MainActivity
import com.example.R
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavyCard
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KartiTheme
import com.example.ui.theme.MidnightBg
import com.example.utils.PermissionHelper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

class ScannerOverlayService :
    LifecycleService(),
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = store

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()

        startScannerForeground()

        if (!PermissionHelper.isOverlayGranted(this) || !PermissionHelper.isCameraGranted(this)) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            stopSelf()
            return
        }

        CardDispatchCoordinator.setScannerOverlayRunning(true)
        lifecycleScope.launch {
            val app = applicationContext as? KartiApplication
            val settings = app?.settingsDataStore?.settingsFlow?.first()
            showScannerWindow(
                initialTorch = settings?.autoFlashEnabled ?: true,
                vibrationEnabled = settings?.vibrationEnabled ?: true
            )
        }
    }

    private fun startScannerForeground() {
        val openAppIntent = PendingIntent.getActivity(
            this,
            200,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, KartiApplication.CHANNEL_SCANNER)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.scanner_notif_title))
            .setContentText(getString(R.string.scanner_notif_text))
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun showScannerWindow(initialTorch: Boolean, vibrationEnabled: Boolean) {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val view = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@ScannerOverlayService)
            setViewTreeViewModelStoreOwner(this@ScannerOverlayService)
            setViewTreeSavedStateRegistryOwner(this@ScannerOverlayService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                KartiTheme {
                    ScannerOverlayContent(
                        initialTorchEnabled = initialTorch,
                        vibrationEnabled = vibrationEnabled,
                        lifecycleOwner = this@ScannerOverlayService,
                        onClose = { closeOverlayImmediately() },
                        onCodeLockedAndAutoFillReady = { code, confidence ->
                            mainHandler.postDelayed({
                                closeOverlayImmediately()
                                CardDispatchCoordinator.dispatchScannedCode(
                                    context = this@ScannerOverlayService,
                                    code = code,
                                    confidence = confidence
                                )
                            }, 350L)
                        }
                    )
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
        }

        composeView = view
        runCatching { windowManager?.addView(view, params) }
    }

    private fun closeOverlayImmediately() {
        mainHandler.removeCallbacksAndMessages(null)
        composeView?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
        }
        composeView = null
        stopSelf()
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        CardDispatchCoordinator.setScannerOverlayRunning(false)
        composeView?.let { view ->
            runCatching { windowManager?.removeViewImmediate(view) }
        }
        composeView = null
        store.clear()
        super.onDestroy()
    }

    companion object {
        private const val NOTIFICATION_ID = 4002
    }
}

@Composable
fun ScannerOverlayContent(
    initialTorchEnabled: Boolean,
    vibrationEnabled: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onClose: () -> Unit,
    onCodeLockedAndAutoFillReady: (String, Float) -> Unit
) {
    val context = LocalContext.current
    var torchEnabled by remember { mutableStateOf(initialTorchEnabled) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var scanState by remember { mutableStateOf<OcrScanState>(OcrScanState.Scanning()) }
    var showAccessibilityFallback by remember { mutableStateOf(false) }
    var lockedCode by remember { mutableStateOf<String?>(null) }

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
            val accessibilityReady = PermissionHelper.isAccessibilityServiceEnabled(context) ||
                CardDispatchCoordinator.isAccessibilityConnected.value
            if (accessibilityReady) {
                onCodeLockedAndAutoFillReady(current.code, current.confidence)
            } else {
                CardDispatchCoordinator.dispatchScannedCode(
                    context = context,
                    code = current.code,
                    confidence = current.confidence
                )
                showAccessibilityFallback = true
            }
        }
    }

    val detectedNetwork = CardDispatchCoordinator.detectedNetworkName.value

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.72f))
            .padding(horizontal = 16.dp, vertical = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, EmeraldPrimary.copy(alpha = 0.35f), RoundedCornerShape(24.dp)),
            shape = RoundedCornerShape(24.dp),
            color = DeepNavySurface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Header Row
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
                            imageVector = Icons.Default.Wifi,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(22.dp)
                        )
                        Column {
                            Text(
                                text = "الماسح الذكي لكرت الواي فاي",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (detectedNetwork.isNotBlank()) {
                                Text(
                                    text = detectedNetwork,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanSecondary
                                )
                            }
                        }
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = { torchEnabled = !torchEnabled },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("overlay_flash_toggle")
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = if (torchEnabled) "إطفاء الفلاش" else "تشغيل الفلاش",
                                tint = if (torchEnabled) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        IconButton(
                            onClick = onClose,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("overlay_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق الماسح",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (showAccessibilityFallback && lockedCode != null) {
                    AccessibilityFallbackPanel(
                        code = lockedCode!!,
                        onCopy = { CardDispatchCoordinator.copyToClipboard(context, lockedCode!!) },
                        onOpenPortal = {
                            CardDispatchCoordinator.openCaptivePortalOrWifi(context)
                            onClose()
                        },
                        onEnableAccessibility = {
                            PermissionHelper.openAccessibilitySettings(context)
                        },
                        onRescan = {
                            lockedCode = null
                            showAccessibilityFallback = false
                            ocrAnalyzer.reset()
                        }
                    )
                } else {
                    // Camera Viewfinder Box
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(250.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(Color.Black)
                    ) {
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

                    // Live OCR Status & Code Display
                    OcrLiveStatusBanner(scanState = scanState)

                    // Bottom Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
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
                                .weight(1f)
                                .height(48.dp)
                                .testTag("overlay_capture_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = MidnightBg
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.CameraAlt,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (candidate != null) "تأكيد" else "التقاط",
                                fontWeight = FontWeight.Bold
                            )
                        }

                        FilledTonalButton(
                            onClick = {
                                onClose()
                                context.startActivity(
                                    Intent(context, GalleryPickerActivity::class.java)
                                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                )
                            },
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("overlay_gallery_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.PhotoLibrary,
                                contentDescription = "اختيار صورة من المعرض",
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("المعرض", fontWeight = FontWeight.SemiBold)
                        }

                        OutlinedButton(
                            onClick = onClose,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("overlay_cancel_button")
                        ) {
                            Text("إغلاق")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun ViewfinderOverlay(
    isLocked: Boolean,
    isLowLight: Boolean,
    modifier: Modifier = Modifier
) {
    val cornerColor by animateColorAsState(
        targetValue = when {
            isLocked -> EmeraldPrimary
            isLowLight -> AmberWarning
            else -> CyanSecondary
        },
        label = "cornerColor"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "scanBeam")
    val beamProgress by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "beamProgress"
    )

    Canvas(modifier = modifier) {
        val frameW = size.width * 0.80f
        val frameH = size.height * 0.50f
        val left = (size.width - frameW) / 2f
        val top = (size.height - frameH) / 2f
        val right = left + frameW
        val bottom = top + frameH

        // Dimmed surroundings
        drawRect(
            color = Color.Black.copy(alpha = 0.42f),
            topLeft = Offset.Zero,
            size = Size(size.width, top)
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.42f),
            topLeft = Offset(0f, bottom),
            size = Size(size.width, size.height - bottom)
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.42f),
            topLeft = Offset(0f, top),
            size = Size(left, frameH)
        )
        drawRect(
            color = Color.Black.copy(alpha = 0.42f),
            topLeft = Offset(right, top),
            size = Size(size.width - right, frameH)
        )

        // Subtle inner border
        drawRoundRect(
            color = cornerColor.copy(alpha = 0.25f),
            topLeft = Offset(left, top),
            size = Size(frameW, frameH),
            cornerRadius = CornerRadius(14.dp.toPx(), 14.dp.toPx())
        )

        // Animated laser scan line when scanning
        if (!isLocked) {
            val y = top + 12f + (frameH - 24f) * beamProgress
            drawLine(
                brush = Brush.horizontalGradient(
                    colors = listOf(
                        Color.Transparent,
                        cornerColor.copy(alpha = 0.9f),
                        Color.Transparent
                    ),
                    startX = left,
                    endX = right
                ),
                start = Offset(left + 10f, y),
                end = Offset(right - 10f, y),
                strokeWidth = 3.dp.toPx()
            )
        }

        // Glowing corner brackets
        val stroke = 4.5.dp.toPx()
        val cornerLen = 26.dp.toPx()

        // Top-Left
        drawLine(cornerColor, Offset(left, top), Offset(left + cornerLen, top), stroke, StrokeCap.Round)
        drawLine(cornerColor, Offset(left, top), Offset(left, top + cornerLen), stroke, StrokeCap.Round)
        // Top-Right
        drawLine(cornerColor, Offset(right, top), Offset(right - cornerLen, top), stroke, StrokeCap.Round)
        drawLine(cornerColor, Offset(right, top), Offset(right, top + cornerLen), stroke, StrokeCap.Round)
        // Bottom-Left
        drawLine(cornerColor, Offset(left, bottom), Offset(left + cornerLen, bottom), stroke, StrokeCap.Round)
        drawLine(cornerColor, Offset(left, bottom), Offset(left, bottom - cornerLen), stroke, StrokeCap.Round)
        // Bottom-Right
        drawLine(cornerColor, Offset(right, bottom), Offset(right - cornerLen, bottom), stroke, StrokeCap.Round)
        drawLine(cornerColor, Offset(right, bottom), Offset(right, bottom - cornerLen), stroke, StrokeCap.Round)
    }
}

@Composable
fun OcrLiveStatusBanner(scanState: OcrScanState) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = ElevatedNavyCard
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when (scanState) {
                is OcrScanState.Locked -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = EmeraldPrimary,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            text = "تم التقاط الكود بنجاح! جاري الإدخال...",
                            color = EmeraldPrimary,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Text(
                            text = scanState.code,
                            fontFamily = JetBrainsMonoFontFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            letterSpacing = 2.sp,
                            color = EmeraldPrimary
                        )
                    }
                }

                is OcrScanState.Scanning -> {
                    val candidate = scanState.candidateCode
                    if (candidate != null) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = candidate,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 22.sp,
                                letterSpacing = 1.5.sp,
                                color = CyanSecondary
                            )
                        }
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (scanState.isLowLight) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = AmberWarning,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                        Text(
                            text = scanState.statusMessage,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (scanState.isLowLight) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                is OcrScanState.Error -> {
                    Text(
                        text = scanState.message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

@Composable
fun AccessibilityFallbackPanel(
    code: String,
    onCopy: () -> Unit,
    onOpenPortal: () -> Unit,
    onEnableAccessibility: () -> Unit,
    onRescan: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            color = ElevatedNavyCard
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = "تمت قراءة رقم الكرت ونسخه للحافظة",
                    style = MaterialTheme.typography.labelLarge,
                    color = EmeraldPrimary
                )
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Text(
                        text = code,
                        fontFamily = JetBrainsMonoFontFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 26.sp,
                        letterSpacing = 2.sp,
                        color = Color.White
                    )
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = AmberWarning.copy(alpha = 0.12f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.AccessibilityNew,
                        contentDescription = null,
                        tint = AmberWarning,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = "خدمة الملء التلقائي غير مفعّلة",
                        style = MaterialTheme.typography.labelLarge,
                        color = AmberWarning
                    )
                }
                Text(
                    text = "لتعبئة الكرت وضغط تسجيل الدخول تلقائياً: افتح إعدادات إمكانية الوصول ← التطبيقات المثبتة ← كرتي ← تفعيل.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = onCopy,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("fallback_copy_button"),
                colors = ButtonDefaults.buttonColors(
                    containerColor = EmeraldPrimary,
                    contentColor = MidnightBg
                )
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("نسخ الكود", fontWeight = FontWeight.Bold)
            }

            FilledTonalButton(
                onClick = onOpenPortal,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("fallback_open_portal_button")
            ) {
                Icon(Icons.Default.OpenInBrowser, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("فتح البوابة")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onEnableAccessibility,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
            ) {
                Text("تفعيل التلقائي")
            }
            OutlinedButton(
                onClick = onRescan,
                modifier = Modifier
                    .height(48.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "إعادة المسح", modifier = Modifier.size(18.dp))
            }
        }
    }
}

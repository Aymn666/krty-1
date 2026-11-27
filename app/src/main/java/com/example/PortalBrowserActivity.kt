package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Message
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.local.KartiSettings
import com.example.ocr.OcrAnalyzer
import com.example.ocr.OcrScanState
import com.example.service.CardDispatchCoordinator
import com.example.service.OcrLiveStatusBanner
import com.example.service.OverlayBubbleService
import com.example.ui.components.CameraScannerViewfinderBox
import com.example.ui.components.ScannedCodeReviewPanel
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanSecondary
import com.example.ui.theme.DeepNavySurface
import com.example.ui.theme.ElevatedNavyCard
import com.example.ui.theme.EmeraldPrimary
import com.example.ui.theme.JetBrainsMonoFontFamily
import com.example.ui.theme.KartiTheme
import com.example.ui.theme.MidnightBg
import com.example.utils.PermissionHelper
import com.example.utils.WifiPortalHelper
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.Executors

class PortalBrowserActivity : ComponentActivity() {

    private var autoStartScannerState by mutableStateOf(true)
    private var prefillCodeState by mutableStateOf<String?>(null)
    private var networkNameState by mutableStateOf("")
    private var autoSubmitOnLockState by mutableStateOf(false)
    private var forceAutoClickState by mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(DeepNavySurface.toArgb()),
            navigationBarStyle = SystemBarStyle.dark(DeepNavySurface.toArgb())
        )
        applyLaunchIntent(intent)
        WifiPortalHelper.bindToWifiNetwork(this)

        setContent {
            val app = applicationContext as KartiApplication
            val settings by app.settingsDataStore.settingsFlow.collectAsStateWithLifecycle(
                initialValue = KartiSettings()
            )
            KartiTheme {
                PortalBrowserScreen(
                    initialShowScanner = autoStartScannerState,
                    initialPrefillCode = prefillCodeState,
                    initialAutoSubmitOnLock = autoSubmitOnLockState,
                    initialForceAutoClick = forceAutoClickState,
                    networkName = networkNameState,
                    settings = settings,
                    onConsumeScannerTrigger = { autoStartScannerState = false },
                    onConsumePrefillCode = { prefillCodeState = null },
                    onClosePortal = { finish() }
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyLaunchIntent(intent)
        WifiPortalHelper.bindToWifiNetwork(this)
    }

    override fun onStart() {
        super.onStart()
        CardDispatchCoordinator.setPortalBrowserOpen(true)
    }

    override fun onStop() {
        CardDispatchCoordinator.setPortalBrowserOpen(false)
        super.onStop()
    }

    override fun onDestroy() {
        CardDispatchCoordinator.setPortalBrowserOpen(false)
        WifiPortalHelper.unbindProcessNetwork(this)
        super.onDestroy()
    }

    private fun applyLaunchIntent(intent: Intent?) {
        autoStartScannerState = intent?.getBooleanExtra(EXTRA_AUTO_START_SCANNER, false) == true
        autoSubmitOnLockState = intent?.getBooleanExtra(EXTRA_AUTO_SUBMIT_ON_LOCK, false) == true
        forceAutoClickState = intent?.getBooleanExtra(EXTRA_FORCE_AUTO_CLICK, false) == true
        prefillCodeState = intent?.getStringExtra(EXTRA_PREFILL_CODE)?.takeIf { it.isNotBlank() }
        val extraNet = intent?.getStringExtra(EXTRA_NETWORK_NAME).orEmpty()
        networkNameState = extraNet.ifBlank { CardDispatchCoordinator.detectedNetworkName.value }
    }

    companion object {
        const val EXTRA_AUTO_START_SCANNER = "extra_auto_start_scanner"
        const val EXTRA_PREFILL_CODE = "extra_prefill_code"
        const val EXTRA_NETWORK_NAME = "extra_network_name"
        const val EXTRA_AUTO_SUBMIT_ON_LOCK = "extra_auto_submit_on_lock"
        const val EXTRA_FORCE_AUTO_CLICK = "extra_force_auto_click"
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PortalBrowserScreen(
    initialShowScanner: Boolean,
    initialPrefillCode: String?,
    initialAutoSubmitOnLock: Boolean,
    initialForceAutoClick: Boolean,
    networkName: String,
    settings: KartiSettings,
    onConsumeScannerTrigger: () -> Unit,
    onConsumePrefillCode: () -> Unit,
    onClosePortal: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val startGatewayUrl = remember { WifiPortalHelper.resolvePortalStartUrl(context) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var currentUrl by remember { mutableStateOf(startGatewayUrl) }
    var initialLoginUrl by remember { mutableStateOf<String?>(null) }
    var isPageLoading by remember { mutableStateOf(true) }
    var loadProgress by remember { mutableIntStateOf(15) }
    var usedProbeFallback by remember { mutableStateOf(false) }
    var loadErrorMessage by remember { mutableStateOf<String?>(null) }

    var showScannerOverlay by remember { mutableStateOf(initialShowScanner) }
    var autoSubmitOnLock by remember { mutableStateOf(initialAutoSubmitOnLock) }
    var forceAutoClick by remember { mutableStateOf(initialForceAutoClick) }
    var pendingInjectionCode by remember { mutableStateOf(initialPrefillCode) }
    var injectionAttempts by remember { mutableIntStateOf(0) }
    var hasSubmittedCode by remember { mutableStateOf(false) }
    var lastSubmittedCode by remember { mutableStateOf<String?>(null) }
    var isOnBalancePage by remember { mutableStateOf(false) }
    var balancePromptDismissed by remember { mutableStateOf(false) }
    var portalErrorDetected by remember { mutableStateOf(false) }
    var statusBannerText by remember { mutableStateOf<String?>(null) }

    var hasCameraPermission by remember {
        mutableStateOf(PermissionHelper.isCameraGranted(context))
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "يمكنك اختيار صورة الكرت من المعرض أو منح صلاحية الكاميرا", Toast.LENGTH_SHORT).show()
        }
    }

    LaunchedEffect(initialShowScanner, initialAutoSubmitOnLock, initialForceAutoClick) {
        autoSubmitOnLock = initialAutoSubmitOnLock
        if (initialForceAutoClick) {
            forceAutoClick = true
        }
        if (initialShowScanner) {
            showScannerOverlay = true
            onConsumeScannerTrigger()
        }
    }

    LaunchedEffect(initialPrefillCode) {
        if (!initialPrefillCode.isNullOrBlank()) {
            pendingInjectionCode = initialPrefillCode
            injectionAttempts = 0
            portalErrorDetected = false
            isOnBalancePage = false
            balancePromptDismissed = false
            onConsumePrefillCode()
        }
    }

    fun evaluatePortalState(view: WebView?) {
        val wv = view ?: return
        wv.evaluateJavascript(WifiPortalHelper.buildBalancePageCheckScript()) { raw ->
            val cleaned = raw?.trim()?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    runCatching { JSONObject("{\"v\":$it}").getString("v") }.getOrDefault(it)
                } else it
            } ?: return@evaluateJavascript

            val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return@evaluateJavascript
            val hasLoginInput = json.optBoolean("hasLoginInput", true)
            val hasBalanceKeywords = json.optBoolean("hasBalanceKeywords", false)
            val hasErrorKeywords = json.optBoolean("hasErrorKeywords", false)
            val extractedError = json.optString("errorMessage", "").trim()
            val pageUrl = json.optString("url", currentUrl)

            if (hasErrorKeywords && hasLoginInput && hasSubmittedCode) {
                portalErrorDetected = true
                isOnBalancePage = false
                statusBannerText = extractedError.ifBlank {
                    "لم يتم قبول الكود — تأكد من الرقم أو امسح كرتاً آخر"
                }
                return@evaluateJavascript
            }

            val urlLooksLikeStatus = WifiPortalHelper.isBalanceOrStatusUrl(pageUrl)
            val transitionedAwayFromLogin = hasSubmittedCode && !hasLoginInput && !hasErrorKeywords &&
                (hasBalanceKeywords || urlLooksLikeStatus ||
                    (!initialLoginUrl.isNullOrBlank() && !pageUrl.equals(initialLoginUrl, ignoreCase = true)))

            if (urlLooksLikeStatus || transitionedAwayFromLogin) {
                isOnBalancePage = true
                portalErrorDetected = false
                statusBannerText = null
                lastSubmittedCode?.let { code ->
                    CardDispatchCoordinator.markPortalLoginSuccess(context, code)
                }
                // Stop floating bubble once connected to balance/status page
                context.stopService(Intent(context, OverlayBubbleService::class.java))
            }
        }
    }

    fun tryInjectPendingCode(view: WebView?) {
        val code = pendingInjectionCode ?: return
        val wv = view ?: return
        if (injectionAttempts >= 3) return

        if (!forceAutoClick && !settings.autoFillEnabled) {
            pendingInjectionCode = null
            CardDispatchCoordinator.copyToClipboard(context, code, showToast = false)
            statusBannerText = "التعبئة التلقائية متوقفة — تم نسخ الكود ($code)"
            return
        }

        injectionAttempts++
        val script = WifiPortalHelper.buildAutoFillAndSubmitScript(
            code = code,
            autoClick = forceAutoClick || settings.autoClickEnabled
        )
        wv.evaluateJavascript(script) { raw ->
            val cleaned = raw?.trim()?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    runCatching { JSONObject("{\"v\":$it}").getString("v") }.getOrDefault(it)
                } else it
            } ?: return@evaluateJavascript

            val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return@evaluateJavascript
            val filled = json.optBoolean("filled", false)
            val submitted = json.optBoolean("submitted", false)

            if (filled) {
                pendingInjectionCode = null
                lastSubmittedCode = code
                portalErrorDetected = false
                if (submitted) {
                    hasSubmittedCode = true
                    statusBannerText = "تم إدخال الكود ($code) والضغط على زر الدخول..."
                    coroutineScope.launch {
                        delay(650L)
                        evaluatePortalState(wv)
                        delay(800L)
                        if (!isOnBalancePage && !portalErrorDetected) {
                            evaluatePortalState(wv)
                        }
                    }
                } else {
                    statusBannerText = "تم تعبئة الكود ($code) — اضغط دخول من الصفحة"
                }
            } else if (!isPageLoading && injectionAttempts >= 3) {
                pendingInjectionCode = null
                CardDispatchCoordinator.copyToClipboard(context, code, showToast = false)
                statusBannerText = "لم يُعثر على حقل الإدخال تلقائياً — تم نسخ الكود ($code)"
            }
        }
    }

    // Attempt immediate injection if page is already loaded when code arrives
    LaunchedEffect(pendingInjectionCode, isPageLoading) {
        val code = pendingInjectionCode
        if (!code.isNullOrBlank() && !isPageLoading && webViewRef != null) {
            delay(30L)
            tryInjectPendingCode(webViewRef)
        }
    }

    BackHandler {
        when {
            showScannerOverlay -> showScannerOverlay = false
            webViewRef?.canGoBack() == true -> webViewRef?.goBack()
            else -> onClosePortal()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MidnightBg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Portal Control Bar
            Surface(
                color = DeepNavySurface,
                tonalElevation = 4.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars)) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        IconButton(
                            onClick = onClosePortal,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("portal_close_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "إغلاق البوابة",
                                tint = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Wifi,
                                    contentDescription = null,
                                    tint = if (isOnBalancePage) EmeraldPrimary else CyanSecondary,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = networkName.ifBlank { "بوابة الشبكة" },
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Text(
                                text = if (isOnBalancePage) "صفحة الرصيد ومعلومات الاشتراك" else currentUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnBalancePage) EmeraldPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        FilledTonalButton(
                            onClick = { showScannerOverlay = true },
                            modifier = Modifier
                                .height(40.dp)
                                .testTag("portal_rescan_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.QrCodeScanner,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مسح كرت", style = MaterialTheme.typography.labelMedium)
                        }

                        IconButton(
                            onClick = {
                                loadErrorMessage = null
                                webViewRef?.reload()
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("portal_refresh_button")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "تحديث الصفحة",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (isPageLoading) {
                        LinearProgressIndicator(
                            progress = { (loadProgress / 100f).coerceIn(0.05f, 1f) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp),
                            color = EmeraldPrimary,
                            trackColor = ElevatedNavyCard
                        )
                    }
                }
            }

            // Status / Feedback Strip
            AnimatedVisibility(visible = statusBannerText != null) {
                Surface(
                    color = if (portalErrorDetected) AmberWarning.copy(alpha = 0.16f) else ElevatedNavyCard,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = if (portalErrorDetected) Icons.Default.WarningAmber else Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (portalErrorDetected) AmberWarning else EmeraldPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(
                            text = statusBannerText.orEmpty(),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        if (portalErrorDetected) {
                            FilledTonalButton(
                                onClick = {
                                    portalErrorDetected = false
                                    showScannerOverlay = true
                                },
                                modifier = Modifier
                                    .height(36.dp)
                                    .testTag("portal_error_rescan_button")
                            ) {
                                Text("مسح كرت آخر", style = MaterialTheme.typography.labelSmall)
                            }
                        } else if (lastSubmittedCode != null) {
                            IconButton(
                                onClick = {
                                    CardDispatchCoordinator.copyToClipboard(context, lastSubmittedCode!!)
                                },
                                modifier = Modifier.size(36.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "نسخ الكود",
                                    tint = CyanSecondary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }

            // Main Portal WebView Container
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            val webSettings = this.settings
                            webSettings.javaScriptEnabled = true
                            webSettings.domStorageEnabled = true
                            webSettings.loadsImagesAutomatically = true
                            webSettings.useWideViewPort = true
                            webSettings.loadWithOverviewMode = true
                            webSettings.builtInZoomControls = true
                            webSettings.displayZoomControls = false
                            webSettings.setSupportMultipleWindows(false)
                            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            webChromeClient = object : WebChromeClient() {
                                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                    loadProgress = newProgress
                                    isPageLoading = newProgress < 100
                                }

                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    if (transport != null && view != null) {
                                        transport.webView = view
                                        resultMsg.sendToTarget()
                                        return true
                                    }
                                    return false
                                }
                            }
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(
                                    view: WebView?,
                                    request: WebResourceRequest?
                                ): Boolean {
                                    val uri = request?.url ?: return false
                                    val scheme = uri.scheme?.lowercase().orEmpty()
                                    if (scheme == "http" || scheme == "https") {
                                        return false
                                    }
                                    runCatching {
                                        ctx.startActivity(
                                            Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        )
                                    }
                                    return true
                                }

                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isPageLoading = true
                                    loadErrorMessage = null
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                        if (initialLoginUrl == null && !WifiPortalHelper.isBalanceOrStatusUrl(url)) {
                                            initialLoginUrl = url
                                        }
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isPageLoading = false
                                    loadProgress = 100
                                    if (!url.isNullOrBlank()) {
                                        currentUrl = url
                                    }
                                    if (pendingInjectionCode != null) {
                                        tryInjectPendingCode(view)
                                    }
                                    if (hasSubmittedCode || (!url.isNullOrBlank() && WifiPortalHelper.isBalanceOrStatusUrl(url))) {
                                        evaluatePortalState(view)
                                    }
                                }

                                override fun onReceivedError(
                                    view: WebView?,
                                    request: WebResourceRequest?,
                                    error: WebResourceError?
                                ) {
                                    super.onReceivedError(view, request, error)
                                    if (request?.isForMainFrame == true) {
                                        if (!usedProbeFallback) {
                                            usedProbeFallback = true
                                            WifiPortalHelper.bindToWifiNetwork(ctx)
                                            view?.loadUrl(WifiPortalHelper.CAPTIVE_PROBE_URL)
                                        } else {
                                            isPageLoading = false
                                            loadErrorMessage = "تعذر فتح صفحة بوابة الشبكة تلقائياً. تأكد من اتصالك بشبكة الواي فاي."
                                        }
                                    }
                                }
                            }
                            webViewRef = this
                            loadUrl(startGatewayUrl)
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.navigationBars)
                )

                // Connection Error Overlay
                if (loadErrorMessage != null) {
                    Surface(
                        color = MidnightBg.copy(alpha = 0.94f),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Default.WarningAmber,
                                contentDescription = null,
                                tint = AmberWarning,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = loadErrorMessage!!,
                                style = MaterialTheme.typography.bodyLarge,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(
                                    onClick = {
                                        loadErrorMessage = null
                                        usedProbeFallback = false
                                        WifiPortalHelper.bindToWifiNetwork(context)
                                        webViewRef?.loadUrl(WifiPortalHelper.resolvePortalStartUrl(context))
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldPrimary,
                                        contentColor = MidnightBg
                                    )
                                ) {
                                    Text("إعادة المحاولة", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(onClick = { CardDispatchCoordinator.openWifiSettings(context) }) {
                                    Text("إعدادات الواي فاي")
                                }
                            }
                        }
                    }
                }
            }
        }

        // Balance / Status Page Choice Card
        AnimatedVisibility(
            visible = isOnBalancePage && !balancePromptDismissed && !showScannerOverlay,
            enter = slideInVertically { it } + fadeIn(),
            exit = slideOutVertically { it } + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(14.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = DeepNavySurface,
                tonalElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.55f), RoundedCornerShape(22.dp))
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
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
                        Text(
                            text = "تم تسجيل الدخول — صفحة الرصيد",
                            style = MaterialTheme.typography.titleMedium,
                            color = EmeraldPrimary
                        )
                    }

                    if (!lastSubmittedCode.isNullOrBlank()) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Text(
                                text = lastSubmittedCode!!,
                                fontFamily = JetBrainsMonoFontFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color.White
                            )
                        }
                    }

                    Text(
                        text = "يمكنك متابعة قراءة تفاصيل الرصيد والوقت المتبقي على الصفحة، أو إغلاق النافذة الآن.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { balancePromptDismissed = true },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("portal_continue_balance_button"),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = EmeraldPrimary,
                                contentColor = MidnightBg
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.Visibility,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("متابعة قراءة الرصيد", fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = onClosePortal,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("portal_balance_close_button")
                        ) {
                            Text("إغلاق")
                        }
                    }
                }
            }
        }

        // Camera + Gallery Scanner Window Directly Above the Portal Page
        AnimatedVisibility(
            visible = showScannerOverlay,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.fillMaxSize()
        ) {
            PortalOverlayScannerSheet(
                hasCameraPermission = hasCameraPermission,
                initialTorchEnabled = settings.autoFlashEnabled,
                vibrationEnabled = settings.vibrationEnabled,
                autoSubmitOnLock = autoSubmitOnLock,
                networkName = networkName,
                onRequestCameraPermission = {
                    cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
                },
                onCodeLocked = { code, confidence ->
                    CardDispatchCoordinator.saveScannedCardToHistory(
                        context = context,
                        code = code,
                        confidence = confidence,
                        submitted = false
                    )
                    forceAutoClick = true
                    autoSubmitOnLock = false
                    pendingInjectionCode = code
                    injectionAttempts = 0
                    portalErrorDetected = false
                    isOnBalancePage = false
                    balancePromptDismissed = false
                    showScannerOverlay = false
                },
                onSilentLogin = { code, confidence ->
                    showScannerOverlay = false
                    CardDispatchCoordinator.startSilentLogin(
                        context = context,
                        code = code,
                        confidence = confidence
                    )
                    onClosePortal()
                },
                onDismiss = {
                    autoSubmitOnLock = false
                    showScannerOverlay = false
                }
            )
        }
    }
}

@Composable
private fun PortalOverlayScannerSheet(
    hasCameraPermission: Boolean,
    initialTorchEnabled: Boolean,
    vibrationEnabled: Boolean,
    autoSubmitOnLock: Boolean,
    networkName: String,
    onRequestCameraPermission: () -> Unit,
    onCodeLocked: (String, Float) -> Unit,
    onSilentLogin: (String, Float) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()

    var torchEnabled by rememberSaveable { mutableStateOf(initialTorchEnabled) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }

    // Live camera scanning state (transient)
    var liveScanState by remember { mutableStateOf<OcrScanState>(OcrScanState.Scanning()) }

    // Separated extracted code state — persists across Photo Picker return and camera cleanup
    val lockedCodeState = rememberSaveable { mutableStateOf<String?>(null) }
    val lockedConfidenceState = rememberSaveable { mutableFloatStateOf(0.95f) }
    val lockedAlternativesState = rememberSaveable { mutableStateOf(ArrayList<String>()) }
    val lockedSourceLabelState = rememberSaveable { mutableStateOf("الكاميرا") }

    var vibratedCode by rememberSaveable { mutableStateOf<String?>(null) }
    val isAnalyzingGalleryState = remember { mutableStateOf(false) }

    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val ocrAnalyzer = remember {
        OcrAnalyzer { newState ->
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

    // Camera cleanup never clears extracted code state
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

    var autoSubmitted by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(activeLockedCode, autoSubmitOnLock) {
        if (activeLockedCode != null && vibratedCode != activeLockedCode) {
            vibratedCode = activeLockedCode
            if (vibrationEnabled) {
                PermissionHelper.triggerSuccessVibration(context)
            }
        }
        if (activeLockedCode != null && autoSubmitOnLock && !autoSubmitted) {
            autoSubmitted = true
            onCodeLocked(activeLockedCode, lockedConfidenceState.floatValue)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.68f))
            .windowInsetsPadding(WindowInsets.statusBars)
            .windowInsetsPadding(WindowInsets.navigationBars)
            .imePadding()
            .padding(horizontal = 16.dp, vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        if (lockedState != null && !autoSubmitOnLock) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ScannedCodeReviewPanel(
                    initialCode = lockedState.code,
                    confidence = lockedState.confidence,
                    alternatives = lockedState.alternatives,
                    sourceLabel = lockedState.sourceLabel,
                    onSilentLogin = { finalCode ->
                        onSilentLogin(finalCode, lockedState.confidence)
                    },
                    onConnectDirect = { finalCode ->
                        onCodeLocked(finalCode, lockedState.confidence)
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
                    }
                )

                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("portal_review_close")
                ) {
                    Text("العودة لصفحة البوابة")
                }
            }
        } else {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 520.dp)
                    .border(1.dp, EmeraldPrimary.copy(alpha = 0.45f), RoundedCornerShape(24.dp)),
                shape = RoundedCornerShape(24.dp),
                color = DeepNavySurface,
                tonalElevation = 10.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
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
                                    text = "امسح كرت الواي فاي",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                if (networkName.isNotBlank()) {
                                    Text(
                                        text = networkName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = CyanSecondary
                                    )
                                }
                            }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (hasCameraPermission) {
                                IconButton(
                                    onClick = { torchEnabled = !torchEnabled },
                                    modifier = Modifier
                                        .size(48.dp)
                                        .testTag("portal_scanner_flash_toggle")
                                ) {
                                    Icon(
                                        imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                        contentDescription = if (torchEnabled) "إطفاء الفلاش" else "تشغيل الفلاش",
                                        tint = if (torchEnabled) AmberWarning else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("portal_scanner_close")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "إخفاء الماسح",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (hasCameraPermission) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(250.dp)
                                .clip(RoundedCornerShape(18.dp))
                        ) {
                            CameraScannerViewfinderBox(
                                lifecycleOwner = lifecycleOwner,
                                cameraExecutor = cameraExecutor,
                                ocrAnalyzer = ocrAnalyzer,
                                torchEnabled = torchEnabled,
                                scanState = liveScanState,
                                onBoundCameraChanged = { boundCamera = it },
                                modifier = Modifier.fillMaxSize()
                            )

                            if (isAnalyzingGalleryState.value || (liveScanState as? OcrScanState.Scanning)?.isAnalyzingPhoto == true) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black.copy(alpha = 0.6f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        CircularProgressIndicator(
                                            color = EmeraldPrimary,
                                            modifier = Modifier.size(24.dp),
                                            strokeWidth = 3.dp
                                        )
                                        Text(
                                            text = "جاري التحليل الذكي...",
                                            color = Color.White,
                                            style = MaterialTheme.typography.titleSmall
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = ElevatedNavyCard,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(18.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                Text(
                                    text = "امنح صلاحية الكاميرا لمسح الكرت مباشرة، أو اختر صورة الكرت من المعرض",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = Color.White
                                )
                                Button(
                                    onClick = onRequestCameraPermission,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = EmeraldPrimary,
                                        contentColor = MidnightBg
                                    )
                                ) {
                                    Text("منح صلاحية الكاميرا", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    OcrLiveStatusBanner(scanState = liveScanState)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val candidate = (liveScanState as? OcrScanState.Scanning)?.candidateCode

                        if (hasCameraPermission) {
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
                                    .height(48.dp)
                                    .testTag("portal_scanner_capture"),
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
                                    text = if (candidate != null) "مراجعة" else "التقاط ذكي",
                                    fontWeight = FontWeight.Bold
                                )
                            }
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
                                .height(48.dp)
                                .then(if (!hasCameraPermission) Modifier.weight(1f) else Modifier)
                                .testTag("portal_scanner_gallery")
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
                            onClick = onDismiss,
                            modifier = Modifier
                                .height(48.dp)
                                .testTag("portal_scanner_dismiss")
                        ) {
                            Text("إغلاق")
                        }
                    }
                }
            }
        }
    }
}

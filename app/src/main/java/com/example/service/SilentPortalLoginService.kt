package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
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
import com.example.KartiApplication
import com.example.MainActivity
import com.example.R
import com.example.ui.components.SilentLoginStatusPill
import com.example.ui.theme.KartiTheme
import com.example.utils.PermissionHelper
import com.example.utils.WifiPortalHelper
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Background foreground-service that runs the app's existing Captive Portal WebView
 * discovery and auto-fill engine silently (hidden from view) and displays an animated
 * floating Status Pill showing progress, connection success, or the exact portal error.
 */
class SilentPortalLoginService :
    LifecycleService(),
    SavedStateRegistryOwner,
    ViewModelStoreOwner {

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore
        get() = store

    private val mainHandler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var hiddenWebContainer: FrameLayout? = null
    private var statusPillView: ComposeView? = null
    private var webView: WebView? = null

    private var activeCode: String = ""
    private var activeNetworkName: String = ""
    private var initialLoginUrl: String? = null
    private var usedProbeFallback = false
    private var injectionAttempts = 0
    private var hasSubmittedCode = false
    private var isPageLoading = true
    private var isTerminalState = false

    private var evaluationJob: Job? = null
    private var timeoutJob: Job? = null
    private var autoFinishJob: Job? = null

    override fun onCreate() {
        savedStateRegistryController.performRestore(null)
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        startSilentForeground("جاري تسجيل الدخول صامتاً...")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        val code = intent?.getStringExtra(EXTRA_CARD_CODE)?.trim().orEmpty()
        if (code.isBlank()) {
            stopSelf()
            return START_NOT_STICKY
        }

        val networkExtra = intent?.getStringExtra(EXTRA_NETWORK_NAME).orEmpty()
        activeCode = code
        activeNetworkName = networkExtra.ifBlank { CardDispatchCoordinator.detectedNetworkName.value }

        startLoginAttempt()
        return START_NOT_STICKY
    }

    private fun startLoginAttempt() {
        evaluationJob?.cancel()
        timeoutJob?.cancel()
        autoFinishJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)

        initialLoginUrl = null
        usedProbeFallback = false
        injectionAttempts = 0
        hasSubmittedCode = false
        isPageLoading = true
        isTerminalState = false

        CardDispatchCoordinator.updateSilentLoginState(
            SilentLoginState.Connecting(activeCode, activeNetworkName)
        )
        startSilentForeground("⏳ جاري تسجيل الدخول صامتاً بالكود $activeCode...")

        WifiPortalHelper.bindToWifiNetwork(this)
        ensureStatusPillOverlay()
        setupHiddenWebView()

        timeoutJob = lifecycleScope.launch {
            delay(LOGIN_TIMEOUT_MS)
            if (!isTerminalState) {
                CardDispatchCoordinator.copyToClipboard(
                    this@SilentPortalLoginService,
                    activeCode,
                    showToast = false
                )
                handleError(
                    "انتهت مهلة الاتصال بالبوابة — تم نسخ الكود ($activeCode)"
                )
            }
        }
    }

    private fun startSilentForeground(statusText: String) {
        val openAppIntent = PendingIntent.getActivity(
            this,
            210,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification: Notification = NotificationCompat.Builder(this, KartiApplication.CHANNEL_BUBBLE)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.silent_login_notif_title))
            .setContentText(statusText)
            .setContentIntent(openAppIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgsType)
    }

    private fun ensureStatusPillOverlay() {
        if (!PermissionHelper.isOverlayGranted(this)) return
        if (statusPillView != null) return

        val density = resources.displayMetrics.density
        val pillCompose = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@SilentPortalLoginService)
            setViewTreeViewModelStoreOwner(this@SilentPortalLoginService)
            setViewTreeSavedStateRegistryOwner(this@SilentPortalLoginService)
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                KartiTheme {
                    val state by CardDispatchCoordinator.silentLoginState.collectAsStateWithLifecycle()
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                        Box(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            SilentLoginStatusPill(
                                state = state,
                                onOpenPortal = {
                                    CardDispatchCoordinator.clearSilentLoginState()
                                    CardDispatchCoordinator.openInAppPortal(
                                        context = this@SilentPortalLoginService,
                                        autoStartScanner = false,
                                        prefillCode = activeCode,
                                        forceAutoClick = true
                                    )
                                    stopSelf()
                                },
                                onDismiss = {
                                    CardDispatchCoordinator.clearSilentLoginState()
                                    stopSelf()
                                }
                            )
                        }
                    }
                }
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (42 * density).toInt()
        }

        statusPillView = pillCompose
        runCatching { windowManager?.addView(pillCompose, params) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupHiddenWebView() {
        destroyHiddenWebViewOnly()

        val startUrl = WifiPortalHelper.resolvePortalStartUrl(this)
        val density = resources.displayMetrics.density
        val viewportWidthPx = (390 * density).toInt().coerceAtLeast(720)
        val viewportHeightPx = (640 * density).toInt().coerceAtLeast(1080)

        val wv = WebView(this).apply {
            val webSettings = this.settings
            webSettings.javaScriptEnabled = true
            webSettings.domStorageEnabled = true
            webSettings.loadsImagesAutomatically = true
            webSettings.useWideViewPort = true
            webSettings.loadWithOverviewMode = true
            webSettings.setSupportMultipleWindows(false)
            webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
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
                    val scheme = request?.url?.scheme?.lowercase().orEmpty()
                    return scheme != "http" && scheme != "https"
                }

                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    isPageLoading = true
                    if (!url.isNullOrBlank() &&
                        initialLoginUrl == null &&
                        !WifiPortalHelper.isBalanceOrStatusUrl(url)
                    ) {
                        initialLoginUrl = url
                    }
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    isPageLoading = false
                    if (isTerminalState) return

                    if (!hasSubmittedCode) {
                        tryInjectSilentCode(view)
                    }
                    if (hasSubmittedCode || (!url.isNullOrBlank() && WifiPortalHelper.isBalanceOrStatusUrl(url))) {
                        evaluatePortalState(view, url.orEmpty())
                    }
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame == true && !isTerminalState) {
                        if (!usedProbeFallback) {
                            usedProbeFallback = true
                            WifiPortalHelper.bindToWifiNetwork(this@SilentPortalLoginService)
                            view?.loadUrl(WifiPortalHelper.CAPTIVE_PROBE_URL)
                        } else {
                            CardDispatchCoordinator.copyToClipboard(
                                this@SilentPortalLoginService,
                                activeCode,
                                showToast = false
                            )
                            handleError("تعذر الوصول لبوابة الشبكة — تم نسخ الكود ($activeCode)")
                        }
                    }
                }
            }
        }

        webView = wv

        if (PermissionHelper.isOverlayGranted(this)) {
            val container = FrameLayout(this)
            container.addView(
                wv,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            // Keep alpha = 0f at WindowManager level (invisible on screen) while
            // CSS computed styles inside the WebView retain opacity: 1 and real viewport bounds.
            val hiddenParams = WindowManager.LayoutParams(
                viewportWidthPx,
                viewportHeightPx,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 0
                y = 0
                alpha = 0f
            }
            hiddenWebContainer = container
            runCatching { windowManager?.addView(container, hiddenParams) }
        } else {
            // Without overlay permission, assign explicit offscreen dimensions so DOM elements have rect > 0.
            wv.measure(
                View.MeasureSpec.makeMeasureSpec(viewportWidthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(viewportHeightPx, View.MeasureSpec.EXACTLY)
            )
            wv.layout(0, 0, viewportWidthPx, viewportHeightPx)
        }

        wv.loadUrl(startUrl)
    }

    private fun tryInjectSilentCode(view: WebView?) {
        if (isTerminalState || hasSubmittedCode) return
        val wv = view ?: return
        if (injectionAttempts >= 3) return

        injectionAttempts++
        val script = WifiPortalHelper.buildAutoFillAndSubmitScript(
            code = activeCode,
            autoClick = true
        )
        wv.evaluateJavascript(script) { raw ->
            if (isTerminalState) return@evaluateJavascript
            val cleaned = raw?.trim()?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    runCatching { JSONObject("{\"v\":$it}").getString("v") }.getOrDefault(it)
                } else {
                    it
                }
            } ?: return@evaluateJavascript

            val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return@evaluateJavascript
            val filled = json.optBoolean("filled", false)
            val submitted = json.optBoolean("submitted", false)

            if (filled && submitted) {
                hasSubmittedCode = true
                evaluationJob?.cancel()
                evaluationJob = lifecycleScope.launch {
                    delay(650L)
                    if (!isTerminalState) evaluatePortalState(wv, wv.url.orEmpty())
                    delay(950L)
                    if (!isTerminalState) evaluatePortalState(wv, wv.url.orEmpty())
                    delay(1400L)
                    if (!isTerminalState) evaluatePortalState(wv, wv.url.orEmpty())
                }
            } else if (filled && !submitted && !isPageLoading && injectionAttempts >= 3) {
                CardDispatchCoordinator.copyToClipboard(
                    this@SilentPortalLoginService,
                    activeCode,
                    showToast = false
                )
                handleError("تم تعبئة الكود لكن تعذر الضغط على زر الدخول تلقائياً")
            } else if (!filled && !isPageLoading && injectionAttempts >= 3) {
                CardDispatchCoordinator.copyToClipboard(
                    this@SilentPortalLoginService,
                    activeCode,
                    showToast = false
                )
                handleError("لم يُعثر على حقل الكرت في البوابة — تم نسخ الكود ($activeCode)")
            }
        }
    }

    private fun evaluatePortalState(view: WebView?, fallbackUrl: String) {
        if (isTerminalState) return
        val wv = view ?: return
        wv.evaluateJavascript(WifiPortalHelper.buildBalancePageCheckScript()) { raw ->
            if (isTerminalState) return@evaluateJavascript
            val cleaned = raw?.trim()?.let {
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    runCatching { JSONObject("{\"v\":$it}").getString("v") }.getOrDefault(it)
                } else {
                    it
                }
            } ?: return@evaluateJavascript

            val json = runCatching { JSONObject(cleaned) }.getOrNull() ?: return@evaluateJavascript
            val hasLoginInput = json.optBoolean("hasLoginInput", true)
            val hasBalanceKeywords = json.optBoolean("hasBalanceKeywords", false)
            val hasErrorKeywords = json.optBoolean("hasErrorKeywords", false)
            val extractedError = json.optString("errorMessage", "").trim()
            val pageUrl = json.optString("url", fallbackUrl)

            if (hasErrorKeywords && hasLoginInput && hasSubmittedCode) {
                val errorText = extractedError.ifBlank {
                    "لم يتم قبول الكود — تأكد من الرقم أو الرصيد"
                }
                handleError(errorText)
                return@evaluateJavascript
            }

            val urlLooksLikeStatus = WifiPortalHelper.isBalanceOrStatusUrl(pageUrl)
            val transitionedAwayFromLogin = hasSubmittedCode && !hasLoginInput && !hasErrorKeywords &&
                (hasBalanceKeywords || urlLooksLikeStatus ||
                    (!initialLoginUrl.isNullOrBlank() && !pageUrl.equals(initialLoginUrl, ignoreCase = true)))

            if (urlLooksLikeStatus || transitionedAwayFromLogin) {
                handleSuccess()
            }
        }
    }

    private fun handleSuccess() {
        if (isTerminalState) return
        isTerminalState = true
        timeoutJob?.cancel()
        evaluationJob?.cancel()

        CardDispatchCoordinator.markPortalLoginSuccess(this, activeCode)
        CardDispatchCoordinator.updateSilentLoginState(
            SilentLoginState.Success(activeCode, activeNetworkName)
        )
        stopService(Intent(this, OverlayBubbleService::class.java))
        startSilentForeground("✅ تم الاتصال بالإنترنت بنجاح!")

        lifecycleScope.launch {
            val app = applicationContext as? KartiApplication
            val vibrate = app?.settingsDataStore?.settingsFlow?.first()?.vibrationEnabled ?: true
            if (vibrate) {
                PermissionHelper.triggerSuccessVibration(this@SilentPortalLoginService)
            }
            if (!PermissionHelper.isOverlayGranted(this@SilentPortalLoginService)) {
                Toast.makeText(
                    this@SilentPortalLoginService,
                    "✅ تم الاتصال بالإنترنت بنجاح!",
                    Toast.LENGTH_SHORT
                ).show()
            }
            autoFinishJob?.cancel()
            autoFinishJob = launch {
                delay(SUCCESS_DISMISS_DELAY_MS)
                CardDispatchCoordinator.clearSilentLoginState()
                stopSelf()
            }
        }
    }

    private fun handleError(message: String) {
        if (isTerminalState) return
        isTerminalState = true
        timeoutJob?.cancel()
        evaluationJob?.cancel()

        CardDispatchCoordinator.updateSilentLoginState(
            SilentLoginState.Error(code = activeCode, message = message)
        )
        startSilentForeground(message)

        if (!PermissionHelper.isOverlayGranted(this)) {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }

        autoFinishJob?.cancel()
        autoFinishJob = lifecycleScope.launch {
            delay(ERROR_DISMISS_DELAY_MS)
            CardDispatchCoordinator.clearSilentLoginState()
            stopSelf()
        }
    }

    private fun destroyHiddenWebViewOnly() {
        webView?.let { wv ->
            runCatching {
                wv.stopLoading()
                wv.destroy()
            }
        }
        webView = null
        hiddenWebContainer?.let { container ->
            runCatching { windowManager?.removeViewImmediate(container) }
        }
        hiddenWebContainer = null
    }

    override fun onDestroy() {
        timeoutJob?.cancel()
        evaluationJob?.cancel()
        autoFinishJob?.cancel()
        mainHandler.removeCallbacksAndMessages(null)
        destroyHiddenWebViewOnly()
        statusPillView?.let { pill ->
            runCatching { windowManager?.removeViewImmediate(pill) }
        }
        statusPillView = null
        WifiPortalHelper.unbindProcessNetwork(this)
        store.clear()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_CARD_CODE = "extra_card_code"
        const val EXTRA_NETWORK_NAME = "extra_network_name"
        private const val NOTIFICATION_ID = 4003
        private const val LOGIN_TIMEOUT_MS = 18_000L
        private const val SUCCESS_DISMISS_DELAY_MS = 3_200L
        private const val ERROR_DISMISS_DELAY_MS = 5_500L
    }
}

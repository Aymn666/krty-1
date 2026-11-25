package com.example.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.KartiApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PortalAccessibilityService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @Volatile
    private var isAttempting = false
    private var lastAttemptCode: String? = null
    private var lastAttemptTimeMs = 0L

    private val inputKeywords = listOf(
        "رقم الكرت",
        "أدخل رقم الكرت",
        "ادخل رقم الكرت",
        "ادخل رمز الدخول",
        "رمز الدخول",
        "الكرت",
        "card number",
        "card",
        "voucher",
        "pin",
        "code",
        "username",
        "user",
        "login"
    )

    private val buttonKeywords = listOf(
        "تسجيل الدخول",
        "دخول للإنترنت",
        "دخول الإنترنت",
        "دخول الانترنت",
        "تسجيل",
        "دخول",
        "اتصال",
        "موافق",
        "log in",
        "login",
        "sign in",
        "connect",
        "submit",
        "ok"
    )

    private val ignoredButtonKeywords = listOf(
        "الأسعار",
        "الاسعار",
        "أماكن البيع",
        "اماكن البيع",
        "معلومات",
        "إدارة",
        "ادارة",
        "محو الكل",
        "تحديثات",
        "سرعة",
        "إلغاء",
        "الغاء",
        "إغلاق",
        "اغلاق",
        "نسخ",
        "واتساب",
        "whatsapp"
    )

    private val ignoredInputIds = listOf(
        "url_bar",
        "omnibox",
        "search_box",
        "location_bar",
        "toolbar"
    )

    private val ignoredPackages = listOf(
        "inputmethod",
        "gboard",
        "latinime",
        "swiftkey",
        "honeyboard",
        "systemui"
    )

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        CardDispatchCoordinator.setAccessibilityConnected(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()?.lowercase().orEmpty()
        if (pkg == packageName.lowercase() || ignoredPackages.any { pkg.contains(it) }) return

        val pending = CardDispatchCoordinator.pendingCardCode.value ?: return
        if (isAttempting) return

        val now = System.currentTimeMillis()
        if (pending == lastAttemptCode && now - lastAttemptTimeMs < 3000L) return

        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) {
            attemptAutoFill(pending)
        }
    }

    override fun onInterrupt() {
        // No-op
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        CardDispatchCoordinator.setAccessibilityConnected(false)
        serviceScope.cancel()
        super.onDestroy()
    }

    fun attemptAutoFill(code: String) {
        if (isAttempting) return
        isAttempting = true
        lastAttemptCode = code
        lastAttemptTimeMs = System.currentTimeMillis()

        serviceScope.launch {
            try {
                val app = applicationContext as? KartiApplication
                val settings = app?.settingsDataStore?.settingsFlow?.first()
                val autoFillEnabled = settings?.autoFillEnabled ?: true
                val autoClickEnabled = settings?.autoClickEnabled ?: true
                if (!autoFillEnabled) {
                    CardDispatchCoordinator.onAutoFillResult(
                        context = this@PortalAccessibilityService,
                        code = code,
                        filled = false,
                        submitted = false
                    )
                    isAttempting = false
                    return@launch
                }

                val candidateRoots = collectExternalWindowRoots()
                var bestInput: AccessibilityNodeInfo? = null
                var bestScore = Int.MIN_VALUE

                for (root in candidateRoots) {
                    val editableNodes = mutableListOf<AccessibilityNodeInfo>()
                    collectNodes(root, editableNodes) { node ->
                        node.isEditable || node.className?.toString() == "android.widget.EditText"
                    }
                    for (node in editableNodes) {
                        val score = scoreInputNode(node)
                        if (score > bestScore) {
                            bestScore = score
                            bestInput = node
                        }
                    }
                }

                if (bestInput == null || bestScore < 0) {
                    CardDispatchCoordinator.onAutoFillResult(
                        context = this@PortalAccessibilityService,
                        code = code,
                        filled = false,
                        submitted = false
                    )
                    isAttempting = false
                    return@launch
                }

                // Focus & set text WITHOUT ACTION_CLICK so the soft keyboard doesn't pop up
                // and obscure or shift the portal's login button.
                bestInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                val args = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, code)
                }
                val filled = bestInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)

                if (!filled) {
                    CardDispatchCoordinator.onAutoFillResult(
                        context = this@PortalAccessibilityService,
                        code = code,
                        filled = false,
                        submitted = false
                    )
                    isAttempting = false
                    return@launch
                }

                if (!autoClickEnabled) {
                    CardDispatchCoordinator.onAutoFillResult(
                        context = this@PortalAccessibilityService,
                        code = code,
                        filled = true,
                        submitted = false
                    )
                    isAttempting = false
                    return@launch
                }

                // Slight delay so WebView DOM updates before clicking Submit
                handler.postDelayed({
                    try {
                        var clicked = findAndClickSubmitButton()
                        if (!clicked && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            clicked = bestInput.performAction(
                                AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id
                            )
                        }
                        CardDispatchCoordinator.onAutoFillResult(
                            context = this@PortalAccessibilityService,
                            code = code,
                            filled = true,
                            submitted = clicked
                        )
                    } finally {
                        isAttempting = false
                    }
                }, 260L)
            } catch (_: Throwable) {
                isAttempting = false
                CardDispatchCoordinator.onAutoFillResult(
                    context = this@PortalAccessibilityService,
                    code = code,
                    filled = false,
                    submitted = false
                )
            }
        }
    }

    private fun collectExternalWindowRoots(): List<AccessibilityNodeInfo> {
        return buildList {
            rootInActiveWindow?.let { root ->
                val pkg = root.packageName?.toString()?.lowercase().orEmpty()
                if (pkg != packageName.lowercase() && ignoredPackages.none { pkg.contains(it) }) {
                    add(root)
                }
            }
            windows?.forEach { win ->
                val root = win.root ?: return@forEach
                val pkg = root.packageName?.toString()?.lowercase().orEmpty()
                if (pkg != packageName.lowercase() && ignoredPackages.none { pkg.contains(it) }) {
                    add(root)
                }
            }
        }
    }

    private fun findAndClickSubmitButton(): Boolean {
        val candidateRoots = collectExternalWindowRoots()
        var bestButton: AccessibilityNodeInfo? = null
        var bestScore = Int.MIN_VALUE

        for (root in candidateRoots) {
            val allNodes = mutableListOf<AccessibilityNodeInfo>()
            collectNodes(root, allNodes) { true }
            for (node in allNodes) {
                val label = extractNodeLabel(node).lowercase()
                if (label.isBlank()) continue
                if (ignoredButtonKeywords.any { label.contains(it) }) continue

                val matchedKeyword = buttonKeywords.firstOrNull { label.contains(it) } ?: continue
                val clickableNode = findClickableTarget(node) ?: continue

                var score = 50
                if (matchedKeyword.length >= 6) score += 40
                if (node.className?.toString()?.contains("Button", ignoreCase = true) == true) score += 25
                if (node.isVisibleToUser) score += 15
                if (score > bestScore) {
                    bestScore = score
                    bestButton = clickableNode
                }
            }
        }

        return bestButton?.performAction(AccessibilityNodeInfo.ACTION_CLICK) == true
    }

    private fun findClickableTarget(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isClickable && node.isEnabled) return node
        var parent = node.parent
        var depth = 0
        while (parent != null && depth < 6) {
            if (parent.isClickable && parent.isEnabled) return parent
            parent = parent.parent
            depth++
        }
        return null
    }

    private fun scoreInputNode(node: AccessibilityNodeInfo): Int {
        if (!node.isEnabled || !node.isVisibleToUser) return -100
        val viewId = node.viewIdResourceName?.lowercase().orEmpty()
        if (ignoredInputIds.any { viewId.contains(it) }) return -100

        val hint = node.hintText?.toString()?.lowercase().orEmpty()
        val text = node.text?.toString()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.lowercase().orEmpty()
        val combined = "$hint $text $desc $viewId"

        var score = 10
        if (inputKeywords.any { combined.contains(it) }) {
            score += 100
        }
        val pkg = node.packageName?.toString()?.lowercase().orEmpty()
        if (pkg.contains("captiveportal")) {
            score += 40
        }
        return score
    }

    private fun extractNodeLabel(node: AccessibilityNodeInfo): String {
        val t = node.text?.toString()?.trim().orEmpty()
        if (t.isNotBlank()) return t
        val d = node.contentDescription?.toString()?.trim().orEmpty()
        if (d.isNotBlank()) return d
        return node.hintText?.toString()?.trim().orEmpty()
    }

    private fun collectNodes(
        node: AccessibilityNodeInfo?,
        out: MutableList<AccessibilityNodeInfo>,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ) {
        if (node == null) return
        if (predicate(node)) {
            out.add(node)
        }
        for (i in 0 until node.childCount) {
            collectNodes(node.getChild(i), out, predicate)
        }
    }

    companion object {
        @Volatile
        private var instance: PortalAccessibilityService? = null

        fun requestAutoFill(code: String) {
            instance?.attemptAutoFill(code)
        }
    }
}

package com.example.utils

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build

object WifiPortalHelper {

    const val CAPTIVE_PROBE_URL = "http://connectivitycheck.gstatic.com/generate_204"
    const val DEFAULT_GATEWAY_URL = "http://10.0.0.1"

    fun findWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        @Suppress("DEPRECATION")
        val allNetworks = cm.allNetworks
        var fallbackWifi: Network? = null
        for (network in allNetworks) {
            val caps = cm.getNetworkCapabilities(network) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                val isCaptiveOrUnvalidated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) ||
                    !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                if (isCaptiveOrUnvalidated) {
                    return network
                }
                if (fallbackWifi == null) {
                    fallbackWifi = network
                }
            }
        }
        return fallbackWifi
    }

    fun bindToWifiNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return null
        val wifiNetwork = findWifiNetwork(context) ?: return null
        runCatching {
            cm.bindProcessToNetwork(wifiNetwork)
        }
        return wifiNetwork
    }

    fun unbindProcessNetwork(context: Context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return
        runCatching {
            cm.bindProcessToNetwork(null)
        }
    }

    fun resolvePortalStartUrl(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return DEFAULT_GATEWAY_URL
        val targetNetwork = findWifiNetwork(context) ?: return DEFAULT_GATEWAY_URL
        val linkProps = cm.getLinkProperties(targetNetwork) ?: return DEFAULT_GATEWAY_URL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val dhcpHost = linkProps.dhcpServerAddress?.hostAddress
            if (!dhcpHost.isNullOrBlank() && !dhcpHost.contains(':')) {
                return "http://$dhcpHost"
            }
        }

        for (route in linkProps.routes) {
            val gateway = route.gateway
            if (route.isDefaultRoute && gateway != null && !gateway.isLoopbackAddress) {
                val host = gateway.hostAddress
                if (!host.isNullOrBlank() && !host.contains(':')) {
                    return "http://$host"
                }
            }
        }

        return DEFAULT_GATEWAY_URL
    }

    fun isBalanceOrStatusUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.isBlank()) return false
        if (lower.contains("/login")) return false
        return lower.contains("/status") ||
            lower.contains("status.html") ||
            lower.contains("/alogin") ||
            lower.contains("alogin.html") ||
            lower.contains("/balance") ||
            lower.contains("/success")
    }

    fun escapeJsString(value: String): String {
        val sb = StringBuilder(value.length + 8)
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> sb.append(ch)
            }
        }
        return sb.toString()
    }

    /**
     * Generates a safe, self-contained JS snippet that locates the MikroTik voucher input field
     * (without popping up the soft keyboard), injects the card code, dispatches DOM input/change
     * events, and clicks the matching login/submit button.
     */
    fun buildAutoFillAndSubmitScript(code: String, autoClick: Boolean): String {
        val escapedCode = escapeJsString(code)
        val clickFlag = if (autoClick) "true" else "false"
        return """
            (function() {
                try {
                    var cardCode = '$escapedCode';
                    var shouldAutoClick = $clickFlag;
                    var inputKeywords = [
                        'رقم الكرت', 'ادخل رقم الكرت', 'أدخل رقم الكرت', 'ادخل رمز الدخول',
                        'رمز الدخول', 'الكرت', 'كود', 'اسم المستخدم',
                        'card number', 'card', 'voucher', 'pin', 'code', 'username', 'user', 'login'
                    ];
                    var buttonKeywords = [
                        'تسجيل الدخول', 'دخول للإنترنت', 'دخول الإنترنت', 'دخول الانترنت',
                        'تسجيل', 'دخول', 'اتصال', 'موافق',
                        'log in', 'login', 'sign in', 'connect', 'submit', 'ok'
                    ];
                    var ignoredButtonKeywords = [
                        'الأسعار', 'الاسعار', 'أماكن البيع', 'اماكن البيع', 'معلومات',
                        'إدارة', 'ادارة', 'محو الكل', 'تحديثات', 'سرعة', 'إلغاء', 'الغاء',
                        'إغلاق', 'اغلاق', 'نسخ', 'whatsapp', 'واتساب', 'استفسار'
                    ];

                    function isVisible(el) {
                        if (!el) return false;
                        var style = window.getComputedStyle(el);
                        if (style.display === 'none' || style.visibility === 'hidden' || style.opacity === '0') return false;
                        var rect = el.getBoundingClientRect();
                        return rect.width > 0 && rect.height > 0;
                    }

                    function collectInputs(doc) {
                        var list = [];
                        try {
                            var nodes = doc.querySelectorAll('input');
                            for (var i = 0; i < nodes.length; i++) {
                                list.push({ el: nodes[i], doc: doc });
                            }
                            var frames = doc.querySelectorAll('iframe');
                            for (var f = 0; f < frames.length; f++) {
                                try {
                                    var fDoc = frames[f].contentDocument || frames[f].contentWindow.document;
                                    if (fDoc) {
                                        list = list.concat(collectInputs(fDoc));
                                    }
                                } catch (e) {}
                            }
                        } catch (e) {}
                        return list;
                    }

                    var allInputs = collectInputs(document);
                    var bestInputEntry = null;
                    var bestInputScore = -9999;

                    for (var i = 0; i < allInputs.length; i++) {
                        var entry = allInputs[i];
                        var el = entry.el;
                        var t = (el.type || 'text').toLowerCase();
                        if (t === 'hidden' || t === 'submit' || t === 'button' || t === 'checkbox' || t === 'radio' || t === 'reset' || t === 'file') {
                            continue;
                        }
                        if (el.disabled || el.readOnly || !isVisible(el)) continue;

                        var meta = [
                            el.placeholder || '',
                            el.name || '',
                            el.id || '',
                            el.getAttribute('aria-label') || '',
                            el.className || ''
                        ].join(' ').toLowerCase();

                        var score = 10;
                        if (meta.indexOf('search') !== -1 || meta.indexOf('بحث') !== -1) score -= 50;
                        for (var k = 0; k < inputKeywords.length; k++) {
                            if (meta.indexOf(inputKeywords[k]) !== -1) {
                                score += 100;
                                break;
                            }
                        }
                        if (t === 'text' || t === 'tel' || t === 'number') score += 15;
                        if (score > bestInputScore) {
                            bestInputScore = score;
                            bestInputEntry = entry;
                        }
                    }

                    if (!bestInputEntry || bestInputScore < 0) {
                        return JSON.stringify({ filled: false, submitted: false, reason: 'no_input' });
                    }

                    var targetInput = bestInputEntry.el;
                    var targetDoc = bestInputEntry.doc;

                    var nativeSetter = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                    if (nativeSetter && nativeSetter.set) {
                        nativeSetter.set.call(targetInput, cardCode);
                    } else {
                        targetInput.value = cardCode;
                    }
                    targetInput.setAttribute('value', cardCode);
                    targetInput.dispatchEvent(new Event('input', { bubbles: true }));
                    targetInput.dispatchEvent(new Event('change', { bubbles: true }));
                    targetInput.dispatchEvent(new KeyboardEvent('keyup', { bubbles: true, key: 'Enter' }));

                    if (!shouldAutoClick) {
                        return JSON.stringify({ filled: true, submitted: false, reason: 'auto_click_disabled' });
                    }

                    var candidates = targetDoc.querySelectorAll('button, input[type="submit"], input[type="button"], a, [role="button"], [onclick]');
                    var bestBtn = null;
                    var bestBtnScore = -9999;
                    var inputForm = targetInput.form;

                    for (var b = 0; b < candidates.length; b++) {
                        var btn = candidates[b];
                        if (btn.disabled || !isVisible(btn)) continue;

                        var rawText = (
                            (btn.innerText || '') + ' ' +
                            (btn.textContent || '') + ' ' +
                            (btn.value || '') + ' ' +
                            (btn.getAttribute('aria-label') || '') + ' ' +
                            (btn.title || '')
                        ).trim().toLowerCase();

                        var skip = false;
                        for (var ig = 0; ig < ignoredButtonKeywords.length; ig++) {
                            if (rawText.indexOf(ignoredButtonKeywords[ig]) !== -1) {
                                skip = true;
                                break;
                            }
                        }
                        if (skip) continue;

                        var btnScore = 0;
                        var matched = false;
                        for (var bk = 0; bk < buttonKeywords.length; bk++) {
                            if (rawText.indexOf(buttonKeywords[bk]) !== -1) {
                                btnScore += 100 + Math.min(buttonKeywords[bk].length * 3, 40);
                                matched = true;
                                break;
                            }
                        }

                        var btnType = (btn.type || '').toLowerCase();
                        if (btnType === 'submit') {
                            btnScore += 50;
                            matched = true;
                        }
                        if (inputForm && btn.form === inputForm) {
                            btnScore += 45;
                            matched = true;
                        }
                        if (!matched) continue;

                        if (btn.tagName === 'BUTTON' || btn.tagName === 'INPUT') btnScore += 20;
                        if (btnScore > bestBtnScore) {
                            bestBtnScore = btnScore;
                            bestBtn = btn;
                        }
                    }

                    var submitted = false;
                    if (bestBtn) {
                        setTimeout(function() {
                            try { bestBtn.click(); } catch (e) {
                                if (inputForm) {
                                    if (typeof inputForm.requestSubmit === 'function') inputForm.requestSubmit();
                                    else inputForm.submit();
                                }
                            }
                        }, 160);
                        submitted = true;
                    } else if (inputForm) {
                        setTimeout(function() {
                            try {
                                if (typeof inputForm.requestSubmit === 'function') inputForm.requestSubmit();
                                else inputForm.submit();
                            } catch (e) {}
                        }, 160);
                        submitted = true;
                    }

                    return JSON.stringify({ filled: true, submitted: submitted, reason: submitted ? 'ok' : 'no_button' });
                } catch (err) {
                    return JSON.stringify({ filled: false, submitted: false, reason: String(err) });
                }
            })();
        """.trimIndent()
    }

    fun buildBalancePageCheckScript(): String {
        return """
            (function() {
                try {
                    var bodyText = (document.body && document.body.innerText ? document.body.innerText : '').toLowerCase();
                    var url = (window.location.href || '').toLowerCase();
                    var inputs = document.querySelectorAll('input');
                    var hasLoginInput = false;
                    for (var i = 0; i < inputs.length; i++) {
                        var el = inputs[i];
                        var t = (el.type || 'text').toLowerCase();
                        if (t === 'hidden' || t === 'submit' || t === 'button' || t === 'checkbox' || t === 'radio') continue;
                        var style = window.getComputedStyle(el);
                        var rect = el.getBoundingClientRect();
                        if (style.display !== 'none' && style.visibility !== 'hidden' && rect.width > 0 && rect.height > 0) {
                            hasLoginInput = true;
                            break;
                        }
                    }

                    var balanceWords = [
                        'الرصيد', 'الوقت المتبقي', 'المتبقي', 'الصلاحية', 'البيانات',
                        'التحميل', 'التنزيل', 'تسجيل الخروج', 'قطع الاتصال', 'تم تسجيل الدخول',
                        'status', 'uptime', 'time left', 'bytes', 'remaining', 'logout', 'welcome'
                    ];
                    var errorWords = [
                        'غير صحيح', 'خاطئ', 'خطأ', 'منتهي', 'تم استخدام', 'invalid', 'expired', 'wrong', 'failed'
                    ];
                    var hasBalanceKeywords = balanceWords.some(function(w) { return bodyText.indexOf(w) !== -1; });
                    var hasErrorKeywords = errorWords.some(function(w) { return bodyText.indexOf(w) !== -1; });

                    return JSON.stringify({
                        hasLoginInput: hasLoginInput,
                        hasBalanceKeywords: hasBalanceKeywords,
                        hasErrorKeywords: hasErrorKeywords,
                        url: url
                    });
                } catch (e) {
                    return JSON.stringify({
                        hasLoginInput: true,
                        hasBalanceKeywords: false,
                        hasErrorKeywords: false,
                        url: ''
                    });
                }
            })();
        """.trimIndent()
    }
}

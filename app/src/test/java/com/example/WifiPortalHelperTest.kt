package com.example

import com.example.utils.WifiPortalHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiPortalHelperTest {

    @Test
    fun detectsStatusAndBalanceUrlsAccurately() {
        assertTrue(WifiPortalHelper.isBalanceOrStatusUrl("http://10.0.0.1/status"))
        assertTrue(WifiPortalHelper.isBalanceOrStatusUrl("http://ataq.net/status.html"))
        assertTrue(WifiPortalHelper.isBalanceOrStatusUrl("http://c.net/alogin"))
        assertTrue(WifiPortalHelper.isBalanceOrStatusUrl("http://192.168.88.1/balance?user=123"))

        assertFalse(WifiPortalHelper.isBalanceOrStatusUrl("http://10.0.0.1/login"))
        assertFalse(WifiPortalHelper.isBalanceOrStatusUrl("http://ataq.net/login.html"))
        assertFalse(WifiPortalHelper.isBalanceOrStatusUrl(""))
    }

    @Test
    fun escapesJsStringsSafely() {
        assertEquals("6891077607", WifiPortalHelper.escapeJsString("6891077607"))
        assertEquals("ab\\'cd\\\\ef", WifiPortalHelper.escapeJsString("ab'cd\\ef"))
    }

    @Test
    fun buildsAutoFillScriptWithCodeAndKeywords() {
        val script = WifiPortalHelper.buildAutoFillAndSubmitScript("6l6gns7w", autoClick = true)
        assertTrue(script.contains("var cardCode = '6l6gns7w';"))
        assertTrue(script.contains("var shouldAutoClick = true;"))
        assertTrue(script.contains("رقم الكرت"))
        assertTrue(script.contains("دخول للإنترنت"))
        assertTrue(script.contains("تسجيل الدخول"))
    }
}

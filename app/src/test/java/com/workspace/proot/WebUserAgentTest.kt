package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.11.0：通用移动 Chrome UA 派生规则。 */
class WebUserAgentTest {

    private val webViewUa =
        "Mozilla/5.0 (Linux; Android 13; Pixel 7 Build/TQ3A.230805.001; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/119.0.6045.163 Mobile Safari/537.36"

    @Test
    fun `去掉 wv 与 Version 4 0`() {
        val ua = WebUserAgent.genericMobile(webViewUa)
        assertFalse("不许留 WebView 标记：$ua", ua.contains("; wv"))
        assertFalse("不许留旧版标记：$ua", ua.contains("Version/4.0"))
        assertTrue("Chrome 版本必须保留：$ua", ua.contains("Chrome/119.0.6045.163"))
        assertTrue("仍是移动 UA：$ua", ua.contains("Mobile Safari/537.36"))
        assertTrue("仍是 Android UA：$ua", ua.contains("Android 13"))
    }

    @Test
    fun `幂等`() {
        val once = WebUserAgent.genericMobile(webViewUa)
        assertEquals(once, WebUserAgent.genericMobile(once))
    }

    @Test
    fun `不误伤机型里的 wv 字样`() {
        val ua = "Mozilla/5.0 (Linux; Android 14; WVDevice Build/ABC; wv) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/120.0.0.0 Mobile Safari/537.36"
        val cleaned = WebUserAgent.genericMobile(ua)
        assertTrue("机型里的 WV 不能被删：$cleaned", cleaned.contains("WVDevice"))
        assertFalse("但 WebView 标记要删：$cleaned", cleaned.contains("; wv"))
    }

    @Test
    fun `空串原样返回`() {
        assertEquals("", WebUserAgent.genericMobile(""))
    }
}

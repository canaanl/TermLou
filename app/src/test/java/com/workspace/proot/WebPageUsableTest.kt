package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 落地页可用性判定的锁定测试（5.9.4）。
 *
 * ## 为什么要判
 *
 * WebView 导航失败时会加载它**自己的错误页**（`chrome-error://chromewebdata/`），
 * 而这个错误页**照样触发 `onPageFinished`**——所以 `ready` 为真却落地在
 * `net::ERR_TIMED_OUT` 上。真机上"gamersky 点了不跳转、落地是错误页"就是这个机制，
 * 而此前它会以 `ok:true` 溜过去。这条测试把"识别错误页"的规则钉死。
 */
class WebPageUsableTest {

    private fun judge(
        protocol: String,
        url: String = "https://example.com/",
        title: String = "Example Domain",
        text: String = "hello world"
    ) = WebPageUsable.judge(protocol, url, title, text)

    // ---------- 正常页 ----------

    @Test
    fun `正常页面判为可用`() {
        val v = judge("https:")
        assertTrue(v is WebPageUsable.Verdict.Usable)
        assertEquals("Example Domain", (v as WebPageUsable.Verdict.Usable).title)
    }

    @Test
    fun `http 页面也可用`() {
        assertTrue(judge("http:") is WebPageUsable.Verdict.Usable)
    }

    @Test
    fun `about 页面可用`() {
        assertTrue(judge("about:") is WebPageUsable.Verdict.Usable)
    }

    // ---------- WebView 自己的错误页 ----------

    @Test
    fun `chrome-error 协议判为错误页`() {
        val v = judge("chrome-error:", url = "chrome-error://chromewebdata/")
        assertTrue(v is WebPageUsable.Verdict.ErrorPage)
    }

    @Test
    fun `chrome-error 即便没有 ERR_ 字样也能认出`() {
        val v = judge("chrome-error:", title = "", text = "")
        assertTrue("协议就是铁证，不该依赖文本", v is WebPageUsable.Verdict.ErrorPage)
    }

    // ---------- 内容里带错误码 ----------

    @Test
    fun `正文含 ERR_TIMED_OUT 判为错误页`() {
        val v = judge(
            "https:",
            title = "无法访问此网站",
            text = "无法访问此网页\nnet::ERR_TIMED_OUT\n请检查网络连接。"
        )
        assertTrue(v is WebPageUsable.Verdict.ErrorContent)
        assertEquals("ERR_TIMED_OUT", (v as WebPageUsable.Verdict.ErrorContent).code)
    }

    @Test
    fun `标题里的错误码也能认`() {
        val v = judge("https:", title = "ERR_CONNECTION_REFUSED", text = "")
        assertTrue(v is WebPageUsable.Verdict.ErrorContent)
    }

    @Test
    fun `正常页面提到 ERR_ 这个词不算错误`() {
        // 页面正文提到 "ERR_" 但后面不是已知错误码 → 不能误判
        val v = judge("https:", text = "handle ERR_ and ERR_END properly")
        assertTrue("不该误判为错误页", v is WebPageUsable.Verdict.Usable)
    }

    @Test
    fun `页面提到未收录的错误码也不误判`() {
        // 只认 WebView 已知的那批；宁可漏判也不把正常页当成错误页
        val v = judge("https:", text = "we define ERR_WHATEVER in our code")
        assertTrue(v is WebPageUsable.Verdict.Usable)
    }

    @Test
    fun `已知错误码即使夹在别的文字里也能认出来`() {
        val v = judge("https:", text = "前缀噪声 ERR_CONNECTION_RESET 后缀噪声")
        assertTrue(v is WebPageUsable.Verdict.ErrorContent)
        assertEquals("ERR_CONNECTION_RESET", (v as WebPageUsable.Verdict.ErrorContent).code)
    }

    @Test
    fun `正文里其他带 ERR_ 单词的页面不误判`() {
        // 页面自己定义了 ERR_WHATEVER 这种标识符（不是 WebView 错误码），不该误判
        val v = judge("https:", text = "we log ERR_WHATEVER and ERR_OUR_OWN here")
        assertTrue(v is WebPageUsable.Verdict.Usable)
    }

    @Test
    fun `正文里出现已收录的真实错误码仍会判为错误页`() {
        // 反过来验一遍白名单不是太窄：真实错误码必须能认出来
        val v = judge("https:", text = "load failed: ERR_FAILED")
        assertTrue(v is WebPageUsable.Verdict.ErrorContent)
    }

    // ---------- 错误码提取 ----------

    @Test
    fun `能提取 net 前缀的错误码`() {
        assertEquals("ERR_TIMED_OUT", WebPageUsable.errorCodeIn("blah net::ERR_TIMED_OUT blah"))
        assertEquals("ERR_NAME_NOT_RESOLVED", WebPageUsable.errorCodeIn("net::ERR_NAME_NOT_RESOLVED"))
    }

    @Test
    fun `空文本或无错误码时返回 null`() {
        assertNull(WebPageUsable.errorCodeIn(""))
        assertNull(WebPageUsable.errorCodeIn("nothing here"))
    }

    @Test
    fun `只有 ERR_ 四个字符不算错误码`() {
        assertNull("孤零零的 ERR_ 不构成错误码", WebPageUsable.errorCodeIn("ERR_"))  
        assertNull(WebPageUsable.errorCodeIn("a ERR_ b"))
    }

    // ---------- 错误码转人话 ----------

    @Test
    fun `常见错误码有人话说明`() {
        // 英文说明在 net:: 前缀后，agent 可直接按前缀匹配
        assertTrue(WebPageUsable.describeErrorCode("ERROR_TIMEOUT").contains("net::ERR_TIMED_OUT"))
        assertTrue(WebPageUsable.describeErrorCode("ERROR_HOST_LOOKUP").contains("ERR_NAME_NOT_RESOLVED"))
        assertTrue(WebPageUsable.describeErrorCode("ERROR_CONNECT").contains("ERR_CONNECTION_REFUSED"))
        // 末尾附中文，便于人读
        assertTrue(WebPageUsable.describeErrorCode("ERROR_TIMEOUT").contains("加载失败"))
    }

    @Test
    fun `HTTP 错误码原样带出`() {
        assertTrue(WebPageUsable.describeErrorCode("HTTP_404").contains("404"))
        assertTrue(WebPageUsable.describeErrorCode("HTTP_503").contains("503"))
    }

    @Test
    fun `未知错误码不崩`() {
        assertFalse(WebPageUsable.describeErrorCode(null).isEmpty())
        assertFalse(WebPageUsable.describeErrorCode("").isEmpty())
        assertTrue(WebPageUsable.describeErrorCode("SOMETHING_NEW").contains("SOMETHING_NEW"))
    }

    @Test
    fun `错误码也能从已收录的名字反查`() {
        assertTrue(WebPageUsable.errorCodeIn("net::ERR_IO failed") == "ERR_IO")
        assertTrue(WebPageUsable.errorCodeIn("ERR_TOO_MANY_REQUESTS") == "ERR_TOO_MANY_REQUESTS")
        assertTrue(WebPageUsable.errorCodeIn("ERR_REDIRECT_LOOP") == "ERR_REDIRECT_LOOP")
    }

    @Test
    fun `错误页判定优先看协议再看文本`() {
        // 同时满足两种特征时，协议（铁证）优先
        val v = judge("chrome-error:", text = "net::ERR_TIMED_OUT")
        assertTrue(v is WebPageUsable.Verdict.ErrorPage)
    }
}

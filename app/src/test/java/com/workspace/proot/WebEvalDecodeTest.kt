package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `evaluateJavascript` 返回值解码的锁定测试（5.9.0）。
 *
 * 这是真机上踩到的一个坑：回调给的是 **JSON 编码值**，不解码的话
 * `html` / `eval` 回来的就是带引号、`\n` 转义的一坨，agent 拿去解析必错。
 */
class WebEvalDecodeTest {

    @Test
    fun `字符串去掉外层引号`() {
        assertEquals("<p>hi</p>", WebProtocol.decodeEvalResult("\"<p>hi</p>\""))
    }

    @Test
    fun `换行被还原成真换行`() {
        assertEquals("a\nb", WebProtocol.decodeEvalResult("\"a\\nb\""))
    }

    @Test
    fun `引号与反斜杠被还原`() {
        assertEquals("say \"hi\"", WebProtocol.decodeEvalResult("\"say \\\"hi\\\"\""))
        assertEquals("a\\b", WebProtocol.decodeEvalResult("\"a\\\\b\""))
    }

    @Test
    fun `tab 与回车也被还原`() {
        assertEquals("a\tb", WebProtocol.decodeEvalResult("\"a\\tb\""))
        assertEquals("a\rb", WebProtocol.decodeEvalResult("\"a\\rb\""))
    }

    @Test
    fun `JS 的 null 变成 Kotlin 的 null`() {
        assertNull(WebProtocol.decodeEvalResult("null"))
        assertNull(WebProtocol.decodeEvalResult(null))
    }

    @Test
    fun `空字符串不会当成 null`() {
        assertEquals("", WebProtocol.decodeEvalResult("\"\""))
    }

    @Test
    fun `数字与布尔原样返回`() {
        assertEquals("42", WebProtocol.decodeEvalResult("42"))
        assertEquals("true", WebProtocol.decodeEvalResult("true"))
        assertEquals("false", WebProtocol.decodeEvalResult("false"))
    }

    @Test
    fun `对象与数组保持 JSON 文本`() {
        assertEquals("{\"a\":1}", WebProtocol.decodeEvalResult("{\"a\":1}"))
        assertEquals("[1,2]", WebProtocol.decodeEvalResult("[1,2]"))
    }

    @Test
    fun `内嵌引号的 HTML 能完整还原`() {
        val html = "<div class=\"box\" data-x='1'>文本</div>"
        val encoded = org.json.JSONObject().put("k", html).toString()
            .substringAfter("\"k\":").removeSuffix("}")
        assertEquals(html, WebProtocol.decodeEvalResult(encoded))
    }

    @Test
    fun `超时的结果与页面返回 null 能分开`() {
        val timeout: WebProtocol.EvalOutcome = WebProtocol.EvalOutcome.Timeout(20_000)
        val nullValue: WebProtocol.EvalOutcome = WebProtocol.EvalOutcome.Value(null)
        assertTrue(timeout is WebProtocol.EvalOutcome.Timeout)
        assertTrue(nullValue is WebProtocol.EvalOutcome.Value)
        assertNull((nullValue as WebProtocol.EvalOutcome.Value).value)
    }
}

package com.workspace.proot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无头浏览器的 HTTP/JSON 协议锁定测试（5.9.0）。
 *
 * 这是 agent 与 app 之间唯一的契约：解析错一个字，Linux 侧的 skill 就废了，
 * 所以请求解析、响应格式、说明书内容都逐条锁死。
 */
class WebProtocolTest {

    private fun raw(
        method: String = "POST",
        target: String = "/op",
        headers: List<String>? = null,
        body: String = "{\"op\":\"open\",\"url\":\"x\"}"
    ): ByteArray {
        val bodyBytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$method $target HTTP/1.1\r\n")
            append("Host: 127.0.0.1:39080\r\n")
            for (h in headers ?: listOf("X-Token: t0ken", "Content-Length: ${bodyBytes.size}")) {
                append("$h\r\n")
            }
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1)
        return head + bodyBytes
    }

    // ---------- 请求解析 ----------

    @Test
    fun `解析方法 路径 令牌与请求体`() {
        val r = WebProtocol.parseRequest(raw())!!
        assertEquals("POST", r.method)
        assertEquals("/op", r.path)
        assertEquals("t0ken", r.token)
        assertEquals("{\"op\":\"open\",\"url\":\"x\"}", r.body)
    }

    @Test
    fun `查询串不计入路径`() {
        val r = WebProtocol.parseRequest(raw(target = "/op?wait=1"))!!
        assertEquals("/op", r.path)
    }

    @Test
    fun `头名大小写不敏感`() {
        val r = WebProtocol.parseRequest(
            raw(headers = listOf("x-TOKEN: abc", "content-length: 2"), body = "{}")
        )!!
        assertEquals("abc", r.token)
        assertEquals("{}", r.body)
    }

    @Test
    fun `没有请求体时体为空串`() {
        val r = WebProtocol.parseRequest(
            raw(method = "GET", target = "/help", headers = emptyList())
        )!!
        assertEquals("", r.body)
        assertEquals("", r.token)
    }

    @Test
    fun `中文 UTF-8 请求体按字节长度读回不截断`() {
        val body = "{\"op\":\"open\",\"url\":\"https://例子.测试/路径\"}"
        val bytes = body.toByteArray(Charsets.UTF_8)
        val r = WebProtocol.parseRequest(
            raw(headers = listOf("Content-Length: ${bytes.size}"), body = body)
        )!!
        assertEquals(body, r.body)
    }

    @Test
    fun `残缺请求返回 null 而不是崩`() {
        assertNull(WebProtocol.parseRequest(byteArrayOf()))
        assertNull(WebProtocol.parseRequest("GARBAGE\r\n\r\n".toByteArray()))
        assertNull(WebProtocol.parseRequest("GET\r\n\r\n".toByteArray()))
    }

    // ---------- 请求体取值 ----------

    @Test
    fun `取出 op 与字段`() {
        val r = WebProtocol.parseRequest(raw())!!
        assertEquals("open", WebProtocol.bodyOp(r))
        assertEquals("x", WebProtocol.bodyString(r, "url"))
        assertEquals(7, WebProtocol.bodyInt(r, "nope", 7))
    }

    @Test
    fun `wait 毫秒数能被解析出来`() {
        val body = "{\"op\":\"wait\",\"ms\":8000}"
        val r = WebProtocol.parseRequest(
            raw(headers = listOf("Content-Length: ${body.toByteArray().size}"), body = body)
        )!!
        assertEquals("wait", WebProtocol.bodyOp(r))
        assertEquals(8000, WebProtocol.bodyInt(r, "ms", 10_000))
    }

    @Test
    fun `wait 缺省时用默认值`() {
        val body = "{\"op\":\"open\",\"url\":\"x\"}"
        val r = WebProtocol.parseRequest(
            raw(headers = listOf("Content-Length: ${body.toByteArray().size}"), body = body)
        )!!
        assertEquals(0, WebProtocol.bodyInt(r, "wait", 0))
    }

    @Test
    fun `体不是合法 JSON 时 op 为 null 而不是抛异常`() {
        val r = WebProtocol.parseRequest(
            raw(headers = listOf("Content-Length: 3"), body = "{{{")
        )!!
        assertNull(WebProtocol.bodyOp(r))
        assertEquals("", WebProtocol.bodyString(r, "url"))
    }

    // ---------- 响应 ----------

    @Test
    fun `响应头含正确的状态码 长度与连接关闭`() {
        val resp = String(WebProtocol.httpResponse(200, "hi", "application/json"), Charsets.ISO_8859_1)
        assertTrue(resp.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(resp.contains("Content-Length: 2\r\n"))
        assertTrue(resp.contains("Connection: close\r\n"))
        assertTrue(resp.endsWith("\r\n\r\nhi"))
    }

    @Test
    fun `401 与 404 用各自的短语`() {
        assertTrue(
            String(WebProtocol.httpResponse(401, "", "t"), Charsets.ISO_8859_1)
                .startsWith("HTTP/1.1 401 Unauthorized")
        )
        assertTrue(
            String(WebProtocol.httpResponse(404, "", "t"), Charsets.ISO_8859_1)
                .startsWith("HTTP/1.1 404 Not Found")
        )
    }

    @Test
    fun `成功响应里每个键都在且 ok 为 true`() {
        val o = JSONObject(WebProtocol.okJson("msg" to "pong", "port" to 39080))
        assertTrue(o.getBoolean("ok"))
        assertEquals("pong", o.getString("msg"))
        assertEquals(39080, o.getInt("port"))
    }

    @Test
    fun `失败响应 ok 为 false 且带 error`() {
        val o = JSONObject(WebProtocol.errJson("bad token"))
        assertTrue(!o.getBoolean("ok"))
        assertEquals("bad token", o.getString("error"))
    }

    @Test
    fun `错误信息里的引号不会破坏 JSON`() {
        val o = JSONObject(WebProtocol.errJson("""他说"不行""""))
        assertEquals("""他说"不行"""", o.getString("error"))
    }

    // ---------- 说明书 ----------

    @Test
    fun `说明书含端口令牌与全部指令`() {
        val help = WebProtocol.help(39080, "deadbeef")
        assertTrue(help.contains("39080"))
        assertTrue(help.contains("deadbeef"))
        for (op in listOf("open", "wait", "eval", "html", "text", "click", "type", "select", "shot", "back", "reload", "cookies", "clear", "close", "ping")) {
            assertTrue("说明书缺少 $op", help.contains("\"$op\""))
        }
        assertTrue(help.contains("~/web/web.env"))
        assertTrue(help.contains("/help"))
    }

    @Test
    fun `说明书是中英双语的`() {
        val help = WebProtocol.help(39080, "t")
        assertTrue(help.contains("TermLou 无头浏览器"))
        assertTrue(help.contains("headless browser"))
        assertTrue(help.contains("No history"))
    }

    @Test
    fun `端口常量与说明书一致`() {
        assertEquals(39080, WebProtocol.DEFAULT_PORT)
        assertTrue(WebProtocol.help(WebProtocol.DEFAULT_PORT, "t").contains("127.0.0.1:39080"))
    }

    @Test
    fun `视口是手机竖屏尺寸`() {
        assertTrue(WebProtocol.VIEWPORT_W_DP in 360..440)
        assertTrue(WebProtocol.VIEWPORT_H_DP in 700..1000)
        assertNotNull(WebProtocol.VIEWPORT_W_DP)
    }
    @Test
    fun `说明书写明只监听回环与 cookie 的范围`() {
        val help = WebProtocol.help(39080, "t")
        assertTrue("应说明只听 127.0.0.1", help.contains("127.0.0.1"))
        assertTrue("应说明 cookie 只覆盖当前页", help.contains("current page"))
        assertTrue("应说明截图不会给白图", help.contains("blank"))
    }

    @Test
    fun `说明书含选择器两种写法`() {
        val help = WebProtocol.help(39080, "t")
        assertTrue(help.contains("input[name=q]"))
        assertTrue(help.contains("text="))
    }

    @Test
    fun `说明书里的示例都带令牌头`() {
        val help = WebProtocol.help(39080, "abc")
        assertTrue(help.contains("X-Token: abc"))
        assertTrue(help.contains("X-Token: \$TOKEN"))
    }

    @Test
    fun `503 有自己的状态短语`() {
        val resp = String(WebProtocol.httpResponse(503, "{}", "t"), Charsets.ISO_8859_1)
        assertTrue(resp.startsWith("HTTP/1.1 503 Service Unavailable"))
    }
}

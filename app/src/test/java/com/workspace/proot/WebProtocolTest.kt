package com.workspace.proot

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 浏览器的 HTTP/JSON 协议锁定测试（5.9.0）。
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
    fun `说明书含端口与全部指令但绝不印令牌`() {
        val help = WebProtocol.help(39080)
        assertTrue(help.contains("39080"))
        // 5.9.9：`/help` 改成要令牌了，**说明书里必须不再出现真令牌**。
        // 此前它免鉴权却又把 `X-Token: <真令牌>` 印在里面 ——
        // Android 上任何 app 访问 127.0.0.1 都不用权限，一条 curl 就拿到完整凭据。
        assertFalse("说明书绝不许印令牌", help.contains("deadbeef"))
        assertTrue("得说清令牌从哪儿来", help.contains("web.env"))
        for (op in listOf("open", "wait", "eval", "html", "text", "click", "type", "select", "shot", "back", "reload", "cookies", "clear", "close", "ping")) {
            assertTrue("说明书缺少 $op", help.contains("\"$op\""))
        }
        // 5.9.5：此前这里断言的是 `~/web/web.env` —— 把错路径锁住了。Linux 里工作区挂在
        // /workspace，`~` 是 rootfs 里的 /root，所以那条路径 agent 根本打不开。
        assertTrue(help.contains("${WebProtocol.WEB_DIR}/web.env"))
        assertFalse("产物路径不许再写成 ~/web（那是 rootfs 里的 /root）", help.contains("~/web"))
        assertTrue(help.contains("/help"))
    }

    @Test
    fun `说明书是中英双语的`() {
        val help = WebProtocol.help(39080)
        assertTrue("中文名要同步：$help", help.contains("TermLou 浏览器"))
        assertTrue("英文名要同步：$help", help.contains("TermLou Browser"))
        assertTrue(help.contains("No history"))
    }

    @Test
    fun `端口常量与说明书一致`() {
        assertEquals(39080, WebProtocol.DEFAULT_PORT)
        assertTrue(WebProtocol.help(WebProtocol.DEFAULT_PORT).contains("127.0.0.1:39080"))
    }

    @Test
    fun `视口不再是常数而是窗口尺寸`() {
        // 5.9.34：以前是 VIEWPORT_W_DP=412 锁死、再缩进小窗 ——
        // 而安卓是照着屏幕上实际大小决定网页要画多少的，缩放一压就只画那一小块，
        // 整页截图于是永远停在第 2 屏。现在视口 = 窗口 = 屏宽 ÷ 3。
        val src = readMain("WebProtocol.kt")
        assertFalse(
            "视口常数必须删掉：它和窗口是两个数，中间那层缩放正是坏图的来源\n$src",
            src.contains("VIEWPORT_W_DP")
        )
    }
    @Test
    fun `说明书写明只监听回环与 cookie 的范围`() {
        val help = WebProtocol.help(39080)
        assertTrue("应说明只听 127.0.0.1", help.contains("127.0.0.1"))
        assertTrue("应说明 cookie 只覆盖当前页", help.contains("current page"))
        assertTrue("应说明截图不会给白图", help.contains("blank"))
    }

    @Test
    fun `说明书含选择器两种写法`() {
        val help = WebProtocol.help(39080)
        assertTrue(help.contains("input[name=q]"))
        assertTrue(help.contains("text="))
    }

    @Test
    fun `说明书里的示例都带令牌头但只留占位符`() {
        val help = WebProtocol.help(39080)
        // 5.9.9：示例里保留 `X-Token: $TOKEN`（从 web.env 取），但**不许出现真令牌**
        assertTrue(help.contains("X-Token: \$TOKEN"))
        assertFalse("示例里不许塞真令牌", help.contains("X-Token: abc"))
    }

    @Test
    fun `503 有自己的状态短语`() {
        val resp = String(WebProtocol.httpResponse(503, "{}", "t"), Charsets.ISO_8859_1)
        assertTrue(resp.startsWith("HTTP/1.1 503 Service Unavailable"))
    }
    @Test
    fun `说明书含 diag 指令`() {
        val help = WebProtocol.help(39080)
        assertTrue("应含 diag", help.contains("\"diag\""))
    }

    @Test
    fun `说明书区分 ready 与 usable`() {
        val help = WebProtocol.help(39080)
        assertTrue("应解释 usable 的含义", help.contains("usable"))
        assertTrue("应说明错误页也会触发页面事件", help.contains("error page") || help.contains("错误页"))
    }

    @Test
    fun `示例不再依赖某个站点的具体文案`() {
        // 5.9.4：旧示例写 text=More information，而 example.com 的链接文字其实是
        // "Learn more"，示例本身就是错的。现在示例用 eval/text:h1 这种与站点无关的写法。
        val help = WebProtocol.help(39080)
        assertFalse("不该再出现 More information", help.contains("More information"))
        assertTrue(help.contains("document.title"))
    }

    // ---------- 5.9.33：说明书跟着实际行为走 ----------

    @Test
    fun `说明书里不许再提1_5倍门槛`() {
        // 门槛 5.9.33 已删。说明书还写"比视口高 1.5 倍"的话，agent 会以为
        // 一屏半高的页面只有一张图 —— 而实际是比一屏高就分屏。
        val help = WebProtocol.help(39080)
        assertFalse("门槛已删，说明书不该再提 1.5 倍：\n$help", help.contains("1.5 倍"))
    }

    @Test
    fun `说明书说清一屏一张不拼接`() {
        // 用户明说：不许拼接整页长图。agent 拿到 files 就该知道要按顺序读几张，
        // 而不是去找一张 tall image。
        val help = WebProtocol.help(39080)
        assertTrue("要写明一屏一张：\n$help", help.contains("一屏拍一张"))
        assertTrue("要写明不拼接：\n$help", help.contains("不拼接"))
        assertTrue("英文也要说一句：\n$help", help.contains("never stitched"))
    }

    @Test
    fun `说明书说清shot不带参数且报哪些字段`() {
        // 接口一个字都不许加参数 —— 说明书里的示例必须还是裸的 {"op":"shot"}
        val help = WebProtocol.help(39080)
        assertTrue("示例必须是裸 shot：\n$help", help.contains("{\"op\":\"shot\"}"))
        assertFalse(
            "shot 不带参数，说明书里不许出现 shot 跟参数同行：\n$help",
            Regex("\\{\"op\":\"shot\"\\s*,").containsMatchIn(help)
        )
        for (f in listOf("files", "screens", "page_height", "full_page")) {
            assertTrue("字段 $f 要写进说明书", help.contains(f))
        }
    }

    @Test
    fun `说明书说清width是一屏page_height才是整页`() {
        // 这两个最容易用错：width/height 是**一屏**的尺寸。
        // agent 拿 width/height 当整页尺寸去算缩放就会错。
        val help = WebProtocol.help(39080)
        assertTrue(
            "要写明 width/height 是一屏的尺寸：\n$help",
            help.contains("width/height 是**一屏**的尺寸")
        )
    }

    @Test
    fun `说明书说清部分失败是ok_false加partial`() {
        // 半张图报成功比修不好更糟 —— 说明书必须让 agent 知道
        // partial:true 时 files 只是已经拍好的那几屏，不是全的。
        val help = WebProtocol.help(39080)
        assertTrue("要写明 partial：\n$help", help.contains("partial:true"))
        assertTrue("要写明照交已拍好的屏：\n$help", help.contains("照交"))
    }
}

/** 从测试的工作目录往上找 `app/src/main/...` —— 单测的工作目录不是仓库根。 */
private fun readMain(name: String): String {
    var d: File? = File("").absoluteFile
    var hops = 0
    while (d != null && hops < 6) {
        val f = File(d, "app/src/main/java/com/workspace/proot/$name")
        if (f.isFile) return f.readText()
        d = d.parentFile
        hops++
    }
    throw AssertionError("找不到 $name，这条检查等于没跑")
}

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
        for (op in listOf(
            "open", "wait", "eval", "html", "extract", "text", "click", "type", "select",
            "reload", "clear", "close", "ping", "diag"
        )) {
            assertTrue("说明书缺少 $op", help.contains("\"$op\""))
        }
        // 5.9.39：back 整个删掉（平台侧的后退在"从没被点过"的窗口里装死，见 RegressionScanTest）
        assertFalse("说明书不该再有 back 指令", help.contains("\"back\""))
        // 5.9.37 删掉整页截图。说明书里不许还留着它 ——
        // agent 会照着说明书调一条根本不存在的指令，然后拿到 "unknown op"。
        assertFalse("说明书不该再有 shot 指令", help.contains("\"shot\""))
        assertFalse("说明书不该再有 shots 目录", help.contains("shots/"))
        // 5.9.38 删掉 cookie 导出（用户要求：cookie 全删、不保留）。
        assertFalse("说明书不该再有 cookies 指令", help.contains("\"cookies\""))
        assertFalse("说明书不该再提 cookie 文件路径", help.contains("cookies.txt"))
        assertTrue("但要说清这个浏览器不留 cookie/缓存", help.contains("不保留 cookie"))
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
        // 5.9.38：原来看的是 "No history"（那句在讲无痕），现在无痕的说法改过了
        assertTrue("英文那段也要留下：$help", help.contains("keeps no cookies and no cache at all"))
    }

    @Test
    fun `端口常量与说明书一致`() {
        assertEquals(39080, WebProtocol.DEFAULT_PORT)
        assertTrue(WebProtocol.help(WebProtocol.DEFAULT_PORT).contains("127.0.0.1:39080"))
    }

    @Test
    fun `视口是手机竖屏尺寸`() {
        // 5.9.34 我把这个常数删了、改成"视口 = 窗口"（160dp），理由是"缩放是第 2 屏失败的原因"。
        // **那个理由是错的** —— 5.9.34/5.9.35 都没有缩放，第 2 屏照样失败。
        // 删掉它的代价只有画质：360px 视口里字 19px，发虚。
        //
        // 5.9.36 请回来。锁死 412dp 的意义：网页按正常手机版式排版，
        // agent 的坐标与版式一个像素都不变，且小窗里字是 48px。
        assertTrue(WebProtocol.VIEWPORT_W_DP in 360..440)
        assertTrue(WebProtocol.VIEWPORT_H_DP in 700..1000)
    }
    @Test
    fun `说明书写明只监听回环与不留 cookie`() {
        val help = WebProtocol.help(39080)
        assertTrue("应说明只听 127.0.0.1", help.contains("127.0.0.1"))
        // 5.9.37 断言过"截图不会给白图"；5.9.38 断言过 cookie 只覆盖当前页 ——
        // 截图与 cookie 都没了，换成对应的两条现在成立的话。
        assertTrue(
            "应说明 extract 扫不到时会直说",
            help.contains("扫不到东西时 note 会直说")
        )
        assertTrue(
            "应说明这个浏览器不留 cookie / 缓存（换个站点要重新登录是设计）",
            help.contains("不保留 cookie") && help.contains("这是设计，不是故障")
        )
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
        // 5.9.37：搜索流程示例里的选择器是**示意图**（#kw 之类）。
        // 说明书必须把它写成"照着 extract 的结果替换"，而不是让 agent 照抄一个假选择器。
        assertTrue(
            "搜索示例必须写清按 extract 报出来的 id 替换选择器：\n$help",
            help.contains("像真人那样用这个浏览器") && help.contains("不靠猜")
        )
    }

    // ---------- 5.9.37：说明书跟着实际行为走 ----------

    @Test
    fun `说明书里不许再提1_5倍门槛`() {
        // 门槛 5.9.33 已删，整页截图 5.9.37 也整个删了。
        // 说明书不许留任何"按屏数算"的旧说法 —— 那是已经不存在的世界。
        val help = WebProtocol.help(39080)
        assertFalse("门槛已删，说明书不该再提 1.5 倍：\n$help", help.contains("1.5 倍"))
        assertFalse("不该再提屏数：\n$help", help.contains("一屏拍一张"))
        assertFalse("不该再提不拼接：\n$help", help.contains("不拼接"))
        assertFalse("不该再提 pages/screens 那套字段：\n$help", help.contains("full_page"))
    }

    @Test
    fun `说明书说清extract的返回字段`() {
        // agent 完全靠说明书知道 extract 回什么 —— 漏一个字段它就不知道去哪儿找。
        val help = WebProtocol.help(39080)
        for (f in listOf(
            "url", "title", "description", "text",
            "inputs", "buttons", "links", "links_total", "links_truncated"
        )) {
            assertTrue("extract 字段 $f 要写进说明书：\n$help", help.contains(f))
        }
    }

    @Test
    fun `说明书说清extract的两个上限与默认值`() {
        // 用户明说：上限要能被大模型自己改。所以默认值、封顶、越界怎么办都得写进去。
        val help = WebProtocol.help(39080)
        assertTrue("要写 limit：\n$help", help.contains("\"limit\""))
        assertTrue("要写 text_chars：\n$help", help.contains("\"text_chars\""))
        // 默认值/封顶必须**引用常量**，不许在说明书里另抄一份数字（抄两份就会对不上）
        assertTrue("默认链接数：\n$help", help.contains("默认 ${WebExtract.DEFAULT_LIMIT}"))
        assertTrue("封顶链接数：\n$help", help.contains("${WebExtract.MAX_LIMIT}"))
        assertTrue("默认正文字数：\n$help", help.contains("默认 ${WebExtract.DEFAULT_TEXT_CHARS}"))
        assertTrue(
            "越界要说明会夹住并标记：\n$help",
            help.contains("limit_capped") && help.contains("limit_requested")
        )
        // 截断要明说 —— 瞒着会让 agent 以为那就是全部
        assertTrue("要说明截了会明说：\n$help", help.contains("截了会明说"))
    }

    @Test
    fun `说明书说清输入框和按钮永不截断`() {
        // 这是用户最担心的一条：怕加了上限会漏掉搜索框。
        // 说明书必须讲明白它为什么不会漏，否则 agent 会不敢用 extract。
        val help = WebProtocol.help(39080)
        assertTrue("要写明 inputs/buttons 永不截断：\n$help", help.contains("永不截断"))
        assertTrue(
            "要写明搜索框在哪张表里：\n$help",
            help.contains("搜索框就在这里")
        )
    }

    @Test
    fun `说明书说清type的回车用法`() {
        // 用户明说要加回车：真人搜索是打完字按回车，不是去找那个又小又难找的提交按钮。
        val help = WebProtocol.help(39080)
        assertTrue("要写 enter:true：\n$help", help.contains("\"enter\":true"))
        assertTrue("要说明填完立刻按回车：\n$help", help.contains("填完按回车"))
        // 回车之后页面会跳转，必须等落地再回 url —— 不然 agent 拿到的是跳转前的地址
        assertTrue("要说明回车后要等新页：\n$help", help.contains("type 的 enter:true 是**填完立刻按回车**"))
    }

    @Test
    fun `说明书给出一条完整的搜索流程`() {
        // 这个功能的全部意义就是"让 agent 像真人一样搜索"。
        // 说明书只列指令、不给顺序，agent 还是会乱来 —— 所以顺序必须写死一条范例。
        val help = WebProtocol.help(39080)
        val start = help.indexOf("搜索 ——")
        assertTrue("说明书里没有搜索那一节：\n$help", start > 0)
        val section = help.substring(start, help.indexOf("selector 选择器:", start))
        val open = section.indexOf("\"op\":\"open\"")
        val ext = section.indexOf("\"op\":\"extract\"")
        val type = section.indexOf("\"op\":\"type\"")
        val click = section.indexOf("\"op\":\"click\"")
        assertTrue("搜索那一节缺步骤：\n$section",
            open > 0 && ext > 0 && type > 0 && click > 0)
        assertTrue("顺序必须是 open → extract → type → click：\n$section",
            open < ext && ext < type && type < click)
    }

    @Test
    fun `说明书说清视口是手机宽度且缩放只在显示层`() {
        // 5.9.34 的旧说法是"视口 = 窗口（屏宽÷3）、不缩放"，5.9.36/5.9.37 已经不成立。
        // 说明书再那么写，agent 会按 160dp 去挑选择器，然后全落空。
        val help = WebProtocol.help(39080)
        assertTrue(
            "要写明按手机宽度排版：\n$help",
            help.contains("${WebProtocol.VIEWPORT_W_DP}×${WebProtocol.VIEWPORT_H_DP}dp")
        )
        assertFalse("不该再说 160dp 那种窄排版：\n$help", help.contains("160dp"))
        assertFalse("不该再说'不缩放'：\n$help", help.contains("**不缩放**"))
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

package com.workspace.proot

import org.json.JSONObject

/**
 * 浏览器的 HTTP/JSON 指令协议（5.9.0，纯逻辑、不依赖安卓，可单测）。
 *
 * 传输：**仅回环地址**的固定端口 + 每次安装固定的令牌（`X-Token` 头）；`GET /help` 免令牌
 * （纯说明书，供"skill 写丢了"时自发现），其余一律要令牌。
 *
 * 指令：`POST /op`，体为 `{"op":"…"}`，统一返回 `{"ok":true, …}` 或 `{"ok":false,"error":"…"}`。
 */
object WebProtocol {

    /** 固定端口（写进 agent skill 的常量；被占用时服务启动失败并如实报错，不静默改端口）。 */
    const val DEFAULT_PORT = 39080

    /**
     * TermLou 的 Linux 里工作区挂载在**这个**路径下。
     *
     * ⚠ **不是 `~`**（5.9.5 修）：proot 用 `-b <workspace>:/workspace` 挂工作区，
     * 而 `HOME=/root` 指向的是 rootfs 里的**另一个**目录。此前所有说明都写
     * `$WEB_DIR/...`，agent 在终端里 `cat $WEB_DIR/web.env` 直接报
     * `No such file or directory`，`shot` 返回的路径同样打不开 ——
     * "产物 Linux 可见"这个需求一直是坏的，而且被一条测试断言锁住了。
     *
     * 与 [TerminalManager.buildProotArgs] 的挂载点必须一致。
     */
    const val WORKSPACE_MOUNT = "/workspace"

    /** 产物目录在 Linux 侧的路径（端口、令牌、截图、cookie 都在底下）。 */
    const val WEB_DIR = "$WORKSPACE_MOUNT/web"

    /**
     * 视口尺寸（dp）：页面按手机竖屏渲染，截图与取文本都基于它。
     *
     * 5.9.27：**这不跟悬浮窗大小走**。窗口只有屏宽 1/3，但视口锁死在这里 ——
     * 缩放只发生在显示那一层，agent 的版式与坐标一个像素都不变。
     */
    const val VIEWPORT_W_DP = 412
    const val VIEWPORT_H_DP = 892

    data class Request(val method: String, val path: String, val token: String, val body: String)

    /** 解析我们需要的最小 HTTP 子集：请求行 + 头 + Content-Length 体；不合法返回 null。 */
    fun parseRequest(raw: ByteArray): Request? {
        val headEnd = indexOfBlankLine(raw) ?: return null
        val head = String(raw, 0, headEnd, Charsets.ISO_8859_1)
        val lines = head.split("\r\n")
        val parts = lines.firstOrNull()?.trim()?.split(" ") ?: return null
        if (parts.size < 2) return null
        var token = ""
        var contentLength = 0
        for (i in 1 until lines.size) {
            val line = lines[i]
            val c = line.indexOf(':')
            if (c <= 0) continue
            val name = line.substring(0, c).trim().lowercase()
            val value = line.substring(c + 1).trim()
            if (name == "x-token") token = value
            if (name == "content-length") contentLength = value.toIntOrNull() ?: 0
        }
        val bodyStart = headEnd + 4
        val body = if (contentLength > 0 && raw.size >= bodyStart + contentLength) {
            String(raw, bodyStart, contentLength, Charsets.UTF_8)
        } else {
            ""
        }
        return Request(
            method = parts[0].uppercase(),
            path = parts[1].substringBefore('?'),
            token = token,
            body = body
        )
    }

    private fun indexOfBlankLine(raw: ByteArray): Int? {
        for (i in 0..raw.size - 4) {
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
                raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()
            ) return i
        }
        return null
    }

    fun bodyOp(request: Request): String? =
        runCatching { JSONObject(request.body).optString("op") }.getOrNull()

    fun bodyString(request: Request, key: String): String =
        runCatching { JSONObject(request.body).optString(key) }.getOrDefault("")

    fun bodyInt(request: Request, key: String, fallback: Int): Int =
        runCatching { JSONObject(request.body).optInt(key, fallback) }.getOrDefault(fallback)

    fun bodyOptBoolean(request: Request, key: String, fallback: Boolean): Boolean =
        runCatching { JSONObject(request.body).optBoolean(key, fallback) }.getOrDefault(fallback)

    /** 统一响应体。 */
    fun okJson(vararg pairs: Pair<String, Any?>): String {
        val o = JSONObject()
        o.put("ok", true)
        for ((k, v) in pairs) o.put(k, v ?: JSONObject.NULL)
        return o.toString()
    }

    fun errJson(message: String): String =
        JSONObject().put("ok", false).put("error", message).toString()

    fun httpResponse(status: Int, body: String, contentType: String): ByteArray {
        val reason = when (status) {
            200 -> "OK"
            401 -> "Unauthorized"
            404 -> "Not Found"
            503 -> "Service Unavailable"
            else -> "Error"
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(status).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + bytes
    }

    /**
     * 一次 `eval` 的结果：**超时**和**页面返回 null**必须能分开。
     * 混成一种的话，"元素没找到"会被误报成"求值超时"，agent 会往错的方向重试。
     */
    sealed class EvalOutcome {
        /** 内核在超时时间内没有回调。 */
        data class Timeout(val ms: Long) : EvalOutcome()

        /** 有结果；[value] 为 null 表示页面里算出来的就是 null。 */
        data class Value(val value: String?) : EvalOutcome()

        /** 客户端中途断开（agent 的命令被外层杀掉）：立刻放弃，不再占着页面锁。 */
        data object Cancelled : EvalOutcome()

        /** 投递不到主线程（服务正在收尾）。 */
        data object NotPosted : EvalOutcome()

        /**
         * **发起**求值时自己抛了（5.9.9）。
         *
         * 与 [Value] 里 `value == null` 严格分开：那个是"页面算出来就是 null"，
         * 这个是"求压根没发出去"（WebView 已 destroy、上下文没了之类）。
         * 混起来的话会把宿主侧的问题引到 JS 上找原因。
         */
        data class Failed(val reason: String) : EvalOutcome()
    }

    /**
     * 解码 `WebView.evaluateJavascript` 的返回值。
     *
     * **这是必须做的一步**：回调给的是 **JSON 编码值**——字符串带引号、`\n` 被转义、
     * JS 的 `null` 会变成字符串 `"null"`。不解码的话 `html` 返回的就是一坨带引号转义的
     * 文本，agent 拿去解析必错。
     *
     * 解法是把这个 JSON 字符串字面量塞回一个对象里、交给真正的 JSON 解析器解：
     * 自己手写反转义在 HTML 里含 `<`、`>` 之类字符时会截断（真机踩过：`<p>hi</p>` 只剩 `<p>hi`）。
     */
    fun decodeEvalResult(raw: String?): String? {
        if (raw == null || raw == "null") return null
        if (raw.length >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return runCatching { JSONObject("{\"v\":" + raw + "}").optString("v") }.getOrNull()
                ?: raw.substring(1, raw.length - 1)
        }
        return raw
    }

    /**
     * `GET /help` 的说明书（中英双语，agent 也能直接读）。
     *
     * ## 5.9.9：**这里不再印令牌**
     *
     * 此前 `/help` 免令牌，而本函数又把真令牌写进 `header X-Token: xxx` 那一行 ——
     * Android 上任何 app 访问 127.0.0.1 都不需要任何权限，所以
     * **任何装在手机上的应用一条 `curl http://127.0.0.1:<port>/help` 就拿到完整凭据。**
     * 现在 `/help` 也要令牌（见 [WebAutomationService] 的 `route`），
     * 自发现走 `web.env`（app 私有目录，别的 app 读不到）。
     */
    fun help(port: Int): String = buildString {
        val host = "http://127.0.0.1:$port"
        appendLine("TermLou Browser · TermLou 浏览器")
        appendLine()
        appendLine("Endpoint 端点: http://127.0.0.1:$port   (POST /op 与 GET /help 都要 header X-Token: <令牌>)")
        appendLine("Self-discovery 自发现: cat $WEB_DIR/web.env   ·   curl -H \"X-Token: \$TOKEN\" $host/help")
        appendLine()
        appendLine("Commands 指令（全部 POST /op）:")
        appendLine("  {\"op\":\"open\",\"url\":\"https://…\",\"wait\":8000}  打开网址（复用同一页面；wait=等加载完的毫秒数）")
        appendLine("  {\"op\":\"wait\",\"ms\":10000}               单独等当前页面加载完")
        appendLine("  {\"op\":\"eval\",\"js\":\"…\"}                在页面里执行 JS，返回其值（字符串就是字符串）")
        appendLine("  {\"op\":\"html\"}                        取回当前页面 HTML")
        appendLine("  {\"op\":\"text\",\"selector\":\"…\"}         取可见文字（不给 selector = 整页正文）")
        appendLine("  {\"op\":\"click\",\"selector\":\"…\"}       真实点击（可带 wait 等新页）")
        appendLine("  {\"op\":\"type\",\"selector\":\"…\",\"text\":\"…\"}   填输入框（clear=false 追加）")
        appendLine("  {\"op\":\"select\",\"selector\":\"…\",\"value\":\"…\"}   选 <select> 的一项")
        appendLine("  {\"op\":\"shot\"}                        截图并写入 $WEB_DIR/shots/，返回路径")
        appendLine("                                          页面比视口高 1.5 倍时**滚着拍**，拆成多张：")
        appendLine("                                          files 按从上到下列出，每张就是一屏")
        appendLine("                                          （shot-0007-1.png、-2.png、-3.png…）")
        appendLine("                                          返回 files/screens/file/width/height/")
        appendLine("                                          page_height/full_page；缩放过还会有 note")
        appendLine("  {\"op\":\"back\"} / {\"op\":\"reload\"}      后退 / 重载")
        appendLine("  {\"op\":\"cookies\"}                     导出 cookie 到 $WEB_DIR/cookies.txt|json")
        appendLine("  {\"op\":\"clear\"} / {\"op\":\"close\"}      清 cookie/缓存/localStorage 并结束会话")
        appendLine("  {\"op\":\"ping\"}                        探活")
        appendLine("  {\"op\":\"diag\"}                        诊断：窗口几何 / 页面可见性 / 最近错误码")
        appendLine()
        appendLine("selector 选择器:")
        appendLine("  \"input[name=q]\"  CSS 选择器")
        appendLine("  \"text=登录\"       按可见文字找元素（text==登录 为严格相等）")
        appendLine()
        appendLine("curl 示例 example:")
        appendLine("  source $WEB_DIR/web.env")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"open\",\"url\":\"\$URL\",\"wait\":8000}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"eval\",\"js\":\"document.title\"}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"text\",\"selector\":\"h1\"}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  （选择器是 CSS 或 text=可见文字；换成你自己页面上真实存在的文字）")
        appendLine()
        appendLine("须知 notes:")
        appendLine("  · 一次只开一个页面；同一页面反复 open 即复用。")
        appendLine("  · open 的 wait 到点没加载完不报错，只回 ready:false 与 progress，可再 wait 重试。")
        appendLine("  · 截图只在页面已渲染时成功；白图会如实报错，不会给你一张空图。")
        appendLine("  · 整页截图某一屏没画出来（页面没滚动、或渲染没跟上）会**报出是第几屏**，")
        appendLine("    例如 screen 3: identical to the previous screen —— 那是这一屏的问题，")
        appendLine("    不是整张都废；等页面稳一下重试，或改用 eval 滚动后逐屏截。")
        appendLine("  · back 需要历史里有条目：点完链接先 wait 落地，再 back；导航还在途中时报错")
        appendLine("    并附上历史条目数，可照此判断是没历史还是没等完。")
        appendLine("  · cookies 只导出当前页面可见的 cookie：安卓没有枚举全部的 API。")
        appendLine("  · clear 与 close 完全等价：都是清数据 + 结束当前页面会话。")
        appendLine("  · 只监听 127.0.0.1（同一 WiFi 下别的设备连不上）。")
        appendLine("  · 元素找不到一律 ok:false，绝不会报成功 —— ok:true 表示事情真的发生了。")
        appendLine("  · 选择器报错怎么读：not found = 元素不在（换选择器或先 wait）；")
        appendLine("    bad selector (...) = 选择器本身写错了（照抄报错里的原因）；")
        appendLine("    not a text field / not a <select> = 元素找对了但类型不对。")
        appendLine("    元素在 iframe 里的话这里查不到 —— 换个直接含它的页面。")
        appendLine("  · 三个字段各管一件事，别混：")
        appendLine("    ok:false = 这次操作本身失败（选择器错、没有历史、落地是错误页）。")
        appendLine("    ready    = 页面事件走完。")
        appendLine("    usable   = 落地页真的能用来干活。")
        appendLine("  · 加载失败时 WebView 会加载它自己的错误页，而那个错误页同样会触发页面事件，")
        appendLine("    所以只看 ready 会把失败当成功。以 usable 为准。")
        appendLine("  · open 不带 wait 时 usable 一定是 false（还没渲染完），那不代表打开失败 ——")
        appendLine("    打开失败会是 ok:false。要判断就 open → wait，wait 会带回 usable。")
        appendLine("  · usable:false 看 warning 分两种：真的不能用（说了是错误页），")
        appendLine("    和没探到（warning 写 could not determine）。宁可漏判不误判。")
        appendLine("  · 碰页面的指令之间串行（同一时刻只有一条在操作页面），但 ping/diag 不会被堵住。")
        appendLine("  · 客户端中途断开（命令被超时杀掉）时，服务端立刻放弃等待，不会留下卡住的会话。")
        appendLine("  · 推荐节奏：open 不带 wait → wait 短等 → 取内容；单条 wait 上限 30 秒。")
        appendLine("  · 不带 wait 的 click 若已点出新导航，一律报 ready:false（不会拿上一页冒充）。")
        appendLine("  · 无历史/无痕：每次打开 app 与每次服务启动都会清 cookie 与缓存；不保存密码与表单。")
        appendLine("  · One page at a time; if open's wait runs out it does not error, it returns ready:false.")
        appendLine("  · ok:false = the operation itself failed; ready = page events finished;")
        appendLine("    usable = the landed page can actually be worked with.")
        appendLine("  · An unwaited open always reports usable:false (nothing rendered yet) - that is")
        appendLine("    not a failure; a failed open is ok:false. open then wait, and wait reports usable.")
        appendLine("  · Shots fail loudly instead of returning a blank image.")
        appendLine("  · Cookies cover the current page only; Android has no enumerate-all API.")
        appendLine("  · No history: cookies and cache are wiped on every app start and every service start;")
        appendLine("    passwords and form data are never saved.")
        appendLine("  · back needs a committed history entry: wait for the navigation to land first.")
        appendLine("    If it cannot go back the error carries the history entry count.")
        appendLine("  · A missing element always returns ok:false; ok:true means it really happened.")
        appendLine("  · A click with no wait reports ready:false once it started a new navigation.")
        appendLine()
        appendLine("提示：服务未开启时连接被拒——请在 TermLou 设置 → 高级 → 浏览器 里开启。")
        appendLine("Note: connection refused means the service is off — enable it in TermLou 设置 → 高级 → 浏览器.")
    }
}

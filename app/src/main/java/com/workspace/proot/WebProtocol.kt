package com.workspace.proot

import org.json.JSONObject

/**
 * 浏览器的 HTTP/JSON 指令协议（5.9.0，纯逻辑、不依赖安卓，可单测）。
 *
 * 传输：**仅回环地址**的固定端口 + 每次安装固定的令牌（`X-Token` 头）。
 *
 * ⚠ **`GET /help` 也要令牌**（5.9.9 改）。此前它免令牌，而说明书里又把真令牌
 * 印在 `X-Token: xxx` 那一行 —— 安卓上任何 app 访问 127.0.0.1 都不需要任何权限，
 * 于是任何装在手机上的应用一条 `curl http://127.0.0.1:<端口>/help` 就拿到完整凭据。
 * 自发现的正路是 `web.env`（在 app 私有目录里，别的 app 读不到）。
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

    /**
     * 产物目录在 Linux 侧的路径。
     *
     * 5.9.38 起这个目录里**只有 `web.env`**（端口 + 令牌，仅服务运行时存在）——
     * 不再导出 cookie、不再有截图。设置里的「清除缓存」清的就是它。
     */
    const val WEB_DIR = "$WORKSPACE_MOUNT/web"

    /**
     * 视口尺寸（dp）：页面按手机竖屏排版，跟正常手机一致。
     *
     * 5.9.34 我把它删掉、改成"视口 = 窗口"（160dp），理由是"缩放是第 2 屏的原因"。
     * **那个理由是错的** —— 5.9.34/5.9.35 都没有缩放，第 2 屏照样失败。
     * 缩放从来不是原因，而为它付出的代价是画质（360px 视口里字只有 19px，发虚）。
     *
     * 5.9.36 请回来：用户要的是**清楚**。缩放只发生在**显示**那一层
     * （见 [WebFloatWindow.scaleFactors]），agent 的版式与坐标一个像素都不变。
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

    /**
     * 取一个整数参数，**传了但不是个数就返回 null**（5.9.37，`extract` 的 `limit`/`text_chars` 用）。
     *
     * 不能用 [bodyInt]：它是 `optInt`，传了 `"abc"` 会**默默回落到默认值**，
     * agent 于是以为自己要的参数生效了，其实被丢了 —— 而"参数被悄悄丢掉"
     * 正是这个项目反复吃亏的那类错（静默兜底）。
     *
     * 两种返回：
     * - 键不存在 / 是 JSON null → 回落到 `fallback`（没传就是默认值，正常）
     * - 键在但不是个数（字符串、对象、布尔、小数）→ `null`（调用方**报错**，不装作没传）
     */
    fun bodyIntOrNull(request: Request, key: String, fallback: Int): Int? =
        runCatching {
            val o = JSONObject(request.body)
            if (!o.has(key) || o.isNull(key)) fallback
            else if (o.get(key) is Int) o.getInt(key) else null
        }.getOrNull()

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

    /**
     * 失败，但**带上已经拿到的数据**。
     *
     * ## 为什么需要它
     *
     * 有些操作是"做到了大部分、但有一步没成"（历史上是整页截图的第 3 屏）。
     * 两种做法都有代价：
     *
     * - 全扔 → agent 什么都拿不到，而那些已经拿到的明明是好的；
     * - 报 `ok:true` → **5.9.9 的教训**：半张图报成功比修不好更糟，
     *   agent 会拿残缺的东西做判断。
     *
     * 所以：仍然 `ok:false`，但把已经拿到的那部分**一起返回**并说明缺了什么。
     *
     * 5.9.38 起 `click` 也用它：点了链接、页面一点没动时，
     * `ok:false` + 把"点了什么"原样交回去，agent 就不用重问一次。
     *
     * @param message 说清缺了什么、为什么
     * @param okBody 拼好的成功体（会被改成 ok:false + error，并标上 partial）
     */
    fun errJsonWith(message: String, okBody: String): String {
        val o = runCatching { JSONObject(okBody) }.getOrElse { JSONObject() }
        o.put("ok", false)
        o.put("error", message)
        // 明确标出来：这些是**部分**结果，不是完整的
        o.put("partial", true)
        return o.toString()
    }

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
        appendLine("  {\"op\":\"html\"}                        取回当前页面 HTML（很大，慎用）")
        appendLine("  {\"op\":\"extract\"}                    **看清页面上有什么** —— 见下面「搜索」一节")
        appendLine("                                          可带 \"limit\":0~${WebExtract.MAX_LIMIT}（链接条数，默认 ${WebExtract.DEFAULT_LIMIT}）")
        appendLine("                                          可带 \"text_chars\":0~${WebExtract.MAX_TEXT_CHARS}（正文字数，默认 ${WebExtract.DEFAULT_TEXT_CHARS}）")
        appendLine("  {\"op\":\"text\",\"selector\":\"…\"}         取可见文字（不给 selector = 整页正文）")
        appendLine("  {\"op\":\"click\",\"selector\":\"…\"}       真实点击（可带 wait 等新页）")
        appendLine("                                          返回 clicked{tag,href,target} + navigated")
        appendLine("                                          ⚠ 点的是链接而页面一步没走 → ok:false")
        appendLine("  {\"op\":\"type\",\"selector\":\"…\",\"text\":\"…\"}   填输入框（clear=false 追加）")
        appendLine("                                          加 \"enter\":true 就**填完按回车**（可带 wait 等跳转）")
        appendLine("                                          回车后返回 navigated/ready，别当成没跳")
        appendLine("  {\"op\":\"select\",\"selector\":\"…\",\"value\":\"…\"}   选 <select> 的一项")
        appendLine("  {\"op\":\"reload\"}                    重载当前页")
        appendLine("  {\"op\":\"clear\"} / {\"op\":\"close\"}      清 cookie/缓存/localStorage 并结束会话")
        appendLine("  {\"op\":\"ping\"}                        探活")
        appendLine("  {\"op\":\"diag\"}                        诊断：窗口几何 / 缩放 / 页面可见性 / 最近错误码")
        appendLine()
        appendLine("extract —— 一次把页面看全（打开网页后的第一件事）:")
        appendLine("  返回 url/title/description/text/inputs/buttons/links/links_total/links_truncated")
        appendLine("  · inputs  = 所有能填的地方（<input>/<textarea>/<select>）：带 id/name/提示语")
        appendLine("    → 搜索框就在这里，**永不截断**，滚多久都不会漏")
        appendLine("  · buttons = 所有能点的按钮（<button>）：带按钮上的字与 id")
        appendLine("  · links   = 所有有可见文字的链接：带文字 + 完整网址（<canvas> 与纯图标链接取不到）")
        appendLine("  · 它不猜，**照抄网站自己写明的标签**：网站给了 id=\"kw\"，它就报 id=\"kw\"。")
        appendLine("    所以别去猜搜索框叫什么，extract 会告诉你。")
        appendLine("  · 链接有上限（默认 ${WebExtract.DEFAULT_LIMIT}、封顶 ${WebExtract.MAX_LIMIT}）。截了会明说：")
        appendLine("    links_truncated:true + links_total:N + note 里写清该用多大的 limit 重取。")
        appendLine("    超过封顶会回 limit_capped:true 与 limit_requested（你传的值）。")
        appendLine("  · 只想找搜索框时用 {\"op\":\"extract\",\"limit\":0,\"text_chars\":0} —— 返回极小。")
        appendLine("  · 扫不到东西时 note 会直说（可能还在加载，或页面把自己画进 <canvas>）。")
        appendLine()
        appendLine("搜索 —— 像真人那样用这个浏览器（推荐节奏）:")
        appendLine("  1 {\"op\":\"open\",\"url\":\"https://www.baidu.com\"}")
        appendLine("  2 {\"op\":\"extract\",\"limit\":0,\"text_chars\":0}      找出搜索框的 id（这一步很便宜）")
        appendLine("  3 {\"op\":\"type\",\"selector\":\"#kw\",\"text\":\"关键词\",\"enter\":true,\"wait\":8000}")
        appendLine("  4 {\"op\":\"extract\"}                             看搜索结果：标题 + 网址都在 links 里")
        appendLine("  5 {\"op\":\"click\",\"selector\":\"…\",\"wait\":5000}    点开想要的那条")
        appendLine("  6 {\"op\":\"extract\",\"text_chars\":20000}           读正文")
        appendLine("  （真人也是：看页面 → 找框 → 打字 → 回车 → 看结果 → 点进去。每一步都不靠猜。）")
        appendLine()
        appendLine("selector 选择器:")
        appendLine("  \"input[name=q]\"  CSS 选择器")
        appendLine("  \"text=登录\"       按可见文字找元素（text==登录 为严格相等）")
        appendLine()
        appendLine("curl 示例 example:")
        appendLine("  source $WEB_DIR/web.env")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"open\",\"url\":\"\$URL\",\"wait\":8000}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"extract\",\"limit\":0,\"text_chars\":0}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"eval\",\"js\":\"document.title\"}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  curl -s -H \"X-Token: \$TOKEN\" -d '{\"op\":\"text\",\"selector\":\"h1\"}' \\")
        appendLine("       http://127.0.0.1:\$PORT/op")
        appendLine("  （选择器是 CSS 或 text=可见文字；换成你自己页面上真实存在的文字）")
        appendLine()
        appendLine("须知 notes:")
        appendLine("  · 一次只开一个页面；同一页面反复 open 即复用。")
        appendLine("  · **网页按 ${WebProtocol.VIEWPORT_W_DP}×${WebProtocol.VIEWPORT_H_DP}dp 排版**（跟正常手机一致），")
        appendLine("    再缩进右上角那个小窗（屏宽 ÷ 3）显示。所以版式、坐标、媒体查询都和真手机一样，")
        appendLine("    不用迁就窄排版；小窗里的字是缩小后的样子。")
        appendLine("  · 用户能在小窗里看到你的一举一动（打开、填词、回车、点链接、加载），")
        appendLine("    所以别做人类不会做的事（疯狂刷新、无意义的滚动）。")
        appendLine("  · open 的 wait 到点没加载完不报错，只回 ready:false 与 progress，可再 wait 重试。")
        appendLine("  · type 的 enter:true 是**填完立刻按回车**，然后按 wait 等新页落地；")
        appendLine("    返回里带 url / navigated / ready，可以确认到底跳到哪了。")
        appendLine("  · click 报回来的是\"点了什么\"：clicked{tag,href,target}。")
        appendLine("    ⚠ **点的是链接、而页面一步都没走（navigated:false）时是 ok:false** ——")
        appendLine("    那意味着这次点击没起作用（链接被页面脚本拦了之类），换个目标或直接 open href。")
        appendLine("    点的是页内锚点（#xxx）不算失败（本来就不换页）。")
        appendLine("    ⚠ selector=\"text=…\" 点时找的是**人看得见的名字**：输入框里现有的字不算，")
        appendLine("    所以 text=<搜索词> 不会点到顶部搜索框（点它什么都不会发生）。")
        appendLine("    文字确实在页面上、却落在不能点的东西上时，报的是 not clickable 而不是 not found。")
        appendLine("  · **没有后退指令**（5.9.39 删掉）：安卓的后退在这个\"从没被点过\"的窗口里")
        appendLine("    会一直说没得退（平台侧的坑）。要回上一页就 `open` 上一条网址 ——")
        appendLine("    那个网址你手上一直有（extract / type 回车 / open 的返回里都带 url）。")
        appendLine("  · clear 与 close 完全等价：都是清数据 + 结束当前页面会话。")
        appendLine("  · 只监听 127.0.0.1（同一 WiFi 下别的设备连不上）。")
        appendLine("  · 元素找不到一律 ok:false，绝不会报成功 —— ok:true 表示事情真的发生了。")
        appendLine("  · 选择器报错怎么读：not found = 元素不在（换选择器或先 wait）；")
        appendLine("    bad selector (...) = 选择器本身写错了（照抄报错里的原因）；")
        appendLine("    not a text field / not a <select> = 元素找对了但类型不对。")
        appendLine("    元素在 iframe 里的话这里查不到 —— 换个直接含它的页面。")
        appendLine("  · 四个字段各管一件事，别混：")
        appendLine("    ok:false    = 这次操作本身失败（选择器错、没有历史、点了没反应、落地是错误页）。")
        appendLine("    ready       = 页面事件走完。")
        appendLine("    navigated   = 页面真的开始换页了吗。")
        appendLine("    usable      = 落地页真的能用来干活。")
        appendLine("  · 加载失败时 WebView 会加载它自己的错误页，而那个错误页同样会触发页面事件，")
        appendLine("    所以只看 ready 会把失败当成功。以 usable 为准。")
        appendLine("  · open 不带 wait 时 usable 一定是 false（还没渲染完），那不代表打开失败 ——")
        appendLine("    打开失败会是 ok:false。要判断就 open → wait，wait 会带回 usable。")
        appendLine("  · usable:false 看 warning 分两种：真的不能用（说了是错误页），")
        appendLine("    和没探到（warning 写 could not determine）。宁可漏判不误判。")
        appendLine("  · 碰页面的指令之间串行（同一时刻只有一条在操作页面），但 ping/diag 不会被堵住。")
        appendLine("  · 客户端中途断开（命令被超时杀掉）时，服务端立刻放弃等待，不会留下卡住的会话。")
        appendLine("  · 推荐节奏：open → wait → extract → 再动手。单条 wait 上限 30 秒。")
        appendLine("  · 不带 wait 的 click 若已点出新导航，一律报 ready:false（不会拿上一页冒充）。")
        appendLine("  · **本浏览器不保留 cookie、不留缓存**（用户要求）：")
        appendLine("    没有导出 cookie 的指令；cookie 与 localStorage 在 app 启动、服务启动、")
        appendLine("    会话结束三处都会清掉；缓存由加载时绕开 + 每次开关清空两道保证。")
        appendLine("    所以换个站点就得重新登录 —— 这是设计，不是故障。")
        appendLine("  · Open a page, then extract it - that is how you see what is on it without guessing.")
        appendLine("    inputs and buttons are never truncated, so the search box is always there.")
        appendLine("    Truncated links say so (links_truncated / links_total / note); they are not hidden.")
        appendLine("  · One page at a time; if open's wait runs out it does not error, it returns ready:false.")
        appendLine("  · ok:false = the operation itself failed; ready = page events finished;")
        appendLine("    navigated = the page really started changing;")
        appendLine("    usable = the landed page can actually be worked with.")
        appendLine("  · A click on a link that does not navigate at all comes back ok:false - nothing happened.")
        appendLine("    With text= selectors, click only matches names a person can see: text already in a")
        appendLine("    text field does not count, so a search term will not hit the search box itself.")
        appendLine("  · The page lays out at ${WebProtocol.VIEWPORT_W_DP}×${WebProtocol.VIEWPORT_H_DP}dp (a normal phone width),")
        appendLine("    then is scaled down into the small floating window - so the layout is a normal one.")
        appendLine("  · The user watches the same window: opening, typing, Enter, clicking and loading are")
        appendLine("    all visible. Do what a person would do, not what is cheapest to automate.")
        appendLine("  · This browser keeps no cookies and no cache at all: cookies and localStorage are wiped")
        appendLine("    on app start, on service start and when the session ends, and caching is off.")
        appendLine("    Logging in again for a different site is by design, not a failure.")
        appendLine("  · There is no back command (removed in 5.9.39): Android's back keeps saying")
        appendLine("    there is nothing to go back to in a window that was never touched, while the")
        appendLine("    history is actually fine. To return to a previous page, just open its url -")
        appendLine("    you already have it from the extract / type / open responses.")
        appendLine("  · A missing element always returns ok:false; ok:true means it really happened.")
        appendLine("  · A click with no wait reports ready:false once it started a new navigation.")
        appendLine()
        appendLine("提示：服务未开启时连接被拒——请在 TermLou 设置 → 高级 → 浏览器 里开启。")
        appendLine("Note: connection refused means the service is off — enable it in TermLou 设置 → 高级 → 浏览器.")
    }
}

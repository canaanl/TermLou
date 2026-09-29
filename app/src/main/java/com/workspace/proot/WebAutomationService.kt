package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * 无头浏览器前台服务（5.9.0）：**手动开关式**（照 [LanShareService] 的模式），开着时
 * 在**回环地址**固定端口提供 HTTP/JSON 指令接口，供 Linux 侧 agent 用系统自带 WebView 驱动网页。
 *
 * 设计要点：
 *  - **只听 127.0.0.1**：绑 `InetAddress` 回环地址，同一 WiFi 下的别的设备连不上。
 *    令牌虽然固定，但 `GET /help` 是免令牌的，不绑回环等于把令牌公开给整个局域网；
 *  - **无头**：WebView 挂在本服务自己的 overlay 窗口上、整体挪到屏幕左侧外（用户看不见），
 *    渲染照常进行，所以能取 HTML、能截图；
 *  - **懒加载**：服务常驻但 WebView 只在第一条 `open` 时创建，会话结束即 destroy——
 *    闲置时只有一个空壳进程；
 *  - **无痕**：进程启动、服务启动、会话结束/关闭，三处都会清 cookie / 缓存 / localStorage /
 *    表单数据，且不保存密码；app 里没有第二个 WebView，故"清全部 WebView 数据"只影响本功能；
 *  - **串行**：所有指令走**单线程队列**，两条指令不会互相踩同一个 WebView；
 *    连接数有上限，满了立刻回 503 而不是把线程耗光；
 *  - **产物可见**：截图与 cookie 落在 Linux 可见的 `~/web/`（= filesDir/workspace/web），
 *    另有 `web.env` 写着端口与令牌（**仅服务运行时存在**），供 agent 自发现；
 *  - **令牌**：每次安装随机生成一次并固定下来（可复制进 agent skill），只存在 TermLou 私有
 *    目录，别的 app 读不到；端口固定，被占用时启动失败并如实报错。
 *
 * 端口/协议/选择器/产物路径的纯逻辑在 [WebProtocol]、[WebSelector]、[WebArtifacts]（均可单测）。
 */
class WebAutomationService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy { getSystemService(WindowManager::class.java) }

    /** 连接处理线程池：核心 1 条（页面操作严格串行），上限 [MAX_CLIENTS]，满了回 503。 */
    private val workers: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            1, MAX_CLIENTS, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_CLIENTS),
            { r -> Thread(r, "term-lou-web-op").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    @Volatile private var running = false
    @Volatile private var stopping = false
    private var webView: WebView? = null

    /** 页面加载进度：100 = 完成。[pageReady] 只在主线程写，读侧靠 volatile 同步。 */
    @Volatile private var progress = 0
    @Volatile private var pageReady = false

    private val pageClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            progress = 0
            pageReady = false
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            progress = 100
            pageReady = true
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            // 站内跳转（点了链接）也算一轮加载完
            progress = 100
            pageReady = true
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.web_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            teardown()
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.web_starting)))
        } catch (e: Exception) {
            Log.e(TAG, "startForeground failed", e)
            statusText = getString(R.string.web_failed)
            isRunning = false
            stopSelf()
            return START_NOT_STICKY
        }
        startServer()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- 启停 ----------

    private fun startServer() {
        if (running) return
        stopping = false
        clearWebData()                    // 服务启动即无痕
        val port = WebProtocol.DEFAULT_PORT
        val socket = try {
            // 只听回环：别的设备/别的 app 不该能通过 WiFi 连上这台手机
            ServerSocket(port, MAX_CLIENTS, InetAddress.getByName(LOOPBACK))
        } catch (e: Exception) {
            Log.e(TAG, "bind $port failed", e)
            statusText = getString(R.string.web_port_busy_fmt, port)
            isRunning = false
            stopSelf()
            return
        }
        serverSocket = socket
        acceptThread = Thread { acceptLoop(socket) }.apply {
            isDaemon = true
            start()
        }
        running = true
        isRunning = true
        statusText = getString(R.string.web_running_fmt, port)
        WebArtifacts.writeEnv(this, port, currentToken(this))
        // 开着就是"待使用"：status 栏开始闪烁提示，直到 agent 发出第一条真指令
        WebArtifacts.setNoticePending(this, true)
        refreshNotification()
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!stopping && !socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (e: Exception) {
                if (!stopping) Log.w(TAG, "accept failed", e)
                return
            }
            try {
                workers.execute { serve(client) }
            } catch (e: RejectedExecutionException) {
                // 线程池满了：立刻如实回错误并断开，不能让卡住的连接把服务拖垮
                Log.w(TAG, "too many concurrent requests", e)
                writeQuietly(
                    client,
                    WebProtocol.httpResponse(503, WebProtocol.errJson("too many concurrent requests"), JSON_TYPE)
                )
                runCatching { client.close() }
            }
        }
    }

    private fun teardown() {
        stopping = true
        running = false
        isRunning = false
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        // 先把正在跑的指令排空，再拆页面：否则指令会踩到已销毁的 WebView
        runCatching { workers.shutdown() }
        runCatching { workers.awaitTermination(TEARDOWN_JOIN_MS, TimeUnit.MILLISECONDS) }
        runCatching { workers.shutdownNow() }
        destroySession()
        WebArtifacts.clearEnv(this)
        // 关掉就是"用完了"：不再闪烁提示
        WebArtifacts.setNoticePending(this, false)
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    // ---------- HTTP 分发 ----------

    private fun serve(client: Socket) {
        runCatching {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val badRequest = WebProtocol.httpResponse(
                400, WebProtocol.errJson("bad request"), JSON_TYPE
            )
            val raw = readRequest(client.getInputStream())
                ?: return@runCatching writeQuietly(client, badRequest)
            val request = WebProtocol.parseRequest(raw)
                ?: return@runCatching writeQuietly(client, badRequest)
            writeQuietly(client, route(request))
        }.onFailure { Log.w(TAG, "serve failed", it) }
        runCatching { client.close() }
    }

    private fun writeQuietly(client: Socket, response: ByteArray) {
        runCatching {
            client.getOutputStream().write(response)
            client.getOutputStream().flush()
        }
    }

    /** 读一个请求：头部到 `\r\n\r\n`，再按 Content-Length 读体。 */
    private fun readRequest(input: InputStream): ByteArray? {
        val buf = ByteArrayOutputStream()
        var matched = 0
        val one = ByteArray(1)
        while (matched < 4) {
            val n = input.read(one)
            if (n < 0) return null
            buf.write(one, 0, 1)
            matched = if ((matched == 0 || matched == 2) && one[0] == 13.toByte()) matched + 1
            else if ((matched == 1 || matched == 3) && one[0] == 10.toByte()) matched + 1
            else 0
            if (buf.size() > MAX_HEAD) return null
        }
        val head = buf.toByteArray()
        val contentLength = CONTENT_LENGTH.find(String(head, Charsets.ISO_8859_1))
            ?.groupValues?.get(1)?.toIntOrNull() ?: 0
        if (contentLength > MAX_BODY) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val n = input.read(body, read, contentLength - read)
            if (n < 0) break
            read += n
        }
        return head + body
    }

    private fun route(request: WebProtocol.Request): ByteArray {
        // /help 免令牌：纯说明书，供 agent 自发现
        if (request.method == "GET" && request.path == "/help") {
            return WebProtocol.httpResponse(
                200,
                WebProtocol.help(WebProtocol.DEFAULT_PORT, currentToken(this)),
                "text/plain; charset=utf-8"
            )
        }
        if (request.token != currentToken(this)) {
            return WebProtocol.httpResponse(401, WebProtocol.errJson("bad token"), JSON_TYPE)
        }
        if (request.method != "POST" || request.path != "/op") {
            return WebProtocol.httpResponse(404, WebProtocol.errJson("no such endpoint"), JSON_TYPE)
        }
        val op = WebProtocol.bodyOp(request) ?: return json(WebProtocol.errJson("missing op"))
        // 任何一条真指令都算"用过了"：停止 status 闪烁提示（探活不算）
        if (op != "ping") WebArtifacts.markUsed(this)
        return try {
            json(handle(op, request))
        } catch (e: Exception) {
            Log.e(TAG, "op $op failed", e)
            json(WebProtocol.errJson(e.message ?: e.javaClass.simpleName))
        }
    }

    private fun json(body: String) = WebProtocol.httpResponse(200, body, JSON_TYPE)

    private fun handle(op: String, request: WebProtocol.Request): String = when (op) {
        "ping" -> WebProtocol.okJson("msg" to "pong", "port" to WebProtocol.DEFAULT_PORT)
        "open" -> opOpen(request)
        "wait" -> opWait(request)
        "eval" -> opEval(request)
        "html" -> opHtml()
        "text" -> opText(request)
        "click" -> opClick(request)
        "type" -> opType(request)
        "select" -> opSelect(request)
        "shot" -> opShot()
        "back" -> opBack()
        "reload" -> opReload(request)
        "cookies" -> opCookies()
        "clear", "close" -> opClose()
        else -> WebProtocol.errJson("unknown op: $op")
    }

    // ---------- 无头 WebView ----------

    /** WebView 懒加载：只有真的要用网页时才创建。 */
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        if (!Settings.canDrawOverlays(this)) {
            throw IllegalStateException("overlay permission missing — grant it in TermLou settings")
        }
        val density = resources.displayMetrics.density
        val w = (WebProtocol.VIEWPORT_W_DP * density).toInt().coerceAtLeast(1)
        val h = (WebProtocol.VIEWPORT_H_DP * density).toInt().coerceAtLeast(1)
        val wv = WebView(this)
        wv.setBackgroundColor(Color.WHITE)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // 不持久化任何表单/密码（属性已废弃，但它是唯一能关掉密码保存的开关）
            @Suppress("DEPRECATION")
            saveFormData = false
        }
        wv.webViewClient = pageClient
        wv.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progress = newProgress
                if (newProgress >= 100) pageReady = true
            }
        }
        val params = WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            // 挪到屏幕左侧外：渲染照常（无头），但用户看不见
            x = -(w + 100)
            y = 0
            gravity = Gravity.TOP or Gravity.START
        }
        wm.addView(wv, params)
        webView = wv
        progress = 0
        pageReady = false
        return wv
    }

    private fun destroySession() {
        val wv = webView
        webView = null
        pageReady = false
        progress = 0
        if (wv == null) return
        // removeView/destroy 必须在主线程
        onMain(TEARDOWN_JOIN_MS) {
            runCatching {
                wv.stopLoading()
                wm.removeView(wv)
                wv.destroy()
            }.onFailure { Log.w(TAG, "destroy webview failed", it) }
            null
        }
    }

    /** 页面操作必须回主线程（WebView 只允许在主线程调用）。 */
    private fun <T> onMain(timeoutMs: Long = 30_000, block: () -> T): T? {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val latch = CountDownLatch(1)
        var value: T? = null
        var error: Throwable? = null
        mainHandler.post {
            try {
                value = block()
            } catch (t: Throwable) {
                error = t
            } finally {
                latch.countDown()
            }
        }
        if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return null
        error?.let { throw it }
        return value
    }

    // ---------- 指令 ----------

    /**
     * `open`：建/复用页面并开始加载。
     * `wait`（毫秒，默认 0=不等）在这里等页面真正加载完再返回——
     * 否则 agent 紧接着 `html` 拿到的是加载中的空壳页，这是无头浏览器最常见的坑。
     */
    private fun opOpen(request: WebProtocol.Request): String {
        val url = WebProtocol.bodyString(request, "url")
        if (url.isBlank()) return WebProtocol.errJson("missing url")
        val waitMs = WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS)
        val wv = onMain { ensureWebView() } ?: return WebProtocol.errJson("webview timeout")
        onMain { wv.loadUrl(url) } ?: return WebProtocol.errJson("load timeout")
        val ready = waitForPage(waitMs)
        return WebProtocol.okJson(
            "msg" to "opened",
            "url" to url,
            "ready" to ready,
            "progress" to progress
        )
    }

    /**
     * 等页面加载完成。[waitMs]=0 时只做一次非阻塞检查（页面刚好已就绪才算）。
     * 超时**不报错**，把进度如实回给 agent 由它决定是否重试。
     */
    private fun waitForPage(waitMs: Int): Boolean {
        if (pageReady && progress >= 100) return true
        if (waitMs <= 0) return false
        val deadline = SystemClock.elapsedRealtime() + waitMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (pageReady && progress >= 100) return true
            Thread.sleep(50)
        }
        return pageReady && progress >= 100
    }

    /** `wait`：单独等页面就绪（agent 想在别处加载完再取内容时用）。 */
    private fun opWait(request: WebProtocol.Request): String {
        if (webView == null) return WebProtocol.errJson("no page open")
        val waitMs = WebProtocol.bodyInt(request, "ms", 10_000).coerceIn(0, MAX_WAIT_MS)
        val ready = waitForPage(waitMs)
        return WebProtocol.okJson(
            "msg" to if (ready) "ready" else "timeout",
            "ready" to ready,
            "progress" to progress,
            "url" to (onMain { webView?.url } ?: "")
        )
    }

    /**
     * `eval`：在页面里跑任意 JS，返回值原样带回（字符串就是字符串，不是 JSON 字面量）。
     *
     * **必须解码**：evaluateJavascript 的回调给的是 JSON 编码值（字符串带引号、`\n` 转义、
     * JS 的 null 变成字符串 `"null"`）。不解码的话 agent 拿到的是带引号转义的一坨，解析必错。
     */
    private fun opEval(request: WebProtocol.Request): String {
        val js = WebProtocol.bodyString(request, "js")
        if (js.isBlank()) return WebProtocol.errJson("missing js")
        val wv = webView ?: return WebProtocol.errJson("no page open")
        return when (val outcome = evalInPage(wv, js)) {
            is WebProtocol.EvalOutcome.Timeout -> WebProtocol.errJson("evaluate timeout")
            is WebProtocol.EvalOutcome.Value ->
                WebProtocol.okJson("value" to outcome.value, "url" to (onMain { wv.url } ?: ""))
        }
    }

    private fun opHtml(): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        return when (val outcome = evalInPage(wv, "(document.documentElement||{}).outerHTML||''")) {
            is WebProtocol.EvalOutcome.Timeout -> WebProtocol.errJson("evaluate timeout")
            is WebProtocol.EvalOutcome.Value ->
                WebProtocol.okJson("html" to outcome.value, "url" to (onMain { wv.url } ?: ""))
        }
    }

    /** `text`：取可见文字；不给选择器就是整页正文。 */
    private fun opText(request: WebProtocol.Request): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val raw = WebProtocol.bodyString(request, "selector")
        val js = if (raw.isBlank()) {
            "(document.body ? document.body.innerText : '')"
        } else {
            val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
            "(function(){var e=" + WebSelector.pickJs(kind) + ";" +
                "return e?(e.innerText?e.innerText:(e.value!=null?e.value:'')):null})()"
        }
        return when (val outcome = evalInPage(wv, js)) {
            is WebProtocol.EvalOutcome.Timeout -> WebProtocol.errJson("evaluate timeout")
            is WebProtocol.EvalOutcome.Value -> outcome.value?.let { text ->
                WebProtocol.okJson("text" to text, "url" to (onMain { wv.url } ?: ""))
            } ?: WebProtocol.errJson("not found: $raw")
        }
    }

    /** `click`：真实点击（不是改状态），元素会先滚进可视区。 */
    private fun opClick(request: WebProtocol.Request): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val raw = WebProtocol.bodyString(request, "selector")
        val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
        val js = "(function(){var e=" + WebSelector.pickJs(kind) + ";" +
            "if(!e)return null;" +
            "try{e.scrollIntoView({block:'center',inline:'center'})}catch(_){}" +
            "e.click();return (e.innerText?e.innerText:(e.value!=null?e.value:'ok'))})()"
        return when (val outcome = evalInPage(wv, js)) {
            is WebProtocol.EvalOutcome.Timeout -> WebProtocol.errJson("evaluate timeout")
            is WebProtocol.EvalOutcome.Value -> outcome.value?.let { label ->
                val ready = waitForPage(WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS))
                WebProtocol.okJson(
                    "msg" to "clicked",
                    "label" to label,
                    "ready" to ready,
                    "url" to (onMain { wv.url } ?: "")
                )
            } ?: WebProtocol.errJson("not found: $raw")
        }
    }

    /**
     * `type`：往输入框/文本域填字。
     * 走原生 setter + input/change 事件，而不是逐键模拟——React/Vue 这类框架
     * 监听的是 input 事件上 setter 的痕迹，直接改 `value` 它们收不到。
     */
    private fun opType(request: WebProtocol.Request): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val value = WebProtocol.bodyString(request, "text")
        if (value.isEmpty()) return WebProtocol.errJson("missing text")
        val clear = WebProtocol.bodyOptBoolean(request, "clear", true)
        val raw = WebProtocol.bodyString(request, "selector")
        val fill = if (raw.isBlank()) {
            // 不给选择器：填到当前焦点元素上
            "(function(){var e=document.activeElement;if(!e)return null;" +
                "return WEB_FILL(e," + WebSelector.jsString(value) + "," + clear + ")?1:null})()"
        } else {
            val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
            "(function(){var e=" + WebSelector.pickJs(kind) + ";" +
                "if(!e)return null;return WEB_FILL(e," + WebSelector.jsString(value) + "," + clear + ")?1:null})()"
        }
        return when (val outcome = evalInPage(wv, FILL_HELPER + fill)) {
            is WebProtocol.EvalOutcome.Timeout -> WebProtocol.errJson("evaluate timeout")
            is WebProtocol.EvalOutcome.Value -> outcome.value?.let {
                WebProtocol.okJson("msg" to "typed", "bytes" to value.length)
            } ?: WebProtocol.errJson("not a fillable element: $raw")
        }
    }

    /** `select`：按 value 或可见文字选中 `<select>` 的一项，并派发 change。 */
    private fun opSelect(request: WebProtocol.Request): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val value = WebProtocol.bodyString(request, "value")
        if (value.isBlank()) return WebProtocol.errJson("missing value")
        val raw = WebProtocol.bodyString(request, "selector")
        if (raw.isBlank()) return WebProtocol.errJson("missing selector")
        val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
        val js = "(function(){var e=" + WebSelector.pickJs(kind) + ";" +
            "if(!e)return null;if(String(e.tagName).toUpperCase()!=='SELECT')return '-1';" +
            "var want=" + WebSelector.jsString(value) + ";var i,o,hit='';" +
            "for(i=0;i<e.options.length;i++){o=e.options[i];" +
            "if(o.value===want||String(o.text).trim()===want){hit=o.value;break}}" +
            "if(hit==='')return '-1';e.value=hit;" +
            "try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(_){}" +
            "return hit})()"
        return when (val outcome = evalInPage(wv, js)) {
            is WebProtocol.EvalOutcome.Timeout -> WebProtocol.errJson("evaluate timeout")
            is WebProtocol.EvalOutcome.Value -> when (outcome.value) {
                null -> WebProtocol.errJson("not found: $raw")
                "-1" -> WebProtocol.errJson("not a select, or no option matches: $value")
                else -> WebProtocol.okJson("msg" to "selected", "value" to outcome.value)
            }
        }
    }

    /**
     * 截图。**两条路，按可靠度依次尝试**：
     *  1. [WebView.capturePicture]：内核走软件绘制，硬件加速的 WebView 也能画全；
     *  2. `View.draw(Canvas)`：capture 返回空图时的兜底（有些机型这条路才有像素）。
     *
     * 两路都空就如实报错，不写一张白图糊弄 agent。
     */
    private fun opShot(): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val bmp = onMain { capturePage(wv) }
            ?: return WebProtocol.errJson("shot failed (view not laid out)")
        if (!hasContent(bmp)) {
            bmp.recycle()
            return WebProtocol.errJson("shot blank (page not rendered yet — retry after wait)")
        }
        val file = WebArtifacts.saveShot(this, bmp)
        bmp.recycle()
        return if (file == null) WebProtocol.errJson("shot write failed")
        else WebProtocol.okJson("file" to WebArtifacts.linuxPath(this, file), "bytes" to file.length())
    }

    private fun capturePage(wv: WebView): Bitmap? {
        val density = resources.displayMetrics.density
        val w = (WebProtocol.VIEWPORT_W_DP * density).toInt().coerceAtLeast(1)
        val h = (WebProtocol.VIEWPORT_H_DP * density).toInt().coerceAtLeast(1)
        if (wv.width <= 0 || wv.height <= 0) return null
        val width = w.coerceAtMost(wv.width)
        val height = h.coerceAtMost(wv.height)
        val fromPicture = runCatching {
            @Suppress("DEPRECATION")
            val pic = wv.capturePicture()
            if (pic == null || pic.width <= 0 || pic.height <= 0) return@runCatching null
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                canvas.scale(width.toFloat() / pic.width, height.toFloat() / pic.height)
                pic.draw(canvas)
            }
        }.getOrNull()
        if (hasContent(fromPicture)) return fromPicture
        fromPicture?.recycle()
        return runCatching {
            Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { bmp ->
                wv.draw(Canvas(bmp))
            }
        }.getOrNull()
    }

    /** 抽样判断是不是"整屏同色"的空图（截图最常见的假成功），规则见 [WebShotSampler]。 */
    private fun hasContent(bmp: Bitmap?): Boolean {
        if (bmp == null) return false
        return !WebShotSampler.looksBlank(bmp.width, bmp.height) { x, y -> bmp.getPixel(x, y) }
    }

    /** `back`：后退。没有历史就如实报错，不假装成功。 */
    private fun opBack(): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val canGoBack = onMain { wv.canGoBack() } ?: false
        if (!canGoBack) return WebProtocol.errJson("no history to go back")
        onMain { wv.goBack() }
        return WebProtocol.okJson("msg" to "back", "url" to (onMain { wv.url } ?: ""))
    }

    /** `reload`：重载当前页（可带 `wait`）。 */
    private fun opReload(request: WebProtocol.Request): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        onMain { wv.reload() } ?: return WebProtocol.errJson("reload timeout")
        val ready = waitForPage(WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS))
        return WebProtocol.okJson(
            "msg" to "reloaded",
            "ready" to ready,
            "url" to (onMain { wv.url } ?: "")
        )
    }

    /**
     * 导出 cookie 到 `~/web/cookies.txt|json` 供人查看。
     * Android 不提供"枚举全部 cookie"的 API（[CookieManager] 只能按 URL 取），
     * 因此这里导出**当前页面**可见的 Cookie 头——这也是 agent 真正关心的部分。
     * 响应里的 `scope` 字段明说这件事，免得 agent 把"没导出"当成"没有 cookie"。
     */
    private fun opCookies(): String {
        val wv = webView
        val url = onMain { wv?.url } ?: ""
        val header = onMain {
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrDefault("")
        } ?: ""
        val pairs = header.split(";").mapNotNull { part ->
            val kv = part.trim()
            val i = kv.indexOf('=')
            if (i <= 0) null else kv.substring(0, i).trim() to kv.substring(i + 1).trim()
        }
        WebArtifacts.writeCookies(this, url, pairs)
        return WebProtocol.okJson(
            "file" to WebArtifacts.linuxPath(this, File(WebArtifacts.webRoot(this), "cookies.txt")),
            "url" to url,
            "count" to pairs.size,
            "scope" to "current page only (Android exposes no enumerate-all API)"
        )
    }

    private fun opClose(): String {
        destroySession()
        clearWebData()
        return WebProtocol.okJson("msg" to "closed")
    }

    /** 无痕：cookie / 缓存 / localStorage / 表单全清。 */
    private fun clearWebData() {
        runCatching {
            onMain {
                val cm = CookieManager.getInstance()
                cm.removeAllCookies(null)
                cm.flush()
                WebStorage.getInstance().deleteAllData()
                webView?.clearCache(true)
                webView?.clearHistory()
                webView?.clearFormData()
            }
        }.onFailure { Log.w(TAG, "clear web data failed", it) }
    }

    /**
     * evaluateJavascript 是异步回调：这里在主线程上同步等它回来（带超时），
     * 并把 JSON 编码的结果**解码回原始值**（字符串不再带引号与转义）。
     * 超时与"页面返回 null"用 [WebProtocol.EvalOutcome] 分开，不混为一谈。
     */
    private fun evalInPage(wv: WebView, js: String, timeoutMs: Long = EVAL_TIMEOUT_MS): WebProtocol.EvalOutcome {
        var outcome: WebProtocol.EvalOutcome? = null
        val posted = onMain(timeoutMs + 1_000) {
            val latch = CountDownLatch(1)
            wv.evaluateJavascript(js) { result ->
                outcome = WebProtocol.EvalOutcome.Value(WebProtocol.decodeEvalResult(result))
                latch.countDown()
            }
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) Unit else Unit
        }
        return outcome ?: WebProtocol.EvalOutcome.Timeout(timeoutMs)
    }

    // ---------- 通知 ----------

    private fun refreshNotification() {
        runCatching {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(null))
        }
    }

    private fun buildNotification(overrideText: String?): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pendingOpen = PendingIntent.getActivity(
            this, 6, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            this, 7, Intent(this, WebAutomationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.web_notif_title))
            .setContentText(overrideText ?: statusText)
            .setSmallIcon(R.drawable.ic_tile)
            .setContentIntent(pendingOpen)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.web_notif_stop), stopIntent).build()
            )
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "WebAutomationService"
        private const val CHANNEL_ID = "term-lou-web"
        private const val NOTIFICATION_ID = 8
        private const val JSON_TYPE = "application/json; charset=utf-8"
        private const val MAX_BODY = 2 * 1024 * 1024
        private const val MAX_HEAD = 64 * 1024
        private const val MAX_CLIENTS = 8
        private const val MAX_WAIT_MS = 60_000
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val EVAL_TIMEOUT_MS = 20_000L
        private const val TEARDOWN_JOIN_MS = 3_000L
        private const val LOOPBACK = "127.0.0.1"
        private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")

        /**
         * `type` 用的页面侧助手：先用原生 setter 改 value，再派发 input/change，
         * 让框架能观察到这次改动。
         */
        private const val FILL_HELPER = "function WEB_FILL(e,v,clear){" +
            "if(!e)return false;" +
            "try{e.scrollIntoView({block:'center'})}catch(_){}" +
            "try{e.focus()}catch(_){}" +
            "var proto=e instanceof HTMLTextAreaElement?" +
            "HTMLTextAreaElement.prototype:HTMLInputElement.prototype;" +
            "var d=Object.getOwnPropertyDescriptor(proto,'value');" +
            "if(clear){if(d&&d.set){d.set.call(e,'')}else{e.value=''}}" +
            "if(d&&d.set){d.set.call(e,v)}else{e.value=v}" +
            "try{e.dispatchEvent(new Event('input',{bubbles:true}))}catch(_){}" +
            "try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(_){}" +
            "return true}"

        const val ACTION_START = "com.workspace.proot.WEB_START"
        const val ACTION_STOP = "com.workspace.proot.WEB_STOP"

        @Volatile var isRunning = false
            private set
        @Volatile var statusText = ""
            private set

        /** 每次安装固定令牌：首次生成后落 prefs，agent skill 里可写死。 */
        fun currentToken(context: Context): String {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            prefs.getString(KEY_TOKEN, null)?.let { return it }
            val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val token = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY_TOKEN, token).apply()
            return token
        }

        fun start(context: Context) {
            context.startForegroundService(
                Intent(context, WebAutomationService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            runCatching {
                context.startService(
                    Intent(context, WebAutomationService::class.java).setAction(ACTION_STOP)
                )
            }
        }

        /** 进程启动即无痕（app 里没有第二个 WebView，故清全部 WebView 数据只影响本功能）。 */
        fun wipeOnProcessStart(context: Context) {
            runCatching {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            }.onFailure { Log.w(TAG, "wipe failed", it) }
        }

        private const val PREFS = "term-lou-web"
        private const val KEY_TOKEN = "webToken"
    }
}

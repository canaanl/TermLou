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
import java.io.BufferedInputStream
import java.io.PushbackInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.SecureRandom
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

/**
 * 无头浏览器前台服务（5.9.0 / 5.9.1）：**手动开关式**（照 [LanShareService] 的模式），开着时
 * 在**回环地址**固定端口提供 HTTP/JSON 指令接口，供 Linux 侧 agent 用系统自带 WebView 驱动网页。
 *
 * 设计要点：
 *  - **只听 127.0.0.1**：绑回环地址，同一 WiFi 下别的设备连不上。令牌虽然固定，
 *    但 `GET /help` 是免令牌的，不绑回环等于把令牌公开给整个局域网；
 *  - **无头**：WebView 挂在本服务自己的 overlay 窗口上、整体挪到屏幕左侧外（用户看不见），
 *    渲染照常进行，所以能取 HTML、能截图；
 *  - **懒加载**：服务常驻但 WebView 只在第一条 `open` 时创建，会话结束即 destroy——
 *    闲置时只有一个空壳进程；
 *  - **无痕**：进程启动、服务启动、会话结束/关闭，三处都会清 cookie / 缓存 / localStorage /
 *    表单数据，且不保存密码；app 里没有第二个 WebView，故"清全部 WebView 数据"只影响本功能；
 *  - **不占队头**：请求线程池多条线程并发；只有**碰 WebView 的指令**才抢 [pageLock] 串行，
 *    `ping` 之类的轻量指令不会被一条卡住的 `open` 堵在后面；
 *  - **断开即收手**：客户端被杀掉（超时/中断）时，等待循环每 100ms 探一次 socket，
 *    一断就立刻放弃并跳过写响应，不留一个"卡在加载态"的幽灵指令占着页面锁；
 *  - **绝不在主线程上等回调**：见 [UiEval] 的说明（5.9.0 的主线程死锁就是从这来的）；
 *  - **产物可见**：截图与 cookie 落在 Linux 可见的 `~/web/`（= filesDir/workspace/web），
 *    另有 `web.env` 写着端口与令牌（**仅服务运行时存在**），供 agent 自发现；
 *  - **令牌**：每次安装随机生成一次并固定下来（可复制进 agent skill），只存在 TermLou 私有
 *    目录，别的 app 读不到；端口固定，被占用时启动失败并如实报错。
 *
 * 端口/协议/选择器/产物路径/主线程投递的纯逻辑在 [WebProtocol]、[WebSelector]、
 * [WebArtifacts]、[UiEval]（均可单测）。
 */
class WebAutomationService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val wm: WindowManager by lazy { getSystemService(WindowManager::class.java) }

    /**
     * 请求线程池：多条线程（不再是一条），连接数有上限，满了回 503。
     * 页面操作的串行性由 [pageLock] 保证，不由线程数保证。
     */
    private val workers: ThreadPoolExecutor by lazy {
        ThreadPoolExecutor(
            2, MAX_CLIENTS, 30L, TimeUnit.SECONDS, ArrayBlockingQueue(MAX_CLIENTS),
            { r -> Thread(r, "term-lou-web-op").apply { isDaemon = true } },
            ThreadPoolExecutor.AbortPolicy()
        )
    }

    /** 页面操作锁：只有碰 WebView 的指令才抢，探活之类不排队。 */
    private val pageLock = ReentrantLock()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    /** 当前连接的带缓冲输入流（探测客户端存活要用它，不能直接用裸 Socket 流）。 */
    private var probeInput: PushbackInputStream? = null
    @Volatile private var running = false
    @Volatile private var stopping = false
    private var webView: WebView? = null

    /** 页面加载进度：100 = 完成。[pageReady] 只在主线程写，读侧靠 volatile 同步。 */
    @Volatile private var progress = 0
    @Volatile private var pageReady = false

    /** 最近一次主文档加载失败的错误码（如 ERROR_TIMEOUT），由 [WebViewClient] 记。 */
    @Volatile private var lastErrorCode: String? = null

    /**
     * 导航代数：WebView 每发起一次新导航就 +1（[pageClient] 的 `onPageStarted`）。
     *
     * 用途是区分"这次点击**真的**引起了导航"和"点了但页面没动"（`<a>` 被
     * `preventDefault`、点了个纯展示的 div）。`onPageStarted` 在主线程上晚一步才到，
     * 所以只能靠操作**前后**的代数差来判断，不能靠读页面状态。
     */
    @Volatile private var navSeq = 0L

    /** overlay 窗口最后一次拿到的实际几何（诊断用；null = 窗口还没加上）。 */
    @Volatile private var hostBounds: String? = null

    private val pageClient = object : WebViewClient() {
        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            navSeq++
            progress = 0
            pageReady = false
            lastErrorCode = null
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            progress = 100
            pageReady = true
        }

        /**
         * 加载失败：记下错误码。
         *
         * 这一层比"抓正文找 ERR_"可靠——`onReceivedError` 拿得到系统给出的确切码
         * （`ERROR_TIMEOUT` 等）。注意 WebView 加载自己的错误页时**照样会触发
         * `onPageFinished`**，所以只靠 ready 会把"打开失败"误报成成功（5.9.4 修的就是这个）。
         */
        override fun onReceivedError(
            view: WebView?,
            request: android.webkit.WebResourceRequest?,
            error: android.webkit.WebResourceError?
        ) {
            if (request?.isForMainFrame != true) return          // 子资源失败不关我们的事
            lastErrorCode = error?.errorCode?.toString()
        }

        /** HTTP 层失败（4xx/5xx）：只有主文档才算。 */
        override fun onReceivedHttpError(
            view: WebView?,
            request: android.webkit.WebResourceRequest?,
            errorResponse: android.webkit.WebResourceResponse?
        ) {
            if (request?.isForMainFrame != true) return
            val code = errorResponse?.statusCode ?: return
            if (code >= 400) lastErrorCode = "HTTP_$code"
        }

        override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
            // 站内跳转（点了链接）也算一轮加载完。
            // progress 必须一起置 100：否则 ready:true 会和 progress:10 并存，
            // 两个字段自相矛盾（5.9.4 真机复查指出）。
            progress = 100
            pageReady = true
        }
    }

    private val mainUi = UiEval.Ui { task -> postToMain(task) }

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
        markActivity()
        statusText = getString(R.string.web_running_fmt, port)
        WebArtifacts.writeEnv(this, port, currentToken(this))
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
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    // ---------- HTTP 分发 ----------

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

    private fun route(request: WebProtocol.Request, client: Socket): ByteArray {
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
        // 任何一条真指令都算"用过了"：重置闲置计时（探活不算），status 的空闲提示据此判断
        if (op != "ping") markActivity()
        val response = try {
            json(handle(op, request) { isClientGone(client) })
        } catch (e: Exception) {
            Log.e(TAG, "op $op failed", e)
            json(WebProtocol.errJson(e.message ?: e.javaClass.simpleName))
        }
        return response
    }

    private fun json(body: String) = WebProtocol.httpResponse(200, body, JSON_TYPE)

    private fun handle(op: String, request: WebProtocol.Request, cancelled: () -> Boolean): String {
        // 探活不碰页面也不抢锁：一条卡住的 open 不该把 ping 堵在后面
        if (op == "ping") return WebProtocol.okJson("msg" to "pong", "port" to WebProtocol.DEFAULT_PORT)
        // diag 只读状态（overlay 几何 / 页面可见性 / 错误码），故也不抢锁——
        // 这样"卡住了"的时候还能用它看出卡在哪
        if (op == "diag") return opDiag()

        pageLock.lock()
        try {
            if (cancelled()) return WebProtocol.errJson("client disconnected")
            return when (op) {
                "open" -> opOpen(request, cancelled)
                "wait" -> opWait(request, cancelled)
                "eval" -> opEval(request, cancelled)
                "html" -> opHtml(cancelled)
                "text" -> opText(request, cancelled)
                "click" -> opClick(request, cancelled)
                "type" -> opType(request, cancelled)
                "select" -> opSelect(request, cancelled)
                "shot" -> opShot()
                "back" -> opBack(request, cancelled)
                "reload" -> opReload(request, cancelled)
                "cookies" -> opCookies()
                "clear", "close" -> opClose()
                else -> WebProtocol.errJson("unknown op: $op")
            }
        } finally {
            pageLock.unlock()
        }
    }

    /**
     * 客户端是否已经断开。
     *
     * HTTP 请求已经完整读完了（头 + 按 Content-Length 读的体），正常情况下**不会再有字节**，
     * 所以这里读一个字节来试探：读到 -1 说明对端发了 FIN（agent 的 shell 被外层杀了就是这条路径）；
     * 万一真读到了（畸形请求），`unread` 退回去，不影响后续解析。
     *
     * 用 `PushbackInputStream` 而不是 `peek()`：后者不是标准 InputStream API。
     */
    private fun isClientGone(client: Socket): Boolean {
        val input = probeInput ?: return false
        return try {
            val previous = client.soTimeout
            val got = try {
                client.soTimeout = PROBE_TIMEOUT_MS
                input.read()
            } finally {
                client.soTimeout = previous
            }
            if (got >= 0) input.unread(got)
            got == -1                      // 对端发了 FIN
        } catch (e: SocketTimeoutException) {
            false                        // 没数据 = 客户端还在等我们
        } catch (e: Exception) {
            true                         // 连接已经废了
        }
    }

    /**
     * 请求体的读入口。**所有探测都要经过它**：`Socket` 裸流没有 `peek`，
     * 而 `peek` 正是判断"客户端还在不在"的唯一无损手段（`read()` 会把字节吃掉，
     * 后面解析请求体就少了字节）。
     */
    private fun serve(client: Socket) {
        val input = PushbackInputStream(BufferedInputStream(client.getInputStream()), PROBE_PUSHBACK)
        probeInput = input
        runCatching {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val badRequest = WebProtocol.httpResponse(
                400, WebProtocol.errJson("bad request"), JSON_TYPE
            )
            val raw = readRequest(input)
                ?: return@runCatching writeQuietly(client, badRequest)
            val request = WebProtocol.parseRequest(raw)
                ?: return@runCatching writeQuietly(client, badRequest)
            writeQuietly(client, route(request, client))
        }.onFailure { Log.w(TAG, "serve failed", it) }
        if (probeInput === input) probeInput = null
        runCatching { client.close() }
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
        // 记下窗口**实际拿到的**几何：诊断看的是这个，而不是我们请求的值
        // （5.9.4 之前无法回答"挂在屏外的窗口到底渲没渲染"）
        hostBounds = runCatching {
            val dm = resources.displayMetrics
            "x=${params.x} y=${params.y} w=${wv.width} h=${wv.height} " +
                "screen=${dm.widthPixels}x${dm.heightPixels}"
        }.getOrDefault("unknown")
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
        postToMain {
            runCatching {
                wv.stopLoading()
                wm.removeView(wv)
                wv.destroy()
            }.onFailure { Log.w(TAG, "destroy webview failed", it) }
        }
    }

    /** 投递到主线程，不等待（等待绝不能发生在主线程上——见 [UiEval]）。 */
    private fun postToMain(task: () -> Unit): Boolean {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            task()
            return true
        }
        return runCatching { mainHandler.post(task) }.getOrDefault(false)
    }

    /** 需要拿到返回值的主线程调用（短操作：读 url、销毁视图等）。 */
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
     * `wait`（毫秒，默认 0=不等）在这里等页面真正加载完再返回——否则 agent 紧接着
     * `html` 拿到的是加载中的空壳页。客户端中途断开时会提前收手。
     */
    private fun opOpen(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val url = WebProtocol.bodyString(request, "url")
        if (url.isBlank()) return WebProtocol.errJson("missing url")
        val waitMs = WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS)
        val wv = onMain { ensureWebView() } ?: return WebProtocol.errJson("webview timeout")
        markNavigationStarted()          // 必须在 loadUrl 之前（见该函数注释）
        onMain { wv.loadUrl(url) } ?: return WebProtocol.errJson("load timeout")
        val ready = waitForPage(waitMs, cancelled)
        // ready 只说明页面事件完成；落地页可能是 WebView 自己的错误页（它照样触发
        // onPageFinished），所以再判一次"真的能用"，否则 ok:true 会把加载失败糊过去
        unusableReason(wv)?.let { return WebProtocol.errJson("$it — you asked for $url") }
        return WebProtocol.okJson(
            "msg" to "opened",
            "url" to url,
            "ready" to ready,
            "usable" to ready,
            "progress" to progress
        )
    }

    /**
     * 发起导航前**同步**清掉上一页留下的状态。
     *
     * 5.9.4 修：`onPageStarted` 要在主线程上晚一步才到，所以此前 `loadUrl` 之后立刻
     * `waitForPage(0)` 读到的 `pageReady/progress` **是上一页的**——于是
     * `open https://不存在的域名` 报出 `ready:true, usable:true`（可用性探针也是
     * 在旧页面上做的，两个字段一起描述了上一页）。
     * 在投递 `loadUrl` 之前先清：新导航此刻还没开始，它的回调不会被我们擦掉。
     */
    private fun markNavigationStarted() {
        progress = 0
        pageReady = false
        lastErrorCode = null
    }

    /**
     * 等页面加载完成。[waitMs]=0 时只做一次非阻塞检查。
     * 超时**不报错**，把进度如实回给 agent 由它决定是否重试；客户端断开则立刻收手。
     */
    private fun waitForPage(waitMs: Int, cancelled: () -> Boolean): Boolean {
        if (isReadyNow()) return true
        if (waitMs <= 0) return false
        return waitUntilReady(SystemClock.elapsedRealtime() + waitMs, cancelled)
    }

    /** 页面此刻是否就绪（已收到完成事件且进度走满）。 */
    private fun isReadyNow(): Boolean = pageReady && progress >= 100

    /** 轮询到 [deadline] 为止等页面就绪。 */
    private fun waitUntilReady(deadline: Long, cancelled: () -> Boolean): Boolean {
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isReadyNow()) return true
            if (cancelled()) return false
            Thread.sleep(POLL_MS)
        }
        return isReadyNow()
    }

    /**
     * 「可能引起导航」的操作（`click`）之后的就绪判定。
     *
     * 不能像 open 那样先无条件清状态：点击未必导航，那会让"没导航"永远报 ready:false。
     * 也不能不管：点击引起导航时，立刻读到的 `pageReady/progress` **是上一页的**，
     * 于是 `click` 会报 ready:true，紧接着的可用性探针也在旧页面上做（5.9.4 真机复现：
     * 点完立刻报 usable:true）。所以先比导航代数：
     *  - 代数变了 → 新导航已经开始 → 一律按"还没就绪"处理，绝不冒充上一页；
     *  - 代数没变 → 页面确实没动，沿用 WebView 报的状态。
     */
    private fun waitAfterOptionalNav(
        seqBefore: Long,
        waitMs: Int,
        cancelled: () -> Boolean
    ): Boolean {
        val navStarted = navSeq != seqBefore
        if (waitMs <= 0) {
            return WebReady.readyUnwaited(navStarted, pageReady, progress)
        }
        val deadline = SystemClock.elapsedRealtime() + waitMs
        if (navStarted) return waitUntilReady(deadline, cancelled)
        // 先等导航真的开始；到点还没开始就是这次点击没导航，别把时间全耗在空等上
        while (navSeq == seqBefore && SystemClock.elapsedRealtime() < deadline) {
            if (cancelled()) return false
            Thread.sleep(POLL_MS)
        }
        if (cancelled()) return false
        return if (navSeq != seqBefore) waitUntilReady(deadline, cancelled) else isReadyNow()
    }

    /** `wait`：单独等页面就绪。 */
    private fun opWait(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        if (webView == null) return WebProtocol.errJson("no page open")
        val waitMs = WebProtocol.bodyInt(request, "ms", 10_000).coerceIn(0, MAX_WAIT_MS)
        val ready = waitForPage(waitMs, cancelled)
        return WebProtocol.okJson(
            "msg" to if (ready) "ready" else "timeout",
            "ready" to ready,
            "progress" to progress,
            "url" to (onMain { webView?.url } ?: "")
        )
    }

    /**
     * 在页面里求值。**等待发生在当前线程，不在主线程**（5.9.1 修的就是这个）。
     * 结果分四种如实回报：拿到值 / 超时 / 客户端断开 / 投递失败。
     */
    private fun evalInPage(
        wv: WebView,
        js: String,
        cancelled: () -> Boolean
    ): WebProtocol.EvalOutcome = when (
        val result = UiEval.postAndAwait(
            ui = mainUi,
            timeoutMs = EVAL_TIMEOUT_MS,
            cancelled = cancelled,
            pollMs = POLL_MS,
            evaluate = { deliver -> wv.evaluateJavascript(js) { deliver(it) } },
            decode = { WebProtocol.decodeEvalResult(it) }
        )
    ) {
        is UiEval.Result.Ok -> WebProtocol.EvalOutcome.Value(result.value)
        is UiEval.Result.TimedOut -> WebProtocol.EvalOutcome.Timeout(result.waitedMs)
        is UiEval.Result.Cancelled -> WebProtocol.EvalOutcome.Cancelled
        is UiEval.Result.NotPosted -> WebProtocol.EvalOutcome.NotPosted
    }

    /** 把失败的结果变成对 agent 说的话；成功返回 null。 */
    private fun outcomeError(outcome: WebProtocol.EvalOutcome): String? = when (outcome) {
        is WebProtocol.EvalOutcome.Timeout -> "evaluate timeout"
        is WebProtocol.EvalOutcome.Cancelled -> "client disconnected"
        is WebProtocol.EvalOutcome.NotPosted -> "cannot reach main thread"
        is WebProtocol.EvalOutcome.Value -> null
    }

    /** 取成功结果里的值（失败时 null，调用方已先排掉 [outcomeError]）。 */
    private fun WebProtocol.EvalOutcome.valueOrNull(): String? =
        (this as? WebProtocol.EvalOutcome.Value)?.value

    /**
     * 落地页**是否真的可用**（5.9.4）。
     *
     * 两层判：
     *  1. [WebViewClient] 记下的 `lastErrorCode`（`ERROR_TIMEOUT` 之类）——最可靠；
     *  2. 页面探针：`location.protocol` 是不是 `chrome-error:`、正文里有没有 `ERR_…`。
     *
     * 返回 null 表示可用；非 null 是给 agent 的错误说明。
     */
    private fun unusableReason(wv: WebView): String? {
        lastErrorCode?.let { code ->
            return "landed on an error page: ${WebPageUsable.describeErrorCode(code)}"
        }
        val outcome = evalInPage(
            wv,
            "(function(){var t='';try{t=document.title||''}catch(e){};" +
                "var b='';try{b=(document.body?document.body.innerText:'').slice(0,400)}catch(e){};" +
                "return t+'\n'+b+'\n'+(location.protocol||'')+'\n'+(location.href||'')})()",
            { false }
        )
        val value = (outcome as? WebProtocol.EvalOutcome.Value)?.value ?: return null
        val parts = value.split('\n')
        if (parts.size < 4) return null
        val title = parts[0]
        val text = parts[1]
        val protocol = parts[2]
        val href = parts[3]
        return when (val v = WebPageUsable.judge(protocol, href, title, text)) {
            is WebPageUsable.Verdict.Usable -> null
            is WebPageUsable.Verdict.ErrorPage ->
                "landed on WebView's own error page (chrome-error://) instead of $href"
            is WebPageUsable.Verdict.ErrorContent ->
                "landed on an error page: net::$v.code (${title.ifEmpty { "no title" }})"
        }
    }

    /**
     * `diag`：诊断探针（5.9.4）。
     *
     * 回答那些"代码上看不出来、只能真机看"的问题——尤其是**挂在屏幕外的 overlay 窗口
     * 到底有没有在渲染**。只读状态，不改变任何行为。
     */
    private fun opDiag(): String {
        val wv = webView
        val overlay = hostBounds ?: "no overlay window yet (no page opened)"
        val base = listOf<Pair<String, Any?>>(
            "overlay" to overlay,
            "viewport" to "${WebProtocol.VIEWPORT_W_DP}x${WebProtocol.VIEWPORT_H_DP} dp",
            "last_error" to (lastErrorCode ?: "none")
        )
        if (wv == null) {
            return WebProtocol.okJson(
                "msg" to "no page open",
                *base.toTypedArray(),
                "note" to "open a page first, then call diag again to see the rendering state"
            )
        }
        val outcome = evalInPage(
            wv,
            "(function(){var r={};" +
                "try{r.protocol=location.protocol}catch(e){r.protocol='?'}" +
                "try{r.href=location.href}catch(e){r.href='?'}" +
                "try{r.title=document.title}catch(e){r.title='?'}" +
                "try{r.readyState=document.readyState}catch(e){r.readyState='?'}" +
                "try{r.docW=document.documentElement.scrollWidth}catch(e){r.docW=-1}" +
                "try{r.docH=document.documentElement.scrollHeight}catch(e){r.docH=-1}" +
                "try{r.bodyLen=(document.body?document.body.innerHTML.length:-1)}catch(e){r.bodyLen=-1}" +
                "try{r.visState=document.visibilityState}catch(e){r.visState='?'}" +
                "try{r.visCss=(document.body?getComputedStyle(document.body).visibility:'-')}catch(e){r.visCss='?'}" +
                "try{r.imgs=document.images.length;r.doneImgs=0;" +
                "for(var i=0;i<document.images.length;i++){if(document.images[i].complete)r.doneImgs++}}catch(e){}" +
                "return JSON.stringify(r)})()",
            { false }
        )
        val raw = (outcome as? WebProtocol.EvalOutcome.Value)?.value
        return WebProtocol.okJson(
            "msg" to "diag",
            *base.toTypedArray(),
            "view" to "w=${wv.width} h=${wv.height} attached=${wv.isAttachedToWindow} vis=${wv.visibility}",
            "ready" to pageReady,
            "progress" to progress,
            "page" to (raw ?: "evaluate failed")
        )
    }

    /**
     * 把页面侧带回来的哨兵翻译成**互不混淆**的错误信息（5.9.3）：
     *
     *  - [WebSelector.NOT_FOUND] —— 元素真的不在 → `not found: <selector>`
     *  - `__TERMLOU_JS_ERROR__:…` —— 选择器语法错 → `bad selector (<sel>): <JS 报错>`
     *  - `WEBERR:…` —— 找到了但操作做不了（不是 text field / 不是 select / 点击抛错）
     *
     * 正常值返回 null。
     *
     * **为什么必须分开**：5.9.0–5.9.2 连续三个版本，"选择器写错"、"元素不在"、"我的脚本报错"
     * 三件事全被塌成同一个 not found / returned null，把排查方向带偏了两次。
     */
    private fun pageSideError(value: String, selector: String): String? = when {
        // 5.9.4 第二道保险：字面量 "null" 也算没找到，绝不能当成内容（见 isMissing）
        WebSelector.isMissing(value) -> "not found: $selector"
        // 第二道保险：字面量 "null" 绝不能当成内容。5.9.4 之前 CSS 选择器查不到元素时
        // 页面侧 `String(null)` 得到的就是字符串 "null"，于是 text/click/type/select
        // 对不存在的元素全报 ok:true（真机实测 #ghost 返回 label:"null"）。
        value == "null" -> "not found: $selector"
        else -> WebSelector.jsErrorOf(value)?.let { "bad selector ($selector): $it" }
            ?: WebOpScripts.webErrorOf(value)?.let { "$it [selector: $selector]" }
    }

/**
     * `eval`：在页面里跑任意 JS，返回值原样带回（字符串就是字符串，不是 JSON 字面量）。
     */
    private fun opEval(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val js = WebProtocol.bodyString(request, "js")
        if (js.isBlank()) return WebProtocol.errJson("missing js")
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val outcome = evalInPage(wv, js, cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson("value" to outcome.valueOrNull(), "url" to (onMain { wv.url } ?: ""))
    }

    private fun opHtml(cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val outcome = evalInPage(wv, "(document.documentElement||{}).outerHTML||''", cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson("html" to outcome.valueOrNull(), "url" to (onMain { wv.url } ?: ""))
    }

    /** `text`：取可见文字；不给选择器就是整页正文。 */
    private fun opText(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val raw = WebProtocol.bodyString(request, "selector")
        val pick = if (raw.isBlank()) null else {
            val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
            WebSelector.pickJs(kind)
        }
        val outcome = evalInPage(wv, WebOpScripts.text(pick), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        val value = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        pageSideError(value, raw)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson("text" to value, "url" to (onMain { wv.url } ?: ""))
    }

    /** `click`：真实点击（不是改状态），元素会先滚进可视区。 */
    private fun opClick(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val raw = WebProtocol.bodyString(request, "selector")
        val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
        val navSeqBefore = navSeq
        val outcome = evalInPage(wv, WebOpScripts.click(WebSelector.pickJs(kind)), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        val label = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        pageSideError(label, raw)?.let { return WebProtocol.errJson(it) }
        val ready = waitAfterOptionalNav(
            navSeqBefore,
            WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS),
            cancelled
        )
        val landed = onMain { wv.url } ?: ""
        // 点击本身成功 ≠ 目的地可用：落地是错误页时如实说出来，但 ok 仍为 true
        //（点击确实发生了，agent 需要知道"点了，但没到想去的地方"）
        val broken = if (ready) unusableReason(wv) else null
        return WebProtocol.okJson(
            "msg" to "clicked",
            "label" to label,
            "ready" to ready,
            "usable" to (ready && broken == null),
            "url" to landed,
            "warning" to broken
        )
    }

    /**
     * `type`：往输入框/文本域填字。
     * 走原生 setter + input/change 事件，而不是逐键模拟——React/Vue 这类框架
     * 监听的是 input 事件上 setter 的痕迹，直接改 `value` 它们收不到。
     */
    private fun opType(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val value = WebProtocol.bodyString(request, "text")
        if (value.isEmpty()) return WebProtocol.errJson("missing text")
        val clear = WebProtocol.bodyOptBoolean(request, "clear", true)
        val raw = WebProtocol.bodyString(request, "selector")
        val pick = if (raw.isBlank()) {
            null                                       // 填到当前焦点元素上
        } else {
            val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
            WebSelector.pickJs(kind)
        }
        val outcome = evalInPage(wv, WebOpScripts.type(pick, value, clear), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        val result = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        pageSideError(result, raw)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson("msg" to "typed", "bytes" to value.length)
    }

    /** `select`：按 value 或可见文字选中 `<select>` 的一项，并派发 change。 */
    private fun opSelect(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val value = WebProtocol.bodyString(request, "value")
        if (value.isBlank()) return WebProtocol.errJson("missing value")
        val raw = WebProtocol.bodyString(request, "selector")
        if (raw.isBlank()) return WebProtocol.errJson("missing selector")
        val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
        val outcome = evalInPage(wv, WebOpScripts.select(WebSelector.pickJs(kind), value), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        val result = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        pageSideError(result, raw)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson("msg" to "selected", "value" to result)
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

    /**
     * 截图取像素，两条路按可靠度依次尝试（`capturePicture` → `View.draw`）。
     *
     * **等比缩放**：此前是 `canvas.scale(w/picW, h/picH)` 两个方向独立缩放，而
     * `capturePicture()` 给的是**整页** Picture（长页面高度远大于视口），于是长页面
     * 被纵向压扁 —— 真机表现为"导出能看到图，但比例不对"。
     * 现在取**单一 scale 因子**（`min`），再居中裁剪：比例永远正确，
     * 长页面只截到视口那么高的一段（想要整页请改用 full 模式）。
     */
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
                // 单一缩放因子：比例正确，多余部分裁掉
                val scale = minOf(
                    width.toFloat() / pic.width,
                    height.toFloat() / pic.height
                )
                canvas.translate((width - pic.width * scale) / 2f, (height - pic.height * scale) / 2f)
                canvas.scale(scale, scale)
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

    /**
     * `back`：后退（可带 `wait`）。
     *
     * 5.9.4 修：`goBack()` 是**异步**导航，此前紧接着读 `wv.url` 拿到的还是**跳转前**的
     * 地址，害得调用方只能自己去 eval `location.href`。现在等新页就绪后再读 url，
     * 语义与 `reload` 对齐。
     */
    private fun opBack(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val canGoBack = onMain { wv.canGoBack() } ?: false
        if (!canGoBack) {
            // 带上历史信息：此前"导航还在途中"和"真的没历史"报同一句话，无法分辨。
            // 导航未提交时确实还没有条目，所以这句同时也是提示：先 wait 再 back。
            val entries = onMain { wv.copyBackForwardList().size } ?: -1
            return WebProtocol.errJson(
                "no history to go back (history entries: $entries) - " +
                    "if a navigation is still in flight, wait for it first: {\"op\":\"wait\"}"
            )
        }
        markNavigationStarted()
        onMain { wv.goBack() } ?: return WebProtocol.errJson("back failed")
        val ready = waitForPage(WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS), cancelled)
        unusableReason(wv)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson(
            "msg" to "back",
            "ready" to ready,
            "usable" to ready,
            "url" to (onMain { wv.url } ?: "")
        )
    }

    /** `reload`：重载当前页（可带 `wait`）。 */
    private fun opReload(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        markNavigationStarted()
        onMain { wv.reload() } ?: return WebProtocol.errJson("reload timeout")
        val ready = waitForPage(WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS), cancelled)
        unusableReason(wv)?.let { return WebProtocol.errJson(it) }
        return WebProtocol.okJson(
            "msg" to "reloaded",
            "ready" to ready,
            "usable" to ready,
            "url" to (onMain { wv.url } ?: "")
        )
    }

    /**
     * 导出 cookie 到 `~/web/cookies.txt|json` 供人查看。
     * Android 不提供"枚举全部 cookie"的 API（[CookieManager] 只能按 URL 取），
     * 因此这里导出**当前页面**可见的 Cookie 头。响应里的 `scope` 字段明说这件事，
     * 免得 agent 把"没导出"当成"没有 cookie"。
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
        private const val MAX_WAIT_MS = 30_000
        private const val SOCKET_TIMEOUT_MS = 60_000
        private const val PROBE_TIMEOUT_MS = 200
        private const val PROBE_PUSHBACK = 1
        private const val EVAL_TIMEOUT_MS = 15_000L
        private const val TEARDOWN_JOIN_MS = 3_000L
        private const val POLL_MS = 100L
        private const val LOOPBACK = "127.0.0.1"

        /**
         * 页面侧"出错了"的哨兵前缀：JS 里无法直接抛异常（会被 evaluateJavascript
         * 吞成 null），所以把错误信息编码成字符串带回来。
         */
        /**
         * `evaluateJavascript` 把 JS 的语法错误/运行时错误**一并吞成 null**，
         * 所以"值是 null"只能说明脚本没跑完——具体原因拿不到。
         * 这句话必须明说"是脚本的问题"，不能像 5.9.2 那样报 `click returned null`，
         * 那会让人以为是"点击了但没结果"，方向完全错。
         */
        private const val SCRIPT_FAILED =
            "script failed to run in the page (this is a bug on the TermLou side, not a bad selector)"

        private val CONTENT_LENGTH = Regex("(?i)content-length:\\s*(\\d+)")

        const val ACTION_START = "com.workspace.proot.WEB_START"
        const val ACTION_STOP = "com.workspace.proot.WEB_STOP"

        @Volatile var isRunning = false
            private set
        @Volatile var statusText = ""
            private set

        /** 最后一次"真指令"的时间（elapsedRealtime），供空闲提示算闲置时长。 */
        @Volatile var lastActivityAt = 0L
            private set

        /** 记录一次使用：服务启动与每条真指令都会调它。 */
        fun markActivity() {
            lastActivityAt = SystemClock.elapsedRealtime()
        }

        /** 已经空闲多久；没在跑算"无限空闲"（但那时压根不该提示）。 */
        fun idleMillis(): Long =
            if (lastActivityAt <= 0L) Long.MAX_VALUE else SystemClock.elapsedRealtime() - lastActivityAt

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

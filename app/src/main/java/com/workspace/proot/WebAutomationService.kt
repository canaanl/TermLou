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
import android.view.View
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
 * 浏览器前台服务（5.9.0 / 5.9.1）：**手动开关式**（照 [LanShareService] 的模式），开着时
 * 在**回环地址**固定端口提供 HTTP/JSON 指令接口，供 Linux 侧 agent 用系统自带 WebView 驱动网页。
 *
 * 设计要点：
 *  - **只听 127.0.0.1**：绑回环地址，同一 WiFi 下别的设备连不上。令牌虽然固定，
 *    但 `GET /help` 是免令牌的，不绑回环等于把令牌公开给整个局域网；
 *  - **可见悬浮窗**（5.9.27）：WebView 挂在屏幕右上角一个**看得见的小窗**里，
 *    agent 的每一步操作用户都能看见。窗口宽 = 屏宽 1/3、长宽比跟屏幕；
 *    按窗体任意处能拖走，点不动它。
 *    ⚠ **5.9.34：视口就是窗口，一个像素对一个像素，没有缩放。**
 *    以前视口锁死 412×892dp 再缩进小窗 —— 而安卓正是照着**屏幕上实际大小**
 *    决定网页要画多少，缩放一压它就只画 480×1056，其余 87% 从没被画过，
 *    整页截图于是永远停在第 2 屏。代价是网页跟着变窄（160dp），
 *    小窗里的字变大约 2.6 倍（用户明确接受）；
 *  - **懒加载**：服务常驻，WebView 只在第一条 `open` 时创建，会话结束即 destroy——
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

    /**
     * 最近一次页面探针 eval 的原始结果（5.9.9），放进 `diag`。
     *
     * `value:…` / `value:null` / `failed:…` / `error:…` 四种。
     * 有了它就不用猜"eval 到底有没有回值"了 ——
     * 那正是 5.9.8 真机上"page height unknown"查不出来的原因。
     */
    @Volatile private var lastEvalOutcome = "n/a"

    /** 悬浮窗最后一次拿到的实际几何（诊断用）。 */
    @Volatile private var hostBounds: String? = null

    /** 悬浮窗当前左上角（拖动会变，`diag` 看得见）。 */
    @Volatile private var floatWindowPos = "0,0"

    /** 悬浮窗建不起来的原因 —— `open` 失败时原样报出去，别报成"timeout"。 */
    @Volatile private var floatWindowError: String? = null

    /** 可见悬浮窗（5.9.27）。窗口在**服务运行期间**一直存在，与页面无关。 */
    private val floatWindow: WebFloatWindowHost by lazy { WebFloatWindowHost(this, wm) }

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
            /**
             * 5.9.32：`onPageFinished` 是**公开 API**里"页面加载完了"的那个回调
             * （`WebChromeClient.onLoadingFinished` 与 `View.postVisualStateCallback`
             * 都是 `@hide`，`javap` 查过 android-34 里根本没有）。
             *
             * 整页截图开拍前等的就是它（或 `document.readyState === 'complete'`）——
             * **官方回调，不是猜一个时间**。页面还在加载时拍，拿到的就是半张。
             */
            pageLoadFinished = true
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
        // 5.9.27：**服务一开就把悬浮窗摆出来**（用户要求：设置里打开服务器才现出浮窗），
        // 空窗里显示"等待 agent 打开页面"。WebView 仍然是懒加载 ——
        // 第一条 `open` 才建，省内存。
        val winErr = onMain { floatWindow.show { x, y -> floatWindowPos = "$x,$y" } }
        if (winErr != null) {
            floatWindowError = winErr
            Log.w(TAG, "float window unavailable: $winErr")
        }
        statusText = if (winErr == null) {
            getString(R.string.web_running_fmt, port)
        } else {
            winErr
        }
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
        // 先摘 WebView 再拆窗（反了会在 removeViewImmediate 时留下野指针）
        destroySession()
        postToMain { floatWindow.hide() }
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

    private fun route(
        request: WebProtocol.Request,
        client: Socket,
        /**
         * 当前连接的带缓冲输入流（5.9.10：由 [serve] 传进来，不再经过 Service 级字段）。
         *
         * ⚠ 它**必须是参数**，不能是字段：此前这里是个 Service 级 `probeInput`，
         * 8 条 worker 线程并发时后来连上的会盖掉先连上的，于是 A 的存活探测
         * 读的是 B 的流 —— A 断了测不出来（幽灵指令占 `pageLock`），
         * B 断了反而误杀 A。超时的 `soTimeout` 设在 `client` 上、
         * 阻塞读的却是别人的流，更是对不上了。
         *
         * 也不能每次拿 `client.getInputStream()` 现包：请求解析已经用这个
         * wrapper 吃过字节了，探测必须用**同一个**（缓冲里的字节不能漏，
         * `unread` 也要退回这个 wrapper）。
         */
        input: PushbackInputStream
    ): ByteArray {
        // ⚠ 5.9.9：**/help 也要令牌**。
        // 此前它免鉴权，而说明书里又把**真令牌**印在 `X-Token: xxx` 那一行 ——
        // Android 上任何 app 访问 127.0.0.1 都不需要任何权限，于是
        // 任何装在手机上的应用一条 `curl http://127.0.0.1:39080/help` 就拿到完整凭据，
        // 之后可以任意驱动这个浏览器。**令牌形同虚设。**
        //
        // agent 的自发现另有正路：`web.env` 里就写着端口与令牌（只存在于服务运行期间，
        // 且在 app 私有目录里，别的 app 读不到）。
        if (request.token != currentToken(this)) {
            return WebProtocol.httpResponse(401, WebProtocol.errJson("bad token"), JSON_TYPE)
        }
        if (request.method == "GET" && request.path == "/help") {
            return WebProtocol.httpResponse(
                200,
                WebProtocol.help(WebProtocol.DEFAULT_PORT),
                "text/plain; charset=utf-8"
            )
        }
        if (request.method != "POST" || request.path != "/op") {
            return WebProtocol.httpResponse(404, WebProtocol.errJson("no such endpoint"), JSON_TYPE)
        }
        val op = WebProtocol.bodyOp(request) ?: return json(WebProtocol.errJson("missing op"))
        // 任何一条真指令都算"用过了"：重置闲置计时（探活不算），status 的空闲提示据此判断
        if (op != "ping") markActivity()
        val response = try {
            json(handle(op, request) { isClientGone(client, input) })
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
                "shot" -> opShot(cancelled)
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
     *
     * ⚠ `input` 必须是**这个连接自己的流**（由 [serve] 当参数一路传下来）。
     * 此前这里读的是 Service 级共享字段，8 条线程并发时 A 的探测会读到 B 的流。
     * 本体逻辑在伴生的 [probeGone] 里（纯函数，可单测），这里只是个薄转发。
     */
    private fun isClientGone(client: Socket, input: PushbackInputStream): Boolean =
        probeGone(client, input)

    /**
     * 请求体的读入口。**所有探测都要经过它**：`Socket` 裸流没有 `peek`，
     * 而 `peek` 正是判断"客户端还在不在"的唯一无损手段（`read()` 会把字节吃掉，
     * 后面解析请求体就少了字节）。
     */
    private fun serve(client: Socket) {
        val input = PushbackInputStream(BufferedInputStream(client.getInputStream()), PROBE_PUSHBACK)
        runCatching {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val badRequest = WebProtocol.httpResponse(
                400, WebProtocol.errJson("bad request"), JSON_TYPE
            )
            val raw = readRequest(input)
                ?: return@runCatching writeQuietly(client, badRequest)
            val request = WebProtocol.parseRequest(raw)
                ?: return@runCatching writeQuietly(client, badRequest)
            writeQuietly(client, route(request, client, input))
        }.onFailure { Log.w(TAG, "serve failed", it) }
        runCatching { client.close() }
    }

    // ---------- 悬浮窗里的 WebView ----------

    /**
     * WebView 懒加载：只有真的要用网页时才创建。
     *
     * 5.9.27：WebView 挂在**可见悬浮窗**上（[attachFloatWindow]）。
     * 窗口本身在 `startServer()` 就摆出来了，等的是这一行把页面装进去。
     *
     * 视口尺寸**就是窗口尺寸**（[viewportSizePx]）—— 没有缩放，
     * 所以安卓量到的、你看到的、要拍的，是同一块东西。
     *
     * @return 建不出窗口时返回 `null`（原因在 [WebFloatWindowHost.lastError]）
     */
    private fun ensureWebView(): WebView? {
        webView?.let { return it }
        val w = viewWidthPx()
        val h = viewHeightPx()
        val wv = WebView(this)
        wv.setBackgroundColor(Color.WHITE)
        // 5.9.36：WebView 换成**软件渲染**。这是公开 API（View.setLayerType，公开）。
        //
        // 为什么：硬件加速时 Chromium 只把"已经光栅化过的那块"交给 draw()，
        // 滚动后新位置的光栅化还没完成，draw() 拿回空白。
        // 软件渲染时 WebView 每次 draw() 重新渲染整页，不依赖光栅化缓存。
        // 代价：CPU 占用略高（大页面），但截图正确率大幅提升。
        @Suppress("DEPRECATION")
        runCatching { wv.setLayerType(View.LAYER_TYPE_SOFTWARE, null) }
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // ⚠ 5.9.9：**显式关掉文件与内容访问**。
            // 这几项默认是 true，意味着 `{"op":"open","url":"file:///data/data/com.workspace.proot/..."}`
            // 能读到 app 私有目录（笔记、设置、令牌都在里面），
            // 再用 `{"op":"html"}` 原样取回。WebView 跑在 app 进程里、用 app 的权限，
            // 所以 sandbox 对它不生效。
            // 这个 WebView 只用来上网，本来就不需要碰本地文件。
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            allowFileAccessFromFileURLs = false
            @Suppress("DEPRECATION")
            allowUniversalAccessFromFileURLs = false
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
        // 视口尺寸必须先给足 —— 挂窗口之后 WebView 照这个尺寸排版。
        // 5.9.34：这个尺寸**就是窗口尺寸**（屏宽 ÷ 3），网页与窗口一个像素对一个像素。
        layoutView(wv, w, h)
        // 5.9.27：挂**可见**悬浮窗。没权限就如实报错，不静默退回屏外
        val err = attachFloatWindow(wv)
        if (err != null) {
            runCatching { wv.destroy() }
            return null
        }
        webView = wv
        progress = 0
        pageReady = false
        return wv
    }

    /**
     * 视口尺寸 —— **412×892dp × density**（5.9.36 把缩放请回来了）。
     *
     * ## 为什么请回来
     *
     * 5.9.34 我把它改成"视口 = 窗口"（160dp 宽），理由是"缩放是第 2 屏的原因"。
     * **那个理由是错的** —— 5.9.34/5.9.35 都没有缩放，第 2 屏照样失败。
     * 缩放从来不是原因，而我为它付出了画质：360px 视口里字只有 19px，发虚。
     *
     * 用户要的是**清楚**。所以视口改回 412dp（1236×2676），靠
     * [WebFloatWindow.scaleFactors] 缩进小窗显示。
     *
     * ## 真正的根因是别的（5.9.36）
     *
     * 取像素的那一行是 `wv.draw(canvas)`。硬件加速时 Chromium 只把**已光栅化**
     * 的区域交给 `draw()`；滚动后新位置的光栅化还没完成，于是 `draw()` 拿回空白。
     * `setLayerType(LAYER_TYPE_SOFTWARE)` 让 WebView 每次 `draw()` 重新渲染，
     * 不依赖光栅化缓存 —— 这才是根治，见 [ensureWebView]。
     *
     * ⚠ 缩放挂在**容器**上（5.9.31 的结论），WebView 自己的 scale 恒为 1。
     */
    private fun viewportSizePx(): Pair<Int, Int> {
        val d = resources.displayMetrics.density
        return (WebProtocol.VIEWPORT_W_DP * d).toInt().coerceAtLeast(1) to
            (WebProtocol.VIEWPORT_H_DP * d).toInt().coerceAtLeast(1)
    }

    private fun viewWidthPx(): Int = viewportSizePx().first

    private fun viewHeightPx(): Int = viewportSizePx().second

    /** 手工量尺寸。视口必须先给足 —— 挂窗口之后 WebView 照这个尺寸排版。 */
    private fun layoutView(v: View, w: Int, h: Int) {
        v.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        v.layout(0, 0, w, h)
    }

    /**
     * 建悬浮窗并把 WebView 装进去（5.9.27）。
     *
     * ## 为什么不再有"屏外兜底"
     *
     * 5.9.7–5.9.26 的结构是"无头为主、自检不过才退回屏外 overlay"。自检整条路
     * （[startHeadlessSelfCheck] / [WebHeadless]）现在**全部删掉**：
     * 有了真窗口就没有"要不要窗口"这个问题了 —— 用户要看，权限就是硬需求，
     * 没权限时如实报错，而不是静默退回他看不见的地方。
     *
     * @return 失败时给出人话（`null` = 成功）
     */
    private fun attachFloatWindow(wv: WebView): String? {
        val dm = resources.displayMetrics
        val (winW, winH) = WebFloatWindow.windowSize(dm.widthPixels, dm.heightPixels)
        val err = floatWindow.show { x, y -> floatWindowPos = "$x,$y" }
        if (err != null) {
            floatWindowError = err
            statusText = err
            refreshNotification()
            return err
        }
        floatWindowError = null
        floatWindow.attachWebView(wv, viewWidthPx(), viewHeightPx(), winW, winH)
        hostBounds = runCatching {
            "float ${winW}x$winH at $floatWindowPos screen=${dm.widthPixels}x${dm.heightPixels}"
        }.getOrDefault("unknown")
        return null
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
                // 5.9.27：先把 WebView 从**悬浮窗**里摘下来。
                // 以前是 `wm.removeView(wv)`（WebView 直接挂在窗口根上），
                // 现在窗口里还有一个 DragFrameLayout，摘错地方就是野指针。
                floatWindow.detachWebView(wv)
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
        // ⚠ 5.9.27：建不出窗口时**不能说"timeout"** —— 那会把"没给悬浮窗权限"
        // 报成"主线程忙"，agent 会去重试，而重试一万次也是这个结果。
        // 两种原因分开报。
        val wv = onMain { ensureWebView() }
            ?: return WebProtocol.errJson(floatWindow.lastError ?: "webview timeout")
        markNavigationStarted()          // 必须在 loadUrl 之前（见该函数注释）
        onMain { wv.loadUrl(url) } ?: return WebProtocol.errJson("load timeout")
        val ready = waitForPage(waitMs, cancelled)
        // ready 只说明页面事件完成；落地页可能是 WebView 自己的错误页（它照样触发
        // onPageFinished），所以再判一次"真的能用"，否则 ok:true 会把加载失败糊过去
        val u = probeUsability(wv)
        if (u is WebUsability.Unusable) {
            return WebProtocol.errJson("${u.reason} — you asked for $url")
        }
        return WebProtocol.okJson(
            "msg" to "opened",
            "url" to url,
            "ready" to ready,
            "progress" to progress,
            *WebUsability.fields(u, ready).toTypedArray()
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
        pageLoadFinished = false   // 新导航开始 → 重新算"加载完了没有"
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
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val waitMs = WebProtocol.bodyInt(request, "ms", 10_000).coerceIn(0, MAX_WAIT_MS)
        val ready = waitForPage(waitMs, cancelled)
        // 5.9.5：wait 也回报 usable。open 不带 wait 时 usable 必然是 false（还没渲染完），
        // 想判断能不能用只能再等一次 —— 现在等完就地告诉你，不必额外探针。
        // wait 本身是成功的（等到了或如实报超时），所以落地不可用不改成 ok:false，
        // 而是 ok:true + usable:false + warning，与 click 一致。
        return WebProtocol.okJson(
            "msg" to if (ready) "ready" else "timeout",
            "ready" to ready,
            "progress" to progress,
            "url" to (onMain { wv.url } ?: ""),
            *WebUsability.fields(probeUsability(wv), ready).toTypedArray()
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
        // 发起求值时自己抛了（5.9.9）。**不能当成"页面返回 null"** ——
        // `evaluateJavascript` 自己抛异常通常是 WebView 已经不可用（destroy 了、
        // 上下文没了），把它说成 null 会让人往 JS 上找原因，而原因在宿主这边
        is UiEval.Result.Failed -> WebProtocol.EvalOutcome.Failed(
            result.error.message ?: result.error.javaClass.simpleName
        )
    }

    /** 把失败的结果变成对 agent 说的话；成功返回 null。 */
    private fun outcomeError(outcome: WebProtocol.EvalOutcome): String? = when (outcome) {
        is WebProtocol.EvalOutcome.Timeout -> "evaluate timeout"
        is WebProtocol.EvalOutcome.Cancelled -> "client disconnected"
        is WebProtocol.EvalOutcome.NotPosted -> "cannot reach main thread"
        is WebProtocol.EvalOutcome.Failed -> "evaluate failed (${outcome.reason})"
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
    /**
     * 落地页能不能真的用来干活。**三态**（5.9.5）。
     *
     * ⚠ 必须把"探不到"和"能用"分开：此前探测一失败（超时 / 投不进主线程 / 答案不完整）
     * 就返回 null，而 null 的含义是"可用" —— 探测自己坏了却被报成页面能用，
     * 正是 5.9.3–5.9.4 刚清掉的那类假成功，只是搬到了探测层。
     */

    /**
     * 页面探针：一次 eval 拿回 标题 / 正文片段 / 协议 / 当前地址。
     *
     * ## 必须 JSON.stringify，不能手拼分隔符（5.9.9 修的严重 bug）
     *
     * 此前是 `return t+'\n'+b+'\n'+protocol+'\n'+href`：
     *
     *  - Kotlin 里的 `'\n'` 编译后是**真实的 LF 字符**，被塞进 JS 的**单引号字符串
     *    字面量**中间。JS 不允许字面量里裸换行 → **整段语法错** →
     *    `evaluateJavascript` 回调 `null`。
     *  - 本地 node 复现过：`SyntaxError: Invalid or unexpected token`，
     *    双反斜杠（`"\\n"`）才通过。
     *  - 真机表现：`usable` **对每一个页面**都是 `Unknown("probe returned no value")`，
     *    `open`/`wait`/`click`/`back`/`reload` 五处都瞎。
     *  - 而且 Kotlin 侧 `split('\n')` 也错：正文（`innerText` 截 400 字）几乎必然
     *    自带换行，`parts[2]` 拿到的是正文第二行而不是 protocol，
     *    **错误页检测因此失效**。
     *
     * 改用 `JSON.stringify` 一次干掉两处。全项目只有这一处踩了这个坑 ——
     * `WebSelector.escape`（`:52`）写的是 `"\\n"`，那才是对的。
     */
    private val PROBE_JS = "(function(){var t='';try{t=document.title||''}catch(e){};" +
        "var b='';try{b=(document.body?document.body.innerText:'').slice(0,400)}catch(e){};" +
        "var p='',h='';try{p=location.protocol||'';h=location.href||''}catch(e){};" +
        "return JSON.stringify({t:t,b:b,p:p,h:h})})()"

    /**
     * 探一次落地页。
     *
     * 两层判：① [lastErrorCode]（`onReceivedError` 记的，最可靠）；
     * ② 页面探针（协议是不是 `chrome-error:`、正文有没有已知 `ERR_*` 码）。
     */
    private fun probeUsability(wv: WebView): WebUsability.State {
        lastErrorCode?.let { code ->
            return WebUsability.Unusable("landed on an error page: ${WebPageUsable.describeErrorCode(code)}")
        }
        val outcome = evalInPage(wv, PROBE_JS) { false }
        // ⚠ 这三条以前都掉进"可用"那一支
        outcomeError(outcome)?.let {
            // 5.9.9：把**原始回值**记下来放进 diag。eval 在真页面上不回值这件事
            // 查了很久没定位，最后靠"拿到原始回值"才分得清是脚本没跑起来、
            // 还是页面真的返回了 null。记一行成本几乎为零。
            lastEvalOutcome = if (outcome is WebProtocol.EvalOutcome.Failed) "failed:${outcome.reason}" else it
            return WebUsability.Unknown(it)
        }
        val raw = outcome.valueOrNull()
        lastEvalOutcome = if (raw == null) "value:null" else "value:${raw.take(60)}"
        if (raw == null) return WebUsability.Unknown("probe returned no value")
        val parsed = runCatching { org.json.JSONObject(raw) }.getOrNull()
            ?: return WebUsability.Unknown("probe answer is not JSON (${raw.take(40)})")
        val href = parsed.optString("h", "")
        val title = parsed.optString("t", "")
        return when (val v = WebPageUsable.judge(
            parsed.optString("p", ""),
            href,
            title,
            parsed.optString("b", "")
        )) {
            is WebPageUsable.Verdict.Usable -> WebUsability.Usable
            is WebPageUsable.Verdict.ErrorPage ->
                WebUsability.Unusable("landed on WebView's own error page (chrome-error://) instead of $href")
            is WebPageUsable.Verdict.ErrorContent ->
                WebUsability.Unusable("landed on an error page: net::$v.code (${title.ifEmpty { "no title" }})")
        }
    }

    /** 页面还没加载完时的统一措辞。 */

    /**
     * `diag`：诊断探针（5.9.4）。
     *
     * 回答那些"代码上看不出来、只能真机看"的问题——尤其是**悬浮窗到底有没有在渲染**。
     * 只读状态，不改变任何行为。
     */
    private fun opDiag(): String {
        val wv = webView
        val overlay = hostBounds ?: "float window not created yet (no page opened)"
        val base = listOf<Pair<String, Any?>>(
            // 5.9.27：承载方式只剩一种了（可见悬浮窗），所以 `headless_mode` /
            // `headless_check` / `headless_failed` / `headless_ink*` 四个字段全删。
            // 换成下面三个真机才答得出的问题。
            "rendering" to if (floatWindow.isShown) "float window (visible)" else "no window yet",
            // 窗口实际拿到哪：几何 + 拖到哪了。拖动会变，diag 一问就知
            "float_pos" to floatWindowPos,
            // **多窗口共存那行反射到底成没成** —— `@hide` 字段，必须真机验，
            // 不猜。原样报出来：`hideOverlayWindows=false` / `字段不存在（…）`
            "float_multiwindow" to floatWindow.multiWindowReport,
            // **拖动诊断**：`down=0` = 触摸压根没进窗口（标志位/窗口类型那一层）；
            // `down>0` = 拦到了、问题在落地那一步（layout_error 会带原因）。
            // 5.9.27 真机上"按住拖不动"就是靠这一栏定到位置的。
            "float_touch" to floatWindow.touchReport,
            // 5.9.34：**没有缩放了**。报一个恒等的 1，是为了盯住"谁又加回了缩放"——
            // 安卓是照着屏幕上实际大小决定网页要画多少的，缩放一压就只画那一小块。
            "float_scale" to "none (viewport == window, 1:1)",
            // 最近一趟整页截图，最慢的一屏等了几帧才画好（>1 = 页面出帧慢）
            "shot_frame_waits" to lastScreenWaits.toString(),
            // 5.9.35：本趟等画面时，帧回调被主线程叫醒过几次。
            // 0 = 主线程一直没空（那正是 5.9.34 的病）；>0 = 主线程是通的。
            "shot_frame_commits" to lastFrameCommits.toString(),
            // 5.9.9：最近一次页面探针 eval 的原始结果。分四种：
            // value:… / value:null / failed:… / error:…
            "eval_raw" to lastEvalOutcome,
            "overlay" to overlay,
            // 5.9.34：视口 = 窗口 = 屏宽 ÷ 3，两个数必须是同一个
            "viewport" to "${viewWidthPx()}x${viewHeightPx()} px",
            "last_error" to (lastErrorCode ?: "none"),
            "float_error" to (floatWindowError ?: "none")
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
        // （点击确实发生了，agent 需要知道"点了，但没到想去的地方"）
        return WebProtocol.okJson(
            "msg" to "clicked",
            "label" to label,
            "ready" to ready,
            "url" to landed,
            *WebUsability.fields(probeUsability(wv), ready).toTypedArray()
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
     * `shot`：一屏一张，从上到下顺序拍；**不拼接、不缩放**（5.9.34 重写）。
     *
     * ## 流程
     *
     * ```
     * 量一次页面高
     *   页面 <= 一屏 → 拍一屏 → 存 → 完事
     *   页面 >  一屏 →
     *     y = 0
     *     循环：
     *       1 滚到 y，回读滚动位置确认真到了      <- 判据
     *       2 等画面画完：探针连两次指纹相同      <- 判据
     *       3 画一屏（窗口尺寸，一个像素不缩）   <- 动作
     *       4 验不是白图                        <- 判据
     *       5 立刻落盘 + 回收位图                <- 不攒在内存里
     *       6 重读页面度量（页面可能又长高了）
     *       7 下一屏起点夹到最远，走不动就停
     * ```
     *
     * ## 判据与动作分开（5.9.34 最重要的一处改动）
     *
     * 5.9.33 的 `awaitScreenStable()` 用**截图**判断"画面画完没有"：
     * 拍一张 → 白吗 → 再拍一张 → 和上一张一样吗。
     * 判据和被测对象是同一件事，于是截图这条路一瞎，循环只剩一个结局 ——
     * 一直白、一直等、最后报一句 `nothing rendered (blank)`。
     * 真机连续三个版本都卡在同一句话上（5.9.31 第 3 屏、5.9.32 第 2 屏、5.9.33 第 2 屏），
     * 而那句话什么都没说清，我只能靠反推才猜出"整页图其实只有一屏高"。
     *
     * 现在三步各有各的判据，失败也各有各的说法（见 [ShotRunner.failure]）：
     *
     * | 步 | 问什么 | 失败说 |
     * |---|---|---|
     * | 1 | 滚到位了吗 | `the page would not scroll to y=…` |
     * | 2 | 画面画完了吗 | `the window showed nothing new at y=…` |
     * | 3 | 拍到东西了吗 | `the capture came back blank at y=…` |
     *
     * ## 只有一条拍摄路径
     *
     * [captureScreen] —— 把 WebView 原尺寸画进一张位图。就这一条。
     * 5.9.33 之前还有一条"取整页图再切块"的兜底，而它切出来的是**1 像素高的白条**
     *（`capturePicture()` 给的不是整页，是当前那一屏），正是 `blank` 的来源。
     *
     * ## 5.9.34 为什么这条路才走得通
     *
     * 视口 = 窗口 = 屏宽 ÷ 3，**没有缩放**。安卓量到的网页大小 = 屏幕上真实那块，
     * 所以它画多少我们就能拿多少。以前缩放一压，它只画 480×1056，
     * 1236×2676 里剩下 87% 从来没被画过 —— 不是画了看不见，是压根没画。
     */
    private fun opShot(cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val density = resources.displayMetrics.density
        val (vw, vh) = viewportSizePx()
        if (vw <= 0 || vh <= 0) return WebProtocol.errJson("the browser window has no size")

        // 问一次页面度量（CSS px）。问不到就照常截一屏，但**如实说**没量到 ——
        // 不能让 agent 以为这一张就是整页。
        //
        // ⚠ 拆成两行，别串成一条链：`outcomeError()` 成功时返回 null，
        // 串起来会让成功那一路直接短路，取值那行根本不执行（5.9.9 踩过）。
        val metricsOutcome = evalInPage(wv, ShotRunner.METRICS_JS, cancelled)
        outcomeError(metricsOutcome)?.let { return WebProtocol.errJson(it) }
        val metrics = ShotRunner.parseMetrics(metricsOutcome.valueOrNull())
        val pageHeightPx = metrics?.let { (it.pageHeightCss * density).toInt().coerceAtLeast(0) } ?: 0

        // ⚠ 5.9.34：**拍一张立刻写一张**（[WebArtifacts.ShotWriter]）。
        // 旧做法把所有屏攒在内存里最后一起写 —— 每屏 2 MB，
        // 视口变窄后一篇长文章十几屏就是三十几 MB，长页面能把它撑爆。
        val writer = WebArtifacts.ShotWriter(this)
        if (!writer.open()) return WebProtocol.errJson("shot write failed")

        val walk = if (ShotRunner.needsScroll(pageHeightPx, vh)) {
            walkScreens(wv, vw, vh, density, writer, awaitPageSettled(wv, cancelled), cancelled)
        } else {
            // 短页面：一屏就够，不用等静止（等也是白等，页面本来就在一屏里）
            val bmp = onMain { captureScreen(wv, vw, vh) }
            if (bmp == null || !hasContent(bmp)) {
                bmp?.recycle()
                writer.close()
                return WebProtocol.errJson(SHOT_NOTHING)
            }
            if (writer.write(1, bmp) == null) {
                bmp.recycle()
                writer.close()
                return WebProtocol.errJson("shot write failed")
            }
            // 记下**真实**尺寸：报给 agent 的 width/height 用它，不用视口参数。
            val w = bmp.width
            val h = bmp.height
            bmp.recycle()
            Screens(0, true, null, pageHeightPx, 0, w, h)
        }

        val files = writer.close()
        return finishShot(files, vw, vh, pageHeightPx, walk)
    }

    /**
     * 滚一屏拍一屏，走到**实测的底**为止。
     *
     * ## 边走边量，不预排段数
     *
     * 页面在拍摄途中还会长高（懒加载、评论展开）—— 真机实测两次 `shot` 的
     * `page_height` 是 **6621 → 7443**。所以**每拍完一屏就重读**一次 `maxScroll`，
     * 页面长了多少下一屏自动跟上。**过期计划这一类问题从结构上不存在了。**
     *
     * ## "到底了"也是实测的
     *
     * [ShotRunner.nextScreenTop] 返回 null 才停 —— 不是"拍够 N 屏"。
     * 屏数是走出来的，不是算出来的。
     *
     * @param settledUpFront 开拍前页面是否已静止（写进 note）
     */
    private fun walkScreens(
        wv: WebView,
        vw: Int,
        vh: Int,
        density: Float,
        writer: WebArtifacts.ShotWriter,
        settledUpFront: Boolean,
        cancelled: () -> Boolean
    ): Screens {
        var yDevice = 0
        var screenNo = 0
        var measuredPageHeight = 0
        var unsettled = 0
        var worstWaits = 0
        var prevPrint = 0L            // 上一屏的指纹；0 = 还没有上一屏
        var shotW = 0
        var shotH = 0

        /** 每条返回路径都要过它 —— `lastScreenWaits` 只有走这里才不会是上一次的旧值（5.9.35）。 */
        fun done(s: Screens): Screens {
            lastScreenWaits = worstWaits
            return s
        }

        while (true) {
            screenNo++
            if (cancelled()) {
                return done(Screens(worstWaits, settledUpFront, "client disconnected", measuredPageHeight, unsettled))
            }
            if (screenNo > ShotRunner.MAX_SCREENS) {
                return done(Screens(
                    worstWaits, settledUpFront,
                    "stopped after ${ShotRunner.MAX_SCREENS} screens - the page reports more " +
                        "content than that; it may be an infinite-scroll feed",
                    measuredPageHeight, unsettled
                ))
            }

            // 1. 滚到这一屏的起点。JS 滚**文档**，回读确认真到了。
            if (!scrollDocumentTo(wv, ShotRunner.toCss(yDevice, density), cancelled)) {
                return done(Screens(
                    worstWaits, settledUpFront,
                    ShotRunner.failure(1, screenNo, yDevice),
                    measuredPageHeight, unsettled
                ))
            }

            // 2. 等这一屏**画面画完**。
            //
            // ⚠ 5.9.35：**不许把整个等待循环交给主线程执行**（旧写法就是那样）。
            // 那个写法把整个等待循环（含 sleep）放进主线程，而网页要把新画面
            // 交给主线程画 —— 主线程在睡，网页永远画不出来。
            // 真机症状：第 1 屏正常，第 2 屏开始整片空白 4 秒。
            // 这里直接在**工作线程**上等它。
            frameCommitsThisRun = 0
            val ready = awaitScreenStable(wv, vw, vh)
            if (ready == null) {
                // 探针连图都画不出来 —— 卡在**第 3 步**（5.9.36 与"画面是白的"分开）。
                // 第 3 步压根没走到：探针就空了，说明"印的手伸不进去"。
                return done(Screens(
                    worstWaits, settledUpFront,
                    ShotRunner.failure(3, screenNo, yDevice, "probe could not be drawn at all"),
                    measuredPageHeight, unsettled
                ))
            }
            if (!ready.hasContent || ready.bitmap == null) {
                // 一直是白的 —— 卡在**第 2 步**（画面没画出来），
                // 不是第 3 步。5.9.34 这里贴错了标签，害我只能靠反推。
                ready.bitmap?.recycle()
                return done(Screens(
                    worstWaits, settledUpFront,
                    ShotRunner.failure(2, screenNo, yDevice, "within ${SHOT_FRAME_BUDGET_MS}ms"),
                    measuredPageHeight, unsettled
                ))
            }
            if (!ready.settled) unsettled++
            worstWaits = maxOf(worstWaits, ready.waits)
            lastScreenWaits = worstWaits
            lastFrameCommits = frameCommitsThisRun

            // ⚠ 5.9.35：**这屏不许和上一屏一样**。
            // 画面卡住不更新时探针拍到的是旧内容，"连两次相同"会误判成"画完了"，
            // 于是交出一张和上一屏一模一样的图 —— agent 会以为翻页了。
            if (prevPrint != 0L && WebShotSampler.sameContent(prevPrint, ready.print)) {
                ready.bitmap.recycle()
                return done(Screens(
                    worstWaits, settledUpFront,
                    ShotRunner.failure(4, screenNo, yDevice),
                    measuredPageHeight, unsettled
                ))
            }
            prevPrint = ready.print

            // 3. 立刻落盘，然后立刻回收位图 —— 内存里最多只有一屏。
            val shot = ready.bitmap
            // 记下**真实**尺寸：报给 agent 的 width/height 用它，不用视口参数。
            shotW = shot.width
            shotH = shot.height
            val written = writer.write(screenNo, shot)
            shot.recycle()
            if (written == null) {
                return done(Screens(worstWaits, settledUpFront, "shot write failed", measuredPageHeight, unsettled, shotW, shotH))
            }

            // 4. **每屏重读**页面度量 —— 页面可能又长高了。绝不缓存。
            val m = readMetrics(wv, cancelled)
            if (m == null) {
                return done(Screens(
                    worstWaits, settledUpFront,
                    "screen $screenNo: could not read the page metrics",
                    measuredPageHeight, unsettled
                ))
            }
            measuredPageHeight = (m.pageHeightCss * density).toInt().coerceAtLeast(0)

            // 5. **实测**判"到底了"：null 就是底。
            val nextY = ShotRunner.nextScreenTop(
                yDevice, vh, (m.maxScrollCss * density).toInt().coerceAtLeast(0)
            ) ?: break
            yDevice = nextY
        }
        lastFrameCommits = frameCommitsThisRun
        return done(Screens(worstWaits, settledUpFront, null, measuredPageHeight, unsettled))
    }

    /**
     * [walkScreens] 的过程记录。
     *
     * ⚠ **不再持有位图**（5.9.34）—— 拍一张写一张，位图当场回收。
     * 旧结构是 `List<Bitmap>`，一篇长文章十几屏就是三十几 MB 全压在内存里。
     */
    private class Screens(
        /** 最慢的一屏等了几帧（`diag` 用）。 */
        val worstWaits: Int,
        /** 开拍前页面是否已经静止。 */
        val settledUpFront: Boolean,
        /** 失败原因；`null` = 走到底了。 */
        val error: String?,
        /** 走完之后**实测**的整页高（设备像素）。 */
        val measuredPageHeight: Int = 0,
        /** 有几屏是"超时降级收下"的（画面还在加载）。 */
        val unsettledScreens: Int = 0,
        /**
         * 落盘那张图的**真实**像素宽（5.9.35）。`0` = 一张都没拍出来。
         *
         * 报给 agent 的 `width` 用它，不用视口参数 —— 视图没量好时位图比参数小，
         * 报参数就等于给了一个对不上的尺寸。
         */
        val shotW: Int = 0,
        /** 落盘那张图的**真实**像素高。见 [shotW]。 */
        val shotH: Int = 0
    )

    /** 一屏的结果（5.9.34：位图只活到落盘那一刻）。 */
    private class ScreenReady(
        /** 拍下来的这一屏。`null` = 没画出来（`hasContent` 同时为 false）。 */
        val bitmap: Bitmap?,
        /** 探针指纹。`0` = 没量到。 */
        val print: Long,
        val hasContent: Boolean,
        val waits: Int,
        /** `true` = 连续两帧画面相同，确已画完；`false` = 超时降级收下。 */
        val settled: Boolean
    )

    /**
     * 等到"这一屏**画面画完**"为止。
     *
     * ## ⚠ 这个方法**跑在工作线程上，绝不能整体丢进主线程**（5.9.35）
     *
     * 5.9.31–5.9.34 它是被主线程整个包住的，于是循环里的 Thread.sleep
     * 全在主线程上睡；而网页要把新画面交给主线程画，主线程在睡就永远画不出来。
     *
     * 真机症状：第 1 屏正常（内容早就画好了，不需要画新的），
     * 第 2 屏开始整片空白 4 秒 —— `screen 2: nothing rendered`。
     *
     * 现在：等待在工作线程做，主线程只在两个瞬间被借用，进去就出来：
     * 注册帧回调、画那张 48 见方的探针。
     *
     * ## 判据：连续两次探针指纹相同
     *
     * 探针只回答"画面变了没有"，**不拿去交差** —— 交出去的是原尺寸那一屏。
     *
     * ## 四种结局（5.9.36：探针画不出来和画面一直是白的是两回事，分开）
     *
     * - 连两次相同 → 画完了，才去拍整屏（settled = true）
     * - 变了但一直没稳 → 照收，但 settled = false，note 里明写这屏可能还在加载
     * - 一直是白的 → 返回 hasContent = false，调用方报**第 2 步**
     * - 探针连图都画不出来 → 返回 null，调用方报**第 3 步**
     *
     * @return 画好了就带位图；一直是白的返回 hasContent=false；探针都画不出来返回 null
     */
    private fun awaitScreenStable(wv: WebView, vw: Int, vh: Int): ScreenReady? {
        val deadline = System.currentTimeMillis() + SHOT_FRAME_BUDGET_MS
        var waits = 0
        var lastPrint = 0L
        var probeFailed = false      // 探针连图都画不出来（区别于"画面一直是白的"）
        while (true) {
            // 主线程：注册帧回调（立刻返回）。信号由主线程派发，所以只能记标志位，
            // 绝不能在这里等 —— 堵着主线程等主线程，注定等不到。
            // ⚠ registerFrameCommitCallback **必须在主线程注册**，所以这两下也走 onMain，
            // 但它们进去就出来，绝不等待。
            onMain { armFrameCommit(wv) }
            // 主线程：画探针（立刻返回）
            val probe = onMain { probeScreen(wv, vw, vh) }
            val observedFrame = onMain { disarmFrameCommit(wv) } ?: false
            if (observedFrame) frameCommitsThisRun++

            if (probe == null) {
                // ⚠ 探针都画不出来 —— 这是"印的手伸不进去"，不是"画面是白的"（5.9.36 分开）。
                // 立刻放弃：再等下去结果一样，只会白白浪费 4 秒。
                probeFailed = true
                waits++
                if (System.currentTimeMillis() >= deadline) return null
                runCatching { Thread.sleep(SHOT_FRAME_POLL_MS) }   // ← 工作线程
                continue
            }
            val blank = WebShotSampler.looksBlank(probe.width, probe.height) { x, y ->
                probe.getPixel(x, y)
            }
            val print = WebShotSampler.fingerprint(probe.width, probe.height) { x, y ->
                probe.getPixel(x, y)
            }
            probe.recycle()

            if (blank) {
                // 窗口里一个像素都没画出来 —— 照实说，别等满预算再报一句含糊的 blank
                waits++
                if (System.currentTimeMillis() >= deadline) {
                    return if (probeFailed) null else ScreenReady(null, 0L, false, waits, false)
                }
                runCatching { Thread.sleep(SHOT_FRAME_POLL_MS) }   // ← 工作线程
                continue
            }

            if (print == lastPrint) {
                // 画面不再变了 = 画完了。现在才去拍整屏。
                val bmp = onMain { captureScreen(wv, vw, vh) }
                if (bmp == null || !hasContent(bmp)) {
                    bmp?.recycle()
                    return null
                }
                return ScreenReady(bmp, WebShotSampler.fingerprint(bmp.width, bmp.height) { x, y ->
                    bmp.getPixel(x, y)
                }, true, waits, true)
            }
            lastPrint = print
            waits++
            if (System.currentTimeMillis() >= deadline) {
                // 变了但一直没稳：降级收下，但如实标记"没稳"。
                val final = onMain { captureScreen(wv, vw, vh) }
                if (final == null || !hasContent(final)) {
                    final?.recycle()
                    return null
                }
                val finalPrint = WebShotSampler.fingerprint(final.width, final.height) { x, y ->
                    final.getPixel(x, y)
                }
                return ScreenReady(final, finalPrint, true, waits, false)
            }
            runCatching { Thread.sleep(SHOT_FRAME_POLL_MS) }   // ← 工作线程
        }
    }

    /**
     * 最近一趟整页截图里，帧回调被主线程叫醒过几次（`diag` 诊断）。
     *
     * `0` = 主线程一直没空下来（5.9.34 就是这样）；`>0` = 主线程是通的。
     */
    @Volatile private var lastFrameCommits = 0

    /** 帧回调被叫醒时置位（主线程写），工作线程读走。 */
    @Volatile private var frameCommitSeen = false

    /** 本趟累计被叫醒的次数（工作线程自增）。 */
    private var frameCommitsThisRun = 0

    /**
     * 注册一个帧提交回调 —— **只注册，绝不在这里等**（5.9.35）。
     *
     * ## 为什么必须拆成 arm / disarm
     *
     * 5.9.31–5.9.34 是"注册 → `latch.await` → 注销"。而帧回调是**主线程派发**的，
     * 调用它的时候已经在主线程上了 —— `latch.await` 就是在**堵着主线程等主线程**，
     * 注定等不到，只会白占满 200ms 然后放开。
     *
     * 而且它被 [awaitScreenStable] 在一个"整体跑在主线程"的循环里调用，
     * 于是每轮白堵 200ms —— 网页连"被画出来"的机会都没有。
     *
     * 现在：注册完立刻返回，工作线程去 sleep，主线程空出来把画面画完，
     * 回调把 [frameCommitSeen] 置位，下一轮读走。
     *
     * 方法名是 registerFrameCommit**Callback**（不是 Listener）——
     * `javap` 查过 android-34 的 ViewTreeObserver，只有 Callback 那两个。
     */
    private fun armFrameCommit(wv: WebView) {
        if (android.os.Build.VERSION.SDK_INT < 29) return
        val observer = wv.viewTreeObserver
        if (!observer.isAlive) return
        frameCommitSeen = false
        val l = Runnable { runCatching { frameCommitSeen = true } }
        frameListener = l
        runCatching { observer.registerFrameCommitCallback(l) }
    }

    /**
     * 注销帧回调，返回这一轮里帧有没有真的提交过。
     *
     * **必须传同一个 lambda 实例** —— 新建一个是注销不掉的，
     * 那会让每次截图都往 ViewTreeObserver 上多挂一个回调，越用越多。
     */
    private fun disarmFrameCommit(wv: WebView): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        val observer = wv.viewTreeObserver
        if (!observer.isAlive) return false
        val l = frameListener ?: return false
        frameListener = null
        runCatching { observer.unregisterFrameCommitCallback(l) }
        return frameCommitSeen
    }

    /** 当前注册的帧回调（arm 时建、disarm 时清）。 */
    private var frameListener: Runnable? = null

    /** 最近一趟整页截图里，最慢的一屏等了几帧（`diag` 诊断；>1 = 页面出帧慢）。 */
    @Volatile private var lastScreenWaits = 0

    /**
     * 页面（含全部资源）是否加载完（5.9.32）。
     *
     * 由 [WebChromeClient.onLoadingFinished] 置位，新导航开始时清掉。
     */
    @Volatile private var pageLoadFinished = false

    /**
     * 开拍前等"页面静止"的上限（5.9.32）。
     *
     * 等的是 [pageLoadFinished] 或 `document.readyState === 'complete'` ——
     * **官方回调，不是猜的时间**。超时也照常开拍（不能因为动画永远截不到），
     * 但会在 note 里说明。
     */
    private val PAGE_SETTLE_BUDGET_MS = 5000L
    private val PAGE_SETTLE_POLL_MS = 250L

    // 整页截图的屏数上限搬到了 [ShotRunner.MAX_SCREENS]。

    /**
     * 把**文档**滚到指定 CSS 像素，并回读确认真的到位。
     *
     * @return 到位（容差 2 CSS px）返回 true；滚不动 / 超时 / 页面报错返回 false
     */
    private fun scrollDocumentTo(
        wv: WebView, targetCss: Int, cancelled: () -> Boolean
    ): Boolean {
        // 先看现在在哪：已经在那儿就别白等一轮
        val startCss = readScrollCss(wv, cancelled) ?: return false
        if (ShotRunner.scrollLanded(targetCss, startCss)) return true

        val asked = evalInPage(wv, ShotRunner.scrollToJs(targetCss), cancelled)
        outcomeError(asked)?.let { return false }

        // ⚠ **必须回读，不能只看 JS 有没有回值。** `window.scrollTo` 是个请求：
        // 页面可以用 scroll-snap 改掉、可以用 JS 拦掉、可以在滚动动画里还没到位。
        // 回读到的才是**真的**滚动位置。
        for (attempt in 1..SCROLL_SETTLE_TRIES) {
            if (cancelled()) return false
            val nowCss = readScrollCss(wv, cancelled) ?: return false
            if (ShotRunner.scrollLanded(targetCss, nowCss)) return true
            runCatching { Thread.sleep(SCROLL_SETTLE_WAIT_MS) }
        }
        return false
    }

    /** 读当前文档滚动位置（CSS px）。读不到返回 null。 */
    private fun readScrollCss(wv: WebView, cancelled: () -> Boolean): Int? {
        val outcome = evalInPage(wv, ShotRunner.SCROLL_Y_JS, cancelled)
        outcomeError(outcome)?.let { return null }
        return outcome.valueOrNull()?.trim()?.toIntOrNull()
    }

    /**
     * 读页面度量：整页高、最大滚动量、当前滚动位置、加载态（一次 eval 拿全）。
     *
     * ⚠ **每拍完一屏都要重读** —— 页面可能又长高了。绝不缓存到循环外。
     */
    private fun readMetrics(wv: WebView, cancelled: () -> Boolean): ShotRunner.Metrics? {
        val outcome = evalInPage(wv, ShotRunner.METRICS_JS, cancelled)
        outcomeError(outcome)?.let { return null }
        return ShotRunner.parseMetrics(outcome.valueOrNull())
    }

    /**
     * 等页面**静止**了再开拍（前置条件，5.9.32）。
     *
     * ## 这是"渲染完了"的标准反馈，不是猜
     *
     * - [pageLoadFinished] 由 [WebChromeClient.onLoadingFinished] 置位
     *   —— 那是"页面连同它的资源都加载完了"的**官方回调**；
     * - `document.readyState === 'complete'` 是 JS 侧同一件事的表述。
     *
     * 页面还在加载时开拍，拿到的是半张 —— 真机第 2 屏的"大块空白/错位"就是加载中的页面。
     *
     * 有上限；一直不静止也照常往下走（不能因为动画就永远截不到），但 note 里会说明。
     */
    private fun awaitPageSettled(wv: WebView, cancelled: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + PAGE_SETTLE_BUDGET_MS
        while (true) {
            if (pageLoadFinished) return true
            val rs = evalInPage(wv, ShotRunner.READY_STATE_JS, cancelled)
            if (outcomeError(rs) == null && rs.valueOrNull()?.trim() == "complete") return true
            if (System.currentTimeMillis() >= deadline) return false
            runCatching { Thread.sleep(PAGE_SETTLE_POLL_MS) }
        }
    }

    /**
     * 组装返回字段（5.9.34 重写：文件已经落盘了，这里只拼 JSON）。
     *
     * ## 返回字段（agent 看这里）
     *
     * | 字段 | 含义 |
     * |---|---|
     * | `files` | **所有屏，按从上到下**（`shot-0007-1.png`、`-2.png`…） |
     * | `screens` | 屏数 |
     * | `file` | 第一屏（保留：老 agent 只认这个字段，不至于炸） |
     * | `width`/`height` | **一屏**的像素尺寸 |
     * | `page_height` | **整页**高（`full_page` 时页面真实高度） |
     *
     * ⚠ **`height` 不是整页高**。老 agent 拿它当页面长度会算错，所以补了 `page_height`，
     * 并在 note 里写明拆成几屏、按顺序看。
     *
     * ⚠ 5.9.34：**不再持有位图**。文件由 [WebArtifacts.ShotWriter] 一张张写完，
     * 位图当场就回收了 —— 长页面不会把内存撑爆。
     *
     * ## 部分失败仍然是 `ok:false`
     *
     * 已拍好的屏照交，但 `error` 说清是第几屏、卡在哪一步（[ShotRunner.failure]）。
     * 半张图报成功比修不好更糟（5.9.9 的教训）。
     *
     * @param files 已落盘的文件，从上到下
     * @param vw/vh 一屏的像素尺寸（= 视口 = 窗口）
     * @param measuredPageHeightPx 开拍前量到的整页高（没量到就是 0）
     */
    private fun finishShot(
        files: List<java.io.File>,
        vw: Int,
        vh: Int,
        measuredPageHeightPx: Int,
        walk: Screens
    ): String {
        if (files.isEmpty()) {
            return walk.error?.let { WebProtocol.errJson(it) } ?: WebProtocol.errJson(SHOT_NOTHING)
        }
        // ⚠ `page_height` 报**走完之后实测**的值。
        // 开拍前量到的那个会过期 —— 真机实测两次 shot 是 6621 → 7443。
        val pageHeightPx = if (walk.measuredPageHeight > 0) walk.measuredPageHeight else measuredPageHeightPx
        // ⚠ 5.9.35：`isLong` 不能再只看拍到的张数。
        // **第 1 屏就失败时 `walk.measuredPageHeight` 还是 0**，旧写法会判成"不是长页面"，
        // 于是明明是 2025px 的长页面却报 `page_height: 756, full_page: false`。
        // 两个来源取大的那个才对。
        val knownPageHeight = maxOf(walk.measuredPageHeight, measuredPageHeightPx)
        val isLong = files.size > 1 || knownPageHeight > vh
        // ⚠ 5.9.35：`width`/`height` 报**真实位图尺寸**（[Screens.shotW/shotH]），
        // 不是视口参数。`captureScreen` 里是 `vw.coerceAtMost(wv.width)` ——
        // 视图没量好时位图更小，报参数就等于告诉 agent 一个对不上的尺寸。
        val shotW = if (walk.shotW > 0) walk.shotW else vw
        val shotH = if (walk.shotH > 0) walk.shotH else vh
        val notes = mutableListOf<String>()
        if (measuredPageHeightPx <= 0) {
            notes += "page height unknown - this is one screen, not the full page"
        }
        if (!walk.settledUpFront) {
            notes += "the page had not finished loading when this shot started; " +
                "later screens may be missing content"
        }
        if (walk.unsettledScreens > 0) {
            notes += "${walk.unsettledScreens} of ${files.size} screen(s) were still loading " +
                "when captured - those images may be incomplete"
        }
        val fields = mutableListOf<Pair<String, Any?>>(
            "files" to files.map { WebArtifacts.linuxPath(this, it) },
            "screens" to files.size,
            "file" to WebArtifacts.linuxPath(this, files[0]),
            "bytes" to files.sumOf { it.length() },
            "width" to shotW,
            "height" to shotH,
            "page_height" to (if (isLong) pageHeightPx else shotH),
            "full_page" to isLong
        )
        if (notes.isNotEmpty()) fields += "note" to notes.joinToString("; ")
        val body = WebProtocol.okJson(*fields.toTypedArray())
        return walk.error?.let { WebProtocol.errJsonWith(it, body) } ?: body
    }
    /**
     * 截不出内容时的说法。
     *
     * 5.9.7 说的是"retry after wait"和"view not laid out"——**两句都误导**：
     * 视图一直是量好的，而重试在"滚出去那块画不出来"时永远没用。
     * 现在只说事实，并把该走的路指出来。
     */
    private val SHOT_NOTHING =
        "shot failed (nothing rendered) — wait for the page, then retry"

    /**
     * 滚动**回读**确认的轮询策略（5.9.29）。
     *
     * `window.scrollTo` 是个**请求**：页面可以用 scroll-snap 改掉、可以用 JS 拦掉、
     * 可以在平滑滚动动画里还没到位。所以必须回读 `window.pageYOffset` 才知道真到没到。
     */
    private val SCROLL_SETTLE_TRIES = 8
    private val SCROLL_SETTLE_WAIT_MS = 120L

    /**
     * 每屏的"等渲染跟上"策略（5.9.31）。
     *
     * ## 为什么不是固定 sleep
     *
     * 5.9.27–5.9.30 用的是**固定 700ms** —— 那是我拍的数字。真机上：
     * 长页面前两屏正常，**第 3 屏起画面不跟随滚动**（页面越往下内容越多，出帧越慢）。
     * 固定等待对"页面有多重"一无所知。
     *
     * ## 现在的做法
     *
     * 事件驱动 + 事实校验，两步：
     *
     * 1. **等一帧真的提交** —— `ViewTreeObserver.registerFrameCommitCallback`（API 29+），
     *    帧真的进到窗口表面时才回调。API 29 以下退回复核循环里的定时等待。
     * 2. **校验内容确实变了** —— 拍一张比指纹（[WebShotSampler.sameContent]）。
     *    变了才算这一屏画好；没变就再等下一帧。
     *
     * 上限 [SHOT_FRAME_BUDGET_MS]：超时按"渲染没跟上"报错并**报出屏号**，
     * 不交一张看起来有图、其实还是上一屏的图。
     */
    private val SHOT_FRAME_BUDGET_MS = 4000L

    /** 每帧之间的复核间隔。API 29 以下没有帧提交回调，靠它轮询。 */
    private val SHOT_FRAME_POLL_MS = 200L

    /**
     * 拍一屏 —— **只有这一条路**（5.9.34）。
     *
     * ## 就是把 WebView 原尺寸画进一张位图
     *
     * 一个像素都不缩、不裁、不拼。视口就是窗口（[viewportSizePx]），
     * 所以画出来的那张 = 用户在小窗里看到的整块，一个像素不差。
     *
     * ## 此前是两条，都删了
     *
     * 1. `capturePicture()` 取整页再切块（5.9.33）——
     *    **切出来的是 1 像素高的白条**：`capturePicture()` 给的不是整页，
     *    是当前那一屏，于是第 2 屏的矩形被夹成 `[picH-1, picH)`。
     *    真机那连续三次的 `screen 2: nothing rendered (blank)` 就是它。
     * 2. `draw()` 失败后退回 `capturePicture()`（5.9.27–5.9.32）——
     *    两条路意味着一条不行时另一条**静默顶替**，交出看着正常、其实错的图。
     *
     * 现在只有一条。没有兜底：不行就如实报错。
     *
     * @param vw/vh 视口尺寸（= 窗口尺寸）
     */
    private fun captureScreen(wv: WebView, vw: Int, vh: Int): Bitmap? {
        if (wv.width <= 0 || wv.height <= 0) return null
        val w = vw.coerceAtMost(wv.width).coerceAtLeast(1)
        val h = vh.coerceAtMost(wv.height).coerceAtLeast(1)
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                wv.draw(canvas)
            }
        }.getOrNull()
    }

    /**
     * 画一张**探针**（5.9.34 新增）：把整个视口缩进 [ShotRunner.PROBE_SIZE] 见方。
     *
     * ## 它只回答"画面变了没有"，不拿去交差
     *
     * 交出去的那一屏是 [captureScreen] 原尺寸画的，48×48 那张小图不会给 agent。
     *
     * ## 为什么要单独一个探针
     *
     * 5.9.33 把"判断画完没有"和"拍这一屏"合成了一步 —— 都是 [captureScreen]。
     * 于是截图这条路一瞎（取不到像素），循环只剩一个结局：
     * 一直白、一直等、最后报一句 `nothing rendered (blank)`。
     * 真机 5.9.31/5.9.32/5.9.33 三个版本都卡在这一句上，而它什么都没说清。
     *
     * 分开之后，卡在哪一步能直接说（[ShotRunner.failure]）：
     * 没滚过去 / 滚过去了画面没变 / 画面变了取不下来。
     *
     * ## 便宜
     *
     * 48×48 = 2304 像素，比整屏 48 万像素便宜两个数量级。
     */
    private fun probeScreen(wv: WebView, vw: Int, vh: Int): Bitmap? {
        if (wv.width <= 0 || wv.height <= 0) return null
        // 量的是**视口**（= 窗口），夹到视图实际尺寸以内 —— 视图还没量好时不能画。
        val sw = vw.coerceAtLeast(1).coerceAtMost(wv.width)
        val sh = vh.coerceAtLeast(1).coerceAtMost(wv.height)
        val size = ShotRunner.PROBE_SIZE
        val s = ShotRunner.probeScale(sw, sh)
        val w = (sw * s).toInt().coerceIn(1, size)
        val h = (sh * s).toInt().coerceIn(1, size)
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                canvas.drawColor(Color.WHITE)
                canvas.scale(w.toFloat() / sw.toFloat(), h.toFloat() / sh.toFloat())
                wv.draw(canvas)
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
        val u = probeUsability(wv)
        if (u is WebUsability.Unusable) return WebProtocol.errJson(u.reason)
        return WebProtocol.okJson(
            "msg" to "back",
            "ready" to ready,
            "url" to (onMain { wv.url } ?: ""),
            *WebUsability.fields(u, ready).toTypedArray()
        )
    }

    /** `reload`：重载当前页（可带 `wait`）。 */
    private fun opReload(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        markNavigationStarted()
        onMain { wv.reload() } ?: return WebProtocol.errJson("reload timeout")
        val ready = waitForPage(WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS), cancelled)
        val u = probeUsability(wv)
        if (u is WebUsability.Unusable) return WebProtocol.errJson(u.reason)
        return WebProtocol.okJson(
            "msg" to "reloaded",
            "ready" to ready,
            "url" to (onMain { wv.url } ?: ""),
            *WebUsability.fields(u, ready).toTypedArray()
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
         * 探一下这个连接的对端还在不在。**纯函数**：只碰传进来的 socket 与流，
         * 不读任何 Service 状态 —— 所以并发时 A 永远探 A 的流（可单测）。
         *
         * 此前 [WebAutomationService.isClientGone] 读的是 Service 级共享字段，
         * 后连上的盖掉先连上的，A 的探测会读到 B 的流。
         */
        internal fun probeGone(client: Socket, input: PushbackInputStream): Boolean {
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

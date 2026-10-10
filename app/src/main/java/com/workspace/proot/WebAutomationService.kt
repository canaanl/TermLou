package com.workspace.proot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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
 *  - **只听 127.0.0.1**：绑回环地址，同一 WiFi 下别的设备连不上。`/help` 与 `/op`
 *    **都要令牌**（5.9.9 修：此前 `/help` 免令牌，而说明书里又把真令牌印在里面，
 *    安卓上任何 app 访问回环都不用权限 —— 令牌形同虚设）；
 *  - **可见悬浮窗**（5.9.27）：WebView 挂在屏幕右上角一个**看得见的小窗**里，
 *    agent 的每一步操作用户都能看见。窗口宽 = 屏宽 1/3、长宽比跟屏幕；
 *    按窗体任意处能拖走，点不动它。
 *    网页按 **412×892dp** 排版（跟正常手机一致），再缩进小窗显示 ——
 *    缩放挂在容器上（[WebFloatWindowHost.ScaleFrameLayout]），WebView 自己恒为 1:1；
 *  - **懒加载**：服务常驻，WebView 只在第一条 `open` 时创建，会话结束即 destroy——
 *    闲置时只有一个空壳进程；
 *  - **不留痕，也不留缓存**（5.9.38 修好）：进程启动、会话销毁（`close` 与**关闭服务**
 *    都走这里）、以及网页实例刚建好那一刻，都会清 cookie / localStorage / 缓存 / 历史 / 表单。
 *    ⚠ 5.9.37 之前那三行"清缓存"写在"销毁之后"，而那时网页已经是 null ——
 *    **从来没执行过**。现在"清数据"是销毁会话的**第一步**，调用方不可能再漏。
 *    加载另有一道：`cacheMode = LOAD_NO_CACHE`（不去用缓存）；
 *  - **唯一产物是 `web.env`**（端口 + 令牌，**仅服务运行时存在**，供 agent 自发现），
 *    落在 Linux 可见的 `/workspace/web/`。5.9.38 起不再导出 cookie、不再有任何截图 ——
 *    这个功能**不保留 cookie**，也不往磁盘上留任何浏览痕迹；
 *  - **不占队头**：请求线程池多条线程并发；只有**碰 WebView 的指令**才抢 [pageLock] 串行，
 *    `ping` 之类的轻量指令不会被一条卡住的 `open` 堵在后面；
 *  - **断开即收手**：客户端被杀掉（超时/中断）时，等待循环每 100ms 探一次 socket，
 *    一断就立刻放弃并跳过写响应，不留一个"卡在加载态"的幽灵指令占着页面锁；
 *  - **绝不在主线程上等回调**：见 [UiEval] 的说明（5.9.0 的主线程死锁就是从这来的）；
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

    /**
     * 新窗口请求的计数器（5.9.38，`diag` 报）。
     *
     * 网页要开新窗口时：手指点的（点链接）我们**接进这一扇窗**（[newWindowsFollowed]），
     * 页面自己弹的（弹窗广告）**丢掉并记一笔**（[newWindowsDropped]）——
     * 不是静默丢，`diag` 里看得见。
     */
    @Volatile private var newWindowsFollowed = 0
    @Volatile private var newWindowsDropped = 0

    /**
     * `web.env` 写成功了吗（5.9.38）。
     *
     * agent 的**自发现全靠这个文件**。写不进去（磁盘满、目录建不出来）时，
     * 从前是 `runCatching` 一声不响 —— 表现只是"agent 连不上"，
     * 两边都看不到原因。现在写失败会在 `diag` 的 `env_written` 里留痕。
     */
    @Volatile private var envWritten: Boolean? = null

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
        // 服务启动即无痕：清 cookie 与 localStorage/IndexedDB。
        //
        // ⚠ 这里清不了缓存 —— 网页实例是懒加载的，此刻还没有 WebView。
        // 缓存由另外两处负责：实例刚建好的瞬间、以及会话销毁之前（见 [wipeWebViewData]）。
        clearCookiesAndStorage()
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
        // 5.9.38：写成功没有要记下来 —— 自发现全靠它，写失败不能一声不响（见 [envWritten]）
        envWritten = WebArtifacts.writeEnv(this, port, currentToken(this))
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
                "extract" -> opExtract(request, cancelled)
                "reload" -> opReload(request, cancelled)
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
        // ⚠ 5.9.37：**不许再动渲染层类型**。
        //
        // 5.9.36 加过 `setLayerType(LAYER_TYPE_SOFTWARE)`，理由是"硬件加速时
        // `wv.draw()` 拿不到没光栅化过的区域"。那个理由对截图是对的，但**代价更大**：
        // 软件图层要自己分配一张视口大小的位图 —— 1236×2676 的 ARGB_8888 是 13.2 MB，
        // 分配失败时系统直接杀服务，于是 teardown → 窗口消失。
        // 真机症状就是"小窗没了 + 第 2 屏照样空白"，两个问题一个都没解决。
        //
        // 5.9.37 删掉整页截图功能，`wv.draw()` 这条路**整个没了**，
        // 硬件加速照原样保留 —— 小窗正常显示、不卡。
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_NO_CACHE
            // ⚠ 5.9.38：**必须显式打开多窗口**，否则"开新窗口"的请求被安卓整个丢掉。
            //
            // 官方文档原话："默认情况下，开新窗口的请求会被忽略 —— 无论它来自 JavaScript
            // 还是来自链接上的 target 属性。" 而默认值就是 false。
            //
            // 真机症状（5.9.37 用户实测）：点搜索结果里带 `target="_blank"` 的那一条，
            // **什么都不发生** —— 不导航、不报错 —— 而 click 只看"JS 没抛异常"，
            // 照样报 ok:true。换一条不带 _blank 的就正常，所以看起来"时好时坏"。
            //
            // 我们只有一扇窗，所以新窗口请求一律**接进这一扇窗**（见下面 onCreateWindow）。
            // ⚠ 这里是 setSupportMultipleWindows(...) 而不是属性赋值：
            // 它的 getter 叫 `supportMultipleWindows()`（不是 `getSupportMultipleWindows`），
            // Kotlin 没把它合成属性。
            setSupportMultipleWindows(true)
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

            /**
             * 新窗口请求 → **接进我们这一扇窗**（5.9.38）。
             *
             * ## 为什么必须有这个
             *
             * 没有它，`target="_blank"` 的链接与 `window.open()` 的导航请求会被安卓
             * **整个丢掉**：不导航、不报错。这是 5.9.37 真机上"点了没反应但 ok:true"
             * 的一个真原因（另一个是 `text=` 点到了搜索框）。
             *
             * ## 为什么只有一条路
             *
             * 我们没有第二扇窗可以给它，所以不新建 WebView、不走 `WebViewTransport` 到别处 ——
             * 把请求的结果对象指回**同一个 WebView**，它就原地加载那个地址。
             * 相当于真人的浏览器里点了个新标签页，而我们只跟着那一个走。
             *
             * ## 只有"手指点的"才跟随
             *
             * [isUserGesture] 为 false 的是页面自己弹的（弹窗广告那一类）。
             * 我们只有一扇预览窗，不该被页面自己弹的东西顶掉 ——
             * 真人的浏览器也拦这种。丢掉的会记一笔，`diag` 里看得见（不是静默丢）。
             */
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (!isUserGesture) {
                    newWindowsDropped++
                    return false
                }
                val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                transport.webView = view
                resultMsg.sendToTarget()
                newWindowsFollowed++
                return true
            }
        }
        // 视口尺寸必须先给足 —— 挂窗口之后 WebView 照这个尺寸排版。
        // ⚠ 5.9.34 曾写"这个尺寸就是窗口尺寸" —— 那是错的，见 [viewportSizePx]。
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
        // ⚠ 5.9.38：**实例刚建好，先清一次缓存/历史/表单**。
        //
        // 这是"每次开关都清空"里补的那条缝：app 被强杀时走不到正常的关闭流程，
        // 上次留下的缓存会躺着。下一次服务开启、网页实例建出来的这一瞬间正好清掉它。
        // 这里 WebView 还是空的（没加载过任何东西），清的是**上次残留**。
        wipeWebViewData(wv)
        return wv
    }

    /**
     * 视口尺寸 —— **412×892dp × density**（5.9.36 改回，5.9.37 保留）。
     *
     * ## 为什么不是"视口 = 窗口"
     *
     * 5.9.34 我改成 160dp 宽（= 屏宽 ÷ 3），理由是"缩放是第 2 屏空白的原因"。
     * **那个理由是错的** —— 5.9.34 / 5.9.35 都没有缩放，第 2 屏照样空白。
     * 缩放从来不是原因，我却为它付出了画质：360px 宽的视口里字只有 19px，发虚。
     *
     * 用户要的是**清楚**。所以视口给足 412dp（1236×2676），靠
     * [WebFloatWindow.scaleFactors] 缩进小窗显示。
     *
     * ⚠ 缩放挂在**容器**上（[WebFloatWindowHost] 的 [WebFloatWindowHost.ScaleFrameLayout]），
     * WebView 自己的 scale 恒为 1 —— 它按 412dp 排版，容器把它缩到 360px 显示。
     *
     * ## 5.9.37：第 2 屏那个 bug 随截图功能一起删掉了
     *
     * 取像素的那一行是 `wv.draw(canvas)`，而这条调用点在 5.9.27–5.9.36 之间
     * **一行都没换过**（只有 [captureScreen] 和 probeScreen 两处，都随截图删了）。
     * 十个版本的症状全是"第 2 屏空白"，说明问题在这条路的取像素上，
     * 不在缩放、不在主线程、不在等待策略。既然 agent 不再需要截图，这条路整个拿掉。
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
        // ⚠ 5.9.38：装不进去必须**说出来**。原来这个函数不返回结果（静默 return），
        // 页面会照常加载、但用户在小窗里根本看不见它，而没有任何一处会说这件事。
        val attachErr = floatWindow.attachWebView(wv, viewWidthPx(), viewHeightPx(), winW, winH)
        if (attachErr != null) {
            floatWindowError = attachErr
            statusText = attachErr
            refreshNotification()
            return attachErr
        }
        hostBounds = runCatching {
            "float ${winW}x$winH at $floatWindowPos screen=${dm.widthPixels}x${dm.heightPixels}"
        }.getOrDefault("unknown")
        return null
    }

    /**
     * 销毁页面会话。**"清数据"是它的第一步**（5.9.38 修）。
     *
     * ## 为什么清数据必须在这里，而不是让调用方记得先调
     *
     * 5.9.37 的情形是 `opClose` 写成"先 `destroySession()`、再 `clearWebData()`"，
     * 而 `clearWebData` 里那三行读的是 `webView?.` —— 那时它**已经是 null**，
     * 于是"清缓存/历史/表单"从写下去那天起**一次都没执行过**。
     *
     * 根因不是"顺序写反了"，而是**把顺序交给调用方记着**。现在清在销毁内部，
     * `close` 与**关闭服务**（[teardown]）两条路都不可能漏 —— 两条路都只走这一个函数。
     *
     * 清与销毁在**同一个主线程任务**里，顺序天然成立（不用赌 handler 的排队顺序）。
     */
    private fun destroySession() {
        val wv = webView
        webView = null
        pageReady = false
        progress = 0
        // removeView/destroy 必须在主线程；清数据也必须在主线程（WebView 的方法）
        postToMain {
            runCatching {
                if (wv != null) {
                    wipeWebViewData(wv)        // ← 先清
                }
                clearCookiesAndStorage()      // ← 不依赖 WebView 的那两样，任何时候都清
                if (wv != null) {
                    wv.stopLoading()
                    // 5.9.27：先把 WebView 从**悬浮窗**里摘下来。
                    // 以前是 `wm.removeView(wv)`（WebView 直接挂在窗口根上），
                    // 现在窗口里还有一个 DragFrameLayout，摘错地方就是野指针。
                    floatWindow.detachWebView(wv)
                    wv.destroy()               // ← 后销毁
                }
            }.onFailure { Log.w(TAG, "destroy session failed", it) }
        }
    }

    /**
     * 清掉与 WebView 实例绑定的那些：缓存（内存 + 磁盘）、历史、表单。
     *
     * ⚠ **必须在 WebView 还活着的时候调** —— 这也是 5.9.37 那个"清缓存从来没跑过"的根因。
     * 调用点只有两个，都在实例活着的时候：实例刚建好（清上次残留）、销毁之前（清这次）。
     */
    private fun wipeWebViewData(wv: WebView) {
        runCatching { wv.clearCache(true) }
            .onFailure { Log.w(TAG, "clearCache failed", it) }
        runCatching { wv.clearHistory() }
            .onFailure { Log.w(TAG, "clearHistory failed", it) }
        runCatching { wv.clearFormData() }
            .onFailure { Log.w(TAG, "clearFormData failed", it) }
    }

    /**
     * 清 cookie 与 localStorage/IndexedDB（**不依赖 WebView 实例**）。
     *
     * 这两样是"不留痕、不留缓存"里最要紧的：cookie 是登录态，localStorage 是网站
     * 往你手机里塞的数据。它们在进程启动、服务启动、会话销毁三处都要清。
     */
    private fun clearCookiesAndStorage() {
        runCatching {
            val cm = CookieManager.getInstance()
            cm.removeAllCookies(null)
            cm.flush()
        }.onFailure { Log.w(TAG, "clear cookies failed", it) }
        runCatching { WebStorage.getInstance().deleteAllData() }
            .onFailure { Log.w(TAG, "clear web storage failed", it) }
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
     * 探一次落地页。
     *
     * 两层判：① [lastErrorCode]（`onReceivedError` 记的，最可靠）；
     * ② 页面探针（协议是不是 `chrome-error:`、正文有没有已知 `ERR_*` 码）。
     */
    private fun probeUsability(wv: WebView): WebUsability.State {
        lastErrorCode?.let { code ->
            return WebUsability.Unusable("landed on an error page: ${WebPageUsable.describeErrorCode(code)}")
        }
        val outcome = evalInPage(wv, WebOpScripts.PROBE_JS) { false }
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
            // **拖动诊断**：`down=0` = 触摸压根没进窗口（标志位/窗口类型那一层）；
            // `down>0` = 拦到了、问题在落地那一步（layout_error 会带原因）。
            // 5.9.27 真机上"按住拖不动"就是靠这一栏定到位置的。
            "float_touch" to floatWindow.touchReport,
            // 5.9.38：新窗口请求的计数。`followed>0` = 有链接要开新窗口、我们接进了这一扇；
            // `dropped>0` = 页面自己弹窗被我们挡了（不是静默挡，这里看得见）。
            "float_newwindow" to "followed=$newWindowsFollowed dropped=$newWindowsDropped",
            // 5.9.37：**报真实缩放因子**，不再硬编码成"没有缩放"。
            // 5.9.36 把缩放请回来了，这一行却忘了改 —— diag 说的和实际做的对不上，
            // 这正是当初那个"扫源码的锁给了坏设计盖章"的同类毛病。现在如实报。
            "float_scale" to {
                val dm = resources.displayMetrics
                val win = WebFloatWindow.windowSize(dm.widthPixels, dm.heightPixels)
                val (sx, sy) = WebFloatWindow.scaleFactors(
                    win.first, win.second, viewWidthPx(), viewHeightPx()
                )
                "x=${"%.3f".format(sx)} y=${"%.3f".format(sy)} " +
                    "(viewport ${viewWidthPx()}x${viewHeightPx()} -> window ${win.first}x${win.second})"
            },
            // 5.9.9：最近一次页面探针 eval 的原始结果。分四种：
            // value:… / value:null / failed:… / error:…
            "eval_raw" to lastEvalOutcome,
            "overlay" to overlay,
            // 5.9.34：视口 = 412dp × density（与窗口不是同一个数，靠缩放联系）
            "viewport" to "${viewWidthPx()}x${viewHeightPx()} px",
            "last_error" to (lastErrorCode ?: "none"),
            "float_error" to (floatWindowError ?: "none"),
            // 5.9.38：web.env 写成功了吗。agent 的自发现全靠它，
            // 写不进去时以前**没人知道**（两边都看不到原因）。
            "env_written" to (envWritten?.toString() ?: "not yet")
        )
        if (wv == null) {
            return WebProtocol.okJson(
                "msg" to "no page open",
                *base.toTypedArray(),
                "note" to "open a page first, then call diag again to see the rendering state"
            )
        }
        val outcome = evalInPage(wv, WebOpScripts.DIAG_JS) { false }
        // ⚠ 5.9.38：把"探针为什么没回值"分开报。原来四种原因（超时 / 客户端断开 /
        // 投不进主线程 / 脚本自己报错）全塌成一句 "evaluate failed" ——
        // 那正是这个项目到处在消灭的"失败混成一锅"。
        val pageJson = when (outcome) {
            is WebProtocol.EvalOutcome.Value -> outcome.value ?: "value:null"
            is WebProtocol.EvalOutcome.Timeout -> "evaluate timeout after ${outcome.ms}ms"
            is WebProtocol.EvalOutcome.Cancelled -> "client disconnected"
            is WebProtocol.EvalOutcome.NotPosted -> "cannot reach main thread"
            is WebProtocol.EvalOutcome.Failed -> "evaluate failed (${outcome.reason})"
        }
        return WebProtocol.okJson(
            "msg" to "diag",
            *base.toTypedArray(),
            "view" to "w=${wv.width} h=${wv.height} attached=${wv.isAttachedToWindow} " +
                // ⚠ 5.9.38：原文印的是安卓的原始整数，而 **0 = 可见**。
                // 谁看都以为"不可见"，5.9.35 我就是被它带偏过一次。现在印名字。
                "vis=${viewVisibilityName(wv.visibility)}",
            "ready" to pageReady,
            "progress" to progress,
            "page" to pageJson
        )
    }

    /** 安卓的可见性整数 → 人话（`0` 是**可见**，这两件事必须印明白）。 */
    private fun viewVisibilityName(v: Int): String = when (v) {
        View.VISIBLE -> "VISIBLE"
        View.INVISIBLE -> "INVISIBLE"
        View.GONE -> "GONE"
        else -> "unknown($v)"
    }

    /**
     * 把页面侧带回来的哨兵翻译成**互不混淆**的错误信息（5.9.3）：
     *
     *  - [WebSelector.NOT_FOUND]（以及字面量 `"null"`）—— 元素真的不在 → `not found: <selector>`
     *  - `__TERMLOU_JS_ERROR__:…` —— 选择器语法错 → `bad selector (<sel>): <JS 报错>`
     *  - `WEBERR:…` —— 找到了但操作做不了（不是 text field / 不是 select / 点击抛错）
     *
     * 正常值返回 null。
     *
     * **为什么必须分开**：5.9.0–5.9.2 连续三个版本，"选择器写错"、"元素不在"、"我的脚本报错"
     * 三件事全被塌成同一个 not found / returned null，把排查方向带偏了两次。
     *
     * （`"null"` 那条合并进 [WebSelector.isMissing] 里了 —— 原来这里又写了一遍，
     * 两处判断同一件事，改一处漏一处。5.9.38 去重。）
     */
    private fun pageSideError(value: String, selector: String): String? = when {
        WebSelector.isMissing(value) -> "not found: $selector"
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
        val outcome = evalInPage(wv, WebOpScripts.OUTER_HTML_JS, cancelled)
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

    /**
     * `click`：真实点击（不是改状态），元素会先滚进可视区。
     *
     * ## 5.9.38 修的两件事（都是"点了没反应，却报成功"）
     *
     * 1. **`text=` 点到了搜索框**：`text=` 原来会把"输入框里现有的字"（`value`）
     *    当匹配依据，而搜索框在页面最前面、里面装的正是搜索词 —— 于是
     *    `click {"selector":"text=某词"}` 会点到顶部搜索框：点一个输入框什么都不会
     *    发生，而这里只看"JS 没抛异常"就报 `ok:true`。现在 `click` 用
     *    [WebSelector.pickJs] 的 clickable 模式（只认人看得见的名字），
     *    并且"文字确实在、但落在不能点的东西上"有**单独一句**（[WebSelector.NOT_CLICKABLE]）。
     * 2. **要开新窗口的链接，导航请求被安卓整个丢掉**（`target="_blank"`、
     *    `window.open`）：不导航、不报错。现在 [onCreateWindow] 把它接进这一扇窗。
     *
     * ## 返回字段（agent 看这里）
     *
     * | 字段 | 含义 |
     * |---|---|
     * | `clicked` | 点了什么：`tag` / `href` / `target` / `text` |
     * | `navigated` | 点完之后**页面真的开始导航了吗**（翻页了没有） |
     * | `ready` | 导航落地并加载完（没给 `wait` 时基本是 false） |
     * | `usable` | 落地的页能不能用（错误页会明说） |
     *
     * ### 什么时候算失败
     *
     * 点的是一个**指向别的页面**的链接，而**页面一点都没动** → `ok:false` 并说清。
     * 这正是真机上"点了没反应"的形状；报成功就等于让 agent 以为已经翻页了。
     *
     * 例外：链接只是**页内锚点**（`#xxx`）时不算失败 —— 它本来就不换页。
     * 另外 `wait` 没给时会**先给它一小会儿**（[CLICK_NAV_GRACE_MS]）让导航真的开始，
     * 不然会把"还没来得及开始"误判成"没反应"。
     */
    private fun opClick(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val raw = WebProtocol.bodyString(request, "selector")
        val kind = WebSelector.parse(raw) ?: return WebProtocol.errJson("bad selector: $raw")
        val navSeqBefore = navSeq
        val outcome = evalInPage(wv, WebOpScripts.click(WebSelector.pickJs(kind, clickable = true)), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        val label = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        // "文字在页面上、但落在不能点的东西上"（典型：搜索框里的搜索词）
        // 与"压根找不到"分开报 —— 这两种的处置完全不同：前者要换一个像标题的文字
        if (WebSelector.isNotClickable(label)) {
            return WebProtocol.errJson(
                "matched an element that cannot be clicked with \"$raw\" - the text is on the " +
                    "page but it sits inside something a person cannot click (a text field, " +
                    "for example). Nothing was clicked. Use the title of a link/button, " +
                    "or a CSS selector like \"a[href=...]\""
            )
        }
        pageSideError(label, raw)?.let { return WebProtocol.errJson(it) }
        val clicked = WebOpScripts.parseClicked(label)
        val requestedWait = WebProtocol.bodyInt(request, "wait", 0).coerceIn(0, MAX_WAIT_MS)
        // 没给 wait 且点的是链接时，先给它一小会儿：导航是异步开始的，
        // 不给自己一个机会就看，会把"还没来得及开始"说成"没反应"。
        val effectiveWait = if (requestedWait == 0 && clicked?.isLink == true) {
            CLICK_NAV_GRACE_MS
        } else {
            requestedWait
        }
        val ready = waitAfterOptionalNav(navSeqBefore, effectiveWait, cancelled)
        val navigated = navSeq != navSeqBefore
        val landed = onMain { wv.url } ?: ""
        val fields = mutableListOf<Pair<String, Any?>>(
            "msg" to "clicked",
            "label" to (clicked?.text ?: label),
            "navigated" to navigated,
            "ready" to ready,
            "url" to landed
        )
        if (clicked != null) {
            fields += "clicked" to mapOf(
                "tag" to clicked.tag,
                "href" to clicked.href,
                "target" to clicked.target
            )
        }
        // 点了链接却一步都没走 → 如实报失败（这是"点了没反应"的形状）
        if (clicked != null && clicked.isLink && !navigated && !isSamePageAnchor(clicked.href, landed)) {
            val why = if (clicked.wantsNewWindow) {
                "the link asks for a new window (target=\"_blank\")"
            } else {
                "the link may be intercepted by the page's own scripts"
            }
            return WebProtocol.errJsonWith(
                "clicked <${clicked.tag}> ${clicked.href} but the page did not navigate at all - " +
                    "$why. Nothing happened; try another target, or open the href directly",
                WebProtocol.okJson(*fields.toTypedArray())
            )
        }
        val u = probeUsability(wv)
        // 点击本身成功 ≠ 目的地可用：落地是错误页时如实说出来，但 ok 仍为 true
        // （点击确实发生了，agent 需要知道"点了，但没到想去的地方"）
        return WebProtocol.okJson(
            *fields.toTypedArray(),
            *WebUsability.fields(u, ready).toTypedArray()
        )
    }

    /**
     * 这个地址是不是"同一页里的锚点"（`#xxx`）——那种链接点完不换页，不算失败。
     *
     * 判据：空的、以 `#` 开头、或者去掉 `#` 之后与当前地址一致。
     */
    private fun isSamePageAnchor(href: String, currentUrl: String): Boolean {
        if (href.isEmpty()) return true
        if (href.startsWith("#")) return true
        val h = href.substringBefore('#')
        val c = currentUrl.substringBefore('#')
        return h == c
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
        val enter = WebProtocol.bodyOptBoolean(request, "enter", false)
        // ⚠ 5.9.38：**必须在这一步之前采样导航代数**。
        //
        // 5.9.37 把它写在 eval **之后**，却当成"动作之前"的值传给
        // `waitAfterOptionalNav` —— 于是回车引起的导航被算成"本次操作之前就发生了"，
        // 等待循环走进"先等导航开始"那一支，白等满 `wait` 才返回。
        // 代码注释在别处反复警告过这个坑（`markNavigationStarted` 必须在 loadUrl 之前），
        // 这里是自己又踩了一次。
        val navSeqBefore = navSeq
        val outcome = evalInPage(wv, WebOpScripts.type(pick, value, clear, enter), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        val result = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        pageSideError(result, raw)?.let { return WebProtocol.errJson(it) }
        // ⚠ 5.9.37：`enter:true` 之后**页面通常正在跳转**。
        // 不等它落定就返回，agent 紧接着读到的还是旧页面 ——
        // 真人按回车之后也是要等一下才看到结果。
        var ready: Boolean? = null
        if (enter) {
            ready = waitAfterOptionalNav(
                seqBefore = navSeqBefore,
                waitMs = WebProtocol.bodyInt(request, "wait", 5_000).coerceIn(0, MAX_WAIT_MS),
                cancelled = cancelled
            )
        }
        val fields = mutableListOf<Pair<String, Any?>>(
            "msg" to "typed",
            "bytes" to value.length,
            "enter" to enter,
            "url" to (onMain { wv.url } ?: "")
        )
        if (enter) {
            // 按回车就是为了"提交/跳转"，所以这件事必须说清楚：
            // 没跳成的时候 agent 要能立刻看出来，而不是接着往旧页面上操作。
            fields += "navigated" to (navSeq != navSeqBefore)
            fields += "ready" to (ready ?: false)
        }
        return WebProtocol.okJson(*fields.toTypedArray())
    }

    /**
     * `extract`：**一次把页面看全**（5.9.37 新增）。
     *
     * ## 它补的是哪一个洞
     *
     * agent 打开网页之后是"瞎的"：它不知道搜索框的选择器叫什么、不知道结果链接在哪。
     * `click` / `type` / `text` 全都以"agent 知道元素叫什么"为前提 ——
     * 所以整条搜索流程卡在第一步。
     *
     * 老路都不行：`text` 是整页正文（几千字，搜索框淹在里面），
     * `html` 是整页源码（几十万字符，撑爆上下文）。
     *
     * ## 它怎么知道什么是什么
     *
     * **不猜，照着网站自己写明的标签读**：`<input>` 是框、`<button>` 是按钮、
     * `<a href>` 是链接。百度写了 `id="kw"`，它就抄 `id="kw"` 告诉 agent。
     *
     * ## 两个上限
     *
     * `limit` 管链接（默认 300、封顶 2000），`text_chars` 管正文（默认 8000、封顶 50000）。
     * **输入框和按钮永不截断** —— 搜索框就在那两张表里，而且总在页面顶部，
     * 所以不管下面滚了多少内容都不会漏掉它。
     *
     * 截了就**明说**（`links_truncated` / `links_total` / `limit_capped`），
     * 不让 agent 以为那就是全部。
     */
    private fun opExtract(request: WebProtocol.Request, cancelled: () -> Boolean): String {
        val wv = webView ?: return WebProtocol.errJson("no page open")
        val rawLimit = WebProtocol.bodyIntOrNull(request, "limit", WebExtract.DEFAULT_LIMIT)
            ?: return WebProtocol.errJson("bad limit: must be a non-negative number")
        val rawChars = WebProtocol.bodyIntOrNull(request, "text_chars", WebExtract.DEFAULT_TEXT_CHARS)
            ?: return WebProtocol.errJson("bad text_chars: must be a non-negative number")
        val limit = try {
            WebExtract.clampLimit(rawLimit)
        } catch (e: IllegalArgumentException) {
            return WebProtocol.errJson(e.message ?: "bad limit")
        }
        val textChars = try {
            WebExtract.clampTextChars(rawChars)
        } catch (e: IllegalArgumentException) {
            return WebProtocol.errJson(e.message ?: "bad text_chars")
        }

        val outcome = evalInPage(wv, WebExtract.pageJs(limit, textChars), cancelled)
        outcomeError(outcome)?.let { return WebProtocol.errJson(it) }
        // ⚠ 5.9.38：`null` 在这里**只有一个意思** —— 我们注入的脚本没跑起来。
        //
        // 页面脚本在每一条路径上都回一个字符串（解析不出也是回 JSON），所以它不可能
        // "算出 null"。而 `evaluateJavascript` 会把**语法错误**一并吞成 null。
        // 5.9.37 这里写的是"the page returned no usable answer — try wait, then retry"，
        // 于是"我的脚本坏了"被说成"页面没答上来，等一等重试"——真机上照它做一万次都没用。
        // 现在照实说：这是 TermLou 自己的问题，别重试。
        val raw = outcome.valueOrNull() ?: return WebProtocol.errJson(SCRIPT_FAILED)
        val page = WebExtract.parse(raw)
            ?: return WebProtocol.errJson(
                "extract: the page answered but the answer could not be read " +
                    "(${raw.take(120)}) - this is a TermLou bug"
            )

        val fields = mutableListOf<Pair<String, Any?>>(
            "url" to page.url,
            "title" to page.title,
            "description" to page.description,
            "text" to page.text,
            "inputs" to page.inputs,
            "buttons" to page.buttons,
            "links" to page.links,
            // 链接总数与是否截断：**必须一起给**，否则 agent 会以为那就是全部
            "links_total" to page.linksTotal,
            "links_truncated" to page.linksTruncated,
            "limit" to limit,
            "text_chars" to textChars
        )
        // 参数被封顶 / 截断，都**如实标记**，不瞒着
        if (WebExtract.limitCapped(rawLimit)) {
            fields += "limit_capped" to true
            fields += "limit_requested" to rawLimit
        }
        if (WebExtract.textCharsCapped(rawChars)) {
            fields += "text_chars_capped" to true
            fields += "text_chars_requested" to rawChars
        }
        val notes = mutableListOf<String>()
        if (page.linksTruncated) {
            notes += "links were cut at $limit of ${page.linksTotal}; " +
                "re-run with a higher limit (max ${WebExtract.MAX_LIMIT}) for the rest"
        }
        if (page.inputs.isEmpty() && page.buttons.isEmpty() && page.links.isEmpty()) {
            notes += "nothing clickable was found on this page - it may still be loading, " +
                "or render itself into <canvas> (text returns nothing there either)"
        }
        if (notes.isNotEmpty()) fields += "note" to notes.joinToString("; ")
        return WebProtocol.okJson(*fields.toTypedArray())
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
     * `reload`：重载当前页（可带 `wait`）。
     *
     * ## 这里没有 `back`（5.9.39 删掉）
     *
     * 安卓 WebView 的"后退"在一个**从没被手指点过的网页**里会一直说"没得退"：
     * `canGoBack()` 返回 false、`goBack()` 不动 —— 而历史其实是好的
     * （`history.length` 与原生列表都数得出条目）。这是平台侧的坑，
     * 有 Tauri 项目与 Chromium 官方 issue 的同类复现记录（"在 WebView 上点一下，
     * 返回就正常了"）。
     *
     * 而我们的窗口**永远不可能被点**，这是两条硬需求决定的、不会变：
     *  - `FLAG_NOT_FOCUSABLE`：不吃输入法焦点，否则 agent 的 `type` 会失灵
     *  - `DragFrameLayout` 吃掉全部触摸：点小窗唯一的行为是拖动
     *
     * 所以这个功能**永久站在那块暗礁上**。既然不是必需的
     * （要回上一页，`open <上一条网址>` 就行，而那个网址 agent 手上一直有：
     * `extract` / `type` 回车 / `open` 的返回里都带着 `url`），
     * 就把它整个删掉 —— 这一类问题连存在的机会都没有了。
     */
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

    private fun opClose(): String {
        destroySession()
        return WebProtocol.okJson("msg" to "closed")
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
         * `evaluateJavascript` 把 JS 的语法错误/运行时错误**一并吞成 null**，
         * 所以"值是 null"只能说明脚本没跑完——具体原因拿不到。
         * 这句话必须明说"是脚本的问题"，不能像 5.9.2 那样报 `click returned null`，
         * 那会让人以为是"点击了但没结果"，方向完全错。
         */
        private const val SCRIPT_FAILED =
            "script failed to run in the page (this is a bug on the TermLou side, not a bad selector)"

        /**
         * `click` 点到链接、但 agent **没给 `wait`** 时，额外给它多久让导航真的开始。
         *
         * 导航是异步开始的（`onPageStarted` 由主线程晚一步派发），点完立刻看会得到
         * "页面没动"的假象。800ms 是"够开始、又不至于让每条 click 都多等"的取中值：
         * 真机上点链接后导航通常在几十毫秒内就开始。给了 `wait` 的走 agent 自己的值。
         */
        private const val CLICK_NAV_GRACE_MS = 800

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

        /**
         * 进程启动即无痕：清 cookie 与 localStorage/IndexedDB。
         *
         * （app 里没有第二个 WebView，故"清全部 WebView 数据"只影响浏览器。
         * 缓存清不了 —— 那需要 WebView 实例，由服务开启时、实例刚建好那一刻负责。）
         */
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

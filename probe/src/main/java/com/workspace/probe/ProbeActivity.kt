package com.workspace.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Presentation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Display
import android.view.Gravity
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File

/**
 * 一次性探针：**不挂窗口的 WebView 到底能不能用**（v2）。
 *
 * 跑三个部分：
 *  1. **合成页**（本地 HTML）：A 不挂窗口 / B 挂 overlay 对照，逐项比
 *  2. **双尺寸**（决定性）：不挂窗口时把视图量成 200dp 与 600dp，看 `innerWidth` 跟不跟
 *  3. **真实站点** example.com：合成页太简单，真实页面更有说服力
 *
 * ⚠ 全程**回调驱动，不在主线程上等**：`evaluateJavascript` 的回调要主线程才能跑，
 * 主线程上一旦 sleep 等它，就是自己等自己（v1 就踩过这个坑，报告整片空白）。
 */
class ProbeActivity : Activity() {

    private val main = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var out: TextView
    private lateinit var runBtn: Button
    private lateinit var permBtn: Button
    private lateinit var urlBox: EditText
    private lateinit var slowBox: CheckBox
    /** 本轮 slowDraw 开关状态（写进报告头，免得两轮跑完分不清哪轮是哪轮）。 */
    private var slowDrawOn = false
    private val prefs by lazy { getSharedPreferences("probe", MODE_PRIVATE) }
    private var attached: View? = null
    private var attachError: String? = null
    private val wm by lazy { getSystemService(WindowManager::class.java) }

    private var loaded = false
    private var loadErr: String? = null
    private lateinit var wv: WebView
    private lateinit var saveDir: File
    private var density = 1f
    private var realSiteLoaded = false
    private var realSiteErr: String? = null

    /** 视图宽高按当前请求的 dp 换算。 */
    private fun px(dp: Int): Int = (dp * density).toInt().coerceAtLeast(1)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        density = resources.displayMetrics.density
        val d = { v: Int -> (v * density).toInt() }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(d(16), d(16), d(16), d(16))
            setBackgroundColor(Color.WHITE)
        }
        status = TextView(this).apply {
            setTextColor(Color.BLACK); textSize = 14f
            text = if (canOverlay()) "可以开始：点「跑测试」。" else "缺悬浮窗权限，B 组对照组会失效。先授权。"
        }
        root.addView(status)
        // v3：真实站点由你填 —— example.com 在有些手机上打不开，测到的只是 WebView 自己的错误页。
        // 填一个你这台手机能正常打开的地址，真实内容才有说服力。留空则跳过该项。
        urlBox = EditText(this).apply {
            setText(prefs.getString(KEY_URL, "") ?: "")
            hint = "真实站点网址，可留空（例：https://www.bing.com）"
            textSize = 14f
            setSingleLine()
        }
        root.addView(urlBox)
        permBtn = Button(this).apply {
            text = "授予悬浮窗权限"; textSize = 16f
            show(!canOverlay())
            setOnClickListener { openOverlaySettings() }
        }
        root.addView(permBtn)
        runBtn = Button(this).apply {
            text = "跑测试"; textSize = 18f
            show(canOverlay())
            setOnClickListener { if (it.isEnabled) { it.isEnabled = false; run() } }
        }
        root.addView(runBtn)
        // v10：enableSlowWholeDocumentDraw 是进程级一次性开关，
        // 必须在第一个 WebView 建出来之前调。同一进程里跑第二遍就不再生效，
        // 所以勾上之后要**划掉探针重进再跑** —— 报告头会写明本轮是开是关。
        slowBox = CheckBox(this).apply {
            text = "slowDraw（勾上后重启探针再跑）"; textSize = 13f
            isChecked = prefs.getBoolean(KEY_SLOW, false)
        }
        root.addView(slowBox)
        out = TextView(this).apply {
            textSize = 11f; setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(0, d(12), 0, 0)
        }
        root.addView(ScrollView(this).apply { addView(out) }, lp(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
    }

    private fun lp(w: Int, h: Int, weight: Float = 0f) =
        LinearLayout.LayoutParams(w, h).apply { if (weight > 0f) this.weight = weight }

    private fun View.show(v: Boolean) { visibility = if (v) View.VISIBLE else View.GONE }
    private fun canOverlay() = Settings.canDrawOverlays(this)

    private fun openOverlaySettings() {
        runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }
            .onFailure { runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) } }
    }

    override fun onResume() {
        super.onResume()
        runCatching {
            val ok = canOverlay()
            permBtn.show(!ok); runBtn.show(ok)
            status.text = if (ok) "可以开始：点「跑测试」。" else "缺悬浮窗权限，B 组对照组会失效。先授权。"
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun run() {
        out.text = ""
        val url = normalizeUrl(urlBox.text.toString()).also {
            prefs.edit().putString(KEY_URL, it).apply()
        }
        slowDrawOn = slowBox.isChecked.also {
            prefs.edit().putBoolean(KEY_SLOW, it).apply()
        }
        saveDir = File(getExternalFilesDir(null), "shots").apply { mkdirs() }
        // 静态开关必须在第一个 WebView 建出来之前 —— 放在 `WebView(this)` 前面
        if (slowDrawOn) WebView.enableSlowWholeDocumentDraw()
        wv = WebView(this)
        wv.setBackgroundColor(Color.WHITE)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = WebSettings.LOAD_NO_CACHE
        }
        wv.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) { loaded = true; realSiteLoaded = true }
            override fun onReceivedError(
                view: WebView, req: android.webkit.WebResourceRequest?, e: android.webkit.WebResourceError?
            ) {
                if (req?.isForMainFrame == true) { loadErr = "err ${e?.errorCode}"; realSiteErr = "err ${e?.errorCode}" }
            }
        }

        // ---- 全部 A 组（不挂窗口）先跑完，再切到 B 组 ----
        synthetic("A 合成页 412dp", 412, wantAttached = false) { a1 ->
            status.text = "A 组 · 双尺寸 200dp…"
            synthetic("A 双尺寸 200dp", 200, wantAttached = false) { a2 ->
                synthetic("A 双尺寸 600dp", 600, wantAttached = false) { a3 ->
                    scrollSweep("A", wantAttached = false) { sa ->
                        realSite("A 真实站点", wantAttached = false, url = url) { a4 ->
                            status.text = "B 组 · 挂窗口（现有方案）…"
                            synthetic("B 合成页 412dp", 412, wantAttached = true) { b1 ->
                                scrollSweep("B", wantAttached = true) { sb ->
                                    realSite("B 真实站点", wantAttached = true, url = url) { b2 ->
                                        report(a1, a2, a3, a4, b1, b2, sa, sb, url)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 扫一遍做法。
     *
     * 每个做法只画**一张图**，从同一张图里同时数绿和红 —— 所以横向可比。
     * 两条截图路（`draw()` 与 `capturePicture()`）各量一次。
     */
    private fun scrollSweep(group: String, wantAttached: Boolean, done: (ScrollSweep) -> Unit) {
        val results = ArrayList<ScrollFix.Result>()
        var i = 0
        fun next() {
            if (i >= ScrollFix.Variant.entries.size) {
                done(ScrollSweep(group, wantAttached, results))
                return
            }
            val v = ScrollFix.Variant.entries[i]
            i++
            status.text = "$group 组 · ${v.label}（$i/${ScrollFix.Variant.entries.size}）…"
            // 结果一到就推进 —— 不在别处等它。v8 头一次就是这一行之后的接线断了，
            // 队列永远不往下走，用户只看到状态冻在 1/5
            runScrollVariant(group, v, wantAttached) { r -> results.add(r); next() }
        }
        next()
    }

    private data class ScrollSweep(
        val group: String,
        val attached: Boolean,
        val results: List<ScrollFix.Result>
    )

    /**
     * 跑一个做法。**把"走队列"整个交给 [StepRunner]**，自己只管执行每一步。
     *
     * ## 为什么不能自己写队列
     *
     * v8 头一次就是把回调一层层往下传，其中一环写成了 `{ done }` ——
     * 那是**函数引用**不是调用。Kotlin 把 lambda 最后一个表达式强制转成 `Unit`，
     * **编译器一声不吭地收下了**。每一步都跑完了、PNG 也存了，
     * 队列永远不往下走，状态文字冻在 `1/5`。
     * 而那个探针做了 8 版全程没有超时，所以"卡住"是不可见的。
     *
     * 现在队列在 [StepRunner] 里，它的两条保证都有测试盯着：
     *  - 每步都有看门狗，回调不来就记下卡在哪、照样往下走
     *  - `then` 一定被调用一次，哪怕每一步都卡住
     *
     * 于是本函数里 `emit` **只出现一次** —— 少一个能"引用而不调用"的地方。
     */
    private fun runScrollVariant(
        group: String,
        v: ScrollFix.Variant,
        wantAttached: Boolean,
        emit: (ScrollFix.Result) -> Unit
    ) {
        if (wantAttached) attach(412) else detach()
        wv.setLayerType(View.LAYER_TYPE_NONE, null)
        wv.visibility = View.VISIBLE
        // v10：预光栅开关。settings 跟着这个 wv 活N轮（同一实例复用），
        // 所以每轮开头必须重设 —— 只写"开"不写"关"，下一轮就串了
        @Suppress("DEPRECATION")
        wv.settings.setOffscreenPreRaster(v.how == ScrollFix.How.PRERASTER)
        // v11：虚拟屏整套资源跟着轮次走 —— 上一轮的不拆，Surface 就 leak 了
        releaseVirtual()
        bitmapScale = 1f
        scrollNote = ""
        sizeTo(412, heightFor(412))

        // MEASURE 那一步量出来的东西放在这儿 —— 它是队列唯一的产出
        var spot: ScrollFix.Spot? = null

        val runner = StepRunner(
            steps = ScrollPlan.stepsFor(v.how),
            later = { ms, block -> main.postDelayed(block, ms) },
            step = { step, done -> doScrollStep(group, v, step) { s -> spot = s; done(true) } }
        )
        runner.run {
            // 唯一把结果交出去的地方。除了这里，`emit` 在本函数里不出现。
            emit(ScrollFix.Result(v, spot ?: deadSpot("队列走完但没量到东西"), runner.stalls.toString()))
        }
    }

    /**
     * 执行一步，做完就调 ok。
     *
     * 每一步**必须**调 ok —— 那就是 v8 头一次丢的那一环。
     * 万一漏了，StepRunner 的看门狗会替它补上，队列照样走得完，并且记下卡在哪一步。
     */
    private fun doScrollStep(
        group: String,
        v: ScrollFix.Variant,
        step: ScrollPlan.Step,
        ok: (ScrollFix.Spot?) -> Unit
    ) {
        when (step) {
            ScrollPlan.Step.LOAD -> {
                val html = if (ScrollPlan.usesShiftedPage(v.how)) ProbePage.longHtmlShifted
                else ProbePage.longHtml
                loaded = false; loadErr = null
                wv.loadDataWithBaseURL("https://probe.local/", html, "text/html", "utf-8", null)
                // LOAD 没有回调可等，就是单纯等首帧
                main.postDelayed({ ok(null) }, step.budgetMs)
            }

            ScrollPlan.Step.READ_HEIGHT ->
                docHeightCssPx { sh -> growTo(sh) { note -> scrollNote = note; ok(null) } }

            ScrollPlan.Step.RESIZE -> main.postDelayed({ ok(null) }, step.budgetMs)

            ScrollPlan.Step.SCROLL ->
                wv.evaluateJavascript(ProbePage.SCROLL_JS) { main.postDelayed({ ok(null) }, 700) }

            ScrollPlan.Step.SCROLL99 ->
                wv.evaluateJavascript(ProbePage.SCROLL_99_JS) { main.postDelayed({ ok(null) }, 700) }

            ScrollPlan.Step.DIRTY ->
                wv.evaluateJavascript(ProbePage.DIRTY_JS) { main.postDelayed({ ok(null) }, 700) }

            ScrollPlan.Step.LDIRTY ->
                wv.evaluateJavascript(ProbePage.LAYOUT_DIRTY_JS) { main.postDelayed({ ok(null) }, 700) }

            // 循环 20 次 × 120ms = 2400ms 才结束；1200ms 后直接量，
            // 量的时候（eval + 两张图约几百毫秒）它还在抖
            ScrollPlan.Step.JITTERLOOP ->
                wv.evaluateJavascript(ProbePage.JITTER_JS) { main.postDelayed({ ok(null) }, 1200) }

            // v10：滚完不自己定时间，等 WebView 说"visual state 好了"。
            // 不来就由看门狗记一笔 —— 在离屏 WebView 上它不来，本身也是数据
            ScrollPlan.Step.VISUAL ->
                wv.postVisualStateCallback(
                    v.ordinal.toLong() + 100L,
                    object : WebView.VisualStateCallback() {
                        override fun onComplete(requestId: Long) {
                            main.postDelayed({ ok(null) }, 200)
                        }
                    }
                )

            ScrollPlan.Step.SOAK -> main.postDelayed({ ok(null) }, step.budgetMs)

            // v11：主 wv 碰都不碰 —— 虚拟屏那套自己建、自己滚、自己取图
            ScrollPlan.Step.VSETUP -> vdSetup(group, v) { ok(null) }

            ScrollPlan.Step.VSCROLL -> vdScroll { ok(null) }

            // PixelCopy 回调里才算完 —— 不来就由看门狗记一笔
            ScrollPlan.Step.VSHOT -> vdShot(group, v) { ok(null) }

            ScrollPlan.Step.REPAINT ->
                wv.evaluateJavascript(ProbePage.REPAINT_JS) { main.postDelayed({ ok(null) }, 600) }

            ScrollPlan.Step.NUDGE ->
                wv.evaluateJavascript(ProbePage.NUDGE_JS) { main.postDelayed({ ok(null) }, 900) }

            // 收尾那步。看门狗替它兜底：量不出来就交一张明确失败的占位图，
            // 绝不装作"量到了 0 个像素"
            ScrollPlan.Step.MEASURE -> measureSpot(group, v) { m -> ok(m) }
        }
    }

    /** 这一轮的例外说明（位图缩放过、视图没撑开之类）。空串表示没例外。 */
    private var scrollNote = ""

    // ---------- v11 虚拟屏 ----------

    private var vdDisplay: VirtualDisplay? = null
    private var vdPres: Presentation? = null
    private var vdWv: WebView? = null
    private var vdTex: SurfaceTexture? = null
    private var vdSurf: Surface? = null
    private var vdW = 0
    private var vdH = 0
    /** PixelCopy 那张量的结果。null = 还没量出来（看门狗会替它兜底）。 */
    private var vdShot: ScrollFix.Shot? = null
    private var vdNote = ""

    private class VPres(context: Context, display: Display) : Presentation(context, display)

    /**
     * 建一套虚拟屏：VirtualDisplay + SurfaceTexture + Presentation + 显示上下文 WebView。
     *
     * 和 overlay 窗口是两回事：`DisplayManager.createVirtualDisplay` 建的是
     * 自家私有显示，**不需要悬浮窗权限** —— "真无头"保得住。
     * 建完加载长页面，等首帧。失败也照样往下走，原因写进 `vdNote`。
     */
    @SuppressLint("SetJavaScriptEnabled")
    private fun vdSetup(group: String, v: ScrollFix.Variant, then: () -> Unit) {
        releaseVirtual()
        vdNote = ""
        vdShot = null
        val wPx = px(412)
        val hPx = heightForPx(412)
        vdW = wPx; vdH = hPx
        val setup = runCatching {
            val tex = SurfaceTexture(0).apply { setDefaultBufferSize(wPx, hPx) }
            val surf = Surface(tex)
            val dm = getSystemService(DisplayManager::class.java)
                ?: throw IllegalStateException("拿不到 DisplayManager")
            val vd = dm.createVirtualDisplay(
                "probe-headless", wPx, hPx, (density * 160).toInt(),
                surf, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
            ) ?: throw IllegalStateException("createVirtualDisplay 返回 null")
            val pres = VPres(this, vd.display)
            val wv2 = WebView(pres.context)
            wv2.setBackgroundColor(Color.WHITE)
            wv2.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                loadWithOverviewMode = true
                useWideViewPort = true
                cacheMode = WebSettings.LOAD_NO_CACHE
            }
            val box = FrameLayout(pres.context)
            box.addView(
                wv2,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
            pres.setContentView(box)
            pres.show()
            vdTex = tex; vdSurf = surf; vdDisplay = vd; vdPres = pres; vdWv = wv2
            wv2.loadDataWithBaseURL("https://probe.local/", ProbePage.longHtml, "text/html", "utf-8", null)
        }
        if (setup.isFailure) {
            vdNote = "虚拟屏没建起来：" + (setup.exceptionOrNull()?.message ?: "?")
        }
        // 本地页 + 首帧，和 LOAD 那步等的时间一个量级
        main.postDelayed({ then() }, ScrollPlan.Step.VSETUP.budgetMs)
    }

    private fun vdScroll(then: () -> Unit) {
        val wv2 = vdWv
        if (wv2 == null) { then(); return }
        wv2.evaluateJavascript(ProbePage.SCROLL_JS) { main.postDelayed({ then() }, 700) }
    }

    /**
     * PixelCopy 从 Surface 取图。**回调里才算完** ——
     * 不来就由看门狗记一笔（Surface 没出 buffer，本身也是数据）。
     */
    private fun vdShot(group: String, v: ScrollFix.Variant, then: () -> Unit) {
        val surf = vdSurf
        if (surf == null || !surf.isValid) {
            vdShot = ScrollFix.Shot(-1, -1, "-", "Surface 无效")
            then()
            return
        }
        val bmp = runCatching {
            Bitmap.createBitmap(vdW, vdH, Bitmap.Config.ARGB_8888)
        }.getOrNull()
        if (bmp == null) {
            vdShot = ScrollFix.Shot(-1, -1, "-", "建不出位图")
            then()
            return
        }
        runCatching {
            PixelCopy.request(surf, Rect(0, 0, vdW, vdH), bmp, { res ->
                vdShot = if (res == PixelCopy.SUCCESS) measure(bmp, null)
                else ScrollFix.Shot(-1, -1, "-", "PixelCopy 返回 $res")
                bmp.let { savePng(it, "$group-${v.name}-vd.png") }
                bmp.recycle()
                then()
            }, main)
        }.onFailure {
            vdShot = ScrollFix.Shot(-1, -1, "-", it.message ?: it.javaClass.simpleName)
            bmp.recycle()
            then()
        }
    }

    /** 拆掉上一轮的虚拟屏。settings 跟着 wv 走，这里是整套资源跟着轮次走。 */
    private fun releaseVirtual() {
        runCatching { vdPres?.dismiss() }
        runCatching { vdDisplay?.release() }
        runCatching { vdSurf?.release() }
        runCatching { vdTex?.release() }
        runCatching { vdWv?.destroy() }
        vdPres = null; vdDisplay = null; vdSurf = null; vdTex = null; vdWv = null
    }

    /**
     * 把视图撑到整页那么高，于是整页就是首屏。
     *
     * 位图面积有上限（[ScrollFix.MAX_PIXELS]），超了就按比例缩 —— 缩放系数
     * 通过 `note` 报出去，免得把缩小后的数当原尺寸的数看。
     * 撑失败也**照样往下走**并把原因写进 `note`：宁可给一个作废的行，
     * 也不要少一行让人以为没测过。
     *
     * 提成成员函数而不是本地函数：本地函数只能先声明后使用，而
     * [growTo] 和队列推进 `next` 是互相递归的 —— 本地函数做不到。
     */
    private fun growTo(shCss: Int, then: (String) -> Unit) {
        var note = ""
        if (shCss <= 0) {
            note = "⚠ 页面没报出高度，视图没撑开"
        } else {
            val wPx = px(412)
            val hPx = (shCss * density).toInt().coerceIn(1, 40_000)
            val f = if (wPx.toLong() * hPx > ScrollFix.MAX_PIXELS) {
                kotlin.math.sqrt(ScrollFix.MAX_PIXELS.toFloat() / (wPx.toFloat() * hPx))
            } else 1f
            bitmapScale = f
            sizeToPx(wPx, hPx)
            // 改了视图高度，页面要按新视口重新排版，等它排完
            if (f != 1f) note = "位图缩到 ${(f * 100).toInt()}%"
        }
        main.postDelayed({ then(note) }, ScrollPlan.Step.RESIZE.budgetMs)
    }

    /**
     * 画一张图，数绿和红，读页面状态。
     *
     * **位图尺寸必须和视图尺寸对得上。** `draw()` 只画视图那么大；
     * 视图比位图矮的话底部那段永远是白纸，看着像"没画出来"，其实压根没截到。
     * 缩放时靠 `canvas.scale` 把视图整个塞进位图，不裁不补。
     */
    private fun measureSpot(
        group: String,
        v: ScrollFix.Variant,
        give: (ScrollFix.Spot) -> Unit
    ) {
        // v11：主 wv 碰都不碰 —— 量的是虚拟屏那张。PixelCopy 只走一趟，
        // picture 那格如实标"没量"，不许装作量过
        if (v.how == ScrollFix.How.VDISPLAY) {
            measureVirtual(give)
            return
        }
        val wPx = px(412)
        val hPx = if (v.how == ScrollFix.How.FULL_PAGE) wv.height else heightForPx(412)
        val s = bitmapScale
        wv.evaluateJavascript(ProbePage.SCROLL_METRICS_JS) { raw ->
            val m = JsString.decodeJson(raw)
            val bw = (wPx * s).toInt().coerceAtLeast(1)
            val bh = (hPx * s).toInt().coerceAtLeast(1)
            // innerHeight 页面报的是 CSS px，这里乘成设备像素，
            // 才能和 wv.height（设备像素）比 —— 拿两把尺子比会得出错的结论
            val ihCss = m?.optInt("ih", -1) ?: -1
            // **画完一张立刻回收再画下一张。** 全高视图那张位图有
            // 1236×7260 ≈ 36MB，两张同时活着就是 72MB，很容易 OOM ——
            // 而 OOM 会被 runCatching 吞掉，表现成「那一路没画成图」
            val draw = oneShot(bw, bh, s) { c -> wv.draw(c) }
            val drawShot = draw.shot
            draw.bitmap?.let { savePng(it, "$group-${v.name}-draw.png") }
            draw.bitmap?.recycle()
            val pic = pictureShot(bw, bh, s)
            pic.bitmap?.let { savePng(it, "$group-${v.name}-pic.png") }
            pic.bitmap?.recycle()
            give(
                ScrollFix.Spot(
                    draw = drawShot,
                    picture = pic.shot,
                    evalOk = m != null,
                    scrollY = m?.optInt("sy", -1) ?: -1,
                    viewW = wv.width,
                    viewH = wv.height,
                    innerHPx = if (ihCss > 0) (ihCss * density).toInt() else ihCss,
                    blockVisible = m?.optBoolean("tailVisible", false) ?: false,
                    note = scrollNote,
                    elAt = m?.optString("el", "") ?: ""
                )
            )
        }
    }

    /** 步骤没跑完时的占位观测。两条路都标成失败，绝不装作"量到了 0"。 */
    private fun deadSpot(why: String): ScrollFix.Spot {
        val bad = ScrollFix.Shot(-1, -1, "-", why)
        return ScrollFix.Spot(bad, bad, false, -1, 0, 0, 0, false, why)
    }

    /**
     * v11：量虚拟屏那张。
     *
     * `draw` = PixelCopy 取到的那张图；`picture` 如实标"本轮没走这条路"。
     * 注意这里不再数"顶部绿"有没有 —— PixelCopy 取的是滚到底之后的那一帧，
     * 顶部本来就不在画面里，绿 0 是对的，不代表没画出来。
     */
    private fun measureVirtual(give: (ScrollFix.Spot) -> Unit) {
        val wv2 = vdWv
        if (wv2 == null) {
            give(deadSpot("虚拟屏 WebView 没建起来"))
            return
        }
        wv2.evaluateJavascript(ProbePage.SCROLL_METRICS_JS) { raw ->
            val m = JsString.decodeJson(raw)
            val ihCss = m?.optInt("ih", -1) ?: -1
            give(
                ScrollFix.Spot(
                    draw = vdShot ?: ScrollFix.Shot(-1, -1, "-", "PixelCopy 没回值"),
                    picture = ScrollFix.Shot(-1, -1, "-", "v11 只走 PixelCopy 这一路"),
                    evalOk = m != null,
                    scrollY = m?.optInt("sy", -1) ?: -1,
                    viewW = vdW,
                    viewH = vdH,
                    innerHPx = if (ihCss > 0) (ihCss * density).toInt() else ihCss,
                    blockVisible = m?.optBoolean("tailVisible", false) ?: false,
                    note = vdNote,
                    elAt = m?.optString("el", "") ?: ""
                )
            )
        }
    }

    /** 页面自己报的高度（CSS px）。取不到返回 0，由调用方决定怎么办。 */
    private fun docHeightCssPx(give: (Int) -> Unit) {
        // 用 String() 包一层：evaluateJavascript 回来的是 JSON 字符串而不是裸数字，
        // 直接 JSONObject(raw) 一定抛（v3 踩过：报空白且不报错）
        wv.evaluateJavascript("String(Math.round(document.documentElement.scrollHeight))") { raw ->
            give(JsString.decode(raw)?.toIntOrNull() ?: 0)
        }
    }

    /** 这一轮的位图缩放系数。`FULL_PAGE` 会改它，所以每轮开头必须复位。 */
    private var bitmapScale = 1f

    private class RawShot(val bitmap: android.graphics.Bitmap?, val shot: ScrollFix.Shot)

    /** `draw()` 那条路。位图比视图小时先把画布缩放，视图的每个像素都落进位图。 */
    private fun oneShot(bw: Int, bh: Int, scale: Float, draw: (Canvas) -> Unit): RawShot {
        val (bmp, fail) = Drawer.drawToBitmap(bw, bh) { c ->
            if (scale != 1f) c.scale(scale, scale)
            draw(c)
        }
        return RawShot(bmp, measure(bmp, fail))
    }

    /** `capturePicture()` 那条路 —— 本体的兜底路径，v7 实测滚到底也是 0 红。 */
    @Suppress("DEPRECATION")
    private fun pictureShot(bw: Int, bh: Int, scale: Float): RawShot {
        val (bmp, fail) = PictureShot.toBitmap(bw, bh, { wv.capturePicture() }, scale)
        return RawShot(bmp, measure(bmp, fail))
    }

    private fun measure(bmp: android.graphics.Bitmap?, fail: String?): ScrollFix.Shot =
        if (bmp == null) ScrollFix.Shot(-1, -1, "-", fail ?: "没画出图")
        else ScrollFix.Shot(
            Ink.countGreen(bmp), Ink.countRed(bmp), Ink.sample(bmp), fail,
            Ink.countWhite(bmp), Ink.countPageBg(bmp), bmp.width * bmp.height
        )

    /** 跑一次合成页：切好挂载状态与尺寸 → 加载 → 等 → 取观测 → 画图 → 存 PNG。 */
    private fun synthetic(label: String, widthDp: Int, wantAttached: Boolean, done: (RunResult) -> Unit) {
        status.text = "$label 跑着…"
        if (wantAttached) attach(widthDp) else detach()
        sizeTo(widthDp, heightFor(widthDp))
        loaded = false; loadErr = null
        wv.loadDataWithBaseURL("https://probe.local/", ProbePage.html, "text/html", "utf-8", null)
        main.postDelayed({
            wv.evaluateJavascript(ProbePage.PROBE_JS) { raw ->
                done(finish(label, wantAttached, JsString.decodeJson(raw)))
            }
        }, 1300)
    }

    /** 跑一次真实站点：网络可能慢或不通，所以轮询等，超时就如实说"未验证"而不是算失败。 */
    private fun realSite(label: String, wantAttached: Boolean, url: String, done: (RunResult) -> Unit) {
        status.text = "$label 跑着…（可能要几秒）"
        if (wantAttached) attach(412) else detach()
        sizeTo(412, heightFor(412))
        loaded = false; loadErr = null; realSiteLoaded = false; realSiteErr = null
        if (url.isBlank()) { done(RunResult(label, wantAttached, false, "未填网址，跳过", null, -1, true, null)); return }
        wv.loadUrl(url)
        var waited = 0
        val poll = object : Runnable {
            override fun run() {
                if (realSiteLoaded || waited >= 40 || realSiteErr != null) {
                    wv.evaluateJavascript(ProbePage.REAL_JS) { raw ->
                        val obj = JsString.decodeJson(raw)
                        if (obj != null) {
                            val flat = JSONObject()
                            for (k in listOf("title", "head", "visState")) flat.put(k, obj.optString(k, ""))
                            for (k in listOf("innerW", "innerH", "len", "links", "imgs")) flat.put(k, obj.optInt(k, -1))
                            // 真实站点也画一次图
                            val (bmp, df) = Drawer.drawToBitmap(px(412), heightForPx(412)) { c -> wv.draw(c) }
                            val ink = bmp?.let { Ink.nonWhite(it) } ?: -1
                            bmp?.let { savePng(it, "$label.png") }
                            done(RunResult(label, wantAttached, realSiteLoaded, realSiteErr, flat, ink, true, df))
                        } else {
                            done(RunResult(label, wantAttached, realSiteLoaded, realSiteErr ?: "eval 无回值", null, -1, true, null))
                        }
                    }
                } else {
                    waited++; main.postDelayed(this, 250)
                }
            }
        }
        main.postDelayed(poll, 300)
    }

    private fun finish(label: String, isAttached: Boolean, obs: org.json.JSONObject?): RunResult {
        val (bmp, drawFail) = Drawer.drawToBitmap(px(412), heightForPx(412)) { c -> wv.draw(c) }
        val ink = bmp?.let { Ink.nonWhite(it) } ?: -1
        bmp?.let { savePng(it, "$label.png") }
        val wasLoaded = loaded
        val err = loadErr ?: attachError?.takeIf { isAttached }
        return RunResult(label, isAttached, wasLoaded, err, obs, ink, true, drawFail)
    }

    private fun savePng(bmp: android.graphics.Bitmap, name: String) {
        // 注意：use 里的 it 是输出流，不是 bitmap —— 要压缩得用外层那个 bmp
        runCatching { File(saveDir, name).outputStream().use { out -> bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out) } }
    }

    /** 保持 412:892 的比例。 */
    private fun heightFor(widthDp: Int): Int = (widthDp * 892) / 412
    private fun heightForPx(widthDp: Int): Int = px(heightFor(widthDp))

    private fun sizeTo(widthDp: Int, heightDp: Int) = sizeToPx(px(widthDp), px(heightDp))

    /** 直接按设备像素量。全高视图那条要的就是整页那么高，没法用 dp 表达。 */
    private fun sizeToPx(w: Int, h: Int) {
        wv.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY)
        )
        wv.layout(0, 0, w, h)
    }

    private fun attach(widthDp: Int) {
        if (attached === wv) { sizeTo(widthDp, heightFor(widthDp)); return }
        detach()
        attachError = null
        runCatching {
            val w = px(widthDp); val h = px(heightFor(widthDp))
            val p = WindowManager.LayoutParams(
                w, h,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply { x = -(w + 100); y = 0; gravity = Gravity.TOP or Gravity.START }
            wm.addView(wv, p)
            attached = wv
        }.onFailure { attachError = "B 组挂窗口失败: ${it.message}" }
    }

    private fun detach() {
        runCatching { attached?.let { wm.removeView(it) } }
        attached = null
    }

    private fun report(
        a1: RunResult, a2: RunResult, a3: RunResult,
        a4: RunResult, b1: RunResult, b2: RunResult,
        sa: ScrollSweep, sb: ScrollSweep, url: String
    ) {
        val rows = ProbeVerdict.rows(a1, b1, 412)
        val follows = ProbeVerdict.viewportFollowsView(a2, a3, 200, 600)
        val realOk = if (a4.obs == null || b2.obs == null) null else ProbeVerdict.realSiteComparable(a4, b2)
        val v = ProbeVerdict.conclusion(rows, follows, realOk)
        val pkg = runCatching { WebView.getCurrentWebViewPackage()?.versionName }.getOrNull()

        val L = StringBuilder()
        L.append("WebView ").append(pkg ?: "?").append("  ·  ").append(android.os.Build.MANUFACTURER)
            .append(' ').append(android.os.Build.MODEL).append("  Android ")
            .append(android.os.Build.VERSION.RELEASE).append("  density=").append(density).append('\n')
        // slowDraw 是进程级一次性开关 —— 本轮开/关必须写在报告头，
        // 否则"开着跑一轮、关着跑一轮"两张截图放一起就分不清了
        L.append("slowDraw静态开关：").append(if (slowDrawOn) "开（建第一个视图前调过）" else "关").append('\n')
        // 视图按 px 给（412dp × 3 = 1236px），但页面报的是 CSS px（有 meta 时 1:1 就是 dp）。
        // 下面两个数不一样是对的，写出来免得看的人以为对不上。
        L.append("视图给的是 ").append(px(412)).append('×').append(px(heightFor(412)))
            .append(" 设备像素；页面报的 innerW 是 CSS 像素（= dp）\n\n")
        L.append(String.format("%-26s %-11s %-11s%n", "项目", "A 不挂窗口", "B 现有"))
        L.append("-".repeat(52)).append('\n')
        for (r in rows) L.append(String.format("%-26s %-11s %-11s%n", r.name, mark(r.a), mark(r.b)))
        L.append("等价 ").append(rows.count { it.same }).append('/').append(rows.size).append(" 项\n")

        L.append("\n【第二部分 · 双尺寸（决定性）】\n")
        L.append("  给 200dp → innerW=").append(nOf(a2)).append('\n')
        L.append("  给 600dp → innerW=").append(nOf(a3)).append('\n')
        L.append("  视口跟着视图走 = ").append(yn(follows)).append('\n')

        L.append("\n【第三部分 · 真实站点】").append(if (url.isBlank()) "（未填网址，已跳过）" else url).append('\n')
        L.append(realLine("A", a4)).append('\n')
        L.append(realLine("B", b2)).append('\n')
        L.append("两组一致 = ").append(if (realOk == null) "未验证" else yn(realOk)).append('\n')

        L.append("\n【第四部分 · 滚动截图 · 十三种做法逐个量】\n")
        L.append("  长页面**顶部铺纯绿、底部铺纯红**。每个做法只画一张图，\n")
        L.append("  从同一张图里同时数绿和红；两条截图路（draw / capturePicture）各量一次。\n")
        L.append("  「绿X 红X」= 顶部/底部的色块像素数，✓ = 画出来了，0 = 没有，× = 那路没画成图。\n")
        L.append("  ≥").append(ScrollFix.MIN_PIXELS).append(" 算画出来。非白像素在这页恒为满值（底色\n")
        L.append("  #e9e9e9 本身就算），所以不作数。v5~v7 那 22 次量的红像素全是 0。\n")
        L.append("  v9 加 4 个：滚到99%留1px / 抖着画 / 改字逼重绘 / 滚完等20秒 ——\n")
        L.append("  之前 11 种全是逼它出帧，这 4 种是让它觉得有帧可出。\n")
        L.append("  v10 加 2 个做法 + 1 个静态开关：预光栅开（B组=官方说的那档）/ \n")
        L.append("  等visual回调（不抢跑）/ slowDraw（见报告头开/关）。\n")
        L.append("  v11 加 1 个：虚拟屏实渲 —— VirtualDisplay+Presentation+PixelCopy，\n")
        L.append("  官方文档指的 snapshot 路。不需要悬浮窗权限。\n")
        L.append("  v12 加 1 个：插元素逼重排 —— 滚到底后插100px元素，布局变了看 raster 跟不跟。\n\n")
        listOf("A 不挂窗口" to sa, "B 现有方案" to sb).forEach { (title, sw) ->
            L.append("  ").append(title).append('\n')
            L.append(String.format("    %-16s %-12s %-12s %-9s%n", "做法", "draw 绿/红", "pic 绿/红", "视图高"))
            for (fx in ScrollFix.Variant.entries) {
                val r = sw.results.firstOrNull { it.variant == fx }
                L.append(
                    String.format(
                        "    %-16s %-12s %-12s %-9s%n",
                        fx.label,
                        pair(r?.spot?.draw),
                        pair(r?.spot?.picture),
                        if (r == null) "—" else "${r.spot.viewH}px"
                    )
                )
                r?.spot?.note?.takeIf { it.isNotEmpty() }?.let {
                    L.append("      ↳ ").append(it).append('\n')
                }
            }
            L.append('\n')
        }
        L.append("  A: ").append(ScrollFix.summary(sa.results)).append('\n')
        L.append("  B: ").append(ScrollFix.summary(sb.results)).append('\n')
        // 测法本身的可信度也得报出来：eval 没回值、或者 DOM 说红块压根不在视口里
        listOf("A" to sa, "B" to sb).forEach { (g, sw) ->
            sw.results.forEach { r ->
                val s = r.spot
                val bad = buildList {
                    if (!s.evalOk) add("eval 无回值")
                    if (!s.blockVisible) add("DOM 说红块不在视口里")
                    if (s.draw.fail != null) add("draw 失败：${s.draw.fail}")
                    if (s.picture.fail != null) add("capturePicture 失败：${s.picture.fail}")
                    if (r.variant.how == ScrollFix.How.FULL_PAGE && !s.viewCoversPage) {
                        add("视图没撑到整页（挂窗口时窗口管理器只按窗口尺寸布局，这条在 B 组本就无效）")
                    }
                }
                if (bad.isNotEmpty()) {
                    L.append("  ⚠ ").append(g).append("·").append(r.variant.label).append("：")
                        .append(bad.joinToString("；")).append("（这一行不作数）\n")
                }
                // 有步骤没跑完 → 那一行的结论只覆盖跑完的那部分，单独点出来
                if (r.stalls.isNotEmpty()) {
                    L.append("  ⏱ ").append(g).append("·").append(r.variant.label).append("：\n")
                    L.append(r.stalls)
                }
            }
        }

        // v9 三色分解：只列底部那张 draw 图。白 = 画布底（连底色都没回放），
        // 底色 = #e9e9e9 附近（内容层单独掉了），其他 = 四个桶都装不下。
        // DOM= 那列是 elementFromPoint 报的 —— 是 #tail 说明 DOM 层正常。
        L.append("\n【三色分解 · 底部那张 draw 图】\n")
        val triHow = listOf(
            ScrollFix.Variant.BASELINE, ScrollFix.Variant.P99,
            ScrollFix.Variant.JITTER, ScrollFix.Variant.DIRTY, ScrollFix.Variant.WAIT20,
            ScrollFix.Variant.PRERASTER, ScrollFix.Variant.VISUAL, ScrollFix.Variant.VDISPLAY,
            ScrollFix.Variant.LDIRTY
        )
        listOf("A" to sa, "B" to sb).forEach { (g, sw) ->
            for (fx in triHow) {
                val r = sw.results.firstOrNull { it.variant == fx } ?: continue
                L.append("  ").append(g).append("·").append(fx.label).append("：")
                    .append(tri(r.spot))
                // 虚拟屏那行不是 draw 出来的 —— 不标的话会和 draw 路混在一起
                if (fx.how == ScrollFix.How.VDISPLAY) L.append("（PixelCopy）")
                L.append('\n')
            }
        }

        L.append("\n【结论】\n").append(v.text).append("\n\n—— 合成页原始观测 ——\n")
        L.append(inkLine("A", a1)).append('\n')
        L.append(inkLine("B", b1)).append('\n')
        L.append("A: ").append(a1.raw()).append('\n')
        L.append("B: ").append(b1.raw()).append('\n')
        L.append("\n—— 逐项 ——\n")
        for (r in rows) {
            L.append("· ").append(r.name).append('\n')
            if (r.aNote.isNotEmpty()) L.append("    A: ").append(r.aNote).append('\n')
            if (r.bNote.isNotEmpty()) L.append("    B: ").append(r.bNote).append('\n')
        }
        attachError?.let { L.append("\n⚠ ").append(it).append('\n') }
        L.append("\n图: ").append(saveDir.absolutePath)
        out.text = L.toString()
        status.text = "跑完了，把这屏截图发我。"
        runBtn.isEnabled = true
    }

    private fun nOf(r: RunResult) = r.obs?.optInt("innerW", -1) ?: -1

    /**
     * 报告里一条截图路那一格：`绿✓ 红0`。
     *
     * 绿和红是**两个独立判据** —— `capturePicture()` 给的是整页，两种颜色会同时
     * 出现在同一张图里，所以「哪个颜色达标就写哪个」，绝不能合成一个"画出来了"。
     * v7 就是合成一个，结果把「只画出了顶部」误报成「底部也画出来了」。
     */
    private fun pair(s: ScrollFix.Shot?): String {
        if (s == null) return "—"
        if (s.fail != null) return "×"
        return "绿${flag(s.hasGreen, s.green)} 红${flag(s.hasRed, s.red)}"
    }

    private fun flag(ok: Boolean, n: Int): String =
        if (n < 0) "×" else if (ok) "✓" else "0"

    /**
     * 三色分解那一行：`白0 底色330万 其他0 DOM=#tail`。
     *
     * 只看 draw 那张（底部位置）。pic 那张是整页，顶部绿块也在里面，
     * 三色分不开 —— 别拿它来分。
     */
    private fun tri(s: ScrollFix.Spot): String {
        val d = s.draw
        if (d.fail != null) return "×（${d.fail}）"
        val o = d.others()
        val el = s.elAt.ifEmpty { "?" }
        // 四个桶互不重叠，加起来不可能超总数。超了就是计数逻辑坏了 ——
        // v11 那次"底色369万 > 总数330万"如果再出现，这里会标出来，而不是压成"其他0"
        val parts = listOf(d.white, d.pageBg, d.green, d.red)
        val warn = if (parts.any { it < 0 }) "" else if (parts.sum() > d.total) "⚠数对不上" else ""
        return "白${kb(d.white)} 底色${kb(d.pageBg)} 其他${if (o < 0) "×" else kb(o)} DOM=$el$warn"
    }

    /** 大数压成「330万」，小数字原样。小屏放得下。 */
    private fun kb(n: Int): String = when {
        n < 0 -> "×"
        n >= 10_000 -> "${n / 10_000}万"
        else -> n.toString()
    }

    /**
     * "非白像素"这行的读法：图像是**设备像素**尺寸，页面底色大面积是 `#eee`(238)，
     * 低于 245 的阈值，所以会被算进去 —— 数字看着大是正常的，占比才说明问题。
     */
    private fun inkLine(tag: String, r: RunResult): String {
        val total = px(412).toLong() * px(heightFor(412))
        val pct = if (r.drawNonWhitePixels > 0 && total > 0) (r.drawNonWhitePixels * 100 / total) else 0
        return "$tag: 非白像素=${r.drawNonWhitePixels} / ${total}（${pct}%）"
    }

    /** 没写协议就补上，否则 loadUrl 认不出来。 */
    private fun normalizeUrl(raw: String): String {
        val s = raw.trim()
        if (s.isEmpty()) return ""
        return if (s.contains("://")) s else "https://$s"
    }
    private fun yn(b: Boolean) = if (b) "是" else "否"
    private fun mark(b: Boolean) = if (b) "✓" else "✗"
    private fun realLine(tag: String, r: RunResult): String {
        val o = r.obs
        if (o == null) return "$tag: 无观测（${r.loadError ?: "未知"}）"
        // 挑不出真实内容时明说：这测的是 WebView 自己的错误页，不是真实站点
        val err = if (ProbePage.looksLikeErrorPage(o)) "  ⚠这是 WebView 的错误页，不是真实内容" else ""
        // v4：即使渲染出了错误页也要把错误码写上 —— 不然只剩一句"打不开"，
        // 分不清是 DNS(-2)、超时(-1)、被拒(-10)还是压根没网
        val code = r.loadError?.let { "  错误码=$it" } ?: ""
        return "$tag: title=\"${o.optString("title")}\" h1=\"${o.optString("h1")}\" " +
            "正文=${o.optInt("len")}字 链接=${o.optInt("links")} 图=${o.optInt("imgs")} " +
            "innerW=${o.optInt("innerW")} 历史=${o.optInt("hist")} 可见=${o.optString("visState")}$err$code"
    }

    private companion object { const val KEY_URL = "url"; const val KEY_SLOW = "slowdraw" }

    override fun onDestroy() {
        detach()
        releaseVirtual()
        runCatching { wv.destroy() }
        super.onDestroy()
    }
}

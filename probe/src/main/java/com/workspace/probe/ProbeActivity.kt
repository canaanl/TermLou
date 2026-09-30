package com.workspace.probe

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
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
        saveDir = File(getExternalFilesDir(null), "shots").apply { mkdirs() }
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
                    realSite("A 真实站点", wantAttached = false, url = url) { a4 ->
                        status.text = "B 组 · 挂窗口（现有方案）…"
                        synthetic("B 合成页 412dp", 412, wantAttached = true) { b1 ->
                            realSite("B 真实站点", wantAttached = true, url = url) { b2 ->
                                report(a1, a2, a3, a4, b1, b2, url)
                            }
                        }
                    }
                }
            }
        }
    }

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

    private fun sizeTo(widthDp: Int, heightDp: Int) {
        val w = px(widthDp)
        val h = px(heightDp)
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
        a4: RunResult, b1: RunResult, b2: RunResult, url: String
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

    private companion object { const val KEY_URL = "url" }

    override fun onDestroy() {
        detach()
        runCatching { wv.destroy() }
        super.onDestroy()
    }
}

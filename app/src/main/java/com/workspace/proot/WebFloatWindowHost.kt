package com.workspace.proot

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView

/**
 * 浏览器悬浮窗（5.9.27）。
 *
 * ## 它取代了什么
 *
 * 5.9.7–5.9.26 是"无头"：WebView 不挂任何窗口，只手工 `measure()`+`layout()`。
 * 那条路能用，但它有两个硬伤：
 *
 * 1. **不产帧** —— 没有 surface 的视图，滚出去的内容压根不会被光栅化。
 *    探针扫了 11 种"踢一帧"的办法、22 次全灭。整页截图只能靠"把视图撑到整页高"
 *    绕过去，代价是长页面重新排版一次。
 * 2. **什么都看不见** —— 用户完全不知道 agent 在干什么。
 *
 * 挂了真窗口之后这两条一起没了：合成器一直在产帧，滚到哪里渲染到哪里；
 * 用户也能看着 agent 操作。
 *
 * ## 视口不许动
 *
 * 窗口宽度只有屏宽的 1/3，但**WebView 仍按 agent 视口（412×892dp）排版**，
 * 只是缩着塞进小窗。缩放只发生在显示这一层（[WebFloatWindow.scaleFactors]）。
 *
 * 要是直接把 WebView 量成窗口大小，视口就变成 160dp 宽 —— 多数手机站会重排成
 * 完全不同的版式，agent 学过的选择器与坐标全部失效。
 *
 * ## 触摸：整窗拖动，一个 touch 都不给 WebView
 *
 * 用户要求"点小窗唯一的行为就是拖动"。做法是外层 [DragFrameLayout] 在
 * `onInterceptTouchEvent` 里**一律返回 true** —— WebView 收不到任何事件，
 * 点不动它、也滚不了它。这比 `FLAG_NOT_TOUCHABLE` 强：那个 flag 会让整个窗口
 * 连拖动都收不到。
 *
 * ## 与系统小窗共存
 *
 * Android 12 起，App 进入分屏/自由窗口时系统会藏掉自己的 `TYPE_APPLICATION_OVERLAY`。
 * 想共存得显式声明，那是个 `@hide` 字段（公开 SDK 里没有），只能反射设置 ——
 * 而**它到底存不存在、语义是不是这样，必须真机验**，所以见 [multiWindowOptOut]。
 */
class WebFloatWindowHost(
    private val service: WebAutomationService,
    private val wm: WindowManager
) {

    companion object {
        private const val TAG = "TermLouWebFloat"

        /** 空窗时的提示文字。 */
        private const val HINT_TEXT = "等待 agent 打开页面…"
    }

    /** 多窗口共存那行反射的**实测结果** —— `diag` 原样报出来，不猜。 */
    @Volatile var multiWindowReport: String = "还没建窗"
        private set

    /**
     * 最近一次建窗失败的原因。
     *
     * `open` 失败时要**用它**，不能报"timeout" —— 那会让 agent 以为是主线程忙，
     * 于是不停重试，而没权限这件事重试一万次也是这个结果。
     */
    @Volatile var lastError: String? = null
        private set

    /** 拖动时回调新的左上角坐标（主线程）。 */
    private var onMoved: ((Int, Int) -> Unit)? = null

    private var root: DragFrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var hint: TextView? = null

    /** 当前挂在窗口里的 WebView —— 拆的时候要用它摘下来。 */
    private var attached: WebView? = null

    @Volatile private var attachedView = false

    // ---------- 建 / 拆 ----------

    /**
     * 建窗并显示。**幂等** —— 已经在就不用再建。
     *
     * @param onMoved 拖动回调
     * @return 失败时给出人话（没权限 / 系统不让加窗口）
     */
    fun show(onMoved: (Int, Int) -> Unit): String? {
        lastError = null
        if (root != null) {
            this.onMoved = onMoved
            return null
        }
        val dm = service.resources.displayMetrics
        val (winW, winH) = WebFloatWindow.windowSize(dm.widthPixels, dm.heightPixels)
        val (initX, initY) = WebFloatWindow.defaultPosition(dm.widthPixels, winW)

        val p = WindowManager.LayoutParams(
            winW, winH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // **不给 NOT_TOUCHABLE** —— 那样连拖动都收不到。
            // NOT_FOCUSABLE 是为了不吃输入法焦点（否则 agent 的 type 会失灵）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initX
            y = initY
        }
        multiWindowOptOut(p)

        val host = DragFrameLayout(service).apply {
            layoutParams = ViewGroup.LayoutParams(winW, winH)
            clipChildren = false
            setBackgroundColor(Color.TRANSPARENT)
            onDrag = { dx, dy -> moveBy(dx, dy) }
        }
        host.addView(hintView(winW, winH))

        return try {
            wm.addView(host, p)
            root = host
            params = p
            this.onMoved = onMoved
            null
        } catch (e: Exception) {
            // 权限被撤销、系统拦了 —— 都走到这里。**如实说**，不假装成功
            Log.w(TAG, "addView failed", e)
            val msg = if (!android.provider.Settings.canDrawOverlays(service)) {
                "悬浮窗权限未授予：请在系统设置里给 TermLou 打开「显示在其他应用上层」"
            } else {
                "悬浮窗建不起来：${e.javaClass.simpleName}"
            }
            lastError = msg
            msg
        }
    }

    /**
     * 拆窗。
     *
     * `removeViewImmediate` 而不是 `removeView`：服务被杀/用户关开关时，
     * 异步移除会把调用方的视图树搞坏 —— 那个 View 可能已经被别处拿走了。
     */
    fun hide() {
        attached = null
        attachedView = false
        onMoved = null
        val host = root
        root = null
        params = null
        hint = null
        if (host == null) return
        runCatching { wm.removeViewImmediate(host) }
            .onFailure { Log.w(TAG, "removeViewImmediate failed", it) }
    }

    // ---------- 内容 ----------

    /**
     * 把 WebView 装进窗口。
     *
     * 空窗提示收到背景里 —— 提示只是"还没打开页面"，有页面了就该让位。
     */
    fun attachWebView(wv: WebView, viewW: Int, viewH: Int, winW: Int, winH: Int) {
        val host = root ?: return
        val (sx, sy) = WebFloatWindow.scaleFactors(winW, winH, viewW, viewH)
        onMain {
            if (host !== root) return@onMain
            attached?.let { old -> runCatching { host.removeView(old) } }
            // 视口尺寸**不许跟着窗口缩** —— 缩放由 scaleX/scaleY 负责，
            // 布局尺寸就是 agent 视口，页面版式与坐标因此一个像素不变。
            wv.layoutParams = FrameLayout.LayoutParams(viewW, viewH)
            wv.pivotX = 0f
            wv.pivotY = 0f
            wv.scaleX = sx
            wv.scaleY = sy
            host.addView(wv)
            attached = wv
            attachedView = true
            hint?.visibility = View.GONE
        }
    }

    /** 把 WebView 从窗口里摘下来（页面会话销毁时）。窗口留着，空窗提示回来。 */
    fun detachWebView(wv: WebView) {
        val host = root
        attachedView = false
        onMain {
            runCatching { host?.removeView(wv) }
            if (attached === wv) attached = null
            hint?.visibility = if (host != null) View.VISIBLE else View.GONE
        }
    }

    /** 窗口当前在不在（`diag` 报"用户看得见一个窗吗"的依据）。 */
    val isShown: Boolean get() = root != null

    /** 缩放因子 —— 截图那边要知道，得按**未缩放**的视口出图。 */
    fun currentScale(): Pair<Float, Float> {
        val wv = attached ?: return 1f to 1f
        return wv.scaleX to wv.scaleY
    }

    /**
     * 截图期间把缩放**临时归 1**，画完再恢复。
     *
     * 不这么做的话 `draw()` 出来的是缩过的图 —— 而 agent 要的是视口原尺寸。
     */
    fun withoutScale(block: () -> Unit) {
        val wv = attached
        val sx = wv?.scaleX
        val sy = wv?.scaleY
        onMain {
            if (sx != null) wv!!.scaleX = 1f
            if (sy != null) wv!!.scaleY = 1f
            try {
                block()
            } finally {
                if (sx != null) wv!!.scaleX = sx
                if (sy != null) wv!!.scaleY = sy
            }
        }
    }

    // ---------- 拖动 ----------

    private fun moveBy(dx: Int, dy: Int) {
        val p = params ?: return
        val dm = service.resources.displayMetrics
        val (nx, ny) = WebFloatWindow.clampToScreen(
            p.x + dx, p.y + dy, p.width, p.height, dm.widthPixels, dm.heightPixels
        )
        if (nx == p.x && ny == p.y) return
        p.x = nx
        p.y = ny
        runCatching { wm.updateViewLayout(root, p) }
            .onFailure { Log.w(TAG, "updateViewLayout failed", it) }
        onMoved?.invoke(nx, ny)
    }

    // ---------- 空窗提示 ----------

    private fun hintView(winW: Int, winH: Int): TextView {
        val tv = TextView(service)
        tv.text = HINT_TEXT
        tv.setTextColor(Color.parseColor("#8A8A8A"))
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        tv.gravity = Gravity.CENTER
        tv.setBackgroundColor(Color.parseColor("#F2F2F2"))
        tv.layoutParams = FrameLayout.LayoutParams(winW, winH)
        hint = tv
        return tv
    }

    private fun onMain(block: () -> Unit) {
        val h = android.os.Handler(service.mainLooper)
        if (android.os.Looper.myLooper() == service.mainLooper) block() else h.post(block)
    }

    /**
     * 声明"我进系统小窗也别藏我的悬浮窗"。
     *
     * ## 为什么用反射
     *
     * 那个开关是 `WindowManager.LayoutParams` 上的 `@hide` 字段
     * （公开 SDK 里查不到，`javap` 确认过 android-34 没有）。
     *
     * ## 为什么返回值要如实报出来
     *
     * **我不能靠记忆断言它的字段名与语义** —— 这轮已经猜错太多次。
     * 所以：反射不到就返回 null（照常工作，系统爱藏就藏），
     * 反射到了就报出来，让 `diag` 里有据可查。真机跑一次就知道对不对。
     */
    private fun multiWindowOptOut(p: WindowManager.LayoutParams) {
        multiWindowReport = if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            "android 11 及以下无此规则"
        } else {
            runCatching {
                val f = WindowManager.LayoutParams::class.java.getField("hideOverlayWindows")
                f.setBoolean(p, false)
                "hideOverlayWindows=false"
            }.getOrElse { "hideOverlayWindows 字段不存在（${it.javaClass.simpleName}）" }
        }
    }
}

/**
 * 整窗拖动的容器。
 *
 * ## 为什么 `onInterceptTouchEvent` 一律返回 true
 *
 * 用户要求"点小窗唯一的行为就是拖动"。返回 true 意味着**所有** touch 都在这里
 * 被吃掉，WebView 一个事件都收不到 —— 点不动它，也滚不了它。
 *
 * 只在 `ACTION_DOWN` 拦是不够的：那之后 WebView 已经拿到手势了，
 * 一个滚动手势会把页面滚走。**从 DOWN 就全拦**。
 */
@SuppressLint("ClickableViewAccessibility")
private class DragFrameLayout(context: Context) : FrameLayout(context) {

    var onDrag: ((dx: Int, dy: Int) -> Unit)? = null

    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = ev.rawX
                lastY = ev.rawY
                dragging = false
                // 从 DOWN 就吃掉 —— 子视图（WebView）从此收不到任何事件
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = (ev.rawX - lastX).toInt()
                val dy = (ev.rawY - lastY).toInt()
                if (dx != 0 || dy != 0) {
                    lastX = ev.rawX
                    lastY = ev.rawY
                    dragging = true
                    onDrag?.invoke(dx, dy)
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return true
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean = true
}
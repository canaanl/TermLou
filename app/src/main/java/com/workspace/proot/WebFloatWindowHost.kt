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
 * 浏览器悬浮窗（5.9.27 建立，5.9.34 重写）。
 *
 * ## 结构：**没有缩放**，网页与窗口一个像素对一个像素
 *
 * ```
 *  WindowManager.LayoutParams   1/3屏宽 · 屏幕比例 · 右上角
 *  └ DragFrameLayout           dispatchTouchEvent 吃掉全部 touch（整窗拖动）
 *     ├ hint TextView          空窗提示
 *     └ WebView                layout = 窗口尺寸（480×1056）
 * ```
 *
 * ## 为什么缩放整个删掉了（5.9.34）
 *
 * 5.9.27–5.9.33 一直有缩放（先挂在 WebView 上，后改挂容器上）。**那条路在真机上出不来图**：
 *
 * 安卓问网页"你要画多大"时，量的是**它在屏幕上实际占多大**。缩放一压，
 * 它就只画 480×1056 —— 1236×2676 里剩下 87% **从来没被画过**。
 * 不是画了看不见，是压根没画。于是整页截图永远停在第 2 屏：
 *
 * ```
 * 真机 5.9.33：screen 2: nothing rendered (blank)
 * 真机 5.9.32：第 2 屏大块空白 / 错位
 * 真机 5.9.31：第 3 屏起画面不跟随滚动
 * ```
 *
 * 改动只有一处，但它是根：**视口尺寸 = 窗口尺寸**（[WebFloatWindow.windowSize]）。
 * 缩放层（`ScaleFrameLayout`）、缩放因子、pivot 全都不存在了 ——
 * 于是安卓量到的、你眼睛看到的、我们要拍的，是同一块东西。
 *
 * ## 触摸：整窗拖动，一个 touch 都不给 WebView
 *
 * [DragFrameLayout] 在 `dispatchTouchEvent`（**事件分发的入口**）里就把事件吃掉，
 * 子视图连分发都进不去 —— WebView 点不动、也滚不了。
 *
 * 5.9.27 第一版用 `onInterceptTouchEvent` + `onTouchEvent` 那套，**真机上拖不动**。
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

    /** 收到过几次 DOWN / MOVE。**拖不动时先看这两个数** —— 见类注释。 */
    @Volatile private var touchDowns = 0
    @Volatile private var touchMoves = 0

    /** 最近一次 `updateViewLayout` 失败的原因；`null` = 没失败过。 */
    @Volatile private var layoutError: String? = null

    /** 拖动时回调新的左上角坐标（主线程）。 */
    private var onMoved: ((Int, Int) -> Unit)? = null

    private var root: DragFrameLayout? = null

    /** 缩放层已删（5.9.34）：WebView 直接挂在 [DragFrameLayout] 下，尺寸 = 窗口尺寸。 */
    private var params: WindowManager.LayoutParams? = null
    private var hint: TextView? = null

    /** 当前挂在窗口里的 WebView —— 拆的时候要用它摘下来。 */
    private var attached: WebView? = null

    /** 拖动锚点：按下那一刻窗口在哪。之后按"锚点 + 手指位移"算，不累加。 */
    private var anchorX = 0
    private var anchorY = 0

    /** 拖动诊断：`down=N move=M` 外加落地失败的原因。 */
    val touchReport: String
        get() = "down=$touchDowns move=$touchMoves" +
            (layoutError?.let { " layout_error=$it" } ?: "")

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
            // ⚠ **不给 NOT_TOUCHABLE** —— 那样连拖动都收不到。
            // NOT_FOCUSABLE 是为了不吃输入法焦点（否则 agent 的 type 会失灵）。
            // NOT_TOUCH_MODAL 必须有：否则这个窗会吃掉**全屏**的触摸，
            // 底下的 app 就一点都点不动了。
            //
            // 5.9.28：**去掉 LAYOUT_NO_LIMITS**。窗口只有屏宽 1/3，本来就不超屏，
            // 这个 flag 没用；而它会让窗口无视屏幕边界与系统栏 inset ——
            // 压在状态栏上的那一块收不到触摸，正是"拖不动"的嫌疑之一。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
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
        }
        // 接线：按下记锚点，移动按"锚点 + 位移"落位
        host.onGrab = {
            val cur = params
            anchorX = cur?.x ?: 0
            anchorY = cur?.y ?: 0
            touchDowns++
        }
        host.onDragDelta = { dx, dy ->
            touchMoves++
            moveBy(anchorX + dx, anchorY + dy)
        }
        // 5.9.34：**没有缩放层了。** WebView 直接挂在 host 下，尺寸 = 窗口尺寸。
        // 安卓量到的"网页占多大"从此等于屏幕上真实那块 —— 见类注释。
        host.addView(hintView(winW, winH))

        return try {
            wm.addView(host, p)
            root = host
            params = p
            this.onMoved = onMoved
            layoutError = null
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
        onMoved = null
        val host = root
        root = null
        params = null
        hint = null
        touchDowns = 0
        touchMoves = 0
        if (host == null) return
        runCatching { wm.removeViewImmediate(host) }
            .onFailure { Log.w(TAG, "removeViewImmediate failed", it) }
    }

    // ---------- 内容 ----------

    /**
     * 把 WebView 装进窗口。**尺寸就是窗口尺寸**，一个像素不多一个像素不少。
     *
     * 5.9.34 起这个函数里**不再有任何缩放**。此前它要算一遍
     * `scaleFactors(winW, winH, viewW, viewH)` 并把 1236×2676 压进 480×1056 ——
     * 而安卓正是照着这个被压过的大小决定"要画多少"，于是 87% 的内容从没被画过。
     *
     * @param viewW/viewH 视口（设备像素）= 窗口尺寸
     */
    fun attachWebView(wv: WebView, viewW: Int, viewH: Int, winW: Int, winH: Int) {
        val host = root ?: return
        onMain {
            if (host !== root) return@onMain
            attached?.let { old -> runCatching { host.removeView(old) } }
            wv.layoutParams = FrameLayout.LayoutParams(viewW, viewH)
            host.addView(wv)
            attached = wv
            hint?.visibility = View.GONE
        }
    }

    /** 把 WebView 从窗口里摘下来（页面会话销毁时）。窗口留着，空窗提示回来。 */
    fun detachWebView(wv: WebView) {
        onMain {
            runCatching { root?.removeView(wv) }
            if (attached === wv) attached = null
            hint?.visibility = if (root != null) View.VISIBLE else View.GONE
        }
    }

    /** 窗口当前在不在（`diag` 报"用户看得见一个窗吗"的依据）。 */
    val isShown: Boolean get() = root != null

    // ---------- 拖动 ----------

    /**
     * 把窗口落到指定左上角。
     *
     * ## 为什么是"绝对落位"而不是"加一段增量"
     *
     * 5.9.27 第一版是 `p.x += dx`，而 `dx` 来自 `(rawX - lastX).toInt()`。
     * 在 density 3.0 的屏上手指稍慢一点，一次 MOVE 的位移截断成 Int 就是 **0** ——
     * 于是每次都判定成"没动"，表现就是按住拖不动。
     *
     * 现在改成：按下时记下窗口锚点，移动时按 `锚点 + 手指位移` 一次算出落点。
     * 位移全程用 `Float`，只在最后取整，不累加、不截断中间值。
     */
    private fun moveBy(targetX: Float, targetY: Float) {
        val p = params ?: return
        val host = root ?: return
        val dm = service.resources.displayMetrics
        val (nx, ny) = WebFloatWindow.clampToScreen(
            targetX.toInt(), targetY.toInt(), p.width, p.height,
            dm.widthPixels, dm.heightPixels
        )
        if (nx == p.x && ny == p.y) return
        p.x = nx
        p.y = ny
        // ⚠ **不许静默吞掉失败**。第一版用 `runCatching` 包着，失败就吞 ——
        // 界面表现是"按住没反应"，一句日志都不打，只能靠猜。
        try {
            wm.updateViewLayout(host, p)
            layoutError = null
        } catch (e: Exception) {
            val msg = "${e.javaClass.simpleName}: ${e.message}"
            layoutError = msg
            Log.w(TAG, "updateViewLayout failed", e)
        }
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
     * 所以反射不到就照常工作，反射到了就报出来，让 `diag` 里有据可查。
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
 * ## 为什么拦在 `dispatchTouchEvent` 而不是 `onInterceptTouchEvent`
 *
 * `onInterceptTouchEvent` 是"要不要把事件从子视图手里抢回来"的钩子，
 * 抢回来之后还得靠 `onTouchEvent` 接住 —— 两个环节，哪一个不配合就整个失效。
 *
 * `dispatchTouchEvent` 是**事件分发的入口**：在这里返回 true，子视图**连分发都进不去**。
 * 用户要求"点小窗唯一的行为就是拖动"，那就把这件事钉死在一个地方。
 *
 * ## 为什么位移用 Float 往上传
 *
 * 第一版在 `onInterceptTouchEvent` 里写的是 `(rawX - lastX).toInt()` ——
 * density 3.0 的屏上，一次 MOVE 位移不足 1px 时截断成 0，
 * 累加下来窗口几乎不动。这里改成 Float，由上层一次算完落点。
 */
@SuppressLint("ClickableViewAccessibility")
private class DragFrameLayout(context: Context) : FrameLayout(context) {

    /** 按下（用来记锚点）。 */
    var onGrab: (() -> Unit)? = null

    /** 移动：位移是**相对按下那一下**的，单位设备像素。 */
    var onDragDelta: ((dx: Float, dy: Float) -> Unit)? = null

    private var downX = 0f
    private var downY = 0f

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 不调 super —— 一律自己吃掉，子视图（WebView）收不到任何事件
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                onGrab?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                onDragDelta?.invoke(ev.rawX - downX, ev.rawY - downY)
            }
            // UP / CANCEL 什么都不用做：这里只负责拖动，窗口位置已经是最终值
            else -> Unit
        }
        return true
    }
}
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
 * 浏览器悬浮窗（5.9.27 重构于 5.9.31）。
 *
 * ## 结构：缩放在**容器**上，WebView 永远是 1:1
 *
 * ```
 *  WindowManager.LayoutParams   1/3屏宽 · 屏幕比例 · 右上角
 *  └ DragFrameLayout           dispatchTouchEvent 吃掉全部 touch（整窗拖动）
 *     └ ScaleFrameLayout       scaleX = 0.39   ← 显示缩放挂在**这里**
 *        ├ hint TextView       空窗提示
 *        └ WebView             layout 1236×2676 · scale 1  ← 常驻，永不改
 * ```
 *
 * ## 为什么缩放必须在容器上（这是 5.9.27–5.9.30 一直错的结构）
 *
 * 之前缩放挂在 **WebView 自己**身上（`wv.scaleX = 0.39`）。后果有两样：
 *
 * 1. **截图绕不开它** —— 为了拍 1:1 的图，只能"临时把 scale 归 1、画完在 finally 还原"。
 *    这是事后补救，而且**每屏来回切两次视图变换**，每一次都在合成器管线上插一脚。
 * 2. **它自己就捣乱出帧** —— 真机症状：整页截图前两屏正常，**第 3 屏起画面不跟随滚动**，
 *    两屏像素相同。反复改视图变换是首要嫌疑。
 *
 * 缩放移到容器之后：
 *
 * - WebView 的 scale 是**常量 1**，没有任何代码会去改它；
 * - `wv.draw(canvas)` **看不见祖先的变换** —— 祖先变换由父视图的 `drawChild` 施加，
 *   不在视图自己的 `draw()` 里。所以截图 1:1 **结构上就成立**，不是"临时归 1"那种补救；
 * - 每屏两次视图变换 → **0 次**。
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

    /** **显示缩放挂在这层**（5.9.31）。WebView 自己的 scale 恒为 1。 */
    private var scaledLayer: ScaleFrameLayout? = null
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
        // ⚠ **缩放容器夹在窗口与 WebView 之间**（5.9.31 结构改动）。
        // 缩放加在它身上，WebView 的 scale 就永远是 1 ——
        // 截图 1:1 于是结构上成立，而不是"截图时临时归 1、画完还原"。
        // 它的大小是窗口尺寸（装得下缩放后的视口）；`clipChildren=false`
        // 让缩放后的视口不被自己这一层裁掉。
        val scaled = ScaleFrameLayout(service).apply {
            layoutParams = ViewGroup.LayoutParams(winW, winH)
            clipChildren = false
        }
        host.addView(scaled, ViewGroup.LayoutParams(winW, winH))
        scaledLayer = scaled
        // 空窗提示**放在缩放容器之外**（它要按窗口尺寸铺满，不该被缩成一小块）
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
        scaledLayer = null
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
     * 把 WebView 装进窗口。
     *
     * ## 这里**不许**碰 WebView 的 scale
     *
     * 显示缩放加在 [ScaleFrameLayout]（容器）上。WebView 的 `scaleX/scaleY`
     * 是**常量 1**，全工程没有任何代码会去改它 —— 改了就会把"截图 1:1"
     * 这件结构上成立的事，退回到"临时归 1 再还原"的补救状态（5.9.27–5.9.30 的错）。
     *
     * @param viewW/viewH agent 视口（设备像素），**原样**作为布局尺寸
     */
    fun attachWebView(wv: WebView, viewW: Int, viewH: Int, winW: Int, winH: Int) {
        val host = root ?: return
        val layer = scaledLayer ?: return
        val (sx, sy) = WebFloatWindow.scaleFactors(winW, winH, viewW, viewH)
        onMain {
            if (host !== root) return@onMain
            attached?.let { old -> runCatching { layer.removeView(old) } }
            // 布局尺寸就是 agent 视口，一个像素不改 —— 页面版式与坐标因此不变
            wv.layoutParams = FrameLayout.LayoutParams(viewW, viewH)
            // 缩放挂容器，pivot 也在容器：视口左上角对齐窗口左上角
            layer.pivotX = 0f
            layer.pivotY = 0f
            layer.scaleX = sx
            layer.scaleY = sy
            layer.addView(wv)
            attached = wv
            hint?.visibility = View.GONE
        }
    }

    /** 把 WebView 从窗口里摘下来（页面会话销毁时）。窗口留着，空窗提示回来。 */
    fun detachWebView(wv: WebView) {
        val layer = scaledLayer
        onMain {
            runCatching { layer?.removeView(wv) }
            if (attached === wv) attached = null
            hint?.visibility = if (root != null) View.VISIBLE else View.GONE
        }
    }

    /** 窗口当前在不在（`diag` 报"用户看得见一个窗吗"的依据）。 */
    val isShown: Boolean get() = root != null

    /**
     * 容器上的显示缩放（诊断用）。
     *
     * **这里返回的是容器的缩放** —— WebView 自己的缩放永远是 1。
     */
    fun displayScale(): Pair<Float, Float> {
        val layer = scaledLayer ?: return 1f to 1f
        return layer.scaleX to layer.scaleY
    }

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
 * **显示缩放挂在这里**：容器负责把视口缩进小窗，WebView 自己永远是 1:1。
 *
 * 5.9.31：缩放原来挂在 WebView 上，导致截图必须"临时归 1、画完还原"，
 * 而每屏来回切两次视图变换又会把合成器搞乱（真机：整页截图第 3 屏起画面不跟随滚动）。
 * 挂到容器上之后这两样一起消失 —— 详见 [WebFloatWindowHost] 类注释。
 */
private class ScaleFrameLayout(context: Context) : FrameLayout(context)

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
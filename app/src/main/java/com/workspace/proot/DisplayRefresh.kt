package com.workspace.proot

import android.graphics.PixelFormat
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import java.util.Collections

/**
 * 高刷请求（5.9.62）。
 *
 * ## 前五版为什么全都没用
 *
 * 刷新率不是"谁请求听谁的"。Android 的 `DisplayModeDirector` 把各来源的投票
 * **按优先级求交集**，从高到低依次收窄：
 *
 * | 优先级 | 来源 | 行为 |
 * |---|---|---|
 * | 高 | **触摸提升** | 手指按下就顶到高档，持续一段时间 |
 * | 中 | **内容帧率检测** | SurfaceFlinger 量每个图层**实际提交帧的间隔**：稳定 16.7ms → 判定"内容是 60fps" |
 * | 最低 | **应用请求** | `preferredRefreshRate` / `preferredDisplayModeId` |
 *
 * 而 AOSP 的 SurfaceFlinger 里，图层想投票还有个硬前提：
 *
 * ```cpp
 * // Non-visible layers should now be inactive in LayerHistory.
 * if (flags::misc1() && !info.isVisible()) { return false; }
 * ```
 *
 * **图层必须可见 + 活跃（post 过 buffer）才有投票权。** 这就是为什么
 * app 主窗口自己的 surface 是最可靠的载体，而额外挂的锚点图层不一定。
 *
 * ## 5.9.62 修掉的三个根因
 *
 * ### 根因一：ARR 投票从头到尾是死代码（铁证）
 *
 * `View.setRequestedFrameRate` 的参数类型是 **`float`**，不是 int：
 *
 * ```java
 * public static final float REQUESTED_FRAME_RATE_CATEGORY_DEFAULT       = 0.0f;
 * public static final float REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE = -1;
 * public static final float REQUESTED_FRAME_RATE_CATEGORY_LOW           = -2;
 * public static final float REQUESTED_FRAME_RATE_CATEGORY_NORMAL        = -3;
 * public static final float REQUESTED_FRAME_RATE_CATEGORY_HIGH          = -4;
 * ```
 *
 * 而 5.9.60/5.9.61 找的是 `(int)` 重载、传的是 `4`。`Class.getMethod`
 * **要求参数类型精确匹配、不做隐式转换** —— 必然抛 `NoSuchMethodException`，
 * 被 `runCatching` 吞掉，**什么也没发生**。
 *
 * 现在直接传**正数的目标帧率**：文档明确"正数 = 期望帧率，负数 = 分类"。
 * 这同时绕开了那组**会随版本变化**的常量（它们从 -30/-60/-120 改成了 -2/-3/-4），
 * 不再依赖任何猜出来的数字。
 *
 * ### 根因二：请求值曾经是"当前值"
 *
 * 5.9.58 ~ 5.9.60 把 `pickTarget()` 的返回值同时当成"要不要切显示模式"和
 * "请求什么帧率"。它在没事可做时返回的 `hz` 是**当前正在跑的刷新率** ——
 * 启动时通常就是 60。于是那三版一路把 60 传给了 `setFrameRate()`：
 * **不是在"请求失败"，是在明确地、系统地要 60Hz。**
 *
 * ### 根因三：反射失败全是隐形的（真正的病根）
 *
 * `runCatching` 兜死没错（不能崩），但代价是 —— 方法不存在、参数类型不对、
 * 常量非法，**全都无声无息**。连着三版写下了执行不到的代码还以为它在工作。
 *
 * 所以本版给每一层加了 [note]：**成败都留痕**，并由终端介绍页显示出来。
 * 这是对工作方式的修补，不是装饰 —— 没有它，下一次还会写出死代码而不知道。
 *
 * ## 请求值为什么不查设备
 *
 * 官方文档原话：*"应用无需考虑实际支持的显示屏刷新率即可安全地调用 `setFrameRate()`。
 * 例如，即使设备仅支持 60Hz，也请使用应用偏好的帧速率调用。设备没有更适合的匹配
 * 则会保持当前的显示刷新率。"*
 *
 * 也就是说**先查设备再决定请求值本身就是反模式** —— 而且正是它把"当前值 60"
 * 引了进来。现在一律声明 [DESIRED_HZ]，由系统去挑最接近的档位。
 * 设备能力只用于**诊断显示**，不参与请求。
 */
object DisplayRefresh {

    /**
     * 本 app 的期望帧率。官方文档明确：直接声明偏好即可，系统会挑最接近的档位，
     * 没有更好的匹配就保持当前 —— **不要先查设备再决定请求值**。
     */
    const val DESIRED_HZ = 120f

    /** 只留挑选需要的四个字段 —— 不直接吃 `Display.Mode`，否则没法在 JVM 单测里精确验证。 */
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /** 挑出来的目标。`modeId` 为 0 表示"不动，交给系统自己挑"。 */
    data class Target(val modeId: Int, val hz: Float)

    /** 本次实际请求的刷新率，只给诊断读数用。 */
    @Volatile
    var requestedHz: Float? = null

    // ---------- 诊断：每一层的成败都留痕 ----------

    private val notes = Collections.synchronizedList(mutableListOf<String>())

    /**
     * 记一层请求的结果。**成功也要记** —— 只有失败才记的话，
     * "这层根本没被调用到"这种最危险的情况仍然看不见。
     */
    fun note(layer: String, outcome: String) {
        synchronized(notes) {
            notes.removeAll { it.substringBefore(':') == layer }
            notes.add("$layer=$outcome")
        }
    }

    /** 供介绍页显示的一行摘要。空串表示一层都没跑过。 */
    val report: String get() = synchronized(notes) { notes.joinToString("  ") }

    /** 设备能力，只用于诊断显示，**不参与请求**。 */
    @Volatile
    var deviceCapability: String = ""

    /** 记一次设备能力探测结果。 */
    fun noteDevice(currentW: Int, currentH: Int, currentHz: Float, altRates: List<Float>, modes: List<Mode>) {
        val sameSize = modes.filter { it.width == currentW && it.height == currentH && it.hz > 0f }
        val modeMax = sameSize.maxOfOrNull { it.hz } ?: 0f
        val altMax = altRates.filter { it > 0f }.maxOrNull() ?: 0f
        deviceCapability = buildString {
            append("当前%.0f".format(currentHz))
            append(" 模式最高%.0f".format(modeMax))
            append(" 备选最高%.0f".format(altMax))
            append(" 模式数${modes.size}")
        }
    }

    /** 浮点比较容差：面板报的 Hz 常带小数（59.94 / 120.0），留 0.5Hz 余量免得反复切。 */
    private const val EPS_HZ = 0.5f

    /**
     * 挑目标显示模式，**只用来决定要不要锁 `preferredDisplayModeId`**。
     * 它产出的 `hz` **不再用于任何帧率请求** —— 那是 5.9.58~5.9.60 的 bug 来源。
     *
     * - **只认分辨率逐像素一致的模式** —— 换分辨率会让整个版式重排，这是最大风险点。
     * - **已最优就不切**（`modeId = 0`）—— 避免无意义的切模式黑屏。
     * - 非正刷新率的坏模式先滤掉，否则可能被选成"最高档"。
     */
    fun pickTarget(
        currentW: Int,
        currentH: Int,
        currentHz: Float,
        modes: List<Mode>
    ): Target {
        val keep = Target(0, currentHz)
        if (currentW <= 0 || currentH <= 0 || modes.isEmpty()) return keep
        val sameSize = modes.filter { it.width == currentW && it.height == currentH }
        val best = sameSize.filter { it.hz > 0f }.maxByOrNull { it.hz } ?: return keep
        if (best.hz <= currentHz + EPS_HZ) return keep
        return Target(best.id, best.hz)
    }

    /**
     * 往 [parent] 里挂一个 1×1 的 [SurfaceView] 并对它设显式帧率。
     *
     * ## 它为什么能投票
     *
     * AOSP 的 SurfaceFlinger 判"图层是否活跃"时有一个特例：
     *
     * ```cpp
     * // Layers with an explicit frame rate or frame rate category are kept active,
     * // but ignore NoVote.
     * if (info.getSetFrameRateVote().isValid() && !info.getSetFrameRateVote().isNoVote()) {
     *     return true;
     * }
     * ```
     *
     * **带显式帧率票的可见图层会被保持为活跃** —— 所以它不需要自己画任何东西。
     * 这也是官方文档说的：*"为 `SurfaceView` 和 `TextureView` 显式设置的帧率会被
     * 尊重并应用。"*
     *
     * 尺寸取 1×1 而不是铺满：铺满会多出一个全屏图层白费合成开销，
     * 而投票只看有没有显式 `setFrameRate`，与尺寸无关。
     *
     * 格式设成半透明，避免默认的不透明格式在角落画一个黑点。
     *
     * 成败都走 [note] 留痕 —— 一个不投票的图层是隐形的，那是 5.9.60/61 的教训。
     */
    fun attachFrameRateAnchor(parent: ViewGroup, hz: Float) {
        if (hz <= 0f) return
        runCatching {
            val sv = SurfaceView(parent.context)
            sv.layoutParams = FrameLayout.LayoutParams(1, 1)
            // 不抢焦点、不吃触摸事件、不参与无障碍 —— 它只是个投票载体
            sv.isClickable = false
            sv.isFocusable = false
            sv.isFocusableInTouchMode = false
            sv.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            parent.addView(sv)

            val cb = object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) = applyFrameRate(holder, hz)
                override fun surfaceChanged(holder: SurfaceHolder, f: Int, w2: Int, h2: Int) = Unit
                override fun surfaceDestroyed(holder: SurfaceHolder) = Unit
            }
            sv.holder.setFormat(PixelFormat.TRANSLUCENT)
            sv.holder.addCallback(cb)
            // surfaceCreated 可能还没回调到，这里主动补一次。
            // 重复设置同一个值是幂等的，不会引发反复切档。
            applyFrameRate(sv.holder, hz)
        }.onFailure { note("锚点", "建视图失败") }
    }

    /**
     * 真正调 `Surface.setFrameRate()` 的地方，用 `Surface` 的同名常量（API 30/31 编译期就有）。
     *
     * `CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` 保证切换是**无缝**的：不满足条件就保持原档，
     * 而不是黑屏一秒（官方文档：*"Change the frame rate only if the transition is going
     * to be seamless."*）。
     */
    private fun applyFrameRate(holder: SurfaceHolder, hz: Float) {
        val surf = holder.surface
        if (surf == null) {
            note("锚点", "无surface")
            return
        }
        runCatching {
            surf.setFrameRate(
                hz,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
        }.onSuccess { note("锚点", "OK") }
            .onFailure { note("锚点", "抛${it.javaClass.simpleName}") }
    }
}

package com.workspace.proot

import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/**
 * 显示模式挑选 + 高刷三层投票（5.9.60）。
 *
 * ## 前四次为什么全都没用 —— 机制在这一段，不要再走回头路
 *
 * 刷新率不是"谁请求听谁的"。Android 的 `DisplayModeDirector` 把各来源的投票
 * **按优先级求交集**，从高到低依次收窄：
 *
 * | 优先级 | 来源 | 行为 |
 * |---|---|---|
 * | 高 | **触摸提升** | 手指按下就顶到高档，持续一段时间 |
 * | 中 | **内容帧率检测** | SurfaceFlinger 量每个图层**实际提交帧的间隔**：稳定 16.7ms → 判定"内容是 60fps" |
 * | 最低 | **应用请求** | `preferredDisplayModeId` / `preferredRefreshRate` |
 *
 * 所以 `preferredRefreshRate`（5.9.57）和 `preferredDisplayModeId`（5.9.58）
 * 永远是最弱的那一票，会被内容检测直接压死 —— **这就是前两次无效的真正原因**，
 * 与厂商白名单无关。
 *
 * ## 三层叠加，各覆盖不同版本
 *
 * **第一层 · `SurfaceView` 显式帧率（API 30+，覆盖 13/14/15/16）**
 * 官方文档：*"为 `SurfaceView` 和 `TextureView` 显式设置的帧率会被尊重并应用。"*
 * 它有独立图层、独立 BufferQueue，投票不受内容检测压制 —— **这正是游戏在用的路**。
 * 微信/浏览器能到 90 也在这里：它们有 `SurfaceView`，而本 app 此前一个都没有。
 *
 * **第二层 · `View.setRequestedFrameRate(HIGH)`（API 35+，覆盖 15/16）**
 * Android 15 的 ARR：*"目前大多数 View 默认投 Normal 帧率，通常是 60Hz。
 * 要更高的帧率需要用专门 API 改变偏好，系统一般取最高票。"*
 * 不主动投 High 就必然锁 60，**和厂商无关**。本 app 在 Android 16 上只有 60 就是这条。
 *
 * **第三层 · 窗口属性（覆盖 13/14）**
 * 低版本没有 ARR。空闲时内容检测投"无偏好"，此时窗口请求才有机会生效。
 *
 * ## ⚠ 5.9.60 修掉 5.9.58 自己埋的雷
 *
 * 5.9.58 写的是 `preferredRefreshRate = target.hz`，而 `pickTarget` 在没有更好档位时
 * 返回的 `hz` 是**当前值**。于是没得升的时候，它反而在向系统要 60Hz —— 本该"不表态"，
 * 却变成了"要 60"。现在没得升一律设 0（0 = 不表态）。
 *
 * ## 5.9.60 撤掉 5.9.59 的 isGame
 *
 * 官方文档：*"Android 15 默认把游戏的刷新率设为 60Hz 以省电。要解锁更高帧率必须显式请求。"*
 * 声明 `android:isGame="true"` 等于主动跳进这个坑，还会招来 vivo 游戏空间的侧边栏。
 * 已连同 `game_mode_config.xml` 一起删除。
 *
 * ## 风险
 *
 * - SurfaceView 锚点是 **1×1 全透明**，只多一个几乎为零的图层，不可见、不占显存。
 * - 第二层是公开 SDK 方法的反射，`SDK_INT` 把门 + `runCatching` 兜死，**不可能崩**。
 * - 不升 compileSdk，构建工具链零改动。
 */
object DisplayRefresh {

    /** 只留挑选需要的四个字段 —— 不直接吃 `Display.Mode`，否则没法在 JVM 单测里精确验证。 */
    data class Mode(val id: Int, val width: Int, val height: Int, val hz: Float)

    /** 挑出来的目标。`modeId` 为 0 表示"不动，交给系统自己挑"。 */
    data class Target(val modeId: Int, val hz: Float)

    /** 本次实际请求的刷新率，只给诊断读数用。 */
    @Volatile
    var requestedHz: Float? = null

    /**
     * `View.REQUESTED_FRAME_RATE_CATEGORY_HIGH`（API 35）。
     * 刻意重定义而不反射常量：`setRequestedFrameRate` 要 int 参数，反射方法本身已够，
     * 这个值与 AOSP 一致，单测可直接比对（值取自 AOSP View.java）。
     */
    const val CATEGORY_HIGH = 4

    /** 浮点比较容差：面板报的 Hz 常带小数（59.94 / 120.0），留 0.5Hz 余量免得反复切。 */
    private const val EPS_HZ = 0.5f

    /**
     * 挑目标显示模式（第三层用）。
     *
     * - **只认分辨率逐像素一致的模式** —— 换分辨率会让整个版式重排，这是最大风险点。
     * - **已最优就不切**（`modeId = 0`）—— 避免无意义的切模式黑屏。
     * - 非正刷新率的坏模式先滤掉，否则可能被选成"最高档"。
     *
     * 本函数是**幂等**的：切完再调，`currentHz` 已是高档，返回"不动"。
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
     * 第一层：往 [parent] 里挂一个 1×1 的 [SurfaceView] 并对它设显式帧率。
     *
     * 这是唯一一条**不受内容帧率检测压制**的路，也是游戏真正在用的那条。
     *
     * 尺寸取 1×1 而不是铺满：铺满会多出一个全屏图层，白费合成开销；
     * 而帧率投票只看这个 Surface 有没有显式 `setFrameRate`，与尺寸无关。
     *
     * 全程 `runCatching` 兜死 —— 拿不到 Surface / 底层不支持时静默跳过，
     * 另两层仍然生效。
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
            sv.holder.addCallback(cb)
            // surfaceCreated 可能还没回调到，这里主动补一次。
            // 重复设置同一个值是幂等的，不会引发反复切档。
            applyFrameRate(sv.holder, hz)
        }
    }

    /**
     * 真正调 `Surface.setFrameRate()` 的地方，用 `Surface` 的同名常量（API 30/31 编译期就有）。
     *
     * `FRAME_RATE_COMPATIBILITY_DEFAULT = 0`、**`CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS = 1`** ——
     * 后者保证切换是**无缝**的：不满足条件就保持原档，而不是黑屏一秒
     * （官方文档：*"Change the frame rate only if the transition is going to be seamless."*）。
     */
    private fun applyFrameRate(holder: SurfaceHolder, hz: Float) {
        runCatching {
            holder.surface?.setFrameRate(
                hz,
                Surface.FRAME_RATE_COMPATIBILITY_DEFAULT,
                Surface.CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS
            )
        }
    }
}

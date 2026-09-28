package com.workspace.proot

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

/**
 * 快捷启动抽屉「拟物实心」面板（5.8.4，纯视觉层，与窗口结构/时序/贴边/拖拽零耦合）。
 *
 * 面板本体 = **纯 app 背景色实心板**（顶角 20dp、底角 0，由 TileDrawer 侧 GradientDrawable
 * 承担）：顶边棱线与顶边上方落影已按真机反馈删除——叠在按键之上读作"一条奇怪的横线"。
 *
 * 颜色与 app 内部完全同源：面板底 = 背景色（ThemeColors.surface）、行文字 = 墨色
 * （onSurface）、握把 = 弱化墨色（onSurfaceVariant）、键面 = M3 容器台阶色
 * （surfaceContainer）；形状与大小和 5.6.0 逐项一致（通栏全宽、顶角 20dp、底角 0、
 * 58dp 行高、面板总高公式不变）。
 *
 * 拟物光学全部**按对比度反解**得出（[SolidPanel.of]）：从键面朝白/黑混到"刚好看得见"
 * 为止，而不是写死混入比——换任意背景色（纯黑/纯白/中灰/极端彩）光学关系恒成立。
 *
 * 每行是一颗机械键盘式按键（[RowKeyDrawable]），**静止态直接沿用 app 自己的拟态凸起配方**
 * （笔记页悬浮加号 `NotesController#buildFab`：右下暗影 + 左上亮高光 + 框架 elevation），
 * 按抽屉的几何重定参数（键面 50dp 见 [RowKeyDrawable.KEY_FACE_DP]）：
 *  - 静止（凸起）：右下暗影 + 左上亮高光（各偏移 [RowKeyDrawable.OFFSET_DP]）+ 键面，
 *    外加 `elevation = 5dp` 的**框架真阴影**——形状由 [RowKeyDrawable.getOutline] 的键面
 *    圆角矩形决定，真模糊、贴边框（等价 CSS box-shadow，不需要位图预渲染）；
 *  - 按下（凹陷）：手绘影撤掉、键面压深、上/左内阴影 + 下/右内高光、elevation 收到
 *    [RowKeyDrawable.PRESSED_ELEVATION_DP]、键帽整体下沉 1.5dp——**光的来向反过来**。
 *
 * 状态由 View 的 pressed 自动驱动（isStateful + onStateChange），触摸逻辑一行未动；
 * 松手后延迟 [RowKeyDrawable.MIN_PRESS_MS] ms 才弹回，快点点一下也看得见陷下去那一下。
 */
internal data class SolidColors(
    /** 面板底 = app 背景色。 */
    val panel: Int,
    val grip: Int,
    /** 行文字 = app 墨色。 */
    val ink: Int,
    /** 键面 = app 容器台阶色（M3 保证与 surface 有一档差）。 */
    val key: Int,
    /** 左上亮高光（受光面）。 */
    val keyHi: Int,
    /** 右下暗影（背光面）。 */
    val keyShadow: Int,
    /** 框架 elevation 阴影的染色。 */
    val keyCast: Int,
    val keyPressed: Int,
    val keyPressedInner: Int,
    val keyPressedHi: Int
)

internal object SolidPanel {

    private const val WHITE = 0xFFFFFFFF.toInt()
    private const val BLACK = 0xFF000000.toInt()

    // 可见性下限（WCAG 对比度）。浮雕只要"看得见"，不是文字可读性级别。
    private const val C_KEY_HI = 1.30
    private const val C_KEY_SHADOW = 1.55
    private const val C_KEY_CAST = 1.25
    private const val C_KEY_PRESSED = 1.22
    private const val C_KEY_PRESSED_INNER = 2.00
    private const val C_KEY_PRESSED_HI = 1.25

    /** 键帽按下下沉（真键盘的键帽是被压下去的）。 */
    fun sinkPx(density: Float): Float = 1.5f * density

    fun of(theme: ThemeColors): SolidColors {
        val key = theme.surfaceContainer
        return SolidColors(
            panel = theme.surface,
            grip = theme.onSurfaceVariant,
            ink = theme.onSurface,
            key = key,
            keyHi = ColorMath.mixUntil(key, WHITE, C_KEY_HI),
            keyShadow = ColorMath.mixUntil(key, BLACK, C_KEY_SHADOW),
            keyCast = ColorMath.mixUntil(key, BLACK, C_KEY_CAST),
            keyPressed = ColorMath.mixUntil(key, BLACK, C_KEY_PRESSED),
            keyPressedInner = ColorMath.mixUntil(key, BLACK, C_KEY_PRESSED_INNER),
            keyPressedHi = ColorMath.mixUntil(key, WHITE, C_KEY_PRESSED_HI)
        )
    }
}

/**
 * 行按键：静止凸起 / 按下凹陷的状态机。状态由 View 的 pressed 自动驱动
 * （isStateful + onStateChange），触摸与点击逻辑一行未动。
 *
 * 几何（按抽屉实际布局定的，不是照抄悬浮加号——那是 56dp 正圆、悬在内容之上）：
 *  行高 58dp = 键面 [KEY_FACE_DP] + 下方影带 [BAND_DP]。影带同时容纳手绘的右下暗影
 *  （偏移 [OFFSET_DP]）与相邻键之间 5dp elevation 模糊的呼吸空间。
 *
 * 圆角取 app 按钮同款（[ButtonStyle.CORNER_RADIUS_DP]），左右留白见 TileDrawer。
 */
internal class RowKeyDrawable(
    private val c: SolidColors,
    private val density: Float
) : Drawable() {

    private val radius = ButtonStyle.CORNER_RADIUS_DP * density
    private val offset = OFFSET_DP * density
    private val innerShadowTopH = 2.5f * density
    private val innerShadowSideW = 1.5f * density
    private val innerHiBottomH = 1.2f * density
    private val innerHiSideW = 1.5f * density
    private val bandH = BAND_DP * density

    private var keyH = 0f

    private val keyPath = Path()
    private val tmp = RectF()

    // 静止：右下暗影 / 左亮高光 / 键面 + 顶棱内亮线
    private val darkPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 凹陷：压深的键面 + 上/左内阴影 + 下/右内高光
    private val pressedFacePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedShTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedShSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedHiSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pressedHiBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val handler = Handler(Looper.getMainLooper())
    private var pressed = false
    private var statePressed = false
    private var pending: Runnable? = null

    override fun isStateful(): Boolean = true

    override fun onStateChange(state: IntArray): Boolean {
        val now = state.contains(android.R.attr.state_pressed)
        statePressed = now
        if (now) {
            pending?.let { handler.removeCallbacks(it) }
            pending = null
            if (!pressed) {
                pressed = true
                invalidateSelf()
            }
            return true
        }
        if (pressed) {
            // 最短按压：延迟期间若又被按下（连点/多指）则不回弹
            val r = Runnable {
                pending = null
                if (!statePressed) {
                    pressed = false
                    invalidateSelf()
                }
            }
            pending = r
            handler.postDelayed(r, MIN_PRESS_MS)
        }
        return true
    }

    override fun onBoundsChange(b: Rect) {
        super.onBoundsChange(b)
        val w = b.width().toFloat()
        val h = b.height().toFloat()
        keyH = (h - bandH).coerceAtLeast(dp(1f))
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.left + w
        keyPath.reset()
        tmp.set(left, top, right, top + keyH)
        keyPath.addRoundRect(tmp, radius, radius, Path.Direction.CW)

        darkPaint.color = c.keyShadow
        lightPaint.color = c.keyHi
        facePaint.color = c.key
        pressedFacePaint.color = c.keyPressed
        // 顶棱内亮线（静止凸起，受光面）
        hiTopPaint.shader = LinearGradient(
            0f, top, 0f, top + innerHiBottomH,
            intArrayOf(
                withAlpha(c.keyHi, 210),
                withAlpha(c.keyHi, 105),
                withAlpha(c.keyHi, 0)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        // 上棱内阴影（凹陷）
        pressedShTopPaint.shader = LinearGradient(
            0f, top, 0f, top + innerShadowTopH,
            intArrayOf(
                withAlpha(c.keyPressedInner, 230),
                withAlpha(c.keyPressedInner, 128),
                withAlpha(c.keyPressedInner, 0)
            ),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
        // 左棱内阴影
        pressedShSidePaint.shader = LinearGradient(
            left, 0f, left + innerShadowSideW, 0f,
            intArrayOf(
                withAlpha(c.keyPressedInner, 153),
                withAlpha(c.keyPressedInner, 77),
                withAlpha(c.keyPressedInner, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // 右棱内高光（反弹光）
        pressedHiSidePaint.shader = LinearGradient(
            right - innerHiSideW, 0f, right, 0f,
            intArrayOf(
                withAlpha(c.keyPressedHi, 0),
                withAlpha(c.keyPressedHi, 77),
                withAlpha(c.keyPressedHi, 153)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // 下棱内高光
        pressedHiBottomPaint.shader = LinearGradient(
            0f, top + keyH - innerHiBottomH, 0f, top + keyH,
            intArrayOf(
                withAlpha(c.keyPressedHi, 0),
                withAlpha(c.keyPressedHi, 115),
                withAlpha(c.keyPressedHi, 217)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    /**
     * 框架 elevation 阴影的轮廓：**只取键面**（不含下方影带），圆角矩形——
     * 于是框架画出的真模糊阴影形状贴键的圆角走，等价于 CSS `box-shadow` 的贴边效果，
     * 且不需要位图预渲染（此前的位图方案会在 dismiss 回收时与仍在绘制的视图打架崩溃）。
     */
    override fun getOutline(outline: Outline) {
        if (keyH <= 0f || bounds.isEmpty) {
            outline.setEmpty()
            return
        }
        outline.setRoundRect(
            bounds.left, bounds.top, bounds.right, bounds.top + keyH.toInt(), radius
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat()
        val left = b.left.toFloat()
        val top = b.top.toFloat()
        val right = b.left + w
        val faceBottom = top + keyH
        if (pressed) {
            canvas.save()
            canvas.clipPath(keyPath)
            canvas.drawRect(left, top, right, faceBottom, pressedFacePaint)
            canvas.drawRect(left, top, right, top + innerShadowTopH, pressedShTopPaint)
            canvas.drawRect(left, top, left + innerShadowSideW, faceBottom, pressedShSidePaint)
            canvas.drawRect(right - innerHiSideW, top, right, faceBottom, pressedHiSidePaint)
            canvas.drawRect(left, faceBottom - innerHiBottomH, right, faceBottom, pressedHiBottomPaint)
            canvas.restore()
        } else {
            // 凸起：右下暗影 + 左亮高光（笔记页悬浮加号同款配方），再压键面。
            // 亮高光层**只往左溢出**、不往上：往上溢会跑进上一行的空隙里、在左上角露白；
            // 顶边受光改由键面内的亮线承担（见 hiTopPaint）。
            tmp.set(left, top + offset, right, faceBottom + offset)
            canvas.drawRoundRect(tmp, radius, radius, darkPaint)
            tmp.set(left - offset, top, right - offset, faceBottom - offset)
            canvas.drawRoundRect(tmp, radius, radius, lightPaint)
            tmp.set(left, top, right, faceBottom)
            canvas.drawRoundRect(tmp, radius, radius, facePaint)
            // 顶棱内亮线（受光面，键面内，不溢出）
            canvas.drawRect(left, top, right, top + innerHiBottomH, hiTopPaint)
        }
    }

    override fun setAlpha(alpha: Int) {
        // 整体透明度由 View.alpha 控制，drawable 自身 alpha 契约不使用
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        facePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Android")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    private fun dp(v: Float): Float = (v * density).coerceAtLeast(1f)

    companion object {
        /**
         * 键面高度（dp）。行高 58dp 里让出 [BAND_DP] 给下方影带——比上一版的 52dp 再矮一点：
         * 5dp elevation 的真阴影绕整圈投，相邻键只隔 6dp 会糊掉下一键的上棱。
         */
        const val KEY_FACE_DP = 50

        /** 键面下方的影带高度（dp），行高因此仍是 58dp（面板总高公式不变）。 */
        const val BAND_DP = 8

        /** 手绘影偏移（dp）：右下暗影 / 左上亮高光（同悬浮加号，相对 50dp 键面同比例）。 */
        const val OFFSET_DP = 3f

        /** 静止态 elevation（dp）≈ 框架阴影的模糊半径，铺在 8dp 影带里刚好不溢出。 */
        const val ELEVATION_DP = 5f

        /** 按下态 elevation（dp）：影子收掉。 */
        const val PRESSED_ELEVATION_DP = 1f

        /** 最短按压时长（ms）：快点点一下也看得见凹陷。设 0 = 立即回弹。 */
        const val MIN_PRESS_MS = 90L
    }
}

/** 给颜色套 alpha（内阴影/内高光的多段衰减用；基色本身是不透明的推导色）。 */
private fun withAlpha(color: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

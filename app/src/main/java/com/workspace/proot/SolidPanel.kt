package com.workspace.proot

import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
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
 * 快捷启动抽屉「拟物实心」面板（5.8.2，纯视觉层，与窗口结构/时序/贴边/拖拽零耦合）。
 *
 * 面板本体 = **纯 app 背景色实心板**（顶角 20dp、底角 0，由 TileDrawer 侧 GradientDrawable
 * 承担）：顶边棱线与顶边上方落影已按真机反馈删除——叠在行键之上读作"一条奇怪的横线"。
 *
 * 颜色与 app 内部完全同源：面板底 = 背景色（ThemeColors.surface）、行文字 = 墨色
 * （onSurface）、握把 = 弱化墨色（onSurfaceVariant）、键面 = M3 容器台阶色
 * （surfaceContainer）；形状与大小和 5.6.0 逐项一致（通栏全宽、顶角 20dp、底角 0、
 * 58dp 行高、面板总高公式不变）。
 *
 * 拟物光学全部**按对比度反解**得出（[SolidPanel.of]）：从键面朝白/黑混到"刚好看得见"
 * 为止，而不是写死混入比——换任意背景色（纯黑/纯白/中灰/极端彩）光学关系恒成立。
 *
 * 每行 = 一颗机械键盘式按键（[RowKeyDrawable]），按经典拟物按钮的四条棱 + 外投影配方：
 *  - 静止（凸起）：上棱/左棱内高光（受光）· 下棱/右棱内阴影（背光）· **键面下方一条外投影**
 *    落在面板上（键面本身保持干净）；
 *  - 按下（凹陷）：上棱/左棱/右棱内阴影（光进不去）· 下棱内高光（反弹光）· **外投影消失**，
 *    键帽整体下沉 1.5dp——光的来向反过来了，这才是凸/凹的物理区别。
 *
 * 几何：行高 58dp = 键面 52dp（[BAND_DP] 让出的 6dp 为投影带，画在行 bounds 内）
 * → 面板总高公式一字不变，文字加下内边距 6dp 仍精确居中在键面上。
 * 所有棱与投影都是多段色标（外投影 4 段、棱线 3 段），模拟 CSS box-shadow 的衰减，
 * 不是 transparent→color 的硬斜坡。
 *
 * 状态由 View 的 pressed 自动驱动，触摸逻辑一行未动；松手后延迟 [MIN_PRESS_MS] ms
 * 才弹回，快点点一下也看得见陷下去那一下。
 */
internal data class SolidColors(
    /** 面板底 = app 背景色。 */
    val panel: Int,
    val grip: Int,
    /** 行文字 = app 墨色。 */
    val ink: Int,
    /** 键面 = app 容器台阶色（M3 保证与 surface 有一档差）。 */
    val key: Int,
    val keyHi: Int,
    val keyShadow: Int,
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
 * 键下方外投影的几何（纯逻辑）：等价于 CSS `box-shadow: 0 OFFSET_Y BLUR color`——
 * **形状贴键的圆角矩形真模糊衰减**，不是叠矩形/叠圆角矩形去逼近（后者在角上必然缺影子）。
 * [extentPx] 是相对键面底边的总下延，必须塞进投影带 [RowKeyDrawable.BAND_DP]。
 */
internal object RowShadow {

    /** 模糊半径（dp）≈ CSS box-shadow 的 blur。 */
    const val BLUR_DP = 3f

    /** 垂直偏移（dp）：阴影落在键下方，而不是正贴在键底。 */
    const val OFFSET_Y_DP = 1.5f

    /** 整体透明度（0..1）：位图是实色模糊，这里统一压一档。 */
    const val ALPHA = 0.72f

    /** BlurMaskFilter 的模糊实际外扩约 1.5×半径（位图要留出这块）。 */
    private const val SPREAD = 1.5f

    fun blurPx(density: Float): Int = (BLUR_DP * density).toInt().coerceAtLeast(1)

    fun offsetYPx(density: Float): Int = (OFFSET_Y_DP * density).toInt()

    /** 位图每侧要留出的外扩（px）。 */
    fun extentPx(density: Float): Int = offsetYPx(density) + (blurPx(density) * SPREAD).toInt()

    fun alpha255(): Int = (ALPHA * 255f).toInt().coerceIn(1, 255)
}

/**
 * 投影位图缓存：所有行同尺寸同色，只建一次（~0.7MB）。
 * BlurMaskFilter 在硬件加速画布上不生效（API 31 前），而抽屉是硬件加速悬浮窗，
 * 故用**软件 Canvas 预渲染成位图**再 blit——真模糊 + API 26+ 全兼容。
 * 抽屉弹出前 prebuild（把一次模糊挡在 250ms 延迟里，不压首帧），关闭时 clear()。
 */
internal object RowShadowCache {

    private var bmp: Bitmap? = null
    private var fingerprint = 0L

    fun prebuild(keyW: Int, keyH: Int, radiusPx: Float, shadowColor: Int, density: Float) {
        get(keyW, keyH, radiusPx, shadowColor, density)
    }

    fun get(keyW: Int, keyH: Int, radiusPx: Float, shadowColor: Int, density: Float): Bitmap? {
        if (keyW <= 0 || keyH <= 0) return null
        val fp = fingerprintOf(keyW, keyH, radiusPx, shadowColor, density)
        val cached = bmp
        if (cached != null && fp == fingerprint && !cached.isRecycled) return cached
        val built = build(keyW, keyH, radiusPx, shadowColor, density) ?: return null
        cached?.recycle()
        bmp = built
        fingerprint = fp
        return built
    }

    fun clear() {
        bmp?.recycle()
        bmp = null
        fingerprint = 0L
    }

    private fun fingerprintOf(w: Int, h: Int, r: Float, color: Int, density: Float): Long =
        ((((w.toLong() * 31 + h) * 31 + r.toInt()) * 31 + color) * 31 + (density * 100f).toInt())

    private fun build(
        keyW: Int,
        keyH: Int,
        radiusPx: Float,
        shadowColor: Int,
        density: Float
    ): Bitmap? {
        val ext = RowShadow.extentPx(density)
        val blur = RowShadow.blurPx(density)
        return try {
            val b = Bitmap.createBitmap(keyW + ext * 2, keyH + ext * 2, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = shadowColor
                maskFilter = BlurMaskFilter(blur.toFloat(), BlurMaskFilter.Blur.NORMAL)
            }
            c.drawRoundRect(
                ext.toFloat(), ext.toFloat(),
                (ext + keyW).toFloat(), (ext + keyH).toFloat(),
                radiusPx, radiusPx, p
            )
            b
        } catch (t: Throwable) {
            // 位图分配失败/OOM → 退化为无投影，抽屉照常可用
            null
        }
    }
}

/**
 * 行按键：静止凸起 / 按下凹陷的状态机。状态由 View 的 pressed 自动驱动
 * （isStateful + onStateChange），触摸与点击逻辑一行未动。
 * 圆角取 app 按钮同款（[ButtonStyle.CORNER_RADIUS_DP]），左右留白见 TileDrawer。
 */
internal class RowKeyDrawable(
    private val c: SolidColors,
    private val density: Float
) : Drawable() {

    private val radius = ButtonStyle.CORNER_RADIUS_DP * density
    private val hiH = 1.2f * density        // 上棱内高光厚度
    private val sideW = 1.5f * density      // 左右棱厚度
    private val bottomH = 1.2f * density    // 下棱厚度
    private val pressedTopH = 2.5f * density // 凹陷上棱内阴影厚度（更深）
    private val bandH = BAND_DP * density   // 键面下方留给外投影的高度

    private var keyH = 0f

    private val path = Path()
    private val tmp = RectF()

    // 静止（凸起）
    private val facePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiTopPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hiSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shSidePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shBottomPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // 真·CSS 投影：预渲染位图（形状贴圆角真模糊），绘制时 blit
    private val shadowBlit = Paint(Paint.FILTER_BITMAP_FLAG)
    private var shadowBmp: Bitmap? = null
    private var shadowExt = 0

    // 按下（凹陷）
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
        keyH = (h - bandH).coerceAtLeast(h * 0.5f)
        tmp.set(b.left.toFloat(), b.top.toFloat(), b.left + w, b.top + keyH)
        path.reset()
        path.addRoundRect(tmp, radius, radius, Path.Direction.CW)

        facePaint.color = c.key
        pressedFacePaint.color = c.keyPressed

        // ① 外投影：贴键圆角真模糊的位图（见 [RowShadow] / [RowShadowCache]）
        shadowExt = RowShadow.extentPx(density)
        shadowBlit.alpha = RowShadow.alpha255()
        shadowBmp = RowShadowCache.get(w.toInt(), keyH.toInt(), radius, c.keyCast, density)

        // ② 上棱内高光（受光面）
        hiTopPaint.shader = LinearGradient(
            0f, 0f, 0f, hiH,
            intArrayOf(
                withAlpha(c.keyHi, 230),
                withAlpha(c.keyHi, 115),
                withAlpha(c.keyHi, 0)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        // ③ 左棱内高光（弱一档）
        hiSidePaint.shader = LinearGradient(
            0f, 0f, sideW, 0f,
            intArrayOf(
                withAlpha(c.keyHi, 140),
                withAlpha(c.keyHi, 71),
                withAlpha(c.keyHi, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // ④ 右棱内阴影（背光面）
        shSidePaint.shader = LinearGradient(
            w - sideW, 0f, w, 0f,
            intArrayOf(
                withAlpha(c.keyShadow, 0),
                withAlpha(c.keyShadow, 71),
                withAlpha(c.keyShadow, 140)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // ⑤ 下棱内阴影（键帽底面背光，很细一线）
        shBottomPaint.shader = LinearGradient(
            0f, keyH - bottomH, 0f, keyH,
            intArrayOf(
                withAlpha(c.keyShadow, 0),
                withAlpha(c.keyShadow, 102),
                withAlpha(c.keyShadow, 191)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        // ⑥ 凹陷：上棱内阴影（更深）
        pressedShTopPaint.shader = LinearGradient(
            0f, 0f, 0f, pressedTopH,
            intArrayOf(
                withAlpha(c.keyPressedInner, 230),
                withAlpha(c.keyPressedInner, 128),
                withAlpha(c.keyPressedInner, 0)
            ),
            floatArrayOf(0f, 0.40f, 1f),
            Shader.TileMode.CLAMP
        )
        // ⑦ 凹陷：左棱内阴影
        pressedShSidePaint.shader = LinearGradient(
            0f, 0f, sideW, 0f,
            intArrayOf(
                withAlpha(c.keyPressedInner, 153),
                withAlpha(c.keyPressedInner, 77),
                withAlpha(c.keyPressedInner, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // ⑧ 凹陷：右棱内高光
        pressedHiSidePaint.shader = LinearGradient(
            w - sideW, 0f, w, 0f,
            intArrayOf(
                withAlpha(c.keyPressedHi, 0),
                withAlpha(c.keyPressedHi, 77),
                withAlpha(c.keyPressedHi, 153)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        // ⑨ 凹陷：下棱内高光（反弹光）
        pressedHiBottomPaint.shader = LinearGradient(
            0f, keyH - bottomH, 0f, keyH,
            intArrayOf(
                withAlpha(c.keyPressedHi, 0),
                withAlpha(c.keyPressedHi, 115),
                withAlpha(c.keyPressedHi, 217)
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val top = b.top.toFloat()
        val left = b.left.toFloat()
        val right = b.right.toFloat()
        val faceBottom = top + keyH
        if (pressed) {
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(left, top, right, faceBottom, pressedFacePaint)
            canvas.drawRect(left, top, right, top + pressedTopH, pressedShTopPaint)
            canvas.drawRect(left, top, left + sideW, faceBottom, pressedShSidePaint)
            canvas.drawRect(right - sideW, top, right, faceBottom, pressedHiSidePaint)
            canvas.drawRect(left, faceBottom - bottomH, right, faceBottom, pressedHiBottomPaint)
            canvas.restore()
        } else {
            // 外投影（贴键圆角真模糊，落在面板上）——凸起态的"影子"在这里，不在键面里
            shadowBmp?.let {
                canvas.drawBitmap(
                    it,
                    left - shadowExt,
                    top + RowShadow.offsetYPx(density) - shadowExt,
                    shadowBlit
                )
            }
            canvas.save()
            canvas.clipPath(path)
            canvas.drawRect(left, top, right, faceBottom, facePaint)
            canvas.drawRect(left, top, right, top + hiH, hiTopPaint)
            canvas.drawRect(left, top, left + sideW, faceBottom, hiSidePaint)
            canvas.drawRect(right - sideW, top, right, faceBottom, shSidePaint)
            canvas.drawRect(left, faceBottom - bottomH, right, faceBottom, shBottomPaint)
            canvas.restore()
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

    companion object {
        /**
         * 键面下方留给外投影的高度（dp，从 58dp 行高里让出 6dp）——
         * 面板总高因此不变；行文字下内边距取同值，文字仍居中在键面上。
         */
        const val BAND_DP = 6

        /** 最短按压时长（ms）：快点点一下也看得见凹陷。设 0 = 立即回弹。 */
        const val MIN_PRESS_MS = 90L
    }
}

/** 给颜色套 alpha（棱线/投影的多段衰减用；基色本身是不透明的推导色）。 */
private fun withAlpha(color: Int, alpha: Int): Int =
    (alpha.coerceIn(0, 255) shl 24) or (color and 0x00FFFFFF)

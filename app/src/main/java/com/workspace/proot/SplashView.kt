package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * 开屏动画：点阵飞入 + 品牌渐变。
 * 点阵默认 TERMLOU（SplashTokens.defaultCells），可传自定义 cells（启动工坊保存的 splash.json）。
 * 背景与已落位像素预渲染进静态层位图，逐帧只贴图 + 画飞行中粒子。
 * @param customCells 自定义像素 (row, col, 灰阶档）；null = 默认 LOGO
 * @param showProgress 是否显示进度条/状态文字（预览模式 false）
 */
class SplashView(context: Context, customCells: List<SplashTokens.SplashCell>? = null, private val showProgress: Boolean = true) : View(context) {

    companion object {
        private const val DAY_BG = 0xFFF5F5F5.toInt()
        private const val DAY_TEXT = 0xFF616161.toInt()
        private const val DAY_TRACK = 0x1F000000.toInt()
    }

    private val night: Boolean =
        context.getSharedPreferences("term-lou-settings", Context.MODE_PRIVATE).getBoolean("nightMode", true)
    private val bgColor: Int = if (night) Color.BLACK else DAY_BG

    private val rows = SplashTokens.ROWS
    private val cols = SplashTokens.COLS

    private val density = context.resources.displayMetrics.density
    private val screenW = context.resources.displayMetrics.widthPixels.toFloat()
    private val screenH = context.resources.displayMetrics.heightPixels.toFloat()

    private val pixelSize = SplashTokens.pixelSize(screenW)
    private val totalW = pixelSize * cols
    private val totalH = pixelSize * rows
    private val offsetX = (screenW - totalW) / 2f
    private val offsetY = (screenH - totalH) / 2f

    private val green = SplashTokens.GREEN
    private val cyan = SplashTokens.CYAN

    private val cells = ArrayList<Cell>()
    private val particles = ArrayList<Particle>()

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val solidPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val decelerate = DecelerateInterpolator(2f)

    private var gradientShader: LinearGradient? = null
    private var barShader: LinearGradient? = null
    private var backdropShader: RadialGradient? = null

    private var convergeAnim: ValueAnimator? = null
    private var convergeFinished = false
    private var snapped = false
    private var dismissing = false
    private var pendingDismiss: (() -> Unit)? = null
    private var detached = false

    /** 静态层：背景径向渐变 + 已落位实色，尺寸变化时重建，逐帧只贴图。 */
    private var layer: Bitmap? = null
    private var layerCanvas: Canvas? = null
    private var layerW = 0
    private var layerH = 0
    private var settledDrawn = BooleanArray(0)
    private var tickPosted = false

    private val breatheRunnable = object : Runnable {
        override fun run() {
            tickPosted = false
            if (detached || dismissing) return
            invalidate()
            if (!isSettled()) postTick()
        }
    }

    /** 按需刷帧：仅在聚合未完成或进度条未收敛时续跑，静止即停。 */
    private fun postTick() {
        if (tickPosted || detached || dismissing) return
        tickPosted = true
        postDelayed(breatheRunnable, 16)
    }

    private fun isSettled(): Boolean =
        convergeFinished && (!showProgress || shownProgress >= targetProgress - 0.002f)

    private val startTime = System.currentTimeMillis()
    private var sawStatus = false
    private var targetProgress = 0.15f
    private var shownProgress = 0f

    var statusText: String = ""
        set(value) {
            field = value
            advanceProgress(value)
        }

    init {
        buildGrid(customCells)
        buildParticles()
        settledDrawn = BooleanArray(particles.size)
        setupPaints()
        setBackgroundColor(bgColor)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        startConverge()
        postTick()
    }

    private fun buildGrid(customCells: List<SplashTokens.SplashCell>?) {
        val list = customCells ?: SplashTokens.defaultCells()
        for (cell in list) {
            val (r, col, v) = cell
            cells.add(
                Cell(
                    col,
                    offsetX + (col + 0.5f) * pixelSize,
                    offsetY + (r + 0.5f) * pixelSize,
                    SplashTokens.cellColor(col),
                    v.coerceIn(SplashTokens.LEVEL_OFF, SplashTokens.LEVEL_FULL)
                )
            )
        }
    }

    private fun levelFactor(v: Int): Float =
        SplashTokens.LEVEL_ALPHAS[v.coerceIn(SplashTokens.LEVEL_OFF, SplashTokens.LEVEL_FULL)] / 255f

    private fun buildParticles() {
        val margin = SplashTokens.PARTICLE_MARGIN_DP * density
        for (cell in cells) {
            val side = (0..3).random()
            val sx: Float
            val sy: Float
            when (side) {
                0 -> {
                    sx = (0 until screenW.toInt()).random().toFloat()
                    sy = -(margin + (0..80).random())
                }
                1 -> {
                    sx = (0 until screenW.toInt()).random().toFloat()
                    sy = screenH + margin + (0..80).random()
                }
                2 -> {
                    sx = -(margin + (0..80).random())
                    sy = (0 until screenH.toInt()).random().toFloat()
                }
                else -> {
                    sx = screenW + margin + (0..80).random()
                    sy = (0 until screenH.toInt()).random().toFloat()
                }
            }
            val letterIdx = cell.col / (SplashTokens.COLS_PER_LETTER + 1)
            val delay = letterIdx * SplashTokens.FLY_DELAY_LETTER +
                (cell.col % (SplashTokens.COLS_PER_LETTER + 1)) * SplashTokens.FLY_DELAY_COL +
                (0..SplashTokens.FLY_DELAY_RAND).random()
            val duration = SplashTokens.FLY_DURATION_BASE + (0..SplashTokens.FLY_DURATION_RANGE.toInt()).random()
            particles.add(Particle(sx, sy, cell.tx, cell.ty, delay, duration, cell.color, cell.level))
        }
    }

    private fun setupPaints() {
        particlePaint.style = Paint.Style.FILL
        solidPaint.style = Paint.Style.FILL
        bgPaint.style = Paint.Style.FILL
        trackPaint.color = if (night) UiTokens.whiteFaint else DAY_TRACK
        textPaint.color = if (night) UiTokens.splashText else DAY_TEXT
        textPaint.textSize = 12 * density
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textAlign = Paint.Align.CENTER
    }

    private fun startConverge() {
        val anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = SplashTokens.CONVERGE_MS.toLong()
            addUpdateListener { invalidate() }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    convergeFinished = true
                    invalidate()
                    postTick()
                    pendingDismiss?.let {
                        pendingDismiss = null
                        if (!detached) it()
                    }
                }
            })
        }
        convergeAnim = anim
        anim.start()
    }

    fun dismiss(done: () -> Unit) {
        if (detached) {
            done()
            return
        }
        if (dismissing) return
        dismissing = true
        targetProgress = 1f
        postTick()
        if (convergeFinished || convergeAnim == null) {
            snapped = true
            fadeOut(done)
        } else {
            pendingDismiss = {
                snapped = true
                fadeOut(done)
            }
        }
    }

    private fun fadeOut(done: () -> Unit) {
        if (detached) {
            done()
            return
        }
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = SplashTokens.FADE_OUT_MS.toLong()
            addUpdateListener { v -> alpha = v.animatedValue as Float }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!detached) done()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        detached = true
        pendingDismiss = null
        convergeAnim?.cancel()
        removeCallbacks(breatheRunnable)
        layer?.recycle()
        layer = null
        layerCanvas = null
        super.onDetachedFromWindow()
    }

    private fun pProgress(p: Particle, elapsed: Float): Float {
        if (snapped) return 1f
        val e = elapsed - p.delay
        if (e <= 0f) return 0f
        val pr = e / p.duration
        if (pr >= 1f) return 1f
        return decelerate.getInterpolation(pr)
    }

    private fun drawParticle(canvas: Canvas, p: Particle, local: Float) {
        val x = p.sx + (p.tx - p.sx) * local
        val y = p.sy + (p.ty - p.sy) * local
        val size = pixelSize * SplashTokens.PARTICLE_SIZE_FACTOR
        particlePaint.shader = null
        particlePaint.color = SplashTokens.lerpAlpha(p.color, ((60 + 180 * local) * levelFactor(p.level)).toInt())
        canvas.drawRoundRect(
            x - size / 2f, y - size / 2f,
            x + size / 2f, y + size / 2f,
            size * SplashTokens.PARTICLE_CORNER_FACTOR, size * SplashTokens.PARTICLE_CORNER_FACTOR, particlePaint
        )
    }

    private fun drawBackdrop(canvas: Canvas) {
        if (backdropShader == null) {
            backdropShader = RadialGradient(
                offsetX + totalW / 2f, offsetY + totalH / 2f, totalW * 1.1f,
                intArrayOf(UiTokens.backdropGlow, 0x00000000), null, Shader.TileMode.CLAMP
            )
        }
        bgPaint.shader = backdropShader
        canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), bgPaint)
        bgPaint.shader = null
    }

    /** 确保静态层存在且尺寸匹配：背景 + 已落位实色一次画好，逐帧只贴图。 */
    private fun ensureLayer(): Boolean {
        val w = width
        val h = height
        if (w <= 0 || h <= 0) return false
        if (layer != null && layerW == w && layerH == h) return true
        layer?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        drawBackdrop(c)
        val radius = pixelSize * SplashTokens.PIXEL_RADIUS_FACTOR
        for (i in particles.indices) {
            if (settledDrawn[i]) drawSolid(c, i, radius)
        }
        layer = bmp
        layerCanvas = c
        layerW = w
        layerH = h
        return true
    }

    /** 把一个已落位像素画进静态层（品牌按列渐变，档位透明度）。 */
    private fun drawSolid(canvas: Canvas, i: Int, radius: Float) {
        var shader = gradientShader
        if (shader == null) {
            shader = LinearGradient(
                offsetX, 0f, offsetX + totalW, 0f,
                intArrayOf(green, cyan), null, Shader.TileMode.CLAMP
            )
            gradientShader = shader
        }
        solidPaint.shader = shader
        val cell = cells[i]
        val l = cell.tx - pixelSize * 0.5f
        val t = cell.ty - pixelSize * 0.5f
        solidPaint.alpha = SplashTokens.LEVEL_ALPHAS[cell.level]
        canvas.drawRoundRect(l, t, l + pixelSize, t + pixelSize, radius, radius, solidPaint)
        solidPaint.alpha = 255
        solidPaint.shader = null
    }

    private fun drawLetters(canvas: Canvas, elapsed: Float) {
        if (!ensureLayer()) {
            canvas.drawColor(bgColor)
            return
        }
        val lc = layerCanvas ?: return
        val radius = pixelSize * SplashTokens.PIXEL_RADIUS_FACTOR
        // 本帧新落位的像素补画进静态层（只在落位时画一次，不逐帧重画）
        for (i in particles.indices) {
            if (!settledDrawn[i] && pProgress(particles[i], elapsed) >= 1f) {
                drawSolid(lc, i, radius)
                settledDrawn[i] = true
            }
        }
        layer?.let { canvas.drawBitmap(it, 0f, 0f, null) }
        // 逐帧只画仍在飞行中的粒子
        for (i in particles.indices) {
            val local = pProgress(particles[i], elapsed)
            if (local > 0f && local < 1f) drawParticle(canvas, particles[i], local)
        }
    }

    private fun advanceProgress(text: String) {
        sawStatus = true
        val target = when (text) {
            context.getString(R.string.splash_preparing) -> 0.30f
            context.getString(R.string.splash_deps) -> 0.60f
            context.getString(R.string.splash_starting) -> 0.85f
            else -> min(0.95f, targetProgress + 0.10f)
        }
        if (target > targetProgress) targetProgress = target
        postTick()
    }

    private fun drawStatus(canvas: Canvas) {
        if (!showProgress) return
        val w = canvas.width.toFloat()
        val h = canvas.height.toFloat()
        val barW = w * 0.5f
        val barH = 3f * density
        val barX = (w - barW) / 2f
        val barY = h - 54f * density
        val radius = barH / 2f

        canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, radius, radius, trackPaint)

        val idleMin = if (sawStatus) {
            targetProgress
        } else {
            min(targetProgress, 0.05f + (System.currentTimeMillis() - startTime) / 6000f * 0.4f)
        }
        val goal = max(targetProgress, idleMin)
        shownProgress += (goal - shownProgress) * 0.06f
        if (shownProgress > 1f) shownProgress = 1f

        if (shownProgress > 0.01f) {
            var shader = barShader
            if (shader == null) {
                shader = LinearGradient(
                    barX, 0f, barX + barW, 0f,
                    intArrayOf(green, cyan), null, Shader.TileMode.CLAMP
                )
                barShader = shader
            }
            fillPaint.shader = shader
            canvas.save()
            canvas.clipRect(barX, barY, barX + barW * shownProgress, barY + barH)
            canvas.drawRoundRect(barX, barY, barX + barW, barY + barH, radius, radius, fillPaint)
            canvas.restore()
            fillPaint.shader = null
        }

        if (statusText.isNotEmpty()) {
            canvas.drawText(statusText, w / 2f, barY - 10f * density, textPaint)
        }
    }

    override fun onDraw(canvas: Canvas) {
        val t = convergeAnim?.animatedValue as? Float ?: 0f
        drawLetters(canvas, t * SplashTokens.CONVERGE_MS)
        drawStatus(canvas)
    }

    private class Cell(val col: Int, val tx: Float, val ty: Float, val color: Int, val level: Int)
    private class Particle(
        val sx: Float, val sy: Float, val tx: Float, val ty: Float,
        val delay: Float, val duration: Float, val color: Int, val level: Int
    )
}

package com.workspace.proot

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
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
import kotlin.math.sin

/**
 * 开屏动画：点阵飞入 + 品牌渐变 + 呼吸光晕。
 * 点阵默认 TERMLOU（SplashTokens.defaultCells），可传自定义 cells（启动工坊保存的 splash.json）。
 * @param customCells 自定义像素 (row, col)；null = 默认 LOGO
 * @param showProgress 是否显示进度条/状态文字（预览模式 false）
 */
class SplashView(context: Context, customCells: List<Pair<Int, Int>>? = null, private val showProgress: Boolean = true) : View(context) {

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
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
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

    private val breatheRunnable = object : Runnable {
        override fun run() {
            if (detached || dismissing) return
            invalidate()
            postDelayed(this, 16)
        }
    }

    private val startTime = System.currentTimeMillis()
    private var sawStatus = false
    private var targetProgress = 0.15f
    private var shownProgress = 0f

    private val glowBase = pixelSize * SplashTokens.GLOW_FACTOR

    var statusText: String = ""
        set(value) {
            field = value
            advanceProgress(value)
        }

    init {
        buildGrid(customCells)
        buildParticles()
        setupPaints()
        setBackgroundColor(Color.BLACK)
        layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        )
        startConverge()
        postDelayed(breatheRunnable, 16)
    }

    private fun buildGrid(customCells: List<Pair<Int, Int>>?) {
        val list = customCells ?: SplashTokens.defaultCells()
        for ((r, col) in list) {
            cells.add(
                Cell(
                    col,
                    offsetX + (col + 0.5f) * pixelSize,
                    offsetY + (r + 0.5f) * pixelSize,
                    SplashTokens.cellColor(col)
                )
            )
        }
    }

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
            particles.add(Particle(sx, sy, cell.tx, cell.ty, delay, duration, cell.color))
        }
    }

    private fun setupPaints() {
        particlePaint.style = Paint.Style.FILL
        glowPaint.style = Paint.Style.FILL
        solidPaint.style = Paint.Style.FILL
        bgPaint.style = Paint.Style.FILL
        trackPaint.color = UiTokens.whiteFaint
        textPaint.color = UiTokens.splashText
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
        particlePaint.color = SplashTokens.lerpAlpha(p.color, (60 + 180 * local).toInt())
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

    private fun drawLetters(canvas: Canvas, elapsed: Float) {
        for (i in particles.indices) {
            val p = particles[i]
            val local = pProgress(p, elapsed)
            if (local > 0f && local < 1f) drawParticle(canvas, p, local)
        }

        val radius = pixelSize * SplashTokens.PIXEL_RADIUS_FACTOR
        val breathe = 1f + 0.3f * sin(System.currentTimeMillis() * 0.004f)
        glowPaint.setShadowLayer(glowBase * breathe, 0f, 0f, UiTokens.letterGlow)
        for (i in particles.indices) {
            if (pProgress(particles[i], elapsed) >= 1f) {
                val cell = cells[i]
                val l = cell.tx - pixelSize * 0.5f
                val t = cell.ty - pixelSize * 0.5f
                glowPaint.color = SplashTokens.lerpAlpha(cell.color, 110)
                canvas.drawRoundRect(l - 1f, t - 1f, l + pixelSize + 1f, t + pixelSize + 1f, radius, radius, glowPaint)
            }
        }
        glowPaint.setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)

        var shader = gradientShader
        if (shader == null) {
            shader = LinearGradient(
                offsetX, 0f, offsetX + totalW, 0f,
                intArrayOf(green, cyan), null, Shader.TileMode.CLAMP
            )
            gradientShader = shader
        }
        solidPaint.shader = shader
        for (i in particles.indices) {
            if (pProgress(particles[i], elapsed) >= 1f) {
                val cell = cells[i]
                val l = cell.tx - pixelSize * 0.5f
                val t = cell.ty - pixelSize * 0.5f
                canvas.drawRoundRect(l, t, l + pixelSize, t + pixelSize, radius, radius, solidPaint)
            }
        }
        solidPaint.shader = null
    }

    private fun advanceProgress(text: String) {
        sawStatus = true
        val target = when (text) {
            "准备环境..." -> 0.30f
            "安装依赖..." -> 0.60f
            "启动终端..." -> 0.85f
            else -> min(0.95f, targetProgress + 0.10f)
        }
        if (target > targetProgress) targetProgress = target
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
        canvas.drawColor(Color.BLACK)
        drawBackdrop(canvas)
        val t = convergeAnim?.animatedValue as? Float ?: 0f
        drawLetters(canvas, t * SplashTokens.CONVERGE_MS)
        drawStatus(canvas)
    }

    private class Cell(val col: Int, val tx: Float, val ty: Float, val color: Int)
    private class Particle(
        val sx: Float, val sy: Float, val tx: Float, val ty: Float,
        val delay: Float, val duration: Float, val color: Int
    )
}

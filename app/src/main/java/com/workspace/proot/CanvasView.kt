package com.workspace.proot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 图片缓存：脚本只能读 App 能读到的路径/content URI，解码尺寸有上限。 */
class CanvasImageCache(private val ctx: Context) {
    private val cache = HashMap<String, Bitmap?>()
    private val failed = HashSet<String>()

    fun get(src: String): Bitmap? {
        if (src.isBlank()) return null
        if (cache.containsKey(src)) return cache[src]
        if (failed.contains(src)) return null
        val bmp = runCatching { load(src) }.getOrNull()
        if (bmp == null) failed.add(src) else cache[src] = bmp
        return bmp
    }

    private fun load(src: String): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val stream = { resolveStream(src) }
        stream()?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        var sample = 1
        while (opts.outWidth / sample > 1600 || opts.outHeight / sample > 1600) sample *= 2
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        return stream()?.use { BitmapFactory.decodeStream(it, null, decode) }
    }

    private fun resolveStream(src: String): java.io.InputStream? {
        return when {
            src.startsWith("content://") -> ctx.contentResolver.openInputStream(Uri.parse(src))
            src.startsWith("file://") -> File(Uri.parse(src).path ?: return null).inputStream()
            else -> File(src).inputStream()
        }
    }

    fun clear() {
        cache.values.forEach { it?.recycle() }
        cache.clear()
        failed.clear()
    }
}

/**
 * 原生 Canvas 宿主：渲染、动画时钟、原位 patch、命中/动作、设计态编辑。
 *
 * 运行时任何交互只改这里的场景状态并 invalidate，不写 req/res，不重建卡片。
 */
class CanvasHostView(
    context: Context,
    scene: CanvasScene = CanvasScene(),
    private val images: CanvasImageCache = CanvasImageCache(context)
) : View(context) {

    var scene: CanvasScene = scene
        set(value) {
            field = value
            invalidate()
        }

    var designMode: Boolean = false
        set(value) {
            field = value
            invalidate()
        }

    var showGrid: Boolean = true
    var snapToGrid: Boolean = true
    var gridStep: Float = 8f
    var selectedId: String? = null
        private set

    var onSceneChanged: ((CanvasScene) -> Unit)? = null
    var onSelectionChanged: ((String?) -> Unit)? = null
    var onAction: ((String) -> Unit)? = null
    /** submit:ID / submit:ID:keep —— 点击后把结果交回现有弹窗结果链路。 */
    var onSubmit: ((id: String, keepOpen: Boolean) -> Unit)? = null
    var onEditStart: (() -> Unit)? = null

    private var submitLocked = false

    private val d get() = resources.displayMetrics.density
    private val density get() = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val path = Path()

    private var playing = true
    private var baseMs = 0L
    private var startedAt = SystemClock.uptimeMillis()
    private var lastFrameMs = 0L
    private var viewScale = 1f
    private var offsetX = 0f
    private var offsetY = 0f

    private data class Transient(
        val nodeId: String,
        val prop: String,
        val from: Float?,
        val to: Float?,
        val fromColor: Int?,
        val toColor: Int?,
        val startMs: Long,
        val durationMs: Int,
        val easing: String
    )

    private val transients = mutableListOf<Transient>()

    private enum class DragMode { NONE, MOVE, SCALE_TL, SCALE_TR, SCALE_BL, SCALE_BR, ROTATE, POINT }

    private var dragMode = DragMode.NONE
    private var dragStartSceneX = 0f
    private var dragStartSceneY = 0f
    private var dragStartNode: CanvasNode? = null
    private var dragPointIndex = -1
    private var dragStartAngle = 0f
    private var pressedNodeId: String? = null

    init {
        // 阴影、虚线、渐变在软件层最稳；画布本身很小，性能可接受。
        setLayerType(LAYER_TYPE_SOFTWARE, null)
        isClickable = true
        isFocusable = false
    }

    fun setScene(newScene: CanvasScene, resetClock: Boolean = false) {
        scene = newScene
        if (resetClock) {
            baseMs = 0
            startedAt = SystemClock.uptimeMillis()
        }
        if (selectedId != null && newScene.findNode(selectedId!!) == null) {
            selectedId = null
            onSelectionChanged?.invoke(null)
        }
        invalidate()
    }

    fun snapshotJson(): String = CanvasSceneCodec.sceneToJsonString(scene)

    fun select(id: String?) {
        selectedId = id
        onSelectionChanged?.invoke(id)
        invalidate()
    }

    fun play() {
        if (!playing) {
            startedAt = SystemClock.uptimeMillis()
            playing = true
        }
        invalidate()
    }

    fun pause() {
        if (playing) {
            baseMs = currentTimeMs()
            playing = false
        }
        invalidate()
    }

    fun seek(ms: Long) {
        baseMs = ms.coerceAtLeast(0)
        startedAt = SystemClock.uptimeMillis()
        invalidate()
    }

    fun isPlaying(): Boolean = playing

    fun currentTimeMs(): Long = if (playing) baseMs + (SystemClock.uptimeMillis() - startedAt) else baseMs

    /**
     * 应用一条 canvas patch：只改当前视图的场景/时钟，不重建弹窗。
     * @return 是否命中目标节点（全场景替换时恒为 true）。
     */
    fun applyPatch(patch: CanvasPatch): Boolean {
        if (patch.scene != null) {
            setScene(patch.scene)
            return true
        }
        patch.play?.let { if (it) play() else pause() }
        patch.seekMs?.let { seek(it) }
        val nodeId = patch.node
        if (nodeId == null || patch.props.isEmpty()) {
            invalidate()
            return true
        }
        val result = patch.applyTo(scene)
        scene = result.scene
        val now = currentTimeMs()
        if (patch.animate) {
            val dur = patch.durationMs ?: 320
            for (c in result.changes) {
                if (c.from != null && c.to != null) {
                    transients.add(Transient(c.nodeId, c.property, c.from, c.to, null, null, now, dur, patch.easing))
                } else if (c.fromColor != null && c.toColor != null) {
                    transients.add(Transient(c.nodeId, c.property, null, null, c.fromColor, c.toColor, now, dur, patch.easing))
                }
            }
        }
        if (result.nodeFound) {
            selectedId?.let { selected ->
                if (scene.findNode(selected) == null) select(null)
            }
        }
        invalidate()
        return result.nodeFound
    }

    /** 新请求到来时解锁；同一请求内只允许提交一次。 */
    fun resetSubmitLock() {
        submitLocked = false
    }

    fun performAction(action: String) {
        if (action.isBlank()) return
        CanvasAction.parseSubmit(action)?.let { submit ->
            if (submitLocked) return
            submitLocked = true
            onSubmit?.invoke(submit.id, submit.keepOpen)
            return
        }
        val parts = action.split(":")
        when (parts[0]) {
            "toggle" -> {
                val id = parts.getOrNull(1) ?: return
                val n = scene.findNode(id) ?: return
                setScene(scene.updateNode(id) { n.copy(visible = !n.visible) })
            }
            "show" -> {
                val id = parts.getOrNull(1) ?: return
                setScene(scene.updateNode(id) { it.copy(visible = true) })
            }
            "hide" -> {
                val id = parts.getOrNull(1) ?: return
                setScene(scene.updateNode(id) { it.copy(visible = false) })
            }
            "progress" -> {
                val id = parts.getOrNull(1) ?: return
                val v = parts.getOrNull(2)?.toFloatOrNull() ?: return
                setScene(scene.updateNode(id) { it.copy(progress = CanvasSpec.clamp(v, 0f, 1f)) })
            }
            "seek" -> {
                parts.getOrNull(1)?.toLongOrNull()?.let { seek(it) }
            }
            "play" -> play()
            "pause" -> pause()
            "select" -> select(parts.getOrNull(1))
        }
        onAction?.invoke(action)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis() - baseMs
        invalidate()
    }

    override fun onDetachedFromWindow() {
        transients.clear()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width <= 0 || height <= 0) return
        updateTransform()
        val frame = CanvasAnimator.evaluate(scene, currentTimeMs())
        val animatedRoots = applyTransients(frame.nodes)
        drawBackground(canvas)
        if (designMode && showGrid) drawGrid(canvas)
        canvas.save()
        val w = scene.width.coerceAtLeast(1f)
        val h = scene.height.coerceAtLeast(1f)
        val s = viewScale
        canvas.translate(offsetX, offsetY)
        canvas.scale(s, s)
        val cam = frame.cameraZoom.coerceIn(0.05f, 20f)
        canvas.translate(w / 2f, h / 2f)
        canvas.scale(cam, cam)
        canvas.translate(-w / 2f - frame.cameraX, -h / 2f - frame.cameraY)
        for (n in animatedRoots) drawNode(canvas, n, 1f)
        canvas.restore()
        if (scene.particles.count > 0) drawParticles(canvas, frame.timeMs)
        if (designMode) drawSelection(canvas)
        transients.removeAll { currentTimeMs() - it.startMs > it.durationMs }
        if (playing && (scene.animations.isNotEmpty() || scene.particles.count > 0 || transients.isNotEmpty())) {
            lastFrameMs = frame.timeMs
            postInvalidateOnAnimation()
        }
    }

    private fun applyTransients(nodes: List<CanvasNode>): List<CanvasNode> {
        if (transients.isEmpty()) return nodes
        val now = currentTimeMs()
        fun walk(list: List<CanvasNode>): List<CanvasNode> = list.map { n ->
            var out = n
            for (t in transients) {
                if (t.nodeId != n.id) continue
                val p = if (t.durationMs <= 0) 1f else ((now - t.startMs).toFloat() / t.durationMs).coerceIn(0f, 1f)
                val e = CanvasEasing.eval(p, t.easing)
                if (t.from != null && t.to != null) {
                    out = out.withNumber(t.prop, CanvasSpec.lerp(t.from, t.to, e))
                } else if (t.fromColor != null && t.toColor != null) {
                    out = out.withString(t.prop, CanvasSpec.colorHex(CanvasSpec.lerpColor(t.fromColor, t.toColor, e)))
                }
            }
            if (n.children.isNotEmpty()) out = out.copy(children = walk(n.children))
            out
        }
        return walk(nodes)
    }

    private fun updateTransform() {
        val w = scene.width.coerceAtLeast(1f)
        val h = scene.height.coerceAtLeast(1f)
        val vw = width.toFloat()
        val vh = height.toFloat()
        val fit = min(vw / w, vh / h)
        viewScale = fit
        offsetX = (vw - w * fit) / 2f
        offsetY = (vh - h * fit) / 2f
    }

    private fun drawBackground(canvas: Canvas) {
        val c = CanvasSpec.color(scene.background, 0)
        if (Color.alpha(c) > 0) {
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.color = c
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        }
    }

    private fun drawGrid(canvas: Canvas) {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = 0x22FFFFFF
        val step = (gridStep * viewScale).coerceAtLeast(4f)
        val r = (0.7f * d).coerceAtLeast(0.5f)
        var x = offsetX
        while (x <= offsetX + scene.width * viewScale) {
            var y = offsetY
            while (y <= offsetY + scene.height * viewScale) {
                canvas.drawCircle(x, y, r, paint)
                y += step
            }
            x += step
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = (1f * d).coerceAtLeast(1f)
        paint.color = 0x33FFFFFF
        canvas.drawRect(offsetX, offsetY, offsetX + scene.width * viewScale, offsetY + scene.height * viewScale, paint)
    }

    private fun drawNode(canvas: Canvas, n: CanvasNode, inheritedAlpha: Float) {
        if (!n.visible || n.alpha <= 0.001f) return
        val alpha = (n.alpha * inheritedAlpha).coerceIn(0f, 1f)
        canvas.save()
        if (n.isGroup) {
            canvas.translate(n.x, n.y)
            val cx = n.w / 2f
            val cy = n.h / 2f
            canvas.translate(cx, cy)
            canvas.rotate(n.rotation)
            canvas.scale(n.scaleX, n.scaleY)
            canvas.translate(-cx, -cy)
        } else {
            val cx = n.x + n.w / 2f
            val cy = n.y + n.h / 2f
            canvas.translate(cx, cy)
            canvas.rotate(n.rotation)
            canvas.scale(n.scaleX, n.scaleY)
            canvas.translate(-cx, -cy)
        }
        when (n.type) {
            CanvasSpec.TYPE_GROUP -> for (c in n.children) drawNode(canvas, c, alpha)
            CanvasSpec.TYPE_TEXT -> drawText(canvas, n, alpha)
            CanvasSpec.TYPE_IMAGE -> drawImage(canvas, n, alpha)
            CanvasSpec.TYPE_PROGRESS -> drawProgress(canvas, n, alpha)
            CanvasSpec.TYPE_LINE -> drawLineNode(canvas, n, alpha)
            CanvasSpec.TYPE_PATH -> drawPathNode(canvas, n, alpha)
            CanvasSpec.TYPE_CIRCLE, CanvasSpec.TYPE_ELLIPSE -> drawOval(canvas, n, alpha)
            else -> drawRect(canvas, n, alpha)
        }
        canvas.restore()
    }

    private fun basePaint(n: CanvasNode, alpha: Float, fill: Boolean): Paint {
        paint.reset()
        paint.isAntiAlias = true
        paint.style = if (fill) Paint.Style.FILL else Paint.Style.STROKE
        paint.color = CanvasSpec.color(if (fill) n.fill else n.stroke, if (fill) 0 else 0xFFE6E6E6.toInt())
        paint.alpha = (Color.alpha(paint.color) * alpha).roundToInt().coerceIn(0, 255)
        if (!fill) paint.strokeWidth = n.strokeWidth.coerceAtLeast(1f)
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeJoin = Paint.Join.ROUND
        return paint
    }

    private fun drawRect(canvas: Canvas, n: CanvasNode, alpha: Float) {
        rect.set(n.x, n.y, n.x + n.w, n.y + n.h)
        val round = n.type == CanvasSpec.TYPE_ROUND_RECT || n.radius > 0f
        if (n.hasFill) {
            val p = basePaint(n, alpha, true)
            if (n.shadow) p.setShadowLayer(n.shadowRadius, 0f, n.shadowRadius / 3f, 0x66000000)
            if (round) canvas.drawRoundRect(rect, n.radius, n.radius, p) else canvas.drawRect(rect, p)
        }
        if (n.hasStroke) {
            val p = basePaint(n, alpha, false)
            if (n.dash > 0f && n.dashGap > 0f) {
                p.pathEffect = DashPathEffect(floatArrayOf(n.dash, n.dashGap), n.dashOffset)
            }
            if (round) canvas.drawRoundRect(rect, n.radius, n.radius, p) else canvas.drawRect(rect, p)
        }
    }

    private fun drawOval(canvas: Canvas, n: CanvasNode, alpha: Float) {
        rect.set(n.x, n.y, n.x + n.w, n.y + n.h)
        if (n.hasFill) canvas.drawOval(rect, basePaint(n, alpha, true))
        if (n.hasStroke) {
            val p = basePaint(n, alpha, false)
            if (n.dash > 0f && n.dashGap > 0f) p.pathEffect = DashPathEffect(floatArrayOf(n.dash, n.dashGap), n.dashOffset)
            canvas.drawOval(rect, p)
        }
    }

    private fun drawLineNode(canvas: Canvas, n: CanvasNode, alpha: Float) {
        val p = basePaint(n, alpha, false)
        p.strokeWidth = if (n.strokeWidth <= 0f) 2f else n.strokeWidth
        if (n.dash > 0f && n.dashGap > 0f) p.pathEffect = DashPathEffect(floatArrayOf(n.dash, n.dashGap), n.dashOffset)
        if (n.points.size >= 2) {
            for (i in 1 until n.points.size) {
                canvas.drawLine(
                    n.x + n.points[i - 1].x, n.y + n.points[i - 1].y,
                    n.x + n.points[i].x, n.y + n.points[i].y, p
                )
            }
        } else {
            canvas.drawLine(n.x, n.y, n.x + n.w, n.y + n.h, p)
        }
    }

    private fun drawPathNode(canvas: Canvas, n: CanvasNode, alpha: Float) {
        val pts = if (n.points.isNotEmpty()) n.points else CanvasPath.parse(n.d).points
        if (pts.size < 2) return
        val built = buildTrimmedPath(pts, n.trim)
        canvas.save()
        canvas.translate(n.x, n.y)
        if (n.hasFill && n.closed) {
            val fillPath = Path(built).apply { close() }
            canvas.drawPath(fillPath, basePaint(n, alpha, true))
        }
        val p = basePaint(n, alpha, false)
        p.strokeWidth = if (n.strokeWidth <= 0f) 2f else n.strokeWidth
        if (n.dash > 0f && n.dashGap > 0f) p.pathEffect = DashPathEffect(floatArrayOf(n.dash, n.dashGap), n.dashOffset)
        canvas.drawPath(built, p)
        canvas.restore()
    }

    private fun buildTrimmedPath(points: List<CanvasPoint>, trim: Float): Path {
        val p = Path()
        if (points.isEmpty()) return p
        p.moveTo(points[0].x, points[0].y)
        var total = 0f
        val lens = FloatArray(points.size)
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            total += hypot(dx, dy)
            lens[i] = total
        }
        val stop = total * trim.coerceIn(0f, 1f)
        for (i in 1 until points.size) {
            if (lens[i] <= stop) {
                p.lineTo(points[i].x, points[i].y)
            } else {
                val seg = lens[i] - lens[i - 1]
                val t = if (seg <= 0.0001f) 0f else (stop - lens[i - 1]) / seg
                if (t > 0f) {
                    p.lineTo(
                        CanvasSpec.lerp(points[i - 1].x, points[i].x, t),
                        CanvasSpec.lerp(points[i - 1].y, points[i].y, t)
                    )
                }
                break
            }
        }
        return p
    }

    private fun drawText(canvas: Canvas, n: CanvasNode, alpha: Float) {
        if (n.text.isEmpty()) return
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.color = CanvasSpec.color(n.textColor, 0xFFE6E6E6.toInt())
        textPaint.alpha = (Color.alpha(textPaint.color) * alpha).roundToInt().coerceIn(0, 255)
        val base = if (n.monospace) Typeface.MONOSPACE else Typeface.DEFAULT
        textPaint.typeface = when {
            n.bold && n.italic -> Typeface.create(base, Typeface.BOLD_ITALIC)
            n.bold -> Typeface.create(base, Typeface.BOLD)
            n.italic -> Typeface.create(base, Typeface.ITALIC)
            else -> base
        }
        textPaint.textSize = n.textSize.coerceIn(1f, 512f)
        textPaint.textAlign = when (n.textAlign) {
            CanvasSpec.ALIGN_CENTER -> Paint.Align.CENTER
            CanvasSpec.ALIGN_RIGHT -> Paint.Align.RIGHT
            else -> Paint.Align.LEFT
        }
        val lines = n.text.split('\n')
        val lh = n.textSize * 1.3f
        val x = when (n.textAlign) {
            CanvasSpec.ALIGN_CENTER -> n.x + n.w / 2f
            CanvasSpec.ALIGN_RIGHT -> n.x + n.w
            else -> n.x
        }
        val maxLines = lines.size.coerceAtMost(200)
        for (i in 0 until maxLines) {
            canvas.drawText(lines[i], x, n.y + n.textSize + i * lh, textPaint)
        }
    }

    private fun drawImage(canvas: Canvas, n: CanvasNode, alpha: Float) {
        val bmp = images.get(n.src) ?: return
        rect.set(n.x, n.y, n.x + n.w, n.y + n.h)
        if (n.w <= 0f || n.h <= 0f) return
        paint.reset()
        paint.isAntiAlias = true
        paint.alpha = (alpha * 255).roundToInt().coerceIn(0, 255)
        val tint = CanvasSpec.color(n.tint, 0)
        if (Color.alpha(tint) > 0) paint.colorFilter = android.graphics.PorterDuffColorFilter(tint, android.graphics.PorterDuff.Mode.SRC_IN)
        val src = android.graphics.Rect(0, 0, bmp.width, bmp.height)
        val dst = RectF(rect)
        when (n.fit) {
            CanvasSpec.FIT_COVER -> {
                val sc = maxOf(n.w / bmp.width, n.h / bmp.height)
                val sw = (n.w / sc).toInt()
                val sh = (n.h / sc).toInt()
                src.set((bmp.width - sw) / 2, (bmp.height - sh) / 2, (bmp.width + sw) / 2, (bmp.height + sh) / 2)
            }
            CanvasSpec.FIT_STRETCH -> Unit
            else -> {
                val sc = min(n.w / bmp.width, n.h / bmp.height)
                val dw = bmp.width * sc
                val dh = bmp.height * sc
                dst.set(n.x + (n.w - dw) / 2f, n.y + (n.h - dh) / 2f, n.x + (n.w + dw) / 2f, n.y + (n.h + dh) / 2f)
            }
        }
        canvas.drawBitmap(bmp, src, dst, paint)
    }

    private fun drawProgress(canvas: Canvas, n: CanvasNode, alpha: Float) {
        val r = n.radius.coerceIn(0f, n.h / 2f)
        rect.set(n.x, n.y, n.x + n.w, n.y + n.h)
        val track = basePaint(n, alpha, true).apply { color = CanvasSpec.color(n.trackColor, 0x33FFFFFF) }
        track.alpha = (Color.alpha(track.color) * alpha).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(rect, r, r, track)
        val pw = n.w * n.progress.coerceIn(0f, 1f)
        if (pw > 0f) {
            val bar = RectF(n.x, n.y, n.x + pw, n.y + n.h)
            val barPaint = basePaint(n, alpha, true)
            barPaint.color = CanvasSpec.color(n.barColor, 0xFF2D7D46.toInt())
            val gf = CanvasSpec.color(n.gradientFrom, 0)
            val gt = CanvasSpec.color(n.gradientTo, 0)
            if (Color.alpha(gf) > 0 && Color.alpha(gt) > 0) {
                barPaint.shader = LinearGradient(n.x, n.y, n.x + n.w, n.y, gf, gt, Shader.TileMode.CLAMP)
            }
            barPaint.alpha = (255 * alpha).roundToInt().coerceIn(0, 255)
            canvas.drawRoundRect(bar, min(r, pw / 2f), min(r, pw / 2f), barPaint)
        }
        if (n.showLabel) {
            textPaint.reset()
            textPaint.isAntiAlias = true
            textPaint.color = CanvasSpec.color(n.labelColor, 0xFFE6E6E6.toInt())
            textPaint.alpha = (Color.alpha(textPaint.color) * alpha).roundToInt().coerceIn(0, 255)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = n.labelSize.coerceIn(1f, 512f)
            val label = n.labelFormat
                .replace("{percent}", (n.progress * 100).roundToInt().toString())
                .replace("{value}", n.progress.toString())
            val baseline = n.y + n.h / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
            canvas.drawText(label, n.x + n.w / 2f, baseline, textPaint)
        }
    }

    private fun drawParticles(canvas: Canvas, timeMs: Long) {
        val p = scene.particles
        if (p.count <= 0) return
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = CanvasSpec.color(p.color, 0x66FFFFFF)
        val n = p.count.coerceIn(0, 300)
        val t = timeMs / 1000f
        for (i in 0 until n) {
            val seed = (p.seed * 31L + i * 2654435761L)
            val rx = (((seed * 1103515245L + 12345L) and 0x7FFFFFFFL).toFloat() / 2147483647f)
            val ry = (((seed * 1664525L + 1013904223L) and 0x7FFFFFFFL).toFloat() / 2147483647f)
            val speed = p.speed * (0.35f + rx)
            val x = ((rx * (scene.width + 40f) + sin(t * speed * 0.08f + i) * p.drift * 12f) %
                (scene.width + 40f)) - 20f
            val y = ((ry * (scene.height + 40f) - t * speed) % (scene.height + 40f) + (scene.height + 40f)) %
                (scene.height + 40f) - 20f
            val r = p.size * (0.5f + ry)
            canvas.drawCircle(offsetX + (x * viewScale), offsetY + (y * viewScale), r * viewScale, paint)
        }
    }

    // ===== 设计态：选择框、手柄、拖拽/缩放/旋转/路径点 =====

    /** 从根到直接父级的祖先链；嵌套 group 的变换按这个顺序叠加。 */
    private fun ancestorsOf(id: String): List<CanvasNode> {
        val chains = HashMap<String, List<CanvasNode>>()
        fun index(list: List<CanvasNode>, chain: List<CanvasNode>) {
            for (n in list) {
                chains[n.id] = chain
                if (n.children.isNotEmpty()) index(n.children, chain + n)
            }
        }
        index(scene.nodes, emptyList())
        return chains[id] ?: emptyList()
    }

    /** 节点自身变换：局部坐标 → 父级坐标（与 drawNode 的旋转/缩放顺序一致）。 */
    private fun selfToParent(x: Float, y: Float, n: CanvasNode): Pair<Float, Float> {
        val cx = n.x + n.w / 2f
        val cy = n.y + n.h / 2f
        val dx = (x - cx) * n.scaleX
        val dy = (y - cy) * n.scaleY
        if (n.rotation == 0f) return (cx + dx) to (cy + dy)
        val a = Math.toRadians(n.rotation.toDouble())
        return (cx + dx * cos(a) - dy * sin(a)).toFloat() to
            (cy + dx * sin(a) + dy * cos(a)).toFloat()
    }

    /** 节点自身变换的逆映射：父级坐标 → 局部坐标。 */
    private fun parentToSelf(x: Float, y: Float, n: CanvasNode): Pair<Float, Float> {
        val cx = n.x + n.w / 2f
        val cy = n.y + n.h / 2f
        var dx = x - cx
        var dy = y - cy
        if (n.rotation != 0f) {
            val a = Math.toRadians(-n.rotation.toDouble())
            val rx = (dx * cos(a) - dy * sin(a)).toFloat()
            val ry = (dx * sin(a) + dy * cos(a)).toFloat()
            dx = rx
            dy = ry
        }
        val sx = if (abs(n.scaleX) < 0.001f) 1f else n.scaleX
        val sy = if (abs(n.scaleY) < 0.001f) 1f else n.scaleY
        return (cx + dx / sx) to (cy + dy / sy)
    }

    /** 节点局部坐标（相对 x/y）→ 场景坐标；逐层叠加自身和祖先变换。 */
    private fun nodeLocalToScene(
        x: Float,
        y: Float,
        n: CanvasNode,
        ancestors: List<CanvasNode>
    ): Pair<Float, Float> {
        var p = selfToParent(x + n.x, y + n.y, n)
        for (a in ancestors.asReversed()) p = selfToParent(p.first, p.second, a)
        return p
    }

    private fun sceneToNodeLocal(
        x: Float,
        y: Float,
        n: CanvasNode,
        ancestors: List<CanvasNode>
    ): Pair<Float, Float> {
        var px = x
        var py = y
        // 祖先链是根 → 直接父级，逆变换必须按同样顺序逐层剥。
        for (a in ancestors) {
            val p = parentToSelf(px, py, a)
            px = p.first
            py = p.second
        }
        val local = parentToSelf(px, py, n)
        return (local.first - n.x) to (local.second - n.y)
    }

    /** 场景坐标 → 节点父级坐标（不含节点自身变换）。 */
    private fun sceneToParent(x: Float, y: Float, ancestors: List<CanvasNode>): Pair<Float, Float> {
        var px = x
        var py = y
        for (a in ancestors) {
            val p = parentToSelf(px, py, a)
            px = p.first
            py = p.second
        }
        return px to py
    }

    private fun drawSelection(canvas: Canvas) {
        val id = selectedId ?: return
        val n = scene.findNode(id) ?: return
        val ancestors = ancestorsOf(id)
        val corners = cornersOf(n, ancestors)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = (1.5f * d).coerceAtLeast(1f)
            color = 0xFF4DD0E1.toInt()
        }
        val box = Path()
        box.moveTo(corners[0].first, corners[0].second)
        for (i in 1 until corners.size) box.lineTo(corners[i].first, corners[i].second)
        box.close()
        canvas.drawPath(box, p)
        val handle = (4f * d).coerceAtLeast(4f)
        val hp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = 0xFF4DD0E1.toInt()
        }
        for (c in corners) canvas.drawCircle(c.first, c.second, handle, hp)
        val topLocal = nodeLocalToScene(n.w / 2f, -28f / viewScale.coerceAtLeast(0.01f), n, ancestors)
        val top = toView(topLocal.first, topLocal.second)
        canvas.drawLine(corners[0].first, corners[0].second, top.first, top.second, p)
        canvas.drawCircle(top.first, top.second, handle, hp)
        if (n.type == CanvasSpec.TYPE_PATH) {
            for (pt in n.points) {
                val scenePt = nodeLocalToScene(pt.x, pt.y, n, ancestors)
                val vp = toView(scenePt.first, scenePt.second)
                canvas.drawCircle(vp.first, vp.second, (3.5f * d).coerceAtLeast(3f), hp)
            }
        }
    }

    private data class Hit(val node: CanvasNode, val depth: Int)

    private fun findHit(x: Float, y: Float): CanvasNode? {
        fun walk(
            list: List<CanvasNode>,
            depth: Int,
            ancestors: List<CanvasNode>,
            out: MutableList<Hit>
        ) {
            for (n in list) {
                if (!n.visible || !n.hitTest) continue
                val (lx, ly) = sceneToNodeLocal(x, y, n, ancestors)
                if (n.isGroup) {
                    if (inBounds(n, lx, ly)) out.add(Hit(n, depth))
                    walk(n.children, depth + 1, ancestors + n, out)
                } else if (containsLocal(n, lx, ly)) {
                    out.add(Hit(n, depth))
                }
            }
        }
        val hits = mutableListOf<Hit>()
        walk(scene.nodes, 0, emptyList(), hits)
        val order = scene.flatten().withIndex().associate { it.value.id to it.index }
        return hits.maxWithOrNull(
            compareBy<Hit> { it.depth }.thenBy { order[it.node.id] ?: -1 }
        )?.node
    }

    private fun containsLocal(n: CanvasNode, lx: Float, ly: Float): Boolean = when (n.type) {
        CanvasSpec.TYPE_CIRCLE, CanvasSpec.TYPE_ELLIPSE -> inOval(n, lx, ly)
        CanvasSpec.TYPE_LINE, CanvasSpec.TYPE_PATH -> nearPath(n, lx, ly)
        else -> inBounds(n, lx, ly)
    }

    private fun inBounds(n: CanvasNode, lx: Float, ly: Float): Boolean =
        lx >= 0f && lx <= n.w && ly >= 0f && ly <= n.h

    private fun inOval(n: CanvasNode, lx: Float, ly: Float): Boolean {
        val rx = n.w / 2f
        val ry = n.h / 2f
        if (rx <= 0f || ry <= 0f) return false
        val dx = (lx - rx) / rx
        val dy = (ly - ry) / ry
        return dx * dx + dy * dy <= 1f
    }

    private fun nearPath(n: CanvasNode, lx: Float, ly: Float): Boolean {
        val pts = if (n.points.isNotEmpty()) n.points else CanvasPath.parse(n.d).points
        val tolerance = n.strokeWidth.coerceAtLeast(2f) / 2f + 8f
        var hit = false
        var i = 1
        while (i < pts.size && !hit) {
            hit = distToSegment(lx, ly, pts[i - 1].x, pts[i - 1].y, pts[i].x, pts[i].y) <= tolerance
            i++
        }
        return if (pts.size >= 2) hit else inBounds(n, lx, ly)
    }

    private fun distToSegment(px: Float, py: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x2 - x1
        val dy = y2 - y1
        val len = dx * dx + dy * dy
        if (len <= 0.0001f) return hypot(px - x1, py - y1)
        var t = ((px - x1) * dx + (py - y1) * dy) / len
        t = t.coerceIn(0f, 1f)
        return hypot(px - (x1 + t * dx), py - (y1 + t * dy))
    }

    private fun toScene(x: Float, y: Float): Pair<Float, Float> {
        val s = viewScale.coerceAtLeast(0.0001f)
        var sx = (x - offsetX) / s
        var sy = (y - offsetY) / s
        val cam = scene.cameraZoom.coerceIn(0.05f, 20f)
        if (cam != 1f || scene.cameraX != 0f || scene.cameraY != 0f) {
            sx = scene.width / 2f + (sx - scene.width / 2f) / cam + scene.cameraX
            sy = scene.height / 2f + (sy - scene.height / 2f) / cam + scene.cameraY
        }
        return sx to sy
    }

    private fun toView(x: Float, y: Float): Pair<Float, Float> {
        var sx = x
        var sy = y
        val cam = scene.cameraZoom.coerceIn(0.05f, 20f)
        if (cam != 1f || scene.cameraX != 0f || scene.cameraY != 0f) {
            sx = scene.width / 2f + (sx - scene.width / 2f - scene.cameraX) * cam
            sy = scene.height / 2f + (sy - scene.height / 2f - scene.cameraY) * cam
        }
        return offsetX + sx * viewScale to offsetY + sy * viewScale
    }

    private fun cornersOf(n: CanvasNode, ancestors: List<CanvasNode>): List<Pair<Float, Float>> {
        val local = listOf(0f to 0f, n.w to 0f, n.w to n.h, 0f to n.h)
        return local.map { (lx, ly) ->
            val p = nodeLocalToScene(lx, ly, n, ancestors)
            toView(p.first, p.second)
        }
    }

    private fun hitHandle(x: Float, y: Float): DragMode {
        val n = scene.findNode(selectedId ?: return DragMode.NONE) ?: return DragMode.NONE
        val ancestors = ancestorsOf(n.id)
        val corners = cornersOf(n, ancestors)
        val rad = (14f * d).coerceAtLeast(12f)
        val modes = listOf(DragMode.SCALE_TL, DragMode.SCALE_TR, DragMode.SCALE_BR, DragMode.SCALE_BL)
        for (i in corners.indices) {
            if (hypot(x - corners[i].first, y - corners[i].second) <= rad) return modes[i]
        }
        val topLocal = nodeLocalToScene(n.w / 2f, -28f / viewScale.coerceAtLeast(0.01f), n, ancestors)
        val top = toView(topLocal.first, topLocal.second)
        if (hypot(x - top.first, y - top.second) <= rad) return DragMode.ROTATE
        if (n.type == CanvasSpec.TYPE_PATH) {
            for (pt in n.points) {
                val local = nodeLocalToScene(pt.x, pt.y, n, ancestors)
                val v = toView(local.first, local.second)
                if (hypot(x - v.first, y - v.second) <= rad) return DragMode.POINT
            }
        }
        return DragMode.NONE
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!designMode) return runtimeTouch(event)
        val (sx, sy) = toScene(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragStartSceneX = sx
                dragStartSceneY = sy
                val hm = hitHandle(event.x, event.y)
                if (hm == DragMode.POINT) {
                    val n = scene.findNode(selectedId ?: "") ?: return true
                    val ancestors = ancestorsOf(n.id)
                    var best = -1
                    var bestD = Float.MAX_VALUE
                    for (i in n.points.indices) {
                        val p = nodeLocalToScene(n.points[i].x, n.points[i].y, n, ancestors)
                        val v = toView(p.first, p.second)
                        val dd = hypot(event.x - v.first, event.y - v.second)
                        if (dd < bestD) { bestD = dd; best = i }
                    }
                    dragPointIndex = best
                    dragMode = if (best >= 0) DragMode.POINT else DragMode.NONE
                    dragStartNode = n
                    onEditStart?.invoke()
                    return true
                }
                if (hm != DragMode.NONE) {
                    dragMode = hm
                    val n = scene.findNode(selectedId ?: "") ?: return true
                    dragStartNode = n
                    val (px, py) = sceneToParent(sx, sy, ancestorsOf(n.id))
                    val cx = n.x + n.w / 2f
                    val cy = n.y + n.h / 2f
                    dragStartAngle = Math.toDegrees(atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()
                    onEditStart?.invoke()
                    return true
                }
                val hit = findHit(sx, sy)
                select(hit?.id)
                if (hit != null && !hit.locked) {
                    dragMode = DragMode.MOVE
                    dragStartNode = hit
                    onEditStart?.invoke()
                } else {
                    dragMode = DragMode.NONE
                    dragStartNode = null
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val start = dragStartNode ?: return true
                val ancestors = ancestorsOf(start.id)
                val (px, py) = sceneToParent(sx, sy, ancestors)
                val (startPx, startPy) = sceneToParent(dragStartSceneX, dragStartSceneY, ancestors)
                when (dragMode) {
                    DragMode.MOVE -> {
                        val dx = px - startPx
                        val dy = py - startPy
                        var nx = start.x + dx
                        var ny = start.y + dy
                        if (snapToGrid) {
                            nx = (nx / gridStep).roundToInt() * gridStep
                            ny = (ny / gridStep).roundToInt() * gridStep
                        }
                        val updated = start.copy(x = nx, y = ny)
                        scene = scene.updateNode(start.id) { updated }
                        invalidate()
                    }
                    DragMode.SCALE_TL, DragMode.SCALE_TR, DragMode.SCALE_BL, DragMode.SCALE_BR -> {
                        val dx = px - startPx
                        val dy = py - startPy
                        var x = start.x
                        var y = start.y
                        var w = start.w
                        var h = start.h
                        when (dragMode) {
                            DragMode.SCALE_BR -> { w += dx; h += dy }
                            DragMode.SCALE_TL -> { x += dx; y += dy; w -= dx; h -= dy }
                            DragMode.SCALE_TR -> { y += dy; w += dx; h -= dy }
                            DragMode.SCALE_BL -> { x += dx; w -= dx; h += dy }
                            else -> Unit
                        }
                        if (snapToGrid) {
                            x = (x / gridStep).roundToInt() * gridStep
                            y = (y / gridStep).roundToInt() * gridStep
                            w = (w / gridStep).roundToInt() * gridStep
                            h = (h / gridStep).roundToInt() * gridStep
                        }
                        w = w.coerceAtLeast(gridStep)
                        h = h.coerceAtLeast(gridStep)
                        val scaled = if (start.type == CanvasSpec.TYPE_PATH || start.type == CanvasSpec.TYPE_LINE) {
                            start.scaledLocalPoints(w, h)
                        } else start.points
                        scene = scene.updateNode(start.id) {
                            it.copy(x = x, y = y, w = w, h = h, points = scaled)
                        }
                        invalidate()
                    }
                    DragMode.ROTATE -> {
                        val cx = start.x + start.w / 2f
                        val cy = start.y + start.h / 2f
                        val angle = Math.toDegrees(atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()
                        var rot = start.rotation + angle - dragStartAngle
                        if (snapToGrid) rot = (rot / 15f).roundToInt() * 15f
                        scene = scene.updateNode(start.id) { it.copy(rotation = rot) }
                        invalidate()
                    }
                    DragMode.POINT -> {
                        val idx = dragPointIndex
                        if (idx in start.points.indices) {
                            var tx = sx
                            var ty = sy
                            if (snapToGrid) {
                                tx = (tx / gridStep).roundToInt() * gridStep
                                ty = (ty / gridStep).roundToInt() * gridStep
                            }
                            val (lx, ly) = sceneToNodeLocal(tx, ty, start, ancestors)
                            val pts = start.points.toMutableList()
                            pts[idx] = CanvasPoint(lx, ly)
                            scene = scene.updateNode(start.id) { it.copy(points = pts, d = "") }
                            invalidate()
                        }
                    }
                    else -> Unit
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasEditing = dragMode != DragMode.NONE
                dragMode = DragMode.NONE
                dragStartNode = null
                dragPointIndex = -1
                if (wasEditing) onSceneChanged?.invoke(scene)
                return true
            }
        }
        return true
    }

    private fun runtimeTouch(event: MotionEvent): Boolean {
        val (sx, sy) = toScene(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressedNodeId = findHit(sx, sy)?.id
                return pressedNodeId != null
            }
            MotionEvent.ACTION_UP -> {
                val id = pressedNodeId
                pressedNodeId = null
                val n = id?.let { scene.findNode(it) }
                if (n != null && n.action.isNotEmpty()) {
                    performAction(n.action)
                    return true
                }
                return false
            }
        }
        return false
    }
}

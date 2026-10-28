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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.LruCache
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import java.io.File
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** 图片缓存：脚本只能读 App 能读到的路径/content URI，解码尺寸和驻留内存都有上限。 */
class CanvasImageCache(private val ctx: Context) {
    private val cache = object : LruCache<String, Bitmap>(MAX_CACHE_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val failed = HashSet<String>()
    private val pending = HashSet<String>()
    private val hosts: MutableSet<CanvasHostView> =
        Collections.newSetFromMap(WeakHashMap<CanvasHostView, Boolean>())

    fun register(host: CanvasHostView) {
        hosts.add(host)
    }

    fun unregister(host: CanvasHostView) {
        hosts.remove(host)
    }

    /** 命中返回图片；未命中返回 null 并在后台解码，完成后通知宿主重绘。 */
    fun get(src: String): Bitmap? {
        if (src.isBlank()) return null
        cache.get(src)?.let { return it }
        if (failed.contains(src) || !pending.add(src)) return null
        LOADER.execute {
            val bmp = runCatching { load(src) }.getOrNull()
            MAIN.post {
                pending.remove(src)
                if (bmp == null) {
                    if (failed.size < MAX_FAILED_KEYS) failed.add(src)
                } else {
                    cache.put(src, bmp)
                    hosts.forEach { it.invalidate() }
                }
            }
        }
        return null
    }

    companion object {
        private const val MAX_CACHE_BYTES = 24 * 1024 * 1024
        private const val MAX_FAILED_KEYS = 256
        private val LOADER = Executors.newSingleThreadExecutor()
        private val MAIN = Handler(Looper.getMainLooper())
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
        cache.evictAll()
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
            textLayouts.clear()
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
    /** 带来源节点的动作：弹窗需要按 Canvas 块记录 `key.action`。 */
    var onNodeAction: ((nodeId: String?, action: String) -> Unit)? = null
    /** 运行态任意点击/滚动，用于弹窗空闲超时重置。 */
    var onUserActivity: (() -> Unit)? = null
    /** submit:ID / submit:ID:keep —— 点击后把结果交回现有弹窗结果链路。 */
    var onSubmit: ((id: String, keepOpen: Boolean) -> Unit)? = null
    var onEditStart: (() -> Unit)? = null
    /** 输入框节点被点中；弹窗宿主据此把透明 EditText 放到节点位置。 */
    var onInputFocus: ((CanvasNode) -> Unit)? = null
    /** 点到非输入节点时收起输入代理。 */
    var onInputBlur: (() -> Unit)? = null
    /** 运行时表单状态；设计器为 null，按节点默认值静态绘制。 */
    var formState: CanvasFormState? = null

    private var submitLocked = false
    private var widgetPressedId: String? = null
    private var widgetScrollNodeId: String? = null
    private var widgetDownX = 0f
    private var widgetDownY = 0f
    private var widgetLastX = 0f
    private var widgetLastY = 0f
    private var widgetDragging = false
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val textLayouts = HashMap<String, CachedTextLayout>()

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

    private data class CachedTextLayout(
        val signature: String,
        val layout: StaticLayout,
        val paint: TextPaint
    )

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
        images.register(this)
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

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    fun performAction(action: String, sourceNodeId: String? = null) {
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
        onNodeAction?.invoke(sourceNodeId, action)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        startedAt = SystemClock.uptimeMillis() - baseMs
        images.register(this)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        transients.clear()
        textLayouts.clear()
        resetWidgetTouch()
        images.unregister(this)
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
        if (playing && shouldKeepAnimating(frame.timeMs)) {
            postInvalidateOnAnimation()
        }
    }

    /** 有限动画结束后不再 60fps 空转；循环动画、粒子和 patch 过渡仍继续。 */
    private fun shouldKeepAnimating(nowMs: Long): Boolean {
        val timed = scene.animations.isNotEmpty() &&
            (CanvasPlayback.hasInfinite(scene.animations) ||
                CanvasPlayback.finiteEndMs(scene.animations) > nowMs)
        return scene.particles.count > 0 || transients.isNotEmpty() || timed
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

    @Suppress("CyclomaticComplexMethod")
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
        if (n.clip && n.isGroup) canvas.clipRect(0f, 0f, n.w, n.h)
        when (n.type) {
            CanvasSpec.TYPE_GROUP -> for (c in n.children) drawNode(canvas, c, alpha)
            CanvasSpec.TYPE_TEXT -> drawText(canvas, n, alpha)
            CanvasSpec.TYPE_IMAGE -> drawImage(canvas, n, alpha)
            CanvasSpec.TYPE_PROGRESS -> drawProgress(canvas, n, alpha)
            CanvasSpec.TYPE_LINE -> drawLineNode(canvas, n, alpha)
            CanvasSpec.TYPE_PATH -> drawPathNode(canvas, n, alpha)
            CanvasSpec.TYPE_CIRCLE, CanvasSpec.TYPE_ELLIPSE -> drawOval(canvas, n, alpha)
            CanvasSpec.TYPE_INPUT -> drawInput(canvas, n, alpha)
            CanvasSpec.TYPE_SELECT -> drawSelect(canvas, n, alpha)
            CanvasSpec.TYPE_TOGGLE -> drawToggle(canvas, n, alpha)
            CanvasSpec.TYPE_BUTTON -> drawButton(canvas, n, alpha)
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
        if (n.wrap || n.ansi) {
            drawRichText(canvas, n, alpha)
            return
        }
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

    private fun widgetPaint(size: Float, color: Int, alpha: Float, align: Paint.Align): Paint {
        textPaint.reset()
        textPaint.isAntiAlias = true
        textPaint.typeface = Typeface.MONOSPACE
        textPaint.textSize = size.coerceIn(1f, 512f)
        textPaint.color = color
        textPaint.alpha = (Color.alpha(color) * alpha).roundToInt().coerceIn(0, 255)
        textPaint.textAlign = align
        return textPaint
    }

    private fun drawWidgetLabel(canvas: Canvas, n: CanvasNode, alpha: Float) {
        if (n.label.isEmpty()) return
        val p = widgetPaint(n.labelSize, CanvasSpec.color(n.labelColor, 0xFF9A9A9A.toInt()), alpha, Paint.Align.LEFT)
        val text = TextUtils.ellipsize(n.label, TextPaint(p), n.w.coerceAtLeast(1f), TextUtils.TruncateAt.END)
        canvas.drawText(text, 0, text.length, n.x, n.y + n.labelSize, p)
    }

    @Suppress("ReturnCount")
    private fun drawInput(canvas: Canvas, n: CanvasNode, alpha: Float) {
        drawWidgetLabel(canvas, n, alpha)
        val labelH = if (n.label.isEmpty()) 0f else CanvasWidgetLayout.LABEL_H + CanvasWidgetLayout.LABEL_GAP
        val field = RectF(n.x, n.y + labelH, n.x + n.w, n.y + n.h)
        if (field.bottom <= field.top) return
        if (n.hasFill) {
            val p = basePaint(n, alpha, true)
            if (n.shadow) p.setShadowLayer(n.shadowRadius, 0f, n.shadowRadius / 3f, 0x66000000)
            canvas.drawRoundRect(field, n.radius, n.radius, p)
        }
        if (n.hasStroke) canvas.drawRoundRect(field, n.radius, n.radius, basePaint(n, alpha, false))
        if (formState?.isFocused(n) == true) return
        val value = formState?.inputValue(n) ?: n.text
        if (value.isEmpty()) return
        val shown = if (n.password) "•".repeat(value.length.coerceAtMost(64)) else value
        val p = widgetPaint(n.textSize, CanvasSpec.color(n.textColor, 0xFFE6E6E6.toInt()), alpha, Paint.Align.LEFT)
        val maxW = (field.width() - 2 * CanvasWidgetLayout.FIELD_PAD_X).coerceAtLeast(1f)
        canvas.save()
        canvas.clipRect(field)
        if (n.multiline) {
            val lines = shown.split('\n').take(8)
            val lh = n.textSize * 1.25f
            var y = field.top + CanvasWidgetLayout.FIELD_PAD_Y + n.textSize
            for (line in lines) {
                if (y > field.bottom) break
                val text = TextUtils.ellipsize(line, TextPaint(p), maxW, TextUtils.TruncateAt.END)
                canvas.drawText(text, 0, text.length, field.left + CanvasWidgetLayout.FIELD_PAD_X, y, p)
                y += lh
            }
        } else {
            val text = TextUtils.ellipsize(shown, TextPaint(p), maxW, TextUtils.TruncateAt.END)
            val baseline = field.top + (field.height() - (p.descent() + p.ascent())) / 2f
            canvas.drawText(text, 0, text.length, field.left + CanvasWidgetLayout.FIELD_PAD_X, baseline, p)
        }
        canvas.restore()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun drawSelect(canvas: Canvas, n: CanvasNode, alpha: Float) {
        drawWidgetLabel(canvas, n, alpha)
        val top = n.y + CanvasWidgetLayout.selectBodyTop(n)
        val bottom = n.y + n.h - CanvasWidgetLayout.SELECT_PAD_BOTTOM
        if (bottom <= top) return
        val boxTop = if (n.label.isEmpty()) top else top - CanvasWidgetLayout.SELECT_PAD_TOP
        val box = RectF(n.x, boxTop, n.x + n.w, n.y + n.h)
        if (n.hasFill) canvas.drawRoundRect(box, n.radius, n.radius, basePaint(n, alpha, true))
        if (n.hasStroke) canvas.drawRoundRect(box, n.radius, n.radius, basePaint(n, alpha, false))
        val state = formState
        val visible = CanvasWidgetLayout.visibleOptions(n, state?.activeFilters ?: emptySet())
        val selected = state?.selected(n) ?: emptyList()
        val scroll = state?.scrollOffset(n) ?: 0f
        val baseColor = CanvasSpec.color(n.textColor, 0xFFE6E6E6.toInt())
        val accent = CanvasSpec.color(n.barColor, 0xFF2D7D46.toInt())
        val markerW = 22f
        canvas.save()
        canvas.clipRect(n.x, top, n.x + n.w, bottom)
        for (i in visible.indices) {
            val rowTop = top + i * CanvasWidgetLayout.OPTION_H - scroll
            if (rowTop + CanvasWidgetLayout.OPTION_H < top || rowTop > bottom) continue
            val option = visible[i]
            val checked = selected.contains(option.label)
            val p = widgetPaint(n.textSize, if (checked) accent else baseColor, alpha, Paint.Align.LEFT)
            val baseline = rowTop + (CanvasWidgetLayout.OPTION_H - (p.descent() + p.ascent())) / 2f
            val marker = when {
                n.multi && checked -> "[x] "
                n.multi -> "[ ] "
                checked -> "● "
                else -> "○ "
            }
            canvas.drawText(marker, n.x + CanvasWidgetLayout.OPTION_PAD_X, baseline, p)
            val labelX = n.x + CanvasWidgetLayout.OPTION_PAD_X + markerW
            val maxW = (n.x + n.w - CanvasWidgetLayout.OPTION_PAD_X - labelX).coerceAtLeast(1f)
            val text = TextUtils.ellipsize(option.label, TextPaint(p), maxW, TextUtils.TruncateAt.END)
            canvas.drawText(text, 0, text.length, labelX, baseline, p)
        }
        canvas.restore()
        val count = visible.size
        val maxScroll = CanvasWidgetLayout.maxScroll(n, count)
        if (maxScroll > 0f) {
            val trackRight = n.x + n.w - 3f
            val trackW = 2f
            val track = RectF(trackRight - trackW, top, trackRight, bottom)
            paint.reset()
            paint.isAntiAlias = true
            paint.color = 0x33FFFFFF
            canvas.drawRoundRect(track, 1f, 1f, paint)
            val bodyH = CanvasWidgetLayout.selectBodyHeight(n)
            val ratio = (bodyH / (count * CanvasWidgetLayout.OPTION_H)).coerceIn(0.1f, 1f)
            val knobH = max(12f, track.height() * ratio)
            val maxKnob = (track.height() - knobH).coerceAtLeast(0f)
            val knobTop = track.top + maxKnob * (scroll / maxScroll).coerceIn(0f, 1f)
            paint.color = CanvasSpec.color(n.barColor, 0xFF2D7D46.toInt())
            canvas.drawRoundRect(RectF(track.left, knobTop, track.right, knobTop + knobH), 1f, 1f, paint)
        }
    }

    private fun drawToggle(canvas: Canvas, n: CanvasNode, alpha: Float) {
        val on = formState?.toggleValue(n) ?: n.checked
        val pillW = CanvasWidgetLayout.TOGGLE_PILL_W.coerceAtMost(n.w)
        val pillX = n.x + n.w - pillW
        if (n.label.isNotEmpty()) {
            val p = widgetPaint(n.labelSize, CanvasSpec.color(n.labelColor, 0xFF9A9A9A.toInt()), alpha, Paint.Align.LEFT)
            val maxW = (pillX - n.x - 8f).coerceAtLeast(1f)
            val text = TextUtils.ellipsize(n.label, TextPaint(p), maxW, TextUtils.TruncateAt.END)
            val baseline = n.y + (n.h - (p.descent() + p.ascent())) / 2f
            canvas.drawText(text, 0, text.length, n.x, baseline, p)
        }
        val pill = RectF(pillX, n.y + (n.h - CanvasWidgetLayout.TOGGLE_PILL_H) / 2f, pillX + pillW, n.y + (n.h + CanvasWidgetLayout.TOGGLE_PILL_H) / 2f)
        paint.reset()
        paint.isAntiAlias = true
        paint.style = Paint.Style.FILL
        paint.color = CanvasSpec.color(n.fill, 0xFF3A3A3A.toInt())
        paint.alpha = (Color.alpha(paint.color) * alpha).roundToInt().coerceIn(0, 255)
        canvas.drawRoundRect(pill, n.radius, n.radius, paint)
        val p = widgetPaint(n.textSize, CanvasSpec.color(if (on) n.barColor else n.textColor, 0xFF9A9A9A.toInt()), alpha, Paint.Align.CENTER)
        val text = if (on) "[ON]" else "[OFF]"
        val baseline = pill.top + (pill.height() - (p.descent() + p.ascent())) / 2f
        canvas.drawText(text, pill.centerX(), baseline, p)
    }

    private fun drawButton(canvas: Canvas, n: CanvasNode, alpha: Float) {
        val rect = RectF(n.x, n.y, n.x + n.w, n.y + n.h)
        if (n.hasFill) {
            val p = basePaint(n, alpha, true)
            if (n.shadow) p.setShadowLayer(n.shadowRadius, 0f, n.shadowRadius / 3f, 0x66000000)
            canvas.drawRoundRect(rect, n.radius, n.radius, p)
        }
        if (n.hasStroke) canvas.drawRoundRect(rect, n.radius, n.radius, basePaint(n, alpha, false))
        if (n.text.isEmpty()) return
        val p = widgetPaint(n.textSize, CanvasSpec.color(n.textColor, 0xFFE6E6E6.toInt()), alpha, Paint.Align.CENTER)
        if (n.bold) p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
        val maxW = (n.w - 16f).coerceAtLeast(1f)
        val text = TextUtils.ellipsize(n.text, TextPaint(p), maxW, TextUtils.TruncateAt.END)
        val baseline = n.y + (n.h - (p.descent() + p.ascent())) / 2f
        canvas.drawText(text, 0, text.length, n.x + n.w / 2f, baseline, p)
    }

    private fun drawRichText(canvas: Canvas, n: CanvasNode, alpha: Float) {
        if (n.w <= 0f || n.h <= 0f) return
        val entry = richTextLayout(n) ?: return
        canvas.save()
        canvas.clipRect(n.x, n.y, n.x + n.w, n.y + n.h)
        val alphaInt = (alpha.coerceIn(0f, 1f) * 255).roundToInt()
        val layer = if (alphaInt < 255) {
            canvas.saveLayerAlpha(n.x, n.y, n.x + n.w, n.y + n.h, alphaInt)
        } else {
            canvas.save()
        }
        canvas.translate(n.x, n.y)
        entry.layout.draw(canvas)
        canvas.restoreToCount(layer)
        canvas.restore()
    }

    @Suppress("CyclomaticComplexMethod")
    private fun richTextLayout(n: CanvasNode): CachedTextLayout? {
        val width = n.w.toInt().coerceAtLeast(1)
        val signature = listOf(
            n.text.hashCode(), width, n.textSize, n.ansi, n.bold, n.italic,
            n.monospace, n.textColor, n.textAlign
        ).joinToString(":")
        textLayouts[n.id]?.let { if (it.signature == signature) return it }
        val tp = TextPaint(Paint.ANTI_ALIAS_FLAG)
        tp.typeface = when {
            n.bold && n.italic -> Typeface.create(if (n.monospace) Typeface.MONOSPACE else Typeface.DEFAULT, Typeface.BOLD_ITALIC)
            n.bold -> Typeface.create(if (n.monospace) Typeface.MONOSPACE else Typeface.DEFAULT, Typeface.BOLD)
            n.italic -> Typeface.create(if (n.monospace) Typeface.MONOSPACE else Typeface.DEFAULT, Typeface.ITALIC)
            n.monospace -> Typeface.MONOSPACE
            else -> Typeface.DEFAULT
        }
        tp.textSize = n.textSize.coerceIn(1f, 512f)
        tp.color = CanvasSpec.color(n.textColor, 0xFFE6E6E6.toInt())
        val text: CharSequence = if (n.ansi) ansiSpannable(n.text, tp.color) else n.text
        val alignment = when (n.textAlign) {
            CanvasSpec.ALIGN_CENTER -> Layout.Alignment.ALIGN_CENTER
            CanvasSpec.ALIGN_RIGHT -> Layout.Alignment.ALIGN_OPPOSITE
            else -> Layout.Alignment.ALIGN_NORMAL
        }
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 1f)
            .build()
        val entry = CachedTextLayout(signature, layout, tp)
        textLayouts[n.id] = entry
        return entry
    }

    private fun ansiSpannable(text: String, defaultFg: Int): CharSequence {
        val parsed = AnsiParser.parse(text)
        val sp = SpannableString(parsed.clean)
        for (s in parsed.spans) {
            if (s.end <= s.start) continue
            val fg = if (s.inverse) (s.bg ?: defaultFg) else (s.fg ?: defaultFg)
            sp.setSpan(ForegroundColorSpan(fg), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.bg != null && !s.inverse) {
                sp.setSpan(BackgroundColorSpan(s.bg), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (s.bold) sp.setSpan(StyleSpan(Typeface.BOLD), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.italic) sp.setSpan(StyleSpan(Typeface.ITALIC), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (s.underline) sp.setSpan(UnderlineSpan(), s.start, s.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sp
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
            val cam = scene.cameraZoom.coerceIn(0.05f, 20f)
            val r = p.size * (0.5f + ry) * viewScale * cam
            val view = toView(x, y)
            canvas.drawCircle(view.first, view.second, r, paint)
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

    /** 节点局部坐标（相对左上角）→ 场景坐标；组合节点单独处理 x/y。 */
    private fun nodeLocalToScene(
        x: Float,
        y: Float,
        n: CanvasNode,
        ancestors: List<CanvasNode>
    ): Pair<Float, Float> = CanvasTransform.localToScene(n, x, y, ancestors)

    /** 场景坐标 → 节点局部坐标（相对左上角）。 */
    private fun sceneToNodeLocal(
        x: Float,
        y: Float,
        n: CanvasNode,
        ancestors: List<CanvasNode>
    ): Pair<Float, Float> = CanvasTransform.sceneToLocal(n, x, y, ancestors)

    /** 场景坐标 → 节点直接父级坐标（不含节点自身变换）。 */
    private fun sceneToParent(x: Float, y: Float, ancestors: List<CanvasNode>): Pair<Float, Float> =
        CanvasTransform.sceneToParent(x, y, ancestors)

    @Suppress("ReturnCount")
    private fun drawSelection(canvas: Canvas) {
        val id = selectedId ?: return
        val n = scene.findNode(id) ?: return
        if (!n.visible) return
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
        // 锁定节点只显示选中框，不再提供任何可拖拽手柄。
        if (n.locked) return
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

    private fun findHit(x: Float, y: Float): CanvasNode? {
        fun walk(
            list: List<CanvasNode>,
            ancestors: List<CanvasNode>,
            out: MutableList<CanvasNode>
        ) {
            for (n in list) {
                if (!n.visible || !n.hitTest) continue
                val (lx, ly) = sceneToNodeLocal(x, y, n, ancestors)
                if (n.isGroup) {
                    if (inBounds(n, lx, ly)) out.add(n)
                    walk(n.children, ancestors + n, out)
                } else if (containsLocal(n, lx, ly)) {
                    out.add(n)
                }
            }
        }
        val hits = mutableListOf<CanvasNode>()
        walk(scene.nodes, emptyList(), hits)
        // 命中必须跟绘制顺序，而不是层级深度：后绘制、盖在上面的节点优先。
        val order = scene.flatten().withIndex().associate { it.value.id to it.index }
        return hits.maxByOrNull { order[it.id] ?: -1 }
    }

    private fun containsLocal(n: CanvasNode, lx: Float, ly: Float): Boolean = when (n.type) {
        CanvasSpec.TYPE_CIRCLE, CanvasSpec.TYPE_ELLIPSE -> inOval(n, lx, ly)
        CanvasSpec.TYPE_LINE -> nearPath(n, lx, ly)
        CanvasSpec.TYPE_PATH -> {
            val pts = if (n.points.isNotEmpty()) n.points else CanvasPath.parse(n.d).points
            (n.closed && n.hasFill && pointInPolygon(pts, lx, ly)) ||
                nearPath(n, lx, ly)
        }
        else -> inBounds(n, lx, ly)
    }

    private fun pointInPolygon(points: List<CanvasPoint>, x: Float, y: Float): Boolean {
        if (points.size < 3) return false
        var inside = false
        var j = points.size - 1
        for (i in points.indices) {
            val xi = points[i].x
            val yi = points[i].y
            val xj = points[j].x
            val yj = points[j].y
            if ((yi > y) != (yj > y)) {
                val crossX = (xj - xi) * (y - yi) / (yj - yi) + xi
                if (x < crossX) inside = !inside
            }
            j = i
        }
        return inside
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

    /** 节点局部坐标（相对左上角）→ 当前 View 像素坐标；输入代理定位用。 */
    fun nodeLocalToView(nodeId: String, localX: Float, localY: Float): Pair<Float, Float>? {
        val n = scene.findNode(nodeId) ?: return null
        val (sx, sy) = nodeLocalToScene(localX, localY, n, ancestorsOf(nodeId))
        return toView(sx, sy)
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
        if (!n.visible || n.locked) return DragMode.NONE
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
        if (!designMode) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) onUserActivity?.invoke()
            return runtimeTouch(event)
        }
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

    private fun widgetSlop(): Float = touchSlop / viewScale.coerceAtLeast(0.01f)

    @Suppress("LongMethod", "CyclomaticComplexMethod", "NestedBlockDepth", "ComplexCondition", "ReturnCount")
    private fun runtimeTouch(event: MotionEvent): Boolean {
        val (sx, sy) = toScene(event.x, event.y)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = findHit(sx, sy)
                if (formState?.focusedNodeId != null && hit?.type != CanvasSpec.TYPE_INPUT) {
                    formState?.focus(null)
                    onInputBlur?.invoke()
                    invalidate()
                }
                pressedNodeId = hit?.id
                widgetPressedId = hit?.id
                widgetDownX = sx
                widgetDownY = sy
                widgetLastX = sx
                widgetLastY = sy
                widgetDragging = false
                widgetScrollNodeId = null
                if (hit != null && hit.type == CanvasSpec.TYPE_SELECT && formState != null) {
                    val count = CanvasWidgetLayout.visibleOptions(hit, formState!!.activeFilters).size
                    if (CanvasWidgetLayout.maxScroll(hit, count) > 0f) {
                        widgetScrollNodeId = hit.id
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                return hit != null
            }
            MotionEvent.ACTION_MOVE -> {
                val id = widgetPressedId ?: return false
                val n = scene.findNode(id) ?: return false
                if (widgetScrollNodeId == id && formState != null) {
                    val ancestors = ancestorsOf(id)
                    val (_, localY) = sceneToNodeLocal(sx, sy, n, ancestors)
                    val (_, downLocalY) = sceneToNodeLocal(widgetDownX, widgetDownY, n, ancestors)
                    if (!widgetDragging && abs(localY - downLocalY) > widgetSlop()) widgetDragging = true
                    if (widgetDragging) {
                        val (_, lastLocalY) = sceneToNodeLocal(widgetLastX, widgetLastY, n, ancestors)
                        val dy = localY - lastLocalY
                        val changed = formState!!.scrollBy(n, -dy)
                        widgetLastX = sx
                        widgetLastY = sy
                        if (changed) invalidate()
                        handoffSelectEdge(n, dy)
                        return true
                    }
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val id = widgetPressedId
                val moved = widgetDragging || abs(sy - widgetDownY) > widgetSlop()
                resetWidgetTouch()
                parent?.requestDisallowInterceptTouchEvent(false)
                if (event.actionMasked == MotionEvent.ACTION_CANCEL || id == null) return false
                val n = scene.findNode(id) ?: return false
                when (n.type) {
                    CanvasSpec.TYPE_INPUT -> if (!moved) {
                        formState?.focus(n)
                        invalidate()
                        onInputFocus?.invoke(n)
                        true
                    } else false
                    CanvasSpec.TYPE_SELECT -> {
                        if (!moved) {
                            val (_, ly) = sceneToNodeLocal(sx, sy, n, ancestorsOf(n.id))
                            if (formState?.tapOption(n, ly) == true) invalidate()
                        }
                        true
                    }
                    CanvasSpec.TYPE_TOGGLE -> {
                        if (!moved) {
                            val state = formState
                            if (state != null) {
                                state.toggle(n)
                                for (select in scene.flatten()) {
                                    if (select.type == CanvasSpec.TYPE_SELECT) state.clampScroll(select)
                                }
                            }
                            invalidate()
                        }
                        true
                    }
                    CanvasSpec.TYPE_BUTTON -> {
                        if (!moved) performAction(n.action, n.id)
                        true
                    }
                    else -> {
                        if (!moved && n.action.isNotEmpty()) performAction(n.action, n.id)
                        !moved && n.action.isNotEmpty()
                    }
                }
            }
        }
        return false
    }

    /**
     * 选项框滚到边界后把后续竖直拖动交还外层 ScrollView。
     * 手指向下(dy>0)且已经在顶部，或手指向上(dy<0)且已经在底部时释放拦截。
     */
    @Suppress("ComplexCondition")
    private fun handoffSelectEdge(n: CanvasNode, dy: Float) {
        val state = formState ?: return
        val count = CanvasWidgetLayout.visibleOptions(n, state.activeFilters).size
        val max = CanvasWidgetLayout.maxScroll(n, count)
        val offset = state.scrollOffset(n)
        val atTop = offset <= 0.01f
        val atBottom = offset >= max - 0.01f
        if ((atTop && dy > 0f) || (atBottom && dy < 0f)) {
            widgetScrollNodeId = null
            parent?.requestDisallowInterceptTouchEvent(false)
        }
    }

    private fun resetWidgetTouch() {
        pressedNodeId = null
        widgetPressedId = null
        widgetScrollNodeId = null
        widgetDragging = false
        widgetDownX = 0f
        widgetDownY = 0f
        widgetLastX = 0f
        widgetLastY = 0f
    }
}

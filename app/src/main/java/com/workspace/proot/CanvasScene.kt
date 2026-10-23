package com.workspace.proot

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 原生 Canvas 场景模型（纯 Kotlin，可 JVM 单测）。
 *
 * 设计原则：
 *  - 数据驱动：脚本只传场景 JSON，App 用原生 Canvas 解释渲染，不接 WebView；
 *  - 增量：场景里的节点、动画、交互动作全部是可选字段，旧请求完全不受影响；
 *  - 可校验：所有尺寸/数量都有上限，非法字段回退默认值，绝不因为一个坏字段崩浮窗。
 *
 * 场景坐标使用 dp 语义：width/height 是设计画布尺寸，渲染时按 View 大小等比适配。
 */
object CanvasSpec {
    const val VERSION = 1

    const val TYPE_RECT = "rect"
    const val TYPE_ROUND_RECT = "roundRect"
    const val TYPE_CIRCLE = "circle"
    const val TYPE_ELLIPSE = "ellipse"
    const val TYPE_LINE = "line"
    const val TYPE_PATH = "path"
    const val TYPE_TEXT = "text"
    const val TYPE_IMAGE = "image"
    const val TYPE_PROGRESS = "progress"
    const val TYPE_GROUP = "group"

    const val ALIGN_LEFT = "left"
    const val ALIGN_CENTER = "center"
    const val ALIGN_RIGHT = "right"

    const val FIT_CONTAIN = "contain"
    const val FIT_COVER = "cover"
    const val FIT_STRETCH = "stretch"

    const val CAMERA_TARGET = "@camera"

    const val DEFAULT_WIDTH = 360f
    const val DEFAULT_HEIGHT = 220f

    const val MAX_NODES = 200
    const val MAX_DEPTH = 4
    const val MAX_POINTS = 2000
    const val MAX_ANIMATIONS = 200
    const val MAX_KEYFRAMES = 120
    const val MAX_TEXT = 4000
    const val MAX_ID = 128
    const val MAX_SRC = 2048

    val NODE_TYPES = setOf(
        TYPE_RECT, TYPE_ROUND_RECT, TYPE_CIRCLE, TYPE_ELLIPSE, TYPE_LINE,
        TYPE_PATH, TYPE_TEXT, TYPE_IMAGE, TYPE_PROGRESS, TYPE_GROUP
    )

    val NUMERIC_PROPS = setOf(
        "x", "y", "w", "h", "rotation", "scaleX", "scaleY", "alpha",
        "progress", "textSize", "strokeWidth", "radius", "dash", "dashGap",
        "dashOffset", "trim"
    )

    val COLOR_PROPS = setOf(
        "fill", "stroke", "textColor", "trackColor", "barColor", "labelColor",
        "gradientFrom", "gradientTo", "tint"
    )

    val STRING_PROPS = setOf(
        "text", "labelFormat", "src", "fit", "textAlign", "name", "action", "d", "path"
    )

    val BOOL_PROPS = setOf("visible", "showLabel", "bold", "italic", "monospace", "closed", "hitTest", "locked", "shadow")

    fun clamp(v: Float, lo: Float, hi: Float): Float = if (v.isNaN()) lo else v.coerceIn(lo, hi)

    fun clampInt(v: Int, lo: Int, hi: Int): Int = v.coerceIn(lo, hi)

    fun safeText(s: String): String = if (s.length <= MAX_TEXT) s else s.substring(0, MAX_TEXT)

    fun safeId(s: String): String = s.trim().take(MAX_ID)

    fun color(s: String, fallback: Int): Int = ScriptDialogSpec.parseColor(s) ?: fallback

    fun colorHex(c: Int): String = String.format("#%08X", c)

    fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    fun lerpColor(a: Int, b: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val aa = ((a ushr 24) and 0xFF).toFloat()
        val ar = ((a ushr 16) and 0xFF).toFloat()
        val ag = ((a ushr 8) and 0xFF).toFloat()
        val ab = (a and 0xFF).toFloat()
        val ba = ((b ushr 24) and 0xFF).toFloat()
        val br = ((b ushr 16) and 0xFF).toFloat()
        val bg = ((b ushr 8) and 0xFF).toFloat()
        val bb = (b and 0xFF).toFloat()
        val oa = (aa + (ba - aa) * tt).roundToInt().coerceIn(0, 255)
        val or = (ar + (br - ar) * tt).roundToInt().coerceIn(0, 255)
        val og = (ag + (bg - ag) * tt).roundToInt().coerceIn(0, 255)
        val ob = (ab + (bb - ab) * tt).roundToInt().coerceIn(0, 255)
        return (oa shl 24) or (or shl 16) or (og shl 8) or ob
    }
}

/**
 * Canvas 节点的“提交结果”动作：submit:ID / submit:ID:keep。
 * 纯 Kotlin，供运行时和单测共用，避免两处解析不一致。
 */
object CanvasAction {
    data class Submit(val id: String, val keepOpen: Boolean)

    fun parseSubmit(action: String): Submit? {
        if (!action.startsWith("submit:")) return null
        val rest = action.removePrefix("submit:")
        val keep = rest.endsWith(":keep")
        val id = (if (keep) rest.removeSuffix(":keep") else rest).trim()
        return id.takeIf { it.isNotEmpty() }?.let { Submit(it, keep) }
    }
}

data class CanvasPoint(val x: Float, val y: Float)

data class CanvasKeyframe(
    val at: Float,
    val value: Float,
    val easing: String = "ease-in-out",
    val path: String = ""
)

data class CanvasAnimation(
    val id: String = "",
    val target: String = "",
    val targets: List<String> = emptyList(),
    val property: String = "alpha",
    val from: Float? = null,
    val to: Float? = null,
    val fromColor: String = "",
    val toColor: String = "",
    val fromPath: String = "",
    val toPath: String = "",
    val durationMs: Int = 600,
    val delayMs: Int = 0,
    val repeat: Int = 1,
    val alternate: Boolean = false,
    val easing: String = "ease-out",
    val staggerMs: Int = 0,
    val keyframes: List<CanvasKeyframe> = emptyList()
)

data class CanvasParticle(
    val count: Int = 0,
    val color: String = "#66FFFFFF",
    val size: Float = 2f,
    val speed: Float = 12f,
    val drift: Float = 0.6f,
    val seed: Int = 7
)

data class CanvasNode(
    val id: String,
    val type: String = CanvasSpec.TYPE_RECT,
    val x: Float = 0f,
    val y: Float = 0f,
    val w: Float = 0f,
    val h: Float = 0f,
    val rotation: Float = 0f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    val alpha: Float = 1f,
    val fill: String = "#00000000",
    val stroke: String = "",
    val strokeWidth: Float = 0f,
    val radius: Float = 0f,
    val text: String = "",
    val textSize: Float = 13f,
    val textColor: String = "#E6E6E6",
    val textAlign: String = CanvasSpec.ALIGN_LEFT,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val monospace: Boolean = true,
    val points: List<CanvasPoint> = emptyList(),
    val closed: Boolean = false,
    val d: String = "",
    val src: String = "",
    val fit: String = CanvasSpec.FIT_CONTAIN,
    val tint: String = "",
    val progress: Float = 0f,
    val trackColor: String = "#33FFFFFF",
    val barColor: String = "#2D7D46",
    val gradientFrom: String = "",
    val gradientTo: String = "",
    val showLabel: Boolean = false,
    val labelFormat: String = "{percent}%",
    val labelColor: String = "#E6E6E6",
    val labelSize: Float = 12f,
    val dash: Float = 0f,
    val dashGap: Float = 0f,
    val dashOffset: Float = 0f,
    val trim: Float = 1f,
    val children: List<CanvasNode> = emptyList(),
    val action: String = "",
    val visible: Boolean = true,
    val hitTest: Boolean = true,
    val locked: Boolean = false,
    val shadow: Boolean = false,
    val shadowRadius: Float = 8f,
    val name: String = ""
) {
    val isGroup: Boolean get() = type == CanvasSpec.TYPE_GROUP
    val hasFill: Boolean get() = fill.isNotEmpty() && CanvasSpec.color(fill, 0) != 0
    val hasStroke: Boolean get() = stroke.isNotEmpty() && strokeWidth > 0f

    /**
     * 缩放选框时同步缩放路径/直线局部点。
     *
     * 路径点统一是节点局部坐标，所以缩放只做比例映射，不碰节点 x/y；
     * 这样选框和图形始终同步，局部坐标语义也不会在拖拽/输入属性时漂移。
     */
    fun scaledLocalPoints(newW: Float, newH: Float): List<CanvasPoint> {
        if (points.isEmpty()) return points
        val sx = if (w > 0.001f) newW / w else 1f
        val sy = if (h > 0.001f) newH / h else 1f
        return points.map { CanvasPoint(it.x * sx, it.y * sy) }
    }

    /** 场景坐标 → 节点局部坐标（与绘制时的旋转中心、缩放顺序一致）。 */
    fun sceneToLocal(sceneX: Float, sceneY: Float): Pair<Float, Float> {
        val cx = x + w / 2f
        val cy = y + h / 2f
        var dx = sceneX - cx
        var dy = sceneY - cy
        if (rotation != 0f) {
            val a = Math.toRadians(-rotation.toDouble())
            val rx = (dx * cos(a) - dy * sin(a)).toFloat()
            val ry = (dx * sin(a) + dy * cos(a)).toFloat()
            dx = rx
            dy = ry
        }
        val sx = if (abs(scaleX) < 0.001f) 1f else scaleX
        val sy = if (abs(scaleY) < 0.001f) 1f else scaleY
        return (cx + dx / sx - x) to (cy + dy / sy - y)
    }

    /** 节点局部坐标 → 场景坐标（sceneToLocal 的逆运算）。 */
    fun localToScene(localX: Float, localY: Float): Pair<Float, Float> {
        val cx = x + w / 2f
        val cy = y + h / 2f
        var dx = (localX + x - cx) * scaleX
        var dy = (localY + y - cy) * scaleY
        if (rotation != 0f) {
            val a = Math.toRadians(rotation.toDouble())
            val rx = (dx * cos(a) - dy * sin(a)).toFloat()
            val ry = (dx * sin(a) + dy * cos(a)).toFloat()
            dx = rx
            dy = ry
        }
        return (cx + dx) to (cy + dy)
    }

    fun baseNumber(prop: String): Float? = when (prop) {
        "x" -> x
        "y" -> y
        "w" -> w
        "h" -> h
        "rotation" -> rotation
        "scaleX" -> scaleX
        "scaleY" -> scaleY
        "alpha" -> alpha
        "progress" -> progress
        "textSize" -> textSize
        "strokeWidth" -> strokeWidth
        "radius" -> radius
        "dash" -> dash
        "dashGap" -> dashGap
        "dashOffset" -> dashOffset
        "trim" -> trim
        else -> null
    }

    fun baseColor(prop: String): Int? = when (prop) {
        "fill" -> CanvasSpec.color(fill, 0)
        "stroke" -> CanvasSpec.color(stroke, 0)
        "textColor" -> CanvasSpec.color(textColor, 0xFFE6E6E6.toInt())
        "trackColor" -> CanvasSpec.color(trackColor, 0x33FFFFFF)
        "barColor" -> CanvasSpec.color(barColor, 0xFF2D7D46.toInt())
        "labelColor" -> CanvasSpec.color(labelColor, 0xFFE6E6E6.toInt())
        "gradientFrom" -> CanvasSpec.color(gradientFrom, 0)
        "gradientTo" -> CanvasSpec.color(gradientTo, 0)
        "tint" -> CanvasSpec.color(tint, 0)
        else -> null
    }

    fun withNumber(prop: String, value: Float): CanvasNode {
        val v = when (prop) {
            "alpha" -> CanvasSpec.clamp(value, 0f, 1f)
            "progress" -> CanvasSpec.clamp(value, 0f, 1f)
            "trim" -> CanvasSpec.clamp(value, 0f, 1f)
            "scaleX", "scaleY" -> CanvasSpec.clamp(value, 0.01f, 100f)
            "textSize", "labelSize" -> CanvasSpec.clamp(value, 1f, 512f)
            "strokeWidth", "radius", "dash", "dashGap", "shadowRadius" -> CanvasSpec.clamp(value, 0f, 10000f)
            else -> CanvasSpec.clamp(value, -100000f, 100000f)
        }
        return when (prop) {
            "x" -> copy(x = v)
            "y" -> copy(y = v)
            "w" -> copy(w = v)
            "h" -> copy(h = v)
            "rotation" -> copy(rotation = v)
            "scaleX" -> copy(scaleX = v)
            "scaleY" -> copy(scaleY = v)
            "alpha" -> copy(alpha = v)
            "progress" -> copy(progress = v)
            "textSize" -> copy(textSize = v)
            "strokeWidth" -> copy(strokeWidth = v)
            "radius" -> copy(radius = v)
            "dash" -> copy(dash = v)
            "dashGap" -> copy(dashGap = v)
            "dashOffset" -> copy(dashOffset = v)
            "trim" -> copy(trim = v)
            else -> this
        }
    }

    fun withString(prop: String, value: String): CanvasNode = when (prop) {
        "text" -> copy(text = CanvasSpec.safeText(value))
        "labelFormat" -> copy(labelFormat = CanvasSpec.safeText(value))
        "src" -> copy(src = value.take(CanvasSpec.MAX_SRC))
        "fit" -> copy(fit = value)
        "textAlign" -> copy(textAlign = value)
        "name" -> copy(name = value.take(128))
        "action" -> copy(action = value.take(256))
        "d" -> copy(d = value.take(20000))
        "path" -> copy(d = value.take(20000))
        "fill", "stroke", "textColor", "trackColor", "barColor", "labelColor", "gradientFrom", "gradientTo", "tint" ->
            when (prop) {
                "fill" -> copy(fill = value.take(32))
                "stroke" -> copy(stroke = value.take(32))
                "textColor" -> copy(textColor = value.take(32))
                "trackColor" -> copy(trackColor = value.take(32))
                "barColor" -> copy(barColor = value.take(32))
                "labelColor" -> copy(labelColor = value.take(32))
                "gradientFrom" -> copy(gradientFrom = value.take(32))
                "gradientTo" -> copy(gradientTo = value.take(32))
                else -> copy(tint = value.take(32))
            }
        else -> this
    }

    fun withBoolean(prop: String, value: Boolean): CanvasNode = when (prop) {
        "visible" -> copy(visible = value)
        "showLabel" -> copy(showLabel = value)
        "bold" -> copy(bold = value)
        "italic" -> copy(italic = value)
        "monospace" -> copy(monospace = value)
        "closed" -> copy(closed = value)
        "hitTest" -> copy(hitTest = value)
        "locked" -> copy(locked = value)
        "shadow" -> copy(shadow = value)
        else -> this
    }

    fun withProperty(name: String, value: CanvasValue): Pair<CanvasNode, CanvasPropChange?>? {
        if (name in CanvasSpec.NUMERIC_PROPS) {
            val old = baseNumber(name) ?: return null
            val new = value.asFloat() ?: return null
            val next = withNumber(name, new)
            return next to CanvasPropChange(id, name, old, next.baseNumber(name) ?: new, null, null)
        }
        if (name in CanvasSpec.COLOR_PROPS) {
            val s = value.asString() ?: return null
            val old = baseColor(name)
            val next = withString(name, s)
            val new = next.baseColor(name)
            return next to CanvasPropChange(id, name, null, null, old, new)
        }
        if (name in CanvasSpec.STRING_PROPS) {
            val s = value.asString() ?: return null
            return withString(name, s) to null
        }
        if (name in CanvasSpec.BOOL_PROPS) {
            val b = value.asBool() ?: return null
            return withBoolean(name, b) to null
        }
        return null
    }
}

data class CanvasScene(
    val version: Int = CanvasSpec.VERSION,
    val width: Float = CanvasSpec.DEFAULT_WIDTH,
    val height: Float = CanvasSpec.DEFAULT_HEIGHT,
    val background: String = "#00000000",
    val nodes: List<CanvasNode> = emptyList(),
    val animations: List<CanvasAnimation> = emptyList(),
    val cameraX: Float = 0f,
    val cameraY: Float = 0f,
    val cameraZoom: Float = 1f,
    val particles: CanvasParticle = CanvasParticle()
) {
    fun flatten(): List<CanvasNode> {
        val out = mutableListOf<CanvasNode>()
        fun walk(list: List<CanvasNode>, depth: Int) {
            if (depth > CanvasSpec.MAX_DEPTH) return
            for (n in list) {
                out.add(n)
                if (n.children.isNotEmpty()) walk(n.children, depth + 1)
            }
        }
        walk(nodes, 0)
        return out
    }

    fun findNode(id: String): CanvasNode? = flatten().firstOrNull { it.id == id }

    fun updateNode(id: String, transform: (CanvasNode) -> CanvasNode): CanvasScene {
        fun walk(list: List<CanvasNode>): List<CanvasNode> = list.map { n ->
            if (n.id == id) transform(n) else if (n.children.isNotEmpty()) n.copy(children = walk(n.children)) else n
        }
        return copy(nodes = walk(nodes))
    }

    fun removeNode(id: String): CanvasScene {
        fun walk(list: List<CanvasNode>): List<CanvasNode> = list.mapNotNull { n ->
            if (n.id == id) null else if (n.children.isNotEmpty()) n.copy(children = walk(n.children)) else n
        }
        return copy(nodes = walk(nodes))
    }

    fun addNode(node: CanvasNode): CanvasScene = copy(nodes = nodes + node)

    fun moveNode(id: String, delta: Int): CanvasScene {
        val list = nodes.toMutableList()
        val idx = list.indexOfFirst { it.id == id }
        if (idx < 0) return this
        val target = (idx + delta).coerceIn(0, list.lastIndex)
        if (target == idx) return this
        val n = list.removeAt(idx)
        list.add(target, n)
        return copy(nodes = list)
    }
}

sealed class CanvasValue {
    data class Num(val value: Float) : CanvasValue()
    data class Str(val value: String) : CanvasValue()
    data class Bool(val value: Boolean) : CanvasValue()

    fun asFloat(): Float? = when (this) {
        is Num -> value
        is Str -> value.trim().toFloatOrNull()
        is Bool -> if (value) 1f else 0f
    }

    fun asString(): String? = when (this) {
        is Num -> if (value == value.toInt().toFloat()) value.toInt().toString() else value.toString()
        is Str -> value
        is Bool -> if (value) "true" else "false"
    }

    fun asBool(): Boolean? = when (this) {
        is Bool -> value
        is Num -> value != 0f
        is Str -> when (value.trim().lowercase()) {
            "1", "true", "yes", "on" -> true
            "0", "false", "no", "off", "" -> false
            else -> null
        }
    }
}

data class CanvasPropChange(
    val nodeId: String,
    val property: String,
    val from: Float?,
    val to: Float?,
    val fromColor: Int?,
    val toColor: Int?
)

data class CanvasPatch(
    val id: String = "",
    val key: String? = null,
    val scene: CanvasScene? = null,
    val node: String? = null,
    val props: Map<String, CanvasValue> = emptyMap(),
    val play: Boolean? = null,
    val seekMs: Long? = null,
    val animate: Boolean = false,
    val durationMs: Int? = null,
    val easing: String = "ease-out"
) {
    fun applyTo(scene: CanvasScene): CanvasPatchResult {
        var out = scene
        val changes = mutableListOf<CanvasPropChange>()
        val nid = node
        if (nid != null && props.isNotEmpty()) {
            val target = out.findNode(nid)
            if (target != null) {
                var updated: CanvasNode = target
                for ((k, v) in props) {
                    val r = updated.withProperty(k, v) ?: continue
                    updated = r.first
                    r.second?.let { changes.add(it) }
                }
                out = out.updateNode(nid) { updated }
            }
            return CanvasPatchResult(out, changes, target != null)
        }
        return CanvasPatchResult(out, changes, true)
    }

    companion object {
        const val CONTROL_KEYS = "op,id,key,patch,scene,node,play,seek,animate,duration,easing,target,value,invoke"

        fun parse(text: String): CanvasPatch = parse(MiniJson.parse(text))

        fun parse(obj: MiniJson.Obj): CanvasPatch {
            val patchObj = obj.optObj("patch")
            val source = patchObj ?: obj
            val key = source.optString("key", obj.optString("key", "")).ifEmpty { null }
            val id = obj.optString("id", source.optString("id", ""))
            val node = source.optString("node", "").ifEmpty { null }
            val scene = source.optObj("scene")?.let { CanvasSceneCodec.parseScene(it) }
            val controls = CONTROL_KEYS.split(",").toSet()
            val props = linkedMapOf<String, CanvasValue>()
            for (k in source.keys()) {
                if (k in controls) continue
                parseValue(source.raw(k))?.let { props[k] = it }
            }
            val play = if (source.has("play")) source.optBoolean("play", true) else null
            val seek = source.optDouble("seek", Double.NaN).takeIf { !it.isNaN() }?.toLong()?.coerceIn(0, 86_400_000)
            val animate = source.optBoolean("animate", false)
            val duration = source.optInt("duration", 0).takeIf { it > 0 }?.coerceIn(1, 60_000)
            val easing = source.optString("easing", "ease-out")
            return CanvasPatch(id, key, scene, node, props, play, seek, animate, duration, easing)
        }

        private fun parseValue(raw: Any?): CanvasValue? = when (raw) {
            null -> null
            is Boolean -> CanvasValue.Bool(raw)
            is Int -> CanvasValue.Num(raw.toFloat())
            is Long -> CanvasValue.Num(raw.toFloat())
            is Double -> CanvasValue.Num(raw.toFloat())
            is Float -> CanvasValue.Num(raw)
            is String -> CanvasValue.Str(raw)
            else -> null
        }
    }
}

data class CanvasPatchResult(
    val scene: CanvasScene,
    val changes: List<CanvasPropChange>,
    val nodeFound: Boolean
)

object CanvasPath {
    data class Parsed(val points: List<CanvasPoint>, val closed: Boolean)

    /** 支持 M/L/H/V/C/Q/S/T/A/Z 的 SVG path 子集；曲线会被展开成点，不做贝塞尔控制柄编辑。 */
    fun parse(d: String): Parsed {
        val pts = mutableListOf<CanvasPoint>()
        var cx = 0f
        var cy = 0f
        var sx = 0f
        var sy = 0f
        var lastCtrlX = 0f
        var lastCtrlY = 0f
        var lastCmd = ' '
        var closed = false
        val s = d.trim()
        var i = 0

        fun add(x: Float, y: Float) {
            if (pts.size < CanvasSpec.MAX_POINTS) pts.add(CanvasPoint(x, y))
        }

        fun readNum(): Float? {
            while (i < s.length && (s[i] == ' ' || s[i] == ',' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t')) i++
            if (i >= s.length) return null
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c.isDigit()) { i++; continue }
                if (c == '.' && !dot) { dot = true; i++; continue }
                if ((c == 'e' || c == 'E') && i + 1 < s.length) {
                    i++
                    if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
                    continue
                }
                break
            }
            return s.substring(start, i).toFloatOrNull()
        }

        fun cubic(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            val steps = 14
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val u = 1f - t
                val px = u * u * u * cx + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3
                val py = u * u * u * cy + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3
                add(px, py)
            }
        }

        fun quad(x1: Float, y1: Float, x2: Float, y2: Float) {
            val steps = 12
            for (k in 1..steps) {
                val t = k.toFloat() / steps
                val u = 1f - t
                val px = u * u * cx + 2f * u * t * x1 + t * t * x2
                val py = u * u * cy + 2f * u * t * y1 + t * t * y2
                add(px, py)
            }
        }

        while (i < s.length && pts.size < CanvasSpec.MAX_POINTS) {
            while (i < s.length && s[i].isWhitespace()) i++
            if (i >= s.length) break
            val c = s[i]
            val cmd: Char
            if (c.isLetter()) {
                cmd = c
                i++
            } else {
                cmd = when (lastCmd) {
                    'M' -> 'L'
                    'm' -> 'l'
                    else -> lastCmd
                }
            }
            lastCmd = cmd
            when (cmd) {
                'M', 'm' -> {
                    val x = readNum() ?: break
                    val y = readNum() ?: break
                    if (cmd == 'm') { cx += x; cy += y } else { cx = x; cy = y }
                    sx = cx; sy = cy
                    add(cx, cy)
                }
                'L', 'l' -> {
                    val x = readNum() ?: break
                    val y = readNum() ?: break
                    if (cmd == 'l') { cx += x; cy += y } else { cx = x; cy = y }
                    add(cx, cy)
                }
                'H', 'h' -> {
                    val x = readNum() ?: break
                    cx = if (cmd == 'h') cx + x else x
                    add(cx, cy)
                }
                'V', 'v' -> {
                    val y = readNum() ?: break
                    cy = if (cmd == 'v') cy + y else y
                    add(cx, cy)
                }
                'C', 'c' -> {
                    var x1 = readNum() ?: break
                    var y1 = readNum() ?: break
                    var x2 = readNum() ?: break
                    var y2 = readNum() ?: break
                    var x3 = readNum() ?: break
                    var y3 = readNum() ?: break
                    if (cmd == 'c') {
                        x1 += cx; y1 += cy; x2 += cx; y2 += cy; x3 += cx; y3 += cy
                    }
                    cubic(x1, y1, x2, y2, x3, y3)
                    lastCtrlX = x2; lastCtrlY = y2
                    cx = x3; cy = y3
                }
                'S', 's' -> {
                    var x2 = readNum() ?: break
                    var y2 = readNum() ?: break
                    var x3 = readNum() ?: break
                    var y3 = readNum() ?: break
                    if (cmd == 's') { x2 += cx; y2 += cy; x3 += cx; y3 += cy }
                    val x1 = 2f * cx - lastCtrlX
                    val y1 = 2f * cy - lastCtrlY
                    cubic(x1, y1, x2, y2, x3, y3)
                    lastCtrlX = x2; lastCtrlY = y2
                    cx = x3; cy = y3
                }
                'Q', 'q' -> {
                    var x1 = readNum() ?: break
                    var y1 = readNum() ?: break
                    var x2 = readNum() ?: break
                    var y2 = readNum() ?: break
                    if (cmd == 'q') { x1 += cx; y1 += cy; x2 += cx; y2 += cy }
                    quad(x1, y1, x2, y2)
                    lastCtrlX = x1; lastCtrlY = y1
                    cx = x2; cy = y2
                }
                'T', 't' -> {
                    var x2 = readNum() ?: break
                    var y2 = readNum() ?: break
                    if (cmd == 't') { x2 += cx; y2 += cy }
                    val x1 = 2f * cx - lastCtrlX
                    val y1 = 2f * cy - lastCtrlY
                    quad(x1, y1, x2, y2)
                    lastCtrlX = x1; lastCtrlY = y1
                    cx = x2; cy = y2
                }
                'A', 'a' -> {
                    // 弧线按端点直线近似（不提供钢笔控制柄；复杂弧线建议先转成路径点）。
                    readNum(); readNum(); readNum(); readNum(); readNum()
                    var x = readNum() ?: break
                    var y = readNum() ?: break
                    if (cmd == 'a') { x += cx; y += cy }
                    cx = x; cy = y
                    add(cx, cy)
                }
                'Z', 'z' -> {
                    closed = true
                    cx = sx; cy = sy
                    if (pts.isNotEmpty()) add(sx, sy)
                    break
                }
                else -> break
            }
        }
        return Parsed(pts, closed)
    }

    fun resample(points: List<CanvasPoint>, count: Int): List<CanvasPoint> {
        if (points.isEmpty()) return emptyList()
        if (points.size == 1) return List(count) { points[0] }
        val n = count.coerceIn(2, CanvasSpec.MAX_POINTS)
        val lengths = FloatArray(points.size)
        var total = 0f
        for (i in 1 until points.size) {
            val dx = points[i].x - points[i - 1].x
            val dy = points[i].y - points[i - 1].y
            total += sqrt(dx * dx + dy * dy)
            lengths[i] = total
        }
        if (total <= 0.0001f) return List(n) { points[0] }
        val out = ArrayList<CanvasPoint>(n)
        for (k in 0 until n) {
            val target = total * k / (n - 1).toFloat()
            var i = 1
            while (i < lengths.size && lengths[i] < target) i++
            if (i >= points.size) i = points.size - 1
            val prev = lengths[i - 1]
            val seg = lengths[i] - prev
            val t = if (seg <= 0.0001f) 0f else (target - prev) / seg
            out.add(
                CanvasPoint(
                    CanvasSpec.lerp(points[i - 1].x, points[i].x, t),
                    CanvasSpec.lerp(points[i - 1].y, points[i].y, t)
                )
            )
        }
        return out
    }

    fun morph(from: List<CanvasPoint>, to: List<CanvasPoint>, t: Float): List<CanvasPoint> {
        if (from.isEmpty() || to.isEmpty()) return if (t < 0.5f) from else to
        val n = max(from.size, to.size).coerceIn(2, 160)
        val a = resample(from, n)
        val b = resample(to, n)
        return List(n) { i -> CanvasPoint(CanvasSpec.lerp(a[i].x, b[i].x, t), CanvasSpec.lerp(a[i].y, b[i].y, t)) }
    }
}

object CanvasEasing {
    fun eval(t: Float, easing: String): Float {
        val x = t.coerceIn(0f, 1f)
        val name = easing.trim()
        if (name.startsWith("cubic-bezier(")) {
            val nums = Regex("""[-+]?[0-9]*\.?[0-9]+(?:[eE][-+]?[0-9]+)?""")
                .findAll(name).mapNotNull { it.value.toFloatOrNull() }.toList()
            if (nums.size >= 4) return cubicBezier(x, nums[0], nums[1], nums[2], nums[3])
            return easeInOut(x)
        }
        if (name.startsWith("spring")) {
            val damping = Regex("""damping\s*=\s*([0-9.]+)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 6f
            val freq = Regex("""frequency\s*=\s*([0-9.]+)""").find(name)?.groupValues?.get(1)?.toFloatOrNull() ?: 2.2f
            val d = damping.coerceIn(1f, 30f)
            if (x >= 1f) return 1f
            return (1f - kotlin.math.exp(-d * x) * cos(freq * 2f * Math.PI.toFloat() * x)).coerceIn(-0.5f, 1.5f)
        }
        if (name.startsWith("steps(")) {
            val n = Regex("""steps\(\s*([0-9]+)""").find(name)?.groupValues?.get(1)?.toIntOrNull() ?: 4
            if (n <= 1) return if (x >= 1f) 1f else 0f
            return floor(x * n).coerceAtMost((n - 1).toFloat()) / (n - 1).toFloat()
        }
        return when (name.lowercase()) {
            "linear" -> x
            "ease" -> cubicBezier(x, 0.25f, 0.1f, 0.25f, 1f)
            "ease-in" -> x * x * x
            "ease-out" -> 1f - (1f - x).pow(3)
            "ease-in-out" -> easeInOut(x)
            "ease-in-quad" -> x * x
            "ease-out-quad" -> 1f - (1f - x) * (1f - x)
            "back" -> {
                val c = 1.70158f
                (x - 1f) * (x - 1f) * ((c + 1f) * (x - 1f) + c) + 1f
            }
            "elastic" -> {
                if (x == 0f || x == 1f) x
                else {
                    val p = 0.3f
                    val s = p / 4f
                    (2f.pow(-10f * x) * sin((x - s) * (2f * Math.PI.toFloat()) / p) + 1f)
                }
            }
            else -> easeInOut(x)
        }
    }

    private fun easeInOut(x: Float): Float =
        if (x < 0.5f) 4f * x * x * x else 1f - (-2f * x + 2f).pow(3) / 2f

    private fun cubicBezier(t: Float, x1: Float, y1: Float, x2: Float, y2: Float): Float {
        fun curve(a: Float, b: Float, u: Float): Float {
            val v = 1f - u
            return 3f * v * v * u * a + 3f * v * u * u * b + u * u * u
        }
        var lo = 0f
        var hi = 1f
        var u = t
        repeat(18) {
            val x = curve(x1.coerceIn(0f, 1f), x2.coerceIn(0f, 1f), u)
            if (x < t) lo = u else hi = u
            u = (lo + hi) / 2f
        }
        return curve(y1, y2, u)
    }
}

data class CanvasFrame(
    val nodes: List<CanvasNode>,
    val cameraX: Float,
    val cameraY: Float,
    val cameraZoom: Float,
    val timeMs: Long
)

object CanvasAnimator {
    private data class Override(
        val numbers: MutableMap<String, Float> = mutableMapOf(),
        val colors: MutableMap<String, Int> = mutableMapOf(),
        val paths: MutableMap<String, List<CanvasPoint>> = mutableMapOf()
    )

    fun evaluate(scene: CanvasScene, timeMs: Long): CanvasFrame {
        val index = scene.flatten().associateBy { it.id }
        val overrides = HashMap<String, Override>()
        var camX = scene.cameraX
        var camY = scene.cameraY
        var camZ = scene.cameraZoom

        for (anim in scene.animations.take(CanvasSpec.MAX_ANIMATIONS)) {
            val targets = if (anim.targets.isNotEmpty()) anim.targets else listOf(anim.target)
            for ((idx, target) in targets.withIndex()) {
                if (target.isBlank()) continue
                val effectiveDelay = anim.delayMs.toLong() + idx.toLong() * anim.staggerMs.toLong()
                val phase = phaseAt(timeMs, effectiveDelay, anim.durationMs, anim.repeat, anim.alternate)
                if (target == CanvasSpec.CAMERA_TARGET) {
                    val base = when (anim.property) {
                        "cameraX" -> scene.cameraX
                        "cameraY" -> scene.cameraY
                        "cameraZoom" -> scene.cameraZoom
                        else -> null
                    } ?: continue
                    val v = valueOf(anim, phase, base) ?: continue
                    when (anim.property) {
                        "cameraX" -> camX = v
                        "cameraY" -> camY = v
                        "cameraZoom" -> camZ = v.coerceIn(0.05f, 20f)
                    }
                    continue
                }
                val node = index[target] ?: continue
                val ov = overrides.getOrPut(target) { Override() }
                if (anim.property in CanvasSpec.COLOR_PROPS && anim.fromColor.isNotEmpty() && anim.toColor.isNotEmpty()) {
                    val a = CanvasSpec.color(anim.fromColor, node.baseColor(anim.property) ?: 0)
                    val b = CanvasSpec.color(anim.toColor, a)
                    ov.colors[anim.property] = CanvasSpec.lerpColor(a, b, CanvasEasing.eval(phase, anim.easing))
                    continue
                }
                if ((anim.property == "path" || anim.property == "d") &&
                    (anim.fromPath.isNotEmpty() || anim.toPath.isNotEmpty())
                ) {
                    val from = CanvasPath.parse(anim.fromPath).points
                    val to = CanvasPath.parse(anim.toPath).points
                    if (from.isNotEmpty() && to.isNotEmpty()) {
                        ov.paths["points"] = CanvasPath.morph(from, to, CanvasEasing.eval(phase, anim.easing))
                    }
                    continue
                }
                val base = node.baseNumber(anim.property) ?: continue
                val v = valueOf(anim, phase, base) ?: continue
                ov.numbers[anim.property] = v
            }
        }

        fun applyNode(n: CanvasNode): CanvasNode {
            val ov = overrides[n.id]
            var out = n
            if (ov != null) {
                for ((k, v) in ov.numbers) out = out.withNumber(k, v)
                for ((k, c) in ov.colors) out = out.withString(k, CanvasSpec.colorHex(c))
                ov.paths["points"]?.let { out = out.copy(points = it) }
            }
            if (n.children.isNotEmpty()) out = out.copy(children = n.children.map { applyNode(it) })
            return out
        }

        return CanvasFrame(scene.nodes.map { applyNode(it) }, camX, camY, camZ.coerceIn(0.05f, 20f), timeMs)
    }

    private fun phaseAt(timeMs: Long, delayMs: Long, durationMs: Int, repeat: Int, alternate: Boolean): Float {
        val local = timeMs - delayMs
        if (durationMs <= 0) return 1f
        if (local <= 0) return 0f
        val cycles = local.toFloat() / durationMs
        val infinite = repeat <= 0
        if (!infinite && cycles >= repeat) {
            val lastCycle = (repeat - 1).toFloat()
            return if (alternate && (floor(lastCycle).toInt() % 2 == 1)) 0f else 1f
        }
        val whole = floor(cycles).toInt()
        var phase = cycles - whole
        if (alternate && whole % 2 == 1) phase = 1f - phase
        return phase.coerceIn(0f, 1f)
    }

    private fun valueOf(anim: CanvasAnimation, phase: Float, base: Float): Float? {
        if (anim.keyframes.isNotEmpty()) {
            val kfs = anim.keyframes.sortedBy { it.at }
            if (phase <= kfs.first().at) return kfs.first().value
            if (phase >= kfs.last().at) return kfs.last().value
            for (i in 1 until kfs.size) {
                val b = kfs[i]
                if (phase <= b.at) {
                    val a = kfs[i - 1]
                    val span = (b.at - a.at).takeIf { abs(it) > 0.00001f } ?: 1f
                    val t = CanvasEasing.eval((phase - a.at) / span, b.easing)
                    return CanvasSpec.lerp(a.value, b.value, t)
                }
            }
        }
        val from = anim.from ?: base
        val to = anim.to ?: base
        return CanvasSpec.lerp(from, to, CanvasEasing.eval(phase, anim.easing))
    }
}

object CanvasSceneCodec {
    fun parseScene(text: String): CanvasScene = parseScene(MiniJson.parse(text))

    fun parseScene(obj: MiniJson.Obj): CanvasScene {
        val width = CanvasSpec.clamp(obj.optDouble("width", CanvasSpec.DEFAULT_WIDTH.toDouble()).toFloat(), 32f, 4000f)
        val height = CanvasSpec.clamp(obj.optDouble("height", CanvasSpec.DEFAULT_HEIGHT.toDouble()).toFloat(), 32f, 4000f)
        val ids = HashSet<String>()
        var autoId = 0
        fun nextId(): String {
            var id: String
            do {
                autoId++
                id = "n$autoId"
            } while (ids.contains(id))
            ids.add(id)
            return id
        }
        var count = 0
        fun parseNodes(arr: MiniJson.Arr?, depth: Int): List<CanvasNode> {
            if (arr == null || depth > CanvasSpec.MAX_DEPTH) return emptyList()
            val out = mutableListOf<CanvasNode>()
            for (i in 0 until arr.length()) {
                if (count >= CanvasSpec.MAX_NODES) break
                val o = arr.getObj(i) ?: continue
                val n = parseNode(o, depth, ids, ::nextId) ?: continue
                count++
                out.add(n)
            }
            return out
        }
        val nodes = parseNodes(obj.optArr("nodes"), 0)
        val anims = mutableListOf<CanvasAnimation>()
        obj.optArr("animations")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (anims.size >= CanvasSpec.MAX_ANIMATIONS) break
                arr.getObj(i)?.let { parseAnimation(it) }?.let { anims.add(it) }
            }
        }
        val particles = obj.optObj("particles")?.let { p ->
            CanvasParticle(
                count = CanvasSpec.clampInt(p.optInt("count", 0), 0, 300),
                color = p.optString("color", "#66FFFFFF"),
                size = CanvasSpec.clamp(p.optDouble("size", 2.0).toFloat(), 0.5f, 16f),
                speed = CanvasSpec.clamp(p.optDouble("speed", 12.0).toFloat(), 0f, 200f),
                drift = CanvasSpec.clamp(p.optDouble("drift", 0.6).toFloat(), 0f, 10f),
                seed = p.optInt("seed", 7)
            )
        } ?: CanvasParticle()
        return CanvasScene(
            version = CanvasSpec.VERSION,
            width = width,
            height = height,
            background = obj.optString("background", "#00000000"),
            nodes = nodes,
            animations = anims,
            cameraX = CanvasSpec.clamp(obj.optDouble("cameraX", 0.0).toFloat(), -100000f, 100000f),
            cameraY = CanvasSpec.clamp(obj.optDouble("cameraY", 0.0).toFloat(), -100000f, 100000f),
            cameraZoom = CanvasSpec.clamp(obj.optDouble("cameraZoom", 1.0).toFloat(), 0.05f, 20f),
            particles = particles
        )
    }

    private fun parseNode(
        o: MiniJson.Obj,
        depth: Int,
        ids: MutableSet<String>,
        nextId: () -> String
    ): CanvasNode? {
        val rawType = o.optString("type", CanvasSpec.TYPE_RECT)
        val type = if (rawType in CanvasSpec.NODE_TYPES) rawType else CanvasSpec.TYPE_RECT
        var id = CanvasSpec.safeId(o.optString("id", ""))
        if (id.isEmpty()) id = nextId()
        while (ids.contains(id)) id = "${id}_${nextId()}"
        ids.add(id)
        val d = o.optString("d", o.optString("path", "")).take(20000)
        val parsedPath = if (d.isNotEmpty()) CanvasPath.parse(d) else CanvasPath.Parsed(emptyList(), false)
        var points = parsePoints(o.optArr("points"))
        var nodeX = CanvasSpec.clamp(o.optDouble("x", 0.0).toFloat(), -100000f, 100000f)
        var nodeY = CanvasSpec.clamp(o.optDouble("y", 0.0).toFloat(), -100000f, 100000f)
        var nodeW = CanvasSpec.clamp(o.optDouble("w", 0.0).toFloat(), 0f, 100000f)
        var nodeH = CanvasSpec.clamp(o.optDouble("h", 0.0).toFloat(), 0f, 100000f)
        if (points.isEmpty() && parsedPath.points.isNotEmpty()) {
            // SVG path 输入是场景坐标；内部统一转成局部点，节点 x/y 就是包围盒原点。
            val minX = parsedPath.points.minOf { it.x }
            val minY = parsedPath.points.minOf { it.y }
            val maxX = parsedPath.points.maxOf { it.x }
            val maxY = parsedPath.points.maxOf { it.y }
            points = parsedPath.points.map { CanvasPoint(it.x - minX, it.y - minY) }
            if (!o.has("x")) nodeX = minX
            if (!o.has("y")) nodeY = minY
            if (!o.has("w")) nodeW = maxX - minX
            if (!o.has("h")) nodeH = maxY - minY
        } else if (points.isNotEmpty()) {
            // points 是节点局部坐标。缺省包围盒按点集补齐，方便只传 points 的 JSON。
            if (!o.has("w")) nodeW = points.maxOf { it.x }
            if (!o.has("h")) nodeH = points.maxOf { it.y }
        }
        val children = if (type == CanvasSpec.TYPE_GROUP && depth < CanvasSpec.MAX_DEPTH) {
            val arr = o.optArr("children") ?: o.optArr("nodes")
            val out = mutableListOf<CanvasNode>()
            if (arr != null) {
                var c = 0
                for (i in 0 until arr.length()) {
                    if (c >= CanvasSpec.MAX_NODES) break
                    val co = arr.getObj(i) ?: continue
                    parseNode(co, depth + 1, ids, nextId)?.let { out.add(it); c++ }
                }
            }
            out
        } else emptyList()

        return CanvasNode(
            id = id,
            type = type,
            x = nodeX,
            y = nodeY,
            w = nodeW,
            h = nodeH,
            rotation = CanvasSpec.clamp(o.optDouble("rotation", 0.0).toFloat(), -100000f, 100000f),
            scaleX = CanvasSpec.clamp(o.optDouble("scaleX", 1.0).toFloat(), 0.01f, 100f),
            scaleY = CanvasSpec.clamp(o.optDouble("scaleY", 1.0).toFloat(), 0.01f, 100f),
            alpha = CanvasSpec.clamp(o.optDouble("alpha", 1.0).toFloat(), 0f, 1f),
            fill = if (o.has("fill")) o.optString("fill", "#00000000") else "#00000000",
            stroke = o.optString("stroke", ""),
            strokeWidth = CanvasSpec.clamp(o.optDouble("strokeWidth", 0.0).toFloat(), 0f, 10000f),
            radius = CanvasSpec.clamp(o.optDouble("radius", 0.0).toFloat(), 0f, 10000f),
            text = CanvasSpec.safeText(o.optString("text", "")),
            textSize = CanvasSpec.clamp(o.optDouble("textSize", 13.0).toFloat(), 1f, 512f),
            textColor = o.optString("textColor", "#E6E6E6"),
            textAlign = o.optString("textAlign", CanvasSpec.ALIGN_LEFT),
            bold = o.optBoolean("bold", false),
            italic = o.optBoolean("italic", false),
            monospace = o.optBoolean("monospace", true),
            points = points.take(CanvasSpec.MAX_POINTS),
            closed = o.optBoolean("closed", parsedPath.closed),
            d = d,
            src = o.optString("src", "").take(CanvasSpec.MAX_SRC),
            fit = o.optString("fit", CanvasSpec.FIT_CONTAIN),
            tint = o.optString("tint", ""),
            progress = CanvasSpec.clamp(o.optDouble("progress", 0.0).toFloat(), 0f, 1f),
            trackColor = o.optString("trackColor", "#33FFFFFF"),
            barColor = o.optString("barColor", "#2D7D46"),
            gradientFrom = o.optString("gradientFrom", ""),
            gradientTo = o.optString("gradientTo", ""),
            showLabel = o.optBoolean("showLabel", false),
            labelFormat = CanvasSpec.safeText(o.optString("labelFormat", "{percent}%")),
            labelColor = o.optString("labelColor", "#E6E6E6"),
            labelSize = CanvasSpec.clamp(o.optDouble("labelSize", 12.0).toFloat(), 1f, 512f),
            dash = CanvasSpec.clamp(o.optDouble("dash", 0.0).toFloat(), 0f, 10000f),
            dashGap = CanvasSpec.clamp(o.optDouble("dashGap", 0.0).toFloat(), 0f, 10000f),
            dashOffset = CanvasSpec.clamp(o.optDouble("dashOffset", 0.0).toFloat(), -100000f, 100000f),
            trim = CanvasSpec.clamp(o.optDouble("trim", 1.0).toFloat(), 0f, 1f),
            children = children,
            action = o.optString("action", "").take(256),
            visible = o.optBoolean("visible", true),
            hitTest = o.optBoolean("hitTest", true),
            locked = o.optBoolean("locked", false),
            shadow = o.optBoolean("shadow", false),
            shadowRadius = CanvasSpec.clamp(o.optDouble("shadowRadius", 8.0).toFloat(), 0f, 10000f),
            name = o.optString("name", "").take(128)
        )
    }

    private fun parsePoints(arr: MiniJson.Arr?): List<CanvasPoint> {
        if (arr == null) return emptyList()
        val out = mutableListOf<CanvasPoint>()
        for (i in 0 until arr.length()) {
            if (out.size >= CanvasSpec.MAX_POINTS) break
            val pObj = arr.getObj(i)
            if (pObj != null) {
                out.add(
                    CanvasPoint(
                        CanvasSpec.clamp(pObj.optDouble("x", 0.0).toFloat(), -100000f, 100000f),
                        CanvasSpec.clamp(pObj.optDouble("y", 0.0).toFloat(), -100000f, 100000f)
                    )
                )
                continue
            }
            when (val raw = arr.raw(i)) {
                is MiniJson.Arr -> {
                    val x = (raw.raw(0) as? Number)?.toFloat() ?: 0f
                    val y = (raw.raw(1) as? Number)?.toFloat() ?: 0f
                    out.add(CanvasPoint(CanvasSpec.clamp(x, -100000f, 100000f), CanvasSpec.clamp(y, -100000f, 100000f)))
                }
                is String -> {
                    val parts = raw.split(",")
                    if (parts.size >= 2) {
                        out.add(CanvasPoint(parts[0].trim().toFloatOrNull() ?: 0f, parts[1].trim().toFloatOrNull() ?: 0f))
                    }
                }
            }
        }
        return out
    }

    private fun parseAnimation(o: MiniJson.Obj): CanvasAnimation? {
        val target = o.optString("target", "")
        val targets = mutableListOf<String>()
        o.optArr("targets")?.let { arr ->
            for (i in 0 until arr.length()) arr.getString(i)?.let { if (it.isNotBlank()) targets.add(it.take(CanvasSpec.MAX_ID)) }
        }
        if (target.isBlank() && targets.isEmpty()) return null
        val keyframes = mutableListOf<CanvasKeyframe>()
        o.optArr("keyframes")?.let { arr ->
            for (i in 0 until arr.length()) {
                if (keyframes.size >= CanvasSpec.MAX_KEYFRAMES) break
                val k = arr.getObj(i) ?: continue
                keyframes.add(
                    CanvasKeyframe(
                        at = CanvasSpec.clamp(k.optDouble("at", 0.0).toFloat(), 0f, 1f),
                        value = k.optDouble("value", 0.0).toFloat(),
                        easing = k.optString("easing", "ease-in-out"),
                        path = k.optString("path", "")
                    )
                )
            }
        }
        val repeat = o.optInt("repeat", if (o.optBoolean("loop", false)) -1 else 1)
        return CanvasAnimation(
            id = o.optString("id", "").take(CanvasSpec.MAX_ID),
            target = target.take(CanvasSpec.MAX_ID),
            targets = targets,
            property = o.optString("property", "alpha"),
            from = if (o.has("from")) o.optDouble("from", 0.0).toFloat() else null,
            to = if (o.has("to")) o.optDouble("to", 0.0).toFloat() else null,
            fromColor = o.optString("fromColor", ""),
            toColor = o.optString("toColor", ""),
            fromPath = o.optString("fromPath", ""),
            toPath = o.optString("toPath", ""),
            durationMs = CanvasSpec.clampInt(o.optInt("duration", 600), 0, 600000),
            delayMs = CanvasSpec.clampInt(o.optInt("delay", 0), 0, 600000),
            repeat = CanvasSpec.clampInt(repeat, -1, 10000),
            alternate = o.optBoolean("alternate", false),
            easing = o.optString("easing", "ease-out"),
            staggerMs = CanvasSpec.clampInt(o.optInt("stagger", 0), 0, 60000),
            keyframes = keyframes
        )
    }

    fun sceneToJson(scene: CanvasScene): MiniJson.Obj {
        val o = MiniJson.Obj()
            .put("version", CanvasSpec.VERSION)
            .put("width", scene.width.toDouble())
            .put("height", scene.height.toDouble())
            .put("background", scene.background)
            .put("cameraX", scene.cameraX.toDouble())
            .put("cameraY", scene.cameraY.toDouble())
            .put("cameraZoom", scene.cameraZoom.toDouble())
        val nodes = MiniJson.Arr()
        for (n in scene.nodes) nodes.put(nodeToJson(n))
        o.put("nodes", nodes)
        val anims = MiniJson.Arr()
        for (a in scene.animations) anims.put(animationToJson(a))
        o.put("animations", anims)
        o.put(
            "particles",
            MiniJson.Obj()
                .put("count", scene.particles.count)
                .put("color", scene.particles.color)
                .put("size", scene.particles.size.toDouble())
                .put("speed", scene.particles.speed.toDouble())
                .put("drift", scene.particles.drift.toDouble())
                .put("seed", scene.particles.seed)
        )
        return o
    }

    fun sceneToJsonString(scene: CanvasScene): String = sceneToJson(scene).toString()

    fun nodeToJson(n: CanvasNode): MiniJson.Obj {
        val o = MiniJson.Obj()
            .put("id", n.id)
            .put("type", n.type)
            .put("x", n.x.toDouble())
            .put("y", n.y.toDouble())
            .put("w", n.w.toDouble())
            .put("h", n.h.toDouble())
            .put("rotation", n.rotation.toDouble())
            .put("scaleX", n.scaleX.toDouble())
            .put("scaleY", n.scaleY.toDouble())
            .put("alpha", n.alpha.toDouble())
            .put("fill", n.fill)
            .put("stroke", n.stroke)
            .put("strokeWidth", n.strokeWidth.toDouble())
            .put("radius", n.radius.toDouble())
            .put("text", n.text)
            .put("textSize", n.textSize.toDouble())
            .put("textColor", n.textColor)
            .put("textAlign", n.textAlign)
            .put("bold", n.bold)
            .put("italic", n.italic)
            .put("monospace", n.monospace)
            .put("progress", n.progress.toDouble())
            .put("trackColor", n.trackColor)
            .put("barColor", n.barColor)
            .put("gradientFrom", n.gradientFrom)
            .put("gradientTo", n.gradientTo)
            .put("showLabel", n.showLabel)
            .put("labelFormat", n.labelFormat)
            .put("labelColor", n.labelColor)
            .put("labelSize", n.labelSize.toDouble())
            .put("dash", n.dash.toDouble())
            .put("dashGap", n.dashGap.toDouble())
            .put("dashOffset", n.dashOffset.toDouble())
            .put("trim", n.trim.toDouble())
            .put("closed", n.closed)
            .put("d", n.d)
            .put("src", n.src)
            .put("fit", n.fit)
            .put("tint", n.tint)
            .put("action", n.action)
            .put("visible", n.visible)
            .put("hitTest", n.hitTest)
            .put("locked", n.locked)
            .put("shadow", n.shadow)
            .put("shadowRadius", n.shadowRadius.toDouble())
            .put("name", n.name)
        val pts = MiniJson.Arr()
        for (p in n.points) pts.put(MiniJson.Obj().put("x", p.x.toDouble()).put("y", p.y.toDouble()))
        o.put("points", pts)
        if (n.children.isNotEmpty()) {
            val ch = MiniJson.Arr()
            for (c in n.children) ch.put(nodeToJson(c))
            o.put("children", ch)
        }
        return o
    }

    private fun animationToJson(a: CanvasAnimation): MiniJson.Obj {
        val o = MiniJson.Obj()
            .put("id", a.id)
            .put("target", a.target)
            .put("property", a.property)
            .put("duration", a.durationMs)
            .put("delay", a.delayMs)
            .put("repeat", a.repeat)
            .put("alternate", a.alternate)
            .put("easing", a.easing)
            .put("stagger", a.staggerMs)
        if (a.targets.isNotEmpty()) {
            val arr = MiniJson.Arr()
            for (t in a.targets) arr.put(t)
            o.put("targets", arr)
        }
        if (a.from != null) o.put("from", a.from.toDouble())
        if (a.to != null) o.put("to", a.to.toDouble())
        if (a.fromColor.isNotEmpty()) o.put("fromColor", a.fromColor)
        if (a.toColor.isNotEmpty()) o.put("toColor", a.toColor)
        if (a.fromPath.isNotEmpty()) o.put("fromPath", a.fromPath)
        if (a.toPath.isNotEmpty()) o.put("toPath", a.toPath)
        if (a.keyframes.isNotEmpty()) {
            val arr = MiniJson.Arr()
            for (k in a.keyframes) {
                arr.put(
                    MiniJson.Obj()
                        .put("at", k.at.toDouble())
                        .put("value", k.value.toDouble())
                        .put("easing", k.easing)
                        .put("path", k.path)
                )
            }
            o.put("keyframes", arr)
        }
        return o
    }
}

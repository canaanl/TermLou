package com.workspace.proot

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * 启动工坊的手写笔刷：只把笔迹落成点阵格子，不生成、也不保存矢量路径。
 *
 * 坐标统一用“格子坐标”：x=0 是画板左边缘，格子 (r,c) 的中心是
 * (c + 0.5, r + 0.5)。这样笔刷半径的单位就是“格”，和 96 列点阵同源。
 *
 * 连续性来自两件事：
 *  - 触摸采样点之间的线段按 [STAMP_STEP] 细分，快速划动也不会跳格；
 *  - 每个细分点按圆形笔刷覆盖周围格子，压感/速度只改半径，不改数据结构。
 */
object SplashBrush {

    /** 基准笔宽（格）。工坊滑块把它放大 10 倍存成整数刻度。 */
    const val DEFAULT_RADIUS = 1.2f
    const val MIN_RADIUS = 0.8f
    const val MAX_RADIUS = 4.0f

    /** 相邻采样点之间的细分步长（格）。越小越连续，但落格次数越多。 */
    const val STAMP_STEP = 0.22f

    /**
     * 笔刷边缘的软过渡宽度（格）。边缘收窄后，半径变化会真正反映成
     * 点亮格数的变化，而不是只改一圈很淡的光晕。
     */
    private const val EDGE = 0.55f

    private const val STYLUS_PRESSURE_BASE = 0.55f
    private const val STYLUS_PRESSURE_GAIN = 1.0f
    private const val FINGER_SPEED_REF = 0.28f
    private const val FINGER_SLOW_SCALE = 1.60f
    private const val FINGER_FAST_DROP = 0.90f
    private const val MIN_SEGMENT_MS = 1L
    private const val CELL_CENTER = 0.5f
    private const val MIN_COVERAGE = 0.02f
    private const val CONTACT_MIN = 1.0f
    private const val CONTACT_MAX = 1.15f

    data class Point(
        val x: Float,
        val y: Float,
        val pressure: Float = 1f,
        val timeMs: Long = 0L,
        val contactCells: Float = 1f
    )

    data class Stamp(val x: Float, val y: Float, val radius: Float)

    /**
     * 根据基准笔宽、压感和移动速度求这一笔的半径。
     * 手写笔优先用压感；手指没有可靠压感，改用“快细慢粗”。
     * contactCells 是接触面积（格），用于手指轻微修正。
     */
    fun radius(
        base: Float,
        pressure: Float,
        speedCellsPerMs: Float,
        stylus: Boolean,
        contactCells: Float = 1f
    ): Float {
        val b = base.coerceIn(MIN_RADIUS, MAX_RADIUS)
        val scale = if (stylus) {
            STYLUS_PRESSURE_BASE + STYLUS_PRESSURE_GAIN * pressure.coerceIn(0f, 1f)
        } else {
            val fast = (speedCellsPerMs / FINGER_SPEED_REF).coerceIn(0f, 1f)
            FINGER_SLOW_SCALE - FINGER_FAST_DROP * fast
        }
        val contact = contactCells.coerceIn(CONTACT_MIN, CONTACT_MAX)
        return (b * scale * contact).coerceIn(MIN_RADIUS, MAX_RADIUS)
    }

    /**
     * 把 from→to 细化成一串笔刷落点。包含两端；压力按段内线性插值，
     * 速度按整段位移/耗时估算。
     */
    @Suppress("LongParameterList")
    fun line(
        from: Point,
        to: Point,
        base: Float,
        stylus: Boolean,
        step: Float = STAMP_STEP,
        speedOverride: Float? = null
    ): List<Stamp> {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val dist = hypot(dx, dy)
        val steps = ceil(dist / step).toInt().coerceAtLeast(1)
        val dt = (to.timeMs - from.timeMs).coerceAtLeast(MIN_SEGMENT_MS).toFloat()
        val speed = speedOverride ?: (dist / dt)
        val out = ArrayList<Stamp>(steps + 1)
        for (i in 0..steps) {
            val t = i / steps.toFloat()
            val pressure = from.pressure + (to.pressure - from.pressure) * t
            val contact = from.contactCells + (to.contactCells - from.contactCells) * t
            out.add(
                Stamp(
                    from.x + dx * t,
                    from.y + dy * t,
                    radius(base, pressure, speed, stylus, contact)
                )
            )
        }
        return out
    }

    /**
     * 把一个圆形笔刷落点写进 cells。
     *
     * 只升不降：同一格被多次覆盖时取最亮的级别。这样同一笔反复经过不会
     * 把已经画亮的地方擦暗。若传入 [changes]，只在该格第一次被本次笔画
     * 修改时记录旧值（null = 原来没有），供一笔一撤销使用。
     */
    @Suppress("LongParameterList", "NestedBlockDepth")
    fun stamp(
        cells: MutableMap<Pair<Int, Int>, Int>,
        x: Float,
        y: Float,
        radius: Float,
        rows: Int,
        cols: Int,
        changes: MutableMap<Pair<Int, Int>, Int?>? = null
    ) {
        val r = radius.coerceIn(MIN_RADIUS, MAX_RADIUS)
        val minC = floor(x - r - CELL_CENTER).toInt().coerceAtLeast(0)
        val maxC = ceil(x + r - CELL_CENTER).toInt().coerceAtMost(cols - 1)
        val minR = floor(y - r - CELL_CENTER).toInt().coerceAtLeast(0)
        val maxR = ceil(y + r - CELL_CENTER).toInt().coerceAtMost(rows - 1)
        if (minC > maxC || minR > maxR) return

        for (row in minR..maxR) {
            for (col in minC..maxC) {
                val dx = (col + CELL_CENTER) - x
                val dy = (row + CELL_CENTER) - y
                val coverage = coverage(hypot(dx, dy), r)
                if (coverage <= MIN_COVERAGE) continue
                val level = (coverage * SplashTokens.LEVEL_FULL)
                    .roundToInt()
                    .coerceIn(1, SplashTokens.LEVEL_FULL)
                val key = row to col
                val old = cells[key]
                if (old == null || level > old) {
                    if (changes != null && !changes.containsKey(key)) {
                        changes[key] = old
                    }
                    cells[key] = level
                }
            }
        }
    }

    /** 圆内为 1，边缘 [EDGE] 格内平滑降到 0。 */
    private fun coverage(distance: Float, radius: Float): Float = when {
        distance >= radius -> 0f
        distance <= (radius - EDGE).coerceAtLeast(0f) -> 1f
        else -> ((radius - distance) / EDGE).coerceIn(0f, 1f)
    }
}

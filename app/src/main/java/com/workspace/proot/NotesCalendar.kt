package com.workspace.proot

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日历视图纯逻辑：把笔记/待办全集按「创建日」归档成逐日状态，再铺成整月网格。
 * 无 Android 依赖，JVM 单测可直接跑（NotesCalendarTest）。
 */
object NotesCalendar {

    /** 待办完成三态：决定格子图标的形状与配色（红=空、黄=半、绿=满）。 */
    enum class TodoState { OPEN, PARTIAL, DONE }

    /** 单日状态：有无笔记、有无标签、待办总数与已完成数。 */
    data class DayStatus(
        val hasNote: Boolean = false,
        val hasTag: Boolean = false,
        val todoTotal: Int = 0,
        val todoDone: Int = 0
    ) {
        /** 当天没有任何内容（弹窗走空态）。 */
        val isEmpty: Boolean
            get() = !hasNote && !hasTag && todoTotal <= 0

        /** 三态：null = 当天无待办（格子不画待办图标）。 */
        val todoState: TodoState?
            get() = when {
                todoTotal <= 0 -> null
                todoDone <= 0 -> TodoState.OPEN
                todoDone >= todoTotal -> TodoState.DONE
                else -> TodoState.PARTIAL
            }
    }

    /** 时间戳 → 归档日（按给定时区）；createdAt<=0 的历史数据返回 null、不落格。 */
    fun dayOf(ts: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate? =
        if (ts > 0) LocalDate.ofInstant(Instant.ofEpochMilli(ts), zone) else null

    /**
     * 逐日状态：笔记看 createdAt、待办看 createdAt（编辑不搬家）；
     * createdAt<=0 的历史数据不落任何日期格。
     */
    fun dayStatus(
        notes: List<NoteEntry>,
        todos: List<TodoItem>,
        zone: ZoneId = ZoneId.systemDefault()
    ): Map<LocalDate, DayStatus> {
        val map = LinkedHashMap<LocalDate, DayStatus>()
        for (n in notes) {
            val day = dayOf(n.createdAt, zone) ?: continue
            val cur = map[day] ?: DayStatus()
            map[day] = cur.copy(hasNote = true, hasTag = cur.hasTag || n.tags.isNotEmpty())
        }
        for (t in todos) {
            val day = dayOf(t.createdAt, zone) ?: continue
            val cur = map[day] ?: DayStatus()
            map[day] = cur.copy(
                todoTotal = cur.todoTotal + 1,
                todoDone = cur.todoDone + if (t.done) 1 else 0
            )
        }
        return map
    }

    /**
     * 整月网格：6 行 × 7 列共 42 格、周一起头，含上/下月溢出日期。
     * 溢出格由调用方按 isInMonth 判定后只显示淡化数字、不可点。
     */
    fun monthGrid(year: Int, month: Int): List<LocalDate> {
        val first = LocalDate.of(year, month, 1)
        val shift = (first.dayOfWeek.value + 6) % 7 // 周一=0 … 周日=6
        val start = first.minusDays(shift.toLong())
        return (0 until CELL_COUNT).map { start.plusDays(it.toLong()) }
    }

    /** 该日期是否属于给定年月（用于区分本月格与溢出格）。 */
    fun isInMonth(date: LocalDate, year: Int, month: Int): Boolean =
        date.year == year && date.monthValue == month

    private const val CELL_COUNT = 42
}

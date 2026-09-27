package com.workspace.proot

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
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
     * 逐日状态：笔记看 createdAt（编辑不搬家）；待办分两类——
     * **有归属笔记的算在那篇笔记的创建日**（在以往的笔记里新加待办不算今天，
     * 否则会出现「今天没笔记却有待办」的格），无归属（note="" 全局待办/老数据）
     * 才看待办自身 createdAt；createdAt<=0 的历史数据不落任何日期格。
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
        val byName = notes.associateBy { it.name }
        for (t in todos) {
            val day = dayOf(archiveTs(t, byName), zone) ?: continue
            val cur = map[day] ?: DayStatus()
            map[day] = cur.copy(
                todoTotal = cur.todoTotal + 1,
                todoDone = cur.todoDone + if (t.done) 1 else 0
            )
        }
        return map
    }

    /**
     * 待办的归档时间戳：有归属笔记 → 笔记的 createdAt（与笔记同格、同口径）；
     * 归属笔记已被删 → 退回待办自身 createdAt；归属笔记 createdAt<=0 → 返回 0，
     * 与笔记一样不落格。无归属 → 待办自身 createdAt。
     */
    private fun archiveTs(t: TodoItem, byName: Map<String, NoteEntry>): Long =
        if (t.note.isEmpty()) t.createdAt else byName[t.note]?.createdAt ?: t.createdAt

    /**
     * 整月网格：周一起头，**只补齐到本周日**——月头补上月凑满首周、月尾补到周日为止，
     * 行数 = ceil((偏移 + 当月天数) / 7)（5 或 6 行）、格数 = 行数×7，
     * 不再为了撑高度多补一整行灰格。溢出格由调用方按 isInMonth 判定：
     * 只显淡化数字、不画图标、不可点。
     */
    fun monthGrid(year: Int, month: Int): List<LocalDate> {
        val first = LocalDate.of(year, month, 1)
        val shift = (first.dayOfWeek.value + 6) % 7 // 周一=0 … 周日=6
        val start = first.minusDays(shift.toLong())
        val used = shift + first.lengthOfMonth()
        val rows = (used + COLS_PER_ROW - 1) / COLS_PER_ROW // 只补到本周日
        return (0 until rows * COLS_PER_ROW).map { start.plusDays(it.toLong()) }
    }

    /** 该日期是否属于给定年月（用于区分本月格与溢出格）。 */
    fun isInMonth(date: LocalDate, year: Int, month: Int): Boolean =
        date.year == year && date.monthValue == month

    /** 翻月判定结果：能翻 / 更早已无内容（拦住提示）/ 要翻到未来（拦住提示）。 */
    enum class ShiftVerdict { OK, BLOCK_NO_OLDER, BLOCK_FUTURE }

    /**
     * 最早有内容的月份——笔记和待办都算（待办也占格子，只看笔记会出现
     * 「往前翻还有待办格却提示到底了」）；待办按与 dayStatus 相同的归属规则换算，
     * createdAt<=0 不算。一条内容都没有返回 null，由调用方拿「当前正在看的月份」当底。
     */
    fun earliestContentMonth(
        notes: List<NoteEntry>,
        todos: List<TodoItem>,
        zone: ZoneId = ZoneId.systemDefault()
    ): YearMonth? {
        val byName = notes.associateBy { it.name }
        var earliest: YearMonth? = null
        for (ts in notes.map { it.createdAt } + todos.map { archiveTs(it, byName) }) {
            val d = dayOf(ts, zone) ?: continue
            val m = YearMonth.from(d)
            val cur = earliest
            if (cur == null || m < cur) earliest = m
        }
        return earliest
    }

    /**
     * 翻月判定：目标月晚于本月 = 未来（拦）；早于最早内容月 = 到底（拦）；
     * 范围内无内容的月份照常放行（空月正常显示）。
     */
    fun shiftVerdict(
        from: YearMonth,
        delta: Long,
        today: YearMonth,
        earliest: YearMonth
    ): ShiftVerdict {
        val target = from.plusMonths(delta)
        return when {
            target > today -> ShiftVerdict.BLOCK_FUTURE
            target < earliest -> ShiftVerdict.BLOCK_NO_OLDER
            else -> ShiftVerdict.OK
        }
    }

    private const val COLS_PER_ROW = 7
}

package com.workspace.proot

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历视图纯逻辑锁定：按创建日归档（归属笔记的待办跟着笔记的创建日走）、待办三态、
 * 周一起头且只补到本周日的整月网格。
 * 时区显式传 UTC，测试结果与跑测机时区无关。
 */
class NotesCalendarTest {

    private val zone = ZoneOffset.UTC

    private fun at(day: LocalDate): Long =
        day.atStartOfDay(zone).toInstant().toEpochMilli()

    private fun note(
        name: String,
        created: LocalDate,
        tags: List<String> = emptyList(),
        updated: LocalDate = created
    ) = NoteEntry(name, tags, at(created), at(updated))

    private fun todo(
        id: String,
        created: LocalDate,
        done: Boolean
    ) = TodoItem(id, id, done, at(created))

    @Test
    fun `笔记按创建日归档到当天`() {
        val day = LocalDate.of(2026, 9, 10)
        val status = NotesCalendar.dayStatus(listOf(note("n", day)), emptyList(), zone)
        assertEquals(setOf(day), status.keys)
        assertTrue(status.getValue(day).hasNote)
    }

    @Test
    fun `改 updatedAt 不搬家 仍算创建日`() {
        val created = LocalDate.of(2026, 9, 10)
        val updated = LocalDate.of(2026, 9, 25)
        val status = NotesCalendar.dayStatus(listOf(note("n", created, updated = updated)), emptyList(), zone)
        assertEquals(setOf(created), status.keys)
        // 编辑日（9/25）上没有多出任何格子
        assertFalse(status.containsKey(updated))
    }

    @Test
    fun `只有带标签的笔记才点亮标签标记`() {
        val day = LocalDate.of(2026, 9, 10)
        val status = NotesCalendar.dayStatus(
            listOf(
                note("带标签", day, tags = listOf("工作", "A")),
                note("光杆", LocalDate.of(2026, 9, 11))
            ),
            emptyList(),
            zone
        )
        assertTrue(status.getValue(day).hasTag)
        assertFalse(status.getValue(LocalDate.of(2026, 9, 11)).hasTag)
    }

    @Test
    fun `createdAt 为 0 的历史数据不落任何日期`() {
        val legacy = NoteEntry("老", emptyList(), 0L, 0L)
        val legacyTodo = TodoItem("t", "老待办", false, 0L)
        assertTrue(NotesCalendar.dayStatus(listOf(legacy), listOf(legacyTodo), zone).isEmpty())
    }

    @Test
    fun `待办按创建日统计且同天合并`() {
        val day = LocalDate.of(2026, 9, 12)
        val status = NotesCalendar.dayStatus(
            emptyList(),
            listOf(
                todo("t1", day, done = true),
                todo("t2", day, done = false),
                todo("t3", LocalDate.of(2026, 9, 13), done = false)
            ),
            zone
        )
        val st = status.getValue(day)
        assertEquals(2, st.todoTotal)
        assertEquals(1, st.todoDone)
        assertEquals(NotesCalendar.TodoState.PARTIAL, st.todoState)
        assertEquals(NotesCalendar.TodoState.OPEN, status.getValue(LocalDate.of(2026, 9, 13)).todoState)
    }

    @Test
    fun `归属笔记的待办算在笔记的创建日 不算今天`() {
        val noteDay = LocalDate.of(2026, 1, 5)
        val today = LocalDate.of(2026, 9, 27)
        val n = note("旧笔记", noteDay)
        // 在以往的笔记里新加的待办：createdAt 是现在，但归属那篇笔记
        val t = TodoItem("t1", "在旧笔记里新加", false, at(today), n.name)
        val status = NotesCalendar.dayStatus(listOf(n), listOf(t), zone)
        // 落在笔记那天，和笔记同格
        val st = status.getValue(noteDay)
        assertTrue(st.hasNote)
        assertEquals(1, st.todoTotal)
        // 今天既没笔记也没待办 → 不再出现「有待办没笔记」的格
        assertFalse(status.containsKey(today))
    }

    @Test
    fun `无归属的全局待办仍按自身创建日`() {
        val today = LocalDate.of(2026, 9, 27)
        val t = TodoItem("t", "待办视图里新建的全局待办", false, at(today), "")
        val status = NotesCalendar.dayStatus(emptyList(), listOf(t), zone)
        val st = status.getValue(today)
        assertEquals(1, st.todoTotal)
        // 允许「只有待办没有笔记」的格（全局待办本来就不属于任何笔记）
        assertFalse(st.hasNote)
    }

    @Test
    fun `归属笔记已删 退回待办自身创建日`() {
        val today = LocalDate.of(2026, 9, 27)
        val t = TodoItem("t", "笔记已经没了", false, at(today), "已删除的笔记")
        val status = NotesCalendar.dayStatus(emptyList(), listOf(t), zone)
        assertEquals(1, status.getValue(today).todoTotal)
    }

    @Test
    fun `归属笔记 createdAt为0 与笔记一样不落格`() {
        val legacyNote = NoteEntry("老笔记", emptyList(), 0L, 0L)
        val t = TodoItem("t", "老待办", false, at(LocalDate.of(2026, 9, 27)), "老笔记")
        assertTrue(NotesCalendar.dayStatus(listOf(legacyNote), listOf(t), zone).isEmpty())
    }

    @Test
    fun `最早内容月 归属待办按笔记的月份算`() {
        val n = note("旧笔记", LocalDate.of(2026, 1, 5))
        val t = TodoItem("t", "今天在旧笔记里加的", false, at(LocalDate.of(2026, 9, 27)), n.name)
        // 归属待办不把自己的创建月（9 月）算进最早月，最晚也不会早过笔记本身
        assertEquals(YearMonth.of(2026, 1), NotesCalendar.earliestContentMonth(listOf(n), listOf(t), zone))
        // 只有全局待办时，最早月仍是全局待办自己的月份
        val g = TodoItem("g", "全局", false, at(LocalDate.of(2026, 3, 3)), "")
        assertEquals(YearMonth.of(2026, 3), NotesCalendar.earliestContentMonth(emptyList(), listOf(g), zone))
    }

    @Test
    fun `待办三态 无待办不画图标`() {
        val day = LocalDate.of(2026, 9, 12)
        // 无待办 → null（格子不显示待办图标）
        assertNull(NotesCalendar.DayStatus().todoState)
        // 全未完成 → 红
        assertEquals(NotesCalendar.TodoState.OPEN, NotesCalendar.DayStatus(todoTotal = 3).todoState)
        // 全完成 → 绿
        assertEquals(
            NotesCalendar.TodoState.DONE,
            NotesCalendar.DayStatus(todoTotal = 3, todoDone = 3).todoState
        )
        // 部分完成 → 黄
        assertEquals(
            NotesCalendar.TodoState.PARTIAL,
            NotesCalendar.DayStatus(todoTotal = 4, todoDone = 1).todoState
        )
        // 当天没有内容也判定为空
        assertTrue(NotesCalendar.DayStatus().isEmpty)
    }

    @Test
    fun `整月网格只补到本周日 不多补整行`() {
        for (year in listOf(2025, 2026, 2028)) { // 2028 闰年，2 月 29 天
            for (m in 1..12) {
                val first = LocalDate.of(year, m, 1)
                val shift = (first.dayOfWeek.value + 6) % 7
                val rows = (shift + first.lengthOfMonth() + 6) / 7
                val grid = NotesCalendar.monthGrid(year, m)
                // 行数 = ceil((周一起始偏移 + 当月天数) / 7)，格数 = 行数 × 7
                assertEquals("$year-$m 行数不对", rows * 7, grid.size)
                assertTrue(grid.size in 28..42) // 5 或 6 行，最多 42 格
                assertEquals(java.time.DayOfWeek.MONDAY, grid.first().dayOfWeek)
                // 只补齐到本周日：末格必是周日，不会多出一整行下月灰格
                assertEquals("$year-$m 末格不是周日", java.time.DayOfWeek.SUNDAY, grid.last().dayOfWeek)
                // 逐日连续
                assertTrue(grid.zipWithNext().all { (a, b) -> a.plusDays(1) == b })
                val last = LocalDate.of(year, m, first.lengthOfMonth())
                assertTrue(grid.contains(first))
                assertTrue(grid.contains(last))
                // 溢出格只有上月尾/下月头
                assertTrue(grid.filter { !NotesCalendar.isInMonth(it, year, m) }.all {
                    it < first || it > last
                })
                // 当月最后一天正好是周日 → 一格灰格都不补
                if (last.dayOfWeek == java.time.DayOfWeek.SUNDAY) {
                    assertEquals("$year-$m 不该补位", shift + first.lengthOfMonth(), grid.size)
                }
            }
        }
    }

    @Test
    fun `2026年9月 5行35格 首格8月31日周一 末格10月4日周日`() {
        val grid = NotesCalendar.monthGrid(2026, 9)
        // 9/1 是周二 → 偏移 1、30 天 = 31 格 → 5 行 35 格，末尾补到 10/4（周日），
        // 不再像 42 格那样多出一整行 10/5~10/11 的灰格
        assertEquals(35, grid.size)
        assertEquals(LocalDate.of(2026, 8, 31), grid.first())
        assertEquals(LocalDate.of(2026, 10, 4), grid.last())
    }

    @Test
    fun `2026年9月首格是 8 月 31 日周一`() {
        // 2026-09-01 是周二 → 补 1 格上月
        val grid = NotesCalendar.monthGrid(2026, 9)
        assertEquals(LocalDate.of(2026, 8, 31), grid.first())
        assertTrue(NotesCalendar.isInMonth(grid[1], 2026, 9))
        assertFalse(NotesCalendar.isInMonth(grid.first(), 2026, 9))
        assertEquals(LocalDate.of(2026, 9, 1), grid[1])
    }

    @Test
    fun `isInMonth 只认本年本月`() {
        val sep = LocalDate.of(2026, 9, 30)
        assertTrue(NotesCalendar.isInMonth(sep, 2026, 9))
        assertFalse(NotesCalendar.isInMonth(sep, 2025, 9))
        assertFalse(NotesCalendar.isInMonth(sep, 2026, 10))
    }

    @Test
    fun `空日期状态 什么都没记`() {
        val empty = NotesCalendar.DayStatus()
        assertTrue(empty.isEmpty)
        assertNull(empty.todoState)
        val withNote = NotesCalendar.dayStatus(listOf(note("n", LocalDate.of(2026, 1, 1))), emptyList(), zone)
        assertFalse(withNote.getValue(LocalDate.of(2026, 1, 1)).isEmpty)
    }

    // ---------- 翻月边界 ----------

    @Test
    fun `最早有内容月份 笔记与待办都算 createdAt为0忽略`() {
        val notes = listOf(note("笔记在3月", LocalDate.of(2026, 3, 5)))
        val todos = listOf(
            todo("t1", LocalDate.of(2026, 1, 20), done = false),
            TodoItem("legacy", "老待办", false, 0L)
        )
        assertEquals(YearMonth.of(2026, 1), NotesCalendar.earliestContentMonth(notes, todos, zone))
        // 只有带标签/未完成的记录也一样算
        assertEquals(
            YearMonth.of(2026, 3),
            NotesCalendar.earliestContentMonth(notes, emptyList(), zone)
        )
        assertEquals(
            YearMonth.of(2026, 1),
            NotesCalendar.earliestContentMonth(emptyList(), todos, zone)
        )
    }

    @Test
    fun `一条内容都没有时最早月为 null 由调用方拿当前月当底`() {
        assertNull(NotesCalendar.earliestContentMonth(emptyList(), emptyList(), zone))
        assertNull(
            NotesCalendar.earliestContentMonth(
                listOf(NoteEntry("老", emptyList(), 0L, 0L)), emptyList(), zone
            )
        )
    }

    @Test
    fun `翻月判定 范围内放行 出界拦截`() {
        val today = YearMonth.of(2026, 9)
        val earliest = YearMonth.of(2026, 6)
        // 范围内（含空月）照常翻
        assertEquals(
            NotesCalendar.ShiftVerdict.OK,
            NotesCalendar.shiftVerdict(YearMonth.of(2026, 9), -1, today, earliest)
        )
        assertEquals(
            NotesCalendar.ShiftVerdict.OK,
            NotesCalendar.shiftVerdict(YearMonth.of(2026, 7), -1, today, earliest)
        )
        // 正好停在最早内容月也允许，再往前一步就拦
        assertEquals(
            NotesCalendar.ShiftVerdict.OK,
            NotesCalendar.shiftVerdict(YearMonth.of(2026, 6), 0, today, earliest)
        )
        assertEquals(
            NotesCalendar.ShiftVerdict.BLOCK_NO_OLDER,
            NotesCalendar.shiftVerdict(YearMonth.of(2026, 6), -1, today, earliest)
        )
        // 上滑：落点是本月则允许，越过本月才拦（未来）
        assertEquals(
            NotesCalendar.ShiftVerdict.OK,
            NotesCalendar.shiftVerdict(YearMonth.of(2026, 8), 1, today, earliest)
        )
        // 已在本月再上滑才拦
        assertEquals(
            NotesCalendar.ShiftVerdict.BLOCK_FUTURE,
            NotesCalendar.shiftVerdict(YearMonth.of(2026, 9), 1, today, earliest)
        )
    }

    @Test
    fun `全库无内容时 当前月就是底 两个方向都被拦`() {
        val today = YearMonth.of(2026, 9)
        val fallback = today // 调用方：contentEarliest ?: month
        assertEquals(
            NotesCalendar.ShiftVerdict.BLOCK_NO_OLDER,
            NotesCalendar.shiftVerdict(today, -1, today, fallback)
        )
        assertEquals(
            NotesCalendar.ShiftVerdict.BLOCK_FUTURE,
            NotesCalendar.shiftVerdict(today, 1, today, fallback)
        )
        // 停在原地永远允许
        assertEquals(
            NotesCalendar.ShiftVerdict.OK,
            NotesCalendar.shiftVerdict(today, 0, today, fallback)
        )
    }
}

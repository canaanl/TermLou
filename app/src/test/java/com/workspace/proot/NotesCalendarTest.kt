package com.workspace.proot

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日历视图纯逻辑锁定：按创建日归档、待办三态、周一起头的 42 格整月网格。
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
    fun `整月网格 42 格且周一起头`() {
        for (m in 1..12) {
            val grid = NotesCalendar.monthGrid(2026, m)
            assertEquals(42, grid.size)
            assertEquals(java.time.DayOfWeek.MONDAY, grid.first().dayOfWeek)
            // 42 格逐日连续
            assertTrue(grid.zipWithNext().all { (a, b) -> a.plusDays(1) == b })
            // 本月每一天都在格子里
            val first = LocalDate.of(2026, m, 1)
            val last = LocalDate.of(2026, m, first.lengthOfMonth())
            assertTrue(grid.contains(first))
            assertTrue(grid.contains(last))
            // 溢出格只有上月尾/下月头
            assertTrue(grid.filter { !NotesCalendar.isInMonth(it, 2026, m) }.all {
                it < first || it > last
            })
        }
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
}

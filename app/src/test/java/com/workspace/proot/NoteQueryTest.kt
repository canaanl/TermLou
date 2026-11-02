package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 5.10.3 搜索字段语法：tag:标签 与普通词混用，标签过滤按 AND。 */
class NoteQueryTest {

    @Test
    fun `empty query has no terms and no tags`() {
        assertTrue(NoteQuery.parse("").isEmpty)
        assertTrue(NoteQuery.parse("   ").isEmpty)
    }

    @Test
    fun `plain text keeps the existing term splitting`() {
        val q = NoteQuery.parse("  Foo   BAR foo ")
        assertEquals(listOf("foo", "bar"), q.textTerms)
        assertTrue(q.tagFilters.isEmpty())
    }

    @Test
    fun `tag field is extracted with half or full width colon`() {
        assertEquals(listOf("工作"), NoteQuery.parse("tag:工作").tagFilters)
        assertEquals(listOf("工作"), NoteQuery.parse("tag：工作").tagFilters)
        assertEquals(listOf("工作"), NoteQuery.parse("TAG:工作").tagFilters)
        assertTrue(NoteQuery.parse("tag:工作").textTerms.isEmpty())
    }

    @Test
    fun `tag value tolerates hash prefix and quotes`() {
        assertEquals(listOf("工作"), NoteQuery.parse("tag:#工作").tagFilters)
        assertEquals(listOf("工作"), NoteQuery.parse("tag:\"工作\"").tagFilters)
    }

    @Test
    fun `tag and text mix without polluting text terms`() {
        val q = NoteQuery.parse("tag:工作 苹果 tag:重要")
        assertEquals(listOf("工作", "重要"), q.tagFilters)
        assertEquals(listOf("苹果"), q.textTerms)
    }

    @Test
    fun `duplicate tags are collapsed`() {
        val q = NoteQuery.parse("tag:工作 tag:工作 TAG:工作")
        assertEquals(listOf("工作"), q.tagFilters)
    }

    @Test
    fun `matches tags requires every filter and ignores case`() {
        val q = NoteQuery.parse("tag:Work tag:重要")
        assertTrue(q.matchesTags(listOf("work", "重要", "其他")))
        assertFalse(q.matchesTags(listOf("work")))
        assertTrue(NoteQuery.parse("").matchesTags(emptyList()))
    }

    @Test
    fun `compose puts tag fields before free text`() {
        assertEquals("tag:工作 tag:重要 苹果", NoteQuery.compose(listOf("工作", "重要"), " 苹果 "))
        assertEquals("tag:工作", NoteQuery.compose(listOf("#工作"), ""))
        assertEquals("苹果", NoteQuery.compose(emptyList(), "苹果"))
    }
}

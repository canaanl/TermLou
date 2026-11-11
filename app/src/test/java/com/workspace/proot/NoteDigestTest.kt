package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 搜索聚合展示的数据层回归（5.12.5）。
 *
 * 规则来自用户定义：
 *  - 只有**正文与待办**命中算内容匹配，笔记名/标签不算；
 *  - 待办命中输出完整一条；
 *  - 正文命中输出片段；
 *  - 纯标签查询（无文字词）聚合按钮必须灰色。
 */
@Suppress("MagicNumber")
class NoteDigestTest {

    private fun src(note: String, body: String = "", todos: List<String> = emptyList()) =
        NoteDigest.Source(note = note, body = body, todos = todos)

    @Test
    fun `todo hit emits the full todo text`() {
        val long = "买苹果和香蕉还有牛奶面包鸡蛋以及一些蔬菜水果"
        val items = NoteDigest.build(listOf(src("n1", todos = listOf(long))), listOf("苹果"))
        assertEquals(1, items.size)
        assertEquals(NoteDigest.Kind.TODO, items[0].kind)
        assertEquals("待办必须是完整一条，不能截断", long, items[0].text)
        assertEquals("n1", items[0].note)
    }

    @Test
    fun `body hit emits a snippet around the match`() {
        val body = "前面一段无关内容".repeat(10) + "这里提到苹果了" + "后面一段无关内容".repeat(10)
        val items = NoteDigest.build(listOf(src("n1", body = body)), listOf("苹果"))
        assertEquals(1, items.size)
        assertEquals(NoteDigest.Kind.BODY, items[0].kind)
        assertTrue("片段必须包含命中词：${items[0].text}", items[0].text.contains("苹果"))
        assertTrue("片段必须比整篇短：${items[0].text.length} vs ${body.length}", items[0].text.length < body.length)
    }

    @Test
    fun `match at the very start does not go out of bounds`() {
        val body = "苹果在最开头" + "尾巴".repeat(50)
        val items = NoteDigest.build(listOf(src("n1", body = body)), listOf("苹果"))
        assertEquals(1, items.size)
        assertTrue(items[0].text.startsWith("苹果"))
    }

    @Test
    fun `match at the very end does not go out of bounds`() {
        val body = "开头".repeat(50) + "最后提到苹果"
        val items = NoteDigest.build(listOf(src("n1", body = body)), listOf("苹果"))
        assertEquals(1, items.size)
        assertTrue("结尾命中必须保留命中词：${items[0].text}", items[0].text.contains("苹果"))
    }

    @Test
    fun `tag-only query produces nothing`() {
        val sources = listOf(src("n1", body = "苹果", todos = listOf("买苹果")))
        // 纯标签查询没有文字词，聚合必须为空。
        assertTrue(NoteDigest.build(sources, emptyList()).isEmpty())
        assertFalse(NoteDigest.hasContentMatch(sources, emptyList()))
    }

    @Test
    fun `note name hit alone is not a content match`() {
        // 笔记名叫"苹果笔记"，但正文和待办里都没有"苹果"。
        val sources = listOf(src("苹果笔记", body = "完全无关的内容", todos = listOf("无关待办")))
        assertTrue("笔记名不算内容匹配", NoteDigest.build(sources, listOf("苹果")).isEmpty())
        assertFalse(NoteDigest.hasContentMatch(sources, listOf("苹果")))
    }

    @Test
    fun `pinyin match still yields a body item with fallback head`() {
        PinyinDict.install(mapOf('苹' to listOf("ping"), '果' to listOf("guo")))
        try {
            val body = "今天吃了苹果很开心"
            val items = NoteDigest.build(listOf(src("n1", body = body)), listOf("pingguo"))
            assertEquals("拼音命中也要进聚合页", 1, items.size)
            assertEquals(NoteDigest.Kind.BODY, items[0].kind)
            assertTrue("无字面命中时退化为正文开头：${items[0].text}", body.contains(items[0].text))
        } finally {
            PinyinDict.uninstall()
        }
    }

    @Test
    fun `one note with both body and todo hits emits both`() {
        val sources = listOf(src("n1", body = "正文提到苹果", todos = listOf("买苹果")))
        val items = NoteDigest.build(sources, listOf("苹果"))
        assertEquals(2, items.size)
        assertTrue(items.any { it.kind == NoteDigest.Kind.TODO })
        assertTrue(items.any { it.kind == NoteDigest.Kind.BODY })
    }

    @Test
    fun `multi term requires every term in content`() {
        val onlyApple = listOf(src("n1", body = "苹果"))
        val both = listOf(src("n2", body = "苹果和香蕉"))
        assertTrue("缺一个词不能进聚合", NoteDigest.build(onlyApple, listOf("苹果", "香蕉")).isEmpty())
        assertFalse(NoteDigest.hasContentMatch(onlyApple, listOf("苹果", "香蕉")))
        assertEquals(1, NoteDigest.build(both, listOf("苹果", "香蕉")).size)
    }

    @Test
    fun `empty body with matching todo still aggregates`() {
        val sources = listOf(src("n1", body = "", todos = listOf("苹果待办")))
        val items = NoteDigest.build(sources, listOf("苹果"))
        assertEquals(1, items.size)
        assertEquals(NoteDigest.Kind.TODO, items[0].kind)
    }

    @Test
    fun `case insensitive body match`() {
        val items = NoteDigest.build(listOf(src("n1", body = "Hello APPLE world")), listOf("apple"))
        assertEquals(1, items.size)
        assertEquals(NoteDigest.Kind.BODY, items[0].kind)
    }

    @Test
    fun `snippets are capped per note`() {
        val body = (1..20).joinToString("填充内容") { "苹果$it" }
        val items = NoteDigest.build(listOf(src("n1", body = body)), listOf("苹果"))
        assertTrue("片段数必须封顶：${items.size}", items.size <= 3)
        assertTrue(items.isNotEmpty())
    }

    @Test
    fun `hasContentMatch is true for todo-only match`() {
        val sources = listOf(src("n1", body = "无关", todos = listOf("苹果")))
        assertTrue(NoteDigest.hasContentMatch(sources, listOf("苹果")))
    }

    @Test
    fun `no sources means no match`() {
        assertFalse(NoteDigest.hasContentMatch(emptyList(), listOf("苹果")))
        assertTrue(NoteDigest.build(emptyList(), listOf("苹果")).isEmpty())
    }
}

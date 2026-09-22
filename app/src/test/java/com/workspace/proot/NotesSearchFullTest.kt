package com.workspace.proot

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * searchNotesFull 行为锁定：
 * 1) 对拍——新管线（召回→交集→批量取正文→精排）与「对全部笔记逐条 noteScore」
 *    在覆盖 字面/标签/待办/同音错字/拼音/多音字副读音/首字母/短词/长词容错/多词 AND/
 *    纯标点/混合词/无命中的查询集上逐条等价，连排序都一致；
 * 2) 关键召回通道各有端到端点名断言（失败时能一眼定位是哪条通道断了）；
 * 3) 老结构库（v1）升级走删库重建通道，重建后新搜索照常；
 * 4) 外部改过的 txt 下次进页面重灌，能搜到新内容、搜不到已删内容。
 */
class NotesSearchFullTest {

    private lateinit var dir: File
    private lateinit var store: NotesStore

    @Before
    fun setUp() {
        PinyinDict.install(
            mapOf(
                '苹' to listOf("ping"), '果' to listOf("guo"), '菓' to listOf("guo"),
                '派' to listOf("pai"),
                '香' to listOf("xiang"), '蕉' to listOf("jiao"),
                '相' to listOf("xiang"), '交' to listOf("jiao"),
                '超' to listOf("chao"), '市' to listOf("shi"), '屎' to listOf("shi"),
                '牛' to listOf("niu"), '奶' to listOf("nai"), '和' to listOf("he"),
                '面' to listOf("mian"), '包' to listOf("bao"),
                '重' to listOf("zhong", "chong"), '量' to listOf("liang"),
                '冲' to listOf("chong"), '凉' to listOf("liang"),
                '工' to listOf("gong"), '作' to listOf("zuo"),
                '出' to listOf("chu"), '发' to listOf("fa"),
                '旅' to listOf("lv"), '行' to listOf("xing"),
                '计' to listOf("ji"), '划' to listOf("hua"),
                '明' to listOf("ming"), '天' to listOf("tian"), '去' to listOf("qu"),
                '买' to listOf("mai"), '水' to listOf("shui"), '费' to listOf("fei"),
                '记' to listOf("ji"), '录' to listOf("lu"),
                '一' to listOf("yi"), '些' to listOf("xie"), '了' to listOf("le"),
                '新' to listOf("xin"),
                '设' to listOf("she"), '置' to listOf("zhi"),
                '指' to listOf("zhi"), '南' to listOf("nan"),
                '电' to listOf("dian"),
                '的' to listOf("de"), '书' to listOf("shu"), '本' to listOf("ben")
            )
        )
        dir = Files.createTempDirectory("notes-full").toFile()
        store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        store.reload()
        seed()
    }

    @After
    fun tearDown() {
        runCatching { store.close() }
        dir.deleteRecursively()
    }

    private fun seed() {
        store.createNote("苹果派教程")
        store.saveNote("苹果派教程", "苹果派的做法：先买苹果，再切块烘烤。")
        store.attachTags("苹果派教程", listOf("烘焙"))

        store.createNote("购物清单")
        store.saveNote("购物清单", "明天去超市买牛奶和面包")
        store.addTodo("交水电费", "购物清单")

        store.createNote("旅行计划")
        store.saveNote("旅行计划", "相交的路口往左走")

        store.createNote("工作汇报")
        store.saveNote("工作汇报", "重量重新出发，冲凉也要记录。")
        store.attachTags("工作汇报", listOf("重要"))

        store.createNote("日记一")
        store.saveNote("日记一", "买了一些苹果")

        store.createNote("快速记录")
        store.saveNote("快速记录", "去超市牛奶就好")

        store.createNote("english note")
        store.saveNote("english note", "hello world foo bar")

        store.createNote("wifi指南")
        store.saveNote("wifi指南", "wifi设置指南在这里")

        store.createNote("纯标点")
        store.saveNote("纯标点", "只有标点。。。")
    }

    /** 旧管线基准：UI 改造前的逐条全量打分（读 .txt），作为新管线的对拍真值。 */
    private fun brute(raw: String): List<String> {
        val terms = NoteMatcher.terms(raw)
        if (terms.isEmpty()) return store.listNotes().map { it.name }
        val todoTexts = store.todos().filter { it.note.isNotEmpty() }.groupBy({ it.note }, { it.text })
        return store.listNotes()
            .map { e ->
                e to NoteMatcher.noteScore(
                    e.name, store.readNote(e.name), terms,
                    e.tags.joinToString(" "), todoTexts[e.name].orEmpty().joinToString(" ")
                )
            }
            .filter { it.second >= 0 }
            .sortedBy { it.second }
            .map { it.first.name }
    }

    private fun full(raw: String): List<String> = store.searchNotesFull(raw).map { it.name }

    @Test
    fun `new pipeline matches brute force scoring on every query shape`() {
        val queries = listOf(
            "", "   ",
            "苹果", "苹果派", "苹果 超市", "苹果 派",
            "苹菓", "超屎",
            "香蕉", "记录", "重量",
            "pingguo", "xiangjiao", "chongxin", "zhongliang", "chongliang",
            "pg", "p",
            "超市", "牛奶和面", "明", "天", "屎",
            "去超市买牛奶", "去超市牛",
            "交水电", "烘焙", "重要",
            "hello", "foo bar", "wifi设置",
            "。。。", "，",
            "不存在xyz", "笔记本电脑"
        )
        for (q in queries) {
            assertEquals("query=[$q]", brute(q), full(q))
        }
    }

    @Test
    fun `body pinyin recalls notes whose title does not match`() {
        // 「日记一」标题无拼音关联，靠正文拼音列召回（写入时物化）
        assertTrue(full("pingguo").contains("日记一"))
        // 多音字副读音：正文「重量重新」，搜 chongxin 靠 body_py2 剖面召回
        assertEquals(listOf("工作汇报"), full("chongxin"))
    }

    @Test
    fun `homophone typo initials and todo are recalled end to end`() {
        // 「超屎」同音错字：超市命中的笔记都该回来（购物清单正文、快速记录正文）
        assertTrue(full("超屎").contains("购物清单"))
        assertTrue(full("超屎").contains("快速记录"))
        assertTrue(full("香蕉").contains("旅行计划"))
        assertTrue(full("pg").contains("苹果派教程"))
        assertEquals(listOf("购物清单"), full("交水电"))
        assertTrue(full("去超市牛").contains("快速记录"))
    }

    @Test
    fun `externally edited txt reingests on reload`() {
        val f = File(dir, "外部.${NotesStore.EXT}")
        f.writeText("原来的苹果")
        store.reload()
        assertTrue(full("苹果").contains("外部"))
        Thread.sleep(30)
        f.writeText("改成香蕉了")
        store.reload()
        // 新内容搜得到，旧内容随重灌消失（种子语料里的苹果笔记不受影响）
        assertFalse(full("苹果").contains("外部"))
        assertTrue(full("香蕉").contains("外部"))
    }

    @Test
    fun `old v1 schema db rebuilds from txt and search works after upgrade`() {
        // 造一个老结构库：无 body/file_mtime 列、无拼音列、无 bigram 表、user_version=0
        runCatching { store.close() }
        assertTrue(File(dir, NotesSql.DB_FILE).delete())
        val old = JdbcNotesDb(File(dir, NotesSql.DB_FILE).absolutePath)
        old.exec(
            "CREATE TABLE notes (name TEXT PRIMARY KEY, tags_json TEXT NOT NULL DEFAULT '[]', " +
                "created_at INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL DEFAULT 0)"
        )
        old.exec(
            "CREATE TABLE todos (id TEXT PRIMARY KEY, text TEXT NOT NULL, " +
                "done INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL DEFAULT 0, " +
                "note TEXT NOT NULL DEFAULT '')"
        )
        old.exec("CREATE VIRTUAL TABLE notes_fts USING fts5(name, body, tokenize='trigram')")
        old.execArgs(
            "INSERT INTO notes(name, tags_json, created_at, updated_at) VALUES(?, ?, ?, ?)",
            listOf("老库幽灵", "[]", 1L, 1L)
        )
        old.execArgs("INSERT INTO notes_fts(name, body) VALUES(?, ?)", listOf("老库幽灵", "苹果"))
        old.close()
        // 重开：版本不符 → 删库 → 按目录 txt 头部全量重建（老库幽灵无 txt 自然消失）
        store = NotesStore(dir) { JdbcNotesDb(it.absolutePath) }
        store.reload()
        assertTrue(store.listNotes().any { it.name == "苹果派教程" })
        assertTrue(full("超屎").contains("购物清单"))
        assertTrue(full("pingguo").contains("日记一"))
    }
}

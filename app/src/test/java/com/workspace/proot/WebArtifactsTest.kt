package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertFalse
import java.io.File
import org.junit.Test

/**
 * 产物目录规则锁定测试（5.9.0）：截图只留最近 50 张、文件名按序号可排序。
 * 纯文件逻辑，不依赖安卓，故可单测。
 */
class WebArtifactsTest {

    /** 本次跑动建出来的临时目录，测完清掉。 */
    private val temps = mutableListOf<File>()

    /**
     * 真的建一个临时目录来测递归删除 —— 这段逻辑删错东西的后果是丢文件，
     * 只断言字符串形状是守不住的（5.9.0–5.9.5 连续六版的教训）。
     */
    private fun webTempDir(tag: String): File =
        File(System.getProperty("java.io.tmpdir"), "webart--{System.nanoTime()}").apply {
            mkdirs()
            temps.add(this)
        }

    @After
    fun cleanupTemps() {
        temps.forEach { runCatching { it.deleteRecursively() } }
        temps.clear()
    }

    @Test
    fun `截图文件名按四位序号补零`() {
        assertEquals("shot-0001.png", WebArtifacts.shotFileName(1))
        assertEquals("shot-0042.png", WebArtifacts.shotFileName(42))
        assertEquals("shot-1234.png", WebArtifacts.shotFileName(1234))
    }

    // ---------- 5.9.30：整页截图一屏一个文件 ----------

    @Test
    fun `整页截图每屏的文件名带屏号`() {
        assertEquals("shot-0007-1.png", WebArtifacts.shotScreenFileName(7, 1))
        assertEquals("shot-0007-2.png", WebArtifacts.shotScreenFileName(7, 2))
        assertEquals("shot-0042-3.png", WebArtifacts.shotScreenFileName(42, 3))
    }

    @Test
    fun `屏号从1起不许是0或负`() {
        // 屏号 0 会读起来像"第 0 屏"，那不是人话
        assertEquals("shot-0007-1.png", WebArtifacts.shotScreenFileName(7, 0))
        assertEquals("shot-0007-1.png", WebArtifacts.shotScreenFileName(7, -5))
    }

    @Test
    fun `带屏号的文件名照样能解析出序号`() {
        // ⚠ 这是 5.9.30 修的坑：`shot-0007-2.png` 去掉前缀后缀剩 `0007-2`，
        // 整串 toIntOrNull() 直接 null → 序号永远算成 0 → **新截图覆盖旧截图**。
        assertEquals(7, WebArtifacts.highestShotSeq(listOf("shot-0007-1.png")))
        assertEquals(7, WebArtifacts.highestShotSeq(listOf("shot-0007-2.png", "shot-0007-3.png")))
        assertEquals(
            "混着普通截图与整页截图也要能取到最大值",
            42,
            WebArtifacts.highestShotSeq(listOf("shot-0042.png", "shot-0007-1.png"))
        )
    }

    @Test
    fun `整页截图的几张算同一次截图只占一个序号`() {
        val screens = (1..5).map { WebArtifacts.shotScreenFileName(7, it) }
        assertEquals(
            "5 屏的序号必须都是 7 —— 否则看图时分不清哪几张是一组",
            7,
            WebArtifacts.highestShotSeq(screens)
        )
    }

    @Test
    fun `未超上限时不删任何东西`() {
        val names = (1..50).map { WebArtifacts.shotFileName(it) }
        assertEquals(emptyList<String>(), WebArtifacts.oldestToDelete(names))
    }

    @Test
    fun `超上限只删最旧的`() {
        val names = (1..53).map { WebArtifacts.shotFileName(it) }
        assertEquals(
            listOf("shot-0001.png", "shot-0002.png", "shot-0003.png"),
            WebArtifacts.oldestToDelete(names)
        )
    }

    @Test
    fun `名字乱序也能正确找出最旧的`() {
        val names = listOf("shot-0010.png", "shot-0002.png", "shot-0033.png", "shot-0001.png")
        assertEquals(
            listOf("shot-0001.png", "shot-0002.png"),
            WebArtifacts.oldestToDelete(names, max = 2)
        )
    }

    @Test
    fun `上限为零时全删`() {
        val names = (1..5).map { WebArtifacts.shotFileName(it) }
        assertEquals(names.sorted(), WebArtifacts.oldestToDelete(names, max = 0))
    }

    @Test
    fun `负上限按零处理不会崩`() {
        val names = (1..3).map { WebArtifacts.shotFileName(it) }
        assertEquals(3, WebArtifacts.oldestToDelete(names, max = -1).size)
    }

    @Test
    fun `默认上限就是约定的 50 张`() {
        assertEquals(50, WebArtifacts.MAX_SHOTS)
        val names = (1..60).map { WebArtifacts.shotFileName(it) }
        assertEquals(10, WebArtifacts.oldestToDelete(names).size)
    }

    @Test
    fun `序号补零保证字典序等于时间序`() {
        val seq = listOf(9, 10, 2, 100, 1)
        val sorted = seq.map { WebArtifacts.shotFileName(it) }.sorted()
        assertEquals(listOf(1, 2, 9, 10, 100), sorted.map { it.removePrefix("shot-").removeSuffix(".png").toInt() })
    }
    @Test
    fun `已有截图的最大序号能被认出来`() {
        assertEquals(7, WebArtifacts.highestShotSeq(listOf("shot-0001.png", "shot-0007.png", "shot-0003.png")))
    }

    @Test
    fun `没有截图时最大序号是 0`() {
        assertEquals(0, WebArtifacts.highestShotSeq(emptyList()))
    }

    @Test
    fun `杂七杂八的文件名不干扰取最大序号`() {
        assertEquals(
            12,
            WebArtifacts.highestShotSeq(
                listOf("shot-0012.png", "note.txt", "shot-0005.png.bak", "shot-0009.png")
            )
        )
    }

    @Test
    fun `进程重启后序号从已有最大值续上不会覆盖旧图`() {
        // 真机上的坑：进程内计数器重启归零，新图会盖掉上次的 shot-0001
        val existing = (1..3).map { WebArtifacts.shotFileName(it) }
        assertEquals(3, WebArtifacts.highestShotSeq(existing))
        assertTrue(WebArtifacts.highestShotSeq(existing) + 1 > 3)
    }

    @Test
    fun `文件名字面量由前缀与后缀拼成`() {
        assertTrue(WebArtifacts.shotFileName(9).startsWith("shot-"))
        assertTrue(WebArtifacts.shotFileName(9).endsWith(".png"))
    }

    // ---------- 清除产物（5.9.6）：清空 web 目录，目录本身留着 ----------

    /** 摆一个"用过的" web 目录：env + 截图 + cookie + 嵌套目录 + 用户自己放的文件。 */
    private fun seedWebDir(root: File) {
        root.mkdirs()
        File(root, "web.env").writeText("PORT=39080\nTOKEN=abc\n")
        File(root, "cookies.txt").writeText("a=b\n")
        File(root, "cookies.json").writeText("{}")
        File(root, "notes.md").writeText("agent 自己放的文件")
        File(root, "shots").mkdirs()
        File(root, "shots/shot-0001.png").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "shots/shot-0002.png").writeBytes(byteArrayOf(4, 5, 6))
        File(root, "shots/nested").mkdirs()
        File(root, "shots/nested/deep.txt").writeText("deep")
    }

    @Test
    fun `清空之后 web 目录里什么都不剩`() {
        val root = webTempDir("clear-empty")
        seedWebDir(root)
        val removed = WebArtifacts.deleteContents(root)
        assertEquals("残留: " + root.walkTopDown().filter { it.isFile }.map { it.name }, 0,
            root.walkTopDown().count { it.isFile })
        assertTrue("端口令牌文件也该删（服务已停，没有活令牌要保）", !File(root, "web.env").exists())
        assertTrue("agent 自己放的文件按新规则也删", !File(root, "notes.md").exists())
        assertTrue("嵌套目录里的文件也要删", !File(root, "shots/nested/deep.txt").exists())
        assertEquals("删了 7 个文件（env + 2 个 cookie + notes + 2 张截图 + nested/deep）", 7, removed)
    }

    @Test
    fun `web 目录自己不会被删掉`() {
        val root = webTempDir("clear-keep-dir")
        seedWebDir(root)
        WebArtifacts.deleteContents(root)
        assertTrue("web 目录必须还在，否则文件页里会闪一下消失", root.isDirectory)
    }

    @Test
    fun `删空之后目录还能再被写进去`() {
        val root = webTempDir("clear-rewrite")
        seedWebDir(root)
        WebArtifacts.deleteContents(root)
        // 服务下次写截图是 mkdirs + 写文件，清空后这条路径必须还能走通
        File(root, "shots").mkdirs()
        File(root, "shots/shot-0001.png").writeBytes(byteArrayOf(9))
        assertTrue(File(root, "shots/shot-0001.png").length() == 1L)
    }

    @Test
    fun `计数只算文件不算目录`() {
        val root = webTempDir("clear-count")
        seedWebDir(root)
        val removed = WebArtifacts.deleteContents(root)
        // 7 个文件 + shots/ 与 shots/nested/ 两个目录 = 9 个条目；目录不计入
        assertEquals("7 个文件；目录若被计入会是 9", 7, removed)
    }

    @Test
    fun `目录不存在时不崩`() {
        val root = webTempDir("clear-missing")
        assertEquals(0, WebArtifacts.deleteContents(root))
    }

    // ---------- 危险边界：绝不能删掉目录自己 ----------

    @Test
    fun `空串与相对路径标记永不删`() {
        assertTrue(WebArtifacts.isDisposable("web.env"))
        assertTrue(WebArtifacts.isDisposable("shots"))
        assertTrue(WebArtifacts.isDisposable("shots/shot-0001.png"))
        assertTrue(WebArtifacts.isDisposable("a/b/c.txt"))
        assertFalse("空串不能删", WebArtifacts.isDisposable(""))
        assertFalse("只有斜杠等于空", WebArtifacts.isDisposable("/"))
        assertFalse("只有反斜杠等于空", WebArtifacts.isDisposable("\\\\"))
        assertFalse("`.` 删了就是删目录自己", WebArtifacts.isDisposable("."))
        assertFalse("`..` 同上", WebArtifacts.isDisposable(".."))
        assertFalse("包着斜杠的 . 也不行", WebArtifacts.isDisposable("./"))
        assertFalse("反斜杠写法也要挡", WebArtifacts.isDisposable("..\\"))
    }

    @Test
    fun `自检：isDisposable 挡得住 File 在路径不落在目录里时的输出`() {
        // File("../x").relativeTo(File("y")) 在这种组合下会给出 ".." 一类的结果
        val rel = File("shots", "..").absoluteFile.relativeTo(File("shots").absoluteFile).path
        assertFalse("relativeTo 给出 `..` 时必须被挡：$rel", WebArtifacts.isDisposable(rel))
    }
}

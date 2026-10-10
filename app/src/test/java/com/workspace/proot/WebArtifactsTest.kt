package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Assert.assertFalse
import java.io.File
import org.junit.Test

/**
 * 产物目录规则锁定测试（5.9.0 建立；5.9.37 删掉截图后只剩"清空产物"与"绝不删目录自己"）。
 *
 * ⚠ 5.9.37 删掉的部分：截图文件名补零、整页截图的屏号、保留最近 N 张的清理规则。
 * 那些函数随 `shot` 一起从 [WebArtifacts] 里删了；cookie 导出的部分随 5.9.38 一起删 ——
 * **不留孤儿测试**。
 *
 * 剩下的这两组是"删错东西的后果是丢文件"，只断言字符串形状守不住
 * （5.9.0–5.9.5 连续六版的教训），所以真的建临时目录来测。
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

    // ---------- 清除产物（5.9.6）：清空 web 目录，目录本身留着 ----------

    /** 摆一个"用过的" web 目录：env + 嵌套目录 + 用户自己放的文件。 */
    private fun seedWebDir(root: File) {
        root.mkdirs()
        File(root, "web.env").writeText("PORT=39080\nTOKEN=abc\n")
        File(root, "notes.md").writeText("agent 自己放的文件")
        File(root, "sub").mkdirs()
        File(root, "sub/one.bin").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "sub/two.bin").writeBytes(byteArrayOf(4, 5, 6))
        File(root, "sub/nested").mkdirs()
        File(root, "sub/nested/deep.txt").writeText("deep")
    }

    /** 摆出来的文件数（`sub/` 与 `sub/nested/` 两个目录不计入）。 */
    private val SEEDED_FILES = 5

    @Test
    fun `清空之后 web 目录里什么都不剩`() {
        val root = webTempDir("clear-empty")
        seedWebDir(root)
        val removed = WebArtifacts.deleteContents(root)
        assertEquals("残留: " + root.walkTopDown().filter { it.isFile }.map { it.name }, 0,
            root.walkTopDown().count { it.isFile })
        assertTrue("端口令牌文件也该删（服务已停，没有活令牌要保）", !File(root, "web.env").exists())
        assertTrue("agent 自己放的文件按新规则也删", !File(root, "notes.md").exists())
        assertTrue("嵌套目录里的文件也要删", !File(root, "sub/nested/deep.txt").exists())
        assertEquals("删了 $SEEDED_FILES 个文件（env + notes + 2 个 bin + nested/deep）",
            SEEDED_FILES, removed)
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
        // 服务下次写 web.env 是 mkdirs + 写文件，清空后这条路径必须还能走通
        File(root, "sub").mkdirs()
        File(root, "sub/new.txt").writeBytes(byteArrayOf(9))
        assertTrue(File(root, "sub/new.txt").length() == 1L)
    }

    @Test
    fun `计数只算文件不算目录`() {
        val root = webTempDir("clear-count")
        seedWebDir(root)
        val removed = WebArtifacts.deleteContents(root)
        // 5 个文件 + sub/ 与 sub/nested/ 两个目录 = 7 个条目；目录不计入
        assertEquals("$SEEDED_FILES 个文件；目录若被计入会是 7", SEEDED_FILES, removed)
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
        assertTrue(WebArtifacts.isDisposable("sub"))
        assertTrue(WebArtifacts.isDisposable("sub/a.bin"))
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
        val rel = File("sub", "..").absoluteFile.relativeTo(File("sub").absoluteFile).path
        assertFalse("relativeTo 给出 `..` 时必须被挡：$rel", WebArtifacts.isDisposable(rel))
    }
}

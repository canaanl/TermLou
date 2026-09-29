package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 产物目录规则锁定测试（5.9.0）：截图只留最近 50 张、文件名按序号可排序。
 * 纯文件逻辑，不依赖安卓，故可单测。
 */
class WebArtifactsTest {

    @Test
    fun `截图文件名按四位序号补零`() {
        assertEquals("shot-0001.png", WebArtifacts.shotFileName(1))
        assertEquals("shot-0042.png", WebArtifacts.shotFileName(42))
        assertEquals("shot-1234.png", WebArtifacts.shotFileName(1234))
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
}

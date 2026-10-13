package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 5.9.40：MD3 UI 规范的第一层锁 —— **数值本身**。
 *
 * 只验"规则没被人改回去"；规则有没有被真正用上，由
 * [UiDimsScanTest] 扫源码验。这一层单独存在是不够的
 * —— 这个项目已经栽过 5 次"锁是绿的但东西早没了"，故两层并行。
 */
class UiTokensTest {

    // ===== 字号：MD3 5 档 =====

    @Test
    fun `typography has exactly five md3 tiers`() {
        assertEquals(28f, UiTokens.TEXT_DISPLAY)
        assertEquals(18f, UiTokens.TEXT_TITLE)
        assertEquals(15f, UiTokens.TEXT_BODY)
        assertEquals(13f, UiTokens.TEXT_COMPACT)
        assertEquals(12f, UiTokens.TEXT_META)
    }

    @Test
    fun `type tiers are strictly descending`() {
        // 档位必须真的分得开；靠得太近的"档"等于没有档。
        val tiers = listOf(
            UiTokens.TEXT_DISPLAY, UiTokens.TEXT_TITLE, UiTokens.TEXT_BODY,
            UiTokens.TEXT_COMPACT, UiTokens.TEXT_META,
        )
        tiers.zipWithNext { a, b ->
            assertTrue("字号档必须递减：$a 然后 $b", a > b)
        }
    }

    // ===== 行高：此前全项目 0 次 =====

    @Test
    fun `line heights are set and follow md3 ratios`() {
        // MD3 typography 的 line-height 落在 1.3~1.4，不允许离谱。
        for ((name, v) in listOf(
            "LINE_TITLE" to UiTokens.LINE_TITLE,
            "LINE_BODY" to UiTokens.LINE_BODY,
            "LINE_COMPACT" to UiTokens.LINE_COMPACT,
            "LINE_META" to UiTokens.LINE_META,
        )) {
            assertTrue("$name=$v 不在 MD3 的 1.30~1.45 区间", v >= 1.30f && v <= 1.45f)
        }
    }

    // ===== 间距：必须是 4 的倍数 =====

    @Test
    fun `every spacing tier is a multiple of four`() {
        // 这是 MD3 的硬规则，也是这次统一的核心。
        for ((name, v) in listOf(
            "SPACE_0" to UiTokens.SPACE_0, "SPACE_1" to UiTokens.SPACE_1,
            "SPACE_2" to UiTokens.SPACE_2, "SPACE_3" to UiTokens.SPACE_3,
            "SPACE_4" to UiTokens.SPACE_4, "SPACE_5" to UiTokens.SPACE_5,
            "SPACE_6" to UiTokens.SPACE_6,
        )) {
            assertEquals("$name=$v 不是 4 的倍数", 0, v % 4)
        }
    }

    @Test
    fun `spacing tiers are strictly ascending`() {
        val s = listOf(
            UiTokens.SPACE_0, UiTokens.SPACE_1, UiTokens.SPACE_2,
            UiTokens.SPACE_3, UiTokens.SPACE_4, UiTokens.SPACE_5, UiTokens.SPACE_6,
        )
        s.zipWithNext { a, b -> assertTrue("间距档必须递增：$a 然后 $b", a < b) }
    }

    // ===== 圆角：MD3 shape 档 =====

    @Test
    fun `corner tiers follow md3 shape scale`() {
        assertEquals(4f, UiTokens.CORNER_XS)
        assertEquals(8f, UiTokens.CORNER_SM)
        assertEquals(12f, UiTokens.CORNER_MD)
        assertEquals(16f, UiTokens.CORNER_LG)
        assertEquals(28f, UiTokens.CORNER_XL)
    }

    @Test
    fun `the old 20dp corner is gone`() {
        // 5.9.40 用户确认不保留 20dp 大圆角；若有代码再写 20，说明规则被绕过了。
        assertNotEquals(UiTokens.CORNER_LG, 20f)
    }

    // ===== 触控目标 =====

    @Test
    fun `touch target meets md3 minimum`() {
        assertTrue("MD3 要求触控目标 ≥48dp", UiTokens.TOUCH_TARGET >= 48)
        assertEquals(48, UiTokens.LIST_ROW_HEIGHT)
        assertTrue(UiTokens.BUTTON_HEIGHT <= UiTokens.TOUCH_TARGET)
    }
}
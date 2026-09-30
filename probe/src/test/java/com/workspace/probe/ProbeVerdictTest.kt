package com.workspace.probe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ProbeVerdict] v2 的锁。
 *
 * 重点锁 v1 那两个**把好结果判成坏**的错误：
 *  - 视口：不能再拿硬编码的像素数去比（v1 拿 412 比，而页面没 meta 时 Chromium
 *    默认视口是 980，于是 980 这个正确值被判成失败）
 *  - 排版：不能再硬编码"应≈206"（v1 的 412dp 的一半），必须自参照
 */
class ProbeVerdictTest {

    private fun obs(
        innerW: Int = 412, innerH: Int = 892, fiftyW: Int = 198,
        innerTextLen: Int = 33, hasSecret: Boolean = false,
        imgComplete: String = "true", canvasPx: String = "255,0,0",
        afterSelect: String = "b", histBefore: Int = 1, histAfter: Int = 2, hash: String = "#done"
    ) = JSONObject().apply {
        put("innerW", innerW); put("innerH", innerH); put("fiftyW", fiftyW)
        put("innerTextLen", innerTextLen); put("innerTextHasSecret", hasSecret)
        put("imgComplete", imgComplete); put("canvasPx", canvasPx)
        put("title", "probe-ready"); put("afterClick", "已点击"); put("afterType", "probe-typed")
        put("afterSelect", afterSelect)
        put("histBefore", histBefore); put("histAfter", histAfter); put("hash", hash)
    }

    private fun r(obs: JSONObject?, ink: Int = 9000, loaded: Boolean = true) =
        RunResult("x", false, loaded, null, obs, ink, true, null)

    // ---------- 自参照的排版判定 ----------

    @Test
    fun `排版判定是自参照的 换个视口宽度也对`() {
        // 视口 412 → 内容宽 396 → 一半 198
        assertEquals(198, ProbeVerdict.expectedFifty(412))
        assertTrue("412 视口下 198 正确", ProbeVerdict.layoutOk(r(obs(innerW = 412, fiftyW = 198))))
        // 视口 980 → 内容宽 964 → 一半 482。v1 恰好就是这个数，被误判成失败
        assertEquals(482, ProbeVerdict.expectedFifty(980))
        assertTrue(
            "v1 误判的那组数在自参照下应判为正确",
            ProbeVerdict.layoutOk(r(obs(innerW = 980, fiftyW = 482)))
        )
    }

    @Test
    fun `排版确实不对时仍会判为不对`() {
        assertFalse(ProbeVerdict.layoutOk(r(obs(innerW = 412, fiftyW = 0))))
        assertFalse(ProbeVerdict.layoutOk(r(obs(innerW = 412, fiftyW = 300))))
        assertFalse("没量到宽度时不算对", ProbeVerdict.layoutOk(r(null)))
    }

    // ---------- 视口判定 ----------

    @Test
    fun `视口必须等于我给的 dp 宽度`() {
        assertTrue(ProbeVerdict.viewportOk(r(obs(innerW = 412)), 412))
        assertFalse("尺寸被忽略时报的就是别的值", ProbeVerdict.viewportOk(r(obs(innerW = 980)), 412))
    }

    // ---------- 双尺寸：v2 新增的判定性测试 ----------

    @Test
    fun `两个宽度分别对上 才算视口跟着视图走`() {
        val small = r(obs(innerW = 200, fiftyW = 92))
        val large = r(obs(innerW = 600, fiftyW = 292))
        assertTrue(ProbeVerdict.viewportFollowsView(small, large, 200, 600))
    }

    @Test
    fun `两次都报同一个数 说明视图尺寸被忽略`() {
        // 这正是 v1 看不出来的情形：412dp × 2.379 ≈ 980，而默认视口也正好是 980
        val a = r(obs(innerW = 980))
        val b = r(obs(innerW = 980))
        assertFalse(
            "自检：这两个数确实撞上了，所以必须靠双尺寸才能分辨",
            ProbeVerdict.viewportFollowsView(a, b, 200, 600)
        )
    }

    @Test
    fun `视口不跟随时结论是放弃`() {
        val rows = ProbeVerdict.rows(r(obs()), r(obs()), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = false, realOk = null)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.GIVE_UP)
    }

    // ---------- 结论 ----------

    @Test
    fun `十项全等价且能出图时是彻底去掉权限`() {
        val rows = ProbeVerdict.rows(r(obs()), r(obs()), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = true, realOk = true)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.DROP_PERMISSION)
    }

    @Test
    fun `排版不对就放弃`() {
        val rows = ProbeVerdict.rows(r(obs(fiftyW = 0)), r(obs(fiftyW = 0)), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = null, realOk = null)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.GIVE_UP)
    }

    @Test
    fun `innerText 看得见隐藏内容时降级`() {
        val rows = ProbeVerdict.rows(r(obs(hasSecret = true)), r(obs()), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = true, realOk = null)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.FALLBACK)
    }

    @Test
    fun `draw 出不来图时是降级不是放弃`() {
        val rows = ProbeVerdict.rows(r(obs(), ink = 0), r(obs(), ink = 9000), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = true, realOk = null)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.FALLBACK)
        assertTrue("要说清是截图那一条", (v as ProbeVerdict.Verdict.FALLBACK).text.contains("draw()"))
    }

    @Test
    fun `canvas 挂掉不算致命`() {
        val rows = ProbeVerdict.rows(r(obs(canvasPx = "ERR:x")), r(obs()), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = true, realOk = null)
        assertTrue(
            "canvas 要 GPU 路径，未挂窗口挂掉是预期内的，不该否掉整件事。实际: $v",
            v is ProbeVerdict.Verdict.DROP_PERMISSION
        )
    }

    @Test
    fun `真实站点没验到时结论里要说未验证而不是算通过`() {
        val rows = ProbeVerdict.rows(r(obs()), r(obs()), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = true, realOk = null)
        assertTrue(
            (v as ProbeVerdict.Verdict.DROP_PERMISSION).text.contains("未验证")
        )
    }

    @Test
    fun `select 选不上时会被如实标为不等价`() {
        val rows = ProbeVerdict.rows(r(obs(afterSelect = "a")), r(obs()), 412)
        val sel = rows.first { it.name.startsWith("10") }
        assertEquals(false, sel.same)
    }

    @Test
    fun `导航与历史栈不通时降级 因为 back 与 reload 会失灵`() {
        val stuck = obs(histBefore = 2, histAfter = 2, hash = "")
        val rows = ProbeVerdict.rows(r(stuck), r(stuck), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = true, realOk = null)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.FALLBACK)
        assertTrue(
            "要说清是 back / reload 的问题",
            (v as ProbeVerdict.Verdict.FALLBACK).text.contains("back")
        )
    }

    @Test
    fun `历史栈正常时不算问题`() {
        val rows = ProbeVerdict.rows(r(obs()), r(obs()), 412)
        val nav = rows.first { it.name.startsWith("11") }
        assertTrue("两组都通就该算等价", nav.same)
        assertTrue(nav.a)
    }

    @Test
    fun `页面压根没加载时放弃`() {
        val rows = ProbeVerdict.rows(r(null, loaded = false), r(obs()), 412)
        val v = ProbeVerdict.conclusion(rows, viewportFollows = null, realOk = null)
        assertTrue("实际: $v", v is ProbeVerdict.Verdict.GIVE_UP)
    }
}

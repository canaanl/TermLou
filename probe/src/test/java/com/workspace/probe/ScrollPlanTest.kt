package com.workspace.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤计划的锁。
 *
 * ## 这批测试为什么存在
 *
 * v8 头一次，队列写成「在回调里调下一步」，回调一层层往下传，
 * 其中一环写成了 `{ done }` —— 那是**函数引用**不是调用。Kotlin 把 lambda
 * 最后一个表达式强制转成 `Unit`，**编译器一声不吭地收下了**。
 *
 * 每一步都跑完了、PNG 也存了，队列却永远不往下走，状态文字冻在 `1/5`。
 * 而且这个探针做了 8 版全程没有超时，所以"卡死"是**唯一**能看到的现象。
 *
 * 这批测试**测不出 `{ done }`** —— 那是接线问题，接线不进单测。
 * 它能做的是：把步骤顺序变成数据，让"形状对不对"能被看见，
 * 并且给看门狗的上界一个数，好让"最坏要等多久"是已知的而不是天知道的。
 */
class ScrollPlanTest {

    private val hows = ScrollFix.How.entries

    @Test
    fun `每个做法都有计划`() {
        for (h in hows) {
            assertTrue("$h 没有计划", ScrollPlan.stepsFor(h).isNotEmpty())
        }
    }

    @Test
    fun `每个计划都以测量收尾`() {
        // 收尾那步不到，队列就永远停在原地 —— 它必须是最后一步
        for (h in hows) {
            val steps = ScrollPlan.stepsFor(h)
            assertEquals("$h 的最后一步不是 MEASURE", ScrollPlan.Step.MEASURE, steps.last())
        }
    }

    @Test
    fun `测量步恰好出现一次`() {
        for (h in hows) {
            val n = ScrollPlan.stepsFor(h).count { it == ScrollPlan.Step.MEASURE }
            assertEquals("$h 的测量步出现了 $n 次", 1, n)
        }
    }

    @Test
    fun `加载永远是第一步`() {
        // v11 例外：VDISPLAY 碰都不碰主 wv —— 它建自己的虚拟屏 WebView，
        // VSETUP 里自带加载。所以第一步是 VSETUP 不是 LOAD
        for (h in hows) {
            if (h == ScrollFix.How.VDISPLAY) {
                assertEquals(
                    ScrollPlan.Step.VSETUP,
                    ScrollPlan.stepsFor(h).first()
                )
                continue
            }
            assertEquals("$h 没有先加载页面", ScrollPlan.Step.LOAD, ScrollPlan.stepsFor(h).first())
        }
    }

    @Test
    fun `没有步骤重复`() {
        for (h in hows) {
            val steps = ScrollPlan.stepsFor(h)
            assertEquals("$h 有重复步骤：$steps", steps.size, steps.distinct().size)
        }
    }

    @Test
    fun `不滚到底就不该有滚和重绘那几步`() {
        // CSS_SHIFT / FULL_PAGE 的整个卖点就是"不滚"。计划里混进 SCROLL
        // 就说明它悄悄退化成了基线，横向就不可比了
        for (h in listOf(ScrollFix.How.CSS_SHIFT, ScrollFix.How.FULL_PAGE)) {
            val steps = ScrollPlan.stepsFor(h)
            assertFalse("$h 不该有 SCROLL", steps.contains(ScrollPlan.Step.SCROLL))
            assertFalse("$h 不该有 REPAINT", steps.contains(ScrollPlan.Step.REPAINT))
            assertFalse("$h 不该有 NUDGE", steps.contains(ScrollPlan.Step.NUDGE))
        }
    }

    @Test
    fun `全高视图要先问高度再撑开顺序不能反`() {
        // 顺序反了就是拿默认视口去撑，撑出来还是视口那么高，等于没做
        val steps = ScrollPlan.stepsFor(ScrollFix.How.FULL_PAGE)
        assertTrue(
            "READ_HEIGHT 必须在 RESIZE 之前，实际 $steps",
            steps.indexOf(ScrollPlan.Step.READ_HEIGHT) < steps.indexOf(ScrollPlan.Step.RESIZE)
        )
    }

    @Test
    fun `需要新帧的那几种都先滚到底`() {
        for (h in listOf(ScrollFix.How.REPAINT, ScrollFix.How.NUDGE, ScrollFix.How.JITTER, ScrollFix.How.WAIT20)) {
            val steps = ScrollPlan.stepsFor(h)
            assertTrue("$h 缺 SCROLL", steps.contains(ScrollPlan.Step.SCROLL))
        }
        for ((h, act) in listOf(
            ScrollFix.How.REPAINT to ScrollPlan.Step.REPAINT,
            ScrollFix.How.NUDGE to ScrollPlan.Step.NUDGE,
            ScrollFix.How.JITTER to ScrollPlan.Step.JITTERLOOP,
            ScrollFix.How.WAIT20 to ScrollPlan.Step.SOAK
        )) {
            val steps = ScrollPlan.stepsFor(h)
            assertTrue(
                "$h 的 SCROLL 排在动作之前，实际 $steps",
                steps.indexOf(ScrollPlan.Step.SCROLL) < steps.indexOf(act)
            )
        }
    }

    @Test
    fun `只有贴底版用另一个页面`() {
        for (h in hows) {
            assertEquals(
                "$h 的页面选错了",
                h == ScrollFix.How.CSS_SHIFT,
                ScrollPlan.usesShiftedPage(h)
            )
        }
    }

    @Test
    fun `每步都有正的预算`() {
        // 预算是 0 就等于没上表 —— 那一环的回调不来又会永久停住
        for (s in ScrollPlan.Step.entries) {
            assertTrue("${s.label} 预算是 ${s.budgetMs}", s.budgetMs > 0)
        }
    }

    @Test
    fun `看门狗的上界是可知的`() {
        // 「最坏要等多久」必须是个能算出来的数。算不出来就等于没有上界
        for (h in hows) {
            val worst = ScrollPlan.worstCaseMs(h)
            val sum = ScrollPlan.stepsFor(h).sumOf { it.budgetMs }
            assertTrue("$h 的上界算不出来", worst > 0)
            assertEquals(
                "$h 的上界应当正好是各步预算之和再加宽限",
                sum + ScrollPlan.STALL_SLACK_MS,
                worst
            )
        }
    }

    @Test
    fun `一整轮扫完的等待是有上限的`() {
        // 12 个做法 × 2 组。按每个做法的实际最坏值加总。
        // v11 虚拟屏一轮最坏 35 秒，加总 626 秒 —— 上界提到 12 分钟。
        // 再涨就说明有人在步骤里加了长预算，那时再看。
        val total = ScrollFix.Variant.entries.sumOf { ScrollPlan.worstCaseMs(it.how) } * 2
        assertTrue("最坏要等 ${total / 60000.0} 分钟，太久了", total <= 12 * 60_000L)
    }

    @Test
    fun `v11虚拟屏自成一路`() {        // VDISPLAY 和其他 11 个比的不是"做法"而是"路"：
        // 主 wv 碰都不碰，所以计划里不许出现 LOAD / SCROLL / MEASURE 之外的旧步骤混入
        val vd = ScrollPlan.stepsFor(ScrollFix.How.VDISPLAY)
        assertEquals(
            listOf(
                ScrollPlan.Step.VSETUP, ScrollPlan.Step.VSCROLL,
                ScrollPlan.Step.VSHOT, ScrollPlan.Step.MEASURE
            ),
            vd
        )
        // VSETUP 里自带建屏与加载 —— 第一位必须是它，不能是 LOAD
        assertEquals(ScrollPlan.Step.VSETUP, vd.first())
    }

    @Test
    fun `v10预光栅和visual回调的计划形状`() {
        // PRERASTER 只是个开关，步骤和基线一样 —— 差别在 setup 里，不在步骤里。
        // 开关要是漏到步骤里，说明有人把"状态"和"步骤"又混在一起了
        assertEquals(
            ScrollPlan.stepsFor(ScrollFix.How.SCROLL),
            ScrollPlan.stepsFor(ScrollFix.How.PRERASTER)
        )

        val visual = ScrollPlan.stepsFor(ScrollFix.How.VISUAL)
        assertTrue("VISUAL 滚完要等回调", visual.contains(ScrollPlan.Step.VISUAL))
        assertTrue(
            "回调必须在量之前，实际 $visual",
            visual.indexOf(ScrollPlan.Step.VISUAL) < visual.indexOf(ScrollPlan.Step.MEASURE)
        )
    }

    @Test
    fun `v9那4个测的都是让它觉得有帧可出`() {
        // 静止点 / 抖动中 / DOM 脏区 / 慢帧 —— 计划里必须长这样
        val p99 = ScrollPlan.stepsFor(ScrollFix.How.P99)
        assertTrue("P99 不该滚到底", !p99.contains(ScrollPlan.Step.SCROLL))
        assertTrue("P99 要走 SCROLL99", p99.contains(ScrollPlan.Step.SCROLL99))

        val dirty = ScrollPlan.stepsFor(ScrollFix.How.DIRTY)
        assertTrue("DIRTY 不该走 SCROLL（它自己那段 JS 里滚）", !dirty.contains(ScrollPlan.Step.SCROLL))
        assertTrue("DIRTY 要走 DIRTY", dirty.contains(ScrollPlan.Step.DIRTY))

        val jitter = ScrollPlan.stepsFor(ScrollFix.How.JITTER)
        assertTrue(
            "JITTER 先滚到底再起抖动循环，实际 $jitter",
            jitter.indexOf(ScrollPlan.Step.SCROLL) < jitter.indexOf(ScrollPlan.Step.JITTERLOOP)
        )

        val wait20 = ScrollPlan.stepsFor(ScrollFix.How.WAIT20)
        assertTrue("WAIT20 不滚完等什么", wait20.contains(ScrollPlan.Step.SCROLL))
        assertTrue("WAIT20 要走 SOAK", wait20.contains(ScrollPlan.Step.SOAK))
        assertTrue("SOAK 必须在量之前", wait20.indexOf(ScrollPlan.Step.SOAK) < wait20.indexOf(ScrollPlan.Step.MEASURE))
    }

    @Test
    fun `v12布局dirty和装饰dirty走不同的步骤`() {
        // DIRTY（改字色）和 LDIRTY（插元素改布局）测的是两件不同的事：
        // 前者装饰性，后者布局性。步骤要是混成同一个，这轮就白测了
        val dirty = ScrollPlan.stepsFor(ScrollFix.How.DIRTY)
        val ldirty = ScrollPlan.stepsFor(ScrollFix.How.LDIRTY)
        assertTrue("LDIRTY 要走 LDIRTY", ldirty.contains(ScrollPlan.Step.LDIRTY))
        assertFalse("LDIRTY 不该走 DIRTY", ldirty.contains(ScrollPlan.Step.DIRTY))
        assertFalse("DIRTY 不该走 LDIRTY", dirty.contains(ScrollPlan.Step.LDIRTY))
        assertTrue(
            "布局动作必须在量之前，实际 $ldirty",
            ldirty.indexOf(ScrollPlan.Step.LDIRTY) < ldirty.indexOf(ScrollPlan.Step.MEASURE)
        )
    }
}

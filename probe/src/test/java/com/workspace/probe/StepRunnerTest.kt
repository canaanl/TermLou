package com.workspace.probe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 队列的锁。**这是 v8 头一次那个 bug 的解药。**
 *
 * 那天的情况：回调一层层往下传，其中一环写成了 `{ done }` ——
 * 那是**函数引用**不是调用。Kotlin 把 lambda 最后一个表达式强制转成 `Unit`，
 * 于是**编译器一声不吭地收下了**。每一步都跑完了、PNG 也存了，
 * 队列永远不往下走，状态文字冻在 `1/5`。而那个探针做了 8 版全程没有超时，
 * 所以"卡住"是不可见的 —— 唯一的现象就是用户来问"怎么执行不完"。
 *
 * 下面每一条测试都是"这种事不能再发生一次"的意思。
 */
class StepRunnerTest {

    /** 假驱动：把延时回调攥在手里，由测试决定什么时候放。 */
    private class Clock {
        val pending = ArrayList<() -> Unit>()
        fun later(_ms: Long, block: () -> Unit) { pending.add(block) }
        /** 放掉最早的一个待触发回调。返回还有没有剩下的。 */
        fun fireOne(): Boolean {
            if (pending.isEmpty()) return false
            pending.removeAt(0).invoke()
            return true
        }
        fun fireAll() {
            var guard = 0
            while (pending.isNotEmpty() && guard++ < 500) fireOne()
        }
    }

    @Test
    fun `每步都回调时按顺序走完`() {
        val seen = ArrayList<ScrollPlan.Step>()
        val clock = Clock()
        var ended = 0
        val r = StepRunner(
            steps = ScrollPlan.stepsFor(ScrollFix.How.NUDGE),
            later = { ms, b -> clock.later(ms, b) },
            step = { s, done -> seen.add(s); done(true) }
        )
        r.run { ended++ }
        assertEquals(1, ended)
        assertEquals(ScrollPlan.stepsFor(ScrollFix.How.NUDGE), seen)
        assertEquals("没卡住就不该记一笔", "", r.stalls.toString())
    }

    @Test
    fun `收尾那步一定会被走到`() {
        // v8 头一次就是收尾那步的回调没被调用 —— 队列停在原地，永远不往下走
        val clock = Clock()
        val seen = ArrayList<ScrollPlan.Step>()
        var ended = 0
        StepRunner(
            steps = ScrollPlan.stepsFor(ScrollFix.How.FULL_PAGE),
            later = { ms, b -> clock.later(ms, b) },
            step = { s, done -> seen.add(s); done(true) }
        ).run { ended++ }
        assertTrue("没走到收尾那步：$seen", seen.contains(ScrollPlan.Step.MEASURE))
        assertEquals(1, ended)
    }

    @Test
    fun `回调永远不来时靠看门狗走完——这正是那天的情况`() {
        val clock = Clock()
        val seen = ArrayList<ScrollPlan.Step>()
        var ended = 0
        val r = StepRunner(
            steps = ScrollPlan.stepsFor(ScrollFix.How.SCROLL),
            later = { ms, b -> clock.later(ms, b) },
            step = { s, _ -> seen.add(s) }   // **一次都不回调**
        )
        r.run { ended++ }
        assertEquals("不放看门狗就不该结束 —— 那天就是这样冻住的", 0, ended)
        clock.fireAll()
        assertEquals(1, ended)
        assertEquals(ScrollPlan.stepsFor(ScrollFix.How.SCROLL), seen)
    }

    @Test
    fun `卡住的每一步都要记下来`() {
        val clock = Clock()
        var ended = 0
        val steps = ScrollPlan.stepsFor(ScrollFix.How.FULL_PAGE)
        val r = StepRunner(
            steps = steps,
            later = { ms, b -> clock.later(ms, b) },
            step = { _, _ -> }
        )
        r.run { ended++ }
        clock.fireAll()
        assertEquals(1, ended)
        for (s in steps) {
            assertTrue("漏记了「${s.label}」：\n${r.stalls}", r.stalls.contains(s.label))
        }
    }

    @Test
    fun `只有卡住的那几步才记`() {
        val clock = Clock()
        val steps = ScrollPlan.stepsFor(ScrollFix.How.FULL_PAGE)
        var ended = 0
        val r = StepRunner(
            steps = steps,
            later = { ms, b -> clock.later(ms, b) },
            // 只有问高度那一步不回，其余都回
            step = { s, done -> if (s != ScrollPlan.Step.READ_HEIGHT) done(true) }
        )
        r.run { ended++ }
        clock.fireAll()
        assertEquals(1, ended)
        assertTrue("卡住的那步要记：\n${r.stalls}", r.stalls.contains(ScrollPlan.Step.READ_HEIGHT.label))
        assertFalse(
            "没卡住的步不该被记：\n${r.stalls}",
            r.stalls.contains(ScrollPlan.Step.LOAD.label)
        )
    }

    @Test
    fun `已经做好的步骤不会被迟到的看门狗再走一遍`() {
        val clock = Clock()
        val seen = ArrayList<ScrollPlan.Step>()
        val steps = ScrollPlan.stepsFor(ScrollFix.How.REPAINT)
        var ended = 0
        StepRunner(
            steps = steps,
            later = { ms, b -> clock.later(ms, b) },
            step = { s, done -> seen.add(s); done(true) }
        ).run { ended++ }
        assertEquals(steps.size, seen.size)
        // 现在把所有看门狗都放掉 —— 正常情况下它们都该作废
        clock.fireAll()
        assertEquals("迟到的看门狗把队列又走了一遍", steps.size, seen.size)
        assertEquals(1, ended)
    }

    @Test
    fun `一步回调两次也只算一次`() {
        val clock = Clock()
        val seen = ArrayList<ScrollPlan.Step>()
        var ended = 0
        StepRunner(
            steps = ScrollPlan.stepsFor(ScrollFix.How.SCROLL),
            later = { ms, b -> clock.later(ms, b) },
            step = { s, done -> seen.add(s); done(true); done(true); done(false) }
        ).run { ended++ }
        assertEquals(ScrollPlan.stepsFor(ScrollFix.How.SCROLL), seen)
        assertEquals(1, ended)
    }

    @Test
    fun `每个做法都能在回调全丢的情况下走完`() {
        // 五个做法逐个过一遍"最坏情况"：任何一步都不回调，只靠看门狗推进
        for (h in ScrollFix.How.entries) {
            val clock = Clock()
            val steps = ScrollPlan.stepsFor(h)
            val seen = ArrayList<ScrollPlan.Step>()
            var ended = 0
            val r = StepRunner(
                steps = steps,
                later = { ms, b -> clock.later(ms, b) },
                step = { s, _ -> seen.add(s) }
            )
            r.run { ended++ }
            clock.fireAll()
            assertEquals("$h 走不完", 1, ended)
            assertEquals("$h 漏了步骤：$seen", steps, seen)
            assertTrue("$h 一条都没记下卡住", r.stalls.isNotEmpty())
        }
    }

    @Test
    fun `每个做法在每步都回调时也都能走完`() {
        for (h in ScrollFix.How.entries) {
            val clock = Clock()
            val steps = ScrollPlan.stepsFor(h)
            var ended = 0
            val r = StepRunner(
                steps = steps,
                later = { ms, b -> clock.later(ms, b) },
                step = { _, done -> done(true) }
            )
            r.run { ended++ }
            assertEquals("$h 走不完", 1, ended)
            assertEquals("$h 明明没卡住却记了", "", r.stalls.toString())
        }
    }

    @Test
    fun `空计划也要交差`() {
        var ended = 0
        StepRunner(steps = emptyList(), later = { _, _ -> }, step = { _, _ -> }).run { ended++ }
        assertEquals(1, ended)
    }
}

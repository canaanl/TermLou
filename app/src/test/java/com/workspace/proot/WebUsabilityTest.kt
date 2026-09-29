package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`usable` 语义的锁**（5.9.5）。
 *
 * 三件事被这轮修掉：
 *  1. `wait` 不回报 `usable` —— 于是 `open`（不带 wait 时必然 `usable:false`）
 *     之后无从判断，只能等 `wait`，而 `wait` 又不说。字段一致性从这里锁。
 *  2. `open`/`reload`/`back` 的 `usable` 写的是 `"usable" to ready` —— 探测结果
 *     压根没进这个字段，它就是 `ready` 的复印件，零信息。
 *  3. 探测失败（超时 / 投不进主线程 / 答案不完整）被当成"页面可用" —— 探测自己
 *     坏了却被报成能用，和前两版清掉的假成功同一个形状。
 *
 * 判定式在 `WebAutomationService` 里是 private，所以这里锁的是**它的输出契约**
 * 与纯逻辑等价物 `WebUsability`。
 */
class WebUsabilityTest {

    // ---------- 字段契约 ----------

    @Test
    fun `wait 也回报 usable`() {
        // wait 的响应字段由同一处生成：usable:true / usable:false+warning
        assertTrue(
            "wait 必须带 usable —— 否则 open(usable:false) 之后只能靠猜",
            WebUsability.fields(WebUsability.Usable, ready = true)
                .any { it.first == "usable" && it.second == true }
        )
    }

    @Test
    fun `usable 为 true 只在探到可用且 ready 时出现`() {
        assertEquals(
            listOf<Pair<String, Any?>>("usable" to true),
            WebUsability.fields(WebUsability.Usable, ready = true)
        )
        // 探到可用但还没 ready：不能说能用
        assertEquals(
            listOf<Pair<String, Any?>>("usable" to false, "warning" to WebUsability.STILL_LOADING),
            WebUsability.fields(WebUsability.Usable, ready = false)
        )
    }

    @Test
    fun `usable 不再是 ready 的复印件`() {
        // 5.9.4 的写法是 "usable" to ready —— 两者永远相等，字段名在骗人
        val notReady = WebUsability.fields(WebUsability.Usable, ready = false)
        assertTrue(
            "ready:false 而 usable:true 就是当年那个退化",
            notReady.first().second == false
        )
        // 三种探测结果在 ready=true 下必须给出可区分的输出
        val usable = WebUsability.fields(WebUsability.Usable, ready = true)
        val unusable = WebUsability.fields(WebUsability.Unusable("boom"), ready = true)
        val unknown = WebUsability.fields(WebUsability.Unknown("timeout"), ready = true)
        assertEquals(true, usable.single().second)
        assertEquals(false, unusable.first().second)
        assertEquals(false, unknown.first().second)
        assertTrue("不可用与没探到必须能分辨", unusable[1] != unknown[1])
    }

    // ---------- 三态探测的映射 ----------

    @Test
    fun `没探到时报 false 并说明是没探到`() {
        val fields = WebUsability.fields(WebUsability.Unknown("evaluate timeout"), ready = true)
        assertEquals("usable" to false, fields[0])
        val warning = fields[1].second.toString()
        assertTrue("warning 必须写明是 could not determine，实际：$warning", warning.contains("could not determine"))
        assertTrue("warning 要带上探针自己的原因", warning.contains("evaluate timeout"))
    }

    @Test
    fun `探到不能用时报 false 并原样给出原因`() {
        val fields = WebUsability.fields(WebUsability.Unusable("landed on an error page: net::-2"), ready = true)
        assertEquals("usable" to false, fields[0])
        assertEquals("landed on an error page: net::-2", fields[1].second)
    }

    @Test
    fun `自检：Unknown 不会混进 Unusable 那一支`() {
        // 两者 usable 都是 false，只有 warning 不同 —— 这正是"探测失败不许报成可用"的落点
        val u = WebUsability.fields(WebUsability.Unknown("cannot reach main thread"), ready = true)
        val b = WebUsability.fields(WebUsability.Unusable("cannot reach main thread"), ready = true)
        assertEquals(u[0], b[0])
        assertTrue("warning 必须能区分：${u[1]} vs ${b[1]}", u[1] != b[1])
    }

    @Test
    fun `三种状态全部覆盖`() {
        val all = listOf(
            WebUsability.Usable,
            WebUsability.Unusable("x"),
            WebUsability.Unknown("y")
        )
        for (s in all) {
            for (ready in listOf(true, false)) {
                val fields = WebUsability.fields(s, ready)
                assertEquals("每种状态都要有 usable 字段", "usable", fields[0].first)
                assertTrue(
                    "$s / ready=$ready 不该报 usable=true",
                    fields[0].second != true || (s == WebUsability.Usable && ready)
                )
            }
        }
    }
}

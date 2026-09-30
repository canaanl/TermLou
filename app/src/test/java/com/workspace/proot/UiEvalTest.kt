package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * **主线程死锁回归锁**（5.9.1）。
 *
 * 这是 5.9.0 最严重的一个 bug：实现在主线程的 block 里 `latch.await` 等
 * `evaluateJavascript` 的回调，而那个回调只能由主线程送达 —— 于是每次 eval 必然
 * 跑满 20 秒超时，还把 App 主线程冻住 20 秒。
 *
 * 这里用**单线程 executor 冒充主线程**，把安卓上的形状原样搬过来：
 * 回调由"同一线程"的另一条任务送达。所以"投递"和"等待"必须发生在不同线程，
 * 否则必然死锁 —— 这正是真实 bug 的成因，测试直接把它钉住。
 */
class UiEvalTest {

    /** 冒充主线程：单线程，回调只能靠这个线程的另一条任务送达。 */
    private class FakeMainThread : AutoCloseable {
        private val pool = Executors.newSingleThreadExecutor { r -> Thread(r, "fake-main") }

        /** 主线程上的"回调槽"：evaluateJavascript 往里放回调，Chromium 用另一条任务送达。 */
        var deliver: ((String) -> Unit)? = null

        val ui = UiEval.Ui { task -> submit(task) != null }

        /** 冒充 evaluateJavascript：回调由**本线程**的另一条任务送达。 */
        fun evaluate(callback: (String) -> Unit) {
            deliver = callback
            submit {
                val cb = deliver
                deliver = null
                cb?.invoke("RESULT")
            }
        }

        /** 主线程是否还能立刻处理别的任务（真机上就是 UI 是否还活着）。 */
        fun isResponsive(): Boolean {
            val probe = CountDownLatch(1)
            submit { probe.countDown() }
            return probe.await(2, TimeUnit.SECONDS)
        }

        /** 直接提交到底层线程池（绕过 Ui 接口，用于复现错误写法）。 */
        fun submit(task: () -> Unit): Future<*>? = runCatching { pool.submit(task) }.getOrNull()

        override fun close() {
            pool.shutdownNow()
        }
    }

    @Test
    fun `正确写法能拿到值`() {
        FakeMainThread().use { main ->
            val result = UiEval.postAndAwait(
                ui = main.ui,
                timeoutMs = 5_000,
                cancelled = { false },
                evaluate = { deliver -> main.evaluate(deliver) },
                decode = { it }
            )
            assertTrue("应拿到结果，实际 $result", result is UiEval.Result.Ok)
            assertEquals("RESULT", (result as UiEval.Result.Ok).value)
        }
    }

    @Test
    fun `等完之后主线程仍然空着`() {
        FakeMainThread().use { main ->
            val result = UiEval.postAndAwait(
                ui = main.ui,
                timeoutMs = 5_000,
                cancelled = { false },
                evaluate = { deliver -> main.evaluate(deliver) },
                decode = { it }
            )
            assertTrue(result is UiEval.Result.Ok)
            // 关键：等完之后主线程还能立刻处理别的任务（真机上就是 UI 不再卡死）
            assertTrue("主线程被占住了", main.isResponsive())
        }
    }

    @Test
    fun `在主线程里等待的实现必然死锁，这正是 5_9_0 的 bug`() {
        FakeMainThread().use { main ->
            val deadlock = CountDownLatch(1)
            var deliveredWhileBlocked = true      // 默认假设"送达了"，只有真被送达才保持 true
            main.submit {
                main.evaluate { deadlock.countDown() }
                // ← 错误写法：主线程 park，等一个只能由自己送达的回调
                deadlock.await(400, TimeUnit.MILLISECONDS)
                // 主线程停止等待的那一刻，回调有没有被送达过？
                deliveredWhileBlocked = deadlock.count == 0L
            }
            main.isResponsive()                   // 等那条错误任务跑完
            assertFalse("主线程等待期间回调根本送不到", deliveredWhileBlocked)
        }
    }

    @Test
    fun `客户端断开立刻返回 Cancelled 不再等满超时`() {
        FakeMainThread().use { main ->
            val started = System.nanoTime()
            val result = UiEval.postAndAwait(
                ui = main.ui,
                timeoutMs = 30_000,
                cancelled = { true },
                pollMs = 20,
                evaluate = { deliver -> main.deliver = deliver },   // 回调永远不来
                decode = { it }
            )
            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            assertTrue("应立刻返回，实际 ${result.javaClass.simpleName}", result is UiEval.Result.Cancelled)
            assertTrue("不该等满超时，实际 ${elapsedMs}ms", elapsedMs < 3_000)
        }
    }

    @Test
    fun `投递失败返回 NotPosted`() {
        val result = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { false },
            timeoutMs = 1_000,
            cancelled = { false },
            evaluate = { deliver -> deliver("x") },
            decode = { it }
        )
        assertTrue(result is UiEval.Result.NotPosted)
    }

    @Test
    fun `发起方抛错时不会永久挂住而且要能认出是抛错`() {
        val result = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { task -> task(); true },
            timeoutMs = 1_000,
            cancelled = { false },
            evaluate = { error("webview already destroyed") },
            decode = { it }
        )
        // 5.9.9：这一条从 `Ok(null)` 改成了 `Failed`。
        // 此前 `onFailure { latch.countDown() }` 不记异常，于是
        // 「求压根没发出去」和「页面返回 null」塌成同一种结果 ——
        // 那个混淆贵得很：`probeUsability` 的探针 JS 里混进了一个真实换行
        // （Kotlin 的 `'\n'`），整段语法错 → `evaluateJavascript` 回调 null，
        // 真机上 `usable` 对**每一个**页面都是"probe returned no value"，
        // 而外部看到的是"页面返回了 null"，谁也想不到是脚本压根没跑起来。
        assertTrue("必须是 Failed，实际 $result", result is UiEval.Result.Failed)
        assertEquals(
            "webview already destroyed",
            (result as UiEval.Result.Failed).error.message
        )
    }

    @Test
    fun `抛错与页面返回null必须能分开`() {
        val threw = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { task -> task(); true },
            timeoutMs = 1_000,
            cancelled = { false },
            evaluate = { error("boom") },
            decode = { it }
        )
        // ⚠ 必须喂**真的 JS null**（也就是字符串 "null"），不是 Kotlin 的 null。
        // `evaluateJavascript` 把页面的 null 编成 JSON 的 `null` 传回来，
        // 经 `decodeEvalResult` 之后才是 Kotlin 的 null。
        // 喂 Kotlin 的 null 会让本该测的那条分支根本没被走到。
        val returnedNull = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { task -> task(); true },
            timeoutMs = 1_000,
            cancelled = { false },
            evaluate = { deliver -> deliver("null") },
            decode = { WebProtocol.decodeEvalResult(it) }
        )
        assertTrue(threw is UiEval.Result.Failed)
        assertTrue("页面真的返回 null 时是 Ok，实际 $returnedNull", returnedNull is UiEval.Result.Ok)
        assertNull((returnedNull as UiEval.Result.Ok).value)
    }

    @Test
    fun `重复回调只认第一次`() {
        val result = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { task -> task(); true },
            timeoutMs = 1_000,
            cancelled = { false },
            evaluate = { deliver ->
                deliver("first")
                deliver("second")      // 迟到的第二次不能覆盖第一次
            },
            decode = { it }
        )
        assertTrue(result is UiEval.Result.Ok)
        assertEquals("first", (result as UiEval.Result.Ok).value)
    }

    @Test
    fun `页面返回 null 与超时可区分`() {
        val nullResult = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { task -> task(); true },
            timeoutMs = 500,
            cancelled = { false },
            evaluate = { deliver -> deliver("null") },        // WebView 回调的 JSON null
            decode = { WebProtocol.decodeEvalResult(it) }
        )
        assertTrue(nullResult is UiEval.Result.Ok)
        assertNull((nullResult as UiEval.Result.Ok).value)

        val notPosted = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { false },
            timeoutMs = 500,
            cancelled = { false },
            evaluate = { },
            decode = { it }
        )
        assertTrue("未投递不是超时", notPosted is UiEval.Result.NotPosted)
    }

    @Test
    fun `超时如实回报等待时长`() {
        val result = UiEval.postAndAwait<String>(
            ui = UiEval.Ui { true },
            timeoutMs = 250,
            cancelled = { false },
            pollMs = 20,
            evaluate = { },                                    // 回调永不到达
            decode = { it }
        )
        assertTrue(result is UiEval.Result.TimedOut)
        assertTrue("等待时长应接近 250ms", (result as UiEval.Result.TimedOut).waitedMs >= 200)
    }
}

package com.workspace.proot

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 「在主线程发起求值、在当前线程等回调解码」的模式（5.9.1，纯逻辑、可单测）。
 *
 * ## 为什么要单独抽出来
 *
 * `WebView.evaluateJavascript` 的回调是 Chromium post 回**主线程**的。
 * 此前实现在 `onMain { … }` 的 block 里直接 `latch.await(20s)`——那个 block 跑在主线程上，
 * 于是主线程自己 park 等一个只能由主线程送达的回调：
 *
 * - 每次 `eval` / `html` / `text` / `click` / `type` / `select` **必然跑满超时**才返回超时；
 * - 同时**把 App 主线程冻结满超时**（连点都没反应，ANR 门槛 5s 就到了）；
 * - worker 只有一条线程，于是下一条指令排在后面——外部看起来就是"一用就卡死/被杀"。
 *
 * [UiEvalTest] 用**单线程 executor 冒充主线程**把这条死锁复现并锁死：
 * 回调由同一线程的另一条任务送达，"投递与等待不在同一线程"才拿得到值。
 */
object UiEval {

    /** 主线程抽象：把任务投递过去，返回 false 表示投不进去（主线程忙或已销毁）。 */
    fun interface Ui {
        fun post(task: () -> Unit): Boolean
    }

    /** 结果分四种，**不混为一谈**：agent 才知道该重试还是该改代码。 */
    sealed class Result {
        /** 拿到结果；[value] 为 null 表示页面里算出来的就是 null。 */
        data class Ok(val value: String?) : Result()

        /** 超时：内核没在时限内回调。 */
        data class TimedOut(val waitedMs: Long) : Result()

        /** 客户端已断开：立刻放弃，不再占着队列。 */
        data object Cancelled : Result()

        /** 投递失败：主线程收不到这个任务（已销毁/忙）。 */
        data object NotPosted : Result()
    }

    /**
     * @param ui 主线程投递口
     * @param timeoutMs 等回调的上限
     * @param cancelled 客户端是否已断开（每轮轮询问一次，用于尽早收手）
     * @param pollMs 轮询粒度
     * @param evaluate 在主线程上发起求值，用 [deliver] 参数注册回调
     * @param decode 回调原始值 → 最终值
     */
    fun <T> postAndAwait(
        ui: Ui,
        timeoutMs: Long,
        cancelled: () -> Boolean,
        pollMs: Long = 100L,
        evaluate: (deliver: (T) -> Unit) -> Unit,
        decode: (T) -> String?
    ): Result {
        val latch = CountDownLatch(1)
        var value: String? = null
        var settled = false

        val posted = ui.post {
            runCatching {
                evaluate { raw ->
                    // 回调只可能在"投递成功之后"到达；重复投递只认第一次
                    if (!settled) {
                        settled = true
                        value = decode(raw)
                        latch.countDown()
                    }
                }
            }.onFailure { latch.countDown() }
        }
        if (!posted) return Result.NotPosted

        val startNanos = System.nanoTime()
        val deadline = startNanos + timeoutMs.coerceAtLeast(0L) * 1_000_000L
        while (true) {
            if (latch.await(pollMs, TimeUnit.MILLISECONDS)) return Result.Ok(value)
            if (cancelled()) return Result.Cancelled
            if (System.nanoTime() >= deadline) {
                return Result.TimedOut((System.nanoTime() - startNanos) / 1_000_000L)
            }
        }
    }
}

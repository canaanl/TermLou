package com.workspace.probe

/**
 * 走一个 [ScrollPlan]。**不碰任何 Android 的东西**，所以能在 JVM 上单测。
 *
 * ## 为什么要有它
 *
 * v8 头一次不是"某个 API 不好用"，是**队列停在了原地**：回调一层层往下传，
 * 其中一环写成了 `{ done }` —— 那是**函数引用**不是调用。
 * Kotlin 会把 lambda 最后一个表达式强制转成 `Unit`，
 * 于是**编译器一声不吭地收下了**。每一步都跑完了、PNG 也存了，
 * 队列永远不往下走，状态文字冻在 `1/5`。
 *
 * 那个探针做了 8 版全程一个超时都没有，所以"停住"是不可见的 ——
 * 唯一的现象就是用户来问"怎么执行不完"。
 *
 * ## 它保证什么
 *
 * 把"走"这件事从 Activity 里抽出来，就能对**两种最坏情况**写断言：
 *  - 驱动每步都回调 → 必须走到最后一步
 *  - 驱动**永远不**回调 → **也必须**走到最后一步（靠看门狗兜底推进），
 *    并且把卡住的步骤记进 [stalls]
 *
 * 第二条就是这个 bug 的解药。以前"停住"是不可见的，现在它是可测的。
 *
 * 另外保证一件事：`done` 被调用两次也只算一次 —— 迟到的看门狗
 * 不会把已经做完的步骤再走一遍。
 */
class StepRunner(
    val steps: List<ScrollPlan.Step>,
    /** 安排一个延时回调。生产环境是 `Handler.postDelayed`，测试里自己攥着。 */
    private val later: (Long, () -> Unit) -> Unit,
    /** 执行一步。成功或卡住时**各调一次** `done(ok)`。 */
    private val step: (ScrollPlan.Step, (Boolean) -> Unit) -> Unit
) {
    private var i = 0
    private var token = 0L
    private var ended = false

    /** 跑完一整轮时交出结果：`stalls` 非空表示有步骤没跑完。 */
    val stalls = StringBuilder()

    /** 走完。`then` 一定被调用一次 —— 哪怕每一步都卡住。 */
    fun run(then: () -> Unit) = advance(then)

    private fun advance(then: () -> Unit) {
        if (ended) return
        if (i >= steps.size) {
            ended = true
            then()
            return
        }
        val s = steps[i]
        i++
        val mine = ++token
        var called = false

        /**
         * 这一步有个结果了 —— 无论成功还是被看门狗判卡，都只算一次。
         * 迟到的看门狗再回调也进不来，所以**不会把做完的步骤再走一遍**。
         */
        val settle: (Boolean) -> Unit = { ok ->
            if (!called) {
                called = true
                if (ok) {
                    // 回调到了：换 token，作废这一次的看门狗
                    token++
                } else {
                    stalls.append("　· 第").append(i).append('/').append(steps.size)
                        .append(" 步「").append(s.label).append("」等了 ")
                        .append(s.budgetMs / 1000).append("s 回调没来，跳过\n")
                }
                advance(then)
            }
        }

        // 看门狗：到了预算还没人回调，就当这一步卡了，照样往下走
        later(s.budgetMs + ScrollPlan.STALL_SLACK_MS) {
            if (token == mine) settle(false)
        }
        step(s) { ok -> settle(ok) }
    }
}

package com.workspace.proot

/**
 * 落地页可用性的**三态**与字段映射（5.9.5），纯逻辑、可单测。
 *
 * ## 为什么必须三态
 *
 * 此前探测一失败（`evaluateJavascript` 超时 / 投不进主线程 / 返回值不完整）就返回
 * `null`，而 `null` 的含义是"页面可用" —— **探测自己坏了，却被报成页面能用**。
 * 这和 5.9.3、5.9.4 修掉的假成功是同一个形状，只是搬到了探测层。
 *
 * 宁可漏判不误判：探不到就说探不到，让调用方重试，绝不冒充"可用"。
 */
object WebUsability {

    sealed interface State

    /** 探到了，页面能用。 */
    object Usable : State

    /** 探到了，页面不能用：WebView 自己的错误页，或正文里的已知 `ERR_*` 码。 */
    class Unusable(val reason: String) : State

    /** **没探到** —— 不是"能用"，是不知道。 */
    class Unknown(val reason: String) : State

    /** 页面还没加载完时的统一措辞。 */
    const val STILL_LOADING = "page is still loading - not usable yet (retry with wait)"

    /**
     * 把探测结果摊成 `usable` / `warning` 两个响应字段。**所有 op 共用这一处**。
     *
     * `usable:true` 只在「探到可用 **且** [ready]」时出现。`ready:false` 一律
     * `usable:false` —— 还没渲染完就说能用，是把"没加载好"讲成"能用"。
     */
    fun fields(s: State, ready: Boolean): List<Pair<String, Any?>> = when {
        !ready -> listOf("usable" to false, "warning" to STILL_LOADING)
        s === Usable -> listOf("usable" to true)
        s is Unusable -> listOf("usable" to false, "warning" to s.reason)
        else -> listOf("usable" to false, "warning" to "could not determine usability: ${(s as Unknown).reason}")
    }
}

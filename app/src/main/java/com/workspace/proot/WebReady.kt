package com.workspace.proot

/**
 * 就绪状态的判定规则（5.9.4），纯逻辑、可单测。
 *
 * 单独拆出来是因为这里的判断错了不会编译失败、测试也不会红，只在真机上表现成
 * 「明明没加载好，却报 ready:true」——5.9.0 到 5.9.4 连续四个版本都是这样挂的。
 *
 * ⚠ 5.9.38 删掉了 `readyUnwaitedAfterNavigation`：它从写下来那天起**没有任何调用方**
 * （`open`/`reload`/`back` 是直接调 `waitForPage`，语义等价），却带着 4 条测试 ——
 * **锁着一个没人用的函数**。死代码不留（这次不删，下次就会有人以为它在生效）。
 */
object WebReady {

    /**
     * 「可能引起导航的操作」（`click`）之后、**且没有等待**时，该怎么报 ready。
     *
     * @param navStarted 本次操作前后导航代数是否变了（= WebView 真的开始导航了）
     * @param pageReady  WebView 报过"这一轮加载完成"
     * @param progress   当前加载进度，100 才是完成
     *
     * 导航已开始就一律 false：此刻页面正在加载，[pageReady] / [progress] 很可能还是
     * **上一页**留下的值，报 true 等于拿旧状态冒充新页面（5.9.4 真机复现：点完立刻
     * 报 `ready:true, usable:true`，而可用性探针是在旧页面上做的）。
     *
     * 导航没开始说明这次点击没引起导航，页面没动，沿用 WebView 报的状态。
     */
    fun readyUnwaited(navStarted: Boolean, pageReady: Boolean, progress: Int): Boolean =
        if (navStarted) false else pageReady && progress >= 100
}

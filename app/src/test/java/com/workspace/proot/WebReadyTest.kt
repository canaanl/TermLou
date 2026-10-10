package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 就绪判定的锁（5.9.4）。
 *
 * 5.9.4 真机复查：`open https://不存在的域名` 返回 `ready:true, usable:true`。
 * 根因不是判定式写错，而是**读到了上一页留下的状态** —— `onPageStarted` 要在
 * 主线程上晚一步才到，`loadUrl` 之后立刻读 `pageReady/progress` 读到的还是旧值。
 *
 * 所以这里锁的是"**导航已开始时，绝不许拿旧状态冒充就绪**"这条语义。
 */
class WebReadyTest {

    @Test
    fun `click 引起了导航且没等时一律报未就绪`() {
        assertFalse(
            "新导航已开始、又没有等待 —— 绝不能报就绪（会拿上一页冒充）",
            WebReady.readyUnwaited(navStarted = true, pageReady = true, progress = 100)
        )
        assertFalse(
            WebReady.readyUnwaited(navStarted = true, pageReady = false, progress = 10)
        )
    }

    @Test
    fun `click 没引起导航时沿用 WebView 报的状态`() {
        assertTrue(
            "没导航 = 页面没动，之前就绪现在仍就绪",
            WebReady.readyUnwaited(navStarted = false, pageReady = true, progress = 100)
        )
        assertFalse(
            WebReady.readyUnwaited(navStarted = false, pageReady = false, progress = 10)
        )
        assertFalse(
            "进度没走满不算就绪",
            WebReady.readyUnwaited(navStarted = false, pageReady = true, progress = 90)
        )
    }

    @Test
    fun `确定要导航的操作在没等待时 不拿上一页冒充`() {
        // ⚠ 5.9.38：原来这里测的是 `readyUnwaitedAfterNavigation`，而那个函数
        // **从写下来那天起没有任何调用方**（open/reload/back 直接调 waitForPage，
        // 语义一样），却带着好几条测试 —— 锁着一个没人用的东西。函数已删。
        //
        // 它锁的那条不变式还在，而且**就在下面这两行里**：
        // `navStarted = false` 时 readyUnwaited 就是 `pageReady && progress >= 100`，
        // 与那个被删的函数是同一个表达式。所以不变式没丢，只是不用再锁一个死函数。
        assertTrue(WebReady.readyUnwaited(navStarted = false, pageReady = true, progress = 100))
        assertFalse(
            "ready:true 与 progress:10 不能并存（5.9.4 真机指出的自相矛盾）",
            WebReady.readyUnwaited(navStarted = false, pageReady = true, progress = 10)
        )
        assertFalse(
            "pageReady 没置位时，progress 单独到 100 也不算就绪",
            WebReady.readyUnwaited(navStarted = false, pageReady = false, progress = 100)
        )
    }
}

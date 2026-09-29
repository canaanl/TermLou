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
    fun `open 这类确定要导航的操作按当前状态报`() {
        // 发起导航前已同步清过状态（markNavigationStarted），所以这里只看 WebView 报的值
        assertTrue(WebReady.readyUnwaitedAfterNavigation(pageReady = true, progress = 100))
        assertFalse(WebReady.readyUnwaitedAfterNavigation(pageReady = false, progress = 100))
        assertFalse(
            "ready:true 与 progress:10 不能并存（5.9.4 真机指出的自相矛盾）",
            WebReady.readyUnwaitedAfterNavigation(pageReady = true, progress = 10)
        )
    }

    @Test
    fun `自检：拿旧状态冒充就绪的那组输入必须判成未就绪`() {
        // 复刻 5.9.4 的现场：click 点了链接，导航刚开始，pageReady 仍是上一页的 true
        val readyBefore = WebReady.readyUnwaited(navStarted = true, pageReady = true, progress = 100)
        assertFalse("自检失败：这组正是假就绪的现场，检查没抓住", readyBefore)
    }

    @Test
    fun `自检：判定式不依赖 progress 单独为真`() {
        // progress=100 但 pageReady=false 是常见中间态，不能当就绪
        assertFalse(WebReady.readyUnwaitedAfterNavigation(pageReady = false, progress = 100))
        assertFalse(WebReady.readyUnwaited(navStarted = false, pageReady = false, progress = 100))
    }
}

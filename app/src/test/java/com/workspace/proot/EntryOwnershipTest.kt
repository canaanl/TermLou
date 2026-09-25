package com.workspace.proot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * termlou-ui 连续换屏错位修复（v5.4.2 治本内核）的时序锁定：
 * 换屏必须同步归属编号并结算上一屏等待方，同一请求只允许结算一次。
 */
class EntryOwnershipTest {

    private data class Req(val id: String)

    @Test
    fun `首屏接管没有被顶掉的等待方`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        assertNull(own.takeOver(s1))
        assertSame(s1, own.current)
    }

    @Test
    fun `上一屏已结算则换屏不再重复结算`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        own.takeOver(s1)
        // 点击 close=false：结算 + finish（current 清空）
        assertTrue(own.markResolved(s1))
        own.finish(s1)
        assertNull(own.current)

        val s2 = Req("s2")
        assertNull(own.takeOver(s2))
        assertSame(s2, own.current)
        // 陈旧点击晚到：被闸门拦下
        assertFalse(own.markResolved(s1))
        assertTrue(own.isResolved(s1))
    }

    @Test
    fun `上一屏仍在等待时换屏交还给调用方结算`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        val s2 = Req("s2")
        own.takeOver(s1)
        // 屏1 还没被点，屏2 的请求先到：必须把屏1 还出来结算（dismiss），不能让它挂到超时
        val superseded = own.takeOver(s2)
        assertSame(s1, superseded)
        assertSame(s2, own.current)
        assertTrue(own.markResolved(superseded!!))
    }

    @Test
    fun `finish 只清自己的指针不误清新屏归属`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        val s2 = Req("s2")
        own.takeOver(s1)
        own.takeOver(s2)
        // 屏1 的陈旧回调结算后调 finish：此时 current 已是屏2，指针必须保持
        own.finish(s1)
        assertSame(s2, own.current)
        // 本屏结算才清指针
        own.finish(s2)
        assertNull(own.current)
    }

    @Test
    fun `同一请求重复结算只认第一次`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        assertTrue(own.markResolved(s1))
        assertFalse(own.markResolved(s1))
        assertFalse(own.markResolved(s1))
    }

    @Test
    fun `takeOver 不会把同一个请求当等待方交还`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        own.takeOver(s1)
        assertNull(own.takeOver(s1))
        assertSame(s1, own.current)
    }

    @Test
    fun `reset 清空指针与结算标记`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        own.takeOver(s1)
        own.markResolved(s1)
        own.reset()
        assertNull(own.current)
        assertFalse(own.isResolved(s1))
        assertTrue(own.markResolved(s1))
    }

    @Test
    fun `连续三屏向导时序`() {
        val own = EntryOwnership<Req>()
        val s1 = Req("s1")
        val s2 = Req("s2")
        val s3 = Req("s3")

        // 屏1 展示、被点（close=false）
        assertNull(own.takeOver(s1))
        assertTrue(own.markResolved(s1))
        own.finish(s1)

        // 屏2 原位更新、被点
        assertNull(own.takeOver(s2))
        assertTrue(own.markResolved(s2))
        own.finish(s2)

        // 屏3 原位更新（同构按钮也不再产生错位：归属始终指向屏3）
        assertNull(own.takeOver(s3))
        assertSame(s3, own.current)
        assertTrue(own.markResolved(s3))
        own.finish(s3)

        // 三屏各结算一次，谁也没收到别人的结果
        assertFalse(own.markResolved(s1))
        assertFalse(own.markResolved(s2))
        assertFalse(own.markResolved(s3))
    }
}

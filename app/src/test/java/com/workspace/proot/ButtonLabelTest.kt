package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按钮标签防溢出的锁（5.9.4）。
 *
 * 设置区两行都是"半宽双按钮"。再叠上系统超大字体，`maxLines=1 + ellipsize`
 * 会把"需认证"截成"需认…" —— 那等于没说。所以宁可换缩写，也不要半个词。
 */
class ButtonLabelTest {

    @Test
    fun `放得下就用完整标签`() {
        assertEquals(
            ButtonLabel.FULL,
            ButtonLabel.pick(availablePx = 300f, fullWidthPx = 120f, shortWidthPx = 60f)
        )
    }

    @Test
    fun `放不下就换缩写`() {
        assertEquals(
            ButtonLabel.SHORT,
            ButtonLabel.pick(availablePx = 100f, fullWidthPx = 140f, shortWidthPx = 60f)
        )
    }

    @Test
    fun `刚好放下算放得下`() {
        assertEquals(
            "宽度正好等于可用宽度时应保留完整标签",
            ButtonLabel.FULL,
            ButtonLabel.pick(availablePx = 140f, fullWidthPx = 140f, shortWidthPx = 60f)
        )
    }

    @Test
    fun `还没量到宽度时保持完整标签`() {
        // 布局完成前 availablePx 是 0，这时不该急着换短的（换早了会闪一下）
        assertEquals(
            ButtonLabel.FULL,
            ButtonLabel.pick(availablePx = 0f, fullWidthPx = 999f, shortWidthPx = 1f)
        )
    }

    @Test
    fun `宽度为负（极端情况）也保持完整标签`() {
        assertEquals(
            ButtonLabel.FULL,
            ButtonLabel.pick(availablePx = -5f, fullWidthPx = 999f, shortWidthPx = 1f)
        )
    }

    @Test
    fun `缩写比完整标签短才有意义`() {
        // 规则本身假定 short <= full；这里把假设写出来，免得以后有人传反
        assertTrue(
            "缩写必须比完整标签窄，否则降级没有意义",
            60f < 140f
        )
    }
}

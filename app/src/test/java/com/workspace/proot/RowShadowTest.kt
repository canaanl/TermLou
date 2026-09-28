package com.workspace.proot

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

/**
 * 键下方外投影的几何锁：等价于 CSS `box-shadow: 0 OFFSET_Y BLUR color`。
 * 关键约束——总下延必须塞进投影带（行高 58dp 拆出 6dp 给阴影），
 * 否则阴影会溢出到下一行键面上；模糊外扩必须为正，否则圆角处会"缺影子"。
 */
class RowShadowTest {

    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)

    @Test
    fun shadowFitsInsideProjectionBand() {
        for (d in densities) {
            val bandPx = (RowKeyDrawable.BAND_DP * d).roundToInt()
            val ext = RowShadow.extentPx(d)
            assertTrue(
                "density=$d：投影总下延 $ext 必须 ≤ 投影带 $bandPx",
                ext in 1..bandPx
            )
        }
    }

    @Test
    fun blurSpreadIsAlwaysPositive() {
        for (d in densities) {
            assertTrue("density=$d 模糊半径须 ≥1px", RowShadow.blurPx(d) >= 1)
            assertTrue(
                "density=$d 外扩须大于等于模糊半径（否则圆角处缺影子）",
                RowShadow.extentPx(d) > RowShadow.blurPx(d)
            )
            assertTrue("density=$d 偏移须 ≥0", RowShadow.offsetYPx(d) >= 0)
        }
    }

    @Test
    fun extentGrowsWithDensity() {
        var prev = 0
        for (d in densities) {
            val ext = RowShadow.extentPx(d)
            assertTrue("density=$d 外扩应随密度单调不减（$prev → $ext）", ext >= prev)
            prev = ext
        }
    }

    @Test
    fun alphaIsVisibleButNotOpaque() {
        val a = RowShadow.alpha255()
        assertTrue("投影透明度 $a 应在 (0, 255)", a in 1..254)
    }
}

package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 按键几何锁（按抽屉实际布局定的，不是照抄悬浮加号）：
 *  - 行高必须仍是 58dp（键面 + 影带）→ 面板总高公式 30dp + rows×58dp + 18dp 不变；
 *  - 影带必须容得下 elevation 的真阴影模糊，否则阴影溢出、糊掉相邻键的上棱；
 *  - 按下时 elevation 必须真的收掉（凹陷 = 影子消失）。
 */
class KeyGeometryTest {

    /** 抽屉行高（dp）——与 TileDrawer 的 58dp 行高、面板总高公式绑定。 */
    private val rowDp = 58

    private val densities = listOf(1f, 1.5f, 2f, 2.625f, 2.75f, 3f, 3.5f, 4f)

    @Test
    fun keyFacePlusBandEqualsRowHeight() {
        assertEquals(
            "键面 + 影带必须等于行高 $rowDp dp（面板总高公式不能变）",
            rowDp,
            RowKeyDrawable.KEY_FACE_DP + RowKeyDrawable.BAND_DP
        )
    }

    @Test
    fun bandFitsElevationBlur() {
        for (d in densities) {
            val bandPx = (RowKeyDrawable.BAND_DP * d).toInt()
            val elevationPx = (RowKeyDrawable.ELEVATION_DP * d).toInt()
            assertTrue(
                "density=$d：影带 ${bandPx}px 装不下 elevation 阴影 $elevationPx" +
                    "px（会溢出到相邻键）",
                bandPx >= elevationPx
            )
        }
    }

    @Test
    fun bandFitsHandDrawnOffset() {
        for (d in densities) {
            val bandPx = (RowKeyDrawable.BAND_DP * d).toInt()
            val offsetPx = (RowKeyDrawable.OFFSET_DP * d).toInt()
            assertTrue(
                "density=$d：影带 ${bandPx}px 装不下手绘影偏移 $offsetPx px（右下暗影会被切）",
                bandPx >= offsetPx
            )
        }
    }

    @Test
    fun pressedElevationCollapsesShadow() {
        assertTrue(
            "按下态 elevation 必须小于静止态（凹陷 = 影子收掉）",
            RowKeyDrawable.PRESSED_ELEVATION_DP < RowKeyDrawable.ELEVATION_DP
        )
    }
}

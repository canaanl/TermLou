package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 缝隙活动条的判据锁（5.9.19）。
 *
 * 用户提的两条改版要求：
 * 1. **等宽等高** —— 之前激活格满高、轨道点 34% 高，一眼两种高度。现在每格都一样高。
 * 2. **单焦点匀速** —— 之前 8 格单元平铺满宽，全宽 9 个亮头一起动。现在整条只有 1 个焦点。
 */
class ActivityTrailTest {

    // ---------- 参数按用户要求 ----------

    @Test
    fun `参数按用户定的来`() {
        assertEquals("一趟 1.5s", 1500f, ActivityTrail.SWEEP_MS, 0.01f)
        assertEquals("右端停 360ms（同原版 9 帧）", 360f, ActivityTrail.HOLD_RIGHT_MS, 0.01f)
        assertEquals("左端停 1200ms（同原版 30 帧）", 1200f, ActivityTrail.HOLD_LEFT_MS, 0.01f)
        assertEquals("拖尾 3 倍 = 18 级", 18, ActivityTrail.TRAIL_STEPS)
        assertEquals("一个来回 4.56s", 4560f, ActivityTrail.CYCLE_MS, 1f)
    }

    // ---------- 焦点运动：匀速、往复 ----------

    @Test
    fun `焦点从左匀速走到右`() {
        val cells = 73
        val last = 72f
        assertEquals(0f, ActivityTrail.focusCell(cells, 0f), 0.01f)
        assertEquals("一半时间走一半路", last / 2f, ActivityTrail.focusCell(cells, 750f), 0.5f)
        assertEquals("到右端", last, ActivityTrail.focusCell(cells, 1500f), 0.01f)
    }

    @Test
    fun `匀速意味着等时间走等距离`() {
        val cells = 73
        val samples = listOf(100f, 300f, 500f, 700f, 900f, 1100f, 1300f)
        val positions = samples.map { ActivityTrail.focusCell(cells, it) }
        val gaps = positions.zipWithNext { a, b -> b - a }
        for (i in 1 until gaps.size) {
            assertEquals(
                "等间隔时间的位移必须相等（第 $i 段 ${gaps[i - 1]} vs ${gaps[i]}）",
                gaps[i - 1], gaps[i], 0.01f
            )
        }
    }

    @Test
    fun `右端停完折回左端再停完回到起点`() {
        val cells = 73
        val last = 72f
        // 右停期间不动
        assertEquals(last, ActivityTrail.focusCell(cells, 1500f), 0.01f)
        assertEquals(last, ActivityTrail.focusCell(cells, 1859f), 0.01f)
        // 左扫
        assertEquals(last / 2f, ActivityTrail.focusCell(cells, 1500f + 360f + 750f), 0.5f)
        assertEquals(0f, ActivityTrail.focusCell(cells, 1500f + 360f + 1500f), 0.01f)
        // 左停期间不动
        assertEquals(0f, ActivityTrail.focusCell(cells, 4500f), 0.01f)
        // 周期末尾回到起点（无缝）
        assertEquals(0f, ActivityTrail.focusCell(cells, 4560f - 1f), 0.5f)
    }

    @Test
    fun `方向在折返时跟着换边`() {
        val afterRightHold = ActivityTrail.SWEEP_MS + ActivityTrail.HOLD_RIGHT_MS
        // 右扫：向右
        assertTrue("起点", ActivityTrail.isMovingForward(0f))
        assertTrue("右扫途中", ActivityTrail.isMovingForward(ActivityTrail.SWEEP_MS - 1f))
        // 右停：沿用最后方向，仍是向右
        assertTrue("右端停留", ActivityTrail.isMovingForward(ActivityTrail.SWEEP_MS))
        assertTrue("右停末尾", ActivityTrail.isMovingForward(afterRightHold - 1f))
        // 左扫：换成向左
        assertEquals("左扫起点", false, ActivityTrail.isMovingForward(afterRightHold))
        assertEquals("左扫途中", false, ActivityTrail.isMovingForward(afterRightHold + 700f))
        // 左停：沿用最后方向，仍是向左
        assertEquals(
            "左端停留",
            false,
            ActivityTrail.isMovingForward(afterRightHold + ActivityTrail.SWEEP_MS)
        )
    }

    @Test
    fun `两端停留时焦点不动`() {
        assertTrue("右停", ActivityTrail.isHolding(1600f))
        assertTrue("左停", ActivityTrail.isHolding(4400f))
        assertEquals("右扫时不在停留", false, ActivityTrail.isHolding(700f))
        assertEquals("左扫时不在停留", false, ActivityTrail.isHolding(2600f))
    }

    @Test
    fun `任何时刻只有一个焦点`() {
        // 用户原话："很多一起移动"→ 要"只有一个凸显的焦点"。
        // 判据：任取一帧，轨道上最亮的那一格有且只有一格（拖尾严格递减）。
        val cells = 73
        var frame = 0f
        while (frame < ActivityTrail.CYCLE_MS) {
            val focus = ActivityTrail.focusCell(cells, frame)
            val forward = ActivityTrail.isMovingForward(frame)
            var best = -1f
            var second = -1f
            for (i in 0 until cells) {
                val a = ActivityTrail.alphaAt(
                    ActivityTrail.signedDistance(focus, i.toFloat(), forward), frame
                )
                if (a > best) {
                    second = best
                    best = a
                } else if (a > second) {
                    second = a
                }
            }
            // 最亮的比第二名明显更亮（拖尾是递减的，不该有并列）
            assertTrue(
                "第 ${frame}ms 出现并列焦点：best=$best second=$second",
                best > second + 0.001f
            )
            frame += 37f
        }
    }

    // ---------- 明暗：重新算过 ----------

    @Test
    fun `明暗是对数均分不是照搬原版的指数衰减`() {
        // 原版 6 级用 0.65^(i-1)：照搬到 18 级会衰减到 0.0016，后半截全黑。
        val last = ActivityTrail.trailAlpha(ActivityTrail.TRAIL_STEPS - 1)
        assertEquals("末级必须落在下限上", ActivityTrail.TRAIL_FLOOR, last, 0.001f)
        assertEquals("首级最亮", 1f, ActivityTrail.trailAlpha(0), 0.001f)
        // 全程可见：每一级都不低于下限
        for (i in 0 until ActivityTrail.TRAIL_STEPS) {
            assertTrue("第 $i 级 ${ActivityTrail.trailAlpha(i)} 低于下限", ActivityTrail.trailAlpha(i) >= ActivityTrail.TRAIL_FLOOR - 0.001f)
        }
    }

    @Test
    fun `明暗单调递减`() {
        var prev = 2f
        for (i in 0 until ActivityTrail.TRAIL_STEPS) {
            val a = ActivityTrail.trailAlpha(i)
            assertTrue("第 $i 级 $a 没有比上一级 $prev 小", a < prev)
            prev = a
        }
    }

    @Test
    fun `拖尾之外与焦点前方都是零`() {
        // 焦点正前方（正向走时 = 右侧）没有拖尾 → 0
        assertEquals(0f, ActivityTrail.alphaAt(-1f, 0f), 0.001f)
        assertEquals(0f, ActivityTrail.alphaAt(-0.4f, 0f), 0.001f)
        // 焦点自己最亮
        assertEquals(1f, ActivityTrail.alphaAt(0f, 0f), 0.001f)
        // 拖尾长度之外必须是 0（那一段由暗色轨道兜底）
        val beyond = ActivityTrail.TRAIL_STEPS.toFloat() + 0.5f
        assertEquals("拖尾外必须是 0", 0f, ActivityTrail.alphaAt(beyond, 0f), 0.001f)
    }

    @Test
    fun `拖尾只长在焦点身后`() {
        // 正向走时拖尾在左侧；反向走时拖尾在右侧 —— 焦点前方永远暗
        val focus = 10.5f
        val behindLeft = ActivityTrail.signedDistance(focus, 10f, true)
        val aheadRight = ActivityTrail.signedDistance(focus, 11f, true)
        assertTrue("正向走时左侧（身后）距离为正：$behindLeft", behindLeft > 0f)
        assertTrue("正向走时右侧（前方）距离为负：$aheadRight", aheadRight < 0f)
        assertTrue("身后那格要有亮度", ActivityTrail.alphaAt(behindLeft, 0f) > 0f)
        assertEquals("前方那格必须全暗", 0f, ActivityTrail.alphaAt(aheadRight, 0f), 0.001f)

        // 反向走：拖尾换到右侧
        val behindRight = ActivityTrail.signedDistance(focus, 11f, false)
        assertTrue("反向走时右侧（身后）距离为正：$behindRight", behindRight > 0f)
        assertTrue("反向走时右侧要有亮度", ActivityTrail.alphaAt(behindRight, 0f) > 0f)
        assertEquals(
            "反向走时左侧变暗",
            0f,
            ActivityTrail.alphaAt(ActivityTrail.signedDistance(focus, 10f, false), 0f),
            0.001f
        )
    }

    @Test
    fun `格内小数距离线性插值所以移动没有档位感`() {
        // 焦点正好落在两格之间时，身后那一格不能突然掉到 0（那会看出档位）
        val focus = 10.9f
        val near = ActivityTrail.alphaAt(ActivityTrail.signedDistance(focus, 10f, true), 0f)
        val far = ActivityTrail.alphaAt(ActivityTrail.signedDistance(focus, 9f, true), 0f)
        assertTrue("贴身那格要亮：$near", near > 0.5f)
        assertTrue("再远一格要暗：$far", far < near)
        // 焦点右移一格，这格就该从"很亮"变成"刚好拖尾起点"，中间没有断崖
        val later = ActivityTrail.alphaAt(ActivityTrail.signedDistance(11.0f, 10f, true), 0f)
        assertTrue("右移后仍连续：$near → $later", later < near && later > 0f)
    }

    @Test
    fun `轨道暗色也跟着呼吸`() {
        assertEquals(
            "移动起步最暗",
            ActivityTrail.MIN_ALPHA,
            ActivityTrail.fadeFactor(0f),
            0.001f
        )
        assertEquals("扫到末尾升到满亮", 1f, ActivityTrail.fadeFactor(1499f), 0.001f)
        assertTrue("停留时往下落", ActivityTrail.fadeFactor(1700f) < 1f)
        assertTrue(
            "轨道 alpha 永远不会是 0（那就不是暗而是没有）",
            ActivityTrail.trackAlpha(2000f) > 0f
        )
    }

    @Test
    fun `空闲阈值留得住连续重绘的TUI`() {
        assertTrue(TerminalActivityBar.IDLE_MS >= 500L)
        assertTrue(TerminalActivityBar.IDLE_MS <= 2000L)
    }

    @Test
    fun `极窄轨道不许崩`() {
        assertEquals(0f, ActivityTrail.focusCell(0, 100f), 0.001f)
        assertEquals(0f, ActivityTrail.focusCell(1, 100f), 0.001f)
        // 单格轨道上焦点永远在 0，alpha 合法
        val a = ActivityTrail.alphaAt(ActivityTrail.signedDistance(0f, 0f, true), 100f)
        assertTrue(a >= 0f && a <= 1f)
    }
}
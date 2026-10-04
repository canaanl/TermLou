package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * opencode v2 进度条的判据锁。
 *
 * 用户要求"一模一样的样式"，所以 54 帧一帧一帧锁死，不许被"优化"。
 * 源：`anomalyco/opencode` 的 `packages/tui/src/ui/spinner.ts` + `footer.view.tsx`。
 */
class OpencodeRiderTest {

    // ---------- 参数与原版一致 ----------

    @Test
    fun `参数与opencode页脚一致`() {
        assertEquals(8, OpencodeRider.WIDTH)
        assertEquals(6, OpencodeRider.TRAIL_STEPS)
        assertEquals(9, OpencodeRider.HOLD_END)
        assertEquals(30, OpencodeRider.HOLD_START)
        assertEquals(40L, OpencodeRider.INTERVAL_MS)
        assertEquals(0.6f, OpencodeRider.INACTIVE_FACTOR, 1e-6f)
        assertEquals(0.3f, OpencodeRider.MIN_ALPHA, 1e-6f)
        assertEquals(1.15f, OpencodeRider.GLARE_BOOST, 1e-6f)
        assertEquals("一个来回 54 帧", 54, OpencodeRider.TOTAL_FRAMES)
        assertEquals(2160L, OpencodeRider.TOTAL_FRAMES * OpencodeRider.INTERVAL_MS)
        assertEquals("激活格 ■ U+25A0", 0x25A0, OpencodeRider.ACTIVE_GLYPH.code)
        assertEquals("未激活格 ⬝ U+2B1D", 0x2B1D, OpencodeRider.IDLE_GLYPH.code)
    }

    // ---------- 54 帧序列逐帧锁死 ----------

    @Test
    fun `右移八帧头从左走到右`() {
        for (f in 0 until 8) assertEquals("第 $f 帧", f, OpencodeRider.activePosition(f))
    }

    @Test
    fun `右端停九帧`() {
        for (f in 8..16) {
            assertEquals("第 $f 帧应停在右端", 7, OpencodeRider.activePosition(f))
            assertTrue("第 $f 帧应在停留", OpencodeRider.isHolding(f))
        }
        assertEquals("第 7 帧还在走", false, OpencodeRider.isHolding(7))
        assertEquals("第 17 帧已回头", false, OpencodeRider.isHolding(17))
    }

    @Test
    fun `回头七帧走回左端`() {
        val expected = listOf(6, 5, 4, 3, 2, 1, 0)
        for (i in 0 until 7) assertEquals("回头第 $i 帧", expected[i], OpencodeRider.activePosition(17 + i))
    }

    @Test
    fun `左端停三十帧后回到起点`() {
        for (f in 24 until 54) {
            assertEquals("第 $f 帧应停在左端", 0, OpencodeRider.activePosition(f))
            assertTrue("第 $f 帧应在停留", OpencodeRider.isHolding(f))
        }
        assertEquals("第 54 帧就是下一轮第 0 帧", 0, OpencodeRider.activePosition(54 % 54))
    }

    @Test
    fun `头最亮身后递减到六级为止`() {
        assertEquals("第 5 帧头在 5", 0, OpencodeRider.trailIndex(5, 5))
        assertEquals("身后 5 级", 5, OpencodeRider.trailIndex(5, 0))
        assertEquals(4, OpencodeRider.trailIndex(5, 1))
        assertEquals(1, OpencodeRider.trailIndex(5, 4))
        assertEquals("超出 6 级就是未激活", -1, OpencodeRider.trailIndex(5, 6))
        assertEquals("头前方永远是未激活", -1, OpencodeRider.trailIndex(5, 7))
        assertEquals("第 0 帧只有头", 0, OpencodeRider.trailIndex(0, 0))
        assertEquals(1, (0 until 8).count { OpencodeRider.trailIndex(0, it) >= 0 })
    }

    @Test
    fun `每格拖尾级号都合法`() {
        for (f in 0 until 54) {
            for (c in 0 until 8) {
                val idx = OpencodeRider.trailIndex(f, c)
                assertTrue("第 $f 帧第 $c 格 = $idx，超出 -1..5", idx in -1 until 6)
            }
        }
    }

    @Test
    fun `拖尾alpha是原版那一串`() {
        val expected = listOf(1.0f, 0.9f, 0.65f, 0.4225f, 0.274625f, 0.17850625f)
        for (i in expected.indices) {
            assertEquals("第 $i 级", expected[i], OpencodeRider.trailAlpha(i), 1e-6f)
        }
    }

    @Test
    fun `移动时淡入停留时淡出`() {
        assertEquals("第 0 帧最暗起步", OpencodeRider.MIN_ALPHA, OpencodeRider.fadeFactor(0), 1e-6f)
        assertEquals("右移结束升到满亮", 1f, OpencodeRider.fadeFactor(7), 1e-6f)
        assertEquals("右端停留起始仍是满亮", 1f, OpencodeRider.fadeFactor(8), 1e-6f)
        assertEquals("回头第 0 帧重新从最暗起步", OpencodeRider.MIN_ALPHA, OpencodeRider.fadeFactor(17), 1e-6f)
        // 停留期间单调下降，且始终不低于下限
        var prev = 1f
        for (f in 8 until 17) {
            val v = OpencodeRider.fadeFactor(f)
            assertTrue("第 $f 帧应不高于上一帧（$v > $prev）", v <= prev + 1e-6f)
            assertTrue("第 $f 帧不该低于下限", v >= OpencodeRider.MIN_ALPHA - 1e-6f)
            prev = v
        }
        // 未激活格 alpha = 0.6 × fade，永远不会是 0
        for (f in 0 until 54) {
            val a = OpencodeRider.inactiveAlpha(f)
            assertTrue("第 $f 帧未激活 alpha=$a", a > 0f && a <= OpencodeRider.INACTIVE_FACTOR + 1e-6f)
        }
    }

    // ---------- 一半时间是暗的：opencode 原味，不许当 bug 修掉 ----------

    @Test
    fun `拖尾只有六级所以停留到第六帧就全暗`() {
        // 停留时整条拖尾按 holdProgress **后移一级**（原版 calculateColorIndex 的
        // `directionalDistance + holdProgress`），所以头本身也是一级一级变暗的：
        // 第 8 帧还是 0 级 → 第 13 帧已到第 5 级 → 第 14 帧跌出级数变暗点。
        assertEquals("第 8 帧头还是最亮", 0, OpencodeRider.trailIndex(8, 7))
        assertEquals("第 13 帧头已暗到第 5 级", 5, OpencodeRider.trailIndex(13, 7))
        for (f in 14..16) {
            assertEquals("第 $f 帧应已全暗", -1, OpencodeRider.trailIndex(f, 7))
            assertTrue("第 $f 帧整条无亮块", !OpencodeRider.hasBrightBlock(f))
        }
        // 左端停留：第 30 帧起全暗，一直暗到第 53 帧
        assertTrue("第 30 帧整条无亮块", !OpencodeRider.hasBrightBlock(30))
        assertTrue("第 53 帧整条无亮块", !OpencodeRider.hasBrightBlock(53))
    }

    @Test
    fun `暗的帧数正好是一半`() {
        var dark = 0
        for (f in 0 until 54) if (!OpencodeRider.hasBrightBlock(f)) dark++
        assertEquals("3（右端）+ 24（左端）= 27 帧，正好一半", 27, dark)
        assertEquals(27, OpencodeRider.TOTAL_FRAMES / 2)
    }

    @Test
    fun `移动期间一定有亮块`() {
        val moving = (0 until 8) + (17 until 24)
        for (f in moving) {
            assertTrue("第 $f 帧必须有亮块", OpencodeRider.hasBrightBlock(f))
            val head = OpencodeRider.activePosition(f)
            assertTrue("头自己必须是亮的", OpencodeRider.trailIndex(f, head) >= 0)
        }
    }

    @Test
    fun `是往复不是单向所以头被反复经过`() {
        val seen = HashMap<Int, Int>()
        var revisits = 0
        for (f in 0 until 54) {
            val head = OpencodeRider.activePosition(f)
            if (seen.containsKey(head)) revisits++ else seen[head] = f
        }
        assertEquals(54 - 8, revisits)
        val path = (0 until 54).map { OpencodeRider.activePosition(it) }
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 6, 7), path.subList(0, 8))
        assertEquals(listOf(6, 5, 4, 3, 2, 1, 0), path.subList(17, 24))
    }

    @Test
    fun `空闲阈值留得住连续重绘的TUI`() {
        assertTrue("太小会一顿一顿：${TerminalActivityBar.IDLE_MS}", TerminalActivityBar.IDLE_MS >= 500L)
        assertTrue("太长会一直转：${TerminalActivityBar.IDLE_MS}", TerminalActivityBar.IDLE_MS <= 2000L)
    }
}

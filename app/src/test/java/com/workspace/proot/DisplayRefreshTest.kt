package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 显示模式挑选的锁（5.9.58）。
 *
 * 这条锁要守的是三件事，按重要性排：
 *
 * 1. **绝不换分辨率** —— 换模式时如果顺手把分辨率也换了，整个版式会重排，
 *    那是这个改动能造成的最大风险。宁可停在 60Hz 也不能动尺寸。
 * 2. **已经最优就不切** —— 非无缝的切模式可能黑屏一秒上下（官方文档），
 *    没有收益就不该动。
 * 3. **真的选最高的** —— 这才是"支持高刷就生效"的本体。
 *
 * 纯函数，不碰 Android API，所以断言是精确值而不是近似。
 */
class DisplayRefreshTest {

    // ---------- 1. 绝不换分辨率（最大风险） ----------

    @Test
    fun `分辨率不一致的模式一律不选`() {
        // 120Hz 的模式分辨率不同 —— 看着更高也不许碰
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(2, 1440, 3200, 120f),   // 尺寸不同
                DisplayRefresh.Mode(3, 1080, 1920, 144f),    // 尺寸不同
                DisplayRefresh.Mode(4, 720, 1600, 165f)     // 尺寸不同
            )
        )
        assertEquals("换了分辨率就是灾难，必须返回不动", 0, t.modeId)
        assertEquals("不动时 Hz 应如实回报当前值", 60f, t.hz, 0.01f)
    }

    @Test
    fun `横竖对调也算不一致`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(2, 2400, 1080, 120f)    // 转置了，不是一回事
            )
        )
        assertEquals(0, t.modeId)
    }

    @Test
    fun `没有同分辨率的模式就放弃升刷`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(DisplayRefresh.Mode(1, 1440, 3200, 120f))
        )
        assertEquals(0, t.modeId)
    }

    // ---------- 2. 已经最优就不切（避免黑屏） ----------

    @Test
    fun `当前已经是最高档就不切`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 120f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(2, 1080, 2400, 120f),
                DisplayRefresh.Mode(3, 1080, 2400, 90f)
            )
        )
        assertEquals("已是最高档，切它就是白挨一次黑屏", 0, t.modeId)
        assertEquals(120f, t.hz, 0.01f)
    }

    @Test
    fun `小数刷新率的小数抖动不许触发切换`() {
        // 面板报 59.94 而目标是 60 —— 差 0.06Hz，切它纯属浪费一次黑屏
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 59.94f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 59.94f),
                DisplayRefresh.Mode(2, 1080, 2400, 60f)
            )
        )
        assertEquals(0, t.modeId)
    }

    @Test
    fun `真的更高才切`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 59.94f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 59.94f),
                DisplayRefresh.Mode(2, 1080, 2400, 120f)
            )
        )
        assertEquals("高了 60Hz 就该切", 2, t.modeId)
        assertEquals(120f, t.hz, 0.01f)
    }

    // ---------- 3. 真的选最高的 ----------

    @Test
    fun `120Hz设备选出120那档`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(2, 1080, 2400, 90f),
                DisplayRefresh.Mode(3, 1080, 2400, 120f)
            )
        )
        assertEquals(3, t.modeId)
        assertEquals(120f, t.hz, 0.01f)
    }

    @Test
    fun `144Hz设备选出144那档而不是120`() {
        val t = DisplayRefresh.pickTarget(
            1440, 3200, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1440, 3200, 60f),
                DisplayRefresh.Mode(2, 1440, 3200, 90f),
                DisplayRefresh.Mode(3, 1440, 3200, 120f),
                DisplayRefresh.Mode(4, 1440, 3200, 144f)
            )
        )
        assertEquals("支持多高就给多高，不是写死 120", 4, t.modeId)
        assertEquals(144f, t.hz, 0.01f)
    }

    @Test
    fun `modeId不必连续取的是最大刷新率那一档`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(7, 1080, 2400, 60f),
                DisplayRefresh.Mode(9, 1080, 2400, 90f),
                DisplayRefresh.Mode(2, 1080, 2400, 144f)
            )
        )
        assertEquals("按 Hz 选，不是按 id 选", 2, t.modeId)
    }

    // ---------- 4. 只支持 60 的设备不折腾 ----------

    @Test
    fun `只有60Hz的设备返回不动`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(DisplayRefresh.Mode(1, 1080, 2400, 60f))
        )
        assertEquals(0, t.modeId)
        assertEquals(60f, t.hz, 0.01f)
    }

    @Test
    fun `模式列表为空不许崩`() {
        val t = DisplayRefresh.pickTarget(1080, 2400, 60f, emptyList())
        assertEquals(0, t.modeId)
        assertEquals(60f, t.hz, 0.01f)
    }

    @Test
    fun `尺寸还没量出来时不许崩`() {
        // onCreate 里 display.mode 理论上总有效，但兜底要有 —— 崩在这里等于冷启动黑屏
        assertEquals(0, DisplayRefresh.pickTarget(0, 0, 60f, listOf(DisplayRefresh.Mode(1, 1080, 2400, 120f))).modeId)
        assertEquals(0, DisplayRefresh.pickTarget(-1, 2400, 60f, emptyList()).modeId)
    }

    // ---------- 幂等：多调不会来回切 ----------

    @Test
    fun `坏模式不许把最高档选走`() {
        // 面板偶尔报出 0Hz / 负数。maxBy 若不先滤掉，可能选中一个 0Hz 的"最高"，
        // 那会让系统切到一个不存在的模式 —— 比不切更糟。
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(2, 1080, 2400, 0f),      // 坏模式
                DisplayRefresh.Mode(3, 1080, 2400, 120f)
            )
        )
        assertEquals(3, t.modeId)
        assertEquals(120f, t.hz, 0.01f)
    }

    @Test
    fun `只有坏模式时不动`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(2, 1080, 2400, 0f)
            )
        )
        assertEquals("切到 0Hz 的模式比不切更糟", 0, t.modeId)
    }

    @Test
    fun `切完之后再调不会又切回去`() {
        val all = listOf(
            DisplayRefresh.Mode(1, 1080, 2400, 60f),
            DisplayRefresh.Mode(2, 1080, 2400, 120f)
        )
        val first = DisplayRefresh.pickTarget(1080, 2400, 60f, all)
        assertEquals(2, first.modeId)
        // 切完之后的当前值就是 120，再调一次必须是"不动"——否则会来回黑屏
        val second = DisplayRefresh.pickTarget(1080, 2400, 120f, all)
        assertEquals("已是高档就不许再切", 0, second.modeId)
    }

    // ---------- 请求值必须和选中的模式自洽 ----------

    @Test
    fun `返回的Hz永远来自真实模式列表`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 60f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(5, 1080, 2400, 90f)
            )
        )
        assertEquals(5, t.modeId)
        assertEquals("Hz 必须和 modeId 指的那一档一致", 90f, t.hz, 0.01f)
    }

    @Test
    fun `不动时Hz如实回报当前档位`() {
        val t = DisplayRefresh.pickTarget(
            1080, 2400, 90f,
            listOf(
                DisplayRefresh.Mode(1, 1080, 2400, 60f),
                DisplayRefresh.Mode(5, 1080, 2400, 90f)
            )
        )
        assertEquals(0, t.modeId)
        assertTrue("不动就要如实说现在是多少", t.hz in 89f..91f)
    }

    // ---------- 5.9.60：ARR 投票常量 ----------

    @Test
    fun `ARR的High票常量必须与AOSP一致`() {
        // 反射传的是裸 int，值错了会投成 Normal（60Hz）—— 而那正是本 app 锁在 60 的原因。
        // 值取自 AOSP View.REQUESTED_FRAME_RATE_CATEGORY_HIGH。
        assertEquals(
            "REQUESTED_FRAME_RATE_CATEGORY_HIGH 必须是 4",
            4,
            DisplayRefresh.CATEGORY_HIGH
        )
    }

    // ---------- 5.9.61：bestSupportedHz —— 要请求的帧率永远是最高档 ----------

    /** 一块典型的三档屏（1080×2400）。 */
    private val MODES_60_90_120 = listOf(
        DisplayRefresh.Mode(1, 1080, 2400, 60f),
        DisplayRefresh.Mode(2, 1080, 2400, 90f),
        DisplayRefresh.Mode(3, 1080, 2400, 120f)
    )

    @Test
    fun `请求值取的是设备最高档不是当前档`() {
        // ⚠ 5.9.58~5.9.60 的根因就在这：请求值取成了"当前刷新率"，
        // 启动时屏幕 60，于是向系统要 60，永远锁 60。
        assertEquals(
            "120Hz 设备当前跑 60 时必须请求 120",
            120f,
            DisplayRefresh.bestSupportedHz(1080, 2400, MODES_60_90_120),
            0.01f
        )
        assertEquals(
            "当前已经 120 时也还是报 120（不是报当前值就算完）",
            120f,
            DisplayRefresh.bestSupportedHz(1080, 2400, MODES_60_90_120),
            0.01f
        )
    }

    @Test
    fun `请求值不受当前档位影响`() {
        // 同一块屏幕、同一组模式，只改"当前是多少"，请求值必须一模一样。
        val modes = MODES_60_90_120
        val from60 = DisplayRefresh.bestSupportedHz(1080, 2400, modes)
        val from90 = DisplayRefresh.bestSupportedHz(1080, 2400, modes)
        assertEquals("请求值与当前档位无关", from60, from90, 0.01f)
        assertEquals(120f, from60, 0.01f)
    }

    @Test
    fun `请求值同样只认同分辨率`() {
        assertEquals(
            "更高刷但分辨率不同的模式不许被选中",
            60f,
            DisplayRefresh.bestSupportedHz(
                1080, 2400,
                listOf(
                    DisplayRefresh.Mode(1, 1080, 2400, 60f),
                    DisplayRefresh.Mode(2, 1440, 3200, 144f)
                )
            ),
            0.01f
        )
    }

    @Test
    fun `请求值不许被坏模式带偏`() {
        assertEquals(
            120f,
            DisplayRefresh.bestSupportedHz(
                1080, 2400,
                listOf(
                    DisplayRefresh.Mode(1, 1080, 2400, 0f),
                    DisplayRefresh.Mode(2, 1080, 2400, 120f)
                )
            ),
            0.01f
        )
    }

    @Test
    fun `没有同分辨率模式时请求值是零而不是猜一个`() {
        assertEquals(0f, DisplayRefresh.bestSupportedHz(1080, 2400, emptyList()), 0.01f)
        assertEquals(
            0f,
            DisplayRefresh.bestSupportedHz(
                1080, 2400, listOf(DisplayRefresh.Mode(1, 720, 1600, 120f))
            ),
            0.01f
        )
        assertEquals("尺寸没量出来时不许崩", 0f, DisplayRefresh.bestSupportedHz(0, 0, MODES_60_90_120), 0.01f)
    }

    @Test
    fun `只有60Hz的设备请求值就是60`() {
        assertEquals(
            60f,
            DisplayRefresh.bestSupportedHz(
                1080, 2400, listOf(DisplayRefresh.Mode(1, 1080, 2400, 60f))
            ),
            0.01f
        )
    }
}

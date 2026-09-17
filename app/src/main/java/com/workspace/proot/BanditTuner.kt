package com.workspace.proot

/**
 * 并列带轮换分：只在基础分差 TIE_BAND 以内动手，之外与旧逻辑一致。
 *
 * 全零冷启动（含老版本升级上来缺表）回退到按最近使用排，与旧行为逐字节一致；
 * 学开后由轮换分接管并列裁决。更新只发生在用户真实点击上，无负反馈。
 */
object BanditTuner {
    const val STEP = 0.1f
    const val DECAY = 0.95f
    const val CAP = 1.0f

    /** 回放日志上限（条）：只记（状态，前文，点击），供离线重放验证用。 */
    const val REPLAY_CAP = 500

    fun weightOf(weights: Map<String, Map<String, Float>>, state: String, id: String): Float =
        weights[state]?.get(id) ?: 0f

    /** 点的加分（封顶），同状态其他人衰减；缺桶自动建。 */
    fun onTap(weights: MutableMap<String, MutableMap<String, Float>>, state: String, tappedId: String) {
        val inner = weights.getOrPut(state) { mutableMapOf() }
        for ((id, w) in inner) {
            if (id != tappedId) inner[id] = w * DECAY
        }
        inner[tappedId] = ((inner[tappedId] ?: 0f) + STEP).coerceAtMost(CAP)
    }

    /** 并列裁决：轮换分高者上；相等（含全零冷启动）回退按最近使用排。 */
    fun winsTie(w: Float, bestW: Float, used: Long, bestUsed: Long): Boolean =
        w > bestW || (w == bestW && used > bestUsed)
}

/** 单条回放样本：状态 + 预测用前文 + 实际点击，按时间顺序追加。 */
data class ReplaySample(val state: String, val anchor: List<String>, val tappedId: String)

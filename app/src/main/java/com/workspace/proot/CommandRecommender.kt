package com.workspace.proot

import kotlin.math.exp

object CommandRecommender {

    const val W_STATE = 1.0f
    const val W_SUCC = 3.0f
    const val W_GLOBAL = 0.8f
    const val W_RECENT = 0.5f

    private const val SEQ_CAP = 24
    private const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000
    private const val SUCC_CONF_K = 3f
    private const val MIN_TRANSITIONS = 3

    fun summonScore(
        id: String,
        stateUsage: Map<String, Int>?,
        stateSeq: List<String>,
        anchor: List<String>,
        usage: Map<String, Map<String, Int>>,
        lastUsed: Map<String, Long>,
        now: Long
    ): Float {
        val freqN = stateFreqN(id, stateUsage)
        val succN = successorN(id, stateSeq, anchor)
        val damp = 1f - 0.5f * freqN
        val globN = globalN(id, usage) * damp
        val recN = recencyN(lastUsed[id], now)
        return W_STATE * freqN + W_SUCC * succN + W_GLOBAL * globN + W_RECENT * recN
    }

    fun seqCap(): Int = SEQ_CAP

    private fun stateFreqN(id: String, stateUsage: Map<String, Int>?): Float {
        if (stateUsage == null) return 0f
        val freq = stateUsage[id] ?: 0
        val maxFreq = stateUsage.values.maxOrNull() ?: 0
        if (freq <= 0 || maxFreq <= 0) return 0f
        return freq.toFloat() / maxFreq
    }

    private fun successorN(id: String, stateSeq: List<String>, anchor: List<String>): Float {
        if (anchor.isEmpty() || stateSeq.size < 2) return 0f
        val maxOrder = minOf(3, anchor.size)
        for (order in maxOrder downTo 1) {
            val suffix = anchor.takeLast(order)
            val result = orderScore(id, stateSeq, suffix)
            if (result > 0f) return result
        }
        return 0f
    }

    private fun orderScore(id: String, stateSeq: List<String>, suffix: List<String>): Float {
        val order = suffix.size
        var total = 0
        var match = 0
        for (i in 0..stateSeq.size - order - 1) {
            if (stateSeq.subList(i, i + order) == suffix) {
                total++
                if (stateSeq[i + order] == id) match++
            }
        }
        if (total < MIN_TRANSITIONS) return 0f
        val raw = (match + 1f) / (total + 2f)
        val conf = total.toFloat() / (total + SUCC_CONF_K)
        return raw * conf
    }

    private fun globalN(id: String, usage: Map<String, Map<String, Int>>): Float {
        val total = usage.values.sumOf { it[id] ?: 0 }
        if (total <= 0) return 0f
        val maxTotal = usage.values.maxOfOrNull { inner -> inner.values.sum() } ?: 0
        if (maxTotal <= 0) return 0f
        return total.toFloat() / maxTotal
    }

    private fun recencyN(lastUsed: Long?, now: Long): Float {
        if (lastUsed == null || lastUsed <= 0) return 0f
        val delta = now - lastUsed
        if (delta < 0) return 0f
        return exp(-delta / HALF_LIFE_MS.toFloat()).coerceIn(0f, 1f)
    }
}
package com.workspace.proot

import kotlin.math.exp

object CommandRecommender {

    private const val W_FREQ = 1.0f
    private const val W_SEQ = 2.0f
    private const val W_RECENT = 0.8f
    private const val SEQ_CAP = 24
    private const val HALF_LIFE_MS = 7L * 24 * 60 * 60 * 1000

    fun score(
        id: String,
        stateUsage: Map<String, Int>?,
        seq: List<String>,
        lastUsed: Map<String, Long>,
        now: Long
    ): Float {
        val freq = stateUsage?.get(id) ?: 0
        val maxFreq = stateUsage?.values?.maxOrNull() ?: 0
        val freqN = if (maxFreq > 0) freq.toFloat() / maxFreq else 0f

        val seqN = seqScore(id, seq)

        val recencyN = lastUsed[id]?.let { t ->
            if (t > 0) exp(-(now - t) / HALF_LIFE_MS.toFloat()) else 0f
        } ?: 0f

        return W_FREQ * freqN + W_SEQ * seqN + W_RECENT * recencyN
    }

    fun globalScore(
        id: String,
        usage: Map<String, Map<String, Int>>,
        lastUsed: Map<String, Long>,
        now: Long
    ): Float {
        val total = usage.values.sumOf { it[id] ?: 0 }
        val maxTotal = usage.values.maxOfOrNull { inner ->
            inner.values.sum()
        } ?: 0
        val freqN = if (maxTotal > 0) total.toFloat() / maxTotal else 0f

        val recencyN = lastUsed[id]?.let { t ->
            if (t > 0) exp(-(now - t) / HALF_LIFE_MS.toFloat()) else 0f
        } ?: 0f

        return W_FREQ * freqN + W_RECENT * recencyN
    }

    fun seqCap(): Int = SEQ_CAP

    private fun seqScore(id: String, seq: List<String>): Float {
        if (seq.size < 2) return 0f
        val last = seq.last()
        var match = 0
        var total = 0
        for (i in 0 until seq.size - 1) {
            if (seq[i] != last) continue
            total++
            if (seq[i + 1] == id) match++
        }
        if (total == 0) return 0f
        return match.toFloat() / total
    }
}

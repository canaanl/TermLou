package com.workspace.proot

/**
 * 流量行为分类器（纯计算，无 Android 依赖，可单测）。
 *
 * 只基于元数据（字节/时长/方向比值/端口）判断，不做任何内容解析；
 * 精度优先：宁可「其他」（不返回标签）也不错标，模糊时才并出两个标签 + 置信度。
 * 规则与阈值见下方常量（命名即文档）。
 */
object FlowClassifier {

    enum class Tag { INTERACT, MEDIA, HEARTBEAT, UPLOAD, AD }

    data class TagScore(val tag: Tag, val confidence: Int)

    data class FlowSample(
        val bytesUp: Long,
        val bytesDown: Long,
        val startedMs: Long,
        val durMs: Long?,
        val nowMs: Long,
        val state: String,
        val port: Int,
        val adHit: Boolean
    )

    private const val HEARTBEAT_MIN_DURATION_MS = 60_000L
    private const val HEARTBEAT_MAX_TOTAL = 4_096L
    private const val INTERACT_MAX_DURATION_MS = 30_000L
    private const val INTERACT_MAX_TOTAL = 65_536L
    private const val UPLOAD_MIN_UP = 32_768L
    private const val UPLOAD_RATIO = 4.0
    private const val UPLOAD_STRONG_RATIO = 8.0
    private const val MEDIA_MIN_TOTAL = 262_144L
    private const val MEDIA_MID_MULTIPLIER = 4L
    private const val MEDIA_HIGH_MULTIPLIER = 40L
    private const val BALANCED_RATIO_MIN = 0.25f
    private const val BALANCED_RATIO_MAX = 4f
    private const val NO_UPLOAD_RATIO = 999f
    private const val AMBIGUOUS_GAP = 15
    private const val PORT_DNS = 53
    private const val PORT_MDNS = 5353

    private const val AD_CONF = 95
    private const val HEARTBEAT_CONF = 92
    private const val INTERACT_CONF_BALANCED = 84
    private const val INTERACT_CONF_SKEWED = 70
    private const val UPLOAD_CONF_STRONG = 88
    private const val UPLOAD_CONF_MEDIUM = 74
    private const val MEDIA_CONF_LOW = 72
    private const val MEDIA_CONF_MID = 84
    private const val MEDIA_CONF_HIGH = 92

    fun classify(s: FlowSample): List<TagScore> {
        val total = s.bytesUp + s.bytesDown
        val dnsPort = s.port == PORT_DNS || s.port == PORT_MDNS
        if (total <= 0L || s.state == "BLOCKED" || dnsPort) return emptyList()
        val duration = s.durMs ?: (s.nowMs - s.startedMs).coerceAtLeast(0L)

        val scores = ArrayList<TagScore>()
        if (s.adHit) scores.add(TagScore(Tag.AD, AD_CONF))
        heartbeatIf(duration, total)?.let { scores.add(it) }
        interactIf(duration, total, s.bytesUp, s.bytesDown)?.let { scores.add(it) }
        uploadIf(s.bytesUp, s.bytesDown)?.let { scores.add(it) }
        mediaIf(s.bytesUp, s.bytesDown, total)?.let { scores.add(it) }

        return if (scores.isEmpty()) {
            emptyList()
        } else {
            scores.sortByDescending { it.confidence }
            if (scores.size > 1 && scores[0].confidence - scores[1].confidence <= AMBIGUOUS_GAP) {
                scores.take(2)
            } else {
                listOf(scores[0])
            }
        }
    }

    private fun heartbeatIf(duration: Long, total: Long): TagScore? =
        if (duration >= HEARTBEAT_MIN_DURATION_MS && total < HEARTBEAT_MAX_TOTAL) {
            TagScore(Tag.HEARTBEAT, HEARTBEAT_CONF)
        } else {
            null
        }

    private fun interactIf(duration: Long, total: Long, bytesUp: Long, bytesDown: Long): TagScore? {
        val ratio = if (bytesUp <= 0L) NO_UPLOAD_RATIO else bytesDown.toFloat() / bytesUp
        val interact = duration <= INTERACT_MAX_DURATION_MS && total < INTERACT_MAX_TOTAL
        val balanced = ratio in BALANCED_RATIO_MIN..BALANCED_RATIO_MAX
        return if (interact) {
            TagScore(Tag.INTERACT, if (balanced) INTERACT_CONF_BALANCED else INTERACT_CONF_SKEWED)
        } else {
            null
        }
    }

    private fun uploadIf(bytesUp: Long, bytesDown: Long): TagScore? {
        val bigUp = bytesUp >= UPLOAD_MIN_UP
        val ratio = bytesUp.toDouble() / bytesDown.coerceAtLeast(1L)
        return when {
            bigUp && ratio >= UPLOAD_STRONG_RATIO -> TagScore(Tag.UPLOAD, UPLOAD_CONF_STRONG)
            bigUp && ratio >= UPLOAD_RATIO -> TagScore(Tag.UPLOAD, UPLOAD_CONF_MEDIUM)
            else -> null
        }
    }

    private fun mediaIf(bytesUp: Long, bytesDown: Long, total: Long): TagScore? {
        val media = bytesDown >= bytesUp && total >= MEDIA_MIN_TOTAL
        return if (media) {
            val conf = when {
                total >= MEDIA_MIN_TOTAL * MEDIA_HIGH_MULTIPLIER -> MEDIA_CONF_HIGH
                total >= MEDIA_MIN_TOTAL * MEDIA_MID_MULTIPLIER -> MEDIA_CONF_MID
                else -> MEDIA_CONF_LOW
            }
            TagScore(Tag.MEDIA, conf)
        } else {
            null
        }
    }
}

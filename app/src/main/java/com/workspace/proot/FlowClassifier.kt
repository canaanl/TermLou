package com.workspace.proot

/**
 * 连接行为分类器（纯计算，无 Android 依赖，可单测）。
 *
 * 只做"读数就能坐实"的分类：方向（上行/下行字节）、体积、时长、端口全部直接计量，
 * 不做任何内容解析与意图推断。无法确切归类的一律返回 OTHER（兜底），绝不硬猜。
 * 每条非屏蔽连接恒返回一个标签（全覆盖）。
 */
object FlowClassifier {

    enum class Tag { MEDIA, UPLOAD, HEARTBEAT, DNS, OTHER }

    data class FlowSample(
        val bytesUp: Long,
        val bytesDown: Long,
        val startedMs: Long,
        val durMs: Long?,
        val nowMs: Long,
        val state: String,
        val port: Int
    )

    private const val MEDIA_MIN_TOTAL = 262_144L
    private const val UPLOAD_MIN_UP = 65_536L
    private const val UPLOAD_RATIO = 4.0
    private const val HEARTBEAT_MIN_DURATION_MS = 60_000L
    private const val HEARTBEAT_MAX_TOTAL = 4_096L
    private const val PORT_DNS = 53
    private const val PORT_MDNS = 5353

    fun classify(s: FlowSample): Tag {
        if (s.port == PORT_DNS || s.port == PORT_MDNS) return Tag.DNS
        val total = s.bytesUp + s.bytesDown
        val duration = s.durMs ?: (s.nowMs - s.startedMs).coerceAtLeast(0L)
        return when {
            duration >= HEARTBEAT_MIN_DURATION_MS && total < HEARTBEAT_MAX_TOTAL -> Tag.HEARTBEAT
            s.bytesDown >= s.bytesUp && total >= MEDIA_MIN_TOTAL -> Tag.MEDIA
            s.bytesUp >= UPLOAD_MIN_UP &&
                s.bytesUp >= s.bytesDown.coerceAtLeast(1L) * UPLOAD_RATIO -> Tag.UPLOAD
            else -> Tag.OTHER
        }
    }
}

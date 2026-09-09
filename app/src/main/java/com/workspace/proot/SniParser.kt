package com.workspace.proot

/**
 * TLS / QUIC SNI 提取（纯函数，可单测）。
 *
 *  - [tcpClientHello]: 解析 TLS record 前缀中首个 ClientHello 的 server_name 扩展
 *  - [quicClientHello]: 解析 QUIC Initial 长头包中的首个 CRYPTO 帧 → TLS ClientHello → SNI
 *  - [TlsSniffer]:      TCP 分片重组用小缓冲（ClientHello 首 record 通常 < 1KB，双缓冲足够）
 */
object SniParser {

    private const val RECORD_HANDSHAKE = 0x16
    private const val HANDSHAKE_TYPE_CLIENT_HELLO = 0x01
    private const val EXT_SERVER_NAME = 0x0000
    private const val NAME_TYPE_HOST = 0x00
    private const val QUIC_FRAME_CRYPTO = 0x06
    private const val QUIC_INITIAL_TYPE_MASK = 0x30

    /** TCP：从 TLS record 前缀提取 SNI；数据不足或非 TLS ClientHello 时返回 null。 */
    fun tcpClientHello(bytes: ByteArray): String? {
        val n = bytes.size
        if (n < 5) return null
        if ((bytes[0].toInt() and 0xFF) != RECORD_HANDSHAKE) return null
        val recordLen = u16(bytes, 3)
        val recordEnd = 5 + recordLen
        if (n < recordEnd) return null
        return handshakeMessageSni(bytes, 5, recordEnd)
    }

    /** QUIC：从 UDP 载荷（Initial 长头包）提取 SNI；非 Initial / 数据不足时返回 null。 */
    fun quicClientHello(bytes: ByteArray): String? {
        if (bytes.size < 4) return null
        val b0 = bytes[0].toInt() and 0xFF
        val longHeader = (b0 and 0x80) != 0 && (b0 and 0x40) != 0
        if (!longHeader) return null
        if ((b0 and QUIC_INITIAL_TYPE_MASK) != 0x00) return null
        var off = 1
        if (u32(bytes, off) == 0L) return null
        off += 4
        off = skipVarintPrefixed(bytes, off) ?: return null
        off = skipVarintPrefixed(bytes, off) ?: return null
        off = skipVarintPrefixed(bytes, off) ?: return null
        val pktLen = varint(bytes, off) ?: return null
        off = pktLen.second
        val pktEnd = off + pktLen.first.toInt()
        if (pktEnd > bytes.size) return null
        if (off + 1 > pktEnd) return null
        val pnLen = ((b0 ushr 2) and 0x03) + 1
        off += pnLen
        if (off >= pktEnd) return null
        val crypto = bytes[off].toInt() and 0xFF
        if (crypto != QUIC_FRAME_CRYPTO) return null
        off += 1
        val offsetVar = varint(bytes, off) ?: return null
        if (offsetVar.first != 0L) return null
        val lenVar = varint(bytes, offsetVar.second) ?: return null
        off = lenVar.second
        val cryptoEnd = off + lenVar.first.toInt()
        if (cryptoEnd > pktEnd) return null
        return handshakeMessageSni(bytes, off, cryptoEnd)
    }

    /**
     * 从握手消息头（type + u24 len + 消息体）解析 ClientHello → server_name。
     * [head] 指向 handshake type 字节，[end] 为该 record/帧的边界。
     */
    private fun handshakeMessageSni(b: ByteArray, head: Int, end: Int): String? {
        if (head + 4 > end) return null
        if ((b[head].toInt() and 0xFF) != HANDSHAKE_TYPE_CLIENT_HELLO) return null
        val msgLen = u24(b, head + 1)
        val msgEnd = head + 4 + msgLen
        if (msgEnd > end) return null
        var off = head + 4
        off += 34
        if (off + 1 > msgEnd) return null
        val sidLen = b[off].toInt() and 0xFF
        off += 1 + sidLen
        if (off + 2 > msgEnd) return null
        val csLen = u16(b, off)
        off += 2 + csLen
        if (off + 1 > msgEnd) return null
        val cmLen = b[off].toInt() and 0xFF
        off += 1 + cmLen
        if (off + 2 > msgEnd) return null
        val extCount = u16(b, off)
        off += 2
        repeat(extCount) {
            if (off + 4 > msgEnd) return null
            val type = u16(b, off)
            val extLen = u16(b, off + 2)
            off += 4
            if (off + extLen > msgEnd) return null
            if (type == EXT_SERVER_NAME) {
                if (extLen < 2) return null
                val listLen = u16(b, off)
                var p = off + 2
                val listEnd = p + listLen
                if (listEnd > off + extLen) return null
                while (p + 3 <= listEnd) {
                    val nameType = b[p].toInt() and 0xFF
                    val hostLen = u16(b, p + 1)
                    p += 3
                    if (p + hostLen > listEnd) return null
                    if (nameType == NAME_TYPE_HOST) {
                        val host = String(b, p, hostLen, Charsets.UTF_8).lowercase()
                        if (host.isNotEmpty()) return host
                    }
                    p += hostLen
                }
                return null
            }
            off += extLen
        }
        return null
    }

    private fun skipVarintPrefixed(b: ByteArray, off: Int): Int? {
        val v = varint(b, off) ?: return null
        val next = v.second + v.first.toInt()
        if (next > b.size) return null
        return next
    }

    private fun varint(b: ByteArray, off: Int): Pair<Long, Int>? {
        if (off >= b.size) return null
        val b0 = b[off].toInt() and 0xFF
        return when (b0 ushr 6) {
            0 -> Pair((b0 and 0x3F).toLong(), off + 1)
            1 -> {
                if (off + 1 >= b.size) return null
                Pair((((b0 and 0x3F) shl 8) or (b[off + 1].toInt() and 0xFF)).toLong(), off + 2)
            }
            2 -> {
                if (off + 3 >= b.size) return null
                var v = (b0 and 0x3F).toLong()
                for (k in 1..3) v = (v shl 8) or (b[off + k].toInt() and 0xFF).toLong()
                Pair(v, off + 4)
            }
            else -> {
                if (off + 7 >= b.size) return null
                var v = (b0 and 0x3F).toLong()
                for (k in 1..7) v = (v shl 8) or (b[off + k].toInt() and 0xFF).toLong()
                Pair(v, off + 8)
            }
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)

    private fun u24(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 16) or
            ((b[off + 1].toInt() and 0xFF) shl 8) or
            (b[off + 2].toInt() and 0xFF)

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)
}

/** TCP 首个方向的小缓冲嗅探器：拼接分片直到能解析出 ClientHello（或放弃）。 */
class TlsSniffer {

    private val buf = ByteArray(MAX_BUF)
    private var len = 0
    private var done = false

    fun feed(chunk: ByteArray, n: Int): String? {
        if (done || n <= 0) return null
        if (len == 0 && (chunk[0].toInt() and 0xFF) != RECORD_HANDSHAKE) {
            done = true
            return null
        }
        if (len >= MAX_BUF) return null
        val take = minOf(n, MAX_BUF - len)
        System.arraycopy(chunk, 0, buf, len, take)
        len += take
        val r = SniParser.tcpClientHello(buf.copyOfRange(0, len))
        if (r != null) done = true
        return r
    }

    private companion object {
        const val RECORD_HANDSHAKE = 0x16
        const val MAX_BUF = 16389
    }
}

package com.workspace.proot

import java.net.InetAddress

/**
 * 极简 DNS 报文解析（纯函数，可单测）：
 *  - queryName: 解析查询报文的首个 QNAME（域名）
 *  - responseMappings: 解析应答报文中的 A/AAAA 记录 → (域名, IP) 列表，支持压缩名
 */
object DnsParser {

    fun queryName(payload: ByteArray): String? {
        if (payload.size < 12) return null
        val qr = (payload[2].toInt() shr 7) and 1
        if (qr != 0) return null
        val qd = u16(payload, 4)
        if (qd == 0) return null
        var off = 12
        var name = ""
        var guard = 0
        while (true) {
            if (guard++ > 64 || off >= payload.size) return null
            val len = payload[off].toInt() and 0xFF
            if (len == 0) {
                off += 1
                break
            }
            if (len > 63) return null
            off += 1
            if (off + len > payload.size) return null
            val label = String(payload, off, len, Charsets.UTF_8)
            name = if (name.isEmpty()) label else "$name.$label"
            off += len
        }
        return if (name.isEmpty()) null else name.lowercase()
    }

    fun responseMappings(payload: ByteArray): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        if (payload.size < 12) return out
        val qr = (payload[2].toInt() shr 7) and 1
        if (qr != 1) return out
        val qd = u16(payload, 4)
        val an = u16(payload, 6)
        if (an == 0) return out
        var off = 12
        for (i in 0 until qd) {
            val r = skipName(payload, off) ?: return out
            off = r + 4
            if (off > payload.size) return out
        }
        for (i in 0 until an) {
            val r = readName(payload, off) ?: return out
            val name = r.first
            off = r.second
            if (off + 10 > payload.size) return out
            val type = u16(payload, off)
            off += 8
            val rdlen = u16(payload, off)
            off += 2
            if (off + rdlen > payload.size) return out
            if ((type == 1 && rdlen == 4) || (type == 28 && rdlen == 16)) {
                val ip = InetAddress.getByAddress(payload.copyOfRange(off, off + rdlen)).hostAddress ?: ""
                if (ip.isNotEmpty()) out.add(Pair(name, ip))
            }
            off += rdlen
        }
        return out
    }

    private fun readName(payload: ByteArray, start: Int): Pair<String, Int>? {
        val labels = mutableListOf<String>()
        var off = start
        var jumped = false
        var next = start
        val visited = HashSet<Int>()
        var guard = 0
        while (true) {
            if (guard++ > 128 || off >= payload.size) return null
            val len = payload[off].toInt() and 0xFF
            when {
                len == 0 -> {
                    if (!jumped) next = off + 1
                    return Pair(labels.joinToString(".").lowercase(), next)
                }
                (len and 0xC0) == 0xC0 -> {
                    if (off + 1 >= payload.size) return null
                    val ptr = ((len and 0x3F) shl 8) or (payload[off + 1].toInt() and 0xFF)
                    if (!jumped) next = off + 2
                    if (ptr >= payload.size || !visited.add(ptr)) return null
                    off = ptr
                    jumped = true
                }
                (len and 0xC0) != 0 -> return null
                else -> {
                    if (off + 1 + len > payload.size) return null
                    val label = String(payload, off + 1, len, Charsets.UTF_8)
                    labels.add(label)
                    off += 1 + len
                }
            }
        }
    }

    private fun skipName(payload: ByteArray, start: Int): Int? {
        var off = start
        var guard = 0
        while (true) {
            if (guard++ > 128 || off >= payload.size) return null
            val len = payload[off].toInt() and 0xFF
            when {
                len == 0 -> return off + 1
                (len and 0xC0) == 0xC0 -> return off + 2
                (len and 0xC0) != 0 -> return null
                else -> off += 1 + len
            }
        }
    }

    private fun u16(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 8) or (b[off + 1].toInt() and 0xFF)
}

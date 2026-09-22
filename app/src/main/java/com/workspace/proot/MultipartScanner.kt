package com.workspace.proot

import java.io.File
import java.io.RandomAccessFile

/**
 * multipart/form-data 分段扫描器：分块顺序扫描，只用固定缓冲，
 * 不把整个请求体读进堆（旧实现 spool.readBytes() 最大 64MB，
 * 32 并发最坏可占 2GB 堆内存直接 OOM）。
 *
 * 纯 JVM、无 Android 依赖，可直接单测。
 */
object MultipartScanner {

    /** 一个表单分段：头区间 [headerStart, headerEnd)、正文区间 [bodyStart, bodyEnd)。 */
    data class Part(
        val headerStart: Int,
        val headerEnd: Int,
        val bodyStart: Int,
        val bodyEnd: Int
    )

    private const val CHUNK = 512 * 1024
    /** 单个分段头部扫描上限（与请求头上限一致），防异常头部撑爆内存。 */
    private const val MAX_HEADER_SCAN = 32 * 1024
    private val CRLF2 = byteArrayOf(13, 10, 13, 10)

    private data class Marker(val pos: Long, val terminator: Boolean)

    /**
     * 扫描 [spool]，返回可按区间取用的分段列表。
     * 结构异常 / 无分段 / 首标记即结束符时返回空列表。
     */
    fun scan(spool: File, boundary: ByteArray): List<Part> {
        if (boundary.isEmpty() || !spool.isFile) return emptyList()
        val markers = findMarkers(spool, boundary)
        if (markers.isEmpty() || markers[0].terminator) return emptyList()

        val parts = ArrayList<Part>()
        RandomAccessFile(spool, "r").use { raf ->
            for (i in markers.indices) {
                val m = markers[i]
                if (m.terminator) break
                val next = markers.getOrNull(i + 1) ?: break
                if (next.pos <= m.pos) continue
                // 头区间：boundary 行起到空行（CRLFCRLF）为止，扫描长度封顶
                val headerLimit = minOf(next.pos, m.pos + MAX_HEADER_SCAN)
                val headerLen = (headerLimit - m.pos).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
                if (headerLen <= 0) continue
                val header = readRegion(raf, m.pos, headerLen)
                val he = indexOfSeq(header, CRLF2, 0)
                if (he < 0) continue
                val bodyStart = m.pos + he + 4
                val bodyEnd = next.pos - 2
                if (bodyEnd < bodyStart) continue
                if (bodyStart > Int.MAX_VALUE || bodyEnd > Int.MAX_VALUE) continue
                parts.add(Part(m.pos.toInt(), (m.pos + he).toInt(), bodyStart.toInt(), bodyEnd.toInt()))
            }
        }
        return parts
    }

    /**
     * 分块找 boundary 标记（含结束符 `boundary--`）。
     * 关键点：末尾未确认的命中要连同前缀留到下一块重扫（跨块匹配），
     * 已确认的命中不能重复入列。
     */
    private fun findMarkers(spool: File, boundary: ByteArray): List<Marker> {
        val term = ByteArray(boundary.size + 2) { i -> if (i < boundary.size) boundary[i] else 0x2D }
        val maxMarker = term.size                    // boundary + "--"
        val carryLen = maxMarker - 1                 // 至少保留 maxMarker-1 字节才能补全命中
        val out = ArrayList<Marker>()
        RandomAccessFile(spool, "r").use { raf ->
            val chunk = ByteArray(CHUNK)
            var carry = ByteArray(0)
            var base = 0L                            // carry[0] 对应的文件偏移
            while (true) {
                val n = raf.read(chunk)
                val eof = n <= 0
                if (eof && carry.isEmpty()) break
                val window: ByteArray = when {
                    carry.isEmpty() -> chunk.copyOfRange(0, n)
                    eof -> carry
                    else -> {
                        val w = ByteArray(carry.size + n)
                        System.arraycopy(carry, 0, w, 0, carry.size)
                        System.arraycopy(chunk, 0, w, carry.size, n)
                        w
                    }
                }

                var scanFrom = 0
                var lastAccepted = -1
                while (true) {
                    val hit = indexOfSeq(window, boundary, scanFrom)
                    if (hit < 0) break
                    val room = hit + maxMarker <= window.size
                    if (!room && !eof) break         // 尾部未确认：留到下一块
                    val isTerm = room && matches(window, hit, term)
                    out.add(Marker(base + hit, isTerm))
                    scanFrom = hit + boundary.size
                    lastAccepted = hit
                }

                if (eof) break
                val keepFrom = maxOf(
                    window.size - carryLen,
                    if (lastAccepted >= 0) lastAccepted + boundary.size else 0
                ).coerceIn(0, window.size)
                carry = window.copyOfRange(keepFrom, window.size)
                base += keepFrom
            }
        }
        return out
    }

    private fun readRegion(raf: RandomAccessFile, start: Long, len: Int): ByteArray {
        val b = ByteArray(len)
        raf.seek(start)
        var off = 0
        while (off < len) {
            val n = raf.read(b, off, len - off)
            if (n <= 0) break
            off += n
        }
        return if (off == len) b else b.copyOf(off)
    }

    private fun matches(buf: ByteArray, from: Int, seq: ByteArray): Boolean {
        if (from + seq.size > buf.size) return false
        for (j in seq.indices) if (buf[from + j] != seq[j]) return false
        return true
    }

    private fun indexOfSeq(buf: ByteArray, seq: ByteArray, from: Int): Int {
        if (seq.isEmpty() || buf.size < seq.size) return -1
        val last = buf.size - seq.size
        var i = from.coerceAtLeast(0)
        while (i <= last) {
            var j = 0
            while (j < seq.size && buf[i + j] == seq[j]) j++
            if (j == seq.size) return i
            i++
        }
        return -1
    }
}

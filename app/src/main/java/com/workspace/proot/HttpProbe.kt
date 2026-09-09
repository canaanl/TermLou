package com.workspace.proot

import java.util.Locale

/**
 * 极简明文 HTTP 嗅探（纯函数，可单测）。
 * 仅在明文端口的首包上识别请求行 / 状态行，识别失败返回 null（视为非 HTTP）。
 * 设计约束：只取首包首行，不做任何状态机与协议解析，开销为 O(首行长度)。
 */
object HttpProbe {

    private val METHODS = setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS", "CONNECT", "TRACE")
    private const val MAX_LINE = 8192

    fun plaintextPort(port: Int): Boolean = port == 80 || port == 8080 || port == 8000

    /** 首包 → "METHOD path"（如 "GET /api/search"）。 */
    fun requestLine(chunk: ByteArray): String? {
        val line = firstLine(chunk) ?: return null
        val sp = line.indexOf(' ')
        if (sp <= 0) return null
        val method = line.substring(0, sp)
        if (method.uppercase(Locale.ROOT) !in METHODS) return null
        val target = line.substring(sp + 1).substringBefore(' ')
        if (target.isEmpty()) return null
        return "$method $target"
    }

    /** 首包 → 三位状态码（如 "200"）。 */
    fun statusLine(chunk: ByteArray): String? {
        val line = firstLine(chunk) ?: return null
        if (!line.startsWith("HTTP/1.")) return null
        val sp = line.indexOf(' ')
        if (sp < 0 || sp + 4 > line.length) return null
        val code = line.substring(sp + 1, sp + 4)
        if (!code.all { it.isDigit() }) return null
        return code
    }

    private fun firstLine(bytes: ByteArray): String? {
        var end = 0
        while (end < bytes.size && end < MAX_LINE) {
            val c = bytes[end].toInt() and 0xFF
            if (c == 0x0A || c == 0x0D) break
            end++
        }
        if (end == 0) return null
        val line = runCatching { String(bytes, 0, end, Charsets.US_ASCII) }.getOrNull() ?: return null
        if (line.isEmpty()) return null
        for (ch in line) if (ch.code < 0x20 || ch.code > 0x7E) return null
        return line
    }
}

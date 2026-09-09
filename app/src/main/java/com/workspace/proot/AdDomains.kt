package com.workspace.proot

import android.content.Context
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 已知广告/追踪域名集合。
 * 命中即「广告」标签依据（名单查询，非内容推测）。
 * 内置 asset 快照保底 + refresh() 多源合并增强：任一源失败静默保留旧名单，不阻塞抓包。
 * 名单来源与许可见 assets/ad_domains.txt 头部注释。
 */
object AdDomains {

    private val base = HashSet<String>()
    private val domains = HashSet<String>()
    @Volatile private var loaded = false

    private const val ASSET_NAME = "ad_domains.txt"
    private const val FILE_NAME = "ad_domains.txt"
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000

    private val sources = listOf(
        "https://pgl.yoyo.org/adservers/plain?mimetype=plaintext",
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/hosts"
    )

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(domains) {
            if (loaded) return
            try {
                loadFromAsset(context)
            } finally {
                loaded = true
            }
        }
    }

    fun size(): Int = synchronized(domains) { domains.size }

    fun adOf(domain: String?): String? {
        val d = domain?.let { normalize(it) } ?: return null
        var cur = d
        while (cur.contains('.') && !domains.contains(cur)) {
            cur = cur.substring(cur.indexOf('.') + 1)
        }
        return if (domains.contains(cur)) cur else null
    }

    sealed class Result {
        data class Success(val count: Int) : Result()
        object Failure : Result()
    }

    fun refresh(context: Context, onDone: (Result) -> Unit) {
        Thread {
            val merged = HashSet<String>()
            var fetchedAny = false
            for (url in sources) {
                try {
                    val parsed = parse(fetch(url))
                    if (parsed.isNotEmpty()) {
                        merged.addAll(parsed)
                        fetchedAny = true
                    }
                } catch (_: Exception) {
                }
            }
            if (!fetchedAny) {
                onDone(Result.Failure)
                return@Thread
            }
            synchronized(domains) {
                val file = domainFile(context)
                file.parentFile?.mkdirs()
                file.writeText(merged.sorted().joinToString("\n"))
                loadFileIntoDomains(file)
            }
            onDone(Result.Success(merged.size))
        }.start()
    }

    private fun loadFromAsset(context: Context) {
        domains.clear()
        try {
            context.assets.open(ASSET_NAME).use { ins ->
                base.addAll(parse(ins.reader().readText()))
            }
        } catch (_: Exception) {
        }
        domains.addAll(base)
        val file = domainFile(context)
        if (file.exists()) loadFileIntoDomains(file)
    }

    private fun loadFileIntoDomains(file: File) {
        try {
            domains.addAll(parse(file.readText()))
        } catch (_: Exception) {
        }
    }

    private fun domainFile(context: Context): File = File(context.filesDir, FILE_NAME)

    private fun normalize(domain: String): String? {
        val t = domain.trim().lowercase().trimStart('*', '.').removeSuffix(".")
        val valid = t.isNotEmpty() && t.contains('.') && t.all { ch -> ch in 'a'..'z' || ch == '.' || ch == '-' }
        return if (valid) t else null
    }

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.setRequestProperty("User-Agent", "TermLou/4.7")
            conn.inputStream.reader(Charsets.UTF_8).use { return it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun parse(text: String): Set<String> {
        val body = if (text.startsWith("\uFEFF")) text.substring(1) else text
        val out = HashSet<String>()
        for (line in body.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty() || t.startsWith("#")) continue
            val token = t
                .removePrefix("0.0.0.0 ")
                .removePrefix("127.0.0.1 ")
                .removePrefix("::1 ")
                .trim()
            normalize(token)?.let { out.add(it) }
        }
        return out
    }
}

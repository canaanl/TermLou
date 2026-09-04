package com.workspace.proot

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * LAN 共享服务器：同端口兼容器 HTTP（xterm.html / 文件浏览 / 登录）与
 * WebSocket（透传独立 pty）。仅 LAN 开关开启时 bind，关即停。
 */
class WsServer(
    private val context: Context,
    val token: String,
    private val lanUser: String,
    private val lanPass: String,
    private val onClientsChanged: (Int) -> Unit = {}
) : Thread("ws-lan") {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private var pool: ThreadPoolExecutor? = null
    private val clients = AtomicInteger(0)
    private val sessions = ConcurrentHashMap<String, WsSession>()
    var boundPort: Int = 0
        private set

    fun startListening(preferredPort: Int): Int {
        var port = preferredPort
        var s: ServerSocket? = null
        var lastErr: Exception? = null
        for (i in 0 until 20) {
            try {
                val cand = ServerSocket()
                cand.reuseAddress = true
                cand.bind(InetSocketAddress("0.0.0.0", port + i))
                s = cand
                port += i
                break
            } catch (e: Exception) {
                lastErr = e
            }
        }
        val bound = s ?: throw IllegalStateException("端口 $preferredPort 起 20 个均被占用：${lastErr?.message}")
        server = bound
        boundPort = port
        running = true
        pool = ThreadPoolExecutor(
            8, 32, 60, TimeUnit.SECONDS, SynchronousQueue(),
            { r -> Thread(null, r, "ws-lan-worker", 256 * 1024).apply { isDaemon = true } }
        )
        start()
        return port
    }

    override fun run() {
        val s = server ?: return
        while (running) {
            try {
                val client = s.accept()
                pool?.execute { handleClient(client) }
            } catch (_: Exception) {
                if (!running) break
            }
        }
    }

    fun stopListening() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
        pool = null
        sessions.values.forEach { runCatching { it.close() } }
        sessions.clear()
        clients.set(0)
    }

    private fun handleClient(client: Socket) {
        try {
            client.soTimeout = 30_000
            val input = client.getInputStream()
            val head = readHeaders(input) ?: run { client.close(); return }
            val headerEnd = indexOfHeaderEnd(head)
            if (headerEnd < 0) { client.close(); return }
            val headerText = String(head, 0, headerEnd, Charsets.ISO_8859_1)
            val lines = headerText.split("\r\n")
            val reqLine = lines.firstOrNull()?.split(" ") ?: run { client.close(); return }
            if (reqLine.size < 2) { client.close(); return }
            val method = reqLine[0].uppercase()
            val target = reqLine[1]
            val headers = mutableMapOf<String, String>()
            for (i in 1 until lines.size) {
                val idx = lines[i].indexOf(':')
                if (idx > 0) headers[lines[i].substring(0, idx).trim().lowercase()] = lines[i].substring(idx + 1).trim()
            }
            val path = target.substringBefore('?')
            val query = parseQuery(target.substringAfter('?', ""))

            val upgrade = headers["upgrade"] ?: ""
            if (method == "GET" && upgrade.contains("websocket", true)) {
                val key = headers["sec-websocket-key"] ?: run { client.close(); return }
                if (!checkToken(query["token"] ?: headers["authorization"]?.removePrefix("Bearer ")?.trim())) {
                    writeText(client, 403, "Forbidden")
                    client.close()
                    return
                }
                doHandshake(client, key)
                client.soTimeout = 0
                val sess = WsSession(context, client, input)
                val id = System.nanoTime().toString() + "-" + clients.incrementAndGet()
                sessions[id] = sess
                onClientsChanged(clients.get())
                try {
                    sess.runLoop()
                } finally {
                    sessions.remove(id)
                    clients.decrementAndGet()
                    onClientsChanged(clients.get())
                    runCatching { client.close() }
                }
                return
            }

            when {
                method == "GET" && (path == "/" || path == "/index.html") -> {
                    val html = context.assets.open("xterm/index.html").use { it.readBytes() }
                    writeBytes(client, 200, "text/html; charset=utf-8", html)
                    client.close()
                }
                method == "GET" && path.startsWith("/static/") -> {
                    val name = path.removePrefix("/static/")
                    val mime = when {
                        name.endsWith(".js") -> "text/javascript; charset=utf-8"
                        name.endsWith(".css") -> "text/css; charset=utf-8"
                        else -> null
                    }
                    val ok = mime != null && (name == "xterm.js" || name == "xterm.css" || name == "addon-fit.js") && !name.contains("..")
                    if (!ok) { writeText(client, 404, "Not found"); client.close(); return }
                    val data = context.assets.open("xterm/$name").use { it.readBytes() }
                    writeBytes(client, 200, mime!!, data)
                    client.close()
                }
                method == "POST" && path == "/login" -> {
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = readBody(input, head, headerEnd + 4, len.coerceAtMost(4096))
                    val map = parseLoginBody(body, headers["content-type"] ?: "")
                    val ok = if (lanUser.isEmpty() && lanPass.isEmpty()) true
                    else map["user"] == lanUser && map["pass"] == lanPass
                    if (ok) writeText(client, 200, "{\"token\":\"$token\"}", "application/json")
                    else writeText(client, 403, "{\"error\":\"auth\"}", "application/json")
                    client.close()
                }
                method == "GET" && path == "/files" -> {
                    if (!checkToken(query["token"])) { writeText(client, 403, "Forbidden"); client.close(); return }
                    val rel = query["path"] ?: "/"
                    writeText(client, 200, listFilesJson(rel), "application/json")
                    client.close()
                }
                method == "GET" && path == "/download" -> {
                    if (!checkToken(query["token"])) { writeText(client, 403, "Forbidden"); client.close(); return }
                    val f = resolveWorkspaceFile(query["path"] ?: "")
                    if (f == null || !f.isFile) { writeText(client, 404, "Not found"); client.close(); return }
                    writeFile(client, f)
                    client.close()
                }
                method == "POST" && path == "/upload" -> {
                    if (!checkToken(query["token"])) { writeText(client, 403, "Forbidden"); client.close(); return }
                    val dir = resolveWorkspaceFile(query["path"] ?: "/")
                    val ctype = headers["content-type"] ?: ""
                    val len = headers["content-length"]?.toIntOrNull() ?: 0
                    if (dir == null || len <= 0 || len > 64 * 1024 * 1024) {
                        writeText(client, 400, "Bad request"); client.close(); return
                    }
                    val body = readBody(input, head, headerEnd + 4, len)
                    val saved = saveMultipart(dir, body, ctype)
                    if (saved) writeText(client, 200, "{\"ok\":true}", "application/json")
                    else writeText(client, 400, "{\"error\":\"upload\"}", "application/json")
                    client.close()
                }
                else -> {
                    writeText(client, 404, "Not found")
                    client.close()
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }

    private fun checkToken(t: String?): Boolean {
        if (t == null) return false
        return t == token
    }

    private fun doHandshake(client: Socket, key: String) {
        val accept = Base64.encodeToString(
            MessageDigest.getInstance("SHA-1")
                .digest((key.trim() + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray(Charsets.ISO_8859_1)),
            Base64.NO_WRAP
        )
        val resp = "HTTP/1.1 101 Switching Protocols\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Accept: $accept\r\n\r\n"
        client.getOutputStream().write(resp.toByteArray(Charsets.ISO_8859_1))
        client.getOutputStream().flush()
    }

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        return q.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i < 0) null else urlDecode(it.substring(0, i)) to urlDecode(it.substring(i + 1))
        }.toMap()
    }

    private fun urlDecode(s: String): String = runCatching {
        java.net.URLDecoder.decode(s, "UTF-8")
    }.getOrDefault(s)

    private fun parseLoginBody(body: ByteArray, contentType: String): Map<String, String> {
        val text = String(body, Charsets.UTF_8)
        if (contentType.contains("application/json")) {
            val m = mutableMapOf<String, String>()
            Regex("\"(user|pass)\"\\s*:\\s*\"([^\"]*)\"").findAll(text).forEach {
                m[it.groupValues[1]] = it.groupValues[2]
            }
            return m
        }
        return parseQuery(text)
    }

    private fun workspaceRoot(): File = File(context.filesDir, "workspace")

    private fun resolveWorkspaceFile(rel: String): File? {
        return try {
            val root = workspaceRoot().canonicalFile
            val target = File(root, rel.trimStart('/')).canonicalFile
            if (target == root || target.path.startsWith(root.path + File.separator)) target else null
        } catch (_: Exception) { null }
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

    private fun listFilesJson(rel: String): String {
        val dir = resolveWorkspaceFile(if (rel.isEmpty()) "/" else rel) ?: return "{\"error\":\"bad path\"}"
        if (!dir.exists()) return "{\"error\":\"not found\"}"
        if (!dir.isDirectory) return "{\"error\":\"not dir\"}"
        val sb = StringBuilder("{\"path\":\"").append(esc(rel)).append("\",\"files\":[")
        val kids = dir.listFiles()?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        kids.take(500).forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":\"").append(esc(f.name)).append("\",\"dir\":").append(f.isDirectory)
            sb.append(",\"size\":").append(if (f.isFile) f.length() else 0).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun saveMultipart(dir: File, body: ByteArray, contentType: String): Boolean {
        return try {
            if (!dir.isDirectory) return false
            val bIdx = contentType.indexOf("boundary=")
            if (bIdx < 0) return false
            val boundary = "--" + contentType.substring(bIdx + 9).trim().trim('"')
            val text = String(body, Charsets.ISO_8859_1)
            var pos = text.indexOf(boundary)
            var saved = false
            while (pos >= 0) {
                val headEnd = text.indexOf("\r\n\r\n", pos)
                if (headEnd < 0) break
                val partHead = text.substring(pos, headEnd)
                val nameMatch = Regex("filename=\"([^\"]*)\"").find(partHead)
                val next = text.indexOf(boundary, headEnd + 4)
                if (nameMatch != null && next > 0) {
                    val fname = File(nameMatch.groupValues[1]).name
                    if (fname.isNotEmpty() && fname != "." && fname != "..") {
                        val start = headEnd + 4
                        var end = next - 2
                        if (end < start) end = start
                        val bytes = body.copyOfRange(start, end)
                        val target = resolveWorkspaceFile(
                            dir.relativeTo(workspaceRoot()).path + "/" + fname
                        ) ?: return false
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                        saved = true
                    }
                }
                pos = next
                if (text.indexOf(boundary + "--", pos.coerceAtLeast(0)) == pos) break
            }
            saved
        } catch (_: Exception) { false }
    }

    private data class Frame(val op: Int, val data: ByteArray)

    companion object {
        fun reportPtyError(ctx: Context, socket: Socket, e: Exception) {
            val msg = "pty 启动失败: ${e.javaClass.simpleName}: ${e.message}"
            runCatching {
                val dir = File(ctx.filesDir, "lan")
                dir.mkdirs()
                File(dir, "error.log").appendText(
                    "${java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} $msg\n${
                        e.stackTrace.take(8).joinToString("\n")
                    }\n"
                )
            }
            runCatching {
                val os = socket.getOutputStream()
                val data = "\r\n[TermLou] $msg\r\n".toByteArray(Charsets.UTF_8)
                os.write(0x80 or 0x1)
                if (data.size < 126) os.write(data.size)
                else {
                    os.write(126)
                    os.write((data.size shr 8) and 0xFF)
                    os.write(data.size and 0xFF)
                }
                os.write(data)
                os.flush()
                Thread.sleep(300)
            }
        }

        fun indexOfHeaderEnd(head: ByteArray): Int {
            for (i in 0 until head.size - 3) {
                if (head[i] == 13.toByte() && head[i + 1] == 10.toByte() &&
                    head[i + 2] == 13.toByte() && head[i + 3] == 10.toByte()
                ) return i
            }
            return -1
        }

        fun newToken(): String {
            val b = ByteArray(12)
            SecureRandom().nextBytes(b)
            return Base64.encodeToString(b, Base64.URL_SAFE or Base64.NO_WRAP)
        }

        private fun readHeaders(input: InputStream): ByteArray? {
            val out = ByteArrayOutputStream()
            val win = ByteArray(4)
            var n = 0
            var total = 0
            while (total < 32 * 1024) {
                val r = try { input.read() } catch (_: Exception) { -1 }
                if (r < 0) return null
                out.write(r)
                win[n % 4] = r.toByte()
                n++
                total++
                if (n >= 4 && win[(n - 4) % 4] == 13.toByte() && win[(n - 3) % 4] == 10.toByte() &&
                    win[(n - 2) % 4] == 13.toByte() && win[(n - 1) % 4] == 10.toByte()
                ) return out.toByteArray()
            }
            return null
        }

        private fun readBody(input: InputStream, head: ByteArray, bodyStart: Int, len: Int): ByteArray {
            val out = ByteArrayOutputStream(len.coerceAtLeast(0))
            if (bodyStart < head.size) out.write(head, bodyStart, head.size - bodyStart)
            var remain = len - (head.size - bodyStart).coerceAtLeast(0)
            val buf = ByteArray(8192)
            while (remain > 0) {
                val r = try { input.read(buf, 0, minOf(buf.size, remain)) } catch (_: Exception) { -1 }
                if (r <= 0) break
                out.write(buf, 0, r)
                remain -= r
            }
            return out.toByteArray()
        }

        private fun writeText(client: Socket, code: Int, body: String, mime: String = "text/plain; charset=utf-8") {
            writeBytes(client, code, mime, body.toByteArray(Charsets.UTF_8))
        }

        private fun writeBytes(client: Socket, code: Int, mime: String, body: ByteArray) {
            val status = when (code) {
                200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"
                else -> "OK"
            }
            val h = "HTTP/1.1 $code $status\r\nContent-Type: $mime\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
            val os = client.getOutputStream()
            os.write(h.toByteArray(Charsets.ISO_8859_1))
            os.write(body)
            os.flush()
        }

        private fun writeFile(client: Socket, f: File) {
            val ascii = f.name.replace(Regex("[^\\x20-\\x7E]"), "_").ifEmpty { "download" }
            val encoded = runCatching {
                java.net.URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
            }.getOrDefault(ascii)
            val h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"$ascii\"; filename*=UTF-8''$encoded\r\n" +
                "Content-Length: ${f.length()}\r\nConnection: close\r\n\r\n"
            val os = client.getOutputStream()
            os.write(h.toByteArray(Charsets.ISO_8859_1))
            f.inputStream().use { it.copyTo(os, 32768) }
            os.flush()
        }
    }

    /**
     * 单 WebSocket 会话：独立 proot pty，双向桥接。
     */
    inner class WsSession(
        private val ctx: Context,
        private val socket: Socket,
        private val input: InputStream
    ) {
        @Volatile private var closed = false
        private var masterFd: Int = -1
        private var pid: Int = 0
        private var pfd: ParcelFileDescriptor? = null
        private var reader: Thread? = null

        fun runLoop() {
            val lxRoot = File(ctx.filesDir, "workspace/linux")
            val wsFiles = File(ctx.filesDir, "workspace")
            val wsTmp = File(ctx.filesDir, "workspace/tmp")
            val tm = TerminalManager(ctx, lxRoot, wsFiles, wsTmp)
            val shell = tm.findShellInRootfs() ?: throw IllegalStateException("no shell")
            val prootBin = File(ctx.applicationInfo.nativeLibraryDir, "libproot_exec.so")
            val loader = File(ctx.applicationInfo.nativeLibraryDir, "libproot_loader.so")
            val args = tm.buildProotArgs(shell)
            val env = tm.buildProotEnv(loader)
            wsTmp.mkdirs()
            val pidOut = IntArray(1)
            try {
                masterFd = PtyJni.createSubprocess(
                    prootBin.absolutePath, wsFiles.absolutePath,
                    args.toTypedArray(), env, pidOut, 24, 80
                )
                if (masterFd < 0) throw IllegalStateException("pty fd=$masterFd")
                pid = pidOut[0]
                pfd = ParcelFileDescriptor.adoptFd(masterFd)
            } catch (e: Exception) {
                reportPtyError(ctx, socket, e)
                return
            }
            val pin = FileInputStream(pfd!!.fileDescriptor)
            val pout = FileOutputStream(pfd!!.fileDescriptor)

            reader = Thread(null, {
                val buf = ByteArray(8192)
                try {
                    while (!closed) {
                        val n = pin.read(buf)
                        if (n <= 0) break
                        sendBinary(buf.copyOf(n))
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                }
            }, "ws-lan-pty-reader", 256 * 1024).apply { isDaemon = true; start() }

            try {
                while (!closed) {
                    val frame = readFrame() ?: break
                    when (frame.op) {
                        0x8 -> break
                        0x9 -> sendPong(frame.data)
                        0xA -> {}
                        0x1 -> handleText(String(frame.data, Charsets.UTF_8), pout)
                        0x2 -> runCatching { pout.write(frame.data); pout.flush() }
                        else -> {}
                    }
                }
            } finally {
                close()
            }
        }

        private fun handleText(text: String, pout: FileOutputStream) {
            val t = text.trim()
            if (t.startsWith("{") && t.contains("resize")) {
                val cols = Regex("\"cols\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                val rows = Regex("\"rows\"\\s*:\\s*(\\d+)").find(t)?.groupValues?.get(1)?.toIntOrNull()
                if (cols != null && rows != null && masterFd >= 0) {
                    runCatching { PtyJni.setPtyWindowSize(masterFd, rows.coerceIn(1, 300), cols.coerceIn(1, 500)) }
                }
                return
            }
            runCatching { pout.write(frameBytes(text)); pout.flush() }
        }

        private fun frameBytes(s: String) = s.toByteArray(Charsets.UTF_8)

        fun close() {
            if (closed) return
            closed = true
            runCatching { pfd?.close() }
            runCatching { if (pid > 0) android.system.Os.kill(pid, 9) }
            runCatching { socket.close() }
        }

        private fun readFrame(): Frame? {
            val b1 = readByte() ?: return null
            val b2 = readByte() ?: return null
            val op = b1 and 0x0F
            val masked = (b2 and 0x80) != 0
            var len = (b2 and 0x7F).toLong()
            if (len == 126L) {
                val a = readByte() ?: return null
                val b = readByte() ?: return null
                len = ((a shl 8) or b).toLong()
            } else if (len == 127L) {
                len = 0
                repeat(8) { len = (len shl 8) or ((readByte() ?: return null).toLong() and 0xFF) }
            }
            if (len < 0 || len > 8 * 1024 * 1024) return null
            val mask = if (masked) ByteArray(4).also {
                for (i in 0 until 4) it[i] = (readByte() ?: return null).toByte()
            } else null
            val data = ByteArray(len.toInt())
            var off = 0
            while (off < data.size) {
                val r = try { input.read(data, off, data.size - off) } catch (_: Exception) { -1 }
                if (r <= 0) return null
                off += r
            }
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            if (op == 0x0) return readFrame()
            return Frame(op, data)
        }

        private fun readByte(): Int? = try {
            val r = input.read()
            if (r < 0) null else r
        } catch (_: Exception) { null }

        @Synchronized
        private fun sendBinary(data: ByteArray) {
            if (closed) return
            try {
                val os = socket.getOutputStream()
                writeFrame(os, 0x2, data)
            } catch (_: Exception) { close() }
        }

        private fun sendPong(data: ByteArray) {
            try { writeFrame(socket.getOutputStream(), 0xA, data) } catch (_: Exception) {}
        }

        private fun writeFrame(os: java.io.OutputStream, op: Int, data: ByteArray) {
            os.write(0x80 or op)
            when {
                data.size < 126 -> os.write(data.size)
                data.size < 65536 -> {
                    os.write(126)
                    os.write((data.size shr 8) and 0xFF)
                    os.write(data.size and 0xFF)
                }
                else -> {
                    os.write(127)
                    var v = data.size.toLong()
                    val tmp = ByteArray(8)
                    for (i in 7 downTo 0) { tmp[i] = (v and 0xFF).toByte(); v = v shr 8 }
                    os.write(tmp)
                }
            }
            os.write(data)
            os.flush()
        }
    }
}

package com.workspace.proot

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ExecutorService
import java.util.concurrent.RejectedExecutionHandler
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class MiniSocks5Server(
    private val host: String,
    private val port: Int,
    private val protectSocket: (Socket) -> Unit,
    private val protectDatagram: (DatagramSocket) -> Unit,
    private val logDir: File? = null
) : Thread("mini-socks5") {

    private var server: ServerSocket? = null
    @Volatile private var running = false
    private val blockedRows = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private var pool: ExecutorService? = null
    private val overflowActive = AtomicInteger(0)
    private val dnsCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, InetAddress>>()

    private companion object {
        private const val UDP_PEER_IDLE_MS = 15_000L
        private const val UDP_PEER_MAX = 64
        private const val DNS_CACHE_MS = 60_000L
        private const val OVERFLOW_MAX = 24
    }

    private fun blockedOnce(proto: String, ident: String, port: Int) {
        val key = "$proto|$ident|$port"
        if (blockedRows.putIfAbsent(key, true) != null) return
        FlowLog.add(proto, ident, port, 0, 0, "BLOCKED", if (proto == "DNS") ident else DnsMap.domainOf(ident))
    }

    private fun log(msg: String) {
        val f = logDir ?: return
        runCatching {
            f.mkdirs()
            val lf = File(f, "minisocks.log")
            val lines = if (lf.exists()) lf.readLines() else emptyList()
            val stamp = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            val out = (lines + "[$stamp] $msg").takeLast(1500)
            lf.writeText(out.joinToString("\n") + "\n")
        }
    }

    private fun resolveHost(name: String): InetAddress {
        val now = System.currentTimeMillis()
        val hit = dnsCache[name]
        if (hit != null && now - hit.first < DNS_CACHE_MS) return hit.second
        val addr = InetAddress.getByName(name)
        dnsCache[name] = now to addr
        return addr
    }

    fun startListening(): Int {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(host, port))
        server = s
        running = true
        pool = ThreadPoolExecutor(
            8, 256, 60, TimeUnit.SECONDS, SynchronousQueue(),
            { r ->
                Thread(null, r, "mini-socks5-worker", 256 * 1024).apply { isDaemon = true }
            },
            object : RejectedExecutionHandler {
                override fun rejectedExecution(r: Runnable, executor: ThreadPoolExecutor) {
                    if (Thread.currentThread() === this@MiniSocks5Server) {
                        log("accept busy, dropped task")
                        return
                    }
                    if (overflowActive.getAndIncrement() < OVERFLOW_MAX) {
                        Thread(null, { try { r.run() } finally { overflowActive.decrementAndGet() } }, "mini-socks5-overflow", 256 * 1024).apply {
                            isDaemon = true
                            start()
                        }
                    } else {
                        overflowActive.decrementAndGet()
                        r.run()
                    }
                }
            }
        )
        start()
        return s.localPort
    }

    override fun run() {
        val s = server ?: return
        while (running) {
            try {
                val client = s.accept()
                handleClient(client)
            } catch (e: Exception) {
                if (!running) break
            }
        }
    }

    fun stopListening() {
        running = false
        blockedRows.clear()
        try { server?.close() } catch (_: Exception) {}
        pool?.shutdownNow()
        pool = null
    }

    private fun handleClient(client: Socket) {
        val remote = client.remoteSocketAddress?.toString() ?: "?"
        (pool ?: return).execute {
            try {
                client.soTimeout = 20000
                val input = client.getInputStream()
                val output = client.getOutputStream()

                val ver = readByte(input)
                val nmethods = readByte(input)
                if (ver != 5) throw IllegalStateException("bad ver $ver")
                for (i in 0 until nmethods) readByte(input)
                output.write(byteArrayOf(5, 0))
                output.flush()

                val req = parseRequest(input)
                when (req.cmd) {
                    1 -> handleConnect(client, input, output, req.dst, req.port)
                    3 -> handleUdpAssociate(client, input, output)
                    else -> throw IllegalStateException("bad cmd ${req.cmd}")
                }
            } catch (e: Exception) {
                log("client $remote err: ${e.message}")
                try { client.close() } catch (_: Exception) {}
            }
        }
    }

    internal data class Request(val cmd: Int, val atyp: Int, val dst: InetAddress, val port: Int)

    internal fun parseRequest(input: InputStream): Request {
        readByte(input) // VER
        val cmd = readByte(input) // CMD
        readByte(input) // RSV
        val atyp = readByte(input) // ATYP
        val dst = readAddr(input, atyp)
        val port = ((readByte(input) and 0xFF) shl 8) or (readByte(input) and 0xFF)
        return Request(cmd, atyp, dst, port)
    }

    private fun readByte(input: java.io.InputStream): Int {
        val b = input.read()
        if (b < 0) throw IllegalStateException("eof")
        return b
    }

    private fun readFully(input: java.io.InputStream, len: Int): ByteArray {
        val b = ByteArray(len)
        var off = 0
        while (off < len) {
            val n = input.read(b, off, len - off)
            if (n < 0) throw IllegalStateException("eof")
            off += n
        }
        return b
    }

    private fun readAddr(input: java.io.InputStream, atyp: Int): InetAddress {
        return when (atyp) {
            1 -> InetAddress.getByAddress(readFully(input, 4))
            3 -> {
                val len = readByte(input)
                resolveHost(String(readFully(input, len), Charsets.UTF_8))
            }
            4 -> InetAddress.getByAddress(readFully(input, 16))
            else -> throw IllegalStateException("bad atyp $atyp")
        }
    }

    private fun writeReply(output: java.io.OutputStream, rep: Int, addr: InetAddress, port: Int) {
        val a = addr.address
        output.write(5)
        output.write(rep)
        output.write(0)
        if (a.size == 4) {
            output.write(1)
            output.write(a)
        } else {
            output.write(4)
            output.write(a)
        }
        output.write((port ushr 8) and 0xFF)
        output.write(port and 0xFF)
        output.flush()
    }

    private class UdpPeer(
        val sock: DatagramSocket,
        val flowId: Long,
        val bytesUp: AtomicLong,
        val bytesDown: AtomicLong,
        val target: InetAddress,
        val targetPort: Int,
        val header: ByteArray
    ) {
        @Volatile var lastSeen: Long = System.currentTimeMillis()
        @Volatile var lastFlowUpdate: Long = System.currentTimeMillis()
    }

    private fun handleConnect(client: Socket, input: InputStream, output: OutputStream, dst: InetAddress, dstPort: Int) {
        val ip = dst.hostAddress ?: dst.toString()
        val domain = DnsMap.domainOf(ip)
        if (BlockRules.isBlocked(ip, domain)) {
            log("blocked tcp $ip:$dstPort${domain?.let { " ($it)" } ?: ""}")
            FlowLog.add("TCP", ip, dstPort, 0, 0, "BLOCKED", domain)
            try { writeReply(output, 2, client.localAddress, 0) } catch (_: Exception) {}
            try { client.close() } catch (_: Exception) {}
            return
        }
        val up = AtomicLong(0)
        val down = AtomicLong(0)
        val id = FlowLog.add("TCP", ip, dstPort, 0, 0, "OPEN", domain)

        var remote: Socket? = null
        var rep = 1
        try {
            val s = Socket()
            protectSocket(s)
            s.connect(InetSocketAddress(dst, dstPort), 10000)
            remote = s
            writeReply(output, 0, s.localAddress, s.localPort)
            rep = 0
        } catch (e: Exception) {
            try { writeReply(output, rep, client.localAddress, 0) } catch (_: Exception) {}
        }
        log("tcp connect ${dst.hostAddress}:$dstPort rep=$rep")

        val r = remote
        if (r != null) {
            try { r.soTimeout = 20000 } catch (_: Exception) {}
            val rInput = r.getInputStream()
            val rOutput = r.getOutputStream()
            val p = pool ?: return
            val remaining = java.util.concurrent.atomic.AtomicInteger(2)
            fun oneDone() {
                if (remaining.decrementAndGet() != 0) return
                try { r.close() } catch (_: Exception) {}
                try { client.close() } catch (_: Exception) {}
                FlowLog.updateBytes(id, up.get(), down.get(), "CLOSED")
            }
            p.execute {
                try {
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        rOutput.write(buf, 0, n)
                        rOutput.flush()
                        up.addAndGet(n.toLong())
                    }
                } catch (_: Exception) {}
                try { r.shutdownOutput() } catch (_: Exception) {}
                oneDone()
            }
            p.execute {
                try {
                    val buf = ByteArray(16384)
                    while (true) {
                        val n = rInput.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        output.flush()
                        down.addAndGet(n.toLong())
                    }
                } catch (_: Exception) {}
                try { client.shutdownOutput() } catch (_: Exception) {}
                oneDone()
            }
            return
        }
        try { client.close() } catch (_: Exception) {}
        FlowLog.updateBytes(id, up.get(), down.get(), "CLOSED")
    }

    private fun handleUdpAssociate(client: Socket, input: InputStream, output: OutputStream) {
        val relay = DatagramSocket(InetSocketAddress(host, 0))
        relay.soTimeout = 20000
        writeReply(output, 0, InetAddress.getByName(host), relay.localPort)
        log("udp associate relay=${host}:${relay.localPort}")

        val peers = java.util.concurrent.ConcurrentHashMap<String, UdpPeer>()

        (pool ?: return).execute {
            try {
                val buf = ByteArray(32768)
                while (running && !client.isClosed) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        relay.receive(p)
                    } catch (e: SocketTimeoutException) {
                        continue
                    }
                    val data = p.data
                    val len = p.length
                    if (len < 10) continue
                    if (data[2] != 0.toByte()) continue
                    val atyp = data[3].toInt() and 0xFF
                    var off = 4
                    val target: InetAddress
                    when (atyp) {
                        1 -> {
                            target = InetAddress.getByAddress(data.copyOfRange(off, off + 4))
                            off += 4
                        }
                        3 -> {
                            val dl = data[off].toInt() and 0xFF
                            off += 1
                            target = InetAddress.getByName(String(data, off, dl, Charsets.UTF_8))
                            off += dl
                        }
                        4 -> {
                            target = InetAddress.getByAddress(data.copyOfRange(off, off + 16))
                            off += 16
                        }
                        else -> continue
                    }
                    val tport = ((data[off].toInt() and 0xFF) shl 8) or (data[off + 1].toInt() and 0xFF)
                    off += 2
                    val payloadLen = len - off
                    val ip = target.hostAddress ?: target.toString()
                    val isDns = tport == 53
                    val dnsQuery = if (isDns) data.copyOfRange(off, off + payloadLen) else null

                    if (isDns) {
                        val qname = DnsParser.queryName(dnsQuery!!)
                        if (qname != null) {
                            if (BlockRules.isBlockedDomain(qname)) {
                                blockedOnce("DNS", qname, 0)
                                continue
                            }
                        }
                    } else if (BlockRules.isBlocked(ip, DnsMap.domainOf(ip))) {
                        blockedOnce("UDP", ip, tport)
                        continue
                    }

                    val key = (p.address?.hostAddress ?: "?") + ":" + p.port
                    var peer = peers[key]
                    if (peer == null || peer.sock.isClosed) {
                        peers.remove(key)
                        if (peer != null) {
                            log("udp peer re-created $key -> $ip:$tport")
                        }
                        if (peers.size >= UDP_PEER_MAX) {
                            val oldest = peers.entries.minByOrNull { it.value.lastSeen }
                            if (oldest != null) {
                                peers.remove(oldest.key, oldest.value)
                                runCatching { oldest.value.sock.close() }
                                log("udp peer cap dropped ${oldest.key}")
                            }
                        }
                        val out = DatagramSocket()
                        protectDatagram(out)
                        out.soTimeout = 15000
                        val id = if (isDns) 0L else FlowLog.add("UDP", ip, tport, 0, 0, "OPEN", DnsMap.domainOf(ip))
                        val np = UdpPeer(out, id, AtomicLong(0), AtomicLong(0), target, tport, buildUdpHeader(target, tport))
                        peers[key] = np
                        peer = np
                        val peerKey = key
                        if (!isDns) log("udp peer $peerKey -> $ip:$tport")
                        (pool ?: return@execute).execute {
                            try {
                                val rb = ByteArray(32768)
                                while (running && !client.isClosed) {
                                    val rp = DatagramPacket(rb, rb.size)
                                    try {
                                        out.receive(rp)
                                    } catch (e: SocketTimeoutException) {
                                        if (System.currentTimeMillis() - np.lastSeen > UDP_PEER_IDLE_MS) {
                                            log("udp peer idle closed $peerKey")
                                            break
                                        }
                                        continue
                                    } catch (e: Exception) {
                                        break
                                    }
                                    np.lastSeen = System.currentTimeMillis()
                                    if (isDns) {
                                        val answers = DnsParser.responseMappings(rp.data.copyOfRange(0, rp.length))
                                        for ((d, a) in answers) {
                                            DnsMap.record(d, a)
                                            FlowLog.patchDomain(a, d)
                                        }
                                        val blockedName = answers.firstOrNull { BlockRules.isBlockedDomain(it.first) }?.first
                                        if (blockedName != null) {
                                            blockedOnce("DNS", blockedName, 0)
                                            continue
                                        }
                                    } else {
                                        np.bytesDown.addAndGet(rp.length.toLong())
                                    }
                                    val hdr = np.header
                                    val msg = ByteArray(hdr.size + rp.length)
                                    System.arraycopy(hdr, 0, msg, 0, hdr.size)
                                    System.arraycopy(rp.data, 0, msg, hdr.size, rp.length)
                                    val sp = DatagramPacket(msg, msg.size, p.address, p.port)
                                    relay.send(sp)
                                    if (!isDns) updatePeerFlow(np)
                                }
                            } catch (_: Exception) {}
                            if (!isDns) FlowLog.updateBytes(np.flowId, np.bytesUp.get(), np.bytesDown.get(), "UDP")
                            runCatching { out.close() }
                            peers.remove(peerKey, np)
                        }
                    }
                    peer.lastSeen = System.currentTimeMillis()
                    peer.bytesUp.addAndGet(payloadLen.toLong())
                    try {
                        val sp = if (isDns) DatagramPacket(dnsQuery!!, payloadLen, target, tport)
                                 else DatagramPacket(data, off, payloadLen, target, tport)
                        peer.sock.send(sp)
                    } catch (e: Exception) {
                        peers.remove(key, peer)
                        runCatching { peer.sock.close() }
                        log("udp peer send failed, dropped $key")
                        continue
                    }
                    if (!isDns) updatePeerFlow(peer)
                }
            } catch (_: Exception) {}
        }

        try {
            while (running && !client.isClosed) {
                if (input.read() < 0) break
            }
        } catch (_: Exception) {}
        try { relay.close() } catch (_: Exception) {}
        peers.values.forEach { it.sock.close() }
        try { client.close() } catch (_: Exception) {}
        log("udp associate closed")
    }

    private fun updatePeerFlow(peer: UdpPeer) {
        val now = System.currentTimeMillis()
        if (now - peer.lastFlowUpdate < 1000) return
        peer.lastFlowUpdate = now
        FlowLog.updateBytes(peer.flowId, peer.bytesUp.get(), peer.bytesDown.get(), "UDP")
    }

    private fun buildUdpHeader(srcAddr: InetAddress, srcPort: Int): ByteArray {
        val a = srcAddr.address
        val out = java.io.ByteArrayOutputStream()
        out.write(0); out.write(0); out.write(0)
        if (a.size == 4) {
            out.write(1)
            out.write(a)
        } else {
            out.write(4)
            out.write(a)
        }
        out.write((srcPort ushr 8) and 0xFF)
        out.write(srcPort and 0xFF)
        return out.toByteArray()
    }
}
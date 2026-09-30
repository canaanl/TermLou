package com.workspace.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.PushbackInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * 存活探测的并发锁。
 *
 * ## 这个 bug 是什么
 *
 * `isClientGone` 曾读 Service 级共享字段 `probeInput`：8 条 worker 线程并发时，
 * 后连上的盖掉先连上的。于是：
 *  - A 在 `wait`（30 秒），中间来个 `ping`（B）→ A 的探测读 B 的流，
 *    **A 断了测不出来**，幽灵指令继续占 `pageLock`；
 *  - B 先断（FIN），A 还在等 → A 读到 B 的 -1 → **A 被误杀**。
 * 更脏的是超时的 `soTimeout` 设在 A 的 socket 上，阻塞读的却是 B 的流。
 *
 * 修法：流当参数一路传下来（`serve → route → handle → isClientGone`），
 * 字段删掉。本体探测逻辑在伴生的 [WebAutomationService.probeGone]（纯函数），
 * 下面用真回环 socket 测它。
 */
class ClientProbeTest {

    /** 一对连好的回环连接：客户端 half + 服务端 half（含 wrapper 流）。 */
    private class Pair(val client: Socket, val server: Socket, val input: PushbackInputStream)

    private fun connect(server: ServerSocket): Pair {
        val client = Socket(InetAddress.getByName("127.0.0.1"), server.localPort)
        val svc = server.accept()
        svc.soTimeout = 60_000
        val input = PushbackInputStream(BufferedInputStream(svc.getInputStream()), 1)
        return Pair(client, svc, input)
    }

    private fun gone(p: Pair): Boolean = WebAutomationService.probeGone(p.server, p.input)

    @Test
    fun `活着的连接探出来是没断`() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val a = connect(server)
            try {
                assertFalse("刚连上就说断了", gone(a))
            } finally {
                a.client.close(); a.server.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `B断了A的探测不受影响——此前共享字段会误杀A`() {
        // 这条就是回归本体：旧代码里 probeInput 被 B 盖掉之后，
        // 探 A 会读到 B 的流（已 FIN，-1），于是 A 被误杀。
        // 现在流是参数，A 永远探 A 的。
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val a = connect(server)
            val b = connect(server)
            try {
                b.client.close()
                Thread.sleep(300)   // 等 FIN 到服务端这边
                assertTrue("B 自己断了必须探出来", gone(b))
                assertFalse("B 断了不能连累 A", gone(a))
                a.client.close()
                Thread.sleep(300)
                assertTrue("A 自己断了也要探出来", gone(a))
            } finally {
                runCatching { a.client.close() }; runCatching { a.server.close() }
                runCatching { b.client.close() }; runCatching { b.server.close() }
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `对端发来的字节会被退回去不影响后续解析`() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val a = connect(server)
            try {
                a.client.getOutputStream().write('X'.code)
                a.client.getOutputStream().flush()
                Thread.sleep(300)
                assertFalse("有字节不等于断了", gone(a))
                assertEquals("退回去的字节必须还在", 'X'.code, a.input.read())
            } finally {
                a.client.close(); a.server.close()
            }
        } finally {
            server.close()
        }
    }

    @Test
    fun `探测完soTimeout必须还回去`() {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        try {
            val a = connect(server)
            try {
                a.server.soTimeout = 60_000
                gone(a)
                assertEquals("soTimeout 没还回去会改变后续读行为", 60_000, a.server.soTimeout)
            } finally {
                a.client.close(); a.server.close()
            }
        } finally {
            server.close()
        }
    }
}

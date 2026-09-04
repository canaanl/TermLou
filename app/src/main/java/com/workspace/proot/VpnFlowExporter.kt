package com.workspace.proot

import android.content.Context
import java.io.File

object VpnFlowExporter {
    @Volatile private var started = false
    private val listener: () -> Unit = { flush() }
    private var workspaceFile: File? = null

    fun start(context: Context) {
        if (started) return
        started = true
        workspaceFile = File(context.filesDir, "workspace/vpn-flows.json")
        workspaceFile?.parentFile?.mkdirs()
        FlowLog.subscribe(listener)
        flush()
    }

    fun stop() {
        if (!started) return
        started = false
        FlowLog.unsubscribe(listener)
        try {
            workspaceFile?.delete()
            File(workspaceFile?.parentFile, workspaceFile!!.name + ".tmp").delete()
        } catch (_: Exception) {
        }
    }

    private fun flush() {
        if (!started) return
        if (!NetVpnService.isRunning) return
        val outFile = workspaceFile ?: return
        val flows = FlowLog.list()
        val sb = StringBuilder(flows.size * 180 + 2)
        sb.append('[')
        for (i in flows.indices) {
            val f = flows[i]
            if (i > 0) sb.append(',')
            sb.append('{')
            sb.append("\"id\":").append(f.id).append(',')
            sb.append("\"time\":\"").append(esc(f.time)).append("\",")
            sb.append("\"proto\":\"").append(esc(f.proto)).append("\",")
            sb.append("\"dstIp\":\"").append(esc(f.dstIp)).append("\",")
            sb.append("\"dstPort\":").append(f.dstPort).append(',')
            sb.append("\"bytesUp\":").append(f.bytesUp).append(',')
            sb.append("\"bytesDown\":").append(f.bytesDown).append(',')
            sb.append("\"state\":\"").append(esc(f.state)).append("\",")
            sb.append("\"domain\":")
            if (f.domain == null) sb.append("null") else sb.append('"').append(esc(f.domain)).append('"')
            sb.append('}')
        }
        sb.append(']')
        try {
            outFile.parentFile?.mkdirs()
            val tmp = File(outFile.parentFile, outFile.name + ".tmp")
            tmp.writeText(sb.toString())
            // atomic rename mirrors OverlayBridge pattern
            if (!tmp.renameTo(outFile)) {
                tmp.copyTo(outFile, overwrite = true)
                tmp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun esc(s: String): String = s
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
}

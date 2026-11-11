package com.workspace.proot

/**
 * `termlou-ui service ...` 的纯协议层（5.12.0）。
 *
 * 与 Android 的进程/会话管理解耦，便于单测：
 *  - 解析脚本写来的 JSON 请求；
 *  - 校验服务名与参数；
 *  - 生成 status/list/start 的稳定 JSON 结构。
 */
object TermlouServiceProtocol {

    const val OP = "service"
    const val ACTION_START = "start"
    const val ACTION_STATUS = "status"
    const val ACTION_LIST = "list"
    const val ACTION_STOP = "stop"
    const val ACTION_RM = "rm"
    const val ACTION_CLEAN = "clean"

    private val NAME_RE = Regex("^[A-Za-z0-9._-]{1,64}$")

    data class Request(
        val id: String,
        val action: String,
        val name: String,
        val argv: List<String>,
    )

    enum class State {
        STARTING,
        RUNNING,
        STOPPING,
        STOPPED,
        FAILED,
    }

    data class ServiceSnapshot(
        val name: String,
        val command: String,
        val state: State,
        val startedAt: Long,
        val alive: Boolean,
        val exitCode: Int?,
        val error: String?,
        val logPath: String,
        val pid: Int? = null,
    )

    fun validName(name: String): Boolean =
        NAME_RE.matches(name) && name != "." && name != ".."

    @Suppress("ReturnCount")
    fun parse(obj: MiniJson.Obj): Request? {
        if (obj.optString("op", "") != OP) return null
        val action = obj.optString("action", "").lowercase()
        if (action !in setOf(ACTION_START, ACTION_STATUS, ACTION_LIST, ACTION_STOP, ACTION_RM, ACTION_CLEAN)) {
            return null
        }
        val name = obj.optString("name", "")
        if (action !in setOf(ACTION_LIST, ACTION_CLEAN) && !validName(name)) return null
        val argv = mutableListOf<String>()
        val arr = obj.optArr("argv")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val value = arr.raw(i)
                if (value !is String) return null
                argv += value
            }
        }
        if (action == ACTION_START && (argv.isEmpty() || argv.first().isEmpty())) return null
        return Request(
            id = obj.optString("id", ""),
            action = action,
            name = name,
            argv = argv,
        )
    }

    fun serviceJson(s: ServiceSnapshot): MiniJson.Obj = MiniJson.Obj()
        .put("name", s.name)
        .put("command", s.command)
        .put("state", s.state.name)
        .put("started_at", s.startedAt)
        .put("pid", s.pid ?: -1)
        .put("alive", s.alive)
        .put("exit_code", s.exitCode ?: -1)
        .put("error", s.error ?: "")
        .put("log", s.logPath)

    fun ok(snapshot: ServiceSnapshot): MiniJson.Obj =
        MiniJson.Obj().put("ok", true).put("service", serviceJson(snapshot))

    fun okServices(list: List<ServiceSnapshot>): MiniJson.Obj {
        val arr = MiniJson.Arr()
        for (s in list) arr.put(serviceJson(s))
        return MiniJson.Obj().put("ok", true).put("services", arr)
    }

    fun okRemoved(name: String): MiniJson.Obj =
        MiniJson.Obj().put("ok", true).put("removed", name)

    fun okRemovedCount(count: Int): MiniJson.Obj =
        MiniJson.Obj().put("ok", true).put("removed_count", count)

    fun error(message: String, snapshot: ServiceSnapshot? = null): MiniJson.Obj {
        val obj = MiniJson.Obj().put("ok", false).put("error", message)
        if (snapshot != null) obj.put("service", serviceJson(snapshot))
        return obj
    }
}

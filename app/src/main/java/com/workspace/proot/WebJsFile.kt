package com.workspace.proot

import java.io.File

/**
 * `eval` 的 `js_file` 路径解析（5.13.1，纯逻辑、可单测）。
 *
 * 只允许 `/workspace` 内的文件：`/workspace/x.js` 与相对路径 `x.js` 都相对
 * workspace 根目录；`/tmp/x.js`、`../x.js`、指向外部的符号链接一律拒绝。
 *
 * ## 为什么单独抽出来
 *
 * 5.13.0 的 `resolveJs` 在失败时只回 `null`，调用方只能报
 * `missing js (or js_file not readable)` —— 用户看不出是路径越权、文件不存在、
 * 还是权限问题。这里把“实际尝试的路径 + 原因”做成结构化结果，服务层原样返回。
 */
object WebJsFile {

    sealed class Result {
        data class Ok(val text: String) : Result()

        data class Failure(val reason: String) : Result()
    }

    @Suppress("ReturnCount", "CyclomaticComplexMethod")
    fun resolve(root: File, requested: String): Result {
        val raw = requested.trim()
        if (raw.isEmpty()) return Result.Failure("missing js_file")
        val display = if (raw.startsWith("/")) raw else "/workspace/$raw"

        val candidate = when {
            raw == "/workspace" || raw == "/workspace/" -> root
            raw.startsWith("/workspace/") -> File(root, raw.removePrefix("/workspace/"))
            raw.startsWith("/") ->
                return Result.Failure("js_file must be inside /workspace: $display")
            else -> File(root, raw)
        }

        val canonicalRoot = runCatching { root.canonicalFile }.getOrNull()
            ?: return Result.Failure("js_file root unavailable")
        val canonical = runCatching { candidate.canonicalFile }.getOrNull()
            ?: return Result.Failure("js_file not found: $display")
        val inside = canonical.path == canonicalRoot.path ||
            canonical.path.startsWith(canonicalRoot.path + File.separator)
        if (!inside) {
            return Result.Failure("js_file must be inside /workspace: $display")
        }
        if (!canonical.exists()) return Result.Failure("js_file not found: $display")
        if (!canonical.isFile) return Result.Failure("js_file is not a file: $display")

        val text = runCatching { canonical.readText() }.getOrNull()
            ?: return Result.Failure("js_file not readable: $display")
        return Result.Ok(text)
    }
}

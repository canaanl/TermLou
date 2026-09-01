package com.workspace.proot

import java.io.File

/**
 * 从 rootfs 的 /etc/os-release 解析发行版 codename（trixie / bookworm …），
 * 让 apt 源随内置 rootfs 自动匹配。
 */
fun resolveDistroCodename(rootfsDir: File): String {
    val osRelease = File(rootfsDir, "etc/os-release")
    var codename: String? = null
    try {
        osRelease.readLines().forEach { line ->
            if (line.startsWith("VERSION_CODENAME=")) {
                codename = line.substringAfter("=").trim().trim('"').lowercase()
            }
        }
    } catch (_: Exception) {
    }
    if (!codename.isNullOrBlank()) return codename!!
    return "trixie"
}

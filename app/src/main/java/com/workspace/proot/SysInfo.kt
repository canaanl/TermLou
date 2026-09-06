package com.workspace.proot

import android.os.Build
import java.io.File

/**
 * 系统信息：发行版（读 rootfs 的 /etc/os-release，Android 侧纯文件读，
 * 不起 shell）+ 设备架构。rootfs 未下载/文件缺失时返回 null，由 UI 显示未知。
 */
data class DistroInfo(
    val pretty: String,
    val versionId: String,
    val codename: String
)

fun resolveDistroInfo(rootfsDir: File): DistroInfo? {
    val osRelease = File(rootfsDir, "etc/os-release")
    if (!osRelease.isFile) return null
    var pretty: String? = null
    var versionId: String? = null
    var codename: String? = null
    try {
        osRelease.readLines().forEach { line ->
            when {
                line.startsWith("PRETTY_NAME=") -> pretty = line.substringAfter("=").trim().trim('"')
                line.startsWith("VERSION_ID=") -> versionId = line.substringAfter("=").trim().trim('"')
                line.startsWith("VERSION_CODENAME=") -> codename = line.substringAfter("=").trim().trim('"').lowercase()
            }
        }
    } catch (_: Exception) {
        return null
    }
    if (pretty.isNullOrBlank() && versionId.isNullOrBlank() && codename.isNullOrBlank()) return null
    return DistroInfo(
        pretty = pretty.orEmpty(),
        versionId = versionId.orEmpty(),
        codename = codename.orEmpty()
    )
}

fun deviceAbi(): String = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"

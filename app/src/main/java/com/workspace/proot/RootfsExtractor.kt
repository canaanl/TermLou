package com.workspace.proot

import android.content.Context
import android.system.Os
import android.util.Log
import me.rerere.workspace.RootfsPatcher
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileNotFoundException

data class SymlinkEntry(
    val linkPath: File,
    val linkTarget: String,
)

private const val TAG = "RootfsExtractor"

@Suppress("TooManyFunctions")
class RootfsExtractor(private val context: Context) {

    companion object {
        /**
         * APK 内的实际资源名。AAPT 会把 assets 下的 *.gz 自动解压并去掉 .gz，
         * 所以源码是 rootfs.tar.gz，APK 里看到的是 rootfs.tar。
         */
        const val ROOTFS_ASSET = "rootfs.tar"
        const val ROOTFS_GZ_ASSET = "rootfs.tar.gz"
        const val ROOTFS_MARKER = ".termlou-rootfs-ok"
        private const val MARKER_TEXT = "termlou-rootfs-v1\n"
        private const val STAGING_NAME = ".rootfs-staging"
        private const val BACKUP_NAME = ".rootfs-backup"

        /**
         * 安装是否完整。
         *
         * 有 marker 的新安装：必须关键路径齐全。
         * 没有 marker 的老安装：老版本只认 etc/passwd，这里也只按老条件
         * 补写 marker，绝不因为新增校验把能用的老 rootfs 判成未安装。
         */
        @Suppress("ReturnCount")
        fun isInstalled(linuxDir: File): Boolean {
            if (!linuxDir.isDirectory) return false
            val marker = File(linuxDir, ROOTFS_MARKER)
            if (marker.isFile) return hasKeyPaths(linuxDir)
            if (!File(linuxDir, "etc/passwd").isFile) return false
            runCatching { marker.writeText(MARKER_TEXT) }
            return true
        }

        private fun hasKeyPaths(linuxDir: File): Boolean =
            File(linuxDir, "etc/passwd").isFile &&
                File(linuxDir, "usr/bin/env").exists() &&
                (File(linuxDir, "usr/bin/sh").exists() || File(linuxDir, "bin/sh").exists())
    }

    fun extractTo(linuxDir: File) {
        val parent = linuxDir.parentFile
            ?: throw IllegalArgumentException("rootfs parent is missing: ${linuxDir.path}")
        val staging = File(parent, STAGING_NAME)
        val backup = File(parent, BACKUP_NAME)
        recoverInterrupted(linuxDir, staging, backup)
        if (isInstalled(linuxDir)) return

        staging.deleteRecursively()
        staging.mkdirs()
        try {
            extractRootfsAsset(staging)
            installStaticCurl(staging)
            setupDns(staging)
            try {
                fixPermissions(staging)
            } catch (e: Exception) {
                Log.w(TAG, "fixPermissions failed", e)
            }
            verifyRootfs(staging)
            File(staging, ROOTFS_MARKER).writeText(MARKER_TEXT)
            commitStaging(staging, linuxDir, backup)
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * 提交前先清理上次中断留下的状态。若生产目录是半成品而备份是完整的，
     * 先把备份恢复回来，避免升级/重装失败时把原有 rootfs 弄丢。
     */
    private fun recoverInterrupted(linuxDir: File, staging: File, backup: File) {
        runCatching {
            if (!linuxDir.exists() && backup.exists()) {
                if (!backup.renameTo(linuxDir)) {
                    Log.w(TAG, "failed to restore rootfs backup")
                }
            }
            if (linuxDir.exists() && backup.exists()) {
                if (isInstalled(linuxDir)) {
                    backup.deleteRecursively()
                } else {
                    linuxDir.deleteRecursively()
                    if (!backup.renameTo(linuxDir)) {
                        Log.w(TAG, "failed to restore incomplete rootfs backup")
                    }
                }
            }
            staging.deleteRecursively()
        }
    }

    private fun verifyRootfs(linuxDir: File) {
        require(hasKeyPaths(linuxDir)) { "RootFS extraction incomplete: key paths missing" }
    }

    @Suppress("ThrowsCount")
    private fun commitStaging(staging: File, linuxDir: File, backup: File) {
        backup.deleteRecursively()
        var movedOld = false
        try {
            if (linuxDir.exists()) {
                if (!linuxDir.renameTo(backup)) {
                    throw java.io.IOException("Failed to move existing rootfs to backup")
                }
                movedOld = true
            }
            if (!staging.renameTo(linuxDir)) {
                throw java.io.IOException("Failed to move staged rootfs into place")
            }
            backup.deleteRecursively()
        } catch (e: Exception) {
            if (movedOld && !linuxDir.exists() && backup.exists()) {
                backup.renameTo(linuxDir)
            }
            throw e
        }
    }

    private fun installStaticCurl(linuxDir: File) {
        try {
            val curlTarget = File(linuxDir, "usr/bin/curl")
            if (!curlTarget.exists()) {
                context.assets.open("curl_aarch64").use { input ->
                    curlTarget.parentFile?.mkdirs()
                    curlTarget.outputStream().use { input.copyTo(it) }
                }
                curlTarget.setExecutable(true, false)
            }
            val caTarget = File(linuxDir, "etc/ssl/certs/ca-certificates.crt")
            if (!caTarget.exists()) {
                context.assets.open("cacert.pem").use { input ->
                    caTarget.parentFile?.mkdirs()
                    caTarget.outputStream().use { input.copyTo(it) }
                }
            }
        } catch (_: Exception) {}
    }

    @Suppress("SwallowedException")
    private fun extractRootfsAsset(linuxDir: File) {
        var assetName = ROOTFS_ASSET
        val raw = try {
            context.assets.open(assetName)
        } catch (e: FileNotFoundException) {
            // 兼容以后关闭 AAPT 自动解压、保留 .gz 的打包方式。
            assetName = ROOTFS_GZ_ASSET
            context.assets.open(assetName)
        }
        val input = when {
            assetName.endsWith(".xz") -> BufferedInputStream(XZCompressorInputStream(BufferedInputStream(raw)))
            assetName.endsWith(".gz") -> BufferedInputStream(GzipCompressorInputStream(BufferedInputStream(raw)))
            else -> BufferedInputStream(raw)
        }
        input.use { extractTar(it, linuxDir) }
    }

    private fun extractTar(inputStream: java.io.InputStream, linuxDir: File) {
        val symlinks = mutableListOf<SymlinkEntry>()
        val deferredSymlinks = mutableListOf<SymlinkEntry>()
        val hardlinks = mutableListOf<Pair<File, String>>()
        val deferredHardlinks = mutableListOf<Pair<File, String>>()

        TarArchiveInputStream(inputStream).use { tarStream ->
            var entry = tarStream.nextTarEntry
            while (entry != null) {
                val entryName = normalizeTarEntry(entry.name)
                if (entryName != null) {
                    val targetFile = resolveInside(linuxDir, entryName)
                    when {
                        entry.isDirectory -> targetFile.mkdirs()
                        entry.isSymbolicLink -> symlinks += SymlinkEntry(targetFile, entry.linkName)
                        entry.isLink -> hardlinks += targetFile to entry.linkName
                        entry.isFile || entry.isFIFO || entry.isCharacterDevice || entry.isBlockDevice -> {
                            targetFile.parentFile?.mkdirs()
                            targetFile.outputStream().use { out -> tarStream.copyTo(out) }
                            try {
                                targetFile.setExecutable(entry.mode and 64 != 0, entry.mode and 8 == 0)
                            } catch (_: Exception) {}
                        }
                    }
                }
                entry = tarStream.nextTarEntry
            }
        }

        // symlinks: 两轮创建，失败降级复制目标文件
        for (s in symlinks) {
            try { Os.symlink(s.linkTarget, s.linkPath.absolutePath) }
            catch (_: Exception) { deferredSymlinks += s }
        }
        for (s in deferredSymlinks) {
            try { Os.symlink(s.linkTarget, s.linkPath.absolutePath) }
            catch (_: Exception) { copyTargetFile(linuxDir, s.linkPath, s.linkTarget) }
        }

        // hardlinks: 两轮创建，失败降级复制目标文件
        for ((linkPath, linkName) in hardlinks) {
            try {
                val source = resolveInside(linuxDir, linkName)
                Os.link(source.absolutePath, linkPath.absolutePath)
            } catch (_: Exception) {
                deferredHardlinks += linkPath to linkName
            }
        }
        for ((linkPath, linkName) in deferredHardlinks) {
            try {
                val source = resolveInside(linuxDir, linkName)
                Os.link(source.absolutePath, linkPath.absolutePath)
            } catch (_: Exception) {
                copyTargetFile(linuxDir, linkPath, linkName)
            }
        }
    }

    private fun normalizeTarEntry(name: String): String? {
        val normalized = name
            .replace('\\', '/')
            .trim()
            .trimStart('/')
            .removePrefix("./")
        if (normalized.isBlank()) return null
        require(!normalized.contains('\u0000')) { "Rootfs entry path contains invalid character" }
        require(normalized.split('/').none { it == ".." }) { "Rootfs entry escapes target directory: $name" }
        return normalized
    }

    private fun resolveInside(linuxDir: File, name: String): File {
        val normalized = normalizeTarEntry(name) ?: throw IllegalArgumentException("Rootfs path is blank: $name")
        val root = linuxDir.canonicalFile
        val target = File(root, normalized).canonicalFile
        require(target.path == root.path || target.path.startsWith(root.path + File.separator)) {
            "Rootfs path escapes target directory: $name"
        }
        return target
    }

    private fun copyTargetFile(linuxDir: File, link: File, target: String) {
        try {
            val base = if (target.trimStart().startsWith("/")) {
                File(linuxDir, target.trim().trimStart('/'))
            } else {
                File(link.parentFile, target)
            }
            val resolved = base.canonicalFile
            val root = linuxDir.canonicalFile
            require(resolved.path == root.path || resolved.path.startsWith(root.path + File.separator)) {
                "Symlink target escapes rootfs: $target"
            }
            if (resolved.exists()) {
                link.parentFile?.mkdirs()
                if (resolved.isDirectory) {
                    Log.w(TAG, "skip dir target ${link.name} -> $target")
                } else {
                    resolved.copyTo(link, overwrite = true)
                }
            } else {
                Log.w(TAG, "target $target for ${link.name} does not exist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "copyTarget failed for ${link.name}", e)
        }
    }

    private fun setupDns(linuxDir: File) {
        val servers = NetworkUtils.getDnsServers(context)
        RootfsPatcher().ensureRootfsDns(File(linuxDir, "etc"), servers)
    }

    private fun fixPermissions(linuxDir: File) {
        for (dir in listOf("bin", "sbin", "usr/bin", "usr/sbin", "usr/libexec")) {
            val d = File(linuxDir, dir)
            if (d.isDirectory) {
                d.walkTopDown().forEach { if (it.isFile) it.setExecutable(true, false) }
            }
        }
    }
}

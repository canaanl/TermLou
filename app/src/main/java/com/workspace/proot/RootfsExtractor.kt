package com.workspace.proot

import android.content.Context
import android.system.Os
import android.util.Log
import me.rerere.workspace.RootfsPatcher
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.BufferedInputStream

data class SymlinkEntry(
    val linkPath: File,
    val linkTarget: String,
)

private const val TAG = "RootfsExtractor"

class RootfsExtractor(private val context: Context) {

    fun extractTo(linuxDir: File) {
        if (linuxDir.exists() && File(linuxDir, "etc/passwd").exists()) return
        if (linuxDir.exists()) linuxDir.deleteRecursively()
        linuxDir.mkdirs()
        extractTarAsset(context, "rootfs.tar", linuxDir)

        installStaticCurl(linuxDir)
        setupDns(linuxDir)
        try { fixPermissions(linuxDir) } catch (_: Exception) {}
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

    private fun extractTarAsset(context: Context, assetName: String, linuxDir: File) {
        val raw = context.assets.open(assetName)
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
                val targetFile = File(linuxDir, entry.name)
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
            catch (_: Exception) { copyTargetFile(s.linkPath, s.linkTarget) }
        }

        // hardlinks: 两轮创建，失败降级复制目标文件
        for ((linkPath, linkName) in hardlinks) {
            try {
                val source = File(linuxDir, linkName)
                Os.link(source.absolutePath, linkPath.absolutePath)
            } catch (_: Exception) {
                deferredHardlinks += linkPath to linkName
            }
        }
        for ((linkPath, linkName) in deferredHardlinks) {
            try {
                val source = File(linuxDir, linkName)
                Os.link(source.absolutePath, linkPath.absolutePath)
            } catch (_: Exception) {
                copyTargetFile(linkPath, linkName)
            }
        }
    }

    private fun copyTargetFile(link: File, target: String) {
        try {
            val resolved = File(link.parentFile, target).canonicalFile
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

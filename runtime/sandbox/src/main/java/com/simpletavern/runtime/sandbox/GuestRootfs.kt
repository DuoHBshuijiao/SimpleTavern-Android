package com.simpletavern.runtime.sandbox

import android.system.Os
import com.simpletavern.core.model.StError
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Extract the bundled Linux base, not arbitrary user archives. Links are rooted in the guest filesystem. */
internal object GuestRootfs {
    suspend fun extract(input: InputStream, root: File) {
        val header = ByteArray(512)
        val links = mutableListOf<Triple<File, String, Boolean>>()
        val directories = mutableListOf<Pair<File, Int>>()
        var entries = 0; var bytes = 0L
        fun field(start: Int, length: Int) = header.copyOfRange(start, start + length).toString(Charsets.UTF_8).substringBefore('\u0000')
        fun number(start: Int, length: Int) = field(start, length).trim().takeIf(String::isNotEmpty)?.toLongOrNull(8) ?: 0L
        fun path(raw: String): File {
            val name = raw.removePrefix("./").trimEnd('/')
            if (name.isEmpty()) return root
            if (name.startsWith('/') || name.split('/').any { it in setOf("", ".", "..") || '\\' in it || '\u0000' in it })
                throw StError.Validation("invalid bundled rootfs path")
            return root.resolve(name)
        }
        suspend fun transfer(size: Long, output: FileOutputStream? = null) {
            var remaining = size
            val buffer = ByteArray(65536)
            while (remaining > 0) {
                currentCoroutineContext().ensureActive()
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) throw StError.Validation("truncated bundled rootfs")
                output?.write(buffer, 0, read); remaining -= read
            }
        }
        while (true) {
            currentCoroutineContext().ensureActive()
            var read = 0
            while (read < 512) {
                val count = input.read(header, read, 512 - read)
                if (count < 0) throw StError.Validation("truncated rootfs header")
                read += count
            }
            if (header.all { it == 0.toByte() }) break
            if (++entries > 10000) throw StError.Validation("bundled rootfs exceeds entry budget")
            val prefix = field(345, 155)
            val name = if (prefix.isEmpty()) field(0, 100) else "$prefix/${field(0, 100)}"
            val file = path(name)
            val size = number(124, 12)
            bytes += size
            if (size !in 0..33_554_432L || bytes > 67_108_864L) throw StError.Validation("bundled rootfs exceeds size budget")
            val mode = number(100, 8).toInt() and 511
            when (header[156].toInt().toChar()) {
                '0', '\u0000' -> {
                    file.parentFile!!.mkdirs()
                    FileOutputStream(file).use { output -> transfer(size, output); output.fd.sync() }
                    Os.chmod(file.path, mode)
                }
                '5' -> { file.mkdirs(); directories += file to mode; transfer(size) }
                '1', '2' -> { file.parentFile!!.mkdirs(); links += Triple(file, field(157, 100), header[156].toInt().toChar() == '1'); transfer(size) }
                else -> throw StError.Unsupported("unsupported bundled rootfs entry type")
            }
            transfer((512 - size % 512) % 512)
        }
        // No symlink is followed during extraction. Normalize absolute guest links to portable relative links.
        for ((file, target, hard) in links) {
            val destination = if (target.startsWith('/')) root.resolve(target.removePrefix("/"))
                else if (hard) path(target) else file.parentFile!!.resolve(target)
            val normalized = destination.toPath().normalize().toFile()
            if (normalized != root && !normalized.path.startsWith(root.path + File.separator))
                throw StError.Validation("bundled rootfs link escapes guest root")
            if (hard) Os.link(normalized.path, file.path)
            else Os.symlink(file.parentFile!!.toPath().relativize(normalized.toPath()).toString(), file.path)
        }
        directories.asReversed().forEach { (directory, mode) -> Os.chmod(directory.path, mode) }
    }

    fun removeStaging(root: File) {
        // Exact owned staging tree only; unlink links instead of following them.
        val stat = android.system.Os.lstat(root.path)
        if (android.system.OsConstants.S_ISDIR(stat.st_mode)) {
            root.listFiles()?.forEach(::removeStaging)
                ?: throw StError.Sandbox("cannot enumerate rootfs staging")
        }
        if (!root.delete()) throw StError.Sandbox("cannot remove rootfs staging")
    }
}

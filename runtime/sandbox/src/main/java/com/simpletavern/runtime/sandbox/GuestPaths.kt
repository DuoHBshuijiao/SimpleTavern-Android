package com.simpletavern.runtime.sandbox

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.model.StError
import java.io.File
import java.util.ArrayDeque

/** Root is already owned/checked by SandboxManager. Absolute link targets are guest paths, never host paths. */
internal object GuestPaths {
    fun resolve(root: File, path: String, followFinalLink: Boolean = true): File {
        if ('\u0000' in path || path.toByteArray(Charsets.UTF_8).size > 8192)
            throw StError.Validation("invalid or oversized guest path")
        val pending = ArrayDeque<String>()
        fun expand(value: String) {
            val parts = value.split('/')
            if (pending.size + parts.size > 4096) throw StError.Sandbox("guest path expansion exceeds component limit")
            parts.asReversed().forEach(pending::addFirst)
        }
        expand(path)
        var directory = root
        var links = 0
        while (pending.isNotEmpty()) {
            val component = pending.removeFirst()
            when (component) {
                "", "." -> continue
                ".." -> {
                    if (directory == root) throw StError.Sandbox("path escape rejected", mapOf("path" to path))
                    directory = directory.parentFile!!
                }
                else -> {
                    val next = directory.resolve(component)
                    val info = try { Os.lstat(next.path) } catch (error: ErrnoException) {
                        if (error.errno == OsConstants.ENOENT) null else throw error
                    }
                    if (info != null && OsConstants.S_ISLNK(info.st_mode)) {
                        // Unlink/rename operate on this name, including dangling or cyclic final links.
                        if (!followFinalLink && pending.isEmpty()) return next
                        if (++links > 40) throw StError.Sandbox("too many guest symbolic links", mapOf("path" to path))
                        val target = Os.readlink(next.path)
                        if (target.startsWith('/')) directory = root
                        // Do not normalize before expansion: link/.. depends on the link's resolved destination.
                        expand(target)
                    } else {
                        if (info != null && pending.isNotEmpty() && !OsConstants.S_ISDIR(info.st_mode))
                            throw StError.Validation("guest path ancestor is not a directory")
                        directory = next
                    }
                }
            }
        }
        return directory
    }
}

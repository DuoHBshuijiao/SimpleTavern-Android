package com.simpletavern.core.api

import com.simpletavern.core.model.NativeBackupFile
import com.simpletavern.core.model.StError
import java.util.ArrayDeque
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

internal data class NativeLinkEntry(val kind: String, val target: String? = null)
internal enum class NativeLinkResolution { RESOLVED, DANGLING, NOT_DIRECTORY, LOOP, ESCAPES_SANDBOX }

/** Walk components AFTER expanding each link. normalize() before expansion loses the meaning of '..'. */
internal object NativeBackupLinkResolver {
    /** Rebase an absolute guest link beneath its sandbox without normalizing its suffix. */
    fun portableTarget(path: String, target: String): String {
        val parts = path.split('/')
        if (parts.size < 3 || parts[0] != "sandboxes" || parts[1].isEmpty())
            throw StError.Validation("invalid native guest link location")
        val portable = if (target.startsWith('/')) {
            val parentsToRoot = parts.size - 3
            buildString {
                repeat(parentsToRoot) { if (isNotEmpty()) append('/'); append("..") }
                val suffix = target.trimStart('/')
                if (suffix.isNotEmpty()) {
                    if (isNotEmpty()) append('/')
                    append(suffix)
                }
            }.ifEmpty { "." }
        } else target
        NativeBackupPreflightService.validateLinkTarget(path, portable)
        return portable
    }

    suspend fun resolve(path: String, target: String, lookup: suspend (String) -> NativeLinkEntry?): NativeLinkResolution {
        val parts = path.split('/')
        if (parts.size < 3 || parts[0] != "sandboxes") throw StError.Validation("invalid native link location")
        val root = "sandboxes/${parts[1]}"
        val directory = parts.drop(2).dropLast(1).toMutableList()
        val pending = ArrayDeque<String>()
        fun expand(linkPath: String, value: String): Boolean {
            // Bounded by manifest size/real readlink size; also avoid millions of empty components.
            val components = mutableListOf<String>()
            var start = 0
            for (index in value.indices) if (value[index] == '/') {
                if (components.size >= 16_384) throw StError.Validation("native link has too many components")
                components += value.substring(start, index); start = index + 1
            }
            components += value.substring(start)
            if (pending.size + components.size > 16_384) throw StError.Validation("native link expansion exceeds component budget")
            // Apply the existing component budget before lexical normalization parses the target.
            try { NativeBackupPreflightService.validateLinkTarget(linkPath, value) }
            catch (_: StError.Validation) { return false }
            for (component in components.asReversed()) pending.addFirst(component)
            return true
        }
        if (!expand(path, target)) return NativeLinkResolution.ESCAPES_SANDBOX
        var links = 1
        var steps = 0
        while (pending.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            if (++steps > 65_536) throw StError.Validation("native link resolution exceeds component budget")
            val component = pending.removeFirst()
            when (component) {
                "", "." -> continue
                ".." -> {
                    if (directory.isEmpty()) return NativeLinkResolution.ESCAPES_SANDBOX
                    directory.removeAt(directory.lastIndex)
                }
                else -> {
                    val next = (listOf(root) + directory + component).joinToString("/")
                    val entry = lookup(next) ?: return NativeLinkResolution.DANGLING
                    if (entry.kind == "symlink") {
                        if (++links > 40) return NativeLinkResolution.LOOP // Linux pathname resolution limit, not recursion.
                        if (!expand(next, entry.target ?: throw StError.Validation("native link target missing")))
                            return NativeLinkResolution.ESCAPES_SANDBOX
                    } else {
                        if (pending.isNotEmpty() && entry.kind != "directory") return NativeLinkResolution.NOT_DIRECTORY
                        directory += component
                    }
                }
            }
        }
        return NativeLinkResolution.RESOLVED
    }

    /** Archive-only topology. Dangling/cyclic links are inert metadata, never followed or omitted. */
    suspend fun validateArchive(files: List<NativeBackupFile>): List<String> {
        val entries = files.associate { it.path to NativeLinkEntry(it.kind, it.linkTarget) }
        val counts = mutableMapOf<NativeLinkResolution, Int>()
        for (file in files.filter { it.kind == "symlink" }) {
            val result = resolve(file.path, file.linkTarget!!) { entries[it] }
            if (result == NativeLinkResolution.ESCAPES_SANDBOX) throw StError.Validation("composed native link escapes its sandbox")
            if (result != NativeLinkResolution.RESOLVED) counts[result] = (counts[result] ?: 0) + 1
        }
        return counts.entries.map { "Archive link topology ${it.key.name.lowercase()}: ${it.value}; link metadata preserved, never followed." }
    }
}

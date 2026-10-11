package com.simpletavern.runtime.sandbox

import android.content.Context
import android.os.Build
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import com.simpletavern.core.model.*
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import kotlinx.coroutines.*

/** Bundled offline base; per-sandbox writable Linux roots. No user-selected host-directory binds. */
class SandboxRuntime(context: Context) {
    private val assets = context.applicationContext.assets
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val executable = nativeDir.resolve("libstproot.so")
    private val loader = nativeDir.resolve("libstprootloader.so")
    private fun marker(root: File) = root.resolve(".st-runtime")
    private fun hasGuestShell(root: File): Boolean = try {
        GuestPaths.resolve(root, "bin/sh").isFile
    } catch (_: StError.Validation) {
        false
    } catch (_: StError.Sandbox) {
        false
    }
    private fun bundled() = "arm64-v8a" in Build.SUPPORTED_ABIS && executable.isFile && loader.isFile &&
        nativeDir.resolve("libtalloc.so").isFile && nativeDir.resolve("libandroid-shmem.so").isFile
    fun status(id: String, root: File) = SandboxRuntimeStatus(id, "arm64-v8a", VERSION, bundled(),
        marker(root).let { it.isFile && it.length() <= 256 && it.readText().trim() == VERSION } && hasGuestShell(root))

    suspend fun install(root: File) = withContext(Dispatchers.IO) {
        if (!bundled()) throw StError.Unsupported("bundled arm64 PRoot runtime is unavailable on this ABI/install")
        if (status("", root).initialized) return@withContext
        val stage = root.parentFile!!.resolve(".rootfs-install-${UUID.randomUUID()}")
        if (!stage.mkdir()) throw StError.Sandbox("cannot create rootfs staging")
        var removeStage = true
        try {
            // AGP expands .gz assets and removes the suffix when building the AAR/APK.
            assets.open("sandbox/alpine-arm64.tar").use { archive -> BufferedInputStream(archive).use {
                GuestRootfs.extract(it, stage)
            } }
            stage.resolve("imports").mkdir()
            listOf("null", "zero", "random", "urandom").forEach { stage.resolve("dev/$it").createNewFile() }
            val dns = stage.resolve("etc/resolv.conf")
            if (!dns.exists()) dns.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
            FileOutputStream(marker(stage)).use { it.write(VERSION.toByteArray(Charsets.UTF_8)); it.fd.sync() }
            val children = stage.listFiles() ?: throw StError.Sandbox("cannot inspect rootfs staging")
            // Preflight every top-level name before publication. Never overwrite existing user files.
            val moves = children.filter { source ->
                val target = root.resolve(source.name)
                val existing = try { Os.lstat(target.path) } catch (error: ErrnoException) {
                    if (error.errno == OsConstants.ENOENT) null else throw error
                }
                if (existing == null) true
                else if (OsConstants.S_ISDIR(existing.st_mode) && source.isDirectory && source.listFiles()?.isEmpty() == true) false
                else throw StError.Conflict("runtime base conflicts with existing sandbox entry: ${source.name}; use a new sandbox")
            }.sortedBy { if (it.name == marker(stage).name) 1 else 0 }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) {
                val published = mutableListOf<File>()
                try {
                    // Publish readiness last, after every base entry has been moved.
                    moves.forEach { source ->
                        Os.rename(source.path, root.resolve(source.name).path)
                        published += source
                    }
                    val fd = Os.open(root.path, OsConstants.O_RDONLY or OsConstants.O_CLOEXEC, 0)
                    try { Os.fsync(fd) } finally { Os.close(fd) }
                } catch (error: Throwable) {
                    try {
                        while (published.isNotEmpty()) {
                            val source = published.last()
                            Os.rename(root.resolve(source.name).path, source.path)
                            published.removeAt(published.lastIndex)
                        }
                    } catch (failure: Throwable) {
                        // Keep this exact staging tree if rollback itself fails; do not delete recoverable files.
                        // Stop at the first failed move, so a retained readiness marker still has the complete base.
                        removeStage = false
                        throw StError.Sandbox("runtime installation failed and rollback is incomplete", mapOf(
                            "stagingDirectory" to stage.name,
                            "remainingEntries" to published.joinToString(",") { it.name },
                        )).apply {
                            initCause(error)
                            addSuppressed(failure)
                        }
                    }
                    throw error
                }
            }
        } finally { if (removeStage) withContext(NonCancellable) { GuestRootfs.removeStaging(stage) } }
    }

    internal fun launch(root: File, command: String, controlDirectory: File): ShellLaunch {
        return launchGuest(root, listOf("/bin/sh", "-c", command), emptyMap(), controlDirectory)
    }

    internal fun launchStdio(root: File, command: String, args: List<String>, environment: Map<String, String>,
        controlDirectory: File): ShellLaunch = launchGuest(root, listOf(command) + args, environment, controlDirectory)

    private fun launchGuest(root: File, command: List<String>, environment: Map<String, String>, controlDirectory: File): ShellLaunch {
        if (!bundled() || !status("", root).initialized) throw StError.Unsupported("initialize the bundled sandbox runtime before shell execution")
        val guest = listOf("/usr/bin/env", "-i", "HOME=/home", "TMPDIR=/tmp", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "LANG=C.UTF-8", "TERM=dumb") + environment.map { (name, value) -> "$name=$value" } + command
        val args = listOf(executable.path, "--kill-on-exit", "-0", "-r", root.path, "-w", "/home") +
            listOf("null", "zero", "random", "urandom").flatMap { listOf("-b", "/dev/$it:/dev/$it") } + guest
        return ShellLaunch(args, mapOf("LD_LIBRARY_PATH" to nativeDir.path, "PROOT_LOADER" to loader.path,
            "PROOT_TMP_DIR" to controlDirectory.path, "PROOT_NO_SECCOMP" to "1"))
    }

    companion object { const val VERSION = "alpine-3.24.2-proot-5.1.107.96-arm64-v1" }
}

internal data class ShellLaunch(val arguments: List<String>, val environment: Map<String, String>)

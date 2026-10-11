package com.simpletavern.core.api

import android.net.Uri
import android.webkit.MimeTypeMap
import com.simpletavern.core.data.*
import com.simpletavern.core.data.db.AttachmentEntity
import com.simpletavern.core.model.*
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import kotlinx.coroutines.*
import okhttp3.*

/** Explicit package installation. Documents/assets only; never execute package instructions. */
internal class AssistantSkillInstaller(private val content: ContentRepository, private val attachments: AttachmentStore,
    private val gate: DataOperationGate, private val paths: StoragePaths, private val inputs: SourceArchiveInputs,
    private val inputRoot: File) {
    private val http = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(20, java.util.concurrent.TimeUnit.SECONDS).readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
        .callTimeout(300, java.util.concurrent.TimeUnit.SECONDS).build()

    suspend fun install(assistantId: String, archive: File, options: SkillInstallOptions): SkillInstallResult =
        install(assistantId, options) { id, claim -> inputs.open(archive).use { inputs.snapshot(id, it, claim) {} } }
    suspend fun install(assistantId: String, archive: Uri, options: SkillInstallOptions): SkillInstallResult =
        install(assistantId, options) { id, claim -> inputs.open(archive).use { inputs.snapshot(id, it, claim) {} } }

    suspend fun install(assistantId: String, source: GitHubSkillSource, options: SkillInstallOptions): SkillInstallResult {
        if (!source.owner.matches(Regex("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) ||
            !source.repository.matches(Regex("[A-Za-z0-9._-]{1,100}")) ||
            source.ref?.let { it.isBlank() || it.length > 512 || it.any(Char::isISOControl) } == true)
            throw StError.Validation("invalid GitHub skill repository or ref")
        val url = HttpUrl.Builder().scheme("https").host("api.github.com").addPathSegment("repos")
            .addPathSegment(source.owner).addPathSegment(source.repository).addPathSegment("zipball")
            .apply { source.ref?.let { addPathSegment(it) } }.build()
        return install(assistantId, options) { id, claim ->
            val stage = inputRoot.resolve(id)
            if (!stage.mkdir()) throw StError.Conflict("skill input directory unavailable")
            claim()
            val archive = stage.resolve("archive.zip")
            download(url, archive)
            archive
        }
    }

    private suspend fun install(assistantId: String, options: SkillInstallOptions,
        prepare: suspend (String, () -> Unit) -> File): SkillInstallResult = withContext(Dispatchers.IO) {
        options.directory?.let(SkillStore::directory)
        options.skillPath?.takeIf { it.isNotEmpty() }?.let(AssistantWorkspaceStore::normalize)
        val config = gate.write { content.getAssistant(assistantId) ?: throw StError.NotFound("assistant configuration") }
        val id = UUID.randomUUID().toString().replace("-", "")
        var owned = false
        try {
            val archive = prepare(id) { owned = true }
            try { ZipFile(archive).use { zip -> publish(config, zip, options) } }
            catch (_: java.util.zip.ZipException) { throw StError.Validation("invalid ZIP skill package") }
        } finally { if (owned) withContext(NonCancellable) { inputs.cleanup(id) } }
    }

    private data class PackageFile(val path: String, val entry: ZipEntry)
    private suspend fun publish(config: AssistantConfig, zip: ZipFile, options: SkillInstallOptions): SkillInstallResult {
        if (zip.size() > 4096) throw StError.Validation("skill archive exceeds 4096 entries")
        val all = zip.entries().asSequence().filter { !it.isDirectory }.map { entry ->
            PackageFile(AssistantWorkspaceStore.normalize(entry.name), entry)
        }.toList()
        if (all.map { it.path }.distinct().size != all.size) throw StError.Validation("duplicate skill archive path")
        val top = all.map { it.path.substringBefore('/') }.distinct()
        val wrapper = if (top.size == 1 && all.all { '/' in it.path }) top.single() + "/" else ""
        val relative = all.map { it.copy(path = it.path.removePrefix(wrapper)) }
        val selectedPath = options.skillPath
        val prefix = if (selectedPath != null) {
            selectedPath.takeIf { it.isNotEmpty() }?.let { AssistantWorkspaceStore.normalize(it) + "/" } ?: ""
        } else {
            val roots = relative.filter { it.path == "SKILL.md" || it.path.endsWith("/SKILL.md") }.map { it.path.removeSuffix("SKILL.md") }
            if (roots.size != 1) throw StError.Validation("select skillPath when the archive contains zero or multiple skills")
            roots.single()
        }
        val files = relative.filter { it.path.startsWith(prefix) }.map { it.copy(path = it.path.removePrefix(prefix)) }
        if (files.size > 512) throw StError.Validation("skill package exceeds 512 files")
        val root = files.find { it.path == "SKILL.md" } ?: throw StError.Validation("selected folder has no SKILL.md")
        val document = zip.getInputStream(root.entry).use { SkillFileTypes.text(readTextBytes(it)) }
            ?: throw StError.Validation("SKILL.md must be UTF-8 text")
        val at = Instant.now().toString()
        val scope = SkillStore.scope(config)
        val parsed = SkillStore.definition(scope, options.directory ?: "skill", document, at, config.sourceNamespace)
        val requested = options.directory ?: prefix.trimEnd('/').substringAfterLast('/').ifBlank {
            parsed.name.replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-').ifBlank { "skill" }
        }
        SkillStore.directory(requested)
        val owned = mutableListOf<File>()
        val rows = mutableListOf<AttachmentEntity>()
        val prepared = mutableListOf<SkillFile>()
        val lease = gate.sharedLease()
        var committed = false
        var totalBytes = 0L
        return try {
            for (file in files.sortedBy { it.path }) {
                currentCoroutineContext().ensureActive()
                var bytes: ByteArray? = null
                val body = if (file.path == "SKILL.md") document else if (!SkillFileTypes.isBinary(file.path) && file.entry.size in 0..AssistantWorkspaceStore.MAX_TEXT_BYTES.toLong()) {
                    bytes = zip.getInputStream(file.entry).use { readTextBytes(it) }
                    SkillFileTypes.text(bytes)
                } else null
                if (body != null) {
                    totalBytes += body.toByteArray(Charsets.UTF_8).size
                    prepared += SkillFile(parsed.id, file.path, body, updatedAt = at, sourceNamespace = config.sourceNamespace)
                } else {
                    val attachmentId = UUID.randomUUID().toString()
                    val relativePath = "skill-install-$attachmentId"
                    val target = paths.attachmentFile(relativePath)
                    if (!target.createNewFile()) throw StError.Conflict("skill asset file already exists")
                    owned += target
                    val size = FileOutputStream(target).use { output ->
                        val copied = if (bytes != null) { output.write(bytes); bytes.size.toLong() }
                            else zip.getInputStream(file.entry).use { copy(it, output, MAX_PACKAGE_BYTES - totalBytes) }
                        output.fd.sync(); copied
                    }
                    totalBytes += size
                    val filename = file.path.substringAfterLast('/')
                    val mime = AttachmentMimeTypes.fromFilename(filename) ?: MimeTypeMap.getSingleton()
                        .getMimeTypeFromExtension(filename.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"
                    rows += AttachmentEntity(attachmentId, relativePath, filename, mime, size, null, null, null, at)
                    prepared += SkillFile(parsed.id, file.path, attachment = AttachmentRef(attachmentId, filename, mime, relativePath, size),
                        updatedAt = at, sourceNamespace = config.sourceNamespace)
                }
                if (totalBytes > MAX_PACKAGE_BYTES) throw StError.Validation("skill package exceeds 256 MiB expanded")
            }
            currentCoroutineContext().ensureActive()
            withContext(NonCancellable) { content.transaction {
                val current = content.getAssistant(config.id) ?: throw StError.NotFound("assistant configuration")
                if (current.sourceNamespace != config.sourceNamespace) throw StError.Conflict("assistant skill library changed during installation")
                var directory = requested
                var suffix = 2
                while (content.skills.get(scope, directory) != null) directory = "$requested-${suffix++}"
                val definition = parsed.copy(id = SkillStore.identity(scope, directory), directory = directory)
                rows.forEach { attachments.upsert(it) }
                prepared.forEach { content.skills.saveFile(it.copy(skillId = definition.id)) }
                content.skills.save(definition)
                SkillInstallResult(definition, prepared.size, directory != requested)
            }.also { committed = true } }
        } finally {
            withContext(NonCancellable) {
                if (!committed) owned.forEach { it.delete() }
                lease.close()
            }
        }
    }

    private suspend fun readTextBytes(input: InputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        copy(input, output, AssistantWorkspaceStore.MAX_TEXT_BYTES.toLong())
        return output.toByteArray()
    }
    private suspend fun copy(input: InputStream, output: java.io.OutputStream, limit: Long): Long {
        var bytes = 0L
        val buffer = ByteArray(65536)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            bytes += count
            if (bytes > limit) throw StError.Validation("skill package input exceeds its size limit")
            output.write(buffer, 0, count)
        }
        return bytes
    }
    private suspend fun download(initial: HttpUrl, target: File) {
        var url = initial
        repeat(3) {
            currentCoroutineContext().ensureActive()
            val request = Request.Builder().url(url).header("User-Agent", "SimpleTavern-Android")
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2026-03-10").build()
            val call = http.newCall(request)
            val next = coroutineScope {
                val stopping = launch(start = CoroutineStart.UNDISPATCHED) { try { awaitCancellation() } finally { call.cancel() } }
                try { call.execute().use { response ->
                    if (response.code in setOf(301, 302, 303, 307, 308)) {
                        val redirect = response.header("Location")?.let(url::resolve) ?: throw StError.Provider("GitHub archive redirect missing")
                        if (redirect.scheme != "https" || redirect.host !in setOf("api.github.com", "codeload.github.com") ||
                            redirect.username.isNotEmpty() || redirect.password.isNotEmpty()) throw StError.Provider("unsupported GitHub archive redirect")
                        redirect
                    } else {
                        if (!response.isSuccessful) throw StError.Provider("GitHub skill archive HTTP ${response.code}")
                        val body = response.body ?: throw StError.Provider("GitHub archive body missing")
                        FileOutputStream(target).use { output -> body.byteStream().use { copy(it, output, MAX_ARCHIVE_BYTES) }; output.fd.sync() }
                        null
                    }
                } } catch (_: java.io.IOException) {
                    currentCoroutineContext().ensureActive(); throw StError.Provider("GitHub skill archive download failed")
                } finally { stopping.cancel() }
            }
            if (next == null) return
            url = next
        }
        throw StError.Provider("too many GitHub archive redirects")
    }

    companion object {
        const val MAX_ARCHIVE_BYTES = 128L * 1024 * 1024
        const val MAX_PACKAGE_BYTES = 256L * 1024 * 1024
    }
}

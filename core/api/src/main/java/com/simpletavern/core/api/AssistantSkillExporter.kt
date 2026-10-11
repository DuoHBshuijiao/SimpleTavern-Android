package com.simpletavern.core.api

import android.content.ContentResolver
import android.net.Uri
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.DataOperationGate
import com.simpletavern.core.data.SkillStore
import com.simpletavern.core.model.StError
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Export one source-scoped package, not a database backup or a script execution. */
internal class AssistantSkillExporter(
    private val content: ContentRepository,
    private val chatAttachments: ChatAttachmentService,
    private val gate: DataOperationGate,
    private val cacheDirectory: File,
    resolver: ContentResolver,
) {
    private val documents = SafDocumentOutput(resolver)

    suspend fun export(assistantId: String, directory: String, destination: Uri): Uri = withContext(Dispatchers.IO) {
        SafDocumentOutput.validate(destination)
        val stage = cacheDirectory.resolve("skill-export-${UUID.randomUUID()}")
        if (!stage.mkdir()) throw StError.Conflict("skill export staging unavailable")
        val archive = stage.resolve("skill.zip")
        try {
            gate.write {
                val (definition, files) = content.transaction {
                    val config = content.getAssistant(assistantId) ?: throw StError.NotFound("assistant configuration")
                    val definition = content.skills.get(SkillStore.scope(config), directory) ?: throw StError.NotFound("skill")
                    definition to content.skills.files(definition.id).map { row ->
                        content.skills.getFile(definition.id, row.path) ?: throw StError.NotFound("skill file")
                    }
                }
                if (files.none { it.path == "SKILL.md" }) throw StError.NotFound("skill root document")
                val includeBuiltinLicense = definition.builtin && definition.directory == "builtin-skill-creator" &&
                    definition.name == "skill-creator" && files.none { it.path == "LICENSE.txt" }
                if (files.size + (if (includeBuiltinLicense) 1 else 0) > 512)
                    throw StError.Validation("skill export exceeds the installable 512-file package limit")
                var bytes = 0L
                ZipOutputStream(archive.outputStream()).use { zip ->
                    suspend fun add(path: String, input: InputStream) {
                        currentCoroutineContext().ensureActive()
                        // These paths already belong to the validated library; preserve them and the original bytes.
                        zip.putNextEntry(ZipEntry("${definition.directory}/$path"))
                        val buffer = ByteArray(65_536)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (bytes > AssistantSkillInstaller.MAX_PACKAGE_BYTES - count)
                                throw StError.Validation("skill export exceeds 256 MiB expanded data")
                            zip.write(buffer, 0, count)
                            bytes += count
                        }
                        zip.closeEntry()
                    }
                    for (file in files.sortedBy { it.path }) {
                        val text = file.content
                        val input = if (text != null) text.toByteArray(Charsets.UTF_8).inputStream()
                            else chatAttachments.copySource(file.attachment?.id ?: throw StError.NotFound("skill asset")).inputStream()
                        input.use { add(file.path, it) }
                    }
                    if (includeBuiltinLicense) {
                        val license = AssistantSkillExporter::class.java.getResourceAsStream("/simpletavern/rikka-builtin/LICENSE.txt")
                            ?: throw StError.NotFound("bundled skill license")
                        license.use { add("LICENSE.txt", it) }
                    }
                }
                if (archive.length() > AssistantSkillInstaller.MAX_ARCHIVE_BYTES)
                    throw StError.Validation("skill ZIP exceeds the installable 128 MiB archive limit")
            }
            // The complete ZIP no longer depends on live library bytes; provider I/O does not own the data gate.
            currentCoroutineContext().ensureActive()
            archive.inputStream().use { input ->
                documents.write(input, destination, AssistantSkillInstaller.MAX_ARCHIVE_BYTES, copied = {}, closed = {})
            }
            destination
        } finally {
            withContext(NonCancellable) {
                if (archive.exists() && !archive.delete()) throw StError.Conflict("skill export cache cleanup failed")
                if (!stage.delete()) throw StError.Conflict("skill export staging cleanup failed")
            }
        }
    }
}

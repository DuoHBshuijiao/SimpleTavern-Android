package com.simpletavern.core.importer

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import java.io.File

/** Files come from the existing expanded archive staging area, never from host skill mounts. */
internal class SkillImport(private val store: SkillStore, private val merge: ImportMerge,
    private val items: MutableList<ImportReportItem>, private val checkCancelled: suspend () -> Unit) {
    suspend fun run(root: File) {
        if (!root.isDirectory) return
        for (folder in root.listFiles().orEmpty().filter { it.isDirectory && !it.name.startsWith('.') }.sortedBy { it.name }) {
            checkCancelled()
            val skillText = text(folder.resolve("SKILL.md"))
            if (skillText == null) { items += ImportReportItem("skill", folder.name, null, "missing", "SKILL.md must be UTF-8 text no larger than 256 KiB"); continue }
            val source = try { SkillStore.definition(merge.namespace, folder.name, skillText, IMPORT_EPOCH, merge.namespace) }
                catch (error: StError.Validation) { items += ImportReportItem("skill", folder.name, null, "retained", error.message); continue }
            val permitted = merge.merge("source_skill", source.id, source,
                store.get(source.scope, source.directory)?.copy(updatedAt = IMPORT_EPOCH)) ?: continue
            for (file in folder.walkTopDown().filter { it.isFile }.sortedBy { it.invariantSeparatorsPath }) {
                checkCancelled()
                val path = AssistantWorkspaceStore.normalize(file.relativeTo(folder).invariantSeparatorsPath)
                val body = if (!SkillFileTypes.isBinary(file.name)) text(file) else null
                // Each binary source revision has its own bytes. Equality/reuse is not guessed with hashes.
                val attachment = if (body == null) merge.attachment("skill-asset:${folder.name}/$path:${ImportService.newId()}", file, originalName = file.name) else null
                if (body == null && attachment == null) continue
                val incoming = SkillFile(source.id, path, body, attachment, IMPORT_EPOCH, merge.namespace)
                val current = store.getFile(source.id, path)
                val normalized = current?.copy(updatedAt = IMPORT_EPOCH)
                merge.merge("skill_file", SkillStore.fileIdentity(source.id, path), incoming, normalized)?.let {
                    if (it != normalized) store.saveFile(it.copy(updatedAt = ImportService.nowIso()))
                }
            }
            val actual = store.getFile(source.id, "SKILL.md")?.content ?: continue
            // Metadata describes the merged document, including local frontmatter edits, not a second independent copy.
            store.save(SkillStore.definition(permitted.scope, permitted.directory, actual,
                store.getFile(source.id, "SKILL.md")!!.updatedAt, merge.namespace))
            items += ImportReportItem("skill", folder.name, source.id, "converted", "source-scoped documents/assets; no scripts executed")
        }
    }
    private fun text(file: File): String? {
        if (!file.isFile || file.length() > AssistantWorkspaceStore.MAX_TEXT_BYTES) return null
        return SkillFileTypes.text(file.readBytes())
    }

    /** Pinned APK resource fills the builtin omitted by source backups; no network or command execution. */
    suspend fun restoreBuiltin() {
        checkCancelled()
        val directory = "builtin-skill-creator"
        val document = SkillImport::class.java.getResourceAsStream("/simpletavern/rikka-builtin/skill-creator/SKILL.md")
            ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            ?: throw StError.Validation("packaged RikkaHub builtin skill unavailable")
        val source = SkillStore.definition(merge.namespace, directory, document, IMPORT_EPOCH, merge.namespace).copy(builtin = true)
        val permitted = merge.merge("source_skill", source.id, source,
            store.get(source.scope, directory)?.copy(updatedAt = IMPORT_EPOCH)) ?: return
        // A user-created record occupying this reserved directory must not be reclassified as builtin.
        if (!permitted.builtin) return
        val incoming = SkillFile(source.id, "SKILL.md", document, updatedAt = IMPORT_EPOCH, sourceNamespace = merge.namespace)
        val current = store.getFile(source.id, "SKILL.md")?.copy(updatedAt = IMPORT_EPOCH)
        merge.merge("skill_file", SkillStore.fileIdentity(source.id, "SKILL.md"), incoming, current)?.let {
            if (it != current) store.saveFile(it.copy(updatedAt = ImportService.nowIso()))
        }
        val actual = store.getFile(source.id, "SKILL.md") ?: return
        store.save(SkillStore.definition(permitted.scope, directory, actual.content!!, actual.updatedAt, merge.namespace).copy(builtin = true))
        items += ImportReportItem("skill", "builtin:skill-creator", source.id, "converted",
            "pinned RikkaHub builtin restored offline; same-name user skills take precedence; no scripts executed")
    }
}

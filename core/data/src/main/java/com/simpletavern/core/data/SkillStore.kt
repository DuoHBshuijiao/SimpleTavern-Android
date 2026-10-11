package com.simpletavern.core.data

import com.simpletavern.core.data.db.AppDatabase
import com.simpletavern.core.data.db.SourceBlobEntity
import com.simpletavern.core.model.*
import kotlinx.serialization.json.*
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor

/** Source-scoped skill documents and managed asset references, not mounted executable directories. */
class SkillStore(private val db: AppDatabase) {
    suspend fun list(scope: String): List<SkillDefinition> = db.imports().sourceBlobs(namespace(scope))
        .map { Jsonx.decode<SkillDefinition>(it.contentJson) }
    suspend fun get(scope: String, directory: String): SkillDefinition? = db.imports().getBlob(identity(scope, directory))
        ?.let { Jsonx.decode(it.contentJson) }
    suspend fun save(skill: SkillDefinition) = db.imports().upsertBlob(SourceBlobEntity(skill.id, namespace(skill.scope),
        directory(skill.directory), Jsonx.encode(skill), skill.updatedAt))
    suspend fun getFile(skillId: String, path: String): SkillFile? = db.imports().getBlob(fileIdentity(skillId, path))
        ?.let { Jsonx.decode(it.contentJson) }
    suspend fun files(skillId: String): List<WorkspaceFileInfo> {
        val rows = mutableListOf<WorkspaceFileInfo>()
        var after = ""
        do {
            val page = db.imports().workspaceFiles(fileNamespace(skillId), after, 200)
            rows.addAll(page)
            after = page.lastOrNull()?.path ?: after
        } while (page.size == 200)
        return rows
    }
    suspend fun saveFile(file: SkillFile) {
        if ((file.content == null) == (file.attachment == null)) throw StError.Validation("skill file must contain text or one managed attachment")
        file.content?.let(AssistantWorkspaceStore::validateContent)
        db.imports().upsertBlob(SourceBlobEntity(fileIdentity(file.skillId, file.path), fileNamespace(file.skillId),
            AssistantWorkspaceStore.normalize(file.path), Jsonx.encode(file), file.updatedAt))
    }
    suspend fun deleteFile(skillId: String, path: String) = db.imports().deleteBlob(fileIdentity(skillId, path))
    suspend fun delete(skill: SkillDefinition) {
        files(skill.id).forEach { deleteFile(skill.id, it.path) }
        db.imports().deleteBlob(skill.id) // Import baselines intentionally remain to preserve local deletion.
    }
    suspend fun enabled(config: AssistantConfig): List<SkillDefinition> {
        val names = (Jsonx.decode<JsonObject>(config.settingsJson)["enabledSkills"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.toSet()
        if (names.isEmpty()) return emptyList()
        val skills = list(scope(config))
        val userNames = skills.filter { !it.builtin }.map { it.name }.toSet()
        return skills.filter { it.name in names && (!it.builtin || it.name !in userNames) }
    }
    suspend fun prompt(config: AssistantConfig): String {
        val skills = enabled(config)
        if (skills.isEmpty()) return ""
        return buildString {
            append("Available skills: call use_skill(name, path?, directory?) to read instructions or referenced files. When stage_skill is available and sandbox scripts/resources are needed, call it to copy the whole skill and use its returned guestPath. Copying does not execute scripts; use separately enabled sandbox tools.\n<available_skills>\n")
            for (skill in skills) append("<skill><name>${xml(skill.name)}</name><directory>${xml(skill.directory)}</directory><description>${xml(skill.description.take(1024))}</description></skill>\n")
            append("</available_skills>")
        }
    }
    companion object {
        const val LOCAL_SCOPE = "local"
        fun scope(config: AssistantConfig) = config.sourceNamespace ?: LOCAL_SCOPE
        fun namespace(scope: String) = "skill-catalog:${scope.length}:$scope"
        fun identity(scope: String, directory: String) = "${namespace(scope)}:${directory(directory)}"
        fun fileNamespace(skillId: String) = "skill-files:${skillId.length}:$skillId"
        fun fileIdentity(skillId: String, path: String) = "${fileNamespace(skillId)}:${AssistantWorkspaceStore.normalize(path)}"
        fun directory(value: String): String = AssistantWorkspaceStore.normalize(value).also {
            if ('/' in it || it.startsWith('.')) throw StError.Validation("skill directory must be one non-hidden folder name")
        }
        fun definition(scope: String, directory: String, text: String, at: String, sourceNamespace: String? = null): SkillDefinition {
            AssistantWorkspaceStore.validateContent(text)
            val raw = text.removePrefix("\uFEFF")
            if (!raw.startsWith("---\n") && !raw.startsWith("---\r\n")) throw StError.Validation("SKILL.md requires YAML frontmatter")
            val end = Regex("\\r?\\n---(?:\\r?\\n|$)").find(raw, 3) ?: throw StError.Validation("skill frontmatter is not closed")
            val options = LoaderOptions().apply { isAllowDuplicateKeys = false; maxAliasesForCollections = 50; nestingDepthLimit = 50 }
            val fields = try { Yaml(SafeConstructor(options)).load<Any?>(raw.substring(3, end.range.first)) as? Map<*, *> }
                catch (_: Exception) { throw StError.Validation("invalid skill YAML frontmatter") }
            val name = (fields?.get("name") as? String)?.takeIf { it.isNotBlank() } ?: throw StError.Validation("skill name is required")
            val description = (fields["description"] as? String)?.takeIf { it.isNotBlank() } ?: throw StError.Validation("skill description is required")
            return SkillDefinition(identity(scope, directory), scope, directory(directory), name, description,
                fields["compatibility"] as? String, at, sourceNamespace)
        }
        fun body(text: String): String {
            val raw = text.removePrefix("\uFEFF")
            val end = Regex("\\r?\\n---(?:\\r?\\n|$)").find(raw, 3) ?: return raw
            return raw.substring(end.range.last + 1).trimStart('\r', '\n')
        }
        fun xml(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

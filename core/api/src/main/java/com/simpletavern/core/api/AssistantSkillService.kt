package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolResult
import java.time.Instant
import java.util.UUID
import com.simpletavern.runtime.sandbox.SandboxManager
import kotlinx.coroutines.*
import kotlinx.serialization.json.*

internal class AssistantSkillService(private val content: ContentRepository, private val attachments: AttachmentStore,
    private val gate: DataOperationGate, private val installer: AssistantSkillInstaller,
    private val sandbox: SandboxManager, private val copies: FileCopyTaskService, private val chatAttachments: ChatAttachmentService) {
    private val store = content.skills
    suspend fun install(assistantId: String, archive: java.io.File, options: SkillInstallOptions) = installer.install(assistantId, archive, options)
    suspend fun install(assistantId: String, archive: android.net.Uri, options: SkillInstallOptions) = installer.install(assistantId, archive, options)
    suspend fun install(assistantId: String, source: GitHubSkillSource, options: SkillInstallOptions) = installer.install(assistantId, source, options)
    private suspend fun config(id: String) = content.getAssistant(id) ?: throw StError.NotFound("assistant configuration")
    private fun names(config: AssistantConfig) = (Jsonx.decode<JsonObject>(config.settingsJson)["enabledSkills"] as? JsonArray)
        .orEmpty().map { it.jsonPrimitive.content }.toSet()
    private suspend fun skill(config: AssistantConfig, directory: String) = store.get(SkillStore.scope(config), directory)
        ?: throw StError.NotFound("skill")
    private fun writable(skill: SkillDefinition?) {
        if (skill?.builtin == true) throw StError.Validation("builtin skill is read-only; create a user skill with the same name to customize it")
    }
    suspend fun list(assistantId: String): List<AssistantSkillInfo> = gate.write {
        val config = config(assistantId)
        val enabled = store.enabled(config).map { it.id }.toSet()
        store.list(SkillStore.scope(config)).map { AssistantSkillInfo(it, it.id in enabled) }
    }
    suspend fun files(assistantId: String, directory: String) = gate.write { store.files(skill(config(assistantId), directory).id) }
    private suspend fun snapshot(assistantId: String, directory: String, prepared: AssistantConfig? = null): Pair<SkillDefinition, List<SkillFile>> = gate.write { content.transaction {
        val current = config(assistantId)
        val definition = skill(current, directory)
        if (prepared != null && (current.sourceNamespace != prepared.sourceNamespace || definition.name !in names(current)))
            throw StError.Conflict("skill source or enabled selection changed")
        definition to store.files(definition.id).map { file ->
            store.getFile(definition.id, file.path) ?: throw StError.NotFound("skill file")
        }
    } }
    suspend fun read(assistantId: String, directory: String, path: String = "SKILL.md"): SkillFile = gate.write {
        store.getFile(skill(config(assistantId), directory).id, path) ?: throw StError.NotFound("skill file")
    }
    suspend fun write(assistantId: String, directory: String, path: String, text: String, expected: SkillFile?): SkillFile = gate.write { content.transaction {
        val config = config(assistantId)
        val scope = SkillStore.scope(config)
        val currentSkill = store.get(scope, directory)
        writable(currentSkill)
        val normalized = AssistantWorkspaceStore.normalize(path)
        if (currentSkill == null && normalized != "SKILL.md") throw StError.NotFound("create SKILL.md first")
        val id = SkillStore.identity(scope, directory)
        if (store.getFile(id, normalized) != expected) throw StError.Conflict("skill file changed")
        val at = Instant.now().toString()
        val definition = if (normalized == "SKILL.md") SkillStore.definition(scope, directory, text, at, config.sourceNamespace) else null
        val file = SkillFile(id, normalized, text, updatedAt = at, sourceNamespace = config.sourceNamespace)
        store.saveFile(file)
        definition?.let { store.save(it) }
        if (definition != null && currentSkill != null && currentSkill.name != definition.name) replaceSelection(scope, currentSkill.name, definition.name)
        file
    } }
    suspend fun link(assistantId: String, directory: String, path: String, attachmentId: String, expected: SkillFile?): SkillFile = gate.write { content.transaction {
        val config = config(assistantId)
        val skill = skill(config, directory)
        writable(skill)
        val normalized = AssistantWorkspaceStore.normalize(path)
        if (normalized == "SKILL.md") throw StError.Validation("SKILL.md must be editable UTF-8 text")
        if (store.getFile(skill.id, normalized) != expected) throw StError.Conflict("skill file changed")
        val row = attachments.get(attachmentId) ?: throw StError.NotFound("registered attachment")
        val file = SkillFile(skill.id, normalized, attachment = AttachmentRef(row.id, row.filename, row.mimeType, row.relativePath, row.size),
            updatedAt = Instant.now().toString(), sourceNamespace = config.sourceNamespace)
        store.saveFile(file)
        file
    } }
    suspend fun deleteFile(assistantId: String, directory: String, expected: SkillFile) = gate.write { content.transaction {
        val config = config(assistantId)
        val skill = skill(config, directory)
        writable(skill)
        if (store.getFile(skill.id, expected.path) != expected) throw StError.Conflict("skill file changed")
        if (expected.path == "SKILL.md") {
            store.delete(skill)
            replaceSelection(skill.scope, skill.name, null)
        } else store.deleteFile(skill.id, expected.path)
    } }
    suspend fun delete(assistantId: String, directory: String, expected: SkillDefinition) = gate.write { content.transaction {
        val skill = skill(config(assistantId), directory)
        writable(skill)
        if (skill != expected) throw StError.Conflict("skill changed")
        store.delete(skill)
        replaceSelection(skill.scope, skill.name, null)
    } }
    private suspend fun replaceSelection(scope: String, oldName: String, replacement: String?) {
        if (store.list(scope).any { it.name == oldName }) return
        for (config in content.listAssistants().filter { SkillStore.scope(it) == scope }) {
            val enabled = names(config)
            if (oldName !in enabled) continue
            val next = enabled - oldName + listOfNotNull(replacement)
            val settings = JsonObject(Jsonx.decode<JsonObject>(config.settingsJson) + ("enabledSkills" to JsonArray(next.map(::JsonPrimitive))))
            content.saveAssistant(config.copy(settingsJson = settings.toString(), updatedAt = Instant.now().toString()))
        }
    }
    suspend fun enable(assistantId: String, enabledNames: Set<String>, expectedUpdatedAt: String): AssistantConfig = gate.write { content.transaction {
        val current = config(assistantId)
        if (current.updatedAt != expectedUpdatedAt) throw StError.Conflict("assistant configuration changed")
        val available = store.list(SkillStore.scope(current)).map { it.name }.toSet()
        if (!available.containsAll(enabledNames)) throw StError.NotFound("enabled skill missing from this source library")
        val settings = JsonObject(Jsonx.decode<JsonObject>(current.settingsJson) + ("enabledSkills" to JsonArray(enabledNames.map(::JsonPrimitive))))
        current.copy(settingsJson = settings.toString(), updatedAt = Instant.now().toString()).also { content.saveAssistant(it) }
    } }

    /** Shared by explicit Core copying and the permission-bound model staging tool. */
    suspend fun copy(assistantId: String, directory: String, sandboxId: String, targetDirectory: String? = null,
                     prepared: AssistantConfig? = null): SkillSandboxCopyResult {
        var reservation: AutoCloseable? = null
        var activeTaskId: String? = null
        try {
            withContext(NonCancellable) { reservation = sandbox.reserveOperation(sandboxId) }
            val (definition, files) = snapshot(assistantId, directory, prepared)
            if (files.size > 512) throw StError.Validation("skill copy exceeds 512 files")
            val sourceFiles = files.filter { it.content == null }.associate { it.path to chatAttachments.copySource(it.attachment!!.id) }
            val bytes = withContext(Dispatchers.IO) {
                files.sumOf { it.content?.toByteArray(Charsets.UTF_8)?.size?.toLong() ?: sourceFiles.getValue(it.path).length() }
            }
            if (bytes > AssistantSkillInstaller.MAX_PACKAGE_BYTES) throw StError.Validation("skill copy exceeds 256 MiB")
            val target = AssistantWorkspaceStore.normalize(targetDirectory ?: "home/skills/${definition.directory}-${UUID.randomUUID()}")
            val existing = sandbox.validateCopyTarget(sandboxId, target)
            val exists = withContext(Dispatchers.IO) {
                try { android.system.Os.lstat(existing.path); true }
                catch (error: android.system.ErrnoException) { if (error.errno == android.system.OsConstants.ENOENT) false else throw error }
            }
            if (exists) throw StError.Conflict("select a new skill copy directory")
            for (file in files) sandbox.validateCopyTarget(sandboxId, "$target/${file.path}")
            val copied = mutableListOf<SkillSandboxCopiedFile>()
            for (file in files.sortedWith(compareBy<SkillFile> { it.path != "SKILL.md" }.thenBy { it.path })) {
                currentCoroutineContext().ensureActive()
                try {
                    withContext(NonCancellable) {
                        val task = if (file.content != null) copies.startWorkspaceText(sandboxId, file.content!!, "$target/${file.path}", exactTarget = true)
                            else copies.start(sandboxId, sourceFiles.getValue(file.path), "$target/${file.path}", exactTarget = true)
                        activeTaskId = task.id
                    }
                    copies.await(activeTaskId!!)
                    copied += SkillSandboxCopiedFile(file.path, copies.progress(activeTaskId!!))
                    activeTaskId = null
                } catch (error: CancellationException) { throw error }
                catch (error: Exception) {
                    currentCoroutineContext().ensureActive()
                    activeTaskId?.let { copied += SkillSandboxCopiedFile(file.path, copies.progress(it)) }
                    activeTaskId = null
                    return SkillSandboxCopyResult(definition.id, sandboxId, target, copied, false, file.path,
                        (error as? StError)?.message ?: "skill directory copy failed")
                }
            }
            return SkillSandboxCopyResult(definition.id, sandboxId, target, copied, true)
        } finally {
            withContext(NonCancellable) {
                try { activeTaskId?.let { copies.stop(it, TaskTerminalReason.USER_STOP) } }
                finally { reservation?.close() }
            }
        }
    }

    suspend fun tools(config: AssistantConfig, sandboxId: String? = null) = AssistantSkillTools(this, config, store.enabled(config), sandboxId)
    private suspend fun selected(prepared: AssistantConfig, arguments: JsonObject): SkillDefinition {
        val current = config(prepared.id)
        if (current.sourceNamespace != prepared.sourceNamespace) throw StError.Conflict("skill source changed")
        val name = arguments.getValue("name").jsonPrimitive.content
        val directory = (arguments["directory"] as? JsonPrimitive)?.contentOrNull
        val candidates = store.enabled(current).filter { (it.name == name || SkillStore.xml(it.name) == name) && (directory == null || it.directory == directory) }
        if (candidates.isEmpty()) throw StError.NotFound("skill is not enabled for this assistant")
        if (candidates.size != 1) throw StError.Validation("skill name is ambiguous; provide directory from the available skills list")
        return candidates.single()
    }

    suspend fun stage(prepared: AssistantConfig, sandboxId: String, arguments: JsonObject): ToolResult {
        val definition = gate.write { selected(prepared, arguments) }
        val result = copy(prepared.id, definition.directory, sandboxId, prepared = prepared)
        val output = buildJsonObject {
            put("ok", result.completed); put("tool", "stage_skill")
            put("data", buildJsonObject {
                put("name", definition.name); put("directory", definition.directory); put("sandboxId", result.sandboxId)
                put("targetDirectory", result.targetDirectory); put("guestPath", "/${result.targetDirectory}")
                put("completed", result.completed); put("copiedFiles", result.files.count { it.progress.published })
                put("taskIds", JsonArray(result.files.map { JsonPrimitive(it.progress.taskId) }))
                result.failedPath?.let { put("failedPath", it) }; result.error?.let { put("error", it) }
                put("note", "Copied snapshot, not a mount. Scripts were not executed; run them separately with the selected sandbox tools.")
            })
        }
        return ToolResult(output.toString(), buildJsonObject {
            put("toolName", "stage_skill"); put("skillId", result.skillId); put("sandboxId", result.sandboxId)
            put("targetDirectory", result.targetDirectory); put("ok", result.completed)
            put("taskIds", JsonArray(result.files.map { JsonPrimitive(it.progress.taskId) }))
        }.toString())
    }
    suspend fun use(prepared: AssistantConfig, arguments: JsonObject): ToolResult = gate.write {
        val skill = selected(prepared, arguments)
        val path = (arguments["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        val file = store.getFile(skill.id, path ?: "SKILL.md") ?: throw StError.NotFound("skill file")
        val data = buildJsonObject {
            put("name", skill.name); put("directory", skill.directory); put("path", file.path)
            put("builtin", skill.builtin)
            if (skill.builtin && file.path == "SKILL.md") put("platformNote",
                "This is the original RikkaHub builtin document. In SimpleTavern, use skill_list/skill_files/skill_read_file/skill_write_file/skill_set_enabled instead of /skills filesystem edits or RikkaHub workspace_* names. Skill documents are stored in the source-scoped library, not a live /skills mount. Builtins are read-only: create a different directory with the same YAML name to override. Do not assume a Skills tab exists. Use stage_skill for an explicit sandbox snapshot and its returned guestPath for scripts; execution requires separately enabled shell tools. Installation does not run scripts or auto-enable names; an already-enabled same name can apply to a new user override.")
            file.content?.let { put("content", if (path == null) SkillStore.body(it) else it) }
            file.attachment?.let { put("attachment", Jsonx.json.parseToJsonElement(Jsonx.encode(it)))
                put("note", "Managed asset; not text instructions. Use stage_skill when available to copy the whole skill to the selected sandbox before shell use.") }
        }
        val output = buildJsonObject { put("ok", true); put("tool", "use_skill"); put("data", data) }.toString()
        if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("skill result exceeds tool text budget")
        ToolResult(output, buildJsonObject { put("toolName", "use_skill"); put("skillId", skill.id); put("path", file.path); put("ok", true) }.toString())
    }

    suspend fun manage(prepared: AssistantConfig, name: String, args: JsonObject): ToolResult = gate.write {
        val current = config(prepared.id)
        if (current.sourceNamespace != prepared.sourceNamespace) throw StError.Conflict("skill source changed")
        if (store.enabled(current).isEmpty()) throw StError.Conflict("assistant skill tools are no longer enabled")
        fun required(key: String) = args.getValue(key).jsonPrimitive.content
        val data = when (name) {
            "skill_list" -> buildJsonObject {
                put("configUpdatedAt", current.updatedAt)
                put("enabledNames", JsonArray(names(current).map(::JsonPrimitive)))
                put("skills", Jsonx.json.parseToJsonElement(Jsonx.encode(list(current.id))))
            }
            "skill_files" -> Jsonx.json.parseToJsonElement(Jsonx.encode(files(current.id, required("directory"))))
            "skill_read_file" -> Jsonx.json.parseToJsonElement(Jsonx.encode(read(current.id, required("directory"), required("path"))))
            "skill_write_file" -> {
                val directory = required("directory")
                val path = required("path")
                val expectedText = (args["expectedContent"] as? JsonPrimitive)?.contentOrNull
                val expected = if (expectedText != null) read(current.id, directory, path).also {
                    if (it.content != expectedText || it.updatedAt != required("expectedUpdatedAt")) throw StError.Conflict("skill file changed")
                } else null
                Jsonx.json.parseToJsonElement(Jsonx.encode(write(current.id, directory, path, required("content"), expected)))
            }
            "skill_link_asset" -> {
                val directory = required("directory")
                val path = required("path")
                val updatedAt = (args["expectedUpdatedAt"] as? JsonPrimitive)?.contentOrNull
                val expectedAttachmentId = (args["expectedAttachmentId"] as? JsonPrimitive)?.contentOrNull
                val expectedContent = (args["expectedContent"] as? JsonPrimitive)?.contentOrNull
                val editing = updatedAt != null || expectedAttachmentId != null || expectedContent != null
                if (editing && (updatedAt == null || (expectedAttachmentId == null) == (expectedContent == null)))
                    throw StError.Validation("asset replacement requires expectedUpdatedAt and exactly one of expectedAttachmentId or expectedContent")
                val expected = if (editing) read(current.id, directory, path).also {
                    val matchesPrevious = if (expectedAttachmentId != null) it.attachment?.id == expectedAttachmentId
                        else it.content == expectedContent
                    if (it.updatedAt != updatedAt || !matchesPrevious) throw StError.Conflict("skill file changed")
                } else null
                link(current.id, directory, path, required("attachmentId"), expected)
                buildJsonObject { put("linked", true); put("directory", directory); put("path", AssistantWorkspaceStore.normalize(path)) }
            }
            "skill_delete_file" -> {
                val directory = required("directory")
                val path = required("path")
                val expectedUpdatedAt = required("expectedUpdatedAt")
                val expectedContent = (args["expectedContent"] as? JsonPrimitive)?.contentOrNull
                val expectedAttachmentId = (args["expectedAttachmentId"] as? JsonPrimitive)?.contentOrNull
                val expected = read(current.id, directory, path)
                val matchesPrevious = if (expected.content != null) expectedContent != null && expected.content == expectedContent
                    else expectedAttachmentId != null && expected.attachment?.id == expectedAttachmentId
                if (expected.updatedAt != expectedUpdatedAt || !matchesPrevious) throw StError.Conflict("skill file changed")
                deleteFile(current.id, directory, expected)
                buildJsonObject { put("deleted", true); put("directory", directory); put("path", expected.path) }
            }
            "skill_delete" -> {
                val directory = required("directory")
                val expectedUpdatedAt = required("expectedUpdatedAt")
                val definition = skill(current, directory)
                if (definition.updatedAt != expectedUpdatedAt) throw StError.Conflict("skill changed")
                delete(current.id, directory, definition)
                buildJsonObject { put("deleted", true); put("directory", definition.directory) }
            }
            "skill_set_enabled" -> {
                val enabled = args.getValue("names").jsonArray.map { it.jsonPrimitive.content }.toSet()
                val next = enable(current.id, enabled, required("expectedUpdatedAt"))
                buildJsonObject { put("configUpdatedAt", next.updatedAt); put("enabledNames", JsonArray(enabled.map(::JsonPrimitive))) }
            }
            else -> throw StError.Unsupported("unknown skill management tool")
        }
        val output = buildJsonObject { put("ok", true); put("tool", name); put("data", data) }.toString()
        if (output.toByteArray(Charsets.UTF_8).size > 1_000_000) throw StError.Validation("skill result exceeds tool text budget")
        ToolResult(output, buildJsonObject { put("toolName", name); put("ok", true) }.toString())
    }
}

internal class AssistantSkillTools(private val service: AssistantSkillService, private val config: AssistantConfig,
    skills: List<SkillDefinition>, private val sandboxId: String?) {
    val categories = if (skills.isEmpty()) emptyMap() else mapOf("use_skill" to ApprovalCategory.READ,
        "skill_list" to ApprovalCategory.READ, "skill_files" to ApprovalCategory.READ, "skill_read_file" to ApprovalCategory.READ,
        "skill_write_file" to ApprovalCategory.WRITE, "skill_link_asset" to ApprovalCategory.WRITE,
        "skill_set_enabled" to ApprovalCategory.EDIT) +
        mapOf("skill_delete_file" to ApprovalCategory.EDIT, "skill_delete" to ApprovalCategory.EDIT) +
        (if (sandboxId != null) mapOf("stage_skill" to ApprovalCategory.WRITE) else emptyMap())
    fun category(name: String, args: JsonObject): ApprovalCategory? = when (name) {
        "skill_write_file" -> {
            val editing = args["expectedContent"] != null
            if (editing != (args["expectedUpdatedAt"] != null)) throw StError.Validation("skill edits require both expectedContent and expectedUpdatedAt")
            if (editing) ApprovalCategory.EDIT else ApprovalCategory.WRITE
        }
        "skill_link_asset" -> {
            val updatedAt = (args["expectedUpdatedAt"] as? JsonPrimitive)?.contentOrNull != null
            val priorAsset = (args["expectedAttachmentId"] as? JsonPrimitive)?.contentOrNull != null
            val priorText = (args["expectedContent"] as? JsonPrimitive)?.contentOrNull != null
            val editing = updatedAt || priorAsset || priorText
            if (editing && (!updatedAt || priorAsset == priorText))
                throw StError.Validation("asset replacement requires expectedUpdatedAt and exactly one of expectedAttachmentId or expectedContent")
            if (editing) ApprovalCategory.EDIT else ApprovalCategory.WRITE
        }
        else -> categories[name]
    }
    val definitions = JsonArray(if (skills.isEmpty()) emptyList() else listOf(buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", "use_skill"); put("description", "按需读取已启用技能的说明或技能内引用文件。不执行脚本，不读取其他来源。path不传读取去除frontmatter的正文；同名技能可用directory精确选择。")
            put("parameters", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { for (key in listOf("name", "path", "directory")) put(key, buildJsonObject { put("type", "string") }) })
                put("required", JsonArray(listOf(JsonPrimitive("name"))))
            })
        })
    }) + managementDefinitions() + if (sandboxId == null) emptyList() else listOf(buildJsonObject {
        put("type", "function"); put("function", buildJsonObject {
            put("name", "stage_skill")
            put("description", "将已启用技能的说明、脚本和资源按原相对目录复制到本聊天已选沙箱的新目录，等待复制结果并返回guestPath。仅在需要沙箱内脚本/资源时调用；不执行脚本、不安装依赖、不覆盖旧目录。失败可能保留部分文件，不应当作成功。后续用shell等既有沙箱工具单独执行。")
            put("parameters", buildJsonObject {
                put("type", "object"); put("additionalProperties", false)
                put("properties", buildJsonObject { for (key in listOf("name", "directory")) put(key, buildJsonObject { put("type", "string") }) })
                put("required", JsonArray(listOf(JsonPrimitive("name"))))
            })
        })
    }))
    suspend fun dispatch(name: String, arguments: JsonObject) = when (name) {
        "use_skill" -> service.use(config, arguments)
        "stage_skill" -> service.stage(config, sandboxId ?: throw StError.Validation("selected sandbox required"), arguments)
        else -> service.manage(config, name, arguments)
    }
    private fun managementDefinitions(): List<JsonObject> {
        fun tool(name: String, description: String, fields: List<String>, required: List<String> = fields) = buildJsonObject {
            put("type", "function"); put("function", buildJsonObject {
                put("name", name); put("description", description)
                put("parameters", buildJsonObject {
                    put("type", "object"); put("additionalProperties", false)
                    put("properties", buildJsonObject { for (field in fields) put(field, buildJsonObject {
                        put("type", if (field == "names") "array" else "string")
                        if (field == "names") put("items", buildJsonObject { put("type", "string") })
                    }) })
                    put("required", JsonArray(required.map(::JsonPrimitive)))
                })
            })
        }
        return listOf(
            tool("skill_list", "列出本助手来源技能库及启用名称、configUpdatedAt。builtin=true为只读；同名用户技能覆盖内置技能。不读取其他来源。", emptyList()),
            tool("skill_files", "列出本来源指定技能的文件元数据，保留真实相对路径。", listOf("directory")),
            tool("skill_read_file", "读取本来源技能完整文本/元数据（SKILL.md包含frontmatter）。修改时使用返回content及updatedAt作为expectedContent/expectedUpdatedAt。", listOf("directory", "path")),
            tool("skill_write_file", "新建或编辑本来源用户技能文本。新技能先写SKILL.md（YAML name/description必填）；新建不传expected字段，已有文件编辑必须传最近读取的完整expectedContent及expectedUpdatedAt。内置只读；定制内置需新目录及相同name。不执行脚本、不自动启用新名称。",
                listOf("directory", "path", "content", "expectedContent", "expectedUpdatedAt"), listOf("directory", "path", "content")),
            tool("skill_link_asset", "将已登记附件关联为本来源用户技能的资源文件；仅在用户要求关联时调用。新建文件不传expected字段；覆盖必须传expectedUpdatedAt及且仅一个旧值：expectedAttachmentId（旧资源）或完整expectedContent（旧文本）。SKILL.md不能关联为binary，builtin只读；只解除/替换技能文件关联，不删除共享附件实体或字节。",
                listOf("directory", "path", "attachmentId", "expectedUpdatedAt", "expectedAttachmentId", "expectedContent"), listOf("directory", "path", "attachmentId")),
            tool("skill_delete_file", "仅在用户要求删除时删除本来源技能文件；必须提供skill_read_file/skill_files取得的expectedUpdatedAt，以及旧文本的完整expectedContent或旧资源的expectedAttachmentId。删除SKILL.md会按原规则删除整项技能。builtin只读；删除关联不会删除共享附件实体或字节。",
                listOf("directory", "path", "expectedUpdatedAt", "expectedContent", "expectedAttachmentId"), listOf("directory", "path", "expectedUpdatedAt")),
            tool("skill_delete", "仅在用户要求删除时删除本来源用户技能。expectedUpdatedAt必须来自skill_list中该SkillDefinition.updatedAt；builtin只读。",
                listOf("directory", "expectedUpdatedAt")),
            tool("skill_set_enabled", "明确设置本助手启用技能名称，names为完整集合，空集合关闭。expectedUpdatedAt来自skill_list.configUpdatedAt；仅用户要求时修改，不为创建技能自动启用。新工具配置在下一次生成生效。", listOf("names", "expectedUpdatedAt")),
        )
    }
}

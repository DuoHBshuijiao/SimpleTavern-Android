package com.simpletavern.core.data

import com.simpletavern.core.data.db.*
import com.simpletavern.core.model.*

object Mappers {
    fun toDomain(e: ChatEntity): ChatSession = ChatSession(
        id = e.id,
        characterId = e.characterId,
        title = e.title,
        userPersonaId = e.userPersonaId,
        isGroup = e.isGroup,
        memberIds = Jsonx.decode(e.memberIdsJson),
        memberSettings = Jsonx.decode(e.memberSettingsJson),
        groupDelayMs = e.groupDelayMs,
        groupSystemInjectDepth = e.groupSystemInjectDepth,
        groupSystemAlwaysAtBottom = e.groupSystemAlwaysAtBottom,
        overrides = Jsonx.decode(e.overridesJson),
        sandboxId = e.sandboxId,
        forkedFromChatId = e.forkedFromChatId,
        forkedFromMessageId = e.forkedFromMessageId,
        forkedFromMessageIndex = e.forkedFromMessageIndex,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        sourceNamespace = e.sourceNamespace,
        sourceId = e.sourceId,
        extraJson = e.extraJson,
    )

    fun fromDomain(c: ChatSession): ChatEntity = ChatEntity(
        id = c.id,
        characterId = c.characterId,
        title = c.title,
        userPersonaId = c.userPersonaId,
        isGroup = c.isGroup,
        memberIdsJson = Jsonx.encode(c.memberIds),
        memberSettingsJson = Jsonx.encode(c.memberSettings),
        groupDelayMs = c.groupDelayMs,
        groupSystemInjectDepth = c.groupSystemInjectDepth,
        groupSystemAlwaysAtBottom = c.groupSystemAlwaysAtBottom,
        overridesJson = Jsonx.encode(c.overrides),
        sandboxId = c.sandboxId,
        forkedFromChatId = c.forkedFromChatId,
        forkedFromMessageId = c.forkedFromMessageId,
        forkedFromMessageIndex = c.forkedFromMessageIndex,
        createdAt = c.createdAt,
        updatedAt = c.updatedAt,
        sourceNamespace = c.sourceNamespace,
        sourceId = c.sourceId,
        extraJson = c.extraJson,
    )

    fun toDomain(n: MessageNodeEntity, candidates: List<MessageCandidateEntity>): MessageNode =
        MessageNode(
            id = n.id,
            chatId = n.chatId,
            orderIndex = n.orderIndex,
            selectedIndex = n.selectedIndex,
            parentNodeId = n.parentNodeId,
            candidates = candidates.map { toDomain(it) },
        )

    fun toDomain(c: MessageCandidateEntity): MessageCandidate = MessageCandidate(
        id = c.id,
        role = ChatRole.valueOf(c.role),
        content = c.content,
        reasoningContent = c.reasoningContent,
        reasoningDurationSec = c.reasoningDurationSec,
        images = Jsonx.decode(c.imagesJson),
        attachments = Jsonx.decode(c.attachmentsJson),
        characterId = c.characterId,
        senderPersonaId = c.senderPersonaId,
        senderName = c.senderName,
        senderAvatar = c.senderAvatar,
        toolCallId = c.toolCallId,
        toolCallsJson = c.toolCallsJson,
        toolRecordJson = c.toolRecordJson,
        usageJson = c.usageJson,
        generationMetadataJson = c.generationMetadataJson,
        createdAt = c.createdAt,
        extraJson = c.extraJson,
    )

    fun fromDomain(nodeId: String, chatId: String, index: Int, c: MessageCandidate): MessageCandidateEntity =
        MessageCandidateEntity(
            id = c.id,
            nodeId = nodeId,
            chatId = chatId,
            candidateIndex = index,
            role = c.role.name,
            content = c.content,
            reasoningContent = c.reasoningContent,
            reasoningDurationSec = c.reasoningDurationSec,
            imagesJson = Jsonx.encode(c.images),
            attachmentsJson = Jsonx.encode(c.attachments),
            characterId = c.characterId,
            senderPersonaId = c.senderPersonaId,
            senderName = c.senderName,
            senderAvatar = c.senderAvatar,
            toolCallId = c.toolCallId,
            toolCallsJson = c.toolCallsJson,
            toolRecordJson = c.toolRecordJson,
            usageJson = c.usageJson,
            generationMetadataJson = c.generationMetadataJson,
            createdAt = c.createdAt,
            extraJson = c.extraJson,
        )

    fun toDomain(e: CharacterEntity): CharacterCard = CharacterCard(
        id = e.id,
        name = e.name,
        description = e.description,
        personality = e.personality,
        scenario = e.scenario,
        firstMessage = e.firstMessage,
        exampleDialogue = e.exampleDialogue,
        systemPrompt = e.systemPrompt,
        avatarRelativePath = e.avatarRelativePath,
        attachedWorldBookIds = Jsonx.decode(e.attachedWorldBookIdsJson),
        mvuEnabled = e.mvuEnabled,
        mvuMode = e.mvuMode,
        mvuDirective = e.mvuDirective,
        initialStateTablesJson = e.initialStateTablesJson,
        contentRegexRulesJson = e.contentRegexRulesJson,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        sourceNamespace = e.sourceNamespace,
        sourceId = e.sourceId,
        extraJson = e.extraJson,
    )

    fun fromDomain(c: CharacterCard): CharacterEntity = CharacterEntity(
        id = c.id,
        name = c.name,
        description = c.description,
        personality = c.personality,
        scenario = c.scenario,
        firstMessage = c.firstMessage,
        exampleDialogue = c.exampleDialogue,
        systemPrompt = c.systemPrompt,
        avatarRelativePath = c.avatarRelativePath,
        attachedWorldBookIdsJson = Jsonx.encode(c.attachedWorldBookIds),
        mvuEnabled = c.mvuEnabled,
        mvuMode = c.mvuMode,
        mvuDirective = c.mvuDirective,
        initialStateTablesJson = c.initialStateTablesJson,
        contentRegexRulesJson = c.contentRegexRulesJson,
        createdAt = c.createdAt,
        updatedAt = c.updatedAt,
        sourceNamespace = c.sourceNamespace,
        sourceId = c.sourceId,
        extraJson = c.extraJson,
    )

    fun toDomain(e: PersonaEntity): UserPersona = UserPersona(
        id = e.id,
        name = e.name,
        description = e.description,
        avatarRelativePath = e.avatarRelativePath,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        sourceNamespace = e.sourceNamespace,
        sourceId = e.sourceId,
        extraJson = e.extraJson,
    )

    fun fromDomain(p: UserPersona): PersonaEntity = PersonaEntity(
        id = p.id,
        name = p.name,
        description = p.description,
        avatarRelativePath = p.avatarRelativePath,
        createdAt = p.createdAt,
        updatedAt = p.updatedAt,
        sourceNamespace = p.sourceNamespace,
        sourceId = p.sourceId,
        extraJson = p.extraJson,
    )

    fun toDomain(e: WorldBookEntity): WorldBook = WorldBook(
        id = e.id,
        name = e.name,
        entries = Jsonx.decode(e.entriesJson),
        globalActive = e.globalActive,
        sessionChatIds = Jsonx.decode(e.sessionChatIdsJson),
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        sourceNamespace = e.sourceNamespace,
        sourceId = e.sourceId,
        extraJson = e.extraJson,
    )

    fun fromDomain(b: WorldBook): WorldBookEntity = WorldBookEntity(
        id = b.id,
        name = b.name,
        entriesJson = Jsonx.encode(b.entries),
        globalActive = b.globalActive,
        sessionChatIdsJson = Jsonx.encode(b.sessionChatIds),
        createdAt = b.createdAt,
        updatedAt = b.updatedAt,
        sourceNamespace = b.sourceNamespace,
        sourceId = b.sourceId,
        extraJson = b.extraJson,
    )

    fun toDomain(e: LongTermMemoryEntity): LongTermMemory = LongTermMemory(
        chatId = e.chatId,
        content = e.content,
        version = e.version,
        anchorMessageId = e.anchorMessageId,
        updatedAt = e.updatedAt,
        source = e.source,
    )

    fun toDomain(e: MvuStateEntity): MvuState = MvuState(
        chatId = e.chatId,
        version = e.version,
        updatedAt = e.updatedAt,
        source = e.source,
        tables = Jsonx.decode(e.tablesJson),
    )

    fun toDomain(e: KnowledgeGraphEntity): KnowledgeGraph = KnowledgeGraph(
        chatId = e.chatId,
        entities = Jsonx.decode(e.entitiesJson),
        relations = Jsonx.decode(e.relationsJson),
        version = e.version,
        updatedAt = e.updatedAt,
        source = e.source,
    )

    fun toDomain(e: TaskEntity): TaskSnapshot = TaskSnapshot(
        id = e.id,
        kind = TaskKind.valueOf(e.kind),
        status = TaskStatus.valueOf(e.status),
        chatId = e.chatId,
        sandboxId = e.sandboxId,
        terminalReason = e.terminalReason?.let { TaskTerminalReason.valueOf(it) },
        errorMessage = e.errorMessage,
        createdAt = e.createdAt,
        updatedAt = e.updatedAt,
        resultSummary = e.resultSummary,
        fileChangeIds = Jsonx.decode(e.fileChangeIdsJson),
        incompleteFileChanges = e.incompleteFileChanges,
    )

    fun toDomain(e: ApprovalEntity): ApprovalRequest = ApprovalRequest(
        id = e.id,
        sessionId = e.sessionId,
        sandboxId = e.sandboxId,
        taskId = e.taskId,
        category = ApprovalCategory.valueOf(e.category),
        toolName = e.toolName,
        argumentsJson = e.argumentsJson,
        editPremiseJson = e.editPremiseJson,
        status = ApprovalDecision.valueOf(e.status),
        createdAt = e.createdAt,
        decidedAt = e.decidedAt,
    )

    fun toDomain(e: FileChangeEntity): FileChangeRecord = FileChangeRecord(
        id = e.id,
        sandboxId = e.sandboxId,
        taskId = e.taskId,
        path = e.path,
        changeType = FileChangeType.valueOf(e.changeType),
        observedAt = e.observedAt,
        beforeSummary = e.beforeSummary,
        afterSummary = e.afterSummary,
        attribution = e.attribution,
        incomplete = e.incomplete,
    )
}

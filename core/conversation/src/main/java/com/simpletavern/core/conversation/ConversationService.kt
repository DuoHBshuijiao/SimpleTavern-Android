package com.simpletavern.core.conversation

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.Jsonx
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.data.UsageStore
import com.simpletavern.core.data.TokenRequestAccounting
import com.simpletavern.core.data.WorkerStopSignals
import com.simpletavern.core.llm.LlmClient
import com.simpletavern.core.llm.LlmFileSession
import com.simpletavern.core.llm.LlmRequest
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.memory.ContextBuilder
import com.simpletavern.core.memory.ContextTokens
import com.simpletavern.core.memory.MemoryService
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolRuntime
import com.simpletavern.core.tools.ToolResult
import com.simpletavern.core.tools.AssistantToolSession
import com.simpletavern.core.tools.GenerationSearchPlan
import com.simpletavern.core.tools.WebSearchService
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*
import kotlin.random.Random

data class AssistantGenerationPlan(val provider: ProviderConfig, val messages: List<ContextMessage>,
    val tools: AssistantToolSession, val maxToolTurns: Int = 8, val maxToolsPerTurn: Int? = null,
    val contextTokenBudget: Int = 0)

@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
class ConversationService(
    private val chats: ChatRepository,
    private val content: ContentRepository,
    private val memory: MemoryService,
    private val contextBuilder: ContextBuilder,
    private val llm: LlmClient,
    private val tools: ToolRuntime,
    private val tasks: TaskStore,
    private val usage: UsageStore,
    private val scope: CoroutineScope,
    private val resolveSearch: suspend (ChatSession, ProviderConfig, WebSearchOptions) -> GenerationSearchPlan = { _, _, options ->
        if (options.enabled) throw StError.Unsupported("web search resolver is not installed")
        GenerationSearchPlan()
    },
    private val prepareAttachments: suspend (List<ContextMessage>) -> List<ContextMessage> = { messages ->
        if (messages.any { it.attachments.isNotEmpty() }) throw StError.Unsupported("attachment reader is not installed")
        messages
    },
    private val prepareAssistant: suspend (ChatSession, ProviderConfig?, Long) -> AssistantGenerationPlan? = { session, _, _ ->
        if (session.overrides.assistant != null) throw StError.Unsupported("assistant runtime is unavailable")
        null
    },
    private val prepareSourceTools: suspend (ChatSession, String?) -> AssistantToolSession? = { _, _ -> null },
    private val saveToolResult: suspend (String, String, ToolResult) -> MessageCandidate = { chatId, callId, result ->
        if (result.media.isNotEmpty()) throw StError.Unsupported("tool attachment storage is not installed")
        MessageCandidate(UUID.randomUUID().toString().replace("-", ""), ChatRole.tool, result.output,
            toolCallId = callId, toolRecordJson = result.recordJson, createdAt = Instant.now().toString()).also { chats.appendNode(chatId, it) }
    },
    private val saveGeneratedMedia: suspend (String, MessageCandidate, List<ToolMedia>, ProviderTurn, (MessageCandidate, ProviderTurn) -> Unit) -> Unit = { _, _, _, _, _ ->
        throw StError.Unsupported("generated attachment storage is not installed")
    },
    private val applyExplicitModes: suspend (ChatSession, String?, ProviderConfig) -> ProviderConfig = { session, memberId, provider ->
        if (memberId?.let { session.memberSettings[it]?.fastMode } != null)
            throw StError.Unsupported("explicit Fast mode resolver is not installed")
        provider
    },
    private val resolveProvider: suspend (ChatSession, String?) -> ProviderConfig,
) {
    private val chatLocks = ConcurrentHashMap<String, Mutex>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val stopSignals = WorkerStopSignals()
    fun signalHostInterruption(reason: TaskTerminalReason) = stopSignals.interrupt(reason, runningJobs)
    private val events = ConcurrentHashMap<String, MutableSharedFlow<StreamEvent>>()

    private fun eventFlow(chatId: String) = events.getOrPut(chatId) {
        generationEventFlow()
    }

    fun observe(chatId: String): Flow<StreamEvent> =
        eventFlow(chatId).asSharedFlow()

    suspend fun createChat(
        characterId: String,
        title: String = "新对话",
        isGroup: Boolean = false,
        memberIds: List<String> = emptyList(),
        sandboxId: String? = null,
        openingMessageIndex: Int = 0,
        userPersonaId: String? = null,
        useDefaultPersona: Boolean = true,
        firstMessageCharacterId: String? = null,
        useOpeningMessage: Boolean = !isGroup || firstMessageCharacterId != null,
        pureAiMode: Boolean? = null,
        groupMvuPreset: String = "off",
        groupMvuPresetCharacterId: String? = null,
    ): ChatSession = chats.transaction {
        val now = now()
        val character = content.getCharacter(characterId) ?: throw StError.NotFound("character $characterId")
        val defaults = content.chatCreationDefaults(character)
        val personaId = userPersonaId ?: defaults.userPersonaId.takeIf { useDefaultPersona }
        if (!useOpeningMessage && firstMessageCharacterId != null)
            throw StError.Validation("first message character requires opening messages to be enabled")
        val sessionMemberIds = if (isGroup) {
            (if (characterId in memberIds) memberIds else listOf(characterId) + memberIds).distinct()
        } else memberIds
        if (isGroup && firstMessageCharacterId != null && firstMessageCharacterId !in sessionMemberIds)
            throw StError.Validation("first message character must be a group member")
        if (!isGroup && firstMessageCharacterId != null && firstMessageCharacterId != characterId)
            throw StError.Validation("first message character must match the conversation character")
        val groupCharacters = if (isGroup) sessionMemberIds.mapNotNull {
            if (it == characterId) character else content.getCharacter(it)
        } else emptyList()
        val mvuCharacter = if (isGroup) when (groupMvuPreset) {
            "off" -> null
            "inherit_member", "fork_session" -> {
                if (groupMvuPresetCharacterId == null || groupMvuPresetCharacterId !in sessionMemberIds)
                    throw StError.Validation("MVU preset character must be a group member")
                val selected = groupCharacters.firstOrNull { it.id == groupMvuPresetCharacterId }
                    ?: throw StError.NotFound("character $groupMvuPresetCharacterId")
                if (groupMvuPreset == "inherit_member" && !content.hasMvuProfileData(selected))
                    throw StError.Validation("selected group member has no MVU profile data")
                selected
            }
            else -> throw StError.Validation("unknown group MVU preset")
        } else null
        val creationOverrides = if (isGroup) defaults.overrides.copy(
            contentRegexRulesJson = Jsonx.encode(content.mergeGroupContentRegexRules(groupCharacters)),
            groupMvuEnabled = mvuCharacter != null,
            groupMvuAnchorCharacterId = mvuCharacter?.id,
            groupMvuTemplateCharacterId = mvuCharacter?.id,
            mvuMode = mvuCharacter?.mvuMode ?: defaults.overrides.mvuMode,
            mvuDirective = if (mvuCharacter != null) mvuCharacter.mvuDirective else defaults.overrides.mvuDirective,
        ) else defaults.overrides
        val openingCharacterId = if (!useOpeningMessage) null else firstMessageCharacterId ?: characterId
        val openingCharacter = openingCharacterId?.let {
            if (it == characterId) character else groupCharacters.firstOrNull { member -> member.id == it }
                ?: throw StError.NotFound("character $it")
        }
        val openingTemplates = openingCharacter?.let { content.characterOpeningMessages(it) }
        val selectedOpening = openingTemplates?.let {
            it.getOrNull(openingMessageIndex)
                ?: throw StError.Validation("opening message index is outside the character's greeting list")
        }
        if (useOpeningMessage && !isGroup && openingMessageIndex != 0 && defaults.initialMessages.isNotEmpty())
            throw StError.Validation("source preset messages cannot be replaced by an alternate greeting")
        val initialMessages = defaults.initialMessages.takeUnless { isGroup }.orEmpty()
        val initialSession = ChatSession(
            id = id(),
            characterId = characterId,
            title = title,
            isGroup = isGroup,
            memberIds = sessionMemberIds,
            sandboxId = sandboxId,
            userPersonaId = personaId,
            sourceNamespace = character.sourceNamespace,
            overrides = creationOverrides.copy(pureAiMode = pureAiMode ?: creationOverrides.pureAiMode),
            createdAt = now,
            updatedAt = now,
        )
        val pureAi = contextBuilder.pureAiFor(initialSession)
        val persona = if (pureAi) null else personaId?.let { content.getPersona(it) }
        if (!pureAi && userPersonaId != null && persona == null) throw StError.NotFound("persona $userPersonaId")
        val session = initialSession.copy(userPersonaId = persona?.id)
        chats.saveSession(session)
        for (message in initialMessages) {
            chats.appendNode(session.id, message.copy(id = id(),
                characterId = if (message.role == ChatRole.assistant) characterId else message.characterId,
                toolCallsJson = null, toolCallId = null, generationMetadataJson = null))
        }
        if (selectedOpening != null && initialMessages.isEmpty()) {
            val characterName = openingCharacter?.name?.takeIf { it.isNotBlank() } ?: "角色"
            val userName = persona?.name?.takeIf { it.isNotBlank() } ?: "用户"
            val variants = openingTemplates.orEmpty().mapIndexedNotNull { sourceIndex, text ->
                contextBuilder.renderPlaceholders(text, characterName, userName).trim().takeIf { it.isNotEmpty() }
                    ?.let { sourceIndex to it }
            }
            val selectedIndex = variants.indexOfFirst { it.first == openingMessageIndex }
            if (selectedIndex >= 0) {
                chats.appendOpeningNode(session.id, variants.map { (_, text) ->
                    MessageCandidate(id = id(), role = ChatRole.assistant, content = text,
                        characterId = openingCharacterId, createdAt = now)
                }, selectedIndex)
            }
        }
        (if (isGroup) mvuCharacter else character)?.initialStateTablesJson?.let {
            memory.bootstrapMvuFromCharacter(session.id, it)
        }
        chats.getSession(session.id)!!
    }

    suspend fun fork(sourceChatId: String, forkAtMessageId: String, newTitle: String?): ChatSession =
        chatLocks.getOrPut(sourceChatId) { Mutex() }.withLock {
            ChatForker(chats, tasks, memory).fork(sourceChatId, forkAtMessageId, newTitle)
        }

    suspend fun branch(sourceChatId: String, newTitle: String?): ChatSession =
        chatLocks.getOrPut(sourceChatId) { Mutex() }.withLock {
            chats.transaction {
                val source = chats.getSession(sourceChatId) ?: throw StError.NotFound("chat $sourceChatId")
                ChatForker(chats, tasks, memory).copyAll(source, newTitle)
            }
        }

    suspend fun promoteToGroup(sourceChatId: String, memberIds: List<String>, newTitle: String? = null,
        memberSettings: Map<String, GroupMemberSettings> = emptyMap(), pureAiMode: Boolean? = null,
        userPersonaId: String? = null, useDefaultPersona: Boolean = true,
        groupSystemInjectDepth: Int = 5, groupSystemAlwaysAtBottom: Boolean = true): ChatSession =
        chatLocks.getOrPut(sourceChatId) { Mutex() }.withLock {
            chats.transaction {
                val source = chats.getSession(sourceChatId) ?: throw StError.NotFound("chat $sourceChatId")
                if (source.isGroup || source.overrides.assistant != null)
                    throw StError.Validation("promotion requires a single role conversation")
                val members = (listOf(source.characterId) + memberIds).distinct()
                if (members.size < 2) throw StError.Validation("group requires at least two members")
                if (groupSystemInjectDepth < 0) throw StError.Validation("group system depth must be nonnegative")
                if (memberSettings.keys.any { it !in members })
                    throw StError.Validation("member settings must belong to the group")
                val characters = members.map { content.getCharacter(it) ?: throw StError.NotFound("character $it") }
                val defaults = content.chatCreationDefaults(characters.first())
                val personaId = userPersonaId ?: defaults.userPersonaId.takeIf { useDefaultPersona }
                val title = newTitle?.trim()?.takeIf { it.isNotEmpty() }
                    ?: source.title.takeIf { it.isNotBlank() } ?: "新群聊"
                ChatForker(chats, tasks, memory).copyAll(source, title) { copied ->
                    val group = copied.copy(isGroup = true, memberIds = members, memberSettings = memberSettings,
                        groupSystemInjectDepth = groupSystemInjectDepth, groupSystemAlwaysAtBottom = groupSystemAlwaysAtBottom,
                        userPersonaId = personaId, overrides = copied.overrides.copy(
                            pureAiMode = pureAiMode ?: defaults.overrides.pureAiMode,
                            contentRegexRulesJson = Jsonx.encode(content.mergeGroupContentRegexRules(characters)),
                            groupMvuEnabled = true, groupMvuAnchorCharacterId = source.characterId,
                            groupMvuTemplateCharacterId = source.characterId))
                    val pureAi = contextBuilder.pureAiFor(group)
                    val persona = if (pureAi) null else personaId?.let { content.getPersona(it) }
                    if (!pureAi && userPersonaId != null && persona == null) throw StError.NotFound("persona $userPersonaId")
                    group.copy(userPersonaId = persona?.id)
                }
            }
        }

    suspend fun forkLineage(chatId: String): ForkLineage {
        val chat = chats.getSession(chatId) ?: throw StError.NotFound(chatId)
        val siblings = chat.forkedFromChatId?.let { origin ->
            chats.listForks(origin).filter {
                it.forkedFromMessageId == chat.forkedFromMessageId && it.id != chatId
            }.map { ChatSessionSummary(it.id, it.title, it.createdAt) }
        } ?: emptyList()
        return ForkLineage(chat.forkedFromChatId, chat.forkedFromMessageId, chat.forkedFromMessageIndex, siblings)
    }

    suspend fun startGeneration(
        chatId: String,
        userText: String?,
        provider: ProviderConfig? = null,
        interjectCharacterId: String? = null,
        persistUser: Boolean = true,
        groupOptions: GroupGenerationOptions? = null,
        webSearch: WebSearchOptions = WebSearchOptions(),
        attachments: List<AttachmentRef> = emptyList(),
        regenerateNodeId: String? = null,
    ): TaskSnapshot {
        if (attachments.size > 20 || attachments.isNotEmpty() && !persistUser)
            throw StError.Validation("at most 20 attachments require a persisted user message")
        val incomingAttachments = attachments.toList()
        val mutex = chatLocks.getOrPut(chatId) { Mutex() }
        return mutex.withLock {
            val session = chats.getSession(chatId) ?: throw StError.NotFound(chatId)
            val regeneration = regenerateNodeId?.let { nodeId -> chats.transaction {
                val target = chats.getMessageNode(nodeId) ?: throw StError.NotFound("node $nodeId")
                if (target.chatId != chatId || target.selected?.role != ChatRole.assistant)
                    throw StError.Validation("regeneration requires an assistant node in this chat")
                if (persistUser || userText != null || attachments.isNotEmpty() || groupOptions != null)
                    throw StError.Validation("regeneration cannot append a user message or schedule group rounds")
                Regeneration(target, chats.messageNodeIdsAfter(chatId, target.orderIndex))
            } }
            val speaker = if (regeneration != null && session.overrides.assistant != null) null else
                regeneration?.target?.selected?.characterId?.takeIf { session.isGroup || it != session.characterId } ?: interjectCharacterId
            if (regeneration != null && session.isGroup && speaker == null)
                throw StError.Validation("regenerated group reply must identify a current member")
            if (session.overrides.assistant != null && (session.isGroup || interjectCharacterId != null || groupOptions != null))
                throw StError.Validation("assistant conversations cannot schedule role/group turns")
            val active = tasks.activeGeneration(chatId)
            if (active.isNotEmpty()) {
                throw StError.Conflict("session already generating", mapOf("taskId" to active.first().id))
            }
            if (groupOptions != null && !session.isGroup) throw StError.Validation("group options require a group chat")
            if (groupOptions != null && interjectCharacterId != null) throw StError.Validation("interjection cannot also schedule rounds")
            if (session.isGroup && speaker != null && speaker !in session.memberIds) {
                throw StError.Validation("interjecting character is not a group member")
            }
            val now = now()
            val userMessage = if (persistUser && (!userText.isNullOrBlank() || incomingAttachments.isNotEmpty())) {
                val persona = contextBuilder.userPersonaFor(session)
                MessageCandidate(id = id(), role = ChatRole.user, content = userText.orEmpty(),
                    images = incomingAttachments.filter { it.mimeType.startsWith("image/") },
                    attachments = incomingAttachments.filterNot { it.mimeType.startsWith("image/") },
                    senderPersonaId = persona?.id, senderName = persona?.name?.takeIf { it.isNotBlank() } ?: "用户",
                    senderAvatar = persona?.avatarRelativePath, createdAt = now)
            } else null
            val task = TaskSnapshot(
                id = id(),
                kind = if (session.isGroup || speaker != null) TaskKind.GROUP_GENERATION else TaskKind.CHAT_GENERATION,
                status = TaskStatus.QUEUED,
                chatId = chatId,
                sandboxId = session.sandboxId,
                createdAt = now,
                updatedAt = now,
                resultSummary = regeneration?.let { "regenerate=${it.target.id}" },
            )
            val progress = if (session.isGroup) planGroup(task.id, session, groupOptions ?: GroupGenerationOptions(), speaker) else null
            val speakers = progress?.turns?.withIndex()?.filter { it.value.status == GroupTurnStatus.QUEUED }
                ?.map { it.index to it.value.characterId } ?: listOf(null to speaker)
            currentCoroutineContext().ensureActive()
            // Registration and worker ownership must survive cancellation of the requesting screen.
            withContext(NonCancellable) {
                stopSignals.register(task.id)
                try { tasks.upsert(task, progress?.let { Jsonx.encode(it) }) }
                catch (error: Throwable) { stopSignals.forget(task.id); throw error }
                val flow = eventFlow(chatId)
                flow.resetReplayCache()
                val ready = CompletableDeferred<Unit>()
                val job = scope.launch(start = CoroutineStart.ATOMIC) {
                    runGeneration(task.id, chatId, userMessage, provider, speakers, progress?.delayMs ?: 0, flow, ready, webSearch, regeneration)
                }
                runningJobs[task.id] = job
                job.invokeOnCompletion { runningJobs.remove(task.id, job); stopSignals.forget(task.id) }
                stopSignals.bind(task.id, job)
                ready.complete(Unit)
            }
            task
        }
    }

    suspend fun groupProgress(taskId: String): GroupGenerationProgress {
        val task = tasks.get(taskId) ?: throw StError.NotFound(taskId)
        if (task.kind != TaskKind.GROUP_GENERATION) throw StError.Validation("not a group generation task")
        return Jsonx.decodeOrNull<GroupGenerationProgress>(tasks.payloadJson(taskId))
            ?: throw StError.NotFound("this legacy or single-chat interjection task has no group schedule")
    }

    private suspend fun planGroup(taskId: String, session: ChatSession, options: GroupGenerationOptions,
                                  interject: String?): GroupGenerationProgress {
        if (session.memberIds.size < 2 || session.memberIds.distinct().size != session.memberIds.size) {
            throw StError.Validation("group chat requires at least two distinct members")
        }
        val members = interject?.let { listOf(it) } ?: options.memberIds.ifEmpty { session.memberIds }
        val rounds = if (interject != null) 1 else options.rounds
        if (rounds !in 1..20 || members.size.toLong() * rounds > 200 || members.distinct().size != members.size ||
            members.any { it !in session.memberIds }) throw StError.Validation("invalid group schedule (1..20 rounds, at most 200 turns)")
        if (session.groupDelayMs !in 0..60_000) throw StError.Validation("group delay must be 0..60000 ms")
        members.forEach { member ->
            if (content.getCharacter(member) == null) throw StError.NotFound("group character $member")
            val probability = session.memberSettings[member]?.probability ?: 1.0
            if (!probability.isFinite() || probability !in 0.0..1.0) throw StError.Validation("member probability must be 0..1")
        }
        val turns = (1..rounds).flatMap { round ->
            val selected = members.filter { interject != null || !options.respectProbability ||
                Random.nextDouble() < (session.memberSettings[it]?.probability ?: 1.0) }.toMutableSet()
            // Preserve desktop semantics even when all members were filtered out.
            if (selected.isEmpty()) selected += members.random()
            members.map { GroupGenerationTurn(round, it, if (it in selected) GroupTurnStatus.QUEUED else GroupTurnStatus.SKIPPED) }
        }
        return GroupGenerationProgress(taskId, session.id, rounds, session.groupDelayMs, turns, updatedAt = now())
    }

    private suspend fun updateProgress(taskId: String, transform: (GroupGenerationProgress) -> GroupGenerationProgress) = chats.transaction {
        val task = tasks.get(taskId) ?: throw StError.NotFound(taskId)
        val progress = Jsonx.decodeOrNull<GroupGenerationProgress>(tasks.payloadJson(taskId)) ?: return@transaction
        tasks.upsert(task, Jsonx.encode(transform(progress).copy(updatedAt = now())))
    }

    private suspend fun finishProgress(taskId: String, status: TaskStatus) = updateProgress(taskId) { progress ->
        val terminal = when (status) {
            TaskStatus.STOPPED -> GroupTurnStatus.STOPPED
            TaskStatus.INTERRUPTED -> GroupTurnStatus.INTERRUPTED
            else -> GroupTurnStatus.FAILED
        }
        progress.copy(currentTurnIndex = null, turns = progress.turns.map { turn ->
            if (turn.status in setOf(GroupTurnStatus.QUEUED, GroupTurnStatus.RUNNING)) turn.copy(status = terminal) else turn
        })
    }

    suspend fun stop(taskId: String, reason: TaskTerminalReason = TaskTerminalReason.USER_STOP): TaskSnapshot {
        val task = tasks.get(taskId) ?: throw StError.NotFound(taskId)
        if (task.status.isTerminal) return task
        if (task.kind !in setOf(TaskKind.CHAT_GENERATION, TaskKind.GROUP_GENERATION)) {
            throw StError.Unsupported("stop is not wired for task kind ${task.kind}")
        }
        val mutex = chatLocks.getOrPut(task.chatId ?: throw StError.Validation("generation chat missing")) { Mutex() }
        return withContext(NonCancellable) {
            val job = mutex.withLock {
                if (tasks.get(taskId)?.status?.isTerminal == true) return@withLock null
                val first = stopSignals.first(taskId, reason)
                val worker = runningJobs[taskId]?.also { it.cancel() }
                val latest = tasks.requestStop(taskId, first)
                if (latest.status.isTerminal) return@withLock null
                worker
            }
            // Cleanup cannot be abandoned if the caller leaves the screen while waiting for stop.
            tools.cancelTask(taskId)
            tools.expireApprovalsForTask(taskId)
            job?.join() // the worker saves buffered output before publishing its terminal state
            chats.transaction {
                val terminal = tasks.update(taskId) { it.copy(status = it.stoppingStatus(), terminalReason = it.completionReason(it.stoppingStatus()), updatedAt = now()) }
                finishProgress(taskId, terminal.status)
                terminal
            }
        }
    }

    suspend fun markInterruptedOnProcessStart() {
        tasks.nonTerminal().forEach { t ->
            chats.transaction {
                val chatId = t.chatId
                val summary = t.resultSummary
                if (chatId != null && summary?.startsWith("assistant=") == true) {
                    val messageId = summary.removePrefix("assistant=")
                    chats.getCandidate(chatId, messageId)?.let { c ->
                        val metadata = Jsonx.decodeOrNull<GenerationMetadata>(c.generationMetadataJson)
                        if (metadata?.status in setOf("running", "awaiting_tools")) {
                            chats.updateCandidate(chatId, c, c.copy(generationMetadataJson = Jsonx.encode(metadata!!.copy(status = "interrupted"))))
                        }
                    }
                }
                tasks.upsert(
                    t.copy(
                        status = TaskStatus.INTERRUPTED,
                        terminalReason = TaskTerminalReason.PROCESS_KILLED,
                        updatedAt = now(),
                        incompleteFileChanges = true,
                    ),
                    tasks.payloadJson(t.id),
                )
                finishProgress(t.id, TaskStatus.INTERRUPTED)
            }
            tools.expireApprovalsForTask(t.id)
        }
    }

    private inner class StreamingAnswer(
        val chatId: String, val requestId: String, val initial: MessageCandidate,
        val metadata: GenerationMetadata,
    ) {
        val lock = Mutex()
        private val text = StringBuilder()
        private val reasoning = StringBuilder()
        private val started = System.nanoTime()
        private var firstTokenMs: Long? = null
        private var saved = initial
        var usage: UsageTokens? = null
        var callsJson: String? = null
        var providerTurn: ProviderTurn? = null
        var cost: CostSnapshot? = null
        var requestCompleted = false
        var search: NativeSearchTrace? = null
        var fileTransfers: List<ProviderFileTransfer> = emptyList()

        suspend fun accountingOutcome() = lock.withLock {
            TokenRequestOutcome(usage ?: UsageTokens(), requestCompleted, firstTokenMs, elapsedMs())
        }

        suspend fun delta(ev: StreamEvent.Delta) = lock.withLock {
            if (firstTokenMs == null) firstTokenMs = elapsedMs()
            if (ev.reasoning) reasoning.append(ev.text) else text.append(ev.text)
        }

        suspend fun complete(ev: StreamEvent.Done) = lock.withLock {
            requestCompleted = true
            search = ev.search
            if (ev.media.isNotEmpty()) {
                saveGeneratedMedia(chatId, saved, ev.media, ev.providerTurn!!) { next, bound ->
                    saved = next
                    providerTurn = bound
                }
            } else providerTurn = ev.providerTurn
        }

        suspend fun flush(status: String): MessageCandidate = lock.withLock {
            val next = saved.copy(
                content = text.toString(), reasoningContent = reasoning.toString().ifBlank { null },
                toolCallsJson = callsJson,
                usageJson = usage?.let { Jsonx.encode(it) },
                generationMetadataJson = Jsonx.encode(metadata.copy(status = status, usage = usage,
                    resolvedModel = usage?.resolvedModel, cost = cost,
                    providerTurn = providerTurn, search = search, fileTransfers = fileTransfers,
                    firstTokenLatencyMs = firstTokenMs,
                    totalDurationMs = if (status == "running") null else elapsedMs())),
            )
            if (next != saved) chats.updateCandidate(chatId, saved, next)
            saved = next
            next
        }

        private fun elapsedMs() = (System.nanoTime() - started) / 1_000_000
    }

    private data class Regeneration(val target: MessageNode, val tailNodeIds: List<String>)

    private suspend fun runGeneration(
        taskId: String, chatId: String, userMessage: MessageCandidate?, providerOverride: ProviderConfig?,
        speakers: List<Pair<Int?, String?>>, delayMs: Int, flow: MutableSharedFlow<StreamEvent>, ready: Deferred<Unit>,
        webSearch: WebSearchOptions,
        regeneration: Regeneration?,
    ) {
        var answer: StreamingAnswer? = null
        var failure: Throwable? = null
        var cancelled = false
        var completed = false
        var autoSummaryBoundary: String? = null
        var activeProvider: ProviderConfig? = null
        var activeFiles: LlmFileSession? = null
        var lastCompleted: MessageCandidate? = null
        val usedCallIds = mutableSetOf<String>()
        val liveToolSessions = mutableSetOf<AssistantToolSession>()
        val generatedNodeIds = mutableListOf<String>()
        try {
            ready.await()
            val snapshot = tasks.update(taskId) {
                if (it.status == TaskStatus.STOPPING) it else it.copy(status = TaskStatus.RUNNING, updatedAt = now())
            }
            if (snapshot.status == TaskStatus.STOPPING || snapshot.status.isTerminal) throw CancellationException("generation stopped")
            currentCoroutineContext().ensureActive()
            userMessage?.let { chats.appendNode(chatId, it) }
            for ((speakerIndex, speaker) in speakers.withIndex()) {
                val (turnIndex, interjectCharacterId) = speaker
                answer = null
                activeProvider = null
                completed = false
                if (speakerIndex > 0) delay(delayMs.toLong())
                currentCoroutineContext().ensureActive()
                val storedSession = chats.getSession(chatId) ?: throw StError.NotFound(chatId)
                val throughOrder = regeneration?.target?.orderIndex?.minus(1) ?: Long.MAX_VALUE
                val boundary = storedSession.overrides.contextStartMessageId?.let { chats.contextAnchorOrder(chatId, it) }
                val session = if (regeneration != null && boundary != null && boundary > throughOrder)
                    storedSession.copy(overrides = storedSession.overrides.copy(contextStartMessageId = null, contextStartKeepBeforeMessages = null))
                    else storedSession
                if (regeneration != null) {
                    val current = chats.getMessageNode(regeneration.target.id)
                    if (current == null || current.chatId != chatId || current.orderIndex != regeneration.target.orderIndex ||
                        current.selected != regeneration.target.selected) throw StError.Conflict("regeneration target changed before dispatch")
                }
                if (turnIndex != null) {
                    if (!session.isGroup || interjectCharacterId !in session.memberIds) {
                        throw StError.Conflict("scheduled member was removed or chat is no longer a group")
                    }
                    if (content.getCharacter(interjectCharacterId!!) == null) throw StError.NotFound("scheduled character $interjectCharacterId")
                    updateProgress(taskId) { progress -> progress.copy(currentTurnIndex = turnIndex,
                        turns = progress.turns.mapIndexed { index, turn ->
                            if (index == turnIndex) turn.copy(status = GroupTurnStatus.RUNNING) else turn
                        }) }
                }
                val member = interjectCharacterId?.let { session.memberSettings[it] }
                val assistantPlan = prepareAssistant(session, providerOverride, throughOrder)
                val sourceTools = if (assistantPlan == null) prepareSourceTools(session, interjectCharacterId) else null
                val boundTools = assistantPlan?.tools ?: sourceTools
                boundTools?.let(liveToolSessions::add)
                val provider = if (providerOverride == null) assistantPlan?.provider ?: resolveProvider(session, interjectCharacterId) else applyExplicitModes(session, interjectCharacterId, providerOverride.copy(
                    model = member?.model?.takeIf { it.isNotBlank() } ?: providerOverride.model,
                    temperature = member?.temperature ?: providerOverride.temperature,
                    topP = member?.topP ?: providerOverride.topP,
                    extra = providerOverride.extra + (member?.reasoningEffort?.let { mapOf("reasoning_effort" to it) } ?: emptyMap()),
                ))
                activeProvider = provider
                val searchPlan = resolveSearch(session, provider, webSearch)
                val providerTools = provider.toolsJson?.let { Jsonx.json.parseToJsonElement(it) as? JsonArray
                    ?: throw StError.Validation("tools must be an array") } ?: JsonArray(emptyList())
                val configuredTools = if (assistantPlan != null) {
                    val names = assistantPlan.tools.definitions.map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }.toSet()
                    if (providerTools.any { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content !in names + "web_search" })
                        throw StError.Validation("provider tools exceed this assistant's enabled tools")
                    JsonArray(providerTools.filter { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content !in names } + assistantPlan.tools.definitions)
                } else if (sourceTools != null) {
                    val names = sourceTools.categories.keys
                    JsonArray(providerTools.filter { it.jsonObject["function"]?.jsonObject?.get("name")?.jsonPrimitive?.content !in names } + sourceTools.definitions)
                } else providerTools
                val hasSearchTool = configuredTools.any { (it as? JsonObject)?.get("function")?.let { fn ->
                    (fn as? JsonObject)?.get("name") == JsonPrimitive("web_search") } == true }
                if (hasSearchTool && searchPlan.session == null) throw StError.Validation("independent search tool requires this request's explicit independent search permission")
                val deepSeekWeb = if (searchPlan.deepSeek) com.simpletavern.core.llm.DeepSeekWebClient(provider, searchPlan.searchEnabled, searchPlan.fetchEnabled) else null
                val requestTools = if (deepSeekWeb != null) JsonArray(configuredTools + deepSeekWeb.definitions).toString() else if (searchPlan.session != null) JsonArray(configuredTools.filterNot { spec ->
                    (spec as? JsonObject)?.get("function")?.let { (it as? JsonObject)?.get("name") } == JsonPrimitive("web_search")
                } + WebSearchService.TOOL).toString() else configuredTools.takeIf { it.isNotEmpty() }?.toString()
                val messages = prepareAttachments(assistantPlan?.messages ?: contextBuilder.build(session,
                    characterIdOverride = interjectCharacterId, throughOrder = throughOrder,
                    outputTokens = ContextTokens.output(provider), toolsJson = requestTools).messages).toMutableList()
                val allowedTools = requestTools?.let { raw ->
                    Jsonx.json.parseToJsonElement(raw).jsonArray.map { spec ->
                        val obj = spec.jsonObject
                        if (obj["type"]?.jsonPrimitive?.contentOrNull != "function") throw StError.Unsupported("only function tools are supported")
                        obj["function"]?.jsonObject?.get("name")?.jsonPrimitive?.contentOrNull
                            ?: throw StError.Validation("tool name missing")
                    }.toSet()
                } ?: emptySet()
                val maxTurns = assistantPlan?.maxToolTurns ?: 8
                val contextTokenBudget = assistantPlan?.contextTokenBudget ?: contextBuilder.contextBudgetFor(session, interjectCharacterId)
                val fileSession = llm.fileSession(provider)
                activeFiles = fileSession
                for (round in 0 until maxTurns) {
                    currentCoroutineContext().ensureActive()
                    ContextTokens.requireFits(contextTokenBudget, messages, ContextTokens.output(provider), requestTools)
                    val requestId = if (speakerIndex == 0 && round == 0) taskId else id()
                    val assistantId = id()
                    val meta = GenerationMetadata(requestId = requestId, chatId = chatId, messageId = assistantId,
                        provider = provider.providerId, protocol = provider.protocol.name, requestedModel = provider.model,
                        webSearchMode = if (searchPlan.native) "native" else if (searchPlan.session != null) "independent" else "disabled",
                        webSearchProvider = searchPlan.session?.provider ?: provider.protocol.name.takeIf { searchPlan.native },
                        startedAt = now(), status = "running")
                    val initial = MessageCandidate(id = assistantId, role = ChatRole.assistant, content = "",
                        characterId = interjectCharacterId ?: session.characterId.takeUnless { session.isGroup || assistantPlan != null },
                        generationMetadataJson = Jsonx.encode(meta), createdAt = now())
                    val current = StreamingAnswer(chatId, requestId, initial, meta)
                    chatLocks.getOrPut(chatId) { Mutex() }.withLock {
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            chats.transaction {
                                val owner = tasks.get(taskId) ?: throw StError.NotFound(taskId)
                                if (owner.status == TaskStatus.STOPPING || owner.status.isTerminal) throw CancellationException("generation stopped")
                                val node = chats.appendNode(chatId, initial)
                                if (regeneration != null) generatedNodeIds += node.id
                                tasks.update(taskId) { it.copy(resultSummary = "assistant=$assistantId" +
                                    (regeneration?.let { ", regenerate=${it.target.id}" } ?: ""), updatedAt = now()) }
                                if (turnIndex != null) updateProgress(taskId) { progress -> progress.copy(
                                    turns = progress.turns.mapIndexed { index, turn ->
                                        if (index == turnIndex) turn.copy(assistantMessageIds = turn.assistantMessageIds + assistantId) else turn
                                    }) }
                            }
                            answer = current
                        }
                    }
                    val calls = mutableListOf<StreamEvent.ToolCall>()
                    var receivedDone = false
                    var continuation = false
                    val req = LlmRequest(requestId, provider.protocol, provider.model, messages.toList(),
                        provider.temperature, provider.topP, requestTools, provider.extra, nativeWebSearch = searchPlan.native && !searchPlan.deepSeek && searchPlan.searchEnabled,
                        nativeWebFetch = searchPlan.native && !searchPlan.deepSeek && searchPlan.fetchEnabled, taskId = taskId)
                    usage.executeTokenRequest(requestId, chatId, provider.providerId, provider.model,
                        UsageRecordContext(provider.protocol.name, startedAt = meta.startedAt, requestedModel = provider.model,
                            purpose = "chat_generation", scope = "chat:$chatId", sourceNamespace = session.sourceNamespace,
                            taskId = taskId, messageCandidateId = assistantId, roundIndex = round + 1, turnIndex = turnIndex),
                        provider.apiKey, outcome = { current.accountingOutcome() }, recorded = { cost -> current.lock.withLock { current.cost = cost } },
                        credentialMaterials = provider.credentialMaterials) { beforeExecute ->
                      coroutineScope {
                        // A timer saves even a single delta followed by a stalled network.
                        val writer = launch {
                            while (isActive) { delay(500); current.flush("running") }
                        }
                        try {
                            fileSession.stream(req, beforeExecute,
                                onFileTransfers = { transfers -> current.lock.withLock { current.fileTransfers = transfers } }).collect { ev ->
                                when (ev) {
                                    is StreamEvent.Delta -> { current.delta(ev); flow.tryEmit(ev) }
                                    is StreamEvent.Usage -> {
                                        val captured = TokenRequestAccounting.safeTokens(ev.usage, provider.credentialMaterials)
                                        current.lock.withLock { current.usage = captured }
                                        flow.tryEmit(StreamEvent.Usage(captured))
                                    }
                                    is StreamEvent.ToolCall -> {
                                        if (ev.name !in allowedTools) throw StError.Validation("provider requested an undeclared tool")
                                        if (!usedCallIds.add(ev.callId)) throw StError.Provider("provider repeated a tool call ID")
                                        calls += ev
                                    }
                                    is StreamEvent.Done -> {
                                        if (!ev.ok) throw StError.Provider("generation did not complete")
                                        if (receivedDone) throw StError.Provider("duplicate stream completion")
                                        receivedDone = true
                                        ev.usage?.let { u -> current.lock.withLock { current.usage = TokenRequestAccounting.safeTokens(u, provider.credentialMaterials) } }
                                        continuation = ev.continuation
                                        current.complete(ev)
                                    }
                                    is StreamEvent.Error -> throw StError.Provider(ev.message)
                                }
                            }
                        } finally { writer.cancelAndJoin() }
                      }
                    }
                    if (!receivedDone) throw StError.Provider("generation stream ended without completion")
                    if (calls.isEmpty() && !continuation) { completed = true; break }
                    if (round == maxTurns - 1) throw StError.Provider("tool round limit reached")
                    val toolCalls = buildJsonArray {
                        calls.forEach { call -> add(buildJsonObject {
                            put("id", call.callId); put("type", "function")
                            put("function", buildJsonObject { put("name", call.name); put("arguments", call.argumentsJson) })
                        }) }
                    }.takeIf { calls.isNotEmpty() }?.toString()
                    current.lock.withLock { current.callsJson = toolCalls }
                    val toolMessage = current.flush("awaiting_tools")
                    val projected = memory.previewMessageContentRegex(chatId, toolMessage.id)
                    messages += contextBuilder.renderContinuation(session, ContextMessage(ChatRole.assistant, projected.content,
                        toolCallsJson = toolCalls, providerTurn = current.providerTurn,
                        attachments = toolMessage.images + toolMessage.attachments, sourceCreatedAt = toolMessage.createdAt))
                    for ((callIndex, call) in calls.withIndex()) {
                        currentCoroutineContext().ensureActive()
                        flow.tryEmit(call)
                        val result = if (assistantPlan?.maxToolsPerTurn?.let { callIndex >= it } == true)
                            com.simpletavern.core.tools.ToolResult("{\"ok\":false,\"code\":\"LIMIT_EXCEEDED\",\"message\":\"maxToolsPerTurn exceeded; not executed\"}", "{\"skipped\":true}")
                        else if (deepSeekWeb?.handles(call.name) == true) com.simpletavern.core.tools.ToolResult(deepSeekWeb.execute(call.name, call.argumentsJson), "{\"provider\":\"deepseek\"}")
                        else tools.executeBound(chatId, snapshot.sandboxId, taskId, call.callId, call.name, call.argumentsJson,
                            webSearch = searchPlan.session, assistantTools = boundTools)
                        currentCoroutineContext().ensureActive()
                        val saved = saveToolResult(chatId, call.callId, result)
                        if (regeneration != null) generatedNodeIds += chats.nodeIdForCandidate(saved.id)
                            ?: throw StError.NotFound("regeneration tool result was deleted")
                        messages += ContextMessage(ChatRole.tool, saved.content, toolCallId = call.callId,
                            attachments = (saved.images + saved.attachments).filter { it.modelVisible &&
                                (AttachmentMimeTypes.isBinary(AttachmentMimeTypes.normalize(it.mimeType)) ||
                                    AttachmentMimeTypes.isText(AttachmentMimeTypes.normalize(it.mimeType))) })
                    }
                    val resolved = prepareAttachments(messages)
                    messages.clear(); messages.addAll(resolved)
                    current.flush("succeeded")
                }
                if (!completed) throw StError.Provider("generation did not reach a final answer")
                fileSession.close()
                activeFiles = null
                if (turnIndex != null) {
                    // Each successful member is committed before the next member builds fresh context.
                    chatLocks.getOrPut(chatId) { Mutex() }.withLock {
                        currentCoroutineContext().ensureActive()
                        withContext(NonCancellable) {
                            lastCompleted = chats.transaction {
                                val task = tasks.get(taskId) ?: throw StError.NotFound(taskId)
                                if (task.status == TaskStatus.STOPPING || task.status.isTerminal) throw CancellationException("group stopped")
                                val saved = answer!!.flush("succeeded")
                                updateProgress(taskId) { progress -> progress.copy(currentTurnIndex = null,
                                    turns = progress.turns.mapIndexed { index, turn ->
                                        if (index == turnIndex) turn.copy(status = GroupTurnStatus.SUCCEEDED) else turn
                                    }) }
                                saved
                            }
                            // Commit and in-memory ownership transfer cannot be separated by cancellation.
                            answer = null
                        }
                    }
                }
                boundTools?.close()
                liveToolSessions.remove(boundTools)
            }
        } catch (e: CancellationException) {
            cancelled = true
        } catch (e: Exception) {
            if (!currentCoroutineContext().isActive) cancelled = true else failure = e
        } finally {
            if (!currentCoroutineContext().isActive) cancelled = true
            // Cancellation must not discard the most recent delta or strand a STOPPING task.
            withContext(NonCancellable) {
                activeFiles?.close()
                coroutineScope { liveToolSessions.map { session -> launch { session.close() } }.joinAll() }
                tools.cancelTask(taskId)
                tools.expireApprovalsForTask(taskId)
                chatLocks.getOrPut(chatId) { Mutex() }.withLock {
                  val notification = if (regeneration != null) {
                    var candidate: MessageCandidate? = lastCompleted
                    // Save the received reply independently, so a later target conflict does not lose it.
                    try { answer?.let { candidate = it.flush("received") } }
                    catch (error: Exception) {
                        failure = error
                        answer?.let { a -> candidate = chats.finishGenerationMetadata(chatId, a.initial.id,
                            a.metadata.copy(status = "failed", usage = a.usage, cost = a.cost,
                                resolvedModel = a.usage?.resolvedModel, fileTransfers = a.fileTransfers)) }
                    }
                    suspend fun persistTerminal(): Pair<StreamEvent.Error?, StreamEvent.Done>? = chats.transaction {
                        val latest = tasks.get(taskId)?.let(stopSignals::effective) ?: return@transaction null
                        if (latest.status.isTerminal) return@transaction null
                        val status = when {
                            latest.status == TaskStatus.STOPPING -> latest.stoppingStatus()
                            cancelled -> TaskStatus.INTERRUPTED
                            failure != null || !completed -> TaskStatus.FAILED
                            else -> TaskStatus.SUCCEEDED
                        }
                        if (status == TaskStatus.SUCCEEDED) {
                            candidate = chats.commitRegeneration(regeneration.target, regeneration.tailNodeIds, generatedNodeIds,
                                candidate?.id ?: throw StError.NotFound("regeneration final reply was deleted"))
                        }
                        candidate?.let { saved -> Jsonx.decodeOrNull<GenerationMetadata>(saved.generationMetadataJson)?.let { meta ->
                            candidate = chats.finishGenerationMetadata(chatId, saved.id, meta.copy(status = status.name.lowercase()))
                        } }
                        val error = failure?.let { safeError(it, activeProvider) }
                        finishProgress(taskId, status)
                        val terminal = tasks.update(taskId) { it.copy(status = status, terminalReason = stopSignals.completionReason(it, status),
                            resultSummary = "assistant=${candidate?.id}, regenerate=${regeneration.target.id}",
                            errorMessage = error ?: it.errorMessage, updatedAt = now(), incompleteFileChanges = it.incompleteFileChanges ||
                            (status != TaskStatus.SUCCEEDED && it.sandboxId != null && (activeProvider?.toolsJson != null || providerOverride?.toolsJson != null))) }
                        if (terminal.status == TaskStatus.SUCCEEDED && chats.getSession(chatId)?.overrides?.assistant == null) autoSummaryBoundary = candidate?.id
                        val event = if (terminal.status == TaskStatus.FAILED) StreamEvent.Error((failure as? StError)?.code ?: "error", error ?: "regeneration failed") else null
                        event to StreamEvent.Done(terminal.status == TaskStatus.SUCCEEDED, candidate?.id ?: answer?.initial?.id,
                            candidate?.reasoningContent, answer?.usage ?: Jsonx.decodeOrNull<UsageTokens>(candidate?.usageJson),
                            candidate?.generationMetadataJson?.let { Jsonx.decodeOrNull<GenerationMetadata>(it) })
                    }
                    try { persistTerminal() }
                    catch (error: StError) {
                        // The transaction rolled back; only persist the failure, never replay the request/tools.
                        failure = error
                        persistTerminal()
                    }
                  } else chats.transaction {
                    val latest = tasks.get(taskId)?.let(stopSignals::effective) ?: return@transaction null
                    if (latest.status.isTerminal) return@transaction null
                    var status = when {
                        latest.status == TaskStatus.STOPPING -> latest.stoppingStatus()
                        cancelled -> TaskStatus.INTERRUPTED
                        failure != null || !completed -> TaskStatus.FAILED
                        else -> TaskStatus.SUCCEEDED
                    }
                    var candidate: MessageCandidate? = lastCompleted
                    try {
                        answer?.let { candidate = it.flush(status.name.lowercase()) }
                    } catch (e: Exception) {
                        failure = e
                        if (status == TaskStatus.SUCCEEDED) status = TaskStatus.FAILED
                        answer?.let { a -> candidate = chats.finishGenerationMetadata(chatId, a.initial.id,
                            a.metadata.copy(status = status.name.lowercase(), usage = a.usage, cost = a.cost,
                                resolvedModel = a.usage?.resolvedModel, fileTransfers = a.fileTransfers)) }
                    }
                    val error = failure?.let { safeError(it, activeProvider) }
                    finishProgress(taskId, status)
                    val terminal = tasks.update(taskId) { it.copy(status = status, terminalReason = stopSignals.completionReason(it, status),
                        errorMessage = error ?: it.errorMessage, updatedAt = now(), incompleteFileChanges = it.incompleteFileChanges ||
                        (status != TaskStatus.SUCCEEDED && it.sandboxId != null && (activeProvider?.toolsJson != null || providerOverride?.toolsJson != null))) }
                    val errorEvent = if (terminal.status == TaskStatus.FAILED) StreamEvent.Error((failure as? StError)?.code ?: "error", error ?: "generation failed") else null
                    if (terminal.status == TaskStatus.SUCCEEDED && chats.getSession(chatId)?.overrides?.assistant == null) autoSummaryBoundary = candidate?.id
                    errorEvent to StreamEvent.Done(terminal.status == TaskStatus.SUCCEEDED, candidate?.id ?: answer?.initial?.id,
                        candidate?.reasoningContent, answer?.usage ?: Jsonx.decodeOrNull<UsageTokens>(candidate?.usageJson),
                        candidate?.generationMetadataJson?.let { Jsonx.decodeOrNull<GenerationMetadata>(it) })
                  }
                  // Publish terminal events only after the transaction has committed.
                  notification?.let { (error, done) -> error?.let { flow.tryEmit(it) }; flow.tryEmit(done) }
                }
            }
        }
        val boundary = autoSummaryBoundary
        if (boundary != null) {
            val handoff = tasks.retainHostHandoff()
            val followUps = scope.launch(start = CoroutineStart.LAZY) {
                coroutineScope {
                    launch {
                        try { memory.maybeAutoSummarize(chatId, boundary) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { memory.logAutoSummaryFailure(chatId, e) }
                    }
                    launch {
                        try { memory.maybeAutoMaintain(chatId, boundary) }
                        catch (e: CancellationException) { throw e }
                        catch (e: Exception) { memory.logAutoMaintenanceFailure(chatId, e) }
                    }
                }
            }
            followUps.invokeOnCompletion { handoff?.close() }
            followUps.start()
        }
    }

    private fun safeError(e: Throwable, provider: ProviderConfig?): String {
        val message = if (e is StError) e.message else "generation failed (${e.javaClass.simpleName})"
        return provider?.redact(message) ?: message
    }

    private fun now() = Instant.now().toString()
    private fun id() = UUID.randomUUID().toString().replace("-", "")
}

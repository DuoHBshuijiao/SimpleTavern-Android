package com.simpletavern.core.conversation

import com.simpletavern.core.data.ChatRepository
import com.simpletavern.core.data.ContentRepository
import com.simpletavern.core.data.TaskStore
import com.simpletavern.core.data.UsageStore
import com.simpletavern.core.data.db.UsageEventEntity
import com.simpletavern.core.llm.LlmClient
import com.simpletavern.core.llm.LlmRequest
import com.simpletavern.core.llm.ProviderConfig
import com.simpletavern.core.memory.ContextBuilder
import com.simpletavern.core.memory.MemoryService
import com.simpletavern.core.model.*
import com.simpletavern.core.tools.ToolRuntime
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
) {
    private val chatLocks = ConcurrentHashMap<String, Mutex>()
    private val runningJobs = ConcurrentHashMap<String, Job>()
    private val events = ConcurrentHashMap<String, MutableSharedFlow<StreamEvent>>()

    fun observe(chatId: String): Flow<StreamEvent> =
        events.getOrPut(chatId) { MutableSharedFlow(extraBufferCapacity = 256) }.asSharedFlow()

    suspend fun createChat(
        characterId: String,
        title: String = "新对话",
        isGroup: Boolean = false,
        memberIds: List<String> = emptyList(),
        sandboxId: String? = null,
    ): ChatSession {
        val now = now()
        val character = content.getCharacter(characterId)
        val session = ChatSession(
            id = id(),
            characterId = characterId,
            title = title,
            isGroup = isGroup,
            memberIds = memberIds,
            sandboxId = sandboxId,
            createdAt = now,
            updatedAt = now,
        )
        chats.saveSession(session)
        val first = character?.firstMessage?.takeIf { it.isNotBlank() }
        if (first != null) {
            chats.appendNode(
                session.id,
                MessageCandidate(id = id(), role = ChatRole.assistant, content = first, characterId = characterId, createdAt = now),
            )
        }
        character?.initialStateTablesJson?.let {
            memory.bootstrapMvuFromCharacter(session.id, it)
        }
        return session
    }

    suspend fun fork(sourceChatId: String, forkAtMessageId: String, newTitle: String?): ChatSession {
        val source = chats.getSession(sourceChatId) ?: throw StError.NotFound("chat $sourceChatId")
        val all = chats.pageMessages(sourceChatId, limit = Int.MAX_VALUE, offset = 0)
        val flat = all.mapIndexed { idx, node -> idx to node }
        val anchor = flat.firstOrNull { (_, n) -> n.selected?.id == forkAtMessageId || n.id == forkAtMessageId }
            ?: throw StError.NotFound("message $forkAtMessageId")
        val keep = flat.filter { (idx, _) -> idx <= anchor.first }.map { it.second }
        val now = now()
        val session = source.copy(
            id = id(),
            title = newTitle ?: "分叉：${source.title}",
            forkedFromChatId = source.id,
            forkedFromMessageId = forkAtMessageId,
            forkedFromMessageIndex = anchor.first + 1,
            createdAt = now,
            updatedAt = now,
            // MVU/KG intentionally not copied (hard fork semantics)
        )
        chats.saveSession(session)
        val copied = keep.mapIndexed { idx, node ->
            node.copy(
                id = id(),
                chatId = session.id,
                orderIndex = idx.toLong(),
                candidates = node.candidates.map { it.copy(id = id()) },
            )
        }
        chats.replaceAllMessages(session.id, copied)
        return session
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
        provider: ProviderConfig,
        interjectCharacterId: String? = null,
        persistUser: Boolean = true,
    ): TaskSnapshot {
        val mutex = chatLocks.getOrPut(chatId) { Mutex() }
        return mutex.withLock {
            val active = tasks.activeGeneration(chatId)
            if (active.isNotEmpty()) {
                throw StError.Conflict("session already generating", mapOf("taskId" to active.first().id))
            }
            val now = now()
            val task = TaskSnapshot(
                id = id(),
                kind = if (interjectCharacterId != null) TaskKind.GROUP_GENERATION else TaskKind.CHAT_GENERATION,
                status = TaskStatus.QUEUED,
                chatId = chatId,
                createdAt = now,
                updatedAt = now,
            )
            tasks.upsert(task)
            val flow = events.getOrPut(chatId) { MutableSharedFlow(extraBufferCapacity = 256) }
            val job = scope.launch {
                runGeneration(task.id, chatId, userText, provider, interjectCharacterId, persistUser, flow)
            }
            runningJobs[task.id] = job
            task
        }
    }

    suspend fun stop(taskId: String): TaskSnapshot {
        val task = tasks.get(taskId) ?: throw StError.NotFound(taskId)
        if (task.status.isTerminal) return task
        val stopping = task.copy(status = TaskStatus.STOPPING, updatedAt = now())
        tasks.upsert(stopping)
        runningJobs[taskId]?.cancel()
        tools.cancelTask(taskId)
        val stopped = stopping.copy(
            status = TaskStatus.STOPPED,
            terminalReason = TaskTerminalReason.USER_STOP,
            updatedAt = now(),
        )
        tasks.upsert(stopped)
        return stopped
    }

    suspend fun markInterruptedOnProcessStart() {
        tasks.nonTerminal().forEach { t ->
            tasks.upsert(
                t.copy(
                    status = TaskStatus.INTERRUPTED,
                    terminalReason = TaskTerminalReason.PROCESS_KILLED,
                    updatedAt = now(),
                    incompleteFileChanges = true,
                ),
            )
            tools.expireApprovalsForTask(t.id)
        }
    }

    private suspend fun runGeneration(
        taskId: String,
        chatId: String,
        userText: String?,
        provider: ProviderConfig,
        interjectCharacterId: String?,
        persistUser: Boolean,
        flow: MutableSharedFlow<StreamEvent>,
    ) {
        var snapshot = tasks.get(taskId) ?: return
        try {
            snapshot = snapshot.copy(status = TaskStatus.RUNNING, updatedAt = now())
            tasks.upsert(snapshot)
            val session = chats.getSession(chatId) ?: throw StError.NotFound(chatId)
            if (persistUser && !userText.isNullOrBlank()) {
                chats.appendNode(
                    chatId,
                    MessageCandidate(id = id(), role = ChatRole.user, content = userText, createdAt = now()),
                )
            }
            val ctx = contextBuilder.build(session, characterIdOverride = interjectCharacterId)
            val requestId = id()
            val req = LlmRequest(
                requestId = requestId,
                protocol = provider.protocol,
                model = provider.model,
                messages = ctx.messages,
                temperature = provider.temperature,
                topP = provider.topP,
                toolsJson = provider.toolsJson,
                extra = provider.extra,
            )
            val assistantId = id()
            val contentBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            var usageTokens: UsageTokens? = null
            llm.stream(provider, req).collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> {
                        if (ev.reasoning) reasoningBuf.append(ev.text) else contentBuf.append(ev.text)
                        flow.emit(ev)
                    }
                    is StreamEvent.ToolCall -> {
                        flow.emit(ev)
                        val result = tools.executeBound(
                            sessionId = chatId,
                            sandboxId = session.sandboxId,
                            taskId = taskId,
                            callId = ev.callId,
                            name = ev.name,
                            argumentsJson = ev.argumentsJson,
                        )
                        // Tool results are persisted; execution is not replayed on resubscribe.
                        chats.appendNode(
                            chatId,
                            MessageCandidate(
                                id = id(),
                                role = ChatRole.tool,
                                content = result.output,
                                toolCallId = ev.callId,
                                createdAt = now(),
                                toolRecordJson = result.recordJson,
                            ),
                        )
                    }
                    is StreamEvent.Usage -> {
                        usageTokens = ev.usage
                        flow.emit(ev)
                    }
                    is StreamEvent.Done -> flow.emit(ev)
                    is StreamEvent.Error -> flow.emit(ev)
                }
            }
            val candidate = MessageCandidate(
                id = assistantId,
                role = ChatRole.assistant,
                content = contentBuf.toString(),
                reasoningContent = reasoningBuf.toString().ifBlank { null },
                characterId = interjectCharacterId ?: session.characterId.takeIf { session.isGroup.not() },
                usageJson = usageTokens?.let { com.simpletavern.core.data.Jsonx.encode(it) },
                generationMetadataJson = com.simpletavern.core.data.Jsonx.encode(
                    GenerationMetadata(
                        requestId = requestId,
                        chatId = chatId,
                        messageId = assistantId,
                        provider = provider.providerId,
                        protocol = provider.protocol.name,
                        requestedModel = provider.model,
                        resolvedModel = provider.model,
                        startedAt = snapshot.createdAt,
                        status = "succeeded",
                        usage = usageTokens,
                    ),
                ),
                createdAt = now(),
            )
            chats.appendNode(chatId, candidate)
            usageTokens?.let {
                usage.record(
                    UsageEventEntity(
                        id = id(),
                        eventId = requestId,
                        requestId = requestId,
                        chatId = chatId,
                        provider = provider.providerId,
                        model = provider.model,
                        usageJson = com.simpletavern.core.data.Jsonx.encode(it),
                        costJson = null,
                        createdAt = now(),
                    ),
                )
            }
            chats.getSession(chatId)?.let { s ->
                chats.saveSession(s.copy(updatedAt = now()))
                memory.maybeAutoSummarize(s)
            }
            flow.emit(StreamEvent.Done(ok = true, assistantMessageId = assistantId, reasoningContent = candidate.reasoningContent, usage = usageTokens))
            tasks.upsert(
                snapshot.copy(
                    status = TaskStatus.SUCCEEDED,
                    terminalReason = TaskTerminalReason.COMPLETED,
                    updatedAt = now(),
                    resultSummary = "assistant=$assistantId",
                ),
            )
        } catch (c: kotlinx.coroutines.CancellationException) {
            // stop() owns terminal state
            throw c
        } catch (t: Throwable) {
            flow.emit(StreamEvent.Error(t.let { (it as? StError)?.code } ?: "error", t.message ?: "error"))
            tasks.upsert(
                snapshot.copy(
                    status = TaskStatus.FAILED,
                    terminalReason = TaskTerminalReason.ERROR,
                    errorMessage = t.message,
                    updatedAt = now(),
                ),
            )
        } finally {
            runningJobs.remove(taskId)
        }
    }

    private fun now() = Instant.now().toString()
    private fun id() = UUID.randomUUID().toString().replace("-", "")
}

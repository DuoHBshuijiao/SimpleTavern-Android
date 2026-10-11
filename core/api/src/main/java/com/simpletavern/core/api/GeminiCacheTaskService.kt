package com.simpletavern.core.api

import com.simpletavern.core.data.*
import com.simpletavern.core.llm.*
import com.simpletavern.core.model.*
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.json.*

/** Explicit new operations only; public metadata lives in tasks, prompts/keys/bindings stay process-local. */
@OptIn(ExperimentalCoroutinesApi::class, DelicateCoroutinesApi::class)
internal class GeminiCacheTaskService(private val chats: ChatRepository, private val providers: ProviderResolver,
    private val tasks: TaskStore, private val gate: DataOperationGate, private val scope: CoroutineScope,
    private val prices: GeminiCachePriceStore, private val attachments: MessageAttachmentReader,
    private val client: GeminiCacheClient = GeminiCacheClient()) {
    private val epoch = UUID.randomUUID().toString()
    private val control = Mutex()
    private val workers = ConcurrentHashMap<String, Job>()
    private val bindings = mutableMapOf<String, GeminiCachedContext>()
    private class Confirmation(val taskId: String, val connection: GeminiCacheConnection)
    private val confirmations = linkedMapOf<Pair<GeminiCacheOwner, String>, Confirmation>()
    private class ListingSession { val tokens = mutableSetOf<String>(); var pages = 0; var tokenBytes = 0L }
    private class ListingConfirmation(val connection: GeminiCacheConnection, val token: String, val session: ListingSession) {
        var consumed = false
        override fun toString() = "ListingConfirmation(<redacted>)"
    }
    private val listingConfirmations = linkedMapOf<String, ListingConfirmation>()
    private val busy = mutableSetOf<String>()
    private val automatic = Mutex()
    private val inUse = mutableMapOf<String, Int>()
    private val signals = WorkerStopSignals()
    private val httpSlots = Semaphore(2)
    private val latest = MutableStateFlow<GeminiCacheTaskProgress?>(null)
    fun observe(): StateFlow<GeminiCacheTaskProgress?> = latest
    fun signalHostInterruption(reason: TaskTerminalReason) = signals.interrupt(reason, workers)

    fun selection(chatId: String, characterId: String?) = providers.selectedCache(chatId, characterId)
    suspend fun select(chatId: String, selection: GeminiCacheGenerationSelection?, characterId: String?): GeminiCacheGenerationSelection? =
        withContext(Dispatchers.IO) { gate.write {
            if (selection == null) { providers.selectCache(chatId, characterId, null); return@write null }
            val (owner, provider) = connection(chatId, characterId)
            control.withLock {
                pruneBindings()
                val saved = progress(selection.taskId)
                val binding = bindings[selection.taskId] ?: throw StError.Conflict("cache has no live context binding in this process")
                if (saved.action != GeminiCacheAction.CREATE || saved.owner != owner || saved.processEpoch != epoch ||
                    !binding.matchesConnection(provider)) throw StError.Conflict("selected cache belongs to a different chat, member or connection")
                if (resourceKey(owner, binding.metadata.name) in busy) throw StError.Conflict("cache management is in progress")
                providers.selectCache(chatId, characterId, selection)
                selection
            }
        } }

    /** Called only for a new collected generation, after native body validation, on the LLM IO dispatcher. */
    suspend fun prepareUse(provider: ProviderConfig, request: LlmRequest, body: JsonObject, files: GeminiCacheFiles): GeminiCacheUse {
        val plan = provider.cachePlan ?: throw StError.Validation("explicit cache plan missing")
        // LlmClient checked the frozen plan before uploads; here recheck mutable database/connection state.
        client.validateConnection(provider)
        val owner = plan.owner
        val lease = gate.sharedLease()
        var use: GeminiCacheUse? = null
        var handedOff = false
        try {
            val chat = chats.getSession(owner.chatId) ?: throw StError.NotFound("cache chat missing")
            if (chat.sourceNamespace != owner.sourceNamespace || owner.characterId?.let { it != chat.characterId && it !in chat.memberIds } == true)
                throw StError.Conflict("explicit cache chat or source changed")
            val (currentOwner, currentProvider) = connection(owner.chatId, owner.characterId)
            if (currentOwner != owner || !GeminiCacheConnection(provider).matches(currentProvider))
                throw StError.Conflict("explicit cache connection changed before preparation")
            automatic.withLock {
                currentCoroutineContext().ensureActive()
                val selection = plan.selection
                val id = if (selection != null) control.withLock {
                    pruneBindings()
                    val saved = progress(selection.taskId)
                    if (saved.owner != owner || saved.action != GeminiCacheAction.CREATE || saved.processEpoch != epoch ||
                        bindings[selection.taskId]?.matchesConnection(provider) != true)
                        throw StError.Conflict("selected cache has no matching live binding; clear or explicitly create/select a new cache")
                    selection.taskId
                } else run {
                    val system = request.messages.filter { it.role == ChatRole.system }.joinToString("\n") { it.content }
                    val history = if (plan.historyTail) request.messages.filter { it.role != ChatRole.system }.dropLast(1) else emptyList()
                    val sourceFiles = files.select(history)
                    // Like desktop, no cache operation applies when there is no immutable context to cache.
                    if (system.isBlank() && (body["tools"] as? JsonArray).isNullOrEmpty() && history.isEmpty()) {
                        use = GeminiCacheUse(provider.copy(cachePlan = null)) { lease.close() }
                        return@withLock
                    }
                    val existing = control.withLock {
                        pruneBindings()
                        // Retire stale connection/configuration bindings without deleting remote data.
                        for (id in bindings.keys.toList()) {
                            val saved = progress(id)
                            if (saved.automatic && saved.owner.chatId == owner.chatId && saved.owner.characterId == owner.characterId &&
                                (saved.owner != owner || saved.ttlSeconds != plan.ttlSeconds || saved.historyTail != plan.historyTail ||
                                    bindings[id]?.matchesConnection(provider) != true ||
                                    saved.automatic && bindings[id]?.matches(provider, body, files) != true)) bindings.remove(id)
                        }
                        bindings.entries.firstOrNull { (id, binding) ->
                            val saved = progress(id)
                            saved.owner == owner && saved.automatic && saved.ttlSeconds == plan.ttlSeconds &&
                                saved.historyTail == plan.historyTail && binding.matches(provider, body, files)
                        }?.key
                    }
                    existing ?: run {
                        if (history.size > 4096) throw StError.Validation("cache history exceeds message budget")
                        sourceFiles.requireLifetime(plan.ttlSeconds)
                        val at = now()
                        val state = GeminiCacheTaskProgress(newId(), owner, GeminiCacheAction.CREATE, at, at, epoch,
                            ttlSeconds = plan.ttlSeconds, generationRequestId = request.requestId, automatic = true, historyTail = plan.historyTail,
                            generationTaskId = request.taskId, accountingVersion = 1, pricing = prices.freeze(owner, at), lifecycleVersion = 1,
                            sourceFilesExpireTime = sourceFiles.sourceFilesExpireTime)
                        val taskLease = gate.sharedLease()
                        var registered = false
                        try {
                            enqueue(state, provider, taskLease, "create:${owner.chatId}:${owner.characterId.orEmpty()}", { registered = true }) { checkpoint, _ ->
                                val binding = client.create(provider, system, request.toolsJson, plan.ttlSeconds, history, request.nativeWebSearch,
                                    checkpoint, sourceFiles)
                                Result(binding.metadata, binding)
                            }
                            workers[state.taskId]?.join()
                            currentCoroutineContext().ensureActive()
                            val task = tasks.cacheOperation(state.taskId).first
                            if (task.status != TaskStatus.SUCCEEDED) throw StError.Provider("explicit cache creation did not complete; do not retry automatically")
                        } catch (error: CancellationException) {
                            if (registered) withContext(NonCancellable) {
                                // Preserve a known parent/platform reason, otherwise mark cancellation as interruption.
                                val parent = tasks.get(request.taskId ?: request.requestId)
                                val reason = parent?.terminalReason?.takeIf { parent.status == TaskStatus.STOPPING || parent.status.isTerminal }
                                    ?: TaskTerminalReason.HOST_UNAVAILABLE
                                stop(state.taskId, reason)
                            }
                            throw error
                        } finally { if (!registered) taskLease.close() }
                        state.taskId
                    }
                }
                control.withLock {
                    val binding = (bindings[id] ?: throw StError.Conflict("created cache has no live binding"))
                        .forGeneration(selection?.contentMode ?: GeminiCacheContentMode.MATCH_PREFIX)
                    val saved = progress(id)
                    if (saved.processEpoch != epoch || !binding.matches(provider, body, files))
                        throw StError.Conflict("cache no longer matches the generation prefix")
                    val key = resourceKey(owner, binding.metadata.name)
                    if (key in busy) throw StError.Conflict("a cache management operation owns this resource")
                    inUse[key] = (inUse[key] ?: 0) + 1
                    use = GeminiCacheUse(provider.copy(cachedContext = binding, cachePlan = null)) {
                        try { control.withLock {
                            val count = (inUse[key] ?: 1) - 1
                            if (count == 0) inUse.remove(key) else inUse[key] = count
                        } } finally { lease.close() }
                    }
                }
            }
            currentCoroutineContext().ensureActive()
            val prepared = use ?: throw StError.Conflict("cache use was not prepared")
            handedOff = true
            return prepared
        } finally { if (!handedOff) { if (use != null) use!!.close() else lease.close() } }
    }

    suspend fun create(request: GeminiCacheCreateRequest): TaskSnapshot = withContext(Dispatchers.IO) {
        if (request.ttlSeconds !in 1..604800 || request.contents.size > 4096 ||
            Jsonx.encode(request).toByteArray(Charsets.UTF_8).size > 1_048_576)
            throw StError.Validation("cache request exceeds input budget")
        // A public request selects attachment IDs, not caller-supplied paths/remote capabilities.
        val input = request.copy(contents = request.contents.map { it.copy(attachments = it.attachments.toList(), media = emptyList()) })
        val lease = gate.sharedLease()
        var transferred = false
        try {
            val (owner, provider) = connection(input.chatId, input.characterId)
            val at = now()
            val state = GeminiCacheTaskProgress(newId(), owner, GeminiCacheAction.CREATE, at, at, epoch,
                ttlSeconds = input.ttlSeconds, accountingVersion = 1, pricing = prices.freeze(owner, at), lifecycleVersion = 1)
            val key = "create:${owner.chatId}:${owner.characterId.orEmpty()}"
            val task = enqueue(state, provider, lease, key, { transferred = true }) { checkpoint, updateFiles ->
                val contents = attachments.prepare(input.contents)
                var transfers: List<ProviderFileTransfer> = emptyList()
                val binding = client.createWithFiles(provider, input.system, input.toolsJson, input.ttlSeconds,
                    contents, input.nativeWebSearch, beforeExecute = { sources ->
                        updateFiles(transfers, sources.sourceFilesExpireTime)
                        checkpoint()
                    }, onFileTransfers = { current ->
                        transfers = current
                        updateFiles(current, null)
                    })
                Result(binding.metadata, binding)
            }
            task
        } finally { if (!transferred) lease.close() }
    }

    suspend fun manage(chatId: String, referenceTaskId: String, action: GeminiCacheAction, ttlSeconds: Long? = null,
        expireTime: String? = null, expectedFrontier: List<String>? = null): TaskSnapshot = withContext(Dispatchers.IO) {
        if (action in setOf(GeminiCacheAction.CREATE, GeminiCacheAction.LIST)) throw StError.Validation("use the dedicated create/list entry")
        val lease = gate.sharedLease()
        var transferred = false
        try {
            val previous = progress(referenceTaskId)
            val resource = previous.resource ?: throw StError.Conflict("reference has no confirmed cache resource")
            if (!previous.remoteCompleted || previous.action == GeminiCacheAction.DELETE)
                throw StError.Conflict("reference has no confirmed reusable cache resource")
            val (owner, provider) = connection(chatId, previous.owner.characterId)
            if (owner != previous.owner) throw StError.Conflict("cache owner differs from the current chat connection")
            if (action != GeminiCacheAction.GET && previous.processEpoch != epoch)
                throw StError.Conflict("inspect the historical cache with a new GET task before mutating it")
            val at = now()
            val state = GeminiCacheTaskProgress(newId(), owner, action, at, at, epoch, referenceTaskId = referenceTaskId,
                resourceName = resource.name, ttlSeconds = ttlSeconds, requestedExpireTime = expireTime, accountingVersion = 1,
                sourceFilesExpireTime = previous.sourceFilesExpireTime)
            val name = resource.name
            requireSourceLifetime(state)
            val key = resourceKey(owner, name)
            val task = enqueue(state, provider, lease, key, { transferred = true }, expectedFrontier) { checkpoint, _ ->
                when (action) {
                    GeminiCacheAction.GET -> Result(client.get(provider, name, checkpoint))
                    GeminiCacheAction.UPDATE_TTL -> Result(client.updateTtl(provider, name, ttlSeconds!!, checkpoint))
                    GeminiCacheAction.UPDATE_EXPIRY -> Result(client.updateExpireTime(provider, name, expireTime!!, checkpoint))
                    GeminiCacheAction.DELETE -> { client.delete(provider, name, checkpoint); Result(null) }
                    else -> throw StError.Validation("invalid cache operation")
                }
            }
            task
        } finally { if (!transferred) lease.close() }
    }

    /** One explicitly requested page per durable task. No startup query, automatic next page or token replay. */
    suspend fun list(chatId: String, characterId: String? = null, pageSize: Int = 100,
        previousListTaskId: String? = null): TaskSnapshot = withContext(Dispatchers.IO) {
        if (pageSize !in 1..1000) throw StError.Validation("invalid cache listing page size")
        val lease = gate.sharedLease(); var transferred = false
        try {
            val (owner, provider) = connection(chatId, characterId)
            val prior = previousListTaskId?.let { progress(it) }
            if (prior != null && (prior.action != GeminiCacheAction.LIST || prior.owner != owner || prior.phase != "completed" ||
                    prior.listingPageSize != pageSize || prior.processEpoch != epoch || prior.listing?.hasNextPage != true))
                throw StError.Conflict("listing continuation differs from the confirmed page; start a new listing")
            val proof = control.withLock {
                previousListTaskId?.let { id -> listingConfirmations[id]?.takeIf { !it.consumed && it.connection.matches(provider) }
                    ?: throw StError.Conflict("listing token is unavailable, consumed or belongs to changed credentials; start a new listing") }
            }
            val session = proof?.session ?: ListingSession()
            val at = now(prior)
            val state = GeminiCacheTaskProgress(newId(), owner, GeminiCacheAction.LIST, at, at, epoch,
                referenceTaskId = previousListTaskId, accountingVersion = 1, listingPageSize = pageSize)
            enqueue(state, provider, lease, Jsonx.encode(listOf("listing", owner.providerId, owner.baseUrl)), { transferred = true }) { checkpoint, _ ->
                val page = client.list(provider, pageSize, proof?.token, checkpoint)
                control.withLock {
                    page.nextPageToken?.let { session.tokenBytes += it.toByteArray(Charsets.UTF_8).size }
                    if (++session.pages > 10000 || session.tokenBytes > 262144 || page.nextPageToken?.let { !session.tokens.add(it) } == true)
                        throw StError.Provider("cache listing pagination exceeded budget or repeated a token; do not retry")
                }
                Result(null, page = page, listingSession = session)
            }
        } finally { if (!transferred) lease.close() }
    }

    suspend fun progress(id: String): GeminiCacheTaskProgress = withContext(Dispatchers.IO) {
        tasks.cacheOperationRecord(id).operation
    }
    suspend fun page(after: String = "", limit: Int = 100): List<GeminiCacheTaskProgress> = withContext(Dispatchers.IO) {
        tasks.cacheOperationRecords(after, limit).map { it.operation }
    }
    suspend fun resource(referenceTaskId: String, at: String = now()): GeminiCacheResourceState = withContext(Dispatchers.IO) {
        gate.write {
            val reference = progress(referenceTaskId)
            val name = reference.resourceName ?: throw StError.Conflict("operation has no known resource name")
            GeminiCacheLifecycle.project(reference.owner, name, tasks.cacheResourceHistory(reference.owner.chatId), at)
        }
    }
    suspend fun storage(referenceTaskId: String, at: String): GeminiCacheStorageView = withContext(Dispatchers.IO) {
        gate.write {
            val reference = progress(referenceTaskId)
            val name = reference.resourceName ?: throw StError.Conflict("operation has no known resource name")
            GeminiCacheStorage.projectFromValidatedHistory(reference.owner, name, tasks.cacheResourceHistory(reference.owner.chatId), at)
        }
    }
    suspend fun resources(chatId: String, afterKey: String = "", limit: Int = 100, at: String = now()): GeminiCacheResourcePage =
        withContext(Dispatchers.IO) { gate.write {
            if (limit !in 1..200 || afterKey.toByteArray(Charsets.UTF_8).size > 32768)
                throw StError.Validation("invalid cache resource page")
            val history = tasks.cacheResourceHistory(chatId)
            val keys = history.mapNotNull { value -> value.resourceName?.let { value.owner to it } }.distinct()
            val selected = keys.map { (owner, name) -> resourceViewKey(owner, name) to (owner to name) }
                .filter { it.first > afterKey }.sortedBy { it.first }.take(limit + 1)
            val items = selected.take(limit).map { (key, identity) -> GeminiCacheResourceItem(key,
                GeminiCacheLifecycle.project(identity.first, identity.second, history, at)) }
            GeminiCacheResourcePage(items, items.lastOrNull()?.key, selected.size > limit)
        } }
    suspend fun stop(id: String, reason: TaskTerminalReason): TaskSnapshot = withContext(NonCancellable) {
        val worker = control.withLock {
            val task = tasks.cacheOperation(id).first
            if (task.status.isTerminal) return@withLock null
            val first = signals.first(id, reason)
            val worker = workers[id]
            tasks.requestStop(id, first)
            worker?.cancel()
            worker
        }
        worker?.join()
        if (worker == null) recoverOne(id)
        tasks.cacheOperation(id).first
    }

    /** Only interrupts old queued/executing rows; never queries, deletes, resumes or recreates remote resources. */
    suspend fun onProcessStart() = withContext(Dispatchers.IO) {
        var after = ""
        var seen = 0
        while (true) {
            val rows = tasks.cacheOperations(after, 100, pending = true)
            if (rows.isEmpty()) break
            for ((task, _) in rows) {
                if (++seen > 10000) throw StError.Validation("cache recovery exceeds operation budget")
                recoverOne(task.id)
                after = task.id
            }
        }
    }
    private suspend fun recoverOne(id: String) = withContext(NonCancellable + Dispatchers.IO) { control.withLock {
        val record = tasks.cacheOperationRecord(id)
        val task = record.task
        val raw = record.payload
        if (task.status.isTerminal) return@withLock
        val saved = record.operation
        val at = now(saved)
        val status = if (task.status == TaskStatus.STOPPING) task.stoppingStatus() else TaskStatus.INTERRUPTED
        val next = saved.copy(phase = status.name.lowercase(), endedAt = at, updatedAt = at, errorCode = "cache_operation_interrupted")
        val original = NativeJsonInput.parse(raw) as? JsonObject ?: throw StError.Validation("invalid cache operation payload")
        val payload = JsonObject(original + mapOf("phase" to JsonPrimitive(next.phase), "endedAt" to JsonPrimitive(at),
            "updatedAt" to JsonPrimitive(at), "errorCode" to JsonPrimitive(next.errorCode!!))).toString()
        if (payload.toByteArray(Charsets.UTF_8).size > GeminiCacheTaskMetadata.byteLimit(saved.action))
            throw StError.Validation("recovered cache operation exceeds budget")
        tasks.upsertCacheOperation(task.copy(status = status, terminalReason = if (task.status == TaskStatus.STOPPING) task.completionReason(status) else TaskTerminalReason.PROCESS_KILLED,
            updatedAt = at, errorMessage = next.errorCode), payload)
    } }

    private suspend fun connection(chatId: String, characterId: String?): Pair<GeminiCacheOwner, ProviderConfig> {
        val chat = chats.getSession(chatId) ?: throw StError.NotFound("cache chat missing")
        if (characterId != null && characterId != chat.characterId && characterId !in chat.memberIds)
            throw StError.Validation("cache character does not belong to this chat")
        val provider = providers.resolveCacheConnection(chat, characterId)
        client.validateConnection(provider)
        val owner = GeminiCacheOwner(chat.id, characterId, chat.sourceNamespace, provider.providerId, provider.model,
            provider.baseUrl, ProviderHttpOptions.normalizeAuth(provider.httpOptions.authStyle, provider.protocol),
            provider.presetId)
        if (provider.containsCredential(Jsonx.encode(owner))) throw StError.Validation("cache owner metadata contains credential material")
        return owner to provider
    }
    private data class Result(val metadata: GeminiCacheMetadata?, val binding: GeminiCachedContext? = null,
        val page: GeminiCachePage? = null, val listingSession: ListingSession? = null)
    private suspend fun enqueue(state: GeminiCacheTaskProgress, provider: ProviderConfig, lease: AutoCloseable,
        resourceKey: String, transfer: () -> Unit, expectedFrontier: List<String>? = null,
        operation: suspend (suspend () -> Unit, suspend (List<ProviderFileTransfer>, String?) -> Unit) -> Result): TaskSnapshot = control.withLock {
        currentCoroutineContext().ensureActive()
        if (!scope.isActive || workers.size >= 16) throw StError.Conflict("cache task host is stopped or queue is full")
        pruneBindings()
        if (state.action == GeminiCacheAction.CREATE && bindings.size + workers.size >= 16)
            throw StError.Conflict("cache binding capacity reached; delete or wait for expiration")
        if ((inUse[resourceKey] ?: 0) > 0 || !busy.add(resourceKey)) throw StError.Conflict("generation or another cache operation owns this resource")
        try {
            val listParentId = state.referenceTaskId
            if (state.action == GeminiCacheAction.LIST && listParentId != null) {
                val proof = listingConfirmations[listParentId]
                if (proof == null || proof.consumed || !proof.connection.matches(provider))
                    throw StError.Conflict("cache listing continuation changed before registration")
            }
            val queued = if (state.action in setOf(GeminiCacheAction.CREATE, GeminiCacheAction.LIST)) state else {
                val name = state.resourceName ?: throw StError.Validation("cache resource name missing")
                val view = GeminiCacheLifecycle.project(state.owner, name, tasks.cacheResourceHistory(state.owner.chatId), now())
                if (expectedFrontier != null && (expectedFrontier.size !in 1..64 || expectedFrontier.distinct().size != expectedFrontier.size ||
                        expectedFrontier.sorted() != view.frontierTaskIds)) throw StError.Conflict("cache resource changed; inspect before cleanup")
                if (state.action != GeminiCacheAction.GET) {
                    val confirmation = confirmations[state.owner to name]
                    if (view.status !in setOf("available", "expired") || view.confirmedTaskId != state.referenceTaskId ||
                        confirmation == null || confirmation.taskId != state.referenceTaskId || !confirmation.connection.matches(provider))
                        throw StError.Conflict("use the latest resource confirmation with unchanged credentials; explicitly GET before mutation")
                }
                val at = maxOf(OffsetDateTime.parse(state.createdAt).toInstant(), OffsetDateTime.parse(view.latestOperationAt).toInstant()).toString()
                state.copy(createdAt = at, updatedAt = at, lifecycleVersion = 1, lifecyclePreviousTaskIds = view.frontierTaskIds,
                    storagePricing = if (state.action in setOf(GeminiCacheAction.UPDATE_TTL, GeminiCacheAction.UPDATE_EXPIRY))
                        prices.freeze(state.owner, at) else null)
            }
            val payload = encode(queued, provider)
            withContext(NonCancellable) {
                val task = TaskSnapshot(queued.taskId, GeminiCacheTaskMetadata.kind(queued.action), TaskStatus.QUEUED,
                    chatId = queued.owner.chatId, createdAt = queued.createdAt, updatedAt = queued.updatedAt)
                signals.register(task.id)
                try { tasks.upsertCacheOperation(task, payload) } catch (error: Throwable) { signals.forget(task.id); throw error }
                latest.value = queued
                val ready = CompletableDeferred<Unit>()
                val worker = scope.launch(start = CoroutineStart.ATOMIC) { run(task, queued, provider, lease, ready, resourceKey, operation) }
                workers[task.id] = worker
                worker.invokeOnCompletion {
                    workers.remove(task.id, worker); signals.forget(task.id)
                }
                signals.bind(task.id, worker)
                if (queued.action == GeminiCacheAction.LIST) queued.referenceTaskId?.let { listingConfirmations.getValue(it).consumed = true }
                transfer()
                ready.complete(Unit)
                task
            }
        } catch (error: Throwable) { busy.remove(resourceKey); throw error }
    }

    private suspend fun run(initial: TaskSnapshot, original: GeminiCacheTaskProgress, provider: ProviderConfig,
        lease: AutoCloseable, ready: Deferred<Unit>, resourceKey: String,
        operation: suspend (suspend () -> Unit, suspend (List<ProviderFileTransfer>, String?) -> Unit) -> Result) {
        var state = original
        var handoff: AutoCloseable? = null
        var failure = false
        var cancelled = false
        var result: Result? = null
        try {
            ready.await()
            handoff = tasks.retainHostHandoff()
            result = httpSlots.withPermit { operation({
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable + Dispatchers.IO) { control.withLock {
                    val current = tasks.get(initial.id)?.let(signals::effective) ?: throw StError.NotFound("cache task missing")
                    if (current.status.isTerminal || current.status == TaskStatus.STOPPING) throw CancellationException("cache stopped")
                    if (state.httpAttempted) throw StError.Conflict("cache operation cannot execute twice")
                    requireSourceLifetime(state)
                    val (currentOwner, currentProvider) = connection(state.owner.chatId, state.owner.characterId)
                    if (currentOwner != state.owner || !GeminiCacheConnection(provider).matches(currentProvider))
                        throw StError.Conflict("cache connection changed before send; create a new operation")
                    val at = now(state)
                    val next = state.copy(phase = "executing", httpAttempted = true, attemptedAt = at, updatedAt = at,
                        cost = CostSnapshot(reason = "cache_remote_outcome_unknown"))
                    tasks.upsertCacheOperation(current.copy(status = TaskStatus.RUNNING, updatedAt = at), encode(next, provider))
                    state = next
                    latest.value = state
                } }
            }, { transfers, sourceExpiry ->
                // Cleanup callbacks also arrive under cancellation; retain their final receipt.
                withContext(NonCancellable + Dispatchers.IO) { control.withLock {
                    val current = tasks.get(initial.id)?.let(signals::effective) ?: return@withLock
                    if (current.status.isTerminal) return@withLock
                    val at = now(state)
                    val next = state.copy(fileTransfers = transfers, sourceFilesExpireTime = sourceExpiry ?: state.sourceFilesExpireTime,
                        phase = if (state.httpAttempted) state.phase else "preparing", updatedAt = at)
                    val status = if (current.status == TaskStatus.STOPPING) current.status else TaskStatus.RUNNING
                    tasks.upsertCacheOperation(current.copy(status = status, updatedAt = at), encode(next, provider))
                    state = next
                    latest.value = state
                } }
            }) }
        } catch (_: CancellationException) { cancelled = true }
        catch (_: Exception) { if (!currentCoroutineContext().isActive) cancelled = true else failure = true }
        finally {
            withContext(NonCancellable + Dispatchers.IO) {
                try { control.withLock {
                    val current = tasks.get(initial.id)?.let(signals::effective) ?: return@withLock
                    if (current.status.isTerminal) return@withLock
                    val remote = result
                    val status = when {
                        current.status == TaskStatus.STOPPING -> current.stoppingStatus()
                        cancelled -> TaskStatus.INTERRUPTED
                        failure || remote == null -> TaskStatus.FAILED
                        else -> TaskStatus.SUCCEEDED
                    }
                    val at = now(state)
                    state = state.copy(phase = if (status == TaskStatus.SUCCEEDED) "completed" else status.name.lowercase(),
                        resource = remote?.metadata, resourceName = remote?.metadata?.name ?: state.resourceName,
                        listing = remote?.page?.let { GeminiCacheListing(it.caches, it.nextPageToken != null) },
                        remoteCompleted = remote != null, endedAt = at, updatedAt = at,
                        errorCode = if (status == TaskStatus.SUCCEEDED) null else "cache_operation_${status.name.lowercase()}",
                        cost = CostSnapshot(reason = if (remote != null) "cache_cost_not_priced" else if (state.httpAttempted)
                            "cache_remote_outcome_unknown" else "request_not_sent"))
                    // A frozen quote is not a provider charge. Preserve the unknown settlement cost above.
                    if (state.remoteCompleted && state.action == GeminiCacheAction.CREATE) {
                        state.pricing?.let { price -> state = state.copy(projectedCost = GeminiCachePricing.quote(
                            price, state.owner, state.createdAt, state.resource?.totalTokenCount, state.ttlSeconds!!)) }
                    }
                    tasks.upsertCacheOperation(current.copy(status = status, terminalReason = signals.completionReason(current, status),
                        updatedAt = at, errorMessage = state.errorCode, resultSummary = "cache operation ${state.phase}; cost unknown"), encode(state, provider))
                    if (remote?.page != null) {
                        if (status == TaskStatus.SUCCEEDED) remote.page.nextPageToken?.let { token ->
                            listingConfirmations[state.taskId] = ListingConfirmation(GeminiCacheConnection(provider), token, remote.listingSession!!)
                            while (listingConfirmations.size > 32) listingConfirmations.remove(listingConfirmations.keys.first())
                        }
                    } else if (remote != null) {
                        val name = state.resourceName!!
                        // Scope invalidation to this exact public owner, never every account with the same short name.
                        for (id in bindings.keys.toList()) {
                            val saved = progress(id)
                            if (saved.owner == state.owner && saved.resourceName == name && state.action != GeminiCacheAction.CREATE) {
                                if (state.action != GeminiCacheAction.DELETE && remote.metadata != null && bindings.getValue(id).matchesConnection(provider))
                                    bindings[id] = bindings.getValue(id).updated(remote.metadata)
                                else bindings.remove(id)
                            }
                        }
                        if (state.action == GeminiCacheAction.DELETE) confirmations.remove(state.owner to name) else {
                            confirmations.remove(state.owner to name)
                            confirmations[state.owner to name] = Confirmation(state.taskId, GeminiCacheConnection(provider))
                            while (confirmations.size > 256) confirmations.remove(confirmations.keys.first())
                        }
                        remote.binding?.let { if (status == TaskStatus.SUCCEEDED) bindings[initial.id] = it }
                    } else if (state.httpAttempted && state.action !in setOf(GeminiCacheAction.CREATE, GeminiCacheAction.GET, GeminiCacheAction.LIST)) {
                        confirmations.remove(state.owner to state.resourceName!!)
                        for (id in bindings.keys.toList()) {
                            val saved = progress(id)
                            if (saved.owner == state.owner && saved.resourceName == state.resourceName) bindings.remove(id)
                        }
                    }
                    latest.value = state
                } } finally { control.withLock { busy.remove(resourceKey) }; lease.close(); handoff?.close() }
            }
        }
    }
    private fun encode(state: GeminiCacheTaskProgress, provider: ProviderConfig): String {
        // Persisted writes validate at TaskStore; this step only serializes and prevents credential leakage.
        val raw = Jsonx.encode(state)
        if (raw.toByteArray(Charsets.UTF_8).size > GeminiCacheTaskMetadata.byteLimit(state.action) || provider.containsCredential(raw))
            throw StError.Validation("cache operation metadata exceeds budget or contains credentials")
        if (state.accountingVersion == 1) {
            val event = GeminiCacheAccounting.eventFromOperation(state, raw)
            if (listOfNotNull(event.id, event.eventId, event.requestId, event.chatId, event.provider, event.model, event.usageJson,
                    event.costJson, event.createdAt).any(provider::containsCredential))
                throw StError.Validation("cache ledger metadata contains credentials")
        }
        return raw
    }
    private fun newId() = UUID.randomUUID().toString().replace("-", "")
    private fun requireSourceLifetime(state: GeminiCacheTaskProgress) {
        val sourceExpiry = state.sourceFilesExpireTime ?: return
        val requested = when (state.action) {
            GeminiCacheAction.UPDATE_TTL -> {
                val ttl = state.ttlSeconds ?: throw StError.Validation("cache TTL missing")
                if (ttl !in 1..604800) throw StError.Validation("invalid cache TTL")
                Instant.now().plusSeconds(ttl + 60)
            }
            GeminiCacheAction.UPDATE_EXPIRY -> OffsetDateTime.parse(state.requestedExpireTime!!).toInstant().plusSeconds(60)
            else -> return
        }
        if (requested.isAfter(OffsetDateTime.parse(sourceExpiry).toInstant()))
            throw StError.Validation("cache expiration exceeds its uploaded source file lifetime")
    }
    private fun resourceKey(owner: GeminiCacheOwner, name: String) = Jsonx.encode(listOf(owner.providerId, owner.baseUrl, name))
    private fun resourceViewKey(owner: GeminiCacheOwner, name: String) = buildJsonArray { add(Jsonx.encode(owner)); add(name) }.toString()
    /** control owns the live map; expired private prefixes are not retained as a replay index. */
    private suspend fun pruneBindings() {
        bindings.entries.removeAll { !Instant.now().isBefore(OffsetDateTime.parse(it.value.metadata.expireTime).toInstant()) }
        val histories = mutableMapOf<String, List<GeminiCacheTaskProgress>>()
        for (id in bindings.keys.toList()) {
            val saved = try { progress(id) } catch (_: StError.NotFound) { null }
            if (saved == null || saved.processEpoch != epoch || saved.resource?.name != bindings[id]?.metadata?.name) bindings.remove(id)
            else {
                val history = histories[saved.owner.chatId] ?: tasks.cacheResourceHistory(saved.owner.chatId).also { histories[saved.owner.chatId] = it }
                val view = GeminiCacheLifecycle.project(saved.owner, saved.resourceName!!, history, now())
                val proof = confirmations[saved.owner to saved.resourceName!!]
                if (view.status !in setOf("available", "pending") || view.confirmedTaskId != proof?.taskId) bindings.remove(id)
            }
        }
    }
    private fun now(state: GeminiCacheTaskProgress? = null) = maxOf(Instant.now(), state?.updatedAt?.let {
        OffsetDateTime.parse(it).toInstant() } ?: Instant.MIN).toString()
}

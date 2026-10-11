# Gemini 与 Vertex 缓存

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="gemini-cache-transport"></a>

## Gemini 显式缓存传输与引用底座

2026-10-09：同一传输/任务链增加 Vertex 服务账号原生缓存集合、完整模型/资源名和项目编号别名；原 Developer API 保留，正常 token 刷新不使同账号绑定失效。历史“仅 generativelanguage v1beta”限制已扩展；GCS/大文件、CMEK、跨进程前缀和实际云权限仍未完成，见 [GEMINI_CACHE.md#vertex-cache](GEMINI_CACHE.md#vertex-cache)。

第四十三批已在新生成路径按来源 explicit 配置准备缓存任务/复用租约，见 [GEMINI_CACHE.md#gemini-cache-generation](GEMINI_CACHE.md#gemini-cache-generation)。不会启动重发旧操作，费用/持久前缀索引/完整清理及 native 全关系仍待补；后文未接自动聊天的描述是当时历史状态。

第四十二批后续已接显式 SimpleTavernCore 操作入口、持久任务记录、停止/宿主/gate 和部分 native 关系，详见 [GEMINI_CACHE.md#gemini-cache-operations](GEMINI_CACHE.md#gemini-cache-operations)。下文第四十一批的“尚未开放 Core/无操作账本”是历史阶段状态；聊天自动管理、通用用量/价格/持久前缀索引与全部恢复关系仍未完成。

第四十一批，2026-10-06。补原生传输和进程内引用，未声称完整缓存管理已完成；正常编译/代码检查，没有实际缓存 API 或收费调用。

### 当前实现

core:llm `GeminiCacheClient` 接 create/get/list/updateTtl/updateExpireTime/delete，对应官方 v1beta cachedContents。分页最多 200，不自动翻页；资源只允许 cachedContents/单段 ID。固定官方 HTTPS origin/v1beta 根，拒绝 Vertex、兼容网关、URL 密钥/fragment/额外版本参数，不擅自重写用户连接。鉴权沿用 ProviderHttpOptions 公共头/加密引用/运行时 query-key 与凭据跟踪。

create 接原模型、system、canonical function tools、原生搜索工具、可选已有内容前缀与整数秒 TTL；PATCH 仅改 TTL 或 expireTime，不修改不可变前缀。TTL/截止时间使用本地七天预算，不把它称为服务器上限；整数秒不是完整 protobuf 小数 Duration 支持。响应解析 name/model/expireTime/createTime/updateTime/usageMetadata.totalTokenCount，不保存或回显原始响应。分页 token 有界且拒绝选中密钥材料，参考资源 metadata 无提示正文/凭据/重发参数。

每个方法强制传 `beforeExecute`：URL/headers/body 构造与预算通过后、唯一 Call.execute 前调用，checkpoint 失败不会发送。客户端取消 watcher 持续到响应读完，关闭自动重试/重定向并有 NoHttpReplayInterceptor。请求上限 1 MiB、响应 256 KiB、60 秒 deadline；创建的大前缀编码与网络在 IO 上运行。HTTP 404/超时/失效直接报错，不像桌面实现那样重建一次，也不静默退回无缓存。服务端已创建但响应丢失仍属于未解决的远端状态，需要后续账本记录，不能据此重发。

create 返回 `GeminiCachedContext`，仅 core:llm 能构造；它是进程内绑定，不可序列化。原连接/原模型/原 credentialId、密钥快照、公开和加密引用头必须相同，HTTP options 另做副本，不让可变头改掉创建时身份。toString 脱敏，私有前缀与凭据只留内存。绑定类型的 TTL/截止时间更新和删除也检查 owner；更新返新绑定，不修改原快照。

ProviderConfig 可持有该绑定；LlmClient Gemini 发送前逐值比较 system/tools 与完整历史前缀，不用任何内容哈希。前缀相同才移除已缓存 system/tools/history，并在顶层放 cachedContent，不塞进 generationConfig。相邻同角色合并时按最后缓存 content 的 parts 边界剥离，保留新追加 parts/后续 contents，不改 opaque 签名或工具对象。剩余无新内容、模型/连接/凭据改动、已知过期或前缀变化均拒绝，不偷用旧上下文或创建新资源。将 Gemini 绑定带到其他协议同样拒绝。

Gemini implicit source promptCache 不发控制字段，ttl null 按未指定；不能强制 TTL，明确给 TTL 时失败。explicit 的来源配置仍提示缺持久管理器，未自动创建收费资源。原始来源、三方合并并存和本地编辑保护未改，不启动迁移旧数据。

### 尚未接通的完整链路

这只是传输/绑定基础。SimpleTavernCore 尚未开放这些管理入口，普通聊天不会自动调用它；因此目前没有新增无账本的收费路径。需要接持久 cache-operation ledger/前台任务、准备→发送→结束/未知状态、缓存存储与写入费用、所属 chat/source/credential 与恢复 epoch、可恢复索引和明确清理、自动 explicit 生成流程、native 所有关系/备份恢复、配置变化并发与服务器实际状态。数据门和后台/停止生命周期都应在该管理层接上；不可把 mandatory callback 本身当成耐久账本。

缓存使用的 generation usage 加 cache_storage_billed_separately 警告，既有计价返回未知，避免把 token 费用当含缓存存储的完整账单。metadata.totalTokenCount 不等于总金额；存储时长、计价规则和 provider 发票仍未覆盖。Vertex/Fast/Qwen、完整多模态及所有参数/源版本/设备验证继续在完整后端范围内。

官方格式：[Caching API](https://ai.google.dev/api/caching)、[Context caching](https://ai.google.dev/gemini-api/docs/caching)。Desktop gemini_cache_store.py 只读参考，内容哈希索引和 404 重建未复制。Room v2/25 表/native v1/依赖不变；不执行旧任务，不做哈希校验、冒烟或单测集。普通编译证据见 VERIFICATION.md，完整目标 STATUS.md 继续。

<a id="gemini-cache-operations"></a>

## Gemini 缓存持久操作与任务

2026-10-08 手动附件创建：原startGeminiCacheCreate的contents.attachments现按托管ID解析，内联/上传预算与聊天相同，忽略调用方media路径/句柄。缓存任务增preparing及fileTransfers，上传不是缓存POST，httpAttempted仅在POST checkpoint置true。清理/保留状态写入原任务/通用账目，sourceFilesExpireTime在上传后、POST前首次赋值并冻结；停止/恢复不重放。源文件保留策略及尚未支持的手动缓存生成选择见 [PROVIDERS.md#provider-files](PROVIDERS.md#provider-files)。

第四十七批远端 LIST 已开放独立持久任务入口，并接账目/逐页确认/运行时 token/连接一致/停止/native 恢复，见 [GEMINI_CACHE.md#gemini-cache-listing](GEMINI_CACHE.md#gemini-cache-listing)。下文“Core 尚无枚举任务”为历史状态；列表不直接成为可清理资源，不自动翻页/重发，完整归属/孤儿清理/跨进程索引/实际存储结算仍保留。

第四十六批新增本地因果资源视图/分页、最新确认和当前私有连接核对、前沿比较的明确 DELETE 清理及部分失效/native 祖先循环保护，见 [GEMINI_CACHE.md#gemini-cache-lifecycle](GEMINI_CACHE.md#gemini-cache-lifecycle)。下文仅 epoch/公开 owner 的管理检查已增强；条件清理不是完整孤儿/后台清理，完整跨进程索引/存活结算/native 全关系仍待补。第四十五批价格规则和独立报价见 [GEMINI_CACHE.md#gemini-cache-pricing](GEMINI_CACHE.md#gemini-cache-pricing)，实际费用仍未知。

第四十四批的新任务与独立通用缓存账目原子同步，旧 task-only 历史不补账；见 [GEMINI_CACHE.md#gemini-cache-accounting](GEMINI_CACHE.md#gemini-cache-accounting)。费用仍 unknown，创建/存储价格及其余完整链路继续。

第四十三批后续已接来源 explicit 配置到新生成的自动创建/进程内复用、使用租约和父取消；见 [GEMINI_CACHE.md#gemini-cache-generation](GEMINI_CACHE.md#gemini-cache-generation)。下文“普通聊天尚未自动创建/使用”是第四十二批历史状态；通用账目/价格、跨进程索引/完整清理和全部恢复关系仍未完成。

第四十二批，2026-10-06。在第四十一批传输底座上接显式 Core 入口、操作记录及任务生命周期；不自动创建收费缓存，也不声称聊天自动缓存、完整费用或全部恢复关系已完成。

### Core 入口

- `startGeminiCacheCreate(GeminiCacheCreateRequest)`：使用指定 chat/可选成员的当前同来源连接，新建独立 CREATE 任务。请求支持 system、canonical toolsJson、contents、nativeWebSearch、整数秒 TTL；正文/前缀/密钥只留运行时，不写任务 payload。
- `startGeminiCacheOperation(chatId, referenceTaskId, action, ttlSeconds?, expireTime?)`：对已有确认资源新建 GET、UPDATE_TTL、UPDATE_EXPIRY 或 DELETE 任务。引用不是任意资源名输入，必须有本地确认的结果且当前 chat/source/model/provider/preset/base/auth 公开归属一致。CREATE 使用专门入口。
- `getGeminiCacheOperation(taskId)`：读取该操作记录；`geminiCacheOperationsPage(afterTaskId = "", limit = 100)`：按 taskId 字典序翻页，limit 1..200，不是时间排序或快照游标。
- `observeGeminiCacheOperation()`：最新一条进度的 StateFlow，不是完整事件日志；任务状态仍走既有 Task 接口。`stopTask` 与现有宿主中断处理识别 GEMINI_CACHE_OPERATION。

上述 suspend 入口等待 Core 初始化，读/编码/网络在 IO 路径；真正任务归 core scope，启动调用结束不是远端完成。只有调用这些新入口才发送新请求，本批未执行实际账号/缓存操作。

### 持久边界与取消

新记录复用 tasks.payloadJson，不新增表、数据库版本或外部依赖。记录包含公开 owner、操作种类、可选原任务/资源名、TTL/截止时间、queued/executing/终态、发送意图时间、可得资源 metadata 和 unknown CostSnapshot。操作总 payload 32 KiB；SQL 先读长度预算再取完整行；请求 1 MiB/4096 条内容，队列最多 16、HTTP 两并发，私有前缀绑定最多 16。

初始任务写入后 worker 才接手 gate shared lease；每个 HTTP 发送前先短提交 executing/httpAttempted，再执行唯一网络交换，数据库 checkpoint 失败不发送。整个任务持 data gate，不长持 Room 事务；宿主 handoff 保留至结果落库与清理。停止先持久 STOPPING，取消请求并等待 finally，第一中断原因优先。正常结果可得时仍保存远端结果；停止后的成功创建不会放入可复用的运行时绑定。httpAttempted 只代表本地发送意图，不证明服务端接收/收费。

启动只把未结束操作改成 interrupted/原停止原因，不 GET、不删除、不重建、不续发。原生恢复也只终止未结束任务，所有恢复记录换为 native-restored epoch。跨进程或恢复后的旧资源必须先由调用者明确创建一个新 GET 任务确认，才能以该 GET 结果发修改/删除请求。GET 是新请求，不是重放旧任务；公开 owner/epoch 不是永久账号所有权证明，凭据/配置变化的完整索引失效仍待补。

启动和原生恢复归一化只覆盖生命周期字段，保留原 payload 的未知根字段及嵌套 JSON。native 已验证任务 ID/chat/创建时间/状态、资源模型/格式、来源及可得历史 preset/character/referenceTask 关系；被引用的可得父任务必须是同 owner/资源的确认缓存结果。尚未补齐包外原有效缓存引用覆盖保护、所有链图/循环/配置/CAS/版本/真实 IO，不能称为完整恢复审计。

### 费用与剩余实现

未发送为 unknown/request_not_sent，发送后无法确定结果为 unknown/cache_remote_outcome_unknown，已收到操作成功结果为 unknown/cache_cost_not_priced。未知不是零；totalTokenCount 不代表存储时长、创建费用或发票。本批记录在 tasks 中，尚未并入通用 usage_events 汇总/价格规则。

普通聊天仍未自动选择/创建/使用 explicit 缓存。进程内创建结果绑定保留并按过期/恢复记录变化淘汰，但尚未接普通生成、持久前缀索引、完整配置/凭据失效、价格/实际存储时长、服务器清理策略。低层 list 已有，Core 尚无远端枚举任务。传输和逐值前缀比对见 [GEMINI_CACHE.md#gemini-cache-transport](GEMINI_CACHE.md#gemini-cache-transport)，完整后端范围见 [STATUS.md](STATUS.md)。

来源自动合并并存、本地编辑保护、旧任务不续发保持。不做哈希校验/内容哈希索引、冒烟或新增单测集；本批仅正常编译和代码检查，未执行 APK、导入/恢复用户包或调用付费服务。

<a id="gemini-cache-generation"></a>

## Gemini 显式缓存接入生成

2026-10-09：MATCH_PREFIX/PREPEND 和来源 explicit/history-tail 已可沿 Vertex 服务账号原生连接使用，仍只接受本进程实际创建的私有绑定。项目/区域/账号/key 保持一致时，短期 token 刷新使用新 token、不丢弃缓存；不启用 Vertex OpenAI extra_body、GCS 上传或跨进程前缀，见 [GEMINI_CACHE.md#vertex-cache](GEMINI_CACHE.md#vertex-cache)。

### 2026-10-08：手动选择已创建缓存

新增 `setGeminiCacheForGeneration(chatId, GeminiCacheGenerationSelection(taskId, contentMode), characterId=null)` 与 `getGeminiCacheForGeneration(...)`。taskId 使用本进程成功 CREATE 的原任务ID（手动或自动创建均可），不接收外部账号列表中的短资源名、历史恢复记录或 GET 作为上下文证明。传null只清除选择，不删除远端缓存；设置/读取本身不联网或生成。普通聊天、群聊具体成员及独立助手沿原 ProviderResolver/LlmClient 使用；记忆总结、MVU/KG维护和语音预处理不继承聊天的手动选择。

`MATCH_PREFIX` 默认沿原前缀逐值匹配/去重，正文、system/tools、签名和已托管上传文件身份必须匹配；当前请求须包含缓存历史并有新内容。`PREPEND` 明确把缓存内容作为额外前置上下文，保留当前全部contents，不要求它重复缓存历史；当前system/tools若存在须与缓存字段一致，缺省时可沿用缓存字段。两者均不默默丢弃冲突系统提示/工具，使用cachedContent的原协议路径、流式/非流式及工具续轮。PREPEND若用户同时传回同一历史会重复上下文，这是明确模式而非自动猜测。

选择优先于自动新建缓存，TTL/historyTail创建策略不改变已选资源，不因为原promptCache没有explicit而忽略选择；修改协议/模型/来源/成员/凭据、上下文不匹配、过期/源Files到期或私有绑定失效会明确失败。不自动重建或以无缓存重发，需明确清除/重新创建选择。原数据lease/资源inUse阻止生成中管理竞争；新GET或确认成功的TTL/到期修改保留同连接私有前缀并更新metadata，未知修改/删除仍淘汰绑定，不用GET恢复已丢失正文。

选择只保存在当前进程，冻结到新生成的计划；正在执行的一次生成及其工具续轮不因随后清除/切换选择而偷换上下文。重启不会恢复选择、正文、凭据或上传句柄，远端资源和账目仍保留。助手实际继承的presetId现在进入同一公开owner，不再从缺省的聊天overrides猜归属；不改用户preset/协议/模型。

没有新表、通用任务引擎、哈希或测试套件。不是跨进程缓存重绑定、缓存引用的完整native审计、跨生成免上传或真实账号验收；这些余项保持。官方 `generateContent/streamGenerateContent` 的 cachedContent 格式依据：[生成API](https://ai.google.dev/api/generate-content)、[缓存API及追加上下文示例](https://ai.google.dev/api/caching)。未实际创建/选择用户缓存或调用模型。下文“手动缓存生成选择尚缺”为旧批历史状态。

2026-10-08 上传历史：historyTail已接已托管上传文件的缓存归属，缓存POST可能发送后不随生成清理源文件，保留至Files到期。创建/续期不得超过sourceFilesExpireTime，跨生成的URI变更按托管身份匹配前缀，不改正文/签名或新内容URI。fileTransfers增cache_retained/retainedUntil；只进程内绑定，不恢复远端上传句柄，不自动重发。后续手动缓存创建已接附件解析/上传，但跨生成免上传及手动缓存生成选择尚缺，见 [PROVIDERS.md#provider-files](PROVIDERS.md#provider-files)。

第四十六批增当前连接重核、公开归属/策略/私有连接与自动前缀失效、因果资源状态/最近确认核对，结果不确定的修改/删除淘汰绑定；确认 GET 只更新已有私有绑定 metadata，不恢复旧前缀。清理仅明确条件 DELETE，旧任务不重发，见 [GEMINI_CACHE.md#gemini-cache-lifecycle](GEMINI_CACHE.md#gemini-cache-lifecycle)。完整跨进程索引/原子配置失效/孤儿清理/存活费用与全部 native 关系仍保留，以下为历史进展。

第四十四批已把新缓存操作同步到通用 usage_events/分页/汇总，并接 tracked task/ledger 的成对恢复及部分包外覆盖保护，见 [GEMINI_CACHE.md#gemini-cache-accounting](GEMINI_CACHE.md#gemini-cache-accounting)。下文“未接通通用汇总”是第四十三批历史状态；缓存创建/存储价格、跨进程索引/完整清理与其余恢复关联仍待补。

第四十三批，2026-10-06。第四十二批手动操作任务保持；本批把来源配置的 explicit 缓存接到 Core 共用 LlmClient 的新生成请求，不在启动/导入/恢复时执行缓存操作。

### 配置与触发

ProviderPromptCacheSettings 返回生成参数和独立 Gemini 缓存意图，不把缓存参数塞入 generationConfig。明确官方 HTTPS generativelanguage.googleapis.com 根或 /v1beta 连接可使用 `promptCache: {mode: "explicit", ttl: 3600}`；TTL 支持整数秒/整数文本以及 s/m/h 后缀，默认 3600 秒，本地预算 1..604800 秒，不冒充服务器上限或完整小数 Duration。implicit/off/best_effort 的原行为保持，明确非官方/Vertex/版本覆盖/消息级 explicitMarkers 不假装支持。

模型、chat、成员、来源、preset、连接和鉴权公开归属由 ProviderResolver 冻结为 GeminiCachePlan，正常解析/预览不联网。只有新请求实际 collect 到 Gemini 发送路径，完成 native 正文/模型/生成字段验证后才进入缓存管理。单聊、群成员、工具后续轮次共用该路径；记忆/状态维护解析到同一显式配置时也遵循它。语音预处理当前显式连接未产生缓存计划，本批没有扩大它的缓存行为。

与桌面一致，稳定 system/tools 一起形成 Gemini 不可变缓存前缀，不把 system/tools breakpoint 当成 Claude 的独立断点。breakpoints 含 history_tail 时，还缓存本次非 system 历史中最后一条之前的内容，保留新内容用于 generateContent；不修改 opaque 签名/工具往返。cacheKey 不用作 Gemini 哈希或跨 chat 索引，缓存按当前 owner/前缀隔离。没有 system/tools/可缓存历史时无适用的缓存前缀，不发创建请求，正常生成继续；不是服务器缓存错误后的降级。没有任何新 contents 的请求在创建前拒绝。

### 创建、复用与使用租约

GeminiCacheTaskService 先验证有界公开计划及当前 chat/source/成员。只在当前进程 live bindings 中查找同 owner、TTL、historyTail 策略且原连接/原凭据快照/原前缀逐值匹配的成功自动创建结果，无哈希、无跨来源借用。过期和原生恢复 epoch 变化的绑定淘汰；恢复记录不是重放索引。不同前缀或凭据的新生成只能新建记录，不能把旧缓存当成同一上下文。

未命中时创建独立 GEMINI_CACHE_OPERATION 子任务，复用发送前 checkpoint、两并发/队列/宿主/gate 和未知费用三态，保存 generationRequestId、可得 generationTaskId、automatic/historyTail，不保存提示正文、密钥或重发参数。等待创建时父请求取消，会停止并等子任务收尾；有可得父 task 的用户/平台中断原因时沿用，无法判定原因时中断，不假称用户停止。已完成的远端结果仍保留，不补发或删除。

成功准备后返回 GeminiCacheUse：资源使用计数与 shared data gate 一直持有到整个流完成/失败/取消，包括读取、工具结果解析及 Done；finally 释放。相同资源被使用期间手动管理返回 conflict，防止 TTL/删除与生成竞争。自动准备串行合并并发查找/创建，生成流本身不串行。请求仍按原模型发送 cachedContent，移除已缓存字段/前缀的处理沿用低层逐值边界校验；404/已失效/创建失败直接结束，不重建一次、不重发、不改成无缓存发送。

### 用量、恢复及余项

使用绑定的 active ProviderConfig 参与 usage 解析，生成 token 用量附 cache_storage_billed_separately；原未知费用保护有效，不把 token 估算当完整缓存账单。创建与生成分别记录：缓存已成功但生成未发送，也不会把缓存费用改成 request_not_sent。缓存成本仍保存在 tasks，未接通通用 usage_events 汇总/创建与存储定价，未知不是零。

native 增自动创建与可得原生成账目的 request/chat/provider/model/source/protocol/task 关系，以及可得父任务 chat/种类校验；缺历史账目为 warning，已知冲突阻断。其余 native 覆盖保护/完整引用链仍待补。旧操作启动/恢复只中断；新提示触发的新操作不等于重放旧任务。

本批不自动延时或删除服务器缓存，不支持跨进程恢复 private 前缀/凭据绑定。16 个 live binding 的本地预算、完整配置/凭据失效与清理策略、复用生成的资源关联索引、缓存价格/存储时长/发票、通用账目与完整 native 保护继续待补；不能以自动接入替代完整缓存后端或 [STATUS.md](STATUS.md) 的全目标。

官方格式核对：[Caching API](https://ai.google.dev/api/caching)。只读桌面 gemini_cache_store.py 参考，未复制哈希索引/404 重建。来源自动合并并存/本地保护、Room v2/25 表/native v1/依赖不变；仅正常编译/代码检查，无实际模型/缓存/账号/用户包操作、哈希校验、冒烟或新增单测集。

<a id="gemini-cache-lifecycle"></a>

## Gemini 缓存生命周期与条件清理

第四十七批后续已开放 [远端枚举任务](GEMINI_CACHE.md#gemini-cache-listing)。LIST 使用独立 task kind，在本地资源历史 SQL 排除；页面 metadata 不冒充本地 ownership、确认修改或可删孤儿。下文“远端枚举任务待补”为第四十六批历史状态，完整归属/孤儿清理/跨进程索引/存活结算与所有余项继续。

第四十六批，2026-10-06。延续独立操作/账目/冻结报价，新增从持久任务证据投影的资源状态、前沿条件比较、运行时连接确认和明确的新 DELETE 清理入口。不是远端列表、永久所有权证书、跨进程私有前缀索引或完整账单。

### 入口

- `getGeminiCacheResource(referenceTaskId, at=now)`：本地查询已知资源，即使引用的是失败/删除任务也可查看；未知名称的未确认 CREATE 仍只能查操作记录。
- `geminiCacheResourcesPage(chatId, afterKey="", limit=100, at=now)`：本地按完整公开 owner/name 键字典序分页，limit 1..200。afterKey 是不透明键，不是时间、快照或可重用恢复凭证；页间记录可变化，清理必须另做前沿比较。
- `startGeminiCacheCleanup(chatId, referenceTaskId, expectedFrontierTaskIds)`：明确条件 DELETE，新建普通持久缓存操作任务。referenceTaskId 必须是当前资源最近确认结果，expectedFrontierTaskIds 来自状态视图。状态变化/正在使用/其他管理竞争/凭据变化/无当前确认均拒绝登记，不自动 GET 或重发。
- 原 `startGeminiCacheOperation` 的修改/删除也要求最新确认记录和当前连接；GET 是明确新请求，可以重新确认历史资源或合并歧义前沿，绝不重放旧任务。

查询不读账号/密钥，不执行远端操作。真正的清理复用 DELETE 发送前 checkpoint、宿主/停止/共享 gate、唯一 HTTP 和任务/账目同事务提交。本批没有实际调用这些入口执行远端清理，也没有删除用户数据。

### 因果状态

新 CREATE/管理任务增加 `lifecycleVersion=1`。CREATE 无前驱；管理登记时捕获资源的完整 `lifecyclePreviousTaskIds`，最多 64 个，与 owner/name、原 referenceTaskId 同属不可变身份。TaskStore 在初始账目/任务事务中重新比较前沿；变化就冲突，不自动刷新条件再执行。新操作开始时间不早于已有证据更新时间，避免本机时钟回拨导致父子顺序倒置。

本地读取按 chat 在 Room 一致事务内每页 100 条、先预算后读任务及配对账目；最多 10000 操作/64 MiB task payload。按完整公开 owner/name 分组，迭代投影 DAG，不按 UUID、时间戳或插入顺序选“最新”。旧无 marker 记录只沿可得 referenceTaskId，不改历史或补账；缺祖先带 warning、已知归属冲突或循环拒绝，存在多个终端前沿则 ambiguous。

| 状态 | 证据/行为 |
| --- | --- |
| available | 单一前沿有确认结果，或未发送/失败 GET 沿用此前确认，且已知到期时间未到 |
| expired | 本地确认 metadata 的 expireTime 已到；不证明服务端已删或已结算 |
| deleted | 明确收到 DELETE 成功结果；停止后的可得成功结果也保留 |
| pending | 当前前沿仍未结束；不借此前确认绕过管理租约 |
| unknown | 修改/删除已登记发送意图但未确认结果，或缺少足够此前证据 |
| ambiguous | 多个互不覆盖的前沿；不挑第一条或按时钟猜顺序 |

GET 未确认不能证明发生修改，也不把 HTTP 错误（含 404）擅自当作资源已删除。已确认的新 GET/更新形成新确认；DELETE 不再留下可用于修改的旧 CREATE 确认。GET/修改/删除不重复计创建费，不改旧报价，实际存活/分段存储费仍未结算。

### 连接、使用与失效

运行时最多 256 个确认快照，保存最近确认任务与完整 provider/model/origin/API key/auth/API-version/headers/凭据材料逐值快照，仅进程内、不可序列化且 toString 脱敏。管理公开 owner 相同仍不代表凭据相同；换凭据/头/恢复/重启后无匹配私有确认，需要调用者明确 GET。快照预算淘汰后也不自动取回或联网。注册时只验证当前实际连接，不将密钥或前缀写入生命周期字段。

新生成准备时重新核对当前 chat/来源/成员/连接。相同 chat/成员的公开归属、TTL/history 策略、连接或自动前缀改变时淘汰旧私有绑定；只淘汰本地绑定，不删除服务器缓存或追加账目。使用中的请求持有原冻结对象和租约直至结束，资源管理仍被 inUse 阻止。

复用前核对资源投影和最近运行时确认。确认 GET 可更新同 owner/name 的已有私有绑定 metadata，但不恢复没有私有前缀的历史绑定；TTL 更新/删除、结果不确定的修改/删除会淘汰该 owner/name 绑定及过时确认。无结果的 CREATE 不能编造资源名称。名字相同但 owner 不同的绑定不被批量移除；物理管理锁使用结构化 provider/origin/name，避免冒号拼接碰撞，并保守串行化公开端点下同名管理。

所有新缓存 HTTP 在原 checkpoint 前再核对当前公开 owner 和私有连接快照，变更则不发送，任务按未发送结束；不会改用新凭据发旧任务。此检查不是对配置写入/网络发送的全局原子 CAS，正在发送的已冻结请求也不会假称被撤回。

### 原生恢复与剩余范围

原生校验可得生命周期前驱任务的同 owner/name、结束状态及先后时间，缺历史只 warning。NativeGeminiCacheLifecycleGuard 迭代检查候选/可得 live 祖先的循环，最多 100000 节点/64 MiB payload 读取；等时间戳不能隐藏循环。包外保留的原有效成对操作，其可验证生命周期祖先若被替换会比较完整 operation JSON（仅排除运行时 epoch），改变则阻断。未知字段、前沿、冻结报价保持，未结束任务依然只中断，恢复不发送任何缓存请求。

Room v2/25 表/native v1/依赖不变。当前视图是有界持久操作投影，不是长期 compact 资源索引、跨进程前缀/凭据恢复或生成复用账目索引；远端枚举任务、完整孤儿/跨配置/已删 chat 资源清理、失效策略与原子配置版本、实际存活区间/分段存储费/发票、全 native 关系和 STATUS.md 全余项仍在范围。来源自动合并并存、本地保护、旧操作不续发保持；未做哈希校验/内容哈希索引、冒烟或新增单测，也未执行设备/账号/收费/用户包操作。

<a id="gemini-cache-listing"></a>

## Gemini 远端缓存枚举任务

第四十七批，2026-10-06。补齐已存在的原生 list 适配器到 Core 持久任务/独立账目/停止和恢复链路；每页一个新 LIST 任务，不自动遍历、重试、续发或清理账户缓存。

### API 和分页

`startGeminiCacheList(chatId, characterId=null, pageSize=100, previousListTaskId=null)` 返回新 TaskSnapshot；首屏不传父任务，明确下一页传上一次成功 LIST 的 taskId。页大小 1..1000，与原页一致；公开 owner/chat/成员/来源/preset/连接和私有 key/headers/auth/API-version/凭据材料均须仍匹配。

LIST 用指定 chat/可选成员的当前官方连接查询账户缓存，没有模型筛选参数，因此返回的条目可以属于不同模型。配置中的 owner.model 只是冻结连接/配置的归属字段，LIST 账目的 model/requestedModel 为 null，不把枚举算成该模型调用。

任务种类为 GEMINI_CACHE_LIST，操作 action=LIST。既有 getGeminiCacheOperation、操作分页、最新观察、stopTask/宿主中断共用；与其他缓存操作同样写入初始账目、HTTP 前 checkpoint、终态配对事务，持 shared data gate 和宿主 handoff，队列 16/HTTP 两并发，不因页面查询绕过停止。

确认结果只保存 GeminiCacheListing(caches, hasNextPage, warnings)，条目是公开 metadata，不保存 system/tools/contents。warnings 固定 account_listing_not_local_ownership_proof。空页面也是有效确认；hasNextPage=false 不能继续。停止后可得响应仍保存，只有成功完成的页才可作为下一页来源。

分页 token 不出服务运行时：不写 task/usage/native 或 UI 结果。调用者不能上传任意 token；最多 32 个页面确认快照，原页 token 在下一页登记成功后被标为已消费，即使该子任务后来失败/停止也不重用。不存在快照、被淘汰、重启/恢复、配置/凭据改变或原页已被消费，均要求调用者明确开始新的首屏查询，不自动查询首屏。

每个运行时枚举会话检测已返回 token 重复/循环，最多 10000 页及 256 KiB token 字节；单 token 沿用 2048 字符无控制字符的本地预算，异常不降低 pageSize 重查。发送仍为一次交换，重定向/网络重发保护和 60 秒/256 KiB 响应上限保持。这些是本地预算，不冒充服务商全部上限。

官方 [Caching API 分页契约](https://ai.google.dev/api/caching#method:-cachedcontents.list) 要求续页的其他参数一致，本批不遍历未经明确请求的下一页，也未实际执行账号请求。

### 持久预算与通用查询

普通资源操作仍是 32 KiB task payload/64 KiB usage envelope。LIST 为 1 MiB task payload、最多 1000 个唯一资源名，usage envelope 上限 1 MiB+32 KiB，费用列仍 32 KiB；条目名称/模型/时间/数量和固定 warnings 同时校验，不省略超额条目伪装成功。

SQL 先读 kind/长度/身份预算再取 task，独立 kind 让本地资源因果历史查询在 SQL 排除 LIST；远端观察不会插入本地 ownership 或形成可清理资源前沿。操作分页有 8 MiB task 字节总量预算，超限要求减小 limit；启动 pending LIST 无确认页 payload，所以原启动中断读取不加载大历史页。

通用 usage 分页只对具备缓存标识候选的较大行放宽预读限额，读到后必须通过完整 LIST/配对身份/cost/结果验证；非 LIST 的原限额不放宽。每页实际读取不超过 8 MiB，达到字节限额时减少此次原始行数并正确推进游标/hasMore，不静默跳过行。旧 forChat 无游标入口超总量会要求使用分页。

usageSummary 追加 cacheListingCount、reportedCacheListedResources、unknownCacheListingResultCount。列出条目数是确认页观察数量的和，不是账户唯一资源数、创建数量、创建 token、存储量或本地归属证明；无结果的 LIST 是未知，不猜零。LIST 实际费用仍 unknown，绝不按结果 token 数记一次创建费或存储费。

### 原生恢复

新 task kind/action/结果/大小规则加入 native 验证；可得父页须同 owner、相同页大小、成功确认并 hasNextPage，时间先后有效，引用链仍由迭代循环检查覆盖。包外保留的原有效页/账目须继续配对；可验证父页被替换时额外保护完整 JSON（只排除运行时 epoch）不被改成别的历史页。

恢复保留未知字段及已确认页面，未结束页只中断；不重算费用、不恢复 token、不创建可用的运行时分页快照，因此恢复后的 hasNextPage 不是能继续旧查询的凭证。Room v2/25 表/native v1/依赖不变。

### 剩余范围

枚举结果不会自动成为本项目可修改/删除的资源，不将同账户的其他应用缓存判为孤儿并删除。完整账户/跨配置/已删 chat 的归属登记与孤儿清理、长期紧凑/跨进程前缀/生成复用索引、完整配置原子版本/失效、实际存活/分段存储费/发票、多模态与价格维度、全 native 关系及 STATUS.md 全余项继续。来源自动合并并存/本地保护/旧任务不重发保持；没有哈希校验/内容哈希索引、冒烟、新增单测、真实账号/收费/缓存/用户包或设备执行，也没有实际删除任何用户/远端数据。

<a id="gemini-cache-pricing"></a>

## Gemini 缓存价格规则与独立估算

2026-10-06，第四十五批。在第四十四批独立 gemini_cache 账目上追加冻结规则和估算，不重写实际扣款、不补历史账、不执行旧任务。

### Core 入口

- `getGeminiCachePricingRules()` 返回规则和 `PricingVersion(epoch, revision)`。
- `replaceGeminiCachePricingRules(rules, expected)` 仅替换 user 规则，保留 catalog。
- `installGeminiCachePricingCatalog(rules, expected)` 仅替换 catalog，保留 user；catalog ID 必须以 `catalog:` 开头。
- `estimateGeminiCacheCost(owner, tokenCount, storageSeconds=3600, at=now)` 从调用者提供的公开归属、数量、时间只读估算；不读取密钥，不向远端查资源，不写任务或账目。它不是资源归属证明。
- 已有 `getGeminiCacheOperation`、操作分页与 `usageEventsPage` 返回开始冻结的 `pricing` 和确认创建后保存的 `projectedCost`。
- `usageSummary` 另返 `cacheQuotesByCurrency`、`unknownCacheCreationQuoteCount`、`unknownCacheStorageQuoteCount`；不混入 `costsByCurrency`。

独立设置行 `gemini_cache_pricing_v1`，总预算 256 KiB、最多 200 条。修改在 Room 事务中比较当前实例/恢复 epoch 和 revision；版本失效要求重新读取，不自动覆盖。规则没有内置线上价，也不从模型目录、普通生成 token 费率或缓存读取费率推导创建费用。

### 规则选择和数值

规则可以精确限制 providerId、model、chatId、sourceNamespace；不做别名、正则、模糊模型选择或货币换算。有效期必须含时区，开始包含、结束不包含。启用的匹配 user 规则优先于 catalog，同类规则比较非空限定字段数量；最高优先级有多条时返回 `ambiguous_cache_price`，不选第一条、不按费率字段拼装规则。

全部费率使用非负有界十进制字符串，最多 12 位小数。数量使用非负 Long，TTL 为 1..604800 整数秒；乘除用 BigDecimal，除法最多 18 位小数、HALF_EVEN，不按小时向上取整：

| 字段 | 含义/公式 |
| --- | --- |
| creationPerOperation | 每次创建固定费率 |
| creationPerMillionTokens | 创建 token 数 × 费率 / 1000000 |
| storagePerMillionTokenHours | token 数 × 费率 × 请求 TTL 秒 / 3600000000 |

缺失费率或必需数量保留 null，绝不猜免费；明确 `"0"` 费率可以得到零，即使数量未报告。完整 creationAmount 必须固定费和 token 创建费两维均可计算，不适用的维度需显式填零。projectedTotalAmount 还要求存储维度可计算。未匹配、歧义、配置无效有明确原因，部分估算为 `cache_quote_incomplete`。

### 创建流程和账目

手动/来源自动 CREATE 在登记任务前冻结规则、revision、开始时间；排队期间改规则不影响它。发送前和终态沿用原快照，不读新规则重算。只有已收到确认创建结果才保存估算，用返回的 totalTokenCount 与原请求 TTL；确认创建后用户停止仍保留该证据，无法确认远端结果则不造 token 或报价。

未知实际费用继续保留 `CostSnapshot(source=unknown)` 和既有三种原因。projectedCost 的固定 warnings 是 `requested_ttl_storage_projection`、`not_provider_billed_cost`，存储额只是请求 TTL 的预测，不代表实际存活时长、服务商计费舍入、免费额度、税费或发票。报价是用户/明确安装目录的线性规则，不证明多模态或阶梯价已覆盖。

GET/延时/删除不再统计一次创建费；它们不修改旧 CREATE 的 TTL 预测。通用汇总只处理确认 CREATE 的已保存报价，各币种分别累计完整创建估算与可计算存储预测，并分别统计贡献条数；对应维度缺失的确认 CREATE 计入 unknown。旧记录没有报价也保留未知，不按当前价补算。汇总里的零需要同时看贡献数量，不表示所有记录免费。

任务/账目同事务存储的规则快照属于不可变身份，终态报价必须与该快照、原 TTL 和确认数量完全一致。原生恢复同样校验；未知 JSON 字段和历史报价保留，未结束任务只中断，不重发/重算；恢复价格设置令旧编辑版本失效。Room v2/25 表/native v1/依赖不变。

### 剩余范围

本批不是实际费用结算。实际存活区间、TTL 更新/提前删除后的分段存储费、远端价格刷新/账户档位/阶梯与多模态、发票对账，跨进程前缀/生成复用索引、完整失效与清理及全部 native 关联保护仍需继续。编译不等于真实服务或设备验证。来源自动合并并存、本地编辑保护、旧操作不续发的要求不变；不做哈希校验、冒烟或新增单测，也未调用任何收费/缓存接口。

只读参考：[Gemini 价格说明](https://ai.google.dev/gemini-api/docs/pricing)、[Caching API](https://ai.google.dev/api/caching)。线上目录中的缓存读取与存储维度不能当作本项目已确认的创建扣款；本批不下载或安装费率。

<a id="gemini-cache-accounting"></a>

## Gemini 缓存独立账目与恢复证据

第四十七批 LIST 账户观察已接同一账目协议及新 GEMINI_CACHE_LIST task kind，model/requestedModel 为 null；确认列表不是创建/本地归属，专用数量汇总不计创建 token/费用。普通操作 32/64 KiB 不变，LIST payload/envelope 单独放宽到 1 MiB/1 MiB+32 KiB，通用读取有 8 MiB 页面预算和大行完整验证，见 [GEMINI_CACHE.md#gemini-cache-listing](GEMINI_CACHE.md#gemini-cache-listing)。token 不持久化，旧任务仍不续发，完整余项保留。

第四十五批后续：[GEMINI_CACHE.md#gemini-cache-pricing](GEMINI_CACHE.md#gemini-cache-pricing) 增独立规则/CAS/只读估算、开始冻结 pricing、确认 CREATE 保存 projectedCost 和独立报价汇总。下文成本 unknown 仍指实际结算未知；创建估算/请求 TTL 存储预测不是扣款、不入 totalKnownAmount，旧历史不补算。实际区间/账单、全维度定价和完整索引/清理/native 关系仍保留。

第四十四批，2026-10-06。把新缓存操作接进通用 usage_events，而不是把缓存 metadata 当普通聊天 token 或已知金额；自动生成与手动管理保持 [GEMINI_CACHE.md#gemini-cache-generation](GEMINI_CACHE.md#gemini-cache-generation) / [GEMINI_CACHE.md#gemini-cache-operations](GEMINI_CACHE.md#gemini-cache-operations) 的语义。

### 独立事件与事务

新 GeminiCacheTaskProgress 标记 accountingVersion=1。任务 id 仍独立 UUID；账目 id/eventId 为 gemini_cache:{taskId}，requestId 为 taskId，provider 为 gemini-cache:{原 credentialId}，model/chat/createdAt 与操作冻结归属一致。usageJson 使用 _kind=gemini_cache、version=1、operation 快照；不保存正文、密钥、私有前缀绑定或重发参数。

TaskStore.upsertCacheOperation 在一个短 Room 事务中提交任务和对应单行账目：初始 queued、发送前 executing/httpAttempted、最后 completed/failed/stopped/interrupted。账目初始与发送中 costJson 为 null，终态存操作的 unknown CostSnapshot；task 内 CostSnapshot 仍随已知状态变化。唯一发送 checkpoint 失败不 HTTP。操作开始/身份/原 owner/action/引用/TTL/生成请求归属固定，不允许 HTTP 证据倒退、改 attemptedAt 或改写已结束结果。

已有账目使用完整身份/原 usageJson/原 costJson 的 CAS 更新，不插入三笔阶段事件，不改变原 rowid。初始身份冲突、缺账、任务/账目不一致均拒绝，不猜旧费用；host 注册失败时同一事务回滚。只读查询先做 SQL 字节预算，再核对 task 与 ledger 的已知字段和完整 operation JSON。恢复 epoch 是运行时失效标记，不是历史费用身份，epoch 单独变化不等于原账目改变。

新事件预算：标识合计 4096 字节、provider 1024、operation 32 KiB、usage 64 KiB、cost 32 KiB。服务构造外层标识和 envelope 后也检查选中凭据材料，不能只检查原任务 JSON。默认 LLM/TTS/search 的启动索引不扫描缓存事件；缓存启动通过原 pending task 恢复，在同一事务终止 task/ledger，不执行任何请求。

### 通用查询与汇总

usageEventsPage / usageSummary 的 UsageFilter.kind 新支持 gemini_cache；provider 使用上述独立前缀，可与同 credentialId 的 LLM 区分。UsageLedgerItem.geminiCache 返回操作快照，usage 为 null；上下文为 GEMINI_CACHE、cache_create/get/update_ttl/update_expiry/delete、chat/source/task，queued/executing 映射通用 prepared/running。无效身份/费用/操作归为 invalid_cache_usage，金额不能进入已知汇总。

UsageSummary 增 cacheOperationCount、cacheCreationCount、reportedCacheCreatedTokens（十进制文本）、unknownCacheCreatedTokensCount。creationCount 是 CREATE 操作数，不等于成功创建资源数；只有确认 CREATE 返回的 totalTokenCount 才累计，未返回计数标未知，不把 GET/TTL/DELETE 的 metadata 重复当写入。缓存事件不增加普通聊天 input/output/cache-read/cache-write token 数或搜索 credits/TTS 字符。未知金额仍进入 unknownCostCount，而不是金额为零的币种总额。

终态费用三态保持：未发送 request_not_sent、已发送结果不明 cache_remote_outcome_unknown、收到成功结果 cache_cost_not_priced。创建成功而父生成未发送/失败，仍有独立缓存结果，不能被父请求的 request_not_sent 覆盖。没有提供商发票、存储时长/费率规则时仍 unknown；本批没有新缓存价格 API，不自动用模型目录参考价、当前 token 规则或 TTL 算完整账单。

### 原生恢复与兼容

任务和缓存账目共享同一稳定归一化：原 operation.updatedAt 与包 snapshotAt 的较晚时间结束未完成操作，phase=interrupted/error=cache_restore_interrupted，processEpoch=native-restored；终态保留原结果/时间/费用，仅更换运行时 epoch。原 operation/外层 envelope 未知 JSON 保留，已有终态 costJson 原样保留，不重新计价。tracked task 要求对应 ledger，ledger 的可得 task 必须同 kind/归属/operation；只有 ledger 的历史任务缺失可 warning，不重发请求。

REPLACE_INCLUDED 加有界 NativeGeminiCacheRestoreGuard：核实原 tracked task/ledger 的成对证据；若任一行留在包外，不允许另一行覆盖成不同/不可验证的操作，检查完整 operation JSON（仅 epoch 不作为账目事实）。保护可得原 chat/source 及父缓存引用；原来不可验证的本地历史仅 warning，不补造证明。扫描每页 200、最多 100000 行、64 MiB 读取预算，超总量拒绝而非授权部分成功。整对明确包含在包内仍允许原生恢复的正常替换，不把历史账目当服务器资源所有权证书。

accountingVersion 缺失的第四十二/四十三批 task-only 历史继续只保留/中断，不启动补账或迁移。新的 GET/更新等仍各自建立新账目。旧任务停止/恢复不续发，来源自动合并并存及本地编辑保护不变；Room v2/25 表/native v1/依赖不变。

本批只补独立账目/汇总与上述部分恢复保护，不代表完整缓存计价、跨进程前缀索引、复用生成的资源关联、完整配置失效/清理、所有旧历史引用/链图/版本/真实 IO 已完成。全后端要求继续见 [STATUS.md](STATUS.md)。仅正常编译/代码检查，无实际模型/缓存/账号/收费/用户包操作、哈希校验、冒烟或新增单测集。

<a id="vertex-cache"></a>

## Vertex 原生缓存

2026-10-09。Vertex 服务账号原生 Gemini 连接已接入现有 GeminiCacheClient/GeminiCacheTaskService；无新 Core 接口、任务引擎、表或依赖。原 Gemini Developer API v1beta 路径保留，Vertex Express/API-key 和 OpenAI compatible 不自动启用这条原生缓存路径。

### 已接能力

明确配置服务账号/项目/区域后，可沿原 `startGeminiCacheCreate`、`startGeminiCacheList`、`startGeminiCacheOperation` 查询、分页列出、更新 TTL/到期时间和删除；沿原本地因果资源视图/确认执行明确清理。`setGeminiCacheForGeneration` 的 MATCH_PREFIX/PREPEND、来源 `promptCache.mode=explicit` 与 history-tail 共用原聊天/群聊成员生成绑定、流式/非流式和工具续轮。系统/工具与前缀逐值匹配，不加文件哈希。

缓存集合为 `https://<host>/<version>/projects/<configured-project>/locations/<location>/cachedContents`，与生成的 `.../publishers/google/models/<model>` 分开。版本沿现有 v1/v1beta1 配置；global 使用 aiplatform.googleapis.com，区域使用 `<location>-aiplatform.googleapis.com`。创建请求 model 为完整 Google publisher 资源名；响应和生成 cachedContent 使用服务返回的完整资源名。共享 GeminiCacheRoutes 同时供传输与已有任务元数据读写使用，不增加持久路由注册表。

Google 返回的项目编号与配置项目 ID 可能不同；接受该编号表示和同一区域的模型/资源形状，不把它当成归属证明。后续 HTTP 始终取配置项目集合和资源末段 ID，不跟随响应名切换项目/主机。原 CREATE 私有上下文、已确认操作/连接及本地因果关系仍负责绑定；账号列表只观察远端，不直接授权清理、生成或重启后恢复私有前缀。

Vertex 短期 Bearer token 更新不使同一账号缓存失效：运行时连接比较预设凭据 ID、账号 email 标记、私钥、模型/项目/区域/协议和用户头等稳定快照，发出操作/生成用当前 token。账号/私钥或连接改变仍不能沿用旧绑定。普通 Developer API-key 连接保留原精确 key 比较。私钥、标记、token、前缀和分页 token 均不写入普通任务记录。

原操作停止/宿主中断、HTTP checkpoint、独立操作账目、明确配置价格规则/存储报价和 native 元数据关系复用；没有自动翻页、401 后重新发送、失败缓存退回普通生成、后台续期/删除或重启重放。原本地七天 TTL、1 MiB 请求等预算不变，云模型缓存门槛/权限错误由服务返回，不宣称所有模型/区域都可用或价格估计就是实际账单。

### 尚未完成

可用内容沿现有文本、工具和受支持的内联托管媒体；没有新增 GCS 上传/读取或 Vertex 专用大文件服务。原 Developer Files 上传不冒充 Vertex/GCS。CMEK、第三方 publisher、自部署/调优模型、Vertex OpenAI 的 extra_body 缓存、跨进程私有前缀绑定、完整存活结算/自动孤儿清理、ADC/WIF 和真实云账户/Android 运行仍未完成；其他后端缺口继续。

普通离线 app:assembleDebug 已通过，未创建、列出、使用、更新或删除实际云缓存，未操作真实账号/凭据。没有前端、哈希校验、冒烟或新单测；仅编译不能证明实际云权限、计费或设备功能。

官方接口依据：[创建缓存](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/context-cache/context-cache-create)、[使用缓存](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/context-cache/context-cache-use)、[更新到期时间](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/context-cache/context-cache-update)、[列出缓存](https://cloud.google.com/vertex-ai/docs/reference/rest/v1/projects.locations.cachedContents/list)。

# 用量与搜索

按主题合并原功能文档；各节日期及历史状态以 [当前状态](STATUS.md) 为准。

<a id="usage-accounting"></a>

## 用量与成本账本 v1

### 2026-10-10 RikkaHub 消息用量快照导入

RikkaImport 将带 usage 的助手候选转换进原 usage_events，可由原 usageEventsPage/usageSummary 读取。不只处理选中候选，也不把助手 presetMessages 当历史请求。Luna 编写 RikkaUsageMapper，主代理接原 ImportMerge/导入事务；原 DesktopUsageRecord 仅改为共享内部 ImportedUsageRecord，Desktop 的字段和默认用途不变，没有新表、迁移、计费或任务框架。

- promptTokens/completionTokens/cachedTokens/totalTokens 对应 input/output/cacheRead/total；缺失保持 null，不算总和，不推断缓存包含关系、思考或缓存写入。费用固定 unknown（rikka_import_cost_unreported），不按现价补算或冒记0。模型/协议只来自备份 modelId 对应的配置，不借当前助手默认模型，也不伪称上游 resolvedModel。
- 以同来源原消息 UUID 映射事件身份；同批跨节点、会话或分叉重复 UUID 只计一次，保留首次有效记录的聊天关联。不同来源/libraryId 仍隔离，明确再次导入沿原基线/本地修改与删除保护，原位 UPDATE 保留 rowid。没有源请求ID时，requestId只是既有导入分组身份，不是原上游请求证明。
- 有效用量即使所属聊天被本地删除也可进入历史账本，不复活聊天。原消息会计字段另以 source_blob 脱敏保留；无法转换的结构/身份/计数/时间只保留原文，重复和本地删除保护有计数。报告逐聊天列 converted/raw-only/duplicate/locally-deleted，不新增隐藏请求或重放。JSON、旧 nodes 和 schema 25 独立节点表均经过现有候选转换路径。
- 偏移时间或毫秒时间转为 UTC；官方无时区 LocalDateTime 按导入设备 ZoneId.systemDefault 解释，报告明确写时区，这不是恢复原设备时区的证明。改变导入设备时区会改变无偏移时间的解释。finishedAt 只标 source_finished/source_unfinished，不制造本地任务、生成元数据或请求生命周期。

这是源消息里留下的用量快照，不是每个工具轮次/流式片段的完整请求账本；汇总事件数不能当真实请求次数，未知费用不能当历史总账。未执行真实包、数据库、导入/汇总、账户或设备；无哈希、冒烟和新增单测。普通离线构建31秒通过。

固定版本依据：官方 [Usage.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/core/Usage.kt)、[Message.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/ui/Message.kt) 和 [StreamChunkHandler.kt](https://github.com/rikkahub/rikkahub/blob/6c903feba7e6b65f518b320a88ae29efebae77c4/ai/src/main/java/me/rerere/ai/ui/StreamChunkHandler.kt) 已只读核对；不是用户实际版本或设备证明。

### 2026-10-10 桌面历史账本导入

DesktopImport 现将 `usage/*.jsonl` 的 v1 事件转换进原 `usage_events`，随后可通过原 `usageEventsPage`、`usageSummary` 查询，不仅保留原文。两个 Luna 分别编写字段/费用映射及 DAO/仓储方法，主代理接流式读取、来源身份和原三方合并。

- 原 eventId 经既有来源映射成为目标事件身份；requestId、chatId 和可得的消息 ID 分别映射。Desktop、RikkaHub及不同 libraryId 不共用身份。同包及跨分片重复 eventId 只计一次；重复导入不新增事件，本地修改/删除沿原基线保护，源中省略的历史行不自动删除。
- token 缺失保持未知；时间必须有时区，优先原 ts，模型优先 resolvedModel。保留 serviceTier、fastRequested、耗时和状态，context 标明来源与 `desktop_import`；不制造本地任务或请求生命周期。
- 费用只取原 provider/estimated 金额和明确币种，以十进制文本保存，不调用当前价格规则、汇率或发送模型请求。缺失/不完整费用保持 unknown，不冒记0。provider 附带的比较估算没有独立币种，不进入按币种比较汇总；原值仍保留。
- 每个 JSONL 原记录继续脱敏保存；格式可解析但版本/结构/计数/时间不受支持的记录仅保留原文并计入文件报告的 raw-only 数量。修复队列 `repair.jsonl` 不计作已完成事件、不重放；`usage_index.json` 等 JSON 作为完整文档保存，不按行误解析成事件。缺目录明确报告 missing，未知文件类型报告 deferred。
- JSONL 流式读取，每行最多128 KiB并响应取消，不加载整月列表；JSON索引最多4 MiB。沿原导入事务提交事件、映射、基线和报告，更新已有事件用原位 UPDATE 保留分页 rowid。读入/JSON损坏或目标预算超限会使当前导入失败并保留原数据，不跳过为成功。

无需新表、迁移、价格或后台引擎；原生完整备份沿现有表选项带上这些事件和来源记录。桌面旧 generationMetadata 仍保留来源格式，不伪称已转换为 Android 请求/费用创建关系证明。桌面消息里的重复 usage 不额外生成账本；RikkaHub 消息级快照在本日后续批次已接，完整请求账本及其它版本转换仍未证明。未用真实包/数据库/设备执行导入或汇总，无哈希、冒烟或新增单测。

第四十七批：gemini_cache 中 LIST 记录确认账户页面 metadata/hasNextPage，不存分页 token；ledger model/requestedModel=null。cacheListingCount/reportedCacheListedResources/unknownCacheListingResultCount 是观察次数/确认条目数之和/未知结果数量，不是唯一资源或创建 token，不估计创建/存储费。LIST 较大行预预算后完整验证，通用分页不超过 8 MiB 实读量并保持原始游标/hasMore；其他操作/协议原单行约束不放宽。见 [GEMINI_CACHE.md#gemini-cache-listing](GEMINI_CACHE.md#gemini-cache-listing)。

第四十五批：缓存 CREATE 账目增 frozen pricing/projectedCost。usageSummary 的 cacheQuotesByCurrency 独立汇总 creationAmount/projectedStorageAmount 与各自贡献数量，两个 unknownCache*QuoteCount 只统计确认 CREATE 对应维度未知的记录；不混 costsByCurrency/totalKnownAmount、不把 TTL 预测当实际扣款。旧历史不补算，实际成本仍 unknown，见 [GEMINI_CACHE.md#gemini-cache-pricing](GEMINI_CACHE.md#gemini-cache-pricing)。

第四十四批新增 kind=gemini_cache，缓存任务/独立事件同事务 checkpoint；UsageLedgerItem.geminiCache 与独立创建操作/确认创建 token 汇总已接，不混普通聊天 token，不按读取 metadata 重复写入。未知缓存费用不当零，旧 task-only 记录不补账；标识/预算/取消/native 成对证据及剩余创建/存储定价见 [GEMINI_CACHE.md#gemini-cache-accounting](GEMINI_CACHE.md#gemini-cache-accounting)。

更新：2026-10-05。第二十二批 token 账本、第二十五批搜索与第二十九批语音扩展，仅有代码检查与普通 Debug 编译证据，没有实际上游账单或设备运行证据。

第三十六批生成消息的 request/usage/冻结费用与同源历史创建关系在 native 恢复核对，包外原可验证引用的费用/来源被覆盖改坏会阻断。按 eventId 而非假定旧主键查账，同源分叉引用原事件，不新增费用；缺历史证据 warning，不调价补账、不重发，见 [ACCOUNTING.md#generation-usage-restore](ACCOUNTING.md#generation-usage-restore)。

### 入口

通过 `SimpleTavernCore` 调用，数据库工作在 IO dispatcher；写入经过现有 data gate。

| API | 语义 |
| --- | --- |
| `getPricingRules()` | 返回规则和 `PricingVersion(epoch, revision)` |
| `replacePricingRules(rules, expected)` | CAS 替换 user 部分，保留 catalog 部分 |
| `installPricingCatalog(rules, expected)` | CAS 替换 catalog 部分，保留 user 部分；不是网络下载入口 |
| `estimateUsageCost(tokens, provider, model, at)` | 无账本写入的当前规则估算；at 默认现在，历史估算须传原请求时间 |
| `usageEventsPage(filter, cursor, limit, throughSequence)` | 有界事件、上下文、成本快照与问题列表 |
| `usageSummary(filter)` | 固定扫描上界、可取消分页汇总；按币种分别累计 |

规则保存到既有 `app_settings` 的独立 `pricing_rules_v1` 行，不替换全局设置、来源设置或其他配置。CAS epoch 包含当前实例身份与最近原生恢复提交标记，重启或恢复后应重新取快照。规则缓存仅比较完整 JSON 文本，不计算内容哈希。

### 规则与金额

`PricingRule` 的费率为每百万 token 的非负十进制字符串，金额用 BigDecimal，不使用浮点金额或汇率。币种是三位大写标签，不声称已验证当前 ISO 币种列表。没有内置实时模型价格，也不猜价格或默认供应商费用币种；`ProviderConfig.reportedCostCurrency` 只能作为明确配置的费用币种补充。

provider 匹配实际提供商/凭据配置 ID，不是厂商品牌名。匹配顺序为实际模型精确值、请求模型精确值、别名、RE2 正则；同一匹配级别 user 优先于 catalog，剩余同级按规则顺序。服务层级必须一致，`default` 归 standard；Fast 只作为已报告/请求的计价选择，不表示已实现所有 Fast 请求参数。实际模型未知时不伪造 observed identity。

规则可指定生效时间和来源 URL。日期按 UTC 日解释，截止日期包含当天；带 offset 的截止时间为排他上界。单价允许最多 12 位小数、不超过 1e12；十进制文本最多 64 字符。规则总数至多 200，配置至多 256 KiB；每规则别名至多 32、正则至多 8，RE2 不支持的语法拒绝。catalog 写入的 source 必须为 catalog、ID 必须以 `catalog:` 开头；用户不能占用此前缀。source URL 只是记录来源，不自动请求或验真。

### 用量口径与未知

OpenAI 输入总数含缓存读写，普通输入为输入减缓存读减缓存写；输出包含 reasoning，不重复加一次。依据 [OpenAI 官方缓存文档](https://developers.openai.com/api/docs/guides/prompt-caching)。Claude 普通输入不含缓存，三个输入类别分别计价；5 分钟与 1 小时写入明细必须与总写入一致。依据 [Claude 官方缓存文档](https://platform.claude.com/docs/en/build-with-claude/prompt-caching)。Gemini 输出 candidates 不含 thoughts，完整输出相加；缺少 thoughts 时仅在对应口径且 total/input/output 都已知时用总量差值推导。依据 [Gemini UsageMetadata](https://ai.google.dev/api/generate-content#UsageMetadata)。Gemini 本次生成不把缓存创建/存储费用伪装为逐 token 缓存写入费。

缺字段不是 0。明确报告 0 的类别允许金额 0，即使没有该类别费率；缺计数或正数缺费率则不产生完整估算。已提供但不是 Long 整数的计数、负数、缓存超过输入、已包含 reasoning 却 reasoning 超过输出、完整分项与 total 矛盾、缓存 TTL 明细矛盾均拒绝完整本地估算；总量比较用 BigInteger 防止加法溢出。工具额外计费维度或未覆盖的混合模态标记未知；明确全 0 的 server tool 对象不会仅因对象存在就触发额外计费警告。实际模型被脱敏时不回落到请求模型猜价格。

`CostSnapshot.source` 为 provider / estimated / unknown。明确合法的供应商金额与币种优先作为 amount/currency，保留原始 providerReported；本地 estimatedAmount/estimatedCurrency 单独保存，不覆盖供应商金额，不跨币种相加。没有完整估算时 partialEstimatedAmount 仅是已知分项，不是总费用。缺匹配规则、无效配置、未知计数等保留 reason。合法供应商费用即使本地计数矛盾仍保留，但不是已经核对支付发票。

### 记录与读取

第三十二至三十四批预处理使用普通 token 事件，context purpose=tts_preprocess/scope/sourceNamespace/taskId/messageCandidateId，TTS preprocessingRequestId 关联该普通 UUID，开始冻结价格、不混合字符或搜索计费。第三十四批 `_requestLifecycle` / TokenUsageRequest 是同一行 prepared/running/终态：四协议唯一 HTTP 前回调提交本地发送标记，失败不执行请求；完整 JSON/身份/空 cost CAS，短 NonCancellable 提交及赋值。供应商完成后本地稿失败不抹调用；不存正文/key/原始输出/重放句柄。startup 和 native 只中断未结束行为 unknown，不查实时价/重发；无新标记历史行不补造证据。

第三十五批聊天/群聊/工具往返、总结 chunk 与 MVU/KG 段/工具轮均接 executeTokenRequest，普通 purpose 为 chat_generation/memory_summary/state_maintenance，chat scope 与源/task/candidate/segmentIndex/roundIndex/turnIndex 冻结。沿用原 requestId，不强制 UUID；预处理仍 UUID。usageEventsPage 返回可空 tokenRequestLifecycle，坏关系 invalid_token_request_lifecycle 不计已知费用；prepared/running 原地更新不是上界内容快照。预算身份 4 KiB/usage 64 KiB/cost 32 KiB，startup 仅新规范未结束记录先预算再严格解析，native 按逻辑键检查可得消息/任务/summary job 的同 chat/任务用途，不重发；完整范围见 [ACCOUNTING.md#token-request-accounting](ACCOUNTING.md#token-request-accounting)。

第三十五批供应商请求在每次调用结束的独立短事务收尾，再提交消息/记忆/状态；不再把账本与业务应用放在同一外层事务，业务回滚不撤销已完成的调用费用。旧补记/批量 UsageAttempt 入口移除，首回调失败不发 HTTP，终态记录失败保留 unfinished/安全错误，不重发。消息 GenerationMetadata 回填同一已保存成本，规则开始冻结，调价不改历史。未知用量/费用不当免费，尚未证明服务器收款、发票或真实进程/数据库耐久性。

上下文放在 usageJson 的 `_recordContext`，原 token 字段仍在顶层；成本放既有 costJson，Room v2/25 表不变。无效定价配置记录 unknown，而数据库错误仍向上传播。每行身份累计至多 4 KiB、usage 64 KiB、cost 32 KiB；分页先用 SQL 字节长度读取预算，超预算历史行明确失败，不静默丢弃。新分页 limit 为 1..200。

filter 的 chat/provider/model 是账本存储字段精确匹配，model 为请求模型，不是 resolvedModel。since 包含、until 排除，按带 offset 时间的 Instant 比较。过滤发生在有界扫描后，所以一页可以 items 为空但 hasMore=true；必须按 nextCursor 推进。无效历史时间在时间过滤时记 excludedInvalidTime。cursor 绑定 epoch 和原 filter；扫描上界通过 throughSequence 返回，分页时传回以排除后来新增记录。恢复/重启后旧 cursor 冲突，不能直接复用 rowid。

summary 按最多 200 行的短事务分批读取，不一次加载全账本。token 总和用 BigInteger 字符串，金额按币种用 BigDecimal 字符串；至多 64 个币种桶，超限明确失败。providerAmount 与 estimatedOnlyAmount 的和为 totalKnownAmount；comparisonEstimatedAmount 仅用于对照，不能再次加到总费用。unknown/invalid/excluded 计数单独保留。reportedInputTokens 是各协议原始输入字段之和，不是跨协议标准化输入总量，不能直接作为统一缓存命中率分母。

### 尚未完成

第二十九批独立 TTS 事件/显式定价/启动及原生恢复终止已接，见 [ACCOUNTING.md#speech-accounting](ACCOUNTING.md#speech-accounting)。直接/后台合成都记录 _kind=tts，provider 列 tts:<provider>，model 为请求模型；prepared/running/终态同一行，成本冻结于请求开始的匹配规则，按次/输入 Unicode code points/报告字符估算。通用 kind 可取 llm/web_search/tts，UsageLedgerItem.speech 与 summary 语音事件/输入字符/分提供方报告字符分列，不混 token。缓存发布失败不抹供应商调用事件，绑定/读取/清理不新增账；完整其他维度、来源价表与真实发票未完成。

第二十七至二十八批只增加 SPEECH_SYNTHESIS 任务/缓存/消息引用，当时尚无独立费用账本。SpeechAsset 的 MiniMax 字符仍只是报告值，普通任务成功/失败和音频文件不等于实际账单；第二十九批账本估算也不变成供应商发票。不增加 token 或假零费用，延后绑定/解绑是本地引用操作，不是额外云端请求，见 SPEECH.md#tts。

第二十五批独立搜索账本与显式 request/credit 计价已接，见 [ACCOUNTING.md#search-accounting](ACCOUNTING.md#search-accounting)。通用分页可按 kind=llm/web_search/tts 过滤，默认全部；SearchUsageSnapshot 不伪装 UsageTokens，provider 用 web-search: 前缀。搜索一行 prepared/running 阶段可按完整旧 JSON 条件更新，终态和 costJson 提交后冻结，不把三个状态算三笔；summary 单列搜索 credits/事件，不增加 token 未知计数。扫描上界固定不表示活跃行被冻结，kind 过滤可产生空页但 hasMore=true。以下剩余计费指全部维度/真实账单，不再指没有独立事件入口。

第二十六至二十九批四云端 TTS 调用/任务/缓存/绑定/账本已接，见 SPEECH.md#tts / ACCOUNTING.md#speech-accounting，不把字符伪装 token 或把未记录费用当免费。普通助手其他工具、完整 TTS 费用维度仍待实现。搜索调用/原生接线与 Tavily/博查账户代理见 ACCOUNTING.md#web-search，独立账本/显式估算见 ACCOUNTING.md#search-accounting。Brave 未支持账户查询，实际计费与全部维度仍未证明。账户数据不入账本，不把原始 credits/余额或账户差值直接当金额，credits 只有乘明确费率才形成估算，缺币种保持未知。来源价格配置转换、实时/catalog 下载、所有模态/工具/地域/分层账单维度、标准化模型统计与延迟分位数也未完成。旧 forChat 入口已补同事务字节预算及 IO 读取，其他表/大行仍需继续审计。已准备请求记录不等于所有真实 HTTP 尝试或账单，准备前失败、进程死亡、事务失败仍可能没有新账本事件；没有执行请求/发票/设备验证，不证明账单完整性或崩溃持久性。原生包包含普通配置行与历史成本快照，但完整跨版本恢复仍待审计；不恢复运行中的旧请求。

<a id="token-request-accounting"></a>

## 普通模型请求持久账目（第三十四至三十六批）

更新：2026-10-05。当前所有仓库内 LlmClient.stream 业务调用（聊天、总结、状态维护、语音预处理）已接发送前持久账目；只有代码与普通 Debug 编译证据，没有实际请求、收费、设备、进程死亡或断电证明。desktop/RikkaHub 自动合并并存保持，不做哈希、冒烟或单测集。

### 请求而非业务结果

UsageStore.executeTokenRequest 先冻结开始时价格并插入 prepared，构造请求后由四协议共用 HTTP 前回调提交 running/httpAttempted/attemptedAt，再检查取消执行一次 Call。prepared/running 成本为空而非零；正常结束/失败/取消更新同一行，身份/eventId/requestId 相同，不新增第二账目。回调/数据库失败不发送，第二回调拒绝，默认 HTTP 防 follow-up 重发仍有效。

结束记录在调用外层 finally 中、短 NonCancellable 独立事务完成，不嵌入后续消息/记忆/状态提交事务。调用完成而业务应用失败、停止或提交回滚时，已保存的供应商请求账目保留。旧的 MemorySummary/StateMaintenance UsageAttempt 列表与最终批量 recordUsage、聊天在工具/成员/终态事务中补记的入口已移除；不把 prepared/running/终态算三次，也不在业务回滚后重发。

| purpose | 请求关系 |
| --- | --- |
| chat_generation | 单聊、群成员、客户端工具往返和活跃原生 continuation 每次 HTTP 单独 requestId；taskId/candidateId/sourceNamespace、chat scope、roundIndex 与可得 turnIndex；聊天流保留可得首 token/耗时 |
| memory_summary | 每个普通/历史重建 chunk 独立记录，taskId=summary job、segmentIndex；空/取消/源或记忆版本变化不抹已完成调用 |
| state_maintenance | 每个 MVU/KG 段/工具轮独立记录，taskId/segmentIndex/roundIndex；仅维护草稿的模型调用也计账，实际状态/规则原子应用语义不变；无事实且未调用模型不造事件 |
| tts_preprocess | 保留既有 UUID/assistant 或 global scope/两阶段关联；不混入 TTS 字符或搜索 credits，细则见 SPEECH.md#speech-preprocessing |

普通请求沿用既有 requestId（生成/状态为 task 首请求或新随机 ID，总结还有 job:chunk:N），不用改成 UUID 或新建重复记录。新 row.id/eventId/requestId 统一等于此 ID；预处理仍严格 UUID。context 增兼容可空 segmentIndex/roundIndex/turnIndex，task/candidate 是创建时关系，不是请求恢复句柄。

### 费用、隐私与错误

供应商 Done 的完成状态与业务结果分开：返回空稿/后续工具或提交失败不把已完成调用改为“未发送”。断流/取消只记可得计数；价格按开始冻结规则，不在任务结束读新费率。尚未越过发送边界时 cost unknown/request_not_sent，既非零费用也非发票。结束保存或成本回填失败给安全 accounting_failed/requestId；已有异常/取消保留并附安全 suppressed 错误，不能据此自动再次执行。

普通 token 顶层与 _recordContext 不变，_requestLifecycle v1 保存本地发送标记/时间/安全错误。供应商用量在流保存及 finalization 两处经同一有界清理：32 KiB、严格 JSON/Unicode、非负计数/有界模型 tier/金额字符串，按解析后字符串查凭据，防 JSON 转义漏检；不可信项降未知 usage_metadata_omitted，不把未知补零。凭据只在本进程使用，不进生命周期，返回成本中的配置/报告 metadata 含选中凭据时降 unknown/cost_metadata_omitted。

聊天 GenerationMetadata 回填已保存的同一 CostSnapshot，后续消息提交失败不撤销供应商账本；fallback 元数据也携带该快照。记忆/双状态仍先检查源和版本，再在各自短事务提交，不因账目提前保存而允许部分应用。任务活跃期间原生 backup/restore 已拒绝其他非终态任务；预处理独立调用还持 shared gate，网络不持 Room 事务。不是恶意低层 raw SQL/shell 的隔离边界。

### 读取、启动和原生包

通用分页返回 context 与可空 tokenRequestLifecycle，旧无标记行仍历史兼容；prepared/running 原地更新不代表固定上界内容快照，非法新关系不计已知金额。startup 只按规范 header 和三身份相同、空 cost 索引新未结束记录，先 SQL 身份 4 KiB/usage 64 KiB 预算，再严格解析，每页 200/最多 100000 行和 64 MiB，标 interrupted/process_interrupted，不扫描并重写所有历史普通记录。

native 按逻辑 JSON 键校验所有四个用途及时间/状态/成本，稳定归一化 unfinished 为 interrupted/native_restore_interrupted/unknown；不以 header/键序/转义作真实性检查。可得创建消息、task、summary job 必须同 chat；任务种类须对应生成/总结/MVU/KG/语音，错误 TOKEN_REQUEST_MESSAGE_SCOPE_MISMATCH/TASK_SCOPE_MISMATCH/TASK_KIND_MISMATCH/SUMMARY_SCOPE_MISMATCH。历史缺失引用仅 warning，不以当前配置/价格补账或重发。保留语音关系仍比较完整预处理 usage/cost；第三十六批生成消息与可核实费用关联、包外保留引用覆盖保护及同源分叉共享已接，见 ACCOUNTING.md#generation-usage-restore，不能替代全部 native 关联审计。

Room v2/25 表/format v1/外部依赖无变化，新属性默认可空；旧无 lifecycle 事件不补造请求前证据。全部来源/用户 APK/包版本兼容、低磁盘/并发/崩溃/WAL/断电、服务器接受/实际发票、所有原生工具/参数/计费维度与完整后端继续，见 STATUS.md。httpAttempted 只证明本地发送前提交，取消和连接失败可能紧随其后，不保证上游收到或收费。

<a id="generation-usage-restore"></a>

## 生成消息与历史费用的恢复关联

第三十六批，2026-10-05。仅代码检查与普通 Debug 编译，不是用户包恢复、设备、HTTP 或实际账单证据。desktop/RikkaHub 自动合并并存不变，不做哈希、冒烟或单测集。

### 请求身份与分叉

NativeGenerationUsage 只解释 GenerationMetadata v1 的账目字段，不解码 search/providerTurn 等 opaque 内容。按 eventId=requestId 查费用，不假定旧费用主键等于请求 ID；旧 record 模式的 createdAt 可以是请求结束时间，开始时间按冻结 context 判断。新持久事件仍遵守三身份相同与 TokenRequestAccounting 生命周期契约。

已知元数据与可核实普通生成账目核对 request/provider/protocol/requestedModel/startedAt、usage/resolvedModel、CostSnapshot，以及候选可得重复 usage。候选 chatId/messageId 指向当前消息；费用的创建 chat/task/candidate 保持原身份。ChatForker 只改消息身份、保留历史请求和费用，不复制账本：同来源的分叉/多消息引用同一费用合法，不要求分叉消息等于原创建消息，也不重复收费。

当前消息来源须与新账目冻结 sourceNamespace 相同；旧无生命周期账目只能从尚存创建会话证明来源，不能借其他来源或猜来源。可得创建会话也须同来源，可得 task 必须在原创建会话且为聊天/群聊，原创建候选必须是该会话 assistant。历史创建引用缺失不补造；旧记录证据不足只 warning。正文编辑、工具等待后的总耗时、当前模型配置和价格不用于重算或锁定历史费用。

### 覆盖与中断

包内候选的已知账目关系不匹配阻断 GENERATION_USAGE_MISMATCH；缺费用、未知版本/形状、不可核实旧账目分别 warning，不根据当前价格补账。确认了可核实账目后，关系字段不可读也阻断。新生命周期事件本身仍由既有严格 native 验证负责，不借历史 warning 绕过。

REPLACE_INCLUDED 涉及 usage_events/chats/tasks/message_candidates 时，NativeGenerationRestoreGuard 扫描未被包内显式替换的生成消息。只有原来能核实、且受此次覆盖影响的引用受保护：完整事件身份、usage/cost JSON 与来源的证明不得变为不同或不可核实，报 PRESERVED_GENERATION_USAGE_CHANGED，整次恢复拒绝，不悄悄改包外消息。原已失效/不明/不可读引用只 warning。显式替换的消息走上述候选检查；普通 JSON 对象键序/空白变化不算费用变化。

已知 v1/协议的 queued/running/awaiting_tools/stopping 状态在迁入时稳定改为 interrupted，保留全部其他原始 JSON 字段，即使旧费用字段无法解码。未知版本/协议/不合法历史 JSON 不冒充已解释。没有执行、重试、重新估价或恢复旧请求入口。

### 有界读取与限制

每页 200、至多 100000 行；SQL 先看字节长度，再逐列分块读取，避免整行进入 CursorWindow。ID/chat 为 1024 字节，生成元数据沿用 native TEXT 的 32000000 字节/8000000 码点上限，重复 usage/账目 usage 为 64 KiB，cost 为 32 KiB，model 为 2048 字节、时间为 64 字节。元数据不套用语音消息 256 KiB 总行上限，不截掉 opaque 原生输出。守卫累计读取文本 64 MiB，超整次行数/读取预算明确失败，不返回部分成功；本来不可读的大旧行 warning。

没有表、Room v2/25 表、format v1 或依赖变化。费用证明只是本地历史字段一致性，不是真实服务器接收/发票/语义正确性。其余 native 引用/来源版本/最终大行/配置/CAS/真实 IO、WAL/断电/并发及全部后端仍待补，见 STATUS.md。

<a id="search-accounting"></a>

## 独立搜索账本与计价

更新：2026-10-05（第二十五批）。实现与普通 Debug 编译证据，不是供应商账单/设备运行证明。

### 入口

- `getSearchPricingRules()` 返回 SearchPricingSnapshot 与 PricingVersion。
- `replaceSearchPricingRules(rules, expected)` 只替换 user 分区；`installSearchPricingCatalog(rules, expected)` 只替换 catalog 分区。两者不擦另一分区，catalog ID 必须以 catalog: 开头。没有自动下载价表或内置实时费率。
- `usageEventsPage(UsageFilter(kind = "web_search"), cursor, limit, throughSequence)` 查看搜索事件；kind=null 包含全部，kind=llm 只取模型事件。搜索 provider 过滤用 web-search:tavily / web-search:bocha / web-search:brave，不与模型 provider 同名混用。
- `usageSummary(filter)` 的 searchEventCount、reportedSearchCredits（按提供方）、unknownSearchCreditsCount 与币种费用桶区分搜索和 token。搜索不增加 unknownInput/Output/Cache 计数；credits 不是 token 或金额。未知费用仍计 unknownCostCount，不能据此声称完整账单。

### 计价

规则保存在独立 app_settings.search_pricing_v1，CAS 绑定实例/原生恢复 epoch + revision；至多 200 条/256 KiB，无哈希。provider 明确为 tavily/bocha/brave，unit=request 或 credit，amountPerUnit 为非负有界十进制，currency 为显式三位大写代码。可指定有 offset 的 effectiveFrom/To（左闭右开），不接受猜时区的日期；matchParameters 仅 search_depth/topic/max_results/count/freshness/summary，键值精确匹配，字段不填就不猜供应商默认值。

先选提供方/生效时间/条件匹配；匹配的 user 规则优先 catalog，再取条件最多者，同优先级多个候选则 unknown/ambiguous_search_price，不默选第一条。调用准备时冻结 revision/选中规则；后续调价不改已冻结事件。配置非法时仍可搜索，费用记录 pricing_configuration_invalid；没有规则不当免费。

request 规则只在调用 completed 时按 1 次估算；失败/取消/中断是否收费未知。credit 规则必须有有效供应商报告，已收到的 credits 可用于估算，但 estimated 不表示已付金额。乘法按 BigDecimal，结果超预算明确 unknown/search_cost_exceeds_budget，不擅自舍入。没有返回供应商真实金额/货币的适配，不从余额差、HTTP 成功、结果数量或公开宣传价格制造发票。

Tavily 按官方 [Search 文档](https://docs.tavily.com/documentation/api-reference/endpoint/search) 支持 include_usage；未显式配置时本客户端设 true 请求用量元数据，显式 false 保留。不改 search_depth 或其他内容/价格选项。只读取 Tavily usage.credits 的有效非负有界十进制；缺失/异常保持未知，不猜 1/2 credits。博查/Brave 当前不解释未验证的 usage 字段；可按明确配置的按次规则估算，但无真实账单证明。

### 生命周期与存储

直接 webSearch 和生成工具调用均通过 SearchUsageStore。查询/凭据与参数先校验，之后创建新 requestId；查询正文、来源内容、密钥、账户余额不进该账本，仅保留白名单计价维度、来源配置 scope、可选 chat/task/call IDs。返回 WebSearchResult.requestId 可对应 usage_events.requestId，eventId/id 为 web_search:<requestId>，provider 加 web-search: 前缀、model=null。没有新表/列/依赖，Room v2/25 表不变。

usageJson 为 version=1、_kind=web_search 的 SearchUsageSnapshot。先提交 prepared，传输 semaphore 放行后、调用 execute 前提交 running/httpAttempted=true，最终提交 completed/failed/cancelled，costJson 同次写入。httpAttempted 是本地进入 HTTP 执行前的意图，不证明服务端收到请求或扣费：标记之后仍可能在 execute 前取消。一次调用只有一行，不把 prepared/running/final 当三次收费。costJson 非 null 后正常流程不可再写；已完成成本不自动重估。

状态更新以完整旧 usageJson + costJson IS NULL 作条件，不哈希、不删除型替换。短提交和内存状态赋值处于 NonCancellable，取消后仍收尾；网络本身可取消、超时、无自动重试/回退。账本开始失败则不发请求；HTTP 已执行而终态写库失败返回安全 accounting_failed（或作为原异常的安全附加信息），保留未完成行，不冒充免费/已成功记录，不再执行搜索。进程死亡/磁盘异常仍可能留下未完成或缺证据的行，不能证明远端扣费/事务持久性。

整个搜索持一个 shared data-operation lease：不是长 Room 事务，也不串行普通写/搜索；只是让原生 snapshot/restore 在本次账本与网络调用结束前明确报 busy，避免恢复夹在事件生命周期中。账户只读状态查询不持该长 lease。直接搜索仍是调用者所有，不新增后台任务；生成内仍沿用既有任务/前台/停止宿主。

初始化按至多 200 行先查 SQL 大小再读取，将 prepared/running 行标记 interrupted、费用未知；不重发或续接。原生包保留规则/事件/终态费用，恢复把包内未结束记录稳定规范为 interrupted（时间不早于包/原开始），验证 kind/version/来源/历史引用；旧无 kind 的 usage 仍按 LLM。旧 APK 不认识新语义，完整跨版本兼容待证明，不能只凭表数相同声称可互通。

分页继续沿用 rowid/epoch/固定扫描上界、identity 4 KiB/usage 64 KiB/cost 32 KiB 预算，kind 与时间在有界解码后过滤，故一页可为空但 hasMore=true，必须继续 nextCursor，不因空页停止。扫描上界固定不意味着正在运行的单行版本被冻结；summary 为分批观察，不是所有活跃调用同一瞬间的快照。坏版本/结构明确 issues，不伪装 token。金额按币种分开，搜索费用与模型费用为独立事件；原生搜索仍由模型事件标未知额外工具计费，未在此再次收一次费用。

### 剩余范围

分页及原生恢复还核对终态 CostSnapshot 与该事件冻结规则/quantity 的结构化计算结果：不匹配时标 issue/阻断恢复，不把异常费用纳入汇总。这是已有字段的精确语义比较，不是内容哈希或签名校验。启动扫描同时限定 eventId 前缀并检查 kind，不按提供方名称独自把历史 token 行改成搜索。

真实上游计费/取消/权限、全部计费维度与实际发票、Brave 结果长期存储授权兼容、自动 catalog/来源价格映射、全原生能力仍待补/验证。普通助手其他工具/TTS、真实 rootfs/文件隔离、剩余输入、大行/异常/版本/持久性/设备的完整 BACKEND_COMPLETION 范围不缩减。本轮没有真实请求、哈希校验、冒烟或单测。

<a id="speech-accounting"></a>

## 语音用量与估算费用

更新：2026-10-05，第二十九批。Room v2/25 表不变，复用 usage_events 和 app_settings；只代码/普通编译证据，无实际合成/账单/设备证明。不下载价表、不做哈希、冒烟或单测集。

### 入口与事件

第三十二至三十五批预处理仍普通 LLM token 事件，TTS context/asset/progress.preprocessingRequestId 指向该 UUID，合成 requestId/tts: 事件另记。预处理与当前聊天/总结/状态维护调用点均接单行 prepared/running/终态/发送前回调/分页 lifecycle/启动与 native 中断，账本独立于业务提交收尾，不发送不伪造费用、不重发。处理成功后停止/挂接失败保留 LLM 记录，原生检查可得完成事件/发送标记/历史冻结费用，旧无标记兼容。httpAttempted 仅本地边界，实际进程/持久性/发票仍缺，见 [ACCOUNTING.md#token-request-accounting](ACCOUNTING.md#token-request-accounting)。

`getSpeechPricingRules()` 返回 SpeechPricingSnapshot(value, PricingVersion(epoch, revision))；`replaceSpeechPricingRules(rules, expected)` 仅替换 user 部分，`installSpeechPricingCatalog(rules, expected)` 仅替换 catalog 部分。两个写入都是完整版本 CAS，不覆盖另一部分，不发网络；进程/原生恢复 epoch 改变后旧版本失效。规则放在 app_settings 的 speech_pricing_v1，与搜索/token 配置分开。

`synthesizeSpeech` 和 `startSpeechTask` 实际合成都走 SpeechUsageStore。会话/来源/凭据/参数校验准备成功后，调用 synthesize 才新增一个事件；preview、排队前取消/配置失败、缓存读取/统计/绑定/解绑/淘汰/清理不创建云端账目。后台任务在事件 context 记 chatId/taskId/启动时 messageCandidateId；直接合成可有 chatId，没有假后台任务。

每个新事件以 tts:<UUID> 为 id/eventId，requestId 是独立 UUID，provider 列为 tts:minimax/glm/openrouter/siliconflow，model 列为实际选定请求模型。usageJson 为 SpeechUsageSnapshot(_kind=tts, version=1)，不伪装 UsageTokens，也不按相同文本/hash 去重。SpeechAudio 和新缓存 SpeechAsset 返回 requestId，读取缓存保持这个关联；绑定到别的消息不把原请求改成另一次收费。

事件存 scope(global/assistant:<id>)、冻结 sourceNamespace、模型、format/voice_id/发送的 speed/volume/pitch/emotion/sample_rate/gain/endpoint_host、输入字符数、可得供应商字符数、返回字节数和生命周期。没有正文、音频字节、API key、HTTP body、余额或恢复请求指令。模型/音色/情绪元数据若包含选中凭据则准备阶段拒绝，避免写入账本。第二十八批缓存 task 的 spokenText 仍是用户文本，这是另一种记录，不应混称为账本含正文。

### 生命周期与一致性

准备时同短 Room 事务冻结规则并插入 prepared 行；等待 HTTP slot 可取消。即将调用 HTTP execute 的边界先以完整旧 usageJson、costJson IS NULL 条件写 running/httpAttempted=true，再发请求；记录失败就不发 HTTP。httpAttempted 仅表示本地越过这个边界，不证明服务器收到、接受或收费，取消可能发生在标记之后、发出之前。

prepared/running/终态始终同一行，不算三笔。正常响应记 completed，失败/取消记 failed/cancelled，短 NonCancellable 条件更新与本地状态赋值一起保护提交，网络和队列仍可取消。最后写库失败返回安全 tts_accounting_failed（附 requestId）；若原请求已失败/取消则保留原异常，并附加账本失败。此时 unfinished 行不会被假装完成，不自动补发。

账本围绕供应商调用完成，不等于缓存 task 成功。上游成功后本地正文变化、缓存发布/磁盘失败或停止，已有合成账目保留，不把音频未挂上解释成没收费。停止合成也不能承诺上游不收费。若已拿到响应再被取消，能保留的 reportedCharacters 仍可保留；响应未交回/校验失败时不猜供应商计费量。

一次事件持 shared data gate lease，防原生快照/恢复截断账本生命周期；不持长 Room 事务，不串行其他网络/普通写入。直接合成因此与此前不同：现在写账本并持该 lease，仍不保存音频/消息或创建前台宿主。后台语音同时保留其现有任务 lease/通知/停止。

### 显式计价

规则要求 provider/currency/amountPerUnit/unit，model/scope/sourceNamespace 和 matchParameters 可选。条件精确匹配发送参数（例如 speed 的字符串为 1.0）；未发送控制没有隐式默认条件值。有效时间要求 offset，起点包含/终点排除。user 匹配优先于 catalog，然后按明确条件数择最具体；同等具体有多条则 ambiguous_speech_price，缺规则/坏配置为未知，不选第一条。

| unit | 计价量 | 边界 |
| --- | --- | --- |
| request | 成功响应为 1 次 | 非成功收费未知 |
| input_character | 本次完整输入 Unicode code points | 成功响应才估算；不是 UTF-16 长度/UTF-8 字节/grapheme，也不宣称厂商实际计费字符口径 |
| reported_character | 供应商明确报告的字符数 | 当前只有 MiniMax 适配器可得；缺值未知，不能拿输入字符或音频字节顶替 |

amountPerUnit 是每一个上述单位的明确费率，不是每百万。金额 BigDecimal，不用 Double；rate 非负、至多 12 小数位，金额有统一精度/规模预算，超量未知并给 reason。显式零费率才可估为零，缺信息不补零。返回 CostSnapshot(source=estimated)，不是 provider invoice；没有内置实时价格、价格抓取、余额差计费或伪造 providerReported。

reported_character 有报告量时可估算，即使响应交回后本地取消，也不把已报告量抹为零。其他非成功状态保持 speech_charge_unknown；未越过发送边界为 speech_request_not_sent。多个币种分开，不转换汇率。不知道 token/音频时长/订阅、免费额度/分层/地域/克隆费用的量或规则时不声称完整账单。

### 通用读取、恢复与预算

既有 usageEventsPage/usageSummary 可按 kind=llm/web_search/tts 筛选，缺省全部；provider 精确字段使用 tts:<provider>，model 为请求模型。UsageLedgerItem.speech 返回结构化事件，usage 为 null；context.protocol=TTS/status/起始与可计算总耗时用于诊断，不制造首 token 延迟。费用以冻结规则重算核对，不一致记录 issue，汇总不计异常金额。

UsageSummary 单列 speechEventCount/speechInputCharacters/reportedSpeechCharacters（按 provider）/unknownReportedSpeechCharactersCount，不增加模型 token 或 token 缺失计数。金额仍按币种汇总。通用分页仍为固定上界/epoch/过滤，有界扫描可产生空页，须看 hasMore/nextCursor；prepared/running 还会原地更新，不是不可变快照。

每次定价至多 200 条/256 KiB，事件 usageJson 64 KiB、身份总和 4096 字节、成本 32 KiB；通用和启动每页 200，SQL 先查预算再装 DTO。启动只扫描 tts: 标记、明确 provider、costJson 为空的行，严格校验 _kind/身份后标 interrupted/process_interrupted，费用未知，不重发。不是所有 HTTP 尝试都必然有最终记录：准备前失败、进程/数据库故障仍可能缺证据。

原生备份带普通配置和 usage_events，恢复检查 v1/字段预算、身份/模型/会话与成本快照及可得历史任务/消息引用；prepared/running 以稳定包生命周期时间改 interrupted/native_restore_interrupted，不重开连接。第三十批可得资产/requestId 账目相互核对创建 scope/task/chat/message、格式音色/输入报告字符/字节和成本；原有效包外语音引用所指账目被显式覆盖改成另一笔时阻断，旧未验证关联只 warning，不猜费用。缺历史引用 warning，旧版本不识 _kind=tts 的相应包不能假定兼容。完整其他引用/版本/幂等/低磁盘/WAL/断电、实际供应商计费和设备行为仍待证明。

来源价格格式导入、实时 catalog、全部 token/时长/订阅/克隆等维度、供应商发票/请求跟踪、实际发送/进程和数据库耐久性证据、音色目录与完整 TTS 能力继续实施，不能用本批账本或编译代替整个后端完成。

<a id="web-search"></a>

## 搜索后端 v1

更新：2026-10-05。第二十三批搜索/生成接线、第二十四批账户状态、第二十五批独立搜索账本/计价已接；普通 Debug 编译通过，未发送实际搜索/模型/账户请求，没有账单或设备运行证据。

### 公开入口与配置

- `getWebSearchConfiguration(assistantId = null)` 返回配置、CAS version 和各独立提供方的 configured 标志；只检查加密凭据是否存在，不联网检查可用性/余额。
- `getWebSearchStatus(assistantId = null, fetchAccounts = false)` 默认只返回本地配置状态；明确传 true 才查询已配置的 Tavily 用量和博查余额。Brave 无已实现的余额查询适配器，不发请求，不声称鉴权成功。
- `saveWebSearchConfiguration(configuration, expected, assistantId = null)` 写独立配置。连接只含 credentialId、可选 baseUrl 和 parametersJson，不含密钥。
- `setProviderCredential(credentialId, apiKey)` 沿用加密凭据入口。不要把密钥放入搜索配置、全局 JSON 或 parametersJson。
- `webSearch(query, assistantId = null)` 是调用者明确发起的独立搜索；返回 provider、hits、answer、截断标志和可报告的 credits。调用者取消会取消网络连接。它不是新后台任务，界面离开/进程被杀后不续跑。
- 两组 `startGeneration` 和 `startGroupGeneration` 重载新增 `webSearch: WebSearchOptions`，默认 `enabled=false`，可指定 Gemini 来源。生成内调用使用原有生成任务/前台宿主/停止流程，不依赖 UI 订阅。

配置保存在既有 `app_settings` 的 `web_search_v1:global` 或 `web_search_v1:assistant:<id>` 独立行，不覆盖全局设置、来源助手、提供商预设或其他来源。没有独立行时读取对应 settingsJson.webSearch；适配桌面原有字段，只取当前支持的参数，非空明文 key 拒绝使用。默认凭据 ID 为 `web-search:global:<provider>` 或 `web-search:assistant:<id>:<provider>`，也可明确指定另一 ID；不会猜其他凭据或自动导入 key。

本地会话默认用 global；来源会话默认用同 sourceNamespace 下唯一助手的搜索设置，缺少/多个来源助手明确失败，不借用另一来源/global 的 key。`WebSearchOptions.assistantId` 可明确选同来源助手，本地会话只可选无来源助手；不存在或来源不一致则拒绝，不能默选第一项。生成每个群成员开始时重新解析配置；同成员的工具轮次固定当前连接/凭据快照。设置更新或 key 轮换用于后续新请求/成员，不重放旧调用。

CAS version 绑定 scope、实例/原生恢复 epoch、revision；首次从来源设置读取时还绑定有效配置完整文本 basis，不计算哈希。显式保存后成为本地覆盖，后续来源导入不自动擦掉该独立行。重启/恢复后重新取 version；单行配置 64 KiB、来源设置读取 256 KiB、单连接 parametersJson 16 KiB，先查 SQL 字节长度再读。JSON 拒绝重复 key、过深嵌套和无效 Unicode；Room v2/25 表不变。

### 路由

| 已解析模型协议 | 开启搜索后的路线 |
| --- | --- |
| Chat Completions / compatible | 选定 Tavily / 博查 / Brave 的独立函数工具循环 |
| Responses | 厂商原生 `web_search`，不要求独立 key |
| Anthropic Messages | 原生 `web_search_20250305`，每请求 max_uses=5，不要求独立 key |
| Gemini | 请求 geminiSearch > 会话 overrides.params.geminiSearch > 当前配置；native 为 google_search，independent 为独立函数循环 |

失败不切换提供方，不从原生切独立，也不从独立切原生。不替换模型，不假定当前模型支持所有工具组合；上游不支持时明确失败。新版 Anthropic 动态过滤/隐含 code execution、OpenAI 深度研究/后台任务不是本轮实现。

独立 `web_search` 函数只接受 query，当前生成明确开启独立搜索后才附标准 schema。预设擅自声明该函数、原生模式混入同名本地函数、没有会话级内存许可都拒绝。call ID 仍绑定任务/会话/参数/搜索许可身份，已尝试失败调用不能用同 ID 再执行。搜索是网络读取，不要求沙箱，不赋予文件读写或 shell 能力，也不能从独立 sandbox-task 入口绕过。其他模型工具仍沿用原有审批和路径前提。

Anthropic `pause_turn` 只在当前活跃任务的最多 8 轮循环中把原始 assistant blocks 原样送入下一新请求；不是恢复停止/中断/旧任务。厂商 server_tool_use 与结果不交给本地函数执行，HTTP 200 内的 search error 也使生成失败。工具/暂停达到轮数上限明确失败，保留已提交历史与账本，不伪造最终回答。

### 独立请求与结果边界

只用官方 REST：Tavily POST /search 的 Bearer header；博查 POST /v1/web-search 的 Bearer header（仅 api.bocha.cn / api.bochaai.com HTTPS 根）；Brave GET /res/v1/web/search 的 X-Subscription-Token header。拒绝自定义目标、重定向、自动连接重试和供应商回退，不抓取搜索命中的网页。

Tavily 支持 max_results、search_depth、topic、include_answer、include_raw_content、time_range、start_date/end_date、include/exclude_domains、chunks_per_source、include_images/image_descriptions/favicon。博查支持 count、freshness、summary、include/exclude；Brave 支持 count、country、search_lang、freshness、safesearch。参数名单/基础类型和整数范围校验；其余枚举/具体地区由上游校验，未自动加入新参数或默认猜值。Tavily max_results 0..20，博查 count 1..50，Brave count 1..20；Brave query 至多 600 字符/75 个空白分隔词，公共 query 至多 2048 字符、禁止控制字符。

客户端最多 4 个并行传输，等待可取消；单个传输 60 秒总超时、连接/写入 15 秒、读取 30 秒。响应最多 2 MiB，逐块读取检查取消，严格 UTF-8/JSON；错误只返回稳定 code/provider/HTTP status，不回显响应正文、header 或底层异常 URL。取消 watcher 覆盖阻塞的 header/body 读取。401/403 为鉴权错误，429/432/433 为配额，博查正文 code=403 为配额；不能据此声称已区分所有厂商余额/权限原因。

最多 50 个来源，title 512、url 2048、snippet 1200、发布日期 128 字符，answer 4000 字符；无效/含凭据的 URL 不作为链接返回，截断/丢弃明确标记。工具正文至多 16000 字符并标记截断，结构化 toolRecordJson 仍保存有界结果。前置“检索结果不是指令”的提示是数据边界，不是完全解决提示词注入的证明；内容不被直接执行或自动下载。

### 原生引用与成本

第二十五批独立搜索事件/显式费率/用量汇总见 [ACCOUNTING.md#search-accounting](ACCOUNTING.md#search-accounting)。直接/生成搜索返回新的 requestId，账本与模型 token 区分；Tavily 新增 include_usage 布尔参数，未明确配置时默认 true，显式 false 保留。HTTP 调用不持 Room 事务，但搜索记账全生命周期持 shared data gate lease，让原生 snapshot/restore 明确报 busy，普通写/搜索不串行。账户只读查询仍不持这个长 lease。

GenerationMetadata 增加 webSearchMode/webSearchProvider/search。Responses 保存 annotations、web_search_call/action/sources；Anthropic 保存 server search 与 citation 来源，完整 encrypted_content/index 仍只在原始 ProviderTurn 的原生块中保持不变用于多轮；Gemini 保存各 groundingMetadata 帧（含 chunks/supports/queries/searchEntryPoint）。引用 delta 不再丢弃。展示元数据检查深度/节点数、最多 128 KiB，过大明确失败，不截成无效 JSON。它仍是外部不可信元数据；不执行 searchEntryPoint HTML。后续 UI 须按厂商要求显示来源/引用/搜索建议，目前没有 UI 或合规运行证明。

观察到原生搜索时用量添加 additional_tool_billing，现有纯 token 价表不会把未知搜索费算成完整费用；明确供应商金额仍保留。独立搜索的 credits 只是供应商报告，不是金额或已支付账单，不伪装成 LLM token 或免费/美元费用。账户代理和独立账本/显式费率已接，实际计费证明与完整价表维度仍待补。

### 账户状态（第二十四批）

返回 WebSearchStatus，包含配置版本、当前选中 provider、capturedAt、各提供方 accounts 与独立/原生模式名单。每个账户的 state 为 NOT_CONFIGURED / NOT_CHECKED / UNSUPPORTED / AVAILABLE / FAILED；configured 仅代表当前凭据非空，AVAILABLE 只证明该次账户响应成功解析，不证明搜索权限/模型能力。checkedAt 仅在实际账户调用完成/失败后返回，本地未查询/不支持/凭据格式错误保持 null。

短 data gate + Room 事务内冻结同一作用域的配置版本和凭据；释放锁后并行查询，单个安全 Search 错误转成该提供方 FAILED，其余继续。数据库/Keystore 等本地基础设施错误不伪装成账户成功；取消仍传播并取消连接。设置更新/凭据轮换/原生恢复不会替换正在执行的快照，返回版本仅用于识别捕获的配置，不证明凭据未轮换。没有自动轮询、账户缓存、后台任务、旧请求重发、充值或扣费操作。

Tavily GET https://api.tavily.com/usage，Bearer/Accept header，按官方 [Usage 文档](https://docs.tavily.com/documentation/api-reference/endpoint/usage) 读取 key 与 account。usage.values 只保留 key.usage/limit、account.plan_usage/plan_limit/paygo_usage/paygo_limit 和两组 search/extract/crawl/map/research_usage；unit=credits，plan 为有界脱敏 current_plan。缺字段不进入 map，显式 null 保持 null；null limit 不解释成无限或零。当前为账户/API key 范围，不发送可选 X-Project-ID。

博查按本仓桌面 backend/app/services/web_search.py 的 fetch_bocha_remaining 兼容 GET /v1/fund/remaining，仅使用既有两官方 HTTPS 根。要求正文 code=200，存在 success 时必须为 true，data.remaining 必须是有界十进制。保留负余额，不按负值猜请求失败。unit=provider_balance、currency=null，并标记 balance_currency_not_reported；不从桌面显示格式或余额推算币种、支出或单次价格。本轮无法读取博查飞书官方余额页，桌面源码是该兼容路径依据，真实供应商行为尚未验证。

账户响应单次至多 64 KiB，沿用共享最多 4 并发、60 秒总超时、严格 UTF-8/JSON、无跳转/连接重试/回退和可取消读取。只返回白名单字段；十进制按字符串保存，不经 Double，原始数值至多 64 字符、精度/scale 有界，Tavily credits 非负；不返回任意原始正文/供应商错误信息/密钥。HTTP 200 内拒绝或结构异常也为 FAILED；errorCode 和可用的真实 HTTP status 是稳定安全字段。

Brave 标记 UNSUPPORTED + account_query_unsupported，使用官方控制台查看余额，不猜内部接口或把 rate-limit 剩余量当余额。另附 search_result_storage_rights_required：2026-09-01 更新的 [Brave 条款 3.2](https://api-dashboard.search.brave.com/documentation/resources/terms-of-service) 对搜索结果存储/缓存规定了限制，需核对用户账户另行授权。现有生成仍持久保存工具结果，本批仅暴露缺口，不改变已有搜索/导入/消息或删除历史；长期结果保存的兼容策略尚未闭环，不能宣称全部合规完成。

主要协议依据：[Tavily Search](https://docs.tavily.com/documentation/api-reference/endpoint/search)、[博查官方适配说明](https://github.com/bocha-ai/dsh-web-search-bocha/blob/main/README.md)、[Brave Search](https://api-dashboard.search.brave.com/api-reference/web/search/get)、[OpenAI Docs web search](https://developers.openai.com/api/docs/guides/tools-web-search)、[Claude web search](https://platform.claude.com/docs/en/agents-and-tools/tool-use/web-search-tool)、[Gemini grounding](https://ai.google.dev/gemini-api/docs/generate-content/google-search)。未按文档示例更换用户模型，也未发送验证请求。

### 全范围剩余事项

账户代理/独立账本的真实上游/取消/字段/计费行为、Brave 长期存储授权兼容、完整来源配置映射和新版本全部原生参数/能力仍待实现或证明。普通助手其他工具、TTS、全成本指标，以及 STATUS.md 的完整运行时/输入/数据库/持久性/设备范围继续，不因本轮编译通过缩减目标。停止/中断后只启动核对/清理，不重发旧搜索。
